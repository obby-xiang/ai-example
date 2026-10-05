package com.example.configmgr.ai.tools;

import com.example.configmgr.ai.tool.ToolScope;
import com.example.configmgr.ai.tool.ToolRisk;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.data.service.ConfigDataService;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.service.JobService;
import com.example.configmgr.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Export workflow tools.
 */
@Component
@RequiredArgsConstructor
public class ExportTools {

    private final TaskService taskService;
    private final JobService jobService;
    private final ConfigDataService dataService;

    @Tool(name = "set_query_condition", description = "为指定配置项设置导出查询条件（JSON格式）")
    @ToolScope("task:EXPORT/QUERY_COND")
    @ToolRisk(ToolMeta.RiskLevel.WRITE)
    public String setQueryCondition(
            @ToolParam(description = "任务ID") Long taskId,
            @ToolParam(description = "配置定义编码") String defCode,
            @ToolParam(description = "查询条件 JSON，例如: {\"scopeKeys\":[\"HE\",\"HS\"]}") String conditionJson) {
        try {
            taskService.setCondition(taskId, defCode, conditionJson);
            return "已设置 " + defCode + " 的查询条件";
        } catch (Exception e) {
            return "设置查询条件失败: " + e.getMessage();
        }
    }

    @Tool(name = "get_row_count", description = "预估指定配置项的数据行数")
    @ToolScope({"task:EXPORT/QUERY_COND", "task:EXPORT/EXPORT"})
    @ToolRisk(ToolMeta.RiskLevel.READ)
    public String getRowCount(
            @ToolParam(description = "配置定义编码") String defCode,
            @ToolParam(description = "范围类型: GLOBAL/REGION/PROJECT", required = false) String scopeType,
            @ToolParam(description = "范围键值（地区或项目编码）", required = false) String scopeKey) {
        long count = dataService.count(defCode, scopeType, scopeKey);
        return String.format("%s 共有 %d 行数据", defCode, count);
    }

    @Tool(name = "start_export", description = "启动配置导出作业，开始将数据导出为Excel文件")
    @ToolScope("task:EXPORT/EXPORT")
    @ToolRisk(ToolMeta.RiskLevel.WRITE)
    public String startExport(
            @ToolParam(description = "任务ID") Long taskId) {
        try {
            Job job = jobService.createAndStart(taskId, Job.JobType.EXPORT);
            return String.format("导出作业已启动（作业 #%d），请稍后查看进度", job.getId());
        } catch (Exception e) {
            return "启动导出失败: " + e.getMessage();
        }
    }

    @Tool(name = "check_job_status", description = "查询作业状态和进度")
    @ToolScope("task:*")
    @ToolRisk(ToolMeta.RiskLevel.READ)
    public String checkJobStatus(
            @ToolParam(description = "作业ID") Long jobId) {
        try {
            Job job = jobService.findById(jobId);
            return String.format("作业 #%d [%s] 状态:%s 进度:%d/%d 错误:%d",
                    job.getId(), job.getJobType(), job.getStatus(),
                    job.getProgress(), job.getTotal(), job.getErrorCount());
        } catch (Exception e) {
            return "查询作业失败: " + e.getMessage();
        }
    }
}
