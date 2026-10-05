package com.example.quickstart.service;

import com.example.quickstart.entity.Task;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;

/**
 * 后台异步执行器：耗时操作在专用线程池运行，通过 SSE 推进度。
 */
@Slf4j
@Service
public class AsyncRunner {

    private final ExportRunner exportRunner;
    private final ImportRunner importRunner;
    private final Executor jobExecutor;

    public AsyncRunner(ExportRunner exportRunner, ImportRunner importRunner,
                       @Qualifier("jobTaskExecutor") Executor jobExecutor) {
        this.exportRunner = exportRunner;
        this.importRunner = importRunner;
        this.jobExecutor = jobExecutor;
    }

    public void runExport(Task task, String sessionId) {
        jobExecutor.execute(() -> {
            try {
                exportRunner.run(task, sessionId);
            } catch (Exception e) {
                log.error("导出任务执行失败 task={}", task.getId(), e);
            }
        });
    }

    public void runCheck(Task task, String sessionId) {
        jobExecutor.execute(() -> {
            try {
                importRunner.runCheck(task, sessionId);
            } catch (Exception e) {
                log.error("检查任务执行失败 task={}", task.getId(), e);
            }
        });
    }

    public void runImport(Task task, String sessionId) {
        jobExecutor.execute(() -> {
            try {
                importRunner.runImport(task, sessionId);
            } catch (Exception e) {
                log.error("导入任务执行失败 task={}", task.getId(), e);
            }
        });
    }

    public void runPublish(Task task, String sessionId) {
        jobExecutor.execute(() -> {
            try {
                importRunner.runPublish(task, sessionId);
            } catch (Exception e) {
                log.error("发布任务执行失败 task={}", task.getId(), e);
            }
        });
    }
}
