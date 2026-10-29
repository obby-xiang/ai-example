package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.observe.AiTrace;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
 *
 * <h2>运行期低频死轮扫描（批次8B / Task 12 Step 3）</h2>
 * 启动扫描每个实例只跑一拍，之后"死在调用中间 / 决策已落库却没人执行 / 重挂自愈那一下抖了"的轮次
 * 会一直挂着。{@link #sweep()} 按 {@code app.ai.resume.runtime-sweep-interval} 补一拍兜底：
 * 候选仍是 {@link ResumeService#listRuns()}，活/死仍是 {@link RunLiveness#isDead}（租约 + 本进程活订阅 +
 * 业务活动锚点），判死窗口仍是 {@code zombie-age}（180s，<b>不放宽</b>）；终态不复活、仍 {@code PENDING}
 * 的外部项不碰。定时器由 {@code app.ai.resume.runtime-sweep-enabled} 控制，应用停机经
 * {@code @PreDestroy} 关闭（同一入口 {@link #stopRuntimeSweep()}）。
 */
@Slf4j
@Component
public class StartupResumeRunner {

	private final ResumeService resumeService;

	private final RunStore store;

	private final AiProperties properties;

	private final AiAvailability availability;

	/**
	 * 本进程的写出器登记（可选）：只用于运行期扫描判"这一轮在本进程还有没有真实订阅者"。
	 *
	 * <p>非 Spring 装配（单测直接 {@code new}）没有它 ⇒ 该路证据按"无订阅者"处理；生产装配一定注入。
	 * 没有它也不新增第二套活度逻辑 —— 租约与业务活动锚点仍是主判据（见 {@link RunLiveness}）。
	 */
	private final RunRegistry registry;

	private final AtomicBoolean scanned = new AtomicBoolean();

	private volatile Map<String, Object> lastScan = Map.of("scanned", false);

	/** 最近一次运行期扫描的结果（观测面；不改变业务语义）。 */
	private volatile Map<String, Object> lastSweep = Map.of("swept", false);

	private final Object sweepLock = new Object();

	private volatile ScheduledExecutorService sweepExecutor;

	private volatile ScheduledFuture<?> sweepFuture;

	public StartupResumeRunner(ResumeService resumeService, RunStore store, AiProperties properties,
			AiAvailability availability) {
		this(resumeService, store, properties, availability, null);
	}

	@Autowired
	public StartupResumeRunner(ResumeService resumeService, RunStore store, AiProperties properties,
			AiAvailability availability, RunRegistry registry) {
		this.resumeService = resumeService;
		this.store = store;
		this.properties = properties;
		this.availability = availability;
		this.registry = registry;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onReady() {
		// 运行期低频扫描与启动扫描各自独立开关：先注册定时器，再走启动那一拍。
		startRuntimeSweep();
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
			Discovery discovery = discoveryOf(run);
			if (discovery != Discovery.TAKEABLE) {
				Map<String, Object> skippedItem = new LinkedHashMap<>();
				skippedItem.put("runId", runId);
				skippedItem.put("status", run.get("status"));
				skippedItem.put("reason", scanSkipReason(discovery));
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
				ResumeService.ResumeResult result = resumeWithRunIdentity(runId, zombie);
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
	 * <b>运行期低频死轮扫描</b>（批次8B / Task 12 Step 3）：把"没有驾驶方、但仍有活要干"的轮次交给
	 * 与启动扫描<b>同一条</b>续跑路径。
	 *
	 * <h2>为什么需要它（启动扫描的一次性盲区）</h2>
	 * 启动扫描每个实例只跑一拍；此后"进程死在模型调用中间"、"决策落库了但派发窗口整段错过"、
	 * "重挂自愈那一刻恰好 Redis 抖了一下"都会留下<b>无人推进</b>的轮次，而快照仍是
	 * {@code RUNNING}/{@code SUSPENDED}。用户看到的是"卡住了"，只有重启或人工 {@code POST
	 * /api/ai/startup-resume/scan} 才能收敛。本方法就是这条兜底的周期拍。
	 *
	 * <h2>判据只有一套（不新增第二套活度逻辑）</h2>
	 * <ul>
	 * <li><b>候选</b> = {@link ResumeService#listRuns()}：与启动扫描、运维端点同一份"发现态"，
	 * 其中 {@code cancelled} / {@code unresolvedExternal} / {@code zombieRunning} 已按
	 * {@code app.ai.resume.zombie-age}（180s）与<b>业务</b>活动锚点算好；</li>
	 * <li><b>活/死</b> = {@link RunLiveness#isDead}：会话门放行（{@code AiController#isDeadRound}）
	 * 与重挂自愈用的是同一个函数 —— 租约在别人手里、本进程还有真实订阅者、业务活动仍在窗口内，
	 * 任一成立即判活 ⇒ <b>活跃不接管</b>；</li>
	 * <li><b>窗口</b> = {@code app.ai.resume.zombie-age} 原值：本方法<b>不</b>引入更短的判死窗口，
	 * 也<b>不</b>把心跳算成业务活动（心跳只证明进程活着）。</li>
	 * </ul>
	 *
	 * <h2>三条不再扩大语义的边界</h2>
	 * <ol>
	 * <li><b>终态不复活</b>：{@code DONE/FAILED/CANCELLED} 在候选过滤就跳过（失败现场、完成事实不改写）；</li>
	 * <li><b>不绕开人审</b>：仍有 {@code PENDING} 外部项（确认门/前端工具）一律跳过，与启动扫描同义；</li>
	 * <li><b>挂起已决、无驱动才接管</b>：{@code SUSPENDED} 且条目全结算、活动超龄 ⇒ 按非僵尸续跑
	 * （{@code forceTakeover=false}）；{@code RUNNING} 超龄 ⇒ 与启动扫描一样带 {@code forceTakeover=true}
	 * 走僵尸接管。两条都落在 {@link ResumeService#resume(String, boolean)}：租约、会话门、
	 * {@code resuming} 互斥、取消优先、终态判定全部由那一条漏斗负责 —— 已有驾驶方时只会得到
	 * {@code ALREADY_RUNNING} / {@code LEASE_HELD_ELSEWHERE} / {@code SESSION_BUSY} 的跳过。</li>
	 * </ol>
	 *
	 * <p>
	 * 本方法本身不判"该不该开定时器"（开关只管注册，见 {@link #startRuntimeSweep()}），
	 * 因此单测/运维可以直接同步驱动一拍，与 {@code SessionPurgeRetryRunner#runOnce()} 同姿态。
	 */
	public Map<String, Object> sweep() {
		if (!this.availability.isAvailable()) {
			Map<String, Object> skippedReport = unavailableSweep();
			this.lastSweep = skippedReport;
			log.info("运行期死轮扫描跳过：AI 不可用（{}）", this.availability.code());
			return skippedReport;
		}
		List<Map<String, Object>> candidates;
		try {
			candidates = this.resumeService.listRuns();
		}
		catch (Exception ex) {
			// 与启动扫描同一姿态：Redis 不可用时"响亮地失败"并留事实，不把异常丢给调度线程。
			log.warn("运行期死轮扫描失败（Redis 不可用？）：{}", ex.getMessage());
			Map<String, Object> failed = new LinkedHashMap<>();
			failed.put("swept", true);
			failed.put("at", Instant.now().toString());
			failed.put("instanceId", this.store.instanceId());
			failed.put("error", String.valueOf(ex.getMessage()));
			failed.put("resumed", List.of());
			failed.put("skipped", List.of());
			this.lastSweep = failed;
			return failed;
		}
		long nowMs = System.currentTimeMillis();
		long zombieAgeMs = this.properties.getResume().getZombieAge().toMillis();
		// 上限与启动扫描共用 max-on-startup：一次扫描最多受理这么多轮，避免"挂起池被一拍占满"
		int limit = Math.max(1, this.properties.getResume().getMaxOnStartup());
		List<Map<String, Object>> resumed = new ArrayList<>();
		List<Map<String, Object>> skipped = new ArrayList<>();
		for (Map<String, Object> run : candidates) {
			String runId = String.valueOf(run.get("runId"));
			String status = run.get("status") == null ? null : String.valueOf(run.get("status"));
			Discovery discovery = discoveryOf(run);
			if (discovery != Discovery.TAKEABLE) {
				skipped.add(sweepSkipItem(runId, status, sweepSkipReason(discovery)));
				continue;
			}
			if (!deadWithoutDriver(run, status, nowMs, zombieAgeMs)) {
				// 租约/活订阅/活动窗口三者都说"还有人管" ⇒ 这一拍不碰（下一拍再看）。
				skipped.add(sweepSkipItem(runId, status, "ACTIVE"));
				continue;
			}
			if (resumed.size() >= limit) {
				skipped.add(sweepSkipItem(runId, status, "MAX_PER_SWEEP_REACHED"));
				continue;
			}
			boolean takeover = RunSnapshot.RUNNING.equals(status);
			AiTrace.bind(runId, null);
			try {
				ResumeService.ResumeResult result = resumeWithRunIdentity(runId, takeover);
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("runId", runId);
				item.put("statusBeforeSweep", status);
				item.put("zombieTakeover", takeover);
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
				log.error("运行期死轮扫描续跑异常 runId={}", runId, ex);
			}
			finally {
				AiTrace.clear();
			}
		}
		Map<String, Object> report = new LinkedHashMap<>();
		report.put("swept", true);
		report.put("at", Instant.now().toString());
		report.put("instanceId", this.store.instanceId());
		report.put("candidates", candidates.size());
		report.put("resumed", resumed);
		report.put("skipped", skipped);
		report.put("zombieAgeMs", zombieAgeMs);
		this.lastSweep = report;
		log.info("运行期死轮扫描完成：候选 {} 轮，受理 {} 轮，跳过 {} 轮（判死窗口 {}）",
				candidates.size(), resumed.size(), skipped.size(), this.properties.getResume().getZombieAge());
		return report;
	}

	/** 最近一次运行期扫描的结果（观测面；未开开关时保持 {@code swept=false} 的初值）。 */
	public Map<String, Object> lastSweep() {
		return this.lastSweep;
	}

	/**
	 * 注册运行期扫描定时器（{@code app.ai.resume.runtime-sweep-enabled}）。
	 *
	 * <p>缺省关闭（代码缺省 {@code false}，出厂 yml 打开，与 {@code on-startup} 同一纪律）；
	 * 关闭时<b>不建线程</b>。周期非法（{@code <= 0}）时只 ERROR 留痕、不注册 ——
	 * {@code scheduleWithFixedDelay} 对非正值会抛 {@link IllegalArgumentException}，
	 * 不能让一个配错的间隔把启动路径带崩。
	 */
	public void startRuntimeSweep() {
		if (!this.properties.getResume().isRuntimeSweepEnabled()) {
			log.info("运行期死轮扫描未注册（app.ai.resume.runtime-sweep-enabled=false）："
					+ "启动扫描与重挂自愈照常");
			return;
		}
		Duration interval = this.properties.getResume().getRuntimeSweepInterval();
		if (interval == null || interval.isZero() || interval.isNegative()) {
			log.error("运行期死轮扫描未注册：app.ai.resume.runtime-sweep-interval 必须 > 0（当前 = {}）", interval);
			return;
		}
		synchronized (this.sweepLock) {
			if (this.sweepFuture != null && !this.sweepFuture.isDone()) {
				return;
			}
			ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
				Thread thread = new Thread(runnable, "ai-runtime-sweep");
				thread.setDaemon(true);
				return thread;
			});
			this.sweepExecutor = executor;
			this.sweepFuture = executor.scheduleWithFixedDelay(this::sweepQuietly, interval.toMillis(),
					interval.toMillis(), TimeUnit.MILLISECONDS);
		}
		log.info("运行期死轮扫描已注册：interval={} zombie-age={}（阈值不放宽）", interval,
				this.properties.getResume().getZombieAge());
	}

	/** 停止运行期扫描定时器；应用停机由 {@code @PreDestroy} 走同一入口（幂等）。 */
	@PreDestroy
	public void stopRuntimeSweep() {
		ScheduledFuture<?> future;
		ScheduledExecutorService executor;
		synchronized (this.sweepLock) {
			future = this.sweepFuture;
			executor = this.sweepExecutor;
			this.sweepFuture = null;
			this.sweepExecutor = null;
		}
		if (future != null) {
			future.cancel(false);
		}
		if (executor != null) {
			executor.shutdownNow();
		}
	}

	/** 运行期扫描定时器是否在跑（观测/单测；停机后必为 false）。 */
	public boolean isRuntimeSweepRunning() {
		ScheduledFuture<?> future = this.sweepFuture;
		return future != null && !future.isDone() && !future.isCancelled();
	}

	/**
	 * 以<b>该轮快照的身份</b>执行一次续跑（启动扫描与运行期扫描的唯一入口）。
	 *
	 * <p>扫描跑在定时线程上，那里没有请求身份；身份也不能从"当前线程"或默认租户补 ——
	 * 唯一来源是该轮 {@code RunSnapshot} 的 {@code tenantId/userId}。旧快照（0/0）没有身份可恢复：
	 * {@link RunIdentityScope#open(long, long)} 返回空作用域，续跑入口按既有 fail-closed 口径处理。
	 *
	 * <p>作用域只包住这一次续跑调用，扫描循环（下一轮可能是另一个租户）不继承身份。
	 */
	private ResumeService.ResumeResult resumeWithRunIdentity(String runId, boolean takeover) {
		RunSnapshot snapshot = this.store.get(runId);
		long tenantId = snapshot == null ? 0L : snapshot.getTenantId();
		long userId = snapshot == null ? 0L : snapshot.getUserId();
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(tenantId, userId)) {
			return this.resumeService.resume(runId, takeover);
		}
	}

	/** 调度线程绝不能被单轮异常杀死：异常只留痕，下一拍照常。 */
	private void sweepQuietly() {
		try {
			sweep();
		}
		catch (Throwable ex) {
			log.warn("运行期死轮扫描本轮异常（不猜状态，下一拍重试）：{}", ex.toString());
		}
	}

	/**
	 * 这一轮此刻<b>有没有人管</b>（运行期扫描的活度判据；本体仍是 {@link RunLiveness}）。
	 *
	 * <p>三路证据与会话门放行（{@code AiController#isDeadRound}）、重挂自愈同源：
	 * 租约（跨实例"还有人负责"）、本进程真实订阅（活连接；空写出器不算）、业务活动锚点
	 * （{@code listRuns} 已按状态取好口径）。任一为真即判活 —— 宁可晚一拍接管，也不让同一轮双执行。
	 *
	 * <p>租约读不出来时按"可能有人持有"处理（fail-closed）：这里的错误方向是并发双跑，
	 * 不能靠乐观兜底。
	 */
	private boolean deadWithoutDriver(Map<String, Object> run, String status, long nowMs, long zombieAgeMs) {
		String runId = String.valueOf(run.get("runId"));
		boolean leaseHeld;
		try {
			LeaseService leases = this.store.leaseService();
			leaseHeld = leases != null && leases.isHeld(this.store.runLeaseKey(runId));
		}
		catch (Exception ex) {
			log.debug("运行期扫描读取续跑租约失败 runId={}（按活跃处理，不接管）：{}", runId, ex.getMessage());
			leaseHeld = true;
		}
		boolean liveInProcess;
		try {
			liveInProcess = this.registry != null && this.registry.hasLiveSubscriber(runId);
		}
		catch (Exception ex) {
			log.debug("运行期扫描读取本进程订阅失败 runId={}（按活跃处理，不接管）：{}", runId, ex.getMessage());
			liveInProcess = true;
		}
		long updatedAtMs = asLong(run.get("updatedAtMs"));
		// listRuns 的 activeAtMs = max(快照写入, 最后一条非心跳业务帧) —— 与 RunLiveness 的第 2/3 参同义
		long lastActivityAtMs = asLong(run.get("activeAtMs"));
		return RunLiveness.isDead(status, updatedAtMs, lastActivityAtMs, leaseHeld, liveInProcess, zombieAgeMs,
				nowMs);
	}

	/**
	 * 发现态条目的接管结论（<b>启动扫描与运行期扫描共用</b>）：{@link ResumeService#listRuns()}
	 * 已经把"取消 / 终态 / 未决外部输入 / 僵尸 RUNNING"算成字段，这里只做一次归类，
	 * 避免两条入口各写一套 if 链而慢慢走偏。
	 */
	private enum Discovery {

		/** 可以交给续跑漏斗（是否真的驱动仍由租约/会话门/待决条目在漏斗里决定）。 */
		TAKEABLE,

		/** 取消旗标（Redis 为准）：已取消的轮不得被任何扫描拾起。 */
		CANCELLED,

		/** 仍有 PENDING 的外部项（确认门/前端工具）：自动补执行不能代替人决策。 */
		PENDING_UNRESOLVED,

		/** 终态（DONE/FAILED/CANCELLED，含 REJECTED 归一）：不复活、不改写。 */
		TERMINAL,

		/** RUNNING 且业务活动仍在僵尸窗口内：可能是活轮，不接管。 */
		RUNNING_ACTIVE

	}

	private static Discovery discoveryOf(Map<String, Object> run) {
		if (Boolean.TRUE.equals(run.get("cancelled"))) {
			return Discovery.CANCELLED;
		}
		Object unresolved = run.get("unresolvedExternal");
		if (unresolved instanceof List<?> list && !list.isEmpty()) {
			return Discovery.PENDING_UNRESOLVED;
		}
		Object rawStatus = run.get("status");
		String status = rawStatus == null ? null : String.valueOf(rawStatus);
		if (status == null || RunSnapshot.isTerminal(status)) {
			return Discovery.TERMINAL;
		}
		if (RunSnapshot.RUNNING.equals(status) && !Boolean.TRUE.equals(run.get("zombieRunning"))) {
			return Discovery.RUNNING_ACTIVE;
		}
		return Discovery.TAKEABLE;
	}

	/** 启动扫描的跳过原因（对外口径保持不变：{@code TERMINAL_OR_RUNNING} 仍是活/终态合称）。 */
	private static String scanSkipReason(Discovery discovery) {
		return switch (discovery) {
			case CANCELLED -> "CANCELLED";
			case PENDING_UNRESOLVED -> "PENDING_UNRESOLVED";
			case TAKEABLE -> null;
			default -> "TERMINAL_OR_RUNNING";
		};
	}

	/** 运行期扫描的跳过原因（比启动扫描多一个 {@code ACTIVE}，让"没接管"可解释）。 */
	private static String sweepSkipReason(Discovery discovery) {
		return switch (discovery) {
			case CANCELLED -> "CANCELLED";
			case PENDING_UNRESOLVED -> "PENDING_UNRESOLVED";
			case TERMINAL -> "TERMINAL";
			case RUNNING_ACTIVE -> "ACTIVE";
			case TAKEABLE -> null;
		};
	}

	private static Map<String, Object> sweepSkipItem(String runId, String status, String reason) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("runId", runId);
		item.put("status", status);
		item.put("reason", reason);
		return item;
	}

	private static long asLong(Object value) {
		return value instanceof Number number ? number.longValue() : 0L;
	}

	/**
	 * "AI 不可用所以不扫"的扫描事实（与启动扫描的 {@code scanned=false, reason=AI_UNAVAILABLE} 同形，
	 * 这里对应 {@code swept=false}）。
	 */
	private Map<String, Object> unavailableSweep() {
		Map<String, Object> report = new LinkedHashMap<>();
		report.put("swept", false);
		report.put("reason", this.availability.code());
		report.put("message", this.availability.reason());
		report.put("instanceId", this.store.instanceId());
		return report;
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
