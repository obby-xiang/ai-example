package com.example.spike.tools;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工具执行台账：每一次"工具被真正执行"都留一条记录（含 toolCallId），
 * 用于判定"重复 toolCallId 是否导致重复执行"。
 */
@Component
public class ToolExecutionLog {

    private final AtomicLong seq = new AtomicLong();
    private final List<Map<String, Object>> entries = new CopyOnWriteArrayList<>();

    public Map<String, Object> record(String source, String runId, String toolCallId, String name,
                                      String arguments, long durationMs, String outcome) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("seq", seq.incrementAndGet());
        e.put("tsMs", System.currentTimeMillis());
        e.put("source", source);
        e.put("runId", runId);
        e.put("toolCallId", toolCallId);
        e.put("name", name);
        e.put("arguments", arguments);
        e.put("durationMs", durationMs);
        e.put("outcome", outcome);
        synchronized (entries) {
            entries.add(e);
        }
        return e;
    }

    public List<Map<String, Object>> all() {
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }

    public List<Map<String, Object>> since(long seqExclusive) {
        synchronized (entries) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> e : entries) {
                if ((Long) e.get("seq") > seqExclusive) {
                    out.add(e);
                }
            }
            return out;
        }
    }

    public long currentSeq() {
        return seq.get();
    }

    public int countById(String toolCallId) {
        int n = 0;
        synchronized (entries) {
            for (Map<String, Object> e : entries) {
                if (toolCallId.equals(e.get("toolCallId"))) {
                    n++;
                }
            }
        }
        return n;
    }

    public boolean hasExecutionsForRun(String runId) {
        synchronized (entries) {
            for (Map<String, Object> e : entries) {
                if (runId.equals(e.get("runId"))) {
                    return true;
                }
            }
        }
        return false;
    }

    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }
}
