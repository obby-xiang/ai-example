package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.FakeLeaseRedis;
import com.example.configmgr.ai.run.FakeRenewScheduler;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证性实验（非回归用例，保留作定案证据）：<b>孤儿轮多张 HITL 待决卡，批准其中一张后续跑能否走通</b>。
 *
 * <h2>争议</h2>
 * <ul>
 * <li>甲方：一轮里有 3 张待决确认卡，用户批准第 1 张后，续跑应当能继续走；</li>
 * <li>乙方（静态分析）：批准后走 {@code ConfirmGate.maybeTriggerResume} → 抢租 →
 *     {@code ResumeService.resume}，其中"仍有 PENDING 外部条目"闸门（第 2、3 张未决）返回
 *     {@code PENDING_UNRESOLVED}，不驱动、不发帧。</li>
 * </ul>
 *
 * <h2>实验设置（全部走生产路径，不 mock 被测决策）</h2>
 * <ul>
 * <li>快照：{@code SUSPENDED} + {@code inFlightToolCalls} = N 条 {@code kind=CONFIRM} 条目，
 *     条目表里同 N 条均 {@code PENDING}、{@code req=null}（模拟升级前遗留的孤儿轮：无 latch、无租约）；</li>
 * <li>决策：{@code gate.submitDecision(runId, "call-1", true, ...)} —— 与 HTTP 端点同一条路径
 *     （内含 {@code maybeTriggerResume}）；</li>
 * <li>续跑：{@code maybeTriggerResume} 派发的任务在测试线程内同步执行（捕获执行器），
 *     真实调用 {@code ResumeService.resume(runId,false,lease)}；用一个记录型子类把返回值抓出来；</li>
 * <li>租约：真实 {@link LeaseService} + {@link FakeLeaseRedis}（epoch 单调、脚本语义真跑）；</li>
 * <li>驱动：{@link ResilientChatService} 为 mock，{@code driveResumed} 是"是否真的开始跑模型"的唯一观测点。</li>
 * </ul>
 */
class OrphanResumePendingUnresolvedExperimentTest {

	private static final String RUN_ID = "run-orphan-hitl";

	private static final String SESSION_ID = "s-orphan-hitl";

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final AiProperties properties = new AiProperties();

	private final FakeRenewScheduler renewScheduler = new FakeRenewScheduler();

	private final LeaseService leases = new LeaseService(this.fake.redis, this.properties, this.renewScheduler,
			"inst-a");

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final ResilientChatService chatService = mock(ResilientChatService.class);

	private final SpToolCallingManager toolCallingManager = mock(SpToolCallingManager.class);

	private final ThreadPoolTaskExecutor suspendExecutor = mock(ThreadPoolTaskExecutor.class);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	/** 触发池：只捕获不执行，由用例显式 drain（与 ConfirmGateDeadlockResumeTest 同形）。 */
	private final List<Runnable> dispatched = Collections.synchronizedList(new ArrayList<>());

	@SuppressWarnings("unchecked")
	private final ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);

	/** 记录真实 resume 的返回值（ConfirmGate.runResume 只打日志，不向外返回）。 */
	private final AtomicReference<ResumeService.ResumeResult> resumeResult = new AtomicReference<>();

	private final ResumeService recordingResume;

	private final ConfirmGate gate;

	OrphanResumePendingUnresolvedExperimentTest() {
		this.store.useLeaseFacility(this.leases);
		this.recordingResume = new ResumeService(this.store, this.registry, this.codec, this.chatService,
				this.toolCallingManager, this.suspendExecutor, this.redis, this.properties, this.cancellations,
				this.limiter) {

			@Override
			public ResumeResult resume(String runId, boolean forceTakeover, LeaseService.Lease lease) {
				ResumeResult result = super.resume(runId, forceTakeover, lease);
				resumeResult.set(result);
				return result;
			}

		};
		this.gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations, this.beacon,
				List.of(), this.provider, this.dispatched::add, this.leases);
	}

	@BeforeEach
	void stubCommon() {
		when(this.provider.getIfAvailable()).thenReturn(this.recordingResume);
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
		when(this.chatService.buildResumedPrompt(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any()))
			.thenReturn(new Prompt(List.of()));
		when(this.toolCallingManager.resolvePending(eq(RUN_ID), any(Prompt.class), any(PendingToolCall.class)))
			.thenReturn("已执行");
		// 续跑驱动同步跑完：断言的是"有没有被驱动"，不是线程时序
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(this.suspendExecutor).execute(any(Runnable.class));
	}

	// ── 场景 1：3 张待决卡，批准第 1 张 ──────────────────────────────────────

	@Test
	void approvingTheFirstOfThreePendingConfirmCardsIsRefusedWithPendingUnresolved() {
		seedSuspended(3);

		ConfirmGate.Submission submission = this.gate.submitDecision(RUN_ID, "call-1", true, "同意");

		// 决策本身落库了，死卡兜底也确实派发了（所以不是"没触发"，是"触发了但被闸门拦下"）
		assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		assertThat(submission.woke()).as("孤儿轮无本进程 latch ⇒ woke=false").isFalse();
		assertThat(submission.resumeTrigger().triggered()).isTrue();
		assertThat(submission.resumeTrigger().outcome()).isEqualTo("SCHEDULED");
		assertThat(this.dispatched).hasSize(1);
		assertThat(this.store.pending(RUN_ID, "call-1").getStatus()).isEqualTo(PendingToolCall.APPROVED);

		drainDispatch();

		ResumeService.ResumeResult result = this.resumeResult.get();
		assertThat(result).as("续跑真的被调到了（不是派发丢失）").isNotNull();
		assertThat(result.outcome()).as("批准第 1 张后，续跑被 PENDING_UNRESOLVED 拒绝")
			.isEqualTo(ResumeService.Outcome.PENDING_UNRESOLVED);
		assertThat(result.body()).containsEntry("resumed", false).containsEntry("reason", "PENDING_UNRESOLVED");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> unresolved = (List<Map<String, Object>>) result.body().get("unresolved");
		assertThat(unresolved).extracting(item -> item.get("toolCallId"))
			.as("被闸门点名的正是未决的第 2、3 张")
			.containsExactlyInAnyOrder("call-2", "call-3");

		// 没有驱动、没有帧、状态没动
		verify(this.chatService, never()).driveResumed(any(), any(), any(), any(), any());
		verify(this.suspendExecutor, never()).execute(any(Runnable.class));
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(this.store.get(RUN_ID).getInFlightToolCalls()).hasSize(3);
		assertThat(this.store.ledger(RUN_ID)).as("没有任何工具被补执行").isEmpty();
		assertThat(this.store.pending(RUN_ID, "call-2").getStatus()).isEqualTo(PendingToolCall.PENDING);
		assertThat(this.store.pending(RUN_ID, "call-3").getStatus()).isEqualTo(PendingToolCall.PENDING);
		// 拒绝结局在归还白名单内：租约当场还掉，不留悬挂
		assertThat(this.store.heldRunEpoch(RUN_ID)).isZero();
		assertThat(this.fake.string(this.store.runLeaseKey(RUN_ID))).isNull();
	}

	// ── 场景 2（对照）：只有 1 张待决卡，批准它 ─────────────────────────────

	@Test
	void approvingTheOnlyPendingConfirmCardDrivesTheResume() {
		seedSuspended(1);

		ConfirmGate.Submission submission = this.gate.submitDecision(RUN_ID, "call-1", true, "同意");

		assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		assertThat(submission.woke()).isFalse();
		assertThat(submission.resumeTrigger().triggered()).isTrue();
		assertThat(this.dispatched).hasSize(1);

		drainDispatch();

		ResumeService.ResumeResult result = this.resumeResult.get();
		assertThat(result).isNotNull();
		assertThat(result.outcome()).as("唯一一张卡已决 ⇒ 续跑放行").isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(result.body()).containsEntry("resumed", true);
		verify(this.chatService, times(1)).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any(),
				eq(this.emitter));
		assertThat(this.store.get(RUN_ID).getStatus()).as("交棒给驱动 ⇒ RUNNING").isEqualTo(RunSnapshot.RUNNING);
		assertThat(this.store.get(RUN_ID).getInFlightToolCalls()).isEmpty();
		assertThat(this.store.heldRunEpoch(RUN_ID)).as("ACCEPTED ⇒ 租约交给驱动方续租").isPositive();
	}

	// ── 场景 3（补充）：3 张卡逐张批准，何时才真正续上 ─────────────────────

	@Test
	void theResumeOnlyProceedsAfterEveryPendingCardHasBeenDecided() {
		seedSuspended(3);

		assertThat(decideAndResume("call-1")).as("第 1 张批准后：仍被第 2、3 张挡住")
			.isEqualTo(ResumeService.Outcome.PENDING_UNRESOLVED);
		assertThat(decideAndResume("call-2")).as("第 2 张批准后：仍被第 3 张挡住")
			.isEqualTo(ResumeService.Outcome.PENDING_UNRESOLVED);
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.SUSPENDED);

		assertThat(decideAndResume("call-3")).as("最后一张批准后：闸门消失，续跑放行")
			.isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.RUNNING);
		verify(this.chatService, times(1)).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any(),
				eq(this.emitter));
	}

	// ───────────────────────── helpers ─────────────────────────

	/** 批准指定卡并同步跑完派发出来的续跑任务，返回本次 resume 的真实结局。 */
	private ResumeService.Outcome decideAndResume(String toolCallId) {
		this.resumeResult.set(null);
		this.gate.submitDecision(RUN_ID, toolCallId, true, "同意");
		drainDispatch();
		assertThat(this.resumeResult.get()).isNotNull();
		return this.resumeResult.get().outcome();
	}

	/**
	 * 铺一个"升级前遗留的孤儿轮"：SUSPENDED + {@code count} 张 CONFIRM 卡均 PENDING、
	 * 请求位 {@code req=null}（从未置位）、无 latch、无租约。
	 */
	private void seedSuspended(int count) {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setContext(AiContext.of("sheet", "SHEET", "SHEET", 7L, null).toMap());
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("请更新三张表"))));
		List<PendingToolCall> items = new ArrayList<>();
		for (int index = 1; index <= count; index++) {
			PendingToolCall pending = new PendingToolCall();
			pending.setToolCallId("call-" + index);
			pending.setName("update_sheet");
			pending.setType("function");
			pending.setKind(PendingToolCall.KIND_CONFIRM);
			pending.setArguments("{\"sheetId\":" + index + "}");
			pending.setStatus(PendingToolCall.PENDING);
			pending.setReq(null);
			pending.setRequestedAtMs(System.currentTimeMillis());
			items.add(pending);
			this.store.seedPending(RUN_ID, pending);
		}
		snapshot.setInFlightToolCalls(new ArrayList<>(items));
		this.store.save(snapshot);
	}

	private void drainDispatch() {
		synchronized (this.dispatched) {
			this.dispatched.forEach(Runnable::run);
			this.dispatched.clear();
		}
	}

}
