package com.example.configmgr.job.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 作业取消标志注册表。作业执行器在循环批次之间检查标志，
 * 用户取消后尽早停止（而非只改数据库状态）。
 */
@Component
public class JobCancellationRegistry {

    private final ConcurrentMap<Long, AtomicBoolean> flags = new ConcurrentHashMap<>();

    public void register(Long jobId) {
        flags.put(jobId, new AtomicBoolean(false));
    }

    public boolean isCancelled(Long jobId) {
        AtomicBoolean flag = flags.get(jobId);
        return flag != null && flag.get();
    }

    public void cancel(Long jobId) {
        AtomicBoolean flag = flags.get(jobId);
        if (flag != null) {
            flag.set(true);
        }
    }

    public void unregister(Long jobId) {
        flags.remove(jobId);
    }
}
