package com.example.configmgr.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

/**
 * 多实例共享库 profile 的 Flyway 启动单飞装配（仅 {@code h2tcp} / {@code postgresql}）。
 *
 * <p>不定义第二个 {@code DataSource} Bean（那会让 Spring Boot 的 DataSourceAutoConfiguration
 * 在条件阶段退避，连主库都不建）。改用 {@link FlywayConfigurationCustomizer}：Spring Boot 先
 * 用主 DataSource 配置 Flyway，再应用 customizer 把 DataSource 换成
 * {@link FlywayConnectionPin.PinnedDataSource}；正常启动期外仍回落真实连接池。
 *
 * <p>默认 profile 不装配任何 Bean，单实例 H2 文件库保持原行为。
 * 本类不改 {@code PostgresMigrationConfig}（PG 只禁 Java 迁移、使用 db/postgresql 方言副本）。
 */
@Configuration
@Profile({ "h2tcp", "postgresql" })
class FlywayStartupLockConfig {

    @Bean
    FlywayConnectionPin flywayConnectionPin() {
        return new FlywayConnectionPin();
    }

    @Bean
    FlywayConfigurationCustomizer flywayStartupLockDataSource(
            @Qualifier("dataSource") DataSource primaryDataSource, FlywayConnectionPin pin) {
        FlywayConnectionPin.PinnedDataSource pinned = new FlywayConnectionPin.PinnedDataSource(primaryDataSource,
                pin);
        // 必须排在其他 customizer 之后执行也无妨：它们改的是 locations/Java 迁移，不碰 DataSource。
        return configuration -> configuration.dataSource(pinned);
    }

    @Bean
    FlywayStartupLock flywayStartupLock(@Qualifier("dataSource") DataSource primaryDataSource,
            FlywayConnectionPin pin) {
        return new FlywayStartupLock(primaryDataSource, pin);
    }

    @Bean
    FlywayMigrationStrategy flywayMigrationStrategy(FlywayStartupLock startupLock) {
        return startupLock::migrate;
    }
}
