package com.example.configmgr.health;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 就绪探针的**有界执行器**：所有维度共享一个总时限，超时维度记 DOWN，调用方按期返回。
 *
 * <h2>为什么需要它</h2>
 * {@code /health/ready} 由 LB/编排器周期调用，它们对探测有 1–5s 的超时。探针本身可能阻塞在
 * 远程共享卷（{@code Files.createDirectories/isWritable} 走 NFS/SMB）或慢连接上 ——
 * 只要有一个维度拖住，端点就会"超时"而不是给出 503/200 的明确结论，摘流与恢复都不确定。
 * 因此把探测放到**固定容量的 daemon 线程池**里跑，调用线程只等到一个共享截止时刻。
 *
 * <h2>有界的三层</h2>
 * <ol>
 * <li><b>时间</b>：一次 {@link #probe(List)} 的总等待 ≤ {@code timeout}（默认 2s），
 * 不是"每个探针各等 timeout"；</li>
 * <li><b>线程</b>：core = max = {@code threads}，队列 {@code queueCapacity}，
 * 不随探测次数创建线程；</li>
 * <li><b>饱和</b>：池满且队列满 ⇒ 新探测被拒绝，该维度直接记 DOWN（{@code saturated}），
 * 绝不改由调用线程跑（那会重新变回无界等待）。</li>
 * </ol>
 *
 * <h2>边界（如实声明）</h2>
 * {@link Future#cancel(boolean)} 只能**中断**线程：阻塞在不可中断 native I/O 上的调用线程会
 * 留在池里直到 I/O 返回 —— 本类保证的是"调用方有界返回"，不是"底层 I/O 被杀死"。
 * 卡住线程占用池位是这条边界的代价：池位耗尽后新探测按 saturated 记 DOWN，仍不会拖住端点。
 * 因此 {@code threads} 应 ≥ 探针维度数，队列只是吸收抖动。
 *
 * <h2>生命周期</h2>
 * 由 Spring 以 {@code destroyMethod=close} 管理；关闭后 {@link #probe(List)} 不再提交，
 * 全部维度记 DOWN（端点仍能响应，只是不再宣布就绪）。
 */
@Slf4j
public final class ReadinessProbeRunner implements AutoCloseable {

	/** 共享总时限缺省值（LB 探测超时通常 1–5s，取 2s 留出代理侧余量）。 */
	public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

	public static final int DEFAULT_THREADS = 4;

	public static final int DEFAULT_QUEUE_CAPACITY = 8;

	private final Duration timeout;

	private final int threads;

	private final int queueCapacity;

	private final ThreadPoolExecutor executor;

	public ReadinessProbeRunner(int threads, int queueCapacity, Duration timeout) {
		if (threads <= 0) {
			throw new IllegalStateException("app.health.readiness.probe-threads 必须 > 0（当前 = " + threads + "）");
		}
		if (queueCapacity <= 0) {
			throw new IllegalStateException(
					"app.health.readiness.probe-queue-capacity 必须 > 0（当前 = " + queueCapacity
							+ "）：0 容量的有界队列不合法；要「不排队」请配 1（饱和语义仍是「拒绝即 DOWN」）");
		}
		if (timeout == null || timeout.isZero() || timeout.isNegative()) {
			throw new IllegalStateException(
					"app.health.readiness.probe-timeout 必须 > 0（当前 = " + timeout + "）：0/负值等于取消就绪探测的时限");
		}
		this.timeout = timeout;
		this.threads = threads;
		this.queueCapacity = queueCapacity;
		this.executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
				new ArrayBlockingQueue<>(queueCapacity), new DaemonThreadFactory(), new ThreadPoolExecutor.AbortPolicy());
		// 空闲线程可回收：就绪探测是低频动作，不必常驻 N 条线程（剩余容量仍受 threads 上界约束）
		this.executor.allowCoreThreadTimeOut(true);
	}

	/** 缺省装配（2s / 4 线程 / 队列 8）：测试与无配置场景用。 */
	public static ReadinessProbeRunner defaults() {
		return new ReadinessProbeRunner(DEFAULT_THREADS, DEFAULT_QUEUE_CAPACITY, DEFAULT_TIMEOUT);
	}

	public Duration timeout() {
		return this.timeout;
	}

	/**
	 * 并发跑一遍全部探针，共享一个截止时刻。
	 *
	 * @return 与入参同序的结果（每个探针一条）；探针为 {@code null} 时按 DOWN 处理
	 */
	public List<Outcome> probe(List<ReadinessProbe> probes) {
		if (probes.isEmpty()) {
			return List.of();
		}
		List<Future<ProbeResult>> futures = new ArrayList<>(probes.size());
		List<ProbeResult> immediate = new ArrayList<>(probes.size());
		boolean shutDown = this.executor.isShutdown();
		long deadlineNanos = System.nanoTime() + this.timeout.toNanos();

		for (ReadinessProbe probe : probes) {
			if (probe == null) {
				futures.add(null);
				immediate.add(ProbeResult.down("探针为 null"));
				continue;
			}
			if (shutDown) {
				futures.add(null);
				immediate.add(ProbeResult.down("探测执行器已关闭"));
				continue;
			}
			try {
				futures.add(this.executor.submit(probe::check));
				immediate.add(null);
			}
			catch (RejectedExecutionException ex) {
				futures.add(null);
				immediate.add(ProbeResult.down("探测线程池饱和（threads=" + this.threads
						+ ", queue=" + this.queueCapacity + "）：本维度按 DOWN 处理，不阻塞就绪端点"));
			}
		}

		List<Outcome> outcomes = new ArrayList<>(probes.size());
		for (int i = 0; i < probes.size(); i++) {
			ReadinessProbe probe = probes.get(i);
			ProbeResult result = immediate.get(i);
			if (result == null) {
				result = await(futures.get(i), deadlineNanos);
			}
			outcomes.add(new Outcome(probe, result));
		}
		return outcomes;
	}

	private ProbeResult await(Future<ProbeResult> future, long deadlineNanos) {
		long remainingNanos = deadlineNanos - System.nanoTime();
		if (remainingNanos <= 0) {
			// 共享时限已到：已完成的任务结果仍要如实上报（不能把"早就跑完"的维度也判超时）
			if (future.isDone()) {
				return completed(future);
			}
			future.cancel(true);
			return timeoutResult();
		}
		try {
			ProbeResult result = future.get(remainingNanos, TimeUnit.NANOSECONDS);
			return result == null ? ProbeResult.down("probe 返回空结果") : result;
		}
		catch (TimeoutException ex) {
			future.cancel(true);
			return timeoutResult();
		}
		catch (CancellationException ex) {
			return ProbeResult.down("探测被取消");
		}
		catch (ExecutionException ex) {
			return failed(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			future.cancel(true);
			return ProbeResult.down("探测等待被中断");
		}
	}

	/** 已完成任务的结果（不阻塞）。 */
	private ProbeResult completed(Future<ProbeResult> future) {
		try {
			ProbeResult result = future.get();
			return result == null ? ProbeResult.down("probe 返回空结果") : result;
		}
		catch (CancellationException ex) {
			return ProbeResult.down("探测被取消");
		}
		catch (ExecutionException ex) {
			return failed(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return ProbeResult.down("探测等待被中断");
		}
	}

	private static ProbeResult failed(ExecutionException ex) {
		Throwable cause = ex.getCause() == null ? ex : ex.getCause();
		return ProbeResult.down(cause.getClass().getSimpleName()
				+ (cause.getMessage() == null ? "" : ": " + cause.getMessage()));
	}

	private ProbeResult timeoutResult() {
		return ProbeResult.down("探测超时（共享时限 " + this.timeout.toMillis()
				+ "ms）：就绪端点已按期返回；底层调用可能仍在进行（Java 无法杀死不可中断 I/O）");
	}

	@Override
	public void close() {
		this.executor.shutdownNow();
		log.debug("就绪探测执行器已关闭（threads={}, queue={}）", this.threads, this.queueCapacity);
	}

	/**
	 * 单个维度的探测结果。
	 *
	 * @param probe 该维度（required 判定在调用方，见 {@link ReadinessProbe#required()}）
	 * @param result 结果（超时/饱和/异常都收敛成 DOWN）
	 */
	public record Outcome(ReadinessProbe probe, ProbeResult result) {
	}

	/** daemon 线程：探针卡住时不得阻止 JVM 退出。 */
	private static final class DaemonThreadFactory implements ThreadFactory {

		private final AtomicInteger counter = new AtomicInteger();

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, "readiness-probe-" + this.counter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		}

	}

}
