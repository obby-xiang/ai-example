package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.RunContextRefresher;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.slot.DefinitionCatalog;
import com.example.configmgr.ai.slot.SlotDefinition;
import com.example.configmgr.ai.slot.TaskSlots;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 宿主确定性定义选择表单：<b>新任务 + 停在选择步骤 + 配置未知</b> ⇒ 宿主自己挂起一张平铺多选表单。
 *
 * <h2>被钉住的缺陷</h2>
 * 过去这一步完全交给模型自由决定：同一现场下它可能写成文字反问（"请告诉我导出哪个配置"），
 * 也可能自己拼一份层级 options 的表单。用户口径是<b>确定性的</b>：新建任务 → 上下文到达选择步骤 →
 * 配置未知 ⇒ 宿主直接挂起 {@code purpose=SELECT_DEFINITIONS} 的平铺多选，选项来自
 * {@code DefinitionCatalog}（{@code value=defCode}、{@code label=定义名称}），不需要
 * {@code dependsOn} / 层级，也不需要模型先 {@code list_config_defs}。
 *
 * <h2>本用例锁的口径</h2>
 * <ol>
 * <li>只在新任务链（本轮台账里确有 {@code create_task} 成功）+ 权威上下文的 taskId 是<b>那个</b>任务 +
 * 页/步骤是 {@code export}/{@code SELECT_DEFS} + 配置未知时挂起；导航是否成功、导航到哪都不参与判定
 * （前端导航有等待上限，瞬时边等不到时表单仍须出现）；普通浏览既有页面不弹；</li>
 * <li>已知配置（槽位里有 defCodes）不弹 —— 仍走 {@code select_definitions}；</li>
 * <li>目录为空/读取失败/超出平铺上限 ⇒ <b>不生成空表单</b>（走既有失败恢复，模型侧照旧）；</li>
 * <li>表单正文仍由既有白名单闸门（{@link GenerativeFormGuard}）与既有回灌/取消路径承认 ——
 * 本批不新增表单框架，也不新增中断路径。</li>
 * <li>选择成功后的推进由宿主经既有 {@code confirm_step} 通道完成；选择失败或本批已有推进时
 * <b>不重复下发</b>（避免同一批里推进两次）。</li>
 * </ol>
 */
class DefinitionSelectionFormTest {

	private static final String RUN_ID = "run-def-form";

	private static final long TASK_ID = 42L;

	private final RunStore store = mock(RunStore.class);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final ConfirmGate gate = mock(ConfirmGate.class);

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final AiProperties properties = new AiProperties();

	private final List<SlotDefinition> catalogEntries = new ArrayList<>(List.of(
			new SlotDefinition("CURRENCY", "币种", List.of()),
			new SlotDefinition("FX_RATE", "汇率", List.of())));

	private final DefinitionCatalog catalog = () -> new ArrayList<>(this.catalogEntries);

	private DefinitionSelectionForm form;

	private final GenerativeFormGuard guard = new GenerativeFormGuard(new ObjectMapper());

	@BeforeEach
	void setUp() {
		this.properties.getPark().setEnabled(true);
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.store.runKey(RUN_ID)).thenReturn("ai:run:" + RUN_ID);
		when(this.store.pendingKey(RUN_ID)).thenReturn("ai:pending:" + RUN_ID);
		when(this.store.instanceId()).thenReturn("test-instance");
		when(this.store.ledger(RUN_ID)).thenReturn(List.of(createTaskRecord(TASK_ID)));
		when(this.store.pendingsLenient(RUN_ID)).thenReturn(List.of());
		this.form = new DefinitionSelectionForm(this.store, this.registry, this.gate, this.codec, this.properties,
				this.catalog);
	}

	// ── ① 新任务 + 未知配置：必出平铺多选 ──────────────────────────────────

	@Test
	void parksFlatMultiSelectFormAfterCreatingAndNavigatingToTheNewTask() {
		boolean parked = this.form.parkIfDue(RUN_ID, selectionStepContext(Map.of()),
				List.of(navigateTo(TASK_ID, ToolOutcome.OK)), history());

		assertThat(parked).as("新任务 + 导航成功 + 配置未知 ⇒ 宿主必须自己挂起表单").isTrue();

		PendingToolCall pending = capturedPending();
		assertThat(pending.getName()).isEqualTo(GenerativeFormRules.TOOL_NAME);
		assertThat(pending.getKind()).isEqualTo(PendingToolCall.KIND_FRONTEND);
		assertThat(pending.getStatus()).isEqualTo(PendingToolCall.PENDING);
		// 同一个任务只会挂起一张宿主表单（toolCallId 由任务号决定，重导航不重弹）
		assertThat(pending.getToolCallId()).isEqualTo(DefinitionSelectionForm.toolCallId(TASK_ID));

		JsonNode arguments = parse(pending.getArguments());
		JsonNode form = arguments.path("form");
		assertThat(form.path("purpose").asText()).isEqualTo(GenerativeFormRules.PURPOSE_SELECT_DEFINITIONS);
		assertThat(form.path("scenario").asText()).isIn("CLARIFY", "FILTER");
		JsonNode fields = form.path("fields");
		assertThat(fields).hasSize(1);
		JsonNode field = fields.get(0);
		assertThat(field.path("key").asText()).isEqualTo(DefinitionSelectionForm.FIELD_KEY);
		assertThat(field.path("type").asText()).isEqualTo("multi_select");
		assertThat(field.path("required").asBoolean()).isTrue();
		// 平铺多选：直接是配置项，没有任何层级/级联/预填
		assertThat(field.has("optionsFrom")).isFalse();
		assertThat(field.has("defaultValue")).isFalse();
		assertThat(field.path("options")).hasSize(2);
		assertThat(field.path("options").get(0).path("value").asText()).isEqualTo("CURRENCY");
		assertThat(field.path("options").get(0).path("label").asText()).isEqualTo("币种");
		assertThat(field.path("options").get(1).path("value").asText()).isEqualTo("FX_RATE");
		assertThat(field.path("options").get(1).path("label").asText()).isEqualTo("汇率");

		// 走既有挂起面：先外置挂起态，再发 suspended + 请求帧（前端据此渲染既有表单卡）
		verify(this.store).saveSuspended(eq(RUN_ID), org.mockito.ArgumentMatchers.isNull(),
				eq(List.of(pending)), any(), eq(this.codec), org.mockito.ArgumentMatchers.isNull());
		verify(this.emitter).suspended(eq(List.of(pending)), anyString(), anyString(), anyString());
		verify(this.gate).publishRequestsForParkedBatch(RUN_ID, List.of(pending));
	}

	@Test
	void generatedFormIsAcceptedByTheExistingWhitelistGate() {
		this.form.parkIfDue(RUN_ID, selectionStepContext(Map.of()),
				List.of(navigateTo(TASK_ID, ToolOutcome.OK)), history());
		PendingToolCall pending = capturedPending();

		// 下发前：既有白名单 + 槽位放行（配置未知 ⇒ 放行）
		assertThat(this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME, pending.getArguments(),
				selectionStepContext(Map.of())).accepted()).isTrue();
		// 回灌前：既有类型复核接受用户勾选（value = defCode）
		assertThat(this.guard.inspectResult(RUN_ID, pending.getToolCallId(), pending.getArguments(),
				"{\"" + DefinitionSelectionForm.FIELD_KEY + "\":[\"CURRENCY\",\"FX_RATE\"]}").accepted()).isTrue();
	}

	// ── ② 已知配置不弹 ────────────────────────────────────────────────────

	@Test
	void knownConfigNeverPopsTheForm() {
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put(TaskSlots.EXTRA_KEY, new TaskSlots("EXPORT", List.of("CURRENCY"), List.of()).toMap());

		boolean parked = this.form.parkIfDue(RUN_ID, selectionStepContext(extra),
				List.of(navigateTo(TASK_ID, ToolOutcome.OK)), history());

		assertThat(parked).as("配置已确定 ⇒ 不弹表单，直接走 select_definitions").isFalse();
		verify(this.store, never()).saveSuspended(anyString(), any(), any(), any(), any(), any());
		verifyNoInteractions(this.gate);
	}

	@Test
	void noFormWhenTheBatchAlreadyHandledTheSelectionItself() {
		PendingToolCall ownForm = entry("call-own-form", GenerativeFormRules.TOOL_NAME,
				"{\"form\":{\"scenario\":\"CLARIFY\",\"fields\":[]}}", PendingToolCall.KIND_FRONTEND,
				ToolOutcome.OK);
		boolean parked = this.form.parkIfDue(RUN_ID, selectionStepContext(Map.of()),
				List.of(navigateTo(TASK_ID, ToolOutcome.OK), ownForm), history());

		assertThat(parked).as("本批已有模型自己的表单 ⇒ 宿主不再重复挂一张").isFalse();
		verify(this.store, never()).saveSuspended(anyString(), any(), any(), any(), any(), any());
	}

	@Test
	void optionsKeepTheCatalogOrderAndFallBackToTheCodeWhenTheNameIsBlank() {
		this.catalogEntries.clear();
		this.catalogEntries.add(new SlotDefinition("ROLE_DICT", "角色字典", List.of()));
		this.catalogEntries.add(new SlotDefinition("CURRENCY", "   ", List.of()));
		this.catalogEntries.add(new SlotDefinition("FX_RATE", "汇率", List.of()));

		this.form.parkIfDue(RUN_ID, selectionStepContext(Map.of()),
				List.of(navigateTo(TASK_ID, ToolOutcome.OK)), history());

		JsonNode options = parse(capturedPending().getArguments()).path("form").path("fields").get(0)
			.path("options");
		assertThat(options.findValuesAsText("value")).as("选项顺序 = 目录顺序（用户按目录认项）")
			.containsExactly("ROLE_DICT", "CURRENCY", "FX_RATE");
		assertThat(options.findValuesAsText("label")).as("名称为空时回落编码，不留空标签")
			.containsExactly("角色字典", "CURRENCY", "汇率");
	}

	@Test
	void importWizardNeverPopsTheSelectionForm() {
		// 导入向导的首步是 UPLOAD（没有"选择配置项"这一步），因此这里不该有宿主表单
		AiContext context = AiContext.of("import", "IMPORT", "UPLOAD", TASK_ID, "ACTIVE", Map.of());

		boolean parked = this.form.parkIfDue(RUN_ID, context,
				List.of(entry("call-navigate", RunContextRefresher.TOOL_NAVIGATE,
						"{\"page\":\"import\",\"taskId\":" + TASK_ID + "}", PendingToolCall.KIND_FRONTEND,
						ToolOutcome.OK)), history());

		assertThat(parked).isFalse();
		verify(this.store, never()).saveSuspended(anyString(), any(), any(), any(), any(), any());
	}

	// ── ③ 权威状态判据：导航边不参与；普通浏览 / 别的页面 / 别的步骤 / 别的任务不弹 ──

	@Test
	void browsingAnExistingTaskNeverPopsTheForm() {
		when(this.store.ledger(RUN_ID)).thenReturn(List.of());

		boolean parked = this.form.parkIfDue(RUN_ID, selectionStepContext(Map.of()),
				List.of(navigateTo(TASK_ID, ToolOutcome.OK)), history());

		assertThat(parked).as("没有 create_task 的普通浏览 ⇒ 宿主不得弹表单").isFalse();
		verify(this.store, never()).saveSuspended(anyString(), any(), any(), any(), any(), any());
	}

	@Test
	void navigationTargetIsNotPartOfTheStateCriterion() {
		boolean parked = this.form.parkIfDue(RUN_ID, selectionStepContext(Map.of()),
				List.of(navigateTo(7L, ToolOutcome.OK)), history());

		assertThat(parked).as("判据看权威上下文（page/taskId/step），导航到哪个任务不参与判定").isTrue();
		assertThat(capturedPending().getToolCallId()).isEqualTo(DefinitionSelectionForm.toolCallId(TASK_ID));
	}

	@Test
	void failedNavigationStillPopsTheFormWhenPageTaskAndStepMatch() {
		boolean parked = this.form.parkIfDue(RUN_ID, selectionStepContext(Map.of()),
				List.of(navigateTo(TASK_ID, ToolOutcome.FAILED)), history());

		assertThat(parked).as("page/taskId/step 满足 + 配置未知 ⇒ 导航 failed 也必须发表单").isTrue();
		JsonNode form = parse(capturedPending().getArguments()).path("form");
		assertThat(form.path("purpose").asText()).isEqualTo(GenerativeFormRules.PURPOSE_SELECT_DEFINITIONS);
		assertThat(form.path("fields")).hasSize(1);
		assertThat(form.path("fields").get(0).path("key").asText()).isEqualTo(DefinitionSelectionForm.FIELD_KEY);
	}

	@Test
	void contextPointingAtAnotherTaskNeverPopsTheForm() {
		AiContext context = AiContext.of("export", "EXPORT", "SELECT_DEFS", 7L, "ACTIVE", Map.of());

		boolean parked = this.form.parkIfDue(RUN_ID, context, List.of(navigateTo(7L, ToolOutcome.OK)), history());

		assertThat(parked).as("上下文任务号不是本轮新建任务 ⇒ 即使页/步骤对也不弹").isFalse();
		verify(this.store, never()).saveSuspended(anyString(), any(), any(), any(), any(), any());
	}

	@Test
	void selectionStepOnAnotherPageNeverPopsTheForm() {
		AiContext context = AiContext.of("tasks", "EXPORT", "SELECT_DEFS", TASK_ID, "ACTIVE", Map.of());

		boolean parked = this.form.parkIfDue(RUN_ID, context, List.of(navigateTo(TASK_ID, ToolOutcome.OK)),
				history());

		assertThat(parked).isFalse();
		verify(this.store, never()).saveSuspended(anyString(), any(), any(), any(), any(), any());
	}

	@Test
	void stepPastSelectionNeverPopsTheForm() {
		AiContext context = AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of());

		boolean parked = this.form.parkIfDue(RUN_ID, context, List.of(navigateTo(TASK_ID, ToolOutcome.OK)),
				history());

		assertThat(parked).isFalse();
		verify(this.store, never()).saveSuspended(anyString(), any(), any(), any(), any(), any());
	}

	@Test
	void theSameTaskNeverPopsASecondForm() {
		PendingToolCall already = entry(DefinitionSelectionForm.toolCallId(TASK_ID),
				GenerativeFormRules.TOOL_NAME, "{}", PendingToolCall.KIND_FRONTEND, null);
		when(this.store.pendingsLenient(RUN_ID)).thenReturn(List.of(already));

		boolean parked = this.form.parkIfDue(RUN_ID, selectionStepContext(Map.of()),
				List.of(navigateTo(TASK_ID, ToolOutcome.OK)), history());

		assertThat(parked).as("同一任务已挂过宿主表单 ⇒ 不重复挂起").isFalse();
		verify(this.store, never()).saveSuspended(anyString(), any(), any(), any(), any(), any());
	}

	@Test
	void parkDisabledNeverPopsTheForm() {
		this.properties.getPark().setEnabled(false);

		boolean parked = this.form.parkIfDue(RUN_ID, selectionStepContext(Map.of()),
				List.of(navigateTo(TASK_ID, ToolOutcome.OK)), history());

		assertThat(parked).as("非 park 装配（阻塞式等待）不引入新中断路径").isFalse();
		verify(this.store, never()).saveSuspended(anyString(), any(), any(), any(), any(), any());
	}

	// ── ④ 目录空 / 读取失败 / 超上限：不得生成空表单 ─────────────────────────

	@Test
	void emptyCatalogNeverProducesAnEmptyForm() {
		this.catalogEntries.clear();

		boolean parked = this.form.parkIfDue(RUN_ID, selectionStepContext(Map.of()),
				List.of(navigateTo(TASK_ID, ToolOutcome.OK)), history());

		assertThat(parked).as("目录为空 ⇒ 不生成空表单（走既有失败恢复）").isFalse();
		verify(this.store, never()).saveSuspended(anyString(), any(), any(), any(), any(), any());
	}

	@Test
	void catalogReadFailureNeverProducesAnEmptyForm() {
		this.form = new DefinitionSelectionForm(this.store, this.registry, this.gate, this.codec, this.properties,
				() -> {
					throw new IllegalStateException("catalog down");
				});

		boolean parked = this.form.parkIfDue(RUN_ID, selectionStepContext(Map.of()),
				List.of(navigateTo(TASK_ID, ToolOutcome.OK)), history());

		assertThat(parked).as("目录读取失败 ⇒ 不生成空表单").isFalse();
		verify(this.store, never()).saveSuspended(anyString(), any(), any(), any(), any(), any());
	}

	@Test
	void catalogBeyondTheFlatOptionLimitNeverProducesAPartialForm() {
		this.catalogEntries.clear();
		for (int i = 0; i < GenerativeFormRules.MAX_OPTIONS + 1; i++) {
			this.catalogEntries.add(new SlotDefinition("DEF_" + i, "定义 " + i, List.of()));
		}

		boolean parked = this.form.parkIfDue(RUN_ID, selectionStepContext(Map.of()),
				List.of(navigateTo(TASK_ID, ToolOutcome.OK)), history());

		assertThat(parked).as("超出平铺上限 ⇒ 不生成残缺表单（宁可交回既有路径）").isFalse();
		verify(this.store, never()).saveSuspended(anyString(), any(), any(), any(), any(), any());
	}

	// ── ⑤ 挂起写失败：不留"看不见的待决条目"（否则续跑会被 PENDING_UNRESOLVED 卡死） ──

	@Test
	void parkWriteFailureConvergesTheEntryInsteadOfLeavingItPending() {
		org.mockito.Mockito.doThrow(new IllegalStateException("redis down")).when(this.store)
			.saveSuspended(anyString(), any(), any(), any(), any(), any());

		boolean parked = this.form.parkIfDue(RUN_ID, selectionStepContext(Map.of()),
				List.of(navigateTo(TASK_ID, ToolOutcome.OK)), history());

		assertThat(parked).isFalse();
		ArgumentCaptor<PendingToolCall> converged = ArgumentCaptor.forClass(PendingToolCall.class);
		verify(this.store).putPending(eq(RUN_ID), converged.capture());
		assertThat(converged.getValue().getStatus()).as("收敛成终态：不再以 PENDING 挡住续跑").isEqualTo(
				PendingToolCall.BLOCKED);
		assertThat(converged.getValue().isExecuted()).isFalse();
		// 没有挂起，就没有任何"卡已下发"的帧
		verify(this.emitter, never()).suspended(any(), anyString(), anyString(), anyString());
		verifyNoInteractions(this.gate);
	}

	// ── ⑥ 选择成功后的宿主推进：失败/本批已处理时不重复下发 ────────────────

	@Test
	void failedSelectionNeverAdvancesTheWizardFromTheHost() {
		PendingToolCall failedSelection = entry("call-select", "select_definitions",
				"{\"codes\":[\"CURRENCY\"]}", PendingToolCall.KIND_FRONTEND, ToolOutcome.FAILED);

		boolean parked = this.form.parkAdvanceIfDue(RUN_ID, selectionStepContext(knownConfig()),
				List.of(failedSelection), history());

		assertThat(parked).as("选择失败 ⇒ 宿主不得代替用户/模型推进步骤").isFalse();
		verify(this.store, never()).saveSuspended(anyString(), any(), any(), any(), any(), any());
		verifyNoInteractions(this.gate);
	}

	@Test
	void batchThatAlreadyAdvancedIsNotAdvancedTwice() {
		PendingToolCall selected = entry("call-select", "select_definitions",
				"{\"codes\":[\"CURRENCY\"]}", PendingToolCall.KIND_FRONTEND, ToolOutcome.OK);
		PendingToolCall alreadyAdvanced = entry("call-step", "confirm_step", "{}",
				PendingToolCall.KIND_FRONTEND, ToolOutcome.OK);

		boolean parked = this.form.parkAdvanceIfDue(RUN_ID, selectionStepContext(knownConfig()),
				List.of(selected, alreadyAdvanced), history());

		assertThat(parked).as("本批已有 confirm_step ⇒ 宿主不再重复推进").isFalse();
		verify(this.store, never()).saveSuspended(anyString(), any(), any(), any(), any(), any());
		verifyNoInteractions(this.gate);
	}

	// ── 桩与辅助 ────────────────────────────────────────────────────────────

	private PendingToolCall capturedPending() {
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<PendingToolCall>> captor = ArgumentCaptor.forClass(List.class);
		verify(this.store).saveSuspended(eq(RUN_ID), org.mockito.ArgumentMatchers.isNull(), captor.capture(),
				any(), eq(this.codec), org.mockito.ArgumentMatchers.isNull());
		assertThat(captor.getValue()).hasSize(1);
		return captor.getValue().get(0);
	}

	private static List<Message> history() {
		return List.of(new UserMessage("帮我新建一个导出任务"));
	}

	private static AiContext selectionStepContext(Map<String, Object> extra) {
		return AiContext.of("export", "EXPORT", "SELECT_DEFS", TASK_ID, "ACTIVE", extra);
	}

	/** 已落条目的已知配置（选择成功之后交棒上下文里应有的槽位）。 */
	private static Map<String, Object> knownConfig() {
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put(TaskSlots.EXTRA_KEY, new TaskSlots("EXPORT", List.of("CURRENCY"), List.of()).toMap());
		return extra;
	}

	private static Map<String, Object> createTaskRecord(long taskId) {
		Map<String, Object> record = new LinkedHashMap<>();
		record.put("name", RunContextRefresher.TOOL_CREATE_TASK);
		record.put("outcome", ToolOutcome.OK.wire());
		record.put("resultText", "已创建导出任务 #" + taskId + "（当前步骤：SELECT_DEFS）");
		return record;
	}

	private static PendingToolCall navigateTo(long taskId, ToolOutcome outcome) {
		return entry("call-navigate", RunContextRefresher.TOOL_NAVIGATE,
				"{\"page\":\"export\",\"taskId\":" + taskId + "}", PendingToolCall.KIND_FRONTEND, outcome);
	}

	private static PendingToolCall entry(String toolCallId, String name, String arguments, String kind,
			ToolOutcome outcome) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName(name);
		pending.setType("function");
		pending.setArguments(arguments);
		pending.setKind(kind);
		pending.setStatus(PendingToolCall.FRONTEND_RESULT);
		pending.setOutcome(outcome == null ? null : outcome.wire());
		pending.setResultText("前端已执行：" + name);
		return pending;
	}

	private JsonNode parse(String json) {
		try {
			return this.mapper.readTree(json);
		}
		catch (Exception ex) {
			throw new IllegalStateException("表单入参不是合法 JSON：" + json, ex);
		}
	}

}
