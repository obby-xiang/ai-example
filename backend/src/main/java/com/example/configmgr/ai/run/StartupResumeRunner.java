package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.observe.AiTrace;
import com.example.configmgr.ai.observe.LogSampling;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.RunKeyScope;
import com.example.configmgr.ai.tenant.TenantUser;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 启动自动续跑（S4.2 规格 §3 第 6 步的自动化形态；N3/N8 的落地点）。
 *
 * <h2>为什么必须有它（M3 的完整闭环）</h2>
 * 挂起态外置之后，"进程死亡"不再是数据丢失，而是"这一轮的进度留在 Redis 里等下一棒"。
 * 但外置只是<b>可恢复</b>；要让"用户以为还在跑"的轮次真的跑完，得有人在启动时把它捡起来 ——
 * 本类就是那个人：应用就绪 → 延迟 {@code app.ai.resume.startup-delay} + 有界抖动 → 扫
 * {@code ai:recovery:due} 索引 → 对每一轮按 {@link ResumeService#listRunsForRecovery()} 的口径判定是否可续跑 →
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
 * <h2>两个面：系统扫描 vs 请求面手工扫描</h2>
 * <ul>
 * <li><b>系统面</b>（应用启动的自动扫描、运行期扫描）：跑在无身份线程上，候选来自
 * {@code ai:recovery:due} 的复合成员，逐轮按<b>该轮记录身份</b>推进 —— 不扫描租户列表、也不沿用线程身份。
 * 发现面只产出带身份的复合成员（{@code ResumeService.listRunsForRecovery()}），但入口仍对
 * {@code 0/0} 记录做硬校验：身份不可用一律记 {@link #LEGACY_NO_IDENTITY}、不驱动模型/工具。</li>
 * <li><b>请求面</b>（{@code POST /api/ai/startup-resume/scan}，线程有身份）：<b>只处理当前身份</b>的轮次。
 * 若候选里混入别的身份（列表实现变化、桩数据等），一律<b>既不续跑、也不进报告</b> —— runId 本身
 * 就是跨身份信息，不能借"跳过原因"回显。报告面（GET）同样按调用身份切片，
 * 见 {@link #lastScanForIdentity(TenantUser)}。</li>
 * </ul>
 *
 * <p>启动/手工（{@link #scan(boolean)}）与运行期（{@link #sweep()}）从候选枚举开始共用
 * {@link #runPipeline}：身份门、0/0 硬校验、预算与发现态分类只实现一次；运行期只是多出
 * 取消收尾/到期收敛两条旁路和租约判死，报告字段仍按各自契约组装。
 *
 * <h2>扫描成本：轻量投影 + 单飞 + 轮转（审计 S4 / F-03 的落地点）</h2>
 * <ul>
 * <li><b>发现面只读恢复所需字段</b>：{@link ResumeService#listRunsForRecovery()} 不读
 * {@code ledgerCounts}（旧数据回落整份台账，正常只读小型元数据投影）、{@code messageCount}、
 * 心跳键与逐条 {@code pending} 载荷，
 * 但 {@code pending/取消/状态/活动锚点} 这些安全判据一个不少；API 详情仍走
 * {@link ResumeService#listRuns()}；</li>
 * <li><b>单飞</b>：启动/运行期/手工共用一套（{@link #tryStartScan}）—— 进程内门 +
 * 跨实例租约（{@code ai:lock:resume-scan}，续租 TTL 与抢锁 TTL 同取 {@code scan-lease-ttl}，
 * owner 每次扫描唯一，收尾先停续租再归还）。系统面全局一把，手工扫描按调用身份分；
 * 抢不到就在有界等待后让位（报告字段契约不变，标 {@code deferred=true}）；</li>
 * <li><b>轮转 + 尝试预算</b>：到期顺序 + 有界预算下，每拍从"上一拍最后尝试过的候选之后"开始
 * （{@link #scanCursors}，条数有界，见 {@link #MAX_SCAN_CURSORS}），尝试次数（含失败）另有上界
 * （{@code app.ai.resume.scan-max-attempts}）。
 * 于是"排在前面的候选每拍失败"不会让后面的轮次永久饿死，"每轮都试一次但都不成"也不会让单拍无界。</li>
 * </ul>
 *
 * <h2>运行期低频死轮扫描（批次8B / Task 12 Step 3）</h2>
 * 启动扫描每个实例只跑一拍，之后"死在调用中间 / 决策已落库却没人执行 / 重挂自愈那一下抖了"的轮次
 * 会一直挂着。{@link #sweep()} 按 {@code app.ai.resume.runtime-sweep-interval} 补一拍兜底：
 * 候选仍是 {@link ResumeService#listRunsForRecovery()}，活/死仍是 {@link RunLiveness#isDead}（租约 + 本进程活订阅 +
 * 业务活动锚点），判死窗口仍是 {@code zombie-age}（180s，<b>不放宽</b>）；终态不复活、仍 {@code PENDING}
 * 的外部项不碰。定时器由 {@code app.ai.resume.runtime-sweep-enabled} 控制，应用停机经
 * {@code @PreDestroy} 关闭（同一入口 {@link #stopRuntimeSweep()}）。
 *
 * <p><b>两路非续跑的收敛</b>（F3/F2）：①<b>取消旗标存在 + 快照非终态</b> ⇒ 不跳过、也不续跑，
 * 走 {@link ResumeService#cancelConfirmed(String)} 落 CANCELLED 终态/终帧（驱动进程已死时这是唯一
 * 会消费该旗标的执行体）；②<b>park 到期</b> ⇒ 调 {@code ConfirmGate#convergeExpiredParkedFrontend}
 * 消费持久化的 {@code expiresAt}（任实例可做，决策 guard 保证同一张卡只赢一次）。两者都不驱动模型。
 */
@Slf4j
@Component
public class StartupResumeRunner {

	/**
	 * 系统扫描对<b>没有可用身份</b>的轮次的明确跳过原因（{@code tenantId<=0} 或
	 * {@code userId<=0 且 != -1}，即 {@code 0/0} 记录）。
	 *
	 * <p>当前发现面（{@code ResumeService.listRunsForRecovery()}）只产出带身份的复合成员，因此正常数据
	 * 不会落到本分支；但入口仍<b>硬校验</b>：身份不可用一律不驱动模型/工具，不伪造归属、
	 * 也不沿用扫描线程身份。防线不因"当前不产出"而删除。
	 */
	public static final String LEGACY_NO_IDENTITY = "LEGACY_NO_IDENTITY";

	/**
	 * 系统面扫描单飞键（无 tag 资源锁）：启动扫描与运行期兜底共用一把。
	 *
	 * <p>为什么是"被保护的对象只有扫描"而不是每轮：真正的双驱动护栏仍是每轮的续跑权租约
	 * （{@code ai:lease:run:*}）+ 会话门；本锁只解决"N 个实例同拍把同一批候选各扫一遍、
	 * 各算一份 {@code max-on-startup}"的启动风暴（审计 F-03）。因此它<b>不参与</b>任何
	 * 取消/租约/fencing 判定，也不改变单轮准入。
	 */
	static final String SCAN_LEASE_KEY = LeaseKeys.LOCK_PREFIX + "resume-scan";

	/**
	 * 请求面（手工扫描）的单飞键前缀：按<b>调用身份</b>记。
	 *
	 * <p>手工扫描只处理当前身份（见 {@link #runPipeline}），因此它的单飞也按身份分：既不会被
	 * 别人的手工扫描挡住，也不会因为一次用户点按把全租户的系统扫描挡住。
	 */
	static final String SCAN_LEASE_IDENTITY_PREFIX = LeaseKeys.LOCK_PREFIX + "resume-scan:";

	/** 抢扫描单飞时最多等多久（有界：启动/手工都不该被另一实例的长扫描拖住）。 */
	private static final long SCAN_LEASE_WAIT_MS = 1_500L;

	/** 重试抢单飞的间隔（下界；实际再加同等量级的抖动，避免两实例同拍再撞）。 */
	private static final long SCAN_LEASE_RETRY_MS = 100L;

	/** 启动抖动上界的硬顶（配置写多大都不会把启动扫描无限期推迟）。 */
	private static final long MAX_STARTUP_JITTER_MS = 60_000L;

	/**
	 * 启动拍最多尝试几次单飞（含第一次）：命中"手工扫描正占着进程内门"的短窗口时，
	 * 让位重试比永久放弃这一拍更合适；总等待仍是有界的（每次重试之间 {@link #STARTUP_SCAN_RETRY_MS} 起）。
	 */
	private static final int STARTUP_SCAN_ATTEMPTS = 3;

	/** 启动拍让位重试的基准间隔（实际再加同等量级的抖动）。 */
	private static final long STARTUP_SCAN_RETRY_MS = 500L;

	/** 单飞没抢到时报告的 {@code reason}（字段契约不变，只额外标注这一事实）。 */
	static final String SCAN_IN_FLIGHT = "SCAN_IN_FLIGHT";

	/** 尝试预算用尽时的跳过原因（与产出预算的 {@code MAX_*} 分开，让"没轮到"可解释）。 */
	static final String ATTEMPT_BUDGET_REACHED = "SCAN_ATTEMPT_BUDGET_REACHED";

	/**
	 * 轮转游标的上限（有界）：{@link #scanCursors} 的键 = 模式 × 触发面，系统面固定 2 条，
	 * 请求面随"在本实例点过手工扫描的身份数"增长。达到上限时<b>只淘汰请求面</b>游标（见
	 * {@link #rememberCursor}）：系统面的轮转起点不能被清掉，否则排队尾的轮次又会饿死。
	 */
	private static final int MAX_SCAN_CURSORS = 64;

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

	/**
	 * 前端工具到期收敛的入口（{@link ConfirmGate#convergeExpiredParkedFrontend(String)}）。
	 *
	 * <p><b>字段注入</b>：构造器被多份既有用例直接 {@code new}（{@code StartupResumeRunner*Test}），
	 * 改签名等于让它们跟改。未注入（轻装配/单测）时只跳过"消费 expiresAt"这一路 —— 扫描的其余
	 * 判据与行为不变。
	 */
	@Autowired(required = false)
	private ConfirmGate confirmGate;

	private final AtomicBoolean scanned = new AtomicBoolean();

	/**
	 * 进程内扫描互斥：同一实例的启动拍、运行期兜底、手工扫描不会并发跑（跨实例那一层是
	 * {@link #SCAN_LEASE_KEY} 上的租约）。有界 `tryLock` —— 宁可这一拍让位，也不让 HTTP 线程或
	 * 定时线程无限等。
	 */
	private final ReentrantLock scanGate = new ReentrantLock();

	/** 本进程是否正持有一次扫描（续租判据：转假 ⇒ 下一拍归还单飞租约）。 */
	private final AtomicBoolean scanInFlight = new AtomicBoolean();

	/**
	 * 扫描 owner 的序号：每次扫描的 owner 取 {@code <instanceId>:scan:<序号>}，<b>一次扫描一个</b>。
	 *
	 * <p>为什么不复用 {@code owner = instanceId}：每次扫描独立标识本次席位，续租与释放都按这一任执行。
	 * 资源锁不参与 run fencing；{@code LeaseService} 在 release 异常时会条件清除该任本地视图，
	 * Redis 键按 TTL 收敛，因此唯一 owner 不会令 held/ended 随扫描次数增长。
	 */
	private final AtomicLong scanSeq = new AtomicLong();

	/**
	 * 轮转起点（按触发面分记：{@code MODE/system} 或 {@code MODE/tenant:user}）：
	 * 上一页最后一个候选的到期 score + member。
	 *
	 * <p>为什么需要它：候选按到期分数排序，产出/尝试预算又有界。若每拍都从第一个开始，
	 * 排在前面的候选每拍都能把预算用光（哪怕每次都只是失败/被占），后面的轮次永久饿死。
	 * 下一拍从"上次停下的位置之后"开始，N 拍内每个候选都有机会被尝试；预算用尽只影响"这一拍"。
	 *
	 * <p><b>有界</b>：键含身份，不能随"来过本实例的用户数"无限增长 —— 上限与淘汰规则见
	 * {@link #MAX_SCAN_CURSORS} / {@link #rememberCursor}（系统面两条永不淘汰）。
	 */
	private final Map<String, RecoveryPageCursor> scanCursors = new ConcurrentHashMap<>();

	private record RecoveryPageCursor(long dueAtMs, String member) {
	}

	private volatile Map<String, Object> lastScan = Map.of("scanned", false);

	/** 最近一次运行期扫描的结果（观测面；不改变业务语义）。 */
	private volatile Map<String, Object> lastSweep = Map.of("swept", false);

	private final Object sweepLock = new Object();

	private final ScheduledExecutorService sweepExecutor;

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
		ScheduledExecutorService executor = null;
		if (properties.getResume().isRuntimeSweepEnabled() || properties.getResume().isOnStartup()) {
			ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(1);
			pool.setKeepAliveTime(60L, TimeUnit.SECONDS);
			pool.allowCoreThreadTimeOut(true);
			pool.setRemoveOnCancelPolicy(true);
			pool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
			executor = pool;
		}
		this.sweepExecutor = executor;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onReady() {
		// 运行期低频扫描与启动扫描各自独立开关：先注册定时器，再走启动那一拍。
		startRuntimeSweep();
		if (!this.properties.getResume().isOnStartup()) {
			log.info("startup auto-resume disabled (app.ai.resume.on-startup=false), discovery only: GET /api/ai/runs");
			this.lastScan = Map.of("scanned", false, "reason", "app.ai.resume.on-startup=false",
					"instanceId", this.store.instanceId());
			return;
		}
		if (!this.availability.isAvailable()) {
			// 模型配置专项批①（设计稿 §3.4）：本类改为无条件装配，所以"没有可用 provider"必须
			// 在这里自检并跳过 —— 绝不能因"无 key"在启动扫描里报错（旧行为 = bean 压根不存在）。
			this.lastScan = unavailableScan();
			log.info("startup auto-resume skipped: AI unavailable ({})", this.availability.code());
			return;
		}
		Duration delay = this.properties.getResume().getStartupDelay();
		long jitterMs = startupJitterMs();
		log.info("startup auto-resume scheduled: delay={} jitter={}ms "
				+ "(bounded stagger, avoids multi-instance scan collision)", delay, jitterMs);
		long waitMs = (delay == null || delay.isZero() || delay.isNegative() ? 0L : delay.toMillis()) + jitterMs;
		this.sweepExecutor.schedule(this::startupScanQuietly, waitMs, TimeUnit.MILLISECONDS);
	}

	private void startupScanQuietly() {
		try {
			startupScan();
		}
		catch (Throwable ex) {
			log.error("startup auto-resume uncaught exception (degraded to no auto-resume)", ex);
		}
	}

	private Map<String, Object> scanCandidates(ScanFlight flight) {
		String cursorScope = cursorScope(Mode.STARTUP, RequestContextUtils.currentOrNull());
		RecoveryPageCursor pageCursor = this.scanCursors.get(cursorScope);
		List<Map<String, Object>> candidates;
		try {
			// 每拍只取一页；下一拍从 score + member 游标后继续，而不是重读前 256 条。
			candidates = recoveryPage(pageCursor);
			if (candidates.isEmpty() && pageCursor != null) {
				this.scanCursors.remove(cursorScope);
				candidates = recoveryPage(null);
			}
		}
		catch (Exception ex) {
			// Redis 不可用时（Q2/§13.1 降级场景）扫描必须"响亮地失败"，而不是让启动线程抛异常：
			// 业务与 AI 端点各自有自己的降级路径，这里只记事实。
			log.warn("startup auto-resume scan failed (Redis unavailable?)", ex);
			Map<String, Object> failed = new LinkedHashMap<>();
			failed.put("scanned", true);
			failed.put("at", Instant.now().toString());
			failed.put("instanceId", this.store.instanceId());
			failed.put("error", "AI_RESUME_FAILED");
			failed.put("resumed", List.of());
			failed.put("skipped", List.of());
			this.lastScan = failed;
			return failed;
		}
		PipelineResult result = runPipeline(candidates, Mode.STARTUP);
		rememberCursor(cursorScope, cursorOfLast(candidates));
		Map<String, Object> report = new LinkedHashMap<>();
		report.put("scanned", true);
		report.put("at", Instant.now().toString());
		report.put("instanceId", this.store.instanceId());
		report.put("candidates", candidates.size());
		report.put("resumed", result.resumed());
		report.put("skipped", result.skipped());
		report.put("onStartup", this.properties.getResume().isOnStartup());
		report.put("zombieAge", String.valueOf(this.properties.getResume().getZombieAge()));
		report.put("scanLease", flight.outcome());
		this.lastScan = report;
		log.info("startup auto-resume done: candidates={} resumed={} skipped={} (single-flight={}, see GET /api/ai/startup-resume)",
				candidates.size(), result.resumed().size(), result.skipped().size(), flight.outcome());
		return report;
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
			log.info("startup auto-resume scan skipped: AI unavailable ({})", this.availability.code());
			return this.lastScan;
		}
		ScanFlight flight = tryStartScan("startup/manual", SCAN_LEASE_WAIT_MS);
		if (flight == null) {
			// 单飞没抢到：另一个执行体（本进程另一线程/另一实例）正在扫同一批候选 ——
			// 系统面扫描本身就是全租户口径，手工扫描按身份单飞，因此"让位"不会漏掉哪一轮。
			// 不覆盖 lastScan：GET 面仍展示最近一次真实扫描的事实。
			Map<String, Object> deferred = deferredScanReport();
			log.info("startup scan deferred: single-flight seat busy (trigger=startup/manual)");
			return deferred;
		}
		try {
			return scanCandidates(flight);
		}
		finally {
			flight.close();
		}
	}

	/**
	 * 启动拍：单飞没抢到时<b>有界重试</b>（至多 {@link #STARTUP_SCAN_ATTEMPTS} 次）。
	 *
	 * <p>让位不覆盖 {@code lastScan}（GET 面仍展示最近一次真实扫描），也不把"没扫"伪装成"扫了 0 轮"；
	 * 每次重试重置 {@code scanned}，否则 {@link #scan(boolean)} 会直接回上一次的 {@code lastScan}。
	 * 总等待 = 每拍有界等待 × 次数 + 间隔抖动，仍在"启动时效"范围内。
	 */
	private void startupScan() {
		for (int attempt = 1; attempt <= STARTUP_SCAN_ATTEMPTS; attempt++) {
			Map<String, Object> report = scan(false);
			if (!Boolean.TRUE.equals(report.get("deferred"))) {
				return;
			}
			this.scanned.set(false);
			if (attempt == STARTUP_SCAN_ATTEMPTS) {
				log.info("startup auto-resume deferred: single-flight seat not acquired within "
						+ "{} ticks (another instance/this process scanning; coverage not lost)", attempt);
				return;
			}
			if (!isSleepQuietly(STARTUP_SCAN_RETRY_MS + ThreadLocalRandom.current().nextLong(STARTUP_SCAN_RETRY_MS))) {
				return;
			}
		}
	}

	public Map<String, Object> lastScan() {
		return this.lastScan;
	}

	/**
	 * 最近一次扫描在<b>给定身份</b>下的视图（{@code GET /api/ai/startup-resume} 的唯一取数口）。
	 *
	 * <p>系统面的自动扫描看到的是全部租户的轮次；报告面不能原样回给任意调用者 ——
	 * 别人的 runId 与跳过原因都属于跨身份信息。这里按<b>报告项自带的记录身份</b>切片
	 * （扫描时逐项写入），不扫描租户列表、不额外读别人的键。
	 *
	 * <p>没有身份（{@code null}）⇒ fail-closed，回 {@code scanned=false, reason=IDENTITY_REQUIRED}，
	 * 绝不回全局事实。HTTP 面上该分支不可达（身份边界先拒）。
	 *
	 * <p>切片后的 {@code candidates} 按本身份的条目数重算；{@code identityScoped=true} 标明这是切片视图
	 * （与系统面的全局报告可区分）。
	 */
	public Map<String, Object> lastScanForIdentity(TenantUser identity) {
		if (identity == null) {
			return Map.of("scanned", false, "reason", "IDENTITY_REQUIRED");
		}
		Map<String, Object> report = this.lastScan;
		Map<String, Object> scoped = new LinkedHashMap<>(report);
		List<Map<String, Object>> resumed = ownedItems(report.get("resumed"), identity);
		List<Map<String, Object>> skipped = ownedItems(report.get("skipped"), identity);
		if (report.containsKey("resumed")) {
			scoped.put("resumed", resumed);
		}
		if (report.containsKey("skipped")) {
			scoped.put("skipped", skipped);
		}
		if (report.containsKey("candidates")) {
			scoped.put("candidates", resumed.size() + skipped.size());
		}
		scoped.put("identityScoped", true);
		return scoped;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> ownedItems(Object raw, TenantUser identity) {
		if (!(raw instanceof List<?> list)) {
			return List.of();
		}
		List<Map<String, Object>> owned = new ArrayList<>();
		for (Object item : list) {
			if (item instanceof Map<?, ?> map
					&& isSameIdentity(identity, asLong(map.get("tenantId")), asLong(map.get("userId")))) {
				owned.add((Map<String, Object>) map);
			}
		}
		return owned;
	}

	/**
	 * 记录身份是否等于给定身份：两侧都必须<b>可用</b>（{@code tenantId>0}、
	 * {@code userId>0 或 -1}）。{@code 0/0} 记录不属于任何身份 —— 不得被"恰好没有身份"
	 * 的调用方认领，也不得被请求面扫描拾起。
	 */
	private static boolean isSameIdentity(TenantUser identity, long tenantId, long userId) {
		return identity != null && RunIdentityScope.usable(tenantId, userId) && identity.tenantId() == tenantId
				&& identity.userId() == userId;
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
	 * <li><b>候选</b> = {@link ResumeService#listRunsForRecovery()}：与启动扫描、运维端点同一份"发现态"
	 * （恢复侧只取轻量投影，安全字段一致），
	 * 其中 {@code cancelled} / {@code unresolvedExternal} / {@code zombieRunning} 已按
	 * {@code app.ai.resume.zombie-age}（180s）与<b>业务</b>活动锚点算好；</li>
	 * <li><b>活/死</b> = {@link RunLiveness#isDead}：会话门放行（{@code AiController#isDeadRound}）
	 * 与重挂自愈用的是同一个函数 —— 租约在别人手里、本进程还有真实订阅者、业务活动仍在窗口内，
	 * 任一成立即判活 ⇒ <b>活跃不接管</b>；</li>
	 * <li><b>窗口</b> = {@code app.ai.resume.zombie-age} 原值：本方法<b>不</b>引入更短的判死窗口，
	 * 也<b>不</b>把心跳算成业务活动（心跳只证明进程活着）。</li>
	 * </ul>
	 *
	 * <h2>四条不再扩大语义的边界</h2>
	 * <ol>
	 * <li><b>无身份不驱动</b>：{@code tenantId/userId} 不可用的记录一律记 {@link #LEGACY_NO_IDENTITY}、
	 * 不调续跑（即使当前发现面不产出，入口硬校验仍保留）；</li>
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
	 *
	 * <h2>F3 / F2：两条"不续跑但必须收敛"的旁路</h2>
	 * <ul>
	 * <li><b>取消旗标 + 非终态</b>：不跳过、不续跑，走 {@link ResumeService#cancelConfirmed(String)}
	 *     （抢租约 + 校验取消事实 + 归档终帧幂等，绝不驱动模型）。报告记入 {@code finalized}；
	 *     没落定的（租约在别处/会话门被占/不可确认）记入 {@code skipped} 的 {@code CANCEL_*} 原因，
	 *     下一拍再试。</li>
	 * <li><b>park 到期</b>：非终态轮次在 {@code PENDING_UNRESOLVED} 判定之前先让确认门消费持久化的
	 *     {@code expiresAt}（{@code confirmGate.convergeExpiredParkedFrontend}）；本拍收敛过就记入
	 *     {@code expired} 并跳过（到期收敛内部已派发一次续跑，避免重复驱动）。</li>
	 * </ul>
	 * 两条都按<b>记录身份</b>开作用域（不扫描租户列表），产出预算仍与续跑共用 {@code max-on-startup}；
	 * 动作尝试（含失败/无果）另受 {@code scan-max-attempts} 约束，起点按轮转推进（见 {@link #runPipeline}）。
	 */
	private Map<String, Object> sweepCandidates(ScanFlight flight) {
		String cursorScope = cursorScope(Mode.RUNTIME, RequestContextUtils.currentOrNull());
		RecoveryPageCursor pageCursor = this.scanCursors.get(cursorScope);
		List<Map<String, Object>> candidates;
		try {
			candidates = recoveryPage(pageCursor);
			if (candidates.isEmpty() && pageCursor != null) {
				this.scanCursors.remove(cursorScope);
				candidates = recoveryPage(null);
			}
		}
		catch (Exception ex) {
			// 与启动扫描同一姿态：Redis 不可用时"响亮地失败"并留事实，不把异常丢给调度线程。
			log.warn("runtime dead-run sweep failed (Redis unavailable?)", ex);
			Map<String, Object> failed = new LinkedHashMap<>();
			failed.put("swept", true);
			failed.put("at", Instant.now().toString());
			failed.put("instanceId", this.store.instanceId());
			failed.put("error", "AI_RESUME_FAILED");
			failed.put("resumed", List.of());
			failed.put("skipped", List.of());
			this.lastSweep = failed;
			return failed;
		}
		long zombieAgeMs = this.properties.getResume().getZombieAge().toMillis();
		PipelineResult result = runPipeline(candidates, Mode.RUNTIME);
		rememberCursor(cursorScope, cursorOfLast(candidates));
		Map<String, Object> report = new LinkedHashMap<>();
		report.put("swept", true);
		report.put("at", Instant.now().toString());
		report.put("instanceId", this.store.instanceId());
		report.put("candidates", candidates.size());
		report.put("resumed", result.resumed());
		report.put("finalized", result.finalized());
		report.put("expired", result.expired());
		report.put("skipped", result.skipped());
		report.put("zombieAgeMs", zombieAgeMs);
		report.put("scanLease", flight.outcome());
		this.lastSweep = report;
		log.info("runtime dead-run sweep done: candidates={} resumed={} finalized={} "
				+ "expired={} skipped={} (zombieAge={}, single-flight={})",
				candidates.size(), result.resumed().size(), result.finalized().size(), result.expired().size(),
				result.skipped().size(), this.properties.getResume().getZombieAge(), flight.outcome());
		return report;
	}

	public Map<String, Object> sweep() {
		if (!this.availability.isAvailable()) {
			Map<String, Object> skippedReport = unavailableSweep();
			this.lastSweep = skippedReport;
			log.info("runtime dead-run sweep skipped: AI unavailable ({})", this.availability.code());
			return skippedReport;
		}
		// 运行期拍不等待单飞：周期扫描本来就是"下一拍再来"，等只会占用调度线程。
		ScanFlight flight = tryStartScan("runtime-sweep", 0L);
		if (flight == null) {
			Map<String, Object> deferred = deferredSweepReport();
			log.info("runtime dead-run sweep deferred: single-flight seat busy (next tick as usual)");
			return deferred;
		}
		try {
			return sweepCandidates(flight);
		}
		finally {
			flight.close();
		}
	}

	private int recoveryCandidateLimit() {
		int configured = Math.max(this.properties.getResume().getMaxOnStartup(),
				this.properties.getResume().getScanMaxAttempts());
		return Math.min(256, Math.max(1, configured));
	}

	/** 扫描触发面：启动/手工（{@link #scan(boolean)}）与运行期兜底（{@link #sweep()}）。 */
	private enum Mode {

		STARTUP,

		RUNTIME

	}

	/** 公共管线产出；报告面由两个触发入口各自组装（契约字段不变）。 */
	private record PipelineResult(List<Map<String, Object>> resumed, List<Map<String, Object>> skipped,
			List<Map<String, Object>> finalized, List<Map<String, Object>> expired) {
	}

	/**
	 * <b>扫描/兜底的公共核心管线</b>：候选枚举之后，身份门、0/0 硬校验、预算、发现态分类和
	 * "取消收尾 / 到期收敛 / 续跑"都只在这里实现一次。
	 *
	 * <p>两种触发只差三处：运行期多两条收敛旁路（F3 取消收尾、F2 park 到期）和租约/活订阅判死
	 * （{@link RunLiveness}），启动/手工则按 {@code zombieRunning}（启动接管时效）给
	 * {@code forceTakeover}。请求面（线程有身份）在进入循环时先切片，<b>只处理当前身份</b>；
	 * 系统面按每条记录的记录身份推进。
	 *
	 * <p><b>最小安全前置</b>：取消旗标、终态、未决外部输入（{@code discoveryOf}）先跳过；
	 * 真正的裁决仍由 {@link ResumeService#resume} 的真实 outcome 给出 —— 租约、会话门、取消、
	 * 终态、待决条目都在那条漏斗里，本类不复制也不绕过。
	 */
	private PipelineResult runPipeline(List<Map<String, Object>> candidates, Mode mode) {
		int limit = Math.max(1, this.properties.getResume().getMaxOnStartup());
		int attemptLimit = Math.max(limit, this.properties.getResume().getScanMaxAttempts());
		PipelineBudget budget = new PipelineBudget(limit, attemptLimit,
				this.properties.getResume().getZombieAge().toMillis());
		PipelineResult result = new PipelineResult(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
				new ArrayList<>());
		TenantUser caller = RequestContextUtils.currentOrNull();
		for (Map<String, Object> run : candidates) {
			processPipelineCandidate(run, mode, caller, budget, result);
		}
		return result;
	}

	private void processPipelineCandidate(Map<String, Object> run, Mode mode, TenantUser caller,
			PipelineBudget budget, PipelineResult result) {
		String runId = String.valueOf(run.get("runId"));
		String status = ObjectUtils.toString(run.get("status"), (String) null);
		long tenantId = identityField(run, "tenantId");
		long userId = identityField(run, "userId");
		if (caller != null && !isSameIdentity(caller, tenantId, userId)) {
			if (log.isDebugEnabled()) log.debug("scan skipped run of another identity runId={} (identity mismatch)", runId);
			return;
		}
		if (!RunIdentityScope.usable(tenantId, userId)) {
			result.skipped().add(mode == Mode.STARTUP
					? scanDiscoverySkipItem(runId, status, tenantId, userId, run, LEGACY_NO_IDENTITY)
					: sweepSkipItem(runId, status, LEGACY_NO_IDENTITY));
			log.info("scan skipped run without identity runId={} (reason={}; no model/tool drive)", runId,
					LEGACY_NO_IDENTITY);
			return;
		}
		if (mode == Mode.RUNTIME && processRuntimeCancellation(run, runId, status, tenantId, userId, budget, result)) {
			return;
		}
		if (mode == Mode.RUNTIME && processRuntimeExpiry(runId, status, tenantId, userId, budget, result)) {
			return;
		}
		Discovery discovery = discoveryOf(run);
		if (discovery != Discovery.TAKEABLE) {
			result.skipped().add(mode == Mode.STARTUP
					? scanDiscoverySkipItem(runId, status, tenantId, userId, run, scanSkipReason(discovery))
					: sweepSkipItem(runId, status, sweepSkipReason(discovery)));
			return;
		}
		if (mode == Mode.RUNTIME) {
			processRuntimeResume(run, runId, status, tenantId, userId, budget, result);
		}
		else {
			processStartupResume(run, runId, status, tenantId, userId, budget, result);
		}
	}

	private boolean processRuntimeCancellation(Map<String, Object> run, String runId, String status, long tenantId,
			long userId, PipelineBudget budget, PipelineResult result) {
		if (!Boolean.TRUE.equals(run.get("cancelled")) || RunSnapshot.isTerminal(status)) {
			return false;
		}
		if (productive(result.resumed(), result.finalized(), result.expired()) >= budget.limit()) {
			result.skipped().add(sweepSkipItem(runId, status, "MAX_PER_SWEEP_REACHED"));
			return true;
		}
		if (budget.attempts() >= budget.attemptLimit()) {
			result.skipped().add(sweepSkipItem(runId, status, ATTEMPT_BUDGET_REACHED));
			return true;
		}
		budget.attempts++;
		ResumeService.CancelFinalize outcome;
		try {
			outcome = cancelWithRunIdentity(runId, tenantId, userId);
		}
		catch (RuntimeException ex) {
			result.skipped().add(sweepSkipItem(runId, status, "CANCEL_EXCEPTION"));
			log.warn("runtime scan cancel finalize failed runId={} (retry next tick)", runId, ex);
			return true;
		}
		if (outcome == ResumeService.CancelFinalize.FINALIZED
				|| outcome == ResumeService.CancelFinalize.ALREADY_TERMINAL
				|| outcome == ResumeService.CancelFinalize.NO_RUN) {
			result.finalized().add(cancelFinalizedItem(runId, status, tenantId, userId, outcome));
		}
		else {
			result.skipped().add(sweepSkipItem(runId, status, "CANCEL_" + outcome));
		}
		return true;
	}

	private static Map<String, Object> cancelFinalizedItem(String runId, String status, long tenantId, long userId,
			ResumeService.CancelFinalize outcome) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("runId", runId);
		item.put("tenantId", tenantId);
		item.put("userId", userId);
		item.put("statusBeforeSweep", status);
		item.put("outcome", outcome.name());
		return item;
	}

	private boolean processRuntimeExpiry(String runId, String status, long tenantId, long userId,
			PipelineBudget budget, PipelineResult result) {
		if (RunSnapshot.isTerminal(status) || this.confirmGate == null
				|| productive(result.resumed(), result.finalized(), result.expired()) >= budget.limit()
				|| budget.expireAttempts() >= budget.attemptLimit()) {
			return false;
		}
		budget.expireAttempts++;
		int converged = expireParkedWithRunIdentity(runId, tenantId, userId);
		if (converged <= 0) {
			return false;
		}
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("runId", runId);
		item.put("tenantId", tenantId);
		item.put("userId", userId);
		item.put("statusBeforeSweep", status);
		item.put("expired", converged);
		result.expired().add(item);
		return true;
	}

	private void processRuntimeResume(Map<String, Object> run, String runId, String status, long tenantId,
			long userId, PipelineBudget budget, PipelineResult result) {
		if (!isDeadWithoutDriver(run, status, budget.nowMs(), budget.zombieAgeMs())) {
			result.skipped().add(sweepSkipItem(runId, status, "ACTIVE"));
			return;
		}
		if (result.resumed().size() >= budget.limit()) {
			result.skipped().add(sweepSkipItem(runId, status, "MAX_PER_SWEEP_REACHED"));
			return;
		}
		if (budget.attempts() >= budget.attemptLimit()) {
			result.skipped().add(sweepSkipItem(runId, status, ATTEMPT_BUDGET_REACHED));
			return;
		}
		budget.attempts++;
		result.resumed().add(resumeRuntimeCandidate(runId, status, tenantId, userId));
	}

	private void processStartupResume(Map<String, Object> run, String runId, String status, long tenantId,
			long userId, PipelineBudget budget, PipelineResult result) {
		if (result.resumed().size() >= budget.limit()) {
			result.skipped().add(startupBudgetSkipItem(runId, tenantId, userId, "MAX_ON_STARTUP_REACHED"));
			return;
		}
		if (budget.attempts() >= budget.attemptLimit()) {
			result.skipped().add(startupBudgetSkipItem(runId, tenantId, userId, ATTEMPT_BUDGET_REACHED));
			return;
		}
		budget.attempts++;
		result.resumed().add(resumeStartupCandidate(runId, tenantId, userId,
				Boolean.TRUE.equals(run.get("zombieRunning"))));
	}

	private static Map<String, Object> startupBudgetSkipItem(String runId, long tenantId, long userId,
			String reason) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("runId", runId);
		item.put("tenantId", tenantId);
		item.put("userId", userId);
		item.put("reason", reason);
		return item;
	}

	private static final class PipelineBudget {

		private final int limit;
		private final int attemptLimit;
		private final long nowMs = System.currentTimeMillis();
		private final long zombieAgeMs;
		private int attempts;
		private int expireAttempts;

		private PipelineBudget(int limit, int attemptLimit, long zombieAgeMs) {
			this.limit = limit;
			this.attemptLimit = attemptLimit;
			this.zombieAgeMs = zombieAgeMs;
		}

		private int limit() { return this.limit; }
		private int attemptLimit() { return this.attemptLimit; }
		private long nowMs() { return this.nowMs; }
		private long zombieAgeMs() { return this.zombieAgeMs; }
		private int attempts() { return this.attempts; }
		private int expireAttempts() { return this.expireAttempts; }

	}

	private Map<String, Object> resumeRuntimeCandidate(String runId, String status, long tenantId, long userId) {
		boolean takeover = RunSnapshot.RUNNING.equals(status);
		AiTrace.bind(runId, null);
		try {
			ResumeService.ResumeResult result = resumeWithRunIdentity(runId, takeover, tenantId, userId);
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("runId", runId);
			item.put("statusBeforeSweep", status);
			item.put("zombieTakeover", takeover);
			item.put("outcome", result.outcome().name());
			item.put("body", result.body());
			return item;
		}
		catch (Exception ex) {
			log.error("runtime dead-run sweep resume failed runId={}", runId, ex);
			return resumeExceptionItem(runId);
		}
		finally {
			AiTrace.clear();
		}
	}

	private Map<String, Object> resumeStartupCandidate(String runId, long tenantId, long userId, boolean zombie) {
		AiTrace.bind(runId, null);
		try {
			ResumeService.ResumeResult result = resumeWithRunIdentity(runId, zombie, tenantId, userId);
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("runId", runId);
			item.put("tenantId", tenantId);
			item.put("userId", userId);
			item.put("zombieTakeover", zombie);
			item.put("outcome", result.outcome().name());
			item.put("body", result.body());
			return item;
		}
		catch (Exception ex) {
			log.error("startup auto-resume failed runId={}", runId, ex);
			return resumeExceptionItem(runId);
		}
		finally {
			AiTrace.clear();
		}
	}

	private static Map<String, Object> resumeExceptionItem(String runId) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("runId", runId);
		item.put("outcome", "EXCEPTION");
		item.put("error", "AI_RESUME_FAILED");
		return item;
	}

	/**
	 * 记一条轮转起点（<b>有界</b>）。键 = 模式 × 触发面：系统面固定两条（{@code STARTUP/RUNTIME + /system}），
	 * 请求面每个在本实例触发过手工扫描的身份一条。
	 *
	 * <p>身份数在进程内不设上界（任何用户都可能点一次），因此到达 {@link #MAX_SCAN_CURSORS} 时
	 * <b>只淘汰请求面的游标</b>，系统面两条保留：系统面每拍的轮转起点若被清掉，就会退回"每拍从头部
	 * 开始"，排队尾的轮次重新饿死 —— 那正是轮转要解决的问题。被淘汰的身份下一次扫描从固定顺序起点
	 * 开始、之后照常轮转，只损失一次连续性，不产生永久饿死。
	 */
	private List<Map<String, Object>> recoveryPage(RecoveryPageCursor cursor) {
		long afterDueAtMs = cursor == null ? 0L : cursor.dueAtMs();
		String afterMember = cursor == null ? null : cursor.member();
		return this.resumeService.listRunsForRecoveryAfter(recoveryCandidateLimit(), afterDueAtMs, afterMember);
	}

	private static RecoveryPageCursor cursorOfLast(List<Map<String, Object>> candidates) {
		if (candidates.isEmpty()) {
			return null;
		}
		Map<String, Object> last = candidates.getLast();
		String runId = String.valueOf(last.get("runId"));
		String member = RequestContextUtils.currentOrNull() == null
				? RunKeyScope.indexMember(identityField(last, "tenantId"), identityField(last, "userId"), runId)
				: runId;
		return new RecoveryPageCursor(asLong(last.get("dueAtMs")), member);
	}

	private void rememberCursor(String scope, RecoveryPageCursor cursor) {
		if (cursor == null) {
			this.scanCursors.remove(scope);
			return;
		}
		if (this.scanCursors.size() >= MAX_SCAN_CURSORS && !this.scanCursors.containsKey(scope)
				&& !isEvictIdentityCursor()) {
			// 理论上不可达（系统面至多 2 条 < 上限 ⇒ 达上限时必有请求面游标可淘汰）。
			// 真到这里就宁可不记（该身份下一次从头部开始），也守住"游标表有界"这条硬约束。
			return;
		}
		this.scanCursors.put(scope, cursor);
	}

	/** 淘汰一条请求面游标；系统面（{@code MODE/system}）永不淘汰。 */
	private boolean isEvictIdentityCursor() {
		for (String scope : this.scanCursors.keySet()) {
			if (!scope.endsWith("/system") && this.scanCursors.remove(scope) != null) {
				return true;
			}
		}
		return false;
	}

	/** 产出预算口径（受理 + 取消收尾 + 到期收敛）。 */
	private static int productive(List<Map<String, Object>> resumed, List<Map<String, Object>> finalized,
			List<Map<String, Object>> expired) {
		return resumed.size() + finalized.size() + expired.size();
	}

	/** 轮转游标的作用域：模式 + 触发面身份（系统面 / 某一身份）。 */
	private static String cursorScope(Mode mode, TenantUser caller) {
		return mode.name() + (caller == null ? "/system" : "/" + caller.tenantId() + ":" + caller.userId());
	}

	/** 启动/手工扫描的"发现态跳过"报告项（带记录身份，GET 面据此切片）。 */
	private static Map<String, Object> scanDiscoverySkipItem(String runId, String status, long tenantId,
			long userId, Map<String, Object> run, String reason) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("runId", runId);
		item.put("tenantId", tenantId);
		item.put("userId", userId);
		item.put("status", status);
		item.put("reason", reason);
		item.put("unresolvedExternal", run.get("unresolvedExternal"));
		return item;
	}

	/** 最近一次运行期扫描的结果（观测面；未开开关时保持 {@code swept=false} 的初值）。 */
	public Map<String, Object> lastSweep() {
		return this.lastSweep;
	}

	// ── 扫描单飞（进程内门 + 跨实例带续租租约） ─────────────────────────────

	/**
	 * 抢一次扫描的<b>单飞席位</b>：进程内门 → 跨实例租约（带续租）。
	 *
	 * <h2>为什么三层都要，缺一层会怎样</h2>
	 * <ul>
	 * <li><b>进程内门</b>：启动拍、运行期拍、手工请求可能同时到达；只靠 Redis 租约时三者会
	 *     互相"可重入"（同 owner），于是并发跑同一批候选；</li>
	 * <li><b>跨实例租约</b>：N 个实例同拍启动会把同一批候选各扫一遍、各算一份
	 *     {@code max-on-startup}（审计 F-03）；</li>
	 * <li><b>续租</b>：扫描可能比租约 TTL 长（候选多、F2/F3 各要一次往返）；不续租就会被第二个实例
	 *     在扫描中途抢走席位 ⇒ 两实例并发扫。续租判据是 {@code scanInFlight}，续租 TTL 与抢锁同取
	 *     {@link #scanLeaseTtl()}（不能让默认 30s 把 60s 配置缩掉），扫描收尾先停续租再归还。</li>
	 * </ul>
	 *
	 * <p><b>owner 每次扫描唯一</b>（{@code <instanceId>:scan:<序号>}，见 {@link #scanSeq}）：
	 * 每一拍都是全新 {@code (key, owner)}，续租与释放都对应这一任；资源锁 release 异常时
	 * 本地视图立即清理，Redis 键仍按 TTL 收敛。
	 *
	 * <h2>拿不到时的两种结论（都不是"报错"）</h2>
	 * <ul>
	 * <li><b>别人持有</b>（{@code HELD_ELSEWHERE}）：等有界预算（{@link #SCAN_LEASE_WAIT_MS}），
	 *     仍拿不到 ⇒ 返回 {@code null}（本拍让位）。系统面扫描本来就是全租户口径、手工扫描按身份单飞，
	 *     所以让位不会漏掉哪一轮；</li>
	 * <li><b>设施不可确认</b>（{@code UNAVAILABLE} / 无租约设施）：按
	 *     {@link LeaseService} 的契约退到<b>进程内单飞</b>继续扫 —— 一次 Redis 抖动不能把启动续跑
	 *     整体停摆（单实例仍然正确）。</li>
	 * </ul>
	 *
	 * @param trigger 日志口径（{@code startup/manual} / {@code runtime-sweep}）
	 * @param waitMs  抢不到时的有界等待（运行期拍传 0：下一次周期再来）
	 * @return 席位（必须 {@code close()}）；{@code null} = 本拍让位
	 */
	private ScanFlight acquiredScanFlight(LeaseService leases, LeaseService.AcquireResult result,
			TenantUser scanIdentity, String trigger) {
		this.scanInFlight.set(true);
		LeaseService.Lease lease = result.lease();
		if (lease != null) {
			try {
				// 带续租：扫描期间保持席位；收尾先停续租再释放（见 ScanFlight#close）。
				// 续租 TTL 必须与抢锁 TTL 同值：默认口径是 app.ai.lease.ttl（30s），
				// 用它续期会把 60s 的 scan-lease-ttl 直接在下一拍缩成 30s。
				leases.markDriving(lease, this.scanInFlight::get, scanIdentity, scanLeaseTtl());
			}
			catch (RuntimeException ex) {
				// 续租登记失败（调度器已停机等）：仍按"已拿到席位"继续 —— TTL 到期后自然可被接手，
				// 绝不能让一次登记异常把闸门留在锁住状态。
				log.warn("scan single-flight renew registration failed (TTL fallback) trigger={}", trigger, ex);
			}
		}
		// owner 唯一 ⇒ created 恒为真；仍把它写进报告口径（日志/观测可区分异常形态）。
		return new ScanFlight(leases, lease, result.created() ? "ACQUIRED" : "REENTRANT");
	}

	private ScanFlight inconclusiveScanFlight(String trigger) {
		// 设施返回了"没有结论"（非生产形态：真实现恒返回三态之一；替身/桩可能给 null）：
		// 同样按"不可确认"降级，绝不让一次 null 把整拍扫描打崩。
		log.warn("scan single-flight lease inconclusive (falling back to in-process single-flight) trigger={}", trigger);
		this.scanInFlight.set(true);
		return new ScanFlight(null, null, "UNAVAILABLE");
	}

	private ScanFlight tryStartScan(String trigger, long waitMs) {
		if (!lockScanGate(waitMs)) {
			log.info("scan skipped: scan already running in this process (trigger={}), not scanning the same candidates concurrently", trigger);
			return null;
		}
		TenantUser scanIdentity = RequestContextUtils.currentOrNull();
		String key = scanLeaseKey(scanIdentity);
		LeaseService leases = this.store.leaseService();
		if (leases == null) {
			this.scanInFlight.set(true);
			return new ScanFlight(null, null, "LOCAL_ONLY");
		}
		// 每次扫描唯一的 owner（见 scanSeq 的类注）：release 失败也不会让下一拍拿到 created=false 的
		// 旧视图，因此"续租 / 释放"在每一拍都是必须做的两件事，而不是可能被重入短路掉。
		String owner = leases.instanceId() + ":scan:" + this.scanSeq.incrementAndGet();
		long deadlineMs = System.currentTimeMillis() + Math.max(0L, waitMs);
		while (true) {
			LeaseService.AcquireResult result;
			try {
				result = leases.acquire(key, scanLeaseTtl(), owner);
			}
			catch (RuntimeException ex) {
				// 租约设施异常：按进程内单飞继续（LeaseService 的降级契约），别把扫描整体停摆。
				log.warn("scan single-flight lease unavailable (falling "
						+ "back to in-process single-flight) trigger={}", trigger, ex);
				this.scanInFlight.set(true);
				return new ScanFlight(null, null, "UNAVAILABLE");
			}
			if (result == null) {
				return inconclusiveScanFlight(trigger);
			}
			if (result.isAcquired()) {
				return acquiredScanFlight(leases, result, scanIdentity, trigger);
			}
			if (result.outcome() != LeaseService.AcquireOutcome.HELD_ELSEWHERE) {
				log.warn("scan single-flight lease facility unconfirmed (falling back to in-process single-flight) trigger={} outcome={}", trigger,
						result.outcome());
				this.scanInFlight.set(true);
				return new ScanFlight(null, null, "UNAVAILABLE");
			}
			if (System.currentTimeMillis() >= deadlineMs) {
				unlockScanGate();
				log.info("scan deferred: another instance holds scan single-flight (trigger={}, wait budget {}ms exhausted)", trigger, waitMs);
				return null;
			}
			if (!isSleepQuietly(SCAN_LEASE_RETRY_MS + ThreadLocalRandom.current().nextLong(SCAN_LEASE_RETRY_MS))) {
				unlockScanGate();
				log.info("scan abandoned: single-flight wait interrupted (trigger={})", trigger);
				return null;
			}
		}
	}

	/** 单飞键：系统面一把全局锁；请求面按调用身份分（手工扫描只处理当前身份）。 */
	private static String scanLeaseKey(TenantUser scanIdentity) {
		return scanIdentity == null ? SCAN_LEASE_KEY
				: SCAN_LEASE_IDENTITY_PREFIX + scanIdentity.tenantId() + ":" + scanIdentity.userId();
	}

	/** 单飞 TTL（配错/未配时给一个有界缺省，避免 0 TTL 变成"永不持有"）。 */
	private Duration scanLeaseTtl() {
		Duration ttl = this.properties.getResume().getScanLeaseTtl();
		return ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofSeconds(60) : ttl;
	}

	private boolean lockScanGate(long waitMs) {
		try {
			return waitMs <= 0L ? this.scanGate.tryLock() : this.scanGate.tryLock(waitMs, TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private void unlockScanGate() {
		if (this.scanGate.isHeldByCurrentThread()) {
			this.scanGate.unlock();
		}
	}

	/** 睡一小段；被中断时返回 false（调用方负责归还门）。 */
	private static boolean isSleepQuietly(long ms) {
		if (ms <= 0L) {
			return true;
		}
		try {
			Thread.sleep(ms);
			return true;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** 启动抖动：{@code [0, min(startup-jitter, 60s)]} 的均匀随机（有界，绝不无限推迟）。 */
	private long startupJitterMs() {
		Duration jitter = this.properties.getResume().getStartupJitter();
		if (jitter == null || jitter.isZero() || jitter.isNegative()) {
			return 0L;
		}
		long bound = Math.min(jitter.toMillis(), MAX_STARTUP_JITTER_MS);
		return bound <= 0L ? 0L : ThreadLocalRandom.current().nextLong(bound + 1L);
	}

	/**
	 * 一次扫描的单飞席位（{@code tryStartScan} 的返回值）。
	 *
	 * <p><b>owner 每次扫描唯一</b>（{@code <instanceId>:scan:<序号>}，见 {@link #scanSeq}）：
	 * 因此"这个席位归本次扫描"无需再看 {@code created} —— 同一 (key, owner) 不可能被第二次 acquire
	 * 复用（进程内门也保证同一实例不并发扫）。资源锁 release 异常会清掉该任本地视图，
	 * 不让唯一 owner 的历史条目在进程内累积。
	 *
	 * <p>关闭顺序：
	 * <ol>
	 * <li>{@code scanInFlight} 置假：任何正在跑的续租拍下一拍判据即转假；</li>
	 * <li>{@link LeaseService#stopDriving(String, String) stopDriving(key, owner)}：<b>先停掉</b>本任的续租任务，
	 *     再删租约键。反过来的顺序里，一拍已经排上/正在执行的"迟到续租"可能落在 {@code release} 之后：
	 *     那时键已删（PEXPIRE 无事发生）或已被别人以新 epoch 抢走（RENEW 返回 0 ⇒ 给本任落 LOST 墓碑并
	 *     摘进程内视图）—— 都会把「已经交还的席位」再动一次。先停再放，本任的续租不可能晚于归还；</li>
	 * <li>{@code release(lease)}：条件删除（token 含唯一 owner ⇒ 错 epoch/错 owner 不会删别人的键），
	 *     并顺手清墓碑；</li>
	 * <li>放进程内门。任何一步失败都只留痕，不让扫描主流程抛异常。</li>
	 * </ol>
	 */
	private final class ScanFlight implements AutoCloseable {

		private final LeaseService leases;

		private final LeaseService.Lease lease;

		private final String outcome;

		ScanFlight(LeaseService leases, LeaseService.Lease lease, String outcome) {
			this.leases = leases;
			this.lease = lease;
			this.outcome = outcome;
		}

		/** 报告/日志口径：{@code ACQUIRED} / {@code REENTRANT} / {@code LOCAL_ONLY} / {@code UNAVAILABLE}。 */
		String outcome() {
			return this.outcome;
		}

		@Override
		public void close() {
			StartupResumeRunner.this.scanInFlight.set(false);
			if (this.leases != null && this.lease != null) {
				try {
					// 先停续租：本任的续租拍不会再晚于 release 落到"已交还/已被别人接管"的键上。
					this.leases.stopDriving(this.lease.key(), this.lease.owner());
				}
				catch (RuntimeException ex) {
					if (LogSampling.canAcquire("startup-resume-runtime-catch")) log.warn("scan single-flight renew stop failed (TTL fallback)", ex);
				}
				try {
					this.leases.isReleased(this.lease);
				}
				catch (RuntimeException ex) {
					if (LogSampling.canAcquire("startup-resume-runtime-catch")) log.warn("scan single-flight lease release failed (waiting TTL expiry)", ex);
				}
			}
			unlockScanGate();
		}

	}

	/**
	 * 让位时的启动口径报告：<b>字段契约与正常报告一致</b>（{@code scanned/at/instanceId/candidates/
	 * resumed/skipped/onStartup/zombieAge}），只额外标 {@code deferred=true} 与原因，绝不伪装成"扫了 0 轮"。
	 */
	private Map<String, Object> deferredScanReport() {
		Map<String, Object> report = new LinkedHashMap<>();
		report.put("scanned", true);
		report.put("at", Instant.now().toString());
		report.put("instanceId", this.store.instanceId());
		report.put("candidates", 0);
		report.put("resumed", List.of());
		report.put("skipped", List.of());
		report.put("onStartup", this.properties.getResume().isOnStartup());
		report.put("zombieAge", String.valueOf(this.properties.getResume().getZombieAge()));
		report.put("deferred", true);
		report.put("reason", SCAN_IN_FLIGHT);
		return report;
	}

	/** 让位时的运行期报告（契约同上：{@code swept/.../finalized/expired/skipped/zombieAgeMs}）。 */
	private Map<String, Object> deferredSweepReport() {
		Map<String, Object> report = new LinkedHashMap<>();
		report.put("swept", true);
		report.put("at", Instant.now().toString());
		report.put("instanceId", this.store.instanceId());
		report.put("candidates", 0);
		report.put("resumed", List.of());
		report.put("finalized", List.of());
		report.put("expired", List.of());
		report.put("skipped", List.of());
		report.put("zombieAgeMs", this.properties.getResume().getZombieAge().toMillis());
		report.put("deferred", true);
		report.put("reason", SCAN_IN_FLIGHT);
		return report;
	}

	/**
	 * 注册运行期扫描定时器（{@code app.ai.resume.runtime-sweep-enabled}）。
	 *
	 * <p>缺省关闭（代码缺省 {@code false}，出厂 yml 打开，与 {@code on-startup} 同一纪律）；
	 * 与启动扫描同时关闭时<b>不建线程</b>。周期非法（{@code <= 0}）时只 ERROR 留痕、不注册 ——
	 * {@code scheduleWithFixedDelay} 对非正值会抛 {@link IllegalArgumentException}，
	 * 不能让一个配错的间隔把启动路径带崩。
	 */
	public void startRuntimeSweep() {
		if (!this.properties.getResume().isRuntimeSweepEnabled()) {
			log.info("runtime dead-run sweep not registered (app.ai.resume.runtime-sweep-enabled=false): "
					+ "startup scan and reattach self-heal still active");
			return;
		}
		Duration interval = this.properties.getResume().getRuntimeSweepInterval();
		if (interval == null || interval.isZero() || interval.isNegative()) {
			log.error("runtime dead-run sweep not registered: app.ai.resume.runtime-sweep-interval must be > 0 (current = {})", interval);
			return;
		}
		synchronized (this.sweepLock) {
			if (this.sweepFuture != null && !this.sweepFuture.isDone()) {
				return;
			}
			this.sweepFuture = this.sweepExecutor.scheduleWithFixedDelay(this::sweepQuietly, interval.toMillis(),
					interval.toMillis(), TimeUnit.MILLISECONDS);
		}
		log.info("runtime dead-run sweep registered: interval={} zombie-age={} (threshold not relaxed)", interval,
				this.properties.getResume().getZombieAge());
	}

	/** 停止运行期扫描定时器；应用停机由 {@code @PreDestroy} 走同一入口（幂等）。 */
	@PreDestroy
	public void stopRuntimeSweep() {
		ScheduledFuture<?> future;
		synchronized (this.sweepLock) {
			future = this.sweepFuture;
			this.sweepFuture = null;
		}
		try {
			if (future != null) {
				future.cancel(false);
			}
		}
		catch (RuntimeException ex) {
			if (LogSampling.canAcquire("startup-sweep-future-close")) log.warn("runtime sweep task close failed", ex);
		}
		try {
			if (this.sweepExecutor != null) {
				this.sweepExecutor.shutdownNow();
			}
		}
		catch (RuntimeException ex) {
			if (LogSampling.canAcquire("startup-sweep-executor-close")) log.warn("runtime sweep executor close failed", ex);
		}
	}

	/** 运行期扫描定时器是否在跑（观测/单测；停机后必为 false）。 */
	public boolean isRuntimeSweepRunning() {
		ScheduledFuture<?> future = this.sweepFuture;
		return future != null && !future.isDone() && !future.isCancelled();
	}

	/**
	 * 以<b>该轮记录身份</b>执行一次续跑（启动扫描与运行期扫描的唯一入口）。
	 *
	 * <p>扫描跑在定时线程上，那里没有请求身份；身份也不能从"当前线程"或默认租户补。
	 * 身份的<b>来源是 {@code ai:recovery:due} 的复合索引成员</b>（{@code tenantId/userId/runId}，
	 * 由 {@code ResumeService.listRunsForRecovery()} 随条目交下来）—— 不是"先读快照再猜身份"：
	 * 快照键本身由身份拼出，在无身份线程上先读只能读到 {@code null}（那条链会静默退化成空作用域）。
	 *
	 * <p>候选只来自 {@code ai:recovery:due} 的复合成员，记录身份随成员一起交下来；无归属成员在发现面
	 * （{@code allRunRecords()}）就被丢弃，不会进入本方法。因此本方法只处理带身份的记录。
	 *
	 * <p>作用域只包住这一次续跑调用，扫描循环（下一轮可能是另一个租户）不继承身份。
	 */
	private ResumeService.ResumeResult resumeWithRunIdentity(String runId, boolean takeover, long tenantId,
			long userId) {
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(tenantId, userId)) {
			return this.resumeService.resume(runId, takeover);
		}
	}

	/**
	 * 以该轮<b>记录身份</b>执行一次取消收尾（与 {@link #resumeWithRunIdentity} 同一条身份纪律）。
	 *
	 * <p>扫描线程没有请求身份，而 {@code cancelConfirmed} 的第一步 {@code store.get(runId)} 就要按
	 * 快照键（身份拼出）读取 —— 不在记录身份作用域里调用，只会读到 {@code null}。
	 */
	private ResumeService.CancelFinalize cancelWithRunIdentity(String runId, long tenantId, long userId) {
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(tenantId, userId)) {
			return this.resumeService.cancelConfirmed(runId);
		}
	}

	/**
	 * 以该轮<b>记录身份</b>消费持久化的 {@code expiresAt}（F2）：任何实例都可做，收敛动作本身
	 * 由 {@link ConfirmGate#convergeExpiredParkedFrontend(String)} 用既有 {@code expireFrontend}
	 * + 决策 guard 完成（同一张卡只赢一次）。
	 *
	 * @return 本拍收敛的条目数（0 = 没有到期条目/读不可知）
	 */
	private int expireParkedWithRunIdentity(String runId, long tenantId, long userId) {
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(tenantId, userId)) {
			return this.confirmGate == null ? 0 : this.confirmGate.convergeExpiredParkedFrontend(runId);
		}
		catch (RuntimeException ex) {
			log.warn("runtime scan park expiry convergence failed runId={} (no state change; retry next tick)", runId, ex);
			return 0;
		}
	}

	/** 调度线程绝不能被单轮异常杀死：异常只留痕，下一拍照常。 */
	private void sweepQuietly() {
		try {
			sweep();
		}
		catch (Throwable ex) {
			log.warn("runtime dead-run sweep tick failed (no state guess; retry next tick)", ex);
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
	private boolean isDeadWithoutDriver(Map<String, Object> run, String status, long nowMs, long zombieAgeMs) {
		String runId = String.valueOf(run.get("runId"));
		boolean leaseHeld;
		try {
			LeaseService leases = this.store.leaseService();
			// 判死口径必须用严格读取口：isHeld 吞异常返回 false 会把"读不出来"当成"没人持有"
			// ⇒ 仍有租约的活轮被判死接管（双驱动）。异常由下面 catch 折成 fail-closed。
			// 租约键由身份拼出：按<b>索引成员里的记录身份</b>取键（扫描线程没有身份；用线程身份取键
			// 会落到另一套键面 ⇒ 恒"无人持有" ⇒ 活跃轮被判死并接管 = 双驱动）。
			leaseHeld = leases != null && leases.isHeldStrict(leaseKeyOf(run, runId));
		}
		catch (Exception ex) {
			if (LogSampling.canAcquire("startup-resume-exception-catch")) log.warn("runtime scan read resume lease failed runId={} (treating as active, no takeover)", runId, ex);
			leaseHeld = true;
		}
		boolean liveInProcess;
		try {
			// 精确重载：把<b>记录身份</b>一并交下去 —— 扫描线程没有身份，`hasLiveSubscriber(runId)`
			// 只能把"任一身份桶里有这一轮的真实连接"当成判活证据；同 runId 属于两个身份时，
			// 那一证据可能来自另一身份（误判活只晚一拍，但也没必要把身份维度丢掉）。
			liveInProcess = this.registry != null
					&& this.registry.hasLiveSubscriber(runId, identityField(run, "tenantId"),
							identityField(run, "userId"));
		}
		catch (Exception ex) {
			if (LogSampling.canAcquire("startup-resume-exception-catch")) log.warn("runtime scan read local subscription failed runId={} (treating as active, no takeover)", runId, ex);
			liveInProcess = true;
		}
		long updatedAtMs = asLong(run.get("updatedAtMs"));
		// 发现面的 activeAtMs = max(快照写入, 最后一条非心跳业务帧) —— 与 RunLiveness 的第 2/3 参同义
		long lastActivityAtMs = asLong(run.get("activeAtMs"));
		return RunLiveness.isDead(status, updatedAtMs, lastActivityAtMs, leaseHeld, liveInProcess, zombieAgeMs,
				nowMs);
	}

	/**
	 * 发现态条目的接管结论（<b>启动扫描与运行期扫描共用</b>）：{@link ResumeService#listRunsForRecovery()}
	 * 已经把"取消 / 终态 / 未决外部输入 / 僵尸 RUNNING"算成字段，这里只做一次归类，
	 * 避免两条入口各写一套 if 链而慢慢走偏。
	 */
	private enum Discovery {

		/** 可以交给续跑漏斗（是否真的驱动仍由租约/会话门/待决条目在漏斗里决定）。 */
		TAKEABLE,

		/**
		 * 取消旗标（Redis 为准）。<b>启动扫描</b>不拾起（重启不复活被取消的轮）；<b>运行期扫描</b>在
		 * 快照非终态时改走取消收尾（{@code cancelConfirmed}，F3）—— 取消是已确认的事实，
		 * 驱动进程死后必须有执行体把它落成终态。
		 */
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
		String status = ObjectUtils.toString(rawStatus, (String) null);
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
	 * 发现条目里的<b>记录身份</b>字段（{@code tenantId}/{@code userId}，由 {@code listRuns} 从
	 * {@code ai:recovery:due} 的复合成员交下来）。缺字段 ⇒ {@code 0}（旧快照/无归属成员：没有身份可恢复，
	 * 不猜、也不沿用扫描线程身份）。
	 */
	private static long identityField(Map<String, Object> run, String key) {
		return asLong(run.get(key));
	}

	/** 该轮续跑权租约键（按记录身份）。 */
	private static String leaseKeyOf(Map<String, Object> run, String runId) {
		return RunStore.runScopedKey(RunStore.LEASE_RUN_PREFIX, identityField(run, "tenantId"),
				identityField(run, "userId"), runId);
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
