package com.example.ai.service;

import com.example.ai.dto.AiDTO;
import com.example.ai.entity.AiChatMessage;
import com.example.ai.entity.AgentState;
import com.example.ai.entity.ConfigDefinition;
import com.example.ai.entity.Task;
import com.example.ai.repository.AiChatMessageRepository;
import com.example.ai.repository.AgentStateRepository;
import com.example.ai.tool.FrontendTools;
import com.example.ai.tool.ToolDiscoveryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 对话服务 —— 方案二：后端 Runtime + Agent Loop + 前端工具暂停-恢复。
 *
 * ====== 架构核心（业界主流 LangGraph checkpointer / OpenAI Assistants Thread） ======
 *   1. agent loop 在后端：调 DeepSeek → 解析 tool_calls → 分类处理 → 必要时回灌结果 → 继续调用
 *   2. 所有工具定义集中在后端 FrontendTools（用户硬约束），前端只接收 toolName+args
 *   3. 工具分类：
 *      - FRONTEND：loop 暂停，保存 AgentState(resumeToken) → 返回前端 → 前端执行 → POST /tool-result 回灌 → 恢复 loop
 *      - BACKEND ：loop 内同步执行（预留扩展点，当前 7 个工具均为 FRONTEND）
 *   4. loop 结束条件：模型不再返回 tool_calls（返回最终回复）
 *
 * ====== 单一对话入口 ======
 *   - chat(req)             : 首次发起/继续对话
 *   - submitToolResult(req) : 前端工具执行结果回灌，恢复 loop
 *   两个入口共享 runAgentLoop() 核心逻辑
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiService {

    private final AiChatMessageRepository messageRepository;
    private final AgentStateRepository agentStateRepository;
    private final TaskService taskService;
    private final ConfigDefinitionService definitionService;
    private final ToolDiscoveryService toolDiscoveryService;

    // =============== DeepSeek 原生 API 配置 ===============
    @Value("${app.ai.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${app.ai.chat-path:/chat/completions}")
    private String chatPath;

    @Value("${app.ai.model:deepseek-v4-flash}")
    private String model;

    @Value("${app.ai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.chat.options.temperature:0.2}")
    private double temperature;

    @Value("${spring.ai.openai.chat.options.max-tokens:4096}")
    private int maxTokens;

    /** ====== 改造 C1 ====== WAITING_TOOL 状态存活时间（分钟），超时拒绝回灌（AG-UI 超时安全默认：拒绝而非自动提交） */
    @Value("${app.agent.waiting-tool-ttl-minutes:10}")
    private long waitingToolTtlMinutes;

    /** ====== 改造 H2 ====== LLM 调用超时（秒）。长上下文流式生成可能 60s+ 不出 token，默认 30s 会误杀，需 120s+ */
    @Value("${app.ai.read-timeout-seconds:180}")
    private int readTimeoutSeconds;

    /** ====== 改造 H1 ====== 可重试错误的最大重试次数（429/5xx/网络超时，指数退避） */
    @Value("${app.ai.max-retries:3}")
    private int maxRetries;

    /** Agent loop 最大迭代次数（防止无限循环） */
    private static final int MAX_LOOP_ITERATIONS = 8;

    /**
     * ====== 改造 F2：协作式中断标志（sessionId 维度） ======
     * /cancel 接口置位；agent loop 每轮开始检查，命中则当前轮边界停止（保留现场，不回滚）。
     * 新一轮 chat() 入口清除标志（用户发新消息=显式开启新 run）。
     */
    private final Set<String> cancelledSessions = ConcurrentHashMap.newKeySet();

    private volatile RestClient cachedClient;

    private RestClient client() {
        if (cachedClient == null) {
            synchronized (this) {
                if (cachedClient == null) {
                    // ====== 改造 H2 ====== 显式超时：connect 10s / read 默认 180s（配置项 app.ai.read-timeout-seconds）。
                    // 长上下文流式生成可能 60s+ 不出 token，默认 30s 会误杀。
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(10_000);
                    factory.setReadTimeout(readTimeoutSeconds * 1000);
                    cachedClient = RestClient.builder()
                            .baseUrl(baseUrl)
                            .requestFactory(factory)
                            .build();
                }
            }
        }
        return cachedClient;
    }

    private final ObjectMapper om = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {};

    // ================================ 对外接口 ================================

    public List<AiChatMessage> listHistory(Long taskId, String sessionId) {
        if (taskId != null) {
            return messageRepository.findByTaskIdOrderByCreatedAtAscIdAsc(taskId);
        }
        if (sessionId != null) {
            return messageRepository.findByTaskIdIsNullAndSessionIdOrderByCreatedAtAscIdAsc(sessionId);
        }
        return Collections.emptyList();
    }

    /**
     * 首次发起/继续对话入口。
     * 调用流程：
     *   1. 保存用户消息
     *   2. 启动 agent loop
     *   3. 返回 {reply, toolCalls?, resumeToken?, done}
     */
    @Transactional
    public AiDTO.ChatResp chat(AiDTO.ChatReq req) {
        // 0. 清理同一 session 残留的 WAITING_TOOL 状态（避免冲突）
        cleanupWaitingState(req.getSessionId());

        // 1. 保存用户消息
        AiChatMessage userMsg = AiChatMessage.builder()
                .taskId(req.getTaskId())
                .sessionId(req.getSessionId())
                .role("user")
                .content(req.getMessage())
                .build();
        messageRepository.save(userMsg);

        // 2. 构造完整 messages 数组（system + 历史 + user）
        List<Map<String, Object>> messages = buildRawMessages(req);

        // 3. 启动 agent loop（按当前 scenario+step 动态过滤工具集）
        return runAgentLoop(req, messages, null, req.getCurrentScenario(), req.getCurrentStep());
    }

    /**
     * 前端工具执行结果回灌入口 —— 恢复暂停的 agent loop。
     * 调用流程：
     *   1. 加载 AgentState(resumeToken)
     *   2. 把 tool result 追加为 tool message
     *   3. 继续 agent loop（从 state 恢复 scenario+step）
     */
    @Transactional
    public AiDTO.ChatResp submitToolResult(AiDTO.ToolResultReq req) {
        // 1. 加载 state
        AgentState state = agentStateRepository.findById(req.getResumeToken())
                .orElseThrow(() -> new IllegalArgumentException("resumeToken 无效或已过期: " + req.getResumeToken()));
        if (!"WAITING_TOOL".equals(state.getStatus())) {
            throw new IllegalStateException("state 状态非 WAITING_TOOL，无法回灌: " + state.getStatus());
        }

        // ====== 改造 C2：交互超时取消 ======
        // 过期拒绝回灌 + 置 EXPIRED + 明确错误码（AG-UI 准则：超时默认最安全动作=拒绝）。
        // 前端 pending 卡片有倒计时（用 expiresAt），超时置灰「已过期，请重新发起」。
        if (state.getExpiresAt() != null && state.getExpiresAt().isBefore(LocalDateTime.now())) {
            state.setStatus("EXPIRED");
            agentStateRepository.save(state);
            throw new IllegalStateException("PENDING_EXPIRED: 交互已超时（超过 "
                    + waitingToolTtlMinutes + " 分钟未响应），请重新发起操作");
        }

        // 2. 反序列化 messages + pendingToolCalls
        List<Map<String, Object>> messages;
        List<AiDTO.FrontendToolCall> pendingCalls;
        try {
            messages = om.readValue(state.getMessagesJson(), LIST_MAP_TYPE);
            pendingCalls = om.readValue(state.getPendingToolCallsJson(),
                    new TypeReference<List<AiDTO.FrontendToolCall>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("AgentState 反序列化失败: " + e.getMessage(), e);
        }

        // 3. 找到本次回灌对应的 toolCall（OpenAI 协议要求 tool message 必须有 tool_call_id）
        //    pendingCalls 中第一个未回灌的（前端按顺序回灌）
        AiDTO.FrontendToolCall matchedCall = null;
        for (AiDTO.FrontendToolCall tc : pendingCalls) {
            if (tc.getCallId().equals(req.getCallId())) {
                matchedCall = tc;
                break;
            }
        }
        if (matchedCall == null) {
            throw new IllegalArgumentException("callId 不匹配当前 pending toolCalls: " + req.getCallId());
        }

        // 4. 把 assistant 的 tool_calls 消息（如果尚未追加）追加为 assistant message
        //    注意：暂停时我们只在 messages 里追加了 user 的 system + 历史，没有追加 assistant
        //    的 tool_calls 响应。这里要把那次 assistant 响应补齐（OpenAI 协议要求）。
        appendAssistantToolCallMessage(messages, state, matchedCall);

        // 5. 把 tool 执行结果作为 tool message 追加到 messages
        Map<String, Object> toolMessage = new LinkedHashMap<>();
        toolMessage.put("role", "tool");
        toolMessage.put("tool_call_id", matchedCall.getCallId());
        // DeepSeek 要求 content 是字符串
        String resultStr;
        try {
            resultStr = om.writeValueAsString(req.getResult() == null ? Map.of("ok", false) : req.getResult());
        } catch (JsonProcessingException e) {
            resultStr = "{\"ok\":false,\"message\":\"result serialization failed\"}";
        }
        // ====== 改造 G2：工具结果截断（>2000 字符摘要化，防单次结果打爆上下文）======
        if (resultStr.length() > 2000) {
            resultStr = resultStr.substring(0, 2000)
                    + "...(结果过长已截断,共 " + resultStr.length() + " 字符。如需完整数据请调 get_workspace_state 现查)";
        }
        // ====== 改造 D3c：dataVersion 变更感知 ======
        // 挂起时记录 dataVersion 到 AgentState（D3b）；回灌携带最新快照（D3a）。
        // 版本不一致 → tool result 前置系统提示，强制 AI 先现查再决策。
        // 决策（plan v1.1）：提示并强制现查但不阻断回灌——配置场景下用户手工修改后再确认是合法路径。
        Long suspendedVersion = state.getDataVersion();
        Long currentVersion = extractDataVersion(req.getWorkspaceState());
        if (suspendedVersion != null && currentVersion != null && !suspendedVersion.equals(currentVersion)) {
            resultStr = "[系统] 等待期间工作区已被用户手动修改（v" + suspendedVersion + "→v" + currentVersion
                    + "），后续决策请先调 get_workspace_state 获取最新状态。\n" + resultStr;
            log.info("回灌检测到 dataVersion 变更: sessionId={} v{}→v{}", state.getSessionId(), suspendedVersion, currentVersion);
        }
        toolMessage.put("content", resultStr);
        messages.add(toolMessage);

        // 6. 标记 state 为 RUNNING（已使用），并继续 loop
        state.setStatus("RUNNING");
        agentStateRepository.save(state);

        // 7. 构造 ChatReq 等价物（用于 system prompt 上下文 + 状态机上下文）
        AiDTO.ChatReq chatReq = new AiDTO.ChatReq();
        chatReq.setTaskId(state.getTaskId());
        chatReq.setSessionId(state.getSessionId());
        chatReq.setMessage(null); // 不再追加 user message（已在原 chat 中追加过）
        chatReq.setAutoPrompt(false);
        // Phase 2: 从 state 恢复 scenario+step，用于 buildRawMessages 和工具动态发现
        chatReq.setCurrentScenario(state.getCurrentScenario());
        chatReq.setCurrentStep(state.getCurrentStep());

        // 8. 恢复 loop（从 state 恢复 scenario+step，按当前步骤动态过滤工具）
        return runAgentLoopInternal(chatReq, messages, null,
                state.getCurrentScenario(), state.getCurrentStep());
    }

    @Transactional
    public void clear(Long taskId) {
        if (taskId != null) {
            messageRepository.deleteByTaskId(taskId);
        }
    }

    /**
     * ====== 改造 F1 ====== 协作式取消：把 session 的活跃 WAITING_TOOL 全部置 EXPIRED。
     * 业界共识：打断=轮次边界停止+保留现场（LangGraph interrupt / AG-UI RUN_CANCELLED），
     * 非指令级中断、不回滚已完成操作。同步 loop 内的 LLM 调用无法中途杀线程，
     * 采取协作语义：前端 abort HTTP 请求 + 本接口作废 pending state，
     * 后续对该 resumeToken 的回灌会被「状态非 WAITING_TOOL」拒绝。
     *
     * @return 被取消的 state 数
     */
    @Transactional
    public int cancelRun(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return 0;
        // 改造 F2：置位中断标志，运行中的 agent loop 在下一轮迭代边界停止
        cancelledSessions.add(sessionId);
        List<AgentState> waiting = agentStateRepository
                .findAllBySessionIdAndStatus(sessionId, "WAITING_TOOL");
        for (AgentState s : waiting) {
            s.setStatus("EXPIRED");
            agentStateRepository.save(s);
        }
        if (!waiting.isEmpty()) {
            log.info("协作式取消: sessionId={} 作废 {} 个 WAITING_TOOL state", sessionId, waiting.size());
        }
        return waiting.size();
    }

    // ================================ Agent Loop 核心 ================================

    /**
     * Agent loop 入口（首次对话用）。
     * 已包含完整 messages（system + 历史 + user），直接进入 loop。
     * Phase 2.4: 接受 scenario+step，按当前步骤动态过滤工具集（progressive disclosure）
     */
    private AiDTO.ChatResp runAgentLoop(AiDTO.ChatReq req, List<Map<String, Object>> messages,
                                        String interimContent, String scenario, String step) {
        return runAgentLoopInternal(req, messages, interimContent, scenario, step);
    }

    /**
     * Agent loop 内部实现（chat + submitToolResult 共享）。
     *
     * 循环逻辑：
     *   while (iteration < MAX):
     *     1. 调 DeepSeek（按 scenario+step 动态过滤工具集）
     *     2. 解析 content + tool_calls
     *     3. 把 assistant 消息追加到 messages（用于下次调用上下文）
     *     4. 分类 tool_calls：
     *        - BACKEND：同步执行，追加 tool message，继续 loop
     *        - FRONTEND：保存 AgentState（含 scenario+step），返回 ChatResp(done=false, resumeToken, toolCalls)
     *     5. 没有 tool_calls：返回最终回复 ChatResp(done=true)
     */
    private AiDTO.ChatResp runAgentLoopInternal(AiDTO.ChatReq req,
                                                List<Map<String, Object>> messages,
                                                String interimContent,
                                                String scenario, String step) {
        String latestContent = interimContent == null ? "" : interimContent;
        int iteration = 0;

        while (iteration < MAX_LOOP_ITERATIONS) {
            iteration++;
            // ====== 改造 F2：每轮 loop 开始检查中断标志（轮次边界停止，保留现场，不回滚） ======
            if (req.getSessionId() != null && cancelledSessions.remove(req.getSessionId())) {
                log.info("Agent loop 被协作式中断: sessionId={} 第 {} 轮边界停止", req.getSessionId(), iteration);
                String stoppedContent = (latestContent == null || latestContent.isBlank() ? "" : latestContent + "\n")
                        + "（已停止，已完成的操作保留）";
                return finalizeResponse(req, stoppedContent, Collections.emptyList(), null, true,
                        scenario, step, null);
            }
            try {
                // 1. 构造请求体（Phase 2.4: 按 scenario+step 动态过滤工具集）
                Map<String, Object> requestBody = buildRawRequestBody(messages, scenario, step);

                // 2. 调用 DeepSeek
                JsonNode responseNode = callDeepSeekApi(requestBody);

                // 3. 解析 content + tool_calls
                JsonNode msgNode = responseNode.path("choices").get(0).path("message");
                latestContent = msgNode.path("content").isNull() ? "" : msgNode.path("content").asText("");
                latestContent = sanitizeAssistantContent(latestContent);

                JsonNode toolCallsNode = msgNode.path("tool_calls");
                List<RawToolCall> rawCalls = new ArrayList<>();
                if (!toolCallsNode.isMissingNode() && toolCallsNode.isArray()) {
                    for (JsonNode tcNode : toolCallsNode) {
                        try {
                            rawCalls.add(parseRawToolCall(tcNode));
                        } catch (Exception ex) {
                            log.warn("解析 tool_call 失败，跳过", ex);
                        }
                    }
                }

                // 4. 没有 tool_calls → 追加纯文本 assistant 消息，loop 结束返回最终回复
                if (rawCalls.isEmpty()) {
                    appendAssistantMessage(messages, msgNode, rawCalls);
                    return finalizeResponse(req, latestContent, Collections.emptyList(), null, true,
                            scenario, step, null);
                }

                // 5. 分类处理 tool_calls（业界共识：严格串行执行避免依赖问题）
                //    Phase 2.5: 取第一个 tool_call 执行，等结果回灌后再决定下一步
                RawToolCall c = rawCalls.get(0);
                if (rawCalls.size() > 1) {
                    log.warn("AI 返回 {} 个 tool_calls，串行只执行第一个 {}，其余由模型基于结果重新推理",
                            rawCalls.size(), c.name);
                }
                String toolKind = toolDiscoveryService.kindOf(c.name);

                // 6. 只把要执行的这一个 tool_call 放入 assistant 消息（修复 400 bug）
                //    OpenAI 协议要求: assistant.tool_calls 中每个 tool_call_id 必须有对应 tool message 跟随。
                //    串行执行只处理第一个,故 assistant 只放这一个,避免"N 个 tool_calls 配 1 个 tool message"
                //    触发 400 insufficient tool messages。其余 tool_call 丢弃,模型拿到第一个结果后
                //    会重新推理决定下一步(Anthropic/Claude 串行 tool_use 模式)。
                appendAssistantMessage(messages, msgNode, List.of(c));

                if ("BACKEND".equals(toolKind)) {
                    // 后端工具：同步执行（loop 内），追加 tool message，继续下次 loop
                    Object result = executeBackendTool(c.name, c.args, req);
                    Map<String, Object> toolMessage = new LinkedHashMap<>();
                    toolMessage.put("role", "tool");
                    toolMessage.put("tool_call_id", c.id);
                    try {
                        toolMessage.put("content", om.writeValueAsString(result));
                    } catch (JsonProcessingException e) {
                        toolMessage.put("content", "{\"ok\":false}");
                    }
                    messages.add(toolMessage);
                    // 继续下一次 loop（让模型消费结果）
                    continue;
                }

                // FRONTEND 工具：暂停 loop，保存 state（含 scenario+step），返回
                AiDTO.FrontendToolCall frontendDTO = convertRawToolCallToFrontendDTO(c);
                List<AiDTO.FrontendToolCall> frontendDTOs = List.of(frontendDTO);
                String token = saveAgentState(req, messages, frontendDTOs, latestContent, scenario, step);
                AiDTO.ChatResp pauseResp = finalizeResponse(req, latestContent, frontendDTOs, token, false,
                        scenario, step, frontendDTO.getMode());
                // 改造 C3：下发 pending 过期时间，前端渲染倒计时
                pauseResp.setPendingExpiresAt(LocalDateTime.now().plusMinutes(waitingToolTtlMinutes).toString());
                return pauseResp;

            } catch (Exception e) {
                log.error("Agent loop 第 {} 次迭代失败，转入兜底", iteration, e);
                return fallbackResponse(req, e.getMessage());
            }
        }

        // 超过最大迭代数，返回当前内容
        log.warn("Agent loop 达到最大迭代数 {}", MAX_LOOP_ITERATIONS);
        return finalizeResponse(req, latestContent, Collections.emptyList(), null, true,
                scenario, step, null);
    }

    // ================================ AgentState 持久化 ================================

    /** 保存 loop 状态并生成 resumeToken（Phase 2: 含 scenario+step 上下文；改造 C1: TTL 可配置；改造 D3b: 记录挂起时 dataVersion） */
    private String saveAgentState(AiDTO.ChatReq req, List<Map<String, Object>> messages,
                                  List<AiDTO.FrontendToolCall> pendingCalls, String interimContent,
                                  String scenario, String step) {
        String token = "rst-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        try {
            AgentState state = AgentState.builder()
                    .resumeToken(token)
                    .taskId(req.getTaskId())
                    .sessionId(req.getSessionId())
                    .messagesJson(om.writeValueAsString(messages))
                    .pendingToolCallsJson(om.writeValueAsString(pendingCalls))
                    .interimContent(interimContent)
                    .currentScenario(scenario)
                    .currentStep(step)
                    .mode(pendingCalls.isEmpty() ? "AUTO" : pendingCalls.get(0).getMode())
                    .dataVersion(extractDataVersion(req.getWorkspaceState()))
                    .status("WAITING_TOOL")
                    // 改造 C1：WAITING_TOOL TTL 从 24h 缩到可配置（默认 10 分钟），超时拒绝回灌
                    .expiresAt(LocalDateTime.now().plusMinutes(waitingToolTtlMinutes))
                    .build();
            agentStateRepository.save(state);
            return token;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("AgentState 序列化失败: " + e.getMessage(), e);
        }
    }

    /** 从工作区快照中提取 dataVersion（契约字段，见 plan 改造 D2；容忍缺失/类型差异） */
    private Long extractDataVersion(Map<String, Object> workspaceState) {
        if (workspaceState == null) return null;
        Object v = workspaceState.get("dataVersion");
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    /**
     * ====== 改造 B1 ====== 查询 session 当前待处理的 pending 交互（供刷新恢复）。
     * 返回最新一条未过期的 WAITING_TOOL state 的关键信息：
     *   { resumeToken, pendingToolCalls, expiresAt, mode, currentScenario, currentStep, interimContent }
     * 无 pending 返回 null。
     */
    public Map<String, Object> findPendingInteraction(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        List<AgentState> waiting = agentStateRepository
                .findAllBySessionIdAndStatus(sessionId, "WAITING_TOOL");
        LocalDateTime now = LocalDateTime.now();
        AgentState latest = null;
        for (AgentState s : waiting) {
            // 过期的不返回（由 cleanupWaitingState / markExpired 兜底清理）
            if (s.getExpiresAt() != null && s.getExpiresAt().isBefore(now)) continue;
            if (latest == null || (s.getCreatedAt() != null && latest.getCreatedAt() != null
                    && s.getCreatedAt().isAfter(latest.getCreatedAt()))) {
                latest = s;
            }
        }
        if (latest == null) return null;
        List<AiDTO.FrontendToolCall> pendingCalls;
        try {
            pendingCalls = om.readValue(latest.getPendingToolCallsJson(),
                    new TypeReference<List<AiDTO.FrontendToolCall>>() {});
        } catch (JsonProcessingException e) {
            log.warn("pendingToolCalls 反序列化失败: {}", e.getMessage());
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resumeToken", latest.getResumeToken());
        result.put("pendingToolCalls", pendingCalls);
        result.put("expiresAt", latest.getExpiresAt() == null ? null : latest.getExpiresAt().toString());
        result.put("mode", latest.getMode());
        result.put("currentScenario", latest.getCurrentScenario());
        result.put("currentStep", latest.getCurrentStep());
        result.put("interimContent", latest.getInterimContent());
        return result;
    }

    /** 清理同一 session 过期的 WAITING_TOOL 状态（保留活跃的，避免误清理未回灌的 state） */
    private void cleanupWaitingState(String sessionId) {
        if (sessionId == null) return;
        List<AgentState> existing = agentStateRepository
                .findAllBySessionIdAndStatus(sessionId, "WAITING_TOOL");
        LocalDateTime now = LocalDateTime.now();
        for (AgentState s : existing) {
            // 只清理过期的 WAITING_TOOL state，保留活跃的。
            // 修复 bug: 之前无条件清理所有 WAITING_TOOL，导致用户发新消息时
            // 前一个未回灌的 run_flow state 被误设为 EXPIRED → 二次确认后回灌 500。
            // submitToolResult 用 resumeToken 精确查找，多个活跃 WAITING_TOOL 不冲突。
            if (s.getExpiresAt() == null || s.getExpiresAt().isBefore(now)) {
                s.setStatus("EXPIRED");
                agentStateRepository.save(s);
            }
        }
    }

    // ================================ 后端工具执行（预留扩展点） ================================

    /**
     * 后端工具同步执行（Phase 4 实现）。
     *
     * 业界 MCP tools/call 的等价实现：BACKEND 工具在 agent loop 内同步执行，
     * 结果作为 tool message 回灌给模型，模型据此继续推理。
     *
     * 已实现工具：
     *   - list_config_defs  : 列出所有配置定义摘要（id/code/name/fieldCount）
     *   - get_config_def    : 按 defId 查询单个配置定义的字段详情（key/type/label/defaultValue）
     *   - get_workspace_state: 返回前端传入的工作区状态快照（场景/步骤/已选配置项等）
     *
     * 设计动机（Phase 4 核心价值）：
     *   系统不再把所有配置定义全量注入 system prompt（token 浪费 + 上下文污染），
     *   而是 AI 按需调用 list_config_defs / get_config_def 获取信息。
     *   即"告诉 AI 能做什么，而非把数据喂给 AI"（用户核心诉求）。
     */
    private Object executeBackendTool(String name, Map<String, Object> args, AiDTO.ChatReq req) {
        log.info("执行后端工具: name={} args={}", name, args);
        try {
            switch (name == null ? "" : name) {
                case "list_config_defs":
                    return executeListConfigDefs();
                case "get_config_def":
                    return executeGetConfigDef(args);
                case "get_workspace_state":
                    return executeGetWorkspaceState(req);
                default:
                    return Map.of("ok", false, "message", "未实现的后端工具: " + name);
            }
        } catch (Exception e) {
            log.error("后端工具 {} 执行失败", name, e);
            return Map.of("ok", false, "message", "工具执行异常: " + e.getMessage());
        }
    }

    /** list_config_defs：列出所有启用的配置定义摘要 */
    private Object executeListConfigDefs() {
        List<ConfigDefinition> defs = definitionService.listAll();
        List<Map<String, Object>> list = new ArrayList<>();
        for (ConfigDefinition d : defs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", d.getId());
            item.put("code", d.getCode());
            item.put("name", d.getName());
            item.put("fieldCount", d.getColumns() != null ? d.getColumns().size() : 0);
            list.add(item);
        }
        // ====== 改造 G2：大结果截断摘要化（列表>50 项返回前 50+总数+查询提示），防打爆上下文 ======
        if (list.size() > 50) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            r.put("count", list.size());
            r.put("defs", list.subList(0, 50));
            r.put("truncated", true);
            r.put("message", "结果过长已截断：仅返回前 50 条（共 " + list.size()
                    + " 条）。如需某配置项字段详情，请调 get_config_def(defId) 查询。");
            return r;
        }
        return Map.of("ok", true, "count", list.size(), "defs", list);
    }

    /** get_config_def：按 defId 查询单个配置定义的字段详情 */
    private Object executeGetConfigDef(Map<String, Object> args) {
        Object defIdObj = args == null ? null : args.get("defId");
        if (defIdObj == null) {
            return Map.of("ok", false, "message", "缺少必填字段: defId");
        }
        Long defId;
        try {
            defId = ((Number) defIdObj).longValue();
        } catch (ClassCastException e) {
            defId = Long.parseLong(String.valueOf(defIdObj));
        }
        Optional<ConfigDefinition> opt = definitionService.getById(defId);
        if (opt.isEmpty()) {
            return Map.of("ok", false, "message", "配置定义不存在: " + defId);
        }
        ConfigDefinition d = opt.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("id", d.getId());
        result.put("code", d.getCode());
        result.put("name", d.getName());
        result.put("description", d.getDescription());
        result.put("fields", d.getColumns() != null ? d.getColumns() : List.of());
        return result;
    }

    /** get_workspace_state：返回前端传入的工作区状态快照 */
    private Object executeGetWorkspaceState(AiDTO.ChatReq req) {
        Map<String, Object> ws = req.getWorkspaceState();
        if (ws == null || ws.isEmpty()) {
            return Map.of("ok", true, "workspaceState", Map.of(),
                    "message", "当前无工作区状态快照（用户尚未进入向导或未传状态）");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("workspaceState", ws);
        return result;
    }

    // ================================ RawToolCall 内部表示 ================================

    private record RawToolCall(String id, String name, Map<String, Object> args) {}

    private RawToolCall parseRawToolCall(JsonNode tcNode) {
        JsonNode fnNode = tcNode.path("function");
        String name = fnNode.path("name").asText("");
        String id = tcNode.path("id").asText("");
        String argumentsStr = fnNode.path("arguments").asText("{}");
        Map<String, Object> args;
        try {
            args = (argumentsStr == null || argumentsStr.isBlank())
                    ? new LinkedHashMap<>() : om.readValue(argumentsStr, MAP_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("tool_call arguments 不是合法 JSON，原样包装: {}", argumentsStr);
            args = new LinkedHashMap<>();
            args.put("_raw", argumentsStr);
        }
        return new RawToolCall(
                (id == null || id.isBlank()) ? "tc-" + UUID.randomUUID().toString().substring(0, 10) : id,
                name, args);
    }

    /**
     * 把 RawToolCall 转为 FrontendToolCall DTO。
     *
     * Phase 2.5 改造：从 ToolDiscoveryService 注入 autoExec/requireDoubleConfirm/mode 元数据。
     * 业界 Cursor Composer 模式：信任分级让前端知道是 AUTO 自动执行还是 CONFIRM 弹卡片确认。
     */
    private AiDTO.FrontendToolCall convertRawToolCallToFrontendDTO(RawToolCall c) {
        Map<String, Object> args = FrontendTools.normalizeArgs(c.name, c.args);
        boolean autoExec = toolDiscoveryService.isAutoExec(c.name);
        boolean needConfirm = toolDiscoveryService.defaultNeedConfirm(c.name);
        boolean requireDoubleConfirm = toolDiscoveryService.isRequireDoubleConfirm(c.name);
        // 模式：collect_user_input / excel_import → INPUT（前端渲染 Schema-driven 表单收集用户输入/上传文件）；
        //       autoExec=true 且 needConfirm=false → AUTO 自动执行；否则 CONFIRM 等用户确认
        String mode = ("collect_user_input".equals(c.name) || "excel_import".equals(c.name))
                ? "INPUT"
                : ((autoExec && !needConfirm) ? "AUTO" : "CONFIRM");
        return AiDTO.FrontendToolCall.builder()
                .callId(c.id)
                .toolName(c.name)
                .args(args)
                .title(toolTitle(c.name))
                .impact(FrontendTools.summarizeImpact(c.name, args))
                .needConfirm(needConfirm)
                .legacy(false)
                .autoExec(autoExec)
                .requireDoubleConfirm(requireDoubleConfirm)
                .mode(mode)
                .build();
    }

    /** 把 assistant 消息（含 tool_calls）追加到 messages，供下次调用 */
    @SuppressWarnings("unchecked")
    private void appendAssistantMessage(List<Map<String, Object>> messages, JsonNode msgNode, List<RawToolCall> calls) {
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        String content = msgNode.path("content").isNull() ? "" : msgNode.path("content").asText("");
        assistant.put("content", content);
        // DeepSeek thinking 模式：模型可能返回 reasoning_content（思维链）。
        // 官方 API 要求多轮调用时必须把上一轮 reasoning_content 原样传回，否则 400
        // "reasoning_content in the thinking mode must be passed back to the API"。
        // 这里保留 reasoning_content 以满足协议（同时下方 buildRawRequestBody 关闭思考以根治）。
        JsonNode rcNode = msgNode.path("reasoning_content");
        if (!rcNode.isMissingNode() && !rcNode.isNull()) {
            String rc = rcNode.asText("");
            if (!rc.isBlank()) {
                assistant.put("reasoning_content", rc);
            }
        }
        if (!calls.isEmpty()) {
            List<Map<String, Object>> toolCallsArr = new ArrayList<>();
            for (RawToolCall c : calls) {
                Map<String, Object> tc = new LinkedHashMap<>();
                tc.put("id", c.id);
                tc.put("type", "function");
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", c.name);
                try {
                    fn.put("arguments", om.writeValueAsString(c.args));
                } catch (JsonProcessingException e) {
                    fn.put("arguments", "{}");
                }
                tc.put("function", fn);
                toolCallsArr.add(tc);
            }
            assistant.put("tool_calls", toolCallsArr);
        }
        messages.add(assistant);
    }

    /** 暂停时未追加 assistant tool_calls 消息，回灌时补齐 */
    @SuppressWarnings("unchecked")
    private void appendAssistantToolCallMessage(List<Map<String, Object>> messages,
                                                AgentState state,
                                                AiDTO.FrontendToolCall matchedCall) {
        // 如果 messages 末尾已经是 assistant 且带 tool_calls，不再重复追加
        if (!messages.isEmpty()) {
            Map<String, Object> last = messages.get(messages.size() - 1);
            if ("assistant".equals(last.get("role")) && last.get("tool_calls") != null) {
                return;
            }
        }
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", state.getInterimContent() == null ? "" : state.getInterimContent());
        List<Map<String, Object>> toolCallsArr = new ArrayList<>();
        Map<String, Object> tc = new LinkedHashMap<>();
        tc.put("id", matchedCall.getCallId());
        tc.put("type", "function");
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", matchedCall.getToolName());
        try {
            fn.put("arguments", om.writeValueAsString(matchedCall.getArgs()));
        } catch (JsonProcessingException e) {
            fn.put("arguments", "{}");
        }
        tc.put("function", fn);
        toolCallsArr.add(tc);
        assistant.put("tool_calls", toolCallsArr);
        messages.add(assistant);
    }

    // ================================ 响应构造 + 兜底 ================================

    /**
     * 构造最终响应。
     * Phase 2: 加入 scenario/step/mode 元数据，让前端知道当前状态机位置和执行模式。
     */
    private AiDTO.ChatResp finalizeResponse(AiDTO.ChatReq req, String reply,
                                            List<AiDTO.FrontendToolCall> toolCalls,
                                            String resumeToken, boolean done,
                                            String scenario, String step, String mode) {
        // 保存 assistant 消息到 DB（仅当对话结束时保存最终回复）
        AiChatMessage aiMsg = null;
        if (done && reply != null && !reply.isBlank()) {
            AiChatMessage msg = AiChatMessage.builder()
                    .taskId(req.getTaskId())
                    .sessionId(req.getSessionId())
                    .role("assistant")
                    .content(reply)
                    .build();
            aiMsg = messageRepository.save(msg);
        }

        // 兜底：如果模型未走 tool_calls 而走了文本 JSON（罕见），尝试解析
        List<AiDTO.AiAction> legacyActions = Collections.emptyList();
        if (done && toolCalls.isEmpty()) {
            legacyActions = parseActionsFromReply(reply);
            if (!legacyActions.isEmpty()) {
                List<AiDTO.FrontendToolCall> adapted = new ArrayList<>();
                for (AiDTO.AiAction a : legacyActions) {
                    adapted.add(FrontendTools.adaptFromLegacy(a));
                }
                toolCalls = adapted;
            }
        }

        // 序列化 toolCalls 存入 DB
        String toolCallJsonForDb = null;
        if (!toolCalls.isEmpty() && aiMsg != null) {
            try {
                toolCallJsonForDb = om.writeValueAsString(toolCalls);
                aiMsg.setToolCall(toolCallJsonForDb);
                messageRepository.save(aiMsg);
            } catch (JsonProcessingException ignored) {
            }
        }

        // 模式决定：done=true 用 DONE；否则用传入的 mode 或默认 CONFIRM
        String responseMode = done ? "DONE" : (mode == null ? "CONFIRM" : mode);

        return AiDTO.ChatResp.builder()
                .messageId(aiMsg == null ? null : aiMsg.getId())
                .reply(reply == null ? "" : reply)
                .toolCalls(toolCalls)
                .actions(legacyActions)
                .resumeToken(resumeToken)
                .done(done)
                .pendingCallId(done ? null : (toolCalls.isEmpty() ? null : toolCalls.get(0).getCallId()))
                .currentScenario(scenario)
                .currentStep(step)
                .mode(responseMode)
                .build();
    }

    private AiDTO.ChatResp fallbackResponse(AiDTO.ChatReq req, String errMsg) {
        String reply = "【AI服务暂时不可用】" + (errMsg == null ? "" : "原因: " + errMsg);
        List<AiDTO.AiAction> actions = buildFallbackActions(req);
        List<AiDTO.FrontendToolCall> toolCalls = new ArrayList<>();
        for (AiDTO.AiAction a : actions) {
            toolCalls.add(FrontendTools.adaptFromLegacy(a));
        }
        AiChatMessage msg = AiChatMessage.builder()
                .taskId(req.getTaskId())
                .sessionId(req.getSessionId())
                .role("assistant")
                .content(reply)
                .build();
        AiChatMessage saved = messageRepository.save(msg);
        return AiDTO.ChatResp.builder()
                .messageId(saved.getId())
                .reply(reply)
                .toolCalls(toolCalls)
                .actions(actions)
                .resumeToken(null)
                .done(true)
                .mode("DONE")
                .build();
    }

    private String toolTitle(String name) {
        return switch (name == null ? "" : name) {
            case "navigate_step" -> "跳转工作区步骤";
            case "select_definitions" -> "管理配置项选择";
            case "run_flow" -> "一键执行业务流程";
            case "table_batch_set_field" -> "表格按条件批量修改";
            case "table_delete_rows" -> "删除表格数据行";
            case "table_replace_values" -> "表格字段值替换";
            case "confirm_complete" -> "确认完成流程";
            case "collect_user_input" -> "收集用户输入";
            default -> "前端工具";
        };
    }

    // ================================ 原生协议构造 + 调用 ================================

    /**
     * 构造 DeepSeek 请求体。
     *
     * Phase 2.4 改造：用 ToolDiscoveryService 按当前 scenario+step 动态过滤工具集
     * （业界 MCP tools/list + Anthropic Skills Progressive Disclosure）。
     * 若 scenario/step 为 null，用 FrontendTools.RAW_TOOLS_AS_MAPS 兜底（兼容旧调用）。
     */
    private Map<String, Object> buildRawRequestBody(List<Map<String, Object>> messages,
                                                    String scenario, String step) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("messages", messages);
        // 关闭 DeepSeek 思考模式（根治 400 bug）：
        // 官方文档(api-docs.deepseek.com/guides/thinking_mode)：思考模式默认开启,带 tools 时
        // 中间 assistant 的 reasoning_content 必须回传,否则 400 "reasoning_content must be passed back"。
        // 配置管理场景(导航/选择/表格操作)无需思维链推理,关闭后模型不返回 reasoning_content,
        // 从根上消除回传问题,同时降低 token 消耗、加速响应(官方推荐:简单任务用 disabled)。
        body.put("thinking", Map.of("type", "disabled"));

        // 动态工具发现：按 scenario+step 过滤工具集
        List<Map<String, Object>> tools;
        if (scenario != null && step != null) {
            tools = toolDiscoveryService.listAvailableTools(scenario, step);
            log.debug("动态工具发现: scenario={} step={} -> {} 个工具", scenario, step, tools.size());
        } else {
            // 兜底：暴露所有工具
            tools = FrontendTools.RAW_TOOLS_AS_MAPS;
            log.debug("未指定 scenario/step，使用全部工具: {} 个", tools.size());
        }
        body.put("tools", tools);
        body.put("tool_choice", "auto");
        return body;
    }

    private JsonNode callDeepSeekApi(Map<String, Object> requestBody) {
        String reqJson;
        try {
            reqJson = om.writeValueAsString(requestBody);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("请求体序列化失败", e);
        }
        log.debug("DeepSeek 请求体(前1500字符): {}", reqJson.substring(0, Math.min(1500, reqJson.length())));

        // ====== 改造 H1：错误三分类 + 指数退避重试 ======
        //   ①可重试: 429/5xx/网络超时 → 退避(1s×2^n + jitter,上限 maxRetries 次),尊重 Retry-After
        //   ②需干预: 401/400/上下文超限 → 不重试直接抛(上层走兜底/压缩)
        //   ③不可恢复: 403/配额 → 直接抛
        // 依据: pydantic-ai(tenacity + Retry-After);网络可靠性 99.5~99.9%,长会话必遇失败,按常态设计
        RuntimeException lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return doCallDeepSeekApi(reqJson);
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                boolean retryable = status == 429 || status >= 500;
                if (!retryable || attempt >= maxRetries) {
                    throw new RuntimeException("DeepSeek API 错误 HTTP " + status + ": "
                            + abbrev(e.getResponseBodyAsString(), 300), e);
                }
                long backoffMs = retryAfterMs(e).orElse(backoffWithJitter(attempt));
                log.warn("DeepSeek 调用失败(HTTP {}),第 {}/{} 次重试,退避 {}ms", status, attempt + 1, maxRetries, backoffMs);
                sleep(backoffMs);
                lastError = new RuntimeException("DeepSeek API 错误 HTTP " + status, e);
            } catch (ResourceAccessException e) {
                // 网络层失败(连接/读超时)
                if (attempt >= maxRetries) {
                    throw new RuntimeException("DeepSeek 网络调用失败(已重试 " + maxRetries + " 次): " + e.getMessage(), e);
                }
                long backoffMs = backoffWithJitter(attempt);
                log.warn("DeepSeek 网络异常,第 {}/{} 次重试,退避 {}ms: {}", attempt + 1, maxRetries, backoffMs, e.getMessage());
                sleep(backoffMs);
                lastError = new RuntimeException("DeepSeek 网络调用失败: " + e.getMessage(), e);
            }
        }
        throw lastError != null ? lastError : new RuntimeException("DeepSeek 调用失败(未知)");
    }

    /** 单次调用(不重试),从 callDeepSeekApi 拆出 */
    private JsonNode doCallDeepSeekApi(String reqJson) {
        String respStr = client()
                .post()
                .uri(chatPath)
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(reqJson)
                .retrieve()
                .body(String.class);

        if (respStr == null || respStr.isBlank()) {
            throw new RuntimeException("DeepSeek 返回空响应");
        }
        try {
            JsonNode root = om.readTree(respStr);
            JsonNode err = root.path("error");
            if (!err.isMissingNode() && !err.isNull() && err.size() > 0) {
                throw new RuntimeException("DeepSeek 业务错误: " + err.toString());
            }
            JsonNode choices = root.path("choices");
            if (choices.isMissingNode() || !choices.isArray() || choices.isEmpty()) {
                throw new RuntimeException("DeepSeek 响应缺少 choices 字段");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("DeepSeek 响应 JSON 解析失败: " +
                    respStr.substring(0, Math.min(300, respStr.length())), e);
        }
    }

    // ================================ 改造 H1 重试辅助 ================================

    /** 尊重服务端 Retry-After 头（秒），无则 empty */
    private java.util.Optional<Long> retryAfterMs(RestClientResponseException e) {
        try {
            String h = e.getHeaders() == null ? null : e.getHeaders().getFirst("Retry-After");
            if (h == null) return java.util.Optional.empty();
            return java.util.Optional.of(Math.max(0L, Long.parseLong(h.trim()) * 1000L));
        } catch (Exception ignored) {
            return java.util.Optional.empty();
        }
    }

    /** 指数退避 + jitter：1s, 2s, 4s... ±20% 抖动（防多实例同时重试打爆对端） */
    private long backoffWithJitter(int attempt) {
        long base = 1000L * (1L << Math.min(attempt, 5));
        double jitter = 0.8 + Math.random() * 0.4;
        return (long) (base * jitter);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private String abbrev(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...(截断)";
    }

    // ================================ System + 历史 + 用户消息（原生格式） ================================

    private List<Map<String, Object>> buildRawMessages(AiDTO.ChatReq req) {
        List<Map<String, Object>> list = new ArrayList<>();

        // Phase 4: system prompt 精简 —— 不再全量注入配置定义（token 浪费 + 上下文污染），
        // 改为告诉 AI "能做什么"，让其按需调 list_config_defs / get_config_def 查询。
        // 业界主流：MCP tools/list + Anthropic Skills "progressive disclosure"。
        StringBuilder sys = new StringBuilder(1024);
        sys.append("你是「AI配置助手」,服务于中文的配置管理系统。\n");
        sys.append("系统支持 4 种场景: EXPORT(导出配置)、IMPORT(导入配置)、ADD(新增配置)、MODIFY(修改配置)。\n");
        sys.append("每个场景是状态图,你只能调用当前步骤暴露的工具(由系统按 scenario+step 动态过滤)。\n");
        sys.append("EXPORT: SELECT_SCENARIO→SELECT_DEFS→QUERY_COND→RESULT。\n");
        sys.append("IMPORT/ADD/MODIFY: SELECT_SCENARIO→VIEW_DEFS→PRECHECK→REVIEW→PUBLISH。\n");

        sys.append("\n【工具调用协议 · 强制执行】\n");
        sys.append("1. 所有动作一律通过 tool_calls 字段指定工具,绝对不能在 content 里输出 JSON / 代码块 / 工具调用标记。\n");
        sys.append("2. 需要配置定义信息时调 list_config_defs 取摘要(id/code/name),再用 get_config_def(defId) 查字段详情,禁止凭空猜测字段名。\n");
        sys.append("3. 需要当前工作区状态时调 get_workspace_state。\n");
        sys.append("4. 破坏性/大批量改动用对应表格工具,系统会自动二次确认。\n");
        sys.append("5. 所有回复必须用中文,不要重复废话。工具结果以 tool 角色回灌,基于结果继续推理或给最终回复。\n");
        sys.append("6. 流程推进(关键): 当当前步骤的意图已满足时,立即调 navigate_step 推进到状态图下一步,不要停等用户确认,也不要反复调 get_workspace_state。例:用户说'新建导出/导出配置'即场景 EXPORT,调 navigate_step(scenario=EXPORT,step=SELECT_SCENARIO) 后场景已选定,应继续调 navigate_step(step=SELECT_DEFS) 进入选择配置项,而非停在 SELECT_SCENARIO。只有遇到需要用户输入(如选哪些配置项、设查询条件)时才停下给出引导。\n");
        sys.append("7. 收集用户输入(关键): 当需要用户提供具体参数(如导出文件名、查询条件值、选择项、配置项 code/name 等)时,调 collect_user_input 工具,用 fields 数组定义表单字段(支持 text/textarea/number/boolean/single_select/multi_select/button_group/date 八种控件,每字段含 key/label/type/required/options 等),系统会渲染表单让用户填写并自动回灌结果,你基于回灌的值继续推理。一次可收集多个字段(单轮表单)或单个字段(多轮问答),由你根据需要决定。不要用纯文本提问代替表单——凡需结构化输入一律用 collect_user_input。\n");

        // ====== 改造 E：prompt 行为准则（状态一致性三铁律）======
        sys.append("\n【状态一致性准则 · 强制执行】\n");
        sys.append("8. 执行任何写操作(table_*、run_flow 等)前必须调 get_workspace_state 确认最新状态,不得依赖对话历史中的数据快照——快照可能已被用户手动修改。\n");
        sys.append("9. 回答与当前流程无关的问题时不要重置或推进流程状态;用户表达继续意图(如'继续''接着做')时先调 get_workspace_state 现查当前步骤再行动。\n");
        sys.append("10. 收到「等待期间工作区已被用户手动修改」的系统提示时,必须先调 get_workspace_state 现查最新状态再决策,禁止沿用修改前的认知。\n");

        if (req.getWorkspaceState() != null && !req.getWorkspaceState().isEmpty()) {
            sys.append("\n[当前工作区状态]\n");
            try {
                sys.append(om.writeValueAsString(req.getWorkspaceState())).append("\n");
            } catch (JsonProcessingException ignored) {}
        }

        if (req.getTriggerEvent() != null) {
            sys.append("[触发事件]: ").append(req.getTriggerEvent()).append("\n");
        }
        if (Boolean.TRUE.equals(req.getAutoPrompt())) {
            sys.append("这是工作区操作触发的自动提示,请用 2-3 句中文介绍当前步骤,并使用工具给出下一步操作建议。\n");
        }

        list.add(Map.of("role", "system", "content", sys.toString()));

        // 加入历史消息
        List<AiChatMessage> history = listHistory(req.getTaskId(), req.getSessionId());
        int startIdx = Math.max(0, history.size() - 20);
        for (int i = startIdx; i < history.size(); i++) {
            AiChatMessage m = history.get(i);
            if (m.getContent() == null || m.getContent().isBlank()) continue;
            String content = m.getContent();
            if (content.length() > 3000) content = content.substring(0, 3000) + "...(截断)";
            if ("user".equals(m.getRole()) || "assistant".equals(m.getRole())) {
                list.add(Map.of("role", m.getRole(), "content", content));
            }
        }

        // 当前用户消息（去重：chat 入口已保存 user 消息到 DB，listHistory 会加载到它，
        // 若历史末尾已是相同 content 则不重复追加，避免 messages 出现两条相同 user 消息）
        String userContent = (req.getMessage() == null || req.getMessage().isBlank())
                ? "（请根据工作区当前状态给出自动提示和操作建议）"
                : req.getMessage();
        boolean alreadyAppended = false;
        if (req.getMessage() != null && !req.getMessage().isBlank() && !list.isEmpty()) {
            Map<String, Object> last = list.get(list.size() - 1);
            if ("user".equals(last.get("role"))
                    && userContent.equals(last.get("content"))) {
                alreadyAppended = true;
            }
        }
        if (!alreadyAppended) {
            list.add(Map.of("role", "user", "content", userContent));
        }

        return list;
    }

    // ================================ 降级兜底 + 消毒 ================================

    private String sanitizeAssistantContent(String text) {
        if (text == null) return "";
        return text.replaceAll("(?s)```\\s*(?i:json|javascript)?\\s*\\n?.*?```", "")
                .replaceAll("(?s)\\{\\s*\"type\"\\s*:.*?\\}(?:\\s*,\\s*\\{\\s*\"type\"\\s*:.*?\\})*", "")
                .replaceAll("(?s)\\[\\s*\\{\\s*\"type\"\\s*:.*?\\}\\s*\\]", "")
                .trim();
    }

    private List<AiDTO.AiAction> parseActionsFromReply(String reply) {
        if (reply == null) return Collections.emptyList();
        List<AiDTO.AiAction> list = new ArrayList<>();
        int idx = 0;
        while (true) {
            int s = reply.indexOf("```json", idx);
            if (s < 0) s = reply.indexOf("``` JSON", idx);
            if (s < 0) s = reply.indexOf("```", idx);
            if (s < 0) break;
            int fenceLen = reply.startsWith("```json", s) ? 7
                    : reply.startsWith("``` JSON", s) ? 8 : 3;
            int start = s + fenceLen;
            int end = reply.indexOf("```", start);
            if (end < 0) break;
            String jsonStr = reply.substring(start, end).trim();
            try {
                JsonNode node = om.readTree(jsonStr);
                if (node.isArray()) {
                    for (JsonNode e : node) {
                        AiDTO.AiAction a = om.treeToValue(e, AiDTO.AiAction.class);
                        if (a.getType() != null) list.add(a);
                    }
                } else if (node.isObject()) {
                    AiDTO.AiAction a = om.treeToValue(node, AiDTO.AiAction.class);
                    if (a.getType() != null) list.add(a);
                }
            } catch (Exception ex) {
                log.trace("解析兜底动作JSON失败(可忽略): {}", jsonStr.substring(0, Math.min(120, jsonStr.length())));
            }
            idx = end + 3;
        }
        return list;
    }

    private List<AiDTO.AiAction> buildFallbackActions(AiDTO.ChatReq req) {
        List<AiDTO.AiAction> list = new ArrayList<>();
        if (req.getTaskId() != null) {
            Optional<Task> t = taskService.getById(req.getTaskId());
            if (t.isPresent() && "SELECT_SCENARIO".equals(t.get().getCurrentStep())) {
                list.add(AiDTO.AiAction.builder()
                        .id("fa-scenario")
                        .type("navigate")
                        .title("选择导出场景")
                        .impact("切换场景为导出配置")
                        .payload(Map.of("scenario", "EXPORT", "step", "SELECT_DEFS"))
                        .needConfirm(false)
                        .build());
            }
        }
        return list;
    }
}
