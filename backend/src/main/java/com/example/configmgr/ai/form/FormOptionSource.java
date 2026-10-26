package com.example.configmgr.ai.form;

import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.service.DefinitionService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 选项数据源（G-D 原语④ {@code optionsFrom.sourceId} 的<b>唯一开放面</b>）。
 *
 * <p><b>闭集</b>：只有本包内注册的实现可被模型引用；模型给 URL / 脚本 / 任意标识一律在
 * schema 闸门判 {@code OPTIONS_SOURCE_UNKNOWN}（{@link GenerativeFormRules#OPTION_SOURCE_IDS}）。
 * 这是"远程校验器开放注册面"被明确排除后的最小落地：<b>两个</b>内置数据源
 * （{@code definition_fields} 定义字段 / {@code definitions} 定义枚举），都只读既有
 * {@link DefinitionService}，不新增任何外部访问面。
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

	/**
	 * 配置定义枚举选项源（{@code definitions}）：父值 = 配置定义层级
	 * （{@code GLOBAL/REGION/PROJECT}，大小写不敏感），选项 = 该层级的配置定义
	 * （value=定义编码，label=定义名称）。
	 *
	 * <p>用途：先让用户选"哪个层级的哪个配置项"（本批此前只能靠模型硬编码静态 options，
	 * 数据一变就失真），再用 {@link DefinitionFieldOptionSource} 展开该定义的字段。
	 *
	 * <p><b>空值不是通配符</b>：父值为空、空白或不是已知层级时返回<b>空列表</b>，
	 * 绝不回退成"列出全部定义"。清空是用户在上层字段上的显式动作，
	 * 不允许空串在数据源里被当成"未筛选"的隐式语义，也不与非法空值混用。
	 *
	 * <p>上限 = {@link GenerativeFormRules#MAX_OPTIONS}（与表单内联选项同一口径），
	 * 顺序按 {@code sortOrder, code} 稳定输出，避免下拉项每次刷新换序。
	 */
	@Component
	class DefinitionOptionSource implements FormOptionSource {

		public static final String ID = "definitions";

		private final DefinitionService definitionService;

		public DefinitionOptionSource(DefinitionService definitionService) {
			this.definitionService = definitionService;
		}

		@Override
		public String id() {
			return ID;
		}

		@Override
		public List<Map<String, String>> options(String parentValue) {
			List<Map<String, String>> out = new ArrayList<>();
			ConfigDefinition.ConfigLevel level = levelOf(parentValue);
			if (level == null) {
				return out;
			}
			List<ConfigDefinition> definitions;
			try {
				definitions = this.definitionService.findByLevel(level);
			}
			catch (RuntimeException ex) {
				return out;
			}
			if (definitions == null) {
				return out;
			}
			List<ConfigDefinition> ordered = new ArrayList<>();
			for (ConfigDefinition definition : definitions) {
				if (definition != null && definition.getCode() != null && !definition.getCode().isBlank()) {
					ordered.add(definition);
				}
			}
			ordered.sort(Comparator.comparingInt(ConfigDefinition::getSortOrder)
				.thenComparing(ConfigDefinition::getCode));
			for (ConfigDefinition definition : ordered) {
				if (out.size() >= GenerativeFormRules.MAX_OPTIONS) {
					break;
				}
				Map<String, String> option = new LinkedHashMap<>();
				option.put("value", definition.getCode());
				option.put("label", definition.getName() == null || definition.getName().isBlank()
						? definition.getCode() : definition.getName());
				out.add(option);
			}
			return out;
		}

		/** 父值 → 层级；空/未知一律 null（调用方据此返回空列表，不做"全部"回落）。 */
		private static ConfigDefinition.ConfigLevel levelOf(String parentValue) {
			if (parentValue == null || parentValue.isBlank()) {
				return null;
			}
			try {
				return ConfigDefinition.ConfigLevel.valueOf(parentValue.trim().toUpperCase(Locale.ROOT));
			}
			catch (IllegalArgumentException ex) {
				return null;
			}
		}
	}

}
