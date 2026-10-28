/**
 * 批次5A · 数据浏览页的页面上下文声明。
 *
 * 现场（审查 §5-4）：数据页只声明 `taskType/step`，不清 `taskId / taskStatus / queryConditions /
 * importMode` ⇒ 从导出/导入向导点进数据页后，下一轮上下文仍带着**别的任务**的终态与绑定。
 * 后果不是观感问题：`taskStatus=CANCELLED` 会让契约层只读守卫把数据页相关的写事件当
 * 「任务已结束」误拒。
 *
 * 同时锁「不扩大清空」：数据页**拥有**「正在浏览哪个定义」这一项（`selectedDefs`），
 * 进页复位后必须是本页刚选中的定义，而不是被顺手清成空。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent } from 'vue'

const api = vi.hoisted(() => ({
  listDefinitions: vi.fn(),
  listData: vi.fn(),
  countData: vi.fn(),
  listRegions: vi.fn(),
  listProjects: vi.fn()
}))

vi.mock('@/api/definitions', () => ({ definitionsApi: { listDefinitions: api.listDefinitions } }))
vi.mock('@/api/data', () => ({ dataApi: { listData: api.listData, countData: api.countData } }))
vi.mock('@/api/masterdata', () => ({
  masterDataApi: { listRegions: api.listRegions, listProjects: api.listProjects }
}))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }))
// `@/stores/workspace` 会 import 真实 router（PAGE_ROUTES 用 ROUTE_NAMES）⇒ vue-router 被换掉后
// 它不能在模块初始化期真的 createRouter（与 a10 恢复路径同一处理）。
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
vi.mock('@/components/wizard/ConditionForm.vue', () => ({
  default: defineComponent({ name: 'ConditionForm', template: '<div />' })
}))
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() }
  }
})

import DataBrowserView from '@/views/DataBrowserView.vue'
import { useWorkspaceStore } from '@/stores/workspace'
import type { ConfigDataRow } from '@/types/data'

/**
 * `el-table-column` 的段 stub：**按列作用域插槽的真实形态**投一个 row。
 *
 * 缺省 stub（以及未解析组件按原生元素处理）调用默认插槽时不传参，而模板里三处列插槽都是
 * `#default="{ row }"`（DataBrowserView.vue:76/98/107/116）⇒ 解构 `undefined` 直接崩。
 * 这里只补插槽入参，不改模板，也不把 Element Plus 整包拉进 jsdom。
 */
const ElTableColumnStub = defineComponent({
  name: 'ElTableColumn',
  props: { type: String, prop: String, label: String },
  setup(_props, { slots }) {
    const row: ConfigDataRow = {
      id: 1,
      defCode: 'DEMO',
      scopeType: 'GLOBAL',
      scopeKey: null,
      rowKey: 'DEMO:GLOBAL',
      dataJson: '{}',
      version: 1,
      updatedAt: '2026-01-01T00:00:00'
    }
    return () => (slots.default ? slots.default({ row }) : null)
  }
})

beforeEach(() => {
  setActivePinia(createPinia())
  api.listDefinitions.mockReset()
  api.listData.mockReset()
  api.countData.mockReset()
  api.listRegions.mockReset()
  api.listProjects.mockReset()
  api.listDefinitions.mockResolvedValue([{ code: 'DEMO', name: '演示配置', level: 'GLOBAL', fields: [] }])
  api.listData.mockResolvedValue({ content: [], totalElements: 0 })
  api.listRegions.mockResolvedValue([])
  api.listProjects.mockResolvedValue([])
})

describe('批次5A 数据页：进页即复位任务态，保留本页声明', () => {
  it('从导出向导（任务 7，已取消）进入数据页 ⇒ 任务态复位，选中集是正在浏览的定义', async () => {
    const workspace = useWorkspaceStore()
    workspace.enterPage({
      pageId: 'export',
      page: 'export',
      taskType: 'EXPORT',
      step: 'QUERY_COND',
      taskId: 7,
      taskStatus: 'CANCELLED',
      selectedDefs: ['CURRENCY'],
      queryConditions: { CURRENCY: { fields: {} } },
      importMode: 'MERGE'
    })

    const wrapper = shallowMount(DataBrowserView, {
      global: {
        // 解析 + stub 同一对象：仅注册不 stub 会渲染整个列组件；仅 stub 不注册则标签未解析，
        // 插槽仍被当原生 children 无参调用（原崩溃形态）。
        components: { ElTableColumn: ElTableColumnStub },
        stubs: { ElTableColumn: ElTableColumnStub }
      }
    })
    await flushPromises()

    expect(workspace.pageId).toBe('data')
    expect(workspace.page).toBe('data')
    expect(workspace.taskType).toBeNull()
    expect(workspace.step).toBeNull()
    expect(workspace.taskId).toBeNull()
    expect(workspace.taskStatus).toBeNull()
    expect(workspace.importMode).toBeNull()
    expect(workspace.queryConditions).toEqual({})
    // 本页声明保留：正在浏览的定义（不是被顺手清空）
    expect(workspace.selectedDefs).toEqual(['DEMO'])
    // 批次4 不回退：顶层仍上报 taskStatus（值 null）
    expect(workspace.buildContext().taskStatus).toBeNull()

    wrapper.unmount()
  })
})
