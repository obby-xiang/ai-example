package com.example.configadmin.ai;

import com.example.configadmin.service.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiApi.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后端 AI Runtime（Agent 循环）：
 * 1. 流式调用 OpenAI 兼容模型（DeepSeek），SSE 推送 delta/reasoning/tool_start/tool_result/ui_event/done；
 * 2. 手动工具循环：模型产出 tool_calls → 执行后端工具 → 回填消息 → 下一轮（最多 maxIterations 轮）；
 * 3. 渐进式披露：仅把当前页面允许的工具清单放入请求与系统提示词；
 * 4. HITL：confirm=true 的破坏性工具暂停流、经 ui_event 请求确认，确认后经 /api/ai/confirm 续跑；
 * 5. 状态同步：前端上下文快照 → 影子状态；工具写操作更新影子状态并下发 ui_event 镜像前端。
 */
@Service
public class AiRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(AiRuntimeService.class);

    private final OpenAiApi openAiApi;
    private final AiSessionStore store;
    private final ToolRegistry registry;
    private final ObjectMapper mapper;
    private final ConfigDefService defService;
    private final ConfigDataService dataService;
    private final ExportService exportService;
    private final ImportService importService;
    private final TaskRunner taskRunner;
    private final TaskExecutor taskExecutor;

    @Value("${spring.ai.openai.chat.options.model:deepseek-flash}")
    private String model;

    @Value("${app.ai.max-tool-iterations:12}")
    private int maxIterations;

    @Value("${app.ai.max-messages:40}")
    private int maxMessages;

    @Value("${app.ai.tool-result-max-chars:4000}")
    private int toolResultMaxChars;

    public AiRuntimeService(OpenAiApi openAiApi, AiSessionStore store, ToolRegistry registry,
                            ObjectMapper mapper, ConfigDefService defService, ConfigDataService dataService,
                            ExportService exportService, ImportService importService, TaskRunner taskRunner,
                            @Qualifier("taskExecutor") TaskExecutor taskExecutor) {
        this.openAiApi = openAiApi;
        this.store = store;
        this.registry = registry;
        this.mapper = mapper;
        this.defService = defService;
        this.dataService = dataService;
        this.exportService = exportService;
        this.importService = importService;
        this.taskRunner = taskRunner;
        this.taskExecutor = taskExecutor;
    }

    // ==================== 入口 ====================

    public void chat(String sessionId, String message, Map<String, Object> context, SseEmitter emitter) {
        taskExecutor.execute(() -> doChat(sessionId, message, context, emitter));
    }

    private void doChat(String sessionId, String message, Map<String, Object> context, SseEmitter emitter) {
        try {
            AiSession s = store.getOrCreate(sessionId);
            s.touch();
            if (message == null || message.isBlank()) {
                sendError(emitter, "消息不能为空");
                return;
            }
            // 1) 合并上下文快照（业务 → AI）
            if (context != null) {
                Map<String, Object> merged = new LinkedHashMap<>(s.getContext());
                merged.putAll(context);
                s.setContext(merged);
            }
            // 2) 未确认的旧操作：自动视为拒绝（超时安全默认：拒绝）
            if (s.getPending() != null) {
                AiSession.PendingConfirm p = s.getPending();
                s.getMessages().add(toolResultMsg(p.calls().get(p.index()),
                        "用户未确认（发送了新消息），视为拒绝该操作"));
                s.setPending(null);
            }
            s.getMessages().add(new ChatCompletionMessage(message, ChatCompletionMessage.Role.USER));
            trim(s);
            runLoop(s, emitter);
        } catch (Exception e) {
            log.error("AI 对话异常", e);
            sendError(emitter, "系统异常：" + e.getMessage());
        }
    }

    /** 确认续跑：approved=true 执行工具并继续批次与循环；false 回填拒绝结果继续。 */
    public void confirm(String sessionId, boolean approved, SseEmitter emitter) {
        taskExecutor.execute(() -> {
            try {
                AiSession s = store.get(sessionId);
                s.touch();
                AiSession.PendingConfirm p = s.getPending();
                if (p == null) {
                    sendError(emitter, "没有待确认的操作（可能已超时或已被处理）");
                    return;
                }
                s.setPending(null);
                ChatCompletionMessage.ToolCall tc = p.calls().get(p.index());
                if (approved) {
                    AiTool tool = registry.get(tc.function().name()).orElse(null);
                    Map<String, Object> args = parseArgs(tc.function().arguments());
                    if (tool == null) {
                        s.getMessages().add(toolResultMsg(tc, "错误：未注册的工具"));
                        send(emitter, "tool_result", Map.of("name", tc.function().name(), "ok", false, "summary", "未注册工具"));
                    } else if (!executeOne(s, emitter, tc, tool, args)) {
                        return;
                    }
                } else {
                    s.getMessages().add(toolResultMsg(tc, "用户拒绝了该操作"));
                    send(emitter, "tool_result", Map.of("name", tc.function().name(), "ok", false, "summary", "用户已拒绝执行"));
                }
                processToolBatch(s, emitter, p.calls(), p.index() + 1);
            } catch (Exception e) {
                log.error("确认续跑异常", e);
                sendError(emitter, "系统异常：" + e.getMessage());
            }
        });
    }

    // ==================== Agent 循环 ====================

    private void runLoop(AiSession s, SseEmitter emitter) {
        for (int iter = 0; iter < maxIterations; iter++) {
            ensureSystemPrompt(s);
            List<FunctionTool> tools = registry.forPage(pageOf(s)).stream()
                    .map(this::toFunctionTool).toList();
            List<ChatCompletionMessage> messages = List.copyOf(s.getMessages());

            StringBuilder text = new StringBuilder();
            List<ChatCompletionMessage.ToolCall> pending = new ArrayList<>();

            try {
                ChatCompletionRequest req = buildRequest(messages, tools);
                Flux<ChatCompletionChunk> flux = openAiApi.chatCompletionStream(req);
                for (ChatCompletionChunk chunk : flux.toIterable()) {
                    if (chunk.choices() == null || chunk.choices().isEmpty()) {
                        if (chunk.usage() != null) {
                            if (!send(emitter, "usage", Map.of("usage", String.valueOf(chunk.usage())))) return;
                        }
                        continue;
                    }
                    ChatCompletionChunk.ChunkChoice choice = chunk.choices().get(0);
                    ChatCompletionMessage delta = choice.delta();
                    if (delta == null) continue;
                    Object c = delta.content();
                    if (c != null && !String.valueOf(c).isEmpty()) {
                        text.append(String.valueOf(c));
                        if (!send(emitter, "delta", Map.of("content", String.valueOf(c)))) return;
                    }
                    if (delta.reasoningContent() != null && !delta.reasoningContent().isEmpty()) {
                        if (!send(emitter, "reasoning", Map.of("content", delta.reasoningContent()))) return;
                    }
                    if (choice.finishReason() == ChatCompletionFinishReason.TOOL_CALLS
                            && delta.toolCalls() != null) {
                        pending.addAll(delta.toolCalls());
                    }
                }
            } catch (Exception e) {
                log.warn("模型调用失败 session={}", s.getId(), e);
                sendError(emitter, "模型调用失败：" + extractAiError(e));
                return;
            }

            if (pending.isEmpty()) {
                s.getMessages().add(new ChatCompletionMessage(text.toString(), ChatCompletionMessage.Role.ASSISTANT));
                trim(s);
                send(emitter, "done", Map.of("content", text.toString()));
                emitter.complete();
                return;
            }

            // 回填 assistant(tool_calls)（注意：reasoning_content 绝不能回传，DeepSeek 会 400）
            List<ChatCompletionMessage.ToolCall> calls = pending.stream()
                    .map(tc -> new ChatCompletionMessage.ToolCall(tc.id(), "function",
                            new ChatCompletionMessage.ChatCompletionFunction(
                                    tc.function().name(), tc.function().arguments())))
                    .toList();
            s.getMessages().add(new ChatCompletionMessage(null, ChatCompletionMessage.Role.ASSISTANT,
                    null, null, calls, null, null, null, null));
            if (!processToolBatch(s, emitter, pending, 0)) {
                return;
            }
        }
        sendError(emitter, "工具调用轮次超过上限（" + maxIterations + "），已停止");
    }

    /** 处理工具批次；confirm 工具会暂停（返回 false 表示流已结束）。 */
    private boolean processToolBatch(AiSession s, SseEmitter emitter,
                                     List<ChatCompletionMessage.ToolCall> calls, int from) {
        for (int i = from; i < calls.size(); i++) {
            ChatCompletionMessage.ToolCall tc = calls.get(i);
            String name = tc.function().name();
            Map<String, Object> args = parseArgs(tc.function().arguments());
            if (!send(emitter, "tool_start", Map.of("name", name, "args", args == null ? Map.of() : args))) {
                return false;
            }
            AiTool tool = registry.get(name).orElse(null);
            if (tool == null) {
                s.getMessages().add(toolResultMsg(tc, "错误：未注册的工具 " + name));
                if (!send(emitter, "tool_result", Map.of("name", name, "ok", false, "summary", "未注册工具"))) {
                    return false;
                }
                continue;
            }
            if (tool.def().confirm()) {
                // HITL：暂停流，前端展示确认卡片；确认后经 /api/ai/confirm 续跑
                s.setPending(new AiSession.PendingConfirm(calls, i));
                Map<String, Object> uiEvent = new LinkedHashMap<>();
                uiEvent.put("type", "confirm_tool");
                uiEvent.put("toolCallId", tc.id() == null ? String.valueOf(i) : tc.id());
                uiEvent.put("toolName", name);
                uiEvent.put("summary", args == null ? "无参数" : mapper.valueToTree(args).toString());
                if (!send(emitter, "ui_event", uiEvent)) return false;
                send(emitter, "done", Map.of("content", "⏸ 请求执行「" + name + "」，请在下方确认或取消"));
                emitter.complete();
                return false;
            }
            if (!executeOne(s, emitter, tc, tool, args)) {
                return false;
            }
        }
        runLoop(s, emitter);
        return true;
    }

    private boolean executeOne(AiSession s, SseEmitter emitter, ChatCompletionMessage.ToolCall tc,
                               AiTool tool, Map<String, Object> args) {
        try {
            ToolContext tctx = new ToolContext(s, mapper, defService, dataService,
                    exportService, importService, taskRunner);
            ToolResult res = tool.run(tctx, args == null ? Map.of() : args);
            String truncated = truncate(res.getText());
            s.getMessages().add(toolResultMsg(tc, truncated));
            if (!send(emitter, "tool_result", Map.of("name", tool.def().name(), "ok", res.isOk(),
                    "summary", truncated))) {
                return false;
            }
            for (Map<String, Object> ui : res.getUiEvents()) {
                if (!send(emitter, "ui_event", ui)) return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("工具执行失败 {}", tool.def().name(), e);
            String msg = "工具执行失败：" + e.getMessage();
            s.getMessages().add(toolResultMsg(tc, msg));
            return send(emitter, "tool_result", Map.of("name", tool.def().name(), "ok", false, "summary", msg));
        }
    }

    // ==================== 组装 ====================

    private ChatCompletionRequest buildRequest(List<ChatCompletionMessage> messages, List<FunctionTool> tools) {
        return new ChatCompletionRequest(
                messages,                       // messages
                model,                          // model
                null, null, null, null, null, null, // store/metadata/frequencyPenalty/logitBias/logprobs/topLogprobs
                4096,                           // maxTokens（DeepSeek 用 max_tokens）
                null,                           // maxCompletionTokens（与 max_tokens 互斥）
                null,                           // n
                null, null,                     // modalities/audio
                null,                           // presencePenalty
                null,                           // responseFormat
                null,                           // seed
                null,                           // serviceTier
                null,                           // stop
                true,                           // stream
                ChatCompletionRequest.StreamOptions.INCLUDE_USAGE, // streamOptions
                null,                           // temperature（走模型默认）
                null,                           // topP
                tools,                          // tools
                ChatCompletionRequest.ToolChoiceBuilder.AUTO, // toolChoice
                false,                          // parallelToolCalls（1.1.8 合并器仅支持单帧单调用）
                null, null, null, null, null, null, null); // 其余
    }

    private FunctionTool toFunctionTool(ToolDef d) {
        Map<String, Object> schema = new LinkedHashMap<>(d.jsonSchema());
        return new FunctionTool(new FunctionTool.Function(d.description(), d.name(), schema, null));
    }

    private ChatCompletionMessage toolResultMsg(ChatCompletionMessage.ToolCall tc, String content) {
        return new ChatCompletionMessage(content, ChatCompletionMessage.Role.TOOL,
                tc.function().name(), tc.id(), null, null, null, null, null);
    }

    private Map<String, Object> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return Map.of();
        try {
            return mapper.readValue(argsJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("工具参数解析失败: {}", argsJson);
            return Map.of("_raw", argsJson);
        }
    }

    private String pageOf(AiSession s) {
        Object p = s.getContext().get("page");
        return p == null ? "unknown" : String.valueOf(p);
    }

    /** 系统提示词：每次循环重建（页面切换后渐进披露的工具清单随之变化）。 */
    private void ensureSystemPrompt(AiSession s) {
        String page = pageOf(s);
        List<ToolDef> tools = registry.forPage(page);
        StringBuilder sb = new StringBuilder();
        sb.append("你是「AI 辅助动态配置管理系统」的中文助手，界面左侧是业务工作区（任务向导），右侧是对话栏。\n");
        sb.append("当前页面：").append(pageLabel(page)).append("\n");
        sb.append("当前工作区状态快照（JSON，写操作前可调用 get_ui_state 查看最新）：\n");
        sb.append(mapper.valueToTree(s.getContext()).toString()).append("\n\n");
        sb.append("当前页面可用工具（渐进式披露，只能使用这些）：\n");
        for (ToolDef t : tools) {
            sb.append("- ").append(t.name()).append("：").append(t.description())
                    .append(t.confirm() ? "（⚠ 执行前需用户确认）" : "").append("\n");
        }
        sb.append("\n行为准则：\n");
        sb.append("1. 先理解用户意图；不确定工作区状态时先调 get_ui_state。\n");
        sb.append("2. 工具执行会自动通过 ui_event 同步前端界面（勾选配置、跳转步骤、启动任务、触发下载等），你在回复中简要说明做了什么即可。\n");
        sb.append("3. 执行破坏性操作（导入/发布/写入数据）前工具会要求用户确认，此时应停止并等待确认结果。\n");
        sb.append("4. 用户没有明确要求时不要擅自执行写操作。\n");
        sb.append("5. 回复使用中文，简洁、结构化，重要结论放在前面。\n");
        sb.append("6. 若操作失败，如实说明原因并给出修正建议。");

        List<ChatCompletionMessage> msgs = s.getMessages();
        ChatCompletionMessage sys = new ChatCompletionMessage(sb.toString(), ChatCompletionMessage.Role.SYSTEM);
        if (!msgs.isEmpty() && msgs.get(0).role() == ChatCompletionMessage.Role.SYSTEM) {
            msgs.set(0, sys);
        } else {
            msgs.add(0, sys);
        }
    }

    private String pageLabel(String page) {
        return switch (page) {
            case "export" -> "导出配置向导";
            case "import" -> "导入配置向导";
            case "defs" -> "配置定义管理";
            case "data" -> "数据浏览";
            default -> page;
        };
    }

    private void trim(AiSession s) {
        List<ChatCompletionMessage> msgs = s.getMessages();
        // 保留 system 头 + 最近 maxMessages 条
        while (msgs.size() > 1 && msgs.size() - 1 > maxMessages) {
            msgs.remove(1);
        }
    }

    private String truncate(String text) {
        if (text == null) return "";
        return text.length() <= toolResultMaxChars ? text
                : text.substring(0, toolResultMaxChars) + "\n…（结果已截断）";
    }

    // ==================== SSE 发送 ====================

    /** 从异常链中提取模型服务返回的原始错误详情（便于定位 4xx 具体原因）。 */
    private String extractAiError(Throwable e) {
        Throwable cause = e;
        int depth = 0;
        while (cause != null && depth < 10) {
            if (cause instanceof org.springframework.web.reactive.function.client.WebClientResponseException wre) {
                String body = wre.getResponseBodyAsString();
                if (body != null && !body.isBlank()) {
                    return "HTTP " + wre.getStatusCode().value() + " " + body;
                }
                return "HTTP " + wre.getStatusCode().value() + " " + wre.getStatusText();
            }
            cause = cause.getCause();
            depth++;
        }
        return e.getMessage();
    }

    private boolean send(SseEmitter emitter, String event, Object data) {        try {
            emitter.send(SseEmitter.event().name(event).data(mapper.writeValueAsString(data)));
            return true;
        } catch (Exception e) {
            log.debug("SSE 发送失败（客户端断开）");
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    private void sendError(SseEmitter emitter, String message) {
        send(emitter, "error", Map.of("message", message));
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }
}
