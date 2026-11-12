package com.example.configmgr.job.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.job.repo.JobItemRepository;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StaleJobRecoveryLogControlTest {

    @Test
    void emptyRecoveryScanHonorsDebugSwitchAndReturnsWithoutSideEffects() {
        Logger logger = (Logger) LoggerFactory.getLogger(StaleJobRecoveryRunner.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        JobRepository jobRepository = mock(JobRepository.class);
        JobItemRepository itemRepository = mock(JobItemRepository.class);
        TaskService taskService = mock(TaskService.class);
        JobHeartbeatRegistry heartbeatRegistry = mock(JobHeartbeatRegistry.class);
        StaleJobRecoveryRunner runner = new StaleJobRecoveryRunner(jobRepository, itemRepository, taskService,
                heartbeatRegistry, mock(PlatformTransactionManager.class));
        when(jobRepository.findInFlightRefsAfter(anyLong(), any(), any(PageRequest.class))).thenReturn(List.of());
        try {
            logger.setLevel(Level.DEBUG);
            runner.run(null);
            assertThat(appender.list).hasSize(1).allMatch(event -> event.getLevel() == Level.DEBUG);

            appender.list.clear();
            logger.setLevel(Level.INFO);
            runner.run(null);
            assertThat(appender.list).isEmpty();
            verify(jobRepository, org.mockito.Mockito.times(2)).findInFlightRefsAfter(anyLong(), any(),
                    any(PageRequest.class));
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(appender);
        }
    }
}
