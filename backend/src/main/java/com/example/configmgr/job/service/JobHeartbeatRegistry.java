package com.example.configmgr.job.service;

import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.config.AppProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 作业心跳 + runner 活跃登记簿（R-96 批 B 新建，Q1 裁决；R1-13/C1 扩多实例；
 * 多实例 job 接管批：共享心跳 + 判死单飞；复审 C1/I1-I3 修）。
 *
 * <h2>共享存活语义（唯一口径，两个实例看到同一件事）</h2>
 * <ul>
 * <li><b>共享心跳</b> {@code job:beat:<jobId>}：<b>短 TTL</b>（= 判死窗
 *     {@code heartbeat-interval × dead-multiplier}，缺省 90s），由<b>进程级 ticker</b>
 *     （{@code beat-ttl/3}，缺省 30s）周期续期，<b>与业务分片节拍解耦</b>。
 *     于是长导入/预检/长事务整段没有 {@code snapshot} 节拍也不会被对端误判；进程死亡 ⇒
 *     ticker 停 ⇒ 键在 ≤ TTL 内过期。</li>
 * <li><b>新鲜度只看"键在不在"</b>（Redis TTL 权威，Redis 服务端时钟）。值里的时间戳仅作诊断，
 *     <b>绝不跨实例比较</b> —— 快/慢时钟（时区、NTP 漂移、容器时钟）都不影响判死。</li>
 * <li>键不存在时再看 {@code job:runner-active:<jobId>} 这个<b>长 TTL 锚点</b>：
 *     锚点在 ⇒ {@link SharedBeatState#STALE}（runner 有 ticker、心跳已断，判死候选）；
 *     锚点也不在 ⇒ {@link SharedBeatState#ABSENT}（从未有过运行痕迹，保守不判，交给绝对上限）。</li>
 * <li><b>held 不延长共享 TTL</b>：{@link #hold} 只是本实例的判死豁免；共享存活仍由 ticker 决定。
 *     旧实现把 held 写成 65m 长租约 ⇒ 持有者死掉要等 65m 才收敛，本批改为"hold 有上界"：
 *     持有者一死，键在 ≤ 判死窗内过期，对端立刻按 STALE 接管。</li>
 * <li>本实例 runner 仍登记（{@link #isRunnerActive}）时看门狗不判死 —— 这是<b>明确写出的
 *     fail-closed 例外</b>（不杀活线程），不是"本实例恒活"：对端只按上面的共享语义判。</li>
 * </ul>
 *
 * <h2>三种本地状态</h2>
 * <ul>
 * <li><b>本地心跳</b>（{@code lastBeatAtMs}）：memory 模式的新鲜度判据（同一进程同一时钟，
 *     不存在跨实例漂移）；{@code clear} 只清它。</li>
 * <li><b>runner 活跃</b>：进程内集合 + 可选 Redis {@code job:runner-active:<jobId>}
 *     （长 TTL = 2×max-execution-time+5m，ticker/分片节拍续期）。删除闸门读共享态，
 *     A 实例 runner 未退出时 B 实例也 409；Redis 不可读时 fail-closed。</li>
 * <li><b>已撤销</b>（{@link #revokeRunner}）：判死后删除共享键并拒绝迟到的 beat。</li>
 * </ul>
 *
 * <h2>判死单飞与快速收敛</h2>
 * 判死动作由 {@code SET job:deadjudge:<jobId> <instance> NX PX <lease>} 单飞：只允许一个实例
 * 落 issue / 终态 / 取消标志，重复扫描与多实例并发扫描都不增加重复 issue（多写一次终态也由
 * {@code JobProgressService#finish} 的条件 UPDATE 兜住）。判死<b>成功后</b>
 * {@link #revokeRunner(Long)} 清 runner-active 与共享心跳 ⇒ 删除闸门立即释放，不再白等
 * runner-active 的 30m/65m TTL。<b>不自动重排队</b>：FAILED 即终点，重跑由用户显式发起。
 *
 * <h2>失败口径（fail-closed）</h2>
 * 判死输入读不到（Redis 读失败）时调用方不得判死 —— {@link #readSharedBeat} 返回
 * {@link SharedBeatState#UNAVAILABLE}，{@link JobWatchdog} 记降级计数并跳过本轮。
 * 单飞键不可用时：心跳判死不判；超时判据只收敛条件终态、<b>不写 issue</b>（issue 必须单飞，
 * 否则 N 个实例会重复写）。
 * {@code beginRunner} 仍是硬闸门：redis 模式写共享键失败返回 false，调用方不得启动 runner。
 *
 * <h2>模式</h2>
 * {@code app.job.runner-active-store=redis}（生产默认）或 {@code memory}（单测默认，避免 CI 依赖
 * Redis）。memory 模式下共享心跳/单飞退化为进程内语义（同进程时钟），单实例行为不变。
 *
 * <h2>时钟可注入</h2>
 * 生产用 {@link Clock#systemUTC()}；单测注入可控时钟，把「失联 TTL 到期」从真实睡眠中解放出来。
 */
@Slf4j
@Component
public class JobHeartbeatRegistry {

    /** Redis 共享 runner 活跃键（R1-13 / 多实例）：长 TTL 锚点 + 删除闸门。 */
    public static final String RUNNER_ACTIVE_PREFIX = "job:runner-active:";
    /** Redis 共享心跳键（多实例 job 接管）：短 TTL，只按"键是否存在"判活。 */
    public static final String BEAT_PREFIX = "job:beat:";
    /** Redis 判死单飞键（SET NX PX）：同一作业只允许一个实例判死。 */
    public static final String DEAD_JUDGE_PREFIX = "job:deadjudge:";
    /** TTL 余量：在 max-execution-time 的 2 倍之上再留 5m，覆盖判死/收尾延迟。 */
    public static final Duration RUNNER_ACTIVE_TTL_MARGIN = Duration.ofMinutes(5);
    /** 共享心跳缺省 TTL = 缺省判死窗（30s × 3）。 */
    public static final Duration DEFAULT_BEAT_TTL = Duration.ofSeconds(90);
    /**
     * runner 实例标识形状：{@code runner-<host>-pid<pid>-<uniq>}（启动清理按它解析主机与 pid）。
     */
    private static final Pattern INSTANCE_ID_PATTERN =
            Pattern.compile("^runner-(.+)-pid(\\d+)-([^-|]+)$");

    private static final String LOCAL_HOST = hostName();
    private static final String INSTANCE_ID = "runner-" + LOCAL_HOST + "-pid"
            + ProcessHandle.current().pid() + "-" + UUID.randomUUID().toString().substring(0, 6);

    private final ConcurrentMap<Long, Long> lastBeatAtMs = new ConcurrentHashMap<>();

    /** 处于「心跳判死豁免」中的作业（R-96 批 B 收口修，红队 M1）：hold 期间<b>本实例</b>看门狗的
     *  心跳失联判据对该作业恒为 false；30min 绝对上限判据不受影响。
     *  <b>不写入共享态、不延长共享 TTL</b>（复审 C1：跨实例豁免必须有上界；进程死亡后
     *  共享心跳在 ≤ 判死窗内过期，对端按 STALE 接管）。 */
    private final Set<Long> heldJobIds = ConcurrentHashMap.newKeySet();

    /**
     * runner 生命周期（R1-13 / C1）：在作业创建事务内、commit 前登记，事务回滚时注销；
     * runner 真正退出时由 {@code AsyncJobExecutor} 的 finally 注销。与心跳分开：看门狗判死会清心跳，但 runner 线程可能仍活着（本类不结束 runner），
     * 删除闸门只认本集合，避免“心跳过期/看门狗清心跳后被当成可删”。
     *
     * <p>本地集合只负责本实例快速判断；跨实例由 Redis {@code job:runner-active:<jobId>} 共享。
     * 进程重启后本地集合为空，旧 Redis 键由启动清理/ TTL 收敛。
     */
    private final Set<Long> activeRunners = ConcurrentHashMap.newKeySet();

    /**
     * 已被看门狗判死并撤销 runner 登记的作业：删除闸门必须立即放开（{@link #revokeRunner}），
     * 且判死后迟到的 {@code beat} <b>不得</b>把共享键写回来（否则删除又被挡到 TTL）。
     * {@code beginRunner}/{@code endRunner} 负责清理，集合不随历史作业无界增长。
     */
    private final Set<Long> revokedJobIds = ConcurrentHashMap.newKeySet();

    /** memory 模式的判死单飞（作业 → 租约到期时刻）；redis 模式走 {@code SET NX PX}。 */
    private final ConcurrentMap<Long, Long> localJudgements = new ConcurrentHashMap<>();

    private final Clock clock;
    private final StringRedisTemplate redis;
    private final boolean redisMode;
    private final Duration runnerActiveTtl;
    private final Duration beatTtl;

    /** 进程级共享心跳续期器（懒启动；只在 redis 模式且本实例有在跑 runner 时存在）。 */
    private volatile ScheduledExecutorService ticker;

    public JobHeartbeatRegistry() {
        this(Clock.systemUTC(), null, false, runnerActiveTtlFor(Duration.ofMinutes(30)), DEFAULT_BEAT_TTL);
    }

    /** Spring 生产构造：默认 redis；测试 classpath 的 application.properties 显式设 memory。 */
    @Autowired
    public JobHeartbeatRegistry(StringRedisTemplate redis,
                                @Value("${app.job.runner-active-store:redis}") String storeMode,
                                AppProperties appProperties) {
        this(Clock.systemUTC(), redis, !"memory".equalsIgnoreCase(storeMode),
                runnerActiveTtlFor(appProperties.getJob().getMaxExecutionTime()),
                beatTtlFor(appProperties));
    }

    JobHeartbeatRegistry(Clock clock) {
        this(clock, null, false, runnerActiveTtlFor(Duration.ofMinutes(30)), DEFAULT_BEAT_TTL);
    }

    /** 多实例测试/手工装配用（默认 30m 上限派生的 runner-active TTL、缺省共享心跳 TTL）。 */
    public JobHeartbeatRegistry(Clock clock, StringRedisTemplate redis, boolean redisMode) {
        this(clock, redis, redisMode, runnerActiveTtlFor(Duration.ofMinutes(30)), DEFAULT_BEAT_TTL);
    }

    /** 可注入 max-execution-time 派生的 TTL（配置边界测试）。 */
    public JobHeartbeatRegistry(Clock clock, StringRedisTemplate redis, boolean redisMode,
                                Duration runnerActiveTtl) {
        this(clock, redis, redisMode, runnerActiveTtl, DEFAULT_BEAT_TTL);
    }

    public JobHeartbeatRegistry(Clock clock, StringRedisTemplate redis, boolean redisMode,
                                Duration runnerActiveTtl, Duration beatTtl) {
        this.clock = clock;
        this.redis = redis;
        this.redisMode = redisMode;
        this.runnerActiveTtl = runnerActiveTtl;
        this.beatTtl = beatTtl;
    }

    /**
     * TTL 必须严格大于 max-execution-time：合法作业可能在绝对上限内一直运行且心跳短暂停摆；
     * 取 2× + 5m 余量。配置为 2h 时 TTL = 4h5m，不会被合法作业提前跑过期。
     */
    public static Duration runnerActiveTtlFor(Duration maxExecutionTime) {
        if (maxExecutionTime == null || maxExecutionTime.isZero() || maxExecutionTime.isNegative()) {
            throw new IllegalStateException("app.job.max-execution-time 必须 > 0，无法派生 runner-active TTL");
        }
        Duration ttl = maxExecutionTime.multipliedBy(2).plus(RUNNER_ACTIVE_TTL_MARGIN);
        if (ttl.compareTo(maxExecutionTime) <= 0) {
            throw new IllegalStateException("runner-active TTL(" + ttl
                    + ") 必须 > max-execution-time(" + maxExecutionTime + ")");
        }
        return ttl;
    }

    /**
     * 共享心跳 TTL = 判死窗（{@code heartbeat-interval × dead-multiplier}）。
     *
     * <p>判活口径是"键是否存在"（Redis TTL 权威），不再读值里的时间戳：
     * TTL 就是失联检测上界；ticker 以 TTL/3 续期，可容忍两次续期抖动。
     * 旧实现取 2× 并把"过期"与"从未有心跳"混为一谈（还依赖跨实例时间戳比较），已废弃。
     */
    public static Duration beatTtlFor(AppProperties appProperties) {
        Duration interval = appProperties.getJob().getHeartbeatInterval();
        int multiplier = appProperties.getJob().getWatchdog().getDeadMultiplier();
        if (interval == null || interval.isZero() || interval.isNegative() || multiplier <= 0) {
            return DEFAULT_BEAT_TTL;
        }
        try {
            return interval.multipliedBy(multiplier);
        } catch (ArithmeticException e) {
            return DEFAULT_BEAT_TTL;
        }
    }

    public Duration runnerActiveTtl() {
        return runnerActiveTtl;
    }

    /** 共享心跳 TTL（短 TTL；诊断/断言用）。 */
    public Duration beatTtl() {
        return beatTtl;
    }

    /** 判死单飞租约（{@code SET NX PX} 的 PX）。持有者崩溃后别的实例可在租约到期后接管。 */
    public Duration deadJudgeLease() {
        return beatTtl;
    }

    /** 分片边界续期一次心跳（幂等覆盖，只保留最新一次），并续期共享 runner 活跃键 + 共享心跳。 */
    public void beat(Long jobId) {
        if (revokedJobIds.contains(jobId)) {
            // 判死后迟到的分片边界节拍：runner 已被判死，重新写回共享键会把删除闸门再挡一个 TTL。
            log.debug("作业 #{} 已判死并撤销 runner 登记，忽略迟到的 beat", jobId);
            return;
        }
        this.lastBeatAtMs.put(jobId, this.clock.millis());
        renewRunnerActive(jobId);
        writeSharedBeat(jobId);
    }

    /** 最近一次本地心跳的纪元毫秒；从未续期过 = {@code null}。 */
    public Long lastBeatAtMs(Long jobId) {
        return this.lastBeatAtMs.get(jobId);
    }

    /**
     * 只清进程内心跳（finish/超时判死用）。<b>不删 Redis runner 活跃键</b>：
     * 看门狗清心跳时 runner 线程可能仍活着，删键会误放行。
     */
    public void clear(Long jobId) {
        this.lastBeatAtMs.remove(jobId);
        // 终态/超时判死之后该作业不会再有判定，单飞租约无需保留（避免进程内无界增长）。
        this.localJudgements.remove(jobId);
    }

    /**
     * runner 即将开始（作业创建事务内、commit 前）：本地 + Redis 同时登记，写首帧共享心跳，
     * 并启动进程级 ticker（长作业无分片节拍也不会被对端误判）。
     *
     * @return redis 模式下共享键写入成功才 true；false 表示不得启动 runner（调用方收敛 FAILED）。
     *         memory 模式恒 true。
     */
    public boolean beginRunner(Long jobId) {
        this.revokedJobIds.remove(jobId);
        if (!redisMode) {
            this.activeRunners.add(jobId);
            this.lastBeatAtMs.put(jobId, this.clock.millis());
            return true;
        }
        if (redis == null) {
            log.error("runner 活跃键登记失败（Redis 模板未装配），作业不得执行：jobId={}", jobId);
            return false;
        }
        long now = this.clock.millis();
        try {
            redis.opsForValue().set(key(jobId), anchorValue(), runnerActiveTtl);
            redis.opsForValue().set(beatKey(jobId), beatValue(now), beatTtl);
        } catch (RuntimeException e) {
            // 不能 WARN 后继续跑：B 实例看不到共享键会在 runner 活着时放行删除。
            log.error("runner 活跃键登记失败（共享键可能从未写入），作业不得执行：jobId={}", jobId, e);
            deleteSafely(key(jobId));
            deleteSafely(beatKey(jobId));
            return false;
        }
        this.lastBeatAtMs.put(jobId, now);
        this.activeRunners.add(jobId);
        ensureTicker();
        return true;
    }

    /** runner 已退出（{@code AsyncJobExecutor} 的 finally，含异常/取消路径）：本地 + Redis 同时注销。 */
    public void endRunner(Long jobId) {
        this.activeRunners.remove(jobId);
        this.revokedJobIds.remove(jobId);
        clearSharedRunnerState(jobId);
        stopTickerIfIdle();
    }

    /**
     * 看门狗判死成功后的撤销：本地 runner/心跳/held 全清，删除共享 runner-active 与共享心跳，
     * 并记住"已撤销"以拒绝迟到的 beat。
     *
     * <p>这样删除闸门<b>随判死立即释放</b>，不再等 runner-active 的 30m/65m TTL；
     * 被判死 runner 的迟到写由各写路径的"在途/终态条件 UPDATE"丢弃（{@link JobProgressService}）。
     * 只用于心跳判死；绝对上限判超时不清 —— 超时作业的 runner 可能仍在正常写，闸门留给
     * {@code AsyncJobExecutor.finally} 释放。
     */
    public void revokeRunner(Long jobId) {
        if (!this.activeRunners.remove(jobId) && !this.revokedJobIds.contains(jobId)) {
            log.debug("作业 #{} 本地无 runner 登记，仍按判死撤销共享 runner 状态", jobId);
        }
        this.lastBeatAtMs.remove(jobId);
        this.heldJobIds.remove(jobId);
        this.localJudgements.remove(jobId);
        this.revokedJobIds.add(jobId);
        clearSharedRunnerState(jobId);
        stopTickerIfIdle();
    }

    /** 当前是否有登记中的 runner（不含“从未启动/已退出”的作业；本地口径）。 */
    public boolean isRunnerActive(Long jobId) {
        return this.activeRunners.contains(jobId);
    }

    public boolean isRedisMode() {
        return this.redisMode;
    }

    /**
     * 删除闸门口径：本地 runner/心跳，或 Redis 共享活跃键任一存在即视为活着。
     *
     * <p>Redis 读取失败抛 {@link RunnerActiveStateUnavailableException}，由调用方 fail-closed（409），
     * 绝不把“查不到”当成“可删”。已被判死撤销的作业恒返回 false —— 那是判死方的明确结论，
     * 不是"查不到"。
     */
    public boolean isRunnerAlive(Long jobId) {
        if (this.revokedJobIds.contains(jobId)) {
            return false;
        }
        if (isRunnerActive(jobId) || this.lastBeatAtMs.containsKey(jobId)) {
            return true;
        }
        if (!redisMode) {
            return false;
        }
        if (redis == null) {
            throw new RunnerActiveStateUnavailableException(jobId, "Redis 模板未装配");
        }
        try {
            Boolean present = redis.hasKey(key(jobId));
            if (present == null) {
                throw new RunnerActiveStateUnavailableException(jobId, "Redis hasKey 返回 null");
            }
            return present;
        } catch (RunnerActiveStateUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RunnerActiveStateUnavailableException(jobId, e.getMessage());
        }
    }

    // ── 共享心跳续期（进程级 ticker）─────────────────────────────────────────

    /**
     * 续期本实例所有在跑 runner 的共享键（ticker 周期调用）。
     *
     * <p><b>包内可见</b>：单测用它把"进程还活着"变成确定性事件（真实 ticker 按真实时间跑，
     * 与可控时钟的 TTL 用例无法对齐）。ticker 本身只做 try/catch 后调本方法。
     */
    void tickOnce() {
        for (Long jobId : this.activeRunners) {
            if (this.revokedJobIds.contains(jobId)) {
                continue;
            }
            renewRunnerActive(jobId);
            writeSharedBeat(jobId);
        }
    }

    /** ticker 是否在跑（诊断/断言用）。 */
    boolean isTickerRunning() {
        return this.ticker != null;
    }

    private void ensureTicker() {
        if (!redisMode || this.ticker != null) {
            return;
        }
        synchronized (this) {
            if (this.ticker != null) {
                return;
            }
            long periodMs = Math.max(1_000L, this.beatTtl.toMillis() / 3);
            ScheduledExecutorService svc = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(() -> {
                    // 全局 ticker 不承载任何调用者身份：线程创建时先清掉从创建者继承来的 TTL 绑定，
                    // 否则这条守护线程会把"最近启动 runner 的调用者"身份一直带在身上。
                    try (RunIdentityScope.Scope ignored = RunIdentityScope.denied()) {
                        r.run();
                    }
                }, "job-heartbeat-ticker");
                thread.setDaemon(true);
                return thread;
            });
            svc.scheduleWithFixedDelay(this::tickSafely, periodMs, periodMs, TimeUnit.MILLISECONDS);
            this.ticker = svc;
            log.debug("共享心跳 ticker 已启动：period={}ms beat-ttl={}", periodMs, this.beatTtl);
        }
    }

    private void tickSafely() {
        // 心跳 ticker 是全局的，没有"单作业记录身份"：每拍显式 denied，清掉继承/上一拍残留的身份，
        // 避免这条进程级线程长期持有最近启动者的租户/用户身份。
        try (RunIdentityScope.Scope ignored = RunIdentityScope.denied()) {
            tickOnce();
        } catch (Throwable t) {
            // 单个周期失败不终止 ticker（下个周期重试）；共享键失联由 TTL 兜底
            log.warn("共享心跳续期失败（下个周期重试）：{}", t.getMessage());
        }
    }

    private void stopTickerIfIdle() {
        ScheduledExecutorService svc;
        synchronized (this) {
            if (!this.activeRunners.isEmpty()) {
                return;
            }
            svc = this.ticker;
            this.ticker = null;
        }
        if (svc != null) {
            svc.shutdownNow();
        }
    }

    /** 应用关闭：停掉 ticker（守护线程，不拖住退出）。 */
    @PreDestroy
    void stopTicker() {
        ScheduledExecutorService svc;
        synchronized (this) {
            svc = this.ticker;
            this.ticker = null;
        }
        if (svc != null) {
            svc.shutdownNow();
        }
    }

    // ── 共享心跳读取（跨实例判死输入）──────────────────────────────────────────

    /**
     * 读该作业的共享/本地心跳状态（<b>不抛异常</b>：判死侧据此 fail-closed）。
     *
     * <p>redis 模式：<b>只看键是否存在</b>（Redis TTL 权威）——
     * 心跳键在 ⇒ {@link SharedBeatState#ALIVE}；心跳键不在但 runner-active
     * 锚点在 ⇒ {@link SharedBeatState#STALE}（runner 有 ticker，beat 缺失就是真失联）；
     * 两者都不在 ⇒ {@link SharedBeatState#ABSENT}；读失败 ⇒ {@link SharedBeatState#UNAVAILABLE}。
     * 值里的时间戳<b>不参与判定</b>（快/慢时钟不会造成误杀或漏杀）。
     *
     * <p>memory 模式：本地心跳（同进程同一时钟），新鲜 ⇒ ALIVE、有过但过期 ⇒ STALE、
     * 从未有过 ⇒ ABSENT。
     *
     * @param deadWindowMs 仅 memory 模式的本地新鲜度窗口；redis 模式由键 TTL 决定，忽略本参数
     */
    public SharedBeat readSharedBeat(Long jobId, long deadWindowMs) {
        if (!redisMode) {
            return localBeat(jobId, this.clock.millis(), deadWindowMs);
        }
        if (redis == null) {
            return SharedBeat.unavailable();
        }
        try {
            String beat = redis.opsForValue().get(beatKey(jobId));
            if (beat != null) {
                return new SharedBeat(SharedBeatState.ALIVE, instanceOf(beat));
            }
            String anchor = redis.opsForValue().get(key(jobId));
            if (anchor == null) {
                return SharedBeat.absent();
            }
            // 锚点在、心跳键不在：runner 的 ticker 没续期 ⇒ 真失联，判死候选。
            return new SharedBeat(SharedBeatState.STALE, instanceOf(anchor));
        } catch (RuntimeException e) {
            log.warn("共享心跳读取失败（判死 fail-closed，本轮不判死）jobId={}：{}", jobId, e.getMessage());
            return SharedBeat.unavailable();
        }
    }

    // ── 判死单飞 ─────────────────────────────────────────────────────────────

    /**
     * 判死单飞：{@code SET job:deadjudge:<jobId> <instance> NX PX <beatTtl>}。
     *
     * <p>三态刻意分开：{@link JudgeLease#HELD}（别的实例持有 ⇒ 跳过，防重复 issue）/
     * {@link JudgeLease#ACQUIRED} / {@link JudgeLease#UNAVAILABLE}（Redis 写不进去）。
     * 心跳判死对 {@code UNAVAILABLE} 必须 fail-closed（不判）；超时判据只收敛条件终态，
     * <b>issue 写仍要求 ACQUIRED</b>（{@link JobWatchdog} 据此保证 N 实例不重复写 issue）。
     *
     * <p>单飞键<b>不在判死后删除</b>：那里正是"重复扫描/多实例并发不得重复写 issue"的判重键，
     * 由 PX 租约自然过期。
     */
    public JudgeLease tryAcquireDeadJudgement(Long jobId) {
        if (!redisMode) {
            long now = this.clock.millis();
            Long deadline = this.localJudgements.get(jobId);
            if (deadline != null && deadline > now) {
                return JudgeLease.HELD;
            }
            this.localJudgements.put(jobId, now + deadJudgeLease().toMillis());
            return JudgeLease.ACQUIRED;
        }
        if (redis == null) {
            log.warn("判死单飞键获取失败（Redis 模板未装配）jobId={}", jobId);
            return JudgeLease.UNAVAILABLE;
        }
        try {
            Boolean acquired = redis.opsForValue()
                    .setIfAbsent(deadJudgeKey(jobId), INSTANCE_ID, deadJudgeLease());
            if (acquired == null) {
                log.warn("判死单飞键获取失败（SET NX 返回值 null，按不可用处理）jobId={}", jobId);
                return JudgeLease.UNAVAILABLE;
            }
            return acquired ? JudgeLease.ACQUIRED : JudgeLease.HELD;
        } catch (RuntimeException e) {
            log.warn("判死单飞键获取失败（Redis 不可用）jobId={}：{}", jobId, e.getMessage());
            return JudgeLease.UNAVAILABLE;
        }
    }

    // ── hold（本实例慢临界段豁免）─────────────────────────────────────────────

    /**
     * 慢临界段心跳判死豁免（R-96 批 B 收口修，红队 M1/C2）：hold 期间<b>本实例</b>
     * {@link JobWatchdog} 的心跳失联判据对该作业恒为 {@code false}（单配置项大 sheet 的
     * Excel 写 / 发布长事务等无界区段，作业线程无法续期心跳，期间心跳停摆 ≠ 失联）。
     * 30min 绝对上限判据不受影响，PENDING 语义不变。消费点必须用 try/finally 与
     * {@link #release} 成对包裹长临界段。
     *
     * <p><b>跨实例不再靠 held 豁免</b>（复审 C1）：hold 只续一次共享心跳（短 TTL），
     * 长临界段期间的存活由进程级 ticker 负责 —— ticker 与业务线程无关，临界段再长也会续期；
     * 进程一死，共享键在 ≤ 判死窗内过期，对端按 STALE 接管。旧实现把 held 写成 65m 长租约，
     * 持有者死后对端要等 65m，已被本口径取代。
     */
    public void hold(Long jobId) {
        this.heldJobIds.add(jobId);
        renewRunnerActive(jobId);
        writeSharedBeat(jobId);
    }

    /** 解除心跳判死豁免（<b>幂等</b>）：未 hold 的作业调用无副作用。 */
    public void release(Long jobId) {
        this.heldJobIds.remove(jobId);
        writeSharedBeat(jobId);
    }

    /** 当前是否处于心跳判死豁免中（本实例看门狗判据消费）。 */
    public boolean isHeld(Long jobId) {
        return this.heldJobIds.contains(jobId);
    }

    // ── 兼容既有启动清理 ──────────────────────────────────────────────────────

    /**
     * 启动清理（同一主机）：旧进程已死、但 Redis 键尚未过 TTL 时，删除该键。
     * 其他主机/无法解析的键保留到 TTL，避免误删仍在运行的实例。
     */
    public void cleanupDeadLocalRunner(Long jobId) {
        if (!redisMode || redis == null) {
            return;
        }
        try {
            String value = redis.opsForValue().get(key(jobId));
            if (value == null) {
                return;
            }
            Matcher matcher = INSTANCE_ID_PATTERN.matcher(value);
            if (!matcher.matches()) {
                return;
            }
            if (!LOCAL_HOST.equals(matcher.group(1))) {
                return;
            }
            long pid = Long.parseLong(matcher.group(2));
            if (ProcessHandle.of(pid).isPresent()) {
                return;
            }
            redis.delete(key(jobId));
            log.info("清理同一主机旧进程的 runner 活跃键：jobId={} instance={}", jobId, value);
        } catch (RuntimeException e) {
            log.warn("清理 runner 活跃键失败（保留到 TTL）jobId={}：{}", jobId, e.getMessage());
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private String key(Long jobId) {
        return RUNNER_ACTIVE_PREFIX + jobId;
    }

    private String beatKey(Long jobId) {
        return BEAT_PREFIX + jobId;
    }

    private String deadJudgeKey(Long jobId) {
        return DEAD_JUDGE_PREFIX + jobId;
    }

    /** 值 = {@code 实例标识|写入时刻}（时刻只作诊断，<b>不参与跨实例判定</b>）。 */
    private String beatValue(long nowMs) {
        return INSTANCE_ID + "|" + nowMs;
    }

    private static String instanceOf(String raw) {
        int sep = raw.indexOf('|');
        return sep <= 0 ? raw : raw.substring(0, sep);
    }

    /**
     * 普通业务路径：Redis 失败只记日志，不影响创建/执行。
     *
     * <p>只在本地 runner 仍登记时续期：避免 runner 已退出后，迟到的 {@code finish/beat}
     * 又写回一个无人持有的键，把删除永久挡到 TTL。
     */
    private void renewRunnerActive(Long jobId) {
        if (!redisMode || redis == null || !activeRunners.contains(jobId)) {
            return;
        }
        try {
            redis.opsForValue().set(key(jobId), anchorValue(), runnerActiveTtl);
        } catch (RuntimeException e) {
            log.warn("runner 活跃键续期失败（删除侧将 fail-closed）jobId={}：{}", jobId, e.getMessage());
        }
    }

    /** anchor 值 = 实例标识（心跳值另带 {@code |<写入时刻>}，见 {@link #beatValue}）。 */
    private String anchorValue() {
        return INSTANCE_ID;
    }

    /**
     * 共享心跳续期（短 TTL，键存在 = 活）。hold <b>不</b>改变 TTL：
     * 长临界段由 ticker 续期，死掉的 hold 必须能在 ≤ 判死窗内过期。
     */
    private void writeSharedBeat(Long jobId) {
        if (!redisMode || redis == null || !activeRunners.contains(jobId)
                || revokedJobIds.contains(jobId)) {
            return;
        }
        try {
            redis.opsForValue().set(beatKey(jobId), beatValue(this.clock.millis()), beatTtl);
        } catch (RuntimeException e) {
            log.warn("共享心跳续期失败（跨实例判死将 fail-closed）jobId={}：{}", jobId, e.getMessage());
        }
    }

    /** memory 模式：本地心跳（同一进程同一时钟，不存在跨实例漂移）。 */
    private SharedBeat localBeat(Long jobId, long nowMs, long deadWindowMs) {
        Long beatAt = this.lastBeatAtMs.get(jobId);
        if (beatAt == null) {
            return SharedBeat.absent();
        }
        return nowMs - beatAt <= deadWindowMs
                ? new SharedBeat(SharedBeatState.ALIVE, INSTANCE_ID)
                : new SharedBeat(SharedBeatState.STALE, INSTANCE_ID);
    }

    /**
     * 清理共享 runner 状态（{@link #endRunner}/{@link #revokeRunner} 共用）：<b>先删 beat、后删 anchor</b>。
     *
     * <p>危险形态是"anchor 还在、beat 已删" ⇒ 对端读作 {@code STALE} ⇒ 可能给已完成/已撤销的作业
     * 留下不可撤销的 ERROR issue 与取消标志。因此：
     * <ul>
     * <li><b>beat 删除失败</b> ⇒ <b>不</b>删 anchor：两键都在 = {@code ALIVE}，对端不判死，
     *     各自 TTL 收敛（短 beat TTL + 长 anchor TTL）；</li>
     * <li><b>beat 删成功、anchor 删除失败</b> ⇒ 补写一次<b>短 TTL</b> beat，把"anchor-only ⇒
     *     立刻 STALE"退化为"一个判死窗后才可能判死"（有界延迟），而不是立即误杀；</li>
     * <li>补写仍是 {@link #beatTtl()}（不引入永久豁免/无限 TTL）。</li>
     * </ul>
     */
    private void clearSharedRunnerState(Long jobId) {
        if (!redisMode || redis == null) {
            return;
        }
        if (!deleteKeySafely(beatKey(jobId))) {
            log.warn("共享心跳删除失败：保留 runner-active 锚点（避免 anchor-only 被对端误判 STALE）jobId={}",
                    jobId);
            return;
        }
        if (!deleteKeySafely(key(jobId))) {
            rearmSharedBeatForGrace(jobId);
        }
    }

    /**
     * 单键删除（<b>不要</b>把两个键并成一次 {@code DEL}：{@code job:runner-active:<id>} 与
     * {@code job:beat:<id>} 在 Cluster 下不同槽，多键命令会 CROSSSLOT）。
     *
     * @return true = 命令成功（键是否存在不重要）；false = 命令失败（键保留到 TTL）
     */
    private boolean deleteKeySafely(String key) {
        try {
            redis.delete(key);
            return true;
        } catch (RuntimeException e) {
            // 删除失败时键保留到 TTL；删除任务会被 fail-closed 挡住，不产生孤儿。
            log.warn("共享状态键删除失败（等 TTL 过期）key={}：{}", key, e.getMessage());
            return false;
        }
    }

    /** anchor 删除失败后的兜底：补一次短 TTL 心跳，使对端最坏只"延迟判死"，不会立刻误杀。 */
    private void rearmSharedBeatForGrace(Long jobId) {
        try {
            redis.opsForValue().set(beatKey(jobId), beatValue(this.clock.millis()), beatTtl);
            log.warn("runner-active 锚点删除失败：已补写短 TTL 心跳（{}）避免对端立即判死 jobId={}",
                    beatTtl, jobId);
        } catch (RuntimeException e) {
            log.warn("runner-active 锚点删除失败且心跳补写失败（最坏：一个判死窗内仍按 STALE）jobId={}：{}",
                    jobId, e.getMessage());
        }
    }

    /** 启动失败回滚（beginRunner 的 catch）用的尽力清理：两键都删，顺序无关。 */
    private void deleteSafely(String key) {
        deleteKeySafely(key);
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 判死单飞键的获取结果（三态：跳过 / 拿到 / 不可用）。 */
    public enum JudgeLease {
        /** 本实例拿到判死权。 */
        ACQUIRED,
        /** 别的实例持有租约：本轮必须跳过（防重复 issue / 重复终态）。 */
        HELD,
        /** 共享态写不进去：心跳判死 fail-closed；超时判据只收敛终态、不写 issue。 */
        UNAVAILABLE
    }

    /** 共享存活状态（判死输入；redis 模式只看键是否存在，不比较时间戳）。 */
    public enum SharedBeatState {
        /** 共享心跳键存在（Redis TTL 内）：runner 活着。 */
        ALIVE,
        /** 心跳键已不在、但 runner-active 锚点还在（或 memory 模式本地心跳过期）：判死候选。 */
        STALE,
        /** 既无心跳键也无锚点（从未有过运行痕迹）：保守不判，交给绝对上限。 */
        ABSENT,
        /** 共享状态不可读：fail-closed，不得判死。 */
        UNAVAILABLE
    }

    /** 一次共享存活判定的快照（{@code instanceId} 只作诊断，不参与判定）。 */
    public record SharedBeat(SharedBeatState state, String instanceId) {

        static SharedBeat absent() {
            return new SharedBeat(SharedBeatState.ABSENT, null);
        }

        static SharedBeat unavailable() {
            return new SharedBeat(SharedBeatState.UNAVAILABLE, null);
        }
    }

    /** Redis 不可用/共享状态不可读；TaskService.delete 将其映射为 409 fail-closed。 */
    public static class RunnerActiveStateUnavailableException extends RuntimeException {
        private final Long jobId;

        public RunnerActiveStateUnavailableException(Long jobId, String detail) {
            super("无法确认作业 #" + jobId + " 的 runner 活跃状态：" + detail);
            this.jobId = jobId;
        }

        public Long getJobId() {
            return jobId;
        }
    }
}
