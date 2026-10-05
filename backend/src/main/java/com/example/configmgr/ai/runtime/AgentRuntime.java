package com.example.configmgr.ai.runtime;

import com.example.configmgr.ai.hitl.HitlManager;
import com.example.configmgr.ai.hitl.InteractionRequest;
import com.example.configmgr.ai.session.AiSession;
import com.example.configmgr.ai.session.AiSessionStore;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core agent loop. Runs on a virtual thread per invocation.
 * <p>
 * 复用 Spring AI 能力（约束：能用 Spring AI 就不自研）：
 * - 会话记忆：{@link org.springframework.ai.chat.memory.ChatMemory}（MessageWindowChatMemory 窗口裁剪）
 * - 流式汇总：{@link MessageAggregator}（聚合文本/工具调用/usage）
 * - 工具执行：{@link ToolCallingManager}（DefaultToolCallingManager + StaticToolCallbackResolver，
 *   异常转错误响应、对话历史组装均由 Spring AI 完成）
 * 自研部分仅为 Spring AI 未提供的编排：迭代循环、HITL 人机确认、SSE 事件推送。
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

            // Build message history（记忆来自 Spring AI ChatMemory，窗口已裁剪）
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(SYSTEM_PROMPT));
            messages.add(new SystemMessage(contextBuilder.buildContextMessage(session)));
            messages.addAll(session.getMemory().get(sessionId));
            messages.add(new UserMessage(userMessage));

            int maxIter = appProperties.getAi().getMaxIterations();
            for (int iter = 0; iter < maxIter; iter++) {
                if (Thread.currentThread().isInterrupted()) {
                    emitter.error("运行已被取消");
                    return;
                }

                List<ToolCallback> tools = toolRegistry.forContext(session.getContext());
                log.debug("Iter {} with {} tools", iter, tools.size());

                Prompt prompt = new Prompt(messages, buildOptions(tools));

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

                // 工具执行交给 Spring AI ToolCallingManager（解析/执行/异常转错误响应）
                ToolCallingManager toolCallingManager = DefaultToolCallingManager.builder()
                        .toolCallbackResolver(new StaticToolCallbackResolver(tools))
                        .build();

                for (AssistantMessage.ToolCall tc : toolCalls) {
                    String toolName = tc.name();
                    String callId = tc.id();

                    emitter.toolStart(callId, toolName, getDisplayName(toolName));

                    // 高风险工具：HITL 人机确认（Spring AI 无此能力，属编排层）
                    ToolMeta meta = toolRegistry.getMeta(toolName);
                    if (meta != null && meta.getRiskLevel() == ToolMeta.RiskLevel.DANGER) {
                        InteractionRequest request = hitlManager.createInteraction(
                                sessionId, runId, callId,
                                InteractionRequest.InteractionType.CONFIRM,
                                "即将执行高风险操作: " + getDisplayName(toolName),
                                "工具: " + toolName + "\n参数: " + tc.arguments(),
                                Map.of("toolName", toolName, "args", tc.arguments()));
                        emitter.interactionRequest(request);

                        InteractionRequest.InteractionResult hitlResult =
                                hitlManager.awaitResponse(sessionId, request);

                        if (!hitlResult.isApproved()) {
                            String resultStr = "用户拒绝了此操作: "
                                    + (hitlResult.getRejectionReason() != null
                                            ? hitlResult.getRejectionReason() : "未提供原因");
                            messages.add(ToolResponseMessage.builder()
                                    .responses(List.of(new ToolResponseMessage.ToolResponse(callId, toolName, resultStr)))
                                    .build());
                            emitter.toolDone(callId, false, "操作已拒绝");
                            continue;
                        }
                        emitter.toolDone(callId, true, "用户已批准");
                    }

                    // 单次调用包装为 ChatResponse 交给 ToolCallingManager 执行
                    AssistantMessage singleCall = AssistantMessage.builder()
                            .content("")
                            .toolCalls(List.of(tc))
                            .build();
                    ChatResponse callResponse = new ChatResponse(List.of(new Generation(singleCall)));
                    ToolExecutionResult execResult = toolCallingManager.executeToolCalls(prompt, callResponse);

                    // 只取其中的工具响应消息（assistant 消息已在上方整体添加）
                    for (Message m : execResult.conversationHistory()) {
                        if (m instanceof ToolResponseMessage trm) {
                            messages.add(trm);
                            for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                                emitter.toolDone(r.id(),
                                        !r.responseData().startsWith("工具执行出错"),
                                        summarize(r.responseData()));
                            }
                        }
                    }
                    if (execResult.returnDirect()) {
                        break;
                    }
                }
            }

            // 保存记忆（Spring AI ChatMemory；窗口裁剪由 MessageWindowChatMemory 完成）
            session.getMemory().clear(sessionId);
            session.getMemory().add(sessionId, messages.subList(2, messages.size())); // skip system messages
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
     * 真流式调用：逐块把文本推给前端；结束时用 Spring AI MessageAggregator
     * 汇总本轮完整 assistant 消息（文本 + 工具调用 + usage）。
     */
    private StreamResult streamModel(Prompt prompt, SseRunEmitter emitter) {
        MessageAggregator aggregator = new MessageAggregator();
        AtomicReference<ChatResponse> aggregatedRef = new AtomicReference<>();

        chatModel.stream(prompt)
                .doOnNext(cr -> {
                    if (cr.getResult() == null) return;
                    String text = cr.getResult().getOutput().getText();
                    if (text != null && !text.isBlank()) {
                        emitter.textDelta(text);
                    }
                })
                .transform(flux -> aggregator.aggregate(flux, aggregatedRef::set))
                .blockLast(java.time.Duration.ofMinutes(3));

        ChatResponse finalResponse = aggregatedRef.get();
        AssistantMessage assistantMessage = finalResponse != null && finalResponse.getResult() != null
                ? finalResponse.getResult().getOutput()
                : new AssistantMessage("");
        List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls() != null
                ? assistantMessage.getToolCalls() : List.of();
        Usage usage = finalResponse != null && finalResponse.getMetadata() != null
                ? finalResponse.getMetadata().getUsage() : null;

        return new StreamResult(assistantMessage, toolCalls, usage);
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
            builder.toolCallbacks(tools.toArray(new ToolCallback[0]));
        }

        return builder.build();
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
