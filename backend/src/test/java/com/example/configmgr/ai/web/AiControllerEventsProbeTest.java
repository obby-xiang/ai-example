package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.conformance.FrameContract;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.FrameStore;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.common.AiStateUnavailableException;
import com.example.configmgr.common.ErrorBodyFactory;
import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.common.GlobalExceptionHandler;
import com.example.configmgr.job.service.JobWatchdog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>M08：重挂探测走同路径轻量 JSON（{@code GET /api/ai/events/{runId}?probe=1}）</b>。
 *
 * <h2>被钉住的缺陷（真机 trace）</h2>
 * 前端探测原来打的是<b>正式 SSE 端点</b>并声明 {@code Accept: text/event-stream}：
 * 服务端因此照常建 {@link SseChatEmitter} 订阅、回放整段归档、{@code touchActiveRun} 续期 ——
 * 一次"只想看看状态"的探测产生了一次<b>真实订阅</b>（trace 里 10 个 HTTP = 5 条 EventSource
 * + 5 条 probe fetch，不是双 SSE；探测自身还抢占了退避时间线）。
 *
 * <h2>本组口径</h2>
 * <ul>
 * <li>探测只读：<b>零 SseEmitter / 零回放（{@code replayAndAttach}）/ 零订阅（{@code registry.of}）
 *     / 零续期（{@code touchActiveRun}）</b>；</li>
 * <li>状态语义与正式通道一致：202（AR-06 准备中）/ 404（真不存在）/ 503（归档末号不可读、快照读失败）；</li>
 * <li>成功路径（快照已建）⇒ 200 JSON {@code ready=true}，前端据此走正式 EventSource 重挂。</li>
 * </ul>
 *
 * <p>证红方式：让探测请求恢复到"只声明 {@code text/event-stream}"的正式端点 ⇒ 客户端声明
 * {@code Accept: application/json} 时协商不到（406），或在快照已建时拿到 {@link SseChatEmitter}
 * 且 {@code touchActiveRun} 被调用一次。
 */
class AiControllerEventsProbeTest {

	private static final String RUN_ID = "run-probe";

	private static final String SESSION_ID = "s-probe";

	private final ObjectMapper mapper = new ObjectMapper();

	private final RunStore store = mock(RunStore.class);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final FrameStore frameStore = mock(FrameStore.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		AiController controller = new AiController(mock(AiAvailability.class), provider(), provider(), provider(),
				mock(ChatMemory.class), mock(ChatMemoryRepository.class), mock(SessionGate.class),
				mock(ConfirmGate.class), this.store, this.registry, this.cancellations,
				mock(RedisAvailability.class), mock(ThreadPoolTaskExecutor.class), this.mapper, new AiProperties(),
				mock(Environment.class), mock(JobWatchdog.class));
		ReflectionTestUtils.setField(controller, "frameStore", this.frameStore);
		when(this.registry.of(anyString())).thenReturn(this.emitter);
		when(this.cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
		this.mvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler(new ErrorBodyFactory(provider())))
			.build();
	}

	@Test
	void probeOnLiveRunReturnsLightweightJsonWithoutSubscribingReplayingOrRenewing() throws Exception {
		when(this.store.get(RUN_ID)).thenReturn(snapshot(RunSnapshot.RUNNING));
		when(this.frameStore.lastSeqIfKnown(RUN_ID)).thenReturn(OptionalLong.of(7L));

		this.mvc.perform(get("/api/ai/events/{runId}", RUN_ID).param("probe", "1")
			.accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.ready").value(true))
			.andExpect(jsonPath("$.preparing").value(false))
			.andExpect(jsonPath("$.runId").value(RUN_ID))
			.andExpect(jsonPath("$.status").value(RunSnapshot.RUNNING))
			.andExpect(jsonPath("$.degraded").value(false));

		assertNoSubscriptionSideEffects();
	}

	@Test
	void probeDuringPreparationKeepsAr06PreparingSemanticsWithoutSideEffects() throws Exception {
		long startedAtMs = System.currentTimeMillis() - 9_000L;
		when(this.store.get(RUN_ID)).thenReturn(null);
		when(this.frameStore.lastSeqIfKnown(RUN_ID)).thenReturn(OptionalLong.of(1L));
		when(this.frameStore.readFrom(eq(RUN_ID), anyLong(), anyInt()))
			.thenReturn(List.of(startFrame(startedAtMs)));

		this.mvc.perform(get("/api/ai/events/{runId}", RUN_ID).param("probe", "1")
			.accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isAccepted())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.preparing").value(true))
			.andExpect(jsonPath("$.runId").value(RUN_ID))
			.andExpect(jsonPath("$.retryAfterMs").value(1000))
			.andExpect(jsonPath("$.reattach").value("/api/ai/events/" + RUN_ID))
			.andExpect(jsonPath("$.degraded").value(false))
			.andExpect(jsonPath("$.code").doesNotExist());

		assertNoSubscriptionSideEffects();
	}

	@Test
	void probeForAnUnknownRunIsStill404() throws Exception {
		when(this.store.get(RUN_ID)).thenReturn(null);
		when(this.frameStore.lastSeqIfKnown(RUN_ID)).thenReturn(OptionalLong.of(0L));

		this.mvc.perform(get("/api/ai/events/{runId}", RUN_ID).param("probe", "1")
			.accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value(ErrorCodes.UNKNOWN_RUN));

		assertNoSubscriptionSideEffects();
	}

	@Test
	void probeWithUnreadableArchiveTailIs503Not404() throws Exception {
		when(this.store.get(RUN_ID)).thenReturn(null);
		when(this.frameStore.lastSeqIfKnown(RUN_ID)).thenReturn(OptionalLong.empty());

		this.mvc.perform(get("/api/ai/events/{runId}", RUN_ID).param("probe", "1")
			.accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value(ErrorCodes.AI_REDIS_UNAVAILABLE))
			.andExpect(jsonPath("$.degraded").value(true));

		assertNoSubscriptionSideEffects();
	}

	@Test
	void probeWithASnapshotButUnreadableArchiveTailIs503WithoutRenewing() throws Exception {
		// 与正式通道同口径：末号读不出来 ⇒ 503（探测不得为了"看起来可用"而续期或建订阅）
		when(this.store.get(RUN_ID)).thenReturn(snapshot(RunSnapshot.RUNNING));
		when(this.frameStore.lastSeqIfKnown(RUN_ID)).thenReturn(OptionalLong.empty());

		this.mvc.perform(get("/api/ai/events/{runId}", RUN_ID).param("probe", "1")
			.accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value(ErrorCodes.AI_REDIS_UNAVAILABLE))
			.andExpect(jsonPath("$.degraded").value(true));

		assertNoSubscriptionSideEffects();
	}

	@Test
	void probeWhenSnapshotReadFailsIs503NotAStream() throws Exception {
		when(this.store.get(RUN_ID)).thenThrow(new AiStateUnavailableException("redis down"));

		this.mvc.perform(get("/api/ai/events/{runId}", RUN_ID).param("probe", "1")
			.accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value(ErrorCodes.AI_REDIS_UNAVAILABLE));

		assertNoSubscriptionSideEffects();
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	/** 零副作用：不订阅、不回放、不续期（探测只读状态）。 */
	private void assertNoSubscriptionSideEffects() {
		verify(this.registry, never()).of(anyString());
		verify(this.emitter, never()).replayAndAttach(any(), any(), anyBoolean());
		verify(this.emitter, never()).completeSubscriptions();
		verify(this.store, never()).touchActiveRun(any(RunSnapshot.class));
		verify(this.store, never()).touchActiveRun(anyString());
	}

	private static RunSnapshot snapshot(String status) {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(RUN_ID);
		snapshot.setSessionId(SESSION_ID);
		snapshot.setStatus(status);
		return snapshot;
	}

	private static Map<String, Object> startFrame(long atMs) {
		return FrameContract.fixture("start", 1L, "runId", RUN_ID, "sessionId", SESSION_ID, "atMs", atMs);
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}

}
