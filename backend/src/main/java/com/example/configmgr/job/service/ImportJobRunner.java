package com.example.configmgr.job.service;

import com.example.configmgr.config.AppProperties;
import com.example.configmgr.data.entity.ConfigDataRow;
import com.example.configmgr.data.entity.ConfigStagingRow;
import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.data.repo.ConfigStagingRowRepository;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.definition.service.DependencyResolver;
import com.example.configmgr.excel.ExcelReader;
import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.ValidationIssue;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import com.example.configmgr.task.entity.TaskFile;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.service.TaskService;
import com.example.configmgr.task.service.TaskSseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 导入作业执行器：逐配置项读文件 → 写暂存行（含发布时并发冲突检测的 baseVersion 快照）。
 *
 * <p>状态/进度落库口径见 {@link JobProgressService}（⑤）：数据面（暂存行、校验问题、任务条目）
 * 仍在本方法的事务里；<b>作业行与作业条目</b>一律经 {@link JobProgressService} 的独立事务写入，
 * 于是运行中的 REST 快照不再是 PENDING/0；终态经 {@link JobTerminalWriter} 在事务提交后落地
 * 并联动作业所属任务状态（⑥）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportJobRunner {

    private final DefinitionService definitionService;
    private final DependencyResolver dependencyResolver;
    private final ConfigDataRowRepository dataRowRepository;
    private final ConfigStagingRowRepository stagingRowRepository;
    private final TaskItemRepository taskItemRepository;
    private final TaskFileRepository taskFileRepository;
    private final ValidationIssueRepository issueRepository;
    private final ExcelReader excelReader;
    private final FileStorageService fileStorage;
    private final TaskSseService taskSseService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final JobCancellationRegistry cancellationRegistry;
    private final TaskService taskService;
    private final JobProgressService progressService;
    private final JobTerminalWriter terminalWriter;

    @Transactional
    public void run(Job job) {
        final Long jobId = job.getId();
        final Long taskId = job.getTaskId();

        boolean cancelled = false;
        int errorCount = 0;
        int processedRows = 0;
        int totalRows = 0;

        progressService.markRunning(jobId, totalRows);

        try {
            var items = taskItemRepository.findByTaskIdOrderBySortOrder(taskId);
            List<String> defCodes = items.stream().map(i -> i.getDefCode()).toList();
            List<String> sorted = dependencyResolver.sort(defCodes);

            for (String defCode : sorted) {
                if (cancellationRegistry.isCancelled(jobId)) {
                    cancelled = true;
                    break;
                }
                Long itemId = progressService.startItem(jobId, defCode);
                int defTotal = 0;
                int defProcessed = 0;

                try {
                    ConfigDefinition def = definitionService.findByCode(defCode);
                    List<ConfigField> keyFields = def.getFields().stream()
                            .filter(ConfigField::isKey).toList();

                    // Clear old staging rows for this def
                    stagingRowRepository.deleteByTaskIdAndDefCode(taskId, defCode);

                    // Find file
                    Optional<TaskFile> fileOpt = taskFileRepository
                            .findByTaskIdAndDefCodeAndFileType(taskId, defCode, "UPLOAD");
                    if (fileOpt.isEmpty()) {
                        throw new RuntimeException("未找到文件");
                    }

                    byte[] fileBytes = fileStorage.read(fileOpt.get().getStoragePath());
                    List<Map<String, Object>> rows = excelReader.read(def, fileBytes);
                    defTotal = rows.size();
                    totalRows += defTotal;
                    progressService.updateItem(itemId, 0, defTotal);
                    progressService.snapshot(jobId, processedRows, totalRows);

                    // Write to staging
                    String scopeType = def.getLevel() == ConfigDefinition.ConfigLevel.GLOBAL ? "GLOBAL"
                            : def.getLevel() == ConfigDefinition.ConfigLevel.REGION ? "REGION" : "PROJECT";

                    List<ConfigStagingRow> stagingBatch = new ArrayList<>();
                    for (int i = 0; i < rows.size(); i++) {
                        Map<String, Object> row = rows.get(i);
                        String rowKey = buildRowKey(row, keyFields);
                        String scopeKey = (String) row.get(def.getLevel() == ConfigDefinition.ConfigLevel.REGION
                                ? "regionCode" : "projectCode");

                        ConfigStagingRow sr = new ConfigStagingRow();
                        sr.setTaskId(taskId);
                        sr.setDefCode(defCode);
                        sr.setRowKey(rowKey);
                        sr.setScopeType(scopeType);
                        sr.setScopeKey(scopeKey);
                        sr.setDataJson(objectMapper.writeValueAsString(row));
                        sr.setStatus("STAGED");
                        // 记录导入时刻的已发布行版本号，供发布时做并发冲突检测（新增行记 null）
                        sr.setBaseVersion(dataRowRepository
                                .findRow(defCode, scopeType, scopeKey, rowKey)
                                .map(ConfigDataRow::getVersion)
                                .orElse(null));
                        stagingBatch.add(sr);

                        if (stagingBatch.size() >= appProperties.getJob().getBatchSize()) {
                            stagingRowRepository.saveAll(stagingBatch);
                            defProcessed += stagingBatch.size();
                            processedRows += stagingBatch.size();
                            stagingBatch.clear();
                            Thread.sleep(appProperties.getJob().getDemoBatchDelayMs());
                            // ⑤ 快照与 SSE 事件同数同刻：先落库、再推送，消除"事件领先于库"
                            progressService.updateItem(itemId, defProcessed, defTotal);
                            progressService.snapshot(jobId, processedRows, totalRows);
                            publishProgress(job, defCode, processedRows, totalRows);
                            if (cancellationRegistry.isCancelled(jobId)) {
                                cancelled = true;
                                progressService.finishItem(itemId, "CANCELLED", defProcessed, defTotal);
                                break;
                            }
                        }
                    }
                    if (!stagingBatch.isEmpty()) {
                        stagingRowRepository.saveAll(stagingBatch);
                        defProcessed += stagingBatch.size();
                        processedRows += stagingBatch.size();
                        progressService.updateItem(itemId, defProcessed, defTotal);
                        progressService.snapshot(jobId, processedRows, totalRows);
                    }
                    if (cancelled) break;

                    progressService.finishItem(itemId, "COMPLETED", defProcessed, defTotal);
                    taskService.updateItemStatus(taskId, defCode, "IMPORTED");

                } catch (Exception e) {
                    log.error("Import failed for {}: {}", defCode, e.getMessage(), e);
                    ValidationIssue issue = new ValidationIssue();
                    issue.setJobId(jobId);
                    issue.setDefCode(defCode);
                    issue.setSeverity(ValidationIssue.Severity.ERROR);
                    issue.setMessage("导入过程出错: " + e.getMessage());
                    issueRepository.save(issue);
                    errorCount++;
                    progressService.finishItem(itemId, "FAILED", defProcessed, defTotal);
                    taskService.updateItemStatus(taskId, defCode, "FAILED");
                }
            }
        } catch (Exception e) {
            log.error("Import job failed: {}", e.getMessage(), e);
            errorCount++;
            terminalWriter.complete(job, Job.JobStatus.FAILED, errorCount, 0,
                    processedRows, totalRows, doneEvent(job, Job.JobStatus.FAILED, errorCount));
            return;
        }

        Job.JobStatus status = cancelled ? Job.JobStatus.CANCELLED
                : (errorCount > 0 ? Job.JobStatus.FAILED : Job.JobStatus.COMPLETED);
        terminalWriter.complete(job, status, errorCount, 0, processedRows, totalRows,
                doneEvent(job, status, errorCount));
    }

    private Map<String, Object> doneEvent(Job job, Job.JobStatus status, int errorCount) {
        return Map.of("jobId", job.getId(), "jobType", "IMPORT",
                "status", status, "errors", errorCount);
    }

    private String buildRowKey(Map<String, Object> row, List<ConfigField> keyFields) {
        StringBuilder sb = new StringBuilder();
        for (ConfigField f : keyFields) {
            if (sb.length() > 0) sb.append("|");
            Object v = row.get(f.getCode());
            sb.append(v != null ? v.toString() : "");
        }
        return sb.toString();
    }

    /** 行级进度事件：与 {@link JobProgressService#snapshot} 同一对数字（processed/total）。 */
    private void publishProgress(Job job, String defCode, int processed, int total) {
        int pct = total > 0 ? (processed * 100 / total) : 0;
        taskSseService.publish(job.getTaskId(), "JOB_PROGRESS", Map.of(
                "jobId", job.getId(), "jobType", job.getJobType(), "defCode", defCode,
                "processed", processed, "total", total, "pct", pct));
    }
}
