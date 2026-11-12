package com.example.configmgr.ai.exec;

import com.alibaba.ttl.threadpool.TtlExecutors;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.observe.AiMdcTaskDecorator;
import com.example.configmgr.ai.run.StreamWatchdog;
import com.example.configmgr.ai.tenant.RequestContextTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * AI 运行线程模型（S4.2 §2 exec/AiExecutorConfig + DC-12：禁用虚拟线程）。
 *
 * <h2>挂起专用有界平台线程池</h2>
 * 一轮对话占用一条线程直至终帧 —— 挂起等待（确认门与 {@code generative_form} 自 2026-10-20
 * 起<b>无上限</b>；即时执行类前端工具默认 120s）就是在这一条线程上阻塞的。因此：
 * <ul>
 * <li>池容量 = {@code app.ai.suspend.pool-size}（默认 20）= <b>挂起并发上限</b>
 * （ADR-2 重审 N1：BLOCKING 形态每次挂起实占本池 1 条 + 官方硬编码 boundedElastic 1 条）；</li>
 * <li><b>队列容量 0</b>：池满即拒绝，而不是排队 —— SP-02 实测形态为
 * {@code ThreadPoolExecutor(..., SynchronousQueue, AbortPolicy)}，语义是"快速失败"
 * （排队会让请求在无上限时长里等一个不知何时到来的槽位）；</li>
 * <li>拒绝由 {@code AiController}/{@code ResumeService} 转成 <b>503
 * {@code SUSPEND_POOL_SATURATED}</b>（与技术方案 v2.1.2 状态码口径统一；与 ADR-5 的
 * <b>409</b> 会话并发区分：409 = 同一 sessionId 已有轮次，503 = 全局挂起容量用尽）。</li>
 * </ul>
 *
 * <p><b>登记（数字规格清点裁决③的连带面，关联等待治理 R-41「多挂起累计预算」）</b>：确认门上限由
 * 120s 抬到 240s 后，池满（20 条全被挂起占住、队列 0）时的 503 饱和窗口同比例翻倍 ——
 * 单次挂起 2 分钟级 → 4 分钟级，池级最坏累计 40 分钟级 → 80 分钟级；若日后按裁决③抬到 600s，
 * 单次即 10 分钟级。本项登记备查，本批不动容量。
 *
 * <p>
 * 基座原先用 {@code Thread.ofVirtual()} 起运行线程，本棒改为平台线程池以满足 DC-12。
 *
 * <h2>停机语义（线程治理·T-02）</h2>
 * 两个池都<b>不再</b>用 {@code shutdownNow()}：中断在 {@code ResilientChatService} /
 * {@code SpToolCallingManager} 里被映射成"用户取消"，于是滚动发布会把在飞轮次写成
 * {@code CANCELLED} 并落 {@code stopped} 记忆。现在统一为
 * {@code waitForTasksToCompleteOnShutdown = true} + 有界的 {@code awaitTerminationSeconds}：
 * 等待上限内收尾的轮次照常落真实终态；超限后 Spring 只记 WARN、不补中断（在飞线程继续跑到自身预算），
 * 进程退出由容器宽限期兜底。空闲核心线程同时允许回收（见 {@link #aiRunExecutor(AiProperties)}）。
 *
 * <p><b>停机时间预算（三项按关闭顺序相加，必须整体读）</b>：
 * <pre>
 *   SIGTERM
 *     ├─ ① ContextClosedEvent → {@code ShutdownDrainGate} 的 draining 窗口
 *     │     app.health.readiness.drain-window：多实例 profile 5s / 单实例缺省 0s
 *     ├─ ② lifecycle onClose（{@code server.shutdown=graceful}）→ 等在途 HTTP 请求收尾
 *     │     spring.lifecycle.timeout-per-shutdown-phase：postgresql profile 20s
 *     │     （非 graceful profile 不产生这段等待）
 *     └─ ③ bean destroy（本类两个池各一次）→ shutdown() + awaitTerminationSeconds
 *           aiRunExecutor ≤ 10s，随后 aiResumeTrigger ≤ 10s
 *
 *   drain + phase + runAwait + triggerAwait + 余量 ≤ terminationGracePeriodSeconds
 * </pre>
 * 按 postgresql profile 实算：5 + 20 + 10 + 10 = 45s ⇒ 宽限期至少要 ≥ 45s 且留出余量
 * （建议 60s；K8s 缺省 30s <b>不够</b>，会在第 ③ 段等待中被 SIGKILL）。
 * 两个池的 destroy 是<b>串行</b>的，所以上界里两个 await 相加，不能只算运行池那一个。
 * 式中的"余量"还要容纳同一 destroy 阶段的其他 bean（如 {@code sse-write} / 唤醒订阅池各自的
 * 2s 有界收尾）与编排器自身的信号处理开销，所以不能把 45s 直接当宽限期用。
 * 这也是运行池 await 取 10s 而非 30s 的原因：它把第 ③ 段从 40s 压到 20s，
 * 让 60s 宽限期仍留有 15s 余量；再长不改善结果（超限本来就不中断任务），只挤占 drain/phase 的预算。
 */
@Configuration
public class AiExecutorConfig {

	/**
	 * {@code aiRunExecutor} 停机等待上限（秒）。
	 *
	 * <p>取值依据：一次轮次的收尾（写终帧 + 落库 + 释放租约）是秒级；10s 覆盖正常收尾，
	 * 且把"drain + phase + runAwait + triggerAwait"控制在 45s 以内（见类注"停机时间预算"）。
	 * 等待超限只 WARN、<b>不补中断</b> —— 中断会被上层当成"用户取消"。
	 */
	private static final int RUN_AWAIT_TERMINATION_SECONDS = 10;

	/**
	 * {@code aiResumeTrigger} 停机等待上限（秒）：任务本身很短（一次 Redis 读 + 转投），
	 * 与运行池同量级；两个池的 destroy 串行，故上界相加（见类注"停机时间预算"）。
	 */
	private static final int TRIGGER_AWAIT_TERMINATION_SECONDS = 10;

	/** 空闲线程存活时间（秒）：核心线程超时回收的判据（与 JDK 缺省一致，显式写出便于核对）。 */
	private static final int IDLE_KEEP_ALIVE_SECONDS = 60;

	@Bean(name = "aiRunExecutor", destroyMethod = "shutdown")
	public ThreadPoolTaskExecutor aiRunExecutor(AiProperties properties) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(properties.getSuspend().getPoolSize());
		executor.setMaxPoolSize(properties.getSuspend().getPoolSize());
		executor.setQueueCapacity(0);
		executor.setThreadNamePrefix("ai-suspend-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		// 日志链路：本轮锚点在提交时刻随任务进池（平台线程不继承 MDC）
		// 身份传播：TTL 在提交时刻抓取租户/用户上下文，池线程执行首轮建快照/登记会话时身份不丢
		executor.setTaskDecorator(aiTaskDecorator());
		// 空闲回收（线程资源治理·T-04）：core == max == 20 且不允许核心线程超时，任何一次峰值后
		// 实例会长期保留 20 条空转线程（每条约 1MB 栈地址空间）。允许超时后新任务按需重建线程
		// （毫秒级）；SynchronousQueue + AbortPolicy 的"满即 503"语义不变。
		executor.setAllowCoreThreadTimeOut(true);
		executor.setKeepAliveSeconds(IDLE_KEEP_ALIVE_SECONDS);
		// 停机（见类注"停机语义"）：等待在飞轮次收尾，绝不 shutdownNow 把中断当成用户取消。
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(RUN_AWAIT_TERMINATION_SECONDS);
		executor.initialize();
		return executor;
	}

	/**
	 * 流式看门狗的调度器（韧性棒）：每 {@link StreamWatchdog#TICK_MILLIS}ms 一次节拍，
	 * 判定首包静默 / 事件间静默 / 段预算；另承载每轮 5s 的保活心跳。
	 *
	 * <p>
	 * 2 条<b>平台</b>守护线程（DC-12 禁虚拟线程）。<b>节拍体不做任何 I/O</b>：
	 * 每轮 1Hz 的取消标志 Redis GET 已移到 {@link #aiCancelPoll()}（T-01），
	 * 节拍只读内存里的取消快照 + 常数次原子读，因此不会因 Redis 慢而整体停摆。
	 * 轮与轮之间共用同一个调度器（任务按 runId 命名，便于日志定位）。
	 *
	 * <p>
	 * <b>身份传播</b>：与两个 {@code ThreadPoolTaskExecutor} 同一纪律 —— 提交时刻抓 TTL 快照
	 * （含租户/用户身份），每次节拍执行时回放。看门狗与保活自身还会按<b>建立时的本轮身份</b>
	 * 再开一次作用域（{@code RunIdentityScope}），两层都成立时才落到同一个身份上。
	 */
	@Bean(name = "aiStreamWatchdog", destroyMethod = "shutdownNow")
	public ScheduledExecutorService aiStreamWatchdog() {
		ScheduledThreadPoolExecutor raw = new ScheduledThreadPoolExecutor(2, Executors.defaultThreadFactory());
		configureScheduler(raw);
		// TtlExecutors 包装：调度器本身不做 MDC/身份传播，统一装饰补齐
		return TtlExecutors.getTtlScheduledExecutorService(raw);
	}

	/**
	 * 每轮 1Hz <b>取消标志轮询</b>的专用调度器（线程治理·T-01）。
	 *
	 * <p><b>为什么必须独立</b>：{@link StreamWatchdog} 的节拍里每次要读一次 Redis 权威取消标志
	 * （{@code CancellationRegistry#cancelState}，同步 GET，{@code spring.data.redis.timeout}=2s）。
	 * 它与"纯内存"的首包/静默/段预算判定共用 {@code aiStreamWatchdog} 的 2 条线程时，
	 * 一次"读得慢但不失败"的 GET 就会把两条线程各按 2s（降级退避只在<b>读失败</b>后生效），
	 * 所有轮的判定与 5s 心跳一起延后。现在把这次 I/O 单独放一条有界时间线：
	 * 取消轮询被 Redis 拖慢时，判定节拍照常按秒推进。
	 *
	 * <p><b>形状</b>：2 条守护平台线程（{@code ai-cancel-poll-N}），有界、无队列增长 ——
	 * 轮询体是一次 GET + 一次 CAS 判定；正常 Redis（亚毫秒）下 2 条线程足以覆盖默认
	 * 20 并发轮（{@code suspend.pool-size}）的 20 次/秒；Redis 故障时
	 * {@code CancellationRegistry} 的 5s 降级退避会让后续轮询不再触网，排队的轮询很快排空。
	 *
	 * <p><b>取消时效不变</b>：同一进程内的取消仍由 {@code CancellationRegistry#onCancel} 监听者
	 * <b>立即</b>触发；跨进程/跨实例的取消上限仍是 1 个轮询节拍（{@link StreamWatchdog#TICK_MILLIS}）。
	 * 语义仍是"Redis 为准"，{@code UNKNOWN} 照旧只表示"这一拍没有权威答案"。
	 *
	 * <p><b>身份</b>：轮询任务在 {@code StreamWatchdog} 里按<b>建立时的本轮身份</b>开
	 * {@code RunIdentityScope}（与看门狗节拍同一份身份），调度器本身仍做 TTL 提交时刻捕获。
	 *
	 * <p><b>关闭</b>：{@code shutdownNow}（守护线程 + 幂等 GET，没有业务终态要落）；
	 * 每个看门狗 {@code close()} 会先取消自己的轮询任务，停机时不会有轮次残留轮询。
	 */
	@Bean(name = "aiCancelPoll", destroyMethod = "shutdownNow")
	public ScheduledExecutorService aiCancelPoll() {
		ScheduledThreadPoolExecutor raw = new ScheduledThreadPoolExecutor(2, Executors.defaultThreadFactory());
		configureScheduler(raw);
		return TtlExecutors.getTtlScheduledExecutorService(raw);
	}

	@Bean(name = "aiSessionPurgeRetryScheduler", destroyMethod = "shutdownNow")
	public ScheduledExecutorService aiSessionPurgeRetryScheduler() {
		ScheduledThreadPoolExecutor raw = new ScheduledThreadPoolExecutor(1, Executors.defaultThreadFactory());
		configureScheduler(raw);
		return raw;
	}

	private static void configureScheduler(ScheduledThreadPoolExecutor scheduler) {
		scheduler.setKeepAliveTime(IDLE_KEEP_ALIVE_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
		scheduler.allowCoreThreadTimeOut(true);
		scheduler.setRemoveOnCancelPolicy(true);
		scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
	}

	/**
	 * 续跑触发的<b>轻量执行器</b>（T3-6a / A9）：承载 {@code ConfirmGate} 的
	 * "死卡兜底 → ResumeService.resume"动作，让 HTTP 线程（{@code POST /api/ai/confirm}）立即返回。
	 *
	 * <h2>为什么必须独立于挂起池</h2>
	 * {@code resume} 内部还会再向挂起池（{@code aiRunExecutor}）提交一次真正的驱动任务；
	 * 若触发动作本身就跑在挂起池里，同一次续跑会占用该池两条线程（嵌套占用），
	 * 池满时构成自锁。本池的任务很短（一次 Redis 读 + 重建 + 转投），容量小、队列有界、
	 * 饱和即拒绝（{@code AbortPolicy}）—— 拒绝由 {@code ConfirmGate} 吞掉并如实回报
	 * {@code EXECUTOR_SATURATED}。
	 */
	@Bean(name = "aiResumeTrigger", destroyMethod = "shutdown")
	public ThreadPoolTaskExecutor aiResumeTrigger(AiProperties properties) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(properties.getResume().getTriggerPoolSize());
		executor.setMaxPoolSize(properties.getResume().getTriggerPoolSize());
		executor.setQueueCapacity(properties.getResume().getTriggerQueueCapacity());
		executor.setThreadNamePrefix("ai-resume-trigger-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		// 日志链路：POST /confirm 线程上的锚点随"死卡兜底续跑"任务一起进池
		// 身份传播：同上，续跑触发线程同样需要请求身份
		executor.setTaskDecorator(aiTaskDecorator());
		// 停机语义与运行池同口径（T-02）：触发动作虽短，但同样不得被 shutdownNow 中断 ——
		// 中断会把"一次续跑派发"变成异常出口，而异常出口在 ConfirmGate 里按失败/饱和处理。
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(TRIGGER_AWAIT_TERMINATION_SECONDS);
		executor.initialize();
		return executor;
	}

	/**
	 * 提交时刻同时抓取日志锚点（MDC）与身份上下文（TTL）。
	 *
	 * <p>顺序无关：两者各自捕获、各自恢复；MDC 只用于日志行首，TTL 才决定执行体看到的
	 * {@code tenantId/userId}。
	 */
	@Bean(name = "aiTaskDecorator")
	public static TaskDecorator aiTaskDecorator() {
		TaskDecorator mdc = new AiMdcTaskDecorator();
		TaskDecorator identity = new RequestContextTaskDecorator();
		return runnable -> identity.decorate(mdc.decorate(runnable));
	}

}
