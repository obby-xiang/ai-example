package com.example.configmgr.ai.gate;

import java.util.Map;

/**
 * 测试专用：把 {@link ConfirmGate#submitFrontendResult} 的 8 参签名收敛成便捷重载。
 *
 * <p>生产契约固定为
 * {@code (runId, toolCallId, result, source, cancelled, outcome, sheetMeta, code)}；
 * 旧用例大多只关心前 4-6 个位置参数。这里只补齐"未提供 = 不声明"的尾部参数，
 * 不改变任何默认语义（{@code outcome}/{@code code} 为 null、{@code sheetMeta} 为空表）。
 */
public final class GateCalls {

	private GateCalls() {
	}

	public static ConfirmGate.Submission submit(ConfirmGate gate, String runId, String toolCallId, String result,
			String source) {
		return submit(gate, runId, toolCallId, result, source, false);
	}

	public static ConfirmGate.Submission submit(ConfirmGate gate, String runId, String toolCallId, String result,
			String source, boolean cancelled) {
		return submit(gate, runId, toolCallId, result, source, cancelled, null);
	}

	public static ConfirmGate.Submission submit(ConfirmGate gate, String runId, String toolCallId, String result,
			String source, boolean cancelled, String outcome) {
		return submit(gate, runId, toolCallId, result, source, cancelled, outcome, Map.of());
	}

	public static ConfirmGate.Submission submit(ConfirmGate gate, String runId, String toolCallId, String result,
			String source, boolean cancelled, String outcome, Map<String, Object> sheetMeta) {
		return gate.submitFrontendResult(runId, toolCallId, result, source, cancelled, outcome, sheetMeta, null);
	}
}
