package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.context.ContextStateStore;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.history.ConversationTranscriptStore;
import com.example.configmgr.ai.history.TranscriptEntries;
import com.example.configmgr.ai.history.TranscriptEntry;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.session.SessionGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B2：{@code /history} 的读点 —— transcript 读页优先且<b>只投影本页</b>；已提交压缩事实来自
 * {@link ContextStateStore}；读失败可见（503），不伪装成空历史。
 *
 * <h2>当前契约（与旧实现不同）</h2>
 * <ul>
 * <li>分页由 {@code readPage(sessionId, before, latest)} 承担（游标 {@code before} + 页大小
 * {@code latest}），控制器不再做 offset 切片，也不再回落 {@code chat:mem}；</li>
 * <li>压缩事件来自 {@code ContextStateStore#events}（不是 run 事件日志），按 {@code atMs,eventId}
 * 升序输出；</li>
 * <li>transcript 读异常 ⇒ 503（不得把"读不出来"当成"没有历史"）。</li>
 * </ul>
 */
class AiControllerHistoryTranscriptTest {

	private final ChatMemory chatMemory = mock(ChatMemory.class);

	private final ChatMemoryRepository chatMemoryRepository = mock(ChatMemoryRepository.class);

	private final SessionGate sessionGate = mock(SessionGate.class);

	private final RedisAvailability redisAvailability = mock(RedisAvailability.class);

	private final RunStore runStore = mock(RunStore.class);

	private final AiAvailability availability = mock(AiAvailability.class);

	private final ConversationTranscriptStore transcript = mock(ConversationTranscriptStore.class);

	private final ContextStateStore contextStateStore = mock(ContextStateStore.class);

	private List<com.example.configmgr.ai.resultview.ToolResultViewProvider> resultViewProviders = List.of();

	private MockMvc mockMvc() {
		AiController controller = new AiController(this.availability, provider(), provider(), provider(),
				this.chatMemory, this.chatMemoryRepository, this.sessionGate, mock(ConfirmGate.class), this.runStore,
				mock(RunRegistry.class), mock(CancellationRegistry.class), this.redisAvailability,
				mock(ThreadPoolTaskExecutor.class), new ObjectMapper(), new AiProperties(), mock(Environment.class),
				mock(com.example.configmgr.job.service.JobWatchdog.class));
		ReflectionTestUtils.setField(controller, "transcript", this.transcript);
		ReflectionTestUtils.setField(controller, "contextStateStore", this.contextStateStore);
		ReflectionTestUtils.setField(controller, "resultViewProviders", this.resultViewProviders);
		return MockMvcBuilders.standaloneSetup(controller).build();
	}

	private void stubPage(List<TranscriptEntry> entries, long total, String nextCursor, boolean hasMore) {
		when(this.transcript.readPage(anyString(), any(), any())).thenReturn(new ConversationTranscriptStore.ReadPage(
				entries, total, 7L, nextCursor, hasMore, false));
	}

	@Test
	void readsTranscriptPageNotWorkingMemory() throws Exception {
		stubPage(List.of(TranscriptEntries.user("run-a", 1L, 10L, "原话"),
				TranscriptEntries.assistant("run-a", 1L, 20L, "原文回答", null, null)), 2L, null, false);
		when(this.chatMemory.get("s-1")).thenReturn(List.of(
				new org.springframework.ai.chat.messages.UserMessage("被压缩后的摘要")));

		mockMvc().perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].type").value("USER"))
			.andExpect(jsonPath("$.data.messages[0].text").value("原话"))
			.andExpect(jsonPath("$.data.messages[0].runId").value("run-a"))
			.andExpect(jsonPath("$.data.messages[1].text").value("原文回答"))
			.andExpect(jsonPath("$.data.total").value(2))
			.andExpect(jsonPath("$.data.revision").value(7));
	}

	@Test
	void transcriptToolEntriesExposeToolCallsAndResponses() throws Exception {
		stubPage(List.of(TranscriptEntries.user("run-a", 1L, 10L, "问题"),
				TranscriptEntries.assistant("run-a", 1L, 20L, "",
						List.of(new TranscriptEntry.ToolCallView("c-1", "function", "list_tasks", "{}")),
						Map.of("c-1", Map.of("status", "EXECUTED", "kind", "BACKEND"))),
				TranscriptEntries.tool("run-a", 1L, 30L, "c-1", "list_tasks", "BACKEND", "EXECUTED", "工具原文")),
				3L, null, false);

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
		TranscriptEntry truncatedTool = new TranscriptEntry("run-a:tool:c-1", "tool", "run-a", 1L, 30L,
				null, null, null, null, null, null,
				"c-1", "list_tasks", "BACKEND", "SKIPPED",
				"未执行：本轮工具调用步数已达上限",
				com.example.configmgr.common.ErrorCodes.TOOL_STEP_LIMIT,
				true, com.example.configmgr.ai.tool.TruncationContract.STRATEGY_HEAD, 900, 100,
				null, null, null, null, null, null);
		stubPage(List.of(truncatedTool), 1L, null, false);

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

	/** 分页由读页口承担：只投影本页，resultView provider 只被调用本页次数。 */
	@Test
	void onlyTheRequestedPageIsProjected() throws Exception {
		stubPage(List.of(TranscriptEntries.tool("run-a", 1L, 100L, "c-19", "list_tasks", "BACKEND", "EXECUTED",
				"[]")), 20L, "c-19", true);
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

		mockMvc().perform(get("/api/ai/history/s-1").param("before", "19").param("latest", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.total").value(20))
			.andExpect(jsonPath("$.data.count").value(1))
			.andExpect(jsonPath("$.data.hasMore").value(true))
			.andExpect(jsonPath("$.data.nextCursor").value("c-19"));

		org.assertj.core.api.Assertions.assertThat(builds.get())
			.as("只投影本页（1 条），不得为 20 条全量构造 resultView").isEqualTo(1);
	}

	/** 空 transcript ⇒ 空历史（不再回落 chat:mem）。 */
	@Test
	void emptyTranscriptReturnsEmptyHistoryWithoutMemoryFallback() throws Exception {
		when(this.transcript.readPage(anyString(), any(), any()))
			.thenReturn(ConversationTranscriptStore.ReadPage.EMPTY);
		when(this.chatMemory.get("s-1")).thenReturn(List.of(
				new org.springframework.ai.chat.messages.UserMessage("旧会话")));

		mockMvc().perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.count").value(0))
			.andExpect(jsonPath("$.data.total").value(0))
			.andExpect(jsonPath("$.data.messages").isEmpty());
	}

	@Test
	void historyReturnsCompactionEventsInOrder() throws Exception {
		stubPage(List.of(TranscriptEntries.user("run-a", 1L, 10L, "q")), 1L, null, false);
		when(this.contextStateStore.events("s-1")).thenReturn(List.of(
				Map.of("eventId", "r:c2", "runId", "r", "atMs", 200L, "anchorToolCallId", "c-2"),
				Map.of("eventId", "r:c1", "runId", "r", "atMs", 100L, "anchorToolCallId", "c-1")));

		mockMvc().perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.events[0].eventId").value("r:c1"))
			.andExpect(jsonPath("$.data.events[0].anchorToolCallId").value("c-1"))
			.andExpect(jsonPath("$.data.events[1].eventId").value("r:c2"));
	}

	@Test
	void transcriptReadFailureIsVisibleNotFakeEmpty() throws Exception {
		when(this.transcript.readPage(anyString(), any(), any()))
			.thenThrow(new IllegalStateException("Redis 不可用"));

		mockMvc().perform(get("/api/ai/history/s-1"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").exists());
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}

}
