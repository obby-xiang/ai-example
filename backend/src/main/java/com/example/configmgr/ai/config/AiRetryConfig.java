package com.example.configmgr.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

/**
 * 模型调用重试模板的装配点（超时面治理批① 第 2 项）。
 *
 * <p>
 * <b>为什么这里要显式给 bean</b>：{@code spring-ai-autoconfigure-retry} 的 {@code retryTemplate}
 * 带 {@code @ConditionalOnMissingBean}，自动配置在用户 bean 之后才求值 —— 本类的 bean 因此
 * <b>顶掉</b>它的缺省策略（10 次尝试 / 2s×5 上限 3min）。缺了这一步，
 * {@link AiProviderRegistry} 里的 {@code getIfAvailable(AiRetryDefaults::retryTemplate)}
 * 回落分支永远不会生效，"代码里写的策略"与"线上真正跑的策略"会是两份。
 *
 * <p>
 * 面很窄：只换 {@code RetryTemplate}，不动 {@code ResponseErrorHandler}（HTTP code → 异常的映射
 * 仍由 autoconfig 给），也不改任何 {@code spring.ai.*} 键。
 */
@Configuration
public class AiRetryConfig {

	@Bean
	public RetryTemplate retryTemplate() {
		return AiRetryDefaults.retryTemplate();
	}

}
