package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.common.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>B1 的派发边界</b>：确定不可用（{@code PROVIDER_GONE}）时，<b>两条入口都必须收敛</b> ——
 * 直接决策（{@code POST /api/ai/confirm} 的回灌路径）与决策后重挂的自愈对账
 * （{@code GET /api/ai/events/{runId}} 发现"条目已决 + 快照仍 SUSPENDED + 无驱动方"）。
 *
 * <h2>为什么这条用例必须走真实的 {@link ResumeService}</h2>
 * 两个入口最终都落到 {@code ConfirmGate#runResume} 这一个派发点；收敛写在
 * {@link ResumeService} 的硬否定处，所以只要有<b>任何一条</b>入口漏了收敛，客户端就会挂在
 * "不会再有帧"的连接上（实测 120s 超时）。本用例用真实服务 + 真实写出器（{@link RunRegistry}
 * 三参装配，帧落 {@link InMemoryRunStore} 事件日志）：断言的是<b>对外可见的终帧与终态</b>，
 * 不是"某方法被调用过"。
 *
 * <h2>复核 Critical-1：两条入口也必须保留"不可知 ≠ 判死"</h2>
 * 配置刷新失败 / key 此刻解不开时：快照保持 {@code SUSPENDED}、归档里没有终帧，并且<b>当下挂着的
 * 订阅必须被收掉</b> —— 否则客户端会挂上一条永远不会再有帧的连接（无界 emitter）。
 */
class ResumeProviderGoneDispatchTest {

	private static final String RUN_ID = "run-gone-dispatch";

	private static final String SESSION_ID = "s-gone-dispatch";

	private static final String TOOL_CALL_ID = "call-gone";

	private static final Long PROVIDER_ID = 7L;

	private static final String MODEL_ID = "deepseek-flash";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final AiProperties properties = new AiProperties();

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final AiConfigService configService = mock(AiConfigService.class);

	private ConfirmGate gate;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		this.properties.getPark().setEnabled(true);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any())).thenAnswer(invocation -> (AutoCloseable) () -> {
		});

		// 硬否定：库里真的没有这一行（刷新成功 + 行级声明也没有）
		when(this.configService.resolveChatTarget(any(Long.class), anyString())).thenReturn(Optional.empty());
		when(this.configService.reloadWithOutcome())
			.thenReturn(new AiConfigService.ReloadResult(AiConfigService.ConfigSnapshot.empty(), true));
		when(this.configService.bindingState(any(Long.class), anyString()))
			.thenReturn(AiConfigService.BindingState.ABSENT);
		ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(executor).execute(any(Runnable.class));
		ResumeService resumeService = new ResumeService(this.store, this.registry, this.codec,
				mock(ResilientChatService.class), mock(SpToolCallingManager.class), executor,
				mock(StringRedisTemplate.class), this.properties, mock(CancellationRegistry.class),
				mock(ToolResultLimiter.class));
		ReflectionTestUtils.setField(resumeService, "aiConfigService", this.configService);

		ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(resumeService);
		this.gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations,
				new ToolActivityBeacon(), List.of(), provider, Runnable::run, null, (task, delay) -> {
					task.run();
					return () -> {
					};
				});
	}

	@Test
	void reattachSelfHealConvergesToAFailedTerminalFrame() {
		seedSuspendedRun();

		// GET /events/{runId} 的"已决但无驱动方"自愈对账入口
		ConfirmGate.ResumeTrigger trigger = this.gate.triggerResumeForParked(RUN_ID);

		assertThat(trigger.triggered()).isTrue();
		assertTerminalConvergence();
	}

	@Test
	void directConfirmConvergesToAFailedTerminalFrame() {
		seedSuspendedRun();
		seedPendingConfirm();

		ConfirmGate.Submission submission = this.gate.submitDecision(RUN_ID, TOOL_CALL_ID, true, "ok");

		assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		assertTerminalConvergence();
	}

	// ── 复核 Critical-1：两条入口都必须保留"不可知 ≠ 判死"，且不得留下无界 emitter ──

	/**
	 * 重挂自愈遇到"配置刷新失败"：这一轮保持 SUSPENDED，归档里不得出现任何业务帧，
	 * 且<b>当前订阅必须被收掉</b> —— 否则客户端会挂在一条永远不会再有帧的连接上（120s 超时）。
	 */
	@Test
	void reattachSelfHealOnUnknownConfigReleasesTheHungStream() {
		when(this.configService.reloadWithOutcome())
			.thenReturn(new AiConfigService.ReloadResult(AiConfigService.ConfigSnapshot.empty(), false));
		seedSuspendedRun();
		attachRealSubscriber();

		this.gate.triggerResumeForParked(RUN_ID);

		assertUnknownKeepsSuspendedAndReleases();
	}

	/** 直接决策同样保留区分：行在但 key 解不开 ⇒ 不判死、不出终帧、连接不再悬挂。 */
	@Test
	void directConfirmOnUnreadableKeyReleasesTheHungStream() {
		when(this.configService.bindingState(PROVIDER_ID, MODEL_ID))
			.thenReturn(AiConfigService.BindingState.KEY_UNREADABLE);
		seedSuspendedRun();
		seedPendingConfirm();
		attachRealSubscriber();

		ConfirmGate.Submission submission = this.gate.submitDecision(RUN_ID, TOOL_CALL_ID, true, "ok");

		assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		assertUnknownKeepsSuspendedAndReleases();
	}

	/** 真弄一条"服务端连接"上来（真实 emitter + 归档回放），好让"收流"这件事可断言。 */
	private void attachRealSubscriber() {
		org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
				new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(120000L);
		this.registry.of(RUN_ID).replayAndAttach(emitter, 0L, false);
		assertThat(this.registry.of(RUN_ID).subscriberCount()).as("前置：确有一条订阅挂着").isEqualTo(1);
	}

	private void assertUnknownKeepsSuspendedAndReleases() {
		assertThat(this.store.get(RUN_ID).getStatus()).as("不可知 ⇒ 不得判死").isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(this.store.events(RUN_ID))
			.as("不可知 ⇒ 不得写终帧（done/error）；决策回执这类非终帧不受影响")
			.noneMatch(frame -> "done".equals(frame.get("type")) || "error".equals(frame.get("type")));
		assertThat(this.registry.find(RUN_ID)).as("写出器条目可以留着（轮次还没结束）").isNotNull();
		assertThat(this.registry.find(RUN_ID).subscriberCount())
			.as("挂着的连接必须被收掉（否则重挂 = 无界 emitter，客户端只能等 120s 超时）").isZero();
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private void assertTerminalConvergence() {
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).as("确定不可用 ⇒ FAILED 终态").isEqualTo(RunSnapshot.FAILED);
		assertThat(snapshot.getError()).as("机器可读前缀").startsWith("PROVIDER_GONE");
		List<Map<String, Object>> frames = this.store.events(RUN_ID);
		Map<String, Object> last = frames.isEmpty() ? null : frames.get(frames.size() - 1);
		assertThat(last).as("必须落一帧终帧（重挂回放它之后连接才收流）").isNotNull();
		assertThat(last.get("type")).isEqualTo("error");
		assertThat(last.get("code")).isEqualTo(ErrorCodes.AI_PROVIDER_GONE);
	}

	private void seedSuspendedRun() {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setProviderId(PROVIDER_ID);
		snapshot.setModelId(MODEL_ID);
		snapshot.setContext(AiContext.empty().toMap());
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("你好"))));
		this.store.save(snapshot);
	}

	private void seedPendingConfirm() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(TOOL_CALL_ID);
		pending.setName("start_publish");
		pending.setType("function");
		pending.setArguments("{}");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		pending.setStatus(PendingToolCall.PENDING);
		this.store.seedPending(RUN_ID, pending);
	}

}
