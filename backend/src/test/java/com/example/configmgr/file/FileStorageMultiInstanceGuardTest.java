package com.example.configmgr.file;

import com.example.configmgr.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多实例判据与路径闸门：<b>h2tcp 单独激活</b>也必须要求绝对路径；显式开关同样生效；
 * 默认单实例（无 profile、开关 false）继续允许相对路径。
 */
class FileStorageMultiInstanceGuardTest {

    @TempDir
    Path sharedRoot;

    @Test
    void h2tcpDatasourceAloneRejectsRelativeStoragePath() {
        AppProperties properties = new AppProperties();
        properties.getFile().setStoragePath("./data/files");
        MockEnvironment environment = env();
        environment.withProperty("spring.datasource.url", "jdbc:h2:tcp://127.0.0.1:9092/./config_mgr");

        assertThatThrownBy(() -> new FileStorageMultiInstanceGuard(properties, environment))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("绝对路径")
            .hasMessageContaining(FileStorageMultiInstanceGuard.H2TCP_DATASOURCE_PREFIX);
    }

    @Test
    void h2SslDatasourceAlsoCountsAsSharedDatabase() {
        AppProperties properties = new AppProperties();
        properties.getFile().setStoragePath("./data/files");
        MockEnvironment environment = env();
        environment.withProperty("spring.datasource.url", "jdbc:h2:ssl://127.0.0.1:9092/./config_mgr");

        assertThatThrownBy(() -> new FileStorageMultiInstanceGuard(properties, environment))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(FileStorageMultiInstanceGuard.H2SSL_DATASOURCE_PREFIX);
    }

    @Test
    void postgresqlDatasourceAloneRejectsRelativeStoragePath() {
        AppProperties properties = new AppProperties();
        properties.getFile().setStoragePath("./data/files");
        MockEnvironment environment = env();
        environment.withProperty("spring.datasource.url", "jdbc:postgresql://db:5432/config_mgr");

        assertThatThrownBy(() -> new FileStorageMultiInstanceGuard(properties, environment))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("绝对路径")
            .hasMessageContaining(FileStorageMultiInstanceGuard.POSTGRESQL_DATASOURCE_PREFIX);
    }

    @Test
    void fileAndMemoryDatasourcesKeepSingleInstanceDevWorking() {
        AppProperties properties = new AppProperties();
        properties.getFile().setStoragePath("./data/files");
        MockEnvironment file = env();
        file.withProperty("spring.datasource.url", "jdbc:h2:file:./data/config_mgr_db");
        MockEnvironment mem = env();
        mem.withProperty("spring.datasource.url", "jdbc:h2:mem:unit-test");

        assertThatCode(() -> new FileStorageMultiInstanceGuard(properties, file)).doesNotThrowAnyException();
        assertThatCode(() -> new FileStorageMultiInstanceGuard(properties, mem)).doesNotThrowAnyException();
    }

    @Test
    void defaultSingleInstanceDevKeepsRelativePathWorking() {
        AppProperties properties = new AppProperties();
        properties.getFile().setStoragePath("./data/files");

        assertThatCode(() -> new FileStorageMultiInstanceGuard(properties, env()))
            .as("默认单实例开发不受影响")
            .doesNotThrowAnyException();
    }

    @Test
    void h2tcpProfileAloneRejectsRelativeStoragePath() {
        AppProperties properties = new AppProperties();
        properties.getFile().setStoragePath("./data/files");

        assertThatThrownBy(() -> new FileStorageMultiInstanceGuard(properties, env("h2tcp")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.file.storage-path")
            .hasMessageContaining("绝对路径")
            .hasMessageContaining("profile=h2tcp")
            .hasMessageContaining(FileStorageMultiInstanceGuard.STORAGE_PATH_ENV);
    }

    @Test
    void postgresqlProfileAloneRejectsRelativeStoragePath() {
        AppProperties properties = new AppProperties();
        properties.getFile().setStoragePath("./data/files");

        assertThatThrownBy(() -> new FileStorageMultiInstanceGuard(properties, env("postgresql")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("profile=postgresql")
            .hasMessageContaining("绝对路径");
    }

    @Test
    void multiInstanceAndClusterProfilesAlsoEnforceAbsolutePath() {
        AppProperties properties = new AppProperties();
        properties.getFile().setStoragePath("./data/files");

        assertThatThrownBy(() -> new FileStorageMultiInstanceGuard(properties, env("multi-instance")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("profile=multi-instance");
        assertThatThrownBy(() -> new FileStorageMultiInstanceGuard(properties, env("cluster")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("profile=cluster");
    }

    @Test
    void explicitSwitchEnforcesWithoutAnyProfile() {
        AppProperties properties = new AppProperties();
        properties.getFile().setStoragePath("./data/files");
        properties.getFile().setMultiInstance(true);

        assertThatThrownBy(() -> new FileStorageMultiInstanceGuard(properties, env()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.file.multi-instance=true")
            .hasMessageContaining("绝对路径");
    }

    @Test
    void blankOrUnresolvedPathAlsoFailsFastUnderH2tcp() {
        AppProperties blank = new AppProperties();
        blank.getFile().setStoragePath("  ");
        assertThatThrownBy(() -> new FileStorageMultiInstanceGuard(blank, env("h2tcp")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.file.storage-path");

        AppProperties unresolved = new AppProperties();
        unresolved.getFile().setStoragePath("${APP_FILE_STORAGE_PATH}");
        assertThatThrownBy(() -> new FileStorageMultiInstanceGuard(unresolved, env("h2tcp")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("未解析");
    }

    @Test
    void absoluteStoragePathIsAcceptedUnderEveryMultiInstanceSignal() {
        AppProperties properties = new AppProperties();
        properties.getFile().setStoragePath(this.sharedRoot.toAbsolutePath().toString());

        assertThatCode(() -> new FileStorageMultiInstanceGuard(properties, env("h2tcp"))).doesNotThrowAnyException();
        assertThatCode(() -> new FileStorageMultiInstanceGuard(properties, env("postgresql")))
            .doesNotThrowAnyException();
        assertThatCode(() -> new FileStorageMultiInstanceGuard(properties, env("cluster", "multi-instance")))
            .doesNotThrowAnyException();
    }

    private static MockEnvironment env(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }
}
