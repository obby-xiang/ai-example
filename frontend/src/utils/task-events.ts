/**
 * 轮询治理一期 · 步3：**任务中心的任务级事件订阅池**（`GET /api/tasks/{id}/events`）。
 *
 * 为什么要有它：列表此前只有定时轮询 ⇒ `TASK_CHANGED` / `JOB_DONE` 这类事件**零消费**
 * （别处改了任务状态，要等下一个 4s/30s tick 才看得到）；可"给当前页每个在跑任务都开一条 SSE"
 * 又会把连接数推成 O(在跑任务数)，翻页/筛选/终态/卸载任一漏回收即累积（R3-1 面）。
 * 于是收敛成一个**有上限、可观测、有显式出口**的池子：
 *
 * - **上限 3 的论据（防御性上限 + 可观测计数）**：AI 对话通道走 fetch POST 而非 `EventSource`
 *   （只有 `api/sse.ts` 的 reattach 用 `EventSource`，且单次调用内 ≤1 条）⇒ 同域 6 连接上限
 *   **不可达**，"保护 AI 通道"不构成本池的论据。真正的放大源是漏回收的 watcher（R-14 面，
 *   已由 `job-watch.ts` 的同 kind 回收表治理）。故这里的上限是**防御性**的：上限内按 `taskId`
 *   **降序**取前 3（列表 createdAt DESC, id DESC ⇒ 大行号 = 最新行，淘汰保新），第 4+ 个在跑任务
 *   **不订阅**，其进度靠列表的条件轮询兜底（`hasActiveJob` 覆盖全体可见行 ⇒ 仍 ≤4s 新鲜）；
 *   同时订阅数暴露为 **store 可观测字段**（`taskEventSubscriptionCount`），与真实实例数一一对应、
 *   可断言无泄漏。注意：上限只约束本池，**对 `job-watch` 池无效**（后者由自身回收表管）。
 *
 * - **四个出口**：筛选 / 翻页 / 作业终态都经 `store.load()` 落地 ⇒ 在 load 成功后统一
 *   `sync()` 重算集合；卸载走**跨层显式出口**（`TaskCenterView.onBeforeUnmount` 调 store 的
 *   `closeTaskEvents()`）—— 订阅状态挂 store（Pinia 单例，跨路由存活），**不得依赖组件自动清理**。
 *
 * - **close-gate**（R3-5）：`EventSource.close()` 只保证不再收新帧，**已入队的帧仍可能投递**
 *   ⇒ 每个订阅自带 `closed` 闸：close 之后的任何帧（4 类全覆盖）直接丢弃，不触发 `refreshNow`、
 *   不直更行（与 `job-watch.ts` 的 `disposed` 闸同款）。
 */

import { tasksApi } from '@/api/tasks'
import type { JobProgressPayload, TaskEventFrame } from '@/types/sse'

/** 订阅池实例上限（防御性上限 + 可观测计数，论据见文件头）。 */
export const TASK_EVENT_SUBSCRIPTION_LIMIT = 3

/** 一个可订阅的目标：当前页"有在跑作业"的任务。 */
export interface TaskEventTarget {
  taskId: number
  /** 该任务最新在跑作业的 id（JOB_PROGRESS/JOB_DONE 的匹配依据；未知时为 null） */
  jobId: number | null
}

export interface TaskEventsPoolOptions {
  /** 取当前应订阅的目标集合（每次 sync 重新读；调用方给当前页快照） */
  getTargets: () => TaskEventTarget[]
  /** JOB_PROGRESS 帧（直更对应行用） */
  onProgress: (taskId: number, payload: JobProgressPayload) => void
  /** JOB_DONE / TASK_CHANGED 帧（即时刷新用） */
  onRefresh: () => void
  /** 活跃订阅数变化（store 可观测字段的回填出口） */
  onCountChange?: (count: number) => void
}

export interface TaskEventsPool {
  /**
   * 重算订阅集合：上限内按 `taskId` **降序**取前 3（保最新行，F9）；回收多余者、补齐缺口、
   * 同 `taskId` 去重；已有实例不重建（幂等 ⇒ 淘汰顺序稳定）。
   */
  sync(): void
  /** 关闭全部订阅（卸载出口；幂等）。关闭后的池子不再复用（store 侧会重建一个新池）。 */
  closeAll(): void
  /** 活跃订阅数（与真实 `EventSource` 实例数一致）。 */
  activeCount(): number
  /** 当前在订的 taskId（升序，便于断言淘汰顺序）。 */
  activeTaskIds(): number[]
}

interface Subscription {
  taskId: number
  source: EventSource
  /** close-gate：close 之后到达的帧一律丢弃 */
  closed: boolean
}

export function createTaskEventsPool(options: TaskEventsPoolOptions): TaskEventsPool {
  const subscriptions = new Map<number, Subscription>()
  /** 死通道任务（F6：onerror 判死后标记，sync 不再重订阅 —— 该任务退回列表轮询兜底） */
  const dead = new Set<number>()
  let disposed = false

  const notify = (): void => {
    options.onCountChange?.(subscriptions.size)
  }

  /** 帧分派（close-gate 之后才轮到它）：进度直更；终态/任务变更即时刷新；心跳无业务动作。 */
  function dispatch(taskId: number, frame: TaskEventFrame): void {
    switch (frame.type) {
      case 'JOB_PROGRESS':
        options.onProgress(taskId, frame.data as JobProgressPayload)
        return
      case 'JOB_DONE':
      case 'TASK_CHANGED':
        options.onRefresh()
        return
      default:
        // HEARTBEAT：只作"通道已建立"判据，不做业务动作
        return
    }
  }

  function unsubscribe(taskId: number): void {
    const subscription = subscriptions.get(taskId)
    if (!subscription) {
      return
    }
    subscription.closed = true // close-gate 先置位：close 后（含同 tick）投递的迟到帧一律丢弃
    subscription.source.close()
    subscriptions.delete(taskId)
  }

  function subscribe(taskId: number): void {
    // 先建对象、后开通道：帧回调闭包里读的是这个订阅的 closed 闸
    const subscription: Subscription = { taskId, source: null as unknown as EventSource, closed: false }
    subscription.source = tasksApi.openTaskEvents(
      taskId,
      (frame: TaskEventFrame) => {
        if (subscription.closed || disposed) {
          return
        }
        dispatch(taskId, frame)
      },
      {
        // F6（红队 M6）：死通道（404/断流，onerror 且 readyState 未 OPEN）不再 ~3s 无限重连占名额 ——
        // close 并释放名额、记入 dead，该任务标记走列表条件轮询兜底（load 的 sync 也不重订阅）。
        onerror: () => {
          if (subscription.closed || disposed) {
            return
          }
          if (subscription.source.readyState !== EventSource.OPEN) {
            dead.add(taskId)
            unsubscribe(taskId)
            notify()
          }
        }
      }
    )
    subscriptions.set(taskId, subscription)
  }

  /** 上限内的订阅计划：同 taskId 去重后按 taskId **降序**取前 N（保最新行，F9；稳定排序）。 */
  function plan(): number[] {
    const wanted = new Set<number>()
    for (const target of options.getTargets()) {
      if (!dead.has(target.taskId)) {
        wanted.add(target.taskId)
      }
    }
    return [...wanted].sort((a, b) => b - a).slice(0, TASK_EVENT_SUBSCRIPTION_LIMIT)
  }

  return {
    sync(): void {
      if (disposed) {
        return
      }
      const desired = plan()
      const kept = new Set(desired)
      let changed = false
      for (const taskId of [...subscriptions.keys()]) {
        if (!kept.has(taskId)) {
          unsubscribe(taskId)
          changed = true
        }
      }
      for (const taskId of desired) {
        if (!subscriptions.has(taskId)) {
          subscribe(taskId)
          changed = true
        }
      }
      if (changed) {
        notify()
      }
    },

    closeAll(): void {
      disposed = true
      for (const taskId of [...subscriptions.keys()]) {
        unsubscribe(taskId)
      }
      notify() // 计数归 0（即使原本就是 0 也回填一次，字段与真实实例数始终一致）
    },

    activeCount(): number {
      return subscriptions.size
    },

    activeTaskIds(): number[] {
      return [...subscriptions.keys()].sort((a, b) => a - b)
    }
  }
}
