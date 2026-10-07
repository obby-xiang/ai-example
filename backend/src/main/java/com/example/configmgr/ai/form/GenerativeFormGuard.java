package com.example.configmgr.ai.form;

import com.example.configmgr.ai.tool.FrontendToolGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code generative_form} 的复核器（DC-15 安全闸）：把模型给的表单 schema 与前端回灌的填写值，
 * 交给 {@link GenerativeFormRules} 的白名单规则判定，产出可回填的 {@link Verdict}。
 *
 * <p>本类只做三件事：解析 JSON、调用纯规则、组装结论文本 —— 校验口径<b>全部</b>在
 * {@link GenerativeFormRules} 里（那里是可以脱离 Spring 直接断言的正反例对象）。
 *
 * <p>两道闸门的调用方见 {@link FrontendToolGuard}：下发前（{@code SpToolCallingManager}）与
 * 回灌前（{@code ConfirmGate}）。本类<b>不</b>在 {@code AiTools#generativeForm} 里做事 ——
 * 那个方法体是哨兵桩，正常路径永不执行。
 */
@Slf4j
@Component
public class GenerativeFormGuard implements FrontendToolGuard {

	private final ObjectMapper objectMapper;

	public GenerativeFormGuard(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public boolean supports(String toolName) {
		return GenerativeFormRules.TOOL_NAME.equals(toolName);
	}

	/** 下发前的 schema 白名单校验：不合法 ⇒ 调用方不挂起、前端看不到这张表单。 */
	@Override
	public Verdict inspectArguments(String argumentsJson) {
		JsonNode arguments = parse(argumentsJson);
		if (arguments == null) {
			return Verdict.reject(GenerativeFormRules.SCHEMA_REJECTED,
					"表单 schema 不是合法 JSON，工具未执行（未向前端下发表单）",
					List.of("工具参数无法解析为 JSON：" + abbreviate(argumentsJson)));
		}
		List<String> reasons = GenerativeFormRules.argumentReasons(arguments);
		if (reasons.isEmpty()) {
			return Verdict.accept();
		}
		log.warn("generative_form schema 未通过白名单校验（未挂起、未下发前端）：{}", reasons);
		return Verdict.reject(GenerativeFormRules.SCHEMA_REJECTED,
				"表单 schema 未通过白名单校验，工具未执行（未向前端下发表单）", reasons);
	}

	/** 回灌前的填写值类型复核：不合法 ⇒ 调用方拒绝本次回灌（挂起仍在等待，可修正后重试）。 */
	@Override
	public Verdict inspectResult(String argumentsJson, String resultJson) {
		JsonNode arguments = parse(argumentsJson);
		JsonNode form = arguments != null && arguments.isObject() ? arguments.get("form") : null;
		JsonNode values = parse(resultJson);
		if (values == null) {
			log.warn("generative_form 回灌结果不是合法 JSON，本次回灌被拒：{}", abbreviate(resultJson));
			return Verdict.reject(GenerativeFormRules.RESULT_REJECTED,
					"回灌的表单结果不是合法 JSON 对象，本次回灌被拒（挂起仍在等待）",
					List.of("回灌内容无法解析为 JSON：" + abbreviate(resultJson)
							+ "（应回灌字段 key→值的 JSON 对象；用户放弃请带 cancelled=true）"));
		}
		List<String> reasons = GenerativeFormRules.resultReasons(form, values);
		if (reasons.isEmpty()) {
			return Verdict.accept();
		}
		log.warn("generative_form 回灌结果未通过类型复核（回灌被拒、挂起保持未决）：{}", reasons);
		return Verdict.reject(GenerativeFormRules.RESULT_REJECTED,
				"回灌的表单结果未通过类型复核，本次回灌被拒（挂起仍在等待，可修正后重试）", reasons);
	}

	private JsonNode parse(String json) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			return this.objectMapper.readTree(json);
		}
		catch (Exception ex) {
			return null;
		}
	}

	/** 错误信息里的原文摘要（防止把超长入参整段回灌进日志/错误体）。 */
	private static String abbreviate(String text) {
		if (text == null) {
			return "(空)";
		}
		String flat = text.replaceAll("\\s+", " ").trim();
		return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
	}

}
