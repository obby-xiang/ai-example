package com.example.spike.config;

import com.example.spike.memory.MessageJsonCodec;
import com.example.spike.memory.RedisChatMemoryRepository;
import com.example.spike.tools.SpikeTools;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 装配：自定义 ChatMemoryRepository + 官方 MessageWindowChatMemory + 官方 MessageChatMemoryAdvisor。
 *
 * <p>
 * 官方 {@code ChatMemoryAutoConfiguration} 的两个 bean 都是 {@code @ConditionalOnMissingBean}，
 * 因此这里显式定义 {@link ChatMemoryRepository} 与 {@link ChatMemory} 后官方自动配置自动让位，
 * 不会产生重复 bean。
 */
@Configuration
public class SpikeConfig {

	@Bean
	public MessageJsonCodec messageJsonCodec(ObjectMapper objectMapper) {
		return new MessageJsonCodec(objectMapper);
	}

	@Bean
	public ChatMemoryRepository chatMemoryRepository(StringRedisTemplate redisTemplate, MessageJsonCodec codec,
			SpikeMemoryProperties properties) {
		return new RedisChatMemoryRepository(redisTemplate, codec, properties.getTtl());
	}

	@Bean
	public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository, SpikeMemoryProperties properties) {
		return MessageWindowChatMemory.builder()
			.chatMemoryRepository(chatMemoryRepository)
			.maxMessages(properties.getMaxMessages())
			.build();
	}

	@Bean
	public SpikeTools spikeTools() {
		return new SpikeTools();
	}

	@Bean
	public ToolCallbackProvider spikeToolCallbackProvider(SpikeTools spikeTools) {
		return MethodToolCallbackProvider.builder().toolObjects(spikeTools).build();
	}

	@Bean
	public ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
		return builder.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build()).build();
	}

}
