package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>confirm_step 的后置条件兜底</b>：模型试图把导出任务从 {@code QUERY_COND} 直接推进到
 * {@code EXPORT} 时，若任务条目上还没有任何显式筛选条件、且本轮还没有收集过，就在真正推进前
 * 拦下并挂起一张<b>非必填自由文本框</b>（{@code purpose=SET_CONDITIONS}）。任务仍停
 * {@code QUERY_COND}；用户留空回灌后，下一次 {@code confirm_step} 才允许全量推进。
 *
 * <h2>为什么只做这一处</h2>
 * 首轮与续跑交棒点都<b>不再</b>由宿主抢先发条件表单：条件写法（大小写、别名、自由文本）必须先由
 * 模型理解用户原话，已知条件直接 {@code set_condition}。本类只兜住模型"跳过条件直接 confirm_step"
 * 这一条会造成数据错误（按全量导出）的路径，不改变任何其它工具或步骤。
 *
 * <h2>判据（全部成立才挂起）</h2>
 * <ol>
 * <li>{@code confirm_step} 且目标是 {@code EXPORT}（不传 step 也按下一步处理）；</li>
 * <li>任务真实 {@code currentStep} 是 {@code QUERY_COND}（不看模型自报的步骤）；</li>
 * <li>任务条目里<b>没有任何显式条件</b>（解析真实 {@code conditionJson}，不写死字段/值/正则）；</li>
 * <li>本轮尚未收集过（同一 run 的条件表单 {@code toolCallId} 只挂一次）；</li>
 * </ol>
 *
 * <p>已知条件（包括模型刚 {@code set_condition} 落库的）由第 3 条直接放行；已经到 {@code EXPORT}
 * 或之后的步骤由第 2 条放行。
 */
@Slf4j
@Component
public class ConditionsForm extends HostForm {

	/** 表单唯一字段的 key（回灌值形如 {@code {"conditions":"code = CNY"}}；空串 = 不设条件）。 */
	public static final String FIELD_KEY = "conditions";

	/** 表单标题（与提示词同一语言）。 */
	public static final String TITLE = "筛选条件（可选）";

	/** 字段标签。 */
	public static final String FIELD_LABEL = "筛选条件";

	/** 宿主条件表单的 {@code toolCallId} 前缀（后接 runId 与任务号：同一轮同一任务只问一次）。 */
	public static final String TOOL_CALL_ID_PREFIX = "host-cond-form-";

	/** 条件步（导出向导）。 */
	private static final String STEP_QUERY_COND = "QUERY_COND";

	/** 推进目标步（导出向导）。 */
	private static final String STEP_EXPORT = "EXPORT";

	/** 推进工具名（{@code RunContextRefresher.TOOL_CONFIRM_STEP} 同一拼写）。 */
	private static final String TOOL_CONFIRM_STEP = "confirm_step";

	private final TaskService taskService;

	@Autowired
	public ConditionsForm(RunStore store, RunRegistry registry, ConfirmGate gate, MessageJsonCodec codec,
			AiProperties properties, TaskService taskService) {
		super(store, registry, gate, codec, properties);
		this.taskService = taskService;
	}

	/** 宿主为某个 run 的某个任务生成的条件表单 {@code toolCallId}（确定性 = 天然去重）。 */
	public static String toolCallId(String runId, long taskId) {
		return TOOL_CALL_ID_PREFIX + (runId == null ? "" : runId + "-") + taskId;
	}

	/**
	 * 判据成立时挂起非必填文本框。
	 *
	 * @param runId    本轮 id
	 * @param context  本轮<b>权威上下文</b>（taskId 来自这里；步骤仍以任务真实值为准）
	 * @param pending  模型刚发出的 {@code confirm_step} 调用（尚未交给前端）
	 * @param history  交棒历史（<b>不含</b>这次 confirm_step 的 assistant tool_call）
	 * @return {@code true} = 已挂起：调用方本拍<b>不得</b>再执行 confirm_step
	 */
	public boolean parkIfConfirmStepAdvance(String runId, AiContext context, PendingToolCall pending,
			List<Message> history) {
		if (runId == null || context == null || pending == null || history == null) {
			return false;
		}
		if (!parkEnabled()) {
			// 阻塞式等待不承接这条流程（见 HostForm 类注：不新增中断路径）
			return false;
		}
		try {
			Long taskId = context.getTaskId();
			if (taskId == null || !isConfirmStepForward(pending)) {
				return false;
			}
			Task task = readTask(taskId);
			if (task == null || task.getType() != Task.TaskType.EXPORT) {
				return false;
			}
			if (!STEP_QUERY_COND.equalsIgnoreCase(trim(task.getCurrentStep()))) {
				// 只在真正会发生 QUERY_COND→EXPORT 的推进上兜底；EXPORT 及之后一律不弹
				return false;
			}
			if (task.getItems() == null || task.getItems().isEmpty()) {
				// 没有已选配置项就没有"条件"可收集（这类任务本就推进不下去）
				return false;
			}
			if (hasAnyExplicitCondition(task)) {
				// 已知条件（含模型刚 set_condition 落库的）⇒ 直接放行
				return false;
			}
			String toolCallId = toolCallId(runId, taskId);
			if (alreadyIssued(runId, toolCallId)) {
				// 同一轮已经收集过（含留空/取消）：不得重复弹
				return false;
			}
			Optional<String> arguments = formArguments(buildForm());
			if (arguments.isEmpty()) {
				return false;
			}
			log.info("confirm_step 推进前条件未设置，改为收集一次可选条件 runId={} taskId={}", runId, taskId);
			return park(runId, history, frontendForm(toolCallId, arguments.get()));
		}
		catch (RuntimeException ex) {
			log.warn("confirm_step 的条件兜底未能挂起（按既有推进路径继续）runId={}：{}", runId, ex.getMessage());
			return false;
		}
	}

	/** 是 {@code confirm_step}，且目标步骤为空（下一步）或正好是 {@code EXPORT}。 */
	private static boolean isConfirmStepForward(PendingToolCall pending) {
		if (!TOOL_CONFIRM_STEP.equals(pending.getName())
				|| !PendingToolCall.KIND_FRONTEND.equals(pending.getKind())) {
			return false;
		}
		JsonNode arguments = parseJson(pending.getArguments());
		JsonNode step = arguments == null ? null : arguments.get("step");
		if (step == null || step.isNull()) {
			return true;
		}
		String target = step.asText("").trim();
		return target.isEmpty() || STEP_EXPORT.equalsIgnoreCase(target);
	}

	/** 读任务真实状态；读不到 = 不兜底（fail-safe，交回既有推进路径）。 */
	private Task readTask(Long taskId) {
		if (this.taskService == null) {
			return null;
		}
		try {
			return this.taskService.findById(taskId);
		}
		catch (RuntimeException ex) {
			log.warn("读取任务真实条件状态失败，confirm_step 条件兜底跳过 taskId={}：{}", taskId, ex.getMessage());
			return null;
		}
	}

	/** 任务条目里是否已有显式条件（字段条件或范围键任一非空）。 */
	private static boolean hasAnyExplicitCondition(Task task) {
		for (TaskItem item : task.getItems()) {
			if (item != null && hasCondition(item.getConditionJson())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 条件 JSON 是否非空：{@code {} }/{@code null}/空串 = 未设置；{@code fields} 里有合法的
	 * {@code fieldCode + operator}，或 {@code scopeKeys} 里有非空字符串 = 已设置。
	 * 解析失败/无效条目按"未设置"处理（与前端 {@code parseConditionJson} 同口径：宁可多问一次，
	 * 也不按全量误推进）。判据只认结构，不认具体字段/值/大小写。
	 */
	private static boolean hasCondition(String conditionJson) {
		if (conditionJson == null || conditionJson.isBlank()) {
			return false;
		}
		JsonNode node = parseJson(conditionJson);
		if (node == null || !node.isObject()) {
			return false;
		}
		return hasValidField(node.get("fields")) || hasValidScope(node.get("scopeKeys"));
	}

	private static boolean hasValidField(JsonNode fields) {
		if (fields == null || !fields.isArray()) {
			return false;
		}
		for (JsonNode field : fields) {
			if (field != null && field.isObject() && text(field.get("fieldCode")) != null
					&& text(field.get("operator")) != null) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasValidScope(JsonNode scopeKeys) {
		if (scopeKeys == null || !scopeKeys.isArray()) {
			return false;
		}
		for (JsonNode key : scopeKeys) {
			if (key != null && key.isTextual() && !key.asText().isBlank()) {
				return true;
			}
		}
		return false;
	}

	private static String text(JsonNode node) {
		if (node == null || !node.isTextual()) {
			return null;
		}
		String value = node.asText().trim();
		return value.isEmpty() ? null : value;
	}

	/** 单个非必填 text 框（形状与 {@link GenerativeFormRules#PURPOSE_SET_CONDITIONS} 一致）。 */
	private static Map<String, Object> buildForm() {
		Map<String, Object> field = new LinkedHashMap<>();
		field.put("key", FIELD_KEY);
		field.put("label", FIELD_LABEL);
		field.put("type", "text");
		field.put("required", false);
		field.put("variant", GenerativeFormRules.VARIANT_LONG_TEXT);
		field.put("placeholder", "留空 = 不设筛选（全量）");

		Map<String, Object> form = new LinkedHashMap<>();
		form.put("scenario", "FILTER");
		form.put("purpose", GenerativeFormRules.PURPOSE_SET_CONDITIONS);
		form.put("title", TITLE);
		form.put("fields", List.of(field));
		return form;
	}

	private static String trim(String step) {
		return step == null ? "" : step.trim();
	}

}
