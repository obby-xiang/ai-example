package com.example.configmgr.ai.context;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.common.CodedIllegalStateException;
import com.example.configmgr.common.ErrorCodes;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

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
			.isInstanceOf(CodedIllegalStateException.class)
			.hasMessageContaining("summary request timed out")
			.satisfies(ex -> assertThat(((CodedIllegalStateException) ex).getCode())
				.isEqualTo(ErrorCodes.AI_SUMMARY_UNAVAILABLE));
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

	/** 停机先让在飞摘要正常收尾，不能立即中断模型调用。 */
	@Test
	void closeWaitsForInflightSummaryToComplete() throws Exception {
		this.properties.getContext().setSummaryTimeout(Duration.ofSeconds(5));
		CountDownLatch started = new CountDownLatch(1);
		AtomicBoolean interrupted = new AtomicBoolean();
		ContextSummaryClient client = new ContextSummaryClient(this.properties, (system, user, runtime) -> {
			started.countDown();
			try {
				Thread.sleep(150);
			}
			catch (InterruptedException ex) {
				interrupted.set(true);
				throw ex;
			}
			return "<conversation_summary>在飞摘要</conversation_summary>";
		});
		CompletableFuture<String> summary = CompletableFuture.supplyAsync(() -> {
			try {
				return client.summarize("系统", "用户", TestProviderRuntime.runtime());
			}
			catch (Exception ex) {
				throw new CompletionException(ex);
			}
		});
		try {
			assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
			client.close();

			assertThat(summary).succeedsWithin(Duration.ofSeconds(2)).isEqualTo("在飞摘要");
			assertThat(interrupted).as("有界等待窗口内不得中断在飞摘要").isFalse();
		}
		finally {
			client.close();
			summary.cancel(true);
		}
	}

	/** 超过有界等待窗口才取消在飞摘要，并留下未竟任务的真实记录。 */
	@Test
	void closeCancelsAndLogsUnfinishedSummaryAfterBoundedWait() throws Exception {
		this.properties.getContext().setSummaryTimeout(Duration.ofSeconds(10));
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch interrupted = new CountDownLatch(1);
		CountDownLatch interruptedMayFinish = new CountDownLatch(1);
		ContextSummaryClient client = new ContextSummaryClient(this.properties, (system, user, runtime) -> {
			started.countDown();
			try {
				if (!release.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("test summary was not released");
				}
			}
			catch (InterruptedException ex) {
				interrupted.countDown();
				interruptedMayFinish.await(5, TimeUnit.SECONDS);
				throw ex;
			}
			return "<conversation_summary>迟到的摘要</conversation_summary>";
		});
		CompletableFuture<String> summary = CompletableFuture.supplyAsync(() -> {
			try {
				return client.summarize("系统", "用户", TestProviderRuntime.runtime());
			}
			catch (Exception ex) {
				throw new CompletionException(ex);
			}
		});
		Logger logger = (Logger) LoggerFactory.getLogger(ContextSummaryClient.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		CompletableFuture<Void> closing = null;
		try {
			assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
			long closeStarted = System.nanoTime();
			closing = CompletableFuture.runAsync(client::close);

			assertThat(interrupted.await(150, TimeUnit.MILLISECONDS)).as("宽限窗口内不得提前取消").isFalse();
			closing.get(4, TimeUnit.SECONDS);
			long closeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStarted);
			assertThat(closeMillis).as("2 秒停机预算及调度容差").isBetween(1_500L, 4_000L);
			assertThat(interrupted.await(5, TimeUnit.SECONDS)).as("超时后必须取消在飞摘要").isTrue();
			assertThat(appender.list).anySatisfy(event -> {
				assertThat(event.getLevel()).isEqualTo(Level.WARN);
				assertThat(event.getFormattedMessage()).isEqualTo(
						"summary executor cancelled unfinished tasks (reason=timeout queued=0 active=1 terminated=false)");
			});
		}
		finally {
			release.countDown();
			interruptedMayFinish.countDown();
			if (closing != null) {
				closing.cancel(true);
			}
			summary.cancel(true);
			client.close();
			logger.detachAppender(appender);
		}
	}

	/** 四条活动摘要占满线程池时，排队摘要必须在停机后及时取消且从未进入模型体。 */
	@Test
	void closeCancelsQueuedSummaryWithoutStartingItsModelCall() throws Exception {
		this.properties.getContext().setSummaryTimeout(Duration.ofSeconds(10));
		AtomicInteger modelCalls = new AtomicInteger();
		Set<String> modelPrompts = ConcurrentHashMap.newKeySet();
		CountDownLatch started = new CountDownLatch(4);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch interrupted = new CountDownLatch(4);
		CountDownLatch interruptedMayFinish = new CountDownLatch(1);
		ContextSummaryClient client = new ContextSummaryClient(this.properties, (system, user, runtime) -> {
			modelCalls.incrementAndGet();
			modelPrompts.add(user);
			started.countDown();
			try {
				if (!release.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("test summary was not released");
				}
			}
			catch (InterruptedException ex) {
				interrupted.countDown();
				interruptedMayFinish.await(5, TimeUnit.SECONDS);
				throw ex;
			}
			return "<conversation_summary>迟到的摘要</conversation_summary>";
		});
		ExecutorService callers = Executors.newFixedThreadPool(5);
		List<Future<String>> calls = new ArrayList<>();
		for (int index = 0; index < 5; index++) {
			int callIndex = index;
			calls.add(callers.submit(() -> client.summarize("系统", "用户-" + callIndex, TestProviderRuntime.runtime())));
		}
		Logger logger = (Logger) LoggerFactory.getLogger(ContextSummaryClient.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
			ThreadPoolExecutor pool = (ThreadPoolExecutor) client.executor();
			assertThat(pool.getActiveCount()).isEqualTo(4);
			assertThat(pool.getQueue()).hasSize(1);

			long closeStarted = System.nanoTime();
			client.close();
			long closeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStarted);
			assertThat(closeMillis).as("2 秒停机预算及调度容差").isBetween(1_500L, 4_000L);
			assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(modelCalls).as("排队摘要不得进入模型体").hasValue(4);
			assertThat(modelPrompts).hasSize(4);
			assertThat(appender.list).anySatisfy(event -> {
				assertThat(event.getLevel()).isEqualTo(Level.WARN);
				assertThat(event.getFormattedMessage()).isEqualTo(
						"summary executor cancelled unfinished tasks (reason=timeout queued=1 active=4 terminated=false)");
			});

			int queuedIndex = -1;
			for (int index = 0; index < calls.size(); index++) {
				if (!modelPrompts.contains("用户-" + index)) {
					queuedIndex = index;
					break;
				}
			}
			assertThat(queuedIndex).isNotNegative();
			Future<String> queued = calls.get(queuedIndex);
			assertThatThrownBy(() -> queued.get(1, TimeUnit.SECONDS))
				.isInstanceOf(ExecutionException.class)
				.satisfies(ex -> {
					assertThat(ex.getCause()).isInstanceOf(CodedIllegalStateException.class);
					CodedIllegalStateException failure = (CodedIllegalStateException) ex.getCause();
					assertThat(failure.getCode()).isEqualTo(ErrorCodes.AI_SUMMARY_UNAVAILABLE);
					assertThat(failure).hasMessage("summary request cancelled")
						.hasCauseInstanceOf(CancellationException.class);
				});
		}
		finally {
			release.countDown();
			interruptedMayFinish.countDown();
			calls.forEach(call -> call.cancel(true));
			callers.shutdownNow();
			client.close();
			logger.detachAppender(appender);
		}
	}

	/** 重复停机不得改变已经完成的收口结果。 */
	@Test
	void closeIsIdempotentAfterInflightSummaryCompletes() throws Exception {
		this.properties.getContext().setSummaryTimeout(Duration.ofSeconds(5));
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch interrupted = new CountDownLatch(1);
		ContextSummaryClient client = new ContextSummaryClient(this.properties, (system, user, runtime) -> {
			started.countDown();
			try {
				if (!release.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("test summary was not released");
				}
			}
			catch (InterruptedException ex) {
				interrupted.countDown();
				throw ex;
			}
			return "<conversation_summary>幂等摘要</conversation_summary>";
		});
		CompletableFuture<String> summary = CompletableFuture.supplyAsync(() -> {
			try {
				return client.summarize("系统", "用户", TestProviderRuntime.runtime());
			}
			catch (Exception ex) {
				throw new CompletionException(ex);
			}
		});
		ExecutorService closers = Executors.newFixedThreadPool(2);
		CountDownLatch closeInvoked = new CountDownLatch(2);
		Runnable close = () -> {
			closeInvoked.countDown();
			client.close();
		};
		Future<?> firstClose = null;
		Future<?> secondClose = null;
		try {
			assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
			firstClose = closers.submit(close);
			secondClose = closers.submit(close);
			assertThat(closeInvoked.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(firstClose.isDone() && secondClose.isDone()).as("两次 close 必须真实重叠").isFalse();
			assertThat(interrupted.getCount()).as("重叠关闭期间不得提前取消").isEqualTo(1);
			release.countDown();

			firstClose.get(2, TimeUnit.SECONDS);
			secondClose.get(2, TimeUnit.SECONDS);
			assertThat(summary).succeedsWithin(Duration.ofSeconds(2)).isEqualTo("幂等摘要");
			assertThat(client.executor().isShutdown()).isTrue();
		}
		finally {
			release.countDown();
			if (firstClose != null) {
				firstClose.cancel(true);
			}
			if (secondClose != null) {
				secondClose.cancel(true);
			}
			closers.shutdownNow();
			client.close();
			summary.cancel(true);
		}
	}

	/** 等待停机时被中断：取消未竟摘要，并恢复调用线程的中断标记。 */
	@Test
	void closeRestoresInterruptAfterCancellingUnfinishedSummary() throws Exception {
		this.properties.getContext().setSummaryTimeout(Duration.ofSeconds(10));
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicBoolean modelInterrupted = new AtomicBoolean();
		ContextSummaryClient client = new ContextSummaryClient(this.properties, (system, user, runtime) -> {
			started.countDown();
			try {
				if (!release.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("test summary was not released");
				}
			}
			catch (InterruptedException ex) {
				modelInterrupted.set(true);
				throw ex;
			}
			return "<conversation_summary>迟到的摘要</conversation_summary>";
		});
		CompletableFuture<String> summary = CompletableFuture.supplyAsync(() -> {
			try {
				return client.summarize("系统", "用户", TestProviderRuntime.runtime());
			}
			catch (Exception ex) {
				throw new CompletionException(ex);
			}
		});
		ExecutorService closer = Executors.newSingleThreadExecutor();
		AtomicReference<Thread> closingThread = new AtomicReference<>();
		AtomicBoolean interruptRestored = new AtomicBoolean();
		CountDownLatch closeEntered = new CountDownLatch(1);
		CountDownLatch closeReturned = new CountDownLatch(1);
		Future<?> closing = null;
		try {
			assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
			closing = closer.submit(() -> {
				closingThread.set(Thread.currentThread());
				closeEntered.countDown();
				client.close();
				interruptRestored.set(Thread.currentThread().isInterrupted());
				closeReturned.countDown();
			});
			assertThat(closeEntered.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(closeReturned.await(150, TimeUnit.MILLISECONDS)).as("停机必须仍在有界等待中").isFalse();

			closingThread.get().interrupt();
			closing.get(2, TimeUnit.SECONDS);
			assertThat(interruptRestored).as("close 调用线程的中断标记必须恢复").isTrue();
			assertThat(modelInterrupted).as("中断停机必须取消未竟摘要").isTrue();
		}
		finally {
			release.countDown();
			Thread thread = closingThread.get();
			if (thread != null) {
				thread.interrupt();
			}
			if (closing != null) {
				closing.cancel(true);
			}
			closer.shutdownNow();
			summary.cancel(true);
			client.close();
		}
	}

	/** 未竟日志自身失败时仍恢复中断，并把真实日志异常交还调用方。 */
	@Test
	@SuppressWarnings("unchecked")
	void closeRestoresInterruptWhenUnfinishedLoggingFails() throws Exception {
		this.properties.getContext().setSummaryTimeout(Duration.ofSeconds(10));
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicBoolean modelInterrupted = new AtomicBoolean();
		ContextSummaryClient client = new ContextSummaryClient(this.properties, (system, user, runtime) -> {
			started.countDown();
			try {
				if (!release.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("test summary was not released");
				}
			}
			catch (InterruptedException ex) {
				modelInterrupted.set(true);
				throw ex;
			}
			return "<conversation_summary>迟到的摘要</conversation_summary>";
		});
		CompletableFuture<String> summary = CompletableFuture.supplyAsync(() -> {
			try {
				return client.summarize("系统", "用户", TestProviderRuntime.runtime());
			}
			catch (Exception ex) {
				throw new CompletionException(ex);
			}
		});
		Logger logger = (Logger) LoggerFactory.getLogger(ContextSummaryClient.class);
		IllegalStateException logFailure = new IllegalStateException("test logging failure");
		Appender<ILoggingEvent> failingAppender = mock(Appender.class);
		doThrow(logFailure).when(failingAppender).doAppend(any());
		logger.addAppender(failingAppender);
		ExecutorService closer = Executors.newSingleThreadExecutor();
		AtomicReference<Thread> closingThread = new AtomicReference<>();
		AtomicReference<RuntimeException> closeFailure = new AtomicReference<>();
		AtomicBoolean interruptRestored = new AtomicBoolean();
		CountDownLatch closeEntered = new CountDownLatch(1);
		CountDownLatch closeReturned = new CountDownLatch(1);
		Future<?> closing = null;
		try {
			assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
			closing = closer.submit(() -> {
				closingThread.set(Thread.currentThread());
				closeEntered.countDown();
				try {
					client.close();
				}
				catch (RuntimeException ex) {
					closeFailure.set(ex);
				}
				finally {
					interruptRestored.set(Thread.currentThread().isInterrupted());
					closeReturned.countDown();
				}
			});
			assertThat(closeEntered.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(closeReturned.await(150, TimeUnit.MILLISECONDS)).isFalse();

			closingThread.get().interrupt();
			closing.get(2, TimeUnit.SECONDS);
			assertThat(closeFailure).hasValue(logFailure);
			assertThat(interruptRestored).as("日志失败不得阻断中断标记恢复").isTrue();
			assertThat(modelInterrupted).isTrue();
		}
		finally {
			release.countDown();
			Thread thread = closingThread.get();
			if (thread != null) {
				thread.interrupt();
			}
			if (closing != null) {
				closing.cancel(true);
			}
			closer.shutdownNow();
			summary.cancel(true);
			client.close();
			logger.detachAppender(failingAppender);
		}
	}

	/** 池关闭后提交 ⇒ 可判定的「摘要不可用」（不是无限排队、不是 NPE）。 */
	@Test
	void closedClientRejectsInsteadOfQueueingForever() {
		this.properties.getContext().setSummaryTimeout(Duration.ofSeconds(5));
		ContextSummaryClient client = new ContextSummaryClient(this.properties, (system, user, runtime) ->
				"<conversation_summary>摘要</conversation_summary>");
		client.close();

		assertThatThrownBy(() -> client.summarize("系统", "用户", TestProviderRuntime.runtime()))
			.isInstanceOf(CodedIllegalStateException.class)
			.hasMessageContaining("summary executor pool is saturated or closed")
			.satisfies(ex -> assertThat(((CodedIllegalStateException) ex).getCode())
				.isEqualTo(ErrorCodes.AI_SUMMARY_UNAVAILABLE));
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
