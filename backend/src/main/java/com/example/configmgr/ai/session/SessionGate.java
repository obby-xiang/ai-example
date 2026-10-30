package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantKeyScope;
import com.example.configmgr.ai.tenant.TenantUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 同 sessionId 单值门（S4.2 §2 {@code session/SessionGate}，ADR-5 会话串行化）。
 *
 * <h2>语义</h2>
 * 一个会话同时只允许一轮在跑：第二轮请求被拒（<b>409</b>，与挂起池饱和的 503
 * {@code SUSPEND_POOL_SATURATED} 语义区分），且响应必须携带<b>进行中那一轮的 runId</b> ——
 * 前端据此改用 {@code GET /api/ai/events/{runId}} 重挂（ADR-5 补记 CH-P4：解决
 * "刷新不丢"与 409 之间的死锁）。
 *
 * <h2>两道锁</h2>
 * <ul>
 * <li><b>进程内单值门</b>（{@link ConcurrentHashMap}）：恒定生效，是 §13.1 降级形态的本体；</li>
 * <li><b>Redis 锁</b>{@code ai:session:lock:<sessionId>}：跨实例串行化，带 <b>watchdog 续期</b>
 * （每 {@code app.ai.session.lock.watchdog} 续一次 {@code app.ai.session.lock.ttl}），
 * 轮终态<b>主动释放</b>（只删自己的锁）。</li>
 * </ul>
 *
 * <h2>锁归属 = 每次 acquire 的 token（I2）</h2>
 * 只按 runId 判归属不够：park 后由 {@code ResumeService} 用<b>同一个 runId</b> 重新取门，
 * 而首轮任务那条迟到的 {@code finally} 会把新持有者的锁删掉、并 cancel 新持有者的 watchdog ⇒
 * 恢复执行在"无锁"状态下跑到终态，同会话可并发第二轮。因此：
 * <ul>
 * <li>每次 {@link #acquire(String, String, String)} 带一个 token，锁值为 {@code runId|token}
 * （{@code token == runId} 时退化为裸 runId，首轮/旧调用点行为不变）；</li>
 * <li>{@link #release(String, String, String)} 按 token <b>精确匹配</b>；旧 token 的迟到释放是 no-op；</li>
 * <li>同一 runId 用新 token 取门 = <b>接管</b>（park 后续跑重新取门），锁值换成新 token；</li>
 * <li>watchdog 续期<b>在脚本内同值校验</b>：锁已换主就停续期（不再替别人的锁续命）。</li>
 * </ul>
 * 判死路径（{@code AiController} 已确认进程内无订阅者）用 {@link #releaseDeadHeld(String, String)}
 * 按 runId 强释放：此时该轮已不可能再推进，无需（也无法）持有原 token。
 *
 * <h2>检查与写入必须是同一条单键 Lua（原子）</h2>
 * 只做"GET 比较 → 再写"是不够的：两个动作之间锁可能换主，于是释放会 DEL 掉别人的锁、
 * 续期会给别人续命、同 run 接管会用 plain SET 覆盖别人的锁。<b>本类所有写路径</b>
 * （取门/同 run 接管、续期、释放、强释放）都收敛到 {@code redis.execute(script, [lockKey], ...)}：
 * <ul>
 * <li><b>KEYS 恰好一个</b>（Cluster 单槽；一条 EVAL 只碰一个键）；</li>
 * <li>脚本内比对锁值后才写；比对不过返回 0，<b>永不</b>退回裸 SET/DEL/EXPIRE；</li>
 * <li>强释放只匹配该 runId（{@code runId} 或 {@code runId|*}），不匹配别的轮；</li>
 * <li>Redis 异常或结果不可知（{@code null}）⇒ 视为<b>未成功</b>：不写兜底、不宣称为
 * "已取门/已接管/已续期"（续期异常直接抛给 watchdog 按降级处理）。</li>
 * </ul>
 *
 * <h2>§13.1 降级</h2>
 * 锁服务不可用（Redis 异常）时降级为进程内单值门：M1 单实例下等价，冲突照样 409 提示重试，
 * 只是失去了跨实例互斥；降级事实写进返回体（{@code degraded=true}）与 WARN 日志，不静默。
 *
 * <h2>降级必须"事后也可见"（RT-34 / RT-35）</h2>
 * 两条曾经只在日志里说一句的路子现在已经收口：
 * <ul>
 * <li><b>续期失败</b>（脚本返回 0 或异常）⇒ 置 holder 的 {@code degraded} 并累加
 * {@link #lockLost()}：跨实例互斥已失效，后续 409 的 {@code degraded} 字段会把事实带给调用方，
 * 不再"日志里说一句、对外一切如常"；</li>
 * <li><b>诊断 GET 失败</b> ⇒ 持有者<b>不可知</b>（{@link Busy#unknown()}，{@code runId = null}），
 * <b>绝不</b>回退成调用方自己的 runId —— 否则续跑方会把"没取到门"读成"取到了"，
 * 在没有跨实例会话门的情况下与另一实例并发。</li>
 * </ul>
 *
 * <h2>身份隔离（2026-10-30）</h2>
 * 锁键 = {@code ai:session:lock:<tenantId>:<userId>:<sessionId>}（{@link TenantKeyScope}）：
 * 跨租户/跨用户即使 sessionId 相同，也<b>不是同一把锁</b>（不互斥、不互相强释放、不互相诊断）。
 * 进程内单值门按同一把锁键分桶，键形变了桶也就分开了。
 * 无身份上下文时落到 legacy 键（{@code ai:session:lock:<sessionId>}，独立过渡命名空间）。
 *
 * <h2>watchdog 必须按记录恢复身份</h2>
 * 续期跑在 {@code ai-session-lock-watchdog} 单线程上，<b>没有请求身份</b>。看门狗在调度时
 * 记录了这次持有属于哪个身份，每次续期都用 {@link RequestContextUtils#useContext} 恢复它再算锁键 ——
 * 否则续期会算到 legacy 键上（续不上 ⇒ 误判丢锁 / 给别人续命）。
 */
@Slf4j
@Component
public class SessionGate {

	public static final String LOCK_PREFIX = "ai:session:lock:";

	/**
	 * 锁键（<b>带当前身份</b>）：{@code ai:session:lock:<t>:<u>:<sessionId>}；
	 * 无身份上下文时是 legacy 键形（{@code ai:session:lock:<sessionId>}）。
	 */
	static String gateKey(String sessionId) {
		return TenantKeyScope.scoped(LOCK_PREFIX, sessionId);
	}

	/** 锁键（显式身份；诊断/用例用）。 */
	static String gateKey(String sessionId, long tenantId, long userId) {
		return TenantKeyScope.scoped(LOCK_PREFIX, tenantId, userId, sessionId);
	}

	/**
	 * 取门 / 同 run 接管（单键原子）。
	 *
	 * <p>KEYS[1] = 锁键；ARGV[1] = 新值（{@code runId|token}）；ARGV[2] = TTL(ms)；ARGV[3] = runId。
	 * 键不存在 ⇒ 取门；当前值就是本 run（{@code runId} 或 {@code runId|*}）⇒ 接管并换新 token；
	 * 其余（别的轮持有）⇒ 返回 0，<b>不写</b>。
	 */
	private static final RedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
			-- SESSION_LOCK_ACQUIRE
			local current = redis.call('GET', KEYS[1])
			if not current then
			  redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
			  return 1
			end
			if current == ARGV[3] then
			  redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
			  return 1
			end
			if string.sub(current, 1, string.len(ARGV[3]) + 1) == ARGV[3] .. '|' then
			  redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
			  return 1
			end
			return 0
			""", Long.class);

	/**
	 * 续期（单键原子）：只有锁值<b>恰好</b>是自己的那个值才 PEXPIRE。
	 *
	 * <p>KEYS[1] = 锁键；ARGV[1] = 期望值；ARGV[2] = TTL(ms)。返回 1 = 续到了自己的锁，0 = 键不存在/已换主。
	 */
	private static final RedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>("""
			-- SESSION_LOCK_RENEW
			if redis.call('GET', KEYS[1]) == ARGV[1] then
			  return redis.call('PEXPIRE', KEYS[1], ARGV[2])
			end
			return 0
			""", Long.class);

	/**
	 * 释放（单键原子）：只有锁值<b>恰好</b>是自己的那个值才 DEL。
	 *
	 * <p>KEYS[1] = 锁键；ARGV[1] = 期望值（精确含 token）。
	 */
	private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
			-- SESSION_LOCK_RELEASE
			if redis.call('GET', KEYS[1]) == ARGV[1] then
			  return redis.call('DEL', KEYS[1])
			end
			return 0
			""", Long.class);

	/**
	 * 判死强释放（单键原子）：只按 runId 匹配（{@code runId} 或 {@code runId|*}），
	 * 别的轮持有的锁一律不动。
	 *
	 * <p>KEYS[1] = 锁键；ARGV[1] = runId。
	 */
	private static final RedisScript<Long> RELEASE_DEAD_SCRIPT = new DefaultRedisScript<>("""
			-- SESSION_LOCK_RELEASE_DEAD
			local current = redis.call('GET', KEYS[1])
			if not current then
			  return 0
			end
			if current == ARGV[1] then
			  return redis.call('DEL', KEYS[1])
			end
			if string.sub(current, 1, string.len(ARGV[1]) + 1) == ARGV[1] .. '|' then
			  return redis.call('DEL', KEYS[1])
			end
			return 0
			""", Long.class);

	private final StringRedisTemplate redis;

	private final AiProperties.Session.Lock lockProperties;

	private final Map<String, Holder> inProcess = new ConcurrentHashMap<>();

	/** 看门狗线程（平台线程，DC-12 禁虚拟线程；仅做续期，无业务阻塞）。 */
	private final ScheduledExecutorService watchdog;

	private final AtomicLong renewals = new AtomicLong();

	/**
	 * 续期失败（脚本返回 0 或异常）累计次数 —— RT-34 的可观测面。
	 *
	 * <p>为什么必须有计数而不只是日志：续期失败把"跨实例互斥"降级成进程内互斥，属于必须能聚合
	 * 告警的降级，而日志会被轮询节拍刷掉。对外接线在 {@code AiController} 的 {@code /api/ai/health}
	 * （{@code sessionLockLost} / {@code sessionLockDegraded} 两个 additive 键；后者 =
	 * 本计数 &gt; 0 <b>或</b> {@link #degradedSessions()} &gt; 0，因为取门降级只置 holder 不累加本计数）。
	 */
	private final AtomicLong lockLost = new AtomicLong();

	public SessionGate(StringRedisTemplate redis, AiProperties properties) {
		this.redis = redis;
		this.lockProperties = properties.getSession().getLock();
		this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "ai-session-lock-watchdog");
			thread.setDaemon(true);
			return thread;
		});
	}

	/**
	 * 占用会话门（首轮/旧调用点：token = runId）。
	 *
	 * @return {@code null} = 取到门（可以开跑）；非 null = 已有一轮在跑，值里带进行中的 runId
	 */
	public Busy acquire(String sessionId, String runId) {
		return acquire(sessionId, runId, runId);
	}

	/**
	 * 占用会话门，并声明本次持有的 token（I2）。
	 *
	 * <p>同一 runId 用新 token 再取门 = 接管（park 后 ResumeService 重新取门），锁值与
	 * watchdog 的期望值都换成新 token；不同 runId 仍然互斥。
	 *
	 * <p><b>取门与接管是同一条脚本</b>（{@link #ACQUIRE_SCRIPT}）：脚本内比对后才写，因此
	 * "检查通过、写入已换主"的窗口不存在。三种结局：
	 * <ul>
	 * <li>脚本返回 1 ⇒ 已持有（取门或同 run 接管），换 token 并重启 watchdog；</li>
	 * <li>脚本返回 0 ⇒ <b>另一轮持有</b>：按对方 runId 返回 {@link Busy}，
	 * <b>不改</b>自己的 holder token、也不宣称接管成功（重入路径下 holder 仍是本轮的）；</li>
	 * <li>异常 / 结果不可知 ⇒ 只保留进程内门（{@code degraded}），不写兜底、不宣称取到 Redis 锁。</li>
	 * </ul>
	 *
	 * @return {@code null} = 取到门（含同轮接管）；非 null = 另一轮在跑
	 */
	public Busy acquire(String sessionId, String runId, String token) {
		String gateKey = gateKey(sessionId);
		Holder candidate = new Holder(sessionId, gateKey, RequestContextUtils.currentOrNull(), runId, token);
		Holder previous = this.inProcess.putIfAbsent(gateKey, candidate);
		if (previous != null) {
			if (!previous.runId.equals(runId)) {
				return new Busy(previous.runId, previous.degraded);
			}
			// 重入（同一轮再次 acquire，例如"首轮 park 后由 ResumeService 重新取门"）
			Boolean taken = takeLock(gateKey, runId, token);
			if (Boolean.TRUE.equals(taken)) {
				previous.token = token;
				previous.lockValue = encode(runId, token);
				startWatchdog(gateKey, runId, previous.lockValue, previous);
				return null;
			}
			if (Boolean.FALSE.equals(taken)) {
				// 锁已被另一轮拿走（park 期间锁到期后别人接管）：不覆盖、不续看门狗，
				// holder 保持原 token（本轮进程内仍是它的门），对外如实报"另一轮在跑"
				return busyHeldByAnother(gateKey, sessionId);
			}
			// 结果不可知：<b>不宣称 Redis 接管成功</b> —— 不重启 watchdog、不更新 lockValue，
			// 已有 watchdog 仍按上一次确认的值续期（续不上就自停）。
			// 但本地 token 必须跟上：进程内门是本体，调用方随后会用新 token 释放；
			// token 停在旧值会让那次释放摘不掉本地门（会话被永久占住）。
			previous.token = token;
			previous.degraded = true;
			return null;
		}

		Boolean taken = takeLock(gateKey, runId, token);
		if (Boolean.TRUE.equals(taken)) {
			candidate.lockValue = encode(runId, token);
			startWatchdog(gateKey, runId, candidate.lockValue, candidate);
			return null;
		}
		if (Boolean.FALSE.equals(taken)) {
			// 另一实例持有：让出进程内门，按对方 runId 拒绝（读不到持有者 ⇒ unknown，不回退成自己）
			this.inProcess.remove(gateKey, candidate);
			return busyHeldByAnother(gateKey, sessionId);
		}
		// §13.1：锁服务不可用/结果不可知 → 降级为进程内单值门（M1 单实例下等价）。
		// 刻意不写兜底 SET：若脚本其实已执行而回包丢失，锁按 TTL 自行过期（fencing 由续跑租约兜底）；
		// lockValue 保持 null ⇒ 释放只按协议值 {runId|token} 走脚本验证（不猜、不裸删）。
		candidate.degraded = true;
		return null;
	}

	/**
	 * 原子取门/同 run 接管。
	 *
	 * @return {@code TRUE} = 脚本确认本 run 现在持有；{@code FALSE} = 另一轮持有；
	 * {@code null} = Redis 异常或结果不可知（<b>不得</b>当成功）
	 */
	private Boolean takeLock(String gateKey, String runId, String token) {
		return runLockScript(gateKey, ACQUIRE_SCRIPT, "取门/接管", encode(runId, token), ttlMs(), runId);
	}

	/** 轮终态主动释放（token = runId：首轮/旧调用点）。 */
	public void release(String sessionId, String runId) {
		release(sessionId, runId, runId);
	}

	/**
	 * 轮终态主动释放：只释放<b>自己这个 token</b> 持有的那一轮（I2）。
	 *
	 * <p>旧 token 的迟到 finally 对新持有者是 no-op —— 不会删掉续跑方的锁、
	 * 也不会 cancel 对方刚起的 watchdog。Redis 侧是单键脚本
	 * （{@link #RELEASE_SCRIPT}：锁值<b>精确等于</b>要释放的那个值才 DEL），因此
	 * "GET 时还是自己的、DEL 时已被换主"的窗口不存在。
	 *
	 * <p>要释放的值取<b>本 holder 上一次确认过的锁值</b>（{@code lockValue}）：降级/结果不可知
	 * 之后锁里可能仍是上一个 token 的值，而调用方手里已经是新 token —— 仍按真值释放，
	 * 且脚本会验证它确实属于本轮。没有 holder（跨线程/进程收尾）时按协议值
	 * {@code runId|token} 释放，同样由脚本精确验证。
	 *
	 * @return 是否真的释放了（进程内门或 Redis 锁任一）；Redis 异常/结果不可知时只报进程内事实
	 */
	public boolean release(String sessionId, String runId, String token) {
		boolean released = false;
		String expected = encode(runId, token);
		String gateKey = gateKey(sessionId);
		Holder holder = this.inProcess.get(gateKey);
		if (!matches(holder, runId, token)) {
			// 异步收尾线程可能没带身份（TTL 尚未接线/Task 3 之前的路径）：按<b>记录</b>
			// （sessionId + runId + token）找到真正持有者，用它的锁键释放 ——
			// runId+token 只属于那一轮，不会跨身份误放。带身份时不做这层兜底（严格按身份桶）。
			Holder recorded = RequestContextUtils.currentOrNull() == null
					? findHolder(sessionId, runId, token) : null;
			if (recorded != null) {
				holder = recorded;
				gateKey = recorded.gateKey;
			}
		}
		if (matches(holder, runId, token)) {
			this.inProcess.remove(holder.gateKey, holder);
			cancelWatchdog(holder);
			released = true;
			if (holder.lockValue != null) {
				expected = holder.lockValue;
			}
		}
		Boolean deleted = runLockScript(gateKey, RELEASE_SCRIPT, "释放", expected);
		if (Boolean.TRUE.equals(deleted)) {
			released = true;
		}
		else if (deleted == null) {
			log.warn("会话锁释放结果不可知 sessionId={} runId={}（未做裸 DEL；锁按 TTL 自行过期）", sessionId,
					runId);
		}
		return released;
	}

	private static boolean matches(Holder holder, String runId, String token) {
		return holder != null && holder.runId.equals(runId) && token.equals(holder.token);
	}

	/** 按记录（sessionId+runId+token）定位持有者（只用于未带身份的异步收尾）。 */
	private Holder findHolder(String sessionId, String runId, String token) {
		for (Holder holder : this.inProcess.values()) {
			if (holder.sessionId.equals(sessionId) && matches(holder, runId, token)) {
				return holder;
			}
		}
		return null;
	}

	/**
	 * 判死路径的强释放：该轮已确认进程内无订阅者、不可能再推进，按 runId 释放
	 * （调用方拿不到原 token）。<b>只给</b> {@code AiController} 的僵尸判定用。
	 *
	 * <p>Redis 侧是单键脚本（{@link #RELEASE_DEAD_SCRIPT}）：只删 {@code runId} 或
	 * {@code runId|*} 形态的锁；另一轮的锁一个字节都不动。
	 *
	 * <p><b>匹配持有者时同时核对身份</b>：进程内门按锁键（含身份段）分桶，但"按记录找持有者"的兜底
	 * 扫描跨全部桶 —— 只按 {@code sessionId + runId} 匹配会让<b>别的身份</b>的同名轮被误释放
	 * （摘进程内门、取消 watchdog、并把锁键换成对方那把 ⇒ 删掉对方的会话锁）。
	 * 无身份（legacy 视图）只匹配 legacy 持有者；不同身份之间永不互相强释放。
	 */
	public void releaseDeadHeld(String sessionId, String runId) {
		if (runId == null || runId.isBlank()) {
			// 持有者不可知（RT-35 的 busy.unknown）：没有任何 runId 可以匹配，强释放必须放弃 ——
			// 猜一个 id 去删锁，删到别人那一任就是"静默抢门"。
			log.warn("会话门强释放缺少 runId sessionId={}（持有者不可知：不猜、不删任何锁）", sessionId);
			return;
		}
		String gateKey = gateKey(sessionId);
		TenantUser identity = RequestContextUtils.currentOrNull();
		Holder holder = this.inProcess.get(gateKey);
		if (holder == null) {
			holder = findDeadHolder(sessionId, runId, identity);
		}
		if (holder != null && holder.runId.equals(runId) && sameIdentity(holder, identity)) {
			this.inProcess.remove(holder.gateKey, holder);
			cancelWatchdog(holder);
			gateKey = holder.gateKey;
		}
		Boolean deleted = runLockScript(gateKey, RELEASE_DEAD_SCRIPT, "强释放", runId);
		if (deleted == null) {
			log.warn("会话锁强释放结果不可知 sessionId={} runId={}（未做裸 DEL；锁按 TTL 自行过期）", sessionId,
					runId);
		}
	}

	/** 进行中轮的 runId（null = 空闲）。锁值是 {@code runId|token}，取 {@code |} 前段。 */
	public String currentRunId(String sessionId) {
		Holder holder = this.inProcess.get(gateKey(sessionId));
		if (holder != null) {
			return holder.runId;
		}
		try {
			return runIdOfValue(this.redis.opsForValue().get(gateKey(sessionId)));
		}
		catch (Exception ex) {
			return null;
		}
	}

	public int heldSessions() {
		return this.inProcess.size();
	}

	public long renewals() {
		return this.renewals.get();
	}

	/** 续期失败（0/异常）累计次数：会话锁降级的观测面（RT-34）。 */
	public long lockLost() {
		return this.lockLost.get();
	}

	/**
	 * 当前仍标记为降级的持有者数 —— {@link #lockLost()} 的<b>必要补充</b>。
	 *
	 * <p>取门时 Redis 不可用/结果不可知只把 holder 置 {@code degraded}（见
	 * {@code acquire} 的降级分支），<b>不</b>累加 {@code lockLost}（那条路径没走到续期）。
	 * 只按丢锁计数报降级会把这种最常见的降级形态漏报 —— 而它同样是"跨实例互斥已失效"。
	 */
	public int degradedSessions() {
		int degraded = 0;
		for (Holder holder : this.inProcess.values()) {
			if (holder.degraded) {
				degraded++;
			}
		}
		return degraded;
	}

	/**
	 * 按记录（sessionId + runId + <b>身份</b>）定位持有者（只用于判死强释放的兜底扫描）。
	 *
	 * <p>身份必须相同：{@code null}（legacy 视图）只匹配 legacy 持有者。同 sessionId/runId
	 * 在不同身份下是<b>两轮互不相干的门</b>，跨身份匹配会误释放别人那一任。
	 */
	private Holder findDeadHolder(String sessionId, String runId, TenantUser identity) {
		for (Holder holder : this.inProcess.values()) {
			if (holder.sessionId.equals(sessionId) && holder.runId.equals(runId)
					&& sameIdentity(holder, identity)) {
				return holder;
			}
		}
		return null;
	}

	/** 持有者身份与给定身份是否同一（{@code null} 只等于 {@code null}：legacy 不冒充任何身份）。 */
	private static boolean sameIdentity(Holder holder, TenantUser identity) {
		if (holder.identity == null || identity == null) {
			return holder.identity == null && identity == null;
		}
		return holder.identity.equals(identity);
	}

	/**
	 * 续期一次（单键脚本 {@link #RENEW_SCRIPT}：锁值<b>恰好</b>等于 {@code expectedValue} 才 PEXPIRE）。
	 *
	 * <p><b>异常不吞</b>：Redis 异常向上抛给 watchdog，由它按 §13.1 降级处理 ——
	 * 绝不把异常/不可知当"续期成功"。键不存在或已换主返回 false（watchdog 据此停续期）。
	 *
	 * @return true = 确实续到了自己的锁；false = 键已不存在或已换主
	 */
	boolean renewLockOnce(String sessionId, String expectedValue) {
		return renewLockAt(gateKey(sessionId), sessionId, expectedValue);
	}

	/**
	 * 按<b>锁键</b>续期（watchdog 用：它在调度时已把锁键与身份记在 holder 里，
	 * 不能在续期线程上重新按"当前上下文"算键 —— 那算到的是别的身份/legacy 键）。
	 */
	private boolean renewLockAt(String gateKey, String sessionId, String expectedValue) {
		Long renewed = this.redis.execute(RENEW_SCRIPT, List.of(gateKey), expectedValue, ttlMs());
		if (renewed == null || renewed != 1L) {
			return false;
		}
		this.renewals.incrementAndGet();
		return true;
	}

	/**
	 * 单键脚本的唯一执行口：KEYS 恰好一个（Cluster 单槽），ARGV 逐项透传。
	 *
	 * @return {@code TRUE} = 脚本返回 1；{@code FALSE} = 脚本返回 0；{@code null} = 异常或结果不可知
	 */
	private Boolean runLockScript(String gateKey, RedisScript<Long> script, String action, Object... args) {
		try {
			Long result = this.redis.execute(script, List.of(gateKey), args);
			if (result == null) {
				log.warn("会话锁{}结果不可知（脚本未返回）key={}（未做任何写入兜底）", action, gateKey);
				return null;
			}
			return result == 1L;
		}
		catch (Exception ex) {
			log.warn("会话锁{}失败 key={}（Redis 异常，未做任何写入兜底）：{}", action, gateKey, ex.getMessage());
			return null;
		}
	}

	/**
	 * 脚本已判定"不是本 run 的锁"时构造对外结论（RT-35）。
	 *
	 * <p>持有者读得到 ⇒ 按它报（调用方据此 reattach）；<b>读不到/键已消失 ⇒ unknown</b>：
	 * 宁可如实报"不知道是谁"，也<b>不得</b>回退成调用方自己的 runId（见 {@link Busy} 的注）。
	 * 诊断 GET 失败同时说明跨实例视图此刻不可确认 ⇒ {@code degraded=true}。
	 */
	private Busy busyHeldByAnother(String gateKey, String sessionId) {
		String observed = observedRunId(gateKey);
		if (observed == null) {
			log.warn("会话门被占用但持有者不可知 sessionId={}（诊断读失败/锁已消失：按 unknown 拒绝，"
					+ "不回退成调用方 runId）", sessionId);
			return new Busy(null, true, true);
		}
		return new Busy(observed, false);
	}

	/**
	 * 诊断口径：只读一次锁值来填 409 的 heldBy / reattach（<b>不参与任何写决策</b>）。
	 *
	 * @return 锁值的 runId 段；读失败或锁已消失返回 {@code null}（持有者不可知，<b>不</b>回退兜底值）
	 */
	private String observedRunId(String gateKey) {
		try {
			return runIdOfValue(this.redis.opsForValue().get(gateKey));
		}
		catch (Exception ex) {
			log.debug("读取会话门持有者失败 key={}（按不可知处理）：{}", gateKey, ex.getMessage());
			return null;
		}
	}

	private String ttlMs() {
		return Long.toString(Math.max(1L, this.lockProperties.getTtl().toMillis()));
	}

	/**
	 * 起/重启看门狗。
	 *
	 * <p><b>身份必须按记录恢复</b>：看门狗跑在 {@code ai-session-lock-watchdog} 单线程上，
	 * 该线程没有请求身份。所以调度时把这次持有的锁键 {@code gateKey} 与身份
	 * {@code tenantId/userId} 一起记在 holder 上；每次续期先 {@code useContext} 恢复身份
	 * （即使续期本身只用到锁键 —— 这条路径将来一旦读身份字段，缺上下文就会 fail-closed 失败），
	 * 再按<b>记录里的锁键</b>续期，绝不按续期线程的"当前上下文"重算键。
	 */
	private void startWatchdog(String gateKey, String runId, String expectedValue, Holder holder) {
		Duration interval = this.lockProperties.getWatchdog();
		long period = Math.max(1, interval.toMillis());
		// 重入/重新取门时旧 watchdog 必须先停：同一 holder 上留两个续期任务既浪费也无法在
		// release 时一次性停掉（release 只 cancel 最新那一个）。
		cancelWatchdog(holder);
		String sessionId = holder.sessionId;
		holder.watchdog = this.watchdog.scheduleAtFixedRate(() -> {
			try (RequestContextUtils.SafeCloseable scope = holder.useIdentity()) {
				if (!renewLockAt(gateKey, sessionId, expectedValue)) {
					// RT-34：续期返回 0 是"锁已失效/换主"的确定结论 —— 必须与异常同口径记降级，
					// 否则只在日志里说一句就没了，调用方看到的仍是"一切正常"。
					holder.degraded = true;
					this.lockLost.incrementAndGet();
					log.warn("会话锁已失效或换主：停止续期并标记降级 sessionId={} runId={} key={}"
							+ "（进程内门仍持有；跨实例互斥已失效，后续 409 的 degraded 会如实上报）", sessionId,
							runId, gateKey);
					cancelWatchdog(holder);
				}
			}
			catch (Exception ex) {
				holder.degraded = true;
				this.lockLost.incrementAndGet();
				log.warn("会话锁续期异常，降级为进程内单值门 sessionId={} key={}：{}", sessionId, gateKey,
						ex.getMessage());
			}
		}, period, period, TimeUnit.MILLISECONDS);
	}

	private static String encode(String runId, String token) {
		return token == null || token.equals(runId) ? runId : runId + "|" + token;
	}

	private static String runIdOfValue(String value) {
		if (value == null) {
			return null;
		}
		int separator = value.indexOf('|');
		return separator < 0 ? value : value.substring(0, separator);
	}

	private void cancelWatchdog(Holder holder) {
		ScheduledFuture<?> future = holder.watchdog;
		if (future != null) {
			future.cancel(false);
		}
	}

	/**
	 * 占用失败的结局。
	 *
	 * <h2>为什么需要 {@code unknown}（RT-35）</h2>
	 * 脚本判定"另一轮持有"后，本类会再读一次锁值来填 409 的 {@code heldBy}/{@code reattach}
	 * （<b>只用于诊断</b>，不参与任何写决策）。这次诊断 GET <b>可能失败或键已消失</b> —— 那时
	 * 持有者<b>不可知</b>，绝不能回退成调用方自己的 runId：
	 * <ul>
	 * <li>{@code AiController} 会拿它去判"死轮"并强释放，还会把 {@code reattach} 指到一个不存在的流；</li>
	 * <li>{@code ResumeService} 的判据是 {@code runId.equals(busy.runId())} —— 回退成自己就等于把
	 *     "没取到门"读成"取到了"，在没有跨实例会话门的情况下与另一实例并发（RT-35 的 V1 形态）。</li>
	 * </ul>
	 * 因此不可知时 {@code runId = null} + {@code unknown = true}：两个消费方都会把它当"被占用"
	 * 而不是"自己持有"，也<b>不会</b>用它去猜着删锁。
	 *
	 * @param runId 进行中那一轮的 runId（前端据此 reattach）；{@code null} = 持有者不可知
	 * @param degraded 是否为降级/不可确认形态（Redis 锁未确认，仅进程内互斥）
	 * @param unknown 持有者是否不可知（诊断 GET 失败/锁已消失）
	 */
	public record Busy(String runId, boolean degraded, boolean unknown) {

		/** 兼容构造：持有者已知（例如进程内门被另一轮占着）。 */
		public Busy(String runId, boolean degraded) {
			this(runId, degraded, false);
		}

	}

	private static final class Holder {

		private final String sessionId;

		/** 这次持有落在哪把锁上（含身份段；释放/续期只认它）。 */
		private final String gateKey;

		/** 持有身份（{@code null} = legacy 命名空间）；watchdog 用它恢复上下文。 */
		private final TenantUser identity;

		private final String runId;

		/** 本次持有的 token（可被同轮接管改写；本地释放匹配用）。 */
		private volatile String token;

		/**
		 * <b>最后一次被脚本确认</b>写进 Redis 的锁值（null = 从未确认，如降级/结果不可知）。
		 *
		 * <p>释放与 watchdog 只认这个值，不按调用方手里的 token 去猜 —— 否则
		 * "接管结果不可知但本地已换 token"会让释放指向一个不存在的值（锁白留到 TTL）。
		 */
		private volatile String lockValue;

		private volatile boolean degraded;

		private volatile ScheduledFuture<?> watchdog;

		Holder(String sessionId, String gateKey, TenantUser identity, String runId, String token) {
			this.sessionId = sessionId;
			this.gateKey = gateKey;
			this.identity = identity;
			this.runId = runId;
			this.token = token;
		}

		/** 看门狗线程的身份作用域（无身份时返回 {@code null}，try-with-resources 允许 null）。 */
		private RequestContextUtils.SafeCloseable useIdentity() {
			return this.identity == null ? null
					: RequestContextUtils.useContext(this.identity.tenantId(), this.identity.userId());
		}

	}

}
