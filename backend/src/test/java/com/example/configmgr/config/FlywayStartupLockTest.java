package com.example.configmgr.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flyway 冷启动单飞：真实共享 H2（mem 多连接）+ 确定性锁竞争 + PG 精确替身。
 *
 * <p>本类验证的是实际实现语义：PG 锁与 migrate 同物理连接（pin）；H2 锁在业务库独立 schema，
 * 锁连接是独立连接（H2 无跨提交保持的会话级锁），因此 H2 侧重点验证“确实等待/有界失败/异常释放/
 * 迁移后锁仍属本 token”，不宣称 H2 同连接。
 */
class FlywayStartupLockTest {

    private static final String LOCATIONS = "classpath:db/migration";

    @Test
    void secondMigratorWaitsAtTheLockThenOnlyOneMigrationSequenceRuns() throws Exception {
        String url = "jdbc:h2:mem:flyway-contend-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        DataSource primary = dataSource(url);
        FlywayConnectionPin pin = new FlywayConnectionPin();
        DataSource pinned = new FlywayConnectionPin.PinnedDataSource(primary, pin);
        FlywayStartupLock lock = new FlywayStartupLock(primary, pin);

        CountDownLatch enteredMigration = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        Flyway holder = org.mockito.Mockito.mock(Flyway.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            enteredMigration.countDown();
            releaseHolder.await(10, TimeUnit.SECONDS);
            return null;
        }).when(holder).migrate();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> {
                lock.migrate(holder);
                return null;
            });
            assertThat(enteredMigration.await(5, TimeUnit.SECONDS)).as("holder 已进入 migrate 并持锁").isTrue();

            CountDownLatch secondStarted = new CountDownLatch(1);
            Future<?> second = pool.submit(() -> {
                secondStarted.countDown();
                lock.migrate(flyway(pinned));
                return null;
            });
            assertThat(secondStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(300);
            assertThat(second).as("holder 未释放前，第二个 migrator 必须被锁挡住").isNotDone();

            releaseHolder.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        }
        finally {
            releaseHolder.countDown();
            pool.shutdownNow();
        }

        assertThat(historyVersions(primary)).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
        assertThat(historyCount(primary)).isEqualTo(9);
        assertThat(historyTableCount(primary)).isEqualTo(1);
    }

    @Test
    void boundedWaitTimeoutFailsClosedInsteadOfMigratingConcurrently() throws Exception {
        String url = "jdbc:h2:mem:flyway-timeout-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        DataSource primary = dataSource(url);
        FlywayConnectionPin pin = new FlywayConnectionPin();
        FlywayStartupLock holderLock = new FlywayStartupLock(primary, pin);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Flyway holder = org.mockito.Mockito.mock(Flyway.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(holder).migrate();

        ExecutorService pool = Executors.newFixedThreadPool(1);
        Future<?> first = pool.submit(() -> {
            holderLock.migrate(holder);
            return null;
        });
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            FlywayStartupLock shortWait = new FlywayStartupLock(primary, pin, Duration.ofMillis(300));

            Flyway mustNotRun = org.mockito.Mockito.mock(Flyway.class);
            long started = System.nanoTime();
            assertThatThrownBy(() -> shortWait.migrate(mustNotRun))
                    .as("等锁超过上界必须启动失败，不能继续 migrate")
                    .isInstanceOf(FlywayException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
            org.mockito.Mockito.verifyNoInteractions(mustNotRun);
        }
        finally {
            release.countDown();
            first.get(30, TimeUnit.SECONDS);
            pool.shutdownNow();
        }
    }

    @Test
    void migrateExceptionReleasesTheH2Lock() throws Exception {
        String url = "jdbc:h2:mem:flyway-ex-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        DataSource primary = dataSource(url);
        FlywayConnectionPin pin = new FlywayConnectionPin();
        FlywayStartupLock lock = new FlywayStartupLock(primary, pin);
        Flyway failing = org.mockito.Mockito.mock(Flyway.class);
        org.mockito.Mockito.doThrow(new FlywayException("boom")).when(failing).migrate();

        assertThatThrownBy(() -> lock.migrate(failing)).isInstanceOf(FlywayException.class);

        // 锁必须已释放：短等待（2s）的第二次启动应立即成功并真正迁移，而不是等 60s。
        FlywayStartupLock next = new FlywayStartupLock(primary, pin, Duration.ofSeconds(2));
        next.migrate(flyway(primary));
        assertThat(historyVersions(primary)).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
    }


    @Test
    void postgresAcquiresAdvisoryLockWithBoundedWaitOnThePinnedConnection() throws Exception {
        PgMock pg = pgMock(true);
        FlywayConnectionPin pin = new FlywayConnectionPin();
        Flyway flyway = org.mockito.Mockito.mock(Flyway.class);

        new FlywayStartupLock(pg.dataSource(), pin).migrate(flyway);

        org.mockito.Mockito.verify(pg.statement()).execute("SET lock_timeout = '60000ms'");
        org.mockito.Mockito.verify(pg.statement())
                .execute("SELECT pg_advisory_lock(" + FlywayStartupLock.PG_LOCK_KEY + ")");
        org.mockito.Mockito.verify(flyway).migrate();
    }

    @Test
    void postgresMigrateExceptionStillUnlocksAndPropagates() throws Exception {
        PgMock pg = pgMock(true);
        Flyway failing = org.mockito.Mockito.mock(Flyway.class);
        org.mockito.Mockito.doThrow(new FlywayException("boom")).when(failing).migrate();

        assertThatThrownBy(() -> new FlywayStartupLock(pg.dataSource(), new FlywayConnectionPin()).migrate(failing))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("boom");
        org.mockito.Mockito.verify(pg.statement())
                .executeQuery("SELECT pg_advisory_unlock(" + FlywayStartupLock.PG_LOCK_KEY + ")");
        org.mockito.Mockito.verify(pg.connection()).close();
    }

    @Test
    void postgresUnlockFalseIsPropagatedAndPhysicalConnectionIsAborted() throws Exception {
        PgMock pg = pgMock(false);

        assertThatThrownBy(() -> new FlywayStartupLock(pg.dataSource(), new FlywayConnectionPin())
                .migrate(org.mockito.Mockito.mock(Flyway.class)))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("释放失败");
        org.mockito.Mockito.verify(pg.connection()).abort(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(pg.connection()).close();
    }

    @Test
    void postgresUnlockSqlExceptionIsPropagatedAndConnectionAborted() throws Exception {
        PgMock pg = pgMock(true);
        org.mockito.Mockito.when(pg.statement()
                .executeQuery("SELECT pg_advisory_unlock(" + FlywayStartupLock.PG_LOCK_KEY + ")"))
                .thenThrow(new SQLException("25P02 current transaction is aborted"));

        assertThatThrownBy(() -> new FlywayStartupLock(pg.dataSource(), new FlywayConnectionPin())
                .migrate(org.mockito.Mockito.mock(Flyway.class)))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("释放失败");
        org.mockito.Mockito.verify(pg.connection()).abort(org.mockito.ArgumentMatchers.any());
    }


    @Test
    void postgresBoundedWaitUsesTheConfiguredTimeout() throws Exception {
        PgMock pg = pgMock(true);
        new FlywayStartupLock(pg.dataSource(), new FlywayConnectionPin(), Duration.ofMillis(500))
                .migrate(org.mockito.Mockito.mock(Flyway.class));

        org.mockito.Mockito.verify(pg.statement()).execute("SET lock_timeout = '500ms'");
    }

    @Test
    void sameConnectionPinningIsAssertedFromInsideFlywayMigrate() throws Exception {
        PgMock pg = pgMock(true);
        FlywayConnectionPin pin = new FlywayConnectionPin();
        Flyway flyway = org.mockito.Mockito.mock(Flyway.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(pin.current()).as("PG 锁连接必须被 pin 给 Flyway 的 migrate").isSameAs(pg.connection());
            return null;
        }).when(flyway).migrate();

        new FlywayStartupLock(pg.dataSource(), pin).migrate(flyway);

        org.mockito.Mockito.verify(flyway).migrate();
    }

    @Test
    void pinnedConnectionIsBorrowedByFlywayWithoutBeingClosedByIt() throws Exception {
        String url = "jdbc:h2:mem:pin-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        DataSource primary = dataSource(url);
        FlywayConnectionPin pin = new FlywayConnectionPin();
        DataSource pinned = new FlywayConnectionPin.PinnedDataSource(primary, pin);
        try (Connection physical = primary.getConnection()) {
            try (AutoCloseable ignored = pin.pin(physical)) {
                Connection borrowed = pinned.getConnection();
                assertThat(borrowed).isNotSameAs(physical);
                borrowed.close();
                assertThat(physical.isClosed()).as("Flyway 关闭的是代理，物理锁连接仍存活").isFalse();
                assertThat(borrowed.isClosed()).isFalse();
            }
            try (Connection after = pinned.getConnection()) {
                assertThat(after).isNotSameAs(physical);
            }
        }
    }

    @Test
    void h2RowLockOnBusinessSchemaDoesNotLeakIntoFlywayBaseline() throws Exception {
        String url = "jdbc:h2:mem:flyway-schema-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        DataSource primary = dataSource(url);
        FlywayConnectionPin pin = new FlywayConnectionPin();
        FlywayStartupLock lock = new FlywayStartupLock(primary, pin);
        lock.migrate(flyway(primary));

        assertThat(historyVersions(primary)).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
        assertThat(historyCount(primary)).isEqualTo(9);
    }

    @Test
    void h2SameConnectionRowLockIsReleasedByCommitSoItCannotCoverFlyway() throws Exception {
        String url = "jdbc:h2:mem:rowlock-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection first = java.sql.DriverManager.getConnection(url, "sa", "");
                Connection second = java.sql.DriverManager.getConnection(url, "sa", "")) {
            try (Statement ddl = first.createStatement()) {
                ddl.execute("CREATE TABLE row_lock (id INT PRIMARY KEY)");
                ddl.execute("INSERT INTO row_lock (id) VALUES (1)");
            }
            first.setAutoCommit(false);
            try (Statement lock = first.createStatement()) {
                lock.executeQuery("SELECT id FROM row_lock WHERE id = 1 FOR UPDATE").next();
            }
            second.createStatement().execute("SET LOCK_TIMEOUT 200");
            assertThatThrownBy(() -> second.createStatement()
                    .executeQuery("SELECT id FROM row_lock WHERE id = 1 FOR UPDATE"))
                    .isInstanceOf(SQLException.class);
            first.commit();
            assertThat(second.createStatement()
                    .executeQuery("SELECT id FROM row_lock WHERE id = 1 FOR UPDATE").next()).isTrue();
        }
    }

    @Test
    void h2StaleLockConnectionIsDetectedAfterMigration() throws Exception {
        Connection connection = org.mockito.Mockito.mock(Connection.class);
        org.mockito.Mockito.when(connection.isValid(2)).thenReturn(false);

        assertThatThrownBy(() -> FlywayStartupLock.verifyH2LockStillHeld(connection, "token"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("失效");
    }

    @Test
    void h2HolderMismatchAfterMigrationFailsClosed() throws Exception {
        Connection connection = org.mockito.Mockito.mock(Connection.class);
        PreparedStatement statement = org.mockito.Mockito.mock(PreparedStatement.class);
        ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
        org.mockito.Mockito.when(connection.isValid(2)).thenReturn(true);
        org.mockito.Mockito.when(connection.prepareStatement(
                org.mockito.ArgumentMatchers.contains("SELECT holder"))).thenReturn(statement);
        org.mockito.Mockito.when(statement.executeQuery()).thenReturn(rs);
        org.mockito.Mockito.when(rs.next()).thenReturn(true);
        org.mockito.Mockito.when(rs.getString(1)).thenReturn("another-instance");

        assertThatThrownBy(() -> FlywayStartupLock.verifyH2LockStillHeld(connection, "my-token"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("丢失");
    }

    private static PgMock pgMock(boolean unlockResult) throws SQLException {
        DataSource dataSource = org.mockito.Mockito.mock(DataSource.class);
        Connection connection = org.mockito.Mockito.mock(Connection.class);
        java.sql.DatabaseMetaData metaData = org.mockito.Mockito.mock(java.sql.DatabaseMetaData.class);
        Statement statement = org.mockito.Mockito.mock(Statement.class);
        ResultSet unlock = org.mockito.Mockito.mock(ResultSet.class);
        org.mockito.Mockito.when(dataSource.getConnection()).thenReturn(connection);
        org.mockito.Mockito.when(connection.getMetaData()).thenReturn(metaData);
        org.mockito.Mockito.when(metaData.getURL()).thenReturn("jdbc:postgresql://db:5432/config");
        org.mockito.Mockito.when(connection.createStatement()).thenReturn(statement);
        org.mockito.Mockito.when(statement.executeQuery(
                "SELECT pg_advisory_unlock(" + FlywayStartupLock.PG_LOCK_KEY + ")")).thenReturn(unlock);
        org.mockito.Mockito.when(unlock.next()).thenReturn(true);
        org.mockito.Mockito.when(unlock.getBoolean(1)).thenReturn(unlockResult);
        return new PgMock(dataSource, connection, statement);
    }

    private static Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(LOCATIONS)
                .schemas("PUBLIC")
                .baselineOnMigrate(true)
                .load();
    }

    private static DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static List<String> historyVersions(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT \"version\" FROM \"flyway_schema_history\" WHERE \"version\" IS NOT NULL AND \"success\" = TRUE ORDER BY \"installed_rank\"")) {
            List<String> versions = new ArrayList<>();
            while (rs.next()) {
                versions.add(rs.getString(1));
            }
            return versions;
        }
    }

    private static int historyCount(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" IS NOT NULL")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int historyTableCount(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = 'flyway_schema_history'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private record PgMock(DataSource dataSource, Connection connection, Statement statement) {
    }
}
