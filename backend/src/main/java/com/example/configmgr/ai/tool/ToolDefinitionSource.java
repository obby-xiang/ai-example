package com.example.configmgr.ai.tool;

/**
 * 工具定义来源（YAML 阶段 / DB 阶段的共同抽象）。
 *
 * <p>
 * 读路径约定：{@link #load()} 在启动期调用一次并缓存为 {@link ToolSnapshot}，
 * **请求路径不得再调用**（否则每轮 chat 都摸一次 IO/DB）。
 */
public interface ToolDefinitionSource {

    /** 当前快照；实现必须容忍"来源不存在"（返回空快照 ⇒ 全部回落注解）。 */
    ToolSnapshot load();

    /** 快照版本标识（日志 / 诊断 / 缓存键）。 */
    String version();
}
