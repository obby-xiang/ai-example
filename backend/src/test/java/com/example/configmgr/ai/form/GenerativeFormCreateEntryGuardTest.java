package com.example.configmgr.ai.form;

import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.FrontendToolGuard;
import com.example.configmgr.common.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GenerativeFormCreateEntryGuardTest {

	private final GenerativeFormGuard guard = new GenerativeFormGuard(new ObjectMapper());

	@Test
	void exportEntryRejectsGeneralClarifyWithDefCodes() {
		FrontendToolGuard.Verdict verdict = inspect(generalClarify("defCodes"), entry("export", "EXPORT"));

		assertCreateTaskRequired(verdict);
	}

	@Test
	void importEntryRejectsGeneralClarifyWithDefCodes() {
		FrontendToolGuard.Verdict verdict = inspect(generalClarify("defCodes"), entry("import", "IMPORT"));

		assertCreateTaskRequired(verdict);
	}

	@Test
	void importEntryRejectsGeneralClarifyWithConditions() {
		FrontendToolGuard.Verdict verdict = inspect(generalClarify("conditions"), entry("import", "IMPORT"));

		assertCreateTaskRequired(verdict);
	}

	@Test
	void tasksEntryRejectsSelectDefinitions() {
		String arguments = "{\"form\":{\"purpose\":\"SELECT_DEFINITIONS\",\"scenario\":\"CLARIFY\",\"fields\":["
				+ "{\"key\":\"chosen\",\"label\":\"配置项\",\"type\":\"multi_select\",\"options\":["
				+ "{\"value\":\"CURRENCY\",\"label\":\"货币\"}]}]}}";

		assertCreateTaskRequired(inspect(arguments, entry("tasks", null)));
	}

	@Test
	void c130FirstCreateTaskEntryRejectsGeneralClarify() {
		FrontendToolGuard.Verdict verdict = inspect(generalClarify("defCodes"), entry("tasks", null));

		assertCreateTaskRequired(verdict);
	}

	@Test
	void exportEntryRejectsGeneralClarifyWithDefinitionsSource() {
		String arguments = "{\"form\":{\"purpose\":\"GENERAL_CLARIFY\",\"scenario\":\"CLARIFY\",\"fields\":["
				+ "{\"key\":\"level\",\"label\":\"层级\",\"type\":\"enum\",\"options\":["
				+ "{\"value\":\"GLOBAL\",\"label\":\"全局\"}]},"
				+ "{\"key\":\"chosen\",\"label\":\"配置项\",\"type\":\"multi_select\","
				+ "\"optionsFrom\":{\"dependsOn\":\"level\",\"sourceId\":\"definitions\"}}]}}";

		assertCreateTaskRequired(inspect(arguments, entry("export", "EXPORT")));
	}

	@Test
	void boundTaskAllowsDefinitionCollection() {
		AiContext context = AiContext.of("export", "EXPORT", "SELECT_DEFS", 42L, Map.of());

		FrontendToolGuard.Verdict verdict = inspect(generalClarify("defCodes"), context);

		assertThat(verdict.accepted()).isTrue();
	}

	@Test
	void unrelatedGeneralClarifyIsAllowedWithoutMatchingTitleOrLabel() {
		String arguments = "{\"form\":{\"purpose\":\"GENERAL_CLARIFY\",\"scenario\":\"CLARIFY\","
				+ "\"title\":\"选择配置项和筛选条件\",\"fields\":["
				+ "{\"key\":\"note\",\"label\":\"配置项条件\",\"type\":\"text\"}]}}";

		FrontendToolGuard.Verdict verdict = inspect(arguments, entry("tasks", null));

		assertThat(verdict.accepted()).isTrue();
	}

	private FrontendToolGuard.Verdict inspect(String arguments, AiContext context) {
		return this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME, arguments, context);
	}

	private static AiContext entry(String page, String taskType) {
		return AiContext.of(page, taskType, null, null, Map.of());
	}

	private static String generalClarify(String fieldKey) {
		return "{\"form\":{\"purpose\":\"GENERAL_CLARIFY\",\"scenario\":\"CLARIFY\",\"fields\":["
				+ "{\"key\":\"" + fieldKey + "\",\"label\":\"值\",\"type\":\"text\",\"required\":true}]}}";
	}

	private static void assertCreateTaskRequired(FrontendToolGuard.Verdict verdict) {
		assertThat(verdict.accepted()).isFalse();
		assertThat(verdict.code()).isEqualTo(ErrorCodes.FORM_SLOTS_ALREADY_KNOWN);
		assertThat(verdict.render()).contains("create_task").contains("未向前端下发表单");
	}

}
