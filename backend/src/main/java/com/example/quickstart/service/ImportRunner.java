package com.example.quickstart.service;

import com.example.quickstart.common.BizException;
import com.example.quickstart.dto.CatalogDTO;
import com.example.quickstart.entity.ConfigData;
import com.example.quickstart.entity.ConfigDef;
import com.example.quickstart.entity.Task;
import com.example.quickstart.runtime.EventPublisher;
import com.example.quickstart.runtime.ProgressManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导入 Runner：检查 → 导入（暂存）→ 发布（范围内替换）。
 * 依赖顺序：按 CONFIG_DEF.dependsOn 拓扑排序，被依赖者先行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportRunner {

    private final TaskService taskService;
    private final CatalogService catalogService;
    private final ConfigDataService dataService;
    private final ValidationService validation;
    private final ProgressManager progress;
    private final EventPublisher events;

    /* ---------------- 检查 ---------------- */

    public void runCheck(Task task, String sessionId) {
        List<String> selection = taskService.selectionOf(task);
        Map<String, Object> params = taskService.paramsOf(task);
        Map<String, Object> uploads = asMap(params.get("uploads"));
        ProgressManager.Job job = progress.begin(task.getId(), ProgressManager.Op.CHECK);
        List<Map<String, Object>> items = new ArrayList<>();
        try {
            task.setStatus(TaskService.EXECUTING);
            taskService.save(task);
            taskService.publishUpdated(sessionId, task.getId());

            int total = selection.size();
            int idx = 0;
            for (String code : topoOrder(selection)) {
                progress.checkCancelled(job);
                item(items, code, "RUNNING", "检查中", 0);
                publish(sessionId, job, task.getId(), "正在检查 " + code,
                        pct(idx, total), items);
                int rows = ValidationService.toUploadData(uploads.get(code)).rows().size();
                progress.simulateCost(rows);
                idx++;
            }

            ValidationService.CheckResult result = validation.validate(uploads);
            for (ValidationService.ConfigCheck c : result.configs()) {
                String msg = c.errorCount() > 0
                        ? "发现 " + c.errorCount() + " 个错误" + (c.warnCount() > 0 ? "、" + c.warnCount() + " 个警告" : "")
                        : (c.warnCount() > 0 ? "发现 " + c.warnCount() + " 个警告" : "通过");
                item(items, c.configCode(), c.errorCount() > 0 ? "ERROR" : "DONE", msg, 100);
            }
            params.put("checkResult", toCheckMap(result));
            taskService.updateParams(task, params);
            taskService.moveToStep(task, "CHECK");
            task.setStatus(TaskService.WAITING);
            taskService.save(task);
            publish(sessionId, job, task.getId(), result.hasError()
                    ? "检查完成：存在错误" : "检查完成：全部通过", 100, items);
            taskService.publishUpdated(sessionId, task.getId());
        } catch (BizException e) {
            if ("操作已取消".equals(e.getMessage())) {
                task.setStatus(TaskService.CANCELLED);
                taskService.markTerminal(task, TaskService.CANCELLED);
                publish(sessionId, job, task.getId(), "已取消", job.getPercent(), items);
                taskService.publishUpdated(sessionId, task.getId());
                return;
            }
            throw e;
        } finally {
            progress.finish(task.getId());
        }
    }

    private Map<String, Object> toCheckMap(ValidationService.CheckResult result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalErrors", result.totalErrors());
        m.put("totalWarnings", result.totalWarnings());
        m.put("hasError", result.hasError());
        m.put("messages", result.messages());
        List<Map<String, Object>> configs = new ArrayList<>();
        for (ValidationService.ConfigCheck c : result.configs()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("configCode", c.configCode());
            cm.put("configName", c.configName());
            cm.put("hasData", c.hasData());
            cm.put("rowCount", c.rowCount());
            cm.put("errorCount", c.errorCount());
            cm.put("warnCount", c.warnCount());
            List<Map<String, Object>> ims = new ArrayList<>();
            for (ValidationService.Issue i : c.issues()) {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("level", i.level());
                im.put("row", i.row());
                im.put("field", i.field());
                im.put("message", i.message());
                ims.add(im);
            }
            cm.put("issues", ims);
            configs.add(cm);
        }
        m.put("configs", configs);
        return m;
    }

    /* ---------------- 导入（暂存） ---------------- */

    public void runImport(Task task, String sessionId) {
        List<String> selection = taskService.selectionOf(task);
        Map<String, Object> params = taskService.paramsOf(task);
        Map<String, Object> uploads = asMap(params.get("uploads"));
        List<String> order = topoOrder(selection);
        ProgressManager.Job job = progress.begin(task.getId(), ProgressManager.Op.IMPORT);
        List<Map<String, Object>> items = new ArrayList<>();
        try {
            task.setStatus(TaskService.EXECUTING);
            taskService.save(task);
            taskService.publishUpdated(sessionId, task.getId());

            // 导入前重跑结构校验（快速失败）
            ValidationService.CheckResult pre = validation.validate(uploads);
            if (pre.hasError()) {
                for (ValidationService.ConfigCheck c : pre.configs()) {
                    item(items, c.configCode(), c.errorCount() > 0 ? "ERROR" : "DONE",
                            c.errorCount() > 0 ? "存在 " + c.errorCount() + " 个错误" : "通过", 100);
                }
                params.put("checkResult", toCheckMap(pre));
                taskService.updateParams(task, params);
                throw new BizException("导入前校验未通过，请先修复错误");
            }

            Map<String, Object> importResult = new LinkedHashMap<>();
            List<Map<String, Object>> summaries = new ArrayList<>();
            int total = order.size();
            int idx = 0;
            for (String code : order) {
                progress.checkCancelled(job);
                ConfigDef def = dataService.requireDef(code);
                ValidationService.UploadData data = ValidationService.toUploadData(uploads.get(code));
                item(items, code, "RUNNING", "导入中", 0);
                publish(sessionId, job, task.getId(), "正在导入 " + code, pct(idx, total), items);

                dataService.replaceStagedForScope(def.getId(), task.getId(), null,
                        data.rows(), dataService.requireFields(def.getId()));
                progress.simulateCost(data.rows().size());

                Map<String, Object> s = diffSummary(def, data.rows().size());
                summaries.add(s);
                item(items, code, "DONE", "已暂存 " + data.rows().size() + " 行", 100);
                publish(sessionId, job, task.getId(), code + " 导入完成", pct(idx + 1, total), items);
                idx++;
            }
            importResult.put("configs", summaries);
            importResult.put("message", "全部导入完成（暂存未发布）");
            params.put("importResult", importResult);
            taskService.updateParams(task, params);
            taskService.moveToStep(task, "IMPORT");
            task.setStatus(TaskService.WAITING);
            taskService.save(task);
            publish(sessionId, job, task.getId(), "导入完成（暂存未发布）", 100, items);
            taskService.publishUpdated(sessionId, task.getId());
        } catch (BizException e) {
            if ("操作已取消".equals(e.getMessage())) {
                taskService.markTerminal(task, TaskService.CANCELLED);
                publish(sessionId, job, task.getId(), "已取消", job.getPercent(), items);
                taskService.publishUpdated(sessionId, task.getId());
                return;
            }
            taskService.markTerminal(task, TaskService.FAILED);
            taskService.publishUpdated(sessionId, task.getId());
            publish(sessionId, job, task.getId(), "导入失败：" + e.getMessage(), job.getPercent(), items);
            throw e;
        } finally {
            progress.finish(task.getId());
        }
    }

    private Map<String, Object> diffSummary(ConfigDef def, int stagedRows) {
        List<ConfigData> published = dataService.stagedRows("__none__", def.getId());
        long publishedCount = dataRepo.countByDefIdAndStatus(def.getId(), "PUBLISHED");
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("configCode", def.getCode());
        s.put("configName", def.getName());
        s.put("stagedRows", stagedRows);
        s.put("publishedRows", publishedCount);
        return s;
    }

    /* ---------------- 发布 ---------------- */

    public void runPublish(Task task, String sessionId) {
        List<String> selection = taskService.selectionOf(task);
        Map<String, Object> params = taskService.paramsOf(task);
        List<String> order = topoOrder(selection);
        ProgressManager.Job job = progress.begin(task.getId(), ProgressManager.Op.PUBLISH);
        List<Map<String, Object>> items = new ArrayList<>();
        try {
            task.setStatus(TaskService.EXECUTING);
            taskService.save(task);
            taskService.publishUpdated(sessionId, task.getId());

            // 发布前校验：暂存数据完整性 + 依赖（用上传数据重跑）
            Map<String, Object> uploads = asMap(params.get("uploads"));
            ValidationService.CheckResult pre = validation.validate(uploads);
            if (pre.hasError()) {
                throw new BizException("发布前校验未通过：" + pre.messages());
            }

            int total = order.size();
            int idx = 0;
            for (String code : order) {
                progress.checkCancelled(job);
                ConfigDef def = dataService.requireDef(code);
                List<ConfigData> staged = dataService.stagedRows(task.getId(), def.getId());
                item(items, code, "RUNNING", "发布中", 0);
                publish(sessionId, job, task.getId(), "正在发布 " + code, pct(idx, total), items);

                // 范围内替换（事务内）：先删同范围 PUBLISHED，再把 STAGED 提升为 PUBLISHED
                dataService.publishStaged(def.getId(), staged);
                progress.simulateCost(staged.size());

                item(items, code, "DONE", "已发布 " + staged.size() + " 行", 100);
                publish(sessionId, job, task.getId(), code + " 发布完成", pct(idx + 1, total), items);
                idx++;
            }

            Map<String, Object> publishResult = new LinkedHashMap<>();
            publishResult.put("message", "全部发布完成");
            publishResult.put("configs", items);
            params.put("publishResult", publishResult);
            taskService.updateParams(task, params);
            taskService.moveToStep(task, "PUBLISH");
            taskService.markTerminal(task, TaskService.SUCCESS);
            publish(sessionId, job, task.getId(), "发布完成", 100, items);
            taskService.publishUpdated(sessionId, task.getId());
        } catch (BizException e) {
            if ("操作已取消".equals(e.getMessage())) {
                taskService.markTerminal(task, TaskService.CANCELLED);
                publish(sessionId, job, task.getId(), "已取消", job.getPercent(), items);
                taskService.publishUpdated(sessionId, task.getId());
                return;
            }
            taskService.markTerminal(task, TaskService.FAILED);
            publish(sessionId, job, task.getId(), "发布失败：" + e.getMessage(), job.getPercent(), items);
            taskService.publishUpdated(sessionId, task.getId());
            throw e;
        } finally {
            progress.finish(task.getId());
        }
    }

    private final com.example.quickstart.repository.ConfigDataRepository dataRepo;

    /* ---------------- 公共 ---------------- */

    /** 依赖拓扑排序：被依赖者先行（无依赖环校验，种子数据保证无环） */
    public List<String> topoOrder(List<String> codes) {
        List<CatalogDTO.ConfigItem> catalog = catalogService.listConfigs();
        Map<String, List<String>> deps = new LinkedHashMap<>();
        for (CatalogDTO.ConfigItem c : catalog) {
            List<String> d = c.getDependsOn() == null ? List.of()
                    : c.getDependsOn().stream().map(CatalogDTO.Dependency::def).toList();
            deps.put(c.getCode(), d);
        }
        List<String> result = new ArrayList<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        java.util.Set<String> visiting = new java.util.HashSet<>();
        for (String code : codes) {
            visit(code, codes, deps, visited, visiting, result);
        }
        return result;
    }

    private void visit(String code, List<String> scope, Map<String, List<String>> deps,
                       java.util.Set<String> visited, java.util.Set<String> visiting, List<String> out) {
        if (visited.contains(code) || !scope.contains(code)) {
            return;
        }
        if (!visiting.add(code)) {
            throw new BizException("配置项依赖存在循环：" + code);
        }
        for (String dep : deps.getOrDefault(code, List.of())) {
            visit(dep, scope, deps, visited, visiting, out);
        }
        visiting.remove(code);
        visited.add(code);
        if (!out.contains(code)) {
            out.add(code);
        }
    }

    private Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> r = new LinkedHashMap<>();
            m.forEach((k, v) -> r.put(String.valueOf(k), v));
            return r;
        }
        return new LinkedHashMap<>();
    }

    private void item(List<Map<String, Object>> items, String code, String status, String message, int percent) {
        Map<String, Object> it = items.stream()
                .filter(x -> code.equals(x.get("configCode"))).findFirst().orElse(null);
        if (it == null) {
            it = new LinkedHashMap<>();
            it.put("configCode", code);
            items.add(it);
        }
        it.put("status", status);
        it.put("message", message);
        it.put("percent", percent);
    }

    private int pct(int done, int total) {
        return total <= 0 ? 100 : (int) Math.round(done * 100.0 / total);
    }

    private void publish(String sessionId, ProgressManager.Job job, String taskId,
                         String message, int percent, List<Map<String, Object>> items) {
        job.setPercent(percent);
        job.setMessage(message);
        job.setItems(items);
        events.publishProgress(sessionId, new com.example.quickstart.runtime.Events.ProgressEvent(
                taskId, job.getOp().name(), "RUN", percent, message, items, false));
    }
}
