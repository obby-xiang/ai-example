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

const api = vi.hoisted(() => ({ listTasks: vi.fn(), deleteTask: vi.fn(), cancelJob: vi.fn() }))
const confirmMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/tasks', () => ({ tasksApi: { listTasks: api.listTasks, deleteTask: api.deleteTask } }))
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

import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/http'
import TaskCenterView from '@/views/TaskCenterView.vue'
import { useTaskStore } from '@/stores/task'
import { emitWorkspaceEvent } from '@/stores/workspace'
import type { Job, JobStatus } from '@/types/job'
import type { TaskSummary } from '@/types/task'

/** jsdom 的 `document.visibilityState` 只读（原型 getter）⇒ 覆写为自身访问器属性。 */
function setDocumentVisibility(state: 'visible' | 'hidden'): void {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state })
  document.dispatchEvent(new Event('visibilitychange'))
}

function restoreDocumentVisibility(): void {
  delete (document as unknown as { visibilityState?: string }).visibilityState
}

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

/**
 * 筛选控件/分页用**原生控件形态**的桩：草稿与已应用筛选的分离面要能被测试驱动
 * （改下拉 ≠ 发请求；翻页/刷新带的是哪一份筛选）。
 */
const stubs = {
  'el-alert': true,
  'el-button': { template: '<button v-bind="$attrs"><slot /></button>' },
  'el-input': {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input class="ctl-input" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
  },
  'el-select': {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<select class="ctl-select" :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>'
  },
  'el-option': { props: ['label', 'value'], template: '<option :value="value">{{ label }}</option>' },
  'el-table': TableStub,
  'el-table-column': ColumnStub,
  'el-tag': { template: '<span><slot /></span>' },
  'el-progress': true,
  'el-pagination': {
    props: ['currentPage', 'pageSize'],
    emits: ['current-change', 'size-change'],
    template: '<div class="pager-stub"><button class="pager-next" @click="$emit(\'current-change\', currentPage + 1)">next</button></div>'
  },
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
  api.deleteTask.mockReset()
  api.cancelJob.mockReset()
  api.cancelJob.mockResolvedValue({ cancelled: true })
  vi.mocked(ElMessage.error).mockReset()
  confirmMock.mockReset()
  confirmMock.mockResolvedValue(undefined)
  FakeEventSource.instances = []
  vi.stubGlobal('EventSource', FakeEventSource)
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
  restoreDocumentVisibility()
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

  /**
   * 筛选源单一路径（review-07 P1-5）：控件是**草稿**，改动本身不发请求；
   * 查询 / 翻页 / 刷新三个入口都必须以同一份「控件当前值」拉取 ——
   * 不允许出现「下拉显示已完成、列表却按全量翻页」的错位（旧实现翻页/刷新读的是 store 旧筛选）。
   */
  it('filter-draft-and-applied-source：草稿与已应用筛选分离，查询/翻页/刷新同源', async () => {
    api.listTasks.mockResolvedValue(pageOf([makeSummary(1, 'RUNNING')]))
    const wrapper = mount(TaskCenterView, { global: { stubs } })
    await flushPromises()
    expect(api.listTasks).toHaveBeenLastCalledWith({ page: 0, size: 10 })

    // 只改控件（状态=已完成）、不点查询 ⇒ 草稿生效前不发任何请求
    const statusSelect = wrapper.findAll('select')[1]
    await statusSelect.setValue('COMPLETED')
    await flushPromises()
    expect(api.listTasks).toHaveBeenCalledTimes(1)

    // 翻页：带控件当前筛选（不是 store 里的旧筛选）
    await wrapper.find('.pager-next').trigger('click')
    await flushPromises()
    expect(api.listTasks).toHaveBeenLastCalledWith({ page: 1, size: 10, status: 'COMPLETED' })

    // 刷新：与翻页同一份筛选（页保持不动，筛选不被旧值顶回）
    await wrapper.find('button[title="刷新"]').trigger('click')
    await flushPromises()
    expect(api.listTasks).toHaveBeenLastCalledWith({ page: 1, size: 10, status: 'COMPLETED' })

    // 查询：同一份筛选 + 回到第一页（三个入口同源）
    await wrapper.findAll('button').find((b) => b.text() === '查询')!.trigger('click')
    await flushPromises()
    expect(api.listTasks).toHaveBeenLastCalledWith({ page: 0, size: 10, status: 'COMPLETED' })
    expect(api.listTasks).toHaveBeenCalledTimes(4)

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

  it('delete-blocked-by-in-flight-job：409 在途作业提示原样展示为错误消息，且不刷新列表', async () => {
    api.listTasks.mockResolvedValue(pageOf([makeSummary(1, 'RUNNING')]))
    const message = '任务 #1 仍有未结束作业：作业 #10 [EXPORT] 状态 RUNNING（JOB_ALREADY_RUNNING），'
      + '暂时无法删除；删除任务会级联清理作业/暂存/文件，请等待作业结束或先取消作业后再删除'
    api.deleteTask.mockRejectedValueOnce(new ApiError(message, 409, 'JOB_ALREADY_RUNNING', null))

    const wrapper = mount(TaskCenterView, {
      global: {
        stubs: {
          ...stubs,
          'el-alert': {
            props: ['title'],
            template: '<div class="alert-render">{{ title }}</div>'
          }
        }
      }
    })
    await flushPromises()

    const deleteButton = wrapper.findAll('button').find((b) => b.text() === '删除')
    expect(deleteButton).toBeDefined()
    await deleteButton!.trigger('click')
    await flushPromises()

    expect(api.deleteTask).toHaveBeenCalledWith(1)
    expect(ElMessage.error).toHaveBeenCalledWith(message)
    expect(useTaskStore().actionError).toBe(message)
    // 409 是写操作错误，不得复用列表加载错误字段（否则顶部出现“任务列表加载失败”横幅）
    expect(useTaskStore().loadError).toBeNull()
    expect(wrapper.find('.alert-render').exists()).toBe(false)
    expect(api.listTasks).toHaveBeenCalledTimes(1) // 失败不触发成功后刷新
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

  /**
   * AR-08（批次 5B 聚焦用例）：任务中心刷新的**三条件**。
   *
   * ①AI 工具事件后 ②存在**非终态**作业（只认 PENDING/RUNNING，与 AR-16 同族）③页面可见
   * —— 三者同时成立才刷一次；隐藏期不刷（回可见补刷一次，不补偿后台错过的次数）；
   * 无在途作业时事件**不触发**刷新（不是轮询、也不空转）。同时锁"数据截至 HH:mm:ss"：
   * 让「没刷新」与「没有变化」可区分。
   */
  it('ar08-event-inflight-visible：事件后 + 在途 + 可见才刷新，并显示数据截至时刻', async () => {
    api.listTasks.mockResolvedValue(pageOf([makeSummary(1, 'RUNNING'), makeSummary(2, 'COMPLETED')]))
    const wrapper = mount(TaskCenterView, { global: { stubs } })
    await flushPromises()
    expect(api.listTasks).toHaveBeenCalledTimes(1)

    // ①+②+③ 同时成立（AI 经 ui_event 起了作业）⇒ 恰好刷一次
    emitWorkspaceEvent('ui_event_result', { event: 'run_job', handled: true, message: '作业已启动：EXPORT（作业 #10）' })
    await flushPromises()
    expect(api.listTasks).toHaveBeenCalledTimes(2)

    // 数据截至时刻可见（「没刷新」与「没有变化」可区分）
    expect(wrapper.text()).toMatch(/数据截至\s*\d{2}:\d{2}:\d{2}/)

    // ③ 不成立（页面隐藏）⇒ 不刷
    setDocumentVisibility('hidden')
    emitWorkspaceEvent('ui_event_result', { event: 'cancel_job', handled: true, message: '已请求取消作业 #10' })
    await flushPromises()
    expect(api.listTasks).toHaveBeenCalledTimes(2)

    // 回可见：隐藏期收到的那次事件补刷恰好一次（仍不是轮询）
    setDocumentVisibility('visible')
    await flushPromises()
    expect(api.listTasks).toHaveBeenCalledTimes(3)

    // ② 不成立（列表里已无在途作业）⇒ 事件不触发刷新（不空转）
    api.listTasks.mockResolvedValue(pageOf([makeSummary(2, 'COMPLETED')]))
    await wrapper.find('button[title="刷新"]').trigger('click')
    await flushPromises()
    expect(api.listTasks).toHaveBeenCalledTimes(4)
    emitWorkspaceEvent('ui_event_result', { event: 'cancel_job', handled: true, message: '已请求取消作业 #10' })
    await flushPromises()
    expect(api.listTasks).toHaveBeenCalledTimes(4)

    wrapper.unmount()
    restoreDocumentVisibility()
  })

  /** AR-08 第 4 条：事件驱动刷新失败必须进错误位（不静默），且不清空已有列表。 */
  it('ar08-event-refresh-failure-visible：事件驱动刷新失败 ⇒ 错误横幅可见、列表不清空', async () => {
    api.listTasks.mockResolvedValue(pageOf([makeSummary(1, 'RUNNING')]))
    const wrapper = mount(TaskCenterView, {
      global: {
        stubs: {
          ...stubs,
          'el-alert': { props: ['title'], template: '<div class="alert-render">{{ title }}</div>' }
        }
      }
    })
    await flushPromises()
    expect(api.listTasks).toHaveBeenCalledTimes(1)

    api.listTasks.mockRejectedValueOnce(new Error('网络中断'))
    emitWorkspaceEvent('ui_event_result', { event: 'run_job', handled: true, message: '作业已启动：EXPORT（作业 #10）' })
    await flushPromises()

    expect(api.listTasks).toHaveBeenCalledTimes(2)
    expect(useTaskStore().loadError).toContain('网络中断')
    expect(wrapper.find('.alert-render').text()).toContain('任务列表加载失败')
    expect(wrapper.text()).toContain('任务 1') // 失败不改写已有列表
    wrapper.unmount()
  })
})
