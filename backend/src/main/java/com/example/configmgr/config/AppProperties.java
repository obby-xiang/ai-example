package com.example.configmgr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Ai ai = new Ai();
    private Job job = new Job();
    private FileStorage file = new FileStorage();

    @Data
    public static class Ai {
        private int maxIterations = 12;
        private int sessionTtlMinutes = 30;
        private int hitlTimeoutMinutes = 5;
        private boolean thinkingDisabled = true;
    }

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
