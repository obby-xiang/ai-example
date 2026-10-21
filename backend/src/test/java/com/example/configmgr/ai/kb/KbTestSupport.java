package com.example.configmgr.ai.kb;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 知识库用例的公共脚手架（临时语料、样例仓定位、服务装配）。 */
final class KbTestSupport {

    private KbTestSupport() {
    }

    static ObjectMapper mapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    /** 样例知识仓（{@code docs/kb-samples}）：surefire 工作目录是 backend 模块。 */
    static Path samples() {
        Path candidate = Path.of("..", "docs", "kb-samples");
        if (!Files.isDirectory(candidate)) {
            candidate = Path.of("docs", "kb-samples");
        }
        return candidate.toAbsolutePath().normalize();
    }

    static AiProperties props(Path kbDir, Path indexDir) {
        AiProperties properties = new AiProperties();
        properties.getKb().setEnabled(true);
        properties.getKb().setSource("local");
        properties.getKb().setDir(kbDir.toString());
        properties.getKb().setIndexDir(indexDir.toString());
        return properties;
    }

    static Path tempDir(String prefix) throws IOException {
        return Files.createTempDirectory(prefix);
    }

    /** 用样例仓建一个已就绪的服务（每个用例独立索引目录）。 */
    static KbService samplesService() throws Exception {
        return service(samples(), tempDir("kb-idx-"));
    }

    static KbService service(Path kbDir, Path indexDir) throws Exception {
        AiProperties properties = props(kbDir, indexDir);
        KbService service = new KbService(properties, mapper(),
                new LocalDirKbSource(kbDir, mapper()));
        service.refreshNow();
        return service;
    }

    /** 未刷新的服务（模拟"启动异步建索引尚未完成"）。 */
    static KbService notReadyService() throws IOException {
        Path dir = tempDir("kb-empty-");
        return new KbService(props(dir, tempDir("kb-idx-")), mapper(), new LocalDirKbSource(dir, mapper()));
    }

    static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent() == null ? root : file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** 写一份最小语料：manifest + 若干 md，返回知识目录。 */
    static Path corpus(String name, Map<String, String> docs) throws IOException {
        Path root = tempDir("kb-" + name + "-");
        StringBuilder json = new StringBuilder();
        json.append("{\"version\":\"v1\",\"generated_at\":\"2026-10-21\",\"docs\":[");
        int i = 0;
        for (Map.Entry<String, String> entry : docs.entrySet()) {
            String id = entry.getKey();
            String file = id + ".md";
            write(root, file, entry.getValue());
            String sha = KbHashing.sha256Hex(entry.getValue());
            if (i++ > 0) {
                json.append(',');
            }
            json.append("{\"id\":\"").append(id).append("\",\"title\":\"").append(id)
                    .append(" 示例\",\"summary\":\"摘要 ").append(id)
                    .append("\",\"tags\":[\"示例\"],\"aliases\":[],\"sha\":\"").append(sha)
                    .append("\",\"updated_at\":\"2026-10-01\",\"file\":\"").append(file).append("\"}");
        }
        json.append("]}");
        write(root, "manifest.json", json.toString());
        return root;
    }

    static Map<String, String> mapOf(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /** 把样例仓整体复制到临时目录（增量用例要改文件，不能污染仓内样例）。 */
    static Path copySamples() throws IOException {
        Path target = tempDir("kb-copy-");
        try (var stream = Files.list(samples())) {
            for (Path file : stream.toList()) {
                Files.copy(file, target.resolve(file.getFileName()));
            }
        }
        return target;
    }
}
