/**
 * L-79（真机 E2E 批② NFEZUI 域 U-06）· 撤销能力退役后的现状锁。
 *
 * <p>原缺陷形态（修前）：`stop()` 收口清单独缺撤销点 ⇒ 停止后上一轮的「撤销」行仍在。
 * 当前产品口径：界面撤销（`uiUndoEntry` / `uiUndoToolCallId` / 撤销栈原语）已整段退役，
 * 面板不再渲染撤销行；停止仍是终态收口，且不依赖任何撤销清理。
 */
import { describe, expect, it, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import * as aiDomTools from '@/utils/ai-dom-tools'
import { aiFill, aiSnapshot, beginUiTurn } from '@/utils/ai-dom-tools'
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
  beginUiTurn('t-undo-stop')
  api.cancel.mockResolvedValue({ cancelled: true, alreadyCancelled: false })
})

describe('U-06（L-79）：撤销能力退役后的收敛面', () => {
  it('撤销原语/字段/动作均不存在；停止仍复位在途轮且不抛错', async () => {
    const store = useAiStore()
    expect('peekUiUndo' in aiDomTools).toBe(false)
    expect('uiUndoEntry' in store).toBe(false)
    expect('uiUndoToolCallId' in store).toBe(false)
    expect('undoLastUiAction' in store).toBe(false)

    enterImportPage()
    registerRegion(makeRoot('<input type="text" data-ai-ref="import.upload.query" data-ai-fill="allow" value="OLD" />'), {
      risk: 'mutate-local'
    })
    aiSnapshot({})
    const filled = await aiFill({ ref: 'import.upload.query', value: 'NEW', epoch: currentAiEpoch() })
    expect(filled.outcome).toBe('ok')

    store.loading = true
    await store.stop()
    expect(store.loading).toBe(false)
  })
})
