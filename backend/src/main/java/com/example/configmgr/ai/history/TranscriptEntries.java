package com.example.configmgr.ai.history;

import com.example.configmgr.ai.context.ToolResultClearing;
import com.example.configmgr.ai.memory.RunMemoryMerge;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 把"一轮历史"投影成 transcript 条目（B2 的唯一投影口）。
 *
 * <p><b>工具原文的来源</b>：终态条目优先取 {@link PendingToolCall#getResultText()}（用户面原文），
 * 只有拿不到时才回落到历史里的 {@code responseData}，且<b>绝不</b>把 L1 占位符写进 transcript。
 * 这样即使 {@code chat:mem} 已被压缩/窗口改写成占位符，用户展示原文也不会被污染。
 *
 * <p><b>合成消息一律不写</b>：压缩摘要、步数/预算收尾、纠正指令都不是用户原话/助手答复，
 * 由 {@link SyntheticMessages#isSynthetic(Message)} 统一过滤。
 */
public final class TranscriptEntries {

	private static final String RUN_ID_KEY = RunMemoryMerge.RUN_ID_KEY;

	private static final String TURN_STARTED_KEY = RunMemoryMerge.TURN_STARTED_AT_KEY;

	private static final String REASONING_KEY = "reasoningContent";

	private static final String STOPPED_KEY = "stopped";

	private static final String TRUNCATED_KEY = "truncated";

	private static final String TOOL_CALL_STATES_KEY = "toolCallStates";

	private TranscriptEntries() {
	}

	public static TranscriptEntry user(String runId, long turnStartedAtMs, long atMs, String text) {
		return new TranscriptEntry(entryId(runId, "user", null), "user", null, runId, turnStartedAtMs, atMs, text,
				null, null, null, null, null, null, null, null, null, null, false, null, null, null, null, null);
	}

	/** 挂起批次的早期条目（status=pending）；终态写点会用同一 entryId 覆盖。 */
	public static TranscriptEntry assistant(String runId, long turnStartedAtMs, long atMs, String text,
			List<TranscriptEntry.ToolCallView> toolCalls, Map<String, Object> toolCallStates) {
		return new TranscriptEntry(assistantEntryId(runId, toolCalls), "assistant", null, runId, turnStartedAtMs,
				atMs, text, null, null, null, toolCalls, toolCallStates, null, null, null, null, null, false, null,
				null, null, null, null);
	}

	/** 单条工具结果（早期 pending / 终态同键覆盖）。 */
	public static TranscriptEntry tool(String runId, long turnStartedAtMs, long atMs, String toolCallId, String name,
			String kind2, String status, String result) {
		return new TranscriptEntry(toolEntryId(runId, toolCallId), "tool", null, runId, turnStartedAtMs, atMs, null,
				null, null, null, null, null, toolCallId, name, kind2, status, result, false, null, null, null, null,
				null);
	}

	/** 一轮完整历史 → 条目（终态写点；{@code pendings} 提供工具原文/状态）。 */
	public static List<TranscriptEntry> fromMessages(List<Message> messages, Map<String, PendingToolCall> pendings,
			boolean baseline, long fallbackTurnStartedAtMs) {
		if (messages == null || messages.isEmpty()) {
			return List.of();
		}
		List<TranscriptEntry> out = new ArrayList<>();
		int index = 0;
		for (Message message : messages) {
			index++;
			if (message == null || message.getMessageType() == MessageType.SYSTEM
					|| SyntheticMessages.isSynthetic(message)) {
				continue;
			}
			Map<String, Object> metadata = message.getMetadata();
			String runId = stringOf(metadata, RUN_ID_KEY);
			long turnStarted = longOf(metadata, TURN_STARTED_KEY, fallbackTurnStartedAtMs);
			long atMs = System.currentTimeMillis();
			if (message instanceof AssistantMessage assistant) {
				List<TranscriptEntry.ToolCallView> calls = toolCalls(assistant);
				String text = assistant.getText();
				String reasoning = stringOf(metadata, REASONING_KEY);
				boolean stopped = Boolean.TRUE.equals(metadata == null ? null : metadata.get(STOPPED_KEY));
				boolean truncated = Boolean.TRUE.equals(metadata == null ? null : metadata.get(TRUNCATED_KEY));
				Map<String, Object> states = mapOf(metadata, TOOL_CALL_STATES_KEY);
				String entryId = runId == null ? "baseline:" + index
						: assistantEntryId(runId, calls);
				out.add(new TranscriptEntry(entryId, baseline ? "baseline" : "assistant",
						baseline ? "ASSISTANT" : null, runId, turnStarted, atMs, text, reasoning,
						stopped ? Boolean.TRUE : null, truncated ? Boolean.TRUE : null,
						calls.isEmpty() ? null : calls, states, null, null, null, null, null, baseline, null, null,
						null, null, null));
				continue;
			}
			if (message instanceof ToolResponseMessage toolResponse) {
				List<ToolResponseMessage.ToolResponse> responses = toolResponse.getResponses();
				for (int i = 0; i < responses.size(); i++) {
					ToolResponseMessage.ToolResponse response = responses.get(i);
					String toolCallId = response.id();
					PendingToolCall pending = toolCallId == null ? null : pendings.get(toolCallId);
					String result = resultOf(pending, response.responseData());
					if (result == null) {
						continue;
					}
					String entryId = runId == null ? "baseline:" + index + ":" + i
							: toolEntryId(runId, toolCallId);
					String name = pending != null && hasText(pending.getName()) ? pending.getName() : response.name();
					EnvelopeFacts facts = factsOf(pending, response.responseData());
					out.add(new TranscriptEntry(entryId, baseline ? "baseline" : "tool",
							baseline ? "TOOL" : null, runId, turnStarted, atMs, null, null, null, null, null, null,
							toolCallId, name, pending == null ? null : pending.getKind(),
							pending == null ? null : pending.getStatus(), result, baseline, facts.code(),
							facts.truncated(), facts.strategy(), facts.originalChars(), facts.returnedChars()));
				}
				continue;
			}
			if (message.getMessageType() == MessageType.USER) {
				String entryId = runId == null ? "baseline:" + index : userEntryId(runId);
				out.add(new TranscriptEntry(entryId, baseline ? "baseline" : "user", baseline ? "USER" : null, runId,
						turnStarted, atMs, message.getText(), null, null, null, null, null, null, null, null, null,
						null, baseline, null, null, null, null, null));
			}
		}
		return out;
	}

	/**
	 * 一轮终态历史 → 本轮条目（只投影<b>本 run</b>的 assistant/tool，避免把快照里携带的旧轮重复写入）。
	 *
	 * <p>判据：工具条目的 toolCallId 必须出现在本 run 的 pending 表；assistant 必须含本 run 的
	 * 工具调用，或它是历史里最后一条 assistant（成功/取消写点追加的终稿/半截）。用户条目这里不写
	 * —— 轮首写点负责，轮首失败由终态写点用快照 {@code requestText} 补写。
	 *
	 * @param includeLastAssistant 成功/取消 = true（追加了终稿）；空回复/失败 = false（没有新终稿）
	 */
	public static List<TranscriptEntry> fromRunTerminal(List<Message> messages, Map<String, PendingToolCall> pendings,
			String runId, long turnStartedAtMs, boolean includeLastAssistant) {
		// 兼容旧调用：无已解析状态、也不查台账（fallback 只认条目自身的 executed 标志）。
		return fromRunTerminal(messages, pendings, runId, turnStartedAtMs, includeLastAssistant, Map.of(), null);
	}

	/**
	 * 同上，并让 tool 条目使用<b>与 assistant {@code toolCallStates} 同一份</b>已解析状态。
	 *
	 * <p>为什么必须传：tool 条目与 assistant 状态各算一遍（一个查台账、一个不查）就会分叉成
	 * {@code assistant=EXECUTED} 而 {@code tool=APPROVED}。调用方在同一次遍历里解析一次，两边共用。
	 *
	 * @param resolvedStatusByToolCallId toolCallId → 已解析 status（生产路径必填，与 assistant 状态同源）
	 * @param executedInLedger            同一会计口径的台账谓词：<b>仅</b>用于 statuses 缺失时的 fallback，
	 *                                    保证回落也带台账证据（不会把已执行卡回落成 APPROVED）；
	 *                                    传 {@code null} 只保留旧 5 参调用的行为，不扩大
	 */
	public static List<TranscriptEntry> fromRunTerminal(List<Message> messages, Map<String, PendingToolCall> pendings,
			String runId, long turnStartedAtMs, boolean includeLastAssistant,
			Map<String, String> resolvedStatusByToolCallId, Predicate<String> executedInLedger) {
		if (messages == null || messages.isEmpty() || runId == null || runId.isBlank()) {
			return List.of();
		}
		Map<String, String> statuses = resolvedStatusByToolCallId == null ? Map.of() : resolvedStatusByToolCallId;
		int lastAssistant = -1;
		for (int i = 0; i < messages.size(); i++) {
			if (messages.get(i) instanceof AssistantMessage) {
				lastAssistant = i;
			}
		}
		List<TranscriptEntry> out = new ArrayList<>();
		long atMs = System.currentTimeMillis();
		for (int i = 0; i < messages.size(); i++) {
			Message message = messages.get(i);
			if (message == null || SyntheticMessages.isSynthetic(message)) {
				continue;
			}
			if (message instanceof AssistantMessage assistant) {
				boolean hasOwnToolCall = assistant.hasToolCalls() && assistant.getToolCalls().stream()
					.anyMatch(call -> call.id() != null && pendings.containsKey(call.id()));
				if (!hasOwnToolCall && !(includeLastAssistant && i == lastAssistant)) {
					continue;
				}
				List<TranscriptEntry.ToolCallView> calls = toolCalls(assistant);
				Map<String, Object> metadata = message.getMetadata();
				Map<String, Object> states = mapOf(metadata, TOOL_CALL_STATES_KEY);
				out.add(new TranscriptEntry(assistantEntryId(runId, calls), "assistant", null, runId,
						turnStartedAtMs, atMs, assistant.getText(), stringOf(metadata, REASONING_KEY),
						metadata != null && Boolean.TRUE.equals(metadata.get(STOPPED_KEY)) ? Boolean.TRUE : null,
						metadata != null && Boolean.TRUE.equals(metadata.get(TRUNCATED_KEY)) ? Boolean.TRUE : null,
						calls.isEmpty() ? null : calls, states, null, null, null, null, null, false, null, null, null,
						null, null));
				continue;
			}
			if (message instanceof ToolResponseMessage toolResponse) {
				List<ToolResponseMessage.ToolResponse> responses = toolResponse.getResponses();
				for (int j = 0; j < responses.size(); j++) {
					ToolResponseMessage.ToolResponse response = responses.get(j);
					PendingToolCall pending = response.id() == null ? null : pendings.get(response.id());
					if (pending == null) {
						// 不属于本 run 的旧工具结果：保留在 memory/既有 transcript 里，不重复写
						continue;
					}
					String result = resultOf(pending, response.responseData());
					if (result == null) {
						continue;
					}
					EnvelopeFacts facts = factsOf(pending, response.responseData());
					String status = statuses.get(response.id());
					if (status == null) {
						// 缺失 status 的回落也必须带同一份台账证据（否则可能 assistant=EXECUTED、tool=APPROVED）
						status = settledStatus(pending, executedInLedger);
					}
					out.add(new TranscriptEntry(toolEntryId(runId, response.id()), "tool", null, runId,
							turnStartedAtMs, atMs, null, null, null, null, null, null, response.id(),
							hasText(pending.getName()) ? pending.getName() : response.name(), pending.getKind(),
							status, result, false, facts.code(), facts.truncated(), facts.strategy(),
							facts.originalChars(), facts.returnedChars()));
				}
			}
		}
		return out;
	}

	/** 既有 {@code chat:mem} → baseline 条目（一次性；之后不再从 memory 复制）。 */
	public static List<TranscriptEntry> baseline(List<Message> messages) {
		return fromMessages(messages, Map.of(), true, 0L);
	}

	/**
	 * 每张卡<b>只解析一次</b>状态：{@code toolCallId → 已解析 status}（未决且 {@code executed} 或台账命中
	 * ⇒ {@code EXECUTED}）。{@code executedInLedger} 只在未决状态下被问一次，同一张卡不会重复全表读。
	 *
	 * <p>批次结算写点用它：assistant 的 {@code toolCallStates} 与 tool 条目的 status 必须同源，
	 * 否则会出现 assistant=EXECUTED 而 tool=APPROVED 的分叉。
	 */
	public static Map<String, String> settledStatuses(List<PendingToolCall> pendings,
			Predicate<String> executedInLedger) {
		Map<String, String> statuses = new LinkedHashMap<>();
		if (pendings == null) {
			return statuses;
		}
		for (PendingToolCall pending : pendings) {
			if (pending == null || pending.getToolCallId() == null) {
				continue;
			}
			statuses.put(pending.getToolCallId(), settledStatus(pending, executedInLedger));
		}
		return statuses;
	}

	/** 用已解析 status 生成段级状态映射（{@link #settledStatuses} 的产物；缺失 id 不写）。 */
	public static Map<String, Object> statesFrom(List<PendingToolCall> pendings, Map<String, String> statuses) {
		Map<String, Object> states = new LinkedHashMap<>();
		if (pendings == null) {
			return states;
		}
		Map<String, String> resolved = statuses == null ? Map.of() : statuses;
		for (PendingToolCall pending : pendings) {
			if (pending == null || pending.getToolCallId() == null) {
				continue;
			}
			String status = resolved.get(pending.getToolCallId());
			if (status == null) {
				continue;
			}
			states.put(pending.getToolCallId(), toolCallState(pending, status));
		}
		return states;
	}

	/**
	 * 单条待决条目 → 历史段状态（{@code {status, outcome?, code?, kind?, decision?, decidedAt?,
	 * submittedValues?}}）。
	 *
	 * <p><b>唯一投影</b>：{@code /history}（transcript）与记忆窗口的 {@code toolCallStates}
	 * 都从这里派生，避免"刷新看到的卡"与"模型回放看到的卡"各说一套。
	 * {@code status} 由调用方给出（可能是升格后的 {@link PendingToolCall#EXECUTED}）：
	 * 未决状态不写 {@code outcome}（写 unknown 会让前端把"等待中"兜底成失败）。
	 */
	public static Map<String, Object> toolCallState(PendingToolCall pending, String status) {
		Map<String, Object> state = new LinkedHashMap<>();
		state.put("status", status);
		if (!PendingToolCall.PENDING.equals(status) && !PendingToolCall.APPROVED.equals(status)) {
			ToolOutcome persisted = ToolOutcome.fromWire(pending.getOutcome());
			state.put("outcome", (persisted == null ? ToolOutcome.fromStatus(status) : persisted).wire());
		}
		String code = pending.effectiveCode();
		if (code != null) {
			state.put("code", code);
		}
		String kind = pending.getKind();
		if (kind != null && !kind.isBlank()) {
			state.put("kind", kind);
		}
		String decision = decisionOf(pending.getStatus());
		if (decision != null) {
			state.put("decision", decision);
		}
		if (pending.getResolvedAtMs() > 0) {
			state.put("decidedAt", pending.getResolvedAtMs());
		}
		if (PendingToolCall.KIND_FRONTEND.equals(kind) && pending.getResultText() != null
				&& !pending.getResultText().isBlank()) {
			state.put("submittedValues", pending.getResultText());
		}
		return state;
	}

	/**
	 * 条目实际状态：<b>已执行但条目态仍停在 PENDING/APPROVED</b> 时按 {@link PendingToolCall#EXECUTED}
	 * 投影（确认门工具执行后条目态永久停留 APPROVED，只认 status 会把已执行卡刷成"待确认"）。
	 *
	 * <p>证据只有两个：条目自身的 {@code executed}（执行完成时写入）与台账命中（{@code executedInLedger}）。
	 * 两者都没有时原样返回条目态 —— 未执行不能靠猜测升格。
	 *
	 * <p><b>例外：{@code outcome=blocked} 不升格</b>。认领失败（他实例已认领/已在执行）会把条目写成
	 * {@code outcome=blocked} + {@code executed=true}，但本次<b>没有执行</b>；按 executed 升格成
	 * {@code EXECUTED} 会让"未执行"显示成已执行。这里投影成 {@link PendingToolCall#BLOCKED}
	 * （既有终态，前端映射 blocked）；其余 outcome 不参与本判据（避免扩散）。
	 */
	public static String settledStatus(PendingToolCall pending, Predicate<String> executedInLedger) {
		String status = pending.getStatus();
		if (!PendingToolCall.PENDING.equals(status) && !PendingToolCall.APPROVED.equals(status)) {
			return status;
		}
		if (ToolOutcome.fromWire(pending.getOutcome()) == ToolOutcome.BLOCKED) {
			return PendingToolCall.BLOCKED;
		}
		if (pending.isExecuted()) {
			return PendingToolCall.EXECUTED;
		}
		String toolCallId = pending.getToolCallId();
		boolean ledgerHit = executedInLedger != null && toolCallId != null
				&& Boolean.TRUE.equals(executedInLedger.test(toolCallId));
		return ledgerHit ? PendingToolCall.EXECUTED : status;
	}

	/**
	 * 条目级终态（非 PENDING / APPROVED 的全部状态码）判定：<b>本方法即单一事实源</b>
	 * （记忆收敛侧与 transcript 都委托到这里；不写数量，增删状态时不必同步改注释）。
	 */
	public static boolean isSettledStatus(String status) {
		return PendingToolCall.EXECUTED.equals(status)
			|| PendingToolCall.REJECTED.equals(status)
			|| PendingToolCall.REJECTED_ARGUMENTS.equals(status)
			|| PendingToolCall.TIMEOUT.equals(status)
			|| PendingToolCall.FRONTEND_RESULT.equals(status)
			|| PendingToolCall.FRONTEND_CANCELLED.equals(status)
			|| PendingToolCall.CANCELLED.equals(status)
			|| PendingToolCall.BLOCKED.equals(status)
			|| PendingToolCall.SKIPPED.equals(status)
			|| PendingToolCall.PRECHECK_FAILED.equals(status)
			|| PendingToolCall.SHEET_DISABLED.equals(status);
	}

	/** 条目态 → 决策口径（APPROVED → approved / 拒绝 → rejected / 取消 → cancel；其它 null）。 */
	private static String decisionOf(String status) {
		if (PendingToolCall.APPROVED.equals(status)) {
			return "approved";
		}
		if (PendingToolCall.REJECTED.equals(status) || PendingToolCall.REJECTED_ARGUMENTS.equals(status)) {
			return "rejected";
		}
		if (PendingToolCall.CANCELLED.equals(status)) {
			return "cancel";
		}
		return null;
	}

	/** assistant 条目的稳定 id：有工具调用按首个 toolCallId，无调用按轮。 */
	public static String assistantEntryId(String runId, List<TranscriptEntry.ToolCallView> calls) {
		if (calls == null || calls.isEmpty()) {
			return entryId(runId, "assistant", null);
		}
		String first = calls.get(0).id();
		return entryId(runId, "assistant", first == null ? "none" : first);
	}

	public static String userEntryId(String runId) {
		return entryId(runId, "user", null);
	}

	public static String toolEntryId(String runId, String toolCallId) {
		return entryId(runId, "tool", toolCallId == null ? "none" : toolCallId);
	}

	private static String entryId(String runId, String kind, String suffix) {
		String base = runId == null || runId.isBlank() ? "baseline" : runId;
		return suffix == null ? base + ":" + kind : base + ":" + kind + ":" + suffix;
	}

	private static List<TranscriptEntry.ToolCallView> toolCalls(AssistantMessage assistant) {
		if (!assistant.hasToolCalls()) {
			return List.of();
		}
		List<TranscriptEntry.ToolCallView> calls = new ArrayList<>(assistant.getToolCalls().size());
		for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
			calls.add(new TranscriptEntry.ToolCallView(call.id(), call.type(), call.name(), call.arguments()));
		}
		return calls;
	}

	private static String resultOf(PendingToolCall pending, String fallback) {
		if (pending != null && hasText(pending.getResultText())
				&& !ToolResultClearing.isAlreadyCleared(pending.getResultText())) {
			return pending.getResultText();
		}
		if (fallback == null || ToolResultClearing.isAlreadyCleared(fallback)) {
			// 技术占位符不得进入用户展示历史；没有原文就宁可没有这一条（B3 会补节点，不补假原文）
			return null;
		}
		return fallback;
	}

	/**
	 * 工具条目的用户面元数据（code / 模型侧截断事实）。
	 *
	 * <p>终态历史里的 {@code responseData} 是模型侧信封（含 {@code code} 与截断五字段）；
	 * transcript 只存用户面原文，因此必须在这里把这两类事实一并落盘 —— 否则刷新后
	 * {@code /history} 读 transcript 时信封已不在，截断角标与 {@code userTextCode} 会丢。
	 * 旧数据/非信封结果解析失败一律按"无元数据"处理，不影响原文。
	 */
	private static EnvelopeFacts factsOf(PendingToolCall pending, String responseData) {
		String code = pending != null && hasText(pending.getCode()) ? pending.getCode() : null;
		if (responseData == null || responseData.isBlank()) {
			return new EnvelopeFacts(code, null, null, null, null);
		}
		try {
			ToolResultEnvelope envelope = ToolResultEnvelope.fromJson(responseData);
			if (envelope == null) {
				return new EnvelopeFacts(code, null, null, null, null);
			}
			String envelopeCode = hasText(envelope.code()) ? envelope.code() : code;
			if (!envelope.truncated()) {
				return new EnvelopeFacts(envelopeCode, null, null, null, null);
			}
			return new EnvelopeFacts(envelopeCode, Boolean.TRUE, envelope.strategy(), envelope.originalChars(),
					envelope.returnedChars());
		}
		catch (RuntimeException ex) {
			return new EnvelopeFacts(code, null, null, null, null);
		}
	}

	private record EnvelopeFacts(String code, Boolean truncated, String strategy, Integer originalChars,
			Integer returnedChars) {
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private static String stringOf(Map<String, Object> metadata, String key) {
		if (metadata == null) {
			return null;
		}
		Object value = metadata.get(key);
		return value == null ? null : String.valueOf(value);
	}

	private static long longOf(Map<String, Object> metadata, String key, long fallback) {
		if (metadata == null) {
			return fallback;
		}
		Object value = metadata.get(key);
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (value instanceof String text) {
			try {
				return Long.parseLong(text.trim());
			}
			catch (NumberFormatException ex) {
				return fallback;
			}
		}
		return fallback;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> mapOf(Map<String, Object> metadata, String key) {
		if (metadata == null) {
			return null;
		}
		Object value = metadata.get(key);
		return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
	}

}
