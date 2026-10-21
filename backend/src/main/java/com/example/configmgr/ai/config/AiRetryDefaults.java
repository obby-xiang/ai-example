package com.example.configmgr.ai.config;

import org.springframework.ai.retry.TransientAiException;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.BackOffContext;
import org.springframework.retry.backoff.BackOffInterruptedException;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * 模型调用的默认重试策略（超时面治理批① 第 2 项；原落点 {@code AiProviderRegistry#newModel} 的
 * {@code RetryTemplate::new}）。
 *
 * <h2>为什么必须显式接管</h2>
 * classpath 上有 {@code spring-ai-autoconfigure-retry}，它的 {@code RetryTemplate} bean 带
 * {@code @ConditionalOnMissingBean} 且缺省为 <b>maxAttempts=10 / 退避 2s×5 上限 3min</b>
 * —— 对"一次摘要请求"（父预算 {@code app.ai.context.summary-timeout}=60s）意味着重试退避本身
 * 就能把父预算吃穿；对"一轮对话"（{@code total-budget}=300s）意味着一次上游抖动就能把整轮拖死。
 * 于是本类给出的策略被 {@link AiRetryConfig} 注册成自有 bean（同样吃 {@code @ConditionalOnMissingBean}），
 * <b>配置面写的策略 = 运行态真用的策略</b>。
 *
 * <h2>策略两件套</h2>
 * <ol>
 * <li><b>只重试瞬时故障</b>：{@link TransientAiException}（上游 5xx / 429）、
 * {@link ResourceAccessException}（RestClient 网络层）、{@code WebClientRequestException}（反应式网络层）、
 * {@link IOException} 族。{@code NonTransientAiException}（4xx：鉴权/参数/越窗）<b>不重试</b> ——
 * 同一份坏请求再发一次只会再坏一次。</li>
 * <li><b>指数退避 + 抖动</b>：0.5s 起、×2、上限 5s、`±25%` 抖动。
 * {@value #MAX_ATTEMPTS} 次尝试（= 2 次重试）的最坏退避总和 = {@value #MAX_TOTAL_BACKOFF_MS}ms，
 * 对 60s 的摘要父预算占比 <b>3.2%</b>（上限要求 ≤ 20%，见 {@link #totalBackoffUpperBoundMs()}）。</li>
 * </ol>
 *
 * <p>为什么不直接用 Spring Retry 的 {@code ExponentialBackOffPolicy}：它没有抖动（多实例同时重试会
 * 共振），且无法把"退避总预算"表达成一个可断言的上界 —— 本类用小体积自实现换这两点。
 */
public final class AiRetryDefaults {

	/** 一次模型调用最多 3 次（首轮 + 2 次重试）：再多的重试不如把剩余时间留给上层重试（整轮重来）。 */
	public static final int MAX_ATTEMPTS = 3;

	/** 首次重试的退避基线（毫秒）。 */
	public static final long INITIAL_BACKOFF_MS = 500L;

	/** 单次退避上限（毫秒）。 */
	public static final long MAX_BACKOFF_MS = 5_000L;

	/** 退避倍数。 */
	public static final double MULTIPLIER = 2d;

	/** 抖动幅度（相对基线）：`±25%`。 */
	public static final double JITTER_RATIO = 0.25d;

	/** {@value #MAX_ATTEMPTS} 次尝试的最坏退避总和（毫秒）= 500×1.25 + 1000×1.25。 */
	public static final long MAX_TOTAL_BACKOFF_MS = Math.round(
			INITIAL_BACKOFF_MS * (1 + JITTER_RATIO) + Math.min(INITIAL_BACKOFF_MS * MULTIPLIER, MAX_BACKOFF_MS)
					* (1 + JITTER_RATIO));

	private AiRetryDefaults() {
	}

	/**
	 * 生产重试模板：瞬时故障 + 指数抖动退避。
	 *
	 * <p>抖动源取 {@link ThreadLocalRandom#current()}（按调用线程取，不在构造期固化 —— 单测与生产同一份实现）。
	 */
	public static RetryTemplate retryTemplate() {
		return retryTemplate(() -> ThreadLocalRandom.current().nextDouble());
	}

	/** 可注入抖动源的重载（单测给固定样本：0 = −25%，0.5 = 基线，1 = +25%）。 */
	public static RetryTemplate retryTemplate(DoubleSupplier jitterSample) {
		RetryTemplate template = new RetryTemplate();
		template.setRetryPolicy(new SimpleRetryPolicy(MAX_ATTEMPTS, transientFailures(), true, false));
		template.setBackOffPolicy(new JitteredExponentialBackOff(jitterSample));
		template.setThrowLastExceptionOnExhausted(false);
		return template;
	}

	/**
	 * 瞬时故障白名单（`true` = 重试）。`traverseCauses=true` 让包装过的网络异常也能命中。
	 *
	 * <p>{@code WebClientRequestException} 以类名登记：本模块不直接依赖 webflux 反应式客户端
	 * （模型调用走 RestClient），登记它只为覆盖"将来换回 WebClient"的同一语义。
	 */
	static Map<Class<? extends Throwable>, Boolean> transientFailures() {
		Map<Class<? extends Throwable>, Boolean> map = new HashMap<>();
		map.put(TransientAiException.class, true);
		map.put(ResourceAccessException.class, true);
		map.put(IOException.class, true);
		try {
			@SuppressWarnings("unchecked")
			Class<? extends Throwable> webClientRequest = (Class<? extends Throwable>) Class
				.forName("org.springframework.web.reactive.function.client.WebClientRequestException");
			map.put(webClientRequest, true);
		}
		catch (ClassNotFoundException ignored) {
			// 反应式客户端不在 classpath：该条豁免可省
		}
		return map;
	}

	/** 第 {@code retryNo} 次重试（1 = 第一次重试）的退避基线（毫秒）。 */
	static long baseMs(int retryNo) {
		int n = Math.max(1, retryNo);
		double raw = INITIAL_BACKOFF_MS * Math.pow(MULTIPLIER, n - 1);
		return Math.min((long) Math.round(raw), MAX_BACKOFF_MS);
	}

	/**
	 * 最坏退避总和的<b>运行期</b>算式（供"占父预算比例"断言）：抖动只放大，故取 `1+JITTER_RATIO`。
	 *
	 * @param maxAttempts 尝试次数（≥1）
	 */
	public static long totalBackoffUpperBoundMs(int maxAttempts) {
		int retries = Math.max(0, maxAttempts - 1);
		long sum = 0L;
		for (int retryNo = 1; retryNo <= retries; retryNo++) {
			sum += Math.round(baseMs(retryNo) * (1 + JITTER_RATIO));
		}
		return sum;
	}

	/** 生产取值下的最坏退避总和（毫秒）。 */
	public static long totalBackoffUpperBoundMs() {
		return totalBackoffUpperBoundMs(MAX_ATTEMPTS);
	}

	/** 指数 + 抖动的退避实现（Spring Retry 2.x 无可注入抖动的现成策略）。 */
	static final class JitteredExponentialBackOff implements BackOffPolicy {

		private final DoubleSupplier jitterSample;

		JitteredExponentialBackOff(DoubleSupplier jitterSample) {
			this.jitterSample = jitterSample;
		}

		@Override
		public BackOffContext start(RetryContext context) {
			return new State();
		}

		@Override
		public void backOff(BackOffContext backOffContext) throws BackOffInterruptedException {
			State state = (State) backOffContext;
			state.retryNo++;
			double sample = clamp01(this.jitterSample.getAsDouble());
			long sleep = Math.round(baseMs(state.retryNo) * (1 - JITTER_RATIO + 2 * JITTER_RATIO * sample));
			try {
				Thread.sleep(sleep);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new BackOffInterruptedException("模型重试退避被中断（" + sleep + "ms）", ex);
			}
		}

		static final class State implements BackOffContext {

			private int retryNo;

		}

	}

	private static double clamp01(double sample) {
		if (Double.isNaN(sample)) {
			return 0.5d;
		}
		return Math.max(0d, Math.min(1d, sample));
	}

}
