package com.example.configmgr.job.service;

import com.example.configmgr.job.entity.Job;
import com.example.configmgr.task.service.TaskService;
import com.example.configmgr.task.service.TaskSseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R-152（取消/迟到终态写竞态）：{@link JobTerminalWriter} 仅在 {@link JobProgressService#finish}
 * 实际落写终态时才联动任务态并推 JOB_DONE —— 迟到终态写被先到者胜守卫拦下（返回 false）时，
 * 不得调用 {@link TaskService#applyJobOutcome}，否则任务行会被执行器迟到的 COMPLETED 翻回、
 * 破坏取消语义（CI E2E MAJOR-2 四连红根因）。
 *
 * <p>纯 Mockito：事务管理器直通（{@link SimpleTransactionStatus}），聚焦"finish 返回值 → 联动"
 * 的条件分派，不重复测 finish 自身的终态守卫（由 {@link JobProgressServiceTerminalGuardTest} 覆盖）。
 */
class JobTerminalWriterTest {

    private final JobProgressService progressService = mock(JobProgressService.class);
    private final TaskService taskService = mock(TaskService.class);
    private final TaskSseService taskSseService = mock(TaskSseService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    private JobTerminalWriter writer;

    @BeforeEach
    void passThroughTransaction() {
        when(this.transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        this.writer = new JobTerminalWriter(this.progressService, this.taskService,
                this.taskSseService, this.transactionManager);
    }

    @Test
    void discardedFinishDoesNotLinkTaskOutcomeNorPublish() {
        Job job = new Job();
        job.setId(1L);
        job.setTaskId(10L);
        when(this.progressService.finish(anyLong(), any(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(false);

        this.writer.complete(job, Job.JobStatus.COMPLETED, 0, 0, 10, 10, Map.of());

        verify(this.taskService, never()).applyJobOutcome(anyLong(), any());
        verify(this.taskSseService, never()).publish(anyLong(), anyString(), any());
    }

    @Test
    void writtenFinishLinksTaskOutcomeExactlyOnceAndPublishes() {
        Job job = new Job();
        job.setId(2L);
        job.setTaskId(20L);
        when(this.progressService.finish(anyLong(), any(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(true);

        this.writer.complete(job, Job.JobStatus.COMPLETED, 0, 0, 10, 10, Map.of());

        verify(this.taskService).applyJobOutcome(20L, Job.JobStatus.COMPLETED);
        verify(this.taskSseService).publish(20L, "JOB_DONE", Map.of());
    }
}
