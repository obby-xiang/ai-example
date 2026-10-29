package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.slot.TaskIntent;
import com.example.configmgr.ai.slot.TaskSlots;
import com.example.configmgr.ai.tool.AiContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * <b>宿主确定性的筛选条件文本框</b>：进入 {@code QUERY_COND} 且条件未给时，由宿主挂起一张
 * <b>非必填自由文本框</b>（{@code purpose=SET_CONDITIONS}），不再把这一问留给模型。
 *
 * <h2>为什么是"一个文本框"而不是结构化表单</h2>
 * 条件是自然语言（"只要 code=CNY 的"，"2026 年以后的"），字段/算子/值因配置项而变。让模型从一段
 * 自由文本里解析 {@code set_condition} 的参数，比让模型现场拼一张条件表更稳（表结构不可能穷举）。
 * 用户已有的口径也因此原样保留：<b>已给条件直接 {@code set_condition}、留空直接 {@code confirm_step}
 * 全量、文本由模型解析</b> —— 宿主只负责"把这一次收集做成确定的"，不改变语义。
 *
 * <h2>触发判据（全部成立才挂起）</h2>
 * <ol>
 * <li><b>停在条件步</b>：权威步骤是 {@code QUERY_COND}（与 {@link GenerativeFormGuard} 的步骤门
 * {@link GenerativeFormRules#conditionsStepAllowed} 同一份判据）—— {@code SELECT_DEFS} 之前、
 * {@code EXPORT} 及之后都不会弹（"EXPORT 后不得再弹"由这一条保证）；</li>
 * <li><b>本句没有要求别的表单/工具/动作</b>：用户明确点名 {@code scenario=CLARIFY} /
 * {@code GENERAL_CLARIFY} / {@code purpose=TASK_TYPE} / {@code SELECT_DEFINITIONS}，或点名别的工具
 * （{@code create_task}/{@code select_definitions}/{@code start_export}/{@code list_config_defs}/
 * {@code navigate_to} 等，见 {@link #asksForOtherFormOrTool}）时<b>一律让位</b> —— 宿主抢着挂条件框
 * 会把用户点名的那张表挤掉（GF2 的回归正是这样：CLARIFY 永远发不出来）。显式条件请求
 * （{@code SET_CONDITIONS}/{@code scenario=FILTER}/"筛选条件"…）优先级更高，照常给框；</li>
 * <li><b>主动任务链</b>：本轮台账里有成功的 {@code create_task} 且就是当前任务，<b>或</b>用户原话是
 * 继续既有任务（{@link TaskIntent#isContinueTaskIntent}）。普通浏览既有任务一律不打扰；</li>
 * <li><b>条件未知</b>：槽位里没有覆盖全部已选配置项的条件（与放行闸门同一份 {@link TaskSlots}）；</li>
 * <li><b>本批没有自己处理</b>：本批已有 {@code generative_form} / {@code set_condition} 时不重复；</li>
 * <li><b>同一任务只问一次</b>：{@code toolCallId} 由任务号决定（提交过、取消过、刷新恢复都不会再来一张）。</li>
 * </ol>
 *
 * <h2>接入点</h2>
 * 续跑侧（{@link com.example.configmgr.ai.run.ResumeService} 的批间交棒点）与首轮
 * （{@link com.example.configmgr.ai.run.ResilientChatService} 驱动模型之前）各一处，共用同一个
 * {@link #parkIfDue}：两处都是"模型被再次提问之前"的确定点。挂起面完全复用 {@link HostForm}，
 * 回灌走既有 {@code /api/ai/frontend-tool-result}，值经既有回灌闸门复核后作为工具结果回填模型。
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

	/** 宿主条件表单的 {@code toolCallId} 前缀（后接任务号：同一任务只会问一次）。 */
	public static final String TOOL_CALL_ID_PREFIX = "host-cond-form-";

	/**
	 * "别的表单 / 别的工具 / 别的动作"的记号（出现即让位，见 {@link #asksForOtherFormOrTool}）。
	 *
	 * <p>大写词忽略大小写匹配（用户/模型都可能写成小写）；工具名走词边界，避免 {@code set_condition}
	 * 被 {@code set_conditions} 之外的普通词误命中。
	 */
	private static final Pattern OTHER_FORM_OR_TOOL = Pattern.compile("(?i)(scenario\\s*[=:：]\\s*clarify)"
			+ "|\\bclarify\\b|general_clarify|\\btask_type\\b|select_definitions|\\bgenerative_form\\b"
			+ "|\\bcreate_task\\b|\\bstart_export\\b|\\bstart_import\\b|\\blist_config_defs\\b"
			+ "|\\bnavigate_to\\b|\\blist_tasks\\b|\\bget_task\\b|\\bconfirm_step\\b|澄清");

	/**
	 * 显式"条件"请求：与本节点的缺口同一件事，优先级高于让位记号（不得被误排除）。
	 */
	private static final Pattern CONDITIONS_REQUEST = Pattern.compile("(?i)set_conditions"
			+ "|scenario\\s*[=:：]\\s*filter|筛选条件|查询条件|过滤条件|条件表单|\\bset_condition\\b");

	@Autowired
	public ConditionsForm(RunStore store, RunRegistry registry, ConfirmGate gate, MessageJsonCodec codec,
			AiProperties properties) {
		super(store, registry, gate, codec, properties);
	}

	/** 宿主为某个任务生成的条件表单 {@code toolCallId}（确定性 = 天然去重）。 */
	public static String toolCallId(long taskId) {
		return TOOL_CALL_ID_PREFIX + taskId;
	}

	/**
	 * 判据成立时挂起非必填文本框。
	 *
	 * @param runId        本轮 id
	 * @param context      <b>交棒那一份</b>上下文（批间刷新 + 任务权威同步之后）
	 * @param settledBatch 本批已结算条目（结算后回读的真值）
	 * @param history      交棒历史（本批工具结果已在内）
	 * @return {@code true} = 已挂起：调用方本拍<b>不得</b>再驱动模型
	 */
	public boolean parkIfDue(String runId, AiContext context, List<PendingToolCall> settledBatch,
			List<Message> history) {
		if (runId == null || context == null || history == null) {
			return false;
		}
		if (!parkEnabled()) {
			// 阻塞式等待不承接这条流程（见 HostForm 类注：不新增中断路径）
			return false;
		}
		try {
			Optional<String> arguments = argumentsIfDue(runId, context, settledBatch, history);
			if (arguments.isEmpty()) {
				return false;
			}
			Long taskId = context.getTaskId();
			return park(runId, history, frontendForm(toolCallId(taskId), arguments.get()));
		}
		catch (RuntimeException ex) {
			log.warn("宿主筛选条件表单未能挂起（按既有路径继续）runId={}：{}", runId, ex.getMessage());
			return false;
		}
	}

	/** 判据 + 表单正文（不含任何外置写）。 */
	Optional<String> argumentsIfDue(String runId, AiContext context, List<PendingToolCall> settledBatch,
			List<Message> history) {
		Long taskId = context.getTaskId();
		if (taskId == null) {
			// 没有任务就没有条件步
			return Optional.empty();
		}
		if (!GenerativeFormRules.STEP_QUERY_COND.equalsIgnoreCase(trim(context.getStep()))) {
			// 只有 QUERY_COND：前置步（SELECT_DEFS/UPLOAD）与后置步（EXPORT 及之后）一律不弹
			return Optional.empty();
		}
		String userText = lastUserText(history);
		if (asksForOtherFormOrTool(userText)) {
			// 用户这一句已经明确要求"别的表单/别的工具/别的动作"（GF2：请调用 generative_form
			// （scenario=CLARIFY）向我澄清…）⇒ 宿主不得抢成条件框，让模型按用户说的去发那张表。
			return Optional.empty();
		}
		if (!activeTaskChain(runId, taskId, userText)) {
			// 普通浏览不自动打扰：只有主动任务链 / 明确的继续意图
			return Optional.empty();
		}
		if (TaskSlots.fromExtra(context.getExtra()).conditionsCoverDefCodes()) {
			// 条件已知 ⇒ 直接 set_condition，不弹表单
			return Optional.empty();
		}
		if (handledInBatch(settledBatch, GenerativeFormRules.TOOL_NAME, "set_condition")) {
			// 本批模型自己已经收集/设置过条件：同一缺口只问一次
			return Optional.empty();
		}
		if (alreadyIssued(runId, toolCallId(taskId))) {
			return Optional.empty();
		}
		return formArguments(buildForm());
	}

	/**
	 * 这一句是否<b>明确要求别的表单/工具/动作</b>（出现即让位：宿主不抢条件框）。
	 *
	 * <p>为什么需要它：宿主条件框的触发条件是"继续任务 + 条件步 + 条件未知"，而用户完全可能在同一句里
	 * 指挥模型做<b>另一件事</b>（GF2 的真实原话就是"请调用 generative_form（scenario=CLARIFY）向我澄清
	 * exportScope/includeInactive…"）。此时宿主抢先挂条件框，等于把用户点名的那张表挤掉 —— 模型这一拍
	 * 根本没机会被驱动。
	 *
	 * <p><b>让位记号</b>（任一命中即让位）：
	 * <ul>
	 * <li>别的表单：{@code scenario=CLARIFY} / {@code CLARIFY} / {@code GENERAL_CLARIFY} /
	 * {@code purpose=TASK_TYPE} / {@code SELECT_DEFINITIONS} / "澄清"；</li>
	 * <li>别的工具：{@code generative_form}（未同时指明条件时）/ {@code create_task} /
	 * {@code select_definitions} / {@code start_export} / {@code start_import} /
	 * {@code list_config_defs} / {@code navigate_to} / {@code list_tasks} / {@code get_task} /
	 * {@code confirm_step}。</li>
	 * </ul>
	 *
	 * <p><b>优先级</b>：显式条件请求（{@code SET_CONDITIONS} / {@code scenario=FILTER} / "筛选条件" /
	 * "查询条件" / "条件表单" / {@code set_condition}）说明用户要的<b>正是</b>本节点的东西，优先级高于
	 * 上面的让位记号 —— 一句里两者都出现时照常给框，不误排除。
	 */
	private static boolean asksForOtherFormOrTool(String userText) {
		if (userText == null || userText.isBlank()) {
			return false;
		}
		if (CONDITIONS_REQUEST.matcher(userText).find()) {
			return false;
		}
		return OTHER_FORM_OR_TOOL.matcher(userText).find();
	}

	/**
	 * 主动任务链：本轮新建的<b>就是这个</b>任务，或用户原话在继续既有任务。
	 *
	 * <p>旧任务的创建回执不算：{@code context.getTaskId()} 必须与台账里的任务号相等。
	 */
	private boolean activeTaskChain(String runId, Long taskId, String userText) {
		Long createdTaskId = createdTaskInRun(runId);
		if (createdTaskId != null && createdTaskId.equals(taskId)) {
			return true;
		}
		return TaskIntent.isContinueTaskIntent(userText);
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
