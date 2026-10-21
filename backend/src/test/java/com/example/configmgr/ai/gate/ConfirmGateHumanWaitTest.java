package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * AI 表格操作专项 T-20：人工介入型前端工具的等待**没有上限**（设计稿 §3.4 / §7-裁 1 选项 A）。
 *
 * <p>判据单点 = {@link ConfirmGate#suspendWaitSeconds(PendingToolCall)}：返回 null ⇒ 帧不带
 * {@code timeoutSeconds}，一直等到用户点“应用/取消”。写工具若落回 120s 机器回执兜底，
 * 用户来不及看完 diff 就被判超时（确认是“人围栏”，不是“超时围栏”）。
 */
class ConfirmGateHumanWaitTest {

    private final ConfirmGate gate = new ConfirmGate(mock(RunStore.class), mock(RunRegistry.class),
            new AiProperties(), mock(CancellationRegistry.class), mock(ToolActivityBeacon.class), List.of());

    private static PendingToolCall frontend(String name) {
        PendingToolCall pending = new PendingToolCall();
        pending.setToolCallId("call-1");
        pending.setName(name);
        pending.setKind(PendingToolCall.KIND_FRONTEND);
        return pending;
    }

    @Test
    void humanWaitFrontendToolsHaveNoDeadline() {
        for (String name : new String[] { "generative_form", "replace_values", "update_rows", "normalize_column",
                "undo_last_sheet_edit" }) {
            assertThat(gate.suspendWaitSeconds(frontend(name)))
                    .as("%s 等的是人 ⇒ 不设上限", name)
                    .isNull();
        }
    }

    @Test
    void immediateFrontendToolsKeepTheMachineReceiptDeadline() {
        Integer seconds = gate.suspendWaitSeconds(frontend("navigate_to"));
        assertThat(seconds).isEqualTo(gate.frontendToolTimeoutSeconds());
        assertThat(seconds).isPositive();
    }

    @Test
    void confirmGateAndBackendToolsAreUnaffected() {
        PendingToolCall confirm = frontend("start_export");
        confirm.setKind(PendingToolCall.KIND_CONFIRM);
        assertThat(gate.suspendWaitSeconds(confirm)).isNull();

        PendingToolCall backend = frontend("list_tasks");
        backend.setKind(PendingToolCall.KIND_BACKEND);
        assertThat(gate.suspendWaitSeconds(backend)).isNull();
        assertThat(gate.suspendWaitSeconds(null)).isNull();
    }

}
