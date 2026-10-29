/**
 * 批 F · F1：参数 popover 一并退场（批 E · E1 的补完，用户裁决 2026-10-22）。
 *
 * <h2>口径</h2>
 * <ul>
 * <li>composer 底栏<b>不再有</b>[参数] 触发器、参数 popover、锁定文案 —— 界面面只剩输入与发送；</li>
 * <li>请求体<b>不携带</b>用户参数：即使页签里残留旧版 `ai-model-params:<sessionId>`，
 *     这一轮也没有 `params` 键（后端 `allow.userParams` 门控与后端能力保留不动）；</li>
 * <li>`stores/ai-model.ts` 整段成为死代码 ⇒ 一并删除：panel 挂载后 pinia 里<b>没有</b> `aiModel` 这个 store
 *     （防"接回 popover"式的死代码复活）。</li>
 * </ul>
 *
 * <h2>证红方式</h2>
 * <ul>
 * <li>把 `params-toggle` / popover 放回 composer ⇒ 第 1、2、3 条红；</li>
 * <li>`send()` 恢复展开参数投影 ⇒ 第 4、5、6 条红（body 多出 `params`）；</li>
 * <li>把 `useAiModelStore()` 接回 panel ⇒ 第 8 条红（pinia 里重新出现 `aiModel`）；</li>
 * <li>顺手删掉 ContextPill 或发送钮 ⇒ 第 7 条红（去 UI 不等于拆掉相邻能力）。</li>
 * </ul>
 */
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, getActivePinia, setActivePinia } from 'pinia'
import { installIdentityFixture } from './ai-identity-fixture'
import { existsSync } from 'node:fs'
import { resolve } from 'node:path'
import ContextPill from '@/components/AiPanel/ContextPill.vue'

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

const HEALTH_BASE = {
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
  checkedAt: '2026-01-01T00:00:00Z'
}

function health(allowedParams: boolean) {
  return {
    ...HEALTH_BASE,
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
  api.health.mockResolvedValue(health(true))
  api.history.mockResolvedValue({ count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({ found: false, runId: null, awaitingExternal: false, pendingEntries: [] })
  api.chat.mockResolvedValue(undefined)
  installIdentityFixture()
})

async function mountPanel(): Promise<ReturnType<typeof mount>> {
  const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
  const wrapper = mount(AiPanel, { global: { stubs } })
  await flushPromises()
  return wrapper
}

async function sendOnce(wrapper: Awaited<ReturnType<typeof mountPanel>>, text = '你好'): Promise<Record<string, unknown>> {
  await wrapper.find('[data-testid="composer-input"]').setValue(text)
  await wrapper.find('[data-testid="send-btn"]').trigger('click')
  await flushPromises()
  return api.chat.mock.calls[0][0] as Record<string, unknown>
}

describe('F1 参数弹层已退场', () => {
  it('composer 底栏不再有参数触发器与参数弹层', async () => {
    const wrapper = await mountPanel()

    expect(wrapper.find('[data-testid="params-toggle"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="params-popover"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('三项输入 / 恢复默认 / 锁定文案一并退场（策略允许时也不出）', async () => {
    const wrapper = await mountPanel()

    expect(wrapper.find('[data-testid="param-temperature"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="param-top-p"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="param-max-tokens"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="params-reset"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="params-locked"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('composer 底栏无「参数 / 恢复默认」残留文案（不是只拆 testid）', async () => {
    const wrapper = await mountPanel()

    const footer = wrapper.find('[data-testid="composer-input"]').element.parentElement?.lastElementChild as HTMLElement
    expect(footer.textContent ?? '').not.toContain('参数')
    expect(footer.textContent ?? '').not.toContain('恢复默认')
    wrapper.unmount()
  })

  it('参数 store 文件已删除（死代码不留在树里，不是"导入但不使用"）', () => {
    const storeFile = resolve(process.cwd(), 'src/stores/ai-model.ts')

    expect(existsSync(storeFile)).toBe(false)
  })

  it('面板里没有任何数字输入（参数回显的唯一载体已不存在）', async () => {
    const wrapper = await mountPanel()

    expect(wrapper.findAll('input[type="number"]').length).toBe(0)
    wrapper.unmount()
  })

  it('allowedParams=false 时也不再出现锁定文案（锁的是已退场的界面，不是"点了没反应"）', async () => {
    api.health.mockResolvedValue(health(false))
    const wrapper = await mountPanel()

    expect(wrapper.find('[data-testid="params-locked"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="params-toggle"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('请求体只有 sessionId/message/context —— 没有 params', async () => {
    const wrapper = await mountPanel()

    const body = await sendOnce(wrapper)

    expect(body).not.toHaveProperty('params')
    expect(Object.keys(body).sort()).toEqual(['context', 'message', 'sessionId'])
    wrapper.unmount()
  })

  it('页签残留 ai-model-params:<sessionId> 也不进请求体（残留态不复活已移除的能力）', async () => {
    const wrapper = await mountPanel()
    const { useAiStore } = await import('@/stores/ai')
    const sessionId = useAiStore().sessionId
    sessionStorage.setItem(`ai-model-params:${sessionId}`, JSON.stringify({ temperature: 0.9, topP: 0.5 }))

    const body = await sendOnce(wrapper)

    expect(body).not.toHaveProperty('params')
    wrapper.unmount()
  })

  it('残留参数 + 锁策略 + 残留 ai-model-id：连续两轮都不带 params / modelId', async () => {
    api.health.mockResolvedValue(health(false))
    sessionStorage.setItem('ai-model-id', 'plain-model')
    const wrapper = await mountPanel()
    const { useAiStore } = await import('@/stores/ai')
    const sessionId = useAiStore().sessionId
    sessionStorage.setItem(`ai-model-params:${sessionId}`, JSON.stringify({ maxTokens: 2048 }))

    const first = await sendOnce(wrapper, '第一轮')
    await wrapper.find('[data-testid="composer-input"]').setValue('第二轮')
    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()
    const second = api.chat.mock.calls[1][0] as Record<string, unknown>

    for (const body of [first, second]) {
      expect(body).not.toHaveProperty('params')
      expect(body).not.toHaveProperty('modelId')
    }
    wrapper.unmount()
  })

  it('参数 store 不再挂载（死代码不留"接回 popover"的入口）', async () => {
    const wrapper = await mountPanel()

    const stateKeys = Object.keys(getActivePinia()!.state.value)

    expect(stateKeys).not.toContain('aiModel')
    wrapper.unmount()
  })

  it('去 UI 不等于拆相邻能力：输入可编辑、发送钮与上下文 pill 仍在', async () => {
    const wrapper = await mountPanel()

    expect(wrapper.find('[data-testid="composer-input"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('[data-testid="send-btn"]').exists()).toBe(true)
    expect(wrapper.findComponent(ContextPill).exists()).toBe(true)
    wrapper.unmount()
  })
})
