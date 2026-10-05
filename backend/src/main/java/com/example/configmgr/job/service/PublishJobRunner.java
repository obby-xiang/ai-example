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
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.JobItem;
import com.example.configmgr.job.entity.ValidationIssue;
import com.example.configmgr.job.repo.JobItemRepository;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.repo.TaskRepository;
import com.example.configmgr.task.service.TaskSseService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PublishJobRunner {

    private final DefinitionService definitionService;
    private final DependencyResolver dependencyResolver;
    private final ConfigDataRowRepository dataRowRepository;
    private final ConfigStagingRowRepository stagingRowRepository;
    private final TaskItemRepository taskItemRepository;
    private final TaskRepository taskRepository;
    private final JobRepository jobRepository;
    private final JobItemRepository jobItemRepository;
    private final ValidationIssueRepository issueRepository;
    private final TaskSseService taskSseService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final JobCancellationRegistry cancellationRegistry;

    @Transactional
    public void run(Job job) {
        job.setStatus(Job.JobStatus.RUNNING);
        job.setStartedAt(LocalDateTime.now());
        jobRepository.save(job);

        issueRepository.deleteByJobId(job.getId());

        // 导入模式：MERGE=增量合并（仅 upsert），REPLACE=整体替换（暂存范围外已发布行删除）
        boolean replaceMode = isReplaceMode(job.getTaskId());

        boolean cancelled = false;
        try {
            var items = taskItemRepository.findByTaskIdOrderBySortOrder(job.getTaskId());
            List<String> defCodes = items.stream().map(i -> i.getDefCode()).toList();
            List<String> sorted = dependencyResolver.sort(defCodes);

            job.setTotal(sorted.size());
            jobRepository.save(job);

            int totalErrors = 0;

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
                    String scopeType = def.getLevel() == ConfigDefinition.ConfigLevel.GLOBAL ? "GLOBAL"
                            : def.getLevel() == ConfigDefinition.ConfigLevel.REGION ? "REGION" : "PROJECT";

                    List<ConfigStagingRow> stagingRows = stagingRowRepository
                            .findByTaskIdAndDefCode(job.getTaskId(), defCode);

                    ji.setTotal(stagingRows.size());

                    // REPLACE 模式：删除受影响范围内、暂存区之外的行（暂存行稍后 upsert）
                    if (replaceMode) {
                        int deleted = deleteOutOfStaging(defCode, scopeType, stagingRows);
                        log.info("REPLACE 模式：{} 删除范围外已发布行 {} 行", defCode, deleted);
                    }

                    int processed = 0;
                    for (ConfigStagingRow sr : stagingRows) {
                        Map<String, Object> data = objectMapper.readValue(sr.getDataJson(), new TypeReference<>() {});

                        // 并发冲突检测：导入时刻快照的 baseVersion 与当前版本不一致则跳过
                        Optional<ConfigDataRow> existing = dataRowRepository
                                .findRow(defCode, sr.getScopeType(), sr.getScopeKey(), sr.getRowKey());
                        if (hasConflict(sr, existing)) {
                            ValidationIssue issue = new ValidationIssue();
                            issue.setJobId(job.getId());
                            issue.setDefCode(defCode);
                            issue.setRowKey(sr.getRowKey());
                            issue.setSeverity(ValidationIssue.Severity.ERROR);
                            issue.setMessage("发布冲突：行 " + sr.getRowKey()
                                    + " 在导入后被其他操作修改（快照版本 " + sr.getBaseVersion()
                                    + "，当前版本 " + existing.map(ConfigDataRow::getVersion).orElse(null)
                                    + "），请重新导入后再发布");
                            issueRepository.save(issue);
                            totalErrors++;
                            sr.setStatus("FAILED");
                            stagingRowRepository.save(sr);
                            processed++;
                            continue;
                        }

                        // Upsert into live table
                        ConfigDataRow liveRow = existing.orElseGet(ConfigDataRow::new);
                        liveRow.setDefCode(defCode);
                        liveRow.setScopeType(sr.getScopeType());
                        liveRow.setScopeKey(sr.getScopeKey());
                        liveRow.setRowKey(sr.getRowKey());
                        liveRow.setDataJson(sr.getDataJson());
                        dataRowRepository.save(liveRow);

                        // Mark staging row as published
                        sr.setStatus("PUBLISHED");
                        stagingRowRepository.save(sr);

                        processed++;
                        if (processed % appProperties.getJob().getBatchSize() == 0) {
                            Thread.sleep(appProperties.getJob().getDemoBatchDelayMs());
                            ji.setProcessed(processed);
                            jobItemRepository.save(ji);
                            publishProgress(job, defCode, processed, stagingRows.size());
                            if (cancellationRegistry.isCancelled(job.getId())) {
                                cancelled = true;
                                ji.setStatus("CANCELLED");
                                jobItemRepository.save(ji);
                                break;
                            }
                        }
                    }
                    if (cancelled) break;

                    ji.setStatus("COMPLETED");
                    ji.setProcessed(stagingRows.size());
                    jobItemRepository.save(ji);

                } catch (Exception e) {
                    log.error("Publish failed for {}: {}", defCode, e.getMessage(), e);
                    ValidationIssue issue = new ValidationIssue();
                    issue.setJobId(job.getId());
                    issue.setDefCode(defCode);
                    issue.setSeverity(ValidationIssue.Severity.ERROR);
                    issue.setMessage("发布过程出错: " + e.getMessage());
                    issueRepository.save(issue);
                    totalErrors++;
                    ji.setStatus("FAILED");
                    jobItemRepository.save(ji);
                }

                job.setProgress(job.getProgress() + 1);
                jobRepository.save(job);
            }

            job.setErrorCount(totalErrors);
            if (cancelled) {
                job.setStatus(Job.JobStatus.CANCELLED);
            } else {
                job.setStatus(totalErrors > 0 ? Job.JobStatus.FAILED : Job.JobStatus.COMPLETED);
            }

        } catch (Exception e) {
            log.error("Publish job failed: {}", e.getMessage(), e);
            job.setStatus(Job.JobStatus.FAILED);
        } finally {
            job.setFinishedAt(LocalDateTime.now());
            jobRepository.save(job);
            taskSseService.publish(job.getTaskId(), "JOB_DONE", Map.of(
                    "jobId", job.getId(), "jobType", "PUBLISH",
                    "status", job.getStatus(), "errors", job.getErrorCount()));
        }
    }

    /**
     * 冲突判定：
     * - 暂存行有快照版本（当时是更新）：当前行不存在或版本不一致 → 冲突
     * - 暂存行无快照版本（当时是新增）：当前行已存在 → 冲突（期间被他人创建）
     */
    private boolean hasConflict(ConfigStagingRow sr, Optional<ConfigDataRow> existing) {
        if (sr.getBaseVersion() != null) {
            return existing.isEmpty() || !existing.get().getVersion().equals(sr.getBaseVersion());
        }
        return existing.isPresent();
    }

    private int deleteOutOfStaging(String defCode, String scopeType, List<ConfigStagingRow> stagingRows) {
        Set<String> stagedKeys = new HashSet<>();
        Set<String> scopeKeys = new HashSet<>();
        for (ConfigStagingRow sr : stagingRows) {
            stagedKeys.add(sr.getRowKey());
            scopeKeys.add(sr.getScopeKey());
        }
        // GLOBAL 只有一个范围（scopeKey=null）
        if (scopeKeys.size() == 1 && scopeKeys.contains(null)) {
            scopeKeys = new HashSet<>(List.of((String) null));
        }

        int deleted = 0;
        for (String sk : scopeKeys) {
            List<ConfigDataRow> live = dataRowRepository.findRowsInScope(defCode, scopeType, sk);
            for (ConfigDataRow row : live) {
                if (!stagedKeys.contains(row.getRowKey())) {
                    dataRowRepository.delete(row);
                    deleted++;
                }
            }
        }
        return deleted;
    }

    private boolean isReplaceMode(Long taskId) {
        return taskRepository.findById(taskId)
                .map(t -> {
                    String settings = t.getSettingsJson();
                    if (settings == null || settings.isBlank()) return false;
                    try {
                        return "REPLACE".equalsIgnoreCase(
                                objectMapper.readTree(settings).path("importMode").asText("MERGE"));
                    } catch (Exception e) {
                        return false;
                    }
                })
                .orElse(false);
    }

    private void publishProgress(Job job, String defCode, int processed, int total) {
        int pct = total > 0 ? (processed * 100 / total) : 0;
        taskSseService.publish(job.getTaskId(), "JOB_PROGRESS", Map.of(
                "jobId", job.getId(), "defCode", defCode,
                "processed", processed, "total", total, "pct", pct));
    }
}
