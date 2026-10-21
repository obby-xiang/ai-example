package com.example.configmgr.ai.kb;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 矩阵 M25 + 本地源契约：manifest 解析、sha 实算、路径越界、单篇缺失。
 */
class KbLocalSourceTest {

    @Test
    void sampleManifestIsValidAndShaMatchesFiles() throws Exception {
        Path dir = KbTestSupport.samples();
        KbSource.ManifestFetch fetch = new LocalDirKbSource(dir, KbTestSupport.mapper()).fetchManifest(null);

        assertThat(fetch.notModified()).isFalse();
        KbManifest manifest = fetch.manifest();
        manifest.validate();
        assertThat(manifest.docs()).hasSize(20);
        for (KbDocMeta doc : manifest.docs()) {
            Path file = dir.resolve(doc.effectiveFile());
            assertThat(Files.isRegularFile(file)).as(doc.id()).isTrue();
            String actual = KbHashing.sha256Hex(Files.readAllBytes(file));
            assertThat(doc.sha()).as("manifest sha 必须与文件一致：" + doc.id()).isEqualTo(actual);
        }
    }

    @Test
    void declaredShaMismatchIsOverriddenByActualContent() throws Exception {
        Path root = KbTestSupport.tempDir("kb-sha-");
        KbTestSupport.write(root, "a.md", "# A\n正文\n");
        KbTestSupport.write(root, "manifest.json", """
                {"version":"v1","docs":[{"id":"a","title":"A","sha":"deadbeef","updated_at":"2026-01-01","file":"a.md"}]}
                """);

        KbSource.ManifestFetch fetch = new LocalDirKbSource(root, KbTestSupport.mapper()).fetchManifest(null);

        String actual = KbHashing.sha256Hex(new String(Files.readAllBytes(root.resolve("a.md")), StandardCharsets.UTF_8));
        assertThat(fetch.manifest().docs().get(0).sha()).isEqualTo(actual).isNotEqualTo("deadbeef");
    }

    @Test
    void missingManifestFailsWithActionableMessage() throws Exception {
        Path root = KbTestSupport.tempDir("kb-nomanifest-");
        assertThatThrownBy(() -> new LocalDirKbSource(root, KbTestSupport.mapper()).fetchManifest(null))
                .isInstanceOf(KbSourceException.class)
                .hasMessageContaining("manifest.json");
    }

    @Test
    void pathTraversalIsRejected() throws Exception {
        Path root = KbTestSupport.tempDir("kb-traversal-");
        KbTestSupport.write(root, "manifest.json", """
                {"version":"v1","docs":[{"id":"evil","title":"Evil","file":"../outside.md"}]}
                """);
        assertThatThrownBy(() -> new LocalDirKbSource(root, KbTestSupport.mapper()).fetchManifest(null))
                .isInstanceOf(KbSourceException.class)
                .hasMessageContaining("越界");
    }

    @Test
    void missingDocumentFailsTheWholeRefresh() throws Exception {
        Path root = KbTestSupport.tempDir("kb-missingdoc-");
        KbTestSupport.write(root, "manifest.json", """
                {"version":"v1","docs":[{"id":"gone","title":"Gone","file":"gone.md"}]}
                """);
        assertThatThrownBy(() -> new LocalDirKbSource(root, KbTestSupport.mapper()).fetchManifest(null))
                .isInstanceOf(KbSourceException.class)
                .hasMessageContaining("不存在");
    }
}
