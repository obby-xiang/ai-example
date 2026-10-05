package com.example.quickstart.ai;

import com.example.quickstart.dto.Condition;
import com.example.quickstart.dto.TaskDto;
import com.example.quickstart.entity.JobRun;
import com.example.quickstart.repository.JobRunRepository;
import com.example.quickstart.service.ConfigDefService;
import com.example.quickstart.service.TaskService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后端工具执行器：在 Agent Loop 内同步执行 BACKEND 工具，
 * 结果作为 tool 消息回填给模型；前端只看到 tool_run 事件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackendToolExecutor {

    private static final TypeReference<List<Condition>> CONDITION_LIST_TYPE = new TypeReference<>() {
    };

    private final ConfigDefService configDefService;
    private final TaskService taskService;
    private final JobRunRepository jobRunRepository;
    private final ObjectMapper om;

    public Object execute(String name, Map<String, Object> args) {
        log.info("执行后端工具: {} args={}", name, args);
        try {
            return switch (name) {
                case "list_config_defs" -> listConfigDefs(args);
                case "get_config_def" -> getConfigDef(args);
                case "count_config_data" -> countConfigData(args);
                case "list_tasks" -> listTasks(args);
                case "get_task" -> getTask(args);
                case "create_task" -> createTask(args);
                default -> errorResult("未知的后端工具: " + name);
            };
        } catch (Exception e) {
            log.warn("后端工具执行失败: {}", name, e);
            return errorResult("工具执行失败: " + e.getMessage());
        }
    }

    private Object listConfigDefs(Map<String, Object> args) {
        String level = strArg(args, "level");
        List<Map<String, Object>> defs = configDefService.listDefs().stream()
                .filter(d -> level == null || level.equalsIgnoreCase(d.level()))
                .map(d -> {
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("code", d.code());
                    item.put("name", d.name());
                    item.put("level", d.level());
                    item.put("dependsOn", d.dependsOn());
                    item.put("rowCount", d.rowCount());
                    return item;
                })
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", defs.size());
        result.put("defs", defs);
        return result;
    }

    private Object getConfigDef(Map<String, Object> args) {
        String code = strArg(args, "code");
        if (code == null) {
            return errorResult("缺少必填参数: code");
        }
        return configDefService.getDef(code);
    }

    private Object countConfigData(Map<String, Object> args) {
        String code = strArg(args, "code");
        if (code == null) {
            return errorResult("缺少必填参数: code");
        }
        List<Condition> conditions = om.convertValue(args.get("conditions"), CONDITION_LIST_TYPE);
        long count = configDefService.countRows(code, conditions);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("count", count);
        return result;
    }

    private Object listTasks(Map<String, Object> args) {
        String type = strArg(args, "type");
        String status = strArg(args, "status");
        List<Map<String, Object>> tasks = taskService.list().stream()
                .filter(t -> type == null || type.equalsIgnoreCase(t.type()))
                .filter(t -> status == null || status.equalsIgnoreCase(t.status()))
                .limit(20)
                .map(t -> {
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("taskId", t.id());
                    item.put("taskNo", t.taskNo());
                    item.put("type", t.type());
                    item.put("name", t.name());
                    item.put("status", t.status());
                    item.put("currentStep", t.currentStep());
                    item.put("progress", t.progress());
                    return item;
                })
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", tasks.size());
        result.put("tasks", tasks);
        return result;
    }

    private Object getTask(Map<String, Object> args) {
        Long taskId = longArg(args, "taskId");
        if (taskId == null) {
            return errorResult("缺少必填参数: taskId");
        }
        TaskDto task = taskService.get(taskId);
        List<Map<String, Object>> recentJobs = jobRunRepository.findTop3ByTaskIdOrderByIdDesc(taskId).stream()
                .map(j -> {
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("jobId", j.getId());
                    item.put("kind", j.getKind());
                    item.put("status", j.getStatus());
                    item.put("processed", j.getProcessed());
                    item.put("total", j.getTotal());
                    item.put("currentItem", j.getCurrentItem());
                    return item;
                })
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task", task);
        result.put("recentJobs", recentJobs);
        return result;
    }

    private Object createTask(Map<String, Object> args) {
        String type = strArg(args, "type");
        String name = strArg(args, "name");
        if (type == null || name == null) {
            return errorResult("缺少必填参数: type/name");
        }
        TaskDto task = taskService.create(type, name);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.id());
        result.put("taskNo", task.taskNo());
        return result;
    }

    private Map<String, Object> errorResult(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", message);
        return result;
    }

    private String strArg(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private Long longArg(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v != null) {
            try {
                return Long.parseLong(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
