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
 * 单次尝试的流式看门狗（S4.2 §2 韧性棒的判定核心）：把"首包静默 / 事件间静默 / <b>段</b>预算 / 取消"
 * 四种"流已不再前进"的情形统一变成一次 {@link StreamViolationException}。
 *
 * <h2>段预算（2026-10-21 决策看板 {@code :14}「挂起恢复计时口径裁决」）</h2>
 * 原判据是「轮开始 − 挂起等待 &gt; 300s」（扣除式）；现改为「<b>段开始</b> &gt; 300s」（重置式）：
 * <b>挂起点段结束、恢复点段重置</b>。`{@code TOTAL_BUDGET}` 码名保留、语义改为「段」。
 * <ul>
 * <li>段起点由 {@link ToolActivityBeacon#segmentStartedAtMs(String, long)} 持有：轮开始/续跑入口播种，
 * 每次挂起结束（决策/回灌/超时/取消）在 {@code endGateWait} 里前移；</li>
 * <li>挂起进行中（{@link ToolActivityBeacon#inGateWait(String)}）<b>不判预算</b> —— 段已结束；</li>
 * <li>{@link ToolActivityBeacon#gateWaitMs(String)} 保留但<b>只供观测</b>（日志/指标），不再参与判据。</li>
 * </ul>
 * 为什么必须配步数笼（{@code app.ai.resilience.max-tool-steps}）：段重置让"每段 300s"成立，
 * 若同时放开工具步数，一轮可以靠反复挂起无限续命 —— 步数累计不重置正是这条的地板（看板 {@code :14}）。
 *
 * <h2>为什么不直接用 {@code Flux.timeout(...)}</h2>
 * 官方 {@code timeout} 只有"距上一个事件多久"的概念，而挂起等待（确认门与
 * {@code generative_form} 现在<b>没有上限</b>，其余前端工具 120s；见 {@code ConfirmGate}）期间
 * 上游<b>本来就</b>是静默的 —— 用纯 timeout 会把
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

	/**
	 * 段起点的<b>播种值</b>（本尝试构造时刻）。
	 *
	 * <p>语义变更（2026-10-21 决策看板 {@code :14}）：本参数以前是"轮开始"，现在只是
	 * {@link ToolActivityBeacon#segmentStartedAtMs(String, long)} 的兜底 —— 真正的段锚点由信标持有，
	 * 恢复点（决策/回灌/续跑）会在工具循环线程上把它前移。看门狗每拍重读一次，因此运行中的段重置
	 * 立刻生效。
	 */
	private final long segmentSeedAtMs;

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
			ToolActivityBeacon beacon, CancellationRegistry cancellations, long segmentStartedAtMs,
			Consumer<StreamViolationException> onViolation) {
		this.runId = runId;
		this.config = config;
		this.beacon = beacon;
		this.cancellations = cancellations;
		this.segmentSeedAtMs = segmentStartedAtMs;
		this.onViolation = onViolation;
		this.lastUpstreamAtMs.set(segmentStartedAtMs);
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
			// C2：UNKNOWN（Redis 读失败/退避）在看门狗里按"未取消 + 计数继续" —— 看门狗没有不可逆动作，
			// 不能让一次读抖动静默停摆段预算/首包/静默判据；A5 计数已由 CancellationRegistry 落。
			CancellationRegistry.CancelState cancelState = this.cancellations.cancelState(this.runId);
			if (cancelState == CancellationRegistry.CancelState.CANCELLED) {
				fire(StreamViolationException.CANCELLED, "本轮对话已被取消（取消标志已置位）");
				return;
			}
			// ② 段预算（决策看板 :14「段级预算重置式」）：判据 = 当前段起点，不跨段累计。
			// 挂起点（确认门 / 无超时工具 suspend）段已结束 ⇒ 等待期<b>不判</b>；
			// 恢复点（决策/回灌/续跑）由 ToolActivityBeacon#endGateWait / markSegmentStart 重置段起点，
			// 于是每段各自重新拥有 app.ai.resilience.total-budget。code 名沿用 TOTAL_BUDGET（码名保留）。
			if (!this.beacon.inGateWait(this.runId)) {
				long segmentStart = this.beacon.segmentStartedAtMs(this.runId, this.segmentSeedAtMs);
				// 轻装配面（mock/无锚点）读回 0 ⇒ 按播种值算（否则"没有锚点"会当场判成预算耗尽）
				if (segmentStart <= 0L) {
					segmentStart = this.segmentSeedAtMs;
				}
				long segmentElapsedMs = now - segmentStart;
				if (segmentElapsedMs >= this.config.getTotalBudget().toMillis()) {
					fire(StreamViolationException.TOTAL_BUDGET, "本段预算 " + this.config.getTotalBudget()
							+ " 已耗尽（app.ai.resilience.total-budget，段级；本段已用 " + segmentElapsedMs
							+ "ms，本轮累计人工等待 " + this.beacon.gateWaitMs(this.runId) + "ms 不计入）");
					return;
				}
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

	/**
	 * 本段"计入预算"的已用时长（毫秒）= 墙钟 − 段起点。
	 *
	 * <p>段起点由信标持有（{@link ToolActivityBeacon#segmentStartedAtMs(String, long)}），恢复点会在
	 * 工具循环线程上把它前移；这里每拍现读，因此运行中的段重置立刻生效。
	 */
	long segmentElapsedMs(long nowMs) {
		long start = this.beacon.segmentStartedAtMs(this.runId, this.segmentSeedAtMs);
		return nowMs - (start <= 0L ? this.segmentSeedAtMs : start);
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
