/**
 * 前端工具「作废后零写」回归锁（2026-10-29）。
 *
 * `UiEventLifetime.abandoned` 原先只在契约层 `dispatchUiEvent` 内被读到：页面能力 handler
 * （路由 2 / 4：selectDefs / setConditions / nextStep …）与登记执行器（路由 1）**收不到**
 * 这个标记。于是预算到点（回灌已发"超时"）或用户点了停止之后，被抛弃的续体仍会在下一次
 * await 之后写工作区 / 步骤 / 选中集 —— 面板出现"已超时又报同步"，下一轮上下文带着一条
 * 模型并未确认的动作。
 *
 * 本文件锁三件事：
 * 1. 页面 handler 收到 lifetime（第二参数），超时后续体不再写工作区；
 * 2. 登记执行器同样收到 lifetime；
 * 3. 停止（`stop()`）让**在途**工具立即作废（不是只停"后续界面动词"）；
 * 并保留两条对照：未作废时照常写；直调（无 lifetime）行为不变。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAiStore } from '@/stores/ai'
import { registerPageHandler, unregisterPageHandler, useWorkspaceStore } from '@/stores/workspace'
import {
  executeFrontendTool,
  registerFrontendToolExecutor,
  unregisterFrontendToolExecutor
} from '@/utils/frontend-tools'

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

let release: () => void
let gate: Promise<void>
let entered: () => void
let enterGate: Promise<void>

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  useWorkspaceStore().enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'EXPORT', taskId: 7 })
  gate = new Promise<void>((resolve) => {
    release = resolve
  })
  enterGate = new Promise<void>((resolve) => {
    entered = resolve
  })
  api.cancel.mockResolvedValue({})
})

afterEach(() => {
  vi.useRealTimers()
  unregisterPageHandler('probe.write-after-await')
  unregisterFrontendToolExecutor('download_export_files')
})

describe('R1 页面 handler：预算到点后的续体不得再写工作区', () => {
  it('超时已回灌后，handler 在下一次 await 之后写入被 lifetime 拦下（工作区零变）', async () => {
    vi.useFakeTimers()
    registerPageHandler('probe.write-after-await', async (_payload, lifetime) => {
      entered()
      await gate
      if (lifetime?.abandoned === true) {
        return { ok: false, reason: '前端工具预算已到点：本次未写入工作区' }
      }
      useWorkspaceStore().setSelectedDefs(['LEAK'])
      return '写入完成'
    })

    const pending = executeFrontendTool({ name: 'probe.write-after-await', args: {}, budgetMs: 10 })
    await enterGate
    await vi.advanceTimersByTimeAsync(20)
    const execution = await pending
    expect(execution.outcome).toBe('timeout')

    release()
    await vi.advanceTimersByTimeAsync(0)

    const workspace = useWorkspaceStore()
    expect(workspace.selectedDefs).toEqual([])
    expect(workspace.recentActions.some((action) => action.summary.includes('LEAK'))).toBe(false)
  })

  it('对照：未作废时同一 handler 照常写入（不是把写入口整体关掉）', async () => {
    registerPageHandler('probe.write-after-await', async (_payload, lifetime) => {
      entered()
      await gate
      if (lifetime?.abandoned === true) {
        return { ok: false, reason: '前端工具预算已到点：本次未写入工作区' }
      }
      useWorkspaceStore().setSelectedDefs(['OK'])
      return '写入完成'
    })

    const pending = executeFrontendTool({ name: 'probe.write-after-await', args: {}, budgetMs: 5_000 })
    await enterGate
    release()
    const execution = await pending

    expect(execution.outcome).toBe('ok')
    expect(useWorkspaceStore().selectedDefs).toEqual(['OK'])
  })

  it('反向：page handler 直调（无 lifetime）行为不变', async () => {
    registerPageHandler('probe.write-after-await', async (payload, lifetime) => {
      expect(payload.defCode).toBe('DEMO')
      expect(lifetime).toBeUndefined()
      useWorkspaceStore().setSelectedDefs(['DIRECT'])
      return '直调完成'
    })

    const { getPageHandler } = await import('@/stores/workspace')
    const reply = await getPageHandler('probe.write-after-await')?.({ defCode: 'DEMO' })

    expect(reply).toBe('直调完成')
    expect(useWorkspaceStore().selectedDefs).toEqual(['DIRECT'])
  })
})

describe('R2 登记执行器：同样收到 lifetime', () => {
  it('执行器 await 期间预算到点 ⇒ 续体不再写工作区', async () => {
    vi.useFakeTimers()
    registerFrontendToolExecutor('download_export_files', async (_call, lifetime) => {
      entered()
      await gate
      if (lifetime?.abandoned === true) {
        return { ok: false, outcome: 'failed', availability: 'degraded', result: '已作废' }
      }
      useWorkspaceStore().setImportMode('MERGE')
      return '执行器写入完成'
    })

    const pending = executeFrontendTool({
      name: 'download_export_files',
      args: { taskId: 7, defCodes: ['DEMO'] },
      budgetMs: 10
    })
    await enterGate
    await vi.advanceTimersByTimeAsync(20)
    const execution = await pending
    // 下载类超时按既有口径落 `unknown`（副作用可能已发生）；本用例只关心"续体零写"
    expect(execution.outcome).toBe('unknown')

    release()
    await vi.advanceTimersByTimeAsync(0)

    expect(useWorkspaceStore().importMode).toBeNull()
  })
})

describe('R3 停止：在途工具立即作废（不是只停后续动作）', () => {
  it('stop() 后在途 handler 恢复执行也不写工作区/选中集', async () => {
    registerPageHandler('probe.write-after-await', async (_payload, lifetime) => {
      entered()
      await gate
      if (lifetime?.abandoned === true) {
        return { ok: false, reason: '前端工具预算已到点：本次未写入工作区' }
      }
      useWorkspaceStore().setSelectedDefs(['AFTER-STOP'])
      return '写入完成'
    })

    const store = useAiStore()
    store.$patch({ loading: true, activeRunId: 'run-1' })
    const pending = executeFrontendTool({ name: 'probe.write-after-await', args: {}, budgetMs: 5_000 })
    await enterGate

    await store.stop()
    release()
    await pending

    expect(useWorkspaceStore().selectedDefs).toEqual([])
  })
})
