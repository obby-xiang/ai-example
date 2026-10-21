package com.example.configmgr.ai.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文计量（M 组：C-01…C-06，设计稿 §5.1）。
 *
 * <h2>为什么这组必须存在</h2>
 * 现状是"直读末帧 usage"（{@code Accumulator#usage} 的旧实现），它在上游的两种常见形态下都会失真：
 * <b>末帧 0/0/0</b>（Spring AI 对每个分片都挂一个空 usage）与<b>每块重复同一份 usage</b>
 * （按累加实现就会放大 N 倍）。本组用例逐条钉住 R1–R6，且每条都在类注里写明 kill（改哪一行会红）。
 */
class ContextMeterTest {

	/** 一条带 usage 的上游响应。 */
	private static ChatResponse usage(Integer prompt, Integer completion, Integer total) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("x").build())),
				ChatResponseMetadata.builder().model("test-model").usage(new DefaultUsage(prompt, completion, total)).build());
	}

	/** 一条没有任何 usage 的上游响应（聚合器的空 usage 形态）。 */
	private static ChatResponse plain() {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("x").build())));
	}

	/** C-01：单请求 + 末块带 usage ⇒ 真值、est=false、source=stream。 */
	@Test
	void c01SingleFrameIsTheTruth() {
		ContextMeter meter = new ContextMeter();
		meter.accept(usage(100, 20, 120));

		assertThat(meter.measured()).isTrue();
		assertThat(meter.promptTokens()).isEqualTo(100);
		assertThat(meter.completionTokens()).isEqualTo(20);
		assertThat(meter.frames()).isEqualTo(1);
		assertThat(meter.dirty()).isZero();

		var map = meter.usage(999);
		assertThat(map).containsEntry("promptTokens", 100).containsEntry("completionTokens", 20)
			.containsEntry("totalTokens", 120).containsEntry("est", false)
			.containsEntry("source", ContextMeter.SOURCE_STREAM).containsEntry("frames", 1)
			.containsEntry("dirty", 0);
	}

	/** C-02：每 chunk 重复同一 usage ⇒ 值不放大，frames 照实计。kill：删 R2 并改成累加 ⇒ ×3。 */
	@Test
	void c02RepeatedUsageIsNotAmplified() {
		ContextMeter meter = new ContextMeter();
		meter.accept(usage(100, 20, 120));
		meter.accept(usage(100, 20, 120));
		meter.accept(usage(100, 20, 120));

		assertThat(meter.promptTokens()).as("重复帧不得累加（业界 1.7M tokens 事故的同形成因）").isEqualTo(100);
		assertThat(meter.frames()).isEqualTo(3);
		assertThat(meter.dirty()).isZero();
	}

	/** C-03：末帧 0/0/0、中间帧有真值 ⇒ 取最后一个**非空**。kill：回退成 lastResponse 直读 ⇒ 0/0/0。 */
	@Test
	void c03TrailingEmptyFrameIsIgnored() {
		ContextMeter meter = new ContextMeter();
		meter.accept(usage(100, 20, 120));
		meter.accept(usage(0, 0, 0));

		assertThat(meter.promptTokens()).isEqualTo(100);
		assertThat(meter.measured()).isTrue();
		assertThat(meter.frames()).as("空帧不参与采集计数").isEqualTo(1);
		assertThat(meter.usage(0)).containsEntry("est", false).containsEntry("source", ContextMeter.SOURCE_STREAM);
	}

	/** C-04：全程无 usage ⇒ est=true、source=estimate、值 = 估算器输入。kill：est 恒 false ⇒ 前端 ≈ 断言红。 */
	@Test
	void c04NoUsageFallsBackToEstimate() {
		ContextMeter meter = new ContextMeter();
		meter.accept(plain());
		meter.accept(plain());

		assertThat(meter.measured()).isFalse();
		var map = meter.usage(777);
		assertThat(map).containsEntry("est", true).containsEntry("source", ContextMeter.SOURCE_ESTIMATE)
			.containsEntry("promptTokens", 777).containsEntry("completionTokens", 0)
			.containsEntry("totalTokens", 777);
		assertThat(meter.frames()).isZero();
	}

	/** C-05：prompt 回退（脏帧）⇒ 保留较大值、dirty=1、不抛异常。kill：删 R4 ⇒ 值被拉小。 */
	@Test
	void c05RegressionFrameIsDirtyButNeverWins() {
		ContextMeter meter = new ContextMeter();
		meter.accept(usage(100, 20, 120));
		meter.accept(usage(80, 20, 100));

		assertThat(meter.promptTokens()).isEqualTo(100);
		assertThat(meter.dirty()).isEqualTo(1);
		assertThat(meter.frames()).isEqualTo(2);
	}

	/** C-06：每次尝试各建一个计量器 ⇒ 失败尝试不污染成功尝试（R6）。kill：跨 attempt 复用 ⇒ 混值。 */
	@Test
	void c06AttemptsDoNotShareState() {
		ContextMeter failedAttempt = new ContextMeter();
		failedAttempt.accept(usage(500, 50, 550));

		ContextMeter successfulAttempt = new ContextMeter();
		successfulAttempt.accept(plain());

		assertThat(successfulAttempt.measured()).as("第二次尝试没有 usage ⇒ 必须是估算态，不能继承上一次的 500")
			.isFalse();
		assertThat(successfulAttempt.usage(300)).containsEntry("promptTokens", 300).containsEntry("est", true);
		assertThat(failedAttempt.promptTokens()).isEqualTo(500);
	}

	/** 单调前进（正常多帧累计值）照常采纳最后一个：R3 不能被 R4 误伤。 */
	@Test
	void monotonicFramesAdoptTheLastValue() {
		ContextMeter meter = new ContextMeter();
		meter.accept(usage(100, 20, 120));
		meter.accept(usage(140, 20, 160));
		meter.accept(usage(200, 30, 230));

		assertThat(meter.promptTokens()).isEqualTo(200);
		assertThat(meter.completionTokens()).isEqualTo(30);
		assertThat(meter.dirty()).isZero();
	}

}
