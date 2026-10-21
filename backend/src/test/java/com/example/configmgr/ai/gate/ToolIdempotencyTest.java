package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.FrameContract;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolOutcome;
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
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 幂等与认领三态的<b>门级</b>用例（B23L1 批③b）：{@code ai:idem:} 重放、认领失败后的二次对账、
 * 以及 {@code STALE_FENCE} 的独立结论。
 *
 * <h2>实例面维度（M8 / DC-11 口径）</h2>
 * 本类用<b>两个"实例"</b>的痕迹面构造语料：台账记录里写着 {@code executedBy=instance-prior}、
 * 认领哈希里写着别的实例 —— 当前实例（{@code instance-conformance}）只凭 Redis 里的痕迹做判断。
 * 这正是跨实例幂等的判据面：结论不依赖"对方进程还活着"，只依赖台账/认领这两个外置事实。
 *
 * <h2>覆盖</h2>
 * <ol>
 * <li>I1 重放回台账原结果（delegate 零调用、台账不新增）；</li>
 * <li>I1b（U4）台账命中 {@code outcome=unknown} ⇒ <b>保持 UNKNOWN</b>（不再写成成功，红测基线）；</li>
 * <li>I4 认领失败后二次对账：命中 → 回原结果；未命中 → 既有 {@code TOOL_ALREADY_CLAIMED} 文案；</li>
 * <li>I5 {@code STALE_FENCE} ⇒ 独立文案（"写回权已转移"），{@code executed=false}，
 * 且不再出现"由实例 null 认领"（红测基线）。</li>
 * </ol>
 */
class ToolIdempotencyTest {

	private static final String RUN_ID = "run-idem";

	private static final String SESSION_ID = "s-idem";

	private static final String CALL_ID = "call-idem-1";

	private final AiProperties properties = new AiProperties();

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final MessageJsonCodec codec = mock(MessageJsonCodec.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final FakeImportTool tool = new FakeImportTool();

	private InMemoryRunStore store;

	private RunRegistry registry;

	private SpToolCallingManager manager;

	@BeforeEach
	void setUp() {
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.channelOf(anyString())).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.toolRegistry.riskOf(anyString())).thenReturn(ToolMeta.RiskLevel.READ);
		wire(new InMemoryRunStore());
	}

	private void wire(InMemoryRunStore runStore) {
		this.store = runStore;
		this.registry = new RunRegistry(runStore, new ObjectMapper(), null);
		ConfirmGate gate = new ConfirmGate(runStore, this.registry, this.properties, this.cancellations, this.beacon,
				List.of());
		this.manager = new SpToolCallingManager(runStore, this.registry, gate, this.toolRegistry, this.codec,
				this.beacon, this.cancellations, new ToolResultLimiter(this.properties), List.of());
		RunSnapshot snapshot = runStore.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.of("import", "IMPORT", "IMPORT", 7L, null).toMap());
		runStore.save(snapshot);
	}

	// ── I1：重放回台账原结果 ────────────────────────────────────────────────

	@Test
	void ledgerHitReplaysOriginalResultWithoutExecuting() {
		seedLedger(ToolOutcome.OK, "原结果：导入作业已启动（作业 #4221）", "instance-prior");
		FrameWire wire = attach();

		this.manager.executeToolCalls(prompt(), response("start_import", "{\"taskId\":7}"));
		finish();

		assertThat(this.tool.invocations.get()).as("delegate 零调用").isZero();
		Map<String, Object> frame = FrameContract.oneOfType(wire.frames(), "tool_result");
		assertThat(frame).containsEntry("outcome", ToolOutcome.OK.wire())
			.containsEntry("reused", true)
			.containsEntry("executed", true)
			.containsEntry("executedBy", "instance-prior");
		assertThat(String.valueOf(frame.get("result"))).isEqualTo("原结果：导入作业已启动（作业 #4221）");

		assertThat(this.store.ledger(RUN_ID)).as("重放不新增台账").hasSize(1);
		assertThat(this.store.claimedBy(RUN_ID, CALL_ID)).as("重放不认领").isNull();
		assertThat(this.store.pending(RUN_ID, CALL_ID).effectiveOutcome()).isEqualTo(ToolOutcome.OK);
	}

	// ── I1b / U4：台账里的 unknown 必须保持 unknown ─────────────────────────

	@Test
	void ledgerUnknownKeepsUnknownInsteadOfReportingSuccess() {
		seedLedger(ToolOutcome.UNKNOWN, "执行结果未知（进程在写台账前退出）", "instance-prior");
		FrameWire wire = attach();

		this.manager.executeToolCalls(prompt(), response("start_import", "{\"taskId\":7}"));
		finish();

		Map<String, Object> frame = FrameContract.oneOfType(wire.frames(), "tool_result");
		assertThat(frame).containsEntry("outcome", ToolOutcome.UNKNOWN.wire())
			.containsEntry("ok", false)
			.containsEntry("reused", true);
		assertThat(this.tool.invocations.get()).isZero();
		assertThat(this.store.pending(RUN_ID, CALL_ID).effectiveOutcome())
			.as("unknown 是确定的未判定，不能写成成功").isEqualTo(ToolOutcome.UNKNOWN);
		assertThat(this.store.ledger(RUN_ID)).hasSize(1);
	}

	// ── I4：认领失败后的二次对账 ────────────────────────────────────────────

	@Test
	void lostClaimSecondLedgerCheckReplaysOriginalResult() {
		// 第一次对账（执行前）故意"看不见"台账，第二次（认领失败后）才命中 —— 模拟对方恰好写完
		LateLedgerStore late = new LateLedgerStore();
		wire(late);
		seedLedger(ToolOutcome.OK, "原结果：已由对方实例完成", "instance-other");
		seedClaim("instance-other");
		FrameWire wire = attach();

		this.manager.executeToolCalls(prompt(), response("start_import", "{\"taskId\":7}"));
		finish();

		assertThat(late.calls).as("两次对账都发生了").isGreaterThanOrEqualTo(2);
		assertThat(this.tool.invocations.get()).isZero();
		Map<String, Object> frame = FrameContract.oneOfType(wire.frames(), "tool_result");
		assertThat(frame).containsEntry("outcome", ToolOutcome.OK.wire())
			.containsEntry("reused", true);
		assertThat(String.valueOf(frame.get("result"))).isEqualTo("原结果：已由对方实例完成");
	}

	@Test
	void lostClaimWithoutLedgerReportsAlreadyClaimed() {
		seedClaim("instance-other");
		FrameWire wire = attach();

		this.manager.executeToolCalls(prompt(), response("start_import", "{\"taskId\":7}"));
		finish();

		assertThat(this.tool.invocations.get()).isZero();
		Map<String, Object> frame = FrameContract.oneOfType(wire.frames(), "tool_result");
		assertThat(frame).containsEntry("ok", false)
			.containsEntry("outcome", ToolOutcome.BLOCKED.wire())
			.containsEntry("code", ToolOutcome.CODE_ALREADY_CLAIMED)
			.containsEntry("reused", true);
		assertThat(String.valueOf(frame.get("result"))).contains("该操作已由实例 instance-other 认领执行");

		PendingToolCall pending = this.store.pending(RUN_ID, CALL_ID);
		assertThat(pending.getCode()).isEqualTo(ToolOutcome.CODE_ALREADY_CLAIMED);
		assertThat(pending.getExecutedBy()).isEqualTo("instance-other");
		assertThat(this.store.ledger(RUN_ID)).isEmpty();
	}

	// ── I5：STALE_FENCE 独立文案（现状即红） ────────────────────────────────

	@Test
	void staleFenceHasIndependentConclusionAndText() {
		// 本进程仍持 epoch 1，但 fence 已被接管方推进到 2
		this.store.seedHeldRunEpoch(RUN_ID, 1L);
		this.store.seedFence(RUN_ID, 2L);
		FrameWire wire = attach();

		this.manager.executeToolCalls(prompt(), response("start_import", "{\"taskId\":7}"));
		finish();

		assertThat(this.tool.invocations.get()).isZero();
		assertThat(this.store.ledger(RUN_ID)).isEmpty();

		Map<String, Object> frame = FrameContract.oneOfType(wire.frames(), "tool_result");
		assertThat(frame).containsEntry("ok", false)
			.containsEntry("outcome", ToolOutcome.BLOCKED.wire())
			.containsEntry("executed", false)
			.containsEntry("status", PendingToolCall.BLOCKED);
		String text = String.valueOf(frame.get("result"));
		assertThat(text).contains("本轮写回权已转移，本次未执行")
			.as("不得再写'由实例 null 认领'").doesNotContain("实例 null");

		PendingToolCall pending = this.store.pending(RUN_ID, CALL_ID);
		assertThat(pending.getStatus()).isEqualTo(PendingToolCall.BLOCKED);
		assertThat(pending.isExecuted()).isFalse();
		assertThat(pending.getExecutedBy()).isNull();
	}

	// ── 桩与辅助 ────────────────────────────────────────────────────────────

	/** 第一次台账读口（索引 + 旧扫描口）都当作未命中，之后走真实查表（I4 的"两次对账"窗口）。 */
	static class LateLedgerStore extends InMemoryRunStore {

		int calls;

		@Override
		public Map<String, Object> idemFor(String runId, String toolCallId) {
			if (++this.calls < 2) {
				return null;
			}
			return super.idemFor(runId, toolCallId);
		}

		@Override
		public Map<String, Object> ledgerFor(String runId, String toolCallId) {
			return this.calls < 2 ? null : super.ledgerFor(runId, toolCallId);
		}

	}

	private void seedLedger(ToolOutcome outcome, String resultText, String executedBy) {
		PendingToolCall seeded = new PendingToolCall();
		seeded.setToolCallId(CALL_ID);
		seeded.setName("start_import");
		seeded.setKind(PendingToolCall.KIND_BACKEND);
		seeded.setStatus(PendingToolCall.EXECUTED);
		seeded.setOutcome(outcome.wire());
		seeded.setExecuted(true);
		seeded.setExecutedBy(executedBy);
		this.store.appendLedger(RUN_ID, seeded, resultText);
	}

	private void seedClaim(String claimedBy) {
		this.store.claimExecution(RUN_ID, CALL_ID, claimedBy);
	}

	private FrameWire attach() {
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		out.start(SESSION_ID);
		return wire;
	}

	private void finish() {
		this.registry.of(RUN_ID).done(Map.of(), "deepseek-flash", Map.of("cancelled", false));
	}

	private Prompt prompt() {
		List<ToolCallback> callbacks = List.of(MethodToolCallbackProvider.builder()
			.toolObjects(this.tool)
			.build()
			.getToolCallbacks());
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(callbacks)
			.toolContext(Map.of("runId", RUN_ID, "sessionId", SESSION_ID))
			.build();
		return new Prompt(List.of(new UserMessage("请发起导入")), options);
	}

	private static ChatResponse response(String tool, String args) {
		AssistantMessage assistant = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall(CALL_ID, "function", tool, args)))
			.build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

	static class FakeImportTool {

		final AtomicInteger invocations = new AtomicInteger();

		@Tool(name = "start_import", description = "受控替身：启动导入作业")
		public String startImport(@ToolParam(description = "任务ID") Long taskId) {
			this.invocations.incrementAndGet();
			return "导入作业已启动（作业 #4221）";
		}

	}

}
