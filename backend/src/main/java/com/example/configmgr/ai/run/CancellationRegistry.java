package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
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
 * Redis 不可用（异常）时，{@link #isCancelled(String)} 退回进程内标志并记降级事实；
 * 即"单实例等价、跨实例失去取消能力"，与 {@code SessionGate} 的降级口径一致，不静默。
 *
 * <h2>键的清理</h2>
 * 取消标志<b>故意不随轮终态删除</b>：它要在 TTL 内继续可见（取证），并且"取消后立刻重启"
 * 时仍然拦得住续跑。TTL = {@code app.ai.resilience.total-budget} × 2。
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

	/**
	 * 是否已取消。<b>Redis 为准</b>：进程内为假时读一次 Redis，命中即把加速态一并置位。
	 */
	public boolean isCancelled(String runId) {
		AtomicBoolean flag = this.flags.get(runId);
		if (flag != null && flag.get()) {
			return true;
		}
		if (flag == null) {
			// 未登记的轮次：只有 Redis 能回答（例如运行在另一个实例上）
			return Boolean.TRUE.equals(redisFlag(runId));
		}
		Boolean remote = redisFlag(runId);
		if (Boolean.TRUE.equals(remote)) {
			this.redisConfirmations.incrementAndGet();
			markLocal(runId);
			return true;
		}
		return false;
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
		Object value = redisValue(runId);
		out.put("redisFlag", value);
		AtomicBoolean flag = this.flags.get(runId);
		out.put("localFlag", flag != null && flag.get());
		out.put("registered", flag != null);
		out.put("listeners", this.listeners.containsKey(runId) ? this.listeners.get(runId).size() : 0);
		out.put("redisPolls", this.redisPolls.get());
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

	private Boolean redisFlag(String runId) {
		Object value = redisValue(runId);
		return value != null;
	}

	private Object redisValue(String runId) {
		// §13.1 降级期的退避：Redis 不可用时，同步 GET 会阻塞到 spring.data.redis.timeout（默认 3s），
		// 而看门狗每秒一拍 —— 若不做节流，一个不可用的 Redis 会把看门狗线程池按在超时上。
		// 退避期间直接以进程内标志作答（本就不可能有跨进程信号到达：对面也连不上同一个 Redis）。
		// 恢复后 ≤ 本间隔即重新开始轮询，语义不变（Redis 仍为准）。
		if (this.degraded
				&& System.currentTimeMillis() - this.lastDegradedPollAtMs.get() < DEGRADED_POLL_BACKOFF_MS) {
			return null;
		}
		this.lastDegradedPollAtMs.set(System.currentTimeMillis());
		this.redisPolls.incrementAndGet();
		try {
			Object value = this.redis.opsForValue().get(keyFor(runId));
			this.degraded = false;
			return value;
		}
		catch (Exception ex) {
			if (!this.degraded) {
				this.degraded = true;
				log.warn("取消标志读取失败，降级为进程内取消（单实例等价，跨实例失效）：{}", ex.getMessage());
			}
			return null;
		}
	}

	private boolean writeRedisFlag(String runId, String requestedBy) {
		Duration ttl = this.properties.getResilience().getTotalBudget().multipliedBy(2);
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
	 * 一次取消的落地形态（端点返回体）。
	 *
	 * @param redisWritten 权威标志是否写进 Redis（false = 降级，仅进程内可见）
	 * @param wokeInProcess 是否当场唤起了本进程的监听者（false = 运行方在别的进程或已死，
	 * 由轮询/续跑拒绝兜住）
	 */
	public record CancelResult(boolean redisWritten, boolean wokeInProcess, String runId) {
	}

}
