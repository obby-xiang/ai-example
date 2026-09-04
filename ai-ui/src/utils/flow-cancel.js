/**
 * ====== 改造 F3：多步流程的协作式取消标志 ======
 *
 * 设计（plan 文档 §4 改造 F）：打断=轮次/步骤边界停止+保留现场，不做指令级中断、不做自动回滚。
 * run_flow 等多步工具在「步骤间」检查本标志，命中即以 user_stopped 提前返回（已完成步骤不回滚）。
 *
 * 独立成模块而非放 ai store：frontend-tool-registry.js 被 stores/ai.js 静态引用，
 * 反向引用 store 会形成 ESM 循环依赖；模块级单例标志两边都可安全使用。
 *
 * 生命周期：chat() 发起新一轮时 resetFlowCancel()；stopRun() 时 requestFlowCancel()。
 */
let cancelled = false

/** 请求取消（停止按钮/会话取消时调用） */
export function requestFlowCancel() { cancelled = true }

/** 新一轮对话开始时复位 */
export function resetFlowCancel() { cancelled = false }

/** 多步流程在步骤边界查询 */
export function isFlowCancelled() { return cancelled }
