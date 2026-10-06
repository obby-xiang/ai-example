package com.example.configmgr.ai.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * Q2 修复：仅当 OpenAI 兼容密钥（{@code spring.ai.openai.api-key}，来源为环境变量
 * {@code AI_API_KEY} 或 {@code DEEPSEEK_API_KEY} 兜底）非空白时，才装配模型侧 bean。
 *
 * <p>
 * 未配置 key 时不装配 ChatModel/ChatClient/AiChatService 运行链路，因此后端照常启动，
 * AI 端点由 {@link AiAvailability} 判空后惰性返回 503 {@code AI_UNAVAILABLE}，
 * 业务 API 与 CI 完全不依赖 key。
 */
public class AiKeyPresentCondition implements Condition {

	static final String API_KEY_PROPERTY = "spring.ai.openai.api-key";

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		return StringUtils.hasText(context.getEnvironment().getProperty(API_KEY_PROPERTY, ""));
	}

}
