package com.example.configmgr.ai.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

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
 * <p><b>sha 以文件实算为准</b>：manifest 的 {@code sha} 是"预期值"，local 源每次扫描都重算并与它
 * 对账；不一致只 WARN 并以实算值覆盖（否则一次忘记更新的 manifest 会让增量永远停在旧版本）。
 *
 * <p><b>路径闸</b>：{@code file} 允许写相对子路径，但解析后必须仍在知识目录内
 * （{@code ../} 越界一律 {@link KbSourceException}）—— manifest 可能由外部维护，不能当成可信输入。
 */
@Slf4j
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
        for (KbDocMeta meta : manifest.docs()) {
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
            String actualSha = KbHashing.sha256Hex(body);
            if (meta.sha() != null && !meta.sha().isBlank() && !meta.sha().equalsIgnoreCase(actualSha)) {
                log.warn("知识库 manifest 的 sha 与文件不一致，以实算为准：doc={} declared={} actual={}",
                        meta.id(), abbreviate(meta.sha()), abbreviate(actualSha));
            }
            String updatedAt = meta.updatedAt() == null || meta.updatedAt().isBlank()
                    ? fileDate(file) : meta.updatedAt();
            resolved.add(new KbDocMeta(meta.id(), meta.title(), meta.summary(), meta.tags(), meta.aliases(),
                    actualSha, updatedAt, meta.file(), meta.terms(), meta.aliasesEn()));
        }
        // local 源没有 ETag：整份是否变化由 KbService 按各篇 sha 判断（一次目录扫描足够便宜）
        return KbSource.ManifestFetch.modified(
                new KbManifest(manifest.version(), manifest.generatedAt(), resolved), null);
    }

    @Override
    public String fetchContent(KbDocMeta meta) throws KbSourceException {
        Path file = resolve(meta);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
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

    private static String abbreviate(String sha) {
        return sha == null || sha.length() <= 12 ? sha : sha.substring(0, 12) + "…";
    }
}
