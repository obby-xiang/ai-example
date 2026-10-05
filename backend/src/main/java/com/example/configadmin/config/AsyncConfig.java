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
        // 虚拟线程：任务以行级 IO 为主，虚拟线程吞吐与隔离性最佳
        executor.setVirtualThreads(true);
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(64);
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
