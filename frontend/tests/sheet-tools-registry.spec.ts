/**
 * AI 表格操作专项：注册表与三级兜底（T-10、T-18、T-19、T-30）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { FRONTEND_TOOLS, SHEET_TOOL_NAMES, SHEET_WRITE_TOOLS, findFrontendTool } from '@/types/tools'
import { executeFrontendTool } from '@/utils/frontend-tools'
import { registerPageHandler, unregisterPageHandler, useWorkspaceStore } from '@/stores/workspace'
import { SHEET_TOOL_MAX_SAMPLE_ROWS } from '@/utils/sheet-tools'

vi.mock('@/router', () => ({
  default: { push: async () => undefined },
  ROUTE_NAMES: new Proxy({}, { get: (_target, key) => String(key) })
}))

beforeEach(() => {
  setActivePinia(createPinia())
  const workspace = useWorkspaceStore()
  workspace.enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'EXPORT', taskId: 7 })
  unregisterPageHandler('sheet.current')
})

describe('T-19 注册表：前端工具 2 → 10', () => {
  it('八个表格工具全部登记，写侧风险 WRITE、读侧 READ', () => {
    expect(FRONTEND_TOOLS).toHaveLength(10)
    for (const name of SHEET_TOOL_NAMES) {
      const descriptor = findFrontendTool(name)
      expect(descriptor, `${name} 已登记`).not.toBeNull()
      expect(descriptor?.scopePatterns).toEqual(['task:EXPORT/EXPORT', 'task:IMPORT/UPLOAD'])
      const expected = SHEET_WRITE_TOOLS.has(name as never) ? 'WRITE' : 'READ'
      expect(descriptor?.riskLevel, `${name} 风险`).toBe(expected)
      expect(descriptor?.description).toContain('20')
    }
  })

  it('T-18 描述与常量同源：采样上限文案含“20”，常量亦为 20', () => {
    expect(SHEET_TOOL_MAX_SAMPLE_ROWS).toBe(20)
    for (const name of SHEET_TOOL_NAMES) {
      expect(findFrontendTool(name)?.description).toContain('20')
    }
  })
})

describe('T-10 三级兜底：页面能力未注册 ⇒ degraded 可行动文案', () => {
  it('未打开向导页时读工具返回 degraded，而不是抛异常', async () => {
    const execution = await executeFrontendTool({
      name: 'read_sheet_summary',
      args: { defCode: 'DEMO' },
      budgetMs: 5_000
    })
    expect(execution.outcome).toBe('failed')
    expect(execution.availability).toBe('degraded')
    expect(execution.result).toContain('请先打开导出结果页或上传页')
  })

  it('注册 handler 后读工具正常执行（回灌纯文本）', async () => {
    registerPageHandler('sheet.current', () => ({
      ok: true,
      defCode: 'DEMO',
      sheetName: '演示表',
      readOnly: false,
      version: 3,
      fields: [
        { defCode: 'DEMO', code: 'code', label: '编码', fieldType: 'STRING', required: true, key: true, sortOrder: 0 },
        { defCode: 'DEMO', code: 'name', label: '名称', fieldType: 'STRING', required: false, key: false, sortOrder: 1 }
      ],
      adapter: {
        rowCount: () => 1,
        colCount: () => 2,
        header: (col: number) => (col === 0 ? '编码' : '名称'),
        getCell: (row: number, col: number) => (row === 0 && col === 0 ? 'CNY' : '人民币'),
        getArray: () => [],
        writeCells: () => undefined,
        selection: () => null
      }
    }))
    const execution = await executeFrontendTool({
      name: 'read_sheet_summary',
      args: { defCode: 'DEMO', scope: 'sheet' },
      budgetMs: 5_000
    })
    expect(execution.outcome).toBe('ok')
    expect(execution.result).toContain('共 1 行')
    expect(execution.result).not.toContain('{')
  })
})

describe('T-30 未上线能力：delete_rows 明确不可用', () => {
  it('返回 unavailable + 当前不支持按条件删除行', async () => {
    const execution = await executeFrontendTool({
      name: 'delete_rows',
      args: { defCode: 'DEMO' },
      budgetMs: 5_000
    })
    expect(execution.availability).toBe('unavailable')
    expect(execution.result).toContain('当前不支持按条件删除行')
  })
})
