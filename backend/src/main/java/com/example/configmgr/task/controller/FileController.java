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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
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
        String defCode = matchDefCode(taskId, originalName);

        String storagePath = fileStorage.store(file.getInputStream(), originalName);

        Optional<TaskFile> existing = taskFileRepository
                .findByTaskIdAndDefCodeAndFileType(taskId, defCode, "UPLOAD");
        TaskFile tf = existing.orElseGet(TaskFile::new);
        tf.setTaskId(taskId);
        tf.setDefCode(defCode);
        tf.setFileType("UPLOAD");
        tf.setStoragePath(storagePath);
        tf.setOriginalPath(storagePath);
        tf.setFileName(originalName);
        taskFileRepository.save(tf);

        return ApiResponse.ok(Map.of("defCode", defCode, "fileName", originalName, "matched", true));
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
        List<TaskFile> files = taskFileRepository.findByTaskId(taskId);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (TaskFile tf : files) {
                if (!"EXPORT".equals(tf.getFileType()) && !"UPLOAD".equals(tf.getFileType())) continue;
                String name = tf.getFileName() != null ? tf.getFileName() : tf.getDefCode() + ".xlsx";
                zos.putNextEntry(new ZipEntry(name));
                zos.write(fileStorage.read(tf.getStoragePath()));
                zos.closeEntry();
            }
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=export-" + taskId + ".zip")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(bos.toByteArray());
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
        // Match by code prefix in filename
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
        if (best != null) return best;
        // Fallback: first item
        return items.isEmpty() ? "UNKNOWN" : items.get(0).getDefCode();
    }
}
