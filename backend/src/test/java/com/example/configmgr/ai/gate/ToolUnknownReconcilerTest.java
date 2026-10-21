package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * UNKNOWN 对账器（B23L1 批③b / 设计稿 §2.4）的用例：台账 → 认领 → 资源锁<b>三跳判定</b>，
 * 以及它在续跑结算（{@code resolvePending}）上的接线。
 *
 * <h2>实例面维度（M8 / DC-11 口径）</h2>
 * 判据全部来自<b>外置痕迹</b>（{@code ai:idem:} / {@code ai:claim:} / {@code ai:lock:*}）——
 * 这正是跨实例场景：本进程不知道对方是否执行完，只能读 Redis 里留下的三个事实。用例用
 * {@code executedBy=instance-other}、{@code claimedBy=instance-other} 以及桩定的锁占用构造这三面。
 *
 * <h2>收敛规则（验收锚）</h2>
 * <ul>
 * <li>U1 台账命中且非 unknown ⇒ 采用台账结局（含原文与执行者）；</li>
 * <li>U2 台账命中但结局是 unknown ⇒ <b>保持 UNKNOWN</b>；</li>
 * <li>U3 未命中 + 认领他人持有 ⇒ 保持 UNKNOWN，文案点明"已在执行中"；</li>
 * <li>U4 未命中 + 无人认领 ⇒ 保持 UNKNOWN，文案「无法判定，需人工对账」；</li>
 * <li>U5 FRONTEND 通道不参与（返回 null，调用方保持原样）。</li>
 * </ul>
 */
class ToolUnknownReconcilerTest {

	private static final String RUN_ID = "run-unknown";

	private static final String SESSION_ID = "s-unknown";

	private static final String CALL_ID = "call-unknown-1";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final RunRegistry registry = new RunRegistry(this.store, new ObjectMapper(), null);

	private final AiProperties properties = new AiProperties();

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final MessageJsonCodec codec = mock(MessageJsonCodec.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations,
			this.beacon, List.of());

	private final ToolResourceLocks locks = mock(ToolResourceLocks.class);

	private final ToolUnknownReconciler reconciler = new ToolUnknownReconciler(this.store, this.locks);

	private final SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry, this.gate,
			this.toolRegistry, this.codec, this.beacon, this.cancellations, new ToolResultLimiter(this.properties),
			List.of(), null, null, null, this.reconciler);

	@BeforeEach
	void setUp() {
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.channelOf(anyString())).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.toolRegistry.riskOf(anyString())).thenReturn(ToolMeta.RiskLevel.READ);
		when(this.locks.isHeld(anyLong(), anyString())).thenReturn(false);
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.of("import", "IMPORT", "IMPORT", 7L, null).toMap());
		this.store.save(snapshot);
	}

	// ── U1：台账命中非 unknown ⇒ 采用台账结局 ───────────────────────────────

	@Test
	void ledgerHitWithConcreteOutcomeIsAdopted() {
		seedLedger(ToolOutcome.FAILED, ToolOutcome.CODE_TOOL_EXECUTION_FAILED, "执行失败：文件损坏", "instance-other");

		ToolUnknownReconciler.Reconciliation result = this.reconciler.reconcile(RUN_ID, pendingBackend());

		assertThat(result.adopted()).isTrue();
		assertThat(result.outcome()).isEqualTo(ToolOutcome.FAILED);
		assertThat(result.code()).isEqualTo(ToolOutcome.CODE_TOOL_EXECUTION_FAILED);
		assertThat(result.text()).isEqualTo("执行失败：文件损坏");
		assertThat(result.executedBy()).isEqualTo("instance-other");
	}

	// ── U2：台账命中但结局 unknown ⇒ 保持 UNKNOWN ───────────────────────────

	@Test
	void ledgerHitWithUnknownOutcomeStaysUnknown() {
		seedLedger(ToolOutcome.UNKNOWN, null, "", "instance-other");

		ToolUnknownReconciler.Reconciliation result = this.reconciler.reconcile(RUN_ID, pendingBackend());

		assertThat(result.ledgerHit()).isTrue();
		assertThat(result.adopted()).as("不能把'没判定出来'收敛成成功").isFalse();
		assertThat(result.outcome()).isEqualTo(ToolOutcome.UNKNOWN);
		assertThat(result.text()).contains("UNKNOWN").contains("需人工对账");
	}

	// ── U3：未命中 + 认领他人持有 ⇒ 保持 UNKNOWN（已在执行中） ──────────────

	@Test
	void pendingClaimKeepsUnknownWithInFlightText() {
		this.store.claimExecution(RUN_ID, CALL_ID, "instance-other");

		ToolUnknownReconciler.Reconciliation result = this.reconciler.reconcile(RUN_ID, pendingBackend());

		assertThat(result.adopted()).isFalse();
		assertThat(result.outcome()).isEqualTo(ToolOutcome.UNKNOWN);
		assertThat(result.text()).contains("已在执行中").contains("instance-other").contains("需人工对账");
	}

	// ── U4：未命中 + 无人认领 ⇒ 保持 UNKNOWN（无法判定） ────────────────────

	@Test
	void noTracesKeepsUnknownWithManualReconciliationText() {
		ToolUnknownReconciler.Reconciliation result = this.reconciler.reconcile(RUN_ID, pendingBackend());

		assertThat(result.adopted()).isFalse();
		assertThat(result.outcome()).isEqualTo(ToolOutcome.UNKNOWN);
		assertThat(result.text()).contains("无法判定，需人工对账");
	}

	@Test
	void heldResourceLockKeepsUnknownWithLockText() {
		when(this.locks.isHeld(7L, "IMPORT")).thenReturn(true);

		ToolUnknownReconciler.Reconciliation result = this.reconciler.reconcile(RUN_ID, pendingBackend());

		assertThat(result.adopted()).isFalse();
		assertThat(result.outcome()).isEqualTo(ToolOutcome.UNKNOWN);
		assertThat(result.text()).contains("资源锁").contains("无法判定，需人工对账");
	}

	// ── U5：FRONTEND 通道不参与 ─────────────────────────────────────────────

	@Test
	void frontendChannelIsNotReconciled() {
		PendingToolCall frontend = pendingBackend();
		frontend.setKind(PendingToolCall.KIND_FRONTEND);

		assertThat(this.reconciler.reconcile(RUN_ID, frontend)).isNull();
	}

	// ── 接线：resolvePending 先对账再回填 ───────────────────────────────────

	@Test
	void resolvePendingAdoptsLedgerOutcomeForUnknownEntry() {
		seedLedger(ToolOutcome.OK, null, "台账结局：作业已启动（作业 #4221）", "instance-other");
		PendingToolCall pending = pendingBackend();
		pending.setStatus(PendingToolCall.EXECUTED);
		pending.setOutcome(ToolOutcome.UNKNOWN.wire());
		pending.setExecuted(true);
		pending.setResultText("旧文案：结果未知");
		this.store.putPending(RUN_ID, pending);

		String text = this.manager.resolvePending(RUN_ID, prompt(), pending);

		assertThat(text).isEqualTo("台账结局：作业已启动（作业 #4221）");
		PendingToolCall stored = this.store.pending(RUN_ID, CALL_ID);
		assertThat(stored.effectiveOutcome()).isEqualTo(ToolOutcome.OK);
		assertThat(stored.getExecutedBy()).isEqualTo("instance-other");
	}

	@Test
	void resolvePendingKeepsUnknownWhenNothingToAdopt() {
		PendingToolCall pending = pendingBackend();
		pending.setStatus(PendingToolCall.EXECUTED);
		pending.setOutcome(ToolOutcome.UNKNOWN.wire());
		pending.setExecuted(true);
		this.store.putPending(RUN_ID, pending);

		String text = this.manager.resolvePending(RUN_ID, prompt(), pending);

		assertThat(text).contains("无法判定，需人工对账");
		assertThat(this.store.pending(RUN_ID, CALL_ID).effectiveOutcome()).isEqualTo(ToolOutcome.UNKNOWN);
	}

	// ── 桩与辅助 ────────────────────────────────────────────────────────────

	private PendingToolCall pendingBackend() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL_ID);
		pending.setName("start_import");
		pending.setKind(PendingToolCall.KIND_BACKEND);
		pending.setArguments("{\"taskId\":7}");
		pending.setStatus(PendingToolCall.EXECUTED);
		pending.setOutcome(ToolOutcome.UNKNOWN.wire());
		pending.setExecuted(true);
		return pending;
	}

	private void seedLedger(ToolOutcome outcome, String code, String resultText, String executedBy) {
		PendingToolCall seeded = new PendingToolCall();
		seeded.setToolCallId(CALL_ID);
		seeded.setName("start_import");
		seeded.setKind(PendingToolCall.KIND_BACKEND);
		seeded.setStatus(PendingToolCall.EXECUTED);
		seeded.setOutcome(outcome.wire());
		seeded.setCode(code);
		seeded.setExecuted(true);
		seeded.setExecutedBy(executedBy);
		this.store.appendLedger(RUN_ID, seeded, resultText);
	}

	private Prompt prompt() {
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(List.of(MethodToolCallbackProvider.builder()
				.toolObjects(new FakeImportTool())
				.build()
				.getToolCallbacks()))
			.toolContext(Map.of("runId", RUN_ID, "sessionId", SESSION_ID))
			.build();
		return new Prompt(List.of(new UserMessage("续跑结算")), options);
	}

	static class FakeImportTool {

		@Tool(name = "start_import", description = "受控替身：启动导入作业")
		public String startImport(@ToolParam(description = "任务ID") Long taskId) {
			return "不应被执行";
		}

	}

}
