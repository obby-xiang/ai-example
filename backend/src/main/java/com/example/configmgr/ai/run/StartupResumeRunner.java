package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.observe.AiTrace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 启动自动续跑（S4.2 规格 §3 第 6 步的自动化形态；N3/N8 的落地点）。
 *
 * <h2>为什么必须有它（M3 的完整闭环）</h2>
 * 挂起态外置之后，"进程死亡"不再是数据丢失，而是"这一轮的进度留在 Redis 里等下一棒"。
 * 但外置只是<b>可恢复</b>；要让"用户以为还在跑"的轮次真的跑完，得有人在启动时把它捡起来 ——
 * 本类就是那个人：应用就绪 → 延迟 {@code app.ai.resume.startup-delay} → 扫
 * {@code ai:runs} 索引 → 对每一轮按 {@code ResumeService.listRuns()} 的口径判定是否可续跑 →
 * 调 {@link ResumeService#resume(String, boolean)}。
 *
 * <h2>三条安全边界（不绕开人审）</h2>
 * <ol>
 * <li><b>开关</b>：{@code app.ai.resume.on-startup}（默认 false；本仓 yml 打开）+ 必须有 AI key
 * （无 key 时模型侧 bean 不存在，本类不装配）；</li>
 * <li><b>只续"已有人类结论/无外部输入需求"的轮次</b>：仍 {@code PENDING} 的确认门/前端工具一律
 * 跳过（{@code PENDING_UNRESOLVED}）—— 自动补执行的前提永远是"人已经在挂起当时给过决策"
 * （{@code APPROVED}）或"本来就是后端工具"，因此自动续跑不会代替人做危险操作的批准；</li>
 * <li><b>僵尸轮（N3）显式接管</b>：状态停在 {@code RUNNING} 且<b>最后一条非心跳业务帧</b>超过
 * {@code app.ai.resume.zombie-age} 的轮次判为上一进程的遗体（心跳不算业务活动），才允许带
 * {@code forceTakeover=true} 续跑；否则保持"RUNNING 一律 409"的保守语义。</li>
 * </ol>
 *
 * <p>
 * 扫描结果留在 {@link #lastScan()} 里（供 {@code GET /api/ai/startup-resume} 与证据文档读取），
 * 不静默：跳过的每一轮都带 reason。
 */
@Slf4j
@Component
public class StartupResumeRunner {

	private final ResumeService resumeService;

	private final RunStore store;

	private final AiProperties properties;

	private final AiAvailability availability;

	private final AtomicBoolean scanned = new AtomicBoolean();

	private volatile Map<String, Object> lastScan = Map.of("scanned", false);

	public StartupResumeRunner(ResumeService resumeService, RunStore store, AiProperties properties,
			AiAvailability availability) {
		this.resumeService = resumeService;
		this.store = store;
		this.properties = properties;
		this.availability = availability;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onReady() {
		if (!this.properties.getResume().isOnStartup()) {
			log.info("启动自动续跑未开启（app.ai.resume.on-startup=false），只做发现：GET /api/ai/runs");
			this.lastScan = Map.of("scanned", false, "reason", "app.ai.resume.on-startup=false",
					"instanceId", this.store.instanceId());
			return;
		}
		if (!this.availability.isAvailable()) {
			// 模型配置专项批①（设计稿 §3.4）：本类改为无条件装配，所以"没有可用 provider"必须
			// 在这里自检并跳过 —— 绝不能因"无 key"在启动扫描里报错（旧行为 = bean 压根不存在）。
			this.lastScan = unavailableScan();
			log.info("启动自动续跑跳过：AI 不可用（{}）", this.availability.code());
			return;
		}
		Duration delay = this.properties.getResume().getStartupDelay();
		Thread starter = new Thread(() -> {
			try {
				if (!delay.isZero() && !delay.isNegative()) {
					Thread.sleep(delay.toMillis());
				}
				scan(false);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			catch (Throwable ex) {
				log.error("启动自动续跑未捕获异常（已降级为不自动续跑）：{}", ex.getMessage(), ex);
			}
		}, "ai-startup-resume");
		starter.setDaemon(true);
		starter.start();
	}

	/** 扫描并续跑（同一实例只自动跑一次；运维端点可强制重扫）。 */
	public Map<String, Object> scan(boolean force) {
		if (!force && !this.scanned.compareAndSet(false, true)) {
			return this.lastScan;
		}
		this.scanned.set(true);
		if (!this.availability.isAvailable()) {
			// 强制重扫（运维端点）同样自检：不可用时不扫、不报错，只留事实
			this.lastScan = unavailableScan();
			log.info("启动自动续跑扫描跳过：AI 不可用（{}）", this.availability.code());
			return this.lastScan;
		}
		List<Map<String, Object>> candidates;
		try {
			candidates = this.resumeService.listRuns();
		}
		catch (Exception ex) {
			// Redis 不可用时（Q2/§13.1 降级场景）扫描必须"响亮地失败"，而不是让启动线程抛异常：
			// 业务与 AI 端点各自有自己的降级路径，这里只记事实。
			log.warn("启动自动续跑扫描失败（Redis 不可用？）：{}", ex.getMessage());
			Map<String, Object> failed = new LinkedHashMap<>();
			failed.put("scanned", true);
			failed.put("at", Instant.now().toString());
			failed.put("instanceId", this.store.instanceId());
			failed.put("error", String.valueOf(ex.getMessage()));
			failed.put("resumed", List.of());
			failed.put("skipped", List.of());
			this.lastScan = failed;
			return failed;
		}
		List<Map<String, Object>> resumed = new ArrayList<>();
		List<Map<String, Object>> skipped = new ArrayList<>();
		int limit = Math.max(1, this.properties.getResume().getMaxOnStartup());
		for (Map<String, Object> run : candidates) {
			String runId = String.valueOf(run.get("runId"));
			boolean zombie = Boolean.TRUE.equals(run.get("zombieRunning"));
			boolean resumeable = Boolean.TRUE.equals(run.get("resumeable"));
			if (!resumeable) {
				Map<String, Object> skippedItem = new LinkedHashMap<>();
				skippedItem.put("runId", runId);
				skippedItem.put("status", run.get("status"));
				skippedItem.put("reason", Boolean.TRUE.equals(run.get("cancelled")) ? "CANCELLED"
						: (!((List<?>) run.getOrDefault("unresolvedExternal", List.of())).isEmpty()
								? "PENDING_UNRESOLVED" : "TERMINAL_OR_RUNNING"));
				skippedItem.put("unresolvedExternal", run.get("unresolvedExternal"));
				skipped.add(skippedItem);
				continue;
			}
			if (resumed.size() >= limit) {
				Map<String, Object> skippedItem = new LinkedHashMap<>();
				skippedItem.put("runId", runId);
				skippedItem.put("reason", "MAX_ON_STARTUP_REACHED");
				skipped.add(skippedItem);
				continue;
			}
			// 日志链路批：启动扫描是"没有 HTTP 入口"的续跑路径，锚点只能在循环里逐轮装
			AiTrace.bind(runId, null);
			try {
				ResumeService.ResumeResult result = this.resumeService.resume(runId, zombie);
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("runId", runId);
				item.put("zombieTakeover", zombie);
				item.put("outcome", result.outcome().name());
				item.put("body", result.body());
				resumed.add(item);
			}
			catch (Exception ex) {
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("runId", runId);
				item.put("outcome", "EXCEPTION");
				item.put("error", String.valueOf(ex.getMessage()));
				resumed.add(item);
				log.error("启动自动续跑异常 runId={}", runId, ex);
			}
			finally {
				AiTrace.clear();
			}
		}
		Map<String, Object> report = new LinkedHashMap<>();
		report.put("scanned", true);
		report.put("at", Instant.now().toString());
		report.put("instanceId", this.store.instanceId());
		report.put("candidates", candidates.size());
		report.put("resumed", resumed);
		report.put("skipped", skipped);
		report.put("onStartup", this.properties.getResume().isOnStartup());
		report.put("zombieAge", String.valueOf(this.properties.getResume().getZombieAge()));
		this.lastScan = report;
		log.info("启动自动续跑完成：候选 {} 轮，受理 {} 轮，跳过 {} 轮（详情见 GET /api/ai/startup-resume）",
				candidates.size(), resumed.size(), skipped.size());
		return report;
	}

	public Map<String, Object> lastScan() {
		return this.lastScan;
	}

	/**
	 * "AI 不可用所以不续跑"的扫描事实（与 {@code GET /api/ai/startup-resume} 的 {
	 * {@code scanned=false, reason=AI_UNAVAILABLE}} 同形；批① 起 runner 恒存在，故该分支只能由本类给出）。
	 */
	private Map<String, Object> unavailableScan() {
		Map<String, Object> report = new LinkedHashMap<>();
		report.put("scanned", false);
		report.put("reason", this.availability.code());
		report.put("message", this.availability.reason());
		report.put("instanceId", this.store.instanceId());
		return report;
	}

}
