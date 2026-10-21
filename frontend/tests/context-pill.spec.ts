/**
 * 上下文占用药丸（UI/R 组：C-31…C-34；上下文统计与压缩专项批①）。
 *
 * <h2>为什么先测纯函数</h2>
 * 三档边界（70/85/95）与 `k` 缩写是"逐点可错"的判定：`>=` 写成 `>` 只差一格，而挂载后读 class
 * 的用例很难把这种差写成可读的失败信息。判定抽到 `utils/context-usage.ts` 后，边界是 6 个断言点的
 * 表格；组件用例只负责"把判定结果贴到 DOM 上"。
 *
 * <h2>断言面</h2>
 * <ol>
 * <li>C-31 文本格式：`66% · 84k/128k`，1000 以下不缩写、`k` 保留一位（整数不补 .0）；</li>
 * <li>C-32 三档色：69/70、84/85、94/95 逐点（边界归高档）；</li>
 * <li>C-33 `≈` 与未知上限两态：估算带 `≈`；上限未知只显示已用；</li>
 * <li>C-34 分项 popover：七项顺序稳定、`free<0` 显示"已超 +N"。</li>
 * </ol>
 */
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const api = vi.hoisted(() => ({
  getContext: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

import ContextPill from '@/components/AiPanel/ContextPill.vue'
import { useAiStore } from '@/stores/ai'
import type { ContextUsage } from '@/types/ai'
import {
  CONTEXT_TIER_DANGER,
  CONTEXT_TIER_HIGH,
  CONTEXT_TIER_WARN,
  contextPillText,
  contextTier,
  formatPartTokens,
  formatPercent,
  formatTokens,
  partLabel,
  tierClass,
  tierTitle
} from '@/utils/context-usage'

const measured = (used: number, limit: number | null, est = false): ContextUsage => ({
  usedTokens: used,
  limitTokens: limit,
  percent: limit === null ? null : Math.round((used * 1000) / limit) / 10,
  level: 'none',
  est,
  circuit: 'closed'
})

describe('formatTokens（C-31：k 缩写纪律）', () => {
  it('1000 以下不缩写', () => {
    expect(formatTokens(0)).toBe('0')
    expect(formatTokens(999)).toBe('999')
  })

  it('k 保留一位、整数不补 .0', () => {
    expect(formatTokens(1000)).toBe('1k')
    expect(formatTokens(1500)).toBe('1.5k')
    expect(formatTokens(84213)).toBe('84.2k')
    expect(formatTokens(128000)).toBe('128k')
    expect(formatTokens(1250)).toBe('1.3k')
  })
})

describe('contextTier（C-32：三档边界逐点）', () => {
  it('阈值常量与设计稿一致', () => {
    expect([CONTEXT_TIER_WARN, CONTEXT_TIER_HIGH, CONTEXT_TIER_DANGER]).toEqual([70, 85, 95])
  })

  it('69/70、84/85、94/95 逐点切换（边界归高档）', () => {
    expect(contextTier(0)).toBe('safe')
    expect(contextTier(69)).toBe('safe')
    expect(contextTier(70)).toBe('warn')
    expect(contextTier(84)).toBe('warn')
    expect(contextTier(85)).toBe('high')
    expect(contextTier(94)).toBe('high')
    expect(contextTier(95)).toBe('danger')
    expect(contextTier(100)).toBe('danger')
  })

  it('上限未知/未测 ⇒ none（不换色）', () => {
    expect(contextTier(null)).toBe('none')
  })

  it('每档配色互不相同（防"换了档但看着一样"）', () => {
    const classes = (['safe', 'warn', 'high', 'danger'] as const).map((tier) => tierClass(tier))
    expect(new Set(classes).size).toBe(4)
    expect(tierClass('none')).toBe(tierClass('safe'))
  })
})

describe('contextPillText（C-31 / C-33）', () => {
  it('有上限：百分比 · 已用/上限', () => {
    expect(contextPillText(measured(84213, 128000))).toBe('65.8% · 84.2k/128k')
  })

  it('整数百分比不带小数', () => {
    expect(contextPillText(measured(70400, 128000))).toBe('55% · 70.4k/128k')
    expect(formatPercent(66)).toBe('66')
  })

  it('估算态前缀 ≈（C-33）', () => {
    expect(contextPillText(measured(84213, 128000, true))).toBe('≈65.8% · 84.2k/128k')
  })

  it('上限未知：只显示已用，不给百分比（C-33）', () => {
    expect(contextPillText(measured(84213, null))).toBe('已用 84.2k')
    expect(contextPillText(measured(84213, null, true))).toBe('≈已用 84.2k')
  })

  it('未测/无数据：空串（调用方据此不渲染）', () => {
    expect(contextPillText(null)).toBe('')
    expect(contextPillText(measured(0, 128000))).toBe('0% · 0/128k')
    expect(contextPillText({ ...measured(0, null), percent: null })).toBe('')
  })
})

describe('分项展示（C-34）', () => {
  it('七项名与未知键兜底', () => {
    expect(partLabel('toolResults')).toBe('工具结果')
    expect(partLabel('free')).toBe('剩余可用')
    expect(partLabel('byTool')).toBe('byTool')
  })

  it('free 为负显示"已超 +N"，不为负显示剩余', () => {
    expect(formatPartTokens('free', -1200)).toBe('已超 1.2k')
    expect(formatPartTokens('free', 43787)).toBe('43.8k tokens')
    expect(formatPartTokens('history', 41000)).toBe('41k tokens')
  })

  it('tierTitle 说清"会发生什么"，不做行情式承诺', () => {
    // 档位越高，措辞越具体（75% ⇒ 轻量清理线；90% ⇒ 压缩区）
    expect(tierTitle(measured(90000, 128000))).toContain('90k / 128k')
    expect(tierTitle(measured(110000, 128000))).toContain('压缩区')
    expect(tierTitle(measured(123000, 128000))).toContain('清理历史工具结果')
    expect(tierTitle(measured(84213, null))).toContain('已用 84.2k')
    expect(tierTitle(measured(84213, 128000, true))).toContain('估算')
  })
})

describe('组件（C-33 / C-34 的 DOM 面）', () => {
  beforeAll(async () => {
    await import('@/components/AiPanel/ContextPill.vue')
  }, 30_000)

  beforeEach(() => {
    setActivePinia(createPinia())
    api.getContext.mockReset()
  })

  it('无数据不渲染药丸', async () => {
    const wrapper = mount(ContextPill)
    await flushPromises()
    expect(wrapper.find('[data-testid="context-pill"]').exists()).toBe(false)
  })

  it('渲染文本 + 档位标记 + 点击展开七项（按服务端顺序）', async () => {
    const store = useAiStore()
    store.contextUsage = measured(84213, 128000)
    const wrapper = mount(ContextPill)
    await flushPromises()

    const pill = wrapper.find('[data-testid="context-pill"]')
    expect(pill.text()).toBe('65.8% · 84.2k/128k')
    expect(pill.attributes('data-tier')).toBe('safe')
    expect(pill.attributes('title')).toContain('84.2k / 128k')

    // 点开：端点拉七项（拆分不进 done 帧）
    api.getContext.mockResolvedValue({
      sessionId: 's-1',
      model: 'deepseek-flash',
      turnNo: 7,
      measuredAtMs: 1,
      limitTokens: 128000,
      usedTokens: 84213,
      percent: 65.8,
      source: 'stream',
      estimated: false,
      breakdown: [
        { key: 'history', tokens: 41000 },
        { key: 'toolResults', tokens: 34080 },
        { key: 'summary', tokens: 0 },
        { key: 'system', tokens: 3120 },
        { key: 'tools', tokens: 5120 },
        { key: 'input', tokens: 93 },
        { key: 'free', tokens: -1200 }
      ],
      compaction: { level: 'none', compactedTurns: 0, lostTokens: 0, lastAtMs: null, reason: null, circuit: 'closed', fuseFails: 0 }
    })
    await pill.trigger('click')
    await flushPromises()

    const rows = wrapper.findAll('[data-testid^="context-row-"]')
    expect(rows.map((row) => row.attributes('data-testid'))).toEqual([
      'context-row-history',
      'context-row-toolResults',
      'context-row-summary',
      'context-row-system',
      'context-row-tools',
      'context-row-input',
      'context-row-free'
    ])
    expect(wrapper.find('[data-testid="context-row-free"]').text()).toContain('已超 1.2k')
    expect(wrapper.find('[data-testid="context-row-history"]').text()).toContain('41k tokens')
  })

  it('未知上限：药丸只显示已用且档位为 none', async () => {
    const store = useAiStore()
    store.contextUsage = measured(84213, null)
    const wrapper = mount(ContextPill)
    await flushPromises()

    const pill = wrapper.find('[data-testid="context-pill"]')
    expect(pill.text()).toBe('已用 84.2k')
    expect(pill.attributes('data-tier')).toBe('none')
  })
})
