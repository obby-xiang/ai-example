package com.example.configmgr.ai.context;

import org.springframework.ai.chat.messages.Message;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 端点载荷装配（上下文统计与压缩专项批①；设计稿 §3.4(b)）。
 *
 * <p>
 * 三个用途：<b>刷新恢复</b>（本地状态没了，pill 仍要如实）、<b>展开分项</b>（popover 的七项）、
 * <b>挂起态</b>（挂起期间没有新帧，占用量仍要可读）。
 *
 * <p>
 * <b>载荷有界（N-4）</b>：{@code breakdown} 恒 6 项（上限未知时）或 7 项；{@code ledger} 恒 ≤20 条
 * （{@code RunStore.CONTEXT_LEDGER_MAX}，LPUSH + LTRIM 维护）；分项估算只遍历<b>记忆窗口</b>
 * （{@code app.ai.memory.max-messages} = 40 条），不扫历史全量 ⇒ 响应体大小与轮数无关，
 * 不会随会话增长而膨胀。
 *
 * <p>
 * 分项来源刻意分两半：
 * <ul>
 * <li>{@code system}/{@code tools} 来自 {@code ai:ctx:} 里轮初写下的锚点值 —— 它们由装配点产生
 * （人设 + 工作区快照、披露的 schema），<b>不在历史里</b>，端点无从重建；</li>
 * <li>{@code history}/{@code toolResults}/{@code summary}/{@code input} 由记忆窗口现算
 * （记忆被压缩过的话，这里读到的就是压缩后的形态 —— 这正是"压缩可见"的另一半）。</li>
 * </ul>
 */
public final class ContextBreakdownService {

	private final ContextCompactor compactor;

	private final ContextStateStore store;

	public ContextBreakdownService(ContextCompactor compactor, ContextStateStore store) {
		this.compactor = compactor;
		this.store = store;
	}

	/**
	 * 会话上下文快照（端点响应体的 {@code data}）。
	 *
	 * @param sessionId 会话 id
	 * @param memory    记忆窗口（调用方给，避免本类反向依赖 {@code ChatMemory}）
	 * @param ledger    是否附台账（{@code ?ledger=1}；默认不带，保持载荷轻）
	 */
	public Map<String, Object> snapshot(String sessionId, List<Message> memory, boolean ledger) {
		ContextState state = this.compactor.readState(sessionId);
		int limit = this.compactor.limitTokens(null, state.model());
		int used = state.usedTokens();
		Map<String, Integer> categories = ContextBreakdown.estimateCategories(memory, state.systemTokens(),
				state.toolsTokens());
		// 记忆被清空/尚未回填时，本轮输入仍有真实体量 —— 用轮初锚点补齐，不让 input 恒为 0
		if (categories.get(ContextBreakdown.INPUT) == 0 && state.inputTokens() > 0) {
			categories.put(ContextBreakdown.INPUT, state.inputTokens());
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sessionId", sessionId);
		body.put("model", state.model());
		body.put("turnNo", state.turnNo());
		body.put("measuredAtMs", state.atMs() > 0L ? state.atMs() : null);
		body.put("limitTokens", limit > 0 ? limit : null);
		body.put("usedTokens", used);
		// 未测过（会话刚开始/记账失败）时不发百分比：0% 会被读成"很空"，而事实是"不知道"
		body.put("percent", state.measured() && limit > 0 ? percent(used, limit) : null);
		body.put("source", !state.measured() ? "none"
				: (state.est() ? ContextMeter.SOURCE_ESTIMATE : ContextMeter.SOURCE_STREAM));
		body.put("estimated", state.est());
		body.put("breakdown", ContextBreakdown.parts(categories, used, limit));
		body.put("compaction", compaction(state));
		if (ledger) {
			body.put("ledger", this.store.contextLedger(sessionId));
		}
		return body;
	}

	/** 百分比（一位小数；整数百分比不补 {@code .0} 的展示由前端决定）。 */
	public static double percent(int used, int limit) {
		if (limit <= 0) {
			return 0d;
		}
		return Math.round((double) Math.max(0, used) * 1000d / limit) / 10d;
	}

	private Map<String, Object> compaction(ContextState state) {
		Map<String, Object> compaction = new LinkedHashMap<>();
		compaction.put("level", state.level() == null ? "none" : state.level());
		compaction.put("compactedTurns", state.compactedTurns());
		compaction.put("lostTokens", state.lostTokens());
		compaction.put("lastAtMs", state.atMs() > 0L ? state.atMs() : null);
		compaction.put("reason", state.reason());
		// 熔断态由批③ 的 CompactionFuse 维护（closed/open/half_open）
		compaction.put("circuit", state.circuit() == null ? "closed" : state.circuit());
		compaction.put("fuseFails", state.fuseFails());
		return compaction;
	}

}
