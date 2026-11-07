package com.example.configmgr.ai.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the risk level of a tool method (for HITL confirmation decisions).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolRisk {
    ToolMeta.RiskLevel value() default ToolMeta.RiskLevel.READ;
}
