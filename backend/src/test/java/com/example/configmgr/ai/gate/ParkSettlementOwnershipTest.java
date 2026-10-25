package com.example.configmgr.ai.gate;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.observe.AiTrace;
import com.example.configmgr.ai.observe.RedisDegradation;
import com.example.configmgr.ai.observe.SettlementObservationStore;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunParkedException;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.SheetAudit;
import com.example.configmgr.ai.tool.SheetSwitches;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>park 结算的所有权边界（跨实例 / 多卡乱序）</b> —— 独立复审 I-1 / I-2 的回归位。
 *
 * <h2>两条序为什么不能靠进程内 {@code woke}</h2>
 * 上一版把"要不要发 {@code tool_call_end}"绑在 {@code woke}（本进程闸门是否被唤醒）上。
 * 那是<b>实例内</b>事实，而 park 之后"谁结算"是<b>跨实例</b>事实：
 * <ul>
 * <li><b>I-1（跨实例双记）</b>：A 把整批请求帧前移公布后仍在跑前序后端工具；用户回填落到 B
 * （B 没有本地闸门 ⇒ {@code woke=false}）⇒ B 发一行；A 随后走到该卡，{@code reserveRequestSlot}
 * 看到已决 ⇒ 继续走到批循环结算处<b>再发一行</b> ⇒ 同一个 {@code toolCallId} 两条 end、
 * {@code readCalls=2}；</li>
 * <li><b>I-2（多卡乱序 0 条）</b>：整批 {@code arm} 预登记闸门让"先回灌第二张卡"命中
 * （{@code woke=true}），决策侧据此不发；随后循环在第一张卡处 park，
 * {@code clearWaiterLatches} 把那张<b>已唤醒、尚无人 await</b> 的闸门一并摘掉 ⇒ 第二张卡再也
 * 没有结算者，永久 0 条 end。</li>
 * </ul>
 *
 * <h2>本批口径</h2>
 * "是否已经结算过"必须是<b>持久、跨实例</b>的证据：发点只有一个——结算行抢占
 * {@code ai:observed:<runId>} 的位（HSETNX，首写者胜）。于是
 * 决策写胜者 / 内联批循环 / 续跑认领胜者 三处随便谁先到都只出一条 end，
 * 与实例亲和性无关；续跑的"复用结果"分支仍然不发（重挂不放大）。
 *
 * <p>装配：<b>两个"实例"共享同一个 {@link InMemoryRunStore}</b>（= 两进程共享一份 Redis），
 * 各自一套 {@code RunRegistry}/{@code ConfirmGate}/{@code SpToolCallingManager}；
 * {@link SheetAudit} 是进程级计数面 —— 这里共用一个实例，于是"两条 end"必然伴随
 * {@code readCalls=2}，两个面一起被钉住。
 */
class ParkSettlementOwnershipTest {

	private static final String RUN_ID = "run-park-ownership";

	private static final String SESSION_ID = "s-park-ownership";

	/** 前序后端工具的 toolCallId（它的执行时长 = 两条序的窗口）。 */
	private static final String BACKEND_CALL = "call-own-backend";

	/** 第一张前端卡（I-1 的"被跨实例回灌"那张；I-2 的"park 落点"那张）。 */
	private static final String FRONT_CALL_A = "call-own-front-a";

	/** 第二张前端卡（I-2 里"先被答复"的那张）。 */
	private static final String FRONT_CALL_B = "call-own-front-b";

	private static final String AGG_TEXT = "按 decimalPlaces 聚合：共 2 组（默认按 count 降序）\n共 5 行";

	private static final Map<String, Object> AGG_META = Map.of("dataClass", "D1", "totalRows", 5, "sheetVer", 1,
			"defCode", "CURRENCY");

	private final AiProperties properties = new AiProperties();

	/** 两个实例共享的一份"Redis"（往返模式：每次读条目都是新副本）。 */
	private final InMemoryRunStore store = new InMemoryRunStore(true);

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final FakeBlockingTool blockingTool = new FakeBlockingTool();

	/** 派发面替身：本类不跑续跑，只钉"谁发结算行"。 */
	private final ResumeService dispatchedResume = mock(ResumeService.class);

	@SuppressWarnings("unchecked")
	private final ObjectProvider<ResumeService> resumeProvider = mock(ObjectProvider.class);

	private SheetAudit audit;

	/** 两个实例共享的"结算观测位"（= 共享一份 Redis 的跨实例仲裁面；进程内退化模式）。 */
	private final SettlementObservationStore observations = new SettlementObservationStore();

	/** 实例 A（首轮驱动方）。 */
	private SpToolCallingManager managerA;

	private ConfirmGate gateA;

	/** 实例 B（只有决策入口、没有 A 的闸门 = 跨实例回灌的落点）。 */
	private ConfirmGate gateB;

	private final Logger traceLogger = (Logger) LoggerFactory.getLogger(AiTrace.class);

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	@BeforeEach
	void setUp() {
		AiTrace.clear();
		this.appender.start();
		this.traceLogger.addAppender(this.appender);
		this.properties.getPark().setEnabled(true);
		// 把前端回执超时推远：本类要测的是"park 之后谁结算"，不是"多久到期"
		this.properties.getHitl().setFrontendToolTimeout(Duration.ofHours(1));
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.sheetDisabled(anyString())).thenReturn(false);
		when(this.toolRegistry.channelOf("blocking_tool")).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.toolRegistry.riskOf("blocking_tool")).thenReturn(ToolMeta.RiskLevel.READ);
		when(this.toolRegistry.channelOf("aggregate_sheet")).thenReturn(ToolMeta.Channel.FRONTEND);
		when(this.toolRegistry.riskOf("aggregate_sheet")).thenReturn(ToolMeta.RiskLevel.READ);
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.of("export", "EXPORT", "EXPORT", 1L, Map.of()).toMap());
		this.store.save(snapshot);
		when(this.resumeProvider.getIfAvailable()).thenReturn(this.dispatchedResume);
		when(this.dispatchedResume.resume(RUN_ID)).thenReturn(new ResumeService.ResumeResult(
				ResumeService.Outcome.NOT_FOUND, Map.of("runId", RUN_ID)));
		this.audit = new SheetAudit(new SheetSwitches(this.properties), this.store);
		// 实例 A：一套 registry/gate/manager（首轮驱动方）
		RunRegistry registryA = new RunRegistry(this.store, this.mapper, null);
		this.gateA = new ConfirmGate(this.store, registryA, this.properties, this.cancellations, this.beacon,
				List.of(), this.resumeProvider, Runnable::run);
		this.gateA.setSheetAudit(this.audit);
		this.gateA.setSettlementObservationStore(this.observations);
		this.managerA = new SpToolCallingManager(this.store, registryA, this.gateA, this.toolRegistry, this.codec,
				this.beacon, this.cancellations, new ToolResultLimiter(this.properties), List.of());
		this.managerA.setSheetAudit(this.audit);
		this.managerA.setSettlementObservationStore(this.observations);
		// 实例 B：共享同一份 store，但<b>没有</b> A 的进程内闸门 —— 与真实跨实例同形
		RunRegistry registryB = new RunRegistry(this.store, this.mapper, null);
		this.gateB = new ConfirmGate(this.store, registryB, this.properties, this.cancellations, this.beacon, List.of(),
				this.resumeProvider, Runnable::run);
		this.gateB.setSheetAudit(this.audit);
		this.gateB.setSettlementObservationStore(this.observations);
	}

	@AfterEach
	void tearDown() {
		this.traceLogger.detachAppender(this.appender);
		AiTrace.clear();
	}

	/**
	 * <b>I-1</b>：本批 {@code [backend_X, frontend_A]}；A 还在跑 backend_X 时，回灌落到<b>另一个实例</b>，
	 * 随后 A 的批循环走到该卡并结算 —— 同一个 {@code toolCallId} 只能有一条 end、{@code readCalls} 只 +1。
	 */
	@Test
	void crossInstanceDecisionDoesNotDoubleSettleWhenTheOriginLoopSettlesLater() throws Exception {
		ExecutorService worker = Executors.newSingleThreadExecutor();
		try {
			Future<?> driving = worker.submit(() -> this.managerA.executeToolCalls(prompt(), response(
					call(BACKEND_CALL, "blocking_tool"), call(FRONT_CALL_A, "aggregate_sheet"))));
			assertThat(this.blockingTool.entered.await(10, TimeUnit.SECONDS))
				.as("复现前提：首轮已开始跑前序后端工具").isTrue();
			assertThat(this.store.pending(RUN_ID, FRONT_CALL_A))
				.as("复现前提：前端卡的请求帧已随整批前移公布（闸门已预登记）").isNotNull();

			ConfirmGate.Submission submission = this.gateB.submitFrontendResult(RUN_ID, FRONT_CALL_A, AGG_TEXT,
					"aie2e", false, "ok", AGG_META, null);
			assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
			assertThat(submission.woke()).as("跨实例：B 没有 A 的闸门").isFalse();

			this.blockingTool.release.countDown();
			driving.get(20, TimeUnit.SECONDS);
		}
		finally {
			this.blockingTool.release.countDown();
			worker.shutdownNow();
		}

		assertThat(this.audit.metrics()).as("readCalls 不得被两个执行体各记一次")
			.containsEntry("readCalls", 1L);
		assertThat(endsFor(FRONT_CALL_A)).as("同一个 toolCallId 只能一条 tool_call_end（跨实例不得两条）")
			.hasSize(1);
		assertThat(this.blockingTool.invocations.get()).as("前序后端工具恰执行一次").isEqualTo(1);
	}

	/**
	 * <b>I-2</b>：本批 {@code [backend_X, frontend_A, frontend_B]}；用户先回灌<b>第二张</b> B
	 * （命中预登记闸门 ⇒ {@code woke=true}），随后循环在 A 处 park 并摘掉全部闸门 ——
	 * B 仍必须恰有一条 end 与一次 {@code readCalls}。
	 */
	@Test
	void multiCardAnsweredOutOfOrderStillSettlesTheAnsweredCardOnce() throws Exception {
		ExecutorService worker = Executors.newSingleThreadExecutor();
		try {
			Future<?> driving = worker.submit(() -> this.managerA.executeToolCalls(prompt(), response(
					call(BACKEND_CALL, "blocking_tool"), call(FRONT_CALL_A, "aggregate_sheet"),
					call(FRONT_CALL_B, "aggregate_sheet"))));
			assertThat(this.blockingTool.entered.await(10, TimeUnit.SECONDS))
				.as("复现前提：首轮已开始跑前序后端工具").isTrue();

			ConfirmGate.Submission submission = this.gateA.submitFrontendResult(RUN_ID, FRONT_CALL_B, AGG_TEXT,
					"aie2e", false, "ok", AGG_META, null);
			assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
			assertThat(submission.woke()).as("复现前提：先答复的第二张卡命中的是预登记闸门").isTrue();

			this.blockingTool.release.countDown();
			assertThatThrownBy(() -> driving.get(20, TimeUnit.SECONDS))
				.as("复现前提：第一张卡未决 ⇒ 循环在它那里 park（线程交还）")
				.hasCauseInstanceOf(RunParkedException.class);
		}
		finally {
			this.blockingTool.release.countDown();
			worker.shutdownNow();
		}

		assertThat(this.store.pending(RUN_ID, FRONT_CALL_A).getStatus()).as("park 落点仍待回灌")
			.isEqualTo(PendingToolCall.PENDING);
		assertThat(endsFor(FRONT_CALL_B)).as("已答复的卡必须有且只有一条 end（park 摘闸门后无人再结算它）")
			.hasSize(1);
		assertThat(endsFor(FRONT_CALL_A)).as("仍未决的卡没有结算行").isEmpty();
		assertThat(this.audit.metrics()).containsEntry("readCalls", 1L);

		// 续跑的"复用结果"分支不得放大：重挂跑两次仍是一条
		this.managerA.resolvePending(RUN_ID, prompt(), this.store.pending(RUN_ID, FRONT_CALL_B));
		this.managerA.resolvePending(RUN_ID, prompt(), this.store.pending(RUN_ID, FRONT_CALL_B));
		assertThat(endsFor(FRONT_CALL_B)).as("复用分支不是第二个发点").hasSize(1);
		assertThat(this.audit.metrics()).containsEntry("readCalls", 1L);
	}

	/**
	 * 仲裁原语本身：<b>首写者胜</b>（两条独立"实例"共享同一个观测位时，第二个必须让位）、
	 * 同一 {@code toolCallId} 跨轮互不影响、无锚点时放行。
	 */
	@Test
	void observationClaimIsFirstWriterWinsAndScopedPerRun() {
		SettlementObservationStore shared = new SettlementObservationStore();

		assertThat(shared.claim(RUN_ID, FRONT_CALL_A)).as("首写者拿到发点").isTrue();
		assertThat(shared.claim(RUN_ID, FRONT_CALL_A)).as("第二个执行体必须让位").isFalse();
		assertThat(shared.claim(RUN_ID, FRONT_CALL_B)).as("不同 toolCallId 互不影响").isTrue();
		assertThat(shared.claim("run-other", FRONT_CALL_A)).as("toolCallId 只在单轮内唯一").isTrue();
		assertThat(shared.claim(RUN_ID, " ")).as("没有锚点就没有可仲裁的对象").isTrue();
	}

	/**
	 * Redis 面：<b>抢位与置 TTL 在同一把单键脚本里</b>（复审 C-1）——
	 * 首写者胜，且键一旦存在就必有 TTL（不存在"写进去但不过期"的中间态）。
	 */
	@Test
	void claimIsAtomicFirstWriterWinsAndAlwaysLeavesATtl() {
		FakeObservationRedis fake = new FakeObservationRedis();
		SettlementObservationStore store = new SettlementObservationStore(fake.template, this.properties);
		String key = store.observedKey(RUN_ID);

		assertThat(store.claim(RUN_ID, FRONT_CALL_A)).isTrue();
		assertThat(fake.hasKey(key)).isTrue();
		assertThat(fake.hasTtl(key)).as("抢位与 EXPIRE 同脚本 ⇒ 有键必有 TTL").isTrue();
		assertThat(store.claim(RUN_ID, FRONT_CALL_A)).as("第二个执行体让位").isFalse();
		assertThat(store.claim(RUN_ID, FRONT_CALL_B)).as("不同 toolCallId 互不影响").isTrue();
	}

	/**
	 * 旧墓碑自愈：上一版本可能留下"有键无 TTL"的残留（抢位成功、过期失败）。
	 * 下一次 claim 必须把 TTL 补回来 —— 否则它会成为一个永不过期的键。
	 */
	@Test
	void aTombstoneWithoutTtlIsRepairedByTheNextClaim() {
		FakeObservationRedis fake = new FakeObservationRedis();
		SettlementObservationStore store = new SettlementObservationStore(fake.template, this.properties);
		String key = store.observedKey(RUN_ID);
		fake.seedTombstone(key, FRONT_CALL_A);

		assertThat(store.claim(RUN_ID, FRONT_CALL_A)).as("已被观测过（墓碑仍算观测）").isFalse();
		assertThat(fake.hasTtl(key)).as("自愈：下一次 claim 把 TTL 补回来").isTrue();
	}

	/**
	 * 脚本契约（C-1 的"不留永久键"保证）：抢位与 {@code EXPIRE} <b>在同一把单键脚本</b>里 ——
	 * 于是"键写进去了但没有 TTL"这个中间态在 Redis 内不可表达；只有一把 KEYS（不是多键脚本）。
	 */
	@Test
	void theClaimScriptCarriesBothHsetnxAndExpireOnASingleKey() {
		FakeObservationRedis fake = new FakeObservationRedis();
		SettlementObservationStore store = new SettlementObservationStore(fake.template, this.properties);

		store.claim(RUN_ID, FRONT_CALL_A);

		@SuppressWarnings({ "unchecked", "rawtypes" })
		ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass((Class) RedisScript.class);
		verify(fake.template).execute(script.capture(), anyList(), any(Object[].class));
		assertThat(String.valueOf(script.getValue().getScriptAsString())).contains("HSETNX").contains("EXPIRE")
			.contains("KEYS[1]")
			.as("单键脚本：只有一把 KEYS（Cluster 下天然同槽，不是多键脚本）")
			.doesNotContain("KEYS[2]");
	}

	/** 置 TTL/自愈动作失败只降级：claim 不抛、仲裁结论不变；后续 claim 仍能修好 TTL。 */
	@Test
	void aFailingTtlAttemptDoesNotBreakTheClaimAndIsRetriedLater() {
		FakeObservationRedis fake = new FakeObservationRedis();
		SettlementObservationStore store = new SettlementObservationStore(fake.template, this.properties);
		String key = store.observedKey(RUN_ID);
		fake.failExpire = true;

		assertThat(store.claim(RUN_ID, FRONT_CALL_A)).as("自愈失败不影响首写结论").isTrue();

		fake.failExpire = false;
		fake.ttlMillis.remove(key);
		store.claim(RUN_ID, FRONT_CALL_A);
		assertThat(fake.hasTtl(key)).as("TTL 被下一次 claim 修好").isTrue();
	}

	/** 依赖整个不可用时 fail-open（宁可极小概率双记，也不能整片缺行）。 */
	@Test
	void aBrokenRedisFailsOpen() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
				org.mockito.ArgumentMatchers.<String>anyList(), any(Object[].class)))
			.thenThrow(new IllegalStateException("模拟 Redis 抖动"));
		SettlementObservationStore store = new SettlementObservationStore(redis, this.properties);

		assertThat(store.claim(RUN_ID, FRONT_CALL_A)).isTrue();
	}

	/** 发失败释放后，同一 toolCallId 必须能被重新抢到（否则就是永久 0 条）。 */
	@Test
	void releaseLetsTheSameSettlementClaimAgain() {
		FakeObservationRedis fake = new FakeObservationRedis();
		SettlementObservationStore store = new SettlementObservationStore(fake.template, this.properties);
		assertThat(store.claim(RUN_ID, FRONT_CALL_A)).isTrue();

		store.release(RUN_ID, FRONT_CALL_A);

		assertThat(fake.hashes.get(store.observedKey(RUN_ID))).doesNotContainKey(FRONT_CALL_A);
		assertThat(store.claim(RUN_ID, FRONT_CALL_A)).isTrue();
	}

	/**
	 * <b>抢位之后发行为失败</b>不得让该条结算永久消失：释放占位后，下一次结算必须还能发。
	 *
	 * <p>复审 I-1：`claim` → `emit` 不在同一临界区，且发行为可能抛（日志/台账都是外部面）。
	 * 若占位被消费却不释放，原本会成功的执行体只会被判"已发过"而静默让位 ⇒ 该 `toolCallId`
	 * 永久 0 条 end、`readCalls` 永久 0（比重记更糟的方向）。
	 */
	@Test
	void aFailedEmissionReleasesTheMarkerSoALaterSettlementCanRetry() {
		PendingToolCall pending = settledFrontendEntry(FRONT_CALL_A);

		assertThatThrownBy(() -> ToolCallEndTrace.emitOnce(this.observations, RUN_ID, SESSION_ID, pending,
				FRONT_CALL_A, "aggregate_sheet", PendingToolCall.KIND_FRONTEND, 1L, 0L, AGG_TEXT, this.audit))
			.as("第一次发行为在载荷构造阶段抛（尚未写出任何行）")
			.isInstanceOf(IllegalStateException.class);
		assertThat(endsFor(FRONT_CALL_A)).as("抛错前没有写出任何行").isEmpty();
		assertThat(this.audit.metrics()).containsEntry("readCalls", 0L);

		ToolCallEndTrace.emitOnce(this.observations, RUN_ID, SESSION_ID, pending, FRONT_CALL_A, "aggregate_sheet",
				PendingToolCall.KIND_FRONTEND, 1L, 0L, AGG_TEXT, this.audit);

		assertThat(this.audit.metrics()).as("占位已释放 ⇒ 重试必须真正发一次").containsEntry("readCalls", 1L);
		assertThat(endsFor(FRONT_CALL_A)).hasSize(1);
	}

	/**
	 * <b>写后失败</b>（R-I-1 的另一半）：行<b>已经</b>写出、审计面随后抛错时，占位<b>不得</b>释放 ——
	 * 释放会让下一次结算再发一行（同一 {@code toolCallId} 两条 end + 两次计数），把"恰好一次"
	 * 变成"至多两条"。写前失败才释放（见上一条用例）。
	 */
	@Test
	void auditFailureAfterTheLineIsWrittenKeepsTheMarker() {
		PendingToolCall pending = plainSettledFrontendEntry(FRONT_CALL_A);
		ThrowingAudit throwing = new ThrowingAudit(new SheetSwitches(this.properties), this.store);

		assertThatThrownBy(() -> ToolCallEndTrace.emitOnce(this.observations, RUN_ID, SESSION_ID, pending,
				FRONT_CALL_A, "aggregate_sheet", PendingToolCall.KIND_FRONTEND, 1L, 0L, AGG_TEXT, throwing))
			.as("审计面在行写出之后抛错").isInstanceOf(IllegalStateException.class);
		assertThat(endsFor(FRONT_CALL_A)).as("行已写出（这正是不能释放的场景）").hasSize(1);
		assertThat(throwing.metrics()).containsEntry("readCalls", 1L);

		ToolCallEndTrace.emitOnce(this.observations, RUN_ID, SESSION_ID, pending, FRONT_CALL_A, "aggregate_sheet",
				PendingToolCall.KIND_FRONTEND, 1L, 0L, AGG_TEXT, throwing);
		assertThat(endsFor(FRONT_CALL_A)).as("写后失败不释放占位 ⇒ 重试必须让位，不得重复").hasSize(1);
		assertThat(throwing.metrics()).as("计数也不得翻倍").containsEntry("readCalls", 1L);
	}

	/**
	 * <b>R-C-1</b>：{@code claim} 必须能透过<b>真实</b> {@code StringRedisTemplate} 跑通。
	 *
	 * <p>上一版把 {@code ttlSeconds()}（{@code long}）直接当 Lua ARGV 传：{@code StringRedisSerializer}
	 * 对 {@code Long} 抛 {@code ClassCastException}，被 {@code catch} 折成 fail-open ⇒ 生产上
	 * "每次抢位都成功"，跨实例仲裁整体失效（I-1 双记原形回归）。本用例用真实模板 + 桩连接
	 * （不真的连 Redis）：ARGV 序列化、脚本调用、结果反序列化都走 Spring 的真实实现，
	 * 因而能钉住"ARGV 必须是字符串"这条边界；断言"第二个执行体必须让位"就是真仲裁。
	 */
	@Test
	void claimArbitratesThroughARealStringRedisTemplate() {
		// 三次抢位的脚本返回值：首写者 → 同一 field 已存在 → 另一个 field 首写
		long[] scriptResults = { 1L, 0L, 1L };
		AtomicInteger evaluations = new AtomicInteger();
		RedisConnection connection = mock(RedisConnection.class, invocation -> {
			String method = invocation.getMethod().getName();
			if ("evalSha".equals(method) || "eval".equals(method)) {
				int index = Math.min(evaluations.getAndIncrement(), scriptResults.length - 1);
				return scriptResults[index];
			}
			return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
		});
		RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
		when(factory.getConnection()).thenReturn(connection);
		StringRedisTemplate template = new StringRedisTemplate(factory);
		template.afterPropertiesSet();
		SettlementObservationStore store = new SettlementObservationStore(template, this.properties);

		assertThat(store.claim(RUN_ID, FRONT_CALL_A)).as("真实模板下首位必须拿到发点（不是 fail-open 假成功）")
			.isTrue();
		assertThat(store.claim(RUN_ID, FRONT_CALL_A)).as("同一 field 的第二个执行体必须让位（真仲裁，不是恒 true）")
			.isFalse();
		assertThat(store.claim(RUN_ID, FRONT_CALL_B)).as("不同 toolCallId 互不影响").isTrue();
	}

	/**
	 * <b>R-I-4</b>：fail-open 必须留可观测信号（项目既有的 {@link RedisDegradation} 计数面）。
	 *
	 * <p>没有这个计数时，"每次 claim 都抛 CCE"这种全量失效在现网是零信号（只有 {@code log.debug}）。
	 */
	@Test
	void failOpenIsRecordedInRedisDegradation() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.execute(any(RedisScript.class), org.mockito.ArgumentMatchers.<String>anyList(),
				any(Object[].class)))
			.thenThrow(new IllegalStateException("模拟 Redis 抖动"));
		SettlementObservationStore store = new SettlementObservationStore(redis, this.properties);
		long before = RedisDegradation.count(RedisDegradation.O1_OBSERVATION_CLAIM);

		assertThat(store.claim(RUN_ID, FRONT_CALL_A)).isTrue();
		assertThat(RedisDegradation.count(RedisDegradation.O1_OBSERVATION_CLAIM))
			.as("降级必须可计数（/health.redis.degradations 可见）").isEqualTo(before + 1L);
	}

	/**
	 * 观测面 I/O 必须在 <b>实例级 {@code submitLock} 之外</b>（复审 I-2）：`submitLock` 是跨 run 共用的
	 * 决策/挂起锁，park 部署下每次回灌都持锁做一次 Redis 往返会把无关 run 一起排队。
	 *
	 * <p>探法：让第一张卡的 `claim` 停在闸门上，再从另一线程提交<b>另一张卡</b>的决策。
	 * 若结算仍在锁内，第二个提交会被这把锁挡住（用例在 2s 内拿不到结果即红）；移出锁后立即返回。
	 */
	@Test
	void settlementIoRunsOutsideTheInstanceSubmitLock() throws Exception {
		seedPendingCard(FRONT_CALL_A);
		seedPendingCard(FRONT_CALL_B);
		BlockingObservationStore blocking = new BlockingObservationStore(FRONT_CALL_A);
		this.gateA.setSettlementObservationStore(blocking);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<?> first = pool.submit(() -> this.gateA.submitFrontendResult(RUN_ID, FRONT_CALL_A, AGG_TEXT,
					"aie2e", false, "ok", AGG_META, null));
			assertThat(blocking.entered.await(10, TimeUnit.SECONDS)).as("第一张卡已进入结算观测位").isTrue();

			Future<?> second = pool.submit(() -> this.gateA.submitFrontendResult(RUN_ID, FRONT_CALL_B, AGG_TEXT,
					"aie2e", false, "ok", AGG_META, null));
			second.get(2, TimeUnit.SECONDS);

			blocking.release.countDown();
			first.get(10, TimeUnit.SECONDS);
		}
		finally {
			blocking.release.countDown();
			pool.shutdownNow();
		}

		assertThat(endsFor(FRONT_CALL_A)).hasSize(1);
		assertThat(endsFor(FRONT_CALL_B)).hasSize(1);
		assertThat(this.audit.metrics()).containsEntry("readCalls", 2L);
	}

	/** 直接铺一张"已公布、仍待回灌"的前端卡（不经过首轮，便于单点驱动决策路径）。 */
	private void seedPendingCard(String toolCallId) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName("aggregate_sheet");
		pending.setType("function");
		pending.setArguments("{\"defCode\":\"CURRENCY\"}");
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setStatus(PendingToolCall.PENDING);
		this.store.putPending(RUN_ID, pending);
	}

	/**
	 * 已结算的前端条目；{@code getSheetMeta()} 首次调用即抛 —— 用来把"发行为失败"钉在
	 * <b>写出任何行之前</b>（载荷构造阶段），于是"失败后重试"可用行数/计数直接判定。
	 */
	private static PendingToolCall settledFrontendEntry(String toolCallId) {
		PendingToolCall pending = new PendingToolCall() {

			private boolean firstRead = true;

			@Override
			public Map<String, Object> getSheetMeta() {
				if (this.firstRead) {
					this.firstRead = false;
					throw new IllegalStateException("模拟观测面在载荷构造时抛错");
				}
				return super.getSheetMeta();
			}

		};
		pending.setToolCallId(toolCallId);
		pending.setName("aggregate_sheet");
		pending.setType("function");
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setStatus(PendingToolCall.FRONTEND_RESULT);
		pending.setOutcome("ok");
		pending.setResultText(AGG_TEXT);
		pending.setSheetMeta(AGG_META);
		return pending;
	}

	/** claim 在指定 toolCallId 上停住的观测位（其余 toolCallId 直接通行）。 */
	private static final class BlockingObservationStore extends SettlementObservationStore {

		private final String blockingCallId;

		private final CountDownLatch entered = new CountDownLatch(1);

		private final CountDownLatch release = new CountDownLatch(1);

		private BlockingObservationStore(String blockingCallId) {
			this.blockingCallId = blockingCallId;
		}

		@Override
		public boolean claim(String runId, String toolCallId) {
			if (this.blockingCallId.equals(toolCallId)) {
				this.entered.countDown();
				try {
					this.release.await(10, TimeUnit.SECONDS);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
			return super.claim(runId, toolCallId);
		}

	}

	/** 已结算的前端条目（{@code getSheetMeta()} 正常返回）：用于"写后失败"场景。 */
	private static PendingToolCall plainSettledFrontendEntry(String toolCallId) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName("aggregate_sheet");
		pending.setType("function");
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setStatus(PendingToolCall.FRONTEND_RESULT);
		pending.setOutcome("ok");
		pending.setResultText(AGG_TEXT);
		pending.setSheetMeta(AGG_META);
		return pending;
	}

	/**
	 * 在<b>行已写出、计数已记</b>之后抛错的审计面：复现"写后失败"（R-I-1 的另一半）。
	 * 真实 {@code SheetAudit#record} 不会抛（台账写入自吞异常），这里是刻意注入的故障。
	 */
	private static final class ThrowingAudit extends SheetAudit {

		private final AtomicInteger records = new AtomicInteger();

		private ThrowingAudit(SheetSwitches switches, InMemoryRunStore store) {
			super(switches, store);
		}

		@Override
		public void record(String sessionId, PendingToolCall pending) {
			super.record(sessionId, pending);
			this.records.incrementAndGet();
			throw new IllegalStateException("模拟观测面在行写出之后的审计阶段抛错");
		}

	}

	// ── 辅助（原） ───────────────────────────────────────────────────────────

	private Prompt prompt() {
		List<ToolCallback> callbacks = List.of(MethodToolCallbackProvider.builder()
			.toolObjects(this.blockingTool)
			.build()
			.getToolCallbacks());
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(callbacks)
			.toolContext(Map.of("runId", RUN_ID))
			.build();
		return new Prompt(List.of(new UserMessage("这张表按小数位数各有多少种货币？")), options);
	}

	/** 捕获的 {@code tool_call_end} 行里属于给定 {@code toolCallId} 的那些（按行首匹配事件名）。 */
	private List<String> endsFor(String toolCallId) {
		List<String> out = new ArrayList<>();
		for (ILoggingEvent item : this.appender.list) {
			String message = item.getFormattedMessage();
			if (message.startsWith("event=tool_call_end ") && message.contains("toolCallId=" + toolCallId + " ")) {
				out.add(message);
			}
		}
		return out;
	}

	private static AssistantMessage.ToolCall call(String id, String name) {
		return new AssistantMessage.ToolCall(id, "function", name, "{}");
	}

	private static ChatResponse response(AssistantMessage.ToolCall... calls) {
		AssistantMessage assistant = AssistantMessage.builder().toolCalls(List.of(calls)).build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

}
