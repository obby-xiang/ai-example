package com.example.configmgr.file;

import com.example.configmgr.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动面证据：<b>仅 h2tcp profile</b> + 相对 storage-path 时上下文必须起不来；
 * 默认 profile + 相对路径照常启动（单实例开发不受影响）。
 *
 * <p>这里真的走 Spring 绑定（{@code @EnableConfigurationProperties}），不是手工 new 一个
 * {@link AppProperties} —— 要证明的是配置面到启动期的整条链。
 */
class FileStorageMultiInstanceGuardStartupTest {

    @TempDir
    Path sharedRoot;

    @Test
    void h2tcpOnlyWithRelativeStoragePathFailsStartup() {
        contextRunner("h2tcp", "./data/files", null).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .as("h2tcp 意味着库已跨进程共享，文件路径不能再相对")
                .hasStackTraceContaining("绝对路径");
        });
    }

    @Test
    void explicitMultiInstanceSwitchWithoutProfileAlsoFailsStartup() {
        contextRunner(null, "./data/files", "true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("app.file.multi-instance=true");
        });
    }

    @Test
    void defaultProfileWithRelativeStoragePathStartsAndKeepsTheGuard() {
        contextRunner(null, "./data/files", null).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FileStorageMultiInstanceGuard.class);
        });
    }

    @Test
    void h2tcpDatasourceOnlyWithRelativeStoragePathFailsStartup() {
        new ApplicationContextRunner()
            .withUserConfiguration(GuardConfig.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:tcp://127.0.0.1:9092/./config_mgr",
                    "app.file.storage-path=./data/files")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .as("仅 H2 TCP（无任何 profile）也意味着库跨进程共享，文件必须共享")
                    .hasStackTraceContaining("jdbc:h2:tcp:")
                    .hasStackTraceContaining("绝对路径");
            });
    }

    @Test
    void h2FileDatasourceWithRelativeStoragePathStillStarts() {
        new ApplicationContextRunner()
            .withUserConfiguration(GuardConfig.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:file:./data/config_mgr_db",
                    "app.file.storage-path=./data/files")
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void h2tcpWithAbsoluteSharedStoragePathStarts() {
        new ApplicationContextRunner()
            .withUserConfiguration(GuardConfig.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:tcp://127.0.0.1:9092/./config_mgr",
                    "app.file.storage-path=" + this.sharedRoot.toAbsolutePath())
            .run(context -> assertThat(context).hasNotFailed());
    }

    private ApplicationContextRunner contextRunner(String profile, String storagePath, String multiInstance) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GuardConfig.class)
            .withPropertyValues("app.file.storage-path=" + storagePath);
        if (profile != null) {
            runner = runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profile));
        }
        if (multiInstance != null) {
            runner = runner.withPropertyValues("app.file.multi-instance=" + multiInstance);
        }
        return runner;
    }

    @Configuration
    @EnableConfigurationProperties(AppProperties.class)
    static class GuardConfig {

        @Bean
        FileStorageMultiInstanceGuard fileStorageMultiInstanceGuard(AppProperties appProperties,
                Environment environment) {
            return new FileStorageMultiInstanceGuard(appProperties, environment);
        }
    }
}
