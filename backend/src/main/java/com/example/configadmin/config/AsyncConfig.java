package com.example.configadmin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executors;

/** 长任务异步执行器：JDK21 虚拟线程，导出/检查/导入/发布互不阻塞。 */
@Configuration
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public TaskExecutor taskExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        // 平台线程池：导出/检查/导入/发布 + AI 对话流（blockLast 阻塞式等待）共享；
        // 虚拟线程在 H2/POI 等阻塞 IO 上存在载波钉死风险，平台线程池行为更可预期
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("async-task-");
        executor.initialize();
        return executor;
    }

    /** SSE 心跳线程（轻量，独立于任务线程池） */
    @Bean(name = "heartbeatExecutor")
    public java.util.concurrent.ScheduledExecutorService heartbeatExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }
}
