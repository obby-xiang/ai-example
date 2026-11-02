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
 * （{@link SessionPurgePendingStore#scheduleVerify} 重开为 0），失败沿用同一退避/上限、耗尽后同样只重排；</li>
 * <li>每会话最多 {@value #MAX_ATTEMPTS} 次自动尝试；退避由 {@link SessionPurgePendingStore} 指数封顶；
 * <b>耗尽且不再自动执行</b> ⇒ owner-CAS 把 nextAt 直接推到 24h + 确定性抖动未来，
 * 不再每 5 分钟回到到期队头挤占其它租户的候选窗口；手动 DELETE 经
 * {@link SessionPurgePendingStore#register} 重置为 now（耗尽的 attempts 归零），仍可推进；</li>
 * <li>调度周期 {@code 30s}，不做实时轮询；<b>周期注册本身可关</b>
 * （{@code app.ai.session.purge-retry.periodic-enabled}，缺省 {@code true} = 生产原样）——
 * 关掉只停"按周期自动推进"，{@link #runOnce()} 同步入口不受影响。</li>
 * </ul>
 *
 * <h2>跨 JVM 单飞</h2>
 * 复用 {@link LeaseService} 的 Redis 租约（{@value #LOCK_KEY}，TTL {@value #LOCK_TTL_SECONDS}s）；
 * 抢不到就跳过本轮，不猜、不抢。租约只包住一轮有界清理，不包业务事务。
 *
 * <h2>失败姿态</h2>
 * 任何 Redis 状态不可知/清理失败都<b>不 disarm</b>、不删 pending；保留给下一轮或显式
 * {@code DELETE /api/ai/session/{sid}}。只有删除主体 complete 且守卫收尾 + pending 移除都成功，
 * service 才会返回 complete=true。
 *
 * <p>发现面只有待重试到期 ZSET（{@code ai:purge:retry:due}，member = pending 键、score = nextAtMs）：
 * 台账登记/改期/失败/完成都在同一次调用里维护自己的到期分，不再有 LIST 队列或 SCAN 兜底。
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

	private final SessionPurgePendingStore pendingStore;

	private final AiSessionPurgeService purgeService;

	private final LeaseService leases;

	private final AiProperties properties;

	private final AtomicBoolean started = new AtomicBoolean();

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
			log.info("会话销毁重试周期注册已关闭（app.ai.session.purge-retry.periodic-enabled=false）："
					+ "runOnce 保持可调用");
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
		log.info("会话销毁重试调度已启动：period={} maxCandidates={} maxAttempts={}", PERIOD, MAX_CANDIDATES,
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
			log.warn("会话销毁自动重试本轮异常（不猜状态、不 disarm）：{}", ex.toString());
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
		LeaseService.AcquireResult lock = this.leases == null ? null
				: this.leases.acquire(LOCK_KEY, Duration.ofSeconds(LOCK_TTL_SECONDS));
		if (this.leases != null && (lock == null || !lock.acquired())) {
			return new RetryReport(false, 0, 0, 0, 0, 0);
		}
		try {
			List<SessionPurgePendingStore.Pending> due;
			try {
				due = this.pendingStore.listDue(nowMs, MAX_CANDIDATES);
			}
			catch (RuntimeException ex) {
				log.warn("会话销毁重试读取 pending 失败（本轮跳过，不猜状态）：{}", ex.getMessage());
				return new RetryReport(true, 0, 0, 0, 0, 0);
			}
			int skipped = 0;
			int attempted = 0;
			int completed = 0;
			int failed = 0;
			for (SessionPurgePendingStore.Pending pending : due) {
				if (pending.attempts() >= MAX_ATTEMPTS) {
					// I4：耗尽且不再自动执行 ⇒ 直接排到 24h + 抖动未来，不再每 5 分钟回到队头
					// 挤占其它租户的候选窗口。手动 DELETE 经 register 会重置为 now/attempts=0。
					skipped++;
					deferExhausted(pending, exhaustedRetireAt(nowMs, pending));
					continue;
				}
				// 身份按<b>记录</b>恢复（台账里的 tenantId/userId）：本线程没有请求身份，
				// 不恢复就会把"某个身份的销毁"当成 legacy 去删 —— 跨身份或删不干净。
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
							recordVerifyFailure(pending, "verify exception: " + ex.getMessage());
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
							// 失败沿用有限退避/MAX_ATTEMPTS（verifyAtMs 保留 ⇒ 下一次仍是记忆面复核）。
							recordVerifyFailure(pending, "memory verify: " + String.join("; ", verify.errors()));
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
						// 失败不 blind disarm；能记则记一次退避，记不了就保持 pending 原状。
						try {
							this.pendingStore.recordFailure(pending.sessionId(), pending.purgeId(),
									"retry exception: " + ex.getMessage());
						}
						catch (RuntimeException recordEx) {
							log.warn("记录会话销毁重试失败失败 sessionId={}（pending/guard 保持）：{}",
									pending.sessionId(), recordEx.getMessage());
						}
					}
				}
			}
			return new RetryReport(true, due.size(), attempted, completed, failed, skipped);
		}
		finally {
			if (this.leases != null && lock != null && lock.created() && lock.lease() != null) {
				try {
					this.leases.release(lock.lease());
				}
				catch (RuntimeException ex) {
					log.warn("释放会话销毁重试锁失败（TTL 会自清）：{}", ex.getMessage());
				}
			}
		}
	}

	/** 复核失败记一次退避（不删台账、不 disarm）；记录本身失败只 WARN，台账保持原状。 */
	private void recordVerifyFailure(SessionPurgePendingStore.Pending pending, String error) {
		try {
			this.pendingStore.recordFailure(pending.sessionId(), pending.purgeId(), error);
		}
		catch (RuntimeException ex) {
			log.warn("记录会话销毁记忆复核失败 sessionId={}（pending/guard 保持）：{}", pending.sessionId(),
					ex.getMessage());
		}
	}

	/**
	 * 台账身份的作用域（{@code null} = legacy 台账，不改变当前上下文）。
	 *
	 * <p>重试线程是定时线程，没有请求身份。每次处理一条台账都按<b>记录</b>恢复 {@code tenantId/userId}
	 * —— 即使当前不读这些字段，也统一口径（将来加读字段不会因为缺上下文 fail-closed 失败）。
	 */
	private static RequestContextUtils.SafeCloseable scopeOf(SessionPurgePendingStore.Pending pending) {
		TenantUser identity = pending.identityOrNull();
		return identity == null ? null : RequestContextUtils.useContext(identity.tenantId(), identity.userId());
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

	/** 把一条已耗尽的台账推到长未来（owner-CAS；失败只 WARN，台账保持原状）。 */
	private void deferExhausted(SessionPurgePendingStore.Pending pending, long nextAtMs) {
		// 改期写的是该台账自己的键（purgeId 条件 CAS）：必须按记录恢复身份，否则会去改 legacy 键。
		try (RequestContextUtils.SafeCloseable ignored = scopeOf(pending)) {
			this.pendingStore.defer(pending.sessionId(), pending.purgeId(), nextAtMs);
		}
		catch (RuntimeException ex) {
			log.warn("重排已耗尽的会话销毁重试项失败 sessionId={}（保持 pending 原状）：{}", pending.sessionId(),
					ex.getMessage());
		}
	}

}
