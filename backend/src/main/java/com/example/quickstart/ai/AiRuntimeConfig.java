package com.example.quickstart.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;

/**
 * Spring AI 1.1.8 装配：
 * - OpenAiChatModel 由 starter 自动配置（DeepSeek OpenAI 兼容参数见 application.yml）
 * - ChatMemory：内存窗口记忆（会话=浏览器页签，不持久化，重启即清）
 * - ChatClient：默认挂记忆 Advisor；工具按轮次动态传入（渐进式披露）
 */
@Configuration
public class AiRuntimeConfig {

    @Bean
    public ChatMemory chatMemory(@Value("${app.ai.memory-window:30}") int window) {
        return MessageWindowChatMemory.builder()
                .maxMessages(window)
                .build();
    }

    @Bean
    public ChatClient chatClient(OpenAiChatModel model, ChatMemory memory) {
        return ChatClient.builder(model)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .build();
    }
}
