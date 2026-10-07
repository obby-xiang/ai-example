package com.example.configmgr.ai.gate;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 受控的"启动导入作业"工具替身（B2 用例用）。
 *
 * <p>
 * 为什么需要它：{@code SpToolCallingManager} 的"放行 → 执行"路径经官方
 * {@code DefaultToolCallingManager} 按<b>名字</b>从 {@code prompt.getOptions().getToolCallbacks()}
 * 解析回调。要断言"确认门放行后工具真的被执行了"，就得有一个真的能被解析、
 * 且能被观测到"被调用过"的回调 —— 用桩件（而非真 {@code JobService}）避免测试去动数据库/作业表。
 *
 * <p>
 * 返回文本刻意带作业号，供"回填给模型的正是工具输出"的断言使用。
 */
public class FakeStartImportTool {

    /** 被调用次数（0 = 从未执行，用来断言"拒绝/超时/越 scope 时绝不执行"）。 */
    public final AtomicInteger invocations = new AtomicInteger();

    @Tool(name = "start_import", description = "受控替身：启动导入作业（不会真的写库）")
    public String startImport(@ToolParam(description = "任务ID") Long taskId) {
        this.invocations.incrementAndGet();
        return "导入作业已启动（作业 #4242），数据将写入暂存区，发布前可预览";
    }

}
