package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.LocalRunKey;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.common.sse.SseWriteBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内的轮次写出器注册表：{@code (身份, runId) → SseChatEmitter}（键见 {@link LocalRunKey}）。
 *
 * <p>
 * 为什么需要它：挂起发生在<b>官方循环内部</b>（{@code SpToolCallingManager} 的扩展点上），
 * 那里不在 HTTP 请求栈里，拿不到 controller 手里的响应对象；而 reattach 又要求后来者能挂到
 * 同一个 runId 上。于是本轮的全部帧写出一律经 {@link #of(String)} 取到同一个写出器。
 *
 * <p>
 * 重启后不存在旧实例的写出器：此时 {@link #of(String)} 惰性新建一个"零订阅者"的写出器，
 * 帧照样写进事件日志（{@link FrameStore}；生产 = Redis Stream），前端重挂时回放即可 ——
 * 这正是 M3 与 ADR-5 reattach 的咬合点。
 *
 * <p>
 * <b>B23L1 批①（2026-10-21）</b>：Spring 装配路径（{@code @Autowired} 六参构造）给写出器注入
 * 事件日志 + 唤醒发布 + 唤醒订阅三件套 ⇒ 单/多实例同一套代码；非 Spring/单测的三参构造
 * 退回兼容口径（LIST 写、不跨实例广播），既有帧序用例的确定性不受影响。
 *
 * <p>
 * T3-1：持有 AiProperties 的构造（Spring 装配路径）会用 {@code app.ai.sse.*} 配置并启用
 * 进程级共享有界投递池；显式传 {@code null} properties 的非 Spring/单测路径保持同步直执，帧序用例的确定性不受影响。
 */
@Slf4j
@Component
public class RunRegistry {

	/**
	 * <b>身份 + runId → 写出器条目</b>（键见 {@link LocalRunKey}）。
	 *
	 * <p>为什么键里必须带身份：runId 只在<b>一轮</b>内唯一，两个租户各自生成同形 runId 是合法且必然的。
	 * 旧实现按裸 runId 定键，于是 A、B 会拿到同一个写出器 —— A 的帧写进 B 的归档、A 的 {@code close}
	 * 收掉 B 的连接、A 的订阅让 B 也被判成"本进程有活连接"。无身份时键落在 legacy 命名空间
	 * （{@code {run:<runId>}}），那是<b>旧数据的独立桶</b>，不是"默认身份"。
	 */
	private final Map<String, EmitterEntry> emitters = new ConcurrentHashMap<>();

	/**
	 * 空闲回收窗口（默认 30 分钟）：条目「零订阅 + 不在写 + 非终态收尾」且<b>超过窗口没有任何
	 * {@code of()} 触达</b>时由空闲扫描摘掉。
	 *
	 * <p>为什么必须有一层时间窗：{@code events}/{@code confirm} 这类入口会为"当前没有连接"的轮次
	 * 惰性建条目（零订阅是常态，不是异常），只靠终态钩子/{@code close} 收不干净 —— 长跑实例上就是
	 * 每轮一条、只增不减。窗口给"刚建好、马上要挂订阅"的窄窗留出余量：回收一个还有人在用的写出器，
	 * 会让同一轮在进程内出现两个写出器（帧号与扇出分家）。
	 */
	private static final long DEFAULT_IDLE_RECYCLE_AFTER_MS = java.time.Duration.ofMinutes(30).toMillis();

	/** 触发空闲扫描的最小间隔（默认 1 分钟；扫描在 {@link #of} 上摊还执行，不额外占线程）。 */
	private static final long DEFAULT_SWEEP_INTERVAL_MS = java.time.Duration.ofMinutes(1).toMillis();

	private volatile long idleRecycleAfterMs = DEFAULT_IDLE_RECYCLE_AFTER_MS;

	private volatile long sweepIntervalMs = DEFAULT_SWEEP_INTERVAL_MS;

	/** 上次空闲扫描的时刻（多线程只看它，谁 CAS 成功谁扫）。 */
	private final java.util.concurrent.atomic.AtomicLong lastSweepAtMs = new java.util.concurrent.atomic.AtomicLong(
			0L);

	private final RunStore store;

	private final ObjectMapper objectMapper;

	private final AiProperties properties;

	/**
	 * 本注册表创建写出器时使用的投递执行器。
	 *
	 * <p>
	 * <b>口径（S2-1 修复批）</b>：Spring 三参装配路径（{@code properties != null}）用进程级共享
	 * 有界投递池（异步，快慢订阅者隔离）；显式传 {@code null} 的非 Spring/单测路径<b>固定同步直执</b>，
	 * 不读进程静态池 —— 静态池一旦被 {@code @SpringBootTest} 装配，该路径就会由"同步直执"
	 * 悄悄变异步投递，同一 JVM 内出现测试顺序相关竞态（帧序用例要求"发完即到"）。
	 */
	private final Executor deliveryExecutor;

	/**
	 * 写出<b>超时预算</b>（包②：SSE 写侧超时预算）。
	 *
	 * <p>
	 * 口径与投递执行器一致（S2-1）：Spring 三参装配路径用<b>进程级弹性写出池</b> +
	 * {@code app.ai.sse.write-timeout}；显式传 {@code null} 的非 Spring/单测路径固定 {@link SseWriteBudget#direct()}
	 * —— 不读进程静态池，避免"同一 JVM 内测试顺序相关"（理由同 {@link #deliveryExecutor}）。
	 */
	private final SseWriteBudget writeBudget;

	/** G5：模型侧裁剪器（null = 不标注模型侧视图）。 */
	private final ToolResultLimiter resultLimiter;

	/** B23L1 批①：事件日志（Spring 装配路径恒为 {@link RedisFrameStore}）。 */
	private final FrameStore frames;

	/** B23L1 批①：唤醒发布面。 */
	private final FrameWakeBus wakeBus;

	/** B23L1 批①：唤醒订阅面（按连接 subscribe/unsubscribe）。 */
	private final FrameWakeListener wakeListener;

	/**
	 * 非 Spring/单测装配（三参）：事件日志走 {@code ai:events:} LIST 兼容口径、不跨实例唤醒。
	 *
	 * <p>
	 * 保留它是为了让 {@code new RunRegistry(store, mapper, null)} 这类 conformance 装配
	 * （外置状态面 = {@code InMemoryRunStore}）零改动；Spring 装配<b>不会</b>选这个构造器
	 * （{@code @Autowired} 在六参构造上）。
	 */
	public RunRegistry(RunStore store, ObjectMapper objectMapper, AiProperties properties) {
		this(store, objectMapper, properties, null, null, null);
	}

	/**
	 * 生产装配（B23L1 批①）：写出器一律拿到 Redis Stream 事件日志 + wake 扇出。
	 *
	 * <p>
	 * {@code frames/wakeBus/wakeListener} 允许为 null（局部上下文/测试）：为 null 时写出器
	 * 退回兼容口径（LIST 写、不跨实例广播），语义与 B1 收官一致。
	 */
	public RunRegistry(RunStore store, ObjectMapper objectMapper, AiProperties properties, FrameStore frames,
			FrameWakeBus wakeBus, FrameWakeListener wakeListener) {
		this(store, objectMapper, properties, frames, wakeBus, wakeListener, null);
	}

	/**
	 * 生产装配（G5 起多注入模型侧裁剪器）：{@code tool_result} 帧据此挂上"模型视图是否被截断"。
	 *
	 * <p>{@code resultLimiter} 允许为 null（局部上下文/测试）：为 null 时写出器不标注模型侧视图，
	 * 帧面其余语义不变。
	 */
	@Autowired
	public RunRegistry(RunStore store, ObjectMapper objectMapper, AiProperties properties, FrameStore frames,
			FrameWakeBus wakeBus, FrameWakeListener wakeListener, ToolResultLimiter resultLimiter) {
		this.store = store;
		this.objectMapper = objectMapper;
		this.properties = properties;
		this.frames = frames;
		this.wakeBus = wakeBus;
		this.wakeListener = wakeListener;
		// 非 Spring 装配（三参/六参）也要有模型侧标注：裁剪器是无状态、纯配置驱动的，
		// 这里按同一份 AiProperties 现造一个，判定与 Spring 注入的 bean 完全一致。
		this.resultLimiter = resultLimiter != null || properties == null ? resultLimiter
				: new ToolResultLimiter(properties);
		if (properties != null) {
			// 超时面治理批① 第 3 项：把绑定后的写侧预算一并登记，供 /api/ai/health 如实回报
			SseChatEmitter.configureSharedDeliveryPool(properties.getSse().getDeliveryPoolSize(),
					properties.getSse().getDeliveryPoolSize() * 64,
					properties.getSse().getWriteTimeout().toMillis());
		}
		// 池必须先装配再取用（configureSharedDeliveryPool 幂等，首装配者胜）
		this.deliveryExecutor = properties != null ? SseChatEmitter.sharedDeliveryExecutorOrDirect() : Runnable::run;
		this.writeBudget = properties != null
				? SseWriteBudget.on(SseWriteBudget.sharedElasticWriterPool(),
						properties.getSse().getWriteTimeout().toMillis())
				: SseWriteBudget.direct();
	}

	/** 取（必要时新建）本轮的写出器（键 = 当前身份 + runId，见 {@link LocalRunKey}）。 */
	public SseChatEmitter of(String runId) {
		String key = LocalRunKey.of(runId);
		long nowMs = System.currentTimeMillis();
		int queueCapacity = this.properties != null ? this.properties.getSse().getDeliveryQueueCapacity()
				: AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY;
		int maxFrameBytes = this.properties != null ? this.properties.getResultBudget().getUi().getMaxFrameBytes()
				: AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES;
		EmitterEntry entry = this.emitters.computeIfAbsent(key, ignored -> new EmitterEntry(runId,
				this.frames == null
						? new SseChatEmitter(runId, this.store, RunStoreFrameStore.of(this.store), this.objectMapper,
								this.deliveryExecutor, queueCapacity, maxFrameBytes, this.writeBudget, null, null,
								this.resultLimiter)
						: new SseChatEmitter(runId, this.store, this.frames, this.objectMapper, this.deliveryExecutor,
								queueCapacity, maxFrameBytes, this.writeBudget, this.wakeBus, this.wakeListener,
								this.resultLimiter),
				nowMs));
		// 空闲回收的"活性"口径 = 这一轮在本进程里还有人 {@code of()}（每次出帧/挂订阅都会取用）。
		entry.touch(nowMs);
		// 资源修复批：终态回收钩子 —— 轮终态且订阅者排空后，写出器把自己从表里摘掉（幂等；
		// 每次 of() 都设一遍，覆盖"上一次 of 拿到的实例被并发替换"的窄窗）。摘除按<b>本次键</b>做：
		// 别的身份的同名轮次是另一个条目，绝不连带。
		entry.emitter().setRecycleHook(() -> this.emitters.remove(key, entry));
		// 惰性空闲回收（摊还到 of 上，不额外占线程）：零订阅条目不会永久留在表里。
		maybeSweep(nowMs);
		return entry.emitter();
	}

	/** 只在已有写出器时返回（避免"查一下"就凭空造出一个）。 */
	public SseChatEmitter find(String runId) {
		EmitterEntry entry = this.emitters.get(LocalRunKey.of(runId));
		return entry == null ? null : entry.emitter();
	}

	/**
	 * 本进程是否仍有该轮的<b>真实订阅者</b>（活连接）。
	 *
	 * <p>与 {@link #find} 的区别：写出器条目本身<b>不是</b>活着的证据 —— 每次 {@code of(runId)}
	 * 都会造一个（帧发出后按 B1 收流、订阅归零），把"表里有条目"当活跃会让会话门永久 409、
	 * 让僵尸判据永远判活。人工等待 park 批起，会话门/存活判定一律用本方法。
	 *
	 * <p><b>身份口径</b>：有当前身份时只看<b>这一身份</b>的条目（A 的连接不得让 B 被判活）；
	 * 无身份（启动/运行期扫描线程，见 {@code StartupResumeRunner}）时回答"本进程有没有这一轮的真实
	 * 连接"—— 任一身份桶命中即为真。判活方向的误报只让我们晚一拍接管；误报"无人"会双驱动。
	 */
	public boolean hasLiveSubscriber(String runId) {
		if (runId == null || runId.isBlank()) {
			return false;
		}
		EmitterEntry exact = this.emitters.get(LocalRunKey.of(runId));
		if (exact != null && exact.emitter().subscriberCount() > 0) {
			return true;
		}
		if (RequestContextUtils.currentOrNull() != null) {
			return false;
		}
		for (EmitterEntry entry : this.emitters.values()) {
			if (runId.equals(entry.runId()) && entry.emitter().subscriberCount() > 0) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 无真实订阅者的写出器条目<b>回收</b>（park 批）：条目只在有连接时才有意义。
	 *
	 * <p>回收是安全的：正在驱动的线程持有自己的 {@link SseChatEmitter} 引用，帧照旧取号/落档；
	 * 之后的重挂会按 runId 新建写出器并从事件日志回放（跨实例唤醒也按 Redis 广播，不依赖本地条目）。
	 *
	 * @return 是否真的移除了条目
	 */
	public boolean recycleIfIdle(String runId) {
		String key = LocalRunKey.of(runId);
		EmitterEntry entry = this.emitters.get(key);
		if (entry == null || entry.emitter().subscriberCount() > 0) {
			return false;
		}
		return this.emitters.remove(key, entry);
	}

	/**
	 * <b>空闲回收</b>（零订阅条目不会永久增长）：摘掉「超过空闲窗口没有任何 {@code of()} 触达
	 * + 无订阅者 + 没有在写的 emit + 未进入终态收尾」的条目。
	 *
	 * <p>判据刻意不是"有订阅才留"：{@code events}/{@code confirm} 这类入口为"当前没有连接"的轮次
	 * 惰性建条目是常态，只按订阅判会让它们在长跑实例上只增不减。反过来，下面三类条目<b>绝不能</b>
	 * 被摘（摘了会让同一轮在进程内出现两个写出器，帧号/扇出分家）：
	 * <ul>
	 * <li><b>有订阅</b>：还有连接挂在这个对象上，摘掉会丢帧；</li>
	 * <li><b>在写</b>（{@code emitting > 0}）：有线程正在 {@code emit} 里（已宣告出帧、未落档/入队）；</li>
	 * <li><b>terminating</b>：终态收尾进行中（尾帧可能还在排空），由写出器自己的终态钩子收；
	 * 另外窗口内的"刚建好"条目也不会被摘（给"建好 → 挂订阅"的窄窗留余量）。</li>
	 * </ul>
	 *
	 * @return 本次摘掉的条目数
	 */
	public int sweepIdle() {
		return sweepIdle(System.currentTimeMillis());
	}

	/** 空闲回收（显式时钟；包内可见，供用例把窗口确定化）。 */
	int sweepIdle(long nowMs) {
		this.lastSweepAtMs.set(nowMs);
		return recycleIdleEntries(nowMs);
	}

	/** 包内可见：收紧空闲窗口（用例把 30 分钟压到毫秒级）。 */
	void setIdleRecycleAfter(java.time.Duration idle) {
		this.idleRecycleAfterMs = Math.max(0L, idle == null ? 0L : idle.toMillis());
	}

	/** 包内可见：收紧扫描间隔（{@code of()} 摊还扫描的节流）。 */
	void setSweepInterval(java.time.Duration interval) {
		this.sweepIntervalMs = Math.max(0L, interval == null ? 0L : interval.toMillis());
	}

	/** {@link #of} 上的摊还扫描：到间隔才扫一次，多线程只有一个 CAS 成功者执行。 */
	private void maybeSweep(long nowMs) {
		if (this.sweepIntervalMs <= 0L) {
			return;
		}
		long last = this.lastSweepAtMs.get();
		if (nowMs - last < this.sweepIntervalMs) {
			return;
		}
		if (!this.lastSweepAtMs.compareAndSet(last, nowMs)) {
			return;
		}
		recycleIdleEntries(nowMs);
	}

	private int recycleIdleEntries(long nowMs) {
		int removed = 0;
		for (Map.Entry<String, EmitterEntry> candidate : this.emitters.entrySet()) {
			EmitterEntry entry = candidate.getValue();
			if (!entry.reclaimable(nowMs, this.idleRecycleAfterMs)) {
				continue;
			}
			if (this.emitters.remove(candidate.getKey(), entry)) {
				removed++;
				log.debug("空闲写出器条目已回收 key={} runId={}（无订阅/不在写/非终态）", candidate.getKey(),
						entry.runId());
			}
		}
		if (removed > 0) {
			log.info("空闲写出器条目回收 {} 条（剩余 {} 条）", removed, this.emitters.size());
		}
		return removed;
	}

	/**
	 * 轮终态：完成全部订阅者、释放事件日志侧的进程内簿记，并把写出器移出注册表。
	 *
	 * <p>终态是"这一轮在本进程的最后一拍"：写出器条目、订阅表、唤醒订阅都在
	 * {@link SseChatEmitter#complete()} 里收敛；这里补的是 {@link FrameStore#forget} ——
	 * 事件日志实现按 runId 记的进程内簿记（降级号 / 观察到的最大载荷 / 每轮一次告警位）
	 * 随终态释放，长跑实例上不再"每轮一条、只增不减"。
	 *
	 * <p><b>只摘当前身份的条目</b>：A 的终态出口不得收掉 B 的同名轮次（那是另一个写出器、
	 * 另一条连接）。身份作用域由调用点提供（驱动任务/HTTP 请求都已按记录身份建立）。
	 */
	public void close(String runId) {
		String key = LocalRunKey.of(runId);
		EmitterEntry entry = this.emitters.remove(key);
		SseChatEmitter emitter = entry == null ? null : entry.emitter();
		if (emitter != null) {
			emitter.complete();
		}
		if (this.frames != null) {
			try {
				this.frames.forget(runId);
			}
			catch (RuntimeException ex) {
				// 释放是"更好"，不是"更正确"的前提：簿记清不掉不能让终态出口失败。
				log.warn("释放轮次事件日志簿记失败 runId={}（不影响终态；到期清理会兜底）：{}", runId, ex.getMessage());
			}
		}
	}

	/**
	 * 一个写出器条目：写出器本身 + 它属于哪一轮 + <b>最后一次被 {@code of()} 触达</b>的时刻。
	 *
	 * <p>时刻放在条目上而不是写出器里：空闲回收问的是"这一轮在本进程里还有没有人取用它"，
	 * 那是注册表的记账；写出器不需要为此多一个字段，也不必被回收语义污染。
	 */
	private static final class EmitterEntry {

		private final String runId;

		private final SseChatEmitter emitter;

		private final AtomicLong lastTouchedAtMs;

		private EmitterEntry(String runId, SseChatEmitter emitter, long nowMs) {
			this.runId = runId;
			this.emitter = emitter;
			this.lastTouchedAtMs = new AtomicLong(nowMs);
		}

		private String runId() {
			return this.runId;
		}

		private SseChatEmitter emitter() {
			return this.emitter;
		}

		private void touch(long nowMs) {
			this.lastTouchedAtMs.set(nowMs);
		}

		/**
		 * 是否可被空闲扫描摘除：窗口内没有任何触达，且<b>无订阅 / 不在写 / 非终态收尾</b>。
		 *
		 * <p>三个否条件缺一不可：有连接的摘掉会丢帧；正在 {@code emit} 的摘掉会让"已宣告出帧、
		 * 尚未落档"的线程与新建的写出器并存（帧号/扇出分家）；终态收尾中的条目由写出器自己的
		 * 终态钩子收，抢在它前面摘会让仍在排空的尾帧失去归属。
		 */
		private boolean reclaimable(long nowMs, long idleMs) {
			if (nowMs - this.lastTouchedAtMs.get() < idleMs) {
				return false;
			}
			return this.emitter.subscriberCount() == 0 && !this.emitter.isTerminating()
					&& this.emitter.inFlightEmits() == 0;
		}

	}

}
