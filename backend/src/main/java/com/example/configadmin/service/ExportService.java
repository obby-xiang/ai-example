package com.example.configadmin.service;

import com.example.configadmin.common.ApiException;
import com.example.configadmin.dto.Cond;
import com.example.configadmin.dto.ExportFileEntry;
import com.example.configadmin.dto.ProgressEvent;
import com.example.configadmin.entity.ConfigDef;
import com.example.configadmin.entity.ExportTask;
import com.example.configadmin.repository.ExportTaskRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 导出配置任务：异步执行、双级进度（任务级+文件级）、SSE 推送、结果落盘。
 */
@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    private final ExportTaskRepository taskRepo;
    private final ConfigDefService defService;
    private final ConfigDataService dataService;
    private final ExcelService excelService;
    private final ProgressHub progressHub;
    private final ObjectMapper mapper;

    @Value("${app.storage.export-dir:./data/exports}")
    private String exportDir;

    @Value("${app.task.simulate-delay-ms:5}")
    private long simulateDelayMs;

    public ExportService(ExportTaskRepository taskRepo, ConfigDefService defService,
                         ConfigDataService dataService, ExcelService excelService,
                         ProgressHub progressHub, ObjectMapper mapper) {
        this.taskRepo = taskRepo;
        this.defService = defService;
        this.dataService = dataService;
        this.excelService = excelService;
        this.progressHub = progressHub;
        this.mapper = mapper;
    }

    /** 创建导出任务（异步执行由 TaskRunner 触发）。 */
    public ExportTask create(List<String> defCodes, Map<String, Map<String, Cond>> conditions) {
        if (defCodes == null || defCodes.isEmpty()) {
            throw ApiException.badRequest("请至少选择一个配置");
        }
        Set<String> distinct = new LinkedHashSet<>(defCodes);
        for (String code : distinct) {
            ConfigDef def = defService.getByCode(code);
            if (!def.isEnabled()) {
                throw ApiException.badRequest("配置已停用：" + def.getName());
            }
        }
        ExportTask t = new ExportTask();
        t.setDefCodesJson(dataService.writeJson(new ArrayList<>(distinct)));
        t.setConditionsJson(dataService.writeJson(conditions == null ? Map.of() : conditions));
        t.setStatus("PENDING");
        t.setMessage("任务已创建，等待执行");
        return taskRepo.save(t);
    }

    /** 异步执行导出（由 TaskRunner 调用）。 */
    public void run(Long taskId) {
        String topic = topic(taskId);
        ExportTask t = taskRepo.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("导出任务不存在：" + taskId));
        List<String> defCodes = readList(t.getDefCodesJson());
        Map<String, Map<String, Cond>> conditions = readConditions(t.getConditionsJson());
        List<ExportFileEntry> files = new ArrayList<>();
        try {
            t.setStatus("RUNNING");
            t.setProgress(0);
            t.setMessage("开始导出");
            t.setFilesJson(dataService.writeJson(files));
            taskRepo.save(t);
            Path dir = Path.of(exportDir, String.valueOf(taskId));
            Files.createDirectories(dir);

            int total = defCodes.size();
            for (int i = 0; i < total; i++) {
                String defCode = defCodes.get(i);
                ConfigDef def = defService.getByCode(defCode);
                var fields = defService.parseFields(def);
                Map<String, Cond> conds = conditions.getOrDefault(defCode, new LinkedHashMap<>());
                String scope = extractScope(conds);

                publish(topic, t, calcProgress(i, total, 0),
                        "正在导出：" + def.getName());

                List<Map<String, Object>> rows = dataService.queryRows(defCode, conds, scope, true, null);

                // 行级进度（大导出场景可见推进；simulate-delay-ms 可调，0 关闭模拟）
                int rowTotal = rows.size();
                for (int k = 0; k < rowTotal; k++) {
                    if (simulateDelayMs > 0 && k % 20 == 0) {
                        sleepQuietly(simulateDelayMs);
                    }
                    int pct = calcProgress(i, total, rowTotal == 0 ? 100 : (k + 1) * 100 / rowTotal);
                    if (pct != t.getProgress()) {
                        publish(topic, t, pct,
                                String.format("正在导出：%s（%d/%d 行）", def.getName(), k + 1, rowTotal));
                    }
                }

                var wb = excelService.buildExport(def, fields, rows);
                Path file = dir.resolve(defCode + ".xlsx");
                try (OutputStream out = Files.newOutputStream(file)) {
                    excelService.write(wb, out);
                }
                wb.close();

                ExportFileEntry entry = new ExportFileEntry();
                entry.setDefCode(defCode);
                entry.setDefName(def.getName());
                entry.setFileName(defCode + ".xlsx");
                entry.setSize(Files.size(file));
                entry.setRowCount(rowTotal);
                entry.setLevel(String.valueOf(def.getLevel()));
                files.add(entry);
                t.setFilesJson(dataService.writeJson(files));

                publish(topic, t, calcProgress(i + 1, total, 100),
                        "导出完成：" + def.getName() + "（" + rowTotal + " 行）");
            }

            t.setStatus("SUCCESS");
            t.setProgress(100);
            t.setMessage("导出完成，共 " + files.size() + " 个文件");
            taskRepo.save(t);
            progressHub.complete(topic, snapshot(t));
        } catch (Exception e) {
            log.error("导出任务失败 taskId={}", taskId, e);
            t.setStatus("FAILED");
            t.setMessage("导出失败：" + e.getMessage());
            taskRepo.save(t);
            progressHub.complete(topic, snapshot(t));
        }
    }

    public ExportTask get(Long id) {
        return taskRepo.findById(id).orElseThrow(() -> ApiException.notFound("导出任务不存在：" + id));
    }

    public List<ExportTask> list() {
        return taskRepo.findAllByOrderByIdDesc();
    }

    public String topic(Long id) {
        return "export:" + id;
    }

    public ProgressEvent snapshot(ExportTask t) {
        ProgressEvent e = new ProgressEvent(t.getStatus(), t.getProgress(), t.getMessage());
        e.getDetail().put("id", t.getId());
        e.getDetail().put("files", readFiles(t.getFilesJson()));
        e.getDetail().put("defCodes", readList(t.getDefCodesJson()));
        return e;
    }

    public List<ExportFileEntry> readFiles(String json) {
        try {
            return mapper.readValue(json == null || json.isBlank() ? "[]" : json,
                    new TypeReference<List<ExportFileEntry>>() {
                    });
        } catch (Exception e) {
            throw new ApiException(500, "文件清单解析失败：" + e.getMessage());
        }
    }

    public Path filePath(Long taskId, String defCode) {
        Path p = Path.of(exportDir, String.valueOf(taskId), defCode + ".xlsx");
        if (!Files.exists(p)) {
            throw ApiException.notFound("文件不存在：" + defCode + ".xlsx");
        }
        return p;
    }

    /** 将选定文件打包为 zip（条目名 = {defCode}.xlsx，可直接回传导入）。 */
    public void writeZip(Long taskId, List<String> defCodes, OutputStream out) throws IOException {
        List<ExportFileEntry> files = readFiles(get(taskId).getFilesJson());
        Set<String> selected = defCodes == null || defCodes.isEmpty()
                ? new HashSet<>(files.stream().map(ExportFileEntry::getDefCode).toList())
                : new HashSet<>(defCodes);
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (ExportFileEntry f : files) {
                if (!selected.contains(f.getDefCode())) continue;
                Path p = filePath(taskId, f.getDefCode());
                zip.putNextEntry(new ZipEntry(f.getFileName()));
                Files.copy(p, zip);
                zip.closeEntry();
            }
        }
    }

    // ---------- 内部 ----------

    private String extractScope(Map<String, Cond> conds) {
        Cond c = conds.remove("__scope__");
        if (c == null || c.value() == null) return null;
        return String.valueOf(c.value()).trim();
    }

    private int calcProgress(int doneDefs, int totalDefs, int defPct) {
        if (totalDefs == 0) return 0;
        return Math.min(99, (doneDefs * 100 + defPct) / totalDefs);
    }

    private void publish(String topic, ExportTask t, int progress, String message) {
        t.setProgress(progress);
        t.setMessage(message);
        taskRepo.save(t);
        ProgressEvent ev = new ProgressEvent(t.getStatus(), progress, message);
        ev.getDetail().put("id", t.getId());
        ev.getDetail().put("defCodes", readList(t.getDefCodesJson()));
        progressHub.publish(topic, ev);
    }

    private List<String> readList(String json) {
        try {
            return mapper.readValue(json == null || json.isBlank() ? "[]" : json,
                    new TypeReference<List<String>>() {
                    });
        } catch (Exception e) {
            throw new ApiException(500, "JSON 解析失败：" + e.getMessage());
        }
    }

    private Map<String, Map<String, Cond>> readConditions(String json) {
        try {
            return mapper.readValue(json == null || json.isBlank() ? "{}" : json,
                    new TypeReference<Map<String, Map<String, Cond>>>() {
                    });
        } catch (Exception e) {
            throw new ApiException(500, "查询条件解析失败：" + e.getMessage());
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
