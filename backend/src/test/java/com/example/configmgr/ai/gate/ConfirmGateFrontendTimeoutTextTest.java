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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 超时收口**文案**的回归锁（等待治理批 A / W-01 的配套文案改实）。
 *
 * <h2>锁的是什么</h2>
 * 前端工具挂起超时（{@code FRONTEND_TIMEOUT}）的旧文案是
 * 「未在 N 秒内回传执行结果（前端超时），本次调用未获得数据」—— 后半句是**误导**：
 * 前端工具的副作用可能已经发生（文件名已落盘、表单已提交），"未获得数据"会被模型读成
 * "什么都没发生"（issue#6 的两轮 120s 就是这种假失败：文件其实已落盘）。
 * 故改为"未在 N 秒内收到前端回执，本次调用结局未知（不表示未发生）"，本类把它钉死，
 * 防日后被静默改回旧口径。
 *
 * <h2>两个分支的语义必须分开（同一条 {@code expire}）</h2>
 * <ul>
 * <li>确认门（等的是人的决策）⇒ 超时 = 工具未执行，文案保留"已自动取消该操作（工具未执行）"；</li>
 * <li>前端工具（等的是前端的回执）⇒ 超时只说明回执没回来，文案必须说"结局未知"。</li>
 * </ul>
 * 断言同时检查"新文案在"与"旧措辞不在"，后者才是防回归的关键——只断言"结局未知"存在时，
 * 旧句子被追加回去也照样全绿。
 *
 * <h2>等待上限为什么注入两个「非 1 且非默认」的值（复核 S2-e 加固）</h2>
 * 本类最初的写法是把上限压到 1s，并在断言里用**同一个常量**拼串
 * （{@code contains("未在 " + SECONDS + " 秒内…")}）：断言与实现同源，于是「把 N 硬编码成 1」
 * 的错误实现照样全绿，"N 用实际配置值"这条要求其实没被锁住（复核报告 S2-e 的破防项）。
 * 现在改为：① 注入 {@code 2} 与 {@code 3} 两个互不相同、既不是 1 也不是默认值 120 的上限
 * （参数化，任一硬编码常量都只能巧合于其中一个值）；② 断言不拼串，而是用正则从**产出的文案里
 * 读回**秒数再与注入值比较（{@link #secondsInText}），断言不再与实现共享字面来源。
 *
 * <p>上限的注入走生产路径 {@code app.ai.hitl.timeout}，故每个用例真等到该秒数才收口
 * （{@code 2s + 3s + 1s}）—— 用"真到期"而非绕过等待的伪造路径。
 */
class ConfirmGateFrontendTimeoutTextTest {

    private static final String RUN_ID = "run-timeout-text";

    private static final String TOOL_CALL_ID = "call-timeout-text";

    /**
     * 确认门用例的上限：该用例锁的是"确认门分支的措辞不被连带改掉"，与 N 取何值无关，
     * 故取允许的最小值 1s 省时（"N 随配置值走"由参数化的前端工具用例负责）。
     */
    private static final int CONFIRM_TIMEOUT_SECONDS = 1;

    /** 从文案里读秒（"未在 N 秒内收到前端回执"），只认产出物本身，不认注入值。 */
    private static final Pattern SECONDS_IN_TEXT = Pattern.compile("未在 (\\d+) 秒内收到前端回执");

    private final RunStore store = mock(RunStore.class);

    private final RunRegistry registry = mock(RunRegistry.class);

    private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

    private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

    private final SseChatEmitter emitter = mock(SseChatEmitter.class);

    @ParameterizedTest(name = "注入 {0} 秒 ⇒ 文案秒数为 {0}")
    @ValueSource(ints = { 2, 3 })
    @DisplayName("前端工具超时：文案里的秒数随注入的配置值走，不再说「未获得数据」")
    void frontendToolTimeoutTextFollowsConfiguredSeconds(int injectedSeconds) {
        ConfirmGate gate = gateWithTimeoutSeconds(injectedSeconds);
        PendingToolCall entry = pending(PendingToolCall.KIND_FRONTEND);
        stubCommon(entry);

        PendingToolCall resolved = gate.awaitFrontendResult(RUN_ID, entry);

        assertThat(resolved.getStatus()).isEqualTo(PendingToolCall.TIMEOUT);
        assertThat(resolved.getReason()).isEqualTo("FRONTEND_TIMEOUT");
        assertThat(resolved.isExecuted()).as("超时不认领执行").isFalse();
        assertThat(secondsInText(resolved.getResultText()))
                .as("文案秒数必须等于注入的 app.ai.hitl.timeout（硬编码常量过不了两个用例）")
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

    @Test
    @DisplayName("确认门超时：文案仍是「已自动取消该操作（工具未执行）」—— 该分支语义不同，不得被一并改掉")
    void confirmTimeoutKeepsNotExecutedWording() {
        ConfirmGate gate = gateWithTimeoutSeconds(CONFIRM_TIMEOUT_SECONDS);
        PendingToolCall entry = pending(PendingToolCall.KIND_CONFIRM);
        stubCommon(entry);

        PendingToolCall resolved = gate.awaitDecision(RUN_ID, entry);

        assertThat(resolved.getStatus()).isEqualTo(PendingToolCall.TIMEOUT);
        assertThat(resolved.getReason()).isEqualTo("CONFIRM_TIMEOUT");
        assertThat(resolved.getResultText())
                .contains("确认等待超过 " + CONFIRM_TIMEOUT_SECONDS + " 秒")
                .contains("系统已自动取消该操作（工具未执行）");
        assertThat(resolved.getResultText())
                .as("前端工具分支专属措辞不得串进确认门：确认门超时是「确定未执行」，不是「结局未知」")
                .doesNotContain("结局未知");
        verify(this.emitter).confirmDecision(resolved, "timeout", "CONFIRM_TIMEOUT", CONFIRM_TIMEOUT_SECONDS * 1000L);
    }

    // ───────────────────────── helpers ─────────────────────────

    private ConfirmGate gateWithTimeoutSeconds(int seconds) {
        AiProperties properties = new AiProperties();
        properties.getHitl().setTimeout(Duration.ofSeconds(seconds));
        return new ConfirmGate(this.store, this.registry, properties, this.cancellations, this.beacon, List.of());
    }

    /** 只 stub 等待循环真会读到的东西：闸门唤醒通道、取消通道、回执帧出口、状态读取。 */
    private void stubCommon(PendingToolCall entry) {
        when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
        when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
        when(this.cancellations.onCancel(anyString(), any())).thenReturn(() -> {
        });
        // 等待循环每拍重读一次；返回同一实例 = 一直 PENDING（模拟"前端始终没回灌"）
        when(this.store.pending(RUN_ID, TOOL_CALL_ID)).thenReturn(entry);
    }

    /** 从产出的文案里读回秒数（正则提取，不用注入值拼串 —— 否则断言与实现同源）。 */
    private static int secondsInText(String text) {
        Matcher matcher = SECONDS_IN_TEXT.matcher(text == null ? "" : text);
        assertThat(matcher.find()).as("文案里应出现「未在 N 秒内收到前端回执」").isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private static PendingToolCall pending(String kind) {
        PendingToolCall pending = new PendingToolCall();
        pending.setToolCallId(TOOL_CALL_ID);
        pending.setName(kind.equals(PendingToolCall.KIND_CONFIRM) ? "start_publish" : "download_export_file");
        pending.setKind(kind);
        pending.setStatus(PendingToolCall.PENDING);
        return pending;
    }
}
