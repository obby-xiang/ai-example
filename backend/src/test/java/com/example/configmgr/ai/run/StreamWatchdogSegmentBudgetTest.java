package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 段级预算重置式（决策看板 {@code docs/决策看板.md:14}，2026-10-21）：
 * {@code TOTAL_BUDGET} <b>码名保留、语义改为「段」</b> —— 判据从「轮开始 − 挂起等待」改为
 * 「段开始」，<b>挂起点段结束、恢复点（决策/回灌/续跑）段重置</b>。
 *
 * <h2>与既有用例的分工</h2>
 * {@link StreamWatchdogGateWaitBudgetTest} 锁的是「等人不计入」这条<b>仍然成立</b>的旧行为
 * （挂起期间不判、无挂起时照常判），本类只加锁<b>段重置</b>这一条新语义 ——
 * 两者叠加才是完整口径：旧行为断言一律保留，未删未改。
 *
 * <h2>为什么必须用真时钟</h2>
 * 判定节拍（{@link StreamWatchdog#TICK_MILLIS} = 1000ms）本身就是墙钟；把时钟抽象掉，
 * 「段锚点是不是真的在恢复点前移」就失去了证据。故本类用秒级窗口，单例约 5~9s。
 */
class StreamWatchdogSegmentBudgetTest {

	private static final String RUN_ID = "run-segment-budget";

	private final ToolActivityBeacon beacon = new ToolActivityBeacon();

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final AtomicReference<StreamViolationException> violation = new AtomicReference<>();

	private ScheduledExecutorService scheduler;

	private StreamWatchdog watchdog;

	@BeforeEach
	void setUp() {
		this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "watchdog-segment-test");
			thread.setDaemon(true);
			return thread;
		});
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenAnswer(
				invocation -> (AutoCloseable) () -> {
				});
	}

	@AfterEach
	void tearDown() {
		if (this.watchdog != null) {
			this.watchdog.close();
		}
		if (this.scheduler != null) {
			this.scheduler.shutdownNow();
		}
	}

	/**
	 * 恢复点段重置：等待前已用 1.2s + 等待 2.5s + 恢复后 1.5s，预算 2s。
	 *
	 * <p>旧「扣除式」判据 = 墙钟 − 挂起等待 = 5.1 − 2.5 = 2.6s ≥ 2s ⇒ 在 5s 那一拍判
	 * {@code TOTAL_BUDGET}；新「段」判据 = 5.1 − 3.7（恢复点）= 1.4s &lt; 2s ⇒ <b>不判</b>。
	 */
	@Test
	void resumeRestartsSegmentBudget() throws Exception {
		this.watchdog = watchdogWith(Duration.ofSeconds(2));

		Thread.sleep(1200L);
		this.beacon.beginGateWait(RUN_ID);
		Thread.sleep(2500L);
		assertThat(this.violation.get()).as("挂起期间段已结束：不判预算（旧行为保留）").isNull();

		this.beacon.endGateWait(RUN_ID);
		Thread.sleep(1500L);
		assertThat(this.violation.get())
				.as("恢复点段重置：恢复后 1.4s < 2s 预算，不得判 TOTAL_BUDGET（旧扣除式会误判 2.6s）")
				.isNull();
	}

	/**
	 * 连续两段：每次恢复都重新计时，累计量不跨段（1.2s + 1.2s 两段各自 &lt; 2s，旧判据会累到 2.4s+）。
	 */
	@Test
	void eachResumeRestartsSegment() throws Exception {
		this.watchdog = watchdogWith(Duration.ofSeconds(2));

		Thread.sleep(1200L);
		this.beacon.beginGateWait(RUN_ID);
		Thread.sleep(2500L);
		this.beacon.endGateWait(RUN_ID);
		Thread.sleep(1200L);
		assertThat(this.violation.get()).as("第一段 1.4s < 2s").isNull();

		this.beacon.beginGateWait(RUN_ID);
		Thread.sleep(2500L);
		this.beacon.endGateWait(RUN_ID);
		Thread.sleep(1500L);

		assertThat(this.violation.get())
				.as("两次挂起各重置一次：第二段恢复后 1.4s < 2s（旧判据累计 2.4s+ 早已判死）")
				.isNull();
	}

	/** 段重置不等于「永不超时」：同一段内跑满预算仍判 {@code TOTAL_BUDGET}。 */
	@Test
	void firesOnceTheNewSegmentIsSpent() throws Exception {
		this.watchdog = watchdogWith(Duration.ofSeconds(2));

		this.beacon.beginGateWait(RUN_ID);
		Thread.sleep(2200L);
		assertThat(this.violation.get()).as("挂起中不判").isNull();

		this.beacon.endGateWait(RUN_ID);
		Thread.sleep(3200L);

		assertThat(this.violation.get()).as("恢复后本段用满 2s ⇒ 照常判").isNotNull();
		assertThat(this.violation.get().getCode()).isEqualTo(StreamViolationException.TOTAL_BUDGET);
	}

	private StreamWatchdog watchdogWith(Duration totalBudget) {
		AiProperties.Resilience config = new AiProperties.Resilience();
		config.setTotalBudget(totalBudget);
		// 静默判据不参与本类讨论：拉到 1 小时，确保被判的只可能是段预算
		config.setFirstByteTimeout(Duration.ofHours(1));
		config.setInterEventTimeout(Duration.ofHours(1));
		return new StreamWatchdog(RUN_ID, this.scheduler, config, this.beacon, this.cancellations,
				System.currentTimeMillis(), this.violation::set);
	}

}
