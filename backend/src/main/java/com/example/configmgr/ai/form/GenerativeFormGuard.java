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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code generative_form} 的复核器（DC-15 安全闸）：把模型给的表单 schema 与前端回灌的填写值，
 * 交给 {@link GenerativeFormRules} 的白名单规则判定，产出可回填的 {@link Verdict}。
 *
 * <p>本类只做五件事：解析 JSON、调用纯规则、<b>槽位放行</b>（purpose + 任务槽位）、<b>附件三重校验</b>
 * （G-E：存在 / 归属 / 一次性）、组装结论文本 —— 校验口径<b>全部</b>在 {@link GenerativeFormRules} 里
 * （那里是可以脱离 Spring 直接断言的正反例对象）。
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
 * {@code GENERAL_CLARIFY} 一律放行 —— 未知配置仍可用表单。缺省 purpose 只在结构明确时推断
 * （{@code optionsFrom.sourceId=definitions} → 选择，{@code scenario=FILTER} → 条件），
 * 显式 {@code GENERAL_CLARIFY} 不被形状覆盖；标题/label 不参与判断。上下文由
 * {@link FrontendToolGuard#inspectArguments(String, String, AiContext)} 传入；没有上下文时
 * （既有单测/兼容调用）槽位规则整体跳过。
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
	 * 物理文件存储（可为 null：只做 schema/值校验的单元路径）。非 null 时，附件
	 * {@code consume} 成功后删除落盘文件（一次性件不会再有第二次读取机会）。
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
				TaskSlots.fromExtra(context == null ? null : context.getExtra()));
	}

	/**
	 * 槽位放行：purpose 与已解析槽位的组合决定"这张表还有没有真实缺口"。
	 *
	 * <p>拒绝是<b>零副作用</b>的（调用方不挂起、不下发、不认领、不记台账），模型收到可读码与
	 * 下一步指引后可按规则 29 做一次不同的纠正；槽位缺失/歧义与 {@code GENERAL_CLARIFY} 一律放行。
	 */
	private Verdict inspectSlots(String purpose, TaskSlots slots) {
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
		return Verdict.accept();
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
	 * <p>复核通过后<b>才</b>消费附件（一次性）：不通过时不消费，用户修正后仍可重试；
	 * 消费失败（并发已消费）同样判 {@code ATTACHMENT_CONSUMED}，挂起保持未决。
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
		try {
			// 元数据必须在消费前取（消费会把登记条目删掉）
			String metadata = attachmentMetadata(form, values);
			if (!consumeAttachments(form, values)) {
				List<FormIssue> consumed = List.of(FormIssue.form(FormIssueCodes.ATTACHMENT_CONSUMED,
						"附件已被其它提交消费（同一 fileId 只能使用一次），请重新上传后再提交"));
				return Verdict.rejectIssues(GenerativeFormRules.RESULT_REJECTED,
						"回灌的附件已被消费，本次回灌被拒（挂起仍在等待，请重新上传）", consumed);
			}
			return metadata == null ? Verdict.accept() : Verdict.accept().withResultSuffix(metadata);
		}
		catch (AiStateUnavailableException ex) {
			// I-2：基础设施故障给 typed FormIssue（可重试），不把 Redis 异常原文暴露给用户。
			log.warn("generative_form 附件登记簿暂不可用 runId={} toolCallId={}：{}", runId, toolCallId,
					ex.getMessage());
			List<FormIssue> unavailable = List.of(FormIssue.form(FormIssueCodes.ATTACHMENT_UNAVAILABLE,
					"附件校验暂不可用，请稍后重试"));
			return Verdict.rejectIssues(GenerativeFormRules.RESULT_REJECTED,
					"附件校验暂不可用，本次回灌未执行（挂起仍在等待，请稍后重试）", unavailable);
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

	private boolean consumeAttachments(JsonNode form, JsonNode values) {
		if (this.attachments == null) {
			return true;
		}
		for (AttachmentRef ref : attachmentRefs(form, values)) {
			// 物理路径必须在消费前取（消费会删掉登记条目）；取不到不影响一次性判定。
			FormAttachmentStore.Attachment stored = this.attachments.find(ref.fileId());
			if (!this.attachments.consume(ref.fileId())) {
				return false;
			}
			deleteConsumedFile(stored);
		}
		return true;
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
