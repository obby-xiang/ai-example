package com.example.quickstart.config;

import io.micrometer.observation.ObservationRegistry;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.ReasoningAwareOpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.retry.support.RetryTemplate;

/**
 * OpenAI 兼容协议 AI 配置（D1）：以 @Primary OpenAiChatModel 替换自动配置
 * （OpenAiChatAutoConfiguration 为 @ConditionalOnMissingBean，自动退让），
 * 实际返回补丁子类 ReasoningAwareOpenAiChatModel（回放 reasoning_content）。
 * openAiApi Bean 由自动配置直接提供（base-url/api-key/completions-path 走 yml）。
 */
@Configuration
public class OpenAiConfig {

	@Bean
	@Primary
	public OpenAiChatModel openAiChatModel(OpenAiApi openAiApi, OpenAiChatProperties chatProperties,
			ToolCallingManager toolCallingManager, RetryTemplate retryTemplate,
			ObjectProvider<ObservationRegistry> observationRegistry,
			ObjectProvider<ChatModelObservationConvention> observationConvention) {
		OpenAiChatModel chatModel = new ReasoningAwareOpenAiChatModel(openAiApi, chatProperties.getOptions(),
				toolCallingManager, retryTemplate, observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP));
		observationConvention.ifAvailable(chatModel::setObservationConvention);
		return chatModel;
	}

	/**
	 * AI 会话模型消息记忆（D9）：conversationId = sessionId；窗口 80 条。
	 * system prompt 不入 memory（随 context 变化，每轮请求时临时 prepend）。
	 */
	@Bean
	public ChatMemory aiChatMemory() {
		return MessageWindowChatMemory.builder()
			.chatMemoryRepository(new InMemoryChatMemoryRepository())
			.maxMessages(80)
			.build();
	}

}
