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
    /**
     * 410：该 sessionId 已被会话销毁，不能继续以同 id 开新轮。
     *
     * <p>与 {@link #SESSION_BUSY}（409，进行中可重挂）区分：410 是终态，唯一出路是新建对话换新 id。
     * 判定在创建 runId / 占会话门之前，命中时不写任何运行态。
     */
    public static final String SESSION_PURGED = "SESSION_PURGED";
    /** 409：未知或已过期的 runId。 */
    public static final String UNKNOWN_RUN = "UNKNOWN_RUN";
    /** 409：未知或已过期的 toolCallId。 */
    public static final String UNKNOWN_TOOL_CALL = "UNKNOWN_TOOL_CALL";
    /** 409：同一 toolCallId 重复决策/回灌（幂等拒绝）。 */
    public static final String DUPLICATE_TOOL_CALL_ID = "DUPLICATE_TOOL_CALL_ID";
    /**
     * 409：外部输入走错了入口（入口 kind 校验批）。
     *
     * <p>两条人工入口写的是<b>同一张</b> {@code ai:pending} 表：{@code /api/ai/confirm} 只应改写
     * {@code kind=CONFIRM} 的条目，{@code /api/ai/frontend-tool-result} 只应改写 {@code kind=FRONTEND}
     * 的条目。错入口会把人根本没点过的确认卡写成"前端已回灌"，或把前端条目写成"已批准"
     * （续跑时被当成人批准过）。判据取<b>服务端条目</b>上的 {@code kind}，客户端传什么都不信；
     * {@code kind=null}（旧数据）按"缺证据不授权"同样回本码（见各入口的 WARN）。
     *
     * <p>与 {@link #UNKNOWN_TOOL_CALL}/{@link #DUPLICATE_TOOL_CALL_ID} 分工：那两个是"id 不存在/已决"，
     * 本码是"id 存在但不属于这条入口" —— 状态与条目一个字节都没变。
     */
    public static final String TOOL_KIND_MISMATCH = "TOOL_KIND_MISMATCH";
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
    /**
     * 400：前端工具回灌的<b>结构化结果</b>超过单条字符上限
     * （{@code app.ai.result-budget.callback.max-chars}）。
     *
     * <p>与 {@link #MESSAGE_TOO_LONG} 同一姿态（拒绝不截断），但拒绝面不同：普通文本回灌超限
     * 仍是<b>截断 + 标记</b>（拒绝回灌会让这一轮永久挂起），只有结构化结果（JSON 对象/数组、
     * 表格写工具的结果文本）超限才走本码 —— 字符级截断会切坏 JSON 结构、切掉表格「共 N 格」尾行，
     * 半截结果落库比"让客户端改小重试"危险得多。挂起保持 {@code PENDING}、不落任何条目。
     */
    public static final String FRONTEND_RESULT_TOO_LARGE = "FRONTEND_RESULT_TOO_LARGE";
    /** 503：上下文计量不可用。 */
    public static final String AI_CONTEXT_UNAVAILABLE = "AI_CONTEXT_UNAVAILABLE";
    /** 400：AI 对话入口缺少租户/用户身份头（或格式非法/系统账号走 HTTP）——fail-closed。 */
    public static final String TENANT_IDENTITY_REQUIRED = "TENANT_IDENTITY_REQUIRED";
    /** 400：身份头齐全但该 (tenantId,userId) 组合不在预置目录内（含跨租户拼装）。 */
    public static final String TENANT_IDENTITY_UNKNOWN = "TENANT_IDENTITY_UNKNOWN";
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
    /**
     * 轮内上下文预算耗尽：armed 后请求的工具未执行，结局 skipped，使用已有信息收尾。
     *
     * <p>与 {@link #TOOL_STEP_LIMIT} 区分：一个是步数耗尽，一个是上下文放不下了。
     */
    public static final String TOOL_BUDGET_EXHAUSTED = "TOOL_BUDGET_EXHAUSTED";
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
    /** 同一轮第二次 create_task：直接拒绝创建（未执行），下一轮可再创建。 */
    public static final String CREATE_TASK_TURN_LIMIT = "CREATE_TASK_TURN_LIMIT";
    /** 同一轮的创建幂等键冲突（本轮已创建过任务）。 */
    public static final String CREATE_TASK_CONFLICT = "CREATE_TASK_CONFLICT";
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
    /** 生成式表单的槽位已由任务/工作区或用户明确值确定：冗余收集表单不下发（零副作用）。 */
    public static final String FORM_SLOTS_ALREADY_KNOWN = "FORM_SLOTS_ALREADY_KNOWN";
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
    /**
     * 503：provider 与 key 都在，但默认目标解析不出<b>该 provider 自己的</b>模型。
     *
     * <p>与 {@link #MODEL_NOT_FOUND}（请求侧显式模型不可用，400）刻意分开：本码是"没配置默认模型/
     * 默认模型已被停用或删除"，入口在开流之前 503，用户去设置页补模型即可。绝不回落到别家
     * provider 的模型名（那正是"选 B 却打 A"的成因）。
     */
    public static final String NO_MODEL_CONFIGURED = "NO_MODEL_CONFIGURED";
    public static final String MODEL_EXISTS = "MODEL_EXISTS";
    public static final String MODELS_INVALID = "MODELS_INVALID";
    public static final String MODELS_UNSUPPORTED = "MODELS_UNSUPPORTED";
    public static final String INVALID_MODEL = "INVALID_MODEL";
    public static final String INVALID_PARAM = "INVALID_PARAM";
    /**
     * 400：provider 的 key 与主机（origin）绑定 —— 改主机名却保留旧 key 被拒（复核 ID-03 / I-2）。
     *
     * <p>
     * 与 {@link #INVALID_PARAM} 分工：后者是"入参/取值范围不合法"的通用校验码；本码是<b>可自解释的
     * 操作指引</b>（"改主机需重新录入 key 或先清空"）。前端按码查表，因此它必须在
     * {@code ai.settings.errors.*} 有专属文案，否则用户只会看到一条与"重录 key"无关的通用提示。
     *
     * <p>
     * 语义边界：只拦"换 origin（scheme/host/port）且不重录 key"。同 origin 的改名/改路径/改开关不受影响，
     * 任意 OpenAI 兼容端点仍可配置（换新端点时重录 key 即可）。
     */
    public static final String KEY_BOUND_TO_HOST = "KEY_BOUND_TO_HOST";
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
    /**
     * SSE error 帧：上游按"请求本身不成立"拒绝（400/404/409/422 及其它未列 4xx）。
     *
     * <p>与 {@link #CONTEXT_OVERFLOW} 分工：越窗有专属压缩路径；本码是"同一份请求再发一次
     * 只会再被拒"的其余 4xx —— 不重试，用户需要改参数/配置。
     */
    public static final String UPSTREAM_REJECTED = "UPSTREAM_REJECTED";
    /** SSE error 帧：上游鉴权失败（401/403）；不重试，用户需要检查/更新 API Key。 */
    public static final String UPSTREAM_AUTH_FAILED = "UPSTREAM_AUTH_FAILED";
    /**
     * SSE error 帧：上游限流（429）。
     *
     * <p>有界重试，且遵守响应头 {@code Retry-After}（秒或 HTTP-date）：等待时长取
     * max(指数退避, Retry-After)，再由"未产出 / 无副作用 / 剩余预算"三条件裁剪。
     */
    public static final String UPSTREAM_RATE_LIMITED = "UPSTREAM_RATE_LIMITED";
    /**
     * SSE error 帧：上游暂时不可用（408 / 5xx / 网络层）。
     *
     * <p>与 {@link #UPSTREAM_CONNECTION_BROKEN} 分工：后者是"流已在途但连接断了"，
     * 本码是"这一轮请求根本没拿到可用响应"（网关超时/服务端 5xx）。
     */
    public static final String UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE";
    /**
     * SSE error 帧：上游以 {@code finish_reason=content_filter} 收尾 —— 明确失败，不重试。
     *
     * <p>即便已有半截正文也按失败收尾：被安全策略拦截的内容不应被当作"完成"。
     */
    public static final String CONTENT_FILTERED = "CONTENT_FILTERED";

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
    /**
     * 终局失败派生码：工具预算耗尽（收尾指令被连续无视）+ <b>本轮已执行过工具</b>。
     *
     * <p>为什么需要它（C13）：{@code TOOL_BUDGET_EXHAUSTED} 原先只在"轮内拦下某一步"时出现，
     * 作为<b>整轮失败码</b>是 C13 新引入的（收尾被无视不再冒充 {@code CONTEXT_OVERFLOW}）。
     * 派生规则与既有 {@code *_AFTER_TOOL_SIDE_EFFECT} 同源：{@code failureCode(base, sideEffect, emitted)}
     * 只会返回<b>已登记</b>的组合，未登记就退回基础码 —— 所以这里必须显式登记，否则"本轮已执行工具"
     * 这条现场信息会被静默丢掉，用户会按"重试一次就好"处理一件已经产生副作用的事。
     */
    public static final String TOOL_BUDGET_EXHAUSTED_AFTER_TOOL_SIDE_EFFECT =
            "TOOL_BUDGET_EXHAUSTED_AFTER_TOOL_SIDE_EFFECT";
    /** SSE error 帧：轮次未捕获异常。 */
    public static final String AI_RUN_FAILED = "AI_RUN_FAILED";
    /** SSE error 帧：续跑失败。 */
    public static final String AI_RESUME_FAILED = "AI_RESUME_FAILED";
    /**
     * SSE error 帧：续跑被<b>确定</b>拒绝 —— 快照绑定的 provider/模型此刻已不可用（删除/停用/模型下架）。
     *
     * <p>
     * 与 {@link #AI_RESUME_FAILED} 分工：后者是"续跑执行失败"（可重试的通用失败），本码是
     * <b>终态语义</b> —— 这一轮不能按原模型继续，也不会跨 provider 换模型；快照随之为 {@code FAILED}，
     * 前端应引导用户重新发一轮，而不是重试续跑。
     */
    public static final String AI_PROVIDER_GONE = "AI_PROVIDER_GONE";

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
