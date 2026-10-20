package com.example.configmgr.ai.tool;

import java.lang.annotation.*;

/**
 * Declares the execution category of a tool method (for batch stop policy).
 *
 * <p>
 * 类别与风险等级（{@link ToolRisk}）是两个维度：风险等级回答"执行前要不要人审"，
 * 类别回答"这一批调用里它失败后要不要停"（P1-10 批策略）。类别名字与判据以
 * {@code docs/research/调研-AI工具执行治理与互斥-2026-10-19.md} §4.1/§4.2 为准。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolCategory {

    ToolMeta.Category value();

}
