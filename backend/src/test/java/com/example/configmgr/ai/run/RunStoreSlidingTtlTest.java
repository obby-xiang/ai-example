package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-10-20 用户裁决第 4 条的机器化落点：<b>run 级键滑动续期、无绝对上限</b>。
 *
 * <h2>锁的是什么（原 T3-8 用例的反向）</h2>
 * 原 {@code RunStoreTtlAbsoluteCapTest} 锁的是"续期不能突破 创建时刻 + session-ttl"，本用例锁的是
 * <b>它已退役</b>：
 * <ol>
 * <li>心跳（{@link RunStore#touchActivity}）续期<b>整额</b> session-ttl —— 一个"创建于 20 小时前"的轮
 * 照样被养满 24h（挂起等人的卡不会因为创建得早而被掐断）；</li>
 * <li>轮快照写入（{@link RunStore#save}）同样整额续期；</li>
 * <li>会话 TTL 默认 24h（6h → 24h）。</li>
 * </ol>
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
class RunStoreSlidingTtlTest {

	private final Map<String, String> strings = new HashMap<>();

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

	private final AiProperties properties = new AiProperties();

	private final ObjectMapper mapper = new ObjectMapper();

	private final RunStore store = new RunStore(this.redis, this.mapper, this.properties);

	RunStoreSlidingTtlTest() {
		when(this.redis.opsForValue()).thenReturn(this.valueOps);
		doAnswer(invocation -> {
			this.strings.put(invocation.getArgument(0), invocation.getArgument(1));
			return null;
		}).when(this.valueOps).set(anyString(), anyString(), any(Duration.class));
		when(this.valueOps.get(anyString())).thenAnswer(invocation -> this.strings.get(invocation.getArgument(0)));
	}

	@Test
	void sessionTtlDefaultIsTwentyFourHours() {
		assertThat(this.properties.getSessionTtl())
				.as("会话有效期 6h → 24h（裁决第 4 条）")
				.isEqualTo(Duration.ofHours(24));
	}

	@Test
	void heartbeatRenewsTheFullTtlEvenForAnOldRun() {
		String runId = "run-old-heartbeat";
		// 创建于 20 小时前：旧逻辑（创建+6h 封顶）下这个键早该"不再续期"
		seedSnapshot(runId, System.currentTimeMillis() - Duration.ofHours(20).toMillis());

		this.store.touchActivity(runId);

		assertThat(capturedTtl(this.store.beatKey(runId)))
				.as("滑动续期：心跳把键重新养满 24h，不再有创建时刻封顶")
				.isEqualTo(Duration.ofHours(24));
	}

	@Test
	void savingAnOldSnapshotAlsoRenewsTheFullTtl() {
		String runId = "run-old-save";
		RunSnapshot snapshot = seedSnapshot(runId, System.currentTimeMillis() - Duration.ofHours(30).toMillis());

		this.store.save(snapshot);

		assertThat(capturedTtl(this.store.runKey(runId)))
				.as("轮快照每次写入整额续期（已过 24h 的旧轮也不回落成最小正 TTL）")
				.isEqualTo(Duration.ofHours(24));
	}

	@Test
	void pendingAndLedgerKeysFollowTheSameSlidingTtl() {
		String runId = "run-old-pending";
		seedSnapshot(runId, System.currentTimeMillis() - Duration.ofHours(10).toMillis());
		when(this.redis.opsForHash()).thenReturn(mock(org.springframework.data.redis.core.HashOperations.class));
		when(this.redis.opsForList()).thenReturn(mock(org.springframework.data.redis.core.ListOperations.class));

		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("call-1");
		pending.setName("start_import");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		this.store.putPending(runId, pending);
		this.store.appendLedger(runId, pending, "ok");

		ArgumentCaptor<Duration> pendingTtl = ArgumentCaptor.forClass(Duration.class);
		verify(this.redis).expire(org.mockito.ArgumentMatchers.eq(this.store.pendingKey(runId)), pendingTtl.capture());
		ArgumentCaptor<Duration> ledgerTtl = ArgumentCaptor.forClass(Duration.class);
		verify(this.redis).expire(org.mockito.ArgumentMatchers.eq(this.store.ledgerKey(runId)), ledgerTtl.capture());
		assertThat(pendingTtl.getValue()).isEqualTo(Duration.ofHours(24));
		assertThat(ledgerTtl.getValue()).isEqualTo(Duration.ofHours(24));
	}

	@Test
	void suspensionHeartbeatRenewsTheCardBackingKeys() {
		String runId = "run-suspended-renew";

		this.store.renewSuspensionTtl(runId);

		ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
		verify(this.redis, org.mockito.Mockito.times(5))
				.expire(anyString(), ttl.capture());
		assertThat(ttl.getAllValues())
				.as("快照/待决/归档/序号/事件日志五类键都被整额续期（卡的后台数据不先于会话过期）")
				.containsOnly(Duration.ofHours(24));
		verify(this.redis).expire(this.store.runKey(runId), Duration.ofHours(24));
		verify(this.redis).expire(this.store.pendingKey(runId), Duration.ofHours(24));
		verify(this.redis).expire(this.store.eventsKey(runId), Duration.ofHours(24));
		verify(this.redis).expire(this.store.seqKey(runId), Duration.ofHours(24));
		// B23L1 批①：事件日志 Stream 与其余卡的后台数据同寿（挂起期无 append，靠心跳续期）
		verify(this.redis).expire(this.store.xeventsKey(runId), Duration.ofHours(24));
	}

	// ───────────────────────── 辅助 ─────────────────────────

	private RunSnapshot seedSnapshot(String runId, long createdAtMs) {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(runId);
		snapshot.setSessionId("s-" + runId);
		snapshot.setStatus(RunSnapshot.RUNNING);
		snapshot.setCreatedAtMs(createdAtMs);
		try {
			this.strings.put(this.store.runKey(runId), this.mapper.writeValueAsString(snapshot));
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
		return snapshot;
	}

	private Duration capturedTtl(String key) {
		ArgumentCaptor<Duration> captor = ArgumentCaptor.forClass(Duration.class);
		verify(this.valueOps).set(org.mockito.ArgumentMatchers.eq(key), anyString(), captor.capture());
		return captor.getValue();
	}
}
