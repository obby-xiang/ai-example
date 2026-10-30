package com.example.configmgr.ai.slot;

/**
 * 单个配置项的<b>无损条件命令</b>（确定性导出条件工作流）。
 *
 * <p>保留任务 {@code conditionJson} 原文（EQ/IN/GT/scopeKeys 都不丢），供工作流复用
 * {@code TaskService#setCondition} 的既有领域路径。<b>不接受自由文本推断</b>：
 * 事实只能来自任务已落库条件或前端结构化 {@code set_condition} 回灌。
 *
 * <p>三态与 {@link ConditionAssessment} 同源：{@code SET} 有结构化条件、
 * {@code EXPLICIT_UNSET} 是用户明确留空（{@code "{}"} 或 EXPORT 步的 null）、
 * {@code UNDECIDED} 才需要收集。<b>不含任何配置编码特判</b>。
 */
public record ConditionCommand(String defCode, State state, String conditionJson) {

	public enum State {
		SET, EXPLICIT_UNSET, UNDECIDED
	}

	public ConditionCommand {
		defCode = defCode == null || defCode.isBlank() ? null : defCode;
		conditionJson = conditionJson == null || conditionJson.isBlank() ? null : conditionJson;
		if (state == State.SET && conditionJson == null) {
			state = State.UNDECIDED;
		}
	}

	public static ConditionCommand set(String defCode, String conditionJson) {
		return new ConditionCommand(defCode, State.SET, conditionJson);
	}

	public static ConditionCommand explicitUnset(String defCode) {
		return new ConditionCommand(defCode, State.EXPLICIT_UNSET, null);
	}

	public static ConditionCommand undecided(String defCode) {
		return new ConditionCommand(defCode, State.UNDECIDED, null);
	}

	/** 是否已有可落库的结构化条件（只有 {@code SET} 且 JSON 非空）。 */
	public boolean hasConditionJson() {
		return this.state == State.SET && this.conditionJson != null;
	}
}
