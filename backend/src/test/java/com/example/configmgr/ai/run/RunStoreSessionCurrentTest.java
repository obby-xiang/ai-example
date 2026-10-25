package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T2b-D#1：{@code ai:session:current:<sessionId>} session → 当前挂起轮索引。
 *
 * <p>Redis 用 Map 支撑的桩（{@code StringRedisTemplate} mock）：索引是体验优化不是正确性依赖，
 * 所以除"SET 带 session-ttl"用 verify 锁定外，其余断言都走公开方法的语义面。
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
class RunStoreSessionCurrentTest {

	private final Map<String, String> strings = new HashMap<>();

	private final Map<String, Map<String, String>> hashes = new HashMap<>();

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

	private final HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);

	private final SetOperations<String, String> setOps = mock(SetOperations.class);

	/** 与 RunStore 同源注入的属性实例：TTL 期望值从这里实读，不硬编码默认值（红队 S3-7）。 */
	private final AiProperties properties = new AiProperties();

	private final RunStore store = new RunStore(this.redis, new ObjectMapper(), this.properties);

	RunStoreSessionCurrentTest() {
		when(this.redis.opsForValue()).thenReturn(this.valueOps);
		doAnswer(invocation -> {
			this.strings.put(invocation.getArgument(0), invocation.getArgument(1));
			return null;
		}).when(this.valueOps).set(anyString(), anyString(), any(Duration.class));
		when(this.valueOps.get(anyString())).thenAnswer(invocation -> this.strings.get(invocation.getArgument(0)));
		when(this.redis.delete(anyString()))
			.thenAnswer(invocation -> this.strings.remove(invocation.getArgument(0)) != null);
		when(this.redis.expire(anyString(), any(Duration.class))).thenReturn(Boolean.TRUE);
		when(this.redis.opsForHash()).thenReturn(this.hashOps);
		when(this.redis.opsForSet()).thenReturn(this.setOps);
		doAnswer(invocation -> {
			this.hashes.computeIfAbsent(invocation.getArgument(0), key -> new LinkedHashMap<>())
				.put(invocation.getArgument(1), invocation.getArgument(2));
			return null;
		}).when(this.hashOps).put(anyString(), any(), any());
		when(this.hashOps.entries(anyString())).thenAnswer(invocation -> {
			Map<String, String> hash = this.hashes.get(invocation.getArgument(0));
			return hash == null ? Map.of() : new LinkedHashMap<>(hash);
		});
	}

	@Test
	void suspendedSaveWritesIndexWithSessionTtl() {
		this.store.save(snapshot("r-1", "s-1", RunSnapshot.SUSPENDED));

		assertEquals("r-1", this.store.sessionCurrentRunId("s-1"));
		// TTL 期望值从测试所用的 AiProperties 实例实读（P2-3：取值源 app.ai.session-ttl，
		// 与会话/快照同寿；不再复用会话锁的 10m），不硬编码默认值（红队 S3-7）
		verify(this.valueOps).set(eq("ai:session:current:s-1"), eq("r-1"), eq(expectedIndexTtl()));
	}

	@Test
	void terminalSaveClearsIndex() {
		for (String terminal : new String[] { RunSnapshot.DONE, RunSnapshot.FAILED, RunSnapshot.CANCELLED }) {
			this.store.save(snapshot("r-1", "s-1", RunSnapshot.SUSPENDED));
			this.store.save(snapshot("r-1", "s-1", terminal));
			assertNull(this.store.sessionCurrentRunId("s-1"), "终态 " + terminal + " 应清除索引");
		}
	}

	@Test
	void runningSaveKeepsIndex() {
		this.store.save(snapshot("r-1", "s-1", RunSnapshot.SUSPENDED));
		// settle 会把快照打回 RUNNING —— 轮仍是 current，索引必须保留
		this.store.save(snapshot("r-1", "s-1", RunSnapshot.RUNNING));

		assertEquals("r-1", this.store.sessionCurrentRunId("s-1"));
	}

	/**
	 * 批G · G1：**在途（RUNNING）开轮即写索引** —— 纯文本轮从不 SUSPENDED，
	 * 旧实现下 {@code /runs/current} 恒 {@code found:false}，刷新后前端没有任何
	 * "这一轮还在跑"的可发现面（实证 A/A3/B/C2）。
	 */
	@Test
	void runningSaveWritesIndexForInFlightRound() {
		this.store.save(snapshot("r-1", "s-1", RunSnapshot.RUNNING));

		assertEquals("r-1", this.store.sessionCurrentRunId("s-1"));
		verify(this.valueOps).set(eq("ai:session:current:s-1"), eq("r-1"), eq(expectedIndexTtl()));
	}

	@Test
	void renewOnlyExtendsMatchingRunId() {
		this.store.markSessionCurrent("s-1", "r-1");

		this.store.renewSessionCurrent("s-1", "r-2");
		verify(this.redis, never()).expire(eq("ai:session:current:s-1"), any(Duration.class));

		this.store.renewSessionCurrent("s-1", "r-1");
		verify(this.redis).expire(eq("ai:session:current:s-1"), eq(expectedIndexTtl()));
	}

	/** 索引 TTL 的期望值：与实现同源实读（P2-3：app.ai.session-ttl），不用字面量锁死默认值。 */
	private Duration expectedIndexTtl() {
		return this.properties.getSessionTtl();
	}

	@Test
	void clearOnlyDeletesMatchingRunId() {
		this.store.markSessionCurrent("s-1", "r-1");

		this.store.clearSessionCurrent("s-1", "r-2");
		assertEquals("r-1", this.store.sessionCurrentRunId("s-1"));
		verify(this.redis, never()).delete("ai:session:current:s-1");

		this.store.clearSessionCurrent("s-1", "r-1");
		assertNull(this.store.sessionCurrentRunId("s-1"));
	}

	@Test
	void redisFailureDegradesInsteadOfThrowing() {
		when(this.redis.opsForValue()).thenThrow(new RuntimeException("boom"));

		// 索引是体验优化：Redis 异常一律降级（log.warn/debug），不向上抛
		assertDoesNotThrow(() -> this.store.markSessionCurrent("s-1", "r-1"));
		assertDoesNotThrow(() -> this.store.renewSessionCurrent("s-1", "r-1"));
		assertDoesNotThrow(() -> this.store.clearSessionCurrent("s-1", "r-1"));
		assertNull(this.store.sessionCurrentRunId("s-1"));
	}

	@Test
	void sameToolCallIdPutPendingTwiceKeepsSingleEntry() {
		PendingToolCall first = pending("c-1", PendingToolCall.PENDING);
		this.store.putPending("r-1", first);

		PendingToolCall rewrite = pending("c-1", PendingToolCall.FRONTEND_RESULT);
		this.store.putPending("r-1", rewrite);

		assertEquals(1, this.store.pendings("r-1").size());
		assertEquals(PendingToolCall.FRONTEND_RESULT, this.store.pendings("r-1").get(0).getStatus());
	}

	@Test
	void newerTerminalRoundRestoresTheOlderSuspendedPointer() {
		this.store.save(snapshot("r-parked", "s-1", RunSnapshot.SUSPENDED));
		this.store.save(snapshot("r-next", "s-1", RunSnapshot.RUNNING));
		when(this.setOps.members(this.store.sessionRunsKey("s-1"))).thenReturn(Set.of("r-parked", "r-next"));

		this.store.save(snapshot("r-next", "s-1", RunSnapshot.DONE));

		assertEquals("r-parked", this.store.sessionCurrentRunId("s-1"));
		this.store.save(snapshot("r-parked", "s-1", RunSnapshot.CANCELLED));
		assertNull(this.store.sessionCurrentRunId("s-1"));
	}

	private static RunSnapshot snapshot(String runId, String sessionId, String status) {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(runId);
		snapshot.setSessionId(sessionId);
		snapshot.setStatus(status);
		return snapshot;
	}

	private static PendingToolCall pending(String toolCallId, String status) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName("generative_form");
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setStatus(status);
		return pending;
	}

}
