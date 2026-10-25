package com.example.configmgr.file;

import com.example.configmgr.config.AppProperties;
import com.example.configmgr.task.repo.TaskFileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 迁移必须是<b>显式 opt-in</b>：默认上下文里根本不存在 {@link FileKeyMigrationRunner}，
 * 只有显式 {@code app.file.key-migration.enabled=true} 才装配（从而在启动时跑一次）。
 *
 * <p>为什么值得单锁：迁移会复制文件并改写 {@code task_files} 行；一旦被无条件装配，任何一次重启都会
 * 触发写盘/改库，而它本该是运维主动发起的一次性动作。
 */
class FileKeyMigrationOptInTest {

    @TempDir
    Path storageRoot;

    @Test
    void runnerIsNotRegisteredByDefault() {
        contextRunner().run(context ->
                assertThat(context).doesNotHaveBean(FileKeyMigrationRunner.class));
    }

    @Test
    void runnerIsRegisteredOnlyWhenExplicitlyEnabled() {
        contextRunner().withPropertyValues("app.file.key-migration.enabled=true").run(context ->
                assertThat(context).hasSingleBean(FileKeyMigrationRunner.class));
    }

    private ApplicationContextRunner contextRunner() {
        AppProperties properties = new AppProperties();
        properties.getFile().setStoragePath(this.storageRoot.toString());
        return new ApplicationContextRunner()
            .withBean(TaskFileRepository.class, () -> mock(TaskFileRepository.class))
            .withBean(FileStorageService.class, () -> new FileStorageService(properties))
            .withBean(FileKeyMigrationLock.class, InMemoryFileKeyMigrationLock::new)
            .withUserConfiguration(FileKeyMigrationRunner.class);
    }
}
