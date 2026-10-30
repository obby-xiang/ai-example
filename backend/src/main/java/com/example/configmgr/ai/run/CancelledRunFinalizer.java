package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.LocalRunKey;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.TenantUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <b>取消收尾的接管者</b>：把"已确认取消、但写权限这一拍不可确认"的收尾交棒给一个
 * <b>不被本轮驱动线程（Reactor 上游已 dispose）牵连</b>的执行体，由它<b>重新取租</b>后走
 * 既有的 {@link ResumeService#cancelConfirmed(String)}（→ {@code cancelParked} → 取消终态）。
 *
 * <h2>为什么需要独立执行体</h2>
 * 主 CI run 40211303032 / AIE2E-W-30：用户在首个 {@code FRONTEND} 工具刚收到请求帧时取消，
 * 取消从 {@code ConfirmGate#await} 的等待循环出来（该轮<b>没有</b> {@code approval_park}/
 * {@code tool_call_park}）。日志只证明一件事：<b>一次在飞的 Redis 命令被打断</b>
 * （{@code Redis command interrupted}，工具线程），它把 {@code LeaseService} 的
 * <b>整实例</b>健康位打假，而自证恢复探针有 1s 节流 —— 4ms 之后的驱动线程
 * {@code cancelTerminal} 因此必然读到 {@link LeaseService.LeaseAuthority#UNAVAILABLE}
 * 而被写门拒绝，终帧被同一判据丢弃；之后<b>没有任何执行体再试一次</b>
 * ⇒ 快照停在 SUSPENDED、前端没有终帧。
 *
 * <p><b>不要把它读成"Redis 故障"</b>：同一进程 7ms 前的另一轮刚打过
 * {@code 租约设施自证恢复（PING 成功）}。本类不假设 Redis 坏掉，也不改通用健康检查；
 * 只把"一次不可确认的拒写被当成最终结论"改成<b>有界重试的交棒</b>。
 *
 * <h2>红线（不允许被本类放宽）</h2>
 * <ul>
 * <li>只做取消收尾：入口 {@link ResumeService#cancelConfirmed(String)} 在取消事实不成立或
 *     不可见时<b>直接返回</b>（交回本类按未知重试），不读历史、不重建、<b>不发起模型/工具</b>；</li>
 * <li>租约在别的实例手里（{@code LEASE_HELD_ELSEWHERE}）⇒ 不抢、不覆盖，按 TTL 之后再试；</li>
 * <li>有界：次数沿用 {@code app.ai.park.max-dispatch-retries}；指数退避走
 *     {@code app.ai.park.dispatch-retry-delay}，租约在别处时走 {@code app.ai.lease.retry-delay}
 *     （缺省 35s ⇒ 15 次上限的实际窗口约 525s；有终态保护，这段窗口不会造成改写）。用尽只 ERROR 留痕；</li>
 * <li>同一 run 同时只有一次交棒在飞（{@link #inFlight}），避免重试风暴与双终帧。</li>
 * </ul>
 */
@Slf4j
@Component
public class CancelledRunFinalizer {

	/** 取消收尾交棒的既有触发原因（日志/取证用；不参与判定）。 */
	public static final String CAUSE_PERSIST_DENIED = "CANCEL_PERSIST_DENIED";

	/** park 退出时写权限已不可确认（{@code ConfirmGate} 的交棒原因）。 */
	public static final String CAUSE_PARK_EXIT = "PARK_CANCEL_UNWRITABLE";

	/**
	 * {@code POST /api/ai/cancel/{runId}} 的交棒原因：取消旗标已写，但这一轮<b>不存在</b>进程内
	 * 触发点（驱动线程已死/在别的实例）—— 必须由交棒执行体重新取租后走
	 * {@link ResumeService#cancelConfirmed(String)} 收敛，不能只留一个旗标。
	 */
	public static final String CAUSE_REQUEST = "REQUEST_CANCEL";

	/**
	 * 延迟重试的调度缝（默认 = 守护线程单线程调度器）。
	 *
	 * <p>为什么是接口：用例要断言"这一拍不可确认 ⇒ 排一次重试；恢复后重试成功"，
	 * 真线程只能 sleep 真实时间（慢且不稳）。生产路径就是
	 * {@code schedule(task, 2s/4s/…/30s)}（与续跑派发同一个 {@code app.ai.park.*} 口径）。
	 */
	interface Scheduler {

		AutoCloseable schedule(Runnable task, Duration delay);

	}

	private static final Scheduler DEFAULT_SCHEDULER = defaultScheduler();

	private static Scheduler defaultScheduler() {
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "ai-cancel-finalize-retry");
			thread.setDaemon(true);
			return thread;
		});
		return (task, delay) -> {
			long millis = delay == null ? 0L : Math.max(1L, delay.toMillis());
			var future = executor.schedule(task, millis, TimeUnit.MILLISECONDS);
			return () -> future.cancel(false);
		};
	}

	/**
	 * 取消收尾的进程内互斥（按 runId 分条）。
	 *
	 * <p>为什么需要：交棒执行体与驱动线程可能同时收尾同一轮（交棒刚取到租，驱动线程的写门又恰好
	 * 恢复）。判据必须是"读快照判终态"与"写终态"的<b>原子</b>组合，否则两边都读到 SUSPENDED
	 * ⇒ 两条终帧。条带化（而不是 Map）避免"每 run 一个监视器"的常驻增长。
	 */
	private static final int MONITOR_STRIPES = 64;

	private final Object[] finalizeMonitors = new Object[MONITOR_STRIPES];

	private final ObjectProvider<ResumeService> resumeServiceProvider;

	private final Executor executor;

	private final Scheduler scheduler;

	private final AiProperties properties;

	/**
	 * 轮次快照读取面（身份来源）：交棒跑在触发池/定时重试线程上，那里没有请求身份。
	 * {@code null} = 轻装配/旧用例构造（无身份可恢复，作用域为空）。
	 */
	private final RunStore store;

	/** 在飞的交棒（同一 run 只允许一次；重试任务通过同一集合自我续命）。 */
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

	/** 已尝试次数（用尽即停，不无限重排）。 */
	private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

	@Autowired
	public CancelledRunFinalizer(ObjectProvider<ResumeService> resumeServiceProvider,
			@Qualifier("aiResumeTrigger") Executor executor, AiProperties properties, RunStore store) {
		this(resumeServiceProvider, executor, properties, store, DEFAULT_SCHEDULER);
	}

	/** 兼容构造（无快照读取面：身份作用域为空，用于纯交棒行为用例）。 */
	CancelledRunFinalizer(ObjectProvider<ResumeService> resumeServiceProvider, Executor executor,
			AiProperties properties) {
		this(resumeServiceProvider, executor, properties, null, DEFAULT_SCHEDULER);
	}

	/** 包内构造（用例注入可手动推进的调度器；不改生产装配形状）。 */
	CancelledRunFinalizer(ObjectProvider<ResumeService> resumeServiceProvider, Executor executor,
			AiProperties properties, Scheduler scheduler) {
		this(resumeServiceProvider, executor, properties, null, scheduler);
	}

	/** 包内构造（身份 + 可手动推进的调度器）。 */
	CancelledRunFinalizer(ObjectProvider<ResumeService> resumeServiceProvider, Executor executor,
			AiProperties properties, RunStore store, Scheduler scheduler) {
		this.resumeServiceProvider = resumeServiceProvider;
		this.executor = executor;
		this.properties = properties;
		this.store = store;
		this.scheduler = scheduler == null ? DEFAULT_SCHEDULER : scheduler;
		for (int i = 0; i < MONITOR_STRIPES; i++) {
			this.finalizeMonitors[i] = new Object();
		}
	}

	/**
	 * 取消收尾的进程内互斥对象（同一身份 + 同一 run 恒返回同一个）。
	 *
	 * <p>条带的键是 {@link LocalRunKey}（身份 + runId）：A、B 的同名轮次落进同一条带只是<b>串行化</b>
	 * （损失并行度），不会共享任何状态 —— 状态表本身按同一把键分条。
	 */
	public Object finalizeMonitor(String runId) {
		int hash = runId == null ? 0 : LocalRunKey.of(runId).hashCode();
		return this.finalizeMonitors[(hash & 0x7fffffff) % MONITOR_STRIPES];
	}

	/**
	 * 交棒一次取消收尾。
	 *
	 * <p>幂等/去重：同一<b>身份 + run</b> 已有交棒在飞时返回 {@code false}（不重复派发；正在飞的那次会
	 * 自己决定"成功 / 重试 / 用尽"）。交棒本身不阻塞调用线程，也不读快照。
	 *
	 * <p>去重粒度为什么必须带身份：runId 只在<b>一轮</b>内唯一，两个租户各自生成同形 runId 是合法且
	 * 必然的。按裸 runId 去重会让 B 的取消收尾被 A 的在飞任务<b>静默拒绝</b> —— B 的终帧与终态都没人落。
	 *
	 * @return 本次是否真的派发了一次
	 */
	public boolean handOff(String runId, String cause) {
		if (runId == null || runId.isBlank()) {
			return false;
		}
		// 身份先定（交棒线程上还有身份 / 快照还没建时用提交线程身份），再拼本地键 —— 键必须与
		// 执行体、重试、终态清理用的那把完全一致。
		TenantUser identity = recordIdentityNow(runId);
		String key = LocalRunKey.of(identity, runId);
		if (!this.inFlight.add(key)) {
			log.debug("取消收尾已在交棒中，本次不重复派发 runId={} cause={}", runId, cause);
			return false;
		}
		this.attempts.put(key, new AtomicInteger());
		submit(runId, key, cause, identity);
		return true;
	}

	/**
	 * <b>交棒时刻</b>抓取这一轮的身份，随任务携带到执行线程。
	 *
	 * <p>为什么不在执行体里现读快照：交棒执行体跑在 {@code aiResumeTrigger} / 重试调度线程上，那里没有
	 * 请求身份，而快照键由身份拼出 —— "先 {@code store.get} 再 {@code useContext}"在无身份线程上恒读到
	 * {@code null} ⇒ 空作用域 ⇒ 取消收尾永远看不到这一轮（重试一次都不可用）。
	 *
	 * <p>记录身份优先；快照尚未建立时退回提交线程身份；快照存在但无归属（0/0）⇒ {@code null}，
	 * 不沿用调用者身份。
	 */
	private TenantUser recordIdentityNow(String runId) {
		TenantUser current = RequestContextUtils.currentOrNull();
		if (this.store == null) {
			return current;
		}
		try {
			RunSnapshot snapshot = this.store.get(runId);
			if (snapshot != null) {
				return RunIdentityScope.usable(snapshot.getTenantId(), snapshot.getUserId())
						? new TenantUser(snapshot.getTenantId(), snapshot.getUserId()) : null;
			}
		}
		catch (RuntimeException ex) {
			log.debug("抓取取消收尾身份时读快照失败 runId={}（退回提交线程身份）：{}", runId, ex.getMessage());
		}
		return current;
	}

	private void submit(String runId, String key, String cause, TenantUser identity) {
		try {
			this.executor.execute(() -> attempt(runId, key, cause, identity));
		}
		catch (RejectedExecutionException ex) {
			// 触发池饱和：交棒动作本身很短，排一次重试即可 —— 但同样受 maxDispatchRetries 约束
			// （M-3：旧实现这条路径绕过上限，池满时会无界重排）。
			log.warn("取消收尾交棒未派发（触发池饱和）runId={}：{}", runId, ex.getMessage());
			rescheduleOrGiveUp(runId, key, cause, false, identity);
		}
		catch (RuntimeException ex) {
			log.warn("取消收尾交棒派发失败 runId={}：{}", runId, ex.getMessage());
			rescheduleOrGiveUp(runId, key, cause, false, identity);
		}
		catch (Error ex) {
			// 派发阶段就抛 Error（执行器实现异常/OOM）：交棒位必须释放，否则后续交棒永久被去重拒绝。
			finish(key);
			throw ex;
		}
	}

	/**
	 * 轮终态的进程内清理（N5）：释放本 run 的交棒位与已尝试次数。
	 *
	 * <p>为什么要显式接口：交棒去重（{@link #inFlight}）与重试预算（{@link #attempts}）都是
	 * <b>进程内</b>状态，交棒成功后不会自行消失 —— 终态之后若不清，同一进程里既留着一个
	 * 永不释放的去重位（下一次同 runId 交棒被静默拒绝），也留着一条无用的计数。
	 *
	 * <p><b>幂等且不改变取消语义</b>：未交棒过 / 已清理 / {@code runId=null} 都不报错；
	 * 只清这两个进程内表，<b>不碰</b>租约（{@code LeaseService} 的 ended/epoch）、帧册、快照与
	 * {@code ai:cancel:<tenantId>:<userId>:<runId>} 取消事实 —— 清理不等于宣告完成，也不会触发任何交棒或驱动。
	 *
	 * <p><b>Error 路径也覆盖</b>：{@link #attempt} 在执行体抛 {@link Error} 时会先 {@code finish} 再
	 * 上抛；本方法则是终态收尾方的兜底入口（终态写成功/失败后都可调用）。
	 */
	public void forget(String runId) {
		if (runId == null || runId.isBlank()) {
			return;
		}
		finish(LocalRunKey.of(runId));
	}

	private void attempt(String runId, String key, String cause, TenantUser identity) {
		try {
			runAttempt(runId, key, cause, identity);
		}
		catch (Error ex) {
			// Error（OOM/断言失败等）若不释放交棒位，该 run 会永久 inFlight：后续交棒全被静默拒绝，
			// 重试链也无人重启。先清进程内状态再把 Error 原样上抛（不吞、不改写语义）。
			finish(key);
			throw ex;
		}
	}

	private void runAttempt(String runId, String key, String cause, TenantUser identity) {
		ResumeService service = this.resumeServiceProvider == null ? null
				: this.resumeServiceProvider.getIfAvailable();
		if (service == null) {
			log.info("取消收尾交棒放弃 runId={}（本实例无续跑服务：无 AI key 的降级装配）", runId);
			finish(key);
			return;
		}
		ResumeService.CancelFinalize outcome;
		try {
			// 身份 = 交棒时刻抓到的<b>记录身份</b>（随任务携带），不在执行线程上现读快照猜身份。
			try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
				outcome = service.cancelConfirmed(runId);
			}
		}
		catch (RuntimeException ex) {
			log.warn("取消收尾交棒执行异常 runId={} cause={}：{}", runId, cause, ex.getMessage());
			rescheduleOrGiveUp(runId, key, cause, false, identity);
			return;
		}
		if (outcome == null) {
			rescheduleOrGiveUp(runId, key, cause, false, identity);
			return;
		}
		switch (outcome) {
			case FINALIZED, ALREADY_TERMINAL, NO_RUN -> {
				log.info("取消收尾交棒完成 runId={} outcome={} cause={}", runId, outcome, cause);
				finish(key);
			}
			// 租约在别的实例手里：不抢、不覆盖；按租约 TTL 之后再确认一次（holder 死了也能接管）。
			case LEASE_HELD_ELSEWHERE -> rescheduleOrGiveUp(runId, key, cause, true, identity);
			// 取消事实不可见 / 取消状态不可读 / 设施不可用 / 同进程已有执行体 / 会话门被占 /
			// 写门仍拒写：一律"这拍没落定"，有界重试；**绝不**宣告完成（M-6），**绝不**驱动。
			default -> rescheduleOrGiveUp(runId, key, cause, false, identity);
		}
	}

	private void rescheduleOrGiveUp(String runId, String key, String cause, boolean leaseHeldElsewhere,
			TenantUser identity) {
		int attempt = nextAttempt(key);
		int max = this.properties.getPark().getMaxDispatchRetries();
		if (max <= 0 || attempt > max) {
			this.inFlight.remove(key);
			this.attempts.remove(key);
			log.error("取消收尾在 {} 次交棒后仍未落定 runId={} cause={}（取消事实仍在 ai:cancel:<tenantId>:<userId>:<runId>；"
					+ "下一次取消/续跑/启动扫描会重新触发）", max, runId, cause);
			return;
		}
		reschedule(runId, key, cause, leaseHeldElsewhere ? leaseRetryDelay() : delayFor(attempt), identity);
	}

	private void reschedule(String runId, String key, String cause, Duration delay, TenantUser identity) {
		try {
			this.scheduler.schedule(() -> attempt(runId, key, cause, identity), delay);
		}
		catch (RuntimeException ex) {
			this.inFlight.remove(key);
			this.attempts.remove(key);
			log.warn("取消收尾重试未排上 runId={}：{}", runId, ex.getMessage());
		}
		catch (Error ex) {
			finish(key);
			throw ex;
		}
	}

	private void finish(String key) {
		this.inFlight.remove(key);
		this.attempts.remove(key);
	}

	private int nextAttempt(String key) {
		AtomicInteger counter = this.attempts.computeIfAbsent(key, ignored -> new AtomicInteger());
		return counter.incrementAndGet();
	}

	/** 与续跑派发同一套指数退避（{@code app.ai.park.dispatch-retry-delay/max-delay}）。 */
	private Duration delayFor(int attempt) {
		Duration base = this.properties.getPark().getDispatchRetryDelay();
		Duration cap = this.properties.getPark().getDispatchRetryMaxDelay();
		if (base == null) {
			base = Duration.ofSeconds(2);
		}
		if (cap == null) {
			cap = Duration.ofSeconds(30);
		}
		long capMs = Math.max(1L, cap.toMillis());
		long delayMs = Math.max(1L, base.toMillis());
		for (int i = 1; i < attempt && delayMs < capMs; i++) {
			delayMs = Math.min(capMs, delayMs * 2);
		}
		return Duration.ofMillis(delayMs);
	}

	/** 租约在别处时的重试间隔 = 租约 TTL + 余量（与 {@code ConfirmGate#scheduleLeaseRetry} 同口径）。 */
	private Duration leaseRetryDelay() {
		return this.properties.getLease().getRetryDelay();
	}

}
