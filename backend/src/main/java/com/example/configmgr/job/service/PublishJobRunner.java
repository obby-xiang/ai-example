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
import com.example.configmgr.task.repo.TaskItemRepository;
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
                    List<ConfigField> keyFields = def.getFields().stream()
                            .filter(ConfigField::isKey).toList();
                    String scopeType = def.getLevel() == ConfigDefinition.ConfigLevel.GLOBAL ? "GLOBAL"
                            : def.getLevel() == ConfigDefinition.ConfigLevel.REGION ? "REGION" : "PROJECT";

                    List<ConfigStagingRow> stagingRows = stagingRowRepository
                            .findByTaskIdAndDefCode(job.getTaskId(), defCode);

                    ji.setTotal(stagingRows.size());

                    int processed = 0;
                    for (ConfigStagingRow sr : stagingRows) {
                        Map<String, Object> data = objectMapper.readValue(sr.getDataJson(), new TypeReference<>() {});

                        // Upsert into live table
                        Optional<ConfigDataRow> existing = dataRowRepository
                                .findByDefCodeAndScopeTypeAndScopeKeyAndRowKey(
                                        defCode, sr.getScopeType(),
                                        sr.getScopeKey(), sr.getRowKey());
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

    private void publishProgress(Job job, String defCode, int processed, int total) {
        int pct = total > 0 ? (processed * 100 / total) : 0;
        taskSseService.publish(job.getTaskId(), "JOB_PROGRESS", Map.of(
                "jobId", job.getId(), "defCode", defCode,
                "processed", processed, "total", total, "pct", pct));
    }
}
