package com.example.configadmin.ai;

import com.example.configadmin.common.ApiException;
import com.example.configadmin.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 运行时（官方 Spring AI 能力组合，不自行实现协议/循环/Schema）：
 * - Agent 循环：ChatClient + 模型内部 ToolCallingManager（HooksToolCallingManager 仅做 HITL 与可见性钩子）；
 * - 工具：@Tool 注解方法（AiTools），JSON Schema 由 Spring AI 自动生成；
 * - 历史：MessageChatMemoryAdvisor + AiSessionChatMemory（按页签会话，H2 持久化）；
 * - 流式：stream().chatResponse() 增量推送（含 reasoningContent 折叠展示）。
 */
@Component
public class AiRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(AiRuntimeService.class);

    private final AiSessionStore store;
    private final Services services;
    private final ObjectMapper mapper;
    private final List<ToolCallback> allCallbacks;
    private final ChatClient chatClient;
    private final TaskExecutor executor;

    public AiRuntimeService(ChatModel chatModel, AiSessionStore store, AiTools aiTools, Services services,
                            AiSessionChatMemory chatMemory, ObjectMapper mapper,
                            @Qualifier("taskExecutor") TaskExecutor executor) {
        this.store = store;
        this.services = services;
        this.mapper = mapper;
        this.executor = executor;
        // 全部 @Tool 方法 → ToolCallback（Spring AI 自动生成 Schema）
        this.allCallbacks = Arrays.asList(MethodToolCallbackProvider.builder()
                .toolObjects(aiTools).build().getToolCallbacks());
        // ChatClient：官方记忆 Advisor（会话隔离靠 advisor param CONVERSATION_ID）
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /** 发起对话（SSE 流式）。 */
    public SseEmitter doChat(String sessionId, String message, Map<String, Object> context) {
        AiSession s = store.getOrCreate(sessionId);
        s.touch();
        s.setCancelled(false);
        s.autoDenyPending(); // 遗留未确认操作 → 自动视为取消（安全默认）
        s.setContext(context == null ? new LinkedHashMap<>() : context);

        SseEmitter emitter = new SseEmitter(0L);
        executor.execute(() -> {
            try {
                String page = String.valueOf(s.getContext().getOrDefault("page", "export"));
                List<ToolCallback> callbacks = callbacksForPage(page);
                Map<String, Object> toolCtx = Map.of(
                        "session", s,
                        "sink", (UiEventSink) ev -> {
                            String type = String.valueOf(ev.getOrDefault("type", "ui_event"));
                            if ("ping".equals(type)) {
                                // 确认门等待期间的心跳：仅保活，不下发业务事件
                                send(emitter, "ping", Map.of());
                                return;
                            }
                            if ("tool_start".equals(type) || "tool_result".equals(type) || "confirm_tool".equals(type)) {
                                // 钩子事件：SSE 事件名 = 类型，data 为剩余字段
                                Map<String, Object> payload = new LinkedHashMap<>(ev);
                                payload.remove("type");
                                send(emitter, type, payload);
                            } else {
                                // 业务 ui_event：统一经 ui_event 事件下发（前端分发到工作区）
                                send(emitter, "ui_event", ev);
                            }
                        },
                        "services", services);

                // 客户端断开（点“停止”/关闭页签/测试脚本提前断开）→ 自动释放 HITL 确认门并终止流
                emitter.onCompletion(() -> {
                    s.approveConfirm(false);
                    s.setCancelled(true);
                });
                emitter.onError(ex -> {
                    s.approveConfirm(false);
                    s.setCancelled(true);
                });
                emitter.onTimeout(() -> {
                    s.approveConfirm(false);
                    s.setCancelled(true);
                });

                StringBuilder full = new StringBuilder();
                // 立即下发 start 事件：确保响应头尽早发出（代理/网关不会因首包延迟超时断开）
                send(emitter, "start", Map.of("sessionId", sessionId));
                // 整条流调度到 boundedElastic：HITL 门在工具执行处的阻塞不会占用 Netty 事件循环线程
                chatClient.prompt()
                        .system(buildSystemPrompt(page, callbacks))
                        .user(message)
                        .toolCallbacks(callbacks)
                        .toolContext(toolCtx)
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                        .stream()
                        .chatResponse()
                        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                        .takeUntil(cr -> s.isCancelled())
                        // 健壮性：90 秒无任何事件视为上游断流 → 自动重试一次（确认门等待期间有心跳保活）
                        .timeout(java.time.Duration.ofSeconds(90))
                        .retry(1)
                        .doOnNext(cr -> {
                            AssistantMessage out = cr.getResult() == null ? null : cr.getResult().getOutput();
                            if (out == null) {
                                return;
                            }
                            String delta = out.getText();
                            if (delta != null && !delta.isEmpty()) {
                                full.append(delta);
                                send(emitter, "delta", Map.of("content", delta));
                            }
                            Object rc = out.getMetadata() == null ? null : out.getMetadata().get("reasoningContent");
                            if (rc != null && !String.valueOf(rc).isEmpty()) {
                                send(emitter, "reasoning", Map.of("content", String.valueOf(rc)));
                            }
                        })
                        .doFinally(sig -> {
                            // 流终止（含取消）时兜底释放遗留确认门
                            s.approveConfirm(false);
                        })
                        .blockLast();

                if (s.isCancelled()) {
                    send(emitter, "done", Map.of("content", full.toString(), "cancelled", true));
                } else {
                    send(emitter, "done", Map.of("content", full.toString(), "cancelled", false));
                }
                store.save(s);
            } catch (Exception e) {
                log.warn("AI 对话异常 session={}", sessionId, e);
                send(emitter, "error", Map.of("message", friendlyError(e)));
            } finally {
                emitter.complete();
            }
        });
        return emitter;
    }

    /** 确认/取消破坏性工具：释放确认门，原 chat SSE 流继续推进。 */
    public void confirm(String sessionId, boolean approved) {
        AiSession s = store.get(sessionId);
        s.touch();
        if (!s.approveConfirm(approved)) {
            throw ApiException.badRequest("当前没有待确认的操作（可能已超时取消）");
        }
    }

    /** 停止生成：取消标记 + 释放遗留确认门。 */
    public void stop(String sessionId) {
        AiSession s = store.getQuiet(sessionId);
        if (s == null) {
            return;
        }
        s.setCancelled(true);
        s.approveConfirm(false);
    }

    /** 当前页披露的工具清单（供 /api/ai/tools 查看）。 */
    public List<Map<String, Object>> toolsForPage(String page) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        callbacksForPage(page).forEach(cb -> {
            ToolMeta.Meta meta = ToolMeta.ALL.get(cb.getToolDefinition().name());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", cb.getToolDefinition().name());
            m.put("description", cb.getToolDefinition().description());
            m.put("confirm", meta != null && meta.confirm());
            out.add(m);
        });
        return out;
    }

    /** 当前页披露的工具回调（渐进式披露）。 */
    private List<ToolCallback> callbacksForPage(String page) {
        return allCallbacks.stream()
                .filter(cb -> {
                    ToolMeta.Meta meta = ToolMeta.ALL.get(cb.getToolDefinition().name());
                    return meta != null && meta.visibleOn(page);
                })
                .toList();
    }

    /** 渐进式披露提示词：仅披露当前页可用工具。 */
    private String buildSystemPrompt(String page, List<ToolCallback> callbacks) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是「AI 辅助动态配置管理系统」的智能助手。右侧为对话区，左侧为业务工作区（与对话联动）。\n");
        sb.append("当前工作区页面：").append(pageLabel(page)).append("。\n");
        sb.append("行为准则：\n");
        sb.append("1. 优先调用工具完成操作；工具执行结果会自动同步到左侧工作区（无需用户手工操作）。\n");
        sb.append("2. 涉及破坏性操作（创建/修改定义、写入数据、导入、发布）的工具执行前系统会请求用户确认；确认前不要宣称操作已完成。\n");
        sb.append("3. 查询类操作无需确认，直接执行。\n");
        sb.append("4. 用户可能在工作区手工操作后再与你对话；若用户描述与工作区状态不符，以工作区当前状态为准（先调用 get_ui_state 核对）。\n");
        sb.append("5. 回复使用中文，简洁、结构化，重要结论放在前面。\n");
        sb.append("6. 若操作失败，如实说明原因并给出修正建议。\n");
        sb.append("7. 不要重复调用同一工具（相同或近似参数）；一次调用获取的信息直接复用。\n");
        sb.append("\n当前页面可用工具（仅可调用以下工具）：\n");
        callbacks.forEach(cb -> {
            ToolMeta.Meta meta = ToolMeta.ALL.get(cb.getToolDefinition().name());
            sb.append("- ").append(cb.getToolDefinition().name()).append("：")
                    .append(cb.getToolDefinition().description());
            if (meta != null && meta.confirm()) {
                sb.append("（需用户确认）");
            }
            sb.append("\n");
        });
        return sb.toString();
    }

    private String pageLabel(String page) {
        return switch (page) {
            case "export" -> "导出配置向导";
            case "import" -> "导入配置向导";
            case "defs" -> "配置定义管理";
            case "data" -> "数据浏览";
            case "tasks" -> "任务管理";
            default -> page;
        };
    }

    private void send(SseEmitter emitter, String event, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception e) {
            // 客户端断开（如点击“停止”或关闭页签）：终止 emitter 以触发 onError → 释放确认门并取消流
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
            }
        }
    }

    private String friendlyError(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        if (msg.contains("401") || msg.contains("Unauthorized")) {
            return "模型服务认证失败（API Key 无效或未配置）。";
        }
        if (msg.contains("timeout") || msg.contains("Timeout")) {
            return "模型服务响应超时，请稍后重试。";
        }
        return "模型服务异常：" + (msg.length() > 200 ? msg.substring(0, 200) : msg);
    }
}
