package com.example.spike.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 全部走官方能力：官方 ChatClient / 官方 MessageWindowChatMemory / 官方 ToolCallingManager。
 * 本 spike 不实现任何模型协议、不写循环、不生成 Schema。
 */
@Configuration
public class SpikeConfig {

    /** 官方记忆窗口（内存实现；跨实例共享由 SP-01c 负责，本卡不重复验证）。 */
    @Bean
    public ChatMemory chatMemory(SpikeProperties props) {
        return MessageWindowChatMemory.builder()
                .maxMessages(props.getChatMemory().getMaxMessages())
                .build();
    }

    @Bean
    public ChatClient chatClient(ChatModel chatModel, ChatMemory chatMemory) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /**
     * 无 advisor 的 ChatClient（仅用于 V-d1 的对照实验：把"重订阅失败"归因到 advisor 链）。
     * 参考实现用了 memory advisor，本对照刻意去掉它以隔离变量。
     */
    @Bean(name = "bareChatClient")
    public ChatClient bareChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    /**
     * 官方 ToolCallingManager 由 ToolCallingAutoConfiguration 提供（@ConditionalOnMissingBean），
     * 这里刻意不再声明——自己声明会与官方自动配置同名成环。
     */
    @Bean(name = "spikeExecutor")
    public TaskExecutor spikeExecutor(SpikeProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getExecutor().getThreads());
        executor.setMaxPoolSize(props.getExecutor().getThreads());
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("spike-run-");
        executor.initialize();
        return executor;
    }
}
