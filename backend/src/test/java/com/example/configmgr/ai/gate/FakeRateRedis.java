package com.example.configmgr.ai.gate;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 限流（批① 闸门 3）的单测底座 —— <b>不为测试起真实 Redis</b>，与 {@code FakeLeaseRedis} /
 * {@code FakeFrameRedis} 同口径：只建模产品代码实际发出的命令面，在 Java 侧逐行复刻
 * {@code -- RATE_SLIDING_WINDOW} 的语义。
 *
 * <p>
 * 建模面：{@code redis.execute(RedisScript, keys, args)} 一条 —— Lua 里的
 * {@code TIME} / {@code ZREMRANGEBYSCORE} / {@code ZCARD} / {@code ZADD} / {@code PEXPIRE} /
 * {@code ZRANGE ... WITHSCORES} 由 {@link #zsets} + {@link #nowMs} 复刻。
 * 断言打的是"<b>脚本语义</b>"（窗口滑过即回收）而不是"脚本原文"。
 *
 * <p>
 * <b>时间可控</b>：{@link #nowMs} 就是脚本里 {@code TIME} 的替身。用例推进它 = 让窗口滑过，
 * 不需要真的 sleep（"窗口滑动恢复"因此是确定性用例）。
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
public final class FakeRateRedis {

	/** 脚本分派标记（必须与 {@link AiRateLimiter} 脚本正文里的注释一致）。 */
	static final String MARK = "RATE_SLIDING_WINDOW";

	/** ZSET 键面：键 → (成员 → 分值=毫秒时间戳)。 */
	public final Map<String, Map<String, Long>> zsets = new ConcurrentHashMap<>();

	/** 键 → 最近一次 {@code PEXPIRE} 的时长（证明窗口键有 TTL、不会永久驻留）。 */
	public final Map<String, Duration> expiries = new ConcurrentHashMap<>();

	/** 脚本内 {@code TIME} 的替身（毫秒）；用例直接推进它来滑窗口。 */
	public final AtomicLong nowMs = new AtomicLong(1_760_000_000_000L);

	/** 走过的脚本次数（判定"判定确实走了一次原子脚本"）。 */
	public final AtomicInteger scriptCalls = new AtomicInteger();

	/** 置 true = 模拟 Redis 宕机（所有命令抛出 ⇒ 限流降级放行）。 */
	public volatile boolean down;

	public final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	public FakeRateRedis() {
		when(this.redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
				.thenAnswer(invocation -> handleScript(invocation.getArgument(0), invocation.getArgument(1),
						trailingArguments(invocation.getArguments())));
	}

	/** 当前某键的计数（用例断言用）。 */
	public int count(String key) {
		return this.zsets.getOrDefault(key, Map.of()).size();
	}

	private Object handleScript(RedisScript<?> script, List<?> keys, List<Object> args) {
		this.scriptCalls.incrementAndGet();
		if (this.down) {
			throw new IllegalStateException("Redis 不可用（模拟宕机）");
		}
		String body = script.getScriptAsString();
		if (!body.contains(MARK)) {
			throw new UnsupportedOperationException("未建模的脚本：" + body);
		}
		String key = str(keys.get(0));
		long windowMs = Long.parseLong(str(args.get(0)));
		int limit = Integer.parseInt(str(args.get(1)));
		String member = str(args.get(2));
		long now = this.nowMs.get();

		Map<String, Long> zset = this.zsets.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
		// ZREMRANGEBYSCORE key -inf (now-window)：窗口外的成员先回收（含边界）
		zset.entrySet().removeIf((entry) -> entry.getValue() <= now - windowMs);
		int count = zset.size();
		int allowed = 0;
		if (count < limit) {
			zset.put(member, now);
			count++;
			allowed = 1;
		}
		this.expiries.put(key, Duration.ofMillis(windowMs));
		long retry = 0L;
		if (allowed == 0) {
			long first = zset.values().stream().mapToLong(Long::longValue).min().orElse(now);
			retry = Math.max(1L, first + windowMs - now);
		}
		return allowed + "|" + count + "|" + retry;
	}

	/** Mockito 的 varargs 可能展开成多个实参、也可能打包成数组；两种形态都摊平。 */
	private static List<Object> trailingArguments(Object[] raw) {
		List<Object> out = new ArrayList<>();
		for (int i = 2; i < raw.length; i++) {
			if (raw[i] instanceof Object[] packed) {
				out.addAll(List.of(packed));
			}
			else {
				out.add(raw[i]);
			}
		}
		return out;
	}

	private static String str(Object value) {
		return String.valueOf(value);
	}

}
