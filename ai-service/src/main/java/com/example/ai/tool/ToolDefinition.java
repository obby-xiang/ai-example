package com.example.ai.tool;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 工具定义模型（从 tools.yaml 加载）。
 *
 * 设计原则（业界 MCP 协议核心理念）：
 *   1. 工具 schema 自描述，模型靠 schema 理解能力边界（不投喂数据）
 *   2. autoExec/needConfirm/requireDoubleConfirm 三级信任分级
 *   3. scenarios/steps 限定工具适用范围，支持动态发现
 *
 * 信任分级（业界 Cursor Composer / Claude Code 模式）：
 *   - autoExec=true           - 自动执行，无需用户点击（只读/导航/选择类）
 *   - autoExec=false          - 暂停等待用户确认（破坏性/不可逆）
 *   - requireDoubleConfirm=true - 二次确认弹框（高危流程）
 */
@Data
public class ToolDefinition {

    /** 工具名，符合正则 ^[a-zA-Z0-9_-]+$ */
    private String name;

    /** 工具描述（自描述，AI 靠此理解能力边界） */
    private String description;

    /** 工具分类：FRONTEND（前端执行，暂停-恢复）/ BACKEND（后端同步执行） */
    private String kind;

    /** 是否自动执行（无需用户点击确认） */
    private boolean autoExec;

    /** 是否需要用户确认 */
    private boolean needConfirm;

    /** 是否需要二次确认弹框（高危流程） */
    private boolean requireDoubleConfirm;

    /** 适用场景列表 [EXPORT, IMPORT, ADD, MODIFY] */
    private List<String> scenarios;

    /** 适用步骤列表 [SELECT_SCENARIO, SELECT_DEFS, ...] */
    private List<String> steps;

    /** OpenAI 协议 JSON Schema（parameters 字段） */
    private Map<String, Object> parameters;
}
