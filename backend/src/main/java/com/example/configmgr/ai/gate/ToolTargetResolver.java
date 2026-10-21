package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.repo.TaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 目标资源<b>执行前硬校验</b>（B23L1 批③ 的 F2 面；设计稿 §2.3）。
 *
 * <h2>它补的是哪一段</h2>
 * 防线③（{@code ToolRegistry#matchesContext}）管"这个工具在<b>当前页面/步骤</b>该不该被披露"，
 * F5 预检管"这件事<b>现在能不能做</b>（在途作业/预检指纹/任务终态）。两者都<b>不看工具参数</b>：
 * 模型完全可以拿着一个不存在的 {@code taskId}、或别的任务/别的任务类型的 {@code taskId} 走到执行入口 ——
 * 请求侧披露按上下文算，参数却是模型自由填的。本类就在执行前把"参数指向的资源"与"当前上下文"
 * 对一次账，不成立即 {@code blocked/TARGET_INVALID}（不抛异常、不断流、不占认领）。
 *
 * <h2>覆盖范围</h2>
 * 只认四个 {@code start_*} 作业发起工具（{@link #supports(String)}）：它们都以 {@code taskId} 定位
 * 一个<b>已存在</b>的任务，并且任务类型决定作业类型（导出任务只能发导出、导入类任务才能发
 * 预检查/导入/发布）。{@code create_task} 没有 {@code taskId}（它<b>创建</b>目标）——不在本类校验面内，
 * 参数合法性由工具自身与执行路径负责；其余 W/U 工具同理。
 *
 * <h2>失败开放（availability ≠ invalidity）</h2>
 * 仓储异常（数据库抖动）不判"目标非法"：{@link Verdict#ok()} 放行并留 WARN。把"查不了"报成
 * "不存在"会让一次抖动变成模型层面的错误结论（模型会据此告诉用户任务没了），而执行路径本来
 * 也会在真正读写时把异常如实浮出。这与资源锁对 {@code UNAVAILABLE} 的处理同一口径。
 */
@Slf4j
@Component
public class ToolTargetResolver {

	/** 四个作业发起工具（与 {@code AiTools} 的 {@code @ToolCategory(J/P)} 一一对应）。 */
	public static final String TOOL_EXPORT = "start_export";

	public static final String TOOL_PRECHECK = "start_precheck";

	public static final String TOOL_IMPORT = "start_import";

	public static final String TOOL_PUBLISH = "start_publish";

	/** 工具名 → 该工具发起的作业类型（同时是"是否校验"的判据：不在表里即不校验）。 */
	private static final Map<String, Job.JobType> JOB_TOOLS = Map.of(TOOL_EXPORT, Job.JobType.EXPORT,
			TOOL_PRECHECK, Job.JobType.PRECHECK, TOOL_IMPORT, Job.JobType.IMPORT, TOOL_PUBLISH, Job.JobType.PUBLISH);

	private static final ObjectMapper JSON = new ObjectMapper();

	private final TaskRepository taskRepository;

	public ToolTargetResolver(TaskRepository taskRepository) {
		this.taskRepository = taskRepository;
	}

	/** 该工具是否走目标资源校验（只有四个作业发起工具）。 */
	public static boolean supports(String toolName) {
		return jobTypeOf(toolName) != null;
	}

	/** 工具发起的作业类型；非作业发起工具返回 null。 */
	public static Job.JobType jobTypeOf(String toolName) {
		return toolName == null ? null : JOB_TOOLS.get(toolName);
	}

	/** 该作业类型要求任务本身是哪一类（导出任务发 EXPORT，其余都发在导入任务上）。 */
	public static Task.TaskType expectedTaskType(Job.JobType jobType) {
		return jobType == Job.JobType.EXPORT ? Task.TaskType.EXPORT : Task.TaskType.IMPORT;
	}

	/**
	 * 从工具入参取 {@code taskId}（缺失/非法一律 null）。
	 *
	 * <p>只认<b>可转成正整数</b>的取值：{@code "7"} 这类数字文本可接受（模型的 JSON 偶尔把数字写成串），
	 * {@code 0}/{@code -1}/{@code "abc"}/{@code 7.5} 一律视为"没给"—— 它们会拼出
	 * "所有调用都能命中的空任务"（同 {@code LeaseKeys#task} 的拒收口径）。
	 */
	public static Long taskIdOf(String argumentsJson) {
		JsonNode node = parse(argumentsJson);
		JsonNode id = node == null ? null : node.get("taskId");
		Long value = longOf(id);
		return value == null || value <= 0L ? null : value;
	}

	/**
	 * 校验一次工具调用的目标资源。
	 *
	 * @param toolName 工具名（非四个 {@code start_*} 一律放行）
	 * @param argumentsJson 工具入参原文
	 * @param context 本轮冻结的工作区上下文（带 {@code taskId} 时用它判"是否同一任务"）
	 * @return {@link Verdict#ok()} 或带人读原因的失败结论
	 */
	public Verdict check(String toolName, String argumentsJson, AiContext context) {
		Job.JobType jobType = jobTypeOf(toolName);
		if (jobType == null) {
			return Verdict.ok();
		}
		JsonNode node = parse(argumentsJson);
		if (node == null || !node.isObject()) {
			return Verdict.invalid("工具参数不是合法的 JSON 对象");
		}
		JsonNode idNode = node.get("taskId");
		if (idNode == null || idNode.isNull()) {
			return Verdict.invalid("缺少 taskId");
		}
		Long parsed = longOf(idNode);
		if (parsed == null || parsed <= 0L) {
			return Verdict.invalid("taskId 非法（" + idNode.asText() + "）");
		}
		long taskId = parsed;
		if (context != null && context.getTaskId() != null && context.getTaskId() != taskId) {
			return Verdict.invalid("任务 #" + taskId + " 与当前工作区任务 #" + context.getTaskId() + " 不一致");
		}
		Optional<Task> found;
		try {
			found = this.taskRepository.findById(taskId);
		}
		catch (RuntimeException ex) {
			// 失败开放：查不了 ≠ 不存在（见类注"失败开放"）
			log.warn("目标资源校验读仓储失败，按放行处理 taskId={} tool={}：{}", taskId, toolName, ex.getMessage());
			return Verdict.ok();
		}
		if (found == null || found.isEmpty()) {
			return Verdict.invalid("任务 #" + taskId + " 不存在（或已删除）");
		}
		Task task = found.get();
		Task.TaskType expected = expectedTaskType(jobType);
		if (task.getType() != expected) {
			return Verdict.invalid("任务 #" + taskId + " 类型 " + task.getType() + "，不能发起 " + jobType + " 作业");
		}
		// defCode 分支（私有预留）：现有四个 start_* 不带 defCode，本支不触发；
		// 带 defCode 的作业类工具一旦出现，同样按"属于该任务"硬校验（设计稿 §2.3）。
		JsonNode defNode = node.get("defCode");
		if (defNode != null && !defNode.isNull() && !defNode.asText().isBlank()) {
			Verdict defVerdict = checkDefCode(defNode.asText().trim(), task);
			if (!defVerdict.valid()) {
				return defVerdict;
			}
		}
		return Verdict.ok();
	}

	/**
	 * {@code defCode} 必须属于该任务（{@code task_items.def_code}）。
	 *
	 * <p><b>私有预留</b>：没有公开调用面（只由 {@link #check} 在参数带 defCode 时触发），
	 * 用例用合成入参（{@code {"taskId":7,"defCode":"CURRENCY"}}）驱动这条分支。
	 */
	Verdict checkDefCode(String defCode, Task task) {
		if (task == null) {
			return Verdict.invalid("缺任务，无法校验配置定义 " + defCode);
		}
		List<TaskItem> items = task.getItems() == null ? List.of() : task.getItems();
		boolean belongs = items.stream()
			.anyMatch(item -> item != null && item.getDefCode() != null
					&& item.getDefCode().equalsIgnoreCase(defCode));
		return belongs ? Verdict.ok()
				: Verdict.invalid("配置定义 " + defCode + " 不在任务 #" + task.getId() + " 内");
	}

	/**
	 * JSON 取值 → long（只认整数与数字文本；小数/布尔/对象/数组/空一律 null）。
	 *
	 * <p>不直接用 {@code canConvertToLong()}：它把 {@code 7.5} 也当"可转换"（截断成 7），
	 * 于是"任务 7 的一半"会被当成"任务 7"。这里显式拒掉浮点。
	 */
	private static Long longOf(JsonNode node) {
		if (node == null || node.isNull() || node.isFloatingPointNumber() || node.isBoolean()) {
			return null;
		}
		if (node.isIntegralNumber()) {
			return node.asLong();
		}
		if (node.isTextual()) {
			try {
				return Long.parseLong(node.asText().trim());
			}
			catch (NumberFormatException ex) {
				return null;
			}
		}
		return null;
	}

	private static JsonNode parse(String argumentsJson) {
		if (argumentsJson == null || argumentsJson.isBlank()) {
			return null;
		}
		try {
			return JSON.readTree(argumentsJson);
		}
		catch (Exception ex) {
			return null;
		}
	}

	/**
	 * 校验结论。
	 *
	 * @param valid 是否通过
	 * @param detail 未通过的原因（人读；通过时为空串）—— 只用于诊断文案，线上码恒为
	 * {@link ToolOutcome#CODE_TARGET_INVALID}
	 */
	public record Verdict(boolean valid, String detail) {

		public static Verdict ok() {
			return new Verdict(true, "");
		}

		public static Verdict invalid(String detail) {
			return new Verdict(false, detail == null ? "" : detail);
		}

	}

}
