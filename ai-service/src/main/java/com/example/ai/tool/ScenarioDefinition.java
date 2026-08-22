package com.example.ai.tool;

import lombok.Data;

import java.util.List;

/**
 * 场景定义模型（从 scenarios.yaml 加载）。
 *
 * 设计原则（业界 LangGraph 状态图模式）：
 *   1. 每个场景是一个状态图，节点是步骤，边是跳转
 *   2. 每个节点声明自己的工具集（progressive disclosure）
 *   3. 状态机维护全局连贯性，AI 不决定路由
 *   4. 跨步骤数据通过 AgentState.stepArtifacts 传递
 */
@Data
public class ScenarioDefinition {

    /** 场景 ID：EXPORT / IMPORT / ADD / MODIFY */
    private String id;

    /** 场景名称（中文） */
    private String name;

    /** 场景描述 */
    private String description;

    /** 步骤列表 */
    private List<StepDefinition> steps;

    /**
     * 步骤定义（状态图节点）。
     * 每个步骤是 context 边界，只暴露自己声明的工具集。
     */
    @Data
    public static class StepDefinition {

        /** 步骤 ID：SELECT_SCENARIO / SELECT_DEFS / VIEW_DEFS / QUERY_COND / PRECHECK / REVIEW / PUBLISH / RESULT */
        private String id;

        /** 下一节点 ID（END 表示流程结束） */
        private String next;

        /** 步骤描述（中文） */
        private String description;

        /** 该步骤允许使用的工具名列表 */
        private List<String> tools;
    }
}
