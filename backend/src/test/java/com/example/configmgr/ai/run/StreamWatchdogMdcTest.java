package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.exec.AiExecutorConfig;
import com.example.configmgr.ai.observe.AiTrace;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class StreamWatchdogMdcTest {

    @Test
    void scheduledTickRestoresTheSubmissionMdc() throws Exception {
        ScheduledExecutorService scheduler = new AiExecutorConfig().aiStreamWatchdog();
        AiProperties.Resilience config = new AiProperties().getResilience();
        config.setTotalBudget(Duration.ofMillis(1));
        AtomicReference<String> observed = new AtomicReference<>();
        StreamWatchdog watchdog;
        CancellationRegistry cancellations = mock(CancellationRegistry.class);
        org.mockito.Mockito.when(cancellations.onCancel(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(() -> { });
        AiTrace.bind("run-mdc", "session-mdc");
        try {
            watchdog = new StreamWatchdog("run-mdc", scheduler, config, mock(ToolActivityBeacon.class),
                    cancellations, System.currentTimeMillis(),
                    violation -> observed.set(MDC.get(AiTrace.RUN_ID)));
        } finally {
            AiTrace.clear();
        }

        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (observed.get() == null && System.nanoTime() < deadline) {
                Thread.sleep(20L);
            }
            assertThat(observed.get()).isEqualTo("run-mdc");
        } finally {
            watchdog.close();
            scheduler.shutdownNow();
        }
    }
}
