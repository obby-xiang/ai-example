/**
 * AI 可视可操作专项 · `ui_fill`（设计稿 §3.3.3）。
 *
 * 覆盖：T-16（`v-model` 生效 = 派发 input）、T-17（未声明 fill=allow 拒绝）、
 * T-18（value 超 200 拒绝）、T-19（回读不一致如实 failed）、T-49（复合控件未标 allow 拒绝）。
 */
import { describe, expect, it, beforeEach, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { aiFill, aiSnapshot, beginUiTurn } from '@/utils/ai-dom-tools'
import { currentAiEpoch } from '@/utils/ai-regions'
import { enterImportPage, makeRoot, registerRegion, resetAiTestState } from './ai-fixtures'

beforeEach(() => {
  resetAiTestState()
  beginUiTurn('t-fill')
})

function input(refName: string, extra = ''): string {
  return `<input type="text" data-ai-ref="${refName}" ${extra} />`
}

describe('T-16：Vue v-model 生效（派发 input 事件，不是只改 .value）', () => {
  it('挂载组件后写值 ⇒ 组件状态同步', async () => {
    // v-model 的机制 = `value` 绑定 + `input` 事件回写；这里原样复刻该机制，
    // 断言"值真的被 Vue 收下"（只设 el.value 不派发 input ⇒ 本用例红）。
    const model = ref('')
    const Comp = defineComponent({
      setup() {
        return () => h('input', {
          'data-ai-ref': 'import.upload.query',
          'data-ai-fill': 'allow',
          value: model.value,
          onInput: (event: Event) => {
            model.value = (event.target as HTMLInputElement).value
          }
        })
      }
    })
    enterImportPage()
    const host = document.createElement('div')
    document.body.appendChild(host)
    const wrapper = mount(Comp, { attachTo: host })
    registerRegion(host, { id: 'import.upload', risk: 'mutate-local' })
    aiSnapshot({})
    const out = await aiFill({ ref: 'import.upload.query', value: 'COUNTRY', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('ok')
    expect(model.value).toBe('COUNTRY')
    expect((wrapper.element as HTMLInputElement).value).toBe('COUNTRY')
    wrapper.unmount()
  })
})

describe('T-17：未声明 data-ai-fill="allow" ⇒ 拒绝', () => {
  it('拒绝且值不变', async () => {
    enterImportPage()
    registerRegion(makeRoot(input('import.upload.plain', 'value="ORIGINAL"')), { risk: 'mutate-local' })
    const before = (document.querySelector('[data-ai-ref="import.upload.plain"]') as HTMLInputElement).value
    aiSnapshot({})
    const out = await aiFill({ ref: 'import.upload.plain', value: 'HACKED', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('failed')
    expect(out.result).toContain('未声明可填写')
    expect((document.querySelector('[data-ai-ref="import.upload.plain"]') as HTMLInputElement).value).toBe(before)
  })

  it('T-49 el-input-number 类复合控件（role=spinbutton）未标 allow ⇒ 拒绝', async () => {
    enterImportPage()
    registerRegion(makeRoot('<input type="text" role="spinbutton" data-ai-ref="import.upload.num" value="1" />'), { risk: 'mutate-local' })
    aiSnapshot({})
    const out = await aiFill({ ref: 'import.upload.num', value: '2', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('failed')
  })
})

describe('T-18：value 长度闸（≤200）', () => {
  it('201 字符 ⇒ 参数拒绝，零写入', async () => {
    enterImportPage()
    registerRegion(makeRoot(input('import.upload.query', 'data-ai-fill="allow" value=""')), { risk: 'mutate-local' })
    const node = document.querySelector('[data-ai-ref="import.upload.query"]') as HTMLInputElement
    aiSnapshot({})
    const out = await aiFill({ ref: 'import.upload.query', value: 'x'.repeat(201), epoch: currentAiEpoch() })
    expect(out.outcome).toBe('failed')
    expect(out.result).toContain('200')
    expect(node.value).toBe('')
  })

  it('正好 200 字符放行', async () => {
    enterImportPage()
    registerRegion(makeRoot(input('import.upload.query', 'data-ai-fill="allow"')), { risk: 'mutate-local' })
    aiSnapshot({})
    const out = await aiFill({ ref: 'import.upload.query', value: 'y'.repeat(200), epoch: currentAiEpoch() })
    expect(out.outcome).toBe('ok')
  })
})

describe('T-19：写后回读不一致 ⇒ failed（不谎报）', () => {
  it('页面校验器改值 ⇒ failed + 「值未生效」', async () => {
    enterImportPage()
    registerRegion(makeRoot(input('import.upload.code', 'data-ai-fill="allow"')), { risk: 'mutate-local' })
    const node = document.querySelector('[data-ai-ref="import.upload.code"]') as HTMLInputElement
    // 页面侧格式化：只允许数字（模拟 el-input 的格式化/校验替换）
    node.addEventListener('input', () => {
      node.value = node.value.replace(/\D/g, '')
    })
    aiSnapshot({})
    const out = await aiFill({ ref: 'import.upload.code', value: 'ab12', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('failed')
    expect(out.result).toContain('值未生效')
  })

  it('回读一致 ⇒ 回执带原值（可撤销）', async () => {
    enterImportPage()
    registerRegion(makeRoot(input('import.upload.query', 'data-ai-fill="allow" value="OLD"')), { risk: 'mutate-local' })
    aiSnapshot({})
    const out = await aiFill({ ref: 'import.upload.query', value: 'NEW', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('ok')
    expect(out.result).toContain('已填写')
    expect(out.result).toContain('OLD')
    expect(out.result).toContain('可撤销')
  })
})

describe('textarea / contenteditable 同样受 allow 约束', () => {
  it('textarea 标 allow 可写；contenteditable 未标拒绝', async () => {
    enterImportPage()
    registerRegion(makeRoot(
      '<textarea data-ai-ref="import.upload.note" data-ai-fill="allow"></textarea>'
        + '<div contenteditable="true" data-ai-ref="import.upload.rich"></div>'
    ), { risk: 'mutate-local' })
    aiSnapshot({})
    const ok = await aiFill({ ref: 'import.upload.note', value: '备注', epoch: currentAiEpoch() })
    expect(ok.outcome).toBe('ok')
    expect((document.querySelector('[data-ai-ref="import.upload.note"]') as HTMLTextAreaElement).value).toBe('备注')
    const denied = await aiFill({ ref: 'import.upload.rich', value: '富文本', epoch: currentAiEpoch() })
    expect(denied.outcome).toBe('failed')
  })
})

describe('元素围栏：data-ai-verbs 收紧', () => {
  it('声明只允许 click 的元素拒绝 fill', async () => {
    enterImportPage()
    registerRegion(makeRoot(input('import.upload.query', 'data-ai-fill="allow" data-ai-verbs="click"')), { risk: 'mutate-local' })
    const spy = vi.spyOn(document.querySelector('[data-ai-ref="import.upload.query"]') as HTMLElement, 'click')
    aiSnapshot({})
    const out = await aiFill({ ref: 'import.upload.query', value: 'X', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('failed')
    expect(out.result).toContain('fill')
    expect(spy).toHaveBeenCalledTimes(0)
  })
})
