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
import com.example.configmgr.job.repo.JobRepository;
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
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /** 指纹摘要只允许写到仍在途的作业行；终态（含取消）后定向 UPDATE 影响 0 行。 */
    private static final List<Job.JobStatus> IN_FLIGHT =
            List.of(Job.JobStatus.PENDING, Job.JobStatus.RUNNING);

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
    private final JobRepository jobRepository;

    @Transactional
    public void run(Job job) {
        final Long jobId = job.getId();
        final Long taskId = job.getTaskId();
        registerRollbackConvergence(job);
        RunState state = new RunState();
        issueRepository.deleteByJobId(jobId);
        Map<String, Map<String, Map<String, Object>>> fingerprintSummary = new LinkedHashMap<>();
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
            processItems(job, sorted, fingerprintSummary, state);
        } catch (Exception e) {
            log.error("Precheck job failed", e);
            state.totalErrors++;
            terminalWriter.complete(job, Job.JobStatus.FAILED, state.totalErrors, state.totalWarnings,
                    state.processedRows, state.totalRows, doneEvent(job, Job.JobStatus.FAILED, state.totalErrors));
            return;
        }
        Job.JobStatus status = state.cancelled ? Job.JobStatus.CANCELLED
                : (state.totalErrors > 0 ? Job.JobStatus.FAILED : Job.JobStatus.COMPLETED);
        writeFingerprintSummary(jobId, fingerprintSummary);
        state.totalRows = JobProgressTotals.denominator(0, state.discoveredRows, 0);
        terminalWriter.complete(job, status, state.totalErrors, state.totalWarnings,
                state.processedRows, state.totalRows, doneEvent(job, status, state.totalErrors));
    }

    private void processItems(Job job, List<String> sorted,
                              Map<String, Map<String, Map<String, Object>>> fingerprintSummary, RunState state) {
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
            processItem(job, defCode, itemId, sorted.size(), fingerprintSummary, state);
            if (state.cancelled) {
                break;
            }
        }
    }

    private void processItem(Job job, String defCode, Long itemId, int itemCount,
                             Map<String, Map<String, Map<String, Object>>> fingerprintSummary, RunState state) {
        ItemState item = new ItemState();
        try {
            precheckItem(job, defCode, itemId, itemCount, fingerprintSummary, state, item);
        } catch (Exception e) {
            handleItemFailure(job, defCode, itemId, itemCount, state, item, e);
        }
    }

    private void precheckItem(Job job, String defCode, Long itemId, int itemCount,
                              Map<String, Map<String, Map<String, Object>>> fingerprintSummary,
                              RunState state, ItemState item) throws Exception {
        ConfigDefinition def = definitionService.findByCode(defCode);
        List<ConfigField> keyFields = def.getFields().stream()
                .filter(ConfigField::isKey).toList();
        Optional<TaskFile> fileOpt = taskFileRepository
                .findByTaskIdAndDefCodeAndFileType(job.getTaskId(), defCode, "UPLOAD");
        String usedFileType = "UPLOAD";
        if (fileOpt.isEmpty()) {
            fileOpt = taskFileRepository
                    .findByTaskIdAndDefCodeAndFileType(job.getTaskId(), defCode, "EXPORT");
            usedFileType = "EXPORT";
        }
        if (fileOpt.isEmpty()) {
            saveRowIssue(job, defCode, null, null, null, "未找到上传文件，请先上传配置数据文件");
            state.totalErrors++;
            progressService.finishItem(itemId, "FAILED", 0, 0);
            taskService.updateItemStatus(job.getTaskId(), defCode, "FAILED");
            return;
        }
        byte[] fileBytes = fileStorage.read(fileOpt.get().getStoragePath());
        fingerprintSummary.computeIfAbsent(defCode, key -> new LinkedHashMap<>())
                .put(usedFileType, digestOf(fileOpt.get().getStoragePath()));
        List<Map<String, Object>> rows = excelReader.read(def, fileBytes);
        item.defTotal = rows.size();
        state.discoveredRows += item.defTotal;
        state.totalRows = JobProgressTotals.denominator(0, state.discoveredRows,
                itemCount - state.startedItems);
        progressService.updateItem(itemId, 0, item.defTotal);
        progressService.snapshot(job.getId(), state.processedRows, state.totalRows);
        validateRows(job, def, defCode, keyFields, rows, itemId, state, item);
        if (state.cancelled) {
            return;
        }
        validateReferences(job, def, defCode, rows, item);
        state.totalErrors += item.rowErrors;
        state.totalWarnings += item.rowWarnings;
        item.rowsSeen = item.defTotal;
        state.finishedDefRows += item.defTotal;
        state.processedRows = state.finishedDefRows;
        progressService.finishItem(itemId, item.rowErrors > 0 ? "FAILED" : "COMPLETED",
                item.defTotal, item.defTotal);
        progressService.snapshot(job.getId(), state.processedRows, state.totalRows);
        taskService.updateItemStatus(job.getTaskId(), defCode,
                item.rowErrors > 0 ? "FAILED" : "CHECKED");
    }

    private void validateRows(Job job, ConfigDefinition def, String defCode, List<ConfigField> keyFields,
                              List<Map<String, Object>> rows, Long itemId, RunState state, ItemState item)
            throws InterruptedException {
        Map<String, Integer> keyIndex = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            item.rowsSeen = i + 1;
            item.rowErrors += validateRequiredFields(job, def, defCode, row, i);
            item.rowErrors += checkDuplicateKey(job, defCode, keyFields, keyIndex, row, i);
            if (i % appProperties.getJob().getBatchSize() == 0) {
                Thread.sleep(appProperties.getJob().getDemoBatchDelayMs());
                state.processedRows = state.finishedDefRows + item.rowsSeen;
                progressService.updateItem(itemId, item.rowsSeen, item.defTotal);
                progressService.snapshot(job.getId(), state.processedRows, state.totalRows);
                publishProgress(job, def, state.processedRows, state.totalRows);
                if (cancellationRegistry.isCancelled(job.getId())) {
                    state.cancelled = true;
                    progressService.finishItem(itemId, "CANCELLED", item.rowsSeen, item.defTotal);
                    break;
                }
            }
        }
    }

    private int validateRequiredFields(Job job, ConfigDefinition def, String defCode,
                                       Map<String, Object> row, int rowIndex) {
        int errors = 0;
        for (ConfigField f : def.getFields()) {
            if (f.isRequired()) {
                Object val = row.get(f.getCode());
                if (StringUtils.isBlank(ObjectUtils.toString(val, null))) {
                    saveRowIssue(job, defCode, f.getCode(), null, rowIndex + 2,
                            "必填字段 [" + f.getLabel() + "] 不能为空");
                    errors++;
                }
            }
        }
        return errors;
    }

    private int checkDuplicateKey(Job job, String defCode, List<ConfigField> keyFields,
                                  Map<String, Integer> keyIndex, Map<String, Object> row, int rowIndex) {
        String rowKey = buildRowKey(row, keyFields);
        if (!keyIndex.containsKey(rowKey)) {
            keyIndex.put(rowKey, rowIndex + 2);
            return 0;
        }
        saveRowIssue(job, defCode, null, rowKey, rowIndex + 2,
                "主键重复: " + rowKey + " (第 " + keyIndex.get(rowKey) + " 行已存在)");
        return 1;
    }

    private void validateReferences(Job job, ConfigDefinition def, String defCode,
                                    List<Map<String, Object>> rows, ItemState item) {
        for (ConfigField f : def.getFields()) {
            if (f.getFieldType() != ConfigField.FieldType.REFERENCE || f.getRefDefCode() == null) {
                continue;
            }
            Set<String> validValues = loadValidRefValues(
                    job.getTaskId(), f.getRefDefCode(), f.getRefFieldCode());
            item.rowErrors += validateReferenceField(job, defCode, f, validValues, rows);
        }
    }

    private int validateReferenceField(Job job, String defCode, ConfigField field,
                                       Set<String> validValues, List<Map<String, Object>> rows) {
        int errors = 0;
        for (int i = 0; i < rows.size(); i++) {
            Object v = rows.get(i).get(field.getCode());
            if (StringUtils.isBlank(ObjectUtils.toString(v, null))) {
                continue;
            }
            if (!validValues.contains(v.toString())) {
                saveRowIssue(job, defCode, field.getCode(), null, i + 2,
                        "引用字段 [" + field.getLabel() + "] 的值 " + v
                                + " 在配置 " + field.getRefDefCode() + " 中不存在");
                errors++;
            }
        }
        return errors;
    }

    private void saveRowIssue(Job job, String defCode, String fieldCode, String rowKey,
                              Integer rowIndex, String message) {
        ValidationIssue issue = new ValidationIssue();
        issue.setJobId(job.getId());
        issue.setDefCode(defCode);
        issue.setFieldCode(fieldCode);
        issue.setRowKey(rowKey);
        issue.setRowIndex(rowIndex);
        issue.setSeverity(ValidationIssue.Severity.ERROR);
        issue.setMessage(message);
        issueRepository.save(issue);
    }

    private void handleItemFailure(Job job, String defCode, Long itemId, int itemCount,
                                   RunState state, ItemState item, Exception e) {
        log.error("Precheck item failed defCode={}", defCode, e);
        saveRowIssue(job, defCode, null, null, null, "检查过程出错，请查看服务日志或稍后重试");
        state.totalErrors++;
        state.finishedDefRows += item.defTotal;
        state.processedRows = state.finishedDefRows;
        state.totalRows = JobProgressTotals.denominator(0, state.discoveredRows,
                itemCount - state.startedItems);
        progressService.compensateItemFailure(itemId,
                Math.max(item.defTotal, item.rowsSeen), item.defTotal);
        taskService.updateItemStatus(job.getTaskId(), defCode, "FAILED");
    }

    private static final class RunState {
        private boolean cancelled;
        private int totalErrors;
        private int totalWarnings;
        private int processedRows;
        private int discoveredRows;
        private int startedItems;
        private int finishedDefRows;
        private int totalRows;
    }

    private static final class ItemState {
        private int defTotal;
        private int rowsSeen;
        private int rowErrors;
        private int rowWarnings;
    }

    /** 单个文件类型的指纹（sha256 + size）。读盘失败记 WARN 并跳过 —— 该项随后走"摘要缺指纹"的从严分支。 */
    private Map<String, Object> digestOf(String storagePath) {
        try {
            var digest = fileStorage.digest(storagePath);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sha256", digest.sha256());
            entry.put("size", digest.size());
            return entry;
        }
        catch (Exception e) {
            log.warn("Failed to compute precheck file digest path={}", storagePath, e);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sha256", null);
            entry.put("size", null);
            return entry;
        }
    }

    /**
     * 把指纹摘要写进作业的 {@code result_json}（T3-4 激活死列；{@code setResultJson} 此前全仓零调用）。
     *
     * <p>I-2：只在父作业仍在途时做<b>定向</b>条件 UPDATE（只写 result_json）。
     * 旧实现 {@code findById + setResultJson + save} 在长事务里会随整行 UPDATE 把读出时刻的
     * status（RUNNING）写回，覆盖期间已提交的 CANCELLED；定向条件写不碰 status，
     * 且取消先提交时影响 0 行（摘要不再写入已取消作业）。
     *
     * <p>包级可见以便真实 H2 回归用例直测该写点；生产调用点仍只在 {@link #run}。
     */
    void writeFingerprintSummary(Long jobId, Map<String, Map<String, Map<String, Object>>> summary) {
        final String json;
        try {
            json = objectMapper.writeValueAsString(summary);
        }
        catch (Exception e) {
            // 摘要写失败不升级为作业失败：从严口径下"摘要缺失"在 IMPORT 侧就是 409 PRECHECK_STALE
            log.warn("Failed to serialize precheck fingerprint summary jobId={}", jobId, e);
            return;
        }
        int updated = jobRepository.writeResultJsonIfInFlight(jobId, json, IN_FLIGHT);
        if (updated == 0) {
            if (log.isDebugEnabled()) log.debug("Job already terminal, fingerprint summary not written jobId={}", jobId);
        }
    }

    private Map<String, Object> doneEvent(Job job, Job.JobStatus status, int errors) {
        return Map.of("jobId", job.getId(), "jobType", "PRECHECK",
                "status", status, "errors", errors);
    }

    /** 作业级 issue 的 {@code def_code} 哨兵（{@code validation_issues.def_code} 为 NOT NULL；同 ExportJobRunner 口径）。 */
    private ValidationIssue jobLevelIssue(Long jobId, Long taskId) {
        ValidationIssue issue = new ValidationIssue();
        issue.setJobId(jobId);
        issue.setDefCode("-");
        issue.setSeverity(ValidationIssue.Severity.ERROR);
        issue.setMessage("任务 #" + taskId + " 没有任何配置项（0 条目），预检查没有可校验的数据，作业未成功");
        return issue;
    }

    /**
     * 回滚收敛（BZ-02，与 {@link ExportJobRunner} 同款）：数据面事务非提交结局时补写 FAILED 终态。
     *
     * <p>{@link JobTerminalWriter#complete} 把终态登记在 {@code afterCommit}，回滚时那次登记永不执行；
     * 进度写（{@code REQUIRES_NEW}）却已独立提交 ⇒ 作业以 RUNNING 悬置到看门狗 30min 上限。
     * 补写走 {@code completeNow}（{@code finish} 的条件 UPDATE，先到者胜），不覆盖取消/既有终态，
     * 条目由 {@code finish} 的 FAILED 分支收敛。
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
                log.warn("Precheck job data transaction rolled back or commit failed, terminal FAILED written jobId={}",
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

    /**
     * 加载被引用配置的合法值集合：已发布数据 + 本任务暂存数据。
     */
    private Set<String> loadValidRefValues(Long taskId, String refDefCode, String refFieldCode) {
        Set<String> values = new HashSet<>();
        try {
            for (ConfigDataRow r : dataRowRepository.findByDefCodeOrderByRowKey(refDefCode)) {
                Map<String, Object> data = objectMapper.readValue(r.getDataJson(), new TypeReference<>() {});
                Object v = data.get(refFieldCode);
                if (StringUtils.isNotBlank(ObjectUtils.toString(v, null))) {
                    values.add(v.toString());
                }
            }
            for (ConfigStagingRow s : stagingRowRepository.findByTaskIdAndDefCode(taskId, refDefCode)) {
                Map<String, Object> data = objectMapper.readValue(s.getDataJson(), new TypeReference<>() {});
                Object v = data.get(refFieldCode);
                if (StringUtils.isNotBlank(ObjectUtils.toString(v, null))) {
                    values.add(v.toString());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load reference value set refDef={}", refDefCode, e);
        }
        return values;
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
