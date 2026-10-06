package com.example.spike.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "spike")
public class SpikeProperties {

    private final Stream stream = new Stream();
    private final ChatMemory chatMemory = new ChatMemory();
    private final Executor executor = new Executor();

    public Stream getStream() {
        return stream;
    }

    public ChatMemory getChatMemory() {
        return chatMemory;
    }

    public Executor getExecutor() {
        return executor;
    }

    public static class Stream {
        /** 首包（第一个上游事件）超时。 */
        private Duration firstEventTimeout = Duration.ofSeconds(5);
        /** 事件间（已开始产出后）超时。 */
        private Duration interEventTimeout = Duration.ofSeconds(6);
        /** 最大尝试次数（含首次）。 */
        private int maxAttempts = 2;
        /** 整个流的总时长预算，超过即不再重试。 */
        private Duration totalBudget = Duration.ofSeconds(20);

        public Duration getFirstEventTimeout() {
            return firstEventTimeout;
        }

        public void setFirstEventTimeout(Duration firstEventTimeout) {
            this.firstEventTimeout = firstEventTimeout;
        }

        public Duration getInterEventTimeout() {
            return interEventTimeout;
        }

        public void setInterEventTimeout(Duration interEventTimeout) {
            this.interEventTimeout = interEventTimeout;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getTotalBudget() {
            return totalBudget;
        }

        public void setTotalBudget(Duration totalBudget) {
            this.totalBudget = totalBudget;
        }
    }

    public static class ChatMemory {
        private int maxMessages = 40;

        public int getMaxMessages() {
            return maxMessages;
        }

        public void setMaxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
        }
    }

    public static class Executor {
        private int threads = 8;

        public int getThreads() {
            return threads;
        }

        public void setThreads(int threads) {
            this.threads = threads;
        }
    }
}
