package com.example.configmgr.ai.web;

/**
 * {@code POST /api/ai/cancel/client-request} 请求体（停止闭环 additive 端点）。
 *
 * <p>只用<b>会话 + 客户端请求 id</b> 精确定位一轮：{@code clientRequestId} 是前端在 /chat 发送前
 * 生成并写入本页签镜像的关联键，<b>不是 runId</b>。后端不扫描 run，也不接受调用方指定 runId；
 * run 由服务端映射（{@code ai:creq:<t>:<u>:<sessionId>}）反查。
 *
 * @param sessionId 页签会话 id（与 /chat 同一个）
 * @param clientRequestId 前端生成的请求 id（形状由服务端校验）
 */
public record ClientRequestCancelRequest(String sessionId, String clientRequestId) {
}
