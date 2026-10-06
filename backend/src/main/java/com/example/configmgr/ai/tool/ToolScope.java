package com.example.configmgr.ai.tool;

import java.lang.annotation.*;

/**
 * Declares the contexts (page/step) in which a tool method is available.
 * Used by ToolRegistry for progressive disclosure.
 *
 * Scope patterns:
 *   "*"                       - always available
 *   "page:tasks"              - on the task list page
 *   "page:definitions"        - on the definitions page
 *   "page:data"               - on the data browser page
 *   "task:*"                  - on any task wizard page
 *   "task:EXPORT"             - in any step of EXPORT task
 *   "task:EXPORT/SELECT_DEFS" - in the specific step of EXPORT task
 *   "task:IMPORT/UPLOAD"      - in the specific step of IMPORT task
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolScope {
    String[] value();
}
