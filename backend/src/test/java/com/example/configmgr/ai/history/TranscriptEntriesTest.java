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

		List<TranscriptEntry> entries = TranscriptEntries.fromMessages(history, Map.of("c-1", pending), false,
				100L);

		assertThat(entries).singleElement()
			.extracting(TranscriptEntry::result).isEqualTo("工具原文");
	}

	@Test
	void clearedPlaceholderIsNeverWrittenWithoutPendingOriginal() {
		List<Message> history = List.of(ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("c-1", "list_tasks",
					ToolResultClearing.placeholder("list_tasks", 1200, "c-1"))))
			.build());

		assertThat(TranscriptEntries.fromMessages(history, Map.of(), false, 100L)).isEmpty();
	}

	@Test
	void syntheticCompactionSummaryIsNotWritten() {
		UserMessage summary = UserMessage.builder()
			.text(RollingSummarizer.SUMMARY_PREFIX + "\n<conversation_summary>旧事</conversation_summary>")
			.metadata(Map.of(SyntheticMessages.COMPACTED_FROM, 3))
			.build();

		assertThat(TranscriptEntries.fromMessages(List.of(new UserMessage("原话"), summary), Map.of(), false, 1L))
			.extracting(TranscriptEntry::text)
			.containsExactly("原话");
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
				"run-a", 100L, true);
		List<TranscriptEntry> withoutFinal = TranscriptEntries.fromRunTerminal(history, Map.of("c-1", pending),
				"run-a", 100L, false);

		assertThat(withFinal).extracting(TranscriptEntry::entryId)
			.containsExactly("run-a:assistant:c-1", "run-a:tool:c-1", "run-a:assistant");
		assertThat(withoutFinal).extracting(TranscriptEntry::entryId)
			.containsExactly("run-a:assistant:c-1", "run-a:tool:c-1");
		assertThat(withFinal).noneMatch(entry -> entry.text() != null && entry.text().contains("上一轮"));
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
				100L, false);

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
		AssistantMessage assistant = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("c-1", "function", "list_tasks", "{}")))
			.build();
		List<Message> history = List.of(new UserMessage("问"), assistant,
				ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("c-1", "list_tasks", "答")))
					.build());

		List<TranscriptEntry> first = TranscriptEntries.fromMessages(history, Map.of(), false, 1L);
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
		List<TranscriptEntry> withRun = TranscriptEntries.fromMessages(attributed, Map.of(), false, 1L);

		assertThat(first).extracting(TranscriptEntry::entryId)
			.containsExactly("baseline:1", "baseline:2", "baseline:3:0");
		assertThat(withRun).extracting(TranscriptEntry::entryId)
			.contains("run-a:user", "run-a:assistant:c-1", "run-a:tool:c-1");
	}

	@Test
	void legacyBaselineAndRunEntriesConvergeByStructureWithoutMemoryIndexes() {
		List<TranscriptEntry> legacy = TranscriptEntries.baseline(List.of(new UserMessage("问"), new AssistantMessage("答")));
		List<Message> attributed = List.of(
				UserMessage.builder().text("问").metadata(Map.of("runId", "run-a")).build(),
				AssistantMessage.builder().content("答").properties(Map.of("runId", "run-a")).build());
		List<TranscriptEntry> current = TranscriptEntries.fromMessages(attributed, Map.of(), false, 100L);
		List<TranscriptEntry> duplicated = new java.util.ArrayList<>(legacy);
		duplicated.addAll(current);

		List<TranscriptEntry> reconciled = TranscriptEntries.reconcileWithMemory(duplicated, null);

		assertThat(reconciled).extracting(TranscriptEntry::entryId)
			.containsExactly("run-a:user", "run-a:assistant");
	}

	@Test
	void identicalTextInDifferentRunsSurvivesLegacyBaselineConvergence() {
		List<TranscriptEntry> duplicated = new java.util.ArrayList<>(
				TranscriptEntries.baseline(List.of(new UserMessage("同文"), new AssistantMessage("同答"))));
		for (String runId : List.of("run-a", "run-b")) {
			List<Message> attributed = List.of(
					UserMessage.builder().text("同文").metadata(Map.of("runId", runId)).build(),
					AssistantMessage.builder().content("同答").properties(Map.of("runId", runId)).build());
			duplicated.addAll(TranscriptEntries.fromMessages(attributed, Map.of(), false, 100L));
		}

		List<TranscriptEntry> reconciled = TranscriptEntries.reconcileWithMemory(duplicated, null);

		assertThat(reconciled).extracting(TranscriptEntry::entryId)
			.containsExactly("run-a:user", "run-a:assistant", "run-b:user", "run-b:assistant");
	}

	@Test
	void legacySameTextEntriesAreConsumedOneForOne() {
		List<TranscriptEntry> duplicated = new java.util.ArrayList<>(
				TranscriptEntries.baseline(List.of(new UserMessage("同文"), new UserMessage("同文"))));
		duplicated.add(TranscriptEntries.user("run-a", 100L, 100L, "同文"));

		List<TranscriptEntry> reconciled = TranscriptEntries.reconcileWithMemory(duplicated, null);

		assertThat(reconciled).extracting(TranscriptEntry::entryId)
			.containsExactly("baseline:2", "run-a:user");
	}

}
