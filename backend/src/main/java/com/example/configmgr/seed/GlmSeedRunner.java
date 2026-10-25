package com.example.configmgr.seed;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * glm 种子并集移植的触发点。
 *
 * 基座 {@code DataSeedRunner} 是 {@code ApplicationRunner} 且未声明 @Order，
 * 与新增 Runner 之间无确定的执行先后；而 glm 的范围数据需要基座
 * regions/projects 主数据就绪。故本类监听 {@code ApplicationReadyEvent}
 * （Spring Boot 保证在所有 Runner 之后发布），不改动既有 Runner 的排序或逻辑。
 *
 * <p>多实例：{@code seedIfAbsent} 内部抢 {@code app_lock} 行锁（{@link SeedLock}），
 * 三个实例同时启动时只有一个播种、其余跳过；本类只负责把"本实例这一轮"的终态写进
 * {@link SeedStatus}（/health/ready 的 seed 维度）。
 */
@Slf4j
@Component
public class GlmSeedRunner {

    private final GlmSeedService glmSeedService;

    private final SeedStatus seedStatus;

    public GlmSeedRunner(GlmSeedService glmSeedService, SeedStatus seedStatus) {
        this.glmSeedService = glmSeedService;
        this.seedStatus = seedStatus;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedGlmDefinitions() {
        this.seedStatus.markRunning(SeedStatus.Seed.GLM);
        try {
            boolean seeded = this.glmSeedService.seedIfAbsent();
            this.seedStatus.mark(SeedStatus.Seed.GLM,
                    seeded ? SeedStatus.State.DONE : SeedStatus.State.SKIPPED,
                    seeded ? "本次写入" : "定义已存在（他实例已播种）");
        } catch (Exception e) {
            // 失败不阻断启动（基座既有 7 个定义不受影响），但 ready 面必须如实可见（SeedStatus 的
            // failures 段），不能只留一行日志。
            this.seedStatus.markFailed(SeedStatus.Seed.GLM, e);
            log.error("GLM 种子并集移植失败，基座既有 7 个定义不受影响：{}", e.getMessage(), e);
        }
    }
}
