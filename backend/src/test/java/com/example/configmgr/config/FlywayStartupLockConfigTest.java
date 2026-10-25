package com.example.configmgr.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 启动锁装配只对 h2tcp / postgresql 生效；默认单实例上下文不出现任何锁 Bean。 */
class FlywayStartupLockConfigTest {

    @Test
    void defaultProfileKeepsSingleInstanceBehaviorUnchanged() {
        new ApplicationContextRunner()
                .withBean("dataSource", DataSource.class, () -> mock(DataSource.class))
                .withUserConfiguration(FlywayStartupLockConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FlywayStartupLock.class);
                    assertThat(context).doesNotHaveBean(FlywayConnectionPin.class);
                });
    }

    @Test
    void h2tcpProfileRegistersTheStartupLockBeans() {
        contextRunner("h2tcp").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FlywayStartupLock.class);
            assertThat(context).hasSingleBean(FlywayConnectionPin.class);
        });
    }

    @Test
    void postgresqlProfileRegistersTheStartupLockBeans() {
        contextRunner("postgresql").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FlywayStartupLock.class);
        });
    }

    private ApplicationContextRunner contextRunner(String profile) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                .withBean("dataSource", DataSource.class, () -> mock(DataSource.class))
                .withUserConfiguration(FlywayStartupLockConfig.class);
    }
}
