package com.example.configmgr.task.controller;

import com.example.configmgr.common.CodedIllegalArgumentException;
import com.example.configmgr.common.ErrorCodes;

import com.example.configmgr.common.ApiResponse;
import com.example.configmgr.common.ConflictException;
import com.example.configmgr.common.ResourceNotFoundException;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.excel.ExcelTemplateBuilder;
import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.file.StoredFile;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskFile;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.repo.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
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
    @Transactional
    public ApiResponse<Map<String, Object>> upload(@PathVariable Long taskId,
                                                    @RequestParam("file") MultipartFile file) throws Exception {
        lockTask(taskId);
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.xlsx";

        if (originalName.toLowerCase().endsWith(".zip")) {
            // ZIP 批量上传：解压后按文件名逐个匹配配置编码
            List<ZipFileEntry> entries = readZipEntries(file.getBytes());
            List<Map<String, String>> matched = new ArrayList<>();
            List<String> unmatched = new ArrayList<>();
            List<String> storedPaths = new ArrayList<>();
            try {
                for (ZipFileEntry entry : entries) {
                    String name = entry.name();
                    if (!name.toLowerCase().endsWith(".xlsx")) {
                        unmatched.add(name + "（非 Excel 文件）");
                        continue;
                    }
                    String defCode = matchDefCode(taskId, name);
                    if (defCode == null) {
                        unmatched.add(name + "（无法匹配配置编码）");
                        continue;
                    }
                    storedPaths.add(storeUploadFile(taskId, defCode, name, entry.content()));
                    matched.add(Map.of("defCode", defCode, "fileName", name));
                }
            } catch (RuntimeException e) {
                // 任一条目落库失败（典型：任务被并发删除导致 FK 冲突）时，清理本次已写盘的文件。
                storedPaths.forEach(fileStorage::delete);
                throw e;
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
            throw new CodedIllegalArgumentException(ErrorCodes.FILE_TYPE_UNSUPPORTED, "仅支持 .xlsx 或 .zip 文件");
        }

        String defCode = matchDefCode(taskId, originalName);
        if (defCode == null) {
            throw new IllegalArgumentException(
                    "无法从文件名匹配配置编码，请使用“编码_xxx.xlsx”命名（如 CURRENCY.xlsx），或先下载模板编辑后上传");
        }
        StoredFile stored = fileStorage.store(file.getInputStream(), originalName);
        // 落库失败之外还有"提交期回滚"（外层事务回滚/提交失败）：新文件不能永久残留。
        fileStorage.deleteAfterRollback(stored.key());
        try {
            saveUploadFile(taskId, defCode, originalName, stored.key());
        } catch (DataIntegrityViolationException e) {
            // 预检后任务被并发删除：FK 拒绝落行，清理刚写入的文件并返回 404 语义。
            fileStorage.delete(stored.key());
            throw ResourceNotFoundException.of("任务", taskId);
        } catch (RuntimeException e) {
            // 其它落库失败同样清理本次新文件（避免“库没写、盘上多一份”）。
            fileStorage.delete(stored.key());
            throw e;
        }

        return ApiResponse.ok(Map.of(
                "defCode", defCode,
                "fileName", originalName,
                "matchedFiles", List.of(Map.of("defCode", defCode, "fileName", originalName)),
                "count", 1));
    }

    private String storeUploadFile(Long taskId, String defCode, String fileName, byte[] content) throws IOException {
        StoredFile stored = fileStorage.store(new ByteArrayInputStream(content), fileName);
        // 整包任一条目失败都会回滚本事务：新文件按回滚回收（见下方 catch 的即时清理）。
        fileStorage.deleteAfterRollback(stored.key());
        try {
            saveUploadFile(taskId, defCode, fileName, stored.key());
        } catch (DataIntegrityViolationException e) {
            fileStorage.delete(stored.key());
            throw ResourceNotFoundException.of("任务", taskId);
        } catch (RuntimeException e) {
            fileStorage.delete(stored.key());
            throw e;
        }
        return stored.key();
    }

    private void saveUploadFile(Long taskId, String defCode, String fileName, String storageKey) {
        Optional<TaskFile> existing = taskFileRepository
                .findByTaskIdAndDefCodeAndFileType(taskId, defCode, "UPLOAD");
        TaskFile tf = existing.orElseGet(TaskFile::new);
        String previousKey = existing.map(TaskFile::getStoragePath).orElse(null);
        tf.setTaskId(taskId);
        tf.setDefCode(defCode);
        tf.setFileType("UPLOAD");
        tf.setStoragePath(storageKey);
        tf.setOriginalPath(storageKey);
        tf.setFileName(fileName);
        taskFileRepository.save(tf);
        // 换引用的旧件只在本次投递提交成功后清（失败/回滚时旧件仍是当前引用的兜底）。
        if (previousKey != null && !previousKey.equals(storageKey)) {
            fileStorage.deleteAfterCommit(previousKey);
        }
    }

    /**
     * 解压 zip 并返回条目列表。文件名先按 UTF-8 解析，
     * 若出现乱码（替换符 U+FFFD）则按 GBK 重新解析，兼容 Windows 压缩工具。
     */
    private List<ZipFileEntry> readZipEntries(byte[] zipBytes) throws IOException {
        List<ZipFileEntry> utf8 = extractZip(zipBytes, StandardCharsets.UTF_8);
        if (utf8.stream().anyMatch(e -> e.name().contains("\uFFFD"))) {
            return extractZip(zipBytes, Charset.forName("GBK"));
        }
        return utf8;
    }

    private List<ZipFileEntry> extractZip(byte[] zipBytes, Charset charset) throws IOException {
        List<ZipFileEntry> result = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), charset)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                result.add(new ZipFileEntry(entry.getName(), zis.readAllBytes()));
                zis.closeEntry();
            }
        }
        return result;
    }

    private record ZipFileEntry(String name, byte[] content) {}

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
    @Transactional
    public ApiResponse<?> save(@PathVariable Long taskId,
                                @PathVariable String defCode,
                                @RequestBody byte[] data) throws Exception {
        lockTask(taskId);
        Optional<TaskFile> fileOpt = taskFileRepository
                .findByTaskIdAndDefCodeAndFileType(taskId, defCode, "EXPORT");
        if (fileOpt.isEmpty()) {
            fileOpt = taskFileRepository.findByTaskIdAndDefCodeAndFileType(taskId, defCode, "UPLOAD");
        }
        TaskFile tf;
        boolean createdNew = fileOpt.isEmpty();
        String previousKey = null;
        if (createdNew) {
            tf = new TaskFile();
            tf.setTaskId(taskId);
            tf.setDefCode(defCode);
            tf.setFileType("EXPORT");
        } else {
            tf = fileOpt.get();
            previousKey = tf.getStoragePath();
        }
        // 一律写新键再换引用：就地覆盖会让"回滚"回到不了旧内容（盘上是新内容、库是旧元数据）。
        StoredFile stored = fileStorage.allocate(".xlsx");
        // 先登记回滚回收、再写盘：写盘中途 IOException 留下的半截文件同样要被回收（M3）。
        fileStorage.deleteAfterRollback(stored.key());
        fileStorage.write(stored.path(), data);
        tf.setStoragePath(stored.key());
        if (previousKey != null && previousKey.equals(tf.getOriginalPath())) {
            // originalPath 原本指向即将被删的旧件：让它跟随存活件，别留悬空引用（M4）。
            tf.setOriginalPath(stored.key());
        }
        try {
            // 显式 flush：与在途导出作业撞 @Version 时异常在这里发生，才能映射成 409 而不是提交期 500。
            taskFileRepository.saveAndFlush(tf);
        } catch (ObjectOptimisticLockingFailureException e) {
            // 版本冲突 = 并发写同一行（典型：导出作业刚更新了该 EXPORT 行）。数据面无一行落库，
            // 新件随回滚回收、旧件与旧引用原样保留；给客户端可重试的 409。
            throw new ConflictException(ErrorCodes.ILLEGAL_STATE,
                    "文件正被并发写入（可能有导出作业正在生成该配置项），请稍后重试");
        } catch (DataIntegrityViolationException e) {
            // 预检后任务被并发删除：FK 拒绝落行。新件从未被引用，直接删；旧件未动。
            fileStorage.delete(stored.key());
            throw ResourceNotFoundException.of("任务", taskId);
        }
        if (previousKey != null && !previousKey.equals(stored.key())) {
            fileStorage.deleteAfterCommit(previousKey);
        }
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
     * 下载勾选的文件：<b>单个编码回该文件本身的字节</b>，多编码才打包 zip。
     *
     * <p>修复前不论命中几个文件一律 {@code buildZip}：单编码下载回的是"再包一层的 zip"，
     * 字节与库里的文件不同（多实例验收 shared-file-download：A 上传 sha256=4172fd…、
     * B 下载 sha256=2207a8…），而响应头仍声称 xlsx —— 调用方按字节比对、按类型解析都会错。
     * 前端 {@code api/tasks.ts#downloadSelected} 的契约本来就是"单个 xlsx 或 zip"。
     *
     * <p>每个编码只取<b>一个</b>文件（EXPORT 优先、回退 UPLOAD，与 {@code GET /files/{defCode}}
     * 同一优先级）：修复前同一编码若同时存在 EXPORT 与 UPLOAD 行，zip 里会出现两个同名 entry。
     */
    @GetMapping("/download")
    public ResponseEntity<byte[]> downloadSelected(@PathVariable Long taskId,
                                                     @RequestParam List<String> codes) throws Exception {
        List<TaskFile> selected = selectOnePerCode(taskFileRepository.findByTaskId(taskId), codes);
        if (selected.isEmpty()) {
            throw ResourceNotFoundException.of("导出文件", String.join(",", codes));
        }
        if (selected.size() == 1) {
            TaskFile only = selected.get(0);
            byte[] bytes = fileStorage.read(only.getStoragePath());
            String filename = only.getFileName() != null ? only.getFileName() : only.getDefCode() + ".xlsx";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8))
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(bytes);
        }
        byte[] zip = buildZip(taskId, selected);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + URLEncoder.encode("export-selected-" + taskId + ".zip",
                                StandardCharsets.UTF_8))
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(zip);
    }

    /** 每个请求编码挑一个文件（EXPORT 优先、回退 UPLOAD；保持请求顺序、编码去重）。 */
    private static List<TaskFile> selectOnePerCode(List<TaskFile> taskFiles, List<String> codes) {
        List<TaskFile> selected = new ArrayList<>();
        for (String code : codes) {
            if (selected.stream().anyMatch(f -> f.getDefCode().equals(code))) {
                continue;
            }
            TaskFile pick = taskFiles.stream()
                    .filter(f -> code.equals(f.getDefCode()) && "EXPORT".equals(f.getFileType()))
                    .findFirst()
                    .or(() -> taskFiles.stream()
                            .filter(f -> code.equals(f.getDefCode()) && "UPLOAD".equals(f.getFileType()))
                            .findFirst())
                    .orElse(null);
            if (pick != null) {
                selected.add(pick);
            }
        }
        return selected;
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

    /**
     * R1-13/I-4：写盘前锁任务行（而不是只 exists 一次）。
     *
     * <p>只 exists 仍有 C1 同类窗口：INSERT 已发出未提交 → 删除提交看不到 → INSERT 后提交成孤儿；
     * H2 FK 不挡这个窗口。与 TaskService.delete 共用 task 行锁后，先写则删除等待并级联，
     * 先删则这里拿不到任务行直接 404。FK 冲突的兜底仍由各写入点 catch 后映射 404。
     */
    private void lockTask(Long taskId) {
        taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> ResourceNotFoundException.of("任务", taskId));
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
