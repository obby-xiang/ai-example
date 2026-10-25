package com.example.configmgr.file;

import com.example.configmgr.config.AppProperties;
import com.example.configmgr.task.entity.TaskFile;
import com.example.configmgr.task.repo.TaskFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FileKeyMigrationRunner}：历史绝对路径 → 相对键的<b>显式</b>迁移（真实临时目录 + Mockito 仓储）。
 *
 * <p>口径逐条钉死：复制不移动、目标已存在不覆盖、源缺失不改行、重复执行幂等、
 * 单行落库失败只清理复制件。
 */
class FileKeyMigrationRunnerTest {

    @TempDir
    Path storageRoot;

    @TempDir
    Path legacyRoot;

    private TaskFileRepository repository;

    private FileStorageService storage;

    private FileKeyMigrationRunner runner;

    private InMemoryFileKeyMigrationLock lock;

    @BeforeEach
    void setUp() {
        this.repository = mock(TaskFileRepository.class);
        when(this.repository.save(any(TaskFile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        this.storage = storage(this.storageRoot);
        this.lock = new InMemoryFileKeyMigrationLock();
        this.runner = new FileKeyMigrationRunner(this.repository, this.storage, this.lock);
    }

    @Test
    void copiesAbsoluteFileToNewKeyAndRewritesTheRowWithoutMovingTheSource() throws IOException {
        Path source = Files.writeString(this.legacyRoot.resolve("old-export.xlsx"), "legacy");
        TaskFile row = row(1L, source.toAbsolutePath().toString(), source.toAbsolutePath().toString());
        when(this.repository.findAll()).thenReturn(List.of(row));

        FileKeyMigrationRunner.MigrationReport report = this.runner.migrate();

        assertThat(report.scanned()).isEqualTo(1);
        assertThat(report.migrated()).isEqualTo(1);
        assertThat(Path.of(row.getStoragePath()).isAbsolute()).isFalse();
        assertThat(row.getStoragePath()).endsWith(".xlsx");
        assertThat(row.getOriginalPath()).as("original_path 原本跟随 storage_path，一并改成新键")
            .isEqualTo(row.getStoragePath());
        assertThat(source).as("复制不移动：源文件必须还在").exists();
        assertThat(this.storage.read(row.getStoragePath()))
            .isEqualTo("legacy".getBytes(StandardCharsets.UTF_8));
        verify(this.repository).save(row);
    }

    @Test
    void secondRunIsIdempotentAndCreatesNoSecondCopy() throws IOException {
        Path source = Files.writeString(this.legacyRoot.resolve("old-export.xlsx"), "legacy");
        TaskFile row = row(1L, source.toAbsolutePath().toString(), source.toAbsolutePath().toString());
        when(this.repository.findAll()).thenReturn(List.of(row));

        this.runner.migrate();
        long filesAfterFirstRun = storedFiles().size();
        FileKeyMigrationRunner.MigrationReport second = this.runner.migrate();

        assertThat(second.migrated()).isZero();
        assertThat(second.alreadyKey()).isEqualTo(1);
        assertThat(storedFiles()).as("已是相对键的行直接跳过，绝不重复复制").hasSize((int) filesAfterFirstRun);
    }

    @Test
    void missingSourceLeavesTheRowUntouched() {
        String missing = this.legacyRoot.resolve("never-written.xlsx").toAbsolutePath().toString();
        TaskFile row = row(2L, missing, missing);
        when(this.repository.findAll()).thenReturn(List.of(row));

        FileKeyMigrationRunner.MigrationReport report = this.runner.migrate();

        assertThat(report.missingSource()).isEqualTo(1);
        assertThat(row.getStoragePath()).as("缺失不改行").isEqualTo(missing);
        assertThat(row.getOriginalPath()).isEqualTo(missing);
        verify(this.repository, never()).save(any(TaskFile.class));
    }

    @Test
    void anExistingTargetIsNeverOverwritten() throws IOException {
        Path source = Files.writeString(this.legacyRoot.resolve("old-export.xlsx"), "new-from-legacy");
        Path existingTarget = Files.writeString(this.storageRoot.resolve("existing.xlsx"), "keep-me");
        StoredFile forced = new StoredFile("existing.xlsx", existingTarget);

        FileStorageService spyStorage = spy(this.storage);
        doReturn(forced).when(spyStorage).allocate(anyString());
        FileKeyMigrationRunner guardedRunner = new FileKeyMigrationRunner(this.repository, spyStorage, this.lock);
        TaskFile row = row(3L, source.toAbsolutePath().toString(), source.toAbsolutePath().toString());
        when(this.repository.findAll()).thenReturn(List.of(row));

        FileKeyMigrationRunner.MigrationReport report = guardedRunner.migrate();

        assertThat(report.targetExists()).isEqualTo(1);
        assertThat(row.getStoragePath()).as("目标冲突时数据库行保持不变")
            .isEqualTo(source.toAbsolutePath().toString());
        assertThat(Files.readString(existingTarget)).as("已存在的目标绝不被覆盖").isEqualTo("keep-me");
        assertThat(source).exists();
        verify(this.repository, never()).save(any(TaskFile.class));
    }

    @Test
    void anOriginalPathPointingElsewhereIsLeftAlone() throws IOException {
        Path source = Files.writeString(this.legacyRoot.resolve("export.xlsx"), "export");
        String otherOriginal = this.legacyRoot.resolve("upload-original.xlsx").toAbsolutePath().toString();
        TaskFile row = row(4L, source.toAbsolutePath().toString(), otherOriginal);
        when(this.repository.findAll()).thenReturn(List.of(row));

        FileKeyMigrationRunner.MigrationReport report = this.runner.migrate();

        assertThat(report.migrated()).isEqualTo(1);
        assertThat(row.getOriginalPath()).as("与 storage_path 不同的历史值属另一件文件，不在本次范围")
            .isEqualTo(otherOriginal);
    }

    @Test
    void databaseFailureLeavesTheRowUnchangedAndRemovesTheCopiedFile() throws IOException {
        Path source = Files.writeString(this.legacyRoot.resolve("old-export.xlsx"), "legacy");
        TaskFile row = row(5L, source.toAbsolutePath().toString(), source.toAbsolutePath().toString());
        when(this.repository.findAll()).thenReturn(List.of(row));
        when(this.repository.save(any(TaskFile.class))).thenThrow(new IllegalStateException("db down"));

        FileKeyMigrationRunner.MigrationReport report = this.runner.migrate();

        assertThat(report.failed()).isEqualTo(1);
        assertThat(row.getStoragePath()).isEqualTo(source.toAbsolutePath().toString());
        assertThat(row.getOriginalPath()).isEqualTo(source.toAbsolutePath().toString());
        assertThat(source).exists();
        assertThat(storedFiles()).as("落库失败的复制件要清掉（源件不受影响）").isEmpty();
    }

    @Test
    void invalidPersistedValuesAreReportedWithoutTouchingRows() {
        TaskFile blank = row(6L, " ", null);
        TaskFile traversal = row(7L, "../escape.xlsx", "../escape.xlsx");
        when(this.repository.findAll()).thenReturn(List.of(blank, traversal));

        FileKeyMigrationRunner.MigrationReport report = this.runner.migrate();

        assertThat(report.invalid()).isEqualTo(2);
        assertThat(blank.getStoragePath()).isEqualTo(" ");
        assertThat(traversal.getStoragePath()).isEqualTo("../escape.xlsx");
        verify(this.repository, never()).save(any(TaskFile.class));
    }

    // ───────────── 多实例单飞：锁被占/并发/续期失效 ─────────────

    @Test
    void whenAnotherInstanceHoldsTheLockNothingIsReadCopiedOrRewritten() {
        String absolute = this.legacyRoot.resolve("old-export.xlsx").toAbsolutePath().toString();
        TaskFile row = row(8L, absolute, absolute);
        when(this.repository.findAll()).thenReturn(List.of(row));
        this.lock.preHold("another-instance");

        FileKeyMigrationRunner.MigrationReport report = this.runner.migrate();

        assertThat(report.lockSkipped()).isTrue();
        assertThat(report.scanned()).isZero();
        verify(this.repository, never()).findAll();
        assertThat(row.getStoragePath()).as("抢不到锁：数据库行原样").isEqualTo(absolute);
        assertThat(this.lock.releaseCalls()).as("没抢到锁就不该释放别人的锁").isZero();
        assertThat(this.lock.isFree()).as("锁仍属于另一实例").isFalse();
    }

    @Test
    void concurrentRunnersMigrateEachRowExactlyOnce() throws Exception {
        Path sourceA = Files.writeString(this.legacyRoot.resolve("a.xlsx"), "a");
        Path sourceB = Files.writeString(this.legacyRoot.resolve("b.xlsx"), "b");
        List<TaskFile> rows = List.of(
                row(11L, sourceA.toAbsolutePath().toString(), sourceA.toAbsolutePath().toString()),
                row(12L, sourceB.toAbsolutePath().toString(), sourceB.toAbsolutePath().toString()));
        when(this.repository.findAll()).thenReturn(rows);

        FileKeyMigrationRunner runnerA = new FileKeyMigrationRunner(this.repository, this.storage, this.lock);
        FileKeyMigrationRunner runnerB = new FileKeyMigrationRunner(this.repository, this.storage, this.lock);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<FileKeyMigrationRunner.MigrationReport>> futures = new ArrayList<>();
        long acquiredRuns;
        try {
            for (FileKeyMigrationRunner candidate : List.of(runnerA, runnerB)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return candidate.migrate();
                }));
            }
            start.countDown();
            List<FileKeyMigrationRunner.MigrationReport> reports = new ArrayList<>();
            for (Future<FileKeyMigrationRunner.MigrationReport> future : futures) {
                reports.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(reports.stream().mapToInt(FileKeyMigrationRunner.MigrationReport::migrated).sum())
                    .as("两个实例并发启动：每行只允许迁移一次").isEqualTo(2);
            acquiredRuns = reports.stream().filter(report -> !report.lockSkipped()).count();
        }
        finally {
            pool.shutdownNow();
        }

        assertThat(storedFiles()).as("每行只允许产生一份复制件").hasSize(2);
        assertThat(rows).allSatisfy(file -> assertThat(Path.of(file.getStoragePath()).isAbsolute()).isFalse());
        assertThat(rows.stream().map(TaskFile::getStoragePath).distinct().count()).isEqualTo(2);
        assertThat(sourceA).exists();
        assertThat(sourceB).exists();
        assertThat(this.lock.acquireCalls()).isEqualTo(2);
        assertThat(this.lock.releaseCalls()).as("抢到的实例在结束后释放锁").isEqualTo((int) acquiredRuns);
        assertThat(this.lock.isFree()).isTrue();
    }

    @Test
    void lockIsReleasedAfterARunSoTheNextStartupCanRunAgain() throws IOException {
        Path source = Files.writeString(this.legacyRoot.resolve("old-export.xlsx"), "legacy");
        TaskFile row = row(13L, source.toAbsolutePath().toString(), source.toAbsolutePath().toString());
        when(this.repository.findAll()).thenReturn(List.of(row));

        this.runner.migrate();
        assertThat(this.lock.isFree()).as("正常结束后必须释放单飞锁").isTrue();

        FileKeyMigrationRunner.MigrationReport second = this.runner.migrate();
        assertThat(second.lockSkipped()).isFalse();
        assertThat(second.migrated()).isZero();
        assertThat(second.alreadyKey()).isEqualTo(1);
    }

    @Test
    void renewFailureBeforeCopyStopsAnyNewFile() throws IOException {
        Path source = Files.writeString(this.legacyRoot.resolve("before-copy.xlsx"), "legacy");
        String absolute = source.toAbsolutePath().toString();
        TaskFile row = row(20L, absolute, absolute);
        when(this.repository.findAll()).thenReturn(List.of(row));
        this.lock.failRenewals();
        FileKeyMigrationRunner clocked = new FileKeyMigrationRunner(this.repository, this.storage, this.lock,
                new StepClock(0L, FileKeyMigrationRunner.RENEW_INTERVAL_NANOS + 1));

        FileKeyMigrationRunner.MigrationReport report = clocked.migrate();

        assertThat(report.lockLost()).isTrue();
        assertThat(report.migrated()).isZero();
        assertThat(report.scanned()).isEqualTo(1);
        assertThat(row.getStoragePath()).as("复制前丢锁：数据库行原样").isEqualTo(absolute);
        assertThat(source).exists();
        assertThat(storedFiles()).as("复制前丢锁：不得产生新件").isEmpty();
        verify(this.repository, never()).save(any(TaskFile.class));
    }

    @Test
    void lockLostAfterSlowCopyDeletesUnreferencedCopyAndKeepsRow() throws IOException {
        Path source = Files.writeString(this.legacyRoot.resolve("slow-copy.xlsx"), "legacy");
        String absolute = source.toAbsolutePath().toString();
        TaskFile row = row(21L, absolute, absolute);
        when(this.repository.findAll()).thenReturn(List.of(row));
        this.lock.failRenewals();
        // 读 #1 = 抢锁后起点；读 #2 = 复制前检查（未到期）；读 #3 = 复制后/save 前（模拟慢复制跨过 TTL/3）
        FileKeyMigrationRunner clocked = new FileKeyMigrationRunner(this.repository, this.storage, this.lock,
                new StepClock(0L, 0L, FileKeyMigrationRunner.RENEW_INTERVAL_NANOS + 1));

        FileKeyMigrationRunner.MigrationReport report = clocked.migrate();

        assertThat(report.lockLost()).isTrue();
        assertThat(report.migrated()).as("慢复制后才发现丢锁：不能记 migrated").isZero();
        assertThat(report.scanned()).isEqualTo(1);
        assertThat(row.getStoragePath()).as("数据库行必须原样保留").isEqualTo(absolute);
        assertThat(row.getOriginalPath()).isEqualTo(absolute);
        assertThat(source).as("源件保留").exists();
        assertThat(storedFiles()).as("慢复制产出的未引用复制件必须删除").isEmpty();
        verify(this.repository, never()).save(any(TaskFile.class));
    }

    @Test
    void timeDrivenRenewalKeepsMigratingWhenTheLockStillHolds() throws IOException {
        Path source = Files.writeString(this.legacyRoot.resolve("renew-ok.xlsx"), "legacy");
        String absolute = source.toAbsolutePath().toString();
        TaskFile row = row(22L, absolute, absolute);
        when(this.repository.findAll()).thenReturn(List.of(row));
        FileKeyMigrationRunner clocked = new FileKeyMigrationRunner(this.repository, this.storage, this.lock,
                new StepClock(0L, 0L, FileKeyMigrationRunner.RENEW_INTERVAL_NANOS + 1));

        FileKeyMigrationRunner.MigrationReport report = clocked.migrate();

        assertThat(report.lockLost()).isFalse();
        assertThat(report.migrated()).isEqualTo(1);
        assertThat(this.lock.renewCalls()).as("TTL/3 到期即续租，而不是等 100 行").isGreaterThanOrEqualTo(1);
        assertThat(storedFiles()).hasSize(1);
        assertThat(source).exists();
    }

    private List<Path> storedFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(this.storageRoot)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    private static TaskFile row(Long id, String storagePath, String originalPath) {
        TaskFile file = new TaskFile();
        file.setId(id);
        file.setTaskId(1L);
        file.setDefCode("ROLE_DICT");
        file.setFileType("EXPORT");
        file.setStoragePath(storagePath);
        file.setOriginalPath(originalPath);
        return file;
    }

    /** 可控时钟：按读取次序返回预设纳秒值（用尽后保持最后一步）。 */
    private static final class StepClock implements LongSupplier {

        private final long[] steps;

        private int index;

        StepClock(long... steps) {
            this.steps = steps;
        }

        @Override
        public long getAsLong() {
            if (this.steps.length == 0) {
                return 0L;
            }
            long value = this.steps[Math.min(this.index, this.steps.length - 1)];
            this.index++;
            return value;
        }
    }

    private static FileStorageService storage(Path root) {
        AppProperties properties = new AppProperties();
        properties.getFile().setStoragePath(root.toString());
        return new FileStorageService(properties);
    }
}
