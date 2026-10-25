package com.example.configmgr.task;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R1-13 / I-2：V6 子表 FK 迁移的可重入性与孤儿预检。
 *
 * <p>用独立 H2 内存库跑 Flyway 全量迁移，不改仓库 data 目录。
 */
class TaskChildFkMigrationTest {

    @Test
    void migrationIsReentrantWhenV6HistoryRowIsLost() throws Exception {
        String url = dbUrl("reentrant");
        Flyway flyway = flyway(url);
        flyway.migrate();

        // 模拟“约束已建、版本未记”的半完成态：只删掉 V6 的历史行后重跑。
        // V7 之后（app_lock / 完整历史表）V6 缺失而更高版本已应用属于**测试里的预期乱序**：
        // 生产仍严格顺序，这里只对测试实例开 outOfOrder（见 flyway(url)）。
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM \"flyway_schema_history\" WHERE \"version\" = '6'");
        }
        flyway.migrate();

        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                             + "WHERE CONSTRAINT_TYPE = 'FOREIGN KEY' "
                             + "AND CONSTRAINT_NAME IN ('FK_TASK_ITEMS_TASK','FK_TASK_FILES_TASK',"
                             + "'FK_CONFIG_STAGING_ROWS_TASK','FK_JOB_ITEMS_JOB','FK_VALIDATION_ISSUES_JOB')")) {
            rs.next();
            assertThat(rs.getLong(1)).as("五个子表 FK 应存在且重跑不报 already exists").isEqualTo(5);
        }
    }

    @Test
    void orphanPrecheckAbortsWithoutDeletingExistingData() throws Exception {
        String url = dbUrl("orphan");
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("5"))
                .load()
                .migrate();

        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO task_files(task_id, def_code, file_type, storage_path) "
                    + "VALUES (999999, 'R113_ORPHAN', 'EXPORT', './target/orphan.xlsx')");
        }

        assertThatThrownBy(() -> flyway(url).migrate())
                .isInstanceOf(FlywayException.class)
                .satisfies(e -> assertThat(messages(e))
                        .contains("task_files")
                        .contains("不会删除")
                        .contains("人工裁决"));

        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT COUNT(*) FROM task_files WHERE task_id = 999999")) {
            rs.next();
            assertThat(rs.getLong(1)).as("预检失败不得删除孤儿数据").isEqualTo(1);
        }
    }

    private Flyway flyway(String url) {
        return Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                // 本用例模拟“V6 约束已建、版本历史行丢失”。V7 之后（app_lock / 完整历史表），
                // V6 缺失而后续版本已应用是<b>预期中的乱序</b>；outOfOrder 只让测试里的 V6 重跑
                // （V6 自身幂等），生产 application.yml 的 Flyway 仍保持严格顺序校验。
                .outOfOrder(true)
                .load();
    }

    private String dbUrl(String name) {
        return "jdbc:h2:mem:r113-v6-" + name + "-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
    }

    private String messages(Throwable error) {
        StringBuilder sb = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                sb.append(current.getMessage()).append('\n');
            }
        }
        return sb.toString();
    }
}
