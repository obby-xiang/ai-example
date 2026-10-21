package com.example.configmgr.ai.tool;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-1 统一信封的单元用例：字段面、目标资源提取、重试表、以及"先裁剪 message 再组装"的裁剪顺序。
 */
class ToolResultEnvelopeTest {

	private final AiProperties properties = new AiProperties();

	@Test
	void okEnvelopeCarriesFixedFieldSetAndExtractsTarget() throws Exception {
		ToolResultEnvelope envelope = ToolResultEnvelope.ok("导入作业已启动（作业 #4242）",
				"{\"taskId\":7,\"defCode\":\"CURRENCY\",\"step\":\"EXPORT\",\"page\":\"export\",\"ignored\":1}");

		assertThat(envelope.ok()).isTrue();
		assertThat(envelope.outcome()).isEqualTo(ToolOutcome.OK.wire());
		assertThat(envelope.code()).isNull();
		assertThat(envelope.retryable()).isFalse();
		assertThat(envelope.evidence()).isNull();
		assertThat(envelope.target()).containsExactly(Map.entry("taskId", 7),
				Map.entry("defCode", "CURRENCY"), Map.entry("step", "EXPORT"), Map.entry("page", "export"));

		String json = envelope.toJson();
		// 模型侧固定键面（null 也要在，不能让模型误读成"字段不存在"）
		assertThat(json).contains("\"ok\":true").contains("\"code\":null").contains("\"outcome\":\"ok\"")
				.contains("\"retryable\":false").contains("\"evidence\":null");

		ToolResultEnvelope parsed = ToolResultEnvelope.fromJson(json);
		assertThat(parsed).isEqualTo(envelope);
	}

	@Test
	void targetIsNullWhenArgumentsHaveNoKnownKeysOrAreInvalid() {
		assertThat(ToolResultEnvelope.targetOf(null)).isNull();
		assertThat(ToolResultEnvelope.targetOf("")).isNull();
		assertThat(ToolResultEnvelope.targetOf("不是 JSON")).isNull();
		assertThat(ToolResultEnvelope.targetOf("[1,2]")).isNull();
		assertThat(ToolResultEnvelope.targetOf("{\"level\":\"GLOBAL\"}")).isNull();
		assertThat(ToolResultEnvelope.targetOf("{\"taskId\":7}")).isEqualTo(Map.of("taskId", 7));
	}

	@Test
	void retryableFollowsTheFailureCodeTable() {
		assertThat(ToolResultEnvelope.of(ToolOutcome.OK, null, "", "{}").retryable()).isFalse();
		assertThat(ToolResultEnvelope.of(ToolOutcome.UNKNOWN, null, "", "{}").retryable()).isFalse();
		assertThat(ToolResultEnvelope.failed("PRECHECK_NOT_PASSED", "", "{}").retryable()).isTrue();
		assertThat(ToolResultEnvelope.failed("PRECHECK_STALE", "", "{}").retryable()).isTrue();
		assertThat(ToolResultEnvelope.failed("JOB_ALREADY_RUNNING", "", "{}").retryable()).isFalse();
		assertThat(ToolResultEnvelope.failed("SCOPE_NOT_DISCLOSED", "", "{}").retryable()).isFalse();
		assertThat(ToolResultEnvelope.failed("TASK_FINALIZED", "", "{}").retryable()).isFalse();
		assertThat(ToolResultEnvelope.failed("TOOL_RESULT_MISSING", "", "{}").retryable()).isFalse();
		assertThat(ToolResultEnvelope.failed("SOMETHING_NEW", "", "{}").retryable()).isFalse();
		assertThat(ToolResultEnvelope.rejected(null, "", "{}").retryable()).isFalse();
		assertThat(ToolResultEnvelope.blocked("SCOPE_NOT_DISCLOSED", "", "{}").retryable()).isFalse();
		// 批③a 扩写（不是放宽）：同一结局下新码可重试、旧码仍不可重试
		assertThat(ToolResultEnvelope.blocked(ToolOutcome.CODE_RESOURCE_LOCKED, "", "{}").retryable()).isTrue();
		assertThat(ToolResultEnvelope.blocked(ToolOutcome.CODE_TARGET_INVALID, "", "{}").retryable()).isFalse();
		assertThat(ToolResultEnvelope.timeout("CONFIRM_TIMEOUT", "", "{}").retryable()).isFalse();
		assertThat(ToolResultEnvelope.cancelled("RUN_CANCELLED", "", "{}").retryable()).isFalse();
	}

	@Test
	void modelJsonTruncatesMessageBeforeAssemblingSoJsonStaysValid() throws Exception {
		properties.getToolResult().setMaxRows(2);
		properties.getToolResult().setMaxChars(0);
		ToolResultLimiter limiter = new ToolResultLimiter(properties);
		String longMessage = "- 第 1 行\n- 第 2 行\n- 第 3 行\n- 第 4 行";

		String json = ToolResultEnvelope.of(ToolOutcome.OK, null, longMessage, "{\"taskId\":7}")
			.toModelJson(limiter);

		// JSON 本身完好（不是把整串 JSON 按字符截断）
		@SuppressWarnings("unchecked")
		Map<String, Object> parsed = new ObjectMapper().readValue(json, Map.class);
		assertThat(parsed).containsEntry("outcome", "ok");
		assertThat((Map<String, Object>) parsed.get("target")).containsEntry("taskId", 7);
		assertThat(String.valueOf(parsed.get("message")))
			.contains(ToolResultLimiter.TRUNCATION_MARKER).contains("原 4 行").doesNotContain("第 4 行");
	}

}
