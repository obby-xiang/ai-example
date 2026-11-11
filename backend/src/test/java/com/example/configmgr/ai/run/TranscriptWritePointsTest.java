package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.history.ConversationTranscriptStore;
import com.example.configmgr.ai.history.TranscriptEntries;
import com.example.configmgr.ai.history.TranscriptEntry;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B2 写点接线：轮首 / 终态（成功、取消）/ 失败 / 挂起都必须真的把条目交给
 * {@link ConversationTranscriptStore}，且终态写点要补写同 entryId 的用户原文（P1）。
 *
 * <p>这些写点是 private 的（公开入口是整轮驱动），这里直接调用写点本身并断言它交给存储的条目，
 * 避免为了展示面再起一套模型流；断言语义与生产调用点逐条对应。
 */
class TranscriptWritePointsTest {

	private static final String RUN_ID = "run-tx-wiring";

	private static final String SESSION_ID = "s-tx-wiring";

	private static final long TENANT = 1L;

	private static final long USER = 101L;

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunStore store = mock(RunStore.class);

	private final ConversationTranscriptStore transcript = mock(ConversationTranscriptStore.class);

	private final AiProperties properties = new AiProperties();

	private ResilientChatService service;

	@BeforeEach
	void setUp() {
		this.service = new ResilientChatService(mock(AiProviderRegistry.class), mock(ChatMemory.class), this.codec,
				this.store, mock(RunRegistry.class), mock(ToolRegistry.class), mock(ContextBuilder.class),
				mock(AiSessionService.class), this.properties, mock(CancellationRegistry.class),
				mock(ToolActivityBeacon.class), mock(ScheduledExecutorService.class));
		ReflectionTestUtils.setField(this.service, "transcript", this.transcript);
		when(this.store.isWritePermitted(anyString(), eq("transcript"))).thenReturn(true);
		when(this.store.get(RUN_ID)).thenReturn(snapshot());
		// create-entry 起终态写点先读持久化页做 superseded 对账；默认返回完整空页，让 append 可发生。
		when(this.transcript.readPage(anyString(), anyLong(), anyLong(), any(), any()))
			.thenReturn(ConversationTranscriptStore.ReadPage.EMPTY);
	}

	@Test
	void turnStartWritesTheUserEntryWithStableEntryId() {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT, USER)) {
			ReflectionTestUtils.invokeMethod(this.service, "recordTranscriptUser", RUN_ID, SESSION_ID, "原话", 1000L);
		}

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<TranscriptEntry>> entries = ArgumentCaptor.forClass(List.class);
		verify(this.transcript).append(eq(SESSION_ID), eq(TENANT), eq(USER), entries.capture(), anyLong());
		assertThat(entries.getValue()).singleElement()
			.extracting(TranscriptEntry::entryId, TranscriptEntry::kind, TranscriptEntry::text)
			.containsExactly(RUN_ID + ":user", "user", "原话");
	}

	@Test
	void turnStartWithoutIdentitySkipsWrite() {
		ReflectionTestUtils.invokeMethod(this.service, "recordTranscriptUser", RUN_ID, SESSION_ID, "原话", 1000L);

		verify(this.transcript, never()).append(anyString(), anyLong(), anyLong(), any(), anyLong());
	}

	@Test
	void successTerminalBackfillsTheUserTextWithTheSameEntryId() {
		RunSnapshot snapshot = snapshot();
		when(this.store.get(RUN_ID)).thenReturn(snapshot);
		List<Message> history = List.of(new UserMessage("原话"), new AssistantMessage("答"));

		ReflectionTestUtils.invokeMethod(this.service, "isRecordTranscript", RUN_ID, SESSION_ID, history, true);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<TranscriptEntry>> entries = ArgumentCaptor.forClass(List.class);
		verify(this.transcript).append(eq(SESSION_ID), eq(TENANT), eq(USER), entries.capture(), anyLong());
		assertThat(entries.getValue()).extracting(TranscriptEntry::kind)
			.containsExactly("user", "assistant");
		assertThat(entries.getValue().get(0).entryId()).isEqualTo(RUN_ID + ":user");
		assertThat(entries.getValue().get(0).text()).isEqualTo("原话");
		assertThat(entries.getValue().get(0).turnStartedAtMs())
			.as("用户条目排序键早于建快照时刻（终态补写不排到工具卡之后）")
			.isEqualTo(snapshot.transcriptUserTurnStartedAtMs());
	}

	@Test
	void cancelTerminalKeepsTheStoppedAssistantAndTheUserText() {
		RunSnapshot snapshot = snapshot();
		when(this.store.get(RUN_ID)).thenReturn(snapshot);
		AssistantMessage stopped = AssistantMessage.builder().content("半截").build();
		List<Message> history = List.of(new UserMessage("原话"), stopped);

		ReflectionTestUtils.invokeMethod(this.service, "isRecordTranscript", RUN_ID, SESSION_ID, history, true);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<TranscriptEntry>> entries = ArgumentCaptor.forClass(List.class);
		verify(this.transcript).append(eq(SESSION_ID), eq(TENANT), eq(USER), entries.capture(), anyLong());
		assertThat(entries.getValue()).extracting(TranscriptEntry::text).containsExactly("原话", "半截");
	}

	/**
	 * 失败终态：用户原文必须补写；<b>可见正文</b>的历史 assistant 也保留（当前契约：
	 * {@code includeLastAssistant} 只决定"无正文/无失败事实的空 assistant"是否写入，不裁可见历史）。
	 */
	@Test
	void failureTerminalBackfillsTheUserTextAndKeepsVisibleHistory() {
		RunSnapshot snapshot = snapshot();
		when(this.store.get(RUN_ID)).thenReturn(snapshot);

		ReflectionTestUtils.invokeMethod(this.service, "isRecordTranscript", RUN_ID, SESSION_ID,
				List.of(new UserMessage("原话"), new AssistantMessage("上一轮终稿")), false);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<TranscriptEntry>> entries = ArgumentCaptor.forClass(List.class);
		verify(this.transcript).append(eq(SESSION_ID), eq(TENANT), eq(USER), entries.capture(), anyLong());
		assertThat(entries.getValue()).extracting(TranscriptEntry::kind, TranscriptEntry::text)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("user", "原话"),
					org.assertj.core.groups.Tuple.tuple("assistant", "上一轮终稿"));
	}

	@Test
	void failureWritePointReadsTheSnapshotHistory() {
		RunSnapshot snapshot = snapshot();
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("原话"))));
		when(this.store.get(RUN_ID)).thenReturn(snapshot);

		ReflectionTestUtils.invokeMethod(this.service, "recordTranscriptFromSnapshot", RUN_ID, SESSION_ID);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<TranscriptEntry>> entries = ArgumentCaptor.forClass(List.class);
		verify(this.transcript).append(eq(SESSION_ID), eq(TENANT), eq(USER), entries.capture(), anyLong());
		assertThat(entries.getValue()).extracting(TranscriptEntry::text).containsExactly("原话");
	}

	@Test
	void terminalWriteKeepsOnlyTheToolOwnerForASharedTextSegment() {
		TranscriptEntry plain = assistantWithTextSegment(RUN_ID, "同一正文", null, 27L);
		TranscriptEntry tool = assistantWithTextSegment(RUN_ID, "同一正文", "create-1", 27L);
		when(this.transcript.readPage(SESSION_ID, TENANT, USER, null, 500))
			.thenReturn(ConversationTranscriptStore.ReadPage.EMPTY);
		when(this.transcript.append(eq(SESSION_ID), eq(TENANT), eq(USER), anyList(), eq(1L))).thenReturn(true);

		ReflectionTestUtils.invokeMethod(this.service, "appendTerminalEntries", RUN_ID, SESSION_ID, snapshot(),
				List.of(plain, tool), null, false, false);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<TranscriptEntry>> entries = ArgumentCaptor.forClass(List.class);
		verify(this.transcript).append(eq(SESSION_ID), eq(TENANT), eq(USER), entries.capture(), eq(1L));
		assertThat(entries.getValue()).extracting(TranscriptEntry::entryId)
			.containsExactly(RUN_ID + ":user", tool.entryId());
		assertThat(entries.getValue().get(0).kind()).isEqualTo("user");
		assertThat(entries.getValue().get(0).text()).isEqualTo("原话");
	}

	@Test
	void terminalWriteRemovesOnlyTheSupersededPersistedPlainEntryBeforeAppending() {
		TranscriptEntry oldPlain = assistantWithTextSegment(RUN_ID, "同一正文", null, 27L);
		TranscriptEntry tool = assistantWithTextSegment(RUN_ID, "同一正文", "create-1", 27L);
		when(this.transcript.readPage(SESSION_ID, TENANT, USER, null, 500)).thenReturn(
				new ConversationTranscriptStore.ReadPage(List.of(oldPlain, tool), 2L, 1L, null, false, false));
		when(this.transcript.remove(SESSION_ID, TENANT, USER, java.util.Set.of(oldPlain.entryId()), 1L)).thenReturn(true);
		when(this.transcript.append(eq(SESSION_ID), eq(TENANT), eq(USER), anyList(), eq(1L))).thenReturn(true);

		ReflectionTestUtils.invokeMethod(this.service, "appendTerminalEntries", RUN_ID, SESSION_ID, snapshot(),
				List.of(tool), null, false, false);

		org.mockito.InOrder order = org.mockito.Mockito.inOrder(this.transcript);
		order.verify(this.transcript).remove(SESSION_ID, TENANT, USER, java.util.Set.of(oldPlain.entryId()), 1L);
		order.verify(this.transcript).append(eq(SESSION_ID), eq(TENANT), eq(USER), anyList(), eq(1L));
	}

	@Test
	void terminalWriteFindsSupersededPlainAfterTheFirstFiveHundredEntries() {
		TranscriptEntry oldPlain = assistantWithTextSegment(RUN_ID, "同一正文", null, 27L);
		TranscriptEntry tool = assistantWithTextSegment(RUN_ID, "同一正文", "create-1", 27L);
		List<TranscriptEntry> firstPage = new ArrayList<>();
		for (int i = 0; i < 500; i++) {
			firstPage.add(TranscriptEntries.user("run-old-" + i, i, i, "旧问题" + i));
		}
		when(this.transcript.readPage(SESSION_ID, TENANT, USER, null, 500)).thenReturn(
				new ConversationTranscriptStore.ReadPage(firstPage, 501L, 1L, "500", true, false));
		when(this.transcript.readPage(SESSION_ID, TENANT, USER, 500L, 500)).thenReturn(
				new ConversationTranscriptStore.ReadPage(List.of(oldPlain, tool), 501L, 2L, null, false, false));
		when(this.transcript.remove(SESSION_ID, TENANT, USER, java.util.Set.of(oldPlain.entryId()), 1L)).thenReturn(true);
		when(this.transcript.append(eq(SESSION_ID), eq(TENANT), eq(USER), anyList(), eq(1L))).thenReturn(true);

		ReflectionTestUtils.invokeMethod(this.service, "appendTerminalEntries", RUN_ID, SESSION_ID, snapshot(),
				List.of(tool), null, false, false);

		verify(this.transcript).remove(SESSION_ID, TENANT, USER, java.util.Set.of(oldPlain.entryId()), 1L);
	}

	@Test
	void terminalWriteKeepsPlainWithSegmentsNotOwnedByTheTool() {
		TranscriptEntry plain = assistantWithTextSegment(RUN_ID, "共享", null, 27L)
			.withContent("共享独立", "独立推理")
			.withSegments(List.of(
					new TranscriptEntry.SegmentView("text", "共享", null, 27L),
					new TranscriptEntry.SegmentView("text", "独立", null, 28L),
					new TranscriptEntry.SegmentView("reasoning", "独立推理", null, 29L)));
		TranscriptEntry tool = assistantWithTextSegment(RUN_ID, "共享", "create-1", 27L);
		when(this.transcript.readPage(SESSION_ID, TENANT, USER, null, 500)).thenReturn(
				new ConversationTranscriptStore.ReadPage(List.of(plain, tool), 2L, 1L, null, false, false));
		when(this.transcript.append(eq(SESSION_ID), eq(TENANT), eq(USER), anyList(), eq(1L))).thenReturn(true);

		ReflectionTestUtils.invokeMethod(this.service, "appendTerminalEntries", RUN_ID, SESSION_ID, snapshot(),
				List.of(plain, tool), null, false, false);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<TranscriptEntry>> entries = ArgumentCaptor.forClass(List.class);
		verify(this.transcript).append(eq(SESSION_ID), eq(TENANT), eq(USER), entries.capture(), eq(1L));
		assertThat(entries.getValue()).extracting(TranscriptEntry::entryId)
			.containsExactly(RUN_ID + ":user", plain.entryId(), tool.entryId());
		verify(this.transcript, never()).remove(anyString(), anyLong(), anyLong(), any(), anyLong());
	}

	private static TranscriptEntry assistantWithTextSegment(String runId, String text, String toolCallId, long seq) {
		List<TranscriptEntry.ToolCallView> calls = toolCallId == null ? List.of()
				: List.of(new TranscriptEntry.ToolCallView(toolCallId, "function", "create_task", "{}"));
		return TranscriptEntries.assistant(runId, 0L, 1L, text, calls, null)
			.withSegments(List.of(new TranscriptEntry.SegmentView("text", text, null, seq)));
	}

	@Test
	void suspendWritesThePendingToolCardToo() {
		SpToolCallingManager manager = new SpToolCallingManager(this.store, mock(RunRegistry.class),
				mock(ConfirmGate.class), mock(ToolRegistry.class), this.codec, mock(ToolActivityBeacon.class),
				mock(CancellationRegistry.class), new ToolResultLimiter(this.properties), List.of());
		manager.setConversationTranscriptStore(this.transcript);
		RunSnapshot snapshot = snapshot();
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("c-1");
		pending.setName("list_tasks");
		pending.setKind(PendingToolCall.KIND_BACKEND);
		pending.setStatus(PendingToolCall.PENDING);
		AssistantMessage assistant = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("c-1", "function", "list_tasks", "{}")))
			.build();

		ReflectionTestUtils.invokeMethod(manager, "recordPendingTranscript", RUN_ID, snapshot,
				new Prompt(List.of(new UserMessage("原话"))), assistant, List.of(pending));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<TranscriptEntry>> entries = ArgumentCaptor.forClass(List.class);
		verify(this.transcript).append(eq(SESSION_ID), eq(TENANT), eq(USER), entries.capture(), anyLong());
		assertThat(entries.getValue()).extracting(TranscriptEntry::kind)
			.containsExactly("assistant", "tool");
		assertThat(entries.getValue().get(1).toolCallId()).isEqualTo("c-1");
	}

	private RunSnapshot snapshot() {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(RUN_ID);
		snapshot.setSessionId(SESSION_ID);
		snapshot.setTenantId(TENANT);
		snapshot.setUserId(USER);
		snapshot.setCreatedAtMs(500L);
		// 终态补写用户原文的当前判据：快照里必须有请求原文与轮首时刻。
		snapshot.setRequestText("原话");
		snapshot.setTurnStartedAtMs(600L);
		snapshot.setRunEpoch(1L);
		return snapshot;
	}

}
