package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>C1 多卡 park 之后的重建执行</b>：一批两张卡都在 park 前公布、都被批准之后，
 * 续跑必须<b>每张卡各执行一次</b>（不重、不漏、不再卡死），且
 * {@code PENDING_UNRESOLVED} 判据仍然生效（另一张卡没决定时不驱动 —— 不能靠删判据绕过死锁）。
 */
class MultiCardParkResumeTest {

	private static final String RUN_ID = "run-multi-card";

	private static final String SESSION_ID = "s-multi-card";

	private static final String CALL_1 = "call-1";

	private static final String CALL_2 = "call-2";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final ResilientChatService chatService = mock(ResilientChatService.class);

	private final SpToolCallingManager toolCallingManager = mock(SpToolCallingManager.class);

	private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final AiProperties properties = new AiProperties();

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

	private final ResumeService service = new ResumeService(this.store, this.registry, this.codec, this.chatService,
			this.toolCallingManager, this.executor, this.redis, this.properties, this.cancellations, this.limiter);

	@Test
	void bothApprovedCardsExecuteExactlyOnceEach() {
		seedSuspendedRun(PendingToolCall.APPROVED, PendingToolCall.APPROVED);
		when(this.chatService.buildResumedPrompt(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any()))
			.thenReturn(new Prompt(List.of()));
		when(this.toolCallingManager.resolvePending(eq(RUN_ID), any(Prompt.class), any(PendingToolCall.class)))
			.thenReturn("已执行");
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(this.executor).execute(any(Runnable.class));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(result.body()).doesNotContainEntry("reason", "PENDING_UNRESOLVED");
		// 每张卡各一次（既不重复执行，也不漏掉任何一张）
		ArgumentCaptor<PendingToolCall> resolved = ArgumentCaptor.forClass(PendingToolCall.class);
		verify(this.toolCallingManager, times(2)).resolvePending(eq(RUN_ID), any(Prompt.class), resolved.capture());
		assertThat(resolved.getAllValues().stream().map(PendingToolCall::getToolCallId))
			.containsExactlyInAnyOrder(CALL_1, CALL_2);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Message>> history = ArgumentCaptor.forClass(List.class);
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), history.capture(),
				eq(this.emitter));
		ToolResponseMessage toolMessage = toolMessage(history.getValue());
		assertThat(toolMessage.getResponses()).as("assistant(tool_calls) 的每个 id 都有配对的 role:tool")
			.hasSize(2);
		assertThat(toolMessage.getResponses().stream().map(ToolResponseMessage.ToolResponse::id))
			.containsExactlyInAnyOrder(CALL_1, CALL_2);
	}

	/** 不能为了修多卡死锁而删掉 {@code PENDING_UNRESOLVED}：另一张卡没决定时仍不驱动。 */
	@Test
	void oneStillPendingCardKeepsTheRunSuspended() {
		seedSuspendedRun(PendingToolCall.APPROVED, PendingToolCall.PENDING);
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(this.executor).execute(any(Runnable.class));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.PENDING_UNRESOLVED);
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
		verify(this.chatService, org.mockito.Mockito.never()).driveResumed(any(), any(), any(), any(), any(), any(),
				any());
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private void seedSuspendedRun(String firstStatus, String secondStatus) {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setContext(AiContext.empty().toMap());
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("启动两个导入"))));
		List<PendingToolCall> inFlight = new ArrayList<>();
		inFlight.add(entry(CALL_1, "{\"taskId\":7}", firstStatus));
		inFlight.add(entry(CALL_2, "{\"taskId\":9}", secondStatus));
		snapshot.setInFlightToolCalls(inFlight);
		this.store.save(snapshot);
		for (PendingToolCall pending : inFlight) {
			this.store.putPending(RUN_ID, pending);
		}
	}

	private static PendingToolCall entry(String callId, String arguments, String status) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(callId);
		pending.setName("start_import");
		pending.setType("function");
		pending.setArguments(arguments);
		// 多卡 park 的现实形态：一批确认卡（人工决策）。BACKEND 的 PENDING 是"待执行"，
		// 按设计不参与 PENDING_UNRESOLVED 判据（见 ResumeSkipCascadeTest）。
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		pending.setStatus(status);
		if (PendingToolCall.APPROVED.equals(status)) {
			pending.setOutcome(ToolOutcome.OK.wire());
			pending.setExecuted(true);
			pending.setExecutedBy("instance-old");
			pending.setResultText("已执行");
		}
		pending.setReq(Boolean.TRUE);
		return pending;
	}

	private static ToolResponseMessage toolMessage(List<Message> history) {
		return history.stream()
			.filter(ToolResponseMessage.class::isInstance)
			.map(ToolResponseMessage.class::cast)
			.findFirst()
			.orElseThrow(() -> new AssertionError("续跑历史里没有 role:tool：" + history));
	}

}
