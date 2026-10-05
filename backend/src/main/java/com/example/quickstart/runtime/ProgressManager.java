package com.example.quickstart.runtime;

import com.example.quickstart.common.BizException;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 后台操作管理：同一任务互斥、进度模型、协作式取消。
 * 约束：取消在"当前配置项处理完成"的边界生效（不中断进行中的写入）。
 */
@Slf4j
@Component
public class ProgressManager {

    public enum Op {CHECK, IMPORT, PUBLISH, EXPORT}

    @Getter
    @Setter
    public static class Job {
        private String taskId;
        private Op op;
        private volatile int percent = 0;
        private volatile String phase = "";
        private volatile String message = "";
        private List<Map<String, Object>> items;
        private volatile boolean cancelRequested = false;
        private volatile boolean done = false;
    }

    private final Map<String, Job> running = new ConcurrentHashMap<>();

    @Value("${app.job.per-config-delay-ms:200}")
    private long perConfigDelayMs;

    @Value("${app.job.per-row-delay-ms:8}")
    private long perRowDelayMs;

    public Job begin(String taskId, Op op) {
        Job existing = running.get(taskId);
        if (existing != null && !existing.isDone()) {
            throw new BizException("该任务已有操作正在执行（" + existing.getOp() + "），请等待完成后再试");
        }
        Job job = new Job();
        job.setTaskId(taskId);
        job.setOp(op);
        running.put(taskId, job);
        return job;
    }

    public void finish(String taskId) {
        Job job = running.get(taskId);
        if (job != null) {
            job.setDone(true);
            running.remove(taskId);
        }
    }

    public Job get(String taskId) {
        return running.get(taskId);
    }

    public void requestCancel(String taskId) {
        Job job = running.get(taskId);
        if (job != null) {
            job.setCancelRequested(true);
        }
    }

    public void checkCancelled(Job job) {
        if (job.isCancelRequested()) {
            throw new BizException(409, "操作已取消");
        }
    }

    /** 模拟耗时：每配置项固定 + 每行增量 */
    public void simulateCost(int rows) {
        try {
            Thread.sleep(perConfigDelayMs + rows * perRowDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(409, "操作被中断");
        }
    }
}
