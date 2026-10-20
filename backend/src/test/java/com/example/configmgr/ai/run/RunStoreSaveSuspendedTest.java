package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code RunStore#saveSuspended} 的时间戳口径（F5 收口 NIT）：只给真正挂起的 {@code PENDING}
 * 条目打 {@code requestedAtMs}；预检失败等<b>已结算</b>条目不得被覆写，否则会出现
 * {@code resolvedAtMs < requestedAtMs} 的时间倒挂。
 */
class RunStoreSaveSuspendedTest {

	private static final String RUN_ID = "r-1";

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);

	private final ObjectMapper mapper = new ObjectMapper();

	private final RunStore store = new RunStore(this.redis, this.mapper, new AiProperties());

	@Test
	void settledEntriesKeepTheirRequestedAtMs() throws Exception {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(RUN_ID);
		snapshot.setSessionId("s-1");
		snapshot.setStatus(RunSnapshot.RUNNING);
		snapshot.setCreatedAtMs(1L);
		when(this.redis.opsForValue().get("ai:run:" + RUN_ID)).thenReturn(this.mapper.writeValueAsString(snapshot));

		PendingToolCall settled = pending("c-settled", PendingToolCall.PRECHECK_FAILED);
		settled.setRequestedAtMs(100L);
		settled.setResolvedAtMs(200L);
		PendingToolCall waiting = pending("c-waiting", PendingToolCall.PENDING);

		this.store.saveSuspended(RUN_ID, null, List.of(settled, waiting), List.of(), mock(MessageJsonCodec.class));

		assertThat(settled.getRequestedAtMs()).as("已结算条目的请求时刻不得被覆写").isEqualTo(100L);
		assertThat(settled.getResolvedAtMs()).isEqualTo(200L);
		assertThat(waiting.getRequestedAtMs()).isPositive();
		verify(this.redis.opsForHash(), times(1)).put(eq("ai:pending:" + RUN_ID), eq("c-waiting"), anyString());
		verify(this.redis.opsForHash(), never()).put(eq("ai:pending:" + RUN_ID), eq("c-settled"), any());
	}

	private static PendingToolCall pending(String toolCallId, String status) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName("start_import");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		pending.setArguments("{\"taskId\":7}");
		pending.setStatus(status);
		return pending;
	}

}
