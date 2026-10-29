package com.example.configmgr.ai.run;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 会话级键族的<b>唯一登记表</b>（超时面治理批① 第 11 项）。
 *
 * <h2>为什么要有这张表</h2>
 * 到批① 为止，"哪些 Redis 键活多久、由谁续期"散在三处：{@link RunStore#renewSuspensionTtl}
 * （挂起期 2s 心跳 5 类）、{@link RunStore#touchActiveRun}（读/决策追加 4 类）、
 * {@link CancellationRegistry}（取消标志，TTL 自己算 `total-budget × 2`）。三个地方各写各的
 * ⇒ 新增一个"与会话同寿"的键时必然漏一处（取消标志就是漏掉的那一处：会话 24h、取消信号只有 10min）。
 * 本表把"键族 → 作用域 → 续期面 → 说明"一次登记，续期动作只从本表派生。
 *
 * <h2>三档续期面（口径固定；改档 = 改语义，须同步用例）</h2>
 * <ul>
 * <li>{@link Renewal#HEARTBEAT}：挂起期心跳（`ConfirmGate` 2s）逐拍整额续期。<b>键数 = 5</b>；
 * {@code RunStoreSlidingTtlTest} 断言<b>每个应续的键都续到了</b>（L-139 后不再锁调用次数形态，
 * 因为逐键 try 下"某键失败"不应改变其余键的续期）。</li>
 * <li>{@link Renewal#READ}：任何实例的读/决策（{@code touchActiveRun}）在心跳面<b>之上追加</b>的键
 * （与心跳面不重叠，避免同一键一拍发两次 EXPIRE）：契约位 {@code ai:req} / 决策 guard {@code ai:decided} /
 * fence {@code ai:fence:run} + <b>本批并入的 {@code ai:cancel}</b>（用户裁定：取消信号随会话共亡
 * —— 它不该比会话先死）+ <b>取号计数器 {@code ai:lease:epoch}</b>（必须与 fence 同面：号比 fence
 * 先过期 = 新租约从 1 起算、被残留 fence 拒死，评审 I-1 的变体）。</li>
 * <li>{@link Renewal#WRITE}：随写续期（写路径各自 SET/EXPIRE 整额 TTL）。只登记，不参与上面两个面。</li>
 * </ul>
 *
 * <h2>显式不进本表（分类而不遗漏）</h2>
 * <ul>
 * <li><b>全局键</b>：{@code ai:cfg:ver}（配置版本）、{@code ai:rate:*}（限流窗口 —— 生命周期 = 窗口长度，
 * 与会话无关）、{@code ai:lease:epoch}（旧全局计数，<b>已不参与 fencing</b>，只剩资源锁 token 编号）；</li>
 * <li><b>短命协调键</b>：{@code ai:lock:task|job:* }（60s，临界区上界，<b>不续租</b>）、
 * {@code ai:claim:<runId>}（认领位，写路径自带 TTL，无独立续期口径）。</li>
 * </ul>
 *
 * <h2>两个"12"要说清（评审 Minor 2；R1-09 后为 14 / 13）</h2>
 * <ul>
 * <li><b>本表的 run 级登记 = 14 条</b>（见 {@link #RUN_KEY_COUNT}）：快照 / 待决 / 兼容 LIST / seq /
 * 事件 Stream（心跳 5）＋ req / decided / fence / cancel（读侧 4）＋ beat / ledger / idem / activity
 * （随写 4）＋ <b>取号计数器</b>（读侧，与 fence 同面）。本表只登记"谁需要被续期"，不登记"谁要被删除"。</li>
 * <li><b>{@code deleteRun} 手里的 = 13 个</b>：本表 14 条里去掉 {@code ai:beat:} / {@code ai:activity:}
 * / {@code ai:cancel:}（各自按 TTL 走），加上本表不登记的 {@code ai:claim:} 与 {@code ai:lease:run:}。
 * 两套数字不同不是笔误 —— 一个是"续期面"，一个是"同生同死面"。</li>
 * </ul>
 *
 * <h2>hash tag 口径（B23L1 批① Cluster 硬阻断）</h2>
 * 判定标准 = <b>"它是不是 {@code deleteRun} 手里那一轮 13 键中的一员"</b>（这一族全部同 tag ⇒ 同槽）：
 * <ul>
 * <li><b>带 {@code {<tenantId>:<userId>:run:<runId>}} tag</b>（13 键，全在 {@link RunStore} 的键方法里；
 * 无身份上下文的过渡期路径退回 legacy 形 {@code {run:<runId>}}）：
 * {@code ai:run:} / {@code ai:pending:} / {@code ai:claim:} / {@code ai:ledger:} / {@code ai:idem:} /
 * {@code ai:events:} / {@code ai:seq:} / {@code ai:xevents:} / {@code ai:lease:run:} /
 * {@code ai:fence:run:} / {@code ai:req:} / {@code ai:decided:} / {@code ai:lease:epoch:}（取号）。
 * 三条多键脚本靠它同槽：{@code FENCED_SET}（快照+fence）、{@code IDEM_PUSH}（台账+索引）、
 * {@code ACQUIRE}（租约+计数器 —— 号与占用必须同序，评审 I-1）；{@code deleteRun} 的 13 键
 * {@code DEL} 也靠它回到"一条命令、原子生效"。
 * 取键一律走 {@link RunStore#runScopedKey(String, String)}（{@code RedisFrameStore} 的三族键同理）；</li>
 * <li><b>裸前缀</b>（{@code ai:beat:} / {@code ai:activity:} / {@code ai:cancel:}）：只进单键命令、
 * 不在 13 键里，无需同槽 —— 给它们加 tag 只牺牲分布，没有收益（{@code ai:activity} 的写是单键
 * Lua 原子 max，见 {@code RunStore#touchBusinessActivity}）。</li>
 * </ul>
 * <p><b>禁止项</b>：单键脚本（RENEW/VERIFY/RELEASE/滑动窗口/COMPARE_DELETE）与
 * {@code ai:rate:*} / {@code kb:cache:*} / {@code ai:cfg:ver} / {@code ai:session:*} <b>一律不加 tag</b>
 * —— 加了只牺牲分布，且会把限流热度压进单槽。
 *
 * <p>
 * 本表是<b>声明</b>，不是"续期执行器"：执行点仍在 {@link RunStore}（它持有 Redis 与 TTL 配置），
 * 但取键一律走 {@link #renewalKeys(Renewal)} —— 新增键只改这张表。
 */
public final class SessionKeyRegistry {

	/** 续期面。 */
	public enum Renewal {

		/** 挂起期心跳逐拍续期（5 类，计数被用例锁定）。 */
		HEARTBEAT,

		/**
	 * 读/决策触发的续期（<b>心跳面之上追加</b>：契约位 2 + fence + 取号计数器 + 取消信号 = 5；
	 * 与心跳面不重叠）。取号计数器必须跟 fence 同面：号一旦比 fence 先过期，新租约就会从 1 起算
	 * 并被残留 fence 拒死（评审 I-1 的变体）。
	 */
		READ,

		/** 随写续期（只登记，不进上面两面）。 */
		WRITE

	}

	/** 作用域：run 级（键名带 runId）或 session 级（键名带 sessionId）。 */
	public enum Scope {

		RUN,

		SESSION

	}

	/**
	 * 一条登记。
	 *
	 * @param prefix 键前缀（文档/grep 的锚点）
	 * @param sample 样例键名（`<runId>` / `<sessionId>` 占位）
	 * @param scope 作用域
	 * @param renewals 参与哪些续期面（只含 WRITE = 只在写路径续期）
	 * @param note 一句话语义
	 */
	public record Entry(String prefix, String sample, Scope scope, List<Renewal> renewals, String note) {

		boolean renews(Renewal renewal) {
			return this.renewals.contains(renewal);
		}

	}

	/** run 级键的键名装配器（与 {@link RunStore} / {@link CancellationRegistry} 的公开键方法一一对应）。 */
	private record RunKey(String prefix, Function<String, String> builder, List<Renewal> renewals, String note) {

	}

	private static final List<RunKey> RUN_KEYS = runKeys();

	private static final List<Entry> ENTRIES = buildEntries();

	/** run 级登记条数（14：5 心跳 + 5 读侧追加〔含取号计数器〕+ 4 随写）。 */
	public static final int RUN_KEY_COUNT = RUN_KEYS.size();

	private SessionKeyRegistry() {
	}

	private static List<RunKey> runKeys() {
		List<RunKey> keys = new ArrayList<>();
		// 1) 挂起期心跳面（5 类）：会话活着，卡的后台数据就活着
		keys.add(new RunKey(RunStore.RUN_PREFIX, runId -> RunStore.runScopedKey(RunStore.RUN_PREFIX, runId),
				List.of(Renewal.HEARTBEAT), "轮快照（含挂起中的 assistant(tool_calls)）"));
		keys.add(new RunKey(RunStore.PENDING_PREFIX, runId -> RunStore.runScopedKey(RunStore.PENDING_PREFIX, runId),
				List.of(Renewal.HEARTBEAT), "待决条目（人的决策落点）"));
		keys.add(new RunKey(RunStore.EVENTS_PREFIX, runId -> RunStore.runScopedKey(RunStore.EVENTS_PREFIX, runId),
				List.of(Renewal.HEARTBEAT), "帧归档（兼容期只读，与 RedisFrameStore#legacyListKey 同键）"));
		keys.add(new RunKey(RunStore.SEQ_PREFIX, runId -> RunStore.runScopedKey(RunStore.SEQ_PREFIX, runId),
				List.of(Renewal.HEARTBEAT), "已发放序号（差量补发锚点，与 RedisFrameStore#seqKey 同键）"));
		keys.add(new RunKey(RunStore.XEVENTS_PREFIX, runId -> RunStore.runScopedKey(RunStore.XEVENTS_PREFIX, runId),
				List.of(Renewal.HEARTBEAT), "事件日志 Stream（回放真值，与 RedisFrameStore#streamKey 同键）"));
		// 2) 读侧续期面追加（契约位 / 决策 guard / fence / 取消信号）
		keys.add(new RunKey(RunStore.REQ_PREFIX, runId -> RunStore.runScopedKey(RunStore.REQ_PREFIX, runId),
				List.of(Renewal.READ), "请求帧已发位（L0 契约位）"));
		keys.add(new RunKey(RunStore.DECIDED_PREFIX, runId -> RunStore.runScopedKey(RunStore.DECIDED_PREFIX, runId),
				List.of(Renewal.READ), "决策 guard / WAL"));
		keys.add(new RunKey(RunStore.FENCE_RUN_PREFIX,
				runId -> RunStore.runScopedKey(RunStore.FENCE_RUN_PREFIX, runId), List.of(Renewal.READ),
				"fence 号（与快照同寿、同槽，防号被推回）"));
		keys.add(new RunKey(LeaseService.EPOCH_PREFIX,
				runId -> RunStore.runScopedKey(LeaseService.EPOCH_PREFIX, runId), List.of(Renewal.READ),
				"取号计数器（per-run、与租约键同槽；与 fence 同面同寿）"));
		keys.add(new RunKey(CancellationRegistry.CANCEL_PREFIX, runId -> CancellationRegistry.CANCEL_PREFIX + runId,
				List.of(Renewal.READ), "取消标志（随会话共亡）"));
		// 3) 随写续期（只登记）
		keys.add(new RunKey(RunStore.BEAT_PREFIX, runId -> RunStore.BEAT_PREFIX + runId, List.of(Renewal.WRITE), "心跳活动戳"));
		keys.add(new RunKey(RunStore.ACTIVITY_PREFIX, runId -> RunStore.ACTIVITY_PREFIX + runId,
				List.of(Renewal.WRITE), "业务活动锚点（非心跳；僵尸判据）"));
		keys.add(new RunKey(RunStore.LEDGER_PREFIX, runId -> RunStore.runScopedKey(RunStore.LEDGER_PREFIX, runId),
				List.of(Renewal.WRITE), "工具执行台账（与幂等索引同槽）"));
		keys.add(new RunKey(RunStore.IDEM_PREFIX, runId -> RunStore.runScopedKey(RunStore.IDEM_PREFIX, runId),
				List.of(Renewal.WRITE), "幂等索引（与台账同槽）"));
		return List.copyOf(keys);
	}

	private static List<Entry> buildEntries() {
		List<Entry> all = new ArrayList<>();
		for (RunKey key : RUN_KEYS) {
			all.add(new Entry(key.prefix(), key.builder().apply("<runId>"), Scope.RUN, key.renewals(), key.note()));
		}
		// session 级：键名带 sessionId（不是 runId），续期随各自写/读路径
		all.add(new Entry(RunStore.SESSION_CURRENT_PREFIX, RunStore.SESSION_CURRENT_PREFIX + "<sessionId>",
				Scope.SESSION, List.of(Renewal.WRITE), "session → 当前挂起轮索引"));
		all.add(new Entry(RunStore.CONTEXT_PREFIX, RunStore.CONTEXT_PREFIX + "<sessionId>", Scope.SESSION,
				List.of(Renewal.WRITE), "上下文真值 HASH"));
		all.add(new Entry(RunStore.CONTEXT_LEDGER_PREFIX, RunStore.CONTEXT_LEDGER_PREFIX + "<sessionId>",
				Scope.SESSION, List.of(Renewal.WRITE), "压缩事件台账 LIST"));
		all.add(new Entry(RunStore.CONTEXT_ARCHIVE_PREFIX, RunStore.CONTEXT_ARCHIVE_PREFIX + "<sessionId>:<turnNo>",
				Scope.SESSION, List.of(Renewal.WRITE), "压缩前原文"));
		all.add(new Entry(RunStore.SAFETY_PREFIX, RunStore.SAFETY_PREFIX + "<sessionId>", Scope.SESSION,
				List.of(Renewal.WRITE), "安全事件台账 LIST"));
		return Collections.unmodifiableList(all);
	}

	/** 全部登记（run 级 14 条 + session 级 5 条）。 */
	public static List<Entry> entries() {
		return ENTRIES;
	}

	/**
	 * 某一续期面的 run 级键装配器（顺序稳定 = 心跳 5 类先、读侧追加在后）。
	 *
	 * <p>只返回 run 级：session 级键（`*:<sessionId>`）在续期点由调用方单独处理
	 * （{@code RunStore#renewSessionCurrent}），因为它的入参是 sessionId 而不是 runId。
	 */
	public static List<Function<String, String>> renewalKeys(Renewal renewal) {
		List<Function<String, String>> keys = new ArrayList<>();
		for (RunKey key : RUN_KEYS) {
			if (key.renewals().contains(renewal)) {
				keys.add(key.builder());
			}
		}
		return List.copyOf(keys);
	}

	/** 某续期面的键前缀清单（断言用：心跳面必须恰好 5 条）。 */
	public static List<String> renewalPrefixes(Renewal renewal) {
		List<String> prefixes = new ArrayList<>();
		for (Entry entry : ENTRIES) {
			if (entry.scope() == Scope.RUN && entry.renews(renewal)) {
				prefixes.add(entry.prefix());
			}
		}
		return List.copyOf(prefixes);
	}

}
