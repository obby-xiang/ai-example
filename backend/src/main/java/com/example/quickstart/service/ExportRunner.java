package com.example.quickstart.service;

import com.example.quickstart.common.BizException;
import com.example.quickstart.common.JsonUtil;
import com.example.quickstart.dto.CatalogDTO;
import com.example.quickstart.entity.Task;
import com.example.quickstart.runtime.EventPublisher;
import com.example.quickstart.runtime.ProgressManager;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导出 Runner：逐配置项查询 → 进度事件 → 结果存入任务参数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportRunner {

    private final TaskService taskService;
    private final CatalogService catalogService;
    private final ConfigDataService dataService;
    private final ProgressManager progress;
    private final EventPublisher events;

    public void run(Task task, String sessionId) {
        List<String> selection = taskService.selectionOf(task);
        Map<String, Object> params = taskService.paramsOf(task);
        Map<String, Object> conditions = asMap(params.get("conditions"));

        ProgressManager.Job job = progress.begin(task.getId(), ProgressManager.Op.EXPORT);
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            task.setStatus(TaskService.EXECUTING);
            taskService.save(task);
            taskService.publishUpdated(sessionId, task.getId());

            List<CatalogDTO.ConfigItem> catalog = catalogService.listConfigs();
            Map<String, CatalogDTO.ConfigItem> catalogByCode = new LinkedHashMap<>();
            for (CatalogDTO.ConfigItem c : catalog) {
                catalogByCode.put(c.getCode(), c);
            }

            int total = selection.size();
            int idx = 0;
            for (String code : selection) {
                progress.checkCancelled(job);
                CatalogDTO.ConfigItem item = catalogByCode.get(code);
                if (item == null) {
                    throw new BizException("配置项不存在：" + code);
                }
                updateItem(items, code, "RUNNING", "查询中", 0);
                publishProgress(sessionId, job, task.getId(), "查询配置 " + item.getName(),
                        percentOf(idx, total), items);

                List<ConditionDTO.Condition> conds = conditionsOf(conditions, code);
                List<Map<String, Object>> rows = dataService.queryPublished(code, conds);

                progress.simulateCost(rows.size());

                Map<String, Object> one = new LinkedHashMap<>();
                one.put("configCode", code);
                one.put("configName", item.getName());
                one.put("level", item.getLevel());
                one.put("fields", item.getFields());
                one.put("rows", rows);
                one.put("rowCount", rows.size());
                results.add(one);

                updateItem(items, code, "DONE", "已导出 " + rows.size() + " 行", 100);
                publishProgress(sessionId, job, task.getId(), item.getName() + " 导出完成（" + rows.size() + " 行）",
                        percentOf(idx + 1, total), items);
                idx++;
            }

            params.put("exportResult", results);
            taskService.updateParams(task, params);
            taskService.moveToStep(task, "EXECUTE_EXPORT");
            taskService.markTerminal(task, TaskService.SUCCESS);
            job.setDone(true);
            publishProgress(sessionId, job, task.getId(), "导出完成", 100, items);
            taskService.publishUpdated(sessionId, task.getId());
        } catch (BizException e) {
            if ("操作已取消".equals(e.getMessage())) {
                taskService.markTerminal(task, TaskService.CANCELLED);
                taskService.publishUpdated(sessionId, task.getId());
                job.setDone(true);
                publishProgress(sessionId, job, task.getId(), "已取消", job.getPercent(), items);
                return;
            }
            throw e;
        } finally {
            progress.finish(task.getId());
        }
    }

    private List<ConditionDTO.Condition> conditionsOf(Map<String, Object> conditions, String code) {
        Object obj = conditions.get(code);
        if (obj == null) {
            return List.of();
        }
        return JsonUtil.mapper().convertValue(obj, new TypeReference<List<ConditionDTO.Condition>>() {
        });
    }

    private Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> r = new LinkedHashMap<>();
            m.forEach((k, v) -> r.put(String.valueOf(k), v));
            return r;
        }
        return new LinkedHashMap<>();
    }

    private void updateItem(List<Map<String, Object>> items, String code, String status, String message, int percent) {
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

    private int percentOf(int done, int total) {
        return total <= 0 ? 100 : (int) Math.round(done * 100.0 / total);
    }

    private void publishProgress(String sessionId, ProgressManager.Job job, String taskId,
                                 String message, int percent, List<Map<String, Object>> items) {
        job.setPercent(percent);
        job.setMessage(message);
        job.setItems(items);
        events.publishProgress(sessionId, new com.example.quickstart.runtime.Events.ProgressEvent(
                taskId, "EXPORT", "RUN", percent, message, items, false));
    }
}
