package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 单次尝试的流式看门狗（S4.2 §2 韧性棒的判定核心）：把"首包静默 / 事件间静默 / 总预算 / 取消"
 * 四种"流已不再前进"的情形统一变成一次 {@link StreamViolationException}。
 *
 * <h2>为什么不直接用 {@code Flux.timeout(...)}</h2>
 * 官方 {@code timeout} 只有"距上一个事件多久"的概念，而挂起等待（上限由
 * {@code app.ai.hitl.confirm-timeout} 与 {@code app.ai.hitl.frontend-tool-timeout} 两键按场景给出：
 * 确认门等人 240s / 前端工具等机器 120s）期间上游<b>本来就</b>是静默的 —— 用纯 timeout 会把
 * 一次长达分钟级的人工确认误判成断流（SM-02 的判据正是这一条）。因此本类把两个硬规范写进判定：
 * <ol>
 * <li><b>超时阈值 &gt; 最长工具耗时</b>：{@link ToolActivityBeacon#isActive(String)} 为真时
 * <b>不参与静默判定</b>（工具执行期间不计数），工具退出后以退出时刻为新锚点重新计时 ——
 * 于是有效容忍度 = 最长工具耗时 + 配置阈值；</li>
 * <li><b>TCP 静默 FIN 按断流处理</b>：正常结束必须有<b>终帧</b>（带 finishReason 的响应）。
 * 连接"安静地"结束而没给终帧，由 {@code ResilientChatService} 判为
 * {@link StreamViolationException#INCOMPLETE}。</li>
 * </ol>
 *
 * <h2>取消</h2>
 * 取消同样在事件边界被这里发现（每 {@value #TICK_MILLIS}ms 轮询一次 Redis 权威标志），
 * 同进程时 {@link CancellationRegistry#onCancel} 的监听者还会当场触发
 * {@link #fire(String, String)}，属于"加速"，不改变以 Redis 为准的语义。
 */
@Slf4j
public class StreamWatchdog implements AutoCloseable {

	/** 判定节拍（毫秒）：既是"事件边界轮询"的周期，也是静默判定的分辨率。 */
	public static final long TICK_MILLIS = 1000L;

	private final String runId;

	private final AiProperties.Resilience config;

	private final ToolActivityBeacon beacon;

	private final CancellationRegistry cancellations;

	private final long turnStartedAtMs;

	private final Consumer<StreamViolationException> onViolation;

	private final AtomicLong lastUpstreamAtMs = new AtomicLong();

	private final AtomicInteger upstreamEvents = new AtomicInteger();

	private final AtomicBoolean fired = new AtomicBoolean();

	private final AtomicInteger ticks = new AtomicInteger();

	private final AtomicReference<Disposable> subscription = new AtomicReference<>();

	private final AtomicReference<String> firedCode = new AtomicReference<>();

	private final AutoCloseable cancelListener;

	private final ScheduledFuture<?> future;

	public StreamWatchdog(String runId, ScheduledExecutorService scheduler, AiProperties.Resilience config,
			ToolActivityBeacon beacon, CancellationRegistry cancellations, long turnStartedAtMs,
			Consumer<StreamViolationException> onViolation) {
		this.runId = runId;
		this.config = config;
		this.beacon = beacon;
		this.cancellations = cancellations;
		this.turnStartedAtMs = turnStartedAtMs;
		this.onViolation = onViolation;
		this.lastUpstreamAtMs.set(turnStartedAtMs);
		// 同进程取消的加速通道：监听者只做"当场判定"，权威态仍在 Redis
		this.cancelListener = cancellations.onCancel(runId, () -> fire(StreamViolationException.CANCELLED,
				"本轮对话已被取消（取消标志已置位），上游连接随即断开）"));
		this.future = scheduler.scheduleAtFixedRate(this::tick, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
	}

	/** 绑定本次尝试的订阅，便于判定成立时立刻断开上游。 */
	public void bind(Disposable disposable) {
		this.subscription.set(disposable);
	}

	/** 每收到一个上游事件调一次（事件边界）。 */
	public void onUpstreamEvent() {
		this.upstreamEvents.incrementAndGet();
		this.lastUpstreamAtMs.set(System.currentTimeMillis());
	}

	public int upstreamEvents() {
		return this.upstreamEvents.get();
	}

	public int ticks() {
		return this.ticks.get();
	}

	/** 判定成立时的 code（未成立返回 null）。 */
	public String firedCode() {
		return this.firedCode.get();
	}

	/** 供日志/证据：本次尝试的静默锚点。 */
	public long lastUpstreamAtMs() {
		return this.lastUpstreamAtMs.get();
	}

	private void tick() {
		try {
			long now = System.currentTimeMillis();
			this.ticks.incrementAndGet();
			if (this.fired.get()) {
				return;
			}
			// ① 取消（Redis 为准；同进程时监听者可能已提前触发，CAS 保证只生效一次）
			if (this.cancellations.isCancelled(this.runId)) {
				fire(StreamViolationException.CANCELLED, "本轮对话已被取消（取消标志已置位）");
				return;
			}
			// ② 总预算（墙钟）
			if (now - this.turnStartedAtMs >= this.config.getTotalBudget().toMillis()) {
				fire(StreamViolationException.TOTAL_BUDGET, "本轮总预算 " + this.config.getTotalBudget()
						+ " 已耗尽（app.ai.resilience.total-budget）");
				return;
			}
			// ③ 硬规范①：工具执行期间不计静默（挂起等待本质上就是上游静默）
			if (this.beacon.isActive(this.runId)) {
				return;
			}
			long anchor = Math.max(this.lastUpstreamAtMs.get(), this.beacon.lastActivityAtMs(this.runId));
			long silenceMs = now - anchor;
			if (this.upstreamEvents.get() == 0) {
				if (silenceMs > this.config.getFirstByteTimeout().toMillis()) {
					fire(StreamViolationException.FIRST_BYTE_TIMEOUT, "上游 " + silenceMs + "ms 内未给出首包（阈值 "
							+ this.config.getFirstByteTimeout() + "）");
				}
				return;
			}
			if (silenceMs > this.config.getInterEventTimeout().toMillis()) {
				fire(StreamViolationException.SILENT_TIMEOUT, "上游事件间静默 " + silenceMs + "ms（阈值 "
						+ this.config.getInterEventTimeout() + "）");
			}
		}
		catch (Exception ex) {
			log.debug("看门狗节拍异常 runId={}：{}", this.runId, ex.getMessage());
		}
	}

	private void fire(String code, String message) {
		if (!this.fired.compareAndSet(false, true)) {
			return;
		}
		this.firedCode.set(code);
		log.warn("本轮流判定为终止 runId={} code={} 已收上游事件={} 节拍={}：{}", this.runId, code,
				this.upstreamEvents.get(), this.ticks.get(), message);
		Disposable current = this.subscription.get();
		if (current != null && !current.isDisposed()) {
			try {
				current.dispose();
			}
			catch (Exception ex) {
				log.debug("断开上游订阅失败 runId={}：{}", this.runId, ex.getMessage());
			}
		}
		this.onViolation.accept(new StreamViolationException(code, message));
	}

	@Override
	public void close() {
		this.future.cancel(false);
		try {
			this.cancelListener.close();
		}
		catch (Exception ex) {
			log.debug("注销取消监听者失败 runId={}：{}", this.runId, ex.getMessage());
		}
	}

}
