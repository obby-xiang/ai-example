package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.RunContextRefresher;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ReplyLanguage;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.AiToolNames;
import com.example.configmgr.ai.tool.FrontendToolGuard;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.WizardActionGuard;
import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.service.TaskSteps;
import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

	/**
	 * 并批拒绝码：新建任务首次条件必须单独下发 {@code set_condition}（系统拒绝，模型面回执；
	 * 用户面不发失败卡，见 {@code SpToolCallingManager#systemRejectedWithoutUserFrames}）。
	 */
	public static final String BATCH_SPLIT_CODE = "SET_CONDITION_BATCH_SPLIT_REQUIRED";

	/** 推进工具名（唯一注册表）。 */
	private static final String TOOL_CONFIRM_STEP = AiToolNames.CONFIRM_STEP;

	private static final ObjectMapper JSON = new ObjectMapper();

	private final TaskService taskService;

	/** 确定性条件工作流（已知结构化条件直接落库，不经过模型/不弹表）。 */
	private final ExportConditionWorkflow conditionWorkflow;

	/**
	 * 向导动作复核器（可选依赖）：{@code create_task(conditions=…)} 的候选落库前必须过与
	 * {@code set_condition} 同一份校验（定义存在、字段属于该定义、算子/取值合法）。未注入 = 不落库
	 * （fail-safe：退回三态收集，绝不把未校验的条件写成任务事实）。
	 */
	private WizardActionGuard wizardActionGuard;

	@Autowired
	public ConditionsForm(RunStore store, RunRegistry registry, ConfirmGate gate, MessageJsonCodec codec,
			AiProperties properties, TaskService taskService, ExportConditionWorkflow conditionWorkflow) {
		super(store, registry, gate, codec, properties);
		this.taskService = taskService;
		this.conditionWorkflow = conditionWorkflow == null ? new ExportConditionWorkflow(taskService)
				: conditionWorkflow;
	}

	/** 兼容构造器（单测/轻装配）：工作流按同一个 TaskService 现场装配。 */
	public ConditionsForm(RunStore store, RunRegistry registry, ConfirmGate gate, MessageJsonCodec codec,
			AiProperties properties, TaskService taskService) {
		this(store, registry, gate, codec, properties, taskService, new ExportConditionWorkflow(taskService));
	}

	/** 可选注入（生产装配）：候选条件落库前的同一份向导动作校验。 */
	@Autowired(required = false)
	public void setWizardActionGuard(WizardActionGuard guard) {
		this.wizardActionGuard = guard;
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
	 * <b>进入 QUERY_COND 后宿主直接挂一次条件表单</b>（不经过模型询问/选择）。
	 *
	 * <p>触发点：导航/工具批上下文刷新后任务权威步骤已到 {@code QUERY_COND}，以及续跑交棒前。
	 * 行为：
	 * <ul>
	 * <li>任务已有条件：事实已由前端结构化 set_condition/确认门落库，直接推进；</li>
	 * <li>明确不设（{@code "{}"} 或 EXPORT 的空白）：直接推进；</li>
	 * <li>仍 {@code UNDECIDED}：宿主挂一次非必填 {@code SET_CONDITIONS} 表单；</li>
	 * <li>已经收集过（非空/留空/取消）或同一缺口已挂过：不自动重问；</li>
	 * <li>坏 JSON/残缺结构：不弹表（交给向导修正/模型结构化路径），零副作用。</li>
	 * </ul>
	 * 不改变 {@code start_export} 确认门，也不自动执行/下载。
	 */
	public boolean parkIfDueAtQueryCond(String runId, AiContext context, List<Message> history) {
		if (runId == null || context == null || history == null || !parkEnabled()) {
			return false;
		}
		try {
			Long taskId = context.getTaskId();
			if (taskId == null) {
				return false;
			}
			Task task = readTask(taskId);
			if (task == null || task.getType() != Task.TaskType.EXPORT
					|| !TaskSteps.QUERY_COND.equals(TaskSteps.normalize(task.getCurrentStep()))) {
				return false;
			}
			if (task.getItems() == null || task.getItems().isEmpty()) {
				return false;
			}
			// 本轮 create_task(conditions=…) 的结构化条件：先按同一份校验落库，再判三态
			task = applyCreateTaskConditions(runId, taskId, context, task);
			ExportConditionWorkflow.Plan plan = this.conditionWorkflow.plan(task);
			if (plan.malformed()) {
				// 坏结构不弹表：由向导修正或模型走 set_condition 结构化路径
				return false;
			}
			if (plan.undecided().isEmpty()) {
				// 任务已有条件（已落库）/ 明确不设：直接推进，不弹表
				return false;
			}
			ConditionFeedback feedback = feedbackOf(runId, taskId, createTaskBoundary(context));
			if (feedback.cancelled() || feedback.blank() || feedback.nonEmpty()) {
				// 已收集/留空/取消：不自动重问（取消恢复通道由 confirm_step 拒绝说明）
				return false;
			}
			String toolCallId = toolCallId(runId, taskId, plan.undecided(), taskDefCodesFor(task));
			if (alreadyIssued(runId, toolCallId)) {
				return false;
			}
			Optional<String> arguments = formArguments(buildForm(ReplyLanguage.current()));
			if (arguments.isEmpty()) {
				return false;
			}
			log.info("进入 QUERY_COND 条件未决定，宿主直接挂一次条件表单 runId={} taskId={} defs={}", runId, taskId,
					plan.undecided());
			return park(runId, history, frontendForm(toolCallId, arguments.get()));
		}
		catch (RuntimeException ex) {
			log.warn("进入 QUERY_COND 的条件表单未能挂起（按既有路径继续）runId={}：{}", runId, ex.getMessage());
			return false;
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
			if (TaskSteps.SELECT_DEFS.equals(step) && TaskSteps.EXPORT.equals(target)) {
				log.info("confirm_step 试图从 SELECT_DEFS 直接跳到 EXPORT，零副作用拒绝 runId={} taskId={}", runId,
						taskId);
				return ConfirmStepDecision.reject(ErrorCodes.STEP_NOT_IN_SEQUENCE,
						"不能从 SELECT_DEFS 直接跳到 EXPORT（工具未执行）",
						List.of("当前步骤 SELECT_DEFS，EXPORT 不是下一步：直接跳步会跳过 QUERY_COND 与条件收集",
								"下一步：先 confirm_step（不传 step）推进到 QUERY_COND，按条件链处理后再推进到 EXPORT"));
			}
			if (!TaskSteps.QUERY_COND.equals(step)) {
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

	/**
	 * 把本轮结构化 {@code create_task(conditions=…)} 的候选条件落库（<b>在 select_definitions 之后</b>）。
	 *
	 * <p><b>绑定范围（终审 Critical）</b>：候选只属于 {@code create_task} 创建的那个任务，且只写
	 * {@code create_task(defCodes=…)} 给出的配置项 —— 上下文任务号必须与
	 * {@code extra.createTaskTaskId} 一致、条目 {@code defCode} 必须在 {@code extra.createTaskDefCodes} 里；
	 * 因此切到别的任务、或后续新增的配置项都不会被写入。
	 *
	 * <p>校验与 {@code set_condition} 同一份（{@link WizardActionGuard}）：定义存在、字段属于该定义、
	 * 算子/取值合法、非空条件；不通过 ⇒ <b>不落库</b>（绝不把未校验条件写成任务事实），交回三态判定。
	 * 已有 {@code conditionJson} 的条目跳过（不覆盖用户在向导里的手工条件）。
	 *
	 * <p><b>落库成功后清理候选</b>（{@code createTaskConditions}/{@code createTaskTaskId}）：下一批不再重复
	 * 落库，也不再据此压掉条件表单；全部候选条目都有条件事实时才清理（部分失败保留候选，下一轮可重试）。
	 *
	 * <p>落库后重读任务，让 {@link ExportConditionWorkflow#plan} 看到 {@code SET} 事实 ——
	 * 已知条件因此不再弹条件表；未传条件时本方法不写任何东西（宿主仍只弹一张）。
	 */
	private Task applyCreateTaskConditions(String runId, long taskId, AiContext context, Task task) {
		if (this.wizardActionGuard == null || task == null || context == null || context.getExtra() == null) {
			return task;
		}
		Map<String, Object> extra = context.getExtra();
		Object raw = extra.get(RunContextRefresher.EXTRA_CREATE_TASK_CONDITIONS);
		if (!(raw instanceof Map<?, ?> conditions) || conditions.isEmpty()) {
			return task;
		}
		// 只认创建它的任务：切任务后旧候选不得写到新任务
		Long boundTaskId = longOf(extra.get(RunContextRefresher.EXTRA_CREATE_TASK_TASK_ID));
		if (boundTaskId == null || !boundTaskId.equals(taskId)) {
			return task;
		}
		// 只认 create_task 明确给出的配置项：后续新增的条目不在候选范围
		List<String> candidateDefCodes = stringList(extra.get(RunContextRefresher.EXTRA_CREATE_TASK_DEF_CODES));
		if (candidateDefCodes.isEmpty()) {
			return task;
		}
		List<TaskItem> items = task.getItems();
		if (items == null || items.isEmpty()) {
			return task;
		}
		String conditionsJson;
		try {
			conditionsJson = JSON.writeValueAsString(conditions);
		}
		catch (Exception ex) {
			log.warn("create_task 的结构化条件无法序列化（不落库）runId={} taskId={}：{}", runId, taskId,
					ex.getMessage());
			return task;
		}
		boolean wrote = false;
		for (TaskItem item : items) {
			String defCode = item == null ? null : item.getDefCode();
			if (defCode == null || defCode.isBlank() || !candidateDefCodes.contains(defCode)) {
				continue;
			}
			if (item.getConditionJson() != null && !item.getConditionJson().isBlank()) {
				continue;
			}
			String probe;
			try {
				Map<String, Object> args = new LinkedHashMap<>();
				args.put("defCode", defCode);
				args.put("conditions", conditions);
				probe = JSON.writeValueAsString(args);
			}
			catch (Exception ex) {
				continue;
			}
			FrontendToolGuard.Verdict verdict = this.wizardActionGuard
				.inspectArgumentsFor(AiToolNames.SET_CONDITION, probe);
			if (!verdict.accepted()) {
				log.warn("create_task 的结构化条件未通过 set_condition 同一校验，不落库 runId={} taskId={} defCode={} reasons={}",
						runId, taskId, defCode, verdict.reasons());
				continue;
			}
			try {
				this.taskService.setCondition(taskId, defCode, conditionsJson);
				wrote = true;
			}
			catch (RuntimeException ex) {
				log.warn("create_task 的结构化条件落库失败（交回三态收集）runId={} taskId={} defCode={}：{}", runId,
						taskId, defCode, ex.getMessage());
			}
		}
		Task refreshed = wrote ? readTask(taskId) : task;
		Task effective = refreshed == null ? task : refreshed;
		if (wrote && candidateCovered(effective, candidateDefCodes)) {
			extra.remove(RunContextRefresher.EXTRA_CREATE_TASK_CONDITIONS);
			extra.remove(RunContextRefresher.EXTRA_CREATE_TASK_TASK_ID);
		}
		return effective;
	}

	/** 候选配置项是否都已有条件事实（已设置/明确不设）——全部覆盖才清理候选。 */
	private static boolean candidateCovered(Task task, List<String> candidateDefCodes) {
		Map<String, Boolean> covered = new LinkedHashMap<>();
		if (task != null && task.getItems() != null) {
			for (TaskItem item : task.getItems()) {
				if (item == null || item.getDefCode() == null) {
					continue;
				}
				covered.put(item.getDefCode(), item.getConditionJson() != null && !item.getConditionJson().isBlank());
			}
		}
		for (String defCode : candidateDefCodes) {
			if (!Boolean.TRUE.equals(covered.get(defCode))) {
				return false;
			}
		}
		return true;
	}

	/** extra 里的字符串列表（非文本/空白项忽略；数字文本保持原样）。 */
	private static List<String> stringList(Object raw) {
		if (!(raw instanceof List<?> list)) {
			return List.of();
		}
		List<String> out = new ArrayList<>();
		for (Object element : list) {
			if (element == null) {
				continue;
			}
			String value = String.valueOf(element).trim();
			if (!value.isEmpty() && !out.contains(value)) {
				out.add(value);
			}
		}
		return out;
	}

	/** 服务端 extra 里的任务号（数字或数字文本；其它形态 = 没有绑定）。 */
	private static Long longOf(Object raw) {
		if (raw instanceof Number number) {
			return number.longValue();
		}
		if (raw == null) {
			return null;
		}
		try {
			return Long.valueOf(String.valueOf(raw).trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	/**
	 * <b>新建任务的 {@code set_condition} 首次硬边界</b>：当上下文指向本 run 由
	 * {@code create_task} 创建的任务、任务尚无条件、且既没有 {@code create_task(conditions=…)}
	 * 候选、也没有本轮条件表有效回灌时，第一次 {@code set_condition} <b>不得直接下发前端</b> ——
	 * 先由宿主给这个新任务挂一次它自己的可选条件表（复用既有挂起面，不弹内部错误卡）。
	 *
	 * <p>放行（任一成立）：非本 run 新建任务（既有任务手工设置不受影响）／有结构化候选（
	 * {@code create_task} 带 conditions，宿主会在 QUERY_COND 落库）／任务条目已有条件或明确不设／
	 * 本轮条件表已有效回灌（留空或非空）／条件表已取消（{@code set_condition} 是恢复通道）。
	 * 条件表仍 PENDING ⇒ 零副作用拒绝并等待填写（不允许绕过）。
	 *
	 * <p><b>并批防护</b>：本边界需要挂表时，只接受“本批恰好一个 {@code set_condition} 且没有其它调用”
	 * 的形态 —— 多调用批里挂表单拿不到同批其它调用的工具响应（历史会出现无配对的 tool_calls），
	 * 因此改为<b>零副作用拒绝</b>并要求模型下一批单独下发；批内若还有非 READ 类调用同样拒绝
	 * （不允许用并批绕过新任务的首次条件收集）。
	 *
	 * @param batchSize         本批待决条目数（含本次）
	 * @param setConditionCalls 本批 {@code set_condition} 条目数
	 * @param foreignWrite      本批是否还有 {@code set_condition} 之外的写类调用
	 */
	public SetConditionDecision inspectSetCondition(String runId, AiContext context, PendingToolCall pending,
			int batchSize, int setConditionCalls, boolean foreignWrite, List<Message> history) {
		if (!parkEnabled() || runId == null || context == null || pending == null || history == null) {
			return SetConditionDecision.allow();
		}
		if (!AiToolNames.SET_CONDITION.equals(pending.getName())) {
			return SetConditionDecision.allow();
		}
		// 入参不合法交给既有 WizardActionGuard（它在本循环稍后统一零副作用拒绝）：本边界只约束
		// "参数合法但属于新建任务首次手写条件"的情况，不替它做参数校验，也不抢先把非法调用挂成表。
		if (this.wizardActionGuard != null
				&& !this.wizardActionGuard.inspectArgumentsFor(AiToolNames.SET_CONDITION,
						pending.getArguments()).accepted()) {
			return SetConditionDecision.allow();
		}
		Long taskId = context.getTaskId();
		if (taskId == null) {
			return SetConditionDecision.allow();
		}
		Map<String, Object> extra = context.getExtra();
		Long createdTaskId = extra == null ? null
				: longOf(extra.get(RunContextRefresher.EXTRA_CREATE_TASK_TASK_ID));
		if (createdTaskId == null || !createdTaskId.equals(taskId)) {
			// 既有任务：手工设置条件走原有路径
			return SetConditionDecision.allow();
		}
		Object candidate = extra.get(RunContextRefresher.EXTRA_CREATE_TASK_CONDITIONS);
		if (candidate instanceof Map<?, ?> conditions && !conditions.isEmpty()) {
			// create_task 已带结构化条件：宿主负责落库，不弹表
			return SetConditionDecision.allow();
		}
		Task task = readTask(taskId);
		if (task == null || task.getType() != Task.TaskType.EXPORT
				|| task.getItems() == null || task.getItems().isEmpty()) {
			return SetConditionDecision.allow();
		}
		if (!TaskSteps.QUERY_COND.equals(TaskSteps.normalize(task.getCurrentStep()))) {
			// 不在条件步：交给既有步骤/披露边界（前端会拒），不在错误步骤挂表
			return SetConditionDecision.allow();
		}
		if (hasAnyCondition(task)) {
			// 已有条件事实（含明确不设 "{}"）
			return SetConditionDecision.allow();
		}
		long boundaryMs = createTaskBoundary(context);
		ConditionFeedback feedback = feedbackOf(runId, taskId, boundaryMs);
		if (feedback.blank() || feedback.nonEmpty()) {
			// 本轮条件表已有效回灌：set_condition 落地用户给出的答案
			return SetConditionDecision.allow();
		}
		if (conditionFormPending(runId, taskId, boundaryMs)) {
			log.info("新建任务的条件表仍待填写，set_condition 零副作用拒绝 runId={} taskId={}", runId, taskId);
			return SetConditionDecision.reject(ErrorCodes.FORM_SLOTS_ALREADY_KNOWN,
					"条件表已下发、尚未填写，set_condition 未执行（未下发前端）",
					List.of("这是本轮新建任务第一次设置条件：先让用户在条件表里填写或留空",
							"条件表回灌后再按用户答案落地；不要用 set_condition 绕过"));
		}
		if (feedback.cancelled()) {
			// 取消后 set_condition 是用户/模型的恢复通道，不再挂第二张表
			return SetConditionDecision.allow();
		}
		ExportConditionWorkflow.Plan plan = this.conditionWorkflow.plan(task);
		if (plan.malformed() || plan.undecided().isEmpty()) {
			return SetConditionDecision.allow();
		}
		// 并批防护：需要挂表时只接受"本批只有这一条调用"，否则零副作用拒绝并要求单独下发
		if (setConditionCalls > 1 || batchSize > 1 || foreignWrite) {
			String reason = foreignWrite
					? "本批含写类调用：新建任务首次筛选条件必须单独下发 set_condition（本批未执行、零副作用）"
					: setConditionCalls > 1
							? "本批有多个 set_condition：新建任务首次筛选条件必须单独确认（本批未执行、零副作用）"
							: "本批还有其它调用：新建任务首次筛选条件必须单独下发 set_condition，"
									+ "宿主要先给该任务挂一次条件表（本批未执行、零副作用）";
			log.info("新建任务首次 set_condition 被并批拦截 runId={} taskId={} batch={} setCalls={} foreignWrite={}",
					runId, taskId, batchSize, setConditionCalls, foreignWrite);
			return SetConditionDecision.reject(BATCH_SPLIT_CODE,
					"新建任务的首次筛选条件不能与其它调用并批，set_condition 未执行（未下发前端）",
					List.of(reason, "下一批只发这一条 set_condition；宿主会先挂一次该任务的条件表再落地"));
		}
		String toolCallId = toolCallId(runId, taskId, plan.undecided(), taskDefCodesFor(task));
		if (alreadyIssued(runId, toolCallId)) {
			return SetConditionDecision.allow();
		}
		Optional<String> arguments = formArguments(buildForm(ReplyLanguage.current()));
		if (arguments.isEmpty()) {
			return SetConditionDecision.allow();
		}
		log.info("新建任务首次 set_condition：先挂一次该任务自己的条件表 runId={} taskId={} defs={}", runId, taskId,
				plan.undecided());
		boolean parked = park(runId, history, frontendForm(toolCallId, arguments.get()));
		return parked ? SetConditionDecision.parkedDecision() : SetConditionDecision.allow();
	}

	/** {@code set_condition} 首次边界结论：放行 / 已挂起条件表单 / 零副作用拒绝。 */
	public record SetConditionDecision(Outcome outcome, String code, String message, List<String> reasons) {

		public enum Outcome {
			ALLOW, PARKED, REJECTED
		}

		public SetConditionDecision {
			reasons = reasons == null ? List.of() : List.copyOf(reasons);
		}

		static SetConditionDecision allow() {
			return new SetConditionDecision(Outcome.ALLOW, null, null, List.of());
		}

		static SetConditionDecision parkedDecision() {
			return new SetConditionDecision(Outcome.PARKED, null, null, List.of());
		}

		static SetConditionDecision reject(String code, String message, List<String> reasons) {
			return new SetConditionDecision(Outcome.REJECTED, code, message, reasons);
		}

		public boolean parked() {
			return this.outcome == Outcome.PARKED;
		}

		public boolean rejected() {
			return this.outcome == Outcome.REJECTED;
		}
	}

	/** 任务条目上是否已有任何条件事实（非空白 {@code conditionJson}，含明确不设 {@code "{}"}）。 */
	private static boolean hasAnyCondition(Task task) {
		if (task == null || task.getItems() == null) {
			return false;
		}
		for (TaskItem item : task.getItems()) {
			if (item != null && item.getConditionJson() != null && !item.getConditionJson().isBlank()) {
				return true;
			}
		}
		return false;
	}

	/** 本 run 是否有一张仍待填写的条件表（宿主或模型下发；取消/已回灌不算）。 */
	private boolean conditionFormPending(String runId, long taskId, long boundaryMs) {
		List<PendingToolCall> pendings;
		try {
			pendings = this.store.pendingsLenient(runId);
		}
		catch (RuntimeException ex) {
			log.warn("读取待决条件表失败（按无待填写表处理）runId={}：{}", runId, ex.getMessage());
			return false;
		}
		for (PendingToolCall form : pendings == null ? List.<PendingToolCall>of() : pendings) {
			if (form != null && PendingToolCall.PENDING.equals(form.getStatus())
					&& GenerativeFormRules.TOOL_NAME.equals(form.getName())
					&& isConditionForm(form.getArguments())
					&& belongsToTask(form.getToolCallId(), runId, taskId)
					&& (boundaryMs <= 0 || requestedAtOf(form) >= boundaryMs)) {
				return true;
			}
		}
		return false;
	}

	private ConfirmStepDecision conditionGate(String runId, long taskId, Task task, AiContext context,
			PendingToolCall pending, List<Message> history) {
		// ① 确定性工作流：可信事实只有任务 conditionJson + 前端结构化回灌；不读自由文本槽位。
		// 坏 JSON/残缺结构明确拒绝，不按全量放行。
		// 本轮 create_task(conditions=…) 的结构化条件先按同一份校验落库，再判三态。
		task = applyCreateTaskConditions(runId, taskId, context, task);
		ExportConditionWorkflow.Plan plan = this.conditionWorkflow.plan(task);
		if (plan.malformed()) {
			log.info("confirm_step 时条件结构残缺，零副作用拒绝 runId={} taskId={}", runId, taskId);
			return ConfirmStepDecision.reject(ErrorCodes.CONDITION_JSON_INVALID,
					"条件结构无法解析，confirm_step 未执行（未向前端推进）",
					List.of("任务条目里存在坏 JSON/未知键/残缺结构，不能按全量放行",
							"下一步：让用户在向导里重新设置合法条件（或清空为明确全量）后再 confirm_step"));
		}
		// ② 逐项核对：全部已有答案（已设置/明确不设）⇒ 放行（不看历史回灌）
		if (plan.undecided().isEmpty()) {
			return ConfirmStepDecision.allow();
		}
		ConditionFeedback feedback = feedbackOf(runId, taskId, createTaskBoundary(context));
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
			if (!persistExplicitUnset(taskId, plan.undecided())) {
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
		String toolCallId = toolCallId(runId, taskId, plan.undecided(),
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
				plan.undecided());
		boolean parked = park(runId, history, frontendForm(toolCallId, arguments.get()));
		return parked ? ConfirmStepDecision.parkedDecision() : ConfirmStepDecision.allow();
	}

	/**
	 * 本轮 {@code SET_CONDITIONS} 表单的回灌事实（模型表单与宿主表单<b>同一份</b>：
	 * 都持久化在同一个 run 的待决条目里，跨实例、跨续跑都读得到）。
	 *
	 * <p><b>任务时间边界</b>：{@code boundaryMs > 0} 时只认该时刻<b>之后产生</b>的表单
	 * （{@code requestedAtMs}，旧条目回落 {@code resolvedAtMs}）—— 上一个任务（A）的回灌/取消
	 * 不会被新任务（B）复用；{@code boundaryMs == 0}（非本 run 新建任务）行为不变。
	 */
	record ConditionFeedback(boolean cancelled, boolean blank, boolean nonEmpty, boolean handled) {

		static ConditionFeedback none() {
			return new ConditionFeedback(false, false, false, false);
		}
	}

	ConditionFeedback feedbackOf(String runId, long taskId, long boundaryMs) {
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
			if (boundaryMs > 0 && requestedAtOf(form) < boundaryMs) {
				// 本任务创建之前的历史表单（A 的回灌/取消）一律不算
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
		String text = conditionTextOf(latest.getArguments(), latest.getResultText());
		if (text == null) {
			return ConditionFeedback.none();
		}
		if (text.isBlank()) {
			return new ConditionFeedback(false, true, false, false);
		}
		long sinceMs = Math.max(boundaryMs, latest.getResolvedAtMs());
		return new ConditionFeedback(false, false, true, setConditionPersistedSince(runId, sinceMs));
	}

	/** 表单产生时刻（{@code requestedAtMs}；旧条目没有该值时回落 {@code resolvedAtMs}）。 */
	private static long requestedAtOf(PendingToolCall form) {
		return form.getRequestedAtMs() > 0 ? form.getRequestedAtMs() : form.getResolvedAtMs();
	}

	/** 本 run 新建任务（B）的创建结算时刻；没有绑定（既有任务/旧快照）= 0（不做时间过滤）。 */
	private static long createTaskBoundary(AiContext context) {
		if (context == null || context.getExtra() == null) {
			return 0L;
		}
		Long value = longOf(context.getExtra().get(RunContextRefresher.EXTRA_CREATE_TASK_REQUESTED_AT));
		return value == null || value <= 0 ? 0L : value;
	}

	/**
	 * 该表单回灌后是否已有 <b>FRONTEND set_condition 回灌</b>（待决条目是事实源；不读 ai:ledger）。
	 *
	 * <p>前端工具执行后由 ConfirmGate 写成 {@code FRONTEND_RESULT}；即使台账缺失/未写，
	 * 也应以真实回灌认定"文本已被处理"。读失败 = 未处理（fail-closed）。
	 */
	private boolean setConditionPersistedSince(String runId, long sinceMs) {
		List<PendingToolCall> pendings;
		try {
			pendings = this.store.pendingsLenient(runId);
		}
		catch (RuntimeException ex) {
			log.warn("读取待决条目失败，非空条件回灌按未处理处理 runId={}：{}", runId, ex.getMessage());
			return false;
		}
		for (PendingToolCall pending : pendings == null ? List.<PendingToolCall>of() : pendings) {
			if (pending == null || !AiToolNames.SET_CONDITION.equals(pending.getName())
					|| !PendingToolCall.FRONTEND_RESULT.equals(pending.getStatus())) {
				continue;
			}
			if (pending.effectiveOutcome() != com.example.configmgr.ai.tool.ToolOutcome.OK) {
				continue;
			}
			if (pending.getResolvedAtMs() >= sinceMs) {
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
		return GenerativeFormRules.isPurpose(form, FormPurposes.SET_CONDITIONS);
	}

	/**
	 * 条件表回灌的<b>文本值</b>：从该表单 {@code arguments.form.fields} 里定位实际文本字段 key
	 * （{@code type=text}，不硬编码字段名 —— 模型可能用 {@code conditionText}/{@code filter_text} 等），
	 * 再从 {@code resultText} 的同一个 key 读值。
	 *
	 * <p>取值口径（不猜）：文本 ⇒ 原样（空串 = 留空）；JSON {@code null} ⇒ 空串（留空）；
	 * 其它形状（对象/数组/数字/布尔）⇒ {@code null}（按未收集）。没有可用文本字段时兼容宿主/
	 * 旧快照的 {@link #FIELD_KEY}（{@code conditions}）。
	 *
	 * @return {@code null} = 不是可识别的文本回灌（按未收集）；{@code ""} = 留空
	 */
	private static String conditionTextOf(String argumentsJson, String resultText) {
		JsonNode values = parseJson(resultText);
		if (values == null || !values.isObject()) {
			return null;
		}
		List<String> declaredKeys = textFieldKeys(argumentsJson);
		if (!declaredKeys.isEmpty()) {
			for (String key : declaredKeys) {
				if (values.has(key)) {
					return textValueOf(values.get(key));
				}
			}
			// 声明了文本字段但回灌里没有对应键：按未收集（不猜宿主字段）
			return null;
		}
		JsonNode legacy = values.get(FIELD_KEY);
		return legacy == null ? null : textValueOf(legacy);
	}

	/** 表单 schema 里的文本字段 key（{@code form.fields[].type=text}，按声明顺序）；没有返回空列表。 */
	private static List<String> textFieldKeys(String argumentsJson) {
		JsonNode arguments = parseJson(argumentsJson);
		JsonNode form = arguments == null ? null : arguments.get("form");
		JsonNode fields = form == null ? null : form.get("fields");
		if (fields == null || !fields.isArray()) {
			return List.of();
		}
		List<String> keys = new ArrayList<>();
		for (JsonNode field : fields) {
			if (field == null || !field.isObject() || !"text".equals(field.path("type").asText(""))) {
				continue;
			}
			String key = field.path("key").asText("");
			if (!key.isBlank() && !keys.contains(key)) {
				keys.add(key);
			}
		}
		return keys;
	}

	/** 文本/空值 ⇒ 文本（null ⇒ 空串）；其它 JSON 形状 ⇒ null（未收集）。 */
	private static String textValueOf(JsonNode value) {
		if (value == null || value.isNull()) {
			return "";
		}
		return value.isTextual() ? value.asText() : null;
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
		return target.isEmpty() || TaskSteps.EXPORT.equals(TaskSteps.normalize(target));
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

	/** 单个非必填 text 框（形状与 {@link FormPurposes#SET_CONDITIONS} 一致）。 */
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
		form.put("purpose", FormPurposes.SET_CONDITIONS);
		form.put("title", english ? TITLE_EN : TITLE);
		form.put("fields", List.of(field));
		return form;
	}

	private static String trim(String step) {
		return step == null ? "" : step.trim();
	}

}
