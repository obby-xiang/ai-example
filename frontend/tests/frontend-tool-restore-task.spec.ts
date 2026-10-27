/**
 * 批次 5B（Task 8）：`restore_task` / 带任务号的 `navigate_to` **按服务端任务类型**导航。
 *
 * <h2>缺陷</h2>
 * `restore_task` 旧实现经别名落到 `navigate_to`：
 * - 只有 `taskId`（模型正是这么调的）⇒ 判"参数不完整（缺少 page）"，动作根本不可达；
 * - 带上 `page` ⇒ 目标页由**列表缓存**决定，未命中直接兜底 EXPORT
 *   ⇒ **导入任务被送进导出向导**（用户看到的标题/步骤全错，且模型收到的是"成功"）。
 *
 * <h2>口径（本文件锁住）</h2>
 * ① 类型取自 `GET /tasks/{id}`（`taskStore.ensureTaskType`），导入任务进导入向导；
 * ② 类型查不到 ⇒ **fail-closed 不导航**（不猜、不兜底 EXPORT），回执可行动；
 * ③ `navigate_to(page=export, taskId=导入任务)` 同样以服务端类型为准，并在回执里说明修正；
 * ④ 导航后等目标页就绪（任务已绑定）才回灌，不等则判 `UI_PAGE_NOT_READY`。
 *
 * 本文件**只写不跑**（批次 5B 只跑 `task-center-view.spec.ts` 的一条聚焦用例）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { executeFrontendTool } from '@/utils/frontend-tools'
import { dispatchUiEvent, getPageHandler, registerPageHandler, unregisterPageHandler, useWorkspaceStore } from '@/stores/workspace'
import { useTaskStore } from '@/stores/task'
import type { RouteLocationRaw } from 'vue-router'

const api = vi.hoisted(() => ({
  getTask: vi.fn(),
  listTasks: vi.fn(),
  createTask: vi.fn(),
  deleteTask: vi.fn(),
  goToStep: vi.fn(),
  selectDefs: vi.fn(),
  setCondition: vi.fn(),
  setImportMode: vi.fn(),
  listJobs: vi.fn(),
  createJob: vi.fn(),
  cancelJob: vi.fn(),
  getIssues: vi.fn()
}))
const push = vi.hoisted(() => vi.fn())

vi.mock('@/router', () => ({
  default: { push },
  ROUTE_NAMES: new Proxy({}, { get: (_target, key) => String(key) })
}))
vi.mock('@/api/tasks', () => ({ tasksApi: api }))
vi.mock('@/api/jobs', () => ({ jobsApi: api }))
vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), warning: vi.fn(), success: vi.fn(), info: vi.fn() }
}))

/**
 * push 桩：模拟"目标页挂载"——页面 `onMounted` 会 `enterPage` 并注册能力 handler。
 * 这正是 `waitForTaskBound` 的完成判据（页面 id 匹配 + 探针 handler 已登记 + 任务已绑定）。
 */
function mountPageOnPush(): void {
  push.mockImplementation(async (target: RouteLocationRaw) => {
    const route = target as { name?: string; query?: Record<string, string> }
    const taskId = Number(route.query?.taskId ?? 0)
    const workspace = useWorkspaceStore()
    if (route.name === 'importWizard') {
      workspace.enterPage({ pageId: 'import', page: 'import', taskType: 'IMPORT', step: 'UPLOAD', taskId })
    } else if (route.name === 'exportWizard') {
      workspace.enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'EXPORT', taskId })
    } else if (route.name === 'dataBrowser') {
      workspace.enterPage({ pageId: 'data', page: 'data', taskType: null, step: null, taskId: null })
    }
  })
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  mountPageOnPush()
  registerPageHandler('import.nextStep', async () => 'ok')
  registerPageHandler('export.nextStep', async () => 'ok')
})

describe('restore_task：类型以服务端为准（导入不跳导出）', () => {
  it('导入任务 ⇒ 进导入向导（不是导出向导），回执说明类型来源', async () => {
    api.getTask.mockResolvedValue({ id: 7, type: 'IMPORT', status: 'IN_PROGRESS', items: [], currentStep: 'UPLOAD' })

    const execution = await executeFrontendTool({ name: 'restore_task', args: { taskId: 7 }, budgetMs: 8_000 })

    expect(api.getTask).toHaveBeenCalledWith(7)
    expect(push).toHaveBeenCalledTimes(1)
    expect(push).toHaveBeenCalledWith({ name: 'importWizard', query: { taskId: '7' } })
    expect(push).not.toHaveBeenCalledWith(expect.objectContaining({ name: 'exportWizard' }))
    expect(execution.outcome).toBe('ok')
    expect(execution.result).toContain('导入')
    expect(execution.result).toContain('已绑定')
  })

  it('类型查不到 ⇒ fail-closed：不导航 + 可行动回执（不兜底 EXPORT）', async () => {
    api.getTask.mockRejectedValue(new Error('任务不存在'))

    const execution = await executeFrontendTool({ name: 'restore_task', args: { taskId: 8 }, budgetMs: 8_000 })

    expect(push).not.toHaveBeenCalled()
    expect(execution.outcome).toBe('failed')
    expect(execution.result).toContain('无法确认任务 #8 的类型')
    expect(execution.result).toContain('list_tasks')
  })

  it('缺 taskId ⇒ unavailable 且指名缺参（不回显参数对象）', async () => {
    const execution = await executeFrontendTool({ name: 'restore_task', args: {}, budgetMs: 5_000 })

    expect(execution.outcome).toBe('failed')
    expect(execution.availability).toBe('unavailable')
    expect(execution.result).toContain('taskId')
    expect(execution.result).not.toContain('{')
  })
})

describe('navigate_to(page, taskId)：已知任务类型优先于模型写的页面', () => {
  it('模型写 page=export 而任务是导入任务 ⇒ 实际进导入向导，并在回执里说明修正', async () => {
    api.getTask.mockResolvedValue({ id: 7, type: 'IMPORT', status: 'IN_PROGRESS', items: [], currentStep: 'UPLOAD' })
    // 类型"已知"的来源可以是任务中心列表 / 之前 restore_task 的权威查询（这里走后者的同一条缓存）
    await useTaskStore().ensureTaskType(7)

    const execution = await executeFrontendTool({
      name: 'navigate_to',
      args: { page: 'export', taskId: 7 },
      budgetMs: 8_000
    })

    expect(push).toHaveBeenCalledWith({ name: 'importWizard', query: { taskId: '7' } })
    expect(execution.outcome).toBe('ok')
    expect(execution.result).toContain('已按服务端类型进入导入向导')
  })

  it('无 taskId 的纯切页不发类型查询（GET 0 次）', async () => {
    const execution = await executeFrontendTool({
      name: 'navigate_to',
      args: { page: 'data' },
      budgetMs: 5_000
    })

    expect(api.getTask).not.toHaveBeenCalled()
    expect(push).toHaveBeenCalledWith({ name: 'dataBrowser' })
    expect(execution.outcome).toBe('ok')
  })

  it('已在目标页且任务已绑定 ⇒ 幂等直返，不再 push', async () => {
    useWorkspaceStore().enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'EXPORT', taskId: 9 })
    api.getTask.mockResolvedValue({ id: 9, type: 'EXPORT', status: 'IN_PROGRESS', items: [], currentStep: 'EXPORT' })

    const execution = await executeFrontendTool({
      name: 'navigate_to',
      args: { page: 'export', taskId: 9 },
      budgetMs: 5_000
    })

    expect(push).not.toHaveBeenCalled()
    expect(execution.outcome).toBe('ok')
    expect(execution.result).toContain('已在目标页')
  })
})

describe('导航后等目标页就绪', () => {
  it('目标页在等待上界内未就绪 ⇒ 可判定失败 UI_PAGE_NOT_READY（不谎报成功）', async () => {
    // push 不模拟挂载（页面一直没起来）⇒ waitForPageReady 到点返回 false
    push.mockResolvedValue(undefined)
    unregisterPageHandler('export.nextStep')

    const execution = await executeFrontendTool({
      name: 'navigate_to',
      args: { page: 'export' },
      // 预算给足 3s 等待上界 + 余量
      budgetMs: 6_000
    })

    expect(execution.outcome).toBe('failed')
    expect(execution.code).toBe('UI_PAGE_NOT_READY')
  })

  it('restore_task 后页面就绪但任务未绑定 ⇒ degraded（区分"还在加载"与"绑不上"）', async () => {
    api.getTask.mockResolvedValue({ id: 7, type: 'IMPORT', status: 'IN_PROGRESS', items: [], currentStep: 'UPLOAD' })
    // 页面挂上了，但绑定的是别的任务
    push.mockImplementation(async () => {
      useWorkspaceStore().enterPage({ pageId: 'import', page: 'import', taskType: 'IMPORT', step: 'UPLOAD', taskId: 99 })
    })
    expect(getPageHandler('import.nextStep')).not.toBeNull()

    const execution = await executeFrontendTool({ name: 'restore_task', args: { taskId: 7 }, budgetMs: 6_000 })

    expect(execution.outcome).toBe('failed')
    expect(execution.result).toContain('绑定没有完成')
  })
})

describe('冷缓存：类型缓存优先、未命中查一次、查不到不导航', () => {
  it('navigate_to(page=export) 而缓存为空 ⇒ 发一次 GET 并按服务端类型进导入向导', async () => {
    api.getTask.mockResolvedValue({ id: 41, type: 'IMPORT', status: 'IN_PROGRESS', items: [], currentStep: 'UPLOAD' })

    const execution = await executeFrontendTool({
      name: 'navigate_to',
      args: { page: 'export', taskId: 41 },
      budgetMs: 8_000
    })

    expect(api.getTask).toHaveBeenCalledTimes(1)
    expect(push).toHaveBeenCalledWith({ name: 'importWizard', query: { taskId: '41' } })
    expect(execution.result).toContain('已按服务端类型进入导入向导')
  })

  it('类型查不到 ⇒ navigate_to 也 fail-closed（push 0 次）', async () => {
    api.getTask.mockRejectedValue(new Error('任务不存在'))

    const execution = await executeFrontendTool({
      name: 'navigate_to',
      args: { page: 'export', taskId: 42 },
      budgetMs: 8_000
    })

    expect(push).not.toHaveBeenCalled()
    expect(execution.outcome).toBe('failed')
    expect(execution.result).toContain('无法确认任务 #42 的类型')
  })

  it('契约层 restore_task 未带 taskType ⇒ 当场用 ensureTaskType 定页（不按列表兜底 EXPORT）', async () => {
    api.getTask.mockResolvedValue({ id: 43, type: 'IMPORT', status: 'IN_PROGRESS', items: [], currentStep: 'UPLOAD' })

    const result = await dispatchUiEvent({ type: 'restore_task', taskId: 43 })

    expect(result.handled).toBe(true)
    expect(push).toHaveBeenCalledWith({ name: 'importWizard', query: { taskId: '43' } })
  })
})

describe('导航成功前等"任务详情已应用"（防 confirm_step 空写）', () => {
  it('详情未应用 ⇒ failed（不自称成功），回执点名"本轮不要推进步骤"', async () => {
    api.getTask.mockResolvedValue({ id: 51, type: 'EXPORT', status: 'IN_PROGRESS', items: [], currentStep: 'EXPORT' })
    registerPageHandler('export.taskDetailReady', () => false)
    try {
      const execution = await executeFrontendTool({
        name: 'navigate_to',
        args: { page: 'export', taskId: 51 },
        budgetMs: 6_000
      })

      expect(execution.outcome).toBe('failed')
      expect(execution.code).toBe('UI_PAGE_NOT_READY')
      expect(execution.result).toContain('详情')
      expect(execution.result).not.toContain('可继续执行本页动作')
    } finally {
      unregisterPageHandler('export.taskDetailReady')
    }
  })

  it('详情已应用 ⇒ ok，回执说明已绑定', async () => {
    api.getTask.mockResolvedValue({ id: 52, type: 'EXPORT', status: 'IN_PROGRESS', items: [], currentStep: 'EXPORT' })
    registerPageHandler('export.taskDetailReady', () => true)
    try {
      const execution = await executeFrontendTool({
        name: 'navigate_to',
        args: { page: 'export', taskId: 52 },
        budgetMs: 6_000
      })

      expect(execution.outcome).toBe('ok')
      expect(execution.result).toContain('任务 #52 已绑定')
    } finally {
      unregisterPageHandler('export.taskDetailReady')
    }
  })

  it('同页同参幂等早退也等详情：详情未应用 ⇒ 不再回 UI_ALREADY_ON_PAGE', async () => {
    useWorkspaceStore().enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'EXPORT', taskId: 53 })
    api.getTask.mockResolvedValue({ id: 53, type: 'EXPORT', status: 'IN_PROGRESS', items: [], currentStep: 'EXPORT' })
    registerPageHandler('export.taskDetailReady', () => false)
    try {
      const execution = await executeFrontendTool({
        name: 'navigate_to',
        args: { page: 'export', taskId: 53 },
        budgetMs: 6_000
      })

      expect(execution.outcome).toBe('failed')
      expect(execution.code).not.toBe('UI_ALREADY_ON_PAGE')
      expect(push).not.toHaveBeenCalled()
    } finally {
      unregisterPageHandler('export.taskDetailReady')
    }
  })
})
