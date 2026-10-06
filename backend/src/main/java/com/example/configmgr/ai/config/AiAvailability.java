package com.example.configmgr.ai.config;

import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * AI 可用性判定（Q2）。唯一的判据是密钥是否非空白——有了 key 才存在模型侧 bean。
 *
 * <p>
 * 端点据此在<b>进入流式管道之前</b>返回 503 {@code AI_UNAVAILABLE}：不建 SSE 连接、
 * 不触发任何上游调用，业务 API 完全不受影响。
 */
public class AiAvailability {

	private final String apiKey;

	public AiAvailability(Environment environment) {
		this.apiKey = environment.getProperty(AiKeyPresentCondition.API_KEY_PROPERTY, "");
	}

	public boolean isAvailable() {
		return StringUtils.hasText(this.apiKey);
	}

	/** 503 响应体的 code 字段（前端据此展示"AI 未启用"）。 */
	public String code() {
		return "AI_UNAVAILABLE";
	}

	public String reason() {
		return "AI 能力未启用：未配置 AI_API_KEY（或 DEEPSEEK_API_KEY）环境变量，AI 端点已惰性降级";
	}

}
