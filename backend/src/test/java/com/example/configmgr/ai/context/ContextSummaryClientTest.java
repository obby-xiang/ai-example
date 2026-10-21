package com.example.configmgr.ai.context;

import com.example.configmgr.ai.config.AiProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 摘要模型客户端（上下文专项批③；设计稿 §3.5「summary-timeout 是失败/熔断路径的输入」）。
 *
 * <p>
 * 本类只锁两件事：<b>超时必须中断</b>（否则摘要请求挂住整轮）、<b>{@code summary-model} 空 = 主模型</b>
 * （D3 裁决的过渡形态）。真正的模型调用在 provider 注册表后面，单测注入假实现。
 */
class ContextSummaryClientTest {

	private final AiProperties properties = new AiProperties();

	@Test
	void timeoutFailsFast() {
		this.properties.getContext().setSummaryTimeout(Duration.ofMillis(80));
		ContextSummaryClient client = new ContextSummaryClient(this.properties, (prompt, model) -> {
			Thread.sleep(5_000);
			return "迟到的摘要";
		});

		assertThatThrownBy(() -> client.summarize("prompt")).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("超时");
	}

	/** summary-model 为空 ⇒ 传 null 给下游（= 用主模型），不让空串当作模型名发上游。 */
	@Test
	void blankSummaryModelMeansMainModel() throws Exception {
		AtomicReference<String> seenModel = new AtomicReference<>("unset");
		this.properties.getContext().setSummaryModel("  ");
		ContextSummaryClient client = new ContextSummaryClient(this.properties, (prompt, model) -> {
			seenModel.set(model);
			return "摘要";
		});

		assertThat(client.summarize("prompt")).isEqualTo("摘要");
		assertThat(seenModel.get()).isNull();
	}

	@Test
	void summaryModelOverrideIsPassedThrough() throws Exception {
		AtomicReference<String> seenModel = new AtomicReference<>();
		this.properties.getContext().setSummaryModel("cheap-model");
		ContextSummaryClient client = new ContextSummaryClient(this.properties, (prompt, model) -> {
			seenModel.set(model);
			return "摘要";
		});

		client.summarize("prompt");

		assertThat(seenModel.get()).isEqualTo("cheap-model");
	}

}
