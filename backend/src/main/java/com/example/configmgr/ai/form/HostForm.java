package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.RunContextRefresher;
import com.example.configmgr.ai.history.SyntheticMessages;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 宿主确定性表单的<b>共用挂起面</b>：三张宿主表单（选择配置项 / 任务类型 / 筛选条件）走的是
 * <b>同一份</b>挂起、去重、序列化与失败收敛逻辑，只有"什么时候该发"这一条按节点各不相同。
 *
 * <h2>为什么抽出基类</h2>
 * 挂起面不是"发一帧"那么简单：外置挂起态先于任何帧、正文/推理元数据必须沿用、发帧失败不得交回
 * 模型（否则 SUSPENDED + 待决条目 + 模型在跑 = 双驱动）、写挂起失败必须把条目收敛成终态（否则
 * 一条看不见的 PENDING 会永远挡住续跑）。这些约束<b>逐字相同</b>；复制三份必然漂移 —— 修了一处
 * 忘了另两处，症状是"某张卡挂起时偶发双驱动/卡死"，且极难复现。故这里只留一份实现，子类只负责
 * <b>判据 + 表单正文</b>。
 *
 * <h2>复用的是既有机制，不新增中断路径</h2>
 * 挂起面与模型自己下发的 {@code generative_form} 完全一致：
 * {@link RunStore#saveSuspended} → {@code suspended} 帧 → {@link ConfirmGate#publishRequestsForParkedBatch}
 * → 本轮收流。用户的提交/取消走既有 {@code POST /api/ai/frontend-tool-result} 与既有续跑，
 * 值经既有回灌闸门（{@link GenerativeFormGuard#inspectResult}）复核后作为工具结果回填模型。
 *
 * <p><b>表单正文也要过同一份白名单</b>（{@link #formArguments}）：宿主自建的表单与模型自发的表单
 * 在 schema 上没有特权 —— 它同样必须满足 {@link GenerativeFormRules#argumentIssues}，否则不下发。
 * 这样"宿主表单"永远不可能成为绕过 DC-15 闸门的第二条路径。
 *
 * <p><b>只在 park 装配下生效</b>（{@code app.ai.park.enabled}，生产 yml = true）：阻塞式等待
 * （park=false）意味着"等人在同一个工作线程里"，把它用在这些流程上就是新的中断路径。
 */
@Slf4j
abstract class HostForm {

	private static final ObjectMapper JSON = new ObjectMapper();

	protected final RunStore store;

	protected final RunRegistry registry;

	protected final ConfirmGate gate;

	protected final MessageJsonCodec codec;

	protected final AiProperties properties;

	protected HostForm(RunStore store, RunRegistry registry, ConfirmGate gate, MessageJsonCodec codec,
			AiProperties properties) {
		this.store = store;
		this.registry = registry;
		this.gate = gate;
		this.codec = codec;
		this.properties = properties;
	}

	/** 宿主表单只在 park 装配下生效（见类注）。 */
	protected boolean parkEnabled() {
		return this.properties != null && this.properties.getPark().isEnabled();
	}

	/**
	 * 表单参数（{@code {"form":…}}）的序列化与<b>白名单自检</b>。
	 *
	 * @return {@code empty} = 超长或未通过 schema 白名单（<b>不得</b>下发一张自己都过不了闸门的表）
	 */
	protected Optional<String> formArguments(Map<String, Object> form) {
		Map<String, Object> arguments = new LinkedHashMap<>();
		arguments.put("form", form);
		String json;
		try {
			json = JSON.writeValueAsString(arguments);
		}
		catch (Exception ex) {
			log.warn("宿主表单序列化失败，本次不下发：{}", ex.getMessage());
			return Optional.empty();
		}
		if (json.length() > GenerativeFormRules.MAX_FORM_CHARS) {
			log.warn("宿主表单长度 {} 超出上限 {}，本次不下发", json.length(), GenerativeFormRules.MAX_FORM_CHARS);
			return Optional.empty();
		}
		List<FormIssue> issues = GenerativeFormRules.argumentIssues(parseJson(json));
		if (!issues.isEmpty()) {
			// 宿主自己写坏的表单：宁可不下发，也不能让用户看到一张"点了也没用"的卡
			log.warn("宿主表单未通过白名单校验（本次不下发）：{}", GenerativeFormRules.messages(issues));
			return Optional.empty();
		}
		return Optional.of(json);
	}

	/**
	 * 同一张宿主表单只挂一次：{@code toolCallId} 是确定性的，待决条目里已有同号即视为已挂过。
	 *
	 * <p>读不出来就不冒"重复弹表"的险：宁可不发，交回既有路径。
	 */
	protected boolean alreadyIssued(String runId, String toolCallId) {
		List<PendingToolCall> pendings;
		try {
			pendings = this.store.pendingsLenient(runId);
		}
		catch (RuntimeException ex) {
			log.warn("读取待决条目失败，宿主不生成表单 runId={}：{}", runId, ex.getMessage());
			return true;
		}
		for (PendingToolCall pending : pendings == null ? List.<PendingToolCall>of() : pendings) {
			if (pending != null && toolCallId.equals(pending.getToolCallId())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 既有挂起面（与模型自己下发的 {@code generative_form} 同一条路）：外置挂起态 → {@code suspended}
	 * → 请求帧 → 本轮收流。顺序是硬要求（{@code saveSuspended} 先于任何帧）。
	 *
	 * <p><b>正文/推理元数据沿用当前快照</b>：{@code saveSuspended} 会把 {@code assistantContent} 与
	 * {@code assistantMetadata} 一起覆盖写 —— 这里传 {@code ""}/{@code null} 等于抹掉模型刚产出的那一轮。
	 * 挂起只该换"谁在等"，因此先读快照再原样带上（6 参重载）。
	 *
	 * @return {@code true} = 已挂起：调用方本拍<b>不得</b>再驱动模型（这一轮在等人填表）
	 */
	protected boolean park(String runId, List<Message> history, PendingToolCall pending) {
		try {
			RunSnapshot current = this.store.get(runId);
			this.store.saveSuspended(runId, current == null ? null : current.getAssistantContent(),
					List.of(pending), history, this.codec,
					current == null ? null : current.getAssistantMetadata());
		}
		catch (RuntimeException ex) {
			// 写挂起态失败：<b>不得留下一条看不见的待决条目</b>（saveSuspended 先写条目再写快照，
			// 快照那一步失败时条目会留在 Redis 里；前端没有请求帧、用户看不到卡，而后续续跑会被
			// 既有 PENDING_UNRESOLVED 前置挡住 —— 一轮就此卡死）。这里尽力把它收敛成终态。
			convergeAfterParkFailure(runId, pending, ex);
			return false;
		}
		SseChatEmitter out;
		try {
			out = this.registry.of(runId);
			out.suspended(List.of(pending), this.store.runKey(runId), this.store.pendingKey(runId),
					this.store.instanceId());
		}
		catch (RuntimeException ex) {
			// 快照已成功挂起 ⇒ 这一拍<b>绝不能</b>交回模型（否则 SUSPENDED + 待决条目 + 模型在跑 =
			// 双驱动：同一轮的帧与历史会分叉）。发帧是观测面，失败只告警，等恢复。
			log.warn("宿主表单已挂起，但 suspended 帧发布失败（等待恢复，本拍不驱动模型）runId={} toolCallId={}：{}",
					runId, pending.getToolCallId(), ex.getMessage());
			return true;
		}
		try {
			this.gate.publishRequestsForParkedBatch(runId, List.of(pending));
		}
		catch (RuntimeException ex) {
			log.warn("宿主表单已挂起，但请求帧发布失败（等待恢复，本拍不驱动模型）runId={} toolCallId={}：{}",
					runId, pending.getToolCallId(), ex.getMessage());
			return true;
		}
		try {
			// "挂起即断"（B1）：请求帧送达后本轮订阅收流，用户提交/取消后再带 lastSeq 重连
			out.completeSubscriptions();
		}
		catch (RuntimeException ex) {
			// 收流失败不回退：条目与快照已就位，重挂/刷新仍能取到这张卡
			log.warn("宿主表单已挂起，但本轮收流失败（不影响挂起态）runId={} toolCallId={}：{}", runId,
					pending.getToolCallId(), ex.getMessage());
		}
		log.info("宿主已挂起表单 runId={} toolCallId={}（等用户填写）", runId, pending.getToolCallId());
		return true;
	}

	/** 挂起失败后的尽力收敛：把条目落成"未执行"的终态，避免它永远以 PENDING 挡住续跑。 */
	private void convergeAfterParkFailure(String runId, PendingToolCall pending, RuntimeException cause) {
		log.warn("宿主表单挂起写失败（本拍不挂起，按既有路径继续）runId={} toolCallId={}：{}", runId,
				pending.getToolCallId(), cause.getMessage());
		try {
			pending.setStatus(PendingToolCall.BLOCKED);
			pending.setOutcome(ToolOutcome.FAILED.wire());
			pending.setCode(ToolOutcome.CODE_TOOL_EXECUTION_FAILED);
			pending.setReason("宿主表单挂起失败，未下发前端");
			pending.setExecuted(false);
			pending.setResolvedAtMs(System.currentTimeMillis());
			this.store.putPending(runId, pending);
		}
		catch (RuntimeException nested) {
			log.warn("宿主表单挂起失败后的条目收敛也失败（该条目留在 PENDING）runId={} toolCallId={}：{}", runId,
					pending.getToolCallId(), nested.getMessage());
		}
	}

	/** 待决条目的形状（与模型自发的 {@code generative_form} 同形）：前端工具、非不可逆。 */
	protected static PendingToolCall frontendForm(String toolCallId, String arguments) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName(GenerativeFormRules.TOOL_NAME);
		pending.setType("function");
		pending.setArguments(arguments);
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		// 表单是"收集值"，不是不可逆写：不要不确定性的复选框语义
		pending.setIrreversible(false);
		return pending;
	}

	/**
	 * 本轮是否已创建任务（AR-03 的同一份台账口径）；回执里没有任务号 = 不算"新任务链"。
	 *
	 * <p>{@code create_task} 成功是"用户主动新建"的唯一证据：普通浏览/打开既有任务不会产生它。
	 */
	protected Long createdTaskInRun(String runId) {
		List<Map<String, Object>> ledger;
		try {
			ledger = this.store.ledger(runId);
		}
		catch (RuntimeException ex) {
			log.warn("读取本轮台账失败，宿主不生成表单 runId={}：{}", runId, ex.getMessage());
			return null;
		}
		if (ledger == null || ledger.isEmpty()) {
			return null;
		}
		for (Map<String, Object> record : ledger) {
			if (record == null || !RunContextRefresher.TOOL_CREATE_TASK.equals(record.get("name"))) {
				continue;
			}
			if (!ToolOutcome.OK.wire().equals(String.valueOf(record.get("outcome")))) {
				continue;
			}
			Long taskId = RunContextRefresher.taskIdFromText(String.valueOf(record.get("resultText")));
			if (taskId != null) {
				return taskId;
			}
		}
		return null;
	}

	/** 本批已经有模型自己的同名调用：宿主不再重复（同一缺口只问一次）。 */
	protected static boolean handledInBatch(List<PendingToolCall> settledBatch, String... toolNames) {
		if (settledBatch == null || toolNames == null) {
			return false;
		}
		for (PendingToolCall settled : settledBatch) {
			if (settled == null || settled.getName() == null) {
				continue;
			}
			for (String toolName : toolNames) {
				if (toolName.equals(settled.getName())) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * 交棒历史里最后一条<b>真实用户</b>原文（续跑没有新用户消息，用的就是这一条）。
	 *
	 * <p>跳过合成消息（压缩摘要 / 步数或预算收尾提示，判据见 {@code SyntheticMessages}）：它们是系统
	 * 提示，不是用户原话。把它们当"最后一句"会让"继续任务"的意图凭空消失（表单不再挂起），
	 * 这与 {@code TaskSlotResolver} 读历史时的过滤是同一条口径。
	 */
	protected static String lastUserText(List<Message> history) {
		if (history == null) {
			return null;
		}
		for (int i = history.size() - 1; i >= 0; i--) {
			Message message = history.get(i);
			if (message instanceof UserMessage user && !SyntheticMessages.isSynthetic(message)) {
				return user.getText();
			}
		}
		return null;
	}

	protected static JsonNode parseJson(String json) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			return JSON.readTree(json);
		}
		catch (Exception ex) {
			return null;
		}
	}

}
