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
}
