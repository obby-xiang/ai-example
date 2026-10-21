package com.example.configmgr.ai.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上游越窗识别（上下文专项批③；设计稿 §3.5 L3"映射为 CONTEXT_OVERFLOW"）。
 *
 * <p>
 * 正例取各主流网关的真实措辞（OpenAI 兼容 400 的 message、DeepSeek/通义等的变体），
 * 反例取"连接中断/空流/普通 500" —— 后者绝不能被误判成越窗（否则会被当成"不可重试"直接失败）。
 */
class ContextOverflowTest {

	@Test
	void recognisesUpstreamContextOverflowWording() {
		assertThat(ContextOverflow.matches("400 Bad Request：context_length_exceeded")).isTrue();
		assertThat(ContextOverflow.matches("This model's maximum context length is 128000 tokens")).isTrue();
		assertThat(ContextOverflow.matches("prompt is too long: 130000 tokens > 128000 maximum")).isTrue();
		assertThat(ContextOverflow.matches("输入过长：超过模型上下文长度")).isTrue();
		assertThat(ContextOverflow.matches(null, "reduce the length of the messages")).isTrue();
	}

	@Test
	void doesNotMistakeOtherFailuresForOverflow() {
		assertThat(ContextOverflow.matches("Connection reset by peer")).isFalse();
		assertThat(ContextOverflow.matches("500 Internal Server Error：upstream overloaded")).isFalse();
		assertThat(ContextOverflow.matches((String) null)).isFalse();
		assertThat(ContextOverflow.matches("工具执行超时")).isFalse();
	}

}
