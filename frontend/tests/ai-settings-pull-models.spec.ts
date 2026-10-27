/**
 * 「AI 设置」· 拉取模型按 **provider 归属**（并发切换不串模型）。
 *
 * 现场：`/models` 是慢请求（外网端点），用户点完「拉取」再切到另一个 provider 是常态。
 * 修复前响应无条件写进全局 `fetchedModels` —— A 的清单挂在 B 的卡片下，再点「添加」就把
 * A 的模型名加到了 B 上。本 spec 锁三条：
 * ① A 在途时切到 B ⇒ A 的响应落地后不出「可添加模型」清单；
 * ② 切 provider ⇒ 上一个 provider 已拉到的清单被清掉（不留在新卡片下）；
 * ③ busy 按 provider 分键：A 在飞不挡 B 的按钮。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const api = vi.hoisted(() => ({
  listProviders: vi.fn(),
  getDefaults: vi.fn(),
  health: vi.fn(),
  fetchModels: vi.fn(),
  addModel: vi.fn()
}))

const toast = vi.hoisted(() => ({ success: vi.fn(), warning: vi.fn(), error: vi.fn() }))

vi.mock('@/api/aiConfig', () => ({ aiConfigApi: api, default: undefined }))
vi.mock('@/api/ai', () => ({ aiApi: { health: api.health } }))
vi.mock('@/stores/workspace', () => ({ useWorkspaceStore: () => ({ enterPage: vi.fn() }) }))
vi.mock('element-plus', () => ({
  ElMessage: toast,
  ElMessageBox: { confirm: vi.fn().mockResolvedValue(undefined) }
}))

import AiSettingsView from '@/views/AiSettingsView.vue'

function provider(id: number, code: string) {
  return {
    id,
    code,
    displayName: code.toUpperCase(),
    protocol: 'OPENAI_COMPAT',
    baseUrl: `https://${code}.example.com`,
    completionsPath: '/chat/completions',
    modelsPath: '/models',
    keyConfigured: true,
    keyLast4: '0000',
    keyRef: 'DB',
    keyStatus: 'OK',
    source: 'DB',
    enabled: true,
    models: []
  }
}

const PROVIDERS = [provider(7, 'alpha'), provider(8, 'beta')]

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => {
    resolve = res
  })
  return { promise, resolve }
}

/** 可添加模型清单（`data-testid="fetched-models"`）里的模型名。 */
function fetched(wrapper: ReturnType<typeof mount>): string[] {
  const box = wrapper.find('[data-testid="fetched-models"]')
  return box.exists() ? box.findAll('span').map((node) => node.text()) : []
}

beforeEach(() => {
  Object.values(api).forEach((fn) => fn.mockReset())
  Object.values(toast).forEach((fn) => fn.mockReset())
  api.listProviders.mockResolvedValue(PROVIDERS)
  api.getDefaults.mockResolvedValue({ modelId: null, providerId: null, temperature: 0.3, topP: 0.9, maxTokens: 1024, allowUserParams: true })
  api.health.mockResolvedValue({ available: true, code: 'AI_AVAILABLE', providers: [] })
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('pullModels 按 provider 归属', () => {
  it('A 在途时切到 B：A 的响应不得挂到 B 的卡片下', async () => {
    const pending = deferred<{ ok: boolean; models: string[]; cached: boolean }>()
    api.fetchModels.mockReturnValue(pending.promise)

    const wrapper = mount(AiSettingsView)
    await flushPromises()
    expect(api.listProviders).toHaveBeenCalled()

    await wrapper.find('[data-testid="pull-models"]').trigger('click')
    await flushPromises()
    expect(api.fetchModels).toHaveBeenCalledWith(7, true)

    // 在途期间切到 provider 8
    await wrapper.find('[data-testid="provider-select"]').setValue(8)
    await flushPromises()

    pending.resolve({ ok: true, models: ['alpha-large'], cached: false })
    await flushPromises()

    // 关键断言：A 的清单不出现在 B 的卡片下
    expect(fetched(wrapper)).toEqual([])
  })

  it('切 provider 清掉上一个 provider 已拉到的清单', async () => {
    api.fetchModels.mockResolvedValue({ ok: true, models: ['alpha-large'], cached: false })

    const wrapper = mount(AiSettingsView)
    await flushPromises()

    await wrapper.find('[data-testid="pull-models"]').trigger('click')
    await flushPromises()
    expect(fetched(wrapper).join('')).toContain('alpha-large')

    await wrapper.find('[data-testid="provider-select"]').setValue(8)
    await flushPromises()
    expect(fetched(wrapper)).toEqual([])
  })

  it('A 在飞不挡 B 的按钮（busy 按 provider 分键）', async () => {
    const pending = deferred<{ ok: boolean; models: string[]; cached: boolean }>()
    api.fetchModels.mockReturnValue(pending.promise)

    const wrapper = mount(AiSettingsView)
    await flushPromises()

    await wrapper.find('[data-testid="pull-models"]').trigger('click')
    await flushPromises()

    await wrapper.find('[data-testid="provider-select"]').setValue(8)
    await flushPromises()

    const button = wrapper.find('[data-testid="pull-models"]')
    expect(button.attributes('disabled')).toBeUndefined()

    pending.resolve({ ok: true, models: [], cached: false })
    await flushPromises()
  })
})
