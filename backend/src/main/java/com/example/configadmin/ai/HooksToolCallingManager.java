package com.example.configadmin.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具执行钩子管理器：协议与循环完全交给 Spring AI（OpenAiChatModel 内部循环），
 * 本类只组合包装官方 DefaultToolCallingManager，做两件业务增强：
 * 1) 工具可见性：每次工具执行前后向 SSE 推送 tool_start / tool_result；
 * 2) HITL 确认门：confirm=true 的工具（见 ToolMeta）执行前挂起等待前端确认，
 *    批准→放行执行；取消/超时→以“用户取消”作为工具结果回填，由模型如实解释。
 * 按 Spring AI 官方扩展点实现：OpenAiChatModel.Builder.toolCallingManager(...) 注入
 * （在 AiModelConfig 中组装，普通类不注册为组件，避免与官方自动配置条件互斥）。
 */
public class HooksToolCallingManager implements ToolCallingManager {

    private static final Logger log = LoggerFactory.getLogger(HooksToolCallingManager.class);

    private final ToolCallingManager delegate;
    private final ObjectMapper mapper;
    private final AiSessionStore store;

    public HooksToolCallingManager(ToolCallingManager delegate, ObjectMapper mapper, AiSessionStore store) {
        this.delegate = delegate;
        this.mapper = mapper;
        this.store = store;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        AssistantMessage assistant = chatResponse.getResult().getOutput();
        if (assistant == null || !assistant.hasToolCalls()) {
            return delegate.executeToolCalls(prompt, chatResponse);
        }

        UiEventSink sink = sinkOf(prompt);
        AiSession session = sessionOf(prompt);
        List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();

        for (AssistantMessage.ToolCall call : calls) {
            String name = call.name();
            Map<String, Object> args = parseArgs(call.arguments());
            if (sink != null) {
                sink.emit(Map.of("type", "tool_start", "name", name, "args", args));
            }

            // HITL：确认门
            if (ToolMeta.confirmRequired(name)) {
                AiSession.ConfirmGate gate = session == null ? null
                        : session.registerConfirm(name, summarize(args));
                if (sink != null) {
                    sink.emit(Map.of("type", "confirm_tool", "toolCallId", call.id(),
                            "toolName", name, "summary", summarize(args)));
                }
                boolean approved = gate == null || Boolean.TRUE.equals(
                        reactor.core.publisher.Mono.fromCallable(() -> gate.await(15, sink))
                                .subscribeOn(Schedulers.boundedElastic())
                                .block());
                if (!approved) {
                    String refusal = "用户取消了该操作，未执行。";
                    responses.add(new ToolResponseMessage.ToolResponse(call.id(), name, refusal));
                    if (sink != null) {
                        sink.emit(Map.of("type", "tool_result", "name", name, "ok", false, "summary", refusal));
                    }
                    continue;
                }
            }

            // 委托官方管理器执行该调用（单调用构造，逐个执行以保留顺序与确认门）
            try {
                AssistantMessage single = AssistantMessage.builder().toolCalls(List.of(call)).build();
                ToolExecutionResult r = delegate.executeToolCalls(prompt, new ChatResponse(List.of(new Generation(single))));
                String resultText = r.conversationHistory().stream()
                        .filter(m -> m instanceof ToolResponseMessage)
                        .map(m -> (ToolResponseMessage) m)
                        .flatMap(trm -> trm.getResponses().stream())
                        .filter(tr -> call.id() != null && call.id().equals(tr.id()))
                        .map(ToolResponseMessage.ToolResponse::responseData)
                        .findFirst()
                        .orElse("");
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), name, resultText));
                if (sink != null) {
                    sink.emit(Map.of("type", "tool_result", "name", name, "ok", true,
                            "summary", truncate(resultText)));
                }
            } catch (Exception e) {
                log.warn("工具执行失败 name={}", name, e);
                String failure = "工具执行失败：" + e.getMessage();
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), name, failure));
                if (sink != null) {
                    sink.emit(Map.of("type", "tool_result", "name", name, "ok", false, "summary", failure));
                }
            }
        }

        // 组装官方约定的 conversationHistory：[带全部工具调用的助手消息, 工具结果消息]
        ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder().responses(responses).build();
        // 流式路径下 MessageChatMemoryAdvisor 不保存工具消息 → 此处直接写入会话记忆，
        // 保证多轮上下文完整 + 刷新恢复历史含工具卡片
        if (session != null) {
            session.getMessages().add(assistant);
            session.getMessages().add(toolResponseMessage);
            store.save(session);
        }
        return ToolExecutionResult.builder()
                .conversationHistory(List.of(assistant, toolResponseMessage))
                .build();
    }

    private UiEventSink sinkOf(Prompt prompt) {
        Map<String, Object> ctx = toolContextOf(prompt);
        Object o = ctx == null ? null : ctx.get("sink");
        return o instanceof UiEventSink sink ? sink : null;
    }

    private AiSession sessionOf(Prompt prompt) {
        Map<String, Object> ctx = toolContextOf(prompt);
        Object o = ctx == null ? null : ctx.get("session");
        return o instanceof AiSession s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toolContextOf(Prompt prompt) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions opts) {
            return opts.getToolContext();
        }
        return Map.of();
    }

    private Map<String, Object> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(argsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            return Map.of("_raw", argsJson);
        }
    }

    private String summarize(Map<String, Object> args) {
        return truncate(String.valueOf(args));
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }
}
