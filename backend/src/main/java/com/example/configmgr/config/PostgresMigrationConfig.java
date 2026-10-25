package com.example.configmgr.config;

import org.flywaydb.core.api.migration.JavaMigration;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;

/**
 * PostgreSQL profile 的 Flyway 迁移集装配（仅 {@code postgresql} profile 生效）。
 *
 * <h2>为什么需要这个类</h2>
 * H2 侧的子表外键迁移是 Java 迁移 {@code db.migration.V6__Task_child_fk}（H2 的 DDL 非事务、
 * 约束探查语法跨库不同，故用元数据判存在）。PG 侧对应 {@code db/postgresql/V6__task_child_fk.sql}
 * ——PG 的 DDL 是事务性的，"可重入"用一条 {@code DO} 块表达即可。
 *
 * <p>但 Flyway 的 Java 迁移是<b>按类路径 {@code db/migration} 包扫描</b>的，不受
 * {@code spring.flyway.locations} 约束：只要 PG profile 也把 {@code db/postgresql} 当迁移目录，
 * 类路径上那个 Java V6 就会被同时发现 ⇒ 同一个版本号两个迁移，Flyway 校验阶段直接失败。
 * 因此 PG profile 下把 Java 迁移提供者换成空集合：<b>PG 的迁移集完全由 db/postgresql 的 SQL 文件构成</b>。
 *
 * <h2>边界（后续加迁移须知）</h2>
 * 本 profile 生效时新加的 Java 迁移在 PG 上<b>不会执行</b>。PG 侧要落地同一变更，须在
 * {@code db/postgresql} 放一份同版本号的 SQL（方言副本），否则两库版本号会分叉。
 * {@code MigrationSetGuardTest} 对 V1~V7 的"两目录对齐"做了静态校验，新增迁移时同步扩表。
 */
@Configuration
@Profile("postgresql")
class PostgresMigrationConfig {

	@Bean
	FlywayConfigurationCustomizer postgresqlUsesSqlMigrationsOnly() {
		return configuration -> configuration.javaMigrationClassProvider(PostgresMigrationConfig::noJavaMigrations);
	}

	/** 空提供者：PG 的迁移只来自 {@code classpath:db/postgresql} 的 SQL 文件。 */
	private static List<Class<? extends JavaMigration>> noJavaMigrations() {
		return List.of();
	}

}
