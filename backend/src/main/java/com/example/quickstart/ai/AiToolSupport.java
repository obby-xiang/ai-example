package com.example.quickstart.ai;

import com.example.quickstart.common.BizException;
import com.example.quickstart.dto.CatalogDTO;
import com.example.quickstart.entity.Task;
import com.example.quickstart.runtime.SessionHolder;
import com.example.quickstart.service.CatalogService;
import com.example.quickstart.service.TaskService;
import org.springframework.ai.chat.model.ToolContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具公共支撑：会话ID提取、活动任务定位、目录查询。
 */
public abstract class AiToolSupport {

    protected final TaskService taskService;
    protected final CatalogService catalogService;
    protected final SessionHolder sessionHolder;

    protected AiToolSupport(TaskService taskService, CatalogService catalogService,
                            SessionHolder sessionHolder) {
        this.taskService = taskService;
        this.catalogService = catalogService;
        this.sessionHolder = sessionHolder;
    }

    protected String sid(ToolContext ctx) {
        Object v = ctx.getContext().get("sessionId");
        if (v == null) {
            throw new BizException("工具上下文缺少 sessionId");
        }
        return String.valueOf(v);
    }

    protected Task requireActive(String sessionId) {
        Task task = activeTask(sessionId);
        if (task == null) {
            throw new BizException("当前会话没有进行中的任务，请先 create_task");
        }
        return task;
    }

    protected Task activeTask(String sessionId) {
        SessionHolder.TaskRef ref = sessionHolder.get(sessionId);
        if (ref == null || ref.taskId() == null) {
            return null;
        }
        try {
            return taskService.require(ref.taskId());
        } catch (Exception e) {
            return null;
        }
    }

    protected CatalogDTO.ConfigItem requireItem(String configCode) {
        return catalogService.listConfigs().stream()
                .filter(c -> c.getCode().equals(configCode)).findFirst()
                .orElseThrow(() -> new BizException("配置项不存在：" + configCode));
    }

    protected String shortId(String id) {
        return id == null ? "" : id.substring(0, Math.min(8, id.length())) + "…";
    }

    protected Map<String, Object> castMap(Map<?, ?> m) {
        Map<String, Object> r = new LinkedHashMap<>();
        m.forEach((k, v) -> r.put(String.valueOf(k), v));
        return r;
    }
}
