package com.example.configmgr.job.service;

import com.example.configmgr.config.AppProperties;
import com.example.configmgr.data.entity.ConfigDataRow;
import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.data.service.ConditionEvaluator;
import com.example.configmgr.data.service.QueryCondition;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.excel.ExcelWriter;
import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.JobItem;
import com.example.configmgr.job.repo.JobItemRepository;
import com.example.configmgr.job.repo.JobRepository;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class ExportJobRunner {

    private final DefinitionService definitionService;
    private final ConfigDataRowRepository dataRowRepository;
    private final TaskItemRepository taskItemRepository;
    private final TaskService taskService;
    private final TaskFileRepository taskFileRepository;
    private final JobRepository jobRepository;
    private final JobItemRepository jobItemRepository;
    private final ExcelWriter excelWriter;
    private final FileStorageService fileStorage;
    private final TaskSseService taskSseService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final JobCancellationRegistry cancellationRegistry;

    @Transactional
    public void run(Job job) {
        job.setStatus(Job.JobStatus.RUNNING);
        job.setStartedAt(java.time.LocalDateTime.now());
        jobRepository.save(job);

        boolean cancelled = false;
        try {
            var items = taskItemRepository.findByTaskIdOrderBySortOrder(job.getTaskId());
            job.setTotal(items.size());
            jobRepository.save(job);

            for (var item : items) {
                if (cancellationRegistry.isCancelled(job.getId())) {
                    cancelled = true;
                    break;
                }
                String defCode = item.getDefCode();
                JobItem ji = new JobItem();
                ji.setJobId(job.getId());
                ji.setDefCode(defCode);
                ji.setStatus("RUNNING");
                ji = jobItemRepository.save(ji);

                try {
                    ConfigDefinition def = definitionService.findByCode(defCode);
                    List<String> keyFields = def.getFields().stream()
                            .filter(ConfigField::isKey).map(ConfigField::getCode).toList();

                    // Parse condition
                    QueryCondition cond = parseCondition(item.getConditionJson());

                    // Load all rows matching condition（范围过滤 + 字段级过滤统一由 ConditionEvaluator 处理）
                    List<ConfigDataRow> allRows = dataRowRepository.findByDefCodeOrderByRowKey(defCode);
                    List<Map<String, Object>> filtered = new ArrayList<>();
                    for (ConfigDataRow r : allRows) {
                        Map<String, Object> data = objectMapper.readValue(r.getDataJson(), new TypeReference<>() {});
                        if (!ConditionEvaluator.matches(data, cond)) {
                            continue;
                        }
                        filtered.add(data);
                        // Demo delay per batch
                        if (filtered.size() % appProperties.getJob().getBatchSize() == 0) {
                            Thread.sleep(appProperties.getJob().getDemoBatchDelayMs());
                            ji.setProcessed(filtered.size());
                            ji.setTotal(allRows.size());
                            jobItemRepository.save(ji);
                            publishProgress(job, defCode, filtered.size(), allRows.size());
                            if (cancellationRegistry.isCancelled(job.getId())) {
                                cancelled = true;
                                ji.setStatus("CANCELLED");
                                jobItemRepository.save(ji);
                                break;
                            }
                        }
                    }
                    if (cancelled) break;

                    // Write Excel
                    String storagePath = fileStorage.newPath(".xlsx");
                    excelWriter.write(def, filtered, storagePath);

                    // Save TaskFile
                    Optional<TaskFile> existing = taskFileRepository
                            .findByTaskIdAndDefCodeAndFileType(job.getTaskId(), defCode, "EXPORT");
                    TaskFile tf = existing.orElseGet(TaskFile::new);
                    tf.setTaskId(job.getTaskId());
                    tf.setDefCode(defCode);
                    tf.setFileType("EXPORT");
                    tf.setStoragePath(storagePath);
                    tf.setOriginalPath(storagePath);
                    tf.setFileName(defCode + "_" + def.getName() + ".xlsx");
                    tf.setRowCount(filtered.size());
                    taskFileRepository.save(tf);

                    ji.setStatus("COMPLETED");
                    ji.setProcessed(filtered.size());
                    ji.setTotal(filtered.size());
                    jobItemRepository.save(ji);
                    taskService.updateItemStatus(job.getTaskId(), defCode, "COMPLETED");

                } catch (Exception e) {
                    log.error("Export failed for def {}: {}", defCode, e.getMessage(), e);
                    ji.setStatus("FAILED");
                    jobItemRepository.save(ji);
                    job.setErrorCount(job.getErrorCount() + 1);
                    taskService.updateItemStatus(job.getTaskId(), defCode, "FAILED");
                }

                job.setProgress(job.getProgress() + 1);
                jobRepository.save(job);
            }

            if (cancelled) {
                job.setStatus(Job.JobStatus.CANCELLED);
            } else {
                job.setStatus(job.getErrorCount() > 0 ? Job.JobStatus.FAILED : Job.JobStatus.COMPLETED);
                if (job.getErrorCount() == 0) {
                    // 导出全部成功 → 任务完成
                    taskService.updateStatus(job.getTaskId(), Task.TaskStatus.COMPLETED);
                }
            }
        } catch (Exception e) {
            log.error("Export job failed: {}", e.getMessage(), e);
            job.setStatus(Job.JobStatus.FAILED);
        } finally {
            job.setFinishedAt(java.time.LocalDateTime.now());
            jobRepository.save(job);
            taskSseService.publish(job.getTaskId(), "JOB_DONE", Map.of(
                    "jobId", job.getId(), "jobType", "EXPORT", "status", job.getStatus()));
        }
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
