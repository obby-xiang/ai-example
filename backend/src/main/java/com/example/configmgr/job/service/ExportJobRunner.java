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
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

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

    /** {@code validation_issues.message} 的列宽（VARCHAR(1024)）——写入前必须保护。 */
    private static final int ISSUE_MESSAGE_MAX = 1024;

    /** 消息截断标记（列宽保护的可见痕迹）。 */
    private static final String ISSUE_MESSAGE_TRUNCATED = "…[已按列宽截断]";

    @Transactional
    public void run(Job job) {
        final Long jobId = job.getId();
        final Long taskId = job.getTaskId();

        // 回滚收敛口径（仅 EXPORT）：进度/终态写走 REQUIRES_NEW，数据面事务回滚时它们已经独立提交，
        // 作业会停在 RUNNING（进度满格）等看门狗 30min 判死。故登记一个 afterCompletion：
        // 非提交结局一律补写 FAILED（finish 的 PENDING/RUNNING 守卫保证不覆盖既有终态，也不会写 COMPLETED）。
        RunSnapshot snapshot = new RunSnapshot();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == TransactionSynchronization.STATUS_COMMITTED) {
                        return;
                    }
                    log.warn("导出作业 #{} 的数据面事务回滚，补写 FAILED 终态（避免停在 RUNNING）", jobId);
                    terminalWriter.completeNow(job, Job.JobStatus.FAILED, snapshot.errors, 0,
                            snapshot.processed, snapshot.total, doneEvent(job, Job.JobStatus.FAILED));
                }
            });
        }

        boolean cancelled = false;
        int errorCount = 0;
        int processedRows = 0;   // 已扫描行数（已完成配置项合计 + 当前配置项已扫描）
        int discoveredRows = 0;  // 已发现行数（已开工配置项的行数合计）
        int finishedDefRows = 0; // 已结束配置项的扫描行数合计
        int startedItems = 0;    // 已开工配置项数

        // 幂等清理（与 PrecheckJobRunner#run 同位置同口径）：重跑同一作业不叠加历史 issue
        issueRepository.deleteByJobId(jobId);

        var items = taskItemRepository.findByTaskIdOrderBySortOrder(taskId);

        // ③ 分母下限：导出作业的行数能便宜地预估（各配置已发布行数合计），故开工即写终值，
        //    不再"边发现边长"——首配置收尾瞬间不会出现 progress==total 的 100% 闪现。
        int estimatedRows = items.stream()
                .mapToInt(i -> (int) dataRowRepository.countByDefCode(i.getDefCode()))
                .sum();
        int totalRows = JobProgressTotals.denominator(estimatedRows, 0, items.size());

        progressService.markRunning(jobId, totalRows);

        try {
            for (var item : items) {
                if (cancellationRegistry.isCancelled(jobId)) {
                    cancelled = true;
                    break;
                }
                // 回滚收敛用的分片边界快照（本项开工时的已落库进度）
                snapshot.processed = processedRows;
                snapshot.total = totalRows;
                snapshot.errors = errorCount;
                String defCode = item.getDefCode();
                Long itemId = progressService.startItem(jobId, defCode);
                startedItems++;
                int defTotal = 0;
                int rowsSeen = 0;
                String storageKey = null;
                boolean persisted = false;

                try {
                    ConfigDefinition def = definitionService.findByCode(defCode);
                    // W-03 fail-fast（R-96 批 B）：条件解析统一走数据面同义的
                    // ConfigDataService#parseCondition —— 解析失败抛 IllegalArgumentException、
                    // 未知操作符在其内的 ConditionEvaluator.validate 早失败（与 /api/data/** 的 400
                    // 同一口径），由下面 catch 转成"该配置项 FAILED + 作业 FAILED"，
                    // 绝不静默放宽为全量导出（原私有 parseCondition 吞异常 return null 是病灶本体）。
                    QueryCondition cond = configDataService.parseCondition(item.getConditionJson());

                    // 慢临界段（红队 M1/C2，终审微补丁 F2b）：起点上移到本配置项重活区段开头 ——
                    // 全表载入 + 逐行过滤 + 单配置项大 sheet 写整段都无分片边界可续期心跳，大配置项
                    // 任一段都可能远超 90s 判死窗 —— hold 覆盖「载入→过滤→finishItem/snapshot→写」，
                    // finally 释放（幂等）；区段内的 snapshot/finishItem 是快 DB 写，被 hold 覆盖无副作用。
                    // 30min 绝对上限不受影响，泄漏也有上限兜底；项级 catch 在更外层，异常语义不变。
                    StoredFile storedFile = fileStorage.allocate(".xlsx");
                    storageKey = storedFile.key();
                    // 本项若在提交期回滚（含外层回滚），这次新写的导出件按回滚回收；
                    // 提交成功则保留（旧件由下面的 deleteAfterCommit 清理）。
                    fileStorage.deleteAfterRollback(storageKey);
                    List<Map<String, Object>> filtered = new ArrayList<>();
                    heartbeatRegistry.hold(jobId);
                    try {
                        // Load all rows matching condition（范围过滤 + 字段级过滤统一由 ConditionEvaluator 处理）
                        List<ConfigDataRow> allRows = dataRowRepository.findByDefCodeOrderByRowKey(defCode);
                        defTotal = allRows.size();
                        discoveredRows += defTotal;
                        totalRows = JobProgressTotals.denominator(estimatedRows, discoveredRows,
                                items.size() - startedItems);
                        progressService.updateItem(itemId, 0, defTotal);
                        progressService.snapshot(jobId, processedRows, totalRows);

                        for (ConfigDataRow r : allRows) {
                            rowsSeen++;
                            Map<String, Object> data = objectMapper.readValue(r.getDataJson(), new TypeReference<>() {});
                            if (!ConditionEvaluator.matches(data, cond)) {
                                continue;
                            }
                            filtered.add(data);
                            // Demo delay per batch
                            if (filtered.size() % appProperties.getJob().getBatchSize() == 0) {
                                Thread.sleep(appProperties.getJob().getDemoBatchDelayMs());
                                processedRows = finishedDefRows + rowsSeen;
                                // ⑤ 先落库再推送：快照与事件同一对数字
                                progressService.updateItem(itemId, rowsSeen, defTotal);
                                progressService.snapshot(jobId, processedRows, totalRows);
                                publishProgress(job, def, processedRows, totalRows);
                                if (cancellationRegistry.isCancelled(jobId)) {
                                    cancelled = true;
                                    progressService.finishItem(itemId, "CANCELLED", rowsSeen, defTotal);
                                    break;
                                }
                            }
                        }
                        if (cancelled) break;

                        // 本配置项扫描完毕（无论命中多少行）
                        rowsSeen = defTotal;
                        processedRows = finishedDefRows + rowsSeen;
                        finishedDefRows += defTotal;
                        totalRows = JobProgressTotals.denominator(estimatedRows, discoveredRows,
                                items.size() - startedItems);
                        progressService.finishItem(itemId, "COMPLETED", defTotal, defTotal);
                        progressService.snapshot(jobId, processedRows, totalRows);

                        // Write Excel（本慢临界段的尾段；ExcelWriter 签名保持字符串路径）
                        excelWriter.write(def, filtered, storedFile.pathString());
                    } finally {
                        heartbeatRegistry.release(jobId);
                    }

                    // Save TaskFile
                    Optional<TaskFile> existing = taskFileRepository
                            .findByTaskIdAndDefCodeAndFileType(taskId, defCode, "EXPORT");
                    TaskFile tf = existing.orElseGet(TaskFile::new);
                    String previousKey = existing.map(TaskFile::getStoragePath).orElse(null);
                    tf.setTaskId(taskId);
                    tf.setDefCode(defCode);
                    tf.setFileType("EXPORT");
                    tf.setStoragePath(storageKey);
                    tf.setOriginalPath(storageKey);
                    tf.setFileName(defCode + "_" + def.getName() + ".xlsx");
                    tf.setRowCount(filtered.size());
                    taskFileRepository.save(tf);
                    persisted = true;
                    if (previousKey != null && !previousKey.equals(storageKey)) {
                        // 旧导出件在本次提交成功后回收（失败/回滚时保持原引用可用）
                        fileStorage.deleteAfterCommit(previousKey);
                    }

                    taskService.updateItemStatus(taskId, defCode, "COMPLETED");

                } catch (Exception e) {
                    // 新件从未成为 task_files 的引用（save 未成功/未写盘）时它是纯孤儿：立即回收。
                    if (!persisted && storageKey != null) {
                        fileStorage.delete(storageKey);
                    }
                    // 数据面事务已被标记回滚（典型：内层参与事务抛错）：后续每项都只会白写盘、
                    // 提交时整批丢弃。立即抛出，交给外层 catch + 回滚收敛统一收尾。
                    if (rollbackOnly()) {
                        // 条目是 startItem 在 REQUIRES_NEW 里提交的，数据面回滚带不走它；若失败发生在
                        // finishItem 之前，直接抛会留下 jobs=FAILED + job_items=RUNNING。补偿本身是独立事务，
                        // 回滚后仍能落库；补偿失败只挂 suppressed，不得顶掉原始异常。
                        try {
                            progressService.compensateItemFailure(itemId, (int) Math.max(defTotal, rowsSeen), defTotal);
                        }
                        catch (RuntimeException compensationFailure) {
                            e.addSuppressed(compensationFailure);
                            log.warn("导出项 {} 回滚前的条目补偿失败（保留原异常）：{}", defCode,
                                    compensationFailure.toString());
                        }
                        throw e;
                    }
                    log.error("Export failed for def {}: {}", defCode, e.getMessage(), e);
                    saveIssue(jobId, defCode, issueMessage(e));
                    errorCount++;
                    finishedDefRows += defTotal;
                    processedRows = finishedDefRows;
                    totalRows = JobProgressTotals.denominator(estimatedRows, discoveredRows,
                            items.size() - startedItems);
                    // I-4：成功路径已先写条目 COMPLETED（随后写盘失败），catch 必须走补偿降级
                    progressService.compensateItemFailure(itemId, (int) Math.max(defTotal, rowsSeen), defTotal);
                    taskService.updateItemStatus(taskId, defCode, "FAILED");
                }
            }
        } catch (Exception e) {
            log.error("Export job failed: {}", e.getMessage(), e);
            errorCount++;
            snapshot.processed = processedRows;
            snapshot.total = totalRows;
            snapshot.errors = errorCount;
            // 事务已被标记回滚时，本方法内的任何写（issue/终态）都会在提交期被整批丢弃，
            // 终态交给 afterCompletion 的回滚收敛补写（FAILED）。
            if (!rollbackOnly()) {
                saveIssue(jobId, JOB_LEVEL_DEF_CODE, "导出作业失败: " + issueMessage(e));
                terminalWriter.complete(job, Job.JobStatus.FAILED, errorCount, 0,
                        processedRows, totalRows, doneEvent(job, Job.JobStatus.FAILED));
            }
            return;
        }

        Job.JobStatus status = cancelled ? Job.JobStatus.CANCELLED
                : (errorCount > 0 ? Job.JobStatus.FAILED : Job.JobStatus.COMPLETED);
        // R-153 B1：任务 COMPLETED/步骤联动收编进 terminalWriter 的落写成功分支（原先在此处
        // 无条件直写 updateStatus(COMPLETED)+goToStep 是取消竞态翻写点——取消胜出时这里仍会执行）。
        // 终态分母回归"真实工作量"（= 预估/已发现行数，未开工预留清零）：全部扫完时 processed==total→100%
        totalRows = JobProgressTotals.denominator(estimatedRows, discoveredRows, 0);
        snapshot.processed = processedRows;
        snapshot.total = totalRows;
        snapshot.errors = errorCount;
        terminalWriter.complete(job, status, errorCount, 0, processedRows, totalRows,
                doneEvent(job, status), "EXPORT");
    }

    /** 当前事务是否已标记回滚（此时提交会被整批丢弃，继续跑只是白写）。 */
    private static boolean rollbackOnly() {
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

    /**
     * 异常摘要（列宽保护）：<b>异常类名 + 摘要</b>，长度不超过 {@value #ISSUE_MESSAGE_MAX}。
     *
     * <p>类名置于消息<b>头</b>并在截断预算里先行扣除 —— 于是"类名"这一最有排障价值的部分
     * 永远不会被截掉（截断只吃掉摘要尾部，并在末尾留
     * {@value #ISSUE_MESSAGE_TRUNCATED} 痕迹）。没有这层保护时，超长异常消息会让 INSERT 因列宽失败，
     * 把"项级失败"升级成"作业级失败"（丢失其余配置项的处置）。
     */
    private static String issueMessage(Exception e) {
        String head = e.getClass().getSimpleName() + ": ";
        String detail = e.getMessage() == null ? "(无消息)" : e.getMessage();
        int budget = ISSUE_MESSAGE_MAX - head.length() - ISSUE_MESSAGE_TRUNCATED.length();
        if (detail.length() <= budget) {
            return head + detail;
        }
        return head + detail.substring(0, Math.max(0, budget)) + ISSUE_MESSAGE_TRUNCATED;
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
