/**
 * AI 可视可操作专项 · 撤销（深度 1，设计稿 §3.8）。
 *
 * 覆盖：T-25（fill → 撤销 → 再撤销）、T-26（无 data-ai-undo 的 click 不记撤销点）。
 * 撤销**只给用户**（面板按钮 → store action），不提供 `ui_undo` 工具。
 */
import { describe, expect, it, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { aiClick, aiFill, aiSnapshot, applyUiUndo, beginUiTurn, peekUiUndo, takeUiUndo } from '@/utils/ai-dom-tools'
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

describe('T-25：fill → 撤销 → 再撤销', () => {
  it('撤销回旧值；第二次撤销给出「没有可撤销的界面操作」', async () => {
    enterImportPage()
    registerRegion(makeRoot('<input type="text" data-ai-ref="import.upload.query" data-ai-fill="allow" value="OLD" />'), { risk: 'mutate-local' })
    const node = document.querySelector('[data-ai-ref="import.upload.query"]') as HTMLInputElement

    aiSnapshot({})
    const filled = await aiFill({ ref: 'import.upload.query', value: 'NEW', epoch: currentAiEpoch() })
    expect(filled.outcome).toBe('ok')
    expect(node.value).toBe('NEW')

    const entry = takeUiUndo()
    expect(entry).toMatchObject({ kind: 'fill', previous: 'OLD' })
    const undone = applyUiUndo(entry!)
    expect(undone.outcome).toBe('ok')
    expect(undone.result).toContain('已撤销')
    expect(node.value).toBe('OLD')
    expect(peekUiUndo()).toBeNull()

    const store = useAiStore()
    const second = await store.undoLastUiAction()
    expect(second).toContain('没有可撤销的界面操作')
  })

  it('深度 1：后一次覆盖前一次（只有最近一步可撤）', async () => {
    enterImportPage()
    registerRegion(makeRoot('<input type="text" data-ai-ref="import.upload.query" data-ai-fill="allow" value="V0" />'), { risk: 'mutate-local' })
    aiSnapshot({})
    await aiFill({ ref: 'import.upload.query', value: 'V1', epoch: currentAiEpoch() })
    await aiFill({ ref: 'import.upload.query', value: 'V2', epoch: currentAiEpoch() })
    const entry = takeUiUndo()
    expect(entry).toMatchObject({ previous: 'V1' })
    applyUiUndo(entry!)
    expect((document.querySelector('[data-ai-ref="import.upload.query"]') as HTMLInputElement).value).toBe('V1')
  })
})

describe('T-26：无 data-ai-undo 的点击不记撤销点', () => {
  it('read 元素点击后撤销栈为空', async () => {
    enterImportPage()
    registerRegion(makeRoot('<button data-ai-ref="import.upload.tab" data-ai-risk="read">切换</button>'))
    aiSnapshot({})
    const out = await aiClick({ ref: 'import.upload.tab', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('ok')
    expect(peekUiUndo()).toBeNull()
  })

  it('mutate-local 但无 data-ai-undo ⇒ 同样不记（撤销点靠显式声明）', async () => {
    enterImportPage()
    registerRegion(makeRoot('<button data-ai-ref="import.upload.open" data-ai-risk="mutate-local">打开</button>'))
    aiSnapshot({})
    const out = await aiClick({ ref: 'import.upload.open', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('ok')
    expect(peekUiUndo()).toBeNull()
    expect(out.result).not.toContain('可撤销')
  })
})

describe('store action：撤销走用户手势（模型无 ui_undo 工具）', () => {
  it('有撤销点时回执带「已撤销」并 touch', async () => {
    enterImportPage()
    registerRegion(makeRoot('<input type="text" data-ai-ref="import.upload.query" data-ai-fill="allow" value="OLD" />'), { risk: 'mutate-local' })
    aiSnapshot({})
    await aiFill({ ref: 'import.upload.query', value: 'NEW', epoch: currentAiEpoch() })
    const store = useAiStore()
    const text = await store.undoLastUiAction()
    expect(text).toContain('已撤销')
    expect((document.querySelector('[data-ai-ref="import.upload.query"]') as HTMLInputElement).value).toBe('OLD')
  })
})
