package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ReplyLanguage;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.tool.AiContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>宿主确定性的任务类型表单</b>：模型发起 {@code create_task} 但缺少或给出非法
 * {@code type} 时，挂起一张单选 {@code EXPORT/IMPORT}（{@code purpose=TASK_TYPE}）。
 *
 * <p>触发依据只是结构化工具参数，不解析用户原文，也不从旧任务页面推断新任务类型。
 * 类型已给出时仍由 {@code create_task} 走正常预检与确认门；宿主只处理明确的类型缺口。
 *
 * <p><b>与放行闸门的分工</b>：{@link GenerativeFormGuard} 仍按槽位拒绝<b>模型自发</b>的类型表单
 * （槽位已定 = 模型不该再问）；宿主表单也经过同一份白名单闸门。
 *
 * <p>挂起面复用 {@link HostForm}（{@code saveSuspended} → {@code suspended} 帧 → 请求帧 → 收流），
 * 用户选择经既有 {@code /api/ai/frontend-tool-result} 回灌，{@code create_task} 的预检与 HITL 不变。
 */
@Slf4j
@Component
public class TaskTypeForm extends HostForm {

	/** 表单唯一字段的 key（回灌值形如 {@code {"type":"EXPORT"}}）。 */
	public static final String FIELD_KEY = "type";

	/** 表单标题（与提示词同一语言）。 */
	public static final String TITLE = "选择任务类型";

	/** 字段标签。 */
	public static final String FIELD_LABEL = "任务类型";

	/** 英文标题（由 {@link ReplyLanguage} 选择，不新增翻译体系）。 */
	public static final String TITLE_EN = "Select task type";

	/** 英文字段标签。 */
	public static final String FIELD_LABEL_EN = "Task type";

	/** 宿主类型表单的 {@code toolCallId} 前缀（后接 runId：同一轮只会问一次）。 */
	public static final String TOOL_CALL_ID_PREFIX = "host-type-form-";

	@Autowired
	public TaskTypeForm(RunStore store, RunRegistry registry, ConfirmGate gate, MessageJsonCodec codec,
			AiProperties properties) {
		super(store, registry, gate, codec, properties);
	}

	/** 宿主为某一轮生成的类型表单 {@code toolCallId}（确定性 = 天然去重）。 */
	public static String toolCallId(String runId) {
		return TOOL_CALL_ID_PREFIX + runId;
	}

	/**
	 * <b>结构化类型缺口</b>：模型发起了 {@code create_task} 但缺少/非法 {@code type}。
	 *
	 * <p>这是模型工具调用（结构化事实），不是用户中文措辞。宿主据此挂一次
	 * {@code purpose=TASK_TYPE} 单选；同一 run 只挂一次。调用方必须在真正执行 create_task 前
	 * 调用，并把 {@code history} 构造成"assistant(tool_calls) + 该调用的工具响应"，
	 * 保证挂起后续跑的 tool_calls/tool 响应对完整。
	 */
	public boolean parkIfCreateTaskMissingType(String runId, AiContext context, String argumentsJson,
			List<Message> history) {
		return parkIfCreateTaskMissingType(runId, context, argumentsJson, history, null, null);
	}

	/**
	 * 同 {@link #parkIfCreateTaskMissingType(String, AiContext, String, List)}，并显式带上
	 * <b>当前模型 assistant 正文/元数据</b>。
	 *
	 * <p>工具跳提前 park 时，本段正文还没进 {@code messageJson}；若不显式传给
	 * {@code HostForm}，挂起快照只会带上上一轮/空 {@code assistantContent}。调用方应把
	 * {@code history} 里的 assistant 正文置空（避免挂起历史与 {@code assistantContent} 各存一份），
	 * 正文由这里随挂起快照保存。
	 */
	public boolean parkIfCreateTaskMissingType(String runId, AiContext context, String argumentsJson,
			List<Message> history, String assistantContent, Map<String, Object> assistantMetadata) {
		if (runId == null || context == null || history == null || !isParkEnabled()) {
			return false;
		}
		if (!typeMissingOrInvalid(argumentsJson)) {
			return false;
		}
		if (hasIssued(runId, toolCallId(runId))) {
			return false;
		}
		Optional<String> arguments = formArguments(buildForm(ReplyLanguage.current()));
		if (arguments.isEmpty()) {
			return false;
		}
		log.info("create_task has no valid type; host parks a TASK_TYPE form runId={}", runId);
		return shouldPark(runId, history, frontendForm(toolCallId(runId), arguments.get()), assistantContent,
				assistantMetadata);
	}

	/** type 缺失/空白/不在 EXPORT|IMPORT 闭集。 */
	public static boolean typeMissingOrInvalid(String argumentsJson) {
		if (StringUtils.isBlank(argumentsJson)) {
			return true;
		}
		try {
			com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
				.readTree(argumentsJson);
			com.fasterxml.jackson.databind.JsonNode type = node == null ? null : node.get("type");
			String text = type == null || type.isNull() ? "" : type.asText("").trim();
			return !("EXPORT".equalsIgnoreCase(text) || "IMPORT".equalsIgnoreCase(text));
		}
		catch (Exception ex) {
			return true;
		}
	}

	/** 单个 enum 单选 {@code EXPORT/IMPORT}（形状与 {@link FormPurposes#TASK_TYPE} 一致）。 */
	private static Map<String, Object> buildForm(ReplyLanguage language) {
		boolean english = language != null && language.english();
		Map<String, Object> field = new LinkedHashMap<>();
		field.put("key", FIELD_KEY);
		field.put("label", english ? FIELD_LABEL_EN : FIELD_LABEL);
		field.put("type", "enum");
		field.put("required", true);
		field.put("options", english
				? List.of(option("EXPORT", "Export"), option("IMPORT", "Import"))
				: List.of(option("EXPORT", "导出"), option("IMPORT", "导入")));

		Map<String, Object> form = new LinkedHashMap<>();
		form.put("scenario", "CLARIFY");
		form.put("purpose", FormPurposes.TASK_TYPE);
		form.put("title", english ? TITLE_EN : TITLE);
		form.put("fields", List.of(field));
		return form;
	}

	private static Map<String, Object> option(String value, String label) {
		Map<String, Object> option = new LinkedHashMap<>();
		option.put("value", value);
		option.put("label", label);
		return option;
	}

}
