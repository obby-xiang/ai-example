/**
 * GF 优化批（G-A 结构化错误 / G-D 联动 / G-E 附件 / G-F 输入类型 / G-G a11y+i18n）渲染层用例。
 *
 * 这一层锁的是**模板本身**：label/for 与 id 关联、aria-invalid/aria-describedby、错误 live region、
 * 提交失败聚焦首个错误字段、级联选项、条件显隐/必填/禁用、textarea 与 input type、附件控件，
 * 以及文案是否真的走 locales（切到 en-US 后 chrome 文案变英文）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import FormRenderer from '@/components/AiPanel/FormRenderer.vue'
import { applyLocale } from '@/locales'
import type { FormSpec } from '@/types/form-schema'

const api = vi.hoisted(() => ({
  uploadFormAttachment: vi.fn(),
  formOptionSource: vi.fn()
}))

vi.mock('@/api/ai', () => ({
  aiApi: {},
  uploadFormAttachment: api.uploadFormAttachment,
  formOptionSource: api.formOptionSource
}))

const SCHEMA: FormSpec = {
  scenario: 'FILTER',
  fields: [
    { key: 'name', label: '名称', type: 'text', required: true },
    { key: 'mail', label: '邮箱', type: 'text', format: 'email' },
    { key: 'tel', label: '电话', type: 'text', format: 'tel' },
    { key: 'site', label: '网址', type: 'text', format: 'url' },
    { key: 'note', label: '备注', type: 'text', variant: 'longText' }
  ]
}

const CASCADE: FormSpec = {
  scenario: 'FILTER',
  fields: [
    // 父字段用 text（enum 的初值固定取首个选项 —— 那会让"未选"这个测试前置不成立）
    { key: 'country', label: '国家', type: 'text' },
    {
      key: 'province',
      label: '省份',
      type: 'enum',
      optionsFrom: { dependsOn: 'country', map: { CN: [{ value: 'SC' }, { value: 'BJ' }], US: [{ value: 'CA' }] } },
      visibleWhen: { key: 'country', op: 'notEmpty' },
      requiredWhen: { key: 'country', op: 'eq', value: 'CN' }
    },
    { key: 'frozen', label: '冻结', type: 'text', disabledWhen: { key: 'country', op: 'in', values: ['US'] } }
  ]
}

const ATTACH: FormSpec = {
  scenario: 'CLARIFY',
  fields: [{ key: 'evidence', label: '附件', type: 'attachment', required: true }]
}

beforeEach(() => {
  applyLocale('zh-CN')
  api.uploadFormAttachment.mockReset()
  api.formOptionSource.mockReset().mockResolvedValue({ options: [] })
})

afterEach(() => {
  applyLocale('zh-CN')
})

function mountForm(schema: FormSpec, props: Record<string, unknown> = {}, options: Record<string, unknown> = {}) {
  return mount(FormRenderer, {
    props: { schema, submitting: false, error: null, ...props },
    ...options
  })
}

describe('G-G a11y 基线', () => {
  it('label 用 for 关联控件 id，控件带 aria 描述位', () => {
    const wrapper = mountForm(SCHEMA)
    const label = wrapper.find('label[for="gf-field-name"]')
    expect(label.exists()).toBe(true)
    const input = wrapper.find('#gf-field-name')
    expect(input.exists()).toBe(true)
    expect(input.attributes('aria-describedby')).toBe('gf-field-name-hint')
    expect(wrapper.find('#gf-field-name-hint').exists()).toBe(true)
  })

  it('字段错误：aria-invalid=true + describedby 指向错误文案 + live region 存在', async () => {
    const wrapper = mountForm(SCHEMA)
    await wrapper.find('[data-testid="gf-submit"]').trigger('click')
    await flushPromises()

    const input = wrapper.find('#gf-field-name')
    expect(input.attributes('aria-invalid')).toBe('true')
    expect(input.attributes('aria-describedby')).toContain('gf-field-name-error')
    const error = wrapper.find('#gf-field-name-error')
    expect(error.exists()).toBe(true)
    expect(error.text()).toContain('必填')
    // live region：错误出现时读屏能播报
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    expect(wrapper.find('[aria-live]').exists()).toBe(true)
  })

  it('提交失败聚焦首个错误字段', async () => {
    const wrapper = mountForm(SCHEMA, {}, { attachTo: document.body })
    await wrapper.find('[data-testid="gf-submit"]').trigger('click')
    await flushPromises()
    expect(document.activeElement?.id).toBe('gf-field-name')
    wrapper.unmount()
  })
})

describe('G-F 输入类型与 textarea', () => {
  it('format=email/tel/url 用对应 input type，longText 用 textarea', () => {
    const wrapper = mountForm(SCHEMA)
    expect(wrapper.find('#gf-field-mail').attributes('type')).toBe('email')
    expect(wrapper.find('#gf-field-tel').attributes('type')).toBe('tel')
    expect(wrapper.find('#gf-field-site').attributes('type')).toBe('url')
    expect(wrapper.find('textarea#gf-field-note').exists()).toBe(true)
    expect(wrapper.find('textarea#gf-field-note').attributes('maxlength')).toBe('2000')
  })
})

describe('G-D 联动在渲染层生效', () => {
  it('显隐 + 条件必填 + 条件禁用', async () => {
    const wrapper = mountForm(CASCADE)
    // country 未选：province 不渲染
    expect(wrapper.find('#gf-field-province').exists()).toBe(false)

    await wrapper.find('#gf-field-country').setValue('CN')
    expect(wrapper.find('#gf-field-province').exists()).toBe(true)
    expect(wrapper.find('#gf-field-province').attributes('aria-required')).toBe('true')

    await wrapper.find('#gf-field-country').setValue('US')
    expect(wrapper.find('#gf-field-province').exists()).toBe(true)
    expect(wrapper.find('#gf-field-frozen').attributes('disabled')).toBeDefined()
  })

  it('选项级联：A 选 CN ⇒ B 只出 SC/BJ', async () => {
    const wrapper = mountForm(CASCADE)
    await wrapper.find('#gf-field-country').setValue('CN')
    let options = wrapper.find('#gf-field-province').findAll('option').map((o) => o.attributes('value'))
    expect(options).toEqual(['', 'SC', 'BJ'])

    await wrapper.find('#gf-field-country').setValue('US')
    options = wrapper.find('#gf-field-province').findAll('option').map((o) => o.attributes('value'))
    expect(options).toEqual(['', 'CA'])
  })

  it('条件必填在提交流程里生效（country=CN 且 province 空 ⇒ 不发 submit）', async () => {
    const wrapper = mountForm(CASCADE)
    await wrapper.find('#gf-field-country').setValue('CN')
    await wrapper.find('[data-testid="gf-submit"]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('submit')).toBeUndefined()
    expect(wrapper.find('#gf-field-province-error').text()).toContain('必填')
  })

  /**
   * L-77（真机 E2E 批② HGTA 域）：**可选字段留空 = 未填，不提交空串**。
   *
   * 修前：`filter` 场景的可选日期不填时，`visibleValues` 把 `''` 原样上送，后端按「日期格式不合法」
   * 判 400 —— 表单侧却认为"没填就不校验"，两侧口径打架。裁定的修法是**前端丢弃空串可选值**
   * （后端不改），与 `validateDraftModel` 的「空值不算错」同口径。
   */
  it('L-77：可选日期留空 ⇒ 提交值里没有该键（不是空串）', async () => {
    const schema: FormSpec = {
      scenario: 'FILTER',
      fields: [
        { key: 'name', label: '名称', type: 'text', required: true },
        { key: 'from', label: '起始日期', type: 'date' }
      ]
    }
    const wrapper = mountForm(schema)
    await wrapper.find('#gf-field-name').setValue('A')
    await wrapper.find('[data-testid="gf-submit"]').trigger('click')
    await flushPromises()

    const submitted = wrapper.emitted('submit')?.[0]?.[0] as Record<string, unknown>
    expect(submitted).toMatchObject({ name: 'A' })
    expect(Object.prototype.hasOwnProperty.call(submitted, 'from')).toBe(false)
  })

  it('sourceId 级联走后端注册数据源（只认闭集 id）', async () => {
    api.formOptionSource.mockResolvedValue({
      options: [{ value: 'currency_code', label: '币种编码' }]
    })
    const schema: FormSpec = {
      scenario: 'FILTER',
      fields: [
        { key: 'def', label: '定义', type: 'enum', options: [{ value: 'CURRENCY' }, { value: 'DOC_TYPE' }] },
        {
          key: 'field',
          label: '字段',
          type: 'multi_select',
          optionsFrom: { dependsOn: 'def', sourceId: 'definition_fields' }
        }
      ]
    }
    const wrapper = mountForm(schema)
    await flushPromises()
    expect(api.formOptionSource).toHaveBeenCalledWith('definition_fields', 'CURRENCY')
    expect(wrapper.text()).toContain('币种编码')

    await wrapper.find('#gf-field-def').setValue('DOC_TYPE')
    await flushPromises()
    expect(api.formOptionSource).toHaveBeenLastCalledWith('definition_fields', 'DOC_TYPE')
  })
})

describe('G-A 结构化错误按 key 点亮', () => {
  it('后端 iss 数组里的 key 点亮对应字段，而不是只落在卡级错误条', async () => {
    const wrapper = mountForm(SCHEMA, {
      fieldErrors: [{ key: 'mail', code: 'VALUE_FORMAT_MISMATCH', message: '字段 mail 不符合 email 格式' }]
    })
    expect(wrapper.find('#gf-field-mail').attributes('aria-invalid')).toBe('true')
    expect(wrapper.find('#gf-field-mail-error').text()).toContain('email')
    // 其他字段不受牵连
    expect(wrapper.find('#gf-field-name').attributes('aria-invalid')).toBe('false')
  })

  it('code 决定是否可重试：不可重试码只留取消', () => {
    const retryable = mountForm(SCHEMA, {
      fieldErrors: [{ key: 'mail', code: 'VALUE_FORMAT_MISMATCH', message: 'x' }]
    })
    expect(retryable.find('[data-testid="gf-submit"]').exists()).toBe(true)

    const blocked = mountForm(SCHEMA, {
      fieldErrors: [{ key: '', code: 'RESULT_SCHEMA_UNAVAILABLE', message: 'schema 不可用' }]
    })
    expect(blocked.find('[data-testid="gf-submit"]').exists()).toBe(false)
    expect(blocked.find('[data-testid="gf-cancel"]').exists()).toBe(true)
    expect(blocked.text()).toContain('无法继续提交')
  })
})

describe('G-B 草稿镜像事件', () => {
  it('每次改动 emit draft（含当前全部值），不触发 submit', async () => {
    const wrapper = mountForm(SCHEMA)
    await wrapper.find('#gf-field-name').setValue('张三')
    const drafts = wrapper.emitted('draft')
    expect(drafts).toBeTruthy()
    expect(drafts?.at(-1)?.[0]).toMatchObject({ name: '张三' })
    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('初始草稿值回填到控件', () => {
    const wrapper = mountForm(SCHEMA, { initialValues: { name: '已填', mail: 'a@b.co' } })
    expect((wrapper.find('#gf-field-name').element as HTMLInputElement).value).toBe('已填')
    expect((wrapper.find('#gf-field-mail').element as HTMLInputElement).value).toBe('a@b.co')
  })
})

describe('G-E attachment 控件', () => {
  it('选择文件即上传，成功后显示文件名/大小，提交值是 fileId；可删除重传', async () => {
    api.uploadFormAttachment.mockResolvedValue({
      fileId: 'att_12345678',
      fileName: 'evidence.pdf',
      size: 2048
    })
    const wrapper = mountForm(ATTACH, { uploadContext: { runId: 'r1', toolCallId: 'c1', sessionId: 's1' } })
    const input = wrapper.find('#gf-field-evidence')
    expect(input.attributes('type')).toBe('file')

    const file = new File(['abc'], 'evidence.pdf', { type: 'application/pdf' })
    Object.defineProperty(input.element, 'files', { value: [file] })
    await input.trigger('change')
    await flushPromises()

    expect(api.uploadFormAttachment).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('evidence.pdf')
    expect(wrapper.text()).toContain('2.0 KB')

    await wrapper.find('[data-testid="gf-submit"]').trigger('click')
    expect(wrapper.emitted('submit')?.[0]?.[0]).toMatchObject({ evidence: 'att_12345678' })

    // 删除：清空值 + 回到"未选择"
    await wrapper.find('[data-testid="gf-attach-remove-evidence"]').trigger('click')
    expect(wrapper.text()).toContain('选择文件')
  })

  it('文件内容永不出现在提交值里（只有 fileId）', async () => {
    api.uploadFormAttachment.mockResolvedValue({ fileId: 'att_12345678', fileName: 'a.txt', size: 3 })
    const wrapper = mountForm(ATTACH, { uploadContext: { runId: 'r1', toolCallId: 'c1' } })
    const input = wrapper.find('#gf-field-evidence')
    Object.defineProperty(input.element, 'files', {
      value: [new File(['secret-content'], 'a.txt', { type: 'text/plain' })]
    })
    await input.trigger('change')
    await flushPromises()
    await wrapper.find('[data-testid="gf-submit"]').trigger('click')
    const submitted = JSON.stringify(wrapper.emitted('submit')?.[0]?.[0] ?? {})
    expect(submitted).not.toContain('secret-content')
    expect(submitted).toContain('att_12345678')
  })
})

describe('L-92 同页两卡：字段作用域（轮次唯一 testid / id 不撞车 / 聚焦不串卡）', () => {
  const ROUND2 = 'call-r2'

  const mounted: Array<() => void> = []
  afterEach(() => {
    while (mounted.length > 0) mounted.pop()?.()
  })

  function mountCard(props: Record<string, unknown>) {
    const wrapper = mountForm(SCHEMA, props, { attachTo: document.body })
    mounted.push(() => wrapper.unmount())
    return wrapper
  }

  it('「上一轮只读卡 + 本轮待填卡」：待填卡 testid 带轮次作用域、只读卡另占一套、DOM id 不撞车', () => {
    const round1 = mountCard({ readonly: true })
    const round2 = mountCard({ uploadContext: { runId: 'run-2', toolCallId: ROUND2 } })

    const live = round2.find(`[data-testid="gf-field-${ROUND2}-name"]`)
    expect(live.exists()).toBe(true)
    // 交互卡 id 保持历史形态（`#gf-field-<key>` 的既有用例与外部锚点零变更）
    expect(live.attributes('id')).toBe('gf-field-name')

    const stale = round1.find('[data-testid$="-name"]')
    expect(stale.exists()).toBe(true)
    expect(stale.attributes('id')).not.toBe('gf-field-name')
    expect(stale.attributes('data-testid')).not.toBe(live.attributes('data-testid'))

    // 同页 `#gf-field-name` 只有一个 ⇒ label for / aria-describedby / getElementById 不再取到别卡
    expect(document.querySelectorAll('[id="gf-field-name"]')).toHaveLength(1)
  })

  it('只读回放卡之间也各占一套 id（多轮历史卡不撞车）', () => {
    const first = mountCard({ readonly: true })
    const second = mountCard({ readonly: true })
    const firstId = first.find('[data-testid$="-name"]').attributes('id')
    const secondId = second.find('[data-testid$="-name"]').attributes('id')
    expect(firstId).not.toBe(undefined)
    expect(firstId).not.toBe(secondId)
  })

  it('首错聚焦落在本卡内：同页已有同 key 的只读卡时不串卡', async () => {
    mountCard({ readonly: true })
    const live = mountCard({ uploadContext: { runId: 'run-2', toolCallId: ROUND2 } })

    await live.find('[data-testid="gf-submit"]').trigger('click')
    await flushPromises()

    expect(document.activeElement?.id).toBe('gf-field-name')
    expect(live.element.contains(document.activeElement)).toBe(true)
  })
})

describe('G-G 文案走 locales', () => {
  it('切到 en-US 后 chrome 文案为英文（默认 zh 不变）', async () => {
    const zh = mountForm(CASCADE, { initialValues: { country: 'CN' } })
    expect(zh.text()).toContain('取消')
    expect(zh.text()).toContain('提交')
    expect(zh.text()).toContain('请选择省份')
    applyLocale('en-US')
    const en = mountForm(CASCADE, { initialValues: { country: 'CN' } })
    expect(en.text()).toContain('Cancel')
    expect(en.text()).toContain('Submit')
    expect(en.text()).toContain('Select 省份')
  })
})

/**
 * AR-07（批次4 前端C）：「常用筛选一屏，高级渐进披露，不机械一屏一问」。
 *
 * 产品口径（合并稿 §5 Q6b / 调研-业界对照）：GOV.UK 式一屏一问只适合**线性、低耦合**的服务问答；
 * 筛选条件相互影响，NN/g 明确这类拆分有害。故本仓采用「**常用条件一屏 + 长尾可选条件渐进披露**」。
 *
 * 断言点：
 * ① 常用条件同屏（全部控件同时在场，不存在"下一步"分页）；
 * ② 长尾可选条件默认折叠，一个开关只出现一次，展开后即可填；
 * ③ 必填 / 已填值 / 有错误的字段**永不折叠**（披露只藏没用到的条件，不藏用户的东西）。
 */
describe('AR-07 常用筛选一屏 + 高级条件渐进披露', () => {
  const manyFields = (): FormSpec => ({
    scenario: 'FILTER',
    fields: [
      { key: 'f0', label: '字段0', type: 'text', required: true },
      { key: 'f1', label: '字段1', type: 'text' },
      { key: 'f2', label: '字段2', type: 'text' },
      { key: 'f3', label: '字段3', type: 'text' },
      { key: 'f4', label: '字段4', type: 'text' },
      { key: 'f5', label: '字段5', type: 'text' },
      { key: 'f6', label: '字段6', type: 'text' },
      { key: 'f7', label: '字段7', type: 'text' }
    ]
  })

  /** 尾部那张改成必填：披露不得把必填字段藏起来。 */
  const tailRequired = (): FormSpec => ({
    scenario: 'FILTER',
    fields: [
      { key: 'f0', label: '字段0', type: 'text', required: true },
      { key: 'f1', label: '字段1', type: 'text' },
      { key: 'f2', label: '字段2', type: 'text' },
      { key: 'f3', label: '字段3', type: 'text' },
      { key: 'f4', label: '字段4', type: 'text' },
      { key: 'f5', label: '字段5', type: 'text' },
      { key: 'f6', label: '字段6', type: 'text' },
      { key: 'f7', label: '字段7', type: 'text', required: true }
    ]
  })

  it('常用条件同时在场；长尾可选条件折叠，展开后即可填', async () => {
    const wrapper = mountForm(manyFields())
    // ① 一屏：前 6 张（常用）全部同时渲染 —— 不是一屏一问
    for (const key of ['f0', 'f1', 'f2', 'f3', 'f4', 'f5']) {
      expect(wrapper.find(`#gf-field-${key}`).exists()).toBe(true)
    }
    // ② 长尾可选条件默认折叠
    expect(wrapper.find('#gf-field-f6').exists()).toBe(false)
    expect(wrapper.find('#gf-field-f7').exists()).toBe(false)

    const toggles = wrapper.findAll('[data-testid="gf-advanced-toggle"]')
    expect(toggles).toHaveLength(1)
    expect(toggles[0]?.attributes('aria-expanded')).toBe('false')
    expect(toggles[0]?.text()).toContain('更多条件')

    await toggles[0]?.trigger('click')
    expect(wrapper.find('#gf-field-f6').exists()).toBe(true)
    expect(wrapper.find('#gf-field-f7').exists()).toBe(true)
    expect(wrapper.find('[data-testid="gf-advanced-toggle"]').attributes('aria-expanded')).toBe('true')
  })

  it('必填 / 已填值的高级字段不折叠（不藏用户的东西）', () => {
    const wrapper = mountForm(tailRequired(), { initialValues: { f6: '已经填了' } })
    // 必填不折叠（折叠了表就交不出去）
    expect(wrapper.find('#gf-field-f7').exists()).toBe(true)
    // 有值不折叠（草稿恢复的内容也要看得见）
    expect(wrapper.find('#gf-field-f6').exists()).toBe(true)
    expect((wrapper.find('#gf-field-f6').element as HTMLInputElement).value).toBe('已经填了')
  })

  it('确认型（CLARIFY）不做折叠：确认场景要一眼看全', () => {
    const wrapper = mountForm({ ...manyFields(), scenario: 'CLARIFY' })
    expect(wrapper.find('#gf-field-f7').exists()).toBe(true)
    expect(wrapper.find('[data-testid="gf-advanced-toggle"]').exists()).toBe(false)
  })
})
