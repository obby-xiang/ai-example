package com.example.configmgr.job.service;

import com.example.configmgr.job.entity.Job;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 作业异步执行器（独立 Bean）。
 *
 * 注意：@Async 通过代理生效，必须放在独立组件中由外部调用；
 * 若 JobService 内部自调用 @Async 方法会绕过代理，作业会阻塞 HTTP 线程同步执行，
 * 导致"启动后无法取消、接口长时间无响应"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncJobExecutor {

    private final ExportJobRunner exportJobRunner;
    private final PrecheckJobRunner precheckJobRunner;
    private final ImportJobRunner importJobRunner;
    private final PublishJobRunner publishJobRunner;
    private final JobCancellationRegistry cancellationRegistry;

    @Async
    public void execute(Job job) {
        log.info("Starting job {} type={} taskId={}", job.getId(), job.getJobType(), job.getTaskId());
        try {
            switch (job.getJobType()) {
                case EXPORT -> exportJobRunner.run(job);
                case PRECHECK -> precheckJobRunner.run(job);
                case IMPORT -> importJobRunner.run(job);
                case PUBLISH -> publishJobRunner.run(job);
            }
        } catch (Exception e) {
            log.error("Job {} failed unexpectedly: {}", job.getId(), e.getMessage(), e);
        } finally {
            cancellationRegistry.unregister(job.getId());
        }
    }
}
