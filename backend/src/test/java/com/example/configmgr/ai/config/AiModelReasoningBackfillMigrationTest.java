package com.example.configmgr.ai.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>L-104</b>：存量库的 {@code ai_model.supports_reasoning} 必须随升级自动回填（Flyway V5）。
 *
 * <p>
 * 缺陷形态（2026-10-23）：L-91 只把 <b>种子行</b>置了 {@code true}（{@link AiConfigService#envSeedModel()}），
 * 而种子只在 {@code ai_provider} 空表时写 —— <b>既有 DB 的 ENV_SEED 模型行永远不会被刷新</b>，
 * 老库升级后该能力位仍为 false（模型选择器继续显示"不支持推理"）。
 *
 * <p>
 * 迁移口径（安全边界，三重 WHERE）：
 * <ul>
 * <li>只动 {@code source='ENV_SEED'} 的供应商行下的模型 —— 用户在 UI 改过的 provider（source 翻成
 * {@code DB}）下的模型行一律不碰；</li>
 * <li>只动 {@code deepseek%} 模型行 —— 与本机实证返回 {@code reasoning_content} 的模型族一致；</li>
 * <li>只做 {@code false/NULL → true}，从不反转已是 true 的行 ⇒ 幂等、可重复执行。</li>
 * </ul>
 *
 * <p><b>证红</b>：删掉 {@code V5__ai_model_reasoning_backfill.sql} ⇒ 本类三条用例全红
 * （历史表无 V5、存量行仍 false）。<b>证幂等</b>：同一脚本连跑两次，值集合不变。
 */
class AiModelReasoningBackfillMigrationTest {

    private static final String ENV_SEED_PROVIDER = "deepseek";
    private static final String USER_PROVIDER = "custom-1";

    @Test
    void upgradeBackfillsLegacyEnvSeedDeepseekRows() {
        DataSource dataSource = freshDatabaseMigratedTo("4");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLegacyRows(jdbc);

        migrateToLatest(dataSource);

        assertThat(modelFlags(jdbc, ENV_SEED_PROVIDER))
                .as("ENV_SEED 供应商下的 deepseek 系模型行：false/NULL 必须回填成 true")
                .containsEntry("deepseek-flash", true)
                .containsEntry("deepseek-reasoner", true)
                .containsEntry("deepseek-chat", true);
    }

    @Test
    void userOwnedAndNonDeepseekRowsAreLeftAlone() {
        DataSource dataSource = freshDatabaseMigratedTo("4");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLegacyRows(jdbc);

        migrateToLatest(dataSource);

        assertThat(modelFlags(jdbc, USER_PROVIDER))
                .as("用户自建 / 改过的 provider（source=DB）下的模型行不得被迁移改写")
                .containsEntry("deepseek-flash", false);
        assertThat(modelFlags(jdbc, ENV_SEED_PROVIDER))
                .as("非 deepseek 系的模型行不臆断能力位")
                .containsEntry("local-llm", false);
    }

    @Test
    void migrationIsIdempotent() throws Exception {
        DataSource dataSource = freshDatabaseMigratedTo("4");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLegacyRows(jdbc);
        migrateToLatest(dataSource);
        Map<String, Boolean> afterFirst = modelFlags(jdbc, ENV_SEED_PROVIDER);

        ClassPathResource script = new ClassPathResource("db/migration/V5__ai_model_reasoning_backfill.sql");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, script);
            ScriptUtils.executeSqlScript(connection, script);
        }

        assertThat(modelFlags(jdbc, ENV_SEED_PROVIDER))
                .as("重复执行迁移 SQL 不得改变结果（只 false/NULL → true）")
                .isEqualTo(afterFirst);
        assertThat(latestFlyway(dataSource).info().applied())
                .as("V5 必须已被 Flyway 应用（且只有一条）")
                .filteredOn(info -> "5".equals(info.getVersion() == null ? null : info.getVersion().getVersion()))
                .hasSize(1);
    }

    private static DataSource freshDatabaseMigratedTo(String target) {
        String url = "jdbc:h2:mem:reasoning-backfill-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(target))
                .load()
                .migrate();
        return dataSource;
    }

    private static void migrateToLatest(DataSource dataSource) {
        latestFlyway(dataSource).migrate();
    }

    private static Flyway latestFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.LATEST)
                .load();
    }

    /** 制造"L-91 之前就存在"的存量库：种子行与用户行混在一起，能力位全是 false/NULL。 */
    private static void seedLegacyRows(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO ai_provider (code, display_name, base_url, key_ref, source)"
                + " VALUES (?, ?, ?, ?, ?)",
                ENV_SEED_PROVIDER, "DeepSeek", "http://seed-endpoint:18399", "ENV", "ENV_SEED");
        Long seedProviderId = jdbc.queryForObject(
                "SELECT id FROM ai_provider WHERE code = ?", Long.class, ENV_SEED_PROVIDER);

        jdbc.update("INSERT INTO ai_provider (code, display_name, base_url, key_ref, source)"
                + " VALUES (?, ?, ?, ?, ?)",
                USER_PROVIDER, "Custom", "https://api.custom:8443", "DB", "DB");
        Long userProviderId = jdbc.queryForObject(
                "SELECT id FROM ai_provider WHERE code = ?", Long.class, USER_PROVIDER);

        insertModel(jdbc, seedProviderId, "deepseek-flash", "FALSE", true);
        insertModel(jdbc, seedProviderId, "deepseek-reasoner", null, false);
        insertModel(jdbc, seedProviderId, "deepseek-chat", "TRUE", false);
        insertModel(jdbc, seedProviderId, "local-llm", "FALSE", false);
        insertModel(jdbc, userProviderId, "deepseek-flash", "FALSE", true);
    }

    private static void insertModel(JdbcTemplate jdbc, Long providerId, String modelId, String reasoning,
            boolean isDefault) {
        jdbc.update("INSERT INTO ai_model (provider_id, model_id, supports_tool_calling, supports_reasoning,"
                + " enabled, is_default, sort_order) VALUES (?, ?, TRUE, " + reasoning + ", TRUE, ?, 0)",
                providerId, modelId, isDefault);
    }

    private static Map<String, Boolean> modelFlags(JdbcTemplate jdbc, String providerCode) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT m.model_id, m.supports_reasoning FROM ai_model m"
                        + " JOIN ai_provider p ON p.id = m.provider_id WHERE p.code = ?",
                providerCode);
        Map<String, Boolean> flags = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            flags.put((String) row.get("model_id"), (Boolean) row.get("supports_reasoning"));
        }
        return flags;
    }
}
