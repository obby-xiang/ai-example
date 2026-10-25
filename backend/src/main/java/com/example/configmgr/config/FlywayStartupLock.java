package com.example.configmgr.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.springframework.beans.factory.annotation.Qualifier;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * Flyway <b>启动期</b>单飞：在 Flyway 创建/读取 {@code flyway_schema_history} 之前拿到跨 JVM 互斥。
 *
 * <h2>为什么不能在 Flyway 之后加锁</h2>
 * Flyway 11 的 {@code Flyway.migrate()} 在回调 {@code BEFORE_MIGRATE} 之前就已
 * {@code SchemaHistory.create(false)}（字节码：{@code Flyway.migrate → SchemaHistory.create → new DbMigrate}）。
 * 三实例同时冷启动时，两个实例都看到“历史表已存在且为空”，其中一个尝试 baseline 即报
 * {@code Unable to baseline schema history table ... already exists, and is empty}。
 * 故互斥必须包住整个 {@code migrate()}，不能放在 Flyway 回调或 SeedLock 里。
 *
 * <h2>方言与连接</h2>
 * <ul>
 * <li><b>PostgreSQL</b>：会话级 {@code pg_advisory_lock(bigint)}；锁连接与 Flyway 的
 * migrate <b>同一物理连接</b>（{@link FlywayConnectionPin} 钉住）；等待上界
 * {@code SET lock_timeout='60000ms'}。解锁失败/返回 false 一律上抛，并 abort 物理连接，
 * 绝不把仍带锁的池连接还回池。</li>
 * <li><b>H2 TCP</b>：H2 2.3.232/2.5.252 <b>都没有</b> MySQL 的 {@code GET_LOCK}/{@code RELEASE_LOCK}
 * （实机 {@code CALL GET_LOCK('x',1)} 返回 {@code Function "GET_LOCK" not found}；全量类检索也没有）。
 * H2 也没有“跨提交保持”的会话级锁（{@code SET EXCLUSIVE} 会拒绝新连接且不可中断）。
 * 这里在<b>业务库的独立 schema {@code FLYWAY_LOCK}</b> 建行锁：
 * {@code SET LOCK_TIMEOUT} + {@code SELECT ... FOR UPDATE}，有界等待，rollback/close 释放；
 * 锁表独立 schema 不会让 Flyway 把 PUBLIC 判成非空而 baseline。
 * <b>H2 侧锁连接是独立连接（不是 Flyway 的物理连接）</b>——这是 H2 缺少会话级命名锁的硬边界；
 * 迁移结束后用 holder token + {@code isValid} 复核锁仍属本次运行，锁连接中途失效则启动失败。</li>
 * </ul>
 *
 * <p>默认 profile 不装配本类（见 {@link FlywayStartupLockConfig}），单实例 H2 文件库行为不变。
 */
public class FlywayStartupLock {

    /** 单飞等待上限（两库同一口径；覆盖一轮冷启动迁移）。 */
    static final Duration WAIT_TIMEOUT = Duration.ofSeconds(60);

    /** PG advisory lock 的应用级键（本应用全局固定）。 */
    static final long PG_LOCK_KEY = 20261025L;

    /** H2 锁表放在业务库的独立 schema，避免污染 PUBLIC 的 baseline 判定。 */
    static final String H2_LOCK_SCHEMA = "FLYWAY_LOCK";

    static final String H2_LOCK_TABLE = H2_LOCK_SCHEMA + ".flyway_startup_lock";

    static final String H2_LOCK_ROW = "migrate";

    private final DataSource primaryDataSource;

    private final FlywayConnectionPin pin;

    private final Duration waitTimeout;

    public FlywayStartupLock(@Qualifier("dataSource") DataSource primaryDataSource, FlywayConnectionPin pin) {
        this(primaryDataSource, pin, WAIT_TIMEOUT);
    }

    FlywayStartupLock(DataSource primaryDataSource, FlywayConnectionPin pin, Duration waitTimeout) {
        this.primaryDataSource = primaryDataSource;
        this.pin = pin;
        this.waitTimeout = waitTimeout;
    }

    /**
     * 在启动锁内执行整段 Flyway migrate。
     *
     * <p>失败（锁等待超时/拿不到锁/SQL 异常/解锁失败）向上抛，启动失败；绝不把“没拿到锁/没解掉锁”当成功。
     */
    public void migrate(Flyway flyway) {
        Connection lockConnection = null;
        String dialect = null;
        boolean acquired = false;
        Throwable failure = null;
        try {
            lockConnection = this.primaryDataSource.getConnection();
            dialect = dialectOf(lockConnection.getMetaData().getURL());
            if ("h2".equals(dialect)) {
                String token = "h2-" + UUID.randomUUID();
                acquireH2RowLock(lockConnection, token);
                acquired = true;
                flyway.migrate();
                verifyH2LockStillHeld(lockConnection, token);
            }
            else {
                acquirePostgres(lockConnection, this.waitTimeout);
                acquired = true;
                try (AutoCloseable pinned = this.pin.pin(lockConnection)) {
                    flyway.migrate();
                }
            }
        }
        catch (Exception e) {
            failure = e;
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new FlywayException("Flyway 启动锁失败", e);
        }
        catch (Error e) {
            failure = e;
            throw e;
        }
        finally {
            Exception releaseFailure = null;
            if (lockConnection != null && acquired) {
                try {
                    if ("h2".equals(dialect)) {
                        releaseH2(lockConnection);
                    }
                    else {
                        releasePostgres(lockConnection);
                    }
                }
                catch (Exception e) {
                    releaseFailure = e;
                    abortQuietly(lockConnection);
                }
            }
            if (lockConnection != null) {
                try {
                    lockConnection.close();
                }
                catch (SQLException closeFailure) {
                    if (releaseFailure == null) {
                        releaseFailure = closeFailure;
                    }
                    else {
                        releaseFailure.addSuppressed(closeFailure);
                    }
                }
            }
            if (releaseFailure != null) {
                if (failure != null) {
                    failure.addSuppressed(releaseFailure);
                }
                else {
                    throw new FlywayException("Flyway 启动锁释放失败（已 abort 物理连接）", releaseFailure);
                }
            }
        }
    }

    /** H2：业务库独立 schema 的行锁；有界等待，holder token 用于迁移后复核。 */
    private void acquireH2RowLock(Connection connection, String token) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCK_TIMEOUT " + this.waitTimeout.toMillis());
            try {
                statement.execute("CREATE SCHEMA IF NOT EXISTS " + H2_LOCK_SCHEMA);
            }
            catch (SQLException e) {
                if (!isAlreadyExists(e)) {
                    throw e;
                }
            }
            try {
                statement.execute("CREATE TABLE IF NOT EXISTS " + H2_LOCK_TABLE
                        + " (lock_name VARCHAR(64) PRIMARY KEY, holder VARCHAR(128))");
            }
            catch (SQLException e) {
                if (!isAlreadyExists(e)) {
                    throw e;
                }
            }
            try {
                statement.execute("INSERT INTO " + H2_LOCK_TABLE + " (lock_name) SELECT '" + H2_LOCK_ROW
                        + "' WHERE NOT EXISTS (SELECT 1 FROM " + H2_LOCK_TABLE + " WHERE lock_name = '"
                        + H2_LOCK_ROW + "')");
            }
            catch (SQLException e) {
                if (!isAlreadyExists(e)) {
                    throw e;
                }
            }
        }
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT lock_name FROM " + H2_LOCK_TABLE + " WHERE lock_name = ? FOR UPDATE")) {
            statement.setString(1, H2_LOCK_ROW);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("H2 Flyway 启动锁行缺失：" + H2_LOCK_TABLE + "." + H2_LOCK_ROW);
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + H2_LOCK_TABLE + " SET holder = ? WHERE lock_name = ?")) {
            statement.setString(1, token);
            statement.setString(2, H2_LOCK_ROW);
            statement.executeUpdate();
        }
    }

    /** 迁移完成后复核：锁连接仍存活，且 holder 仍是本次 token；否则 fail-closed。 */
    static void verifyH2LockStillHeld(Connection connection, String token) throws SQLException {
        if (!connection.isValid(2)) {
            throw new SQLException("H2 Flyway 启动锁连接已失效（迁移期间中断）");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT holder FROM " + H2_LOCK_TABLE + " WHERE lock_name = ?")) {
            statement.setString(1, H2_LOCK_ROW);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next() || !token.equals(rs.getString(1))) {
                    throw new SQLException("H2 Flyway 启动锁已丢失或被接管（holder 不匹配）");
                }
            }
        }
    }

    private static void releaseH2(Connection connection) throws SQLException {
        // 回滚释放行锁；失败由上抛，调用方会 abort 物理连接。
        connection.rollback();
    }

    private static void acquirePostgres(Connection connection, Duration timeout) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET lock_timeout = '" + timeout.toMillis() + "ms'");
            statement.execute("SELECT pg_advisory_lock(" + PG_LOCK_KEY + ")");
        }
    }

    /** 解锁必须成功；返回 false / SQL 异常一律上抛（由调用方 abort 物理连接）。 */
    private static void releasePostgres(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT pg_advisory_unlock(" + PG_LOCK_KEY + ")")) {
            if (!rs.next() || !rs.getBoolean(1)) {
                throw new SQLException("pg_advisory_unlock 返回 false：会话锁未释放");
            }
        }
    }

    private static void abortQuietly(Connection connection) {
        try {
            connection.abort(Runnable::run);
        }
        catch (Exception ignored) {
            // abort 失败仍有 close/socket 断开兜底；原始释放异常已上抛。
        }
    }

    private static boolean isAlreadyExists(SQLException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("already exists")
                || "23505".equals(e.getSQLState())
                || message.contains("primary key violation")
                || message.contains("unique index");
    }

    static String dialectOf(String url) {
        String normalized = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("jdbc:h2:")) {
            return "h2";
        }
        if (normalized.startsWith("jdbc:postgresql:")) {
            return "postgresql";
        }
        throw new IllegalStateException("Flyway 启动锁只支持 H2 / PostgreSQL，当前 URL=" + url);
    }
}
