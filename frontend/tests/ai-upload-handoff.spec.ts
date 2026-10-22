/**
 * AI 可视可操作专项 · 上传交接（设计稿 §3.3.7 / §3.9，本专项唯一「等人」工具）。
 *
 * 覆盖：T-33（路径入参永久拒收）、T-34（picker → getFile → receiveFile）、T-34b（**手势同步栈**）、
 * T-35（无 picker 回退）、T-36（只读页 unavailable）、T-37（放弃 ⇒ cancelled）、
 * T-38（刷新后区域仍注册 / 已不在该页）、T-39（用户自己传完的联动收口）、T-51（文件名脱路径）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import type { ChatMessage } from '@/types/ai'
import type { SseFrame } from '@/types/sse'
import { useAiStore } from '@/stores/ai'
import { applyFrameToParts } from '@/utils/parts'
import { unregisterAiRegion } from '@/utils/ai-regions'
import { aiSnapshot } from '@/utils/ai-dom-tools'
import UploadHandoffCard from '@/components/AiPanel/UploadHandoffCard.vue'
import { enterImportPage, makeRoot, resetAiTestState } from './ai-fixtures'
import { registerAiRegion } from '@/utils/ai-regions'

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

type PickerStub = ReturnType<typeof vi.fn>

function installPicker(files: File[] | Error): PickerStub {
  const picker = vi.fn(async () => {
    if (files instanceof Error) {
      throw files
    }
    return files.map((file) => ({ getFile: async () => file }))
  })
  ;(window as unknown as { showOpenFilePicker?: PickerStub }).showOpenFilePicker = picker
  return picker
}

function removePicker(): void {
  delete (window as unknown as { showOpenFilePicker?: PickerStub }).showOpenFilePicker
}

function aborted(): Error {
  const error = new Error('The user aborted a request')
  error.name = 'AbortError'
  return error
}

let received: File[] = []

function installRegion(options: { readOnly?: boolean } = {}): HTMLElement {
  const root = makeRoot('<button data-ai-ref="import.upload.choose" aria-label="上传 xlsx / zip">上传</button>')
  registerAiRegion({
    id: 'import.upload',
    root,
    risk: 'consequential',
    page: 'import',
    label: '上传区',
    uploadEntry: 'import.upload.choose',
    readOnly: () => options.readOnly ?? false,
    receiveFile: async (file: File) => {
      received.push(file)
      return '匹配 1 个配置项'
    }
  })
  // 批 A 快照闸：上传入口也是原语（store 的 degraded 回退会高亮它），先走一次真实快照。
  aiSnapshot({})
  return root
}

function armFrame(
  store: ReturnType<typeof useAiStore>,
  args: Record<string, unknown> = {}
): { message: ChatMessage; frame: Extract<SseFrame, { type: 'frontend_tool_request' }> } {
  store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
  const message = store.appendAssistantMessage()
  const frame: Extract<SseFrame, { type: 'frontend_tool_request' }> = {
    type: 'frontend_tool_request',
    toolCallId: 'ui-up-1',
    name: 'ui_request_upload',
    args: JSON.stringify(args),
    runId: 'run-1',
    seq: 2
  }
  applyFrameToParts(message, frame)
  return { message, frame }
}

function lastSubmitted(): { result?: string; cancelled?: boolean; outcome?: string; code?: string } {
  const calls = api.frontendToolResult.mock.calls
  return (calls[calls.length - 1]?.[0] ?? {}) as { result?: string; cancelled?: boolean; outcome?: string; code?: string }
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  resetAiTestState()
  received = []
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.frontendToolResult.mockResolvedValue({})
  removePicker()
})

describe('T-33：永不接受路径类入参', () => {
  it('filePath 键 ⇒ 直接拒绝、不建卡、不回灌挂起', async () => {
    enterImportPage()
    installRegion()
    installPicker([new File(['x'], 'a.xlsx')])
    const store = useAiStore()
    const { message, frame } = armFrame(store, { filePath: 'C' + ':' + '/tmp/a.xlsx' })
    await store.openUploadHandoff(message, frame)
    expect(store.activeUploadHandoff).toBeNull()
    expect(lastSubmitted().result).toContain('filePath')
    expect(api.frontendToolResult).toHaveBeenCalledTimes(1)
  })

  it('path / selector 同样拒收', async () => {
    enterImportPage()
    installRegion()
    const store = useAiStore()
    for (const bad of [{ path: '/tmp/a.xlsx' }, { selector: '#upload' }]) {
      const { message, frame } = armFrame(store, bad)
      await store.openUploadHandoff(message, frame)
      expect(store.activeUploadHandoff).toBeNull()
    }
  })
})

describe('T-34 / T-34b：picker 主路径', () => {
  it('T-34 选择文件 ⇒ picker 1 次 → getFile → receiveFile 1 次 → 三态 available', async () => {
    enterImportPage()
    installRegion()
    const picker = installPicker([new File(['bytes'], '币种.xlsx')])
    const store = useAiStore()
    const { message, frame } = armFrame(store)
    await store.openUploadHandoff(message, frame)
    expect(store.activeUploadHandoff?.status).toBe('waiting')

    await store.chooseUploadFile()
    expect(picker).toHaveBeenCalledTimes(1)
    expect(received).toHaveLength(1)
    expect(received[0]?.name).toBe('币种.xlsx')
    const submitted = lastSubmitted()
    expect(submitted.cancelled).toBeUndefined()
    expect(submitted.result).toContain('用户已选择文件')
    expect(submitted.result).toContain('币种.xlsx')
    expect(submitted.result).toContain('匹配 1 个配置项')
    expect(store.activeUploadHandoff).toBeNull()
  })

  it('T-34b 手势同步栈：调用返回前 picker 已被调用（首个 await 之前）', async () => {
    enterImportPage()
    installRegion()
    const picker = installPicker([new File(['x'], 'a.xlsx')])
    const store = useAiStore()
    // 直接建最小交接态（不依赖 openUploadHandoff 的异步完成）
    ;(store as unknown as { activeUploadHandoff: unknown }).activeUploadHandoff = {
      toolCallId: 'ui-up-1',
      runId: 'run-1',
      ref: 'import.upload.choose',
      accept: null,
      status: 'waiting',
      reason: null,
      filename: null
    }
    // 手势栈断言：不 await 即已调用（浏览器要求 transient activation 同步消费）
    const pending = store.chooseUploadFile()
    expect(picker).toHaveBeenCalledTimes(1)
    await pending
  })

  it('用户取消选择 ⇒ degraded + 卡保留（可重试），不回灌', async () => {
    enterImportPage()
    installRegion()
    installPicker(aborted())
    const store = useAiStore()
    const { message, frame } = armFrame(store)
    await store.openUploadHandoff(message, frame)
    await store.chooseUploadFile()
    expect(store.activeUploadHandoff?.status).toBe('degraded')
    expect(api.frontendToolResult).not.toHaveBeenCalled()
  })
})

describe('T-35：浏览器无 showOpenFilePicker ⇒ 回退', () => {
  it('degraded + 高亮上传入口 + 指路左侧按钮', async () => {
    enterImportPage()
    const root = installRegion()
    const store = useAiStore()
    const { message, frame } = armFrame(store)
    await store.openUploadHandoff(message, frame)
    expect(store.activeUploadHandoff?.status).toBe('degraded')
    expect(store.activeUploadHandoff?.reason).toContain('不支持在对话内直接选择文件')
    expect((root.querySelector('[data-ai-ref="import.upload.choose"]') as HTMLElement)
      .getAttribute('data-ai-highlighted')).toBe('true')
    expect(api.frontendToolResult).not.toHaveBeenCalled()
  })

  it('另有一条待人工收口的路：放弃时 cancelled=true', async () => {
    enterImportPage()
    installRegion()
    const store = useAiStore()
    const { message, frame } = armFrame(store)
    await store.openUploadHandoff(message, frame)
    await store.cancelUploadHandoff()
    expect(lastSubmitted().cancelled).toBe(true)
    expect(store.activeUploadHandoff).toBeNull()
  })
})

describe('T-36：只读页不可用', () => {
  it('unavailable + 「任务已结束…」 + 零高亮零卡', async () => {
    enterImportPage()
    const root = installRegion({ readOnly: true })
    const store = useAiStore()
    const { message, frame } = armFrame(store)
    await store.openUploadHandoff(message, frame)
    expect(store.activeUploadHandoff).toBeNull()
    expect(lastSubmitted().result).toContain('任务已结束')
    expect(lastSubmitted().result).toContain('未执行上传')
    expect((root.querySelector('[data-ai-ref="import.upload.choose"]') as HTMLElement)
      .getAttribute('data-ai-highlighted')).toBeNull()
  })

  it('区域未注册（不在导入向导）⇒ unavailable 并指路', async () => {
    enterImportPage()
    const store = useAiStore()
    const { message, frame } = armFrame(store)
    await store.openUploadHandoff(message, frame)
    expect(store.activeUploadHandoff).toBeNull()
    expect(lastSubmitted().result).toContain('导入向导')
  })
})

describe('T-51：文件名脱路径 + 截断', () => {
  it('路径分隔与超长文件名只回 basename（≤64 字符）', async () => {
    enterImportPage()
    installRegion()
    installPicker([new File(['x'], '..\\..\\evil\\很长的名字'.padEnd(90, '甲') + '.xlsx')])
    const store = useAiStore()
    const { message, frame } = armFrame(store)
    await store.openUploadHandoff(message, frame)
    await store.chooseUploadFile()
    const result = lastSubmitted().result ?? ''
    expect(result).not.toContain('evil')
    expect(result).not.toContain('\\\\')
    expect(result).toContain('.xlsx')
    expect(result.length).toBeLessThan(220)
  })
})

describe('T-39：用户自己点了页面上传', () => {
  it('notifyAiUploadDone ⇒ 活动交接立即 ok 回灌并落卡终态', async () => {
    enterImportPage()
    installRegion()
    installPicker([new File(['x'], 'a.xlsx')])
    const store = useAiStore()
    const { message, frame } = armFrame(store)
    await store.openUploadHandoff(message, frame)
    expect(store.activeUploadHandoff?.status).toBe('waiting')
    await store.notifyAiUploadDone('已上传并匹配 1 个文件')
    expect(lastSubmitted().result).toContain('用户已通过页面入口完成上传')
    expect(lastSubmitted().result).toContain('匹配 1 个文件')
    expect(store.activeUploadHandoff).toBeNull()
  })

  it('无活动交接时 notify 是空操作（不误杀别的轮）', async () => {
    enterImportPage()
    const store = useAiStore()
    await store.notifyAiUploadDone('已上传')
    expect(api.frontendToolResult).not.toHaveBeenCalled()
  })
})

describe('T-38：刷新恢复（两种形态：可继续 / 已离页）', () => {
  it('区域仍注册 ⇒ 可继续；区域已不在该页 ⇒ missing（只有放弃）', async () => {
    enterImportPage()
    installRegion()
    installPicker([new File(['x'], 'a.xlsx')])
    const store = useAiStore()
    const { message, frame } = armFrame(store)
    await store.openUploadHandoff(message, frame)
    expect(store.uploadHandoffAvailability()).toBe('waiting')

    unregisterAiRegion('import.upload')
    expect(store.uploadHandoffAvailability()).toBe('missing')

    installRegion()
    expect(store.uploadHandoffAvailability()).toBe('waiting')
  })

  it('恢复路：PENDING 的 ui_request_upload 帧重建交接卡（不依赖本地 plan）', async () => {
    enterImportPage()
    installRegion()
    const store = useAiStore()
    const { message, frame } = armFrame(store)
    // 模拟刷新：本地无 activeUploadHandoff，但后端快照里该条目仍 PENDING + req=true
    await store.rebuildUploadHandoffFromFrame(message, frame)
    const restored = store.activeUploadHandoff
    expect(restored?.toolCallId).toBe('ui-up-1')
    expect(restored?.status).toBe('waiting')
  })
})

describe('交接卡组件（三态渲染）', () => {
  function mountCard(status: 'waiting' | 'degraded' | 'missing' | 'unavailable', reason: string | null = null) {
    return mount(UploadHandoffCard, {
      props: {
        status,
        accept: '.xlsx,.zip',
        label: '上传 xlsx / zip',
        reason
      }
    })
  }

  it('waiting：渲染「选择文件」+「放弃」，点击各自 emit', async () => {
    const wrapper = mountCard('waiting')
    const choose = wrapper.get('[data-testid="upload-handoff-choose"]')
    expect(choose.text()).toContain('选择文件')
    await choose.trigger('click')
    expect(wrapper.emitted('choose')).toHaveLength(1)
    await wrapper.get('[data-testid="upload-handoff-cancel"]').trigger('click')
    expect(wrapper.emitted('cancel')).toHaveLength(1)
    wrapper.unmount()
  })

  it('degraded：给回退话术（点左侧高亮按钮 / 拖到高亮区），主按钮禁用', () => {
    const wrapper = mountCard('degraded', '当前浏览器不支持在对话内直接选择文件')
    expect(wrapper.text()).toContain('点左侧高亮按钮')
    expect(wrapper.text()).toContain('拖到高亮区域')
    expect(wrapper.get('[data-testid="upload-handoff-choose"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('missing：禁用主按钮 + 「请先打开导入向导」+ 只有放弃可用', () => {
    const wrapper = mountCard('missing')
    expect(wrapper.get('[data-testid="upload-handoff-choose"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('请先打开导入向导')
    expect(wrapper.get('[data-testid="upload-handoff-cancel"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('unavailable：不渲染可点按钮（零「选择文件」）', () => {
    const wrapper = mountCard('unavailable')
    expect(wrapper.find('[data-testid="upload-handoff-choose"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="upload-handoff-cancel"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('界面无 emoji（图标一律内联 SVG）', () => {
    const wrapper = mountCard('waiting')
    expect(/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(wrapper.html())).toBe(false)
    expect(wrapper.find('svg').exists()).toBe(true)
    wrapper.unmount()
  })
})

describe('批 A：显式 ref 必须来自本轮快照（设计稿 §6 #5）', () => {
  it('未快照的 ref ⇒ 不建卡 + rejected + UI_REF_NOT_SNAPSHOTTED', async () => {
    enterImportPage()
    installRegion()
    const store = useAiStore()
    const { message, frame } = armFrame(store, { ref: 'import.upload.late' })
    await store.openUploadHandoff(message, frame)
    expect(store.activeUploadHandoff).toBeNull()
    expect(lastSubmitted().outcome).toBe('rejected')
    expect(lastSubmitted().code).toBe('UI_REF_NOT_SNAPSHOTTED')
    expect(lastSubmitted().result).toContain('快照')
  })
})

describe('帧路由：frontend_tool_request 的 ui_request_upload 走交接特判（不进 60s 预算）', () => {
  it('handleFrame 收到该帧 ⇒ 建交接态且不回灌（等用户手势）', async () => {
    enterImportPage()
    installRegion()
    installPicker([new File(['x'], 'a.xlsx')])
    const store = useAiStore()
    store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
    store.appendAssistantMessage()
    store.handleFrame({
      type: 'frontend_tool_request',
      toolCallId: 'ui-up-frame',
      name: 'ui_request_upload',
      args: JSON.stringify({ accept: ['xlsx'] }),
      runId: 'run-1',
      seq: 2
    })
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(store.activeUploadHandoff?.toolCallId).toBe('ui-up-frame')
    expect(store.activeUploadHandoff?.status).toBe('waiting')
    expect(api.frontendToolResult).not.toHaveBeenCalled()
  })
})
