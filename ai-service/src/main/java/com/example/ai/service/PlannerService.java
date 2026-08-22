package com.example.ai.service;

import com.example.ai.dto.AiDTO;
import com.example.ai.tool.ScenarioDefinition;
import com.example.ai.tool.ToolDiscoveryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;

/**
 * Planner 服务 —— 业界 Plan-and-Execute 模式核心实现（响应经验 100029435）。
 *
 * 设计原则：
 *   1. Plan 阶段：调 LLM 生成完整 plan（只描述步骤，不指定具体参数）
 *   2. Execute 阶段（AiService 负责）：逐步执行，每步基于前一步结果推理
 *   3. 用户在 Plan 阶段一次确认整个计划，代替每步确认（业界主流 Cursor Composer / Claude Code）
 *
 * 业界对照：
 *   - LangGraph Plan-and-Execute 子图
 *   - Anthropic 三智能体 Harness 架构的 Planner
 *   - 业界长 horizon 任务研究：12 步任务加 4 个检查点成功率从 28% 升到 85%
 *
 * Planner 输出格式：
 *   {
 *     "plan": [
 *       { "order":1, "stepId":"SELECT_DEFS", "description":"...", "expectedTools":["..."], "autoExec":true },
 *       ...
 *     ],
 *     "summary": "简短中文摘要"
 *   }
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlannerService {

    private final ToolDiscoveryService toolDiscoveryService;
    private final ObjectMapper om = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Value("${app.ai.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${app.ai.chat-path:/chat/completions}")
    private String chatPath;

    @Value("${app.ai.model:deepseek-v4-flash}")
    private String model;

    @Value("${app.ai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.chat.options.temperature:0.2}")
    private double temperature;

    @Value("${spring.ai.openai.chat.options.max-tokens:4096}")
    private int maxTokens;

    private volatile RestClient cachedClient;

    private RestClient client() {
        if (cachedClient == null) {
            synchronized (this) {
                if (cachedClient == null) {
                    cachedClient = RestClient.builder().baseUrl(baseUrl).build();
                }
            }
        }
        return cachedClient;
    }

    /**
     * 生成执行计划。
     *
     * @param req 用户请求（含 message / currentScenario / currentStep）
     * @return plan 步骤列表 + 摘要
     */
    public PlannerResult generatePlan(AiDTO.ChatReq req) {
        String scenario = req.getCurrentScenario();
        String currentStep = req.getCurrentStep();

        // 1. 构造 planner system prompt
        String systemPrompt = buildPlannerSystemPrompt(scenario, currentStep);

        // 2. 构造 messages
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        String userContent = (req.getMessage() == null || req.getMessage().isBlank())
                ? "（请根据当前工作区状态给出执行计划）"
                : req.getMessage();
        messages.add(Map.of("role", "user", "content", userContent));

        // 3. 调 DeepSeek（不带 tools，让模型直接输出 JSON plan）
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("temperature", Math.max(0.0, temperature - 0.1)); // planner 用更低温度
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("messages", messages);
        // 用 response_format 强制 JSON 输出（DeepSeek 支持）
        Map<String, String> responseFormat = new LinkedHashMap<>();
        responseFormat.put("type", "json_object");
        requestBody.put("response_format", responseFormat);

        try {
            JsonNode root = callDeepSeekApi(requestBody);
            JsonNode content = root.path("choices").get(0).path("message").path("content");
            String contentStr = content.asText("");
            log.info("Planner 返回: {}", contentStr.substring(0, Math.min(500, contentStr.length())));
            return parsePlannerResponse(contentStr, scenario, currentStep);
        } catch (Exception e) {
            log.error("Planner 调用失败", e);
            // 兜底：用当前场景状态图直接生成默认 plan
            return buildFallbackPlan(scenario, currentStep, "AI 规划失败: " + e.getMessage());
        }
    }

    // ================================ System Prompt 构造 ================================

    private String buildPlannerSystemPrompt(String scenario, String currentStep) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("你是配置管理任务规划器。根据用户目标和当前场景状态图，生成可执行的计划。\n\n");

        sb.append("【输出格式 · 强制 JSON】\n");
        sb.append("{\n");
        sb.append("  \"summary\": \"<中文一句话摘要>\",\n");
        sb.append("  \"plan\": [\n");
        sb.append("    {\n");
        sb.append("      \"order\": 1,\n");
        sb.append("      \"stepId\": \"<步骤ID，必须来自下方状态图>\",\n");
        sb.append("      \"description\": \"<该步骤具体做什么，中文>\",\n");
        sb.append("      \"expectedTools\": [\"<工具名1>\", \"<工具名2>\"],\n");
        sb.append("      \"autoExec\": true\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n\n");

        sb.append("【规划规则】\n");
        sb.append("1. 只描述每个步骤做什么，不指定具体参数（参数在执行时基于上一步结果生成）\n");
        sb.append("2. stepId 必须来自当前场景的状态图，按顺序推进\n");
        sb.append("3. expectedTools 必须是该步骤允许的工具（来自状态图）\n");
        sb.append("4. autoExec: 只读/导航/选择类步骤 true，破坏性/不可逆步骤 false\n");
        sb.append("5. 不要规划超过 6 步，超过的合并\n");
        sb.append("6. 必须输出合法 JSON，不要 ```代码块，不要其他文字\n\n");

        // 注入当前场景状态图
        ScenarioDefinition sDef = toolDiscoveryService.getScenario(scenario);
        if (sDef == null) {
            sb.append("【当前场景】未指定（通用模式）\n");
        } else {
            sb.append("【当前场景】").append(sDef.getId()).append(" - ").append(sDef.getName()).append("\n");
            sb.append("【场景描述】").append(sDef.getDescription()).append("\n");
            sb.append("【状态图】\n");
            int order = 1;
            for (ScenarioDefinition.StepDefinition step : sDef.getSteps()) {
                sb.append(String.format("  %d. %s → %s | 工具: %s | %s\n",
                        order++,
                        step.getId(),
                        "END".equals(step.getNext()) ? "结束" : step.getNext(),
                        step.getTools(),
                        step.getDescription()));
            }
        }

        if (currentStep != null) {
            sb.append("\n【当前步骤】").append(currentStep).append("（从这一步开始规划，不重复已完成步骤）\n");
        }

        sb.append("\n【可用工具元数据】（autoExec/needConfirm 标签来自 tools.yaml 声明）\n");
        for (var t : toolDiscoveryService.listAll()) {
            sb.append(String.format("  - %s | kind=%s autoExec=%s needConfirm=%s | %s\n",
                    t.getName(), t.getKind(), t.isAutoExec(), t.isNeedConfirm(),
                    t.getDescription() == null ? "" : t.getDescription().substring(0, Math.min(60, t.getDescription().length()))));
        }

        return sb.toString();
    }

    // ================================ 响应解析 ================================

    public PlannerResult parsePlannerResponse(String content, String scenario, String currentStep) {
        if (content == null || content.isBlank()) {
            return buildFallbackPlan(scenario, currentStep, "Planner 返回空内容");
        }
        try {
            JsonNode root = om.readTree(content);
            JsonNode planArr = root.path("plan");
            if (!planArr.isArray() || planArr.isEmpty()) {
                return buildFallbackPlan(scenario, currentStep, "Planner 未返回 plan 数组");
            }
            List<AiDTO.PlanStep> steps = new ArrayList<>();
            int order = 1;
            for (JsonNode sNode : planArr) {
                AiDTO.PlanStep step = AiDTO.PlanStep.builder()
                        .order(sNode.path("order").asInt(order++))
                        .stepId(sNode.path("stepId").asText(""))
                        .description(sNode.path("description").asText(""))
                        .expectedTools(toStringList(sNode.path("expectedTools")))
                        .autoExec(sNode.path("autoExec").asBoolean(true))
                        .status("PENDING")
                        .build();
                if (step.getStepId() != null && !step.getStepId().isBlank()) {
                    steps.add(step);
                }
            }
            String summary = root.path("summary").asText("");
            return new PlannerResult(steps, summary, null);
        } catch (JsonProcessingException e) {
            log.warn("Planner 响应解析失败: {}", content.substring(0, Math.min(200, content.length())), e);
            return buildFallbackPlan(scenario, currentStep, "Planner 响应解析失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(JsonNode node) {
        if (node == null || !node.isArray()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (JsonNode n : node) result.add(n.asText(""));
        return result;
    }

    /** 兜底：用当前场景状态图直接生成默认 plan（不走 LLM） */
    public PlannerResult buildFallbackPlan(String scenario, String currentStep, String reason) {
        ScenarioDefinition sDef = toolDiscoveryService.getScenario(scenario);
        List<AiDTO.PlanStep> steps = new ArrayList<>();
        if (sDef != null) {
            boolean started = currentStep == null;
            int order = 1;
            for (ScenarioDefinition.StepDefinition step : sDef.getSteps()) {
                if (!started && step.getId().equals(currentStep)) {
                    started = true;
                }
                if (started) {
                    // 简单规则：含破坏性工具（table_delete/update/replace）的步骤 autoExec=false
                    boolean autoExec = true;
                    for (String tool : step.getTools()) {
                        var t = toolDiscoveryService.getTool(tool);
                        if (t != null && t.isNeedConfirm()) {
                            autoExec = false;
                            break;
                        }
                    }
                    steps.add(AiDTO.PlanStep.builder()
                            .order(order++)
                            .stepId(step.getId())
                            .description(step.getDescription())
                            .expectedTools(step.getTools())
                            .autoExec(autoExec)
                            .status("PENDING")
                            .build());
                }
            }
        }
        String summary = "兜底规划: " + (reason == null ? "默认场景状态图推进" : reason);
        return new PlannerResult(steps, summary, reason);
    }

    // ================================ DeepSeek 调用 ================================

    private JsonNode callDeepSeekApi(Map<String, Object> requestBody) {
        String reqJson;
        try {
            reqJson = om.writeValueAsString(requestBody);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Planner 请求体序列化失败", e);
        }

        String respStr = client()
                .post()
                .uri(chatPath)
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(reqJson)
                .retrieve()
                .body(String.class);

        if (respStr == null || respStr.isBlank()) {
            throw new RuntimeException("DeepSeek 返回空响应");
        }
        try {
            JsonNode root = om.readTree(respStr);
            JsonNode err = root.path("error");
            if (!err.isMissingNode() && !err.isNull() && err.size() > 0) {
                throw new RuntimeException("DeepSeek 业务错误: " + err.toString());
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("DeepSeek 响应解析失败: " +
                    respStr.substring(0, Math.min(300, respStr.length())), e);
        }
    }

    /**
     * Planner 输出结果。
     *
     * @param steps  计划步骤列表
     * @param summary 中文摘要
     * @param fallbackReason 兜底原因（null 表示 LLM 正常返回）
     */
    public record PlannerResult(List<AiDTO.PlanStep> steps, String summary, String fallbackReason) {
        public boolean isFallback() { return fallbackReason != null; }
    }
}
