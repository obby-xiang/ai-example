package com.example.configmgr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import lombok.Data;

/**
 * 基座业务配置（{@code app.*}）。
 *
 * <p>
 * {@code app.ai.*} 不在本类：AI Runtime 的运行参数由 {@code com.example.configmgr.ai.config.AiProperties}
 * 单一绑定（S4.2-P1 遗留 N4 的"双绑定"已消除 —— 原来的 {@code AppProperties.Ai}
 * 带 max-iterations/session-ttl-minutes/hitl-timeout-minutes/thinking-disabled 四个键，
 * 与规格 §4 的键表重复或已失效，本棒一并删除）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Job job = new Job();
    private FileStorage file = new FileStorage();

    @Data
    public static class Job {
        private int demoBatchDelayMs = 300;
        private int batchSize = 100;
    }

    @Data
    public static class FileStorage {
        private String storagePath = "./data/files";
    }
}
