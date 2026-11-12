package com.example.configmgr.ai.slot;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.List;
import java.util.Map;

/**
 * 任务槽位（Rasa Forms 式 <b>requested_slot / 缺失槽位</b> 的最小实现，见
 * {@code docs/research/修复-AI多步工作流与动态披露-2026-10-28.md} §8）。
 *
 * <p>表达任务类型、已确定配置项、已确定筛选条件及读取可用性。正常解析时<b>空集合 = 缺失</b>，
 * 不表达"用户想选但还没选"之类的中间态 —— 调用方据此判断表单是否还有真实缺口。
 * {@code unavailable=true} 只表示任务读取失败，此时配置项与条件均为未知而非缺失，不得据此重新收集；
 * 未提供该标记时按可用处理，任务不存在不设置该标记。
 *
 * <p><b>条件三态（Task 1 第 1 项）</b>：{@code conditions} 仍是模型可读的 EQ 槽位；另有两个
 * 只服务放行判定的集合：
 * <ul>
 * <li>{@code conditionDefCodes}：已有条件事实的配置项（含 IN/GT 等非 EQ 算子与 {@code scopeKeys}，
 * 由 {@link ConditionAssessment} 统一解析）；</li>
 * <li>{@code undecidedDefCodes}：条件<b>未决定/无法解析</b>的配置项；独立 presence=false 表示
 * 未声明逐项三态，读取时按 EQ 条件覆盖判定；键存在且为空数组则表示已逐项核对、没有缺口。</li>
 * </ul>
 * 明确不设（{@code "{}"}）既不在 {@code conditionDefCodes} 也不在 {@code undecidedDefCodes} ——
 * 它已经有答案，不需要再问。
 *
 * <p><b>为什么不引入表单框架</b>：这里的用途只有两个 ——
 * <ol>
 * <li>作为 {@code AiContext.extra} 的白名单字段（{@link #EXTRA_KEY}）随快照传递，
 * 由 {@link com.example.configmgr.ai.tool.ContextBuilder} 以数据形式展示给模型；</li>
 * <li>作为 {@code GenerativeFormGuard} 下发前的放行判据：槽位已确定或读取不可用时，
 * 对应收集表单一律拒绝下发（零副作用），不得把已知值或未知状态当成新缺口。</li>
 * </ol>
 *
 * <p><b>服务端字段</b>：{@link #EXTRA_KEY} 只在服务端解析后写入，客户端的同名 extra 会被
 * {@code ContextFieldGate} 的白名单丢掉（见 {@code ContextBuilder#SERVER_EXTRA_KEYS}），
 * 因此客户端无法用伪造槽位压掉表单。
 */
public record TaskSlots(@JsonProperty("taskType") String taskType, @JsonProperty("defCodes") List<String> defCodes,
		@JsonProperty("conditions") List<ConditionSlot> conditions,
		@JsonProperty("conditionDefCodes") List<String> conditionDefCodes,
		@JsonProperty("undecidedDefCodes") List<String> undecidedDefCodes,
		@JsonProperty("conditionTexts") List<String> conditionTexts,
		@JsonIgnore boolean undecidedDefCodesPresent, @JsonProperty("unavailable") boolean isUnavailable) {

	/** {@code AiContext.extra} 里的键名（服务端写入，不来自客户端）。 */
	public static final String EXTRA_KEY = "taskSlots";

	public TaskSlots {
		taskType = StringUtils.defaultIfBlank(taskType, null);
		defCodes = defCodes == null ? List.of() : List.copyOf(defCodes);
		conditions = conditions == null ? List.of() : List.copyOf(conditions);
		conditionDefCodes = conditionDefCodes == null ? List.of() : List.copyOf(conditionDefCodes);
		undecidedDefCodes = !undecidedDefCodesPresent || undecidedDefCodes == null ? List.of()
				: List.copyOf(undecidedDefCodes);
		conditionTexts = conditionTexts == null ? List.of() : List.copyOf(conditionTexts);
	}

	/**
	 * 轻装配构造器（三参）：未声明逐项条件三态时，放行判定使用 EQ 条件覆盖口径。
	 * 生产路径由 {@link TaskSlotResolver} 提供完整三态。
	 */
	public TaskSlots(String taskType, List<String> defCodes, List<ConditionSlot> conditions) {
		this(taskType, defCodes, conditions,
				conditions == null ? List.of() : conditions.stream().map(ConditionSlot::defCode).distinct().toList(),
				List.of(), List.of(), false, false);
	}

	/** 完整条件三态入口：undecidedDefCodes 非 null 表示已声明逐项核对结果。 */
	public TaskSlots(String taskType, List<String> defCodes, List<ConditionSlot> conditions,
			List<String> conditionDefCodes, List<String> undecidedDefCodes, List<String> conditionTexts) {
		this(taskType, defCodes, conditions, conditionDefCodes, undecidedDefCodes, conditionTexts,
				undecidedDefCodes != null, false);
	}

	public static TaskSlots empty() {
		return new TaskSlots(null, List.of(), List.of());
	}

	public static TaskSlots unavailable(String taskType) {
		return new TaskSlots(taskType, List.of(), List.of(), List.of(), List.of(), List.of(), false, true);
	}

	public boolean isEmpty() {
		return taskType == null && defCodes.isEmpty() && conditions.isEmpty() && !isUnavailable;
	}

	public boolean hasDefCodes() {
		return !defCodes.isEmpty();
	}

	/**
	 * 条件是否<b>覆盖了全部已确定配置项</b>：只有这样才能说"条件槽位已齐"。
	 * 单个配置项有值即成立；多配置项时缺任何一个都不算（避免用一张表单重复收集已知条件）。
	 */
	public boolean hasFullConditionCoverage() {
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

	/**
	 * 逐项核对：是否还有<b>真实的条件缺口</b>（需要收集）。
	 *
	 * <p>三态口径：任一配置项 {@code UNDECIDED}（含无法解析）即缺口；明确不设与已设置都不是缺口。
	 * 禁止"任一配置项有条件就整体放行"——多配置时必须逐项判断。
	 */
	@JsonIgnore
	public boolean isConditionCollectionNeeded() {
		if (isUnavailable || defCodes.isEmpty()) {
			return false;
		}
		if (!this.undecidedDefCodesPresent) {
			// 未声明逐项三态：使用 EQ 覆盖口径，不把未知当全量
			return !hasFullConditionCoverage();
		}
		for (String defCode : defCodes) {
			if (this.undecidedDefCodes.contains(defCode)) {
				return true;
			}
		}
		return false;
	}

	@JsonIgnore
	public boolean needsConditionCollection() {
		return isConditionCollectionNeeded();
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
		if (!conditionDefCodes.isEmpty()) {
			out.put("conditionDefCodes", conditionDefCodes);
		}
		// 空列表也要写：它表示"逐项核对过、没有未决定的"，与"没有三态信息"必须可区分
		if (this.undecidedDefCodesPresent) {
			out.put("undecidedDefCodes", this.undecidedDefCodes);
		}
		if (!conditionTexts.isEmpty()) {
			out.put("conditionTexts", conditionTexts);
		}
		if (isUnavailable) {
			out.put("unavailable", true);
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
		List<String> conditionDefCodes = stringList(map.get("conditionDefCodes"));
		List<String> conditionTexts = stringList(map.get("conditionTexts"));
		// 键缺失 = 未声明逐项三态；键存在（含空列表）= 已给出逐项核对结果
		boolean undecidedDefCodesPresent = map.containsKey("undecidedDefCodes");
		List<String> undecidedDefCodes = undecidedDefCodesPresent ? stringList(map.get("undecidedDefCodes")) : List.of();
		return new TaskSlots(taskType, defCodes, conditions, conditionDefCodes, undecidedDefCodes, conditionTexts,
				undecidedDefCodesPresent,
				map.get("unavailable") instanceof Boolean unavailable && BooleanUtils.isTrue(unavailable));
	}

	private static List<String> stringList(Object raw) {
		List<String> out = new ArrayList<>();
		if (raw instanceof List<?> list) {
			for (Object element : list) {
				String value = text(element);
				if (value != null && !out.contains(value)) {
					out.add(value);
				}
			}
		}
		return out.isEmpty() ? List.of() : out;
	}

	private static String text(Object raw) {
		return StringUtils.trimToNull(Objects.toString(raw, null));
	}

	/**
	 * 单个等值筛选条件（当前只承载 {@code EQ}：范围/IN/比较运算不在"槽位"的最小模型里，
	 * 保持缺失比猜一个更保守）。
	 *
	 * @param defCode   条件所属配置定义编码
	 * @param fieldCode 字段编码（该定义的字段）
	 * @param value     等值
	 */
	public record ConditionSlot(@JsonProperty("defCode") String defCode,
			@JsonProperty("fieldCode") String fieldCode, @JsonProperty("value") String value) {

		public ConditionSlot {
			defCode = StringUtils.defaultIfBlank(defCode, null);
			fieldCode = StringUtils.defaultIfBlank(fieldCode, null);
			value = StringUtils.defaultIfBlank(value, null);
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
