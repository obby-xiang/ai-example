package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ReplyLanguage;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.slot.ConditionAssessment;
import com.example.configmgr.ai.slot.TaskSlots;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>confirm_step 的后置条件兜底与条件闭环</b>：模型试图把导出任务从 {@code QUERY_COND} 直接推进到
 * {@code EXPORT} 时，按 {@link ConditionAssessment} 的<b>三态</b>逐项核对每个配置项：
 * 未决定/无法解析 ⇒ 挂起一张<b>非必填自由文本框</b>（{@code purpose=SET_CONDITIONS}）收集一次；
 * 已设置或明确不设 ⇒ 放行。任务仍停 {@code QUERY_COND}；用户留空回灌后才允许按全量推进。
 *
 * <h2>为什么只做这一处</h2>
 * 首轮与续跑交棒点都<b>不再</b>由宿主抢先发条件表单：条件写法（大小写、别名、自由文本）必须先由
 * 模型理解用户原话，已知条件直接 {@code set_condition}。本类只兜住模型"跳过条件直接 confirm_step"
 * 这一条会造成数据错误（按全量导出）的路径，不改变任何其它工具或步骤。
 *
 * <h2>判据</h2>
 * <ol>
 * <li>{@code confirm_step} 且目标是 {@code EXPORT}（不传 step 也按下一步处理）；</li>
 * <li>任务真实 {@code currentStep} 是 {@code QUERY_COND}（不看模型自报的步骤）；
 *     {@code SELECT_DEFS → EXPORT} 的直接跳步<b>零副作用拒绝</b>；</li>
 * <li>条件未知/无法解析时才收集；模型已知但未落库、或条件表回灌了非空文本却未落库 ⇒
 *     <b>零副作用拒绝 confirm_step 并指回 set_condition</b>，不弹表单；</li>
 * <li>同一 run 的条件表单只问一次（含取消/留空）：不换卡重问。</li>
 * </ol>
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

	/** 表单占位（留空 = 全量）。 */
	public static final String PLACEHOLDER = "留空 = 不设筛选（全量）";

	/** 英文标题（由 {@link ReplyLanguage} 选择，不新增翻译体系）。 */
	public static final String TITLE_EN = "Filter conditions (optional)";

	/** 英文字段标签。 */
	public static final String FIELD_LABEL_EN = "Filter conditions";

	/** 英文占位。 */
	public static final String PLACEHOLDER_EN = "Leave blank = no filter (export all)";

	/** 宿主条件表单的 {@code toolCallId} 前缀（后接 runId 与任务号：同一轮同一任务只问一次）。 */
	public static final String TOOL_CALL_ID_PREFIX = "host-cond-form-";

	/** 选择配置项步（导出向导）：不得直接跳到 EXPORT。 */
	private static final String STEP_SELECT_DEFS = "SELECT_DEFS";

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
	 * 按<b>当前缺口</b>生成的条件表单 {@code toolCallId}：全部配置项都未决定时沿用旧号（兼容既有卡），
	 * 只剩部分项时追加缺口集合 —— 同一缺口只问一次，剩余项不会被"已问过"永久挡住。
	 */
	static String toolCallId(String runId, long taskId, List<String> undecidedDefCodes, List<String> allDefCodes) {
		String base = toolCallId(runId, taskId);
		if (undecidedDefCodes == null || undecidedDefCodes.isEmpty() || allDefCodes == null
				|| undecidedDefCodes.size() >= allDefCodes.size()) {
			// 全部配置项都未决定 = 首张条件表：沿用旧号（既有卡与去重口径不变）
			return base;
		}
		return base + "-" + String.join("-", undecidedDefCodes);
	}

	/** 当前任务的配置项编码（生成"按缺口"的条件表 id 用）。 */
	private static List<String> taskDefCodesFor(Task task) {
		if (task == null || task.getItems() == null) {
			return List.of();
		}
		List<String> out = new ArrayList<>();
		for (var item : task.getItems()) {
			if (item != null && item.getDefCode() != null && !item.getDefCode().isBlank()) {
				out.add(item.getDefCode());
			}
		}
		return out;
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
	/** confirm_step 复核结论：放行 / 已挂起条件表单 / 零副作用拒绝。 */
	public record ConfirmStepDecision(Outcome outcome, String code, String message, List<String> reasons) {

		public enum Outcome {
			ALLOW, PARKED, REJECTED
		}

		public ConfirmStepDecision {
			reasons = reasons == null ? List.of() : List.copyOf(reasons);
		}

		static ConfirmStepDecision allow() {
			return new ConfirmStepDecision(Outcome.ALLOW, null, null, List.of());
		}

		static ConfirmStepDecision parkedDecision() {
			return new ConfirmStepDecision(Outcome.PARKED, null, null, List.of());
		}

		static ConfirmStepDecision reject(String code, String message, List<String> reasons) {
			return new ConfirmStepDecision(Outcome.REJECTED, code, message, reasons);
		}

		public boolean parked() {
			return this.outcome == Outcome.PARKED;
		}

		public boolean rejected() {
			return this.outcome == Outcome.REJECTED;
		}
	}

	/**
	 * 统一复核 {@code confirm_step}（Task 1 第 2/3 项）：
	 * <ol>
	 * <li><b>禁止跳步</b>：{@code SELECT_DEFS} 不能直接点名 {@code EXPORT}（会跳过 QUERY_COND 与条件收集）；</li>
	 * <li><b>非空回灌未落库</b>：模型自己的条件表或宿主条件表收到非空文本、但任务条目还没有对应条件
	 * ⇒ 零副作用拒绝，回执指回 {@code set_condition}；</li>
	 * <li><b>已知未落库</b>（槽位里已有条件事实）⇒ 同样指回 {@code set_condition}，<b>不弹表单</b>；</li>
	 * <li><b>逐项核对</b>：任一配置项仍未决定/无法解析 ⇒ 挂一次可选条件表单（留空才允许全量）；
	 * 已设置或明确不设 ⇒ 放行；取消过的表单不换卡重问。</li>
	 * </ol>
	 */
	public ConfirmStepDecision inspectConfirmStep(String runId, AiContext context, PendingToolCall pending,
			List<Message> history) {
		if (runId == null || context == null || pending == null || history == null) {
			return ConfirmStepDecision.allow();
		}
		if (!isConfirmStepForward(pending)) {
			return ConfirmStepDecision.allow();
		}
		try {
			Long taskId = context.getTaskId();
			if (taskId == null) {
				return ConfirmStepDecision.allow();
			}
			Task task = readTask(taskId);
			if (task == null || task.getType() != Task.TaskType.EXPORT) {
				return ConfirmStepDecision.allow();
			}
			String step = trim(task.getCurrentStep());
			if (step.isEmpty()) {
				// 步骤未知（旧任务/直调）：不据此新增拒绝或表单
				return ConfirmStepDecision.allow();
			}
			String target = targetStep(pending);
			if (STEP_SELECT_DEFS.equalsIgnoreCase(step) && STEP_EXPORT.equalsIgnoreCase(target)) {
				log.info("confirm_step 试图从 SELECT_DEFS 直接跳到 EXPORT，零副作用拒绝 runId={} taskId={}", runId,
						taskId);
				return ConfirmStepDecision.reject(ErrorCodes.STEP_NOT_IN_SEQUENCE,
						"不能从 SELECT_DEFS 直接跳到 EXPORT（工具未执行）",
						List.of("当前步骤 SELECT_DEFS，EXPORT 不是下一步：直接跳步会跳过 QUERY_COND 与条件收集",
								"下一步：先 confirm_step（不传 step）推进到 QUERY_COND，按条件链处理后再推进到 EXPORT"));
			}
			if (!STEP_QUERY_COND.equalsIgnoreCase(step)) {
				// 只在真正会发生 QUERY_COND→EXPORT 的推进上做条件兜底；EXPORT 及之后一律放行
				return ConfirmStepDecision.allow();
			}
			if (task.getItems() == null || task.getItems().isEmpty()) {
				// 没有已选配置项就没有"条件"可收集（这类任务本就推进不下去）
				return ConfirmStepDecision.allow();
			}
			return conditionGate(runId, taskId, task, context, pending, history);
		}
		catch (RuntimeException ex) {
			log.warn("confirm_step 的条件复核未能完成（按既有推进路径继续）runId={}：{}", runId, ex.getMessage());
			return ConfirmStepDecision.allow();
		}
	}

	private ConfirmStepDecision conditionGate(String runId, long taskId, Task task, AiContext context,
			PendingToolCall pending, List<Message> history) {
		ConditionAssessment.Assessment assessment = ConditionAssessment.of(task);
		TaskSlots slots = TaskSlots.fromExtra(context.getExtra());
		// ① 模型已知但未落库（槽位有条件事实，任务条目没有）：指回 set_condition，不弹表单
		List<String> knownUnpersisted = new ArrayList<>();
		for (String defCode : slots.conditionDefCodes()) {
			if (assessment.stateOf(defCode) != ConditionAssessment.State.SET) {
				knownUnpersisted.add(defCode);
			}
		}
		if (!knownUnpersisted.isEmpty()) {
			String known = String.join(", ", knownUnpersisted);
			log.info("confirm_step 时条件已知但尚未落库，零副作用拒绝 runId={} taskId={} defs={}", runId, taskId,
					known);
			return ConfirmStepDecision.reject(ErrorCodes.FORM_SLOTS_ALREADY_KNOWN,
					"条件已明确但尚未落库，confirm_step 未执行（未向前端推进）",
					List.of("已明确条件：" + known + "（来自用户原话/槽位，任务条目里还没有）",
							"下一步：先调用 set_condition 把条件落库（仍停在 QUERY_COND），下一批再 confirm_step"));
		}
		// ② 逐项核对：全部已有答案（已设置/明确不设）⇒ 放行（不看历史回灌）
		if (assessment.undecidedDefCodes().isEmpty()) {
			return ConfirmStepDecision.allow();
		}
		ConditionFeedback feedback = feedbackOf(runId, taskId);
		// ③ 取消不自动重问，但也不能放行：条件仍未决定，confirm_step 零副作用拒绝并给出两条明确出口
		if (feedback.cancelled()) {
			log.info("confirm_step 时条件表已被取消且条件仍未决定，零副作用拒绝 runId={} taskId={}", runId, taskId);
			return ConfirmStepDecision.reject(ErrorCodes.FORM_RESULT_REJECTED,
					"条件仍未决定（条件表已取消），confirm_step 未执行（未向前端推进）",
					List.of("取消不自动重问；但条件仍未决定，不能按全量误推进",
							"恢复通道：由用户下一轮明确选择——给出筛选条件（模型先 set_condition 落库）"
									+ "或明确按全量导出；本 run 不再自动弹条件表"));
		}
		// ④ 留空回灌：允许按全量推进；把"明确不设"落库成 {@code "{}"}（与前端清空条件的落库形态一致），
		// 这样后续直接 start_export 的条件预检读得到同一个答案（不会把"留空"误判成"未决定"）。
		if (feedback.blank()) {
			if (!persistExplicitUnset(taskId, assessment.undecidedDefCodes())) {
				return ConfirmStepDecision.reject(ErrorCodes.FORM_RESULT_REJECTED,
						"条件留空未能落库，confirm_step 未执行（未向前端推进）",
						List.of("条件表已留空（= 全量），但「明确不设」没有写成任务条目的条件事实",
								"下一步：重试 confirm_step（零副作用，不会按未决定误推进）"));
			}
			return ConfirmStepDecision.allow();
		}
		// ⑤ 非空回灌：只有在它还没被 set_condition 落库时才挡；已处理过的回灌不得永久挡住剩余项
		if (feedback.nonEmpty() && !feedback.handled()) {
			log.info("confirm_step 时条件表已收到非空文本但未落库，零副作用拒绝 runId={} taskId={}", runId, taskId);
			return ConfirmStepDecision.reject(ErrorCodes.FORM_SLOTS_ALREADY_KNOWN,
					"条件表已填写但尚未落库，confirm_step 未执行（未向前端推进）",
					List.of("条件表回灌了非空文本，但任务条目里还没有对应条件（已知未落库）",
							"下一步：先把该文本解析成结构化条件并调用 set_condition 落库，下一批再 confirm_step",
							"不得改用第二张条件表单重复收集"));
		}
		// ⑥ 同一缺口只问一次；已处理过的回灌之后，剩余项用新的 toolCallId 继续收集
		String toolCallId = toolCallId(runId, taskId, assessment.undecidedDefCodes(),
				taskDefCodesFor(task));
		if (!parkEnabled() || alreadyIssued(runId, toolCallId)) {
			// 阻塞式等待不承接这条流程（见 HostForm 类注：不新增中断路径）
			return ConfirmStepDecision.allow();
		}
		Optional<String> arguments = formArguments(buildForm(ReplyLanguage.current()));
		if (arguments.isEmpty()) {
			return ConfirmStepDecision.allow();
		}
		log.info("confirm_step 推进前条件未决定，改为收集一次可选条件 runId={} taskId={} defs={}", runId, taskId,
				assessment.undecidedDefCodes());
		boolean parked = park(runId, history, frontendForm(toolCallId, arguments.get()));
		return parked ? ConfirmStepDecision.parkedDecision() : ConfirmStepDecision.allow();
	}

	/**
	 * 本轮 {@code SET_CONDITIONS} 表单的回灌事实（模型表单与宿主表单<b>同一份</b>：
	 * 都持久化在同一个 run 的待决条目里，跨实例、跨续跑都读得到）。
	 */
	record ConditionFeedback(boolean cancelled, boolean blank, boolean nonEmpty, boolean handled) {

		static ConditionFeedback none() {
			return new ConditionFeedback(false, false, false, false);
		}
	}

	ConditionFeedback feedbackOf(String runId, long taskId) {
		List<PendingToolCall> pendings;
		try {
			pendings = this.store.pendingsLenient(runId);
		}
		catch (RuntimeException ex) {
			log.warn("读取条件表单回灌失败（按未收集处理）runId={}：{}", runId, ex.getMessage());
			return ConditionFeedback.none();
		}
		PendingToolCall latest = null;
		for (PendingToolCall form : pendings == null ? List.<PendingToolCall>of() : pendings) {
			if (form == null || !GenerativeFormRules.TOOL_NAME.equals(form.getName())
					|| !isConditionForm(form.getArguments()) || !belongsToTask(form.getToolCallId(), runId, taskId)) {
				continue;
			}
			if (latest == null || form.getResolvedAtMs() >= latest.getResolvedAtMs()) {
				latest = form;
			}
		}
		if (latest == null) {
			return ConditionFeedback.none();
		}
		if (PendingToolCall.FRONTEND_CANCELLED.equals(latest.getStatus())) {
			// 取消也是"问过了"：不换卡重问，但也不得放行（见 conditionGate ③）
			return new ConditionFeedback(true, false, false, false);
		}
		if (!PendingToolCall.FRONTEND_RESULT.equals(latest.getStatus())) {
			// 还没回灌（PENDING）或旧状态：不据此重问
			return ConditionFeedback.none();
		}
		String text = conditionTextOf(latest.getResultText());
		if (text == null) {
			return ConditionFeedback.none();
		}
		if (text.isBlank()) {
			return new ConditionFeedback(false, true, false, false);
		}
		return new ConditionFeedback(false, false, true, setConditionPersistedSince(runId, latest.getResolvedAtMs()));
	}

	/** 该表单回灌后是否已有 set_condition 落库（台账是跨实例的既成事实）。读失败 = 未处理（fail-closed）。 */
	private boolean setConditionPersistedSince(String runId, long sinceMs) {
		List<Map<String, Object>> ledger;
		try {
			ledger = this.store.ledger(runId);
		}
		catch (RuntimeException ex) {
			log.warn("读取台账失败，非空条件回灌按未落库处理 runId={}：{}", runId, ex.getMessage());
			return false;
		}
		for (Map<String, Object> record : ledger == null ? List.<Map<String, Object>>of() : ledger) {
			if (record == null || !"set_condition".equals(record.get("name"))) {
				continue;
			}
			if (!ToolOutcome.OK.wire().equals(String.valueOf(record.get("outcome")))) {
				continue;
			}
			Object atMs = record.get("atMs");
			if (atMs instanceof Number number && number.longValue() >= sinceMs) {
				return true;
			}
		}
		return false;
	}

	/** 宿主条件表单的 toolCallId 带任务号；模型表单没有任务号（同一 run 只有一个任务）⇒ 按 run 认。 */
	private static boolean belongsToTask(String toolCallId, String runId, long taskId) {
		if (toolCallId == null) {
			return false;
		}
		if (!toolCallId.startsWith(TOOL_CALL_ID_PREFIX)) {
			return true;
		}
		// 宿主条件表有两种形态：host-cond-form-<run>-<task> 与带缺口集合的 <...-<def1>-<def2>>。
		// 必须按"base + 边界"匹配：直接 equals 会把部分缺口卡误认成别的 task，直接 startsWith(base)
		// 会把 task 42 与 420 混为一谈。
		String base = toolCallId(runId, taskId);
		return toolCallId.equals(base) || toolCallId.startsWith(base + "-");
	}

	private static boolean isConditionForm(String argumentsJson) {
		JsonNode arguments = parseJson(argumentsJson);
		JsonNode form = arguments == null ? null : arguments.get("form");
		return form != null && GenerativeFormRules.PURPOSE_SET_CONDITIONS.equals(form.path("purpose").asText());
	}

	/** 回灌值形态 {@code {"conditions":"…"}}；解析失败返回 null（不猜）。 */
	private static String conditionTextOf(String resultText) {
		JsonNode values = parseJson(resultText);
		JsonNode conditions = values == null ? null : values.get(FIELD_KEY);
		if (conditions == null || conditions.isNull() || !conditions.isTextual()) {
			return null;
		}
		return conditions.asText();
	}

	private static String targetStep(PendingToolCall pending) {
		JsonNode arguments = parseJson(pending.getArguments());
		JsonNode step = arguments == null ? null : arguments.get("step");
		return step == null || step.isNull() ? null : step.asText("");
	}

	/**
	 * 把"用户留空 = 明确不设"落成任务条目的 {@code "{}"}（与前端 {@code setCondition} 清空同形态）。
	 *
	 * <p>不写这一步的后果：条件表留空后任务仍是"未决定"，下一次直接 {@code start_export} 的
	 * 同源条件预检会把它判成未决定并拦住全量导出。
	 *
	 * @return {@code false} = 至少一个配置项没写成功（调用方不得当作已答过）
	 */
	private boolean persistExplicitUnset(long taskId, List<String> defCodes) {
		if (this.taskService == null || defCodes == null || defCodes.isEmpty()) {
			return true;
		}
		for (String defCode : defCodes) {
			try {
				this.taskService.setCondition(taskId, defCode, "{}");
			}
			catch (RuntimeException ex) {
				log.warn("条件留空落库失败 taskId={} defCode={}：{}", taskId, defCode, ex.getMessage());
				return false;
			}
		}
		return true;
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

	/** 单个非必填 text 框（形状与 {@link GenerativeFormRules#PURPOSE_SET_CONDITIONS} 一致）。 */
	private static Map<String, Object> buildForm(ReplyLanguage language) {
		boolean english = language != null && language.english();
		Map<String, Object> field = new LinkedHashMap<>();
		field.put("key", FIELD_KEY);
		field.put("label", english ? FIELD_LABEL_EN : FIELD_LABEL);
		field.put("type", "text");
		field.put("required", false);
		field.put("variant", GenerativeFormRules.VARIANT_LONG_TEXT);
		field.put("placeholder", english ? PLACEHOLDER_EN : PLACEHOLDER);

		Map<String, Object> form = new LinkedHashMap<>();
		form.put("scenario", "FILTER");
		form.put("purpose", GenerativeFormRules.PURPOSE_SET_CONDITIONS);
		form.put("title", english ? TITLE_EN : TITLE);
		form.put("fields", List.of(field));
		return form;
	}

	private static String trim(String step) {
		return step == null ? "" : step.trim();
	}

}
