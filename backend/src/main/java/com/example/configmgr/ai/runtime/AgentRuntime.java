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
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

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
            2. 用户明确要求执行某个操作时，直接调用对应工具完成任务，
               不要只在对话里描述步骤
            3. 高风险操作（如发布配置）由系统内置的确认卡片负责征得用户同意：
               调用工具即可，系统会自动弹出确认请求，无需你在对话里再询问"是否确认"
            4. 工具调用失败时给出清晰的错误说明和建议
            5. 主动提示用户下一步可以做什么
            """;

    private final OpenAiChatModel chatModel;
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

            long totalPromptTokens = 0;
            long totalCompletionTokens = 0;

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

                // Call the model（真流式：逐块推送文本，末尾汇总工具调用）
                Prompt prompt = new Prompt(messages, opts);
                StreamResult streamResult;
                try {
                    streamResult = streamModel(prompt, emitter);
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) {
                        emitter.error("运行已被取消");
                        return;
                    }
                    log.error("Model call failed: {}", e.getMessage(), e);
                    emitter.error("AI 服务调用失败: " + e.getMessage() + "，请稍后重试");
                    return;
                }

                // 组装本轮 assistant 消息（文本 + 可能存在的工具调用）
                AssistantMessage assistantMsg = streamResult.assistantMessage();
                messages.add(assistantMsg);

                if (streamResult.usage() != null) {
                    totalPromptTokens += streamResult.usage().getPromptTokens();
                    totalCompletionTokens += streamResult.usage().getCompletionTokens();
                }

                List<AssistantMessage.ToolCall> toolCalls = streamResult.toolCalls();
                if (toolCalls.isEmpty()) {
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
            Map<String, Object> usage = totalPromptTokens > 0 || totalCompletionTokens > 0
                    ? Map.of("promptTokens", totalPromptTokens, "completionTokens", totalCompletionTokens)
                    : Map.of();
            session.setLastPromptTokens(totalPromptTokens);
            session.setLastCompletionTokens(totalCompletionTokens);
            emitter.runCompleted(runId, usage);

        } catch (Exception e) {
            log.error("Agent run failed: {}", e.getMessage(), e);
            emitter.error("对话出现未预期错误: " + e.getMessage());
        } finally {
            session.endRun();
            AgentRunContext.clear();
        }
    }

    /**
     * 真流式调用：逐块把文本推给前端；结束时把本轮的文本与工具调用汇总返回。
     */
    private StreamResult streamModel(Prompt prompt, SseRunEmitter emitter) {
        StringBuilder acc = new StringBuilder();
        List<AssistantMessage.ToolCall> toolCalls =
                java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Usage> usageRef = new AtomicReference<>();

        chatModel.stream(prompt)
                .doOnNext(cr -> {
                    if (cr.getResult() == null) {
                        return;
                    }
                    String text = cr.getResult().getOutput().getText();
                    if (text != null && !text.isBlank()) {
                        acc.append(text);
                        emitter.textDelta(text);
                    }
                    List<AssistantMessage.ToolCall> calls = cr.getResult().getOutput().getToolCalls();
                    if (calls != null && !calls.isEmpty()) {
                        toolCalls.addAll(calls);
                    }
                    if (cr.getMetadata() != null && cr.getMetadata().getUsage() != null) {
                        usageRef.set(cr.getMetadata().getUsage());
                    }
                })
                .blockLast(java.time.Duration.ofMinutes(3));

        String fullText = acc.toString();
        List<AssistantMessage.ToolCall> calls = new ArrayList<>(toolCalls);
        AssistantMessage assistantMessage = calls.isEmpty()
                ? new AssistantMessage(fullText)
                : AssistantMessage.builder().content(fullText).toolCalls(calls).build();

        return new StreamResult(assistantMessage, calls, usageRef.get());
    }

    private record StreamResult(AssistantMessage assistantMessage,
                                List<AssistantMessage.ToolCall> toolCalls,
                                Usage usage) {
    }

    private OpenAiChatOptions buildOptions(List<ToolCallback> tools) {
        var builder = OpenAiChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .temperature(0.2)
                .maxTokens(4096)
                // 让 DeepSeek 在流式最后一块返回 token 用量
                .streamUsage(true);

        // Disable DeepSeek thinking via extraBody。
        // 实测：thinking=disabled + stream=true 时模型不发起工具调用，
        // 必须同时设置 reasoning_effort=low（DeepSeek API 行为），流式工具调用才恢复。
        if (appProperties.getAi().isThinkingDisabled()) {
            builder.extraBody(Map.of("thinking", Map.of("type", "disabled")));
            builder.reasoningEffort("low");
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
