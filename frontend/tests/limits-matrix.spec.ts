/**
 * 测试扩充 V4 · C6 超限矩阵（前端侧）。
 *
 * <p>五格 = 附件大小 / 附件 MIME / 草稿长度 / 快照 2000 字符闸 / 单帧 64KB。
 *
 * <p>与既有覆盖的分界（不重复断言）：
 * <ul>
 * <li>草稿 2000：`ai-panel-composer-limit.spec.ts` 锁的是**计数文案与发送钮 disabled**；
 * 本文件锁**键盘路径也不放行 + 草稿一字不丢 + 零请求**（用户实际按 Enter 的那条路）；</li>
 * <li>快照 2000：`ai-perception-batch-c.spec.ts#C-03` 锁「控件行吃掉大半预算、文本段被压缩」；
 * 本文件锁**控件行自己就超 2000** 的另一分支（文本段整段让位，尾行仍诚实）；</li>
 * <li>附件：后端 `GenerativeFormAttachmentController` 有白名单（5MB / 8 扩展名），前端此前**只靠
 * `<input accept>`**（浏览器软过滤，用户可切「所有文件」），故本格同时是 C6 的缺陷面；</li>
 * <li>64KB：`ai-panel-parts-render.spec.ts#2` / `parts-reducer.spec.ts` 锁的是**截断标记的渲染**；
 * 本文件锁**未截断的贴边帧**（恰好 ≤65536 字节、多字节字符不被切碎、前端不自行截断）。</li>
 * </ul>
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage } from '@/types/ai'
import { installIdentityFixture } from './ai-identity-fixture'
import type { FormSpec } from '@/types/form-schema'
import { useWorkspaceStore } from '@/stores/workspace'
import FormRenderer from '@/components/AiPanel/FormRenderer.vue'
import { SseChunkParser, parseSseFrame } from '@/api/sse'
import { applyFrameToParts } from '@/utils/parts'
import { aiSnapshot } from '@/utils/ai-dom-tools'
import { enterImportPage, makeRoot, registerRegion, resetAiTestState } from './ai-fixtures'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  frontendToolResult: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  confirm: vi.fn(),
  feedbackSubmit: vi.fn(),
  uploadFormAttachment: vi.fn(),
  formOptionSource: vi.fn()
}))

vi.mock('@/api/ai', () => ({
  aiApi: api,
  uploadFormAttachment: api.uploadFormAttachment,
  formOptionSource: api.formOptionSource
}))

const stubs = {
  'el-alert': true,
  'el-button': { template: '<button><slot /></button>' },
  'el-empty': true,
  'el-icon': { template: '<i><slot /></i>' },
  'el-input': true,
  'el-skeleton': true,
  'el-tag': { template: '<span class="tag"><slot /></span>' },
  FormRenderer: true
}

const MB = 1024 * 1024

const ATTACH_SCHEMA: FormSpec = {
  scenario: 'CLARIFY',
  title: '上传证据',
  fields: [{ key: 'evidence', label: '附件', type: 'attachment', required: true }]
}

function healthFixture(): Record<string, unknown> {
  return {
    available: true, code: 'OK', model: 'm', baseUrl: 'b', memoryBackend: 'redis', sessionTtl: '1h',
    frontendToolTimeoutSeconds: 120, suspendPoolSize: 4, activeSuspendGates: 0, heldSessions: 0,
    sessionLockRenewals: 0, resumeOnStartup: false, instanceId: 'i', checkedAt: '2026-01-01T00:00:00Z'
  }
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  document.body.innerHTML = ''
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.health.mockResolvedValue(healthFixture())
  api.history.mockResolvedValue({ sessionId: 's', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({
    found: false, runId: null, snapshotStatus: null, pendingEntries: [], unresolvedExternal: [], awaitingMaxSeq: 0, archiveMaxSeq: 0
  })
  api.chat.mockResolvedValue(undefined)
  api.formOptionSource.mockResolvedValue({ options: [] })
  const workspace = useWorkspaceStore()
  workspace.page = 'tasks'
  workspace.pageId = 'tasks'
  installIdentityFixture()
})

function pickFile(input: ReturnType<ReturnType<typeof mount>['find']>, file: File): Promise<void> {
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  return input.trigger('change')
}

function fileOf(name: string, type: string, size: number): File {
  const file = new File(['x'], name, { type })
  Object.defineProperty(file, 'size', { value: size })
  return file
}

describe('C6-01 附件大小：超 5MB 在浏览器侧拦下（不发请求、给可行动文案）', () => {
  it('5MB + 1 字节 ⇒ 零上传请求 + 字段错误说清上限；5MB 整照常上传', async () => {
    api.uploadFormAttachment.mockResolvedValue({ fileId: 'att_ok', fileName: 'evidence.pdf', size: MB })
    const wrapper = mount(FormRenderer, {
      props: { schema: ATTACH_SCHEMA, submitting: false, error: null, uploadContext: { runId: 'r1', toolCallId: 'c1' } }
    })

    const input = wrapper.find('#gf-field-evidence')
    await pickFile(input, fileOf('evidence.pdf', 'application/pdf', 5 * MB + 1))
    await flushPromises()

    expect(api.uploadFormAttachment).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('附件不能超过 5MB')
    expect(wrapper.find('[data-testid="gf-submit"]').exists()).toBe(true)

    // 边界另一侧：恰好 5MB 放行
    await pickFile(input, fileOf('evidence.pdf', 'application/pdf', 5 * MB))
    await flushPromises()
    expect(api.uploadFormAttachment).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).not.toContain('附件不能超过 5MB')
    expect(wrapper.text()).toContain('evidence.pdf')
    wrapper.unmount()
  })
})

describe('C6-02 附件 MIME：扩展名白名单（accept 只是软过滤，仍要硬拦）', () => {
  it('accept 与后端白名单同源；.exe 与无扩展名被拒且零上传', async () => {
    const wrapper = mount(FormRenderer, {
      props: { schema: ATTACH_SCHEMA, submitting: false, error: null, uploadContext: { runId: 'r1', toolCallId: 'c1' } }
    })
    const input = wrapper.find('#gf-field-evidence')
    expect(input.attributes('accept')).toBe('.png,.jpg,.jpeg,.webp,.pdf,.txt,.csv,.xlsx')

    await pickFile(input, fileOf('setup.exe', 'application/octet-stream', 1024))
    await flushPromises()
    expect(api.uploadFormAttachment).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('不支持该文件类型')

    await pickFile(input, fileOf('README', '', 1024))
    await flushPromises()
    expect(api.uploadFormAttachment).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('不支持该文件类型')

    // 大小写不敏感：.PDF 放行
    api.uploadFormAttachment.mockResolvedValue({ fileId: 'att_pdf', fileName: 'A.PDF', size: 1024 })
    await pickFile(input, fileOf('A.PDF', 'application/pdf', 1024))
    await flushPromises()
    expect(api.uploadFormAttachment).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})

describe('C6-03 草稿长度：按住 Enter 也发不出去，草稿一字不丢', () => {
  it('2001 字 + Enter ⇒ 零 /chat 请求；输入框原样保留 2001 字，计数标红', async () => {
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs } })
    await wrapper.vm.$nextTick()
    await flushPromises()

    const box = wrapper.find('[data-testid="composer-input"]')
    const draft = '中'.repeat(2001)
    await box.setValue(draft)
    await box.trigger('keydown', { key: 'Enter' })
    await flushPromises()

    expect(api.chat).not.toHaveBeenCalled()
    expect((box.element as HTMLTextAreaElement).value).toHaveLength(2001)
    expect(wrapper.find('[data-testid="composer-count"]').text()).toBe('已超 1 字上限')
    expect(wrapper.find('[data-testid="composer-count"]').classes()).toContain('text-[#c0392b]')
    wrapper.unmount()
  }, 20_000)
})

describe('C6-04 快照 2000 字符闸：字符裁行时尾行恒在（模型据此知道"没看全"）', () => {
  it('40 行超长控件行 + text=true ⇒ 整段 ≤2000、按「行」标记字符裁、尾行逐字可读且不被裁掉', () => {
    resetAiTestState()
    enterImportPage()
    const rows = Array.from({ length: 40 }, (_, i) => {
      const ref = `import.upload.${'x'.repeat(60)}${i}`
      return `<button data-ai-ref="${ref}">很长的按钮标题第${i}个用于撑爆预算</button>`
    }).join('')
    const root = makeRoot(rows)
    registerRegion(root)
    // 区域内的补充文案：控件行优先，文本段只能吃剩下的余量（预算不足时整段让位/省略）
    root.appendChild(document.createElement('p')).textContent = '区域内的补充说明文案'

    const out = aiSnapshot({ text: true, limit: 40 })

    expect(out.outcome).toBe('ok')
    expect(out.result.length).toBeLessThanOrEqual(2000)
    const lines = out.result.split('\n')
    // 尾行是硬要求：未截断也要回「共 N（本次回 M；上限…）」；字符裁不得把它裁掉
    const footer = lines.find((line) => line.startsWith('共 40 个元素'))
    expect(footer).toMatch(/^共 40 个元素（本次回 \d+ 个；上限 40 行 \/ 2000 字符）$/)
    // 字符裁的行数与标记口径（rows 标记说「个」，chars 标记说「行」）
    expect(out.result).toContain('…[已截断：原 40 行')
    const elementLines = lines.filter((line) => line.startsWith('['))
    expect(elementLines.length).toBeLessThan(40)
    expect(elementLines.every((line) => line.length <= 120)).toBe(true)
    // 文本段若在场，只能追加在尾行之后（控件行优先），且整段仍在同一 2000 预算内
    if (out.result.includes('区域文本（只读')) {
      expect(out.result.indexOf('区域文本（只读')).toBeGreaterThan(out.result.indexOf(footer!))
    }
  })
})

describe('C6-05 单帧 64KB：贴边帧整帧到达，多字节字符不被切碎', () => {
  it('65536 字节内的中文 tool_result ⇒ 单帧、字符数逐字保真、无替换符、前端不自行截断', () => {
    const overhead = JSON.stringify({
      type: 'tool_result', toolCallId: 'c-64k', name: 'list_config_defs', kind: 'BACKEND',
      ok: true, executed: true, result: '', status: 'SUCCEEDED'
    })
    // 每个汉字 3 字节：取整到"不超过 64KB 的最大整数个字符"
    const charCount = Math.floor((65536 - overhead.length) / 3)
    const text = '中'.repeat(charCount)
    const payload = overhead.replace('"result":""', `"result":${JSON.stringify(text)}`)
    const bytes = new TextEncoder().encode(payload).length
    expect(bytes).toBeLessThanOrEqual(65536)
    expect(bytes).toBeGreaterThan(65_000)

    const parser = new SseChunkParser()
    // 故意在网络分片处劈开一个 3 字节汉字（多字节边界）
    const sse = `data: ${payload}\n\n`
    const cut = Math.floor(sse.length / 2) + 1
    const payloads = [...parser.push(sse.slice(0, cut)), ...parser.push(sse.slice(cut)), ...parser.flush()]
    expect(payloads).toHaveLength(1)

    const frame = parseSseFrame(payloads[0]!)
    expect(frame?.type).toBe('tool_result')
    if (frame?.type !== 'tool_result') return
    expect(frame.result).toBe(text)
    expect(frame.result).toHaveLength(charCount)
    expect(frame.result).not.toContain('\uFFFD')
    expect(frame.truncated ?? false).toBe(false)

    const message: ChatMessage = {
      id: 'a-64k', role: 'assistant', content: '', parts: [], reasoning: '', toolRuns: [],
      pendingCall: null, streaming: true, done: false
    }
    applyFrameToParts(message, frame)
    const part = message.parts.find((item) => item.kind === 'tool')
    expect(part).toBeDefined()
    if (!part || part.kind !== 'tool') return
    expect(part.result).toHaveLength(charCount)
    expect(part.truncated).toBe(false)
  })
})
