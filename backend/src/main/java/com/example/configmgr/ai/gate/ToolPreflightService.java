package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.run.ReplyLanguage;
import com.example.configmgr.common.CodedIllegalArgumentException;
import com.example.configmgr.common.CodedIllegalStateException;
import com.example.configmgr.common.ErrorCodes;

import com.example.configmgr.ai.slot.ConditionAssessment;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.AiToolNames;
import com.example.configmgr.ai.tool.WizardActionGuard;
import com.example.configmgr.common.ConflictException;
import com.example.configmgr.common.ResourceNotFoundException;
import com.example.configmgr.data.repo.ConfigStagingRowRepository;
import com.example.configmgr.data.service.ConditionOperators;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.service.JobService;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskFile;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.service.TaskSteps;
import com.example.configmgr.definition.service.DefinitionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <h2>协议口径（本批收口）</h2>
 * <ul>
 * <li><b>code + args 是协议面</b>：每个检查项带稳定机器码（{@link CheckCode}，如 {@code TASK_EXISTS}）
 * 与结构化参数（{@code args.taskId} 等）。前端/模型按码与参数分支，不解析中文长句；</li>
 * <li><b>人读文案不是协议</b>：{@code name}/{@code detail}/{@code impact}/{@code conditionSummary}/
 * {@link PreflightReport#failureMessage()} 都按本轮 {@link ReplyLanguage} 渲染（既有语言体系，
 * 不新增翻译框架）；写错语言不会改变任何判定；</li>
 * <li><b>指纹与语言无关</b>：{@code reportHash} 只由 toolName + target + 检查码/参数 + 结构化的
 * 影响面/条件事实 + publishMode 决定。批准发生在请求线程、执行前复核可能发生在另一线程（locale 不同），
 * 若指纹吃人读文本，一次英文请求就会在复核时被判 {@code PRECHECK_STALE}。</li>
 * </ul>
 *
 * <h2>覆盖范围</h2>
 * 只认五个进确认门的工具（{@link AiToolNames.ConfirmTool} 的四个 {@code start_*} + {@code create_task}）。
 * 判定靠<b>工具名</b>而不是风险等级：测试/未来新增的 DANGER 工具不该被这里"顺手"改写行为
 * （{@link #supports(String)} 为 false 即完全跳过）。
 */
@Slf4j
@Component
public class ToolPreflightService {

	public static final String TOOL_EXPORT = AiToolNames.START_EXPORT;

	public static final String TOOL_PRECHECK = AiToolNames.START_PRECHECK;

	public static final String TOOL_IMPORT = AiToolNames.START_IMPORT;

	public static final String TOOL_PUBLISH = AiToolNames.START_PUBLISH;

	/** 创建任务（AR-03：创建也进确认门，但判据与四个 {@code start_*} 不同）。 */
	public static final String TOOL_CREATE_TASK = AiToolNames.CREATE_TASK;

	/**
	 * 每个检查项的稳定机器码 + 双语名称（<b>唯一注册定义</b>）。
	 *
	 * <p>码是协议面：{@code checks[].code} 与 {@code args} 一起构成"可分支的结论"；
	 * 名称只是人读渲染，随 {@link ReplyLanguage} 变，不参与指纹。
	 */
	public enum CheckCode {
		TASK_EXISTS("任务存在", "Task exists"),
		TYPE_MATCH("类型匹配", "Type matches"),
		NO_JOB_IN_FLIGHT("无在途作业", "No job in flight"),
		FRESH_PRECHECK("预检指纹新鲜", "Precheck fingerprint fresh"),
		HAS_ITEMS("配置项齐备", "Definitions selected"),
		HAS_UPLOAD("上传文件齐备", "Upload present"),
		TARGET_CONSISTENT("目标一致", "Target consistent"),
		STEP_READY("步骤就绪", "Step ready"),
		CONDITIONS_DECIDED("条件已决定", "Conditions decided"),
		CREATE_TYPE("创建类型合法", "Create type valid"),
		CREATE_DEF_CODES("创建配置项合法", "Create definitions valid"),
		CREATE_CONDITIONS("创建条件合法", "Create conditions valid");

		private final String zh;

		private final String en;

		CheckCode(String zh, String en) {
			this.zh = zh;
			this.en = en;
		}

		/** 稳定机器码（= 枚举名）。 */
		public String code() {
			return name();
		}

		/** 人读名称（按本轮语言渲染）。 */
		public String label(boolean english) {
			return english ? this.en : this.zh;
		}
	}

	/**
	 * 检查项名称常量（<b>既有调用点兼容别名</b>，值由 {@link CheckCode} 渲染，不再抄一份字面量）。
	 * 协议面用 {@link CheckCode#code()}；人读面随 {@link ReplyLanguage} 变。
	 */
	public static final String CHECK_TASK_EXISTS = CheckCode.TASK_EXISTS.label(false);

	public static final String CHECK_TYPE_MATCH = CheckCode.TYPE_MATCH.label(false);

	public static final String CHECK_NO_JOB_IN_FLIGHT = CheckCode.NO_JOB_IN_FLIGHT.label(false);

	public static final String CHECK_FRESH_PRECHECK = CheckCode.FRESH_PRECHECK.label(false);

	/** 任务有可执行的配置项（导出/导入都为必需；空任务会在 runner 里跑成 0 条目失败）。 */
	public static final String CHECK_HAS_ITEMS = CheckCode.HAS_ITEMS.label(false);

	/** 导入类任务有上传文件（预检查/导入/发布的前置；缺文件会在作业里直接失败）。 */
	public static final String CHECK_HAS_UPLOAD = CheckCode.HAS_UPLOAD.label(false);

	/** 目标任务与当前工作区绑定的任务一致（把执行前的 ToolTargetResolver 判据前移一份）。 */
	public static final String CHECK_TARGET_CONSISTENT = CheckCode.TARGET_CONSISTENT.label(false);

	/** 导出必须先走到 EXPORT 步（直接 start_export 不得绕过 confirm_step 链）。 */
	public static final String CHECK_STEP_READY = CheckCode.STEP_READY.label(false);

	/** 每个配置项的条件都已有答案（已设置或明确不设）；未决定/无法解析不得按全量发起。 */
	public static final String CHECK_CONDITIONS_DECIDED = CheckCode.CONDITIONS_DECIDED.label(false);

	/** {@code create_task} 的类型判据。 */
	public static final String CHECK_CREATE_TYPE = CheckCode.CREATE_TYPE.label(false);

	/** {@code create_task} 的配置项候选判据（编码必须全部存在）。 */
	public static final String CHECK_CREATE_DEF_CODES = CheckCode.CREATE_DEF_CODES.label(false);

	/** 未进确认门 / 未执行的显式前缀（与"已批准·未执行"必须可区分）。 */
	public static final String NOT_APPROVED_NOT_EXECUTED = "未批准·未执行";

	/** 已批准但执行前复核失败的前缀（目标不一致/资源被占等）。 */
	public static final String APPROVED_NOT_EXECUTED = "已批准·未执行";

	/** 取不到条件时的如实措辞。 */
	public static final String CONDITION_ALL = "全部数据（无条件）";

	/** 取不到影响面时的如实措辞（不编数字）。 */
	public static final String IMPACT_UNKNOWN = "影响面暂不可估";

	private static final String IMPORT_FILE_TYPE = "UPLOAD";

	private static final String EXPORT_FILE_TYPE = "EXPORT";

	private static final ObjectMapper JSON = new ObjectMapper();

	private final JobService jobService;

	private final TaskFileRepository taskFileRepository;

	private final ConfigStagingRowRepository stagingRowRepository;

	/** 配置定义只读校验（{@code create_task(defCodes=…)} 的候选必须逐个存在才允许创建）。 */
	private final DefinitionService definitionService;

	@Autowired
	public ToolPreflightService(JobService jobService, TaskFileRepository taskFileRepository,
			ConfigStagingRowRepository stagingRowRepository, DefinitionService definitionService) {
		this.jobService = jobService;
		this.taskFileRepository = taskFileRepository;
		this.stagingRowRepository = stagingRowRepository;
		this.definitionService = definitionService;
	}

	/**
	 * 兼容构造器（既有直调用例只构造三个协作者）：<b>不带定义校验器</b>。
	 *
	 * <p>带 {@code defCodes} 的预检在该构造器下会 fail-closed（"校验不可用 ⇒ 不得创建"），
	 * 生产装配永远走四参构造器 —— 于是"少了校验器"不会静默变成"跳过校验"。
	 */
	public ToolPreflightService(JobService jobService, TaskFileRepository taskFileRepository,
			ConfigStagingRowRepository stagingRowRepository) {
		this(jobService, taskFileRepository, stagingRowRepository, null);
	}

	/**
	 * 该工具是否走预检（{@link AiToolNames.ConfirmTool} 的四个作业工具 + {@code create_task}）。
	 */
	public static boolean supports(String toolName) {
		if (AiToolNames.ConfirmTool.of(toolName).isPresent()) {
			return true;
		}
		return TOOL_CREATE_TASK.equals(toolName);
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
		boolean english = english();
		AiToolNames.ConfirmTool confirmTool = AiToolNames.ConfirmTool.of(toolName)
			.orElseThrow(() -> new CodedIllegalArgumentException(ErrorCodes.TOOL_NOT_PREFLIGHTABLE,
					"工具 " + toolName + " 不在预检范围内（见 supports）"));
		Job.JobType jobType = confirmTool.jobType();
		Long taskId = taskIdOf(argumentsJson);
		Optional<Task> task = jobService.findTask(taskId);

		Map<String, Object> target = targetOf(taskId, task);
		List<PreflightReport.Check> checks = new ArrayList<>(9);

		// ① 任务存在
		boolean exists = task.isPresent();
		checks.add(PreflightReport.check(CheckCode.TASK_EXISTS, exists, argsOf("taskId", taskId),
				exists ? "" : english ? "Task #" + taskId + " not found (or not accessible)"
						: "未找到任务 #" + taskId + "（或当前无权访问）"));

		// ② 类型匹配（任务类型 vs 作业类型；任务已终态也在此项失败 —— 检查项名称/顺序保持既有口径，
		//    失败 detail 直说"任务已 <STATUS>，不可再发起 <JOB> 作业"，不把终态结论藏在类型文案里）
		if (!exists) {
			checks.add(PreflightReport.check(CheckCode.TYPE_MATCH, false, argsOf("taskId", taskId),
					english ? "Task not found; type cannot be determined" : "任务不存在，无法判断类型"));
		}
		else {
			Task present = task.get();
			ConflictException finalized = finalizedOf(taskId, jobType);
			Task.TaskType expected = confirmTool.taskType();
			boolean typeMatches = present.getType() == expected;
			boolean pass = typeMatches && finalized == null;
			String detail;
			if (!typeMatches) {
				detail = english
						? "Task #" + taskId + " is " + present.getType() + " and cannot start a " + jobType + " job"
						: "任务 #" + taskId + " 类型 " + present.getType() + "，不能发起 " + jobType + " 作业";
			}
			else if (finalized != null) {
				detail = english
						? "Task #" + taskId + " is " + present.getStatus() + " and cannot start another " + jobType
								+ " job (" + finalized.getCode() + ")"
						: "任务 #" + taskId + " 已 " + present.getStatus() + "，不可再发起 " + jobType + " 作业（"
								+ finalized.getCode() + "）";
			}
			else {
				detail = "";
			}
			Map<String, Object> args = new LinkedHashMap<>();
			args.put("taskId", taskId);
			args.put("taskType", String.valueOf(present.getType()));
			args.put("jobType", jobType.name());
			// 状态是语言中立枚举：终态迁移必须改变指纹（否则"卡上还是 ACTIVE、批准时已 COMPLETED"会漏检）
			args.put("status", String.valueOf(present.getStatus()));
			checks.add(PreflightReport.check(CheckCode.TYPE_MATCH, pass, args, detail));
		}

		// ③ 无在途作业
		if (!exists) {
			Map<String, Object> args = new LinkedHashMap<>();
			args.put("taskId", taskId);
			args.put("jobType", jobType.name());
			checks.add(PreflightReport.check(CheckCode.NO_JOB_IN_FLIGHT, false, args,
					english ? "Task not found; in-flight job cannot be checked" : "任务不存在，无法检查在途作业"));
		}
		else {
			ConflictException inFlight = jobService.inFlightConflict(taskId, jobType);
			Map<String, Object> args = new LinkedHashMap<>();
			args.put("taskId", taskId);
			args.put("jobType", jobType.name());
			checks.add(PreflightReport.check(CheckCode.NO_JOB_IN_FLIGHT, inFlight == null, args,
					inFlight == null ? "" : inFlight.getMessage()));
		}

		// ④ 预检指纹新鲜（IMPORT/PUBLISH 要"最近一次预检查通过"，IMPORT 还要文件指纹未变；
		//    EXPORT/PRECHECK 不依赖既往预检查 —— 通过但如实说明依据）
		Optional<Job> latestPrecheck = exists ? jobService.latestPrecheck(taskId) : Optional.empty();
		// 指纹依据进 args（语言中立）：预检查换了一份，"批准时看到的那一版"必须随之变号
		Job precheckBasis = latestPrecheck.orElse(null);
		Map<String, Object> precheckArgs = new LinkedHashMap<>();
		precheckArgs.put("taskId", taskId);
		precheckArgs.put("jobType", jobType.name());
		precheckArgs.put("jobId", precheckBasis == null ? null : precheckBasis.getId());
		precheckArgs.put("fingerprint", fingerprint4(precheckBasis == null ? null : precheckBasis.getResultJson()));
		if (!exists) {
			checks.add(PreflightReport.check(CheckCode.FRESH_PRECHECK, false, precheckArgs,
					english ? "Task not found; precheck fingerprint cannot be checked"
							: "任务不存在，无法检查预检指纹"));
		}
		else if (jobType != Job.JobType.IMPORT && jobType != Job.JobType.PUBLISH) {
			checks.add(PreflightReport.check(CheckCode.FRESH_PRECHECK, true, precheckArgs,
					describeFingerprint(precheckBasis, jobType, english)));
		}
		else {
			ConflictException stale = jobService.precheckConflict(taskId, jobType);
			checks.add(PreflightReport.check(CheckCode.FRESH_PRECHECK, stale == null, precheckArgs,
					stale == null ? describeFingerprint(precheckBasis, jobType, english) : stale.getMessage()));
		}

		// ⑤ 配置项 / 上传文件齐备（review-03 第 4 项：此前预检四项全过，批准后作业立刻 FAILED）
		checks.add(itemsCheck(taskId, task.orElse(null), english));
		checks.add(uploadCheck(taskId, task.orElse(null), jobType, english));

		// ⑥ 目标一致（把 ToolTargetResolver 的"参数目标 vs 工作区任务"判据前移；执行前仍复核）
		checks.add(targetConsistencyCheck(taskId, task.isPresent(), context, english));

		// ⑦ 导出：与 confirm_step 同一套步骤/条件预检（AI 直接 start_export 不得绕过条件收集）
		if (TOOL_EXPORT.equals(toolName)) {
			checks.add(exportStepCheck(taskId, task.orElse(null), english));
			checks.add(exportConditionCheck(taskId, task.orElse(null), english));
		}

		Task present = task.orElse(null);
		Impact impact = impactOf(toolName, taskId, present, english);
		ConditionView conditions = conditionViewOf(present);
		String publishMode = publishModeOf(jobType, present, context);
		boolean pass = checks.stream().allMatch(PreflightReport.Check::pass);
		String reportHash = reportHash(toolName, target, checks, impact.fact(), conditions.fact(), publishMode);
		return new PreflightReport(pass, target, checks, impact.text(), conditions.text(), publishMode, reportHash);
	}

	/**
	 * {@code create_task} 的专用预检：判"创建参数可用"。
	 *
	 * <p>它没有 {@code taskId}，四项作业判据都不适用；单轮上限与宿主幂等由
	 * {@code TaskService#createIdempotent} 负责（模型重试/并发/重复确认都收敛到同一结果）。
	 * 预检本身仍只读：不建任务、不写库。
	 *
	 * <h2>结构化 {@code defCodes} 必须由宿主校验（2026-10-30 复审项）</h2>
	 * {@code create_task} 的 {@code defCodes} 会经 {@code RunContextRefresher} 写成
	 * "已知配置项候选"（{@code taskSlots.defCodes}），从而让配置选择表单被
	 * {@code FORM_SLOTS_ALREADY_KNOWN} 拒绝、模型直接 {@code select_definitions}。若候选来自模型
	 * 拼出的编码而未校验，等于让<b>模型参数直接成为宿主事实</b>（不存在的编码会一路成为"已知"，
	 * 直到前端落库才 400）。因此本方法逐个调 {@link DefinitionService#findByCode}：
	 * <ul>
	 * <li>全部存在 ⇒ 通过，且编码写进 {@code target.defCodes}、{@code CREATE_DEF_CODES.args}、
	 *     影响面文案与报告指纹（确认卡上看到的就是被校验过的那一组）；</li>
	 * <li>任一不存在/形状非法/校验器缺失 ⇒ <b>预检失败</b>，不进入确认门、不创建（fail-closed）；</li>
	 * <li>未传 {@code defCodes} ⇒ 不产生该项检查（宿主仍只弹一次配置表，行为不变）。</li>
	 * </ul>
	 * 执行前复核（{@code verifyApprovedReport}）会重跑本方法：批准与执行之间定义被删 ⇒ 指纹变化 ⇒
	 * 要求重新确认，不会带着过期候选创建。
	 */
	private PreflightReport inspectCreate(String argumentsJson) {
		boolean english = english();
		Map<String, Object> target = new LinkedHashMap<>();
		String type = argumentText(argumentsJson, "type");
		String title = argumentText(argumentsJson, "title");
		boolean typeValid = "EXPORT".equalsIgnoreCase(type) || "IMPORT".equalsIgnoreCase(type);
		target.put("type", typeValid ? type.trim().toUpperCase(Locale.ROOT) : null);
		target.put("title", title);
		List<PreflightReport.Check> checks = new ArrayList<>(2);
		checks.add(PreflightReport.check(CheckCode.CREATE_TYPE, typeValid, argsOf("type", type),
				typeValid ? "" : english
						? "Illegal task type: " + type + " (EXPORT / IMPORT only)"
						: "任务类型不合法：" + type + "（只支持 EXPORT / IMPORT）"));

		// 结构化 defCodes：宿主校验通过后才允许成为"已知配置"候选
		DefCodes requested = defCodesOf(argumentsJson);
		List<String> defCodes = requested.codes();
		if (requested.present() && !requested.invalid()) {
			List<String> missing = new ArrayList<>();
			boolean valid = true;
			String detail = "";
			if (this.definitionService == null) {
				// 少了校验器不得静默跳过校验（直调/测试构造器路径）
				valid = false;
				detail = english
						? "Definition validation is unavailable; cannot create a task with defCodes"
						: "配置定义校验不可用，不能带 defCodes 创建任务";
			}
			else {
				for (String defCode : defCodes) {
					try {
						this.definitionService.findByCode(defCode);
					}
					catch (ResourceNotFoundException ex) {
						missing.add(defCode);
					}
				}
				valid = missing.isEmpty();
				if (!valid) {
					detail = english
							? "Unknown definitions: " + String.join(", ", missing)
									+ " (task must not be created; use list_config_defs to look up codes)"
							: "配置定义不存在：" + String.join(", ", missing)
									+ "（不得创建；可用 list_config_defs 查编码）";
				}
			}
			if (!defCodes.isEmpty()) {
				target.put("defCodes", defCodes);
			}
			checks.add(PreflightReport.check(CheckCode.CREATE_DEF_CODES, valid,
					argsOf("defCodes", defCodes, "missing", missing), detail));
		}
		else if (requested.present()) {
			// 形状非法（非字符串数组/含空编码）：同样不得创建
			target.put("defCodes", defCodes);
			String invalidDetail = english
					? defCodes.isEmpty() ? "defCodes must be a non-empty array of definition codes"
							: "defCodes contains invalid entries; only non-blank definition codes are allowed"
					: defCodes.isEmpty() ? "defCodes 必须是配置定义编码组成的非空数组（不得创建）"
							: "defCodes 含非法元素：只能是非空配置定义编码（不得创建）";
			checks.add(PreflightReport.check(CheckCode.CREATE_DEF_CODES, false,
					argsOf("defCodes", defCodes, "missing", defCodes), invalidDetail));
		}

		// 结构化 conditions：与 set_condition 同一份校验（定义存在/字段属于该定义/算子与取值合法/非空），
		// 非法结构一律不得进入（预检失败 ⇒ 不挂确认卡、不创建）。
		ConditionsRequest requestedConditions = conditionsOf(argumentsJson);
		JsonNode conditionsNode = requestedConditions.node();
		if (requestedConditions.present()) {
			List<String> conditionIssues = new ArrayList<>();
			boolean conditionsValid = true;
			Map<String, Object> conditionsMap = conditionsMap(conditionsNode);
			if (!"EXPORT".equalsIgnoreCase(type)) {
				conditionsValid = false;
				conditionIssues.add(english
						? "conditions apply to EXPORT tasks only (import tasks have no filter step)"
						: "conditions 仅适用于导出（EXPORT）任务（导入没有筛选步骤）");
			}
			else if (conditionsNode == null || !conditionsNode.isObject() || conditionsNode.isEmpty()) {
				conditionsValid = false;
				conditionIssues.add(english
						? "conditions must be a non-empty object ({\"scopeKeys\":[...],\"fields\":[...]})"
						: "conditions 必须是非空对象（形如 {\"scopeKeys\":[...],\"fields\":[...]}）");
			}
			else if (!requested.present() || requested.invalid() || defCodes.isEmpty()) {
				conditionsValid = false;
				conditionIssues.add(english
						? "conditions need a valid defCodes array: conditions target specific definitions"
						: "conditions 必须与合法的 defCodes 同时给出（条件属于具体配置项）");
			}
			else if (this.definitionService == null) {
				conditionsValid = false;
				conditionIssues.add(english
						? "Definition validation is unavailable; cannot create a task with conditions"
						: "配置定义校验不可用，不能带 conditions 创建任务");
			}
			else {
				WizardActionGuard guard = new WizardActionGuard(this.definitionService);
				for (String defCode : defCodes) {
					String probe;
					try {
						Map<String, Object> args = new LinkedHashMap<>();
						args.put("defCode", defCode);
						args.put("conditions", conditionsNode);
						probe = JSON.writeValueAsString(args);
					}
					catch (Exception ex) {
						conditionsValid = false;
						conditionIssues.add("conditions 无法序列化/校验：" + ex.getMessage());
						continue;
					}
					var verdict = guard.inspectArgumentsFor(AiToolNames.SET_CONDITION, probe);
					if (!verdict.accepted()) {
						conditionsValid = false;
						for (String reason : verdict.reasons()) {
							if (conditionIssues.size() < 5) {
								conditionIssues.add(reason);
							}
						}
					}
				}
			}
			target.put("conditions", conditionsMap);
			checks.add(PreflightReport.check(CheckCode.CREATE_CONDITIONS, conditionsValid,
					argsOf("conditions", conditionsMap, "reasons", conditionIssues),
					conditionsValid ? "" : String.join("；", conditionIssues)));
		}

		String label = typeValid ? type.trim().toUpperCase(Locale.ROOT)
				: english ? "unknown type" : "未知类型";
		String codesText = defCodes.isEmpty() ? ""
				: english ? " · definitions: " + String.join(", ", defCodes)
						: " · 配置项：" + String.join("、", defCodes);
		String impactText = english
				? "Will create a " + label + " task"
						+ (title == null || title.isBlank() ? "" : " \"" + title.trim() + "\"") + codesText
						+ " (create is not execute; no job is started)"
				: "将创建 " + label + " 任务"
						+ (title == null || title.isBlank() ? "" : "「" + title.trim() + "」") + codesText
						+ "（创建≠执行，不启动作业）";
		String conditionText = requestedConditions.present()
				? (english
						? "Structured filter conditions supplied at creation (applied after definitions are selected; no condition form)"
						: "创建时已给出结构化筛选条件（勾选配置项后落库，不再弹条件表）")
				: (english ? "No filter conditions (create task)" : "无筛选条件（创建任务）");
		boolean pass = checks.stream().allMatch(PreflightReport.Check::pass);
		String reportHash = reportHash(TOOL_CREATE_TASK, target, checks,
				"create:" + (typeValid ? type.trim().toUpperCase(Locale.ROOT) : "-") + ":"
						+ (title == null ? "" : title) + ":" + String.join(",", defCodes)
						+ (requestedConditions.present() ? ":" + conditionsNode : ""),
				"create:none", null);
		return new PreflightReport(pass, target, checks, impactText, conditionText, null, reportHash);
	}

	/** {@code conditions} 的解析结果：{@code present=false} 表示入参没给（或显式 null）。 */
	private record ConditionsRequest(boolean present, JsonNode node) {
	}

	/** {@code conditions} 入参的存在性与原样对象（只解析，不做校验）。 */
	private static ConditionsRequest conditionsOf(String argumentsJson) {
		JsonNode node = parseObject(argumentsJson);
		if (node == null || !node.has("conditions")) {
			return new ConditionsRequest(false, null);
		}
		JsonNode conditions = node.get("conditions");
		if (conditions == null || conditions.isNull()) {
			return new ConditionsRequest(false, null);
		}
		return new ConditionsRequest(true, conditions);
	}

	private static JsonNode parseObject(String argumentsJson) {
		if (argumentsJson == null || argumentsJson.isBlank()) {
			return null;
		}
		try {
			JsonNode node = JSON.readTree(argumentsJson);
			return node != null && node.isObject() ? node : null;
		}
		catch (Exception ex) {
			return null;
		}
	}

	/** 预检报告里回显的条件对象（JsonNode → Map，帧/指纹序列化口径统一）。 */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> conditionsMap(JsonNode conditionsNode) {
		if (conditionsNode == null || !conditionsNode.isObject()) {
			return Map.of();
		}
		try {
			return JSON.convertValue(conditionsNode, Map.class);
		}
		catch (RuntimeException ex) {
			return Map.of();
		}
	}

	/** {@code defCodes} 的解析结果：{@code present=false} 表示入参没给该键。 */
	private record DefCodes(boolean present, boolean invalid, List<String> codes) {
	}

	/**
	 * 从 {@code create_task} 入参取 {@code defCodes}：去空白、去重并保持顺序。
	 *
	 * <p>形状判据（fail-closed）：该键存在时必须是数组，元素必须是文本且非空 —— 否则
	 * {@code invalid=true}（调用方据此判预检失败）。缺失该键 ⇒ {@code present=false}。
	 */
	private static DefCodes defCodesOf(String argumentsJson) {
		if (argumentsJson == null || argumentsJson.isBlank()) {
			return new DefCodes(false, false, List.of());
		}
		JsonNode node;
		try {
			node = JSON.readTree(argumentsJson);
		}
		catch (Exception ex) {
			return new DefCodes(false, false, List.of());
		}
		if (node == null || !node.isObject() || !node.has("defCodes")) {
			return new DefCodes(false, false, List.of());
		}
		JsonNode raw = node.get("defCodes");
		if (raw == null || raw.isNull()) {
			return new DefCodes(false, false, List.of());
		}
		if (!raw.isArray()) {
			return new DefCodes(true, true, List.of());
		}
		List<String> codes = new ArrayList<>();
		boolean invalid = false;
		for (JsonNode element : raw) {
			if (element == null || !element.isTextual() || element.asText().isBlank()) {
				invalid = true;
				continue;
			}
			String code = element.asText().trim();
			if (!codes.contains(code)) {
				codes.add(code);
			}
		}
		if (codes.isEmpty()) {
			// 空数组/全是空串：不给候选（但形状若含非法元素仍判非法）
			return new DefCodes(true, invalid, List.of());
		}
		return new DefCodes(true, invalid, List.copyOf(codes));
	}

	// ── 配置项 / 上传文件 ─────────────────────────────────────────────────────

	private PreflightReport.Check itemsCheck(Long taskId, Task task, boolean english) {
		if (task == null) {
			return PreflightReport.check(CheckCode.HAS_ITEMS, false, argsOf("taskId", taskId),
					english ? "Task not found; definitions cannot be checked" : "任务不存在，无法检查配置项");
		}
		List<TaskItem> items = task.getItems() == null ? List.of() : task.getItems();
		Map<String, Object> args = argsOf("taskId", taskId, "itemCount", items.size());
		if (items.isEmpty()) {
			return PreflightReport.check(CheckCode.HAS_ITEMS, false, args,
					english
							? "Task #" + taskId + " has no definitions selected; the job would run 0 rows. "
									+ "Call select_definitions first"
							: "任务 #" + taskId + " 尚未选择任何配置项，作业会跑成 0 条目；先 select_definitions 勾选配置项再发起");
		}
		return PreflightReport.check(CheckCode.HAS_ITEMS, true, args, "");
	}

	private PreflightReport.Check uploadCheck(Long taskId, Task task, Job.JobType jobType, boolean english) {
		if (task == null) {
			return PreflightReport.check(CheckCode.HAS_UPLOAD, false, argsOf("taskId", taskId),
					english ? "Task not found; uploads cannot be checked" : "任务不存在，无法检查上传文件");
		}
		Map<String, Object> args = argsOf("taskId", taskId, "jobType", jobType.name());
		if (jobType == Job.JobType.EXPORT) {
			return PreflightReport.check(CheckCode.HAS_UPLOAD, true, args,
					english ? "Export jobs need no upload" : "导出作业不需要上传文件");
		}
		try {
			boolean hasUpload = this.taskFileRepository.findByTaskId(taskId).stream()
				.anyMatch(file -> IMPORT_FILE_TYPE.equals(file.getFileType()));
			return PreflightReport.check(CheckCode.HAS_UPLOAD, hasUpload, args,
					hasUpload ? "" : english
							? "Task #" + taskId + " has no uploaded data file yet; upload it in the import wizard and rerun precheck"
							: "任务 #" + taskId + " 尚未上传数据文件；先在导入向导上传文件并重跑预检查");
		}
		catch (RuntimeException ex) {
			log.debug("预检读任务文件失败 taskId={}：{}", taskId, ex.getMessage());
			// 失败开放：查不了 ≠ 没有（与 ToolTargetResolver 同一口径）
			return PreflightReport.check(CheckCode.HAS_UPLOAD, true, args,
					english ? "Upload state is temporarily unavailable (rechecked before execution)"
							: "上传文件状态暂不可查（执行前会再校验）");
		}
	}

	private static PreflightReport.Check targetConsistencyCheck(Long taskId, boolean exists, AiContext context,
			boolean english) {
		Long workspaceTaskId = context == null ? null : context.getTaskId();
		Map<String, Object> args = new LinkedHashMap<>();
		args.put("taskId", taskId);
		args.put("workspaceTaskId", workspaceTaskId);
		if (!exists) {
			return PreflightReport.check(CheckCode.TARGET_CONSISTENT, false, args,
					english ? "Task not found; target cannot be verified" : "任务不存在，无法核对目标");
		}
		if (workspaceTaskId == null) {
			return PreflightReport.check(CheckCode.TARGET_CONSISTENT, true, args,
					english ? "Workspace is not bound to a task (no conflict)"
							: "当前工作区未绑定任务（不构成冲突）");
		}
		if (workspaceTaskId.equals(taskId)) {
			return PreflightReport.check(CheckCode.TARGET_CONSISTENT, true, args, "");
		}
		return PreflightReport.check(CheckCode.TARGET_CONSISTENT, false, args,
				english
						? "Task #" + taskId + " does not match the workspace task #" + workspaceTaskId
								+ "; switch to that task first"
						: "任务 #" + taskId + " 与当前工作区任务 #" + workspaceTaskId + " 不一致；请先切换到该任务再发起");
	}

	/**
	 * 导出步骤检查：当前步骤必须已经是 {@code EXPORT}。
	 *
	 * <p>未知步骤（旧任务/直调测试没有 {@code currentStep}）不据此拦截 —— 但这不是把未知当通过，
	 * 条件检查同样因"无法核对"而只作为说明；真正的执行前复核仍在 {@code JobService}。
	 */
	private static PreflightReport.Check exportStepCheck(Long taskId, Task task, boolean english) {
		String expected = TaskSteps.EXPORT;
		if (task == null) {
			return PreflightReport.check(CheckCode.STEP_READY, false,
					argsOf("taskId", taskId, "expectedStep", expected),
					english ? "Task not found; step cannot be checked" : "任务不存在，无法检查步骤");
		}
		String step = task.getCurrentStep() == null ? "" : task.getCurrentStep().trim();
		Map<String, Object> args = argsOf("taskId", taskId, "step", step, "expectedStep", expected);
		if (step.isEmpty()) {
			// fail-closed：步骤未知时不能声称"已到 EXPORT"，否则直接 start_export 会同时绕过步骤与条件
			return PreflightReport.check(CheckCode.STEP_READY, false, args,
					english
							? "Current step of task #" + taskId + " is unknown; cannot confirm the export can start. "
									+ "Open the export wizard so the step fact is persisted"
							: "任务 #" + taskId + " 的当前步骤未知，不能确认可以发起导出；先打开导出向导让步骤事实落库");
		}
		if (TaskSteps.isExportExecutionStep(step)) {
			return PreflightReport.check(CheckCode.STEP_READY, true, args, "");
		}
		return PreflightReport.check(CheckCode.STEP_READY, false, args,
				english
						? "Task #" + taskId + " is at step " + step + " and cannot start export directly; "
								+ "advance with confirm_step to EXPORT first (skipping QUERY_COND is not allowed)"
						: "任务 #" + taskId + " 当前步骤 " + step + "，不能直接发起导出；先按步骤链 confirm_step 推进到 EXPORT"
								+ "（禁止跳过 QUERY_COND 等中间步骤）");
	}

	/**
	 * 导出条件检查：每个配置项都必须已设置或明确不设（逐项核对）。
	 *
	 * <p>条件未决定/JSON 无法解析时不得按全量发起（Task 1 第 1/3 项）；明确不设（{@code "{}"}）
	 * 与任何算子的已设置条件都算"有答案"。
	 */
	private static PreflightReport.Check exportConditionCheck(Long taskId, Task task, boolean english) {
		if (task == null) {
			return PreflightReport.check(CheckCode.CONDITIONS_DECIDED, false, argsOf("taskId", taskId),
					english ? "Task not found; conditions cannot be checked" : "任务不存在，无法检查条件");
		}
		String step = task.getCurrentStep() == null ? "" : task.getCurrentStep().trim();
		if (step.isEmpty()) {
			return PreflightReport.check(CheckCode.CONDITIONS_DECIDED, false,
					argsOf("taskId", taskId, "step", step),
					english
							? "Current step of task #" + taskId + " is unknown; conditions cannot be checked item by item. "
									+ "Open the export wizard so the step fact is persisted"
							: "任务 #" + taskId + " 的当前步骤未知，条件状态无法逐项核对；先打开导出向导让步骤事实落库");
		}
		boolean atExport = TaskSteps.isExportExecutionStep(step);
		ConditionAssessment.Assessment assessment = ConditionAssessment.of(task);
		List<String> undecided = new ArrayList<>();
		for (String defCode : assessment.undecidedDefCodes()) {
			// 手工已经推进到 EXPORT 的空白条件（conditionJson=null）代表用户留空 = 全量：
			// 只在权威步骤 EXPORT 时生效；SELECT_DEFS/QUERY_COND 的 null 仍是未决定，必须收集。
			// 坏 JSON/残缺结构（unparseable=true）在 EXPORT 也继续拒绝。
			if (atExport && blankCondition(task, defCode)) {
				continue;
			}
			undecided.add(defCode);
		}
		Map<String, Object> args = argsOf("taskId", taskId, "step", step, "undecided", undecided);
		if (undecided.isEmpty()) {
			return PreflightReport.check(CheckCode.CONDITIONS_DECIDED, true, args, "");
		}
		String detail = english
				? "Conditions are undecided for task #" + taskId + ": " + String.join(", ", undecided)
						+ (assessment.hasUnparseable() ? " (some condition JSON cannot be parsed)" : "")
						+ "; set them with set_condition first, or have the user explicitly leave them empty "
						+ "to confirm the full range — export must not start as an implicit full range"
				: "任务 #" + taskId + " 的条件未决定：" + String.join(", ", undecided)
						+ (assessment.hasUnparseable() ? "（其中存在无法解析的条件 JSON）" : "")
						+ "；先 set_condition 落库，或由用户明确留空确认全量，不能按全量直接发起导出";
		return PreflightReport.check(CheckCode.CONDITIONS_DECIDED, false, args, detail);
	}

	// ── 条件摘要 / 发布模式（AR-02：只三字段，不恢复旧预检区） ────────────────

	/** 该配置项的条件 JSON 是否为空/空白（只有 null/空串；坏 JSON 不算）。 */
	private static boolean blankCondition(Task task, String defCode) {
		if (task == null || task.getItems() == null || defCode == null) {
			return false;
		}
		for (TaskItem item : task.getItems()) {
			if (item != null && defCode.equals(item.getDefCode())) {
				return item.getConditionJson() == null || item.getConditionJson().isBlank();
			}
		}
		return false;
	}

	/**
	 * 条件摘要的<b>结构化视图</b>：{@code text} 是人读摘要（按语言渲染），{@code fact} 是参与指纹的
	 * 语言中立事实串（同一条任务在任何语言下同值）。
	 */
	private record ConditionView(String text, String fact) {
	}

	private static ConditionView conditionViewOf(Task task) {
		boolean english = english();
		if (task == null || task.getItems() == null) {
			return new ConditionView(english ? "All data (no condition)" : CONDITION_ALL, "none");
		}
		ConditionAssessment.Assessment assessment = ConditionAssessment.of(task);
		boolean export = task.getType() == Task.TaskType.EXPORT;
		boolean manualExport = export && TaskSteps.isExportExecutionStep(task.getCurrentStep());
		List<String> parts = new ArrayList<>();
		StringBuilder fact = new StringBuilder();
		for (ConditionAssessment.ItemCondition item : assessment.items()) {
			if (!export && item.state() == ConditionAssessment.State.UNDECIDED) {
				// 导入没有筛选步：没有条件事实不等于"未决定"，不得渲染成缺口
				continue;
			}
			String prefix = item.defCode() == null ? "" : item.defCode() + (english ? ": " : "：");
			ConditionAssessment.State state = item.state();
			if (state == ConditionAssessment.State.UNDECIDED && manualExport && !item.unparseable()
					&& blankCondition(task, item.defCode())) {
				// 已进入 EXPORT 的空白条件 = 用户留空（全量），不是"未决定"
				state = ConditionAssessment.State.EXPLICIT_UNSET;
			}
			fact.append(item.defCode() == null ? "-" : item.defCode()).append(':').append(state.name())
				.append(':').append(item.unparseable()).append(':');
			for (ConditionAssessment.ConditionFact conditionFact : item.facts()) {
				fact.append(conditionFact.fieldCode()).append('|').append(conditionFact.operator()).append('|')
					.append(conditionFact.value()).append(';');
			}
			fact.append('/');
			switch (state) {
				case SET -> parts.add(prefix + renderFacts(item.facts(), english));
				case EXPLICIT_UNSET -> parts.add(prefix
						+ (english ? "no filter (all data)" : "未设筛选（全量）"));
				case UNDECIDED -> parts.add(prefix + (item.unparseable()
						? english ? "condition cannot be parsed (must not be treated as all data)"
								: "条件无法解析（不能按全量处理）"
						: english ? "condition undecided (must not be treated as all data)"
								: "条件未决定（不能按全量处理）"));
			}
		}
		if (parts.isEmpty()) {
			return new ConditionView(english ? "All data (no condition)" : CONDITION_ALL, "none");
		}
		String summary = String.join(english ? "; " : "；", parts);
		if (summary.length() > 240) {
			summary = summary.substring(0, 239) + "…";
		}
		return new ConditionView(summary, fact.toString());
	}

	private static String renderFacts(List<ConditionAssessment.ConditionFact> facts, boolean english) {
		List<String> rendered = new ArrayList<>(facts.size());
		for (ConditionAssessment.ConditionFact fact : facts) {
			if (ConditionOperators.SCOPE.equals(fact.operator())) {
				rendered.add((english ? "scope = " : "范围 = ") + fact.value());
			}
			else {
				rendered.add(fact.fieldCode() + " " + ConditionOperators.label(fact.operator(), english)
						+ (fact.value() == null || fact.value().isBlank() ? "" : " " + fact.value()));
			}
		}
		return String.join(english ? "; " : "；", rendered);
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

	/** 影响面：{@code text} 人读（按语言），{@code fact} 语言中立（参与指纹）。 */
	private record Impact(String text, String fact) {
	}

	private Impact impactOf(String toolName, Long taskId, Task task, boolean english) {
		if (task == null) {
			return new Impact(english ? "Impact cannot be estimated" : IMPACT_UNKNOWN, "unknown");
		}
		List<TaskItem> items = task.getItems() == null ? List.of() : task.getItems();
		int defs = items.size();
		if (defs == 0) {
			return new Impact(english ? "Impact cannot be estimated" : IMPACT_UNKNOWN, "unknown");
		}
		return switch (toolName) {
			case TOOL_PUBLISH -> stagingImpact(taskId, items, defs, english);
			case TOOL_IMPORT -> uploadImpact(taskId, defs, english);
			case TOOL_EXPORT -> exportImpact(taskId, defs, english);
			default -> new Impact(
					english ? "Will validate upload files for " + defs + " definitions"
							: "将校验 " + defs + " 个配置定义的上传文件",
					"validate:" + defs);
		};
	}

	/** 发布影响面：暂存区里该任务实际要落库的行数（取不到就写"暂不可估"，不编）。 */
	private Impact stagingImpact(Long taskId, List<TaskItem> items, int defs, boolean english) {
		long rows = 0L;
		try {
			for (TaskItem item : items) {
				rows += this.stagingRowRepository.countByTaskIdAndDefCode(taskId, item.getDefCode());
			}
		}
		catch (RuntimeException ex) {
			log.debug("发布影响面取暂存行数失败（按不可估处理）：{}", ex.getMessage());
			return new Impact(
					english ? "Will publish " + defs + " definitions · impact cannot be estimated"
							: "将发布 " + defs + " 个配置定义 · " + IMPACT_UNKNOWN,
					"publish:" + defs + ":unknown");
		}
		String formatted = String.format(Locale.ROOT, "%,d", rows);
		return new Impact(
				english ? "Will publish " + defs + " definitions · " + formatted + " rows affected"
						: "将发布 " + defs + " 个配置定义 · 影响 " + formatted + " 行数据",
				"publish:" + defs + ":" + rows);
	}

	private Impact uploadImpact(Long taskId, int defs, boolean english) {
		Long rows = summedRows(taskId, IMPORT_FILE_TYPE);
		if (rows == null) {
			return new Impact(
					english
							? "Will write " + defs + " definitions into staging · impact cannot be estimated"
							: "将写入 " + defs + " 个配置定义的数据到暂存区 · " + IMPACT_UNKNOWN,
					"import:" + defs + ":unknown");
		}
		String formatted = String.format(Locale.ROOT, "%,d", rows);
		return new Impact(
				english ? "Will write " + defs + " definitions into staging · " + formatted + " rows involved"
						: "将写入 " + defs + " 个配置定义的数据到暂存区 · 涉及 " + formatted + " 行",
				"import:" + defs + ":" + rows);
	}

	private Impact exportImpact(Long taskId, int defs, boolean english) {
		Long rows = summedRows(taskId, EXPORT_FILE_TYPE);
		if (rows == null) {
			return new Impact(
					english ? "Will export " + defs + " definitions · impact cannot be estimated"
							: "将导出 " + defs + " 个配置定义 · " + IMPACT_UNKNOWN,
					"export:" + defs + ":unknown");
		}
		String formatted = String.format(Locale.ROOT, "%,d", rows);
		return new Impact(
				english ? "Will export " + defs + " definitions · " + formatted + " rows involved"
						: "将导出 " + defs + " 个配置定义 · 涉及 " + formatted + " 行数据",
				"export:" + defs + ":" + rows);
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
	 * 报告指纹（8 位十六进制）：对
	 * {@code toolName + target + checks(code/pass/args) + impactFact + conditionFact + publishMode}
	 * 的规范串取 sha256 —— 同一份预检结论必然同指纹，任一检查结论/影响面/条件事实变化即变号。
	 *
	 * <p><b>不吃人读文本</b>（本批收口）：{@code name}/{@code detail}/{@code impact}/{@code conditionSummary}
	 * 的措辞随 {@link ReplyLanguage} 变；若参与哈希，英文请求在批准后（复核线程 locale 可能为 zh）
	 * 必然被判 {@code PRECHECK_STALE}。故只散列结构化事实，校验方可从 {@code asMap()} 的
	 * {@code checks[].code/args} 重算同一口径。
	 */
	private static String reportHash(String toolName, Map<String, Object> target,
			List<PreflightReport.Check> checks, String impactFact, String conditionFact, String publishMode) {
		StringBuilder material = new StringBuilder("tool=").append(toolName);
		material.append("|target=").append(target);
		for (PreflightReport.Check check : checks) {
			material.append("|check=").append(check.code()).append(':').append(check.pass())
					.append(':').append(check.args());
		}
		material.append("|impact=").append(impactFact);
		material.append("|condition=").append(conditionFact);
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
	private static String describeFingerprint(Job precheck, Job.JobType jobType, boolean english) {
		if (precheck == null) {
			return jobType == Job.JobType.IMPORT || jobType == Job.JobType.PUBLISH
					? "" : english ? "This job does not depend on a previous precheck" : "该作业不依赖既往预检查";
		}
		String basis = precheck.getResultJson();
		return (english ? "Precheck job #" : "预检作业 #") + precheck.getId() + " · " + fingerprint4(basis);
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

	/** 检查项参数（偶数位：键、值、键、值……；保持插入顺序，指纹因此稳定）。 */
	private static Map<String, Object> argsOf(Object... keyValues) {
		Map<String, Object> args = new LinkedHashMap<>();
		for (int i = 0; i + 1 < keyValues.length; i += 2) {
			args.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
		}
		return args;
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

	/** 本轮回答语言（与系统提示、错误体同一个取值点；缺失/异常一律中文）。 */
	private static boolean english() {
		return ReplyLanguage.current().english();
	}

	// ── 报告 ────────────────────────────────────────────────────────────────

	/**
	 * 一份只读预检报告（{@code {target, checks, impact, conditionSummary, publishMode, reportHash}}）。
	 * {@link #asMap()} 是它的线上形态：进 {@code PendingToolCall.preflight} → 随
	 * {@code suspended} / {@code confirm_request} 帧下发。
	 */
	public record PreflightReport(boolean pass, Map<String, Object> target, List<Check> checks, String impact,
			String conditionSummary, String publishMode, String reportHash) {

		/** 兼容构造器：{@code conditionSummary}/{@code publishMode} 之前的调用点无需改动。 */
		public PreflightReport(boolean pass, Map<String, Object> target, List<Check> checks, String impact,
				String reportHash) {
			this(pass, target, checks, impact, CONDITION_ALL, null, reportHash);
		}

		/**
		 * 单项检查。
		 *
		 * <ul>
		 * <li>{@code code}：稳定机器码（{@link CheckCode}），<b>协议面</b>；</li>
		 * <li>{@code name}：人读名称（按本轮语言渲染，不参与指纹）；</li>
		 * <li>{@code pass}：布尔结论；</li>
		 * <li>{@code detail}：失败原因/通过依据（人读，按本轮语言渲染）；</li>
		 * <li>{@code args}：结构化参数（如 {@code taskId}/{@code jobType}/{@code undecided}），<b>协议面</b>。</li>
		 * </ul>
		 */
		public record Check(String code, String name, boolean pass, String detail, Map<String, Object> args) {

			public Check {
				// 保留插入顺序（指纹按 args 的规范串计算，顺序必须稳定）；
				// 不用 Map.copyOf：它会拒绝 null 值并丢掉顺序（taskId 可为 null）。
				args = args == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(args));
			}

			/** 兼容构造器：只给名称的旧调用点（码按名称反查，查不到用名称本身）。 */
			public Check(String name, boolean pass, String detail) {
				this(codeOfName(name), name, pass, detail, Map.of());
			}
		}

		/** 按人读名称反查检查码（兼容构造器用；名称改词不影响已登记的码路径）。 */
		private static String codeOfName(String name) {
			for (CheckCode code : CheckCode.values()) {
				if (code.label(false).equals(name) || code.label(true).equals(name)) {
					return code.code();
				}
			}
			return name;
		}

		/** 构造一个检查项（码 + 双语名称 + 参数 + 人读 detail）。 */
		static Check check(CheckCode code, boolean pass, Map<String, Object> args, String detail) {
			return new Check(code.code(), code.label(english()), pass, detail == null ? "" : detail, args);
		}

		/**
		 * 线上形态（固定键序：target → checks → impact → conditionSummary → publishMode → reportHash）。
		 *
		 * <p>AR-02 的<b>三项知情字段</b>只增这三项：{@code impact}（影响面）、
		 * {@code conditionSummary}（条件摘要，后端算好）、{@code publishMode}（权威发布模式）。
		 * 本批给每个检查项增 {@code code}/{@code args}（协议面；{@code name}/{@code detail} 保留为人读）。
		 */
		public Map<String, Object> asMap() {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("target", this.target);
			List<Map<String, Object>> rendered = new ArrayList<>(this.checks.size());
			for (Check check : this.checks) {
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("code", check.code());
				item.put("name", check.name());
				item.put("pass", check.pass());
				item.put("detail", check.detail());
				item.put("args", check.args());
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
		 *
		 * <p>按本轮 {@link ReplyLanguage} 渲染；检查码本身不翻译（模型按码分支）。
		 */
		public String failureMessage() {
			boolean english = english();
			StringBuilder sb = new StringBuilder(english
					? "NOT APPROVED · NOT EXECUTED (preflight failed): this call did not enter the confirm gate and did "
							+ "not execute (the user was not asked to confirm). Failed checks:"
					: NOT_APPROVED_NOT_EXECUTED + "（预检未通过）：本次未进入确认门、未执行"
							+ "（用户未被打扰确认）。未通过项：");
			List<Check> failed = failures();
			for (int i = 0; i < failed.size(); i++) {
				Check check = failed.get(i);
				sb.append('\n').append(i + 1).append(". ").append(check.code()).append(" / ")
					.append(check.name()).append(english ? ": " : "：").append(check.detail());
			}
			sb.append(english
					? "\nFix the reasons above and retry (this failure is retryable); if data files changed, "
							+ "upload again and rerun precheck first."
					: "\n请按上述原因修正后重试（该失败可重试）；如涉及数据文件变更，先重新上传并重跑预检查。");
			return sb.toString();
		}

	}
}
