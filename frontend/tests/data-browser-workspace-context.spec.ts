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

    const wrapper = shallowMount(DataBrowserView)
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
