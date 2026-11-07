package com.example.configmgr.job.service;

import com.example.configmgr.config.AppProperties;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 作业取消标志注册表（ADR-8 修正③ / CH-P9 / W1）。
 *
 * <h2>DB 权威，Redis 与进程内通知仅加速</h2>
 * <ul>
 * <li><b>权威态</b>：{@code jobs.status + jobs.cancel_version} 随取消/终态条件写原子提交；
 *     Redis 键保存该版本的镜像，未命中或读失败时执行边界必须回查 DB。</li>
 * <li><b>加速态</b>：{@code job:cancel:{jobId}}、进程内 {@link AtomicBoolean} 与
 *     {@code onCancel} 监听者只减少权威读取与本地发现延迟，均不承担跨实例持久正确性。</li>
 * </ul>
 *
 * <p>Redis 与 DB 都无法给出答案时不得按“未取消”继续执行；取消键 TTL 由作业上限派生。
 *
 * <h2>键的清理与 TTL</h2>
 * 取消标志故意不随作业终态删除——它在 TTL 内继续可见（取证），且"取消后立刻重启"
 * 时仍拦得住同名作业的重复启动。作业 id 为 IDENTITY 自增、不会复用，故旧键无副作用。
 */
@Slf4j
@Component
public class JobCancellationRegistry {

    public static final String CANCEL_PREFIX = "job:cancel:";

    private static final RedisScript<Long> WRITE_MONOTONIC_CANCEL_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current then
              local sep = string.find(current, '|', 1, true)
              if not sep then
                return 0
              end
              local currentVersion = tonumber(string.sub(current, 1, sep - 1))
              if not currentVersion or currentVersion > tonumber(ARGV[1]) then
                return 0
              end
            end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            return 1
            """, Long.class);

    private static final long NEGATIVE_CACHE_MILLIS = 250L;

    private final StringRedisTemplate redis;
    private final JobRepository jobRepository;
    private final Duration cancelTtl;

    private final ConcurrentMap<Long, AtomicBoolean> flags = new ConcurrentHashMap<>();

    private final ConcurrentMap<Long, Set<Runnable>> listeners = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Long> negativeCacheUntilMs = new ConcurrentHashMap<>();
    private final AtomicLong cacheVersion = new AtomicLong();

    @Autowired
    public JobCancellationRegistry(StringRedisTemplate redis, JobRepository jobRepository, AppProperties properties) {
        this.redis = redis;
        this.jobRepository = jobRepository;
        this.cancelTtl = JobHeartbeatRegistry.runnerActiveTtlFor(properties.getJob().getMaxExecutionTime());
    }

    public String keyFor(Long jobId) {
        return CANCEL_PREFIX + jobId;
    }

    /** 作业开始：登记进程内标志与监听者容器。 */
    public void register(Long jobId) {
        invalidate(jobId);
        this.flags.putIfAbsent(jobId, new AtomicBoolean(false));
        this.listeners.putIfAbsent(jobId, ConcurrentHashMap.newKeySet());
    }

    /** 作业终态：清进程内状态（Redis 标志留着，见类注释）。 */
    public void unregister(Long jobId) {
        invalidate(jobId);
        this.flags.remove(jobId);
        Set<Runnable> set = this.listeners.remove(jobId);
        if (set != null) {
            set.clear();
        }
    }

    /** Redis 命中直接返回；DB 未取消结果只缓存 250ms，终态写会主动失效。 */
    public boolean isCancelled(Long jobId) {
        AtomicBoolean flag = this.flags.get(jobId);
        if (flag != null && flag.get()) {
            return true;
        }
        if (isRedisFlagSet(jobId)) {
            isNewlyMarkedLocal(jobId);
            return true;
        }
        long now = System.currentTimeMillis();
        Long cachedUntil = this.negativeCacheUntilMs.get(jobId);
        if (cachedUntil != null && cachedUntil > now) {
            return false;
        }
        if (cachedUntil != null) {
            this.negativeCacheUntilMs.remove(jobId, cachedUntil);
        }
        long version = this.cacheVersion.get();
        if (isDatabaseCancelled(jobId)) {
            isNewlyMarkedLocal(jobId);
            return true;
        }
        this.negativeCacheUntilMs.compute(jobId, (ignored, current) -> {
            if (this.cacheVersion.get() != version) {
                return current;
            }
            return current == null ? now + NEGATIVE_CACHE_MILLIS : Math.max(current, now + NEGATIVE_CACHE_MILLIS);
        });
        return false;
    }

    /** 全局单调序号防止旧 DB 读在终态失效后重新放回负缓存，且不遗留 per-job 版本项。 */
    public void invalidate(Long jobId) {
        this.cacheVersion.incrementAndGet();
        this.negativeCacheUntilMs.remove(jobId);
    }

    /** DB 已提交取消/终态后，再把其持久版本镜像到 Redis 并唤醒本进程。 */
    public CancelResult cancel(Long jobId, String requestedBy) {
        invalidate(jobId);
        Long version = authoritativeCancelVersion(jobId);
        boolean redisWritten = version != null && isRedisFlagWritten(jobId, requestedBy, version);
        boolean woke = isNewlyMarkedLocal(jobId);
        return new CancelResult(redisWritten, woke, jobId, keyFor(jobId));
    }

    private boolean isDatabaseCancelled(Long jobId) {
        try {
            return this.jobRepository.findCancelStateById(jobId)
                    .map(state -> state.getCancelVersion() > 0L
                            || (state.getStatus() != Job.JobStatus.PENDING && state.getStatus() != Job.JobStatus.RUNNING))
                    .orElse(true);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Could not confirm cancellation state for job #" + jobId
                    + " because neither Redis nor the database is readable", ex);
        }
    }

    private Long authoritativeCancelVersion(Long jobId) {
        try {
            return this.jobRepository.findCancelStateById(jobId)
                    .map(JobRepository.CancelState::getCancelVersion).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("Authoritative job cancel version read failed; existing Redis mirror preserved jobId={}",
                    jobId, ex);
            return null;
        }
    }

    /**
     * 登记"被取消时立即执行"的监听者（加速通道；跨进程时不会被调用）。
     *
     * @return 注销句柄（作业终态必须关闭）
     */
    public AutoCloseable onCancel(Long jobId, Runnable listener) {
        Set<Runnable> set = this.listeners.computeIfAbsent(jobId, key -> ConcurrentHashMap.newKeySet());
        set.add(listener);
        return () -> {
            Set<Runnable> current = this.listeners.get(jobId);
            if (current != null) {
                current.remove(listener);
            }
        };
    }

    /** 当前正在登记（可能仍在跑）的作业数。 */
    public int registeredJobs() {
        return this.flags.size();
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 置位加速态并唤起监听者；返回是否"此前未置位、本次置位"（未登记时为 false —— 没有加速位可置）。
     *
     * <p><b>只为已登记的本地作业置位</b>：{@link #unregister} 只由本实例的执行器在终态调用，
     * 非执行实例若为每次跨实例取消 {@code computeIfAbsent} 建 {@link AtomicBoolean}，那些条目
     * 永远没有删除路径（按取消次数单调增长）。DB 仍是权威态，这里只影响本进程的加速通道。
     *
     * <p>监听者如果登记在案（{@link #onCancel} 会自建容器，不依赖加速位），无论有没有置位成功都照常
     * 唤起 —— 唤醒是"通知"，不该被"没有加速位"挡住。
     */
    private boolean isNewlyMarkedLocal(Long jobId) {
        AtomicBoolean flag = this.flags.get(jobId);
        boolean newly = flag != null && flag.compareAndSet(false, true);
        Set<Runnable> set = this.listeners.get(jobId);
        if (set != null) {
            for (Runnable listener : set) {
                try {
                    listener.run();
                } catch (Exception ex) {
                    log.warn("Cancel listener failed jobId={}", jobId, ex);
                }
            }
        }
        return newly;
    }

    private boolean isRedisFlagSet(Long jobId) {
        try {
            return this.redis.opsForValue().get(keyFor(jobId)) != null;
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean isRedisFlagWritten(Long jobId, String requestedBy, long version) {
        try {
            Long written = this.redis.execute(WRITE_MONOTONIC_CANCEL_SCRIPT, List.of(keyFor(jobId)),
                    Long.toString(version), version + "|" + StringUtils.defaultString(requestedBy, "unknown"),
                    Long.toString(this.cancelTtl.toMillis()));
            return written != null && written > 0L;
        } catch (Exception ex) {
            log.warn("Cancel acceleration write failed; runner boundaries will use DB jobId={}",
                    jobId, ex);
            return false;
        }
    }

    /**
     * 一次取消的落地形态。
     *
     * @param redisWritten DB 取消版本的 Redis 加速镜像是否写入成功
     * @param wokeInProcess 本进程加速态是否"本次刚置位"（并已唤起当时存在的监听者）。
     *                      false = 本实例没有该作业的本地登记 / 该作业在本实例已置位过；
     *                      作业线程仍在别的进程或已死时，靠分片轮询 Redis 兜住。
     * @param key Redis 键名（取证）
     */
    public record CancelResult(boolean redisWritten, boolean wokeInProcess, Long jobId, String key) {
    }
}
