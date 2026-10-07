package com.example.configmgr.job.service;

import com.example.configmgr.job.entity.Job;
import com.example.configmgr.task.service.TaskService;
import com.example.configmgr.task.service.TaskSseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

/**
 * 作业终态写入器：把"作业终态 + 任务态联动 + JOB_DONE 推送"合成一步，
 * 并保证它们在<b>数据面事务提交之后</b>落地。
 *
 * <h2>为什么必须滞后到提交之后</h2>
 * 执行器（IMPORT/EXPORT/PRECHECK）的整轮数据面写入在自己的事务里，事务提交发生在
 * {@code @Transactional} 方法<b>返回时</b>。若在方法体内写终态（独立事务），
 * 客户端可能先看到"作业 COMPLETED"、再读到尚未提交的数据 —— 反向的"库领先于事件"，
 * 同样是快照不可信。这里把终态写入登记为 {@code afterCommit}，于是顺序恒为
 * "数据落库 → 作业终态可见 → JOB_DONE 推送"。
 *
 * <h2>为什么整步必须跑在 REQUIRES_NEW 事务里</h2>
 * {@code afterCommit} 回调仍处于"原事务资源已绑定到本线程、但事务已提交"的临界态：
 * 此时用 {@code REQUIRED} 打开的业务写入会<b>静默加入那个已完成的旧事务</b>而不落库
 * （实测：作业行写了、任务行没写）。故整步包在 {@code REQUIRES_NEW} 里，
 * 悬挂旧资源、开新事务提交。
 *
 * <h2>⑥ 任务态联动</h2>
 * 三个写入路径之一（另两处：{@link JobService#cancel} 取消联动、
 * {@link StaleJobRecoveryRunner} 僵尸恢复联动）：
 * COMPLETED/FAILED/CANCELLED → 任务态同步为同名终态（幂等，见 {@link TaskService#applyJobOutcome}）。
 */
@Slf4j
@Component
public class JobTerminalWriter {

    private final JobProgressService progressService;
    private final TaskService taskService;
    private final TaskSseService taskSseService;
    private final TransactionTemplate requiresNew;

    public JobTerminalWriter(JobProgressService progressService,
                            TaskService taskService,
                            TaskSseService taskSseService,
                            PlatformTransactionManager transactionManager) {
        this.progressService = progressService;
        this.taskService = taskService;
        this.taskSseService = taskSseService;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 写作业终态 + 联动任务态 + 推送 JOB_DONE。
     *
     * @param job             作业（只用其 id/taskId，不写实体）
     * @param jobStatus       终态
     * @param processed/total 行级进度（保持快照口径一致）
     */
    public void complete(Job job, Job.JobStatus jobStatus, int errorCount, int warningCount,
                         int processed, int total, Map<String, Object> doneEvent) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    apply(job, jobStatus, errorCount, warningCount, processed, total, doneEvent);
                }
            });
        } else {
            apply(job, jobStatus, errorCount, warningCount, processed, total, doneEvent);
        }
    }

    private void apply(Job job, Job.JobStatus jobStatus, int errorCount, int warningCount,
                       int processed, int total, Map<String, Object> doneEvent) {
        try {
            requiresNew.executeWithoutResult(status -> {
                progressService.finish(job.getId(), jobStatus, errorCount, warningCount, processed, total);
                taskService.applyJobOutcome(job.getTaskId(), jobStatus);
            });
            taskSseService.publish(job.getTaskId(), "JOB_DONE", doneEvent);
        } catch (Exception e) {
            // 终态补记失败不能让作业线程崩掉：日志留痕，快照下一次读取仍能修正
            log.error("作业 {} 终态写入失败: {}", job.getId(), e.getMessage(), e);
        }
    }
}
