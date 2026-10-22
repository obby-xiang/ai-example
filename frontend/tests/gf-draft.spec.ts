/**
 * GF 优化批（G-B 草稿恢复 / G-A 结构化错误）store 层用例。
 *
 * 口径（用户逐条裁定）：草稿**只存浏览器镜像**（sessionStorage，按 toolCallId 键），
 * 刷新恢复时回填；提交 / 取消 / 终态即清；草稿**永不进模型上下文、永不写服务器**。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAiStore } from '@/stores/ai'
import {
  clearFormDraft,
  formDraftKey,
  loadFormDraft,
  saveFormDraft
} from '@/utils/form-draft'
import type { ChatMessage } from '@/types/ai'
import type { FormSpec } from '@/types/form-schema'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  frontendToolResult: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  feedbackSubmit: vi.fn(),
  confirm: vi.fn(),
  uploadFormAttachment: vi.fn(),
  formOptionSource: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

const FORM: FormSpec = {
  scenario: 'FILTER',
  fields: [
    { key: 'name', label: '名称', type: 'text', required: true },
    { key: 'note', label: '备注', type: 'text', placeholder: '可留空' }
  ]
}

function message(): ChatMessage {
  return {
    id: 'm1',
    role: 'assistant',
    content: '',
    parts: [],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: false
  } as unknown as ChatMessage
}

function frame(form: unknown, toolCallId = 'call-1') {
  return {
    type: 'frontend_tool_request' as const,
    name: 'generative_form',
    toolCallId,
    runId: 'run-1',
    args: JSON.stringify({ form })
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  sessionStorage.clear()
  localStorage.clear()
  api.frontendToolResult.mockReset().mockResolvedValue({ accepted: true })
})

describe('GF 草稿镜像（sessionStorage，按 toolCallId）', () => {
  it('写 / 读 / 清的最小语义', () => {
    expect(loadFormDraft('call-1')).toBeNull()
    saveFormDraft('call-1', { name: '半张表' })
    expect(loadFormDraft('call-1')).toEqual({ name: '半张表' })
    expect(sessionStorage.getItem(formDraftKey('call-1'))).not.toBeNull()
    clearFormDraft('call-1')
    expect(loadFormDraft('call-1')).toBeNull()
  })

  it('镜像键按 toolCallId 隔离，且不落到 localStorage（不跨页签/不落盘）', () => {
    saveFormDraft('call-1', { a: 1 })
    saveFormDraft('call-2', { a: 2 })
    expect(loadFormDraft('call-1')).toEqual({ a: 1 })
    expect(loadFormDraft('call-2')).toEqual({ a: 2 })
    expect(localStorage.getItem(formDraftKey('call-1'))).toBeNull()
  })

  it('脏数据 / 非对象镜像读出 null（不把畸形值塞进表单）', () => {
    sessionStorage.setItem(formDraftKey('call-1'), '{不是 JSON')
    expect(loadFormDraft('call-1')).toBeNull()
    sessionStorage.setItem(formDraftKey('call-1'), '[1,2]')
    expect(loadFormDraft('call-1')).toBeNull()
  })

  it('刷新恢复：重新打开同一 toolCallId 的表单时回填草稿值', () => {
    const store = useAiStore()
    saveFormDraft('call-1', { name: '填了一半' })
    store.openGenerativeForm(message(), frame(FORM))
    expect(store.activeForm?.values).toEqual({ name: '填了一半' })
  })

  it('草稿随填写更新；提交成功后镜像无残留', async () => {
    const store = useAiStore()
    store.openGenerativeForm(message(), frame(FORM))
    store.updateFormDraft({ name: '张三', note: 'x' })
    expect(loadFormDraft('call-1')).toEqual({ name: '张三', note: 'x' })

    // 回灌成功（不打真网络：@/api/ai 已整体桩化）
    await store.submitGenerativeForm({ name: '张三', note: 'x' })
    expect(loadFormDraft('call-1')).toBeNull()
    expect(store.activeForm?.values ?? {}).toEqual({})
  })

  it('取消后镜像无残留', async () => {
    const store = useAiStore()
    store.openGenerativeForm(message(), frame(FORM))
    store.updateFormDraft({ name: '半' })
    await store.cancelGenerativeForm()
    expect(loadFormDraft('call-1')).toBeNull()
  })
})
