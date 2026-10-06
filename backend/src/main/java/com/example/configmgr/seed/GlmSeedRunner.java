package com.example.configmgr.seed;

import lombok.RequiredArgsConstructor;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GlmSeedRunner {

    private final GlmSeedService glmSeedService;

    @EventListener(ApplicationReadyEvent.class)
    public void seedGlmDefinitions() {
        try {
            glmSeedService.seedIfAbsent();
        } catch (Exception e) {
            log.error("GLM 种子并集移植失败，基座既有 7 个定义不受影响：{}", e.getMessage(), e);
        }
    }
}
