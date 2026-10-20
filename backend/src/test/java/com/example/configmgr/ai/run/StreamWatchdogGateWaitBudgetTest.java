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
 * 人机预算分离（用户裁决 2026-10-20 第 3 条）的回归锁：
 * <b>挂起等待（{@code awaitDecision}/{@code awaitFrontendResult} 等待期）不计入
 * {@code app.ai.resilience.total-budget}</b>。
 *
 * <h2>锁的是什么</h2>
 * <ol>
 * <li>{@link ToolActivityBeacon#gateWaitMs(String)} 的累计语义：等待进行中<b>实时</b>增长（含当前窗口），
 * 结束后冻结为已结束窗口之和；</li>
 * <li>{@link StreamWatchdog} 的总预算判据按它扣除：等待跨过一个整预算仍不判 {@code TOTAL_BUDGET}，
 * 等待结束后计时恢复并把预算正常判掉；</li>
 * <li>没有等待时的预算语义不变（不因本改动放松）—— 否则"扣除"会退化成"永不超预算"。</li>
 * </ol>
 *
 * <p>为什么用真时钟：判定节拍本身就是墙钟（{@code TICK_MILLIS=1000}），把时钟抽象只为了测试
 * 会让"节拍里扣的是不是真的等待时长"这条事实失去证据。故本类用秒级窗口，总耗时约 8s。
 */
class StreamWatchdogGateWaitBudgetTest {

	private static final String RUN_ID = "run-gate-wait-budget";

	private final ToolActivityBeacon beacon = new ToolActivityBeacon();

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final AtomicReference<StreamViolationException> violation = new AtomicReference<>();

	private ScheduledExecutorService scheduler;

	private StreamWatchdog watchdog;

	@BeforeEach
	void setUp() throws Exception {
		this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "watchdog-gate-wait-test");
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

	@Test
	void beaconAccumulatesGateWaitIncludingTheOngoingWindow() throws Exception {
		this.beacon.beginGateWait(RUN_ID);
		Thread.sleep(150L);
		long duringWait = this.beacon.gateWaitMs(RUN_ID);
		assertThat(duringWait).as("等待进行中：包含当前窗口（看门狗每拍都要读到已扣的量）")
				.isGreaterThanOrEqualTo(100L);

		this.beacon.endGateWait(RUN_ID);
		long afterWait = this.beacon.gateWaitMs(RUN_ID);
		assertThat(afterWait).isGreaterThanOrEqualTo(duringWait);
		Thread.sleep(120L);
		assertThat(this.beacon.gateWaitMs(RUN_ID))
				.as("等待结束后冻结为已结束窗口之和，不再增长")
				.isLessThan(afterWait + 100L);
	}

	@Test
	void totalBudgetPausesWhileGateWaits() throws Exception {
		this.watchdog = watchdogWith(Duration.ofSeconds(2));

		this.beacon.beginGateWait(RUN_ID);
		// 跨过一个整预算（2s）再等 0.5s：等待期不计预算
		Thread.sleep(2500L);
		assertThat(this.violation.get())
				.as("挂起等待期间不得判 TOTAL_BUDGET（等人的时间不计入预算）")
				.isNull();

		this.beacon.endGateWait(RUN_ID);
		// 等待结束后计时恢复：0.5s（等待前已用）+ 3.2s 新增 > 2s 预算
		Thread.sleep(3200L);
		assertThat(this.violation.get())
				.as("等待结束后预算照常判定，且 code 为 TOTAL_BUDGET")
				.isNotNull();
		assertThat(this.violation.get().getCode()).isEqualTo(StreamViolationException.TOTAL_BUDGET);
	}

	@Test
	void totalBudgetStillFiresWithoutGateWait() throws Exception {
		this.watchdog = watchdogWith(Duration.ofSeconds(1));

		Thread.sleep(2200L);

		assertThat(this.violation.get()).as("没有挂起等待时的预算语义不变").isNotNull();
		assertThat(this.violation.get().getCode()).isEqualTo(StreamViolationException.TOTAL_BUDGET);
	}

	private StreamWatchdog watchdogWith(Duration totalBudget) {
		AiProperties.Resilience config = new AiProperties.Resilience();
		config.setTotalBudget(totalBudget);
		// 静默判据不参与本类讨论：拉到 1 小时，确保被判的只可能是总预算
		config.setFirstByteTimeout(Duration.ofHours(1));
		config.setInterEventTimeout(Duration.ofHours(1));
		return new StreamWatchdog(RUN_ID, this.scheduler, config, this.beacon, this.cancellations,
				System.currentTimeMillis(), this.violation::set);
	}

}
