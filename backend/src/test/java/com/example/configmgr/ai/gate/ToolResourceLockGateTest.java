package com.example.configmgr.ai.gate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.FrameContract;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.FakeLeaseRedis;
import com.example.configmgr.ai.run.FakeRenewScheduler;
import com.example.configmgr.ai.run.LeaseKeys;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L1 资源互斥的<b>门级</b>用例（B23L1 批③b）：真 {@link ConfirmGate} + 真 {@link SseChatEmitter}
 * + 进程内 store + <b>真</b> {@link LeaseService}（跑在 {@link FakeLeaseRedis} 的可控时钟上）。
 *
 * <h2>实例面维度（M8 / DC-11 口径）</h2>
 * 本类固定<b>一个</b> {@link LeaseService} 实例（{@code instance-lock}），用<b>不同 owner</b>
 * （{@code instanceId:runId:toolCallId}）扮演并发。这正是 L1 最容易写错的一面：同一实例内的两个
 * 不同业务持有者必须互斥 —— 若锁只按实例标识记主，下面 S1 会变成"两次都执行"。
 * 跨实例那一面（两个 LeaseService / 两份进程内视图）由 {@code LeaseServiceDistinctOwnerTest} 与
 * {@code fake.advanceTime} 的 TTL 用例覆盖，本类不重复。
 *
 * <h2>为什么把 {@code start_import} 按 BACKEND 披露</h2>
 * 资源锁与确认门<b>正交</b>（确认门管"人要不要做"，资源锁管"现在能不能做"）。本类把风险等级桩成
 * READ，让 {@code start_import} 直接走 {@code executeOnce}，把变量收在锁上；确认门那一面由既有
 * {@code ConfirmGate*} 用例覆盖。
 *
 * <h2>判据</h2>
 * 帧流（{@link FrameWire}）/ 台账（{@code ledger}）/ 待决条目状态 / {@code ai:lock:*} 键的存亡 ——
 * 不断言"某方法被调用过"。
 */
class ToolResourceLockGateTest {

	private static final String RUN_A = "run-lock-a";

	private static final String RUN_B = "run-lock-b";

	private static final String SESSION_ID = "s-lock";

	private static final String CALL_ID = "call-lock-1";

	private static final long TASK_ID = 7L;

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final AiProperties properties = new AiProperties();

	private final FakeRenewScheduler renew = new FakeRenewScheduler();

	private final LeaseService lease = new LeaseService(this.fake.redis, this.properties, this.renew, "instance-lock");

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final RunRegistry registry = new RunRegistry(this.store, new ObjectMapper(), null);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final MessageJsonCodec codec = mock(MessageJsonCodec.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations,
			this.beacon, List.of());

	private final ToolResourceLocks locks = new ToolResourceLocks(this.lease, this.properties);

	private final SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry, this.gate,
			this.toolRegistry, this.codec, this.beacon, this.cancellations, new ToolResultLimiter(this.properties),
			List.of(), null, this.locks, null, null);

	private final FakeStartImportTool startTool = new FakeStartImportTool();

	private final FakeStartExportTool exportTool = new FakeStartExportTool();

	private final FakeStatusTool statusTool = new FakeStatusTool();

	private ExecutorService workers;

	@BeforeEach
	void setUp() {
		this.workers = Executors.newFixedThreadPool(2, runnable -> {
			Thread thread = new Thread(runnable, "lock-gate-worker");
			thread.setDaemon(true);
			return thread;
		});
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.channelOf(anyString())).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.toolRegistry.riskOf(anyString())).thenReturn(ToolMeta.RiskLevel.READ);
		seedRun(RUN_A);
		seedRun(RUN_B);
	}

	@AfterEach
	void tearDown() {
		this.workers.shutdownNow();
		this.fake.heal();
	}

	// ── S1：同 taskId 并发，恰一个执行 ──────────────────────────────────────

	@Test
	void concurrentCallsOnSameTaskExecuteExactlyOnce() throws Exception {
		this.startTool.blocking = true;
		FrameWire wireA = attach(RUN_A);
		FrameWire wireB = attach(RUN_B);

		Future<ToolExecutionResult> first = this.workers
			.submit(() -> this.manager.executeToolCalls(prompt(RUN_A), response("start_import", taskArgs())));
		assertThat(this.startTool.entered.await(5, TimeUnit.SECONDS)).as("第一个调用已进入工具体（锁已到手）").isTrue();
		// I-1（复审裁决）：资源锁<b>不续租</b> —— 持锁期间调度器上不应出现任何续租任务
		assertThat(this.renew.activeCount()).as("I-1：资源锁不续租，持锁期间零续租任务").isZero();

		ToolExecutionResult second = this.manager.executeToolCalls(prompt(RUN_B),
				response("start_import", taskArgs()));
		this.startTool.release.countDown();
		first.get(10, TimeUnit.SECONDS);
		finish(RUN_A);
		finish(RUN_B);

		// 模型侧信封：blocked + RESOURCE_LOCKED + retryable=true（"等会儿再来"是可重试的）
		ToolResultEnvelope envelope = envelopeOf(second);
		assertThat(envelope.outcome()).isEqualTo(ToolOutcome.BLOCKED.wire());
		assertThat(envelope.code()).isEqualTo(ToolOutcome.CODE_RESOURCE_LOCKED);
		assertThat(envelope.ok()).isFalse();
		assertThat(envelope.retryable()).isTrue();
		// ① 恰一次执行：第二次没有进入工具体
		assertThat(this.startTool.invocations.get()).as("同任务并发恰一执行").isEqualTo(1);

		// ② 第二条 blocked / RESOURCE_LOCKED / executed=false（帧面 + 条目面）
		Map<String, Object> blockedFrame = FrameContract.oneOfType(wireB.frames(), "tool_result");
		assertThat(blockedFrame).containsEntry("ok", false)
			.containsEntry("outcome", ToolOutcome.BLOCKED.wire())
			.containsEntry("code", ToolOutcome.CODE_RESOURCE_LOCKED)
			.containsEntry("executed", false)
			.containsEntry("retryable", true)
			.containsEntry("status", PendingToolCall.BLOCKED);
		assertThat(String.valueOf(blockedFrame.get("result"))).contains("该任务已有作业在执行，本次未执行");

		PendingToolCall blockedPending = this.store.pending(RUN_B, CALL_ID);
		assertThat(blockedPending.getStatus()).isEqualTo(PendingToolCall.BLOCKED);
		assertThat(blockedPending.getCode()).isEqualTo(ToolOutcome.CODE_RESOURCE_LOCKED);
		assertThat(blockedPending.isExecuted()).isFalse();
		assertThat(blockedPending.getExecutedBy()).isNull();

		// ③ 台账恰 1 条（被阻断的那次不记账）
		assertThat(this.store.ledger(RUN_B)).isEmpty();
		assertThat(this.store.ledger(RUN_A)).hasSize(1);

		// ④ 执行完释放：两个锁键都消失（TTL 之外没有残留）
		assertThat(this.fake.string(LeaseKeys.task(TASK_ID))).isNull();
		assertThat(this.fake.string(LeaseKeys.job(TASK_ID, "IMPORT"))).isNull();
		// 帧面：A 是成功，B 是阻断（都不抛）
		assertThat(FrameContract.types(wireA.frames())).containsSubsequence("tool_start", "tool_result", "done");
		assertThat(FrameContract.types(wireB.frames())).containsSubsequence("tool_start", "tool_result", "done");
	}

	// ── S2：帧序 tool_start → tool_result(blocked) → 后续照常 → done 无 error ──

	@Test
	void blockedCallKeepsFrameOrderAndDoesNotHaltBatch() {
		// 别人已经持有该任务的锁（预置语料：另一个 owner）
		this.lease.acquire(LeaseKeys.task(TASK_ID), this.properties.getLease().getResourceTtl(), "other:owner");
		FrameWire wire = attach(RUN_A);

		this.manager.executeToolCalls(promptWithBothTools(),
				response(new AssistantMessage.ToolCall("call-lock-1", "function", "start_import", taskArgs()),
						new AssistantMessage.ToolCall("call-lock-2", "function", "check_job_status", "{\"jobId\":1}")));
		finish(RUN_A);

		List<Map<String, Object>> frames = wire.frames();
		assertThat(FrameContract.types(frames)).containsExactly("start", "suspended", "tool_start", "tool_result",
				"tool_start", "tool_result", "done");
		assertThat(FrameContract.types(frames)).doesNotContain("error");
		FrameContract.assertConformant(frames);

		List<Map<String, Object>> results = FrameContract.ofType(frames, "tool_result");
		assertThat(results.get(0)).containsEntry("outcome", ToolOutcome.BLOCKED.wire())
			.containsEntry("code", ToolOutcome.CODE_RESOURCE_LOCKED)
			.containsEntry("executed", false);
		// 后续调用照常执行（haltsBatch 只认 FAILED）
		assertThat(results.get(1)).containsEntry("outcome", ToolOutcome.OK.wire()).containsEntry("executed", true);
		assertThat(this.statusTool.invocations.get()).isEqualTo(1);
		assertThat(this.startTool.invocations.get()).as("被锁挡住的那次没有执行").isZero();
	}

	// ── S3：撞锁不消耗认领（释放后重跑可执行） ─────────────────────────────

	@Test
	void blockedCallDoesNotConsumeClaimAndCanRunAfterLockRelease() {
		LeaseService.Lease other = this.lease
			.acquire(LeaseKeys.task(TASK_ID), this.properties.getLease().getResourceTtl(), "other:owner")
			.lease();
		FrameWire wire = attach(RUN_A);

		this.manager.executeToolCalls(prompt(RUN_A), response("start_import", taskArgs()));
		finish(RUN_A);

		assertThat(FrameContract.oneOfType(wire.frames(), "tool_result")).containsEntry("code",
				ToolOutcome.CODE_RESOURCE_LOCKED);
		assertThat(this.store.claimedBy(RUN_A, CALL_ID)).as("被锁挡住不占 ai:claim:").isNull();
		assertThat(this.store.ledger(RUN_A)).isEmpty();

		// 锁释放后重跑同一 toolCallId（换一轮 run）：可以执行，且台账一条
		this.lease.release(other);
		assertThat(this.fake.string(LeaseKeys.task(TASK_ID))).isNull();
		this.manager.executeToolCalls(prompt(RUN_B), response("start_import", taskArgs()));
		finish(RUN_B);

		assertThat(this.startTool.invocations.get()).isEqualTo(1);
		assertThat(this.store.ledger(RUN_B)).hasSize(1);
	}

	// ── S4：TTL 过期可抢 + 错 owner 不误删 ─────────────────────────────────

	@Test
	void expiredLockCanBeTakenAndWrongOwnerCannotRelease() {
		Duration ttl = this.properties.getLease().getResourceTtl();
		ToolResourceLocks.LockHandle first = this.locks.acquire(TASK_ID, "IMPORT", "owner-a");
		assertThat(first.outcome()).isEqualTo(ToolResourceLocks.LockOutcome.ACQUIRED);
		LeaseService.Lease held = first.created().get(0);

		// 错 owner 的释放：不删别人的键
		assertThat(this.lease.release(new LeaseService.Lease(held.key(), held.epoch(), "owner-x"))).isFalse();
		assertThat(this.fake.string(held.key())).as("错 owner 不误删").isNotNull();

		// TTL 到期：另一个 owner 可以拿到（无需先释放）
		this.fake.advanceTime(ttl.plusSeconds(1));
		assertThat(this.fake.string(LeaseKeys.task(TASK_ID))).isNull();
		ToolResourceLocks.LockHandle second = this.locks.acquire(TASK_ID, "IMPORT", "owner-b");
		assertThat(second.outcome()).isEqualTo(ToolResourceLocks.LockOutcome.ACQUIRED);
		assertThat(this.fake.string(LeaseKeys.task(TASK_ID))).endsWith("|owner-b");
		second.close();
		first.close();
		assertThat(this.fake.string(LeaseKeys.task(TASK_ID))).as("关闭只放自己那一任").isNull();
	}

	// ── S5：异常/终态 finally 释放 + created=false 不释放 ───────────────────

	@Test
	void locksAreReleasedInFinallyEvenWhenToolThrows() {
		this.startTool.failWith = new IllegalStateException("导入执行失败（受控）");
		FrameWire wire = attach(RUN_A);

		this.manager.executeToolCalls(prompt(RUN_A), response("start_import", taskArgs()));
		finish(RUN_A);

		assertThat(FrameContract.oneOfType(wire.frames(), "tool_result")).containsEntry("outcome",
				ToolOutcome.FAILED.wire());
		assertThat(this.fake.string(LeaseKeys.task(TASK_ID))).as("异常路径也必须归还").isNull();
		assertThat(this.fake.string(LeaseKeys.job(TASK_ID, "IMPORT"))).isNull();
		assertThat(this.renew.activeCount()).as("释放路径不遗留续租任务（本层不续租，I-1）").isZero();
	}

	@Test
	void reentrantAcquireHasNoReleaseRight() {
		ToolResourceLocks.LockHandle first = this.locks.acquire(TASK_ID, "IMPORT", "same-owner");
		ToolResourceLocks.LockHandle second = this.locks.acquire(TASK_ID, "IMPORT", "same-owner");

		assertThat(second.outcome()).isEqualTo(ToolResourceLocks.LockOutcome.ACQUIRED);
		assertThat(second.created()).as("重入拿到的租约没有释放权").isEmpty();
		second.close();
		assertThat(this.fake.string(LeaseKeys.task(TASK_ID))).as("重入关闭不得删创建者的锁").isNotNull();

		first.close();
		assertThat(this.fake.string(LeaseKeys.task(TASK_ID))).isNull();
	}

	// ── S6：设施不可用 ⇒ 放行 + WARN ───────────────────────────────────────

	@Test
	void unavailableLeaseFacilityDegradesToExecutionWithWarn() {
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		Logger logger = (Logger) LoggerFactory.getLogger(ToolResourceLocks.class);
		logger.addAppender(appender);
		try {
			this.fake.goDown();
			FrameWire wire = attach(RUN_A);

			this.manager.executeToolCalls(prompt(RUN_A), response("start_import", taskArgs()));
			finish(RUN_A);

			assertThat(this.startTool.invocations.get()).as("锁设施不可用不得把工具一起挡掉").isEqualTo(1);
			assertThat(FrameContract.oneOfType(wire.frames(), "tool_result")).containsEntry("outcome",
					ToolOutcome.OK.wire());
			assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.WARN
					&& event.getFormattedMessage().contains("资源锁设施不可用"));
		}
		finally {
			logger.detachAppender(appender);
		}
	}

	// ── S9：enabled=false 整段跳过（零 Redis 往返） ─────────────────────────

	@Test
	void disabledLockSkipsWholeSectionWithoutRedisRoundTrip() {
		this.properties.getLock().setEnabled(false);
		int before = this.fake.scriptCalls.get();
		FrameWire wire = attach(RUN_A);

		this.manager.executeToolCalls(prompt(RUN_A), response("start_import", taskArgs()));
		finish(RUN_A);

		assertThat(this.startTool.invocations.get()).isEqualTo(1);
		assertThat(FrameContract.oneOfType(wire.frames(), "tool_result")).containsEntry("outcome",
				ToolOutcome.OK.wire());
		assertThat(this.fake.scriptCalls.get()).as("关掉开关即零锁往返").isEqualTo(before);
	}

	// ── M-3（批③b 复审）：只占作业级锁 ⇒ 阻断，且先拿到的任务级锁必须归还 ────

	@Test
	void jobLockOnlyBlocksTheCallAndReturnsTheTaskLockItJustTook() {
		// 只占作业级锁、任务级锁空闲 ⇒ 调用方先拿到任务锁，再被作业锁挡下
		this.lease.acquire(LeaseKeys.job(TASK_ID, "IMPORT"), this.properties.getLease().getResourceTtl(), "other:owner");
		assertThat(this.fake.string(LeaseKeys.task(TASK_ID))).as("语料：任务级锁确实空闲").isNull();
		FrameWire wire = attach(RUN_A);

		this.manager.executeToolCalls(prompt(RUN_A), response("start_import", taskArgs()));
		finish(RUN_A);

		assertThat(FrameContract.oneOfType(wire.frames(), "tool_result"))
			.containsEntry("ok", false)
			.containsEntry("outcome", ToolOutcome.BLOCKED.wire())
			.containsEntry("code", ToolOutcome.CODE_RESOURCE_LOCKED)
			.containsEntry("executed", false);
		// 被 job 锁挡下 ⇒ 本次创建的 task 锁必须就地归还（否则白占一个 resource-ttl）
		assertThat(this.fake.string(LeaseKeys.task(TASK_ID))).as("本次创建的 task 锁已归还").isNull();
		assertThat(this.fake.string(LeaseKeys.job(TASK_ID, "IMPORT"))).as("别人的 job 锁不得误删")
			.endsWith("|other:owner");
		assertThat(this.startTool.invocations.get()).isZero();
		assertThat(this.store.claimedBy(RUN_A, CALL_ID)).as("被挡不占认领").isNull();
		assertThat(this.store.ledger(RUN_A)).isEmpty();
	}

	/**
	 * M-3 的字面口径对照（裁决原文写作「预置 job:IMPORT → start_export 被 blocked」，但两者作业类型不同，
	 * 第二把锁带作业类型 ⇒ 这条组合在设计上<b>不应</b>被挡）。本用例把这一面钉住：不同类型的作业可以并行。
	 */
	@Test
	void anotherJobTypeDoesNotBlockTheSameTask() {
		this.lease.acquire(LeaseKeys.job(TASK_ID, "IMPORT"), this.properties.getLease().getResourceTtl(), "other:owner");
		FrameWire wire = attach(RUN_A);

		this.manager.executeToolCalls(prompt(RUN_A, List.of(this.exportTool)), response("start_export", taskArgs()));
		finish(RUN_A);

		assertThat(FrameContract.oneOfType(wire.frames(), "tool_result")).containsEntry("outcome", ToolOutcome.OK.wire())
			.containsEntry("executed", true);
		assertThat(this.exportTool.invocations.get()).isEqualTo(1);
		assertThat(this.fake.string(LeaseKeys.task(TASK_ID))).as("终态归还 task 锁").isNull();
		assertThat(this.fake.string(LeaseKeys.job(TASK_ID, "IMPORT"))).as("别的作业类型不受影响").isNotNull();
	}

	// ── 桩与辅助 ────────────────────────────────────────────────────────────

	private void seedRun(String runId) {
		RunSnapshot snapshot = this.store.seedRun(runId, SESSION_ID);
		snapshot.setContext(AiContext.of("import", "IMPORT", "IMPORT", TASK_ID, null).toMap());
		this.store.save(snapshot);
	}

	private FrameWire attach(String runId) {
		SseChatEmitter out = this.registry.of(runId);
		FrameWire wire = FrameWire.attachTo(out);
		out.start(SESSION_ID);
		return wire;
	}

	private void finish(String runId) {
		this.registry.of(runId).done(Map.of(), "deepseek-flash", Map.of("cancelled", false));
	}

	private static String taskArgs() {
		return "{\"taskId\":" + TASK_ID + "}";
	}

	private Prompt prompt(String runId) {
		return prompt(runId, List.of(this.startTool));
	}

	private Prompt promptWithBothTools() {
		return prompt(RUN_A, List.of(this.startTool, this.statusTool));
	}

	private Prompt prompt(String runId, List<Object> tools) {
		List<ToolCallback> callbacks = List.of(MethodToolCallbackProvider.builder()
			.toolObjects(tools.toArray())
			.build()
			.getToolCallbacks());
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(callbacks)
			.toolContext(Map.of("runId", runId, "sessionId", SESSION_ID))
			.build();
		return new Prompt(List.of(new UserMessage("请发起导入")), options);
	}

	private static ChatResponse response(String tool, String args) {
		return response(new AssistantMessage.ToolCall(CALL_ID, "function", tool, args));
	}

	private static ChatResponse response(AssistantMessage.ToolCall... calls) {
		AssistantMessage assistant = AssistantMessage.builder().toolCalls(List.of(calls)).build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

	/** 受控替身：{@code start_import}（可阻塞/可抛错，用来把并发窗口确定化）。 */
	static class FakeStartImportTool {

		final AtomicInteger invocations = new AtomicInteger();

		final CountDownLatch entered = new CountDownLatch(1);

		final CountDownLatch release = new CountDownLatch(1);

		volatile boolean blocking;

		volatile RuntimeException failWith;

		@Tool(name = "start_import", description = "受控替身：启动导入作业")
		public String startImport(@ToolParam(description = "任务ID") Long taskId) throws InterruptedException {
			this.invocations.incrementAndGet();
			this.entered.countDown();
			if (this.blocking) {
				this.release.await(10, TimeUnit.SECONDS);
			}
			if (this.failWith != null) {
				throw this.failWith;
			}
			return "导入作业已启动（作业 #4221）";
		}

	}

	/** 受控替身：{@code start_export}（M-3 的"不同作业类型不互相阻塞"对照）。 */
	static class FakeStartExportTool {

		final AtomicInteger invocations = new AtomicInteger();

		@Tool(name = "start_export", description = "受控替身：启动导出作业")
		public String startExport(@ToolParam(description = "任务ID") Long taskId) {
			this.invocations.incrementAndGet();
			return "导出作业已启动（作业 #5001）";
		}

	}

	/** 同批第二个调用（证明被锁挡住的那条不触发停批）。 */
	static class FakeStatusTool {

		final AtomicInteger invocations = new AtomicInteger();

		@Tool(name = "check_job_status", description = "受控替身：查作业状态")
		public String checkJobStatus(@ToolParam(description = "作业ID") Long jobId) {
			this.invocations.incrementAndGet();
			return "作业 #" + jobId + " 状态:RUNNING";
		}

	}

	/** 模型侧信封（阻断路径上必须是 blocked，不被裁成空串）。 */
	private static ToolResultEnvelope envelopeOf(ToolExecutionResult result) {
		return result.conversationHistory()
			.stream()
			.filter(ToolResponseMessage.class::isInstance)
			.map(ToolResponseMessage.class::cast)
			.flatMap(message -> message.getResponses().stream())
			.map(response -> ToolResultEnvelope.fromJson(response.responseData()))
			.findFirst()
			.orElseThrow();
	}

}
