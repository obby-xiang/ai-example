package com.example.configmgr.task.service;

import com.example.configmgr.job.service.JobHeartbeatRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1-13 P1-2：runner-active TTL 必须由 app.job.max-execution-time 派生且严格大于它。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:r113-runner-ttl;DB_CLOSE_DELAY=-1",
        "app.job.max-execution-time=2h",
        "app.job.runner-active-store=memory"
})
class RunnerActiveTtlConfigTest {

    @Autowired
    private JobHeartbeatRegistry registry;

    @Test
    void ttlForTwoHourMaxExecutionTimeIsLongerThanTheJobBudget() {
        assertThat(registry.runnerActiveTtl())
                .as("2h 作业上限下 TTL 不能仍是固定 1h")
                .isGreaterThan(Duration.ofHours(2));
        assertThat(registry.runnerActiveTtl())
                .isEqualTo(Duration.ofHours(4).plusMinutes(5));
    }

    @Test
    void ttlFormulaHasMarginOverAnyPositiveMaxExecutionTime() {
        Duration max = Duration.ofMinutes(30);
        assertThat(JobHeartbeatRegistry.runnerActiveTtlFor(max))
                .isGreaterThan(max)
                .isEqualTo(Duration.ofHours(1).plusMinutes(5));
    }
}
