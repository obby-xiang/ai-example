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
 */
import { shallowRef } from 'vue'

/**
 * pendingInteraction 形态:
 *   { toolCallId, type: 'confirm'|'form', toolName, args, impact, resolve, reject, result }
 *   result 字段在 resolve 后回填(供 AiPanel 显示最终态)
 */
export const pendingInteraction = shallowRef(null)

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
      reject
    }
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
      reject
    }
  })
}

/**
 * AiPanel 调用:用户完成交互,把结果回灌给 await 的 execute。
 * @param result  确认工具:{confirmed:true/false};表单工具:{ok:true,data}或{cancelled:true}
 */
export function resolveInteraction(result) {
  const p = pendingInteraction.value
  if (p && typeof p.resolve === 'function') {
    p.result = result
    p.resolve(result)
  }
}

/** 取消当前交互(如对话被清空/出错) */
export function cancelInteraction() {
  const p = pendingInteraction.value
  if (p && typeof p.reject === 'function') {
    p.reject(new Error('interaction_cancelled'))
  }
  pendingInteraction.value = null
}

/** 清空(无 reject,仅清理) */
export function clearInteraction() {
  pendingInteraction.value = null
}
