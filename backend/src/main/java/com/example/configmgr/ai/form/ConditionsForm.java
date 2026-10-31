package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.RunContextRefresher;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CreateTaskDraftStore;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * <b>导出筛选条件的草案与收集闭环</b>：AI 对话里产生的条件候选<b>只存 Redis</b>（运行上下文 /
 * 待决条目），业务库的 {@code conditionJson} 只能由前端在「下一步」或启动作业前经
 * {@code persistStepData} 提交。本类因此<b>不写任何业务库</b>（不调用 {@code TaskService#setCondition}）。
 *
 * <h2>三条路径</h2>
 * <ol>
 * <li>{@code create_task(conditions=…)} 的候选：进入 {@code QUERY_COND} 后，按 {@code defCodes}
 * 逐项用与 {@code set_condition} 同一份 {@link WizardActionGuard} 校验；合法项复用既有前端
 * {@code set_condition} 通道生成<b>页面草案</b>（确定性 {@code toolCallId} + 幂等），不弹
 * {@code SET_CONDITIONS} 表单；绑定错误/校验失败/下发失败只记 WARN，走内部失败口径，不转化为再次询问用户。
 * <b>候选来源</b>：{@code extra} 优先，缺失或绑定信息丢失时回落本轮已结算 {@code create_task}
 * 原始入参/会话级 Redis 草案（{@link CreateTaskDraftStore}：runId 解析会话号、字段 = taskId，
 * 跨轮存活）；读取异常按内部失败处理；</li>
 * <li>真正的未知条件：宿主只挂<b>一次</b>非必填自由文本框（{@code purpose=SET_CONDITIONS}）；
 * 已知候选、已有草案、已明确留空都不弹；</li>
 * <li>{@code confirm_step} 推进到 {@code EXPORT} 前复核：任务条目已落库条件、本轮已成功结算的
 * {@code set_condition} 草案（按 {@code defCode} 与任务绑定核对）、表单明确留空，三者都认可；
 * 非空表单回灌必须先由模型解析成草案才能确认；后端不代写 {@code "{}"}。</li>
 * </ol>
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

	/** 宿主下发 {@code set_condition} 页面草案的 {@code toolCallId} 前缀（run/task/defCode 全部编码在内）。 */
	public static final String DRAFT_TOOL_CALL_ID_PREFIX = "host-cond-draft-";

	/** 推进工具名（唯一注册表）。 */
	private static final String TOOL_CONFIRM_STEP = AiToolNames.CONFIRM_STEP;

	private static final ObjectMapper JSON = new ObjectMapper();

	private final TaskService taskService;

	/** 确定性条件工作流（只读任务事实；本类不落库）。 */
	private final ExportConditionWorkflow conditionWorkflow;

	/**
	 * 条件候选下发前的复核器（可选依赖）：{@code create_task(conditions=…)} 的候选必须过与
	 * {@code set_condition} 同一份校验（定义存在、字段属于该定义、算子/取值合法）。未注入 =
	 * 不生成草案（fail-safe：绝不把未校验的条件写成页面事实），走内部失败口径而不是再问用户。
	 */
	private WizardActionGuard wizardActionGuard;

	/**
	 * 会话级 create_task 候选草案（可选依赖）：{@code extra} 丢失/绑定信息丢失时的跨轮回落。
	 * 缺席 = 只用 {@code extra} 与本轮已结算 args（行为与改造前一致）。
	 */
	private CreateTaskDraftStore draftStore;

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

	/** 可选注入（生产装配）：候选条件下发页面草案前的同一份向导动作校验。 */
	@Autowired(required = false)
	public void setWizardActionGuard(WizardActionGuard guard) {
		this.wizardActionGuard = guard;
	}

	/** 可选注入（生产装配）：任务级 create_task 候选草案的跨轮回落。 */
	@Autowired(required = false)
	public void setCreateTaskDraftStore(CreateTaskDraftStore draftStore) {
		this.draftStore = draftStore;
	}

	/** 宿主为某个 run 的某个任务生成的条件表单 {@code toolCallId}（确定性 = 天然去重）。 */
	public static String toolCallId(String runId, long taskId) {
		return TOOL_CALL_ID_PREFIX + (runId == null ? "" : runId + "-") + taskId;
	}

	/** 宿主为某个任务的某个配置项下发的 {@code set_condition} 草案 id（确定性 = 幂等去重）。 */
	static String draftToolCallId(String runId, long taskId, String defCode) {
		return DRAFT_TOOL_CALL_ID_PREFIX + (runId == null ? "" : runId + "-") + taskId + "-" + defCode;
	}

	/** 该 id 是否是本 run、本任务下发的草案（任务号边界匹配，避免 42 与 420 混淆）。 */
	private static boolean isDraftForTask(String toolCallId, String runId, long taskId) {
		if (toolCallId == null || !toolCallId.startsWith(DRAFT_TOOL_CALL_ID_PREFIX)) {
			return false;
		}
		return toolCallId.startsWith(DRAFT_TOOL_CALL_ID_PREFIX + (runId == null ? "" : runId + "-") + taskId + "-");
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
	 * <b>进入 QUERY_COND 后宿主按缺口推进条件</b>（不经过模型询问/选择、不写业务库）。
	 *
	 * <p>触发点：导航/工具批上下文刷新后任务权威步骤已到 {@code QUERY_COND}，以及续跑交棒前。
	 * 行为：
	 * <ul>
	 * <li>{@code create_task(conditions=…)} 候选合法且绑定当前任务/配置项：复用前端
	 *     {@code set_condition} 通道下发页面草案（逐 defCode、确定性 id、幂等），不弹条件表；</li>
	 * <li>候选绑定错误/校验失败/下发失败：只记 WARN，内部失败口径（不弹表、不写库、不再问用户）；</li>
	 * <li>任务已有条件或本轮已有成功草案：直接推进；</li>
	 * <li>表单已明确留空/取消/非空回灌：不自动重问，也不代写 {@code "{}"}；</li>
	 * <li>仍 {@code UNDECIDED}：{@code allowUnknownForm=false} 时先交模型结构化，否则宿主挂一次非必填
	 *     {@code SET_CONDITIONS} 表单；</li>
	 * <li>坏 JSON/残缺结构：不弹表（交给向导修正/模型结构化路径），零副作用。</li>
	 * </ul>
	 * 不改变 {@code start_export} 确认门，也不自动执行/下载。
	 */
	public boolean parkIfDueAtQueryCond(String runId, AiContext context, List<Message> history) {
		return parkIfDueAtQueryCond(runId, context, history, true);
	}

	public boolean parkIfDueAtQueryCond(String runId, AiContext context, List<Message> history,
			boolean allowUnknownForm) {
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
			long boundary = createTaskBoundary(context);
			DraftFacts drafts = draftFacts(runId, task, boundary);
			ExportConditionWorkflow.Plan plan = this.conditionWorkflow.plan(task);
			if (plan.malformed()) {
				// 坏结构不弹表：由向导修正或模型走 set_condition 结构化路径
				return false;
			}
			ConditionFeedback feedback = feedbackOf(runId, taskId, boundary, plan.undecided());
			if (feedback.blank() || feedback.nonEmpty()) {
				// 用户已经给出答案（留空 = 全量；非空 = 待模型解析）：不得用 create_task 候选覆盖，
				// 也不自动重问。留空/非空都交给 confirm_step 与前端 persistStepData，后端零写。
				return false;
			}
			CandidateDispatch candidates = dispatchCreateTaskCandidates(runId, taskId, context, task, drafts);
			if (!candidates.drafts().isEmpty()) {
				return parkAll(runId, history, candidates.drafts());
			}
			if (candidates.failure()) {
				// 内部失败口径：不交代表单、不弹表、不写库；confirm_step 会按同一口径拒绝
				log.warn("create_task 条件候选无法生成页面草案（内部失败，不弹条件表）runId={} taskId={}", runId,
						taskId);
				return false;
			}
			List<String> remaining = subtract(plan.undecided(), drafts.settled());
			if (remaining.isEmpty()) {
				// 任务已有条件（已落库）/ 本轮已有成功草案：直接推进，不弹表
				return false;
			}
			if (attemptedAny(remaining, drafts)) {
				// 草案已下发但执行失败：内部失败，不再询问用户
				log.warn("条件草案执行失败，宿主不再弹条件表 runId={} taskId={} defs={}", runId, taskId, remaining);
				return false;
			}
			if (feedback.cancelled()) {
				// 取消：不自动重问（恢复通道由 confirm_step 拒绝说明）
				return false;
			}
			if (!allowUnknownForm) {
				// 刚进入条件步骤：只披露查定义、设置条件与推进，先让模型完成结构化。
				Map<String, Object> extra = new LinkedHashMap<>(context.getExtra());
				extra.put(AiContext.EXTRA_CONDITION_STRUCTURE_HANDOFF, Boolean.TRUE);
				context.setExtra(extra);
				return false;
			}
			String toolCallId = toolCallId(runId, taskId, remaining, taskDefCodesFor(task));
			if (alreadyIssued(runId, toolCallId)) {
				return false;
			}
			Optional<String> arguments = formArguments(buildForm(ReplyLanguage.current()));
			if (arguments.isEmpty()) {
				return false;
			}
			log.info("进入 QUERY_COND 条件未决定，宿主直接挂一次条件表单 runId={} taskId={} defs={}", runId, taskId,
					remaining);
			return park(runId, history, frontendForm(toolCallId, arguments.get()));
		}
		catch (RuntimeException ex) {
			log.warn("进入 QUERY_COND 的条件表单未能挂起（按既有路径继续）runId={}：{}", runId, ex.getMessage());
			return false;
		}
	}

	/**
	 * 统一复核 {@code confirm_step}：
	 * <ol>
	 * <li><b>禁止跳步</b>：{@code SELECT_DEFS} 不能直接点名 {@code EXPORT}（会跳过 QUERY_COND 与条件收集）；</li>
	 * <li><b>合法候选</b>：{@code create_task(conditions=…)} 的候选先下发成前端 {@code set_condition}
	 * 页面草案（不写库、不弹表）；绑定/校验失败走内部失败口径，不转化成再次询问用户；</li>
	 * <li><b>草案事实</b>：本轮已成功结算的 {@code set_condition} 草案（按 defCode 与任务绑定核对）
	 * 与任务条目已落库条件一样认可；不要求 {@code conditionJson} 提前存在；</li>
	 * <li><b>表单回灌</b>：明确留空允许推进（前端 {@code persistStepData} 提交 {@code {}}）；
	 * 非空文本必须已按表单覆盖的 defCode 解析成草案，否则零副作用拒绝并指回 {@code set_condition}；</li>
	 * <li><b>逐项核对</b>：真正未知的配置项才挂一次可选条件表单；取消过的表单不换卡重问。</li>
	 * <li><b>收集失败不得 allow（F3）</b>：{@code park} 未启用、挂起写失败、读任务异常、同一缺口的
	 * 条件表已存在且未结算 —— 条件仍 {@code UNDECIDED} 时一律 {@code TOOL_EXECUTION_FAILED}
	 * 零副作用拒绝；只有已明确 {@code SET}/{@code EXPLICIT_UNSET}/已结算草案/明确留空才放行。</li>
	 * <li><b>混批不 park（F4）</b>：{@code allowPark=false} 时零副作用拒绝并明确"先单独调用
	 * confirm_step"（host park 会先外置整批历史、吞掉同批其它调用）。</li>
	 * </ol>
	 */
	public ConfirmStepDecision inspectConfirmStep(String runId, AiContext context, PendingToolCall pending,
			List<Message> history) {
		return inspectConfirmStep(runId, context, pending, history, true);
	}

	/**
	 * 带"本批是否允许挂起"的复核（F4）。
	 *
	 * <p>{@code allowPark=false}（同一批还有其它工具）：<b>不</b>在 {@code confirm_step} 的复核里挂条件表 ——
	 * 宿主 park 会先于同批其它调用把整批历史外置，它们的确认卡/回执随之丢失。此时零副作用拒绝并
	 * 明确"先单独调用 confirm_step"，让模型分批。
	 */
	public ConfirmStepDecision inspectConfirmStep(String runId, AiContext context, PendingToolCall pending,
			List<Message> history, boolean allowPark) {
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
			Task task;
			try {
				task = readTask(taskId);
			}
			catch (RuntimeException ex) {
				if (!conditionGateExpected(context)) {
					log.warn("读取任务失败，confirm_step 按既有推进路径继续 runId={} taskId={}：{}", runId, taskId,
							ex.getMessage());
					return ConfirmStepDecision.allow();
				}
				// F3：读异常时无法确认条件状态 —— 条件链上必须 fail-closed，不能按全量放行
				log.warn("confirm_step 条件复核读取任务失败，零副作用拒绝 runId={} taskId={}：{}", runId, taskId,
						ex.getMessage());
				return readFailureDecision(taskId);
			}
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
			return conditionGate(runId, taskId, task, context, pending, history, allowPark);
		}
		catch (RuntimeException ex) {
			if (!conditionGateExpected(context)) {
				log.warn("confirm_step 的条件复核未能完成（按既有推进路径继续）runId={}：{}", runId, ex.getMessage());
				return ConfirmStepDecision.allow();
			}
			// F3：条件链上的意外异常同样 fail-closed（不能因为复核炸了就按全量推进）
			log.warn("confirm_step 条件复核异常，零副作用拒绝 runId={}：{}", runId, ex.getMessage());
			return internalConditionFailure("条件复核未能完成（内部执行失败），confirm_step 未执行（未向前端推进）",
					List.of("条件状态无法确认，不能按全量推进",
							"请稍后重试 confirm_step；若持续失败请检查任务服务/存储健康"));
		}
	}

	/**
	 * 上下文是否落在"导出 QUERY_COND"这个条件链上（读任务失败/复核异常时的 fail-closed 判据）。
	 *
	 * <p>只看上下文：任务本身读不出来时这是唯一可用的事实。导入没有 QUERY_COND 步，因此不会误伤。
	 */
	private static boolean conditionGateExpected(AiContext context) {
		if (context == null) {
			return false;
		}
		String step = TaskSteps.normalize(context.getStep());
		if (!TaskSteps.QUERY_COND.equals(step)) {
			return false;
		}
		String type = trim(context.getTaskType());
		return type.isEmpty() || Task.TaskType.EXPORT.name().equalsIgnoreCase(type);
	}

	private static ConfirmStepDecision readFailureDecision(long taskId) {
		return internalConditionFailure("条件复核读取任务失败（内部执行失败），confirm_step 未执行（未向前端推进）",
				List.of("任务 #" + taskId + " 的条件状态无法确认：不能按全量推进",
						"请稍后重试 confirm_step；若持续失败请检查任务服务/存储健康"));
	}

	/** 一个配置项的条件草案事实（全部来自 Redis 待决条目；业务库只读）。 */
	private record DraftFacts(Set<String> settled, Set<String> attempted, Set<String> issued) {

		static DraftFacts none() {
			return new DraftFacts(Set.of(), Set.of(), Set.of());
		}
	}

	/** 候选下发结果：{@code failure} = 绑定/校验/序列化内部失败（不弹表、不写库）。 */
	private record CandidateDispatch(boolean failure, List<PendingToolCall> drafts) {

		static CandidateDispatch none() {
			return new CandidateDispatch(false, List.of());
		}
	}

	/**
	 * 把本轮结构化 {@code create_task(conditions=…)} 的候选<b>下发成前端 {@code set_condition} 页面草案</b>
	 * （<b>不写业务库</b>；真正的提交由前端 {@code persistStepData} 在下一步完成）。
	 *
	 * <p><b>候选来源</b>（见 {@link #resolveCandidateSource}）：运行上下文 {@code extra} 优先；
	 * {@code extra} 缺失或绑定信息丢失时回落<b>本轮已结算 {@code create_task} 的原始入参</b>与
	 * <b>会话级 Redis 草案</b>（{@link CreateTaskDraftStore}：runId 解析会话号、字段 = taskId，
	 * 跨轮存活）。读取异常按内部失败处理（WARN、不弹条件表、不再问用户），不伪装成"条件未知"。
	 *
	 * <p><b>绑定范围</b>：候选只属于 {@code create_task} 创建的那个任务，且只处理
	 * {@code create_task(defCodes=…)} 给出的配置项 —— 上下文任务号必须与
	 * {@code extra.createTaskTaskId} 一致、条目 {@code defCode} 必须在候选 {@code defCodes} 里；
	 * 切到别的任务、或后续新增的配置项都不会被下发。
	 *
	 * <p>校验与 {@code set_condition} 同一份（{@link WizardActionGuard}）：定义存在、字段属于该定义、
	 * 算子/取值合法、非空条件；不通过 ⇒ <b>不下发</b>（绝不把未校验条件写成页面事实），
	 * 并标记内部失败（不弹条件表、不再问用户）。
	 *
	 * <p>已有 {@code conditionJson} 的条目、或已有草案（成功/失败/待回灌）的配置项跳过；
	 * 确定性 {@code toolCallId} 使重复调用天然幂等。多 defCodes 逐项下发，一次挂起整批。
	 */
	private CandidateDispatch dispatchCreateTaskCandidates(String runId, long taskId, AiContext context, Task task,
			DraftFacts drafts) {
		CandidateSource source = resolveCandidateSource(runId, taskId, context);
		if (source.failure()) {
			// 绑定错误/读取基础设施故障：内部失败口径（不弹表、不写库）；WARN 已在解析处留痕
			return new CandidateDispatch(true, List.of());
		}
		Map<?, ?> conditions = source.conditions();
		if (conditions.isEmpty()) {
			return CandidateDispatch.none();
		}
		if (this.wizardActionGuard == null) {
			log.warn("create_task 的结构化条件缺少 set_condition 复核器，不生成草案 runId={} taskId={}", runId,
					taskId);
			return new CandidateDispatch(true, List.of());
		}
		// 只认 create_task 明确给出的配置项：后续新增的条目不在候选范围
		List<String> candidateDefCodes = source.defCodes();
		if (candidateDefCodes.isEmpty()) {
			log.warn("create_task 的结构化条件缺少 defCodes 候选，不生成草案 runId={} taskId={}", runId, taskId);
			return new CandidateDispatch(true, List.of());
		}
		Map<String, TaskItem> itemsByDefCode = new LinkedHashMap<>();
		if (task != null && task.getItems() != null) {
			for (TaskItem item : task.getItems()) {
				if (item != null && item.getDefCode() != null && !item.getDefCode().isBlank()) {
					itemsByDefCode.put(item.getDefCode(), item);
				}
			}
		}
		boolean failure = false;
		List<PendingToolCall> draftsToPark = new ArrayList<>();
		Set<String> issued = new LinkedHashSet<>(drafts.issued());
		for (String defCode : candidateDefCodes) {
			TaskItem item = itemsByDefCode.get(defCode);
			if (item == null) {
				log.warn("create_task 条件候选的 defCode={} 不在当前任务条目里（绑定错误，不生成草案）runId={} taskId={}",
						defCode, runId, taskId);
				failure = true;
				continue;
			}
			if (item.getConditionJson() != null && !item.getConditionJson().isBlank()) {
				// 任务条目已有条件事实（含明确的 "{}"）：不覆盖用户/前端的答案
				continue;
			}
			if (issued.contains(defCode)) {
				// 已有草案（成功/失败/待回灌）：确定性 id 幂等，不重复下发
				continue;
			}
			String draftId = draftToolCallId(runId, taskId, defCode);
			if (alreadyIssued(runId, draftId)) {
				// 确定性 toolCallId 已在待决条目里：重复调用不得再下发一次
				continue;
			}
			String probe = conditionArguments(defCode, conditions);
			if (probe == null) {
				log.warn("create_task 的结构化条件无法序列化，不生成草案 runId={} taskId={} defCode={}", runId,
						taskId, defCode);
				failure = true;
				continue;
			}
			FrontendToolGuard.Verdict verdict = this.wizardActionGuard
				.inspectArgumentsFor(AiToolNames.SET_CONDITION, probe);
			if (!verdict.accepted()) {
				log.warn("create_task 的结构化条件未通过 set_condition 同一校验，不生成草案 runId={} taskId={} defCode={} reasons={}",
						runId, taskId, defCode, verdict.reasons());
				failure = true;
				continue;
			}
			PendingToolCall draft = frontendTool(draftId, AiToolNames.SET_CONDITION, probe);
			applyToolTimeout(draft);
			draftsToPark.add(draft);
			issued.add(defCode);
		}
		return new CandidateDispatch(failure, List.copyOf(draftsToPark));
	}

	/**
	 * {@code create_task} 候选的<b>来源解析</b>（按可信度）：
	 * <ol>
	 * <li><b>运行上下文 {@code extra}</b>：本轮的即时事实；带绑定任务号时必须与当前任务一致；</li>
	 * <li><b>本轮已结算 {@code create_task} 的原始入参</b>：结算事实（extra 丢了它还在）；</li>
	 * <li><b>会话级 Redis 草案</b>（{@link CreateTaskDraftStore}）：按 当前tenant/user + 会话
	 *     （runId 解析）存、字段 = taskId，跨轮存活 —— {@code extra} 缺失或绑定信息丢失时的回落。</li>
	 * </ol>
	 *
	 * <p><b>空 {@code conditions={}} 不表示已知</b>：任何一层为空都继续看下一层；任务级草案的写侧
	 * 也不会用空值覆盖明确的非空候选。
	 *
	 * <p><b>读取异常 = 内部失败</b>：Redis 读失败/草案内容损坏一律返回 {@code failure}
	 * （不弹条件表、不写库、不再问用户），绝不把基础设施故障伪装成"条件未知"。
	 */
	private CandidateSource resolveCandidateSource(String runId, long taskId, AiContext context) {
		Map<String, Object> extra = context == null ? null : context.getExtra();
		Long boundTaskId = extra == null ? null : longOf(extra.get(RunContextRefresher.EXTRA_CREATE_TASK_TASK_ID));
		if (boundTaskId != null && !boundTaskId.equals(taskId)) {
			// 只认创建它的任务：切任务后旧候选不得写到新任务
			log.warn("create_task 的结构化条件绑定任务 #{} 与当前任务 #{} 不一致，不生成草案 runId={}", boundTaskId,
					taskId, runId);
			return CandidateSource.internalFailure();
		}
		Map<?, ?> extraConditions = extra == null ? Map.of()
				: conditionsOf(extra.get(RunContextRefresher.EXTRA_CREATE_TASK_CONDITIONS));
		if (boundTaskId != null && !extraConditions.isEmpty()) {
			// 绑定确认过的即时事实：defCodes 也只认 extra（缺 = 由内部失败口径收敛）
			return new CandidateSource(false, stringList(extra.get(RunContextRefresher.EXTRA_CREATE_TASK_DEF_CODES)),
					extraConditions);
		}
		// extra 缺失 / 绑定信息丢失：回落到按任务号归属的事实
		CandidateSource fallback = taskScopedCandidate(runId, taskId);
		if (fallback.failure()) {
			return fallback;
		}
		if (!fallback.conditions().isEmpty()) {
			List<String> defCodes = fallback.defCodes();
			if (defCodes.isEmpty() && extra != null) {
				defCodes = stringList(extra.get(RunContextRefresher.EXTRA_CREATE_TASK_DEF_CODES));
			}
			return new CandidateSource(false, defCodes, fallback.conditions());
		}
		if (!extraConditions.isEmpty()) {
			// 有候选但归属无法确认（绑定信息丢失且没有任务级事实）：不得把无主候选写到当前任务
			log.warn("create_task 的结构化条件缺少任务绑定且没有任务级草案，不生成草案 runId={} taskId={}", runId,
					taskId);
			return CandidateSource.internalFailure();
		}
		return CandidateSource.none();
	}

	/** 按任务号归属的候选事实：本轮已结算 {@code create_task} 原始入参优先，其次会话级 Redis 草案。 */
	private CandidateSource taskScopedCandidate(String runId, long taskId) {
		CandidateSource settled = settledCreateTaskCandidate(runId, taskId);
		if (settled.failure()) {
			return settled;
		}
		CandidateSource draft = taskDraftCandidate(runId, taskId);
		if (draft.failure()) {
			return draft;
		}
		return new CandidateSource(false, settled.defCodes().isEmpty() ? draft.defCodes() : settled.defCodes(),
				settled.conditions().isEmpty() ? draft.conditions() : settled.conditions());
	}

	/** 本轮已结算 {@code create_task} 的原始入参（成功且回执任务号正是当前任务的那一条）。 */
	private CandidateSource settledCreateTaskCandidate(String runId, long taskId) {
		if (runId == null || this.store == null) {
			return CandidateSource.none();
		}
		List<PendingToolCall> pendings;
		try {
			// 强一致读口：读不出来按内部失败处理（不能用 lenient 的空表把故障伪装成"没有候选"）
			pendings = this.store.pendings(runId);
		}
		catch (RuntimeException ex) {
			log.warn("读取本轮 create_task 原始入参失败（内部失败，不弹条件表）runId={} taskId={}：{}", runId, taskId,
					ex.getMessage());
			return CandidateSource.internalFailure();
		}
		for (PendingToolCall pending : pendings == null ? List.<PendingToolCall>of() : pendings) {
			if (pending == null || !AiToolNames.CREATE_TASK.equals(pending.getName()) || !pending.resolved()
					|| pending.effectiveOutcome() != ToolOutcome.OK) {
				continue;
			}
			Long created = RunContextRefresher.taskIdFromText(pending.getResultText());
			if (created == null || !created.equals(taskId)) {
				continue;
			}
			JsonNode args = parseJson(pending.getArguments());
			Map<?, ?> conditions = conditionsOf(args == null ? null : args.get("conditions"));
			if (conditions.isEmpty()) {
				continue;
			}
			return new CandidateSource(false, defCodesOf(args), conditions);
		}
		return CandidateSource.none();
	}

	/** 会话级 Redis 草案（runId → 会话号，字段 = taskId；跨轮存活）；读失败/内容损坏 = 内部失败。 */
	private CandidateSource taskDraftCandidate(String runId, long taskId) {
		CreateTaskDraftStore draftStore = this.draftStore;
		if (draftStore == null) {
			return CandidateSource.none();
		}
		CreateTaskDraftStore.Draft draft;
		try {
			draft = draftStore.read(runId, taskId);
		}
		catch (RuntimeException ex) {
			log.warn("读取会话任务草案失败（内部失败，不弹条件表）runId={} taskId={}：{}", runId, taskId,
					ex.getMessage());
			return CandidateSource.internalFailure();
		}
		if (draft == null) {
			return CandidateSource.none();
		}
		return new CandidateSource(false, draft.defCodes(), draft.conditions());
	}

	/** extra/草案里的结构化条件（非 Map 或空对象 = 空；空不表示"已知"）。 */
	private static Map<?, ?> conditionsOf(Object raw) {
		return raw instanceof Map<?, ?> map && !map.isEmpty() ? map : Map.of();
	}

	/** {@code create_task} 入参里的结构化条件（缺键/非对象/空对象 = 空）。 */
	private static Map<?, ?> conditionsOf(JsonNode node) {
		if (node == null || !node.isObject() || node.isEmpty()) {
			return Map.of();
		}
		return JSON.convertValue(node, Map.class);
	}

	/** {@code create_task} 入参里的 defCodes（非数组/空白项忽略；保持原顺序）。 */
	private static List<String> defCodesOf(JsonNode args) {
		JsonNode raw = args == null ? null : args.get("defCodes");
		if (raw == null || !raw.isArray()) {
			return List.of();
		}
		List<String> out = new ArrayList<>();
		for (JsonNode element : raw) {
			if (element == null || !element.isTextual()) {
				continue;
			}
			String value = element.asText("").trim();
			if (!value.isEmpty() && !out.contains(value)) {
				out.add(value);
			}
		}
		return out;
	}

	/** create_task 候选来源：{@code failure} = 绑定/结构/读取的内部失败（不弹表、不写库）。 */
	private record CandidateSource(boolean failure, List<String> defCodes, Map<?, ?> conditions) {

		static CandidateSource none() {
			return new CandidateSource(false, List.of(), Map.of());
		}

		static CandidateSource internalFailure() {
			return new CandidateSource(true, List.of(), Map.of());
		}

		CandidateSource {
			defCodes = defCodes == null ? List.of() : List.copyOf(defCodes);
			conditions = conditions == null ? Map.of() : conditions;
		}
	}

	/** 即时执行类前端工具的到期时刻（与确认门同一口径；等人填写的表单不会走到这里）。 */
	private void applyToolTimeout(PendingToolCall draft) {
		Integer waitSeconds = this.gate == null ? null : this.gate.suspendWaitSeconds(draft);
		if (waitSeconds != null && waitSeconds > 0) {
			long atMs = System.currentTimeMillis();
			draft.setAtMs(atMs);
			draft.setExpiresAt(atMs + waitSeconds * 1000L);
		}
	}

	/** {@code set_condition} 的入参原文（与模型调用同形）；序列化失败 = {@code null}。 */
	private static String conditionArguments(String defCode, Map<?, ?> conditions) {
		Map<String, Object> args = new LinkedHashMap<>();
		args.put("defCode", defCode);
		args.put("conditions", conditions);
		try {
			return JSON.writeValueAsString(args);
		}
		catch (Exception ex) {
			return null;
		}
	}

	/**
	 * 本轮 {@code set_condition} 草案事实（待决条目是唯一事实源，业务库只读）。
	 *
	 * <p>任务绑定：宿主草案的 {@code toolCallId} 编码了 run/task；模型自己下发的 {@code set_condition}
	 * 没有任务号，因此只认 {@code defCode} 属于当前任务、且在任务创建边界之后请求的条目。
	 */
	private DraftFacts draftFacts(String runId, Task task, long boundaryMs) {
		Set<String> taskDefCodes = new LinkedHashSet<>(taskDefCodesFor(task));
		Set<String> settled = new LinkedHashSet<>();
		Set<String> attempted = new LinkedHashSet<>();
		Set<String> issued = new LinkedHashSet<>();
		if (task == null || runId == null) {
			return DraftFacts.none();
		}
		List<PendingToolCall> pendings;
		try {
			pendings = this.store.pendingsLenient(runId);
		}
		catch (RuntimeException ex) {
			log.warn("读取条件草案事实失败（按无草案处理）runId={}：{}", runId, ex.getMessage());
			return DraftFacts.none();
		}
		long taskId = task.getId() == null ? 0L : task.getId();
		for (PendingToolCall pending : pendings == null ? List.<PendingToolCall>of() : pendings) {
			if (pending == null || !AiToolNames.SET_CONDITION.equals(pending.getName())
					|| !PendingToolCall.KIND_FRONTEND.equals(pending.getKind())) {
				continue;
			}
			String defCode = defCodeOf(pending.getArguments());
			if (defCode == null || !taskDefCodes.contains(defCode)) {
				continue;
			}
			boolean hostDraft = pending.getToolCallId() != null
					&& pending.getToolCallId().startsWith(DRAFT_TOOL_CALL_ID_PREFIX);
			if (hostDraft && !isDraftForTask(pending.getToolCallId(), runId, taskId)) {
				// 别的任务/别的 run 的草案：不得当成当前任务的事实
				continue;
			}
			if (!hostDraft && boundaryMs > 0 && requestedAtOf(pending) < boundaryMs) {
				// 本任务创建之前的历史 set_condition：不算本轮草案
				continue;
			}
			issued.add(defCode);
			boolean resolved = !PendingToolCall.PENDING.equals(pending.getStatus());
			if (resolved && pending.effectiveOutcome() == ToolOutcome.OK) {
				settled.add(defCode);
			}
			else {
				attempted.add(defCode);
			}
		}
		return new DraftFacts(Set.copyOf(settled), Set.copyOf(attempted), Set.copyOf(issued));
	}

	/** {@code set_condition} 入参里的 defCode（坏 JSON/缺键 = null）。 */
	private static String defCodeOf(String argumentsJson) {
		JsonNode arguments = parseJson(argumentsJson);
		JsonNode defCode = arguments == null ? null : arguments.get("defCode");
		if (defCode == null || !defCode.isTextual()) {
			return null;
		}
		String value = defCode.asText("").trim();
		return value.isEmpty() ? null : value;
	}

	/**
	 * <b>同一 run + 当前任务 + 同 {@code defCode} + 完全相同入参</b>的 {@code set_condition} 已成功结算
	 * ⇒ 返回原回执（幂等重放，F11）。
	 *
	 * <p>为什么要这一层：模型常在同一轮里把同一个条件再发一次（换个措辞、或重试），
	 * 前端会再写一遍同一份草案（重复写）。完全相同 = 结果必然相同，直接复用已结算回执即可；
	 * <b>不同值必须视为修改照常执行</b> —— 因此判据是"defCode + conditions 结构相同"，
	 * 不是"同 defCode 就跳过"。
	 *
	 * @return 可复用的回执文本；{@code null} = 没有可复用的成功调用（照常下发）
	 */
	public String settledSetConditionReplay(String runId, AiContext context, String argumentsJson) {
		if (runId == null || context == null || context.getTaskId() == null || argumentsJson == null) {
			return null;
		}
		JsonNode arguments = parseJson(argumentsJson);
		JsonNode conditions = arguments == null ? null : arguments.get("conditions");
		String defCode = defCodeOf(argumentsJson);
		if (defCode == null || conditions == null || !conditions.isObject()) {
			return null;
		}
		Task task;
		try {
			task = readTask(context.getTaskId());
		}
		catch (RuntimeException ex) {
			// 读不到当前任务就不做幂等（宁可多一次下发，也不把结果复用到别的任务上）
			return null;
		}
		if (task == null || !taskDefCodesFor(task).contains(defCode)) {
			return null;
		}
		long boundaryMs = createTaskBoundary(context);
		long taskId = task.getId() == null ? 0L : task.getId();
		List<PendingToolCall> pendings;
		try {
			pendings = this.store.pendingsLenient(runId);
		}
		catch (RuntimeException ex) {
			return null;
		}
		for (PendingToolCall pending : pendings == null ? List.<PendingToolCall>of() : pendings) {
			if (pending == null || !AiToolNames.SET_CONDITION.equals(pending.getName())
					|| !PendingToolCall.KIND_FRONTEND.equals(pending.getKind())
					|| !PendingToolCall.FRONTEND_RESULT.equals(pending.getStatus())
					|| pending.effectiveOutcome() != ToolOutcome.OK) {
				continue;
			}
			if (!defCode.equals(defCodeOf(pending.getArguments()))
					|| !sameConditions(pending.getArguments(), conditions)) {
				continue;
			}
			boolean hostDraft = pending.getToolCallId() != null
					&& pending.getToolCallId().startsWith(DRAFT_TOOL_CALL_ID_PREFIX);
			if (hostDraft && !isDraftForTask(pending.getToolCallId(), runId, taskId)) {
				// 别的任务的宿主草案：不得当作当前任务的幂等事实
				continue;
			}
			if (!hostDraft && boundaryMs > 0 && requestedAtOf(pending) < boundaryMs) {
				// 本任务创建之前的历史成功调用：不复用
				continue;
			}
			return pending.getResultText() == null ? "" : pending.getResultText();
		}
		return null;
	}

	private static boolean sameConditions(String argumentsJson, JsonNode conditions) {
		JsonNode arguments = parseJson(argumentsJson);
		JsonNode other = arguments == null ? null : arguments.get("conditions");
		return other != null && other.equals(conditions);
	}

	/**
	 * {@code set_condition} 入参的内容签名（{@code defCode + conditions 结构}；坏 JSON/缺键 = {@code null}）。
	 * 供同一批次内的重复调用去重（F11）：一次批次里出现两条完全相同的调用时，第二条不再挂起。
	 */
	public static String setConditionSignature(String argumentsJson) {
		String defCode = defCodeOf(argumentsJson);
		JsonNode arguments = parseJson(argumentsJson);
		JsonNode conditions = arguments == null ? null : arguments.get("conditions");
		if (defCode == null || conditions == null || !conditions.isObject()) {
			return null;
		}
		return defCode + '\u0001' + conditions;
	}

	/** {@code remaining = all - covered}（保持原顺序）。 */
	private static List<String> subtract(List<String> all, Set<String> covered) {
		List<String> out = new ArrayList<>();
		for (String defCode : all) {
			if (!covered.contains(defCode)) {
				out.add(defCode);
			}
		}
		return out;
	}

	/** 这些配置项里是否有已下发但未成功结算的草案（内部失败，不再询问用户）。 */
	private static boolean attemptedAny(List<String> defCodes, DraftFacts drafts) {
		for (String defCode : defCodes) {
			if (drafts.attempted().contains(defCode)) {
				return true;
			}
		}
		return false;
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

	private ConfirmStepDecision conditionGate(String runId, long taskId, Task task, AiContext context,
			PendingToolCall pending, List<Message> history, boolean allowPark) {
		// ① 草案事实只来自 Redis 待决条目：任务 conditionJson 已落库条件 + 本轮成功的 set_condition 草案，
		// 以及表单明确留空。坏 JSON/残缺结构明确拒绝，不按全量放行。
		long boundaryMs = createTaskBoundary(context);
		DraftFacts drafts = draftFacts(runId, task, boundaryMs);
		ExportConditionWorkflow.Plan plan = this.conditionWorkflow.plan(task);
		if (plan.malformed()) {
			log.info("confirm_step 时条件结构残缺，零副作用拒绝 runId={} taskId={}", runId, taskId);
			return ConfirmStepDecision.reject(ErrorCodes.CONDITION_JSON_INVALID,
					"条件结构无法解析，confirm_step 未执行（未向前端推进）",
					List.of("任务条目里存在坏 JSON/未知键/残缺结构，不能按全量放行",
							"下一步：让用户在向导里重新设置合法条件（或清空为明确全量）后再 confirm_step"));
		}
		if (plan.undecided().isEmpty()) {
			// 任务条目已有条件事实（已设置/明确不设）：历史回灌不再挡路
			return ConfirmStepDecision.allow();
		}
		ConditionFeedback feedback = feedbackOf(runId, taskId, boundaryMs, plan.undecided());
		List<String> remaining = subtract(plan.undecided(), drafts.settled());
		// ② 表单明确留空：允许推进；后端<b>不写</b> "{}"，由前端在离开 QUERY_COND 时经 persistStepData 提交。
		// 留空也压过 create_task 候选：用户的最新明确回答优先。
		if (feedback.blank()) {
			return ConfirmStepDecision.allow();
		}
		// ③ 非空回灌必须先由模型解析成 set_condition 草案；在此之前不得下发候选或换卡重问。
		// 拒绝清单只点名**还没有草案**的剩余项：已按 set_condition(defCode, {}) 结算的配置项不算缺口。
		if (feedback.nonEmpty() && !feedback.handled()) {
			log.info("confirm_step 时条件表已收到非空文本但尚无对应草案，零副作用拒绝 runId={} taskId={}", runId, taskId);
			return unparsedFeedbackDecision(remaining);
		}
		// ④ 合法 create_task 候选：复用前端 set_condition 通道生成页面草案（不弹条件表、不写库）
		CandidateDispatch candidates = parkEnabled()
				? dispatchCreateTaskCandidates(runId, taskId, context, task, drafts)
				: CandidateDispatch.none();
		if (!candidates.drafts().isEmpty()) {
			if (!allowPark) {
				// F4：混批不能在这里 park（同样会先外置整批历史、吞掉同批调用）
				log.info("混批中 confirm_step 需要下发条件草案，零副作用拒绝（要求单独调用）runId={} taskId={}",
						runId, taskId);
				return mixedBatchDecision();
			}
			boolean parked = parkAll(runId, history, candidates.drafts());
			if (parked) {
				log.info("confirm_step 前下发条件草案 runId={} taskId={} drafts={}", runId, taskId,
						candidates.drafts().size());
				return ConfirmStepDecision.parkedDecision();
			}
			return internalConditionFailure("条件草案下发失败，confirm_step 未执行（未向前端推进）",
					List.of("create_task 的结构化条件已通过校验，但页面草案没有成功挂起（内部执行失败）",
							"不会改用条件表再次询问用户；请重试 confirm_step"));
		}
		if (candidates.failure()) {
			// 绑定错误/校验失败：WARN 已记录；内部失败口径，绝不转化成"再问用户一次"
			return internalConditionFailure("条件候选无法生成页面草案，confirm_step 未执行（未向前端推进）",
					List.of("create_task 的结构化条件绑定/校验失败（内部失败口径，不弹条件表）",
							"不会改用条件表再次询问用户；请由用户或向导修正条件后再 confirm_step"));
		}
		if (remaining.isEmpty()) {
			// 本轮草案已覆盖全部缺口：放行；前端 persistStepData 负责真正的提交
			return ConfirmStepDecision.allow();
		}
		// ⑤ 已下发但执行失败的草案：内部失败，不再把问题转回用户
		if (attemptedAny(remaining, drafts)) {
			log.warn("confirm_step 时条件草案执行失败，零副作用拒绝（不再询问用户）runId={} taskId={} defs={}", runId,
					taskId, remaining);
			return internalConditionFailure("条件草案执行失败，confirm_step 未执行（未向前端推进）",
					List.of("本轮已下发的 set_condition 草案没有成功结算（内部执行失败）",
							"不会改用条件表再次询问用户；请由用户或向导修正条件后再 confirm_step"));
		}
		// ⑥ 取消不自动重问，但也不能放行：条件仍未决定，confirm_step 零副作用拒绝并给出两条明确出口
		if (feedback.cancelled()) {
			log.info("confirm_step 时条件表已被取消且条件仍未决定，零副作用拒绝 runId={} taskId={}", runId, taskId);
			return ConfirmStepDecision.reject(ErrorCodes.FORM_RESULT_REJECTED,
					"条件仍未决定（条件表已取消），confirm_step 未执行（未向前端推进）",
					List.of("取消不自动重问；但条件仍未决定，不能按全量误推进",
							"恢复通道：由用户下一轮明确选择——给出筛选条件（模型先 set_condition）"
									+ "或明确按全量导出；本 run 不再自动弹条件表"));
		}
		// ⑦ 真正未知的条件：同一缺口只问一次。
		//
		// F3：以下每条"收不到答案"的路径都必须 fail-closed —— 条件仍 UNDECIDED 时不能 allow
		// （allow 会让 confirm_step 把一次"宿主没问成"变成全量导出）。只有 SET / EXPLICIT_UNSET /
		// 已结算草案 / 明确留空才走上面的放行分支。
		String toolCallId = toolCallId(runId, taskId, remaining, taskDefCodesFor(task));
		if (!parkEnabled()) {
			log.warn("条件未决定但宿主表单挂起未启用，零副作用拒绝 runId={} taskId={} defs={}", runId, taskId,
					remaining);
			return internalConditionFailure("条件未决定且宿主表单挂起未启用（app.ai.park.enabled=false），"
					+ "confirm_step 未执行（未向前端推进）",
					List.of("条件仍未决定，不能按全量推进",
							"下一步：先 set_condition 给出结构化条件（明确不设请对每个 defCode 传 {}），"
									+ "或让用户明确按全量导出后再 confirm_step",
							"宿主不会在未挂起状态下等待用户填表"));
		}
		if (!allowPark) {
			// F4：混批 park 会先外置整批历史，吞掉同批其它调用的卡/回执 —— 改成让模型分批
			log.info("混批中 confirm_step 需要收集条件，零副作用拒绝（要求单独调用）runId={} taskId={}", runId,
					taskId);
			return mixedBatchDecision();
		}
		if (alreadyIssued(runId, toolCallId)) {
			log.warn("同一缺口的条件表已存在且未结算，零副作用拒绝（不换第二张）runId={} taskId={} toolCallId={}",
					runId, taskId, toolCallId);
			return internalConditionFailure("同一缺口的条件表已下发但未结算（或已作废），条件仍未决定，"
					+ "confirm_step 未执行（未向前端推进）",
					List.of("条件表 " + toolCallId + " 的状态不是成功回灌：不能重复下发第二张，也不能按全量推进",
							"恢复通道：由用户明确给出条件（模型先 set_condition；不设请对每个 defCode 传 {}）"
									+ "或明确按全量导出后再 confirm_step"));
		}
		Optional<String> arguments = formArguments(buildForm(ReplyLanguage.current()));
		if (arguments.isEmpty()) {
			log.warn("条件表未通过白名单/长度校验，零副作用拒绝 runId={} taskId={}", runId, taskId);
			return internalConditionFailure("条件表无法生成（未通过表单白名单或超长），条件仍未决定，"
					+ "confirm_step 未执行（未向前端推进）",
					List.of("宿主无法下发一张自己都过不了闸门的表",
							"下一步：先 set_condition 给出结构化条件（明确不设请对每个 defCode 传 {}），"
									+ "或让用户明确按全量导出后再 confirm_step"));
		}
		log.info("confirm_step 推进前条件未决定，改为收集一次可选条件 runId={} taskId={} defs={}", runId, taskId,
				remaining);
		boolean parked = park(runId, history, frontendForm(toolCallId, arguments.get()));
		if (!parked) {
			// F3：挂起写失败（saveSuspended/converge 失败）同样不能 allow
			log.warn("条件表挂起失败，条件仍未决定，零副作用拒绝 runId={} taskId={}", runId, taskId);
			return internalConditionFailure("条件表挂起失败（内部执行失败），条件仍未决定，"
					+ "confirm_step 未执行（未向前端推进）",
					List.of("宿主没有把条件表挂起成功（本拍未下发、未推进）",
							"请稍后重试 confirm_step；若持续失败请检查存储/会话状态",
							"不会在条件未决定时按全量推进"));
		}
		return ConfirmStepDecision.parkedDecision();
	}

	private static ConfirmStepDecision internalConditionFailure(String message, List<String> reasons) {
		return ConfirmStepDecision.reject(ErrorCodes.TOOL_EXECUTION_FAILED, message, reasons);
	}

	/** 混批需要挂起时的统一拒绝（F4）：让模型把 confirm_step 单独成批。 */
	private static ConfirmStepDecision mixedBatchDecision() {
		return internalConditionFailure("同一批里还有其它工具，条件表不能在混批中挂起，"
				+ "confirm_step 未执行（未向前端推进）",
				List.of("先单独调用 confirm_step（不传 step，只提交这一个工具），下一批再执行其它工具",
						"混批挂起会先外置整批历史，吞掉同批其它调用的卡与回执",
						"条件仍未决定：本批不会按全量推进"));
	}

	/**
	 * 非空表单回灌尚未被模型解析成 set_condition 草案：零副作用拒绝，不换卡重问。
	 *
	 * <p>{@code remaining} = 还没有成功草案的配置项（已结算的不再点名）。用户可能用"无"/"不筛选"
	 * 回答条件表 —— 那是<b>逐项明确不设</b>，对表单覆盖的每个 {@code defCode} 各调用一次
	 * {@code set_condition(defCode, {})}（恰好空对象 = EXPLICIT_UNSET），不要重发条件表。
	 */
	private static ConfirmStepDecision unparsedFeedbackDecision(List<String> remaining) {
		return ConfirmStepDecision.reject(ErrorCodes.FORM_SLOTS_ALREADY_KNOWN,
				"条件表已填写但尚未生成草案，confirm_step 未执行（未向前端推进）",
				List.of("条件表回灌了非空文本，但对应配置项还没有成功的 set_condition 草案",
						"尚未生成草案的配置项：" + String.join("、", remaining),
						"下一步：先把该文本解析成结构化条件并调用 set_condition（草案，不写库），下一批再 confirm_step",
						"若用户已明确不设筛选（如回答“无”“不筛选”“全部”），对表单覆盖的每个 defCode 各调用一次 "
								+ "set_condition(defCode, {})（恰好空对象 = 明确不设），不要重发 SET_CONDITIONS",
						"不得改用第二张条件表单重复收集"));
	}

	/**
	 * 本轮 {@code SET_CONDITIONS} 表单的回灌事实（宿主表单持久化在同一个 run 的待决条目里，
	 * 跨实例、跨续跑都读得到；旧模型表单仅作历史兼容）。
	 *
	 * <p><b>任务时间边界</b>：{@code boundaryMs > 0} 时只认该时刻<b>之后产生</b>的表单
	 * （{@code requestedAtMs}，旧条目回落 {@code resolvedAtMs}）—— 上一个任务（A）的回灌/取消
	 * 不会被新任务（B）复用；{@code boundaryMs == 0}（非本 run 新建任务）行为不变。
	 *
	 * <p>{@code handled} 按表单<b>实际覆盖的 defCode</b> 核对：非空回灌后必须为这些配置项各产生一条
	 * 成功的 {@code set_condition} 草案（按 defCode 与任务绑定），否则不得 confirm。
	 */
	record ConditionFeedback(boolean cancelled, boolean blank, boolean nonEmpty, boolean handled) {

		static ConditionFeedback none() {
			return new ConditionFeedback(false, false, false, false);
		}
	}

	ConditionFeedback feedbackOf(String runId, long taskId, long boundaryMs, List<String> undecidedDefCodes) {
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
		Set<String> asked = askedDefCodesOf(latest, runId, taskId, undecidedDefCodes);
		long sinceMs = Math.max(boundaryMs, latest.getResolvedAtMs());
		return new ConditionFeedback(false, false, true,
				setConditionDraftsSettledSince(runId, taskId, sinceMs, asked, boundaryMs));
	}

	/** 表单实际覆盖的 defCode：宿主缺口卡 id 后缀优先；整卡/模型卡 = 当前全部未决定项。 */
	private static Set<String> askedDefCodesOf(PendingToolCall form, String runId, long taskId,
			List<String> undecidedDefCodes) {
		Set<String> fallback = new LinkedHashSet<>(undecidedDefCodes == null ? List.of() : undecidedDefCodes);
		String toolCallId = form.getToolCallId();
		String base = toolCallId(runId, taskId);
		if (toolCallId != null && toolCallId.startsWith(base + "-")) {
			Set<String> covered = new LinkedHashSet<>(List.of(toolCallId.substring(base.length() + 1).split("-")));
			Set<String> matched = new LinkedHashSet<>();
			for (String defCode : fallback) {
				if (covered.contains(defCode)) {
					matched.add(defCode);
				}
			}
			if (!matched.isEmpty()) {
				return matched;
			}
		}
		return fallback;
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
	 * 表单回灌后，表单覆盖的每个 defCode 是否都已有<b>成功的 set_condition 草案</b>
	 * （待决条目是事实源；不读 ai:ledger）。读失败 = 未处理（fail-closed）。
	 */
	private boolean setConditionDraftsSettledSince(String runId, long taskId, long sinceMs, Set<String> defCodes,
			long boundaryMs) {
		if (defCodes == null || defCodes.isEmpty()) {
			return false;
		}
		List<PendingToolCall> pendings;
		try {
			pendings = this.store.pendingsLenient(runId);
		}
		catch (RuntimeException ex) {
			log.warn("读取待决条目失败，非空条件回灌按未处理处理 runId={}：{}", runId, ex.getMessage());
			return false;
		}
		Set<String> settled = new LinkedHashSet<>();
		for (PendingToolCall pending : pendings == null ? List.<PendingToolCall>of() : pendings) {
			if (pending == null || !AiToolNames.SET_CONDITION.equals(pending.getName())
					|| !PendingToolCall.KIND_FRONTEND.equals(pending.getKind())
					|| !PendingToolCall.FRONTEND_RESULT.equals(pending.getStatus())
					|| pending.effectiveOutcome() != ToolOutcome.OK || pending.getResolvedAtMs() < sinceMs) {
				continue;
			}
			String defCode = defCodeOf(pending.getArguments());
			if (defCode == null || !defCodes.contains(defCode)) {
				continue;
			}
			boolean hostDraft = pending.getToolCallId() != null
					&& pending.getToolCallId().startsWith(DRAFT_TOOL_CALL_ID_PREFIX);
			if (hostDraft && !isDraftForTask(pending.getToolCallId(), runId, taskId)) {
				continue;
			}
			if (!hostDraft && boundaryMs > 0 && requestedAtOf(pending) < boundaryMs) {
				continue;
			}
			settled.add(defCode);
		}
		return settled.containsAll(defCodes);
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
		if (values.isEmpty()) {
			return "";
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
	/**
	 * 任务读口：读失败<b>抛出</b>（F3）。条件链上"读不到"必须可区分于"任务不存在" ——
	 * 吞成 {@code null} 会让 confirm_step 按"没有条件可核对"放行（全量导出）。
	 * 非条件链的调用方（{@link #parkIfDueAtQueryCond}）各自 catch 后按既有路径继续。
	 */
	private Task readTask(Long taskId) {
		if (this.taskService == null) {
			return null;
		}
		return this.taskService.findById(taskId);
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
