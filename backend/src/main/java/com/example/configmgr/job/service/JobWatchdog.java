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
 * 作业看门狗（R-96 批 B 新建，Q1/Q4/Q6 裁决；多实例 job 接管批：共享心跳 + 单飞判死）：
 * 运行期收敛"失联作业"与"超长作业"。
 *
 * <h2>两条判据（任一命中即判，互不依赖）</h2>
 * <ol>
 * <li><b>心跳失联判死</b>：RUNNING 作业的<b>共享心跳键</b>（{@code job:beat:<jobId>}，短 TTL =
 *     判死窗，由进程级 ticker 周期续期）不再存在 ⇒ 判僵尸。键存在即活，值里的时间戳
 *     <b>不参与比较</b>（跨实例时钟不可比）。共享心跳让"A 实例的 runner 死了"能被 B 实例在
 *     ≤ 判死窗（缺省 90s）内发现（K8s {@code periodSeconds × failureThreshold} 模式）；</li>
 * <li><b>绝对上限判超时</b>：作业已运行时长（RUNNING 用 {@code startedAt}，PENDING 用
 *     {@code createdAt}）超过 {@code max-execution-time}（缺省 30m，Hangfire
 *     {@code InvisibilityTimeout} 官方缺省）⇒ 判超时。</li>
 * </ol>
 *
 * <h2>fail-closed（多实例接管批，防误杀）</h2>
 * <ul>
 * <li>共享心跳<b>读失败</b>（{@link JobHeartbeatRegistry.SharedBeatState#UNAVAILABLE}）⇒
 *     本轮不判死，只记 {@code skippedUnavailableTotal} 与一次 WARN —— 绝不把"读不到"当成"死"；</li>
 * <li><b>本实例 runner 仍登记</b>（{@code isRunnerActive}）⇒ 不判死（可能只是长段无节拍，
 *     由绝对上限兜底）；</li>
 * <li><b>held</b>（长临界段豁免）⇒ <b>仅本实例</b>不判死。跨实例不再有 held 豁免：
 *     存活由进程级 ticker 续期，hold 不延长共享 TTL（复审 C1：持锁方死亡后不得等 65m）；</li>
 * <li>判活只看共享键是否存在（Redis TTL 权威）⇒ 快/慢时钟不影响判死（复审 I2）；</li>
 * <li>判死<b>单飞</b>：{@code SET job:deadjudge:<jobId> NX PX} 只有一个实例能落判定，
 *     其余实例记 {@code skippedNotLeaderTotal} 跳过 —— 重复扫描/多实例并发不重复写 issue；
 *     单飞键<b>不可用</b>时心跳判死 fail-closed，而<b>超时判据 fail-open</b>：只收敛<b>条件终态</b>
 *     （先到者胜），<b>不写 issue</b>（issue 必须单飞，记 {@code issueSuppressedTotal}）。</li>
 * </ul>
 *
 * <h2>判死动作（固定顺序，不强杀线程）</h2>
 * {@code ValidationIssue}（ERROR + {@code def_code} 哨兵 {@code "-"}，沿用
 * {@link ExportJobRunner#JOB_LEVEL_DEF_CODE} 口径）→ {@link JobTerminalWriter#complete}
 * （FAILED，看门狗线程无事务 ⇒ 走直接 apply 分支）→ {@link JobCancellationRegistry#cancel}
 * （Redis 权威取消标志 ⇒ 作业线程在<b>下一个分片边界</b>自行止步）→
 * {@link JobHeartbeatRegistry#revokeRunner}（清 runner-active + 共享心跳，删除闸门立即释放，
 * 不再白等 30m/65m TTL）。<b>不自动重排队</b>（Q6 裁决，对齐
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
    private final AtomicLong skippedUnavailableTotal = new AtomicLong();
    private final AtomicLong skippedNotLeaderTotal = new AtomicLong();
    private final AtomicLong issueSuppressedTotal = new AtomicLong();

    /**
     * 已判死/判超时的 jobId 集合（R-96 批 B 收口修，红队 m7）：判重键 —— 同一作业不二次判死。
     * 终态写入（{@link JobTerminalWriter}）失败被吞时，下一扫描也不得重复写 issue /
     * 重复累加计数；配合判死动作固定为 FAILED 终态（Q6 不自动重排队），已判作业无再判语义。
     */
    private final Set<Long> judgedJobIds = ConcurrentHashMap.newKeySet();

    /** 已记过"共享状态不可读 ⇒ fail-closed"WARN 的作业（避免每 10s 刷屏）。 */
    private final Set<Long> failClosedLogged = ConcurrentHashMap.newKeySet();

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
        log.info("作业看门狗已启动：scan-period={} heartbeat-interval={} dead-multiplier={} max-execution-time={}"
                        + " beat-ttl={} runner-active-store={}",
                scanPeriod(), heartbeatInterval(), deadMultiplier(), maxExecutionTime(),
                this.heartbeatRegistry.beatTtl(), this.heartbeatRegistry.isRedisMode() ? "redis" : "memory");
    }

    @PreDestroy
    void stop() {
        ScheduledExecutorService svc = this.scheduler;
        if (svc != null) {
            svc.shutdownNow();
        }
    }

    /** {@code /health} 观测面：tracked / judged / skipped（fail-closed、单飞落选）/ lastScanAt。 */
    public Map<String, Object> metrics() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tracked", this.trackedLastScan);
        body.put("judgedDeadTotal", this.judgedDeadTotal.get());
        body.put("judgedTimeoutTotal", this.judgedTimeoutTotal.get());
        body.put("skippedUnavailableTotal", this.skippedUnavailableTotal.get());
        body.put("skippedNotLeaderTotal", this.skippedNotLeaderTotal.get());
        body.put("issueSuppressedTotal", this.issueSuppressedTotal.get());
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
                boolean dead = job.getStatus() == Job.JobStatus.RUNNING && isHeartbeatDead(job, deadAfterMs);
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
     * 心跳失联判据：<b>共享心跳键不在</b>（Redis TTL 权威，值里的时间戳不参与比较）。
     *
     * <p>状态映射（与 {@link JobHeartbeatRegistry.SharedBeatState} 一一对应）：
     * <ul>
     * <li>{@code ALIVE}（键还在）⇒ 不判死；</li>
     * <li>{@code STALE}（键没了、runner-active 锚点还在；memory = 本地心跳过期）⇒ 判死；</li>
     * <li>{@code ABSENT}（既无键也无锚点：从未有过运行痕迹）⇒ 保守不判，交给绝对上限；</li>
     * <li>{@code UNAVAILABLE}（读失败）⇒ fail-closed，不判死并记降级计数。</li>
     * </ul>
     *
     * <p>另外两道 fail-closed 闸：本实例 runner 仍登记（不杀活线程）、本实例 held（长临界段豁免）。
     * 跨实例的 held 已不再豁免（复审 C1）：存活由进程级 ticker 续期，持有者一死键就过期。
     */
    private boolean isHeartbeatDead(Job job, long deadAfterMs) {
        Long jobId = job.getId();
        // held（慢临界段豁免，红队 M1）：长临界段期间心跳停摆是预期的，不判死；
        // 30min 绝对上限判据在 scan() 中独立生效，不受豁免影响
        if (this.heartbeatRegistry.isHeld(jobId)) {
            return false;
        }
        // runner 仍活（本实例登记中）：无节拍 ≠ 失联，绝不误杀；长跑由绝对上限收敛
        if (this.heartbeatRegistry.isRunnerActive(jobId)) {
            return false;
        }
        JobHeartbeatRegistry.SharedBeat beat = this.heartbeatRegistry.readSharedBeat(jobId, deadAfterMs);
        switch (beat.state()) {
            case UNAVAILABLE -> {
                noteFailClosed(jobId);
                return false;
            }
            case ALIVE -> {
                return false;
            }
            case STALE -> {
                return true;
            }
            case ABSENT -> {
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    private void noteFailClosed(Long jobId) {
        this.skippedUnavailableTotal.incrementAndGet();
        if (this.failClosedLogged.add(jobId)) {
            log.warn("作业 #{} 的共享心跳不可读，本轮判死 fail-closed（不误杀；Redis 恢复后自动继续）", jobId);
        }
    }

    /**
     * 判死/判超时落地：单飞 →（issue）→ 终态 FAILED → 取消标志（固定顺序，见类注释）。
     *
     * <p><b>issue 是单飞资源</b>（复审 I3）：只有拿到单飞租约（{@code ACQUIRED}）的实例才写 issue。
     * 单飞键不可用时，超时判据仍 fail-open 收敛<b>条件终态</b>（先到者胜），但<b>不写 issue</b> ——
     * 否则 Redis 抖动期间 N 个实例会各写一条重复 issue。心跳判死对不可用仍整体 fail-closed。
     */
    private void judge(Job job, boolean timeout) {
        JobHeartbeatRegistry.JudgeLease lease = this.heartbeatRegistry.tryAcquireDeadJudgement(job.getId());
        if (lease == JobHeartbeatRegistry.JudgeLease.HELD) {
            // 别的实例正在/已经判过：本轮不落任何动作（重复 issue / 重复终态都不增加）
            this.skippedNotLeaderTotal.incrementAndGet();
            log.debug("作业 #{} 判死单飞键已被持有，本实例跳过本轮判定", job.getId());
            return;
        }
        final boolean writeIssue = lease == JobHeartbeatRegistry.JudgeLease.ACQUIRED;
        if (lease == JobHeartbeatRegistry.JudgeLease.UNAVAILABLE) {
            if (!timeout) {
                // 心跳判死依赖共享态：拿不到单飞键（Redis 不可用）必须 fail-closed
                noteFailClosed(job.getId());
                return;
            }
            // 超时判据只依赖本实例时钟与 jobs 行，不依赖共享态：安全网继续生效，
            // 但 issue 必须单飞 —— 拿不到租约就不写（终态由条件 UPDATE 先到者胜）。
            this.issueSuppressedTotal.incrementAndGet();
            log.warn("作业 #{} 判超时但单飞键不可用（Redis）：仅收敛终态，跳过 issue 写入"
                    + "（避免多实例重复 issue）", job.getId());
        }
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
        if (writeIssue) {
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
        }
        this.terminalWriter.complete(job, Job.JobStatus.FAILED, 1, 0,
                job.getProgress(), job.getTotal(),
                Map.of("jobId", job.getId(), "jobType", job.getJobType(),
                        "status", Job.JobStatus.FAILED));
        try {
            this.cancellationRegistry.cancel(job.getId(), "job-watchdog");
        } catch (RuntimeException e) {
            // 取消标志写失败不阻断收尾：终态已落 FAILED，执行线程的迟到写由条件 UPDATE 丢弃
            log.warn("作业 #{} 写取消标志失败（执行线程可能继续到下一分片边界）：{}",
                    job.getId(), e.getMessage());
        }
        if (timeout) {
            // 超时不强杀线程：runner 可能仍活着并在长事务里迟到写，删除闸门留给
            // AsyncJobExecutor.finally 的 endRunner 释放（runner 仍活 ⇒ fail-closed 不误放行）。
            this.heartbeatRegistry.clear(job.getId());
        } else {
            // 心跳判死：runner 已失联，撤销登记并清共享键 ⇒ 删除闸门随 runner 清理立即释放，
            // 不再白等 runner-active 的 30m/65m TTL（本批硬要求）。
            this.heartbeatRegistry.revokeRunner(job.getId());
        }
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
