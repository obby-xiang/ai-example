package com.example.configmgr.job.service;

import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobItemRepository;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.task.service.TaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 服务重启后的僵尸作业恢复（ADR-8 修正①② / Q5 / Q6 / Q16④）。
 *
 * <h2>① 扫描范围 = PENDING + RUNNING</h2>
 * 基座实测崩溃残留<b>多为 PENDING</b>（作业已落库、异步线程还没开工就断电）——只扫 RUNNING 会漏；
 * 这里两类一起收。
 *
 * <h2>② 恢复动作 = 标记 FAILED + 联动回写任务态，不自动重排队</h2>
 * 僵尸作业没有"从中断点继续"的语义（暂存/发布进度不可续），故一律 FAILED；
 * 任务态同步为 FAILED（{@link TaskService#applyJobOutcome}），列表口径随之可见"可恢复"。
 * 重跑由用户在任务中心一键恢复（FR-4.2）显式发起，<b>不自动重排队</b>（Q5 定案）。
 *
 * <h2>幂等（重复重启不重复处理）</h2>
 * 处理完即写终态，第二次重启扫描集合为空 —— 无重复处理、无重复事件。
 * 终态写入是条件 UPDATE（{@code status IN (PENDING, RUNNING)}），重复扫描/多实例并发时
 * 先到者胜，迟到的恢复影响 0 行，不再联动任务态。
 *
 * <h2>多实例（P1-6）</h2>
 * 共享 DB/Redis 下，PENDING/RUNNING 作业可能属于<b>其它实例仍在运行的 runner</b>。
 * 恢复前逐个读 {@link JobHeartbeatRegistry#isRunnerAlive(Long)}：活跃则跳过，绝不把它判 FAILED；
 * 读取失败（Redis 不可用）同样跳过（fail-closed），把"无法确认"留给删除闸门 409，
 * 而不是用一个错误的终态掩盖不确定性。本机旧 PID 的键由
 * {@link RunnerActiveKeyCleanupRunner} 在恢复<b>之前</b>清理（@Order 已调至恢复之前）。
 *
 * <h2>有界资源占用（线程修复-后端）</h2>
 * 旧实现是"一个 {@code @Transactional} 里 {@code findAll()} + 逐作业 Redis/写库"，三个放大面：
 * <ul>
 * <li>{@code findAll()} 水合<b>全库</b>作业行，连同 {@code Job.items}（EAGER）与
 *     {@code result_json}（CLOB）常驻一级缓存，堆峰值与启动时长随历史作业数线性增长；</li>
 * <li>1 + N 条子查询在同一个连接上串行执行；</li>
 * <li>循环内的 Redis 往返（{@code isRunnerAlive}）与写库都在那一个长事务里 —— 持连接等网络。</li>
 * </ul>
 * 现形态：
 * <ol>
 * <li><b>候选分页</b>：{@link JobRepository#findInFlightRefsAfter} 只投影恢复要用的列、只取在途状态，
 *     每页 {@link #BATCH_SIZE} 条 keyset 前进 —— 任何时刻堆上只有一页 id + 标量，不水合条目/CLOB；</li>
 * <li><b>逐作业短事务 + 逐作业异常隔离</b>：{@link TransactionTemplate}（{@code REQUIRED}）包住
 *     "锁 task → 判活 → 条件终态 → 条目收敛 → 任务联动"五步。生产环境（无外层事务）就是每个
 *     作业一个短事务，锁的持有范围只覆盖该作业的写入；单个作业抛异常只回滚该作业，记录后
 *     继续处理后续作业。调用方已有事务时（单测的 {@code @Transactional}）加入外层事务，语义不变；</li>
 * <li><b>写终态走条件 UPDATE 且不回写旧进度</b>
 *     （{@link JobRepository#finishInterruptedIfInFlight}）：不再
 *     {@code 读实体 → setStatus → save} 整行回写；只迁移 status + finishedAt，
 *     {@code progress/total} 等列保持数据库当前值（候选分页读到的是扫描时刻的旧快照）。
 *     取消/终态竞态不会把作业翻回 FAILED。</li>
 * </ol>
 */
@Slf4j
@Component
@Order(0)
public class StaleJobRecoveryRunner implements ApplicationRunner {

    /** 每页候选数（只含 id 与标量列；不水合 items/CLOB）。 */
    private static final int BATCH_SIZE = 50;

    private static final List<Job.JobStatus> IN_FLIGHT =
            List.of(Job.JobStatus.PENDING, Job.JobStatus.RUNNING);

    private final JobRepository jobRepository;
    private final JobItemRepository jobItemRepository;
    private final TaskService taskService;
    private final JobHeartbeatRegistry heartbeatRegistry;
    private final TransactionTemplate perJob;

    public StaleJobRecoveryRunner(JobRepository jobRepository,
                                  JobItemRepository jobItemRepository,
                                  TaskService taskService,
                                  JobHeartbeatRegistry heartbeatRegistry,
                                  PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.jobItemRepository = jobItemRepository;
        this.taskService = taskService;
        this.heartbeatRegistry = heartbeatRegistry;
        // REQUIRED：生产（无外层事务）逐作业开短事务；已有事务（单测/嵌入调用）则加入，语义不变。
        this.perJob = new TransactionTemplate(transactionManager);
        this.perJob.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public void run(ApplicationArguments args) {
        long afterId = 0L;
        int scanned = 0;
        int recovered = 0;
        while (true) {
            List<JobRepository.InFlightJobRef> batch =
                    jobRepository.findInFlightRefsAfter(afterId, IN_FLIGHT, PageRequest.of(0, BATCH_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            for (JobRepository.InFlightJobRef ref : batch) {
                afterId = Math.max(afterId, ref.getId());
                scanned++;
                // 逐作业隔离：单个作业的锁/判活/写库失败不得中断整轮恢复。生产环境每个作业
                // 是独立短事务，异常只回滚该作业；记录后继续下一页/下一个作业。
                try {
                    if (isRecoveryWritten(ref)) {
                        recovered++;
                    }
                } catch (RuntimeException e) {
                    log.error("Stale job recovery failed for one job, continuing jobId={} taskId={}",
                            ref.getId(), ref.getTaskId(), e);
                }
            }
        }
        if (scanned == 0) {
            if (log.isDebugEnabled()) {
                log.debug("Stale job recovery: nothing to do");
            }
            return;
        }
        log.warn("Stale job recovery finished, jobs marked FAILED count={}", recovered);
    }

    /**
     * 单作业恢复（一个短事务）。返回 true = 本次实际把作业写成 FAILED 并联动了任务态。
     *
     * <p>写顺序与其它任务态写路径一致（R1-13）：先锁 task，再写 job，最后联动 task；
     * 任务已被删除时跳过该作业（避免对已删 job 行做写而把它重新插回，历史孤儿作业也走这条）。
     * 跳过后的兜底链不变：作业保持 PENDING/RUNNING，由 JobWatchdog 的超时判据收敛。
     *
     * <p>本方法抛出的 {@link RuntimeException} 由 {@link #run(ApplicationArguments)} 逐作业捕获：
     * 事务已随异常回滚，不影响其它作业的恢复。
     */
    private boolean isRecoveryWritten(JobRepository.InFlightJobRef ref) {
        Long jobId = ref.getId();
        Long taskId = ref.getTaskId();
        Boolean recovered = this.perJob.execute(status -> {
            // R1-13：任务态写路径统一先锁 task，再写 job。
            if (taskService.tryLockForUpdate(taskId).isEmpty()) {
                log.warn("Stale job task missing, recovery skipped jobId={} taskId={}", jobId, taskId);
                return false;
            }
            // P1-6：这条 PENDING/RUNNING 可能属于别的实例仍在跑的 runner。
            // 活跃 → 跳过；读失败 → fail-closed 跳过（不用错误终态掩盖"无法确认"）。
            try {
                if (heartbeatRegistry.isRunnerAlive(jobId)) {
                    if (log.isDebugEnabled()) {
                        log.debug("Stale job recovery skipped, runner still active jobId={}", jobId);
                    }
                    return false;
                }
            } catch (JobHeartbeatRegistry.RunnerActiveStateUnavailableException e) {
                log.error("Stale job recovery skipped, runner state unavailable (fail-closed) jobId={}",
                        jobId, e);
                return false;
            }
            // 只迁移状态：候选分页读到的 progress/total 是扫描时刻的旧快照，不能随终态写回
            // （期间别的 runner 可能已推进进度）。计数/进度保持数据库当前值。
            boolean wrote = jobRepository.finishInterruptedIfInFlight(jobId, Job.JobStatus.FAILED,
                    LocalDateTime.now(), IN_FLIGHT) > 0;
            if (!wrote) {
                if (log.isDebugEnabled()) {
                    log.debug("Stale job recovery skipped, job already left in-flight state jobId={}", jobId);
                }
                return false;
            }
            // 实现细节补充：FAILED 作业下不得残留 RUNNING/PENDING 的作业条目（状态机不自相矛盾）；
            // 条件 UPDATE 只匹配在途条目，重复调用/多实例并发都是 0 行。
            int normalized = jobItemRepository.convergeOpenItems(jobId, "FAILED");
            // ② 联动回写任务状态（与作业失败联动、取消联动同一口径；CANCELLED 吸收态闸在 TaskService 内）
            taskService.applyJobOutcome(taskId, Job.JobStatus.FAILED);
            log.warn("Job interrupted by restart, marked FAILED and task marked FAILED jobId={} jobType={} itemsReset={} taskId={}",
                    jobId, ref.getJobType(), normalized, taskId);
            return true;
        });
        if (Boolean.TRUE.equals(recovered)) {
            heartbeatRegistry.revokeRunner(jobId);
            return true;
        }
        return false;
    }
}
