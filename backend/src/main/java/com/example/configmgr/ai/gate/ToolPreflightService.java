package com.example.configmgr.ai.gate;

import com.example.configmgr.common.CodedIllegalArgumentException;
import com.example.configmgr.common.CodedIllegalStateException;
import com.example.configmgr.common.ErrorCodes;

import com.example.configmgr.ai.slot.ConditionAssessment;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.common.ConflictException;
import com.example.configmgr.common.ResourceNotFoundException;
import com.example.configmgr.data.repo.ConfigStagingRowRepository;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.service.JobService;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskFile;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 确认门的<b>只读预检</b>（F5 预检前移：先把"能不能做"问清楚，再问"要不要做"）。
 *
 * <h2>为什么要有它</h2>
 * 确认卡此前只带 {@code args}，审批人不知道这个任务当前是否可执行；业务守卫全在批准<b>之后</b>的
 * {@link JobService#createAndStart} 里才跑（{@code TASK_FINALIZED} / {@code JOB_ALREADY_RUNNING} /
 * {@code PRECHECK_NOT_PASSED} / {@code PRECHECK_STALE}）。于是"已批准 + 执行失败"在审计上自相矛盾，
 * 用户也是在批准之后才发现做不了。本类把同一批守卫<b>提前</b>到挂起之前跑一遍：
 *
 * <ul>
 * <li><b>判据只借不抄</b>：四项检查全部复用 {@link JobService} 的只读守卫口径
 * （{@code finalizedConflict} / {@code inFlightConflict} / {@code precheckConflict}），
 * 与执行路径是同一份代码 —— 预检不会因为守卫改写而静默漂移；</li>
 * <li><b>只读</b>：不建作业、不写库、不改任务状态；唯一的"重活"是 IMPORT 的指纹比对（读文件算 sha256），
 * 那也是执行路径同样要做的事；</li>
 * <li><b>不替代执行前校验</b>：预检通过 ≠ 执行一定成功。批准到执行之间资源仍可变（文件被替换、
 * 别人先发了作业），{@code createAndStart} 里的守卫照旧复核 —— 预检只是让审批人"批准时看到的那一版"
 * 有据可查（{@code reportHash} 随批准绑定进台账）。</li>
 * </ul>
 *
 * <h2>覆盖范围</h2>
 * 只认五个进确认门的工具（{@link #CONFIRM_TOOLS}：四个 {@code start_*} + {@code create_task}）。
 * 判定靠<b>工具名</b>而不是风险等级：测试/未来新增的 DANGER 工具不该被这里"顺手"改写行为
 * （{@link #supports(String)} 为 false 即完全跳过）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolPreflightService {

	public static final String TOOL_EXPORT = "start_export";

	public static final String TOOL_PRECHECK = "start_precheck";

	public static final String TOOL_IMPORT = "start_import";

	public static final String TOOL_PUBLISH = "start_publish";

	/** 创建任务（AR-03：创建也进确认门，但判据与四个 {@code start_*} 不同）。 */
	public static final String TOOL_CREATE_TASK = "create_task";

	/**
	 * 进确认门、因而需要预检的工具（与 {@code AiTools} 的五个 {@code @ToolRisk(DANGER)} 一一对应）。
	 *
	 * <p>{@code create_task} 的预检只判"创建参数是否可用"：它没有 {@code taskId}，
	 * 也没有作业类型/在途作业/预检指纹三项；单轮上限与幂等由宿主（{@code TaskService}）保证。
	 */
	private static final Set<String> CONFIRM_TOOLS = Set.of(TOOL_EXPORT, TOOL_PRECHECK, TOOL_IMPORT, TOOL_PUBLISH,
			TOOL_CREATE_TASK);

	public static final String CHECK_TASK_EXISTS = "任务存在";

	public static final String CHECK_TYPE_MATCH = "类型匹配";

	public static final String CHECK_NO_JOB_IN_FLIGHT = "无在途作业";

	public static final String CHECK_FRESH_PRECHECK = "预检指纹新鲜";

	/** 任务有可执行的配置项（导出/导入都为必需；空任务会在 runner 里跑成 0 条目失败）。 */
	public static final String CHECK_HAS_ITEMS = "配置项齐备";

	/** 导入类任务有上传文件（预检查/导入/发布的前置；缺文件会在作业里直接失败）。 */
	public static final String CHECK_HAS_UPLOAD = "上传文件齐备";

	/** 目标任务与当前工作区绑定的任务一致（把执行前的 ToolTargetResolver 判据前移一份）。 */
	public static final String CHECK_TARGET_CONSISTENT = "目标一致";

	/** 导出必须先走到 EXPORT 步（直接 start_export 不得绕过 confirm_step 链）。 */
	public static final String CHECK_STEP_READY = "步骤就绪";

	/** 每个配置项的条件都已有答案（已设置或明确不设）；未决定/无法解析不得按全量发起。 */
	public static final String CHECK_CONDITIONS_DECIDED = "条件已决定";

	/** {@code create_task} 的类型判据。 */
	public static final String CHECK_CREATE_TYPE = "创建类型合法";

	/** 未进确认门 / 未执行的显式前缀（与"已批准·未执行"必须可区分）。 */
	public static final String NOT_APPROVED_NOT_EXECUTED = "未批准·未执行";

	/** 已批准但执行前复核失败的前缀（目标不一致/资源被占等）。 */
	public static final String APPROVED_NOT_EXECUTED = "已批准·未执行";

	/** 取不到条件时的如实措辞。 */
	public static final String CONDITION_ALL = "全部数据（无条件）";

	/** 取不到影响面时的如实措辞（不编数字）。 */
	public static final String IMPACT_UNKNOWN = "影响面暂不可估";

	private static final String IMPORT_FILE_TYPE = "UPLOAD";

	private static final ObjectMapper JSON = new ObjectMapper();

	private final JobService jobService;

	private final TaskFileRepository taskFileRepository;

	private final ConfigStagingRowRepository stagingRowRepository;

	/** 该工具是否走预检（四个 start_* 之外一律不预检）。 */
	public static boolean supports(String toolName) {
		return toolName != null && CONFIRM_TOOLS.contains(toolName);
	}

	/**
	 * 跑一次只读预检。调用方只对 {@link #supports(String)} 为真的工具调用本方法。
	 *
	 * @param toolName 工具名（决定作业类型与影响面口径）
	 * @param argumentsJson 工具入参原文（取 {@code taskId}）
	 * @return 预检报告（{@link PreflightReport#pass()} 为 false 时不得进入确认门）
	 */
	public PreflightReport inspect(String toolName, String argumentsJson) {
		return inspect(toolName, argumentsJson, null);
	}

	/**
	 * 跑一次只读预检（带本轮工作区上下文）。
	 *
	 * @param context 本轮冻结的工作区上下文；{@code null} = 无工作区（旧调用点/直调测试）。
	 *                用于"目标一致"检查：任务 {@code taskId} 必须与工作区绑定任务一致，
	 *                把执行前的 {@code ToolTargetResolver} 判据前移一份（执行前复核仍保留）。
	 */
	public PreflightReport inspect(String toolName, String argumentsJson, AiContext context) {
		if (TOOL_CREATE_TASK.equals(toolName)) {
			return inspectCreate(argumentsJson);
		}
		Job.JobType jobType = jobTypeOf(toolName);
		Long taskId = taskIdOf(argumentsJson);
		Optional<Task> task = jobService.findTask(taskId);

		Map<String, Object> target = targetOf(taskId, task);
		List<PreflightReport.Check> checks = new ArrayList<>(7);

		// ① 任务存在
		boolean exists = task.isPresent();
		checks.add(new PreflightReport.Check(CHECK_TASK_EXISTS, exists,
				exists ? "" : "未找到任务 #" + taskId + "（或当前无权访问）"));

		// ② 类型匹配（任务类型 vs 作业类型；任务已终态也在此项失败 —— 检查项名称/顺序保持既有口径，
		//    失败 detail 直说"任务已 <STATUS>，不可再发起 <JOB> 作业"，不把终态结论藏在类型文案里）
		if (!exists) {
			checks.add(new PreflightReport.Check(CHECK_TYPE_MATCH, false, "任务不存在，无法判断类型"));
		}
		else {
			Task present = task.get();
			ConflictException finalized = finalizedOf(taskId, jobType);
			Task.TaskType expected = expectedTaskType(jobType);
			boolean typeMatches = present.getType() == expected;
			boolean pass = typeMatches && finalized == null;
			String detail;
			if (!typeMatches) {
				detail = "任务 #" + taskId + " 类型 " + present.getType() + "，不能发起 " + jobType + " 作业";
			}
			else if (finalized != null) {
				detail = "任务 #" + taskId + " 已 " + present.getStatus() + "，不可再发起 " + jobType + " 作业（"
						+ finalized.getCode() + "）";
			}
			else {
				detail = "";
			}
			checks.add(new PreflightReport.Check(CHECK_TYPE_MATCH, pass, detail));
		}

		// ③ 无在途作业
		if (!exists) {
			checks.add(new PreflightReport.Check(CHECK_NO_JOB_IN_FLIGHT, false, "任务不存在，无法检查在途作业"));
		}
		else {
			ConflictException inFlight = jobService.inFlightConflict(taskId, jobType);
			checks.add(new PreflightReport.Check(CHECK_NO_JOB_IN_FLIGHT, inFlight == null,
					inFlight == null ? "" : inFlight.getMessage()));
		}

		// ④ 预检指纹新鲜（IMPORT/PUBLISH 要"最近一次预检查通过"，IMPORT 还要文件指纹未变；
		//    EXPORT/PRECHECK 不依赖既往预检查 —— 通过但如实说明依据）
		Optional<Job> latestPrecheck = exists ? jobService.latestPrecheck(taskId) : Optional.empty();
		if (!exists) {
			checks.add(new PreflightReport.Check(CHECK_FRESH_PRECHECK, false, "任务不存在，无法检查预检指纹"));
		}
		else if (jobType != Job.JobType.IMPORT && jobType != Job.JobType.PUBLISH) {
			checks.add(new PreflightReport.Check(CHECK_FRESH_PRECHECK, true,
					describeFingerprint(latestPrecheck.orElse(null), jobType)));
		}
		else {
			ConflictException stale = jobService.precheckConflict(taskId, jobType);
			checks.add(new PreflightReport.Check(CHECK_FRESH_PRECHECK, stale == null,
					stale == null ? describeFingerprint(latestPrecheck.orElse(null), jobType) : stale.getMessage()));
		}

		// ⑤ 配置项 / 上传文件齐备（review-03 第 4 项：此前预检四项全过，批准后作业立刻 FAILED）
		checks.add(itemsCheck(taskId, task.orElse(null)));
		checks.add(uploadCheck(taskId, task.orElse(null), jobType));

		// ⑥ 目标一致（把 ToolTargetResolver 的"参数目标 vs 工作区任务"判据前移；执行前仍复核）
		checks.add(targetConsistencyCheck(taskId, task.isPresent(), context));

		// ⑦ 导出：与 confirm_step 同一套步骤/条件预检（AI 直接 start_export 不得绕过条件收集）
		if (TOOL_EXPORT.equals(toolName)) {
			checks.add(exportStepCheck(taskId, task.orElse(null)));
			checks.add(exportConditionCheck(taskId, task.orElse(null)));
		}

		Task present = task.orElse(null);
		String impact = impactOf(toolName, taskId, present);
		String conditionSummary = conditionSummaryOf(present);
		String publishMode = publishModeOf(jobType, present, context);
		boolean pass = checks.stream().allMatch(PreflightReport.Check::pass);
		String reportHash = reportHash(toolName, target, checks, impact, conditionSummary, publishMode);
		return new PreflightReport(pass, target, checks, impact, conditionSummary, publishMode, reportHash);
	}

	/**
	 * {@code create_task} 的专用预检：只判"创建参数可用"。
	 *
	 * <p>它没有 {@code taskId}，四项作业判据都不适用；单轮上限与宿主幂等由
	 * {@code TaskService#createIdempotent} 负责（模型重试/并发/重复确认都收敛到同一结果）。
	 * 预检本身仍只读：不建任务、不写库。
	 */
	private PreflightReport inspectCreate(String argumentsJson) {
		Map<String, Object> target = new LinkedHashMap<>();
		String type = argumentText(argumentsJson, "type");
		String title = argumentText(argumentsJson, "title");
		boolean typeValid = "EXPORT".equalsIgnoreCase(type) || "IMPORT".equalsIgnoreCase(type);
		target.put("type", typeValid ? type.trim().toUpperCase(Locale.ROOT) : null);
		target.put("title", title);
		List<PreflightReport.Check> checks = new ArrayList<>(1);
		checks.add(new PreflightReport.Check(CHECK_CREATE_TYPE, typeValid,
				typeValid ? "" : "任务类型不合法：" + type + "（只支持 EXPORT / IMPORT）"));
		String label = typeValid ? type.trim().toUpperCase(Locale.ROOT) : "未知类型";
		String impact = "将创建 " + label + " 任务" + (title == null || title.isBlank() ? "" : "「" + title.trim() + "」")
				+ "（创建≠执行，不启动作业）";
		boolean pass = checks.stream().allMatch(PreflightReport.Check::pass);
		String reportHash = reportHash(TOOL_CREATE_TASK, target, checks, impact, "无筛选条件（创建任务）", null);
		return new PreflightReport(pass, target, checks, impact, "无筛选条件（创建任务）", null, reportHash);
	}

	// ── 配置项 / 上传文件 ─────────────────────────────────────────────────────

	private PreflightReport.Check itemsCheck(Long taskId, Task task) {
		if (task == null) {
			return new PreflightReport.Check(CHECK_HAS_ITEMS, false, "任务不存在，无法检查配置项");
		}
		List<TaskItem> items = task.getItems() == null ? List.of() : task.getItems();
		if (items.isEmpty()) {
			return new PreflightReport.Check(CHECK_HAS_ITEMS, false,
					"任务 #" + taskId + " 尚未选择任何配置项，作业会跑成 0 条目；先 select_definitions 勾选配置项再发起");
		}
		return new PreflightReport.Check(CHECK_HAS_ITEMS, true, "");
	}

	private PreflightReport.Check uploadCheck(Long taskId, Task task, Job.JobType jobType) {
		if (task == null) {
			return new PreflightReport.Check(CHECK_HAS_UPLOAD, false, "任务不存在，无法检查上传文件");
		}
		if (jobType == Job.JobType.EXPORT) {
			return new PreflightReport.Check(CHECK_HAS_UPLOAD, true, "导出作业不需要上传文件");
		}
		try {
			boolean hasUpload = this.taskFileRepository.findByTaskId(taskId).stream()
				.anyMatch(file -> IMPORT_FILE_TYPE.equals(file.getFileType()));
			return new PreflightReport.Check(CHECK_HAS_UPLOAD, hasUpload,
					hasUpload ? "" : "任务 #" + taskId + " 尚未上传数据文件；先在导入向导上传文件并重跑预检查");
		}
		catch (RuntimeException ex) {
			log.debug("预检读任务文件失败 taskId={}：{}", taskId, ex.getMessage());
			// 失败开放：查不了 ≠ 没有（与 ToolTargetResolver 同一口径）
			return new PreflightReport.Check(CHECK_HAS_UPLOAD, true, "上传文件状态暂不可查（执行前会再校验）");
		}
	}

	private static PreflightReport.Check targetConsistencyCheck(Long taskId, boolean exists, AiContext context) {
		if (!exists) {
			return new PreflightReport.Check(CHECK_TARGET_CONSISTENT, false, "任务不存在，无法核对目标");
		}
		Long workspaceTaskId = context == null ? null : context.getTaskId();
		if (workspaceTaskId == null) {
			return new PreflightReport.Check(CHECK_TARGET_CONSISTENT, true, "当前工作区未绑定任务（不构成冲突）");
		}
		if (workspaceTaskId.equals(taskId)) {
			return new PreflightReport.Check(CHECK_TARGET_CONSISTENT, true, "");
		}
		return new PreflightReport.Check(CHECK_TARGET_CONSISTENT, false,
				"任务 #" + taskId + " 与当前工作区任务 #" + workspaceTaskId + " 不一致；请先切换到该任务再发起");
	}

	/**
	 * 导出步骤检查：当前步骤必须已经是 {@code EXPORT}。
	 *
	 * <p>未知步骤（旧任务/直调测试没有 {@code currentStep}）不据此拦截 —— 但这不是把未知当通过，
	 * 条件检查同样因"无法核对"而只作为说明；真正的执行前复核仍在 {@code JobService}。
	 */
	private static PreflightReport.Check exportStepCheck(Long taskId, Task task) {
		if (task == null) {
			return new PreflightReport.Check(CHECK_STEP_READY, false, "任务不存在，无法检查步骤");
		}
		String step = task.getCurrentStep() == null ? "" : task.getCurrentStep().trim();
		if (step.isEmpty()) {
			// fail-closed：步骤未知时不能声称"已到 EXPORT"，否则直接 start_export 会同时绕过步骤与条件
			return new PreflightReport.Check(CHECK_STEP_READY, false,
					"任务 #" + taskId + " 的当前步骤未知，不能确认可以发起导出；先打开导出向导让步骤事实落库");
		}
		if ("EXPORT".equalsIgnoreCase(step)) {
			return new PreflightReport.Check(CHECK_STEP_READY, true, "");
		}
		return new PreflightReport.Check(CHECK_STEP_READY, false,
				"任务 #" + taskId + " 当前步骤 " + step + "，不能直接发起导出；先按步骤链 confirm_step 推进到 EXPORT"
						+ "（禁止跳过 QUERY_COND 等中间步骤）");
	}

	/**
	 * 导出条件检查：每个配置项都必须已设置或明确不设（逐项核对）。
	 *
	 * <p>条件未决定/JSON 无法解析时不得按全量发起（Task 1 第 1/3 项）；明确不设（{@code "{}"}）
	 * 与任何算子的已设置条件都算"有答案"。
	 */
	private static PreflightReport.Check exportConditionCheck(Long taskId, Task task) {
		if (task == null) {
			return new PreflightReport.Check(CHECK_CONDITIONS_DECIDED, false, "任务不存在，无法检查条件");
		}
		String step = task.getCurrentStep() == null ? "" : task.getCurrentStep().trim();
		if (!step.isEmpty() && !"EXPORT".equalsIgnoreCase(step)) {
			return new PreflightReport.Check(CHECK_CONDITIONS_DECIDED, true,
					"步骤未到 EXPORT，条件收集由 confirm_step 链负责");
		}
		if (step.isEmpty()) {
			return new PreflightReport.Check(CHECK_CONDITIONS_DECIDED, false,
					"任务 #" + taskId + " 的当前步骤未知，条件状态无法逐项核对；先打开导出向导让步骤事实落库");
		}
		ConditionAssessment.Assessment assessment = ConditionAssessment.of(task);
		List<String> undecided = assessment.undecidedDefCodes();
		if (undecided.isEmpty()) {
			return new PreflightReport.Check(CHECK_CONDITIONS_DECIDED, true, "");
		}
		String detail = "任务 #" + taskId + " 的条件未决定：" + String.join(", ", undecided)
				+ (assessment.hasUnparseable() ? "（其中存在无法解析的条件 JSON）" : "")
				+ "；先 set_condition 落库，或由用户明确留空确认全量，不能按全量直接发起导出";
		return new PreflightReport.Check(CHECK_CONDITIONS_DECIDED, false, detail);
	}

	// ── 条件摘要 / 发布模式（AR-02：只三字段，不恢复旧预检区） ────────────────

	/** 条件摘要（人读，后端算好；前端不反解条件文本）。 */
	private static String conditionSummaryOf(Task task) {
		if (task == null || task.getItems() == null) {
			return CONDITION_ALL;
		}
		ConditionAssessment.Assessment assessment = ConditionAssessment.of(task);
		boolean export = task.getType() == Task.TaskType.EXPORT;
		List<String> parts = new ArrayList<>();
		for (ConditionAssessment.ItemCondition item : assessment.items()) {
			if (!export && item.state() == ConditionAssessment.State.UNDECIDED) {
				// 导入没有筛选步：没有条件事实不等于"未决定"，不得渲染成缺口
				continue;
			}
			switch (item.state()) {
				case SET -> parts.add((item.defCode() == null ? "" : item.defCode() + "：")
						+ renderFacts(item.facts()));
				case EXPLICIT_UNSET -> parts.add((item.defCode() == null ? "" : item.defCode() + "：")
						+ "未设筛选（全量）");
				case UNDECIDED -> parts.add((item.defCode() == null ? "" : item.defCode() + "：")
						+ (item.unparseable() ? "条件无法解析（不能按全量处理）" : "条件未决定（不能按全量处理）"));
			}
		}
		if (parts.isEmpty()) {
			return CONDITION_ALL;
		}
		String summary = String.join("；", parts);
		return summary.length() <= 240 ? summary : summary.substring(0, 239) + "…";
	}

	private static String renderFacts(List<ConditionAssessment.ConditionFact> facts) {
		List<String> rendered = new ArrayList<>(facts.size());
		for (ConditionAssessment.ConditionFact fact : facts) {
			if ("SCOPE".equals(fact.operator())) {
				rendered.add("范围 = " + fact.value());
			}
			else {
				rendered.add(fact.fieldCode() + " " + operatorLabel(fact.operator())
						+ (fact.value() == null || fact.value().isBlank() ? "" : " " + fact.value()));
			}
		}
		return String.join("；", rendered);
	}

	private static String operatorLabel(String operator) {
		return switch (operator.toUpperCase(Locale.ROOT)) {
			case "EQ" -> "等于";
			case "NE" -> "不等于";
			case "CONTAINS", "LIKE" -> "包含";
			case "STARTS_WITH" -> "以…开头";
			case "IN" -> "包含于";
			case "EMPTY" -> "为空";
			case "NOT_EMPTY" -> "不为空";
			case "GT" -> "大于";
			case "GTE" -> "大于等于";
			case "LT" -> "小于";
			case "LTE" -> "小于等于";
			default -> operator;
		};
	}

	/**
	 * 权威发布模式：只取宿主（任务设置 {@code importMode}，其次本轮上下文 {@code extra.importMode}），
	 * 缺省 = {@code MERGE}（发布执行器的默认语义）。<b>模型文本永远不参与</b>。
	 */
	private static String publishModeOf(Job.JobType jobType, Task task, AiContext context) {
		if (jobType != Job.JobType.PUBLISH) {
			return null;
		}
		String fromTask = importModeOf(task);
		if (fromTask != null) {
			return fromTask;
		}
		Object raw = context == null || context.getExtra() == null ? null : context.getExtra().get("importMode");
		String fromContext = normalizeImportMode(raw == null ? null : String.valueOf(raw));
		return fromContext == null ? "MERGE" : fromContext;
	}

	private static String importModeOf(Task task) {
		if (task == null || task.getSettingsJson() == null || task.getSettingsJson().isBlank()) {
			return null;
		}
		try {
			return normalizeImportMode(JSON.readTree(task.getSettingsJson()).path("importMode").asText(null));
		}
		catch (Exception ex) {
			return null;
		}
	}

	private static String normalizeImportMode(String raw) {
		if (raw == null) {
			return null;
		}
		String mode = raw.trim().toUpperCase(Locale.ROOT);
		return "MERGE".equals(mode) || "REPLACE".equals(mode) ? mode : null;
	}

	// ── 影响面 ──────────────────────────────────────────────────────────────

	private String impactOf(String toolName, Long taskId, Task task) {
		if (task == null) {
			return IMPACT_UNKNOWN;
		}
		List<TaskItem> items = task.getItems() == null ? List.of() : task.getItems();
		int defs = items.size();
		if (defs == 0) {
			return IMPACT_UNKNOWN;
		}
		return switch (toolName) {
			case TOOL_PUBLISH -> stagingImpact(taskId, items, defs);
			case TOOL_IMPORT -> uploadImpact(taskId, defs);
			case TOOL_EXPORT -> exportImpact(taskId, defs);
			default -> "将校验 " + defs + " 个配置定义的上传文件";
		};
	}

	/** 发布影响面：暂存区里该任务实际要落库的行数（取不到就写"暂不可估"，不编）。 */
	private String stagingImpact(Long taskId, List<TaskItem> items, int defs) {
		long rows = 0L;
		try {
			for (TaskItem item : items) {
				rows += this.stagingRowRepository.countByTaskIdAndDefCode(taskId, item.getDefCode());
			}
		}
		catch (RuntimeException ex) {
			log.debug("发布影响面取暂存行数失败（按不可估处理）：{}", ex.getMessage());
			return "将发布 " + defs + " 个配置定义 · " + IMPACT_UNKNOWN;
		}
		return "将发布 " + defs + " 个配置定义 · 影响 " + String.format(Locale.ROOT, "%,d", rows) + " 行数据";
	}

	private String uploadImpact(Long taskId, int defs) {
		Long rows = summedRows(taskId, IMPORT_FILE_TYPE);
		return rows == null ? "将写入 " + defs + " 个配置定义的数据到暂存区 · " + IMPACT_UNKNOWN
				: "将写入 " + defs + " 个配置定义的数据到暂存区 · 涉及 " + String.format(Locale.ROOT, "%,d", rows) + " 行";
	}

	private String exportImpact(Long taskId, int defs) {
		Long rows = summedRows(taskId, "EXPORT");
		return rows == null ? "将导出 " + defs + " 个配置定义 · " + IMPACT_UNKNOWN
				: "将导出 " + defs + " 个配置定义 · 涉及 " + String.format(Locale.ROOT, "%,d", rows) + " 行数据";
	}

	/** 该任务该类型文件的已知行数合计；一律取不到行数时返回 null（不把"未知"写成 0）。 */
	private Long summedRows(Long taskId, String fileType) {
		long rows = 0L;
		boolean known = false;
		try {
			for (TaskFile file : this.taskFileRepository.findByTaskId(taskId)) {
				if (!fileType.equals(file.getFileType()) || file.getRowCount() == null) {
					continue;
				}
				rows += file.getRowCount();
				known = true;
			}
		}
		catch (RuntimeException ex) {
			log.debug("影响面取文件行数失败（按不可估处理）：{}", ex.getMessage());
			return null;
		}
		return known ? rows : null;
	}

	// ── 指纹与小结 ──────────────────────────────────────────────────────────

	/**
	 * 报告指纹（8 位十六进制）：对 {@code toolName + target + checks(name/pass/detail) + impact}
	 * 的规范串取 sha256 —— 同一份预检结论必然同指纹，任一检查项/影响面变化即变号。
	 * 校验方可从报告原文重算（{@link PreflightReport#asMap()} 已含全部参与哈希的字段）。
	 */
	private static String reportHash(String toolName, Map<String, Object> target,
			List<PreflightReport.Check> checks, String impact, String conditionSummary, String publishMode) {
		StringBuilder material = new StringBuilder("tool=").append(toolName);
		material.append("|target=").append(target);
		for (PreflightReport.Check check : checks) {
			material.append("|check=").append(check.name()).append(':').append(check.pass())
					.append(':').append(check.detail());
		}
		material.append("|impact=").append(impact);
		material.append("|condition=").append(conditionSummary);
		material.append("|publishMode=").append(publishMode);
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(material.toString().getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(8);
			for (int i = 0; i < 4; i++) {
				hex.append(String.format("%02x", digest[i]));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException ex) {
			throw new CodedIllegalStateException(ErrorCodes.SHA256_UNAVAILABLE, "SHA-256 不可用，无法生成预检指纹", ex);
		}
	}

	/** 通过时的指纹依据（人读）：有新预检查就锚定它的作业号，没有就说明"该项不依赖既往预检查"。 */
	private static String describeFingerprint(Job precheck, Job.JobType jobType) {
		if (precheck == null) {
			return jobType == Job.JobType.IMPORT || jobType == Job.JobType.PUBLISH
					? "" : "该作业不依赖既往预检查";
		}
		String basis = precheck.getResultJson();
		return "预检作业 #" + precheck.getId() + " · " + fingerprint4(basis);
	}

	/** 预检查结果指纹（4 位）：没有结果摘要时锚定作业号，"改了预检查就换号"的判据仍在。 */
	private static String fingerprint4(String material) {
		String source = material == null || material.isBlank() ? "-" : material;
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(4);
			for (int i = 0; i < 2; i++) {
				hex.append(String.format("%02x", digest[i]));
			}
			return hex.append('…').toString();
		}
		catch (NoSuchAlgorithmException ex) {
			return "…";
		}
	}

	private static Map<String, Object> targetOf(Long taskId, Optional<Task> task) {
		Map<String, Object> target = new LinkedHashMap<>();
		target.put("taskId", taskId);
		target.put("title", task.map(Task::getTitle).orElse(null));
		target.put("type", task.map(t -> String.valueOf(t.getType())).orElse(null));
		target.put("status", task.map(t -> String.valueOf(t.getStatus())).orElse(null));
		return target;
	}

	/** 工具 → 作业类型（与 {@code AiTools} 的四个 start_* 一一对应）。 */
	private static Job.JobType jobTypeOf(String toolName) {
		return switch (toolName) {
			case TOOL_EXPORT -> Job.JobType.EXPORT;
			case TOOL_PRECHECK -> Job.JobType.PRECHECK;
			case TOOL_IMPORT -> Job.JobType.IMPORT;
			case TOOL_PUBLISH -> Job.JobType.PUBLISH;
			default -> throw new CodedIllegalArgumentException(ErrorCodes.TOOL_NOT_PREFLIGHTABLE, "工具 " + toolName + " 不在预检范围内（见 supports）");
		};
	}

	/** 该作业类型要求任务本身是哪一类（导出任务发 EXPORT，其余都发在导入任务上）。 */
	private static Task.TaskType expectedTaskType(Job.JobType jobType) {
		return jobType == Job.JobType.EXPORT ? Task.TaskType.EXPORT : Task.TaskType.IMPORT;
	}

	/** 从工具入参取 {@code taskId}（缺失/非法一律 null，由"任务存在"检查如实失败）。 */
	private static Long taskIdOf(String argumentsJson) {
		if (argumentsJson == null || argumentsJson.isBlank()) {
			return null;
		}
		try {
			JsonNode node = JSON.readTree(argumentsJson);
			JsonNode taskId = node == null ? null : node.get("taskId");
			return taskId == null || taskId.isNull() ? null : taskId.asLong();
		}
		catch (Exception ex) {
			return null;
		}
	}

	/** 从入参取文本字段（缺失/非法一律 null）。 */
	private static String argumentText(String argumentsJson, String field) {
		if (argumentsJson == null || argumentsJson.isBlank()) {
			return null;
		}
		try {
			JsonNode node = JSON.readTree(argumentsJson);
			JsonNode value = node == null ? null : node.get(field);
			return value == null || value.isNull() ? null : value.asText();
		}
		catch (Exception ex) {
			return null;
		}
	}

	private ConflictException finalizedOf(Long taskId, Job.JobType jobType) {
		try {
			return this.jobService.finalizedConflict(taskId, jobType);
		}
		catch (ResourceNotFoundException ex) {
			return null; // 存在性由"任务存在"检查负责，这里不重复判
		}
	}

	// ── 报告 ────────────────────────────────────────────────────────────────

	/**
	 * 一份只读预检报告（{@code {target, checks, impact, reportHash}}）。{@link #asMap()} 是它的线上形态：
	 * 进 {@code PendingToolCall.preflight} → 随 {@code suspended} / {@code confirm_request} 帧下发。
	 */
	public record PreflightReport(boolean pass, Map<String, Object> target, List<Check> checks, String impact,
			String conditionSummary, String publishMode, String reportHash) {

		/** 兼容构造器：{@code conditionSummary}/{@code publishMode} 之前的调用点无需改动。 */
		public PreflightReport(boolean pass, Map<String, Object> target, List<Check> checks, String impact,
				String reportHash) {
			this(pass, target, checks, impact, CONDITION_ALL, null, reportHash);
		}

		/** 单项检查（{@code name} 人读、{@code pass} 布尔、{@code detail} 失败原因或通过依据）。 */
		public record Check(String name, boolean pass, String detail) {
		}

		/**
		 * 线上形态（固定键序：target → checks → impact → conditionSummary → publishMode → reportHash）。
		 *
		 * <p>AR-02 的<b>三项知情字段</b>只增这三项：{@code impact}（影响面）、
		 * {@code conditionSummary}（条件摘要，后端算好）、{@code publishMode}（权威发布模式）。
		 * 其余旧预检区字段（报告原文/逐项明细版式/详情抽屉）<b>不恢复</b>。
		 */
		public Map<String, Object> asMap() {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("target", this.target);
			List<Map<String, Object>> rendered = new ArrayList<>(this.checks.size());
			for (Check check : this.checks) {
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("name", check.name());
				item.put("pass", check.pass());
				item.put("detail", check.detail());
				rendered.add(item);
			}
			out.put("checks", rendered);
			out.put("impact", this.impact);
			out.put("conditionSummary", this.conditionSummary);
			out.put("publishMode", this.publishMode);
			out.put("reportHash", this.reportHash);
			return out;
		}

		/** 未通过项（人读文案的取材面）。 */
		public List<Check> failures() {
			return this.checks.stream().filter(check -> !check.pass()).toList();
		}

		/**
		 * 回给模型的人读文案：逐项列出未通过原因 + 明确的下一步（不是"失败了"，而是"改什么再试"）。
		 * 确认门在这里<b>没有</b>被建立，模型应把它当成"这次没做成"向用户解释，而不是等一张卡。
		 */
		public String failureMessage() {
			StringBuilder sb = new StringBuilder(NOT_APPROVED_NOT_EXECUTED + "（预检未通过）：本次未进入确认门、未执行"
					+ "（用户未被打扰确认）。未通过项：");
			List<Check> failed = failures();
			for (int i = 0; i < failed.size(); i++) {
				sb.append('\n').append(i + 1).append(". ").append(failed.get(i).name()).append('：')
					.append(failed.get(i).detail());
			}
			sb.append("\n请按上述原因修正后重试（该失败可重试）；如涉及数据文件变更，先重新上传并重跑预检查。");
			return sb.toString();
		}

	}
}
