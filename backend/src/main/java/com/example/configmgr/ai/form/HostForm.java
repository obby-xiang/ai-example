package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.RunContextRefresher;
import com.example.configmgr.ai.history.SyntheticMessages;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.ai.chat.messages.Message;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 宿主确定性表单的<b>共用挂起面</b>：两张常规宿主表单（选择配置项 / 任务类型）与
 * {@code confirm_step} 的条件兜底走的是<b>同一份</b>挂起、去重、序列化与失败收敛逻辑，
 * 只有"什么时候该发"这一条按节点各不相同。
 *
 * <h2>为什么抽出基类</h2>
 * 挂起面不是"发一帧"那么简单：外置挂起态先于任何帧、正文/推理元数据必须沿用、发帧失败不得交回
 * 模型（否则 SUSPENDED + 待决条目 + 模型在跑 = 双驱动）、写挂起失败必须把条目收敛成终态（否则
 * 一条看不见的 PENDING 会永远挡住续跑）。这些约束<b>逐字相同</b>；复制三份必然漂移 —— 修了一处
 * 忘了另两处，症状是"某张卡挂起时偶发双驱动/卡死"，且极难复现。故这里只留一份实现，子类只负责
 * <b>判据 + 表单正文</b>。
 *
 * <h2>复用的是既有机制，不新增中断路径</h2>
 * 挂起面与模型自己下发的 {@code generative_form} 完全一致：
 * {@link RunStore#saveSuspended} → {@code suspended} 帧 → {@link ConfirmGate#publishRequestsForParkedBatch}
 * → 本轮收流。用户的提交/取消走既有 {@code POST /api/ai/frontend-tool-result} 与既有续跑，
 * 值经既有回灌闸门（{@link GenerativeFormGuard#inspectResult}）复核后作为工具结果回填模型。
 *
 * <p><b>表单正文也要过同一份白名单</b>（{@link #formArguments}）：宿主自建的表单与模型自发的表单
 * 在 schema 上没有特权 —— 它同样必须满足 {@link GenerativeFormRules#argumentIssues}，否则不下发。
 * 这样"宿主表单"永远不可能成为绕过 DC-15 闸门的第二条路径。
 *
 * <p><b>只在 park 装配下生效</b>（{@code app.ai.park.enabled}，生产 yml = true）：阻塞式等待
 * （park=false）意味着"等人在同一个工作线程里"，把它用在这些流程上就是新的中断路径。
 */
@Slf4j
abstract class HostForm {

    private static final ObjectMapper JSON = new ObjectMapper();

    protected final RunStore store;

    protected final RunRegistry registry;

    protected final ConfirmGate gate;

    protected final MessageJsonCodec codec;

    protected final AiProperties properties;

    protected HostForm(RunStore store, RunRegistry registry, ConfirmGate gate, MessageJsonCodec codec,
                       AiProperties properties) {
        this.store = store;
        this.registry = registry;
        this.gate = gate;
        this.codec = codec;
        this.properties = properties;
    }

    /**
     * 宿主表单只在 park 装配下生效（见类注）。
     */
    protected boolean isParkEnabled() {
        return this.properties != null && this.properties.getPark().isEnabled();
    }

    /**
     * 表单参数（{@code {"form":…}}）的序列化与<b>白名单自检</b>。
     *
     * @return {@code empty} = 超长或未通过 schema 白名单（<b>不得</b>下发一张自己都过不了闸门的表）
     */
    protected Optional<String> formArguments(Map<String, Object> form) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("form", form);
        String json;
        try {
            json = JSON.writeValueAsString(arguments);
        } catch (Exception ex) {
            log.warn("host form serialization failed; not sent this time", ex);
            return Optional.empty();
        }
        if (json.length() > GenerativeFormRules.MAX_FORM_CHARS) {
            log.warn("host form length {} exceeds limit {}; not sent", json.length(), GenerativeFormRules.MAX_FORM_CHARS);
            return Optional.empty();
        }
        List<FormIssue> issues = GenerativeFormRules.argumentIssues(parseJson(json));
        if (!issues.isEmpty()) {
            // 宿主自己写坏的表单：宁可不下发，也不能让用户看到一张"点了也没用"的卡
            log.warn("host form failed whitelist validation; not sent codes={}", issues.stream().map(i -> i.code()).toList());
            return Optional.empty();
        }
        return Optional.of(json);
    }

    /**
     * 同一张宿主表单只挂一次：{@code toolCallId} 是确定性的，待决条目里已有同号即视为已挂过。
     *
     * <p>读不出来就不冒"重复弹表"的险：宁可不发，交回既有路径。
     */
    protected boolean hasIssued(String runId, String toolCallId) {
        List<PendingToolCall> pendings;
        try {
            pendings = this.store.pendingsLenient(runId);
        } catch (RuntimeException ex) {
            log.warn("could not read pending entry; host skips the form runId={}", runId, ex);
            return true;
        }
        for (PendingToolCall pending : ObjectUtils.defaultIfNull(pendings, List.<PendingToolCall>of())) {
            if (pending != null && toolCallId.equals(pending.getToolCallId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 既有挂起面（与模型自己下发的 {@code generative_form} 同一条路）：外置挂起态 → {@code suspended}
     * → 请求帧 → 本轮收流。顺序是硬要求（{@code saveSuspended} 先于任何帧）。
     *
     * <p><b>正文/推理元数据沿用当前快照</b>：{@code saveSuspended} 会把 {@code assistantContent} 与
     * {@code assistantMetadata} 一起覆盖写 —— 这里传 {@code ""}/{@code null} 等于抹掉模型刚产出的那一轮。
     * 挂起只该换"谁在等"，因此先读快照再原样带上，并用当前 emitter 的完整正文更新 parked fullText。
     *
     * @return {@code true} = 已挂起：调用方本拍<b>不得</b>再驱动模型（这一轮在等人填表）
     */
    protected boolean shouldPark(String runId, List<Message> history, PendingToolCall pending) {
        return shouldPark(runId, history, pending, null, null);
    }

    /**
     * 显式带上<b>当前模型 assistant 正文/元数据</b>的挂起（工具跳提前 park 用）。
     *
     * <p>既有签名（{@code assistantContent == null}）继续沿用快照里的正文，保持所有老调用零变化。
     * 工具执行点在 {@code confirm_step} 等提前 park 场景必须显式传本段正文：此刻快照里的
     * {@code assistantContent} 还是上一轮/空值，不传就会把模型刚产出的正文丢掉。
     */
    protected boolean shouldPark(String runId, List<Message> history, PendingToolCall pending, String assistantContent,
                                 Map<String, Object> assistantMetadata) {
        return shouldParkAll(runId, history, List.of(pending), assistantContent, assistantMetadata);
    }

    /**
     * 一次挂起<b>多条</b>前端条目（同一批请求帧整批公布）。除条目数量外与单条挂起逐字同语义：
     * 先外置、再 {@code suspended}、再逐条请求帧、最后收流；失败时全部收敛成终态，绝不留
     * "看不见的 PENDING"挡住续跑。
     *
     * <p>用于宿主一次性下发的多条结构化草案（例如多个 {@code defCodes} 的 {@code set_condition}）：
     * 逐条 {@code saveSuspended} 会互相覆盖 {@code inFlightToolCalls}，只留最后一条。
     */
    protected boolean shouldParkAll(String runId, List<Message> history, List<PendingToolCall> pendings) {
        return shouldParkAll(runId, history, pendings, null, null);
    }

    /**
     * @param assistantContent  {@code null} = 沿用快照正文（既有调用方）；非 {@code null} = 本段模型正文
     *                          （工具跳提前 park），原样写进 {@code saveSuspended} 的 {@code assistantContent}，
     *                          使取消/刷新/续跑都能从挂起快照恢复这段正文。
     * @param assistantMetadata 显式正文对应的元数据（推理/来源等）；与 {@code assistantContent} 同进同退。
     */
    protected boolean shouldParkAll(String runId, List<Message> history, List<PendingToolCall> pendings,
                                    String assistantContent, Map<String, Object> assistantMetadata) {
        if (CollectionUtils.isEmpty(pendings)) {
            return false;
        }
        if (!isParkStateSaved(runId, history, pendings, assistantContent, assistantMetadata)) {
            return false;
        }
        SseChatEmitter out;
        try {
            out = this.registry.of(runId);
            out.suspended(pendings, this.store.runKey(runId), this.store.pendingKey(runId),
                    this.store.instanceId());
        } catch (RuntimeException ex) {
            // 快照已成功挂起 ⇒ 这一拍<b>绝不能</b>交回模型（否则 SUSPENDED + 待决条目 + 模型在跑 =
            // 双驱动：同一轮的帧与历史会分叉）。发帧是观测面，失败只告警，等恢复。
            log.warn("host form parked but suspended frame publish failed (waiting for resume; model not driven this tick) runId={} toolCallIds={}",
                    runId, toolCallIds(pendings), ex);
            return true;
        }
        try {
            this.gate.publishRequestsForParkedBatch(runId, pendings);
        } catch (RuntimeException ex) {
            log.warn("host form parked but request frame publish failed (waiting for resume; model not driven this tick) runId={} toolCallIds={}",
                    runId, toolCallIds(pendings), ex);
            return true;
        }
        try {
            // "挂起即断"（B1）：请求帧送达后本轮订阅收流，用户提交/取消后再带 lastSeq 重连
            out.completeSubscriptions();
        } catch (RuntimeException ex) {
            // 收流失败不回退：条目与快照已就位，重挂/刷新仍能取到这张卡
            log.warn("host form parked but turn stream close failed (park state unaffected) runId={} toolCallIds={}", runId,
                    toolCallIds(pendings), ex);
        }
        log.info("host parked the form (waiting for user input) runId={} toolCallIds={}", runId, toolCallIds(pendings));
        return true;
    }

    private boolean isParkStateSaved(String runId, List<Message> history, List<PendingToolCall> pendings,
                                     String assistantContent, Map<String, Object> assistantMetadata) {
        try {
            RunSnapshot current = this.store.get(runId);
            boolean explicit = assistantContent != null;
            String content = explicit ? assistantContent : current == null ? null : current.getAssistantContent();
            Map<String, Object> metadata = explicit ? assistantMetadata
                    : current == null ? null : current.getAssistantMetadata();
            // 合成内部指令（尤其 condition handoff 的一次性 USER）只属于当前请求视图，
            // 不得随宿主表单挂起进入 messageJson/chat memory。
            List<Message> persistedHistory = SyntheticMessages.withoutCorrectionInstruction(history);
            String fullText = this.registry.of(runId).transcriptFullText();
            this.store.saveSuspended(runId, content, fullText, pendings, persistedHistory, this.codec, metadata);
            return true;
        } catch (RuntimeException ex) {
            // 写挂起态失败：<b>不得留下一条看不见的待决条目</b>（saveSuspended 先写条目再写快照，
            // 快照那一步失败时条目会留在 Redis 里；前端没有请求帧、用户看不到卡，而后续续跑会被
            // 既有 PENDING_UNRESOLVED 前置挡住 —— 一轮就此卡死）。这里尽力把它收敛成终态。
            for (PendingToolCall pending : pendings) {
                convergeAfterParkFailure(runId, pending, ex);
            }
            return false;
        }
    }

    private static List<String> toolCallIds(List<PendingToolCall> pendings) {
        List<String> ids = new java.util.ArrayList<>(pendings.size());
        for (PendingToolCall pending : pendings) {
            ids.add(pending.getToolCallId());
        }
        return ids.isEmpty() ? List.of() : ids;
    }

    /**
     * 挂起失败后的尽力收敛：把条目落成"未执行"的终态，避免它永远以 PENDING 挡住续跑。
     */
    private void convergeAfterParkFailure(String runId, PendingToolCall pending, RuntimeException cause) {
        log.warn("could not persist host form park (not parked this tick; continue on existing path) runId={} toolCallId={}", runId,
                pending.getToolCallId(), cause);
        try {
            pending.setStatus(PendingToolCall.BLOCKED);
            pending.setOutcome(ToolOutcome.FAILED.wire());
            pending.setCode("HOST_FORM_PARK_FAILED");
            pending.setReason("表单未能打开，本次未执行；请稍后重试。");
            pending.setResultText("表单未能打开，本次未执行；请稍后重试。");
            pending.setExecuted(false);
            pending.setResolvedAtMs(System.currentTimeMillis());
            this.store.putPending(runId, pending);
        } catch (RuntimeException nested) {
            log.warn("entry convergence after park failure also failed (entry stays PENDING) runId={} toolCallId={}", runId,
                    pending.getToolCallId(), nested);
        }
    }

    /**
     * 待决条目的形状（与模型自发的 {@code generative_form} 同形）：前端工具、非不可逆。
     */
    protected static PendingToolCall frontendForm(String toolCallId, String arguments) {
        return frontendTool(toolCallId, GenerativeFormRules.TOOL_NAME, arguments);
    }

    /**
     * 机器执行类前端工具（{@code navigate_to} / {@code select_definitions} / {@code confirm_step}
     * 等，<b>没有可点的人工卡片</b>）的到期时刻：与确认门共用同一份
     * {@link ConfirmGate#suspendWaitSeconds(PendingToolCall)}（默认 {@code app.ai.hitl.frontend-tool-timeout}），
     * 避免这类条目以永久 PENDING 挡住续跑。
     *
     * <p>等人填写的表单（{@code generative_form}）不走这里：它们的等待语义是"没有上限"，
     * 由 {@code suspendWaitSeconds} 按工具名返回 {@code null} 自然保持。
     */
    protected PendingToolCall withToolTimeout(PendingToolCall pending) {
        if (pending == null || this.gate == null) {
            return pending;
        }
        Integer waitSeconds = this.gate.suspendWaitSeconds(pending);
        if (waitSeconds != null && waitSeconds > 0) {
            long now = System.currentTimeMillis();
            pending.setAtMs(now);
            pending.setExpiresAt(now + waitSeconds * 1000L);
        }
        return pending;
    }

    /**
     * 任意前端工具的确定性待决条目（宿主代替模型下发时用，例如结构化 {@code set_condition} 草案）。
     * 形状与模型自己的调用一致：前端通道、可逆、非确认门。
     */
    protected static PendingToolCall frontendTool(String toolCallId, String name, String arguments) {
        PendingToolCall pending = new PendingToolCall();
        pending.setToolCallId(toolCallId);
        pending.setName(name);
        pending.setType("function");
        pending.setArguments(arguments);
        pending.setKind(PendingToolCall.KIND_FRONTEND);
        // 前端动作是"收集/应用值"，不是不可逆写：不要不确定性的复选框语义
        pending.setIrreversible(false);
        return pending;
    }

    /**
     * 本轮是否已创建任务（AR-03 的同一份台账口径）；回执里没有任务号 = 不算"新任务链"。
     *
     * <p>{@code create_task} 成功是"用户主动新建"的唯一证据：普通浏览/打开既有任务不会产生它。
     */
    protected Long createdTaskInRun(String runId) {
        List<Map<String, Object>> ledger;
        try {
            ledger = this.store.ledger(runId);
        } catch (RuntimeException ex) {
            log.warn("could not read this turn's ledger; host skips the form runId={}", runId, ex);
            return null;
        }
        if (CollectionUtils.isEmpty(ledger)) {
            return null;
        }
        for (Map<String, Object> record : ledger) {
            if (record == null || !RunContextRefresher.TOOL_CREATE_TASK.equals(record.get("name"))) {
                continue;
            }
            if (!ToolOutcome.OK.wire().equals(String.valueOf(record.get("outcome")))) {
                continue;
            }
            Long taskId = positiveTaskId(record.get(PendingToolCall.CREATED_TASK_ID));
            if (taskId != null) {
                return taskId;
            }
        }
        return null;
    }

    /**
     * 台账 JSON Long 可为字符串；只接受纯十进制正整数，拒绝有损或超范围转换。
     */
    private static Long positiveTaskId(Object raw) {
        if (!(raw instanceof Number) && !(raw instanceof String)) {
            return null;
        }
        String value = raw.toString();
        if (!NumberUtils.isDigits(value)) {
            return null;
        }
        long taskId = NumberUtils.toLong(value, -1L);
        return taskId > 0L ? taskId : null;
    }

    /**
     * 本批已经有模型自己的同名调用：宿主不再重复（同一缺口只问一次）。
     */
    protected static boolean isHandledInBatch(List<PendingToolCall> settledBatch, String... toolNames) {
        if (settledBatch == null || toolNames == null) {
            return false;
        }
        for (PendingToolCall settled : settledBatch) {
            if (settled == null || settled.getName() == null) {
                continue;
            }
            for (String toolName : toolNames) {
                if (toolName.equals(settled.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    protected static JsonNode parseJson(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            return JSON.readTree(json);
        } catch (Exception ex) {
            return null;
        }
    }

}
