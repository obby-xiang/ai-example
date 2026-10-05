package com.example.configadmin.ai;

import com.example.configadmin.common.ApiException;
import com.example.configadmin.entity.AiSessionRecord;
import com.example.configadmin.repository.AiSessionRecordRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.api.OpenAiApi;
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
 * - 刷新不丢：前端经 /api/ai/history 恢复；后端重启后由 H2 恢复，历史依然保留；
 * - 空闲清理：内存与 H2 同步清理（超过 TTL 未访问）。
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
        // 内存未命中 → 从 H2 恢复（后端重启场景）
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
        AiSession s = sessions.get(sessionId);
        if (s == null) {
            s = recordRepo.findById(sessionId).map(this::fromRecord).orElse(null);
            if (s != null) {
                sessions.put(sessionId, s);
            }
        }
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

    // ---------- 消息 ↔ JSON 转换（OpenAI 协议消息的可持久化表示） ----------

    private List<Map<String, Object>> toMaps(List<OpenAiApi.ChatCompletionMessage> msgs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OpenAiApi.ChatCompletionMessage m : msgs) {
            if (m.role() == OpenAiApi.ChatCompletionMessage.Role.SYSTEM) {
                continue; // 系统提示每轮重建，不入库
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("role", m.role().name());
            map.put("content", m.content() == null ? null : String.valueOf(m.content()));
            if (m.name() != null) map.put("name", m.name());
            if (m.toolCallId() != null) map.put("toolCallId", m.toolCallId());
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                List<Map<String, Object>> calls = new ArrayList<>();
                for (OpenAiApi.ChatCompletionMessage.ToolCall tc : m.toolCalls()) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("id", tc.id());
                    c.put("name", tc.function().name());
                    c.put("arguments", tc.function().arguments());
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
            for (Map<String, Object> m : maps) {
                s.getMessages().add(fromMap(m));
            }
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

    private OpenAiApi.ChatCompletionMessage fromMap(Map<String, Object> m) {
        String role = String.valueOf(m.getOrDefault("role", "USER"));
        String content = m.get("content") == null ? null : String.valueOf(m.get("content"));
        OpenAiApi.ChatCompletionMessage.Role r = OpenAiApi.ChatCompletionMessage.Role.valueOf(role);
        if (r == OpenAiApi.ChatCompletionMessage.Role.TOOL) {
            return new OpenAiApi.ChatCompletionMessage(content, r,
                    m.get("name") == null ? null : String.valueOf(m.get("name")),
                    m.get("toolCallId") == null ? null : String.valueOf(m.get("toolCallId")),
                    null, null, null, null, null);
        }
        Object rawCalls = m.get("toolCalls");
        if (r == OpenAiApi.ChatCompletionMessage.Role.ASSISTANT && rawCalls instanceof List<?> list && !list.isEmpty()) {
            List<OpenAiApi.ChatCompletionMessage.ToolCall> calls = new ArrayList<>();
            for (Object o : list) {
                @SuppressWarnings("unchecked")
                Map<String, Object> c = (Map<String, Object>) o;
                calls.add(new OpenAiApi.ChatCompletionMessage.ToolCall(
                        c.get("id") == null ? null : String.valueOf(c.get("id")), "function",
                        new OpenAiApi.ChatCompletionMessage.ChatCompletionFunction(
                                String.valueOf(c.get("name")),
                                c.get("arguments") == null ? "{}" : String.valueOf(c.get("arguments")))));
            }
            return new OpenAiApi.ChatCompletionMessage(content, r, null, null, calls, null, null, null, null);
        }
        return new OpenAiApi.ChatCompletionMessage(content, r);
    }
}
