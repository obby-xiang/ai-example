package com.example.configmgr.ai.tool;

import com.example.configmgr.ai.slot.TaskSlots;
import com.example.configmgr.ai.slot.TaskSlots.ConditionSlot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务槽位的工作区呈现（Rasa Forms 式缺失槽位，见
 * {@code docs/research/修复-AI多步工作流与动态披露-2026-10-28.md} §8）。
 *
 * <p>槽位是<b>系统解析出的数据</b>，不是指令：它必须落在 {@code <workspace_state>} 定界内，
 * 且只在有已知值时出现（空槽位不渲染"缺失"噪声）。
 */
class ContextBuilderTaskSlotsTest {

	private final ContextBuilder builder = new ContextBuilder(null, new ObjectMapper());

	@Test
	void taskSlotsAreRenderedAsDataInsideWorkspaceState() {
		TaskSlots slots = new TaskSlots("EXPORT", List.of("CURRENCY"),
				List.of(new ConditionSlot("CURRENCY", "code", "CNY")));
		AiContext context = AiContext.of("tasks", null, null, null,
				Map.of(TaskSlots.EXTRA_KEY, slots.toMap()));

		String text = this.builder.buildContextMessage(context);

		assertThat(text).contains("任务槽位");
		assertThat(text).contains("已确定任务类型: EXPORT");
		assertThat(text).contains("已确定配置项: CURRENCY");
		assertThat(text).contains("已确定条件: CURRENCY.code = CNY");
		assertThat(text.indexOf("任务槽位")).isLessThan(text.indexOf(ContextBuilder.WORKSPACE_STATE_CLOSE));
	}

	@Test
	void emptyTaskSlotsDoNotAddNoise() {
		AiContext context = AiContext.of("tasks", null, null, null,
				Map.of(TaskSlots.EXTRA_KEY, TaskSlots.empty().toMap()));

		String text = this.builder.buildContextMessage(context);

		assertThat(text).doesNotContain("任务槽位");
	}

	/**
	 * <b>未设筛选 = 全量</b>（本批收口）：已选配置项但没有任何查询条件，是"全量导出"的正常状态，
	 * 不是需要按第 26 条反复收集的"长期缺失"。否则模型每轮都看到"条件缺失"，会反复下发条件表单。
	 */
	@Test
	void selectedDefinitionsWithoutConditionsRenderAsUnsetFilterFullExport() {
		TaskSlots slots = new TaskSlots("EXPORT", List.of("CURRENCY"), List.of());
		AiContext context = AiContext.of("export", "EXPORT", "QUERY_COND", null,
				Map.of(TaskSlots.EXTRA_KEY, slots.toMap()));

		String text = this.builder.buildContextMessage(context);

		assertThat(text).contains("未设筛选");
		assertThat(text).contains("全量");
		assertThat(text).as("条件不再列为缺失槽位").doesNotContain("不要猜测）: 条件");
	}

	/** 反向：已设条件时不得再说"未设筛选"。 */
	@Test
	void configuredConditionsDoNotClaimAnUnsetFilter() {
		TaskSlots slots = new TaskSlots("EXPORT", List.of("CURRENCY"),
				List.of(new ConditionSlot("CURRENCY", "code", "CNY")));
		AiContext context = AiContext.of("export", "EXPORT", "QUERY_COND", null,
				Map.of(TaskSlots.EXTRA_KEY, slots.toMap()));

		String text = this.builder.buildContextMessage(context);

		assertThat(text).contains("已确定条件: CURRENCY.code = CNY");
		assertThat(text).doesNotContain("未设筛选");
	}

	/**
	 * 返修（Important 4）：{@code 未设筛选 = 全量导出} 只对 <b>EXPORT</b> 成立 —— 导入向导没有
	 * QUERY_COND 步，渲染"即导出全部"是错误事实（会把模型引向不存在的导出语义）。
	 */
	@Test
	void importTaskNeverClaimsAnUnsetFilterMeansFullExport() {
		TaskSlots slots = new TaskSlots("IMPORT", List.of("CURRENCY"), List.of());
		AiContext context = AiContext.of("import", "IMPORT", "PRECHECK", null,
				Map.of(TaskSlots.EXTRA_KEY, slots.toMap()));

		String text = this.builder.buildContextMessage(context);

		assertThat(text).contains("已确定配置项: CURRENCY");
		assertThat(text).doesNotContain("未设筛选");
		assertThat(text).doesNotContain("导出全部");
		assertThat(text).as("导入也不得把它列为缺失槽位").doesNotContain("不要猜测）: 条件");
	}

	/** 反向：EXPORT 仍渲染"未设筛选 = 全量"（既有口径不回退）。 */
	@Test
	void exportTaskKeepsTheUnsetFilterFullExportLine() {
		TaskSlots slots = new TaskSlots("EXPORT", List.of("CURRENCY"), List.of());
		AiContext context = AiContext.of("export", "EXPORT", "QUERY_COND", null,
				Map.of(TaskSlots.EXTRA_KEY, slots.toMap()));

		assertThat(this.builder.buildContextMessage(context)).contains("未设筛选: 全量");
	}

	/** 相邻：配置项仍未知时只报配置项缺失（没有配置项就无从谈筛选，更不能把条件当缺口）。 */
	@Test
	void missingDefinitionsRemainTheOnlyMissingSlot() {
		TaskSlots slots = new TaskSlots("EXPORT", List.of(), List.of());
		AiContext context = AiContext.of("export", "EXPORT", null, null,
				Map.of(TaskSlots.EXTRA_KEY, slots.toMap()));

		String text = this.builder.buildContextMessage(context);

		assertThat(text).contains("缺失（需要时按第 26 条收集，不要猜测）: 配置项");
		assertThat(text).doesNotContain("未设筛选");
	}
}
