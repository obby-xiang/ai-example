package com.example.configmgr.ai.gate;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.common.AiStateUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * <b>park 批：派发失败的有界重试</b>（{@code POOL_SATURATED} / {@code SESSION_BUSY}），
 * 以及 <b>W-21：取消状态此刻不可确认</b>（{@code CANCEL_STATE_UNKNOWN}）同待遇。
 * （{@code STATE_UNKNOWN} <b>不</b>同待遇：它可能来自条目 JSON 的永久损坏，见
 * {@link #stateUnknownIsNotRetried()}。）
 *
 * <p>为什么必须锁：park 之后"推进这一轮"的责任在派发方。续跑池饱和一次就放弃，会让用户
 * <b>已经批准</b>的卡停在"已决 + 无人执行"—— 这是本批明确禁止的形态（"不能批准已落无人执行"）。
 * 取消标志读不出来（进程级 5s 退避窗内的每一拍都是 {@code UNKNOWN}）同属这一类：不可确认不是终态，
 * 一次失败就搁置会让<b>已结算</b>的前端工具没有物化路径。
 * 重试必须有界：死卡不能永久占用调度线程；用尽后只 ERROR 留痕（可观测），卡保持 SUSPENDED，
 * 下一次交互/重挂会再触发（启动扫描直接调 {@code ResumeService}，不经确认门、也不重置预算）。
 *
 * <p><b>上限按"外部触发周期"计</b>：一个周期内多链共享同一份预算，耗尽即钉死（不会因为某条链
 * 晚一拍醒来又拿到新预算）；只有外部新触发（决策/放弃/回灌、到期裁决、重挂自愈对账）才清零重算。
 * {@link #persistentUnknownAcrossOverlappingChainsStaysBounded()} 与
 * {@link #exhaustedCycleIsRearmedOnlyByAnExternalTrigger()} 钉这两点。
 */
class ResumeDispatchRetryTest {

	private static final String RUN_ID = "run-dispatch-retry";

	private static final String SESSION_ID = "s-dispatch-retry";

	/**
	 * 取消标志降级退避窗：与 {@code CancellationRegistry.DEGRADED_POLL_BACKOFF_MS} <b>同值</b>
	 * （那边是 private，取不到）。<b>任一处改动必须同步这里</b>，否则本类"节拍越过 5s 窗"的断言会静默失效。
	 */
	private static final long CANCELLATION_BACKOFF_WINDOW_MS = 5_000L;

	/** 排空"只排队、不执行"的调度器：防止待办无限增生时用例自己跑不完。 */
	private static final int DRAIN_GUARD = 1_000;

	private final AiProperties properties = new AiProperties();

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final List<Duration> scheduled = new ArrayList<>();

	private final AtomicInteger resumeCalls = new AtomicInteger();

	private ResumeService resumeService;

	private ConfirmGate gate;

	@BeforeEach
	void setUp() {
		this.properties.getPark().setEnabled(true);
		this.resumeService = mock(ResumeService.class);
		// 同步触发池 + "立即执行"的重试调度器：把"排期"折叠成同步调用，断言次数即可。
		ConfirmGate.RetryScheduler immediate = (task, delay) -> {
			this.scheduled.add(delay);
			task.run();
			return () -> {
			};
		};
		this.gate = gateWith(immediate);
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);
	}

	@SuppressWarnings("unchecked")
	private ConfirmGate gateWith(ConfirmGate.RetryScheduler scheduler) {
		return gateWith(this.store, scheduler);
	}

	@SuppressWarnings("unchecked")
	private ConfirmGate gateWith(RunStore runStore, ConfirmGate.RetryScheduler scheduler) {
		ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(this.resumeService);
		return new ConfirmGate(runStore, this.registry, this.properties,
				mock(CancellationRegistry.class), new ToolActivityBeacon(), List.of(), provider, Runnable::run, null,
				scheduler);
	}

	/** 只排队、不执行的调度器：把"拍"交给用例自己排空（才能模拟拍与拍之间的临时故障）。 */
	private ConfirmGate queueingGate(List<Runnable> queue) {
		return gateWith((task, delay) -> {
			this.scheduled.add(delay);
			queue.add(task);
			return () -> {
			};
		});
	}

	private ResumeService.ResumeResult result(ResumeService.Outcome outcome) {
		return new ResumeService.ResumeResult(outcome, Map.of("runId", RUN_ID, "resumed", false));
	}

	/** 排空"只排队、不执行"的调度器：每一拍都可能再排下一拍，用 {@link #DRAIN_GUARD} 兜住无限增生。 */
	private void drain(List<Runnable> queue) {
		int guard = 0;
		while (!queue.isEmpty() && guard++ < DRAIN_GUARD) {
			queue.remove(0).run();
		}
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

	/** I3：用尽后<b>可再触发</b>（下一次决策/重挂<b>重新计数</b>；启动扫描直接调 ResumeService，不重置本预算），
	 * 不是"永久只留一条 ERROR"。 */
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

	/**
	 * <b>W-21：取消状态不可确认（{@code CANCEL_STATE_UNKNOWN}）与池满同待遇。</b>
	 *
	 * <p>死卡兜底续跑的入口复查要读取消标志。{@code CancellationRegistry} 读失败后会进入进程级
	 * <b>5s 退避窗</b>，窗内的每一拍都返回 {@code UNKNOWN}（不把"没读 Redis"伪装成"未取消"），
	 * 于是续跑拿到 {@code CANCEL_STATE_UNKNOWN} + {@code resumed=false}。这不是终态、也不是
	 * "有人接管" —— 只是这一拍没有可确认的驱动者；一次失败就永久搁置，已结算的前端工具
	 * （{@code FRONTEND_RESULT}）就没有物化路径（W-21 的"历史里缺 TOOL 段"）。
	 */
	@Test
	void unknownCancelStateIsRetriedAndThenAccepted() {
		when(this.resumeService.resume(anyString())).thenAnswer(invocation -> {
			int call = this.resumeCalls.incrementAndGet();
			return call == 1
					? this.result(ResumeService.Outcome.CANCEL_STATE_UNKNOWN)
					: new ResumeService.ResumeResult(ResumeService.Outcome.ACCEPTED,
							Map.of("runId", RUN_ID, "resumed", true));
		});

		ConfirmGate.ResumeTrigger trigger = this.gate.triggerResumeForParked(RUN_ID);

		assertThat(trigger.triggered()).isTrue();
		assertThat(this.resumeCalls.get()).as("UNKNOWN 一次后必须自动重派发并成功").isEqualTo(2);
		assertThat(this.scheduled).containsExactly(Duration.ofSeconds(2));
	}

	/** 一直不可确认 ⇒ 仍走<b>同一个</b>有界重派发（1 次首发 + max 次重试），不无限重排。 */
	@Test
	void persistentUnknownCancelStateIsRetriedBoundedly() {
		when(this.resumeService.resume(anyString())).thenAnswer(invocation -> {
			this.resumeCalls.incrementAndGet();
			return this.result(ResumeService.Outcome.CANCEL_STATE_UNKNOWN);
		});

		this.gate.triggerResumeForParked(RUN_ID);

		assertThat(this.resumeCalls.get()).isEqualTo(1 + this.properties.getPark().getMaxDispatchRetries());
		assertThat(this.scheduled).hasSize(this.properties.getPark().getMaxDispatchRetries());
		assertThat(this.scheduled.get(0)).isEqualTo(this.properties.getPark().getDispatchRetryDelay());
		assertThat(this.scheduled.get(this.scheduled.size() - 1))
			.as("封顶不变：不为 UNKNOWN 单独延长超时").isEqualTo(this.properties.getPark().getDispatchRetryMaxDelay());
	}

	/**
	 * 窗口算术：重派发节拍必须能<b>越过</b> 5s 退避窗（{@code CancellationRegistry.DEGRADED_POLL_BACKOFF_MS}，
	 * 本类用同值的 {@link #CANCELLATION_BACKOFF_WINDOW_MS} 建模）。
	 *
	 * <p>既有节拍 2s→4s→8s…（累计 2s / 6s / 14s…）在第 2 拍（6s）就已经出窗 ⇒
	 * <b>不需要改任何延迟</b>。本用例用虚拟时钟钉住这一点：窗内读回 UNKNOWN、出窗后读回确定值，
	 * 同一轮必须自动收敛。
	 */
	@Test
	void unknownCancelStateRetryCadenceCrossesTheFiveSecondBackoffWindow() {
		long[] elapsedMs = {0};
		ConfirmGate.RetryScheduler virtualClock = (task, delay) -> {
			elapsedMs[0] += delay.toMillis();
			this.scheduled.add(delay);
			task.run();
			return () -> {
			};
		};
		ConfirmGate clockedGate = gateWith(virtualClock);
		when(this.resumeService.resume(anyString())).thenAnswer(invocation -> {
			this.resumeCalls.incrementAndGet();
			return elapsedMs[0] < CANCELLATION_BACKOFF_WINDOW_MS
					? this.result(ResumeService.Outcome.CANCEL_STATE_UNKNOWN)
					: new ResumeService.ResumeResult(ResumeService.Outcome.ACCEPTED,
							Map.of("runId", RUN_ID, "resumed", true));
		});

		clockedGate.triggerResumeForParked(RUN_ID);

		assertThat(this.scheduled).as("窗内那一拍（2s）仍在退避窗内，第 2 个节拍（累计 6s）才出窗")
			.containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4));
		assertThat(this.resumeCalls.get()).as("首发 1 次 + 2 次重试 ⇒ 第 3 拍成功收敛").isEqualTo(3);
		assertThat(elapsedMs[0]).as("成功那一拍必须已在 5s 窗之外").isGreaterThanOrEqualTo(CANCELLATION_BACKOFF_WINDOW_MS);
		assertThat(elapsedMs[0]).as("累计 6s 就够（既有节拍），不靠拉长超时换绿").isLessThan(30_000L);
	}

	/**
	 * <b>反向锁：{@code STATE_UNKNOWN} 不进重试集。</b>
	 *
	 * <p>它看着与 {@code CANCEL_STATE_UNKNOWN} 同义，但产地不同：{@code ResumeService} 入口那个
	 * {@code catch (RuntimeException)} 同时包住"待决条目读失败（瞬时）"和"条目 JSON 反序列化失败
	 * （{@code IllegalStateException}，永久数据损坏）"。后者的重试必然全废，只会刷 15 条 ERROR 与
	 * 一串无意义续跑尝试 —— 不可确认 ≠ 等一拍就好。W-21 也不需要它（重建循环里的不可用翻译走
	 * {@code CANCEL_STATE_UNKNOWN}）。
	 */
	@Test
	void stateUnknownIsNotRetried() {
		when(this.resumeService.resume(anyString())).thenAnswer(invocation -> {
			this.resumeCalls.incrementAndGet();
			return this.result(ResumeService.Outcome.STATE_UNKNOWN);
		});

		this.gate.triggerResumeForParked(RUN_ID);

		assertThat(this.resumeCalls.get()).as("STATE_UNKNOWN 不得按瞬时故障重试").isEqualTo(1);
		assertThat(this.scheduled).as("STATE_UNKNOWN 不得排期重试").isEmpty();
	}

	/**
	 * 反向锁：确定结局<b>不得</b>被套上重试。
	 *
	 * <p>{@code PROVIDER_GONE} 是"这一轮没法按原样继续"（换模型重跑会让历史说谎）、
	 * {@code NOT_FOUND} / {@code ALREADY_DONE} / {@code ALREADY_CANCELLED} 是收尾、
	 * {@code ALREADY_RUNNING} / {@code PENDING_UNRESOLVED} / {@code ACCEPTED} 是"已有人管" ——
	 * 重派发只会制造无意义风暴。{@code STATE_UNKNOWN} 也在此列（可能是永久性条目损坏，见
	 * {@link #stateUnknownIsNotRetried()}）。
	 */
	@Test
	void deterministicOutcomesAreNotRetried() {
		List<ResumeService.Outcome> deterministic = List.of(ResumeService.Outcome.ACCEPTED,
				ResumeService.Outcome.ALREADY_RUNNING, ResumeService.Outcome.PENDING_UNRESOLVED,
				ResumeService.Outcome.NOT_FOUND, ResumeService.Outcome.ALREADY_DONE,
				ResumeService.Outcome.ALREADY_CANCELLED, ResumeService.Outcome.PROVIDER_GONE,
				ResumeService.Outcome.LEASE_HELD_ELSEWHERE, ResumeService.Outcome.STATE_UNKNOWN);

		for (ResumeService.Outcome outcome : deterministic) {
			ConfirmGate fresh = gateWith((task, delay) -> {
				this.scheduled.add(delay);
				task.run();
				return () -> {
				};
			});
			when(this.resumeService.resume(anyString())).thenAnswer(invocation -> {
				this.resumeCalls.incrementAndGet();
				return this.result(outcome);
			});
			// 计数在打桩之后再清零：when(...) 自身会触发上一个 thenAnswer（Mockito 的打桩调用）。
			this.resumeCalls.set(0);
			this.scheduled.clear();

			fresh.triggerResumeForParked(RUN_ID);

			assertThat(this.resumeCalls.get()).as("%s 不得重派发", outcome).isEqualTo(1);
			assertThat(this.scheduled).as("%s 不得排期重试", outcome).isEmpty();
		}
	}

	/**
	 * <b>Important 1：多链 + 持续故障下，重派发总数不得超过本触发周期的上限。</b>
	 *
	 * <p>W-21 的真实形态就是同 run 多链：44.102 / 44.108 / 44.110 三次死卡兜底各派发一次，
	 * 三条链的后续节拍还挂在调度器里。旧实现耗尽时把计数 {@code remove} 掉，于是<b>每条晚醒的链</b>
	 * 都会 {@code computeIfAbsent} 拿到一整套新预算 ⇒ 一个触发周期内的总次数是
	 * <b>15 × 链数</b>（三次触发就是 45 次，本用例在旧实现上实测 45），而"15 次后仍未成功"的
	 * ERROR 也会重复、说谎。钉死耗尽状态后，本周期总次数 = max + (触发数-1)。
	 *
	 * <p><b>注意 17 只是本场景的重叠队列算术</b>（max + 触发数 − 1），<b>不是全局 cap</b>：
	 * 全局不变式只有两条 —— 每个外部触发周期最多 max 次退避重派发、耗尽后钉死不再增生。
	 * 触发次数越多，"各触发首拍"这一项就越多；用例锁的是"排空即停、不因 remove 增生"。
	 *
	 * <p>排期用"只排队、不执行"的调度器 + 手动排空，才能把并发链的后续拍都算进同一个周期。
	 */
	@Test
	void persistentUnknownAcrossOverlappingChainsStaysBounded() {
		List<Runnable> queue = new ArrayList<>();
		ConfirmGate queueing = gateWith((task, delay) -> {
			this.scheduled.add(delay);
			queue.add(task);
			return () -> {
			};
		});
		when(this.resumeService.resume(anyString())).thenAnswer(invocation -> {
			this.resumeCalls.incrementAndGet();
			return this.result(ResumeService.Outcome.CANCEL_STATE_UNKNOWN);
		});
		Logger gateLogger = (Logger) LoggerFactory.getLogger(ConfirmGate.class);
		ListAppender<ILoggingEvent> logs = new ListAppender<>();
		logs.start();
		gateLogger.addAppender(logs);
		try {
			// 三次外部触发（= W-21 的三次死卡兜底），随后 Redis 一直不可读。
			int triggers = 3;
			for (int i = 0; i < triggers; i++) {
				queueing.triggerResumeForParked(RUN_ID);
			}
			drain(queue);

			int max = this.properties.getPark().getMaxDispatchRetries();
			// 每次外部触发都会清零重算，所以前 triggers-1 次触发的首拍各排一次（随即被覆盖），
			// 最后一个周期拿满 max 次：合计 max + (triggers-1)。关键不在这个数，而在"排空即停、不再增生"。
			int expectedRetries = max + triggers - 1;
			assertThat(this.scheduled).as("持续故障下的重派发总数有界（不因 remove 再造预算而增生）")
				.hasSize(expectedRetries);
			assertThat(queue).as("排空后不得再有积压（耗尽即停，不无限重排）").isEmpty();
			assertThat(this.resumeCalls.get()).as("%d 次首发 + 上述有界重试", triggers)
				.isEqualTo(triggers + expectedRetries);
			long exhaustedLogs = logs.list.stream()
				.filter(event -> event.getFormattedMessage().contains("次有界重试后仍未成功"))
				.count();
			assertThat(exhaustedLogs).as("耗尽 ERROR 恰一条（它之后确实没有再排期，日志不再说谎）").isEqualTo(1);
		}
		finally {
			gateLogger.detachAppender(logs);
		}
	}

	/**
	 * <b>Important 1：耗尽状态不可被内部拍重置，只有外部新触发才 rearm。</b>
	 *
	 * <p>"下一次明确交互可重新触发"要保留，但 rearm 的入口只能是对外的那几个（决策/放弃/回灌、
	 * 到期裁决、重挂自愈对账）。调度器内部的续派（{@code allowRetry=false}）跑到耗尽之后
	 * 必须什么都不做 —— 否则"有界"就只是"直到下一条链醒过来"。启动扫描不在此列：
	 * {@code StartupResumeRunner} 直接调 {@code ResumeService}，不经确认门。
	 */
	@Test
	void exhaustedCycleIsRearmedOnlyByAnExternalTrigger() {
		List<Runnable> queue = new ArrayList<>();
		ConfirmGate queueing = gateWith((task, delay) -> {
			this.scheduled.add(delay);
			queue.add(task);
			return () -> {
			};
		});
		when(this.resumeService.resume(anyString())).thenAnswer(invocation -> {
			this.resumeCalls.incrementAndGet();
			return this.result(ResumeService.Outcome.CANCEL_STATE_UNKNOWN);
		});
		int max = this.properties.getPark().getMaxDispatchRetries();

		// 两条链（两次外部触发）一起把预算耗尽。
		queueing.triggerResumeForParked(RUN_ID);
		queueing.triggerResumeForParked(RUN_ID);
		drain(queue);
		int afterFirstCycle = this.resumeCalls.get();
		assertThat(this.scheduled).as("两条链共用最后那份预算（max + 先前触发的首拍）")
			.hasSize(max + 1);

		// 内部拍：无新排期 ⇒ 再排空也什么都不发生（不 rearm）。
		drain(queue);
		assertThat(this.resumeCalls.get()).as("耗尽后内部拍不得再产生续跑尝试").isEqualTo(afterFirstCycle);

		// 外部新触发 = 新周期：重新拿到一整份预算。
		this.scheduled.clear();
		queueing.triggerResumeForParked(RUN_ID);
		drain(queue);
		assertThat(this.scheduled).as("外部新触发的周期仍拿满 max 次退避重试").hasSize(max);
		assertThat(queue).isEmpty();
		assertThat(this.resumeCalls.get()).isEqualTo(afterFirstCycle + 1 + max);
	}

	/**
	 * <b>agent-194 Important：重派发拍里"快照读临时不可用"不得终结预算链。</b>
	 *
	 * <p>{@code RunStore.get} 对 Redis 断连/超时抛的是 {@code AiStateUnavailableException}
	 * （{@code RunStore.java:1037}，与"值损坏"的 {@code IllegalStateException} 分开）。重试 lambda
	 * 以前把它当普通异常只 WARN 就返回 —— 一次抖动就让 {@code CANCEL_STATE_UNKNOWN} 的整条预算链
	 * 提前结束，轮次永久停在 SUSPENDED（正是 W-21 的收尾形态）。
	 *
	 * <p>本用例：首发 {@code CANCEL_STATE_UNKNOWN} → 第一拍快照读临时失败 → 下一拍恢复 → 走完续跑。
	 * {@code AiStateUnavailableException} 是 {@code IllegalStateException} 的子类，与永久性数据损坏
	 * 区分得开，所以"继续同一周期"不会把永久故障也当成瞬时的。
	 */
	@Test
	void transientSnapshotReadFailureInARetryBeatKeepsTheBoundedCycleAlive() {
		List<Runnable> queue = new ArrayList<>();
		RunStore flaky = spy(this.store);
		ConfirmGate queueing = gateWith(flaky, (task, delay) -> {
			this.scheduled.add(delay);
			queue.add(task);
			return () -> {
			};
		});
		when(this.resumeService.resume(anyString())).thenAnswer(invocation -> this.resumeCalls.incrementAndGet() == 1
				? this.result(ResumeService.Outcome.CANCEL_STATE_UNKNOWN)
				: new ResumeService.ResumeResult(ResumeService.Outcome.ACCEPTED,
						Map.of("runId", RUN_ID, "resumed", true)));

		queueing.triggerResumeForParked(RUN_ID);
		// 第一拍：快照读临时不可用；下一拍恢复（doCallRealMethod = 回落真实 InMemoryRunStore）。
		doThrow(new AiStateUnavailableException("无法读取轮次快照 " + RUN_ID + "（Redis 不可用）"))
			.doCallRealMethod()
			.when(flaky)
			.get(RUN_ID);
		drain(queue);

		assertThat(this.scheduled).as("抖动那一拍不得吞掉后续节拍：仍在同一份预算内继续")
			.containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4));
		assertThat(this.resumeCalls.get()).as("首发 UNKNOWN + 恢复后 ACCEPTED（抖动拍不消耗一次续跑）").isEqualTo(2);
	}

	/**
	 * 同上，但临时不可用发生在 {@code ResumeService.resume} 内部（{@code runResume} 的
	 * {@code catch (Exception)}）：以前只 {@code log.error} + 放租就结束，轮次同样留 SUSPENDED。
	 */
	@Test
	void transientResumeFailureInARetryBeatKeepsTheBoundedCycleAlive() {
		List<Runnable> queue = new ArrayList<>();
		ConfirmGate queueing = queueingGate(queue);
		when(this.resumeService.resume(anyString())).thenAnswer(invocation -> {
			int call = this.resumeCalls.incrementAndGet();
			if (call == 1) {
				return this.result(ResumeService.Outcome.CANCEL_STATE_UNKNOWN);
			}
			if (call == 2) {
				throw new AiStateUnavailableException("无法读取待决条目批次：" + RUN_ID);
			}
			return new ResumeService.ResumeResult(ResumeService.Outcome.ACCEPTED,
					Map.of("runId", RUN_ID, "resumed", true));
		});

		queueing.triggerResumeForParked(RUN_ID);
		drain(queue);

		assertThat(this.scheduled).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4));
		assertThat(this.resumeCalls.get()).as("UNKNOWN → 一拍临时失败 → ACCEPTED").isEqualTo(3);
	}

	/**
	 * 持续的状态读取失败仍走<b>同一个</b>有界周期：抖动不会变成新的预算来源。
	 *
	 * <p>（上限语义不变：max 次后钉死；这里只证明"临时失败也计入同一份预算"。）
	 */
	@Test
	void persistentSnapshotReadFailureStaysWithinTheSameCycleBudget() {
		List<Runnable> queue = new ArrayList<>();
		RunStore flaky = spy(this.store);
		ConfirmGate queueing = gateWith(flaky, (task, delay) -> {
			this.scheduled.add(delay);
			queue.add(task);
			return () -> {
			};
		});
		when(this.resumeService.resume(anyString())).thenAnswer(invocation -> {
			this.resumeCalls.incrementAndGet();
			return this.result(ResumeService.Outcome.CANCEL_STATE_UNKNOWN);
		});

		queueing.triggerResumeForParked(RUN_ID);
		doThrow(new AiStateUnavailableException("无法读取轮次快照 " + RUN_ID + "（Redis 不可用）")).when(flaky)
			.get(RUN_ID);
		drain(queue);

		assertThat(this.scheduled).as("持续抖动也只有 max 次重派发（同一周期，不增生）")
			.hasSize(this.properties.getPark().getMaxDispatchRetries());
		assertThat(queue).as("耗尽即停").isEmpty();
		assertThat(this.resumeCalls.get()).as("抖动期一拍都没有真正进到 resume").isEqualTo(1);
	}

}
