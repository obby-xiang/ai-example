package com.example.configmgr.ai.config;

import com.example.configmgr.ai.observe.RedisDegradation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 可用性前置探活（R4 裁决：AI 端点要"快速失败"，不能在 Redis 不可用时挂十几秒）。
 *
 * <h2>为什么需要它</h2>
 * AI 运行时把三类状态都放在 Redis（会话记忆 {@code chat:mem:*}、挂起态 {@code ai:run:*}、
 * 会话锁 {@code ai:session:lock:*}）。Redis 不可用时，一次 {@code POST /api/ai/chat} 会依次撞上
 * 5 次同步 Redis 操作，每次都要等 {@code spring.data.redis.timeout}（实测 3s × 5 ≈ 15.1s）
 * 才以一个 `Redis command timed out` 的 error 帧收场 —— 前端体验与语义都不好。
 * 本类在**进入流式管道之前**做一次带短超时的 PING，把这种情形变成
 * <b>HTTP 503 + {@code AI_REDIS_UNAVAILABLE}</b>（≤ {@code spring.data.redis.timeout}，默认已收紧到 2s）。
 *
 * <h2>为什么不给每次请求都加一次往返</h2>
 * 探活结果带缓存（正/负分开），健康时每 {@value #POSITIVE_TTL_MS}ms 才真发一次 PING，
 * 不可用时每 {@value #NEGATIVE_TTL_MS}ms 才重试一次 —— 稳态开销可忽略，且"Redis 刚恢复"
 * 最多延迟 {@value #NEGATIVE_TTL_MS}ms 被感知。
 *
 * <h2>降级而不是阻断业务</h2>
 * 只有 AI 端点用它。业务 API（H2/JPA）与纯 Redis 读端点（历史、取消标志）不经过这里，
 * 与 §13.1 的降级口径一致：Redis 挂掉不该连带把业务前端一起判死。
 */
@Slf4j
@Component
public class RedisAvailability {

	/**
	 * 探活成功后的缓存时长（毫秒）：<b>0 = 健康时不缓存</b>。
	 *
	 * <p>
	 * 为什么不缓存：一次本地 PING 的成本远低于"Redis 恰好在上一次探活之后挂掉"的漏检代价 ——
	 * 实测踩到过：{@code /health} 刚探活成功（缓存 1s 内），紧接着 Redis 被 {@code CLIENT PAUSE}，
	 * 进来的一次 {@code /chat} 因为命中正缓存而跳过探活，于是照样走了 5 次串行 Redis 操作、10.1s 才报错。
	 * PING 本身是微秒级（同一台机器），AI 端点每次请求多一次 PING 可以忽略。
	 */
	public static final long POSITIVE_TTL_MS = 0L;

	/**
	 * 探活失败后的缓存时长（毫秒）：不可用期间每本间隔才重探一次（其余请求立即 503），
	 * 也是"Redis 恢复后最迟多久被重新感知"的上界。
	 */
	public static final long NEGATIVE_TTL_MS = 5000L;

	private final StringRedisTemplate redis;

	private final AtomicLong probes = new AtomicLong();

	private final AtomicLong failures = new AtomicLong();

	private volatile long checkedAtMs;

	private volatile boolean available = true;

	private volatile String lastError;

	public RedisAvailability(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/** 探活（带缓存）。不可用时返回 false，调用方据此在开流之前 503。 */
	public boolean isAvailable() {
		long now = System.currentTimeMillis();
		long ttl = this.available ? POSITIVE_TTL_MS : NEGATIVE_TTL_MS;
		if (now - this.checkedAtMs < ttl) {
			return this.available;
		}
		synchronized (this) {
			now = System.currentTimeMillis();
			ttl = this.available ? POSITIVE_TTL_MS : NEGATIVE_TTL_MS;
			if (now - this.checkedAtMs < ttl) {
				return this.available;
			}
			this.checkedAtMs = now;
			this.probes.incrementAndGet();
			try {
				String pong = this.redis.execute((RedisCallback<String>) (RedisConnection connection) -> connection.ping());
				boolean ok = pong != null;
				if (!ok) {
					this.failures.incrementAndGet();
					this.lastError = "PING 返回空";
				}
				this.available = ok;
				this.lastError = ok ? null : this.lastError;
			}
			catch (Exception ex) {
				this.available = false;
				this.failures.incrementAndGet();
				this.lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
				log.warn("Redis 前置探活失败，AI 端点将快速失败（503 AI_REDIS_UNAVAILABLE）：{}", this.lastError);
			}
			return this.available;
		}
	}

	/** 探活态（仅供 {@code GET /api/ai/health} 与证据）：含原始 {@code lastError} 与统一降级计数。 */
	public Map<String, Object> describe() {
		Map<String, Object> out = describeForResponse();
		out.put("lastError", this.lastError);
		// Cluster / 5.0 可观测批：A1/A3/A4/A5/A6 + L-139 + B3 的降级事件计数。
		// 只进运维健康面；503 响应体（describeForResponse）的键面与判定不改。
		out.put("degradations", RedisDegradation.snapshot());
		return out;
	}

	/**
	 * 503 响应体里的 redis 子对象：只回安全事实（判据、探活次数、失败次数、时间锚点）。
	 *
	 * <p>为什么去掉 {@code lastError}：它含异常类名与连接地址（如
	 * {@code RedisConnectionFailureException: … 127.0.0.1:6379}），属于实现细节，不该出现在
	 * 普通用户可见的 503 body 里。诊断不丢 —— 原始值仍进 WARN 日志与 {@code /health} 的
	 * {@link #describe()}；二者与这里同源（本方法先出安全子集，{@code describe()} 再补诊断字段）。
	 */
	public Map<String, Object> describeForResponse() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("available", this.available);
		out.put("probes", this.probes.get());
		out.put("failures", this.failures.get());
		out.put("checkedAgoMs", System.currentTimeMillis() - this.checkedAtMs);
		out.put("positiveTtlMs", POSITIVE_TTL_MS);
		out.put("negativeTtlMs", NEGATIVE_TTL_MS);
		return out;
	}

	/** 503 响应体的 code。 */
	public static String code() {
		return "AI_REDIS_UNAVAILABLE";
	}

	/**
	 * 503 响应体的 message（用户面）：短句、可操作，不含异常类名/连接地址等内部实现细节。
	 *
	 * <p>诊断信息不丢：探活失败的类名与地址仍留在 WARN 日志与 /health 的 {@code lastError}；
	 * 503 体走 {@link #describeForResponse()}，不带 {@code lastError}。
	 */
	public String reason() {
		return "AI 服务暂不可用，请稍后重试。";
	}

}
