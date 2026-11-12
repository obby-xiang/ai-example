package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * <li><b>T4</b>：只有<b>有时限</b>的 {@code frontend_tool_request} 带 {@code timeoutSeconds} +
 * 绝对 {@code expiresAt}；无上限的确认卡与 {@code generative_form} 两键都不出现；</li>
 * <li><b>T5</b>：{@code tool_result}/{@code frontend_tool_result} 带 {@code messageId}，
 * 正文有 {@code message_start → delta → message_end} 三段式边界；</li>
 * <li><b>T6</b>：每帧带单调 {@code seq}（跨进程续号），reattach 用 {@code lastSeq} 只补增量。
 * S5c-1 起<b>业务帧</b>才占 {@code seq}，心跳走独立的 {@code heartbeatSeq}（见下条）；</li>
 * <li><b>T7</b>：心跳帧不入 {@code ai:events} 归档，但仍推给在线订阅者，并留下活动戳；
 * 它同时<b>不占业务序号</b>（S5c-1：否则跨进程续号会重用前端已覆盖的号 ⇒ 差量重挂静默丢帧）。</li>
 * <li><b>T4-3 后半</b>：<b>实时</b>帧（含心跳）在帧构造点就带 {@code atMs}，与归档副本同值；
 * 挂起类的 {@code expiresAt} 与同一帧 {@code atMs} 同钟单点取值 ⇒
 * {@code expiresAt - atMs == timeoutSeconds * 1000L} 精确成立。</li>
 * </ul>
 */
class SseChatEmitterFramesTest {

    private static final String RUN_ID = "run-frames";

    private final RunStore store = mock(RunStore.class);

    private final InMemoryFrameStore frames = new InMemoryFrameStore();

    private RequestContextUtils.SafeCloseable identity;

    private SseChatEmitter emitter;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        this.identity = RequestContextUtils.useContext(1L, 101L);
        this.emitter = newEmitter(new ObjectMapper());
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        if (this.identity != null) {
            this.identity.close();
        }
    }

    /** 11 参装配：帧面独立（FrameStore），写出器身份取构造线程的显式身份。 */
    private SseChatEmitter newEmitter(ObjectMapper mapper) {
        return new SseChatEmitter(RUN_ID, this.store, this.frames, mapper, Runnable::run,
                com.example.configmgr.ai.config.AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY,
                com.example.configmgr.ai.config.AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES,
                com.example.configmgr.common.sse.SseWriteBudget.direct(), new NoopWakeBus(), null, null);
    }

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
        this.frames.seed(RUN_ID, frame("done", 41L));

        this.emitter.toolStart(pending("call-9"));

        List<Map<String, Object>> archived = archivedFrames();
        assertThat(archived.get(archived.size() - 1)).containsEntry("seq", 42L);
    }

    // ── T4：expiresAt ────────────────────────────────────────────────────────

    /**
     * 2026-10-20 裁决后：<b>只有有时限的前端工具</b>带到期时刻。
     * 确认卡无上限（帧里不带 {@code timeoutSeconds}/{@code expiresAt}），
     * {@code generative_form}（timeoutSeconds=null）同样不带 —— 前端据此不武装倒计时。
     */
    @Test
    void suspendRequestsCarryAbsoluteExpiryOnlyWhenThereIsADeadline() {
        long before = System.currentTimeMillis();
        this.emitter.confirmRequest(pending("call-1"));
        this.emitter.frontendToolRequest(pending("call-2"), 30);
        this.emitter.frontendToolRequest(generativeForm("call-3"), null);
        long after = System.currentTimeMillis();

        List<Map<String, Object>> archived = archivedFrames();
        assertThat(archived).extracting(frame -> frame.get("type"))
                .containsExactly("confirm_request", "frontend_tool_request", "frontend_tool_request");
        assertThat(archived.get(0)).as("确认卡没有到期时刻（一直等人）")
                .containsKeys("type", "seq", "atMs", "preflight")
                .containsEntry("toolCallId", "call-1")
                .containsEntry("name", "start_import")
                .containsEntry("args", "{\"taskId\":7}")
                .containsEntry("summary", "{\"taskId\":7}")
                .containsEntry("irreversible", false)
                .doesNotContainKey("expiresAt")
                .doesNotContainKey("timeoutSeconds")
                .doesNotContainKey("callback");
        assertThat((Long) archived.get(1).get("expiresAt"))
                .isBetween(before + 30_000L, after + 30_000L);
        assertThat(archived.get(1)).containsKeys("type", "seq", "atMs")
                .containsEntry("toolCallId", "call-2")
                .containsEntry("name", "start_import")
                .containsEntry("args", "{\"taskId\":7}")
                .containsEntry("timeoutSeconds", 30)
                .doesNotContainKey("callback");
        assertThat(archived.get(2)).as("generative_form 无上限：键不出现（与 confirm_request 同口径）")
                .doesNotContainKey("timeoutSeconds")
                .doesNotContainKey("expiresAt")
                .doesNotContainKey("callback");
    }

    /**
     * 批4-D（用户 2026-10-23 裁决）：不可撤销的工具（{@code ToolMeta.Category.P}）确认卡
     * 多一层复选框 —— 该事实必须随 {@code confirm_request} 帧下发，前端才可能画出复选框。
     *
     * <p>契约是 <b>additive</b>：不论真假键都在（false 也让前端明确"这张卡不需要勾选"），
     * 帧类型集不变。
     */
    @Test
    void confirmRequestCarriesIrreversibleFlag() {
        PendingToolCall publish = pending("call-publish");
        publish.setName("start_publish");
        publish.setIrreversible(true);
        PendingToolCall export = pending("call-export");

        this.emitter.confirmRequest(publish);
        this.emitter.confirmRequest(export);

        List<Map<String, Object>> archived = archivedFrames();
        assertThat(archived).extracting(frame -> frame.get("type"))
                .containsExactly("confirm_request", "confirm_request");
        assertThat(archived.get(0)).as("发布作业不可撤销 ⇒ 帧带 irreversible=true")
                .containsEntry("irreversible", true);
        assertThat(archived.get(1)).as("可逆确认卡显式带 false（键恒在，前端不必猜）")
                .containsEntry("irreversible", false);
    }

    // ── T5：messageId 与三段式边界 ───────────────────────────────────────────

    @Test
    void toolResultFramesCarryMessageId() {
        this.emitter.toolResult(executed("call-7"), true, false, "全部 15 个定义");
        this.emitter.frontendToolResult(frontendResult("call-8"), true, "已打开编辑器");

        List<Map<String, Object>> archived = archivedFrames();
        assertThat(archived.get(0)).containsEntry("messageId", "tool-call-7")
                .containsEntry("status", PendingToolCall.EXECUTED);
        assertThat(archived.get(1)).containsEntry("messageId", "tool-call-8");
    }

    /**
     * N9（2026-10-26 批次 6C）：工具卡「输出」块只说人话。
     *
     * <p>{@code result} 是卡面的人读通道，不得出现 {@code app.ai.*} 配置键、内部接口路径
     * 或"请直接…"这类说给模型的指令；{@code outcome}/{@code code} 仍是机器可读真值。
     */
    @Test
    void toolResultFrameShowsUserSummaryForInternalCodes() {
        PendingToolCall skipped = pending("call-skip");
        skipped.setStatus(PendingToolCall.SKIPPED);
        skipped.setOutcome(ToolOutcome.SKIPPED.wire());
        skipped.setCode(ToolOutcome.CODE_TOOL_STEP_LIMIT);

        PendingToolCall wrongEntry = pending("call-wrong");
        wrongEntry.setStatus(PendingToolCall.BLOCKED);
        wrongEntry.setOutcome(ToolOutcome.BLOCKED.wire());
        wrongEntry.setCode(ToolOutcome.CODE_TOOL_KIND_MISMATCH);

        this.emitter.toolResult(skipped, false, false,
                "未执行：本轮工具调用步数已达上限（app.ai.resilience.max-tool-steps = 25）。"
                        + "请用已经获得的信息给出结论。");
        this.emitter.toolResult(wrongEntry, false, false,
                "TOOL_KIND_MISMATCH：该调用是前端通道工具（kind=FRONTEND）；"
                        + "前端结果只能经 /api/ai/frontend-tool-result 回灌。");

        List<Map<String, Object>> frames = archivedFrames();
        assertThat(frames.get(0)).containsEntry("outcome", ToolOutcome.SKIPPED.wire())
                .containsEntry("code", ToolOutcome.CODE_TOOL_STEP_LIMIT);
        assertThat(String.valueOf(frames.get(0).get("result")))
                .as("用户可见摘要不含配置键与模型指令")
                .doesNotContain("app.ai.")
                .doesNotContain("请用已经获得的信息")
                .contains("这一步没有执行");
        assertThat(frames.get(1)).containsEntry("outcome", ToolOutcome.BLOCKED.wire())
                .containsEntry("code", ToolOutcome.CODE_TOOL_KIND_MISMATCH);
        assertThat(String.valueOf(frames.get(1).get("result")))
                .as("用户可见摘要不含内部接口路径与 kind")
                .doesNotContain("/api/ai/")
                .doesNotContain("kind=FRONTEND")
                .contains("这一步没有执行");
    }

    @Test
    void toolResultFrameCarriesOutcomeFieldsEvenUnderNonNullMapper() throws Exception {
        // 本用例注入一个 NON_NULL 的 ObjectMapper，验证帧装配器不依赖"全局 inclusion 恰好是 ALWAYS"：
        // 若把本类构造函数里的 copy().setSerializationInclusion(ALWAYS) 去掉，code/target 为 null 时
        // 整键会消失，前端无法区分"字段缺失"与"null"。
        // 注意：当前生产唯一 primary mapper（JacksonConfig）保持 Jackson 默认 ALWAYS，故那行 copy 是
        // 防御未来改动的 no-op —— 不是"生产装配就是 NON_NULL"。
        ObjectMapper nonNull = new ObjectMapper()
                .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        SseChatEmitter out = newEmitter(nonNull);
        FrameWire wire = FrameWire.attachTo(out);

        out.toolResult(executed("call-x"), true, false, "查看中");

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = new ObjectMapper().readValue(wire.payloads().get(0), Map.class);
        assertThat(payload).containsKeys("outcome", "code", "retryable", "target")
                .containsEntry("outcome", "ok")
                .containsEntry("retryable", false);
        assertThat(payload.get("code")).isNull();
        assertThat(payload.get("target")).isEqualTo(Map.of("taskId", "7"));
    }

    @Test
    void frontendToolResultCarriesSameOutcomeFieldsAsToolResult() {
        // I1：回灌帧与 tool_result 同契约 —— 四字段同一推导，前端不会遇到"帧 ok=false 而卡面 succeeded"。
        PendingToolCall ok = frontendResult("call-8");
        PendingToolCall cancelled = pending("call-9");
        cancelled.setStatus(PendingToolCall.FRONTEND_CANCELLED);
        cancelled.setOutcome("cancelled");
        cancelled.setCode("FRONTEND_CANCELLED");

        this.emitter.frontendToolResult(ok, true, "{\"page\":\"export\"}");
        this.emitter.frontendToolResult(cancelled, false, "用户取消");

        List<Map<String, Object>> archived = archivedFrames();
        assertThat(archived.get(0)).containsEntry("outcome", "ok")
                .containsEntry("code", null)
                .containsEntry("retryable", false)
                .containsEntry("target", Map.of("taskId", "7"));
        assertThat(archived.get(1)).containsEntry("outcome", "cancelled")
                .containsEntry("code", "FRONTEND_CANCELLED")
                .containsEntry("retryable", false)
                .containsEntry("target", Map.of("taskId", "7"));
    }

    @Test
    void toolResultRejectsOkThatContradictsOutcome() {
        // M4：ok 保留在签名里，但只能等于 outcome.ok() —— 传错即 fail-fast，不产出自相矛盾的帧。
        assertThatThrownBy(() -> this.emitter.toolResult(executed("call-bad"), false, false, "成功"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inconsistent with the derived outcome");

        // fail-fast 发生在组帧之前：归档里一帧都不该有
        assertThat(archivedFrames()).isEmpty();
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
        assertThat(archivedFrames()).isEmpty();
        verify(this.store).touchActivity(RUN_ID);
        // ③ 非心跳帧照旧归档，且序号是 1（心跳没有偷走一个业务号）
        this.emitter.delta("正文");
        assertThat(archivedFrames().get(0)).containsEntry("seq", 1L);
    }

    // ── T4-3 后半：实时帧 atMs 覆盖 ──────────────────────────────────────────

    @Test
    void liveFramesCarryAtMsFromEmission() {
        FrameWire wire = FrameWire.attachTo(this.emitter);
        long before = System.currentTimeMillis();
        this.emitter.start("s-1");
        this.emitter.delta("正文");
        this.emitter.confirmRequest(pending("call-1"));
        this.emitter.heartbeat(Map.of("suspendKind", "confirm", "waitedSeconds", 4));
        this.emitter.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));
        long after = System.currentTimeMillis();

        List<Map<String, Object>> live = wire.frames();
        assertThat(live).extracting(frame -> frame.get("type"))
                .containsExactly("start", "delta", "confirm_request", "heartbeat", "done");
        // 覆盖含心跳/正文在内的一切帧：atMs 在帧构造点即定型，故实时投递原文里必须逐帧可见
        for (Map<String, Object> frame : live) {
            Object atMs = frame.get("atMs");
            assertThat(atMs).as("实时帧 %s 必须带 atMs", frame.get("type")).isInstanceOf(Number.class);
            assertThat(((Number) atMs).longValue()).as("实时帧 %s 的 atMs 落在发射窗口内", frame.get("type"))
                    .isPositive()
                    .isBetween(before, after);
        }
    }

    @Test
    void frontendToolRequestSharesOneClockBetweenAtMsAndExpiry() {
        FrameWire wire = FrameWire.attachTo(this.emitter);

        this.emitter.confirmRequest(pending("call-1"));
        this.emitter.frontendToolRequest(pending("call-2"), 30);
        this.emitter.frontendToolRequest(pending("call-3"), 120);

        List<Map<String, Object>> live = wire.frames();
        assertThat(live).extracting(frame -> frame.get("type"))
                .containsExactly("confirm_request", "frontend_tool_request", "frontend_tool_request");
        // 精确等式（long 运算，无容差）：expiresAt 与 atMs 必须同一取钟点，否则折算剩余时长会被毫秒差污染。
        // 确认卡没有到期时刻（2026-10-20 裁决），故只对有时限的前端工具帧断言。
        for (Map<String, Object> frame : live.subList(1, live.size())) {
            long atMs = ((Number) frame.get("atMs")).longValue();
            long expiresAt = ((Number) frame.get("expiresAt")).longValue();
            long timeoutSeconds = ((Number) frame.get("timeoutSeconds")).longValue();
            assertThat(expiresAt - atMs).as("帧 %s 的 expiresAt − atMs", frame.get("type"))
                    .isEqualTo(timeoutSeconds * 1000L);
        }
    }

    @Test
    void archivedAtMsMatchesLiveAtMs() {
        FrameWire wire = FrameWire.attachTo(this.emitter);

        this.emitter.start("s-1");
        this.emitter.confirmRequest(pending("call-1"));
        this.emitter.done(Map.of(), "deepseek-flash", Map.of());

        List<Map<String, Object>> live = wire.frames();
        List<Map<String, Object>> archived = archivedFrames();
        // "归档 = 投递同一份帧"（T3-10 口径）不被 atMs 前移破坏：两者逐帧同值
        assertThat(archived).hasSameSizeAs(live);
        for (int i = 0; i < live.size(); i++) {
            assertThat(archived.get(i).get("atMs")).as("第 %d 帧归档 atMs 与实时投递原文", i)
                    .isEqualTo(live.get(i).get("atMs"));
        }
    }

    // ── T6：reattach 增量补发 + 孤儿过滤 ─────────────────────────────────────

    @Test
    void replayWithLastSeqSendsOnlyNewFrames() throws Exception {
        seedReplayArchive();
        SseEmitter subscriber = mock(SseEmitter.class);

        int sent = this.emitter.replayTo(subscriber, 3L);

        // 归档 seq 1..5：lastSeq=3 ⇒ 只补 4、5（seq ≤ 3 的孤儿前端本地已有，不重发）
        assertThat(sent).isEqualTo(2);
        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(subscriber, org.mockito.Mockito.times(2)).send(payloads.capture());
        assertThat(payloads.getAllValues().get(0)).contains("\"seq\":4");
        assertThat(payloads.getAllValues().get(1)).contains("\"seq\":5");
    }

    @Test
    void replayWithoutLastSeqStaysFull() throws Exception {
        seedReplayArchive();
        SseEmitter subscriber = mock(SseEmitter.class);

        // 不传 lastSeq = 老客户端口径：全量回放归档全部帧
        assertThat(this.emitter.replayTo(subscriber, null)).isEqualTo(5);
    }

    private void seedReplayArchive() {
        for (Map<String, Object> frame : archived()) {
            this.frames.seed(RUN_ID, frame);
        }
    }

    @Test
    void toolResultTargetCarriesPersistedJobIdWithoutReadingReceiptText() {
        PendingToolCall pending = executed("call-structured-job");
        assertThat(PendingToolCall.class.getDeclaredFields())
                .as("实时帧只能来自持久化 createdJobId")
                .anyMatch(field -> "createdJobId".equals(field.getName()));
        try {
            java.lang.reflect.Field field = PendingToolCall.class.getDeclaredField("createdJobId");
            field.setAccessible(true);
            field.set(pending, 4242L);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }

        this.emitter.toolResult(pending, true, false, "导入作业已启动（作业 #999）");

        Map<String, Object> frame = archivedFrames().get(0);
        assertThat(frame.get("target")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("taskId", "7")
                .containsEntry("jobId", "4242");
        assertThat(frame.get("result")).isEqualTo("导入作业已启动（作业 #999）");
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

    /** 已执行的后端条目（持久化 outcome=ok；tool_result 的 ok=true 只能配这个结局）。 */
    private static PendingToolCall executed(String toolCallId) {
        PendingToolCall pending = pending(toolCallId);
        pending.setStatus(PendingToolCall.EXECUTED);
        pending.setOutcome(ToolOutcome.OK.wire());
        return pending;
    }

    /** 前端回灌成功的条目（持久化 outcome=ok）。 */
    private static PendingToolCall frontendResult(String toolCallId) {
        PendingToolCall pending = pending(toolCallId);
        pending.setKind(PendingToolCall.KIND_FRONTEND);
        pending.setStatus(PendingToolCall.FRONTEND_RESULT);
        pending.setOutcome(ToolOutcome.OK.wire());
        return pending;
    }

    /** 生成式表单条目（前端通道，等待无上限）。 */
    private static PendingToolCall generativeForm(String toolCallId) {
        PendingToolCall pending = pending(toolCallId);
        pending.setKind(PendingToolCall.KIND_FRONTEND);
        pending.setName("generative_form");
        return pending;
    }

    /** 本次测试里 FrameStore 归档的帧（按 seq 升序；心跳不入档）。 */
    private List<Map<String, Object>> archivedFrames() {
        return this.frames.readFrom(RUN_ID, 0L, 0);
    }

    /** 回放用例的固定归档语料（seq 1..5）。 */
    private static List<Map<String, Object>> archived() {
        List<Map<String, Object>> frames = new ArrayList<>();
        frames.add(frame("delta", 1L));
        frames.add(frame("suspended", 2L));
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

    /**
     * 批次2 / AR-05（只写不跑）：{@code error} 终帧可带 additive {@code unconfirmed=true} ——
     * 前端只据此进入中性「状态未确认」视图，而不是拿 {@code AI_REDIS_UNAVAILABLE} 猜。
     */
    @Test
    void errorFrameCarriesAdditiveUnconfirmedFlag() {
        this.emitter.error("AI_REDIS_UNAVAILABLE", "终态暂不可确认", Map.of("unconfirmed", true));

        Map<String, Object> frame = archivedFrames().get(0);
        assertThat(frame).containsEntry("type", "error")
                .containsEntry("code", "AI_REDIS_UNAVAILABLE")
                .containsEntry("unconfirmed", true);
    }

    /** 静默未用到的桩（避免 IDE 提示）：本测试类不校验最后一个订阅者的摘除行为。 */
    @Test
    void subscriberCountTracksAttachment() {
        assertThat(this.emitter.subscriberCount()).isZero();
        this.emitter.attach(mock(SseEmitter.class));
        assertThat(this.emitter.subscriberCount()).isEqualTo(1);
    }

}
