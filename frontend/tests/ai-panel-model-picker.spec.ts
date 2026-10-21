/**
 * 批③ 对话面板（P1 选择器 / P4 能力位降级 / P5 副标题 / P6 切换语义 / 请求体投影）。
 *
 * <h2>装配范式</h2>
 * 照 `ai-panel-composer-limit.spec.ts`：Element Plus 全 stub + 最小三桩（health / history / runsCurrent），
 * 再加本批的两个数据源（`aiApi.chat` 与 `aiConfigApi.listAvailableModels`）。每个文件自带桩，
 * 不依赖别的 spec 的残留（那条踩过的坑写在 composer-limit 的文件头）。
 *
 * <h2>证红方式</h2>
 * <ul>
 * <li>把 `requestPatch` 从 `send()` 里摘掉 ⇒ {@code 选中模型后 chat 体带 modelId} 红；</li>
 * <li>把 `supportsToolCalling` 的判据反过来 ⇒ 黄条用例红；</li>
 * <li>让切换模型顺手 `resetSession()` ⇒ "不清会话、不改 sessionId" 红。</li>
 * </ul>
 */
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  frontendToolResult: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  feedbackSubmit: vi.fn(),
  confirm: vi.fn()
}))

const configApi = vi.hoisted(() => ({ listAvailableModels: vi.fn() }))

vi.mock('@/api/ai', () => ({ aiApi: api }))
vi.mock('@/api/aiConfig', () => ({ aiConfigApi: configApi, default: undefined }))

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

const MODELS = [
  {
    providerId: 7,
    providerCode: 'deepseek',
    providerName: 'DeepSeek',
    modelId: 'deepseek-flash',
    displayName: 'Flash',
    supportsToolCalling: true,
    supportsVision: false,
    supportsReasoning: false,
    maxContext: 128000,
    maxOutput: 8192,
    isDefault: true
  },
  {
    providerId: 7,
    providerCode: 'deepseek',
    providerName: 'DeepSeek',
    modelId: 'plain-model',
    displayName: 'Plain',
    supportsToolCalling: false,
    supportsVision: false,
    supportsReasoning: false,
    maxContext: 32000,
    maxOutput: 4096,
    isDefault: false
  }
]

beforeAll(async () => {
  await import('@/components/AiPanel/AiPanel.vue')
}, 30_000)

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  configApi.listAvailableModels.mockReset()
  configApi.listAvailableModels.mockResolvedValue(MODELS)
  api.health.mockResolvedValue({
    available: true,
    code: 'AI_AVAILABLE',
    model: 'deepseek-flash',
    baseUrl: 'http://placeholder',
    memoryBackend: 'redis',
    sessionTtl: '1h',
    frontendToolTimeoutSeconds: 120,
    suspendPoolSize: 4,
    activeSuspendGates: 0,
    heldSessions: 0,
    sessionLockRenewals: 0,
    resumeOnStartup: false,
    instanceId: 'i',
    checkedAt: '2026-01-01T00:00:00Z',
    allowedParams: true,
    defaultParams: { temperature: 0.2, topP: null, maxTokens: 1024 }
  })
  api.history.mockResolvedValue({ count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({ found: false, runId: null, awaitingExternal: false, pendingEntries: [] })
  api.chat.mockResolvedValue(undefined)
})

async function mountPanel(): Promise<ReturnType<typeof mount>> {
  const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
  const wrapper = mount(AiPanel, { global: { stubs } })
  await flushPromises()
  return wrapper
}

describe('P1 模型选择器', { timeout: 20_000 }, () => {
  it('只列后端返回的可用模型（列表即真值，前端不做二次过滤/放宽）', async () => {
    const wrapper = await mountPanel()

    const options = wrapper.findAll('[data-testid="model-picker"] option')
    const values = options.map((option) => option.attributes('value'))
    expect(values).toContain('deepseek-flash')
    expect(values).toContain('plain-model')
    // 首项是"默认模型"占位（空值 = 不指定，回落后端解析链）
    expect(values[0]).toBe('')
    wrapper.unmount()
  })

  it('列表为空 ⇒ 不渲染选择器，也不阻塞输入（降级沿用既有 chip）', async () => {
    configApi.listAvailableModels.mockResolvedValue([])
    const wrapper = await mountPanel()

    expect(wrapper.find('[data-testid="model-picker"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="model-picker-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="composer-input"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('选中模型 ⇒ 写 ai-model-id，且不清会话、不改 sessionId', async () => {
    const wrapper = await mountPanel()
    const sessionId = sessionStorage.getItem('ai-session-id')

    await wrapper.find('[data-testid="model-picker"]').setValue('plain-model')
    await flushPromises()

    expect(sessionStorage.getItem('ai-model-id')).toBe('plain-model')
    expect(sessionStorage.getItem('ai-session-id')).toBe(sessionId)
    wrapper.unmount()
  })

  it('P5 副标题显示当前模型（纯展示，不承担切换）', async () => {
    const wrapper = await mountPanel()
    await wrapper.find('[data-testid="model-picker"]').setValue('plain-model')
    await flushPromises()

    const subtitle = wrapper.find('[data-testid="current-model"]')
    expect(subtitle.exists()).toBe(true)
    expect(subtitle.text()).toContain('Plain')
    expect(subtitle.element.tagName).not.toBe('SELECT')
    wrapper.unmount()
  })
})

describe('P6 切换语义：下一轮生效', { timeout: 20_000 }, () => {
  it('选中模型后下一轮 chat 体带 modelId，且 context/sessionId 原样', async () => {
    const wrapper = await mountPanel()
    await wrapper.find('[data-testid="model-picker"]').setValue('plain-model')

    await wrapper.find('[data-testid="composer-input"]').setValue('你好')
    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()

    expect(api.chat).toHaveBeenCalledTimes(1)
    const body = api.chat.mock.calls[0][0] as Record<string, unknown>
    expect(body.modelId).toBe('plain-model')
    expect(body.sessionId).toBe(sessionStorage.getItem('ai-session-id'))
    expect(Object.keys(body).sort()).toEqual(['context', 'message', 'modelId', 'sessionId'])
    wrapper.unmount()
  })

  it('未选模型 ⇒ 体里没有 modelId 键（回落后端默认解析链）', async () => {
    const wrapper = await mountPanel()

    await wrapper.find('[data-testid="composer-input"]').setValue('你好')
    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()

    const body = api.chat.mock.calls[0][0] as Record<string, unknown>
    expect(body).not.toHaveProperty('modelId')
    wrapper.unmount()
  })
})

describe('P4 能力位降级', { timeout: 20_000 }, () => {
  it('选中不支持工具调用的模型 ⇒ 顶部黄条出现（文案逐字）', async () => {
    const wrapper = await mountPanel()
    expect(wrapper.find('[data-testid="alert-no-tool-calling"]').exists()).toBe(false)

    await wrapper.find('[data-testid="model-picker"]').setValue('plain-model')
    await flushPromises()

    const banner = wrapper.find('[data-testid="alert-no-tool-calling"]')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain('该模型不支持工具调用，本轮退化为纯问答')
    // 界面禁 emoji：黄条里不得出现表情符号
    expect(/[\u{1F300}-\u{1FAFF}]/u.test(banner.text())).toBe(false)
    wrapper.unmount()
  })

  it('换成支持工具调用的模型 ⇒ 黄条撤除', async () => {
    const wrapper = await mountPanel()
    await wrapper.find('[data-testid="model-picker"]').setValue('plain-model')
    await flushPromises()
    expect(wrapper.find('[data-testid="alert-no-tool-calling"]').exists()).toBe(true)

    await wrapper.find('[data-testid="model-picker"]').setValue('deepseek-flash')
    await flushPromises()

    expect(wrapper.find('[data-testid="alert-no-tool-calling"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
