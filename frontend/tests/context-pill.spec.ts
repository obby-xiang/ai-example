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
import { applyLocale } from '@/locales'

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

describe('E3 整体占用口径（百分比 = 整体占用，不是每轮增量）', () => {
  it('tooltip 同时给出 已用 / 上限 / 占比，且措辞是"上下文"而非"本轮"', () => {
    const title = tierTitle(measured(84213, 128000))

    expect(title).toContain('已用 84.2k')
    expect(title).toContain('128k')
    expect(title).toContain('占比 65.8%')
    expect(title).not.toContain('本轮')
  })

  it('上限未知：不给占比（没有分母就没有百分比），仍说已用', () => {
    const title = tierTitle(measured(84213, null))

    expect(title).toContain('已用 84.2k')
    expect(title).not.toContain('占比')
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

  it('渲染为圆环：无可见文字、一行 tooltip、点击不展开、progress value 正确', async () => {
    const store = useAiStore()
    store.contextUsage = measured(84213, 128000)
    const wrapper = mount(ContextPill)
    await flushPromises()

    const pill = wrapper.find('[data-testid="context-pill"]')
    // 语义源是原生 <progress>，不是按钮
    expect(pill.element.tagName).toBe('PROGRESS')
    expect(wrapper.find('button').exists()).toBe(false)
    // 默认态无任何可见文字（不出已用/上限/占比三件，也不出七项）
    expect(pill.text()).toBe('')
    expect(wrapper.element.textContent).toBe('')
    // 圆环读的就是占比本身
    expect(pill.attributes('value')).toBe('65.8')
    expect(pill.attributes('max')).toBe('100')
    expect(pill.attributes('data-tier')).toBe('safe')
    // hover 只有一行（title 与 aria-label 同源），不含已用/上限/占比三件
    const title = pill.attributes('title') ?? ''
    expect(title).toBe('上下文占用 65.8%')
    expect(title).not.toMatch(/[\r\n]/)
    expect(title).not.toContain('84.2k')
    expect(title).not.toContain('128k')
    expect(pill.attributes('aria-label')).toBe('上下文占用 65.8%')

    // 点击不展开：不拉端点、不出详情、不留展开态 a11y 属性
    await pill.trigger('click')
    await flushPromises()
    expect(api.getContext).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid^="context-row-"]').exists()).toBe(false)
    expect(pill.attributes('aria-expanded')).toBeUndefined()
    expect(pill.attributes('aria-haspopup')).toBeUndefined()
  })

  it('未知上限：圆环不确定态（无 value、不谎报 0%），档位 none、tooltip 只说已用', async () => {
    const store = useAiStore()
    store.contextUsage = measured(84213, null)
    const wrapper = mount(ContextPill)
    await flushPromises()

    const pill = wrapper.find('[data-testid="context-pill"]')
    expect(pill.text()).toBe('')
    expect(pill.attributes('data-tier')).toBe('none')
    // 没有分母就没有占比：不写 value（原生不确定态），视觉只剩轨道，绝不用 0 冒充
    expect(pill.element.hasAttribute('value')).toBe(false)
    expect(pill.attributes('aria-label')).toBe('上下文已用 84.2k tokens')
    expect(pill.attributes('title')).toBe('上下文已用 84.2k tokens')
  })

  it('估算态 tooltip 带「约」（估的数必须一眼看出来）', async () => {
    applyLocale('zh-CN')
    const store = useAiStore()
    store.contextUsage = measured(84213, 128000, true)
    const wrapper = mount(ContextPill)
    await flushPromises()

    const pill = wrapper.find('[data-testid="context-pill"]')
    expect(pill.attributes('title')).toBe('上下文占用 约65.8%')
    expect(pill.attributes('aria-label')).toBe('上下文占用 约65.8%')
    expect(pill.text()).toBe('')
    wrapper.unmount()
  })

  it('英文界面 tooltip 走 en-US 一行文案', async () => {
    applyLocale('en-US')
    const store = useAiStore()
    store.contextUsage = measured(84213, 128000)
    const wrapper = mount(ContextPill)
    await flushPromises()

    const pill = wrapper.find('[data-testid="context-pill"]')
    expect(pill.attributes('title')).toBe('Context usage 65.8%')
    expect(pill.attributes('aria-label')).toBe('Context usage 65.8%')
    expect(pill.attributes('title')).not.toContain('已用')
    expect(pill.text()).toBe('')
    wrapper.unmount()
  })
})
