package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.form.GenerativeFormRules;
import com.example.configmgr.ai.form.TaskTypeForm;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>首轮类型表单的回灌链</b>：用户在类型卡上选完之后，续跑必须把"选择"当作工具结果交棒模型
 * （模型据此直接 {@code create_task}，进确认卡），而不是再问一次。
 *
 * <h2>为什么必须单独测</h2>
 * 这张卡是<b>首轮</b>挂起的：挂起时模型一次都还没跑过（没有 assistant 正文、没有工具轮）。
 * 续跑侧要从"in-flight + 快照"重建出合法的 {@code assistant(tool_calls)} → {@code tool} 序列
 * 再交棒 —— 这条恢复路径与"模型自己发起的表单"同一条，但现场更空（只有 SYSTEM + USER）。
 * 若重建不出来，症状是续跑报错或把同一张卡再挂一次（用户看到"选了还是问"）。
 *
 * <p><b>证红</b>：续跑侧去重失效（同一张表被再挂）⇒ 本用例红（模型一次都不会被交棒）；
 * 回灌值不进历史 ⇒ 第 2 条红。
 */
class TaskTypeFormResumeTest {

	private static final String RUN_ID = "run-type-resume";

	private static final String SESSION_ID = "s-type-resume";

	private static final String CALL_TYPE_FORM = TaskTypeForm.toolCallId(RUN_ID);

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

	private ResumeService service;

	@BeforeEach
	void setUp() {
		this.properties.getPark().setEnabled(true);
		when(this.toolCallingManager.resolvePending(eq(RUN_ID), any(Prompt.class), any(PendingToolCall.class)))
			.thenAnswer(invocation -> invocation.<PendingToolCall>getArgument(2).getResultText());
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any())).thenAnswer(invocation -> (AutoCloseable) () -> {
		});
		when(this.chatService.systemPromptText(any())).thenReturn(SYSTEM_TEXT);
		when(this.chatService.workspaceStateText(any())).thenReturn("## 当前工作区状态\n<workspace_state>\n"
				+ "页面：任务中心\n</workspace_state>\n");
		when(this.chatService.buildResumedPrompt(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any()))
			.thenAnswer(invocation -> new Prompt(invocation.<List<Message>>getArgument(3),
					ToolCallingChatOptions.builder().build()));
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(this.executor).execute(any(Runnable.class));

		this.service = new ResumeService(this.store, this.registry, this.codec, this.chatService,
				this.toolCallingManager, this.executor, this.redis, this.properties, this.cancellations, this.limiter);
	}

	@Test
	void submittingTheChosenTypeHandsOffToTheModelInTheSameTurnWithoutAskingAgain() {
		seedFirstTurnParkedWith("{\"type\":\"IMPORT\"}");

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(result.body()).as("回灌后是正常续跑，不是又一次挂起").doesNotContainKey("reason");
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any(),
				eq(this.emitter));

		// 选择作为工具结果进历史：模型据此直接 create_task(type=IMPORT)，进既有确认卡
		List<Message> handed = historyOfDriveResumed();
		Message last = handed.get(handed.size() - 1);
		assertThat(last).isInstanceOf(ToolResponseMessage.class);
		List<ToolResponseMessage.ToolResponse> responses = ((ToolResponseMessage) last).getResponses();
		assertThat(responses).hasSize(1);
		assertThat(responses.get(0).id()).isEqualTo(CALL_TYPE_FORM);
		assertThat(responses.get(0).name()).isEqualTo(GenerativeFormRules.TOOL_NAME);
		assertThat(responses.get(0).responseData()).contains("IMPORT");
		// 首轮挂起时模型还没跑过：重建出来的 assistant(tool_calls) 必须与这条 tool 结果配对
		assertThat(handed.get(handed.size() - 2).getMessageType().name()).isEqualTo("ASSISTANT");

		// 不得再问第二张类型卡
		assertThat(this.store.pendingsLenient(RUN_ID)).extracting(PendingToolCall::getToolCallId)
			.containsExactly(CALL_TYPE_FORM);
		assertThat(this.store.pending(RUN_ID, CALL_TYPE_FORM).getStatus())
			.isEqualTo(PendingToolCall.FRONTEND_RESULT);
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.RUNNING);
		assertThat(snapshot.getInFlightToolCalls()).as("交棒前清空 in-flight").isEmpty();
	}

	// ── 桩与辅助 ────────────────────────────────────────────────────────────

	private static final String SYSTEM_TEXT = "PERSONA 行\n\n## 当前工作区状态\n<workspace_state>\n"
			+ "页面：任务中心\n</workspace_state>\n";

	/** 首轮挂起现场：只有 SYSTEM + USER，助手轮由 in-flight 重建（正文为空）。 */
	private void seedFirstTurnParkedWith(String resultText) {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setContext(AiContext.of("tasks", null, null, null, null).toMap());
		snapshot.setMessageJson(this.codec.serializeAll(
				List.of(new SystemMessage(SYSTEM_TEXT), new UserMessage("帮我创建一个任务"))));
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL_TYPE_FORM);
		pending.setName(GenerativeFormRules.TOOL_NAME);
		pending.setType("function");
		pending.setArguments("{\"form\":{\"scenario\":\"CLARIFY\",\"purpose\":\"TASK_TYPE\","
				+ "\"fields\":[{\"key\":\"type\",\"label\":\"任务类型\",\"type\":\"enum\",\"required\":true,"
				+ "\"options\":[{\"value\":\"EXPORT\",\"label\":\"导出\"},{\"value\":\"IMPORT\",\"label\":\"导入\"}]}]}}");
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setStatus(PendingToolCall.FRONTEND_RESULT);
		pending.setOutcome(ToolOutcome.OK.wire());
		pending.setResultText(resultText);
		pending.setReq(Boolean.TRUE);
		snapshot.setInFlightToolCalls(new ArrayList<>(List.of(pending)));
		this.store.save(snapshot);
		this.store.putPending(RUN_ID, pending);
	}

	@SuppressWarnings("unchecked")
	private List<Message> historyOfDriveResumed() {
		ArgumentCaptor<List<Message>> history = ArgumentCaptor.forClass(List.class);
		verify(this.chatService, org.mockito.Mockito.atLeastOnce()).driveResumed(eq(RUN_ID), eq(SESSION_ID),
				any(AiContext.class), history.capture(), eq(this.emitter));
		return history.getValue();
	}

}
