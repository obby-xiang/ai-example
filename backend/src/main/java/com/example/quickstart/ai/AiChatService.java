package com.example.quickstart.ai;

import com.example.quickstart.common.JsonUtil;
import com.example.quickstart.entity.Task;
import com.example.quickstart.runtime.SessionHolder;
import com.example.quickstart.service.TaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 聊天服务：每轮组装动态系统提示词 + 按任务类型渐进式注册工具，流式输出 SSE。
 * 工具调用由 OpenAiChatModel 内部 ToolCallingManager 自动循环执行（Spring AI 高层能力）。
 */
@Slf4j
@Service
public class AiChatService {

    private static final long SSE_TIMEOUT_MS = 300_000L;

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final SystemPromptBuilder promptBuilder;
    private final AiSessionRegistry sessions;
    private final SessionHolder sessionHolder;
    private final TaskService taskService;
    private final BasicAiTools basicTools;
    private final ExportAiTools exportTools;
    private final ImportAiTools importTools;
    private final Executor aiExecutor;

    public AiChatService(ChatClient chatClient, ChatMemory chatMemory,
                         SystemPromptBuilder promptBuilder, AiSessionRegistry sessions,
                         SessionHolder sessionHolder, TaskService taskService,
                         BasicAiTools basicTools, ExportAiTools exportTools, ImportAiTools importTools,
                         @Qualifier("aiTaskExecutor") Executor aiExecutor) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.promptBuilder = promptBuilder;
        this.sessions = sessions;
        this.sessionHolder = sessionHolder;
        this.taskService = taskService;
        this.basicTools = basicTools;
        this.exportTools = exportTools;
        this.importTools = importTools;
        this.aiExecutor = aiExecutor;
    }

    public SseEmitter chat(String sessionId, String message, boolean skipLlm) {
        if (!sessions.tryBeginChat(sessionId)) {
            SseEmitter emitter = new SseEmitter(1000L);
            sendEvent(emitter, "error", Map.of("message", "当前会话有对话正在处理，请稍候"));
            emitter.complete();
            return emitter;
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        String msg = message == null ? "" : message.trim();
        if (msg.isEmpty()) {
            sendEvent(emitter, "error", Map.of("message", "消息不能为空"));
            sendEvent(emitter, "done", Map.of("status", "error"));
            emitter.complete();
            sessions.endChat(sessionId);
            return emitter;
        }
        aiExecutor.execute(() -> {
            AtomicBoolean completed = new AtomicBoolean(false);
            try {
                Task activeTask = activeTaskOf(sessionId);
                String system = promptBuilder.build(sessionId, activeTask);

                Object[] tools = toolsFor(activeTask);

                StringBuilder visible = new StringBuilder();
                chatClient.prompt()
                        .system(system)
                        .user(msg)
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                        .tools(tools)
                        .toolContext(Map.of("sessionId", sessionId))
                        .stream()
                        .chatResponse()
                        .doOnNext(cr -> {
                            String delta = cr.getResult() != null && cr.getResult().getOutput() != null
                                    ? cr.getResult().getOutput().getText() : null;
                            if (delta != null && !delta.isEmpty()) {
                                visible.append(delta);
                                sendEvent(emitter, "token", Map.of("text", delta));
                            }
                        })
                        .doOnError(e -> log.error("AI 流式输出异常 session={}", sessionId, e))
                        .blockLast();

                sendEvent(emitter, "done", Map.of("status", "finished"));
                completed.set(true);
            } catch (Exception e) {
                log.error("AI 对话失败 session={}", sessionId, e);
                String friendly = friendlyError(e);
                sendEvent(emitter, "error", Map.of("message", friendly));
                sendEvent(emitter, "done", Map.of("status", "error"));
                completed.set(true);
            } finally {
                sessions.endChat(sessionId);
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        });
        return emitter;
    }

    private Object[] toolsFor(Task activeTask) {
        if (activeTask == null) {
            return new Object[]{basicTools};
        }
        if (TaskService.EXPORT_CONFIG.equals(activeTask.getType())) {
            return new Object[]{basicTools, exportTools};
        }
        return new Object[]{basicTools, importTools};
    }

    private Task activeTaskOf(String sessionId) {
        SessionHolder.TaskRef ref = sessionHolder.get(sessionId);
        if (ref == null || ref.taskId() == null) {
            return null;
        }
        try {
            Task t = taskService.require(ref.taskId());
            if (TaskService.SUCCESS.equals(t.getStatus()) || TaskService.CANCELLED.equals(t.getStatus())
                    || TaskService.FAILED.equals(t.getStatus())) {
                return null;
            }
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    private String friendlyError(Exception e) {
        String s = String.valueOf(e.getMessage());
        if (s.contains("401") || s.contains("Authentication")) {
            return "模型服务认证失败，请检查 DEEPSEEK_API_KEY 配置";
        }
        if (s.contains("429")) {
            return "模型服务限流，请稍后重试";
        }
        if (s.contains("currently only one tool call")) {
            return "模型一次发起了多个工具调用，暂不支持并行工具，请重试或换个说法";
        }
        return "AI 服务异常：" + s;
    }

    private void sendEvent(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(JsonUtil.write(data)));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 发送失败（客户端可能已断开）：{}", e.getMessage());
        }
    }

    /* ---------------- 历史恢复（刷新不丢） ---------------- */

    public List<Map<String, Object>> history(String sessionId) {
        List<Message> messages = chatMemory.get(sessionId);
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Message m : messages) {
            if (m instanceof UserMessage um) {
                result.add(Map.of("role", "user", "content", textOf(um)));
            } else if (m instanceof AssistantMessage am) {
                String text = textOf(am);
                if (text != null && !text.isBlank()) {
                    result.add(Map.of("role", "assistant", "content", text));
                }
            }
        }
        return result;
    }

    private String textOf(Message m) {
        return m.getText() == null ? "" : m.getText();
    }

    public void clearHistory(String sessionId) {
        chatMemory.clear(sessionId);
    }
}
