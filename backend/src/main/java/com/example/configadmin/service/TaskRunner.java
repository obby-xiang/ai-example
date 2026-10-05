package com.example.configadmin.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** 长任务异步执行入口（避免自调用 @Async 失效问题）。 */
@Component
public class TaskRunner {

    private final ExportService exportService;
    private final ImportService importService;

    public TaskRunner(ExportService exportService, ImportService importService) {
        this.exportService = exportService;
        this.importService = importService;
    }

    @Async("taskExecutor")
    public void runExport(Long taskId) {
        exportService.run(taskId);
    }

    @Async("taskExecutor")
    public void runCheck(Long batchId) {
        importService.runCheck(batchId);
    }

    @Async("taskExecutor")
    public void runImport(Long batchId) {
        importService.runImport(batchId);
    }

    @Async("taskExecutor")
    public void runPublish(Long batchId) {
        importService.runPublish(batchId);
    }
}
