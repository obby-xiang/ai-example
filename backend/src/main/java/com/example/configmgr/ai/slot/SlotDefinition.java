package com.example.configmgr.ai.slot;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Optional;

/**
 * 定义目录的一行（配置定义及其字段的<b>只读快照</b>）：解析器只依赖这个形状，
 * 不依赖 JPA 实体，因此可以脱离 Spring 直接断言（见 {@code TaskSlotResolverTest}）。
 *
 * @param code   配置定义编码（权威值，比较一律大小写安全）
 * @param name   配置定义中文名（可能是双字词，匹配时按子串，长度 ≥2 才参与）
 * @param fields 字段快照（含 {@code isKey} 与枚举可选值）
 */
public record SlotDefinition(@JsonProperty("code") String code, @JsonProperty("name") String name,
		@JsonProperty("fields") List<SlotField> fields) {

	public SlotDefinition {
		fields = fields == null ? List.of() : List.copyOf(fields);
	}

	/** 主键字段（{@code is_key}）：恰好一个时才允许用于"CONFIG_CODE+CONFIG_VALUE"这类唯一主键缩写。 */
	public List<SlotField> keyFields() {
		return fields.stream().filter(SlotField::isKey).toList();
	}

	public Optional<SlotField> keyField() {
		List<SlotField> keys = keyFields();
		return keys.size() == 1 ? Optional.of(keys.getFirst()) : Optional.empty();
	}

	/**
	 * 字段快照。
	 *
	 * @param code         字段编码
	 * @param label        字段标签（中文名，按子串匹配）
	 * @param key          是否主键字段（{@code config_fields.is_key}）
	 * @param optionValues 枚举可选值（空 = 规则层无法枚举）
	 */
	public record SlotField(@JsonProperty("code") String code, @JsonProperty("label") String label,
			@JsonProperty("key") boolean isKey, @JsonProperty("optionValues") List<String> optionValues) {

		public SlotField {
			optionValues = optionValues == null ? List.of() : List.copyOf(optionValues);
		}

		@JsonIgnore
		public boolean key() {
			return isKey();
		}
	}
}
