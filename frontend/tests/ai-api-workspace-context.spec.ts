/**
 * 返修（Important 3）：HITL 两条写入口（`/confirm` / `/frontend-tool-result`）必须携带
 * **最新工作区上下文** —— 挂起期用户可能已经在向导里改了步骤/勾选；续跑只读快照，
 * 不把它带上就等于让模型按旧步骤/旧勾选继续。
 *
 * 该字段是**可选**的（旧客户端不带 = 不改行为）；本用例锁住"api 层确实把它拼进请求体"。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const post = vi.hoisted(() => vi.fn(async () => ({ accepted: true })))

vi.mock('@/api/http', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/http')>()
  return { ...actual, post }
})

import { confirm, frontendToolResult } from '@/api/ai'
import { registerPageHandler, useWorkspaceStore } from '@/stores/workspace'

describe('HITL 请求携带最新工作区上下文（可选字段）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    post.mockClear()
    registerPageHandler('export.taskDetailReady', () => true)
  })

  it('confirm 请求体带 context（页面/步骤/勾选/selectionSynced）', async () => {
    const workspace = useWorkspaceStore()
    workspace.enterPage({
      pageId: 'export',
      page: 'export',
      taskType: 'EXPORT',
      step: 'QUERY_COND',
      taskId: 42,
      selectedDefs: []
    })

    await confirm({ runId: 'r-1', toolCallId: 'c-1', approved: true })

    const [path, body] = post.mock.calls[0] as unknown as [string, Record<string, unknown>]
    expect(path).toBe('/ai/confirm')
    expect(body.runId).toBe('r-1')
    const context = body.context as Record<string, unknown>
    expect(context).toBeTruthy()
    expect(context.step).toBe('QUERY_COND')
    expect(context.taskId).toBe(42)
    expect((context.extra as Record<string, unknown>).selectedDefs).toEqual([])
    expect((context.extra as Record<string, unknown>).selectionSynced).toBe(true)
  })

  it('frontendToolResult 请求体同样带 context', async () => {
    const workspace = useWorkspaceStore()
    workspace.enterPage({ pageId: 'import', page: 'import', taskType: 'IMPORT', step: 'PRECHECK', taskId: 7 })
    registerPageHandler('import.taskDetailReady', () => false)

    await frontendToolResult({ runId: 'r-2', toolCallId: 'c-2', result: 'ok' })

    const [path, body] = post.mock.calls[0] as unknown as [string, Record<string, unknown>]
    expect(path).toBe('/ai/frontend-tool-result')
    const context = body.context as Record<string, unknown>
    expect(context.step).toBe('PRECHECK')
    expect((context.extra as Record<string, unknown>).selectionSynced).toBe(false)
  })
})
