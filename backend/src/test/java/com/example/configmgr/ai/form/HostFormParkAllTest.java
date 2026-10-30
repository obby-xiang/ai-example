package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.AiToolNames;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link HostForm#parkAll} 的<b>多条目挂起</b>单点：同一批条件草案（多个 {@code set_condition}）必须
 * 外置成一张完整的挂起面，续跑只有在<b>全部</b>回灌后才驱动一次模型；挂起写失败必须把条目收敛成
 * 终态，绝不能留下"看不见的 PENDING"挡住后续续跑。
 */
class HostFormParkAllTest {

	private static final String RUN_ID = "run-host-park-all";

	private static final String SESSION_ID = "s-host-park-all";

	private static final String CALL_1 = "call-draft-1";

	private static final String CALL_2 = "call-draft-2";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final ConfirmGate gate = mock(ConfirmGate.class);

	private final AiProperties properties = new AiProperties();

	private final ResilientChatService chatService = mock(ResilientChatService.class);

	private final SpToolCallingManager toolCallingManager = mock(SpToolCallingManager.class);

	private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

	private TestHostForm form;

	@BeforeEach
	void setUp() {
		this.properties.getPark().setEnabled(true);
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		this.form = new TestHostForm(this.store, this.registry, this.gate, this.codec, this.properties);
		seedRun(this.store);
	}

	@Test
	void partialFeedbackKeepsTheModelUndriven() {
		assertThat(this.form.parkBatch(RUN_ID, history(), List.of(
				TestHostForm.call(CALL_1, "CURRENCY"), TestHostForm.call(CALL_2, "RATE")))).isTrue();
		settle(this.store, CALL_1);

		ResumeService.ResumeResult result = resumeService().resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.PENDING_UNRESOLVED);
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
		verify(this.chatService, never()).driveResumed(any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void allFeedbackDrivesTheModelExactlyOnce() {
		assertThat(this.form.parkBatch(RUN_ID, history(), List.of(
				TestHostForm.call(CALL_1, "CURRENCY"), TestHostForm.call(CALL_2, "RATE")))).isTrue();
		settle(this.store, CALL_1);
		settle(this.store, CALL_2);
		ResumeService service = resumeService();

		ResumeService.ResumeResult first = service.resume(RUN_ID);
		assertThat(first.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Message>> history = ArgumentCaptor.forClass(List.class);
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), history.capture(),
				eq(this.emitter));
		assertThat(toolMessage(history.getValue()).getResponses())
			.extracting(ToolResponseMessage.ToolResponse::id)
			.containsExactlyInAnyOrder(CALL_1, CALL_2);

		ResumeService.ResumeResult second = service.resume(RUN_ID);
		assertThat(second.outcome()).isNotEqualTo(ResumeService.Outcome.ACCEPTED);
		verify(this.chatService, times(1)).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any(),
				eq(this.emitter));
	}

	@Test
	void saveSuspendedFailureConvergesEveryDraftToFailedTerminal() {
		FailingSaveStore failing = new FailingSaveStore();
		seedRun(failing);
		TestHostForm failingForm = new TestHostForm(failing, this.registry, this.gate, this.codec, this.properties);

		assertThat(failingForm.parkBatch(RUN_ID, history(), List.of(
				TestHostForm.call(CALL_1, "CURRENCY"), TestHostForm.call(CALL_2, "RATE")))).isFalse();

		List<PendingToolCall> pendings = failing.pendingsLenient(RUN_ID);
		assertThat(pendings).hasSize(2).allSatisfy(pending -> {
			assertThat(pending.getStatus()).isEqualTo(PendingToolCall.BLOCKED);
			assertThat(pending.effectiveOutcome()).isEqualTo(ToolOutcome.FAILED);
			assertThat(pending.isExecuted()).isFalse();
		});
		assertThat(pendings).as("不能留下看不见的 PENDING 挡住续跑")
			.noneMatch(pending -> PendingToolCall.PENDING.equals(pending.getStatus()));
		verify(this.emitter, never()).suspended(any(), anyString(), anyString(), anyString());
		verify(this.gate, never()).publishRequestsForParkedBatch(anyString(), any());
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private ResumeService resumeService() {
		when(this.chatService.buildResumedPrompt(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any()))
			.thenReturn(new Prompt(List.of()));
		when(this.toolCallingManager.resolvePending(eq(RUN_ID), any(Prompt.class), any(PendingToolCall.class)))
			.thenReturn("已执行");
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(this.executor).execute(any(Runnable.class));
		return new ResumeService(this.store, this.registry, this.codec, this.chatService, this.toolCallingManager,
				this.executor, this.redis, this.properties, this.cancellations, this.limiter);
	}

	private void seedRun(InMemoryRunStore target) {
		RunSnapshot snapshot = target.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.empty().toMap());
		snapshot.setMessageJson(this.codec.serializeAll(history()));
		target.save(snapshot);
	}

	private static void settle(InMemoryRunStore target, String toolCallId) {
		PendingToolCall pending = target.pending(RUN_ID, toolCallId);
		pending.setStatus(PendingToolCall.FRONTEND_RESULT);
		pending.setOutcome(ToolOutcome.OK.wire());
		pending.setResultText("已设置（页面草案）");
		pending.setResolvedAtMs(System.currentTimeMillis());
		target.putPending(RUN_ID, pending);
	}

	private static List<Message> history() {
		return List.of(new UserMessage("导出 CNY"));
	}

	private static ToolResponseMessage toolMessage(List<Message> history) {
		return history.stream()
			.filter(ToolResponseMessage.class::isInstance)
			.map(ToolResponseMessage.class::cast)
			.findFirst()
			.orElseThrow(() -> new AssertionError("续跑历史里没有 role:tool：" + history));
	}

	/** 只暴露 parkAll 的具体宿主：生产类不新增公开面。 */
	private static final class TestHostForm extends HostForm {

		private TestHostForm(com.example.configmgr.ai.run.RunStore store, RunRegistry registry, ConfirmGate gate,
				MessageJsonCodec codec, AiProperties properties) {
			super(store, registry, gate, codec, properties);
		}

		private boolean parkBatch(String runId, List<Message> history, List<PendingToolCall> pendings) {
			return parkAll(runId, history, pendings);
		}

		private static PendingToolCall call(String toolCallId, String defCode) {
			String arguments = "{\"defCode\":\"" + defCode + "\",\"conditions\":{\"fields\":["
					+ "{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}}";
			return frontendTool(toolCallId, AiToolNames.SET_CONDITION, arguments);
		}
	}

	/** 复现"写了一条或多条待决条目后快照写失败"的中间窗口。 */
	private static final class FailingSaveStore extends InMemoryRunStore {

		@Override
		public void saveSuspended(String runId, String assistantContent, List<PendingToolCall> items,
				List<Message> history, MessageJsonCodec codec, Map<String, Object> assistantMetadata) {
			for (PendingToolCall item : items) {
				item.setRequestedAtMs(System.currentTimeMillis());
				putPending(runId, item);
			}
			throw new IllegalStateException("redis down");
		}
	}

}
