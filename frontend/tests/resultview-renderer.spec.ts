/**
 * S6 批 2 · {@code ResultViewRenderer} 的渲染锁（设计稿 §7.1-② / §7.2-3 / §7.2-7）。
 *
 * 判据：kind 分派（table/text）、未知 kind 降级为 fallback 文案（不白屏、不抛错）、
 * 数据引用解析（inline / ref 指向 result JSON）、规模上限（收敛到 200 行）、
 * 表格 a11y（caption + 表头 scope="col"）、纯文本转义（不 v-html、不产生 script 元素）。
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import zhCN from '@/locales/zh-CN.json'
import ResultViewRenderer from '@/components/AiPanel/resultview/ResultViewRenderer.vue'
import type { ResultView } from '@/types/resultview'

const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zhCN } })

function mountView(view: ResultView | unknown, documentText?: string | null) {
  return mount(ResultViewRenderer, {
    props: { view: view as ResultView, documentText: documentText ?? null },
    global: { plugins: [createPinia(), i18n] }
  })
}

const table: ResultView = {
  version: '1.0',
  id: 'rv-1',
  revision: 1,
  kind: 'table',
  title: 'i18n:resultview.publishConflict.title',
  status: 'ok',
  data: {
    inline: {
      rows: [
        { defCode: 'CURRENCY', rowKey: 'GLOBAL', message: '币种精度与主数据不一致' },
        { defCode: 'TAX_RULE', rowKey: 'PRJ-07', message: '税率区间重叠' }
      ]
    }
  },
  columns: [
    { key: 'defCode', type: 'string', label: 'i18n:resultview.publishConflict.col.defCode', width: 4 },
    { key: 'rowKey', type: 'string', label: 'i18n:resultview.publishConflict.col.rowKey', width: 3 },
    { key: 'message', type: 'string', label: 'i18n:resultview.publishConflict.col.message', width: 5 }
  ],
  a11y: { label: 'i18n:resultview.publishConflict.label', role: 'table' },
  fallback: { text: 'i18n:resultview.publishConflict.fallback', format: 'plain' }
}

describe('ResultViewRenderer · kind 分派', () => {
  it('table ⇒ 真表格：caption + scope="col" + 单元格纯文本', () => {
    const wrapper = mountView(table)
    const el = wrapper.find('[data-testid="resultview-table"]')
    expect(el.exists()).toBe(true)
    expect(el.element.tagName).toBe('TABLE')
    const caption = el.find('caption')
    expect(caption.exists()).toBe(true)
    expect(caption.text()).toContain('发布冲突明细')
    const headers = el.findAll('th')
    expect(headers.map((item) => item.attributes('scope'))).toEqual(['col', 'col', 'col'])
    expect(headers.map((item) => item.text())).toEqual(['配置', '行键', '原因'])
    expect(el.text()).toContain('CURRENCY')
    expect(el.text()).toContain('税率区间重叠')
    wrapper.unmount()
  })

  it('text ⇒ 纯文本行，不渲表格', () => {
    const wrapper = mountView({
      ...table,
      kind: 'text',
      columns: undefined,
      data: undefined,
      description: 'i18n:resultview.publishConflict.fallback'
    })
    expect(wrapper.find('[data-testid="resultview-table"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="resultview-text"]').text()).toContain('发布冲突')
    wrapper.unmount()
  })

  it('未知 kind ⇒ fallback 文案（不白屏、不抛错）', () => {
    const wrapper = mountView({
      ...table,
      kind: 'gauge',
      fallback: { text: 'i18n:resultview.publishConflict.fallback', format: 'plain' }
    })
    expect(wrapper.find('[data-testid="resultview-table"]').exists()).toBe(false)
    const fallback = wrapper.find('[data-testid="resultview-fallback"]')
    expect(fallback.exists()).toBe(true)
    expect(fallback.text()).toContain('无法以表格展示')
    wrapper.unmount()
  })

  it('status=error ⇒ 渲 error.message（不渲半张表）', () => {
    const wrapper = mountView({
      ...table,
      status: 'error',
      error: { code: 'RESULT_VIEW_UNSUPPORTED', message: 'i18n:resultview.fallback.generic' }
    })
    expect(wrapper.find('[data-testid="resultview-table"]').exists()).toBe(false)
    const fallback = wrapper.find('[data-testid="resultview-fallback"]')
    expect(fallback.exists()).toBe(true)
    expect(fallback.text()).toContain('不支持结构化展示')
    wrapper.unmount()
  })

  it('chart 占位 ⇒ fallback（v1 不实现图表）', () => {
    const wrapper = mountView({ ...table, kind: 'chart' })
    expect(wrapper.find('[data-testid="resultview-fallback"]').exists()).toBe(true)
    wrapper.unmount()
  })
})

describe('ResultViewRenderer · 数据引用与上限', () => {
  it('data.ref 指向 result JSON 文档时按 RFC6901 求值', () => {
    const wrapper = mountView(
      { ...table, data: { ref: '/rows' } },
      JSON.stringify({ rows: [{ defCode: 'REF_A', rowKey: 'R1', message: '引用解析成功' }] })
    )
    expect(wrapper.find('[data-testid="resultview-table"]').text()).toContain('引用解析成功')
    wrapper.unmount()
  })

  it('data 解不出 ⇒ fallback（不渲空表）', () => {
    const wrapper = mountView({ ...table, data: { ref: '/rows' } }, '作业已启动')
    expect(wrapper.find('[data-testid="resultview-table"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="resultview-fallback"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('>200 行收敛到 200 行并给出截断说明', () => {
    const rows = Array.from({ length: 240 }, (_, index) => ({
      defCode: `D${index}`, rowKey: `R${index}`, message: '冲突'
    }))
    const wrapper = mountView({ ...table, data: { inline: { rows } } })
    expect(wrapper.findAll('[data-testid="resultview-row"]')).toHaveLength(200)
    expect(wrapper.find('[data-testid="resultview-truncated"]').text()).toContain('240')
    wrapper.unmount()
  })

  it('partial + meta.truncated ⇒ 渲"共 N 行，展示前 M 行"', () => {
    const wrapper = mountView({
      ...table,
      status: 'partial',
      meta: { catalogId: 'configmgr/resultview/v1', truncated: { shownRows: 5, totalRows: 9 } }
    })
    const note = wrapper.find('[data-testid="resultview-truncated"]')
    expect(note.text()).toContain('9')
    expect(note.text()).toContain('5')
    wrapper.unmount()
  })

  it('hidden 列不参与渲染', () => {
    const wrapper = mountView({
      ...table,
      columns: [
        { key: 'defCode', type: 'string', label: 'i18n:resultview.publishConflict.col.defCode' },
        { key: 'rowKey', type: 'string', label: 'i18n:resultview.publishConflict.col.rowKey', hidden: true }
      ]
    })
    const headers = wrapper.findAll('th')
    expect(headers).toHaveLength(1)
    expect(headers[0].text()).toBe('配置')
    wrapper.unmount()
  })
})

describe('ResultViewRenderer · 安全与 a11y', () => {
  it('单元格里的 HTML 不被当标记执行（文本化）', () => {
    const wrapper = mountView({
      ...table,
      data: { inline: { rows: [{ defCode: '<b>x</b>', rowKey: 'R', message: '<script>alert(1)</script>' }] } }
    })
    const el = wrapper.find('[data-testid="resultview-table"]')
    expect(el.find('script').exists()).toBe(false)
    expect(el.find('b').exists()).toBe(false)
    expect(el.text()).toContain('<script>alert(1)</script>')
    wrapper.unmount()
  })

  it('空态渲 empty.text（不渲空表）', () => {
    const wrapper = mountView({
      ...table,
      status: 'empty',
      data: { inline: { rows: [] } },
      empty: { text: 'i18n:resultview.publishConflict.fallback' }
    })
    expect(wrapper.find('[data-testid="resultview-table"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="resultview-empty"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('a11y：表格带 aria-label（取 a11y.label → i18n）', () => {
    const wrapper = mountView(table)
    expect(wrapper.find('[data-testid="resultview-table"]').attributes('aria-label')).toBe('发布冲突明细表')
    wrapper.unmount()
  })
})
