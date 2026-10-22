/**
 * 对话时序包 批3③a：前端工具执行结局三态（outcome: ok / failed / unknown）。
 *
 * 覆盖测试锁：T4（动作表等待上界耗尽 → unknown+degraded）、T5（handler 结构化失败 /
 * 字符串兼容 / defaultExecutor 透传）、T6（执行器结构化返回三路解读）、T8（makeExecution
 * 工厂 = ok/outcome 一致性唯一断言点，D1）。
 *
 * 风格照抄 tools-and-deadline.spec.ts：只驱动真实模块，不挂载面板。
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  executeFrontendTool,
  makeExecution,
  registerFrontendToolExecutor,
  unregisterFrontendToolExecutor
} from '@/utils/frontend-tools'
import { registerPageHandler, unregisterPageHandler, useWorkspaceStore } from '@/stores/workspace'
import type { FrontendToolExecution } from '@/types/tools'

// 桩掉真实路由：open_page 契约动作只要求 router.push 结算（本用例测的是**其后**的
// waitForPageReady 上界，不是导航本身）；真实 router 会懒加载向导路由 chunk（SpreadJS），
// 在 jsdom 下把用例拖过 5s 超时，与断言目标无关。
vi.mock('@/router', () => ({
  default: { push: async () => undefined },
  ROUTE_NAMES: new Proxy({}, { get: (_target, key) => String(key) })
}))

beforeEach(() => {
  setActivePinia(createPinia())
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('批3③a T8：makeExecution 工厂 —— ok/outcome 一致性唯一断言点（D1）', () => {
  it("outcome 'ok' ⇒ ok:true；'failed' / 'unknown' ⇒ ok:false（ok === (outcome === 'ok')）", () => {
    expect(makeExecution({ outcome: 'ok', availability: 'available', result: 'r' }))
      .toMatchObject({ ok: true, outcome: 'ok', availability: 'available', result: 'r' })
    expect(makeExecution({ outcome: 'failed', availability: 'degraded', result: 'r' }))
      .toMatchObject({ ok: false, outcome: 'failed' })
    expect(makeExecution({ outcome: 'unknown', availability: 'degraded', result: 'r' }))
      .toMatchObject({ ok: false, outcome: 'unknown' })
  })

  it('reason 透传；缺省不产 reason 键', () => {
    const withReason = makeExecution({ outcome: 'unknown', availability: 'degraded', result: 'r', reason: '执行器预算到期' })
    expect(withReason.reason).toBe('执行器预算到期')
    expect('reason' in makeExecution({ outcome: 'ok', availability: 'available', result: 'r' })).toBe(false)
  })
})

describe('批3③a T5：runPageHandler 结局解读（A3）+ defaultExecutor 结构化透传（A5/D2）', () => {
  it('handler 返回结构化 {ok:false, reason} ⇒ outcome failed（确定性失败，D2）', async () => {
    registerPageHandler('probe.structured-fail', () => ({ ok: false, reason: '表格引擎加载失败' }))
    const execution = await executeFrontendTool({ name: 'probe.structured-fail', args: {}, budgetMs: 5_000 })
    expect(execution).toMatchObject({ ok: false, outcome: 'failed', availability: 'degraded' })
    expect(execution.result).toContain('表格引擎加载失败')
    unregisterPageHandler('probe.structured-fail')
  })

  it('handler 返回字符串 ⇒ outcome ok（兼容解读不变，D3(iii)）', async () => {
    registerPageHandler('probe.string-ok', () => '已完成某事')
    const execution = await executeFrontendTool({ name: 'probe.string-ok', args: {}, budgetMs: 5_000 })
    expect(execution).toMatchObject({ ok: true, outcome: 'ok', availability: 'available' })
    expect(execution.result).toBe('已完成某事')
    unregisterPageHandler('probe.string-ok')
  })

  it('defaultExecutor（open_export_file_editor 工具名路径）透传 handler 的结构化失败 ⇒ failed', async () => {
    // 经注册工具路由（路由 1）：无显式执行器 ⇒ defaultExecutor 直调 export.openEditor handler。
    // scope 校验需 workspace 处于 task:EXPORT/EXPORT 上下文。
    const workspace = useWorkspaceStore()
    workspace.enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'EXPORT', taskId: 7 })
    registerPageHandler('export.openEditor', () => ({ ok: false, reason: 'DEMO 的在线编辑器表格未就绪（引擎加载失败）' }))
    const execution = await executeFrontendTool({
      name: 'open_export_file_editor',
      args: { taskId: 7, defCode: 'DEMO' },
      budgetMs: 5_000
    })
    expect(execution.ok).toBe(false)
    expect(execution.outcome).toBe('failed')
    expect(execution.result).toContain('表格未就绪')
    unregisterPageHandler('export.openEditor')
  })
})

describe('批3③a T6：执行器返回 FrontendToolExecution 的结构化解读（A4）', () => {
  it('执行器返回结构化失败 ⇒ 按结构化落 failed（不被成功支包成 ok:true）', async () => {
    registerFrontendToolExecutor('download_export_files', () => ({
      ok: false,
      availability: 'degraded',
      outcome: 'failed',
      result: '前置校验失败：结果不存在'
    } satisfies FrontendToolExecution))
    const workspace = useWorkspaceStore()
    workspace.enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'EXPORT', taskId: 7 })
    const execution = await executeFrontendTool({
      name: 'download_export_files',
      args: { taskId: 7, defCodes: ['DEMO'] },
      budgetMs: 5_000
    })
    expect(execution).toMatchObject({ ok: false, outcome: 'failed', availability: 'degraded' })
    unregisterFrontendToolExecutor('download_export_files')
  })

  it('执行器返回字符串 ⇒ ok（兼容）', async () => {
    registerFrontendToolExecutor('download_export_files', () => '已发起下载')
    const workspace = useWorkspaceStore()
    workspace.enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'EXPORT', taskId: 7 })
    const execution = await executeFrontendTool({
      name: 'download_export_files',
      args: { taskId: 7, defCodes: ['DEMO'] },
      budgetMs: 5_000
    })
    expect(execution).toMatchObject({ ok: true, outcome: 'ok' })
    unregisterFrontendToolExecutor('download_export_files')
  })

  it('执行器 throw ⇒ failed（负例，既有 catch 路径不变）', async () => {
    registerFrontendToolExecutor('download_export_files', () => {
      throw new Error('前置校验未通过')
    })
    const workspace = useWorkspaceStore()
    workspace.enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'EXPORT', taskId: 7 })
    const execution = await executeFrontendTool({
      name: 'download_export_files',
      args: { taskId: 7, defCodes: ['DEMO'] },
      budgetMs: 5_000
    })
    expect(execution).toMatchObject({ ok: false, outcome: 'failed', availability: 'degraded' })
    unregisterFrontendToolExecutor('download_export_files')
  })

  it('R-156 必填非空：defCodes 为空数组 ⇒ 参数校验期即不可用（执行器根本不被调用）', async () => {
    const call = vi.fn(() => '不应被执行')
    registerFrontendToolExecutor('download_export_files', call)
    const execution = await executeFrontendTool({
      name: 'download_export_files',
      args: { taskId: 7, defCodes: [] },
      budgetMs: 5_000
    })
    expect(execution).toMatchObject({ ok: false, outcome: 'failed', availability: 'unavailable' })
    expect(execution.result).toContain('defCodes')
    expect(call).not.toHaveBeenCalled()
    unregisterFrontendToolExecutor('download_export_files')
  })
})

describe('批3③a T4 → 批 A A-04：动作表等待上界耗尽 ⇒ failed + UI_PAGE_NOT_READY', () => {
  it('navigate_to(export) 3s 内目标页未就绪 ⇒ 可判定失败（不再「结局未知」）', async () => {
    // 现场：open_page 契约动作被受理（router.push 在 jsdom 下自行结算），但 export 页能力
    // handler 从未注册 ⇒ waitForPageReady 轮询 3s 超时。
    // 批 A 语义变更：导航已发生、后置条件未满足 ⇒ failed + UI_PAGE_NOT_READY；
    // 文案要求「导航已提交」「本轮不要重复导航」，且不得再出现「结局未知」。
    const workspace = useWorkspaceStore()
    workspace.enterPage({ pageId: 'tasks', page: 'tasks', taskType: null, step: null, taskId: null })
    const pending = executeFrontendTool({ name: 'navigate_to', args: { page: 'export' }, budgetMs: 30_000 })
    await vi.advanceTimersByTimeAsync(3_000)
    const execution = await pending
    expect(execution.ok).toBe(false)
    expect(execution.outcome).toBe('failed')
    expect(execution.code).toBe('UI_PAGE_NOT_READY')
    expect(execution.availability).toBe('degraded')
    expect(execution.result).toContain('导航已提交')
    expect(execution.result).toContain('本轮不要重复导航')
    expect(execution.result).not.toContain('结局未知')
  })
})
