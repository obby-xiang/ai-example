package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话销毁的<b>有界自动重试闭环</b>（{@code complete=false} 的服务端推进）。
 *
 * <h2>为什么需要</h2>
 * 销毁可能因活跃 run 延后或 Redis 部分失败而 {@code complete=false}。只靠客户端重试，用户关页
 * 就永远删不净；guard 若只有 session TTL，墓碑到期后同一个 sessionId 又能聊天并写回。
 * 因此 pending 是 durable 的（无 TTL），guard 同时看 pending；本类按固定周期把 pending 收敛到
 * complete（随后 guard 进入有限保留期）。
 *
 * <h2>有界</h2>
 * <ul>
 * <li>每轮最多 {@value #MAX_CANDIDATES} 个候选；单候选一次 {@code retry}；</li>
 * <li><b>fix4 复核态</b>：台账带 {@code verifyAtMs}（删除主体完成后的一次性记忆复核）时，
 * 到点只调 {@link AiSessionPurgeService#verifyMemoryOnly}（只碰 {@code chat:mem:*}），
 * <b>不</b>重跑整轮 {@code retry}；复核是独立阶段、自带 {@code attempts} 预算
 * （{@link SessionPurgePendingStore#scheduleVerify} 重开为 0），失败沿用同一退避/上限、耗尽后同样退役
 * （摘掉发现项、台账保留）；</li>
 * <li>每会话最多 {@value #MAX_ATTEMPTS} 次自动尝试；退避由 {@link SessionPurgePendingStore} 指数封顶；
 * <b>耗尽且不再自动执行</b> ⇒ owner-CAS 把 nextAt 推到 24h + 确定性抖动未来并<b>摘掉全局 due 成员</b>
 * （{@link SessionPurgePendingStore#retireExhausted}）—— durable 台账保留（守卫仍有效、绝不伪装完成），
 * 但不再每 5 分钟回到到期队头，也不再随永久失败会话撑大全局 ZSET；手动 DELETE 经
 * {@link SessionPurgePendingStore#register} 重置为 now（耗尽的 attempts 归零）并重新写入到期项，仍可推进；</li>
 * <li>调度周期 {@code 30s}，不做实时轮询；<b>周期注册本身可关</b>
 * （{@code app.ai.session.purge-retry.periodic-enabled}，缺省 {@code true} = 生产原样）——
 * 关掉只停"按周期自动推进"，{@link #runOnce()} 同步入口不受影响。</li>
 * </ul>
 *
 * <h2>跨 JVM 单飞</h2>
 * 复用 {@link LeaseService} 的 Redis 租约（{@value #LOCK_KEY}，TTL {@value #LOCK_TTL_SECONDS}s）；
 * 抢不到就跳过本轮，不猜、不抢。租约只包住一轮有界清理，不包业务事务。
 * <b>每轮 owner 唯一</b>（{@code <instanceId>:purge:<序号>}，见 {@link #runSeq}）：
 * {@link LeaseService} 的进程内视图按 {@code (key, owner)} 记账；唯一 owner 让每一轮独立续租、释放。
 * 资源锁 release 异常时该任本地视图由 LeaseService 条件清除，Redis 键按 TTL 收敛，
 * held/ended 不随周期执行次数增长。
 * <b>一轮执行期间按抢锁时的同一 TTL 续租</b>（{@code markDriving(..., LOCK_TTL_SECONDS)}）：
 * 单轮可能因 DB/文件清理超过 300s，只靠 TTL 到期会让锁在半途消失、另一实例并发跑同一批台账。
 * <b>续租带存活判据（C-62）</b>：本轮仍在跑、且当前候选没有超过
 * {@link #CANDIDATE_STALL_TIMEOUT_MS} 的推进窗口；判据转假 ⇒ 置 {@code stalled}、归还租约并停续租 ——
 * 清理线程卡死时不会把锁无限续下去，另一实例能在锁失效后接手同一批 durable 台账。
 * 判死之后<b>主循环不再处理剩余候选</b>（它们保持 due，交给下一轮/接管实例）：只有判死时正在执行的
 * 那一条会与新 owner 并发，不会把"卡死一次"扩散成整批候选的双跑窗口。
 * 结束时先 {@code stopDriving} 再 {@code release}（顺序反了会让续租任务把刚释放的锁写回去）。
 * 是否续租/释放只看“本轮是否真的拿到 lease”，<b>不再看 {@code created}</b>。
 *
 * <h2>失败姿态</h2>
 * 任何 Redis 状态不可知/清理失败都<b>不 disarm</b>、不删 pending；保留给下一轮或显式
 * {@code DELETE /api/ai/session/{sid}}。只有删除主体 complete 且守卫收尾 + pending 移除都成功，
 * service 才会返回 complete=true。
 *
 * <p>发现面只有待重试到期 ZSET（{@code ai:purge:retry:due}，member = pending 键、score = nextAtMs）：
 * 台账登记/改期/失败/完成都在同一次调用里维护自己的到期分，不再有 LIST 队列或 SCAN 兜底；
 * 自动尝试耗尽的台账会在这里被<b>摘掉</b>（{@link #retireExhausted}），因此发现面不随永久失败会话增长。
 */
@Slf4j
@Component
public class SessionPurgeRetryRunner {

	static final String LOCK_KEY = "ai:purge:retry:lock";

	static final long LOCK_TTL_SECONDS = 300L;

	static final Duration INITIAL_DELAY = Duration.ofSeconds(10);

	static final Duration PERIOD = Duration.ofSeconds(30);

	static final int MAX_CANDIDATES = 10;

	static final int MAX_ATTEMPTS = SessionPurgePendingStore.MAX_AUTO_ATTEMPTS;

	/** 耗尽且不再自动执行的台账：排到 24h 之后，不再按 5 分钟退避回到到期队头。 */
	static final long EXHAUSTED_RETIRE_BASE_MS = Duration.ofHours(24).toMillis();

	/** 长未来抖动的步长（15 分钟）。 */
	static final long EXHAUSTED_RETIRE_JITTER_STEP_MS = Duration.ofMinutes(15).toMillis();

	/** 抖动步数：按台账键确定性地散开 0..23 步（最多约 5h45m），避免同一时刻集体回队头。 */
	static final int EXHAUSTED_RETIRE_JITTER_STEPS = 24;

	/**
	 * <b>单条候选的最长执行预算</b>（C-62 的存活判据）。
	 *
	 * <p>续租不能只看"我还拿着 Lease 对象"：清理线程卡在 DB/文件/网络里时，定时续租会把 300s 的锁
	 * <b>无限</b>续下去，别的实例永远接不了手。这里给每条候选一个真实推进窗口：开跑前重置，
	 * 超过窗口没有任何推进 ⇒ 续租判据转假 ⇒ {@link LeaseService} 归还租约并停续租。
	 *
	 * <p>取值与锁 TTL 同量级（5 分钟）：正常一轮单条候选远小于它；真卡死时最多再等一个续租周期
	 * （{@code app.ai.lease.renew-interval}，判据每 3 拍才复算一次）就会放锁。
	 */
	static final long CANDIDATE_STALL_TIMEOUT_MS = Duration.ofMinutes(5).toMillis();

	private final SessionPurgePendingStore pendingStore;

	private final AiSessionPurgeService purgeService;

	private final LeaseService leases;

	private final AiProperties properties;

	private final AtomicBoolean started = new AtomicBoolean();

	/**
	 * 本轮单飞 owner 的序号：每轮 {@code runOnce} 取 {@code <instanceId>:purge:<序号>}，<b>一轮一个</b>。
	 *
	 * <p>每轮独立 owner 使续租与释放严格对应当前任。资源锁 release 异常时 LeaseService 会清掉
	 * 该任本地视图，Redis 键按 TTL 过期；不会留下随轮次累积的无 TTL 内存记录。
	 */
	private final AtomicLong runSeq = new AtomicLong();

	private volatile ScheduledExecutorService scheduler;

	/**
	 * 直接构造（单测）：周期开关取生产缺省，用例一律走 {@link #runOnce()} 同步驱动，不等调度。
	 */
	public SessionPurgeRetryRunner(SessionPurgePendingStore pendingStore, AiSessionPurgeService purgeService,
			LeaseService leases) {
		this(pendingStore, purgeService, leases, new AiProperties());
	}

	@Autowired
	public SessionPurgeRetryRunner(SessionPurgePendingStore pendingStore, AiSessionPurgeService purgeService,
			LeaseService leases, AiProperties properties) {
		this.pendingStore = pendingStore;
		this.purgeService = purgeService;
		this.leases = leases;
		this.properties = properties;
	}

	/** 一轮有界清理的结果（测试/观测；不改变业务语义）。 */
	public record RetryReport(boolean lockAcquired, int candidates, int attempted, int completed, int failed,
			int skipped) {
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onReady() {
		if (!this.started.compareAndSet(false, true)) {
			return;
		}
		if (!this.properties.getSession().getPurgeRetry().isPeriodicEnabled()) {
			// 只停周期注册：到期项都在 durable ZSET 里，runOnce() 仍可由调用方同步驱动。
			log.info("Session purge retry periodic registration disabled "
					+ "(app.ai.session.purge-retry.periodic-enabled=false); runOnce stays callable");
			return;
		}
		ScheduledExecutorService svc = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "ai-session-purge-retry");
			thread.setDaemon(true);
			return thread;
		});
		this.scheduler = svc;
		svc.scheduleWithFixedDelay(this::runOnceSafely, INITIAL_DELAY.toMillis(), PERIOD.toMillis(),
				TimeUnit.MILLISECONDS);
		log.info("Session purge retry scheduler started: period={} maxCandidates={} maxAttempts={}", PERIOD, MAX_CANDIDATES,
				MAX_ATTEMPTS);
	}

	@PreDestroy
	public void shutdown() {
		ScheduledExecutorService current = this.scheduler;
		if (current != null) {
			current.shutdownNow();
		}
	}

	private void runOnceSafely() {
		try {
			runOnce();
		}
		catch (Throwable ex) {
			// 调度线程绝不能被单轮异常杀死；状态保持在 Redis（pending/guard 不 disarm）。
			log.warn("Session purge auto-retry round threw (state not guessed, not disarmed): {}", ex.toString());
		}
	}

	/**
	 * 执行一轮有界重试（测试可直接调用，不必等待调度）。
	 */
	public RetryReport runOnce() {
		return runOnce(System.currentTimeMillis());
	}

	/** 可注入时钟的一轮清理（测试确定性；生产走 {@link #runOnce()}）。 */
	public RetryReport runOnce(long nowMs) {
		LeaseService.Lease lease = null;
		// C-62：续租的<b>存活判据</b>（在续租线程上执行）：
		// 本轮仍在跑（finally 会置假）且当前候选没有超过"推进窗口"。
		// 单条候选卡死 ⇒ 判据转假 ⇒ LeaseService 归还租约，另一实例可接手同一批 durable 台账。
		AtomicBoolean roundLive = new AtomicBoolean(true);
		AtomicLong progressDeadlineMs = new AtomicLong(System.currentTimeMillis() + CANDIDATE_STALL_TIMEOUT_MS);
		// C-62 判死标记：一旦某条候选超过推进窗口，本轮不再续租，也<b>不再处理剩余候选</b>。
		// 只置一次（CAS）：续租线程与主循环线程可能同时判到。
		AtomicBoolean stalled = new AtomicBoolean(false);
		if (this.leases != null) {
			// 每轮唯一 owner（见 runSeq 的注）：release 失败留下的旧 (key, owner) 视图不会让下一轮
			// 命中 created=false，因此"续租 / 释放"在每一轮都是必须做的两件事。
			String owner = this.leases.instanceId() + ":purge:" + this.runSeq.incrementAndGet();
			LeaseService.AcquireResult lock = this.leases.acquire(LOCK_KEY, Duration.ofSeconds(LOCK_TTL_SECONDS),
					owner);
			if (lock == null || !lock.acquired()) {
				return new RetryReport(false, 0, 0, 0, 0, 0);
			}
			lease = lock.lease();
			if (lease != null) {
				try {
					// 与抢锁相同的 TTL：默认 lease-ttl（30s）会把 300s 的锁在下一拍缩短。
					// 存活判据见上方 roundLive/progressDeadlineMs：卡死的一轮不再无限续租（C-62），
					// 并在判死时置 stalled —— 主循环随后停止处理剩余候选。
					this.leases.markDriving(lease, () -> roundLive.get() && !stallReached(stalled, progressDeadlineMs),
							null, Duration.ofSeconds(LOCK_TTL_SECONDS));
				}
				catch (RuntimeException ex) {
					// 续租登记失败不阻断本轮（锁仍是刚抢到的 300s）；finally 仍会 stop/release。
					log.warn("Session purge retry lock renew registration failed (round continues on the TTL already acquired): {}", ex.getMessage());
				}
			}
		}
		try {
			List<SessionPurgePendingStore.Pending> due;
			try {
				due = this.pendingStore.listDue(nowMs, MAX_CANDIDATES);
			}
			catch (RuntimeException ex) {
				log.warn("Session purge retry failed to read pending ledger (round skipped, state not guessed): {}", ex.getMessage());
				return new RetryReport(true, 0, 0, 0, 0, 0);
			}
			// 取到候选 = 本轮有真实推进：重开推进窗口（否则"慢但仍在推进"的轮次会被判卡死）。
			progressDeadlineMs.set(System.currentTimeMillis() + CANDIDATE_STALL_TIMEOUT_MS);
			int skipped = 0;
			int attempted = 0;
			int completed = 0;
			int failed = 0;
			int retired = 0;
			for (SessionPurgePendingStore.Pending pending : due) {
				// C-62 判死后的止血：剩余候选一条都不再碰（它们仍在 due ZSET，交给下一轮/接管实例）。
				// 判死时正在执行的那条留在上面的调用栈里自行收尾 —— 只有它承担"租约已放、对方可能也在跑"
				// 的并发窗口，不会把窗口扩散到整批候选。
				if (stallReached(stalled, progressDeadlineMs)) {
					log.warn("Session purge retry round declared stalled (C-62): stop processing remaining candidates (kept due for the next round/takeover instance)");
					break;
				}
				// 每处理一条（含退役）都重开推进窗口（C-62）：慢但仍在推进的轮次继续持锁；
				// 单条卡死超过窗口 ⇒ 续租判据转假 ⇒ 置 stalled、放锁，另一实例可接手。
				progressDeadlineMs.set(System.currentTimeMillis() + CANDIDATE_STALL_TIMEOUT_MS);
				if (pending.attempts() >= MAX_ATTEMPTS) {
					// I4/C37：耗尽且不再自动执行 ⇒ 推到 24h + 抖动未来并<b>摘掉全局 due 成员</b>。
					// durable 台账保留（守卫/人工入口都靠它），绝不在这里 complete 伪装成功。
					skipped++;
					retired++;
					retireExhausted(pending, exhaustedRetireAt(nowMs, pending));
					continue;
				}
				// 身份按<b>记录</b>恢复（台账里的 tenantId/userId）：本线程没有请求身份，
				// 不恢复就会按无身份键去删 —— 跨身份或删不干净。
				try (RequestContextUtils.SafeCloseable ignored = scopeOf(pending)) {
					if (pending.verifyAtMs() != null) {
						// fix4：这条台账的删除主体已完成，只剩一次"记忆面"复核（崩溃残留补偿）。
						// 到点只做 delete+staging+删后复核 —— 不重跑整轮 retry（那会再删 DB/附件）。
						attempted++;
						AiSessionPurgeService.MemoryVerify verify;
						try {
							verify = this.purgeService.verifyMemoryOnly(pending.sessionId(), pending.purgeId());
						}
						catch (RuntimeException ex) {
							failed++;
							log.warn("Session purge memory verify threw sessionId={} (durable pending kept): {}",
									pending.sessionId(), ex.getMessage());
							continue;
						}
						if (verify == null) {
							skipped++;
						}
						else if (verify.verified()) {
							completed++;
						}
						else {
							failed++;
						}
						continue;
					}
					attempted++;
					try {
						AiSessionPurgeService.PurgeResult result = this.purgeService.retry(pending.sessionId(),
								pending.purgeId(), "purge-retry");
						if (result == null) {
							skipped++;
						}
						else if (result.complete()) {
							completed++;
						}
						else {
							failed++;
						}
					}
					catch (RuntimeException ex) {
						failed++;
						log.warn("Session purge retry threw sessionId={} (durable pending kept): {}",
								pending.sessionId(), ex.getMessage());
					}
				}
			}
			if (retired > 0) {
				// C-38 容量告警：耗尽台账只有人工 DELETE 才回到自动推进；这里只留痕，不删事实。
				log.warn("Session purge retry retired {} exhausted ledger entries this round (durable ledger kept, "
						+ "global due member removed; a manual DELETE is required to re-enter auto-advance)", retired);
			}
			return new RetryReport(true, due.size(), attempted, completed, failed, skipped);
		}
		finally {
			// 先让存活判据转假（续租线程读它）：本轮已经结束，不再续租；无论下面 release 是否成功，
			// 都不会出现"线程已退出、续租任务还在续"的幽灵锁。
			roundLive.set(false);
			if (lease != null) {
				// 先停续租再释放：顺序反过来会出现"release 成功、续租任务下一拍又把键 SET 回去"的幽灵锁
				// （release 的脚本只删值匹配的那一任，但续租任务持有同一个 Lease 对象）。
				try {
					this.leases.stopDriving(LOCK_KEY, lease.owner());
				}
				catch (RuntimeException ex) {
					log.debug("Failed to stop session purge retry lock renewal (TTL self-clears): {}", ex.getMessage());
				}
				try {
					this.leases.release(lease);
				}
				catch (RuntimeException ex) {
					log.warn("Failed to release session purge retry lock (TTL self-clears): {}", ex.getMessage());
				}
			}
			// 是否释放只看"本轮是否拿到 lease"：owner 每轮唯一 ⇒ 不存在"重入拿到的别人的那一任"。
		}
	}

	/**
	 * C-62 判死：超过推进窗口仍无推进 ⇒ 置 {@code stalled}（CAS 只置一次）并返回 true。
	 *
	 * <p>两个调用点是<b>同一判据</b>：续租线程（判据转假 ⇒ {@link LeaseService} 归还租约）与主循环
	 * （判死 ⇒ 不再处理剩余候选）。没有租约（同步入口/降级）时续租线程不存在，主循环仍会在每条候选
	 * 之后用同一助手补判，判死语义不依赖续租调度是否在场。
	 */
	private static boolean stallReached(AtomicBoolean stalled, AtomicLong progressDeadlineMs) {
		if (stalled.get()) {
			return true;
		}
		if (System.currentTimeMillis() < progressDeadlineMs.get()) {
			return false;
		}
		if (stalled.compareAndSet(false, true)) {
			log.warn("Session purge retry candidate exceeded the progress window ({} ms) without progress: round declared "
					+ "stalled, renewal and remaining candidates stopped (in-flight candidate finishes on its own)", CANDIDATE_STALL_TIMEOUT_MS);
		}
		return true;
	}

	/**
	 * 台账身份的作用域。
	 *
	 * <p>重试线程是定时线程，没有请求身份。每次处理一条台账都按<b>记录</b>恢复 {@code tenantId/userId}；
	 * 记录没有可用身份即失败（不生成无身份键、不猜默认身份）。
	 */
	private static RequestContextUtils.SafeCloseable scopeOf(SessionPurgePendingStore.Pending pending) {
		TenantUser identity = pending.identity();
		return RequestContextUtils.useContext(identity.tenantId(), identity.userId());
	}

	/**
	 * 耗尽台账的"长未来"时刻：24h + 按台账键确定性的 0..23 步抖动。
	 *
	 * <p>确定性抖动（而不是随机数）让同一租户/会话每次都散开到同一刻度，测试与排障可复现；
	 * 不同台账的键不同，天然错开回队头的时刻。
	 */
	private static long exhaustedRetireAt(long nowMs, SessionPurgePendingStore.Pending pending) {
		int step = Math.floorMod(pending.key().hashCode(), EXHAUSTED_RETIRE_JITTER_STEPS);
		return nowMs + EXHAUSTED_RETIRE_BASE_MS + step * EXHAUSTED_RETIRE_JITTER_STEP_MS;
	}

	/**
	 * 把一条已耗尽的台账退役：推到长未来并摘掉全局 due 成员（owner-CAS；失败只 WARN，台账与
	 * 到期项都保持原状 —— 下一轮还会取到并重试，绝不静默删除事实）。
	 */
	private void retireExhausted(SessionPurgePendingStore.Pending pending, long nextAtMs) {
		// 改期写的是该台账自己的键（purgeId 条件 CAS）：必须按记录恢复身份。
		try (RequestContextUtils.SafeCloseable ignored = scopeOf(pending)) {
			this.pendingStore.retireExhausted(pending.sessionId(), pending.purgeId(), nextAtMs);
		}
		catch (RuntimeException ex) {
			log.warn("Failed to retire exhausted session purge retry entry sessionId={} (pending/due left as-is): {}",
					pending.sessionId(), ex.getMessage());
		}
	}

}
