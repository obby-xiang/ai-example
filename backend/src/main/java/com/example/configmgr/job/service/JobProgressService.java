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
 * 已处理行数，{@code total} = 分母（见 {@link JobProgressTotals}：预估总行数与"已发现行数 + 未开工配置项数"
 * 取大，随配置发现而上调且恒不小于 {@code progress} —— 配置项逐个收尾时不会再闪现 100%）。
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
    private final JobHeartbeatRegistry heartbeatRegistry;

    public JobProgressService(JobRepository jobRepository,
                              JobItemRepository jobItemRepository,
                              PlatformTransactionManager transactionManager,
                              JobHeartbeatRegistry heartbeatRegistry) {
        this.jobRepository = jobRepository;
        this.jobItemRepository = jobItemRepository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.heartbeatRegistry = heartbeatRegistry;
    }

    /**
     * 作业开工：状态 RUNNING + startedAt（独立事务，快照立刻可见）。
     *
     * <p>心跳（R-96 批 B）：三个写方法入口各续期一次 {@link JobHeartbeatRegistry} 心跳
     * （<b>REQUIRES_NEW 事务外</b>，纯内存无 I/O），覆盖全部四个 Runner 的分片边界节拍，
     * 供 {@link JobWatchdog} 按「心跳 ×3 判死」收敛失联作业。
     */
    public void markRunning(Long jobId, int totalRows) {
        heartbeatRegistry.beat(jobId);
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
        heartbeatRegistry.beat(jobId);
        requiresNew.executeWithoutResult(status -> jobRepository.updateProgress(jobId, processed, total));
    }

    /**
     * 作业终态：状态 + 计数 + 行级进度（独立事务）。
     *
     * <p>终态守卫（R-96 批 B 收口修，红队 M2/m2）：仅当作业当前仍为 PENDING/RUNNING 才写入
     * （与 {@link #markRunning} 的守卫同姿态）。本批引入的看门狗是第二个并发 finish 调用方，
     * 判死 FAILED 与作业自然完成 COMPLETED 之间存在 TOCTOU 窗口 —— 守卫保证先到者胜、
     * 后到者不覆盖（被判死的慢作业也不再自我覆盖回 CANCELLED）；
     * 终态作业再被 finish 调用 = 静默忽略（debug 留痕）。
     *
     * <p>写入成功后清理 {@link JobHeartbeatRegistry} 条目（红队 m1）：自然完成的作业同样清，
     * 登记簿不随历史作业无界增长。
     *
     * <p>被守卫拦下的调用同样清理（终审微补丁 F1b，红队 m1 残余）：入口的无条件 beat 会把
     * 已终态作业的条目重新塞回登记簿，该路径若不清则条目永无人清理（终态作业不再有
     * 后续分片边界 beat，其唯一清理点是本方法）—— 作业已是终态，清理语义与写入成功路径一致。
     */
    public void finish(Long jobId, Job.JobStatus jobStatus, int errorCount, int warningCount,
                       int processed, int total) {
        heartbeatRegistry.beat(jobId);
        boolean wroteTerminal = Boolean.TRUE.equals(requiresNew.execute(status -> jobRepository.findById(jobId)
                .map(job -> {
                    if (job.getStatus() != Job.JobStatus.PENDING && job.getStatus() != Job.JobStatus.RUNNING) {
                        return false;
                    }
                    job.setStatus(jobStatus);
                    job.setErrorCount(errorCount);
                    job.setWarningCount(warningCount);
                    job.setProgress(processed);
                    job.setTotal(total);
                    job.setFinishedAt(LocalDateTime.now());
                    jobRepository.save(job);
                    return true;
                })
                .orElse(false)));
        if (!wroteTerminal) {
            log.debug("作业 #{} 已收终态，忽略本次 finish({}) 调用（先到者胜，后到者不覆盖）",
                    jobId, jobStatus);
            // 终审微补丁 F1b：入口 beat 已把条目写回登记簿，被拦路径必须同清（否则无清理点）
            heartbeatRegistry.clear(jobId);
            return;
        }
        heartbeatRegistry.clear(jobId);
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
