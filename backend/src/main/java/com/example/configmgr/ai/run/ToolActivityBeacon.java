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
