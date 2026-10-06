package com.example.configmgr.ai.config;

import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.memory.RedisChatMemoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.ReasoningAwareOpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.StringUtils;

/**
 * AI Runtime 装配（S4.2 §2：ChatModel/ChatClient 组装 + 记忆挂载）。
 *
 * <h2>官方扩展点优先（DC-05）</h2>
 * <ul>
 * <li>模型：官方 {@link OpenAiChatModel}，仅经构造器注入补丁子类
 * {@code ReasoningAwareOpenAiChatModel}（ADR-4）与官方 {@link ToolCallingManager}，
 * 对话循环/工具执行全在官方实现内；</li>
 * <li>记忆：官方 {@link MessageWindowChatMemory}（窗口裁剪）+ 官方
 * {@link MessageChatMemoryAdvisor}（读写时机），存储层用官方 SPI
 * {@link ChatMemoryRepository} 的自实现 {@link RedisChatMemoryRepository}；</li>
 * <li>全配置化（DC-01）：base-url / api-key / model / 路径全部来自
 * {@code spring.ai.openai.*}，密钥只经环境变量注入。</li>
 * </ul>
 *
 * <h2>Q2：空 key 必须能启动</h2>
 * 模型侧 bean 全部条件化在 {@link AiKeyPresentCondition} 之下（无 key → 一个都不建），
 * {@link AiAvailability} 恒在，AI 端点据此惰性返回 503 {@code AI_UNAVAILABLE}。
 * 相应地 {@code spring.ai.model.*=none} + {@code spring.ai.chat.client.enabled=false}
 * 关掉官方自动配置：OpenAI 自动配置在 key 为空时会 Assert 失败并中断启动。
 *
 * <h2>RetryTemplate</h2>
 * ADR-6：重试策略只在非流式路径生效。这里沿用官方自动配置的 {@link RetryTemplate}
 * （缺省时取官方默认构造），流式路径不经重试，双超时/三条件重试由后续棒次的
 * ResilientChatService 承担。
 */
@Configuration
@EnableConfigurationProperties({ AiProperties.class, OpenAiConnectionProperties.class, OpenAiChatProperties.class })
public class AiModelConfig {

	// ── 记忆（M2）：存储层 → 官方窗口记忆 → 官方记忆 Advisor ──────────────────────

	@Bean
	@ConditionalOnMissingBean
	public MessageJsonCodec messageJsonCodec(ObjectMapper objectMapper) {
		return new MessageJsonCodec(objectMapper);
	}

	/**
	 * 官方 {@code ChatMemoryAutoConfiguration} 的同类 bean 都是 {@code @ConditionalOnMissingBean}，
	 * 这里显式定义后官方自动配置让位，不会产生重复 bean。
	 */
	@Bean
	@ConditionalOnMissingBean
	public ChatMemoryRepository chatMemoryRepository(StringRedisTemplate redisTemplate, MessageJsonCodec codec,
			AiProperties properties) {
		return new RedisChatMemoryRepository(redisTemplate, codec, properties.getSessionTtl());
	}

	@Bean
	@ConditionalOnMissingBean
	public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository, AiProperties properties) {
		return MessageWindowChatMemory.builder()
			.chatMemoryRepository(chatMemoryRepository)
			.maxMessages(properties.getMemory().getMaxMessages())
			.build();
	}

	@Bean
	@ConditionalOnMissingBean
	public MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
		return MessageChatMemoryAdvisor.builder(chatMemory).build();
	}

	// ── 可用性（Q2）：无 key 也存在的判空门 ────────────────────────────────────

	@Bean
	public AiAvailability aiAvailability(Environment environment) {
		return new AiAvailability(environment);
	}

	// ── 模型侧（仅在有 key 时装配）─────────────────────────────────────────────

	@Configuration(proxyBeanMethods = false)
	@Conditional(AiKeyPresentCondition.class)
	static class OpenAiModelConfiguration {

		/**
		 * 与官方 {@code OpenAIAutoConfigurationUtil.resolveConnectionProperties} 同一套回落规则
		 * （chat 级优先、连接级兜底），差别只在这里不做非空断言——空 key 时根本不进本配置类。
		 */
		@Bean
		public OpenAiApi openAiApi(OpenAiConnectionProperties connectionProperties,
				OpenAiChatProperties chatProperties) {
			String baseUrl = StringUtils.hasText(chatProperties.getBaseUrl()) ? chatProperties.getBaseUrl()
					: connectionProperties.getBaseUrl();
			String apiKey = StringUtils.hasText(chatProperties.getApiKey()) ? chatProperties.getApiKey()
					: connectionProperties.getApiKey();
			return OpenAiApi.builder()
				.baseUrl(baseUrl)
				.apiKey(apiKey)
				.completionsPath(chatProperties.getCompletionsPath())
				.build();
		}

		@Bean
		public OpenAiChatModel chatModel(OpenAiApi openAiApi, OpenAiChatProperties chatProperties,
				ObjectProvider<ToolCallingManager> toolCallingManager, ObjectProvider<RetryTemplate> retryTemplate,
				ObjectProvider<ObservationRegistry> observationRegistry) {
			OpenAiChatOptions options = chatProperties.getOptions();
			// 工具执行留在官方循环内（internalToolExecutionEnabled 取官方默认 true）：
			// 补丁类回放 assistant(tool_calls) 的 reasoning_content，避免第二轮 400（ADR-4）。
			return new ReasoningAwareOpenAiChatModel(openAiApi, options,
					toolCallingManager.getIfAvailable(() -> ToolCallingManager.builder().build()),
					retryTemplate.getIfAvailable(RetryTemplate::new),
					observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP));
		}

		@Bean
		@ConditionalOnMissingBean
		public ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
			return ChatClient.builder(chatModel);
		}

		/** 记忆 advisor 挂成默认 advisor；每轮由 {@code ChatMemory.CONVERSATION_ID} 参数指定 sessionId。 */
		@Bean
		public ChatClient chatClient(ChatClient.Builder chatClientBuilder, MessageChatMemoryAdvisor memoryAdvisor) {
			return chatClientBuilder.defaultAdvisors(memoryAdvisor).build();
		}

	}

}
