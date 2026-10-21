package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.tool.ToolOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 历史结局为 {@code unknown} 的条目的<b>最小对账器</b>（B23L1 批③ 的 UNKNOWN 首跳，设计稿 §2.4）。
 *
 * <h2>为什么需要它</h2>
 * 工具执行是"先认领、再执行、最后落台账"的三段式。进程若死在第二段与第三段之间，重启后的续跑
 * 只能看到一条 {@code outcome=unknown} 的条目：<b>执行可能已经发生</b>（副作用不可撤销），也可能
 * 根本没开始。此时既不能当成功复用，也不能"重试一次"（导入/发布不可重放）。
 *
 * <h2>三跳判定（成本从低到高、判据从硬到软）</h2>
 * <ol>
 * <li><b>台账</b>（{@link RunStore#idemFor}，O(1) 索引）—— 有记录就是"执行过"，且记录里带着结局；
 * 非 {@code unknown} 的结局<b>直接采用</b>（原文与执行者一并取回）；</li>
 * <li><b>认领</b>（{@link RunStore#claimedBy}）—— 没有台账但有认领 ⇒ 某个实例拿了执行权还没交付：
 * 保持 UNKNOWN，文案点明"已在执行中"；</li>
 * <li><b>资源锁</b>（{@link ToolResourceLocks#isHeld}）—— 台账与认领都没有，但目标任务上仍有锁
 * ⇒ 同类动作正在跑（可能是另一次调用），同样无法判定；没有锁 ⇒ 老老实实说"无法判定，需人工对账"。</li>
 * </ol>
 * <b>三条路径都不改结局为 OK</b>：unknown 只在"台账里有非 unknown 的结论"时才被替换。
 * 这就是"收敛规则"的全部 —— 其余一律保持 UNKNOWN，把不确定性如实交给人工。
 *
 * <h2>不参与的通道</h2>
 * {@code FRONTEND} 通道的条目由浏览器侧回灌，后端既没有执行痕迹也没有认领记录，
 * 拿后端的三跳去对账只会得出"无法判定"的噪声结论 ⇒ 直接返回 null（调用方保持原样）。
 *
 * <h2>口径差异（N-4）：旧台账「没有 outcome 键」在两条路径上结论不同</h2>
 * <ul>
 * <li><b>本类 ⇒ UNKNOWN</b>：对账面对的是"结局未知"的条目，而旧记录<b>根本没有结局字段</b>，
 * 无法证明它成功还是失败。对账器只接受"台账里有明确且非 unknown 的结论"这一种收敛证据，
 * 缺失/无法识别一律不收敛 —— 把"没写结局"猜成成功，正是本专项要消灭的那类静默假设；</li>
 * <li><b>{@code SpToolCallingManager#replayLedger} ⇒ OK</b>：重放面对的是"同一个 toolCallId 已执行完
 * 并留下台账"，旧记录缺 {@code outcome} 只说明它是本批之前写的 —— 那时台账只在成功/失败路径都
 * 落盘、且帧面按 {@code ok} 三元展示，按成功复用是<b>历史兼容</b>（否则所有旧台账重放都会变成
 * 未知，续跑会把已完成的工具判成对账中）。</li>
 * </ul>
 * 两者的共同底线：<b>只要台账里写着 {@code unknown}，一律保持 UNKNOWN</b>（那是明确的"未判定"，
 * 不是"没有字段"）。
 */
@Slf4j
@Component
public class ToolUnknownReconciler {

	private final RunStore store;

	private final ToolResourceLocks resourceLocks;

	public ToolUnknownReconciler(RunStore store, ToolResourceLocks resourceLocks) {
		this.store = store;
		this.resourceLocks = resourceLocks;
	}

	/**
	 * 对一条 outcome=unknown 的条目做三跳判定。
	 *
	 * @return 对账结论；{@code null} = 不参与（前端通道），调用方保持原样
	 */
	public Reconciliation reconcile(String runId, PendingToolCall pending) {
		if (pending == null) {
			return null;
		}
		if (PendingToolCall.KIND_FRONTEND.equals(pending.getKind())) {
			log.debug("UNKNOWN 对账跳过前端通道 runId={} toolCallId={}", runId, pending.getToolCallId());
			return null;
		}
		String toolCallId = pending.getToolCallId();

		// ① 台账：唯一能给出确定结局的一跳（空表 = 没查到，不是"命中一条空记录"）
		Map<String, Object> ledger = this.store.idemFor(runId, toolCallId);
		if (ledger == null || ledger.isEmpty()) {
			// 旧读口兜底：真实 idemFor 内部已回落，这里只覆盖只桩了 ledgerFor 的轻装配面
			ledger = this.store.ledgerFor(runId, toolCallId);
		}
		if (ledger != null && !ledger.isEmpty()) {
			String executedBy = textOf(ledger.get("executedBy"));
			ToolOutcome outcome = ToolOutcome.fromWire(textOf(ledger.get("outcome")));
			String resultText = textOf(ledger.get("resultText"));
			if (outcome != null && outcome != ToolOutcome.UNKNOWN) {
				log.info("UNKNOWN 对账命中台账 runId={} toolCallId={} outcome={}", runId, toolCallId,
						outcome.wire());
				return new Reconciliation(true, outcome, textOf(ledger.get("code")),
						resultText == null ? "" : resultText, executedBy);
			}
			// 台账命中但结局本身就是 unknown：保持 UNKNOWN（不能把"没判定出来"写成成功）
			String text = resultText == null || resultText.isBlank()
					? "台账记录存在但结局为 UNKNOWN：无法判定该调用是否已执行，需人工对账。"
					: resultText;
			log.warn("UNKNOWN 对账命中台账但结局仍为 unknown runId={} toolCallId={}（保持 UNKNOWN）", runId,
					toolCallId);
			return new Reconciliation(true, ToolOutcome.UNKNOWN, null, text, executedBy);
		}

		// ② 认领：有人拿了执行权、尚未交付
		String claimedBy = this.store.claimedBy(runId, toolCallId);
		if (claimedBy != null) {
			log.warn("UNKNOWN 对账命中在途认领 runId={} toolCallId={} claimedBy={}（保持 UNKNOWN）", runId,
					toolCallId, claimedBy);
			return unknown("该调用已由实例 " + claimedBy + " 认领，已在执行中；结果尚未落台账，"
					+ "本次保持 UNKNOWN，需人工对账。");
		}

		// ③ 资源锁：目标资源上是否还有动作在跑
		Long taskId = ToolTargetResolver.taskIdOf(pending.getArguments());
		String jobType = ToolTargetResolver.jobTypeOf(pending.getName()) == null ? null
				: ToolTargetResolver.jobTypeOf(pending.getName()).name();
		if (taskId != null && this.resourceLocks != null && this.resourceLocks.isHeld(taskId, jobType)) {
			log.warn("UNKNOWN 对账：目标任务仍被资源锁占用 runId={} toolCallId={} taskId={}（保持 UNKNOWN）", runId,
					toolCallId, taskId);
			return unknown("目标任务上仍有作业在执行（资源锁被占用），无法判定，需人工对账；"
					+ "本次保持 UNKNOWN。");
		}

		log.warn("UNKNOWN 对账无任何执行痕迹 runId={} toolCallId={}（保持 UNKNOWN，需人工对账）", runId, toolCallId);
		return unknown("台账与认领记录里都没有该调用的执行痕迹，无法判定，需人工对账。");
	}

	private static Reconciliation unknown(String text) {
		return new Reconciliation(false, ToolOutcome.UNKNOWN, null, text, null);
	}

	private static String textOf(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	/**
	 * 对账结论。
	 *
	 * @param ledgerHit 是否命中台账（唯一可能"收敛为确定结局"的一跳）
	 * @param outcome 结论结局（只有 {@code ledgerHit && outcome != UNKNOWN} 时才可写回条目）
	 * @param code 结论码（取自台账，可空）
	 * @param text 人读文案（台账原文，或"保持 UNKNOWN + 为什么"的如实措辞）
	 * @param executedBy 台账里的执行者（未命中为空）
	 */
	public record Reconciliation(boolean ledgerHit, ToolOutcome outcome, String code, String text,
			String executedBy) {

		/** 是否收敛为确定结局（只有这一种情况允许覆盖条目的 outcome）。 */
		public boolean adopted() {
			return this.ledgerHit && this.outcome != null && this.outcome != ToolOutcome.UNKNOWN;
		}

	}

}
