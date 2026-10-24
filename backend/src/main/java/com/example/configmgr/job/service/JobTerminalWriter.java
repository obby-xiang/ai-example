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
     * 写作业终态 + 联动任务态 + 推送 JOB_DONE（无成功步骤联动的历史形态，等价于 successStep=null）。
     *
     * @param job             作业（只用其 id/taskId，不写实体）
     * @param jobStatus       终态
     * @param processed/total 行级进度（保持快照口径一致）
     */
    public void complete(Job job, Job.JobStatus jobStatus, int errorCount, int warningCount,
                         int processed, int total, Map<String, Object> doneEvent) {
        complete(job, jobStatus, errorCount, warningCount, processed, total, doneEvent, null);
    }

    /**
     * R-153 B1：带成功步骤联动的终态写入。仅当终态<b>实际落写</b>（finish 先到者胜返回 true）
     * 且 jobStatus 为 COMPLETED 时，才在同一个 REQUIRES_NEW 事务内联动
     * {@code goToStep(successStep)} —— 原先 ExportJobRunner/PublishJobRunner 在执行器事务内
     * 无条件直写任务 COMPLETED 的路径（取消竞态翻写点，CI MAJOR-2 根因）由此收编：
     * 取消胜出时 finish 被丢弃 ⇒ 任务态/步骤零联动。
     *
     * @param successStep 成功步骤名（EXPORT/PUBLISH）；null = 不做步骤联动
     */
    public void complete(Job job, Job.JobStatus jobStatus, int errorCount, int warningCount,
                         int processed, int total, Map<String, Object> doneEvent, String successStep) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    apply(job, jobStatus, errorCount, warningCount, processed, total, doneEvent, successStep);
                }
            });
        } else {
            apply(job, jobStatus, errorCount, warningCount, processed, total, doneEvent, successStep);
        }
    }

    /**
     * R1-13 / begin 失败收敛：在 {@code afterCommit} 回调里立即执行终态写入，不再注册新的
     * transaction synchronization（已提交的事务不会再回调它）。内部仍走 REQUIRES_NEW + 条件
     * finish，取消/先到终态的作业不会被复写。
     */
    public void completeNow(Job job, Job.JobStatus jobStatus, int errorCount, int warningCount,
                            int processed, int total, Map<String, Object> doneEvent) {
        apply(job, jobStatus, errorCount, warningCount, processed, total, doneEvent, null);
    }

    private void apply(Job job, Job.JobStatus jobStatus, int errorCount, int warningCount,
                       int processed, int total, Map<String, Object> doneEvent, String successStep) {
        try {
            // R-152：仅当 finish 实际落写终态（作业仍 PENDING/RUNNING）才联动任务态并推 JOB_DONE。
            // 迟到终态写被先到者胜守卫拦下时（作业已 CANCELLED/其它终态），不得 applyJobOutcome——
            // 否则任务行会被执行器迟到的 COMPLETED 翻回、破坏取消语义，终态守卫随之放行（CI MAJOR-2 根因）。
            // R-153 B1：goToStep 的终态守卫（goToStep 版）对「任务 COMPLETED + 最终作业成功」恒抛
            // TASK_FINALIZED——finish 落写后（独立事务，作业终态先行可见）该形态恒成立（IMPORT→PUBLISH
            // 链上任务在 IMPORT 完成时即已 COMPLETED），故完成步骤走专用收口 writeCompletionStep
            // （不走外部跳步守卫、CANCELLED 仍拒）；任务态写统一走 applyJobOutcome（受 B2 吸收态闸保护）。
            boolean wroteTerminal = Boolean.TRUE.equals(requiresNew.execute(status -> {
                // 不变量：progressService.finish 内部必须用 REQUIRES_NEW 并先行提交（见
                // JobProgressService#finish），因此此处已不持有 job 行锁。随后统一按
                // task → job 之外的方向写 task：先锁 task，再 writeCompletionStep/applyJobOutcome。
                // 若未来把 finish 改成 REQUIRED，这里会变成“持 job 锁等 task”，与删除的
                // task → job 形成死锁；JobProgressServiceTerminalGuardTest 固化 REQUIRES_NEW 语义。
                boolean wrote = progressService.finish(job.getId(), jobStatus, errorCount, warningCount,
                        processed, total);
                if (wrote) {
                    // R1-13/I-3：job 终态已独立提交，此刻没有 job 行锁；锁 task 后再写任务态。
                    // 任务已被删除时不再写（避免对已删 task 做联动），避免与删除的 task 锁互相等待。
                    if (taskService.tryLockForUpdate(job.getTaskId()).isEmpty()) {
                        log.info("作业 #{} 的终态已落，但任务 #{} 已被删除，跳过任务态联动",
                                job.getId(), job.getTaskId());
                        return wrote;
                    }
                    if (jobStatus == Job.JobStatus.COMPLETED && successStep != null) {
                        taskService.writeCompletionStep(job.getTaskId(), successStep);
                    }
                    taskService.applyJobOutcome(job.getTaskId(), jobStatus);
                }
                return wrote;
            }));
            if (wroteTerminal) {
                taskSseService.publish(job.getTaskId(), "JOB_DONE", doneEvent);
            } else {
                log.info("作业 #{} 终态写入被丢弃（已收终态，先到者胜），不联动任务态、不发 JOB_DONE：taskId={}，预期结局={}",
                        job.getId(), job.getTaskId(), jobStatus);
            }
        } catch (Exception e) {
            // 终态补记失败不能让作业线程崩掉：日志留痕，快照下一次读取仍能修正
            log.error("作业 {} 终态写入失败: {}", job.getId(), e.getMessage(), e);
        }
    }
}
