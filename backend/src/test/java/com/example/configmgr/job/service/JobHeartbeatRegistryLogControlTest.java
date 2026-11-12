package com.example.configmgr.job.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.config.AppProperties;
import com.example.configmgr.job.repo.JobRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class JobHeartbeatRegistryLogControlTest {

    @Test
    void revokeWithoutLocalRegistrationHonorsDebugSwitchAndRemainsIdempotent() {
        Logger logger = (Logger) LoggerFactory.getLogger(JobHeartbeatRegistry.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            JobHeartbeatRegistry registry = new JobHeartbeatRegistry(mock(StringRedisTemplate.class), "memory",
                    new AppProperties(), mock(JobRepository.class), mock(JobCancellationRegistry.class));
            logger.setLevel(Level.DEBUG);
            registry.revokeRunner(41L);
            assertThat(appender.list).hasSize(1).allMatch(event -> event.getLevel() == Level.DEBUG);

            appender.list.clear();
            registry.revokeRunner(41L);
            assertThat(appender.list).isEmpty();

            logger.setLevel(Level.INFO);
            registry.revokeRunner(42L);
            assertThat(appender.list).isEmpty();
            assertThat(registry.isRunnerActive(42L)).isFalse();
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(appender);
        }
    }
}
