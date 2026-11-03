package com.example.configmgr.job.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * R1-13 多实例：启动时清理“同一主机旧进程”遗留的 runner-active 键。
 *
 * <p>Redis 键带派生 TTL（{@code max-execution-time × 2 + 5m}，见
 * {@link JobHeartbeatRegistry#runnerActiveTtlFor(java.time.Duration)}），进程被杀后键不会随进程消失。若旧进程与当前进程同主机且 PID 已死，
 * 清理该键，使“重启后无活跃 runner”立即可删；其他主机/无法解析/仍存活的 PID 一律保留到 TTL，
 * 避免误删另一实例正在运行的 runner。Redis 不可用时只记日志，不阻断启动；删除侧仍 fail-closed。
 *
 * <h2>候选集 = 现存键，不是全库作业（线程修复-后端）</h2>
 * 旧实现遍历 {@code jobRepository.findAllIds()}，对<b>全库每个作业</b>做
 * "GET + 条件 DEL 脚本"两次 Redis 往返：候选数与历史作业量同阶（1 万作业 ≈ 1 万次串行 GET），
 * 而其中绝大多数键早已过 TTL。现在改为 {@link JobHeartbeatRegistry#cleanupDeadLocalRunners(int)}：
 * {@code SCAN job:runner-active:*}（Cluster 逐主节点）→ 按值安全清理，命令量∝现存键数。
 * 保留 {@code @Order(-1)} 的时序语义不变：仍必须先于 {@code StaleJobRecoveryRunner(@Order(0))}，
 * 恢复才能读到真实的存活态。
 */
@Slf4j
@Component
@Order(-1) // P1-6：必须先于 StaleJobRecoveryRunner(@Order(0)) 清理本机死 PID 键，恢复才能读到真实存活态
@RequiredArgsConstructor
public class RunnerActiveKeyCleanupRunner implements ApplicationRunner {

    /**
     * 单次启动最多<b>检视</b>的键数（跨 Cluster 节点合计；按游标取出的所有键计，含解析失败的键）。
     *
     * <p>存量键 = 在跑 runner + 尚未过 TTL 的历史键，正常量级是"个位数~几十"；
     * 这个上限只用于把异常/失控键空间挡在启动路径之外（超出部分留给 TTL 收敛）。
     */
    private static final int MAX_SCANNED_KEYS = 512;

    private final JobHeartbeatRegistry heartbeatRegistry;

    @Override
    public void run(ApplicationArguments args) {
        if (!heartbeatRegistry.isRedisMode()) {
            return;
        }
        try {
            int cleaned = heartbeatRegistry.cleanupDeadLocalRunners(MAX_SCANNED_KEYS);
            if (cleaned > 0) {
                log.info("Startup cleanup of dead local runner-active keys finished cleaned={}", cleaned);
            }
        } catch (RuntimeException e) {
            log.warn("Startup cleanup of runner-active keys failed, startup continues error={}", e.getMessage());
        }
    }
}
