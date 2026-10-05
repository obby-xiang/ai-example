package com.example.configmgr.job.service;

import com.example.configmgr.config.AppProperties;
import com.example.configmgr.data.entity.ConfigDataRow;
import com.example.configmgr.data.entity.ConfigStagingRow;
import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.data.repo.ConfigStagingRowRepository;
import com.example.configmgr.data.service.ConfigDataService;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.definition.service.DependencyResolver;
import com.example.configmgr.excel.ExcelReader;
import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.JobItem;
import com.example.configmgr.job.entity.ValidationIssue;
import com.example.configmgr.job.repo.JobItemRepository;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import com.example.configmgr.task.entity.TaskFile;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.service.TaskSseService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

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
    private final JobRepository jobRepository;
    private final JobItemRepository jobItemRepository;
    private final ValidationIssueRepository issueRepository;
    private final ExcelReader excelReader;
    private final FileStorageService fileStorage;
    private final TaskSseService taskSseService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    @Transactional
    public void run(Job job) {
        job.setStatus(Job.JobStatus.RUNNING);
        job.setStartedAt(java.time.LocalDateTime.now());
        jobRepository.save(job);

        // Delete old issues for this job
        issueRepository.deleteByJobId(job.getId());

        try {
            var items = taskItemRepository.findByTaskIdOrderBySortOrder(job.getTaskId());
            List<String> defCodes = items.stream().map(i -> i.getDefCode()).toList();
            List<String> sorted = dependencyResolver.sort(defCodes);

            job.setTotal(sorted.size());
            jobRepository.save(job);

            int totalErrors = 0;
            int totalWarnings = 0;

            for (String defCode : sorted) {
                JobItem ji = new JobItem();
                ji.setJobId(job.getId());
                ji.setDefCode(defCode);
                ji.setStatus("RUNNING");
                ji = jobItemRepository.save(ji);

                try {
                    ConfigDefinition def = definitionService.findByCode(defCode);
                    List<ConfigField> keyFields = def.getFields().stream()
                            .filter(ConfigField::isKey).toList();

                    // Find file for this def
                    Optional<TaskFile> fileOpt = taskFileRepository
                            .findByTaskIdAndDefCodeAndFileType(job.getTaskId(), defCode, "UPLOAD");
                    if (fileOpt.isEmpty()) {
                        fileOpt = taskFileRepository
                                .findByTaskIdAndDefCodeAndFileType(job.getTaskId(), defCode, "EXPORT");
                    }

                    if (fileOpt.isEmpty()) {
                        ValidationIssue issue = new ValidationIssue();
                        issue.setJobId(job.getId());
                        issue.setDefCode(defCode);
                        issue.setSeverity(ValidationIssue.Severity.ERROR);
                        issue.setMessage("未找到上传文件，请先上传配置数据文件");
                        issueRepository.save(issue);
                        totalErrors++;
                        ji.setStatus("FAILED");
                        jobItemRepository.save(ji);
                        job.setProgress(job.getProgress() + 1);
                        jobRepository.save(job);
                        continue;
                    }

                    // Read rows from Excel
                    byte[] fileBytes = fileStorage.read(fileOpt.get().getStoragePath());
                    List<Map<String, Object>> rows = excelReader.read(def, fileBytes);
                    ji.setTotal(rows.size());

                    // Validate each row
                    int rowErrors = 0, rowWarnings = 0;
                    Map<String, Integer> keyIndex = new HashMap<>();

                    for (int i = 0; i < rows.size(); i++) {
                        Map<String, Object> row = rows.get(i);

                        // Check required fields
                        for (ConfigField f : def.getFields()) {
                            if (f.isRequired()) {
                                Object val = row.get(f.getCode());
                                if (val == null || val.toString().isBlank()) {
                                    ValidationIssue issue = new ValidationIssue();
                                    issue.setJobId(job.getId());
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
                            issue.setJobId(job.getId());
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
                            ji.setProcessed(i);
                            jobItemRepository.save(ji);
                            publishProgress(job, defCode, i, rows.size());
                        }
                    }

                    totalErrors += rowErrors;
                    totalWarnings += rowWarnings;

                    ji.setStatus(rowErrors > 0 ? "FAILED" : "COMPLETED");
                    ji.setProcessed(rows.size());
                    jobItemRepository.save(ji);

                } catch (Exception e) {
                    log.error("Precheck failed for {}: {}", defCode, e.getMessage(), e);
                    ValidationIssue issue = new ValidationIssue();
                    issue.setJobId(job.getId());
                    issue.setDefCode(defCode);
                    issue.setSeverity(ValidationIssue.Severity.ERROR);
                    issue.setMessage("检查过程出错: " + e.getMessage());
                    issueRepository.save(issue);
                    totalErrors++;
                    ji.setStatus("FAILED");
                    jobItemRepository.save(ji);
                }

                job.setProgress(job.getProgress() + 1);
                jobRepository.save(job);
            }

            job.setErrorCount(totalErrors);
            job.setWarningCount(totalWarnings);
            job.setStatus(totalErrors > 0 ? Job.JobStatus.FAILED : Job.JobStatus.COMPLETED);

        } catch (Exception e) {
            log.error("Precheck job failed: {}", e.getMessage(), e);
            job.setStatus(Job.JobStatus.FAILED);
        } finally {
            job.setFinishedAt(java.time.LocalDateTime.now());
            jobRepository.save(job);
            taskSseService.publish(job.getTaskId(), "JOB_DONE", Map.of(
                    "jobId", job.getId(), "jobType", "PRECHECK",
                    "status", job.getStatus(), "errors", job.getErrorCount()));
        }
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

    private void publishProgress(Job job, String defCode, int processed, int total) {
        int pct = total > 0 ? (processed * 100 / total) : 0;
        taskSseService.publish(job.getTaskId(), "JOB_PROGRESS", Map.of(
                "jobId", job.getId(), "defCode", defCode,
                "processed", processed, "total", total, "pct", pct));
    }
}
