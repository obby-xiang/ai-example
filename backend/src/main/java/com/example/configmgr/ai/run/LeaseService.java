package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 带 epoch 的 Redis 租约（B23L1 批②a / 设计稿 §2.2）——B3 续跑权 fencing 与 L1 资源互斥的<b>共同原语</b>。
 *
 * <h2>语义</h2>
 * <ul>
 * <li><b>值 = {@code <epoch>|<owner>}</b>：epoch 由 {@code INCR ai:lease:epoch} <b>全局</b>发放
 * （单键、无 TTL）。号不回退是 fencing 的前提 —— 键若过期，旧 epoch 会被当成新租约；</li>
 * <li><b>抢</b>（{@link #tryAcquire}）：Lua 内 {@code GET} → 有则<b>原样返回现值</b>（Java 侧据
 * owner 判"可重入 / 占用拒"，两种判定的差异只在这里，Redis 侧不需要第二段脚本）→ 无则
 * {@code INCR} 取号 + {@code SET PX}。整段是一个原子步；</li>
 * <li><b>续</b>（{@link #renew}）与<b>放</b>（{@link #release}）：Lua 内 {@code GET} 比对
 * {@code <epoch>|<owner>} 后再 {@code PEXPIRE} / {@code DEL}。这正是
 * {@code ResumeService#releaseResumeLock}（GET → Java 比对 → DEL）缺陷的根治点：
 * 比对与删除之间租约可能已过期并被别的实例重抢，非原子实现会<b>删掉别人的租约</b>；</li>
 * <li><b>降级</b>（用户裁决 2026-10-21 第 4 条）：Redis 不可用时 {@code tryAcquire} 返回
 * {@link AcquireOutcome#UNAVAILABLE} 并让 {@code leaseOk=false} —— <b>显式降级</b>而非让 NPE 落进
 * 某个 catch；调用方的契约是"拿不到租约 ⇒ 走进程内互斥"（单实例仍然正确）。
 * 不抛异常，是因为租约设施不可用不该把业务动作一起拖垮；</li>
 * <li><b>续租调度</b>（{@link #markDriving}）：内置单线程 {@link ScheduledExecutorService}
 * 按 {@code app.ai.lease.renew-interval}（默认 10s）续租，直到 {@link #release}；
 * 续租失败即放弃进程内视图（下一拍抢租的实例可以接管）。</li>
 * </ul>
 *
 * <h2>所有权口径：谁创建谁释放，重入不释放</h2>
 * 抢租的返回值带 {@code created} 标志（{@link AcquireResult#created()}）：只有<b>真正在 Redis 里
 * 建出这一任</b>的调用方才有释放权与"交调度器续租"的责任。命中现值的一方（同主重入、进程内视图短路）
 * 拿到的只是"我也持有"这一事实 —— 它释放会把<b>创建者</b>的租约删掉，进而让创建者的 fencing 写
 * 全部退化成 plain SET（fence 键不再推进）。用"抢占前 epoch 是多少"来推断创建者身份是错的：
 * 并发同主双抢时两边读到的是同一个前置状态，推断必然同形。
 *
 * <h2>运维前提（M3）</h2>
 * {@code ai:lease:epoch} 是<b>全局单调</b>的取号键，必须落在 <b>{@code noeviction}</b>（至少不被
 * maxmemory 淘汰）的实例上：号一旦被淘汰就会从 1 重新开始，陈旧的 epoch 会被后来者当成"更大的号"，
 * fencing 写与 {@code ai:claim:} 的比对随之失去意义。租约键本身有 TTL，随内存压力淘汰只等价于
 * "这一任租约到期"；<b>只有这个计数器键不能丢</b>。
 *
 * <h2>进程内视图</h2>
 * {@link #heldEpoch} 是"本进程认为自己持有的租约"，只作<b>短路</b>与 fencing 写判据：
 * 可重入因此不会重复 {@code SET}（L3），{@code RunStore.save} 也据此决定走 fence 写还是 plain SET
 * （E3：无租约不拒写）。视图与 Redis 真值冲突时以 Redis 为准（脚本返回值覆盖视图）。
 */
@Slf4j
@Component
public class LeaseService {

	/**
	 * 全局 epoch 计数键（<b>无 TTL</b>）：{@code INCR} 是唯一取号口，单/多实例同口径。
	 *
	 * <p>不做"每租约一族计数器"：fencing 要的是<b>跨键单调可比</b>的号（换主后的写回判据），
	 * 每族独立号会让不同键的 epoch 不可比；全局单键的代价只是一次 {@code INCR}。
	 */
	public static final String EPOCH_KEY = "ai:lease:epoch";

	/** 脚本分派标记（测试替身按它分派；见 {@code FakeLeaseRedis}）。 */
	static final String ACQUIRE_MARK = "LEASE_ACQUIRE";

	static final String RENEW_MARK = "LEASE_RENEW";

	static final String RELEASE_MARK = "LEASE_RELEASE";

	/**
	 * 抢：现值优先（可重入/占用由调用方判 owner），否则取号 + SET PX。
	 *
	 * <p>返回值末段是<b>本次是否由我创建</b>（{@code |1} 创建 / {@code |0} 命中现值）——
	 * 这是"谁创建谁释放"的唯一判据：GET 命中（含同主重入）的一方<b>没有</b>释放权，
	 * 用"epoch 变了没变"去猜会把并发同主双抢的另一方误判成创建者（见 {@link AcquireResult#created()}）。
	 */
	private static final RedisScript<String> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
			-- LEASE_ACQUIRE
			local current = redis.call('GET', KEYS[1])
			if current then
			  return current .. '|0'
			end
			local epoch = redis.call('INCR', KEYS[2])
			local value = tostring(epoch) .. '|' .. ARGV[1]
			redis.call('SET', KEYS[1], value, 'PX', ARGV[2])
			return value .. '|1'
			""", String.class);

	/** 续：现值 = {@code <epoch>|<owner>} 才 PEXPIRE（比对与续期同一个原子步）。 */
	private static final RedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>("""
			-- LEASE_RENEW
			local current = redis.call('GET', KEYS[1])
			if (not current) or current ~= ARGV[1] then
			  return 0
			end
			redis.call('PEXPIRE', KEYS[1], ARGV[2])
			return 1
			""", Long.class);

	/** 校验：现值仍是 {@code <epoch>|<owner>} 才 1（一次往返；只读，不改 TTL/不改值）。 */
	private static final RedisScript<Long> VERIFY_SCRIPT = new DefaultRedisScript<>("""
			-- LEASE_VERIFY
			local current = redis.call('GET', KEYS[1])
			if current == ARGV[1] then
			  return 1
			end
			return 0
			""", Long.class);

	/** 放：现值 = {@code <epoch>|<owner>} 才 DEL（错 epoch 不删别人的租约）。 */
	private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
			-- LEASE_RELEASE
			local current = redis.call('GET', KEYS[1])
			if (not current) or current ~= ARGV[1] then
			  return 0
			end
			redis.call('DEL', KEYS[1])
			return 1
			""", Long.class);

	/**
	 * 抢租的三态结论（I4）：{@code ACQUIRED} 拿到；{@code HELD_ELSEWHERE} 别人持有；
	 * {@code UNAVAILABLE} 设施不可用（Redis 宕机/超时）。
	 *
	 * <p>为什么必须区分后两者：{@code HELD_ELSEWHERE} 的语义是"这一轮已经有主，我什么都不该做"；
	 * {@code UNAVAILABLE} 的语义是"我不知道有没有主，按进程内互斥继续"。把后者报成前者，
	 * 一次 Redis 抖动就会让启动续跑/运维续跑/死卡兜底全线停摆。
	 */
	public enum AcquireOutcome {

		ACQUIRED, HELD_ELSEWHERE, UNAVAILABLE

	}

	/**
	 * 抢租结论（{@code lease} 仅 {@code ACQUIRED} 时非空）。
	 *
	 * @param created <b>本次是否由我创建</b>这份租约（Redis 里那次 {@code INCR + SET} 是我做的）。
	 * {@code false} = 命中现值（同主重入，或进程内视图短路）——<b>没有释放权</b>：
	 * 所有权口径是"谁创建谁释放，重入不释放"。用"epoch 变了没变"来推断是错的
	 * （并发同主双抢时两边都会看到"变了/没变"同形，败方据此还租会删掉胜方的租约）。
	 */
	public record AcquireResult(AcquireOutcome outcome, Lease lease, boolean created) {

		/** 兼容构造（不关心创建者身份的场景：{@code created=false}，即"没有释放权"）。 */
		public AcquireResult(AcquireOutcome outcome, Lease lease) {
			this(outcome, lease, false);
		}

		/** 是否拿到（含可重入）。 */
		public boolean acquired() {
			return this.outcome == AcquireOutcome.ACQUIRED;
		}

	}

	/** 归属校验三态：{@code HELD} 仍持有；{@code LOST} 已被接管/过期；{@code UNAVAILABLE} 设施不可用。 */
	public enum Hold {

		HELD, LOST, UNAVAILABLE

	}

	/** 恒真存活判据（{@link #markDriving(Lease)} 的默认口径：调用方自己负责在合适的时候 release）。 */
	private static final BooleanSupplier ALWAYS_ALIVE = () -> true;

	/** 一份已到手的租约：{@code key} + 号 + 主人。 */
	public record Lease(String key, long epoch, String owner) {

		/** Redis 里的值形态（续/放/比对都用它）。 */
		public String token() {
			return this.epoch + "|" + this.owner;
		}

	}

	/**
	 * 续租调度缝（默认实现 = 单线程 {@link ScheduledExecutorService}）。
	 *
	 * <p>之所以是接口而不是直接捏一个 executor：续租是"到点才发生"的行为，用例要断言
	 * "按 10s 的间隔续一次、release 之后不再续"，只能靠可控 tick（真线程要 sleep 真实时间，
	 * 慢且不稳）。生产路径不受影响：默认实现就是 {@code scheduleAtFixedRate}。
	 */
	public interface RenewScheduler {

		AutoCloseable schedule(Runnable task, Duration interval);

	}

	private final StringRedisTemplate redis;

	private final AiProperties properties;

	private final RenewScheduler scheduler;

	private final ScheduledExecutorService ownExecutor;

	private final String instanceId;

	/** 进程内所有权视图（key → 已到手的租约）。 */
	private final Map<String, Lease> held = new ConcurrentHashMap<>();

	/** 正在续租的租约句柄（key → 关闭器）。 */
	private final Map<String, AutoCloseable> driving = new ConcurrentHashMap<>();

	@Autowired
	public LeaseService(StringRedisTemplate redis, AiProperties properties) {
		this(redis, properties, new DefaultRenewScheduler(), InstanceId.get());
	}

	/**
	 * 测试/局部装配用（可注入续租调度缝与实例标识）。
	 *
	 * <p>生产装配走 {@link #LeaseService(StringRedisTemplate, AiProperties)}（{@code @Autowired}）。
	 * 本构造器对包外开放，是为了让跨包用例（{@code gate/ConfirmGateLeaseTest}）能用真实语义
	 * （真 epoch、真脚本比对）验证确认门侧的接线，而不是拿 mock 自证。
	 */
	public LeaseService(StringRedisTemplate redis, AiProperties properties, RenewScheduler scheduler,
			String instanceId) {
		this.redis = redis;
		this.properties = properties;
		this.scheduler = scheduler;
		this.instanceId = instanceId;
		this.ownExecutor = scheduler instanceof DefaultRenewScheduler defaultScheduler ? defaultScheduler.executor : null;
	}

	/** 本实例标识（与 {@link RunStore#instanceId()} 同源）。 */
	public String instanceId() {
		return this.instanceId;
	}

	// ── 抢 / 续 / 放 ────────────────────────────────────────────────────────

	/**
	 * 抢一份租约（只问"拿到没有"的薄包装，语义与三态见 {@link #acquire(String, Duration)}）。
	 *
	 * <p>调用方若需要区分"别人持有"与"设施不可用"，用 {@code acquire(...)} —— 把后者当"别人持有"
	 * 会让一次 Redis 抖动变成"全线拒绝续跑"（I4）。
	 *
	 * @return 拿到（新抢或可重入）返回租约；被占用 / Redis 不可用返回 empty
	 */
	public Optional<Lease> tryAcquire(String key, Duration ttl) {
		return Optional.ofNullable(acquire(key, ttl).lease());
	}

	/**
	 * 抢一份租约（<b>三态</b>：拿到 / 别人持有 / 设施不可用，见 {@link AcquireOutcome}）。
	 *
	 * <p>可重入走<b>进程内视图短路</b>（不复查 Redis TTL）：TTL 到期被别人接管的那一侧，
	 * 由"fencing 写被拒"（{@code RunStore#save} 的 STALE_EPOCH）与"续租失败即放弃视图"
	 * （{@link #markDriving}）兜底 —— 短路的代价至多是一个续租间隔（10s），换来的是热路径零往返。
	 */
	public AcquireResult acquire(String key, Duration ttl) {
		Duration effective = ttl == null || ttl.isZero() || ttl.isNegative() ? this.properties.getLease().getTtl() : ttl;
		Lease already = this.held.get(key);
		if (already != null) {
			// 可重入短路：同实例重复 acquire 不得再 SET（L3），也不消耗新 epoch。
			// created=false：租约不是这次调用创建的 ⇒ 本次调用没有释放权。
			return new AcquireResult(AcquireOutcome.ACQUIRED, already, false);
		}
		try {
			String raw = this.redis.execute(ACQUIRE_SCRIPT, List.of(key, EPOCH_KEY), this.instanceId,
					String.valueOf(effective.toMillis()));
			Lease current = parse(key, raw);
			if (current == null) {
				log.warn("租约脚本返回空值 key={}（按占用处理，不吞并别人的租约）", key);
				return new AcquireResult(AcquireOutcome.HELD_ELSEWHERE, null);
			}
			if (!this.instanceId.equals(current.owner())) {
				return new AcquireResult(AcquireOutcome.HELD_ELSEWHERE, null);
			}
			// 双保险：脚本返回的现值就是本次取号的结果，或是"视图丢失但 owner 仍是自己"的重入
			this.held.put(key, current);
			// 只有脚本说"是我建的"才算创建者（并发同主双抢时，后到的那个 owner 也是自己，但不是创建者）
			return new AcquireResult(AcquireOutcome.ACQUIRED, current, createdFlag(raw));
		}
		catch (Exception ex) {
			log.warn("租约抢用降级 key={}（Redis 不可用 ⇒ 调用方走进程内互斥）：{}", key, ex.getMessage());
			return new AcquireResult(AcquireOutcome.UNAVAILABLE, null);
		}
	}

	/**
	 * 校验"本实例是否<b>仍然</b>持有该 epoch"（I3）：一次往返，比对在 Lua 内完成。
	 *
	 * <p>与 {@link #heldEpoch(String)} 的分工：{@code heldEpoch} 是<b>进程内视图</b>（零往返，但可能在
	 * "租约已过期被接管、续租还没失败"的窗口里说谎）；本方法是<b>对着 Redis 的真值校验</b>，
	 * 用在"入口处决定要不要驱动这一轮"这种一次性的判定上（续跑准入），不放在热路径。
	 *
	 * <p>校验失败即作废进程内视图（别再拿它当 fencing 依据）；设施不可用返回
	 * {@link Hold#UNAVAILABLE}，由调用方按"无租约降级"处理。
	 */
	public Hold verifyHeld(String key, long epoch) {
		if (key == null || epoch <= 0L) {
			return Hold.LOST;
		}
		try {
			Long held = this.redis.execute(VERIFY_SCRIPT, List.of(key), epoch + "|" + this.instanceId);
			if (held != null && held > 0L) {
				return Hold.HELD;
			}
			// 现值已不是自己那一任：只摘<b>匹配的那一任</b>（R3）—— 若视图已经换成更新的 epoch，
			// 这次校验说明的是"被校验的那一任没了"，不能顺手把当前那一任也作废。
			Lease local = this.held.get(key);
			if (local != null && local.epoch() == epoch) {
				this.held.remove(key, local);
			}
			return Hold.LOST;
		}
		catch (Exception ex) {
			log.warn("租约归属校验降级 key={} epoch={}（Redis 不可用 ⇒ 按无租约处理）：{}", key, epoch, ex.getMessage());
			return Hold.UNAVAILABLE;
		}
	}

	/** 续租：只有现值仍是自己那一任（epoch + owner）才续。 */
	public boolean renew(Lease lease, Duration ttl) {
		if (lease == null) {
			return false;
		}
		Duration effective = ttl == null || ttl.isZero() || ttl.isNegative() ? this.properties.getLease().getTtl() : ttl;
		try {
			Long renewed = this.redis.execute(RENEW_SCRIPT, List.of(lease.key()), lease.token(),
					String.valueOf(effective.toMillis()));
			return renewed != null && renewed > 0L;
		}
		catch (Exception ex) {
			log.debug("租约续期失败 key={} epoch={}：{}", lease.key(), lease.epoch(), ex.getMessage());
			return false;
		}
	}

	/** 释放：只有现值仍是自己那一任才删（错 epoch 不删别人的租约）。 */
	public boolean release(Lease lease) {
		if (lease == null) {
			return false;
		}
		try {
			Long released = this.redis.execute(RELEASE_SCRIPT, List.of(lease.key()), lease.token());
			// R3：<b>条件删除</b> —— 只有"本进程的视图仍是这一任"才摘视图、才停续租任务。
			// 旧实现入口就无条件 stopDriving、失败分支无条件 held.remove(key)：一个迟到的
			// release(旧租约) 会把本进程<b>当前</b>那一任的视图与续租任务一起误杀（新主人于是
			// 拿不回 fencing 依据、续租也停了）。"错 epoch 不删别人的键"由脚本保证，
			// "错 epoch 不动别人的进程内状态"由这里的条件删除保证。
			if (this.held.remove(lease.key(), lease)) {
				stopDriving(lease.key());
			}
			return released != null && released > 0L;
		}
		catch (Exception ex) {
			log.debug("租约释放失败 key={} epoch={}：{}", lease.key(), lease.epoch(), ex.getMessage());
			return false;
		}
	}

	/** 释放"本进程持有的"租约（未持有返回 false，不触 Redis）。 */
	public boolean releaseIfHeld(String key) {
		Lease lease = key == null ? null : this.held.get(key);
		return lease != null && release(lease);
	}

	/** 进程内所有权视图（0 = 未持有）。fencing 写与 {@code ai:claim:} 值都读它。 */
	public long heldEpoch(String key) {
		Lease lease = key == null ? null : this.held.get(key);
		return lease == null ? 0L : lease.epoch();
	}

	/** 进程内持有的租约（未持有返回 empty）。 */
	public Optional<Lease> heldLease(String key) {
		return Optional.ofNullable(key == null ? null : this.held.get(key));
	}

	// ── 续租调度 ────────────────────────────────────────────────────────────

	/** 开始按 {@code renew-interval} 续租（同一个 key 只登记一个任务，重复调用无副作用）。 */
	public void markDriving(Lease lease) {
		markDriving(lease, ALWAYS_ALIVE);
	}

	/**
	 * 开始续租，但每一拍先问一句"这一轮还需要我活着吗"（I7 僵尸续租）。
	 *
	 * <p>为什么需要存活判据：续租任务是不看业务的定时器。驱动线程死掉/轮次已收尾却没走到
	 * {@code RunStore.save}（终态释放）时，定时器会把租约<b>永远</b>续下去 —— 别的实例既接不了管，
	 * 运维也看不出"这个租约其实已经没有主人"。判据为假 ⇒ 停续租 + 释放（epoch scoped）。
	 *
	 * @param alive 由调用方给出的"仍有驱动者"判据（例如"快照非终态 且 本进程仍有该轮的驱动标记"）；
	 * {@code null} 等价于恒真（兼容既有调用）
	 */
	public void markDriving(Lease lease, BooleanSupplier alive) {
		if (lease == null) {
			return;
		}
		BooleanSupplier check = alive == null ? ALWAYS_ALIVE : alive;
		Duration interval = this.properties.getLease().getRenewInterval();
		// R9：登记用 computeIfAbsent（原子）—— 旧实现是"containsKey 判空 → schedule → put"，
		// 两个线程同时来到会各自排一个续租任务，后 put 的那个把先前的 handle 覆盖成<b>孤儿任务</b>
		// （永远取消不掉，release 之后还在续租）。
		this.driving.computeIfAbsent(lease.key(),
				key -> this.scheduler.schedule(() -> renewTick(lease, check), interval));
	}

	/** 停掉该 key 的续租任务（未在续租则无操作）。 */
	public void stopDriving(String key) {
		AutoCloseable handle = key == null ? null : this.driving.remove(key);
		if (handle == null) {
			return;
		}
		try {
			handle.close();
		}
		catch (Exception ex) {
			log.debug("停止续租任务失败 key={}：{}", key, ex.getMessage());
		}
	}

	private void renewTick(Lease lease, BooleanSupplier alive) {
		try {
			if (!alive.getAsBoolean()) {
				log.info("续租判据转假，停续租并归还租约 key={} epoch={}（驱动者已不在：轮终态/线程收尾）",
						lease.key(), lease.epoch());
				release(lease);
				stopDriving(lease.key());
				return;
			}
			if (renew(lease, this.properties.getLease().getTtl())) {
				return;
			}
			log.warn("续租失败，放弃本进程的租约视图 key={} epoch={}（另一实例可以接管）", lease.key(), lease.epoch());
			this.held.remove(lease.key(), lease);
			stopDriving(lease.key());
		}
		catch (Exception ex) {
			// 续租是后台动作：单拍失败不杀任务，下一拍再试（TTL 三倍于间隔，容忍两拍失败）
			log.debug("续租任务异常 key={} epoch={}：{}", lease.key(), lease.epoch(), ex.getMessage());
		}
	}

	// ── 健康度 ──────────────────────────────────────────────────────────────

	/**
	 * 租约设施自检（{@code /health} 的 {@code leaseOk} 观测面）。
	 *
	 * <p>每次调用都真的打一次 {@code PING}：报告的是"现在能不能用"，而不是"上一次用的时候行不行"
	 * （Redis 恢复后需要能自己回绿）。
	 */
	public boolean leaseOk() {
		try {
			String pong = this.redis.execute((RedisCallback<String>) RedisConnection::ping);
			return pong != null && "PONG".equalsIgnoreCase(pong);
		}
		catch (Exception ex) {
			log.debug("租约探针失败：{}", ex.getMessage());
			return false;
		}
	}

	@PreDestroy
	void shutdown() {
		for (String key : List.copyOf(this.driving.keySet())) {
			stopDriving(key);
		}
		// 优雅停机时把本进程仍持有的租约还掉：等 TTL 到期会让接管方白等一个续租周期
		for (String key : List.copyOf(this.held.keySet())) {
			releaseIfHeld(key);
		}
		if (this.ownExecutor != null) {
			this.ownExecutor.shutdownNow();
		}
	}

	// ── 内部 ────────────────────────────────────────────────────────────────

	/** {@code <epoch>|<owner>} → 租约；格式不合法返回 null（调用方按占用处理）。 */
	private static Lease parse(String key, String raw) {
		if (raw == null) {
			return null;
		}
		// 形态：{@code <epoch>|<owner>} 或 {@code <epoch>|<owner>|<created>}
		String[] parts = raw.split(java.util.regex.Pattern.quote("|"), -1);
		if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
			return null;
		}
		try {
			return new Lease(key, Long.parseLong(parts[0]), parts[1]);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	/** 抢租脚本返回值的"是否由我创建"段（{@code |1} = 创建；缺段/旧形态一律 false）。 */
	private static boolean createdFlag(String raw) {
		if (raw == null) {
			return false;
		}
		int last = raw.lastIndexOf('|');
		return last > 0 && raw.length() > last + 1 && raw.charAt(last + 1) == '1'
				&& raw.indexOf('|') != last;
	}

	/**
	 * 默认续租调度：单线程 + 守护线程（不阻止 JVM 退出），首次续租在 {@code interval} 之后。
	 */
	static final class DefaultRenewScheduler implements RenewScheduler {

		private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "ai-lease-renew");
			thread.setDaemon(true);
			return thread;
		});

		@Override
		public AutoCloseable schedule(Runnable task, Duration interval) {
			long millis = Math.max(1L, interval.toMillis());
			var future = this.executor.scheduleAtFixedRate(task, millis, millis, TimeUnit.MILLISECONDS);
			return () -> future.cancel(false);
		}

	}

}
