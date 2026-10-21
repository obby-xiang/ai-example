package com.example.configmgr.ai.context;

import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.db.AiProviderModel;
import com.example.configmgr.ai.run.RunStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 上下文专项的装配（批①：计量 + 展示 + L1）。
 *
 * <p>
 * <b>为什么单独一个 {@code @Configuration} 而不并进 {@code AiModelConfig}</b>：后者与模型配置专项
 * 批③（逐请求模型选择）是<b>硬碰撞文件</b>（设计稿 §6.4 排他性约束）。本专项的分母取数点
 * {@link ContextLimitResolver} 恰恰是"读对方 DB 列"的那一端，两边同时动同一个装配类必然 rebase 两次。
 * 因此本批的 bean 全部落在这里，{@code AiModelConfig} 一行不改。
 *
 * <p>
 * <b>状态面复用 {@link RunStore}</b>：{@code ai:ctx:*} 三个键族与既有 run 级外置键共用同一份
 * TTL/实例 id/Redis 模板（设计稿 §6.1 的落点），不另起一个 Redis 访问类。
 */
@Configuration
public class ContextConfig {

	/**
	 * 分母取数点。DB 源的唯一消费点就是这里 —— 模型配置专项改列/加表时只影响这一处。
	 */
	@Bean
	public ContextLimitResolver contextLimitResolver(AiProperties properties,
			ObjectProvider<AiConfigService> configService) {
		return new ContextLimitResolver(properties, (providerId, modelId) -> {
			AiConfigService service = configService.getIfAvailable();
			if (service == null || modelId == null) {
				return null;
			}
			return maxContextOf(service, providerId, modelId);
		});
	}

	@Bean
	public ContextCompactor contextCompactor(AiProperties properties, ContextLimitResolver contextLimitResolver,
			RunStore runStore) {
		return new ContextCompactor(properties, contextLimitResolver, runStore);
	}

	@Bean
	public ContextBreakdownService contextBreakdownService(ContextCompactor contextCompactor, RunStore runStore) {
		return new ContextBreakdownService(contextCompactor, runStore);
	}

	/**
	 * {@code ai_model.max_context} 的查找：同 provider 优先，{@code providerId == null} 时按 modelId
	 * 全局匹配（端点在"只有模型名"的上下文里重建分母）。
	 *
	 * <p>同名模型在多个 provider 下各有一行、窗口不同是合法情形，故带 providerId 时只认本 provider 的行。
	 */
	private static Integer maxContextOf(AiConfigService service, Long providerId, String modelId) {
		AiConfigService.ConfigSnapshot snapshot = service.current();
		for (AiProviderModel model : snapshot.models()) {
			if (!model.isEnabled() || !modelId.equals(model.getModelId())) {
				continue;
			}
			if (providerId != null && !providerId.equals(model.getProviderId())) {
				continue;
			}
			Integer maxContext = model.getMaxContext();
			if (maxContext != null && maxContext > 0) {
				return maxContext;
			}
		}
		return null;
	}

}
