package com.example.configadmin.ai;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 会话记忆适配：把 Spring AI 官方 ChatMemory 接口（MessageChatMemoryAdvisor 使用）
 * 桥接到按页签隔离的 AiSession 存储（H2 持久化）。
 * conversationId = sessionId（浏览器页签）。
 */
@Component
public class AiSessionChatMemory implements ChatMemory {

    private final AiSessionStore store;

    public AiSessionChatMemory(AiSessionStore store) {
        this.store = store;
    }

    @Override
    public void add(String conversationId, Message message) {
        AiSession s = store.getQuiet(conversationId);
        if (s == null) {
            return;
        }
        s.getMessages().add(message);
        store.save(s);
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        AiSession s = store.getQuiet(conversationId);
        if (s == null) {
            return;
        }
        s.getMessages().addAll(messages);
        store.save(s);
    }

    @Override
    public List<Message> get(String conversationId) {
        AiSession s = store.getQuiet(conversationId);
        return s == null ? List.of() : List.copyOf(s.getMessages());
    }

    @Override
    public void clear(String conversationId) {
        store.remove(conversationId);
    }
}
