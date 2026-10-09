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
 *   "page:export"             - reserved granularity: wizard pages mirror the route page id
 *                               (page ≡ pageId), but no tool uses this tag yet -- wizard
 *                               disclosure is driven by "task:*" (see below)
 *   "page:import"             - reserved granularity, same status as "page:export"
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
