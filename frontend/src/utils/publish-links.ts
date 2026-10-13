/**
 * R-155：发布冲突明细接线 —— jobId → start_publish 工具段 的会话内注册表。
 *
 * 登记：start_publish 工具结果帧取号 jobId 时写入（ai.ts）；消费：作业终态冲突检出时
 * 按 jobId 查得 toolCallId（workspace.ts / ai.ts onConflict），把明细摘要写回工具段 reason。
 * 清理：冲突落段命中即注销；resetSession 全量清（会话内内存态，不进镜像）。
 *
 * 独立小模块的原因：ai.ts（登记方）与 workspace.ts（查询方）共用本表，
 * 放任一 store 内都会引入 stores 双向依赖。
 */
export interface PublishToolLink {
  toolCallId: string
  sessionId: string
}

const links = new Map<number, PublishToolLink>()

export function linkPublishTool(jobId: number, link: PublishToolLink): void {
  links.set(jobId, link)
}

export function findPublishTool(jobId: number): PublishToolLink | undefined {
  return links.get(jobId)
}

export function unlinkPublishTool(jobId: number): void {
  links.delete(jobId)
}

export function clearPublishToolLinks(): void {
  links.clear()
}
