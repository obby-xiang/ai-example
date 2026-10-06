package com.example.spike.tools;

import com.example.spike.cancel.CancellationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 官方 @Tool 注解工具（Schema 由 Spring AI 自动生成）。
 *
 * 说明：官方工具方法签名里拿不到"当前 toolCallId"——ToolContext 只暴露
 * getContext() 与 getToolCallHistory()，所以 in-loop 执行台账里 toolCallId 记 null，
 * 重复判定改用 (runId, name, arguments, 顺序) 对账，或从 ToolExecutionResult 侧取响应 id。
 */
@Component
public class SpikeTools {

    private static final Logger log = LoggerFactory.getLogger(SpikeTools.class);

    private final CancellationRegistry cancelRegistry;
    private final ToolExecutionLog execLog;
    private final ToolActivityBeacon beacon;

    public SpikeTools(CancellationRegistry cancelRegistry, ToolExecutionLog execLog,
                      ToolActivityBeacon beacon) {
        this.cancelRegistry = cancelRegistry;
        this.execLog = execLog;
        this.beacon = beacon;
    }

    @Tool(description = "返回服务器当前时间。无参数。")
    public String getServerTime(ToolContext toolContext) {
        long start = System.currentTimeMillis();
        String runId = runId(toolContext);
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        execLog.record("INTERNAL_LOOP", runId, null, "getServerTime", "{}",
                System.currentTimeMillis() - start, "OK");
        log.info("TOOL_EXEC source=INTERNAL_LOOP runId={} name=getServerTime", runId);
        return now;
    }

    @Tool(description = """
            长耗时任务，用于模拟导出/检查等阻塞型作业。
            cooperative=true 时按 200ms 分片睡眠，并在每个分片边界检查取消标志：
            一旦该轮对话被取消就立即返回以 CANCELLED 开头的文本，不再睡满。
            cooperative=false 时使用一次性 Thread.sleep，不响应取消（对照组）。
            """)
    public String longTask(
            @ToolParam(description = "任务时长（秒）") int seconds,
            @ToolParam(description = "是否按分片检查取消标志，默认 true") Boolean cooperative,
            ToolContext toolContext) {

        long start = System.currentTimeMillis();
        String runId = runId(toolContext);
        boolean coop = cooperative == null || cooperative;
        int slices = 0;
        beacon.enter(runId, "longTask", "seconds=" + seconds + ",cooperative=" + coop);

        if (coop) {
            long deadline = start + seconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                if (cancelRegistry.isCancelled(runId)) {
                    slices++;
                    long dur = System.currentTimeMillis() - start;
                    beacon.exit(runId, "CANCELLED");
                    execLog.record("INTERNAL_LOOP", runId, null, "longTask",
                            "{\"seconds\":" + seconds + ",\"cooperative\":true}",
                            dur, "CANCELLED_AFTER_" + slices + "_SLICES");
                    log.info("TOOL_EXEC source=INTERNAL_LOOP runId={} name=longTask outcome=CANCELLED durMs={} slices={}",
                            runId, dur, slices);
                    return "CANCELLED after " + dur + "ms (checked cancel flag at slice boundary)";
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                slices++;
                beacon.slice(runId);
            }
        } else {
            try {
                Thread.sleep(seconds * 1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        long dur = System.currentTimeMillis() - start;
        beacon.exit(runId, "OK");
        execLog.record("INTERNAL_LOOP", runId, null, "longTask",
                "{\"seconds\":" + seconds + ",\"cooperative\":" + coop + "}", dur, "OK");
        log.info("TOOL_EXEC source=INTERNAL_LOOP runId={} name=longTask outcome=OK durMs={} slices={}",
                runId, dur, slices);
        return "TASK_DONE after " + dur + "ms, cancel-checked=" + coop + ", slices=" + slices;
    }

    private String runId(ToolContext toolContext) {
        if (toolContext == null) {
            return "unknown";
        }
        Map<String, Object> ctx = toolContext.getContext();
        Object v = ctx == null ? null : ctx.get("runId");
        return v == null ? "unknown" : String.valueOf(v);
    }
}
