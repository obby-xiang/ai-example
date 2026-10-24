package com.example.configmgr.common;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 机器可读错误码的**唯一来源**。
 *
 * <p>
 * 为什么要单一源：此前码字面量散在六处载体（{@code ToolOutcome}、{@code ConflictException} 调用点、
 * AI 端点裸 {@code Map.of(...)}、SSE {@code error} 帧、可用性探针、以及各服务的
 * {@code IllegalArgumentException/IllegalStateException}），无人管集合 ⇒ 已实际漂移（前端镜像
 * 13 条里 3 条后端从未发过、后端 8 条冲突码前端一条没有）。前端 {@code types/api.ts#ErrorCode}、
 * {@code messages_zh/en.properties} 的 {@code error.<CODE>} 键集都以此表为准。
 *
 * <p>
 * 分组只为人读；判据是"码值"本身：
 * <ul>
 * <li>{@code HTTP_*}：业务信封与 AI 端点错误体；</li>
 * <li>{@code TOOL_*} / 工具结局：{@code ToolResultEnvelope.code}、SSE {@code tool_result.code}；</li>
 * <li>{@code DATA_*}：预检、表单、作业数据面；</li>
 * <li>{@code IN_*}：AI 配置（供应商/模型/密钥）写路径；</li>
 * <li>{@code INTERNAL_*}：内部错误（可出现在响应，但文案不承诺语义细节）。</li>
 * </ul>
 *
 * <p>
 * 纪律：**新增码只在本文件加一行**，再由 {@code ErrorCodesContractTest} 逼出前端镜像与 messages 键；
 * 其它文件只允许引用常量（{@code CODE_*} 别名或直接引用），不得再写字面量。
 */
public final class ErrorCodes {

    private ErrorCodes() {
    }

    // ---------------------------------------------------------------- HTTP 面

    /** 400：参数校验失败（请求体/字段级）。 */
    public static final String BAD_REQUEST = "BAD_REQUEST";
    /** 400：参数值非法（类型/取值不合法；无更具体码时的缺省）。 */
    public static final String ILLEGAL_ARGUMENT = "ILLEGAL_ARGUMENT";
    /** 400：状态不允许该操作（无更具体码时的缺省）。 */
    public static final String ILLEGAL_STATE = "ILLEGAL_STATE";
    /** 404：资源不存在（跨域缺省码；{@code ResourceNotFoundException#DEFAULT_CODE}）。 */
    public static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
    /** 409：同 sessionId 已有一轮在进行中。 */
    public static final String SESSION_BUSY = "SESSION_BUSY";
    /** 409：未知或已过期的 runId。 */
    public static final String UNKNOWN_RUN = "UNKNOWN_RUN";
    /** 409：未知或已过期的 toolCallId。 */
    public static final String UNKNOWN_TOOL_CALL = "UNKNOWN_TOOL_CALL";
    /** 409：同一 toolCallId 重复决策/回灌（幂等拒绝）。 */
    public static final String DUPLICATE_TOOL_CALL_ID = "DUPLICATE_TOOL_CALL_ID";
    /** 503：挂起专用线程池饱和。 */
    public static final String SUSPEND_POOL_SATURATED = "SUSPEND_POOL_SATURATED";
    /** 503：无 AI key，模型侧未装配。 */
    public static final String AI_UNAVAILABLE = "AI_UNAVAILABLE";
    /** 503：AI 运行时依赖的 Redis 不可用。 */
    public static final String AI_REDIS_UNAVAILABLE = "AI_REDIS_UNAVAILABLE";
    /** 200/健康面：AI 可用（不是错误码，但同一健康体用它表达"可用"）。 */
    public static final String AI_AVAILABLE = "AI_AVAILABLE";
    /** 429：限流。 */
    public static final String RATE_LIMITED = "RATE_LIMITED";
    /** 400：消息超长。 */
    public static final String MESSAGE_TOO_LONG = "MESSAGE_TOO_LONG";
    /** 400：拒绝原因超长（决策回灌；只回数字，不回显原因原文）。 */
    public static final String REASON_TOO_LONG = "REASON_TOO_LONG";
    /** 503：上下文计量不可用。 */
    public static final String AI_CONTEXT_UNAVAILABLE = "AI_CONTEXT_UNAVAILABLE";
    /** 500：未捕获异常。 */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    /** 400：{@code @Valid} 校验失败（字段级，message 里带字段名）。 */
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    // ---------------------------------------------------------------- 工具结局

    /** 工具抛异常且异常不携带机器可读码时的兜底码。 */
    public static final String TOOL_EXECUTION_FAILED = "TOOL_EXECUTION_FAILED";
    /** 官方执行器没有回填该 toolCallId 的结果。 */
    public static final String TOOL_RESULT_MISSING = "TOOL_RESULT_MISSING";
    /** 他实例已认领该调用，本实例不再执行。 */
    public static final String TOOL_ALREADY_CLAIMED = "TOOL_ALREADY_CLAIMED";
    /** 越 scope（防线③）。 */
    public static final String SCOPE_NOT_DISCLOSED = "SCOPE_NOT_DISCLOSED";
    /**
     * 表格工具被管理员关闸（{@code app.ai.sheet.read-enabled|write-enabled}；AI 表格工具治理批）。
     *
     * <p>与 {@link #SCOPE_NOT_DISCLOSED} 刻意分开：两者都是"未执行"，但一个是"你在这个页面不该用"
     * （模型/用户切页面可解），一个是"管理员把这个功能关了"（切页面无用，只能改配置重启）。
     * 复用越权码会让卡面显示「不在披露范围内」，把"功能被关"误导成"用户点错了"。
     */
    public static final String SHEET_DISABLED = "SHEET_DISABLED";
    /** 确认门超时。 */
    public static final String CONFIRM_TIMEOUT = "CONFIRM_TIMEOUT";
    /** 前端工具回灌超时。 */
    public static final String FRONTEND_TIMEOUT = "FRONTEND_TIMEOUT";
    /**
     * 后端工具**执行**超时（超时面治理批② 第 3 项；{@code app.ai.tool.exec-timeout}）。
     *
     * <p>与 {@link #FRONTEND_TIMEOUT} 分工：前者等"后端桩体跑完"（工具自己卡住），后者等"前端回执"
     * （回灌没回来）。到点 {@code executed=false}，轮继续（不判整轮失败）。
     */
    public static final String TOOL_TIMEOUT = "TOOL_TIMEOUT";
    /**
     * 工具调用步数到顶（超时面治理批② 第 2 项；{@code app.ai.resilience.max-tool-steps}）。
     *
     * <p>结局 {@code skipped}（不是 failed）：预算用完 ≠ 执行出错，不触发批次首错即停，
     * 本轮降级成「部分完成收尾」。
     */
    public static final String TOOL_STEP_LIMIT = "TOOL_STEP_LIMIT";
    /** 轮次被取消。 */
    public static final String RUN_CANCELLED = "RUN_CANCELLED";
    /** 前端工具被用户取消。 */
    public static final String FRONTEND_CANCELLED = "FRONTEND_CANCELLED";
    /** 上游批次确定性失败导致本调用未执行。 */
    public static final String UPSTREAM_FAILED = "UPSTREAM_FAILED";
    /** 只读预检未通过。 */
    public static final String PRECHECK_FAILED = "PRECHECK_FAILED";
    /** 预检基础设施不可用。 */
    public static final String PRECHECK_UNAVAILABLE = "PRECHECK_UNAVAILABLE";
    /** 目标资源正被同类动作占用。 */
    public static final String RESOURCE_LOCKED = "RESOURCE_LOCKED";
    /** 工具参数里的目标资源不成立。 */
    public static final String TARGET_INVALID = "TARGET_INVALID";
    /** 工具不在预检范围内。 */
    public static final String TOOL_NOT_PREFLIGHTABLE = "TOOL_NOT_PREFLIGHTABLE";
    /** 工具结果信封序列化失败。 */
    public static final String TOOL_ENVELOPE_SERIALIZE_FAILED = "TOOL_ENVELOPE_SERIALIZE_FAILED";
    /** 工具结果信封解析失败。 */
    public static final String TOOL_ENVELOPE_PARSE_FAILED = "TOOL_ENVELOPE_PARSE_FAILED";
    /** 工具结果帧的 ok/outcome 自相矛盾（编程/契约错误）。 */
    public static final String TOOL_RESULT_OUTCOME_MISMATCH = "TOOL_RESULT_OUTCOME_MISMATCH";

    // ---------------------------------------------------------------- 数据面

    /** 400：生成式表单回灌值不符 schema（挂起仍在等待，可改值重试）。 */
    public static final String FORM_RESULT_REJECTED = "FORM_RESULT_REJECTED";
    /** 400：生成式表单 schema 越白名单。 */
    public static final String FORM_SCHEMA_REJECTED = "FORM_SCHEMA_REJECTED";
    /** 400：预检查未通过。 */
    public static final String PRECHECK_NOT_PASSED = "PRECHECK_NOT_PASSED";
    /** 400：预检结果已过期。 */
    public static final String PRECHECK_STALE = "PRECHECK_STALE";
    /** 400：查询条件操作符未知。 */
    public static final String CONDITION_OPERATOR_UNKNOWN = "CONDITION_OPERATOR_UNKNOWN";
    /** 400：查询条件 JSON 非法。 */
    public static final String CONDITION_JSON_INVALID = "CONDITION_JSON_INVALID";
    /** 400：Excel 列映射无法解析。 */
    public static final String EXCEL_COLUMN_MAPPING_INVALID = "EXCEL_COLUMN_MAPPING_INVALID";
    /** 400：Excel 无数据工作表。 */
    public static final String EXCEL_SHEET_NOT_FOUND = "EXCEL_SHEET_NOT_FOUND";
    /** 400：仅支持 .xlsx / .zip。 */
    public static final String FILE_TYPE_UNSUPPORTED = "FILE_TYPE_UNSUPPORTED";
    /** 400：目标步骤为空。 */
    public static final String STEP_REQUIRED = "STEP_REQUIRED";
    /** 400：目标步骤格式非法。 */
    public static final String STEP_INVALID = "STEP_INVALID";
    /** 400：目标步骤不在该任务类型的步骤序列内。 */
    public static final String STEP_NOT_IN_SEQUENCE = "STEP_NOT_IN_SEQUENCE";
    /** 400：租约键参数非法（taskId/jobType 不成立）。 */
    public static final String LEASE_KEY_INVALID = "LEASE_KEY_INVALID";
    /** 400：导入模式只支持 MERGE / REPLACE。 */
    public static final String IMPORT_MODE_INVALID = "IMPORT_MODE_INVALID";

    // ---------------------------------------------------------------- 业务冲突（409）

    /** 409：作业已是终态。 */
    public static final String JOB_ALREADY_FINAL = "JOB_ALREADY_FINAL";
    /** 409：作业已在运行。 */
    public static final String JOB_ALREADY_RUNNING = "JOB_ALREADY_RUNNING";
    /** 409：任务已终态（不可再改）。 */
    public static final String TASK_FINALIZED = "TASK_FINALIZED";
    /** 409：配置定义编码重复。 */
    public static final String DEFINITION_CODE_DUPLICATE = "DEFINITION_CODE_DUPLICATE";
    /** 409：配置定义已有数据。 */
    public static final String DEFINITION_HAS_DATA = "DEFINITION_HAS_DATA";
    /** 409：配置定义被在途任务引用。 */
    public static final String DEFINITION_IN_ACTIVE_TASK = "DEFINITION_IN_ACTIVE_TASK";
    /** 409：配置定义被引用。 */
    public static final String DEFINITION_REFERENCED = "DEFINITION_REFERENCED";

    // ---------------------------------------------------------------- 定义校验（400）

    public static final String DEFINITION_FIELD_CODE_EMPTY = "DEFINITION_FIELD_CODE_EMPTY";
    public static final String DEFINITION_FIELD_CODE_INVALID = "DEFINITION_FIELD_CODE_INVALID";
    public static final String DEFINITION_FIELD_CODE_DUPLICATE = "DEFINITION_FIELD_CODE_DUPLICATE";
    public static final String DEFINITION_FIELD_REFERENCE_INCOMPLETE = "DEFINITION_FIELD_REFERENCE_INCOMPLETE";
    public static final String DEFINITION_PRIMARY_KEY_REQUIRED = "DEFINITION_PRIMARY_KEY_REQUIRED";
    /** 409/400：配置定义间循环依赖。 */
    public static final String DEFINITION_DEPENDENCY_CYCLE = "DEFINITION_DEPENDENCY_CYCLE";

    // ---------------------------------------------------------------- AI 配置（模型配置批①②）

    public static final String PROVIDER_NOT_FOUND = "PROVIDER_NOT_FOUND";
    public static final String PROVIDER_EXISTS = "PROVIDER_EXISTS";
    public static final String PROVIDER_IN_USE = "PROVIDER_IN_USE";
    public static final String MODEL_NOT_FOUND = "MODEL_NOT_FOUND";
    public static final String MODEL_EXISTS = "MODEL_EXISTS";
    public static final String MODELS_INVALID = "MODELS_INVALID";
    public static final String MODELS_UNSUPPORTED = "MODELS_UNSUPPORTED";
    public static final String INVALID_MODEL = "INVALID_MODEL";
    public static final String INVALID_PARAM = "INVALID_PARAM";
    public static final String INVALID_BASE_URL = "INVALID_BASE_URL";
    public static final String BLOCKED_PRIVATE_ENDPOINT = "BLOCKED_PRIVATE_ENDPOINT";
    public static final String AUTH_FAILED = "AUTH_FAILED";
    public static final String UNREACHABLE = "UNREACHABLE";
    public static final String CODE_IMMUTABLE = "CODE_IMMUTABLE";
    public static final String AI_SECRET_KEK_MISSING = "AI_SECRET_KEK_MISSING";
    /** 503：已配置的 provider 都没有可用 key（用户可到设置页录入）。 */
    public static final String AI_KEY_MISSING = "AI_KEY_MISSING";
    /**
     * 503：可用性判定时 DB 托管 key 解不开（KEK 缺失/变更或密文损坏）。
     *
     * <p>与 {@link #KEY_UNREADABLE} 刻意分开：后者的语义是"配置写/探测路径上这条 key 解不开"
     * （设置页按码给操作建议），本码是"AI 端点因此不可用"（/chat 503 + 前端引导到设置页）。
     * 两者同源（{@code AiKeyCipher}）但出口不同，混用会让用户不知道是"改配置"还是"整条 AI 停了"。
     */
    public static final String AI_KEY_UNREADABLE = "AI_KEY_UNREADABLE";
    public static final String KEY_EMPTY = "KEY_EMPTY";
    public static final String KEY_UNREADABLE = "KEY_UNREADABLE";

    // ---------------------------------------------------------------- 作业/SSE

    /** SSE error 帧：上游流异常。 */
    public static final String UPSTREAM_STREAM_ERROR = "UPSTREAM_STREAM_ERROR";
    /** SSE error 帧：空回复（去空白后为空，轮次终态 FAILED）。 */
    public static final String EMPTY_REPLY = "EMPTY_REPLY";
    /** 轮次终态：cancelled（{@code done{cancelled:true}}），不是 error 帧；永不重试。 */
    public static final String AI_RUN_CANCELLED = "AI_RUN_CANCELLED";
    /** SSE error 帧：上游首包超时。 */
    public static final String UPSTREAM_FIRST_BYTE_TIMEOUT = "UPSTREAM_FIRST_BYTE_TIMEOUT";
    /** SSE error 帧：上游事件间静默超时。 */
    public static final String UPSTREAM_SILENT_TIMEOUT = "UPSTREAM_SILENT_TIMEOUT";
    /** SSE error 帧：单轮总预算耗尽。 */
    public static final String TOTAL_BUDGET_EXCEEDED = "TOTAL_BUDGET_EXCEEDED";
    /** SSE error 帧：上游流提前结束（缺终帧）。 */
    public static final String UPSTREAM_STREAM_INCOMPLETE = "UPSTREAM_STREAM_INCOMPLETE";
    /** SSE error 帧：上游连接中断（reset / EOF / premature close）。 */
    public static final String UPSTREAM_CONNECTION_BROKEN = "UPSTREAM_CONNECTION_BROKEN";
    /** SSE error 帧：上下文超出模型窗口（重试无效，需压缩或新会话）。 */
    public static final String CONTEXT_OVERFLOW = "CONTEXT_OVERFLOW";

    // ---- 终局失败派生码（R-09）：基础码 + 已产出/副作用后缀 ----
    //
    // 为什么仍保留后缀而不是只发基础码：同一基础码在"还没产出任何东西"与"已经给用户看了半截
    // 正文 / 已经执行过工具"两种现场下，处置完全不同（能否重试、用户看到的内容是否可信）。
    // 后缀是**诊断事实**，不是第二套错误分类；用户面文案沿用基础码（前端剥后缀查表），
    // 码本身必须正式登记，否则前端镜像/messages 缺键，用户又看到裸码。

    public static final String UPSTREAM_STREAM_ERROR_AFTER_PARTIAL = "UPSTREAM_STREAM_ERROR_AFTER_PARTIAL";
    public static final String UPSTREAM_STREAM_ERROR_AFTER_TOOL_SIDE_EFFECT =
            "UPSTREAM_STREAM_ERROR_AFTER_TOOL_SIDE_EFFECT";
    public static final String UPSTREAM_CONNECTION_BROKEN_AFTER_PARTIAL =
            "UPSTREAM_CONNECTION_BROKEN_AFTER_PARTIAL";
    public static final String UPSTREAM_CONNECTION_BROKEN_AFTER_TOOL_SIDE_EFFECT =
            "UPSTREAM_CONNECTION_BROKEN_AFTER_TOOL_SIDE_EFFECT";
    public static final String UPSTREAM_STREAM_INCOMPLETE_AFTER_PARTIAL =
            "UPSTREAM_STREAM_INCOMPLETE_AFTER_PARTIAL";
    public static final String UPSTREAM_STREAM_INCOMPLETE_AFTER_TOOL_SIDE_EFFECT =
            "UPSTREAM_STREAM_INCOMPLETE_AFTER_TOOL_SIDE_EFFECT";
    public static final String UPSTREAM_SILENT_TIMEOUT_AFTER_PARTIAL =
            "UPSTREAM_SILENT_TIMEOUT_AFTER_PARTIAL";
    public static final String UPSTREAM_SILENT_TIMEOUT_AFTER_TOOL_SIDE_EFFECT =
            "UPSTREAM_SILENT_TIMEOUT_AFTER_TOOL_SIDE_EFFECT";
    public static final String UPSTREAM_FIRST_BYTE_TIMEOUT_AFTER_PARTIAL =
            "UPSTREAM_FIRST_BYTE_TIMEOUT_AFTER_PARTIAL";
    public static final String UPSTREAM_FIRST_BYTE_TIMEOUT_AFTER_TOOL_SIDE_EFFECT =
            "UPSTREAM_FIRST_BYTE_TIMEOUT_AFTER_TOOL_SIDE_EFFECT";
    public static final String TOTAL_BUDGET_EXCEEDED_AFTER_PARTIAL =
            "TOTAL_BUDGET_EXCEEDED_AFTER_PARTIAL";
    public static final String TOTAL_BUDGET_EXCEEDED_AFTER_TOOL_SIDE_EFFECT =
            "TOTAL_BUDGET_EXCEEDED_AFTER_TOOL_SIDE_EFFECT";
    public static final String CONTEXT_OVERFLOW_AFTER_PARTIAL = "CONTEXT_OVERFLOW_AFTER_PARTIAL";
    public static final String CONTEXT_OVERFLOW_AFTER_TOOL_SIDE_EFFECT =
            "CONTEXT_OVERFLOW_AFTER_TOOL_SIDE_EFFECT";
    /** SSE error 帧：轮次未捕获异常。 */
    public static final String AI_RUN_FAILED = "AI_RUN_FAILED";
    /** SSE error 帧：续跑失败。 */
    public static final String AI_RESUME_FAILED = "AI_RESUME_FAILED";

    // ---------------------------------------------------------------- 内部错误

    public static final String CONFIG_INVALID = "CONFIG_INVALID";
    public static final String SHA256_UNAVAILABLE = "SHA256_UNAVAILABLE";
    public static final String AI_SUMMARY_UNAVAILABLE = "AI_SUMMARY_UNAVAILABLE";
    public static final String STORE_IO_FAILED = "STORE_IO_FAILED";
    public static final String TASK_SETTINGS_UNREADABLE = "TASK_SETTINGS_UNREADABLE";

    /** 全部已登记码（测试与诊断面用；顺序 = 声明顺序，便于 diff）。 */
    public static final Set<String> ALL;

    static {
        Set<String> all = new LinkedHashSet<>();
        for (java.lang.reflect.Field field : ErrorCodes.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    || field.getType() != String.class) {
                continue;
            }
            try {
                all.add((String) field.get(null));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("ErrorCodes 常量不可读：" + field.getName(), e);
            }
        }
        ALL = Collections.unmodifiableSet(all);
    }
}
