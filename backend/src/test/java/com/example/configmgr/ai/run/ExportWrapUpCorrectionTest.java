package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 导出收尾的一轮内部纠正（Task 1 第 5 项）：只在"原导出意图已进入 EXPORT、start_export 已披露、
 * 尚未发起、且用户未拒绝/取消/要求只推进"时补一次，要求模型发起确认卡；<b>不得自动执行</b>。
 */
class ExportWrapUpCorrectionTest {

	private static final AiContext EXPORT_STEP = AiContext.of("export", "EXPORT", "EXPORT", 42L, "ACTIVE",
			Map.of());

	@Test
	void exportWrapUpAfterStructuredProgressionNeedsOneCorrection() {
		// 意图只认本 run 的结构化证据（resolved create_task(type=EXPORT)），不看原话措辞。
		boolean due = ResilientChatService.needsExportWrapUpCorrection(EXPORT_STEP, true, List.of(), null, false,
				true);

		assertThat(due).as("EXPORT 步 + start_export 已披露 + 尚未发起 + 已有导出推进 ⇒ 收尾前补一次纠正").isTrue();
	}

	/** 反向：只有"用户说了导出"的原话、没有结构化推进证据 ⇒ 不纠正（不得按关键词注入 start_export）。 */
	@Test
	void freeTextExportIntentAloneDoesNotTriggerCorrection() {
		boolean due = ResilientChatService.needsExportWrapUpCorrection(EXPORT_STEP, true, List.of(),
				"创建并导出这个任务", false, false);

		assertThat(due).as("原话不是结构化事实").isFalse();
	}

	@Test
	void rejectedOrCancelledStartExportSuppressesTheCorrection() {
		PendingToolCall rejected = new PendingToolCall();
		rejected.setName("start_export");
		rejected.setStatus(PendingToolCall.REJECTED);

		assertThat(ResilientChatService.needsExportWrapUpCorrection(EXPORT_STEP, true, List.of(rejected),
				"创建并导出", false, false)).as("用户已拒绝 ⇒ 不纠正").isFalse();
	}

	@Test
	void explicitAdvanceOnlySuppressesTheCorrection() {
		assertThat(ResilientChatService.needsExportWrapUpCorrection(EXPORT_STEP, true, List.of(),
				"先只推进步骤，先不要启动导出", false, false)).as("用户明确只推进 ⇒ 不纠正").isFalse();
	}

	@Test
	void correctionIsAtMostOncePerRun() {
		assertThat(ResilientChatService.needsExportWrapUpCorrection(EXPORT_STEP, true, List.of(), "创建并导出", true, false))
			.as("本轮已纠正过 ⇒ 不再补").isFalse();
	}

	@Test
	void undisclosedOrNotAtExportStepDoesNotCorrect() {
		assertThat(ResilientChatService.needsExportWrapUpCorrection(EXPORT_STEP, false, List.of(), "创建并导出", false, false))
			.as("start_export 未披露 ⇒ 不纠正").isFalse();
		AiContext queryCond = AiContext.of("export", "EXPORT", "QUERY_COND", 42L, "ACTIVE", Map.of());
		assertThat(ResilientChatService.needsExportWrapUpCorrection(queryCond, true, List.of(), "创建并导出", false, false))
			.as("还没到 EXPORT ⇒ 不纠正").isFalse();
	}

	/** 纠正历史必须含本轮全部工具消息（否则纠正请求会重复副作用/丢工具结果）。 */
	@Test
	void correctionHistoryKeepsToolMessagesAndTheFirstAnswer() {
		InMemoryRunStore store = new InMemoryRunStore();
		ObjectMapper mapper = new ObjectMapper();
		MessageJsonCodec codec = new MessageJsonCodec(mapper);
		RunSnapshot snapshot = store.seedRun("run-correction-history", "s-correction-history");
		AssistantMessage toolCall = AssistantMessage.builder().content("").toolCalls(List.of(
				new AssistantMessage.ToolCall("call-1", "function", "set_condition", "{}"))).build();
		ToolResponseMessage toolResult = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "set_condition", "{\"ok\":true}")))
			.build();
		snapshot.setMessageJson(codec.serializeAll(List.of(new SystemMessage("SYS"), new UserMessage("导出"),
				toolCall, toolResult)));
		ResilientChatService service = new ResilientChatService(
				mock(AiProviderRegistry.class),
				mock(ChatMemory.class), codec, store,
				mock(RunRegistry.class), mock(ToolRegistry.class),
				mock(ContextBuilder.class),
				mock(AiSessionService.class), new AiProperties(),
				mock(CancellationRegistry.class),
				mock(ToolActivityBeacon.class), null);

		List<Message> history = service.correctionHistory(snapshot, List.of(), "第一段", "");

		assertThat(history).anyMatch(message -> message instanceof ToolResponseMessage);
		assertThat(history).anyMatch(message -> message instanceof AssistantMessage assistant
				&& "第一段".equals(assistant.getText()));
	}

	/** I-2：EXPORT 页面的状态/条数问答不是导出意图，不得触发收尾纠正。 */
	@Test
	void exportPageQuestionDoesNotTriggerCorrection() {
		assertThat(ResilientChatService.needsExportWrapUpCorrection(EXPORT_STEP, true, List.of(),
				"这个导出任务有多少条数据？", false, false))
			.as("状态/条数问答不得触发 start_export").isFalse();
	}

	/** I-2：用户本轮没提导出，但本 run 已执行过导出推进工具 ⇒ 仍允许纠正。 */
	@Test
	void exportProgressionInThisRunTriggersCorrectionEvenWithoutExportWords() {
		assertThat(ResilientChatService.needsExportWrapUpCorrection(EXPORT_STEP, true, List.of(),
				"继续这个任务", false, true))
			.as("本 run 已有 create_task/select_definitions/confirm_step/set_condition 证据").isTrue();
	}

	/** 本 run 成功的 create_task(type=EXPORT) 即新建导出事实（不读 ai:ledger）。 */
	@Test
	void exportProgressionSeesCreateTaskWithoutLedger() {
		InMemoryRunStore store = new InMemoryRunStore();
		ObjectMapper mapper = new ObjectMapper();
		MessageJsonCodec codec = new MessageJsonCodec(mapper);
		store.seedRun("run-progress-pending", "s-progress-pending");
		PendingToolCall set = new PendingToolCall();
		set.setToolCallId("call-create");
		set.setName("create_task");
		set.setKind(PendingToolCall.KIND_CONFIRM);
		set.setStatus(PendingToolCall.EXECUTED);
		set.setArguments("{\"type\":\"EXPORT\"}");
		set.setOutcome(com.example.configmgr.ai.tool.ToolOutcome.OK.wire());
		set.setResolvedAtMs(System.currentTimeMillis());
		store.putPending("run-progress-pending", set);
		ResilientChatService service = new ResilientChatService(
				mock(AiProviderRegistry.class), mock(ChatMemory.class), codec, store, mock(RunRegistry.class),
				mock(ToolRegistry.class), mock(ContextBuilder.class), mock(AiSessionService.class), new AiProperties(),
				mock(CancellationRegistry.class), mock(ToolActivityBeacon.class), null);

		assertThat(store.ledger("run-progress-pending")).as("没有台账也要识别").isEmpty();
		assertThat(service.exportProgressionObserved("run-progress-pending")).isTrue();
	}

	/** Critical-B：纠正指令必须命中现有 synthetic 判据，不得被当成真实用户原话。 */
	@Test
	void correctionInstructionIsSyntheticAndNeverTreatedAsRealUserText() {
		UserMessage real = new UserMessage("创建并导出");
		UserMessage correction = new UserMessage(ResilientChatService.exportWrapUpCorrection(42L));

		assertThat(com.example.configmgr.ai.history.SyntheticMessages.isSynthetic(correction)).isTrue();
		assertThat(com.example.configmgr.ai.history.SyntheticMessages.isSynthetic(real)).isFalse();
		assertThat(com.example.configmgr.ai.slot.TaskIntent.lastRealUserText(List.of(real, correction)))
			.as("最后一个真实用户是原话，不是纠正指令").isEqualTo("创建并导出");
	}

	/** Critical-B：写历史前的过滤链保留真实用户/工具链，只去掉纠正指令。 */
	@Test
	void withoutCorrectionInstructionKeepsRealUserAndToolChainOnly() {
		UserMessage real = new UserMessage("创建并导出");
		UserMessage correction = new UserMessage(ResilientChatService.exportWrapUpCorrection(42L));
		ToolResponseMessage tool = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "set_condition", "{}")))
			.build();

		List<Message> filtered = com.example.configmgr.ai.history.SyntheticMessages
			.withoutCorrectionInstruction(List.of(real, correction, tool));

		assertThat(filtered).containsExactly(real, tool);
	}

	/** 去重只删本轮前缀：往轮出现同文 assistant 必须保留。 */
	@Test
	void removePrefixOnlyDeletesTheCurrentTurnAssistant() {
		List<Message> history = new ArrayList<>(List.of(new AssistantMessage("好"), new UserMessage("上一轮"),
				new AssistantMessage("上一轮答复"), new UserMessage("本轮"),
				new AssistantMessage("好")));

		int removed = ResilientChatService.removeAssistantWithText(history, "好");

		assertThat(removed).isEqualTo(1);
		assertThat(history).filteredOn(message -> message instanceof AssistantMessage assistant
				&& "好".equals(assistant.getText())).as("往轮同文必须保留，只删本轮那条").hasSize(1);
		assertThat(history.get(0)).isInstanceOf(AssistantMessage.class);
	}

	/** seed 前缀只用于计数/最终记忆：本段没有新内容时 emittedVisible 仍为 false。 */
	@Test
	void seededPrefixAloneIsNotVisibleOutput() throws Exception {
		Class<?> accumulatorType = Class.forName("com.example.configmgr.ai.run.ResilientChatService$Accumulator");
		var constructor = accumulatorType.getDeclaredConstructor(String.class);
		constructor.setAccessible(true);
		Object accumulator = constructor.newInstance("run-seed-visible");
		var seed = accumulatorType.getDeclaredMethod("seedPrefix", String.class, String.class);
		seed.setAccessible(true);
		seed.invoke(accumulator, "第一段", "");
		var emittedVisible = accumulatorType.getDeclaredMethod("emittedVisible");
		emittedVisible.setAccessible(true);
		assertThat((Boolean) emittedVisible.invoke(accumulator)).as("只有 seed 前缀不算本段可见产出").isFalse();

		var textField = accumulatorType.getDeclaredField("text");
		textField.setAccessible(true);
		((StringBuilder) textField.get(accumulator)).append("新段");
		assertThat((Boolean) emittedVisible.invoke(accumulator)).as("本段新正文才算可见产出").isTrue();
	}

	/** 同轮推进到 EXPORT 后按<b>最新快照上下文</b>触发，而不是驱动起点的冻结上下文。 */
	@Test
	void correctionDueUsesTheLatestRunContext() {
		InMemoryRunStore store = new InMemoryRunStore();
		ObjectMapper mapper = new ObjectMapper();
		MessageJsonCodec codec = new MessageJsonCodec(mapper);
		RunSnapshot snapshot = store.seedRun("run-correction-latest", "s-correction-latest");
		snapshot.setContext(AiContext.of("export", "EXPORT", "QUERY_COND", 42L, "ACTIVE", Map.of()).toMap());
		store.save(snapshot);
		// 意图的结构化证据：本 run 已 resolved 的 create_task(type=EXPORT)（原话不参与判定）。
		PendingToolCall createTask = new PendingToolCall();
		createTask.setToolCallId("call-create-export");
		createTask.setName("create_task");
		createTask.setArguments("{\"type\":\"EXPORT\"}");
		createTask.setStatus(PendingToolCall.EXECUTED);
		createTask.setOutcome(com.example.configmgr.ai.tool.ToolOutcome.OK.wire());
		store.putPending("run-correction-latest", createTask);
		ToolRegistry toolRegistry = mock(ToolRegistry.class);
		ToolCallback startExport = mock(ToolCallback.class);
		ToolDefinition definition = mock(ToolDefinition.class);
		when(definition.name()).thenReturn("start_export");
		when(startExport.getToolDefinition()).thenReturn(definition);
		when(toolRegistry.forContext(any(AiContext.class)))
			.thenReturn(List.of(startExport));
		ResilientChatService service = new ResilientChatService(
				mock(AiProviderRegistry.class),
				mock(ChatMemory.class), codec, store,
				mock(RunRegistry.class), toolRegistry,
				mock(ContextBuilder.class),
				mock(AiSessionService.class), new AiProperties(),
				mock(CancellationRegistry.class),
				mock(ToolActivityBeacon.class), null);

		assertThat(service.exportWrapUpCorrectionDue("run-correction-latest", EXPORT_STEP, "创建并导出"))
			.as("快照仍是 QUERY_COND ⇒ 不触发（不拿冻结上下文冒充最新）").isFalse();

		snapshot.setContext(EXPORT_STEP.toMap());
		store.save(snapshot);
		assertThat(service.exportWrapUpCorrectionDue("run-correction-latest", EXPORT_STEP, "创建并导出"))
			.as("工具批刷新到 EXPORT 后按最新上下文触发").isTrue();
	}
}
