package com.example.quickstart.ai;

import com.example.quickstart.ai.AiSessionStore.AiSession;
import com.example.quickstart.ai.AiSessionStore.PendingCall;
import com.example.quickstart.dto.AiContext;
import com.example.quickstart.dto.ChatRequest;
import com.example.quickstart.dto.ToolResultRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
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
import java.util.stream.Collectors;

/**
 * AI 对话服务（D1/D2/D9）：基于 Spring AI OpenAI 模块（OpenAI 兼容协议指向 DeepSeek）
 * 的高层 API（OpenAiChatModel.stream + ChatMemory + ToolCallback），自研薄 Agent Loop：
 * 流式消费 + 工具分发 + FRONTEND 工具暂停-恢复；SSE 通过 SseEmitter + 异步线程下发。
 * <p>
 * DeepSeek 兼容性：deepseek-flash 为思考模式，回填 assistant 消息必须携带本轮 reasoning_content
 * （流式 chunk 的 reasoning 在 AssistantMessage.properties["reasoningContent"]，聚合后同键入库，
 * 由 ReasoningAwareOpenAiChatModel 补丁负责回放，缺失会 400）；parallelToolCalls=false（yml）；
 * 工具结果截断 4000 字符；429/5xx 整轮指数退避 ≤3 次（流式无官方重试，已产出内容不重试）。
 */
@Slf4j
@Service
public class AiChatService {

    private static final int MAX_TOOL_RESULT_CHARS = 4000;
    /** 回填给模型的 reasoning_content 上限（思考链可能很长，仅用于满足思考模式回放要求） */
    private static final int MAX_REASONING_REPLAY = 8000;
    private static final int MAX_RETRIES = 3;
    /** 流式 chunk 与聚合 AssistantMessage 中思维链的 properties 键（OpenAiChatModel 约定） */
    private static final String REASONING_KEY = "reasoningContent";

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final OpenAiChatModel chatModel;
    private final ChatMemory chatMemory;
    private final AiToolRegistry toolRegistry;
    private final BackendToolExecutor backendToolExecutor;
    private final PromptBuilder promptBuilder;
    private final AiSessionStore sessionStore;
    private final Executor aiExecutor;
    private final ObjectMapper om;
    private final int maxRounds;
    private final String model;

    public AiChatService(OpenAiChatModel chatModel,
                         ChatMemory chatMemory,
                         AiToolRegistry toolRegistry,
                         BackendToolExecutor backendToolExecutor,
                         PromptBuilder promptBuilder,
                         AiSessionStore sessionStore,
                         @Qualifier("aiTaskExecutor") Executor aiExecutor,
                         ObjectMapper om,
                         @Value("${app.ai.max-rounds:8}") int maxRounds,
                         @Value("${spring.ai.openai.chat.options.model:deepseek-flash}") String model) {
        this.chatModel = chatModel;
        this.chatMemory = chatMemory;
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
            chatMemory.add(req.sessionId(), List.of(new UserMessage(req.message())));
            session.displayMessages.add(AiSessionStore.displayMsg("user", req.message(), null));
            trimDisplay(session);
        }
        aiExecutor.execute(() -> runLoop(req.sessionId(), session, req.context(), emitter));
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
            // 回填 tool 结果（截断 4000 字符）：按暂停轮 toolCalls 原始顺序合成一条 ToolResponseMessage，
            // 已执行的 BACKEND 调用 + 本次应答的 FRONTEND 调用 + 未应答调用补错误 ToolResponse 防 400
            String resultJson = truncate(writeJson(
                    req.result() == null ? Map.of("success", false, "error", "无结果") : req.result()));
            Map<String, ToolResponseMessage.ToolResponse> byId = new LinkedHashMap<>();
            for (ToolResponseMessage.ToolResponse r : pending.completedResponses()) {
                byId.put(r.id(), r);
            }
            byId.put(pending.callId(),
                    new ToolResponseMessage.ToolResponse(pending.callId(), pending.name(), resultJson));
            List<ToolResponseMessage.ToolResponse> responses = pending.roundCalls().stream()
                    .map(tc -> byId.getOrDefault(tc.id(), new ToolResponseMessage.ToolResponse(tc.id(), tc.name(),
                            "{\"success\":false,\"error\":\"该工具调用未被执行（同一轮仅恢复一个前端工具调用）\"}")))
                    .toList();
            chatMemory.add(req.sessionId(),
                    List.of(ToolResponseMessage.builder().responses(responses).build()));
        }
        aiExecutor.execute(() -> runLoop(req.sessionId(), session, req.context(), emitter));
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

    private void runLoop(String sessionId, AiSession session, AiContext context, SseEmitter emitter) {
        try {
            String page = context == null ? null : context.page();
            Integer step = context == null ? null : context.step();
            List<AiToolRegistry.AiTool> available = toolRegistry.available(page, step);
            Set<String> availableNames = new HashSet<>();
            for (AiToolRegistry.AiTool t : available) {
                availableNames.add(t.name());
            }
            List<ToolCallback> toolCallbacks = toolRegistry.toolCallbacks(page, step);
            String systemPrompt = promptBuilder.build(context, available);

            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model(model)
                    .toolCallbacks(toolCallbacks)
                    .internalToolExecutionEnabled(false)
                    .build();

            for (int round = 1; round <= maxRounds; round++) {
                // 轮次边界检查取消标志
                if (session.cancelRequested) {
                    session.cancelRequested = false;
                    send(emitter, "done", Map.of("status", "cancelled"));
                    return;
                }

                // system prompt 不入 memory（随 context 变化），每轮请求时临时 prepend
                List<Message> requestMessages = new ArrayList<>();
                requestMessages.add(new SystemMessage(systemPrompt));
                requestMessages.addAll(sanitizedHistory(sessionId));

                RoundOutcome outcome = callRound(requestMessages, options, emitter);
                if (outcome == null) {
                    return; // 错误事件已发送或前端已断开
                }

                // 本轮无工具调用 → 结束
                if (outcome.toolCalls().isEmpty()) {
                    chatMemory.add(sessionId, List.of(aggregateAssistantMessage(
                            outcome.content(), List.of(), outcome.reasoning())));
                    if (!outcome.content().isBlank()) {
                        synchronized (session) {
                            session.displayMessages.add(
                                    AiSessionStore.displayMsg("assistant", outcome.content(), null));
                            trimDisplay(session);
                        }
                    }
                    send(emitter, "done", Map.of("status", "finished"));
                    return;
                }

                // 规范化本轮 toolCalls（补 id / 空参数），assistant 回填与 ToolResponse 必须使用同一组 id
                List<AssistantMessage.ToolCall> calls = outcome.toolCalls().stream()
                        .map(this::normalizeCall)
                        .toList();

                // 回填 assistant(tool_calls)：携带本轮 reasoningContent（思考模式必需，由补丁类回放）
                chatMemory.add(sessionId, List.of(aggregateAssistantMessage(
                        outcome.content().isEmpty() ? null : outcome.content(), calls, outcome.reasoning())));

                // 分发：BACKEND 就地执行逐个收集 ToolResponse；只暂停第一个 FRONTEND 调用，
                // 其余 FRONTEND 调用本轮不应答（恢复时补错误 ToolResponse）
                List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                AssistantMessage.ToolCall frontendCall = null;
                AiToolRegistry.AiTool frontendDef = null;
                for (AssistantMessage.ToolCall tc : calls) {
                    String fnName = tc.name();
                    String fnArgs = tc.arguments();
                    AiToolRegistry.AiTool toolDef = availableNames.contains(fnName) ? toolRegistry.get(fnName) : null;
                    if (toolDef != null && toolDef.kind() == AiToolRegistry.Kind.FRONTEND) {
                        if (frontendCall == null) {
                            frontendCall = tc;
                            frontendDef = toolDef;
                        }
                        continue;
                    }
                    if (toolDef == null) {
                        // 幻觉/越权工具：不回灌给前端，直接回填错误结果让模型自我纠正
                        log.warn("模型调用了当前页面不可用或不存在的工具: {}", fnName);
                        send(emitter, "tool_run", Map.of("name", fnName, "status", "error"));
                        responses.add(new ToolResponseMessage.ToolResponse(tc.id(), fnName,
                                writeJson(Map.of("success", false, "error",
                                        "工具不存在或当前页面/步骤不可用: " + fnName))));
                        continue;
                    }
                    // BACKEND 工具：Loop 内直接执行，结果收集进本轮 ToolResponseMessage
                    Object result = backendToolExecutor.execute(fnName, parseArgs(fnArgs));
                    boolean ok = !(result instanceof Map<?, ?> m && m.containsKey("error"));
                    send(emitter, "tool_run", Map.of("name", fnName, "status", ok ? "ok" : "error"));
                    responses.add(new ToolResponseMessage.ToolResponse(tc.id(), fnName,
                            truncate(writeJson(result))));
                    synchronized (session) {
                        session.displayMessages.add(AiSessionStore.displayMsg("assistant", "", fnName));
                        trimDisplay(session);
                    }
                }

                if (frontendCall == null) {
                    // 全部 BACKEND：一条 ToolResponseMessage 装多个 ToolResponse，继续下一轮
                    chatMemory.add(sessionId,
                            List.of(ToolResponseMessage.builder().responses(responses).build()));
                    continue;
                }

                // FRONTEND 工具：保存暂停现场 → SSE tool_call → done(waiting) 结束流
                PendingCall pending = new PendingCall(frontendCall.id(), frontendCall.name(),
                        frontendCall.arguments(), frontendDef.needConfirm(), calls, responses);
                synchronized (session) {
                    session.pendingCall = pending;
                    session.displayMessages.add(AiSessionStore.displayMsg("assistant", "", frontendCall.name()));
                    trimDisplay(session);
                }
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("callId", frontendCall.id());
                event.put("name", frontendCall.name());
                event.put("arguments", parseArgs(frontendCall.arguments()));
                event.put("needConfirm", frontendDef.needConfirm());
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

    private record RoundOutcome(String content, List<AssistantMessage.ToolCall> toolCalls, String reasoning) {
    }

    /**
     * 单轮流式调用（含 429/5xx/网络异常指数退避重试 ≤3 次；spring.ai.retry 只覆盖 call()，
     * 流式无官方重试，已产出内容不重试）。
     * 返回 null 表示失败（错误事件已发送）或前端已断开。
     */
    private RoundOutcome callRound(List<Message> messages, OpenAiChatOptions options, SseEmitter emitter) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            StringBuilder content = new StringBuilder();
            StringBuilder reasoningBuf = new StringBuilder();
            List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
            boolean emitted = false;
            try {
                Flux<ChatResponse> flux = chatModel.stream(new Prompt(messages, options));
                for (ChatResponse chatResponse : flux.toIterable()) {
                    // include_usage 末帧 / 异常帧：choices 为空，需判空跳过
                    if (chatResponse.getResults() == null || chatResponse.getResults().isEmpty()) {
                        continue;
                    }
                    for (Generation generation : chatResponse.getResults()) {
                        AssistantMessage output = generation.getOutput();
                        if (output == null) {
                            continue;
                        }
                        String text = output.getText();
                        if (text != null && !text.isEmpty()) {
                            content.append(text);
                            if (!send(emitter, "token", Map.of("text", text))) {
                                return null; // 前端已断开
                            }
                            emitted = true;
                        }
                        // OpenAI 模块流式路径把思维链放在 properties["reasoningContent"]
                        Object rc = output.getMetadata().get(REASONING_KEY);
                        if (rc instanceof String reasoning && !reasoning.isEmpty()) {
                            reasoningBuf.append(reasoning);
                            if (!send(emitter, "reasoning", Map.of("text", reasoning))) {
                                return null;
                            }
                            emitted = true;
                        }
                        // 流式 tool_calls 分片已被框架合并，直接可用
                        if (!CollectionUtils.isEmpty(output.getToolCalls())) {
                            toolCalls.addAll(output.getToolCalls());
                        }
                    }
                }
                // deepseek-flash 为思考模式：回填时必须携带本轮 reasoning_content，否则下一轮 400
                String reasoningText = reasoningBuf.isEmpty() ? null
                        : abbrev(reasoningBuf.toString(), MAX_REASONING_REPLAY);
                return new RoundOutcome(content.toString(), toolCalls, reasoningText);
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

    // ================================ 辅助 ================================

    /** 聚合入库的 assistant 消息：思维链放 properties["reasoningContent"]（为空则不放该键），由补丁类回放 */
    private AssistantMessage aggregateAssistantMessage(String content, List<AssistantMessage.ToolCall> toolCalls,
                                                       String reasoning) {
        return AssistantMessage.builder()
                .content(content)
                .toolCalls(toolCalls)
                .properties(reasoning == null ? Map.of() : Map.of(REASONING_KEY, reasoning))
                .build();
    }

    /** 补全缺失的 callId，空参数归一为 "{}"；assistant 回填与 ToolResponse 必须使用同一组 id */
    private AssistantMessage.ToolCall normalizeCall(AssistantMessage.ToolCall tc) {
        String callId = (tc.id() == null || tc.id().isBlank())
                ? "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24)
                : tc.id();
        String args = (tc.arguments() == null || tc.arguments().isBlank()) ? "{}" : tc.arguments();
        String type = (tc.type() == null || tc.type().isBlank()) ? "function" : tc.type();
        return new AssistantMessage.ToolCall(callId, type, tc.name(), args);
    }

    /**
     * 历史保护：ChatMemory 窗口淘汰从头部进行，可能拆散 assistant(tool_calls)/tool 配对。
     * 丢弃开头孤立的 tool 消息，以及 tool 应答不完整的 assistant(tool_calls) 消息，避免协议 400。
     */
    private List<Message> sanitizedHistory(String sessionId) {
        List<Message> copy = new ArrayList<>(chatMemory.get(sessionId));
        boolean changed = true;
        while (changed && !copy.isEmpty()) {
            changed = false;
            Message first = copy.get(0);
            if (first.getMessageType() == MessageType.TOOL) {
                copy.remove(0);
                changed = true;
                continue;
            }
            if (first instanceof AssistantMessage am && !CollectionUtils.isEmpty(am.getToolCalls())) {
                Set<String> needed = am.getToolCalls().stream()
                        .map(AssistantMessage.ToolCall::id)
                        .collect(Collectors.toSet());
                Set<String> found = new HashSet<>();
                for (int i = 1; i < copy.size() && copy.get(i).getMessageType() == MessageType.TOOL; i++) {
                    ToolResponseMessage trm = (ToolResponseMessage) copy.get(i);
                    trm.getResponses().forEach(r -> found.add(r.id()));
                }
                if (!found.containsAll(needed)) {
                    copy.remove(0);
                    changed = true;
                }
            }
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
