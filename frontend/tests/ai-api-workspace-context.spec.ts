import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const post = vi.hoisted(() => vi.fn(async () => ({ accepted: true })))

vi.mock('@/api/http', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/http')>()
  return { ...actual, post }
})

import { confirm, frontendToolResult } from '@/api/ai'
import { registerPageHandler, useWorkspaceStore } from '@/stores/workspace'
import { workspaceOwnerEvidence } from '@/utils/workspace-owner'

function owner(taskId: number | null): any {
  return { workspaceOwnerEvidence: { known: true, taskId } }
}

describe('HITL 请求按挂起归属携带工作区上下文（可选字段）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    post.mockClear()
    registerPageHandler('export.taskDetailReady', () => true)
    registerPageHandler('import.taskDetailReady', () => false)
  })

  it('同任务 confirm 请求体带 context（页面/步骤/勾选/selectionSynced）', async () => {
    const workspace = useWorkspaceStore()
    workspace.enterPage({
      pageId: 'export',
      page: 'export',
      taskType: 'EXPORT',
      step: 'QUERY_COND',
      taskId: 42,
      selectedDefs: []
    })

    await confirm({ runId: 'r-1', toolCallId: 'c-1', approved: true }, owner(42))

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

  it('同任务 frontendToolResult 请求体同样带 context', async () => {
    const workspace = useWorkspaceStore()
    workspace.enterPage({ pageId: 'import', page: 'import', taskType: 'IMPORT', step: 'PRECHECK', taskId: 7 })

    await frontendToolResult({ runId: 'r-2', toolCallId: 'c-2', result: 'ok' }, owner(7))

    const [path, body] = post.mock.calls[0] as unknown as [string, Record<string, unknown>]
    expect(path).toBe('/ai/frontend-tool-result')
    const context = body.context as Record<string, unknown>
    expect(context.step).toBe('PRECHECK')
    expect((context.extra as Record<string, unknown>).selectionSynced).toBe(false)
  })

  it('A 卡所属任务与当前 B 工作区不一致时 confirm 不附带 B context', async () => {
    const workspace = useWorkspaceStore()
    workspace.enterPage({ pageId: 'import', page: 'import', taskType: 'IMPORT', step: 'PRECHECK', taskId: 7 })

    await confirm({ runId: 'r-a', toolCallId: 'c-a', approved: true }, owner(42))

    const [, body] = post.mock.calls[0] as unknown as [string, Record<string, unknown>]
    expect(body.context).toBeUndefined()
  })

  it('A 卡所属任务与当前 B 工作区不一致时 frontendToolResult 不附带 B context', async () => {
    const workspace = useWorkspaceStore()
    workspace.enterPage({ pageId: 'import', page: 'import', taskType: 'IMPORT', step: 'PRECHECK', taskId: 7 })

    await frontendToolResult({ runId: 'r-a', toolCallId: 'c-a', result: 'ok' }, owner(42))

    const [, body] = post.mock.calls[0] as unknown as [string, Record<string, unknown>]
    expect(body.context).toBeUndefined()
  })

  it('无法证明挂起归属时不附带当前 workspace', async () => {
    const workspace = useWorkspaceStore()
    workspace.enterPage({ pageId: 'import', page: 'import', taskType: 'IMPORT', step: 'PRECHECK', taskId: 7 })

    await confirm({ runId: 'r-unknown', toolCallId: 'c-unknown', approved: true }, {
      workspaceOwnerEvidence: { known: false, taskId: null }
    } as any)

    const [, body] = post.mock.calls[0] as unknown as [string, Record<string, unknown>]
    expect(body.context).toBeUndefined()
  })

  it('原 taskId=null 的创建前场景仍允许携带当前 context', async () => {
    const workspace = useWorkspaceStore()
    workspace.enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'SELECT_DEFS', taskId: 42 })

    await confirm({ runId: 'r-create', toolCallId: 'c-create', approved: true }, owner(null))

    const [, body] = post.mock.calls[0] as unknown as [string, Record<string, unknown>]
    expect((body.context as Record<string, unknown>).taskId).toBe(42)
  })

  it('快照为 null 时 pending 的 A 任务证据优先，当前 B 不附带', async () => {
    const workspace = useWorkspaceStore()
    workspace.enterPage({ pageId: 'import', page: 'import', taskType: 'IMPORT', step: 'PRECHECK', taskId: 7 })
    const evidence = workspaceOwnerEvidence({ taskId: null }, {
      name: 'confirm_step',
      arguments: '{"taskId":42}'
    })
    expect(evidence).toEqual({ known: true, taskId: 42 })

    await confirm({ runId: 'r-a', toolCallId: 'c-a', approved: true }, {
      workspaceOwnerEvidence: evidence
    } as any)

    const [, body] = post.mock.calls[0] as unknown as [string, Record<string, unknown>]
    expect(body.context).toBeUndefined()
  })

  it('快照字段非法不等于已证明 create_task 创建前为空', () => {
    expect(workspaceOwnerEvidence({ taskId: 'not-a-task-id' })).toEqual({ known: false, taskId: null })
  })

  it('挂起归属只从既有 run 快照或 pending 证据推导', () => {
    expect(workspaceOwnerEvidence({ taskId: '42' })).toEqual({ known: true, taskId: 42 })
    expect(workspaceOwnerEvidence(undefined, {
      name: 'start_export',
      preflight: { target: { taskId: 42 } }
    })).toEqual({ known: true, taskId: 42 })
    expect(workspaceOwnerEvidence(undefined, {
      name: 'confirm_step',
      arguments: '{"taskId":42}'
    })).toEqual({ known: true, taskId: 42 })
    expect(workspaceOwnerEvidence(undefined, { name: 'create_task', arguments: '{}' }))
      .toEqual({ known: true, taskId: null })
    expect(workspaceOwnerEvidence(undefined, { name: 'generative_form', arguments: '{}' }))
      .toEqual({ known: false, taskId: null })
  })
})
