/**
 * 数据浏览页 · 行数预估（`/count`）的**计数乱序**（last-wins 单点）。
 *
 * 现场：`previewCount` 把响应无条件写进 `serverCount` —— 点完「预估」再改条件（或换定义/范围），
 * 在途那次计数回来时会把**旧条件**的数字贴到新条件下，并弹一条"服务端口径命中 N 行"；
 * 失败同理，旧条件的报错弹在当前条件上。修法与列表（`loadData`）同类但不共享序号：
 * 计数有独立的 `countSeq`，落地前还要核对**发起时的入参身份**（defCode / 范围 / 条件），
 * 两者缺一不可（只比序号会漏"改条件后旧计数回来"，只比入参会漏"同参连点、先发后到"）。
 *
 * 反例（RED）：去掉 `countSeq` 或 `stale()` 的入参比对 ⇒ 下面的断言必红。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h } from 'vue'
import type { ConditionDraftModel } from '@/types/condition'

const api = vi.hoisted(() => ({
  listDefinitions: vi.fn(),
  listData: vi.fn(),
  countData: vi.fn(),
  listRegions: vi.fn(),
  listProjects: vi.fn()
}))

const toast = vi.hoisted(() => ({ success: vi.fn(), warning: vi.fn(), error: vi.fn() }))

vi.mock('@/api/definitions', () => ({ definitionsApi: { listDefinitions: api.listDefinitions } }))
vi.mock('@/api/data', () => ({ dataApi: { listData: api.listData, countData: api.countData } }))
vi.mock('@/api/masterdata', () => ({
  masterDataApi: { listRegions: api.listRegions, listProjects: api.listProjects }
}))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }))
vi.mock('@/router', () => ({
  ROUTE_NAMES: {
    taskCenter: 'task-center',
    exportWizard: 'export-wizard',
    importWizard: 'import-wizard',
    definitions: 'definitions',
    dataBrowser: 'data-browser'
  },
  default: { push: vi.fn() }
}))
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return { ...actual, ElMessage: toast }
})

/** 条件表单桩：点一下就把"改了一个字段"的草案抛回页面（与真表单同一条 `update:model-value`）。 */
const CHANGED_MODEL: ConditionDraftModel = {
  scopeKeys: [],
  fields: { code: { operator: 'EQ', value: 'X', value2: '' } }
}

vi.mock('@/components/wizard/ConditionForm.vue', async () => {
  const { defineComponent: define, h: create } = await import('vue')
  return {
    default: define({
      name: 'ConditionFormStub',
      emits: ['update:model-value'],
      setup(_props, { emit }) {
        return () => create('button', {
          class: 'cf-change',
          onClick: () => emit('update:model-value', {
            scopeKeys: [...CHANGED_MODEL.scopeKeys],
            fields: { ...CHANGED_MODEL.fields }
          })
        })
      }
    })
  }
})

import DataBrowserView from '@/views/DataBrowserView.vue'

/** 空渲染的 Element Plus 占位（真组件在无全局注册的测试环境里会把 scoped slot 无参调用）。 */
const NullComponent = defineComponent({ name: 'NullComponent', setup: () => () => null })

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void; reject: (reason: unknown) => void } {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function mountView(): ReturnType<typeof mount> {
  return mount(DataBrowserView, {
    global: {
      components: {
        'el-table': NullComponent,
        'el-table-column': NullComponent,
        'el-descriptions': NullComponent,
        'el-descriptions-item': NullComponent
      }
    }
  })
}

/** 「预估命中行数」按钮（未注册的 `el-button` 以同名元素渲染，文本即 locale 文案）。 */
function estimateButton(wrapper: ReturnType<typeof mount>) {
  const button = wrapper.findAll('el-button').find((node) => node.text().includes('预估命中行数'))
  expect(button, '页面上应有「预估命中行数」按钮').toBeDefined()
  return button!
}

/** 服务端口径命中行的 el-tag（`v-if="serverCount !== null"`，是 serverCount 的唯一 DOM 面）。 */
function countTag(wrapper: ReturnType<typeof mount>): string {
  const tag = wrapper.find('el-tag')
  return tag.exists() ? tag.text() : ''
}

beforeEach(() => {
  setActivePinia(createPinia())
  Object.values(api).forEach((fn) => fn.mockReset())
  Object.values(toast).forEach((fn) => fn.mockReset())
  api.listDefinitions.mockResolvedValue([{ code: 'DEMO', name: '演示配置', level: 'GLOBAL', fields: [] }])
  api.listData.mockResolvedValue({ content: [], totalElements: 0 })
  api.listRegions.mockResolvedValue([])
  api.listProjects.mockResolvedValue([])
})

describe('previewCount 计数乱序：旧响应/旧错误不得覆盖新条件', () => {
  it('先发的计数后到 ⇒ 不覆盖后发计数，也不弹提示', async () => {
    const first = deferred<{ count: number }>()
    const second = deferred<{ count: number }>()
    api.countData.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)

    const wrapper = mountView()
    await flushPromises()

    await estimateButton(wrapper).trigger('click')
    await flushPromises()
    expect(api.countData).toHaveBeenCalledTimes(1)

    // 计数在途时改条件（旧实现的响应会把旧条件的数字贴到新条件下）
    await wrapper.find('.cf-change').trigger('click')
    await flushPromises()

    await estimateButton(wrapper).trigger('click')
    await flushPromises()
    expect(api.countData).toHaveBeenCalledTimes(2)

    // 后发先到：新条件的结论落地
    second.resolve({ count: 222 })
    await flushPromises()
    expect(countTag(wrapper)).toContain('222')

    // 先发后到：旧条件的结论不得覆盖
    first.resolve({ count: 111 })
    await flushPromises()
    expect(countTag(wrapper)).toContain('222')
    expect(countTag(wrapper)).not.toContain('111')
    expect(toast.success).toHaveBeenCalledTimes(1)
    expect(toast.error).not.toHaveBeenCalled()
  })

  it('旧计数的失败不得弹在当前条件上', async () => {
    const first = deferred<{ count: number }>()
    api.countData.mockReturnValueOnce(first.promise)

    const wrapper = mountView()
    await flushPromises()

    await estimateButton(wrapper).trigger('click')
    await flushPromises()

    // 请求在途时改条件 ⇒ 这次计数的入参已不是当前条件
    await wrapper.find('.cf-change').trigger('click')
    await flushPromises()

    first.reject(new Error('boom'))
    await flushPromises()

    expect(toast.error).not.toHaveBeenCalled()
    expect(countTag(wrapper)).toBe('')
  })
})
