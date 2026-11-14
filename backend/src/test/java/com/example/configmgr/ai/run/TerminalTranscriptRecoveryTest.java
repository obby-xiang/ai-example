package com.example.configmgr.ai.run;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.history.ConversationTranscriptStore;
import com.example.configmgr.ai.history.TranscriptEntries;
import com.example.configmgr.ai.history.TranscriptEntry;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.session.SessionPurgeGuard;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.AiToolNames;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.common.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TerminalTranscriptRecoveryTest {

    private static final String RUN_ID = "run-transcript-recovery";

    private RunStore store;

    private RunRegistry registry;

    private ConversationTranscriptStore transcript;

    private ResilientChatService chatService;

    private InMemoryFrameStore frames;

    private List<TranscriptEntry> persisted;

    private int appendCalls;

    private int removeCalls;

    private final Logger recoveryLogger = (Logger) org.slf4j.LoggerFactory.getLogger(ResilientChatService.class);

    private final Logger emitterLogger = (Logger) org.slf4j.LoggerFactory.getLogger(SseChatEmitter.class);

    private final ListAppender<ILoggingEvent> recoveryLogs = new ListAppender<>();

    @BeforeEach
    void setUp() {
        this.recoveryLogs.start();
        this.recoveryLogger.addAppender(this.recoveryLogs);
        this.emitterLogger.addAppender(this.recoveryLogs);
        this.store = mock(RunStore.class);
        this.registry = mock(RunRegistry.class);
        this.transcript = mock(ConversationTranscriptStore.class);
        this.frames = new InMemoryFrameStore();
        this.persisted = new ArrayList<>();
        this.appendCalls = 0;
        this.removeCalls = 0;
        when(this.store.pendingsLenient(anyString())).thenReturn(List.of());
        when(this.store.pendings(anyString())).thenReturn(List.of());
        when(this.store.ledger(anyString())).thenReturn(List.of());
        when(this.store.isWritePermitted(anyString(), anyString())).thenReturn(true);
        when(this.store.get(anyString())).thenAnswer(invocation -> snapshot(List.of(new UserMessage("问")), ""));
        when(this.store.leaseService()).thenReturn(null);
        when(this.transcript.append(anyString(), anyLong(), anyLong(), any(), anyLong())).thenAnswer(invocation -> {
            this.appendCalls++;
            this.persisted.addAll(invocation.getArgument(3));
            return true;
        });
        when(this.transcript.remove(anyString(), anyLong(), anyLong(), any(), anyLong())).thenAnswer(invocation -> {
            this.removeCalls++;
            java.util.Set<String> ids = new LinkedHashSet<>(invocation.getArgument(3));
            this.persisted.removeIf(entry -> ids.contains(entry.entryId()));
            return true;
        });
        when(this.transcript.readPage(anyString(), anyLong(), anyLong(), any(), any())).thenAnswer(invocation ->
                new ConversationTranscriptStore.ReadPage(List.copyOf(this.persisted), this.persisted.size(), 1L,
                        null, false, false));
        this.chatService = service(this.store, this.registry);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        this.recoveryLogger.detachAppender(this.recoveryLogs);
        this.emitterLogger.detachAppender(this.recoveryLogs);
    }

    @Test
    void resumeEndpointRecoversPlainText() {
        SseChatEmitter out = emitter();
        emitText(out, "正文");
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");
        RunSnapshot snapshot = snapshot(List.of(new UserMessage("问"), assistant("正文", "plain")), "正文");
        when(this.store.get(RUN_ID)).thenReturn(snapshot);
        ResumeService resumeService = new ResumeService(this.store, this.registry,
                new MessageJsonCodec(new ObjectMapper()), this.chatService, null, null, null, new AiProperties(),
                mock(CancellationRegistry.class), null);

        ResumeService.ResumeResult result;
        try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
            result = resumeService.resume(RUN_ID);
        }

        assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ALREADY_DONE);
        assertThat(result.body()).containsEntry("resumed", true).containsEntry("historyComplete", true);
        assertThat(this.persisted).extracting(TranscriptEntry::text).contains("正文");
    }

    @Test
    void plainTextRecoverySucceedsWithLiveEmitter() {
        SseChatEmitter out = emitter();
        emitText(out, "正文");
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");

        Object result = recover(snapshot(List.of(new UserMessage("问"), assistant("正文", "plain")), "正文"));

        assertThat(historyComplete(result)).isTrue();
        assertThat(this.appendCalls).isEqualTo(1);
        assertThat(this.persisted).extracting(TranscriptEntry::text).contains("正文");
    }

    @Test
    void healthyRecoveryClearsInheritedDegradedMetadata() {
        List<Message> history = List.of(new UserMessage("问"), assistant("正文", "plain"));
        RunSnapshot snapshot = snapshot(history, "正文");
        SseChatEmitter out = emitter();
        emitText(out, "正文");
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");
        List<TranscriptEntry> built = ReflectionTestUtils.invokeMethod(this.chatService, "buildTerminalEntries",
                RUN_ID, snapshot, history, true);
        TranscriptEntry degraded = built.getFirst().withOutcome(RunSnapshot.DONE, RUN_ID, false,
                ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");
        this.persisted.add(degraded);
        org.mockito.Mockito.doAnswer(invocation -> {
            this.appendCalls++;
            List<TranscriptEntry> incoming = invocation.getArgument(3);
            for (TranscriptEntry newer : incoming) {
                TranscriptEntry merged = newer;
                for (TranscriptEntry previous : this.persisted) {
                    if (newer.entryId().equals(previous.entryId())) {
                        merged = newer.withOutcome(newer.outcome(),
                                newer.errorCardKey() == null ? previous.errorCardKey() : newer.errorCardKey(),
                                newer.unconfirmed() == null ? previous.unconfirmed() : newer.unconfirmed(),
                                newer.errorCode() == null ? previous.errorCode() : newer.errorCode(),
                                newer.errorMessage() == null ? previous.errorMessage() : newer.errorMessage());
                    }
                }
                TranscriptEntry persistedEntry = merged;
                this.persisted.removeIf(entry -> entry.entryId().equals(persistedEntry.entryId()));
                this.persisted.add(persistedEntry);
            }
            return true;
        }).when(this.transcript).append(anyString(), anyLong(), anyLong(), any(), anyLong());

        Object result = recover(snapshot);

        assertThat(historyComplete(result)).isTrue();
        assertThat(this.persisted).filteredOn(entry -> entry.entryId().equals(RUN_ID + ":assistant:text-plain"))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.outcome()).isEqualTo(RunSnapshot.DONE);
                    assertThat(entry.errorCode()).isNull();
                    assertThat(entry.errorMessage()).isNull();
                    assertThat(entry.errorCardKey()).isNull();
                    assertThat(entry.unconfirmed()).isFalse();
                });
    }

    @Test
    void plainTextRecoverySucceedsWithRecreatedEmitter() {
        SseChatEmitter out = emitter();
        emitText(out, "正文");
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");
        when(this.registry.find(RUN_ID)).thenReturn(null);

        Object result = recover(snapshot(List.of(new UserMessage("问"), assistant("正文", "plain")), "正文"));

        assertThat(historyComplete(result)).isTrue();
        assertThat(this.persisted).extracting(TranscriptEntry::text).contains("正文");
    }

    @Test
    void hiddenReasoningDoesNotBlockToolRecoveryAndStaysPersisted() {
        PendingToolCall pending = pending("tool-1");
        when(this.store.pendingsLenient(RUN_ID)).thenReturn(List.of(pending));
        when(this.store.pendings(RUN_ID)).thenReturn(List.of(pending));
        AssistantMessage tools = AssistantMessage.builder().content("")
                .toolCalls(List.of(toolCall("tool-1")))
                .properties(Map.of(ResilientChatService.REASONING_METADATA_KEY, "隐藏推理"))
                .build();
        SseChatEmitter out = emitter();
        out.toolStart(pending);
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");
        AiProperties hidden = new AiProperties();
        hidden.getReasoning().setDisplayEnabled(false);
        ReflectionTestUtils.setField(this.chatService, "properties", hidden);

        Object result = recover(snapshot(List.of(new UserMessage("问"), tools, response("tool-1")), ""));

        assertThat(historyComplete(result)).isTrue();
        TranscriptEntry recovered = this.persisted.stream()
                .filter(entry -> "assistant".equals(entry.kind()) && !entry.toolCalls().isEmpty())
                .findFirst().orElseThrow();
        assertThat(recovered.reasoning()).isEqualTo("隐藏推理");
        assertThat(recovered.segments()).noneMatch(segment -> "reasoning".equals(segment.kind()));
    }

    @Test
    void lateReasoningAndMultipleToolsRecoverPairingAndSegmentOrder() {
        PendingToolCall first = pending("tool-1");
        PendingToolCall second = pending("tool-2");
        when(this.store.pendingsLenient(RUN_ID)).thenReturn(List.of(first, second));
        when(this.store.pendings(RUN_ID)).thenReturn(List.of(first, second));
        AssistantMessage tools = AssistantMessage.builder().content("")
                .toolCalls(List.of(toolCall("tool-1"), toolCall("tool-2")))
                .properties(Map.of(ResilientChatService.REASONING_METADATA_KEY, "迟到推理",
                        TranscriptEntries.TRANSCRIPT_TEXT_ID_KEY, "tools-entry"))
                .build();
        SseChatEmitter out = emitter();
        out.toolStart(first);
        out.toolStart(second);
        out.reasoningDelta("迟到推理");
        out.reasoningEnd();
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");

        Object result = recover(snapshot(List.of(new UserMessage("问"), tools,
                response("tool-1"), response("tool-2")), ""));

        assertThat(historyComplete(result)).isTrue();
        assertThat(this.persisted).filteredOn(entry -> "tool".equals(entry.kind())).hasSize(2);
        TranscriptEntry assistant = this.persisted.stream()
                .filter(entry -> "assistant".equals(entry.kind()) && entry.toolCalls().size() == 2)
                .findFirst().orElseThrow();
        assertThat(assistant.reasoning()).isEqualTo("迟到推理");
        assertThat(assistant.segments()).extracting(TranscriptEntry.SegmentView::seq).isSorted();
        assertThat(this.persisted.stream().filter(entry -> "tool".equals(entry.kind()))
                .allMatch(entry -> !entry.segments().isEmpty())).isTrue();
    }

    @Test
    void correctionFragmentsKeepTheirOwnedTimeline() {
        AssistantMessage first = assistant("前".repeat(149), "first");
        AssistantMessage second = assistant("中".repeat(49), "second");
        AssistantMessage third = assistant("独".repeat(392), "third");
        SseChatEmitter out = emitter();
        emitText(out, "前".repeat(149));
        emitText(out, "中".repeat(49));
        emitText(out, "独".repeat(392));
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");
        RunSnapshot snapshot = snapshot(List.of(new UserMessage("问"), first, second, third),
                "前".repeat(149) + "中".repeat(49) + "独".repeat(392));
        snapshot.setContext(AiContext.of("export", "EXPORT", null, null,
                Map.of(CorrectionPrefixes.EXPORT_CORRECTION_PREFIX_KEY, "前")).toMap());

        Object result = recover(snapshot);

        assertThat(this.persisted).extracting(TranscriptEntry::text)
                .contains("前".repeat(149), "中".repeat(49), "独".repeat(392));
        assertThat(historyComplete(result)).isTrue();
    }

    @Test
    void toolCandidateDoesNotClaimFollowingPlainAssistant() {
        PendingToolCall pending = pending("tool-1");
        when(this.store.pendingsLenient(RUN_ID)).thenReturn(List.of(pending));
        when(this.store.pendings(RUN_ID)).thenReturn(List.of(pending));
        AssistantMessage tools = AssistantMessage.builder().content("工具正文")
                .toolCalls(List.of(toolCall("tool-1"))).build();
        AssistantMessage plain = assistant("后续正文", "following-plain");
        SseChatEmitter out = emitter();
        emitText(out, "工具正文");
        out.toolStart(pending);
        emitText(out, "后续正文");
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");

        Object result = recover(snapshot(List.of(new UserMessage("问"), tools, response("tool-1"), plain),
                "工具正文后续正文"));

        assertThat(historyComplete(result)).isTrue();
        assertThat(this.persisted).filteredOn(entry -> "assistant".equals(entry.kind()))
                .extracting(TranscriptEntry::text).contains("工具正文", "后续正文");
        TranscriptEntry recoveredTool = this.persisted.stream()
                .filter(entry -> "assistant".equals(entry.kind()) && !entry.toolCalls().isEmpty())
                .findFirst().orElseThrow();
        assertThat(recoveredTool.segments()).allMatch(segment -> segment.text() == null
                || !"后续正文".equals(segment.text()));
        assertThat(this.persisted).filteredOn(entry -> "tool".equals(entry.kind())).hasSize(1);
    }

    @Test
    void followingReasoningOnlyIsNotClaimedByToolWithoutCorrection() {
        Object result = recoverFollowingAssistant("", "后续思考", false);

        assertFollowingAssistantIsIndependent(result, "", "后续思考");
    }

    @Test
    void followingReasoningOnlyIsNotClaimedByToolWithCorrection() {
        Object result = recoverFollowingAssistant("", "后续思考", true);

        assertFollowingAssistantIsIndependent(result, "", "后续思考");
    }

    @Test
    void followingReasoningAndTextAreNotClaimedByToolWithoutCorrection() {
        Object result = recoverFollowingAssistant("后续正文", "后续思考", false);

        assertFollowingAssistantIsIndependent(result, "后续正文", "后续思考");
    }

    @Test
    void followingReasoningAndTextAreNotClaimedByToolWithCorrection() {
        Object result = recoverFollowingAssistant("后续正文", "后续思考", true);

        assertFollowingAssistantIsIndependent(result, "后续正文", "后续思考");
    }

    @Test
    void toolAssistantRecoversWhenSseContainsCorrectionPrefixMissingFromMessageJson() {
        PendingToolCall pending = pending("tool-1");
        when(this.store.pendingsLenient(RUN_ID)).thenReturn(List.of(pending));
        when(this.store.pendings(RUN_ID)).thenReturn(List.of(pending));
        AssistantMessage tools = AssistantMessage.builder().content("后缀")
                .toolCalls(List.of(toolCall("tool-1"))).build();
        SseChatEmitter out = emitter();
        emitText(out, "前缀后缀");
        out.toolStart(pending);
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");
        RunSnapshot snapshot = snapshot(List.of(new UserMessage("问"), tools, response("tool-1")), "前缀后缀");
        snapshot.setContext(AiContext.of("export", "EXPORT", null, null,
                Map.of(CorrectionPrefixes.EXPORT_CORRECTION_PREFIX_KEY, "前缀")).toMap());

        Object result = recover(snapshot);

        assertThat(historyComplete(result)).isTrue();
        assertThat(this.persisted).filteredOn(entry -> "assistant".equals(entry.kind()))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.text()).isEqualTo("前缀后缀");
                    assertThat(entry.toolCalls()).hasSize(1);
                });
    }

    @Test
    void toolCandidateBridgesTextAcrossOrdinaryToolWithoutClaimingIt() {
        PendingToolCall host = pending("host-1");
        when(this.store.pendingsLenient(RUN_ID)).thenReturn(List.of(host));
        when(this.store.pendings(RUN_ID)).thenReturn(List.of(host));
        AssistantMessage ordinary = AssistantMessage.builder().content("")
                .toolCalls(List.of(toolCall("ordinary-1"))).build();
        AssistantMessage tools = AssistantMessage.builder().content("前段后段")
                .toolCalls(List.of(toolCall("host-1"))).build();
        SseChatEmitter out = emitter();
        emitText(out, "前段");
        out.toolStart(pending("ordinary-1"));
        emitText(out, "后段");
        out.toolStart(host);
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");

        Object result = recover(snapshot(List.of(new UserMessage("问"), ordinary, response("ordinary-1"),
                tools, response("host-1")), "前段后段"));

        assertThat(historyComplete(result)).isTrue();
        assertThat(this.persisted).filteredOn(entry -> "assistant".equals(entry.kind()))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.text()).isEqualTo("前段后段");
                    assertThat(entry.toolCalls()).extracting(TranscriptEntry.ToolCallView::id)
                            .containsExactly("host-1");
                    assertThat(entry.segments()).filteredOn(segment -> "text".equals(segment.kind()))
                            .extracting(TranscriptEntry.SegmentView::text).containsExactly("前段", "后段");
                    assertThat(entry.segments()).filteredOn(segment -> "tool".equals(segment.kind()))
                            .extracting(TranscriptEntry.SegmentView::toolCallId).containsExactly("host-1");
                });
        assertThat(this.persisted).filteredOn(entry -> "tool".equals(entry.kind()))
                .extracting(TranscriptEntry::toolCallId).containsExactly("host-1");
    }

    @Test
    void toolCandidateBridgesOrderedTextAcrossMultipleOrdinaryTools() {
        PendingToolCall host = pending("host-1");
        when(this.store.pendingsLenient(RUN_ID)).thenReturn(List.of(host));
        when(this.store.pendings(RUN_ID)).thenReturn(List.of(host));
        AssistantMessage first = AssistantMessage.builder().content("")
                .toolCalls(List.of(toolCall("ordinary-1"))).build();
        AssistantMessage second = AssistantMessage.builder().content("")
                .toolCalls(List.of(toolCall("ordinary-2"))).build();
        AssistantMessage tools = AssistantMessage.builder().content("甲乙丙")
                .toolCalls(List.of(toolCall("host-1"))).build();
        SseChatEmitter out = emitter();
        emitText(out, "甲");
        out.toolStart(pending("ordinary-1"));
        emitText(out, "乙");
        out.toolStart(pending("ordinary-2"));
        emitText(out, "丙");
        out.toolStart(host);
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");

        Object result = recover(snapshot(List.of(new UserMessage("问"), first, response("ordinary-1"),
                second, response("ordinary-2"), tools, response("host-1")), "甲乙丙"));

        assertThat(historyComplete(result)).isTrue();
        TranscriptEntry recovered = this.persisted.stream()
                .filter(entry -> "assistant".equals(entry.kind())).findFirst().orElseThrow();
        assertThat(recovered.text()).isEqualTo("甲乙丙");
        assertThat(recovered.segments()).filteredOn(segment -> "text".equals(segment.kind()))
                .extracting(TranscriptEntry.SegmentView::text).containsExactly("甲", "乙", "丙");
        assertThat(recovered.segments()).filteredOn(segment -> "tool".equals(segment.kind()))
                .extracting(TranscriptEntry.SegmentView::toolCallId).containsExactly("host-1");
    }

    @Test
    void bridgedToolCandidateDoesNotClaimFollowingPlainAssistant() {
        PendingToolCall host = pending("host-1");
        when(this.store.pendingsLenient(RUN_ID)).thenReturn(List.of(host));
        when(this.store.pendings(RUN_ID)).thenReturn(List.of(host));
        AssistantMessage ordinary = AssistantMessage.builder().content("")
                .toolCalls(List.of(toolCall("ordinary-1"))).build();
        AssistantMessage tools = AssistantMessage.builder().content("宿主正文")
                .toolCalls(List.of(toolCall("host-1"))).build();
        AssistantMessage plain = assistant("后续正文", "following-plain");
        SseChatEmitter out = emitter();
        emitText(out, "宿主");
        out.toolStart(pending("ordinary-1"));
        emitText(out, "正文");
        out.toolStart(host);
        emitText(out, "后续正文");
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");

        Object result = recover(snapshot(List.of(new UserMessage("问"), ordinary, response("ordinary-1"),
                tools, response("host-1"), plain), "宿主正文后续正文"));

        assertThat(historyComplete(result)).isTrue();
        assertThat(this.persisted).filteredOn(entry -> "assistant".equals(entry.kind()))
                .extracting(TranscriptEntry::text).containsExactly("宿主正文", "后续正文");
        TranscriptEntry recovered = this.persisted.stream()
                .filter(entry -> "assistant".equals(entry.kind()) && !entry.toolCalls().isEmpty())
                .findFirst().orElseThrow();
        assertThat(recovered.segments()).filteredOn(segment -> "text".equals(segment.kind()))
                .extracting(TranscriptEntry.SegmentView::text).containsExactly("宿主", "正文");
    }

    @Test
    void toolCandidateDoesNotBridgeIntoClaimedAdjacentEntry() {
        PendingToolCall host = pending("host-1");
        when(this.store.pendingsLenient(RUN_ID)).thenReturn(List.of(host));
        when(this.store.pendings(RUN_ID)).thenReturn(List.of(host));
        AssistantMessage previous = assistant("已认领", "previous");
        AssistantMessage ordinary = AssistantMessage.builder().content("")
                .toolCalls(List.of(toolCall("ordinary-1"))).build();
        AssistantMessage tools = AssistantMessage.builder().content("已认领未归属当前")
                .toolCalls(List.of(toolCall("host-1"))).build();
        SseChatEmitter out = emitter();
        emitText(out, "已认领");
        assertThat(out.transcriptSegments(RUN_ID + ":assistant:text-previous", List.of(), "已认领", ""))
                .isNotEmpty();
        emitText(out, "未归属");
        out.toolStart(pending("ordinary-1"));
        emitText(out, "当前");
        out.toolStart(host);
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");

        Object result = recover(snapshot(List.of(new UserMessage("问"), previous, ordinary, response("ordinary-1"),
                tools, response("host-1")), "已认领未归属当前"));

        assertThat(historyComplete(result)).isFalse();
        assertThat(this.appendCalls).isZero();
        assertThat(this.removeCalls).isZero();
    }

    @Test
    void archivedClaimSurvivesLocalOwnershipProjection() {
        SseChatEmitter out = emitter();
        emitText(out, "正文");
        out.toolStart(pending("host-1"));
        List<TranscriptEntry.ToolCallView> calls = List.of(
                new TranscriptEntry.ToolCallView("host-1", "function", AiToolNames.CREATE_TASK, "{}"));
        Object localTimeline = ReflectionTestUtils.getField(out, "transcriptTimeline");
        ReflectionTestUtils.setField(out, "transcriptTimeline", new ArrayList<>());
        assertThat(out.transcriptSegments(RUN_ID + ":assistant:first", calls, "正文", "")).isNotEmpty();

        ReflectionTestUtils.setField(out, "transcriptTimeline", localTimeline);
        assertThat(out.transcriptToolContent(calls, "正文", "")).isNotNull();
        ReflectionTestUtils.setField(out, "transcriptTimeline", new ArrayList<>());

        assertThat(out.transcriptSegments(RUN_ID + ":assistant:second", calls, "正文", "")).isNull();
    }

    @Test
    void toolCandidateStartsAfterClaimedEntryAndBridgesOrdinaryTool() {
        PendingToolCall host = pending("host-1");
        when(this.store.pendingsLenient(RUN_ID)).thenReturn(List.of(host));
        when(this.store.pendings(RUN_ID)).thenReturn(List.of(host));
        AssistantMessage previous = assistant("前一轮", "previous");
        AssistantMessage ordinary = AssistantMessage.builder().content("")
                .toolCalls(List.of(toolCall("ordinary-1"))).build();
        AssistantMessage tools = AssistantMessage.builder().content("甲乙")
                .toolCalls(List.of(toolCall("host-1"))).build();
        SseChatEmitter out = emitter();
        emitText(out, "前一轮");
        assertThat(out.transcriptSegments(RUN_ID + ":assistant:text-previous", List.of(), "前一轮", ""))
                .isNotEmpty();
        emitText(out, "甲");
        out.toolStart(pending("ordinary-1"));
        emitText(out, "乙");
        out.toolStart(host);
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");

        Object result = recover(snapshot(List.of(new UserMessage("问"), previous, ordinary, response("ordinary-1"),
                tools, response("host-1")), "前一轮甲乙"));

        assertThat(historyComplete(result)).isTrue();
        TranscriptEntry recovered = this.persisted.stream()
                .filter(entry -> "assistant".equals(entry.kind()) && !entry.toolCalls().isEmpty())
                .findFirst().orElseThrow();
        assertThat(recovered.text()).isEqualTo("甲乙");
        assertThat(recovered.segments()).filteredOn(segment -> "text".equals(segment.kind()))
                .extracting(TranscriptEntry.SegmentView::text).containsExactly("甲", "乙");
        assertThat(recovered.segments()).filteredOn(segment -> "tool".equals(segment.kind()))
                .extracting(TranscriptEntry.SegmentView::toolCallId).containsExactly("host-1");
    }

    @Test
    void toolCandidateRejectsClaimedOrdinaryToolBetweenHostCalls() {
        PendingToolCall first = pending("host-1");
        PendingToolCall second = pending("host-2");
        when(this.store.pendingsLenient(RUN_ID)).thenReturn(List.of(first, second));
        when(this.store.pendings(RUN_ID)).thenReturn(List.of(first, second));
        AssistantMessage ordinary = AssistantMessage.builder().content("")
                .toolCalls(List.of(toolCall("ordinary-1"))).build();
        AssistantMessage tools = AssistantMessage.builder().content("甲乙")
                .toolCalls(List.of(toolCall("host-1"), toolCall("host-2"))).build();
        SseChatEmitter out = emitter();
        emitText(out, "甲");
        out.toolStart(first);
        out.toolStart(pending("ordinary-1"));
        List<TranscriptEntry.ToolCallView> ordinaryCall = List.of(
                new TranscriptEntry.ToolCallView("ordinary-1", "function", AiToolNames.CREATE_TASK, "{}"));
        assertThat(out.transcriptSegments(RUN_ID + ":assistant:previous", ordinaryCall, "", ""))
                .isNotEmpty();
        emitText(out, "乙");
        out.toolStart(second);
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");

        Object result = recover(snapshot(List.of(new UserMessage("问"), ordinary, response("ordinary-1"),
                tools, response("host-1"), response("host-2")), "甲乙"));

        assertThat(historyComplete(result)).isFalse();
        assertThat(this.appendCalls).isZero();
        assertThat(this.removeCalls).isZero();
    }

    @Test
    void toolCandidateFiltersUnclaimedOrdinaryToolBetweenHostCalls() {
        PendingToolCall first = pending("host-1");
        PendingToolCall second = pending("host-2");
        when(this.store.pendingsLenient(RUN_ID)).thenReturn(List.of(first, second));
        when(this.store.pendings(RUN_ID)).thenReturn(List.of(first, second));
        AssistantMessage ordinary = AssistantMessage.builder().content("")
                .toolCalls(List.of(toolCall("ordinary-1"))).build();
        AssistantMessage tools = AssistantMessage.builder().content("甲乙")
                .toolCalls(List.of(toolCall("host-1"), toolCall("host-2"))).build();
        SseChatEmitter out = emitter();
        emitText(out, "甲");
        out.toolStart(first);
        out.toolStart(pending("ordinary-1"));
        emitText(out, "乙");
        out.toolStart(second);
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");

        Object result = recover(snapshot(List.of(new UserMessage("问"), ordinary, response("ordinary-1"),
                tools, response("host-1"), response("host-2")), "甲乙"));

        assertThat(historyComplete(result)).isTrue();
        TranscriptEntry recovered = this.persisted.stream()
                .filter(entry -> "assistant".equals(entry.kind()) && !entry.toolCalls().isEmpty())
                .findFirst().orElseThrow();
        assertThat(recovered.text()).isEqualTo("甲乙");
        assertThat(recovered.segments()).filteredOn(segment -> "tool".equals(segment.kind()))
                .extracting(TranscriptEntry.SegmentView::toolCallId).containsExactly("host-1", "host-2");
    }

    @Test
    void archivedClaimIsNotOverwrittenByConflictingLocalEntry() {
        SseChatEmitter out = emitter();
        emitText(out, "正文");
        out.toolStart(pending("host-1"));
        List<TranscriptEntry.ToolCallView> calls = List.of(
                new TranscriptEntry.ToolCallView("host-1", "function", AiToolNames.CREATE_TASK, "{}"));
        String archivedEntry = RUN_ID + ":assistant:archived";
        String localEntry = RUN_ID + ":assistant:local";
        Object localTimeline = ReflectionTestUtils.getField(out, "transcriptTimeline");
        ReflectionTestUtils.setField(out, "transcriptTimeline", new ArrayList<>());
        assertThat(out.transcriptSegments(archivedEntry, calls, "正文", "")).isNotEmpty();

        ReflectionTestUtils.setField(out, "transcriptTimeline", localTimeline);
        assertThat(out.transcriptSegments(localEntry, calls, "正文", "")).isNotEmpty();
        assertThat(out.transcriptToolContent(calls, "正文", "")).isNull();
        ReflectionTestUtils.setField(out, "transcriptTimeline", new ArrayList<>());

        assertThat(out.transcriptSegments(archivedEntry, calls, "正文", "")).isNotEmpty();
        assertThat(out.transcriptSegments(localEntry, calls, "正文", "")).isNull();
    }

    @Test
    void archivedClaimAcceptsMatchingLocalOwnershipProjection() {
        SseChatEmitter out = emitter();
        emitText(out, "正文");
        out.toolStart(pending("host-1"));
        List<TranscriptEntry.ToolCallView> calls = List.of(
                new TranscriptEntry.ToolCallView("host-1", "function", AiToolNames.CREATE_TASK, "{}"));
        String entryId = RUN_ID + ":assistant:owner";
        assertThat(out.transcriptToolContent(calls, "正文", "")).isNotNull();
        assertThat(out.transcriptSegments(entryId, calls, "正文", "")).isNotEmpty();
        ReflectionTestUtils.setField(out, "transcriptTimeline", new ArrayList<>());

        assertThat(out.transcriptSegments(entryId, calls, "正文", "")).isNotEmpty();
        assertThat(out.transcriptSegments(RUN_ID + ":assistant:other", calls, "正文", "")).isNull();
    }

    @Test
    void emptyToolCandidateDoesNotAbsorbFollowingPlainDelta() {
        PendingToolCall first = pending("host-1");
        PendingToolCall second = pending("host-2");
        when(this.store.pendingsLenient(RUN_ID)).thenReturn(List.of(first, second));
        when(this.store.pendings(RUN_ID)).thenReturn(List.of(first, second));
        AssistantMessage ordinary = AssistantMessage.builder().content("")
                .toolCalls(List.of(toolCall("ordinary-1"))).build();
        AssistantMessage tools = AssistantMessage.builder().content("")
                .toolCalls(List.of(toolCall("host-1"), toolCall("host-2"))).build();
        AssistantMessage plain = assistant("后续正文", "following-plain");
        SseChatEmitter out = emitter();
        out.toolStart(first);
        out.toolStart(pending("ordinary-1"));
        out.toolStart(second);
        out.delta("后续正文");
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");

        Object result = recover(snapshot(List.of(new UserMessage("问"), ordinary, response("ordinary-1"),
                tools, response("host-1"), response("host-2"), plain), "后续正文"));

        assertThat(historyComplete(result)).isTrue();
        assertThat(this.persisted).filteredOn(entry -> "assistant".equals(entry.kind()))
                .extracting(TranscriptEntry::text).containsExactly("", "后续正文");
        TranscriptEntry recovered = this.persisted.stream()
                .filter(entry -> "assistant".equals(entry.kind()) && !entry.toolCalls().isEmpty())
                .findFirst().orElseThrow();
        assertThat(recovered.segments()).filteredOn(segment -> "tool".equals(segment.kind()))
                .extracting(TranscriptEntry.SegmentView::toolCallId).containsExactly("host-1", "host-2");
    }

    @Test
    void reasoningBridgesAcrossOrdinaryToolWithoutClaimingFollowingReasoning() {
        PendingToolCall host = pending("host-1");
        when(this.store.pendingsLenient(RUN_ID)).thenReturn(List.of(host));
        when(this.store.pendings(RUN_ID)).thenReturn(List.of(host));
        AssistantMessage ordinary = AssistantMessage.builder().content("")
                .toolCalls(List.of(toolCall("ordinary-1"))).build();
        AssistantMessage tools = AssistantMessage.builder().content("正文")
                .toolCalls(List.of(toolCall("host-1")))
                .properties(Map.of(ResilientChatService.REASONING_METADATA_KEY, "思考一思考二"))
                .build();
        AssistantMessage plain = AssistantMessage.builder().content("")
                .properties(Map.of(ResilientChatService.REASONING_METADATA_KEY, "后续思考",
                        TranscriptEntries.TRANSCRIPT_TEXT_ID_KEY, "following-plain"))
                .build();
        SseChatEmitter out = emitter();
        out.reasoningDelta("思考一");
        out.reasoningEnd();
        out.toolStart(pending("ordinary-1"));
        out.reasoningDelta("思考二");
        out.reasoningEnd();
        emitText(out, "正文");
        out.toolStart(host);
        out.reasoningDelta("后续思考");
        out.reasoningEnd();
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");

        Object result = recover(snapshot(List.of(new UserMessage("问"), ordinary, response("ordinary-1"),
                tools, response("host-1"), plain), "正文"));

        assertThat(historyComplete(result)).isTrue();
        TranscriptEntry recovered = this.persisted.stream()
                .filter(entry -> "assistant".equals(entry.kind()) && !entry.toolCalls().isEmpty())
                .findFirst().orElseThrow();
        assertThat(recovered.reasoning()).isEqualTo("思考一思考二");
        assertThat(recovered.segments()).filteredOn(segment -> "reasoning".equals(segment.kind()))
                .extracting(TranscriptEntry.SegmentView::text).containsExactly("思考一", "思考二");
        assertThat(this.persisted).filteredOn(entry -> entry.entryId().endsWith("following-plain"))
                .singleElement().satisfies(entry -> assertThat(entry.reasoning()).isEqualTo("后续思考"));
    }

    @Test
    void correctionPrefixBridgesAcrossOrdinaryToolExactlyOnce() {
        PendingToolCall host = pending("host-1");
        when(this.store.pendingsLenient(RUN_ID)).thenReturn(List.of(host));
        when(this.store.pendings(RUN_ID)).thenReturn(List.of(host));
        AssistantMessage ordinary = AssistantMessage.builder().content("")
                .toolCalls(List.of(toolCall("ordinary-1"))).build();
        AssistantMessage tools = AssistantMessage.builder().content("后缀")
                .toolCalls(List.of(toolCall("host-1"))).build();
        SseChatEmitter out = emitter();
        emitText(out, "前缀");
        out.toolStart(pending("ordinary-1"));
        emitText(out, "后缀");
        out.toolStart(host);
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");
        RunSnapshot snapshot = snapshot(List.of(new UserMessage("问"), ordinary, response("ordinary-1"),
                tools, response("host-1")), "前缀后缀");
        snapshot.setContext(AiContext.of("export", "EXPORT", null, null,
                Map.of(CorrectionPrefixes.EXPORT_CORRECTION_PREFIX_KEY, "前缀")).toMap());

        Object result = recover(snapshot);

        assertThat(historyComplete(result)).isTrue();
        assertThat(this.persisted).filteredOn(entry -> "assistant".equals(entry.kind()))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.text()).isEqualTo("前缀后缀");
                    assertThat(entry.segments()).filteredOn(segment -> "text".equals(segment.kind()))
                            .extracting(TranscriptEntry.SegmentView::text).containsExactly("前缀", "后缀");
                    assertThat(entry.segments()).filteredOn(segment -> "tool".equals(segment.kind()))
                            .extracting(TranscriptEntry.SegmentView::toolCallId).containsExactly("host-1");
                });
    }

    @Test
    void correctionPrefixRecoveryIsIdempotentAcrossArchiveReread() {
        PendingToolCall host = pending("host-1");
        when(this.store.pendingsLenient(RUN_ID)).thenReturn(List.of(host));
        when(this.store.pendings(RUN_ID)).thenReturn(List.of(host));
        AssistantMessage ordinary = AssistantMessage.builder().content("")
                .toolCalls(List.of(toolCall("ordinary-1"))).build();
        AssistantMessage tools = AssistantMessage.builder().content("后缀")
                .toolCalls(List.of(toolCall("host-1"))).build();
        SseChatEmitter out = emitter();
        emitText(out, "前缀");
        out.toolStart(pending("ordinary-1"));
        emitText(out, "后缀");
        out.toolStart(host);
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");
        RunSnapshot snapshot = snapshot(List.of(new UserMessage("问"), ordinary, response("ordinary-1"),
                tools, response("host-1")), "前缀后缀");
        snapshot.setContext(AiContext.of("export", "EXPORT", null, null,
                Map.of(CorrectionPrefixes.EXPORT_CORRECTION_PREFIX_KEY, "前缀")).toMap());

        Object first = recover(snapshot);
        assertThat(historyComplete(first)).isTrue();
        int appends = this.appendCalls;
        int removes = this.removeCalls;
        assertThat(out.transcriptFullText()).isEqualTo("前缀后缀");
        ReflectionTestUtils.setField(out, "transcriptTimeline", new ArrayList<>());

        Object second = recover(snapshot);

        assertThat(historyComplete(second)).isTrue();
        assertThat(this.appendCalls).isEqualTo(appends);
        assertThat(this.removeCalls).isEqualTo(removes);
        assertThat(this.persisted).filteredOn(entry -> "assistant".equals(entry.kind()))
                .singleElement().satisfies(entry -> assertThat(entry.text()).isEqualTo("前缀后缀"));
    }

    @Test
    void formSchemaRejectedPairNeedsNoSseSegmentAndRecoveryIsIdempotent() {
        Object first = recoverInternalRejection(AiToolNames.GENERATIVE_FORM, ErrorCodes.FORM_SCHEMA_REJECTED);
        assertThat(historyComplete(first)).isTrue();
        int appends = this.appendCalls;
        int removes = this.removeCalls;

        Object second = recover(internalRejectionSnapshot(AiToolNames.GENERATIVE_FORM, ErrorCodes.FORM_SCHEMA_REJECTED));

        assertThat(historyComplete(second)).isTrue();
        assertThat(this.appendCalls).isEqualTo(appends);
        assertThat(this.removeCalls).isEqualTo(removes);
        assertInternalRejectionEntries(AiToolNames.GENERATIVE_FORM);
    }

    @Test
    void formSlotsAlreadyKnownPairNeedsNoSseSegment() {
        Object result = recoverInternalRejection(AiToolNames.GENERATIVE_FORM, ErrorCodes.FORM_SLOTS_ALREADY_KNOWN);

        assertThat(historyComplete(result)).isTrue();
        assertInternalRejectionEntries(AiToolNames.GENERATIVE_FORM);
    }

    @Test
    void conditionOperatorUnknownPairNeedsNoSseSegment() {
        Object result = recoverInternalRejection(AiToolNames.CONFIRM_STEP, ErrorCodes.CONDITION_OPERATOR_UNKNOWN);

        assertThat(historyComplete(result)).isTrue();
        assertInternalRejectionEntries(AiToolNames.CONFIRM_STEP);
    }

    @Test
    void userVisibleRejectionStillRequiresSseSegment() {
        PendingToolCall rejected = rejectedWithVisibleSegment("internal-1", AiToolNames.GENERATIVE_FORM,
                "USER_VISIBLE_REJECTION");
        when(this.store.pendingsLenient(RUN_ID)).thenReturn(List.of(rejected));
        when(this.store.pendings(RUN_ID)).thenReturn(List.of(rejected));
        SseChatEmitter out = emitter();
        emitText(out, "最终正文");
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");

        Object result = recover(internalRejectionSnapshot(AiToolNames.GENERATIVE_FORM, "USER_VISIBLE_REJECTION"));

        assertThat(historyComplete(result)).isFalse();
        assertThat(this.appendCalls).isZero();
        assertThat(this.removeCalls).isZero();
    }

    @Test
    void unmarkedToolWithoutSseSegmentIsRejected() {
        PendingToolCall ordinary = pending("ordinary-1");
        when(this.store.pendingsLenient(RUN_ID)).thenReturn(List.of(ordinary));
        when(this.store.pendings(RUN_ID)).thenReturn(List.of(ordinary));
        AssistantMessage tools = AssistantMessage.builder().content("")
                .toolCalls(List.of(toolCall("ordinary-1"))).build();
        SseChatEmitter out = emitter();
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");

        Object result = recover(snapshot(List.of(new UserMessage("问"), tools, response("ordinary-1")), ""));

        assertThat(historyComplete(result)).isFalse();
        assertThat(this.appendCalls).isZero();
        assertThat(this.removeCalls).isZero();
    }

    @Test
    void duplicateAndCrossInstanceRecoveryDoesNotWriteAgain() {
        SseChatEmitter out = emitter();
        emitText(out, "正文");
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");
        RunSnapshot snapshot = snapshot(List.of(new UserMessage("问"), assistant("正文", "plain")), "正文");
        Object first = recover(snapshot);
        assertThat(historyComplete(first)).isTrue();
        int appends = this.appendCalls;
        int removes = this.removeCalls;

        Object duplicate = recover(snapshot);
        ResilientChatService otherInstance = service(this.store, this.registry);
        Object crossInstance;
        try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
            crossInstance = ReflectionTestUtils.invokeMethod(otherInstance, "recoverTerminalTranscript", snapshot);
        }

        assertThat(historyComplete(duplicate)).isTrue();
        assertThat(historyComplete(crossInstance)).isTrue();
        assertThat(this.appendCalls).isEqualTo(appends);
        assertThat(this.removeCalls).isEqualTo(removes);
    }

    @Test
    void finalTextMismatchIsRejectedWithoutWrites() {
        SseChatEmitter out = emitter();
        emitText(out, "正文");
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");

        Object result = recover(snapshot(List.of(new UserMessage("问"), assistant("正文", "plain")), "不一致"));

        assertThat(historyComplete(result)).isFalse();
        assertThat(this.appendCalls).isZero();
        assertThat(this.removeCalls).isZero();
        assertThat(this.recoveryLogs.list).map(ILoggingEvent::getFormattedMessage)
                .anyMatch(line -> line.contains("stage=final_text_mismatch"))
                .noneMatch(line -> line.contains("不一致") || line.contains("正文"));
    }

    @Test
    void unresolvedEntryLogsStageAndEntryIdWithoutContent() {
        SseChatEmitter out = emitter();
        emitText(out, "SSE正文");
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");

        Object result = recover(snapshot(List.of(new UserMessage("问"), assistant("模型正文", "plain")), "SSE正文"));

        assertThat(historyComplete(result)).isFalse();
        assertThat(this.recoveryLogs.list).map(ILoggingEvent::getFormattedMessage)
                .anyMatch(line -> line.contains("stage=entry_unresolved")
                        && line.contains("entryId=" + RUN_ID + ":assistant:text-plain"))
                .noneMatch(line -> line.contains("SSE正文") || line.contains("模型正文")
                        || line.contains("incomplete"));
    }

    @Test
    void missingFramesAreRejectedWithoutWrites() {
        emitter().error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");

        Object result = recover(snapshot(List.of(new UserMessage("问"), assistant("正文", "plain")), "正文"));

        assertThat(historyComplete(result)).isFalse();
        assertThat(this.appendCalls).isZero();
        assertThat(this.removeCalls).isZero();
    }

    @Test
    void archiveReadExceptionLogsOnlySafeScalars() {
        SseChatEmitter out = emitter();
        emitText(out, "正文");
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");
        FrameStore unreadable = mock(FrameStore.class);
        when(unreadable.readFromWithStatus(anyString(), any(Long.class), any(Integer.class)))
                .thenThrow(new IllegalStateException("敏感异常原文"));
        ReflectionTestUtils.setField(out, "frames", unreadable);

        Object result = recover(snapshot(List.of(new UserMessage("问"), assistant("正文", "plain")), "正文"));

        assertThat(historyComplete(result)).isFalse();
        assertThat(this.recoveryLogs.list).map(ILoggingEvent::getFormattedMessage)
                .anyMatch(line -> line.contains("stage=archive_unreadable"))
                .noneMatch(line -> line.contains("敏感异常原文") || line.contains("正文"));
        assertThat(this.recoveryLogs.list).anyMatch(event -> event.getThrowableProxy() != null
                && "java.lang.IllegalStateException".equals(event.getThrowableProxy().getClassName()));
    }

    @Test
    void truncatedFramesAreRejectedWithoutWrites() {
        SseChatEmitter out = emitter();
        emitText(out, "正文");
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");
        FrameStore truncated = mock(FrameStore.class);
        when(truncated.readFromWithStatus(anyString(), any(Long.class), any(Integer.class)))
                .thenReturn(new FrameStore.ReadResult(List.of(), false, true));
        ReflectionTestUtils.setField(out, "frames", truncated);

        Object result = recover(snapshot(List.of(new UserMessage("问"), assistant("正文", "plain")), "正文"));

        assertThat(historyComplete(result)).isFalse();
        assertThat(this.appendCalls).isZero();
        assertThat(this.removeCalls).isZero();
        assertThat(this.recoveryLogs.list).map(ILoggingEvent::getFormattedMessage)
                .anyMatch(line -> line.contains("stage=archive_unreadable"));
    }

    @Test
    void missingToolPairingIsRejectedWithoutWrites() {
        PendingToolCall pending = pending("tool-1");
        when(this.store.pendingsLenient(RUN_ID)).thenReturn(List.of(pending));
        when(this.store.pendings(RUN_ID)).thenReturn(List.of(pending));
        AssistantMessage tools = AssistantMessage.builder().content("")
                .toolCalls(List.of(toolCall("tool-1"))).build();
        SseChatEmitter out = emitter();
        out.toolStart(pending);
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");

        Object result = recover(snapshot(List.of(new UserMessage("问"), tools), ""));

        assertThat(historyComplete(result)).isFalse();
        assertThat(this.appendCalls).isZero();
        assertThat(this.removeCalls).isZero();
        assertThat(this.recoveryLogs.list).map(ILoggingEvent::getFormattedMessage)
                .anyMatch(line -> line.contains("stage=tool_pairs_incomplete"));
    }

    @Test
    void removeFailureStopsBeforeAppend() {
        PendingToolCall pending = pending("tool-1");
        when(this.store.pendingsLenient(RUN_ID)).thenReturn(List.of(pending));
        when(this.store.pendings(RUN_ID)).thenReturn(List.of(pending));
        AssistantMessage tools = AssistantMessage.builder().content("正文")
                .toolCalls(List.of(toolCall("tool-1"))).build();
        SseChatEmitter out = emitter();
        emitText(out, "正文");
        out.toolStart(pending);
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");
        this.persisted.add(TranscriptEntries.assistant(RUN_ID, 1L, 1L, "正文", List.of(), null)
                .withSegments(List.of(new TranscriptEntry.SegmentView("text", "正文", null, 2L))));
        org.mockito.Mockito.doAnswer(invocation -> {
            this.removeCalls++;
            return false;
        }).when(this.transcript).remove(anyString(), anyLong(), anyLong(), any(), anyLong());

        Object result = recover(snapshot(List.of(new UserMessage("问"), tools, response("tool-1")), "正文"));

        assertThat(historyComplete(result)).isFalse();
        assertThat(this.removeCalls).isEqualTo(1);
        assertThat(this.appendCalls).isZero();
        assertThat(this.recoveryLogs.list).map(ILoggingEvent::getFormattedMessage)
                .anyMatch(line -> line.contains("stage=append_failed"));
    }

    @Test
    void appendFailureIsDegradedWithoutRollbackClaim() {
        SseChatEmitter out = emitter();
        emitText(out, "正文");
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");
        org.mockito.Mockito.doAnswer(invocation -> {
            this.appendCalls++;
            return false;
        }).when(this.transcript).append(anyString(), anyLong(), anyLong(), any(), anyLong());

        Object result = recover(snapshot(List.of(new UserMessage("问"), assistant("正文", "plain")), "正文"));

        assertThat(historyComplete(result)).isFalse();
        assertThat(this.appendCalls).isEqualTo(1);
        assertThat(this.recoveryLogs.list).map(ILoggingEvent::getFormattedMessage)
                .anyMatch(line -> line.contains("stage=append_failed"));
    }

    @Test
    void reconcileReadFailureLogsDistinctStage() {
        SseChatEmitter out = emitter();
        emitText(out, "正文");
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");
        when(this.transcript.readPage(anyString(), anyLong(), anyLong(), any(), any())).thenReturn(null);

        Object result = recover(snapshot(List.of(new UserMessage("问"), assistant("正文", "plain")), "正文"));

        assertThat(historyComplete(result)).isFalse();
        assertThat(this.recoveryLogs.list).map(ILoggingEvent::getFormattedMessage)
                .anyMatch(line -> line.contains("stage=reconcile_failed"));
    }

    @Test
    void rereadFailureLogsDistinctStage() {
        SseChatEmitter out = emitter();
        emitText(out, "正文");
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        when(this.transcript.readPage(anyString(), anyLong(), anyLong(), any(), any())).thenAnswer(invocation ->
                reads.getAndIncrement() == 0
                        ? new ConversationTranscriptStore.ReadPage(List.of(), 0, 1L, null, false, false) : null);

        Object result = recover(snapshot(List.of(new UserMessage("问"), assistant("正文", "plain")), "正文"));

        assertThat(historyComplete(result)).isFalse();
        assertThat(this.recoveryLogs.list).map(ILoggingEvent::getFormattedMessage)
                .anyMatch(line -> line.contains("stage=reread_failed"));
    }

    @Test
    void purgeIsRejectedWithoutWrites() {
        SseChatEmitter out = emitter();
        emitText(out, "正文");
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");
        RunSnapshot snapshot = snapshot(List.of(new UserMessage("问"), assistant("正文", "plain")), "正文");
        when(this.store.get(RUN_ID)).thenReturn(snapshot);
        ResumeService resumeService = new ResumeService(this.store, this.registry,
                new MessageJsonCodec(new ObjectMapper()), this.chatService, null, null, null, new AiProperties(),
                mock(CancellationRegistry.class), null);
        SessionPurgeGuard purgeGuard = mock(SessionPurgeGuard.class);
        when(purgeGuard.isArmed("s-transcript-recovery")).thenReturn(true);
        ReflectionTestUtils.setField(resumeService, "sessionPurgeGuard", purgeGuard);

        ResumeService.ResumeResult result;
        try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
            result = resumeService.resume(RUN_ID);
        }

        assertThat(result.body()).containsEntry("historyComplete", false);
        assertThat(this.appendCalls).isZero();
        assertThat(this.removeCalls).isZero();
    }

    @Test
    void crossTenantRecoveryIsRejectedWithoutWrites() {
        SseChatEmitter out = emitter();
        emitText(out, "正文");
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");
        RunSnapshot snapshot = snapshot(List.of(new UserMessage("问"), assistant("正文", "plain")), "正文");

        Object result;
        try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(2L, 202L)) {
            result = ReflectionTestUtils.invokeMethod(this.chatService, "recoverTerminalTranscript", snapshot);
        }

        assertThat(historyComplete(result)).isFalse();
        assertThat(this.appendCalls).isZero();
        assertThat(this.removeCalls).isZero();
    }

    private Object recoverInternalRejection(String name, String code) {
        PendingToolCall rejected = new PendingToolCall() {
            public boolean isTranscriptSegmentRequired() {
                return false;
            }
        };
        rejected.setToolCallId("internal-1");
        rejected.setName(name);
        rejected.setKind(PendingToolCall.KIND_FRONTEND);
        rejected.setCode(code);
        rejected.setStatus(PendingToolCall.REJECTED_ARGUMENTS);
        rejected.setOutcome("rejected");
        rejected.setResultText("internal rejection");
        when(this.store.pendingsLenient(RUN_ID)).thenReturn(List.of(rejected));
        when(this.store.pendings(RUN_ID)).thenReturn(List.of(rejected));
        SseChatEmitter out = emitter();
        emitText(out, "最终正文");
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");
        return recover(internalRejectionSnapshot(name, code));
    }

    private RunSnapshot internalRejectionSnapshot(String name, String code) {
        AssistantMessage tools = AssistantMessage.builder().content("最终正文")
                .toolCalls(List.of(new AssistantMessage.ToolCall("internal-1", "function", name, "{}"))).build();
        ToolResponseMessage response = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("internal-1", name, code))).build();
        return snapshot(List.of(new UserMessage("问"), tools, response), "最终正文");
    }

    private void assertInternalRejectionEntries(String name) {
        List<TranscriptEntry> assistants = this.persisted.stream()
                .filter(entry -> "assistant".equals(entry.kind()) && !entry.toolCalls().isEmpty()).toList();
        assertThat(assistants).hasSize(1);
        TranscriptEntry assistant = assistants.getFirst();
        assertThat(assistant.text()).isEqualTo("最终正文");
        assertThat(assistant.toolCalls()).extracting(TranscriptEntry.ToolCallView::id).containsExactly("internal-1");
        assertThat(assistant.toolCalls()).extracting(TranscriptEntry.ToolCallView::name).containsExactly(name);
        assertThat(assistant.segments()).isEmpty();
        assertThat(assistant.toolCallStates()).containsKey("internal-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) assistant.toolCallStates().get("internal-1");
        assertThat(state).containsEntry("transcriptSegmentRequired", false);
        assertThat(this.persisted).filteredOn(entry -> "tool".equals(entry.kind()))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.toolCallId()).isEqualTo("internal-1");
                    assertThat(entry.segments()).isEmpty();
                });
    }

    private static PendingToolCall rejectedWithVisibleSegment(String id, String name, String code) {
        PendingToolCall pending = new PendingToolCall();
        pending.setToolCallId(id);
        pending.setName(name);
        pending.setKind(PendingToolCall.KIND_FRONTEND);
        pending.setCode(code);
        pending.setStatus(PendingToolCall.REJECTED_ARGUMENTS);
        pending.setOutcome("rejected");
        pending.setResultText("visible rejection");
        return pending;
    }

    private Object recoverFollowingAssistant(String text, String reasoning, boolean correctionActive) {
        PendingToolCall pending = pending("tool-1");
        when(this.store.pendingsLenient(RUN_ID)).thenReturn(List.of(pending));
        when(this.store.pendings(RUN_ID)).thenReturn(List.of(pending));
        AssistantMessage tools = AssistantMessage.builder().content("")
                .toolCalls(List.of(toolCall("tool-1"))).build();
        AssistantMessage plain = AssistantMessage.builder().content(text)
                .properties(Map.of(ResilientChatService.REASONING_METADATA_KEY, reasoning,
                        TranscriptEntries.TRANSCRIPT_TEXT_ID_KEY, "following-plain"))
                .build();
        SseChatEmitter out = emitter();
        out.toolStart(pending);
        out.reasoningDelta(reasoning);
        out.reasoningEnd();
        if (!text.isEmpty()) {
            emitText(out, text);
        }
        out.error(ErrorCodes.AI_TRANSCRIPT_INCOMPLETE, "incomplete");
        RunSnapshot snapshot = snapshot(List.of(new UserMessage("问"), tools, response("tool-1"), plain), text);
        if (correctionActive) {
            snapshot.setContext(AiContext.of("export", "EXPORT", null, null,
                    Map.of(CorrectionPrefixes.EXPORT_CORRECTION_PREFIX_KEY, "前缀")).toMap());
        }
        return recover(snapshot);
    }

    private void assertFollowingAssistantIsIndependent(Object result, String text, String reasoning) {
        assertThat(historyComplete(result)).isTrue();
        List<TranscriptEntry> assistants = this.persisted.stream()
                .filter(entry -> "assistant".equals(entry.kind())).toList();
        assertThat(assistants).hasSize(2);
        TranscriptEntry tool = assistants.stream().filter(entry -> !entry.toolCalls().isEmpty())
                .findFirst().orElseThrow();
        assertThat(tool.text()).isEmpty();
        assertThat(tool.reasoning()).isEmpty();
        TranscriptEntry plain = assistants.stream().filter(entry -> entry.toolCalls().isEmpty())
                .findFirst().orElseThrow();
        assertThat(plain.text()).isEqualTo(text);
        assertThat(plain.reasoning()).isEqualTo(reasoning);
        assertThat(plain.entryId()).endsWith("following-plain");
    }

    private Object recover(RunSnapshot snapshot) {
        try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
            return ReflectionTestUtils.invokeMethod(this.chatService, "recoverTerminalTranscript", snapshot);
        }
    }

    private static boolean historyComplete(Object result) {
        return Boolean.TRUE.equals(ReflectionTestUtils.getField(result, "historyComplete"));
    }

    private RunSnapshot snapshot(List<Message> history, String finalText) {
        RunSnapshot snapshot = new RunSnapshot();
        snapshot.setRunId(RUN_ID);
        snapshot.setSessionId("s-transcript-recovery");
        snapshot.setTenantId(1L);
        snapshot.setUserId(101L);
        snapshot.setStatus(RunSnapshot.DONE);
        snapshot.setRequestText("问");
        snapshot.setFinalText(finalText);
        snapshot.setMessageJson(new MessageJsonCodec(new ObjectMapper()).serializeAll(history));
        snapshot.setCreatedAtMs(2L);
        snapshot.setTurnStartedAtMs(1L);
        return snapshot;
    }

    private SseChatEmitter emitter() {
        return emitter(this.frames);
    }

    private SseChatEmitter emitter(FrameStore frameStore) {
        SseChatEmitter out = new SseChatEmitter(RUN_ID, this.store, frameStore, new ObjectMapper(), Runnable::run,
                AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY, AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES,
                com.example.configmgr.common.sse.SseWriteBudget.direct(), null, null,
                new com.example.configmgr.ai.tool.ToolResultLimiter(new AiProperties()));
        ReflectionTestUtils.setField(out, "deliveryIdentity", new com.example.configmgr.ai.tenant.TenantUser(1L, 101L));
        when(this.registry.find(RUN_ID)).thenReturn(out);
        when(this.registry.of(RUN_ID)).thenReturn(out);
        return out;
    }

    private ResilientChatService service(RunStore runStore, RunRegistry runRegistry) {
        AiProperties properties = new AiProperties();
        properties.getReasoning().setDisplayEnabled(true);
        ResilientChatService service = new ResilientChatService(mock(AiProviderRegistry.class), mock(ChatMemory.class),
                new MessageJsonCodec(new ObjectMapper()), runStore, runRegistry, mock(ToolRegistry.class),
                mock(ContextBuilder.class), mock(AiSessionService.class), properties, mock(CancellationRegistry.class),
                mock(ToolActivityBeacon.class), null);
        ReflectionTestUtils.setField(service, "transcript", this.transcript);
        return service;
    }

    private static AssistantMessage assistant(String text, String entryId) {
        return AssistantMessage.builder().content(text)
                .properties(Map.of(TranscriptEntries.TRANSCRIPT_TEXT_ID_KEY, entryId)).build();
    }

    private static AssistantMessage.ToolCall toolCall(String id) {
        return new AssistantMessage.ToolCall(id, "function", AiToolNames.CREATE_TASK, "{}");
    }

    private static ToolResponseMessage response(String id) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, AiToolNames.CREATE_TASK, "ok"))).build();
    }

    private static PendingToolCall pending(String id) {
        PendingToolCall pending = new PendingToolCall();
        pending.setToolCallId(id);
        pending.setName(AiToolNames.CREATE_TASK);
        pending.setKind(PendingToolCall.KIND_FRONTEND);
        pending.setStatus(PendingToolCall.PENDING);
        return pending;
    }

    private static void emitText(SseChatEmitter out, String text) {
        out.textStart();
        out.delta(text);
        out.textEnd(text.length());
    }
}
