package com.example.configmgr.job.service;

import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.JobItem;
import com.example.configmgr.job.repo.JobItemRepository;
import com.example.configmgr.job.repo.JobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * 作业进度快照写入器（ADR-8 修正⑤ / Q16⑤ / W2）。
 *
 * <h2>为什么需要独立事务</h2>
 * 作业执行器（IMPORT/EXPORT/PRECHECK）整轮跑在自己的事务里，作业数据面的写入要到整批结束
 * 才提交 —— 于是作业运行期间 REST 快照里作业行仍是 {@code PENDING/0}：
 * 这正是 W2 的"事件领先于库"（SSE 报 2999 时快照仍 PENDING/0）与 Q16⑤ 的
 * "运行中列表进度恒 0、仅起止两态可见"。
 * <p>
 * 本类把<b>作业行与作业条目的状态/进度</b>（processed/total）写入
 * {@code REQUIRES_NEW} 独立事务：分片边界一写即提交，别的连接（列表/详情/SSE 权威通道）
 * 立刻读得到；数据面（暂存行、已发布行、校验问题、任务条目）仍留在执行器自己的事务里，
 * 原语义不变。
 *
 * <h2>行级口径（processed/total 的量纲）</h2>
 * 作业行的 {@code progress}/{@code total} 统一为<b>行级</b>口径：{@code progress} = 本作业
 * 已处理行数，{@code total} = 已知待处理行数（随配置项逐个发现而累加，单调不减）。
 * 配置项级进度仍由 {@code job_items.processed/total} 承载，两者不再混用同一对字段。
 * 既有前端 {@code latestJob.progress / latestJob.total} 的百分比算法因此天然成立且运行中非 0。
 *
 * <h2>与执行器事务的关系</h2>
 * 执行器不得在自己事务内写 jobs / job_items 行（否则会与本类争锁）：本类是这两张表
 * 唯一的写入者。作业行终态（COMPLETED/FAILED/CANCELLED）也走这里，由执行器在
 * 数据面事务提交后调用，保证"快照说完成时数据已落库"。
 */
@Slf4j
@Service
public class JobProgressService {

    private final JobRepository jobRepository;
    private final JobItemRepository jobItemRepository;
    private final TransactionTemplate requiresNew;

    public JobProgressService(JobRepository jobRepository,
                              JobItemRepository jobItemRepository,
                              PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.jobItemRepository = jobItemRepository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 作业开工：状态 RUNNING + startedAt（独立事务，快照立刻可见）。 */
    public void markRunning(Long jobId, int totalRows) {
        requiresNew.executeWithoutResult(status -> jobRepository.findById(jobId).ifPresent(job -> {
            if (job.getStatus() != Job.JobStatus.PENDING) {
                // 已被取消（或已是终态）：开工写入退让，避免把取消态改回运行态
                job.setTotal(totalRows);
                return;
            }
            job.setStatus(Job.JobStatus.RUNNING);
            job.setStartedAt(LocalDateTime.now());
            job.setTotal(totalRows);
            jobRepository.save(job);
        }));
    }

    /** 分片边界：回写行级 processed/total（独立事务，只写这两列）。 */
    public void snapshot(Long jobId, int processed, int total) {
        requiresNew.executeWithoutResult(status -> jobRepository.updateProgress(jobId, processed, total));
    }

    /** 作业终态：状态 + 计数 + 行级进度（独立事务）。 */
    public void finish(Long jobId, Job.JobStatus jobStatus, int errorCount, int warningCount,
                       int processed, int total) {
        requiresNew.executeWithoutResult(status -> jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(jobStatus);
            job.setErrorCount(errorCount);
            job.setWarningCount(warningCount);
            job.setProgress(processed);
            job.setTotal(total);
            job.setFinishedAt(LocalDateTime.now());
            jobRepository.save(job);
        }));
    }

    // ── 作业条目（job_items）：同一独立事务口径，供详情抽屉逐配置项实时进度 ──────────

    /** 配置项开工：新建 job_item（RUNNING），返回其 id。 */
    public Long startItem(Long jobId, String defCode) {
        return requiresNew.execute(status -> {
            JobItem item = new JobItem();
            item.setJobId(jobId);
            item.setDefCode(defCode);
            item.setStatus("RUNNING");
            return jobItemRepository.save(item).getId();
        });
    }

    /** 配置项进度：分片边界回写 processed/total。 */
    public void updateItem(Long itemId, int processed, int total) {
        requiresNew.executeWithoutResult(status -> jobItemRepository.findById(itemId).ifPresent(item -> {
            item.setProcessed(processed);
            item.setTotal(total);
            jobItemRepository.save(item);
        }));
    }

    /** 配置项终态：状态 + 进度。 */
    public void finishItem(Long itemId, String itemStatus, int processed, int total) {
        requiresNew.executeWithoutResult(status -> jobItemRepository.findById(itemId).ifPresent(item -> {
            item.setStatus(itemStatus);
            item.setProcessed(processed);
            item.setTotal(total);
            jobItemRepository.save(item);
        }));
    }

    /**
     * 记录一个"已失败/已取消的配置项"，供执行器在数据面回滚之后补记
     * （发布作业硬错误路径：job_items 已由本类提交，补记必须 update 而非 insert）。
     */
    public void markItemFinal(Long jobId, String defCode, String itemStatus, int processed, int total) {
        requiresNew.executeWithoutResult(status -> {
            JobItem item = jobItemRepository.findByJobIdAndDefCode(jobId, defCode).orElseGet(() -> {
                JobItem created = new JobItem();
                created.setJobId(jobId);
                created.setDefCode(defCode);
                return created;
            });
            item.setStatus(itemStatus);
            item.setProcessed(processed);
            item.setTotal(total);
            jobItemRepository.save(item);
        });
    }
}
