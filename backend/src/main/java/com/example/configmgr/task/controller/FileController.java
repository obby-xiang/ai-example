package com.example.configmgr.task.controller;

import com.example.configmgr.common.ApiResponse;
import com.example.configmgr.common.ResourceNotFoundException;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.excel.ExcelTemplateBuilder;
import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskFile;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.repo.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/tasks/{taskId}/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorage;
    private final TaskFileRepository taskFileRepository;
    private final TaskItemRepository taskItemRepository;
    private final TaskRepository taskRepository;
    private final DefinitionService definitionService;
    private final ExcelTemplateBuilder templateBuilder;

    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> upload(@PathVariable Long taskId,
                                                    @RequestParam("file") MultipartFile file) throws Exception {
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.xlsx";

        if (originalName.toLowerCase().endsWith(".zip")) {
            // ZIP 批量上传：解压后按文件名逐个匹配配置编码
            List<String[]> entries = readZipEntries(file.getBytes());
            List<Map<String, String>> matched = new ArrayList<>();
            List<String> unmatched = new ArrayList<>();
            for (String[] entry : entries) {
                String name = entry[0];
                if (!name.toLowerCase().endsWith(".xlsx")) {
                    unmatched.add(name + "（非 Excel 文件）");
                    continue;
                }
                String defCode = matchDefCode(taskId, name);
                if (defCode == null) {
                    unmatched.add(name + "（无法匹配配置编码）");
                    continue;
                }
                storeUploadFile(taskId, defCode, name, entry[1]);
                matched.add(Map.of("defCode", defCode, "fileName", name));
            }
            if (matched.isEmpty()) {
                throw new IllegalArgumentException(
                        "压缩包中没有可匹配的文件，请确认文件名包含配置编码（如 CURRENCY.xlsx）。未匹配: " + unmatched);
            }
            return ApiResponse.ok(Map.of(
                    "matchedFiles", matched,
                    "unmatchedFiles", unmatched,
                    "count", matched.size()));
        }

        if (!originalName.toLowerCase().endsWith(".xlsx")) {
            throw new IllegalArgumentException("仅支持 .xlsx 或 .zip 文件");
        }

        String defCode = matchDefCode(taskId, originalName);
        if (defCode == null) {
            throw new IllegalArgumentException(
                    "无法从文件名匹配配置编码，请使用“编码_xxx.xlsx”命名（如 CURRENCY.xlsx），或先下载模板编辑后上传");
        }
        String storagePath = fileStorage.store(file.getInputStream(), originalName);
        saveUploadFile(taskId, defCode, originalName, storagePath);

        return ApiResponse.ok(Map.of(
                "defCode", defCode,
                "fileName", originalName,
                "matchedFiles", List.of(Map.of("defCode", defCode, "fileName", originalName)),
                "count", 1));
    }

    private void storeUploadFile(Long taskId, String defCode, String fileName, byte[] content) throws IOException {
        String storagePath = fileStorage.store(new java.io.ByteArrayInputStream(content), fileName);
        saveUploadFile(taskId, defCode, fileName, storagePath);
    }

    private void saveUploadFile(Long taskId, String defCode, String fileName, String storagePath) {
        Optional<TaskFile> existing = taskFileRepository
                .findByTaskIdAndDefCodeAndFileType(taskId, defCode, "UPLOAD");
        TaskFile tf = existing.orElseGet(TaskFile::new);
        tf.setTaskId(taskId);
        tf.setDefCode(defCode);
        tf.setFileType("UPLOAD");
        tf.setStoragePath(storagePath);
        tf.setOriginalPath(storagePath);
        tf.setFileName(fileName);
        taskFileRepository.save(tf);
    }

    /**
     * 解压 zip 并返回 [文件名, 内容] 列表。文件名先按 UTF-8 解析，
     * 若出现乱码（替换符 U+FFFD）则按 GBK 重新解析，兼容 Windows 压缩工具。
     */
    private List<String[]> readZipEntries(byte[] zipBytes) throws IOException {
        List<String[]> utf8 = extractZip(zipBytes, StandardCharsets.UTF_8);
        if (utf8.stream().anyMatch(e -> e[0].contains("\uFFFD"))) {
            return extractZip(zipBytes, Charset.forName("GBK"));
        }
        return utf8;
    }

    private List<String[]> extractZip(byte[] zipBytes, Charset charset) throws IOException {
        List<String[]> result = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes), charset)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                result.add(new String[]{entry.getName(), zis.readAllBytes()});
                zis.closeEntry();
            }
        }
        return result;
    }

    @GetMapping("/{defCode}")
    public ResponseEntity<byte[]> download(@PathVariable Long taskId,
                                             @PathVariable String defCode) throws Exception {
        Optional<TaskFile> fileOpt = taskFileRepository
                .findByTaskIdAndDefCodeAndFileType(taskId, defCode, "EXPORT");
        if (fileOpt.isEmpty()) {
            fileOpt = taskFileRepository.findByTaskIdAndDefCodeAndFileType(taskId, defCode, "UPLOAD");
        }
        if (fileOpt.isEmpty()) {
            throw ResourceNotFoundException.of("文件", defCode);
        }
        byte[] bytes = fileStorage.read(fileOpt.get().getStoragePath());
        String filename = URLEncoder.encode(fileOpt.get().getFileName() != null
                ? fileOpt.get().getFileName() : defCode + ".xlsx", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    @PutMapping("/{defCode}")
    public ApiResponse<?> save(@PathVariable Long taskId,
                                @PathVariable String defCode,
                                @RequestBody byte[] data) throws Exception {
        Optional<TaskFile> fileOpt = taskFileRepository
                .findByTaskIdAndDefCodeAndFileType(taskId, defCode, "EXPORT");
        if (fileOpt.isEmpty()) {
            fileOpt = taskFileRepository.findByTaskIdAndDefCodeAndFileType(taskId, defCode, "UPLOAD");
        }
        TaskFile tf;
        if (fileOpt.isEmpty()) {
            tf = new TaskFile();
            tf.setTaskId(taskId);
            tf.setDefCode(defCode);
            tf.setFileType("EXPORT");
            String storagePath = fileStorage.newPath(".xlsx");
            tf.setStoragePath(storagePath);
        } else {
            tf = fileOpt.get();
        }
        fileStorage.write(tf.getStoragePath(), data);
        taskFileRepository.save(tf);
        return ApiResponse.ok();
    }

    @GetMapping("/download-all")
    public ResponseEntity<byte[]> downloadAll(@PathVariable Long taskId) throws Exception {
        List<TaskFile> files = taskFileRepository.findByTaskId(taskId).stream()
                .filter(f -> "EXPORT".equals(f.getFileType()) || "UPLOAD".equals(f.getFileType()))
                .toList();
        byte[] zip = buildZip(taskId, files);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=export-" + taskId + ".zip")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(zip);
    }

    /**
     * 下载勾选的导出文件（打包 zip）。
     */
    @GetMapping("/download")
    public ResponseEntity<byte[]> downloadSelected(@PathVariable Long taskId,
                                                     @RequestParam List<String> codes) throws Exception {
        List<TaskFile> files = taskFileRepository.findByTaskId(taskId).stream()
                .filter(f -> codes.contains(f.getDefCode())
                        && ("EXPORT".equals(f.getFileType()) || "UPLOAD".equals(f.getFileType())))
                .toList();
        if (files.isEmpty()) {
            throw ResourceNotFoundException.of("导出文件", String.join(",", codes));
        }
        byte[] zip = buildZip(taskId, files);
        String filename = files.size() == 1
                ? (files.get(0).getFileName() != null ? files.get(0).getFileName() : files.get(0).getDefCode() + ".xlsx")
                : "export-selected-" + taskId + ".zip";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8))
                .contentType(MediaType.parseMediaType(files.size() == 1
                        ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        : "application/zip"))
                .body(zip);
    }

    private byte[] buildZip(Long taskId, List<TaskFile> files) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        // 显式 UTF-8：中文文件名在 Windows 压缩工具下不乱码
        try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            for (TaskFile tf : files) {
                String name = tf.getFileName() != null ? tf.getFileName() : tf.getDefCode() + ".xlsx";
                zos.putNextEntry(new ZipEntry(name));
                zos.write(fileStorage.read(tf.getStoragePath()));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    @GetMapping("/templates")
    public ResponseEntity<byte[]> downloadTemplates(@PathVariable Long taskId,
                                                      @RequestParam(required = false) List<String> codes) throws Exception {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> ResourceNotFoundException.of("任务", taskId));
        List<TaskItem> items = taskItemRepository.findByTaskIdOrderBySortOrder(taskId);
        List<String> targetCodes = codes != null && !codes.isEmpty() ? codes
                : items.stream().map(TaskItem::getDefCode).toList();

        if (targetCodes.size() == 1) {
            var def = definitionService.findByCode(targetCodes.get(0));
            byte[] bytes = templateBuilder.build(def);
            String filename = URLEncoder.encode(def.getCode() + "_" + def.getName() + ".xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(bytes);
        }

        // Multiple: zip
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            for (String code : targetCodes) {
                var def = definitionService.findByCode(code);
                byte[] tmpl = templateBuilder.build(def);
                zos.putNextEntry(new ZipEntry(code + "_" + def.getName() + ".xlsx"));
                zos.write(tmpl);
                zos.closeEntry();
            }
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=templates-" + taskId + ".zip")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(bos.toByteArray());
    }

    private String matchDefCode(Long taskId, String filename) {
        List<TaskItem> items = taskItemRepository.findByTaskIdOrderBySortOrder(taskId);
        String base = filename.replaceAll("(?i)\\.xlsx?$", "").toUpperCase();
        // Match by code prefix in filename（取最长前缀匹配，避免 PROJECT_PRICE 被 PROJECT 抢先命中）
        String best = null;
        int bestLen = 0;
        for (TaskItem item : items) {
            String code = item.getDefCode().toUpperCase();
            if (base.equals(code) || base.startsWith(code + "_") || base.startsWith(code + "-")) {
                if (code.length() > bestLen) {
                    best = item.getDefCode();
                    bestLen = code.length();
                }
            }
        }
        // 无法匹配时返回 null，由调用方给出明确提示（不再静默挂到第一个配置项）
        return best;
    }
}
