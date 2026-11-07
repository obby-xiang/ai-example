package com.example.configmgr.job.service;

import com.example.configmgr.config.AppProperties;
import com.example.configmgr.data.entity.ConfigDataRow;
import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.data.service.ConditionEvaluator;
import com.example.configmgr.data.service.ConfigDataService;
import com.example.configmgr.data.service.QueryCondition;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.excel.ExcelWriter;
import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.file.StoredFile;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.ValidationIssue;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import com.example.configmgr.task.service.TaskSteps;
import com.example.configmgr.task.entity.TaskFile;
import com.example.configmgr.task.entity.TaskItem;
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
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private final ValidationIssueRepository issueRepository;
    private final ConfigDataService configDataService;
    private final JobHeartbeatRegistry heartbeatRegistry;

    /**
     * 作业级 issue 的 {@code def_code} 哨兵值（{@code validation_issues.def_code} 为 NOT NULL，
     * 作业级失败没有配置项归属；空串不可读、{@code null} 违反列约束，故用 {@code "-"}）。
     *
     * <p><b>已知缺口</b>：前端明细区按 {@code defCode} 与作业条目分组（{@code JobProgress.vue} 的
     * {@code detailOf(defCode)}），哨兵不落入任何分组 ⇒ 作业级 issue 在明细区不展示
     * （展示出口登记为观察项，本轮不实现）。
     */
    private static final String JOB_LEVEL_DEF_CODE = "-";

    @Transactional
    public void run(Job job) {
        final Long jobId = job.getId();
        final Long taskId = job.getTaskId();
        RunSnapshot snapshot = new RunSnapshot();
        registerRollbackConvergence(job, snapshot);
        RunState state = new RunState();
        issueRepository.deleteByJobId(jobId);
        var items = taskItemRepository.findByTaskIdOrderBySortOrder(taskId);
        if (items.isEmpty()) {
            saveIssue(jobId, JOB_LEVEL_DEF_CODE,
                    "任务 #" + taskId + " 没有任何配置项（0 条目），导出作业没有可处理的数据，作业未成功");
            terminalWriter.complete(job, Job.JobStatus.FAILED, 1, 0, 0, 0,
                    doneEvent(job, Job.JobStatus.FAILED));
            return;
        }
        int estimatedRows = items.stream()
                .mapToInt(i -> (int) dataRowRepository.countByDefCode(i.getDefCode()))
                .sum();
        state.totalRows = JobProgressTotals.denominator(estimatedRows, 0, items.size());
        progressService.markRunning(jobId, state.totalRows);
        try {
            processItems(job, items, estimatedRows, snapshot, state);
        } catch (Exception e) {
            log.error("Export job failed", e);
            state.errorCount++;
            snapshot.processed = state.processedRows;
            snapshot.total = state.totalRows;
            snapshot.errors = state.errorCount;
            if (!isRollbackOnly()) {
                saveIssue(jobId, JOB_LEVEL_DEF_CODE, "导出作业失败: " + issueMessage());
                terminalWriter.complete(job, Job.JobStatus.FAILED, state.errorCount, 0,
                        state.processedRows, state.totalRows, doneEvent(job, Job.JobStatus.FAILED));
            }
            return;
        }
        Job.JobStatus status = state.cancelled ? Job.JobStatus.CANCELLED
                : (state.errorCount > 0 ? Job.JobStatus.FAILED : Job.JobStatus.COMPLETED);
        state.totalRows = JobProgressTotals.denominator(estimatedRows, state.discoveredRows, 0);
        snapshot.processed = state.processedRows;
        snapshot.total = state.totalRows;
        snapshot.errors = state.errorCount;
        terminalWriter.complete(job, status, state.errorCount, 0, state.processedRows, state.totalRows,
                doneEvent(job, status), TaskSteps.EXPORT);
    }

    private void registerRollbackConvergence(Job job, RunSnapshot snapshot) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == TransactionSynchronization.STATUS_COMMITTED) {
                        return;
                    }
                    log.warn("Export job data transaction rolled back, terminal FAILED written to avoid stuck RUNNING jobId={}",
                            job.getId());
                    terminalWriter.completeNow(job, Job.JobStatus.FAILED, snapshot.errors, 0,
                            snapshot.processed, snapshot.total, doneEvent(job, Job.JobStatus.FAILED));
                }
            });
        }
    }

    private void processItems(Job job, List<TaskItem> items, int estimatedRows,
                              RunSnapshot snapshot, RunState state) throws Exception {
        for (TaskItem item : items) {
            if (cancellationRegistry.isCancelled(job.getId())) {
                state.cancelled = true;
                break;
            }
            snapshot.processed = state.processedRows;
            snapshot.total = state.totalRows;
            snapshot.errors = state.errorCount;
            String defCode = item.getDefCode();
            Long itemId = progressService.startItem(job.getId(), defCode);
            if (itemId == null) {
                state.cancelled = true;
                break;
            }
            state.startedItems++;
            processItem(job, item, itemId, items.size(), estimatedRows, state);
            if (state.cancelled) {
                break;
            }
        }
    }

    private void processItem(Job job, TaskItem sourceItem, Long itemId, int itemCount,
                             int estimatedRows, RunState state) throws Exception {
        ItemState item = new ItemState();
        String defCode = sourceItem.getDefCode();
        try {
            ConfigDefinition def = definitionService.findByCode(defCode);
            QueryCondition cond = configDataService.parseCondition(sourceItem.getConditionJson());
            StoredFile storedFile = fileStorage.allocate(".xlsx");
            item.storageKey = storedFile.key();
            fileStorage.deleteAfterRollback(item.storageKey);
            List<Map<String, Object>> filtered = filterAndWrite(job, def, defCode, cond, storedFile,
                    itemId, itemCount, estimatedRows, state, item);
            if (state.cancelled) {
                return;
            }
            persistTaskFile(job, def, defCode, filtered, item);
            taskService.updateItemStatus(job.getTaskId(), defCode, "COMPLETED");
        } catch (Exception e) {
            handleItemFailure(job, defCode, itemId, itemCount, estimatedRows, state, item, e);
        }
    }

    private List<Map<String, Object>> filterAndWrite(Job job, ConfigDefinition def, String defCode,
                                                     QueryCondition cond, StoredFile storedFile, Long itemId,
                                                     int itemCount, int estimatedRows, RunState state, ItemState item)
            throws Exception {
        List<Map<String, Object>> filtered = new ArrayList<>();
        heartbeatRegistry.hold(job.getId());
        try {
            List<ConfigDataRow> allRows = dataRowRepository.findByDefCodeOrderByRowKey(defCode);
            item.defTotal = allRows.size();
            state.discoveredRows += item.defTotal;
            state.totalRows = JobProgressTotals.denominator(estimatedRows, state.discoveredRows,
                    itemCount - state.startedItems);
            progressService.updateItem(itemId, 0, item.defTotal);
            progressService.snapshot(job.getId(), state.processedRows, state.totalRows);
            filtered = filterRows(job, def, cond, allRows, itemId, state, item, filtered);
            if (state.cancelled) {
                return filtered;
            }
            item.rowsSeen = item.defTotal;
            state.processedRows = state.finishedDefRows + item.rowsSeen;
            state.finishedDefRows += item.defTotal;
            state.totalRows = JobProgressTotals.denominator(estimatedRows, state.discoveredRows,
                    itemCount - state.startedItems);
            progressService.finishItem(itemId, "COMPLETED", item.defTotal, item.defTotal);
            progressService.snapshot(job.getId(), state.processedRows, state.totalRows);
            excelWriter.write(def, filtered, storedFile.pathString());
            return filtered;
        } finally {
            heartbeatRegistry.release(job.getId());
        }
    }

    private List<Map<String, Object>> filterRows(Job job, ConfigDefinition def, QueryCondition cond,
                                                 List<ConfigDataRow> allRows, Long itemId, RunState state,
                                                 ItemState item, List<Map<String, Object>> filtered) throws Exception {
        for (ConfigDataRow r : allRows) {
            item.rowsSeen++;
            Map<String, Object> data = objectMapper.readValue(r.getDataJson(), new TypeReference<>() {});
            if (!ConditionEvaluator.matches(data, cond)) {
                continue;
            }
            filtered.add(data);
            if (filtered.size() % appProperties.getJob().getBatchSize() == 0) {
                processFilterBatch(job, def, itemId, state, item);
                if (state.cancelled) {
                    break;
                }
            }
        }
        return filtered;
    }

    private void processFilterBatch(Job job, ConfigDefinition def, Long itemId,
                                    RunState state, ItemState item) throws InterruptedException {
        Thread.sleep(appProperties.getJob().getDemoBatchDelayMs());
        state.processedRows = state.finishedDefRows + item.rowsSeen;
        progressService.updateItem(itemId, item.rowsSeen, item.defTotal);
        progressService.snapshot(job.getId(), state.processedRows, state.totalRows);
        publishProgress(job, def, state.processedRows, state.totalRows);
        if (cancellationRegistry.isCancelled(job.getId())) {
            state.cancelled = true;
            progressService.finishItem(itemId, "CANCELLED", item.rowsSeen, item.defTotal);
        }
    }

    private void persistTaskFile(Job job, ConfigDefinition def, String defCode,
                                 List<Map<String, Object>> filtered, ItemState item) {
        Optional<TaskFile> existing = taskFileRepository
                .findByTaskIdAndDefCodeAndFileType(job.getTaskId(), defCode, "EXPORT");
        TaskFile tf = existing.orElseGet(TaskFile::new);
        String previousKey = existing.map(TaskFile::getStoragePath).orElse(null);
        tf.setTaskId(job.getTaskId());
        tf.setDefCode(defCode);
        tf.setFileType("EXPORT");
        tf.setStoragePath(item.storageKey);
        tf.setOriginalPath(item.storageKey);
        tf.setFileName(defCode + "_" + def.getName() + ".xlsx");
        tf.setRowCount(filtered.size());
        taskFileRepository.save(tf);
        item.persisted = true;
        if (previousKey != null && !previousKey.equals(item.storageKey)) {
            fileStorage.deleteAfterCommit(previousKey);
        }
    }

    private void handleItemFailure(Job job, String defCode, Long itemId, int itemCount,
                                   int estimatedRows, RunState state, ItemState item, Exception e) throws Exception {
        if (!item.persisted && item.storageKey != null) {
            fileStorage.delete(item.storageKey);
        }
        if (isRollbackOnly()) {
            try {
                progressService.compensateItemFailure(itemId,
                        Math.max(item.defTotal, item.rowsSeen), item.defTotal);
            } catch (RuntimeException compensationFailure) {
                e.addSuppressed(compensationFailure);
                log.warn("Export item compensation failed before rollback (original error kept) defCode={}",
                        defCode, compensationFailure);
            }
            throw e;
        }
        log.error("Export item failed defCode={}", defCode, e);
        saveIssue(job.getId(), defCode, issueMessage());
        state.errorCount++;
        state.finishedDefRows += item.defTotal;
        state.processedRows = state.finishedDefRows;
        state.totalRows = JobProgressTotals.denominator(estimatedRows, state.discoveredRows,
                itemCount - state.startedItems);
        progressService.compensateItemFailure(itemId,
                Math.max(item.defTotal, item.rowsSeen), item.defTotal);
        taskService.updateItemStatus(job.getTaskId(), defCode, "FAILED");
    }

    private static final class RunState {
        private boolean cancelled;
        private int errorCount;
        private int processedRows;
        private int discoveredRows;
        private int finishedDefRows;
        private int startedItems;
        private int totalRows;
    }

    private static final class ItemState {
        private int defTotal;
        private int rowsSeen;
        private String storageKey;
        private boolean persisted;
    }

    /** 当前事务是否已标记回滚（此时提交会被整批丢弃，继续跑只是白写）。 */
    private static boolean isRollbackOnly() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionAspectSupport.currentTransactionStatus().isRollbackOnly();
    }

    /**
     * 回滚收敛用的进度/计数快照（lambda 不能捕获可变局部量）。
     *
     * <p>只在分片边界更新：回滚时用的是"最后一次已完成项"的数字，与 {@code job_items} 的已提交
     * 进度可能相差当前项 —— 收敛要的是终态与可读数字，不是逐行精确。
     */
    private static final class RunSnapshot {

        private int processed;

        private int total;

        private int errors;

    }

    private Map<String, Object> doneEvent(Job job, Job.JobStatus status) {
        return Map.of("jobId", job.getId(), "jobType", "EXPORT", "status", status);
    }

    /**
     * 写一条 ERROR 级 {@link ValidationIssue}（T3-3）。
     *
     * <p>模板与 {@link ImportJobRunner} 的项级 catch 同款：只落 {@code jobId/defCode/severity/message}，
     * {@code rowKey}/{@code fieldCode} 留空（导出失败不是"某行某字段"的问题）。
     *
     * @param defCode 配置项编码；作业级失败用 {@link #JOB_LEVEL_DEF_CODE} 哨兵
     */
    private void saveIssue(Long jobId, String defCode, String message) {
        ValidationIssue issue = new ValidationIssue();
        issue.setJobId(jobId);
        issue.setDefCode(defCode);
        issue.setSeverity(ValidationIssue.Severity.ERROR);
        issue.setMessage(message);
        issueRepository.save(issue);
    }

    /** 对外只落通用失败说明；异常详情由对应日志保留。 */
    private static String issueMessage() {
        return "导出失败，请查看服务日志或稍后重试";
    }

    /** 行级进度事件：与 {@link JobProgressService#snapshot} 同一对数字，并携当前处理项（④ 纯增量字段）。 */
    private void publishProgress(Job job, ConfigDefinition def, int processed, int total) {
        int pct = total > 0 ? (processed * 100 / total) : 0;
        taskSseService.publish(job.getTaskId(), "JOB_PROGRESS", Map.of(
                "jobId", job.getId(), "jobType", job.getJobType(), "defCode", def.getCode(),
                "currentItem", def.getCode(), "currentItemName", def.getName(),
                "processed", processed, "total", total, "pct", pct));
    }
}
