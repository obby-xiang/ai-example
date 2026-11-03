package com.example.configmgr.task.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 任务 SSE 跨实例帧的<b>有界</b>派发/订阅执行器（线程修复-后端）。
 *
 * <h2>为什么必须显式给</h2>
 * {@link org.springframework.data.redis.listener.RedisMessageListenerContainer} 在未设置
 * {@code taskExecutor} 时自建 {@code SimpleAsyncTaskExecutor}：<b>每条消息新建一条平台线程</b>，
 * 无并发上限、非守护。任务帧的分发体是"带 15s 写预算的 SSE 写出"，慢连接会让每条分发线程
 * 阻塞最长一个预算 —— 帧速率一高就是无界线程创建（~1MB 栈/条，且拖住 JVM 退出）。
 *
 * <h2>取值</h2>
 * 分发体大部分时间在等 socket 写（最坏 15s 预算），2~4 条线程足以覆盖"少量浏览器订阅"；
 * 队列 256 是缓冲上限，满了就<b>丢帧</b>（见 {@code RedisTaskEventBus} 的拒绝计数）——
 * 帧是状态投影（at-most-once），前端 EventSource 重连 + 3s 快照轮询兜底，丢帧不丢状态。
 *
 * <h2>订阅执行器必须独立</h2>
 * {@link org.springframework.data.redis.listener.RedisMessageListenerContainer} 的
 * {@code subscriptionExecutor} 跑 subscribe/unsubscribe，{@code taskExecutor} 跑消息派发。
 * 两者共用一个池时，派发体（带 15s 预算的 socket 写）会把队列占满，订阅/退订任务排在后面
 * 得不到执行 —— 浏览器重连风暴下表现为"越重连越订阅不上"。因此这里再给一个<b>独立、有界</b>
 * 的订阅池：core 1 / max 2 / queue 64（订阅/退订是毫秒级操作）。
 *
 * <p>这两个 Bean 只经 Spring 装配，故不手工 {@code initialize()}：容器会在
 * {@code afterPropertiesSet()} 里初始化。直接 {@code new} 的调用路径不经过本类
 * （{@code RedisTaskEventBus} 会自建同规格守护池）。
 */
@Configuration
public class TaskEventDispatchConfig {

    /** 分发执行器 bean 名（{@code RedisTaskEventBus} 按名注入）。 */
    public static final String DISPATCH_EXECUTOR_BEAN = "taskEventDispatchExecutor";

    /** 订阅/退订执行器 bean 名（{@code RedisTaskEventBus} 按名注入）。 */
    public static final String SUBSCRIPTION_EXECUTOR_BEAN = "taskEventSubscriptionExecutor";

    @Bean(name = DISPATCH_EXECUTOR_BEAN, destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor taskEventDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("task-event-dispatch-");
        executor.setDaemon(true);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    /**
     * 订阅/退订专用有界池：与派发池分开，避免慢写出把订阅操作饿死。
     *
     * <p>队列满由 {@code RedisTaskEventBus} 的包装器改为<b>调用线程内联执行</b>并计数/告警，
     * 不丢 subscribe/unsubscribe（丢了会让容器内部的等待悬挂）。
     */
    @Bean(name = SUBSCRIPTION_EXECUTOR_BEAN, destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor taskEventSubscriptionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("task-event-subscribe-");
        executor.setDaemon(true);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
