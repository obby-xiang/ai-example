package com.example.configmgr.ai.web.dto;

/**
 * {@code POST /api/ai/providers/{id}/test} 的结果（设计稿 §3.5）。
 *
 * <p>
 * <b>永远是 HTTP 200</b>："能不能连通"是一个<b>观测结果</b>而不是请求错误 —— 把
 * {@code AUTH_FAILED} 落成 401 会让前端与代理层把它当成"本接口鉴权失败"（语义错位）。
 * 判成功只看 {@code ok}；{@code code} 是稳定错误码（见 {@code AiConnectionProbe} 的四档）。
 *
 * @param status 上游 HTTP 状态（网络层失败为 null）
 * @param latencyMs 端到端耗时
 * @param modelCount 上游报告的模型数（ok=false 时 0）
 */
public record ProbeView(boolean ok, Integer status, long latencyMs, String code, String message, int modelCount) {
}
