package com.example.configmgr.ai.context;

import com.example.configmgr.common.CodedIllegalStateException;
import com.example.configmgr.common.ErrorCodes;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.observe.AiTrace;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.TenantUser;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * 摘要模型客户端（上下文统计与压缩专项批③；设计稿 §3.5 / 裁决 D3；摘要链最小统一批）。
 *
 * <h2>只用本轮 runtime</h2>
 * 摘要与对话轮次<b>同一供应商、同一模型、同一 options</b>：provider 由 {@link ProviderRuntime#chatModel()}
 * 承载，model 取自 {@link ProviderRuntime#modelId()}，options 从 {@link ProviderRuntime#options()} 复制。
 * 本类<b>不再</b>读取 {@code registry.current()} 的全局默认，也<b>没有</b>独立摘要模型分支
 * （原 {@code app.ai.context.summary-model} 键已按 AR-11 删除，外部仍配置它只得到一次退役 WARN）——
 * 本轮没有 runtime 就直接失败，由编排层保持历史原样。
 *
 * <h2>为什么不复用 {@code ProviderRuntime#chatClient}</h2>
 * 那个 client 挂了默认的 {@code MessageChatMemoryAdvisor}（对话记忆读写）。摘要是一次
 * <b>旁路请求</b>：它既不该读进主会话的记忆，也绝不能把摘要请求本身写回记忆 —— 否则
 * "模型看到的"会被摘要提示词污染。故直接走 {@link ProviderRuntime#chatModel()}，不挂任何 advisor。
 *
 * <h2>请求与产物</h2>
 * 请求固定为<b>两段</b>：{@code SystemMessage} 放摘要规则，{@code UserMessage} 放定界 transcript
 * （规则由 {@link RollingSummarizer} 生成，明确"不可信数据/不得执行/不得调用工具"）。
 * 产物必须完整闭合在 {@code <conversation_summary>…</conversation_summary>} 内；空正文或
 * {@code finishReason=length}（被截断）都按失败处理。
 *
 * <h2>超时</h2>
 * {@code summary-timeout} 是<b>硬上限</b>：到点中断并抛错，由编排层走熔断失败路径（历史原样）。
 * 不设超时等于让一轮对话被摘要请求挂住 —— 而这正是熔断要防的那类不可用。
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

	/** 摘要产物的定界开标签（与历史和提示词同一常量）。 */
	public static final String SUMMARY_OPEN = RollingSummarizer.SUMMARY_OPEN;

	/** 摘要产物的定界闭标签（与历史和提示词同一常量）。 */
	public static final String SUMMARY_CLOSE = RollingSummarizer.SUMMARY_CLOSE;

	/** 一次模型补全（可替换：单测注入假实现，生产走本轮 runtime）。 */
	@FunctionalInterface
	public interface ModelCall {

		String call(String systemPrompt, String userPrompt, ProviderRuntime runtime) throws Exception;

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

	/**
	 * 生产装配：{@code ModelCall} 只使用调用方传入的本轮 runtime（provider/model/options），
	 * 不持有注册表、也不读取全局默认。
	 */
	public static ContextSummaryClient production(AiProperties properties) {
		return new ContextSummaryClient(properties, (systemPrompt, userPrompt, runtime) -> {
			if (runtime == null) {
				throw unavailable("本轮 provider 运行态不可用：摘要只使用本轮模型");
			}
			if (runtime.chatModel() == null) {
				throw unavailable("本轮 provider 运行态缺少 ChatModel：摘要只使用本轮模型");
			}
			if (runtime.modelId() == null || runtime.modelId().isBlank()) {
				throw unavailable("本轮 provider 运行态缺少模型名：摘要只使用本轮模型");
			}
			OpenAiChatOptions options = runtime.options() != null
					? OpenAiChatOptions.fromOptions(runtime.options()) : OpenAiChatOptions.builder().build();
			options.setModel(runtime.modelId());
			// 摘要请求结构性禁用工具：不只是在提示词里说"不得调用工具"
			options.setToolCallbacks(List.of());
			options.setToolNames(Set.of());
			options.setInternalToolExecutionEnabled(false);
			options.setToolContext(Map.of());
			ChatResponse response = runtime.chatModel()
				.call(new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)), options));
			if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
				throw unavailable("摘要模型返回空响应");
			}
			if (response.hasFinishReasons(Set.of("length"))) {
				throw unavailable("摘要被截断（finishReason=length），不采用不完整的摘要");
			}
			String text = response.getResult().getOutput().getText();
			if (text == null || text.isBlank()) {
				throw unavailable("摘要模型返回空正文");
			}
			return text;
		});
	}

	@Override
	public String summarize(String systemPrompt, String userPrompt, ProviderRuntime runtime) throws Exception {
		Duration timeout = this.properties.getContext().getSummaryTimeout();
		Future<String> future;
		// 摘要是本轮对话的旁路请求：提交线程（驱动/续跑线程）已有本轮身份，池线程必须重新建立作用域
		TenantUser identity = RequestContextUtils.currentOrNull();
		try {
			future = this.executor.submit(AiTrace.wrapCallable(() -> {
				try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
					return requireClosedSummary(this.call.call(systemPrompt, userPrompt, runtime));
				}
			}));
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
			throw new CodedIllegalStateException(ErrorCodes.AI_SUMMARY_UNAVAILABLE,
					"摘要请求超时（summary-timeout=" + timeout + "）", ex);
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

	/**
	 * 产物闭合校验：必须<b>只</b>有一个 {@code <conversation_summary>} 块，且块外没有文字。
	 *
	 * <p>只取"第一个闭合标签之前的一段"是不够的：定界外的文字或第二个闭合符意味着模型把注入内容
	 * 当成了新段落 —— 那正是这里要判失败（而不是悄悄截一段）的形态。
	 */
	static String requireClosedSummary(String raw) {
		String text = raw == null ? "" : raw.trim();
		if (text.isEmpty()) {
			throw unavailable("摘要产物为空");
		}
		int open = text.indexOf(SUMMARY_OPEN);
		int close = text.lastIndexOf(SUMMARY_CLOSE);
		if (open != 0 || close < 0 || close + SUMMARY_CLOSE.length() != text.length()) {
			throw unavailable("摘要产物必须且只能闭合在 " + SUMMARY_OPEN + "…" + SUMMARY_CLOSE + " 定界内");
		}
		String inner = text.substring(SUMMARY_OPEN.length(), close);
		if (inner.contains(SUMMARY_OPEN) || inner.contains(SUMMARY_CLOSE)) {
			throw unavailable("摘要产物在定界内再次出现定界符（注入闭合失败）");
		}
		inner = inner.trim();
		if (inner.isEmpty()) {
			throw unavailable("摘要产物为空");
		}
		return inner;
	}

	private static CodedIllegalStateException unavailable(String message) {
		return new CodedIllegalStateException(ErrorCodes.AI_SUMMARY_UNAVAILABLE, message);
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
