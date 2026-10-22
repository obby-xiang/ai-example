/**
 * 批 A · 回执造口与机器码（D4-①）：A-08 / A-09 / A-10 / A-14。
 *
 * 判据：失败回执必须**可判定**（outcome + code + 三段式人话），不得再把所有超时糊成
 * 「结局未知」；`unknown` 只留给「副作用可能已发生、只是回执没回来」的下载类白名单。
 */
import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  executeFrontendTool,
  registerFrontendToolExecutor,
  unregisterFrontendToolExecutor
} from '@/utils/frontend-tools'
import { aiFill, aiSnapshot, beginUiTurn } from '@/utils/ai-dom-tools'
import { currentAiEpoch } from '@/utils/ai-regions'
import { isUiCode, sanitizeUiCode } from '@/utils/ui-receipt'
import { enterExportPage, enterImportPage, makeRoot, registerRegion, resetAiTestState } from './ai-fixtures'

vi.mock('@/router', () => ({
  default: { push: async () => undefined },
  ROUTE_NAMES: new Proxy({}, { get: (_target, key) => String(key) })
}))

beforeEach(() => {
  setActivePinia(createPinia())
  resetAiTestState()
  beginUiTurn('t-receipt')
})

afterEach(() => {
  vi.useRealTimers()
  unregisterFrontendToolExecutor('ui_click')
  unregisterFrontendToolExecutor('download_export_files')
})

describe('A-08：只读原语预算耗尽 ⇒ timeout + UI_BUDGET_EXHAUSTED', () => {
  it('不再报「结局未知」', async () => {
    vi.useFakeTimers()
    enterImportPage()
    registerRegion(makeRoot('<button data-ai-ref="import.upload.choose">上传</button>'))
    registerFrontendToolExecutor('ui_click', () => new Promise(() => undefined))
    const pending = executeFrontendTool({
      name: 'ui_click',
      args: { ref: 'import.upload.choose', epoch: currentAiEpoch() },
      runId: 'run-a08',
      budgetMs: 1_000
    })
    await vi.advanceTimersByTimeAsync(1_200)
    const execution = await pending
    expect(execution.outcome).toBe('timeout')
    expect(execution.code).toBe('UI_BUDGET_EXHAUSTED')
    expect(execution.result).not.toContain('结局未知')
  })
})

describe('A-09：下载类超时仍是 unknown（白名单生效）', () => {
  it('文案含「请勿重复触发」', async () => {
    vi.useFakeTimers()
    enterExportPage()
    registerFrontendToolExecutor('download_export_files', () => new Promise(() => undefined))
    const pending = executeFrontendTool({
      name: 'download_export_files',
      args: { taskId: 7, defCodes: ['DEMO'] },
      runId: 'run-a09',
      budgetMs: 1_000
    })
    await vi.advanceTimersByTimeAsync(1_200)
    const execution = await pending
    expect(execution.outcome).toBe('unknown')
    expect(execution.code).toBe('UI_BUDGET_EXHAUSTED')
    expect(execution.result).toContain('请勿重复触发')
  })
})

describe('A-10：写前原值已被用户手改 ⇒ rejected + UI_PRECONDITION_CHANGED', () => {
  it('不覆盖用户手改', async () => {
    enterImportPage()
    registerRegion(makeRoot('<input type="text" data-ai-ref="import.upload.query" data-ai-fill="allow" value="OLD" />'), {
      risk: 'mutate-local'
    })
    aiSnapshot({})
    const node = document.querySelector('[data-ai-ref="import.upload.query"]') as HTMLInputElement
    const first = await aiFill({ ref: 'import.upload.query', value: 'AI-1', epoch: currentAiEpoch() })
    expect(first.outcome).toBe('ok')

    node.value = 'USER-EDIT'
    const second = await aiFill({ ref: 'import.upload.query', value: 'AI-2', epoch: currentAiEpoch() })
    expect(second.outcome).toBe('rejected')
    expect(second.code).toBe('UI_PRECONDITION_CHANGED')
    expect(node.value).toBe('USER-EDIT')
  })
})

describe('A-14：机器码闭集（前端侧同一口径）', () => {
  it('合法 UI_* 通过；小写 / 空格 / 超长 / 非 UI 前缀一律丢弃', () => {
    expect(isUiCode('UI_PAGE_NOT_READY')).toBe(true)
    expect(isUiCode('UI_REF_NOT_SNAPSHOTTED')).toBe(true)
    expect(sanitizeUiCode('UI_BUDGET_EXHAUSTED')).toBe('UI_BUDGET_EXHAUSTED')
    expect(sanitizeUiCode('ui_lower')).toBeNull()
    expect(sanitizeUiCode('UI HAS SPACE')).toBeNull()
    expect(sanitizeUiCode(`UI_${'A'.repeat(41)}`)).toBeNull()
    expect(sanitizeUiCode('ERROR_CODE')).toBeNull()
    expect(sanitizeUiCode(undefined)).toBeNull()
  })
})

describe('回执造口：三段式（结论 / 原因 / 下一步）单一出口', () => {
  it('超时回执含三段且码随结局上送', async () => {
    vi.useFakeTimers()
    enterImportPage()
    registerRegion(makeRoot('<button data-ai-ref="import.upload.choose">上传</button>'))
    registerFrontendToolExecutor('ui_click', () => new Promise(() => undefined))
    const pending = executeFrontendTool({
      name: 'ui_click',
      args: { ref: 'import.upload.choose', epoch: currentAiEpoch() },
      runId: 'run-receipt',
      budgetMs: 1_000
    })
    await vi.advanceTimersByTimeAsync(1_200)
    const execution = await pending
    expect(execution.result).toContain('结论：')
    expect(execution.result).toContain('原因：')
    expect(execution.result).toContain('下一步：')
  })
})

/** 面板状态映射：8 态里 unknown 不再吃掉 timeout/rejected。 */
describe('ToolsRunStatus 映射：8 态 → 卡面状态', () => {
  it('timeout/rejected/blocked/cancelled/skipped 各有落点，不再一律 failed', async () => {
    const { toolStatusOfOutcome } = await import('@/utils/ui-receipt')
    expect(toolStatusOfOutcome('ok')).toBe('succeeded')
    expect(toolStatusOfOutcome('unknown')).toBe('unknown')
    expect(toolStatusOfOutcome('rejected')).toBe('rejected')
    expect(toolStatusOfOutcome('blocked')).toBe('blocked')
    expect(toolStatusOfOutcome('cancelled')).toBe('cancelled')
    expect(toolStatusOfOutcome('skipped')).toBe('skipped')
    expect(toolStatusOfOutcome('failed')).toBe('failed')
    expect(toolStatusOfOutcome('timeout')).toBe('failed')
  })
})
