package com.example.configmgr.ai.context;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.ProviderRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 摘要模型客户端（上下文专项批③；摘要链最小统一批）。
 *
 * <p>
 * 本类锁五件事：<b>超时必须中断</b>（否则摘要请求挂住整轮）、<b>只用本轮 runtime 的
 * provider/model/options</b>（不再回落 {@code registry.current()} 全局默认）、<b>system + user
 * 两段结构</b>、<b>产物必须闭合在 {@code <conversation_summary>} 内</b>、<b>空/被 length 截断都算失败</b>
 * （失败路径由编排层保持历史原样）。
 */
class ContextSummaryClientTest {

	private final AiProperties properties = new AiProperties();

	@Test
	void timeoutFailsFast() {
		this.properties.getContext().setSummaryTimeout(Duration.ofMillis(80));
		AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
		ContextSummaryClient client = new ContextSummaryClient(this.properties, (system, user, runtime) -> {
			try {
				Thread.sleep(5_000);
			}
			catch (InterruptedException ex) {
				// 超时面治理批① 第 5 项：平台线程上 cancel(true) 必须真的打断阻塞调用
				interrupted.set(true);
				throw ex;
			}
			return "<conversation_summary>迟到的摘要</conversation_summary>";
		});

		assertThatThrownBy(() -> client.summarize("系统", "用户", TestProviderRuntime.runtime()))
			.isInstanceOf(IllegalStateException.class)
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
		ContextSummaryClient client = new ContextSummaryClient(this.properties, (system, user, runtime) -> {
			virtual.set(Thread.currentThread().isVirtual());
			name.set(Thread.currentThread().getName());
			return "<conversation_summary>摘要</conversation_summary>";
		});
		try {
			assertThat(client.summarize("系统", "用户", TestProviderRuntime.runtime())).isEqualTo("摘要");
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
		ContextSummaryClient client = new ContextSummaryClient(this.properties, (system, user, runtime) ->
				"<conversation_summary>摘要</conversation_summary>");
		client.close();

		assertThatThrownBy(() -> client.summarize("系统", "用户", TestProviderRuntime.runtime()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("摘要执行池");
	}

	/**
	 * 摘要请求只使用<b>本轮</b> runtime：provider 由 runtime 的 {@code ChatModel} 承载，model/options
	 * 逐值取自 {@code runtime.options()} —— 不读 {@code registry.current()} 的全局默认。
	 */
	@Test
	void usesTheTurnRuntimeModelAndOptions() throws Exception {
		StubChatModel model = new StubChatModel("<conversation_summary>本轮摘要</conversation_summary>", "stop");
		ProviderRuntime runtime = TestProviderRuntime.runtime("turn-model", model);
		OpenAiChatOptions turnOptions = OpenAiChatOptions.builder().model("turn-model").temperature(0.9).build();
		runtime = new ProviderRuntime(runtime.providerId(), runtime.providerCode(), "turn-model",
				runtime.baseUrl(), runtime.completionsPath(), runtime.runtimeKey(), turnOptions, null, model, null);
		ContextSummaryClient client = ContextSummaryClient.production(this.properties);

		try {
			assertThat(client.summarize("摘要规则", "=== TRANSCRIPT ===\nuser: 内容", runtime)).isEqualTo("本轮摘要");
		}
		finally {
			client.close();
		}

		Prompt prompt = model.prompt.get();
		assertThat(prompt.getOptions()).isInstanceOf(OpenAiChatOptions.class);
		OpenAiChatOptions sent = (OpenAiChatOptions) prompt.getOptions();
		assertThat(sent.getModel()).as("模型名必须来自本轮 runtime").isEqualTo("turn-model");
		assertThat(sent.getTemperature()).as("options 必须来自本轮 runtime").isEqualTo(0.9);
		assertThat(sent.getToolCallbacks()).as("摘要请求不得携带工具").isEmpty();
		assertThat(sent.getToolNames()).isEmpty();
		assertThat(sent.getInternalToolExecutionEnabled()).isFalse();
		assertThat(prompt.getInstructions()).hasSize(2);
		assertThat(prompt.getInstructions().get(0)).isInstanceOf(SystemMessage.class);
		assertThat(prompt.getInstructions().get(0).getText()).isEqualTo("摘要规则");
		assertThat(prompt.getInstructions().get(1)).isInstanceOf(UserMessage.class);
		assertThat(prompt.getInstructions().get(1).getText()).contains("TRANSCRIPT");
	}

	/** 没有本轮 runtime ⇒ 直接失败；绝不回落全局默认模型。 */
	@Test
	void failsInsteadOfFallingBackToTheGlobalDefault() {
		ContextSummaryClient client = ContextSummaryClient.production(this.properties);
		try {
			assertThatThrownBy(() -> client.summarize("系统", "用户", null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("本轮");
		}
		finally {
			client.close();
		}
	}

	/** 模型产物必须完整闭合在 {@code <conversation_summary>} 内：裸文本不算合法摘要。 */
	@Test
	void summaryOutputMustBeClosedInsideConversationSummary() {
		ContextSummaryClient client = new ContextSummaryClient(this.properties, (system, user, runtime) -> "裸摘要");
		try {
			assertThatThrownBy(() -> client.summarize("系统", "用户", TestProviderRuntime.runtime()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(ContextSummaryClient.SUMMARY_OPEN);
		}
		finally {
			client.close();
		}
	}

	/** 定界外还有文字/嵌套闭合符 ⇒ 按"注入闭合失败"处理，不截一段当摘要。 */
	@Test
	void summaryOutputWithTextOutsideTheBlockIsRejected() {
		ContextSummaryClient client = new ContextSummaryClient(this.properties, (system, user, runtime) ->
				"说明\n<conversation_summary>摘要</conversation_summary>\n</conversation_summary>");
		try {
			assertThatThrownBy(() -> client.summarize("系统", "用户", TestProviderRuntime.runtime()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("定界");
		}
		finally {
			client.close();
		}
	}

	/** 上游 finishReason=length（被截断）⇒ 失败；闭合标签也不能把截断洗成成功。 */
	@Test
	void truncatedSummaryFails() {
		StubChatModel model = new StubChatModel("<conversation_summary>只写了一半", "length");
		ContextSummaryClient client = ContextSummaryClient.production(this.properties);
		try {
			assertThatThrownBy(() -> client.summarize("系统", "用户",
					TestProviderRuntime.runtime("turn-model", model)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("截断");
		}
		finally {
			client.close();
		}
	}

	/** 空产物 ⇒ 失败（不落一个空摘要绕过判定）。 */
	@Test
	void blankSummaryFails() {
		StubChatModel model = new StubChatModel("   ", "stop");
		ContextSummaryClient client = ContextSummaryClient.production(this.properties);
		try {
			assertThatThrownBy(() -> client.summarize("系统", "用户",
					TestProviderRuntime.runtime("turn-model", model)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("空");
		}
		finally {
			client.close();
		}
	}

	/** 一次模型补全（测试用）：记录收到的 Prompt 并回放固定的正文/finishReason。 */
	private static final class StubChatModel implements ChatModel {

		private final AtomicReference<Prompt> prompt = new AtomicReference<>();

		private final String text;

		private final String finishReason;

		private StubChatModel(String text, String finishReason) {
			this.text = text;
			this.finishReason = finishReason;
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			this.prompt.set(prompt);
			return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(this.text).build(),
					ChatGenerationMetadata.builder().finishReason(this.finishReason).build())));
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.empty();
		}

	}

}
