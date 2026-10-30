package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.observe.AiTrace;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.TenantUser;
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
 * <h2>两个面：系统扫描 vs 请求面手工扫描</h2>
 * <ul>
 * <li><b>系统面</b>（应用启动的自动扫描、运行期扫描）：跑在无身份线程上，候选来自
 * {@code ai:runs} 的复合成员，逐轮按<b>该轮记录身份</b>推进 —— 不扫描租户列表、也不沿用线程身份。
 * 记录<b>没有可用身份</b>（pre-deploy 的 {@code 0/0} legacy 轮）时两条扫描都<b>不续跑</b>：
 * 续跑只会在无身份下驱动模型/工具，故一律记 {@link #LEGACY_NO_IDENTITY}（不伪造归属）。</li>
 * <li><b>请求面</b>（{@code POST /api/ai/startup-resume/scan}，线程有身份）：<b>只处理当前身份</b>的轮次。
 * 若候选里混入别的身份（列表实现变化、桩数据等），一律<b>既不续跑、也不进报告</b> —— runId 本身
 * 就是跨身份信息，不能借"跳过原因"回显。报告面（GET）同样按调用身份切片，
 * 见 {@link #lastScanForIdentity(TenantUser)}。</li>
 * </ul>
 *
 * <h2>运行期低频死轮扫描（批次8B / Task 12 Step 3）</h2>
 * 启动扫描每个实例只跑一拍，之后"死在调用中间 / 决策已落库却没人执行 / 重挂自愈那一下抖了"的轮次
 * 会一直挂着。{@link #sweep()} 按 {@code app.ai.resume.runtime-sweep-interval} 补一拍兜底：
 * 候选仍是 {@link ResumeService#listRuns()}，活/死仍是 {@link RunLiveness#isDead}（租约 + 本进程活订阅 +
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
	 * 系统扫描对<b>没有可用身份</b>的 legacy 轮次的明确跳过原因（{@code tenantId<=0} 或
	 * {@code userId<=0 且 != -1}，即 pre-deploy 的 {@code 0/0} 记录）。
	 *
	 * <p>这类轮次没有身份可恢复：{@link RunIdentityScope#open(long, long)} 只会清空线程身份，
	 * 续跑就在"谁都不是"的情况下驱动模型与工具。不伪造归属、也不沿用扫描线程身份 —— 只留事实。
	 */
	public static final String LEGACY_NO_IDENTITY = "LEGACY_NO_IDENTITY";

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
		// 请求面（线程有身份）只处理自己的轮次；系统面（无身份线程）按每条记录的记录身份推进。
		TenantUser caller = RequestContextUtils.currentOrNull();
		for (Map<String, Object> run : candidates) {
			String runId = String.valueOf(run.get("runId"));
			long recordTenantId = identityField(run, "tenantId");
			long recordUserId = identityField(run, "userId");
			if (caller != null && !sameIdentity(caller, recordTenantId, recordUserId)) {
				// 别人的轮次：不续跑、也不进报告（runId 与跳过原因都属于跨身份信息）。
				log.debug("启动扫描跳过非当前身份的轮次 runId={}（记录身份 {}/{}，调用身份 {}）", runId, recordTenantId,
						recordUserId, caller);
				continue;
			}
			if (!RunIdentityScope.usable(recordTenantId, recordUserId)) {
				// pre-deploy 的 0/0 legacy 轮：没有身份可恢复。续跑只会在无身份下驱动模型与工具
				// （RunIdentityScope.open 会清空线程身份），既跑不出用户那一轮也无归属可解释 ⇒ 不驱动、只留事实。
				Map<String, Object> skippedItem = new LinkedHashMap<>();
				skippedItem.put("runId", runId);
				skippedItem.put("tenantId", recordTenantId);
				skippedItem.put("userId", recordUserId);
				skippedItem.put("status", run.get("status"));
				skippedItem.put("reason", LEGACY_NO_IDENTITY);
				skippedItem.put("unresolvedExternal", run.get("unresolvedExternal"));
				skipped.add(skippedItem);
				log.info("启动扫描跳过无身份的 legacy 轮次 runId={}（reason={}，不驱动模型/工具）", runId,
						LEGACY_NO_IDENTITY);
				continue;
			}
			boolean zombie = Boolean.TRUE.equals(run.get("zombieRunning"));
			Discovery discovery = discoveryOf(run);
			if (discovery != Discovery.TAKEABLE) {
				Map<String, Object> skippedItem = new LinkedHashMap<>();
				skippedItem.put("runId", runId);
				// 报告项带记录身份：GET 面据此只回显当前身份的轮次（见 lastScanForIdentity）
				skippedItem.put("tenantId", recordTenantId);
				skippedItem.put("userId", recordUserId);
				skippedItem.put("status", run.get("status"));
				skippedItem.put("reason", scanSkipReason(discovery));
				skippedItem.put("unresolvedExternal", run.get("unresolvedExternal"));
				skipped.add(skippedItem);
				continue;
			}
			if (resumed.size() >= limit) {
				Map<String, Object> skippedItem = new LinkedHashMap<>();
				skippedItem.put("runId", runId);
				skippedItem.put("tenantId", recordTenantId);
				skippedItem.put("userId", recordUserId);
				skippedItem.put("reason", "MAX_ON_STARTUP_REACHED");
				skipped.add(skippedItem);
				continue;
			}
			// 日志链路批：启动扫描是"没有 HTTP 入口"的续跑路径，锚点只能在循环里逐轮装
			AiTrace.bind(runId, null);
			try {
				ResumeService.ResumeResult result = resumeWithRunIdentity(runId, zombie, recordTenantId,
						recordUserId);
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("runId", runId);
				item.put("tenantId", recordTenantId);
				item.put("userId", recordUserId);
				item.put("zombieTakeover", zombie);
				item.put("outcome", result.outcome().name());
				item.put("body", result.body());
				resumed.add(item);
			}
			catch (Exception ex) {
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("runId", runId);
				item.put("tenantId", recordTenantId);
				item.put("userId", recordUserId);
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
					&& sameIdentity(identity, asLong(map.get("tenantId")), asLong(map.get("userId")))) {
				owned.add((Map<String, Object>) map);
			}
		}
		return owned;
	}

	/**
	 * 记录身份是否等于给定身份：两侧都必须<b>可用</b>（{@code tenantId>0}、
	 * {@code userId>0 或 -1}）。{@code 0/0} 的 legacy 记录不属于任何身份 —— 不得被"恰好没有身份"
	 * 的调用方认领，也不得被请求面扫描拾起。
	 */
	private static boolean sameIdentity(TenantUser identity, long tenantId, long userId) {
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
	 * <h2>四条不再扩大语义的边界</h2>
	 * <ol>
	 * <li><b>无身份不驱动</b>：{@code tenantId/userId} 不可用的 legacy 轮没有身份可恢复，
	 * 一律记 {@link #LEGACY_NO_IDENTITY}、不调续跑（否则就是"谁都不是"地驱动模型与工具）；</li>
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
	 * 两条都按<b>记录身份</b>开作用域（不扫描租户列表），预算仍与续跑共用 {@code max-on-startup}。
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
		// F3：取消旗标 + 非终态 ⇒ 本拍走取消收尾（不是续跑）。与续跑分开记账，报告里可解释。
		List<Map<String, Object>> finalized = new ArrayList<>();
		// F2：本拍消费了持久化 expiresAt 的轮次（到期收敛已派发一次续跑）。
		List<Map<String, Object>> expired = new ArrayList<>();
		for (Map<String, Object> run : candidates) {
			String runId = String.valueOf(run.get("runId"));
			String status = run.get("status") == null ? null : String.valueOf(run.get("status"));
			long recordTenantId = identityField(run, "tenantId");
			long recordUserId = identityField(run, "userId");
			if (!RunIdentityScope.usable(recordTenantId, recordUserId)) {
				// 与启动扫描同一道门：pre-deploy 的 0/0 legacy 轮没有身份可恢复，
				// 续跑只会在无身份下驱动模型与工具 ⇒ 这拍不碰，只留事实（不伪造归属）。
				skipped.add(sweepSkipItem(runId, status, LEGACY_NO_IDENTITY));
				log.info("运行期扫描跳过无身份的 legacy 轮次 runId={}（reason={}，不驱动模型/工具）", runId,
						LEGACY_NO_IDENTITY);
				continue;
			}
			// ===== F3：取消旗标优先于"跳过" =====
			// 旧实现把 cancelled=true 直接记 CANCELLED 跳过：驱动进程死了就再没人写终态/终帧，
			// 连带会话销毁永远 deferred。取消是<b>已确认的事实</b>，不是"别碰"的理由 ——
			// 交给既有 cancelConfirmed（抢租约 + 校验取消事实 + 归档终帧幂等，绝不驱动模型）。
			if (Boolean.TRUE.equals(run.get("cancelled")) && !RunSnapshot.isTerminal(status)) {
				if (resumed.size() + finalized.size() + expired.size() >= limit) {
					skipped.add(sweepSkipItem(runId, status, "MAX_PER_SWEEP_REACHED"));
					continue;
				}
				ResumeService.CancelFinalize outcome;
				try {
					outcome = cancelWithRunIdentity(runId, recordTenantId, recordUserId);
				}
				catch (RuntimeException ex) {
					skipped.add(sweepSkipItem(runId, status, "CANCEL_EXCEPTION"));
					log.warn("运行期扫描的取消收尾异常 runId={}（下一拍重试）：{}", runId, ex.getMessage());
					continue;
				}
				if (outcome == ResumeService.CancelFinalize.FINALIZED
						|| outcome == ResumeService.CancelFinalize.ALREADY_TERMINAL
						|| outcome == ResumeService.CancelFinalize.NO_RUN) {
					Map<String, Object> item = new LinkedHashMap<>();
					item.put("runId", runId);
					item.put("tenantId", recordTenantId);
					item.put("userId", recordUserId);
					item.put("statusBeforeSweep", status);
					item.put("outcome", outcome.name());
					finalized.add(item);
				}
				else {
					// 租约在别处/会话门被占/这拍不可确认：不是终态，也不得因此驱动 ⇒ 下一拍再收尾。
					skipped.add(sweepSkipItem(runId, status, "CANCEL_" + outcome));
				}
				continue;
			}
			// ===== F2：消费持久化的 expiresAt =====
			// park 后的到期收敛只有原实例进程内定时器；实例被杀后没有任何读侧看 expiresAt。
			// 任一实例的扫描都可以做这件事（真前端工具才有时限；确认门/表单无上限，不消费）。
			if (!RunSnapshot.isTerminal(status) && this.confirmGate != null
					&& resumed.size() + finalized.size() + expired.size() < limit) {
				int converged = expireParkedWithRunIdentity(runId, recordTenantId, recordUserId);
				if (converged > 0) {
					Map<String, Object> item = new LinkedHashMap<>();
					item.put("runId", runId);
					item.put("tenantId", recordTenantId);
					item.put("userId", recordUserId);
					item.put("statusBeforeSweep", status);
					item.put("expired", converged);
					expired.add(item);
					// 到期收敛已派发一次续跑（系统裁决）：本拍不再重复续跑同一轮。
					continue;
				}
			}
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
				ResumeService.ResumeResult result = resumeWithRunIdentity(runId, takeover, recordTenantId,
						recordUserId);
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
		report.put("finalized", finalized);
		report.put("expired", expired);
		report.put("skipped", skipped);
		report.put("zombieAgeMs", zombieAgeMs);
		this.lastSweep = report;
		log.info("运行期死轮扫描完成：候选 {} 轮，受理 {} 轮，取消收尾 {} 轮，到期收敛 {} 轮，跳过 {} 轮（判死窗口 {}）",
				candidates.size(), resumed.size(), finalized.size(), expired.size(), skipped.size(),
				this.properties.getResume().getZombieAge());
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
	 * 以<b>该轮记录身份</b>执行一次续跑（启动扫描与运行期扫描的唯一入口）。
	 *
	 * <p>扫描跑在定时线程上，那里没有请求身份；身份也不能从"当前线程"或默认租户补。
	 * 身份的<b>来源是 {@code ai:runs} 的复合索引成员</b>（{@code tenantId/userId/runId}，
	 * 由 {@code ResumeService.listRuns()} 随条目交下来）—— 不是"先读快照再猜身份"：
	 * 快照键本身由身份拼出，在无身份线程上先读只能读到 {@code null}（那条链会静默退化成空作用域）。
	 *
	 * <p>旧快照 / 无归属成员（{@code 0/0}）没有身份可恢复：调用方（两条扫描）在进本方法<b>之前</b>就按
	 * {@link #LEGACY_NO_IDENTITY} 跳过 —— 绝不靠 {@link RunIdentityScope#open(long, long)} 清空身份后
	 * 硬着头皮续跑（那等于在"谁都不是"的情况下驱动模型与工具）。因此本方法只接受
	 * {@link RunIdentityScope#usable(long, long)} 为真的记录身份。
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
			log.warn("运行期扫描的 park 到期收敛失败 runId={}（不改任何状态，下一拍重试）：{}", runId, ex.getMessage());
			return 0;
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
			// 判死口径必须用严格读取口：isHeld 吞异常返回 false 会把"读不出来"当成"没人持有"
			// ⇒ 仍有租约的活轮被判死接管（双驱动）。异常由下面 catch 折成 fail-closed。
			// 租约键由身份拼出：按<b>索引成员里的记录身份</b>取键（扫描线程没有身份，用线程身份取到的是
			// legacy 租约 ⇒ 恒"无人持有" ⇒ 活跃轮被判死并接管 = 双驱动）。
			leaseHeld = leases != null && leases.isHeldStrict(leaseKeyOf(run, runId));
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
	 * 发现条目里的<b>记录身份</b>字段（{@code tenantId}/{@code userId}，由 {@code listRuns} 从
	 * {@code ai:runs} 的复合成员交下来）。缺字段 ⇒ {@code 0}（旧快照/无归属成员：没有身份可恢复，
	 * 不猜、也不沿用扫描线程身份）。
	 */
	private static long identityField(Map<String, Object> run, String key) {
		return asLong(run.get(key));
	}

	/** 该轮续跑权租约键（按记录身份；旧/无归属条目退回 legacy 键形）。 */
	private static String leaseKeyOf(Map<String, Object> run, String runId) {
		long tenantId = identityField(run, "tenantId");
		long userId = identityField(run, "userId");
		if (RunIdentityScope.usable(tenantId, userId)) {
			return RunStore.runScopedKey(RunStore.LEASE_RUN_PREFIX, tenantId, userId, runId);
		}
		return RunStore.legacyRunKey(RunStore.LEASE_RUN_PREFIX, runId);
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
