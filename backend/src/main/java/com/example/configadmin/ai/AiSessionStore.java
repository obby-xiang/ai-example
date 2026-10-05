package com.example.configadmin.ai;

import com.example.configadmin.common.ApiException;
import com.example.configadmin.entity.AiSessionRecord;
import com.example.configadmin.repository.AiSessionRecordRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话存储：内存 Map（热）+ H2 持久化（ai_session 表，冷）。
 * - 页签唯一：sessionId 由前端 sessionStorage 生成，新页签=新会话；
 * - 刷新不丢：前端经 /api/ai/history 恢复；后端重启后由 H2 恢复；
 * - 消息以 Spring AI Message 表示，持久化为平台无关 JSON（手动 Map 映射，便于跨版本稳定）。
 */
@Component
public class AiSessionStore {

    private static final Logger log = LoggerFactory.getLogger(AiSessionStore.class);

    private final Map<String, AiSession> sessions = new ConcurrentHashMap<>();
    private final AiSessionRecordRepository recordRepo;
    private final ObjectMapper mapper;

    @Value("${app.ai.session-ttl-minutes:30}")
    private long ttlMinutes;

    public AiSessionStore(AiSessionRecordRepository recordRepo, ObjectMapper mapper) {
        this.recordRepo = recordRepo;
        this.mapper = mapper;
    }

    public AiSession getOrCreate(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 64) {
            throw ApiException.badRequest("会话标识无效");
        }
        AiSession s = sessions.get(sessionId);
        if (s != null) {
            s.touch();
            return s;
        }
        AiSession restored = recordRepo.findById(sessionId).map(this::fromRecord).orElse(null);
        if (restored != null) {
            sessions.put(sessionId, restored);
            log.info("会话从持久化恢复：{}（消息 {} 条）", sessionId, restored.getMessages().size());
            return restored;
        }
        AiSession created = new AiSession(sessionId);
        sessions.put(sessionId, created);
        return created;
    }

    public AiSession get(String sessionId) {
        AiSession s = getQuiet(sessionId);
        if (s == null) {
            throw ApiException.notFound("会话不存在或已过期：" + sessionId);
        }
        return s;
    }

    /** 静默读取（无则返回 null）：供刷新页面恢复历史使用。 */
    public AiSession getQuiet(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        AiSession s = sessions.get(sessionId);
        if (s == null) {
            s = recordRepo.findById(sessionId).map(this::fromRecord).orElse(null);
            if (s != null) {
                sessions.put(sessionId, s);
            }
        }
        return s;
    }

    /** 持久化会话（消息+上下文快照）。 */
    public void save(AiSession s) {
        try {
            AiSessionRecord rec = recordRepo.findById(s.getId()).orElseGet(() -> {
                AiSessionRecord r = new AiSessionRecord();
                r.setSessionId(s.getId());
                return r;
            });
            rec.setMessagesJson(mapper.writeValueAsString(toMaps(s.getMessages())));
            rec.setContextJson(mapper.writeValueAsString(s.getContext()));
            rec.setLastAccess(LocalDateTime.now());
            recordRepo.save(rec);
        } catch (Exception e) {
            log.warn("会话持久化失败 session={}", s.getId(), e);
        }
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);
        try {
            recordRepo.deleteById(sessionId);
        } catch (Exception ignored) {
        }
    }

    public int size() {
        return sessions.size();
    }

    /** 每分钟清理空闲超时会话（内存 + H2）。 */
    @Scheduled(fixedDelay = 60_000)
    public void cleanup() {
        LocalDateTime threshold = LocalDateTime.now().minus(Duration.ofMinutes(ttlMinutes));
        int before = sessions.size();
        sessions.entrySet().removeIf(e -> e.getValue().getLastAccess().isBefore(threshold));
        int removed = before - sessions.size();
        try {
            recordRepo.deleteByLastAccessBefore(threshold);
        } catch (Exception e) {
            log.warn("会话持久化清理失败", e);
        }
        if (removed > 0) {
            log.info("清理空闲 AI 会话 {} 个（剩余 {}）", removed, sessions.size());
        }
    }

    // ---------- 消息 ↔ JSON 转换（Spring AI Message 的平台无关表示） ----------

    private List<Map<String, Object>> toMaps(List<Message> msgs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Message m : msgs) {
            if (m.getMessageType() == MessageType.SYSTEM) {
                continue; // 系统提示每轮重建，不入库
            }
            if (m instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("role", "tool");
                    map.put("content", tr.responseData());
                    map.put("toolCallId", tr.id());
                    map.put("name", tr.name());
                    out.add(map);
                }
                continue;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("role", m.getMessageType().name().toLowerCase());
            map.put("content", m.getText());
            if (m instanceof AssistantMessage am && am.hasToolCalls()) {
                List<Map<String, Object>> calls = new ArrayList<>();
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("id", tc.id());
                    c.put("name", tc.name());
                    c.put("arguments", tc.arguments());
                    calls.add(c);
                }
                map.put("toolCalls", calls);
            }
            out.add(map);
        }
        return out;
    }

    private AiSession fromRecord(AiSessionRecord rec) {
        AiSession s = new AiSession(rec.getSessionId());
        try {
            List<Map<String, Object>> maps = mapper.readValue(rec.getMessagesJson(),
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            List<Message> msgs = new ArrayList<>();
            // 连续 tool 记录合并为一条 ToolResponseMessage（OpenAI 协议要求）
            List<ToolResponseMessage.ToolResponse> pendingResponses = new ArrayList<>();
            for (Map<String, Object> m : maps) {
                String role = String.valueOf(m.getOrDefault("role", "user"));
                if ("tool".equals(role)) {
                    pendingResponses.add(new ToolResponseMessage.ToolResponse(
                            m.get("toolCallId") == null ? null : String.valueOf(m.get("toolCallId")),
                            m.get("name") == null ? null : String.valueOf(m.get("name")),
                            m.get("content") == null ? null : String.valueOf(m.get("content"))));
                    continue;
                }
                if (!pendingResponses.isEmpty()) {
                    msgs.add(ToolResponseMessage.builder().responses(List.copyOf(pendingResponses)).build());
                    pendingResponses.clear();
                }
                msgs.add(fromMap(m));
            }
            if (!pendingResponses.isEmpty()) {
                msgs.add(ToolResponseMessage.builder().responses(List.copyOf(pendingResponses)).build());
            }
            s.getMessages().addAll(msgs);
        } catch (Exception e) {
            log.warn("会话消息反序列化失败 session={}", rec.getSessionId(), e);
        }
        try {
            Map<String, Object> ctx = mapper.readValue(rec.getContextJson(),
                    new TypeReference<Map<String, Object>>() {
                    });
            s.setContext(ctx);
        } catch (Exception ignored) {
        }
        return s;
    }

    private Message fromMap(Map<String, Object> m) {
        String role = String.valueOf(m.getOrDefault("role", "user"));
        String content = m.get("content") == null ? null : String.valueOf(m.get("content"));
        if ("assistant".equals(role)) {
            Object rawCalls = m.get("toolCalls");
            if (rawCalls instanceof List<?> list && !list.isEmpty()) {
                List<AssistantMessage.ToolCall> calls = new ArrayList<>();
                for (Object o : list) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> c = (Map<String, Object>) o;
                    calls.add(new AssistantMessage.ToolCall(
                            c.get("id") == null ? null : String.valueOf(c.get("id")),
                            "function",
                            String.valueOf(c.get("name")),
                            c.get("arguments") == null ? "{}" : String.valueOf(c.get("arguments"))));
                }
                return AssistantMessage.builder().content(content).toolCalls(calls).build();
            }
            return content == null || content.isEmpty() ? new AssistantMessage("") : new AssistantMessage(content);
        }
        return new UserMessage(content == null ? "" : content);
    }
}
