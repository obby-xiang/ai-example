package com.example.configmgr.ai.slot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务槽位（Rasa Forms 式 <b>requested_slot / 缺失槽位</b> 的最小实现，见
 * {@code docs/research/修复-AI多步工作流与动态披露-2026-10-28.md} §8）。
 *
 * <p>只表达三件事：任务类型、已确定配置项、已确定筛选条件。<b>空集合 = 缺失</b>，
 * 不表达"用户想选但还没选"之类的中间态 —— 调用方据此判断表单是否还有真实缺口。
 *
 * <p><b>为什么不引入表单框架</b>：这里的用途只有两个 ——
 * <ol>
 * <li>作为 {@code AiContext.extra} 的白名单字段（{@link #EXTRA_KEY}）随快照传递，
 * 由 {@link com.example.configmgr.ai.tool.ContextBuilder} 以数据形式展示给模型；</li>
 * <li>作为 {@code GenerativeFormGuard} 下发前的放行判据：槽位已确定的收集表单没有缺口，
 * 一律拒绝下发（零副作用），把模型引回 {@code select_definitions} / {@code set_condition}。</li>
 * </ol>
 *
 * <p><b>服务端字段</b>：{@link #EXTRA_KEY} 只在服务端解析后写入，客户端的同名 extra 会被
 * {@code ContextFieldGate} 的白名单丢掉（见 {@code ContextBuilder#SERVER_EXTRA_KEYS}），
 * 因此客户端无法用伪造槽位压掉表单。
 */
public record TaskSlots(String taskType, List<String> defCodes, List<ConditionSlot> conditions) {

	/** {@code AiContext.extra} 里的键名（服务端写入，不来自客户端）。 */
	public static final String EXTRA_KEY = "taskSlots";

	public TaskSlots {
		taskType = taskType == null || taskType.isBlank() ? null : taskType;
		defCodes = defCodes == null ? List.of() : List.copyOf(defCodes);
		conditions = conditions == null ? List.of() : List.copyOf(conditions);
	}

	public static TaskSlots empty() {
		return new TaskSlots(null, List.of(), List.of());
	}

	public boolean isEmpty() {
		return taskType == null && defCodes.isEmpty() && conditions.isEmpty();
	}

	public boolean hasDefCodes() {
		return !defCodes.isEmpty();
	}

	/**
	 * 条件是否<b>覆盖了全部已确定配置项</b>：只有这样才能说"条件槽位已齐"。
	 * 单个配置项有值即成立；多配置项时缺任何一个都不算（避免用一张表单重复收集已知条件）。
	 */
	public boolean conditionsCoverDefCodes() {
		if (conditions.isEmpty()) {
			return false;
		}
		if (defCodes.isEmpty()) {
			return false;
		}
		for (String defCode : defCodes) {
			boolean covered = conditions.stream().anyMatch(c -> defCode.equals(c.defCode()));
			if (!covered) {
				return false;
			}
		}
		return true;
	}

	/** 快照/上下文里的形态（只写有值的键，读侧见 {@link #fromValue(Object)}）。 */
	public Map<String, Object> toMap() {
		Map<String, Object> out = new LinkedHashMap<>();
		if (taskType != null) {
			out.put("taskType", taskType);
		}
		if (!defCodes.isEmpty()) {
			out.put("defCodes", defCodes);
		}
		if (!conditions.isEmpty()) {
			out.put("conditions", conditions.stream().map(ConditionSlot::toMap).toList());
		}
		return out;
	}

	/** 从 {@code AiContext.extra} 取槽位；缺失/形状不符一律当"没有槽位"（fail-open：表单照常可用）。 */
	public static TaskSlots fromExtra(Map<String, Object> extra) {
		return extra == null ? empty() : fromValue(extra.get(EXTRA_KEY));
	}

	/** 从快照/JSON 形态还原槽位（容忍缺字段与未知字段）。 */
	public static TaskSlots fromValue(Object raw) {
		if (!(raw instanceof Map<?, ?> map)) {
			return empty();
		}
		String taskType = text(map.get("taskType"));
		List<String> defCodes = new ArrayList<>();
		if (map.get("defCodes") instanceof List<?> list) {
			for (Object element : list) {
				String code = text(element);
				if (code != null && !defCodes.contains(code)) {
					defCodes.add(code);
				}
			}
		}
		List<ConditionSlot> conditions = new ArrayList<>();
		if (map.get("conditions") instanceof List<?> list) {
			for (Object element : list) {
				ConditionSlot slot = ConditionSlot.fromValue(element);
				if (slot != null && !conditions.contains(slot)) {
					conditions.add(slot);
				}
			}
		}
		return new TaskSlots(taskType, defCodes, conditions);
	}

	private static String text(Object raw) {
		if (raw == null) {
			return null;
		}
		String value = String.valueOf(raw).trim();
		return value.isEmpty() ? null : value;
	}

	/**
	 * 单个等值筛选条件（当前只承载 {@code EQ}：范围/IN/比较运算不在"槽位"的最小模型里，
	 * 保持缺失比猜一个更保守）。
	 *
	 * @param defCode   条件所属配置定义编码
	 * @param fieldCode 字段编码（该定义的字段）
	 * @param value     等值
	 */
	public record ConditionSlot(String defCode, String fieldCode, String value) {

		public ConditionSlot {
			defCode = defCode == null || defCode.isBlank() ? null : defCode;
			fieldCode = fieldCode == null || fieldCode.isBlank() ? null : fieldCode;
			value = value == null || value.isBlank() ? null : value;
		}

		public boolean isComplete() {
			return defCode != null && fieldCode != null && value != null;
		}

		public Map<String, Object> toMap() {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("defCode", defCode);
			out.put("fieldCode", fieldCode);
			out.put("value", value);
			return out;
		}

		static ConditionSlot fromValue(Object raw) {
			if (!(raw instanceof Map<?, ?> map)) {
				return null;
			}
			ConditionSlot slot = new ConditionSlot(text(map.get("defCode")), text(map.get("fieldCode")),
					text(map.get("value")));
			return slot.isComplete() ? slot : null;
		}
	}
}
