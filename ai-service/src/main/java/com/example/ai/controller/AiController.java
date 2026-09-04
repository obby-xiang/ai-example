package com.example.ai.controller;

import com.example.ai.dto.AiDTO;
import com.example.ai.dto.R;
import com.example.ai.entity.AiChatMessage;
import com.example.ai.service.AiService;
import com.example.ai.service.PlannerService;
import com.example.ai.tool.ToolDefinition;
import com.example.ai.tool.ToolDiscoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;
    private final ToolDiscoveryService toolDiscoveryService;
    private final PlannerService plannerService;

    /** 发送聊天消息 */
    @PostMapping("/chat")
    public R<AiDTO.ChatResp> chat(@RequestBody AiDTO.ChatReq req) {
        return R.ok(aiService.chat(req));
    }

    /**
     * 前端工具执行结果回灌 —— 恢复暂停的 agent loop。
     * 协议：
     *   前端执行 ChatResp.toolCalls 中的工具后，POST {resumeToken, callId, result}
     *   后端恢复 loop，可能返回下一轮 toolCalls（继续暂停）或 done=true 的最终回复
     */
    @PostMapping("/tool-result")
    public R<AiDTO.ChatResp> toolResult(@RequestBody AiDTO.ToolResultReq req) {
        return R.ok(aiService.submitToolResult(req));
    }

    /**
     * 工具动态发现 —— 业界 MCP tools/list 的等价实现。
     * 按场景+步骤过滤工具集（progressive disclosure），AI 永远只看到当前步骤的工具。
     *
     * 查询参数:
     *   scenario - 场景 ID (EXPORT/IMPORT/ADD/MODIFY)
     *   step     - 步骤 ID (SELECT_SCENARIO/SELECT_DEFS/...)
     *   format   - 返回格式: raw(默认, 原生 OpenAI 协议) / meta(含 autoExec/needConfirm 元数据)
     */
    @GetMapping("/tools")
    public R<Object> tools(@RequestParam(required = false) String scenario,
                           @RequestParam(required = false) String step,
                           @RequestParam(defaultValue = "raw") String format) {
        if ("meta".equalsIgnoreCase(format)) {
            List<ToolDefinition> defs = toolDiscoveryService.listAvailableToolDefs(scenario, step);
            return R.ok(Map.of(
                    "scenario", scenario == null ? "ALL" : scenario,
                    "step", step == null ? "ALL" : step,
                    "count", defs.size(),
                    "tools", defs
            ));
        }
        List<Map<String, Object>> rawTools = toolDiscoveryService.listAvailableTools(scenario, step);
        return R.ok(Map.of(
                "scenario", scenario == null ? "ALL" : scenario,
                "step", step == null ? "ALL" : step,
                "count", rawTools.size(),
                "tools", rawTools
        ));
    }

    /** 列出所有场景定义（状态图） */
    @GetMapping("/scenarios")
    public R<Object> scenarios() {
        return R.ok(Map.of(
                "count", toolDiscoveryService.getScenarios().size(),
                "scenarios", toolDiscoveryService.getScenarios()
        ));
    }

    /**
     * 生成执行计划 —— 业界 Plan-and-Execute 模式 Phase 1。
     * 用户给目标，Planner 生成完整 plan（不执行），前端据此渲染 Plan 卡片让用户一次确认。
     *
     * 请求体同 ChatReq（含 message / currentScenario / currentStep）
     * 返回: { plan: [{order, stepId, description, expectedTools, autoExec, status}], summary, isFallback }
     */
    @PostMapping("/plan")
    public R<Object> generatePlan(@RequestBody AiDTO.ChatReq req) {
        PlannerService.PlannerResult result = plannerService.generatePlan(req);
        return R.ok(Map.of(
                "plan", result.steps(),
                "summary", result.summary(),
                "isFallback", result.isFallback(),
                "count", result.steps().size(),
                "currentScenario", req.getCurrentScenario() == null ? "ALL" : req.getCurrentScenario(),
                "currentStep", req.getCurrentStep() == null ? "ALL" : req.getCurrentStep()
        ));
    }

    /** 自动提示(由工作区触发) */
    @PostMapping("/auto-prompt")
    public R<AiDTO.ChatResp> autoPrompt(@RequestBody AiDTO.ChatReq req) {
        req.setAutoPrompt(true);
        if (req.getMessage() == null) req.setMessage("");
        return R.ok(aiService.chat(req));
    }

    /** 查询历史消息 */
    @GetMapping("/history")
    public R<List<AiChatMessage>> history(@RequestParam(required = false) Long taskId,
                                          @RequestParam(required = false) String sessionId) {
        return R.ok(aiService.listHistory(taskId, sessionId));
    }

    /**
     * ====== 改造 B1 ====== 查询当前 session 的 pending 交互（刷新恢复用）。
     * 前端 onMounted 调用：有未过期的 WAITING_TOOL state 则返回
     * { resumeToken, pendingToolCalls, expiresAt, mode, currentScenario, currentStep, interimContent }，
     * 前端据此重建交互卡片（CONFIRM→确认按钮，INPUT→表单）；无则返回 data=null。
     */
    @GetMapping("/pending")
    public R<Map<String, Object>> pending(@RequestParam String sessionId) {
        return R.ok(aiService.findPendingInteraction(sessionId));
    }

    /**
     * ====== 改造 F1 ====== 协作式取消当前 session 的运行/pending。
     * 前端「停止」按钮调用：作废活跃 WAITING_TOOL state（保留现场、不回滚），
     * 返回 { cancelled: n }。
     */
    @PostMapping("/cancel")
    public R<Map<String, Object>> cancel(@RequestBody Map<String, String> body) {
        int n = aiService.cancelRun(body == null ? null : body.get("sessionId"));
        return R.ok(Map.of("cancelled", n));
    }

    /** 清空任务聊天历史 */
    @DeleteMapping("/history")
    public R<Void> clearHistory(@RequestParam(required = false) Long taskId) {
        aiService.clear(taskId);
        return R.ok();
    }

    /** 心跳与配置信息(前端用于健康检查) */
    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        return R.ok(Map.of("status", "ok", "aiSupport", true, "time", System.currentTimeMillis()));
    }
}
