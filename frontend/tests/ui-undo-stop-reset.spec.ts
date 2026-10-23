/**
 * L-79（真机 E2E 批② NFEZUI 域 U-06 转正）：**停止即收敛 —— 界面撤销点随本轮作废**。
 *
 * <p>缺陷形态（修前）：`stop()` 的收口清单含 `dropActiveForm()` / `dropSheetPreview('stop')` /
 * `clearGateSubmissions()`，**独缺**撤销点 ⇒ 停止后上一轮的「撤销」行仍留在卡面，用户点下去会撤销
 * 一条「本轮已放弃」的界面操作（撤销栈是内存态、按轮归属，停止即应失效）。
 *
 * <p>两条断言面与修复同源：① 面板渲染用的 store 字段（`uiUndoEntry` / `uiUndoToolCallId`）；
 * ② 原语运行时的栈本身（`peekUiUndo()`，防"面板清了、栈还在"的半修）。
 */
import { describe, expect, it, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { aiFill, aiSnapshot, beginUiTurn, peekUiUndo } from '@/utils/ai-dom-tools'
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

/** 造一个撤销点（真实链路：mutate-local 输入框 + `data-ai-fill` 授权）。 */
async function makeUndoPoint(): Promise<void> {
  enterImportPage()
  registerRegion(makeRoot('<input type="text" data-ai-ref="import.upload.query" data-ai-fill="allow" value="OLD" />'), {
    risk: 'mutate-local'
  })
  aiSnapshot({})
  const filled = await aiFill({ ref: 'import.upload.query', value: 'NEW', epoch: currentAiEpoch() })
  expect(filled.outcome).toBe('ok')
  expect(peekUiUndo()).not.toBeNull()
}

describe('U-06（L-79）：停止后撤销点作废', () => {
  it('停止后面板无撤销行：uiUndoEntry / uiUndoToolCallId 清空，且原语栈不再提供撤销项', async () => {
    const store = useAiStore()
    await makeUndoPoint()
    store.syncUiRuntimeState()
    store.uiUndoToolCallId = 'tc-undo'
    expect(store.uiUndoEntry).not.toBeNull()

    store.loading = true // 有在途轮才谈得上"停止"
    await store.stop()

    expect(store.uiUndoEntry).toBeNull()
    expect(store.uiUndoToolCallId).toBeNull()
    // 半修防线：栈若还在，下一轮同步（syncUiRuntimeState）会把撤销点又捡回来
    expect(peekUiUndo()).toBeNull()
    store.syncUiRuntimeState()
    expect(store.uiUndoEntry).toBeNull()
  })
})
