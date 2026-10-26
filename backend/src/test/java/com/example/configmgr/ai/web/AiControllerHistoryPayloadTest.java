package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.history.AiChatHistoryService;
import com.example.configmgr.ai.history.AiChatMessageRepository;
import com.example.configmgr.ai.history.SyntheticMessages;
import com.example.configmgr.ai.resultview.ListTasksResultViewProvider;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R-144 B-1：{@code GET /api/ai/history/{sessionId}} 的载荷断言（此前零覆盖）。
 *
 * <p>载荷面一律用 {@link ChatMemory} 桩（T5 裁决：history 读的是 ChatMemory，不是 RunStore），
 * 构造模板照抄 {@link AiControllerHistoryDeleteTest}（固定参数表 {@code new AiController(...)}，
 * 不动构造器签名）。断言面四条：
 * <ol>
 * <li>ASSISTANT 条目带 {@code toolCallStates}，逐 {@code toolCallId} 各读其态（一轮多工具、状态各异）；</li>
 * <li>{@code kind} 随同通道透出；</li>
 * <li>旧载荷（metadata 无该键）⇒ 后端不产 {@code toolCallStates} 字段；</li>
 * <li>既有五字段（type/text/toolCalls/toolResponses/timestamp）逐字未变；</li>
 * <li>AR-12：assistant 已有 {@code metadata.reasoningContent} 以 additive 键透出，且不产计时字段；
 * synthetic 条目（压缩摘要/收尾）不透出该键与任何内部 metadata。</li>
 * </ol>
 */
class AiControllerHistoryPayloadTest {

	private final ChatMemory chatMemory = mock(ChatMemory.class);

	private final ChatMemoryRepository chatMemoryRepository = mock(ChatMemoryRepository.class);

	private final SessionGate sessionGate = mock(SessionGate.class);

	private final RedisAvailability redisAvailability = mock(RedisAvailability.class);

	private final RunStore runStore = mock(RunStore.class);

	private final AiAvailability availability = mock(AiAvailability.class);

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller(this.availability)).build();

	@Test
	void assistantToolCallStatesAreExposedPerToolCallId() throws Exception {
		AssistantMessage assistant = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(
					new AssistantMessage.ToolCall("tc-1", "function", "start_publish", "{}"),
					new AssistantMessage.ToolCall("tc-2", "function", "download_export_files", "{}")))
			.properties(Map.of("toolCallStates", Map.of(
					"tc-1", Map.of("status", "EXECUTED", "kind", "CONFIRM"),
					"tc-2", Map.of("status", "BLOCKED", "kind", "FRONTEND"))))
			.build();
		when(this.chatMemory.get("s-1")).thenReturn(List.of(assistant));

		this.mockMvc.perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].type").value("ASSISTANT"))
			.andExpect(jsonPath("$.data.messages[0].toolCallStates.tc-1.status").value("EXECUTED"))
			.andExpect(jsonPath("$.data.messages[0].toolCallStates.tc-1.kind").value("CONFIRM"))
			.andExpect(jsonPath("$.data.messages[0].toolCallStates.tc-2.status").value("BLOCKED"))
			.andExpect(jsonPath("$.data.messages[0].toolCallStates.tc-2.kind").value("FRONTEND"));
	}

	@Test
	void oldPayloadWithoutToolCallStatesProducesNoField() throws Exception {
		AssistantMessage assistant = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("tc-1", "function", "start_publish", "{}")))
			.properties(Map.of("spikeTimestamp", "2026-10-12T00:00:00Z"))
			.build();
		when(this.chatMemory.get("s-1")).thenReturn(List.of(assistant));

		this.mockMvc.perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].toolCallStates").doesNotExist())
			.andExpect(jsonPath("$.data.messages[0].type").value("ASSISTANT"))
			.andExpect(jsonPath("$.data.messages[0].text").value(""))
			.andExpect(jsonPath("$.data.messages[0].toolCalls[0].id").value("tc-1"));
	}

	/**
	 * 批次2 / AR-01 第 5 条：<b>synthetic 展示过滤（读侧）</b> —— 压缩摘要按"系统消息"呈现，
	 * <b>不冒充用户原话</b>。判据复用 {@code SyntheticMessages}（与写侧同一份），载荷只加
	 * additive 键 {@code synthetic=true}，正文/角色字段一律原样。
	 */
	@Test
	void syntheticSummaryIsFlaggedInsteadOfPretendingToBeAUserMessage() throws Exception {
		UserMessage summary = new UserMessage(SyntheticMessages.SUMMARY_PLACEHOLDER);
		UserMessage real = new UserMessage("真的问题");
		when(this.chatMemory.get("s-1")).thenReturn(List.of(summary, real));

		this.mockMvc.perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].synthetic").value(true))
			.andExpect(jsonPath("$.data.messages[0].text").value(SyntheticMessages.SUMMARY_PLACEHOLDER))
			.andExpect(jsonPath("$.data.messages[1].synthetic").doesNotExist())
			.andExpect(jsonPath("$.data.messages[1].text").value("真的问题"));
	}

	/**
	 * AR-12（只写不跑）：历史 assistant 已有的 {@code metadata.reasoningContent} 原样透出；
	 * 只读展示，不透出计时（即使 metadata 里恰好有 {@code startedAt/endedAt} 也不下发）。
	 */
	@Test
	void assistantReasoningContentIsAdditiveWithoutTimingFields() throws Exception {
		AssistantMessage assistant = AssistantMessage.builder()
			.content("答复正文")
			.properties(Map.of(ResilientChatService.REASONING_METADATA_KEY, "先看任务再决定",
					"startedAt", 1_000L, "endedAt", 2_000L))
			.build();
		when(this.chatMemory.get("s-1")).thenReturn(List.of(assistant));

		this.mockMvc.perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].reasoningContent").value("先看任务再决定"))
			.andExpect(jsonPath("$.data.messages[0].startedAt").doesNotExist())
			.andExpect(jsonPath("$.data.messages[0].endedAt").doesNotExist());
	}

	/**
	 * AR-12 synthetic 守卫（只写不跑）：压缩摘要/收尾提示是系统提示，不是助手答复 ——
	 * 即使其 metadata 带 {@code reasoningContent}，也不得借本字段透出；其他内部 metadata
	 * （如 {@code compactedFrom}）同样不进入载荷。
	 */
	@Test
	void syntheticEntryDoesNotExposeReasoningOrInternalMetadata() throws Exception {
		AssistantMessage synthetic = AssistantMessage.builder()
			.content(SyntheticMessages.SUMMARY_PLACEHOLDER)
			.properties(Map.of(SyntheticMessages.COMPACTED_FROM, 42,
					ResilientChatService.REASONING_METADATA_KEY, "压缩过程的内部思考"))
			.build();
		when(this.chatMemory.get("s-1")).thenReturn(List.of(synthetic));

		this.mockMvc.perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].synthetic").value(true))
			.andExpect(jsonPath("$.data.messages[0].text").value(SyntheticMessages.SUMMARY_PLACEHOLDER))
			.andExpect(jsonPath("$.data.messages[0].reasoningContent").doesNotExist())
			.andExpect(jsonPath("$.data.messages[0].compactedFrom").doesNotExist());
	}

	/**
	 * AR-12 完整历史通道（只写不跑）：{@code ai_chat_message.metadata_json} 里已有的
	 * {@code reasoningContent} 经既有 {@code AiChatHistoryService#toMessage} 还原后，
	 * 由 {@code toHistoryItem} 原样透出；仍不产计时字段。**不新增表/列/持久化**。
	 */
	@Test
	void fullHistoryExposesPersistedReasoningContentWithoutTimingFields() throws Exception {
		AiChatHistoryService real = new AiChatHistoryService(mock(AiChatMessageRepository.class),
				new ObjectMapper(), mock(PlatformTransactionManager.class));
		AiChatHistoryService.MessageView view = new AiChatHistoryService.MessageView(1L, "s-1", "r-1", 1L,
				"m-1", "ASSISTANT", "答复正文", null, null,
				"{\"reasoningContent\":\"历史思考\",\"startedAt\":1000,\"endedAt\":2000}", false,
				LocalDateTime.now());

		AiChatHistoryService service = mock(AiChatHistoryService.class);
		when(service.pageBefore(eq("s-1"), isNull(), anyInt()))
			.thenReturn(new AiChatHistoryService.ChatPage(List.of(view), false, null));
		when(service.toMessage(view)).thenReturn(real.toMessage(view));

		AiController local = controller(this.availability);
		ReflectionTestUtils.setField(local, "aiChatHistoryService", service);
		MockMvc mvc = MockMvcBuilders.standaloneSetup(local).build();

		mvc.perform(get("/api/ai/history/s-1/messages"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].reasoningContent").value("历史思考"))
			.andExpect(jsonPath("$.data.messages[0].startedAt").doesNotExist())
			.andExpect(jsonPath("$.data.messages[0].endedAt").doesNotExist());
	}

	/**
	 * 截断专项：旧 {@code /history}（记忆窗口）以 additive 可选 {@code truncated} 字段回显
	 * {@code finish_reason=length} 的持久标记；未截断/其他角色不产该键（"没说" ≠ "没截断"）。
	 */
	@Test
	void truncatedAssistantExposesAdditiveFlagOnOldHistoryEndpoint() throws Exception {
		AssistantMessage truncated = AssistantMessage.builder()
			.content("被截断的回答")
			.properties(Map.of(ResilientChatService.TRUNCATED_KEY, true))
			.build();
		AssistantMessage complete = AssistantMessage.builder().content("完整回答").build();
		UserMessage user = new UserMessage("问题");
		when(this.chatMemory.get("s-1")).thenReturn(List.of(user, truncated, complete));

		this.mockMvc.perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].truncated").doesNotExist())
			.andExpect(jsonPath("$.data.messages[1].type").value("ASSISTANT"))
			.andExpect(jsonPath("$.data.messages[1].truncated").value(true))
			.andExpect(jsonPath("$.data.messages[1].stopped").doesNotExist())
			.andExpect(jsonPath("$.data.messages[2].truncated").doesNotExist());
	}

	/**
	 * C2：一轮完成后记忆里 {@code role:tool} 的 {@code responseData} 是统一信封 JSON（模型侧形态），
	 * 但 {@code /history} 是回显通道 —— 必须解包成 {@code message} 原文，不能把整段 JSON 甩给前端。
	 */
	@Test
	void envelopeResponseDataIsUnwrappedToPlainMessageInHistory() throws Exception {
		String envelopeJson = com.example.configmgr.ai.tool.ToolResultEnvelope
			.ok("导入作业已启动（作业 #4242）", "{\"taskId\":7}").toJson();
		ToolResponseMessage toolResponse = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("tc-1", "start_import", envelopeJson)))
			.build();
		when(this.chatMemory.get("s-1")).thenReturn(List.of(toolResponse));

		this.mockMvc.perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].type").value("TOOL"))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].id").value("tc-1"))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].name").value("start_import"))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].responseData")
				.value("导入作业已启动（作业 #4242）"));
	}

	@Test
	void existingFiveFieldsAreUnchanged() throws Exception {
		AssistantMessage assistant = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("tc-1", "function", "start_publish", "{}")))
			.properties(Map.of("spikeTimestamp", "ts-1"))
			.build();
		ToolResponseMessage toolResponse = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("tc-1", "start_publish", "OK")))
			.metadata(Map.of("spikeTimestamp", "ts-2"))
			.build();
		when(this.chatMemory.get("s-1")).thenReturn(List.of(assistant, toolResponse));

		this.mockMvc.perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].type").value("ASSISTANT"))
			.andExpect(jsonPath("$.data.messages[0].text").value(""))
			.andExpect(jsonPath("$.data.messages[0].toolCalls[0].id").value("tc-1"))
			.andExpect(jsonPath("$.data.messages[0].timestamp").value("ts-1"))
			.andExpect(jsonPath("$.data.messages[1].type").value("TOOL"))
			.andExpect(jsonPath("$.data.messages[1].toolResponses[0].id").value("tc-1"))
			.andExpect(jsonPath("$.data.messages[1].toolResponses[0].name").value("start_publish"))
			.andExpect(jsonPath("$.data.messages[1].toolResponses[0].responseData").value("OK"))
			.andExpect(jsonPath("$.data.messages[1].timestamp").value("ts-2"));
	}

	// ── R-154 #16 分页参数 ──

	@Test
	void paginationDefaultsToFullSemantics() throws Exception {
		when(this.chatMemory.get("s-1")).thenReturn(List.of(new UserMessage("m0"), new UserMessage("m1"),
				new UserMessage("m2")));

		this.mockMvc.perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.count").value(3))
			.andExpect(jsonPath("$.data.total").value(3))
			.andExpect(jsonPath("$.data.hasMore").value(false))
			.andExpect(jsonPath("$.data.messages.length()").value(3))
			.andExpect(jsonPath("$.data.messages[0].text").value("m0"))
			.andExpect(jsonPath("$.data.messages[2].text").value("m2"));
	}

	@Test
	void paginationOffsetLimitSlicesWindowInOrder() throws Exception {
		when(this.chatMemory.get("s-1")).thenReturn(List.of(new UserMessage("m0"), new UserMessage("m1"),
				new UserMessage("m2"), new UserMessage("m3"), new UserMessage("m4")));

		this.mockMvc.perform(get("/api/ai/history/s-1").param("offset", "1").param("limit", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.count").value(2))
			.andExpect(jsonPath("$.data.total").value(5))
			.andExpect(jsonPath("$.data.hasMore").value(true))
			.andExpect(jsonPath("$.data.messages.length()").value(2))
			.andExpect(jsonPath("$.data.messages[0].text").value("m1"))
			.andExpect(jsonPath("$.data.messages[1].text").value("m2"));
	}

	@Test
	void paginationHasMoreReflectsOlderMessagesBeforeSlice() throws Exception {
		when(this.chatMemory.get("s-1")).thenReturn(List.of(new UserMessage("m0"), new UserMessage("m1"),
				new UserMessage("m2"), new UserMessage("m3"), new UserMessage("m4")));

		this.mockMvc.perform(get("/api/ai/history/s-1").param("offset", "3"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.count").value(2))
			.andExpect(jsonPath("$.data.total").value(5))
			.andExpect(jsonPath("$.data.hasMore").value(true))
			.andExpect(jsonPath("$.data.messages[0].text").value("m3"))
			.andExpect(jsonPath("$.data.messages[1].text").value("m4"));
	}

	@Test
	void paginationLimitZeroReturnsEmptySliceWithTotal() throws Exception {
		when(this.chatMemory.get("s-1")).thenReturn(List.of(new UserMessage("m0"), new UserMessage("m1")));

		this.mockMvc.perform(get("/api/ai/history/s-1").param("limit", "0"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.count").value(0))
			.andExpect(jsonPath("$.data.total").value(2))
			.andExpect(jsonPath("$.data.hasMore").value(false))
			.andExpect(jsonPath("$.data.messages.length()").value(0));
	}

	@Test
	void paginationNegativeOffsetAndLimitClampToZero() throws Exception {
		when(this.chatMemory.get("s-1")).thenReturn(List.of(new UserMessage("m0"), new UserMessage("m1")));

		this.mockMvc.perform(get("/api/ai/history/s-1").param("offset", "-1").param("limit", "-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.count").value(0))
			.andExpect(jsonPath("$.data.total").value(2))
			.andExpect(jsonPath("$.data.hasMore").value(false))
			.andExpect(jsonPath("$.data.messages.length()").value(0));
	}

	@Test
	void paginationOffsetBeyondTotalReturnsEmptySliceWithTotal() throws Exception {
		when(this.chatMemory.get("s-1")).thenReturn(List.of(new UserMessage("m0"), new UserMessage("m1")));

		this.mockMvc.perform(get("/api/ai/history/s-1").param("offset", "5"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.count").value(0))
			.andExpect(jsonPath("$.data.total").value(2))
			.andExpect(jsonPath("$.data.hasMore").value(false))
			.andExpect(jsonPath("$.data.messages.length()").value(0));
	}

	// ── L-78：历史面重新派生结果视图 ──

	/**
	 * L-78（真机 E2E 批② NFEZUI 域 Z-06 转正）：{@code resultView} 是**帧内字段**，
	 * 历史重建此前只带 {@code toolCallStates} ⇒ 刷新后卡面回落旧文本形态。
	 *
	 * <p>修法 = 历史侧**按工具名从注册表重新派生**：不新增任何持久面（🏛️「AI 对话数据不落库」），
	 * 读侧投影与帧侧同形。只对**成功结局**出视图（与帧路径 `resultViewOf` 同口径）。
	 *
	 * <p>Task 6（BZ-33）：历史侧的行值取自<b>当时那次调用的结果原文</b>，
	 * 不再重取实时库 —— 本用例故意让实时库返回空列表，行仍必须来自历史文本。
	 */
	@Test
	void historyDerivesResultViewByToolName() throws Exception {
		TaskService taskService = mock(TaskService.class);
		when(taskService.findAll()).thenReturn(List.of());

		AiController controller = controller(this.availability);
		ReflectionTestUtils.setField(controller, "resultViewProviders",
				List.of(new ListTasksResultViewProvider(taskService, new AiProperties())));
		MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

		ToolResponseMessage ok = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("tc-1", "list_tasks",
					com.example.configmgr.ai.tool.ToolResultEnvelope.ok(
							"- #7 [IMPORT] 发布用例 步骤:PUBLISH 状态:ACTIVE", "{}").toJson())))
			.build();
		when(this.chatMemory.get("s-1")).thenReturn(List.of(ok));

		mvc.perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].resultView.kind").value("table"))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].resultView.id").value("rv-tc-1"))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].resultView.columns[0].key").value("id"))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].resultView.data.inline.rows[0].id").value(7));
	}

	/**
	 * BZ-33（S3）：<b>历史 {@code list_tasks} 不得被当前库改写</b>。
	 *
	 * <p>缺陷（修复前实测）：历史投影走的是与实时帧同一个 {@code build}，
	 * 而它只做 {@code taskService.findAll()} —— {@code /history} 因此每次刷新都用<b>今天</b>的任务表
	 * 重造归档卡面：任务被改名/推进/删除后，归档表格与同轮 assistant 文本自相矛盾；
	 * {@code meta.truncated{shownRows,totalRows}} 也跟着重算，"共 N 条"可变成与当时不同的数字。
	 *
	 * <p>判据：实时库返回一条<b>完全不同</b>的任务（#9，新标题），历史文本记的是当时的 #7；
	 * 卡面必须显示 #7 的原标题，且 {@code findAll()} <b>一次都不许被调用</b>。
	 */
	@Test
	void historyListViewKeepsTheResultOfThatMomentNotTheCurrentDatabase() throws Exception {
		Task renamedToday = new Task();
		renamedToday.setId(9L);
		renamedToday.setType(Task.TaskType.EXPORT);
		renamedToday.setTitle("今天的标题");
		renamedToday.setCurrentStep("EXPORT");
		renamedToday.setStatus(Task.TaskStatus.COMPLETED);
		TaskService taskService = mock(TaskService.class);
		when(taskService.findAll()).thenReturn(List.of(renamedToday));

		AiController controller = controller(this.availability);
		ReflectionTestUtils.setField(controller, "resultViewProviders",
				List.of(new ListTasksResultViewProvider(taskService, new AiProperties())));
		MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

		String historicalText = "- #7 [IMPORT] 当时的标题 步骤:PRECHECK 状态:ACTIVE\n"
				+ "- #8 [EXPORT] 另一条 步骤:SELECT_DEFS 状态:ACTIVE";
		ToolResponseMessage ok = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("tc-1", "list_tasks",
					com.example.configmgr.ai.tool.ToolResultEnvelope.ok(historicalText, "{}").toJson())))
			.build();
		when(this.chatMemory.get("s-1")).thenReturn(List.of(ok));

		mvc.perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].resultView.data.inline.rows[0].id").value(7))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].resultView.data.inline.rows[0].title")
				.value("当时的标题"))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].resultView.data.inline.rows[0].step")
				.value("PRECHECK"))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].resultView.data.inline.rows[1].id").value(8));

		verify(taskService, never()).findAll();
	}

	/**
	 * BZ-33 的另一面：历史文本不是当前行格式（旧会话/其它形态）时<b>不出视图</b>，
	 * 也不得偷偷回落到实时库 —— 宁可让卡面回到原始文本形态，也不能用今天的库冒充当时的结果。
	 */
	@Test
	void unparseableHistoryTextYieldsNoViewInsteadOfLiveRows() throws Exception {
		TaskService taskService = mock(TaskService.class);
		when(taskService.findAll()).thenReturn(List.of());

		AiController controller = controller(this.availability);
		ReflectionTestUtils.setField(controller, "resultViewProviders",
				List.of(new ListTasksResultViewProvider(taskService, new AiProperties())));
		MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

		ToolResponseMessage ok = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("tc-1", "list_tasks",
					com.example.configmgr.ai.tool.ToolResultEnvelope.ok("- #7 [IMPORT] 旧形态只有三列", "{}").toJson())))
			.build();
		when(this.chatMemory.get("s-1")).thenReturn(List.of(ok));

		mvc.perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].resultView").doesNotExist());

		verify(taskService, never()).findAll();
	}

	/** 失败结局不出视图（与帧路径同口径：视图是"成功结果"的投影，失败走旧卡面）。 */
	@Test
	void historySkipsResultViewForFailedOutcome() throws Exception {
		TaskService taskService = mock(TaskService.class);
		when(taskService.findAll()).thenReturn(List.of());
		AiController controller = controller(this.availability);
		ReflectionTestUtils.setField(controller, "resultViewProviders",
				List.of(new ListTasksResultViewProvider(taskService, new AiProperties())));
		MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

		ToolResponseMessage failed = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("tc-1", "list_tasks",
					com.example.configmgr.ai.tool.ToolResultEnvelope.failed("TOOL_FAILED", "任务列表读取失败", "{}")
						.toJson())))
			.build();
		when(this.chatMemory.get("s-1")).thenReturn(List.of(failed));

		mvc.perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].resultView").doesNotExist());
	}

	/**
	 * A10 R-15：模型侧截断是持久事实 —— 历史通道把信封五字段透出，刷新后前端仍能重建角标。
	 * 未截断/旧数据不产这些键（载荷形状逐字不变）。
	 */
	@Test
	void truncatedToolResponseExposesTruncationFieldsInHistory() throws Exception {
		String truncated = new com.example.configmgr.ai.tool.ToolResultEnvelope(true, null, "ok", false, null, null,
				"前 64KB…", true, "head", 25_000, 19_000, "Result truncated for model view.")
			.toJson();
		ToolResponseMessage toolResponse = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("tc-1", "get_row_count", truncated)))
			.build();
		when(this.chatMemory.get("s-1")).thenReturn(List.of(toolResponse));

		this.mockMvc.perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].responseData").value("前 64KB…"))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].truncated").value(true))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].noticeCode").value("model_view_truncated"))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].originalChars").value(25_000))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].returnedChars").value(19_000))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].strategy").value("head"))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].truncatedReason")
				.value("Result truncated for model view."));
	}

	@Test
	void wrappedModelSideEnvelopeIsReducedToMessageInHistory() throws Exception {
		// L-70：生产记忆是 toModelJson 的产物（声明 + <tool_result> 外壳 + 信封 JSON）。
		String modelJson = com.example.configmgr.ai.tool.ToolResultEnvelope.ok("导入作业已启动", "{}")
			.toModelJson((com.example.configmgr.ai.tool.ToolResultLimiter) null);
		ToolResponseMessage toolResponse = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("tc-1", "start_import", modelJson)))
			.build();
		when(this.chatMemory.get("s-1")).thenReturn(List.of(toolResponse));

		this.mockMvc.perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].responseData").value("导入作业已启动"))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].truncated").doesNotExist());
	}

	@Test
	void untruncatedToolResponseProducesNoTruncationFields() throws Exception {
		String envelope = com.example.configmgr.ai.tool.ToolResultEnvelope.ok("完整结果", "{}").toJson();
		ToolResponseMessage toolResponse = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("tc-1", "get_row_count", envelope)))
			.build();
		when(this.chatMemory.get("s-1")).thenReturn(List.of(toolResponse));

		this.mockMvc.perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].responseData").value("完整结果"))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].truncated").doesNotExist())
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].noticeCode").doesNotExist());
	}

	@Test
	void truncatedModelSideWrappedEnvelopeStillExposesTruncationFields() throws Exception {
		// 生产形态：记忆里的 responseData 是 toModelJson 的产物（带 <tool_result> 定界外壳）。
		String longMessage = "x".repeat(25_000);
		String modelJson = com.example.configmgr.ai.tool.ToolResultEnvelope.ok(longMessage, "{}")
			.toModelJson(new com.example.configmgr.ai.tool.ToolResultLimiter(new AiProperties()), "get_row_count");
		ToolResponseMessage toolResponse = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("tc-1", "get_row_count", modelJson)))
			.build();
		when(this.chatMemory.get("s-1")).thenReturn(List.of(toolResponse));

		this.mockMvc.perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].truncated").value(true))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].noticeCode").value("model_view_truncated"))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].originalChars").value(25_000))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].responseData").value(
					org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<tool_result>"))));
	}

	@SuppressWarnings("unchecked")
	private AiController controller(AiAvailability availability) {
		return new AiController(availability, provider(), provider(), provider(), this.chatMemory,
				this.chatMemoryRepository, this.sessionGate, mock(ConfirmGate.class), this.runStore,
				mock(RunRegistry.class), mock(CancellationRegistry.class), this.redisAvailability,
				mock(ThreadPoolTaskExecutor.class), new ObjectMapper(), new AiProperties(), mock(Environment.class),
				mock(com.example.configmgr.job.service.JobWatchdog.class));
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}

}
