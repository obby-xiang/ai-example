/**
 * 手动刷新批（S1）· 任务中心视图的**静止性 + 手动刷新通道**锁（验收口径①）：
 * - 任务中心**不起周期表**：挂载只拉一次列表，此后推进任意时长 GET /api/tasks 仍恰好 1 次；
 * - **零 EventSource 连接**：任务级事件订阅池（utils/task-events.ts）整摘除，页面生命周期内不得开任何连接；
 * - **手动刷新路径**：右上角刷新按钮走 store.refreshNow()（闲置也照拉一次）；
 * - **写操作后本地刷新**：取消任务成功后本地 refreshNow() 一次（不依赖任何周期机制）。
 *
 * 背景：轮询治理一期步1~步3 的条件自刷（hasActiveJob 4s/30s）与任务级 SSE 订阅池
 * 在本批**整体摘除**（M1 + F2）——全静止口径即 E2E 验收「任务中心全静止可见 ≥60s
 * GET /api/tasks 恰好 0 次增量 + 零 EventSource 连接」的单测化身。
 *
 * 挂载方式：Element Plus 组件全部轻量 stub；el-table/el-table-column 用 provide/inject
 * 迭代行（操作列的「取消」按钮才可点击）；ElMessageBox.confirm 与 API 全部桩掉。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, type PropType, type VNode } from 'vue'

const api = vi.hoisted(() => ({ listTasks: vi.fn(), cancelJob: vi.fn() }))
const confirmMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/tasks', () => ({ tasksApi: { listTasks: api.listTasks } }))
vi.mock('@/api/jobs', () => ({ jobsApi: { cancelJob: api.cancelJob } }))
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
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
    ElMessageBox: { confirm: confirmMock }
  }
})

import TaskCenterView from '@/views/TaskCenterView.vue'
import { useTaskStore } from '@/stores/task'
import type { Job, JobStatus } from '@/types/job'
import type { TaskSummary } from '@/types/task'

/** 桩 EventSource：只登记实例、不发帧（任务中心不得再开任何任务级事件连接）。 */
class FakeEventSource {
  static instances: FakeEventSource[] = []
  onopen: (() => void) | null = null
  onerror: ((ev: Event) => void) | null = null
  readonly readyState = 1
  constructor(readonly url: string) {
    FakeEventSource.instances.push(this)
  }
  close(): void {}
}

function makeJob(status: JobStatus, id: number): Job {
  return {
    id,
    taskId: 1,
    jobType: 'IMPORT',
    status,
    progress: 0,
    total: 0,
    errorCount: 0,
    warningCount: 0,
    items: []
  }
}

/** latestJob=null 表示该行尚无作业（后端 TaskSummary 的形态）。 */
function makeSummary(taskId: number, status: JobStatus | null): TaskSummary {
  return {
    task: {
      id: taskId,
      type: 'IMPORT',
      title: `任务 ${taskId}`,
      currentStep: 'UPLOAD',
      status: 'ACTIVE',
      items: []
    },
    itemCount: 0,
    fileCount: 0,
    latestJob: status === null ? null : makeJob(status, taskId * 10)
  }
}

function pageOf(items: TaskSummary[]): { content: TaskSummary[]; totalElements: number } {
  return { content: items, totalElements: items.length }
}

/** el-table 桩：把行集 provide 下去（函数形式，inject 侧渲染时调用 ⇒ 行数据异步到达后列仍迭代得到）；空数据显示 #empty。 */
const TableStub = defineComponent({
  props: { data: { type: Array as unknown as PropType<unknown[]>, default: () => [] } },
  provide(): { elTableRows: () => unknown[] } {
    const vm = this
    return { elTableRows: () => vm.data }
  },
  template: '<div class="table-stub"><slot v-if="!(data && data.length)" name="empty" /><slot v-else /></div>'
})

/** el-table-column 桩：按注入的行集逐行渲染默认作用域插槽（{ row }）。 */
const ColumnStub = defineComponent({
  inject: ['elTableRows'],
  template:
    '<span class="col-stub"><template v-for="(row, i) in elTableRows()" :key="i"><slot :row="row" /></template></span>'
})

const stubs = {
  'el-alert': true,
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
  'el-empty': true
}

beforeEach(() => {
  setActivePinia(createPinia())
  api.listTasks.mockReset()
  api.listTasks.mockResolvedValue(pageOf([]))
  api.cancelJob.mockReset()
  api.cancelJob.mockResolvedValue({ cancelled: true })
  confirmMock.mockReset()
  confirmMock.mockResolvedValue(undefined)
  FakeEventSource.instances = []
  vi.stubGlobal('EventSource', FakeEventSource)
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('views/TaskCenterView.vue · 全静止 + 手动刷新（M1+F2）', () => {
  it('no-periodic-schedule：挂载只拉一次列表；推进 120s 后 GET /api/tasks 仍恰好 1 次，且全程未起 setInterval 周期表', async () => {
    vi.useFakeTimers()
    const intervalSpy = vi.spyOn(globalThis, 'setInterval')
    const wrapper = mount(TaskCenterView, { global: { stubs } })
    await flushPromises()
    expect(api.listTasks).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(120_000) // ≫ E2E 的 60s 静止窗口
    expect(api.listTasks).toHaveBeenCalledTimes(1) // 全静止：零周期请求
    expect(intervalSpy.mock.calls.length).toBe(0) // 任务中心不再起任何周期表

    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(api.listTasks).toHaveBeenCalledTimes(1) // 卸载后同样静默
  })

  it('zero-eventsource：挂载与 120s 静止期间任务级 EventSource 连接数为 0', async () => {
    vi.useFakeTimers()
    const wrapper = mount(TaskCenterView, { global: { stubs } })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(120_000)
    expect(FakeEventSource.instances).toHaveLength(0)
    wrapper.unmount()
    expect(FakeEventSource.instances).toHaveLength(0)
  })

  it('manual-refresh-button：右上角刷新按钮走 store.refreshNow()（闲置也照拉一次）', async () => {
    const wrapper = mount(TaskCenterView, { global: { stubs } })
    await flushPromises()
    expect(api.listTasks).toHaveBeenCalledTimes(1)

    const button = wrapper.find('button[title="刷新"]')
    expect(button.exists()).toBe(true)
    await button.trigger('click')
    await flushPromises()
    expect(api.listTasks).toHaveBeenCalledTimes(2) // refreshNow ⇒ 直拉一次
    wrapper.unmount()
  })

  it('cancel-success-local-refresh：取消任务成功后本地 refreshNow() 一次（不依赖周期表）', async () => {
    api.listTasks.mockResolvedValue(pageOf([makeSummary(1, 'RUNNING')]))
    const wrapper = mount(TaskCenterView, { global: { stubs } })
    await flushPromises()
    expect(api.listTasks).toHaveBeenCalledTimes(1)

    const store = useTaskStore()
    const refreshSpy = vi.spyOn(store, 'refreshNow')
    const cancelButton = wrapper.findAll('button').find((b) => b.text() === '取消')
    expect(cancelButton).toBeDefined()
    await cancelButton!.trigger('click')
    await flushPromises()

    expect(api.cancelJob).toHaveBeenCalledTimes(1)
    expect(refreshSpy).toHaveBeenCalledTimes(1) // 写操作成功后本地刷新（F2 保留面）
    expect(api.listTasks).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  /**
   * 批4-D（用户 2026-10-23 裁决）：取消/删除是**不可撤销**动作 —— 原 `ElMessageBox.confirm`
   * 升级为复选框版（勾选才放行），文案带动作名。
   */
  it('irreversible-cancel：取消任务走复选框对话框（动作化文案 + beforeClose 放行闸）', async () => {
    api.listTasks.mockResolvedValue(pageOf([makeSummary(1, 'RUNNING')]))
    const wrapper = mount(TaskCenterView, { global: { stubs } })
    await flushPromises()

    const cancelButton = wrapper.findAll('button').find((b) => b.text() === '取消')
    await cancelButton!.trigger('click')
    await flushPromises()

    const call = confirmMock.mock.calls.at(-1)!
    const options = call[2] as { beforeClose?: (action: string, instance: unknown, done: () => void) => void }
    expect(options.beforeClose).toBeTypeOf('function')
    const bubble = mount({ render: () => call[0] as VNode })
    expect(bubble.find('[data-testid="irreversible-ack-box"]').exists()).toBe(true)
    expect(bubble.text()).toContain('确认取消作业，执行后无法恢复')
    // 未勾选点「确定」被拦
    const done = vi.fn()
    options.beforeClose!('confirm', {}, done)
    expect(done).not.toHaveBeenCalled()
    bubble.unmount()
    wrapper.unmount()
  })

  it('irreversible-cancel-dismiss：对话框被取消 ⇒ 不发取消请求', async () => {
    api.listTasks.mockResolvedValue(pageOf([makeSummary(1, 'RUNNING')]))
    confirmMock.mockRejectedValueOnce('cancel')
    const wrapper = mount(TaskCenterView, { global: { stubs } })
    await flushPromises()

    const cancelButton = wrapper.findAll('button').find((b) => b.text() === '取消')
    await cancelButton!.trigger('click')
    await flushPromises()
    expect(api.cancelJob).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('irreversible-delete：删除任务走复选框对话框（「确认删除任务，执行后无法恢复」）', async () => {
    api.listTasks.mockResolvedValue(pageOf([makeSummary(1, 'RUNNING')]))
    const wrapper = mount(TaskCenterView, { global: { stubs } })
    await flushPromises()

    const deleteButton = wrapper.findAll('button').find((b) => b.text() === '删除')
    expect(deleteButton).toBeDefined()
    await deleteButton!.trigger('click')
    await flushPromises()

    const call = confirmMock.mock.calls.at(-1)!
    const bubble = mount({ render: () => call[0] as VNode })
    expect(bubble.find('[data-testid="irreversible-ack-box"]').exists()).toBe(true)
    expect(bubble.text()).toContain('确认删除任务，执行后无法恢复')
    bubble.unmount()
    wrapper.unmount()
  })
})
