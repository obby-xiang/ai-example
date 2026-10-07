package com.example.configmgr.ai.tool;

import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the workspace context string that is injected into every LLM call.
 * 上下文标签（工具渐进披露用）由 {@link ToolRegistry#forContext} 统一负责，这里只产出提示词文本。
 *
 * <h2>T9：{@code extra} 注入收敛（DC-14 / R3-1）</h2>
 * {@code extra} 是请求体里唯一"自由形态"的字段（前端塞工作区摘要、观测数据、任意附加信息都在这里），
 * 而它此前是<b>整张 Map 直接 toString 进系统消息</b> —— 于是"客户端想塞什么就塞什么、
 * 塞多大就多大"：一个几万字符的 extra 会静默吃掉整轮上下文（甚至把工具 Schema 挤出去）。
 * 本类按两条规则收紧：
 * <ol>
 * <li><b>键白名单</b>：只注入契约里定义的键（{@code stores/workspace.ts#buildContext} 的
 * {@code extra} 5 键）。非白名单键<b>不进系统消息</b>（仍原样留在快照里，供排障时查看）；</li>
 * <li><b>序列化截断</b>：每个值序列化后限长（超长即截断并标 {@code …}），整段 extra 另有总预算，
 * 超出即丢弃剩余键并注明"已省略 N 项" —— 上下文成本可控且"被省略"这件事对模型可见
 * （不静默消失）。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextBuilder {

    /**
     * 允许注入模型的 {@code extra} 键白名单（<b>有序</b>：整段预算截停的顺序即此序）——
     * 与前端工作区契约（{@code frontend/src/stores/workspace.ts#buildContext}）逐键对应。
     * 新增键必须<b>两侧同改</b>：前端多塞的键在这里会被丢弃（这是刻意行为，不是缺陷）。
     */
    public static final List<String> ALLOWED_EXTRA_KEYS = List.of("pageId", "selectedDefs", "importMode",
            "dataVersion", "contractVersion", "recentActions");

    /** 单个 extra 值序列化后的上限（字符）。 */
    public static final int EXTRA_VALUE_MAX_CHARS = 300;

    /** 整段 extra 的上限（字符）；超出即按白名单顺序截停并标注省略项数。 */
    public static final int EXTRA_TOTAL_MAX_CHARS = 800;

    private final TaskService taskService;

    private final ObjectMapper objectMapper;

    /**
     * Builds a compact snapshot of the current workspace state to inject into the system message.
     */
    public String buildContextMessage(AiContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 当前工作区状态\n");
        sb.append("页面: ").append(ctx.getPage() != null && !ctx.getPage().isBlank() ? ctx.getPage() : "任务中心").append("\n");

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

        String extra = renderExtra(ctx.getExtra());
        if (!extra.isEmpty()) {
            sb.append("额外上下文: ").append(extra).append("\n");
        }

        return sb.toString();
    }

    /**
     * 白名单 + 截断后的 extra 文本（T9）。空白/无白名单键时返回空串（不进系统消息）。
     */
    public String renderExtra(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        Map<String, String> allowed = new LinkedHashMap<>();
        for (String key : ALLOWED_EXTRA_KEYS) {
            Object value = raw.get(key);
            if (value != null) {
                allowed.put(key, truncate(serialize(value), EXTRA_VALUE_MAX_CHARS));
            }
        }
        if (allowed.isEmpty()) {
            return "";
        }
        Map<String, String> kept = new LinkedHashMap<>();
        int used = 0;
        int omitted = 0;
        for (Map.Entry<String, String> entry : allowed.entrySet()) {
            int cost = entry.getKey().length() + entry.getValue().length() + 3;
            if (used + cost > EXTRA_TOTAL_MAX_CHARS && !kept.isEmpty()) {
                omitted++;
                continue;
            }
            kept.put(entry.getKey(), entry.getValue());
            used += cost;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(kept);
        if (omitted > 0) {
            sb.append("（另有 ").append(omitted).append(" 项因超出上下文预算被省略）");
        }
        return sb.toString();
    }

    /** 值的序列化（对象走 Jackson，保证"给模型看的是 JSON 而不是 Java toString"）。 */
    private String serialize(Object value) {
        if (value instanceof String text) {
            return text;
        }
        try {
            return this.objectMapper.writeValueAsString(value);
        }
        catch (Exception ex) {
            log.debug("extra 值序列化失败，退化为文本：{}", ex.getMessage());
            return String.valueOf(value);
        }
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
