package com.example.configmgr.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 作业看门狗配置键启动期 fail-fast 用例族（R-96 批 B，Q3/Q4 裁决；
 * 对偶先例：{@code AiPropertiesT35DefaultsTest} 的键间上限关系机器化断言）。
 *
 * <p>{@link AppProperties#verifyJobWatchdogKeys()} 的四条正数守卫（heartbeat-interval /
 * watchdog.scan-period / watchdog.dead-multiplier / max-execution-time 均 &gt; 0）
 * 加一条键间上限关系（{@code heartbeat-interval × dead-multiplier < max-execution-time}），
 * 任一违反 ⇒ {@link IllegalStateException}（不让配置面与运行面说两套话）。
 */
class AppPropertiesJobWatchdogValidationTest {

    @Test
    void defaultsPass() {
        assertThatCode(() -> new AppProperties().verifyJobWatchdogKeys())
                .as("缺省值 30s / 10s / 3 / 30m 满足全部守卫（30s×3=90s < 30m）")
                .doesNotThrowAnyException();
    }

    @Test
    void nonPositiveHeartbeatIntervalFailsFast() {
        AppProperties properties = new AppProperties();
        properties.getJob().setHeartbeatInterval(Duration.ZERO);
        assertThatThrownBy(properties::verifyJobWatchdogKeys)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.job.heartbeat-interval");

        properties.getJob().setHeartbeatInterval(Duration.ofSeconds(-1));
        assertThatThrownBy(properties::verifyJobWatchdogKeys)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.job.heartbeat-interval");
    }

    @Test
    void nonPositiveScanPeriodFailsFast() {
        AppProperties properties = new AppProperties();
        properties.getJob().getWatchdog().setScanPeriod(Duration.ZERO);
        assertThatThrownBy(properties::verifyJobWatchdogKeys)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.job.watchdog.scan-period");

        properties.getJob().getWatchdog().setScanPeriod(Duration.ofSeconds(-10));
        assertThatThrownBy(properties::verifyJobWatchdogKeys)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.job.watchdog.scan-period");
    }

    @Test
    void nonPositiveDeadMultiplierFailsFast() {
        AppProperties properties = new AppProperties();
        properties.getJob().getWatchdog().setDeadMultiplier(0);
        assertThatThrownBy(properties::verifyJobWatchdogKeys)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.job.watchdog.dead-multiplier");

        properties.getJob().getWatchdog().setDeadMultiplier(-3);
        assertThatThrownBy(properties::verifyJobWatchdogKeys)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.job.watchdog.dead-multiplier");
    }

    @Test
    void nonPositiveMaxExecutionTimeFailsFast() {
        AppProperties properties = new AppProperties();
        properties.getJob().setMaxExecutionTime(Duration.ZERO);
        assertThatThrownBy(properties::verifyJobWatchdogKeys)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.job.max-execution-time");

        properties.getJob().setMaxExecutionTime(Duration.ofMinutes(-30));
        assertThatThrownBy(properties::verifyJobWatchdogKeys)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.job.max-execution-time");
    }

    @Test
    void deadWindowMustStayBelowMaxExecutionTime() {
        // 判死窗 = 心跳 × 倍数 ≥ 绝对上限：dead-multiplier 形同虚设，必须 fail-fast
        AppProperties properties = new AppProperties();
        properties.getJob().setHeartbeatInterval(Duration.ofMinutes(15));
        properties.getJob().setMaxExecutionTime(Duration.ofMinutes(30)); // 15m × 3 = 45m ≥ 30m
        assertThatThrownBy(properties::verifyJobWatchdogKeys)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须 < max-execution-time");

        // 边界收紧一侧：判死窗恰好等于上限同样不接受（守卫是严格小于）
        AppProperties boundary = new AppProperties();
        boundary.getJob().setHeartbeatInterval(Duration.ofMinutes(10));
        boundary.getJob().setMaxExecutionTime(Duration.ofMinutes(30)); // 10m × 3 = 30m = 上限
        assertThatThrownBy(boundary::verifyJobWatchdogKeys)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void relationHoldsWhenDeadWindowBelowMaxExecutionTime() {
        AppProperties properties = new AppProperties();
        properties.getJob().setHeartbeatInterval(Duration.ofSeconds(60));
        properties.getJob().getWatchdog().setDeadMultiplier(2);
        properties.getJob().setMaxExecutionTime(Duration.ofMinutes(10)); // 60s×2=120s < 10m
        assertThatCode(properties::verifyJobWatchdogKeys).doesNotThrowAnyException();
    }

    @Test
    void overflowingDeadWindowFailsFastWithIllegalState() {
        // heartbeat-interval 接近 Duration 可表示上限 × dead-multiplier 溢出（红队 m6）：
        // multipliedBy 的 ArithmeticException 被守卫转为 IllegalStateException（含当前值回显）
        AppProperties properties = new AppProperties();
        properties.getJob().setHeartbeatInterval(Duration.ofSeconds(4_000_000_000_000_000_000L)); // ×3 溢出

        assertThatThrownBy(properties::verifyJobWatchdogKeys)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("溢出")
                .hasMessageContaining("heartbeat-interval")
                .satisfies(ex -> assertThat(ex.getCause()).isInstanceOf(ArithmeticException.class));
    }
}
