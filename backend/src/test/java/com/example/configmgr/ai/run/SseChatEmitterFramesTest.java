package com.example.configmgr.ai.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DC-14 帧增量用例（T4/T5/T6/T7）：{@code SseChatEmitter} 的帧契约，全程只桩 {@link RunStore}
 * 与订阅者，不碰 Redis/Spring —— 锁住的正是"帧里到底有没有这几个字段、心跳有没有进归档"。
 *
 * <ul>
 * <li><b>T4</b>：{@code confirm_request} / {@code frontend_tool_request} 带绝对 {@code expiresAt}；</li>
 * <li><b>T5</b>：{@code tool_result}/{@code frontend_tool_result} 带 {@code messageId}，
 * 正文有 {@code message_start → delta → message_end} 三段式边界；</li>
 * <li><b>T6</b>：每帧带单调 {@code seq}（跨进程续号），reattach 用 {@code lastSeq} 只补增量。
 * S5c-1 起<b>业务帧</b>才占 {@code seq}，心跳走独立的 {@code heartbeatSeq}（见下条）；</li>
 * <li><b>T7</b>：心跳帧不入 {@code ai:events} 归档，但仍推给在线订阅者，并留下活动戳；
 * 它同时<b>不占业务序号</b>（S5c-1：否则跨进程续号会重用前端已覆盖的号 ⇒ 差量重挂静默丢帧）。</li>
 * </ul>
 */
class SseChatEmitterFramesTest {

    private static final String RUN_ID = "run-frames";

    private final RunStore store = mock(RunStore.class);

    private final SseChatEmitter emitter = new SseChatEmitter(RUN_ID, this.store, new ObjectMapper());

    // ── T6：单调 seq ─────────────────────────────────────────────────────────

    @Test
    void everyFrameCarriesMonotonicSeq() {
        this.emitter.start("s-1");
        this.emitter.delta("你好");
        this.emitter.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));

        List<Map<String, Object>> archived = archivedFrames();
        assertThat(archived).extracting(frame -> frame.get("type"))
                .containsExactly("start", "delta", "done");
        assertThat(archived).extracting(frame -> frame.get("seq"))
                .containsExactly(1L, 2L, 3L);
        assertThat(archived.get(1)).containsEntry("messageId", "assistant-" + RUN_ID);
    }

    @Test
    void seqContinuesAcrossProcessRestart() {
        // 归档里已有 41 帧（续跑的新写出器）：序号必须接着 42 走，否则 lastSeq 差量补发会漏帧
        when(this.store.lastEventSeq(RUN_ID)).thenReturn(41L);

        this.emitter.toolStart(pending("call-9"));

        assertThat(archivedFrames().get(0)).containsEntry("seq", 42L);
    }

    // ── T4：expiresAt ────────────────────────────────────────────────────────

    @Test
    void suspendRequestsCarryAbsoluteExpiry() {
        long before = System.currentTimeMillis();
        this.emitter.confirmRequest(pending("call-1"), 120);
        this.emitter.frontendToolRequest(pending("call-2"), 30);
        long after = System.currentTimeMillis();

        List<Map<String, Object>> archived = archivedFrames();
        assertThat(archived).extracting(frame -> frame.get("type"))
                .containsExactly("confirm_request", "frontend_tool_request");
        assertThat((Long) archived.get(0).get("expiresAt"))
                .isBetween(before + 120_000L, after + 120_000L);
        assertThat(archived.get(0)).containsEntry("timeoutSeconds", 120);
        assertThat((Long) archived.get(1).get("expiresAt"))
                .isBetween(before + 30_000L, after + 30_000L);
    }

    // ── T5：messageId 与三段式边界 ───────────────────────────────────────────

    @Test
    void toolResultFramesCarryMessageId() {
        this.emitter.toolResult(pending("call-7"), true, false, "全部 15 个定义");
        this.emitter.frontendToolResult(pending("call-8"), true, "已打开编辑器");

        List<Map<String, Object>> archived = archivedFrames();
        assertThat(archived.get(0)).containsEntry("messageId", "tool-call-7")
                .containsEntry("status", PendingToolCall.PENDING);
        assertThat(archived.get(1)).containsEntry("messageId", "tool-call-8");
    }

    @Test
    void textSegmentHasStartContentEndBoundaries() {
        // 三段式：start 只发一次（幂等），delta 是 content，end 只发一次且带字符数
        assertThat(this.emitter.textStart()).isTrue();
        assertThat(this.emitter.textStart()).as("同一段不重复开边界").isFalse();
        this.emitter.delta("第一段");
        this.emitter.delta("第二段");
        assertThat(this.emitter.textEnd(6)).isTrue();
        assertThat(this.emitter.textEnd(6)).as("未开段/已收段不再发 end").isFalse();

        List<Map<String, Object>> archived = archivedFrames();
        assertThat(archived).extracting(frame -> frame.get("type"))
                .containsExactly("message_start", "delta", "delta", "message_end");
        assertThat(archived.get(0)).containsEntry("kind", "text")
                .containsEntry("messageId", "assistant-" + RUN_ID);
        assertThat(archived.get(3)).containsEntry("chars", 6);
    }

    // ── T7：心跳不入归档 ─────────────────────────────────────────────────────

    @Test
    void heartbeatIsBroadcastButNotArchived() throws Exception {
        SseEmitter subscriber = mock(SseEmitter.class);
        this.emitter.attach(subscriber);

        this.emitter.heartbeat(Map.of("suspendKind", "confirm", "waitedSeconds", 4));

        // ① 在线订阅者照旧收到（挂起期的保活与倒计时语义不受影响）
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(subscriber).send(sent.capture());
        assertThat(sent.getValue()).contains("\"type\":\"heartbeat\"").contains("\"suspendKind\":\"confirm\"");
        // ①′ S5c-1：心跳在独立序号空间里计数，不带业务 seq
        assertThat(sent.getValue()).contains("\"heartbeatSeq\":1").doesNotContain("\"seq\"");
        // ② 归档里没有它（回放瘦身），但活动戳留下了
        verify(this.store, never()).appendEvent(eq(RUN_ID), anyMap());
        verify(this.store).touchActivity(RUN_ID);
        // ③ 非心跳帧照旧归档，且序号是 1（心跳没有偷走一个业务号）
        this.emitter.delta("正文");
        assertThat(archivedFrames().get(0)).containsEntry("seq", 1L);
    }

    // ── T6：reattach 增量补发 + 孤儿过滤 ─────────────────────────────────────

    @Test
    void replayWithLastSeqSendsOnlyNewFrames() throws Exception {
        when(this.store.events(RUN_ID)).thenAnswer(invocation -> archived());
        SseEmitter subscriber = mock(SseEmitter.class);

        int sent = this.emitter.replayTo(subscriber, false, 3L);

        // 归档共 5 帧：seq 1..5（其中 seq 1 是 delta，默认不回放；seq 2 是历史遗留的无 seq 帧）
        // ⇒ 补发 = 无 seq 的遗留帧 + seq 4、5；seq ≤ 3 的孤儿（前端本地已有）不重发
        assertThat(sent).isEqualTo(3);
        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(subscriber, org.mockito.Mockito.times(3)).send(payloads.capture());
        assertThat(payloads.getAllValues().get(0)).contains("suspended");
        assertThat(payloads.getAllValues().get(1)).contains("\"seq\":4");
        assertThat(payloads.getAllValues().get(2)).contains("\"seq\":5");
    }

    @Test
    void replayWithoutLastSeqStaysFull() throws Exception {
        when(this.store.events(RUN_ID)).thenAnswer(invocation -> archived());
        SseEmitter subscriber = mock(SseEmitter.class);

        // 不传 lastSeq = 老客户端口径：全量回放（归档 5 帧里 delta 仍按 includeDelta=false 跳过 ⇒ 4 帧）
        assertThat(this.emitter.replayTo(subscriber, false, null)).isEqualTo(4);
    }

    // ── 辅助 ────────────────────────────────────────────────────────────────

    private static PendingToolCall pending(String toolCallId) {
        PendingToolCall pending = new PendingToolCall();
        pending.setToolCallId(toolCallId);
        pending.setName("start_import");
        pending.setKind(PendingToolCall.KIND_CONFIRM);
        pending.setArguments("{\"taskId\":7}");
        return pending;
    }

    /** 捕获本次测试里 {@code appendEvent} 收到的帧（按调用顺序）。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> archivedFrames() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(this.store, org.mockito.Mockito.atLeastOnce()).appendEvent(eq(RUN_ID), captor.capture());
        return captor.getAllValues();
    }

    /** 回放用例的固定归档语料（含一帧历史遗留的"没有 seq"帧）。 */
    private static List<Map<String, Object>> archived() {
        List<Map<String, Object>> frames = new ArrayList<>();
        frames.add(frame("delta", 1L));
        frames.add(frame("suspended", null));
        frames.add(frame("confirm_request", 3L));
        frames.add(frame("tool_result", 4L));
        frames.add(frame("done", 5L));
        return frames;
    }

    private static Map<String, Object> frame(String type, Long seq) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type);
        if (seq != null) {
            frame.put("seq", seq);
        }
        return frame;
    }

    /** 静默未用到的桩（避免 IDE 提示）：本测试类不校验最后一个订阅者的摘除行为。 */
    @Test
    void subscriberCountTracksAttachment() {
        when(this.store.lastEventSeq(anyString())).thenReturn(0L);
        assertThat(this.emitter.subscriberCount()).isZero();
        this.emitter.attach(mock(SseEmitter.class));
        assertThat(this.emitter.subscriberCount()).isEqualTo(1);
    }

}
