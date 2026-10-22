package com.example.configmgr.ai.kb;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 样例随包带入 + 首启自播种（KB 样例打包）：
 * <ul>
 * <li>空目录/目录不存在 ⇒ 从 classpath {@code kb-samples/} 播种，随后正常建索引到 ready；</li>
 * <li>目录已有内容 ⇒ 不播种（不覆盖、不混料）；</li>
 * <li>http 源 ⇒ 不播种（生产路径不变）；</li>
 * <li>播种字节按 LF 落地 ⇒ manifest sha 与文件实算一致，刷新全程零 sha WARN。</li>
 * </ul>
 */
class KbSampleSeedingTest {

    @Test
    void emptyDirIsSeededFromClasspathAndBecomesReady() throws Exception {
        assertThat(getClass().getClassLoader().getResource("kb-samples/manifest.json"))
                .as("构建期应把 docs/kb-samples 拷进 classes/kb-samples（pom 的 copy-resources）")
                .isNotNull();

        Path root = KbTestSupport.tempDir("kb-seed-");
        Path dir = root.resolve("kb");
        KbService service = service(dir, root.resolve("idx"));
        try {
            service.start();
            awaitReady(service);

            assertThat(service.catalog()).hasSize(20);
            assertThat(Files.isRegularFile(dir.resolve("manifest.json"))).isTrue();
            try (var files = Files.list(dir)) {
                assertThat(files.filter(path -> path.toString().endsWith(".md")).count()).isEqualTo(20);
            }
        }
        finally {
            service.stop();
        }
    }

    @Test
    void existingContentIsNotOverwritten() throws Exception {
        Path root = KbTestSupport.tempDir("kb-seed-existing-");
        Path dir = root.resolve("kb");
        Files.createDirectories(dir);
        KbTestSupport.write(dir, "own.md", "# 既有语料\n");

        KbService service = service(dir, root.resolve("idx"));
        try {
            service.start();
            Thread.sleep(300);
            assertThat(service.ready()).isFalse();
            try (var files = Files.list(dir)) {
                assertThat(files.map(path -> path.getFileName().toString()).toList()).containsExactly("own.md");
            }
            assertThat(Files.readString(dir.resolve("own.md"), StandardCharsets.UTF_8))
                    .isEqualTo("# 既有语料\n");
        }
        finally {
            service.stop();
        }
    }

    @Test
    void httpSourceDoesNotSeed() throws Exception {
        Path root = KbTestSupport.tempDir("kb-seed-http-");
        Path dir = root.resolve("kb");
        FakeKbSource source = new FakeKbSource().put("a", "A", "# A\n正文\n");
        KbService service = new KbService(KbTestSupport.props(dir, root.resolve("idx")),
                KbTestSupport.mapper(), source);
        try {
            service.start();
            awaitReady(service);
            assertThat(Files.exists(dir)).isFalse();
        }
        finally {
            service.stop();
        }
    }

    @Test
    void seededFilesMatchManifestShaWithZeroShaWarnings() throws Exception {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger sourceLogger = (Logger) LoggerFactory.getLogger(LocalDirKbSource.class);
        Logger serviceLogger = (Logger) LoggerFactory.getLogger(KbService.class);
        sourceLogger.addAppender(appender);
        serviceLogger.addAppender(appender);

        Path root = KbTestSupport.tempDir("kb-seed-sha-");
        Path dir = root.resolve("kb");
        KbService service = service(dir, root.resolve("idx"));
        try {
            service.start();
            awaitReady(service);
        }
        finally {
            service.stop();
            sourceLogger.detachAppender(appender);
            serviceLogger.detachAppender(appender);
        }

        List<String> shaWarnings = appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("sha"))
                .toList();
        assertThat(shaWarnings).as("播种后不应出现 sha 不一致告警").isEmpty();

        KbManifest manifest = KbManifest.parse(Files.readAllBytes(dir.resolve("manifest.json")),
                KbTestSupport.mapper());
        assertThat(manifest.docs()).hasSize(20);
        for (KbDocMeta meta : manifest.docs()) {
            byte[] raw = Files.readAllBytes(dir.resolve(meta.effectiveFile()));
            assertThat(new String(raw, StandardCharsets.UTF_8)).as(meta.id() + " 应为 LF").doesNotContain("\r");
            assertThat(KbHashing.sha256Hex(raw)).as("manifest sha 必须与播种字节一致：" + meta.id())
                    .isEqualTo(meta.sha());
        }
    }

    private static KbService service(Path kbDir, Path indexDir) {
        return new KbService(KbTestSupport.props(kbDir, indexDir), KbTestSupport.mapper(),
                new LocalDirKbSource(kbDir, KbTestSupport.mapper()));
    }

    private static void awaitReady(KbService service) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (service.ready()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("知识库未在 15s 内就绪：" + service.status());
    }
}
