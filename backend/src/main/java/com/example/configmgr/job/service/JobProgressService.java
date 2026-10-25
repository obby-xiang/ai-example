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
import java.util.ArrayList;
import java.util.List;

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
 * 执行器不得在自己事务内写 jobs / job_items 行（否则会与本类争锁）：运行中的状态/进度写都走这里，
 * 且本类的作业行/条目行写一律用条件 UPDATE（在途 + 子项未终态）。作业行终态
 * （COMPLETED/FAILED/CANCELLED）也走这里，由执行器在数据面事务提交后调用，
 * 保证"快照说完成时数据已落库"。
 *
 * <p><b>不再宣称"唯一写入者"</b>：{@link JobService#cancel} 用条件 UPDATE 写作业终态，
 * {@link PrecheckJobRunner#writeFingerprintSummary} 定向写 {@code result_json}；
 * {@link StaleJobRecoveryRunner} <b>仍</b>按 {@code findAll → setStatus → save} 整行写
 * （{@code Job} 无 {@code @DynamicUpdate}），该风险归 R1-10，不在本批收口范围。
 */
@Slf4j
@Service
public class JobProgressService {

    /** 可写入终态/进度的在途状态；条件 UPDATE 的 {@code IN} 白名单。 */
    private static final List<Job.JobStatus> IN_FLIGHT =
            List.of(Job.JobStatus.PENDING, Job.JobStatus.RUNNING);

    /** 子项仍未收终态（允许继续写进度/终态）；已 COMPLETED/FAILED/CANCELLED 的条目一律拒绝迟到写。 */
    private static final List<String> ITEM_OPEN = List.of("PENDING", "RUNNING");

    /**
     * 失败补偿可降级的条目状态（I-4）：成功路径先写 COMPLETED、后续写盘/任务项更新失败时，
     * 同一轮 catch 允许 COMPLETED→FAILED。CANCELLED 不在此列，取消语义不可被补偿翻写。
     */
    private static final List<String> COMPENSABLE_ITEM = List.of("PENDING", "RUNNING", "COMPLETED");

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
        // R1-11 同族：开工也走条件 UPDATE —— 读-判-写会让取消在两步之间提交后被整行 save 翻回 RUNNING。
        requiresNew.executeWithoutResult(status ->
                jobRepository.markRunningIfPending(jobId, Job.JobStatus.RUNNING, Job.JobStatus.PENDING,
                        LocalDateTime.now(), totalRows));
    }

    /**
     * 分片边界：回写行级 processed/total（独立事务，只写这两列）。
     *
     * <p>R1-11：条件里带 {@code status IN (PENDING, RUNNING)} —— 终态之后到达的旧快照影响 0 行，
     * 不会把已完成的进度写回低值。
     */
    public void snapshot(Long jobId, int processed, int total) {
        heartbeatRegistry.beat(jobId);
        requiresNew.executeWithoutResult(status ->
                jobRepository.updateProgressIfInFlight(jobId, processed, total, IN_FLIGHT));
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
     *
     * <p><b>条目收敛（多实例 job 接管批 · kill 后终态一致）</b>：终态<b>实际落写且为 FAILED</b> 时，
     * 同一条 {@code REQUIRES_NEW} 事务里把该作业下仍未收终态的条目（{@code PENDING/RUNNING}）
     * 一并记为 FAILED。没有这一步时，被 SIGKILL 打断的执行器不会再有 {@code finishItem}，
     * 看门狗把作业判 FAILED 后条目会永久停在 {@code RUNNING}（三实例验收：job #5 FAILED 而
     * {@code job_items} 残留 {@code RUNNING, processed=1}）——作业详情与任务中心长期自相矛盾。
     *
     * <p>收敛范围只到 FAILED：{@code CANCELLED} 在 {@link JobService#cancel} 的事务内收敛
     * （取消路径的条目写归属取消方），{@code COMPLETED} 不臆断（见
     * {@link #convergeOpenItemsLocked}）。条件 UPDATE 只匹配在途条目 ⇒ 幂等，重复扫描/
     * 多实例并发都不会翻写已终态条目。
     */
    public boolean finish(Long jobId, Job.JobStatus jobStatus, int errorCount, int warningCount,
                       int processed, int total) {
        // 死锁不变量（R1-13/I-3）：本方法必须走内部的 REQUIRES_NEW 事务并先提交 job 行，
        // 让 job 行锁在 JobTerminalWriter 写 task 前释放。改成 REQUIRED 会形成
        // “持 job 锁 → 等 task 锁”，与 TaskService.delete 的 task → job 顺序成环。
        heartbeatRegistry.beat(jobId);
        final boolean wroteTerminal;
        try {
            // R1-11：终态判定下沉到数据库 —— 单条条件 UPDATE 的影响行数就是「谁胜出」。
            // 读-判-写在应用层跨进程不成立（两个写者都读到 RUNNING 时会双双写终态）。
            wroteTerminal = Boolean.TRUE.equals(requiresNew.execute(status -> {
                int rows = jobRepository.finishIfInFlight(jobId, jobStatus, errorCount, warningCount,
                        processed, total, LocalDateTime.now(), IN_FLIGHT);
                if (rows > 0) {
                    convergeOpenItemsLocked(jobId, jobStatus);
                }
                return rows > 0;
            }));
        } finally {
            // 入口 beat 已把条目写回登记簿：写入成功、被拦与异常路径都必须清（F1b）
            heartbeatRegistry.clear(jobId);
        }
        if (!wroteTerminal) {
            log.debug("作业 #{} 已收终态，忽略本次 finish({}) 调用（先到者胜，后到者不覆盖）",
                    jobId, jobStatus);
        }
        // R-152：返回「本次是否实际落写终态」，供 JobTerminalWriter 据此决定是否联动任务态——
        // 迟到终态写被守卫拦下时（作业已 CANCELLED/终态），返回 false，调用方不得再 applyJobOutcome
        return wroteTerminal;
    }

    /**
     * 把仍未收终态的条目收敛为 FAILED（调用方必须已在 {@code REQUIRES_NEW} 事务内，且作业终态刚刚落写）。
     *
     * <p><b>只处理 FAILED</b>，其余终态刻意不碰：
     * <ul>
     * <li><b>COMPLETED</b>：成功作业理论上没有在途条目，真出现了是执行器的缺陷，
     *     用 COMPLETED 盖过去只会让"少跑了一个配置项"更晚被发现；</li>
     * <li><b>CANCELLED</b>：取消语义下"条目记 CANCELLED"是<b>取消方/执行器</b>的写（
     *     {@code finishItem}/{@code markItemFinal} 的 CANCELLED 分支有专门用例守着：父作业已 CANCELLED
     *     时仍允许补记 CANCELLED）。在这里抢先收敛会把那次写变成 0 行，等于偷走取消路径的归属。</li>
     * </ul>
     * FAILED 没有这个归属问题：判死/判超时/执行器失败这三条路径下都不会再有 runner 来写条目
     * （SIGKILL 后更是只剩数据库里那一行），必须由终态写入方兜住。
     */
    private void convergeOpenItemsLocked(Long jobId, Job.JobStatus jobStatus) {
        if (jobStatus != Job.JobStatus.FAILED) {
            return;
        }
        int converged = jobItemRepository.convergeOpenItems(jobId, "FAILED");
        if (converged > 0) {
            log.warn("作业 #{} 终态 FAILED：收敛 {} 条在途条目为 FAILED（执行器已不再写条目）",
                    jobId, converged);
        }
    }

    // ── 作业条目（job_items）：同一独立事务口径，供详情抽屉逐配置项实时进度 ──────────

    /**
     * 配置项开工：<b>父作业仍在途才</b>新建 job_item（RUNNING），返回其 id；否则返回 {@code null}。
     *
     * <h2>为什么插入点必须持父作业行锁（评审 I-1）</h2>
     * 条目收敛只发生在"写作业终态"的那一刻（{@code finish} 的 FAILED、{@code JobService#cancel} 的
     * CANCELLED）。若 {@code startItem} 无条件插入，<b>收敛之后</b>的迟到开工会在终态作业下插一条
     * 永久 RUNNING 的条目：它的 {@code finishItem}/{@code updateItem} 都因父作业已终态恒为 0 行，
     * 再没有任何路径能收它。可达路径（都是毫秒级真实窗口）：
     * <ul>
     * <li>取消：{@code JobService#cancel} 先提交"状态 + 收敛"，之后才写共享取消标志；runner 在
     *     条目循环顶部才检查标志 ⇒ 两次检查之间会走到下一项的 startItem；</li>
     * <li>看门狗判死/判超时：同样先写终态、后写取消标志；</li>
     * <li>跨实例 + Redis 降级：取消标志写 Redis 失败时只剩发起实例本地生效，另一实例上的 runner
     *     读不到 ⇒ 会在 FAILED/CANCELLED 作业下继续按条目推进。</li>
     * </ul>
     *
     * <p><b>为什么是行锁而不是"先查后插"</b>：终态写走的是 {@code UPDATE jobs ... WHERE status IN
     * (PENDING,RUNNING)}（数据库行锁 + 语句级判据）。这里对同一行加 {@code SELECT ... FOR UPDATE}：
     * 谁先拿到行锁谁先成立 —— 终态先提交则本方法读到终态、返回 null；本方法先提交则终态写随后
     * 在同一事务里收敛这条新条目。两种次序都不会留下在途条目，且跨 JVM 成立（锁在数据库里）。
     * 纯应用层 check-then-insert 在 READ COMMITTED 下仍可插到终态之后，不能替代本锁。
     *
     * @return 新条目 id；{@code null} = 父作业已终态或已删除（调用方必须停止开工并走既有收尾）
     */
    public Long startItem(Long jobId, String defCode) {
        return requiresNew.execute(status -> {
            // 与 finish/cancel 的 jobs 条件 UPDATE 串行化（同一把行锁）；markItemFinal 的插入分支同法。
            Job parent = jobRepository.findByIdForUpdate(jobId).orElse(null);
            if (parent == null || !IN_FLIGHT.contains(parent.getStatus())) {
                log.info("作业 #{} 已离开在途态（{}），拒绝迟到开工的配置项 {}（终态作业不得新增在途条目）",
                        jobId, parent == null ? "已删除" : parent.getStatus(), defCode);
                return null;
            }
            JobItem item = new JobItem();
            item.setJobId(jobId);
            item.setDefCode(defCode);
            item.setStatus("RUNNING");
            return jobItemRepository.save(item).getId();
        });
    }

    /**
     * 配置项进度：分片边界回写 processed/total（I-3：父作业在途 + 条目未终态才写）。
     *
     * @return true = 实际落写；false = 父作业已终态或条目已收终态，本次迟到写被丢弃
     */
    public boolean updateItem(Long itemId, int processed, int total) {
        return Boolean.TRUE.equals(requiresNew.execute(status ->
                jobItemRepository.updateProgressIfJobInFlightAndItemOpen(
                        itemId, processed, total, ITEM_OPEN, IN_FLIGHT) > 0));
    }

    /**
     * 配置项终态：状态 + 进度（R1-11 / I-3：同一条 UPDATE 核父作业终态与条目自身终态）。
     *
     * <p>作业被取消/判死之后，执行器仍可能在下一个配置项边界写条目终态。
     * 条件 UPDATE 只允许「父作业在途」或「条目状态与父作业终态一致」两种形态，
     * 且条目本身必须仍未收终态：
     * 取消路径的 {@code finishItem(..., "CANCELLED", ...)} 仍能把条目收成 CANCELLED，
     * 而父作业已 CANCELLED 时迟到的 COMPLETED、或条目已终态后的任何迟到写影响 0 行。
     *
     * @return true = 实际落写；false = 父作业终态与条目状态冲突、条目已终态（或不存在），本次写被丢弃
     */
    public boolean finishItem(Long itemId, String itemStatus, int processed, int total) {
        if (itemStatus == null) {
            log.warn("作业条目 #{} 的终态写缺少目标状态，已丢弃", itemId);
            return false;
        }
        List<Job.JobStatus> allowed = allowedJobStatuses(itemStatus);
        boolean wrote = Boolean.TRUE.equals(requiresNew.execute(status ->
                jobItemRepository.finishIfJobStatusAllowed(
                        itemId, itemStatus, processed, total, ITEM_OPEN, allowed) > 0));
        if (!wrote) {
            log.debug("作业条目 #{} 的终态写被丢弃（父作业终态冲突、条目已终态或条目不存在）：{}",
                    itemId, itemStatus);
        }
        return wrote;
    }

    /** 允许的父作业状态：在途 + 与条目目标一致的那个终态（取消补偿/失败补记同态幂等）。 */
    private static List<Job.JobStatus> allowedJobStatuses(String itemStatus) {
        List<Job.JobStatus> allowed = new ArrayList<>(IN_FLIGHT);
        Job.JobStatus matchingTerminal = matchingTerminal(itemStatus);
        if (matchingTerminal != null) {
            allowed.add(matchingTerminal);
        }
        return allowed;
    }

    private static Job.JobStatus matchingTerminal(String itemStatus) {
        if (itemStatus == null) {
            return null;
        }
        return switch (itemStatus) {
            case "COMPLETED" -> Job.JobStatus.COMPLETED;
            case "FAILED" -> Job.JobStatus.FAILED;
            case "CANCELLED" -> Job.JobStatus.CANCELLED;
            default -> null;
        };
    }

    /**
     * 失败补偿（I-4，按条目 id）：仅允许父作业仍在途时把条目降级为 FAILED。
     *
     * <p>这是执行器 catch 的专用路径，不是通用终态写：允许条目状态
     * {@code PENDING/RUNNING/COMPLETED} → FAILED（成功路径先写 COMPLETED、随后写盘失败时
     * 需要回退），但 CANCELLED 条目与已终态父作业一律 0 行 —— 不放松取消保护。
     *
     * @return true = 实际降级；false = 父作业已终态或条目已 CANCELLED/FAILED
     */
    public boolean compensateItemFailure(Long itemId, int processed, int total) {
        boolean wrote = Boolean.TRUE.equals(requiresNew.execute(status ->
                jobItemRepository.compensateFailureIfInFlight(
                        itemId, processed, total, COMPENSABLE_ITEM, IN_FLIGHT) > 0));
        if (!wrote) {
            log.debug("作业条目 #{} 的失败补偿被丢弃（父作业已终态或条目已 CANCELLED/FAILED）", itemId);
        }
        return wrote;
    }

    /**
     * 失败补偿（I-4，按业务键）：发布硬错误路径只有 jobId + defCode。
     * 条目存在时走同一条条件降级；缺失时先锁父作业行，仅在父作业仍在途时插入 FAILED 条目。
     *
     * @return true = 实际降级/插入；false = 父作业已终态或条目已 CANCELLED/FAILED
     */
    public boolean compensateItemFailure(Long jobId, String defCode, int processed, int total) {
        return Boolean.TRUE.equals(requiresNew.execute(status -> {
            JobItem existing = jobItemRepository.findByJobIdAndDefCode(jobId, defCode).orElse(null);
            if (existing != null) {
                return jobItemRepository.compensateFailureIfInFlight(
                        existing.getId(), processed, total, COMPENSABLE_ITEM, IN_FLIGHT) > 0;
            }
            Job parent = jobRepository.findByIdForUpdate(jobId).orElse(null);
            if (parent == null || !IN_FLIGHT.contains(parent.getStatus())) {
                log.debug("作业条目失败补偿未插入（父作业不存在或已终态）：jobId={} defCode={}",
                        jobId, defCode);
                return false;
            }
            JobItem created = new JobItem();
            created.setJobId(jobId);
            created.setDefCode(defCode);
            created.setStatus("FAILED");
            created.setProcessed(processed);
            created.setTotal(total);
            jobItemRepository.save(created);
            return true;
        }));
    }

    /**
     * 记录一个"已失败/已取消的配置项"，供执行器在数据面回滚之后补记
     * （发布作业硬错误路径：job_items 已由本类提交，补记必须 update 而非 insert）。
     *
     * <p>I-3：存在条目时走与 {@link #finishItem} 相同的条件 UPDATE（父作业终态/条目终态冲突即 0 行）；
     * 不存在时先对父作业行加悲观写锁再检查状态，避免"检查在途 → 取消提交 → 插入终态条目"的窗口。
     *
     * @return true = 实际落写/插入；false = 父作业或条目终态不接受本次写
     */
    public boolean markItemFinal(Long jobId, String defCode, String itemStatus, int processed, int total) {
        if (itemStatus == null) {
            log.warn("作业条目补记缺少目标状态 jobId={} defCode={}，已丢弃", jobId, defCode);
            return false;
        }
        return Boolean.TRUE.equals(requiresNew.execute(status -> {
            JobItem existing = jobItemRepository.findByJobIdAndDefCode(jobId, defCode).orElse(null);
            if (existing != null) {
                boolean wrote = jobItemRepository.finishIfJobStatusAllowed(existing.getId(), itemStatus,
                        processed, total, ITEM_OPEN, allowedJobStatuses(itemStatus)) > 0;
                if (!wrote) {
                    log.debug("作业条目 #{} 的补记被丢弃（父作业/条目已终态）：jobId={} defCode={} status={}",
                            existing.getId(), jobId, defCode, itemStatus);
                }
                return wrote;
            }
            Job parent = jobRepository.findByIdForUpdate(jobId).orElse(null);
            if (parent == null || !allowedJobStatuses(itemStatus).contains(parent.getStatus())) {
                log.debug("作业条目补记未插入（父作业不存在或已终态）：jobId={} defCode={} status={}",
                        jobId, defCode, itemStatus);
                return false;
            }
            JobItem created = new JobItem();
            created.setJobId(jobId);
            created.setDefCode(defCode);
            created.setStatus(itemStatus);
            created.setProcessed(processed);
            created.setTotal(total);
            jobItemRepository.save(created);
            return true;
        }));
    }
}
