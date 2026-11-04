package com.example.configmgr.perf.redis;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * run 状态判定（纯函数，便于单测；Redis Lua 与 Java 读侧共用同一判据）。
 */
public final class RunStateMachine {

	public enum ConvergeAction {
		NONE, CONVERGE_FROM_TERMINAL, APPEND_TERMINAL_AND_CONVERGE, DEFER
	}

	public enum Claim {
		WON, ALREADY_CLAIMED, STALE_EPOCH, CONFLICT_STATUS, MISSING
	}

	public enum Cancel {
		CANCELLED, CONFLICT_CLAIMED, CONFLICT_DECIDED, CONFLICT_STATUS, MISSING
	}

	public enum Decision {
		APPLIED, IDEMPOTENT, CONFLICT_DECIDED, CONFLICT_CLAIMED, CONFLICT_STATUS, MISSING
	}

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private RunStateMachine() {
	}

	/**
	 * 终帧类型 → 快照终态。done/error 分别映射 DONE/FAILED；cancelled=true 统一映射 CANCELLED。
	 * 解析失败、未知类型返回 null（调用方必须 deferred/unknown，不得伪收敛）。
	 */
	public static String terminalStatus(String terminalJson) {
		if (terminalJson == null || terminalJson.isBlank()) {
			return null;
		}
		try {
			Map<?, ?> frame = MAPPER.readValue(terminalJson, Map.class);
			Object type = frame.get("type");
			if (type == null) {
				return null;
			}
			Object cancelled = frame.get("cancelled");
			boolean isCancelled = Boolean.TRUE.equals(cancelled)
					|| "true".equalsIgnoreCase(String.valueOf(cancelled));
			if (isCancelled) {
				return "CANCELLED";
			}
			if ("done".equals(String.valueOf(type))) {
				return "DONE";
			}
			if ("error".equals(String.valueOf(type))) {
				return "FAILED";
			}
			return null;
		}
		catch (Exception ex) {
			return null;
		}
	}

	/**
	 * 已有终帧 + RUNNING/SUSPENDED ⇒ 用终帧收敛；
	 * 无终帧但持租且允许收尾 ⇒ 补写终帧后收敛；否则 deferred（不得半删）。
	 */
	public static ConvergeAction convergence(String status, boolean terminalFramePresent,
			boolean finalizeAllowed, boolean leaseHeld) {
		if (status == null) {
			return ConvergeAction.DEFER;
		}
		if (isTerminalStatus(status)) {
			return ConvergeAction.NONE;
		}
		if (!isActiveStatus(status)) {
			return ConvergeAction.DEFER;
		}
		if (terminalFramePresent) {
			return ConvergeAction.CONVERGE_FROM_TERMINAL;
		}
		if (finalizeAllowed && leaseHeld) {
			return ConvergeAction.APPEND_TERMINAL_AND_CONVERGE;
		}
		return ConvergeAction.DEFER;
	}

	public static boolean isTerminalStatus(String status) {
		return "DONE".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
	}

	public static boolean isActiveStatus(String status) {
		return "RUNNING".equals(status) || "SUSPENDED".equals(status);
	}

	/** 认领：旧 epoch 必须先于任何副作用被拒；已有 claim 不得二次执行。 */
	public static Claim claim(long leaseEpoch, long fenceEpoch, boolean hasClaim, String pendingStatus) {
		if (leaseEpoch <= 0L || leaseEpoch < fenceEpoch) {
			return Claim.STALE_EPOCH;
		}
		if (hasClaim) {
			return Claim.ALREADY_CLAIMED;
		}
		if (!"APPROVED".equals(pendingStatus)) {
			return Claim.CONFLICT_STATUS;
		}
		return Claim.WON;
	}

	/** 取消是条件写：已认领/已执行的副作用、已有决策都不能被取消覆盖。 */
	public static Cancel cancel(boolean hasClaim, boolean executed, String decidedStatus, String pendingStatus) {
		if (pendingStatus == null) {
			return Cancel.MISSING;
		}
		if (hasClaim || executed) {
			return Cancel.CONFLICT_CLAIMED;
		}
		if (decidedStatus != null) {
			return Cancel.CONFLICT_DECIDED;
		}
		if (!"PENDING".equals(pendingStatus)) {
			return Cancel.CONFLICT_STATUS;
		}
		return Cancel.CANCELLED;
	}

	/** 决策：同槽 Lua 原子写 decided + 完整 pending 载荷；已认领条目不得被取消类决策改写。 */
	public static Decision decide(String currentStatus, String decidedStatus, String requestedStatus,
			boolean claimed, boolean cancelLike) {
		if (currentStatus == null) {
			return Decision.MISSING;
		}
		if (decidedStatus != null) {
			return decidedStatus.equals(requestedStatus) ? Decision.IDEMPOTENT : Decision.CONFLICT_DECIDED;
		}
		if (claimed && cancelLike) {
			return Decision.CONFLICT_CLAIMED;
		}
		if (!"PENDING".equals(currentStatus) && !"APPROVED".equals(currentStatus)) {
			return Decision.CONFLICT_STATUS;
		}
		return Decision.APPLIED;
	}

}
