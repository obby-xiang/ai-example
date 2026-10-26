package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.conformance.FrameContract;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.history.AiChatHistoryRecorder;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.common.AiStateUnavailableException;
import com.example.configmgr.common.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * W-30 夜跑（run 40232440965 · runId {@code 816aa484-13d3-40a7-bfbd-4d352495d22d}）的最小反例集。
 *
 * <h2>现场时序（boot.log 21:01:39）</h2>
 * <ol>
 * <li>{@code 39.562} 第 1 次尝试在工具事件边界读到 {@code cancelState=UNKNOWN}（上一轮取消时一次在飞的
 *     Redis GET 被打断，注册表进了 5s 全实例退避）⇒ {@code SpToolCallingManager} 抛
 *     {@code AI_REDIS_UNAVAILABLE}（可重试）；</li>
 * <li>{@code 39.564} 重试退避第一片就读到取消，日志写"退避期间收到取消…不再等待"，<b>但尝试循环
 *     仍然发起第 2 次尝试</b>；</li>
 * <li>{@code 39.575} 第 2 次尝试的请求没有工具结果、桩的"每路由一次"已用掉 UI-SCROLL ⇒ 回纯文本，
 *     3ms 跑完（没有任何看门狗拍、也没有工具事件边界）；</li>
 * <li>{@code 39.578} 死卡兜底续跑做了取消收尾：快照 CANCELLED + {@code done{cancelled:true}}；</li>
 * <li>{@code 39.583} 驱动线程的 {@code finish} 不看终态，写快照 DONE + {@code done{cancelled:false}}
 *     —— CANCELLED 被成功终局覆盖，20s 后 E2E 读到 DONE。</li>
 * </ol>
 *
 * <p>五条用例：①退避期收到取消不得再发尝试（根因 ②）；②取消终态先落定不得被成功终局覆盖
 * （根因 ③）；③<b>复审 I-1 的窗口</b>——取消落在 finish 的历史写/落库之间（latch 定序，断言 done 恰一条
 * 且快照/历史一致）；④反序（取消先持锁 ⇒ CANCELLED 赢）；⑤<b>跨实例</b>——另一实例在 DONE 落库那一刻已取消
 * （生产 RunStore 的 DONE 状态 CAS 拒写），落库后复读必须转入取消收尾、不再发 {@code done{cancelled:false}}。
 * 根因 ①（注册表被线程局部中断点亮全实例退避）在 {@code CancellationRegistryFailClosedTest} 里钉；
 * 真实 Redis 上的 CAS 语义在 {@code RunStoreTerminalCancellationTest} 里钉。
 */
class CancelDuringRetryBackoffTest {

	private static final String RUN_ID = "run-cancel-retry-backoff";

	private static final String SESSION_ID = "s-cancel-retry-backoff";

	private final ObjectMapper mapper = new ObjectMapper();

	/** 生产口径：每次 {@code get} 返回新反序列化的快照（默认替身返回同一对象，会掩盖 I-1 窗口）。 */
	private final InMemoryRunStore store = new CopyStore(this.mapper);

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final AiProperties properties = new AiProperties();

	private final ChatModel chatModel = mock(ChatModel.class);

	private final AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);

	private final ProviderRuntime runtime = new ProviderRuntime(null, "prov", "ci-stub", "http://localhost",
			"/chat/completions", "k", null, null, this.chatModel, mock(ChatClient.class));

	private final ChatMemory chatMemory = mock(ChatMemory.class);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final ContextBuilder contextBuilder = mock(ContextBuilder.class);

	private final AiSessionService sessionService = mock(AiSessionService.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private ScheduledExecutorService watchdogExecutor;

	private ResilientChatService service;

	private ResilientChatService buildService(RunRegistry runRegistry) {
		return new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec, this.store, runRegistry,
				this.toolRegistry, this.contextBuilder, this.sessionService, this.properties, this.cancellations,
				this.beacon, this.watchdogExecutor);
	}

	/** 取消事实（模拟 {@code POST /api/ai/cancel/{runId}} 已置位本地标志）。 */
	private final AtomicBoolean cancelled = new AtomicBoolean(false);

	private final AtomicInteger streams = new AtomicInteger();

	@BeforeEach
	void setUp() {
		when(this.providerRegistry.current()).thenReturn(this.runtime);
		when(this.providerRegistry.tryCurrent()).thenReturn(java.util.Optional.of(this.runtime));
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "w30-cancel-retry-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = buildService(this.registry);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.cancellations.cancelState(anyString()))
			.thenAnswer(invocation -> this.cancelled.get() ? CancellationRegistry.CancelState.CANCELLED
					: CancellationRegistry.CancelState.NOT_CANCELLED);
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);
	}

	@AfterEach
	void tearDown() {
		this.watchdogExecutor.shutdownNow();
	}

	@Test
	void cancelSeenDuringRetryBackoffDoesNotStartAnotherAttempt() {
		when(this.chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
			if (this.streams.incrementAndGet() == 1) {
				// 第 1 次尝试在工具事件边界失败（N-1 的 UNKNOWN ⇒ AI_REDIS_UNAVAILABLE，可重试），
				// 随后取消到达 —— 现场 39.562/39.564，间隔 2ms。
				this.cancelled.set(true);
				return Flux.error(new StreamViolationException(StreamViolationException.STATE_UNAVAILABLE,
						"取消状态未知（Redis 读失败），工具执行在事件边界终止（runId=" + RUN_ID + "）"));
			}
			// 若仍发起第 2 次尝试：现场那条请求没有工具结果、桩已用过该路由 ⇒ 回纯文本并正常收尾。
			return Flux.just(chunk("迟到的正文"), terminal("stop"));
		});

		FrameWire wire = runTurn();

		assertThat(this.streams.get())
			.as("退避期已读到取消，不得再发起第 2 次尝试（现场正是这次重试把轮次跑成 DONE）")
			.isEqualTo(1);
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.CANCELLED);
		assertThat(snapshot.isCancelled()).as("取消终态必须自带 cancelled 位").isTrue();
		assertThat(doneFrames(wire)).as("取消只出一条 done，且带 cancelled=true")
			.hasSize(1)
			.allSatisfy(frame -> assertThat(frame).containsEntry("cancelled", true));
	}

	@Test
	void successfulAttemptMustNotOverwriteAnAlreadyCancelledTerminal() {
		when(this.chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
			// 取消收尾在本次尝试期间先落定（现场 39.578：快照 CANCELLED + done{cancelled:true}），
			// 驱动线程随后才跑到 finish（39.583）—— 这次"迟到成功"不得把 CANCELLED 改成 DONE。
			this.cancelled.set(true);
			RunSnapshot cancelledSnapshot = this.store.get(RUN_ID);
			cancelledSnapshot.setStatus(RunSnapshot.CANCELLED);
			cancelledSnapshot.setCancelled(true);
			this.store.save(cancelledSnapshot);
			return Flux.just(chunk("迟到的正文"), terminal("stop"));
		});

		FrameWire wire = runTurn();

		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).as("取消终态先落定：成功终局不得覆盖（I-1 的反向）")
			.isEqualTo(RunSnapshot.CANCELLED);
		assertThat(snapshot.getFinalText()).as("被取消的轮不得把迟到正文当成交付").isNull();
		assertThat(doneFrames(wire)).as("不得在取消终帧之后再补一条 done{cancelled:false}")
			.hasSize(1)
			.allSatisfy(frame -> assertThat(frame).containsEntry("cancelled", true));
	}

	// ── 复审 I-1：成功终局的临界区（latch 定序，真实并发；旧代码必红） ──

	/**
	 * <b>取消落在"取消检查之后、写 DONE 之前"的窗口里</b>（复审 I-1）：finish 在历史写入处被 latch 卡住，
	 * 主线程置取消并由另一线程跑 {@code cancelParked}（它走同一把 per-run 监视器）。
	 *
	 * <p>旧代码（finish 不持监视器）：取消侧写 CANCELLED + {@code done{cancelled:true}} + stopped 历史，
	 * finish 放行后把快照盖成 DONE 并补第二条 {@code done{cancelled:false}} ⇒ 归档 2 条 done、历史与终态不一致。
	 * 修复后两条先到顺序都只有一条 done：本用例的时序里 finish 先持锁 ⇒ DONE 赢，取消侧的终态保护拒绝改写；
	 * {@link #cancelFinalisedBeforeTheTurnStillWinsUnderTheSharedMonitor} 覆盖反序（取消先持锁 ⇒ CANCELLED 赢）。
	 */
	@Test
	void cancelLandingInsideTheSuccessWindowStillYieldsExactlyOneDone() throws Exception {
		InMemoryFrameStore frames = new InMemoryFrameStore();
		RunRegistry frameRegistry = new RunRegistry(this.store, this.mapper, null, frames, null, null);
		ResilientChatService svc = buildService(frameRegistry);
		CountingRecorder recorder = new CountingRecorder(true);
		ReflectionTestUtils.setField(svc, "historyRecorder", recorder.recorder);
		svc.setCancelFinalizer(newFinalizer());

		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("完整回答"), terminal("stop")));

		SseChatEmitter out = frameRegistry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		Thread driver = new Thread(() -> svc.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(),
				List.of(new UserMessage("按原语执行一次。")), out), "w30-finish-window");
		driver.start();
		assertThat(recorder.inRecorder.await(5, TimeUnit.SECONDS))
			.as("finish 已进入成功终局的临界区（卡在 DB 历史写入）")
			.isTrue();

		// 取消在窗口内落地：先置取消事实（POST /cancel 的本地标志），再让取消收尾去抢同一把监视器
		this.cancelled.set(true);
		Thread canceller = new Thread(() -> svc.cancelParked(RUN_ID), "w30-cancel-window");
		canceller.start();
		awaitAtMonitorOrFinished(canceller);

		recorder.release.countDown();
		driver.join(5000);
		canceller.join(5000);
		assertThat(driver.isAlive() || canceller.isAlive()).as("驱动/取消两个线程都必须收尾").isFalse();

		RunSnapshot snapshot = this.store.get(RUN_ID);
		List<Map<String, Object>> archived = doneFrames(frames.readFrom(RUN_ID, 0, 0));
		// 本时序是确定的：finish 先拿到 monitor（取消线程在 monitorenter 上阻塞），所以终态先到者必为 DONE；
		// 取消侧随后读到 DONE，按终态保护拒绝改写（不写 stopped、不发 done）。反序由
		// cancelFinalisedBeforeTheTurnStillWinsUnderTheSharedMonitor 覆盖。
		assertThat(snapshot.getStatus()).as("finish 先持锁 ⇒ 终态先到者赢（DONE）").isEqualTo(RunSnapshot.DONE);
		assertThat(archived).as("归档里 done 恰一条（旧代码：取消一条 + finish 一条）").hasSize(1);
		assertThat(archived.get(0)).containsEntry("cancelled", false);
		assertThat(doneFrames(wire)).as("实时通道同样只能有一条 done").hasSize(1);
		assertThat(recorder.finalWrites.get()).as("DONE ⇒ 历史是完整最终 assistant").isEqualTo(1);
		assertThat(recorder.stoppedWrites.get()).as("DONE ⇒ 取消侧终态保护，不写 stopped 历史").isZero();
	}

	/**
	 * 反序：取消<b>先</b>拿同一把监视器并落定（CANCELLED + {@code done{cancelled:true}}），随后驱动线程
	 * 才跑到成功终局 —— 重新读快照必须走取消收尾，不再写 DONE、不再补第二条 done。
	 */
	@Test
	void cancelFinalisedBeforeTheTurnStillWinsUnderTheSharedMonitor() {
		InMemoryFrameStore frames = new InMemoryFrameStore();
		RunRegistry frameRegistry = new RunRegistry(this.store, this.mapper, null, frames, null, null);
		ResilientChatService svc = buildService(frameRegistry);
		CountingRecorder recorder = new CountingRecorder(false);
		ReflectionTestUtils.setField(svc, "historyRecorder", recorder.recorder);
		svc.setCancelFinalizer(newFinalizer());

		this.cancelled.set(true);
		svc.cancelParked(RUN_ID);
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.CANCELLED);

		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("迟到的正文"), terminal("stop")));
		SseChatEmitter out = frameRegistry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		svc.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("按原语执行一次。")), out);

		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.CANCELLED);
		assertThat(this.store.get(RUN_ID).getFinalText()).as("被取消的轮不得把迟到正文当成交付").isNull();
		List<Map<String, Object>> archived = doneFrames(frames.readFrom(RUN_ID, 0, 0));
		assertThat(archived).as("取消先落定 ⇒ done 仍恰一条（归档幂等挡住重复收尾）")
			.hasSize(1)
			.allSatisfy(frame -> assertThat(frame).containsEntry("cancelled", true));
		assertThat(doneFrames(wire)).hasSize(0);
		assertThat(recorder.finalWrites.get()).as("取消先落定 ⇒ 不得写完整 assistant").isZero();
		assertThat(recorder.stoppedWrites.get()).as("取消收尾写 stopped 历史恰一次").isEqualTo(1);
	}

	/**
	 * <b>跨实例</b>（复审 I-1 的补充）：另一实例的取消在本实例写 DONE 的那一刻已经落库，生产
	 * {@code RunStore#save} 的 DONE 状态 CAS 于是拒写。本用例用 {@link CopyStore#cancelJustBeforeDoneSave}
	 * 复刻这一时序（替身不落 DONE、快照保持 CANCELLED），断言 finish 在落库后复读、转入取消收尾，
	 * <b>不会再发 {@code done{cancelled:false}}</b>、归档 done 恰一条且与快照一致。
	 *
	 * <p>边界（用例里也断言）：{@code recordFinalAssistant} 先于 save（保持 resume 幂等口径），
	 * 所以 DB 历史那一笔已经发生 —— 跨实例取消恰好落在 append 与 save 之间时，快照 CANCELLED / DB
	 * 已有完整 assistant 的组合仍可能短暂存在（first-write-wins 不可回退）。这不是"已闭合"，
	 * 是报告里保留的边界。
	 */
	@Test
	void anotherInstanceCancelBeforeTheDoneWriteIsHonouredAfterSave() {
		InMemoryFrameStore frames = new InMemoryFrameStore();
		RunRegistry frameRegistry = new RunRegistry(this.store, this.mapper, null, frames, null, null);
		ResilientChatService svc = buildService(frameRegistry);
		CountingRecorder recorder = new CountingRecorder(false);
		ReflectionTestUtils.setField(svc, "historyRecorder", recorder.recorder);
		svc.setCancelFinalizer(newFinalizer());
		((CopyStore) this.store).cancelJustBeforeDoneSave = true;

		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("完整回答"), terminal("stop")));
		SseChatEmitter out = frameRegistry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		svc.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("按原语执行一次。")), out);

		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).as("另一实例已取消：DONE 不得落库/不得被当作成功").isEqualTo(RunSnapshot.CANCELLED);
		assertThat(snapshot.isCancelled()).isTrue();
		List<Map<String, Object>> archived = doneFrames(frames.readFrom(RUN_ID, 0, 0));
		assertThat(archived).as("只允许一条 done（cancelled=true）").hasSize(1)
			.allSatisfy(frame -> assertThat(frame).containsEntry("cancelled", true));
		assertThat(doneFrames(wire)).as("不得再发 done{cancelled:false}").hasSize(1)
			.allSatisfy(frame -> assertThat(frame).containsEntry("cancelled", true));
		assertThat(recorder.finalWrites.get()).as("既有顺序：recordFinalAssistant 先于 save（本批保留的边界）")
			.isEqualTo(1);
		assertThat(recorder.stoppedWrites.get()).as("复读发现取消 ⇒ 取消收尾写 stopped 历史").isEqualTo(1);
	}

	/**
	 * 复审 I-1 · 情形 A：DONE 已成功落库，本地取消 flag 在窗口内被置位，而落库后的权威复读恰好失败。
	 * 旧实现退回 flag ⇒ {@code cancelTerminal} 见 DONE 直接返回 ⇒ <b>DONE 零终帧</b>。
	 * 修复后必须恰好一条 {@code error}（不可知）：既不 {@code done{false}}，也不能没有终帧。
	 */
	@Test
	void rereadFailureAfterDoneWithLocalCancelStillEmitsOneTerminal() throws Exception {
		InMemoryFrameStore frames = new InMemoryFrameStore();
		RunRegistry frameRegistry = new RunRegistry(this.store, this.mapper, null, frames, null, null);
		ResilientChatService svc = buildService(frameRegistry);
		CountingRecorder recorder = new CountingRecorder(true);
		ReflectionTestUtils.setField(svc, "historyRecorder", recorder.recorder);
		svc.setCancelFinalizer(newFinalizer());
		((CopyStore) this.store).failNextGetAfterDoneSave = true;

		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("完整回答"), terminal("stop")));
		SseChatEmitter out = frameRegistry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		Thread driver = new Thread(() -> svc.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(),
				List.of(new UserMessage("按原语执行一次。")), out), "w30-reread-window");
		driver.start();
		assertThat(recorder.inRecorder.await(5, TimeUnit.SECONDS)).as("finish 已进入成功终局临界区").isTrue();

		// HTTP 取消线程不需要 monitor：窗口内置位本地 flag，随后才放行 finish（DONE 落库 + 复读失败）
		this.cancelled.set(true);
		recorder.release.countDown();
		driver.join(5000);
		assertThat(driver.isAlive()).isFalse();

		assertThat(this.store.get(RUN_ID).getStatus()).as("DONE 已落库：不可知不得把它改写成 FAILED/CANCELLED")
			.isEqualTo(RunSnapshot.DONE);
		List<Map<String, Object>> archived = terminalFrames(frames.readFrom(RUN_ID, 0, 0));
		assertThat(archived).as("DONE 零终帧是最坏形态：必须恰好一条终帧").hasSize(1);
		assertThat(archived.get(0)).as("不可知用既有 error 表达，且不冒充成功")
			.containsEntry("type", "error")
			.containsEntry("code", ErrorCodes.AI_REDIS_UNAVAILABLE);
		assertThat(doneFrames(frames.readFrom(RUN_ID, 0, 0))).as("不得发 done{cancelled:false}").isEmpty();
		assertThat(doneFrames(wire)).as("实时通道同样不得发 done").isEmpty();
		assertThat(terminalFrames(wire.frames())).hasSize(1);
	}

	/**
	 * 复审 I-1 · 情形 B：DONE 被状态 CAS 拒写（另一实例已写 CANCELLED）、落库后复读失败、
	 * <b>本地 flag 未置位</b>。旧实现把它当"未取消"发 {@code done{cancelled:false}} ⇒ 与 CANCELLED 快照并存。
	 * 修复后只能有一条 {@code error}。
	 */
	@Test
	void casRefusedWithUnreadableSnapshotDoesNotEmitDoneWithoutCancel() {
		InMemoryFrameStore frames = new InMemoryFrameStore();
		RunRegistry frameRegistry = new RunRegistry(this.store, this.mapper, null, frames, null, null);
		ResilientChatService svc = buildService(frameRegistry);
		CountingRecorder recorder = new CountingRecorder(false);
		ReflectionTestUtils.setField(svc, "historyRecorder", recorder.recorder);
		svc.setCancelFinalizer(newFinalizer());
		CopyStore raceStore = (CopyStore) this.store;
		raceStore.cancelJustBeforeDoneSave = true;
		raceStore.failNextGetAfterDoneSave = true;

		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("完整回答"), terminal("stop")));
		FrameWire wire = runTurn(svc, frameRegistry);

		assertThat(this.cancelled.get()).as("本用例的本地 flag 必须未置位（否则测的是另一条路径）").isFalse();
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.CANCELLED);
		List<Map<String, Object>> archived = terminalFrames(frames.readFrom(RUN_ID, 0, 0));
		assertThat(archived).as("不可知只能一条 error，不能发与 CANCELLED 并存的 done{false}").hasSize(1);
		assertThat(archived.get(0)).containsEntry("type", "error");
		assertThat(doneFrames(frames.readFrom(RUN_ID, 0, 0))).as("不得发 done{cancelled:false}").isEmpty();
		assertThat(doneFrames(wire)).isEmpty();
	}

	/**
	 * 复审 193 · 第 2 条：落库后复读发现"另一实例已取消"时，finish <b>不能把成功 accumulator 的正文</b>
	 * 当作停止半截交给 cancelTerminal —— 那会合成 stopped 消息写进 DB 历史与 chat memory
	 * （DB 有 first-write-wins，chat memory 是整窗覆盖，没有保护）。
	 * 只在这条"复读已见取消"的路径传 {@code null}，让 cancelTerminal 取权威快照的 assistantContent。
	 */
	@Test
	void rereadCancelledAfterSuccessDoesNotSynthesizeStoppedTextFromTheSuccessAccumulator() {
		InMemoryFrameStore frames = new InMemoryFrameStore();
		RunRegistry frameRegistry = new RunRegistry(this.store, this.mapper, null, frames, null, null);
		ResilientChatService svc = buildService(frameRegistry);
		CountingRecorder recorder = new CountingRecorder(false);
		ReflectionTestUtils.setField(svc, "historyRecorder", recorder.recorder);
		svc.setCancelFinalizer(newFinalizer());
		List<List<Message>> memoryWrites = new CopyOnWriteArrayList<>();
		doAnswer(invocation -> {
			memoryWrites.add(List.copyOf((List<Message>) invocation.getArgument(1)));
			return null;
		}).when(this.chatMemory).add(anyString(), anyList());

		// 另一实例已取消的权威历史：半截正文 + 归档尚无终帧
		RunSnapshot cancelled = this.store.get(RUN_ID);
		cancelled.setAssistantContent("半截正文");
		cancelled.setMessageJson(
				this.codec.serializeAll(List.of(new SystemMessage("系统提示"), new UserMessage("按原语执行一次。"))));
		this.store.save(cancelled);
		((CopyStore) this.store).cancelJustBeforeDoneSave = true;

		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("完整回答"), terminal("stop")));
		FrameWire wire = runTurn(svc, frameRegistry);

		RunSnapshot after = this.store.get(RUN_ID);
		assertThat(after.getStatus()).isEqualTo(RunSnapshot.CANCELLED);
		// 快照 messageJson 是 JSON 字符串列表（不是 Message 对象）：直接按正文字面量断言
		List<String> snapshotJson = after.getMessageJson();
		assertThat(snapshotJson).as("成功正文不得被合成进停止历史").noneMatch(json -> json.contains("完整回答"));
		assertThat(snapshotJson).as("停止历史取权威半截正文").anyMatch(json -> json.contains("半截正文"));
		assertThat(recorder.stoppedTexts()).as("DB 停止历史同样不得带成功正文")
			.noneMatch(text -> text.contains("完整回答"));
		assertThat(recorder.stoppedTexts()).as("DB 停止历史取权威半截正文")
			.anyMatch(text -> text.contains("半截正文"));
		List<String> memoryTexts = memoryWrites.stream().flatMap(List::stream).map(Message::getText)
			.filter(Objects::nonNull).toList();
		assertThat(memoryTexts).as("chat memory 是整窗覆盖：不得把成功正文写进去")
			.noneMatch(text -> text.contains("完整回答"));
		assertThat(doneFrames(frames.readFrom(RUN_ID, 0, 0))).as("取消终帧仍恰一条")
			.hasSize(1)
			.allSatisfy(frame -> assertThat(frame).containsEntry("cancelled", true));
		assertThat(doneFrames(wire)).hasSize(1);
	}

	@Test
	void alreadyCancelledBeforeSuccessDoesNotSynthesizeSuccessText() {
		InMemoryFrameStore frames = new InMemoryFrameStore();
		RunRegistry frameRegistry = new RunRegistry(this.store, this.mapper, null, frames, null, null);
		ResilientChatService svc = buildService(frameRegistry);
		CountingRecorder recorder = new CountingRecorder(false);
		ReflectionTestUtils.setField(svc, "historyRecorder", recorder.recorder);
		svc.setCancelFinalizer(newFinalizer());
		List<List<Message>> memoryWrites = new CopyOnWriteArrayList<>();
		doAnswer(invocation -> {
			memoryWrites.add(List.copyOf((List<Message>) invocation.getArgument(1)));
			return null;
		}).when(this.chatMemory).add(anyString(), anyList());
		RunSnapshot cancelled = this.store.get(RUN_ID);
		cancelled.setStatus(RunSnapshot.CANCELLED);
		cancelled.setCancelled(true);
		cancelled.setAssistantContent("权威半截");
		cancelled.setMessageJson(
				this.codec.serializeAll(List.of(new SystemMessage("系统提示"), new UserMessage("按原语执行一次。"))));
		this.store.save(cancelled);
		when(this.chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
			this.streams.incrementAndGet();
			return Flux.just(chunk("迟到成功正文"), terminal("stop"));
		});

		FrameWire wire = runTurn(svc, frameRegistry);

		assertThat(this.streams.get()).as("必须实际走到成功收尾前的取消检查").isEqualTo(1);
		RunSnapshot after = this.store.get(RUN_ID);
		assertThat(after.getStatus()).isEqualTo(RunSnapshot.CANCELLED);
		assertThat(after.getMessageJson()).noneMatch(json -> json.contains("迟到成功正文"));
		assertThat(after.getMessageJson()).anyMatch(json -> json.contains("权威半截"));
		assertThat(recorder.stoppedTexts()).noneMatch(text -> text.contains("迟到成功正文"));
		assertThat(recorder.stoppedTexts()).anyMatch(text -> text.contains("权威半截"));
		assertThat(memoryWrites).as("记忆收敛必须实际发生，不能空断言").isNotEmpty();
		List<String> memoryTexts = memoryWrites.stream().flatMap(List::stream).map(Message::getText)
			.filter(Objects::nonNull).toList();
		assertThat(memoryTexts).noneMatch(text -> text.contains("迟到成功正文"));
		assertThat(memoryTexts).anyMatch(text -> text.contains("权威半截"));
		assertThat(doneFrames(frames.readFrom(RUN_ID, 0, 0))).hasSize(1)
			.allSatisfy(frame -> assertThat(frame).containsEntry("cancelled", true));
		assertThat(doneFrames(wire)).hasSize(1);
	}

	// ───────────────────────── 辅助 ─────────────────────────
	/** 真实交棒器（生产同款：{@link CancelledRunFinalizer#finalizeMonitor} 条带化、per-run 恒定）。 */
	private CancelledRunFinalizer newFinalizer() {
		@SuppressWarnings("unchecked")
		ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);
		return new CancelledRunFinalizer(provider, Runnable::run, this.properties);
	}

	private static void awaitAtMonitorOrFinished(Thread thread) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < deadline) {
			Thread.State state = thread.getState();
			if (state == Thread.State.BLOCKED || state == Thread.State.WAITING
					|| state == Thread.State.TIMED_WAITING || state == Thread.State.TERMINATED) {
				return;
			}
			Thread.sleep(10);
		}
		throw new AssertionError("取消收尾线程 5s 内既未进入监视器等锁也未结束");
	}

	/**
	 * 生产口径的快照读：{@code RunStore#get} 在真实实现里每次从 Redis 反序列化一份<b>新对象</b>。
	 * 默认 {@link InMemoryRunStore#get} 返回进程内同一个对象 —— finish 在落库前改的内存字段会被
	 * 取消线程读到，从而把 I-1 的窗口误判成"已落库"，让用例假绿。
	 */
	private static final class CopyStore extends InMemoryRunStore {

		private final ObjectMapper mapper;

		/**
		 * 置 true = 下一次 DONE 的 {@code save} 按生产 RunStore 的 DONE 状态 CAS 语义处理：先由"另一个
		 * 实例"把同一 run 写成 CANCELLED，然后这一笔 DONE 拒写（快照保持 CANCELLED）。
		 */
		private volatile boolean cancelJustBeforeDoneSave;

		/** 置 true = DONE 的 save 之后让紧接着的一次 {@code get} 抛异常（复读失败的确定化）。 */
		private volatile boolean failNextGetAfterDoneSave;

		/** 一次性：下一次 {@code get} 抛 {@link AiStateUnavailableException}。 */
		private final AtomicBoolean failNextGet = new AtomicBoolean();

		private CopyStore(ObjectMapper mapper) {
			this.mapper = mapper;
		}

		@Override
		public RunSnapshot get(String runId) {
			if (this.failNextGet.getAndSet(false)) {
				throw new AiStateUnavailableException("模拟落库后复读失败 runId=" + runId);
			}
			RunSnapshot stored = super.get(runId);
			if (stored == null) {
				return null;
			}
			try {
				return this.mapper.readValue(this.mapper.writeValueAsString(stored), RunSnapshot.class);
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		}

		@Override
		public void save(RunSnapshot snapshot) {
			if (this.cancelJustBeforeDoneSave && RunSnapshot.DONE.equals(snapshot.getStatus())) {
				// 交叉实例时序的替身：另一实例的取消收尾在"本实例写 DONE"这一刻已经落库，
				// 生产 RunStore#save 的 DONE CAS 因此拒写（这里同样不落这一笔 DONE）。
				RunSnapshot other = super.get(snapshot.getRunId());
				if (other != null) {
					other.setStatus(RunSnapshot.CANCELLED);
					other.setCancelled(true);
					super.save(other);
				}
				armRereadFailure();
				return;
			}
			super.save(snapshot);
			if (RunSnapshot.DONE.equals(snapshot.getStatus())) {
				armRereadFailure();
			}
		}

		private void armRereadFailure() {
			if (this.failNextGetAfterDoneSave) {
				this.failNextGet.set(true);
			}
		}

	}

	/**
	 * 记录型历史写入器：{@code recordFinalAssistant} 可选地卡在 latch 上（只有 I-1 窗口用例需要阻塞），
	 * 并计数"完整 assistant / stopped 半截"两侧各被写了几次。
	 */
	private static final class CountingRecorder {

		private final CountDownLatch inRecorder = new CountDownLatch(1);

		private final CountDownLatch release = new CountDownLatch(1);

		private final AtomicInteger finalWrites = new AtomicInteger();

		private final AtomicInteger stoppedWrites = new AtomicInteger();

		/** 每次 {@code recordStopped} 收到的消息（按调用顺序；复用断言"成功正文没被合成进停止历史"）。 */
		private final List<List<Message>> stoppedPayloads = new CopyOnWriteArrayList<>();

		private final AiChatHistoryRecorder recorder = mock(AiChatHistoryRecorder.class);

		private CountingRecorder(boolean blockFinalAssistant) {
			doAnswer(invocation -> {
				this.finalWrites.incrementAndGet();
				this.inRecorder.countDown();
				if (blockFinalAssistant && !this.release.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("latch 超时：finish 未被放行");
				}
				return null;
			}).when(this.recorder).recordFinalAssistant(anyString(), anyString(), any());
			doAnswer(invocation -> {
				this.stoppedWrites.incrementAndGet();
				this.stoppedPayloads.add(List.copyOf((List<Message>) invocation.getArgument(2)));
				return null;
			}).when(this.recorder).recordStopped(anyString(), anyString(), anyList());
		}

		private List<String> stoppedTexts() {
			return this.stoppedPayloads.stream()
				.flatMap(List::stream)
				.map(Message::getText)
				.filter(text -> text != null)
				.toList();
		}

	}

	private FrameWire runTurn() {
		this.store.seedArchive(RUN_ID,
				FrameContract.fixture("start", 1L, "runId", RUN_ID, "sessionId", SESSION_ID));
		return runTurn(this.service, this.registry);
	}

	private FrameWire runTurn(ResilientChatService svc, RunRegistry runRegistry) {
		SseChatEmitter out = runRegistry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		svc.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("按原语执行一次。")), out);
		return wire;
	}

	private List<Map<String, Object>> doneFrames(FrameWire wire) {
		return doneFrames(wire.frames());
	}

	private List<Map<String, Object>> doneFrames(List<Map<String, Object>> frames) {
		return frames.stream().filter(frame -> "done".equals(String.valueOf(frame.get("type")))).toList();
	}

	private List<Map<String, Object>> terminalFrames(List<Map<String, Object>> frames) {
		return frames.stream()
			.filter(frame -> {
				String type = String.valueOf(frame.get("type"));
				return "done".equals(type) || "error".equals(type);
			})
			.toList();
	}

	private static ChatResponse chunk(String text) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
	}

	private static ChatResponse terminal(String finishReason) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").build(),
				ChatGenerationMetadata.builder().finishReason(finishReason).build())));
	}

}
