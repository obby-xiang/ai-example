package com.example.configmgr.ai.kb;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地目录知识源（默认形态）：{@code <dir>/manifest.json} + 各文档文件。
 *
 * <p><b>sha 以规范化后的正文实算为准</b>：已有声明必须一致，否则整轮 fail-fast；
 * 缺失声明时才使用实算值。
 *
 * <p><b>路径闸</b>：{@code file} 允许写相对子路径，但解析后必须仍在知识目录内
 * （{@code ../} 越界一律 {@link KbSourceException}）—— manifest 可能由外部维护，不能当成可信输入。
 */
public class LocalDirKbSource implements KbSource {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Path dir;

    private final ObjectMapper mapper;

    public LocalDirKbSource(Path dir, ObjectMapper mapper) {
        this.dir = dir.toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    @Override
    public String kind() {
        return "local";
    }

    /**
     * 首启自播种：目录不存在/为空时从 classpath 复制样例（详见 {@link KbSampleSeeder}）。
     * 目录已有内容则不动；失败只 WARN。
     */
    @Override
    public void prepare() {
        new KbSampleSeeder(this.dir, this.mapper).seedIfAbsent();
    }

    @Override
    public KbSource.ManifestFetch fetchManifest(String previousEtag) throws KbSourceException {
        return fetchManifest(previousEtag, false, null);
    }

    @Override
    public KbSource.ManifestFetch fetchManifest(String previousEtag, boolean force, KbSource.RefreshBudget budget)
            throws KbSourceException {
        if (budget != null) {
            budget.check();
        }
        Path manifestPath = this.dir.resolve("manifest.json").normalize();
        if (!Files.isRegularFile(manifestPath)) {
            throw new KbSourceException("知识库目录缺少 manifest.json：" + this.dir);
        }
        byte[] raw;
        try {
            raw = Files.readAllBytes(manifestPath);
        }
        catch (IOException e) {
            throw new KbSourceException("读取 manifest.json 失败：" + e.getMessage(), e);
        }
        KbManifest manifest = KbManifest.parse(raw, this.mapper);
        manifest.validate();
        List<KbDocMeta> resolved = new ArrayList<>(manifest.docs().size());
        java.util.Map<String, String> contents = new java.util.LinkedHashMap<>();
        for (KbDocMeta meta : manifest.docs()) {
            if (budget != null) {
                budget.check();
            }
            Path file = resolve(meta);
            if (!Files.isRegularFile(file)) {
                // 单篇缺失是配置错误：整份失败比"静默少一篇"好（少一篇会让检索面无声缩水）
                throw new KbSourceException("知识文档不存在：" + meta.id() + "（" + file + "）");
            }
            byte[] body;
            try {
                body = Files.readAllBytes(file);
            }
            catch (IOException e) {
                throw new KbSourceException("读取知识文档失败：" + meta.id() + "：" + e.getMessage(), e);
            }
            String content = new String(body, StandardCharsets.UTF_8);
            String actualSha = KbHashing.sha256Text(content);
            if (meta.sha() != null && !meta.sha().isBlank() && !meta.sha().equalsIgnoreCase(actualSha)) {
                throw new KbSourceException("KB 正文 SHA-256（去 BOM 并统一 LF 后）与 manifest 声明不一致：" + meta.id());
            }
            String updatedAt = meta.updatedAt() == null || meta.updatedAt().isBlank()
                    ? fileDate(file) : meta.updatedAt();
            resolved.add(new KbDocMeta(meta.id(), meta.title(), meta.summary(), meta.tags(), meta.aliases(),
                    actualSha, updatedAt, meta.file(), meta.terms(), meta.aliasesEn()));
            contents.put(meta.id(), content);
        }
        if (budget != null) {
            budget.check();
        }
        return KbSource.ManifestFetch.modified(
                new KbManifest(manifest.version(), manifest.generatedAt(), resolved), null, null, contents);
    }

    @Override
    public String fetchContent(KbDocMeta meta) throws KbSourceException {
        return fetchContent(meta, null);
    }

    @Override
    public String fetchContent(KbDocMeta meta, KbSource.RefreshBudget budget) throws KbSourceException {
        if (budget != null) {
            budget.check();
        }
        Path file = resolve(meta);
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (budget != null) {
                budget.check();
            }
            return content;
        }
        catch (IOException e) {
            throw new KbSourceException("读取知识文档失败：" + meta.id() + "：" + e.getMessage(), e);
        }
    }

    private Path resolve(KbDocMeta meta) throws KbSourceException {
        Path resolved = this.dir.resolve(meta.effectiveFile()).normalize();
        if (!resolved.startsWith(this.dir)) {
            throw new KbSourceException("知识文档路径越界（必须是知识目录内的相对路径）：" + meta.id());
        }
        return resolved;
    }

    private static String fileDate(Path file) {
        try {
            FileTime time = Files.getLastModifiedTime(file);
            return DATE.format(Instant.ofEpochMilli(time.toMillis()).atZone(ZoneId.systemDefault()));
        }
        catch (IOException e) {
            return "";
        }
    }

}
