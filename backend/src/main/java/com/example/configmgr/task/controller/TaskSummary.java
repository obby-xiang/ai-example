package com.example.configmgr.task.controller;

import com.example.configmgr.job.entity.Job;
import com.example.configmgr.task.entity.Task;

/**
 * 任务列表摘要：任务本体 + 配置项数 + 文件数 + 最新作业（进度/状态）。
 */
public record TaskSummary(Task task, int itemCount, int fileCount, Job latestJob) {
}
