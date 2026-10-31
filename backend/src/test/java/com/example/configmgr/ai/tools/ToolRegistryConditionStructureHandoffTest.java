package com.example.configmgr.ai.tools;

import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolCategory;
import com.example.configmgr.ai.tool.ToolChannel;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolRisk;
import com.example.configmgr.ai.tool.ToolScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>条件结构化交棒</b>（{@code AiContext.EXTRA_CONDITION_STRUCTURE_HANDOFF}）的披露面与执行面。
 *
 * <h2>口径</h2>
 * <ol>
 * <li>{@code QUERY_COND} + 标记：只披露 {@code get_config_def / list_config_defs / set_condition /
 *     confirm_step} 四个工具；{@code create_task / start_export / generative_form} 等其余工具
 *     <b>披露与执行都拒绝</b>（Chrome 验证里模型因此只能调 {@code set_condition} 而不是再弹条件表）；</li>
 * <li>披露面 {@code forContext} 与执行兜底 {@code matchesContext} <b>同一判据</b>（逐工具对账）；</li>
 * <li>无标记、或已离开 {@code QUERY_COND} ⇒ 不限制，完整工具面恢复。</li>
 * </ol>
 */
class ToolRegistryConditionStructureHandoffTest {

	private AnnotationConfigApplicationContext context;

	private ToolRegistry registry;

	@BeforeEach
	void setUp() {
		this.context = new AnnotationConfigApplicationContext();
		this.context.registerBean(ConditionTools.class);
		this.context.registerBean(ToolRegistry.class);
		this.context.refresh();
		this.registry = this.context.getBean(ToolRegistry.class);
	}

	@AfterEach
	void tearDown() {
		if (this.context != null) {
			this.context.close();
		}
	}

	// ── ① 标记在场：只留四个条件结构化工具 ───────────────────────────────

	@Test
	void handoffMarkerDisclosesOnlyTheConditionStructuringTools() {
		AiContext handoff = handoffContext();

		assertThat(namesFor(handoff)).containsExactlyInAnyOrder("list_config_defs", "get_config_def",
				"set_condition", "confirm_step");
		for (String allowed : List.of("list_config_defs", "get_config_def", "set_condition", "confirm_step")) {
			assertThat(this.registry.matchesContext(allowed, handoff)).as("%s 应放行", allowed).isTrue();
		}
		for (String blocked : List.of("create_task", "start_export", "generative_form")) {
			assertThat(namesFor(handoff)).as("%s 不得披露", blocked).doesNotContain(blocked);
			assertThat(this.registry.matchesContext(blocked, handoff)).as("%s 应拒绝执行", blocked).isFalse();
		}
	}

	// ── ② 披露面与执行面同源：逐工具对账 ─────────────────────────────────

	@Test
	void disclosureAndExecutionShareTheSamePredicate() {
		List<AiContext> contexts = List.of(
				handoffContext(),
				AiContext.of("export", "EXPORT", "QUERY_COND", 1L, "ACTIVE", Map.of()),
				AiContext.of("export", "EXPORT", "SELECT_DEFS", 1L, "ACTIVE", Map.of(
						AiContext.EXTRA_CONDITION_STRUCTURE_HANDOFF, Boolean.TRUE)),
				AiContext.of("export", "EXPORT", "EXPORT", 1L, "ACTIVE", Map.of(
						AiContext.EXTRA_CONDITION_STRUCTURE_HANDOFF, Boolean.TRUE)),
				AiContext.of("tasks", null, null, null, null, Map.of(
						AiContext.EXTRA_CONDITION_STRUCTURE_HANDOFF, Boolean.TRUE)),
				AiContext.empty());

		for (AiContext context : contexts) {
			List<String> disclosed = namesFor(context);
			for (String tool : List.of("list_config_defs", "get_config_def", "set_condition", "confirm_step",
					"create_task", "start_export", "generative_form")) {
				assertThat(this.registry.matchesContext(tool, context))
					.as("上下文 %s 下 %s 的执行判定必须与披露集一致", context.getContextKey(), tool)
					.isEqualTo(disclosed.contains(tool));
			}
		}
	}

	// ── ③ 无标记 / 离开 QUERY_COND：完整工具面恢复 ──────────────────────

	@Test
	void droppingTheMarkerOrLeavingTheStepRestoresTheFullSurface() {
		AiContext sameStepWithoutMarker = AiContext.of("export", "EXPORT", "QUERY_COND", 1L, "ACTIVE", Map.of());
		assertThat(namesFor(sameStepWithoutMarker))
			.as("QUERY_COND 无标记 ⇒ 不受条件结构化收窄")
			.contains("set_condition", "confirm_step", "create_task", "generative_form")
			.doesNotContain("start_export");
		assertThat(this.registry.matchesContext("create_task", sameStepWithoutMarker)).isTrue();

		AiContext leftStepWithMarker = AiContext.of("export", "EXPORT", "EXPORT", 1L, "ACTIVE", Map.of(
				AiContext.EXTRA_CONDITION_STRUCTURE_HANDOFF, Boolean.TRUE));
		assertThat(namesFor(leftStepWithMarker))
			.as("已离开 QUERY_COND ⇒ 标记不再收窄工具面")
			.contains("confirm_step", "start_export", "create_task", "generative_form")
			.doesNotContain("set_condition");
		assertThat(this.registry.matchesContext("start_export", leftStepWithMarker)).isTrue();
		assertThat(this.registry.matchesContext("create_task", leftStepWithMarker)).isTrue();
	}

	private static AiContext handoffContext() {
		return AiContext.of("export", "EXPORT", "QUERY_COND", 1L, "ACTIVE", Map.of(
				AiContext.EXTRA_CONDITION_STRUCTURE_HANDOFF, Boolean.TRUE));
	}

	private List<String> namesFor(AiContext context) {
		return this.registry.forContext(context).stream()
			.map(ToolCallback::getToolDefinition)
			.map(definition -> definition.name())
			.toList();
	}

	/** 只声明本用例关心的披露面（与 {@code AiTools} 的真实注解同源）：工具体永不执行。 */
	static class ConditionTools {

		@Tool(name = "list_config_defs", description = "列出配置定义")
		@ToolScope("*")
		@ToolRisk(ToolMeta.RiskLevel.READ)
		@ToolCategory(ToolMeta.Category.R)
		public String listConfigDefs(@ToolParam(description = "层级", required = false) String level) {
			return "桩";
		}

		@Tool(name = "get_config_def", description = "读取配置定义字段")
		@ToolScope("*")
		@ToolRisk(ToolMeta.RiskLevel.READ)
		@ToolCategory(ToolMeta.Category.R)
		public String getConfigDef(@ToolParam(description = "配置定义编码") String code) {
			return "桩";
		}

		@Tool(name = "set_condition", description = "设置筛选条件草案")
		@ToolScope({ "task:EXPORT/QUERY_COND" })
		@ToolRisk(ToolMeta.RiskLevel.WRITE)
		@ToolCategory(ToolMeta.Category.U)
		@ToolChannel(ToolMeta.Channel.FRONTEND)
		public String setCondition(@ToolParam(description = "配置定义编码") String defCode) {
			return "桩";
		}

		@Tool(name = "confirm_step", description = "确认并推进步骤")
		@ToolScope({ "task:EXPORT", "task:IMPORT" })
		@ToolRisk(ToolMeta.RiskLevel.WRITE)
		@ToolCategory(ToolMeta.Category.U)
		@ToolChannel(ToolMeta.Channel.FRONTEND)
		public String confirmStep(@ToolParam(description = "目标步骤", required = false) String step) {
			return "桩";
		}

		@Tool(name = "create_task", description = "创建任务")
		@ToolScope({ "page:tasks", "task:EXPORT", "task:IMPORT" })
		@ToolRisk(ToolMeta.RiskLevel.DANGER)
		@ToolCategory(ToolMeta.Category.W)
		public String createTask(@ToolParam(description = "任务类型") String type) {
			return "桩";
		}

		@Tool(name = "start_export", description = "启动导出作业")
		@ToolScope({ "page:tasks", "task:EXPORT/EXPORT" })
		@ToolRisk(ToolMeta.RiskLevel.DANGER)
		@ToolCategory(ToolMeta.Category.J)
		public String startExport(@ToolParam(description = "任务ID") Long taskId) {
			return "桩";
		}

		@Tool(name = "generative_form", description = "下发结构化表单")
		@ToolScope({ "page:tasks", "task:*" })
		@ToolRisk(ToolMeta.RiskLevel.READ)
		@ToolCategory(ToolMeta.Category.H)
		@ToolChannel(ToolMeta.Channel.FRONTEND)
		public String generativeForm(@ToolParam(description = "表单 schema") String form) {
			return "桩";
		}

	}

}
