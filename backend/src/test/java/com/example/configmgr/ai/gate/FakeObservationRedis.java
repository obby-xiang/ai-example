package com.example.configmgr.ai.gate;

import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 结算观测位的"真语义"Redis 替身：HASH（{@code HSETNX} / {@code HDEL}）、TTL（{@code EXPIRE}）、
 * 整键 {@code DEL}，以及 store 用的那把<b>单键脚本</b>（{@code HSETNX} + {@code EXPIRE}）。
 *
 * <p>为什么不用"逐个方法打桩"：生命周期契约（抢位/置 TTL/自愈/销毁/释放）是<b>状态机</b>，
 * 逐桩只能验证"调没调"，验证不了"键最终长什么样"。本替身按真实语义维护
 * {@link #hashes} 与 {@link #ttlMillis}，用例直接断言键面（存在/有 TTL/被删）。
 *
 * <p>{@link #failExpire} / {@link #failDelete} 用来注入依赖抖动：前者只影响 Java 侧的
 * TTL 自愈（脚本本身在 Redis 内原子执行），后者用来验证销毁失败会如实上报。
 */
final class FakeObservationRedis {

	/** HASH 面：key → (field → value)。 */
	final Map<String, Map<String, String>> hashes = new ConcurrentHashMap<>();

	/** TTL 面：key → 剩余毫秒（缺失 = 该键没有 TTL / 键不存在）。 */
	final Map<String, Long> ttlMillis = new ConcurrentHashMap<>();

	/** 让下一次及之后的 {@code EXPIRE} 抛错（模拟连接抖动）。 */
	volatile boolean failExpire;

	/** 让 {@code DEL} 抛错（模拟销毁失败）。 */
	volatile boolean failDelete;

	final StringRedisTemplate template = mock(StringRedisTemplate.class);

	@SuppressWarnings("unchecked")
	FakeObservationRedis() {
		HashOperations<String, Object, Object> hash = mock(HashOperations.class);
		when(this.template.opsForHash()).thenReturn(hash);
		when(hash.putIfAbsent(any(), any(), any())).thenAnswer(invocation -> {
			Map<String, String> table = table(invocation.getArgument(0));
			return table.putIfAbsent(String.valueOf(invocation.getArgument(1)),
					String.valueOf(invocation.getArgument(2))) == null;
		});
		when(hash.delete(any(), any())).thenAnswer(invocation -> {
			Map<String, String> table = this.hashes.get(invocation.getArgument(0));
			if (table == null) {
				return 0L;
			}
			return table.remove(invocation.getArgument(1)) == null ? 0L : 1L;
		});
		when(this.template.expire(any(), any(Duration.class))).thenAnswer(invocation -> {
			if (this.failExpire) {
				throw new IllegalStateException("模拟 EXPIRE 失败");
			}
			this.ttlMillis.put(invocation.getArgument(0), invocation.getArgument(1, Duration.class).toMillis());
			return true;
		});
		when(this.template.delete(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
			if (this.failDelete) {
				throw new IllegalStateException("模拟 DEL 失败");
			}
			String key = invocation.getArgument(0);
			this.ttlMillis.remove(key);
			return this.hashes.remove(key) != null;
		});
		// 单键脚本：HSETNX 决定首写者，随后无条件 EXPIRE（与生产脚本逐字同语义）
		when(this.template.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(invocation -> {
			String key = ((List<String>) invocation.getArgument(1)).get(0);
			Object[] args = invocation.getArguments();
			// Mockito 对 varargs 有两种暴露形态：摊平到实参位（5 个）或原样一个数组（3 个）
			Object field = args.length >= 5 ? args[2] : ((Object[]) args[2])[0];
			Object value = args.length >= 5 ? args[3] : ((Object[]) args[2])[1];
			Object ttl = args.length >= 5 ? args[4] : ((Object[]) args[2])[2];
			boolean first = table(key).putIfAbsent(String.valueOf(field), String.valueOf(value)) == null;
			// TTL ARGV 的**正确类型是字符串**（StringRedisSerializer 的边界，复审 R-C-1）。
			// 本替身按语义模拟、不模拟序列化层，故两种形态都收；真实类型由
			// ParkSettlementOwnershipTest#claimArbitratesThroughARealStringRedisTemplate 钉住。
			this.ttlMillis.put(key, ttlSecondsOf(ttl) * 1000L);
			return first ? 1L : 0L;
		});
	}

	private static long ttlSecondsOf(Object ttl) {
		if (ttl instanceof Number number) {
			return number.longValue();
		}
		return Long.parseLong(String.valueOf(ttl));
	}

	/** 该键当前是否存在（HASH 面）。 */
	boolean hasKey(String key) {
		Map<String, String> table = this.hashes.get(key);
		return table != null && !table.isEmpty();
	}

	/** 该键当前是否有 TTL（{@code false} = 无 TTL 或键不存在）。 */
	boolean hasTtl(String key) {
		return this.ttlMillis.containsKey(key);
	}

	/** 直接铺一个"有键无 TTL"的旧墓碑，用来复现"上次过期失败"的残留状态。 */
	void seedTombstone(String key, String field) {
		table(key).put(field, "0|seeded");
		this.ttlMillis.remove(key);
	}

	private Map<String, String> table(String key) {
		return this.hashes.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
	}

}
