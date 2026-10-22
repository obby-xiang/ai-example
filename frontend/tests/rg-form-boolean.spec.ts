/**
 * 批G · G7：生成式表单的 boolean 开关**真实点击**可用。
 *
 * <p>
 * 实证 D4：装饰层（`absolute inset-0` 的轨道 span）盖在原生 checkbox 上且没有
 * `pointer-events-none` —— 真实鼠标点不动（只有 DOM `.click()` 在控件本身上才生效）。
 * 修复 = 装饰层 `pointer-events-none`（点击穿透到 input）+ 外层 `<label>` 包裹（原生激活兜底）。
 *
 * <p>
 * jsdom 不做命中测试（CSS `pointer-events` 不参与），因此这里分两层锁：
 * ① **结构锁**（红测点）：装饰层必须 `pointer-events-none` 且被 `<label>` 包裹；
 * ② **行为锁**：点击控件本身改变 `checked` 并把新值以 `draft` 抛给 store。
 * 真机鼠标点击的最终验收见施工报告的「真机验收清单」。
 */
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import FormRenderer from '@/components/AiPanel/FormRenderer.vue'
import type { FormSpec } from '@/types/form-schema'

vi.mock('@/api/ai', () => ({
  aiApi: {},
  uploadFormAttachment: vi.fn(),
  formOptionSource: vi.fn().mockResolvedValue({ options: [] })
}))

const SCHEMA: FormSpec = {
  scenario: 'FILTER',
  fields: [
    { key: 'onlyPk', label: '只看含主键', type: 'boolean' },
    { key: 'name', label: '名称', type: 'text' }
  ]
}

beforeEach(() => {
  document.body.innerHTML = ''
})

describe('批G · G7：boolean 开关真实点击改变 checked', () => {
  it('装饰层 pointer-events-none + label 包裹（点击可穿透/可激活）', () => {
    const wrapper = mount(FormRenderer, { props: { schema: SCHEMA, submitting: false, error: null } })
    const input = wrapper.find('#gf-field-onlyPk').element as HTMLInputElement
    const control = input.closest('label')
    expect(control).not.toBeNull() // 原生激活兜底：点 label 内任何一点都会转发给控件

    const decorations = Array.from(control?.querySelectorAll('span') ?? [])
      .filter((node) => node.getAttribute('aria-hidden') === 'true')
    expect(decorations.length).toBeGreaterThan(0)
    for (const node of decorations) {
      expect(node.className).toContain('pointer-events-none') // 装饰层不得吞掉点击
    }
    wrapper.unmount()
  })

  it('点击控件改变 checked，并把新值以 draft 抛给 store（真值来源不是本地只读快照）', async () => {
    const wrapper = mount(FormRenderer, { props: { schema: SCHEMA, submitting: false, error: null } })
    const input = wrapper.find('#gf-field-onlyPk')
    const element = input.element as HTMLInputElement
    expect(element.checked).toBe(false)

    element.click()
    await wrapper.vm.$nextTick()
    expect(element.checked).toBe(true)
    element.dispatchEvent(new Event('change', { bubbles: true }))
    await wrapper.vm.$nextTick()
    expect((wrapper.emitted('draft') ?? []).at(-1)?.[0]).toMatchObject({ onlyPk: true })

    element.click()
    await wrapper.vm.$nextTick()
    expect(element.checked).toBe(false)
    element.dispatchEvent(new Event('change', { bubbles: true }))
    await wrapper.vm.$nextTick()
    expect((wrapper.emitted('draft') ?? []).at(-1)?.[0]).toMatchObject({ onlyPk: false })
    wrapper.unmount()
  })
})
