/** 工作区版本号：所有会改变工作区状态的操作（用户点击/AI ui_event）递增。
 * 发送 AI 消息时随上下文快照上传，用于“AI 挂起期间用户改过左栏”的竞态提示（借鉴业界 dataVersion 机制）。 */
let version = 0

export function workspaceVersion() {
  return version
}

export function bumpWorkspace() {
  version++
  return version
}

export function resetWorkspace() {
  version = 0
}
