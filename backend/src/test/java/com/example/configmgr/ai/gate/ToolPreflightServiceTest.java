package com.example.configmgr.ai.gate;

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
 * F5 只读预检的判据用例：四项检查各自通过/失败，以及"报告结构 + 指纹 + 影响面"的口径。
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
	void publishPassesWithFourChecksImpactAndFingerprint() {
		stubTask(task(Task.TaskType.IMPORT, Task.TaskStatus.ACTIVE));
		when(this.stagingRowRepository.countByTaskIdAndDefCode(5L, "ORDER")).thenReturn(5_000L);
		when(this.stagingRowRepository.countByTaskIdAndDefCode(5L, "ORDER_LINE")).thenReturn(7_480L);

		ToolPreflightService.PreflightReport report = this.service.inspect("start_publish", "{\"taskId\":5}");

		assertThat(report.pass()).isTrue();
		assertThat(report.checks()).extracting(ToolPreflightService.PreflightReport.Check::name)
			.containsExactly("任务存在", "类型匹配", "无在途作业", "预检指纹新鲜");
		assertThat(report.checks()).allMatch(ToolPreflightService.PreflightReport.Check::pass);
		assertThat(report.impact()).isEqualTo("将发布 2 个配置定义 · 影响 12,480 行数据");
		assertThat(report.reportHash()).matches("[0-9a-f]{8}");

		Map<String, Object> map = report.asMap();
		assertThat(map).containsOnlyKeys("target", "checks", "impact", "reportHash");
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
	void missingTaskFailsAllFourChecks() {
		when(this.jobService.findTask(5L)).thenReturn(Optional.empty());
		when(this.jobService.latestPrecheck(5L)).thenReturn(Optional.empty());

		ToolPreflightService.PreflightReport report = this.service.inspect("start_import", "{\"taskId\":5}");

		assertThat(report.pass()).isFalse();
		assertThat(report.failures()).hasSize(4);
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
			.containsExactly("任务存在", "类型匹配", "无在途作业", "预检指纹新鲜");
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

	// ── helpers ────────────────────────────────────────────────────────────

	private void stubTask(Task task) {
		when(this.jobService.findTask(task.getId())).thenReturn(Optional.of(task));
		when(this.jobService.finalizedConflict(eq(task.getId()), any())).thenReturn(null);
		when(this.jobService.inFlightConflict(eq(task.getId()), any())).thenReturn(null);
		when(this.jobService.precheckConflict(eq(task.getId()), any())).thenReturn(null);
		when(this.jobService.latestPrecheck(task.getId())).thenReturn(Optional.of(precheck()));
		when(this.taskFileRepository.findByTaskId(task.getId())).thenReturn(List.of());
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
		task.setItems(List.of(item("ORDER", 0), item("ORDER_LINE", 1)));
		return task;
	}

	private static TaskItem item(String defCode, int sortOrder) {
		TaskItem item = new TaskItem();
		item.setDefCode(defCode);
		item.setSortOrder(sortOrder);
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
