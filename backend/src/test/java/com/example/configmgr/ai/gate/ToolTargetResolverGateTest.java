package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.FrameContract;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.repo.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F2 的<b>门级</b>用例（B23L1 批③b）：目标资源不成立 ⇒ {@code blocked/TARGET_INVALID}，
 * 且判据落在帧流 / 台账 / 认领 / 待决条目上（不断言"方法被调用过"）。
 *
 * <h2>实例面维度（M8 / DC-11 口径）</h2>
 * 单实例的<b>门内接线</b>面：校验发生在 {@code executeOnce} 的最前面（先于资源锁与认领），
 * 因此结论与实例数无关；跨实例那一面只影响"谁先拿到资源锁/认领"，不影响本类判据。
 *
 * <h2>覆盖</h2>
 * F1 目标不成立 → 阻断且不占认领、不记台账、批次照常；F3 与上下文不一致 → 阻断；
 * F7 重放优先：台账命中时即使目标已失效，也回原结果（不再校验、不再执行）；
 * 另加"非作业发起工具（无 taskId）不校验"的对照（不用 create_task：AR-03 起它是 DANGER 确认门工具）。
 */
class ToolTargetResolverGateTest {

	private static final String RUN_ID = "run-target";

	private static final String SESSION_ID = "s-target";

	private static final String CALL_ID = "call-target-1";

	private static final long TASK_ID = 7L;

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final RunRegistry registry = new RunRegistry(this.store, new ObjectMapper(), null);

	private final AiProperties properties = new AiProperties();

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final MessageJsonCodec codec = mock(MessageJsonCodec.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations,
			this.beacon, List.of());

	private final TaskRepository tasks = mock(TaskRepository.class);

	private final ToolTargetResolver resolver = new ToolTargetResolver(this.tasks);

	private final SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry, this.gate,
			this.toolRegistry, this.codec, this.beacon, this.cancellations, new ToolResultLimiter(this.properties),
			List.of(), null, null, this.resolver, null);

	private final FakeImportTool importTool = new FakeImportTool();

	private final FakeStatusTool statusTool = new FakeStatusTool();

	@BeforeEach
	void setUp() {
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.channelOf(anyString())).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.toolRegistry.riskOf(anyString())).thenReturn(ToolMeta.RiskLevel.READ);
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.of("import", "IMPORT", "IMPORT", TASK_ID, null).toMap());
		this.store.save(snapshot);
	}

	// ── F1：目标不存在 ⇒ 阻断，不占认领、不记台账、不停批 ───────────────────

	@Test
	void invalidTargetIsBlockedWithoutClaimOrLedgerAndBatchContinues() {
		FrameWire wire = attach();
		when(this.tasks.findById(TASK_ID)).thenReturn(Optional.empty());

		ToolExecutionResult result = this.manager.executeToolCalls(promptWithBothTools(RUN_ID),
				response(new AssistantMessage.ToolCall("call-target-1", "function", "start_import",
						"{\"taskId\":7}"),
						new AssistantMessage.ToolCall("call-target-2", "function", "check_job_status",
								"{\"jobId\":1}")));
		finish();

		List<Map<String, Object>> frames = wire.frames();
		assertThat(FrameContract.types(frames)).containsExactly("start", "suspended", "tool_start", "tool_result",
				"tool_start", "tool_result", "done");
		assertThat(FrameContract.types(frames)).doesNotContain("error");
		FrameContract.assertConformant(frames);

		Map<String, Object> blocked = FrameContract.ofType(frames, "tool_result").get(0);
		assertThat(blocked).containsEntry("ok", false)
			.containsEntry("outcome", ToolOutcome.BLOCKED.wire())
			.containsEntry("code", ToolOutcome.CODE_TARGET_INVALID)
			.containsEntry("retryable", false)
			.containsEntry("status", PendingToolCall.BLOCKED)
			.containsEntry("executed", false);
		assertThat(String.valueOf(blocked.get("result"))).contains("目标资源不存在或不在当前任务内，本次未执行")
			.contains("任务 #7 不存在");

		// 副作用为零：没有执行、没有认领、没有台账
		assertThat(this.importTool.invocations.get()).isZero();
		assertThat(this.store.claimedBy(RUN_ID, "call-target-1")).isNull();
		assertThat(this.store.ledger(RUN_ID)).hasSize(1);
		assertThat(this.store.ledgerFor(RUN_ID, "call-target-1")).as("被阻断的那次不记台账").isNull();

		// 同批第二个调用照常（blocked 不触发首错即停）
		assertThat(FrameContract.ofType(frames, "tool_result").get(1)).containsEntry("outcome",
				ToolOutcome.OK.wire());
		assertThat(this.statusTool.invocations.get()).isEqualTo(1);

		// 模型侧信封：blocked / TARGET_INVALID / 不可重试
		ToolResultEnvelope envelope = envelopeOf(result, 0);
		assertThat(envelope.outcome()).isEqualTo(ToolOutcome.BLOCKED.wire());
		assertThat(envelope.code()).isEqualTo(ToolOutcome.CODE_TARGET_INVALID);
		assertThat(envelope.retryable()).isFalse();

		PendingToolCall stored = this.store.pending(RUN_ID, "call-target-1");
		assertThat(stored.getStatus()).isEqualTo(PendingToolCall.BLOCKED);
		assertThat(stored.getCode()).isEqualTo(ToolOutcome.CODE_TARGET_INVALID);
		assertThat(stored.isExecuted()).isFalse();
	}

	// ── F3：与上下文任务不一致 ⇒ 阻断 ──────────────────────────────────────

	@Test
	void taskIdOutsideContextIsBlocked() {
		FrameWire wire = attach();
		when(this.tasks.findById(9L)).thenReturn(Optional.of(task(9L, Task.TaskType.IMPORT)));

		this.manager.executeToolCalls(prompt(RUN_ID), response("start_import", "{\"taskId\":9}"));
		finish();

		Map<String, Object> frame = FrameContract.oneOfType(wire.frames(), "tool_result");
		assertThat(frame).containsEntry("code", ToolOutcome.CODE_TARGET_INVALID)
			.containsEntry("executed", false);
		assertThat(String.valueOf(frame.get("result"))).contains("与当前工作区任务 #7 不一致");
		assertThat(this.importTool.invocations.get()).isZero();
	}

	// ── F7：重放优先（台账命中时目标失效也回原结果） ────────────────────────

	@Test
	void ledgerReplayWinsOverTargetValidation() {
		seedLedger("call-target-1", "原结果：导入作业已启动（作业 #4221）");
		FrameWire wire = attach();
		// 目标此处已失效（任务被删），但台账命中 ⇒ 不得再校验、不得再执行
		when(this.tasks.findById(TASK_ID)).thenReturn(Optional.empty());

		this.manager.executeToolCalls(prompt(RUN_ID), response("start_import", "{\"taskId\":7}"));
		finish();

		Map<String, Object> frame = FrameContract.oneOfType(wire.frames(), "tool_result");
		assertThat(frame).containsEntry("outcome", ToolOutcome.OK.wire())
			.containsEntry("reused", true)
			.containsEntry("executed", true);
		assertThat(String.valueOf(frame.get("result"))).isEqualTo("原结果：导入作业已启动（作业 #4221）");
		assertThat(this.importTool.invocations.get()).isZero();
		assertThat(this.store.ledger(RUN_ID)).hasSize(1);
	}

	// ── F2-b：确认卡**批准后**的执行前复核（approve → TARGET_INVALID 仍存在） ──────

	/**
	 * 批准不是执行授权：卡是"目标还在时"挂起的，批准前目标被删（E2E 里就是用例自己删 fixture），
	 * 续跑结算（{@link SpToolCallingManager#resolvePending}，ResumeService 的唯一入口）必须再过一次
	 * F2 ⇒ {@code blocked/TARGET_INVALID}，且不认领、不记台账、不执行。
	 *
	 * <p>与 F1 的分工：F1 走的是**开卡前**的批次路径（预检 TARGET_CONSISTENT 先拦）；本条走的是
	 * "卡已批准、执行前"的那一段 —— 预检在挂起时是过的，只有 F2 能挡住目标消失。两条路径都必须在册，
	 * 否则"批准后目标没了"会变成一次静默执行。
	 */
	@Test
	void approvedCardWithVanishedTargetIsBlockedWithoutClaimOrLedger() {
		FrameWire wire = attach();
		when(this.tasks.findById(TASK_ID)).thenReturn(Optional.empty());
		PendingToolCall approved = approvedPending("start_import", "{\"taskId\":7}");

		String text = this.manager.resolvePending(RUN_ID, prompt(RUN_ID), approved);

		Map<String, Object> frame = FrameContract.oneOfType(wire.frames(), "tool_result");
		assertThat(frame).containsEntry("outcome", ToolOutcome.BLOCKED.wire())
			.containsEntry("code", ToolOutcome.CODE_TARGET_INVALID)
			.containsEntry("retryable", false)
			.containsEntry("status", PendingToolCall.BLOCKED)
			.containsEntry("executed", false);
		assertThat(text).contains("目标资源不存在或不在当前任务内，本次未执行").contains("任务 #7 不存在");

		// 安全判据：不执行、不占认领、不记台账
		assertThat(this.importTool.invocations.get()).isZero();
		assertThat(this.store.claimedBy(RUN_ID, CALL_ID)).isNull();
		assertThat(this.store.ledger(RUN_ID)).isEmpty();
		assertThat(this.store.ledgerFor(RUN_ID, CALL_ID)).isNull();

		// 条目落成 blocked + 码（不是 failed/unknown：门根本没开）
		PendingToolCall stored = this.store.pending(RUN_ID, CALL_ID);
		assertThat(stored.getStatus()).isEqualTo(PendingToolCall.BLOCKED);
		assertThat(stored.getCode()).isEqualTo(ToolOutcome.CODE_TARGET_INVALID);
		assertThat(stored.isExecuted()).isFalse();
	}

	// ── 对照：非作业发起工具没有 taskId ⇒ 不校验、照常执行 ────────────────────

	@Test
	void nonJobToolWithoutTaskIdIsNotValidated() {
		FrameWire wire = attach();
		this.manager.executeToolCalls(prompt(RUN_ID), response("fake_read_tool", "{}"));
		finish();

		Map<String, Object> frame = FrameContract.oneOfType(wire.frames(), "tool_result");
		assertThat(frame).containsEntry("outcome", ToolOutcome.OK.wire()).containsEntry("executed", true);
		assertThat(this.importTool.invocations.get()).isZero();
	}

	// ── 桩与辅助 ────────────────────────────────────────────────────────────

	private void seedLedger(String toolCallId, String resultText) {
		PendingToolCall seeded = new PendingToolCall();
		seeded.setToolCallId(toolCallId);
		seeded.setName("start_import");
		seeded.setKind(PendingToolCall.KIND_BACKEND);
		seeded.setStatus(PendingToolCall.EXECUTED);
		seeded.setOutcome(ToolOutcome.OK.wire());
		seeded.setExecuted(true);
		seeded.setExecutedBy("instance-prior");
		this.store.appendLedger(RUN_ID, seeded, resultText);
	}

	private FrameWire attach() {
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		out.start(SESSION_ID);
		return wire;
	}

	/**
	 * 造一张"已批准、尚未执行"的确认门条目：正是 ResumeService 在用户点「批准」之后读到的那份状态
	 * （{@code APPROVED} + {@code KIND_CONFIRM}）。写入待决表是必须的 —— 结算会读回同一条目。
	 */
	private PendingToolCall approvedPending(String toolName, String argumentsJson) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL_ID);
		pending.setName(toolName);
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		pending.setStatus(PendingToolCall.APPROVED);
		pending.setArguments(argumentsJson);
		this.store.putPending(RUN_ID, pending);
		return pending;
	}

	private void finish() {
		this.registry.of(RUN_ID).done(Map.of(), "deepseek-flash", Map.of("cancelled", false));
	}

	private Prompt prompt(String runId) {
		List<ToolCallback> callbacks = List.of(MethodToolCallbackProvider.builder()
			.toolObjects(this.importTool, this.statusTool)
			.build()
			.getToolCallbacks());
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(callbacks)
			.toolContext(Map.of("runId", runId, "sessionId", SESSION_ID))
			.build();
		return new Prompt(List.of(new UserMessage("请发起导入")), options);
	}

	private Prompt promptWithBothTools(String runId) {
		return prompt(runId);
	}

	private static ChatResponse response(String tool, String args) {
		return response(new AssistantMessage.ToolCall(CALL_ID, "function", tool, args));
	}

	private static ChatResponse response(AssistantMessage.ToolCall... calls) {
		AssistantMessage assistant = AssistantMessage.builder().toolCalls(List.of(calls)).build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

	private static ToolResultEnvelope envelopeOf(ToolExecutionResult result, int index) {
		List<ToolResponseMessage.ToolResponse> responses = result.conversationHistory()
			.stream()
			.filter(ToolResponseMessage.class::isInstance)
			.map(ToolResponseMessage.class::cast)
			.flatMap(message -> message.getResponses().stream())
			.toList();
		return ToolResultEnvelope.fromJson(responses.get(index).responseData());
	}

	private static Task task(long id, Task.TaskType type) {
		Task task = new Task();
		task.setId(id);
		task.setType(type);
		task.setTitle("受控任务");
		task.setCurrentStep("IMPORT");
		task.setStatus(Task.TaskStatus.ACTIVE);
		return task;
	}

	/** 受控替身：真正的 {@code start_import}（只被 {@link #importTool} 计数，不读写库）。 */
	static class FakeImportTool {

		final AtomicInteger invocations = new AtomicInteger();

		@Tool(name = "start_import", description = "受控替身：启动导入作业")
		public String startImport(@ToolParam(description = "任务ID") Long taskId) {
			this.invocations.incrementAndGet();
			return "导入作业已启动（作业 #4221）";
		}

		@Tool(name = "fake_read_tool", description = "受控替身：非作业发起工具（无 taskId）")
		public String fakeRead(@ToolParam(description = "占位参数") String value) {
			return "非作业工具已执行";
		}

	}

	static class FakeStatusTool {

		final AtomicInteger invocations = new AtomicInteger();

		@Tool(name = "check_job_status", description = "受控替身：查作业状态")
		public String checkJobStatus(@ToolParam(description = "作业ID") Long jobId) {
			this.invocations.incrementAndGet();
			return "作业 #" + jobId + " 状态:RUNNING";
		}

	}

}
