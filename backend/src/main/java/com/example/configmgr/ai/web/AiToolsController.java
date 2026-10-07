package com.example.configmgr.ai.web;

import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.common.ApiResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读工具披露端点（M1 收尾项⑤，S5.b §6.6 的 CI 稳定化建议）。
 *
 * <h2>它回答什么问题</h2>
 * "在当前工作区上下文（页面 / 任务类型 / 步骤）下，模型这一轮会看到哪些工具？" ——
 * 披露口径的唯一事实源是 {@link ToolRegistry#forContext}（与本端点同源），
 * 故本端点可替代"读后端 DEBUG 日志行「上下文=… 披露工具=[…]」"的验证手法，
 * 让 CI 不必依赖日志文件（{@code scripts/verify-e2e.ps1} TC15 已改用本端点）。
 *
 * <h2>为什么叫只读 / 无状态</h2>
 * <ul>
 * <li>只读：不建会话、不写 Redis、不落库、不消耗模型额度 —— 纯粹是工具清单的投影；</li>
 * <li>无状态：只按入参构造一次 {@link AiContext} 现算，不读当前对话/工作区的真实状态
 *     （要"模型此刻真的看到了什么"，看 {@code GET /api/ai/runs/{runId}} 里冻结的那份上下文）；</li>
 * <li>不依赖 AI key / Redis：{@link ToolRegistry} 是纯 Bean 扫描的注册表，
 *     故无 key 时本端点照常可用（它是排障面，不该被"模型未装配"遮住）。</li>
 * </ul>
 *
 * <h2>入参与上下文主键</h2>
 * 传 {@code taskType}+{@code step} 时上下文主键为 {@code task:<类型>/<步骤>}；
 * 只传 {@code page} 时为 {@code page:<页面>}；都不传为 {@code *}（全部登记工具）。
 * {@code taskId} 只回显、不参与裁剪（披露按标签，不按具体任务）。
 */
@RestController
@RequestMapping("/api/ai")
public class AiToolsController {

    private final ToolRegistry toolRegistry;

    public AiToolsController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @GetMapping("/tools")
    public ApiResponse<Map<String, Object>> tools(@RequestParam(required = false) String page,
                                                 @RequestParam(required = false) String taskType,
                                                 @RequestParam(required = false) String step,
                                                 @RequestParam(required = false) Long taskId) {
        AiContext ctx = AiContext.of(page, taskType, step, taskId, null);
        List<ToolCallback> disclosed = toolRegistry.forContext(ctx);

        List<Map<String, Object>> tools = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (ToolCallback callback : disclosed) {
            String name = callback.getToolDefinition().name();
            names.add(name);
            ToolMeta meta = toolRegistry.getMeta(name);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("displayName", meta == null ? callback.getToolDefinition().description() : meta.getDisplayName());
            item.put("riskLevel", toolRegistry.riskOf(name).name());
            item.put("channel", toolRegistry.channelOf(name).name());
            item.put("scopePatterns", meta == null ? List.of("*") : List.of(meta.getScopePatterns()));
            tools.add(item);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("context", ctx.getContextKey());
        body.put("page", ctx.getPage());
        body.put("taskType", ctx.getTaskType());
        body.put("step", ctx.getStep());
        body.put("taskId", ctx.getTaskId());
        body.put("count", names.size());
        body.put("registeredCount", toolRegistry.registeredCount());
        body.put("toolNames", names);
        body.put("tools", tools);
        return ApiResponse.ok(body);
    }
}
