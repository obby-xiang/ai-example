package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>park 批：派发失败的有界重试</b>（{@code POOL_SATURATED} / {@code SESSION_BUSY}）。
 *
 * <p>为什么必须锁：park 之后"推进这一轮"的责任在派发方。续跑池饱和一次就放弃，会让用户
 * <b>已经批准</b>的卡停在"已决 + 无人执行"—— 这是本批明确禁止的形态（"不能批准已落无人执行"）。
 * 重试必须有界：死卡不能永久占用调度线程；用尽后只 ERROR 留痕（可观测），卡保持 SUSPENDED，
 * 下一次交互/启动扫描会再触发。
 */
class ResumeDispatchRetryTest {

	private static final String RUN_ID = "run-dispatch-retry";

	private static final String SESSION_ID = "s-dispatch-retry";

	private final AiProperties properties = new AiProperties();

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final List<Duration> scheduled = new ArrayList<>();

	private final AtomicInteger resumeCalls = new AtomicInteger();

	private ResumeService resumeService;

	private ConfirmGate gate;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		this.properties.getPark().setEnabled(true);
		this.resumeService = mock(ResumeService.class);
		ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(this.resumeService);
		// 同步触发池 + "立即执行"的重试调度器：把"排期"折叠成同步调用，断言次数即可。
		ConfirmGate.RetryScheduler immediate = (task, delay) -> {
			this.scheduled.add(delay);
			task.run();
			return () -> {
			};
		};
		this.gate = new ConfirmGate(this.store, this.registry, this.properties,
				mock(CancellationRegistry.class), new ToolActivityBeacon(), List.of(), provider, Runnable::run,
				null, immediate);
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);
	}

	/** 池满一次 ⇒ 自动重派发一次并成功（不需要用户再点一次批准）。 */
	@Test
	void oneSaturationIsRetriedAndThenAccepted() {
		when(this.resumeService.resume(anyString())).thenAnswer(invocation -> {
			int call = this.resumeCalls.incrementAndGet();
			return call == 1
					? new ResumeService.ResumeResult(ResumeService.Outcome.POOL_SATURATED,
							Map.of("runId", RUN_ID, "resumed", false))
					: new ResumeService.ResumeResult(ResumeService.Outcome.ACCEPTED,
							Map.of("runId", RUN_ID, "resumed", true));
		});

		ConfirmGate.ResumeTrigger trigger = this.gate.triggerResumeForParked(RUN_ID);

		assertThat(trigger.triggered()).isTrue();
		assertThat(this.resumeCalls.get()).as("池满一次后必须自动重试并成功").isEqualTo(2);
		assertThat(this.scheduled).containsExactly(Duration.ofSeconds(2));
	}

	/** 一直饱和 ⇒ 重试有界（1 次首发 + max 次重试），不无限重排。 */
	@Test
	void persistentSaturationIsBounded() {
		when(this.resumeService.resume(anyString())).thenAnswer(invocation -> {
			this.resumeCalls.incrementAndGet();
			return new ResumeService.ResumeResult(ResumeService.Outcome.POOL_SATURATED,
					Map.of("runId", RUN_ID, "resumed", false));
		});

		this.gate.triggerResumeForParked(RUN_ID);

		assertThat(this.resumeCalls.get()).as("1 次首发 + max 次重试（有界）")
			.isEqualTo(1 + this.properties.getPark().getMaxDispatchRetries());
		assertThat(this.scheduled).hasSize(this.properties.getPark().getMaxDispatchRetries());
		assertThat(this.scheduled).as("指数退避：2s 起，封顶 30s（覆盖正常轮时长，而不是 500ms×3）")
			.containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8),
					Duration.ofSeconds(16), Duration.ofSeconds(30), Duration.ofSeconds(30),
					Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(30),
					Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(30),
					Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(30));
	}

	/**
	 * 窗口算术：重派发总窗必须覆盖一次正常轮的收尾窗口（{@code total-budget} = 300s）。
	 *
	 * <p>15 次 = 2+4+8+16+30×11 = <b>360s</b>（不是 390s：四个未封顶档共 30s，其余 11 档各 30s）。
	 * 360s 覆盖 300s + 60s 余量；10 次的 210s 覆盖不到任何一次真实轮的收尾，
	 * 于是"已决"的卡会在驱动权恢复前就停止重派发。
	 */
	@Test
	void retryWindowCoversOneTurnTotalBudget() {
		when(this.resumeService.resume(anyString())).thenAnswer(invocation -> {
			this.resumeCalls.incrementAndGet();
			return new ResumeService.ResumeResult(ResumeService.Outcome.POOL_SATURATED,
					Map.of("runId", RUN_ID, "resumed", false));
		});

		this.gate.triggerResumeForParked(RUN_ID);

		Duration window = this.scheduled.stream().reduce(Duration.ZERO, Duration::plus);
		Duration totalBudget = this.properties.getResilience().getTotalBudget();
		assertThat(this.properties.getPark().getMaxDispatchRetries()).as("15 次重试（2+4+8+16+30×11）")
			.isEqualTo(15);
		assertThat(window).as("总窗必须覆盖 total-budget 300s，且留足余量").isEqualTo(Duration.ofSeconds(360));
		assertThat(window).isGreaterThan(totalBudget);
		assertThat(window.minus(totalBudget)).as("余量 ≥ 30s").isGreaterThanOrEqualTo(Duration.ofSeconds(30));
		assertThat(this.scheduled.get(this.scheduled.size() - 1))
			.as("封顶不变：30s（不把等待搬回工作线程，也不新增调度器）")
			.isEqualTo(this.properties.getPark().getDispatchRetryMaxDelay());
	}

	/** I3：用尽后<b>可再触发</b>（下一次决策/重挂/启动扫描重新计数），不是"永久只留一条 ERROR"。 */
	@Test
	void exhaustedRetriesCanBeRearmedByANewTrigger() {
		when(this.resumeService.resume(anyString())).thenAnswer(invocation -> {
			this.resumeCalls.incrementAndGet();
			return new ResumeService.ResumeResult(ResumeService.Outcome.POOL_SATURATED,
					Map.of("runId", RUN_ID, "resumed", false));
		});

		this.gate.triggerResumeForParked(RUN_ID);
		int afterFirst = this.resumeCalls.get();
		assertThat(afterFirst).isEqualTo(1 + this.properties.getPark().getMaxDispatchRetries());

		this.gate.triggerResumeForParked(RUN_ID);

		assertThat(this.resumeCalls.get())
			.as("新一轮触发重新计数（仍是有界的），不会停在'已决 + 无人执行'")
			.isEqualTo(afterFirst + 1 + this.properties.getPark().getMaxDispatchRetries());
	}

	/** 会话被另一轮占着（SESSION_BUSY）与池满同待遇：有界重派发，不停在"已决无人执行"。 */
	@Test
	void sessionBusyIsRetriedBoundedlyToo() {
		when(this.resumeService.resume(anyString())).thenAnswer(invocation -> {
			int call = this.resumeCalls.incrementAndGet();
			return call == 1
					? new ResumeService.ResumeResult(ResumeService.Outcome.SESSION_BUSY,
							Map.of("runId", RUN_ID, "resumed", false))
					: new ResumeService.ResumeResult(ResumeService.Outcome.ACCEPTED,
							Map.of("runId", RUN_ID, "resumed", true));
		});

		this.gate.triggerResumeForParked(RUN_ID);

		assertThat(this.resumeCalls.get()).isEqualTo(2);
	}

}
