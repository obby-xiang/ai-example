/**
 * 「AI 设置」页（模型配置专项批②）的单测：F1 三段渲染 + F2 key 掩码/留空不改 + F3 按 code 映射文案。
 *
 * 口径：
 * - 页面必须三段齐备（provider 卡 / 模型表 / 默认参数），且 `data-testid` 可选中；
 * - key 显示只给末 4 位；密钥输入框是 `type=password` + `autocomplete=new-password`，留空提交时
 *   请求体里**根本没有** `apiKey` 键（不是空串！）；
 * - 错误一律按稳定 code 查表，**不显示**后端中文 `message`（i18n 口径，F3 的证红方式就是"直接展示
 *   message"）。
 *
 * 证红：
 * - 删掉任一段（比如默认参数 section）⇒ F1 红；
 * - 把输入框的值无条件塞进请求体 ⇒ F2 红（`expect(body).not.toHaveProperty('apiKey')`）；
 * - 把 `error.message` 直接渲染 ⇒ F3 红（后端原文出现在 DOM 里）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ApiError } from '@/api/http'

const api = vi.hoisted(() => ({
  listProviders: vi.fn(),
  createProvider: vi.fn(),
  updateProvider: vi.fn(),
  deleteProvider: vi.fn(),
  testProvider: vi.fn(),
  fetchModels: vi.fn(),
  listModels: vi.fn(),
  addModel: vi.fn(),
  updateModel: vi.fn(),
  deleteModel: vi.fn(),
  getDefaults: vi.fn(),
  updateDefaults: vi.fn(),
  health: vi.fn()
}))

const confirmMock = vi.hoisted(() => vi.fn())
const toast = vi.hoisted(() => ({ success: vi.fn(), warning: vi.fn(), error: vi.fn() }))

vi.mock('@/api/aiConfig', () => ({ aiConfigApi: api, default: undefined }))
vi.mock('@/api/ai', () => ({ aiApi: { health: api.health } }))
vi.mock('element-plus', () => ({
  ElMessage: toast,
  ElMessageBox: { confirm: confirmMock }
}))

import AiSettingsView from '@/views/AiSettingsView.vue'

const providersFixture = () => [
  {
    id: 7,
    code: 'deepseek',
    displayName: 'DeepSeek',
    protocol: 'OPENAI_COMPAT',
    baseUrl: 'https://api.deepseek.com',
    completionsPath: '/chat/completions',
    modelsPath: '/models',
    keyConfigured: true,
    keyLast4: 'ab12',
    keyRef: 'DB',
    keyStatus: 'OK',
    source: 'DB',
    enabled: true,
    models: [
      {
        id: 9,
        modelId: 'deepseek-flash',
        displayName: 'deepseek-flash',
        supportsToolCalling: true,
        supportsVision: false,
        supportsReasoning: false,
        maxContext: 128000,
        maxOutput: 4096,
        enabled: true,
        isDefault: true,
        sortOrder: 0
      }
    ]
  }
]

const defaultsFixture = () => ({
  modelId: 'deepseek-flash',
  temperature: 0.3,
  topP: 0.9,
  maxTokens: 1024,
  allowUserParams: true,
  version: 4
})

const healthFixture = () => ({ available: true, code: 'AI_AVAILABLE', providers: [] })

async function mountView(): Promise<ReturnType<typeof mount>> {
  const wrapper = mount(AiSettingsView)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  Object.values(api).forEach((fn) => fn.mockReset())
  confirmMock.mockReset().mockResolvedValue(undefined)
  Object.values(toast).forEach((fn) => fn.mockReset())
  api.listProviders.mockResolvedValue(providersFixture())
  api.getDefaults.mockResolvedValue(defaultsFixture())
  api.health.mockResolvedValue(healthFixture())
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('F1 三段式渲染与三态', () => {
  it('provider 卡 / 模型表 / 默认参数三段齐备且 testid 可选中', async () => {
    const wrapper = await mountView()

    expect(wrapper.find('[data-testid="provider-section"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-testid="provider-card"]')).toHaveLength(1)
    expect(wrapper.find('[data-testid="model-section"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-testid="model-row"]')).toHaveLength(1)
    expect(wrapper.find('[data-testid="defaults-section"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="default-temperature"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="save-defaults"]').exists()).toBe(true)
  })

  it('空态：没有任何 provider 时给空提示而不是空白页', async () => {
    api.listProviders.mockResolvedValue([])
    api.getDefaults.mockResolvedValue({ ...defaultsFixture(), modelId: null })
    const wrapper = await mountView()

    expect(wrapper.find('[data-testid="ai-settings-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="defaults-section"]').exists()).toBe(true)
  })

  it('错误态：加载失败显示错误块（而不是把异常抛出去）', async () => {
    api.listProviders.mockRejectedValue(new ApiError('boom', 500, 'UNREACHABLE', null))
    const wrapper = await mountView()

    expect(wrapper.find('[data-testid="ai-settings-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="provider-section"]').exists()).toBe(false)
  })

  it('M5：/health 有 env 兜底合成行时给出「未落库」提示', async () => {
    api.listProviders.mockResolvedValue([])
    api.health.mockResolvedValue({
      ...healthFixture(),
      providers: [
        {
          id: null,
          code: 'deepseek',
          name: 'DeepSeek',
          enabled: true,
          keyConfigured: true,
          keyRef: 'ENV',
          source: 'ENV_SEED',
          synthetic: true,
          status: 'OK'
        }
      ]
    })
    const wrapper = await mountView()

    const hint = wrapper.find('[data-testid="env-fallback-hint"]')
    expect(hint.exists()).toBe(true)
    expect(hint.text()).toContain('环境变量兜底')
  })
})

describe('F2 密钥掩码与「留空不改」', () => {
  it('只显示末 4 位，且输入框是 password + new-password', async () => {
    const wrapper = await mountView()

    const keyCell = wrapper.find('[data-testid="provider-key"]')
    expect(keyCell.text()).toContain('ab12')
    expect(keyCell.text()).not.toContain('new-key')

    const input = wrapper.find('[data-testid="key-input"]')
    expect(input.attributes('type')).toBe('password')
    expect(input.attributes('autocomplete')).toBe('new-password')
  })

  it('留空保存 ⇒ 请求体不含 apiKey 键', async () => {
    const wrapper = await mountView()
    api.updateProvider.mockResolvedValue(providersFixture()[0])

    await wrapper.find('[data-testid="save-provider"]').trigger('click')
    await flushPromises()

    expect(api.updateProvider).toHaveBeenCalledTimes(1)
    const [id, body] = api.updateProvider.mock.calls[0]
    expect(id).toBe(7)
    expect(body).not.toHaveProperty('apiKey')
    // 批③ I-3：<b>没改过的字段一律不提交</b>（原断言是"总是带 baseUrl"，那正是"改不动"的成因——
    // 后端会对没变的私网地址重跑 URL 闸）。口径收紧而非放宽：见下面两条"只带变化字段"用例。
    expect(body).not.toHaveProperty('baseUrl')
    expect(body).not.toHaveProperty('displayName')
  })

  it('填了密钥 ⇒ 请求体带 apiKey（且是用户输入的那一串）', async () => {
    const wrapper = await mountView()
    api.updateProvider.mockResolvedValue(providersFixture()[0])

    await wrapper.find('[data-testid="key-input"]').setValue('new-key-1234abcd')
    await wrapper.find('[data-testid="save-provider"]').trigger('click')
    await flushPromises()

    const [, body] = api.updateProvider.mock.calls[0]
    expect(body.apiKey).toBe('new-key-1234abcd')
  })

  it('批③ I-3：只改显示名 ⇒ 提交体只有 displayName（私网种子行也能保存）', async () => {
    const wrapper = await mountView()
    const seeded = { ...providersFixture()[0], baseUrl: 'http://127.0.0.1:18399', source: 'ENV_SEED' }
    api.listProviders.mockResolvedValue([seeded])
    api.updateProvider.mockResolvedValue(seeded)
    await flushPromises()

    await wrapper.find('[data-testid="provider-display-name"]').setValue('我的本地桩')
    await wrapper.find('[data-testid="save-provider"]').trigger('click')
    await flushPromises()

    const [, body] = api.updateProvider.mock.calls[0]
    expect(body).toEqual({ displayName: '我的本地桩' })
    expect(body).not.toHaveProperty('baseUrl')
  })

  it('批③ I-3 反例：改了 baseUrl ⇒ 提交体带 baseUrl（"变化字段"不是"永不带"）', async () => {
    const wrapper = await mountView()
    api.updateProvider.mockResolvedValue(providersFixture()[0])

    await wrapper.find('[data-testid="provider-base-url"]').setValue('https://gateway.example.com')
    await wrapper.find('[data-testid="save-provider"]').trigger('click')
    await flushPromises()

    const [, body] = api.updateProvider.mock.calls[0]
    expect(body.baseUrl).toBe('https://gateway.example.com')
    expect(body).not.toHaveProperty('displayName')
  })

  it('M-2：硬删被模型引用挡住（409 PROVIDER_IN_USE）⇒ 回落软删并说明原因', async () => {
    const wrapper = await mountView()
    api.deleteProvider.mockRejectedValueOnce(
      new ApiError('该 provider 仍有 1 个模型引用，不能硬删', 409, 'PROVIDER_IN_USE', null)
    )
    api.deleteProvider.mockResolvedValueOnce({ ...providersFixture()[0], enabled: false })

    await wrapper.find('[data-testid="remove-provider"]').trigger('click')
    await flushPromises()

    expect(api.deleteProvider).toHaveBeenNthCalledWith(1, 7, true)
    expect(api.deleteProvider).toHaveBeenNthCalledWith(2, 7, false)
    const warned = toast.warning.mock.calls.map((call: unknown[]) => String(call[0])).join(' ')
    expect(warned).toContain('1 个模型引用')
    expect(warned).toContain('停用')
    expect(toast.error).not.toHaveBeenCalled()
  })

  it('M-2：无模型引用时直接硬删（不回落、不告警）', async () => {
    const wrapper = await mountView()
    api.deleteProvider.mockResolvedValue({ ...providersFixture()[0], enabled: false })

    await wrapper.find('[data-testid="remove-provider"]').trigger('click')
    await flushPromises()

    expect(api.deleteProvider).toHaveBeenCalledTimes(1)
    expect(api.deleteProvider).toHaveBeenCalledWith(7, true)
    expect(toast.warning).not.toHaveBeenCalled()
  })

  it('M-1：探活回 KEY_UNREADABLE 时显示映射文案（不显示后端原文）', async () => {
    const backendMessage = '库里的密钥无法解密（KEK 缺失）：AES/GCM 失败'
    api.testProvider.mockResolvedValue({
      ok: false,
      status: null,
      latencyMs: 0,
      code: 'KEY_UNREADABLE',
      message: backendMessage,
      modelCount: 0
    })
    const wrapper = await mountView()

    await wrapper.find('[data-testid="test-provider"]').trigger('click')
    await flushPromises()

    const shown = wrapper.find('[data-testid="probe-result"]').text()
    expect(shown).toContain('无法解密')
    expect(shown).not.toContain(backendMessage)
  })

  it('清除密钥要二次确认，取消则不请求', async () => {
    const wrapper = await mountView()
    confirmMock.mockRejectedValueOnce(new Error('cancel'))

    await wrapper.find('[data-testid="clear-key"]').trigger('click')
    await flushPromises()

    expect(confirmMock).toHaveBeenCalled()
    expect(api.updateProvider).not.toHaveBeenCalled()
  })
})

describe('F3 按 code 映射文案（不显示后端中文）', () => {
  it('provider 保存失败：显示映射文案，不显示后端 message', async () => {
    const backendMessage = 'baseUrl 解析到私网/回环/保留地址（127.0.0.1 → 127.0.0.1）：默认禁止'
    api.updateProvider.mockRejectedValue(new ApiError(backendMessage, 400, 'BLOCKED_PRIVATE_ENDPOINT', null))
    const wrapper = await mountView()

    await wrapper.find('[data-testid="save-provider"]').trigger('click')
    await flushPromises()

    const shown = toast.error.mock.calls.map((call: unknown[]) => String(call[0])).join('\n')
    expect(shown).toContain('私网')
    expect(shown).not.toContain(backendMessage)
  })

  it('默认参数越界：显示映射文案，不显示后端 message', async () => {
    const backendMessage = 'temperature 必须在 [0.0,2.0] 之间（当前 = 3.0）'
    api.updateDefaults.mockRejectedValue(new ApiError(backendMessage, 400, 'INVALID_PARAM', null))
    const wrapper = await mountView()

    await wrapper.find('[data-testid="default-temperature"]').setValue('3.0')
    await wrapper.find('[data-testid="save-defaults"]').trigger('click')
    await flushPromises()

    const shown = toast.error.mock.calls.map((call: unknown[]) => String(call[0])).join('\n')
    expect(shown).toContain('允许范围')
    expect(shown).not.toContain(backendMessage)
    expect(wrapper.text()).not.toContain(backendMessage)
  })

  it('未知 code 走兜底文案而不是把后端原文放出来', async () => {
    api.listProviders.mockRejectedValue(new ApiError('内部细节：table not found', 500, 'SOMETHING_NEW', null))
    const wrapper = await mountView()

    expect(wrapper.find('[data-testid="ai-settings-error"]').text()).toContain('操作失败，请重试')
    expect(wrapper.text()).not.toContain('内部细节')
  })
})
