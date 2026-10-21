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
 * <li>{@link Renewal#HEARTBEAT}：挂起期心跳（`ConfirmGate` 2s）逐拍整额续期。<b>计数 = 5</b>，
 * {@code RunStoreSlidingTtlTest} 锁定 exactly 5 —— 本批不动这一面。</li>
 * <li>{@link Renewal#READ}：任何实例的读/决策（{@code touchActiveRun}）在心跳面<b>之上追加</b>的键
 * （与心跳面不重叠，避免同一键一拍发两次 EXPIRE）：契约位 {@code ai:req} / 决策 guard {@code ai:decided} /
 * fence {@code ai:fence:run} + <b>本批并入的 {@code ai:cancel}</b>（用户裁定：取消信号随会话共亡
 * —— 它不该比会话先死）。</li>
 * <li>{@link Renewal#WRITE}：随写续期（写路径各自 SET/EXPIRE 整额 TTL）。只登记，不参与上面两个面。</li>
 * </ul>
 *
 * <h2>显式不进本表（分类而不遗漏）</h2>
 * <ul>
 * <li><b>全局键</b>：{@code ai:cfg:ver}（配置版本）、{@code ai:rate:*}（限流窗口 —— 生命周期 = 窗口长度，
 * 与会话无关）；</li>
 * <li><b>短命协调键</b>：{@code ai:lease:run:<runId>}（30s，持有者续租）、
 * {@code ai:lock:task|job:* }（60s，临界区上界，<b>不续租</b>）、{@code ai:claim:<runId>}（随快照写入，
 * 无独立口径）、{@code ai:lease:epoch}（无 TTL，号不回退）。</li>
 * </ul>
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

		/** 读/决策触发的续期（<b>心跳面之上追加</b>：契约位 3 + 取消信号 1；与心跳面不重叠）。 */
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

	/** run 级登记条数（12：5 心跳 + 4 读侧追加 + 3 随写）。 */
	public static final int RUN_KEY_COUNT = RUN_KEYS.size();

	private SessionKeyRegistry() {
	}

	private static List<RunKey> runKeys() {
		List<RunKey> keys = new ArrayList<>();
		// 1) 挂起期心跳面（5 类）：会话活着，卡的后台数据就活着
		keys.add(new RunKey(RunStore.RUN_PREFIX, runId -> RunStore.RUN_PREFIX + runId,
				List.of(Renewal.HEARTBEAT), "轮快照（含挂起中的 assistant(tool_calls)）"));
		keys.add(new RunKey(RunStore.PENDING_PREFIX, runId -> RunStore.PENDING_PREFIX + runId,
				List.of(Renewal.HEARTBEAT), "待决条目（人的决策落点）"));
		keys.add(new RunKey(RunStore.EVENTS_PREFIX, runId -> RunStore.EVENTS_PREFIX + runId,
				List.of(Renewal.HEARTBEAT), "帧归档（兼容期只读）"));
		keys.add(new RunKey(RunStore.SEQ_PREFIX, runId -> RunStore.SEQ_PREFIX + runId,
				List.of(Renewal.HEARTBEAT), "已发放序号（差量补发锚点）"));
		keys.add(new RunKey(RunStore.XEVENTS_PREFIX, runId -> RunStore.XEVENTS_PREFIX + runId,
				List.of(Renewal.HEARTBEAT), "事件日志 Stream（回放真值）"));
		// 2) 读侧续期面追加（契约位 / 决策 guard / fence / 取消信号）
		keys.add(new RunKey(RunStore.REQ_PREFIX, runId -> RunStore.REQ_PREFIX + runId, List.of(Renewal.READ), "请求帧已发位（L0 契约位）"));
		keys.add(new RunKey(RunStore.DECIDED_PREFIX, runId -> RunStore.DECIDED_PREFIX + runId, List.of(Renewal.READ), "决策 guard / WAL"));
		keys.add(new RunKey(RunStore.FENCE_RUN_PREFIX, runId -> RunStore.FENCE_RUN_PREFIX + runId, List.of(Renewal.READ),
				"fence 号（与快照同寿，防号被推回）"));
		keys.add(new RunKey(CancellationRegistry.CANCEL_PREFIX, runId -> CancellationRegistry.CANCEL_PREFIX + runId,
				List.of(Renewal.READ), "取消标志（随会话共亡）"));
		// 3) 随写续期（只登记）
		keys.add(new RunKey(RunStore.BEAT_PREFIX, runId -> RunStore.BEAT_PREFIX + runId, List.of(Renewal.WRITE), "心跳活动戳"));
		keys.add(new RunKey(RunStore.LEDGER_PREFIX, runId -> RunStore.LEDGER_PREFIX + runId, List.of(Renewal.WRITE), "工具执行台账"));
		keys.add(new RunKey(RunStore.IDEM_PREFIX, runId -> RunStore.IDEM_PREFIX + runId, List.of(Renewal.WRITE), "幂等索引"));
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

	/** 全部登记（run 级 12 条 + session 级 5 条）。 */
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
