package com.example.configmgr.ai.form;

import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.service.DefinitionService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 选项数据源（G-D 原语④ {@code optionsFrom.sourceId} 的<b>唯一开放面</b>）。
 *
 * <p><b>闭集</b>：只有本包内注册的实现可被模型引用；模型给 URL / 脚本 / 任意标识一律在
 * schema 闸门判 {@code OPTIONS_SOURCE_UNKNOWN}（{@link GenerativeFormRules#OPTION_SOURCE_IDS}）。
 * 这是"远程校验器开放注册面"被明确排除后的最小落地：<b>一个</b>内置数据源。
 */
public interface FormOptionSource {

	/** 数据源 id（必须出现在 {@link GenerativeFormRules#OPTION_SOURCE_IDS} 中）。 */
	String id();

	/**
	 * 按父值解析选项。
	 *
	 * @param parentValue 父字段的当前值（本数据源里是<b>配置定义编码</b>）
	 * @return 选项列表（{@code value/label}）；父值不可解析时返回空列表
	 */
	List<Map<String, String>> options(String parentValue);

	/**
	 * 配置定义字段选项源（{@code definition_fields}）：父值 = 配置定义编码，
	 * 选项 = 该定义的字段（value=字段编码，label=字段名）。
	 *
	 * <p>用途示例：模型先让用户选"哪个配置定义"，再让用户从"该定义的字段"里多选。
	 * 数据来自既有 {@link DefinitionService}，不新增任何外部访问面。
	 */
	@Component
	class DefinitionFieldOptionSource implements FormOptionSource {

		public static final String ID = "definition_fields";

		private final DefinitionService definitionService;

		public DefinitionFieldOptionSource(DefinitionService definitionService) {
			this.definitionService = definitionService;
		}

		@Override
		public String id() {
			return ID;
		}

		@Override
		public List<Map<String, String>> options(String parentValue) {
			List<Map<String, String>> out = new ArrayList<>();
			if (parentValue == null || parentValue.isBlank()) {
				return out;
			}
			ConfigDefinition definition;
			try {
				definition = this.definitionService.findByCode(parentValue);
			}
			catch (RuntimeException ex) {
				return out;
			}
			if (definition == null || definition.getFields() == null) {
				return out;
			}
			for (var field : definition.getFields()) {
				Map<String, String> option = new LinkedHashMap<>();
				option.put("value", field.getCode());
				option.put("label", field.getLabel() == null || field.getLabel().isBlank()
						? field.getCode() : field.getLabel());
				out.add(option);
			}
			return out;
		}
	}

}
