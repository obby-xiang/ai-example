package com.example.configmgr.file;

import com.example.configmgr.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 相对存储键口径（多实例共享文件存储）：<b>真实临时目录</b>，不起 Spring。
 *
 * <p>锁四件事：① 库里该存的值是 UUID 相对键（不含基目录/工作目录）；② 两个实例（同基目录的两个
 * {@link FileStorageService}）A 写 B 读；③ 历史绝对路径仍可读、相对键含 {@code ..} 一律拒绝；
 * ④ 写盘/摘要/删除都经键解析，`allocate` 不建文件。
 */
class FileStorageKeyTest {

    @TempDir
    Path storageRoot;

    @TempDir
    Path legacyRoot;

    @Test
    void storeReturnsRelativeKeyAndWritesUnderTheConfiguredBase() throws IOException {
        FileStorageService storage = storage(this.storageRoot);

        StoredFile stored = storage.store(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)),
                "report.xlsx");

        assertThat(stored.key()).as("键 = UUID + 扩展名，不含任何目录信息").doesNotContain("/")
            .doesNotContain("\\")
            .doesNotContain(this.storageRoot.toString())
            .endsWith(".xlsx");
        assertThat(java.nio.file.Path.of(stored.key()).isAbsolute()).as("键必须是相对值（DB 里只存它）").isFalse();
        assertThat(stored.path().getParent()).isEqualTo(this.storageRoot.toAbsolutePath().normalize());
        assertThat(Files.readString(stored.path())).isEqualTo("hello");
        assertThat(storage.read(stored.key())).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void fileWrittenByOneInstanceIsReadableByAnotherInstanceWithTheSameBase() throws IOException {
        FileStorageService instanceA = storage(this.storageRoot);
        FileStorageService instanceB = storage(this.storageRoot);

        StoredFile stored = instanceA.store(new ByteArrayInputStream("shared".getBytes(StandardCharsets.UTF_8)),
                "shared.xlsx");

        assertThat(instanceB.resolve(stored.key())).as("相对键在两个实例上解析到同一份共享文件")
            .isEqualTo(instanceA.resolve(stored.key()));
        assertThat(instanceB.read(stored.key())).isEqualTo("shared".getBytes(StandardCharsets.UTF_8));
        assertThat(instanceB.digest(stored.key()).size()).isEqualTo(6);
    }

    @Test
    void legacyAbsolutePathStaysReadableAfterTheKeyRefactor() throws IOException {
        Path legacy = Files.writeString(this.legacyRoot.resolve("old-export.xlsx"), "legacy-bytes");
        FileStorageService storage = storage(this.storageRoot);

        assertThat(storage.resolve(legacy.toAbsolutePath().toString()))
            .isEqualTo(legacy.toAbsolutePath().normalize());
        assertThat(storage.read(legacy.toAbsolutePath().toString()))
            .as("历史行存的是本机绝对路径：不迁移也必须能读")
            .isEqualTo("legacy-bytes".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void resolveRejectsRelativeKeysContainingParentTraversal() {
        FileStorageService storage = storage(this.storageRoot);

        assertThatThrownBy(() -> storage.resolve("../escape.xlsx"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("..");
        assertThatThrownBy(() -> storage.resolve("sub/../../escape.xlsx"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("..");
        assertThatThrownBy(() -> storage.resolve(".."))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("..");
    }

    @Test
    void nestedRelativeKeyResolvesInsideTheBase() {
        FileStorageService storage = storage(this.storageRoot);

        assertThat(storage.resolve("nested/part.xlsx"))
            .isEqualTo(this.storageRoot.toAbsolutePath().normalize().resolve("nested/part.xlsx"));
    }

    @Test
    void allocateDoesNotCreateAFileAndAlwaysProducesDistinctFlatKeys() {
        FileStorageService storage = storage(this.storageRoot);

        StoredFile first = storage.allocate(".xlsx");
        StoredFile second = storage.allocate(".xlsx");

        assertThat(first.path()).doesNotExist();
        assertThat(first.key()).isNotEqualTo(second.key());
        assertThat(first.path().getParent()).isEqualTo(this.storageRoot.toAbsolutePath().normalize());
        assertThat(first.pathString()).isEqualTo(first.path().toString());
    }

    @Test
    void storeRejectsAnExtensionCarryingPathTraversal() throws IOException {
        FileStorageService storage = storage(this.storageRoot);

        assertThatThrownBy(() -> storage.store(new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)),
                "evil.x/../../escaped"))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(storedFiles()).as("被拒的扩展名不得在存储目录留下任何文件").isEmpty();
    }

    @Test
    void storeRemovesThePartialFileWhenTheInputStreamFails() throws IOException {
        FileStorageService storage = storage(this.storageRoot);
        InputStream failing = new InputStream() {
            private int reads;

            @Override
            public int read() throws IOException {
                if (this.reads++ < 4) {
                    return 'x';
                }
                throw new IOException("boom");
            }
        };

        assertThatThrownBy(() -> storage.store(failing, "broken.xlsx")).isInstanceOf(IOException.class);

        assertThat(storedFiles()).as("读流中途失败：半截文件必须回收").isEmpty();
    }

    @Test
    void writeReadDigestAndDeleteAllGoThroughTheKey() throws IOException {
        FileStorageService storage = storage(this.storageRoot);
        StoredFile stored = storage.allocate(".xlsx");

        storage.write(stored.path(), "abc".getBytes(StandardCharsets.UTF_8));

        assertThat(storage.read(stored.key())).isEqualTo("abc".getBytes(StandardCharsets.UTF_8));
        FileStorageService.FileDigest digest = storage.digest(stored.key());
        assertThat(digest.size()).isEqualTo(3);
        assertThat(digest.sha256())
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");

        storage.delete(stored.key());
        assertThat(stored.path()).doesNotExist();
    }

    @Test
    void writeRejectsPathsOutsideTheConfiguredBase() {
        FileStorageService storage = storage(this.storageRoot);
        Path outside = this.legacyRoot.resolve("outside.xlsx");

        assertThatThrownBy(() -> storage.write(outside, "x".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("存储目录");
    }

    private List<Path> storedFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(this.storageRoot)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    private static FileStorageService storage(Path root) {
        AppProperties properties = new AppProperties();
        properties.getFile().setStoragePath(root.toString());
        return new FileStorageService(properties);
    }
}
