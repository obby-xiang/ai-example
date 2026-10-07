package com.example.configmgr.job.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 作业取消标志注册表（ADR-8 修正③ / CH-P9 / W1）。
 *
 * <h2>Redis 为准，进程内直通知仅加速</h2>
 * <ul>
 * <li><b>权威态</b>：{@code job:cancel:{jobId}}（STRING，值 = 发起取消的实例标识，带 TTL）。
 *     取消端点只写这一个键 —— 于是"取消作业的是另一个实例/另一个进程"同样成立；
 *     持有作业的执行器在<b>分片/事件边界</b>轮询该键，延迟 ≤1 分片；</li>
 * <li><b>加速态</b>：进程内的 {@link AtomicBoolean} + {@code onCancel} 监听者。
 *     取消方与运行方同进程时监听者当场被唤起，不必等下一个分片边界；
 *     跨进程时监听者为空，靠轮询发现。</li>
 * </ul>
 *
 * <h2>降级（不静默）</h2>
 * Redis 不可用（异常）时 {@link #isCancelled(Long)} 退回进程内标志并记降级事实：
 * 即"单实例等价、跨实例失去取消能力"。取消方写键失败同样只记降级，<b>不抛错</b>
 * （修正③：直通知/共享标志的失败不视为错误，作业状态仍以作业自身终态为准）。
 *
 * <h2>键的清理与 TTL</h2>
 * 取消标志故意不随作业终态删除——它在 TTL 内继续可见（取证），且"取消后立刻重启"
 * 时仍拦得住同名作业的重复启动。作业 id 为 IDENTITY 自增、不会复用，故旧键无副作用。
 */
@Slf4j
@Component
public class JobCancellationRegistry {

    public static final String CANCEL_PREFIX = "job:cancel:";

    /** 取消标志存活时长：覆盖单作业最长运行窗口即可（作业不跨重启续跑，重启后由僵尸恢复收尾）。 */
    private static final Duration CANCEL_TTL = Duration.ofHours(1);

    private final StringRedisTemplate redis;

    private final ConcurrentMap<Long, AtomicBoolean> flags = new ConcurrentHashMap<>();

    private final ConcurrentMap<Long, Set<Runnable>> listeners = new ConcurrentHashMap<>();

    public JobCancellationRegistry(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String keyFor(Long jobId) {
        return CANCEL_PREFIX + jobId;
    }

    /** 作业开始：登记进程内标志与监听者容器（不写 Redis —— 未取消的作业不该有键）。 */
    public void register(Long jobId) {
        this.flags.put(jobId, new AtomicBoolean(false));
        this.listeners.putIfAbsent(jobId, ConcurrentHashMap.newKeySet());
    }

    /** 作业终态：清进程内状态（Redis 标志留着，见类注释）。 */
    public void unregister(Long jobId) {
        this.flags.remove(jobId);
        Set<Runnable> set = this.listeners.remove(jobId);
        if (set != null) {
            set.clear();
        }
    }

    /**
     * 是否已取消。<b>Redis 为准</b>：进程内为假时读一次 Redis，命中即把加速态一并置位。
     * 未登记的作业同样问 Redis（可能由另一实例发起取消）。
     */
    public boolean isCancelled(Long jobId) {
        AtomicBoolean flag = this.flags.get(jobId);
        if (flag != null && flag.get()) {
            return true;
        }
        if (redisFlag(jobId)) {
            markLocal(jobId);
            return true;
        }
        return false;
    }

    /**
     * 发起取消：先写 Redis（权威），再唤起本进程监听者（加速）。
     *
     * @return 落地形态（供端点/验证取证）：权威标志是否写入成功、是否当场唤起本进程监听者
     */
    public CancelResult cancel(Long jobId, String requestedBy) {
        boolean redisWritten = writeRedisFlag(jobId, requestedBy);
        boolean woke = markLocal(jobId);
        return new CancelResult(redisWritten, woke, jobId, keyFor(jobId));
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

    /** 置位加速态并唤起监听者；返回是否"此前未置位、本次置位"。 */
    private boolean markLocal(Long jobId) {
        AtomicBoolean flag = this.flags.computeIfAbsent(jobId, key -> new AtomicBoolean(false));
        boolean newly = flag.compareAndSet(false, true);
        Set<Runnable> set = this.listeners.get(jobId);
        if (set != null) {
            for (Runnable listener : set) {
                try {
                    listener.run();
                } catch (Exception ex) {
                    log.debug("取消监听者执行失败 jobId={}：{}", jobId, ex.getMessage());
                }
            }
        }
        return newly;
    }

    private boolean redisFlag(Long jobId) {
        try {
            return this.redis.opsForValue().get(keyFor(jobId)) != null;
        } catch (Exception ex) {
            log.warn("取消标志读取失败，降级为进程内取消（单实例等价，跨实例失效）jobId={}：{}",
                    jobId, ex.getMessage());
            return false;
        }
    }

    private boolean writeRedisFlag(Long jobId, String requestedBy) {
        try {
            this.redis.opsForValue().set(keyFor(jobId),
                    requestedBy == null ? "unknown" : requestedBy, CANCEL_TTL);
            return true;
        } catch (Exception ex) {
            log.warn("取消标志写入 Redis 失败（仅进程内生效，不视为错误）jobId={}：{}", jobId, ex.getMessage());
            return false;
        }
    }

    /**
     * 一次取消的落地形态。
     *
     * @param redisWritten 权威标志是否写进 Redis（false = 降级，仅进程内可见）
     * @param wokeInProcess 是否当场唤起了本进程监听者（false = 运行方在别的进程或已死，由分片轮询兜住）
     * @param key Redis 键名（取证）
     */
    public record CancelResult(boolean redisWritten, boolean wokeInProcess, Long jobId, String key) {
    }
}
