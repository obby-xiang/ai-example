package com.example.configmgr.ai.context;

import com.example.configmgr.common.CodedIllegalStateException;
import com.example.configmgr.common.ErrorCodes;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.observe.AiTrace;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 摘要模型客户端（上下文统计与压缩专项批③；设计稿 §3.5 / 裁决 D3）。
 *
 * <h2>为什么不复用 {@code ProviderRuntime#chatClient}</h2>
 * 那个 client 挂了默认的 {@code MessageChatMemoryAdvisor}（对话记忆读写）。摘要是一次
 * <b>旁路请求</b>：它既不该读进主会话的记忆，也绝不能把摘要请求本身写回记忆 —— 否则
 * "模型看到的"会被摘要提示词污染。故直接走 {@link ProviderRuntime#chatModel()}，不挂任何 advisor。
 *
 * <h2>超时</h2>
 * {@code summary-timeout} 是<b>硬上限</b>：到点中断并抛错，由编排层走熔断失败路径（历史原样）。
 * 不设超时等于让一轮对话被摘要请求挂住 —— 而这正是熔断要防的那类不可用。
 *
 * <p>
 * {@code summary-model} 为空 = 主模型（D3 的过渡形态：本仓只有一套 provider 配置；
 * 模型配置专项落地后只改这一个键即可切便宜模型）。
 */
public final class ContextSummaryClient implements RollingSummarizer.Summarizer {

	/** 一次模型补全（可替换：单测注入假实现，生产走 provider 注册表）。 */
	@FunctionalInterface
	public interface ModelCall {

		String call(String prompt, String model) throws Exception;

	}

	private final AiProperties properties;

	private final ModelCall call;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	public ContextSummaryClient(AiProperties properties, ModelCall call) {
		this.properties = properties;
		this.call = call;
	}

	/** 生产装配：按当前 provider 运行态取 {@code ChatModel}，逐请求带模型选项。 */
	public static ContextSummaryClient production(AiProperties properties,
			ObjectProvider<AiProviderRegistry> registryProvider) {
		return new ContextSummaryClient(properties, (prompt, model) -> {
			AiProviderRegistry registry = registryProvider.getIfAvailable();
			if (registry == null) {
				throw new CodedIllegalStateException(ErrorCodes.AI_SUMMARY_UNAVAILABLE, "摘要模型不可用：provider 注册表未装配");
			}
			ProviderRuntime runtime = registry.current();
			String modelId = model != null ? model : runtime.modelId();
			OpenAiChatOptions options = OpenAiChatOptions.builder().model(modelId).temperature(0.2).build();
			ChatResponse response = runtime.chatModel().call(new Prompt(List.of(new UserMessage(prompt)), options));
			if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
				throw new CodedIllegalStateException(ErrorCodes.AI_SUMMARY_UNAVAILABLE, "摘要模型返回空响应");
			}
			return response.getResult().getOutput().getText();
		});
	}

	@Override
	public String summarize(String prompt) throws Exception {
		String configured = this.properties.getContext().getSummaryModel();
		String model = configured == null || configured.isBlank() ? null : configured.trim();
		Duration timeout = this.properties.getContext().getSummaryTimeout();
		Future<String> future = this.executor.submit(AiTrace.wrapCallable(() -> this.call.call(prompt, model)));
		try {
			return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			future.cancel(true);
			throw new CodedIllegalStateException(ErrorCodes.AI_SUMMARY_UNAVAILABLE, "摘要请求超时（summary-timeout=" + timeout + "）", ex);
		}
		catch (ExecutionException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof Exception failure) {
				throw failure;
			}
			throw new CodedIllegalStateException(ErrorCodes.AI_SUMMARY_UNAVAILABLE, "摘要请求失败：" + cause, cause);
		}
		catch (InterruptedException ex) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw new CodedIllegalStateException(ErrorCodes.AI_SUMMARY_UNAVAILABLE, "摘要请求被中断", ex);
		}
	}

	/** 由 Spring 在容器关闭时调用（{@code @Bean} 的推断销毁方法）。 */
	public void close() {
		this.executor.shutdownNow();
	}

}
