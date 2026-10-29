/**
 * 2026-10-29 审计批（M-20）：AI 设置页重载不得清掉**其它卡片**的脏密钥与改名。
 *
 * <h2>缺陷形态</h2>
 * `fillForms()` 无条件重建全部卡片表单（`key: ''`），而任何一次 `load()` 都会调它 ——
 * 于是"在 A 卡输入密钥 → 在 B 卡做任一操作"就把 A 的密钥静默清空；再点 A「保存」时请求体里
 * 没有 `apiKey`（留空 = 不改），页面却仍提示"已保存"。用户以为存上了，首次调用才以
 * `AI_KEY_MISSING` 暴露。
 *
 * <h2>口径</h2>
 * - 脏值（用户改过、还没保存）优先：密钥**永远**保留（服务端从不下发明文）；改名同理；
 * - 干净字段跟随服务端：他端改动 / 刚保存的结果仍要能回显（不是"一次都不刷新"）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

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
const workspaceEnterPage = vi.hoisted(() => vi.fn())

vi.mock('@/api/aiConfig', () => ({ aiConfigApi: api, default: undefined }))
vi.mock('@/api/ai', () => ({ aiApi: { health: api.health } }))
vi.mock('@/stores/workspace', () => ({ useWorkspaceStore: () => ({ enterPage: workspaceEnterPage }) }))
vi.mock('element-plus', () => ({
  ElMessage: toast,
  ElMessageBox: { confirm: confirmMock }
}))

import AiSettingsView from '@/views/AiSettingsView.vue'

function providerFixture(id: number, code: string, displayName: string) {
  return {
    id,
    code,
    displayName,
    protocol: 'OPENAI_COMPAT',
    baseUrl: `https://${code}.example.com`,
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
        id: id * 10,
        modelId: `${code}-flash`,
        displayName: `${code}-flash`,
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
}

const twoProviders = () => [providerFixture(7, 'deepseek', 'DeepSeek'), providerFixture(8, 'qwen', 'Qwen')]

const defaultsFixture = () => ({
  modelId: 'deepseek-flash',
  providerId: 7,
  temperature: 0.3,
  topP: 0.9,
  maxTokens: 1024,
  allowUserParams: true,
  version: 4
})

async function mountView(): Promise<ReturnType<typeof mount>> {
  const wrapper = mount(AiSettingsView)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  Object.values(api).forEach((fn) => fn.mockReset())
  confirmMock.mockReset().mockResolvedValue(undefined)
  Object.values(toast).forEach((fn) => fn.mockReset())
  workspaceEnterPage.mockReset()
  api.listProviders.mockResolvedValue(twoProviders())
  api.getDefaults.mockResolvedValue(defaultsFixture())
  api.health.mockResolvedValue({ available: true, code: 'AI_AVAILABLE', providers: [] })
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('M-20 重新加载保留其它卡片的脏值', () => {
  it('M-20 原始：A 卡脏密钥 + 改名，B 卡保存触发重载 ⇒ A 卡保留且保存时真的提交 apiKey', async () => {
    const wrapper = await mountView()
    api.updateProvider.mockResolvedValue(providerFixture(8, 'qwen', 'Qwen'))

    const keyInputs = wrapper.findAll('[data-testid="key-input"]')
    const nameInputs = wrapper.findAll('[data-testid="provider-display-name"]')
    await keyInputs[0]!.setValue('sk-dirty-key-0001')
    await nameInputs[0]!.setValue('我的 DeepSeek')

    // B 卡的任一操作都会走 load() 重建全部卡片表单
    await wrapper.findAll('[data-testid="save-provider"]')[1]!.trigger('click')
    await flushPromises()

    expect((wrapper.findAll('[data-testid="key-input"]')[0]!.element as HTMLInputElement).value)
      .toBe('sk-dirty-key-0001')
    expect((wrapper.findAll('[data-testid="provider-display-name"]')[0]!.element as HTMLInputElement).value)
      .toBe('我的 DeepSeek')

    api.updateProvider.mockClear()
    await wrapper.findAll('[data-testid="save-provider"]')[0]!.trigger('click')
    await flushPromises()

    const [id, body] = api.updateProvider.mock.calls[0] as [number, Record<string, unknown>]
    expect(id).toBe(7)
    expect(body.apiKey).toBe('sk-dirty-key-0001')
    expect(body.displayName).toBe('我的 DeepSeek')
    wrapper.unmount()
  })

  it('M-20 相邻：没改过的卡片仍跟随服务端（他端改名要能回显，不是"再也不刷新"）', async () => {
    const wrapper = await mountView()
    const renamed = twoProviders()
    renamed[0]!.displayName = 'DeepSeek-他端改名'
    api.listProviders.mockResolvedValue(renamed)
    api.updateProvider.mockResolvedValue(providerFixture(8, 'qwen', 'Qwen'))

    await wrapper.findAll('[data-testid="save-provider"]')[1]!.trigger('click')
    await flushPromises()

    expect((wrapper.findAll('[data-testid="provider-display-name"]')[0]!.element as HTMLInputElement).value)
      .toBe('DeepSeek-他端改名')
    expect((wrapper.findAll('[data-testid="key-input"]')[0]!.element as HTMLInputElement).value).toBe('')
    wrapper.unmount()
  })

  it('I7 反向：provider 被他端删除 ⇒ 重载后表单不残留幽灵卡（直接断言 cardForms 已删）', async () => {
    const wrapper = await mountView()
    expect(wrapper.findAll('[data-testid="provider-card"]')).toHaveLength(2)
    api.listProviders.mockResolvedValue([providerFixture(8, 'qwen', 'Qwen')])
    api.updateProvider.mockResolvedValue(providerFixture(8, 'qwen', 'Qwen'))

    await wrapper.findAll('[data-testid="save-provider"]')[1]!.trigger('click')
    await flushPromises()

    expect(wrapper.findAll('[data-testid="provider-card"]')).toHaveLength(1)
    // 直接看组件状态：被删 provider 的卡片表单必须一起消失（否则保存路径还会读到它）
    const forms = (wrapper.vm as unknown as { cardForms: Record<number, unknown> }).cardForms
    expect(forms[7]).toBeUndefined()
    expect(forms[8]).toBeDefined()

    api.updateProvider.mockClear()
    await wrapper.find('[data-testid="save-provider"]').trigger('click')
    await flushPromises()
    const [id, body] = api.updateProvider.mock.calls[0] as [number, Record<string, unknown>]
    expect(id).toBe(8)
    expect(body).not.toHaveProperty('apiKey')
    wrapper.unmount()
  })
})

describe('I1 保存/清除成功后密钥不得留在表单或回滚', () => {
  it('I1 原始：保存成功后密钥输入框清空，再保存不带 apiKey（明文不常驻）', async () => {
    const wrapper = await mountView()
    api.updateProvider.mockResolvedValue(providerFixture(7, 'deepseek', 'DeepSeek'))

    await wrapper.findAll('[data-testid="key-input"]')[0]!.setValue('sk-saved-once')
    await wrapper.findAll('[data-testid="save-provider"]')[0]!.trigger('click')
    await flushPromises()

    const [, firstBody] = api.updateProvider.mock.calls[0] as [number, Record<string, unknown>]
    expect(firstBody.apiKey).toBe('sk-saved-once')
    expect((wrapper.findAll('[data-testid="key-input"]')[0]!.element as HTMLInputElement).value).toBe('')

    api.updateProvider.mockClear()
    await wrapper.findAll('[data-testid="save-provider"]')[0]!.trigger('click')
    await flushPromises()

    const [, secondBody] = api.updateProvider.mock.calls[0] as [number, Record<string, unknown>]
    expect(secondBody).not.toHaveProperty('apiKey')
    wrapper.unmount()
  })

  it('I1 相邻：清除密钥后重载不得把明文留在输入框，再保存不得回滚这次清除', async () => {
    const wrapper = await mountView()
    api.updateProvider.mockResolvedValue(providerFixture(7, 'deepseek', 'DeepSeek'))

    await wrapper.findAll('[data-testid="key-input"]')[0]!.setValue('sk-to-clear')
    await wrapper.findAll('[data-testid="clear-key"]')[0]!.trigger('click')
    await flushPromises()

    const [, clearBody] = api.updateProvider.mock.calls[0] as [number, Record<string, unknown>]
    expect(clearBody).toEqual({ clearKey: true })
    expect((wrapper.findAll('[data-testid="key-input"]')[0]!.element as HTMLInputElement).value).toBe('')

    api.updateProvider.mockClear()
    await wrapper.findAll('[data-testid="save-provider"]')[0]!.trigger('click')
    await flushPromises()
    const [, saveBody] = api.updateProvider.mock.calls[0] as [number, Record<string, unknown>]
    expect(saveBody).not.toHaveProperty('apiKey')
    wrapper.unmount()
  })
})

describe('I2 默认参数区的脏值同样不被任何 load 清空', () => {
  it('I2 原始：改了温度后其它卡片触发重载 ⇒ 温度输入框保持用户值', async () => {
    const wrapper = await mountView()
    api.updateProvider.mockResolvedValue(providerFixture(8, 'qwen', 'Qwen'))
    const temperature = wrapper.find('[data-testid="default-temperature"]')
    await temperature.setValue('0.95')

    // B 卡保存 = 一次 load()（会重建卡片表单与默认参数区）
    await wrapper.findAll('[data-testid="save-provider"]')[1]!.trigger('click')
    await flushPromises()

    expect((wrapper.find('[data-testid="default-temperature"]').element as HTMLInputElement).value)
      .toBe('0.95')
    wrapper.unmount()
  })

  it('I2 相邻：没改过的默认参数仍跟随服务端（他端改了温度要能回显）', async () => {
    const wrapper = await mountView()
    api.updateProvider.mockResolvedValue(providerFixture(8, 'qwen', 'Qwen'))
    api.getDefaults.mockResolvedValue({ ...defaultsFixture(), temperature: 0.7 })

    await wrapper.findAll('[data-testid="save-provider"]')[1]!.trigger('click')
    await flushPromises()

    expect((wrapper.find('[data-testid="default-temperature"]').element as HTMLInputElement).value)
      .toBe('0.7')
    wrapper.unmount()
  })
})
