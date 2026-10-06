package com.example.configmgr.ai.tool;

import java.lang.annotation.*;

/**
 * Declares the risk level of a tool method (for HITL confirmation decisions).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolRisk {
    ToolMeta.RiskLevel value() default ToolMeta.RiskLevel.READ;
}
