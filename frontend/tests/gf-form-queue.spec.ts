/**
 * 批次4 前端C / AR-07：同批多张生成式表单 = **单活动表单 + 到达序排队**。
 *
 * 裁决来源 = `docs/research/裁决-AI交互整改实施口径-2026-10-26.md` AR-07：
 * ① 同一时刻仅一个活动表单（不覆盖、不丢弃、无死卡）；
 * ② 同批第二张及以后**排队**（不拒绝），第一张结算（提交 / 取消 / 失败）后按到达序呈现；
 * ③ **实时与恢复同构** —— 刷新恢复同样按队列顺序重建，不出现"实时丢卡、恢复正确"的分叉；
 * ④ 队列中每一张都可被取消（取消出口恒在）。
 *
 * 另两条同批要求（Task 7 Step 1/3）：
 * - 空选项 + 必填必须在 **schema 校验期**拒绝（前端不渲染注定填不完的表），fail-closed 后
 *   模型可以重出一张可填的表；
 * - 换轮 / 停止 / 换会话时队列与草稿一起收口（不把旧卡补进新轮）。
 *
 * 只驱动真实 store（HTTP 桩化），不挂载面板。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAiStore } from '@/stores/ai'
import { loadFormDraft, saveFormDraft } from '@/utils/form-draft'
import { parseFormSchema } from '@/types/form-schema'
import type { AiRunCurrent, AiRunCurrentEntry, ChatMessage } from '@/types/ai'

const api = vi.hoisted(() => ({
  history: vi.fn(),
  runsCurrent: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  frontendToolResult: vi.fn(),
  confirm: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

const FORM_A = {
  scenario: 'FILTER',
  fields: [{ key: 'name', label: '名称', type: 'text', required: true }]
}

const FORM_B = {
  scenario: 'FILTER',
  fields: [{ key: 'code', label: '编码', type: 'text', required: true }]
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  // 续收连接保持"在跑"（永不结算）：断言只关心"有没有发起重连"
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.chat.mockImplementation(() => new Promise(() => {}))
  api.history.mockResolvedValue({ sessionId: 'sess', count: 0, messages: [] })
  api.cancel.mockResolvedValue({})
  api.deleteHistory.mockResolvedValue({})
  api.confirm.mockResolvedValue({ accepted: true, duplicate: false })
  api.frontendToolResult.mockResolvedValue({ accepted: true, duplicate: false })
})

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

function frame(form: unknown, toolCallId: string) {
  return {
    type: 'frontend_tool_request' as const,
    name: 'generative_form',
    toolCallId,
    runId: 'run-1',
    args: JSON.stringify({ form })
  }
}

/** 一轮"挂起等待人"的快照（同批两张表单）。 */
function suspendedSnapshot(entries: AiRunCurrentEntry[]): AiRunCurrent {
  return {
    found: true,
    runId: 'run-1',
    snapshotStatus: 'PENDING',
    pendingEntries: entries,
    unresolvedExternal: [],
    awaitingExternal: true,
    archiveMaxSeq: 4
  }
}

function formEntry(toolCallId: string, form: unknown = FORM_A): AiRunCurrentEntry {
  return {
    toolCallId,
    entryStatus: 'PENDING',
    kind: 'FRONTEND',
    name: 'generative_form',
    arguments: JSON.stringify({ form }),
    req: true
  }
}

function queuedIds(store: ReturnType<typeof useAiStore>): string[] {
  return store.queuedForms.map((entry) => entry.toolCallId)
}

describe('AR-07 单活动表单 + 同批排队', () => {
  it('同批两张表单：第二张排队不被覆盖，第一张结算后按到达序呈现', async () => {
    const store = useAiStore()
    const host = message()

    store.openGenerativeForm(host, frame(FORM_A, 'call-1'))
    store.openGenerativeForm(host, frame(FORM_B, 'call-2'))

    // ① 同一时刻仅一个活动表单：第二张**不得覆盖**第一张
    expect(store.activeForm?.toolCallId).toBe('call-1')
    expect(queuedIds(store)).toEqual(['call-2'])

    // ② 第一张结算（取消）⇒ 第二张按到达序呈现，队列出清
    await store.cancelGenerativeForm()
    expect(store.activeForm?.toolCallId).toBe('call-2')
    expect(queuedIds(store)).toEqual([])

    // ③ 每一张都可取消：第二张同样有出口，收口后不留死卡
    await store.cancelGenerativeForm()
    expect(store.activeForm).toBeNull()
    expect(queuedIds(store)).toEqual([])
    expect(api.frontendToolResult).toHaveBeenCalledTimes(2)
  })

  it('排队不串草稿：第二张补上时回填自己的镜像，不是第一张的', async () => {
    const store = useAiStore()
    const host = message()
    saveFormDraft('call-1', { name: '第一张的半成品' })
    saveFormDraft('call-2', { code: 'B-42' })

    store.openGenerativeForm(host, frame(FORM_A, 'call-1'))
    store.openGenerativeForm(host, frame(FORM_B, 'call-2'))
    expect(store.activeForm?.values).toEqual({ name: '第一张的半成品' })

    await store.cancelGenerativeForm()
    expect(store.activeForm?.toolCallId).toBe('call-2')
    expect(store.activeForm?.values).toEqual({ code: 'B-42' })
    // 第一张结算即清自己的镜像；第二张的镜像还在（它尚未提交/取消）
    expect(loadFormDraft('call-1')).toBeNull()
    expect(loadFormDraft('call-2')).toEqual({ code: 'B-42' })
  })

  it('队列里的那张可单独取消：不动正在填写的那张', async () => {
    const store = useAiStore()
    const host = message()
    store.openGenerativeForm(host, frame(FORM_A, 'call-1'))
    store.openGenerativeForm(host, frame(FORM_B, 'call-2'))
    saveFormDraft('call-2', { code: '半张' })

    await store.cancelGenerativeForm('call-2')

    expect(store.activeForm?.toolCallId).toBe('call-1')
    expect(store.activeForm?.status).toBe('filling')
    expect(queuedIds(store)).toEqual([])
    expect(loadFormDraft('call-2')).toBeNull()
    expect(api.frontendToolResult).toHaveBeenCalledTimes(1)
  })

  it('队列里那张已在他处结算（结局帧先到）⇒ 出队清草稿，不呈现死卡', async () => {
    const store = useAiStore()
    store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
    store.appendAssistantMessage()
    for (const [toolCallId, form, seq] of [['g1', FORM_A, 2], ['g2', FORM_B, 3]] as const) {
      store.handleFrame({
        type: 'frontend_tool_request',
        toolCallId,
        name: 'generative_form',
        args: JSON.stringify({ form }),
        timeoutSeconds: 120,
        runId: 'run-1',
        seq
      })
    }
    expect(store.activeForm?.toolCallId).toBe('g1')
    expect(queuedIds(store)).toEqual(['g2'])

    store.handleFrame({
      type: 'frontend_tool_result',
      toolCallId: 'g2',
      name: 'generative_form',
      ok: false,
      executed: false,
      status: 'TIMEOUT',
      runId: 'run-1',
      seq: 4
    })

    // 已决的那张不再排队（否则补出来就是一张点不动的死卡）
    expect(queuedIds(store)).toEqual([])
    expect(store.activeForm?.toolCallId).toBe('g1')

    // 活动那张仍可取消；收口后不留任何待呈现的表单
    await store.cancelGenerativeForm()
    expect(store.activeForm).toBeNull()
    expect(queuedIds(store)).toEqual([])
  })

  it('恢复路径同构：快照两张表单只重建第一张，第一张结算后第二张按序补上', async () => {
    const store = useAiStore()
    store.sessionId = 'sess'
    api.runsCurrent.mockResolvedValue(suspendedSnapshot([formEntry('g1'), formEntry('g2', FORM_B)]))

    await store.restoreSuspendedSnapshot()

    // 串行呈现：只重建第一张，第二张排队（不多建一张活动表单）
    expect(store.activeForm?.toolCallId).toBe('g1')
    expect(store.restoredPendingQueue).toHaveLength(1)
    expect(queuedIds(store)).toEqual([])

    await store.cancelGenerativeForm()

    expect(store.activeForm?.toolCallId).toBe('g2')
    expect(store.restoredPendingQueue).toHaveLength(0)
    expect(queuedIds(store)).toEqual([])
  })

  it('换轮 / 终止收口（dropActiveForm）时队列与草稿一起清空（不把旧卡补进新轮）', async () => {
    const store = useAiStore()
    const host = message()
    store.openGenerativeForm(host, frame(FORM_A, 'call-1'))
    store.openGenerativeForm(host, frame(FORM_B, 'call-2'))
    saveFormDraft('call-2', { code: '半张' })

    // 2026-10-29 收口：**停止生成**走 `suspendActiveFormDrafts`（保留未提交草稿），
    // 本用例锁的是"明确收口"那条出口 —— 换轮 / 轮终态 / 换会话仍由 dropActiveForm 清草稿。
    store.dropActiveForm()

    expect(store.activeForm).toBeNull()
    expect(queuedIds(store)).toEqual([])
    expect(loadFormDraft('call-2')).toBeNull()
  })
})

describe('AR-07 schema fail-closed：空选项 + 必填在解析期拒绝', () => {
  const deadEnd = (required: boolean) => ({
    scenario: 'FILTER',
    fields: [
      { key: 'country', label: '国家', type: 'enum', options: [{ value: 'CN' }, { value: 'US' }] },
      {
        key: 'defs',
        label: '配置项',
        type: 'enum',
        required,
        // US 分支缺失：切到 US 时这一格没有任何可选项 ⇒ 必填永远填不出来
        optionsFrom: { dependsOn: 'country', map: { CN: [{ value: 'CURRENCY' }] } }
      }
    ]
  })

  it('必填但某个父值分支没有选项 ⇒ schema 解析即失败（非必填则仍可渲染）', () => {
    expect(parseFormSchema(deadEnd(true))).toBeNull()
    expect(parseFormSchema(deadEnd(false))).not.toBeNull()
  })

  it('拒绝后不武装表单：带 cancelled 回灌，卡面说明要求重出一张可填的表；修好即可填', () => {
    const store = useAiStore()
    const host = message()
    store.openGenerativeForm(host, frame(deadEnd(true), 'call-dead'))
    expect(store.activeForm).toBeNull()
    expect(api.frontendToolResult).toHaveBeenCalledTimes(1)
    expect(api.frontendToolResult.mock.calls[0]?.[0]).toMatchObject({ toolCallId: 'call-dead', cancelled: true })
    // 卡面（段 result）留一句可执行的下一步：让模型重出一张可填的表
    const part = host.parts.find((item) => item.kind === 'tool' && item.toolCallId === 'call-dead')
    expect(part && 'result' in part ? String(part.result) : '').toContain('重新生成')

    // 模型按提示重出一张"每个分支都有选项"的表 ⇒ 正常进入填写
    store.openGenerativeForm(host, frame({
      scenario: 'FILTER',
      fields: [
        { key: 'country', label: '国家', type: 'enum', options: [{ value: 'CN' }, { value: 'US' }] },
        {
          key: 'defs',
          label: '配置项',
          type: 'enum',
          required: true,
          optionsFrom: {
            dependsOn: 'country',
            map: { CN: [{ value: 'CURRENCY' }], US: [{ value: 'DOC_TYPE' }] }
          }
        }
      ]
    }, 'call-good'))
    expect(store.activeForm?.toolCallId).toBe('call-good')
  })
})
