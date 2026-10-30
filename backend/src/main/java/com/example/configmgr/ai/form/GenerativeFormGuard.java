package com.example.configmgr.ai.form;

import com.example.configmgr.ai.slot.TaskSlots;
import com.example.configmgr.ai.slot.TaskSlots.ConditionSlot;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.FrontendToolGuard;
import com.example.configmgr.common.AiStateUnavailableException;
import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.file.FileStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code generative_form} 的复核器（DC-15 安全闸）：把模型给的表单 schema 与前端回灌的填写值，
 * 交给 {@link GenerativeFormRules} 的白名单规则判定，产出可回填的 {@link Verdict}。
 *
 * <p>本类只做五件事：解析 JSON、调用纯规则、<b>槽位放行</b>（purpose + 任务槽位）、<b>附件只读校验</b>
 * （G-E：存在 / 归属 / 形状）与决策落库后的<b>附件消费/回收</b>、组装结论文本 —— 校验口径<b>全部</b>在
 * {@link GenerativeFormRules} 里（那里是可以脱离 Spring 直接断言的正反例对象）。
 *
 * <p><b>校验与消费分离（2026-10-29 修复批）</b>：{@link #inspectResult} 跑在"抢决策 guard / 落库"
 * <b>之前</b>，那个时点拒绝的语义是"可修正后重试"——在那里消费一次性件会把"可重试"变成假话。
 * 消费改由 {@link #settleAcceptedResult}（决策落库后）/ {@link #settleDiscardedResult}（用户放弃后）
 * 承担，失败只告警、不改结论。
 *
 * <p>两道闸门的调用方见 {@link FrontendToolGuard}：下发前（{@code SpToolCallingManager}）与
 * 回灌前（{@code ConfirmGate}）。本类<b>不</b>在 {@code AiTools#generativeForm} 里做事 ——
 * 那个方法体是哨兵桩，正常路径永不执行。
 *
 * <h2>结构化错误（GF 优化批 G-A）</h2>
 * 拒绝时同时给两件东西：人读 {@code reasons}（既有契约，逐条不改）与结构化 {@code issues}
 * （{@code {key, code, message}}）。前端按 {@code key} 点亮字段、按 {@code code} 决定是否可重试；
 * 模型拿到的是同一批 message 的编号清单 —— 两处不各写一份文案。
 *
 * <h2>槽位放行（2026-10-28，任务槽位批）</h2>
 * 下发前在<b>原 schema 校验之后</b>再看 {@code purpose} 与 {@code AiContext.extra[taskSlots]}：
 * 槽位已确定的收集表单没有真实缺口 ⇒ 拒绝下发（{@code FORM_SLOTS_ALREADY_KNOWN}，零副作用），
 * 把模型引回 {@code select_definitions} / {@code set_condition}。缺槽位、歧义与
 * {@code GENERAL_CLARIFY} 一律放行 —— 未知配置仍可用表单。
 * <p><b>缺省 purpose 只在结构明确时推断</b>（{@code optionsFrom.sourceId=definitions} → 选择，
 * {@code scenario=FILTER} → 条件），显式 {@code GENERAL_CLARIFY} 不被形状覆盖；标题/label 不参与判断。
 * 上下文由
 * {@link FrontendToolGuard#inspectArguments(String, String, AiContext)} 传入；没有上下文时
 * （既有单测/兼容调用）槽位与步骤规则整体跳过。
 *
 * <h2>两条补充判据（2026-10-29 闭环批）</h2>
 * <ul>
 * <li><b>任务类型</b>（{@code purpose=TASK_TYPE}）：槽位里已有任务类型 ⇒ 零副作用拒绝并指回
 * {@code create_task(type=…)}；类型未知 ⇒ 放行（此时表单是唯一合法的收集方式，不能退回文字反问）。</li>
 * <li><b>筛选条件</b>（{@code purpose=SET_CONDITIONS}）：<b>有明确步骤时只有 {@code QUERY_COND} 允许</b>，
 * 其余步骤（{@code SELECT_DEFS}、{@code EXPORT}、{@code DONE}、导入链等）零副作用拒绝：
 * 前置步给"先 confirm_step 推进到 QUERY_COND"，后置步给"按既有步骤链继续，不重复收集"；
 * 条件已明确同样拒绝（既有判据）。步骤未知时不判关闭（未知 ≠ 关闭），此时只按槽位规则。</li>
 * </ul>
 */
@Slf4j
@Component
public class GenerativeFormGuard implements FrontendToolGuard {

	private final ObjectMapper objectMapper;

	/**
	 * 附件登记簿（可为 null：只做 schema/值校验的单元路径）。null 时跳过"存在/归属/一次性"，
	 * 但 fileId 形状校验仍在 {@link GenerativeFormRules} 里照旧执行。
	 */
	private final FormAttachmentStore attachments;

	/**
	 * 物理文件存储（可为 null：只做 schema/值校验的单元路径）。非 null 时，决策落库后的
	 * {@code consume}/{@code deleteRegistration} 成功即删除落盘文件（一次性件不会再有第二次读取机会）。
	 */
	private final FileStorageService fileStorage;

	public GenerativeFormGuard(ObjectMapper objectMapper) {
		this(objectMapper, (FormAttachmentStore) null);
	}

	@org.springframework.beans.factory.annotation.Autowired
	public GenerativeFormGuard(ObjectMapper objectMapper,
			ObjectProvider<FormAttachmentStore> attachmentsProvider,
			ObjectProvider<FileStorageService> fileStorageProvider) {
		this(objectMapper, attachmentsProvider == null ? null : attachmentsProvider.getIfAvailable(),
				fileStorageProvider == null ? null : fileStorageProvider.getIfAvailable());
	}

	GenerativeFormGuard(ObjectMapper objectMapper, FormAttachmentStore attachments) {
		this(objectMapper, attachments, null);
	}

	GenerativeFormGuard(ObjectMapper objectMapper, FormAttachmentStore attachments,
			FileStorageService fileStorage) {
		this.objectMapper = objectMapper;
		this.attachments = attachments;
		this.fileStorage = fileStorage;
	}

	@Override
	public boolean supports(String toolName) {
		return GenerativeFormRules.TOOL_NAME.equals(toolName);
	}

	/** 下发前的 schema 白名单校验：不合法 ⇒ 调用方不挂起、前端看不到这张表单。 */
	@Override
	public Verdict inspectArguments(String argumentsJson) {
		return inspectArguments(GenerativeFormRules.TOOL_NAME, argumentsJson, null);
	}

	/**
	 * 下发前的复核（带工具名与上下文）。
	 *
	 * <p>顺序是硬要求：<b>先</b>跑原有 schema 白名单（非法仍是 {@code FORM_SCHEMA_REJECTED}），
	 * <b>再</b>看 {@code purpose} 与任务槽位 —— 槽位已确定的收集表单没有缺口，拒绝下发并把模型
	 * 引回对应工具。{@code context == null}（既有单测/兼容调用）时槽位规则整体跳过。
	 */
	@Override
	public Verdict inspectArguments(String toolName, String argumentsJson) {
		return inspectArguments(toolName, argumentsJson, null);
	}

	@Override
	public Verdict inspectArguments(String toolName, String argumentsJson, AiContext context) {
		JsonNode arguments = parse(argumentsJson);
		if (arguments == null) {
			List<FormIssue> issues = List.of(FormIssue.form(FormIssueCodes.ARG_NOT_OBJECT,
					"工具参数无法解析为 JSON：" + abbreviate(argumentsJson)));
			return Verdict.rejectIssues(GenerativeFormRules.SCHEMA_REJECTED,
					"表单 schema 不是合法 JSON，工具未执行（未向前端下发表单）", issues);
		}
		List<FormIssue> issues = GenerativeFormRules.argumentIssues(arguments);
		if (!issues.isEmpty()) {
			log.warn("generative_form schema 未通过白名单校验（未挂起、未下发前端）：{}",
					GenerativeFormRules.messages(issues));
			return Verdict.rejectIssues(GenerativeFormRules.SCHEMA_REJECTED,
					"表单 schema 未通过白名单校验，工具未执行（未向前端下发表单）", issues);
		}
		return inspectSlots(GenerativeFormRules.purposeOf(arguments.get("form")),
				TaskSlots.fromExtra(context == null ? null : context.getExtra()),
				context == null ? null : context.getStep());
	}

	/**
	 * 槽位放行：purpose 与已解析槽位（以及当前步骤）的组合决定"这张表还有没有真实缺口"。
	 *
	 * <p>拒绝是<b>零副作用</b>的（调用方不挂起、不下发、不认领、不记台账），模型收到可读码与
	 * 下一步指引后可按规则 29 做一次不同的纠正；槽位缺失/歧义与 {@code GENERAL_CLARIFY} 一律放行。
	 *
	 * <p>本批（2026-10-29 闭环批）新增两条：
	 * <ul>
	 * <li>{@code TASK_TYPE} 且任务类型已确定 ⇒ 拒绝并指回 {@code create_task}（类型不是缺口）；</li>
	 * <li><b>{@code SET_CONDITIONS}</b>：<b>只有 {@code QUERY_COND} 允许</b>条件表单 ——
	 * {@code SELECT_DEFS} 等前置步拒绝并指引先 {@code confirm_step} 推进；其它步骤（含 {@code EXPORT} 及之后）
	 * 拒绝并指引按既有步骤链继续；步骤未知时按槽位规则兼容放行（未知 ≠ 关闭）。</li>
	 * </ul>
	 */
	private Verdict inspectSlots(String purpose, TaskSlots slots, String step) {
		if (GenerativeFormRules.PURPOSE_SELECT_DEFINITIONS.equals(purpose) && slots.hasDefCodes()) {
			String known = String.join(", ", slots.defCodes());
			List<FormIssue> issues = List.of(FormIssue.form(FormIssueCodes.FORM_SLOT_ALREADY_KNOWN,
					"已确定配置项：" + known + "（来源：任务/工作区或用户明确值）"));
			List<String> reasons = List.of(
					"已确定配置项：" + known + "（系统解析，不是猜测）",
					"下一步：直接调用 select_definitions（codes=[" + quoted(known) + "]），不要再用表单重复收集");
			log.warn("generative_form 选择表单被槽位放行拒绝（槽位已确定，未挂起、未下发前端）：{}", known);
			return new Verdict(false, ErrorCodes.FORM_SLOTS_ALREADY_KNOWN,
					"配置项已确定，选择表单未下发（工具未执行、未向前端下发表单）", reasons, issues, null);
		}
		if (GenerativeFormRules.PURPOSE_SET_CONDITIONS.equals(purpose) && slots.conditionsCoverDefCodes()) {
			String known = renderConditions(slots);
			List<FormIssue> issues = List.of(FormIssue.form(FormIssueCodes.FORM_SLOT_ALREADY_KNOWN,
					"已确定条件：" + known + "（来源：任务/工作区或用户明确值）"));
			List<String> reasons = List.of(
					"已确定条件：" + known + "（系统解析，不是猜测）",
					"下一步：按既有步骤链调用 set_condition；若当前是 SELECT_DEFS，先 confirm_step（不传 step）"
							+ "推进到 QUERY_COND，下一批再 set_condition");
			log.warn("generative_form 条件表单被槽位放行拒绝（条件已确定，未挂起、未下发前端）：{}", known);
			return new Verdict(false, ErrorCodes.FORM_SLOTS_ALREADY_KNOWN,
					"筛选条件已确定，条件表单未下发（工具未执行、未向前端下发表单）", reasons, issues, null);
		}
		if (GenerativeFormRules.PURPOSE_TASK_TYPE.equals(purpose) && slots.taskType() != null) {
			String known = slots.taskType();
			List<FormIssue> issues = List.of(FormIssue.form(FormIssueCodes.FORM_SLOT_ALREADY_KNOWN,
					"已确定任务类型：" + known + "（来源：任务/工作区或用户明确值）"));
			List<String> reasons = List.of(
					"已确定任务类型：" + known + "（系统解析，不是猜测）",
					"下一步：直接调用 create_task（type=" + known + "，title 可选），不要再用表单重复收集类型");
			log.warn("generative_form 类型表单被槽位放行拒绝（任务类型已确定，未挂起、未下发前端）：{}", known);
			return new Verdict(false, ErrorCodes.FORM_SLOTS_ALREADY_KNOWN,
					"任务类型已确定，类型表单未下发（工具未执行、未向前端下发表单）", reasons, issues, null);
		}
		if (GenerativeFormRules.PURPOSE_SET_CONDITIONS.equals(purpose) && !conditionsStepAllowed(step)) {
			String current = step.trim();
			boolean before = beforeConditionStep(current);
			List<FormIssue> issues = List.of(FormIssue.form(FormIssueCodes.FORM_SLOT_ALREADY_KNOWN,
					(before ? "条件表单只能在 QUERY_COND 下发：当前步骤 " : "查询条件步已关闭：当前步骤 ") + current
							+ "（条件只在 QUERY_COND 收集）"));
			List<String> reasons = before
					? List.of(
							"当前步骤 " + current + " 还不收集条件：条件只在 QUERY_COND 收集（系统状态，不是猜测）",
							"下一步：先 confirm_step（不传 step）推进到 QUERY_COND，下一批再收集一次条件，"
									+ "不要在这一批下发条件表单")
					: List.of(
							"当前步骤 " + current + " 已过 QUERY_COND 或不属于条件收集步：条件只在 QUERY_COND 收集，"
									+ "此时要么已设置、要么按「未设筛选 = 全量」推进（系统状态，不是猜测）",
							"下一步：按既有步骤链调用 confirm_step / start_export 继续，不要重复下发条件表单");
			log.warn("generative_form 条件表单被步骤放行拒绝（当前步骤 {}，不是 QUERY_COND；未挂起、未下发前端）：{}",
					current, before ? "前置步，指引先 confirm_step 推进" : "后置步/非条件步，指引按既有步骤链继续");
			return new Verdict(false, ErrorCodes.FORM_SLOTS_ALREADY_KNOWN,
					(before ? "条件表单只能在 QUERY_COND 下发" : "查询条件步已关闭")
							+ "，条件表单未下发（工具未执行、未向前端下发表单）",
					reasons, issues, null);
		}
		return Verdict.accept();
	}

	/**
	 * 条件收集的步骤门：<b>有明确步骤时只有 {@code QUERY_COND} 允许条件表单</b>，
	 * 其余步骤（{@code SELECT_DEFS}/{@code EXPORT}/{@code DONE}/导入链等）一律拒绝。
	 *
	 * <p>判据本体在 {@link GenerativeFormRules#conditionsStepAllowed} —— 条件表单的下发与这道闸门
	 * <b>共用同一份实现</b>（两处各写一遍必然漂移：表单发的与闸门判的不是同一步骤）。
	 */
	private static boolean conditionsStepAllowed(String step) {
		return GenerativeFormRules.conditionsStepAllowed(step);
	}

	/** 是否还在 {@code QUERY_COND} 之前的前置步：指引是"先 confirm_step 推进"，而不是"步已关闭"。 */
	private static boolean beforeConditionStep(String step) {
		return "SELECT_DEFS".equalsIgnoreCase(step) || "UPLOAD".equalsIgnoreCase(step);
	}

	private static String renderConditions(TaskSlots slots) {
		List<String> rendered = new ArrayList<>();
		for (ConditionSlot condition : slots.conditions()) {
			rendered.add(condition.defCode() + "." + condition.fieldCode() + " = " + condition.value());
		}
		return String.join("；", rendered);
	}

	private static String quoted(String joined) {
		List<String> out = new ArrayList<>();
		for (String code : joined.split(", ")) {
			out.add("\"" + code + "\"");
		}
		return String.join(", ", out);
	}

	/** 回灌前的填写值类型复核（无归属上下文：附件跳过归属校验）。 */
	@Override
	public Verdict inspectResult(String argumentsJson, String resultJson) {
		return inspectResult(null, null, argumentsJson, resultJson);
	}

	/**
	 * 回灌前的填写值复核（带 {@code runId/toolCallId}：附件必须属于<b>本次挂起的这张表单</b>）。
	 *
	 * <h2>只读（2026-10-29 校验与消费分离批）</h2>
	 * 本方法<b>不消费</b>附件、<b>不删</b>盘：它跑在"抢决策 guard / 落库"之前，而这个时点的拒绝
	 * 语义是"可修正后重试"——在那里消费一次性件，任何后续失败都会让"可重试"变成假话
	 * （用户剩下的 fileId 已作废，重试必然 {@code ATTACHMENT_CONSUMED}）。
	 * 消费挪到决策落库之后的 {@link #settleAcceptedResult}。
	 *
	 * <p>多附件在这里<b>全部</b>过一遍三段校验（存在 / 归属 / 形状），一件不合格即整单拒绝，
	 * 不做"先消费前几件"的部分动作。
	 */
	@Override
	public Verdict inspectResult(String runId, String toolCallId, String argumentsJson, String resultJson) {
		JsonNode arguments = parse(argumentsJson);
		JsonNode form = arguments != null && arguments.isObject() ? arguments.get("form") : null;
		JsonNode values = parse(resultJson);
		if (values == null) {
			log.warn("generative_form 回灌结果不是合法 JSON，本次回灌被拒：{}", abbreviate(resultJson));
			List<FormIssue> issues = List.of(FormIssue.form(FormIssueCodes.RESULT_NOT_OBJECT,
					"回灌内容无法解析为 JSON：" + abbreviate(resultJson)
							+ "（应回灌字段 key→值的 JSON 对象；用户放弃请带 cancelled=true）"));
			return Verdict.rejectIssues(GenerativeFormRules.RESULT_REJECTED,
					"回灌的表单结果不是合法 JSON 对象，本次回灌被拒（挂起仍在等待）", issues);
		}
		List<FormIssue> issues = new ArrayList<>(GenerativeFormRules.resultIssues(form, values));
		issues.addAll(attachmentIssues(form, values, runId, toolCallId));
		if (!issues.isEmpty()) {
			log.warn("generative_form 回灌结果未通过复核（回灌被拒、挂起保持未决）：{}",
					GenerativeFormRules.messages(issues));
			return Verdict.rejectIssues(GenerativeFormRules.RESULT_REJECTED,
					"回灌的表单结果未通过类型复核，本次回灌被拒（挂起仍在等待，可修正后重试）", issues);
		}
		String metadata = attachmentMetadata(form, values);
		return metadata == null ? Verdict.accept() : Verdict.accept().withResultSuffix(metadata);
	}

	/**
	 * 决策落库之后消费本次提交的附件（一次性），并回收本表单名下没被提交的登记。
	 *
	 * <p><b>为什么在这里消费</b>：结论已写成 {@code FRONTEND_RESULT}，此时消费失败不再是
	 * "用户改一下再交"能解决的事 —— 于是失败只告警、绝不改变结论，也不对外宣称可重试。
	 * 部分失败最多让未消费的登记活到 TTL（安全方向：多留，不漏删）。
	 *
	 * <p><b>先读后删</b>：物理路径必须在 {@code consume} 之前取（消费会删掉登记条目）；
	 * 读不到的条目直接跳过（登记已过期/已被并发消费），不影响其它件。
	 */
	@Override
	public void settleAcceptedResult(String runId, String toolCallId, String argumentsJson, String resultJson) {
		if (this.attachments == null) {
			return;
		}
		JsonNode arguments = parse(argumentsJson);
		JsonNode form = arguments != null && arguments.isObject() ? arguments.get("form") : null;
		JsonNode values = parse(resultJson);
		List<AttachmentRef> refs = attachmentRefs(form, values);
		if (refs.isEmpty()) {
			// NEW-2（返修二轮）：refs 为空既可能是"确实没传附件"，也可能是"入参/回灌解析失败"。
			// 收口回收若照删，后者会把用户已上传的附件当"未提交"删光 —— 这里 fail-safe：不消费、不回收。
			// 代价只是这些件留到登记 TTL（或会话销毁），安全方向是多留不漏删。
			log.info("回灌里没有可解析的附件引用，跳过附件消费与收口回收 runId={} toolCallId={}", runId,
					toolCallId);
			return;
		}
		// keep = 本次回灌里被提交的全部附件（无论它们最终是否消费成功）：收口回收只清理
		// "没被提交"的件。把消费失败的件也交给回收，等于把"保留到 TTL"偷偷改成"立刻删"。
		Set<String> keep = new LinkedHashSet<>();
		for (AttachmentRef ref : refs) {
			keep.add(ref.fileId());
		}
		Map<String, FormAttachmentStore.Attachment> resolved = new LinkedHashMap<>();
		for (AttachmentRef ref : refs) {
			try {
				FormAttachmentStore.Attachment stored = this.attachments.find(ref.fileId());
				if (stored != null) {
					resolved.put(ref.fileId(), stored);
				}
				else {
					log.info("附件消费时登记已不在（过期或已消费）fileId={} runId={} toolCallId={}", ref.fileId(),
							runId, toolCallId);
				}
			}
			catch (AiStateUnavailableException ex) {
				log.warn("附件消费前读取登记失败（该件保留到 TTL）fileId={} runId={} toolCallId={}：{}", ref.fileId(),
						runId, toolCallId, ex.getMessage());
			}
		}
		for (Map.Entry<String, FormAttachmentStore.Attachment> entry : resolved.entrySet()) {
			try {
				if (this.attachments.consume(entry.getKey())) {
					deleteConsumedFile(entry.getValue());
				}
				else {
					// 决策已定：不判失败、不改结论；该件至多留到 TTL（安全方向是多留不漏删）
					log.warn("附件消费未成功（已被消费或登记过期；决策已落库，不回滚）fileId={} runId={} toolCallId={}",
							entry.getKey(), runId, toolCallId);
				}
			}
			catch (AiStateUnavailableException ex) {
				log.warn("附件消费失败（决策已落库，不回滚）fileId={} runId={} toolCallId={}：{}", entry.getKey(),
						runId, toolCallId, ex.getMessage());
			}
		}
		reclaimFormLeftovers(runId, toolCallId, keep);
	}

	/**
	 * 挂起期续期：把本表单名下的附件登记/索引寿命拉回满额（与待决条目同一条续期链）。
	 *
	 * <p>不续期的后果：表单等得比 {@code app.ai.session-ttl} 久 ⇒ 卡还在、登记先过期
	 * （{@code ATTACHMENT_NOT_FOUND}），且失效文件没有回收入口。
	 */
	@Override
	public void touchSuspension(String runId, String toolCallId) {
		if (this.attachments != null) {
			this.attachments.touch(runId, toolCallId);
		}
	}

	/** 用户放弃该表单：回收它名下所有仍未消费的登记与落盘文件。 */
	@Override
	public void settleDiscardedResult(String runId, String toolCallId) {
		reclaimFormLeftovers(runId, toolCallId, Set.of());
	}

	/**
	 * 表单收口回收：删掉本表单名下<b>没被本次提交消费</b>的登记，并删对应落盘文件。
	 *
	 * <p>什么时候会有这种件：用户传了文件又重传（旧件没人再引用）、传了多个字段只提交一部分、
	 * 直接关掉表单。它们此后再也不可能被提交（该调用已不是 {@code PENDING}，上传口也会拒绝），
	 * 留着只能等 TTL —— 文件就成了孤儿。
	 *
	 * <p>失败只告警：回收是清理面，不得影响已落库的结论。
	 */
	private void reclaimFormLeftovers(String runId, String toolCallId, Set<String> keep) {
		if (this.attachments == null) {
			return;
		}
		try {
			for (FormAttachmentStore.Attachment leftover : this.attachments.findByForm(runId, toolCallId)) {
				if (leftover == null || leftover.fileId() == null || keep.contains(leftover.fileId())) {
					continue;
				}
				FormAttachmentStore.Attachment removed = this.attachments.deleteRegistration(leftover.fileId());
				if (removed != null) {
					deleteConsumedFile(removed);
				}
			}
		}
		catch (RuntimeException ex) {
			// 回收失败不影响结论；残留件仍受登记 TTL 覆盖（与登记同寿）
			log.warn("表单收口回收未提交附件失败 runId={} toolCallId={}：{}", runId, toolCallId, ex.getMessage());
		}
	}

	/**
	 * 附件元数据（给模型看的仅有信息：文件名 / 大小 / fileId —— <b>文件内容永不进入</b>）。
	 * 没有附件字段返回 null（结果文本逐字不变，既有断言不降）。
	 */
	private String attachmentMetadata(JsonNode form, JsonNode values) {
		if (this.attachments == null) {
			return null;
		}
		List<String> parts = new ArrayList<>();
		for (AttachmentRef ref : attachmentRefs(form, values)) {
			try {
				FormAttachmentStore.Attachment stored = this.attachments.find(ref.fileId());
				if (stored != null) {
					parts.add(ref.key() + "=" + stored.fileName() + "（" + stored.size() + " 字节，fileId="
							+ stored.fileId() + "）");
				}
			}
			catch (AiStateUnavailableException ex) {
				// 元数据只是模型可见的补充信息；登记簿暂时不可用时不阻断已通过校验的回灌。
				log.debug("附件元数据读取暂不可用 fileId={}：{}", ref.fileId(), ex.getMessage());
			}
		}
		if (parts.isEmpty()) {
			return null;
		}
		return "\n附件元数据（仅文件名/大小/fileId，文件内容不在上下文中）：" + String.join("；", parts);
	}

	// ── 附件三重校验（G-E） ──────────────────────────────────────────────────

	private List<FormIssue> attachmentIssues(JsonNode form, JsonNode values, String runId, String toolCallId) {
		List<FormIssue> issues = new ArrayList<>();
		for (AttachmentRef ref : attachmentRefs(form, values)) {
			if (this.attachments == null) {
				continue;
			}
			FormAttachmentStore.Attachment stored;
			try {
				stored = this.attachments.find(ref.fileId());
				if (stored == null) {
					if (this.attachments.wasConsumed(ref.fileId())) {
						issues.add(FormIssue.field(ref.key(), FormIssueCodes.ATTACHMENT_CONSUMED,
								"字段 " + ref.key() + " 的附件已被消费（同一 fileId 只能使用一次，fileId="
										+ ref.fileId() + "），请重新上传后再提交"));
					}
					else {
						issues.add(FormIssue.field(ref.key(), FormIssueCodes.ATTACHMENT_NOT_FOUND,
								"字段 " + ref.key() + " 的附件不存在或已过期（fileId=" + ref.fileId()
										+ "），请重新上传后再提交"));
					}
					continue;
				}
			}
			catch (AiStateUnavailableException ex) {
				issues.add(FormIssue.field(ref.key(), FormIssueCodes.ATTACHMENT_UNAVAILABLE,
						"字段 " + ref.key() + " 的附件校验暂不可用，请稍后重试"));
				continue;
			}
			if (runId != null && !runId.equals(stored.runId())) {
				issues.add(FormIssue.field(ref.key(), FormIssueCodes.ATTACHMENT_FORBIDDEN,
						"字段 " + ref.key() + " 的附件不属于本次表单（跨轮次引用被拒），请重新上传"));
				continue;
			}
			if (toolCallId != null && !toolCallId.equals(stored.toolCallId())) {
				issues.add(FormIssue.field(ref.key(), FormIssueCodes.ATTACHMENT_FORBIDDEN,
						"字段 " + ref.key() + " 的附件不属于当前表单调用（跨表单引用被拒），请重新上传"));
			}
		}
		return issues;
	}

	/**
	 * 一次性件被消费后已无第二次读取机会：删除落盘文件，避免附件目录只增不减。
	 *
	 * <p>登记簿里存的是<b>相对存储键</b>（新上传件）或历史绝对路径（迁移前登记）：两者都交给
	 * {@code FileStorageService#delete} 经 {@code resolve} 还原成本实例路径，调用方不拼路径。
	 *
	 * <p>删盘失败只告警、不回滚"已消费"结论（登记已删、墓碑已置位，重试也拿不回文件）；
	 * 反过来，消费失败（返回 false）时<b>不</b>删盘 —— 文件仍属可用登记。
	 */
	private void deleteConsumedFile(FormAttachmentStore.Attachment stored) {
		if (this.fileStorage == null || stored == null || stored.storagePath() == null
				|| stored.storagePath().isBlank()) {
			return;
		}
		try {
			this.fileStorage.delete(stored.storagePath());
		}
		catch (RuntimeException ex) {
			log.warn("附件消费后删除落盘文件失败 fileId={} storageKey={}：{}", stored.fileId(),
					stored.storagePath(), ex.getMessage());
		}
	}

	/** 本轮回灌里实际带了值的 attachment 字段（只收 fileId 形状合法的值）。 */
	private List<AttachmentRef> attachmentRefs(JsonNode form, JsonNode values) {
		List<AttachmentRef> refs = new ArrayList<>();
		if (form == null || !form.isObject() || values == null || !values.isObject()) {
			return refs;
		}
		Set<String> seen = new LinkedHashSet<>();
		for (JsonNode field : form.path("fields")) {
			if (!"attachment".equals(field.path("type").asText())) {
				continue;
			}
			String key = field.path("key").asText("");
			JsonNode value = key.isEmpty() ? null : values.get(key);
			if (value == null || !value.isTextual() || !seen.add(value.asText())) {
				continue;
			}
			if (value.asText().startsWith("att_")) {
				refs.add(new AttachmentRef(key, value.asText()));
			}
		}
		return refs;
	}

	private record AttachmentRef(String key, String fileId) {
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
