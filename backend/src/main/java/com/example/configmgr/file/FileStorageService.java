package com.example.configmgr.file;

import com.example.configmgr.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

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
