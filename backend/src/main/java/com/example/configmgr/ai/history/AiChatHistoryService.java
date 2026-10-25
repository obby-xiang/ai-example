package com.example.configmgr.ai.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.SessionPurgeGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 完整对话历史的存储与游标分页（{@code ai_chat_message}，V7 + V8）。
 *
 * <h2>与记忆窗口的分工</h2>
 * <p>
 * {@code ChatMemory}（Redis）只负责"给模型看最近 40 条"；本服务负责"给用户看全部"。
 * 两者写入时机可以相同，但生命周期不同：本表不设 TTL，删会话才物理删除。
 * 第二阶段已由 {@link AiChatHistoryRecorder} 接入用户/工具轮/最终 assistant/取消轮四个落定点，
 * 并由 {@code convergeMemory} 做幂等补齐。
 *
 * <h2>追加语义（并发 + 幂等）</h2>
 * <p>
 * {@code (session_id, run_id, message_id)} 唯一：同一落定点重复执行命中既有行即返回（首次写入胜）。
 * 新消息每次追加在一个<b>独立事务</b>（{@code REQUIRES_NEW}）里执行：
 * {@code seq = max(seq) + 1} → {@code INSERT} → commit。并发撞车时由
 * {@code uq_ai_chat_message_session_seq} 拒绝后一个写入者，服务捕获冲突并<b>重新读 max 再试</b>。
 * 因此：
 * <ul>
 * <li><b>不重复</b>：同一会话的 seq 由唯一约束兜底，同一消息由 run+messageId 唯一键兜底；</li>
 * <li><b>不跳号</b>：失败的事务不留下 seq，后续重试会复用该号（成功提交的追加会形成 1..N
 * 连续序列）；</li>
 * <li>唯一可能看到空洞的场景是"已提交的行后来被物理删除"（删除会话后重建从 1 开始），
 * 不是并发追加本身造成的。</li>
 * </ul>
 * <p>
 * 重试次数上限 {@value #MAX_APPEND_ATTEMPTS}；超过仍冲突视为持续竞争/非 seq 类数据错误，
 * 抛出 {@link IllegalStateException}（调用方必须当失败处理，不能静默丢历史）。
 *
 * <h2>游标分页</h2>
 * <ul>
 * <li>{@link #pageBefore(String, Long, int)}：向更早翻，返回<b>seq 倒序</b>（最新→最旧）；
 * {@code beforeSeq} 为 null = 从最新开始；{@code beforeSeq=0} = 空页；</li>
 * <li>{@link #pageAfter(String, Long, int)}：向更新翻，返回<b>seq 正序</b>（最旧→最新）；
 * {@code afterSeq} 为 null 或 0 = 从第一条开始；</li>
 * <li>两者都返回 {@code hasMore} 与 {@code nextCursor}（仅当 hasMore=true 时非 null，
 * 直接作为下一次同方向请求的游标；空页/末页为 null）。</li>
 * <li>{@code seq} 唯一且排序稳定，不使用 offset，不会因并发追加导致重复/漏页。</li>
 * </ul>
 *
 * <h2>消息字段来源</h2>
 * <p>
 * {@link #commandFor} 按现有 {@code MessageJsonCodec} 的字段名（{@code toolCalls} /
 * {@code toolResponses} / {@code metadata}）把 Spring AI {@link Message} 拆成列；
 * {@link AppendCommand} 也可直接接收原文 JSON（测试/导入路径），服务不做解析或重排，
 * 保证未知元数据逐字保真。
 */
@Service
public class AiChatHistoryService {

    private static final Logger log = LoggerFactory.getLogger(AiChatHistoryService.class);

    /** 默认页大小（前端一次翻页的条数）。 */
    public static final int DEFAULT_LIMIT = 50;

    /** 单页上限：防止客户端用超大 limit 拉全表。 */
    public static final int MAX_LIMIT = 200;

    /** seq 冲突重试上限（8 并发下通常 1~2 次即可；32 留足余量）。 */
    static final int MAX_APPEND_ATTEMPTS = 32;

    private static final Set<String> ROLES = Set.of("SYSTEM", "USER", "ASSISTANT", "TOOL");

    private final AiChatMessageRepository repository;

    private final ObjectMapper objectMapper;

    private final TransactionTemplate appendTransaction;

    /**
     * 会话销毁写回守卫（跨分支竞态）。生产由 Spring 注入；手工构造/单测为 null（保持既有兼容）。
     * <b>不做异常兜底</b>：守卫读取失败必须阻止写入并向上传播（fail-closed），不得当成未装守卫放行。
     */
    @Autowired(required = false)
    private SessionPurgeGuard purgeGuard;

    public AiChatHistoryService(AiChatMessageRepository repository, ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.appendTransaction = new TransactionTemplate(transactionManager);
        // 每次追加独立提交：调用方事务回滚不得把已成功的历史行一起回滚；
        // 唯一约束冲突后的重试也必须在新事务里做（旧事务已被标记 rollback-only）。
        this.appendTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 追加一条消息，返回落库后的视图。
     *
     * <p><b>幂等口径（第二阶段）</b>：{@code (sessionId, runId, messageId)} 唯一。同一落定点重复执行
     * （重复 converge / 重启后 resume / 取消后再次收尾）命中既有行即返回既有视图，不新增 seq、
     * 不改写首次内容（首次写入胜）。并发同键写入由唯一索引兜底：后到者捕获完整性冲突后回查。
     *
     * <p><b>销毁守卫（跨分支竞态）</b>：在<b>任何</b>查询/插入之前问一次
     * {@link SessionPurgeGuard#isArmed(String)}。命中 ⇒ 本次不插入、不返回历史行（返回 null），
     * 由调用方静默跳过；守卫读取异常<b>不捕获</b>，原样上抛并阻止写入（fail-closed，不把异常当未装守卫）。
     *
     * @return 落库/命中的历史行；守卫已 arm 时返回 {@code null}（销毁语义，静默跳过）
     * @throws IllegalArgumentException 必填字段/角色/长度不合法
     * @throws IllegalStateException    seq 冲突重试耗尽、数据库返回非预期完整性错误，或守卫读取失败
     */
    public MessageView append(AppendCommand command) {
        AppendCommand cmd = validate(command);
        if (this.purgeGuard != null && this.purgeGuard.isArmed(cmd.sessionId())) {
            // 销毁语义：不查既有行、不分配 seq、不插入；调用方（recorder/收敛）按跳过处理。
            log.debug("会话正在销毁，跳过完整历史写入 sessionId={} runId={} messageId={}", cmd.sessionId(),
                    cmd.runId(), cmd.messageId());
            return null;
        }
        Optional<MessageView> existing = findExisting(cmd);
        if (existing.isPresent()) {
            return existing.get();
        }
        for (int attempt = 1; attempt <= MAX_APPEND_ATTEMPTS; attempt++) {
            try {
                MessageView saved = this.appendTransaction.execute(status -> insertNext(cmd));
                return Objects.requireNonNull(saved, "追加事务未返回结果");
            }
            catch (DataIntegrityViolationException | ConcurrencyFailureException | QueryTimeoutException ex) {
                // 先判幂等键：另一线程/另一次 converge 已写入同一 (run, messageId)。
                Optional<MessageView> raced = findExisting(cmd);
                if (raced.isPresent()) {
                    return raced.get();
                }
                if (attempt == MAX_APPEND_ATTEMPTS) {
                    throw new IllegalStateException("追加完整历史失败：seq 冲突重试 " + MAX_APPEND_ATTEMPTS
                            + " 次仍未成功 sessionId=" + cmd.sessionId(), ex);
                }
                // 唯一约束/锁竞争：新事务重读 max(seq) 再试，不吞历史、不重复落库
                log.debug("完整历史追加冲突，重试 sessionId={} attempt={}/{}：{}", cmd.sessionId(), attempt,
                        MAX_APPEND_ATTEMPTS, ex.getMessage());
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /**
     * 从 Spring AI {@link Message} 构造追加命令：字段映射与 {@code MessageJsonCodec} 对齐。
     *
     * <p>
     * {@code messageId} 必须由调用方按既有身份生成（assistant 用 {@code assistant-<runId>}、
     * tool 用 {@code tool-<toolCallId>}；同一 run 内多条 assistant 消息需带上轮次区分）。
     * {@code stopped=true} 会同时写入列与 metadata 的 {@code stopped} 键。
     */
    public AppendCommand commandFor(String sessionId, String runId, String messageId, Message message,
            boolean stopped) {
        Objects.requireNonNull(message, "message");
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (message.getMetadata() != null) {
            metadata.putAll(message.getMetadata());
        }
        boolean stoppedFlag = stopped || Boolean.TRUE.equals(metadata.get("stopped"));
        if (stoppedFlag) {
            metadata.put("stopped", true);
        }
        String toolCallsJson = message instanceof AssistantMessage assistant && assistant.hasToolCalls()
                ? writeToolCallsJson(assistant) : null;
        String toolResponsesJson = message instanceof ToolResponseMessage toolResponse
                ? writeToolResponsesJson(toolResponse) : null;
        return new AppendCommand(sessionId, runId, messageId, message.getMessageType().name(), message.getText(),
                toolCallsJson, toolResponsesJson, writeMetadataJson(metadata), stoppedFlag);
    }

    /** 向更早翻页（倒序）；{@code beforeSeq=null} 从最新开始。 */
    public ChatPage pageBefore(String sessionId, Long beforeSeq, int limit) {
        String sid = requireId(sessionId, "sessionId", 128);
        int size = requireLimit(limit);
        Long cursor = requireCursor(beforeSeq, "beforeSeq");
        if (cursor != null && cursor == 0L) {
            return ChatPage.empty();
        }
        PageRequest request = PageRequest.of(0, size + 1);
        List<AiChatMessage> rows = cursor == null
                ? this.repository.findBySessionIdOrderBySeqDesc(sid, request)
                : this.repository.findBySessionIdAndSeqLessThanOrderBySeqDesc(sid, cursor, request);
        return toPage(rows, size);
    }

    /** {@link #pageBefore(String, Long, int)} 的默认页大小重载。 */
    public ChatPage pageBefore(String sessionId, Long beforeSeq) {
        return pageBefore(sessionId, beforeSeq, DEFAULT_LIMIT);
    }

    /** 向更新翻页（正序）；{@code afterSeq=null/0} 从第一条开始。 */
    public ChatPage pageAfter(String sessionId, Long afterSeq, int limit) {
        String sid = requireId(sessionId, "sessionId", 128);
        int size = requireLimit(limit);
        Long cursor = requireCursor(afterSeq, "afterSeq");
        PageRequest request = PageRequest.of(0, size + 1);
        List<AiChatMessage> rows = cursor == null || cursor == 0L
                ? this.repository.findBySessionIdOrderBySeqAsc(sid, request)
                : this.repository.findBySessionIdAndSeqGreaterThanOrderBySeqAsc(sid, cursor, request);
        return toPage(rows, size);
    }

    /** {@link #pageAfter(String, Long, int)} 的默认页大小重载。 */
    public ChatPage pageAfter(String sessionId, Long afterSeq) {
        return pageAfter(sessionId, afterSeq, DEFAULT_LIMIT);
    }

    /** 物理删除该会话的全部历史；返回删除行数（不存在 = 0，幂等）。 */
    @Transactional
    public int deleteSession(String sessionId) {
        String sid = requireId(sessionId, "sessionId", 128);
        return this.repository.deleteBySessionId(sid);
    }

    /** 该会话的历史总条数（诊断/测试用）。 */
    public long count(String sessionId) {
        return this.repository.countBySessionId(requireId(sessionId, "sessionId", 128));
    }

    /**
     * 把一条持久化行还原成 Spring AI {@link Message}（供完整历史接口复用现有 {@code toHistoryItem}
     * 的文本/工具信封/视图口径）。
     *
     * <p>还原走 {@link MessageJsonCodec#deserialize}，构造的 JSON 与 {@code MessageJsonCodec#toNode}
     * 同形（{@code type/content/metadata/toolCalls/toolResponses}）；不做任何文本裁剪或信封解包，
     * 解包仍由调用方（现有 history 通道）负责。
     */
    public Message toMessage(MessageView view) {
        Objects.requireNonNull(view, "view");
        ObjectNode node = this.objectMapper.createObjectNode();
        node.put("type", view.role());
        node.put("content", view.text());
        node.set("metadata", readJson(view.metadataJson(), "metadata"));
        if (view.toolCallsJson() != null) {
            node.set("toolCalls", readJson(view.toolCallsJson(), "toolCalls"));
        }
        if (view.toolResponsesJson() != null) {
            node.set("toolResponses", readJson(view.toolResponsesJson(), "toolResponses"));
        }
        return new MessageJsonCodec(this.objectMapper).deserialize(node.toString());
    }

    private Optional<MessageView> findExisting(AppendCommand cmd) {
        return this.repository
            .findFirstBySessionIdAndRunIdAndMessageId(cmd.sessionId(), cmd.runId(), cmd.messageId())
            .map(AiChatHistoryService::toView);
    }

    private JsonNode readJson(String json, String field) {
        if (json == null || json.isBlank()) {
            return this.objectMapper.createObjectNode();
        }
        try {
            return this.objectMapper.readTree(json);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("完整历史字段不是合法 JSON field=" + field, ex);
        }
    }

    private MessageView insertNext(AppendCommand cmd) {
        long nextSeq = this.repository.maxSeq(cmd.sessionId()) + 1;
        AiChatMessage row = new AiChatMessage();
        row.setSessionId(cmd.sessionId());
        row.setRunId(cmd.runId());
        row.setSeq(nextSeq);
        row.setMessageId(cmd.messageId());
        row.setRole(cmd.role());
        row.setText(cmd.text());
        row.setToolCallsJson(cmd.toolCallsJson());
        row.setToolResponsesJson(cmd.toolResponsesJson());
        row.setMetadataJson(cmd.metadataJson());
        row.setStopped(cmd.stopped());
        // flush 让唯一约束冲突在本事务内暴露，便于外层按 attempt 重试
        return toView(this.repository.saveAndFlush(row));
    }

    private ChatPage toPage(List<AiChatMessage> rows, int size) {
        boolean hasMore = rows.size() > size;
        List<AiChatMessage> page = hasMore ? new ArrayList<>(rows.subList(0, size)) : new ArrayList<>(rows);
        Long nextCursor = hasMore && !page.isEmpty() ? page.get(page.size() - 1).getSeq() : null;
        List<MessageView> items = page.stream().map(AiChatHistoryService::toView).toList();
        return new ChatPage(items, hasMore, nextCursor);
    }

    private static MessageView toView(AiChatMessage row) {
        return new MessageView(row.getId(), row.getSessionId(), row.getRunId(), row.getSeq(), row.getMessageId(),
                row.getRole(), row.getText(), row.getToolCallsJson(), row.getToolResponsesJson(),
                row.getMetadataJson(), row.isStopped(), row.getCreatedAt());
    }

    /** 与 {@code MessageJsonCodec#toNode} 的 {@code toolCalls[]} 字段/顺序逐字一致。 */
    private String writeToolCallsJson(AssistantMessage assistant) {
        ArrayNode toolCalls = this.objectMapper.createArrayNode();
        for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
            ObjectNode item = toolCalls.addObject();
            item.put("id", toolCall.id());
            item.put("type", toolCall.type());
            item.put("name", toolCall.name());
            item.put("arguments", toolCall.arguments());
        }
        return writeJsonNode(toolCalls, "toolCalls");
    }

    /** 与 {@code MessageJsonCodec#toNode} 的 {@code toolResponses[]} 字段/顺序逐字一致。 */
    private String writeToolResponsesJson(ToolResponseMessage toolResponse) {
        ArrayNode responses = this.objectMapper.createArrayNode();
        for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
            ObjectNode item = responses.addObject();
            item.put("id", response.id());
            item.put("name", response.name());
            item.put("responseData", response.responseData());
        }
        return writeJsonNode(responses, "toolResponses");
    }

    /** metadata 仍按既有 codec 的 {@code valueToTree} 口径保存（含未知键）。 */
    private String writeMetadataJson(Map<String, Object> metadata) {
        return writeJsonNode(this.objectMapper.valueToTree(metadata), "metadata");
    }

    private String writeJsonNode(JsonNode node, String field) {
        try {
            return this.objectMapper.writeValueAsString(node);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("完整历史字段序列化失败 field=" + field, ex);
        }
    }

    private static AppendCommand validate(AppendCommand command) {
        Objects.requireNonNull(command, "command");
        String sessionId = requireId(command.sessionId(), "sessionId", 128);
        String runId = requireId(command.runId(), "runId", 64);
        String messageId = requireId(command.messageId(), "messageId", 128);
        String role = requireId(command.role(), "role", 16).trim().toUpperCase(Locale.ROOT);
        if (!ROLES.contains(role)) {
            throw new IllegalArgumentException("role 必须是 " + ROLES + " 之一（实际 = " + command.role() + "）");
        }
        return new AppendCommand(sessionId, runId, messageId, role, command.text(), command.toolCallsJson(),
                command.toolResponsesJson(), command.metadataJson(), command.stopped());
    }

    private static String requireId(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不得为空白");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " 长度不得超过 " + maxLength + "（实际 = " + value.length() + "）");
        }
        return value;
    }

    private static int requireLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit 必须在 1.." + MAX_LIMIT + " 之间（实际 = " + limit + "）");
        }
        return limit;
    }

    private static Long requireCursor(Long cursor, String field) {
        if (cursor != null && cursor < 0L) {
            throw new IllegalArgumentException(field + " 不得为负数（实际 = " + cursor + "）");
        }
        return cursor;
    }

    /**
     * 追加命令：字段即 {@code ai_chat_message} 的列。
     *
     * <p>{@code toolCallsJson}/{@code toolResponsesJson}/{@code metadataJson} 是<b>原文 JSON</b>，
     * 服务不解析、不重排；{@code text} 可为 null/空串（工具轮的 assistant 正文通常为空）。
     */
    public record AppendCommand(String sessionId, String runId, String messageId, String role, String text,
            String toolCallsJson, String toolResponsesJson, String metadataJson, boolean stopped) {
    }

    /** 一行的对外视图（保留原文 JSON，便于历史接口按现有 {@code /history} 形状组装）。 */
    public record MessageView(Long id, String sessionId, String runId, long seq, String messageId, String role,
            String text, String toolCallsJson, String toolResponsesJson, String metadataJson, boolean stopped,
            LocalDateTime createdAt) {
    }

    /** 游标页：{@code items} 已按请求方向排序；{@code nextCursor} 仅在 {@code hasMore=true} 时非 null。 */
    public record ChatPage(List<MessageView> items, boolean hasMore, Long nextCursor) {

        static ChatPage empty() {
            return new ChatPage(List.of(), false, null);
        }
    }
}
