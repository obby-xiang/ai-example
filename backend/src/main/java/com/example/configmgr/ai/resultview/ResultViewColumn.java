package com.example.configmgr.ai.resultview;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 列定义（设计稿 §3.4）：<b>只描述不携带值</b>。
 *
 * <p>{@code label} 按 §4-规则 9 只接受 i18n key（{@code i18n:} 前缀）或业务字段码；
 * 缺省 = {@code key} 原样展示（业务字段码本就不翻译）。
 *
 * @param key     数据文档里的字段名
 * @param type    封闭枚举（{@link ResultViewColumnType}）
 * @param label   列头文案（i18n key 或字段码；可空）
 * @param format  语义格式（{@code number:2} / {@code date} …；可空，禁 pattern 串）
 * @param width   相对权重 1..12（可空）
 * @param align   {@code left|right|center}（可空 = 按 type 推导）
 * @param options {@code type=enum|badge} 的取值表（可空）
 * @param hidden  保留列定义但不参与渲染（可空 = false）
 */
public record ResultViewColumn(String key, ResultViewColumnType type, String label, String format,
		Integer width, String align, List<Map<String, Object>> options, Boolean hidden) {

	public static ResultViewColumn of(String key, ResultViewColumnType type, String label, Integer width) {
		return new ResultViewColumn(key, type, label, null, width, null, null, null);
	}

	Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("key", this.key);
		map.put("type", this.type == null ? null : this.type.wire());
		if (this.label != null) {
			map.put("label", this.label);
		}
		if (this.format != null) {
			map.put("format", this.format);
		}
		if (this.width != null) {
			map.put("width", this.width);
		}
		if (this.align != null) {
			map.put("align", this.align);
		}
		if (this.options != null && !this.options.isEmpty()) {
			map.put("options", List.copyOf(this.options));
		}
		if (this.hidden != null) {
			map.put("hidden", this.hidden);
		}
		return map;
	}
}
