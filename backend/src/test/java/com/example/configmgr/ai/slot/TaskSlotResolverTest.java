package com.example.configmgr.ai.slot;

import com.example.configmgr.ai.slot.TaskSlots.ConditionSlot;
import com.example.configmgr.ai.tool.AiContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务槽位解析（Rasa Forms 式缺失槽位模式）：只放"已知"值，槽位为空即缺失。
 *
 * <p>本类不启 Spring：{@link DefinitionCatalog} 用固定目录，{@link TaskSlotResolver}
 * 只读 {@code AiContext} 与用户文本 —— 断言的是"解析口径"，不是模型行为。
 */
class TaskSlotResolverTest {

	private final TaskSlotResolver resolver = new TaskSlotResolver(catalog());

	@Test
	void knownCurrencyAndCnyResolveToDirectToolArguments() {
		TaskSlots slots = resolve("帮我导出 CURRENCY，条件 code = CNY");

		assertThat(slots.taskType()).isEqualTo("EXPORT");
		assertThat(slots.defCodes()).containsExactly("CURRENCY");
		assertThat(slots.conditions()).containsExactly(new ConditionSlot("CURRENCY", "code", "CNY"));
	}

	@Test
	void unknownDefinitionLeavesSlotsMissing() {
		TaskSlots slots = resolve("导出 NOT_A_DEFINITION");

		assertThat(slots.defCodes()).isEmpty();
		assertThat(slots.conditions()).isEmpty();
	}

	@Test
	void multipleExplicitDefinitionsAreOneSelection() {
		TaskSlots slots = resolve("导出 CURRENCY 和 ROLE_DICT");

		assertThat(slots.defCodes())
			.as("多项明确配置是合法的多选导出，不是歧义")
			.containsExactly("CURRENCY", "ROLE_DICT");

		TaskSlots byNames = resolve("导出货币和角色字典");
		assertThat(byNames.defCodes())
			.as("唯一中文名同样可以作为多选的一项")
			.containsExactly("CURRENCY", "ROLE_DICT");
	}

	@Test
	void sharedDisplayNameIsStillAmbiguous() {
		TaskSlots slots = resolve("导出重复字典");

		assertThat(slots.defCodes())
			.as("同一中文名对应多个定义编码，且没有编码消歧：不猜")
			.isEmpty();
	}

	@Test
	void cnyCurrencyDictionaryFillsConditionWithoutFilterKeyword() {
		TaskSlots slots = resolve("导出CNY货币字典");

		assertThat(slots.defCodes()).containsExactly("CURRENCY");
		assertThat(slots.conditions())
			.as("原文已给出唯一主键值：不要求出现“筛选/只要”等词")
			.containsExactly(new ConditionSlot("CURRENCY", "code", "CNY"));
	}

	@Test
	void dictionaryQueryIsNotAnExportSelection() {
		TaskSlots slots = resolve("查询货币字典");

		assertThat(slots.taskType()).isNull();
		assertThat(slots.defCodes())
			.as("只有出现在导出/选择意图里的定义名才是选择")
			.isEmpty();
	}

	@Test
	void negatedSelectionClearsInsteadOfFilling() {
		TaskSlots slots = resolve("不要导出 CURRENCY");

		assertThat(slots.taskType()).isNull();
		assertThat(slots.defCodes()).isEmpty();
	}

	@Test
	void laterTurnReusesEarlierSlotsAndExplicitReplacementWins() {
		TaskSlots reused = this.resolver.resolve(AiContext.empty(), "继续", List.of("帮我导出 CURRENCY"));
		assertThat(reused.defCodes()).containsExactly("CURRENCY");

		TaskSlots replaced = this.resolver.resolve(AiContext.empty(), "改选 ROLE_DICT", List.of("帮我导出 CURRENCY"));
		assertThat(replaced.defCodes()).containsExactly("ROLE_DICT");

		TaskSlots rewritten = this.resolver.resolve(AiContext.empty(), "改成 ROLE_DICT", List.of("帮我导出 CURRENCY"));
		assertThat(rewritten.defCodes()).containsExactly("ROLE_DICT");
	}

	@Test
	void historyConditionIsReusedAndCanBeOverridden() {
		TaskSlots reused = this.resolver.resolve(AiContext.empty(), "继续",
				List.of("导出 CURRENCY，筛选 CNY"));
		assertThat(reused.conditions()).containsExactly(new ConditionSlot("CURRENCY", "code", "CNY"));

		TaskSlots overridden = this.resolver.resolve(AiContext.empty(), "条件改成 code = USD",
				List.of("导出 CURRENCY，筛选 CNY"));
		assertThat(overridden.conditions()).containsExactly(new ConditionSlot("CURRENCY", "code", "USD"));
	}

	@Test
	void cancelClearsInheritedSlots() {
		TaskSlots slots = this.resolver.resolve(AiContext.empty(), "取消",
				List.of("导出 CURRENCY，筛选 CNY"));

		assertThat(slots.defCodes()).isEmpty();
		assertThat(slots.conditions()).isEmpty();
	}

	@Test
	void noFilterIntentMeansNoConditions() {
		TaskSlots slots = resolve("导出 CURRENCY");

		assertThat(slots.defCodes()).containsExactly("CURRENCY");
		assertThat(slots.conditions())
			.as("用户没提筛选，不得凭定义/值造条件")
			.isEmpty();
	}

	@Test
	void ambiguousFilterValuesMeanNoConditions() {
		TaskSlots slots = resolve("导出 CURRENCY，筛选 CNY 或 USD");

		assertThat(slots.defCodes()).containsExactly("CURRENCY");
		assertThat(slots.conditions()).isEmpty();
	}

	@Test
	void conflictingExplicitValuesMeanNoConditions() {
		TaskSlots slots = resolve("导出 CURRENCY，条件 code = CNY 或 code = USD");

		assertThat(slots.defCodes()).containsExactly("CURRENCY");
		assertThat(slots.conditions())
			.as("同一字段两个不同值 = 歧义，不替用户挑一个")
			.isEmpty();
	}

	@Test
	void workspaceSelectionIsAuthoritativeUntilExplicitlyReplaced() {
		AiContext workspace = AiContext.of("export", "EXPORT", "SELECT_DEFS", null,
				Map.of("pageId", "export", "selectedDefs", List.of("ROLE_DICT")));

		TaskSlots reused = this.resolver.resolve(workspace, "继续", List.of("导出 CURRENCY"));
		assertThat(reused.defCodes()).containsExactly("ROLE_DICT");

		TaskSlots replaced = this.resolver.resolve(workspace, "改选 CURRENCY", List.of());
		assertThat(replaced.defCodes()).containsExactly("CURRENCY");
	}

	@Test
	void enrichWritesWhitelistedExtraAndSurvivesSnapshotRoundTrip() {
		AiContext enriched = this.resolver.enrich(AiContext.empty(), "导出 CURRENCY，筛选 CNY", List.of());

		TaskSlots fromExtra = TaskSlots.fromExtra(enriched.getExtra());
		assertThat(fromExtra.defCodes()).containsExactly("CURRENCY");
		assertThat(fromExtra.conditions()).containsExactly(new ConditionSlot("CURRENCY", "code", "CNY"));
		// 快照读写同一形态（RunSnapshot 落 Redis 后再重建上下文）
		assertThat(TaskSlots.fromExtra(AiContext.fromMap(enriched.toMap()).getExtra()).conditions())
			.containsExactly(new ConditionSlot("CURRENCY", "code", "CNY"));
	}

	// ── helpers ─────────────────────────────────────────────────────────────

	private TaskSlots resolve(String message) {
		return this.resolver.resolve(AiContext.empty(), message, List.of());
	}

	private static DefinitionCatalog catalog() {
		return () -> List.of(
				new SlotDefinition("CURRENCY", "货币", List.of(
						new SlotDefinition.SlotField("code", "货币代码", true, List.of("CNY", "USD", "JPY")),
						new SlotDefinition.SlotField("name", "货币名称", false, List.of()))),
				new SlotDefinition("ROLE_DICT", "角色字典", List.of(
						new SlotDefinition.SlotField("code", "角色代码", true, List.of("ADMIN", "USER")))),
				new SlotDefinition("SYS_PARAM", "系统参数", List.of(
						new SlotDefinition.SlotField("paramKey", "参数键", true, List.of()),
						new SlotDefinition.SlotField("paramValue", "参数值", false, List.of()))),
				new SlotDefinition("DUP_A", "重复字典", List.of(
						new SlotDefinition.SlotField("code", "代码", true, List.of()))),
				new SlotDefinition("DUP_B", "重复字典", List.of(
						new SlotDefinition.SlotField("code", "代码", true, List.of()))));
	}
}
