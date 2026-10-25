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
 * <li><b>值 = {@code <epoch>|<owner>}</b>：号来自<b>与租约键同槽</b>的计数器
 * （{@code ai:lease:epoch:{run:<runId>}}，见 {@link #EPOCH_PREFIX} / {@link #epochKeyFor}），
 * {@code INCR} 与 {@code SET} 在<b>同一条 Lua</b> 里完成 —— 号与占用必然同序
 * （评审 I-1：取号若在脚本外，迟到者可能用比 fence 更小的号占住租约 ⇒ 这一轮永久 500、无自愈）。
 * 号跨进程单调由 Redis 的 {@code INCR} 保证，不依赖墙钟，也不依赖任何"每族一个号"的假设
 * —— 全仓的 epoch 比较只发生在<b>同一个 run 内</b>（{@code fence ≤ epoch}），所以 per-run 计数器语义等价；</li>
 * <li><b>抢</b>（{@link #tryAcquire} / {@link #acquireFenced}）：<b>三键</b> Lua（租约 / 计数器 / fence）
 * 内 {@code GET} → 命中<b>有效</b>现值（epoch ≥ fence）则原样返回（Java 侧据 owner 判
 * "可重入 / 占用拒"）并顺手补齐计数器；命中<b>陈旧</b>现值（epoch &lt; fence）或无现值则
 * {@code INCR} 取号、必要时抬到 {@code fence+1}，再 {@code SET PX}。整段是一个原子步
 * （评审 N-1：计数器先死也能自愈）；无 hash tag 的租约（资源锁）走单键变体 + 脚本外取号
 * （它们的 epoch 不参与 fencing）；</li>
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
 * <ul>
 * <li><b>run 租约</b>：号源是 per-slot 计数器，<b>三键脚本会在取号时与 fence 对账</b>
 * （{@code epoch = max(INCR, fence + 1)}，见 {@link #ACQUIRE_SCRIPT}）⇒ 计数器先过期、
 * 被 maxmemory 淘汰、甚至迁移时没搬，下一次 acquire 都会自愈。因此
 * <b>不再需要</b>"某个全局键必须 noeviction"这条硬约束，也<b>不需要</b>迁移前预置任何全局 epoch；</li>
 * <li><b>资源锁</b>：仍用旧全局键 {@link #EPOCH_KEY}。它只作 token 的可读编号，不参与比较，
 * 因此即便被淘汰也不会让任何写回失去保护。</li>
 * </ul>
 *
 * <h2>进程内视图</h2>
 * {@link #heldEpoch} 是"本进程认为自己持有的租约"，只作<b>短路</b>与 fencing 写判据：
 * 可重入因此不会重复 {@code SET}（L3）。写权限本身由 {@link #authority} 的四态给出
 * （{@code HELD / LOST / UNAVAILABLE / NEVER_HELD}），{@code RunStore.save} 不再按
 * {@code epoch == 0} 判"可 plain 写"。视图与 Redis 真值冲突时以 Redis 为准（脚本返回值覆盖视图）。
 *
 * <h2>owner 维度（B23L1 批③a；裁决 7）</h2>
 * 租约的持有者不再隐含"＝实例标识"：{@link #acquire(String, Duration, String)} 允许调用方显式给出
 * owner（L1 资源互斥按<b>任务/作业</b>记主，同一实例内两个不同的业务持有者必须能区分）。
 * 进程内视图与续租表按 {@code key + "\0" + owner} 记账 —— 同一个 Redis 键上，<b>不同 owner 是不同租约</b>
 * （后者 OCCUPIED / {@link AcquireOutcome#HELD_ELSEWHERE}），同一个 owner 才是重入。
 * owner 缺省的旧方法全部等价于 {@code owner = instanceId}，批②的语义与用例<b>零变化</b>。
 */
@Slf4j
@Component
public class LeaseService {

	/**
	 * 旧<b>全局</b>取号键：<b>不再参与 fencing</b>。
	 *
	 * <p>它只服务"没有 hash tag、也不需要同槽"的租约（{@code ai:lock:task|job:*} 资源锁）——
	 * 这些租约的 epoch 只作 token 的一部分（续/放按整值比对），从不与任何 fence 比大小，
	 * 所以"取号在脚本外"留下的乱序窗口对它们无害。
	 * 带 tag 的租约（run 租约）改用 {@link #EPOCH_PREFIX} 下与租约键<b>同槽</b>的计数器。
	 *
	 * <p>留着它是为了运维可观测（一眼看到"总发放量"）与兼容既有诊断；它<b>不许</b>再被当作
	 * fencing 的号源 —— 号一旦与 fence 不同槽/不同源，就会出现"晚到者号更小"的卡死（评审 I-1）。
	 */
	public static final String EPOCH_KEY = "ai:lease:epoch";

	/**
	 * <b>per-slot</b> 取号键前缀（评审 I-1 的根治）：{@code ai:lease:epoch:{run:<runId>}}。
	 *
	 * <p>键名 = 本前缀 + 租约键里的 hash tag ⇒ 与租约键<b>同槽</b>（Cluster 下才能放进同一条
	 * {@code EVAL}），于是 {@code INCR} 与 {@code SET} 回到<b>同一个原子步</b>：号与占用必然同序，
	 * "取号后被挂起、醒来用更小的号占住租约"这个窗口在结构上不再存在。
	 */
	public static final String EPOCH_PREFIX = "ai:lease:epoch:";

	/** 脚本分派标记（测试替身按它分派；见 {@code FakeLeaseRedis}）。 */
	static final String ACQUIRE_MARK = "LEASE_ACQUIRE";

	/** 无 tag 租约（资源锁）的抢租标记：取号在脚本外（全局计数器），与带 tag 的脚本区分。 */
	static final String ACQUIRE_UNTAGGED_MARK = "LEASE_ACQUIRE_UNTAGGED";

	/** 无 fence 的带 tag 租约（两键）的抢租标记。 */
	static final String ACQUIRE_NOFENCE_MARK = "LEASE_ACQUIRE_NOFENCE";

	static final String RENEW_MARK = "LEASE_RENEW";

	static final String RELEASE_MARK = "LEASE_RELEASE";

	/**
	 * 抢：<b>三键一条 Lua</b>（KEYS = 租约键 / 计数器 / fence，三者同槽）。
	 *
	 * <h2>为什么必须三键（评审 N-1）</h2>
	 * 两键版（租约+计数器）挡住了"取号与占用不同序"，但挡不住<b>计数器比 fence 先死</b>：
	 * {@code fence} 会被每笔 {@code save} 的 {@code FENCED_SET} 反复续期，计数器只有取号/读侧才被动到
	 * ⇒ 长跑 24h 或 maxmemory 淘汰后，计数器从 1 重来，残留 fence（比如 40）把这一轮<b>永久拒写</b>。
	 * 把 fence 拉进同一个原子步，脚本就能在取号时对账（"号至少要比 fence 大"），
	 * 于是 TTL 差、淘汰、重入、迁移预置这四类入口一次消掉。
	 *
	 * <h2>语义</h2>
	 * <ol>
	 * <li><b>命中的是有效租约</b>（现值 epoch ≥ fence）：原样返回（Java 侧按 owner 判"同主重入 /
	 * 他主占用"），<b>不推进</b>号；同时把计数器补到 ≥ 该 epoch 并刷新它的 TTL —— 计数器若已过期，
	 * 这次重入就把它救回来（否则下一次真正的取号仍从 1 起）；</li>
	 * <li><b>无租约 / 陈旧租约</b>（现值 epoch &lt; fence，本来就写不进去）：{@code INCR} 取号，
	 * 若仍 ≤ fence 则抬到 {@code fence+1} 并写回计数器，再写新租约。⇒ 陈旧租约被新号替换，
	 * 有效高号租约永远不会被覆盖（它走第 1 条）；</li>
	 * <li><b>fence / 计数器缺失</b>：都按 0 处理（{@code tonumber(...) or 0}），全新一轮从 1 起；</li>
	 * <li><b>SET 失败</b>（OOM 等）：Lua 无回滚，号已推进 —— 只跳号，不影响单调性（下一位取号更大）。</li>
	 * </ol>
	 *
	 * <p>返回值末段是<b>本次是否由我创建</b>（{@code |1} 创建 / {@code |0} 命中现值）——
	 * 这是"谁创建谁释放"的唯一判据：GET 命中（含同主重入）的一方<b>没有</b>释放权。
	 *
	 * @param KEYS[1] 租约键
	 * @param KEYS[2] 与 KEYS[1] 同槽的计数器键
	 * @param KEYS[3] 与 KEYS[1] 同槽的 fence 键（{@code ai:fence:run:{run:<runId>}}）
	 * @param ARGV[1] owner
	 * @param ARGV[2] 租约 TTL（毫秒）
	 * @param ARGV[3] 计数器 TTL（毫秒；与 fence 同寿，见 {@code RunStore} 的续期面）
	 */
	private static final RedisScript<String> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
			-- LEASE_ACQUIRE
			local current = redis.call('GET', KEYS[1])
			local fence = tonumber(redis.call('GET', KEYS[3])) or 0
			if current then
			  local sep = string.find(current, '|', 1, true)
			  local curEpoch = sep and tonumber(string.sub(current, 1, sep - 1)) or 0
			  if curEpoch >= fence then
			    local counter = tonumber(redis.call('GET', KEYS[2])) or 0
			    if counter < curEpoch then
			      redis.call('SET', KEYS[2], tostring(curEpoch))
			    end
			    redis.call('PEXPIRE', KEYS[2], ARGV[3])
			    return current .. '|0'
			  end
			end
			local epoch = redis.call('INCR', KEYS[2])
			if epoch <= fence then
			  epoch = fence + 1
			  redis.call('SET', KEYS[2], tostring(epoch))
			end
			redis.call('PEXPIRE', KEYS[2], ARGV[3])
			local value = tostring(epoch) .. '|' .. ARGV[1]
			redis.call('SET', KEYS[1], value, 'PX', ARGV[2])
			return value .. '|1'
			""", String.class);

	/**
	 * 抢：<b>两键</b>（租约 + 计数器，<b>不带 fence</b>）—— 无 fence 的带 tag 租约。
	 *
	 * <p>只保留给"没有 fence 概念"的调用方（测试替身/未来其它带 tag 的租约）。它保住了
	 * "取号与占用同序"，但<b>不能</b>在计数器先死时自愈（没有 fence 可对账）。
	 * run 租约<b>一律</b>走 {@link #acquireFenced(String, String, Duration, String)}。
	 */
	private static final RedisScript<String> ACQUIRE_NOFENCE_SCRIPT = new DefaultRedisScript<>("""
			-- LEASE_ACQUIRE_NOFENCE
			local current = redis.call('GET', KEYS[1])
			if current then
			  local counter = tonumber(redis.call('GET', KEYS[2])) or 0
			  local sep = string.find(current, '|', 1, true)
			  local curEpoch = sep and tonumber(string.sub(current, 1, sep - 1)) or 0
			  if counter < curEpoch then
			    redis.call('SET', KEYS[2], tostring(curEpoch))
			  end
			  redis.call('PEXPIRE', KEYS[2], ARGV[3])
			  return current .. '|0'
			end
			local epoch = redis.call('INCR', KEYS[2])
			redis.call('PEXPIRE', KEYS[2], ARGV[3])
			local value = tostring(epoch) .. '|' .. ARGV[1]
			redis.call('SET', KEYS[1], value, 'PX', ARGV[2])
			return value .. '|1'
			""", String.class);

	/**
	 * 抢：<b>无 hash tag 的租约</b>（{@code ai:lock:task|job:*}）—— 单键 GET/SET，取号在 Java 侧。
	 *
	 * <p>这类租约的 epoch <b>只作 token 的一部分</b>（续/放按 {@code <epoch>|<owner>} 整值比对），
	 * 全仓没有任何地方拿它与 fence 比大小，所以"号与占用不同序"不产生卡死面；它们的键没有 tag
	 * （按设计不加），与全局计数器天然不同槽 —— 放进同一条 {@code EVAL} 就是 CROSSSLOT。
	 */
	private static final RedisScript<String> ACQUIRE_UNTAGGED_SCRIPT = new DefaultRedisScript<>("""
			-- LEASE_ACQUIRE_UNTAGGED
			local current = redis.call('GET', KEYS[1])
			if current then
			  return current .. '|0'
			end
			redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
			return ARGV[1] .. '|1'
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

	/**
	 * 续租三态（续租 fencing 收口批）：{@code RENEWED} 续到了；{@code LOST} <b>确定</b>丢租
	 * （Lua 返回 0：现值已不是自己那一任）；{@code UNAVAILABLE} 设施不可用（异常，不知道还在不在）。
	 *
	 * <p>三者必须分开：{@code LOST} 是"别人已经接管，再写就是覆盖新主人"，{@code UNAVAILABLE}
	 * 是"这一拍问不出来"。两者都要求<b>停止推进</b>（只读），但恢复路径不同：
	 * 前者要重新抢租，后者下一拍续上即可复工。
	 */
	public enum RenewOutcome {

		RENEWED, LOST, UNAVAILABLE

	}

	/**
	 * <b>写权限的权威判定</b>（续租 fencing 收口批）：{@link com.example.configmgr.ai.run.RunStore#save}
	 * 与出帧/认领/台账的唯一判据。
	 *
	 * <h2>为什么不能用 {@code epoch == 0} 判"可写"</h2>
	 * epoch=0 同时表示两件相反的事：<b>从未持租</b>（单实例/降级/首轮）与<b>持过但已丢租</b>
	 * （续租返回 0 后进程内视图被清）。旧实现把两者都当"无租约 ⇒ plain SET"，于是丢租的 A
	 * 能把自己迟到的终态盖到新主人 B 的 DONE 上 —— fencing 在最后一笔写回处失效。
	 *
	 * <h2>四态</h2>
	 * <ul>
	 * <li>{@link #HELD}：仍持租 ⇒ 按 epoch 走 fence 写；</li>
	 * <li>{@link #LOST}：确定丢租 ⇒ <b>只读</b>（停快照写/取号落档/新工具认领/终帧）；</li>
	 * <li>{@link #UNAVAILABLE}：设施异常，无法确认 ⇒ <b>只读不推进</b>（恢复后可回到 HELD）；</li>
	 * <li>{@link #NEVER_HELD}：本进程从未持有这一轮，且设施能自证正常 ⇒ 原普通写。</li>
	 * </ul>
	 */
	public enum LeaseAuthority {

		HELD, LOST, UNAVAILABLE, NEVER_HELD;

		/** 是否必须停止推进（丢租或无法确认）。 */
		public boolean readOnly() {
			return this == LOST || this == UNAVAILABLE;
		}

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

	/** 进程内所有权视图（{@code key + "\0" + owner} → 已到手的租约）。 */
	private final Map<String, Lease> held = new ConcurrentHashMap<>();

	/** 正在续租的租约句柄（{@code key + "\0" + owner} → 关闭器）。 */
	private final Map<String, AutoCloseable> driving = new ConcurrentHashMap<>();

	/**
	 * 已结束的租约状态（{@code key + "\0" + owner} → {@link LeaseAuthority#LOST} /
	 * {@link LeaseAuthority#UNAVAILABLE}）。
	 *
	 * <p>为什么必须留"墓碑"：丢租/不可用时 {@link #held} 里的视图会被移除（或被保留等待恢复），
	 * 若只看 {@code heldEpoch == 0} 就会把"持过但已丢"误判成"从未持租"，写权限随之放大。
	 * 墓碑只由成功 {@code acquire}（同 owner 重新持有）或成功 {@code release}（自愿归还）清除。
	 *
	 * <h2>生命周期边界（如实登记）</h2>
	 * <ul>
	 * <li>清除通道只有两条：成功 acquire / 成功 release（见上）；进程退出由 {@code shutdown()} 清空；</li>
	 * <li><b>没有</b> TTL 到期或容量淘汰：任何"自动放行"都会把 LOST 重新变成可写（plain SET），
	 * 正是 fencing 要消灭的形态；有界性由"每个 (run, owner) 至多一条 + 只对本进程丢过的租约记账"
	 * 保证 —— 量级 = 本进程参与过且丢租的轮次数，不是会话/轮次总数；</li>
	 * <li>{@link com.example.configmgr.ai.run.RunStore#deleteRun} <b>不</b>清墓碑：它只释放"仍持有"
	 * 的租约（{@code releaseIfHeld}）；丢租的 run 已无视图可放。删键之后仍可能有陈旧写者
	 * （旧驱动线程）尝试 plain 写，清墓碑等于给它重新开门。当前生产代码没有会话销毁路径调用
	 * {@code deleteRun}（仅测试/运维清理），故这里选择<b>保留墓碑</b>而不是为省内存牺牲 fencing。</li>
	 * </ul>
	 */
	private final Map<String, LeaseAuthority> ended = new ConcurrentHashMap<>();

	/**
	 * 租约设施的最近观测健康度（{@link #authority} 的"从未持租"分支用）。
	 *
	 * <p>任何一次 Redis 异常都会把它置 false；任何一次成功命令置 true。false 时
	 * {@link #facilityHealthy()} 会做一次有节流的 {@code PING} 自证 —— 正常路径零额外往返。
	 */
	private volatile boolean facilityHealthy = true;

	/** 上次 {@code PING} 自证的时刻（毫秒；{@link #facilityHealthy()} 的节流锚点）。 */
	private final java.util.concurrent.atomic.AtomicLong lastHealthProbeMs = new java.util.concurrent.atomic.AtomicLong();

	/** 上次 {@code PING} 自证的结论（节流窗口内复用）。 */
	private volatile boolean lastProbeOk = true;

	/** 设施健康自证的最小间隔（毫秒）：避免断连时每帧/每笔写都等一次 PING 超时。 */
	private static final long HEALTH_PROBE_INTERVAL_MS = 1000L;

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

	/** 带 owner 的 {@link #tryAcquire(String, Duration)}（L1 资源互斥：同一实例内的不同持有者互斥）。 */
	public Optional<Lease> tryAcquire(String key, Duration ttl, String owner) {
		return Optional.ofNullable(acquire(key, ttl, owner).lease());
	}

	/**
	 * 抢一份租约（<b>三态</b>：拿到 / 别人持有 / 设施不可用，见 {@link AcquireOutcome}）。
	 *
	 * <p>可重入走<b>进程内视图短路</b>（不复查 Redis TTL）：TTL 到期被别人接管的那一侧，
	 * 由"fencing 写被拒"（{@code RunStore#save} 的 STALE_EPOCH）与"续租失败即放弃视图"
	 * （{@link #markDriving}）兜底 —— 短路的代价至多是一个续租间隔（10s），换来的是热路径零往返。
	 */
	public AcquireResult acquire(String key, Duration ttl) {
		return acquire(key, ttl, this.instanceId);
	}

	/**
	 * 抢一份租约，持有者由调用方指定（{@code owner} 缺省 = 本实例标识）。
	 *
	 * <p>语义与 epoch / created 判据与 {@link #acquire(String, Duration)} 完全一致，区别只在
	 * "谁"：进程内视图按 {@code key + "\0" + owner} 记账，脚本返回的现值也按 {@code owner} 比对 ——
	 * 同一 Redis 键上 owner 不同即"别人持有"（{@link AcquireOutcome#HELD_ELSEWHERE}），
	 * 只有 owner 相同才算重入（{@code created=false}，没有释放权）。
	 */
	public AcquireResult acquire(String key, Duration ttl, String owner) {
		return acquire(key, null, ttl, owner);
	}

	/** 带 fence 的 {@link #acquireFenced(String, String, Duration, String)}（owner 缺省 = 本实例标识）。 */
	public AcquireResult acquireFenced(String key, String fenceKey, Duration ttl) {
		return acquireFenced(key, fenceKey, ttl, this.instanceId);
	}

	/**
	 * 抢租，<b>带上该租约的 fence 键</b>（run 租约的唯一生产入口）：三键同槽原子自愈（评审 N-1）。
	 *
	 * <p>fence 键必须与租约键<b>同槽</b>（同一 {@code {run:<runId>}} tag），否则脚本本身就是 CROSSSLOT。
	 * {@code fenceKey == null} 等价于无 fence 口径（两键脚本）；无 tag 的租约（资源锁）忽略 fence 参数
	 * —— 它们没有同槽的 fence，硬塞进来就是 CROSSSLOT。
	 */
	public AcquireResult acquireFenced(String key, String fenceKey, Duration ttl, String owner) {
		return acquire(key, fenceKey, ttl, owner);
	}

	/** 带 fence 的薄包装（owner 缺省 = 本实例标识；语义同 {@link #acquireFenced}）。 */
	public Optional<Lease> tryAcquireFenced(String key, String fenceKey, Duration ttl) {
		return tryAcquireFenced(key, fenceKey, ttl, this.instanceId);
	}

	/** 带 fence 的薄包装（语义同 {@link #acquireFenced}，只问"拿到没有"）。 */
	public Optional<Lease> tryAcquireFenced(String key, String fenceKey, Duration ttl, String owner) {
		return Optional.ofNullable(acquireFenced(key, fenceKey, ttl, owner).lease());
	}

	private AcquireResult acquire(String key, String fenceKey, Duration ttl, String owner) {
		String epochKey = epochKeyFor(key);
		if (epochKey == null && fenceKey != null) {
			// 编程错误（不是运行时降级面）：无 tag 的租约键与 fence 不同槽，同脚本 = CROSSSLOT。
			// 放在 try 之外，避免被下面的 catch 折成 UNAVAILABLE 静默吞掉。
			throw new IllegalArgumentException("无 hash tag 的租约不接受 fence 参数（会跨槽）：key=" + key
					+ " fence=" + fenceKey);
		}
		if (fenceKey != null) {
			// 入口闸（评审 Minor 1）：fence 必须与租约键<b>同 tag ⇒ 同槽</b>。传错 run 的 fence 会让三键脚本
			// 跨槽 —— 真集群回 CROSSSLOT，而 CROSSSLOT 会被本方法的 catch 折成 UNAVAILABLE
			// （整轮静默退化为进程内互斥，最难查）。这是编程错误，不是运行时降级面，故在 try 之外直接抛。
			String leaseTag = hashTagOf(key);
			String fenceTag = hashTagOf(fenceKey);
			if (leaseTag == null || !leaseTag.equals(fenceTag)) {
				throw new IllegalArgumentException("fenceKey 必须与租约键同槽（同一 hash tag）：key=" + key
						+ " tag=" + leaseTag + "，fence=" + fenceKey + " tag=" + fenceTag);
			}
		}
		String holder = owner(owner);
		Duration effective = ttl == null || ttl.isZero() || ttl.isNegative() ? this.properties.getLease().getTtl() : ttl;
		String viewKey = viewKey(key, holder);
		Lease already = this.held.get(viewKey);
		if (already != null) {
			// 可重入短路：同 owner 重复 acquire 不得再 SET（L3），也不消耗新 epoch。
			// created=false：租约不是这次调用创建的 ⇒ 本次调用没有释放权。
			return new AcquireResult(AcquireOutcome.ACQUIRED, already, false);
		}
		try {
			if (epochKey == null) {
				// 无 tag（资源锁）：取号走全局计数器（仅 token 用，不参与 fencing），两步式。
				return acquireUntagged(key, holder, viewKey, effective);
			}
			if (fenceKey != null) {
				// 带 tag + 有 fence：三键同槽原子自愈（评审 N-1 的根治）。
				String raw = this.redis.execute(ACQUIRE_SCRIPT, List.of(key, epochKey, fenceKey), holder,
						String.valueOf(effective.toMillis()), String.valueOf(epochTtlMillis()));
				return interpret(key, holder, viewKey, raw);
			}
			// 带 tag 但没有 fence（测试替身/未来其它带 tag 的租约）：两键版，不自愈。
			String raw = this.redis.execute(ACQUIRE_NOFENCE_SCRIPT, List.of(key, epochKey), holder,
					String.valueOf(effective.toMillis()), String.valueOf(epochTtlMillis()));
			return interpret(key, holder, viewKey, raw);
		}
		catch (Exception ex) {
			this.facilityHealthy = false;
			log.warn("租约抢用降级 key={} owner={}（Redis 不可用 ⇒ 调用方走进程内互斥）：{}", key, holder,
					ex.getMessage());
			return new AcquireResult(AcquireOutcome.UNAVAILABLE, null);
		}
	}

	/** 无 tag 租约（资源锁）：脚本外取号 + 单键 GET/SET（其 epoch 不参与 fencing，乱序无害）。 */
	private AcquireResult acquireUntagged(String key, String holder, String viewKey, Duration effective) {
		String current = this.redis.opsForValue().get(key);
		this.facilityHealthy = true;
		if (current != null) {
			Lease present = parse(key, current);
			if (present == null) {
				log.warn("租约现值形态不合法 key={} value={}（按占用处理，不吞并别人的租约）", key, current);
				return new AcquireResult(AcquireOutcome.HELD_ELSEWHERE, null);
			}
			if (!holder.equals(present.owner())) {
				return new AcquireResult(AcquireOutcome.HELD_ELSEWHERE, null);
			}
			// 视图丢失但 Redis 里仍是自己那一任：重入，created=false（没有释放权）
			this.held.put(viewKey, present);
			this.ended.remove(viewKey);
			return new AcquireResult(AcquireOutcome.ACQUIRED, present, false);
		}
		long epoch = nextEpoch();
		String raw = this.redis.execute(ACQUIRE_UNTAGGED_SCRIPT, List.of(key), epoch + "|" + holder,
				String.valueOf(effective.toMillis()));
		return interpret(key, holder, viewKey, raw);
	}

	/**
	 * 把抢租脚本的返回值翻译成三态结论（两条脚本共用）。
	 *
	 * <p>脚本返回 {@code <epoch>|<owner>|<created>}：owner 不是自己 ⇒ 别人持有（败方那个号作废，
	 * 只增不减，跳号无害）；是自己且 {@code |1} ⇒ 本次创建（有释放权）；{@code |0} ⇒ 命中现值
	 * （同主重入/视图丢失，<b>没有</b>释放权）。
	 */
	private AcquireResult interpret(String key, String holder, String viewKey, String raw) {
		Lease current = parse(key, raw);
		if (current == null) {
			log.warn("租约脚本返回空值 key={} owner={}（按占用处理，不吞并别人的租约）", key, holder);
			return new AcquireResult(AcquireOutcome.HELD_ELSEWHERE, null);
		}
		if (!holder.equals(current.owner())) {
			return new AcquireResult(AcquireOutcome.HELD_ELSEWHERE, null);
		}
		this.held.put(viewKey, current);
		this.ended.remove(viewKey);
		this.facilityHealthy = true;
		return new AcquireResult(AcquireOutcome.ACQUIRED, current, createdFlag(raw));
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
		return verifyHeld(key, epoch, this.instanceId);
	}

	/** 带 owner 的归属校验（与 {@link #acquire(String, Duration, String)} 同一 owner 口径）。 */
	public Hold verifyHeld(String key, long epoch, String owner) {
		if (key == null || epoch <= 0L) {
			return Hold.LOST;
		}
		String holder = owner(owner);
		String viewKey = viewKey(key, holder);
		try {
			Long held = this.redis.execute(VERIFY_SCRIPT, List.of(key), epoch + "|" + holder);
			this.facilityHealthy = true;
			if (held != null && held > 0L) {
				this.ended.remove(viewKey);
				return Hold.HELD;
			}
			// 续租 fencing 收口批：校验失败 = <b>确定</b>丢租 —— 留下墓碑，后续写权限一律只读，
			// 否则进程内视图被清后 epoch=0 又会把"持过但已丢"当成"从未持租"。
			markEnded(viewKey, LeaseAuthority.LOST);
			// 现值已不是自己那一任：只摘<b>匹配的那一任</b>（R3）—— 若视图已经换成更新的 epoch，
			// 这次校验说明的是"被校验的那一任没了"，不能顺手把当前那一任也作废。
			Lease local = this.held.get(viewKey);
			if (local != null && local.epoch() == epoch) {
				this.held.remove(viewKey, local);
			}
			return Hold.LOST;
		}
		catch (Exception ex) {
			this.facilityHealthy = false;
			markEnded(viewKey, LeaseAuthority.UNAVAILABLE);
			log.warn("租约归属校验降级 key={} owner={} epoch={}（Redis 不可用 ⇒ 只读，不推进）：{}", key, holder,
					epoch, ex.getMessage());
			return Hold.UNAVAILABLE;
		}
	}

	/**
	 * 续租：只有现值仍是自己那一任（epoch + owner）才续。
	 *
	 * <p>兼容口（既有调用方只关心 true/false）。需要区分"确定丢租"与"设施不可用"的调用方用
	 * {@link #renewState} —— 两者在布尔面上都是 false，但可写性/恢复路径不同。
	 */
	public boolean renew(Lease lease, Duration ttl) {
		return renewState(lease, ttl) == RenewOutcome.RENEWED;
	}

	/**
	 * 续租并给出<b>三态</b>（续租 fencing 收口批）。
	 *
	 * <ul>
	 * <li>Lua 返回 1 ⇒ {@link RenewOutcome#RENEWED}；清掉墓碑（设施此刻确实是自己的）；</li>
	 * <li>Lua 返回 0 ⇒ {@link RenewOutcome#LOST}：<b>确定</b>丢租，留下 LOST 墓碑 ⇒ 写权限只读；</li>
	 * <li>异常 ⇒ {@link RenewOutcome#UNAVAILABLE}：不知道还在不在，留 UNAVAILABLE 墓碑 ⇒
	 *     只读不推进；下一拍续上即恢复 HELD（不是永久毒化）。</li>
	 * </ul>
	 *
	 * <p>返回值不抛异常：续租是后台/等待循环动作，单拍失败由调用方决定重试节奏。
	 */
	public RenewOutcome renewState(Lease lease, Duration ttl) {
		if (lease == null) {
			return RenewOutcome.UNAVAILABLE;
		}
		Duration effective = ttl == null || ttl.isZero() || ttl.isNegative() ? this.properties.getLease().getTtl() : ttl;
		String viewKey = viewKey(lease.key(), lease.owner());
		try {
			Long renewed = this.redis.execute(RENEW_SCRIPT, List.of(lease.key()), lease.token(),
					String.valueOf(effective.toMillis()));
			this.facilityHealthy = true;
			if (renewed != null && renewed > 0L) {
				this.ended.remove(viewKey);
				return RenewOutcome.RENEWED;
			}
			markEnded(viewKey, LeaseAuthority.LOST);
			return RenewOutcome.LOST;
		}
		catch (Exception ex) {
			this.facilityHealthy = false;
			markEnded(viewKey, LeaseAuthority.UNAVAILABLE);
			log.debug("租约续期失败 key={} epoch={}：{}", lease.key(), lease.epoch(), ex.getMessage());
			return RenewOutcome.UNAVAILABLE;
		}
	}

	/** 释放：只有现值仍是自己那一任才删（错 epoch / 错 owner 不删别人的租约）。 */
	public boolean release(Lease lease) {
		if (lease == null) {
			return false;
		}
		try {
			Long released = this.redis.execute(RELEASE_SCRIPT, List.of(lease.key()), lease.token());
			this.facilityHealthy = true;
			// R3：<b>条件删除</b> —— 只有"本进程的视图仍是这一任"才摘视图、才停续租任务。
			// 旧实现入口就无条件 stopDriving、失败分支无条件 held.remove(key)：一个迟到的
			// release(旧租约) 会把本进程<b>当前</b>那一任的视图与续租任务一起误杀（新主人于是
			// 拿不回 fencing 依据、续租也停了）。"错 epoch 不删别人的键"由脚本保证，
			// "错 epoch 不动别人的进程内状态"由这里的条件删除保证。
			// 批③a：视图与续租任务按 (key, owner) 记账 —— 错 owner 的 release 也摘不到别人那一任。
			String viewKey = viewKey(lease.key(), lease.owner());
			if (this.held.remove(viewKey, lease)) {
				stopDrivingExact(viewKey);
			}
			if (released != null && released > 0L) {
				// 自愿归还：这一任已经结束，墓碑一并清掉（之后若还有迟到写，那是调用方 bug，
				// 由 RunStore 的"终态之后不再写"契约兜底；保留墓碑会让正常的下一任被误判只读）。
				this.ended.remove(viewKey);
			}
			return released != null && released > 0L;
		}
		catch (Exception ex) {
			this.facilityHealthy = false;
			log.debug("租约释放失败 key={} epoch={}：{}", lease.key(), lease.epoch(), ex.getMessage());
			return false;
		}
	}

	/** 释放"本进程持有的"租约（未持有返回 false，不触 Redis）。 */
	public boolean releaseIfHeld(String key) {
		return releaseIfHeld(key, this.instanceId);
	}

	/** 带 owner 的 {@link #releaseIfHeld(String)}（只放这个 owner 那一任）。 */
	public boolean releaseIfHeld(String key, String owner) {
		Lease lease = key == null ? null : this.held.get(viewKey(key, owner(owner)));
		return lease != null && release(lease);
	}

	/** 进程内所有权视图（0 = 未持有）。fencing 写与 {@code ai:claim:} 值都读它。 */
	public long heldEpoch(String key) {
		return heldEpoch(key, this.instanceId);
	}

	/** 带 owner 的进程内所有权视图（同一键上不同 owner 各自独立）。 */
	public long heldEpoch(String key, String owner) {
		Lease lease = key == null ? null : this.held.get(viewKey(key, owner(owner)));
		return lease == null ? 0L : lease.epoch();
	}

	/** 进程内持有的租约（未持有返回 empty）。 */
	public Optional<Lease> heldLease(String key) {
		return heldLease(key, this.instanceId);
	}

	/** 带 owner 的 {@link #heldLease(String)}。 */
	public Optional<Lease> heldLease(String key, String owner) {
		return Optional.ofNullable(key == null ? null : this.held.get(viewKey(key, owner(owner))));
	}

	// ── 写权限的权威判定（续租 fencing 收口批） ─────────────────────────────

	/** 本实例在 {@code key} 上的写权限（owner 缺省 = 本实例标识）。 */
	public LeaseAuthority authority(String key) {
		return authority(key, this.instanceId);
	}

	/**
	 * <b>写权限的权威判定</b>：{@link LeaseAuthority} 四态（判据见枚举注）。
	 *
	 * <p>顺序刻意是<b>墓碑 → 视图 → 从未持租</b>：
	 * <ol>
	 * <li>LOST 是终局（别人已接管，重新持租前一律只读）；</li>
	 * <li>UNAVAILABLE 优先于"视图还在"：续租异常时视图刻意保留（下一拍续上即可复工），
	 *     但那一拍不能写；</li>
	 * <li>视图在 ⇒ HELD；</li>
	 * <li>没有墓碑也没有视图 ⇒ 从未持租：仅当设施能自证正常时放行普通写
	 *     （{@link #facilityHealthy()}）。</li>
	 * </ol>
	 *
	 * <p>本方法<b>不抛异常</b>：它被热路径（每帧/每笔写）调用，判定必须总能给出结论。
	 */
	public LeaseAuthority authority(String key, String owner) {
		if (key == null) {
			return LeaseAuthority.NEVER_HELD;
		}
		String viewKey = viewKey(key, owner(owner));
		LeaseAuthority endedState = this.ended.get(viewKey);
		if (endedState == LeaseAuthority.LOST) {
			return LeaseAuthority.LOST;
		}
		if (endedState == LeaseAuthority.UNAVAILABLE) {
			return LeaseAuthority.UNAVAILABLE;
		}
		if (this.held.containsKey(viewKey)) {
			return LeaseAuthority.HELD;
		}
		return facilityHealthy() ? LeaseAuthority.NEVER_HELD : LeaseAuthority.UNAVAILABLE;
	}

	/**
	 * 设施是否"能自证正常"（{@link #authority} 的从未持租分支）。
	 *
	 * <p>正常路径零额外往返（上一次成功命令已把 {@code facilityHealthy} 置真）。只有观测到失败后
	 * 才做一次有节流的 {@code PING}：断连时每帧都等 PING 超时会把流式路径拖垮。
	 * {@code PING} 成功即恢复"正常"——Redis 恢复后不需要重启进程。
	 */
	private boolean facilityHealthy() {
		if (this.facilityHealthy) {
			return true;
		}
		long now = System.currentTimeMillis();
		long last = this.lastHealthProbeMs.get();
		if (now - last < HEALTH_PROBE_INTERVAL_MS) {
			return this.lastProbeOk;
		}
		if (!this.lastHealthProbeMs.compareAndSet(last, now)) {
			return this.lastProbeOk;
		}
		boolean ok = leaseOk();
		this.lastProbeOk = ok;
		if (ok) {
			this.facilityHealthy = true;
			log.info("租约设施自证恢复（PING 成功）：写权限回到「从未持租 ⇒ 普通写」口径");
		}
		return ok;
	}

	/** 记一次"这一任已结束"的墓碑；LOST 是终局，不会被随后的 UNAVAILABLE 覆盖。 */
	private void markEnded(String viewKey, LeaseAuthority state) {
		if (viewKey == null || state == null) {
			return;
		}
		this.ended.merge(viewKey, state, (existing, incoming) -> existing == LeaseAuthority.LOST
				? LeaseAuthority.LOST : incoming);
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
		// 批③a：登记键 = (key, owner) —— 同键不同 owner 各有一个续租任务，互不覆盖。
		this.driving.computeIfAbsent(viewKey(lease.key(), lease.owner()),
				key -> this.scheduler.schedule(() -> renewTick(lease, check), interval));
	}

	/**
	 * 停掉该 key 上的续租任务（未在续租则无操作）。
	 *
	 * <p>批③a 起续租任务按 {@code (key, owner)} 登记，本方法按<b>裸键</b>停掉该键上的<b>全部</b>
	 * owner（对外语义与批②一致：调用方只认键）；只停某一个 owner 用 {@link #stopDriving(String, String)}。
	 */
	public void stopDriving(String key) {
		if (key == null) {
			return;
		}
		String prefix = key + VIEW_SEPARATOR;
		for (String viewKey : List.copyOf(this.driving.keySet())) {
			if (viewKey.equals(key) || viewKey.startsWith(prefix)) {
				stopDrivingExact(viewKey);
			}
		}
	}

	/** 停掉 {@code (key, owner)} 这一任的续租任务。 */
	public void stopDriving(String key, String owner) {
		if (key == null) {
			return;
		}
		stopDrivingExact(viewKey(key, owner(owner)));
	}

	private void stopDrivingExact(String viewKey) {
		AutoCloseable handle = viewKey == null ? null : this.driving.remove(viewKey);
		if (handle == null) {
			return;
		}
		try {
			handle.close();
		}
		catch (Exception ex) {
			log.debug("停止续租任务失败 key={}：{}", viewKey, ex.getMessage());
		}
	}

	private void renewTick(Lease lease, BooleanSupplier alive) {
		try {
			if (!alive.getAsBoolean()) {
				log.info("续租判据转假，停续租并归还租约 key={} owner={} epoch={}（驱动者已不在：轮终态/线程收尾）",
						lease.key(), lease.owner(), lease.epoch());
				release(lease);
				stopDriving(lease.key(), lease.owner());
				return;
			}
			RenewOutcome outcome = renewState(lease, this.properties.getLease().getTtl());
			if (outcome == RenewOutcome.RENEWED) {
				return;
			}
			if (outcome == RenewOutcome.LOST) {
				log.warn("续租失败，放弃本进程的租约视图 key={} owner={} epoch={}（另一实例已接管：本进程转只读）",
						lease.key(), lease.owner(), lease.epoch());
				this.held.remove(viewKey(lease.key(), lease.owner()), lease);
				stopDriving(lease.key(), lease.owner());
				return;
			}
			// UNAVAILABLE：不知道租约还在不在 —— 视图与续租任务都保留（下一拍续上即可复工），
			// 但 authority() 已是 UNAVAILABLE ⇒ 这一拍只读不推进。
			log.warn("续租不可用 key={} owner={} epoch={}（设施异常 ⇒ 本拍只读，下一拍重试）", lease.key(),
					lease.owner(), lease.epoch());
		}
		catch (Exception ex) {
			// 续租是后台动作：单拍失败不杀任务，下一拍再试（TTL 三倍于间隔，容忍两拍失败）
			log.debug("续租任务异常 key={} epoch={}：{}", lease.key(), lease.epoch(), ex.getMessage());
		}
	}

	/**
	 * 只读探针：该键上此刻是否有租约（不判 owner、不取号、不取租约）。
	 *
	 * <p>给"这个业务对象上还有没有动作在进行"这类<b>对账</b>问题用（B23L1 批③b 的
	 * {@code ToolUnknownReconciler} 第三跳）：对账只关心"有没有锁"，不关心是谁的 ——
	 * 判 owner 需要知道"期望的主人是谁"，而 UNKNOWN 场景里恰恰连主人都不可知。
	 *
	 * <p>设施不可用时返回 false（与"没有锁"同形）：对账的结论本来就是 UNKNOWN，
	 * 探针失败只影响文案里"资源是否仍在执行"的措辞，不应把异常抛进复跑路径。
	 */
	public boolean isHeld(String key) {
		if (key == null) {
			return false;
		}
		try {
			return Boolean.TRUE.equals(this.redis.hasKey(key));
		}
		catch (Exception ex) {
			log.debug("租约只读探针失败 key={}：{}", key, ex.getMessage());
			return false;
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
			boolean ok = pong != null && "PONG".equalsIgnoreCase(pong);
			if (ok) {
				this.facilityHealthy = true;
			}
			return ok;
		}
		catch (Exception ex) {
			this.facilityHealthy = false;
			log.debug("租约探针失败：{}", ex.getMessage());
			return false;
		}
	}

	@PreDestroy
	void shutdown() {
		for (String key : List.copyOf(this.driving.keySet())) {
			stopDrivingExact(key);
		}
		// 优雅停机时把本进程仍持有的租约还掉：等 TTL 到期会让接管方白等一个续租周期
		for (Lease lease : List.copyOf(this.held.values())) {
			release(lease);
		}
		// 进程正在退出：墓碑随进程一起消失。不做 TTL 到期/容量淘汰式的"自动放行"——
		// 那会把 LOST 重新变成可写（见 ended 字段注）。
		this.ended.clear();
		if (this.ownExecutor != null) {
			this.ownExecutor.shutdownNow();
		}
	}

	// ── 内部 ────────────────────────────────────────────────────────────────

	/** 视图键的分隔符：owner 由调用方给出（任务/作业级锁），必须与键名不可能混淆。 */
	private static final String VIEW_SEPARATOR = "\0";

	/** 视图/续租表的记账键：{@code key + "\0" + owner}。 */
	static String viewKey(String key, String owner) {
		return key + VIEW_SEPARATOR + owner;
	}

	/** owner 归一：缺省（null/空白）= 本实例标识（批②的旧行为）。 */
	private String owner(String owner) {
		return owner == null || owner.isBlank() ? this.instanceId : owner;
	}

	/**
	 * 与租约键<b>同槽</b>的取号键（{@link #EPOCH_PREFIX} + 租约键的 hash tag）；键上没有 tag 时返回 null。
	 *
	 * <p>hash tag 规则按 Redis 规范：第一个 {@code {} 与其后的第一个 {@code }} 之间非空才算 tag。
	 * 例：{@code ai:lease:run:{run:abc}} ⇒ {@code ai:lease:epoch:{run:abc}}（同槽）；
	 * {@code ai:lock:task:7} ⇒ null（无 tag ⇒ 走全局计数器）。
	 *
	 * <p>共享同一 tag 的一族键共用一台计数器：run 的 13 个键同 tag，于是"这一轮发过哪些号"
	 * 是单调的一条线 —— 这正是 fence 比较所需要的语义（比较只发生在同一个 run 内）。
	 */
	static String epochKeyFor(String leaseKey) {
		String tag = hashTagOf(leaseKey);
		return tag == null ? null : EPOCH_PREFIX + tag;
	}

	/**
	 * 键的 hash tag（<b>含花括号</b>，如 {@code {run:abc}}）；无 tag 返回 null。
	 *
	 * <p>规则按 Redis 规范：第一个 {@code {} 与其后的第一个 {@code }} 之间非空才算 tag。
	 * 本类只用它做两件事：推导同槽的计数器键（{@link #epochKeyFor}）、以及校验 fence 与租约键
	 * <b>确实同槽</b>（{@link #acquire(String, String, Duration, String)} 的入口闸）。
	 */
	static String hashTagOf(String key) {
		if (key == null) {
			return null;
		}
		int open = key.indexOf('{');
		if (open < 0) {
			return null;
		}
		int close = key.indexOf('}', open + 1);
		if (close <= open + 1) {
			return null;
		}
		return key.substring(open, close + 1);
	}

	/**
	 * 计数器 TTL（毫秒）：与 fence 同寿（{@code app.ai.session-ttl}）。
	 *
	 * <p>为什么给 TTL 而不是像旧的全局键那样永久：per-run 计数器是"一轮一个键"，永久留存会随轮次
	 * 无限增长。它只在 {@code fence} 还活着时才需要活着（fence 比它先死于同一次"无人读"的过期），
	 * 所以同一 TTL + 同一续期面（{@code SessionKeyRegistry.Renewal.READ}）就够；轮次回收
	 * （{@code RunStore#deleteRun}）另行显式删除。
	 */
	private long epochTtlMillis() {
		Duration sessionTtl = this.properties.getSessionTtl();
		// 下限 1ms：{@code session-ttl} 被配成 0/负时，{@code PEXPIRE 0} 会当场删掉计数器 ——
		// 那正是 N-1 要消灭的"计数器比 fence 先死"。与其它 TTL 口径统一为"至少 1ms"。
		return Math.max(1L, (sessionTtl == null ? Duration.ofHours(24) : sessionTtl).toMillis());
	}

	/**
	 * 全局取号（<b>仅资源锁用</b>）：{@code INCR ai:lease:epoch}。
	 *
	 * <p><b>不再参与 fencing</b>：FENCED_SET / 认领的比较对象是同 run 的 per-slot 号
	 * （{@link #EPOCH_PREFIX}）。这里保留"号只增不减"只为资源锁 token 的可读性与运维观测。
	 */
	private long nextEpoch() {
		Long epoch = this.redis.opsForValue().increment(EPOCH_KEY);
		if (epoch == null || epoch <= 0L) {
			throw new IllegalStateException("epoch 取号失败（INCR " + EPOCH_KEY + " 返回 " + epoch + "）");
		}
		return epoch;
	}

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
