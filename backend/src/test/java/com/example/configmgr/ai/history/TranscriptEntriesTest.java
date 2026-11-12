package com.example.configmgr.ai.history;

import com.example.configmgr.ai.context.RollingSummarizer;
import com.example.configmgr.ai.context.ToolResultClearing;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tool.TruncationContract;
import com.example.configmgr.common.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2 展示历史投影：工具原文优先取 pending.resultText、占位符/合成消息不写、entryId 稳定。
 */
class TranscriptEntriesTest {

	@Test
	void toolResultPrefersPendingOriginalOverClearedHistory() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("c-1");
		pending.setName("list_tasks");
		pending.setStatus(PendingToolCall.EXECUTED);
		pending.setResultText("工具原文");
		List<Message> history = List.of(ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("c-1", "list_tasks",
					ToolResultClearing.placeholder("list_tasks", 1200, "c-1"))))
			.build());

		List<TranscriptEntry> entries = TranscriptEntries.fromRunTerminal(history, Map.of("c-1", pending), "run-a", 100L, false, null, null);

		assertThat(entries).singleElement()
			.extracting(TranscriptEntry::result).isEqualTo("工具原文");
	}

	@Test
	void clearedPlaceholderIsNeverWrittenWithoutPendingOriginal() {
		List<Message> history = List.of(ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("c-1", "list_tasks",
					ToolResultClearing.placeholder("list_tasks", 1200, "c-1"))))
			.build());

		assertThat(TranscriptEntries.fromRunTerminal(history, Map.of(), "run-a", 100L, false, null, null)).isEmpty();
	}

	@Test
	void compactionSummaryCarriesInternalOriginWithoutInternalReasoning() throws Exception {
		UserMessage summary = UserMessage.builder()
			.text(RollingSummarizer.SUMMARY_PREFIX + "\n<conversation_summary>旧事</conversation_summary>")
			.metadata(Map.of("origin", "COMPACTION_SUMMARY", SyntheticMessages.COMPACTED_FROM, 3,
					"reasoningContent", "压缩过程的内部思考"))
			.build();

		List<TranscriptEntry> entries = TranscriptEntries.fromRunTerminal(
				List.of(new UserMessage("原话"), summary), Map.of(), "run-a", 1L, false, null, null);

		assertThat(entries).hasSize(1);
		assertThat(entries.get(0).kind()).isEqualTo("assistant");
		assertThat(entries.get(0).text()).isEqualTo(summary.getText());
		assertThat(entries.get(0).reasoning()).isNull();
		assertThat(new ObjectMapper().writeValueAsString(entries.get(0)))
			.contains("\"origin\":\"COMPACTION_SUMMARY\"");
	}

	/**
	 * 终态投影只写本 run：快照里携带的旧轮 assistant/user 不得被重复写成本轮条目。
	 * 成功/取消（includeLastAssistant=true）写最后一条终稿；空回复/失败（false）不写。
	 */
	@Test
	void terminalProjectionOnlyWritesCurrentRun() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("c-1");
		pending.setName("list_tasks");
		pending.setStatus(PendingToolCall.EXECUTED);
		pending.setResultText("本轮工具原文");
		AssistantMessage prior = AssistantMessage.builder().content("上一轮回答").build();
		AssistantMessage toolCall = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("c-1", "function", "list_tasks", "{}")))
			.build();
		AssistantMessage finalAnswer = AssistantMessage.builder().content("本轮回答").build();
		List<Message> history = List.of(new UserMessage("上一轮问题"), prior, new UserMessage("本轮问题"),
				toolCall, ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("c-1", "list_tasks", "占位")))
					.build(),
				finalAnswer);

		List<TranscriptEntry> withFinal = TranscriptEntries.fromRunTerminal(history, Map.of("c-1", pending),
				"run-a", 100L, true, null, null);
		List<TranscriptEntry> withoutFinal = TranscriptEntries.fromRunTerminal(history, Map.of("c-1", pending),
				"run-a", 100L, false, null, null);

		assertThat(withFinal).extracting(TranscriptEntry::entryId)
			.containsExactly("run-a:assistant:c-1", "run-a:tool:c-1", "run-a:assistant:text-5");
		assertThat(withoutFinal).extracting(TranscriptEntry::entryId)
			.containsExactly("run-a:assistant:c-1", "run-a:tool:c-1", "run-a:assistant:text-5");
		assertThat(withFinal).noneMatch(entry -> entry.text() != null && entry.text().contains("上一轮"));
		assertThat(withFinal).noneMatch(entry -> "user".equals(entry.kind()),
			"用户条目由轮首/终态补写点负责，终态投影不得隐式补 user");

		// includeLastAssistant 的真实语义：只决定“无可见正文、仅失败事实的末条 assistant”是否补写。
		List<Message> emptyFailure = List.of(new UserMessage("问"), AssistantMessage.builder()
			.content("")
			.properties(Map.of(TranscriptEntries.ERROR_CODE_KEY, "TOOL_FAILED",
					TranscriptEntries.ERROR_MESSAGE_KEY, "失败事实"))
			.build());
		assertThat(TranscriptEntries.fromRunTerminal(emptyFailure, Map.of(), "run-a", 100L, false, null, null))
			.as("无可视正文且不是终稿补写 ⇒ 不产条目").isEmpty();
		assertThat(TranscriptEntries.fromRunTerminal(emptyFailure, Map.of(), "run-a", 100L, true, null, null))
			.singleElement().satisfies(entry -> {
				assertThat(entry.kind()).isEqualTo("assistant");
				assertThat(entry.errorCode()).isEqualTo("TOOL_FAILED");
			});
	}

	/** P2：transcript 只存原文，但信封里的 code 与模型侧截断事实必须一并落盘。 */
	@Test
	void toolEntriesKeepEnvelopeCodeAndTruncationFacts() throws Exception {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("c-1");
		pending.setName("list_tasks");
		pending.setStatus(PendingToolCall.SKIPPED);
		pending.setCode(ErrorCodes.TOOL_STEP_LIMIT);
		pending.setResultText("未执行：本轮工具调用步数已达上限");
		ToolResultEnvelope envelope = new ToolResultEnvelope(false, ErrorCodes.TOOL_STEP_LIMIT, "skipped", false, null,
				null, "model text", true, TruncationContract.STRATEGY_HEAD, 900, 100, "notice");
		List<Message> history = List.of(ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("c-1", "list_tasks",
					new ObjectMapper().writeValueAsString(envelope))))
			.build());

		List<TranscriptEntry> entries = TranscriptEntries.fromRunTerminal(history, Map.of("c-1", pending), "run-a",
				100L, false, null, null);

		assertThat(entries).singleElement().satisfies(entry -> {
			assertThat(entry.result()).isEqualTo("未执行：本轮工具调用步数已达上限");
			assertThat(entry.code()).isEqualTo(ErrorCodes.TOOL_STEP_LIMIT);
			assertThat(entry.toolTruncated()).isTrue();
			assertThat(entry.toolStrategy()).isEqualTo(TruncationContract.STRATEGY_HEAD);
			assertThat(entry.toolOriginalChars()).isEqualTo(900);
			assertThat(entry.toolReturnedChars()).isEqualTo(100);
		});
	}

	@Test
	void entryIdsAreStableAndScopedByRun() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("c-1");
		pending.setName("list_tasks");
		pending.setStatus(PendingToolCall.EXECUTED);
		pending.setResultText("答");
		AssistantMessage assistant = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("c-1", "function", "list_tasks", "{}")))
			.build();
		List<Message> history = List.of(new UserMessage("问"), assistant,
				ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("c-1", "list_tasks", "答")))
					.build());

		List<TranscriptEntry> first = TranscriptEntries.fromRunTerminal(history, Map.of("c-1", pending), "run-a", 1L,
				false, null, null);
		List<Message> attributed = List.of(
				UserMessage.builder().text("问").metadata(Map.of("runId", "run-a")).build(),
				AssistantMessage.builder()
					.content("")
					.toolCalls(List.of(new AssistantMessage.ToolCall("c-1", "function", "list_tasks", "{}")))
					.properties(Map.of("runId", "run-a"))
					.build(),
				ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("c-1", "list_tasks", "答")))
					.metadata(Map.of("runId", "run-a"))
					.build());
		List<TranscriptEntry> withRun = TranscriptEntries.fromRunTerminal(attributed, Map.of("c-1", pending),
				"run-a", 1L, false, null, null);
		List<TranscriptEntry> otherRun = TranscriptEntries.fromRunTerminal(attributed, Map.of("c-1", pending),
				"run-b", 1L, false, null, null);

		assertThat(first).extracting(TranscriptEntry::entryId)
			.containsExactly("run-a:assistant:c-1", "run-a:tool:c-1");
		assertThat(withRun).extracting(TranscriptEntry::entryId)
			.containsExactly("run-a:assistant:c-1", "run-a:tool:c-1");
		assertThat(withRun).extracting(TranscriptEntry::entryId)
			.containsExactlyElementsOf(first.stream().map(TranscriptEntry::entryId).toList());
		assertThat(otherRun).extracting(TranscriptEntry::entryId)
			.containsExactly("run-b:assistant:c-1", "run-b:tool:c-1");
	}

	@Test
	void terminalProjectionDoesNotEmitLegacyBaselineEntries() {
		List<Message> attributed = List.of(
				UserMessage.builder().text("问").metadata(Map.of("runId", "run-a")).build(),
				AssistantMessage.builder().content("答").properties(Map.of("runId", "run-a")).build());

		List<TranscriptEntry> entries = TranscriptEntries.fromRunTerminal(attributed, Map.of(), "run-a", 100L,
				true, null, null);

		// 当前契约：只投影本轮 assistant，不再产生 baseline:* / 隐式 user 条目，也不会跨 run 合并
		assertThat(entries).extracting(TranscriptEntry::entryId)
			.containsExactly("run-a:assistant:text-1");
		assertThat(entries).noneMatch(entry -> "user".equals(entry.kind()));
	}

	/** 投影幂等：同一历史重复投影得到相同 entryId 集合（存储层按 entryId 去重的前提）。 */
	@Test
	void toolCallStateCarriesStructuredJobTargetAndLegacyStateStaysUnknown() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("c-job");
		pending.setName("start_publish");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		pending.setArguments("{\"taskId\":7}");
		pending.setStatus(PendingToolCall.EXECUTED);
		pending.setOutcome("ok");
		assertThat(PendingToolCall.class.getDeclaredFields())
			.as("历史投影只能来自持久化 createdJobId")
			.anyMatch(field -> "createdJobId".equals(field.getName()));
		try {
			java.lang.reflect.Field field = PendingToolCall.class.getDeclaredField("createdJobId");
			field.setAccessible(true);
			field.set(pending, 4242L);
		} catch (ReflectiveOperationException ex) {
			throw new AssertionError(ex);
		}

		assertThat(TranscriptEntries.toolCallState(pending, PendingToolCall.EXECUTED))
			.containsEntry("target", Map.of("taskId", "7", "jobId", "4242"));

		PendingToolCall legacy = new PendingToolCall();
		legacy.setToolCallId("c-legacy");
		legacy.setArguments("{\"taskId\":7}");
		legacy.setStatus(PendingToolCall.EXECUTED);
		legacy.setResultText("发布作业已启动（作业 #999）");
		assertThat(TranscriptEntries.toolCallState(legacy, PendingToolCall.EXECUTED))
			.containsEntry("target", Map.of("taskId", "7"));
	}

	@Test
	void terminalProjectionIsStableForTheSameHistory() {
		List<Message> attributed = List.of(
				UserMessage.builder().text("同文").metadata(Map.of("runId", "run-a")).build(),
				AssistantMessage.builder().content("同答").properties(Map.of("runId", "run-a")).build());

		List<TranscriptEntry> first = TranscriptEntries.fromRunTerminal(attributed, Map.of(), "run-a", 100L,
				true, null, null);
		List<TranscriptEntry> second = TranscriptEntries.fromRunTerminal(attributed, Map.of(), "run-a", 100L,
				true, null, null);

		assertThat(first).extracting(TranscriptEntry::entryId)
			.containsExactly("run-a:assistant:text-1");
		assertThat(second).extracting(TranscriptEntry::entryId)
			.containsExactlyElementsOf(first.stream().map(TranscriptEntry::entryId).toList());
	}

}
