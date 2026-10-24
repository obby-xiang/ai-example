package com.example.configmgr.job.service;

import com.example.configmgr.config.AppProperties;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 作业心跳 + runner 活跃登记簿（R-96 批 B 新建，Q1 裁决；R1-13/C1 扩多实例）。
 *
 * <p>两种状态刻意分开：
 * <ul>
 * <li><b>心跳</b>（{@code lastBeatAtMs}）：纯进程内，供看门狗判死；{@code clear} 只清它。</li>
 * <li><b>runner 活跃</b>：进程内集合 + 可选 Redis {@code job:runner-active:<jobId>}（TTL + 心跳续期）。
 *     删除闸门读共享态，A 实例 runner 未退出时 B 实例也 409；Redis 不可读时 fail-closed。</li>
 * </ul>
 *
 * <h2>为什么必须先造心跳</h2>
 * 作业执行侧此前没有任何运行期活动戳；看门狗「心跳 ×3 判死」没有可判的输入。本类由
 * {@link JobProgressService} 在每个分片边界（{@code markRunning} / {@code snapshot} / {@code finish}
 * 三方法入口，REQUIRES_NEW 事务外）续期一次，同时续期 Redis runner 活跃键。
 *
 * <h2>重启与 TTL</h2>
 * 心跳仍纯内存；Redis 活跃键 TTL = {@code max-execution-time × 2 + 5m}（由
 * {@link #runnerActiveTtlFor(Duration)} 派生），并由心跳续期。进程重启后，同一主机且旧 PID 已死的键由
 * {@link RunnerActiveKeyCleanupRunner} 启动清理；其他主机/无法解析的键保留到 TTL 过期。
 * 无键/过期键视为旧 runner 不存在，可删。
 *
 * <h2>一致性边界（不要宣称强一致）</h2>
 * {@code beginRunner} 是硬闸门：redis 模式写共享键失败会返回 false，调用方不得启动 runner。
 * {@code beat} 续期失败仍按既定有界租约口径 WARN：键不会无限续命，最坏在 TTL 到期后被当作旧 runner；
 * 因此“A 活着 ⇒ B 永远 409”只在 TTL 窗口与写成功路径内成立，不是跨实例强一致。
 * Redis 读失败时删除侧 fail-closed；Redis 写失败但读恢复的窗口由 begin 硬闸门消除，
 * beat 失败窗口仍需运维关注。
 *
 * <h2>模式</h2>
 * {@code app.job.runner-active-store=redis}（生产默认）或 {@code memory}（单测默认，避免 CI 依赖 Redis）。
 * 即使 memory 模式，删除侧仍保留进程内 runner 闸门。
 *
 * <h2>时钟可注入</h2>
 * 生产用 {@link Clock#systemUTC()}；单测注入可控时钟，把「失联 3×心跳」从真实睡眠中解放出来。
 */
@Slf4j
@Component
public class JobHeartbeatRegistry {

    /** Redis 共享 runner 活跃键（R1-13 / 多实例）。 */
    public static final String RUNNER_ACTIVE_PREFIX = "job:runner-active:";
    /** TTL 余量：在 max-execution-time 的 2 倍之上再留 5m，覆盖判死/收尾延迟。 */
    public static final Duration RUNNER_ACTIVE_TTL_MARGIN = Duration.ofMinutes(5);

    private static final String LOCAL_HOST = hostName();
    private static final String INSTANCE_ID = "runner-" + LOCAL_HOST + "-pid"
            + ProcessHandle.current().pid() + "-" + UUID.randomUUID().toString().substring(0, 6);
    private static final Pattern INSTANCE_ID_PATTERN = Pattern.compile("^runner-(.+)-pid(\\d+)-[^-]+$");

    private final ConcurrentMap<Long, Long> lastBeatAtMs = new ConcurrentHashMap<>();

    /** 处于「心跳判死豁免」中的作业（R-96 批 B 收口修，红队 M1）：hold 期间看门狗的
     *  心跳失联判据对该作业恒为 false；30min 绝对上限判据不受影响。 */
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

    private final Clock clock;
    private final StringRedisTemplate redis;
    private final boolean redisMode;
    private final Duration runnerActiveTtl;

    public JobHeartbeatRegistry() {
        this(Clock.systemUTC(), null, false, runnerActiveTtlFor(Duration.ofMinutes(30)));
    }

    /** Spring 生产构造：默认 redis；测试 classpath 的 application.properties 显式设 memory。 */
    @Autowired
    public JobHeartbeatRegistry(StringRedisTemplate redis,
                                @Value("${app.job.runner-active-store:redis}") String storeMode,
                                AppProperties appProperties) {
        this(Clock.systemUTC(), redis, !"memory".equalsIgnoreCase(storeMode),
                runnerActiveTtlFor(appProperties.getJob().getMaxExecutionTime()));
    }

    JobHeartbeatRegistry(Clock clock) {
        this(clock, null, false, runnerActiveTtlFor(Duration.ofMinutes(30)));
    }

    /** 多实例测试/手工装配用（默认 30m 上限派生 TTL）。 */
    public JobHeartbeatRegistry(Clock clock, StringRedisTemplate redis, boolean redisMode) {
        this(clock, redis, redisMode, runnerActiveTtlFor(Duration.ofMinutes(30)));
    }

    /** 可注入 max-execution-time 派生的 TTL（配置边界测试）。 */
    public JobHeartbeatRegistry(Clock clock, StringRedisTemplate redis, boolean redisMode,
                                Duration runnerActiveTtl) {
        this.clock = clock;
        this.redis = redis;
        this.redisMode = redisMode;
        this.runnerActiveTtl = runnerActiveTtl;
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

    public Duration runnerActiveTtl() {
        return runnerActiveTtl;
    }

    /** 分片边界续期一次心跳（幂等覆盖，只保留最新一次），并续期共享 runner 活跃键。 */
    public void beat(Long jobId) {
        this.lastBeatAtMs.put(jobId, this.clock.millis());
        renewRunnerActive(jobId);
    }

    /** 最近一次心跳的纪元毫秒；从未续期过 = {@code null}（看门狗按"无心跳不判死"保守处理）。 */
    public Long lastBeatAtMs(Long jobId) {
        return this.lastBeatAtMs.get(jobId);
    }

    /**
     * 只清进程内心跳（finish/看门狗用）。<b>不删 Redis runner 活跃键</b>：
     * 看门狗清心跳时 runner 线程可能仍活着，删键会误放行。
     */
    public void clear(Long jobId) {
        this.lastBeatAtMs.remove(jobId);
    }

    /**
     * runner 即将开始（作业创建事务内、commit 前）：本地 + Redis 同时登记。
     *
     * @return redis 模式下共享键写入成功才 true；false 表示不得启动 runner（调用方收敛 FAILED）。
     *         memory 模式恒 true。
     */
    public boolean beginRunner(Long jobId) {
        if (!redisMode) {
            this.activeRunners.add(jobId);
            return true;
        }
        if (redis == null) {
            log.error("runner 活跃键登记失败（Redis 模板未装配），作业不得执行：jobId={}", jobId);
            return false;
        }
        try {
            redis.opsForValue().set(key(jobId), INSTANCE_ID, runnerActiveTtl);
            this.activeRunners.add(jobId);
            return true;
        } catch (RuntimeException e) {
            // 不能 WARN 后继续跑：B 实例看不到共享键会在 runner 活着时放行删除。
            log.error("runner 活跃键登记失败（共享键可能从未写入），作业不得执行：jobId={}", jobId, e);
            return false;
        }
    }

    /** runner 已退出（{@code AsyncJobExecutor} 的 finally，含异常/取消路径）：本地 + Redis 同时注销。 */
    public void endRunner(Long jobId) {
        this.activeRunners.remove(jobId);
        deleteRunnerActive(jobId);
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
     * 绝不把“查不到”当成“可删”。
     */
    public boolean isRunnerAlive(Long jobId) {
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

    /**
     * 慢临界段心跳判死豁免（R-96 批 B 收口修，红队 M1/C2）：hold 期间
     * {@link JobWatchdog} 的心跳失联判据对该作业恒为 {@code false}（单配置项大 sheet 的
     * Excel 写 / 发布长事务等无界区段，作业线程无法续期心跳，期间心跳停摆 ≠ 失联）。
     * 30min 绝对上限判据不受影响，PENDING 语义不变。消费点必须用 try/finally 与
     * {@link #release} 成对包裹长临界段。
     */
    public void hold(Long jobId) {
        this.heldJobIds.add(jobId);
        // 长临界段可能超过心跳 TTL；hold 时续一次，避免 Redis 键在无心跳窗口过期。
        renewRunnerActive(jobId);
    }

    /** 解除心跳判死豁免（<b>幂等</b>）：未 hold 的作业调用无副作用。 */
    public void release(Long jobId) {
        this.heldJobIds.remove(jobId);
    }

    /** 当前是否处于心跳判死豁免中（看门狗判据消费）。 */
    public boolean isHeld(Long jobId) {
        return this.heldJobIds.contains(jobId);
    }

    private String key(Long jobId) {
        return RUNNER_ACTIVE_PREFIX + jobId;
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
            redis.opsForValue().set(key(jobId), INSTANCE_ID, runnerActiveTtl);
        } catch (RuntimeException e) {
            log.warn("runner 活跃键续期失败（删除侧将 fail-closed）jobId={}：{}", jobId, e.getMessage());
        }
    }

    private void deleteRunnerActive(Long jobId) {
        if (!redisMode || redis == null) {
            return;
        }
        try {
            redis.delete(key(jobId));
        } catch (RuntimeException e) {
            // 删除失败时键保留到 TTL；删除任务会被 fail-closed 挡住，不产生孤儿。
            log.warn("runner 活跃键删除失败（等 TTL 过期）jobId={}：{}", jobId, e.getMessage());
        }
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
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
