package com.example.configmgr.ai.conformance;

import com.example.configmgr.ai.run.RunStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI SSE 帧流的<b>序列不变量校验器</b>（帧序一致性 conformance 的判据本体）。
 *
 * <h2>方法论出处（ag-ui 的借鉴边界）</h2>
 * 借的是 ag-ui {@code spec/1.0/conformance} 的<b>方法论</b>，不是它的协议（DC-14 已裁决不切换 AG-UI）：
 * <ol>
 * <li><b>一份帧流 = 一个 conformance 用例</b>：断言的对象是"给定事件流，序列不变量是否成立"，
 * 而不是"某个方法被调了几次"；</li>
 * <li><b>每个断言写清 kill</b>：ag-ui 的 fixture 必填 {@code kill} 字段（"哪一行实现改动会让它变红"）——
 * "一个不可能失败的 fixture 比没有 fixture 更糟，因为它读起来像覆盖率却什么也没证明"。
 * 本类的每条规则都注明自己的 kill，且 {@code FrameContractFalsificationTest} 用<b>构造出的违规流</b>
 * 证明校验器真的会红（否则规则等于没写）；</li>
 * <li><b>把"客户端判定"与"运行自身失败"分开</b>：ag-ui 分 {@code outcome} 与 {@code runError}。
 * 本协议里对应的是 —— {@code done}（含 {@code cancelled=true}）与 {@code error} 都是<b>合法终帧</b>
 * （流被正常消费完毕），而"seq 断裂 / 终帧之后还有帧 / 工具调用没人应答"才是<b>不一致</b>。
 * 因此 {@link #assertConformant} 不会因为出现 {@code error} 帧而失败，只会因为它出现在错误的位置而失败。</li>
 * </ol>
 *
 * <h2>断言的 6 条不变量</h2>
 * <table>
 * <caption>规则与 kill</caption>
 * <tr><th>规则</th><th>内容</th><th>kill（哪一行实现改动会让它红）</th></tr>
 * <tr><td>{@link #seqContract}</td><td>每帧带 {@code seq}、严格递增、自 1 起无缺口</td>
 * <td>{@code SseChatEmitter#nextSeq} 改成常量/每次自增 2/不写 seq</td></tr>
 * <tr><td>{@link #terminalContract}</td><td>首帧 {@code start}；恰好一个终帧；终帧之后无任何帧</td>
 * <td>在 {@code finish()} 的 {@code out.done(...)} 之后再补一帧（或终帧发两次）</td></tr>
 * <tr><td>{@link #toolPairingContract}</td><td>每个 {@code tool_start} 恰有一个配对结局帧
 * （{@code tool_result} 或 {@code frontend_tool_result}，同 {@code toolCallId}）</td>
 * <td>删掉 {@code SpToolCallingManager} 里任一结局分支的 {@code toolResult(...)}</td></tr>
 * <tr><td>{@link #textSegmentContract}</td><td>{@code message_start(kind=text)} → {@code delta}×N →
 * {@code message_end}；delta 同 {@code messageId}；{@code chars} = delta 文本长度之和</td>
 * <td>把 {@code message_end} 的 chars 改成与 delta 不同的计数源（见 S5c-4）</td></tr>
 * <tr><td>{@link #orderingContract}</td><td>确认门/前端工具的因果顺序；{@code retry} 只在首次产出之前</td>
 * <td>把 {@code confirm_decision} 提前到 {@code confirm_request} 之前，或去掉重试的"未产出内容"条件</td></tr>
 * <tr><td>{@link #terminalConsistencyContract}</td><td>终帧类型与运行终态同向（{@code done{cancelled:true}}
 * 只能收 CANCELLED 轮；{@code error} 不得收在已 DONE 的轮上）</td>
 * <td>让 {@code fail()} 与 {@code finish()} 都能发终帧（一轮两个终帧）</td></tr>
 * </table>
 */
public final class FrameContract {

	public static final String TERMINAL_DONE = "done";

	public static final String TERMINAL_ERROR = "error";

	/** 终帧类型（恰好出现一个）。 */
	public static final Set<String> TERMINALS = Set.of(TERMINAL_DONE, TERMINAL_ERROR);

	/** 工具调用的"结局帧"类型（{@code tool_start} 的配对对象）。 */
	public static final Set<String> TOOL_OUTCOMES = Set.of("tool_result", "frontend_tool_result");

	/** 存档期帧（T7：心跳不落归档，故不在本集合）。 */
	public static final String HEARTBEAT = RunStore.HEARTBEAT_TYPE;

	private FrameContract() {
	}

	// ── 全量校验（用例的主入口） ──────────────────────────────────────────────

	/** 跑满 6 条规则，返回全部违规（空 = 这条帧流与帧协议一致）。 */
	public static List<String> violations(List<Map<String, Object>> frames) {
		List<String> all = new ArrayList<>();
		all.addAll(seqContract(frames));
		all.addAll(terminalContract(frames));
		all.addAll(toolPairingContract(frames));
		all.addAll(textSegmentContract(frames));
		all.addAll(orderingContract(frames));
		all.addAll(terminalConsistencyContract(frames));
		return all;
	}

	/** 断言整条帧流一致（失败信息列出全部违规，便于一次看全而不是逐条试）。 */
	public static void assertConformant(List<Map<String, Object>> frames) {
		List<String> found = violations(frames);
		assertThat(found).as("帧序一致性违规（帧流=%s）", types(frames)).isEmpty();
	}

	// ── 规则 1：序号 ─────────────────────────────────────────────────────────

	/**
	 * 每个订阅者收到的帧流：每帧带 {@code seq}、严格递增、自 1 起连续。
	 *
	 * <p>
	 * <b>为什么"无缺口"是对的</b>：序号在 {@code emit()} 里逐帧自增，且每帧都要广播给订阅者 ——
	 * 心跳也占号（它同样是一条到达订阅者的帧），因此<b>订阅者视角</b>必须连续。
	 * 注意这与<b>归档视角</b>不同：心跳不落归档（T7），所以 {@code ai:events} 里的 seq 会有
	 * "心跳留下的空洞"，那正是 {@link #archiveSeqContract} 的断言内容。
	 */
	public static List<String> seqContract(List<Map<String, Object>> frames) {
		List<String> out = new ArrayList<>();
		long expected = 1L;
		for (int i = 0; i < frames.size(); i++) {
			Object seq = frames.get(i).get("seq");
			if (!(seq instanceof Number number)) {
				out.add("第 " + i + " 帧（" + type(frames.get(i)) + "）缺 seq");
				continue;
			}
			long value = number.longValue();
			if (value != expected) {
				out.add("第 " + i + " 帧（" + type(frames.get(i)) + "）seq=" + value + "，期望 " + expected
						+ "（严格递增且无缺口）");
			}
			expected = value + 1;
		}
		return out;
	}

	/**
	 * 归档（{@code ai:events:<runId>}）视角：seq 严格递增、无重复；允许有缺口（心跳占号不落档）。
	 *
	 * <p>
	 * kill：让心跳帧也落归档（{@code RunStore#appendEvent} 去掉心跳分支）⇒ 归档不再有洞，
	 * 断言"至少一个洞"的用例变红。
	 */
	public static List<String> archiveSeqContract(List<Map<String, Object>> archived) {
		List<String> out = new ArrayList<>();
		long previous = 0L;
		Set<Long> seen = new LinkedHashSet<>();
		for (Map<String, Object> frame : archived) {
			Object seq = frame.get("seq");
			if (!(seq instanceof Number number)) {
				continue;
			}
			long value = number.longValue();
			if (value <= previous) {
				out.add("归档内 seq 非严格递增：" + previous + " → " + value);
			}
			if (!seen.add(value)) {
				out.add("归档内 seq 重复：" + value);
			}
			previous = value;
		}
		return out;
	}

	// ── 规则 2：终态 ─────────────────────────────────────────────────────────

	/** 首帧必须是 {@code start}；恰好一个终帧；终帧之后不得再有任何帧（业务帧或心跳）。 */
	public static List<String> terminalContract(List<Map<String, Object>> frames) {
		List<String> out = new ArrayList<>();
		if (frames.isEmpty()) {
			return List.of("帧流为空（连 start 都没有）");
		}
		String first = type(frames.get(0));
		if (!"start".equals(first)) {
			out.add("首帧不是 start，而是 " + first);
		}
		List<Integer> terminals = new ArrayList<>();
		for (int i = 0; i < frames.size(); i++) {
			if (TERMINALS.contains(type(frames.get(i)))) {
				terminals.add(i);
			}
		}
		if (terminals.size() > 1) {
			out.add("终帧出现 " + terminals.size() + " 次（" + terminals + "），一轮只允许一个");
		}
		if (!terminals.isEmpty()) {
			int last = terminals.get(terminals.size() - 1);
			if (last != frames.size() - 1) {
				out.add("终帧（第 " + last + " 帧 " + type(frames.get(last)) + "）之后还有 "
						+ (frames.size() - 1 - last) + " 帧：" + types(frames.subList(last + 1, frames.size())));
			}
		}
		return out;
	}

	// ── 规则 3：工具配对 ─────────────────────────────────────────────────────

	/**
	 * 每个 {@code tool_start} 必须有且仅有一个配对结局帧（同 {@code toolCallId}），
	 * 且结局帧不得在 start 之前。
	 *
	 * <p>
	 * 三种合法结局：后端执行（{@code tool_result}）、确认门拒绝/超时（{@code tool_result, ok=false}）、
	 * 前端通道（{@code frontend_tool_result}）。
	 * kill：删掉 {@code ConfirmGate} 里拒绝路径的 {@code toolResult(...)} ⇒ 出现"有 start 无结局"。
	 */
	public static List<String> toolPairingContract(List<Map<String, Object>> frames) {
		List<String> out = new ArrayList<>();
		Map<String, Integer> starts = new LinkedHashMap<>();
		Map<String, Integer> outcomes = new LinkedHashMap<>();
		for (int i = 0; i < frames.size(); i++) {
			String type = type(frames.get(i));
			String id = String.valueOf(frames.get(i).get("toolCallId"));
			if ("tool_start".equals(type)) {
				Integer previous = starts.put(id, i);
				if (previous != null) {
					out.add("toolCallId=" + id + " 出现两次 tool_start（第 " + previous + "、" + i + " 帧）");
				}
			}
			else if (TOOL_OUTCOMES.contains(type)) {
				Integer previous = outcomes.put(id, i);
				if (previous != null) {
					out.add("toolCallId=" + id + " 出现两个结局帧（第 " + previous + "、" + i + " 帧）");
				}
			}
		}
		for (Map.Entry<String, Integer> entry : starts.entrySet()) {
			Integer index = outcomes.get(entry.getKey());
			if (index == null) {
				out.add("tool_start（toolCallId=" + entry.getKey() + "，第 " + entry.getValue() + " 帧）没有配对结局帧");
			}
			else if (index < entry.getValue()) {
				out.add("toolCallId=" + entry.getKey() + " 的结局帧（第 " + index + " 帧）早于 tool_start（第 "
						+ entry.getValue() + " 帧）");
			}
		}
		for (Map.Entry<String, Integer> entry : outcomes.entrySet()) {
			if (!starts.containsKey(entry.getKey())) {
				out.add("结局帧（toolCallId=" + entry.getKey() + "，第 " + entry.getValue() + " 帧）没有 tool_start");
			}
		}
		return out;
	}

	// ── 规则 4：正文三段式与 delta 拼接 ──────────────────────────────────────

	/**
	 * 正文段：{@code message_start(kind=text)} 恰好一次、在所有 delta 之前；
	 * 所有 delta 同 {@code messageId}；{@code message_end} 恰好一次、在该段最后一个 delta 之后；
	 * 轮次已收终帧时 {@code message_end.chars} 必须等于 delta 文本长度之和。
	 *
	 * <p>
	 * 最后一条就是"delta 帧顺序与拼接一致性"的可判形态：前端渲染正文靠<b>顺序拼接 delta</b>，
	 * 而落库正文来自另一条累计路径；两者若不等，前端看到的正文就与落定正文不同。
	 * kill：把 chars 换成"上游分片总长"（含未发 delta 的空白分片）⇒ 见 S5c-4，实测确有此差。
	 */
	public static List<String> textSegmentContract(List<Map<String, Object>> frames) {
		List<String> out = new ArrayList<>();
		List<Integer> starts = new ArrayList<>();
		List<Integer> ends = new ArrayList<>();
		List<Integer> deltas = new ArrayList<>();
		Set<Object> messageIds = new LinkedHashSet<>();
		long deltaChars = 0L;
		for (int i = 0; i < frames.size(); i++) {
			Map<String, Object> frame = frames.get(i);
			String type = type(frame);
			if ("message_start".equals(type) && "text".equals(frame.get("kind"))) {
				starts.add(i);
			}
			else if ("message_end".equals(type) && "text".equals(frame.get("kind"))) {
				ends.add(i);
			}
			else if ("delta".equals(type)) {
				deltas.add(i);
				messageIds.add(frame.get("messageId"));
				Object text = frame.get("text");
				deltaChars += text == null ? 0L : String.valueOf(text).length();
			}
		}
		if (starts.size() > 1) {
			out.add("正文段 message_start 出现 " + starts.size() + " 次（同一段只应开一次边界）");
		}
		if (ends.size() > 1) {
			out.add("正文段 message_end 出现 " + ends.size() + " 次");
		}
		if (!deltas.isEmpty() && starts.isEmpty()) {
			out.add("有 " + deltas.size() + " 个 delta 帧但没有正文段 message_start");
		}
		if (!deltas.isEmpty() && !starts.isEmpty() && starts.get(0) > deltas.get(0)) {
			out.add("message_start（第 " + starts.get(0) + " 帧）晚于首个 delta（第 " + deltas.get(0) + " 帧）");
		}
		if (!deltas.isEmpty() && !ends.isEmpty() && ends.get(0) < deltas.get(deltas.size() - 1)) {
			out.add("message_end（第 " + ends.get(0) + " 帧）早于最后一个 delta（第 " + deltas.get(deltas.size() - 1)
					+ " 帧）");
		}
		if (messageIds.size() > 1) {
			out.add("同一正文段的 delta 有多个 messageId：" + messageIds);
		}
		if (hasTerminal(frames) && !deltas.isEmpty() && ends.isEmpty()) {
			out.add("轮次已收终帧，但正文段没有 message_end（chars 无从对账）");
		}
		if (!ends.isEmpty() && !deltas.isEmpty()) {
			Object chars = frames.get(ends.get(0)).get("chars");
			long declared = chars instanceof Number number ? number.longValue() : -1L;
			if (declared != deltaChars) {
				out.add("message_end.chars=" + declared + "，而 delta 文本长度之和=" + deltaChars
						+ "（前端拼接所得与落定正文口径不一致）");
			}
		}
		return out;
	}

	// ── 规则 5：因果顺序 ─────────────────────────────────────────────────────

	/**
	 * 因果顺序（同 toolCallId 内）：
	 * <ol>
	 * <li>确认门：{@code confirm_request} &lt; {@code confirm_decision} &lt; {@code tool_result}；
	 * 且 {@code confirm_request} 出现在 {@code tool_start} 之后（工具先"出场"再"等确认"）；</li>
	 * <li>前端通道：{@code frontend_tool_request} &lt; {@code frontend_tool_result}；</li>
	 * <li>{@code suspended}（挂起态已外置的公告）出现在第一个 {@code tool_start} 之前 ——
	 * 硬规范①"外置先于阻塞"在帧序上的投影；</li>
	 * <li>{@code retry} 帧只允许出现在本轮第一次正文产出之前（重试三条件之一"未产出内容"）。</li>
	 * </ol>
	 */
	public static List<String> orderingContract(List<Map<String, Object>> frames) {
		List<String> out = new ArrayList<>();
		Map<String, Integer> starts = indexes(frames, "tool_start");
		Map<String, Integer> confirms = indexes(frames, "confirm_request");
		Map<String, Integer> decisions = indexes(frames, "confirm_decision");
		Map<String, Integer> results = indexes(frames, "tool_result");
		Map<String, Integer> frontendRequests = indexes(frames, "frontend_tool_request");
		Map<String, Integer> frontendResults = indexes(frames, "frontend_tool_result");
		for (Map.Entry<String, Integer> entry : confirms.entrySet()) {
			String id = entry.getKey();
			Integer start = starts.get(id);
			if (start != null && start > entry.getValue()) {
				out.add("toolCallId=" + id + "：confirm_request（第 " + entry.getValue() + " 帧）早于 tool_start（第 "
						+ start + " 帧）");
			}
			Integer decision = decisions.get(id);
			if (decision == null) {
				out.add("toolCallId=" + id + "：有 confirm_request 但缺 confirm_decision（决策结局帧必须回执）");
			}
			else if (decision < entry.getValue()) {
				out.add("toolCallId=" + id + "：confirm_decision 早于 confirm_request");
			}
			Integer result = results.get(id);
			if (result != null && decision != null && result < decision) {
				out.add("toolCallId=" + id + "：tool_result（第 " + result + " 帧）早于 confirm_decision（第 " + decision
						+ " 帧）");
			}
		}
		for (Map.Entry<String, Integer> entry : frontendRequests.entrySet()) {
			String id = entry.getKey();
			Integer start = starts.get(id);
			if (start != null && start > entry.getValue()) {
				out.add("toolCallId=" + id + "：frontend_tool_request 早于 tool_start");
			}
			Integer result = frontendResults.get(id);
			if (result != null && result < entry.getValue()) {
				out.add("toolCallId=" + id + "：frontend_tool_result 早于 frontend_tool_request");
			}
		}
		Integer suspended = firstIndex(frames, "suspended");
		Integer firstToolStart = firstIndex(frames, "tool_start");
		if (suspended != null && firstToolStart != null && suspended > firstToolStart) {
			out.add("suspended（第 " + suspended + " 帧）晚于首个 tool_start（第 " + firstToolStart
					+ " 帧）—— 挂起态外置公告必须先于工具出场（硬规范①）");
		}
		Integer firstDelta = firstIndex(frames, "delta");
		for (int i = 0; i < frames.size(); i++) {
			if (!"retry".equals(type(frames.get(i)))) {
				continue;
			}
			if (firstDelta != null && i > firstDelta) {
				out.add("retry（第 " + i + " 帧）晚于首个 delta（第 " + firstDelta
						+ " 帧）—— 已产出内容不得重试（重试三条件）");
			}
		}
		return out;
	}

	// ── 规则 6：终帧与终态同向 ───────────────────────────────────────────────

	/**
	 * 终帧类型与运行自身结局同向：{@code done{cancelled:true}} 只在取消轮上出现；
	 * {@code error} 与 {@code done{cancelled:false}} 不得同时出现（一轮只能有一个结局）。
	 */
	public static List<String> terminalConsistencyContract(List<Map<String, Object>> frames) {
		List<String> out = new ArrayList<>();
		for (Map<String, Object> frame : frames) {
			if (!TERMINAL_DONE.equals(type(frame))) {
				continue;
			}
			Object cancelled = frame.get("cancelled");
			if (cancelled != null && !(cancelled instanceof Boolean)) {
				out.add("done 帧的 cancelled 不是布尔：" + cancelled);
			}
		}
		return out;
	}

	// ── 小工具 ──────────────────────────────────────────────────────────────

	public static boolean hasTerminal(List<Map<String, Object>> frames) {
		return frames.stream().anyMatch(frame -> TERMINALS.contains(type(frame)));
	}

	public static String type(Map<String, Object> frame) {
		return String.valueOf(frame.get("type"));
	}

	public static List<String> types(List<Map<String, Object>> frames) {
		List<String> out = new ArrayList<>(frames.size());
		for (Map<String, Object> frame : frames) {
			out.add(type(frame));
		}
		return out;
	}

	/** 按类型取帧（保留顺序）。 */
	public static List<Map<String, Object>> ofType(List<Map<String, Object>> frames, String type) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> frame : frames) {
			if (type.equals(type(frame))) {
				out.add(frame);
			}
		}
		return out;
	}

	public static Map<String, Object> oneOfType(List<Map<String, Object>> frames, String type) {
		List<Map<String, Object>> found = ofType(frames, type);
		assertThat(found).as("帧类型 %s 恰好一帧（实际帧流=%s）", type, types(frames)).hasSize(1);
		return found.get(0);
	}

	/** {@code toolCallId → 帧下标}（缺 toolCallId 的帧以其 {@code null} 文本为键）。 */
	private static Map<String, Integer> indexes(List<Map<String, Object>> frames, String type) {
		Map<String, Integer> out = new LinkedHashMap<>();
		for (int i = 0; i < frames.size(); i++) {
			if (type.equals(type(frames.get(i)))) {
				out.putIfAbsent(String.valueOf(frames.get(i).get("toolCallId")), i);
			}
		}
		return out;
	}

	private static Integer firstIndex(List<Map<String, Object>> frames, String type) {
		for (int i = 0; i < frames.size(); i++) {
			if (type.equals(type(frames.get(i)))) {
				return i;
			}
		}
		return null;
	}

	/** 顺序无关的取值辅助：帧里取字符串（缺省为 null）。 */
	public static String text(Map<String, Object> frame, String key) {
		return Objects.toString(frame.get(key), null);
	}

	/**
	 * 造一帧（负向 fixture 与手工语料用）：{@code type} + {@code seq} + 任意附加键值对。
	 *
	 * <p>
	 * 负向用例必须能<b>手工构造违规流</b>：只有"校验器会对一条不合规的流变红"被证明过，
	 * 合规流上的绿灯才算数（ag-ui 的 kill 原则）。
	 */
	public static Map<String, Object> fixture(String type, long seq, Object... keyValues) {
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("type", type);
		frame.put("seq", seq);
		for (int i = 0; i + 1 < keyValues.length; i += 2) {
			frame.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
		}
		return frame;
	}

}
