package com.example.configadmin.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 快速实施任务统一摘要（任务中心列表元素）：
 * 导出任务与导入批次合并展示，均持久化于 H2（创建即入库，历史可查）。
 */
public record TaskSummary(
        String key,               // 唯一键：EXPORT-{id} / IMPORT-{id}（两表 id 独立，需类型前缀区分）
        Long id,                  // 表内 id（跳转向导恢复任务用）
        String taskType,          // EXPORT / IMPORT
        String taskTypeName,      // 导出配置 / 导入配置
        String title,             // 展示名：导出任务 #id / 批次名
        String status,
        int progress,
        String message,
        List<String> defCodes,    // 涉及配置编码
        int filesCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
