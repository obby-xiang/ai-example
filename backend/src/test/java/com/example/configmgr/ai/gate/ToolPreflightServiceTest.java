package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.common.ConflictException;
import com.example.configmgr.data.repo.ConfigStagingRowRepository;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.service.JobService;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskFile;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.repo.TaskFileRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F5 只读预检的判据用例：作业四项 + 覆盖面三项（配置项/上传文件/目标一致）各自通过/失败，
 * 以及"报告结构（影响面 + 条件摘要 + 权威发布模式）+ 指纹"的口径。
 *
 * <p>判据全部走 {@link JobService} 的只读守卫（{@code finalizedConflict}/{@code inFlightConflict}/
 * {@code precheckConflict}），本类只把 {@link JobService} 换桩 —— 预检"照着执行路径的同一份结论"
 * 这件事由 {@code JobService} 自己保证，这里断言的是"怎么把结论组织成一份可审批的报告"。
 */
class ToolPreflightServiceTest {

	private final JobService jobService = mock(JobService.class);

	private final TaskFileRepository taskFileRepository = mock(TaskFileRepository.class);

	private final ConfigStagingRowRepository stagingRowRepository = mock(ConfigStagingRowRepository.class);

	private final ToolPreflightService service = new ToolPreflightService(this.jobService,
			this.taskFileRepository, this.stagingRowRepository);

	// ── 通过路径 ────────────────────────────────────────────────────────────

	@Test
	void publishPassesWithAllChecksImpactAndFingerprint() {
		stubTask(task(Task.TaskType.IMPORT, Task.TaskStatus.ACTIVE));
		when(this.stagingRowRepository.countByTaskIdAndDefCode(5L, "ORDER")).thenReturn(5_000L);
		when(this.stagingRowRepository.countByTaskIdAndDefCode(5L, "ORDER_LINE")).thenReturn(7_480L);

		ToolPreflightService.PreflightReport report = this.service.inspect("start_publish", "{\"taskId\":5}");

		assertThat(report.pass()).isTrue();
		assertThat(report.checks()).extracting(ToolPreflightService.PreflightReport.Check::name)
			.containsExactly("任务存在", "类型匹配", "无在途作业", "预检指纹新鲜", "配置项齐备", "上传文件齐备", "目标一致");
		assertThat(report.checks()).allMatch(ToolPreflightService.PreflightReport.Check::pass);
		assertThat(report.impact()).isEqualTo("将发布 2 个配置定义 · 影响 12,480 行数据");
		assertThat(report.reportHash()).matches("[0-9a-f]{8}");

		Map<String, Object> map = report.asMap();
		assertThat(map).containsOnlyKeys("target", "checks", "impact", "conditionSummary", "publishMode", "reportHash");
		@SuppressWarnings("unchecked")
		Map<String, Object> target = (Map<String, Object>) map.get("target");
		assertThat(target).containsEntry("taskId", 5L)
			.containsEntry("title", "订单配置导入")
			.containsEntry("type", "IMPORT")
			.containsEntry("status", "ACTIVE");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> checks = (List<Map<String, Object>>) map.get("checks");
		assertThat(checks.get(0)).containsOnlyKeys("name", "pass", "detail");
		assertThat(checks.get(3).get("detail")).asString().isNotEmpty();
	}

	@Test
	void payloadCarriesImpactConditionSummaryAndAuthoritativePublishMode() {
		Task task = task(Task.TaskType.IMPORT, Task.TaskStatus.ACTIVE);
		TaskItem conditioned = item("ORDER", 0);
		conditioned.setConditionJson("{\"scopeKeys\":[\"CN\"],\"fields\":["
				+ "{\"fieldCode\":\"AMOUNT\",\"operator\":\"GT\",\"value\":100}]}");
		task.setItems(List.of(conditioned));
		task.setSettingsJson("{\"importMode\":\"REPLACE\"}");
		stubTask(task);
		when(this.stagingRowRepository.countByTaskIdAndDefCode(5L, "ORDER")).thenReturn(42L);
		when(this.taskFileRepository.findByTaskId(5L)).thenReturn(List.of(uploadFile()));

		ToolPreflightService.PreflightReport report = this.service.inspect("start_publish", "{\"taskId\":5}");

		assertThat(report.pass()).isTrue();
		Map<String, Object> map = report.asMap();
		assertThat(map).containsKeys("impact", "conditionSummary", "publishMode", "reportHash");
		assertThat(map.get("impact")).isEqualTo("将发布 1 个配置定义 · 影响 42 行数据");
		assertThat(String.valueOf(map.get("conditionSummary"))).contains("AMOUNT").contains("大于");
		assertThat(map.get("publishMode")).as("发布模式只能取宿主设置，不得由模型文本决定").isEqualTo("REPLACE");

		Task empty = task(Task.TaskType.IMPORT, Task.TaskStatus.ACTIVE);
		empty.setItems(List.of());
		stubTask(empty);
		ToolPreflightService.PreflightReport failed = this.service.inspect("start_import", "{\"taskId\":5}");
		assertThat(failed.pass()).isFalse();
		assertThat(failed.failureMessage()).as("未进确认门 = 未批准，与已批准未执行必须可区分")
			.startsWith("未批准·未执行")
			.doesNotContain("已批准");
	}

	@Test
	void sameConclusionYieldsSameHashAndChangeYieldsDifferentHash() {
		stubTask(task(Task.TaskType.IMPORT, Task.TaskStatus.ACTIVE));
		when(this.stagingRowRepository.countByTaskIdAndDefCode(anyLong(), any())).thenReturn(10L);

		String first = this.service.inspect("start_publish", "{\"taskId\":5}").reportHash();
		String second = this.service.inspect("start_publish", "{\"taskId\":5}").reportHash();
		assertThat(second).isEqualTo(first);

		when(this.stagingRowRepository.countByTaskIdAndDefCode(anyLong(), any())).thenReturn(11L);
		assertThat(this.service.inspect("start_publish", "{\"taskId\":5}").reportHash()).isNotEqualTo(first);
	}

	// ── 失败路径（逐项） ────────────────────────────────────────────────────

	@Test
	void missingTaskFailsAllChecks() {
		when(this.jobService.findTask(5L)).thenReturn(Optional.empty());
		when(this.jobService.latestPrecheck(5L)).thenReturn(Optional.empty());

		ToolPreflightService.PreflightReport report = this.service.inspect("start_import", "{\"taskId\":5}");

		assertThat(report.pass()).isFalse();
		assertThat(report.failures()).hasSize(7);
		assertThat(report.checks().get(0).detail()).contains("未找到任务 #5");
		assertThat(report.failureMessage()).contains("预检未通过").contains("任务存在");
	}

	@Test
	void missingTaskIdArgumentFailsInsteadOfPassing() {
		ToolPreflightService.PreflightReport report = this.service.inspect("start_export", "{}");

		assertThat(report.pass()).isFalse();
		assertThat(report.target()).containsEntry("taskId", null);
	}

	@Test
	void typeMismatchFails() {
		// 任务本身是导入任务，却要发导出作业
		stubTask(task(Task.TaskType.IMPORT, Task.TaskStatus.ACTIVE));

		ToolPreflightService.PreflightReport report = this.service.inspect("start_export", "{\"taskId\":5}");

		assertThat(report.pass()).isFalse();
		assertThat(failure(report, "类型匹配")).contains("类型 IMPORT").contains("EXPORT");
	}

	@Test
	void jobInFlightFails() {
		stubTask(task(Task.TaskType.IMPORT, Task.TaskStatus.ACTIVE));
		when(this.jobService.inFlightConflict(5L, Job.JobType.PUBLISH))
			.thenReturn(new ConflictException("JOB_ALREADY_RUNNING", "任务 #5 已有同类作业在途：作业 #4221 [PUBLISH] 状态 RUNNING"));

		ToolPreflightService.PreflightReport report = this.service.inspect("start_publish", "{\"taskId\":5}");

		assertThat(report.pass()).isFalse();
		assertThat(failure(report, "无在途作业")).contains("作业 #4221");
	}

	@Test
	void stalePrecheckFailsFingerprintCheck() {
		stubTask(task(Task.TaskType.IMPORT, Task.TaskStatus.ACTIVE));
		when(this.jobService.precheckConflict(5L, Job.JobType.IMPORT))
			.thenReturn(new ConflictException("PRECHECK_STALE", "配置项 ORDER 的预检查结果已失效：UPLOAD 件已被替换"));

		ToolPreflightService.PreflightReport report = this.service.inspect("start_import", "{\"taskId\":5}");

		assertThat(report.pass()).isFalse();
		assertThat(failure(report, "预检指纹新鲜")).contains("已被替换");
	}

	@Test
	void finalizedTaskSaysPlainlyAnotherJobCannotStart() {
		// MINOR-3：终态结论不再借用 ConflictException 原文藏在"类型匹配"里，
		// 检查名/顺序不变，detail 直说"任务已 COMPLETED，不可再发起 PUBLISH 作业"。
		stubTask(task(Task.TaskType.IMPORT, Task.TaskStatus.COMPLETED));
		when(this.jobService.finalizedConflict(5L, Job.JobType.PUBLISH)).thenReturn(new ConflictException(
				"TASK_FINALIZED", "任务 #5 已 COMPLETED（PUBLISH 作业成功），禁止新建作业（TASK_FINALIZED）"));

		ToolPreflightService.PreflightReport report = this.service.inspect("start_publish", "{\"taskId\":5}");

		assertThat(report.pass()).isFalse();
		assertThat(report.checks()).extracting(ToolPreflightService.PreflightReport.Check::name)
			.containsExactly("任务存在", "类型匹配", "无在途作业", "预检指纹新鲜", "配置项齐备", "上传文件齐备", "目标一致");
		assertThat(failure(report, "类型匹配")).isEqualTo("任务 #5 已 COMPLETED，不可再发起 PUBLISH 作业（TASK_FINALIZED）");
	}

	@Test
	void exportDoesNotDependOnPastPrecheckButSaysSo() {
		stubTask(task(Task.TaskType.EXPORT, Task.TaskStatus.ACTIVE));
		when(this.jobService.latestPrecheck(5L)).thenReturn(Optional.empty());

		ToolPreflightService.PreflightReport report = this.service.inspect("start_export", "{\"taskId\":5}");

		assertThat(report.pass()).isTrue();
		assertThat(check(report, "预检指纹新鲜").detail()).contains("不依赖既往预检查");
	}

	// ── 覆盖面补齐（review-03 第 4 项） ─────────────────────────────────────

	@Test
	void missingItemsFailsEvenWhenOldFourChecksPass() {
		Task task = task(Task.TaskType.EXPORT, Task.TaskStatus.ACTIVE);
		task.setItems(List.of());
		stubTask(task);

		ToolPreflightService.PreflightReport report = this.service.inspect("start_export", "{\"taskId\":5}");

		assertThat(report.pass()).isFalse();
		assertThat(failure(report, "配置项齐备")).contains("尚未选择任何配置项");
	}

	@Test
	void missingUploadFailsForImportLikeJobs() {
		Task task = task(Task.TaskType.IMPORT, Task.TaskStatus.ACTIVE);
		stubTask(task);
		when(this.taskFileRepository.findByTaskId(5L)).thenReturn(List.of());

		ToolPreflightService.PreflightReport report = this.service.inspect("start_precheck", "{\"taskId\":5}");

		assertThat(report.pass()).isFalse();
		assertThat(failure(report, "上传文件齐备")).contains("尚未上传数据文件");
	}

	@Test
	void targetMismatchFailsWithWorkspaceTask() {
		stubTask(task(Task.TaskType.IMPORT, Task.TaskStatus.ACTIVE));

		ToolPreflightService.PreflightReport report = this.service.inspect("start_publish", "{\"taskId\":5}",
				AiContext.of("import", "IMPORT", "PUBLISH", 8L, "ACTIVE", Map.of()));

		assertThat(report.pass()).isFalse();
		assertThat(failure(report, "目标一致")).contains("任务 #5").contains("任务 #8");
	}

	// ── 直接 start_export：与 confirm_step 同一套步骤/条件预检（Task 1 第 3 项） ──

	@Test
	void directExportBeforeTheExportStepIsBlocked() {
		Task task = task(Task.TaskType.EXPORT, Task.TaskStatus.ACTIVE);
		task.setCurrentStep("QUERY_COND");
		stubTask(task);

		ToolPreflightService.PreflightReport report = this.service.inspect("start_export", "{\"taskId\":5}");

		assertThat(report.pass()).isFalse();
		assertThat(failure(report, "步骤就绪")).contains("不能直接发起导出").contains("confirm_step");
	}

	@Test
	void directExportWithUndecidedConditionIsBlocked() {
		Task task = task(Task.TaskType.EXPORT, Task.TaskStatus.ACTIVE);
		task.setCurrentStep("EXPORT");
		task.getItems().get(0).setConditionJson(null);
		task.getItems().get(1).setConditionJson(null);
		stubTask(task);

		ToolPreflightService.PreflightReport report = this.service.inspect("start_export", "{\"taskId\":5}");

		assertThat(report.pass()).isFalse();
		assertThat(failure(report, "条件已决定")).contains("条件未决定").contains("set_condition");
	}

	@Test
	void directExportWithExplicitlyUnsetConditionPasses() {
		Task task = task(Task.TaskType.EXPORT, Task.TaskStatus.ACTIVE);
		task.setCurrentStep("EXPORT");
		task.getItems().get(0).setConditionJson("{}");
		task.getItems().get(1).setConditionJson("{}");
		stubTask(task);

		ToolPreflightService.PreflightReport report = this.service.inspect("start_export", "{\"taskId\":5}");

		assertThat(report.pass()).isTrue();
		assertThat(report.conditionSummary()).contains("未设筛选");
	}

	@Test
	void directExportWithNonEqConditionPasses() {
		Task task = task(Task.TaskType.EXPORT, Task.TaskStatus.ACTIVE);
		task.setCurrentStep("EXPORT");
		task.getItems().get(0).setConditionJson(
				"{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"IN\",\"value\":[\"CNY\",\"USD\"]}]}");
		task.getItems().get(1).setConditionJson("{}");
		stubTask(task);

		ToolPreflightService.PreflightReport report = this.service.inspect("start_export", "{\"taskId\":5}");

		assertThat(report.pass()).isTrue();
		assertThat(report.conditionSummary()).contains("IN").contains("CNY");
	}

	/** 步骤未知不得"两边都放行"（fail-closed）：直接 start_export 不能绕过步骤事实。 */
	@Test
	void directExportWithUnknownStepFailsClosed() {
		Task task = task(Task.TaskType.EXPORT, Task.TaskStatus.ACTIVE);
		task.setCurrentStep(null);
		task.getItems().get(0).setConditionJson("{}");
		task.getItems().get(1).setConditionJson("{}");
		stubTask(task);

		ToolPreflightService.PreflightReport report = this.service.inspect("start_export", "{\"taskId\":5}");

		assertThat(report.pass()).isFalse();
		assertThat(failure(report, "步骤就绪")).contains("步骤未知").contains("不能确认");
	}

	@Test
	void publishModeNeverComesFromConditionText() {
		Task task = task(Task.TaskType.IMPORT, Task.TaskStatus.ACTIVE);
		task.setSettingsJson("{\"importMode\":\"MERGE\"}");
		stubTask(task);

		ToolPreflightService.PreflightReport report = this.service.inspect("start_publish", "{\"taskId\":5}",
				AiContext.of("import", "IMPORT", "PUBLISH", 5L, "ACTIVE",
						Map.of("importMode", "REPLACE")));

		assertThat(report.publishMode()).as("任务设置优先于上下文/模型文本").isEqualTo("MERGE");
	}

	@Test
	void createTaskPreflightValidatesTypeAndCarriesCreationImpact() {
		ToolPreflightService.PreflightReport report = this.service.inspect("create_task",
				"{\"type\":\"EXPORT\",\"title\":\"季度导出\"}", AiContext.empty());

		assertThat(report.pass()).isTrue();
		assertThat(report.impact()).contains("创建").contains("创建≠执行");
		assertThat(report.conditionSummary()).contains("创建任务");
		assertThat(report.asMap().get("publishMode")).isNull();

		ToolPreflightService.PreflightReport bad = this.service.inspect("create_task", "{\"type\":\"NOPE\"}");
		assertThat(bad.pass()).isFalse();
	}

	// ── helpers ────────────────────────────────────────────────────────────

	private void stubTask(Task task) {
		when(this.jobService.findTask(task.getId())).thenReturn(Optional.of(task));
		when(this.jobService.finalizedConflict(eq(task.getId()), any())).thenReturn(null);
		when(this.jobService.inFlightConflict(eq(task.getId()), any())).thenReturn(null);
		when(this.jobService.precheckConflict(eq(task.getId()), any())).thenReturn(null);
		when(this.jobService.latestPrecheck(task.getId())).thenReturn(Optional.of(precheck()));
		when(this.taskFileRepository.findByTaskId(task.getId())).thenReturn(List.of(uploadFile()));
	}

	private static TaskFile uploadFile() {
		TaskFile file = new TaskFile();
		file.setTaskId(5L);
		file.setDefCode("ORDER");
		file.setFileType("UPLOAD");
		file.setStoragePath("./target/preflight/ORDER.xlsx");
		file.setRowCount(42);
		return file;
	}

	private static Job precheck() {
		Job job = new Job();
		job.setId(9L);
		job.setJobType(Job.JobType.PRECHECK);
		job.setStatus(Job.JobStatus.COMPLETED);
		job.setErrorCount(0);
		job.setResultJson("{\"ORDER\":{\"UPLOAD\":{\"sha256\":\"abc\",\"size\":3}}}");
		return job;
	}

	private static Task task(Task.TaskType type, Task.TaskStatus status) {
		Task task = new Task();
		task.setId(5L);
		task.setTitle("订单配置导入");
		task.setType(type);
		task.setStatus(status);
		task.setCurrentStep(type == Task.TaskType.EXPORT ? "EXPORT" : null);
		task.setItems(List.of(item("ORDER", 0), item("ORDER_LINE", 1)));
		return task;
	}

	private static TaskItem item(String defCode, int sortOrder) {
		TaskItem item = new TaskItem();
		item.setDefCode(defCode);
		item.setSortOrder(sortOrder);
		// 默认"明确不设"（与前端清空后的落库形态一致）：本类的既有用例关注其它检查项
		item.setConditionJson("{}");
		return item;
	}

	private static ToolPreflightService.PreflightReport.Check check(ToolPreflightService.PreflightReport report,
			String name) {
		return report.checks().stream().filter(c -> name.equals(c.name())).findFirst().orElseThrow();
	}

	private static String failure(ToolPreflightService.PreflightReport report, String name) {
		ToolPreflightService.PreflightReport.Check check = check(report, name);
		assertThat(check.pass()).as(name + " 应失败").isFalse();
		return check.detail();
	}

}
