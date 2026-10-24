package com.example.configmgr.job.service;

import com.example.configmgr.config.AppProperties;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.ValidationIssue;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 作业看门狗（R-96 批 B 新建，Q1/Q4/Q6 裁决）：运行期收敛"失联作业"与"超长作业"。
 *
 * <h2>两条判据（任一命中即判，互不依赖）</h2>
 * <ol>
 * <li><b>心跳失联判死</b>：RUNNING 作业距最近一次 {@link JobHeartbeatRegistry} 心跳超过
 *     {@code heartbeat-interval × dead-multiplier}（缺省 30s × 3 = 90s）⇒ 判僵尸
 *     （K8s {@code periodSeconds × failureThreshold} 模式；从未续期过心跳的不判死，
 *     由判据 2 保守收敛）；</li>
 * <li><b>绝对上限判超时</b>：作业已运行时长（RUNNING 用 {@code startedAt}，PENDING 用
 *     {@code createdAt}）超过 {@code max-execution-time}（缺省 30m，Hangfire
 *     {@code InvisibilityTimeout} 官方缺省）⇒ 判超时。</li>
 * </ol>
 *
 * <h2>判死动作（固定顺序，不强杀线程）</h2>
 * {@code ValidationIssue}（ERROR + {@code def_code} 哨兵 {@code "-"}，沿用
 * {@link ExportJobRunner#JOB_LEVEL_DEF_CODE} 口径）→ {@link JobTerminalWriter#complete}
 * （FAILED，看门狗线程无事务 ⇒ 走直接 apply 分支）→ {@link JobCancellationRegistry#cancel}
 * （Redis 权威取消标志 ⇒ 作业线程在<b>下一个分片边界</b>自行止步）。这是唯一不"强杀线程"
 * 的可行组合（侦察报告 §5-R1）。<b>不自动重排队</b>（Q6 裁决，对齐
 * {@link StaleJobRecoveryRunner} 已定案口径：重跑由用户显式发起）。
 *
 * <h2>调度</h2>
 * 仓内无 {@code @EnableScheduling}/{@code @Scheduled}（侦察实测 0 命中），故自建单线程
 * <b>daemon</b> 调度器；<b>不复用</b> {@code AsyncConfig} 作业池（避免观察者与被观察者
 * 互相饿死，侦察报告 §5-R1）。扫描只取 PENDING/RUNNING（{@link JobRepository#findByStatusIn}
 * 派生查询，不 {@code findAll()} 全表，风险 R4）。
 */
@Slf4j
@Component
public class JobWatchdog {

    /** 在途状态（扫描范围）：与 {@link StaleJobRecoveryRunner} 的筛选口径一致。 */
    private static final List<Job.JobStatus> IN_FLIGHT =
            List.of(Job.JobStatus.PENDING, Job.JobStatus.RUNNING);

    /** 作业级 issue 的 def_code 哨兵（与 ExportJobRunner#JOB_LEVEL_DEF_CODE 同口径）。 */
    private static final String JOB_LEVEL_DEF_CODE = "-";

    private final JobRepository jobRepository;
    private final JobHeartbeatRegistry heartbeatRegistry;
    private final JobTerminalWriter terminalWriter;
    private final JobCancellationRegistry cancellationRegistry;
    private final ValidationIssueRepository issueRepository;
    private final AppProperties appProperties;
    private final Clock clock;

    private final AtomicLong judgedDeadTotal = new AtomicLong();
    private final AtomicLong judgedTimeoutTotal = new AtomicLong();

    /**
     * 已判死/判超时的 jobId 集合（R-96 批 B 收口修，红队 m7）：判重键 —— 同一作业不二次判死。
     * 终态写入（{@link JobTerminalWriter}）失败被吞时，下一扫描也不得重复写 issue /
     * 重复累加计数；配合判死动作固定为 FAILED 终态（Q6 不自动重排队），已判作业无再判语义。
     */
    private final Set<Long> judgedJobIds = ConcurrentHashMap.newKeySet();

    /** 最近一次扫描看到的在途作业数（{@code /health} 观测面）。 */
    private volatile int trackedLastScan = 0;

    /** 最近一次扫描完成时刻（{@code /health} 观测面）。 */
    private volatile Instant lastScanAt;

    private volatile ScheduledExecutorService scheduler;

    @Autowired
    public JobWatchdog(JobRepository jobRepository, JobHeartbeatRegistry heartbeatRegistry,
                       JobTerminalWriter terminalWriter, JobCancellationRegistry cancellationRegistry,
                       ValidationIssueRepository issueRepository, AppProperties appProperties) {
        this(jobRepository, heartbeatRegistry, terminalWriter, cancellationRegistry, issueRepository,
                appProperties, Clock.systemUTC());
    }

    JobWatchdog(JobRepository jobRepository, JobHeartbeatRegistry heartbeatRegistry,
                JobTerminalWriter terminalWriter, JobCancellationRegistry cancellationRegistry,
                ValidationIssueRepository issueRepository, AppProperties appProperties, Clock clock) {
        this.jobRepository = jobRepository;
        this.heartbeatRegistry = heartbeatRegistry;
        this.terminalWriter = terminalWriter;
        this.cancellationRegistry = cancellationRegistry;
        this.issueRepository = issueRepository;
        this.appProperties = appProperties;
        this.clock = clock;
    }

    @PostConstruct
    void start() {
        long scanPeriodMs = scanPeriod().toMillis();
        ScheduledExecutorService svc = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "job-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler = svc;
        svc.scheduleWithFixedDelay(this::scanSafely, scanPeriodMs, scanPeriodMs, TimeUnit.MILLISECONDS);
        log.info("作业看门狗已启动：scan-period={} heartbeat-interval={} dead-multiplier={} max-execution-time={}",
                scanPeriod(), heartbeatInterval(), deadMultiplier(), maxExecutionTime());
    }

    @PreDestroy
    void stop() {
        ScheduledExecutorService svc = this.scheduler;
        if (svc != null) {
            svc.shutdownNow();
        }
    }

    /** {@code /health} 观测面：tracked / judgedDeadTotal / judgedTimeoutTotal / lastScanAt。 */
    public Map<String, Object> metrics() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tracked", this.trackedLastScan);
        body.put("judgedDeadTotal", this.judgedDeadTotal.get());
        body.put("judgedTimeoutTotal", this.judgedTimeoutTotal.get());
        body.put("lastScanAt", this.lastScanAt == null ? null : this.lastScanAt.toString());
        return body;
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 兜住一切 Throwable（包级可见，供单测直接驱动；生产由调度周期调用）。 */
    void scanSafely() {
        try {
            scan();
        } catch (Throwable t) {
            // 看门狗绝不能让扫描异常逃出调度线程（否则后续周期静默停摆）；
            // Throwable 而非 Exception：Error（StackOverflowError / OOM 等）同样兜住并留根因，不吞（红队 m4）
            log.error("作业看门狗扫描失败: {}", t.getMessage(), t);
        }
    }

    /** 扫一遍在途作业并判定（包级可见，供单测直接驱动；生产由调度周期调用）。 */
    void scan() {
        List<Job> inFlight = this.jobRepository.findByStatusIn(IN_FLIGHT);
        this.trackedLastScan = inFlight.size();
        this.lastScanAt = Instant.now(this.clock);
        long nowMs = this.clock.millis();
        long deadAfterMs = heartbeatInterval().multipliedBy(deadMultiplier()).toMillis();
        long maxExecutionMs = maxExecutionTime().toMillis();
        for (Job job : inFlight) {
            if (this.judgedJobIds.contains(job.getId())) {
                continue; // 已判死/判超时（m7 幂等：issue 不重复写、计数不重复累加）
            }
            try {
                boolean timeout = ageMs(job, nowMs) > maxExecutionMs;
                boolean dead = job.getStatus() == Job.JobStatus.RUNNING && isHeartbeatStale(job, nowMs, deadAfterMs);
                if (timeout) {
                    judge(job, true);
                } else if (dead) {
                    judge(job, false);
                }
            } catch (Exception e) {
                // 单个作业判定失败不阻断本轮其余作业
                log.error("作业看门狗判定作业 #{} 失败: {}", job.getId(), e.getMessage(), e);
            }
        }
    }

    /** 已运行时长（ms）：RUNNING 用 startedAt，PENDING 用 createdAt（Q3 口径）。 */
    private long ageMs(Job job, long nowMs) {
        LocalDateTime baseline = job.getStatus() == Job.JobStatus.RUNNING && job.getStartedAt() != null
                ? job.getStartedAt()
                : job.getCreatedAt();
        if (baseline == null) {
            return 0;
        }
        long baselineMs = baseline.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return Math.max(0, nowMs - baselineMs);
    }

    /**
     * 心跳失联判据：最近一次心跳距今超过 {@code heartbeat-interval × dead-multiplier}。
     * 从未续期过心跳返回 {@code false}（保守 —— 无输入不判死，交给绝对上限收敛）。
     */
    private boolean isHeartbeatStale(Job job, long nowMs, long deadAfterMs) {
        Long lastBeat = this.heartbeatRegistry.lastBeatAtMs(job.getId());
        // held（慢临界段豁免，红队 M1）：长临界段期间心跳停摆是预期的，不判死；
        // 30min 绝对上限判据在 scan() 中独立生效，不受豁免影响
        return lastBeat != null && !this.heartbeatRegistry.isHeld(job.getId()) && nowMs - lastBeat > deadAfterMs;
    }

    /** 判死/判超时落地：issue → 终态 FAILED → 取消标志（固定顺序，见类注释）。 */
    private void judge(Job job, boolean timeout) {
        this.judgedJobIds.add(job.getId());
        if (timeout) {
            this.judgedTimeoutTotal.incrementAndGet();
        } else {
            this.judgedDeadTotal.incrementAndGet();
        }
        String reason = timeout
                ? "作业看门狗判超时：已运行时长超过上限 " + maxExecutionTime()
                        + "（app.job.max-execution-time），作业标记 FAILED；重跑请由用户显式发起"
                : "作业看门狗判死：作业心跳失联超过 " + heartbeatInterval().multipliedBy(deadMultiplier())
                        + "（app.job.heartbeat-interval × dead-multiplier），作业标记 FAILED；"
                        + "执行线程将在下一个分片边界自行止步，不自动重排队";
        try {
            ValidationIssue issue = new ValidationIssue();
            issue.setJobId(job.getId());
            issue.setDefCode(JOB_LEVEL_DEF_CODE);
            issue.setSeverity(ValidationIssue.Severity.ERROR);
            issue.setMessage(reason);
            this.issueRepository.save(issue);
        } catch (Exception e) {
            log.error("作业看门狗写 issue 失败 jobId={}: {}", job.getId(), e.getMessage(), e);
        }
        this.terminalWriter.complete(job, Job.JobStatus.FAILED, 1, 0,
                job.getProgress(), job.getTotal(),
                Map.of("jobId", job.getId(), "jobType", job.getJobType(),
                        "status", Job.JobStatus.FAILED));
        this.cancellationRegistry.cancel(job.getId(), "job-watchdog");
        // 只清心跳，不结束 runner：看门狗不强杀线程，runner 可能仍活着并在长事务里迟到写。
        // 删除闸门认 JobHeartbeatRegistry 的 runner 登记；endRunner 只由 AsyncJobExecutor.finally 调用，
        // 因此“判死/清心跳”不会把卡死作业误判成可删。
        this.heartbeatRegistry.clear(job.getId());
        log.warn("作业 #{} [{}] 被看门狗判{}：{}", job.getId(), job.getJobType(),
                timeout ? "超时" : "死", reason);
    }

    private Duration heartbeatInterval() {
        return this.appProperties.getJob().getHeartbeatInterval();
    }

    private Duration scanPeriod() {
        return this.appProperties.getJob().getWatchdog().getScanPeriod();
    }

    private int deadMultiplier() {
        return this.appProperties.getJob().getWatchdog().getDeadMultiplier();
    }

    private Duration maxExecutionTime() {
        return this.appProperties.getJob().getMaxExecutionTime();
    }
}
