package db.migration;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * R1-13 / Important 2：子表外键（ON DELETE CASCADE），可重入版本。
 *
 * <p>为什么用 Java 迁移而不是裸 SQL：H2 与 MySQL 的“约束是否已存在/DROP CONSTRAINT”语法不同；
 * 裸 {@code ADD CONSTRAINT} 在部分失败后会留下“约束已建、Flyway 版本未记”的半完成态。
 * 这里逐约束按数据库元数据判存在，已存在则跳过，因此重复执行/失败重跑安全。
 *
 * <p><b>前置检查（只报错，不删数据）</b>：发现任一子表孤儿行时抛出 {@link FlywayException}，
 * 迁移失败并提示人工裁决；本类绝不执行 DELETE/UPDATE。
 *
 * <p><b>上线前核查 SQL</b>（各环境先跑，确认均为 0；有值先人工处理）：
 * <pre>{@code
 * SELECT 'task_items' AS table_name, COUNT(*) AS orphans FROM task_items i
 *   WHERE NOT EXISTS (SELECT 1 FROM tasks t WHERE t.id = i.task_id)
 * UNION ALL SELECT 'task_files', COUNT(*) FROM task_files i
 *   WHERE NOT EXISTS (SELECT 1 FROM tasks t WHERE t.id = i.task_id)
 * UNION ALL SELECT 'config_staging_rows', COUNT(*) FROM config_staging_rows i
 *   WHERE NOT EXISTS (SELECT 1 FROM tasks t WHERE t.id = i.task_id)
 * UNION ALL SELECT 'job_items', COUNT(*) FROM job_items i
 *   WHERE NOT EXISTS (SELECT 1 FROM jobs j WHERE j.id = i.job_id)
 * UNION ALL SELECT 'validation_issues', COUNT(*) FROM validation_issues i
 *   WHERE NOT EXISTS (SELECT 1 FROM jobs j WHERE j.id = i.job_id);
 * }</pre>
 *
 * <p>不对 {@code jobs.task_id} 加 FK：现存历史库有 1 条 task_id=99999 的 FAILED 孤儿作业，
 * 按“不得清理任何现存数据”的约束不先删它；API 建作业/删除的互斥由任务行锁负责。
 */
public class V6__Task_child_fk extends BaseJavaMigration {

    private record ForeignKey(String name, String table, String column, String refTable, String refColumn) {
    }

    private record OrphanCheck(String table, String where) {
    }

    private static final List<ForeignKey> FOREIGN_KEYS = List.of(
            new ForeignKey("fk_task_items_task", "task_items", "task_id", "tasks", "id"),
            new ForeignKey("fk_task_files_task", "task_files", "task_id", "tasks", "id"),
            new ForeignKey("fk_config_staging_rows_task", "config_staging_rows", "task_id", "tasks", "id"),
            new ForeignKey("fk_job_items_job", "job_items", "job_id", "jobs", "id"),
            new ForeignKey("fk_validation_issues_job", "validation_issues", "job_id", "jobs", "id"));

    private static final List<OrphanCheck> ORPHAN_CHECKS = List.of(
            new OrphanCheck("task_items",
                    "NOT EXISTS (SELECT 1 FROM tasks t WHERE t.id = task_items.task_id)"),
            new OrphanCheck("task_files",
                    "NOT EXISTS (SELECT 1 FROM tasks t WHERE t.id = task_files.task_id)"),
            new OrphanCheck("config_staging_rows",
                    "NOT EXISTS (SELECT 1 FROM tasks t WHERE t.id = config_staging_rows.task_id)"),
            new OrphanCheck("job_items",
                    "NOT EXISTS (SELECT 1 FROM jobs j WHERE j.id = job_items.job_id)"),
            new OrphanCheck("validation_issues",
                    "NOT EXISTS (SELECT 1 FROM jobs j WHERE j.id = validation_issues.job_id)"));

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        assertNoOrphans(connection);
        for (ForeignKey fk : FOREIGN_KEYS) {
            if (foreignKeyExists(connection, fk)) {
                continue;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + fk.table()
                        + " ADD CONSTRAINT " + fk.name()
                        + " FOREIGN KEY (" + fk.column() + ") REFERENCES "
                        + fk.refTable() + " (" + fk.refColumn() + ") ON DELETE CASCADE");
            }
        }
    }

    private void assertNoOrphans(Connection connection) throws SQLException {
        List<String> orphans = new ArrayList<>();
        for (OrphanCheck check : ORPHAN_CHECKS) {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT COUNT(*) FROM " + check.table() + " WHERE " + check.where())) {
                rs.next();
                long count = rs.getLong(1);
                if (count > 0) {
                    orphans.add(check.table() + "=" + count);
                }
            }
        }
        if (!orphans.isEmpty()) {
            throw new FlywayException("V6 前置检查失败：子表存在孤儿行 " + orphans
                    + "。迁移不会删除/修改任何数据；请人工裁决清理或归属后重跑。"
                    + "核查 SQL 见 V6__Task_child_fk 类注释。");
        }
    }

    private boolean foreignKeyExists(Connection connection, ForeignKey fk) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getImportedKeys(connection.getCatalog(), connection.getSchema(), fk.table())) {
            while (rs.next()) {
                String name = rs.getString("FK_NAME");
                if (name != null && name.equalsIgnoreCase(fk.name())) {
                    return true;
                }
            }
        }
        // 元数据在某些驱动/大小写配置下拿不到时，用 INFORMATION_SCHEMA 兜底（H2/MySQL 都有）。
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE UPPER(TABLE_NAME) = UPPER(?) AND UPPER(CONSTRAINT_NAME) = UPPER(?)")) {
            ps.setString(1, fk.table());
            ps.setString(2, fk.name());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) > 0;
            }
        }
    }
}
