package com.example.configmgr.ai.tool;

import com.example.configmgr.common.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultUserTextTest {

    @Test
    void cancelOutcomesUseApprovedUserSummariesWithoutChangingModelText() {
        String activeModelText = "模型侧主动停止原文";
        String frontendModelText = "FRONTEND_CANCELLED：模型侧指令";
        String publishModelText = "模型侧发布取消原文";

        assertThat(ToolResultUserText.forUser("start_export", ErrorCodes.RUN_CANCELLED, activeModelText, null))
                .isEqualTo("本轮已停止，该操作未执行。");
        assertThat(ToolResultUserText.forUser("generative_form", ErrorCodes.FRONTEND_CANCELLED,
                frontendModelText, null)).isEqualTo("已取消，本次操作未完成。");
        assertThat(ToolResultUserText.forUser("start_publish", ErrorCodes.RUN_CANCELLED, publishModelText, null))
                .isEqualTo("本轮已停止，该操作未执行。");
        assertThat(ToolResultUserText.userTextCode("start_export", ErrorCodes.RUN_CANCELLED, null))
                .isEqualTo("RUN_CANCELLED");
        assertThat(ToolResultUserText.userTextCode("generative_form", ErrorCodes.FRONTEND_CANCELLED, null))
                .isEqualTo("FRONTEND_CANCELLED");
        assertThat(ToolResultUserText.userTextCode("start_publish", ErrorCodes.RUN_CANCELLED, null))
                .isEqualTo("RUN_CANCELLED");
    }

    @Test
    void preflightUsesStructuredBusinessReasonsWithoutExposingChecksOrTools() {
        Map<String, Object> preflight = Map.of("checks", List.of(
                Map.of("code", "HAS_ITEMS", "pass", false),
                Map.of("code", "HAS_UPLOAD", "pass", false),
                Map.of("code", "NO_JOB_IN_FLIGHT", "pass", false),
                Map.of("code", "CONDITIONS_DECIDED", "pass", false),
                Map.of("code", "SOME_UNKNOWN_CHECK", "pass", false),
                Map.of("code", "TASK_EXISTS", "pass", true)));
        Map<String, Object> args = ToolResultUserText.preflightUserTextArgs(preflight);
        String modelText = "NOT APPROVED: HAS_ITEMS / HAS_UPLOAD / start_export";

        assertThat(args).containsEntry("reasons", List.of("MISSING_ITEMS", "MISSING_UPLOAD",
                "JOB_IN_FLIGHT", "CONDITIONS_UNDECIDED", "PREPARATION_REQUIRED"));
        assertThat(ToolResultUserText.userTextCode("start_export", ErrorCodes.PRECHECK_FAILED, args))
                .isEqualTo("PRECHECK_FAILED");
        assertThat(ToolResultUserText.forUser("start_export", ErrorCodes.PRECHECK_FAILED, modelText, args))
                .startsWith("本次操作未执行，请先完成以下准备：")
                .contains("尚未选择配置项", "尚未上传数据文件", "已有作业正在执行", "筛选条件尚未设置")
                .doesNotContain("HAS_ITEMS", "HAS_UPLOAD", "start_export", "确认门");
        assertThat(modelText).isEqualTo("NOT APPROVED: HAS_ITEMS / HAS_UPLOAD / start_export");
    }

    @Test
    void preflightMissingTaskDoesNotClaimThatAJobIsRunning() {
        Map<String, Object> args = ToolResultUserText.preflightUserTextArgs(Map.of("checks", List.of(
                Map.of("code", "TASK_EXISTS", "pass", false),
                Map.of("code", "NO_JOB_IN_FLIGHT", "pass", false))));

        assertThat(args).containsEntry("reasons", List.of("PREPARATION_REQUIRED"));
        assertThat(ToolResultUserText.forUser("start_import", ErrorCodes.PRECHECK_FAILED, "raw", args))
                .doesNotContain("已有作业正在执行");
    }
}
