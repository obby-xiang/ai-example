package com.example.configmgr.ai.kb;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class KbSchedulerConfig {

    @Bean(name = "kbRefreshScheduler")
    public ThreadPoolTaskScheduler kbRefreshScheduler(@Qualifier("aiTaskDecorator") TaskDecorator taskDecorator) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("kb-refresh-");
        scheduler.setTaskDecorator(taskDecorator);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(2);
        return scheduler;
    }
}
