package com.example.configmgr.job.service;

import com.example.configmgr.config.AppProperties;
import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.excel.ExcelWriter;
import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.ValidationIssue;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.service.TaskService;
import com.example.configmgr.task.service.TaskSseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3-3 用例（作业级）：{@link ExportJobRunner} 的<b>外层作业级 catch</b> 必须落一条
 * 作业级 {@code validation_issues} 条目，{@code defCode} 用哨兵 {@code "-"}。
 *
 * <p>为什么用纯 Mockito（不引 Spring）：外层 catch 只覆盖"循环内、项级 try 之外"的语句
 * （{@code cancellationRegistry.isCancelled} / {@code progressService.startItem}），
 * 真实依赖没有稳定的可触发失败路径 —— 按设计卡的口径用<b>可注入的假件</b>触发，
 * 而不是为了测试改生产代码。本形态与 {@code ConfirmGateWiringTest} 的手工装配一致。
 *
 * <p>哨兵选择的理由（A1 裁定）：{@code validation_issues.def_code} 为 NOT NULL
 * （VARCHAR(64)），作业级失败没有配置项归属 —— 空串不可读、{@code null} 违反列约束，
 * 改表结构又超出本棒范围。
 */
class ExportJobRunnerJobLevelIssueTest {

    private static final String SYS_PARAM = "SYS_PARAM";

    private final DefinitionService definitionService = mock(DefinitionService.class);

    private final ConfigDataRowRepository dataRowRepository = mock(ConfigDataRowRepository.class);

    private final TaskItemRepository taskItemRepository = mock(TaskItemRepository.class);

    private final TaskService taskService = mock(TaskService.class);

    private final TaskFileRepository taskFileRepository = mock(TaskFileRepository.class);

    private final ExcelWriter excelWriter = mock(ExcelWriter.class);

    private final FileStorageService fileStorage = mock(FileStorageService.class);

    private final TaskSseService taskSseService = mock(TaskSseService.class);

    private final AppProperties appProperties = new AppProperties();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final JobCancellationRegistry cancellationRegistry = mock(JobCancellationRegistry.class);

    private final JobProgressService progressService = mock(JobProgressService.class);

    private final JobTerminalWriter terminalWriter = mock(JobTerminalWriter.class);

    private final ValidationIssueRepository issueRepository = mock(ValidationIssueRepository.class);

    private final ExportJobRunner runner = new ExportJobRunner(this.definitionService, this.dataRowRepository,
            this.taskItemRepository, this.taskService, this.taskFileRepository, this.excelWriter, this.fileStorage,
            this.taskSseService, this.appProperties, this.objectMapper, this.cancellationRegistry,
            this.progressService, this.terminalWriter, this.issueRepository);

    @Test
    void outerFailureWritesJobLevelIssueWithSentinelDefCode() {
        TaskItem item = new TaskItem();
        item.setTaskId(1L);
        item.setDefCode(SYS_PARAM);
        item.setSortOrder(0);
        when(this.taskItemRepository.findByTaskIdOrderBySortOrder(1L)).thenReturn(List.of(item));
        // 循环内、项级 try 之外的第一个语句即失败 ⇒ 走外层作业级 catch
        when(this.cancellationRegistry.isCancelled(anyLong()))
                .thenThrow(new IllegalStateException("模拟外层中断：任务条目读取后的不可恢复错误"));

        Job job = new Job();
        job.setId(77L);
        job.setTaskId(1L);
        job.setJobType(Job.JobType.EXPORT);
        job.setStatus(Job.JobStatus.PENDING);

        this.runner.run(job);

        // 幂等清理先执行（与 PrecheckJobRunner 同口径）
        verify(this.issueRepository).deleteByJobId(77L);

        ArgumentCaptor<ValidationIssue> captor = ArgumentCaptor.forClass(ValidationIssue.class);
        verify(this.issueRepository).save(captor.capture());
        ValidationIssue issue = captor.getValue();
        assertThat(issue.getJobId()).isEqualTo(77L);
        assertThat(issue.getDefCode()).as("作业级 issue 的 defCode = 哨兵 \"-\"").isEqualTo("-");
        assertThat(issue.getSeverity()).isEqualTo(ValidationIssue.Severity.ERROR);
        assertThat(issue.getMessage()).contains("IllegalStateException");
        assertThat(issue.getMessage()).contains("导出作业失败");
        assertThat(issue.getMessage().length()).isLessThanOrEqualTo(1024);
        assertThat(issue.getRowKey()).isNull();
        assertThat(issue.getFieldCode()).isNull();

        // 终态仍以 FAILED 收尾（外层 catch 的既有语义不变）
        verify(this.terminalWriter).complete(any(Job.class), org.mockito.ArgumentMatchers.eq(Job.JobStatus.FAILED),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), any());
    }
}
