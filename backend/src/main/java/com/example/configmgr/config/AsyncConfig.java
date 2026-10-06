package com.example.configmgr.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Async executor using JDK 21 virtual threads for @Async jobs.
 * With spring.threads.virtual.enabled=true Spring Boot auto-creates a virtual-thread
 * executor, but we expose an explicit bean so @Async in JobService can reference it.
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    @Override
    public Executor getAsyncExecutor() {
        // Virtual threads are enabled globally via spring.threads.virtual.enabled=true.
        // Spring Boot creates a SimpleAsyncTaskExecutor with virtual threads automatically,
        // so we just delegate to the default here.
        return null; // null = use Spring Boot's default virtual-thread executor
    }
}
