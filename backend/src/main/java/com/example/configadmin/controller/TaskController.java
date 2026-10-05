package com.example.configadmin.controller;

import com.example.configadmin.common.R;
import com.example.configadmin.dto.TaskSummary;
import com.example.configadmin.entity.ExportTask;
import com.example.configadmin.entity.ImportBatch;
import com.example.configadmin.service.ExportService;
import com.example.configadmin.service.ImportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 快速实施任务中心：统一查看全部历史任务（导出/导入），
 * 任务状态与进度均持久化于 H2，创建即入库。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final ExportService exportService;
    private final ImportService importService;

    public TaskController(ExportService exportService, ImportService importService) {
        this.exportService = exportService;
        this.importService = importService;
    }

    /** 统一任务列表（按创建时间倒序，服务端分页）；type 可选 EXPORT/IMPORT 过滤。 */
    @GetMapping
    public R<java.util.Map<String, Object>> list(@RequestParam(required = false) String type,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        List<TaskSummary> all = new ArrayList<>();
        if (type == null || type.isBlank() || "EXPORT".equalsIgnoreCase(type)) {
            exportService.list().forEach(t -> all.add(toSummary(t)));
        }
        if (type == null || type.isBlank() || "IMPORT".equalsIgnoreCase(type)) {
            importService.list().forEach(b -> all.add(toSummary(b)));
        }
        all.sort(Comparator.comparing(TaskSummary::createdAt,
                Comparator.nullsLast(Comparator.reverseOrder())));

        int total = all.size();
        int from = Math.min((page - 1) * size, total);
        int to = Math.min(from + size, total);
        java.util.Map<String, Object> res = new java.util.LinkedHashMap<>();
        res.put("total", total);
        res.put("page", page);
        res.put("size", size);
        res.put("rows", all.subList(from, to));
        return R.ok(res);
    }

    private TaskSummary toSummary(ExportTask t) {
        return new TaskSummary(
                "EXPORT-" + t.getId(),
                t.getId(),
                "EXPORT",
                "导出配置",
                "导出任务 #" + t.getId(),
                t.getStatus(),
                t.getProgress(),
                t.getMessage(),
                exportService.readList(t.getDefCodesJson()),
                exportService.readFiles(t.getFilesJson()).size(),
                t.getCreatedAt(),
                t.getUpdatedAt()
        );
    }

    private TaskSummary toSummary(ImportBatch b) {
        return new TaskSummary(
                "IMPORT-" + b.getId(),
                b.getId(),
                "IMPORT",
                "导入配置",
                b.getName(),
                b.getStatus().name(),
                b.getProgress(),
                b.getMessage(),
                importService.readList(b.getDefCodesJson()),
                importService.readEntries(b.getFilesJson()).size(),
                b.getCreatedAt(),
                b.getUpdatedAt()
        );
    }
}
