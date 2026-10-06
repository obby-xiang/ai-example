package com.example.spike.hitl;

/** HITL 确认门的人工决策（approve / reject），source 记录决策来源。 */
public record ConfirmDecision(boolean approved, String reason, String source) {
}
