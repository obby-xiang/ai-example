package com.example.configmgr.ai.slot;

import com.example.configmgr.ai.history.SyntheticMessages;
import com.example.configmgr.ai.slot.TaskSlots.ConditionSlot;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 槽位解析器（小型、可测试，不引入新框架）。
 *
 * <p><b>优先级</b>（用户的硬要求）：权威任务/工作区 → 当前用户明确值 → 历史未修改值。
 * 当前文本里明确给出的选择/条件是对前值的<b>替换</b>（"后文明确替换则新值优先"）；
 * 明确取消/否定则清空；无法确定（同名多义、多值歧义）保持缺失，严禁猜测。
 * 多项明确配置是合法的多选（`defCodes` 全部填入），不按"多候选 = 歧义"处理。
 *
 * <p><b>与 {@code ContextBuilder} 同一套两态</b>：页面上报的 {@code selectedDefs} 是<b>显式空列表</b>
 * （用户把勾选全部取消）时它是权威空值，不得回落历史重新勾选；只有页面<b>从未同步</b>
 * （无该键 / 不在拥有选中集的向导页上）才允许按历史回落。
 *
 * <p>解析结果写入 {@code AiContext.extra[taskSlots]}（{@link TaskSlots#EXTRA_KEY}），
 * 随 run 快照传递，并由 {@link ContextBuilder} 展示给模型。
 */
@Slf4j
@Component
public class TaskSlotResolver {

	private final DefinitionCatalog catalog;

	/** 权威任务条目来源（可为 null：纯解析用例不读库）。 */
	private final TaskService taskService;

	private final ObjectMapper objectMapper;

	public TaskSlotResolver(DefinitionCatalog catalog) {
		this(catalog, null, new ObjectMapper());
	}

	@org.springframework.beans.factory.annotation.Autowired
	public TaskSlotResolver(DefinitionCatalog catalog, TaskService taskService, ObjectMapper objectMapper) {
		this.catalog = catalog;
		this.taskService = taskService;
		this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
	}

	/**
	 * 解析本轮槽位。
	 *
	 * @param context            请求上下文（taskType/extra 是权威值来源）
	 * @param currentMessage     本轮用户原文
	 * @param historyUserMessages 历史用户原文（按时间顺序；只看用户说过的话）
	 */
	public TaskSlots resolve(AiContext context, String currentMessage, List<String> historyUserMessages) {
		List<SlotDefinition> definitions = definitions();
		AiContext ctx = context == null ? AiContext.empty() : context;

		// 页面实时勾选的三态（与 ContextBuilder#pageSelectedDefs 同源）：null = 页面从未同步，
		// 空列表 = 页面同步了"什么都没选"（权威空值）。
		List<String> pageDefCodes = pageSelectedDefCodes(ctx);
		List<String> taskDefCodes = taskDefCodes(ctx);
		List<String> authoritativeDefCodes = pageDefCodes != null ? pageDefCodes : taskDefCodes;
		// 权威空值不得回落历史：显式空勾选是"用户此刻什么都没选"，按历史重新勾选等于替用户改选择。
		boolean selectionIsAuthoritative = pageDefCodes != null || !taskDefCodes.isEmpty();
		List<ConditionSlot> authoritativeConditions = authoritativeConditions(ctx);
		String authoritativeTaskType = ctx.getTaskType() == null || ctx.getTaskType().isBlank() ? null
				: ctx.getTaskType();

		List<String> historyDefCodes = List.of();
		List<ConditionSlot> historyConditions = List.of();
		String historyTaskType = null;
		for (String text : historyUserMessages == null ? List.<String>of() : historyUserMessages) {
			TaskSlotParser.Intent intent = TaskSlotParser.parse(text, definitions, historyDefCodes);
			historyDefCodes = applySelection(intent.selection(), intent.defCodes(), historyDefCodes);
			historyConditions = applyConditions(intent.condition(), intent.conditions(), historyConditions);
			if (intent.taskType() != null) {
				historyTaskType = intent.taskType();
			}
		}

		List<String> fallbackDefCodes = selectionIsAuthoritative ? authoritativeDefCodes : historyDefCodes;
		TaskSlotParser.Intent current = TaskSlotParser.parse(currentMessage, definitions, fallbackDefCodes);

		String taskType = authoritativeTaskType != null ? authoritativeTaskType
				: current.taskType() != null ? current.taskType() : historyTaskType;
		List<String> defCodes = switch (current.selection()) {
			case DETERMINED -> current.defCodes();
			case CLEARED, AMBIGUOUS -> List.of();
			case ABSENT -> fallbackDefCodes;
		};
		List<ConditionSlot> conditions = switch (current.condition()) {
			case DETERMINED -> current.conditions();
			case CLEARED, AMBIGUOUS -> List.of();
			case ABSENT -> !authoritativeConditions.isEmpty() ? authoritativeConditions : historyConditions;
		};
		if (defCodes.isEmpty()) {
			conditions = List.of();
		}
		else {
			conditions = conditions.stream().filter(c -> defCodes.contains(c.defCode())).distinct().toList();
		}
		return new TaskSlots(taskType, defCodes, conditions);
	}

	private static List<String> applySelection(TaskSlotParser.SlotState state, List<String> value,
			List<String> previous) {
		return switch (state) {
			case DETERMINED -> value;
			case CLEARED, AMBIGUOUS -> List.of();
			case ABSENT -> previous;
		};
	}

	private static List<ConditionSlot> applyConditions(TaskSlotParser.SlotState state, List<ConditionSlot> value,
			List<ConditionSlot> previous) {
		return switch (state) {
			case DETERMINED -> value;
			case CLEARED, AMBIGUOUS -> List.of();
			case ABSENT -> previous;
		};
	}

	/** 首轮出口：把解析结果放进 extra（空槽位则移除键，避免每轮都渲染"缺失"）。 */
	public AiContext enrich(AiContext context, String currentMessage, List<Message> history) {
		AiContext source = context == null ? AiContext.empty() : context;
		List<String> historyTexts = new ArrayList<>();
		if (history != null) {
			for (Message message : history) {
				// 压缩摘要/步数收尾等合成 user 消息不是用户原话：把它们当输入会把旧选择"复活"
				if (message instanceof UserMessage user && !SyntheticMessages.isSynthetic(message)
						&& user.getText() != null) {
					historyTexts.add(user.getText());
				}
			}
		}
		TaskSlots slots = resolve(source, currentMessage, historyTexts);
		Map<String, Object> extra = source.getExtra() == null ? new LinkedHashMap<>()
				: new LinkedHashMap<>(source.getExtra());
		if (slots.isEmpty()) {
			extra.remove(TaskSlots.EXTRA_KEY);
		}
		else {
			extra.put(TaskSlots.EXTRA_KEY, slots.toMap());
		}
		return AiContext.of(source.getPage(), source.getTaskType(), source.getStep(), source.getTaskId(),
				source.getTaskStatus(), extra);
	}

	// ── 权威值（任务/工作区） ────────────────────────────────────────────────

	private List<SlotDefinition> definitions() {
		try {
			List<SlotDefinition> definitions = this.catalog == null ? List.of() : this.catalog.list();
			return definitions == null ? List.of() : definitions;
		}
		catch (RuntimeException ex) {
			// 目录读失败 = 没有可识别项：槽位保持缺失（表单照常可用），不影响本轮
			log.warn("配置定义目录读取失败，本轮槽位按缺失处理：{}", ex.getMessage());
			return List.of();
		}
	}

	/**
	 * 页面实时勾选（三态，与 {@code ContextBuilder#pageSelectedDefs} 同一口径）。
	 *
	 * @return {@code null} = 页面从未同步（不带 {@code selectedDefs} 键、不在拥有选中集的向导页上，
	 *         或列表为空但任务详情还没应用 —— 未水合的 {@code []} 只表示"不知道"）；
	 *         空列表 = 页面同步了空集合且详情已应用（用户取消了全部勾选，这是权威空值）
	 */
	private List<String> pageSelectedDefCodes(AiContext context) {
		Map<String, Object> extra = context.getExtra();
		if (extra == null) {
			return null;
		}
		// 类型口径与 ContextBuilder#pageSelectedDefs 统一为 String.valueOf（此前 instanceof String
		// 会让同一份上下文在两侧得出不同结论）。
		Object pageId = extra.get("pageId");
		if (pageId == null || !ContextBuilder.SELECTION_PAGE_IDS.contains(String.valueOf(pageId))) {
			return null;
		}
		if (!(extra.get("selectedDefs") instanceof List<?> selected)) {
			return null;
		}
		List<String> codes = new ArrayList<>();
		for (Object element : selected) {
			if (element != null && !String.valueOf(element).isBlank()) {
				codes.add(String.valueOf(element));
			}
		}
		if (codes.isEmpty() && !Boolean.TRUE.equals(extra.get(ContextBuilder.SELECTION_SYNCED_KEY))) {
			// 未水合 / 旧客户端：[] 不代表"用户清空"，按未同步处理（允许回落任务条目/历史）
			return null;
		}
		return codes;
	}

	/** 任务已存条目（页面未同步时的权威来源；没有任务 = 空）。 */
	private List<String> taskDefCodes(AiContext context) {
		Task task = task(context);
		if (task == null || task.getItems() == null) {
			return List.of();
		}
		List<String> codes = new ArrayList<>();
		for (TaskItem item : task.getItems()) {
			if (item.getDefCode() != null && !item.getDefCode().isBlank()) {
				codes.add(item.getDefCode());
			}
		}
		return codes;
	}

	private List<ConditionSlot> authoritativeConditions(AiContext context) {
		Task task = task(context);
		if (task == null || task.getItems() == null) {
			return List.of();
		}
		List<ConditionSlot> out = new ArrayList<>();
		for (TaskItem item : task.getItems()) {
			String json = item.getConditionJson();
			if (json == null || json.isBlank()) {
				continue;
			}
			try {
				JsonNode root = this.objectMapper.readTree(json);
				for (JsonNode field : root.path("fields")) {
					if (!"EQ".equalsIgnoreCase(field.path("operator").asText())) {
						// 范围/IN/比较运算不在最小槽位模型里：不猜，保持缺失
						continue;
					}
					String fieldCode = field.path("fieldCode").asText(null);
					JsonNode value = field.get("value");
					if (fieldCode == null || value == null || value.isNull()) {
						continue;
					}
					out.add(new ConditionSlot(item.getDefCode(), fieldCode, value.asText()));
				}
			}
			catch (RuntimeException | java.io.IOException ex) {
				log.debug("任务条目条件 JSON 解析失败，按无权威条件处理 defCode={}：{}", item.getDefCode(),
						ex.getMessage());
			}
		}
		return out;
	}

	private Task task(AiContext context) {
		if (this.taskService == null || context.getTaskId() == null) {
			return null;
		}
		try {
			return this.taskService.findById(context.getTaskId());
		}
		catch (RuntimeException ex) {
			log.debug("读取任务 #{} 失败，权威槽位按缺失处理：{}", context.getTaskId(), ex.getMessage());
			return null;
		}
	}
}
