package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.observe.AiTrace;
import com.example.configmgr.ai.observe.SettlementObservationStore;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.tool.SheetAudit;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code tool_call_end} 的<b>唯一载荷构造点</b>（字段集合、键序、取值口径）与<b>结算仲裁口</b>。
 *
 * <h2>为什么要抽出来</h2>
 * 工具的结算可能发生在两个执行体上，但"这一行长什么样"只能有一份：内联批循环（工具在本轮里跑完）
 * 与 park 结算（请求帧投递后线程交还，结算改由决策/认领的胜者在<b>另一个执行体</b>里完成）。
 * 两处各写一遍，{@link SheetAudit} 的 8 个 additive 字段迟早分叉 —— 而这两处服务的是
 * 同一批表格工具。
 *
 * <h2>exactly-once = 持久首写者，不是进程内 {@code woke}（独立复审 I-1 / I-2）</h2>
 * "本进程有没有等待方"是<b>实例内</b>事实，而 park 之后"谁结算"是<b>跨实例</b>事实。上一版把它
 * 当判据时同时踩了两头：跨实例回灌会与源实例的批循环各发一条（两条 end、{@code readCalls=2}）；
 * 多卡乱序回灌会因"预登记闸门被唤醒但随后被 park 摘掉"而一条都不发（0 条 end）。
 *
 * <p>故发点统一走 {@link #emitOnce}：抢 {@code ai:observed:<tenantId>:<userId>:<runId>} 的位（{@code HSETNX}，
 * 见 {@link SettlementObservationStore}）——<b>首写者发、其后一律不发</b>，与"发点在哪个实例"无关；
 * {@link SheetAudit} 的计数与日志行在同一次抢占之内，两面必然同增同不增。
 *
 * <p>哪些调用需要仲裁：{@code CONFIRM}/{@code FRONTEND}（挂起后可能被决策/认领在别处结算）。
 * {@code BACKEND} 的结算由执行认领独占（{@code tryClaimExecution} 只有一个胜者），不额外占用一个键位。
 *
 * <p>续跑的"复用结果"分支刻意<b>不</b>走本类：重挂/重试会反复经过它，在那里补记会把
 * {@code readCalls} 放大成"重挂次数"。
 */
@Slf4j
final class ToolCallEndTrace {

	private ToolCallEndTrace() {
	}

	/**
	 * <b>结算发点的唯一入口</b>：先构造载荷，再抢观测位，抢到才写一行 + 记一次审计。
	 * <b>失败方向</b>：写行<b>前</b>失败 ⇒ 释放占位（下一位可补发）；写行<b>后</b>失败 ⇒ 保留占位
	 * （行已在，重复比重记更糟）。
	 *
	 * @param observations 结算观测位的仲裁面（{@code null} = 轻装配：不做跨实例仲裁，直接发）
	 * @param settled   结算后的条目真值（结局/码/执行者/表格元数据都取它）
	 * @param toolCallId / {@code tool} / {@code kind}：调用身份，取<b>发起那一次</b>的副本
	 *                  （外置存储读回来的新副本可能与它不等价，见 {@code SpToolCallingManager}）
	 * @param ms        含等待的总耗时（park 结算的算法见 {@link #emitForSettledEntry}）
	 * @param execMs    扣掉审批等待后的执行耗时
	 * @param text      该调用的结果文本（只用来记字符数，正文不进日志）
	 */
	static void emitOnce(SettlementObservationStore observations, String runId, String sessionId,
			PendingToolCall settled, String toolCallId, String tool, String kind, long ms, long execMs, String text,
			SheetAudit audit) {
		if (!requiresArbitration(kind) || observations == null) {
			// 无仲裁面（BACKEND 由认领独占 / 轻装配）：与引入观测位之前逐字同形
			emit(runId, sessionId, settled, toolCallId, tool, kind, ms, execMs, text, audit);
			return;
		}
		// 载荷<b>先于抢位</b>构造：构造失败时"一行都没写、位也没抢" ⇒ 不需要消费/释放占位
		Object[] payload = pairs(settled, toolCallId, tool, kind, ms, execMs, text);
		if (!observations.claim(runId, toolCallId)) {
			log.debug("结算行已由另一执行体发过，本次不再发 runId={} toolCallId={} tool={} kind={}", runId, toolCallId,
					tool, kind);
			return;
		}
		try {
			AiTrace.eventOn(runId, sessionId, "tool_call_end", payload);
		}
		catch (RuntimeException ex) {
			// 抢到了位却<b>没写出行</b>：把占位放回去，让下一个执行体/下一次结算还能发（宁重不宁缺）。
			// 不释放的后果是"永久 0 条"——比重记更糟的方向（独立复审 I-1）。
			observations.release(runId, toolCallId);
			throw ex;
		}
		// 行<b>已经写出</b>：此后的审计面失败<b>不得</b>释放占位 —— 释放会让下一次结算再发一行
		// （同一 toolCallId 两条 end + 两次计数），把"恰好一次"变成"至多两条"（复审 R-I-1）。
		recordAudit(sessionId, settled, audit);
	}

	/**
	 * <b>自建载荷的结算发点</b>（当前唯一使用者：{@code SpToolCallingManager#traceToolAborted}）。
	 *
	 * <p>abort 行的字段集合与常规结算不同（多 {@code cancelled}、{@code code} 取异常分类、
	 * {@code ms/execMs} 都是"开始 → 异常"），塞不进 {@link #emitOnce} 的载荷构造；但"这一条调用
	 * 结算过没有"<b>必须</b>走同一套仲裁 —— 否则 {@code ConfirmGate#settleCancelledToolCalls}
	 * 的取消收尾会与 abort 行各发一条，把"恰好一次"变成同一 toolCallId 两条 end
	 * （abort 在 {@code await} 里已经看到取消、条目转 CANCELLED 之后仍会走到）。
	 *
	 * <p>语义与 {@link #emitOnce} 逐字同源：
	 * <ul>
	 * <li>{@code BACKEND} 不占位（结算权归执行认领，见 {@link #requiresArbitration}）⇒ 直发；</li>
	 * <li>无仲裁面（轻装配/单测）⇒ 直发，与引入观测位之前同形；</li>
	 * <li>抢不到位 ⇒ 一条不发（这条调用已被别的执行体结算）；</li>
	 * <li>写行失败 ⇒ 释放占位（下一位可补发；宁重不宁缺）。</li>
	 * </ul>
	 *
	 * @param payload 自建载荷的发射动作（只在"无需仲裁"或"抢到仲裁位"时执行）
	 */
	static void emitCustomOnce(SettlementObservationStore observations, String runId, String toolCallId, String kind,
			Runnable payload) {
		if (!requiresArbitration(kind) || observations == null) {
			payload.run();
			return;
		}
		if (!observations.claim(runId, toolCallId)) {
			log.debug("结算行已由另一执行体发过，本次不再发 runId={} toolCallId={} kind={}", runId, toolCallId, kind);
			return;
		}
		try {
			payload.run();
		}
		catch (RuntimeException ex) {
			observations.release(runId, toolCallId);
			throw ex;
		}
	}

	/**
	 * 发一条 {@code tool_call_end} 并（{@code audit} 已装配时）记一次表格审计结算。
	 *
	 * <p>只给 {@link #emitOnce} 用：越过观测位直接调本方法就是"第二条 end"的来源。
	 */
	private static void emit(String runId, String sessionId, PendingToolCall settled, String toolCallId, String tool,
			String kind, long ms, long execMs, String text, SheetAudit audit) {
		AiTrace.eventOn(runId, sessionId, "tool_call_end", pairs(settled, toolCallId, tool, kind, ms, execMs, text));
		recordAudit(sessionId, settled, audit);
	}

	/** 表格审计面：{@code /health.sheet} 计数 + 台账（未装配/无表格元数据时什么都不做）。 */
	private static void recordAudit(String sessionId, PendingToolCall settled, SheetAudit audit) {
		if (audit == null || settled == null) {
			return;
		}
		Map<String, Object> sheetMeta = settled.getSheetMeta();
		if (sheetMeta == null || sheetMeta.isEmpty()) {
			return;
		}
		audit.record(sessionId, settled);
	}

	/**
	 * park 结算的发射口：调用方已过所有权边界（决策 guard 胜者 / 认领胜者），
	 * 且本进程<b>没有</b>等待线程会再发一行。
	 *
	 * <h2>耗时口径（只能来自条目本身）</h2>
	 * 结算发生在另一个执行体里，"工具开始执行的那一刻"没在本进程的局部变量上，故全部取条目上
	 * 的钟点，与内联口径可对齐：
	 * <ul>
	 * <li>{@code ms} = 结算时刻 − 卡/认领的最早起点（含人工等待）；</li>
	 * <li>{@code execMs} = 结算时刻 − 认领时刻 —— 前端工具没有认领 ⇒ 0（后端没执行它的桩体），
	 * 与设计稿"只对 {@code CONFIRM}/{@code FRONTEND} 扣审批等待"同一口径；</li>
	 * <li>缺项逐级回落（{@code executedAtMs → resolvedAtMs → now}），宁可少扣，不凭猜造数。</li>
	 * </ul>
	 */
	static void emitForSettledEntry(SettlementObservationStore observations, String runId, String sessionId,
			PendingToolCall settled, String text, SheetAudit audit) {
		long resolvedAtMs = firstPositive(settled.getExecutedAtMs(), settled.getResolvedAtMs(),
				System.currentTimeMillis());
		long executionStartMs = firstPositive(settled.getClaimedAtMs(), resolvedAtMs);
		long cardStartMs = firstPositive(settled.getReqAtMs(), executionStartMs);
		emitOnce(observations, runId, sessionId, settled, settled.getToolCallId(), settled.getName(), settled.getKind(),
				Math.max(0L, resolvedAtMs - cardStartMs), Math.max(0L, resolvedAtMs - executionStartMs), text, audit);
	}

	/**
	 * 只有"挂起后可能被别人结算"的通道才需要观测位仲裁：{@code CONFIRM}/{@code FRONTEND}
	 * （以及 {@code kind} 缺失的旧数据 —— 宁可多一次 HSETNX，也不猜）。
	 * {@code BACKEND} 的执行由认领（{@code tryClaimExecution}）独占，不占键位。
	 */
	private static boolean requiresArbitration(String kind) {
		return !PendingToolCall.KIND_BACKEND.equals(kind);
	}

	private static Object[] pairs(PendingToolCall settled, String toolCallId, String tool, String kind, long ms,
			long execMs, String text) {
		// 用 add 而不是 List.of：code/kind 允许为 null（成功条目没有 code），List.of 会 NPE
		List<Object> pairs = new ArrayList<>();
		pairs.add("toolCallId");
		pairs.add(toolCallId);
		pairs.add("tool");
		pairs.add(tool);
		pairs.add("kind");
		pairs.add(kind);
		pairs.add("ms");
		pairs.add(ms);
		pairs.add("execMs");
		pairs.add(execMs);
		pairs.add("skipped");
		pairs.add(false);
		pairs.add("ok");
		pairs.add(settled.effectiveOutcome().ok());
		pairs.add("outcome");
		pairs.add(settled.effectiveOutcome().wire());
		pairs.add("code");
		pairs.add(settled.effectiveCode());
		pairs.add("executed");
		pairs.add(settled.isExecuted());
		pairs.add("chars");
		pairs.add(text == null ? 0 : text.length());
		// AI 表格工具治理批（2026-10-22 §4.2）：表格工具的采样元数据挂同一行的 additive 字段
		// （8 键；非表格工具一个键都不写 —— "逐键可缺席"）。值一律来自条目上的 sheetMeta 白名单副本
		// （SheetAudit#sanitize 在入口就丢掉了任何业务值）。
		Map<String, Object> sheetMeta = settled.getSheetMeta();
		if (sheetMeta != null && !sheetMeta.isEmpty()) {
			pairs.addAll(SheetAudit.logPairs(sheetMeta));
		}
		return pairs.toArray();
	}

	private static long firstPositive(long... candidates) {
		for (long candidate : candidates) {
			if (candidate > 0L) {
				return candidate;
			}
		}
		return 0L;
	}

}
