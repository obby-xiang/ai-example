package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.observe.RedisDegradation;
import com.example.configmgr.ai.tenant.TenantKeyScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI 轮取消标志注册表（S4.2 §2 {@code run/CancellationRegistry}）。
 *
 * <h2>ADR-8 修正③：Redis 为准，直通知仅加速</h2>
 * <ul>
 * <li><b>权威态</b>：{@code ai:cancel:<tenantId>:<userId>:<runId>}（STRING，值 = 发起取消的实例标识，
 * 带 TTL；键形见 {@link #cancelKey(String)} 与 {@link TenantKeyScope}）。取消端点只写这一个键 ——
 * 于是"取消那一轮的是另一个进程/另一个实例"同样成立，且进程重启后取消意图仍在（续跑会拒绝已取消的
 * 轮次，见 {@link ResumeService}）；<b>两个身份各自生成同一个 runId 是合法的</b> ⇒ 身份段进键后，
 * 跨租户同 runId 的取消旗标互不可见，进程内的 {@code flags}/{@code listeners} 也按同一把键分槽；</li>
 * <li><b>加速态</b>：进程内的 {@link AtomicBoolean} + 监听者集合。取消方与运行方同进程时，
 * 监听者立即被唤起（{@link StreamWatchdog} 据此当场断开上游、{@code ConfirmGate} 据此立刻解开
 * 挂起闸门），不必等下一个轮询节拍；跨进程时监听者为空，靠<b>轮询</b>发现 ——
 * 轮询点只有两处且都在"分片/事件边界"：流式看门狗每 {@value StreamWatchdog#TICK_MILLIS}ms 一次
 * （等价于"事件边界"；生产装配里这次 Redis 读跑在专用有界调度器 {@code aiCancelPoll} 上，
 * 不占判定节拍的线程），确认门/前端工具的等待循环每 {@value com.example.configmgr.ai.gate.ConfirmGate#HEARTBEAT_SECONDS}s 一次
 * （等价于"分片边界"）。</li>
 * </ul>
 *
 * <h2>降级</h2>
 * Redis 不可用（异常）时，本地已置位的取消仍然确定（{@link #cancelState(String)} = {@code CANCELLED}）；
 * 本地未置位而 Redis 读不出来时是 {@code UNKNOWN}（A5）—— "读不出来"不等于"未取消"，不折叠成布尔值。
 * 5s 退避期同样返回 {@code UNKNOWN}，不把"没访问 Redis"伪装成"未取消"。
 * <p><b>"取消早于 register + Redis 写失败"的窄窗</b>：取消端点在运行方登记之前到达、偏偏那时
 * Redis 写不进去 —— 本地没有取消位、Redis 也没有事实，用户点了停止却无处可查。{@code cancel}
 * 因此在写失败时记一条<b>有 TTL（{@value #DEGRADED_CANCEL_TTL_MS}ms）、有上限
 * （{@value #DEGRADED_CANCEL_CAP} 条）</b>的降级事实；{@code register} 到达时把它补置位到本地
 * 取消位，{@code cancelState} 在有效期内直接答 {@code CANCELLED}。过期即遗忘，不恢复无界 sticky
 * 表，也不在 Redis 恢复后压过权威值（本地位一旦置位，语义本来就是"已确定取消"）。
 * 降级事实（WARN / {@code degraded} / 统一降级计数）照旧保留，不静默。
 * <p>退避只由<b>设施级</b>失败<b>新开</b>：本线程被取消/中断引起的读失败（因果链里有 {@code InterruptedException}）
 * 只让那一拍是 {@code UNKNOWN}，不会把 {@code degraded} 从 false 翻成 true —— 否则一个被取消的轮次能把整实例的
 * 取消判定锁进 5s UNKNOWN，让紧接着的另一个轮在工具边界误判为"基础设施不可用"（W-30 · run 40232440965）。
 * 若此前已有真实故障在退避，这一拍仍按既有节流等待（本批不改节流实现）。
 *
 * <h2>键的清理</h2>
 * 取消标志<b>故意不随轮终态删除</b>：它要在 TTL 内继续可见（取证），并且"取消后立刻重启"
 * 时仍然拦得住续跑。TTL = {@code app.ai.session-ttl}（缺省 24h，与会话同寿；超时面治理批①
 * 第 11 项改述 —— 原为 {@code total-budget × 2 = 600s}，见 {@link #cancelFlagTtl()}）。
 */
@Slf4j
@Component
public class CancellationRegistry {

	public static final String CANCEL_PREFIX = "ai:cancel:";

	private final StringRedisTemplate redis;

	private final AiProperties properties;

	private final ConcurrentMap<String, AtomicBoolean> flags = new ConcurrentHashMap<>();

	private final ConcurrentMap<String, Set<Runnable>> listeners = new ConcurrentHashMap<>();

	private final AtomicLong redisPolls = new AtomicLong();

	private final AtomicLong redisConfirmations = new AtomicLong();

	private final AtomicLong localSignals = new AtomicLong();

	private volatile boolean degraded;

	/** 降级期的轮询节流（见 {@link #redisValue(String)}）。 */
	private final AtomicLong lastDegradedPollAtMs = new AtomicLong();

	/** 降级期两次 Redis 轮询之间的最少间隔（避免"每拍都撞一次超时"）。 */
	private static final long DEGRADED_POLL_BACKOFF_MS = 5000L;

	/**
	 * <b>降级取消事实</b>（{@code slot → 过期时刻}）：取消已经发生、但 Redis 写入失败的窄窗记录。
	 *
	 * <p>为什么需要它：{@code cancel()} 可能早于 {@code register()}（取消端点在运行方登记之前到达），
	 * 而那一刻 Redis 正好不可用 —— 旧实现既不建本地 flag（未注册），Redis 里也没有事实，用户已经点了
	 * "停止"却没有任何地方记得。本表把"已取消"记在进程内，等 {@code register()} 到达时补置位。
	 *
	 * <p><b>有界</b>：每条带 <b>TTL</b>（{@value #DEGRADED_CANCEL_TTL_MS}ms，覆盖登记抖动窗口），
	 * 且总量封顶 {@value #DEGRADED_CANCEL_CAP} 条（满了不新增，只记 WARN）。它不是第二张无界 sticky
	 * 表，也不会在 Redis 恢复后继续压过权威值 —— 过期即遗忘。
	 */
	private final ConcurrentMap<String, Long> degradedCancels = new ConcurrentHashMap<>();

	/** 降级取消事实的存活期（毫秒）：只覆盖"取消早于 register / register 稍后到达"的窄窗。 */
	private static final long DEGRADED_CANCEL_TTL_MS = 120_000L;

	/** 降级取消事实的条数上限（有界；只服务窄窗，不随轮次总量增长）。 */
	private static final int DEGRADED_CANCEL_CAP = 4096;

	public CancellationRegistry(StringRedisTemplate redis, AiProperties properties) {
		this.redis = redis;
		this.properties = properties;
	}

	/**
	 * 取消标志的键名（<b>带身份段</b>）：{@code ai:cancel:<tenantId>:<userId>:<runId>}。
	 *
	 * <p>键形来自 {@link TenantKeyScope#scoped(String, String)}（与 {@code ai:beat:} /
	 * {@code ai:activity:} 同族：run 锚点、不带 hash tag、只进单键命令）。没有身份上下文时是
	 * <b>旧键形</b> {@code ai:cancel:<runId>} —— 独立 legacy 命名空间，带身份的读写永不落到它上面。
	 */
	public static String cancelKey(String runId) {
		return TenantKeyScope.scoped(CANCEL_PREFIX, runId);
	}

	public String keyFor(String runId) {
		return cancelKey(runId);
	}

	/**
	 * 轮开始：登记进程内标志与监听者容器（不写 Redis —— 未取消的轮不该有键）。
	 *
	 * <p><b>必须用 {@code computeIfAbsent}</b>（性能/可靠性专项）：旧实现是
	 * {@code flags.put(slot, new AtomicBoolean(false))} —— 若取消已经先到（同实例另一线程、或
	 * {@link #cancelState(String)} 已回填加速态），这一笔会把<b>已经置位的取消位重置成 false</b>，
	 * 正在跑的轮随后读到"未取消"，取消丢失。{@code computeIfAbsent} 保留既有实例 ⇒ 取消位不会被
	 * 注册动作抹掉。
	 *
	 * <p>同时消费<b>降级取消事实</b>：若取消早于本进程登记、且当时 Redis 写失败（见类注释"降级"），
	 * 这里把它补置位到刚建立的本地取消位 —— 否则该轮会带着"用户已停止"的事实开跑。
	 */
	public void register(String runId) {
		String slot = keyFor(runId);
		this.flags.computeIfAbsent(slot, key -> new AtomicBoolean(false));
		this.listeners.putIfAbsent(slot, ConcurrentHashMap.newKeySet());
		// 取消早于本进程 register、且当时 Redis 写失败：把降级事实补置位到本地取消位上
		// （一次性取走；TTL 内未登记则自然遗忘，不会变成 sticky）。
		if (consumeDegradedCancel(slot) && markLocal(runId)) {
			this.localSignals.incrementAndGet();
		}
	}

	/** 最近一次取消标志读取失败的原因（退避期返回 UNKNOWN 时用于保留 cause）。 */
	private volatile Throwable lastReadFailure;

	/**
	 * 取消判定的三态（A5）：读失败是 {@link CancelState#UNKNOWN}，不是"未取消"。
	 *
	 * <p>本地已置位（{@link #cancel(String, String)} 或监听者）时是确定的 {@code CANCELLED}，
	 * 不依赖 Redis —— 保留单实例加速/降级通道；只有"本地未取消 + Redis 读不出来"才是未知。
	 *
	 * <p>Minor 5：成功从 Redis 读到取消事实时，<b>只对已 register 的轮</b>回填本地加速态；
	 * 续跑扫描/清单观察到的其它轮不建本地 flag（否则进程内 {@code flags} 单调增长且永久 sticky）。
	 */
	public CancelState cancelState(String runId) {
		String slot = keyFor(runId);
		AtomicBoolean flag = this.flags.get(slot);
		if (flag != null && flag.get()) {
			return CancelState.CANCELLED;
		}
		if (degradedCancelActive(slot)) {
			// 进程内已记下"取消发生过"（当时 Redis 写失败）：这是确定事实，不必等 Redis 恢复。
			return CancelState.CANCELLED;
		}
		RedisRead read = readRedisFlag(runId);
		if (read.failed()) {
			RedisDegradation.record(RedisDegradation.A5_CANCEL_UNKNOWN);
			return CancelState.UNKNOWN;
		}
		if (read.value() != null) {
			if (flag != null) {
				this.redisConfirmations.incrementAndGet();
				markLocal(runId);
			}
			return CancelState.CANCELLED;
		}
		return CancelState.NOT_CANCELLED;
	}

	/**
	 * 发起取消：先唤起本进程监听者（加速），再写 Redis（权威）。
	 *
	 * <p><b>未注册的轮不建本地条目</b>（性能/可靠性专项）：取消端点可以点名任意 runId
	 * （跨进程、已结束、或根本没在本实例跑）。旧实现的 {@code markLocal} 会
	 * {@code flags.computeIfAbsent(...)} 建一个永久 sticky 的本地 flag —— 一个长期运行的实例
	 * 会被"别人轮次的取消"慢慢撑大，且这些条目没有 TTL、也不会有 {@link #unregister} 来清。
	 * 现在只对<b>已注册</b>的槽置位；未注册的轮完全靠 Redis 标志（{@code cancelState} 读到再回填，
	 * 那时也只在已注册槽上回填，见 {@link #cancelState(String)}）。
	 *
	 * @return 本次调用产生的取消事实（供端点返回体取证）
	 */
	public CancelResult cancel(String runId, String requestedBy) {
		boolean woke = markLocal(runId);
		boolean redisWritten = writeRedisFlag(runId, requestedBy);
		if (!redisWritten) {
			// 权威面没写成：把"已取消"记进有 TTL、有上限的降级表。
			// 若 register 稍后才到（取消早于登记的窄窗），register 会把它补置位到本地取消位。
			rememberDegradedCancel(keyFor(runId));
		}
		if (woke) {
			this.localSignals.incrementAndGet();
		}
		return new CancelResult(redisWritten, woke, runId);
	}

	/** 轮终态：清进程内状态（Redis 标志留着，见类注释）。 */
	public void unregister(String runId) {
		String slot = keyFor(runId);
		this.flags.remove(slot);
		this.degradedCancels.remove(slot);
		Set<Runnable> set = this.listeners.remove(slot);
		if (set != null) {
			set.clear();
		}
	}

	/**
	 * 登记一个"被取消时立即执行"的监听者（加速通道；跨进程时不会被调用）。
	 *
	 * @return 注销句柄（轮终态必须关闭）
	 */
	public AutoCloseable onCancel(String runId, Runnable listener) {
		String slot = keyFor(runId);
		Set<Runnable> set = this.listeners.computeIfAbsent(slot, key -> ConcurrentHashMap.newKeySet());
		set.add(listener);
		return () -> {
			Set<Runnable> current = this.listeners.get(slot);
			if (current != null) {
				current.remove(listener);
			}
		};
	}

	public Map<String, Object> status(String runId) {
		String slot = keyFor(runId);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("runId", runId);
		out.put("key", slot);
		RedisRead read = readRedisFlag(runId);
		out.put("redisFlag", read.value());
		AtomicBoolean flag = this.flags.get(slot);
		boolean degradedCancel = degradedCancelActive(slot);
		out.put("localFlag", flag != null && flag.get());
		out.put("degradedCancel", degradedCancel);
		out.put("redisReadFailed", read.failed());
		if (read.failed()) {
			out.put("cancelState", CancelState.UNKNOWN.name());
		}
		else if ((flag != null && flag.get()) || degradedCancel) {
			out.put("cancelState", CancelState.CANCELLED.name());
		}
		else {
			out.put("cancelState", read.value() != null ? CancelState.CANCELLED.name()
					: CancelState.NOT_CANCELLED.name());
		}
		out.put("registered", flag != null);
		out.put("listeners", this.listeners.containsKey(slot) ? this.listeners.get(slot).size() : 0);
		out.put("redisPolls", this.redisPolls.get());
		out.put("cancelReadFailures", RedisDegradation.count(RedisDegradation.A5_CANCEL_READ));
		out.put("degraded", this.degraded);
		return out;
	}

	public boolean degraded() {
		return this.degraded;
	}

	public long polls() {
		return this.redisPolls.get();
	}

	public long confirmations() {
		return this.redisConfirmations.get();
	}

	public long localSignals() {
		return this.localSignals.get();
	}

	/** 当前正在登记（可能仍在跑）的轮数。 */
	public int registeredRuns() {
		return this.flags.size();
	}

	// ── 内部 ────────────────────────────────────────────────────────────────

	/**
	 * 置位加速态并唤起监听者；返回是否"此前未置位、本次置位"。
	 *
	 * <p><b>只对已注册的槽置位</b>：未注册的 runId 不建 {@code flags} 条目（见
	 * {@link #cancel(String, String)} 的注）。监听者容器若存在（{@link #onCancel} 先于
	 * {@code register} 登记的极窄路径），仍照常唤起 —— 唤醒是"通知"，不该被"没有加速位"挡住。
	 */
	private boolean markLocal(String runId) {
		String slot = keyFor(runId);
		AtomicBoolean flag = this.flags.get(slot);
		boolean newly = flag != null && flag.compareAndSet(false, true);
		Set<Runnable> set = this.listeners.get(slot);
		if (set != null) {
			for (Runnable listener : set) {
				try {
					listener.run();
				}
				catch (Exception ex) {
					log.debug("cancel listener failed runId={}: {}", runId, ex.getMessage());
				}
			}
		}
		return newly;
	}

	/**
	 * 记一条"取消已发生、但 Redis 没写成"的降级事实（{@link #DEGRADED_CANCEL_TTL_MS} 后自然遗忘）。
	 *
	 * <p>有界：先清过期项；仍达上限（{@value #DEGRADED_CANCEL_CAP}）时不新增，只 WARN ——
	 * 这是窄窗补偿，不允许它变成无界 sticky 表。写失败本身已经在 {@link #degraded} 上留痕。
	 */
	private void rememberDegradedCancel(String slot) {
		long now = System.currentTimeMillis();
		purgeExpiredDegradedCancels(now);
		if (this.degradedCancels.size() >= DEGRADED_CANCEL_CAP && !this.degradedCancels.containsKey(slot)) {
			log.warn("degraded cancel records at cap {}, not adding this one (run stays UNKNOWN, not faked as not cancelled) slot={}",
					DEGRADED_CANCEL_CAP, slot);
			return;
		}
		this.degradedCancels.put(slot, now + DEGRADED_CANCEL_TTL_MS);
	}

	/** 取走一条降级取消事实（{@code register} 补置位用）：过期的不算。 */
	private boolean consumeDegradedCancel(String slot) {
		Long expiresAt = this.degradedCancels.remove(slot);
		return expiresAt != null && expiresAt > System.currentTimeMillis();
	}

	/** 该 slot 是否仍有一条未过期的降级取消事实（过期项顺手清掉）。 */
	private boolean degradedCancelActive(String slot) {
		Long expiresAt = this.degradedCancels.get(slot);
		if (expiresAt == null) {
			return false;
		}
		if (expiresAt > System.currentTimeMillis()) {
			return true;
		}
		this.degradedCancels.remove(slot, expiresAt);
		return false;
	}

	/** 清掉已过期的降级取消事实（只在新写入/登记/查询时触发，不做后台线程）。 */
	private void purgeExpiredDegradedCancels(long nowMs) {
		this.degradedCancels.entrySet().removeIf(entry -> entry.getValue() <= nowMs);
	}

	private RedisRead readRedisFlag(String runId) {
		// §13.1 降级期的退避：Redis 不可用时，同步 GET 会阻塞到 spring.data.redis.timeout（默认 3s），
		// 而看门狗每秒一拍 —— 若不做节流，一个不可用的 Redis 会把看门狗线程池按在超时上。
		// 退避期间不访问 Redis，但状态是"未知"而不是"未取消"（A5）：调用方必须知道这一拍没有权威答案。
		// 恢复后 ≤ 本间隔即重新开始轮询，语义不变（Redis 仍为准）。
		if (this.degraded
				&& System.currentTimeMillis() - this.lastDegradedPollAtMs.get() < DEGRADED_POLL_BACKOFF_MS) {
			// 退避期不访问 Redis，但仍是 UNKNOWN；调用方（cancelState）会计数，这里留 debug 供排障。
			log.debug("cancel flag read in 5s backoff, returning UNKNOWN this tick runId={}", runId);
			return RedisRead.unknown(this.lastReadFailure);
		}
		this.lastDegradedPollAtMs.set(System.currentTimeMillis());
		this.redisPolls.incrementAndGet();
		try {
			Object value = this.redis.opsForValue().get(keyFor(runId));
			this.degraded = false;
			this.lastReadFailure = null;
			return RedisRead.value(value);
		}
		catch (Exception ex) {
			// 退避期也要计数（Minor 1）：否则一次持续故障只在首次翻转时留下一个"无 cause、只有一次"
			// 的事实，排障时既看不到频率也看不到根因。
			RedisDegradation.record(RedisDegradation.A5_CANCEL_READ, ex);
			this.lastReadFailure = ex;
			// 本线程被取消/中断引起的读失败是<b>局部信号</b>（取消一轮会 dispose 上游，boundedElastic
			// 上的阻塞 GET 真的会被 interrupt）：这一拍仍是 UNKNOWN，但<b>不把 degraded 从 false 翻成
			// true</b>，因此不会因它新开一个 5s <b>全实例</b>退避。否则紧接着的另一个轮会在工具边界读到
			// UNKNOWN（W-30 · run 40232440965：上一轮取消 320ms 后，816aa484… 的第 1 次尝试就撞在
			// 上一轮失败点亮的退避上）⇒ 可重试失败 ⇒ 重试把轮次跑成 DONE。
			// 判据与 {@link LeaseService#isLocalThreadInterrupt(Throwable)} 同源（因果链里有 InterruptedException）：
			// 真正的设施故障（连接失败 / 查询超时 / CROSSSLOT）链上没有它，照旧降级。
			// <b>已知窄残留（m1，本批只改注释）</b>：若此前已因真实故障处于退避（degraded 已为 true），
			// 本次读在 try 之前已经刷新了节流锚点，这一拍会顺延既有退避 —— 它不解除已有退避，只是不再新增一次。
			if (LeaseService.isLocalThreadInterrupt(ex)) {
				log.debug("cancel flag read interrupted on this thread (this tick only; no instance-wide backoff): {}", ex.getMessage());
				return RedisRead.unknown(ex);
			}
			if (!this.degraded) {
				this.degraded = true;
				log.warn("cancel flag read failed, degraded to in-process cancel "
						+ "(single-instance only, cross-instance ineffective): {}", ex.getMessage());
			}
			return RedisRead.unknown(ex);
		}
	}

	/** 取消标志的一次读取：{@code failed=true} = 读失败（未知），与"读成功且值为 null"区分。 */
	private record RedisRead(Object value, boolean failed, Throwable cause) {

		private static RedisRead value(Object value) {
			return new RedisRead(value, false, null);
		}

		private static RedisRead unknown(Throwable cause) {
			return new RedisRead(null, true, cause);
		}

	}

	private boolean writeRedisFlag(String runId, String requestedBy) {
		Duration ttl = cancelFlagTtl();
		try {
			this.redis.opsForValue().set(keyFor(runId), requestedBy == null ? "unknown" : requestedBy, ttl);
			return true;
		}
		catch (Exception ex) {
			this.degraded = true;
			log.warn("cancel flag write to Redis failed (in-process only) runId={}: {}", runId, ex.getMessage());
			return false;
		}
	}

	/**
	 * 取消标志的存活期 = <b>{@code app.ai.session-ttl}（缺省 24h）</b>，与会话同寿。
	 *
	 * <p>
	 * 超时面治理批① 第 11 项（用户裁决「取消信号随会话共亡」）：原值 {@code total-budget × 2 = 600s}
	 * 只说"10 分钟内可见"，而会话本身有 24h —— 于是一次挂起 15 分钟后取消的轮次，其取消标志可能已
	 * 先于会话消失（续跑/重挂在 10min~24h 之间读到的是"未取消"，会把已取消的轮重新捡起来）。
	 * 取消标志与 run 快照/待决条目/帧归档同寿；它同时登记在
	 * {@link SessionKeyRegistry} 的读侧续期面（{@code touchActiveRun} 会像其他会话级键一样整额续期）。
	 */
	private Duration cancelFlagTtl() {
		Duration sessionTtl = this.properties.getSessionTtl();
		return sessionTtl != null ? sessionTtl : Duration.ofHours(24);
	}

	/**
	 * 一次取消的落地形态（端点返回体）。
	 *
	 * @param redisWritten 权威标志是否写进 Redis（false = 降级，仅进程内可见）
	 * @param wokeInProcess 本实例是否<b>本次新置位</b>了该轮的进程内取消位（未注册的轮不建本地条目
	 * ⇒ 这里为 false；运行方在别的进程/已死时同样为 false，由轮询/续跑拒绝兜住）
	 */
	public record CancelResult(boolean redisWritten, boolean wokeInProcess, String runId) {
	}

	/** 取消判定的三态（A5）：{@code UNKNOWN} = Redis 读失败且本地没有确定的取消事实。 */
	public enum CancelState {

		/** 确定已取消（本地加速态或 Redis 命中）。 */
		CANCELLED,

		/** 确定未取消（Redis 读成功且无该键）。 */
		NOT_CANCELLED,

		/** 未知（Redis 读失败 / 降级退避期）：调用方不得按"未取消"继续做不可逆动作。 */
		UNKNOWN

	}

	/**
	 * 取消状态未知（A5）：Redis 读失败，且本地没有"已取消"的确定事实。
	 *
	 * <p>跨实例取消的权威态只在 Redis，读不出来时"未取消"是猜测；把猜测当事实会让另一个实例
	 * 发出的取消丢失（运行方一直跑到预算/超时）。需要"未知"这个中间态的调用方用
	 * {@link #cancelState(String)}（{@link CancelState#UNKNOWN}），不得把它折叠成"未取消"。
	 */
	public static class CancellationUnknownException extends com.example.configmgr.common.AiStateUnavailableException {

		private final String runId;

		public CancellationUnknownException(String runId, Throwable cause) {
			super("cancel state unknown (Redis read failed) runId=" + runId, cause);
			this.runId = runId;
		}

		public String runId() {
			return this.runId;
		}

	}

}
