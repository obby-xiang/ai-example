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
import com.example.configmgr.job.entity.JobItem;
import com.example.configmgr.job.entity.ValidationIssue;
import com.example.configmgr.job.repo.JobItemRepository;
import com.example.configmgr.job.repo.JobRepository;
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
    private final JobRepository jobRepository;
    private final JobItemRepository jobItemRepository;
    private final ValidationIssueRepository issueRepository;
    private final ExcelReader excelReader;
    private final FileStorageService fileStorage;
    private final TaskSseService taskSseService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final JobCancellationRegistry cancellationRegistry;
    private final TaskService taskService;

    @Transactional
    public void run(Job job) {
        job.setStatus(Job.JobStatus.RUNNING);
        job.setStartedAt(java.time.LocalDateTime.now());
        jobRepository.save(job);

        boolean cancelled = false;
        try {
            var items = taskItemRepository.findByTaskIdOrderBySortOrder(job.getTaskId());
            List<String> defCodes = items.stream().map(i -> i.getDefCode()).toList();
            List<String> sorted = dependencyResolver.sort(defCodes);

            job.setTotal(sorted.size());
            jobRepository.save(job);

            for (String defCode : sorted) {
                if (cancellationRegistry.isCancelled(job.getId())) {
                    cancelled = true;
                    break;
                }
                JobItem ji = new JobItem();
                ji.setJobId(job.getId());
                ji.setDefCode(defCode);
                ji.setStatus("RUNNING");
                ji = jobItemRepository.save(ji);

                try {
                    ConfigDefinition def = definitionService.findByCode(defCode);
                    List<ConfigField> keyFields = def.getFields().stream()
                            .filter(ConfigField::isKey).toList();

                    // Clear old staging rows for this def
                    stagingRowRepository.deleteByTaskIdAndDefCode(job.getTaskId(), defCode);

                    // Find file
                    Optional<TaskFile> fileOpt = taskFileRepository
                            .findByTaskIdAndDefCodeAndFileType(job.getTaskId(), defCode, "UPLOAD");
                    if (fileOpt.isEmpty()) {
                        throw new RuntimeException("未找到文件");
                    }

                    byte[] fileBytes = fileStorage.read(fileOpt.get().getStoragePath());
                    List<Map<String, Object>> rows = excelReader.read(def, fileBytes);
                    ji.setTotal(rows.size());
                    jobItemRepository.save(ji);

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
                        sr.setTaskId(job.getTaskId());
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
                            stagingBatch.clear();
                            Thread.sleep(appProperties.getJob().getDemoBatchDelayMs());
                            ji.setProcessed(i);
                            jobItemRepository.save(ji);
                            publishProgress(job, defCode, i, rows.size());
                            if (cancellationRegistry.isCancelled(job.getId())) {
                                cancelled = true;
                                ji.setStatus("CANCELLED");
                                jobItemRepository.save(ji);
                                break;
                            }
                        }
                    }
                    if (!stagingBatch.isEmpty()) {
                        stagingRowRepository.saveAll(stagingBatch);
                    }
                    if (cancelled) break;

                    ji.setStatus("COMPLETED");
                    ji.setProcessed(rows.size());
                    jobItemRepository.save(ji);
                    taskService.updateItemStatus(job.getTaskId(), defCode, "IMPORTED");

                } catch (Exception e) {
                    log.error("Import failed for {}: {}", defCode, e.getMessage(), e);
                    ValidationIssue issue = new ValidationIssue();
                    issue.setJobId(job.getId());
                    issue.setDefCode(defCode);
                    issue.setSeverity(ValidationIssue.Severity.ERROR);
                    issue.setMessage("导入过程出错: " + e.getMessage());
                    issueRepository.save(issue);
                    job.setErrorCount(job.getErrorCount() + 1);
                    ji.setStatus("FAILED");
                    jobItemRepository.save(ji);
                    taskService.updateItemStatus(job.getTaskId(), defCode, "FAILED");
                }

                job.setProgress(job.getProgress() + 1);
                jobRepository.save(job);
            }

            if (cancelled) {
                job.setStatus(Job.JobStatus.CANCELLED);
            } else {
                job.setStatus(job.getErrorCount() > 0 ? Job.JobStatus.FAILED : Job.JobStatus.COMPLETED);
            }

        } catch (Exception e) {
            log.error("Import job failed: {}", e.getMessage(), e);
            job.setStatus(Job.JobStatus.FAILED);
        } finally {
            job.setFinishedAt(java.time.LocalDateTime.now());
            jobRepository.save(job);
            taskSseService.publish(job.getTaskId(), "JOB_DONE", Map.of(
                    "jobId", job.getId(), "jobType", "IMPORT",
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
