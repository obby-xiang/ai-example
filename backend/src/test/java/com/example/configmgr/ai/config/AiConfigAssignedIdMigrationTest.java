package com.example.configmgr.ai.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiConfigAssignedIdMigrationTest {

    @Test
    void upgradeRemovesIdentityGenerationAndKeepsExistingIds() {
        DataSource dataSource = freshDatabaseMigratedTo2();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLegacyRows(jdbc);
        List<Long> legacyIds = jdbc.queryForList("SELECT id FROM ai_provider ORDER BY id", Long.class);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        assertThat(legacyIds).isNotEmpty();
        assertThat(jdbc.queryForList("SELECT id FROM ai_provider ORDER BY id", Long.class))
                .isEqualTo(legacyIds);
        for (String table : List.of("AI_PROVIDER", "AI_MODEL", "AI_CONFIG_AUDIT")) {
            Boolean identity = jdbc.queryForObject("SELECT IS_IDENTITY FROM INFORMATION_SCHEMA.COLUMNS "
                    + "WHERE TABLE_NAME = ? AND COLUMN_NAME = 'ID'", Boolean.class, table);
            assertThat(identity).as(table + " must no longer generate ids").isFalse();
        }

        jdbc.update("INSERT INTO ai_provider (id, code, display_name, base_url) VALUES (?, ?, ?, ?)",
                9001L, "assigned-after-upgrade", "Assigned", "https://api.deepseek.com");
        jdbc.update("INSERT INTO ai_model (id, provider_id, model_id) VALUES (?, ?, ?)",
                9002L, 9001L, "assigned-model");
        jdbc.update("INSERT INTO ai_config_audit (id, action, target_type) VALUES (?, ?, ?)",
                9003L, "PROVIDER_CREATE", "provider");
        assertThat(jdbc.queryForObject("SELECT id FROM ai_provider WHERE code = 'assigned-after-upgrade'",
                Long.class)).isEqualTo(9001L);
        assertThat(jdbc.queryForObject("SELECT provider_id FROM ai_model WHERE id = 9002", Long.class))
                .isEqualTo(9001L);
        assertThat(jdbc.queryForObject("SELECT id FROM ai_config_audit WHERE id = 9003", Long.class))
                .isEqualTo(9003L);
    }

    private static DataSource freshDatabaseMigratedTo2() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:ai-config-assigned-id-migration;DB_CLOSE_DELAY=-1", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("2"))
                .load()
                .migrate();
        return dataSource;
    }

    private static void seedLegacyRows(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO ai_provider (code, display_name, base_url) VALUES (?, ?, ?)",
                "legacy", "Legacy", "https://api.deepseek.com");
        Long providerId = jdbc.queryForObject("SELECT id FROM ai_provider WHERE code = 'legacy'", Long.class);
        jdbc.update("INSERT INTO ai_model (provider_id, model_id) VALUES (?, ?)", providerId, "legacy-model");
        jdbc.update("INSERT INTO ai_config_audit (action, target_type, target_id) VALUES (?, ?, ?)",
                "PROVIDER_CREATE", "provider", providerId);
    }
}
