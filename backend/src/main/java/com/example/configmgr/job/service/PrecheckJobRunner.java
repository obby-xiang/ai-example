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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 预检查作业执行器：逐配置项校验必填/主键重复/引用完整性。
 *
 * <p>状态/进度落库口径见 {@link JobProgressService}（⑤）；终态与任务态联动见
 * {@link JobTerminalWriter}（⑥）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrecheckJobRunner {

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
        int totalErrors = 0;
        int totalWarnings = 0;
        int processedRows = 0;
        int totalRows = 0;
        int finishedDefRows = 0;

        progressService.markRunning(jobId, totalRows);

        // Delete old issues for this job
        issueRepository.deleteByJobId(jobId);

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
                int rowsSeen = 0;

                try {
                    ConfigDefinition def = definitionService.findByCode(defCode);
                    List<ConfigField> keyFields = def.getFields().stream()
                            .filter(ConfigField::isKey).toList();

                    // Find file for this def
                    Optional<TaskFile> fileOpt = taskFileRepository
                            .findByTaskIdAndDefCodeAndFileType(taskId, defCode, "UPLOAD");
                    if (fileOpt.isEmpty()) {
                        fileOpt = taskFileRepository
                                .findByTaskIdAndDefCodeAndFileType(taskId, defCode, "EXPORT");
                    }

                    if (fileOpt.isEmpty()) {
                        ValidationIssue issue = new ValidationIssue();
                        issue.setJobId(jobId);
                        issue.setDefCode(defCode);
                        issue.setSeverity(ValidationIssue.Severity.ERROR);
                        issue.setMessage("未找到上传文件，请先上传配置数据文件");
                        issueRepository.save(issue);
                        totalErrors++;
                        progressService.finishItem(itemId, "FAILED", 0, 0);
                        taskService.updateItemStatus(taskId, defCode, "FAILED");
                        continue;
                    }

                    // Read rows from Excel
                    byte[] fileBytes = fileStorage.read(fileOpt.get().getStoragePath());
                    List<Map<String, Object>> rows = excelReader.read(def, fileBytes);
                    defTotal = rows.size();
                    totalRows += defTotal;
                    progressService.updateItem(itemId, 0, defTotal);
                    progressService.snapshot(jobId, processedRows, totalRows);

                    // Validate each row
                    int rowErrors = 0, rowWarnings = 0;
                    Map<String, Integer> keyIndex = new HashMap<>();

                    for (int i = 0; i < rows.size(); i++) {
                        Map<String, Object> row = rows.get(i);
                        rowsSeen = i + 1;

                        // Check required fields
                        for (ConfigField f : def.getFields()) {
                            if (f.isRequired()) {
                                Object val = row.get(f.getCode());
                                if (val == null || val.toString().isBlank()) {
                                    ValidationIssue issue = new ValidationIssue();
                                    issue.setJobId(jobId);
                                    issue.setDefCode(defCode);
                                    issue.setFieldCode(f.getCode());
                                    issue.setRowIndex(i + 2); // 1-based + header
                                    issue.setSeverity(ValidationIssue.Severity.ERROR);
                                    issue.setMessage("必填字段 [" + f.getLabel() + "] 不能为空");
                                    issueRepository.save(issue);
                                    rowErrors++;
                                }
                            }
                        }

                        // Check duplicate keys
                        String rowKey = buildRowKey(row, keyFields);
                        if (keyIndex.containsKey(rowKey)) {
                            ValidationIssue issue = new ValidationIssue();
                            issue.setJobId(jobId);
                            issue.setDefCode(defCode);
                            issue.setRowKey(rowKey);
                            issue.setRowIndex(i + 2);
                            issue.setSeverity(ValidationIssue.Severity.ERROR);
                            issue.setMessage("主键重复: " + rowKey + " (第 " + keyIndex.get(rowKey) + " 行已存在)");
                            issueRepository.save(issue);
                            rowErrors++;
                        } else {
                            keyIndex.put(rowKey, i + 2);
                        }

                        if (i % appProperties.getJob().getBatchSize() == 0) {
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

                    // 引用完整性校验：REFERENCE 字段的值必须存在于被引用配置的
                    // 已发布数据或本任务暂存数据中（值级依赖，依赖排序之外的第二道检查）
                    for (ConfigField f : def.getFields()) {
                        if (f.getFieldType() != ConfigField.FieldType.REFERENCE || f.getRefDefCode() == null) {
                            continue;
                        }
                        Set<String> validValues = loadValidRefValues(
                                taskId, f.getRefDefCode(), f.getRefFieldCode());
                        for (int i = 0; i < rows.size(); i++) {
                            Object v = rows.get(i).get(f.getCode());
                            if (v == null || v.toString().isBlank()) {
                                continue; // 空值由必填校验负责
                            }
                            if (!validValues.contains(v.toString())) {
                                ValidationIssue issue = new ValidationIssue();
                                issue.setJobId(jobId);
                                issue.setDefCode(defCode);
                                issue.setFieldCode(f.getCode());
                                issue.setRowIndex(i + 2);
                                issue.setSeverity(ValidationIssue.Severity.ERROR);
                                issue.setMessage("引用字段 [" + f.getLabel() + "] 的值 " + v
                                        + " 在配置 " + f.getRefDefCode() + " 中不存在");
                                issueRepository.save(issue);
                                rowErrors++;
                            }
                        }
                    }

                    totalErrors += rowErrors;
                    totalWarnings += rowWarnings;

                    rowsSeen = defTotal;
                    finishedDefRows += defTotal;
                    processedRows = finishedDefRows;
                    progressService.finishItem(itemId, rowErrors > 0 ? "FAILED" : "COMPLETED",
                            defTotal, defTotal);
                    progressService.snapshot(jobId, processedRows, totalRows);
                    taskService.updateItemStatus(taskId, defCode,
                            rowErrors > 0 ? "FAILED" : "CHECKED");

                } catch (Exception e) {
                    log.error("Precheck failed for {}: {}", defCode, e.getMessage(), e);
                    ValidationIssue issue = new ValidationIssue();
                    issue.setJobId(jobId);
                    issue.setDefCode(defCode);
                    issue.setSeverity(ValidationIssue.Severity.ERROR);
                    issue.setMessage("检查过程出错: " + e.getMessage());
                    issueRepository.save(issue);
                    totalErrors++;
                    finishedDefRows += defTotal;
                    processedRows = finishedDefRows;
                    progressService.finishItem(itemId, "FAILED", (int) Math.max(defTotal, rowsSeen), defTotal);
                    taskService.updateItemStatus(taskId, defCode, "FAILED");
                }
            }
        } catch (Exception e) {
            log.error("Precheck job failed: {}", e.getMessage(), e);
            totalErrors++;
            terminalWriter.complete(job, Job.JobStatus.FAILED, totalErrors, totalWarnings,
                    processedRows, totalRows, doneEvent(job, Job.JobStatus.FAILED, totalErrors));
            return;
        }

        Job.JobStatus status = cancelled ? Job.JobStatus.CANCELLED
                : (totalErrors > 0 ? Job.JobStatus.FAILED : Job.JobStatus.COMPLETED);
        terminalWriter.complete(job, status, totalErrors, totalWarnings, processedRows, totalRows,
                doneEvent(job, status, totalErrors));
    }

    private Map<String, Object> doneEvent(Job job, Job.JobStatus status, int errors) {
        return Map.of("jobId", job.getId(), "jobType", "PRECHECK",
                "status", status, "errors", errors);
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

    /**
     * 加载被引用配置的合法值集合：已发布数据 + 本任务暂存数据。
     */
    private Set<String> loadValidRefValues(Long taskId, String refDefCode, String refFieldCode) {
        Set<String> values = new HashSet<>();
        try {
            for (ConfigDataRow r : dataRowRepository.findByDefCodeOrderByRowKey(refDefCode)) {
                Map<String, Object> data = objectMapper.readValue(r.getDataJson(), new TypeReference<>() {});
                Object v = data.get(refFieldCode);
                if (v != null && !v.toString().isBlank()) {
                    values.add(v.toString());
                }
            }
            for (ConfigStagingRow s : stagingRowRepository.findByTaskIdAndDefCode(taskId, refDefCode)) {
                Map<String, Object> data = objectMapper.readValue(s.getDataJson(), new TypeReference<>() {});
                Object v = data.get(refFieldCode);
                if (v != null && !v.toString().isBlank()) {
                    values.add(v.toString());
                }
            }
        } catch (Exception e) {
            log.warn("加载引用值集合失败 refDef={}: {}", refDefCode, e.getMessage());
        }
        return values;
    }

    private void publishProgress(Job job, String defCode, int processed, int total) {
        int pct = total > 0 ? (processed * 100 / total) : 0;
        taskSseService.publish(job.getTaskId(), "JOB_PROGRESS", Map.of(
                "jobId", job.getId(), "jobType", job.getJobType(), "defCode", defCode,
                "processed", processed, "total", total, "pct", pct));
    }
}
