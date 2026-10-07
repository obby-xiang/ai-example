package com.example.configmgr.job.service;

import com.example.configmgr.config.AppProperties;
import com.example.configmgr.data.entity.ConfigDataRow;
import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.data.service.ConditionEvaluator;
import com.example.configmgr.data.service.QueryCondition;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.excel.ExcelWriter;
import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskFile;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.service.TaskService;
import com.example.configmgr.task.service.TaskSseService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 导出作业执行器：逐配置项按查询条件过滤已发布行 → 写 Excel + task_files（导出结果）。
 *
 * <p>状态/进度落库口径见 {@link JobProgressService}（⑤）：进度按"<b>已扫描行数 / 待扫描行数</b>"
 * 报（不再用"命中行数 / 待扫描行数"——那会让进度条与真实工作量脱节），
 * 作业行与作业条目经独立事务在分片边界实时可见；终态与任务态联动见 {@link JobTerminalWriter}（⑥）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportJobRunner {

    private final DefinitionService definitionService;
    private final ConfigDataRowRepository dataRowRepository;
    private final TaskItemRepository taskItemRepository;
    private final TaskService taskService;
    private final TaskFileRepository taskFileRepository;
    private final ExcelWriter excelWriter;
    private final FileStorageService fileStorage;
    private final TaskSseService taskSseService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final JobCancellationRegistry cancellationRegistry;
    private final JobProgressService progressService;
    private final JobTerminalWriter terminalWriter;

    @Transactional
    public void run(Job job) {
        final Long jobId = job.getId();
        final Long taskId = job.getTaskId();

        boolean cancelled = false;
        int errorCount = 0;
        int processedRows = 0;   // 已扫描行数（已完成配置项合计 + 当前配置项已扫描）
        int totalRows = 0;       // 待扫描行数（已发现配置项合计）
        int finishedDefRows = 0; // 已结束配置项的扫描行数合计

        progressService.markRunning(jobId, totalRows);

        try {
            var items = taskItemRepository.findByTaskIdOrderBySortOrder(taskId);

            for (var item : items) {
                if (cancellationRegistry.isCancelled(jobId)) {
                    cancelled = true;
                    break;
                }
                String defCode = item.getDefCode();
                Long itemId = progressService.startItem(jobId, defCode);
                int defTotal = 0;
                int rowsSeen = 0;

                try {
                    ConfigDefinition def = definitionService.findByCode(defCode);
                    QueryCondition cond = parseCondition(item.getConditionJson());

                    // Load all rows matching condition（范围过滤 + 字段级过滤统一由 ConditionEvaluator 处理）
                    List<ConfigDataRow> allRows = dataRowRepository.findByDefCodeOrderByRowKey(defCode);
                    defTotal = allRows.size();
                    totalRows += defTotal;
                    progressService.updateItem(itemId, 0, defTotal);
                    progressService.snapshot(jobId, processedRows, totalRows);

                    List<Map<String, Object>> filtered = new ArrayList<>();
                    for (ConfigDataRow r : allRows) {
                        rowsSeen++;
                        Map<String, Object> data = objectMapper.readValue(r.getDataJson(), new TypeReference<>() {});
                        if (!ConditionEvaluator.matches(data, cond)) {
                            continue;
                        }
                        filtered.add(data);
                        // Demo delay per batch
                        if (filtered.size() % appProperties.getJob().getBatchSize() == 0) {
                            Thread.sleep(appProperties.getJob().getDemoBatchDelayMs());
                            processedRows = finishedDefRows + rowsSeen;
                            // ⑤ 先落库再推送：快照与事件同一对数字
                            progressService.updateItem(itemId, rowsSeen, defTotal);
                            progressService.snapshot(jobId, processedRows, totalRows);
                            publishProgress(job, defCode, processedRows, totalRows);
                            if (cancellationRegistry.isCancelled(jobId)) {
                                cancelled = true;
                                progressService.finishItem(itemId, "CANCELLED", rowsSeen, defTotal);
                                break;
                            }
                        }
                    }
                    if (cancelled) break;

                    // 本配置项扫描完毕（无论命中多少行）
                    rowsSeen = defTotal;
                    processedRows = finishedDefRows + rowsSeen;
                    finishedDefRows += defTotal;
                    progressService.finishItem(itemId, "COMPLETED", defTotal, defTotal);
                    progressService.snapshot(jobId, processedRows, totalRows);

                    // Write Excel
                    String storagePath = fileStorage.newPath(".xlsx");
                    excelWriter.write(def, filtered, storagePath);

                    // Save TaskFile
                    Optional<TaskFile> existing = taskFileRepository
                            .findByTaskIdAndDefCodeAndFileType(taskId, defCode, "EXPORT");
                    TaskFile tf = existing.orElseGet(TaskFile::new);
                    tf.setTaskId(taskId);
                    tf.setDefCode(defCode);
                    tf.setFileType("EXPORT");
                    tf.setStoragePath(storagePath);
                    tf.setOriginalPath(storagePath);
                    tf.setFileName(defCode + "_" + def.getName() + ".xlsx");
                    tf.setRowCount(filtered.size());
                    taskFileRepository.save(tf);

                    taskService.updateItemStatus(taskId, defCode, "COMPLETED");

                } catch (Exception e) {
                    log.error("Export failed for def {}: {}", defCode, e.getMessage(), e);
                    errorCount++;
                    finishedDefRows += defTotal;
                    processedRows = finishedDefRows;
                    progressService.finishItem(itemId, "FAILED", (int) Math.max(defTotal, rowsSeen), defTotal);
                    taskService.updateItemStatus(taskId, defCode, "FAILED");
                }
            }
        } catch (Exception e) {
            log.error("Export job failed: {}", e.getMessage(), e);
            errorCount++;
            terminalWriter.complete(job, Job.JobStatus.FAILED, errorCount, 0,
                    processedRows, totalRows, doneEvent(job, Job.JobStatus.FAILED));
            return;
        }

        Job.JobStatus status = cancelled ? Job.JobStatus.CANCELLED
                : (errorCount > 0 ? Job.JobStatus.FAILED : Job.JobStatus.COMPLETED);
        if (!cancelled && errorCount == 0) {
            // 导出全部成功 → 任务完成，步骤同步到最终步骤（列表点击标题可直接查看结果）
            taskService.updateStatus(taskId, Task.TaskStatus.COMPLETED);
            taskService.goToStep(taskId, "EXPORT");
        }
        terminalWriter.complete(job, status, errorCount, 0, processedRows, totalRows,
                doneEvent(job, status));
    }

    private Map<String, Object> doneEvent(Job job, Job.JobStatus status) {
        return Map.of("jobId", job.getId(), "jobType", "EXPORT", "status", status);
    }

    private void publishProgress(Job job, String defCode, int processed, int total) {
        int pct = total > 0 ? (processed * 100 / total) : 0;
        taskSseService.publish(job.getTaskId(), "JOB_PROGRESS", Map.of(
                "jobId", job.getId(), "jobType", job.getJobType(), "defCode", defCode,
                "processed", processed, "total", total, "pct", pct));
    }

    private QueryCondition parseCondition(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, QueryCondition.class);
        } catch (Exception e) {
            return null;
        }
    }
}
