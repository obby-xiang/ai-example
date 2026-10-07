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
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class StaleJobRecoveryRunner implements ApplicationRunner {

    private final JobRepository jobRepository;
    private final JobItemRepository jobItemRepository;
    private final TaskService taskService;

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
