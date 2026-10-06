package com.example.spike.core;

import com.example.spike.cancel.CancellationRegistry;
import com.example.spike.config.SpikeProperties;
import com.example.spike.tools.SpikeTools;
import com.example.spike.tools.ToolActivityBeacon;
import com.example.spike.tools.ToolExecutionLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 三种流式策略的对照实现：
 *  - PLAIN  : 无超时、无重试（基线：上游静默时完全无界）
 *  - REF    : 参照分支 AiRuntimeService 的原序 —— takeUntil → timeout(单一值) → retry(1) → doOnNext
 *  - GUARDED: 本 spike 提出的有界方案 —— 首包/事件间双超时 + 有界重试 + "已产出即不重试"
 * 三者共用同一个官方 ChatClient / 官方工具循环，只改韧性包装。
 */
@Service
public class ResilientChatService {

    private static final Logger log = LoggerFactory.getLogger(ResilientChatService.class);

    public static final String DEFAULT_SYSTEM = """
            你是韧性验证助手。回复使用中文，简洁。
            当你需要知道时间时，调用 getServerTime 工具；
            当用户要求执行"长任务/导出/检查"时，调用 longTask 工具。
            """;

    private final ChatClient chatClient;
    private final ChatModel chatModel;
    private final ChatClient bareChatClient;
    private final SpikeProperties props;
    private final CancellationRegistry cancelRegistry;
    private final ToolExecutionLog toolExecLog;
    private final ToolActivityBeacon beacon;
    private final TaskExecutor executor;
    private final List<ToolCallback> toolCallbacks;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public ResilientChatService(ChatClient chatClient, ChatModel chatModel,
                               @Qualifier("bareChatClient") ChatClient bareChatClient,
                               SpikeProperties props,
                               CancellationRegistry cancelRegistry, ToolExecutionLog toolExecLog,
                               SpikeTools spikeTools, ToolActivityBeacon beacon,
                               @Qualifier("spikeExecutor") TaskExecutor executor) {
        this.chatClient = chatClient;
        this.chatModel = chatModel;
        this.bareChatClient = bareChatClient;
        this.props = props;
        this.cancelRegistry = cancelRegistry;
        this.toolExecLog = toolExecLog;
        this.beacon = beacon;
        this.executor = executor;
        this.toolCallbacks = Arrays.asList(MethodToolCallbackProvider.builder()
                .toolObjects(spikeTools).build().getToolCallbacks());
    }

    // ------------------------------------------------------------------ 对外入口

    public SseEmitter chat(String sessionId, String message, String strategy, String systemPrompt) {
        Session s = sessions.computeIfAbsent(sessionId, Session::new);
        String runId = sessionId + "#" + System.currentTimeMillis();
        s.beginRun(runId);
        cancelRegistry.register(runId);

        SseEmitter emitter = new SseEmitter(0L);
        // 客户端断开（脚本提前 abort / 用户点停止）→ 置取消标志（对齐 AiRuntimeService 的 onError 语义）
        // 注意：这里刻意不注册 onCompletion，参照实现用 onCompletion 置取消标志会在正常结束时产生竞态。
        emitter.onError(ex -> {
            s.clientAborted = true;
            s.cancelled.set(true);
            cancelRegistry.cancel(runId);
            log.info("CLIENT_ABORT sessionId={} runId={} err={}", sessionId, runId, ex.toString());
        });
        emitter.onTimeout(() -> {
            s.clientAborted = true;
            s.cancelled.set(true);
            cancelRegistry.cancel(runId);
        });

        executor.execute(() -> execute(s, emitter, message, systemPrompt, strategy, runId));
        return emitter;
    }

    public Map<String, Object> cancel(String sessionId) {
        Session s = sessions.get(sessionId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (s == null) {
            out.put("found", false);
            return out;
        }
        String runId = s.runId;
        boolean registered = cancelRegistry.isRegistered(runId);
        long ts = System.currentTimeMillis();
        cancelRegistry.cancel(runId);
        s.cancelled.set(true);
        s.events.add(evt("cancel-request", Map.of("runId", runId, "registered", registered)));
        out.put("found", true);
        out.put("runId", runId);
        out.put("registryRegistered", registered);
        out.put("cancelSignalTsMs", ts);
        out.put("elapsedSinceRunStartMs", ts - s.startedAt);
        return out;
    }

    public Session session(String sessionId) {
        return sessions.get(sessionId);
    }

    public List<String> sessionIds() {
        return new ArrayList<>(sessions.keySet());
    }

    // ------------------------------------------------------------------ 执行骨架

    private void execute(Session s, SseEmitter emitter, String message, String systemPrompt,
                         String strategy, String runId) {
        try {
            send(emitter, "start", mapOf(
                    "sessionId", s.sessionId,
                    "runId", runId,
                    "strategy", strategy,
                    "firstEventTimeoutMs", props.getStream().getFirstEventTimeout().toMillis(),
                    "interEventTimeoutMs", props.getStream().getInterEventTimeout().toMillis(),
                    "maxAttempts", props.getStream().getMaxAttempts(),
                    "totalBudgetMs", props.getStream().getTotalBudget().toMillis()));

            switch (strategy == null ? "guarded" : strategy) {
                case "plain" -> runPlain(s, emitter, message, systemPrompt);
                case "ref" -> runRef(s, emitter, message, systemPrompt, chatClient, "ref");
                case "refbare" -> runRef(s, emitter, message, systemPrompt, bareChatClient, "refbare");
                case "refmodel" -> runRefModel(s, emitter, message, systemPrompt);
                default -> runGuarded(s, emitter, message, systemPrompt);
            }
        } catch (Throwable t) {
            log.warn("RUN_INTERNAL_ERROR sessionId={} runId={}", s.sessionId, runId, t);
            s.terminal = "FAILED";
            send(emitter, "error", mapOf("code", "INTERNAL_ERROR", "message", String.valueOf(t)));
        } finally {
            s.finishedAt = System.currentTimeMillis();
            if (s.terminal == null) {
                s.terminal = "OK";
            }
            cancelRegistry.unregister(runId);
            if (!"FAILED".equals(s.terminal)) {
                send(emitter, "done", mapOf(
                        "cancelled", "CANCELLED".equals(s.terminal),
                        "terminal", s.terminal,
                        "content", s.content.toString(),
                        "emittedChars", s.emittedChars(),
                        "deltas", s.deltas.get(),
                        "attempts", s.attempts.get(),
                        "elapsedMs", s.elapsedMs()));
            }
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 客户端已断开
            }
            log.info("RUN_END sessionId={} runId={} terminal={} attempts={} elapsedMs={} deltas={} emittedChars={} contentLen={}",
                    s.sessionId, runId, s.terminal, s.attempts.get(), s.elapsedMs(), s.deltas.get(),
                    s.emittedChars(), s.content.length());
        }
    }

    // ------------------------------------------------------------------ REFMODEL：直接用 ChatModel（绕开 ChatClient 的 advisor 链）

    /**
     * 对照组：把参照实现的重试模式用在 {@code ChatModel.stream(prompt)} 上（完全不经过 ChatClient）。
     * 目的：证明"重订阅失败"是 ChatClient advisor 链的问题，而不是 ChatModel/WebClient 的问题；
     * 同时也用来观察"重试会不会把已产出的内容重发一遍"。
     * 注意：此路径没有 memory advisor，历史不落库，仅用于本对照实验。
     */
    private void runRefModel(Session s, SseEmitter emitter, String msg, String sys) {
        long t0 = System.currentTimeMillis();
        AtomicInteger upstreamSubs = new AtomicInteger();
        Prompt prompt = new Prompt(
                List.of(new SystemMessage(sys == null || sys.isBlank() ? DEFAULT_SYSTEM : sys),
                        new UserMessage(msg)),
                modelOptions(s));
        try {
            chatModel.stream(prompt)
                    .doOnSubscribe(x -> log.info("REFMODEL_UPSTREAM_SUBSCRIBE sessionId={} runId={} n={}",
                            s.sessionId, s.runId, upstreamSubs.incrementAndGet()))
                    .takeUntil(cr -> s.cancelled.get())
                    .timeout(props.getStream().getFirstEventTimeout())
                    .retry(1)
                    .doOnError(e -> log.info("REFMODEL_BEFORE_RETRY_ERROR sessionId={} runId={} upstreamSubs={} err={}",
                            s.sessionId, s.runId, upstreamSubs.get(), e.toString()))
                    .doOnNext(cr -> onItem(s, emitter, cr))
                    .blockLast();
            s.refSubscriptions = upstreamSubs.get();
            s.attempts.set(1);
            s.terminal = s.cancelled.get() ? "CANCELLED" : "OK";
            s.attemptRecords.add(attemptRecord(1, "COMPLETED", System.currentTimeMillis() - t0, s.emittedAny, null));
        } catch (Throwable e) {
            s.refSubscriptions = upstreamSubs.get();
            String reason = rootMessage(e);
            s.attempts.set(1);
            s.terminal = "FAILED";
            s.attemptRecords.add(attemptRecord(1, "ERROR", System.currentTimeMillis() - t0, s.emittedAny, reason));
            send(emitter, "error", errorPayload(s, reason, upstreamSubs.get()));
        }
    }

    private ChatOptions modelOptions(Session s) {
        OpenAiChatOptions opts = OpenAiChatOptions.fromOptions((OpenAiChatOptions) chatModel.getDefaultOptions());
        opts.setToolCallbacks(toolCallbacks);
        opts.setToolContext(Map.of("runId", s.runId, "sessionId", s.sessionId));
        return opts;
    }

    // ------------------------------------------------------------------ PLAIN：无超时无重试

    private void runPlain(Session s, SseEmitter emitter, String msg, String sys) {        try {
            baseRequest(s, msg, sys).stream().chatResponse()
                    .doOnNext(cr -> onItem(s, emitter, cr))
                    .blockLast();
            s.terminal = s.cancelled.get() ? "CANCELLED" : "OK";
        } catch (Throwable e) {
            s.terminal = "FAILED";
            String reason = rootMessage(e);
            s.attemptRecords.add(attemptRecord(1, "ERROR", s.elapsedMs(), s.emittedAny, reason));
            send(emitter, "error", errorPayload(s, reason, 1));
        }
    }

    // ------------------------------------------------------------------ REF：参照实现原序

    private void runRef(Session s, SseEmitter emitter, String msg, String sys, ChatClient client, String label) {
        long t0 = System.currentTimeMillis();
        AtomicInteger upstreamSubs = new AtomicInteger();
        try {
            baseRequest(client, s, msg, sys).stream().chatResponse()
                    // 放在 retry 之前：重试若真的重订阅上游，这里会数到 2
                    .doOnSubscribe(x -> log.info("REF_UPSTREAM_SUBSCRIBE strategy={} sessionId={} runId={} n={}",
                            label, s.sessionId, s.runId, upstreamSubs.incrementAndGet()))
                    .takeUntil(cr -> s.cancelled.get())
                    // 参照实现此处为硬编码 90s；spike 用可配值（默认 5s）以缩短实验时长，其余顺序一致
                    .timeout(props.getStream().getFirstEventTimeout())
                    .doOnError(e -> log.info("REF_BEFORE_RETRY_ERROR strategy={} sessionId={} runId={} upstreamSubs={} err={}",
                            label, s.sessionId, s.runId, upstreamSubs.get(), e.toString()))
                    .retry(1)
                    .doOnNext(cr -> onItem(s, emitter, cr))
                    .blockLast();
            s.refSubscriptions = upstreamSubs.get();
            s.attempts.set(1);
            s.terminal = s.cancelled.get() ? "CANCELLED" : "OK";
            s.attemptRecords.add(attemptRecord(1, "COMPLETED", System.currentTimeMillis() - t0, s.emittedAny, null));
        } catch (Throwable e) {
            s.refSubscriptions = upstreamSubs.get();
            String reason = rootMessage(e);
            s.attempts.set(1);
            s.terminal = "FAILED";
            s.attemptRecords.add(attemptRecord(1, "ERROR", System.currentTimeMillis() - t0, s.emittedAny, reason));
            send(emitter, "error", errorPayload(s, reason, upstreamSubs.get()));
        }
    }

    // ------------------------------------------------------------------ GUARDED：有界重试

    private void runGuarded(Session s, SseEmitter emitter, String msg, String sys) {
        int maxAttempts = Math.max(1, props.getStream().getMaxAttempts());
        long budgetMs = props.getStream().getTotalBudget().toMillis();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            s.attempts.set(attempt);
            long t0 = System.currentTimeMillis();
            try {
                streamOnce(s, msg, sys, emitter).blockLast();
                s.attemptRecords.add(attemptRecord(attempt, "COMPLETED",
                        System.currentTimeMillis() - t0, s.emittedAny, null));
                s.terminal = s.cancelled.get() ? "CANCELLED" : "OK";
                return;
            } catch (Throwable e) {
                long cost = System.currentTimeMillis() - t0;
                String reason = rootMessage(e);
                boolean emitted = s.emittedAny;
                // 副作用判定要覆盖"工具已开始执行但还没返回"（此时台账尚无记录，信标里已有）
                boolean sideEffect = toolExecLog.hasExecutionsForRun(s.runId) || beacon.hasActivity(s.runId);
                boolean overBudget = (System.currentTimeMillis() - s.startedAt) >= budgetMs;
                boolean hasNext = attempt < maxAttempts;
                // 重试安全条件：本轮既未对客户端产出内容，也未产生任何工具副作用。
                // 实测教训（vd1-guarded-tooldup 第一轮）：只判"是否已产出内容"不够——工具副作用
                // 可能在内容之前就已发生，重试会把工具再执行一遍。
                boolean retryable = !emitted && !sideEffect && hasNext && !overBudget;
                s.attemptRecords.add(attemptRecord(attempt, "ERROR", cost, emitted, reason));
                log.warn("ATTEMPT_FAILED sessionId={} attempt={} costMs={} emittedAny={} sideEffect={} overBudget={} hasNext={} retryable={} reason={}",
                        s.sessionId, attempt, cost, emitted, sideEffect, overBudget, hasNext, retryable, reason);
                if (!retryable) {
                    s.terminal = "FAILED";
                    send(emitter, "error", errorPayload(s, reason, attempt, sideEffect));
                    return;
                }
                send(emitter, "retry", mapOf(
                        "nextAttempt", attempt + 1,
                        "reason", reason,
                        "elapsedMs", System.currentTimeMillis() - s.startedAt,
                        "emittedChars", s.emittedChars()));
            }
        }
    }

    private Flux<ChatResponse> streamOnce(Session s, String msg, String sys, SseEmitter emitter) {
        return withFirstAndInterTimeout(baseRequest(s, msg, sys).stream().chatResponse(),
                props.getStream().getFirstEventTimeout(), props.getStream().getInterEventTimeout())
                .takeUntil(cr -> s.cancelled.get())
                .doOnNext(cr -> onItem(s, emitter, cr));
    }

    /**
     * 首包 + 事件间双超时。实测（scripts/FluxTimeoutProbe.java 探针 B）显示 Reactor 3.8 的
     * {@code Flux.timeout(Duration)} 单值形式已同时覆盖"首包"与"事件间"，
     * 这里仍拆成两个上界：首次请求要等模型排队，事件间是模型吐字，业务容忍度不同。
     */
    private static <T> Flux<T> withFirstAndInterTimeout(Flux<T> flux, Duration first, Duration inter) {
        return flux.timeout(Mono.delay(first), item -> Mono.delay(inter));
    }

    private ChatClient.ChatClientRequestSpec baseRequest(Session s, String msg, String sys) {
        return baseRequest(chatClient, s, msg, sys);
    }

    private ChatClient.ChatClientRequestSpec baseRequest(ChatClient client, Session s, String msg, String sys) {
        return client.prompt()
                .system(sys == null || sys.isBlank() ? DEFAULT_SYSTEM : sys)
                .user(msg)
                .toolCallbacks(toolCallbacks)
                .toolContext(Map.of("runId", s.runId, "sessionId", s.sessionId))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, s.sessionId));
    }

    // ------------------------------------------------------------------ 事件处理

    private void onItem(Session s, SseEmitter emitter, ChatResponse cr) {
        if (cr == null || cr.getResult() == null) {
            log.info("STREAM_ITEM sessionId={} runId={} result=null", s.sessionId, s.runId);
            return;
        }
        AssistantMessage out = cr.getResult().getOutput();
        if (out == null) {
            log.info("STREAM_ITEM sessionId={} runId={} output=null", s.sessionId, s.runId);
            return;
        }
        String text = out.getText();
        log.info("STREAM_ITEM sessionId={} runId={} tsOffset={}ms textLen={} hasToolCalls={}",
                s.sessionId, s.runId, System.currentTimeMillis() - s.startedAt,
                text == null ? -1 : text.length(), out.hasToolCalls());
        if (out.hasToolCalls()) {
            for (AssistantMessage.ToolCall tc : out.getToolCalls()) {
                Map<String, Object> p = mapOf("id", tc.id(), "name", tc.name(), "arguments", tc.arguments());
                s.toolCallItems.add(p);
                send(emitter, "tool_call", p);
                log.info("TOOL_CALL_ITEM sessionId={} runId={} id={} name={} args={}",
                        s.sessionId, s.runId, tc.id(), tc.name(), tc.arguments());
            }
        }
        String delta = out.getText();
        if (delta != null && !delta.isEmpty()) {
            s.emittedAny = true;
            s.deltas.incrementAndGet();
            s.content.append(delta);
            send(emitter, "delta", mapOf("content", delta));
        }
    }

    private Map<String, Object> errorPayload(Session s, String reason, int attempts) {
        return errorPayload(s, reason, attempts, toolExecLog.hasExecutionsForRun(s.runId));
    }

    private Map<String, Object> errorPayload(Session s, String reason, int attempts, boolean sideEffect) {
        String code = classify(reason);
        if (sideEffect) {
            code = code + "_AFTER_TOOL_SIDE_EFFECT";
        } else if (s.emittedAny) {
            code = code + "_AFTER_PARTIAL";
        }
        return mapOf(
                "code", code,
                "message", friendly(reason),
                "rawReason", reason,
                "attempts", attempts,
                "partial", s.emittedAny,
                "toolSideEffect", sideEffect,
                "emittedChars", s.emittedChars(),
                "elapsedMs", s.elapsedMs());
    }

    private static String classify(String reason) {
        String r = reason == null ? "" : reason;
        if (r.contains("Timeout") || r.contains("timeout")) {
            return "UPSTREAM_SILENT_TIMEOUT";
        }
        if (r.contains("premature") || r.contains("Premature") || r.contains("reset")
                || r.contains("Connection") || r.contains("closed") || r.contains("EOF")) {
            return "UPSTREAM_CONNECTION_BROKEN";
        }
        return "UPSTREAM_STREAM_ERROR";
    }

    private static String friendly(String reason) {
        return switch (classify(reason)) {
            case "UPSTREAM_SILENT_TIMEOUT" -> "模型服务在约定时间内没有任何事件（上游静默），本轮流已终止。";
            case "UPSTREAM_CONNECTION_BROKEN" -> "与模型服务的连接被中断（流式响应未正常结束），本轮流已终止。";
            default -> "模型服务异常，本轮流已终止：" + reason;
        };
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String m = root.getMessage();
        return m == null ? root.getClass().getName() : (root.getClass().getSimpleName() + ": " + m);
    }

    private Map<String, Object> attemptRecord(int attempt, String outcome, long costMs,
                                             boolean emitted, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("attempt", attempt);
        m.put("outcome", outcome);
        m.put("costMs", costMs);
        m.put("emittedAnythingBefore", emitted);
        m.put("reason", reason);
        return m;
    }

    private void send(SseEmitter emitter, String event, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception e) {
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
            }
        }
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private Map<String, Object> evt(String name, Map<String, Object> data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tsMs", System.currentTimeMillis());
        m.put("event", name);
        m.put("data", data);
        return m;
    }

    /** 每会话一次运行的状态（证据出口）。 */
    public final class Session {

        public final String sessionId;
        public volatile String runId = "-";
        public final AtomicBoolean cancelled = new AtomicBoolean(false);
        public volatile boolean clientAborted = false;
        public volatile boolean emittedAny = false;
        public final AtomicInteger deltas = new AtomicInteger();
        public final AtomicInteger attempts = new AtomicInteger();
        public final StringBuilder content = new StringBuilder();
        public volatile long startedAt;
        public volatile long finishedAt;
        public volatile String terminal;
        public volatile int refSubscriptions = 0;
        public final List<Map<String, Object>> attemptRecords = new CopyOnWriteArrayList<>();
        public final List<Map<String, Object>> toolCallItems = new CopyOnWriteArrayList<>();
        public final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();

        Session(String sessionId) {
            this.sessionId = sessionId;
        }

        void beginRun(String runId) {
            this.runId = runId;
            this.cancelled.set(false);
            this.clientAborted = false;
            this.emittedAny = false;
            this.deltas.set(0);
            this.attempts.set(0);
            this.content.setLength(0);
            this.terminal = null;
            this.startedAt = System.currentTimeMillis();
            this.finishedAt = 0L;
            this.attemptRecords.clear();
            this.toolCallItems.clear();
            this.events.clear();
        }

        public int emittedChars() {
            return content.length();
        }

        public long elapsedMs() {
            long end = finishedAt > 0 ? finishedAt : System.currentTimeMillis();
            return end - startedAt;
        }

        public Map<String, Object> snapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", sessionId);
            m.put("runId", runId);
            m.put("terminal", terminal);
            m.put("cancelled", cancelled.get());
            m.put("clientAborted", clientAborted);
            m.put("emittedAny", emittedAny);
            m.put("deltas", deltas.get());
            m.put("emittedChars", emittedChars());
            m.put("content", content.toString());
            m.put("attempts", attempts.get());
            m.put("elapsedMs", elapsedMs());
            m.put("attemptRecords", new ArrayList<>(attemptRecords));
            m.put("toolCallItems", new ArrayList<>(toolCallItems));
            m.put("cancelRegistryRegistered", cancelRegistry.isRegistered(runId));
            m.put("cancelFlagValue", cancelled.get());
            m.put("refSubscriptions", refSubscriptions);
            m.put("toolExecutionsForRun", toolExecLog.all().stream()
                    .filter(e -> runId.equals(e.get("runId"))).toList());
            return m;
        }
    }
}
