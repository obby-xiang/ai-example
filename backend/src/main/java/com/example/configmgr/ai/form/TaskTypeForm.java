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
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>宿主确定性的任务类型表单</b>：用户只说"创建/新建任务"、类型又说不出时，由宿主先挂起一张
 * 单选 {@code EXPORT/IMPORT}（{@code purpose=TASK_TYPE}），<b>不再把这一问留给模型</b>。
 *
 * <h2>被替换掉的自由裁量</h2>
 * 同一句话下模型可能直接写成文字反问（"请问要创建导出还是导入任务？"）。那是把结构化缺口退回
 * 开放式文本：用户得打字、模型还得再解析一次，且两边都可能跑偏。用户口径是确定性的：
 * <b>新建意图 + 类型未知 ⇒ 宿主直接发单选</b>。
 *
 * <h2>触发判据（当前不再由宿主从文本推断）</h2>
 * <p>自然语言意图只由<b>既有模型工具调用</b>输出：类型未知时模型用
 * {@code generative_form(purpose=TASK_TYPE)} 收集，或直接 {@code create_task(type=...)}。
 * 宿主不再用 {@link com.example.configmgr.ai.slot.TaskIntent} 的中文正则判断"新建意图/是否说了类型"，
 * 因此 {@link #parkIfDue} 在生产链路上恒为 {@code false}（保留类与挂起面作兼容）。
 *
 * <p><b>与放行闸门的分工</b>：{@link GenerativeFormGuard} 仍按槽位拒绝<b>模型自发</b>的类型表单
 * （槽位已定 = 模型不该再问）；宿主只负责"这一次由谁来问"，所以它的判据是"本轮原话"而不是旧任务
 * 的权威值。两边判断对象不同，不存在两套互相矛盾的口径。
 *
 * <h2>接入点与"不新增交互形态"</h2>
 * 挂在首轮 {@code store.create} 之后、驱动模型之前：类型未知时这一轮本来就<b>没有</b>可执行的动作
 * （{@code create_task} 缺必填的 type），先问一句是这一轮最短的路径。挂起面完全复用
 * {@link HostForm}（{@code saveSuspended} → {@code suspended} 帧 → 请求帧 → 收流），
 * 用户的选择经既有 {@code /api/ai/frontend-tool-result} 回灌，下一拍模型拿到的就是一条
 * {@code {"type":"EXPORT"}} 的工具结果 —— 确认卡（{@code create_task}）与 HITL 一个字都没改。
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
	 * 判据成立时挂起类型单选。
	 *
	 * @param runId       本轮 id
	 * @param context     本轮上下文（含槽位 {@code extra[taskSlots]}）
	 * @param userMessage 本轮用户原文
	 * @param history     交棒历史（已含本轮用户消息）
	 * @return {@code true} = 已挂起：调用方本拍<b>不得</b>再驱动模型
	 */
	public boolean parkIfDue(String runId, AiContext context, String userMessage, List<Message> history) {
		// 宿主不再从用户原话推断任务类型：类型未知由模型经 generative_form(TASK_TYPE) / create_task 处理。
		return false;
	}

	/**
	 * 判据 + 表单正文（不含任何外置写）。
	 *
	 * <p>{@code context} 刻意<b>不</b>参与类型判定：它带着的是"当前打开的那条旧任务"的权威值
	 * （{@code taskType} 与 {@code extra[taskSlots]}），拿它判<b>新任务</b>的类型就是让旧任务参数
	 * 决定新任务。保留这个参数只为与另外两张宿主表单同形（调用点一处、判据一处）。
	 */
	Optional<String> argumentsIfDue(String runId, AiContext context, String userMessage) {
		// 兼容入口：宿主不再从文本推断类型（生产判断不经过中文正则）。
		return Optional.empty();
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
		if (runId == null || context == null || history == null || !parkEnabled()) {
			return false;
		}
		if (!typeMissingOrInvalid(argumentsJson)) {
			return false;
		}
		if (alreadyIssued(runId, toolCallId(runId))) {
			return false;
		}
		Optional<String> arguments = formArguments(buildForm(ReplyLanguage.current()));
		if (arguments.isEmpty()) {
			return false;
		}
		log.info("create_task 缺少合法 type，宿主挂一次 TASK_TYPE 表单 runId={}", runId);
		return park(runId, history, frontendForm(toolCallId(runId), arguments.get()));
	}

	/** type 缺失/空白/不在 EXPORT|IMPORT 闭集。 */
	public static boolean typeMissingOrInvalid(String argumentsJson) {
		if (argumentsJson == null || argumentsJson.isBlank()) {
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
