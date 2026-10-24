package com.example.configmgr.job.service;

import com.example.configmgr.job.repo.JobRepository;
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
 */
@Slf4j
@Component
@Order(-1) // P1-6：必须先于 StaleJobRecoveryRunner(@Order(0)) 清理本机死 PID 键，恢复才能读到真实存活态
@RequiredArgsConstructor
public class RunnerActiveKeyCleanupRunner implements ApplicationRunner {

    private final JobRepository jobRepository;
    private final JobHeartbeatRegistry heartbeatRegistry;

    @Override
    public void run(ApplicationArguments args) {
        if (!heartbeatRegistry.isRedisMode()) {
            return;
        }
        try {
            for (Long jobId : jobRepository.findAllIds()) {
                heartbeatRegistry.cleanupDeadLocalRunner(jobId);
            }
        } catch (RuntimeException e) {
            log.warn("启动清理 runner-active 键失败（不阻断启动，删除侧仍 fail-closed）：{}", e.getMessage());
        }
    }
}
