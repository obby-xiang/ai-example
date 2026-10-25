/**
 * 交互专项（用户 2026-10-25）：结构化回灌体的 max-chars 预检。
 *
 * 口径：
 * 1. `generative_form` 等结构化结果在**发送前**按后端同一上限（8000 字符）预检；
 *    超限 ⇒ 零请求、挂起保持 PENDING、完整输入不清空，用户改小后可重试；
 * 2. 后端若仍返回 `FRONTEND_RESULT_TOO_LARGE`，走本地分派：同样回到可编辑态、
 *    保留输入、不把表单吞成失败终态；
 * 3. 普通文本（对话输入）不受这条结构化预检影响。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/api/http'
import { ErrorCode } from '@/types/api'
import { useAiStore } from '@/stores/ai'
import type { ActiveGenerativeForm } from '@/types/ai'
import type { FormSpec } from '@/types/form-schema'

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

function longForm(): FormSpec {
  return {
    scenario: 'limit',
    title: '超限表单',
    fields: Array.from({ length: 5 }, (_, index) => ({
      key: `f${index}`,
      label: `字段${index}`,
      type: 'text' as const,
      variant: 'longText' as const,
      required: false
    }))
  }
}

function formOf(form: FormSpec, values: Record<string, unknown>): ActiveGenerativeForm {
  return {
    toolCallId: 'gf-1',
    runId: 'run-1',
    form,
    status: 'filling',
    error: null,
    values: { ...values },
    fieldErrors: []
  }
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.frontendToolResult.mockResolvedValue({})
})

describe('发送前预检：超限零请求且原值保留', () => {
  it('结构化值 JSON > 8000 字符 ⇒ 不发请求、status 仍 filling、输入完整保留、提示上限', async () => {
    const store = useAiStore()
    const values = Object.fromEntries(
      Array.from({ length: 5 }, (_, index) => [`f${index}`, 'x'.repeat(1700)])
    )
    expect(JSON.stringify(values).length).toBeGreaterThan(8000)
    store.activeForm = formOf(longForm(), values)

    await store.submitGenerativeForm(values)

    expect(api.frontendToolResult).not.toHaveBeenCalled()
    expect(store.activeForm?.status).toBe('filling')
    expect(store.activeForm?.values).toEqual(values)
    expect(store.activeForm?.error).toContain('8000')
    expect(store.activeForm?.error).toContain('未发出')
  })

  it('改小后同一 toolCallId 可重试并成功回灌（值被保留而不是被清空）', async () => {
    const store = useAiStore()
    const form = longForm()
    const tooLarge = Object.fromEntries(
      Array.from({ length: 5 }, (_, index) => [`f${index}`, 'x'.repeat(1700)])
    )
    store.activeForm = formOf(form, tooLarge)
    await store.submitGenerativeForm(tooLarge)
    expect(api.frontendToolResult).not.toHaveBeenCalled()

    const smaller = { f0: 'ok' }
    await store.submitGenerativeForm(smaller)

    expect(api.frontendToolResult).toHaveBeenCalledTimes(1)
    const posted = api.frontendToolResult.mock.calls[0]![0] as { toolCallId?: string; result?: string }
    expect(posted.toolCallId).toBe('gf-1')
    expect(posted.result).toBe(JSON.stringify(smaller))
    expect(store.activeForm?.status).toBe('submitted')
  })
})

describe('后端 FRONTEND_RESULT_TOO_LARGE 的本地分派', () => {
  it('后端返回该码 ⇒ 回到可编辑态、保留输入、不做终态失败', async () => {
    const store = useAiStore()
    const form: FormSpec = {
      scenario: 'limit',
      fields: [{ key: 'f0', label: '字段0', type: 'text', required: false }]
    }
    const values = { f0: 'small' }
    store.activeForm = formOf(form, values)
    api.frontendToolResult.mockRejectedValueOnce(
      new ApiError('回灌结果过大', 400, ErrorCode.FRONTEND_RESULT_TOO_LARGE, {
        code: ErrorCode.FRONTEND_RESULT_TOO_LARGE,
        message: '回灌结果过大'
      })
    )

    await store.submitGenerativeForm(values)

    expect(api.frontendToolResult).toHaveBeenCalledTimes(1)
    expect(store.activeForm?.status).toBe('filling')
    expect(store.activeForm?.values).toEqual(values)
    expect(store.activeForm?.error).toContain('超出上限')
  })
})
