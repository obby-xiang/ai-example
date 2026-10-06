package com.example.configmgr.ai.exec;

import com.example.configmgr.ai.config.AiProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * AI 运行线程模型（S4.2 §2 exec/AiExecutorConfig + DC-12：禁用虚拟线程）。
 *
 * <h2>挂起专用有界平台线程池</h2>
 * 一轮对话占用一条线程直至终帧 —— 挂起等待（确认门 ≤ {@code app.ai.hitl.timeout}）
 * 就是在这一条线程上阻塞的。因此：
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

}
