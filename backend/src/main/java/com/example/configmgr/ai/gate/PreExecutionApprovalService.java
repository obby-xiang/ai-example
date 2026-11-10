package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * DANGER+FRONTEND 工具的独立审批 A 与原执行 E 的通用链接协作者。
 * 本类不分发前端请求、不重建模型历史；A 也永不作为后端工具执行。
 */
@Component
public class PreExecutionApprovalService {

    private static final Set<String> FRONTEND_TERMINAL_STATUSES = Set.of(
            PendingToolCall.FRONTEND_RESULT, PendingToolCall.FRONTEND_CANCELLED, PendingToolCall.CANCELLED,
            PendingToolCall.REJECTED, PendingToolCall.TIMEOUT, PendingToolCall.REJECTED_ARGUMENTS,
            PendingToolCall.BLOCKED, PendingToolCall.SKIPPED, PendingToolCall.PRECHECK_FAILED,
            PendingToolCall.SHEET_DISABLED);

    private static final String DEFAULT_REJECTION_REASON = "用户在确认门中拒绝";

    private static final String REJECTED_RESULT_TEXT = "用户拒绝了该操作，工具未执行。";

    private static final String REJECTED_RESULT_TEXT_PREFIX = "用户拒绝了该操作，工具未执行。拒绝原因：";

    private final RunStore store;

    private final ToolRegistry toolRegistry;

    public PreExecutionApprovalService(RunStore store, ToolRegistry toolRegistry) {
        this.store = Objects.requireNonNull(store, "store");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
    }

    public boolean isPreExecutionApprovalRequired(String toolName) {
        return this.toolRegistry.riskOf(toolName) == ToolMeta.RiskLevel.DANGER
                && this.toolRegistry.channelOf(toolName) == ToolMeta.Channel.FRONTEND;
    }

    /** 从保留原 toolCallId 的 E 创建独立 A，并写入双向链接。 */
    public ApprovalPair createPair(String runId, PendingToolCall execution) {
        requireNewExecution(runId, execution);
        PendingToolCall approval = new PendingToolCall();
        approval.setToolCallId(UUID.randomUUID().toString());
        approval.setName(execution.getName());
        approval.setType(StringUtils.defaultIfBlank(execution.getType(), "function"));
        approval.setArguments(execution.getArguments());
        approval.setKind(PendingToolCall.KIND_CONFIRM);
        approval.setIrreversible(execution.isIrreversible());
        approval.setExecutionToolCallId(execution.getToolCallId());
        if (execution.isPreflightPresent()) {
            approval.setPreflight(execution.getPreflight());
        }
        execution.setPreExecutionApprovalId(approval.getToolCallId());
        execution.setReq(false);
        return validateLinked(runId, approval, execution);
    }

    /** 从任一已持久化成员解析另一方；任何链接损坏都抛异常，调用方不得继续执行。 */
    public ApprovalPair requireLinked(String runId, PendingToolCall source) {
        if (source == null || StringUtils.isBlank(runId) || StringUtils.isBlank(source.getToolCallId())) {
            throw invalidLink("source or run is missing");
        }
        PendingToolCall persisted = requireTarget(runId, source.getToolCallId());
        if (PendingToolCall.KIND_CONFIRM.equals(persisted.getKind())) {
            String executionId = requireLink(persisted.getExecutionToolCallId(), "approval execution link");
            return validateLinked(runId, persisted, requireTarget(runId, executionId));
        }
        if (PendingToolCall.KIND_FRONTEND.equals(persisted.getKind())) {
            String approvalId = requireLink(persisted.getPreExecutionApprovalId(), "execution approval link");
            return validateLinked(runId, requireTarget(runId, approvalId), persisted);
        }
        throw invalidLink("source kind must be CONFIRM or FRONTEND");
    }

    /** 校验 A/E 链接、审批载荷及 A 的完整零执行证据。 */
    public ApprovalPair validateLinked(String runId, PendingToolCall approval, PendingToolCall execution) {
        if (StringUtils.isBlank(runId) || approval == null || execution == null) {
            throw invalidLink("linked target or run is missing");
        }
        if (!PendingToolCall.KIND_CONFIRM.equals(approval.getKind())
                || !PendingToolCall.KIND_FRONTEND.equals(execution.getKind())) {
            throw invalidLink("linked entry kind is invalid");
        }
        if (StringUtils.isBlank(approval.getToolCallId()) || StringUtils.isBlank(execution.getToolCallId())
                || approval.getToolCallId().equals(execution.getToolCallId())) {
            throw invalidLink("approval and execution ids must be distinct and present");
        }
        if (!execution.getToolCallId().equals(approval.getExecutionToolCallId())
                || !approval.getToolCallId().equals(execution.getPreExecutionApprovalId())) {
            throw invalidLink("bidirectional links are inconsistent");
        }
        if (StringUtils.isNotBlank(approval.getPreExecutionApprovalId())
                || StringUtils.isNotBlank(execution.getExecutionToolCallId())) {
            throw invalidLink("link fields are assigned to the wrong entry kind");
        }
        if (!isPreExecutionApprovalRequired(approval.getName())
                || !StringUtils.equals(approval.getName(), execution.getName())
                || !StringUtils.equals(approval.getArguments(), execution.getArguments())
                || !StringUtils.equals(StringUtils.defaultIfBlank(approval.getType(), "function"),
                StringUtils.defaultIfBlank(execution.getType(), "function"))) {
            throw invalidLink("linked tool declaration or arguments are inconsistent");
        }
        if (approval.isIrreversible() != execution.isIrreversible()
                || approval.isPreflightPresent() != execution.isPreflightPresent()
                || !approval.getPreflight().equals(execution.getPreflight())) {
            throw invalidLink("linked approval metadata is inconsistent");
        }
        if (this.store.hasExecutionEvidence(runId, approval)) {
            throw invalidLink("approval must not have execution evidence");
        }
        return new ApprovalPair(approval, execution);
    }

    /** ConfirmGate 已拒绝 A 后，以 RunStore 决策 CAS 幂等收敛尚未下发的 E。 */
    public ApprovalPair settleRejection(String runId, String approvalId, String reason) {
        ApprovalPair pair = requireLinked(runId, requireTarget(runId, approvalId));
        PendingToolCall approval = pair.approval();
        PendingToolCall execution = pair.execution();
        requireRejectedTerminal(approval);
        requireNoExecutionEvidence(runId, execution);
        if (Boolean.TRUE.equals(execution.getReq())) {
            throw invalidLink("execution request was already emitted");
        }
        if (PendingToolCall.REJECTED.equals(execution.getStatus())) {
            requireRejectedTerminalPair(approval, execution);
            return pair;
        }
        if (!PendingToolCall.PENDING.equals(execution.getStatus())) {
            throw invalidLink("execution is no longer eligible for rejection settlement");
        }
        String effectiveReason = StringUtils.defaultIfBlank(approval.getReason(), reason);
        effectiveReason = StringUtils.defaultIfBlank(effectiveReason, DEFAULT_REJECTION_REASON);
        settleRejected(execution, effectiveReason, System.currentTimeMillis());
        RunStore.DecisionClaim claim = this.store.tryApplyDecision(runId, execution);
        if (claim == null) {
            throw invalidLink("execution rejection CAS returned no result");
        }
        if (!claim.won()) {
            ApprovalPair current = requireLinked(runId, requireTarget(runId, approvalId));
            requireNoExecutionEvidence(runId, current.execution());
            if (Boolean.TRUE.equals(current.execution().getReq())) {
                throw invalidLink("execution rejection CAS conflicts with an emitted request");
            }
            requireRejectedTerminalPair(current.approval(), current.execution());
            return current;
        }
        return pair;
    }

    /** E 形成白名单终局后校验 A 的完成投影；首次写入必须有匹配 expected-status CAS。 */
    public ApprovalPair convergeCompletion(String runId, String executionToolCallId) {
        ApprovalPair pair = requireLinked(runId, requireTarget(runId, executionToolCallId));
        PendingToolCall approval = pair.approval();
        PendingToolCall execution = pair.execution();
        String executionStatus = execution.getStatus();
        if (executionStatus == null || !FRONTEND_TERMINAL_STATUSES.contains(executionStatus)) {
            throw invalidLink("execution has no valid FRONTEND terminal outcome");
        }
        if (StringUtils.equals(executionStatus, approval.getStatus())) {
            if (PendingToolCall.REJECTED.equals(executionStatus)) {
                requireRejectedTerminalPair(approval, execution);
            }
            if (!isCompletionProjection(approval, execution)) {
                throw invalidLink("approval completion projection is inconsistent");
            }
            return pair;
        }
        if (!PendingToolCall.APPROVED.equals(approval.getStatus())
                || PendingToolCall.REJECTED.equals(executionStatus)) {
            throw invalidLink("approval state is inconsistent with execution completion");
        }
        throw invalidLink("completion requires an APPROVED expected-status CAS, which RunStore does not expose");
    }

    private static boolean isCompletionProjection(PendingToolCall approval, PendingToolCall execution) {
        return approval.getResolvedAtMs() == execution.getResolvedAtMs()
                && isResolvedAtConsistent(approval) && isResolvedAtConsistent(execution)
                && StringUtils.equals(approval.getOutcome(), execution.getOutcome())
                && StringUtils.equals(approval.getCode(), execution.getCode())
                && StringUtils.equals(approval.getReason(), execution.getReason())
                && StringUtils.equals(approval.getResultText(), execution.getResultText());
    }

    private static void requireRejectedTerminal(PendingToolCall pending) {
        if (!isRejectedTerminal(pending)) {
            throw invalidLink("rejected terminal state is inconsistent");
        }
    }

    private static void requireRejectedTerminalPair(PendingToolCall approval, PendingToolCall execution) {
        requireRejectedTerminal(approval);
        requireRejectedTerminal(execution);
        String authoritativeReason = approval.getReason();
        if (!StringUtils.equals(authoritativeReason, execution.getReason())
                || !hasRejectedResultText(approval, authoritativeReason)
                || !hasRejectedResultText(execution, authoritativeReason)) {
            throw invalidLink("rejection reasons or result projections are inconsistent");
        }
    }

    private static boolean isRejectedTerminal(PendingToolCall pending) {
        return pending != null && PendingToolCall.REJECTED.equals(pending.getStatus())
                && ToolOutcome.REJECTED.wire().equals(pending.getOutcome()) && pending.getCode() == null
                && hasRejectedResultText(pending, pending.getReason()) && isResolvedAtConsistent(pending);
    }

    private static boolean hasRejectedResultText(PendingToolCall pending, String authoritativeReason) {
        if (REJECTED_RESULT_TEXT.equals(pending.getResultText())) {
            return true;
        }
        return StringUtils.isNotBlank(authoritativeReason)
                && (REJECTED_RESULT_TEXT_PREFIX + authoritativeReason).equals(pending.getResultText());
    }

    private static boolean isResolvedAtConsistent(PendingToolCall pending) {
        if (pending == null || pending.getResolvedAtMs() <= 0L) {
            return false;
        }
        return (pending.getRequestedAtMs() <= 0L || pending.getResolvedAtMs() >= pending.getRequestedAtMs())
                && (pending.getReqAtMs() <= 0L || pending.getResolvedAtMs() >= pending.getReqAtMs());
    }

    private void requireNewExecution(String runId, PendingToolCall execution) {
        if (StringUtils.isBlank(runId) || execution == null || StringUtils.isBlank(execution.getToolCallId())
                || StringUtils.isBlank(execution.getName())) {
            throw invalidLink("execution identity or run is missing");
        }
        if (!PendingToolCall.KIND_FRONTEND.equals(execution.getKind())
                || !isPreExecutionApprovalRequired(execution.getName())) {
            throw invalidLink("execution must be a DANGER+FRONTEND tool");
        }
        requireNoExecutionEvidence(runId, execution);
        if (!PendingToolCall.PENDING.equals(execution.getStatus())
                || Boolean.TRUE.equals(execution.getReq()) || StringUtils.isNotBlank(execution.getPreExecutionApprovalId())
                || StringUtils.isNotBlank(execution.getExecutionToolCallId())) {
            throw invalidLink("execution is not a new unlinked pending entry");
        }
    }

    private void requireNoExecutionEvidence(String runId, PendingToolCall pending) {
        if (this.store.hasExecutionEvidence(runId, pending)) {
            throw invalidLink("execution evidence already exists");
        }
    }

    private PendingToolCall requireTarget(String runId, String toolCallId) {
        if (StringUtils.isBlank(runId) || StringUtils.isBlank(toolCallId)) {
            throw invalidLink("linked target id is missing");
        }
        PendingToolCall target = this.store.pending(runId, toolCallId);
        if (target == null) {
            throw invalidLink("linked target does not exist");
        }
        return target;
    }

    private static String requireLink(String value, String role) {
        if (StringUtils.isBlank(value)) {
            throw invalidLink(role + " is missing");
        }
        return value;
    }

    private static void settleRejected(PendingToolCall pending, String reason, long resolvedAtMs) {
        pending.setStatus(PendingToolCall.REJECTED);
        pending.setOutcome(ToolOutcome.REJECTED.wire());
        pending.setCode(null);
        pending.setReason(reason);
        pending.setResultText(REJECTED_RESULT_TEXT_PREFIX + reason);
        pending.setExecuted(false);
        pending.setExecutedBy(null);
        pending.setResolvedAtMs(resolvedAtMs);
    }

    private static IllegalStateException invalidLink(String reason) {
        return new IllegalStateException("Pre-execution approval link validation failed: " + reason);
    }

    public record ApprovalPair(PendingToolCall approval, PendingToolCall execution) {
    }
}
