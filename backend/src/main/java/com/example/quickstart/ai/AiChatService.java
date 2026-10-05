package com.example.quickstart.ai;

import com.example.quickstart.ai.AiSessionStore.AiSession;
import com.example.quickstart.ai.AiSessionStore.PendingCall;
import com.example.quickstart.dto.AiContext;
import com.example.quickstart.dto.ChatRequest;
import com.example.quickstart.dto.ToolResultRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionChunk;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionFinishReason;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest;
import org.springframework.ai.openai.api.OpenAiApi.FunctionTool;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * AI 对话服务（D1/D2/D9）：注入自动配置的 OpenAiApi Bean，自研薄 Agent Loop：
 * 流式消费 + 工具分发 + FRONTEND 工具暂停-恢复；SSE 通过 SseEmitter + 异步线程下发。
 * <p>
 * DeepSeek 兼容性：回填 assistant 消息 reasoningContent 恒为 null；parallelToolCalls=false；
 * 每轮只顺序处理第一个 toolCall；工具结果截断 4000 字符；429/5xx 指数退避 ≤3 次。
 */
@Slf4j
@Service
public class AiChatService {

    private static final int MAX_TOOL_RESULT_CHARS = 4000;
    private static final int MAX_HISTORY_MESSAGES = 50;
    private static final int MAX_RETRIES = 3;

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final OpenAiApi openAiApi;
    private final AiToolRegistry toolRegistry;
    private final BackendToolExecutor backendToolExecutor;
    private final PromptBuilder promptBuilder;
    private final AiSessionStore sessionStore;
    private final Executor aiExecutor;
    private final ObjectMapper om;
    private final int maxRounds;
    private final String model;

    public AiChatService(OpenAiApi openAiApi,
                         AiToolRegistry toolRegistry,
                         BackendToolExecutor backendToolExecutor,
                         PromptBuilder promptBuilder,
                         AiSessionStore sessionStore,
                         @Qualifier("aiTaskExecutor") Executor aiExecutor,
                         ObjectMapper om,
                         @Value("${app.ai.max-rounds:8}") int maxRounds,
                         @Value("${spring.ai.openai.chat.options.model:deepseek-flash}") String model) {
        this.openAiApi = openAiApi;
        this.toolRegistry = toolRegistry;
        this.backendToolExecutor = backendToolExecutor;
        this.promptBuilder = promptBuilder;
        this.sessionStore = sessionStore;
        this.aiExecutor = aiExecutor;
        this.om = om;
        this.maxRounds = maxRounds;
        this.model = model;
    }

    // ================================ 对外入口 ================================

    public void chat(ChatRequest req, SseEmitter emitter) {
        AiSession session = sessionStore.getOrCreate(req.sessionId());
        synchronized (session) {
            session.lastAccess = Instant.now();
            if (session.pendingCall != null) {
                sendError(emitter, "存在未完成的前端工具调用（" + session.pendingCall.name()
                        + "），请先完成该操作或刷新页面后再发起新对话");
                return;
            }
            if (session.running) {
                sendError(emitter, "上一个请求正在处理中，请稍候");
                return;
            }
            session.running = true;
            session.cancelRequested = false;
            session.messages.add(new ChatCompletionMessage(req.message(), ChatCompletionMessage.Role.USER));
            session.displayMessages.add(AiSessionStore.displayMsg("user", req.message(), null));
            trimDisplay(session);
        }
        aiExecutor.execute(() -> runLoop(session, req.context(), emitter));
    }

    public void toolResult(ToolResultRequest req, SseEmitter emitter) {
        AiSession session = sessionStore.find(req.sessionId());
        if (session == null) {
            sendError(emitter, "会话不存在或已过期，请重新发起对话");
            return;
        }
        synchronized (session) {
            session.lastAccess = Instant.now();
            if (session.running) {
                sendError(emitter, "上一个请求正在处理中，请稍候");
                return;
            }
            PendingCall pending = session.pendingCall;
            if (pending == null) {
                sendError(emitter, "当前没有等待执行结果的工具调用");
                return;
            }
            if (!pending.callId().equals(req.callId())) {
                sendError(emitter, "callId 与待执行的工具调用不匹配: " + req.callId());
                return;
            }
            session.pendingCall = null;
            session.running = true;
            session.cancelRequested = false;
            // 回填 tool 角色结果消息（截断 4000 字符）
            String resultJson = truncate(writeJson(
                    req.result() == null ? Map.of("success", false, "error", "无结果") : req.result()));
            session.messages.add(new ChatCompletionMessage(resultJson, ChatCompletionMessage.Role.TOOL,
                    pending.name(), pending.callId(), null, null, null, null, null));
        }
        aiExecutor.execute(() -> runLoop(session, req.context(), emitter));
    }

    /** 协作式取消：置标志，Loop 在轮次边界生效 */
    public void cancel(String sessionId) {
        AiSession session = sessionStore.find(sessionId);
        if (session != null) {
            session.cancelRequested = true;
            log.info("AI 会话取消请求: sessionId={}", sessionId);
        }
    }

    /** 刷新恢复：展示用消息 + 未完成的前端工具调用 */
    public Map<String, Object> history(String sessionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        AiSession session = sessionStore.find(sessionId);
        if (session == null) {
            result.put("messages", List.of());
            result.put("pendingCall", null);
            return result;
        }
        synchronized (session) {
            session.lastAccess = Instant.now();
            result.put("messages", new ArrayList<>(session.displayMessages));
            PendingCall pending = session.pendingCall;
            if (pending == null) {
                result.put("pendingCall", null);
            } else {
                Map<String, Object> pc = new LinkedHashMap<>();
                pc.put("callId", pending.callId());
                pc.put("name", pending.name());
                pc.put("arguments", parseArgs(pending.argumentsJson()));
                pc.put("needConfirm", pending.needConfirm());
                result.put("pendingCall", pc);
            }
        }
        return result;
    }

    // ================================ Agent Loop ================================

    private void runLoop(AiSession session, AiContext context, SseEmitter emitter) {
        try {
            String page = context == null ? null : context.page();
            Integer step = context == null ? null : context.step();
            List<AiToolRegistry.AiTool> available = toolRegistry.available(page, step);
            Set<String> availableNames = new HashSet<>();
            for (AiToolRegistry.AiTool t : available) {
                availableNames.add(t.name());
            }
            List<FunctionTool> functionTools = toolRegistry.functionTools(page, step);
            String systemPrompt = promptBuilder.build(context, available);

            for (int round = 1; round <= maxRounds; round++) {
                // 轮次边界检查取消标志
                if (session.cancelRequested) {
                    session.cancelRequested = false;
                    send(emitter, "done", Map.of("status", "cancelled"));
                    return;
                }

                List<ChatCompletionMessage> requestMessages = new ArrayList<>();
                requestMessages.add(new ChatCompletionMessage(systemPrompt, ChatCompletionMessage.Role.SYSTEM));
                requestMessages.addAll(trimmedHistory(session));

                RoundOutcome outcome = callRound(requestMessages, functionTools, emitter);
                if (outcome == null) {
                    return; // 错误事件已发送或前端已断开
                }

                // 本轮无工具调用 → 结束
                if (outcome.toolCalls().isEmpty()) {
                    synchronized (session) {
                        session.messages.add(new ChatCompletionMessage(
                                outcome.content(), ChatCompletionMessage.Role.ASSISTANT));
                        if (!outcome.content().isBlank()) {
                            session.displayMessages.add(
                                    AiSessionStore.displayMsg("assistant", outcome.content(), null));
                            trimDisplay(session);
                        }
                    }
                    send(emitter, "done", Map.of("status", "finished"));
                    return;
                }

                // 每轮只顺序处理第一个 toolCall，其余丢弃并记录
                ChatCompletionMessage.ToolCall tc = outcome.toolCalls().get(0);
                if (outcome.toolCalls().size() > 1) {
                    log.warn("模型返回 {} 个 toolCalls，仅顺序处理第一个 {}，其余 {} 个丢弃",
                            outcome.toolCalls().size(), tc.function().name(), outcome.toolCalls().size() - 1);
                }
                String callId = (tc.id() == null || tc.id().isBlank())
                        ? "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24)
                        : tc.id();
                String fnName = tc.function().name();
                String fnArgs = (tc.function().arguments() == null || tc.function().arguments().isBlank())
                        ? "{}" : tc.function().arguments();

                // 回填 assistant(tool_calls) 消息：reasoningContent 必须为 null（否则 DeepSeek 400）
                var function = new ChatCompletionMessage.ChatCompletionFunction(fnName, fnArgs);
                var toolCall = new ChatCompletionMessage.ToolCall(callId, "function", function);
                var assistantToolCallMsg = new ChatCompletionMessage(null, ChatCompletionMessage.Role.ASSISTANT,
                        null, null, List.of(toolCall), null, null, null, null);
                synchronized (session) {
                    session.messages.add(assistantToolCallMsg);
                }

                AiToolRegistry.AiTool toolDef = availableNames.contains(fnName) ? toolRegistry.get(fnName) : null;
                if (toolDef == null) {
                    // 幻觉/越权工具：不回灌给前端，直接回填错误结果让模型自我纠正
                    log.warn("模型调用了当前页面不可用或不存在的工具: {}", fnName);
                    send(emitter, "tool_run", Map.of("name", fnName, "status", "error"));
                    synchronized (session) {
                        session.messages.add(new ChatCompletionMessage(
                                writeJson(Map.of("success", false, "error",
                                        "工具不存在或当前页面/步骤不可用: " + fnName)),
                                ChatCompletionMessage.Role.TOOL, fnName, callId, null, null, null, null, null));
                    }
                    continue;
                }

                if (toolDef.kind() == AiToolRegistry.Kind.BACKEND) {
                    // 后端工具：Loop 内直接执行，结果回填，继续下一轮
                    Object result = backendToolExecutor.execute(fnName, parseArgs(fnArgs));
                    boolean ok = !(result instanceof Map<?, ?> m && m.containsKey("error"));
                    send(emitter, "tool_run", Map.of("name", fnName, "status", ok ? "ok" : "error"));
                    synchronized (session) {
                        session.messages.add(new ChatCompletionMessage(truncate(writeJson(result)),
                                ChatCompletionMessage.Role.TOOL, fnName, callId, null, null, null, null, null));
                        session.displayMessages.add(AiSessionStore.displayMsg("assistant", "", fnName));
                        trimDisplay(session);
                    }
                    continue;
                }

                // FRONTEND 工具：保存暂停现场 → SSE tool_call → done(waiting) 结束流
                PendingCall pending = new PendingCall(callId, fnName, fnArgs, toolDef.needConfirm());
                synchronized (session) {
                    session.pendingCall = pending;
                    session.displayMessages.add(AiSessionStore.displayMsg("assistant", "", fnName));
                    trimDisplay(session);
                }
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("callId", callId);
                event.put("name", fnName);
                event.put("arguments", parseArgs(fnArgs));
                event.put("needConfirm", toolDef.needConfirm());
                send(emitter, "tool_call", event);
                send(emitter, "done", Map.of("status", "waiting"));
                return;
            }
            log.warn("Agent Loop 达到最大轮次 {}，强制结束", maxRounds);
            send(emitter, "done", Map.of("status", "max_rounds"));
        } catch (Exception e) {
            log.error("Agent Loop 异常", e);
            send(emitter, "error", Map.of("message", "AI 处理异常: " + abbrev(e.getMessage(), 300)));
        } finally {
            synchronized (session) {
                session.running = false;
                session.lastAccess = Instant.now();
            }
            emitter.complete();
        }
    }

    private record RoundOutcome(String content, List<ChatCompletionMessage.ToolCall> toolCalls) {
    }

    /**
     * 单轮流式调用（含 429/5xx/网络异常指数退避重试 ≤3 次）。
     * 返回 null 表示失败（错误事件已发送）或前端已断开。
     */
    private RoundOutcome callRound(List<ChatCompletionMessage> messages,
                                   List<FunctionTool> tools, SseEmitter emitter) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            StringBuilder content = new StringBuilder();
            List<ChatCompletionMessage.ToolCall> toolCalls = new ArrayList<>();
            boolean emitted = false;
            try {
                ChatCompletionRequest request = buildRequest(messages, tools);
                Flux<ChatCompletionChunk> flux = openAiApi.chatCompletionStream(request);
                for (ChatCompletionChunk chunk : flux.toIterable()) {
                    // include_usage 末帧：choices 为空、usage 非空，需判空跳过
                    if (chunk.choices() == null || chunk.choices().isEmpty()) {
                        continue;
                    }
                    var choice = chunk.choices().get(0);
                    var delta = choice.delta();
                    if (delta == null) {
                        continue;
                    }
                    String text = delta.content();
                    if (text != null && !text.isEmpty()) {
                        content.append(text);
                        if (!send(emitter, "token", Map.of("text", text))) {
                            return null; // 前端已断开
                        }
                        emitted = true;
                    }
                    String reasoning = delta.reasoningContent();
                    if (reasoning != null && !reasoning.isEmpty()) {
                        send(emitter, "reasoning", Map.of("text", reasoning));
                        emitted = true;
                    }
                    // 框架已合并流式 tool_calls 分片：finish=tool_calls 时 arguments 完整
                    if (choice.finishReason() == ChatCompletionFinishReason.TOOL_CALLS
                            && delta.toolCalls() != null) {
                        toolCalls.addAll(delta.toolCalls());
                    }
                }
                return new RoundOutcome(content.toString(), toolCalls);
            } catch (Exception e) {
                Throwable cause = Exceptions.unwrap(e);
                if (emitted || attempt >= MAX_RETRIES || !isRetryable(cause)) {
                    log.error("DeepSeek 调用失败（不再重试）: {}", cause.getMessage(), cause);
                    send(emitter, "error", Map.of("message",
                            "AI 服务调用失败: " + abbrev(cause.getMessage(), 300)));
                    return null;
                }
                long backoffMs = (long) (1000L * (1L << attempt) * (0.8 + Math.random() * 0.4));
                log.warn("DeepSeek 调用失败（可重试），第 {}/{} 次重试，退避 {}ms: {}",
                        attempt + 1, MAX_RETRIES, backoffMs, cause.getMessage());
                sleep(backoffMs);
            }
        }
        return null;
    }

    /** 429/5xx（TransientAiException 或错误信息含 429）/网络异常可重试 */
    private boolean isRetryable(Throwable cause) {
        if (cause instanceof TransientAiException) {
            return true;
        }
        if (cause instanceof NonTransientAiException) {
            String msg = cause.getMessage();
            return msg != null && msg.contains("429");
        }
        // 网络层异常（连接/读超时等）
        return cause instanceof IOException
                || (cause.getMessage() != null && cause.getMessage().contains("Connection"));
    }

    // ================================ OpenAiApi 请求构造（速查 A.4） ================================

    private ChatCompletionRequest buildRequest(List<ChatCompletionMessage> messages,
                                               List<FunctionTool> tools) {
        return new ChatCompletionRequest(
                messages,                                                       // 1  messages
                model,                                                          // 2  model
                null, null, null, null, null, null,                             // 3-8 store, metadata, frequencyPenalty, logitBias, logprobs, topLogprobs
                null, null,                                                     // 9-10 maxTokens, maxCompletionTokens（互斥，DeepSeek 默认即可）
                null,                                                           // 11 n
                null, null,                                                     // 12-13 modalities, audio
                null,                                                           // 14 presencePenalty
                null,                                                           // 15 responseFormat
                null,                                                           // 16 seed
                null,                                                           // 17 serviceTier
                null,                                                           // 18 stop
                true,                                                           // 19 stream（chatCompletionStream 断言必须为 true）
                ChatCompletionRequest.StreamOptions.INCLUDE_USAGE,              // 20 streamOptions
                null,                                                           // 21 temperature（走模型默认）
                null,                                                           // 22 topP
                tools,                                                          // 23 tools
                ChatCompletionRequest.ToolChoiceBuilder.AUTO,                   // 24 toolChoice
                false,                                                          // 25 parallelToolCalls（1.1.8 合并器单帧只支持单调用）
                null, null, null, null, null, null, null);                      // 26-32 user, reasoningEffort, webSearchOptions, verbosity, promptCacheKey, safetyIdentifier, extraBody
    }

    // ================================ 辅助 ================================

    /** 历史截断：最多保留最近 MAX_HISTORY_MESSAGES 条；丢弃开头孤立的 tool 消息避免协议错误 */
    private List<ChatCompletionMessage> trimmedHistory(AiSession session) {
        List<ChatCompletionMessage> copy;
        synchronized (session) {
            copy = new ArrayList<>(session.messages);
        }
        if (copy.size() > MAX_HISTORY_MESSAGES) {
            copy = new ArrayList<>(copy.subList(copy.size() - MAX_HISTORY_MESSAGES, copy.size()));
        }
        while (!copy.isEmpty() && copy.get(0).role() == ChatCompletionMessage.Role.TOOL) {
            copy.remove(0);
        }
        return copy;
    }

    private void trimDisplay(AiSession session) {
        while (session.displayMessages.size() > 200) {
            session.displayMessages.remove(0);
        }
    }

    private boolean send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(om.writeValueAsString(data)));
            return true;
        } catch (Exception e) {
            log.debug("SSE 发送失败（前端可能已断开）: {}", e.getMessage());
            return false;
        }
    }

    private void sendError(SseEmitter emitter, String message) {
        send(emitter, "error", Map.of("message", message));
        emitter.complete();
    }

    private Map<String, Object> parseArgs(String argumentsJson) {
        try {
            return om.readValue(argumentsJson, MAP_TYPE);
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("_raw", argumentsJson);
            return fallback;
        }
    }

    private String writeJson(Object value) {
        try {
            return om.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"结果序列化失败\"}";
        }
    }

    /** 工具结果截断 4000 字符，防止打爆模型上下文 */
    private String truncate(String s) {
        if (s == null) {
            return "null";
        }
        if (s.length() <= MAX_TOOL_RESULT_CHARS) {
            return s;
        }
        return s.substring(0, MAX_TOOL_RESULT_CHARS)
                + "...(结果过长已截断，共 " + s.length() + " 字符，如需完整数据请换更精确的查询条件)";
    }

    private String abbrev(String s, int max) {
        if (s == null) {
            return "未知错误";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...(截断)";
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
