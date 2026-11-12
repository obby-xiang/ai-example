package com.example.configmgr.ai.tool;

import com.example.configmgr.common.ErrorCodes;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;

import java.util.Map;

/**
 * 工具结果的两份人读文案（2026-10-26 批次 6C / N9）。
 *
 * <p><b>模型面</b>是 {@code ToolResponseMessage} 信封里的 {@code message}：保留可行动指令与排障码，
 * 模型据此收尾或改道。<b>用户面</b>是 {@code tool_result} 帧的 {@code result} 与 {@code /history}
 * 的 {@code responseData}：只回答"发生了什么、接下来怎么办"，不出现 {@code app.ai.*} 配置键、
 * 内部接口路径、实例/轮次标识，也不出现"请直接用已经获得的信息…"这类说给模型的指令。
 *
 * <p>机器可读真值（{@code outcome}/{@code code}/{@code retryable}）不经过本类，仍由
 * {@link ToolResultEnvelope} 原样下发；本类按 {@code code} 与结构化业务事实选择用户摘要，
 * 并把命中摘要的稳定码作为 {@code userTextCode} 供前端本地化；中文摘要继续作为回退。
 * <b>不认识的码一律回落到原文</b>（业务失败原因不做改写）；{@code create_task} 成功结果
 * 只投影 {@code target.taskId} 与 {@code openWizard}，不从任何语言的回执文本反解任务事实。
 *
 * <p><b>诊断面不丢</b>：原文仍在模型记忆、执行台账与服务端日志里；用户通道不额外挂
 * {@code detail} 字段 —— 内部路径与实例标识不因为"换个字段"就变成可以外发的信息。
 */
public final class ToolResultUserText {

    public static final String CREATE_TASK_SUCCESS = "CREATE_TASK_SUCCESS";

    public static final String CREATE_TASK_CREATE_ONLY = "CREATE_TASK_CREATE_ONLY";

    private static final String TOOL_CREATE_TASK = "create_task";

    private static final String TASK_ID = "taskId";

    private static final String OPEN_WIZARD = "openWizard";

    /**
     * 内部/降级码 → 用户摘要。措辞只讲事实与下一步，不复述配置键、接口路径或模型指令。
     */
    private static final Map<String, String> SUMMARIES = Map.ofEntries(
            Map.entry(ErrorCodes.TOOL_STEP_LIMIT,
                    "本轮工具调用次数已用完，这一步没有执行；AI 会用已有信息继续回答。"),
            Map.entry(ErrorCodes.TOOL_BUDGET_EXHAUSTED,
                    "本轮对话上下文已满，这一步没有执行；AI 会用已有信息继续回答。"),
            Map.entry(ErrorCodes.UPSTREAM_FAILED,
                    "上一个步骤没有成功，这一步没有执行；AI 会用已有信息继续回答。"),
            Map.entry(ErrorCodes.CREATE_TASK_TURN_LIMIT,
                    "同一轮只允许创建一次任务，这一次没有再创建；需要新任务请在下一轮提出。"),
            Map.entry(ErrorCodes.RESOURCE_LOCKED,
                    "该任务已有作业在执行，本次未执行；请等待作业结束后再试。"),
            Map.entry(ErrorCodes.TOOL_KIND_MISMATCH,
                    "这一步没有执行；该操作需要由当前页面完成。"),
            Map.entry(ErrorCodes.SHEET_DISABLED,
                    "表格功能已被管理员关闭，这一步没有执行。"),
            Map.entry(ErrorCodes.SCOPE_NOT_DISCLOSED,
                    "当前页面或步骤不支持这个操作，这一步没有执行。"),
            Map.entry(ErrorCodes.TOOL_TIMEOUT,
                    "这一步没有在时限内完成，结果尚未确认；该操作可能已产生部分变化，请核对后再继续。"),
            Map.entry(ErrorCodes.TOOL_ALREADY_CLAIMED,
                    "这一步已由其他执行实例接管，本次没有重复执行。"),
            Map.entry(ErrorCodes.TOOL_EXECUTION_FAILED,
                    "操作未能完成，可能已有部分变化；请核对当前状态后再继续，或联系管理员。"),
            Map.entry("HOST_FORM_PARK_FAILED", "表单未能打开，本次未执行；请稍后重试。"),
            Map.entry(WizardActionGuard.REJECTED_CODE,
                    "参数不符合当前向导操作要求，本次未执行；请核对配置项和筛选条件后重试。"),
            Map.entry("SHEET_ARGUMENTS_REJECTED",
                    "表格参数不完整或超出允许范围，本次未执行；请调整后重试。"),
            Map.entry("UI_PRIMITIVE_ARGUMENTS_REJECTED",
                    "界面操作参数不符合要求，本次未执行；请重新获取界面快照后再试。"));

    private ToolResultUserText() {
    }

    /** 命中用户摘要时复用原机器码作为本地化键；原文透传时没有摘要码。 */
    public static String userTextCode(String code) {
        return code != null && SUMMARIES.containsKey(code) ? code : null;
    }

    /** 创建任务摘要码只认结构化任务号与打开向导参数；复用任务沿用创建成功摘要。 */
    public static String userTextCode(String toolName, String code, Map<String, Object> structuredArgs) {
        if (TOOL_CREATE_TASK.equals(toolName) && StringUtils.isBlank(code) && positiveTaskId(structuredArgs) != null) {
            return isCreateOnly(structuredArgs) ? CREATE_TASK_CREATE_ONLY : CREATE_TASK_SUCCESS;
        }
        return userTextCode(code);
    }

    /**
     * 用户面文案：命中内部码时返回摘要，否则原样返回（{@code null} 也原样）。
     *
     * @param code      信封里的机器可读码（可为 null = 成功/无码）
     * @param modelText 模型面原文（{@code resultText} / 信封 {@code message}）
     */
    public static String forUser(String code, String modelText) {
        return forUser(null, code, modelText, null);
    }

    /**
     * @param structuredArgs 实时帧取 {@code target + userTextArgs}，历史取信封 {@code target}；
     *                       缺失时保持通用摘要或原文，不从 {@code modelText} 反解。
     */
    public static String forUser(String toolName, String code, String modelText, Map<String, Object> structuredArgs) {
        Long taskId = positiveTaskId(structuredArgs);
        if (StringUtils.isBlank(code)) {
            if (TOOL_CREATE_TASK.equals(toolName) && taskId != null) {
                return "任务 #" + taskId + (isCreateOnly(structuredArgs)
                        ? " 已创建，未自动打开向导，也未启动作业；以后可继续配置"
                        : " 已创建，尚未启动作业；后续由你决定是否配置");
            }
            return modelText;
        }
        if (ErrorCodes.CREATE_TASK_TURN_LIMIT.equals(code) && taskId != null) {
            return "本轮已创建任务 #" + taskId + "；同一轮只允许创建一次任务，这一次没有再创建。";
        }
        String summary = SUMMARIES.get(code);
        return summary == null ? modelText : summary;
    }

    private static boolean isCreateOnly(Map<String, Object> structuredArgs) {
        return structuredArgs != null && Boolean.FALSE.equals(structuredArgs.get(OPEN_WIZARD));
    }

    private static Long positiveTaskId(Map<String, Object> structuredArgs) {
        Object raw = structuredArgs == null ? null : structuredArgs.get(TASK_ID);
        long taskId = raw == null ? 0L : NumberUtils.toLong(StringUtils.trim(String.valueOf(raw)), 0L);
        return taskId > 0L ? taskId : null;
    }

}
