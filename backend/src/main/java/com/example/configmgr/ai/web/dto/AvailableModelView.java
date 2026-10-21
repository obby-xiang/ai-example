package com.example.configmgr.ai.web.dto;

/**
 * {@code GET /api/ai/models} 的一行（设计稿 §3.5：「对话面板用」的跨 provider 可用模型）。
 *
 * <h2>只列<b>此刻真的能选</b>的模型</h2>
 * 三个条件同时成立才出现：provider {@code enabled}、key 可解析、模型行 {@code enabled}。
 * 面板因此不需要再做任何"这个能不能用"的判断 —— 列表即真值（"选了却打不通"是最糟的交互）。
 *
 * <h2>能力位为什么是三个扁平布尔</h2>
 * 与 {@link ModelView} 同口径（同一批字段、同一个含义），面板按同一个词表渲染后缀标记；
 * 不新造嵌套的 {@code capabilities} 对象 —— 两套形状会让"设置页"与"面板"各写一份映射。
 *
 * @param providerId 库内 id；{@code null} = env 合成行（尚未落库）
 * @param modelId 请求 {@code /api/ai/chat} 时要回填的值
 * @param isDefault 该 provider 的 {@code is_default} 标记（面板后缀用）
 */
public record AvailableModelView(Long providerId, String providerCode, String providerName, String modelId,
        String displayName, boolean supportsToolCalling, boolean supportsVision, boolean supportsReasoning,
        Integer maxContext, Integer maxOutput, boolean isDefault) {
}
