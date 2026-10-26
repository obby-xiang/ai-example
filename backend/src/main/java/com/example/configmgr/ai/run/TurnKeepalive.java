package com.example.configmgr.ai.run;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一轮对话的<b>独立服务端保活</b>（AR-06 / Task 5 Step 3）。
 *
 * <h2>为什么必须"独立"</h2>
 * 旧的流式心跳挂在<b>上游事件回调</b>里（{@code ResilientChatService#runAttempts} 的
 * {@code subscribe(...)} 分支）：没有上游事件就没有心跳。于是三类合法静默全都没有保活 ——
 * 请求前压缩（L2 的 N 次串行摘要，每次最长 60s）、上游静默（首包/包间等待）、慢工具执行
 * （工具跑着时上游本来就不出事件）。前端首帧看门狗（45s，不放宽）因此会把<b>正常</b>的压缩判死。
 *
 * <p>
 * 本类把保活搬到一个<b>独立、可取消的定时任务</b>上：节拍只取决于墙钟，与上游事件、
 * 与同步压缩调用都无关。压缩调用自身<b>一帧不发</b>（"不伪称持续发帧"）—— 压缩期的节拍只来自这里。
 *
 * <h2>帧面契约（心跳口径不变）</h2>
 * 出的是既有 {@code heartbeat} 帧（见 {@link SseChatEmitter#stage(String, long, int)}）：
 * <b>不占业务 {@code seq}、不落事件归档</b>，只在独立 {@code heartbeatSeq} 空间计数 ——
 * 帧类型集仍是 16。{@code phase} = {@link #PHASE_COMPACTING} / {@link #PHASE_STREAMING}，
 * {@code elapsedMs} = <b>本阶段</b>已用毫秒（前端"正在压缩·已用 N 秒"的唯一来源，不伪造百分比）。
 *
 * <h2>停表</h2>
 * 终态 / 异常 / 取消 / park 四条出口都必须停表（调用方在出终帧<b>之前</b> {@link #stop()}，
 * 并在 {@code finally} 里 {@link #close()}）：终帧之后再发心跳会破坏"终帧是本轮最后一帧"的
 * 帧序不变量；park 之后这一轮已交还线程，不得再写帧。
 * {@link #rearm()} 只服务"同一轮继续"的分支（空的模型步再试一次），不用于新轮。
 *
 * <p>定时器失败（调度器已关闭 / 节拍体抛错）一律只记日志：保活是观测面，<b>不得反噬</b>轮次本身。
 */
@Slf4j
public final class TurnKeepalive implements AutoCloseable {

	/** 请求前压缩阶段（{@code phase=compacting}）：快照可能尚未建立，重挂侧返回"准备中"。 */
	public static final String PHASE_COMPACTING = "compacting";

	/** 模型生成阶段（{@code phase=streaming}）：含首包等待、包间静默与慢工具执行。 */
	public static final String PHASE_STREAMING = "streaming";

	private final String runId;

	private final SseChatEmitter out;

	private final ScheduledExecutorService scheduler;

	private final long intervalMs;

	private final AtomicReference<Stage> stage;

	private final AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();

	private final AtomicBoolean closed = new AtomicBoolean();

	/** 本尝试序号（0 = 还没进尝试循环，例如压缩阶段）。 */
	private volatile int attempt;

	/** 一次"阶段"：名字 + 起点（{@code elapsedMs} 的零点）。 */
	private record Stage(String phase, long startedAtMs) {
	}

	private TurnKeepalive(String runId, SseChatEmitter out, ScheduledExecutorService scheduler, long intervalMs,
			String phase) {
		this.runId = runId;
		this.out = out;
		this.scheduler = scheduler;
		this.intervalMs = Math.max(1L, intervalMs);
		this.stage = new AtomicReference<>(new Stage(phase, System.currentTimeMillis()));
		schedule();
	}

	/**
	 * 排一个独立的保活定时器（首个节拍在 {@code intervalMs} 之后；阶段开始帧由调用方另发）。
	 *
	 * @param scheduler 调度器（生产 = {@code aiStreamWatchdog}；{@code null} = 只返回不排队的空壳）
	 * @param intervalMs 节拍间隔（生产 = {@link ResilientChatService#STREAMING_HEARTBEAT_MILLIS}）
	 * @param phase 初始阶段（{@link #PHASE_COMPACTING} / {@link #PHASE_STREAMING}）
	 */
	public static TurnKeepalive start(String runId, SseChatEmitter out, ScheduledExecutorService scheduler,
			long intervalMs, String phase) {
		return new TurnKeepalive(runId, out, scheduler, intervalMs, phase);
	}

	/**
	 * 排定时器 + <b>立刻</b>发阶段开始帧。
	 *
	 * <p>调用点必须满足一条时序：本方法返回之后才允许进入<b>同步压缩调用</b>（阶段开始帧因此
	 * 一定先于那次调用到达客户端）。压缩调用内部不发帧，压缩期的后续节拍只由本定时器产生。
	 */
	public static TurnKeepalive startWithStageFrame(String runId, SseChatEmitter out,
			ScheduledExecutorService scheduler, long intervalMs, String phase) {
		TurnKeepalive keepalive = new TurnKeepalive(runId, out, scheduler, intervalMs, phase);
		keepalive.announce();
		return keepalive;
	}

	/** 切阶段（{@code elapsedMs} 从此刻重新起算）。 */
	public void phase(String phase) {
		Stage current = this.stage.get();
		if (!phase.equals(current.phase())) {
			this.stage.set(new Stage(phase, System.currentTimeMillis()));
		}
	}

	/** 本尝试序号（进每次尝试时上报；重试写新号，阶段不重置）。 */
	public void attempt(int attempt) {
		this.attempt = Math.max(0, attempt);
	}

	/** 停表：此后本定时器不再出帧（可 {@link #rearm()} 重新武装）。 */
	public void stop() {
		ScheduledFuture<?> current = this.future.getAndSet(null);
		if (current != null) {
			current.cancel(false);
		}
	}

	/** 重新武装（只服务"同一轮继续"的分支：空的模型步再试一次）。已 close 的实例不再武装。 */
	public void rearm() {
		if (!this.closed.get()) {
			schedule();
		}
	}

	@Override
	public void close() {
		this.closed.set(true);
		stop();
	}

	/** 阶段开始帧（同步、由调用线程在进入同步压缩之前发）。 */
	private void announce() {
		Stage current = this.stage.get();
		emit(current.phase(), 0L);
	}

	private void schedule() {
		if (this.scheduler == null || this.out == null) {
			return;
		}
		if (this.future.get() != null) {
			return;
		}
		try {
			ScheduledFuture<?> scheduled = this.scheduler.scheduleAtFixedRate(this::beat, this.intervalMs,
					this.intervalMs, TimeUnit.MILLISECONDS);
			this.future.set(scheduled);
		}
		catch (RejectedExecutionException ex) {
			// 调度器已关闭（应用停机 / 单测提前 shutdown）：保活缺席不影响本轮正确性，只记 DEBUG
			log.debug("保活定时器未排上 runId={}（调度器已关闭）：{}", this.runId, ex.getMessage());
		}
	}

	private void beat() {
		if (this.closed.get()) {
			return;
		}
		Stage current = this.stage.get();
		emit(current.phase(), Math.max(0L, System.currentTimeMillis() - current.startedAtMs()));
	}

	private void emit(String phase, long elapsedMs) {
		if (this.out == null) {
			return;
		}
		try {
			this.out.stage(phase, elapsedMs, this.attempt);
		}
		catch (RuntimeException ex) {
			// 观测面不得反噬轮次：客户端已断/写出器已关闭/序列化异常都只降级为 DEBUG
			log.debug("保活帧发送失败（已忽略）runId={} phase={}：{}", this.runId, phase, ex.getMessage());
		}
	}

}
