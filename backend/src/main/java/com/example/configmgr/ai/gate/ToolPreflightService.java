package com.example.configmgr.ai.gate;

import com.example.configmgr.common.CodedIllegalArgumentException;
import com.example.configmgr.common.CodedIllegalStateException;
import com.example.configmgr.common.ErrorCodes;

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
 * 只认四个进确认门的工具（{@link #CONFIRM_TOOLS}）。判定靠<b>工具名</b>而不是风险等级：
 * 测试/未来新增的 DANGER 工具不该被这里"顺手"改写行为（{@link #supports(String)} 为 false 即完全跳过）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolPreflightService {

	public static final String TOOL_EXPORT = "start_export";

	public static final String TOOL_PRECHECK = "start_precheck";

	public static final String TOOL_IMPORT = "start_import";

	public static final String TOOL_PUBLISH = "start_publish";

	/** 进确认门、因而需要预检的工具（与 {@code AiTools} 的四个 {@code @ToolRisk(DANGER)} 一一对应）。 */
	private static final Set<String> CONFIRM_TOOLS = Set.of(TOOL_EXPORT, TOOL_PRECHECK, TOOL_IMPORT, TOOL_PUBLISH);

	public static final String CHECK_TASK_EXISTS = "任务存在";

	public static final String CHECK_TYPE_MATCH = "类型匹配";

	public static final String CHECK_NO_JOB_IN_FLIGHT = "无在途作业";

	public static final String CHECK_FRESH_PRECHECK = "预检指纹新鲜";

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
		Job.JobType jobType = jobTypeOf(toolName);
		Long taskId = taskIdOf(argumentsJson);
		Optional<Task> task = jobService.findTask(taskId);

		Map<String, Object> target = targetOf(taskId, task);
		List<PreflightReport.Check> checks = new ArrayList<>(4);

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

		String impact = impactOf(toolName, taskId, task.orElse(null));
		boolean pass = checks.stream().allMatch(PreflightReport.Check::pass);
		String reportHash = reportHash(toolName, target, checks, impact);
		return new PreflightReport(pass, target, checks, impact, reportHash);
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
			List<PreflightReport.Check> checks, String impact) {
		StringBuilder material = new StringBuilder("tool=").append(toolName);
		material.append("|target=").append(target);
		for (PreflightReport.Check check : checks) {
			material.append("|check=").append(check.name()).append(':').append(check.pass())
					.append(':').append(check.detail());
		}
		material.append("|impact=").append(impact);
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
			String reportHash) {

		/** 单项检查（{@code name} 人读、{@code pass} 布尔、{@code detail} 失败原因或通过依据）。 */
		public record Check(String name, boolean pass, String detail) {
		}

		/** 线上形态（固定键序：target → checks → impact → reportHash）。 */
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
			StringBuilder sb = new StringBuilder("预检未通过，本次未进入确认门、未执行（用户未被打扰确认）。未通过项：");
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
