/**
 * 页面侧的"本次前端工具已作废"判据（预算到点 / 用户停止共用同一标记）。
 *
 * <p>为什么单列：两个向导的写入口（选配置 / 设条件 / 推进步骤）都要在**每个 await 之后、
 * 每次写页面态或工作区之前**读同一个判据。三处各写一遍 `lifetime?.abandoned === true`
 * 迟早漂移；文案也只该有一份（与契约层 `dispatchUiEvent` 的"预算已到点"口径一致）。
 *
 * <p>缺省（用户手点 / 单测直调）不传 lifetime ⇒ `uiAbandoned` 恒 false，行为与改动前一致。
 */
import type { UiEventLifetime } from '@/types/workspace'

/** 本次执行是否已作废（预算到点，或用户停止了本轮）。 */
export function uiAbandoned(lifetime?: UiEventLifetime): boolean {
  return lifetime?.abandoned === true
}

/**
 * 作废后的结构化回执（确定性失败）：页面状态 / 工作区 / 步骤此后都没有被写，
 * 不许返回字符串（字符串会被 `interpretHandlerOutcome` 当成功）。
 *
 * @param action 没做什么（紧跟在「本次未」之后，如「变更所选配置项」「设置查询条件」）
 */
export function uiAbandonedReply(action: string): { ok: false; reason: string } {
  return {
    ok: false,
    reason: `前端工具预算已到点或本轮已停止：本次未${action}（工作区与页面状态均未改动）`
  }
}

/** 作废判据的页面侧传参形态：`() => boolean`，供 goStep / persistStepData 这类页内函数复用。 */
export function uiAbandonGuard(lifetime?: UiEventLifetime): () => boolean {
  return () => uiAbandoned(lifetime)
}
