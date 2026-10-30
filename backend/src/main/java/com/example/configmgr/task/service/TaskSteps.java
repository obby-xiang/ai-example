package com.example.configmgr.task.service;

import com.example.configmgr.task.entity.Task;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 任务步骤的<b>唯一注册定义</b>（后端所有消费方共用这一份）。
 *
 * <h2>为什么要有它</h2>
 * 步骤此前是散写的字符串：{@code TaskService} 有一份私有的 {@code STEP_WHITELIST}，
 * {@code ConditionsForm}/{@code DefinitionSelectionForm}/{@code GenerativeFormRules}/
 * {@code GenerativeFormGuard}/{@code ToolPreflightService} 各自再抄一份
 * {@code SELECT_DEFS}/{@code QUERY_COND}/{@code EXPORT}/{@code UPLOAD}。
 * 副本之间的漂移不会报错，只会表现为"同一份任务在表单闸门与预检闸门得到相反结论"
 * （例如一处把 {@code DONE} 算进条件步、另一处不算）。因此把<b>步骤名、每类任务的序列、
 * 首步、前后关系、条件收集步</b>集中到这里，其余类只引用、不再写字面量。
 *
 * <h2>口径</h2>
 * <ul>
 * <li>步骤名与前端 {@code types/task.ts#TaskSteps} 同构（{@code task:&lt;type&gt;/&lt;step&gt;} 标签用它）；</li>
 * <li>导出序列 = {@code SELECT_DEFS → QUERY_COND → EXPORT}；导入序列 = {@code UPLOAD → PRECHECK → IMPORT → PUBLISH}；</li>
 * <li>{@code DONE} 是两类任务通用的完成态（不在序列内，但合法）；</li>
 * <li>条件只在 {@code QUERY_COND} 收集：未知步骤不判关闭（未知 ≠ 已过），其余步骤一律关闭。</li>
 * </ul>
 */
public final class TaskSteps {

	public static final String SELECT_DEFS = "SELECT_DEFS";

	public static final String QUERY_COND = "QUERY_COND";

	public static final String EXPORT = "EXPORT";

	public static final String UPLOAD = "UPLOAD";

	public static final String PRECHECK = "PRECHECK";

	public static final String IMPORT = "IMPORT";

	public static final String PUBLISH = "PUBLISH";

	public static final String DONE = "DONE";

	/** 每类任务的合法步骤序列（顺序即推进顺序，也是 {@code goToStep} 的白名单）。 */
	private static final Map<Task.TaskType, List<String>> SEQUENCES = sequences();

	/** 条件收集步（导出向导）：只有这一步允许下发条件表单 / 落条件。 */
	private static final Set<String> CONDITION_STEPS = Set.of(QUERY_COND);

	/** 条件收集步之前的步骤（指引是"先 confirm_step 推进"，而不是"步已关闭"）。 */
	private static final Set<String> BEFORE_CONDITION_STEPS = Set.of(SELECT_DEFS, UPLOAD);

	private TaskSteps() {
	}

	private static Map<Task.TaskType, List<String>> sequences() {
		Map<Task.TaskType, List<String>> map = new LinkedHashMap<>();
		map.put(Task.TaskType.EXPORT, List.of(SELECT_DEFS, QUERY_COND, EXPORT));
		map.put(Task.TaskType.IMPORT, List.of(UPLOAD, PRECHECK, IMPORT, PUBLISH));
		return Map.copyOf(map);
	}

	/** 该任务类型的步骤序列（不可变；未知类型返回空表）。 */
	public static List<String> sequence(Task.TaskType type) {
		return type == null ? List.of() : SEQUENCES.getOrDefault(type, List.of());
	}

	/** 该任务类型的首步（建任务 / 重新勾选后的权威回落值）。 */
	public static String first(Task.TaskType type) {
		List<String> steps = sequence(type);
		return steps.isEmpty() ? SELECT_DEFS : steps.get(0);
	}

	/** 步骤名归一（去空白 + 大写）；null/空白返回空串（"未知步骤"的唯一表示）。 */
	public static String normalize(String step) {
		return step == null ? "" : step.trim().toUpperCase(Locale.ROOT);
	}

	/** 是否是步骤名（含通用的 {@code DONE}）；null/空白返回 false。 */
	public static boolean isStepName(String step) {
		String normalized = normalize(step);
		if (normalized.isEmpty()) {
			return false;
		}
		if (DONE.equals(normalized)) {
			return true;
		}
		for (List<String> steps : SEQUENCES.values()) {
			if (steps.contains(normalized)) {
				return true;
			}
		}
		return false;
	}

	/** 该任务类型是否接受这个步骤（{@code DONE} 通用）。 */
	public static boolean isKnownFor(Task.TaskType type, String step) {
		String normalized = normalize(step);
		if (normalized.isEmpty()) {
			return false;
		}
		return DONE.equals(normalized) || sequence(type).contains(normalized);
	}

	/** 步骤在该类型序列里的下标；不在序列内（含未知/空白）返回 -1。 */
	public static int indexOf(Task.TaskType type, String step) {
		return sequence(type).indexOf(normalize(step));
	}

	/** {@code step} 是否在 {@code reference} 之前（任一不在序列内 ⇒ false，未知不参与比较）。 */
	public static boolean isBefore(Task.TaskType type, String step, String reference) {
		int current = indexOf(type, step);
		int target = indexOf(type, reference);
		return current >= 0 && target >= 0 && current < target;
	}

	/** {@code step} 是否已到达或越过 {@code reference}（未知步骤一律 false）。 */
	public static boolean isAtOrAfter(Task.TaskType type, String step, String reference) {
		int current = indexOf(type, step);
		int target = indexOf(type, reference);
		return current >= 0 && target >= 0 && current >= target;
	}

	/** 是否是条件收集步（{@code QUERY_COND}）。 */
	public static boolean isConditionStep(String step) {
		return CONDITION_STEPS.contains(normalize(step));
	}

	/**
	 * 条件表单 / 条件落库的<b>步骤门</b>：未知步骤放行（未知 ≠ 关闭，兼容旧任务/直调测试），
	 * 已明确步骤时只有 {@code QUERY_COND} 允许。
	 */
	public static boolean allowsConditionCollection(String step) {
		String normalized = normalize(step);
		return normalized.isEmpty() || CONDITION_STEPS.contains(normalized);
	}

	/** 是否还在条件步之前（{@code SELECT_DEFS}/{@code UPLOAD}）：拒绝指引是"先推进"。 */
	public static boolean isBeforeConditionStep(String step) {
		return BEFORE_CONDITION_STEPS.contains(normalize(step));
	}

	/** 是否是导出任务的执行步（{@code EXPORT}）：条件留空 = 全量只在这一步成立。 */
	public static boolean isExportExecutionStep(String step) {
		return EXPORT.equals(normalize(step));
	}
}
