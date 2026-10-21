package com.example.configmgr.ai.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 类目拆分（B 组：C-11…C-14，设计稿 §5.3）。
 *
 * <p>
 * 守恒是这组的核心：分项是<b>估算</b>、总量是<b>账单</b>。若直接展示估算分项，用户会看到
 * "分项加起来 ≠ 已用"的加总矛盾 —— 归一化把差额按比例摊掉，恒有 Σ分项 == used。
 */
class ContextBreakdownTest {

	/** C-11：随机 1000 组（含 used &lt; Σv 与 used &gt; Σv）恒有 Σ分项 == used。 */
	@Test
	void c11SumEqualsUsedForRandomInputs() {
		Random random = new Random(20261021L);
		for (int round = 0; round < 1000; round++) {
			Map<String, Integer> values = new LinkedHashMap<>();
			for (String key : ContextBreakdown.USED_KEYS) {
				values.put(key, random.nextInt(50_000));
			}
			int used = random.nextInt(400_000);
			Map<String, Integer> normalized = ContextBreakdown.normalizeTo(values, used);
			assertThat(ContextBreakdown.total(normalized)).as("第 %d 组 used=%d", round, used).isEqualTo(used);
		}
	}

	/** 守恒的边界形态：used = 0（全 0）、used 远大于估算、估算远大于 used。 */
	@Test
	void c11EdgeCasesKeepConservation() {
		Map<String, Integer> values = new LinkedHashMap<>();
		values.put(ContextBreakdown.HISTORY, 100);
		values.put(ContextBreakdown.TOOL_RESULTS, 300);

		assertThat(ContextBreakdown.total(ContextBreakdown.normalizeTo(values, 0))).isZero();
		assertThat(ContextBreakdown.total(ContextBreakdown.normalizeTo(values, 100_000))).isEqualTo(100_000);
		assertThat(ContextBreakdown.total(ContextBreakdown.normalizeTo(values, 7))).isEqualTo(7);
		// 全 0 估算 + 有真值：差额不能凭空消失
		assertThat(ContextBreakdown.total(ContextBreakdown.normalizeTo(Map.of(), 500))).isEqualTo(500);
	}

	/** C-12：四类消息各造体量 ⇒ 每项只吃自己那类（tool 不算进 history，input 只数末条 user）。 */
	@Test
	void c12CategoriesDoNotBleedIntoEachOther() {
		List<Message> messages = List.of(
				new SystemMessage("系统".repeat(100)),
				new UserMessage("旧问题".repeat(10)),
				AssistantMessage.builder().content("旧答案".repeat(10)).build(),
				ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("c-1", "list_tasks", "工具结果".repeat(10))))
					.build(),
				new UserMessage("新问题"));

		Map<String, Integer> categories = ContextBreakdown.estimateCategories(messages, 50, 70);

		assertThat(categories.get(ContextBreakdown.HISTORY))
			.as("history 只数旧 user + assistant")
			.isEqualTo(ContextEstimator.estimate("旧问题".repeat(10)) + ContextEstimator.estimate("旧答案".repeat(10)));
		assertThat(categories.get(ContextBreakdown.TOOL_RESULTS)).isGreaterThan(0);
		assertThat(categories.get(ContextBreakdown.INPUT)).as("input 只数最后一条 user")
			.isEqualTo(ContextEstimator.estimate("新问题"));
		assertThat(categories.get(ContextBreakdown.SYSTEM)).isEqualTo(50);
		assertThat(categories.get(ContextBreakdown.TOOLS)).isEqualTo(70);
		assertThat(categories.get(ContextBreakdown.SUMMARY)).isZero();
		// 把 tool 算进 history 会让两项互串：history 会多出工具结果的量
		assertThat(categories.get(ContextBreakdown.HISTORY))
			.isLessThan(ContextEstimator.estimate("旧问题".repeat(10)) + ContextEstimator.estimate("旧答案".repeat(10))
					+ ContextEstimator.estimate("工具结果".repeat(10)));
	}

	/** C-13：tools 项随披露的 schema 数量变化（差值 = 两批 ToolDefinition 的估算差 ±1）。 */
	@Test
	void c13ToolsCategoryReflectsDisclosedSchemas() {
		List<ToolCallback> three = List.of(tool("list_tasks", "列出任务", "{\"type\":\"object\"}"),
				tool("start_export", "启动导出并返回 jobId", "{\"type\":\"object\",\"properties\":{\"taskId\":{}}}"),
				tool("get_status", "查询作业状态", "{}"));
		int withThree = ContextEstimator.estimateToolSchemas(three);
		int withNone = ContextEstimator.estimateToolSchemas(List.of());

		assertThat(withThree).as("披露 3 个工具 ⇒ schema 体量必须为正").isGreaterThan(0);
		assertThat(withNone).isZero();

		Map<String, Integer> a = ContextBreakdown.estimateCategories(List.of(), 0, withThree);
		Map<String, Integer> b = ContextBreakdown.estimateCategories(List.of(), 0, withNone);
		assertThat(Math.abs((a.get(ContextBreakdown.TOOLS) - b.get(ContextBreakdown.TOOLS)) - (withThree - withNone)))
			.isLessThanOrEqualTo(1);
	}

	/** C-14：summary 单列 —— 压缩后 history 下降、summary &gt; 0，二者之和 ≤ 压缩前 history。 */
	@Test
	void c14SummaryIsItsOwnCategory() {
		String original = "历史正文".repeat(200);
		List<Message> before = List.of(new UserMessage("问"), AssistantMessage.builder().content(original).build());
		int historyBefore = ContextBreakdown.estimateCategories(before, 0, 0).get(ContextBreakdown.HISTORY);

		// 压缩后：旧轮被一条 compacted 摘要顶替（摘要短）
		UserMessage summary = UserMessage.builder()
			.text("目标/已做/资源 ID/未解决/待审批/勿重做".repeat(2))
			.metadata(Map.of(ContextEstimator.COMPACTED_KEY, true))
			.build();
		List<Message> after = List.of(summary, new UserMessage("问"));
		Map<String, Integer> categories = ContextBreakdown.estimateCategories(after, 0, 0);

		assertThat(categories.get(ContextBreakdown.SUMMARY)).isGreaterThan(0);
		assertThat(categories.get(ContextBreakdown.HISTORY)).isLessThan(historyBefore);
		assertThat(categories.get(ContextBreakdown.HISTORY) + categories.get(ContextBreakdown.SUMMARY))
			.isLessThanOrEqualTo(historyBefore);
		// 把摘要并进 history ⇒ summary 恒 0，本断言红
	}

	/** 分项输出顺序固定（不按对象键序）：前端 popover 的七行顺序靠它，不能抖动。 */
	@Test
	void partsAreEmittedInAFixedOrderWithFreeLast() {
		Map<String, Integer> values = new LinkedHashMap<>();
		for (String key : ContextBreakdown.USED_KEYS) {
			values.put(key, 100);
		}
		List<Map<String, Object>> parts = ContextBreakdown.parts(values, 600, 128000);

		assertThat(parts).extracting(item -> item.get("key"))
			.containsExactly(ContextBreakdown.HISTORY, ContextBreakdown.TOOL_RESULTS, ContextBreakdown.SUMMARY,
					ContextBreakdown.SYSTEM, ContextBreakdown.TOOLS, ContextBreakdown.INPUT, ContextBreakdown.FREE);
		assertThat(parts.get(parts.size() - 1).get("tokens")).isEqualTo(128000 - 600);
	}

	/** 上限未知（0）时不产 free 项 —— "剩余多少"无从计算，不瞎填 0。 */
	@Test
	void freeIsOmittedWhenLimitIsUnknown() {
		List<Map<String, Object>> parts = ContextBreakdown.parts(freeOf(), 10, 0);
		assertThat(parts).extracting(item -> item.get("key")).doesNotContain(ContextBreakdown.FREE);
	}

	/** free 为负 = 已超（前端显示"已超 +N"）。 */
	@Test
	void freeCanBeNegative() {
		List<Map<String, Object>> parts = ContextBreakdown.parts(freeOf(), 200, 100);
		Object free = parts.get(parts.size() - 1).get("tokens");
		assertThat(free).isEqualTo(-100);
	}

	private static Map<String, Integer> freeOf() {
		Map<String, Integer> values = new LinkedHashMap<>();
		for (String key : ContextBreakdown.USED_KEYS) {
			values.put(key, 0);
		}
		return values;
	}

	private static ToolCallback tool(String name, String description, String schema) {
		ToolCallback callback = mock(ToolCallback.class);
		when(callback.getToolDefinition()).thenReturn(new DefaultToolDefinition(name, description, schema));
		return callback;
	}

	/** 类目全集常量：七项、顺序固定（新增类目必须两侧同改，本断言是护栏）。 */
	@Test
	void allKeysAreTheSevenContractKeys() {
		assertThat(ContextBreakdown.ALL_KEYS).containsExactly("history", "toolResults", "summary", "system", "tools",
				"input", "free");
		assertThat(new ArrayList<>(ContextBreakdown.USED_KEYS)).hasSize(6);
	}

}
