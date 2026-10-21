package com.example.configmgr.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型调用的默认重试策略（超时面治理批① 第 2 项）。
 *
 * <p>锁四件事：<b>只重试瞬时</b>、<b>次数有界（3）</b>、<b>退避 0.5s 起 ×2 上限 5s + 抖动</b>、
 * <b>重试总时长 ≤ 摘要父预算 20%</b>。
 */
class AiRetryDefaultsTest {

	/** 摘要父预算（{@code app.ai.context.summary-timeout} 缺省 60s）。 */
	private static final long SUMMARY_TIMEOUT_MS = Duration.ofSeconds(60).toMillis();

	@Test
	void transientFailuresAreRetriedExactlyMaxAttempts() {
		AtomicInteger calls = new AtomicInteger();
		RetryTemplate template = fastTemplate();

		assertThatThrownBy(() -> template.execute((RetryCallback<Void, RuntimeException>) ctx -> {
			calls.incrementAndGet();
			throw new TransientAiException("上游 503");
		})).isInstanceOf(TransientAiException.class);

		assertThat(calls.get()).as("首轮 + 2 次重试 = 3").isEqualTo(AiRetryDefaults.MAX_ATTEMPTS);
	}

	@Test
	void permanentFailuresAreNotRetried() {
		AtomicInteger calls = new AtomicInteger();
		RetryTemplate template = fastTemplate();

		assertThatThrownBy(() -> template.execute((RetryCallback<Void, RuntimeException>) ctx -> {
			calls.incrementAndGet();
			throw new NonTransientAiException("上游 400");
		})).isInstanceOf(NonTransientAiException.class);

		assertThat(calls.get()).as("4xx 重发一次只会再坏一次").isEqualTo(1);
	}

	@Test
	void networkFailuresAreRetried() {
		AtomicInteger calls = new AtomicInteger();
		RetryTemplate template = fastTemplate();

		assertThatThrownBy(() -> template.execute((RetryCallback<Void, RuntimeException>) ctx -> {
			calls.incrementAndGet();
			throw new ResourceAccessException("connection reset", new IOException("reset"));
		})).isInstanceOf(ResourceAccessException.class);

		assertThat(calls.get()).as("网络层抖动 = 瞬时").isEqualTo(AiRetryDefaults.MAX_ATTEMPTS);
	}

	@Test
	void unknownRuntimeFailuresAreNotRetried() {
		AtomicInteger calls = new AtomicInteger();
		RetryTemplate template = fastTemplate();

		assertThatThrownBy(() -> template.execute((RetryCallback<Void, RuntimeException>) ctx -> {
			calls.incrementAndGet();
			throw new IllegalStateException("provider 注册表未装配");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(calls.get()).as("未知异常不在白名单 ⇒ 不重试").isEqualTo(1);
	}

	@Test
	void transientFailureIsRetriedThenSucceeds() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		RetryTemplate template = fastTemplate();

		String result = template.execute((RetryCallback<String, RuntimeException>) ctx -> {
			if (calls.incrementAndGet() < 2) {
				throw new TransientAiException("上游 502");
			}
			return "ok";
		});

		assertThat(result).isEqualTo("ok");
		assertThat(calls.get()).isEqualTo(2);
	}

	// ── 退避：基线序列 + 抖动区间 + 真的睡了 ──────────────────────────────

	@Test
	void backoffBaseDoublesFromHalfASecondAndCapsAtFiveSeconds() {
		assertThat(AiRetryDefaults.baseMs(1)).isEqualTo(500L);
		assertThat(AiRetryDefaults.baseMs(2)).isEqualTo(1_000L);
		assertThat(AiRetryDefaults.baseMs(3)).isEqualTo(2_000L);
		assertThat(AiRetryDefaults.baseMs(4)).as("4s（尚未到顶）").isEqualTo(4_000L);
		assertThat(AiRetryDefaults.baseMs(5)).as("上限 5s").isEqualTo(5_000L);
		assertThat(AiRetryDefaults.baseMs(9)).as("溢出不回绕").isEqualTo(5_000L);
	}

	@Test
	void backoffSleepsWithinTheJitterWindow() {
		List<Long> attemptAt = new ArrayList<>();
		RetryTemplate template = AiRetryDefaults.retryTemplate(() -> 0.5d); // 抖动中性 ⇒ 精确 500ms

		assertThatThrownBy(() -> template.execute((RetryCallback<Void, RuntimeException>) ctx -> {
			attemptAt.add(System.nanoTime());
			throw new TransientAiException("上游 503");
		})).isInstanceOf(TransientAiException.class);

		assertThat(attemptAt).hasSize(AiRetryDefaults.MAX_ATTEMPTS);
		long firstGapMs = Duration.ofNanos(attemptAt.get(1) - attemptAt.get(0)).toMillis();
		long secondGapMs = Duration.ofNanos(attemptAt.get(2) - attemptAt.get(1)).toMillis();
		assertThat(firstGapMs).as("第 1 次退避 ≈ 500ms").isBetween(450L, 900L);
		assertThat(secondGapMs).as("第 2 次退避 ≈ 1000ms").isBetween(950L, 1_400L);
	}

	// ── 与父预算的关系（摘要 60s） ────────────────────────────────────────

	@Test
	void worstCaseBackoffStaysWithinTwentyPercentOfTheSummaryBudget() {
		long budget = AiRetryDefaults.totalBackoffUpperBoundMs();
		assertThat(budget).as("最坏退避总和").isEqualTo(1_875L);
		assertThat((double) budget).as("占比 ≤ 父预算 20%")
			.isLessThanOrEqualTo(SUMMARY_TIMEOUT_MS * 0.2d);
	}

	@Test
	void constantMatchesTheFormula() {
		assertThat(AiRetryDefaults.MAX_TOTAL_BACKOFF_MS).isEqualTo(AiRetryDefaults.totalBackoffUpperBoundMs());
	}

	// ── 装配：自有 bean 覆盖 autoconfig 缺省 ───────────────────────────────

	@Test
	void configExposesTheSameTemplateAsTheFallback() throws Exception {
		RetryTemplate bean = new AiRetryConfig().retryTemplate();
		AtomicInteger calls = new AtomicInteger();
		assertThatThrownBy(() -> bean.execute((RetryCallback<Void, RuntimeException>) ctx -> {
			calls.incrementAndGet();
			throw new TransientAiException("上游 503");
		})).isInstanceOf(TransientAiException.class);
		assertThat(calls.get()).as("bean 侧同样是 3 次尝试（不是 autoconfig 的 10 次）")
			.isEqualTo(AiRetryDefaults.MAX_ATTEMPTS);
	}

	/** 测试专用：把退避缩到 1ms 级（不改变策略面，只省时间）。 */
	private static RetryTemplate fastTemplate() {
		RetryTemplate template = new RetryTemplate();
		template.setRetryPolicy(new org.springframework.retry.policy.SimpleRetryPolicy(AiRetryDefaults.MAX_ATTEMPTS,
				AiRetryDefaults.transientFailures(), true, false));
		template.setBackOffPolicy(new org.springframework.retry.backoff.NoBackOffPolicy());
		return template;
	}

}
