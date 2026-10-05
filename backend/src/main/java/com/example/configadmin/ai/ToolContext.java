package com.example.configadmin.ai;

import com.example.configadmin.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/** 工具执行上下文：当前会话 + 服务访问 + 上下文快照（影子状态）读写。 */
public class ToolContext {

    private final AiSession session;
    private final ObjectMapper mapper;
    private final ConfigDefService defService;
    private final ConfigDataService dataService;
    private final ExportService exportService;
    private final ImportService importService;
    private final TaskRunner taskRunner;

    public ToolContext(AiSession session, ObjectMapper mapper, ConfigDefService defService,
                       ConfigDataService dataService, ExportService exportService,
                       ImportService importService, TaskRunner taskRunner) {
        this.session = session;
        this.mapper = mapper;
        this.defService = defService;
        this.dataService = dataService;
        this.exportService = exportService;
        this.importService = importService;
        this.taskRunner = taskRunner;
    }

    public AiSession session() { return session; }
    public ObjectMapper mapper() { return mapper; }
    public ConfigDefService defs() { return defService; }
    public ConfigDataService data() { return dataService; }
    public ExportService exports() { return exportService; }
    public ImportService imports() { return importService; }
    public TaskRunner runner() { return taskRunner; }

    /** 当前工作区快照（影子状态）。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> snapshot() {
        return (Map<String, Object>) session.getContext();
    }

    /** 读取某页面影子状态。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> pageState(String page) {
        Object o = session.getContext().get(page);
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    /** 更新影子状态（工具写操作同步，前端经 ui_event 镜像）。 */
    public void updatePageState(String page, String key, Object value) {
        @SuppressWarnings("unchecked")
        Map<String, Object> ctx = (Map<String, Object>) session.getContext();
        Map<String, Object> pageState = (Map<String, Object>) ctx.computeIfAbsent(page,
                k -> new java.util.LinkedHashMap<String, Object>());
        pageState.put(key, value);
        // 任何写操作推进 workspaceVersion，便于版本竞态检测（参考业界 dataVersion 机制）
        Object v = ctx.getOrDefault("workspaceVersion", 0);
        ctx.put("workspaceVersion", (v instanceof Number n ? n.intValue() : 0) + 1);
    }
}
