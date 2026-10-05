package com.example.configmgr.ai.runtime;

import com.example.configmgr.ai.hitl.HitlManager;
import com.example.configmgr.ai.hitl.InteractionRequest;
import com.example.configmgr.ai.session.AiSession;
import com.example.configmgr.ai.session.AiSessionStore;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Core agent loop. Runs on a virtual thread per invocation.
 * Implements user-controlled tool execution (internalToolExecutionEnabled=false).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRuntime {

    private static final String SYSTEM_PROMPT = """
            你是一个专业的配置管理 AI 助手，帮助用户完成配置数据的导出、导入和管理工作。
            
            你的能力：
            - 帮助用户创建和管理快速实施任务（导出/导入配置）
            - 查询配置定义和字段信息
            - 设置查询条件、选择配置项
            - 启动导出、预检查、导入、发布等作业并监控进度
            - 解释检查结果、解答业务疑问
            
            工作原则：
            1. 始终使用中文回复
            2. 操作前先确认用户意图
            3. 高风险操作（发布配置）必须明确告知影响范围
            4. 工具调用失败时给出清晰的错误说明和建议
            5. 主动提示用户下一步可以做什么
            """;

    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;
    private final ContextBuilder contextBuilder;
    private final HitlManager hitlManager;
    private final AiSessionStore sessionStore;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    /**
     * Run the agent loop for a user message. Emits SSE events to the emitter.
     */
    public void run(String sessionId, String runId, String userMessage, SseRunEmitter emitter) {
        AiSession session = sessionStore.getOrThrow(sessionId);
        if (!session.tryStartRun()) {
            emitter.error("已有运行中的对话，请等待完成或取消后重试");
            return;
        }
        session.getCurrentRunThread().set(Thread.currentThread());
        // 工具在同一虚拟线程执行，可经 ThreadLocal 拿到发射器推送 UI 指令
        AgentRunContext.set(emitter);

        try {
            emitter.runStarted(runId);

            // Build message history
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(SYSTEM_PROMPT));

            // Context snapshot
            String ctxMsg = contextBuilder.buildContextMessage(session);
            messages.add(new SystemMessage(ctxMsg));

            // History (trimmed)
            List<Message> history = trimHistory(session.getHistory());
            messages.addAll(history);

            // User message
            messages.add(new UserMessage(userMessage));

            int maxIter = appProperties.getAi().getMaxIterations();
            for (int iter = 0; iter < maxIter; iter++) {
                if (Thread.currentThread().isInterrupted()) {
                    emitter.error("运行已被取消");
                    return;
                }

                // Get tools for current context
                List<ToolCallback> tools = toolRegistry.forContext(session.getContext());
                log.debug("Iter {} with {} tools", iter, tools.size());

                // Build options with thinking disabled
                OpenAiChatOptions opts = buildOptions(tools);

                // Call the model
                Prompt prompt = new Prompt(messages, opts);
                ChatResponse response;
                try {
                    response = callModelWithStreaming(prompt, emitter);
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) {
                        emitter.error("运行已被取消");
                        return;
                    }
                    log.error("Model call failed: {}", e.getMessage(), e);
                    emitter.error("AI 服务调用失败: " + e.getMessage() + "，请稍后重试");
                    return;
                }

                if (response == null) break;

                // Extract tool calls
                var generation = response.getResult();
                if (generation == null) break;
                AssistantMessage assistantMsg = generation.getOutput();
                List<AssistantMessage.ToolCall> toolCalls = assistantMsg.getToolCalls();

                // Add assistant message to history
                messages.add(assistantMsg);

                if (toolCalls == null || toolCalls.isEmpty()) {
                    // Done — no more tool calls
                    break;
                }

                // Execute each tool call
                List<Message> toolResults = new ArrayList<>();
                for (AssistantMessage.ToolCall tc : toolCalls) {
                    String toolName = tc.name();
                    String callId = tc.id();
                    String argsJson = tc.arguments();

                    emitter.toolStart(callId, toolName, getDisplayName(toolName));

                    // Check risk level — DANGER requires HITL
                    ToolMeta meta = toolRegistry.getMeta(toolName);
                    if (meta != null && meta.getRiskLevel() == ToolMeta.RiskLevel.DANGER) {
                        // 关键顺序：必须先创建请求并推送给前端（前端据此渲染确认卡片），
                        // 然后再阻塞等待响应。否则用户永远看不到卡片，只能超时。
                        InteractionRequest request = hitlManager.createInteraction(
                                sessionId, runId, callId,
                                InteractionRequest.InteractionType.CONFIRM,
                                "即将执行高风险操作: " + getDisplayName(toolName),
                                "工具: " + toolName + "\n参数: " + argsJson,
                                Map.of("toolName", toolName, "args", argsJson));
                        emitter.interactionRequest(request);

                        InteractionRequest.InteractionResult hitlResult =
                                hitlManager.awaitResponse(sessionId, request);

                        if (!hitlResult.isApproved()) {
                            String resultStr = "用户拒绝了此操作: "
                                    + (hitlResult.getRejectionReason() != null
                                            ? hitlResult.getRejectionReason() : "未提供原因");
                            toolResults.add(ToolResponseMessage.builder()
                                    .responses(List.of(new ToolResponseMessage.ToolResponse(callId, toolName, resultStr)))
                                    .build());
                            emitter.toolDone(callId, false, "操作已拒绝");
                            continue;
                        }
                        emitter.toolDone(callId, true, "用户已批准");
                    }

                    // Execute the tool
                    String result;
                    try {
                        ToolCallback callback = toolRegistry.getCallback(toolName);
                        if (callback == null) {
                            result = "工具 '" + toolName + "' 不可用（当前步骤不支持此操作）";
                        } else {
                            result = callback.call(argsJson,
                                    new ToolContext(Map.of(
                                            "sessionId", sessionId,
                                            "runId", runId)));
                            if (result == null) result = "操作完成";
                        }
                    } catch (Exception e) {
                        log.warn("Tool {} failed: {}", toolName, e.getMessage());
                        result = "工具执行出错: " + e.getMessage();
                    }

                    toolResults.add(ToolResponseMessage.builder()
                            .responses(List.of(new ToolResponseMessage.ToolResponse(callId, toolName, result)))
                            .build());
                    emitter.toolDone(callId, !result.startsWith("工具执行出错"), summarize(result));
                }

                messages.addAll(toolResults);
            }

            // Save history (trim if needed)
            session.getHistory().clear();
            session.getHistory().addAll(messages.subList(2, messages.size())); // skip system messages
            emitter.runCompleted(runId);

        } catch (Exception e) {
            log.error("Agent run failed: {}", e.getMessage(), e);
            emitter.error("对话出现未预期错误: " + e.getMessage());
        } finally {
            session.endRun();
            AgentRunContext.clear();
        }
    }

    private ChatResponse callModelWithStreaming(Prompt prompt, SseRunEmitter emitter) {
        // Use blocking call — on virtual thread this is fine
        // Collect text via the response; streaming text to frontend via emitter
        ChatResponse response = chatModel.call(prompt);
        // Send text delta
        if (response != null && response.getResult() != null) {
            String text = response.getResult().getOutput().getText();
            if (text != null && !text.isBlank()) {
                emitter.textDelta(text);
            }
        }
        return response;
    }

    private OpenAiChatOptions buildOptions(List<ToolCallback> tools) {
        var builder = OpenAiChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .temperature(0.2)
                .maxTokens(4096);

        // Disable DeepSeek thinking via extraBody
        if (appProperties.getAi().isThinkingDisabled()) {
            builder.extraBody(Map.of("thinking", Map.of("type", "disabled")));
        }

        if (!tools.isEmpty()) {
            // Tools are passed as callbacks
            builder.toolCallbacks(tools.toArray(new ToolCallback[0]));
        }

        return builder.build();
    }

    private List<Message> trimHistory(List<Message> history) {
        if (history.size() <= 40) return new ArrayList<>(history);
        // Keep last 40 messages, ensuring tool_call/response pairs stay intact
        List<Message> trimmed = new ArrayList<>();
        int start = Math.max(0, history.size() - 40);
        // Skip forward if start lands in the middle of an assistant+tool pair
        while (start < history.size() && history.get(start) instanceof ToolResponseMessage) start++;
        trimmed.addAll(history.subList(start, history.size()));
        return trimmed;
    }

    private String getDisplayName(String toolName) {
        ToolMeta meta = toolRegistry.getMeta(toolName);
        return meta != null ? meta.getDisplayName() : toolName;
    }

    private String summarize(String result) {
        if (result == null) return "";
        return result.length() > 80 ? result.substring(0, 80) + "…" : result;
    }
}
