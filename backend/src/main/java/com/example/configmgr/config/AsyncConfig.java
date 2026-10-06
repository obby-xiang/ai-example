package com.example.configmgr.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 业务 {@code @Async}（作业执行）线程模型。
 *
 * <h2>N3 裁决执行（DC-12：禁用虚拟线程是<b>全局</b>的）</h2>
 * 基座原为 {@code spring.threads.virtual.enabled=true} + 本类返回 {@code null}
 * （= 用 Spring Boot 自动建的虚拟线程执行器）：
 * <ul>
 * <li>虚拟线程与 {@code synchronized}/ThreadLocal 的 pinning、线程数无界，
 * 与"专用有界平台线程池承担长挂起"的线程模型冲突（ADR-2 重审 N1 的容量联动无从谈起）；</li>
 * <li>配置已翻为 {@code false}，此时 Spring Boot 的自动兜底是 {@code SimpleAsyncTaskExecutor}
 * —— 每任务一线程、无上限、无队列，作业并发一旦上来就是资源失控。</li>
 * </ul>
 * 因此这里显式给出<b>有界平台线程池</b>作为 {@code @Async} 执行器：
 * 容量与队列都有界、饱和即拒绝（{@code AbortPolicy}，拒绝发生在"事务提交后回调"里，
 * 由 {@code JobService} 的调用链如实冒泡，不静默丢作业）。
 *
 * <h2>为什么池大小是这几个数</h2>
 * 作业是"批量写库 + 逐行进度"的长任务（{@code app.job.batch-size}/{@code demo-batch-delay-ms}），
 * 并发量远小于 AI 挂起；4 条核心线程 / 16 条上限 + 200 队列足以覆盖
 * "一个任务中心点几下一键导出"的真实并发，同时把"无限线程"的可能性彻底关掉。
 * 该取值与 AI 侧 {@code app.ai.suspend.pool-size} 相互独立（两套负载不互相抢占）。
 *
 * <p>
 * {@code @EnableAsync} 由主类 {@code ConfigMgrApplication} 开启，这里只提供执行器。
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    /** 业务作业执行器 bean 名（{@code @Async} 默认按类型找 {@link Executor}）。 */
    @Bean(name = "applicationTaskExecutor", destroyMethod = "shutdown")
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("job-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
