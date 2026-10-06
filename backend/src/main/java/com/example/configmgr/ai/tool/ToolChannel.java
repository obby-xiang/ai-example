package com.example.configmgr.ai.tool;

import java.lang.annotation.*;

/**
 * 声明工具的执行通道（谁真正执行这次调用）。
 *
 * <p>
 * 风险等级（{@link ToolRisk}）与执行通道是两件正交的事：风险等级决定"要不要人审"，
 * 执行通道决定"后端能不能执行"。前端工具（{@code FRONTEND}）的副作用发生在浏览器里
 * （打开在线编辑器、触发下载、页面跳转），后端方法体只是哨兵桩，绝不允许直接执行；
 * 这类调用必须由 {@code gate/SpToolCallingManager} 挂起并向 SSE 下发
 * {@code frontend_tool_request}，等前端回灌结果后作为工具结果交回官方循环。
 *
 * <p>
 * 未标注者一律按 {@link ToolMeta.Channel#BACKEND} 处理。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolChannel {
    ToolMeta.Channel value();
}
