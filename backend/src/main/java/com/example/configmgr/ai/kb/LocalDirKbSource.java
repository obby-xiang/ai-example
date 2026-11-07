package com.example.configmgr.ai.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
            throw new KbSourceException("Knowledge directory has no manifest.json: " + this.dir);
        }
        byte[] raw;
        try {
            raw = FileUtils.readFileToByteArray(manifestPath.toFile());
        }
        catch (IOException e) {
            throw new KbSourceException("Failed to read manifest.json", e);
        }
        if (budget != null) {
            budget.check();
        }
        KbManifest manifest = KbManifest.parse(raw, this.mapper);
        manifest.validate();
        List<KbDocMeta> resolved = new ArrayList<>(manifest.docs().size());
        java.util.Map<String, String> contents = new java.util.LinkedHashMap<>();
        for (KbDocMeta meta : manifest.docs()) {
            ResolvedDoc doc = resolveDoc(meta, budget);
            resolved.add(doc.meta());
            contents.put(meta.id(), doc.content());
        }
        if (budget != null) {
            budget.check();
        }
        return KbSource.ManifestFetch.modified(
                new KbManifest(manifest.version(), manifest.generatedAt(), resolved), null, null, contents);
    }

    private ResolvedDoc resolveDoc(KbDocMeta meta, KbSource.RefreshBudget budget) throws KbSourceException {
        if (budget != null) {
            budget.check();
        }
        Path file = resolve(meta);
        if (!Files.isRegularFile(file)) {
            // 单篇缺失是配置错误：整份失败比"静默少一篇"好（少一篇会让检索面无声缩水）
            throw new KbSourceException("Knowledge document does not exist: " + meta.id() + " (" + file + ")");
        }
        byte[] body;
        try {
            body = FileUtils.readFileToByteArray(file.toFile());
        }
        catch (IOException e) {
            throw new KbSourceException("Failed to read knowledge document: " + meta.id(), e);
        }
        if (budget != null) {
            // 文件读取本身不可硬中断，只能在调用前后检查。
            budget.check();
        }
        String content = new String(body, StandardCharsets.UTF_8);
        String actualSha = KbHashing.sha256Text(content);
        if (StringUtils.isNotBlank(meta.sha()) && !meta.sha().equalsIgnoreCase(actualSha)) {
            throw new KbSourceException("KB content SHA-256 (BOM stripped, LF normalized) does not match manifest: " + meta.id());
        }
        String updatedAt = StringUtils.defaultString(meta.updatedAt());
        KbDocMeta resolved = new KbDocMeta(meta.id(), meta.title(), meta.summary(), meta.tags(), meta.aliases(),
                actualSha, updatedAt, meta.file(), meta.terms(), meta.aliasesEn());
        return new ResolvedDoc(resolved, content);
    }

    private record ResolvedDoc(KbDocMeta meta, String content) {
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
            String content = FileUtils.readFileToString(file.toFile(), StandardCharsets.UTF_8);
            if (budget != null) {
                budget.check();
            }
            return content;
        }
        catch (IOException e) {
            throw new KbSourceException("Failed to read knowledge document: " + meta.id(), e);
        }
    }

    private Path resolve(KbDocMeta meta) throws KbSourceException {
        Path resolved = this.dir.resolve(meta.effectiveFile()).normalize();
        if (!resolved.startsWith(this.dir)) {
            throw new KbSourceException("Knowledge document path escapes the knowledge directory; a relative path is required: " + meta.id());
        }
        return resolved;
    }

}
