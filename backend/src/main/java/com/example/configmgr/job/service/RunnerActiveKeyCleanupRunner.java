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
 * <h2>候选集 = 本机精确索引</h2>
 * 不再遍历全库作业，也不扫描 Redis 全键空间。{@link JobHeartbeatRegistry} 在每个 runner
 * 生命周期内维护每主机 HASH 索引，启动仅检视该索引中的少量成员，再按原主机/PID/当前值规则清理。
 * 保留 {@code @Order(-1)} 的时序语义不变：仍必须先于 {@code StaleJobRecoveryRunner(@Order(0))}，
 * 恢复才能读到真实的存活态。
 */
@Slf4j
@Component
@Order(-1) // P1-6：必须先于 StaleJobRecoveryRunner(@Order(0)) 清理本机死 PID 键，恢复才能读到真实存活态
@RequiredArgsConstructor
public class RunnerActiveKeyCleanupRunner implements ApplicationRunner {

    /** 单次启动最多检视的本机索引条目数；超出部分留给 TTL 与后续启动收敛。 */
    private static final int MAX_INDEX_ENTRIES = 512;

    private final JobHeartbeatRegistry heartbeatRegistry;

    @Override
    public void run(ApplicationArguments args) {
        if (!heartbeatRegistry.isRedisMode()) {
            return;
        }
        try {
            int cleaned = heartbeatRegistry.cleanupDeadLocalRunners(MAX_INDEX_ENTRIES);
            if (cleaned > 0) {
                log.info("Startup cleanup of dead local runner-active keys finished cleaned={}", cleaned);
            }
        } catch (RuntimeException e) {
            log.warn("Startup cleanup of runner-active keys failed, startup continues error={}", e.getMessage());
        }
    }
}
