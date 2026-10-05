package com.example.configadmin.controller;

import com.example.configadmin.common.R;
import com.example.configadmin.dto.ImportFileEntry;
import com.example.configadmin.dto.Issue;
import com.example.configadmin.dto.ProgressEvent;
import com.example.configadmin.entity.ConfigDef;
import com.example.configadmin.entity.ImportBatch;
import com.example.configadmin.service.*;
import com.example.configadmin.dto.BatchCreateRequest;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 导入配置任务：模板下载、上传、检查、导入、发布。 */
@RestController
@RequestMapping("/api/import")
public class ImportController {

    private final ImportService importService;
    private final ConfigDefService defService;
    private final ExcelService excelService;
    private final TaskRunner taskRunner;
    private final ProgressHub progressHub;

    public ImportController(ImportService importService, ConfigDefService defService,
                            ExcelService excelService, TaskRunner taskRunner, ProgressHub progressHub) {
        this.importService = importService;
        this.defService = defService;
        this.excelService = excelService;
        this.taskRunner = taskRunner;
        this.progressHub = progressHub;
    }

    /** 模板下载：单个 xlsx 或多个 zip（zip 条目名 = {编码}.xlsx）。 */
    @GetMapping("/templates")
    public ResponseEntity<byte[]> templates(@RequestParam List<String> defCodes,
                                            @RequestParam(defaultValue = "false") boolean zip) throws IOException {
        if (defCodes == null || defCodes.isEmpty()) {
            throw com.example.configadmin.common.ApiException.badRequest("请选择配置");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!zip && defCodes.size() == 1) {
            ConfigDef def = defService.getByCode(defCodes.get(0));
            try (XSSFWorkbook wb = excelService.buildTemplate(def, defService.parseFields(def))) {
                excelService.write(wb, out);
            }
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + def.getCode() + ".xlsx\"")
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        }
        try (ZipOutputStream zipOut = new ZipOutputStream(out)) {
            for (String code : defCodes) {
                ConfigDef def = defService.getByCode(code);
                ByteArrayOutputStream one = new ByteArrayOutputStream();
                try (XSSFWorkbook wb = excelService.buildTemplate(def, defService.parseFields(def))) {
                    excelService.write(wb, one);
                }
                zipOut.putNextEntry(new ZipEntry(code + ".xlsx"));
                zipOut.write(one.toByteArray());
                zipOut.closeEntry();
            }
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"templates.zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(out.toByteArray());
    }

    // ---------- 批次 ----------

    @PostMapping("/batches")
    public R<ProgressEvent> createBatch(@RequestBody BatchCreateRequest req) {
        ImportBatch b = importService.createBatch(req.name(), req.defCodes());
        return R.ok(importService.snapshot(b));
    }

    @PostMapping("/batches/ensure")
    public R<ProgressEvent> ensureBatch(@RequestBody BatchCreateRequest req) {
        ImportBatch b = importService.ensureBatch(req.name(), req.defCodes());
        return R.ok(importService.snapshot(b));
    }

    @GetMapping("/batches")
    public R<List<Map<String, Object>>> list() {
        return R.ok(importService.list().stream().map(b -> {
            ProgressEvent e = importService.snapshot(b);
            e.getDetail().put("createdAt", b.getCreatedAt() == null ? "" : b.getCreatedAt().toString());
            e.getDetail().put("defCodes", importService.readList(b.getDefCodesJson()));
            return e;
        }).toList());
    }

    @GetMapping("/batches/{id}")
    public R<ProgressEvent> get(@PathVariable Long id) {
        return R.ok(importService.snapshot(importService.getBatch(id)));
    }

    @GetMapping(value = "/batches/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable Long id) {
        ImportBatch b = importService.getBatch(id);
        return progressHub.subscribe(importService.topic(id), () -> importService.snapshot(b));
    }

    /** 上传文件（xlsx / zip，多文件）。 */
    @PostMapping(value = "/batches/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<ImportService.UploadReport> upload(@PathVariable Long id,
                                                @RequestParam("files") List<MultipartFile> files) {
        return R.ok(importService.uploadFiles(id, files));
    }

    /** 下载已上传文件（在线编辑后再覆盖上传）。 */
    @GetMapping("/batches/{id}/files/{defCode}")
    public ResponseEntity<byte[]> downloadUploaded(@PathVariable Long id, @PathVariable String defCode)
            throws IOException {
        Path p = importService.uploadedFile(id, defCode);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + defCode + ".xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(Files.readAllBytes(p));
    }

    // ---------- 检查 / 导入 / 发布（异步） ----------

    @PostMapping("/batches/{id}/check")
    public R<?> check(@PathVariable Long id) {
        ImportBatch b = importService.getBatch(id);
        if (b.getStatus() == com.example.configadmin.entity.BatchStatus.PUBLISHED) {
            throw com.example.configadmin.common.ApiException.badRequest("批次已发布");
        }
        taskRunner.runCheck(id);
        return R.ok();
    }

    @PostMapping("/batches/{id}/import")
    public R<?> doImport(@PathVariable Long id) {
        ImportBatch b = importService.getBatch(id);
        if (b.getStatus() == com.example.configadmin.entity.BatchStatus.PUBLISHED) {
            throw com.example.configadmin.common.ApiException.badRequest("批次已发布");
        }
        taskRunner.runImport(id);
        return R.ok();
    }

    @PostMapping("/batches/{id}/publish")
    public R<?> publish(@PathVariable Long id) {
        ImportBatch b = importService.getBatch(id);
        if (b.getStatus() != com.example.configadmin.entity.BatchStatus.IMPORTED) {
            throw com.example.configadmin.common.ApiException.badRequest("只有完成导入的批次才能发布");
        }
        taskRunner.runPublish(id);
        return R.ok();
    }

    // ---------- 结果 ----------

    @GetMapping("/batches/{id}/results")
    public R<List<ImportFileEntry>> results(@PathVariable Long id) {
        return R.ok(importService.results(id));
    }

    @GetMapping("/batches/{id}/issues/{defCode}")
    public R<List<Issue>> issues(@PathVariable Long id, @PathVariable String defCode) {
        return R.ok(importService.issuesDetail(id, defCode));
    }

    @GetMapping("/batches/{id}/errors/{defCode}")
    public ResponseEntity<byte[]> errorsWorkbook(@PathVariable Long id, @PathVariable String defCode)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        importService.writeErrorsWorkbook(id, defCode, out);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + defCode + "-errors.xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(out.toByteArray());
    }

    @GetMapping("/batches/{id}/drafts/{defCode}")
    public R<List<Map<String, Object>>> drafts(@PathVariable Long id, @PathVariable String defCode) {
        return R.ok(importService.draftsPreview(id, defCode));
    }
}
