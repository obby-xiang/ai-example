package com.example.configmgr.ai.session;

import com.example.configmgr.ai.run.LeaseService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
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
 * <li>每会话最多 {@value #MAX_ATTEMPTS} 次自动尝试；退避由 {@link SessionPurgePendingStore} 指数封顶；
 * <b>耗尽后只重排</b>（owner-CAS 把 nextAt 推到有界未来 + 移到队尾，不再自动执行第 11 次）——
 * 否则"永远 due、永远不执行"的条目会永久占满队头窗口，把其它会话饿死在候选预算之外；</li>
 * <li>启动/队列为空时 SCAN 兜底最多 {@value #MAX_RECOVERY_KEYS} 键；</li>
 * <li>调度周期 {@code 30s}，不做实时轮询。</li>
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
 * <p>发现面：启动时 SCAN 回填 + 队列表（{@code ai:purge:retry:queue}）；队列为空时再做一次
 * 有界 SCAN 兜底，避免“只靠一个易丢失的索引”。
 */
@Slf4j
@Component
public class SessionPurgeRetryRunner {

	static final String LOCK_KEY = "ai:purge:retry:lock";

	static final long LOCK_TTL_SECONDS = 300L;

	static final Duration INITIAL_DELAY = Duration.ofSeconds(10);

	static final Duration PERIOD = Duration.ofSeconds(30);

	static final int MAX_CANDIDATES = 10;

	static final int MAX_ATTEMPTS = 10;

	/** 耗尽项的重排最多两轮：第一轮把队头窗口里的耗尽项推到队尾，第二轮取到真正的候选。 */
	static final int MAX_DEFER_PASSES = 2;

	static final int MAX_RECOVERY_KEYS = 200;

	private final SessionPurgePendingStore pendingStore;

	private final AiSessionPurgeService purgeService;

	private final LeaseService leases;

	private final AtomicBoolean started = new AtomicBoolean();

	private volatile ScheduledExecutorService scheduler;

	public SessionPurgeRetryRunner(SessionPurgePendingStore pendingStore, AiSessionPurgeService purgeService,
			LeaseService leases) {
		this.pendingStore = pendingStore;
		this.purgeService = purgeService;
		this.leases = leases;
	}

	/** 一轮有界清理的结果（测试/观测；不改变业务语义）。 */
	public record RetryReport(boolean lockAcquired, int candidates, int attempted, int completed, int failed,
			int skipped, int recovered) {
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onReady() {
		if (!this.started.compareAndSet(false, true)) {
			return;
		}
		try {
			this.pendingStore.recoverOrphans(MAX_RECOVERY_KEYS);
		}
		catch (RuntimeException ex) {
			log.warn("会话销毁 pending 启动恢复失败（不阻塞启动；定时扫描继续）：{}", ex.getMessage());
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
			return new RetryReport(false, 0, 0, 0, 0, 0, 0);
		}
		try {
			List<SessionPurgePendingStore.Pending> due;
			try {
				due = this.pendingStore.listDue(nowMs, MAX_CANDIDATES);
			}
			catch (RuntimeException ex) {
				log.warn("会话销毁重试读取 pending 失败（本轮跳过，不猜状态）：{}", ex.getMessage());
				return new RetryReport(true, 0, 0, 0, 0, 0, 0);
			}
			int recovered = 0;
			if (due.isEmpty() && this.pendingStore.queueSize() == 0L) {
				recovered = Math.max(0, this.pendingStore.recoverOrphans(MAX_RECOVERY_KEYS));
				if (recovered > 0) {
					due = this.pendingStore.listDue(nowMs, MAX_CANDIDATES);
				}
			}
			// 已耗尽自动尝试的条目：owner-CAS 把 nextAt 推到有界未来并移到队尾，不再占本轮候选预算
			// （它们永远 due 又永远不执行，原地保留会永久占满队头窗口、饿死其它会话）。
			int skipped = 0;
			for (int pass = 0; pass < MAX_DEFER_PASSES && hasExhausted(due); pass++) {
				skipped += deferExhausted(due, nowMs);
				try {
					due = this.pendingStore.listDue(nowMs, MAX_CANDIDATES);
				}
				catch (RuntimeException ex) {
					log.warn("会话销毁重试重排后重取候选失败（本轮按已取到的处理）：{}", ex.getMessage());
					break;
				}
			}
			int attempted = 0;
			int completed = 0;
			int failed = 0;
			for (SessionPurgePendingStore.Pending pending : due) {
				if (pending.attempts() >= MAX_ATTEMPTS) {
					// 兜底：defer 写不进去（Redis 抖动）时仍不强行第 11 次，也不删台账。
					skipped++;
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
			return new RetryReport(true, due.size(), attempted, completed, failed, skipped, recovered);
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

	private static boolean hasExhausted(List<SessionPurgePendingStore.Pending> due) {
		return due.stream().anyMatch(pending -> pending.attempts() >= MAX_ATTEMPTS);
	}

	/** @return 成功重排（= 本轮跳过）的条数 */
	private int deferExhausted(List<SessionPurgePendingStore.Pending> due, long nowMs) {
		long nextAt = nowMs + SessionPurgePendingStore.MAX_BACKOFF_MS;
		int deferred = 0;
		for (SessionPurgePendingStore.Pending pending : due) {
			if (pending.attempts() < MAX_ATTEMPTS) {
				continue;
			}
			try {
				if (this.pendingStore.defer(pending.sessionId(), pending.purgeId(), nextAt)) {
					deferred++;
				}
			}
			catch (RuntimeException ex) {
				log.warn("重排已耗尽的会话销毁重试项失败 sessionId={}（保持 pending 原状）：{}", pending.sessionId(),
						ex.getMessage());
			}
		}
		return deferred;
	}

}
