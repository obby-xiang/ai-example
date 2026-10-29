package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.TenantKeyScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/ai/chat} 的双维度限流（安全防护专项批① §5.1 #3；依据
 * {@code 调研-AI输入输出安全防护业界惯例-2026-10-21.md} §4.3 / §6.4）。
 *
 * <h2>为什么是滑动窗口而不是固定窗口</h2>
 * 固定窗口（{@code INCR + EXPIRE}）在窗口边界会放过 2× 额度（第 59 秒连发 20 次、第 61 秒再连发
 * 20 次）。本闸拦的是<b>脚本连发</b>，边界突刺正是它的形态，故用<b>滑动窗口</b>：每次命中把时间戳
 * 落 ZSET，窗口外的成员在同一个 Lua 里先回收 —— 语义 = "任意 60 秒内不超过 N 次"。
 *
 * <h2>为什么在 Lua 里做</h2>
 * 计数与判定必须<b>单键原子</b>（§6.4"限流计数直接用 Redis 原语，不要新造设施"）：
 * ZREMRANGEBYSCORE → ZCARD → ZADD 三步拆到 Java 侧，并发下会同时放过多条。
 * 时间取 Redis 自己的 {@code TIME}（不是各实例的 {@code currentTimeMillis}）—— 多实例时钟漂移
 * 不会把同一窗口切成两半。
 *
 * <h2>两个维度</h2>
 * <ul>
 * <li>{@code ai:rate:session:<tenantId>:<userId>:<sessionId>}：{@code app.ai.limit.session-per-minute}
 * （20/分）。<b>身份隔离（2026-10-30）</b>：会话维度按复合身份分桶 —— 跨租户/跨用户即使 sessionId
 * 相同也各有各的额度（不会互相消耗，也不会被别人的连发误伤）。无身份上下文时用 legacy 键
 * {@code ai:rate:session:<sessionId>}（独立过渡命名空间）。本仓 sessionId 由客户端自生，
 * 故它只拦"同一个页签被脚本连发"；</li>
 * <li>{@code ai:rate:ip:<RemoteAddr>}：{@code app.ai.limit.ip-per-minute}（60/分）。兜住"换
 * sessionId 绕开会话额度"。<b>刻意不</b>按身份分桶：它防的是同一来源的滥用，按身份分桶等于
 * 让"切身份"变成免费的额度翻倍。用 {@code RemoteAddr} 而<b>不</b>读 {@code X-Forwarded-For}
 * —— 后者可伪造，而本仓没有前置代理，读它等于把额度交给客户端自己声明。</li>
 * </ul>
 * 顺序：先会话后 IP。会话被拒时<b>不</b>消耗 IP 额度（两个独立单键脚本，天然如此）；
 * 反过来"会话放行、IP 被拒"会白占一个会话额度 —— 记录知悉，量级可忽略（60 ≫ 20）。
 *
 * <h2>降级</h2>
 * Redis 计数不可用（异常 / 返回空）⇒ <b>放行</b> + WARN，与批②③的降级口径一致：限流是
 * "防滥用"而不是"准入前提"，设施抖动不该把对话一起判死。注意这与 {@code /chat} 开流前的
 * {@code RedisAvailability} 探活（503 快速失败）是两层：那一层判的是"该轮根本跑不起来"。
 */
@Slf4j
@Component
public class AiRateLimiter {

	/** 键族前缀（观测口径：{@code ai:} 下按用途分段）。 */
	public static final String KEY_PREFIX = "ai:rate:";

	/**
	 * 脚本分派标记（= 脚本正文首行注释）：单测与 {@code FakeRateRedis} 同取本常量，
	 * 避免"改脚本忘了改桩"的静默失配。
	 */
	public static final String SCRIPT_MARK = "RATE_SLIDING_WINDOW";

	/** 会话维度的键前缀。 */
	public static final String SESSION_KEY_PREFIX = KEY_PREFIX + "session:";

	/** IP 维度的键前缀。 */
	public static final String IP_KEY_PREFIX = KEY_PREFIX + "ip:";

	/** 降级放行 / 额度内的判定结论里 `scope` 的取值。 */
	public static final String SCOPE_NONE = "none";

	public static final String SCOPE_SESSION = "session";

	public static final String SCOPE_IP = "ip";

	/**
	 * 滑动窗口计数（单键原子）。
	 *
	 * <p>
	 * {@code ARGV[1]} = 窗口毫秒，{@code ARGV[2]} = 额度，{@code ARGV[3]} = 本次请求的唯一成员名
	 * （同一毫秒内的多次请求也必须各算一条，故成员不能只是时间戳）。
	 * 返回 {@code 放行(1|0)|窗口内计数|距最早一条滑出窗口的毫秒数}。
	 */
	private static final RedisScript<String> SLIDING_WINDOW = new DefaultRedisScript<>("""
			-- RATE_SLIDING_WINDOW
			local t = redis.call('TIME')
			local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
			local window = tonumber(ARGV[1])
			local limit = tonumber(ARGV[2])
			redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
			local count = redis.call('ZCARD', KEYS[1])
			local allowed = 0
			if count < limit then
			  redis.call('ZADD', KEYS[1], now, ARGV[3])
			  count = count + 1
			  allowed = 1
			end
			redis.call('PEXPIRE', KEYS[1], window)
			local retry = 0
			if allowed == 0 then
			  local first = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
			  if first[2] then
			    retry = math.max(1, tonumber(first[2]) + window - now)
			  end
			end
			return allowed .. '|' .. count .. '|' .. retry
			""", String.class);

	private final StringRedisTemplate redis;

	private final AiProperties properties;

	@Autowired
	public AiRateLimiter(StringRedisTemplate redis, AiProperties properties) {
		this.redis = redis;
		this.properties = properties;
	}

	/**
	 * 判定一次 {@code /chat}：先会话后 IP（各自单键原子；会话被拒则不动 IP 计数）。
	 *
	 * @param sessionId 已校验非空的会话 id
	 * @param clientIp 请求来源（{@code RemoteAddr}；取不到时由调用方给一个固定占位）
	 */
	public Decision check(String sessionId, String clientIp) {
		AiProperties.Limit limit = this.properties.getLimit();
		long windowMs = limit.getWindow().toMillis();
		// 会话维度按身份分桶（{@code ai:rate:session:<t>:<u>:<sid>}）；无上下文 = legacy 键形。
		Decision session = hit(TenantKeyScope.scoped(SESSION_KEY_PREFIX, sessionId),
				limit.getSessionPerMinute(), windowMs, SCOPE_SESSION);
		if (!session.allowed()) {
			return session;
		}
		return hit(IP_KEY_PREFIX + clientIp, limit.getIpPerMinute(), windowMs, SCOPE_IP);
	}

	/** 一次单键判定。任何 Redis 侧异常都收敛成"降级放行"，不向调用方抛。 */
	private Decision hit(String key, int limit, long windowMs, String scope) {
		try {
			String raw = this.redis.execute(SLIDING_WINDOW, List.of(key), String.valueOf(windowMs),
					String.valueOf(limit), UUID.randomUUID().toString());
			if (raw == null) {
				log.warn("限流计数无返回（脚本未执行），降级放行：scope={} key={}", scope, key);
				return Decision.degraded(scope);
			}
			String[] parts = raw.split("\\|");
			boolean allowed = parts.length > 0 && "1".equals(parts[0]);
			int used = parts.length > 1 ? parseInt(parts[1], limit) : limit;
			long retryAfterMs = parts.length > 2 ? parseLong(parts[2]) : 0L;
			return allowed ? Decision.allowed(used, limit)
					: new Decision(false, scope, used, limit, retryAfterMs, false);
		}
		catch (Exception ex) {
			log.warn("限流计数不可用（Redis 异常），降级放行：scope={} key={} error={}: {}", scope, key,
					ex.getClass().getSimpleName(), ex.getMessage());
			return Decision.degraded(scope);
		}
	}

	private static int parseInt(String raw, int fallback) {
		try {
			return Integer.parseInt(raw.trim());
		}
		catch (RuntimeException ex) {
			return fallback;
		}
	}

	private static long parseLong(String raw) {
		try {
			return Long.parseLong(raw.trim());
		}
		catch (RuntimeException ex) {
			return 0L;
		}
	}

	/**
	 * 判定结论。
	 *
	 * @param allowed 是否放行
	 * @param scope 被拒的维度（{@code session}/{@code ip}）；放行与降级时为
	 * {@link #SCOPE_NONE}/{@code session|ip} 的"已判过的那一维"
	 * @param used 窗口内已计数（放行时含本次）
	 * @param limit 该维度额度
	 * @param retryAfterMs 距额度恢复的毫秒数（放行时为 0）
	 * @param degraded 是否因设施不可用而放行
	 */
	public record Decision(boolean allowed, String scope, int used, int limit, long retryAfterMs, boolean degraded) {

		static Decision allowed(int used, int limit) {
			return new Decision(true, SCOPE_NONE, used, limit, 0L, false);
		}

		static Decision degraded(String scope) {
			return new Decision(true, scope, 0, 0, 0L, true);
		}

		/** 给 {@code Retry-After} 用的秒数（向上取整，至少 1）。 */
		public long retryAfterSeconds() {
			return Math.max(1L, (this.retryAfterMs + 999L) / 1000L);
		}

	}

}
