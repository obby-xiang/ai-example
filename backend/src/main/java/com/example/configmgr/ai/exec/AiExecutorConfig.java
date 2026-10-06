package com.example.configmgr.ai.exec;

import com.example.configmgr.ai.config.AiProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * AI 运行线程模型（S4.2 §2 exec/AiExecutorConfig + DC-12：禁用虚拟线程）。
 *
 * <p>
 * 专用<b>有界平台线程池</b>：一轮对话占用一条线程直至终帧（后续棒次的挂起等待也在这条线程上），
 * 因此队列有界、池大小可配（{@code app.ai.suspend.pool-size}），饱和即拒绝而不是无限堆积——
 * 拒绝由 controller 转成 503。基座原先用 {@code Thread.ofVirtual()} 起运行线程，本棒改为平台线程，
 * 以满足 DC-12。
 */
@Configuration
public class AiExecutorConfig {

	/** Spring MVC 的异步请求超时由 SseEmitter 自身超时控制，这里只提供承载线程。 */
	@Bean(name = "aiRunExecutor", destroyMethod = "shutdown")
	public ThreadPoolTaskExecutor aiRunExecutor(AiProperties properties) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(properties.getSuspend().getPoolSize());
		executor.setMaxPoolSize(properties.getSuspend().getPoolSize());
		executor.setQueueCapacity(properties.getSuspend().getQueueCapacity());
		executor.setThreadNamePrefix("ai-run-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.setWaitForTasksToCompleteOnShutdown(false);
		executor.initialize();
		return executor;
	}

}
