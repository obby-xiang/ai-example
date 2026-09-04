import request from './request'

export const aiApi = {
  /** @param signal 改造 F1：AbortController.signal，停止按钮中断在途请求 */
  chat: (payload, signal) => request.post('/ai/chat', payload, { signal }).then(r => r.data),
  autoPrompt: (payload, signal) => request.post('/ai/auto-prompt', payload, { signal }).then(r => r.data),
  /** 前端工具执行结果回灌，恢复 agent loop */
  toolResult: (payload, signal) => request.post('/ai/tool-result', payload, { signal }).then(r => r.data),
  history: (taskId, sessionId) =>
    request.get('/ai/history', { params: { taskId, sessionId } }).then(r => r.data),
  /** 改造 B1：查询当前 session 的 pending 交互（刷新恢复用），无则 data=null */
  pending: (sessionId) => request.get('/ai/pending', { params: { sessionId } }).then(r => r.data),
  /** 改造 F1：协作式取消当前 session 的运行/pending（停止按钮） */
  cancelRun: (sessionId) => request.post('/ai/cancel', { sessionId }).then(r => r.data),
  clearHistory: (taskId) => request.delete('/ai/history', { params: { taskId } }),
  health: () => request.get('/ai/health').then(r => r.data),

  /**
   * 生成执行计划 —— 业界 Plan-and-Execute 模式 Phase 1。
   * 用户给目标，Planner 生成完整 plan（不执行），前端据此渲染 Plan 卡片让用户一次确认。
   * @param payload 同 ChatReq（含 message / currentScenario / currentStep）
   * @returns { plan, summary, isFallback, count, currentScenario, currentStep }
   */
  plan: (payload) => request.post('/ai/plan', payload).then(r => r.data),

  /**
   * 工具动态发现 —— 业界 MCP tools/list 等价。
   * 按场景+步骤过滤工具集（progressive disclosure），AI 永远只看到当前步骤的工具。
   * @param scenario 场景 ID
   * @param step     步骤 ID
   * @param format   raw(原生协议) / meta(含 autoExec/needConfirm 元数据)
   */
  tools: (scenario, step, format = 'meta') =>
    request.get('/ai/tools', { params: { scenario, step, format } }).then(r => r.data),

  /** 列出所有场景定义（状态图） */
  scenarios: () => request.get('/ai/scenarios').then(r => r.data)
}
