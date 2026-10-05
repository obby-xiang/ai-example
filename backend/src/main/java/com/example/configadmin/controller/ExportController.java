package com.example.configadmin.controller;

import com.example.configadmin.common.R;
import com.example.configadmin.dto.ExportCreateRequest;
import com.example.configadmin.dto.ProgressEvent;
import com.example.configadmin.entity.ExportTask;
import com.example.configadmin.service.ExportService;
import com.example.configadmin.service.ProgressHub;
import com.example.configadmin.service.TaskRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 导出配置任务。 */
@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final ExportService exportService;
    private final TaskRunner taskRunner;
    private final ProgressHub progressHub;

    public ExportController(ExportService exportService, TaskRunner taskRunner, ProgressHub progressHub) {
        this.exportService = exportService;
        this.taskRunner = taskRunner;
        this.progressHub = progressHub;
    }

    /** 创建并启动导出任务。 */
    @PostMapping("/tasks")
    public R<ProgressEvent> create(@RequestBody ExportCreateRequest req) {
        ExportTask t = exportService.create(req.defCodes(), req.conditions());
        taskRunner.runExport(t.getId());
        return R.ok(exportService.snapshot(exportService.get(t.getId())));
    }

    @GetMapping("/tasks")
    public R<List<Map<String, Object>>> list() {
        return R.ok(exportService.list().stream().map(t -> {
            ProgressEvent e = exportService.snapshot(t);
            e.getDetail().put("createdAt", t.getCreatedAt() == null ? "" : t.getCreatedAt().toString());
            return e;
        }).toList());
    }

    /** 任务快照（刷新恢复进度）。 */
    @GetMapping("/tasks/{id}")
    public R<ProgressEvent> get(@PathVariable Long id) {
        return R.ok(exportService.snapshot(exportService.get(id)));
    }

    /** SSE 进度订阅。 */
    @GetMapping(value = "/tasks/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable Long id) {
        ExportTask t = exportService.get(id);
        return progressHub.subscribe(exportService.topic(id), () -> exportService.snapshot(t));
    }

    /** 下载单个导出文件。 */
    @GetMapping("/tasks/{id}/files/{defCode}")
    public ResponseEntity<byte[]> downloadFile(@PathVariable Long id, @PathVariable String defCode) throws IOException {
        Path p = exportService.filePath(id, defCode);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + defCode + ".xlsx\"; filename*=UTF-8''" + defCode + ".xlsx")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(Files.readAllBytes(p));
    }

    /** 打包下载（选定或全部）。 */
    @GetMapping("/tasks/{id}/package")
    public ResponseEntity<byte[]> packageZip(@PathVariable Long id,
                                             @RequestParam(required = false) List<String> defCodes) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        exportService.writeZip(id, defCodes, bytes);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"export-task-" + id + ".zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(bytes.toByteArray());
    }
}
