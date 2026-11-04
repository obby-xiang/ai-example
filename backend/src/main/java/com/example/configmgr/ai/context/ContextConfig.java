package com.example.configmgr.ai.context;

import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.db.AiProviderModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 上下文专项的装配（批①②③：计量 + 展示 + L1/L2/L3 + 熔断）。
 *
 * <p>
 * <b>为什么单独一个 {@code @Configuration} 而不并进 {@code AiModelConfig}</b>：后者与模型配置专项
 * 批③（逐请求模型选择）是<b>硬碰撞文件</b>（设计稿 §6.4 排他性约束）。本专项的分母取数点
 * {@link ContextLimitResolver} 恰恰是"读对方 DB 列"的那一端，两边同时动同一个装配类必然 rebase 两次。
 * 因此本批的 bean 全部落在这里，{@code AiModelConfig} 一行不改。
 *
 * <p>
 * 状态面使用同键形的 {@link RedisContextStateStore}，将写路径 strict read、version CAS 与字段级原子更新
 * 集中在一处；TTL 与身份键段保持既有语义。
 */
@Configuration
public class ContextConfig {

	/**
	 * 分母取数点。DB 源的唯一消费点就是这里 —— 模型配置专项改列/加表时只影响这一处。
	 */
	@Bean
	public ContextLimitResolver contextLimitResolver(ObjectProvider<AiConfigService> configService) {
		return new ContextLimitResolver((providerId, modelId) -> {
			AiConfigService service = configService.getIfAvailable();
			if (service == null || providerId == null || modelId == null) {
				return null;
			}
			return maxContextOf(service, providerId, modelId);
		});
	}

	@Bean
	public ContextCompactor contextCompactor(AiProperties properties, ContextLimitResolver contextLimitResolver,
			ContextStateStore store, ObjectProvider<ContextSummaryClient> summaryClient) {
		return new ContextCompactor(properties, contextLimitResolver, store, summaryClient.getIfAvailable());
	}

	/**
	 * 摘要模型（L2 递归摘要用；摘要链最小统一：本轮同一供应商同一模型）。
	 *
	 * <p>
	 * 走 {@link ContextSummaryClient#production}：每次调用的 provider/model/options 由本轮
	 * {@code ProviderRuntime} 传入，不持有注册表也不读全局默认；直接取 runtime 的 {@code ChatModel}，
	 * 不挂记忆 advisor —— 摘要是一次旁路请求，不能读写主会话记忆。
	 */
	@Bean
	public ContextSummaryClient contextSummaryClient(AiProperties properties) {
		return ContextSummaryClient.production(properties);
	}

	@Bean
	public ContextBreakdownService contextBreakdownService(ContextCompactor contextCompactor, ContextStateStore store) {
		return new ContextBreakdownService(contextCompactor, store);
	}

	/**
	 * {@code ai_model.max_context} 的查找：只认显式 {@code (providerId, modelId)} 这一行。
	 *
	 * <p>同名模型在多个 provider 下各有一行、窗口不同是合法情形，按名字全局匹配会把别家的窗口
	 * 当成自己的分母，故没有第二条路。
	 */
	private static Integer maxContextOf(AiConfigService service, Long providerId, String modelId) {
		AiConfigService.ConfigSnapshot snapshot = service.current();
		for (AiProviderModel model : snapshot.models()) {
			if (!providerId.equals(model.getProviderId()) || !modelId.equals(model.getModelId())) {
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
