package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.history.ConversationTranscriptStore;
import com.example.configmgr.ai.history.TranscriptEntries;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.session.SessionGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B2：{@code /history} 的读点顺序 —— transcript 优先，空则回落 chat:mem；读失败可见（503）。
 */
class AiControllerHistoryTranscriptTest {

	private final ChatMemory chatMemory = mock(ChatMemory.class);

	private final ChatMemoryRepository chatMemoryRepository = mock(ChatMemoryRepository.class);

	private final SessionGate sessionGate = mock(SessionGate.class);

	private final RedisAvailability redisAvailability = mock(RedisAvailability.class);

	private final RunStore runStore = mock(RunStore.class);

	private final AiAvailability availability = mock(AiAvailability.class);

	private final ConversationTranscriptStore transcript = mock(ConversationTranscriptStore.class);

	private java.util.List<com.example.configmgr.ai.resultview.ToolResultViewProvider> resultViewProviders =
			java.util.List.of();

	private MockMvc mockMvc() {
		AiController controller = new AiController(this.availability, provider(), provider(), provider(),
				this.chatMemory, this.chatMemoryRepository, this.sessionGate, mock(ConfirmGate.class), this.runStore,
				mock(RunRegistry.class), mock(CancellationRegistry.class), this.redisAvailability,
				mock(ThreadPoolTaskExecutor.class), new ObjectMapper(), new AiProperties(), mock(Environment.class),
				mock(com.example.configmgr.job.service.JobWatchdog.class));
		ReflectionTestUtils.setField(controller, "transcript", this.transcript);
		ReflectionTestUtils.setField(controller, "resultViewProviders", this.resultViewProviders);
		return MockMvcBuilders.standaloneSetup(controller).build();
	}

	@Test
	void readsTranscriptNotWorkingMemory() throws Exception {
		when(this.transcript.read("s-1")).thenReturn(new ConversationTranscriptStore.ReadResult(List.of(
				TranscriptEntries.user("run-a", 1L, 10L, "原话"),
				TranscriptEntries.assistant("run-a", 1L, 20L, "原文回答", null, null)), false));
		when(this.chatMemory.get("s-1")).thenReturn(List.of(new UserMessage("被压缩后的摘要")));

		mockMvc().perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].type").value("USER"))
			.andExpect(jsonPath("$.data.messages[0].text").value("原话"))
			.andExpect(jsonPath("$.data.messages[0].runId").value("run-a"))
			.andExpect(jsonPath("$.data.messages[1].text").value("原文回答"));
	}

	@Test
	void transcriptToolEntriesExposeToolCallsAndResponses() throws Exception {
		when(this.transcript.read("s-1")).thenReturn(new ConversationTranscriptStore.ReadResult(List.of(
				TranscriptEntries.user("run-a", 1L, 10L, "问题"),
				TranscriptEntries.assistant("run-a", 1L, 20L, "",
						List.of(new com.example.configmgr.ai.history.TranscriptEntry.ToolCallView("c-1",
								"function", "list_tasks", "{}")),
						java.util.Map.of("c-1", java.util.Map.of("status", "EXECUTED", "kind", "BACKEND"))),
				TranscriptEntries.tool("run-a", 1L, 30L, "c-1", "list_tasks", "BACKEND", "EXECUTED",
						"工具原文")), false));

		mockMvc().perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[1].type").value("ASSISTANT"))
			.andExpect(jsonPath("$.data.messages[1].toolCalls[0].id").value("c-1"))
			.andExpect(jsonPath("$.data.messages[1].toolCallStates.c-1.status").value("EXECUTED"))
			.andExpect(jsonPath("$.data.messages[2].type").value("TOOL"))
			.andExpect(jsonPath("$.data.messages[2].toolResponses[0].id").value("c-1"))
			.andExpect(jsonPath("$.data.messages[2].toolResponses[0].responseData").value("工具原文"));
	}

	@Test
	void transcriptToolEntriesKeepTruncationAndUserTextCode() throws Exception {
		when(this.transcript.read("s-1")).thenReturn(new ConversationTranscriptStore.ReadResult(List.of(
				new com.example.configmgr.ai.history.TranscriptEntry("run-a:tool:c-1", "tool", null, "run-a", 1L,
						30L, null, null, null, null, null, null, "c-1", "list_tasks", "BACKEND", "SKIPPED",
						"未执行：本轮工具调用步数已达上限", false, com.example.configmgr.common.ErrorCodes.TOOL_STEP_LIMIT,
						true, com.example.configmgr.ai.tool.TruncationContract.STRATEGY_HEAD, 900, 100)),
				false));

		mockMvc().perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].userTextCode")
				.value(com.example.configmgr.common.ErrorCodes.TOOL_STEP_LIMIT))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].responseData")
				.value("本轮工具调用次数已用完，这一步没有执行；AI 会用已有信息继续回答。"))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].truncated").value(true))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].noticeCode").value("model_view_truncated"))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].originalChars").value(900))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].returnedChars").value(100));
	}

	/** P2-1：分页先切 entries，只对本页做昂贵投影（resultView provider 只被调用本页次数）。 */
	@Test
	void onlyTheRequestedPageIsProjected() throws Exception {
		List<com.example.configmgr.ai.history.TranscriptEntry> entries = new java.util.ArrayList<>();
		for (int i = 0; i < 20; i++) {
			entries.add(TranscriptEntries.tool("run-a", 1L, 100L + i, "c-" + i, "list_tasks", "BACKEND",
					"EXECUTED", "[]"));
		}
		when(this.transcript.read("s-1")).thenReturn(new ConversationTranscriptStore.ReadResult(entries, false));
		java.util.concurrent.atomic.AtomicInteger builds = new java.util.concurrent.atomic.AtomicInteger();
		this.resultViewProviders = List.of(new com.example.configmgr.ai.resultview.ToolResultViewProvider() {
			@Override
			public boolean supports(String toolName) {
				return "list_tasks".equals(toolName);
			}

			@Override
			public com.example.configmgr.ai.resultview.ResultView build(String toolCallId, String toolName,
					String resultText) {
				builds.incrementAndGet();
				return null;
			}
		});

		mockMvc().perform(get("/api/ai/history/s-1").param("offset", "19").param("limit", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.total").value(20))
			.andExpect(jsonPath("$.data.count").value(1))
			.andExpect(jsonPath("$.data.hasMore").value(true));

		org.assertj.core.api.Assertions.assertThat(builds.get())
			.as("只投影本页（1 条），不得为 20 条全量构造 resultView")
			.isEqualTo(1);
	}

	@Test
	void fallsBackToMemoryWhenNoTranscript() throws Exception {
		when(this.transcript.read("s-1")).thenReturn(ConversationTranscriptStore.ReadResult.EMPTY);
		when(this.chatMemory.get("s-1")).thenReturn(List.of(new UserMessage("旧会话")));

		mockMvc().perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].text").value("旧会话"));
	}

	@Test
	void historyExposesSyntheticKindSoOnlyCompactionCanRenderAsCompacted() throws Exception {
		when(this.transcript.read("s-1")).thenReturn(ConversationTranscriptStore.ReadResult.EMPTY);
		UserMessage stepLimit = new UserMessage(
				com.example.configmgr.ai.history.SyntheticMessages.STEP_LIMIT_PREFIX + " 请收尾");
		UserMessage compaction = UserMessage.builder()
			.text(com.example.configmgr.ai.history.SyntheticMessages.SUMMARY_PLACEHOLDER)
			.metadata(java.util.Map.of(
					com.example.configmgr.ai.history.SyntheticMessages.COMPACTED_FROM, 2))
			.build();
		when(this.chatMemory.get("s-1")).thenReturn(List.of(stepLimit, compaction));

		mockMvc().perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].synthetic").value(true))
			.andExpect(jsonPath("$.data.messages[0].syntheticKind").value("step_limit"))
			.andExpect(jsonPath("$.data.messages[1].syntheticKind").value("compaction"));
	}

	@Test
	void historyReturnsCompactionEventsInOrder() throws Exception {
		when(this.transcript.read("s-1")).thenReturn(ConversationTranscriptStore.ReadResult.EMPTY);
		when(this.chatMemory.get("s-1")).thenReturn(List.of(new UserMessage("q")));
		when(this.runStore.events("s-1")).thenReturn(List.of(
				java.util.Map.of("eventId", "r:c2", "runId", "r", "atMs", 200L, "anchorToolCallId", "c-2"),
				java.util.Map.of("eventId", "r:c1", "runId", "r", "atMs", 100L, "anchorToolCallId", "c-1")));

		mockMvc().perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.events[0].eventId").value("r:c1"))
			.andExpect(jsonPath("$.data.events[0].anchorToolCallId").value("c-1"))
			.andExpect(jsonPath("$.data.events[1].eventId").value("r:c2"));
	}

	@Test
	void transcriptReadFailureIsVisibleNotFakeEmpty() throws Exception {
		when(this.transcript.read(anyString())).thenThrow(new IllegalStateException("Redis 不可用"));

		mockMvc().perform(get("/api/ai/history/s-1"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").exists());
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}

}
