package com.example.configmgr.ai.form;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.common.CodedIllegalStateException;
import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.service.DefinitionService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code definitions} 选项源（配置定义枚举，供"先选一个配置项"）的用例。
 *
 * <p>锁四件事：①父值 = 层级（大小写/首尾空白归一），只列该层级的定义，顺序稳定
 * （sortOrder, code）；②空 / 未知父值返回<b>空列表</b>，绝不回退成"列出全部定义"——
 * 清空是用户在上层字段上的显式动作，不能靠空串在数据源里被当成"未筛选"；
 * ③输出有上限（与表单内联选项同一口径 {@link GenerativeFormRules#MAX_OPTIONS}），
 * 且不产出空 value / 空 label 的选项（非法空值不与显式值混用）；
 * ④读取失败抛出带码异常并只记录安全元数据，不伪装成真实空选项。
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
	void sourceLookupFailureIsUnavailableAndLogsOnlySafeMetadata() {
		IllegalStateException failure = new IllegalStateException("database secret");
		when(this.definitions.findByLevel(ConfigDefinition.ConfigLevel.GLOBAL)).thenThrow(failure);
		Logger logger = (Logger) LoggerFactory.getLogger(FormOptionSource.DefinitionOptionSource.class);
		ListAppender<ILoggingEvent> logs = new ListAppender<>();
		logs.start();
		logger.addAppender(logs);
		try {
			assertThatThrownBy(() -> this.source.options("GLOBAL"))
				.isInstanceOf(CodedIllegalStateException.class)
				.hasFieldOrPropertyWithValue("code", ErrorCodes.FORM_OPTION_SOURCE_UNAVAILABLE)
				.hasCause(failure);
			assertThat(logs.list).hasSize(1);
			ILoggingEvent event = logs.list.getFirst();
			assertThat(event.getLevel()).isEqualTo(Level.WARN);
			assertThat(event.getFormattedMessage())
				.contains("sourceId=definitions", "parentValueLength=6")
				.doesNotContain("database secret");
			assertThat(event.getThrowableProxy()).isNotNull();
		}
		finally {
			logger.detachAppender(logs);
			logs.stop();
		}
	}

	private static ConfigDefinition definition(String code, String name, int sortOrder) {
		ConfigDefinition definition = new ConfigDefinition();
		definition.setCode(code);
		definition.setName(name);
		definition.setSortOrder(sortOrder);
		return definition;
	}

}
