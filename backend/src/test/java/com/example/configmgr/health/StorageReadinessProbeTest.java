package com.example.configmgr.health;

import com.example.configmgr.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StorageReadinessProbeTest {

    @TempDir
    Path tempDir;

    @Test
    void writableStorageIsUpWithSafeFixedDetail() {
        ProbeResult result = probe(this.tempDir).check();

        assertThat(result.up()).isTrue();
        assertThat(String.valueOf(result.detail())).isEqualTo("可写")
                .doesNotContain(this.tempDir.toAbsolutePath().toString());
    }

    @Test
    void regularFileStorageIsDownWithSafeFixedDetail() throws Exception {
        Path regularFile = Files.writeString(this.tempDir.resolve("not-a-directory"), "x");

        ProbeResult result = probe(regularFile).check();

        assertThat(result.up()).isFalse();
        assertThat(String.valueOf(result.detail())).isEqualTo("不是目录")
                .doesNotContain(regularFile.toAbsolutePath().toString());
    }

    @Test
    void invalidPathIsDownWithSafeFixedDetail() {
        ProbeResult result = probe("\0").check();

        assertThat(result.up()).isFalse();
        assertThat(result.detail()).isEqualTo("存储路径不可用");
    }

    @Test
    void ioFailureIsDownWithSafeFixedDetail() throws Exception {
        Path regularFile = Files.writeString(this.tempDir.resolve("regular-file"), "x");

        ProbeResult result = probe(regularFile.resolve("child")).check();

        assertThat(result.up()).isFalse();
        assertThat(result.detail()).isEqualTo("存储路径不可用");
    }

    private static StorageReadinessProbe probe(Path storagePath) {
        return probe(storagePath.toString());
    }

    private static StorageReadinessProbe probe(String storagePath) {
        AppProperties properties = new AppProperties();
        properties.getFile().setStoragePath(storagePath);
        return new StorageReadinessProbe(properties);
    }
}
