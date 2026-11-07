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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        registerRollbackConvergence(job);
        RunState state = new RunState();
        var items = taskItemRepository.findByTaskIdOrderBySortOrder(taskId);
        if (items.isEmpty()) {
            issueRepository.save(jobLevelIssue(jobId, taskId));
            terminalWriter.complete(job, Job.JobStatus.FAILED, 1, 0, 0, 0,
                    doneEvent(job, Job.JobStatus.FAILED, 1));
            return;
        }
        List<String> defCodes = items.stream().map(i -> i.getDefCode()).toList();
        List<String> sorted = dependencyResolver.sort(defCodes);
        state.totalRows = JobProgressTotals.denominator(0, 0, sorted.size());
        progressService.markRunning(jobId, state.totalRows);
        try {
            processItems(job, sorted, state);
        } catch (Exception e) {
            log.error("Import job failed", e);
            state.errorCount++;
            terminalWriter.complete(job, Job.JobStatus.FAILED, state.errorCount, 0,
                    state.processedRows, state.totalRows, doneEvent(job, Job.JobStatus.FAILED, state.errorCount));
            return;
        }
        Job.JobStatus status = state.cancelled ? Job.JobStatus.CANCELLED
                : (state.errorCount > 0 ? Job.JobStatus.FAILED : Job.JobStatus.COMPLETED);
        state.totalRows = JobProgressTotals.denominator(0, state.discoveredRows, 0);
        terminalWriter.complete(job, status, state.errorCount, 0, state.processedRows, state.totalRows,
                doneEvent(job, status, state.errorCount));
    }

    private void processItems(Job job, List<String> sorted, RunState state) {
        for (String defCode : sorted) {
            if (cancellationRegistry.isCancelled(job.getId())) {
                state.cancelled = true;
                break;
            }
            Long itemId = progressService.startItem(job.getId(), defCode);
            if (itemId == null) {
                state.cancelled = true;
                break;
            }
            state.startedItems++;
            processItem(job, defCode, itemId, sorted.size(), state);
            if (state.cancelled) {
                break;
            }
        }
    }

    private void processItem(Job job, String defCode, Long itemId, int itemCount, RunState state) {
        ItemState item = new ItemState();
        try {
            importItem(job, defCode, itemId, itemCount, state, item);
        } catch (Exception e) {
            handleItemFailure(job, defCode, itemId, state, item, e);
        }
    }

    private void importItem(Job job, String defCode, Long itemId, int itemCount,
                            RunState state, ItemState item) throws Exception {
        ConfigDefinition def = definitionService.findByCode(defCode);
        List<ConfigField> keyFields = def.getFields().stream()
                .filter(ConfigField::isKey).toList();
        stagingRowRepository.deleteByTaskIdAndDefCode(job.getTaskId(), defCode);
        Optional<TaskFile> fileOpt = taskFileRepository
                .findByTaskIdAndDefCodeAndFileType(job.getTaskId(), defCode, "UPLOAD");
        if (fileOpt.isEmpty()) {
            throw new RuntimeException("Uploaded file not found");
        }
        byte[] fileBytes = fileStorage.read(fileOpt.get().getStoragePath());
        List<Map<String, Object>> rows = excelReader.read(def, fileBytes);
        item.defTotal = rows.size();
        state.discoveredRows += item.defTotal;
        state.totalRows = JobProgressTotals.denominator(0, state.discoveredRows,
                itemCount - state.startedItems);
        progressService.updateItem(itemId, 0, item.defTotal);
        progressService.snapshot(job.getId(), state.processedRows, state.totalRows);
        String scopeType = def.getLevel() == ConfigDefinition.ConfigLevel.GLOBAL ? "GLOBAL"
                : def.getLevel() == ConfigDefinition.ConfigLevel.REGION ? "REGION" : "PROJECT";
        stageRows(job, def, defCode, scopeType, keyFields, rows, itemId, state, item);
        if (state.cancelled) {
            return;
        }
        progressService.finishItem(itemId, "COMPLETED", item.defProcessed, item.defTotal);
        taskService.updateItemStatus(job.getTaskId(), defCode, "IMPORTED");
    }

    private void stageRows(Job job, ConfigDefinition def, String defCode, String scopeType,
                           List<ConfigField> keyFields, List<Map<String, Object>> rows, Long itemId,
                           RunState state, ItemState item) throws Exception {
        List<ConfigStagingRow> stagingBatch = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            stagingBatch.add(stagingRow(job, def, defCode, scopeType, keyFields, row));
            if (stagingBatch.size() >= appProperties.getJob().getBatchSize()) {
                saveStagingBatch(job, def, itemId, state, item, stagingBatch);
                if (state.cancelled) {
                    break;
                }
            }
        }
        if (!stagingBatch.isEmpty()) {
            stagingRowRepository.saveAll(stagingBatch);
            item.defProcessed += stagingBatch.size();
            state.processedRows += stagingBatch.size();
            progressService.updateItem(itemId, item.defProcessed, item.defTotal);
            progressService.snapshot(job.getId(), state.processedRows, state.totalRows);
        }
    }

    private ConfigStagingRow stagingRow(Job job, ConfigDefinition def, String defCode, String scopeType,
                                        List<ConfigField> keyFields, Map<String, Object> row) throws Exception {
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
        sr.setBaseVersion(dataRowRepository
                .findRow(defCode, scopeType, scopeKey, rowKey)
                .map(ConfigDataRow::getVersion)
                .orElse(null));
        return sr;
    }

    private void saveStagingBatch(Job job, ConfigDefinition def, Long itemId, RunState state,
                                  ItemState item, List<ConfigStagingRow> stagingBatch) throws InterruptedException {
        stagingRowRepository.saveAll(stagingBatch);
        item.defProcessed += stagingBatch.size();
        state.processedRows += stagingBatch.size();
        stagingBatch.clear();
        Thread.sleep(appProperties.getJob().getDemoBatchDelayMs());
        progressService.updateItem(itemId, item.defProcessed, item.defTotal);
        progressService.snapshot(job.getId(), state.processedRows, state.totalRows);
        publishProgress(job, def, state.processedRows, state.totalRows);
        if (cancellationRegistry.isCancelled(job.getId())) {
            state.cancelled = true;
            progressService.finishItem(itemId, "CANCELLED", item.defProcessed, item.defTotal);
        }
    }

    private void handleItemFailure(Job job, String defCode, Long itemId, RunState state,
                                   ItemState item, Exception e) {
        log.error("Import item failed defCode={}", defCode, e);
        ValidationIssue issue = new ValidationIssue();
        issue.setJobId(job.getId());
        issue.setDefCode(defCode);
        issue.setSeverity(ValidationIssue.Severity.ERROR);
        issue.setMessage("导入过程出错，请查看服务日志或稍后重试");
        issueRepository.save(issue);
        state.errorCount++;
        progressService.compensateItemFailure(itemId, item.defProcessed, item.defTotal);
        taskService.updateItemStatus(job.getTaskId(), defCode, "FAILED");
    }

    private static final class RunState {
        private boolean cancelled;
        private int errorCount;
        private int processedRows;
        private int discoveredRows;
        private int startedItems;
        private int totalRows;
    }

    private static final class ItemState {
        private int defTotal;
        private int defProcessed;
    }

    private Map<String, Object> doneEvent(Job job, Job.JobStatus status, int errorCount) {
        return Map.of("jobId", job.getId(), "jobType", "IMPORT",
                "status", status, "errors", errorCount);
    }

    /** 作业级 issue 的 {@code def_code} 哨兵（{@code validation_issues.def_code} 为 NOT NULL；同 ExportJobRunner 口径）。 */
    private ValidationIssue jobLevelIssue(Long jobId, Long taskId) {
        ValidationIssue issue = new ValidationIssue();
        issue.setJobId(jobId);
        issue.setDefCode("-");
        issue.setSeverity(ValidationIssue.Severity.ERROR);
        issue.setMessage("任务 #" + taskId + " 没有任何配置项（0 条目），导入作业没有可处理的数据，作业未成功");
        return issue;
    }

    /**
     * 回滚收敛（BZ-02，与 {@link ExportJobRunner} 同款）：数据面事务非提交结局时补写 FAILED 终态。
     *
     * <p>为什么必须补：{@link JobTerminalWriter#complete} 把终态登记在 {@code afterCommit}，
     * 事务回滚时那次登记永不执行；而 {@code markRunning}/{@code snapshot} 的进度写是
     * {@code REQUIRES_NEW}，早已独立提交 ⇒ 作业会以 RUNNING（进度满格）悬置到看门狗 30min 上限，
     * 期间互斥守卫挡住重跑。补写走 {@code completeNow}（{@code finish} 的条件 UPDATE，
     * PENDING/RUNNING → FAILED，先到者胜），故不覆盖取消或其它已落终态，
     * 条目由 {@code finish} 的 FAILED 分支一并收敛。
     */
    private void registerRollbackConvergence(Job job) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    return;
                }
                log.warn("Import job data transaction rolled back or commit failed, terminal FAILED written jobId={}",
                        job.getId());
                terminalWriter.completeNow(job, Job.JobStatus.FAILED, 1, 0, 0, 0,
                        doneEvent(job, Job.JobStatus.FAILED, 1));
            }
        });
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

    /** 行级进度事件：与 {@link JobProgressService#snapshot} 同一对数字，并携当前处理项（④ 纯增量字段）。 */
    private void publishProgress(Job job, ConfigDefinition def, int processed, int total) {
        int pct = total > 0 ? (processed * 100 / total) : 0;
        taskSseService.publish(job.getTaskId(), "JOB_PROGRESS", Map.of(
                "jobId", job.getId(), "jobType", job.getJobType(), "defCode", def.getCode(),
                "currentItem", def.getCode(), "currentItemName", def.getName(),
                "processed", processed, "total", total, "pct", pct));
    }
}
