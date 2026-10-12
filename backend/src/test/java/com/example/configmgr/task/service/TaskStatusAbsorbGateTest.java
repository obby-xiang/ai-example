package com.example.configmgr.task.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.data.repo.ConfigStagingRowRepository;
import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobItemRepository;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.repo.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R-153 B2：CANCELLED 吸收态单调闸（双入口 = {@link TaskService#updateStatus} +
 * {@link TaskService#applyJobOutcome}）。
 *
 * <p>背景（CI MAJOR-2 根因收口）：执行器迟到终态写/僵尸恢复联动曾可把已 CANCELLED 任务翻回
 * COMPLETED/FAILED（无守卫直写，R-152 只封了 {@code JobTerminalWriter} 一条路）。
 * 本闸语义：CANCELLED 为吸收态，COMPLETED/FAILED 不得覆写并留日志；
 * FAILED↔COMPLETED 既有流转保留（rework 合法语义，{@code assertTaskNotFinalized} 的
 * 「COMPLETED 但无成功终作业」放行分支依赖它）；CANCELLED→CANCELLED 由既有幂等短路放行。
 *
 * <p>纯 Mockito：{@link TaskService} 其余依赖以空 mock 充位（本组用例只触 taskRepository 路径）；
 * 日志留痕以 Logback ListAppender 直证。
 */
@ExtendWith(MockitoExtension.class)
class TaskStatusAbsorbGateTest {

    private static final long TASK_ID = 7L;

    @Mock private TaskRepository taskRepository;
    @Mock private TaskItemRepository taskItemRepository;
    @Mock private TaskFileRepository taskFileRepository;
    @Mock private JobRepository jobRepository;
    @Mock private JobItemRepository jobItemRepository;
    @Mock private ValidationIssueRepository issueRepository;
    @Mock private ConfigStagingRowRepository stagingRowRepository;
    @Mock private FileStorageService fileStorage;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private TaskService taskService;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        this.appender = new ListAppender<>();
        this.appender.start();
        ((Logger) LoggerFactory.getLogger(TaskService.class)).addAppender(this.appender);
    }

    @AfterEach
    void detachAppender() {
        ((Logger) LoggerFactory.getLogger(TaskService.class)).detachAppender(this.appender);
    }

    private Task taskWith(Task.TaskStatus status) {
        Task task = new Task();
        task.setId(TASK_ID);
        task.setStatus(status);
        return task;
    }

    /** (a) 定稿 Q1 测试锚：CANCELLED 任务被 stale recovery 扫到（applyJobOutcome(FAILED)）不得翻 FAILED。 */
    @Test
    void staleRecoveryFailedOutcomeCannotFlipCancelledTask() {
        Task cancelled = taskWith(Task.TaskStatus.CANCELLED);
        when(this.taskRepository.findById(TASK_ID)).thenReturn(Optional.of(cancelled));

        this.taskService.applyJobOutcome(TASK_ID, Job.JobStatus.FAILED);

        verify(this.taskRepository, never()).save(any());
        assertThat(cancelled.getStatus()).as("任务态保持 CANCELLED").isEqualTo(Task.TaskStatus.CANCELLED);
        assertThat(this.appender.list).anyMatch(e -> e.getLevel() == Level.INFO
                && e.getFormattedMessage().contains("吸收态单调闸")
                && e.getFormattedMessage().contains("FAILED"));
    }

    /** (b-1) 双入口之一：updateStatus 的迟到 COMPLETED 写被拒。 */
    @Test
    void updateStatusCannotOverwriteCancelledWithCompleted() {
        Task cancelled = taskWith(Task.TaskStatus.CANCELLED);
        when(this.taskRepository.findById(TASK_ID)).thenReturn(Optional.of(cancelled));

        this.taskService.updateStatus(TASK_ID, Task.TaskStatus.COMPLETED);

        verify(this.taskRepository, never()).save(any());
        assertThat(cancelled.getStatus()).isEqualTo(Task.TaskStatus.CANCELLED);
        assertThat(this.appender.list).anyMatch(e -> e.getLevel() == Level.INFO
                && e.getFormattedMessage().contains("吸收态单调闸")
                && e.getFormattedMessage().contains("COMPLETED"));
    }

    /** (b-2) 双入口之二：applyJobOutcome 的迟到 COMPLETED 联动被拒。 */
    @Test
    void applyJobOutcomeCannotOverwriteCancelledWithCompleted() {
        Task cancelled = taskWith(Task.TaskStatus.CANCELLED);
        when(this.taskRepository.findById(TASK_ID)).thenReturn(Optional.of(cancelled));

        this.taskService.applyJobOutcome(TASK_ID, Job.JobStatus.COMPLETED);

        verify(this.taskRepository, never()).save(any());
        assertThat(cancelled.getStatus()).isEqualTo(Task.TaskStatus.CANCELLED);
        assertThat(this.appender.list).anyMatch(e -> e.getLevel() == Level.INFO
                && e.getFormattedMessage().contains("吸收态单调闸")
                && e.getFormattedMessage().contains("COMPLETED"));
    }

    /** 正向对照：FAILED↔COMPLETED 既有流转保留（闸不得过度拦截 rework 语义）。 */
    @Test
    void failedOverCompletedFlowIsStillAllowed() {
        Task completed = taskWith(Task.TaskStatus.COMPLETED);
        when(this.taskRepository.findById(TASK_ID)).thenReturn(Optional.of(completed));

        this.taskService.updateStatus(TASK_ID, Task.TaskStatus.FAILED);

        verify(this.taskRepository, times(1)).save(any());
        assertThat(completed.getStatus()).isEqualTo(Task.TaskStatus.FAILED);
    }

    /** 对照：CANCELLED→CANCELLED 走既有幂等短路（不放行到闸、无拒绝日志、不落库）。 */
    @Test
    void cancelledToCancelledStaysIdempotentShortCircuit() {
        Task cancelled = taskWith(Task.TaskStatus.CANCELLED);
        when(this.taskRepository.findById(TASK_ID)).thenReturn(Optional.of(cancelled));

        this.taskService.applyJobOutcome(TASK_ID, Job.JobStatus.CANCELLED);

        verify(this.taskRepository, never()).save(any());
        assertThat(this.appender.list).noneMatch(e -> e.getFormattedMessage().contains("吸收态单调闸"));
    }

    /** B1 收口的第三写面：writeCompletionStep 对 CANCELLED 任务同样拒（步骤不落、留日志）。 */
    @Test
    void completionStepWriteRefusedOnCancelledTask() {
        Task cancelled = taskWith(Task.TaskStatus.CANCELLED);
        when(this.taskRepository.findById(TASK_ID)).thenReturn(Optional.of(cancelled));

        this.taskService.writeCompletionStep(TASK_ID, "EXPORT");

        verify(this.taskRepository, never()).save(any());
        assertThat(this.appender.list).anyMatch(e -> e.getLevel() == Level.INFO
                && e.getFormattedMessage().contains("吸收态单调闸")
                && e.getFormattedMessage().contains("EXPORT"));
    }

    /** 正向对照：IMPORT→PUBLISH 链上任务已 COMPLETED，完成步骤写照常落（该链守卫不适用）。 */
    @Test
    void completionStepWriteLandsOnCompletedTask() {
        Task completed = taskWith(Task.TaskStatus.COMPLETED);
        when(this.taskRepository.findById(TASK_ID)).thenReturn(Optional.of(completed));

        this.taskService.writeCompletionStep(TASK_ID, "PUBLISH");

        verify(this.taskRepository, times(1)).save(any());
        assertThat(completed.getCurrentStep()).isEqualTo("PUBLISH");
    }
}
