package com.example.configmgr.ai.form;

import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.service.DefinitionService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code definitions} 选项源（配置定义枚举，供"先选一个配置项"）的用例。
 *
 * <p>锁三件事：①父值 = 层级（大小写/首尾空白归一），只列该层级的定义，顺序稳定
 * （sortOrder, code）；②空 / 未知父值返回<b>空列表</b>，绝不回退成"列出全部定义"——
 * 清空是用户在上层字段上的显式动作，不能靠空串在数据源里被当成"未筛选"；
 * ③输出有上限（与表单内联选项同一口径 {@link GenerativeFormRules#MAX_OPTIONS}），
 * 且不产出空 value / 空 label 的选项（非法空值不与显式值混用）。
 */
class DefinitionOptionSourceTest {

	private final DefinitionService definitions = mock(DefinitionService.class);

	private final FormOptionSource source = new FormOptionSource.DefinitionOptionSource(this.definitions);

	@Test
	void enumeratesDefinitionsOfTheGivenLevelInStableOrder() {
		when(this.definitions.findByLevel(ConfigDefinition.ConfigLevel.GLOBAL))
			.thenReturn(List.of(definition("CURRENCY", "货币字典", 2), definition("DOC_TYPE", "单据类型", 1)));

		// 层级大小写不敏感（与业务侧 level 解析同口径），顺序按 sortOrder 而不是数据库返回顺序
		assertThat(this.source.options("global")).containsExactly(
				Map.of("value", "DOC_TYPE", "label", "单据类型"),
				Map.of("value", "CURRENCY", "label", "货币字典"));
		assertThat(this.source.options("  Global ")).hasSize(2);
	}

	@Test
	void missingNameFallsBackToCodeAndBlankCodesAreSkipped() {
		when(this.definitions.findByLevel(ConfigDefinition.ConfigLevel.REGION))
			.thenReturn(List.of(definition("TAX_RATE", null, 1), definition("  ", "空编码", 2),
					definition(null, "无编码", 3)));

		assertThat(this.source.options("REGION"))
			.containsExactly(Map.of("value", "TAX_RATE", "label", "TAX_RATE"));
	}

	@Test
	void blankOrUnknownParentValueIsEmptyNotEverything() {
		// 空值不是通配符：null/空白/未知层级一律空列表（nil 不是"全部"）
		assertThat(this.source.options(null)).isEmpty();
		assertThat(this.source.options("")).isEmpty();
		assertThat(this.source.options("   ")).isEmpty();
		assertThat(this.source.options("ALL")).isEmpty();
		assertThat(this.source.options("GLOBAL;DROP")).isEmpty();
	}

	@Test
	void optionsAreCappedAtTheFormOptionLimit() {
		List<ConfigDefinition> many = new ArrayList<>();
		for (int i = 0; i < GenerativeFormRules.MAX_OPTIONS + 10; i++) {
			many.add(definition(String.format("D%02d", i), "定义" + i, i));
		}
		when(this.definitions.findByLevel(ConfigDefinition.ConfigLevel.PROJECT)).thenReturn(many);

		List<Map<String, String>> options = this.source.options("PROJECT");

		assertThat(options).hasSize(GenerativeFormRules.MAX_OPTIONS);
		assertThat(options.get(0)).containsEntry("value", "D00");
		assertThat(options.get(GenerativeFormRules.MAX_OPTIONS - 1)).containsEntry("value",
				String.format("D%02d", GenerativeFormRules.MAX_OPTIONS - 1));
	}

	@Test
	void sourceLookupFailureDegradesToEmptyOptions() {
		when(this.definitions.findByLevel(ConfigDefinition.ConfigLevel.GLOBAL))
			.thenThrow(new IllegalStateException("db down"));

		// 读库失败 = 选项为空（前端重试），不抛出、不回落任何外部来源
		assertThat(this.source.options("GLOBAL")).isEmpty();
	}

	private static ConfigDefinition definition(String code, String name, int sortOrder) {
		ConfigDefinition definition = new ConfigDefinition();
		definition.setCode(code);
		definition.setName(name);
		definition.setSortOrder(sortOrder);
		return definition;
	}

}
