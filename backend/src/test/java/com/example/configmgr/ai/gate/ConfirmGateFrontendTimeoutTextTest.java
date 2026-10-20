package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 挂起收口**文案**的回归锁（等待治理批 A / W-01 的配套文案改实；2026-10-20 裁决后重写）。
 *
 * <h2>锁的是什么</h2>
 * <ol>
 * <li><b>前端工具回执超时</b>（{@code FRONTEND_TIMEOUT}）—— 只对即时执行类前端工具（本类用
 * {@code download_export_files}）生效：旧文案「未在 N 秒内回传执行结果（前端超时），本次调用未获得数据」
 * 的后半句是<b>误导</b>（副作用可能已经发生），现为"未在 N 秒内收到前端回执，本次调用结局未知
 * （不表示未发生）"，本类把它钉死，防日后被静默改回旧口径；</li>
 * <li><b>确认门不再超时</b>（裁决第 1 条）：确认卡没有等待上限，只有在人决策 / 取消时才收口 ——
 * 跨过原上限量级的等待之后仍是未决，且<b>永不</b>发出 {@code decision=timeout} 帧；</li>
 * <li><b>{@code generative_form} 不超时</b>（裁决第 2 条）：同一 {@code awaitFrontendResult} 路径，
 * 按工具名区分 —— 即使 {@code frontend-tool-timeout} 小到 1s，表单也照样等。</li>
 * </ol>
 *
 * <h2>为什么前端工具用例注入两个「非 1 且非默认」的上限</h2>
 * 断言与实现同源会让「把 N 硬编码成 1」的错误实现照样全绿。因此：① 注入 {@code 2} 与 {@code 3}
 * 两个互不相同、既不是 1 也不是默认值（{@code frontend-tool-timeout} 120s）的上限（参数化，任一
 * 硬编码常量都只能巧合于其中一个值）；② 断言不拼串，而是用正则从**产出的文案里**读回秒数再与注入值
 * 比较（{@link #secondsInText}），断言不再与实现共享字面来源。
 *
 * <p>上限的注入走生产路径（{@code app.ai.hitl.frontend-tool-timeout}），故前端工具用例真等到
 * 该秒数才收口（{@code 2s + 3s}）。
 */
class ConfirmGateFrontendTimeoutTextTest {

    private static final String RUN_ID = "run-timeout-text";

    private static final String TOOL_CALL_ID = "call-timeout-text";

    /**
     * "确认门不会超时"用例的观察窗口：跨过多个心跳节拍（2s）后仍未决，且在提交决策后立即收敛。
     * 原上限（240s）无法在单测里真等，故本用例锁的是<b>语义</b>：没有到期分支、没有 timeout 帧。
     */
    private static final long CONFIRM_OBSERVE_MILLIS = 2500L;

    /** 从文案里读秒（"未在 N 秒内收到前端回执"），只认产出物本身，不认注入值。 */
    private static final Pattern SECONDS_IN_TEXT = Pattern.compile("未在 (\\d+) 秒内收到前端回执");

    private final RunStore store = mock(RunStore.class);

    private final RunRegistry registry = mock(RunRegistry.class);

    private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

    private final ToolActivityBeacon beacon = new ToolActivityBeacon();

    private final SseChatEmitter emitter = mock(SseChatEmitter.class);

    @ParameterizedTest(name = "注入 {0} 秒 ⇒ 文案秒数为 {0}")
    @ValueSource(ints = { 2, 3 })
    @DisplayName("前端工具回执超时：文案里的秒数随注入的配置值走，不再说「未获得数据」")
    void frontendToolTimeoutTextFollowsConfiguredSeconds(int injectedSeconds) {
        ConfirmGate gate = gateWithFrontendTimeoutSeconds(injectedSeconds);
        PendingToolCall entry = pending(PendingToolCall.KIND_FRONTEND, "download_export_files");
        stubCommon(entry);

        PendingToolCall resolved = gate.awaitFrontendResult(RUN_ID, entry);

        assertThat(resolved.getStatus()).isEqualTo(PendingToolCall.TIMEOUT);
        assertThat(resolved.getReason()).isEqualTo("FRONTEND_TIMEOUT");
        assertThat(resolved.isExecuted()).as("超时不认领执行").isFalse();
        assertThat(secondsInText(resolved.getResultText()))
                .as("文案秒数必须等于注入的 app.ai.hitl.frontend-tool-timeout（硬编码常量过不了两个用例）")
                .isEqualTo(injectedSeconds);
        assertThat(resolved.getResultText())
                .contains("结局未知")
                .contains("不表示未发生");
        assertThat(resolved.getResultText())
                .as("旧措辞必须消失：它会被模型读成「什么都没发生」")
                .doesNotContain("未获得数据")
                .doesNotContain("回传执行结果");
        assertThat(resolved.getResultText())
                .as("确认门专属措辞不得串进前端工具分支：本分支的结局是「未知」，说「工具未执行」自相矛盾")
                .doesNotContain("工具未执行");
        verify(this.emitter).frontendToolRequest(entry, injectedSeconds);
        verify(this.emitter).frontendToolResult(resolved, false, resolved.getResultText());
    }

    /**
     * 确认门<b>不再超时</b>（裁决第 1 条）：观察窗口内一直未决（没有任何超时帧/决策帧），
     * 人的决策一到就立即收敛为 {@code APPROVED}。
     */
    @Test
    @DisplayName("确认门不再超时：长等不产生 timeout 帧，人一批准就收敛")
    void confirmGateWaitsUntilHumanDecides() throws Exception {
        ConfirmGate gate = gate(properties -> {
        });
        PendingToolCall entry = pending(PendingToolCall.KIND_CONFIRM, "start_publish");
        stubCommon(entry);

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<PendingToolCall> awaited = pool.submit(() -> gate.awaitDecision(RUN_ID, entry));
            Thread.sleep(CONFIRM_OBSERVE_MILLIS);
            assertThat(awaited.isDone()).as("观察窗口内必须仍在等（确认门没有到期分支）").isFalse();
            verify(this.emitter, org.mockito.Mockito.never())
                    .confirmDecision(any(PendingToolCall.class), org.mockito.ArgumentMatchers.eq("timeout"),
                            any(), org.mockito.ArgumentMatchers.anyLong());
            // 心跳仍在跑（保活 + 续期），说明这是一条"活着的等待"而不是卡死；
            // 每一拍同时续期卡的后台数据（run/pending/events/seq），长等不会让挂起态先过期
            verify(this.emitter, org.mockito.Mockito.atLeastOnce()).heartbeat(any());
            verify(this.store, org.mockito.Mockito.atLeastOnce()).renewSuspensionTtl(RUN_ID);

            ConfirmGate.Submission submission = gate.submitDecision(RUN_ID, TOOL_CALL_ID, true, "同意发布");
            assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);

            PendingToolCall resolved = awaited.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(resolved.getStatus()).isEqualTo(PendingToolCall.APPROVED);
            // 确认卡帧不带到期时刻（前端因此不武装倒计时、不置灰按钮）
            verify(this.emitter).confirmRequest(entry);
        }
        finally {
            pool.shutdownNow();
        }
    }

    /** {@code generative_form}：同一路径、按工具名豁免 —— 即使前端工具上限只有 1s，表单也照样等。 */
    @Test
    @DisplayName("generative_form 不超时：前端工具上限 1s 也照样等用户填表")
    void generativeFormIgnoresFrontendToolTimeout() throws Exception {
        ConfirmGate gate = gateWithFrontendTimeoutSeconds(1);
        PendingToolCall entry = pending(PendingToolCall.KIND_FRONTEND, "generative_form");
        stubCommon(entry);

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<PendingToolCall> awaited = pool.submit(
                    () -> gate.awaitFrontendResult(RUN_ID, entry));
            Thread.sleep(CONFIRM_OBSERVE_MILLIS);
            assertThat(awaited.isDone())
                    .as("生成式表单没有死线（裁决第 2 条）：1s 上限对它不生效")
                    .isFalse();

            ConfirmGate.Submission submission = gate.submitFrontendResult(RUN_ID, TOOL_CALL_ID,
                    "{\"keyword\":\"CNY\"}", "test");
            assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);

            PendingToolCall resolved = awaited.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(resolved.getStatus()).isEqualTo(PendingToolCall.FRONTEND_RESULT);
            verify(this.emitter).frontendToolRequest(entry, null);
        }
        finally {
            pool.shutdownNow();
        }
    }

    // ───────────────────────── helpers ─────────────────────────

    private ConfirmGate gateWithFrontendTimeoutSeconds(int seconds) {
        return gate(properties -> properties.getHitl().setFrontendToolTimeout(Duration.ofSeconds(seconds)));
    }

    private ConfirmGate gate(Consumer<AiProperties> tune) {
        AiProperties properties = new AiProperties();
        tune.accept(properties);
        return new ConfirmGate(this.store, this.registry, properties, this.cancellations, this.beacon, List.of());
    }

    /** 只 stub 等待循环真会读到的东西：闸门唤醒通道、取消通道、回执帧出口、状态读取。 */
    private void stubCommon(PendingToolCall entry) {
        when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
        when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
        when(this.cancellations.onCancel(anyString(), any())).thenReturn(() -> {
        });
        // 等待循环每拍重读一次；返回同一实例 = 一直 PENDING（模拟"外部始终没给结论"）
        when(this.store.pending(RUN_ID, TOOL_CALL_ID)).thenReturn(entry);
    }

    /** 从产出的文案里读回秒数（正则提取，不用注入值拼串 —— 否则断言与实现同源）。 */
    private static int secondsInText(String text) {
        Matcher matcher = SECONDS_IN_TEXT.matcher(text == null ? "" : text);
        assertThat(matcher.find()).as("文案里应出现「未在 N 秒内收到前端回执」").isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private static PendingToolCall pending(String kind, String name) {
        PendingToolCall pending = new PendingToolCall();
        pending.setToolCallId(TOOL_CALL_ID);
        pending.setName(name);
        pending.setKind(kind);
        pending.setStatus(PendingToolCall.PENDING);
        return pending;
    }
}
