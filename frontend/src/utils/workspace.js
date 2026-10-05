/** 工作区版本号：任何会改变工作区状态的操作（用户点击/AI ui_event）递增。
 * 发送 AI 消息时随上下文快照上传，用于“AI 挂起期间用户改过左栏”的竞态提示（借鉴业界 dataVersion 机制）。
 * 同时通过事件总线通知 AI 面板“工作区已更新”（解耦联动）。 */
import { emit } from './workspaceBus'

let version = 0
let suppressed = false

export function workspaceVersion() {
  return version
}

/** 契约层执行 AI ui_event 期间抑制“工作区已更新”通知（该提示仅针对用户手动操作）。 */
export function suppressWorkspaceEvents() {
  suppressed = true
}

export function resumeWorkspaceEvents() {
  suppressed = false
}

export function bumpWorkspace() {
  version++
  if (!suppressed) emit('workspace_changed', { version })
  return version
}

export function resetWorkspace() {
  version = 0
}
