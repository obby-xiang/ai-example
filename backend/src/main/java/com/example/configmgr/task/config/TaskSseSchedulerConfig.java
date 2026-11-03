package com.example.configmgr.task.config;

import com.alibaba.ttl.threadpool.TtlExecutors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务通道 SSE 的<b>专用</b>有界事件调度器（线程治理·T-01/T-03）。
 *
 * <h2>为什么不复用 {@code aiStreamWatchdog}</h2>
 * 任务通道的跨实例注册重试原先复用 AI 的 2 线程看门狗（{@code app.ai.exec#aiStreamWatchdog}）。
 * 那个调度器承担每轮一条 1Hz 判定节拍 + 一条 5s 心跳，且线程数固定 2；任务通道的重试量随
 * "打开的任务页数"增长，和 AI 轮次抢同一组线程 —— 跨模块耦合，任务通道的推送/重试跟着
 * AI 链路是否健康一起抖动。本 Bean 给它一条<b>独立、有界</b>的时间线：任务通道的推送/重试不再依赖
 * AI 链路是否健康，也不会反向占用 AI 的判定线程（AI 的取消读已另有 {@code aiCancelPoll}）。
 *
 * <h2>形状</h2>
 * <ul>
 * <li>线程数固定 {@value #EVENT_SCHEDULER_THREADS}、守护线程、{@code task-sse-event-N} 命名 ——
 * 有界且可在线程转储里一眼归因；任务体是一次性轻量动作（Redis publish + 入队），
 * 不多开线程就足以覆盖"每个任务一次重试"的量级；</li>
 * <li>{@code setRemoveOnCancelPolicy(true)}：末订阅会 {@code cancel} 尚未到期的重试任务，
 * 不开这条策略它们会在延迟队列里滞留到原定 10s 到期（队列里堆"已取消的空任务"）；</li>
 * <li>停机：{@code shutdownNow}（守护线程 + 一次性重试任务，中断不产生业务终态，
 * 与 AI 运行池"停机不得中断在飞轮次"的口径分属不同资源）；</li>
 * <li>身份（<b>勿误读</b>）：{@link TtlExecutors} 包装只为与其它调度器保持同一纪律 ——
 * 在<b>提交时刻</b>捕获、执行时回放提交侧的 {@code TransmittableThreadLocal} 快照；
 * 它<b>不是</b>重试回调的身份来源：{@code TaskSseService#runRemoteRetry} 在重试体里显式开
 * {@code RunIdentityScope.denied()}，调度线程不得沿用"排期那一刻的调用者身份"
 * （同一池会被所有任务复用）。任务事件频道是 {@code task:events:<taskId>}，不带身份段，
 * 也不按身份分键，因此重试回调不需要、也不应该带租户身份。</li>
 * </ul>
 *
 * <p>Bean 是<b>单例、启动期就绪</b>（不是"用到时才在循环里现建"）：{@link ScheduledThreadPoolExecutor}
 * 本身不在构造时起线程，没有订阅者时零线程开销，因此无需懒加载包装。
 */
@Configuration
public class TaskSseSchedulerConfig {

	/** 任务通道事件线程数（有界；重试是每任务一次的一次性动作）。 */
	private static final int EVENT_SCHEDULER_THREADS = 2;

	/** 事件线程编号（{@code task-sse-event-N}）。 */
	private static final AtomicInteger EVENT_THREAD_NO = new AtomicInteger();

	/**
	 * 任务通道专用调度器：供 {@code TaskSseService} 的延迟重试使用。
	 *
	 * <p>与 {@code aiStreamWatchdog} 相互独立，避免跨模块共用 2 条判定线程（见类注）。
	 */
	@Bean(name = "taskEventScheduler", destroyMethod = "shutdownNow")
	public ScheduledExecutorService taskEventScheduler() {
		ScheduledThreadPoolExecutor raw = new ScheduledThreadPoolExecutor(EVENT_SCHEDULER_THREADS, runnable -> {
			Thread thread = new Thread(runnable, "task-sse-event-" + EVENT_THREAD_NO.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
		// 已取消的延迟任务立即出队（末订阅会 cancel 未到期的重试）。
		raw.setRemoveOnCancelPolicy(true);
		// 停机后不再执行已排期的重试（避免对已关闭的 Redis/上下文发请求）。
		raw.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		return TtlExecutors.getTtlScheduledExecutorService(raw);
	}

}
