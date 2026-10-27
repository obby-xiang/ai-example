/**
 * 无噪声成功批：**成功即静默**的边界（`lastUiEventResult` 回执位）。
 *
 * 现场：`navigate_to` / `restore_task` / `goto_step` / 三类刷新成功时，面板顶部仍弹一条
 * "已打开… / 已恢复到… "的灰条 —— 页面已经换过去了，用户看得见，回执位只是噪声；
 * 模型侧要的证明也已由工具结果（回灌文本）给出。
 *
 * 锁住三条边界：
 * 1. `handled=true` 且属于那六类 ⇒ **不写**回执位；
 * 2. 失败（`handled=false`）与有副作用的成功（`run_job` / `download` …）⇒ **照写**；
 * 3. 新轮清槽：上一轮留在槽里的回执不带到下一轮。
 *
 * 底层不动：`dispatchUiEvent` 仍广播 `ui_event_result`（总线订阅者照收）、
 * `recentActions` / `touch` 照记 —— 本文件只验面板回执位这一处消费面。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAiStore } from '@/stores/ai'
import { emitWorkspaceEvent } from '@/stores/workspace'

const api = vi.hoisted(() => ({
  chat: vi.fn(),
  cancel: vi.fn(async () => undefined),
  history: vi.fn(async () => ({ messages: [] })),
  runsCurrent: vi.fn(async () => ({ found: false })),
  getContext: vi.fn(async () => null)
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

beforeEach(() => {
  setActivePinia(createPinia())
  useAiStore().startWorkspaceSync()
})

afterEach(() => {
  useAiStore().stopWorkspaceSync()
})

function receipt(event: string, handled: boolean, message: string): void {
  emitWorkspaceEvent('ui_event_result', { event, handled, message })
}

describe('成功即静默：导航 / 恢复 / 切步 / 刷新', () => {
  it('六类事件 handled=true ⇒ 回执位保持为空', () => {
    const store = useAiStore()
    for (const event of ['open_page', 'restore_task', 'goto_step', 'refresh_defs', 'refresh_data', 'refresh_tasks']) {
      receipt(event, true, `已执行 ${event}`)
      expect(store.lastUiEventResult).toBeNull()
    }
  })

  it('失败（handled=false）不静默：仍写回执位', () => {
    const store = useAiStore()
    receipt('restore_task', false, '无法确认任务 #7 的类型')
    expect(store.lastUiEventResult).toEqual({
      event: 'restore_task',
      handled: false,
      message: '无法确认任务 #7 的类型'
    })
  })

  it('有副作用的成功不静默：run_job / download 仍写回执位', () => {
    const store = useAiStore()
    receipt('run_job', true, '作业已启动：EXPORT（作业 #10）')
    expect(store.lastUiEventResult?.event).toBe('run_job')
    receipt('download', true, '已触发下载 demo.xlsx')
    expect(store.lastUiEventResult?.event).toBe('download')
  })

  it('静默成功不抹掉槽里已有的失败事实', () => {
    const store = useAiStore()
    receipt('select_defs', false, '任务已结束，仅供查看')
    receipt('open_page', true, '已打开任务中心')
    expect(store.lastUiEventResult).toEqual({ event: 'select_defs', handled: false, message: '任务已结束，仅供查看' })
  })
})

describe('新轮清槽', () => {
  it('send 开新轮 ⇒ 上一轮的回执不带到本轮', async () => {
    const store = useAiStore()
    receipt('run_job', true, '作业已启动（作业 #10）')
    expect(store.lastUiEventResult).not.toBeNull()

    api.chat.mockImplementation((_request: unknown, handlers: { onFrame: (frame: unknown) => void }) => {
      handlers.onFrame({ type: 'done', runId: 'run-silent', seq: 1, cancelled: false })
      return Promise.resolve({ terminal: true, reconnects: 0 })
    })

    await store.send('继续')

    expect(store.lastUiEventResult).toBeNull()
  })
})
