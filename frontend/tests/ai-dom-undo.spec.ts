/**
 * AI 可视可操作专项 · 界面撤销已整段退役（原设计稿 §3.8 深度 1）。
 *
 * <p>当前口径：`ai-dom-tools` 不再导出 `peekUiUndo` / `takeUiUndo` / `applyUiUndo`，
 * store 也不再持有 `uiUndoEntry` / `uiUndoToolCallId` / `undoLastUiAction`。fill/click
 * 照常执行，但不再产生任何“可撤销”承诺（没有撤销栈、没有面板撤销行）。
 */
import { describe, expect, it, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import * as aiDomTools from '@/utils/ai-dom-tools'
import { aiClick, aiFill, aiSnapshot, beginUiTurn } from '@/utils/ai-dom-tools'
import { currentAiEpoch } from '@/utils/ai-regions'
import { useAiStore } from '@/stores/ai'
import { enterImportPage, makeRoot, registerRegion, resetAiTestState } from './ai-fixtures'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  frontendToolResult: vi.fn(),
  confirm: vi.fn(),
  feedbackSubmit: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))
vi.mock('@/router', () => ({
  default: { push: async () => undefined },
  ROUTE_NAMES: new Proxy({}, { get: (_target, key) => String(key) })
}))

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  resetAiTestState()
  beginUiTurn('t-undo')
})

describe('撤销原语已退役', () => {
  it('ai-dom-tools 不再导出 undo 原语', () => {
    expect('peekUiUndo' in aiDomTools).toBe(false)
    expect('takeUiUndo' in aiDomTools).toBe(false)
    expect('applyUiUndo' in aiDomTools).toBe(false)
  })

  it('store 不再持有撤销字段与撤销动作', () => {
    const store = useAiStore()
    expect('uiUndoEntry' in store).toBe(false)
    expect('uiUndoToolCallId' in store).toBe(false)
    expect('undoLastUiAction' in store).toBe(false)
  })
})

describe('fill / click 照常执行但不承诺可撤销', () => {
  it('fill 写入真实值，结果不再带“可撤销”承诺', async () => {
    enterImportPage()
    registerRegion(makeRoot('<input type="text" data-ai-ref="import.upload.query" data-ai-fill="allow" value="OLD" />'), { risk: 'mutate-local' })
    const node = document.querySelector('[data-ai-ref="import.upload.query"]') as HTMLInputElement

    aiSnapshot({})
    const filled = await aiFill({ ref: 'import.upload.query', value: 'NEW', epoch: currentAiEpoch() })
    expect(filled.outcome).toBe('ok')
    expect(node.value).toBe('NEW')
    expect(filled.result).not.toContain('撤销')
  })

  it('read 元素点击成功，同样没有撤销栈', async () => {
    enterImportPage()
    registerRegion(makeRoot('<button data-ai-ref="import.upload.tab" data-ai-risk="read">切换</button>'))
    aiSnapshot({})
    const out = await aiClick({ ref: 'import.upload.tab', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('ok')
    expect(out.result).not.toContain('撤销')
  })
})
