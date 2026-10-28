package com.example.configmgr.ai.slot;

import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.service.DefinitionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 生产侧目录：{@code DefinitionService#findAll()} → {@link SlotDefinition} 快照。
 *
 * <p>每次请求现读（定义数量是"字典规模"，不是数据行规模）；解析器只看到不可变快照，
 * 因此单测不需要 JPA。{@code options_json} 解析失败按"无法枚举"处理（空列表）——
 * 这只影响"缩写值是否在枚举内"的保守判定，不影响选择。
 */
@Slf4j
@Component
public class DefinitionSlotCatalog implements DefinitionCatalog {

	private final DefinitionService definitionService;

	private final ObjectMapper objectMapper;

	public DefinitionSlotCatalog(DefinitionService definitionService, ObjectMapper objectMapper) {
		this.definitionService = definitionService;
		this.objectMapper = objectMapper;
	}

	@Override
	public List<SlotDefinition> list() {
		List<ConfigDefinition> definitions = this.definitionService.findAll();
		if (definitions == null || definitions.isEmpty()) {
			return List.of();
		}
		List<SlotDefinition> out = new ArrayList<>(definitions.size());
		for (ConfigDefinition definition : definitions) {
			out.add(toSlot(definition));
		}
		return out;
	}

	private SlotDefinition toSlot(ConfigDefinition definition) {
		List<SlotDefinition.SlotField> fields = new ArrayList<>();
		if (definition.getFields() != null) {
			for (ConfigField field : definition.getFields()) {
				fields.add(new SlotDefinition.SlotField(field.getCode(), field.getLabel(), field.isKey(),
						optionValues(field.getOptionsJson())));
			}
		}
		return new SlotDefinition(definition.getCode(), definition.getName(), fields);
	}

	private List<String> optionValues(String optionsJson) {
		if (optionsJson == null || optionsJson.isBlank()) {
			return List.of();
		}
		try {
			JsonNode root = this.objectMapper.readTree(optionsJson);
			if (!root.isArray()) {
				return List.of();
			}
			List<String> values = new ArrayList<>();
			for (JsonNode option : root) {
				String value = option.path("value").asText(null);
				if (value != null && !value.isBlank()) {
					values.add(value);
				}
			}
			return values;
		}
		catch (Exception ex) {
			log.debug("字段 options_json 解析失败，按无法枚举处理：{}", ex.getMessage());
			return List.of();
		}
	}
}
