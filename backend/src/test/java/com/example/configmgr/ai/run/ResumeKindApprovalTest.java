package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 续跑侧的 kind 校验（第 6 项·续跑假批准）：{@code APPROVED + FRONTEND} 一律<b>不执行后端桩</b>、
 * <b>不写成功台账</b>，显式结算成 {@code blocked}/{@code TOOL_KIND_MISMATCH}。
 *
 * <h2>这条状态是怎么来的</h2>
 * 前端条目只有"回灌结果"一条合法出口。旧代码不校验入口 kind，于是把确认卡的 toolCallId 发到
 * {@code /api/ai/frontend-tool-result}（或反过来）也能写成功 —— 前端条目被写成
 * {@code APPROVED} 后，进程重启续跑时 {@code resolvePending} 会把 APPROVED 当"已批准"去执行
 * 后端哨兵桩，并写一条成功台账。那是一笔<b>没有任何人点过、也没有任何前端真正执行过</b>的假执行。
 *
 * <h2>本类锁两层</h2>
 * <ol>
 * <li>{@link SpToolCallingManager#resolvePending}：APPROVED+FRONTEND → blocked（实证面：无认领、
 * 无台账、executed=false、结局帧带机器码）；</li>
 * <li>{@link ResumeService#resume}：走完整续跑链后，条目仍是 blocked，轮次照常交棒
 * （不因一条假批准卡死，也不把它说成成功）。</li>
 * </ol>
 */
class ResumeKindApprovalTest {

	private static final String RUN = "run-resume-kind";

	private static final String SESSION = "s-resume-kind";

	private static final String CALL = "call-resume-kind";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final ResilientChatService chatService = mock(ResilientChatService.class);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final AiProperties properties = new AiProperties();

	private final ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations,
			mock(com.example.configmgr.ai.run.ToolActivityBeacon.class), List.of());

	private final SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry, this.gate,
			this.toolRegistry, this.codec, mock(com.example.configmgr.ai.run.ToolActivityBeacon.class),
			this.cancellations, new ToolResultLimiter(this.properties), List.of());

	private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);

	@BeforeEach
	void setUp() {
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.registry.of(RUN)).thenReturn(this.emitter);
		when(this.chatService.buildResumedPrompt(eq(RUN), eq(SESSION), any(AiContext.class), any()))
			.thenReturn(new Prompt(List.of()));
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(this.executor).execute(any(Runnable.class));
	}

	// ── 第 1 层：resolvePending ────────────────────────────────────────────

	@Test
	void approvedFrontendEntryIsSettledAsBlockedInsteadOfBeingExecuted() {
		seedPending(PendingToolCall.APPROVED, PendingToolCall.KIND_FRONTEND);

		String text = this.manager.resolvePending(RUN, new Prompt(List.of()), this.store.pending(RUN, CALL));

		assertThat(text).as("回填模型的文本必须点名机器码与'未执行'")
			.contains("TOOL_KIND_MISMATCH")
			.contains("未执行");
		PendingToolCall after = this.store.pending(RUN, CALL);
		assertThat(after.getStatus()).isEqualTo(PendingToolCall.BLOCKED);
		assertThat(after.effectiveOutcome().wire()).isEqualTo("blocked");
		assertThat(after.effectiveCode()).isEqualTo("TOOL_KIND_MISMATCH");
		assertThat(after.isExecuted()).as("没有执行，就不能说执行过").isFalse();
		assertThat(after.getExecutedBy()).isNull();
		assertThat(this.store.ledger(RUN)).as("不得写成功台账").isEmpty();
		assertThat(this.store.claimedBy(RUN, CALL)).as("未占执行认领（连认领都没走到）").isNull();
	}

	/** 回归：正常前端回灌（非 APPROVED）仍按原口径复用结果（本闸只认 APPROVED+FRONTEND）。 */
	@Test
	void settledFrontendResultIsStillReusedWithoutTouchingTheKindGuard() {
		PendingToolCall settled = seedPending(PendingToolCall.FRONTEND_RESULT, PendingToolCall.KIND_FRONTEND);
		settled.setResultText("表单已提交：keyword=CNY");
		this.store.putPending(RUN, settled);

		String text = this.manager.resolvePending(RUN, new Prompt(List.of()), settled);

		assertThat(text).isEqualTo("表单已提交：keyword=CNY");
		assertThat(this.store.pending(RUN, CALL).getStatus()).isEqualTo(PendingToolCall.FRONTEND_RESULT);
		assertThat(this.store.ledger(RUN)).isEmpty();
	}

	// ── 第 2 层：完整续跑链 ────────────────────────────────────────────────

	@Test
	void resumeSettlesALegacyApprovedFrontendAsBlockedAndStillHandsOff() {
		seedSuspendedRun();
		ResumeService service = new ResumeService(this.store, this.registry, this.codec, this.chatService,
				this.manager, this.executor, mock(StringRedisTemplate.class), this.properties, this.cancellations,
				new ToolResultLimiter(this.properties));

		ResumeService.ResumeResult result = service.resume(RUN);

		assertThat(result.outcome()).as("假批准不该阻止这一轮交棒").isEqualTo(ResumeService.Outcome.ACCEPTED);
		PendingToolCall after = this.store.pending(RUN, CALL);
		assertThat(after.getStatus()).isEqualTo(PendingToolCall.BLOCKED);
		assertThat(after.effectiveCode()).isEqualTo("TOOL_KIND_MISMATCH");
		assertThat(after.isExecuted()).isFalse();
		assertThat(this.store.ledger(RUN)).isEmpty();
		assertThat(String.valueOf(result.body().get("rebuiltTools")))
			.as("交棒的历史里该条目以 blocked/未执行落定")
			.contains(PendingToolCall.BLOCKED)
			.contains("not-executed");
		verify(this.chatService).driveResumed(eq(RUN), eq(SESSION), any(AiContext.class), any(), eq(this.emitter));
	}

	// ── 夹具 ────────────────────────────────────────────────────────────────

	private PendingToolCall seedPending(String status, String kind) {
		PendingToolCall pending = entry(status, kind);
		this.store.putPending(RUN, pending);
		return pending;
	}

	private void seedSuspendedRun() {
		RunSnapshot snapshot = this.store.seedRun(RUN, SESSION);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setContext(AiContext.of("export", "EXPORT", "EXPORT", 7L, null).toMap());
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("请生成表单"))));
		PendingToolCall approved = entry(PendingToolCall.APPROVED, PendingToolCall.KIND_FRONTEND);
		snapshot.setInFlightToolCalls(new ArrayList<>(List.of(approved)));
		this.store.save(snapshot);
		this.store.putPending(RUN, approved);
	}

	private static PendingToolCall entry(String status, String kind) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL);
		pending.setName("generative_form");
		pending.setType("function");
		pending.setArguments("{}");
		pending.setKind(kind);
		pending.setStatus(status);
		return pending;
	}

}
