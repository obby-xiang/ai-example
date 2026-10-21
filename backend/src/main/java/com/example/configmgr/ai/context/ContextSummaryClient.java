package com.example.configmgr.ai.context;

import com.example.configmgr.common.CodedIllegalStateException;
import com.example.configmgr.common.ErrorCodes;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

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
 *
 * <h2>线程形态（超时面治理批① 第 5 项）</h2>
 * 摘要跑在<b>有界平台线程池</b>上，不是 {@code newVirtualThreadPerTaskExecutor()}。理由是超时收口
 * 依赖 {@code Future#cancel(true)}：中断在平台线程上能穿透到阻塞 socket 读（JDK HttpClient 会因此
 * 中止请求），而对停在 native 阻塞读上的虚拟线程并不可靠（取消可能只标记了 future，请求继续跑）。
 * 摘要调用本身是非流式 {@code ChatModel#call}（Spring AI 内部走 RestClient，不经 WebClient），
 * 因此 HTTP 层的 {@code responseTimeout} 对这条路径没有收口作用；层次分工是
 * <b>父预算（{@code summary-timeout}）给"一次摘要"的硬上限，HTTP 层上限管连接/读的细粒度切片</b>。
 *
 * <p>
 * 池容量 {@value #POOL_THREADS}、有界队列 {@value #QUEUE_CAPACITY}：摘要只由 L2/L3 压缩触发、
 * 单轮内串行，4 条线程足够；队满即 {@code AbortPolicy} 拒绝 ⇒ 映射成
 * {@code AI_SUMMARY_UNAVAILABLE}（可判定，不排队无限等）。
 */
public final class ContextSummaryClient implements RollingSummarizer.Summarizer {

	/** 一次模型补全（可替换：单测注入假实现，生产走 provider 注册表）。 */
	@FunctionalInterface
	public interface ModelCall {

		String call(String prompt, String model) throws Exception;

	}

	private final AiProperties properties;

	private final ModelCall call;

	/** 有界平台线程池（不是虚拟线程：见类注。守护线程 + 空闲回收，容器关闭时 shutdownNow）。 */
	private static final int POOL_THREADS = 4;

	/** 待执行摘要的排队上限；队满即拒绝（宁可判"摘要不可用"，也不无限排队等一个挂住的请求）。 */
	private static final int QUEUE_CAPACITY = 8;

	private static final AtomicInteger THREAD_NO = new AtomicInteger();

	private final ExecutorService executor = newExecutor();

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
		Future<String> future;
		try {
			future = this.executor.submit(() -> this.call.call(prompt, model));
		}
		catch (RejectedExecutionException ex) {
			// 池饱和/已关闭：不排队等（一个挂住的摘要请求会把队列堵满），按"摘要不可用"收口
			throw new CodedIllegalStateException(ErrorCodes.AI_SUMMARY_UNAVAILABLE,
					"摘要执行池已满或已关闭（池=" + POOL_THREADS + " 队列=" + QUEUE_CAPACITY + "）", ex);
		}
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

	/**
	 * 摘要执行池（超时面治理批① 第 5 项）：有界、平台线程、守护、空闲回收。
	 *
	 * <p>为什么 {@code allowCoreThreadTimeOut(true)}：摘要不是常驻负载，一个只压缩过几次的进程
	 * 不该长期占着 4 条线程（单测里每个用例都新建一个客户端，线程泄漏会拖垮整轮门禁）。
	 */
	private static ExecutorService newExecutor() {
		ThreadPoolExecutor pool = new ThreadPoolExecutor(POOL_THREADS, POOL_THREADS, 30L, TimeUnit.SECONDS,
				new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
					Thread thread = new Thread(runnable, "ctx-summary-" + THREAD_NO.incrementAndGet());
					thread.setDaemon(true);
					return thread;
				}, new ThreadPoolExecutor.AbortPolicy());
		pool.allowCoreThreadTimeOut(true);
		return pool;
	}

	/** 本客户端实际使用的执行池（诊断/用例断言"不是虚拟线程 + 有界"）。 */
	ExecutorService executor() {
		return this.executor;
	}

}
