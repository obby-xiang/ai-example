/**
 * 测试扩充 V4 · C5 空态矩阵（页面层两格）：无历史（AI 反馈记录）/ 无任务（任务中心）。
 *
 * <p>为什么单独成文件：这两格要挂**页面组件**（不是在面板里），装配面比面板大
 * （Element Plus 表族 stub + store 的 API 桩），与 `empty-panel-matrix.spec.ts` 的
 * 面板桩混在一起会把两套 mock 工厂搅成一团。
 *
 * <p>既有覆盖对照：`resultview-renderer.spec.ts` 锁的是**视图块**（`resultView` 的 empty 文案），
 * 不是页面；`task-center-view.spec.ts` 锁的是**静止性与手动刷新**，从不渲染空列表形态。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, type PropType } from 'vue'
import { useWorkspaceStore } from '@/stores/workspace'

const api = vi.hoisted(() => ({
  feedbackList: vi.fn(),
  listTasks: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: { feedbackList: api.feedbackList } }))
vi.mock('@/api/tasks', () => ({ tasksApi: { listTasks: api.listTasks } }))
vi.mock('@/api/jobs', () => ({ jobsApi: { cancelJob: vi.fn() } }))
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
  ElMessageBox: { confirm: vi.fn() }
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/router', () => ({
  default: { push: vi.fn() },
  ROUTE_NAMES: {
    taskCenter: 'tasks',
    exportWizard: 'export',
    importWizard: 'import',
    definitions: 'definitions',
    dataBrowser: 'data'
  }
}))

/** el-table 桩：空数据时渲染 `#empty` 插槽（本文件只关心空态）。 */
const TableStub = defineComponent({
  props: { data: { type: Array as unknown as PropType<unknown[]>, default: () => [] } },
  template: '<div class="table-stub"><slot v-if="!(data && data.length)" name="empty" /><slot v-else /></div>'
})

const ColumnStub = defineComponent({ template: '<span class="col-stub"><slot /></span>' })

const viewStubs = {
  'el-alert': { props: ['title'], template: '<div class="alert-stub">{{ title }}</div>' },
  'el-button': { template: '<button v-bind="$attrs"><slot /></button>' },
  'el-input': true,
  'el-select': true,
  'el-option': true,
  'el-table': TableStub,
  'el-table-column': ColumnStub,
  'el-tag': { template: '<span><slot /></span>' },
  'el-progress': true,
  'el-pagination': { template: '<div class="pager-stub" />' },
  'el-dialog': { template: '<div><slot /></div>' },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<div><slot /></div>' },
  'el-radio-group': { template: '<div><slot /></div>' },
  'el-radio': { template: '<label><slot /></label>' },
  'el-empty': { props: ['description'], template: '<div class="empty-stub">{{ description }}</div>' }
}

beforeEach(() => {
  setActivePinia(createPinia())
  api.feedbackList.mockReset()
  api.listTasks.mockReset()
})

describe('C5-02 无历史：AI 反馈页在零记录时给空态，不留加载/错误残影', () => {
  it('零条记录 ⇒ el-empty「暂无反馈记录」，表格与错误条都不出现', async () => {
    api.feedbackList.mockResolvedValue([])
    const { default: AiFeedbackView } = await import('@/views/AiFeedbackView.vue')
    const wrapper = mount(AiFeedbackView, { global: { stubs: viewStubs } })
    await flushPromises()

    expect(api.feedbackList).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="ai-feedback-view"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="ai-feedback-table"]').exists()).toBe(false)
    expect(wrapper.find('.empty-stub').text()).toBe('暂无反馈记录')
    expect(wrapper.find('.alert-stub').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('加载中')
    expect(wrapper.findAll('[data-testid="ai-feedback-row"]')).toHaveLength(0)
    wrapper.unmount()
  })

  it('加载失败走错误条而不是空态（空态不掩盖故障）', async () => {
    api.feedbackList.mockRejectedValue(new Error('boom'))
    const { default: AiFeedbackView } = await import('@/views/AiFeedbackView.vue')
    const wrapper = mount(AiFeedbackView, { global: { stubs: viewStubs } })
    await flushPromises()

    expect(wrapper.find('.empty-stub').exists()).toBe(false)
    expect(wrapper.find('.alert-stub').text()).toContain('boom')
    wrapper.unmount()
  })
})

describe('C5-07 无任务：任务中心零行时给行动提示，表体不崩', () => {
  it('零条任务 ⇒ 表体渲「暂无任务，点击右上角创建」，零数据行、零错误条', async () => {
    api.listTasks.mockResolvedValue({ content: [], totalElements: 0 })
    const { default: TaskCenterView } = await import('@/views/TaskCenterView.vue')
    const wrapper = mount(TaskCenterView, { global: { stubs: viewStubs } })
    await flushPromises()

    expect(api.listTasks).toHaveBeenCalledTimes(1)
    expect(wrapper.find('h2').text()).toBe('任务中心')
    expect(wrapper.find('.table-stub').exists()).toBe(true)
    expect(wrapper.find('.empty-stub').text()).toBe('暂无任务，点击右上角创建')
    expect(wrapper.findAll('.col-stub')).toHaveLength(0)
    expect(wrapper.find('.alert-stub').exists()).toBe(false)
    // 空表不是死界面：创建入口仍在
    expect(wrapper.text()).toContain('创建任务')
    wrapper.unmount()
  })
})

describe('批次5A 设置类页面：AI 反馈进页即声明/复位工作区（与 AI 设置同口径）', () => {
  it('从导出向导（任务 7，已取消）进入反馈页 ⇒ 复位到默认页、任务态全中性', async () => {
    const workspace = useWorkspaceStore()
    workspace.enterPage({
      pageId: 'export',
      page: 'export',
      taskType: 'EXPORT',
      step: 'QUERY_COND',
      taskId: 7,
      taskStatus: 'CANCELLED',
      selectedDefs: ['CURRENCY'],
      queryConditions: { CURRENCY: {} },
      importMode: 'MERGE'
    })
    api.feedbackList.mockResolvedValue([])

    const { default: AiFeedbackView } = await import('@/views/AiFeedbackView.vue')
    const wrapper = mount(AiFeedbackView, { global: { stubs: viewStubs } })
    await flushPromises()

    // 设置类页面不上报 settings（后端 page 闭集）⇒ 复位到默认页 tasks
    expect(workspace.pageId).toBe('tasks')
    expect(workspace.page).toBe('tasks')
    expect(workspace.taskType).toBeNull()
    expect(workspace.step).toBeNull()
    expect(workspace.taskId).toBeNull()
    expect(workspace.taskStatus).toBeNull()
    expect(workspace.selectedDefs).toEqual([])
    expect(workspace.queryConditions).toEqual({})
    expect(workspace.importMode).toBeNull()
    // 页面本身照常加载（声明不影响数据面）
    expect(api.feedbackList).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})
