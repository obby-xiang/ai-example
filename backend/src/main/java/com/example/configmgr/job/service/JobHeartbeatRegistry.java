package com.example.configmgr.job.service;

import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.config.AppProperties;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * <li><b>已撤销</b>（{@link #revokeRunner}）：共享 tombstone 与每代 token 共同拒绝迟到 beat/end。</li>
 * </ul>
 *
 * <h2>判死单飞与快速收敛</h2>
 * 判死动作由 {@code SET job:deadjudge:<jobId> <instance> NX PX <lease>} 单飞：只允许一个实例
 * 落 issue / 终态 / 取消标志，重复扫描与多实例并发扫描都不增加重复 issue（多写一次终态也由
 * {@code JobProgressService#finish} 的条件 UPDATE 兜住）。判死<b>成功后</b>
 * {@link #revokeRunner(Long)} 写 runner-active/beat 撤销 fence ⇒ 删除闸门立即释放，不再白等
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
    /** 每主机 runner 精确索引：field=jobId，value=该代 runner 的完整值。 */
    private static final String RUNNER_HOST_INDEX_PREFIX = "job:runner-active-host:";
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

    /**
     * 启动清理用的<b>按值删除</b>：当前值仍是读到的旧值才 DEL。
     *
     * <p>GET 与 DEL 之间的窗口里，本机新进程可能已用同一 jobId 重新登记（{@code beginRunner} 是无条件
     * {@code SET}），非条件 DEL 会把活着的 runner-active 键删掉、错误地打开删除闸门。脚本把
     * 「比对 + 删除」放进同一个原子步；只操作单键，Cluster 下不存在跨槽问题。
     */
    private static final RedisScript<Long> DELETE_IF_VALUE_SCRIPT = new DefaultRedisScript<>("""
            -- JOB_RUNNER_ACTIVE_DELETE_IF_VALUE
            local current = redis.call('GET', KEYS[1])
            if (not current) or current ~= ARGV[1] then
              return 0
            end
            redis.call('DEL', KEYS[1])
            return 1
            """, Long.class);

    private static final String WRITE_INDEX_LUA = """
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return 1
            """;

    private static final RedisScript<Long> WRITE_INDEX_SCRIPT = new DefaultRedisScript<>(WRITE_INDEX_LUA, Long.class);

    private static final RedisScript<Long> DELETE_INDEX_VALUE_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('HGET', KEYS[1], ARGV[1])
            if current and string.sub(current, 1, string.len(ARGV[2])) ~= ARGV[2] then
              return 0
            end
            return redis.call('HDEL', KEYS[1], ARGV[1])
            """, Long.class);

    private static final String WRITE_GENERATION_LUA = """
            local current = redis.call('GET', KEYS[1])
            if current and string.sub(current, 1, string.len(ARGV[1])) ~= ARGV[1] then
              return 0
            end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            return 1
            """;
    private static final String DELETE_GENERATION_LUA = """
            local current = redis.call('GET', KEYS[1])
            if not current then
              return 1
            end
            if string.sub(current, 1, string.len(ARGV[1])) ~= ARGV[1] then
              return 0
            end
            redis.call('DEL', KEYS[1])
            return 1
            """;
    private static final RedisScript<Long> WRITE_GENERATION_SCRIPT =
            new DefaultRedisScript<>(WRITE_GENERATION_LUA, Long.class);
    private static final RedisScript<Long> DELETE_GENERATION_SCRIPT =
            new DefaultRedisScript<>(DELETE_GENERATION_LUA, Long.class);
    private static final RedisScript<Long> WRITE_MONOTONIC_FENCE_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current and string.sub(current, 1, 8) == 'REVOKED|' then
              local rest = string.sub(current, 9)
              local sep = string.find(rest, '|', 1, true)
              if not sep then
                return 0
              end
              local currentVersion = tonumber(string.sub(rest, 1, sep - 1))
              if not currentVersion or currentVersion > tonumber(ARGV[1]) then
                return 0
              end
            end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            return 1
            """, Long.class);
    private static final String REVOKED_VALUE_PREFIX = "REVOKED|";
    private static final int HEARTBEAT_PIPELINE_BATCH = 32;

    private static final String LOCAL_HOST = hostName();
    private static final String INSTANCE_ID = "runner-" + LOCAL_HOST + "-pid"
            + ProcessHandle.current().pid() + "-" + UUID.randomUUID().toString().substring(0, 6);

    private final ConcurrentMap<Long, Long> lastBeatAtMs = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, String> runnerGenerations = new ConcurrentHashMap<>();

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

    private static final long AUTHORITY_CACHE_MILLIS = 250L;
    private static final long UNKNOWN_CACHE_MILLIS = 100L;

    private final Clock clock;
    private final StringRedisTemplate redis;
    private final JobRepository jobRepository;
    private final JobCancellationRegistry cancellationRegistry;
    private final boolean redisMode;
    private final Duration runnerActiveTtl;
    private final Duration beatTtl;
    private final AtomicInteger tickCursor = new AtomicInteger();
    private final ConcurrentMap<Long, AuthorityCache> authorityCache = new ConcurrentHashMap<>();

    /** 进程级共享心跳续期器（懒启动；只在 redis 模式且本实例有在跑 runner 时存在）。 */
    private volatile ScheduledExecutorService ticker;

    @Autowired
    public JobHeartbeatRegistry(StringRedisTemplate redis,
                                @Value("${app.job.runner-active-store:redis}") String storeMode,
                                AppProperties appProperties, JobRepository jobRepository,
                                JobCancellationRegistry cancellationRegistry) {
        this.clock = Clock.systemUTC();
        this.redis = redis;
        this.redisMode = !"memory".equalsIgnoreCase(storeMode);
        this.runnerActiveTtl = runnerActiveTtlFor(appProperties.getJob().getMaxExecutionTime());
        this.beatTtl = beatTtlFor(appProperties);
        this.jobRepository = jobRepository;
        this.cancellationRegistry = cancellationRegistry;
    }

    /**
     * TTL 必须严格大于 max-execution-time：合法作业可能在绝对上限内一直运行且心跳短暂停摆；
     * 取 2× + 5m 余量。配置为 2h 时 TTL = 4h5m，不会被合法作业提前跑过期。
     */
    public static Duration runnerActiveTtlFor(Duration maxExecutionTime) {
        if (maxExecutionTime == null || maxExecutionTime.isZero() || maxExecutionTime.isNegative()) {
            throw new IllegalStateException("app.job.max-execution-time must be > 0 to derive the runner-active TTL");
        }
        Duration ttl = maxExecutionTime.multipliedBy(2).plus(RUNNER_ACTIVE_TTL_MARGIN);
        if (ttl.compareTo(maxExecutionTime) <= 0) {
            throw new IllegalStateException("runner-active TTL(" + ttl
                    + ") must be > max-execution-time(" + maxExecutionTime + ")");
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

    /** 分片边界续期：DB 仍在途才允许本代 token 写共享加速态。 */
    public void beat(Long jobId) {
        if (revokedJobIds.contains(jobId) || !databaseInFlight(jobId)) {
            log.debug("Late beat ignored, job terminal or runner revoked jobId={}", jobId);
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

    /** 终态清理同时失效心跳与取消的短 TTL 权威缓存。 */
    public void clear(Long jobId) {
        this.lastBeatAtMs.remove(jobId);
        this.localJudgements.remove(jobId);
        invalidateAuthority(jobId);
    }

    /** 启动必须 fresh 核 DB；UNKNOWN 与明确终态分别归因，不能冒充 Redis 写失败。 */
    public boolean beginRunner(Long jobId) {
        this.revokedJobIds.remove(jobId);
        AuthorityState authority = readAuthority(jobId, true);
        if (authority == AuthorityState.UNKNOWN) {
            throw new JobStateUnavailableException(jobId);
        }
        if (authority == AuthorityState.TERMINAL) {
            throw new JobNotInFlightException(jobId);
        }
        String generation = UUID.randomUUID().toString();
        this.runnerGenerations.put(jobId, generation);
        if (!redisMode) {
            this.activeRunners.add(jobId);
            this.lastBeatAtMs.put(jobId, this.clock.millis());
            return true;
        }
        if (redis == null) {
            log.error("Runner active key registration failed (Redis template not wired), job must not run jobId={}", jobId);
            return false;
        }
        long now = this.clock.millis();
        String value = generationValue(jobId, now);
        try {
            redis.opsForValue().set(key(jobId), value, runnerActiveTtl);
            redis.opsForValue().set(beatKey(jobId), value, beatTtl);
        } catch (RuntimeException e) {
            log.error("Runner active key registration failed, job must not run jobId={}", jobId, e);
            deleteIfGeneration(key(jobId), jobId);
            deleteIfGeneration(beatKey(jobId), jobId);
            this.runnerGenerations.remove(jobId, generation);
            return false;
        }
        writeRunnerIndex(jobId, value);
        this.lastBeatAtMs.put(jobId, now);
        this.activeRunners.add(jobId);
        ensureTicker();
        return true;
    }

    /** 迟到 end 只能清自己的 generation，不能删除撤销 tombstone 或继任者状态。 */
    public void endRunner(Long jobId) {
        this.activeRunners.remove(jobId);
        this.revokedJobIds.remove(jobId);
        clearSharedRunnerState(jobId);
        removeRunnerIndex(jobId, generationPrefix(jobId));
        this.runnerGenerations.remove(jobId);
        invalidateAuthority(jobId);
        stopTickerIfIdle();
    }

    /** 判死撤销写入共享 tombstone；本代之后即使仍存活也不能重建 beat/anchor。 */
    public void revokeRunner(Long jobId) {
        if (!this.activeRunners.remove(jobId) && !this.revokedJobIds.contains(jobId)) {
            log.debug("No local runner registration, shared runner state revoked anyway jobId={}", jobId);
        }
        this.lastBeatAtMs.remove(jobId);
        this.heldJobIds.remove(jobId);
        this.localJudgements.remove(jobId);
        this.revokedJobIds.add(jobId);
        invalidateAuthority(jobId);
        String previousValue = runnerIndexValueBeforeRevocation(jobId);
        String previousIndexKey = hostIndexKeyForValue(previousValue);
        String previousGeneration = generationPrefixOfValue(previousValue);
        writeRevocation(jobId, true);
        removeRunnerIndex(previousIndexKey, jobId, previousGeneration);
        this.runnerGenerations.remove(jobId);
        stopTickerIfIdle();
    }

    /** 用户取消保留 active 锚点直到 runner 退出，但立即围栏后续共享心跳。 */
    public void fenceCancelledRunner(Long jobId) {
        invalidateAuthority(jobId);
        writeRevocation(jobId, false);
    }

    /** 当前是否有登记中的 runner（不含“从未启动/已退出”的作业；本地口径）。 */
    public boolean isRunnerActive(Long jobId) {
        return this.activeRunners.contains(jobId);
    }

    public boolean isRedisMode() {
        return this.redisMode;
    }

    /** 活跃锚点与撤销 tombstone 都占用同一键；只有非 tombstone 值才代表 runner 仍活。 */
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
            throw new RunnerActiveStateUnavailableException(jobId, "Redis template is not wired");
        }
        try {
            String value = redis.opsForValue().get(key(jobId));
            return value != null && !value.startsWith(REVOKED_VALUE_PREFIX);
        } catch (RuntimeException e) {
            throw new RunnerActiveStateUnavailableException(jobId, e);
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
        List<Long> jobs = new ArrayList<>(this.activeRunners);
        if (jobs.isEmpty()) {
            return;
        }
        jobs.sort(Long::compareTo);
        int start = Math.floorMod(this.tickCursor.get(), jobs.size());
        int examined = 0;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                Math.max(250L, Math.min(5_000L, this.beatTtl.toMillis() / 6)));
        List<Long> batch = new ArrayList<>(HEARTBEAT_PIPELINE_BATCH);
        while (examined < jobs.size() && System.nanoTime() < deadline) {
            Long jobId = jobs.get((start + examined) % jobs.size());
            examined++;
            if (this.revokedJobIds.contains(jobId) || !this.activeRunners.contains(jobId)
                    || !databaseInFlight(jobId)) {
                continue;
            }
            batch.add(jobId);
            if (batch.size() >= HEARTBEAT_PIPELINE_BATCH) {
                if (!writeHeartbeatBatch(batch)) {
                    batch.clear();
                    break;
                }
                batch = new ArrayList<>(HEARTBEAT_PIPELINE_BATCH);
            }
        }
        if (!batch.isEmpty() && System.nanoTime() < deadline) {
            writeHeartbeatBatch(batch);
        }
        this.tickCursor.set(start + examined);
        if (examined < jobs.size()) {
            log.warn("Shared heartbeat tick budget exhausted jobCount={} examined={} nextCursor={}",
                    jobs.size(), examined, Math.floorMod(start + examined, jobs.size()));
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
            log.debug("Shared heartbeat ticker started periodMs={} beatTtl={}", periodMs, this.beatTtl);
        }
    }

    private void tickSafely() {
        // 心跳 ticker 是全局的，没有"单作业记录身份"：每拍显式 denied，清掉继承/上一拍残留的身份，
        // 避免这条进程级线程长期持有最近启动者的租户/用户身份。
        try (RunIdentityScope.Scope ignored = RunIdentityScope.denied()) {
            tickOnce();
        } catch (Throwable t) {
            // 单个周期失败不终止 ticker（下个周期重试）；共享键失联由 TTL 兜底
            log.warn("Shared heartbeat renewal tick failed, retrying next period", t);
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
            if (beat != null && !beat.startsWith(REVOKED_VALUE_PREFIX)) {
                return new SharedBeat(SharedBeatState.ALIVE, instanceOf(beat));
            }
            String anchor = redis.opsForValue().get(key(jobId));
            if (anchor == null || anchor.startsWith(REVOKED_VALUE_PREFIX)) {
                return SharedBeat.absent();
            }
            return new SharedBeat(SharedBeatState.STALE, instanceOf(anchor));
        } catch (RuntimeException e) {
            log.debug("Shared heartbeat read failed, fail-closed this round jobId={}", jobId, e);
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
            log.warn("Dead-judgement lease unavailable (Redis template not wired) jobId={}", jobId);
            return JudgeLease.UNAVAILABLE;
        }
        try {
            Boolean acquired = redis.opsForValue()
                    .setIfAbsent(deadJudgeKey(jobId), INSTANCE_ID, deadJudgeLease());
            if (acquired == null) {
                log.warn("Dead-judgement lease unavailable (SET NX returned null) jobId={}", jobId);
                return JudgeLease.UNAVAILABLE;
            }
            return acquired ? JudgeLease.ACQUIRED : JudgeLease.HELD;
        } catch (RuntimeException e) {
            log.warn("Dead-judgement lease unavailable (Redis error) jobId={}", jobId, e);
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
        if (!databaseInFlight(jobId)) {
            return;
        }
        this.heldJobIds.add(jobId);
        renewRunnerActive(jobId);
        writeSharedBeat(jobId);
    }

    /** 解除心跳判死豁免（<b>幂等</b>）：未 hold 的作业调用无副作用。 */
    public void release(Long jobId) {
        this.heldJobIds.remove(jobId);
        if (databaseInFlight(jobId)) {
            writeSharedBeat(jobId);
        }
    }

    /** 当前是否处于心跳判死豁免中（本实例看门狗判据消费）。 */
    public boolean isHeld(Long jobId) {
        return this.heldJobIds.contains(jobId);
    }

    // ── 启动清理（每主机精确索引，不扫描全键空间）─────────────────────────────

    /** HSCAN 的单次 COUNT 提示；总检视条目数由调用方限制。 */
    private static final int CLEANUP_SCAN_COUNT = 256;

    /**
     * 启动只读每主机 HASH 索引，工作量与 Redis 总键数无关。索引成员仍需通过主机/PID
     * 与 runner-active 当前值复核，存活实例不会被误删。
     */
    public int cleanupDeadLocalRunners(int maxKeys) {
        if (!redisMode || redis == null || maxKeys <= 0) {
            return 0;
        }
        int cleaned = 0;
        int inspected = 0;
        try (Cursor<Map.Entry<Object, Object>> cursor = redis.opsForHash().scan(hostIndexKey(),
                ScanOptions.scanOptions().count(CLEANUP_SCAN_COUNT).build())) {
            while (cursor.hasNext() && inspected < maxKeys) {
                Map.Entry<Object, Object> entry = cursor.next();
                inspected++;
                Long jobId;
                try {
                    jobId = Long.valueOf(String.valueOf(entry.getKey()));
                } catch (NumberFormatException ex) {
                    log.debug("Runner host index contains a non-numeric job id field={}", entry.getKey(), ex);
                    continue;
                }
                if (cleanupDeadLocalRunner(jobId, String.valueOf(entry.getValue()))) {
                    cleaned++;
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Startup runner host index read failed, remaining keys left to TTL", ex);
            return cleaned;
        }
        if (inspected >= maxKeys) {
            log.warn("Startup runner host index inspection reached its bound maxKeys={}", maxKeys);
        }
        return cleaned;
    }

    /** 单键清理保留原语义：只处理同主机且 PID 已死的登记，并按当前值条件删除。 */
    public boolean cleanupDeadLocalRunner(Long jobId) {
        return cleanupDeadLocalRunner(jobId, null);
    }

    private boolean cleanupDeadLocalRunner(Long jobId, String indexedValue) {
        if (!redisMode || redis == null) {
            return false;
        }
        try {
            String value = redis.opsForValue().get(key(jobId));
            String ownerValue = ObjectUtils.defaultIfNull(value, indexedValue);
            if (ownerValue != null && ownerValue.startsWith(REVOKED_VALUE_PREFIX)) {
                removeRunnerIndex(jobId, generationPrefixOfValue(indexedValue));
                return false;
            }
            String instance = instanceOf(ownerValue);
            Matcher matcher = instance == null ? null : INSTANCE_ID_PATTERN.matcher(instance);
            if (matcher == null || !matcher.matches() || !LOCAL_HOST.equals(matcher.group(1))) {
                return false;
            }
            long pid = Long.parseLong(matcher.group(2));
            if (ProcessHandle.of(pid).isPresent()) {
                return false;
            }
            Long deleted = value == null ? 0L : redis.execute(DELETE_IF_VALUE_SCRIPT, List.of(key(jobId)), value);
            removeRunnerIndex(jobId, generationPrefixOfValue(ownerValue));
            if (deleted != null && deleted > 0) {
                log.info("Cleaned runner active key of dead local process jobId={} instance={}", jobId, value);
                return true;
            }
            return false;
        } catch (RuntimeException e) {
            log.warn("Runner active key cleanup failed, left to TTL jobId={}", jobId, e);
            return false;
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

    private String hostIndexKey() {
        return RUNNER_HOST_INDEX_PREFIX + LOCAL_HOST;
    }

    private String runnerIndexValueBeforeRevocation(Long jobId) {
        String localValue = generationValue(jobId, this.clock.millis());
        if (!redisMode || redis == null) {
            return localValue;
        }
        try {
            String activeValue = redis.opsForValue().get(key(jobId));
            if (activeValue != null && !activeValue.startsWith(REVOKED_VALUE_PREFIX)) {
                return activeValue;
            }
            Object indexedValue = redis.opsForHash().get(hostIndexKey(), jobId.toString());
            return indexedValue == null ? localValue : String.valueOf(indexedValue);
        } catch (RuntimeException ex) {
            log.warn("Previous runner generation read failed; existing index preserved jobId={}",
                    jobId, ex);
            return localValue;
        }
    }

    private String hostIndexKeyForValue(String value) {
        String instance = instanceOf(value);
        Matcher matcher = instance == null ? null : INSTANCE_ID_PATTERN.matcher(instance);
        return matcher == null || !matcher.matches() ? hostIndexKey()
                : RUNNER_HOST_INDEX_PREFIX + matcher.group(1);
    }

    private void writeRunnerIndex(Long jobId, String value) {
        if (!redisMode || redis == null || value == null) {
            return;
        }
        try {
            redis.execute(WRITE_INDEX_SCRIPT, List.of(hostIndexKey()), jobId.toString(), value,
                    Long.toString(runnerActiveTtl.toMillis()));
        } catch (RuntimeException ex) {
            log.warn("Runner host index write failed; active key TTL remains authoritative jobId={}",
                    jobId, ex);
        }
    }

    private void removeRunnerIndex(Long jobId, String value) {
        removeRunnerIndex(hostIndexKey(), jobId, value);
    }

    private void removeRunnerIndex(String indexKey, Long jobId, String value) {
        if (!redisMode || redis == null || indexKey == null || value == null) {
            return;
        }
        try {
            redis.execute(DELETE_INDEX_VALUE_SCRIPT, List.of(indexKey), jobId.toString(), value);
        } catch (RuntimeException ex) {
            log.warn("Runner host index cleanup failed jobId={}", jobId, ex);
        }
    }

    private String generationPrefix(Long jobId) {
        String generation = this.runnerGenerations.get(jobId);
        return generation == null ? null : INSTANCE_ID + "|" + generation + "|";
    }

    private String generationValue(Long jobId, long nowMs) {
        String prefix = generationPrefix(jobId);
        return prefix == null ? null : prefix + nowMs;
    }

    private static String instanceOf(String raw) {
        if (raw == null || raw.startsWith(REVOKED_VALUE_PREFIX)) {
            return null;
        }
        int sep = raw.indexOf('|');
        return sep <= 0 ? raw : raw.substring(0, sep);
    }

    private static String generationPrefixOfValue(String value) {
        if (value == null) {
            return null;
        }
        int sep = value.lastIndexOf('|');
        return sep < 0 ? value : value.substring(0, sep + 1);
    }

    private boolean databaseInFlight(Long jobId) {
        return readAuthority(jobId, false) != AuthorityState.TERMINAL;
    }

    private AuthorityState readAuthority(Long jobId, boolean fresh) {
        long now = this.clock.millis();
        if (!fresh) {
            AuthorityCache cached = this.authorityCache.get(jobId);
            if (cached != null && cached.expiresAtMs() > now) {
                return cached.state();
            }
        }
        try {
            AuthorityState current = this.jobRepository.findCancelStateById(jobId)
                    .map(state -> state.getStatus() == Job.JobStatus.PENDING
                            || state.getStatus() == Job.JobStatus.RUNNING
                            ? AuthorityState.IN_FLIGHT : AuthorityState.TERMINAL)
                    .orElse(AuthorityState.TERMINAL);
            this.authorityCache.put(jobId, new AuthorityCache(current, now + AUTHORITY_CACHE_MILLIS));
            return current;
        } catch (RuntimeException ex) {
            log.debug("Authoritative job state read unknown; liveness renewal continues jobId={}",
                    jobId, ex);
            this.authorityCache.put(jobId, new AuthorityCache(AuthorityState.UNKNOWN, now + UNKNOWN_CACHE_MILLIS));
            return AuthorityState.UNKNOWN;
        }
    }

    private void invalidateAuthority(Long jobId) {
        this.authorityCache.remove(jobId);
        this.cancellationRegistry.invalidate(jobId);
    }

    private Long authoritativeCancelVersion(Long jobId) {
        try {
            return this.jobRepository.findCancelStateById(jobId)
                    .map(JobRepository.CancelState::getCancelVersion).orElse(null);
        } catch (RuntimeException ex) {
            log.debug("Cancel version unavailable; existing runner fence preserved jobId={}", jobId, ex);
            return null;
        }
    }

    private void renewRunnerActive(Long jobId) {
        if (!redisMode || redis == null || !activeRunners.contains(jobId)) {
            return;
        }
        String value = generationValue(jobId, this.clock.millis());
        if (writeGeneration(key(jobId), jobId, runnerActiveTtl, value)) {
            writeRunnerIndex(jobId, value);
        }
    }

    private void writeSharedBeat(Long jobId) {
        if (!redisMode || redis == null || !activeRunners.contains(jobId)
                || revokedJobIds.contains(jobId)) {
            return;
        }
        writeGeneration(beatKey(jobId), jobId, beatTtl);
    }

    private boolean writeGeneration(String redisKey, Long jobId, Duration ttl) {
        return writeGeneration(redisKey, jobId, ttl, generationValue(jobId, this.clock.millis()));
    }

    private boolean writeGeneration(String redisKey, Long jobId, Duration ttl, String value) {
        String prefix = generationPrefix(jobId);
        if (prefix == null) {
            return false;
        }
        try {
            Long written = redis.execute(WRITE_GENERATION_SCRIPT, List.of(redisKey), prefix,
                    value, Long.toString(ttl.toMillis()));
            return written != null && written > 0L;
        } catch (RuntimeException ex) {
            log.debug("Generation-fenced runner write failed jobId={}", jobId, ex);
            return false;
        }
    }

    private boolean writeHeartbeatBatch(List<Long> jobIds) {
        if (jobIds.isEmpty()) {
            return true;
        }
        long now = this.clock.millis();
        List<Long> candidates = new ArrayList<>(jobIds.size());
        List<String> values = new ArrayList<>(jobIds.size());
        for (Long jobId : jobIds) {
            String prefix = generationPrefix(jobId);
            if (prefix != null && activeRunners.contains(jobId) && !revokedJobIds.contains(jobId)) {
                candidates.add(jobId);
                values.add(generationValue(jobId, now));
            }
        }
        if (candidates.isEmpty()) {
            return true;
        }
        try {
            List<Object> activeResults = redis.executePipelined((RedisCallback<Object>) connection -> {
                for (int i = 0; i < candidates.size(); i++) {
                    Long jobId = candidates.get(i);
                    byte[] value = bytes(values.get(i));
                    byte[] generation = bytes(generationPrefix(jobId));
                    connection.eval(bytes(WRITE_GENERATION_LUA), ReturnType.INTEGER, 1,
                            bytes(key(jobId)), generation, value, bytes(Long.toString(runnerActiveTtl.toMillis())));
                    connection.eval(bytes(WRITE_GENERATION_LUA), ReturnType.INTEGER, 1,
                            bytes(beatKey(jobId)), generation, value, bytes(Long.toString(beatTtl.toMillis())));
                }
                return null;
            });
            if (activeResults == null || activeResults.size() != candidates.size() * 2) {
                log.warn("Generation-fenced heartbeat result shape mismatch; host index renewal skipped");
                return false;
            }
            List<Long> indexJobIds = new ArrayList<>(candidates.size());
            List<String> indexValues = new ArrayList<>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                Object activeResult = activeResults.get(i * 2);
                if (activeResult instanceof Number number && number.longValue() > 0L) {
                    indexJobIds.add(candidates.get(i));
                    indexValues.add(values.get(i));
                }
            }
            if (indexJobIds.isEmpty()) {
                return true;
            }
            List<Object> indexResults = redis.executePipelined((RedisCallback<Object>) connection -> {
                byte[] hostIndex = bytes(hostIndexKey());
                for (int i = 0; i < indexJobIds.size(); i++) {
                    connection.eval(bytes(WRITE_INDEX_LUA), ReturnType.INTEGER, 1, hostIndex,
                            bytes(indexJobIds.get(i).toString()), bytes(indexValues.get(i)),
                            bytes(Long.toString(runnerActiveTtl.toMillis())));
                }
                return null;
            });
            if (indexResults == null || indexResults.size() != indexJobIds.size()) {
                log.warn("Runner host index renewal result shape mismatch; active key TTL remains authoritative");
                return false;
            }
            for (Object result : indexResults) {
                if (!(result instanceof Number number) || number.longValue() <= 0L) {
                    log.warn("Runner host index renewal failed; active key TTL remains authoritative");
                    return false;
                }
            }
            return true;
        } catch (RuntimeException ex) {
            log.warn("Generation-fenced heartbeat or host index pipeline failed, remainder of tick skipped",
                    ex);
            return false;
        }
    }

    private void writeRevocation(Long jobId, boolean includeAnchor) {
        if (!redisMode || redis == null) {
            return;
        }
        Long version = authoritativeCancelVersion(jobId);
        if (version == null) {
            log.warn("Shared runner revocation skipped; DB version unknown and existing fence preserved jobId={}", jobId);
            return;
        }
        String tombstone = REVOKED_VALUE_PREFIX + version + "|" + INSTANCE_ID;
        String ttl = Long.toString(runnerActiveTtl.toMillis());
        String expectedVersion = Long.toString(version);
        try {
            Long beatWritten = redis.execute(WRITE_MONOTONIC_FENCE_SCRIPT, List.of(beatKey(jobId)),
                    expectedVersion, tombstone, ttl);
            if (beatWritten == null || beatWritten <= 0L) {
                log.warn("Shared beat fence preserved at same/newer version jobId={} version={}", jobId, version);
            }
            if (includeAnchor) {
                Long anchorWritten = redis.execute(WRITE_MONOTONIC_FENCE_SCRIPT, List.of(key(jobId)),
                        expectedVersion, tombstone, ttl);
                if (anchorWritten == null || anchorWritten <= 0L) {
                    log.warn("Shared active fence preserved at same/newer version jobId={} version={}", jobId, version);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Shared runner revocation write failed; DB terminal state remains authoritative jobId={}",
                    jobId, ex);
        }
    }

    private DeleteOutcome deleteIfGeneration(String redisKey, Long jobId) {
        String prefix = generationPrefix(jobId);
        if (prefix == null) {
            return DeleteOutcome.DELETED;
        }
        try {
            Long deleted = redis.execute(DELETE_GENERATION_SCRIPT, List.of(redisKey), prefix);
            return deleted != null && deleted > 0L ? DeleteOutcome.DELETED : DeleteOutcome.NOT_OWNER;
        } catch (RuntimeException ex) {
            log.warn("Generation-fenced runner delete failed jobId={}", jobId, ex);
            return DeleteOutcome.ERROR;
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

    private void clearSharedRunnerState(Long jobId) {
        if (!redisMode || redis == null) {
            return;
        }
        if (deleteIfGeneration(beatKey(jobId), jobId) == DeleteOutcome.ERROR) {
            log.warn("Shared heartbeat delete failed, runner-active anchor kept jobId={}", jobId);
            return;
        }
        if (deleteIfGeneration(key(jobId), jobId) == DeleteOutcome.ERROR) {
            rearmSharedBeatForGrace(jobId);
        }
    }

    private void rearmSharedBeatForGrace(Long jobId) {
        if (writeGeneration(beatKey(jobId), jobId, beatTtl)) {
            log.warn("runner-active anchor delete failed, short-TTL heartbeat rewritten beatTtl={} jobId={}",
                    beatTtl, jobId);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private enum DeleteOutcome {
        DELETED,
        NOT_OWNER,
        ERROR
    }

    private enum AuthorityState {
        IN_FLIGHT,
        TERMINAL,
        UNKNOWN
    }

    private record AuthorityCache(AuthorityState state, long expiresAtMs) {
    }

    public static class JobStateUnavailableException extends RuntimeException {
        public JobStateUnavailableException(Long jobId) {
            super("Could not confirm the database state for job #" + jobId + "; the job was not started");
        }
    }

    public static class JobNotInFlightException extends RuntimeException {
        public JobNotInFlightException(Long jobId) {
            super("Job #" + jobId + " is terminal or does not exist; its runner cannot be started");
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
            super("Could not confirm runner activity for job #" + jobId + ": " + detail);
            this.jobId = jobId;
        }

        public RunnerActiveStateUnavailableException(Long jobId, Throwable cause) {
            super("Could not confirm runner activity for job #" + jobId + " because Redis is unreadable", cause);
            this.jobId = jobId;
        }

        public Long getJobId() {
            return jobId;
        }
    }
}
