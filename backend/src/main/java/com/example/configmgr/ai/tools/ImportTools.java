package com.example.configmgr.ai.tools;

import com.example.configmgr.ai.tool.ToolScope;
import com.example.configmgr.ai.tool.ToolRisk;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.service.JobService;
import com.example.configmgr.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Import workflow tools.
 */
@Component
@RequiredArgsConstructor
public class ImportTools {

    private final TaskService taskService;
    private final JobService jobService;

    @Tool(name = "start_precheck", description = "启动预检查作业，验证上传的Excel文件格式和数据")
    @ToolScope("task:IMPORT/PRECHECK")
    @ToolRisk(ToolMeta.RiskLevel.WRITE)
    public String startPrecheck(
            @ToolParam(description = "任务ID") Long taskId) {
        try {
            Job job = jobService.createAndStart(taskId, Job.JobType.PRECHECK);
            return String.format("预检查已启动（作业 #%d），正在检查数据格式和依赖关系…", job.getId());
        } catch (Exception e) {
            return "启动预检查失败: " + e.getMessage();
        }
    }

    @Tool(name = "start_import", description = "启动导入作业，将数据写入暂存区")
    @ToolScope("task:IMPORT/IMPORT")
    @ToolRisk(ToolMeta.RiskLevel.WRITE)
    public String startImport(
            @ToolParam(description = "任务ID") Long taskId) {
        try {
            Job job = jobService.createAndStart(taskId, Job.JobType.IMPORT);
            return String.format("导入作业已启动（作业 #%d），数据将写入暂存区，发布前可预览", job.getId());
        } catch (Exception e) {
            return "启动导入失败: " + e.getMessage();
        }
    }

    @Tool(name = "start_publish", description = "启动发布作业，将暂存数据正式发布到生产数据（高风险，需要用户确认）")
    @ToolScope("task:IMPORT/PUBLISH")
    @ToolRisk(ToolMeta.RiskLevel.DANGER)
    public String startPublish(
            @ToolParam(description = "任务ID") Long taskId,
            @ToolParam(description = "用户确认发布（必须传入 true）") Boolean confirmed) {
        if (confirmed == null || !confirmed) {
            return "发布操作未确认，已取消。如需发布，请明确确认后重新调用。";
        }
        try {
            Job job = jobService.createAndStart(taskId, Job.JobType.PUBLISH);
            return String.format("发布作业已启动（作业 #%d），正在将数据写入正式库…", job.getId());
        } catch (Exception e) {
            return "启动发布失败: " + e.getMessage();
        }
    }
}
