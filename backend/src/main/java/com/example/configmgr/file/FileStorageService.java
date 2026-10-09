package com.example.configmgr.file;

import com.example.configmgr.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    /** 流式摘要的读缓冲（顺序读，不整文件进内存）。 */
    private static final int DIGEST_BUFFER_BYTES = 8192;

    private final AppProperties appProperties;

    private Path baseDir() {
        Path dir = Path.of(appProperties.getFile().getStoragePath());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建文件存储目录: " + dir, e);
        }
        return dir;
    }

    public String store(InputStream in, String filename) throws IOException {
        String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.')) : "";
        String storedName = UUID.randomUUID() + ext;
        Path target = baseDir().resolve(storedName);
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        log.debug("Stored file {} -> {}", filename, target);
        return target.toAbsolutePath().toString();
    }

    public byte[] read(String storagePath) throws IOException {
        return Files.readAllBytes(Path.of(storagePath));
    }

    /**
     * 文件摘要（T3-4）：<b>流式</b> sha256 + 字节数，一次顺序读。
     *
     * <h2>为什么必须流式（S3-5 的施工口径）</h2>
     * {@link #read(String)} 是 {@code Files.readAllBytes}（全量进内存）。预检查/导入的指纹计算
     * 若复用它，百 MB 级上传件会<b>再</b>额外占用一份等量堆内存（文件已被 {@code excelReader} 读过一次）。
     * 本方法用 {@link DigestInputStream} + 8KB 缓冲顺序读，只经手缓冲块，不持有整文件。
     *
     * @return {@code sha256}（小写十六进制）+ {@code size}（真实字节数，与摘要同一次读得出）
     */
    public FileDigest digest(String storagePath) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("运行环境缺少 SHA-256 实现", ex);
        }
        long size = 0L;
        byte[] buffer = new byte[DIGEST_BUFFER_BYTES];
        try (InputStream raw = Files.newInputStream(Path.of(storagePath));
                DigestInputStream in = new DigestInputStream(raw, md)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                size += read;
            }
        }
        return new FileDigest(HexFormat.of().formatHex(md.digest()), size);
    }

    /** 文件摘要值对象（sha256 小写十六进制 + 字节数）。 */
    public record FileDigest(String sha256, long size) {
    }

    public void write(String storagePath, byte[] data) throws IOException {
        Path target = Path.of(storagePath);
        Files.createDirectories(target.getParent());
        Files.write(target, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public String newPath(String ext) {
        String storedName = UUID.randomUUID() + ext;
        return baseDir().resolve(storedName).toAbsolutePath().toString();
    }

    public void delete(String storagePath) {
        try {
            Files.deleteIfExists(Path.of(storagePath));
        } catch (IOException e) {
            log.warn("Failed to delete file {}: {}", storagePath, e.getMessage());
        }
    }
}
