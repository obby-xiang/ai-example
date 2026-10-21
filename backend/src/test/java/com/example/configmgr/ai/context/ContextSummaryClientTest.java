package com.example.configmgr.ai.context;

import com.example.configmgr.ai.config.AiProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
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
		AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
		ContextSummaryClient client = new ContextSummaryClient(this.properties, (prompt, model) -> {
			try {
				Thread.sleep(5_000);
			}
			catch (InterruptedException ex) {
				// 超时面治理批① 第 5 项：平台线程上 cancel(true) 必须真的打断阻塞调用
				interrupted.set(true);
				throw ex;
			}
			return "迟到的摘要";
		});

		assertThatThrownBy(() -> client.summarize("prompt")).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("超时");
		long deadline = System.currentTimeMillis() + 2_000L;
		while (!interrupted.get() && System.currentTimeMillis() < deadline) {
			Thread.onSpinWait();
		}
		assertThat(interrupted.get()).as("超时收口必须中断底层调用（否则请求继续跑）").isTrue();
		client.close();
	}

	/**
	 * 超时面治理批① 第 5 项：摘要跑在有界<b>平台</b>线程池上（虚拟线程 + 阻塞读让 cancel(true) 不可靠）。
	 */
	@Test
	void summaryRunsOnABoundedPlatformPoolNotVirtualThreads() throws Exception {
		AtomicReference<Boolean> virtual = new AtomicReference<>();
		AtomicReference<String> name = new AtomicReference<>();
		ContextSummaryClient client = new ContextSummaryClient(this.properties, (prompt, model) -> {
			virtual.set(Thread.currentThread().isVirtual());
			name.set(Thread.currentThread().getName());
			return "摘要";
		});
		try {
			assertThat(client.summarize("prompt")).isEqualTo("摘要");
		}
		finally {
			client.close();
		}

		assertThat(virtual.get()).as("不是虚拟线程").isFalse();
		assertThat(name.get()).as("线程名可辨识").startsWith("ctx-summary-");
		assertThat(client.executor()).isInstanceOf(ThreadPoolExecutor.class);
		ThreadPoolExecutor pool = (ThreadPoolExecutor) client.executor();
		assertThat(pool.getMaximumPoolSize()).as("有界线程数").isEqualTo(4);
		assertThat(pool.getQueue().remainingCapacity()).as("有界队列").isEqualTo(8);
	}

	/** 池关闭后提交 ⇒ 可判定的「摘要不可用」（不是无限排队、不是 NPE）。 */
	@Test
	void closedClientRejectsInsteadOfQueueingForever() {
		this.properties.getContext().setSummaryTimeout(Duration.ofSeconds(5));
		ContextSummaryClient client = new ContextSummaryClient(this.properties, (prompt, model) -> "摘要");
		client.close();

		assertThatThrownBy(() -> client.summarize("prompt")).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("摘要执行池");
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
