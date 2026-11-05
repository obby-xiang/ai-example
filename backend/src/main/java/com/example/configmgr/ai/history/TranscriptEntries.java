package com.example.configmgr.ai.history;

import com.example.configmgr.ai.context.ToolResultClearing;
import com.example.configmgr.ai.memory.RunMemoryMerge;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

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

	public static final String ERROR_CODE_KEY = "errorCode";

	public static final String ERROR_MESSAGE_KEY = "errorMessage";

	/**
	 * 预检报告里属于<b>展示面</b>的键：target（卡头业务标题）、impact（影响面）、
	 * conditionSummary（条件摘要）、publishMode（权威发布模式）。
	 *
	 * <p>{@code checks}（含文件指纹等内部结论）与 {@code reportHash} 是内部预检明细，
	 * <b>不落展示历史</b> —— 实时帧里的完整报告口径不变。
	 */
	private static final List<String> PREFLIGHT_DISPLAY_KEYS = List.of("target", "impact", "conditionSummary",
			"publishMode");

	private TranscriptEntries() {
	}

	public static TranscriptEntry user(String runId, long turnStartedAtMs, long atMs, String text) {
		return new TranscriptEntry(entryId(runId, "user", null), "user", runId, turnStartedAtMs, atMs, text,
				null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
				null, null, null, null, null, null);
	}

	/** 挂起批次的早期条目（status=pending）；终态写点会用同一 entryId 覆盖。 */
	public static TranscriptEntry assistant(String runId, long turnStartedAtMs, long atMs, String text,
			List<TranscriptEntry.ToolCallView> toolCalls, Map<String, Object> toolCallStates) {
		return new TranscriptEntry(assistantEntryId(runId, toolCalls), "assistant", runId, turnStartedAtMs,
				atMs, text, null, null, null, toolCalls, toolCallStates, null, null, null, null, null, null,
				null, null, null, null, null, null, null, null, null, null);
	}

	/** 单条工具结果（早期 pending / 终态同键覆盖）。 */
	public static TranscriptEntry tool(String runId, long turnStartedAtMs, long atMs, String toolCallId, String name,
			String kind2, String status, String result) {
		return new TranscriptEntry(toolEntryId(runId, toolCallId), "tool", runId, turnStartedAtMs, atMs, null,
				null, null, null, null, null, toolCallId, name, kind2, status, result, null, null, null, null,
				null, null, null, null, null, null, null);
	}

	/**
	 * 一轮终态历史 → 本轮条目（只投影<b>本 run</b>的 assistant/tool，避免把快照里携带的旧轮重复写入）。
	 *
	 * <p>判据：工具条目的 toolCallId 必须出现在本 run 的 pending 表；assistant 可以含本 run 的
	 * 工具调用、实际正文/思考，或它是历史里最后一条 assistant（成功/取消/失败写点追加的终稿/半截/错误事实）。
	 * 无工具调用的多段正文按历史下标各持独立 entryId，避免互相覆盖。用户条目由轮首或终态补写点负责。
	 *
	 * @param includeLastAssistant 是否允许追加最后一条 assistant；失败只有形成本轮失败消息时才传 true
	 */
	/**
	 * 同上，并让 tool 条目使用<b>与 assistant {@code toolCallStates} 同一份</b>已解析状态。
	 *
	 * <p>为什么必须传：tool 条目与 assistant 状态各算一遍（一个查台账、一个不查）就会分叉成
	 * {@code assistant=EXECUTED} 而 {@code tool=APPROVED}。调用方在同一次遍历里解析一次，两边共用。
	 *
	 * @param resolvedStatusByToolCallId toolCallId → 已解析 status（生产路径必填，与 assistant 状态同源）
	 * @param executedInLedger            同一会计口径的台账谓词：<b>仅</b>用于 statuses 缺失时的 fallback，
	 *                                    保证回落也带台账证据（不会把已执行卡回落成 APPROVED）
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
		int currentRunStart = -1;
		for (int i = 0; i < messages.size(); i++) {
			if (messages.get(i) instanceof UserMessage user && !SyntheticMessages.isSynthetic(user)) {
				currentRunStart = i;
			}
		}
		for (int i = 0; i < messages.size(); i++) {
			Message message = messages.get(i);
			if (message == null || SyntheticMessages.isSynthetic(message)) {
				continue;
			}
			if (message instanceof AssistantMessage assistant) {
				boolean hasOwnToolCall = assistant.hasToolCalls() && assistant.getToolCalls().stream()
					.anyMatch(call -> call.id() != null && pendings.containsKey(call.id()));
				if (i < currentRunStart || assistant.hasToolCalls() && !hasOwnToolCall) {
					continue;
				}
				Map<String, Object> metadata = message.getMetadata();
				boolean hasVisibleContent = hasText(assistant.getText()) || hasText(stringOf(metadata, REASONING_KEY));
				boolean hasFailureFacts = hasText(stringOf(metadata, ERROR_CODE_KEY))
						|| hasText(stringOf(metadata, ERROR_MESSAGE_KEY));
				if (!hasOwnToolCall && !hasVisibleContent
						&& !(includeLastAssistant && i == lastAssistant && hasFailureFacts)) {
					continue;
				}
				List<TranscriptEntry.ToolCallView> calls = toolCalls(assistant);
				Map<String, Object> states = mapOf(metadata, TOOL_CALL_STATES_KEY);
				String entryId = calls.isEmpty() ? entryId(runId, "assistant", "text-" + i) : assistantEntryId(runId, calls);
				out.add(new TranscriptEntry(entryId, "assistant", runId,
						turnStartedAtMs, atMs, assistant.getText(), stringOf(metadata, REASONING_KEY),
						metadata != null && Boolean.TRUE.equals(metadata.get(STOPPED_KEY)) ? Boolean.TRUE : null,
						metadata != null && Boolean.TRUE.equals(metadata.get(TRUNCATED_KEY)) ? Boolean.TRUE : null,
						calls.isEmpty() ? null : calls, states, null, null, null, null, null, null, null, null, null,
						null, stringOf(metadata, ERROR_CODE_KEY), stringOf(metadata, ERROR_MESSAGE_KEY), null, null,
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
					out.add(new TranscriptEntry(toolEntryId(runId, response.id()), "tool", runId,
							turnStartedAtMs, atMs, null, null, null, null, null, null, response.id(),
							hasText(pending.getName()) ? pending.getName() : response.name(), pending.getKind(),
							status, result, facts.code(), facts.truncated(), facts.strategy(),
							facts.originalChars(), facts.returnedChars(), null, null, null, null, null, null));
				}
			}
		}
		return out;
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
	 * submittedValues?, preflight?}}）。
	 *
	 * <p><b>唯一投影</b>：{@code /history}（transcript）与记忆窗口的 {@code toolCallStates}
	 * 都从这里派生，避免"刷新看到的卡"与"模型回放看到的卡"各说一套。
	 * {@code status} 由调用方给出（可能是升格后的 {@link PendingToolCall#EXECUTED}）：
	 * 未决状态不写 {@code outcome}（写 unknown 会让前端把"等待中"兜底成失败）。
	 *
	 * <p>{@code preflight} 只带展示摘要（{@link #displayPreflight}）：终态刷新后卡头仍能按业务
	 * 标题渲染，而不是退回工具名；内部明细不随历史新暴露。
	 */
	public static Map<String, Object> toolCallState(PendingToolCall pending, String status) {
		Map<String, Object> state = new LinkedHashMap<>();
		state.put("status", status);
		if (!PendingToolCall.PENDING.equals(status) && !PendingToolCall.APPROVED.equals(status)) {
			ToolOutcome persisted = ToolOutcome.fromWire(pending.getOutcome());
			state.put("outcome", (persisted == null ? ToolOutcome.UNKNOWN : persisted).wire());
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
		Map<String, Object> preflight = displayPreflight(pending.getPreflight());
		if (preflight != null) {
			state.put("preflight", preflight);
		}
		return state;
	}

	/**
	 * 预检报告 → 展示摘要（白名单 {@link #PREFLIGHT_DISPLAY_KEYS}）。
	 *
	 * <p>没有 target（渲染不出业务标题）或白名单全空 ⇒ {@code null}：不写空键，旧载荷语义不变
	 * （前端"没有就不显示预检区"，卡头回落工具名）。
	 *
	 * <p>同一份摘要有两个出口：展示历史（{@code toolCallStates.preflight}）与
	 * {@code tool_result} 帧（刷新恢复的全量帧重放会跳过已决工具的 {@code suspended}/
	 * {@code confirm_request} 帧，结局帧若不带摘要，重建出的卡就丢了业务标题/条件/影响面）。
	 * 两处必须同源，避免"刷新看到的卡"与"历史看到的卡"各说一套。
	 */
	public static Map<String, Object> displayPreflight(Map<String, Object> preflight) {
		if (preflight == null || preflight.isEmpty() || !hasDisplayValue(preflight.get("target"))) {
			return null;
		}
		Map<String, Object> out = new LinkedHashMap<>();
		for (String key : PREFLIGHT_DISPLAY_KEYS) {
			Object value = preflight.get(key);
			if (hasDisplayValue(value)) {
				out.put(key, value);
			}
		}
		return out.isEmpty() ? null : out;
	}

	/**
	 * 同 entryId 两版条目的展示事实合并：新条目为准，补回旧条目已有的 preflight 摘要；
	 * 新条目没有帧段序时也保留旧段序。规范存储在写时执行同一规则，不在分页读取时重放。
	 *
	 * <p>为什么必须合并：挂起写点（pending）与终态写点共用同一 entryId；
	 * 后写的一份若没带预检（条目读不全/旧条目），卡头会从「<类型> 任务 #N『标题』」退回工具名。
	 * 预检事实对同一 toolCallId 只增不减，不能被一次后写覆盖成空。
	 *
	 * <p>新条目整份状态缺失（条目读不全）⇒ 保持缺失：不插入只有 preflight、没有 status/kind
	 * 的半形态状态 —— 卡面无法据它判终态，宁可按旧载荷语义回落占位。
	 */
	public static TranscriptEntry carryPreflight(TranscriptEntry previous, TranscriptEntry newer) {
		if (previous == null || newer == null) {
			return newer;
		}
		TranscriptEntry current = newer.segments() == null ? newer.withSegments(previous.segments()) : newer;
		Map<String, Object> previousStates = previous.toolCallStates();
		Map<String, Object> newerStates = current.toolCallStates();
		if (previousStates == null || previousStates.isEmpty() || newerStates == null || newerStates.isEmpty()) {
			return current;
		}
		Map<String, Object> states = new LinkedHashMap<>(newerStates);
		boolean changed = false;
		for (Map.Entry<String, Object> previousState : previousStates.entrySet()) {
			Map<String, Object> older = asMap(previousState.getValue());
			if (older == null || asMap(older.get("preflight")) == null) {
				continue;
			}
			Map<String, Object> currentState = asMap(states.get(previousState.getKey()));
			if (currentState == null) {
				continue;
			}
			Map<String, Object> merged = new LinkedHashMap<>(currentState);
			carryPreflightState(merged, older);
			if (!merged.equals(currentState)) {
				states.put(previousState.getKey(), merged);
				changed = true;
			}
		}
		return changed ? current.withToolCallStates(states) : current;
	}

	/**
	 * 把旧状态里已有的 preflight 展示摘要补进新状态（同 toolCallId）：新状态缺该键、或只带了
	 * 部分展示键时沿用旧值。状态/结局/决策一律以新状态为准，本方法只补 {@code preflight}。
	 */
	static Map<String, Object> carryPreflightState(Map<String, Object> newerState, Object previousState) {
		Map<String, Object> previous = asMap(previousState);
		Map<String, Object> older = previous == null ? null : asMap(previous.get("preflight"));
		if (newerState == null || older == null || older.isEmpty()) {
			return newerState;
		}
		Map<String, Object> current = asMap(newerState.get("preflight"));
		if (current == null) {
			Map<String, Object> carried = displayPreflight(older);
			if (carried != null) {
				newerState.put("preflight", carried);
			}
			return newerState;
		}
		Map<String, Object> merged = new LinkedHashMap<>(current);
		boolean changed = false;
		for (String key : PREFLIGHT_DISPLAY_KEYS) {
			if (hasDisplayValue(merged.get(key)) || !hasDisplayValue(older.get(key))) {
				continue;
			}
			merged.put(key, older.get(key));
			changed = true;
		}
		if (changed) {
			newerState.put("preflight", merged);
		}
		return newerState;
	}

	private static boolean hasDisplayValue(Object value) {
		if (value == null) {
			return false;
		}
		if (value instanceof String text) {
			return !text.isBlank();
		}
		if (value instanceof Map<?, ?> map) {
			return !map.isEmpty();
		}
		return true;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object value) {
		return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
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
		if (runId == null || runId.isBlank()) {
			throw new IllegalArgumentException("transcript 条目必须带 runId");
		}
		return suffix == null ? runId + ":" + kind : runId + ":" + kind + ":" + suffix;
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
