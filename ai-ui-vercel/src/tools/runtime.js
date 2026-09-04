/**
 * 暂停-恢复运行时 —— 前端 AI runtime 的核心机制(对齐设计文档 Stage 5/6)。
 *
 * 范式迁移:旧架构用 resumeToken 在后端暂停 agent loop;新架构把暂停
 * 移进 tool 的 execute Promise 内。execute 设置 pendingInteraction(响应式),
 * AiPanel 据此渲染交互卡片(确认按钮 / SchemaFormRenderer 表单),
 * 用户操作后 resolve -> execute 返回 -> SDK 把结果回灌模型继续 loop(下一 step)。
 *
 * 数据流:
 *   1. streamText 的 fullStream 发出 tool-call({toolCallId, toolName, args})
 *   2. SDK 调用 tool.execute(args) -> 对于确认/表单工具,execute 设置 pendingInteraction 并 await
 *   3. AiPanel watch pendingInteraction -> 渲染卡片交互区
 *   4. 用户操作 -> AiPanel 调 resolveInteraction(result) -> Promise resolve
 *   5. execute 返回结果 -> fullStream 发出 tool-result -> 模型续行
 *
 * 改造 C4：pending 交互带 TTL(默认 10 分钟),超时自动以 interaction_expired 解决,
 *   AiPanel 显示倒计时(AG-UI 超时安全默认:超时拒绝/取消而非自动提交)。
 * 改造 D3d：挂起时记录 dataVersion,resolve 时对比,变了则在结果前置系统提示
 *   (提示并强制现查但不阻断——对齐 plan v1.1 决策)。
 */
import { shallowRef } from 'vue'
import { getDataVersion } from '@/utils/workspace-version'

/** pending 交互超时时间(毫秒),对齐后端 app.agent.waiting-tool-ttl-minutes=10 */
export const PENDING_TTL_MS = 10 * 60 * 1000

/**
 * pendingInteraction 形态:
 *   { toolCallId, type: 'confirm'|'form', toolName, args, impact, resolve, reject, result,
 *     createdAt, expiresAt, suspendedDataVersion }
 *   result 字段在 resolve 后回填(供 AiPanel 显示最终态)
 */
export const pendingInteraction = shallowRef(null)

let expiryTimer = null

function armExpiryTimer() {
  clearExpiryTimer()
  expiryTimer = setTimeout(async () => {
    const p = pendingInteraction.value
    if (!p) return
    // 改造 B3/C4：恢复态交互没有旧 Promise 可 resolve,超时仅本地标记卡片过期
    if (p.recovered) {
      try {
        const { useAiStore } = await import('@/stores/ai')
        useAiStore().expireRecoveredInteraction(p.toolCallId)
      } catch (e) { /* ignore */ }
      clearExpiryTimer()
      pendingInteraction.value = null
      return
    }
    // 超时安全默认:以「已过期」结果解决,让模型知道交互已失效
    resolveInteraction({ ok: false, message: 'interaction_expired', reason: '交互超时(10分钟未响应),已自动取消' })
  }, PENDING_TTL_MS)
}

function clearExpiryTimer() {
  if (expiryTimer) { clearTimeout(expiryTimer); expiryTimer = null }
}

/**
 * 工具 execute 调用:暂停等待用户确认。
 * @returns Promise<{ confirmed: boolean }>
 *   - confirmed=true: 用户确认执行
 *   - confirmed=false: 用户取消
 */
export function waitForConfirm(toolCallId, toolName, args, impact) {
  return new Promise((resolve, reject) => {
    pendingInteraction.value = {
      toolCallId,
      type: 'confirm',
      toolName,
      args,
      impact: impact || '',
      resolve: (val) => { resolve(val) },
      reject,
      createdAt: Date.now(),
      expiresAt: Date.now() + PENDING_TTL_MS,
      suspendedDataVersion: getDataVersion()
    }
    armExpiryTimer()
  })
}

/**
 * 工具 execute 调用:暂停渲染表单等待用户提交。
 * @param toolCallId
 * @param args          表单定义 { formTitle, submitLabel, fields }
 * @param toolName      工具名(默认 collect_user_input;excel_import 传 'excel_import' 以区分卡片标题)
 * @returns Promise<{ ok:boolean, data?:object, cancelled?:boolean }>
 */
export function waitForForm(toolCallId, args, toolName = 'collect_user_input') {
  return new Promise((resolve, reject) => {
    pendingInteraction.value = {
      toolCallId,
      type: 'form',
      toolName,
      args,
      impact: '',
      resolve: (val) => { resolve(val) },
      reject,
      createdAt: Date.now(),
      expiresAt: Date.now() + PENDING_TTL_MS,
      suspendedDataVersion: getDataVersion()
    }
    armExpiryTimer()
  })
}

/**
 * ====== 改造 B3：刷新恢复时重建 pendingInteraction ======
 * 旧 execute Promise 已随刷新销毁,故 resolve=null(不可回灌旧 Promise);
 * AiPanel 识别 recovered 标记后走 aiStore.submitRecoveredInteraction 重开一轮 loop(B4)。
 * @returns 是否成功挂载
 */
export function recoverInteraction({ toolCallId, type, toolName, args, impact, suspendedDataVersion }) {
  if (!toolCallId || !type) return false
  pendingInteraction.value = {
    toolCallId,
    type,
    toolName,
    args: args || {},
    impact: impact || '',
    resolve: null, // 旧 Promise 已销毁,标记不可 resolve
    reject: null,
    recovered: true,
    createdAt: Date.now(),
    expiresAt: Date.now() + PENDING_TTL_MS,
    suspendedDataVersion: suspendedDataVersion ?? null
  }
  armExpiryTimer()
  return true
}

/**
 * AiPanel 调用:用户完成交互,把结果回灌给 await 的 execute。
 * @param result  确认工具:{confirmed:true/false};表单工具:{ok:true,data}或{cancelled:true}
 *
 * 改造 D3d：挂起期间 dataVersion 变化 → 结果前置系统提示(不阻断),强制 AI 现查。
 */
export function resolveInteraction(result) {
  const p = pendingInteraction.value
  if (p && typeof p.resolve === 'function') {
    const finalResult = withVersionHint(result, p.suspendedDataVersion)
    p.result = finalResult
    clearExpiryTimer()
    p.resolve(finalResult)
  }
}

/** dataVersion 对比:挂起期间被修改则在结果中前置系统提示(D3d)。导出供 B4 恢复路径复用 */
export function withVersionHint(result, suspendedDataVersion) {
  if (suspendedDataVersion == null) return result
  const current = getDataVersion()
  if (current === suspendedDataVersion) return result
  const hint = `[系统] 等待期间工作区已被用户手动修改(v${suspendedDataVersion}→v${current}),后续决策请先调 get_workspace_state 获取最新状态。`
  if (result && typeof result === 'object') {
    return { ...result, message: hint + (result.message ? '\n' + result.message : '') }
  }
  return result
}

/** 取消当前交互(如对话被清空/出错) */
export function cancelInteraction() {
  const p = pendingInteraction.value
  if (p && typeof p.reject === 'function') {
    p.reject(new Error('interaction_cancelled'))
  }
  clearExpiryTimer()
  pendingInteraction.value = null
}

/** 清空(无 reject,仅清理) */
export function clearInteraction() {
  clearExpiryTimer()
  pendingInteraction.value = null
}
