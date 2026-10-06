package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.StreamViolationException;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 官方循环内的工具执行扩展点（S4.2 §2 {@code gate/SpToolCallingManager}）。
 *
 * <h2>组合而非替换（DC-05 官方能力优先）</h2>
 * 协议、循环、工具 Schema、入参解析全部由 Spring AI 负责
 * （{@code OpenAiChatModel} 内部 isToolExecutionRequired → executeToolCalls → 回填 → 再请求模型）；
 * 本类<b>组合</b>官方 {@link ToolCallingManager#builder() DefaultToolCallingManager}
 * 作为 delegate，只在每一次工具执行处插入三件事：
 * <ul>
 * <li><b>可见性钩子</b>：{@code tool_start} / {@code tool_result} 帧 —— 官方循环在
 * {@code returnDirect=false} 时<b>不把中间工具轮放进客户端流</b>（S4.2-P1 §4.4-3 实测：5 轮 10 次上游
 * 请求，客户端 0 个工具事件），因此工具卡片只能由这里提供；</li>
 * <li><b>确认门</b>：风险等级 {@code DANGER} 的工具（读基座 {@link ToolRegistry} 的
 * {@code @ToolRisk} 分级）执行前挂起等人工决策（{@link ConfirmGate}）；</li>
 * <li><b>前端工具挂起</b>：通道为 {@code FRONTEND} 的工具（{@code @ToolChannel}）不执行后端桩体，
 * 下发 {@code frontend_tool_request} 并阻塞等 {@code POST /api/ai/frontend-tool-result} 回灌。</li>
 * </ul>
 *
 * <h2>硬规范①：外置先于阻塞</h2>
 * 进入任何等待之前，先把 {@code assistant(tool_calls)} 与每个待决条目写进 Redis
 * （{@link RunStore#saveSuspended}）；进程内的闸门（{@link ConfirmGate} 的 Latch）只做唤醒。
 * 写失败一律抛出 → 由调用方回落官方执行，绝不出现"阻塞住了但 Redis 里没有状态"。
 *
 * <h2>硬规范②：续跑必须重建完整历史（本类也守同一条）</h2>
 * 官方 {@code DefaultToolCallingManager} 返回的 {@code conversationHistory} 是
 * <b>{@code prompt.getInstructions() + [assistant(tool_calls), tool]}</b>
 * （1.1.8 字节码 {@code buildConversationHistoryAfterToolExecution} 实测）；
 * 若像 SP-02 的 PoC 那样只返回 {@code [assistant, tool]}，官方循环的下一跳请求就会丢掉
 * system/user —— 这正是规格所称"官方循环自身工具后续跑会丢 system/user"的<b>真正来源是 PoC 实现</b>，
 * 官方实现本身不丢。本类按官方口径拼装，因此：
 * <ul>
 * <li>本轮内每一跳请求都保留 system/user（P1 的 req13/15/17 报文为同一口径：4/6/8 条消息）；</li>
 * <li>{@link RunSnapshot#getMessageJson()} 写的就是模型实际看到的那串历史，续跑据此重建。</li>
 * </ul>
 *
 * <h2>不落地的路径（诚实回落）</h2>
 * 拿不到 {@code runId}（无 toolContext）或 Redis 里没有本轮快照时，挂起态无法外置，
 * 本类<b>原样委托官方执行</b>并留 WARN —— 宁可无 HITL，也不制造"挂起但不可恢复"的死轮。
 */
@Slf4j
@Component
public class SpToolCallingManager implements ToolCallingManager {

	/** 结果文本进帧的截断长度（模型收到的是全文，只有帧里截断）。 */
	private static final int FRAME_TEXT_LIMIT = 800;

	private final ToolCallingManager delegate;

	private final RunStore store;

	private final RunRegistry registry;

	private final ConfirmGate gate;

	private final ToolRegistry toolRegistry;

	private final MessageJsonCodec codec;

	private final ToolActivityBeacon beacon;

	private final CancellationRegistry cancellations;

	public SpToolCallingManager(RunStore store, RunRegistry registry, ConfirmGate gate, ToolRegistry toolRegistry,
			MessageJsonCodec codec, ToolActivityBeacon beacon, CancellationRegistry cancellations) {
		this.delegate = ToolCallingManager.builder().build();
		this.store = store;
		this.registry = registry;
		this.gate = gate;
		this.toolRegistry = toolRegistry;
		this.codec = codec;
		this.beacon = beacon;
		this.cancellations = cancellations;
	}

	@Override
	public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
		return this.delegate.resolveToolDefinitions(chatOptions);
	}

	@Override
	public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
		AssistantMessage assistant = chatResponse.getResult() == null ? null : chatResponse.getResult().getOutput();
		if (assistant == null || !assistant.hasToolCalls()) {
			return this.delegate.executeToolCalls(prompt, chatResponse);
		}

		String runId = runIdOf(prompt);
		if (runId == null || this.store.get(runId) == null) {
			log.warn("工具执行处拿不到本轮 runId/快照（runId={}），挂起态无法外置，回落官方默认执行：{}",
					runId, assistant.getToolCalls().stream().map(AssistantMessage.ToolCall::name).toList());
			return this.delegate.executeToolCalls(prompt, chatResponse);
		}

		SseChatEmitter out = this.registry.of(runId);
		List<PendingToolCall> pendings = newPendings(assistant);

		// ===== 硬规范①：先把 assistant(tool_calls) 与待决条目外置，再进入任何等待 =====
		this.store.saveSuspended(runId, assistant.getText(), pendings, prompt.getInstructions(), this.codec);
		out.suspended(pendings, this.store.runKey(runId), this.store.pendingKey(runId), this.store.instanceId());

		List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(pendings.size());
		for (PendingToolCall pending : pendings) {
			// 事件边界上的取消检查（ADR-8 修正③：取消在"分片/事件边界"轮询）：
			// 逐个工具执行前先看一次权威标志，命中即终止整轮（终态 done{cancelled:true}）。
			checkCancelled(runId);
			out.toolStart(pending);
			// 副作用信标（韧性棒）：工具开始执行即留下痕迹 ——
			// ① 让看门狗在工具执行期间不计静默（硬规范①）；② 让"已产出内容前先有副作用"的重试被拦住。
			this.beacon.enter(runId, pending.getName(), pending.getKind());
			String text;
			try {
				text = switch (pending.getKind()) {
					case PendingToolCall.KIND_FRONTEND -> awaitFrontend(runId, pending);
					case PendingToolCall.KIND_CONFIRM -> awaitConfirm(runId, prompt, pending);
					default -> executeOnce(runId, prompt, pending);
				};
			}
			finally {
				this.beacon.exit(runId, "done");
			}
			// 执行之后再查一次：确认门/前端工具可能正是在等待期间被取消
			checkCancelled(runId);
			responses.add(new ToolResponseMessage.ToolResponse(pending.getToolCallId(), pending.getName(), text));
		}

		ToolResponseMessage toolMessage = ToolResponseMessage.builder().responses(responses).build();
		// ===== 落定本轮：与官方 buildConversationHistoryAfterToolExecution 同构 =====
		List<Message> history = new ArrayList<>(prompt.getInstructions());
		history.add(assistant);
		history.add(toolMessage);
		settle(runId, history);

		return ToolExecutionResult.builder().conversationHistory(history).build();
	}

	// ── 三通道 ──────────────────────────────────────────────────────────────

	/** 事件边界上的取消检查：命中即抛出，交由 {@code ResilientChatService} 以取消收尾。 */
	private void checkCancelled(String runId) {
		if (this.cancellations.isCancelled(runId)) {
			throw new StreamViolationException(StreamViolationException.CANCELLED,
					"本轮对话已被取消，工具执行在事件边界终止（runId=" + runId + "）");
		}
	}

	/** 前端工具：不执行后端桩体，挂起等前端回灌（结局帧由 ConfirmGate 侧发出）。 */
	private String awaitFrontend(String runId, PendingToolCall pending) {
		PendingToolCall resolved = this.gate.awaitFrontendResult(runId, pending);
		return resolved.getResultText() == null ? "" : resolved.getResultText();
	}

	/** 确认门：放行则执行（经认领与台账，恰好一次），拒绝/超时以"未执行"语义回填并继续循环。 */
	private String awaitConfirm(String runId, Prompt prompt, PendingToolCall pending) {
		PendingToolCall resolved = this.gate.awaitDecision(runId, pending);
		if (PendingToolCall.APPROVED.equals(resolved.getStatus())) {
			return executeOnce(runId, prompt, resolved);
		}
		String text = resolved.getResultText() == null ? "该操作未执行。" : resolved.getResultText();
		this.registry.of(runId).toolResult(resolved, false, false, text);
		return text;
	}

	/**
	 * 后端执行（含放行后的敏感工具）：跨进程台账防重放 + 执行前原子认领。
	 *
	 * <ol>
	 * <li>台账里已有同一 {@code toolCallId} → 复用原结果，不再执行（跨进程"不重放"）；</li>
	 * <li>认领失败（他实例已认领）→ 不重复执行（避免重复副作用），如实回填；</li>
	 * <li>认领成功 → 委托官方执行 → 结果写回待决条目并记台账（含 {@code executedBy}）。</li>
	 * </ol>
	 */
	private String executeOnce(String runId, Prompt prompt, PendingToolCall pending) {
		Map<String, Object> ledger = this.store.ledgerFor(runId, pending.getToolCallId());
		if (ledger != null) {
			String reusedText = String.valueOf(ledger.getOrDefault("resultText", ""));
			pending.setExecuted(true);
			pending.setExecutedBy(String.valueOf(ledger.get("executedBy")));
			pending.setResultText(reusedText);
			this.store.putPending(runId, pending);
			this.registry.of(runId).toolResult(pending, true, true, reuseFrame(reusedText));
			return reusedText;
		}

		if (!this.store.claimExecution(runId, pending.getToolCallId(), this.store.instanceId())) {
			String claimedBy = this.store.claimedBy(runId, pending.getToolCallId());
			String text = "该操作已由实例 " + claimedBy + " 认领执行（进程在其完成前退出）。"
					+ "为避免重复副作用，本次不再执行；如需重做，请让用户重新发起。";
			pending.setExecuted(true);
			pending.setExecutedBy(claimedBy);
			pending.setResultText(text);
			this.store.putPending(runId, pending);
			this.registry.of(runId).toolResult(pending, false, true, reuseFrame(text));
			return text;
		}

		String text;
		boolean ok = true;
		try {
			text = delegateSingle(prompt, pending);
		}
		catch (Exception ex) {
			log.warn("工具执行失败 runId={} name={}", runId, pending.getName(), ex);
			ok = false;
			text = "工具执行失败：" + ex.getMessage();
		}
		this.store.appendLedger(runId, pending, text);

		PendingToolCall fresh = this.store.pending(runId, pending.getToolCallId());
		if (fresh == null) {
			fresh = pending;
		}
		fresh.setResultText(text);
		fresh.setExecuted(true);
		fresh.setExecutedBy(this.store.instanceId());
		fresh.setExecutedAtMs(System.currentTimeMillis());
		fresh.setReason(PendingToolCall.KIND_BACKEND.equals(fresh.getKind()) ? "IN_PROCESS_EXECUTION" : fresh.getReason());
		if (PendingToolCall.KIND_BACKEND.equals(fresh.getKind())) {
			fresh.setStatus(PendingToolCall.EXECUTED);
			fresh.setResolvedAtMs(fresh.getExecutedAtMs());
		}
		this.store.putPending(runId, fresh);
		this.registry.of(runId).toolResult(fresh, ok, false, reuseFrame(text));
		return text;
	}

	/** 用官方 DefaultToolCallingManager 执行单个工具调用（只借官方执行，不接管协议）。 */
	private String delegateSingle(Prompt prompt, PendingToolCall pending) {
		AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(pending.getToolCallId(),
				pending.getType() == null ? "function" : pending.getType(), pending.getName(), pending.getArguments());
		AssistantMessage single = AssistantMessage.builder().toolCalls(List.of(call)).build();
		ToolExecutionResult result = this.delegate.executeToolCalls(prompt,
				new ChatResponse(List.of(new Generation(single))));
		return result.conversationHistory()
			.stream()
			.filter(ToolResponseMessage.class::isInstance)
			.map(ToolResponseMessage.class::cast)
			.flatMap(message -> message.getResponses().stream())
			.filter(response -> pending.getToolCallId() != null && pending.getToolCallId().equals(response.id()))
			.map(ToolResponseMessage.ToolResponse::responseData)
			.findFirst()
			.orElse("");
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	/**
	 * 续跑阶段结算一个挂起条目（{@code ResumeService} 的重建路径专用，SP-02 §5.5）。
	 *
	 * <ul>
	 * <li>{@code PENDING} + {@code BACKEND}：后端工具不需要外部输入，直接补执行（进程死在
	 * "已写条目、未执行"之间时的正常路径）；</li>
	 * <li>{@code APPROVED}：补执行恰好一次（E2 窗口）；</li>
	 * <li>其余（{@code EXECUTED}/{@code REJECTED}/{@code TIMEOUT}/{@code FRONTEND_RESULT}）：
	 * 一律复用 {@code resultText}，<b>不执行</b>。</li>
	 * </ul>
	 *
	 * @return 该条目对应的工具结果文本（用于重建 {@code role:tool} 消息）
	 */
	public String resolvePending(String runId, Prompt prompt, PendingToolCall pending) {
		String status = pending.getStatus();
		boolean shouldExecute = PendingToolCall.APPROVED.equals(status)
				|| (PendingToolCall.PENDING.equals(status) && PendingToolCall.KIND_BACKEND.equals(pending.getKind()))
				|| (PendingToolCall.EXECUTED.equals(status) && !pending.isExecuted());
		if (shouldExecute) {
			return executeOnce(runId, prompt, pending);
		}
		String text = pending.getResultText() == null ? "" : pending.getResultText();
		log.debug("续跑结算复用结果 runId={} toolCallId={} status={} executedBy={}", runId, pending.getToolCallId(),
				status, pending.getExecutedBy());
		return text;
	}

	/** 挂起种类由基座元数据决定：通道优先（前端工具不可被后端执行），其次风险等级。 */
	private List<PendingToolCall> newPendings(AssistantMessage assistant) {
		List<PendingToolCall> out = new ArrayList<>();
		for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
			PendingToolCall pending = new PendingToolCall();
			pending.setToolCallId(call.id());
			pending.setName(call.name());
			pending.setType(call.type() == null ? "function" : call.type());
			pending.setArguments(call.arguments());
			pending.setKind(kindOf(call.name()));
			out.add(pending);
		}
		return out;
	}

	private String kindOf(String toolName) {
		if (this.toolRegistry.channelOf(toolName) == ToolMeta.Channel.FRONTEND) {
			return PendingToolCall.KIND_FRONTEND;
		}
		if (this.toolRegistry.riskOf(toolName) == ToolMeta.RiskLevel.DANGER) {
			return PendingToolCall.KIND_CONFIRM;
		}
		return PendingToolCall.KIND_BACKEND;
	}

	/** 落定本轮：历史进快照、in-flight 清空、状态回到 RUNNING（工具已全部结算）。 */
	private void settle(String runId, List<Message> history) {
		RunSnapshot snapshot = this.store.get(runId);
		if (snapshot == null) {
			return;
		}
		snapshot.setMessageJson(this.codec.serializeAll(history));
		snapshot.setInFlightToolCalls(new ArrayList<>());
		snapshot.setAssistantContent(null);
		snapshot.setStatus(RunSnapshot.RUNNING);
		snapshot.setRound(snapshot.getRound() + 1);
		this.store.save(snapshot);
	}

	private String runIdOf(Prompt prompt) {
		if (prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolContext() != null) {
			Object runId = options.getToolContext().get("runId");
			return runId == null ? null : String.valueOf(runId);
		}
		return null;
	}

	/** 帧里的结果文本截断（模型仍拿到全文）。 */
	private String reuseFrame(String text) {
		if (text == null) {
			return "";
		}
		return text.length() > FRAME_TEXT_LIMIT ? text.substring(0, FRAME_TEXT_LIMIT) + "…" : text;
	}

	/** 供诊断端点/日志：本管理器确实被装配。 */
	public String describe() {
		return "delegate=" + this.delegate.getClass().getName() + ", instanceId=" + this.store.instanceId();
	}

}
