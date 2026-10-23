/**
 * 批 A · 回执造口（设计稿 §2.1「单一造口」）。
 *
 * 三个职责，都是为了消灭"结局未知"主力回执：
 * 1. `UI_*` 机器码闭集（给机器、台账与复盘用，**不进用户可见文案**）；
 * 2. 8 态结局 → 卡面状态映射（`unknown` 不再吃掉 `timeout` / `rejected`）；
 * 3. 「结论 / 原因 / 下一步」三段式成文 —— 任何回执文本不得手写第三句。
 */
import type { ToolRunStatus } from '@/types/ai'
import type { FrontendToolOutcome } from '@/types/tools'

/** 前端工具机器码闭集（与设计稿 §2.1 A-04/06/08/10 逐条对应）。 */
export const UI_CODES = [
  /** 导航已提交，但目标页在等待上界内未就绪（后置条件未满足）。 */
  'UI_PAGE_NOT_READY',
  /** ref 不在本 epoch 的快照里（未看先动）。 */
  'UI_REF_NOT_SNAPSHOTTED',
  /** 执行器预算耗尽（非下载类：确定性收口，不再"结局未知"）。 */
  'UI_BUDGET_EXHAUSTED',
  /** 写前原值已被用户手改（前置条件失效，不覆盖）。 */
  'UI_PRECONDITION_CHANGED',
  /** 目标控件处于禁用 / 只读态（写动词一律拒执，不谎报「已点击」）。 */
  'UI_TARGET_DISABLED',
  /** 已在目标页（同页同参幂等直返）。 */
  'UI_ALREADY_ON_PAGE',
  /** 同一轮同参调用已执行过（去重白名单）。 */
  'UI_ALREADY_EXECUTED'
] as const

export type UiCode = (typeof UI_CODES)[number]

/** 入口白名单形态（与后端 `FrontendToolResultRequest` 的校验正则同源）。 */
export const UI_CODE_PATTERN = /^UI_[A-Z_]{2,40}$/

/** 是否为本模块声明的机器码（闭集判定）。 */
export function isUiCode(value: unknown): value is UiCode {
  return typeof value === 'string' && (UI_CODES as readonly string[]).includes(value)
}

/**
 * 对外部传入的码做入口归一：不合规一律丢弃（null），调用方回落状态推导。
 *
 * 与后端 `FrontendToolResultRequest.normalizeCode` 同一口径：非法值不报 400，只丢弃。
 */
export function sanitizeUiCode(value: unknown): UiCode | null {
  return isUiCode(value) ? value : null
}

/** 三段式回执（唯一造口）。 */
export interface UiReceiptInput {
  /** 工具中文名或工具名（一句话主语） */
  tool: string
  /** 结论：发生了什么（可判定） */
  conclusion: string
  /** 原因：为什么（没有原因时给 "-"） */
  reason: string
  /** 下一步：模型/用户该做什么（必须可行动） */
  next: string
}

/** 成文：`<tool>：结论：…。原因：…。下一步：…。` */
export function formatUiReceipt(input: UiReceiptInput): string {
  return `${input.tool}：结论：${input.conclusion}。原因：${input.reason}。下一步：${input.next}。`
}

/**
 * 8 态结局 → 卡面状态。
 *
 * `timeout` 落到 `failed`（卡面已显示完整原因文本；`ToolRunStatus` 没有独立的 timeout 档，
 * 而 `expired` 表达的是"卡失效"，不是"执行超时"）。`unknown` 只在下载类白名单出现。
 */
export function toolStatusOfOutcome(outcome: FrontendToolOutcome): ToolRunStatus {
  switch (outcome) {
    case 'ok':
      return 'succeeded'
    case 'unknown':
      return 'unknown'
    case 'rejected':
      return 'rejected'
    case 'blocked':
      return 'blocked'
    case 'cancelled':
      return 'cancelled'
    case 'skipped':
      return 'skipped'
    case 'failed':
    case 'timeout':
    default:
      return 'failed'
  }
}
