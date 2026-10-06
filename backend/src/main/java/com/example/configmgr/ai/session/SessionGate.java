package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
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
 * 轮终态<b>主动释放</b>（只删自己的锁：值必须是本轮的 runId）。</li>
 * </ul>
 *
 * <h2>§13.1 降级</h2>
 * 锁服务不可用（Redis 异常）时降级为进程内单值门：M1 单实例下等价，冲突照样 409 提示重试，
 * 只是失去了跨实例互斥；降级事实写进返回体（{@code degraded=true}）与 WARN 日志，不静默。
 */
@Slf4j
@Component
public class SessionGate {

	public static final String LOCK_PREFIX = "ai:session:lock:";

	private final StringRedisTemplate redis;

	private final AiProperties.Session.Lock lockProperties;

	private final Map<String, Holder> inProcess = new ConcurrentHashMap<>();

	/** 看门狗线程（平台线程，DC-12 禁虚拟线程；仅做续期，无业务阻塞）。 */
	private final ScheduledExecutorService watchdog;

	private final AtomicLong renewals = new AtomicLong();

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
	 * 占用会话门。
	 *
	 * @return {@code null} = 取到门（可以开跑）；非 null = 已有一轮在跑，值里带进行中的 runId
	 */
	public Busy acquire(String sessionId, String runId) {
		Holder holder = new Holder(runId);
		Holder previous = this.inProcess.putIfAbsent(sessionId, holder);
		if (previous != null && !previous.runId.equals(runId)) {
			return new Busy(previous.runId, previous.degraded);
		}

		String key = LOCK_PREFIX + sessionId;
		try {
			Boolean acquired = this.redis.opsForValue().setIfAbsent(key, runId, this.lockProperties.getTtl());
			if (!Boolean.TRUE.equals(acquired)) {
				// 另一实例持有：让出进程内门，按对方 runId 拒绝
				String other = this.redis.opsForValue().get(key);
				this.inProcess.remove(sessionId, holder);
				return new Busy(other == null ? previous == null ? runId : previous.runId : other, false);
			}
			startWatchdog(sessionId, runId, holder);
		}
		catch (Exception ex) {
			// §13.1：锁服务不可用 → 降级为进程内单值门（M1 单实例下等价）
			holder.degraded = true;
			log.warn("会话锁服务不可用，降级为进程内单值门 sessionId={}：{}", sessionId, ex.getMessage());
		}
		return null;
	}

	/** 轮终态主动释放（只释放自己持有的那一轮）。 */
	public void release(String sessionId, String runId) {
		Holder holder = this.inProcess.get(sessionId);
		if (holder != null && holder.runId.equals(runId)) {
			this.inProcess.remove(sessionId, holder);
			cancelWatchdog(holder);
		}
		String key = LOCK_PREFIX + sessionId;
		try {
			String value = this.redis.opsForValue().get(key);
			if (runId.equals(value)) {
				this.redis.delete(key);
			}
		}
		catch (Exception ex) {
			log.warn("释放会话锁失败 sessionId={} runId={}：{}", sessionId, runId, ex.getMessage());
		}
	}

	/** 进行中轮的 runId（null = 空闲）。 */
	public String currentRunId(String sessionId) {
		Holder holder = this.inProcess.get(sessionId);
		if (holder != null) {
			return holder.runId;
		}
		try {
			return this.redis.opsForValue().get(LOCK_PREFIX + sessionId);
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

	private void startWatchdog(String sessionId, String runId, Holder holder) {
		Duration interval = this.lockProperties.getWatchdog();
		Duration ttl = this.lockProperties.getTtl();
		long period = Math.max(1, interval.toMillis());
		holder.watchdog = this.watchdog.scheduleAtFixedRate(() -> {
			try {
				Boolean renewed = this.redis.expire(LOCK_PREFIX + sessionId, ttl);
				if (Boolean.TRUE.equals(renewed)) {
					this.renewals.incrementAndGet();
				}
				else {
					log.warn("会话锁续期失败（键已不存在）sessionId={} runId={}", sessionId, runId);
				}
			}
			catch (Exception ex) {
				holder.degraded = true;
				log.warn("会话锁续期异常，降级为进程内单值门 sessionId={}：{}", sessionId, ex.getMessage());
			}
		}, period, period, TimeUnit.MILLISECONDS);
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
	 * @param runId 进行中那一轮的 runId（前端据此 reattach）
	 * @param degraded 是否为锁降级形态（Redis 锁不可用，仅进程内互斥）
	 */
	public record Busy(String runId, boolean degraded) {
	}

	private static final class Holder {

		private final String runId;

		private volatile boolean degraded;

		private volatile ScheduledFuture<?> watchdog;

		Holder(String runId) {
			this.runId = runId;
		}

	}

}
