package com.example.spike.core;

import com.example.spike.tools.ToolExecutionLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 前端执行工具（暂停-恢复）架构下的显式循环：官方 ChatModel 关掉"内部工具执行"
 * （OpenAiChatOptions.internalToolExecutionEnabled=false）后，把 tool_calls 交给调用方，
 * 由调用方执行并回灌 tool-result。
 *
 * 该架构下"同一 toolCallId 的结果被重复提交"才成为真实的攻击面；
 * 官方循环（内部执行）根本没有这个 HTTP 注入面（见决策卡 V-d2 卡点定位）。
 * 去重由本类自建的 pending 注册表完成 —— 官方无此能力。
 */
@Service
public class FrontendToolLoop {

    private static final Logger log = LoggerFactory.getLogger(FrontendToolLoop.class);
    private static final int MAX_ROUNDS = 4;

    private final ChatModel chatModel;
    private final ToolExecutionLog execLog;
    private final List<ToolCallback> toolCallbacks;
    private final ConcurrentMap<String, Run> runs = new ConcurrentHashMap<>();
    private final AtomicInteger runSeq = new AtomicInteger();

    public FrontendToolLoop(ChatModel chatModel, com.example.spike.tools.SpikeTools spikeTools,
                            ToolExecutionLog execLog) {
        this.chatModel = chatModel;
        this.execLog = execLog;
        this.toolCallbacks = Arrays.asList(MethodToolCallbackProvider.builder()
                .toolObjects(spikeTools).build().getToolCallbacks());
    }

    public static final class Pending {
        public final String toolCallId;
        public final String name;
        public final String arguments;

        Pending(String toolCallId, String name, String arguments) {
            this.toolCallId = toolCallId;
            this.name = name;
            this.arguments = arguments;
        }

        Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("toolCallId", toolCallId);
            m.put("name", name);
            m.put("arguments", arguments);
            return m;
        }
    }

    public static final class Run {
        public final String runId;
        public final String sessionId;
        public volatile String status = "INIT";
        public volatile int round = 0;
        public volatile String finalText = null;
        public volatile List<Pending> pending = List.of();
        public final Map<String, String> submitted = new java.util.concurrent.ConcurrentHashMap<>();
        public final List<Message> messages = new CopyOnWriteArrayList<>();
        public final List<Map<String, Object>> submissions = new CopyOnWriteArrayList<>();

        Run(String runId, String sessionId) {
            this.runId = runId;
            this.sessionId = sessionId;
        }
    }

    // ------------------------------------------------------------------ 启动一轮

    public Map<String, Object> start(String sessionId, String message, String systemPrompt) {
        String runId = "ftl-" + runSeq.incrementAndGet();
        Run r = new Run(runId, sessionId);
        runs.put(runId, r);
        r.messages.add(new SystemMessage(systemPrompt == null || systemPrompt.isBlank()
                ? "你必须调用工具来完成任务。回复使用中文。" : systemPrompt));
        r.messages.add(new UserMessage(message));
        callModel(r);
        Map<String, Object> out = state(runId);
        out.put("status", r.status);
        return out;
    }

    // ------------------------------------------------------------------ 回灌 tool-result

    public Map<String, Object> submitResult(String runId, String toolCallId, String result) {
        Run r = runs.get(runId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (r == null) {
            out.put("httpStatus", 404);
            out.put("accepted", false);
            out.put("error", "unknown runId");
            return out;
        }
        synchronized (r) {
            boolean isPending = r.pending.stream().anyMatch(p -> p.toolCallId.equals(toolCallId));
            boolean alreadySubmitted = r.submitted.containsKey(toolCallId);
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("tsMs", System.currentTimeMillis());
            record.put("toolCallId", toolCallId);
            record.put("result", result);

            if (alreadySubmitted) {
                // ★ 幂等去重：同一 toolCallId 的第二次提交被拒，不改动任何循环状态、不追加任何消息
                record.put("accepted", false);
                record.put("reason", "DUPLICATE_TOOL_CALL_ID");
                r.submissions.add(record);
                out.put("httpStatus", 200);
                out.put("accepted", false);
                out.put("duplicate", true);
                out.put("reason", "DUPLICATE_TOOL_CALL_ID");
                out.put("toolCallId", toolCallId);
                out.put("loopState", loopState(r));
                log.info("FRONTOOL_DUPLICATE_REJECTED runId={} toolCallId={}", runId, toolCallId);
                return out;
            }
            if (!isPending) {
                record.put("accepted", false);
                record.put("reason", "UNKNOWN_TOOL_CALL_ID");
                r.submissions.add(record);
                out.put("httpStatus", 409);
                out.put("accepted", false);
                out.put("unknown", true);
                out.put("reason", "UNKNOWN_TOOL_CALL_ID");
                out.put("pending", r.pending.stream().map(Pending::asMap).toList());
                return out;
            }

            // 首次提交：执行（此处由 spike 代前端执行，真实系统中由前端执行）
            r.submitted.put(toolCallId, result);
            Pending p = r.pending.stream().filter(x -> x.toolCallId.equals(toolCallId)).findFirst().orElseThrow();
            execLog.record("FRONTEND_TOOL", runId, toolCallId, p.name, p.arguments, 0L, "OK");
            record.put("accepted", true);
            record.put("reason", "FIRST_SUBMISSION_EXECUTED");
            r.submissions.add(record);
            log.info("FRONTOOL_RESULT_ACCEPTED runId={} toolCallId={} name={}", runId, toolCallId, p.name);

            boolean allFilled = r.pending.stream().allMatch(x -> r.submitted.containsKey(x.toolCallId));
            out.put("httpStatus", 200);
            out.put("accepted", true);
            out.put("duplicate", false);
            out.put("toolCallId", toolCallId);
            out.put("pendingRemaining", r.pending.stream()
                    .filter(x -> !r.submitted.containsKey(x.toolCallId)).map(x -> x.toolCallId).toList());
            if (allFilled) {
                appendToolResponses(r);
                callModel(r);
                out.put("status", r.status);
                out.put("finalText", r.finalText);
            } else {
                out.put("status", r.status);
            }
            out.put("loopState", loopState(r));
            return out;
        }
    }

    private void appendToolResponses(Run r) {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (Pending p : r.pending) {
            // 严格按 pending 顺序回灌；值取自 submitted（已被去重保护，每个 id 恰一条）
            responses.add(new ToolResponseMessage.ToolResponse(p.toolCallId, p.name, r.submitted.get(p.toolCallId)));
        }
        r.messages.add(ToolResponseMessage.builder().responses(responses).build());
        log.info("FRONTOOL_APPENDED_TOOL_RESPONSES runId={} count={} ids={}", r.runId, responses.size(),
                responses.stream().map(ToolResponseMessage.ToolResponse::id).toList());
    }

    private void callModel(Run r) {
        r.round++;
        if (r.round > MAX_ROUNDS) {
            r.status = "ROUND_LIMIT";
            return;
        }
        ChatResponse resp = chatModel.call(new Prompt(new ArrayList<>(r.messages), options(r.runId, false)));
        AssistantMessage am = resp.getResult().getOutput();
        r.messages.add(am);
        if (am.hasToolCalls()) {
            List<Pending> pending = new ArrayList<>();
            for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                pending.add(new Pending(tc.id(), tc.name(), tc.arguments()));
            }
            r.pending = pending;
            r.submitted.clear();
            r.status = "PAUSED_WAITING_TOOL_RESULTS";
            log.info("FRONTOOL_PAUSED runId={} round={} toolCalls={}", r.runId, r.round,
                    pending.stream().map(p -> p.toolCallId).toList());
        } else {
            r.pending = List.of();
            r.finalText = am.getText();
            r.status = "DONE";
            log.info("FRONTOOL_DONE runId={} round={} rounds_total={}", r.runId, r.round, r.round);
        }
    }

    private OpenAiChatOptions options(String runId, boolean internalToolExecution) {
        OpenAiChatOptions base = (OpenAiChatOptions) chatModel.getDefaultOptions();
        OpenAiChatOptions opts = OpenAiChatOptions.fromOptions(base);
        opts.setInternalToolExecutionEnabled(internalToolExecution);
        opts.setToolCallbacks(toolCallbacks);
        opts.setToolContext(Map.of("runId", runId));
        return opts;
    }

    // ------------------------------------------------------------------ 证据出口

    public Map<String, Object> state(String runId) {
        Run r = runs.get(runId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (r == null) {
            out.put("found", false);
            return out;
        }
        out.put("found", true);
        out.put("runId", r.runId);
        out.put("sessionId", r.sessionId);
        out.put("status", r.status);
        out.put("round", r.round);
        out.put("finalText", r.finalText);
        out.put("pending", r.pending.stream().map(Pending::asMap).toList());
        out.put("submitted", new LinkedHashMap<>(r.submitted));
        out.put("submissions", new ArrayList<>(r.submissions));
        out.put("messageSequence", messageSeq(r));
        out.put("toolExecLogForRun", execLog.all().stream().filter(e -> runId.equals(e.get("runId"))).toList());
        return out;
    }

    private Map<String, Object> loopState(Run r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", r.status);
        m.put("round", r.round);
        m.put("messageCount", r.messages.size());
        m.put("pendingIds", r.pending.stream().map(p -> p.toolCallId).toList());
        m.put("submittedIds", new ArrayList<>(r.submitted.keySet()));
        return m;
    }

    private List<Map<String, Object>> messageSeq(Run r) {
        List<Map<String, Object>> out = new ArrayList<>();
        int i = 0;
        for (Message m : r.messages) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("index", i++);
            e.put("type", m.getMessageType().name());
            e.put("text", m.getText());
            if (m instanceof AssistantMessage am && am.hasToolCalls()) {
                e.put("toolCalls", am.getToolCalls().stream().map(tc -> {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("id", tc.id());
                    t.put("name", tc.name());
                    t.put("arguments", tc.arguments());
                    return t;
                }).toList());
            }
            if (m instanceof ToolResponseMessage trm) {
                e.put("toolResponses", trm.getResponses().stream().map(tr -> {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("id", tr.id());
                    t.put("name", tr.name());
                    t.put("responseData", tr.responseData());
                    return t;
                }).toList());
            }
            out.add(e);
        }
        return out;
    }
}
