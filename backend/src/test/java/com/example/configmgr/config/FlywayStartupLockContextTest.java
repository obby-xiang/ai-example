package com.example.configmgr.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Spring 启动面：h2tcp profile 下 {@code @FlywayDataSource} 包装 + FlywayMigrationStrategy 生效，
 * 且应用其余 DataSource 注入不被第二个 DataSource Bean 破坏。
 */
@SpringBootTest
@ActiveProfiles("h2tcp")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:migration-race-context;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.job.demo-batch-delay-ms=0",
        "app.ai.resume.on-startup=false",
        // h2tcp 属多实例 profile：会话发号要求显式实例位（单进程测试固定 0），否则上下文起不来。
        "app.instance-slot=0"
})
class FlywayStartupLockContextTest {

    private static final Path STORAGE_ROOT = createStorageRoot();

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("app.file.storage-path", STORAGE_ROOT::toString);
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Test
    void h2tcpContextStartsWithTheStartupLockAndAppliesV1ToV11Once() throws Exception {
        assertThat(flyway).isNotNull();
        assertThat(dataSource).isNotNull();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" IS NOT NULL AND \"success\" = TRUE")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(11);
        }
    }

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("migration-race-files-");
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
