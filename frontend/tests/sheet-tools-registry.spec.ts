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

describe('T-19 注册表：前端工具 2 → 10 → 17 → 18', () => {
  it('九个表格工具全部登记，写侧风险 WRITE、读侧 READ', () => {
    // 2 既有 + 9 表格 + 7 界面原语（AI 可视可操作专项）；工具族增加时本计数同步提高（只升不降）
    expect(FRONTEND_TOOLS).toHaveLength(18)
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
        getArray: (row: number, col: number, rowCount: number, colCount: number) =>
          Array.from({ length: rowCount }, (_row, r) =>
            Array.from({ length: colCount }, (_cell, c) => (row + r === 0 && col + c === 0 ? 'CNY' : '人民币'))),
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

// ── 表格工具批A：注册表/执行器层修错（P0-3 / P0-4 / 文案订正） ──────────────────

function installSheetHandler(input: { readOnly: boolean; rowCount: number }): void {
  registerPageHandler('sheet.current', () => ({
    ok: true,
    defCode: 'DEMO',
    sheetName: '演示表',
    readOnly: input.readOnly,
    version: 1,
    fields: [
      { defCode: 'DEMO', code: 'code', label: '编码', fieldType: 'STRING', required: true, key: true, sortOrder: 0 },
      { defCode: 'DEMO', code: 'name', label: '名称', fieldType: 'STRING', required: false, key: false, sortOrder: 1 }
    ],
    adapter: {
      rowCount: () => input.rowCount,
      colCount: () => 2,
      header: (col: number) => (col === 0 ? '编码' : '名称'),
      getCell: (row: number, col: number) => (col === 0 ? `C${row + 1}` : `名称${row + 1}`),
      getArray: (row: number, col: number, rowCount: number, colCount: number) =>
        Array.from({ length: rowCount }, (_row, r) =>
          Array.from({ length: colCount }, (_cell, c) => (col + c === 0 ? `C${row + r + 1}` : `名称${row + r + 1}`))),
      writeCells: () => undefined,
      selection: () => null
    }
  }))
}

describe('P0-3 rowRange 前端登记为字符串：通用校验不得再拒', () => {
  it('scope=rows + rowRange="2-4" 经 executeFrontendTool ⇒ ok', async () => {
    installSheetHandler({ readOnly: false, rowCount: 3 })
    const execution = await executeFrontendTool({
      name: 'read_sheet_summary',
      args: { defCode: 'DEMO', scope: 'rows', rowRange: '2-4', limit: 3 },
      budgetMs: 5_000
    })
    expect(execution.outcome).toBe('ok')
    expect(execution.result).toContain('scope=rows')
    expect(execution.result).not.toContain('应为对象')
  })
})

describe('P0-4 只读壳只拦写工具，读工具放行', () => {
  it('只读壳 + read_sheet_summary ⇒ 正常回采样（不再误报“未对表格做任何修改”）', async () => {
    installSheetHandler({ readOnly: true, rowCount: 2 })
    const execution = await executeFrontendTool({
      name: 'read_sheet_summary',
      args: { defCode: 'DEMO', scope: 'sheet' },
      budgetMs: 5_000
    })
    expect(execution.outcome).toBe('ok')
    expect(execution.result).toContain('共 2 行')
    expect(execution.result).not.toContain('未对表格做任何修改')
  })

  it('只读壳 + normalize_column(commit=false) ⇒ 仍可出建议', async () => {
    installSheetHandler({ readOnly: true, rowCount: 2 })
    const execution = await executeFrontendTool({
      name: 'normalize_column',
      args: { defCode: 'DEMO', scope: 'sheet', columnCodes: ['code'], rules: ['trim'] },
      budgetMs: 5_000
    })
    expect(execution.outcome).toBe('ok')
    expect(execution.result).toContain('归一化建议')
  })

  it('只读壳 + normalize_column(commit=true) ⇒ 写路径仍被拦', async () => {
    installSheetHandler({ readOnly: true, rowCount: 2 })
    const execution = await executeFrontendTool({
      name: 'normalize_column',
      args: { defCode: 'DEMO', scope: 'sheet', columnCodes: ['code'], rules: ['trim'], commit: true },
      budgetMs: 5_000
    })
    expect(execution.outcome).toBe('failed')
    expect(execution.availability).toBe('degraded')
    expect(execution.result).toContain('任务已结束')
  })
})

describe('文案订正：披露口径不再声称“业务行数据不出浏览器”', () => {
  it('read_sheet_summary 描述 = 全量业务行不出浏览器 + 仅投影采样进模型', () => {
    const description = findFrontendTool('read_sheet_summary')?.description ?? ''
    expect(description).toContain('全量业务行不出浏览器')
    expect(description).toContain('20 行')
    expect(description).not.toContain('业务行数据不出浏览器')
  })
})

describe('批B：find_rows 经执行入口可用（登记 + 默认执行器 + 读路径不受只读壳拦）', () => {
  it('只读壳 + find_rows ⇒ ok，命中行带单元格地址与“共 N 行”', async () => {
    installSheetHandler({ readOnly: true, rowCount: 3 })
    const execution = await executeFrontendTool({
      name: 'find_rows',
      args: { defCode: 'DEMO', search: '名称2' },
      budgetMs: 5_000
    })
    expect(execution.outcome).toBe('ok')
    expect(execution.result).toContain('第 3 行 | B3 name=名称2')
    expect(execution.result).toContain('共 1 行')
    expect(execution.result).not.toContain('未对表格做任何修改')
  })

  it('find_rows 缺 search ⇒ unavailable 且说明 search 必填', async () => {
    installSheetHandler({ readOnly: false, rowCount: 2 })
    const execution = await executeFrontendTool({
      name: 'find_rows',
      args: { defCode: 'DEMO' },
      budgetMs: 5_000
    })
    expect(execution.outcome).toBe('failed')
    expect(execution.result).toContain('search')
  })
})
