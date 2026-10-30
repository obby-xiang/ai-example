package com.example.configmgr.ai.slot;

import com.example.configmgr.ai.history.SyntheticMessages;
import com.example.configmgr.ai.slot.TaskSlots.ConditionSlot;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 任务槽位解析（Rasa Forms 式缺失槽位模式）：只放"已知"值，槽位为空即缺失。
 *
 * <p><b>自然语言信任边界（2026-10-30 确定性工作流）</b>：{@link TaskSlotResolver} 不再解析用户原话，
 * 可信事实只有两类 —— 任务条目（{@code conditionJson} 由前端结构化
 * {@code set_condition} / 确认门落库）与本轮结构化 {@code create_task(defCodes=…)} 候选
 * （服务端写入 {@link TaskSlotResolver#CREATE_TASK_DEF_CODES_KEY}，且绑定创建它的任务号）。
 * 因此本类断言的是"结构化事实 → 槽位"的口径；"自由文本不再被解析"由一条反向锁钉住。
 *
 * <p>本类不启 Spring：{@link DefinitionCatalog} 用固定目录，{@link TaskService} 用替身。
 */
class TaskSlotResolverTest {

	private static final long TASK_ID = 42L;

	private final TaskService taskService = mock(TaskService.class);

	private final TaskSlotResolver resolver = new TaskSlotResolver(catalog());

	private final TaskSlotResolver withTasks = new TaskSlotResolver(catalog(), this.taskService, new ObjectMapper());

	// ── 信任边界：自由文本不再是事实来源 ───────────────────────────────────

	/**
	 * 反向锁：本轮原文与历史原文里明明写着配置项与条件，没有结构化事实时槽位仍必须是空的。
	 * （旧实现会解析出 CURRENCY + code=CNY；那条文本可信链已按裁决删除。）
	 */
	@Test
	void freeTextNoLongerFillsSlots() {
		TaskSlots current = this.resolver.resolve(AiContext.empty(), "帮我导出 CURRENCY，条件 code = CNY", List.of());
		assertThat(current.defCodes()).as("原文不是结构化事实：不解析").isEmpty();
		assertThat(current.conditions()).isEmpty();
		assertThat(current.taskType()).as("任务类型也不再由宿主从措辞推断").isNull();

		TaskSlots history = this.resolver.resolve(AiContext.empty(), "继续",
				List.of("帮我导出 CURRENCY，筛选 code = CNY"));
		assertThat(history.defCodes()).as("历史原文同样不是事实来源").isEmpty();
		assertThat(history.conditions()).isEmpty();
	}

	// ── create_task 结构化候选（任务条目尚未落库时） ──────────────────────────

	@Test
	void createTaskCandidatesStandInForTheNewTask() {
		TaskSlots slots = this.resolver.resolve(createTaskContext(TASK_ID, "CURRENCY", "ROLE_DICT"), null, List.of());

		assertThat(slots.defCodes())
			.as("多项明确配置是合法的多选导出，不是歧义")
			.containsExactly("CURRENCY", "ROLE_DICT");
		assertThat(slots.taskType()).as("任务类型来自上下文结构化值，不来自措辞").isEqualTo("EXPORT");
	}

	/** 候选与创建它的任务号绑定：切到别的任务后旧候选不得压掉新任务的选择表。 */
	@Test
	void candidatesBoundToAnotherTaskAreIgnored() {
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put(TaskSlotResolver.CREATE_TASK_DEF_CODES_KEY, List.of("CURRENCY"));
		extra.put(TaskSlotResolver.CREATE_TASK_TASK_ID_KEY, 7L);
		AiContext otherTask = AiContext.of("export", "EXPORT", "SELECT_DEFS", TASK_ID, extra);

		assertThat(this.resolver.resolve(otherTask, null, List.of()).defCodes()).isEmpty();
	}

	// ── 权威顺序：页面勾选 > 任务条目 > create_task 候选 ──────────────────────

	@Test
	void pageSelectionWinsOverCandidates() {
		Map<String, Object> candidates = new LinkedHashMap<>();
		candidates.put(TaskSlotResolver.CREATE_TASK_DEF_CODES_KEY, List.of("CURRENCY"));
		candidates.put(TaskSlotResolver.CREATE_TASK_TASK_ID_KEY, TASK_ID);
		Map<String, Object> selected = new LinkedHashMap<>(candidates);
		selected.putAll(Map.of("pageId", "export", "selectedDefs", List.of("ROLE_DICT"), "selectionSynced", true));
		AiContext workspace = AiContext.of("export", "EXPORT", "SELECT_DEFS", TASK_ID, selected);
		Map<String, Object> reselected = new LinkedHashMap<>(candidates);
		reselected.putAll(Map.of("pageId", "export", "selectedDefs", List.of("CURRENCY"), "selectionSynced", true));
		AiContext replaced = AiContext.of("export", "EXPORT", "SELECT_DEFS", TASK_ID, reselected);

		assertThat(this.resolver.resolve(workspace, null, List.of()).defCodes())
			.as("页面已勾选 ⇒ 页面权威，候选不覆盖")
			.containsExactly("ROLE_DICT");
		assertThat(this.resolver.resolve(replaced, null, List.of()).defCodes())
			.as("页面改选 ⇒ 以新的页面勾选为准")
			.containsExactly("CURRENCY");
	}

	@Test
	void taskItemsFillWhenPageHasNotSynced() {
		when(this.taskService.findById(any())).thenReturn(task("CURRENCY", null));

		AiContext notHydrated = AiContext.of("export", "EXPORT", "SELECT_DEFS", TASK_ID,
				Map.of("pageId", "export", "selectedDefs", List.of(), "selectionSynced", false));
		assertThat(this.withTasks.resolve(notHydrated, null, List.of()).defCodes())
			.as("未水合 ⇒ 按未同步回落任务条目")
			.containsExactly("CURRENCY");

		AiContext legacyClient = AiContext.of("export", "EXPORT", "SELECT_DEFS", TASK_ID,
				Map.of("pageId", "export", "selectedDefs", List.of()));
		assertThat(this.withTasks.resolve(legacyClient, null, List.of()).defCodes())
			.as("无标记（旧客户端）⇒ 同样按未同步处理")
			.containsExactly("CURRENCY");

		AiContext unsynced = AiContext.of("export", "EXPORT", "SELECT_DEFS", TASK_ID, Map.of("pageId", "export"));
		assertThat(this.withTasks.resolve(unsynced, null, List.of()).defCodes())
			.as("页面从未同步 ⇒ 回落任务条目")
			.containsExactly("CURRENCY");
	}

	/**
	 * <b>页面显式空列表 = 权威空值</b>（与 {@code ContextBuilder#pageSelectedDefs} 同一口径）：
	 * 用户在向导里把勾选全部取消、且任务详情已应用（{@code selectionSynced=true}）时，
	 * 客户端上报的 {@code selectedDefs=[]} 是"现在什么都没选"的事实 —— 不得回落到任务条目
	 * 把旧选择重新勾回来（那会让模型按用户已取消的选择继续推进）。
	 */
	@Test
	void explicitEmptyPageSelectionIsAuthoritativeAndDoesNotResurrectTaskItems() {
		when(this.taskService.findById(any())).thenReturn(task("CURRENCY", "{\"fields\":[{\"fieldCode\":\"code\","
				+ "\"operator\":\"EQ\",\"value\":\"CNY\"}]}"));
		AiContext workspace = AiContext.of("export", "EXPORT", "SELECT_DEFS", TASK_ID,
				Map.of("pageId", "export", "selectedDefs", List.of(), "selectionSynced", true));

		TaskSlots slots = this.withTasks.resolve(workspace, null, List.of());

		assertThat(slots.defCodes()).as("页面同步了空集合 ⇒ 权威空，不回落任务条目").isEmpty();
		assertThat(slots.conditions()).as("没有配置项就没有可承载的条件").isEmpty();
	}

	// ── 条件事实只来自任务 conditionJson ─────────────────────────────────────

	@Test
	void taskConditionJsonDrivesConditions() {
		when(this.taskService.findById(any())).thenReturn(task("CURRENCY", "{\"fields\":[{\"fieldCode\":\"code\","
				+ "\"operator\":\"EQ\",\"value\":\"CNY\"}]}"));
		AiContext context = AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of());

		TaskSlots slots = this.withTasks.resolve(context, null, List.of());

		assertThat(slots.defCodes()).containsExactly("CURRENCY");
		assertThat(slots.conditions()).containsExactly(new ConditionSlot("CURRENCY", "code", "CNY"));
		assertThat(slots.conditionDefCodes()).containsExactly("CURRENCY");
		assertThat(slots.undecidedDefCodes()).as("EQ 条件已成事实，不再算缺口").isEmpty();
	}

	@Test
	void noFilterIntentMeansNoConditions() {
		when(this.taskService.findById(any())).thenReturn(task("CURRENCY", null));
		AiContext context = AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of());

		TaskSlots slots = this.withTasks.resolve(context, null, List.of());

		assertThat(slots.defCodes()).containsExactly("CURRENCY");
		assertThat(slots.conditions())
			.as("用户没给条件事实，不得凭定义/值造条件")
			.isEmpty();
		assertThat(slots.undecidedDefCodes()).as("从未设置的条件仍是缺口（要收集）").containsExactly("CURRENCY");
	}

	// ── extra 白名单与快照往返 ──────────────────────────────────────────────

	@Test
	void enrichWritesWhitelistedExtraAndSurvivesSnapshotRoundTrip() {
		AiContext enriched = this.resolver.enrich(createTaskContext(TASK_ID, "CURRENCY"), null, List.of());

		TaskSlots fromExtra = TaskSlots.fromExtra(enriched.getExtra());
		assertThat(fromExtra.defCodes()).containsExactly("CURRENCY");
		// 快照读写同一形态（RunSnapshot 落 Redis 后再重建上下文）
		assertThat(TaskSlots.fromExtra(AiContext.fromMap(enriched.toMap()).getExtra()).defCodes())
			.containsExactly("CURRENCY");
	}

	/**
	 * 返修：压缩摘要/步数收尾等<b>合成 user 消息</b>不是用户原话，槽位解析（首轮与续跑重算同源）
	 * 不得把它们当作用户的选择判定 —— 否则摘要里"用户要导出 CURRENCY"会把已取消的勾选复活。
	 */
	@Test
	void syntheticSummaryIsNotTreatedAsUserText() {
		Message summary = UserMessage.builder()
			.text("用户要求导出 CURRENCY，筛选 code = CNY")
			.metadata(Map.of(SyntheticMessages.COMPACTED_FROM, "turn-1"))
			.build();

		AiContext enriched = this.resolver.enrich(AiContext.empty(), "", List.of(summary));
		TaskSlots slots = TaskSlots.fromExtra(enriched.getExtra());

		assertThat(slots.defCodes()).as("压缩摘要是系统合成消息，不是用户原话").isEmpty();
		assertThat(slots.conditions()).isEmpty();
	}

	/**
	 * 返修：{@code pageId} 的类型口径与 {@code ContextBuilder#pageSelectedDefs} 统一为
	 * {@code String.valueOf}（此前一侧 {@code instanceof String}、一侧 {@code String.valueOf}，
	 * 非字符串形态会让同一份上下文一边判"已同步"、一边判"未同步"）。
	 */
	@Test
	void nonStringPageIdUsesTheSameValueOfRuleAsContextBuilder() {
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("pageId", new StringBuilder("export"));
		extra.put("selectedDefs", List.of());
		extra.put("selectionSynced", true);
		AiContext workspace = AiContext.of("export", "EXPORT", "SELECT_DEFS", TASK_ID, extra);

		TaskSlots slots = this.withTasks.resolve(workspace, null, List.of());

		assertThat(slots.defCodes()).as("与 ContextBuilder 同一 String.valueOf 口径 ⇒ 已同步空选择").isEmpty();
	}

	// ── helpers ─────────────────────────────────────────────────────────────

	private static AiContext createTaskContext(long taskId, String... defCodes) {
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put(TaskSlotResolver.CREATE_TASK_DEF_CODES_KEY, List.of(defCodes));
		extra.put(TaskSlotResolver.CREATE_TASK_TASK_ID_KEY, taskId);
		return AiContext.of("export", "EXPORT", "SELECT_DEFS", taskId, extra);
	}

	private static Task task(String defCode, String conditionJson) {
		Task task = new Task();
		task.setId(TASK_ID);
		task.setType(Task.TaskType.EXPORT);
		task.setCurrentStep("QUERY_COND");
		task.setStatus(Task.TaskStatus.ACTIVE);
		TaskItem item = new TaskItem();
		item.setTaskId(TASK_ID);
		item.setDefCode(defCode);
		item.setConditionJson(conditionJson);
		task.getItems().add(item);
		return task;
	}

	private static DefinitionCatalog catalog() {
		return () -> List.of(
				new SlotDefinition("CURRENCY", "货币", List.of(
						new SlotDefinition.SlotField("code", "货币代码", true, List.of("CNY", "USD", "JPY")),
						new SlotDefinition.SlotField("name", "货币名称", false, List.of()))),
				new SlotDefinition("ROLE_DICT", "角色字典", List.of(
						new SlotDefinition.SlotField("code", "角色代码", true, List.of("ADMIN", "USER")))),
				new SlotDefinition("SYS_PARAM", "系统参数", List.of(
						new SlotDefinition.SlotField("paramKey", "参数键", true, List.of()),
						new SlotDefinition.SlotField("paramValue", "参数值", false, List.of()))),
				new SlotDefinition("DUP_A", "重复字典", List.of(
						new SlotDefinition.SlotField("code", "代码", true, List.of()))),
				new SlotDefinition("DUP_B", "重复字典", List.of(
						new SlotDefinition.SlotField("code", "代码", true, List.of()))));
	}
}
