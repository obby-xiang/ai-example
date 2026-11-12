package com.example.configmgr.job.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobItemRepository;
import com.example.configmgr.job.repo.JobRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobProgressServiceLogControlTest {

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final JobHeartbeatRegistry heartbeatRegistry = mock(JobHeartbeatRegistry.class);
    private final JobProgressService service = new JobProgressService(this.jobRepository,
            mock(JobItemRepository.class), mock(PlatformTransactionManager.class), this.heartbeatRegistry);

    @Test
    void ignoredTerminalWriteHonorsDebugSwitchAndStillClearsHeartbeat() {
        Logger logger = (Logger) LoggerFactory.getLogger(JobProgressService.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        when(this.jobRepository.finishIfInFlight(anyLong(), any(Job.JobStatus.class), anyInt(), anyInt(), anyInt(),
                anyInt(), any(), any())).thenReturn(0);
        try {
            logger.setLevel(Level.DEBUG);
            assertThat(this.service.finish(51L, Job.JobStatus.COMPLETED, 0, 0, 1, 1)).isFalse();
            assertThat(appender.list).hasSize(1).allMatch(event -> event.getLevel() == Level.DEBUG);

            appender.list.clear();
            logger.setLevel(Level.INFO);
            assertThat(this.service.finish(51L, Job.JobStatus.COMPLETED, 0, 0, 1, 1)).isFalse();
            assertThat(appender.list).isEmpty();
            verify(this.heartbeatRegistry, org.mockito.Mockito.times(2)).clear(51L);
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(appender);
        }
    }
}
