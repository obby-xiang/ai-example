package com.example.configmgr.ai.config;

import com.example.configmgr.ai.memory.IdentityBoundChatMemoryAdvisor;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.memory.RedisChatMemoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * AI Runtime 装配（S4.2 §2：记忆三件；模型侧装配自模型配置专项批① 起归
 * {@link AiProviderRegistry}）。
 *
 * <h2>官方扩展点优先（DC-05）</h2>
 * <ul>
 * <li>记忆：官方 {@link MessageWindowChatMemory}（窗口裁剪）+ 官方
 * {@link MessageChatMemoryAdvisor}（读写时机，外面套身份绑定装饰器
 * {@link IdentityBoundChatMemoryAdvisor}：读写发生在无线程身份的上游回调上，身份必须随请求显式绑定），
 * 存储层用官方 SPI {@link ChatMemoryRepository} 的自实现 {@link RedisChatMemoryRepository}；</li>
 * <li>模型：官方 {@code OpenAiChatModel} + {@code ReasoningAwareOpenAiChatModel} 补丁，由
 * {@link AiProviderRegistry} 按 provider 装配（{@code SpToolCallingManager} 与记忆 advisor 仍是
 * 这里的单例 bean，跨 provider 复用）。</li>
 * </ul>
 *
 * <h2>Q2 → 动态可用性（批① 变更）</h2>
 * 此前模型侧 bean 全部条件化在"env 有 key"的 {@code @Conditional} 之下。条件求值早于 Flyway，
 * 无法可靠回答"DB 里有没有 provider"，故改为<b>无条件装配</b>：{@link AiAvailability} 每次调用
 * 现问 {@link AiConfigService}（O(1) 读快照），无可用 provider 时端点仍回 503 同一 code/文案。
 * {@code spring.ai.model.*=none} + {@code spring.ai.chat.client.enabled=false} 保留 ——
 * 官方自动配置在 key 为空时会 Assert 失败并中断启动，这条路仍然必须关掉。
 *
 * <h2>配置文件绑定</h2>
 * {@code spring.ai.openai.*} 仍是<b>种子来源与缺省参数来源</b>（{@link AiConfigService} 读它合成
 * env 种子行；{@link AiProviderRegistry} 以 {@link OpenAiChatProperties#getOptions()} 作默认选项基底），
 * 但它不再是"运行时唯一真相" —— DB 建好后 DB 是真相，env 只作首次种子。
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
			AiProperties properties, com.example.configmgr.ai.session.SessionPurgeGuard purgeGuard) {
		return new RedisChatMemoryRepository(redisTemplate, codec, properties.getSessionTtl(), purgeGuard);
	}

	@Bean
	@ConditionalOnMissingBean
	public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository, AiProperties properties) {
		return MessageWindowChatMemory.builder()
			.chatMemoryRepository(chatMemoryRepository)
			.maxMessages(properties.getMemory().getMaxMessages())
			.build();
	}

	/**
	 * 记忆 advisor：官方 {@link MessageChatMemoryAdvisor} 外面套一层身份绑定装饰器
	 * （{@link IdentityBoundChatMemoryAdvisor}）。
	 *
	 * <p>为什么不能直接挂官方实例：它的读在 {@code boundedElastic}、写在上游发元素的线程 —— 两条线程
	 * 都没有请求身份，仓储会按无身份上下文的旧键形 {@code chat:mem:{<sessionId>}}（legacy 命名空间）
	 * 与裸索引成员写入，造成跨身份可读、销毁删不掉的正文。装饰器在请求入口按本轮记录身份显式绑定
	 * （见该类文档），读写时机/窗口裁剪仍是官方语义。bean 类型仍是官方 advisor 接口，注入方
	 * （{@code AiProviderRegistry}）照旧把它挂成 {@code ChatClient} 的默认 advisor。
	 */
	@Bean
	@ConditionalOnMissingBean
	public BaseChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
		return new IdentityBoundChatMemoryAdvisor(chatMemory);
	}

	// ── 可用性（Q2 → 动态）：无可用 provider 时端点惰性 503 ──────────────────────

	@Bean
	public AiAvailability aiAvailability(AiConfigService aiConfigService) {
		return new AiAvailability(aiConfigService);
	}

}
