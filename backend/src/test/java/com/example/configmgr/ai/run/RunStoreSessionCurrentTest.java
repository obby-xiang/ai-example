package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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

	/** {@code ai:session:current:<sid>} 的 TTL 续期次数（模板 {@code EXPIRE} 与 Lua {@code PEXPIRE} 同口径）。 */
	private final Map<String, Integer> ttlTouches = new HashMap<>();

	/**
	 * 确定性交错注入点：<b>读到键值之后、返回之前</b>执行（参数 = 键）。
	 *
	 * <p>用例借此在"fallback 已算出 / 最终写之前"把 current 换成同会话的另一轮 —— 不需要 sleep。
	 */
	private Consumer<String> afterValueGet = key -> {
	};

	private final RunStore store = new RunStore(this.redis, new ObjectMapper(), this.properties);

	RunStoreSessionCurrentTest() {
		when(this.redis.opsForValue()).thenReturn(this.valueOps);
		doAnswer(invocation -> {
			this.strings.put(invocation.getArgument(0), invocation.getArgument(1));
			return null;
		}).when(this.valueOps).set(anyString(), anyString(), any(Duration.class));
		when(this.valueOps.get(anyString())).thenAnswer(invocation -> {
			String key = invocation.getArgument(0);
			String value = this.strings.get(key);
			this.afterValueGet.accept(key);
			return value;
		});
		when(this.redis.delete(anyString()))
			.thenAnswer(invocation -> this.strings.remove(invocation.getArgument(0)) != null);
		when(this.redis.expire(anyString(), any(Duration.class))).thenAnswer(invocation -> {
			this.ttlTouches.merge(invocation.getArgument(0), 1, Integer::sum);
			return Boolean.TRUE;
		});
		when(this.redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
			.thenAnswer(invocation -> handleSessionCurrentScript(invocation.getArgument(0), invocation.getArgument(1),
					trailingArguments(invocation.getArguments())));
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
		String key = "ai:session:current:s-1";
		int before = ttlTouchCount(key);

		this.store.renewSessionCurrent("s-1", "r-2");
		assertEquals(before, ttlTouchCount(key), "值不匹配时不得续期");

		this.store.renewSessionCurrent("s-1", "r-1");
		assertEquals(before + 1, ttlTouchCount(key), "值匹配时续期一次（模板 EXPIRE 或单键 Lua PEXPIRE）");
	}

	/**
	 * MI2（TOCTOU）：{@code GET} 之后 current 被换成同会话的另一轮 —— 旧代码仍按读到的旧值
	 * {@code EXPIRE}（把别人的 TTL 续了），新代码的单键 Lua compare-and-PEXPIRE 发现值已不匹配即不动作。
	 *
	 * <p>竞态由 {@link #afterValueGet} 在"读到值之后、返回之前"确定性地注入，<b>不用 sleep</b>。
	 */
	@Test
	void renewDoesNotExtendWhenCurrentChangesAfterGet() {
		this.store.markSessionCurrent("s-renew", "r-1");
		String key = this.store.sessionCurrentKey("s-renew");
		int before = ttlTouchCount(key);
		this.afterValueGet = readKey -> {
			if (key.equals(readKey)) {
				this.strings.put(key, "r-new");
			}
		};

		this.store.renewSessionCurrent("s-renew", "r-1");

		assertEquals(before, ttlTouchCount(key), "current 已被换成他轮：不得按旧值续期");
		assertEquals("r-new", this.store.sessionCurrentRunId("s-renew"));
	}

	/**
	 * MI1（TOCTOU）：current 在"fallback 已算出、最终写之前"被同会话的另一轮替换 ——
	 * 旧实现按过期的读结果删键/覆盖，新实现的单键 Lua compare-and-write 看到 current 已不是本 runId
	 * 就不写。一个方法覆盖两种最终写形态：无回退 ⇒ {@code DEL}、有回退 ⇒ {@code SET} fallback。
	 */
	@Test
	void clearDoesNotWriteWhenCurrentChangesAfterFallbackChosen() {
		// 形态①：无回退（唯一候选已终态）⇒ 旧实现删键
		this.store.save(snapshot("r-old", "s-del", RunSnapshot.DONE));
		this.store.markSessionCurrent("s-del", "r-1");
		ZSetOperations<String, String> active = mock(ZSetOperations.class);
		Set<ZSetOperations.TypedTuple<String>> delOrdered = new LinkedHashSet<>();
		delOrdered.add(ZSetOperations.TypedTuple.of("r-old", 10d));
		when(active.reverseRangeWithScores(this.store.sessionActiveKey("s-del"), 0, -1)).thenReturn(delOrdered);
		when(this.redis.opsForZSet()).thenReturn(active);
		String delKey = this.store.sessionCurrentKey("s-del");
		this.afterValueGet = key -> {
			if (this.store.runKey("r-old").equals(key)) {
				this.strings.put(delKey, "r-new");
			}
		};

		this.store.clearSessionCurrent("s-del", "r-1");

		assertEquals("r-new", this.store.sessionCurrentRunId("s-del"),
				"回退为空时只能删仍指向本 runId 的 current，不得删掉已换成他轮的 current");

		// 形态②：有回退（候选仍 SUSPENDED）⇒ 旧实现把 current 覆盖成 fallback
		this.store.save(snapshot("r-parked", "s-set", RunSnapshot.SUSPENDED));
		this.store.markSessionCurrent("s-set", "r-1");
		Set<ZSetOperations.TypedTuple<String>> setOrdered = new LinkedHashSet<>();
		setOrdered.add(ZSetOperations.TypedTuple.of("r-parked", 10d));
		when(active.reverseRangeWithScores(this.store.sessionActiveKey("s-set"), 0, -1)).thenReturn(setOrdered);
		String setKey = this.store.sessionCurrentKey("s-set");
		this.afterValueGet = key -> {
			if (this.store.runKey("r-parked").equals(key)) {
				this.strings.put(setKey, "r-new2");
			}
		};

		this.store.clearSessionCurrent("s-set", "r-1");

		assertEquals("r-new2", this.store.sessionCurrentRunId("s-set"),
				"回退回填只能写仍指向本 runId 的 current，不得覆盖已换成他轮的 current");
	}

	/**
	 * MI1（TOCTOU）· <b>老会话回退路径</b>：活跃索引不可用（升级前的会话，或设施不支持）⇒ 走
	 * {@code SMEMBERS ai:session:runs} + 逐个快照的旧口径算出 fallback。若在这之后 current 被同会话
	 * 新轮替换，最终写（无回退 ⇒ {@code DEL}、有回退 ⇒ {@code SET} fallback）仍必须是同一条单键 CAS：
	 * 不得删掉、也不得覆盖新值。
	 *
	 * <p>与 {@link #clearDoesNotWriteWhenCurrentChangesAfterFallbackChosen} 互补：那条走
	 * {@code reverseRangeWithScores} 的活跃索引快路径，本条走 SMEMBERS + 快照回退 —— 两种回退计算
	 * 读的是不同面，各自的最终写都得过同一道 CAS。
	 */
	@Test
	void legacyFallbackDoesNotWriteWhenCurrentChangesAfterFallbackComputed() {
		// 形态①：候选只剩终态轮 ⇒ 回退为空 ⇒ 最终写是 DEL
		this.store.save(snapshot("r-legacy-done", "s-legacy-del", RunSnapshot.DONE));
		this.store.markSessionCurrent("s-legacy-del", "r-1");
		when(this.setOps.members(this.store.sessionRunsKey("s-legacy-del")))
			.thenReturn(Set.of("r-legacy-done"));
		String delKey = this.store.sessionCurrentKey("s-legacy-del");
		this.afterValueGet = key -> {
			if (this.store.runKey("r-legacy-done").equals(key)) {
				this.strings.put(delKey, "r-new");
			}
		};

		this.store.clearSessionCurrent("s-legacy-del", "r-1");

		assertEquals("r-new", this.store.sessionCurrentRunId("s-legacy-del"),
				"老会话回退为空时也只能删仍指向本 runId 的 current，不得删掉已换成他轮的 current");

		// 形态②：候选仍 SUSPENDED ⇒ 回退非空 ⇒ 最终写是 SET fallback
		this.store.save(snapshot("r-legacy-parked", "s-legacy-set", RunSnapshot.SUSPENDED));
		this.store.markSessionCurrent("s-legacy-set", "r-1");
		when(this.setOps.members(this.store.sessionRunsKey("s-legacy-set")))
			.thenReturn(Set.of("r-legacy-parked"));
		String setKey = this.store.sessionCurrentKey("s-legacy-set");
		this.afterValueGet = key -> {
			if (this.store.runKey("r-legacy-parked").equals(key)) {
				this.strings.put(setKey, "r-new2");
			}
		};

		this.store.clearSessionCurrent("s-legacy-set", "r-1");

		assertEquals("r-new2", this.store.sessionCurrentRunId("s-legacy-set"),
				"老会话回退回填只能写仍指向本 runId 的 current，不得覆盖已换成他轮的 current");
	}

	/** 索引 TTL 的期望值：与实现同源实读（P2-3：app.ai.session-ttl），不用字面量锁死默认值。 */
	private Duration expectedIndexTtl() {
		return this.properties.getSessionTtl();
	}

	private int ttlTouchCount(String key) {
		return this.ttlTouches.getOrDefault(key, 0);
	}

	/** 单键 session→current 脚本的语义替身（按脚本正文标记分派；未建模脚本回 null = 既有 fallback）。 */
	private Object handleSessionCurrentScript(RedisScript<?> script, List<?> keys, List<Object> args) {
		String body = script.getScriptAsString();
		String key = String.valueOf(keys.get(0));
		String expected = String.valueOf(args.get(0));
		if (body.contains("SESSION_CURRENT_CAS_DELETE")) {
			if (!expected.equals(this.strings.get(key))) {
				return 0L;
			}
			this.strings.remove(key);
			return 1L;
		}
		if (body.contains("SESSION_CURRENT_CAS_SET")) {
			if (!expected.equals(this.strings.get(key))) {
				return 0L;
			}
			this.strings.put(key, String.valueOf(args.get(1)));
			return 1L;
		}
		if (body.contains("SESSION_CURRENT_CAS_PEXPIRE")) {
			if (!expected.equals(this.strings.get(key))) {
				return 0L;
			}
			this.ttlTouches.merge(key, 1, Integer::sum);
			return 1L;
		}
		return null;
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

	/**
	 * N1：终态后回指走"会话活跃有序索引"—— 只对候选快照做一次确认，不再
	 * {@code SMEMBERS ai:session:runs} + 逐个反序列化整份快照。
	 */
	@Test
	void terminalRoundPicksNewestActiveViaActiveIndexWithoutScanningEverySnapshot() {
		ZSetOperations<String, String> active = mock(ZSetOperations.class);
		when(this.redis.opsForZSet()).thenReturn(active);

		this.store.save(snapshot("r-parked", "s-1", RunSnapshot.SUSPENDED));
		this.store.save(snapshot("r-next", "s-1", RunSnapshot.RUNNING));

		// 活跃索引按最近一次活跃倒序：当前轮 r-next(20) 在前，r-parked(10) 在后。
		Set<ZSetOperations.TypedTuple<String>> ordered = new LinkedHashSet<>();
		ordered.add(ZSetOperations.TypedTuple.of("r-next", 20d));
		ordered.add(ZSetOperations.TypedTuple.of("r-parked", 10d));
		when(active.reverseRangeWithScores(this.store.sessionActiveKey("s-1"), 0, -1)).thenReturn(ordered);

		this.store.save(snapshot("r-next", "s-1", RunSnapshot.DONE));

		assertEquals("r-parked", this.store.sessionCurrentRunId("s-1"));
		verify(this.setOps, never()).members(this.store.sessionRunsKey("s-1"));
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
