package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.history.TranscriptEntries;
import com.example.configmgr.ai.history.TranscriptEntry;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.AiToolNames;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.common.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemRejectedTranscriptTest {

	private static final String RUN_ID = "run-system-rejected-transcript";

	@Test
	void terminalProjectionUsesRealSegmentsWithoutDuplicatingCorrectionFragments() {
		PendingToolCall rejected = pending("gf-1", AiToolNames.GENERATIVE_FORM, ErrorCodes.FORM_SLOTS_ALREADY_KNOWN);
		PendingToolCall create = pending("create-1", AiToolNames.CREATE_TASK, null);
		List<Message> history = List.of(
				assistant("前缀", "", "form-entry", toolCall("gf-1", AiToolNames.GENERATIVE_FORM)),
				assistant("后缀", "", "suffix-entry"),
				assistant("后缀", "", "create-entry", toolCall("create-1", AiToolNames.CREATE_TASK)),
				toolResponse("gf-1", AiToolNames.GENERATIVE_FORM, "rejected"),
				toolResponse("create-1", AiToolNames.CREATE_TASK, "waiting"),
				assistant("前缀后缀独立正文", "独立推理", "independent-entry"));
		InMemoryFrameStore frames = new InMemoryFrameStore();
		SseChatEmitter parked = emitter(frames);
		parked.textStart();
		parked.delta("前缀");
		parked.delta("后缀");
		SseChatEmitter out = emitter(frames);
		out.start("session-1");
		out.toolStart(create);
		emitText(out, "独立正文");
		out.reasoningDelta("独立推理");
		out.reasoningEnd();
		List<TranscriptEntry> entries = buildEntries(history, List.of(rejected, create),
				Map.of(CorrectionPrefixes.CREATE_TASK_CORRECTION_PREFIX_KEY, "前缀"));
		String createEntryId = TranscriptEntries.assistantEntryId(RUN_ID,
				List.of(new TranscriptEntry.ToolCallView("create-1", "function", AiToolNames.CREATE_TASK, "{}")));

		ReflectionTestUtils.invokeMethod(service(), "populateAssistantSegments", out, entries, true);

		assertThat(entries.stream().filter(entry -> "assistant".equals(entry.kind())))
				.extracting(TranscriptEntry::text).containsExactly("前缀后缀", "独立正文");
		TranscriptEntry projected = entries.stream().filter(entry -> "assistant".equals(entry.kind())
				&& !entry.toolCalls().isEmpty()).findFirst().orElseThrow();
		assertThat(projected.entryId()).isEqualTo(createEntryId);
		assertThat(projected.reasoning()).isEmpty();
		assertThat(projected.toolCalls()).extracting(TranscriptEntry.ToolCallView::id).containsExactly("create-1");
		assertThat(projected.segments()).extracting(TranscriptEntry.SegmentView::kind)
				.containsExactly("text", "tool");
		assertThat(entries.stream().filter(entry -> "tool".equals(entry.kind())))
				.extracting(TranscriptEntry::toolCallId).containsExactly("create-1");
		TranscriptEntry independent = entries.getLast();
		assertThat(independent.entryId()).endsWith("independent-entry");
		assertThat(independent.reasoning()).isEqualTo("独立推理");
		assertThat(independent.segments()).isNotEmpty();
	}

	@Test
	void claimedTrailingSegmentIsNotReusedForAnEarlierPlainEntry() {
		PendingToolCall create = pending("create-1", AiToolNames.CREATE_TASK, null);
		List<Message> history = List.of(
				assistant("前", "", "early-entry"),
				assistant("前后", "", "create-entry", toolCall("create-1", AiToolNames.CREATE_TASK)),
				toolResponse("create-1", AiToolNames.CREATE_TASK, "waiting"),
				assistant("终", "", "final-entry"));
		SseChatEmitter out = emitter();
		out.textStart();
		out.delta("前后");
		out.toolStart(create);
		emitText(out, "终");
		List<TranscriptEntry> entries = buildEntries(history, List.of(create));

		Object populated = ReflectionTestUtils.invokeMethod(service(), "populateAssistantSegments", out, entries, false);

		assertThat(populated).isNull();
	}

	@Test
	void unknownTrailingContentIsNotUsedForSingleCorrectionPlain() {
		PendingToolCall create = pending("create-1", AiToolNames.CREATE_TASK, null);
		List<Message> history = List.of(
				assistant("未知正文", "原推理", "early-entry"),
				assistant("前后", "", "create-entry", toolCall("create-1", AiToolNames.CREATE_TASK)),
				toolResponse("create-1", AiToolNames.CREATE_TASK, "waiting"));
		SseChatEmitter out = emitter();
		out.textStart();
		out.delta("前后");
		out.toolStart(create);
		emitText(out, "不同正文");
		out.reasoningDelta("不同推理");
		out.reasoningEnd();
		List<TranscriptEntry> entries = buildEntries(history, List.of(create));

		Object populated = ReflectionTestUtils.invokeMethod(service(), "populateAssistantSegments", out, entries, true);

		assertThat(populated).isNull();
	}

	@Test
	void claimedPlainContentIsNotRemovedWithoutCorrection() {
		PendingToolCall create = pending("create-1", AiToolNames.CREATE_TASK, null);
		List<Message> history = List.of(
				assistant("前后", "", "early-entry"),
				assistant("前后", "", "create-entry", toolCall("create-1", AiToolNames.CREATE_TASK)),
				toolResponse("create-1", AiToolNames.CREATE_TASK, "waiting"));
		SseChatEmitter out = emitter();
		out.textStart();
		out.delta("前后");
		out.toolStart(create);
		List<TranscriptEntry> entries = buildEntries(history, List.of(create));

		Object populated = ReflectionTestUtils.invokeMethod(service(), "populateAssistantSegments", out, entries, false);

		assertThat(populated).isNotNull();
		assertThat(entries.stream().filter(entry -> "assistant".equals(entry.kind()) && entry.toolCalls().isEmpty()))
				.singleElement().satisfies(entry -> assertThat(entry.segments()).isNotEmpty());
	}

	@Test
	void claimedContentDoesNotSpanDifferentEntries() {
		SseChatEmitter out = emitter();
		emitText(out, "前");
		emitText(out, "后");
		emitText(out, "独立");
		@SuppressWarnings("unchecked")
		List<Object> timeline = (List<Object>) ReflectionTestUtils.getField(out, "transcriptTimeline");
		ReflectionTestUtils.setField(timeline.get(0), "entryId", "entry-a");
		ReflectionTestUtils.setField(timeline.get(1), "entryId", "entry-a");
		ReflectionTestUtils.setField(timeline.get(2), "entryId", "entry-b");
		assertThat(out.isTranscriptContentClaimed("前后", "")).isTrue();
		assertThat(out.isTranscriptContentClaimed("独立", "")).isTrue();
		assertThat(out.isTranscriptContentClaimed("前", "")).isFalse();
		assertThat(out.isTranscriptContentClaimed("前后独立", "")).isFalse();
	}

	@Test
	void plainAssistantKeepsIndependentContent() {
		assertOrdinaryToolAssistantsKeepTheirOwnContent(0);
	}

	@Test
	void oneOrdinaryToolAssistantKeepsItsOwnContent() {
		assertOrdinaryToolAssistantsKeepTheirOwnContent(1);
	}

	@Test
	void multipleOrdinaryToolAssistantsKeepTheirOwnContent() {
		assertOrdinaryToolAssistantsKeepTheirOwnContent(2);
	}

	private void assertOrdinaryToolAssistantsKeepTheirOwnContent(int assistantCount) {
		List<PendingToolCall> pendings = new ArrayList<>();
		List<Message> history = new ArrayList<>();
		SseChatEmitter out = emitter();
		for (int i = 0; i < assistantCount; i++) {
			String id = "ordinary-" + i;
			PendingToolCall pending = pending(id, AiToolNames.LIST_CONFIG_DEFS, null);
			pendings.add(pending);
			history.add(assistant("正文" + i, "推理" + i, "entry-" + i, toolCall(id, AiToolNames.LIST_CONFIG_DEFS)));
			emitText(out, "正文" + i);
			out.reasoningDelta("推理" + i);
			out.reasoningEnd();
			out.toolStart(pending);
			history.add(toolResponse(id, AiToolNames.LIST_CONFIG_DEFS, "ok"));
		}
		if (assistantCount == 0) {
			history.add(assistant("独立正文", "独立推理", "plain-entry"));
			emitText(out, "独立正文");
			out.reasoningDelta("独立推理");
			out.reasoningEnd();
		}
		List<TranscriptEntry> entries = buildEntries(history, pendings);
		List<String> entryIds = entries.stream().map(TranscriptEntry::entryId).toList();

		ReflectionTestUtils.invokeMethod(service(), "populateAssistantSegments", out, entries, false);

		assertThat(entries.stream().filter(entry -> "assistant".equals(entry.kind())))
				.extracting(TranscriptEntry::text).containsExactlyElementsOf(assistantCount == 0
						? List.of("独立正文") : java.util.stream.IntStream.range(0, assistantCount)
						.mapToObj(i -> "正文" + i).toList());
		assertThat(entries.stream().filter(entry -> "assistant".equals(entry.kind())))
				.extracting(TranscriptEntry::reasoning).containsExactlyElementsOf(assistantCount == 0
						? List.of("独立推理") : java.util.stream.IntStream.range(0, assistantCount)
						.mapToObj(i -> "推理" + i).toList());
		assertThat(entries.stream().map(TranscriptEntry::entryId)).containsExactlyElementsOf(entryIds);
	}

	private static List<TranscriptEntry> buildEntries(List<Message> history, List<PendingToolCall> pendings) {
		return buildEntries(history, pendings, Map.of());
	}

	private static List<TranscriptEntry> buildEntries(List<Message> history, List<PendingToolCall> pendings,
			Map<String, Object> extra) {
		RunStore store = mock(RunStore.class);
		when(store.pendingsLenient(RUN_ID)).thenReturn(pendings);
		when(store.ledger(RUN_ID)).thenReturn(List.of());
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setCreatedAtMs(1L);
		snapshot.setContext(AiContext.of("export", "EXPORT", null, null, extra).toMap());
		return ReflectionTestUtils.invokeMethod(service(store), "buildTerminalEntries", RUN_ID, snapshot, history, true);
	}

	private static SseChatEmitter emitter() {
		return emitter(new InMemoryFrameStore());
	}

	private static SseChatEmitter emitter(InMemoryFrameStore frames) {
		SseChatEmitter out = new SseChatEmitter(RUN_ID, mock(RunStore.class), frames, new ObjectMapper(),
				Runnable::run, AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY,
				AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES,
				com.example.configmgr.common.sse.SseWriteBudget.direct(), null, null,
				new com.example.configmgr.ai.tool.ToolResultLimiter(new AiProperties()));
		ReflectionTestUtils.setField(out, "deliveryIdentity", new com.example.configmgr.ai.tenant.TenantUser(1L, 101L));
		return out;
	}

	private static ResilientChatService service() {
		return service(mock(RunStore.class));
	}

	private static ResilientChatService service(RunStore store) {
		AiProperties properties = new AiProperties();
		properties.getReasoning().setDisplayEnabled(true);
		return new ResilientChatService(mock(AiProviderRegistry.class), mock(ChatMemory.class),
				new MessageJsonCodec(new ObjectMapper()), store, mock(RunRegistry.class), mock(ToolRegistry.class),
				mock(ContextBuilder.class), mock(AiSessionService.class), properties, mock(CancellationRegistry.class),
				mock(ToolActivityBeacon.class), null);
	}

	private static AssistantMessage assistant(String text, String reasoning, String entryId,
			AssistantMessage.ToolCall... calls) {
		return AssistantMessage.builder().content(text).toolCalls(List.of(calls))
				.properties(Map.of(TranscriptEntries.TRANSCRIPT_TEXT_ID_KEY, entryId,
						ResilientChatService.REASONING_METADATA_KEY, reasoning))
				.build();
	}

	private static AssistantMessage.ToolCall toolCall(String id, String name) {
		return new AssistantMessage.ToolCall(id, "function", name, "{}");
	}

	private static ToolResponseMessage toolResponse(String id, String name, String text) {
		return ToolResponseMessage.builder()
				.responses(List.of(new ToolResponseMessage.ToolResponse(id, name, text))).build();
	}

	private static PendingToolCall pending(String id, String name, String code) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(id);
		pending.setName(name);
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setCode(code);
		pending.setStatus(code == null ? PendingToolCall.PENDING : PendingToolCall.REJECTED_ARGUMENTS);
		return pending;
	}

	private static void emitText(SseChatEmitter out, String text) {
		out.textStart();
		out.delta(text);
		out.textEnd(text.length());
	}

}
