/**
 * 批次3 补齐 · AR-10（Redis 24h 只可见化）：挂起 / 确认 / 表单卡面必须能看到
 * 本轮运行态的**剩余时间**，到期后转成**已失效 + 可达出口**，且卡本身不得静默消失。
 *
 * 数据来源口径（不做第二套）：后端 `GET /api/ai/runs/current` 的 `snapshotRemainingMs`
 * 是 Redis `PTTL(ai:run:<runId>)` 的只读推导 —— 前端只按"收到时刻 + 剩余毫秒"递减，
 * 不自己用 24h 常量另算一套到期时间。
 *
 * 无障碍口径：计时文本不进 live region（`aria-live="off"`，外层无 polite/assertive 祖先）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { useAiStore } from '@/stores/ai'
import { installIdentityFixture } from './ai-identity-fixture'
import { applyLocale } from '@/locales'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  confirm: vi.fn(),
  frontendToolResult: vi.fn(),
  deleteHistory: vi.fn(),
  getContext: vi.fn()
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

async function mountPanel(): Promise<ReturnType<typeof mount>> {
  const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
  const wrapper = mount(AiPanel, { global: { stubs } })
  await nextTick()
  return wrapper
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.health.mockResolvedValue({
    available: true,
    code: 'OK',
    model: 'm',
    baseUrl: 'b',
    memoryBackend: 'redis',
    sessionTtl: '24h',
    frontendToolTimeoutSeconds: 120,
    suspendPoolSize: 4,
    activeSuspendGates: 0,
    heldSessions: 0,
    sessionLockRenewals: 0,
    resumeOnStartup: false,
    instanceId: 'i',
    checkedAt: '2026-10-26T08:00:00Z'
  })
  api.history.mockResolvedValue({ sessionId: 'sess-ar10', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({ found: false })
  api.chat.mockImplementation(() => new Promise(() => {}))
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  applyLocale('zh-CN')
  installIdentityFixture()
})

describe('AR-10 · 运行态保留期（界面倒计时已退役，只留 store 锚点与到期通知）', () => {
  it('恢复快照落入 runExpiry 锚点；到期由权威对账发 runExpired 通知，卡不静默消失', async () => {
    vi.useFakeTimers()
    try {
      vi.setSystemTime(new Date('2026-10-26T08:00:00Z'))
      const ai = useAiStore()
      ai.sessionId = 'sess-ar10'
      ai.historyLoaded = true
      ai.expanded = true
      api.runsCurrent.mockResolvedValue({
        found: true,
        runId: 'run-ar10',
        snapshotStatus: 'SUSPENDED',
        pendingEntries: [
          {
            toolCallId: 'c1',
            entryStatus: 'PENDING',
            kind: 'CONFIRM',
            name: 'start_export',
            arguments: '{"taskId":1}',
            req: true
          }
        ],
        unresolvedExternal: ['c1'],
        awaitingExternal: true,
        archiveMaxSeq: 3,
        snapshotRemainingMs: 120_000,
        snapshotExpiresAt: Date.now() + 120_000
      })
      await ai.restoreSuspendedSnapshot(() => true)

      // 计数只落在 store 的 runExpiry 锚点（界面倒计时整段退役）
      expect(ai.runExpiry).not.toBeNull()
      expect(ai.runExpiry?.remainingMs).toBe(120_000)
      expect(ai.runExpiry?.expiresAt).toBe(Date.now() + 120_000)

      const wrapper = await mountPanel()
      // 当前 UI 不再渲染保留期读秒/已失效提示/刷新出口
      expect(wrapper.find('[data-testid="run-expiry-hint"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="run-expiry-text"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="run-expiry-expired"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="run-expiry-reload"]').exists()).toBe(false)
      // 挂起卡本身仍在（卡不因保留期口径退役而消失）
      expect(wrapper.find('[data-testid="hitl-gate"]').exists()).toBe(true)

      // 到期：下一次权威对账给 found=false ⇒ 只发一次 runExpired 通知，锚点归零；卡不静默消失
      vi.advanceTimersByTime(121_000)
      api.runsCurrent.mockResolvedValue({ found: false })
      await ai.restoreSuspendedSnapshot(() => true)
      expect(ai.noticeCode).toBe('ai.notice.runExpired')
      expect(ai.runExpiry?.remainingMs).toBe(0)
      expect(wrapper.find('[data-testid="hitl-gate"]').exists()).toBe(true)
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })
})
