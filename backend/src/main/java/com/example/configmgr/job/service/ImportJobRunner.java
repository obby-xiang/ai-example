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

import java.util.*;

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

        // BZ-02 回滚收敛：本方法 @Transactional，异常路径的 terminalWriter.complete 走 afterCommit ——
        // 事务以回滚结局收场（提交期约束失败 / 标了 rollback-only 后的 UnexpectedRollbackException）时
        // 终态写一次都不会执行，作业停在 RUNNING：进度已被 REQUIRES_NEW 独立提交，同任务重跑又被
        // JOB_ALREADY_RUNNING 挡住，只能等看门狗 30min 上限。这里登记与 ExportJobRunner 同款的
        // afterCompletion 兜底。
        registerRollbackConvergence(job);

        boolean cancelled = false;
        int errorCount = 0;
        int processedRows = 0;
        int discoveredRows = 0; // 已发现行数（已开工配置项从文件读到的行数合计）
        int startedItems = 0;   // 已开工配置项数

        var items = taskItemRepository.findByTaskIdOrderBySortOrder(taskId);

        // BZ-01 零数据不假成功：零条目时循环一次都不跑，errorCount 恒 0 ⇒ 旧实现报 COMPLETED。
        if (items.isEmpty()) {
            issueRepository.save(jobLevelIssue(jobId, taskId));
            terminalWriter.complete(job, Job.JobStatus.FAILED, 1, 0, 0, 0,
                    doneEvent(job, Job.JobStatus.FAILED, 1));
            return;
        }

        List<String> defCodes = items.stream().map(i -> i.getDefCode()).toList();
        List<String> sorted = dependencyResolver.sort(defCodes);

        // ③ 分母下限：导入行数来自上传文件，开工前没有便宜来源 → 先按"配置项条数"写下界
        //    （每个未开工配置项各预留 1 行），读到文件后并入已发现行数并上调。
        int totalRows = JobProgressTotals.denominator(0, 0, sorted.size());
        progressService.markRunning(jobId, totalRows);

        try {
            for (String defCode : sorted) {
                if (cancellationRegistry.isCancelled(jobId)) {
                    cancelled = true;
                    break;
                }
                Long itemId = progressService.startItem(jobId, defCode);
                if (itemId == null) {
                    // 父作业已终态（收敛已提交）：终态作业下不得再新增在途条目（评审 I-1）。
                    // 走既有取消收尾：本方法末尾的终态写会被 CAS 丢弃，不会翻写任务态、不发 JOB_DONE。
                    cancelled = true;
                    break;
                }
                startedItems++;
                int defTotal = 0;
                int defProcessed = 0;

                try {
                    ConfigDefinition def = definitionService.findByCode(defCode);
                    List<ConfigField> keyFields = def.getFields().stream()
                            .filter(ConfigField::isKey).toList();

                    // Clear old staging rows for this def
                    stagingRowRepository.deleteByTaskIdAndDefCode(taskId, defCode);

                    // Find file
                    Optional<TaskFile> fileOpt = taskFileRepository
                            .findByTaskIdAndDefCodeAndFileType(taskId, defCode, "UPLOAD");
                    if (fileOpt.isEmpty()) {
                        throw new RuntimeException("未找到文件");
                    }

                    byte[] fileBytes = fileStorage.read(fileOpt.get().getStoragePath());
                    List<Map<String, Object>> rows = excelReader.read(def, fileBytes);
                    defTotal = rows.size();
                    discoveredRows += defTotal;
                    totalRows = JobProgressTotals.denominator(0, discoveredRows, sorted.size() - startedItems);
                    progressService.updateItem(itemId, 0, defTotal);
                    progressService.snapshot(jobId, processedRows, totalRows);

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
                        sr.setTaskId(taskId);
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
                            defProcessed += stagingBatch.size();
                            processedRows += stagingBatch.size();
                            stagingBatch.clear();
                            Thread.sleep(appProperties.getJob().getDemoBatchDelayMs());
                            // ⑤ 快照与 SSE 事件同数同刻：先落库、再推送，消除"事件领先于库"
                            progressService.updateItem(itemId, defProcessed, defTotal);
                            progressService.snapshot(jobId, processedRows, totalRows);
                            publishProgress(job, def, processedRows, totalRows);
                            if (cancellationRegistry.isCancelled(jobId)) {
                                cancelled = true;
                                progressService.finishItem(itemId, "CANCELLED", defProcessed, defTotal);
                                break;
                            }
                        }
                    }
                    if (!stagingBatch.isEmpty()) {
                        stagingRowRepository.saveAll(stagingBatch);
                        defProcessed += stagingBatch.size();
                        processedRows += stagingBatch.size();
                        progressService.updateItem(itemId, defProcessed, defTotal);
                        progressService.snapshot(jobId, processedRows, totalRows);
                    }
                    if (cancelled) break;

                    progressService.finishItem(itemId, "COMPLETED", defProcessed, defTotal);
                    taskService.updateItemStatus(taskId, defCode, "IMPORTED");

                } catch (Exception e) {
                    log.error("Import failed for {}: {}", defCode, e.getMessage(), e);
                    ValidationIssue issue = new ValidationIssue();
                    issue.setJobId(jobId);
                    issue.setDefCode(defCode);
                    issue.setSeverity(ValidationIssue.Severity.ERROR);
                    issue.setMessage("导入过程出错: " + e.getMessage());
                    issueRepository.save(issue);
                    errorCount++;
                    // I-4：成功路径已先写条目 COMPLETED（随后任务项更新失败），catch 走补偿降级
                    progressService.compensateItemFailure(itemId, defProcessed, defTotal);
                    taskService.updateItemStatus(taskId, defCode, "FAILED");
                }
            }
        } catch (Exception e) {
            log.error("Import job failed: {}", e.getMessage(), e);
            errorCount++;
            terminalWriter.complete(job, Job.JobStatus.FAILED, errorCount, 0,
                    processedRows, totalRows, doneEvent(job, Job.JobStatus.FAILED, errorCount));
            return;
        }

        Job.JobStatus status = cancelled ? Job.JobStatus.CANCELLED
                : (errorCount > 0 ? Job.JobStatus.FAILED : Job.JobStatus.COMPLETED);
        // 终态分母回归"真实工作量"（未开工预留清零）：全部跑完时 processed==total→100%
        totalRows = JobProgressTotals.denominator(0, discoveredRows, 0);
        terminalWriter.complete(job, status, errorCount, 0, processedRows, totalRows,
                doneEvent(job, status, errorCount));
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
                log.warn("导入作业 #{} 的数据面事务回滚/提交失败，补写 FAILED 终态（避免停在 RUNNING 挡重跑）",
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
