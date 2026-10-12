/**
 * 轮询治理一期 · 步3（第三个 spec）：任务级订阅的**生命周期出口与 close-gate**（T5/T8 终态）。
 *
 * 两个风险面：
 *  ① **卸载出口**：订阅状态挂 store（Pinia 单例，**跨路由存活**）⇒ 组件 `onBeforeUnmount` 若只停
 *     定时器不管订阅，离开任务中心页后 `EventSource` 仍挂在后台（R3-1 泄漏面）。本 spec 走
 *     **真实挂载/卸载 `TaskCenterView`** 断言计数归 0（跨层显式出口，不依赖组件自动清理）。
 *  ② **close 后迟到帧**：`EventSource.close()` 只保证不再收新帧，**已入队的帧仍可能投递**
 *     （R3-5）⇒ 派发口必须有 close-gate：close 之后的任何帧直接丢弃，**不触发 refreshNow、
 *     不直更行**（覆盖全部 4 类帧，非只挡其一）。
 *
 * 桩件口径同 `task-events.spec.ts`：自写 `EventSource` 桩挂 `globalThis`（`api/tasks.ts:106`
 * 内部 `new EventSource`，非注入），只替换 `tasksApi.listTasks`，`openTaskEvents` 走真实实现。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'

const api = vi.hoisted(() => ({ listTasks: vi.fn() }))

vi.mock('@/api/tasks', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/tasks')>()
  return { ...actual, tasksApi: { ...actual.tasksApi, listTasks: api.listTasks } }
})

import TaskCenterView from '@/views/TaskCenterView.vue'
import { useTaskStore } from '@/stores/task'
import type { Job, JobStatus } from '@/types/job'
import type { PageResult } from '@/types/api'
import type { TaskEventType } from '@/types/sse'
import type { TaskSummary } from '@/types/task'

/** EventSource 自写桩（jsdom 30 无内置）。 */
class EventSourceStub {
  static instances: EventSourceStub[] = []
  static readonly OPEN = 1
  static readonly CONNECTING = 0
  static readonly CLOSED = 2

  static live(): EventSourceStub[] {
    return EventSourceStub.instances.filter((instance) => !instance.closed)
  }

  static liveForTask(taskId: number): EventSourceStub[] {
    return EventSourceStub.live().filter((instance) => instance.taskId() === taskId)
  }

  readyState = EventSourceStub.CONNECTING
  closed = false
  onopen: ((ev: Event) => void) | null = null
  onmessage: ((ev: MessageEvent<string>) => void) | null = null
  onerror: ((ev: Event) => void) | null = null

  constructor(readonly url: string) {
    EventSourceStub.instances.push(this)
  }

  close(): void {
    this.closed = true
    this.readyState = EventSourceStub.CLOSED
  }

  taskId(): number {
    const matched = /\/tasks\/(\d+)\/events/.exec(this.url)
    return matched ? Number(matched[1]) : Number.NaN
  }

  emit(type: TaskEventType, data: unknown = {}): void {
    this.onmessage?.({ data: JSON.stringify({ type, data }) } as MessageEvent<string>)
  }
}

function makeJob(taskId: number, status: JobStatus, jobId = taskId * 10): Job {
  return {
    id: jobId,
    taskId,
    jobType: 'IMPORT',
    status,
    progress: 0,
    total: 0,
    errorCount: 0,
    warningCount: 0,
    items: []
  }
}

function makeSummary(taskId: number, status: JobStatus): TaskSummary {
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
    latestJob: makeJob(taskId, status)
  }
}

function pageOf(items: TaskSummary[]): PageResult<TaskSummary> {
  return {
    content: items,
    totalElements: items.length,
    totalPages: items.length > 0 ? 1 : 0,
    number: 0,
    size: items.length,
    numberOfElements: items.length,
    first: true,
    last: true,
    empty: items.length === 0
  }
}

async function flush(): Promise<void> {
  for (let i = 0; i < 4; i += 1) {
    await vi.advanceTimersByTimeAsync(0)
  }
}

function calls(): number {
  return api.listTasks.mock.calls.length
}

function liveSource(taskId: number): EventSourceStub {
  const source = EventSourceStub.liveForTask(taskId)[0]
  if (!source) {
    throw new Error(`任务 #${taskId} 无存活 EventSource 桩`)
  }
  return source
}

let store: ReturnType<typeof useTaskStore>

/** 真实挂载任务中心页（Element Plus 组件与 v-loading 指令用轻量桩屏蔽，只走生命周期）。 */
function mountTaskCenter(): { wrapper: ReturnType<typeof mount>; pageStore: ReturnType<typeof useTaskStore> } {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', name: 'task-center', component: { template: '<div />' } }]
  })
  const wrapper = mount(TaskCenterView, {
    global: {
      plugins: [pinia, router],
      directives: { loading: {} },
      stubs: {
        'el-alert': true,
        'el-button': true,
        'el-dialog': true,
        'el-empty': true,
        'el-form': true,
        'el-form-item': true,
        'el-input': true,
        'el-option': true,
        'el-pagination': true,
        'el-progress': true,
        'el-radio': true,
        'el-radio-group': true,
        'el-select': true,
        'el-table': true,
        'el-table-column': true,
        'el-tag': true
      }
    }
  })
  // 组件用的是挂载时那个 pinia 的 store 实例（订阅状态就在它身上）
  return { wrapper, pageStore: useTaskStore() }
}

beforeEach(() => {
  setActivePinia(createPinia())
  store = useTaskStore()
  EventSourceStub.instances = []
  ;(globalThis as unknown as { EventSource: unknown }).EventSource = EventSourceStub
  api.listTasks.mockReset()
  api.listTasks.mockResolvedValue(pageOf([]))
  vi.useFakeTimers()
})

afterEach(() => {
  store.closeTaskEvents()
  store.stopAutoRefresh()
  vi.useRealTimers()
  delete (globalThis as unknown as { EventSource?: unknown }).EventSource
})

describe('任务中心订阅池 · 生命周期出口', () => {
  it('unmount-closes-all-eventsources：走 TaskCenterView 卸载路径（显式 store action）后 EventSource 计数归 0', async () => {
    api.listTasks.mockResolvedValue(pageOf([makeSummary(11, 'RUNNING'), makeSummary(12, 'RUNNING')]))
    const { wrapper, pageStore } = mountTaskCenter()
    await flush()

    expect(EventSourceStub.live()).toHaveLength(2)
    expect(pageStore.taskEventSubscriptionCount).toBe(2)

    wrapper.unmount()

    expect(EventSourceStub.live()).toHaveLength(0)
    expect(pageStore.taskEventSubscriptionCount).toBe(0) // 跨层显式出口：只有 closeTaskEvents 会归零
    expect(EventSourceStub.instances).toHaveLength(2)
    expect(EventSourceStub.instances.every((instance) => instance.closed)).toBe(true)
    expect(vi.getTimerCount()).toBe(0) // F7③：卸载出口连定时器计数也归 0（interval 与 visibilitychange 一并卸载）
  })

  it('late-frame-after-close-ignored：close 后投递迟到帧不触发 refreshNow（且不误伤仍在订的通道）', async () => {
    api.listTasks.mockImplementation(() => Promise.resolve(pageOf([makeSummary(2, 'RUNNING')])))
    store.items = [makeSummary(1, 'RUNNING'), makeSummary(2, 'RUNNING')]
    store.startTaskEvents()
    await flush()

    const stale = liveSource(1)
    // 出口：任务 1 退出在跑集合（订阅被回收）
    store.items = [makeSummary(2, 'RUNNING')]
    store.syncTaskEvents()
    await flush()
    expect(stale.closed).toBe(true)
    expect(store.taskEventSubscriptionCount).toBe(1)

    const before = calls()
    stale.emit('JOB_DONE', { jobId: 10, status: 'COMPLETED' })
    stale.emit('TASK_CHANGED', { taskId: 1, version: 2, currentStep: 'UPLOAD', status: 'ACTIVE', summary: 'x' })
    await flush()
    expect(calls()).toBe(before) // close-gate：迟到帧丢弃、不刷新

    // 正向对照：仍在订的通道照常工作
    liveSource(2).emit('JOB_DONE', { jobId: 20, status: 'COMPLETED' })
    await flush()
    expect(calls()).toBe(before + 1)
  })

  it('close-gate-covers-dispatch：4 类帧（JOB_PROGRESS/JOB_DONE/TASK_CHANGED/HEARTBEAT）在 close 后全部被丢弃', async () => {
    api.listTasks.mockImplementation(() => Promise.resolve(pageOf([makeSummary(1, 'RUNNING')])))
    store.items = [makeSummary(1, 'RUNNING')]
    store.startTaskEvents()
    await flush()
    const source = liveSource(1)

    // 正向对照①：JOB_PROGRESS 直更对应行 latestJob.progress/total
    source.emit('JOB_PROGRESS', { jobId: 10, taskId: 1, jobType: 'IMPORT', defCode: 'D', processed: 5, total: 10, pct: 50 })
    expect(store.items[0]?.latestJob?.progress).toBe(5)
    expect(store.items[0]?.latestJob?.total).toBe(10)

    // 正向对照②：JOB_DONE / TASK_CHANGED 各触发一次刷新
    const beforeClose = calls()
    source.emit('JOB_DONE', { jobId: 10, status: 'RUNNING' })
    await flush()
    expect(calls()).toBe(beforeClose + 1)
    source.emit('TASK_CHANGED', { taskId: 1, version: 2, currentStep: 'UPLOAD', status: 'ACTIVE', summary: 'x' })
    await flush()
    expect(calls()).toBe(beforeClose + 2)

    // close（卸载出口）
    store.closeTaskEvents()
    expect(EventSourceStub.live()).toHaveLength(0)
    const afterClose = calls()
    const progressBefore = store.items[0]?.latestJob?.progress ?? -1

    // ① JOB_PROGRESS：不直更
    source.emit('JOB_PROGRESS', { jobId: 10, taskId: 1, jobType: 'IMPORT', defCode: 'D', processed: 9, total: 10, pct: 90 })
    await flush()
    expect(store.items[0]?.latestJob?.progress).toBe(progressBefore)
    expect(store.items[0]?.latestJob?.progress).not.toBe(9)

    // ② JOB_DONE：不 refreshNow
    source.emit('JOB_DONE', { jobId: 10, status: 'COMPLETED' })
    await flush()
    expect(calls()).toBe(afterClose)

    // ③ TASK_CHANGED：不 refreshNow
    source.emit('TASK_CHANGED', { taskId: 1, version: 3, currentStep: 'EXPORT', status: 'ACTIVE', summary: 'y' })
    await flush()
    expect(calls()).toBe(afterClose)

    // ④ HEARTBEAT：本就无业务动作（close 前后都不产生动作）
    source.emit('HEARTBEAT', {})
    await flush()
    expect(calls()).toBe(afterClose)
    expect(store.items[0]?.latestJob?.progress).toBe(progressBefore)
  })
})
