package com.example.configmgr.ai.run;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工具"正在执行"的副作用信标（S4.2 §2 韧性棒；移植自 SP-01d {@code ToolActivityBeacon}）。
 *
 * <h2>它解决的两个问题</h2>
 * <ol>
 * <li><b>重试的副作用判定</b>：只判"本轮是否已向客户端产出内容"不够——工具副作用可能发生在
 * 内容之前（SP-01d 实测教训）。{@code startedCount} 在工具<b>开始执行</b>时即自增，
 * 于是"重试会不会把工具再执行一遍"可以在这里被拦住；</li>
 * <li><b>硬规范①（超时阈值 &gt; 最长工具耗时）的落地</b>：挂起等待（确认门/前端工具）本质是
 * "上游事件流静默"——若按配置值（默认 90s）直接判定，一次 120s 的人工确认就会被误判成断流。
 * {@link #isActive(String)} 把工具执行窗口告知 {@link StreamWatchdog}，
 * 使静默判定在工具执行期间<b>不参与</b>，退出后以退出时刻为锚点重新计时。</li>
 * <li><b>人机预算分离 → 段级预算</b>（2026-10-20 裁决第 3 条起步，2026-10-21 决策看板
 * {@code :14} 改为「段级预算重置式」）：挂起等待（{@link #beginGateWait} / {@link #endGateWait}）
 * 仍累计成 {@link #gateWaitMs(String)}（<b>只供日志/指标</b>，不再参与预算判据）；预算判据改看
 * <b>段起点</b>（{@link #segmentStartedAtMs(String, long)}）—— 挂起点段结束（{@link #inGateWait}），
 * 恢复点（决策/回灌/续跑）段重置，于是每段各自重新拥有
 * {@code app.ai.resilience.total-budget}（300s）。</li>
 * </ol>
 *
 * <p>
 * 状态全在进程内（纯加速/判定用，不承载可恢复状态）；跨进程的权威副作用记录是
 * {@link RunStore} 的台账（{@code ai:ledger:<runId>}），本类的 {@link #startedCount(String)}
 * 只回答"<b>本次尝试</b>里工具是否开始过"。
 */
@Slf4j
@Component
public class ToolActivityBeacon {

	private final Map<String, Activity> active = new ConcurrentHashMap<>();

	/** runId → 该轮累计开始的工具执行次数（含正在执行与被中断的）。 */
	private final ConcurrentMap<String, AtomicLong> started = new ConcurrentHashMap<>();

	/** runId → 最后一次活动时刻（进入/退出/脉冲），退出后仍保留，供静默锚点使用。 */
	private final ConcurrentMap<String, AtomicLong> lastActivityAtMs = new ConcurrentHashMap<>();

	/**
	 * runId → 累计的<b>挂起等待</b>时长（毫秒；已结束的等待窗口之和）。
	 *
	 * <p><b>语义变更（2026-10-21 决策看板 {@code :14}）</b>：本量<b>不再</b>从预算里扣除 ——
	 * 段级预算重置式把「等人」表达成"挂起点段结束、恢复点段重置"（见
	 * {@link #segmentStartedAtMs(String, long)}）。本量保留为<b>观测面</b>
	 * （日志批的 {@code turn_end.gateWaitMs} 与排障口径依赖它），删掉会让"这一轮为什么慢"失去一半证据。
	 */
	private final ConcurrentMap<String, AtomicLong> gateWaitFinishedMs = new ConcurrentHashMap<>();

	/** runId → 当前挂起等待的起点（毫秒；0 = 当前不在等待）。 */
	private final ConcurrentMap<String, AtomicLong> gateWaitSinceMs = new ConcurrentHashMap<>();

	/**
	 * runId → 当前<b>段</b>的起点（毫秒）。
	 *
	 * <p>段 = 一次"机器在干活"的连续窗口：轮开始 / 决策后恢复 / 回灌后恢复 / 续跑。
	 * 决策看板 {@code :14}（2026-10-21）：段预算重置式 —— 挂起点段结束，恢复点段重置，
	 * 每段各自重新拥有 {@code app.ai.resilience.total-budget}。<b>不跨段累计</b>。
	 *
	 * <p>为什么放在信标而不是看门狗字段里：挂起发生在<b>工具循环线程</b>上，而判定发生在
	 * 看门狗的调度线程上 —— 同一个 runId 的两条路径只有共享一个进程内锚点才能表达"段重置"。
	 */
	private final ConcurrentMap<String, AtomicLong> segmentStartedAtMs = new ConcurrentHashMap<>();

	private final Map<String, Map<String, Object>> lastFinished = new ConcurrentHashMap<>();

	/**
	 * runId → 轮内上下文预算进入"收尾"（armed）的时刻（毫秒；0/缺失 = 未武装）。
	 *
	 * <p>为什么放在信标：武装的判据在<b>工具循环线程</b>上读、又在<b>同一线程</b>的上一批写；
	 * 与段预算同一族"进程内加速态"。它<b>不</b>承载可恢复状态：跨进程续跑读不到 armed 时，
	 * 下一批会按真值/估算重新判一次预算（最坏情况是重判一次，不会漏判）。
	 */
	private final ConcurrentMap<String, AtomicLong> budgetArmedAtMs = new ConcurrentHashMap<>();

	/**
	 * runId → armed 之后被拦下的工具批次数（{@code TurnBudgetPlan#MAX_ARMED_BATCHES} 的输入）。
	 *
	 * <p>收尾指令是软约束：模型可以不理。计数让"反复请求工具"有确定的上界 ——
	 * 到顶按 {@code CONTEXT_OVERFLOW} 收尾（先落定、后报错），而不是交给 300s 看门狗。
	 */
	private final ConcurrentMap<String, AtomicLong> budgetSkippedBatches = new ConcurrentHashMap<>();

	/** 工具开始执行：置活跃 + 记锚点 + 计数。 */
	public void enter(String runId, String toolName, String kind) {
		Activity activity = new Activity(toolName, kind);
		this.active.put(runId, activity);
		this.started.computeIfAbsent(runId, key -> new AtomicLong()).incrementAndGet();
		this.lastActivityAtMs.computeIfAbsent(runId, key -> new AtomicLong()).set(System.currentTimeMillis());
	}

	/** 工具执行期间的心跳（分片/等待节拍）：刷新活动锚点，防"长工具被当静默"。 */
	public void pulse(String runId) {
		AtomicLong anchor = this.lastActivityAtMs.get(runId);
		if (anchor != null) {
			anchor.set(System.currentTimeMillis());
		}
	}

	/**
	 * <b>被动</b>活动锚点（F5 预检）：只刷新 {@link #lastActivityAtMs(String)}，
	 * <b>不</b>计入 {@link #startedCount(String)}、也不置 {@link #isActive(String)}。
	 *
	 * <p>预检（尤其 IMPORT 的多文件 sha256 指纹）是挂着上游静默在跑的只读重活；若不做锚点，
	 * 长计算会被 {@code StreamWatchdog} 当成"上游断流"。但它<b>不是</b>工具副作用 ——
	 * 计数自增会污染"本次尝试是否已产生副作用"的重试判定，故与 {@link #enter} 分开。
	 * 与 {@link #pulse} 不同，锚点不存在时会创建（预检可能发生在任何工具执行之前）。
	 */
	public void markPassiveActivity(String runId) {
		this.lastActivityAtMs.computeIfAbsent(runId, key -> new AtomicLong()).set(System.currentTimeMillis());
	}

	/** 工具执行结束：移出活跃表，保留退出时刻与结局（供重试判定与取证）。 */
	public void exit(String runId, String outcome) {
		Activity activity = this.active.remove(runId);
		long now = System.currentTimeMillis();
		this.lastActivityAtMs.computeIfAbsent(runId, key -> new AtomicLong()).set(now);
		Map<String, Object> finished = new LinkedHashMap<>();
		finished.put("runId", runId);
		finished.put("tool", activity == null ? null : activity.toolName);
		finished.put("kind", activity == null ? null : activity.kind);
		finished.put("outcome", outcome);
		finished.put("exitedAtMs", now);
		this.lastFinished.put(runId, finished);
		log.debug("工具执行退出 runId={} tool={} outcome={}", runId, finished.get("tool"), outcome);
	}

	/**
	 * 进入挂起等待（人在想 / 前端在填）：开始累计"不计入预算"的时长。
	 *
	 * <p>同一个 runId 的工具调用是<b>串行</b>结算的（一个批次内逐个 await），故不需要嵌套计数；
	 * 重复 begin 只把起点前移（等价于"这段等待从此刻算"，宁可少扣不可多扣）。
	 */
	public void beginGateWait(String runId) {
		this.gateWaitSinceMs.computeIfAbsent(runId, key -> new AtomicLong()).set(System.currentTimeMillis());
	}

	/**
	 * 退出挂起等待：把本段等待时长并进累计量（幂等；未在等待时调用无副作用），
	 * 并把<b>段起点</b>前移到此刻 —— 这就是「恢复点段重置」的唯一落点
	 * （决策/回灌/超时/取消四条收尾路径都经 {@code ConfirmGate} 的 finally 走到这里）。
	 */
	public void endGateWait(String runId) {
		AtomicLong since = this.gateWaitSinceMs.get(runId);
		if (since == null) {
			return;
		}
		long start = since.getAndSet(0L);
		if (start > 0) {
			long waited = Math.max(0L, System.currentTimeMillis() - start);
			this.gateWaitFinishedMs.computeIfAbsent(runId, key -> new AtomicLong()).addAndGet(waited);
			// 段重置：恢复点给本段一个全新的预算时钟
			this.segmentStartedAtMs.computeIfAbsent(runId, key -> new AtomicLong())
				.set(System.currentTimeMillis());
		}
	}

	/**
	 * 该轮累计的挂起等待时长（毫秒，<b>含</b>当前正在进行的等待）—— <b>纯观测量</b>。
	 *
	 * <p>2026-10-21 起它不再参与预算判据（判据看段起点，见 {@link #segmentStartedAtMs(String, long)}），
	 * 但仍是日志/排障的一等证据：它回答"这一轮里人占了多少时间"。
	 */
	public long gateWaitMs(String runId) {
		long finished = 0L;
		AtomicLong total = this.gateWaitFinishedMs.get(runId);
		if (total != null) {
			finished = total.get();
		}
		AtomicLong since = this.gateWaitSinceMs.get(runId);
		long ongoing = 0L;
		if (since != null) {
			long start = since.get();
			if (start > 0) {
				ongoing = Math.max(0L, System.currentTimeMillis() - start);
			}
		}
		return finished + ongoing;
	}

	/** 该轮当前是否有工具正在执行（静默判定的抑制条件，硬规范①）。 */
	public boolean isActive(String runId) {
		return this.active.containsKey(runId);
	}

	/**
	 * 是否正处在挂起等待（确认门 / 前端工具 / 无超时工具）里 —— 即<b>段已结束</b>。
	 *
	 * <p>预算判据在段结束期间<b>暂停</b>：否则一次十几分钟的表单填写会在等待期里被判成
	 * {@code TOTAL_BUDGET}，把"无上限的等待"变成 300s 的死线（决策看板 {@code :14} 的挂起点）。
	 */
	public boolean inGateWait(String runId) {
		AtomicLong since = this.gateWaitSinceMs.get(runId);
		return since != null && since.get() > 0L;
	}

	/**
	 * 标记<b>段起点</b>（轮开始 / 续跑入口）。已存在则前移 —— 调用点就是"新段开始"的事实。
	 */
	public void markSegmentStart(String runId, long startedAtMs) {
		this.segmentStartedAtMs.computeIfAbsent(runId, key -> new AtomicLong()).set(startedAtMs);
	}

	/**
	 * 当前段起点；尚无记录时以 {@code fallback} 播种并返回它。
	 *
	 * <p>看门狗每拍读一次（而非在构造时缓存在字段里）：恢复点重置发生在本进程的<b>另一条线程</b>
	 * （工具循环线程的 {@code endGateWait}），缓存值会停在旧段上。
	 */
	public long segmentStartedAtMs(String runId, long fallback) {
		AtomicLong start = this.segmentStartedAtMs.computeIfAbsent(runId, key -> new AtomicLong(fallback));
		return start.get();
	}

	/** 该轮是否已经"开始过"工具执行（正在执行也算）——重试副作用判定的进程内一侧。 */
	public boolean hasActivity(String runId) {
		return startedCount(runId) > 0;
	}

	/** 该轮累计开始的工具执行次数（尝试前后取差即"本次尝试有没有副作用"）。 */
	public long startedCount(String runId) {
		AtomicLong count = this.started.get(runId);
		return count == null ? 0L : count.get();
	}

	/** 最后一次活动时刻（进入/退出）；从未活动过返回 0。 */
	public long lastActivityAtMs(String runId) {
		AtomicLong anchor = this.lastActivityAtMs.get(runId);
		return anchor == null ? 0L : anchor.get();
	}

	/** 武装本轮（轮内上下文预算达收尾线）：此后工具调用只结算"未执行"，不再产生新副作用。 */
	public void armBudget(String runId) {
		this.budgetArmedAtMs.computeIfAbsent(runId, key -> new AtomicLong())
			.set(System.currentTimeMillis());
	}

	/** 本轮是否已武装（收尾中）。 */
	public boolean budgetArmed(String runId) {
		AtomicLong armedAt = this.budgetArmedAtMs.get(runId);
		return armedAt != null && armedAt.get() > 0L;
	}

	/** armed 之后已被拦下的工具批次数（未武装返回 0）。 */
	public int budgetSkippedBatches(String runId) {
		AtomicLong count = this.budgetSkippedBatches.get(runId);
		return count == null ? 0 : count.intValue();
	}

	/** 记一批"武装后仍请求工具"（返回累计批次数）。 */
	public int markBudgetSkippedBatch(String runId) {
		long count = this.budgetSkippedBatches.computeIfAbsent(runId, key -> new AtomicLong()).incrementAndGet();
		return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
	}

	/** 轮终态清理（进程内状态随轮结束释放）。 */
	public void clear(String runId) {
		this.active.remove(runId);
		this.started.remove(runId);
		this.lastFinished.remove(runId);
		this.gateWaitFinishedMs.remove(runId);
		this.gateWaitSinceMs.remove(runId);
		this.segmentStartedAtMs.remove(runId);
		// lastActivityAtMs 故意保留：重试判定要看退出时刻（TTL 由轮的生命周期兜住）
		this.budgetArmedAtMs.remove(runId);
		this.budgetSkippedBatches.remove(runId);
	}

	public Map<String, Object> snapshot() {
		Map<String, Object> activeView = new LinkedHashMap<>();
		this.active.forEach((runId, activity) -> activeView.put(runId, activity.toolName()));
		Map<String, Object> startedView = new LinkedHashMap<>();
		this.started.forEach((runId, count) -> startedView.put(runId, count.get()));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("active", activeView);
		out.put("startedCounts", startedView);
		out.put("lastFinished", Map.copyOf(this.lastFinished));
		return out;
	}

	private record Activity(String toolName, String kind) {
	}

}
