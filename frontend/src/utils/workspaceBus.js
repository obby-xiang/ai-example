/**
 * 工作区事件总线：AI 对话框与业务工作区之间的唯一通信媒介（解耦契约）。
 * - AI 面板：只 emit('ui_event') / 订阅 workspace_changed / 经 getContextSnapshot 取快照；
 * - 工作区（契约层）：订阅 ui_event 执行与用户点击相同的 store action，
 *   并在用户手动操作后 emit('workspace_changed') 通知 AI 面板。
 * 任一方的存在与否都不影响另一方独立完成全部操作。
 */
const handlers = new Map()
const contextProviders = []

export function on(topic, fn) {
  if (!handlers.has(topic)) handlers.set(topic, [])
  handlers.get(topic).push(fn)
  return () => off(topic, fn)
}

export function off(topic, fn) {
  const list = handlers.get(topic)
  if (!list) return
  const i = list.indexOf(fn)
  if (i >= 0) list.splice(i, 1)
}

export function emit(topic, payload) {
  const list = handlers.get(topic)
  if (!list || !list.length) return
  list.slice().forEach(fn => {
    try {
      fn(payload)
    } catch (e) {
      console.warn('[workspaceBus] 处理失败:', topic, e)
    }
  })
}

/** 注册上下文快照提供者（工作区向 AI 暴露摘要级状态）。 */
export function registerContextProvider(fn) {
  if (!contextProviders.includes(fn)) contextProviders.push(fn)
}

/** 聚合所有提供者的上下文快照（工作区不存在时为空，AI 仍可工作）。 */
export function getContextSnapshot() {
  const ctx = {}
  contextProviders.forEach(fn => {
    try {
      Object.assign(ctx, fn() || {})
    } catch (e) {
      console.warn('[workspaceBus] 上下文快照失败:', e)
    }
  })
  return ctx
}
