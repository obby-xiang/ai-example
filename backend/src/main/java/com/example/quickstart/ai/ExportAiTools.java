package com.example.quickstart.ai;

import com.example.quickstart.common.BizException;
import com.example.quickstart.common.JsonUtil;
import com.example.quickstart.dto.CatalogDTO;
import com.example.quickstart.entity.Task;
import com.example.quickstart.runtime.EventPublisher;
import com.example.quickstart.runtime.SessionHolder;
import com.example.quickstart.service.AsyncRunner;
import com.example.quickstart.service.CatalogService;
import com.example.quickstart.service.ConditionDTO;
import com.example.quickstart.service.ConfigDataService;
import com.example.quickstart.service.TaskService;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导出任务工具：仅在当前会话绑定的任务为导出类型时注册（渐进式披露第一层）。
 */
@Component
public class ExportAiTools extends AiToolSupport {

    private final EventPublisher events;
    private final ConfigDataService dataService;
    private final AsyncRunner asyncRunner;

    public ExportAiTools(TaskService taskService, CatalogService catalogService,
                         SessionHolder sessionHolder, EventPublisher events,
                         ConfigDataService dataService, AsyncRunner asyncRunner) {
        super(taskService, catalogService, sessionHolder);
        this.events = events;
        this.dataService = dataService;
        this.asyncRunner = asyncRunner;
    }

    @Tool(description = "【导出任务】查看某配置项可用的查询字段与操作符（不同配置项结构不同）")
    public List<Map<String, Object>> get_query_fields(
            @ToolParam(description = "配置项编码") String configCode) {
        CatalogDTO.ConfigItem item = requireItem(configCode);
        List<Map<String, Object>> r = new ArrayList<>();
        for (CatalogDTO.FieldMeta f : item.getFields()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", f.getCode());
            m.put("name", f.getName());
            m.put("dataType", f.getDataType());
            m.put("operators", ConfigDataService.operatorsFor(f.getDataType()));
            if (f.getOptions() != null) {
                m.put("options", f.getOptions());
            }
            r.add(m);
        }
        return r;
    }

    @Tool(description = "【导出任务】设置各配置项的查询条件。conditionsJson 格式：{\"配置项编码\":[{\"field\":\"字段编码\",\"op\":\"操作符\",\"value\":\"比较值\"}]}；不设条件的配置项导出全部数据。操作符：EQ/NE/CONTAINS/IN/GT/LT/BETWEEN")
    public Map<String, Object> set_query_conditions(
            @ToolParam(description = "条件 JSON 字符串") String conditionsJson,
            ToolContext ctx) {
        String sessionId = sid(ctx);
        Task task = requireActive(sessionId);
        taskService.assertStep(task, "SET_CONDITION");
        Map<String, List<ConditionDTO.Condition>> per;
        try {
            per = JsonUtil.read(conditionsJson, new TypeReference<Map<String, List<ConditionDTO.Condition>>>() {
            });
        } catch (Exception e) {
            throw new BizException("conditionsJson 格式不正确，应为 {\"配置项编码\":[{field,op,value}]}");
        }
        for (String code : taskService.selectionOf(task)) {
            dataService.queryPublished(code, per.getOrDefault(code, List.of()));
        }
        Map<String, Object> params = taskService.paramsOf(task);
        params.put("conditions", per);
        taskService.updateParams(task, params);
        taskService.publishUpdated(sessionId, task.getId());
        int nConds = per.values().stream().mapToInt(List::size).sum();
        events.publishActivity(sessionId, "set_query_conditions", "已设置查询条件（" + nConds + " 条）");
        return Map.of("saved", true, "hint", "条件已保存，可用 start_export 启动导出");
    }

    @Tool(description = "【导出任务】启动导出（异步执行，进度会推送给工作区）。导出完成后用户可在工作区在线编辑并下载 Excel")
    public Map<String, Object> start_export(ToolContext ctx) {
        String sessionId = sid(ctx);
        Task task = requireActive(sessionId);
        taskService.assertStep(task, "SET_CONDITION");
        taskService.assertSelection(taskService.paramsOf(task));
        events.publishActivity(sessionId, "start_export", "已启动导出");
        asyncRunner.runExport(task, sessionId);
        return Map.of("started", true, "hint", "导出已开始，可用 get_task_progress 查询进度；完成后用户可在左侧工作区查看与下载");
    }

    @Tool(description = "【导出任务】查看导出结果摘要：各配置项导出行数")
    public Map<String, Object> get_export_summary(ToolContext ctx) {
        String sessionId = sid(ctx);
        Task task = requireActive(sessionId);
        taskService.assertStepAtLeast(task, "EXECUTE_EXPORT");
        Object result = taskService.paramsOf(task).get("exportResult");
        if (!(result instanceof List<?> list)) {
            return Map.of("hint", "尚无导出结果");
        }
        List<Map<String, Object>> summary = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("configCode", m.get("configCode"));
                s.put("configName", m.get("configName"));
                s.put("rowCount", m.get("rowCount"));
                summary.add(s);
            }
        }
        return Map.of("configs", summary, "hint", "完整数据请在左侧工作区查看、编辑与下载");
    }
}
