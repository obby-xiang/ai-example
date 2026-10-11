package com.example.configmgr.job.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 作业心跳登记簿（R-96 批 B 新建，Q1 裁决）：作业侧运行期心跳的**纯内存**载体。
 *
 * <h2>为什么必须先造心跳</h2>
 * 作业执行侧此前没有任何运行期活动戳（无 DB 列、无内存登记、无周期心跳，侦察报告 §2.1），
 * 看门狗「心跳 ×3 判死」没有可判的输入。本类由 {@link JobProgressService} 在每个分片边界
 * （{@code markRunning} / {@code snapshot} / {@code finish} 三方法入口，REQUIRES_NEW 事务外）
 * 续期一次 —— 一次性覆盖四个 Runner 的全部 17 处分片边界，零额外 DB 写。
 *
 * <h2>纯内存、重启即失（已知边界，登记接受）</h2>
 * {@code jobs} 表无 {@code heartbeat_at} 列（{@code ddl-auto: validate}，加列需 V3 迁移 +
 * 61 个 XML 测试库 schema 同步）；重启场景由启动期 {@link StaleJobRecoveryRunner} 兜底
 * （PENDING/RUNNING 一律 FAILED），故内存态丢失可接受（侦察报告风险 R5）。
 *
 * <h2>时钟可注入</h2>
 * 生产用 {@link Clock#systemUTC()}；单测注入可控时钟，把「失联 3×心跳」从真实睡眠中解放出来。
 */
@Component
public class JobHeartbeatRegistry {

    private final ConcurrentMap<Long, Long> lastBeatAtMs = new ConcurrentHashMap<>();

    /** 处于「心跳判死豁免」中的作业（R-96 批 B 收口修，红队 M1）：hold 期间看门狗的
     *  心跳失联判据对该作业恒为 false；30min 绝对上限判据不受影响。 */
    private final Set<Long> heldJobIds = ConcurrentHashMap.newKeySet();

    private final Clock clock;

    public JobHeartbeatRegistry() {
        this(Clock.systemUTC());
    }

    JobHeartbeatRegistry(Clock clock) {
        this.clock = clock;
    }

    /** 分片边界续期一次心跳（幂等覆盖，只保留最新一次）。 */
    public void beat(Long jobId) {
        this.lastBeatAtMs.put(jobId, this.clock.millis());
    }

    /** 最近一次心跳的纪元毫秒；从未续期过 = {@code null}（看门狗按"无心跳不判死"保守处理）。 */
    public Long lastBeatAtMs(Long jobId) {
        return this.lastBeatAtMs.get(jobId);
    }

    /** 作业收终态后清条目，登记簿不随历史作业无限增长。 */
    public void clear(Long jobId) {
        this.lastBeatAtMs.remove(jobId);
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
    }

    /** 解除心跳判死豁免（<b>幂等</b>）：未 hold 的作业调用无副作用。 */
    public void release(Long jobId) {
        this.heldJobIds.remove(jobId);
    }

    /** 当前是否处于心跳判死豁免中（看门狗判据消费）。 */
    public boolean isHeld(Long jobId) {
        return this.heldJobIds.contains(jobId);
    }
}
