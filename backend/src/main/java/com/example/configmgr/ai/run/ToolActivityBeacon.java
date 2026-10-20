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
 * <li><b>人机预算分离</b>（用户裁决 2026-10-20 第 3 条）：挂起等待（{@link #beginGateWait} /
 * {@link #endGateWait}）累计成 {@link #gateWaitMs(String)}，总预算判据按它扣除 ——
 * 等人的时间不计入 {@code app.ai.resilience.total-budget}。</li>
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
	 * <p>人机预算分离（用户裁决 2026-10-20 第 3 条）：确认门 / 前端工具等待人的那段时间
	 * <b>不计入</b> {@code app.ai.resilience.total-budget}。挂起等待是"人在想"，不是"上游不动"，
	 * 用墙钟把它算进轮次预算会让"等人"与"模型/工具慢"被同一个 300s 掐死。
	 * {@link StreamWatchdog} 与 {@code ResilientChatService} 的总预算判据都按
	 * {@link #gateWaitMs(String)} 扣除。
	 */
	private final ConcurrentMap<String, AtomicLong> gateWaitFinishedMs = new ConcurrentHashMap<>();

	/** runId → 当前挂起等待的起点（毫秒；0 = 当前不在等待）。 */
	private final ConcurrentMap<String, AtomicLong> gateWaitSinceMs = new ConcurrentHashMap<>();

	private final Map<String, Map<String, Object>> lastFinished = new ConcurrentHashMap<>();

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

	/** 退出挂起等待：把本段等待时长并进累计量（幂等；未在等待时调用无副作用）。 */
	public void endGateWait(String runId) {
		AtomicLong since = this.gateWaitSinceMs.get(runId);
		if (since == null) {
			return;
		}
		long start = since.getAndSet(0L);
		if (start > 0) {
			long waited = Math.max(0L, System.currentTimeMillis() - start);
			this.gateWaitFinishedMs.computeIfAbsent(runId, key -> new AtomicLong()).addAndGet(waited);
		}
	}

	/**
	 * 该轮累计的挂起等待时长（毫秒，<b>含</b>当前正在进行的等待）—— 总预算判据的扣除量。
	 *
	 * <p>含正在进行的等待是刻意的：看门狗每拍都要判预算，若只扣"已结束的窗口"，一次长达
	 * 十几分钟的表单填写会在整个等待期里被反复算成"预算已耗尽"。
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

	/** 轮终态清理（进程内状态随轮结束释放）。 */
	public void clear(String runId) {
		this.active.remove(runId);
		this.started.remove(runId);
		this.lastFinished.remove(runId);
		this.gateWaitFinishedMs.remove(runId);
		this.gateWaitSinceMs.remove(runId);
		// lastActivityAtMs 故意保留：重试判定要看退出时刻（TTL 由轮的生命周期兜住）
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
