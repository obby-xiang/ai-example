package com.example.configmgr.ai.form;

import com.example.configmgr.ai.slot.ConditionAssessment;
import com.example.configmgr.ai.slot.ConditionCommand;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.service.TaskService;
import com.example.configmgr.task.service.TaskSteps;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>确定性导出条件工作流</b>：模型只负责自然语言语义；条件事实由本工作流按配置项无损决策。
 *
 * <h2>三态动作</h2>
 * <ul>
 * <li>{@code SET}：任务条件 JSON 是权威事实（由前端结构化 {@code set_condition} 或确认门回灌落库）；
 *     非 EQ/范围键原样保留，不重新向模型收集；</li>
 * <li>{@code EXPLICIT_UNSET}：用户明确留空（{@code "{}"}），或权威步骤已是 {@code EXPORT} 的
 *     null/空白 ⇒ 直接推进（全量）；</li>
 * <li>{@code UNDECIDED}：才收集一次。</li>
 * </ul>
 *
 * <p>坏 JSON / 未知键 / 残缺字段（{@link ConditionAssessment#hasUnparseable()}）一律拒绝，
 * 不按全量放行。<b>不写配置编码特判</b>；自定义配置项与内置项走同一路径。
 *
 * <p><b>自然语言信任边界</b>：本类不解析自由文本，也不使用 {@code TaskSlotParser} /
 * {@code TaskIntent} 的中文正则。自然语言只经既有模型工具调用变成结构化意图
 * （{@code create_task/select_definitions/set_condition}），由确认门/前端结构化回灌落库；
 * 任务 {@code conditionJson} 与前端 {@code set_condition} 回灌才是可信事实。
 */
@Slf4j
@Component
public class ExportConditionWorkflow {

	private final TaskService taskService;

	@Autowired
	public ExportConditionWorkflow(TaskService taskService) {
		this.taskService = taskService;
	}

	/**
	 * 工作流计划。
	 *
	 * @param commands    每个配置项的无损命令
	 * @param undecided   仍需收集的配置项
	 * @param malformed   存在坏 JSON/残缺结构（必须拒绝，不得按全量）
	 */
	public record Plan(List<ConditionCommand> commands, List<String> undecided, boolean malformed) {

		public Plan {
			commands = commands == null ? List.of() : List.copyOf(commands);
			undecided = undecided == null ? List.of() : List.copyOf(undecided);
		}

		/** 没有缺口且没有残缺结构：可以推进。 */
		public boolean readyToAdvance() {
			return !this.malformed && this.undecided.isEmpty();
		}
	}

	/** 按任务真实条目生成计划（只读，不落库；不解析任何自由文本）。 */
	public Plan plan(Task task) {
		if (task == null) {
			return new Plan(List.of(), List.of(), true);
		}
		ConditionAssessment.Assessment assessment = ConditionAssessment.of(task);
		List<String> defCodes = definitionCodes(task);
		boolean atExport = TaskSteps.isExportExecutionStep(task.getCurrentStep());
		List<ConditionCommand> commands = new ArrayList<>(defCodes.size());
		List<String> undecided = new ArrayList<>();
		boolean malformed = false;
		for (String defCode : defCodes) {
			ConditionAssessment.ItemCondition item = assessment.itemOf(defCode);
			ConditionCommand command;
			if (item != null && item.unparseable()) {
				// 坏 JSON / 未知键 / 残缺字段：明确拒绝，不按全量
				malformed = true;
				command = ConditionCommand.undecided(defCode);
			}
			else if (item != null && item.state() == ConditionAssessment.State.SET) {
				command = ConditionCommand.set(defCode, rawConditionJson(task, defCode));
			}
			else if (atExport && blankCondition(task, defCode)) {
				// 权威步骤 EXPORT 的空白 = 用户留空（全量）
				command = ConditionCommand.explicitUnset(defCode);
			}
			else if (item != null && item.state() == ConditionAssessment.State.EXPLICIT_UNSET) {
				command = ConditionCommand.explicitUnset(defCode);
			}
			else {
				command = ConditionCommand.undecided(defCode);
				undecided.add(defCode);
			}
			commands.add(command);
		}
		return new Plan(commands, undecided, malformed);
	}

	private static List<String> definitionCodes(Task task) {
		Set<String> codes = new LinkedHashSet<>();
		if (task.getItems() != null) {
			for (TaskItem item : task.getItems()) {
				if (item != null && item.getDefCode() != null && !item.getDefCode().isBlank()) {
					codes.add(item.getDefCode());
				}
			}
		}
		return new ArrayList<>(codes);
	}

	private static boolean blankCondition(Task task, String defCode) {
		if (task.getItems() == null || defCode == null) {
			return false;
		}
		for (TaskItem item : task.getItems()) {
			if (item != null && defCode.equals(item.getDefCode())) {
				return item.getConditionJson() == null || item.getConditionJson().isBlank();
			}
		}
		return false;
	}

	private static String rawConditionJson(Task task, String defCode) {
		if (task.getItems() == null) {
			return null;
		}
		for (TaskItem item : task.getItems()) {
			if (item != null && defCode.equals(item.getDefCode())) {
				return item.getConditionJson();
			}
		}
		return null;
	}

	private static String trim(String value) {
		return value == null ? "" : value.trim();
	}
}
