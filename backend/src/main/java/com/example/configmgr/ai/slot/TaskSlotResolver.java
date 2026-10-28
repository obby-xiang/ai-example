package com.example.configmgr.ai.slot;

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

		List<String> authoritativeDefCodes = authoritativeDefCodes(ctx);
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

		List<String> fallbackDefCodes = authoritativeDefCodes.isEmpty() ? historyDefCodes : authoritativeDefCodes;
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
				if (message instanceof UserMessage user && user.getText() != null) {
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

	/** 工作区实时勾选优先于任务已存条目（与 ContextBuilder 同一口径）。 */
	private List<String> authoritativeDefCodes(AiContext context) {
		Map<String, Object> extra = context.getExtra();
		if (extra != null && extra.get("pageId") instanceof String pageId
				&& ContextBuilder.SELECTION_PAGE_IDS.contains(pageId)
				&& extra.get("selectedDefs") instanceof List<?> selected) {
			List<String> codes = new ArrayList<>();
			for (Object element : selected) {
				if (element != null && !String.valueOf(element).isBlank()) {
					codes.add(String.valueOf(element));
				}
			}
			return codes;
		}
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
