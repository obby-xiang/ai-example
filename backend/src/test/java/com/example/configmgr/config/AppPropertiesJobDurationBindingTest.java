package com.example.configmgr.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 看门狗三 Duration 键的裸数字绑定口径（R-96 批 B 收口修 F4，红队 m3）：
 * {@code @DurationUnit(SECONDS)} 落地后，裸数字按<b>秒</b>解释（{@code 30} = 30s）——
 * 不带单位后缀的运维覆盖（{@code app.job.heartbeat-interval: 30}）不会退化成毫秒绑定
 * （30ms 心跳 ⇒ 90ms 判死窗 ⇒ 全部作业首个分片边界即被误杀，且旧校验无法拦截）；
 * 带后缀写法（{@code 30s} / {@code 500ms}）行为不变。
 *
 * <p>直接以 Spring {@link Binder} 绑定（无需起全量应用上下文），钉死 relaxed binding 层语义。
 */
class AppPropertiesJobDurationBindingTest {

    @Test
    void bareNumbersBindAsSeconds() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource();
        source.put("app.job.heartbeat-interval", "30");
        source.put("app.job.max-execution-time", "1800");
        source.put("app.job.watchdog.scan-period", "10");

        AppProperties properties = new Binder(source).bind("app", Bindable.of(AppProperties.class)).get();

        assertThat(properties.getJob().getHeartbeatInterval())
                .as("裸数字 30 按 30 秒解释").isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getJob().getMaxExecutionTime())
                .as("裸数字 1800 按 1800 秒解释").isEqualTo(Duration.ofSeconds(1800));
        assertThat(properties.getJob().getWatchdog().getScanPeriod())
                .as("裸数字 10 按 10 秒解释").isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void suffixedValuesBindUnchanged() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource();
        source.put("app.job.heartbeat-interval", "500ms");

        AppProperties properties = new Binder(source).bind("app", Bindable.of(AppProperties.class)).get();

        assertThat(properties.getJob().getHeartbeatInterval())
                .as("带后缀写法兼容不变").isEqualTo(Duration.ofMillis(500));
    }
}
