package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.RunContextRefresher;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 宿主确定性<b>任务类型</b>表单：模型发起 {@code create_task} 但 {@code type} 缺失/非法时，由宿主挂起
 * {@code EXPORT/IMPORT} 单选，而不是等模型写成文字反问。
 *
 * <h2>被钉住的缺陷</h2>
 * 同一句话下模型可能直接问"要创建导出还是导入任务？"——那是把结构化缺口退回开放式文本。
 * 用户口径是确定性的：<b>结构化 create_task 缺 type ⇒ 宿主直接发单选</b>；type 已给出 ⇒ 不弹，
 * 直接走近 {@code create_task} 的确认卡。
 *
 * <h2>自然语言信任边界（2026-10-30）</h2>
 * 宿主<b>不再从用户措辞推断</b>"新建意图"或类型（原 {@code parkIfDue} 判据已恒 false）。
 * 因此本类锁的是：结构化缺口会弹、type 已给不弹、旧任务页面的类型不得压掉新任务的缺口、
 * 同一轮只问一次，以及宿主表单与模型自发表单过<b>同一份</b>白名单闸门。
 */
class TaskTypeFormTest {

	private static final String RUN_ID = "run-type-form";

	private static final String SESSION_ID = "s-type-form";

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

	private TaskTypeForm form;

	@BeforeEach
	void setUp() {
		this.properties.getPark().setEnabled(true);
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any())).thenAnswer(invocation -> (AutoCloseable) () -> {
		});
		this.gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations, this.beacon,
				List.of(this.guard));
		this.form = new TaskTypeForm(this.store, this.registry, this.gate, this.codec, this.properties);
		seedRun();
	}

	// ── ① 措辞不再触发（信任边界）：缺口只由结构化 create_task 触发 ───────────

	@Test
	void freeTextNewTaskIntentNoLongerParks() {
		assertThat(park("帮我创建一个任务"))
			.as("宿主不再从措辞推断新建意图 ⇒ 不弹（缺口改由结构化 create_task 触发）")
			.isFalse();
		assertThat(this.store.pendingsLenient(RUN_ID)).isEmpty();
		verify(this.emitter, never()).suspended(any(), anyString(), anyString(), anyString());
	}

	// ── ② 结构化 create_task 缺 type：宿主直接给单选 ─────────────────────────

	@Test
	void parksSingleEnumTypeFormWhenStructuredCreateTaskMissesType() {
		boolean parked = structuredPark(context(Map.of()), "{\"title\":\"币种导出\"}", "创建一个任务");

		assertThat(parked).as("create_task 缺 type ⇒ 宿主必须先问类型（不是模型文字反问）").isTrue();

		PendingToolCall pending = onlyPending();
		assertThat(pending.getName()).isEqualTo(GenerativeFormRules.TOOL_NAME);
		assertThat(pending.getKind()).as("前端工具：走既有表单卡").isEqualTo(PendingToolCall.KIND_FRONTEND);
		assertThat(pending.getStatus()).isEqualTo(PendingToolCall.PENDING);
		assertThat(pending.getToolCallId()).isEqualTo(TaskTypeForm.toolCallId(RUN_ID));
		assertThat(pending.isIrreversible()).isFalse();

		JsonNode form = parse(pending.getArguments()).path("form");
		assertThat(form.path("purpose").asText()).isEqualTo(GenerativeFormRules.PURPOSE_TASK_TYPE);
		assertThat(form.path("scenario").asText()).isEqualTo("CLARIFY");
		JsonNode fields = form.path("fields");
		assertThat(fields).as("类型表单只有一个字段").hasSize(1);
		JsonNode field = fields.get(0);
		assertThat(field.path("key").asText()).isEqualTo(TaskTypeForm.FIELD_KEY);
		assertThat(field.path("type").asText()).as("必须是单选，不能是自由文本或多选").isEqualTo("enum");
		assertThat(field.path("required").asBoolean()).isTrue();
		assertThat(field.path("options").findValuesAsText("value")).containsExactly("EXPORT", "IMPORT");
		assertThat(field.has("defaultValue")).as("不预填旧任务/猜测的类型").isFalse();

		// 挂起态与帧链：SUSPENDED + 请求帧置位（刷新恢复能重挂这张卡）
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(snapshot.getInFlightToolCalls()).extracting(PendingToolCall::getToolCallId)
			.containsExactly(TaskTypeForm.toolCallId(RUN_ID));
		assertThat(this.store.pendingsLenient(RUN_ID).get(0).getReq()).as("请求帧已置位").isTrue();
		verify(this.emitter).suspended(eq(List.of(pending)), anyString(), anyString(), anyString());
	}

	@Test
	void explicitTypeInStructuredCreateTaskDoesNotAsk() {
		boolean parked = structuredPark(context(Map.of()), "{\"type\":\"EXPORT\"}", "创建导出任务");

		assertThat(parked).as("type 已给出 ⇒ 不弹类型表（直接 create_task）").isFalse();
		assertThat(this.store.pendingsLenient(RUN_ID)).isEmpty();
	}

	// ── ③ 旧任务页面的类型不得压掉新任务的缺口 ────────────────────────────────

	@Test
	void oldTaskPageTypeDoesNotSuppressTheStructuredGap() {
		// 现场：还停在旧 EXPORT 任务 #7 的向导上（上下文 taskType=EXPORT、槽位也带着 EXPORT）
		boolean parked = structuredPark(oldTaskContext(), "{\"title\":\"新建\"}", "创建一个任务");

		assertThat(parked).as("旧任务的 EXPORT 属于那条旧任务，不得压掉新任务的类型缺口（规格：这里必须弹）").isTrue();
		assertThat(onlyPending().getToolCallId()).isEqualTo(TaskTypeForm.toolCallId(RUN_ID));
	}

	@Test
	void explicitImportTypeOnTheOldTaskPageDoesNotAsk() {
		boolean parked = structuredPark(oldTaskContext(), "{\"type\":\"IMPORT\"}", "创建导入任务");

		assertThat(parked).as("create_task 已给出 IMPORT ⇒ 直接走它，旧页面的 EXPORT 不得改写").isFalse();
		assertThat(this.store.pendingsLenient(RUN_ID)).isEmpty();
	}

	// ── ④ 同一轮只问一次：提交后都不再来一张 ────────────────────────────────

	@Test
	void answeringTheFormStopsTheSameRunFromAskingAgain() {
		assertThat(structuredPark(context(Map.of()), "{\"title\":\"新建\"}", "创建一个任务")).isTrue();
		this.gate.submitFrontendResult(RUN_ID, TaskTypeForm.toolCallId(RUN_ID), "{\"type\":\"IMPORT\"}",
				"unit-test");

		boolean parkedAgain = structuredPark(context(Map.of()), "{\"title\":\"新建\"}", "创建一个任务");

		assertThat(parkedAgain).as("同一轮那张类型表单已经问过（含已决）⇒ 不得再弹").isFalse();
		assertThat(this.store.pendingsLenient(RUN_ID)).as("只留一张卡").hasSize(1);
		verify(this.emitter, never()).frontendToolResult(any(PendingToolCall.class), eq(false), anyString());
	}

	// ── ⑤ 宿主表单与模型自发表单同一份闸门（含"类型已知即拒绝"）──────────────

	@Test
	void theTypeFormPassesTheSameWhitelistGate() {
		assertThat(structuredPark(context(Map.of()), "{\"title\":\"新建\"}", "创建一个任务")).isTrue();
		String arguments = onlyPending().getArguments();

		assertThat(this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME, arguments, context(Map.of()))
			.accepted()).as("宿主表单必须能通过既有的 schema 白名单").isTrue();
		assertThat(this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME, arguments,
				context(knownType("EXPORT"))).accepted())
			.as("类型已知时同一张表被槽位放行拒绝 —— 宿主判据与闸门是同一份")
			.isFalse();
	}

	/** Task 1 第 6 项：宿主表单标题/标签/选项复用 {@code ReplyLanguage}，英文请求不出现中文文案。 */
	@Test
	void typeFormCopyFollowsTheReplyLanguage() {
		java.util.Locale previous = org.springframework.context.i18n.LocaleContextHolder.getLocale();
		try {
			org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.ENGLISH);
			assertThat(structuredPark(context(Map.of()), "{\"title\":\"new\"}", "create a task")).isTrue();
			String arguments = onlyPending().getArguments();

			assertThat(arguments).contains(TaskTypeForm.TITLE_EN)
				.contains(TaskTypeForm.FIELD_LABEL_EN)
				.contains("Export")
				.contains("Import")
				.doesNotContain(TaskTypeForm.TITLE)
				.doesNotContain(TaskTypeForm.FIELD_LABEL);
		}
		finally {
			org.springframework.context.i18n.LocaleContextHolder.setLocale(previous);
		}
	}

	// ── 桩与辅助 ────────────────────────────────────────────────────────────

	private boolean park(String message) {
		return this.form.parkIfDue(RUN_ID, context(Map.of()), message, history(message));
	}

	private boolean structuredPark(AiContext context, String argumentsJson, String message) {
		return this.form.parkIfCreateTaskMissingType(RUN_ID, context, argumentsJson, history(message));
	}

	private PendingToolCall onlyPending() {
		List<PendingToolCall> pendings = this.store.pendingsLenient(RUN_ID);
		assertThat(pendings).hasSize(1);
		return pendings.get(0);
	}

	private void seedRun() {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(context(Map.of()).toMap());
		snapshot.setMessageJson(this.codec.serializeAll(history("创建一个任务")));
		this.store.save(snapshot);
	}

	private static List<org.springframework.ai.chat.messages.Message> history(String userMessage) {
		return List.of(new SystemMessage("PERSONA"), new UserMessage(userMessage));
	}

	private static AiContext context(Map<String, Object> extra) {
		return AiContext.of("tasks", null, null, null, null, extra);
	}

	/** 旧 EXPORT 任务页：上下文与槽位都带着那条旧任务的类型（新任务的类型不能从它推出）。 */
	private static AiContext oldTaskContext() {
		return AiContext.of("export", "EXPORT", "SELECT_DEFS", 7L, "ACTIVE", knownType("EXPORT"));
	}

	private static Map<String, Object> knownType(String taskType) {
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put(TaskSlots.EXTRA_KEY, new TaskSlots(taskType, List.of(), List.of()).toMap());
		return extra;
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
