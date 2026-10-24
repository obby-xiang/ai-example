package com.example.configmgr.job.service;

import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.JobItem;
import com.example.configmgr.job.repo.JobItemRepository;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
 *
 * <h2>多实例（P1-6）</h2>
 * 共享 DB/Redis 下，PENDING/RUNNING 作业可能属于<b>其它实例仍在运行的 runner</b>。
 * 恢复前逐个读 {@link JobHeartbeatRegistry#isRunnerAlive(Long)}：活跃则跳过，绝不把它判 FAILED；
 * 读取失败（Redis 不可用）同样跳过（fail-closed），把“无法确认”留给删除闸门 409，
 * 而不是用一个错误的终态掩盖不确定性。本机旧 PID 的键由
 * {@link RunnerActiveKeyCleanupRunner} 在恢复<b>之前</b>清理（@Order 已调至恢复之前）。
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class StaleJobRecoveryRunner implements ApplicationRunner {

    private final JobRepository jobRepository;
    private final JobItemRepository jobItemRepository;
    private final TaskService taskService;
    private final JobHeartbeatRegistry heartbeatRegistry;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Job> stale = jobRepository.findAll().stream()
                .filter(j -> j.getStatus() == Job.JobStatus.RUNNING || j.getStatus() == Job.JobStatus.PENDING)
                .toList();
        if (stale.isEmpty()) {
            log.info("僵尸作业恢复：无需处理（PENDING/RUNNING 作业 0 个）");
            return;
        }
        for (Job job : stale) {
            // R1-13：任务态写路径统一先锁 task，再写 job；任务已被删除时跳过该作业，
            // 避免对已被删除的 job 行做 merge 而把它重新插回（历史孤儿作业也走这条跳过路径）。
            // 跳过后的兜底链：这些 job 保持 PENDING/RUNNING，由 JobWatchdog 的超时判据收敛为 FAILED；
            // job 行仍存在，validation_issues 的 job_id FK 不会触发，删除闸门也不会把它们误当成
            // “任务已删即可删”（删除只针对存在的 task）。
            if (taskService.tryLockForUpdate(job.getTaskId()).isEmpty()) {
                log.warn("僵尸作业 #{} 的任务 #{} 已不存在，跳过恢复（不重建作业行；交由看门狗按超时收敛）",
                        job.getId(), job.getTaskId());
                continue;
            }
            // P1-6：共享 DB/Redis 下，这条 PENDING/RUNNING 可能属于别的实例仍在跑的 runner。
            // 活跃 → 跳过；读失败 → fail-closed 跳过（不用错误终态掩盖“无法确认”）。
            try {
                if (heartbeatRegistry.isRunnerAlive(job.getId())) {
                    log.info("僵尸恢复跳过作业 #{}：runner 仍活跃（其它实例或本机登记未清）", job.getId());
                    continue;
                }
            } catch (JobHeartbeatRegistry.RunnerActiveStateUnavailableException e) {
                log.error("僵尸恢复跳过作业 #{}：runner 活跃状态无法确认（fail-closed，不改终态）：{}",
                        job.getId(), e.getMessage());
                continue;
            }
            job.setStatus(Job.JobStatus.FAILED);
            job.setFinishedAt(LocalDateTime.now());
            jobRepository.save(job);
            // 实现细节补充：FAILED 作业下不得残留 RUNNING/PENDING 的作业条目（状态机不自相矛盾）
            int normalized = 0;
            for (JobItem item : jobItemRepository.findByJobId(job.getId())) {
                if ("PENDING".equals(item.getStatus()) || "RUNNING".equals(item.getStatus())) {
                    item.setStatus("FAILED");
                    jobItemRepository.save(item);
                    normalized++;
                }
            }
            // ② 联动回写任务状态（与作业失败联动、取消联动同一口径）
            taskService.applyJobOutcome(job.getTaskId(), Job.JobStatus.FAILED);
            log.warn("作业 #{} [{}] 在服务重启时中断，已标记为 FAILED（条目归零 {} 个）并联动任务 #{} 为 FAILED",
                    job.getId(), job.getJobType(), normalized, job.getTaskId());
        }
        log.warn("僵尸作业恢复：本次处理 {} 个（PENDING/RUNNING → FAILED）", stale.size());
    }
}
