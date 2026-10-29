package com.example.configmgr.ai.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 表单用途（{@code purpose}）：宿主按它判断"这张表是来补哪个缺口的"，绝不按标题猜。
 *
 * <p>缺省 = {@link GenerativeFormRules#DEFAULT_PURPOSE}（既有调用不带该字段，行为不变）；
 * 非法值走 schema 白名单拒绝（表单不下发）。
 */
class GenerativeFormPurposeRulesTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private JsonNode args(String purpose) {
		String purposeField = purpose == null ? "" : "\"purpose\":\"" + purpose + "\",";
		// 2026-10-29：TASK_TYPE 的形状固定为单个 enum 单选（EXPORT/IMPORT），其余用途用单个文本字段
		String fields = GenerativeFormRules.PURPOSE_TASK_TYPE.equals(purpose)
				? "[{\"key\":\"taskType\",\"label\":\"任务类型\",\"type\":\"enum\",\"options\":["
						+ "{\"value\":\"EXPORT\",\"label\":\"导出\"},{\"value\":\"IMPORT\",\"label\":\"导入\"}]}]"
				: "[{\"key\":\"choice\",\"label\":\"选择\",\"type\":\"text\"}]";
		String json = "{\"form\":{" + purposeField + "\"scenario\":\"CLARIFY\",\"fields\":" + fields + "}}";
		try {
			return this.mapper.readTree(json);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Test
	void purposeIsOptionalAndAcceptsTheWhitelist() {
		assertThat(GenerativeFormRules.argumentIssues(args(null)))
			.as("缺省 purpose 必须与既有调用兼容")
			.isEmpty();
		assertThat(GenerativeFormRules.purposeOf(args(null).get("form")))
			.isEqualTo(GenerativeFormRules.DEFAULT_PURPOSE);

		for (String purpose : GenerativeFormRules.FORM_PURPOSES) {
			assertThat(GenerativeFormRules.argumentIssues(args(purpose)))
				.as("白名单用途必须可用：" + purpose)
				.isEmpty();
		}
	}

	@Test
	void unknownPurposeIsRejectedBySchemaWhitelist() {
		List<FormIssue> issues = GenerativeFormRules.argumentIssues(args("SELECT"));

		assertThat(issues).isNotEmpty();
		assertThat(issues).anySatisfy(issue -> {
			assertThat(issue.code()).isEqualTo(FormIssueCodes.FORM_PURPOSE_INVALID);
			assertThat(issue.message()).contains("purpose").contains("GENERAL_CLARIFY");
		});
	}
}
