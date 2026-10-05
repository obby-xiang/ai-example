package com.example.quickstart.service;

import com.example.quickstart.dto.Condition;
import com.example.quickstart.dto.FieldDef;
import com.example.quickstart.dto.RowError;
import com.example.quickstart.entity.ConfigData;
import com.example.quickstart.entity.ConfigDefinition;
import com.example.quickstart.entity.ExportResult;
import com.example.quickstart.entity.JobItem;
import com.example.quickstart.entity.JobRun;
import com.example.quickstart.entity.StagingConfigData;
import com.example.quickstart.entity.Task;
import com.example.quickstart.repository.ConfigDataRepository;
import com.example.quickstart.repository.ExportResultRepository;
import com.example.quickstart.repository.JobItemRepository;
import com.example.quickstart.repository.JobRunRepository;
import com.example.quickstart.repository.StagingConfigDataRepository;
import com.example.quickstart.repository.TaskRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 作业执行器：专用线程池异步逐配置项处理、逐项更新进度；
 * 支持取消（配置项边界检查标志位）；演示节流 app.job.item-delay-ms。
 */
@Slf4j
@Component
public class JobExecutor {

    private static final TypeReference<List<Condition>> CONDITION_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> ROWS_TYPE = new TypeReference<>() {
    };

    private final Executor jobExecutor;
    private final JobRunRepository jobRunRepository;
    private final JobItemRepository jobItemRepository;
    private final ExportResultRepository exportResultRepository;
    private final StagingConfigDataRepository stagingRepository;
    private final ConfigDataRepository configDataRepository;
    private final TaskRepository taskRepository;
    private final ConfigDefService configDefService;
    private final ConfigValidator configValidator;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper om;
    private final long itemDelayMs;

    /** 取消标志（单机内存，轮次/配置项边界生效） */
    private final Set<Long> cancelFlags = ConcurrentHashMap.newKeySet();

    public JobExecutor(@Qualifier("jobTaskExecutor") Executor jobExecutor,
                       JobRunRepository jobRunRepository,
                       JobItemRepository jobItemRepository,
                       ExportResultRepository exportResultRepository,
                       StagingConfigDataRepository stagingRepository,
                       ConfigDataRepository configDataRepository,
                       TaskRepository taskRepository,
                       ConfigDefService configDefService,
                       ConfigValidator configValidator,
                       PlatformTransactionManager transactionManager,
                       ObjectMapper om,
                       @Value("${app.job.item-delay-ms:300}") long itemDelayMs) {
        this.jobExecutor = jobExecutor;
        this.jobRunRepository = jobRunRepository;
        this.jobItemRepository = jobItemRepository;
        this.exportResultRepository = exportResultRepository;
        this.stagingRepository = stagingRepository;
        this.configDataRepository = configDataRepository;
        this.taskRepository = taskRepository;
        this.configDefService = configDefService;
        this.configValidator = configValidator;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.om = om;
        this.itemDelayMs = itemDelayMs;
    }

    public void requestCancel(Long jobId) {
        cancelFlags.add(jobId);
        log.info("作业取消请求: jobId={}", jobId);
    }

    public void submit(Long jobId) {
        jobExecutor.execute(() -> runSafely(jobId));
    }

    // ================================ 执行主流程 ================================

    private void runSafely(Long jobId) {
        try {
            run(jobId);
        } catch (Exception e) {
            log.error("作业执行异常: jobId={}", jobId, e);
            try {
                JobRun job = jobRunRepository.findById(jobId).orElse(null);
                if (job != null && ("PENDING".equals(job.getStatus()) || "RUNNING".equals(job.getStatus()))) {
                    job.setStatus("FAILED");
                    job.setError("作业执行异常: " + e.getMessage());
                    job.setFinishedAt(LocalDateTime.now());
                    jobRunRepository.save(job);
                    markTaskFailed(job.getTaskId(), e.getMessage());
                }
            } catch (Exception inner) {
                log.error("作业失败状态回写失败: jobId={}", jobId, inner);
            }
        } finally {
            cancelFlags.remove(jobId);
        }
    }

    private void run(Long jobId) {
        JobRun job = jobRunRepository.findById(jobId).orElse(null);
        if (job == null || !"PENDING".equals(job.getStatus())) {
            return;
        }
        List<JobItem> items = jobItemRepository.findByJobIdOrderBySeqAsc(jobId);

        job.setStatus("RUNNING");
        job.setStartedAt(LocalDateTime.now());
        jobRunRepository.save(job);
        markTaskInProgress(job.getTaskId());

        RefValueProvider refProvider = new RefValueProvider(configDefService,
                "PUBLISH".equals(job.getKind()) ? RefValueProvider.Mode.REPLACE : RefValueProvider.Mode.UNION);
        Set<String> failedOrSkipped = new HashSet<>();
        Set<String> selectedDefs = items.stream().map(JobItem::getDefCode).collect(Collectors.toSet());

        for (JobItem item : items) {
            // 配置项边界检查取消标志
            if (cancelFlags.contains(jobId)) {
                cancelRemaining(items, item.getSeq());
                finishJob(job, "CANCELLED", null);
                log.info("作业已取消: jobId={}", jobId);
                return;
            }
            job.setCurrentItem(item.getDefCode());
            jobRunRepository.save(job);

            processItem(job, item, refProvider, failedOrSkipped, selectedDefs);
            jobItemRepository.save(item);

            job.setProcessed(job.getProcessed() + 1);
            jobRunRepository.save(job);
            updateTaskProgress(job.getTaskId(), job.getProcessed(), job.getTotal());
            sleep(itemDelayMs);
        }

        boolean anyFailed = jobItemRepository.findByJobIdOrderBySeqAsc(jobId).stream()
                .anyMatch(i -> "FAILED".equals(i.getStatus()) || "SKIPPED".equals(i.getStatus()));
        finishJob(job, "SUCCESS", null);
        afterJobDone(job, anyFailed);
    }

    private void processItem(JobRun job, JobItem item, RefValueProvider refProvider,
                             Set<String> failedOrSkipped, Set<String> selectedDefs) {
        item.setStatus("RUNNING");
        try {
            ConfigDefinition def = configDefService.getDefOrThrow(item.getDefCode());
            List<FieldDef> fields = configDefService.parseFields(def);
            switch (job.getKind()) {
                case "EXPORT" -> processExport(job, item, def);
                case "CHECK" -> processCheck(item, def, fields, refProvider);
                case "IMPORT" -> processImport(job, item, def, fields, refProvider, failedOrSkipped, selectedDefs);
                case "PUBLISH" -> processPublish(job, item, def, fields, refProvider, failedOrSkipped, selectedDefs);
                default -> throw new IllegalStateException("未知作业类型: " + job.getKind());
            }
        } catch (Exception e) {
            log.warn("作业项处理失败: jobId={} defCode={}", job.getId(), item.getDefCode(), e);
            item.setStatus("FAILED");
            item.setMessage("处理异常: " + e.getMessage());
            failedOrSkipped.add(item.getDefCode());
        }
    }

    // ================================ 各类作业处理 ================================

    /** 导出：按条件查询正式区，写入 ExportResult */
    private void processExport(JobRun job, JobItem item, ConfigDefinition def) {
        List<Condition> conditions = readConditions(item.getRequestJson());
        List<Map<String, Object>> rows = configDefService.queryRows(def.getCode(), conditions);
        String rowsJson = writeJson(rows);
        txTemplate.executeWithoutResult(tx -> {
            exportResultRepository.findByJobIdAndDefCode(job.getId(), def.getCode())
                    .ifPresent(exportResultRepository::delete);
            ExportResult er = new ExportResult();
            er.setJobId(job.getId());
            er.setDefCode(def.getCode());
            er.setRowsJson(rowsJson);
            exportResultRepository.save(er);
        });
        item.setTotalRows(rows.size());
        item.setOkRows(rows.size());
        item.setErrorRows(0);
        item.setDetail(null);
        item.setMessage(null);
        item.setStatus("SUCCESS");
    }

    /** 检查：按拓扑序逐配置项校验，不写库；ref 参考正式区 + 本作业前序配置项的数据 */
    private void processCheck(JobItem item, ConfigDefinition def, List<FieldDef> fields,
                              RefValueProvider refProvider) {
        List<Map<String, Object>> rows = readRows(item.getRequestJson());
        List<RowError> errors = configValidator.validate(fields, rows, refProvider);
        applyValidationResult(item, rows.size(), errors);
        // 无论是否有错误，本配置项数据对后续配置项的 ref 校验可见
        refProvider.overlayRows(def.getCode(), fields, rows);
    }

    /** 导入：再次校验 → 通过的配置项写入暂存区；依赖失败的配置项其后置依赖项跳过 */
    private void processImport(JobRun job, JobItem item, ConfigDefinition def, List<FieldDef> fields,
                               RefValueProvider refProvider, Set<String> failedOrSkipped, Set<String> selectedDefs) {
        String failedDep = findFailedDependency(def, failedOrSkipped, selectedDefs);
        if (failedDep != null) {
            markSkipped(item, failedDep);
            failedOrSkipped.add(def.getCode());
            return;
        }
        List<Map<String, Object>> rows = readRows(item.getRequestJson());
        List<RowError> errors = configValidator.validate(fields, rows, refProvider);
        applyValidationResult(item, rows.size(), errors);
        if (!"SUCCESS".equals(item.getStatus())) {
            failedOrSkipped.add(def.getCode());
            return;
        }
        txTemplate.executeWithoutResult(tx -> {
            stagingRepository.deleteByTaskIdAndDefCode(job.getTaskId(), def.getCode());
            int rowNo = 1;
            for (Map<String, Object> row : rows) {
                StagingConfigData staging = new StagingConfigData();
                staging.setTaskId(job.getTaskId());
                staging.setDefCode(def.getCode());
                staging.setRowNo(rowNo++);
                staging.setDataJson(writeJson(row));
                stagingRepository.save(staging);
            }
        });
        refProvider.overlayRows(def.getCode(), fields, rows);
    }

    /** 发布：再次校验（ref 参考正式区 + 本作业已发布配置项的新数据）→ 正式区全量替换 */
    private void processPublish(JobRun job, JobItem item, ConfigDefinition def, List<FieldDef> fields,
                                RefValueProvider refProvider, Set<String> failedOrSkipped, Set<String> selectedDefs) {
        String failedDep = findFailedDependency(def, failedOrSkipped, selectedDefs);
        if (failedDep != null) {
            markSkipped(item, failedDep);
            failedOrSkipped.add(def.getCode());
            return;
        }
        List<Map<String, Object>> rows = stagingRepository
                .findByTaskIdAndDefCodeOrderByRowNoAsc(job.getTaskId(), def.getCode()).stream()
                .map(s -> configDefService.parseData(s.getDataJson()))
                .toList();
        List<RowError> errors = configValidator.validate(fields, rows, refProvider);
        applyValidationResult(item, rows.size(), errors);
        if (!"SUCCESS".equals(item.getStatus())) {
            failedOrSkipped.add(def.getCode());
            return;
        }
        txTemplate.executeWithoutResult(tx -> {
            configDataRepository.deleteByDefCode(def.getCode());
            int rowNo = 1;
            for (Map<String, Object> row : rows) {
                ConfigData data = new ConfigData();
                data.setDefCode(def.getCode());
                data.setRowNo(rowNo++);
                data.setDataJson(writeJson(row));
                configDataRepository.save(data);
            }
        });
        // 发布语义为全量替换：后续配置项 ref 以新数据为准
        refProvider.overlayRows(def.getCode(), fields, rows);
    }

    // ================================ 辅助 ================================

    private void applyValidationResult(JobItem item, int totalRows, List<RowError> errors) {
        long errorRows = errors.stream()
                .filter(e -> !configValidator.isWarning(e))
                .map(RowError::rowNo).distinct().count();
        item.setTotalRows(totalRows);
        item.setErrorRows((int) errorRows);
        item.setOkRows(totalRows - (int) errorRows);
        item.setDetail(errors.isEmpty() ? null : writeJson(errors));
        if (errorRows > 0) {
            item.setStatus("FAILED");
            String message = errorRows + " 行校验失败";
            if (errors.size() >= ConfigValidator.MAX_DETAIL) {
                message += "（错误明细已截断，仅显示前 " + ConfigValidator.MAX_DETAIL + " 条）";
            }
            item.setMessage(message);
        } else {
            item.setStatus("SUCCESS");
            item.setMessage(errors.isEmpty() ? null : "存在 " + errors.size() + " 条警告");
        }
    }

    private void markSkipped(JobItem item, String failedDep) {
        item.setStatus("SKIPPED");
        item.setTotalRows(0);
        item.setOkRows(0);
        item.setErrorRows(0);
        item.setMessage("依赖的配置项 " + failedDep + " 处理失败，已跳过");
    }

    /** 在所选配置项范围内，找到本配置项依赖且已失败/被跳过的前置配置项 */
    private String findFailedDependency(ConfigDefinition def, Set<String> failedOrSkipped, Set<String> selectedDefs) {
        for (String dep : configDefService.parseDependsOn(def)) {
            if (selectedDefs.contains(dep) && failedOrSkipped.contains(dep)) {
                return dep;
            }
        }
        return null;
    }

    private void cancelRemaining(List<JobItem> items, int fromSeq) {
        for (JobItem item : items) {
            if (item.getSeq() >= fromSeq
                    && ("PENDING".equals(item.getStatus()) || "RUNNING".equals(item.getStatus()))) {
                item.setStatus("CANCELLED");
                item.setMessage("作业已取消");
                jobItemRepository.save(item);
            }
        }
    }

    private void finishJob(JobRun job, String status, String error) {
        JobRun fresh = jobRunRepository.findById(job.getId()).orElse(job);
        fresh.setStatus(status);
        fresh.setError(error);
        fresh.setCurrentItem(null);
        fresh.setFinishedAt(LocalDateTime.now());
        jobRunRepository.save(fresh);
    }

    private void afterJobDone(JobRun job, boolean anyFailed) {
        if ("EXPORT".equals(job.getKind()) || "PUBLISH".equals(job.getKind())) {
            taskRepository.findById(job.getTaskId()).ifPresent(task -> {
                if (anyFailed) {
                    task.setMessage("作业完成，但部分配置项处理失败");
                } else {
                    task.setStatus("COMPLETED");
                    task.setProgress(100);
                    task.setMessage(null);
                }
                task.setUpdatedAt(LocalDateTime.now());
                taskRepository.save(task);
            });
        }
    }

    private void markTaskInProgress(Long taskId) {
        taskRepository.findById(taskId).ifPresent(task -> {
            if ("DRAFT".equals(task.getStatus()) || "IN_PROGRESS".equals(task.getStatus())) {
                task.setStatus("IN_PROGRESS");
                task.setUpdatedAt(LocalDateTime.now());
                taskRepository.save(task);
            }
        });
    }

    private void markTaskFailed(Long taskId, String message) {
        taskRepository.findById(taskId).ifPresent(task -> {
            task.setStatus("FAILED");
            task.setMessage(message);
            task.setUpdatedAt(LocalDateTime.now());
            taskRepository.save(task);
        });
    }

    private void updateTaskProgress(Long taskId, int processed, int total) {
        taskRepository.findById(taskId).ifPresent(task -> {
            if (!"IN_PROGRESS".equals(task.getStatus())) {
                return;
            }
            int progress = total == 0 ? 0 : (int) Math.min(99, processed * 100L / total);
            task.setProgress(progress);
            task.setUpdatedAt(LocalDateTime.now());
            taskRepository.save(task);
        });
    }

    private List<Condition> readConditions(String requestJson) {
        if (requestJson == null || requestJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = om.readTree(requestJson).path("conditions");
            if (node.isMissingNode() || node.isNull()) {
                return List.of();
            }
            return om.readValue(node.toString(), CONDITION_LIST_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("作业条件解析失败", e);
        }
    }

    private List<Map<String, Object>> readRows(String requestJson) {
        if (requestJson == null || requestJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = om.readTree(requestJson).path("rows");
            if (node.isMissingNode() || node.isNull()) {
                return List.of();
            }
            return om.readValue(node.toString(), ROWS_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("作业数据行解析失败", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return om.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
