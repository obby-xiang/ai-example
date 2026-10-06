package com.example.spike.tools;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具"正在执行"的信标：让外部（验证脚本）能确定"取消信号到达时工具确实还在跑"，
 * 从而把取消时刻与工具执行窗口对齐（否则只能靠猜时间）。
 */
@Component
public class ToolActivityBeacon {

    private final ConcurrentMap<String, Map<String, Object>> active = new ConcurrentHashMap<>();

    public void enter(String runId, String tool, String detail) {
        Map<String, Object> m = new ConcurrentHashMap<>();
        m.put("runId", runId);
        m.put("tool", tool);
        m.put("detail", detail);
        m.put("enteredAtMs", System.currentTimeMillis());
        m.put("slices", new AtomicInteger());
        active.put(runId, m);
    }

    public void slice(String runId) {
        Map<String, Object> m = active.get(runId);
        if (m != null) {
            ((AtomicInteger) m.get("slices")).incrementAndGet();
        }
    }

    public void exit(String runId, String outcome) {
        Map<String, Object> m = active.remove(runId);
        if (m != null) {
            m.put("exitedAtMs", System.currentTimeMillis());
            m.put("outcome", outcome);
            m.put("slices", ((AtomicInteger) m.get("slices")).get());
            last.put(runId, m);
        }
    }

    private final ConcurrentMap<String, Map<String, Object>> last = new ConcurrentHashMap<>();

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", active);
        out.put("lastFinished", last);
        return out;
    }

    /** 该 run 是否"开始过工具执行"（含正在执行中）——用于重试前的副作用判定。 */
    public boolean hasActivity(String runId) {
        return active.containsKey(runId) || last.containsKey(runId);
    }
}
