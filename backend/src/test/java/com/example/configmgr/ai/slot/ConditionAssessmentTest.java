package com.example.configmgr.ai.slot;

import com.example.configmgr.ai.slot.ConditionAssessment.State;
import com.example.configmgr.task.entity.TaskItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一条件判定（Task 1 第 1 项）：区分<b>已设置</b>、<b>明确不设</b>、<b>未决定/无法解析</b>。
 *
 * <p>判据只认结构（{@code fields[].fieldCode/operator/value} 与 {@code scopeKeys}），
 * 覆盖 EQ 以外的现有算子；无法解析不得折成"全量"。
 */
class ConditionAssessmentTest {

	@Test
	void nonEqFieldsAndScopeKeysCountAsSet() {
		ConditionAssessment.Assessment assessment = ConditionAssessment.ofItems(List.of(item("CURRENCY",
				"{\"scopeKeys\":[\"XN\"],\"fields\":["
						+ "{\"fieldCode\":\"code\",\"operator\":\"IN\",\"value\":[\"CNY\",\"USD\"]}]}")));

		assertThat(assessment.stateOf("CURRENCY")).isEqualTo(State.SET);
		assertThat(assessment.facts()).extracting(ConditionAssessment.ConditionFact::operator)
			.containsExactly("SCOPE", "IN");
		ConditionAssessment.ConditionFact in = assessment.facts().stream()
			.filter(fact -> "IN".equals(fact.operator()))
			.findFirst()
			.orElseThrow();
		assertThat(in.fieldCode()).isEqualTo("code");
		assertThat(in.value()).isEqualTo("CNY、USD");
		assertThat(assessment.facts().get(0).value()).isEqualTo("XN");
	}

	/** 未知键不是"空条件"：残缺/未来结构一律未决定，禁止按全量。 */
	@Test
	void unknownTopLevelKeyMakesTheConditionUndecided() {
		ConditionAssessment.Assessment assessment = ConditionAssessment.ofItems(List.of(item("CURRENCY",
				"{\"filters\":[]}")));

		assertThat(assessment.stateOf("CURRENCY")).isEqualTo(State.UNDECIDED);
		assertThat(assessment.hasUnparseable()).isTrue();
	}

	/** fields 不是数组 ⇒ 残缺结构，未决定。 */
	@Test
	void fieldsThatIsNotAnArrayMakesTheConditionUndecided() {
		ConditionAssessment.Assessment assessment = ConditionAssessment.ofItems(List.of(item("CURRENCY",
				"{\"fields\":{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}}")));

		assertThat(assessment.stateOf("CURRENCY")).isEqualTo(State.UNDECIDED);
		assertThat(assessment.hasUnparseable()).isTrue();
	}

	/** 缺 operator 的字段不是合法条件：不得把整条折成"空条件 = 全量"。 */
	@Test
	void fieldWithoutOperatorMakesTheConditionUndecided() {
		ConditionAssessment.Assessment assessment = ConditionAssessment.ofItems(List.of(item("CURRENCY",
				"{\"fields\":[{\"fieldCode\":\"code\",\"value\":\"CNY\"}]}")));

		assertThat(assessment.stateOf("CURRENCY")).isEqualTo(State.UNDECIDED);
		assertThat(assessment.hasUnparseable()).isTrue();
	}

	/** 只有合法空数组才是"明确不设"。 */
	@Test
	void legalEmptyArraysAreExplicitlyUnset() {
		ConditionAssessment.Assessment assessment = ConditionAssessment.ofItems(List.of(item("CURRENCY",
				"{\"fields\":[],\"scopeKeys\":[]}")));

		assertThat(assessment.stateOf("CURRENCY")).isEqualTo(State.EXPLICIT_UNSET);
		assertThat(assessment.hasUnparseable()).isFalse();
	}

	private static TaskItem item(String defCode, String conditionJson) {
		TaskItem item = new TaskItem();
		item.setDefCode(defCode);
		item.setConditionJson(conditionJson);
		return item;
	}

}
