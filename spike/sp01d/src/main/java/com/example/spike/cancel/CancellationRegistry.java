package com.example.spike.cancel;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 取消标志注册表。语义对齐参照分支的 JobCancellationRegistry
 * （register / isCancelled / cancel / unregister 四方法 + ConcurrentMap + AtomicBoolean），
 * 只把 key 由 Long jobId 换成 String runId（AI 轮次 id）。
 *
 * 作业执行器在"批次之间"检查标志，本 spike 的 @Tool 在 sleep 分片之间检查标志。
 * 已知缺口：纯 JVM 内存，多实例下取消信号只对持有该流的实例有效（见决策卡 §V-d3）。
 */
@Component
public class CancellationRegistry {

    private final ConcurrentMap<String, AtomicBoolean> flags = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> registeredAt = new ConcurrentHashMap<>();

    public void register(String runId) {
        flags.put(runId, new AtomicBoolean(false));
        registeredAt.put(runId, System.currentTimeMillis());
    }

    public boolean isCancelled(String runId) {
        AtomicBoolean flag = flags.get(runId);
        return flag != null && flag.get();
    }

    public void cancel(String runId) {
        AtomicBoolean flag = flags.get(runId);
        if (flag != null) {
            flag.set(true);
        }
    }

    public void unregister(String runId) {
        flags.remove(runId);
        registeredAt.remove(runId);
    }

    public boolean isRegistered(String runId) {
        return flags.containsKey(runId);
    }

    public Set<String> registered() {
        return Set.copyOf(flags.keySet());
    }

    public Long registeredAt(String runId) {
        return registeredAt.get(runId);
    }
}
