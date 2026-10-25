package com.example.configmgr.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两个新增 profile 的**配置契约**（本机可确定的部分；真实连接留 CI/环境级验证）。
 *
 * <ul>
 * <li>{@code postgresql}：数据源来自环境且**没有默认值**（漏配即 fail-fast，不回落到 H2 文件库）、
 * 迁移目录只指向 {@code db/postgresql}、关掉 baseline 与 H2 控制台、开启优雅停机；</li>
 * <li>{@code h2tcp}：数据源必须是 {@code jdbc:h2:tcp://} 共享库 —— **禁止** {@code jdbc:h2:file:}
 * （H2 文件库同刻只允许一个进程打开，多实例等于"每 JVM 一个库"）。</li>
 * </ul>
 *
 * <p>这里解析 YAML 并按 Hikari 的真实类型绑定（不启动上下文、不连库），所以 CI 与本机都能跑。
 */
class ProfileConfigContractTest {

	@Test
	void postgresqlProfileRequiresEnvUrlAndPgMigrations() throws Exception {
		PropertySource<?> pg = load("application-postgresql.yml");

		assertThat(pg.getProperty("spring.config.activate.on-profile")).isEqualTo("postgresql");
		assertThat(String.valueOf(pg.getProperty("spring.datasource.url")))
				.as("必须由环境注入（无默认值）：漏配时占位符解析失败，而不是静默连本机 H2")
				.isEqualTo("${APP_DB_URL}");
		assertThat(pg.getProperty("spring.datasource.driver-class-name")).isEqualTo("org.postgresql.Driver");
		assertThat(pg.getProperty("spring.flyway.locations"))
				.as("PG 只能用方言副本目录（与 H2 目录同版本号会冲突）")
				.isEqualTo("classpath:db/postgresql");
		assertThat(pg.getProperty("spring.flyway.baseline-on-migrate"))
				.as("生产库必须空库交给 Flyway，禁止静默 baseline")
				.isEqualTo(false);
		assertThat(pg.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
		assertThat(pg.getProperty("spring.h2.console.enabled")).isEqualTo(false);
		assertThat(pg.getProperty("server.shutdown"))
				.as("readiness 摘除后的优雅停机")
				.isEqualTo("graceful");
	}

	@Test
	void postgresqlProfileBoundsLockWaitToSixtySeconds() throws Exception {
		PropertySource<?> pg = load("application-postgresql.yml");

		assertThat(String.valueOf(pg.getProperty("spring.datasource.hikari.connection-init-sql")))
				.as("PG 的锁等待必须与 H2 的 LOCK_TIMEOUT=60000 同口径（60s）：种子单飞的等待方要能"
						+ "等完整一轮播种；没有这把会话级上限，PG 缺省 lock_timeout=0 会无限等待")
				.isEqualTo("SET lock_timeout = '60s'");
	}

	@Test
	void h2tcpProfileUsesSharedTcpDatabaseNeverPerJvmFile() throws Exception {
		PropertySource<?> h2tcp = load("application-h2tcp.yml");

		assertThat(h2tcp.getProperty("spring.config.activate.on-profile")).isEqualTo("h2tcp");
		String url = String.valueOf(h2tcp.getProperty("spring.datasource.url"));
		assertThat(url).startsWith("jdbc:h2:tcp://");
		assertThat(url).as("多实例必须共用 H2 TCP 服务，禁止每 JVM 一个文件库")
				.doesNotContain("jdbc:h2:file:");
		assertThat(url).as("等待方要能等完整一轮播种（H2 缺省锁等待 1s 会先超时）")
				.contains("LOCK_TIMEOUT=");
	}

	@Test
	void defaultWebTrustBoundaryIsEmpty() throws Exception {
		PropertySource<?> defaults = load("application.yml");

		assertThat(defaults.getProperty("app.web.trusted-proxies"))
				.as("默认不信任任何转发头（直连形态），与改造前一致")
				.isNotNull();
	}

	@Test
	void hikariTimeoutValuesAreMilliseconds() throws Exception {
		for (String profile : List.of("h2tcp", "postgresql")) {
			List<PropertySource<?>> sources = new YamlPropertySourceLoader()
					.load(profile, new ClassPathResource("application-" + profile + ".yml"));
			com.zaxxer.hikari.HikariConfig bound = new org.springframework.boot.context.properties.bind.Binder(
					org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(sources))
					.bind("spring.datasource.hikari", com.zaxxer.hikari.HikariConfig.class).get();
			assertThat(bound.getConnectionTimeout()).as("%s 的连接超时应为 5000ms", profile).isEqualTo(5000L);
			if (profile.equals("postgresql")) {
				assertThat(bound.getValidationTimeout()).isEqualTo(2000L);
			}
		}
	}

	private static PropertySource<?> load(String resource) throws Exception {
		List<PropertySource<?>> sources = new YamlPropertySourceLoader()
				.load(resource, new ClassPathResource(resource));
		assertThat(sources).as("%s 应能解析出至少一个文档", resource).isNotEmpty();
		return sources.get(0);
	}

}
