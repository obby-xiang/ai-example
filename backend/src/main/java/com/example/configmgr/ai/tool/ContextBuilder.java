package com.example.configmgr.ai.tool;

import com.example.configmgr.ai.session.AiSession;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Builds the workspace context string that is injected into every LLM call.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextBuilder {

    private final TaskService taskService;
    private final ObjectMapper objectMapper;

    /**
     * Builds a compact snapshot of the current workspace state to inject into the system message.
     */
    public String buildContextMessage(AiSession session) {
        AiSession.WorkspaceContext ctx = session.getContext();
        StringBuilder sb = new StringBuilder();
        sb.append("## 当前工作区状态\n");
        sb.append("页面: ").append(ctx.getPage() != null ? ctx.getPage() : "任务中心").append("\n");

        if (ctx.getTaskId() != null) {
            try {
                Task task = taskService.findById(ctx.getTaskId());
                sb.append("任务: #").append(task.getId()).append(" [").append(task.getType().name())
                        .append("] ").append(task.getTitle()).append("\n");
                sb.append("状态: ").append(task.getStatus().name()).append("\n");
                sb.append("当前步骤: ").append(ctx.getStep() != null ? ctx.getStep() : task.getCurrentStep()).append("\n");

                List<String> selectedDefs = task.getItems().stream()
                        .map(i -> i.getDefCode()).toList();
                if (!selectedDefs.isEmpty()) {
                    sb.append("已选配置项: ").append(String.join(", ", selectedDefs)).append("\n");
                }
            } catch (Exception e) {
                sb.append("任务: #").append(ctx.getTaskId()).append(" (加载失败)\n");
            }
        }

        if (!ctx.getExtra().isEmpty()) {
            sb.append("额外上下文: ").append(ctx.getExtra()).append("\n");
        }

        return sb.toString();
    }

    /**
     * Builds the set of context tags for tool scope matching.
     * Returns tags like: {"*", "page:tasks", "task:EXPORT", "task:EXPORT/SELECT_DEFS"}
     */
    public Set<String> buildContextTags(AiSession.WorkspaceContext ctx) {
        Set<String> tags = new HashSet<>();
        tags.add("*");

        if (ctx.getPage() != null && !ctx.getPage().isBlank()) {
            tags.add("page:" + ctx.getPage());
        }

        if (ctx.getTaskType() != null) {
            tags.add("task:*");
            tags.add("task:" + ctx.getTaskType());
            if (ctx.getStep() != null) {
                tags.add("task:" + ctx.getTaskType() + "/" + ctx.getStep());
            }
        }

        return tags;
    }
}
