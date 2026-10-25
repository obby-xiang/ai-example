package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunParkedException;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>park 退出窗口内的决策：必须有且只有一个驱动方</b>（主 CI run 40174289141 的 H-01 回归位）。
 *
 * <h2>被钉住的缺陷</h2>
 * {@code arm} 在发帧<b>之前</b>预登记 Latch（F8）。park 分支在"预登记之后、Latch 摘除之前"
 * 还要做一次租约释放（Redis 往返，实测窗口 1–3ms）。决策正好落在窗口里时：
 * {@code signal} 命中这个<b>没人 await</b> 的 Latch ⇒ {@code woke=true} ⇒
 * {@code triggerResume} 以 {@code WOKE_IN_PROCESS_GATE} 静默跳过；随后 park 线程摘 Latch 退出
 * ⇒ 一轮既没有驱动方、也没有终帧，快照永久停在 SUSPENDED（CI 里客户端在重挂流上无限阻塞）。
 *
 * <h2>本批口径</h2>
 * park 退出是一次<b>原子生命周期切换</b>（与决策写共用 {@code submitLock}）：
 * <ul>
 * <li>切换时本卡已决 ⇒ 等待线程仍在，直接接手驱动（不 park，不派发续跑）；</li>
 * <li>切换时本卡未决 ⇒ 先还租、再摘掉本 run 的<b>全部</b>等待闸门 ⇒ 之后的决策 signal 必 miss
 *     ⇒ 死卡兜底派发<b>恰好一次</b>续跑。只"先 disarm 再 throw"不够：仍留下"signal 命中后才 disarm"
 *     的窗口。</li>
 * </ul>
 * 两个方向各一条用例（窗口内 / park 之后），外加 park 关闭时行为不变的对照。
 */
class ConfirmGateParkExitRaceTest {

	private static final String RUN_ID = "run-park-exit-race";

	private static final String SESSION_ID = "s-park-exit-race";

	private static final String CALL_ID = "call-form-race";

	private final ObjectMapper mapper = new ObjectMapper();

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = new ToolActivityBeacon();

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final ResumeService resumeService = mock(ResumeService.class);

	private final AiProperties properties = new AiProperties();

	/** 实际被派发的续跑次数（缺省 0）。 */
	private final AtomicInteger resumes = new AtomicInteger();

	private ConfirmGate gate;

	private SpToolCallingManager manager;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		this.properties.getPark().setEnabled(true);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
		when(this.cancellations.onCancel(anyString(), any())).thenAnswer(invocation -> (AutoCloseable) () -> {
		});
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.channelOf("generative_form")).thenReturn(ToolMeta.Channel.FRONTEND);
		when(this.toolRegistry.riskOf("generative_form")).thenReturn(ToolMeta.RiskLevel.WRITE);
		when(this.resumeService.resume(anyString())).thenAnswer(invocation -> {
			this.resumes.incrementAndGet();
			return new ResumeService.ResumeResult(ResumeService.Outcome.ACCEPTED, Map.of("resumed", true));
		});
		ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(this.resumeService);
		this.gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations, this.beacon,
				List.of(), provider, Runnable::run, null, ConfirmGate.DEFAULT_RETRY_SCHEDULER);
		this.manager = new SpToolCallingManager(this.store, this.registry, this.gate, this.toolRegistry,
				mock(MessageJsonCodec.class), this.beacon, this.cancellations,
				new ToolResultLimiter(this.properties), List.of());
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);
	}

	/**
	 * <b>核心用例（本次 CI 的形态）</b>：决策落在 park 退出窗口内 —— 闸门仍是"预登记、无人等待"。
	 * 无论修法选"等待线程接手"还是"补派续跑"，都必须<b>恰好一个驱动方</b>；旧实现是 0 个。
	 */
	@Test
	void aDecisionLandingInsideTheParkExitWindowStillGetsExactlyOneDriver() throws Exception {
		CountDownLatch enteredWindow = new CountDownLatch(1);
		CountDownLatch releasePark = new CountDownLatch(1);
		AtomicBoolean blocked = new AtomicBoolean(false);
		// 阻塞点必须落在 {@code await} 内部：闸门已登记（await:558）之后、park 分支（await:601）之前。
		// 这里用取消监听注册（await:561）—— 它只在这条等待路径上被调用一次，且已在
		// {@code reserveRequestSlot} 之后（否则决策会被"已决"分支提前消费，复现不出窗口）。
		// 不能用 {@code cancelState}：{@code SpToolCallingManager#checkCancelled} 在执行工具前也会调它，
		// 阻塞就会发生在 reserveRequestSlot 之前，park 分支根本不会被走到（假 RED）。
		when(this.cancellations.onCancel(anyString(), any())).thenAnswer(invocation -> {
			if (blocked.compareAndSet(false, true)) {
				enteredWindow.countDown();
				assertThat(releasePark.await(5, TimeUnit.SECONDS)).as("测试必须放开 park 线程").isTrue();
			}
			return (AutoCloseable) () -> {
			};
		});

		AtomicBoolean completed = new AtomicBoolean(false);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread parkThread = new Thread(() -> {
			try {
				this.manager.executeToolCalls(prompt(), response());
				completed.set(true);
			}
			catch (Throwable error) {
				failure.set(error);
			}
		}, "park-exit-race");
		parkThread.start();
		assertThat(enteredWindow.await(5, TimeUnit.SECONDS)).as("park 分支应到达窗口").isTrue();

		assertThat(this.gate.hasActiveLatch(RUN_ID))
			.as("复现前提：窗口内本 run 的等待闸门仍登记在表里（没有人 await 它）")
			.isTrue();
		ConfirmGate.Submission decision = this.gate.submitFrontendResult(RUN_ID, CALL_ID,
				"{\"keyword\":\"E2E\"}", "park-exit-race");
		assertThat(decision.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		assertThat(decision.woke()).as("决策命中了这个没人等待的闸门（H-01 的形态）").isTrue();
		assertThat(decision.resumeTrigger().triggered()).as("命中在途闸门 ⇒ 决策侧按约定不派发").isFalse();

		releasePark.countDown();
		parkThread.join(5000);
		assertThat(parkThread.isAlive()).isFalse();

		int drivers = (completed.get() ? 1 : 0) + this.resumes.get();
		assertThat(drivers)
			.as("恰好一个驱动方：窗口内已决由等待线程接手（旧实现 0 个 ⇒ 悬轮 / CI 取消）")
			.isEqualTo(1);
		assertThat(completed.get()).as("窗口内已决 ⇒ 不得 park 走人，必须接手驱动").isTrue();
		assertThat(failure.get()).isNull();
		assertThat(this.resumes.get()).isZero();
		assertThat(this.store.pending(RUN_ID, CALL_ID).getStatus()).isEqualTo(PendingToolCall.FRONTEND_RESULT);
		assertThat(this.store.get(RUN_ID).getInFlightToolCalls()).as("落定时清空 in-flight").isEmpty();
		assertThat(countFrames("frontend_tool_result"))
			.as("结局帧恰一条（接手驱动只结算一次）")
			.isEqualTo(1L);
	}

	/** 另一侧：park 已完整退出（闸门已摘）后的决策 ⇒ 恰好一次续跑派发。 */
	@Test
	void aDecisionAfterTheParkExitDispatchesExactlyOneResume() {
		assertThatThrownBy(() -> this.manager.executeToolCalls(prompt(), response()))
			.as("无人等待时 park 是控制流")
			.isInstanceOf(RunParkedException.class);
		assertThat(this.gate.hasActiveLatch(RUN_ID)).as("park 之后不得留下任何等待闸门").isFalse();

		ConfirmGate.Submission decision = this.gate.submitFrontendResult(RUN_ID, CALL_ID,
				"{\"keyword\":\"E2E\"}", "after-park");

		assertThat(decision.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		assertThat(decision.woke()).as("没有等待方 ⇒ signal 必 miss").isFalse();
		assertThat(decision.resumeTrigger().triggered()).isTrue();
		assertThat(decision.resumeTrigger().outcome()).isEqualTo("SCHEDULED");
		assertThat(this.resumes.get()).as("恰好一次续跑派发（不多不少）").isEqualTo(1);
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
	}

	/** 对照：park 关闭时行为不变 —— 等待线程被真实唤醒并继续驱动，不派发续跑。 */
	@Test
	void withoutParkTheWaitingThreadKeepsDrivingAndNoResumeIsDispatched() throws Exception {
		this.properties.getPark().setEnabled(false);
		CountDownLatch enteredLoop = new CountDownLatch(1);
		CountDownLatch releaseLoop = new CountDownLatch(1);
		AtomicBoolean blocked = new AtomicBoolean(false);
		// 与窗口用例同一个阻塞点（await 内、闸门登记之后），保证这里走的是"真的进入等待再被唤醒"。
		when(this.cancellations.onCancel(anyString(), any())).thenAnswer(invocation -> {
			if (blocked.compareAndSet(false, true)) {
				enteredLoop.countDown();
				assertThat(releaseLoop.await(5, TimeUnit.SECONDS)).as("测试必须放开等待线程").isTrue();
			}
			return (AutoCloseable) () -> {
			};
		});

		AtomicBoolean completed = new AtomicBoolean(false);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread waitThread = new Thread(() -> {
			try {
				this.manager.executeToolCalls(prompt(), response());
				completed.set(true);
			}
			catch (Throwable error) {
				failure.set(error);
			}
		}, "waiting-thread");
		waitThread.start();
		assertThat(enteredLoop.await(5, TimeUnit.SECONDS)).as("非 park 路径应进入等待循环").isTrue();
		assertThat(this.gate.hasActiveLatch(RUN_ID)).isTrue();

		ConfirmGate.Submission decision = this.gate.submitFrontendResult(RUN_ID, CALL_ID,
				"{\"keyword\":\"E2E\"}", "non-park");
		releaseLoop.countDown();
		waitThread.join(5000);
		assertThat(waitThread.isAlive()).isFalse();

		assertThat(decision.woke()).as("真实等待方 ⇒ 唤醒命中").isTrue();
		assertThat(decision.resumeTrigger().triggered()).isFalse();
		assertThat(completed.get()).as("等待线程继续驱动（非 park 语义不变）").isTrue();
		assertThat(failure.get()).isNull();
		assertThat(this.resumes.get()).isZero();
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private long countFrames(String type) {
		return this.store.archivedTypes(RUN_ID).stream().filter(type::equals).count();
	}

	private static Prompt prompt() {
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(List.of())
			.toolContext(Map.of("runId", RUN_ID))
			.build();
		return new Prompt(List.of(new UserMessage("出一张筛选表单")), options);
	}

	private static ChatResponse response() {
		AssistantMessage assistant = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall(CALL_ID, "function", "generative_form",
					"{\"form\":{\"scenario\":\"FILTER\"}}")))
			.build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

}
