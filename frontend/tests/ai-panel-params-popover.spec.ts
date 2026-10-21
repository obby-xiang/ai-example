/**
 * 批③ 参数 popover（P2 三项 + 恢复默认 / P3 `allowUserParams=false` 整组置灰）。
 *
 * <h2>两条口径</h2>
 * <ul>
 * <li><b>只发变化项</b>：popover 初值 = 全局默认；用户没动过就不发 `params`（避免把默认值
 *     当成"用户偏好"写进每一轮请求）；</li>
 * <li><b>锁定即不发</b>：`allowedParams=false` 时整组置灰 + `PARAMS_LOCKED` 文案，请求体里
 *     <b>没有</b> `params` 键 —— "发了被后端忽略"会让用户以为设置生效了。</li>
 * </ul>
 *
 * 证红：把 `resetParams` 改成"恢复成全局默认但仍发送" ⇒ 恢复默认用例红（body 里出现 params）；
 * 把锁定判据去掉 ⇒ 锁定用例红（body 带 params）。
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

function health(allowedParams: boolean) {
  return {
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
    allowedParams,
    defaultParams: { temperature: 0.2, topP: null, maxTokens: 1024 }
  }
}

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
  configApi.listAvailableModels.mockResolvedValue([
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
    }
  ])
  api.health.mockResolvedValue(health(true))
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

describe('P2 参数 popover 三项 + 恢复默认', { timeout: 20_000 }, () => {
  it('触发器默认收起，点击展开三项（初值 = 全局默认）', async () => {
    const wrapper = await mountPanel()
    expect(wrapper.find('[data-testid="params-popover"]').exists()).toBe(false)

    await wrapper.find('[data-testid="params-toggle"]').trigger('click')

    const popover = wrapper.find('[data-testid="params-popover"]')
    expect(popover.exists()).toBe(true)
    expect((wrapper.find('[data-testid="param-temperature"]').element as HTMLInputElement).value).toBe('0.2')
    expect((wrapper.find('[data-testid="param-max-tokens"]').element as HTMLInputElement).value).toBe('1024')
    expect(wrapper.find('[data-testid="param-top-p"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('改一项 ⇒ 下一轮 body.params 只有改过的那一项（键名逐字：temperature/topP/maxTokens）', async () => {
    const wrapper = await mountPanel()
    await wrapper.find('[data-testid="params-toggle"]').trigger('click')
    await wrapper.find('[data-testid="param-temperature"]').setValue('0.7')
    await wrapper.find('[data-testid="param-temperature"]').trigger('change')

    await wrapper.find('[data-testid="composer-input"]').setValue('你好')
    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()

    const body = api.chat.mock.calls[0][0] as Record<string, unknown>
    expect(body.params).toEqual({ temperature: 0.7 })
    wrapper.unmount()
  })

  it('「恢复默认」⇒ 存储清空、body 不再带 params', async () => {
    const wrapper = await mountPanel()
    await wrapper.find('[data-testid="params-toggle"]').trigger('click')
    await wrapper.find('[data-testid="param-temperature"]').setValue('0.7')
    await wrapper.find('[data-testid="param-temperature"]').trigger('change')

    await wrapper.find('[data-testid="params-reset"]').trigger('click')

    await wrapper.find('[data-testid="composer-input"]').setValue('你好')
    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()

    const body = api.chat.mock.calls[0][0] as Record<string, unknown>
    expect(body).not.toHaveProperty('params')
    wrapper.unmount()
  })
})

describe('P3 allowUserParams=false', { timeout: 20_000 }, () => {
  it('整组置灰 + PARAMS_LOCKED 文案，且 body 不带 params', async () => {
    api.health.mockResolvedValue(health(false))
    const wrapper = await mountPanel()

    const toggle = wrapper.find('[data-testid="params-toggle"]')
    expect(toggle.attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-testid="params-locked"]').text()).toContain('当前配置不允许调整参数')

    await wrapper.find('[data-testid="composer-input"]').setValue('你好')
    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()

    const body = api.chat.mock.calls[0][0] as Record<string, unknown>
    expect(body).not.toHaveProperty('params')
    wrapper.unmount()
  })
})
