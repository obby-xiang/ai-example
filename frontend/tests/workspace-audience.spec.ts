/**
 * M-C5 **红线**：双受众字符串在切英文后必须仍是中文（进模型的那一份不随 UI 语言变）。
 *
 * 为什么单列：`recentActions` 会作为 `context.extra.recentActions` 送进模型；`PAGE_LABELS`
 * 与后端 `ContextBuilder` 的页面名同源。这一批唯一可能**静默改运行时语义**的路径就在这里 ——
 * 抽文案时"顺手"把 touch() 的 summary 也 `t()` 了，切 en 后 prompt 就变了。
 *
 * 证红证据（若把 touch 摘要迁进 i18n）：本文件 en 用例会拿到英文摘要 ⇒ 直接红。
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useWorkspaceStore } from '@/stores/workspace'
import { useLocaleStore } from '@/stores/locale'
import { mapTaskStatusToStep } from '@/stores/task'

describe('双受众红线（C5）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    useLocaleStore().setLocale('zh-CN')
  })

  it('切 en 后 recentActions 送模型的串仍是中文（逐字等于 zh 下的同串）', () => {
    const zhPinia = createPinia()
    setActivePinia(zhPinia)
    useLocaleStore().setLocale('zh-CN')
    const zhWorkspace = useWorkspaceStore()
    zhWorkspace.enterPage({ pageId: 'tasks', page: 'tasks' })
    zhWorkspace.setTaskId(12)
    zhWorkspace.setSelectedDefs(['CURRENCY', 'TAX_RATE'])
    const zhSummaries = zhWorkspace.recentActions.map((record) => record.summary)

    const enPinia = createPinia()
    setActivePinia(enPinia)
    useLocaleStore().setLocale('en-US')
    const enWorkspace = useWorkspaceStore()
    enWorkspace.enterPage({ pageId: 'tasks', page: 'tasks' })
    enWorkspace.setTaskId(12)
    enWorkspace.setSelectedDefs(['CURRENCY', 'TAX_RATE'])
    const enSummaries = enWorkspace.recentActions.map((record) => record.summary)

    expect(enSummaries).toEqual(zhSummaries)
    expect(enSummaries).toEqual(['进入任务中心', '绑定任务 #12', '已选择配置项：CURRENCY、TAX_RATE'])
  })

  it('切 en 后 buildContext 的 extra.recentActions 仍带中文摘要，来源用数据码 UI', () => {
    useLocaleStore().setLocale('en-US')
    const workspace = useWorkspaceStore()
    workspace.enterPage({ pageId: 'export', page: 'export' })
    const context = workspace.buildContext()
    const recent = (context.extra as { recentActions: string[] }).recentActions
    expect(recent[0]).toBe('进入导出向导（来源：UI）')
  })

  it('上下文摘要（UI chip）在 zh 下逐字不变', () => {
    const workspace = useWorkspaceStore()
    workspace.enterPage({ pageId: 'tasks', page: 'tasks' })
    workspace.setSelectedDefs(['A', 'B'])
    expect(workspace.contextSummary).toContain('任务中心')
    expect(workspace.contextSummary).toContain('已选 2 个配置项')
  })

  it('PAGE_LABELS 与后端 ContextBuilder 同源（前端镜像仍为中文常量）', () => {
    // 该断言的意义：页面中文名是**模型侧**文案，不属于 UI i18n 面；值一旦被翻译即红。
    const workspace = useWorkspaceStore()
    const labels: Record<string, string> = {}
    for (const pageId of ['tasks', 'export', 'import', 'definitions', 'data'] as const) {
      workspace.enterPage({ pageId })
      labels[pageId] = workspace.recentActions.at(-1)?.summary ?? ''
    }
    expect(labels).toEqual({
      tasks: '进入任务中心',
      export: '进入导出向导',
      import: '进入导入向导',
      definitions: '进入配置定义',
      data: '进入数据浏览'
    })
  })

  it('任务状态词表（store/format）在 en 下不跟随 UI 语言（模型侧观察面）', () => {
    useLocaleStore().setLocale('en-US')
    // 状态 label 是给步进条/模型看的中文词表；本批不动它（属 S5 双受众二分范围）
    expect(mapTaskStatusToStep('COMPLETED').label).toBe('已完成')
  })
})
