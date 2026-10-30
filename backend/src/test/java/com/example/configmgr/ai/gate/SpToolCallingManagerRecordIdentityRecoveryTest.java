package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.FrameContract;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.FakeLeaseRedis;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunParkedException;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.TenantUser;
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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>工具执行点的“先读记录、再开身份”顺序缺陷</b>（真实 {@link RunStore} + 真实键形）。
 *
 * <h2>被钉住的形态</h2>
 * 官方工具调用跑在 Reactor 上游线程（{@code boundedElastic}）上，那里<b>没有</b>请求身份；而轮级的
 * 快照/待决/帧键都由<b>记录身份</b>拼出。{@code SpToolCallingManager#executeToolCalls} 旧实现是
 * <i>先 {@code store.get(runId)}（读线程身份的 legacy 键）→ 再按快照/信封身份开作用域</i>：
 * <ul>
 * <li>快照存在 ⇒ 无身份线程读到 {@code null}，误判“快照缺失”；</li>
 * <li>随后 {@code recoverSnapshot} 也用无线程身份的读口复核：写入其实落在了带身份键上，
 *     但读回复核仍读 legacy 键 ⇒ {@code null} ⇒ 判成“未落库” ⇒ 失败关闭；</li>
 * <li>前端工具（如 {@code create_task} 之后的 {@code navigate_to}）于是被拒绝直执行，
 *     既不挂起也不下发请求帧（用户看到“AI 说了一步但页面不动”）。</li>
 * </ul>
 *
 * <p>同一条顺序缺陷还有两个更隐蔽的后果，本类一并钉住：
 * <ul>
 * <li><b>残留他人身份串轮</b>：池化线程上残留的身份 B 会让首次读命中<b>B 的</b>同名 runId 快照，
 *     于是一轮被写到别人的快照/待决上（信封 A 反而没被使用）；</li>
 * <li><b>恢复写覆盖活快照</b>：活快照存在但读不到时，恢复写会把信封快照（没有
 *     {@code messageJson}、round/toolSteps 是信封时的旧值）落到活键上，覆盖掉更新的运行事实。</li>
 * </ul>
 *
 * <p>修法（生产侧）：先解析本轮权威执行信封，按<b>记录身份</b>开作用域包住首次读与恢复；
 * {@code recoverSnapshot} 的并发读/写后复核改用<b>显式身份</b>读口。
 */
class SpToolCallingManagerRecordIdentityRecoveryTest {

	private static final String RUN_ID = "run-record-identity";

	private static final String SESSION_ID = "s-record-identity";

	private static final String CALL_ID = "call-nav-identity";

	private static final TenantUser RECORD_IDENTITY = new TenantUser(9001L, 7L);

	private static final TenantUser RESIDUAL_IDENTITY = new TenantUser(9002L, 8L);

	private static final String USER_TEXT = "跳到编辑器";

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = new ToolActivityBeacon();

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private SpToolCallingManager manager;

	@BeforeEach
	void setUp() {
		this.properties.getPark().setEnabled(true);
		// 到期收敛排在 30s 后：本用例只关心“挂起是否成立”，条目必须仍是 PENDING。
		this.properties.getHitl().setFrontendToolTimeout(Duration.ofSeconds(30));
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.channelOf("navigate_to")).thenReturn(ToolMeta.Channel.FRONTEND);
		when(this.toolRegistry.riskOf("navigate_to")).thenReturn(ToolMeta.RiskLevel.READ);
		when(this.toolRegistry.categoryOf("navigate_to")).thenReturn(ToolMeta.Category.J);
		ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations, this.beacon,
				List.of());
		this.manager = new SpToolCallingManager(this.store, this.registry, gate, this.toolRegistry, this.codec,
				this.beacon, this.cancellations, new ToolResultLimiter(this.properties), List.of());
	}

	// ── ① 无身份线程 + 快照确实缺失：凭执行信封恢复到记录身份上，并挂起 navigate_to ──────

	@Test
	void missingSnapshotIsRecoveredOnTheRecordIdentityFromEnvelopeAndParksNavigateTo() {
		RunSnapshot seeded = seedRun(RECORD_IDENTITY);
		seeded.setRound(2);
		seeded.setToolSteps(3);
		saveAs(RECORD_IDENTITY, seeded);
		ToolExecutionEnvelope envelope = ToolExecutionEnvelope.fromSnapshot(seeded, 0L);
		assertThat(envelope).isNotNull();
		// 快照键丢失（TTL/运维误删）：信封是唯一的运行事实来源。
		this.store.deleteRun(RUN_ID, RECORD_IDENTITY.tenantId(), RECORD_IDENTITY.userId());
		assertThatRequestThreadHasNoIdentity();
		assertThat(this.store.get(RUN_ID)).as("前置：无身份线程读的是 legacy 键").isNull();
		assertThat(this.store.get(RUN_ID, RECORD_IDENTITY.tenantId(), RECORD_IDENTITY.userId())).as("前置：快照确实缺失")
			.isNull();

		Throwable signal = catchThrowable(() -> this.manager.executeToolCalls(prompt(envelope), navigateToResponse()));

		RunSnapshot restored = snapshotAs(RECORD_IDENTITY);
		assertThat(restored).as("恢复必须在记录身份键上落库（否则后续 saveSuspended 找不到快照）").isNotNull();
		assertThat(restored.getTenantId()).isEqualTo(RECORD_IDENTITY.tenantId());
		assertThat(restored.getUserId()).isEqualTo(RECORD_IDENTITY.userId());
		assertThat(restored.getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(restored.getRound()).as("恢复不重置轮次（信封原值）").isEqualTo(2);
		assertThat(restored.getToolSteps()).as("恢复不重置步数（信封原值）").isEqualTo(3);
		PendingToolCall parked = pendingAs(RECORD_IDENTITY, CALL_ID);
		assertThat(parked).as("前端工具必须进入待决（挂起）而不是被当成不可恢复").isNotNull();
		assertThat(parked.getStatus()).isEqualTo(PendingToolCall.PENDING);
		assertThat(FrameContract.types(framesAs(RECORD_IDENTITY))).contains("suspended", "frontend_tool_request");
		assertThat(signal).as("前端请求帧下发后线程交还（park），不是失败路径").isInstanceOf(RunParkedException.class);
	}

	// ── ② 池化线程残留他人身份：不得把这一批写到别人的轮上 ──────────────────────────

	@Test
	void residualIdentityOnTheExecutingThreadDoesNotCrossRounds() {
		RunSnapshot record = seedRun(RECORD_IDENTITY);
		record.setRound(3);
		record.setToolSteps(4);
		saveAs(RECORD_IDENTITY, record);
		RunSnapshot other = seedRun(RESIDUAL_IDENTITY);
		other.setRound(7);
		other.setToolSteps(2);
		saveAs(RESIDUAL_IDENTITY, other);
		ToolExecutionEnvelope envelope = ToolExecutionEnvelope.fromSnapshot(record, 0L);

		Throwable signal;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(
				RESIDUAL_IDENTITY.tenantId(), RESIDUAL_IDENTITY.userId())) {
			assertThat(this.store.get(RUN_ID)).as("前置：残留身份下首次读会命中同名的另一轮").isNotNull();
			signal = catchThrowable(() -> this.manager.executeToolCalls(prompt(envelope), navigateToResponse()));
			assertThat(RequestContextUtils.currentOrNull()).as("记录身份作用域不得泄漏到调用线程")
				.isEqualTo(RESIDUAL_IDENTITY);
		}

		RunSnapshot recordAfter = snapshotAs(RECORD_IDENTITY);
		assertThat(recordAfter.getStatus()).as("这一轮必须按信封里的记录身份挂起").isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(recordAfter.getRound()).as("不得拿残留身份的快照轮次当本轮事实").isEqualTo(3);
		PendingToolCall parked = pendingAs(RECORD_IDENTITY, CALL_ID);
		assertThat(parked).isNotNull();
		assertThat(parked.getStatus()).isEqualTo(PendingToolCall.PENDING);

		RunSnapshot otherAfter = snapshotAs(RESIDUAL_IDENTITY);
		assertThat(otherAfter.getStatus()).as("残留身份的那一轮不得被串写（仍是运行中）")
			.isEqualTo(RunSnapshot.RUNNING);
		assertThat(otherAfter.getRound()).isEqualTo(7);
		assertThat(otherAfter.getToolSteps()).isEqualTo(2);
		assertThat(pendingAs(RESIDUAL_IDENTITY, CALL_ID)).as("待决条目不得落到残留身份的轮上").isNull();
		assertThat(FrameContract.types(framesAs(RESIDUAL_IDENTITY))).as("残留身份的那一轮不得出现本轮帧").isEmpty();
		assertThat(signal).isInstanceOf(RunParkedException.class);
	}

	// ── ③ 活快照存在（只是无线程身份读不到）：恢复不得覆盖它 ────────────────────────

	@Test
	void recoveryDoesNotOverwriteLiveSnapshotMessageJsonRoundOrToolSteps() {
		RunSnapshot live = seedRun(RECORD_IDENTITY);
		live.setRound(2);
		live.setToolSteps(3);
		saveAs(RECORD_IDENTITY, live);
		// 信封在“上一批”边界冻结：round/步数都比活快照旧，且不含 messageJson。
		ToolExecutionEnvelope stale = ToolExecutionEnvelope.fromSnapshot(live, 0L);
		live.setRound(5);
		live.setToolSteps(9);
		saveAs(RECORD_IDENTITY, live);
		assertThatRequestThreadHasNoIdentity();
		assertThat(this.store.get(RUN_ID)).as("前置：活快照在带身份键上，无身份线程看不到").isNull();

		Prompt prompt = prompt(stale);
		Throwable signal = catchThrowable(() -> this.manager.executeToolCalls(prompt, navigateToResponse()));

		RunSnapshot after = snapshotAs(RECORD_IDENTITY);
		assertThat(after).isNotNull();
		// 时间戳是“首次序列化时刻”，同一内存对象再序列化会得到新时间戳（见 MessageJsonCodec#canonicalJson），
		// 故按去时间戳的规范形态比对：恢复写若覆盖活快照，这里就是空列表。
		assertThat(canonicalHistory(after.getMessageJson()))
			.as("恢复写不得把活快照的完整历史抹成空")
			.isEqualTo(prompt.getInstructions().stream().map(this.codec::canonicalJson).toList());
		assertThat(after.getRound()).as("恢复写不得把活快照的轮次回拨到信封旧值").isEqualTo(5);
		assertThat(after.getToolSteps()).as("恢复写不得把活快照的步数回拨到信封旧值").isEqualTo(9);
		assertThat(after.getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(pendingAs(RECORD_IDENTITY, CALL_ID).getStatus()).isEqualTo(PendingToolCall.PENDING);
		assertThat(signal).isInstanceOf(RunParkedException.class);
	}

	// ── ④ 恢复“读无 → 创建”与并发创建者的竞态：采用并发那一份，绝不覆盖 ────────────

	@Test
	void recoveryAdoptsAConcurrentlyCreatedSnapshotInsteadOfOverwritingIt() {
		RunSnapshot seeded = seedRun(RECORD_IDENTITY);
		seeded.setRound(2);
		seeded.setToolSteps(3);
		saveAs(RECORD_IDENTITY, seeded);
		ToolExecutionEnvelope envelope = ToolExecutionEnvelope.fromSnapshot(seeded, 0L);
		this.store.deleteRun(RUN_ID, RECORD_IDENTITY.tenantId(), RECORD_IDENTITY.userId());
		assertThatRequestThreadHasNoIdentity();

		// 另一实例恰好在“读到不存在”与“恢复创建”之间建好了这一轮（round=8、步数=6）：
		// 无条件 save 会把这一份更新的运行事实覆盖成信封里的旧值。
		this.fake.beforeSnapshotWrite(() -> {
			RunSnapshot winner = envelope.toSnapshot();
			winner.setRound(8);
			winner.setToolSteps(6);
			saveAs(RECORD_IDENTITY, winner);
		});

		Throwable signal = catchThrowable(() -> this.manager.executeToolCalls(prompt(envelope), navigateToResponse()));

		RunSnapshot after = snapshotAs(RECORD_IDENTITY);
		assertThat(after).as("并发创建者写下的那一份必须仍然存在").isNotNull();
		assertThat(after.getRound()).as("并发创建者的轮次不得被信封快照回拨").isEqualTo(8);
		assertThat(after.getToolSteps()).as("并发创建者的步数不得被信封快照回拨").isEqualTo(6);
		assertThat(after.getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(pendingAs(RECORD_IDENTITY, CALL_ID).getStatus()).isEqualTo(PendingToolCall.PENDING);
		assertThat(signal).isInstanceOf(RunParkedException.class);
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private RunSnapshot seedRun(TenantUser identity) {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(identity.tenantId(),
				identity.userId())) {
			return this.store.create(RUN_ID, SESSION_ID, AiContext.empty(),
					List.of(new UserMessage(USER_TEXT)), this.codec);
		}
	}

	private void saveAs(TenantUser identity, RunSnapshot snapshot) {
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
			this.store.save(snapshot);
		}
	}

	private RunSnapshot snapshotAs(TenantUser identity) {
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
			return this.store.get(RUN_ID);
		}
	}

	private PendingToolCall pendingAs(TenantUser identity, String callId) {
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
			return this.store.pending(RUN_ID, callId);
		}
	}

	private List<Map<String, Object>> framesAs(TenantUser identity) {
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
			return FrameWire.normalize(this.store.events(RUN_ID));
		}
	}

	/** 历史 JSON → 去时间戳的规范形态（时间戳是首次序列化时刻，不能当等值判据）。 */
	private List<String> canonicalHistory(List<String> rawMessages) {
		return this.codec.deserializeAll(rawMessages).stream().map(this.codec::canonicalJson).toList();
	}

	private static void assertThatRequestThreadHasNoIdentity() {
		assertThat(RequestContextUtils.currentOrNull()).as("前置：执行线程模拟官方上游线程（无身份）").isNull();
	}

	private static Prompt prompt(ToolExecutionEnvelope envelope) {
		Map<String, Object> toolContext = new LinkedHashMap<>();
		toolContext.put("runId", RUN_ID);
		toolContext.put("sessionId", SESSION_ID);
		toolContext.put(ToolExecutionEnvelope.TOOL_CONTEXT_KEY, envelope);
		ToolCallingChatOptions options = ToolCallingChatOptions.builder().toolContext(toolContext).build();
		return new Prompt(List.of(new UserMessage(USER_TEXT)), options);
	}

	private static ChatResponse navigateToResponse() {
		AssistantMessage assistant = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall(CALL_ID, "function", "navigate_to",
					"{\"page\":\"editor\"}")))
			.build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

}
