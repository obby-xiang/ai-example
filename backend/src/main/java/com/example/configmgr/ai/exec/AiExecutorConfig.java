package com.example.configmgr.ai.exec;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.run.StreamWatchdog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 运行线程模型（S4.2 §2 exec/AiExecutorConfig + DC-12：禁用虚拟线程）。
 *
 * <h2>挂起专用有界平台线程池</h2>
 * 一轮对话占用一条线程直至终帧 —— 挂起等待（确认门 ≤ {@code app.ai.hitl.confirm-timeout}，
 * 默认 240s）就是在这一条线程上阻塞的。因此：
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
 */
@Configuration
public class AiExecutorConfig {

	@Bean(name = "aiRunExecutor", destroyMethod = "shutdown")
	public ThreadPoolTaskExecutor aiRunExecutor(AiProperties properties) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(properties.getSuspend().getPoolSize());
		executor.setMaxPoolSize(properties.getSuspend().getPoolSize());
		executor.setQueueCapacity(0);
		executor.setThreadNamePrefix("ai-suspend-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.setWaitForTasksToCompleteOnShutdown(false);
		executor.initialize();
		return executor;
	}

	/**
	 * 流式看门狗的调度器（韧性棒）：每 {@link StreamWatchdog#TICK_MILLIS}ms 一次节拍，
	 * 判定首包静默 / 事件间静默 / 总预算 / 取消。
	 *
	 * <p>
	 * 单条<b>平台</b>守护线程（DC-12 禁虚拟线程）：节拍体只做常数次原子读与一次 Redis GET
	 * （仅在未命中加速态时），不阻塞、不发起上游请求，因此不会与挂起池争线程。
	 * 轮与轮之间共用同一个调度器（任务按 runId 命名，便于日志定位）。
	 */
	@Bean(name = "aiStreamWatchdog", destroyMethod = "shutdownNow")
	public ScheduledExecutorService aiStreamWatchdog() {
		AtomicInteger counter = new AtomicInteger();
		return Executors.newScheduledThreadPool(2, runnable -> {
			Thread thread = new Thread(runnable, "ai-stream-watchdog-" + counter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
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
		executor.setWaitForTasksToCompleteOnShutdown(false);
		executor.initialize();
		return executor;
	}

}
