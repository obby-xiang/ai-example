/**
 * 批 E · E1：去掉模型选择 UI（用户裁决 2026-10-22「后续再做模型选择」）。
 *
 * <h2>口径</h2>
 * <ul>
 * <li>composer 底栏<b>不再有</b>模型选择器（也没有"无可选模型"占位）——默认用后端配置的模型；</li>
 * <li>请求体<b>不携带</b>模型选择：即使页签里残留旧版 `ai-model-id`，这一轮也不带 `modelId`；</li>
 * <li>后端能力<b>保留</b>（`AiChatRequest.modelId` 仍是可选键，后续重做选择器时零后端改动）；</li>
 * <li>头部副标题只做<b>展示</b>（当前生效模型名来自 `/health.model`），不承担切换。</li>
 * </ul>
 *
 * <h2>证红方式</h2>
 * <ul>
 * <li>把 `model-picker` 的 `<select>` 放回 composer ⇒ 第 1 条红；</li>
 * <li>`send()` 恢复展开 `modelPatch` ⇒ 第 2 条红（body 多出 modelId）；</li>
 * <li>副标题改回读前端选择态 ⇒ 第 3 条红（显示"流式对话 · 工具调用"而非后端模型名）。</li>
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

vi.mock('@/api/ai', () => ({ aiApi: api }))

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

beforeAll(async () => {
  await import('@/components/AiPanel/AiPanel.vue')
}, 30_000)

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
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

describe('E1 去模型选择', () => {
  it('composer 不再渲染模型选择器 / 无可选模型占位 / 能力位黄条', async () => {
    const wrapper = await mountPanel()

    expect(wrapper.find('[data-testid="model-picker"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="model-picker-empty"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="model-slot"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="alert-no-tool-calling"]').exists()).toBe(false)
    // 输入与发送不受影响（去 UI 不等于降级）
    expect(wrapper.find('[data-testid="composer-input"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('[data-testid="send-btn"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('请求体不带模型选择：即使页签残留旧版 ai-model-id，也只有 sessionId/message/context 三键', async () => {
    sessionStorage.setItem('ai-model-id', 'plain-model')
    const wrapper = await mountPanel()

    await wrapper.find('[data-testid="composer-input"]').setValue('你好')
    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()

    expect(api.chat).toHaveBeenCalledTimes(1)
    const body = api.chat.mock.calls[0][0] as Record<string, unknown>
    expect(body).not.toHaveProperty('modelId')
    expect(Object.keys(body).sort()).toEqual(['context', 'message', 'sessionId'])
    wrapper.unmount()
  })

  it('副标题展示后端生效模型（纯展示，不是 select）', async () => {
    const wrapper = await mountPanel()

    const subtitle = wrapper.find('[data-testid="current-model"]')
    expect(subtitle.exists()).toBe(true)
    expect(subtitle.text()).toContain('deepseek-flash')
    expect(subtitle.element.tagName).not.toBe('SELECT')
    wrapper.unmount()
  })
})
