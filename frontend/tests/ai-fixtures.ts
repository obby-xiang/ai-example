/**
 * AI 可视可操作专项测试夹具：页面上下文 + 区域 DOM 装配。
 *
 * 只驱动真实模块（`utils/ai-regions` / `utils/ai-dom-tools`），不 mock 围栏本身 ——
 * 围栏是"不可越权"的断言目标，mock 掉等于把被测物换成了桩。
 */
import { createPinia, setActivePinia } from 'pinia'
import { useWorkspaceStore } from '@/stores/workspace'
import { registerAiRegion, resetAiRegions, noteAiPage, type AiRegionOptions } from '@/utils/ai-regions'
import { resetUiRuntime } from '@/utils/ai-dom-tools'

/** 每个用例前的公共复位：独立 pinia + 清空区域表 / 运行时计数 / 快照缓存。 */
export function resetAiTestState(): void {
  setActivePinia(createPinia())
  resetAiRegions()
  resetUiRuntime()
  document.body.innerHTML = ''
}

/** 进入导入向导第 1 步（与 `ImportWizardView.onMounted` 同口径：同时声明 AI 页面）。 */
export function enterImportPage(step = 'UPLOAD', taskId: number | null = 1): void {
  const workspace = useWorkspaceStore()
  workspace.enterPage({ pageId: 'import', page: 'import', taskType: 'IMPORT', step, taskId })
  noteAiPage('import')
}

/** 进入导出向导（上下文围栏的反面用例用）。 */
export function enterExportPage(step = 'EXPORT', taskId: number | null = 1): void {
  const workspace = useWorkspaceStore()
  workspace.enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step, taskId })
  noteAiPage('export')
}

/** 造一个挂在 body 上的容器（区域 root），返回该容器。 */
export function makeRoot(html: string): HTMLElement {
  const root = document.createElement('div')
  root.innerHTML = html
  document.body.appendChild(root)
  return root
}

/** 注册一个区域（默认 `import.upload`，风险 read）。 */
export function registerRegion(
  root: HTMLElement,
  overrides: Partial<AiRegionOptions> = {}
): void {
  registerAiRegion({
    id: 'import.upload',
    root,
    risk: 'read',
    label: '上传区',
    ...overrides
  })
}
