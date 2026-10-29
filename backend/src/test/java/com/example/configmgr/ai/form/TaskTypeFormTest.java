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
 * 宿主确定性<b>任务类型</b>表单：用户只说"创建/新建任务"、类型又说不出时，由宿主先挂起
 * {@code EXPORT/IMPORT} 单选，而不是等模型写成文字反问。
 *
 * <h2>被钉住的缺陷</h2>
 * 同一句话下模型可能直接问"要创建导出还是导入任务？"——那是把结构化缺口退回开放式文本。
 * 用户口径是确定性的：<b>新建意图 + 类型未知 ⇒ 宿主直接发单选</b>；类型已知 ⇒ 不弹，直接走近
 * {@code create_task} 的确认卡。
 *
 * <h2>本用例锁的口径</h2>
 * <ol>
 * <li>类型未知 + 新建意图 ⇒ 挂起单 enum 单选（选项正好 EXPORT/IMPORT），走既有挂起面；</li>
 * <li>类型已知（槽位里有）⇒ 不弹；问规则（"怎么创建任务"）与普通浏览 ⇒ 不弹；</li>
 * <li>同一轮只问一次：提交/取消之后都不再来第二张；</li>
 * <li>宿主表单与模型自发表单过<b>同一份</b>白名单闸门 —— 它没有"绕过放行判据"的特权。</li>
 * </ol>
 *
 * <p><b>证红</b>：宿主不再挂起 ⇒ 第 1/4/5 条红；判据漏掉槽位（类型已知仍弹）⇒ 第 2 条红；
 * 去重漏掉（同一轮问两次）⇒ 第 3 条红。
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

	// ── ① 类型未知 + 新建意图：宿主直接给单选 ───────────────────────────────

	@Test
	void parksSingleEnumTypeFormWhenNewTaskIntentAndTypeUnknown() {
		boolean parked = park("帮我创建一个任务");

		assertThat(parked).as("新建意图 + 类型未知 ⇒ 宿主必须先问类型（不是模型文字反问）").isTrue();

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

	// ── ② 类型已知：不弹，交给 create_task ─────────────────────────────────

	@Test
	void doesNotParkWhenTypeIsAlreadyKnown() {
		boolean parked = this.form.parkIfDue(RUN_ID, context(knownType("EXPORT")), "帮我创建一个任务", history("帮我创建一个任务"));

		assertThat(parked).as("类型已给出/已确定 ⇒ 绝不弹类型表单（直接走 create_task）").isFalse();
		assertThat(this.store.pendingsLenient(RUN_ID)).isEmpty();
		verify(this.emitter, never()).suspended(any(), anyString(), anyString(), anyString());
	}

	// ── ③ 普通浏览与问规则：不打扰 ─────────────────────────────────────────

	@Test
	void doesNotParkForBrowsingOrRuleQuestions() {
		assertThat(this.form.parkIfDue(RUN_ID, context(Map.of()), "打开任务 7 看看", history("打开任务 7 看看")))
			.as("普通浏览既有任务不是新建意图").isFalse();
		assertThat(this.form.parkIfDue(RUN_ID, context(Map.of()), "怎么创建任务？", history("怎么创建任务？")))
			.as("问规则不是新建意图").isFalse();
		assertThat(this.form.parkIfDue(RUN_ID, context(Map.of()), "不要创建任务", history("不要创建任务")))
			.as("否定不是新建意图").isFalse();
		assertThat(this.store.pendingsLenient(RUN_ID)).isEmpty();
	}

	// ── ④ 同一轮只问一次：提交/取消后都不再来一张 ──────────────────────────

	@Test
	void answeringTheFormStopsTheSameRunFromAskingAgain() {
		assertThat(park("创建一个任务")).isTrue();
		this.gate.submitFrontendResult(RUN_ID, TaskTypeForm.toolCallId(RUN_ID), "{\"type\":\"IMPORT\"}",
				"unit-test");

		boolean parkedAgain = this.form.parkIfDue(RUN_ID, context(Map.of()), "创建一个任务", history("创建一个任务"));

		assertThat(parkedAgain).as("同一轮那张类型表单已经问过（含已决）⇒ 不得再弹").isFalse();
		assertThat(this.store.pendingsLenient(RUN_ID)).as("只留一张卡").hasSize(1);
		verify(this.emitter, never()).frontendToolResult(any(PendingToolCall.class), eq(false), anyString());
	}

	// ── ⑤ 宿主表单与模型自发表单同一份闸门（含"类型已知即拒绝"）──────────────

	@Test
	void theTypeFormPassesTheSameWhitelistGate() {
		assertThat(park("创建一个任务")).isTrue();
		String arguments = onlyPending().getArguments();

		assertThat(this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME, arguments, context(Map.of()))
			.accepted()).as("宿主表单必须能通过既有的 schema 白名单").isTrue();
		assertThat(this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME, arguments,
				context(knownType("EXPORT"))).accepted())
			.as("类型已知时同一张表被槽位放行拒绝 —— 宿主判据与闸门是同一份")
			.isFalse();
	}

	// ── 桩与辅助 ────────────────────────────────────────────────────────────

	private boolean park(String message) {
		return this.form.parkIfDue(RUN_ID, context(Map.of()), message, history(message));
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
