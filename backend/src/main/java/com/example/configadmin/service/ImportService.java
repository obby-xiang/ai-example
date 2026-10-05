package com.example.configadmin.service;

import com.example.configadmin.common.ApiException;
import com.example.configadmin.dto.*;
import com.example.configadmin.entity.BatchStatus;
import com.example.configadmin.entity.ConfigDef;
import com.example.configadmin.entity.ConfigRow;
import com.example.configadmin.entity.ImportBatch;
import com.example.configadmin.repository.ImportBatchRepository;
import com.example.configadmin.repository.ConfigRowRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 导入配置任务：上传 → 检查 → 导入（草稿） → 发布（生效）。
 * 三步均为异步执行，检查规则 = 类型/必填/选项/数值范围/引用存在性/依赖顺序。
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    private static final Pattern SUFFIX_PATTERN = Pattern.compile("^\\(\\d+\\)$");

    private final ImportBatchRepository batchRepo;
    private final ConfigRowRepository rowRepo;
    private final ConfigDefService defService;
    private final ConfigDataService dataService;
    private final ExcelService excelService;
    private final ValidationEngine engine;
    private final ProgressHub progressHub;
    private final ObjectMapper mapper;

    @Value("${app.storage.import-dir:./data/imports}")
    private String importDir;

    @Value("${app.task.simulate-delay-ms:5}")
    private long simulateDelayMs;

    public ImportService(ImportBatchRepository batchRepo, ConfigRowRepository rowRepo,
                         ConfigDefService defService, ConfigDataService dataService,
                         ExcelService excelService, ValidationEngine engine,
                         ProgressHub progressHub, ObjectMapper mapper) {
        this.batchRepo = batchRepo;
        this.rowRepo = rowRepo;
        this.defService = defService;
        this.dataService = dataService;
        this.excelService = excelService;
        this.engine = engine;
        this.progressHub = progressHub;
        this.mapper = mapper;
    }

    // ==================== 批次管理 ====================

    public ImportBatch createBatch(String name, List<String> defCodes) {
        validateDefCodes(defCodes);
        ImportBatch b = new ImportBatch();
        b.setName(name == null || name.isBlank() ? "导入批次" : name);
        b.setDefCodesJson(dataService.writeJson(new ArrayList<>(new LinkedHashSet<>(defCodes))));
        b.setOrderJson(dataService.writeJson(defService.topoSort(new LinkedHashSet<>(defCodes))));
        b.setStatus(BatchStatus.CREATED);
        b.setMessage("批次已创建，请上传配置文件");
        b.setFilesJson(dataService.writeJson(initEntries(defCodes)));
        return batchRepo.save(b);
    }

    /** 幂等获取：同一组配置、尚未发布的批次可复用（页面刷新/重复操作友好）。 */
    public ImportBatch ensureBatch(String name, List<String> defCodes) {
        validateDefCodes(defCodes);
        Set<String> want = new TreeSet<>(defCodes);
        for (ImportBatch b : batchRepo.findAllByOrderByIdDesc()) {
            if (b.getStatus() == BatchStatus.PUBLISHED || b.getStatus() == BatchStatus.PUBLISHING) continue;
            Set<String> have = new TreeSet<>(readList(b.getDefCodesJson()));
            if (have.equals(want)) {
                return b;
            }
        }
        return createBatch(name, defCodes);
    }

    private void validateDefCodes(List<String> defCodes) {
        if (defCodes == null || defCodes.isEmpty()) {
            throw ApiException.badRequest("请至少选择一个配置");
        }
        for (String code : new LinkedHashSet<>(defCodes)) {
            ConfigDef def = defService.getByCode(code);
            if (!def.isEnabled()) {
                throw ApiException.badRequest("配置已停用：" + def.getName());
            }
        }
    }

    private List<ImportFileEntry> initEntries(List<String> defCodes) {
        List<ImportFileEntry> entries = new ArrayList<>();
        for (String code : new LinkedHashSet<>(defCodes)) {
            ConfigDef def = defService.getByCode(code);
            ImportFileEntry e = new ImportFileEntry();
            e.setDefCode(code);
            e.setDefName(def.getName());
            e.setStatus("UPLOADED");
            e.setMessage("等待上传");
            entries.add(e);
        }
        return entries;
    }

    public ImportBatch getBatch(Long id) {
        return batchRepo.findById(id).orElseThrow(() -> ApiException.notFound("导入批次不存在：" + id));
    }

    public List<ImportBatch> list() {
        return batchRepo.findAllByOrderByIdDesc();
    }

    public String topic(Long id) {
        return "import:" + id;
    }

    public ProgressEvent snapshot(ImportBatch b) {
        ProgressEvent e = new ProgressEvent(b.getStatus().name(), b.getProgress(), b.getMessage());
        e.getDetail().put("id", b.getId());
        e.getDetail().put("name", b.getName());
        e.getDetail().put("files", readEntries(b.getFilesJson()));
        e.getDetail().put("order", readList(b.getOrderJson()));
        return e;
    }

    // ==================== 上传 ====================

    /** 上传结果报告。 */
    public record UploadReport(List<ImportFileEntry> matched, List<String> unmatched) {
    }

    /** 上传文件（xlsx 或 zip），按文件名匹配配置编码；同配置覆盖。 */
    public UploadReport uploadFiles(Long batchId, List<MultipartFile> files) {
        ImportBatch batch = getBatch(batchId);
        if (batch.getStatus() == BatchStatus.PUBLISHED) {
            throw ApiException.badRequest("批次已发布，不能再上传文件");
        }
        Path dir = storageDir(batchId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new ApiException(500, "创建目录失败：" + e.getMessage());
        }

        List<ImportFileEntry> entries = readEntries(batch.getFilesJson());
        Map<String, ImportFileEntry> entryByCode = new LinkedHashMap<>();
        entries.forEach(e -> entryByCode.put(e.getDefCode(), e));

        List<ImportFileEntry> matched = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();

        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) continue;
            String original = f.getOriginalFilename() == null ? "unknown" : f.getOriginalFilename();
            String lower = original.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".zip")) {
                try (ZipInputStream zin = new ZipInputStream(f.getInputStream())) {
                    ZipEntry ze;
                    while ((ze = zin.getNextEntry()) != null) {
                        if (ze.isDirectory()) continue;
                        String entryName = Path.of(ze.getName()).getFileName().toString();
                        String base = baseName(entryName);
                        if (base == null) {
                            unmatched.add(ze.getName());
                            continue;
                        }
                        String defCode = matchDefCode(entryByCode.keySet(), base);
                        if (defCode == null) {
                            unmatched.add(ze.getName());
                            continue;
                        }
                        saveUploaded(dir, defCode, zin);
                        markUploaded(entryByCode, defCode, entryName);
                        matched.add(entryByCode.get(defCode));
                        zin.closeEntry();
                    }
                } catch (IOException e) {
                    throw new ApiException(400, "zip 解压失败（" + original + "）：" + e.getMessage());
                }
            } else if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
                String base = baseName(original);
                if (base == null) {
                    unmatched.add(original);
                    continue;
                }
                String defCode = matchDefCode(entryByCode.keySet(), base);
                if (defCode == null) {
                    unmatched.add(original);
                    continue;
                }
                try (InputStream in = f.getInputStream()) {
                    saveUploaded(dir, defCode, in);
                } catch (IOException e) {
                    throw new ApiException(400, "文件读取失败：" + e.getMessage());
                }
                markUploaded(entryByCode, defCode, original);
                matched.add(entryByCode.get(defCode));
            } else {
                unmatched.add(original);
            }
        }

        batch.setFilesJson(dataService.writeJson(new ArrayList<>(entryByCode.values())));
        batch.setMessage("已上传 " + matched.size() + " 个文件" + (unmatched.isEmpty() ? "" : "，" + unmatched.size() + " 个未匹配"));
        batchRepo.save(batch);
        return new UploadReport(matched, unmatched);
    }

    /** 文件名基名 → 配置编码匹配：{code}.xlsx 或 {code}(N).xlsx。 */
    public String matchDefCode(Collection<String> defCodes, String base) {
        String b = base.trim();
        for (String code : defCodes) {
            if (b.equalsIgnoreCase(code)) return code;
            int idx = b.indexOf('(');
            if (idx > 0 && SUFFIX_PATTERN.matcher(b.substring(idx)).matches()
                    && b.substring(0, idx).equalsIgnoreCase(code)) {
                return code;
            }
        }
        return null;
    }

    private String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? null : fileName.substring(0, dot);
    }

    private void saveUploaded(Path dir, String defCode, InputStream in) throws IOException {
        Path target = dir.resolve(defCode + ".xlsx").normalize();
        if (!target.startsWith(dir.normalize())) {
            throw new ApiException(400, "非法文件名：" + defCode);
        }
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void markUploaded(Map<String, ImportFileEntry> byCode, String defCode, String fileName) {
        ImportFileEntry e = byCode.get(defCode);
        e.setFileName(fileName);
        e.setStatus("UPLOADED");
        e.setMessage("已上传，等待检查");
        e.setRowCount(0);
        e.setErrorCount(0);
        e.setWarnCount(0);
        e.setSampleIssues(new ArrayList<>());
    }

    public Path uploadedFile(Long batchId, String defCode) {
        Path p = storageDir(batchId).resolve(defCode + ".xlsx").normalize();
        if (!Files.exists(p)) {
            throw ApiException.notFound("该配置尚未上传文件：" + defCode);
        }
        return p;
    }

    private Path storageDir(Long batchId) {
        return Path.of(importDir, String.valueOf(batchId));
    }

    // ==================== 检查 / 导入 / 发布 ====================

    /** 检查结果：有效行（按配置分组）+ 问题明细（按配置分组）+ 是否全部通过。 */
    public record CheckRun(Map<String, List<Map<String, Object>>> validRowsByDef,
                           Map<String, List<Issue>> issuesByDef,
                           boolean allPass) {
    }

    /** 执行检查（runCheck/runImport/runPublish 共用；写回批次 entries 与明细文件并推送进度）。 */
    private CheckRun performCheck(ImportBatch batch, String topic, String phaseLabel) {
        List<String> order = readList(batch.getOrderJson());
        List<ImportFileEntry> entries = readEntries(batch.getFilesJson());
        Map<String, ImportFileEntry> entryByCode = new LinkedHashMap<>();
        entries.forEach(e -> entryByCode.put(e.getDefCode(), e));

        Map<String, List<Map<String, Object>>> validRowsByDef = new LinkedHashMap<>();
        Map<String, List<Issue>> issuesByDef = new LinkedHashMap<>();
        Map<String, Set<Object>> knownRefs = dataService.buildRefValueSets(null);
        boolean allPass = true;
        int total = order.size();

        for (int i = 0; i < total; i++) {
            String defCode = order.get(i);
            ConfigDef def = defService.getByCode(defCode);
            List<FieldDef> fields = defService.parseFields(def);
            ImportFileEntry entry = entryByCode.get(defCode);
            List<Issue> issues = new ArrayList<>();
            List<Map<String, Object>> validRows = new ArrayList<>();

            publish(batch, topic, calcProgress(i, total, 0), phaseLabel + "：" + def.getName());

            Path file = storageDir(batch.getId()).resolve(defCode + ".xlsx");
            if (!Files.exists(file)) {
                entry.setStatus("ERROR");
                entry.setMessage("未上传文件，跳过");
                entry.setErrorCount(1);
                issues.add(new Issue("ERROR", 0, "-", "未上传配置文件", ""));
                allPass = false;
            } else {
                try (InputStream in = Files.newInputStream(file)) {
                    ExcelService.ParseResult parsed = excelService.parse(in, def, fields);
                    issues.addAll(parsed.issues());
                    List<Map<String, Object>> rawRows = parsed.rows();
                    int rowTotal = rawRows.size();
                    for (int k = 0; k < rowTotal; k++) {
                        Map<String, Object> raw = rawRows.get(k);
                        ValidationEngine.RowValidation rv =
                                engine.validateAndNormalize(def, fields, raw, knownRefs, k + 2);
                        issues.addAll(rv.issues());
                        if (!rv.hasErrors()) {
                            validRows.add(rv.normalized());
                        }
                        if (simulateDelayMs > 0 && k % 20 == 0) {
                            sleepQuietly(simulateDelayMs);
                        }
                        int pct = calcProgress(i, total, rowTotal == 0 ? 100 : (k + 1) * 100 / rowTotal);
                        if (pct != batch.getProgress()) {
                            publish(batch, topic, pct, String.format("%s：%s（%d/%d 行）",
                                    phaseLabel, def.getName(), k + 1, rowTotal));
                        }
                    }
                    long errors = issues.stream().filter(x -> "ERROR".equals(x.getLevel())).count();
                    long warns = issues.stream().filter(x -> "WARN".equals(x.getLevel())).count();
                    entry.setRowCount(rowTotal);
                    entry.setErrorCount((int) errors);
                    entry.setWarnCount((int) warns);
                    entry.setSampleIssues(issues.subList(0, Math.min(20, issues.size())));
                    if (errors > 0) {
                        entry.setStatus("CHECKED");
                        entry.setMessage("检查未通过：" + errors + " 个错误" + (warns > 0 ? "，" + warns + " 个警告" : ""));
                        allPass = false;
                    } else {
                        entry.setStatus("CHECKED");
                        entry.setMessage("检查通过" + (warns > 0 ? "（" + warns + " 个警告）" : ""));
                    }
                } catch (IOException e) {
                    log.error("读取上传文件失败", e);
                    entry.setStatus("ERROR");
                    entry.setMessage("文件读取失败：" + e.getMessage());
                    issues.add(new Issue("ERROR", 0, "-", "文件读取失败：" + e.getMessage(), ""));
                    allPass = false;
                }
            }

            // 保存明细（供结果查看与错误下载）
            entry.setDetailPath(defCode + ".issues.json");
            issuesByDef.put(defCode, issues);
            validRowsByDef.put(defCode, validRows);
            writeIssuesDetail(batch.getId(), defCode, issues);

            // 本配置的有效行取值并入引用集合（供后续依赖配置校验）
            for (Map<String, Object> row : validRows) {
                row.values().forEach(v -> {
                    if (v != null) knownRefs.get(defCode).add(v);
                });
            }

            batch.setFilesJson(dataService.writeJson(new ArrayList<>(entryByCode.values())));
            publish(batch, topic, calcProgress(i + 1, total, 100),
                    phaseLabel + "完成：" + def.getName() + "（错误 " + entry.getErrorCount() + "）");
        }
        return new CheckRun(validRowsByDef, issuesByDef, allPass);
    }

    /** 检查（异步入口，TaskRunner 调用）。 */
    public void runCheck(Long batchId) {
        String topic = topic(batchId);
        ImportBatch batch = getBatch(batchId);
        if (batch.getStatus() == BatchStatus.CHECKING) {
            return; // 幂等：已在执行
        }
        try {
            batch.setStatus(BatchStatus.CHECKING);
            batch.setProgress(0);
            batch.setMessage("开始检查");
            batchRepo.save(batch);
            CheckRun run = performCheck(batch, topic, "检查");
            batch.setStatus(BatchStatus.CHECKED);
            batch.setProgress(100);
            batch.setMessage(run.allPass() ? "检查全部通过，可继续导入"
                    : "检查完成，存在错误，请查看明细并修正后重新上传");
            batchRepo.save(batch);
            progressHub.complete(topic, snapshot(batch));
        } catch (Exception e) {
            log.error("检查失败 batchId={}", batchId, e);
            batch.setStatus(BatchStatus.FAILED);
            batch.setMessage("检查失败：" + e.getMessage());
            batchRepo.save(batch);
            progressHub.complete(topic, snapshot(batch));
        }
    }

    /** 导入（异步入口：再次检查 + 通过文件落库为草稿）。 */
    public void runImport(Long batchId) {
        String topic = topic(batchId);
        ImportBatch batch = getBatch(batchId);
        if (batch.getStatus() == BatchStatus.IMPORTING) {
            return; // 幂等：已在执行
        }
        try {
            batch.setStatus(BatchStatus.IMPORTING);
            batch.setProgress(0);
            batch.setMessage("开始导入（导入前再次检查）");
            batchRepo.save(batch);
            CheckRun run = performCheck(batch, topic, "导入前检查");
            List<ImportFileEntry> entries = readEntries(batch.getFilesJson());
            Map<String, ImportFileEntry> entryByCode = new LinkedHashMap<>();
            entries.forEach(e -> entryByCode.put(e.getDefCode(), e));

            int imported = 0;
            for (String defCode : readList(batch.getOrderJson())) {
                ImportFileEntry entry = entryByCode.get(defCode);
                List<Issue> issues = run.issuesByDef().getOrDefault(defCode, List.of());
                boolean hasErrors = issues.stream().anyMatch(x -> "ERROR".equals(x.getLevel()));
                if (hasErrors || !Files.exists(uploadedPath(batchId, defCode))) {
                    entry.setStatus("SKIPPED");
                    entry.setMessage("检查未通过，跳过导入（错误 " + entry.getErrorCount() + " 个）");
                } else {
                    // 幂等：先清掉本批次旧草稿再写入
                    rowRepo.deleteByDefCodeAndBatchId(defCode, String.valueOf(batchId));
                    for (Map<String, Object> row : run.validRowsByDef().getOrDefault(defCode, List.of())) {
                        ConfigRow r = new ConfigRow();
                        r.setDefCode(defCode);
                        r.setScope(row.get("__scope__") == null ? null : String.valueOf(row.get("__scope__")));
                        Map<String, Object> data = new LinkedHashMap<>(row);
                        data.remove("__scope__");
                        r.setDataJson(dataService.writeJson(data));
                        r.setBatchId(String.valueOf(batchId));
                        r.setPublished(false);
                        rowRepo.save(r);
                    }
                    entry.setStatus("IMPORTED");
                    entry.setMessage("已导入草稿（未发布）");
                    imported++;
                }
                batch.setFilesJson(dataService.writeJson(new ArrayList<>(entryByCode.values())));
                publish(batch, topic, calcProgress(readList(batch.getOrderJson()).indexOf(defCode) + 1,
                        readList(batch.getOrderJson()).size(), 100), "导入：" + entry.getDefName());
            }
            if (imported == 0) {
                batch.setStatus(BatchStatus.FAILED);
                batch.setMessage("导入失败：所有文件检查未通过");
            } else {
                batch.setStatus(BatchStatus.IMPORTED);
                batch.setMessage("导入完成：" + imported + " 个配置已入库为草稿，核查后可发布");
            }
            batch.setProgress(100);
            batchRepo.save(batch);
            progressHub.complete(topic, snapshot(batch));
        } catch (Exception e) {
            log.error("导入失败 batchId={}", batchId, e);
            batch.setStatus(BatchStatus.FAILED);
            batch.setMessage("导入失败：" + e.getMessage());
            batchRepo.save(batch);
            progressHub.complete(topic, snapshot(batch));
        }
    }

    /** 发布（异步入口：再次检查 + 全部通过后替换生效数据）。 */
    public void runPublish(Long batchId) {
        String topic = topic(batchId);
        ImportBatch batch = getBatch(batchId);
        if (batch.getStatus() == BatchStatus.PUBLISHING || batch.getStatus() == BatchStatus.PUBLISHED) {
            return; // 幂等：已在执行或已发布
        }
        try {
            batch.setStatus(BatchStatus.PUBLISHING);
            batch.setProgress(0);
            batch.setMessage("发布前最终检查");
            batchRepo.save(batch);
            CheckRun run = performCheck(batch, topic, "发布检查");
            List<ImportFileEntry> entries = readEntries(batch.getFilesJson());
            Map<String, ImportFileEntry> entryByCode = new LinkedHashMap<>();
            entries.forEach(e -> entryByCode.put(e.getDefCode(), e));

            if (!run.allPass()) {
                batch.setStatus(BatchStatus.FAILED);
                batch.setMessage("发布失败：发布检查未通过，请修正后重新导入");
                batchRepo.save(batch);
                progressHub.complete(topic, snapshot(batch));
                return;
            }

            for (String defCode : readList(batch.getOrderJson())) {
                ImportFileEntry entry = entryByCode.get(defCode);
                // 1) 本批次草稿转生效
                List<ConfigRow> draftRows = rowRepo.findByDefCodeAndBatchId(defCode, String.valueOf(batchId));
                draftRows.forEach(r -> r.setPublished(true));
                rowRepo.saveAll(draftRows);
                // 2) 同配置旧生效数据退出生效（保持唯一生效版本）
                List<ConfigRow> oldRows = rowRepo.findByDefCodeAndPublished(defCode, true);
                oldRows.stream().filter(r -> !String.valueOf(batchId).equals(r.getBatchId()))
                        .forEach(r -> r.setPublished(false));
                rowRepo.saveAll(oldRows);
                entry.setStatus("PUBLISHED");
                entry.setMessage("已发布生效（替换旧数据）");
                batch.setFilesJson(dataService.writeJson(new ArrayList<>(entryByCode.values())));
                publish(batch, topic, calcProgress(readList(batch.getOrderJson()).indexOf(defCode) + 1,
                        readList(batch.getOrderJson()).size(), 100), "发布：" + entry.getDefName());
            }
            batch.setStatus(BatchStatus.PUBLISHED);
            batch.setProgress(100);
            batch.setMessage("发布成功，全部配置已生效");
            batchRepo.save(batch);
            progressHub.complete(topic, snapshot(batch));
        } catch (Exception e) {
            log.error("发布失败 batchId={}", batchId, e);
            batch.setStatus(BatchStatus.FAILED);
            batch.setMessage("发布失败：" + e.getMessage());
            batchRepo.save(batch);
            progressHub.complete(topic, snapshot(batch));
        }
    }

    // ==================== 结果查询 ====================

    public List<ImportFileEntry> results(Long batchId) {
        return readEntries(getBatch(batchId).getFilesJson());
    }

    public List<Issue> issuesDetail(Long batchId, String defCode) {
        Path p = storageDir(batchId).resolve(defCode + ".issues.json");
        if (!Files.exists(p)) return List.of();
        try {
            return mapper.readValue(Files.readString(p), new TypeReference<List<Issue>>() {
            });
        } catch (IOException e) {
            throw new ApiException(500, "明细读取失败：" + e.getMessage());
        }
    }

    public void writeErrorsWorkbook(Long batchId, String defCode, OutputStream out) throws IOException {
        ConfigDef def = defService.getByCode(defCode);
        try (XSSFWorkbook wb = excelService.buildErrors(def.getName(), issuesDetail(batchId, defCode))) {
            excelService.write(wb, out);
        }
    }

    public List<Map<String, Object>> draftsPreview(Long batchId, String defCode) {
        return dataService.queryRows(defCode, Map.of(), null, false, String.valueOf(batchId));
    }

    private Path uploadedPath(Long batchId, String defCode) {
        return storageDir(batchId).resolve(defCode + ".xlsx");
    }

    private void writeIssuesDetail(Long batchId, String defCode, List<Issue> issues) {
        try {
            Path p = storageDir(batchId).resolve(defCode + ".issues.json");
            Files.writeString(p, dataService.writeJson(issues));
        } catch (IOException e) {
            log.warn("明细写入失败", e);
        }
    }

    // ---------- 内部 ----------

    public List<ImportFileEntry> readEntries(String json) {
        try {
            return mapper.readValue(json == null || json.isBlank() ? "[]" : json,
                    new TypeReference<List<ImportFileEntry>>() {
                    });
        } catch (Exception e) {
            throw new ApiException(500, "文件状态解析失败：" + e.getMessage());
        }
    }

    public List<String> readList(String json) {
        try {
            return mapper.readValue(json == null || json.isBlank() ? "[]" : json,
                    new TypeReference<List<String>>() {
                    });
        } catch (Exception e) {
            throw new ApiException(500, "JSON 解析失败：" + e.getMessage());
        }
    }

    private int calcProgress(int doneDefs, int totalDefs, int defPct) {
        if (totalDefs == 0) return 0;
        return Math.min(99, (doneDefs * 100 + defPct) / totalDefs);
    }

    private void publish(ImportBatch b, String topic, int progress, String message) {
        b.setProgress(progress);
        b.setMessage(message);
        batchRepo.save(b);
        ProgressEvent ev = new ProgressEvent(b.getStatus().name(), progress, message);
        ev.getDetail().put("id", b.getId());
        progressHub.publish(topic, ev);
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
