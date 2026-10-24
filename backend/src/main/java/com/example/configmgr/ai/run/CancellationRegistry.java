package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.observe.RedisDegradation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI 轮取消标志注册表（S4.2 §2 {@code run/CancellationRegistry}）。
 *
 * <h2>ADR-8 修正③：Redis 为准，直通知仅加速</h2>
 * <ul>
 * <li><b>权威态</b>：{@code ai:cancel:<runId>}（STRING，值 = 发起取消的实例标识，带 TTL）。
 * 取消端点只写这一个键 —— 于是"取消那一轮的是另一个进程/另一个实例"同样成立，
 * 且进程重启后取消意图仍在（续跑会拒绝已取消的轮次，见 {@link ResumeService}）；</li>
 * <li><b>加速态</b>：进程内的 {@link AtomicBoolean} + 监听者集合。取消方与运行方同进程时，
 * 监听者立即被唤起（{@link StreamWatchdog} 据此当场断开上游、{@code ConfirmGate} 据此立刻解开
 * 挂起闸门），不必等下一个轮询节拍；跨进程时监听者为空，靠<b>轮询</b>发现 ——
 * 轮询点只有两处且都在"分片/事件边界"：流式看门狗每 {@value StreamWatchdog#TICK_MILLIS}ms 一次
 * （等价于"事件边界"），确认门/前端工具的等待循环每 {@value com.example.configmgr.ai.gate.ConfirmGate#HEARTBEAT_SECONDS}s 一次
 * （等价于"分片边界"）。</li>
 * </ul>
 *
 * <h2>降级</h2>
 * Redis 不可用（异常）时，本地已置位的取消仍然确定（{@link #cancelState(String)} = {@code CANCELLED}）；
 * 本地未置位而 Redis 读不出来时是 {@code UNKNOWN}（A5），布尔口 {@link #isCancelled(String)} 拒绝作答
 * 而不是返回 {@code false}。5s 退避期同样返回 {@code UNKNOWN}，不把"没访问 Redis"伪装成"未取消"。
 * 降级事实（WARN / {@code degraded} / 统一降级计数）照旧保留，不静默。
 *
 * <h2>键的清理</h2>
 * 取消标志<b>故意不随轮终态删除</b>：它要在 TTL 内继续可见（取证），并且"取消后立刻重启"
 * 时仍然拦得住续跑。TTL = {@code app.ai.session-ttl}（缺省 24h，与会话同寿；超时面治理批①
 * 第 11 项改述 —— 原为 {@code total-budget × 2 = 600s}，见 {@link #cancelFlagTtl()}）。
 */
@Slf4j
@Component
public class CancellationRegistry {

	public static final String CANCEL_PREFIX = "ai:cancel:";

	private final StringRedisTemplate redis;

	private final AiProperties properties;

	private final ConcurrentMap<String, AtomicBoolean> flags = new ConcurrentHashMap<>();

	private final ConcurrentMap<String, Set<Runnable>> listeners = new ConcurrentHashMap<>();

	private final AtomicLong redisPolls = new AtomicLong();

	private final AtomicLong redisConfirmations = new AtomicLong();

	private final AtomicLong localSignals = new AtomicLong();

	private volatile boolean degraded;

	/** 降级期的轮询节流（见 {@link #redisValue(String)}）。 */
	private final AtomicLong lastDegradedPollAtMs = new AtomicLong();

	/** 降级期两次 Redis 轮询之间的最少间隔（避免"每拍都撞一次超时"）。 */
	private static final long DEGRADED_POLL_BACKOFF_MS = 5000L;

	public CancellationRegistry(StringRedisTemplate redis, AiProperties properties) {
		this.redis = redis;
		this.properties = properties;
	}

	public String keyFor(String runId) {
		return CANCEL_PREFIX + runId;
	}

	/** 轮开始：登记进程内标志与监听者容器（不写 Redis —— 未取消的轮不该有键）。 */
	public void register(String runId) {
		this.flags.put(runId, new AtomicBoolean(false));
		this.listeners.putIfAbsent(runId, ConcurrentHashMap.newKeySet());
	}

	/** 最近一次取消标志读取失败的原因（退避期返回 UNKNOWN 时用于保留 cause）。 */
	private volatile Throwable lastReadFailure;

	/**
	 * 是否已取消。<b>Redis 为准</b>：进程内为假时读一次 Redis，命中即把加速态一并置位。
	 *
	 * <p><b>A5</b>：读失败且本地没有确定的取消事实时，<b>拒绝作答</b>（抛 {@link CancellationUnknownException}），
	 * 不再返回 {@code false} —— "读不出来"不等于"未取消"。需要显式处理未知的调用点用
	 * {@link #cancelState(String)}。
	 *
	 * @throws CancellationUnknownException Redis 读失败且本地没有确定的取消事实
	 * @deprecated 布尔口无法表达"未知"；新调用点请用 {@link #cancelState(String)}（A5）。
	 */
	@Deprecated
	public boolean isCancelled(String runId) {
		CancelState state = cancelState(runId);
		if (state == CancelState.CANCELLED) {
			return true;
		}
		if (state == CancelState.NOT_CANCELLED) {
			return false;
		}
		throw new CancellationUnknownException(runId, this.lastReadFailure);
	}

	/**
	 * 取消判定的三态（A5）：读失败是 {@link CancelState#UNKNOWN}，不是"未取消"。
	 *
	 * <p>本地已置位（{@link #cancel(String, String)} 或监听者）时是确定的 {@code CANCELLED}，
	 * 不依赖 Redis —— 保留单实例加速/降级通道；只有"本地未取消 + Redis 读不出来"才是未知。
	 *
	 * <p>Minor 5：成功从 Redis 读到取消事实时，<b>只对已 register 的轮</b>回填本地加速态；
	 * 续跑扫描/清单观察到的其它轮不建本地 flag（否则进程内 {@code flags} 单调增长且永久 sticky）。
	 */
	public CancelState cancelState(String runId) {
		AtomicBoolean flag = this.flags.get(runId);
		if (flag != null && flag.get()) {
			return CancelState.CANCELLED;
		}
		RedisRead read = readRedisFlag(runId);
		if (read.failed()) {
			RedisDegradation.record(RedisDegradation.A5_CANCEL_UNKNOWN);
			return CancelState.UNKNOWN;
		}
		if (read.value() != null) {
			if (flag != null) {
				this.redisConfirmations.incrementAndGet();
				markLocal(runId);
			}
			return CancelState.CANCELLED;
		}
		return CancelState.NOT_CANCELLED;
	}

	/**
	 * 发起取消：先写 Redis（权威），再唤起本进程监听者（加速）。
	 *
	 * @return 本次调用产生的取消事实（供端点返回体取证）
	 */
	public CancelResult cancel(String runId, String requestedBy) {
		boolean woke = markLocal(runId);
		boolean redisWritten = writeRedisFlag(runId, requestedBy);
		if (woke) {
			this.localSignals.incrementAndGet();
		}
		return new CancelResult(redisWritten, woke, runId);
	}

	/** 轮终态：清进程内状态（Redis 标志留着，见类注释）。 */
	public void unregister(String runId) {
		this.flags.remove(runId);
		Set<Runnable> set = this.listeners.remove(runId);
		if (set != null) {
			set.clear();
		}
	}

	/**
	 * 登记一个"被取消时立即执行"的监听者（加速通道；跨进程时不会被调用）。
	 *
	 * @return 注销句柄（轮终态必须关闭）
	 */
	public AutoCloseable onCancel(String runId, Runnable listener) {
		Set<Runnable> set = this.listeners.computeIfAbsent(runId, key -> ConcurrentHashMap.newKeySet());
		set.add(listener);
		return () -> {
			Set<Runnable> current = this.listeners.get(runId);
			if (current != null) {
				current.remove(listener);
			}
		};
	}

	public Map<String, Object> status(String runId) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("runId", runId);
		out.put("key", keyFor(runId));
		RedisRead read = readRedisFlag(runId);
		out.put("redisFlag", read.value());
		AtomicBoolean flag = this.flags.get(runId);
		out.put("localFlag", flag != null && flag.get());
		out.put("redisReadFailed", read.failed());
		if (read.failed()) {
			out.put("cancelState", CancelState.UNKNOWN.name());
		}
		else if (flag != null && flag.get()) {
			out.put("cancelState", CancelState.CANCELLED.name());
		}
		else {
			out.put("cancelState", read.value() != null ? CancelState.CANCELLED.name()
					: CancelState.NOT_CANCELLED.name());
		}
		out.put("registered", flag != null);
		out.put("listeners", this.listeners.containsKey(runId) ? this.listeners.get(runId).size() : 0);
		out.put("redisPolls", this.redisPolls.get());
		out.put("cancelReadFailures", RedisDegradation.count(RedisDegradation.A5_CANCEL_READ));
		out.put("degraded", this.degraded);
		return out;
	}

	public boolean degraded() {
		return this.degraded;
	}

	public long polls() {
		return this.redisPolls.get();
	}

	public long confirmations() {
		return this.redisConfirmations.get();
	}

	public long localSignals() {
		return this.localSignals.get();
	}

	/** 当前正在登记（可能仍在跑）的轮数。 */
	public int registeredRuns() {
		return this.flags.size();
	}

	// ── 内部 ────────────────────────────────────────────────────────────────

	/** 置位加速态并唤起监听者；返回是否"此前未置位、本次置位"。 */
	private boolean markLocal(String runId) {
		AtomicBoolean flag = this.flags.computeIfAbsent(runId, key -> new AtomicBoolean(false));
		boolean newly = flag.compareAndSet(false, true);
		Set<Runnable> set = this.listeners.get(runId);
		if (set != null) {
			for (Runnable listener : set) {
				try {
					listener.run();
				}
				catch (Exception ex) {
					log.debug("取消监听者执行失败 runId={}：{}", runId, ex.getMessage());
				}
			}
		}
		return newly;
	}

	private RedisRead readRedisFlag(String runId) {
		// §13.1 降级期的退避：Redis 不可用时，同步 GET 会阻塞到 spring.data.redis.timeout（默认 3s），
		// 而看门狗每秒一拍 —— 若不做节流，一个不可用的 Redis 会把看门狗线程池按在超时上。
		// 退避期间不访问 Redis，但状态是"未知"而不是"未取消"（A5）：调用方必须知道这一拍没有权威答案。
		// 恢复后 ≤ 本间隔即重新开始轮询，语义不变（Redis 仍为准）。
		if (this.degraded
				&& System.currentTimeMillis() - this.lastDegradedPollAtMs.get() < DEGRADED_POLL_BACKOFF_MS) {
			// 退避期不访问 Redis，但仍是 UNKNOWN；调用方（cancelState）会计数，这里留 debug 供排障。
			log.debug("取消标志读取处于 5s 退避期，本拍返回 UNKNOWN runId={}", runId);
			return RedisRead.unknown(this.lastReadFailure);
		}
		this.lastDegradedPollAtMs.set(System.currentTimeMillis());
		this.redisPolls.incrementAndGet();
		try {
			Object value = this.redis.opsForValue().get(keyFor(runId));
			this.degraded = false;
			this.lastReadFailure = null;
			return RedisRead.value(value);
		}
		catch (Exception ex) {
			// 退避期也要计数（Minor 1）：否则一次持续故障只在首次翻转时留下一个"无 cause、只有一次"
			// 的事实，排障时既看不到频率也看不到根因。
			RedisDegradation.record(RedisDegradation.A5_CANCEL_READ, ex);
			this.lastReadFailure = ex;
			if (!this.degraded) {
				this.degraded = true;
				log.warn("取消标志读取失败，降级为进程内取消（单实例等价，跨实例失效）：{}", ex.getMessage());
			}
			return RedisRead.unknown(ex);
		}
	}

	/** 取消标志的一次读取：{@code failed=true} = 读失败（未知），与"读成功且值为 null"区分。 */
	private record RedisRead(Object value, boolean failed, Throwable cause) {

		private static RedisRead value(Object value) {
			return new RedisRead(value, false, null);
		}

		private static RedisRead unknown(Throwable cause) {
			return new RedisRead(null, true, cause);
		}

	}

	private boolean writeRedisFlag(String runId, String requestedBy) {
		Duration ttl = cancelFlagTtl();
		try {
			this.redis.opsForValue().set(keyFor(runId), requestedBy == null ? "unknown" : requestedBy, ttl);
			return true;
		}
		catch (Exception ex) {
			this.degraded = true;
			log.warn("取消标志写入 Redis 失败（仅进程内生效）runId={}：{}", runId, ex.getMessage());
			return false;
		}
	}

	/**
	 * 取消标志的存活期 = <b>{@code app.ai.session-ttl}（缺省 24h）</b>，与会话同寿。
	 *
	 * <p>
	 * 超时面治理批① 第 11 项（用户裁决「取消信号随会话共亡」）：原值 {@code total-budget × 2 = 600s}
	 * 只说"10 分钟内可见"，而会话本身有 24h —— 于是一次挂起 15 分钟后取消的轮次，其取消标志可能已
	 * 先于会话消失（续跑/重挂在 10min~24h 之间读到的是"未取消"，会把已取消的轮重新捡起来）。
	 * 取消标志与 run 快照/待决条目/帧归档同寿；它同时登记在
	 * {@link SessionKeyRegistry} 的读侧续期面（{@code touchActiveRun} 会像其他会话级键一样整额续期）。
	 */
	private Duration cancelFlagTtl() {
		Duration sessionTtl = this.properties.getSessionTtl();
		return sessionTtl != null ? sessionTtl : Duration.ofHours(24);
	}

	/**
	 * 一次取消的落地形态（端点返回体）。
	 *
	 * @param redisWritten 权威标志是否写进 Redis（false = 降级，仅进程内可见）
	 * @param wokeInProcess 是否当场唤起了本进程的监听者（false = 运行方在别的进程或已死，
	 * 由轮询/续跑拒绝兜住）
	 */
	public record CancelResult(boolean redisWritten, boolean wokeInProcess, String runId) {
	}

	/** 取消判定的三态（A5）：{@code UNKNOWN} = Redis 读失败且本地没有确定的取消事实。 */
	public enum CancelState {

		/** 确定已取消（本地加速态或 Redis 命中）。 */
		CANCELLED,

		/** 确定未取消（Redis 读成功且无该键）。 */
		NOT_CANCELLED,

		/** 未知（Redis 读失败 / 降级退避期）：调用方不得按"未取消"继续做不可逆动作。 */
		UNKNOWN

	}

	/**
	 * 取消状态未知（A5）：Redis 读失败，且本地没有"已取消"的确定事实。
	 *
	 * <p>为什么抛而不是返回 false：跨实例取消的权威态只在 Redis，读不出来时"未取消"是猜测；
	 * 把猜测当事实会让另一个实例发出的取消丢失（运行方一直跑到预算/超时）。需要"未知"这个
	 * 中间态的调用方用 {@link #cancelState(String)}；布尔口保持诚实，拒绝作答。
	 */
	public static class CancellationUnknownException extends com.example.configmgr.common.AiStateUnavailableException {

		private final String runId;

		public CancellationUnknownException(String runId, Throwable cause) {
			super("取消状态未知（Redis 读失败）runId=" + runId, cause);
			this.runId = runId;
		}

		public String runId() {
			return this.runId;
		}

	}

}
