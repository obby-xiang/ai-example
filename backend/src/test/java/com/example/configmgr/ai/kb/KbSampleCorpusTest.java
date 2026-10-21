package com.example.configmgr.ai.kb;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 矩阵 M25：20 篇样例的长度分布与复杂度覆盖（追加 1 的验收面）。
 */
class KbSampleCorpusTest {

    @Test
    void corpusHasTwentyDocsWithRequiredLengthSpread() throws Exception {
        List<Path> docs;
        try (var stream = Files.list(KbTestSupport.samples())) {
            docs = stream.filter(p -> p.getFileName().toString().endsWith(".md")).toList();
        }
        assertThat(docs).hasSize(20);

        List<Integer> lengths = docs.stream().map(path -> {
            try {
                return Files.readString(path, StandardCharsets.UTF_8).length();
            }
            catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();

        assertThat(lengths).anySatisfy(len -> assertThat(len).isGreaterThanOrEqualTo(8000));
        assertThat(lengths).anySatisfy(len -> assertThat(len).isGreaterThanOrEqualTo(5000));
        assertThat(lengths.stream().filter(len -> len >= 1000 && len <= 3000).count()).isGreaterThanOrEqualTo(4);
        assertThat(lengths.stream().filter(len -> len >= 300 && len <= 800).count()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void everyDocIsMarkedAsSample() throws Exception {
        try (var stream = Files.list(KbTestSupport.samples())) {
            for (Path doc : stream.filter(p -> p.getFileName().toString().endsWith(".md")).toList()) {
                String text = Files.readString(doc, StandardCharsets.UTF_8);
                assertThat(text).as(doc.getFileName().toString()).contains("样例");
                assertThat(text).containsIgnoringCase("样例");
            }
        }
        String manifest = Files.readString(KbTestSupport.samples().resolve("manifest.json"), StandardCharsets.UTF_8);
        assertThat(manifest).contains("kb-samples");
    }

    @Test
    void complexityElementsAreCovered() throws Exception {
        StringBuilder all = new StringBuilder();
        try (var stream = Files.list(KbTestSupport.samples())) {
            for (Path doc : stream.filter(p -> p.getFileName().toString().endsWith(".md")).toList()) {
                all.append(Files.readString(doc, StandardCharsets.UTF_8));
            }
        }
        String text = all.toString();
        assertThat(text).contains("```mermaid");
        assertThat(text).contains("flowchart");
        assertThat(text).contains("sequenceDiagram");
        assertThat(text).contains("```json").contains("```java");
        assertThat(text).contains("####");
        assertThat(text).contains("- [ ]");
        assertThat(text).contains("**问：");
        assertThat(text).containsIgnoringCase("overview");
        // 宽表：一行至少 12 列
        assertThat(text.lines().anyMatch(line -> line.startsWith("|") && line.split("\\|").length >= 13)).isTrue();
        // 数字/比例/日期密集
        assertThat(text).contains("%").contains("2026-");
    }
}
