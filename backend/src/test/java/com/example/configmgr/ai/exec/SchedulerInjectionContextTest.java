package com.example.configmgr.ai.exec;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.kb.KbSchedulerConfig;
import com.example.configmgr.ai.kb.KbService;
import com.example.configmgr.ai.kb.KbSource;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.session.AiSessionPurgeService;
import com.example.configmgr.ai.session.SessionPurgePendingStore;
import com.example.configmgr.ai.session.SessionPurgeRetryRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SchedulerInjectionContextTest {

    @Test
    void contextResolvesDedicatedPurgeAndKbSchedulers() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AiExecutorConfig.class, SessionPurgeRetryRunner.class, KbService.class,
                    KbSchedulerConfig.class, TestDependencies.class);
            context.refresh();

            ScheduledExecutorService purge = (ScheduledExecutorService) ReflectionTestUtils.getField(
                    context.getBean(SessionPurgeRetryRunner.class), "scheduler");
            Object kb = ReflectionTestUtils.getField(context.getBean(KbService.class), "scheduler");
            assertThat(purge).isSameAs(context.getBean("aiSessionPurgeRetryScheduler"));
            assertThat(purge).isNotSameAs(context.getBean("aiStreamWatchdog"));
            assertThat(purge).isNotSameAs(context.getBean("aiCancelPoll"));
            assertThat(kb).isSameAs(context.getBean("kbRefreshScheduler"));
        }
    }

    @Configuration
    static class TestDependencies {

        @Bean
        SessionPurgePendingStore pendingStore() {
            return mock(SessionPurgePendingStore.class);
        }

        @Bean
        AiSessionPurgeService purgeService() {
            return mock(AiSessionPurgeService.class);
        }

        @Bean
        LeaseService leases() {
            return mock(LeaseService.class);
        }

        @Bean
        AiProperties properties() {
            AiProperties properties = new AiProperties();
            properties.getKb().setEnabled(false);
            return properties;
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        KbSource kbSource() {
            return mock(KbSource.class);
        }
    }
}
