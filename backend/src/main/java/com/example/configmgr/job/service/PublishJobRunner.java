package com.example.configmgr.job.service;

import com.example.configmgr.config.AppProperties;
import com.example.configmgr.data.entity.ConfigDataRow;
import com.example.configmgr.data.entity.ConfigStagingRow;
import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.data.repo.ConfigStagingRowRepository;
import com.example.configmgr.data.service.ConfigDataService;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.definition.service.DependencyResolver;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.ValidationIssue;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.repo.TaskRepository;
import com.example.configmgr.task.service.TaskService;
import com.example.configmgr.task.service.TaskSseService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 发布作业执行器（ADR-7 + Q14 定案：行级 upsert + 范围差集删除）。
 *
 * <h2>数据面语义（Q14）</h2>
 * <ol>
 * <li>匹配键 = 配置项 × 业务键（含范围维度 scopeType/scopeKey）；</li>
 * <li>已存在行：更新字段值、<b>保留行 id</b>、版本递增（{@code OPTIMISTIC_FORCE_INCREMENT} 原语义）；</li>
 * <li>新业务键行：插入，版本从 1 起；</li>
 * <li>范围差集删除：本次导入覆盖范围内、未出现在导入数据中的旧 PUBLISHED 行删除
 *     （按导入模式执行，见 {@link #resolveImportMode}；MERGE 为纯增量 upsert）；</li>
 * <li>baseVersion 快照比对：导入时记快照，发布前比对当前版本，不一致 → 行级 issue + 作业 FAILED，
 *     文案沿用基座"发布冲突：行 X 在导入后被其他操作修改（快照版本 n，当前版本 m），请重新导入后再发布"；</li>
 * <li>事务边界：整个发布（upsert + 差集删除 + 暂存/任务状态推进）在<b>单个事务</b>内完成 —— 见 {@link #run}。</li>
 * </ol>
 *
 * <h2>事务边界与作业状态落库（S4.3 §1 事务行 / C6 整批回滚 / 修正⑤）</h2>
 * <p>
 * 发布数据面在 {@link TransactionTemplate} 内执行：任何硬错误（异常）→ 整批回滚，已发布数据零变化。
 * 但作业自身的状态（RUNNING、行级进度、终态、issue、job_item/task_item）必须<b>在数据面事务之外</b>写 ——
 * 否则（a）"失败"这一事实会随事务消失；（b）运行中的快照恒为 PENDING/0（修正⑤ 要消除的假象）。
 * 故本类不再是 {@code @Transactional}：
 * 作业行/作业条目经 {@link JobProgressService} 的独立事务实时可见，终态经
 * {@link JobTerminalWriter} 在数据面提交后落地并联动作业所属任务状态。
 * <p>
 * 软冲突（快照版本不一致、差集删除候选被并发保护）<b>不是异常</b>：只落行级 issue 并跳过该行，
 * 其余行照常提交（与 baseline B2.18 逐行语义一致，作业终态 FAILED）。
 */
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
    private final ValidationIssueRepository issueRepository;
    private final TaskSseService taskSseService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final JobCancellationRegistry cancellationRegistry;
    private final TaskService taskService;
    private final ConfigDataService configDataService;
    private final TransactionTemplate transactionTemplate;
    private final JobProgressService progressService;
    private final JobTerminalWriter terminalWriter;
    private final JobHeartbeatRegistry heartbeatRegistry;

    /** 发布入口：作业状态在数据面事务外编排（RUNNING 独立事务 + 失败补记 + 终态）。 */
    public void run(Job job) {
        progressService.markRunning(job.getId(), 0);

        PublishOutcome outcome = null;
        // 慢临界段，红队 M1/C2：整个发布数据面事务（REPLACE 差集删除 + upsert，单事务无分片心跳）
        // 可能远超 90s 判死窗 —— hold 豁免心跳判死，finally 释放（幂等）；30min 绝对上限不受影响
        heartbeatRegistry.hold(job.getId());
        try {
            outcome = transactionTemplate.execute(status -> doPublish(job));
        } catch (RuntimeException e) {
            String defCode = e instanceof PublishAbort abort ? abort.getDefCode() : null;
            log.error("Publish job {} aborted, whole batch rolled back (defCode={}): {}",
                    job.getId(), defCode, e.getMessage(), e);
            recordFailure(job, defCode, e);
            return;
        } finally {
            heartbeatRegistry.release(job.getId());
        }

        PublishOutcome finalOutcome = outcome != null ? outcome : new PublishOutcome(false, 0, 0, 0);
        Job.JobStatus status = finalOutcome.cancelled() ? Job.JobStatus.CANCELLED
                : (finalOutcome.totalErrors() > 0 ? Job.JobStatus.FAILED : Job.JobStatus.COMPLETED);
        // R-153 B1：任务 COMPLETED/步骤联动收编进 terminalWriter 落写成功分支（孪生翻写点同 Export）。
        terminalWriter.complete(job, status, finalOutcome.totalErrors(), 0,
                finalOutcome.processedRows(), finalOutcome.totalRows(),
                Map.of("jobId", job.getId(), "jobType", "PUBLISH",
                        "status", status, "errors", finalOutcome.totalErrors()),
                "PUBLISH");
    }

    /**
     * 发布数据面（单事务）：upsert + 范围差集删除 + 暂存/任务状态推进。
     * 抛出的任何异常都会让整个发布回滚（调用方 {@link #run} 负责失败补记）。
     * 作业行/作业条目的状态与进度写在这个事务之外（独立事务），故运行中即可见。
     */
    private PublishOutcome doPublish(Job job) {
        final Long jobId = job.getId();
        final Long taskId = job.getTaskId();

        issueRepository.deleteByJobId(jobId);

        // 导入模式：MERGE=增量合并（纯 upsert）；REPLACE=范围内替换（范围内未被本次导入覆盖的旧行删除）。
        // W-04 fail-fast（R-96 批 B）：settings_json 是坏 JSON ⇒ UNPARSEABLE，此处抛异常走整批回滚 +
        // 作业 FAILED + 作业级 issue（recordFailure 的 defCode=null 哨兵 "-" 路径）；
        // 「未配置」回落 MERGE 是正常语义，不在此列。
        ImportMode importMode = resolveImportMode(taskId);
        if (importMode == ImportMode.UNPARSEABLE) {
            throw new IllegalStateException("任务导入模式 settings_json 解析失败（importMode 不可读）"
                    + "——不静默降级 REPLACE→MERGE（W-04 fail-fast，R-96 批 B）");
        }
        boolean replaceMode = importMode == ImportMode.REPLACE;

        boolean cancelled = false;
        int totalErrors = 0;
        int processedRows = 0;
        int discoveredRows = 0;  // 已发现行数（已开工配置项暂存行数合计）
        int startedItems = 0;    // 已开工配置项数
        int finishedDefRows = 0;

        List<TaskItem> items = taskItemRepository.findByTaskIdOrderBySortOrder(taskId);
        List<String> defCodes = items.stream().map(TaskItem::getDefCode).toList();
        List<String> sorted = dependencyResolver.sort(defCodes);

        // ③ 分母下限：发布行的来源是暂存表，可便宜地逐配置预数 → 分母开工即写终值，
        //    首个配置项收尾瞬间不再出现 progress==total 的 100% 闪现（发现更多时仍会上调）。
        int estimatedRows = sorted.stream()
                .mapToInt(c -> (int) stagingRowRepository.countByTaskIdAndDefCode(taskId, c))
                .sum();
        int totalRows = JobProgressTotals.denominator(estimatedRows, 0, sorted.size());

        for (String defCode : sorted) {
            if (cancellationRegistry.isCancelled(jobId)) {
                cancelled = true;
                break;
            }

            Long itemId = progressService.startItem(jobId, defCode);
            if (itemId == null) {
                // 父作业已终态（收敛已提交）：终态作业下不得再新增在途条目（评审 I-1）。
                cancelled = true;
                break;
            }
            startedItems++;
            boolean defCancelled = false;
            int defProcessed = 0;
            int defTotal = 0;

            try {
                ConfigDefinition def = definitionService.findByCode(defCode);
                String scopeType = def.getLevel() == ConfigDefinition.ConfigLevel.GLOBAL ? "GLOBAL"
                        : def.getLevel() == ConfigDefinition.ConfigLevel.REGION ? "REGION" : "PROJECT";

                List<ConfigStagingRow> stagingRows = stagingRowRepository
                        .findByTaskIdAndDefCode(taskId, defCode);
                defTotal = stagingRows.size();
                discoveredRows += defTotal;
                totalRows = JobProgressTotals.denominator(estimatedRows, discoveredRows,
                        sorted.size() - startedItems);
                progressService.updateItem(itemId, 0, defTotal);
                progressService.snapshot(jobId, processedRows, totalRows);

                // 范围差集删除（REPLACE：本次导入覆盖范围内未出现在导入数据中的旧行删除）
                if (replaceMode) {
                    totalErrors += diffDelete(job, defCode, scopeType, stagingRows);
                }

                int processed = 0;
                for (ConfigStagingRow sr : stagingRows) {
                    // 暂存数据完整性守卫：解析失败即硬错误 → 整批回滚（不留半批）
                    objectMapper.readValue(sr.getDataJson(), new TypeReference<Map<String, Object>>() {
                    });

                    // 并发冲突检测：导入时刻快照的 baseVersion 与当前版本不一致 → 行级 issue，跳过该行
                    Optional<ConfigDataRow> existing = dataRowRepository
                            .findRow(defCode, sr.getScopeType(), sr.getScopeKey(), sr.getRowKey());
                    if (hasConflict(sr, existing)) {
                        saveConflictIssue(job, defCode, sr.getRowKey(),
                                "发布冲突：行 " + sr.getRowKey()
                                        + " 在导入后被其他操作修改（快照版本 " + sr.getBaseVersion()
                                        + "，当前版本 " + existing.map(ConfigDataRow::getVersion).orElse(null)
                                        + "），请重新导入后再发布");
                        totalErrors++;
                        sr.setStatus("FAILED");
                        stagingRowRepository.save(sr);
                    } else {
                        // 行级 upsert：已存在行保留 id 并强制版本递增，新业务键行插入
                        configDataService.upsertPublished(defCode, sr.getScopeType(), sr.getScopeKey(),
                                sr.getRowKey(), sr.getDataJson(), existing.orElse(null));
                        sr.setStatus("PUBLISHED");
                        stagingRowRepository.save(sr);
                    }

                    processed++;
                    if (processed % appProperties.getJob().getBatchSize() == 0) {
                        Thread.sleep(appProperties.getJob().getDemoBatchDelayMs());
                        defProcessed = processed;
                        processedRows = finishedDefRows + processed;
                        // ⑤ 先落库再推送：快照与事件同一对数字
                        progressService.updateItem(itemId, processed, defTotal);
                        progressService.snapshot(jobId, processedRows, totalRows);
                        publishProgress(job, def, processedRows, totalRows);
                        if (cancellationRegistry.isCancelled(jobId)) {
                            defCancelled = true;
                            progressService.finishItem(itemId, "CANCELLED", processed, defTotal);
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                throw new PublishAbort(defCode, e);
            }

            if (defCancelled) {
                cancelled = true;
                break;
            }

            defProcessed = defTotal;
            finishedDefRows += defTotal;
            processedRows = finishedDefRows;
            totalRows = JobProgressTotals.denominator(estimatedRows, discoveredRows, sorted.size() - startedItems);
            progressService.finishItem(itemId, "COMPLETED", defTotal, defTotal);
            progressService.snapshot(jobId, processedRows, totalRows);
            taskService.updateItemStatus(taskId, defCode, "PUBLISHED");
        }

        // 终态分母回归"真实工作量"（未开工预留清零）
        totalRows = JobProgressTotals.denominator(estimatedRows, discoveredRows, 0);
        return new PublishOutcome(cancelled, totalErrors, processedRows, totalRows);
    }

    /** 范围差集删除 + 并发保护冲突落 issue，返回冲突条数（软冲突，不阻断其余行）。 */
    private int diffDelete(Job job, String defCode, String scopeType, List<ConfigStagingRow> stagingRows) {
        LocalDateTime importedAt = stagingRows.stream()
                .map(ConfigStagingRow::getCreatedAt)
                .filter(Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(null);

        List<ConfigDataRow> protectedRows = configDataService
                .deleteRowsOutOfRange(defCode, scopeType, stagingRows, importedAt);

        int conflicts = 0;
        for (ConfigDataRow row : protectedRows) {
            saveConflictIssue(job, defCode, row.getRowKey(),
                    "发布冲突：行 " + row.getRowKey()
                            + " 在导入后被其他操作修改（快照版本 无，当前版本 " + row.getVersion()
                            + "），本次范围内替换未删除该行，请重新导入后再发布");
            conflicts++;
        }
        return conflicts;
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

    private void saveConflictIssue(Job job, String defCode, String rowKey, String message) {
        ValidationIssue issue = new ValidationIssue();
        issue.setJobId(job.getId());
        issue.setDefCode(defCode);
        issue.setRowKey(rowKey);
        issue.setSeverity(ValidationIssue.Severity.ERROR);
        issue.setMessage(message);
        issueRepository.save(issue);
    }

    /**
     * 失败补记（回滚之后、独立事务）：行级 issue + job_item/task_item 失败态 + 作业 FAILED 终态。
     * 数据面已整批回滚，"失败"这一事实本身必须落库，否则对外不可见。
     */
    private void recordFailure(Job job, String defCode, Exception cause) {
        int errors = 1;
        try {
            String message = "发布过程出错: " + cause.getMessage();
            for (String def : defCode != null ? List.of(defCode) : List.<String>of()) {
                saveConflictIssue(job, def, null, message);
                // I-4：成功路径已先写条目 COMPLETED（随后任务项/PUBLISHED 更新失败），走补偿降级
                progressService.compensateItemFailure(job.getId(), def,
                        0, (int) stagingRowRepository.countByTaskIdAndDefCode(job.getTaskId(), def));
            }
            if (defCode == null) {
                saveConflictIssue(job, "-", null, message);
            }
            if (defCode != null) {
                taskService.updateItemStatus(job.getTaskId(), defCode, "FAILED");
            }
        } catch (Exception e) {
            log.error("Publish job {} failure bookkeeping failed: {}", job.getId(), e.getMessage(), e);
        }
        terminalWriter.complete(job, Job.JobStatus.FAILED, errors, 0, 0,
                stagingRowRepository.findByTaskId(job.getTaskId()).size(),
                Map.of("jobId", job.getId(), "jobType", "PUBLISH",
                        "status", Job.JobStatus.FAILED, "errors", errors));
    }

    /**
     * 导入模式三态（W-04，R-96 批 B）：REPLACE=整体替换 / MERGE=增量合并 /
     * UNPARSEABLE=settings_json 解析失败（脏数据显式化，不静默降级 REPLACE→MERGE）。
     */
    private enum ImportMode { REPLACE, MERGE, UNPARSEABLE }

    /**
     * 任务导入模式解析（三态）：
     * <ul>
     * <li>{@code settings_json == null/blank} ⇒ {@link ImportMode#MERGE}
     *     （未配置 ⇒ 回落 MERGE 是<b>正常语义</b>，不可 fail-fast）；</li>
     * <li>{@code importMode == "REPLACE"}（忽略大小写）⇒ {@link ImportMode#REPLACE}；
     *     其余值（含 {@code "MERGE"}）⇒ {@link ImportMode#MERGE}；</li>
     * <li>settings 不可解析 ⇒ {@link ImportMode#UNPARSEABLE}，由调用方（{@link #doPublish}）
     *     抛异常走整批回滚 + 作业 FAILED + 作业级 issue（用户/AI 明确选的"整体替换"
     *     不得静默降级为"增量合并"）。</li>
     * </ul>
     * 任务行不存在 ⇒ MERGE（与旧行为一致）。
     */
    private ImportMode resolveImportMode(Long taskId) {
        return taskRepository.findById(taskId)
                .map(t -> {
                    String settings = t.getSettingsJson();
                    if (settings == null || settings.isBlank()) {
                        return ImportMode.MERGE;
                    }
                    try {
                        return "REPLACE".equalsIgnoreCase(
                                objectMapper.readTree(settings).path("importMode").asText("MERGE"))
                                ? ImportMode.REPLACE
                                : ImportMode.MERGE;
                    } catch (Exception e) {
                        return ImportMode.UNPARSEABLE;
                    }
                })
                .orElse(ImportMode.MERGE);
    }

    /** 行级进度事件：与 {@link JobProgressService#snapshot} 同一对数字，并携当前处理项（④ 纯增量字段）。 */
    private void publishProgress(Job job, ConfigDefinition def, int processed, int total) {
        int pct = total > 0 ? (processed * 100 / total) : 0;
        taskSseService.publish(job.getTaskId(), "JOB_PROGRESS", Map.of(
                "jobId", job.getId(), "jobType", job.getJobType(), "defCode", def.getCode(),
                "currentItem", def.getCode(), "currentItemName", def.getName(),
                "processed", processed, "total", total, "pct", pct));
    }

    /** 数据面执行结果：取消标记 + 错误数 + 行级进度（供事务外写终态）。 */
    private record PublishOutcome(boolean cancelled, int totalErrors, int processedRows, int totalRows) {
    }

    /** 硬错误载体：携带出错配置项，供事务回滚后的失败补记定位。 */
    private static class PublishAbort extends RuntimeException {
        private final String defCode;

        PublishAbort(String defCode, Throwable cause) {
            super(cause.getMessage(), cause);
            this.defCode = defCode;
        }

        String getDefCode() {
            return defCode;
        }
    }
}
