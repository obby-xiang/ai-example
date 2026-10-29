package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.RunContextRefresher;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.slot.TaskSlots;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 宿主确定性<b>筛选条件</b>文本框：进入 {@code QUERY_COND}、条件又没给时，由宿主挂起一张
 * <b>非必填自由文本框</b>（{@code purpose=SET_CONDITIONS}），而不是等模型文字追问。
 *
 * <h2>被钉住的缺陷</h2>
 * 同一现场下模型可能写"请告诉我筛选条件"——那是无条件步也能发生的文字反问。用户口径是确定性的：
 * 条件步 + 条件未知 ⇒ 宿主发一个框；<b>已给条件直接 set_condition、留空直接 confirm_step 全量</b>，
 * 并且 <b>EXPORT 之后不得再弹</b>。
 *
 * <h2>本用例锁的口径</h2>
 * <ol>
 * <li>只有 {@code QUERY_COND} 弹，且是<b>一个非必填 text 框</b>（不是结构化表、不是必填）；</li>
 * <li>{@code SELECT_DEFS}（前置）与 {@code EXPORT}（后置）都不弹；条件已知也不弹；</li>
 * <li>普通浏览不打扰；用户的"继续任务"意图才触发；</li>
 * <li>同一任务只问一次（提交/取消之后都不再来）；留空回灌被既有闸门接受（= 不设条件）；</li>
 * <li>宿主表单与模型自发表单过同一份白名单闸门。</li>
 * </ol>
 *
 * <p><b>证红</b>：宿主不再挂起 ⇒ 第 1/4 条红；步骤门去掉（SELECT_DEFS/EXPORT 也弹）⇒ 第 2 条红；
 * 意图门去掉（普通浏览也弹）⇒ 第 3 条红；去重去掉 ⇒ 第 4 条红。
 */
class ConditionsFormTest {

	private static final String RUN_ID = "run-cond-form";

	private static final String SESSION_ID = "s-cond-form";

	private static final long TASK_ID = 42L;

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final AiProperties properties = new AiProperties();

	private final GenerativeFormGuard guard = new GenerativeFormGuard(new ObjectMapper());

	private ConfirmGate gate;

	private ConditionsForm form;

	@BeforeEach
	void setUp() {
		this.properties.getPark().setEnabled(true);
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any())).thenAnswer(invocation -> (AutoCloseable) () -> {
		});
		this.gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations, this.beacon,
				List.of(this.guard));
		this.form = new ConditionsForm(this.store, this.registry, this.gate, this.codec, this.properties);
	}

	// ── ① 条件步 + 条件未知 + 新任务链：一个非必填文本框 ─────────────────────

	@Test
	void parksOptionalSingleTextBoxWhenEnteringQueryCond() {
		seedNewTaskRun();

		boolean parked = parkAt("QUERY_COND", "帮我新建一个导出任务", List.of());

		assertThat(parked).as("条件步 + 条件未知 ⇒ 宿主必须给一次收集渠道（不是模型文字追问）").isTrue();
		PendingToolCall pending = onlyPending();
		assertThat(pending.getName()).isEqualTo(GenerativeFormRules.TOOL_NAME);
		assertThat(pending.getKind()).isEqualTo(PendingToolCall.KIND_FRONTEND);
		assertThat(pending.getStatus()).isEqualTo(PendingToolCall.PENDING);
		assertThat(pending.getToolCallId()).isEqualTo(ConditionsForm.toolCallId(TASK_ID));

		JsonNode form = parse(pending.getArguments()).path("form");
		assertThat(form.path("purpose").asText()).isEqualTo(GenerativeFormRules.PURPOSE_SET_CONDITIONS);
		assertThat(form.path("fields")).hasSize(1);
		JsonNode field = form.path("fields").get(0);
		assertThat(field.path("key").asText()).isEqualTo(ConditionsForm.FIELD_KEY);
		assertThat(field.path("type").asText()).isEqualTo("text");
		assertThat(field.path("required").asBoolean()).as("非必填：留空 = 不设筛选（全量）").isFalse();
		assertThat(field.path("options").isMissingNode()).as("不是选项表：条件由模型从文本解析").isTrue();

		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(this.store.pendingsLenient(RUN_ID).get(0).getReq()).isTrue();
		verify(this.emitter).suspended(eq(List.of(pending)), anyString(), anyString(), anyString());
	}

	// ── ② 用户明确要求别的表单/工具时让位（GF2 回归）──────────────────────────

	@Test
	void messageAskingForAnotherFormNeverPreemptsTheConditionBox() {
		seedNewTaskRun();
		// GF2 的现场：用户既说"继续这个导出任务"，又明确要求模型用 CLARIFY 表单澄清配置项
		String gf2 = "我想继续这个导出任务，但你缺两个信息。请调用 generative_form 工具（scenario=CLARIFY）"
				+ "向我澄清 exportScope/includeInactive 分别要什么，不要调用其他工具";

		boolean parked = parkAt("QUERY_COND", gf2, List.of());

		assertThat(parked)
			.as("用户明确要求 CLARIFY 表单 ⇒ 宿主不得抢成条件框（模型必须被驱动、自行发 CLARIFY）")
			.isFalse();
		assertThat(this.store.pendingsLenient(RUN_ID)).isEmpty();
		verify(this.emitter, never()).suspended(any(), anyString(), anyString(), anyString());
	}

	@Test
	void messageAskingForAnotherToolNeverPreemptsTheConditionBox() {
		seedNewTaskRun();
		String text = "继续这个导出任务，请先调用 list_config_defs 看看有哪些配置项，别发别的工具";

		assertThat(parkAt("QUERY_COND", text, List.of()))
			.as("用户点名了别的工具 ⇒ 宿主不抢条件框")
			.isFalse();
		assertThat(this.store.pendingsLenient(RUN_ID)).isEmpty();
	}

	@Test
	void explicitConditionFormRequestStillParks() {
		seedNewTaskRun();
		// 负控制：同样是"说话里带表单"，但指向的正是本节点的缺口 —— 不得被让位规则误排除
		String text = "继续这个导出任务，请调用 generative_form（scenario=FILTER）收集筛选条件";

		assertThat(parkAt("QUERY_COND", text, List.of()))
			.as("显式条件请求（FILTER/筛选条件）仍必须由宿主给框")
			.isTrue();
		assertThat(onlyPending().getToolCallId()).isEqualTo(ConditionsForm.toolCallId(TASK_ID));
	}

	@Test
	void explicitSetConditionsPurposeStillParks() {
		seedNewTaskRun();
		String text = "继续任务，用 generative_form 按 purpose=SET_CONDITIONS 发条件框";

		assertThat(parkAt("QUERY_COND", text, List.of()))
			.as("显式 SET_CONDITIONS 请求不得被误排除")
			.isTrue();
		assertThat(onlyPending().getToolCallId()).isEqualTo(ConditionsForm.toolCallId(TASK_ID));
	}

	// ── ③ 步骤门：SELECT_DEFS 与 EXPORT（及之后）一律不弹 ──────────────────

	@Test
	void doesNotParkBeforeQueryCondOrAfterExport() {
		seedNewTaskRun();

		assertThat(parkAt("SELECT_DEFS", "帮我新建一个导出任务", List.of()))
			.as("前置步不收集条件（先 confirm_step 推进）").isFalse();
		assertThat(parkAt("EXPORT", "帮我新建一个导出任务", List.of()))
			.as("EXPORT 后不得再弹条件框").isFalse();
		assertThat(parkAt("DONE", "帮我新建一个导出任务", List.of()))
			.as("已完成/之后的步骤同样不弹").isFalse();
		assertThat(this.store.pendingsLenient(RUN_ID)).isEmpty();
		verify(this.emitter, never()).suspended(any(), anyString(), anyString(), anyString());
	}

	// ── ④ 条件已知：不弹（直接 set_condition） ───────────────────────────────

	@Test
	void doesNotParkWhenConditionsAreAlreadyKnown() {
		seedNewTaskRun();

		boolean parked = this.form.parkIfDue(RUN_ID, context("QUERY_COND", knownConditions()), List.of(),
				history("帮我新建一个导出任务"));

		assertThat(parked).as("条件已确定 ⇒ 不弹表（模型直接 set_condition）").isFalse();
		assertThat(this.store.pendingsLenient(RUN_ID)).isEmpty();
	}

	// ── ⑤ 普通浏览不打扰；"继续任务"才触发 ─────────────────────────────────

	@Test
	void plainBrowsingDoesNotParkButContinueIntentDoes() {
		seedExistingTaskRun();

		assertThat(this.form.parkIfDue(RUN_ID, context("QUERY_COND", Map.of()), List.of(),
				history("打开任务 7 看看")))
			.as("普通浏览既有任务：不得自动打扰").isFalse();
		assertThat(this.store.pendingsLenient(RUN_ID)).isEmpty();

		boolean parked = this.form.parkIfDue(RUN_ID, context("QUERY_COND", Map.of()), List.of(),
				history("继续导出这个任务"));
		assertThat(parked).as("明确的继续意图 ⇒ 条件未给时补一次收集").isTrue();
		assertThat(onlyPending().getToolCallId()).isEqualTo(ConditionsForm.toolCallId(TASK_ID));
	}

	// ── ⑥ 只问一次：提交/取消后都不再来；留空回灌被既有闸门接受 ────────────

	@Test
	void answeringWithBlankKeepsTheFormAnsweredAndDoesNotAskAgain() {
		seedNewTaskRun();
		assertThat(parkAt("QUERY_COND", "帮我新建一个导出任务", List.of())).isTrue();

		// 留空 = 不设筛选：既有回灌闸门必须接受（否则"留空全量"这条路根本走不通）
		ConfirmGate.Submission submission = this.gate.submitFrontendResult(RUN_ID,
				ConditionsForm.toolCallId(TASK_ID), "{\"" + ConditionsForm.FIELD_KEY + "\":\"\"}", "unit-test");
		assertThat(submission.outcome()).as("非必填字段留空是合法回灌").isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		assertThat(onlyPending().getStatus()).isEqualTo(PendingToolCall.FRONTEND_RESULT);

		assertThat(parkAt("QUERY_COND", "帮我新建一个导出任务", List.of()))
			.as("同一任务的条件框只问一次（提交后不再弹）").isFalse();
		assertThat(this.store.pendingsLenient(RUN_ID)).hasSize(1);
	}

	// ── 桩与辅助 ────────────────────────────────────────────────────────────

	private boolean parkAt(String step, String userMessage, List<PendingToolCall> settled) {
		return this.form.parkIfDue(RUN_ID, context(step, Map.of()), settled, history(userMessage));
	}

	private PendingToolCall onlyPending() {
		List<PendingToolCall> pendings = this.store.pendingsLenient(RUN_ID);
		assertThat(pendings).hasSize(1);
		return pendings.get(0);
	}

	private void seedNewTaskRun() {
		seedRun(AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of()));
		PendingToolCall createTask = new PendingToolCall();
		createTask.setToolCallId("call-create");
		createTask.setName(RunContextRefresher.TOOL_CREATE_TASK);
		createTask.setKind(PendingToolCall.KIND_CONFIRM);
		createTask.setStatus(PendingToolCall.EXECUTED);
		createTask.setOutcome(ToolOutcome.OK.wire());
		createTask.setExecuted(true);
		createTask.setResultText("已创建导出任务 #" + TASK_ID + "（当前步骤：SELECT_DEFS）");
		this.store.appendLedger(RUN_ID, createTask, createTask.getResultText());
	}

	private void seedExistingTaskRun() {
		seedRun(AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of()));
	}

	private void seedRun(AiContext context) {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(context.toMap());
		snapshot.setMessageJson(this.codec.serializeAll(history("帮我新建一个导出任务")));
		this.store.save(snapshot);
	}

	private static AiContext context(String step, Map<String, Object> extra) {
		return AiContext.of("export", "EXPORT", step, TASK_ID, "ACTIVE", extra);
	}

	/** 已选 CURRENCY，且该配置项的条件已给（覆盖判定成立）。 */
	private static Map<String, Object> knownConditions() {
		Map<String, Object> slot = new LinkedHashMap<>();
		slot.put("defCode", "CURRENCY");
		slot.put("fieldCode", "code");
		slot.put("value", "CNY");
		Map<String, Object> slots = new TaskSlots("EXPORT", List.of("CURRENCY"), List.of()).toMap();
		slots.put("conditions", List.of(slot));
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put(TaskSlots.EXTRA_KEY, slots);
		return extra;
	}

	// ── ⑦ 合成收尾提示不得盖住用户原话 ─────────────────────────────────────

	@Test
	void syntheticWrapUpMessageDoesNotHideTheUsersContinueIntent() {
		// 既有任务（无 create_task 台账）：任务链只能靠"用户原话在继续"成立
		seedExistingTaskRun();
		List<org.springframework.ai.chat.messages.Message> history = List.of(new SystemMessage("PERSONA"),
				new UserMessage("继续导出这个任务"),
				new UserMessage(SpToolCallingManager.TURN_BUDGET_WRAP_UP));

		assertThat(this.form.parkIfDue(RUN_ID, context("QUERY_COND", Map.of()), List.of(), history))
			.as("轮内预算的收尾提示是系统合成消息，不是用户原话：继续意图仍要看最后一条**真实**用户消息")
			.isTrue();
	}

	private static List<org.springframework.ai.chat.messages.Message> history(String userMessage) {
		return List.of(new SystemMessage("PERSONA"), new UserMessage(userMessage));
	}

	private JsonNode parse(String json) {
		try {
			return this.mapper.readTree(json);
		}
		catch (Exception ex) {
			throw new IllegalStateException("不是合法 JSON：" + json, ex);
		}
	}

}
