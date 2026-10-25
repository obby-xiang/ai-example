/**
 * AI 表格操作专项：表格工具的**纯函数内核**（不 import 任何视图 / 不 import SpreadJS）。
 *
 * 设计依据：`docs/research/设计-AI表格操作专项-2026-10-21.md`
 * - §3.0 五元组参数：`scope × predicate × operation × value × preview`（`commit` 恒为 confirm）
 * - §3.3 尺寸闸单点：`capSheetResult()`（≤20 行 / ≤2000 字符 / 恒定“共 N”）
 * - §3.3bis 预览三档：count（0 行业务值）/ sample-diff（≤20 行）/ full-diff（0 行经模型）
 * - §3.5 行指纹：预览与应用之间表格被改动即整批拒绝（宁可多拒一次，不可改错一行）
 * - §3.6 窄接口：`SheetAdapter`（批量读 `getArray`、批量写 `writeCells`）
 *
 * 批B（读侧补齐）：读路径一律经 {@link scanRows} 批量 `getArray`（对齐业界 batchGet，不再逐格读）；
 * 读输出带 A1 单元格地址（列字母 + Excel 行号）；`aggregate_sheet` 分组先排序再截断（Top-N）；
 * 新增 `find_rows`（全列检索：命中行号 + 地址 + ≤20 行采样 + 共 N 行）。
 *
 * 行列口径：适配器行 = **数据行下标（0 基）**，对外文案统一 `excelRowNumber(row)`（Excel 行号）；
 * 列 = 适配器列下标（0 基），业务列由字段编码经表头归一化解析。
 */
import type { ConfigField } from '@/types/definition'
import type { FieldQueryCondition } from '@/types/condition'
import { conditionSummary } from '@/types/condition'
import type { SheetChange, SheetDistribution, SheetPreviewLevel, SheetWritePlan } from '@/types/ai'
import { columnLetter, excelRowNumber, validateCell, validateRows } from '@/utils/excel'
import i18n from '@/locales'

// ── 常量（设计稿 §3.3，读侧硬顶 / 写侧分层） ─────────────────────────────────

export const SHEET_TOOL_MAX_SAMPLE_ROWS = 20
export const SHEET_TOOL_MAX_RESULT_CHARS = 2000
export const SHEET_TOOL_MAX_COLUMNS = 5
export const SHEET_TOOL_DEFAULT_SAMPLE_ROWS = 5
export const SHEET_AGGREGATE_MAX_GROUPS = 20
export const SHEET_PREVIEW_SAMPLE_PAIRS = 3
export const SHEET_WRITE_SMALL_DIFF_MAX_CELLS = 2000
export const SHEET_WRITE_LARGE_ROW_THRESHOLD = 200
export const SHEET_WRITE_HARD_MAX_CELLS = 100_000
export const SHEET_FINGERPRINT_COLUMNS = 2
/**
 * 撤销深度（AI 表格工具治理批，2026-10-22 裁决）：1 → 3。
 *
 * <p>
 * 3 覆盖"改错 → 再改 → 退回"这条最常见的三步；5 会让"最旧那层早已语义失真"（用户记不住）。
 * 淘汰是**静默**的（FIFO 丢栈底），但撤销回执必须写明"剩余可撤销 N 层"，避免用户以为能一直撤。
 *
 * <p><b>不承诺 Ctrl+Z</b>：AI 改动的反向补丁与用户撤销栈完全独立（共用一个栈就回答不了
 * "哪一笔记的是 AI"），故任何面向用户的文案都不得出现"可用 Ctrl+Z 撤销 AI 改动"。
 */
export const SHEET_UNDO_DEPTH = 3
export const SHEET_WRITE_CHUNK_ROWS = 500
/** 计数档分布显示的组数（超出折叠为「其余 N 组」；内部统计上限 = SHEET_AGGREGATE_MAX_GROUPS）。 */
export const SHEET_DISTRIBUTION_SHOW_GROUPS = 5
/** 批量读的批次行数：一段一次 `getArray`（对齐业界 batchGet，避免逐格读卡主线程）。 */
export const SHEET_READ_BATCH_ROWS = 500

/** 适配器批量写单位（`writeCells` 一条 = 一次 suspendPaint 内的批量落值）。 */
export interface SheetCellUpdate {
  row: number
  col: number
  value: unknown
}

/** 选区（行 = 数据行下标；与 `SheetAdapter#selection` 同口径）。 */
export interface SheetSelection {
  row: number
  col: number
  rowCount: number
  colCount: number
}

/**
 * 屏幕态标记的两种色调（同一套绘制入口，AI 表格工具治理 §5.4）：
 * `applied` = 已应用的 AI 改动（保留到下一次 apply / 撤销 / 刷新）；
 * `preview` = 预览期的**待改格**（取消 / 放弃 / 应用前置 / 切回计数档即清）。
 */
export type SheetMarkTone = 'applied' | 'preview'

/**
 * 表格适配器窄接口（设计稿 §3.6）。
 *
 * 只暴露纯数据面：**读写都必须走 array 版本**（批量），逐格 `getValue/setValue` 在数千行上会卡主线程。
 * 批B 起工具内核**不再用 `getCell` 读数据**（全部经 {@link scanRows} 批量 `getArray`），
 * `getCell` 仅保留给适配器自测与写后回读。
 */
export interface SheetAdapter {
  rowCount(): number
  colCount(): number
  header(col: number): string
  getCell(row: number, col: number): unknown
  getArray(row: number, col: number, rows: number, cols: number): unknown[][]
  writeCells(updates: readonly SheetCellUpdate[]): void
  selection(): SheetSelection | null
  /**
   * 屏幕态标记（AI 表格工具治理批 §5.4；可选能力）：给这些格加临时底色。
   *
   * <p>
   * <b>只走屏幕绘制</b>：不写 cell style、不调 `conditionalFormats` —— 后者会污染
   * `toWorkbookJson()` 产物。本仓的导出链（`buildRowsXlsxBlob`）只读 `collectRows()` 的值，
   * 故底色进不了导出文件（U-06 逐字节相同的结构保证）。
   * 适配器不实现该能力时行为不变（纯数据面适配器 / 单测桩）。
   *
   * @param tone 缺省 = `applied`（既有调用点语义不变）；预览期显式传 `preview`
   */
  markCells?(cells: readonly { row: number; col: number }[], tone?: SheetMarkTone): void
  /**
   * 清除标记（同样只是屏幕态）。
   *
   * @param tone 缺省 = 全清（换会话 / 换页这类「整屏收口」用）；撤销只传 `applied`（只回滚自己那层），
   *   预览收口只传 `preview`（互不误伤）
   */
  clearMarks?(tone?: SheetMarkTone): void
  /**
   * 表格实例身份（可选）：同一工作表实例稳定，`fromJSON` 重建 / 换文件后变化。
   *
   * <p>预览身份（{@link SheetWritePlan#previewId}）的一部分：适配器若持有旧实例，
   * 身份变化即说明"这张表已经不是你预览时的那张"。
   */
  instanceId?(): string
  /**
   * 程序化载入代数（可选）：每次 `fromJSON` / 程序化落数据递增。
   *
   * <p>同一实例内也会递增（重跑导出、上传新文件、换任务重载都可能复用同一工作表对象）；
   * 只靠 `instanceId` 抓不到这种"同表换内容"。
   */
  loadGeneration?(): number
}

/** 表格上下文（页面能力 `sheet.current` 的返回值）。 */
export interface SheetContext {
  defCode: string
  sheetName: string
  readOnly: boolean
  adapter: SheetAdapter
  fields: readonly ConfigField[]
  /** 工作区数据版本号（模型见版本变化即知摘要过期） */
  version: number
  /**
   * 表格实例身份（可选；缺省时由适配器方法/前端兜底派生）。
   *
   * <p>预览身份的一部分：换文件 / 引擎重建后变化。
   */
  sheetInstanceId?: string
  /**
   * 程序化载入代数（可选；缺省 = 0）：上传新文件 / 重跑导出 / 程序化重载时递增。
   *
   * <p>为什么单列：同一工作表实例也会被重新填内容（`loadFromJson`），`instanceId` 不变，
   * 但预览算出的行值与此刻表格已不是同一份数据。
   */
  loadGeneration?: number
}

export type SheetToolOutcome = { ok: true; text: string; meta?: SheetToolMeta } | { ok: false; reason: string }
export type SheetPlanOutcome = { ok: true; plan: SheetWritePlan } | { ok: false; reason: string }

/**
 * 采样元数据（AI 表格工具治理方案 §4.2）：**只有计数与标识，没有业务值**。
 *
 * <p>
 * 随回灌体上送后端（`sheetMeta`），后端白名单过滤后落 `tool_call_end` 的 additive 字段与
 * `ai:safety:` 台账。字段取值一律是整数/枚举/计划 id —— 这里**多一个 `values` 就会变成 D2 泄漏**，
 * 故类型上就不给业务值留位置。
 */
export interface SheetToolMeta {
  /** D2 = 有业务值出域（采样行 > 0 或写侧样例 > 0）；D1 = 只有结构/聚合/计数 */
  dataClass: 'D1' | 'D2'
  /** 本次真的送出几行采样（0 = 只回了聚合/结构） */
  sampleRows?: number
  /** 采样段字符数（与 sampleRows 合起来就是出域体积，用于核对 R3） */
  sampleChars?: number
  /** 投影列数（0–5；**选区路径可超过 5**，见方案 §8 K-11 裁决） */
  projCols?: number
  /** 表/范围总行数（采样占比：20 / 30 万 vs 20 / 30 是两件事） */
  totalRows?: number
  /** 工作区版本号（与 ctx.version 同源） */
  sheetVer?: number
  /** 写工具的 planId（把日志与预览卡、撤销补丁串起来） */
  planId?: string
  /** 写工具的预览档 */
  previewLevel?: SheetPreviewLevel
  /** 配置定义编码（D1 标识；只进台账，不进日志面） */
  defCode?: string
  /** 页面 id（D1 标识；只进台账，不进日志面） */
  page?: string
}

/**
 * 表格治理策略的**前端生效值**（`/health.sheet` 下发，缺省 = 硬顶）。
 *
 * <p>
 * 为什么必须在"数据回来之前"收紧：若只在后端回灌复核，采样行已经在模型上下文里了 ——
 * 拒一个回执并不能把它拿回来（D2 已经出域）。这是方案 §2.5 的唯一硬约束。
 */
export interface SheetPolicy {
  /** 采样行数上界（≤ 20） */
  sampleMaxRows: number
  /** 投影列数上界（≤ 5；只约束 columnCodes 与缺省投影，选区路径不适用） */
  projectionMaxColumns: number
}

/** 硬编码兜底（与治理批之前的行为逐字等价：20 行 / 5 列）。 */
export const DEFAULT_SHEET_POLICY: SheetPolicy = {
  sampleMaxRows: SHEET_TOOL_MAX_SAMPLE_ROWS,
  projectionMaxColumns: SHEET_TOOL_MAX_COLUMNS
}

let currentPolicy: SheetPolicy = { ...DEFAULT_SHEET_POLICY }

/**
 * 更新生效策略（`probeHealth` 拿到 `/health.sheet` 后调用；传 null = 回到硬顶兜底）。
 *
 * <p>只允许<b>收紧</b>：值大于硬顶或非正整数一律按硬顶/缺省计 —— 后端已经 `min()` 过一次，
 * 这里再做一次是为了"前端不信任任何外部输入"（旧后端、代理改写、手改响应都不该放大采样）。
 */
export function setSheetPolicy(policy: Partial<SheetPolicy> | null | undefined): SheetPolicy {
  const clamp = (value: unknown, hardTop: number): number => {
    const num = Number(value)
    if (!Number.isFinite(num) || num < 1) return hardTop
    return Math.min(Math.floor(num), hardTop)
  }
  currentPolicy = policy
    ? {
        sampleMaxRows: clamp(policy.sampleMaxRows, SHEET_TOOL_MAX_SAMPLE_ROWS),
        projectionMaxColumns: clamp(policy.projectionMaxColumns, SHEET_TOOL_MAX_COLUMNS)
      }
    : { ...DEFAULT_SHEET_POLICY }
  return { ...currentPolicy }
}

/** 当前生效策略（只读副本）。 */
export function effectiveSheetPolicy(): SheetPolicy {
  return { ...currentPolicy }
}

type SheetArgs = Record<string, unknown>

// ── 尺寸闸（唯一出口） ───────────────────────────────────────────────────────

export interface CapSheetInput {
  lines: string[]
  total: number
  returned: number
  unit: '行' | '处' | '组' | '格' | '列'
  /** `whole` 模式下：前 N 行属于表头（不占“已回 N 单位”的计数）。 */
  prefixLines?: number
}

/**
 * 尺寸闸单点：行数优先于字符数（沿用 `ToolResultLimiter` 口径）。
 *
 * - 行数先截到 20（`limits.rows`），再按字符截到 2000（`limits.chars`）；
 * - 两者都触发时报**最先触发**的那一个（rows）；
 * - “共 N”恒在末尾（即便未截断）；
 * - 截断必有标记；
 * - `limits.whole = true`：调用方把**整条结果**（表头 + 明细）一次交进来，
 *   字符闸对“正文 + 截断标记 + 尾行”整体生效（缺省只对明细块生效，保留旧口径）。
 */
export function capSheetResult(
  input: CapSheetInput,
  limits: { rows?: number; chars?: number; whole?: boolean } = {}
): { text: string; truncated: boolean; truncatedReason?: 'rows' | 'chars' } {
  const rowLimit = Math.max(1, limits.rows ?? SHEET_TOOL_MAX_SAMPLE_ROWS)
  const charLimit = Math.max(1, limits.chars ?? SHEET_TOOL_MAX_RESULT_CHARS)
  const originalLines = input.lines.length
  const originalChars = input.lines.reduce((sum, line) => sum + line.length + 1, 0)

  let reason: 'rows' | 'chars' | undefined
  let kept = input.lines
  if (!limits.whole && input.lines.length > rowLimit) {
    kept = input.lines.slice(0, rowLimit)
    reason = 'rows'
  }
  let body = kept.join('\n')
  let returned = Math.min(input.returned, kept.length)
  const tail = (count: number): string => `共 ${input.total} ${input.unit}，本次回 ${count} ${input.unit}`
  const charsMarker = (count: number): string =>
    `…[已截断：原 ${originalLines} 行 / ${originalChars} 字符，已回 ${count} ${input.unit}]`
  if (limits.whole) {
    // 整条结果计入字符闸：先按标记 + 尾行预留字数，再从末尾整行回退。
    for (let attempt = 0; attempt < 4; attempt += 1) {
      const marker = charsMarker(returned)
      const budget = charLimit - marker.length - tail(returned).length - 2
      if (body.length <= budget) break
      const clipped = budget > 0 ? body.slice(0, budget) : ''
      const lastBreak = clipped.lastIndexOf('\n')
      body = lastBreak > 0 ? clipped.slice(0, lastBreak) : clipped
      reason = 'chars'
      const keptLines = body === '' ? 0 : body.split('\n').length
      const nextReturned = Math.min(input.returned, Math.max(0, keptLines - (input.prefixLines ?? 0)))
      if (nextReturned === returned) break
      returned = nextReturned
    }
  } else if (body.length > charLimit) {
    const clipped = body.slice(0, charLimit)
    const lastBreak = clipped.lastIndexOf('\n')
    body = lastBreak > 0 ? clipped.slice(0, lastBreak) : clipped
    if (reason === undefined) {
      reason = 'chars'
    }
  }
  const parts: string[] = []
  if (body !== '') {
    parts.push(body)
  }
  if (reason !== undefined) {
    const what = reason === 'rows'
      ? `原 ${originalLines} 行 / ${originalChars} 字符，已回 ${Math.min(rowLimit, originalLines)} 行`
      : `原 ${originalLines} 行 / ${originalChars} 字符，已回 ${returned} ${input.unit}`
    parts.push(`…[已截断：${what}]`)
  }
  parts.push(tail(returned))
  return {
    text: parts.join('\n'),
    truncated: reason !== undefined,
    ...(reason !== undefined ? { truncatedReason: reason } : {})
  }
}

// ── 条件求值器（11 算子，与后端 ConditionEvaluator 同语义） ────────────────────

function textOf(value: unknown): string | null {
  if (value === null || value === undefined) {
    return null
  }
  return String(value)
}

function isBlank(value: unknown): boolean {
  const text = textOf(value)
  return text === null || text.trim() === ''
}

function compareValues(a: string, b: string): number {
  if (/^\d{4}-\d{1,2}-\d{1,2}$/.test(a) && /^\d{4}-\d{1,2}-\d{1,2}$/.test(b)) {
    const left = Date.parse(`${a}T00:00:00`)
    const right = Date.parse(`${b}T00:00:00`)
    if (!Number.isNaN(left) && !Number.isNaN(right)) {
      return left - right
    }
  }
  const leftNum = Number(a)
  const rightNum = Number(b)
  if (a.trim() !== '' && b.trim() !== '' && Number.isFinite(leftNum) && Number.isFinite(rightNum)) {
    return leftNum - rightNum
  }
  return a < b ? -1 : a > b ? 1 : 0
}

/**
 * 单个字段条件是否命中（与后端 `ConditionEvaluator#matchesOp` 逐 case 对齐）。
 *
 * `scopeKeys` 在表格面忽略 —— 它是服务端地区/项目范围过滤，屏幕表已限定单一 def。
 */
export function matchesCondition(row: Record<string, unknown>, condition: FieldQueryCondition | null | undefined): boolean {
  if (!condition || !Array.isArray(condition.fields)) {
    return true
  }
  for (const field of condition.fields) {
    const operator = String(field.operator ?? '').toUpperCase()
    if (operator === '') {
      continue
    }
    const actual = textOf(row[field.fieldCode])
    const expected = textOf(field.value)
    switch (operator) {
      case 'EMPTY':
        if (!(actual === null || actual.trim() === '')) return false
        break
      case 'NOT_EMPTY':
        if (!(actual !== null && actual.trim() !== '')) return false
        break
      case 'EQ':
        if (actual === null || expected === null || actual !== expected) return false
        break
      case 'NE':
        if (actual !== null && expected !== null && actual === expected) return false
        break
      case 'CONTAINS':
      case 'LIKE':
        if (actual === null || expected === null || !actual.includes(expected)) return false
        break
      case 'STARTS_WITH':
        if (actual === null || expected === null || !actual.startsWith(expected)) return false
        break
      case 'IN': {
        if (actual === null || field.value === null || field.value === undefined) return false
        const list = Array.isArray(field.value) ? field.value : [field.value]
        if (!list.some((item) => item !== null && item !== undefined && String(item) === actual)) return false
        break
      }
      case 'GT':
      case 'GTE':
      case 'LT':
      case 'LTE': {
        if (actual === null || expected === null) return false
        const cmp = compareValues(actual, expected)
        if (operator === 'GT' && cmp <= 0) return false
        if (operator === 'GTE' && cmp < 0) return false
        if (operator === 'LT' && cmp >= 0) return false
        if (operator === 'LTE' && cmp > 0) return false
        break
      }
      default:
        return false
    }
  }
  return true
}

// ── 列/行解析 ────────────────────────────────────────────────────────────────

function normalizeHeader(value: unknown): string {
  return String(value ?? '')
    .trim()
    .replace(/^\*\s*/, '')
    .replace(/（/g, '(')
    .replace(/）/g, ')')
    .toLowerCase()
}

interface ColumnMap {
  byCode: Map<string, number>
  byLabel: Map<string, number>
}

function columnMap(ctx: SheetContext): ColumnMap {
  const byCode = new Map<string, number>()
  const byLabel = new Map<string, number>()
  const columns = Math.min(ctx.adapter.colCount(), ctx.fields.length)
  for (let col = 0; col < columns; col += 1) {
    const field = ctx.fields[col]
    if (!field) continue
    const header = normalizeHeader(ctx.adapter.header(col))
    byCode.set(normalizeHeader(field.code), col)
    byLabel.set(normalizeHeader(field.label), col)
    if (header !== '') {
      // 表头即字段标签（必填带 `*` 前缀已归一）⇒ 优先按表头定位；表头被改名时仍可按字段顺序兜底
      byLabel.set(header, col)
    }
  }
  return { byCode, byLabel }
}

function resolveColumn(ctx: SheetContext, code: string): number | null {
  const map = columnMap(ctx)
  const key = normalizeHeader(code)
  return map.byCode.get(key) ?? map.byLabel.get(key) ?? null
}

function columnCode(ctx: SheetContext, col: number): string {
  return ctx.fields[col]?.code ?? `col${col}`
}

function fieldOfColumn(ctx: SheetContext, col: number): ConfigField | null {
  return ctx.fields[col] ?? null
}

function rowRecord(ctx: SheetContext, columns: readonly number[], values: readonly unknown[]): Record<string, unknown> {
  const record: Record<string, unknown> = {}
  columns.forEach((col, index) => {
    record[columnCode(ctx, col)] = values[index]
  })
  return record
}

interface RowRun {
  start: number
  length: number
}

/** 升序行下标 → 连续段（每段一次 `getArray`）；乱序/重复行各自成段，结果口径不变。 */
function rowRuns(rows: readonly number[]): RowRun[] {
  const runs: RowRun[] = []
  for (const row of rows) {
    const last = runs[runs.length - 1]
    if (last && row === last.start + last.length) {
      last.length += 1
    } else {
      runs.push({ start: row, length: 1 })
    }
  }
  return runs
}

interface ColumnRun {
  start: number
  cols: number[]
}

function columnRuns(columns: readonly number[]): ColumnRun[] {
  const runs: ColumnRun[] = []
  for (const col of columns) {
    const last = runs[runs.length - 1]
    const prev = last ? last.cols[last.cols.length - 1] : undefined
    if (last && prev !== undefined && col === prev + 1) {
      last.cols.push(col)
    } else {
      runs.push({ start: col, cols: [col] })
    }
  }
  return runs
}

/**
 * 批量逐行读（`SheetAdapter#getArray`，设计稿 §3.6）：行连续段 × 列连续段，一段一次调用。
 *
 * 大表上把「每格一次读」压成「每 500 行每列段一次读」——与业界 batchGet 同口径；
 * 回调拿到的是与 `columns` **同序对齐**的值数组（缺失格为 `undefined`）。
 *
 * <p>
 * AI 表格工具治理批（§6.2 提前中止）：回调可以返回 {@code false} 要求<b>立刻停止扫描</b>。
 * 计数快路径在 {@code HARD_MAX + 1} 处这样退出 —— 否则"300 万格"要扫完整张表才报错。
 * 中止粒度是读批边界（最多多扫 {@value SHEET_READ_BATCH_ROWS} 行）：逐格判退会让
 * batchGet 退化成逐格读，代价远大于少扫几百行。
 */
function scanRows(
  ctx: SheetContext,
  rows: readonly number[],
  columns: readonly number[],
  visit: (row: number, values: readonly unknown[]) => void | boolean
): void {
  if (rows.length === 0 || columns.length === 0) {
    return
  }
  const colIndex = new Map<number, number>()
  columns.forEach((col, index) => colIndex.set(col, index))
  const runs = columnRuns(columns)
  outer: for (const run of rowRuns(rows)) {
    for (let offset = 0; offset < run.length; offset += SHEET_READ_BATCH_ROWS) {
      const span = Math.min(SHEET_READ_BATCH_ROWS, run.length - offset)
      const buffer: unknown[][] = Array.from({ length: span }, () => new Array<unknown>(columns.length).fill(undefined))
      for (const colRun of runs) {
        const grid = ctx.adapter.getArray(run.start + offset, colRun.start, span, colRun.cols.length)
        for (let r = 0; r < span; r += 1) {
          const line = grid[r]
          if (!Array.isArray(line)) continue
          for (let c = 0; c < colRun.cols.length; c += 1) {
            const col = colRun.cols[c]
            if (col === undefined) continue
            const index = colIndex.get(col)
            if (index !== undefined) {
              const target = buffer[r]
              if (target) target[index] = line[c]
            }
          }
        }
      }
      for (let r = 0; r < span; r += 1) {
        if (visit(run.start + offset + r, buffer[r] ?? []) === false) {
          break outer
        }
      }
    }
  }
}

function allColumnsOf(ctx: SheetContext): number[] {
  const count = ctx.fields.length > 0 ? ctx.fields.length : ctx.adapter.colCount()
  return Array.from({ length: count }, (_, index) => index)
}

function allRowIndexes(ctx: SheetContext): number[] {
  return Array.from({ length: totalRows(ctx) }, (_, index) => index)
}

function excelDisplay(row: number): string {
  return String(excelRowNumber(row))
}

/** 单元格地址（A1 形态：列字母 + Excel 行号；表头为第 1 行）。 */
export function cellAddress(col: number, row: number): string {
  return `${columnLetter(col)}${excelRowNumber(row)}`
}

function cellDisplay(value: unknown): string {
  const text = textOf(value)
  return text === null ? '' : text
}

// ── scope 解析（selection 优先、predicate 次之、rows 限同次调用） ──────────────

interface ResolvedScope {
  scope: string
  rows: number[]
  summary: string
  predicateSummary: string
}

function totalRows(ctx: SheetContext): number {
  return Math.max(0, ctx.adapter.rowCount())
}

function selectionRows(ctx: SheetContext): number[] {
  const selection = ctx.adapter.selection()
  if (!selection) {
    return []
  }
  const rows: number[] = []
  const end = Math.min(totalRows(ctx), selection.row + Math.max(1, selection.rowCount))
  for (let row = Math.max(0, selection.row); row < end; row += 1) {
    rows.push(row)
  }
  return rows
}

function parseRowRange(value: unknown, maxRows: number): number[] | null {
  let start: number | null = null
  let end: number | null = null
  if (Array.isArray(value) && value.length === 2) {
    start = Number(value[0])
    end = Number(value[1])
  } else if (typeof value === 'string') {
    const matched = /^\s*(\d+)\s*[-~至]\s*(\d+)\s*$/.exec(value)
    if (matched) {
      start = Number(matched[1])
      end = Number(matched[2])
    }
  }
  if (start === null || end === null || !Number.isFinite(start) || !Number.isFinite(end)) {
    return null
  }
  if (start < 2 || end < start) {
    return null
  }
  const rows: number[] = []
  for (let excelRow = start; excelRow <= end; excelRow += 1) {
    const index = excelRow - 2
    if (index >= maxRows) break
    rows.push(index)
  }
  return rows
}

function resolveScope(
  ctx: SheetContext,
  args: SheetArgs,
  options: { defaultScope: string; columns?: readonly number[]; requirePredicateForPredicate?: boolean }
): { ok: true; resolved: ResolvedScope } | { ok: false; reason: string } {
  const rawScope = typeof args.scope === 'string' && args.scope !== '' ? args.scope : options.defaultScope
  const predicate = (args.predicate ?? null) as FieldQueryCondition | null
  const predicateSummary = predicate && Array.isArray(predicate.fields) && predicate.fields.length > 0
    ? `条件「${conditionSummary(predicate)}」`
    : ''
  const allRows = allRowIndexes(ctx)
  const filterByPredicate = (rows: number[]): number[] => {
    if (!predicate || !Array.isArray(predicate.fields) || predicate.fields.length === 0) {
      return rows
    }
    const columns = allColumnsOf(ctx)
    const kept: number[] = []
    scanRows(ctx, rows, columns, (row, values) => {
      if (matchesCondition(rowRecord(ctx, columns, values), predicate)) kept.push(row)
    })
    return kept
  }

  switch (rawScope) {
    case 'sheet': {
      const rows = filterByPredicate(allRows)
      return { ok: true, resolved: { scope: 'sheet', rows, summary: `${ctx.defCode} 全表`, predicateSummary } }
    }
    case 'columns': {
      const columns = options.columns ?? []
      const rows = filterByPredicate(allRows)
      const labels = columns.map((col) => columnCode(ctx, col)).join('、')
      return { ok: true, resolved: { scope: 'columns', rows, summary: `${ctx.defCode} 列 ${labels}`, predicateSummary } }
    }
    case 'rows': {
      const rows = parseRowRange(args.rowRange, totalRows(ctx))
      if (rows === null) {
        return { ok: false, reason: 'rowRange 需要 Excel 行号区间（如 2-41，表头为第 1 行），且起始行不小于 2。' }
      }
      const filtered = filterByPredicate(rows)
      return {
        ok: true,
        resolved: {
          scope: 'rows',
          rows: filtered,
          summary: `${ctx.defCode} 第 ${rows[0] !== undefined ? excelDisplay(rows[0]) : ''}-${rows[rows.length - 1] !== undefined ? excelDisplay(rows[rows.length - 1]) : ''} 行`,
          predicateSummary
        }
      }
    }
    case 'predicate': {
      if (!predicate || !Array.isArray(predicate.fields) || predicate.fields.length === 0) {
        return { ok: false, reason: 'scope=predicate 需要同时给出 predicate（字段条件），不猜条件。' }
      }
      const rows = filterByPredicate(allRows)
      return { ok: true, resolved: { scope: 'predicate', rows, summary: `${ctx.defCode} 条件命中`, predicateSummary } }
    }
    case 'selection':
    default: {
      if (rawScope !== 'selection') {
        return { ok: false, reason: `scope 只能是 selection/sheet/columns/rows/predicate，实际为 ${rawScope}。` }
      }
      const rows = selectionRows(ctx)
      if (rows.length === 0) {
        return { ok: false, reason: '当前表格没有选区。请先选中要操作的行（或用条件描述哪些行），本次未做任何修改。' }
      }
      const filtered = filterByPredicate(rows)
      return { ok: true, resolved: { scope: 'selection', rows: filtered, summary: `${ctx.defCode} 选区`, predicateSummary } }
    }
  }
}

// ── 公共小工具 ───────────────────────────────────────────────────────────────

function parseLimit(value: unknown, fallback: number, max: number): number | null {
  if (value === undefined || value === null || value === '') {
    return fallback
  }
  const num = Number(value)
  if (!Number.isInteger(num) || num < 1 || num > max) {
    return null
  }
  return num
}

function keyColumns(ctx: SheetContext): number[] {
  const keys: number[] = []
  ctx.fields.forEach((field, col) => {
    if (field.key) keys.push(col)
  })
  if (keys.length === 0 && ctx.fields.length > 0) {
    keys.push(0)
  }
  return keys.slice(0, SHEET_FINGERPRINT_COLUMNS)
}

function fingerprintRows(ctx: SheetContext, rows: readonly number[], columns: readonly number[]): string[] {
  const fingerprints: string[] = []
  scanRows(ctx, rows, columns, (_row, values) => {
    fingerprints.push(values.map((value) => cellDisplay(value).trim()).join('\u0001'))
  })
  return fingerprints
}

function validateDefCode(ctx: SheetContext, args: SheetArgs): string | null {
  const defCode = args.defCode
  if (typeof defCode !== 'string' || defCode === '') {
    return '缺少 defCode：请指明要操作哪个配置的表格。'
  }
  if (defCode !== ctx.defCode) {
    return `当前激活的表格是 ${ctx.defCode}，不是 ${defCode}。请先切换到该配置的标签页。`
  }
  return null
}

/** 只读壳的写路径拦截文案（单一源，走 locales；读工具不再被它拦）。 */
export function sheetReadOnlyWriteBlocked(): string {
  return i18n.global.t('sheet.readOnlyWriteBlocked')
}

function requireReadable(ctx: SheetContext): string | null {
  if (ctx.readOnly) {
    return sheetReadOnlyWriteBlocked()
  }
  return null
}

function changesToUpdates(changes: readonly SheetChange[]): SheetCellUpdate[] {
  return changes.map((change) => ({ row: change.row, col: change.col, value: change.newValue }))
}

function chunkUpdates(updates: SheetCellUpdate[], size = SHEET_WRITE_CHUNK_ROWS): SheetCellUpdate[][] {
  if (updates.length === 0) return []
  const chunks: SheetCellUpdate[][] = []
  for (let index = 0; index < updates.length; index += size) {
    chunks.push(updates.slice(index, index + size))
  }
  return chunks
}

// ── 读侧：摘要 ───────────────────────────────────────────────────────────────

/**
 * 投影列：显式 `columnCodes` 优先；缺省时收窄为「主键列 + 前 4 列」（≤5 列），
 * 不再回全部列 —— 全列投影会把整行业务值带进模型。
 *
 * <p>
 * 两条路径的上界 = `min(硬顶, app.ai.sheet.projection-max-columns)`。
 * **选区分支不设列上限**（2026-10-22 裁决：用户亲手框选 = 逐次授权；方案 §8 K-11 / §10 O-9）——
 * 行数（≤20）与字符数（≤2000）两道硬闸对选区同样生效。
 */
function projectedColumns(ctx: SheetContext, args: SheetArgs, resolved: ResolvedScope): number[] {
  const policy = effectiveSheetPolicy()
  if (Array.isArray(args.columnCodes)) {
    return (args.columnCodes as unknown[])
      .map((code) => resolveColumn(ctx, String(code)))
      .filter((col): col is number => col !== null)
  }
  const selection = ctx.adapter.selection()
  if (resolved.scope === 'selection' && selection) {
    return Array.from({ length: Math.max(0, selection.colCount) }, (_, index) => selection.col + index)
      .filter((col) => col >= 0 && col < ctx.adapter.colCount())
  }
  const columnLimit = policy.projectionMaxColumns
  const defaults: number[] = []
  ctx.fields.forEach((field, index) => {
    if (field.key && defaults.length < columnLimit) defaults.push(index)
  })
  for (let col = 0; col < ctx.adapter.colCount() && defaults.length < columnLimit; col += 1) {
    if (!defaults.includes(col)) defaults.push(col)
  }
  return defaults
}

/**
 * 读侧元数据的公共底座（无采样行的读工具用 D1）。
 *
 * <p>有采样的读工具（摘要 / 检索 / 明细）自行覆盖 `sampleRows` / `sampleChars` / `projCols`。
 */
function readMeta(ctx: SheetContext, dataClass: 'D1' | 'D2'): SheetToolMeta {
  return { dataClass, totalRows: totalRows(ctx), sheetVer: ctx.version }
}

/**
 * `read_sheet_summary`：回投影列的 ≤20 行采样 + 规模/空值/重复摘要。
 *
 * <p>
 * **离开浏览器的业务数据 = 这 ≤20 行采样**（R3 允许的唯一 D2 面，授权见 `docs/evidence` 的
 * D2 授权件）；整表不出域。尺寸闸（{@link capSheetResult}）覆盖整条结果（表头/摘要/采样/尾行）。
 */
export function readSheetSummary(ctx: SheetContext, args: SheetArgs): SheetToolOutcome {
  const defError = validateDefCode(ctx, args)
  if (defError) return { ok: false, reason: defError }
  const policy = effectiveSheetPolicy()
  const limit = parseLimit(args.limit, Math.min(SHEET_TOOL_DEFAULT_SAMPLE_ROWS, policy.sampleMaxRows),
    SHEET_TOOL_MAX_SAMPLE_ROWS)
  if (limit === null) {
    return { ok: false, reason: `limit 只能是 1 到 ${SHEET_TOOL_MAX_SAMPLE_ROWS} 的整数，本次未做任何修改。` }
  }
  // 生效上界（管理员可收紧；只能收紧不能放大）：args 合法但超过有效值时按有效值取，不拒绝。
  const effectiveLimit = Math.min(limit, policy.sampleMaxRows)
  if (Array.isArray(args.columnCodes)) {
    if (args.columnCodes.length > SHEET_TOOL_MAX_COLUMNS) {
      return {
        ok: false,
        reason: `columnCodes 最多 ${SHEET_TOOL_MAX_COLUMNS} 列，请缩小投影范围（或交给缺省投影：主键列 + 前 4 列）。`
      }
    }
    if (args.columnCodes.length > policy.projectionMaxColumns) {
      // 具名拒绝而不是静默截断：静默截断会让"列 A 没读到"被误读成"列 A 不存在"
      return {
        ok: false,
        reason: `columnCodes 给了 ${args.columnCodes.length} 列，超过管理员设置的投影列上限 ${policy.projectionMaxColumns} 列；请缩小到 ${policy.projectionMaxColumns} 列以内（app.ai.sheet.projection-max-columns）。`
      }
    }
    for (const code of args.columnCodes as unknown[]) {
      if (resolveColumn(ctx, String(code)) === null) {
        return { ok: false, reason: `投影列 ${String(code)} 不在当前表格中。` }
      }
    }
  }
  const scope = resolveScope(ctx, args, { defaultScope: 'selection' })
  if (!scope.ok) return scope
  const { resolved } = scope
  const columns = projectedColumns(ctx, args, resolved)
  const total = totalRows(ctx)

  const lines: string[] = []
  lines.push(`表格：${ctx.defCode}（工作表「${ctx.sheetName}」）  版本 #${ctx.version}`)
  lines.push(`范围：scope=${resolved.scope}${resolved.predicateSummary ? ` · ${resolved.predicateSummary}` : ''}（命中 ${resolved.rows.length} 行 / 共 ${total} 行）`)
  lines.push(`规模：数据行 ${total} 行 × ${ctx.adapter.colCount()} 列`)
  if (columns.length > 0) {
    const labels = columns.slice(0, 8).map((col) => `${columnLetter(col)}=${columnCode(ctx, col)}(${fieldOfColumn(ctx, col)?.label ?? ''})`).join('、')
    lines.push(`列（前 ${Math.min(8, columns.length)} 列 / 共 ${columns.length} 列；A1=列字母+Excel 行号）：${labels}`)
  }

  const blankColumns = columns.slice(0, 8)
  if (blankColumns.length > 0) {
    const blanks = new Array<number>(blankColumns.length).fill(0)
    scanRows(ctx, allRowIndexes(ctx), blankColumns, (_row, values) => {
      values.forEach((value, index) => {
        if (isBlank(value)) blanks[index] = (blanks[index] ?? 0) + 1
      })
    })
    const blankParts = blankColumns.map((col, index) => `${columnCode(ctx, col)} 空 ${blanks[index] ?? 0} 行`)
    lines.push(`空值：${blankParts.join('；')}`)
  }

  const keys = ctx.fields.filter((field) => field.key)
  if (keys.length > 0) {
    const keyColumns = keys
      .map((field) => resolveColumn(ctx, field.code))
      .filter((col): col is number => col !== null)
    const seen = new Set<string>()
    let duplicated = 0
    scanRows(ctx, allRowIndexes(ctx), keyColumns, (_row, values) => {
      const text = values.map((value) => cellDisplay(value).trim()).join('|')
      if (text.replace(/\|/g, '').trim() === '') return
      if (seen.has(text)) duplicated += 1
      else seen.add(text)
    })
    lines.push(`重复主键：${keys.map((field) => field.code).join('+')} 重复 ${duplicated} 组`)
  }

  const sampleRows = resolved.rows.slice(0, effectiveLimit)
  lines.push(`采样（前 ${sampleRows.length} 行，投影 ${columns.length} 列）：`)
  const sampleLines: string[] = []
  scanRows(ctx, sampleRows, columns, (row, values) => {
    const cells = columns.map((col, index) => `${cellAddress(col, row)} ${columnCode(ctx, col)}=${cellDisplay(values[index])}`)
    sampleLines.push(`  第 ${excelDisplay(row)} 行 | ${cells.join(' | ')}`)
  })
  const hidden = Math.max(0, ctx.fields.length - columns.length)
  const allLines = [...lines, ...sampleLines]
  if (hidden > 0) {
    allLines.push(`另有 ${hidden} 列未显示`)
  }
  const capped = capSheetResult({ lines: allLines, total, returned: sampleLines.length, unit: '行', prefixLines: lines.length }, { whole: true })
  // 采样元数据（§4.2）：**计数上界**，不是精确值 —— 截断发生时按截断前计（宁可多报，不少报 D2 体积）
  const sampleChars = sampleLines.reduce((sum, line) => sum + line.length + 1, 0)
  return {
    ok: true,
    text: capped.text,
    meta: {
      dataClass: sampleLines.length > 0 ? 'D2' : 'D1',
      sampleRows: sampleLines.length,
      sampleChars,
      projCols: columns.length,
      totalRows: total,
      sheetVer: ctx.version
    }
  }
}

// ── 读侧：聚合（兼作计数级预览引擎） ──────────────────────────────────────────

interface MetricValue {
  text: string
  /** 排序键：count/distinct/sum/blankRate 为数值；min/max 无天然数值序（用 text 比较）。 */
  order: number | null
}

function metricFromValues(values: readonly unknown[], metric: string): MetricValue {
  switch (metric) {
    case 'count':
      return { text: `${values.length}`, order: values.length }
    case 'distinct': {
      const size = new Set(values.map((value) => cellDisplay(value))).size
      return { text: `${size}`, order: size }
    }
    case 'blankRate': {
      const blanks = values.filter((value) => isBlank(value)).length
      const rate = values.length === 0 ? 0 : blanks / values.length
      return { text: `${Math.round(rate * 100)}%`, order: rate }
    }
    case 'sum': {
      const sum = values.reduce((acc: number, value) => {
        const num = Number(cellDisplay(value))
        return Number.isFinite(num) ? acc + num : acc
      }, 0)
      return { text: `${sum}`, order: sum }
    }
    case 'min':
    case 'max': {
      const texts = values.map((value) => cellDisplay(value)).filter((text) => text.trim() !== '')
      if (texts.length === 0) return { text: '—', order: null }
      const best = texts.reduce((acc, text) => {
        const cmp = compareValues(text, acc)
        return (metric === 'min' ? cmp < 0 : cmp > 0) ? text : acc
      })
      return { text: best, order: null }
    }
    default:
      return { text: '', order: null }
  }
}

/** 分组排序口径（业界 pivot 默认：按首个度量降序，再取前 N 组）。 */
type AggregateSort =
  | { kind: 'metric'; metric: string; desc: boolean }
  | { kind: 'label'; desc: boolean }
  | { kind: 'none' }

const AGGREGATE_METRIC_NAMES = new Set(['count', 'distinct', 'blankRate', 'min', 'max', 'sum'])

function parseAggregateSort(
  raw: unknown,
  metrics: readonly string[]
): { ok: true; sort: AggregateSort } | { ok: false; reason: string } {
  const value = typeof raw === 'string' ? raw : ''
  const metricSort = (metric: string, desc: boolean): { ok: true; sort: AggregateSort } | { ok: false; reason: string } => {
    if (!metrics.includes(metric)) {
      return {
        ok: false,
        reason: `sort=${metric} 需要把 ${metric} 列进 metrics（当前 metrics 为 ${metrics.join('/')}）。`
      }
    }
    return { ok: true, sort: { kind: 'metric', metric, desc } }
  }
  switch (value) {
    case '':
    case 'metric':
    case 'metricDesc':
      return { ok: true, sort: { kind: 'metric', metric: metrics[0] ?? 'count', desc: true } }
    case 'metricAsc':
      return { ok: true, sort: { kind: 'metric', metric: metrics[0] ?? 'count', desc: false } }
    case 'label':
    case 'labelAsc':
      return { ok: true, sort: { kind: 'label', desc: false } }
    case 'labelDesc':
      return { ok: true, sort: { kind: 'label', desc: true } }
    case 'none':
      return { ok: true, sort: { kind: 'none' } }
    default:
      if (AGGREGATE_METRIC_NAMES.has(value)) {
        return metricSort(value, true)
      }
      return {
        ok: false,
        reason: `sort 只支持 metric/metricDesc/metricAsc/labelAsc/labelDesc/none，或 metrics 里的度量名`
          + `（count/distinct/blankRate/min/max/sum），实际为 ${value}。`
      }
  }
}

function aggregateSortLine(sort: AggregateSort, limit: number): string {
  if (sort.kind === 'none') return '排序：保持原顺序（未排序）'
  if (sort.kind === 'label') return `排序：按分组标签${sort.desc ? '降序' : '升序'}`
  return `排序：按 ${sort.metric} ${sort.desc ? '降序' : '升序'}（取前 ${limit} 组）`
}

interface GroupEntry {
  label: string
  rows: number[]
  values: unknown[]
  metrics: Map<string, MetricValue>
}

function compareGroupEntries(left: GroupEntry, right: GroupEntry, sort: AggregateSort): number {
  if (sort.kind === 'metric') {
    const leftMetric = left.metrics.get(sort.metric) ?? { text: '', order: null }
    const rightMetric = right.metrics.get(sort.metric) ?? { text: '', order: null }
    const cmp = leftMetric.order !== null && rightMetric.order !== null
      ? leftMetric.order - rightMetric.order
      : compareValues(leftMetric.text, rightMetric.text)
    // 并列按标签升序，保证输出稳定（不受底层遍历顺序影响）
    if (cmp !== 0) return sort.desc ? -cmp : cmp
    return compareValues(left.label, right.label)
  }
  if (sort.kind === 'label') {
    const cmp = compareValues(left.label, right.label)
    return sort.desc ? -cmp : cmp
  }
  return 0
}

export function aggregateSheet(ctx: SheetContext, args: SheetArgs): SheetToolOutcome {
  const defError = validateDefCode(ctx, args)
  if (defError) return { ok: false, reason: defError }
  const limit = parseLimit(args.limit, SHEET_AGGREGATE_MAX_GROUPS, SHEET_AGGREGATE_MAX_GROUPS)
  if (limit === null) {
    return { ok: false, reason: `limit 只能是 1 到 ${SHEET_AGGREGATE_MAX_GROUPS} 的整数。` }
  }
  const metrics = Array.isArray(args.metrics) && args.metrics.length > 0
    ? (args.metrics as unknown[]).map((item) => String(item))
    : ['count']
  const allowed = AGGREGATE_METRIC_NAMES
  for (const metric of metrics) {
    if (!allowed.has(metric)) {
      return { ok: false, reason: `metrics 只支持 count/distinct/blankRate/min/max/sum，实际为 ${metric}。` }
    }
  }
  const parsedSort = parseAggregateSort(args.sort, metrics)
  if (!parsedSort.ok) return { ok: false, reason: parsedSort.reason }
  const sort = parsedSort.sort
  const groupBy = typeof args.groupBy === 'string' && args.groupBy !== '' ? resolveColumn(ctx, args.groupBy) : null
  if (typeof args.groupBy === 'string' && args.groupBy !== '' && groupBy === null) {
    return { ok: false, reason: `分组列 ${args.groupBy} 不在当前表格中。` }
  }
  const metricCol = typeof args.columnCode === 'string' && args.columnCode !== ''
    ? resolveColumn(ctx, args.columnCode)
    : null
  if (typeof args.columnCode === 'string' && args.columnCode !== '' && metricCol === null) {
    return { ok: false, reason: `度量列 ${args.columnCode} 不在当前表格中。` }
  }
  const scope = resolveScope(ctx, args, { defaultScope: 'sheet' })
  if (!scope.ok) return scope
  const { resolved } = scope

  const lines: string[] = []
  lines.push(`范围：${resolved.predicateSummary || '全部数据'}（命中 ${resolved.rows.length} 行 / 共 ${totalRows(ctx)} 行）`)

  if (groupBy === null) {
    const values = readSingleColumn(ctx, resolved.rows, metricCol ?? 0)
    const parts = metrics.map((metric) => `${metric}=${metricFromValues(values, metric).text}`)
    lines.push(`总计：${resolved.rows.length} 行${metricCol === null ? '' : ` · ${parts.join(' · ')}`}`)
    const capped = capSheetResult({ lines, total: 1, returned: 1, unit: '组' }, { whole: true })
    // 聚合只回聚合值（0 行原文）⇒ D1；totalRows 报"范围总行数"，让"聚合了多大一张表"可追
    return { ok: true, text: capped.text, meta: readMeta(ctx, 'D1') }
  }

  const valueCol = metricCol ?? 0
  const scanColumns = Array.from(new Set([groupBy, valueCol]))
  const groupIdx = scanColumns.indexOf(groupBy)
  const valueIdx = scanColumns.indexOf(valueCol)
  const groups = new Map<string, GroupEntry>()
  scanRows(ctx, resolved.rows, scanColumns, (row, values) => {
    // 与写侧分布共用同一归一化（groupKeyOf）：换一处 key 规则就会让两边组数对不上
    const label = groupKeyOf(values[groupIdx])
    const entry = groups.get(label) ?? { label, rows: [], values: [], metrics: new Map<string, MetricValue>() }
    entry.rows.push(row)
    entry.values.push(values[valueIdx])
    groups.set(label, entry)
  })
  const entries = [...groups.values()]
  for (const entry of entries) {
    for (const metric of metrics) {
      entry.metrics.set(metric, metricFromValues(entry.values, metric))
    }
  }
  if (sort.kind !== 'none') {
    entries.sort((left, right) => compareGroupEntries(left, right, sort))
  }
  const shown = entries.slice(0, limit)
  lines.push(aggregateSortLine(sort, limit))
  const groupLines = shown.map((entry) => {
    if (metricCol === null) {
      return `  ${entry.label}：${entry.rows.length} 行`
    }
    const parts = metrics.map((metric) => `${metric}=${entry.metrics.get(metric)?.text ?? ''}`)
    return `  ${entry.label}：${entry.rows.length} 行 · ${parts.join(' · ')}`
  })
  lines.push(`分组：${columnCode(ctx, groupBy)}`)
  const capped = capSheetResult({ lines: [...lines, ...groupLines], total: entries.length, returned: shown.length, unit: '组', prefixLines: lines.length }, { whole: true })
  return { ok: true, text: capped.text, meta: readMeta(ctx, 'D1') }
}

/** 单列批读（一次 `getArray` 扫完给定行），供总计/条件计数复用。 */
function readSingleColumn(ctx: SheetContext, rows: readonly number[], col: number): unknown[] {
  const values: unknown[] = []
  scanRows(ctx, rows, [col], (_row, cells) => {
    values.push(cells[0])
  })
  return values
}

/**
 * 分组键的**唯一归一化口径**（聚合器与写侧分布共用）：单元格显示值 trim，空 ⇒ `（空）`。
 *
 * <p>共用它是 P-05 的前提：同一份数据在 `aggregate_sheet` 与写侧分布里必须落进同一组，
 * 否则"卡上说 812 行、聚合器说 428 行"这类漂移无法解释（方案 §8 K-5）。
 */
export function groupKeyOf(value: unknown): string {
  const key = cellDisplay(value).trim()
  return key === '' ? '（空）' : key
}

/** 分组计数（写侧分布用）：键 → 行数 / 格数。 */
export interface GroupCount {
  key: string
  rows: number
  cells: number
}

/**
 * 分组计数原语：一次扫码产出「组键 → {rows, cells}」。
 *
 * <p>
 * `cellsOf(row)` 由调用方给出"该行命中了几格"（写侧分布）；不给则 `cells = rows`
 * （等价于 `aggregate_sheet` 的 `count` 口径）。与聚合器共用 {@link groupKeyOf}，
 * 排序口径也一致（rows 降序、同值按键字典序），故 P-05 可以对逐组断言。
 *
 * @param rows 参与统计的行（数据行下标）
 * @param byCol 分组列下标
 * @param cellsOf 该行的命中格数（可选）
 */
export function groupCounts(
  ctx: SheetContext,
  rows: readonly number[],
  byCol: number,
  cellsOf?: (row: number) => number
): Map<string, GroupCount> {
  const counts = new Map<string, GroupCount>()
  scanRows(ctx, rows, [byCol], (row, values) => {
    const key = groupKeyOf(values[0])
    const entry = counts.get(key) ?? { key, rows: 0, cells: 0 }
    entry.rows += 1
    entry.cells += cellsOf ? cellsOf(row) : 1
    counts.set(key, entry)
  })
  return counts
}

// ── 读侧：列映射诊断 ─────────────────────────────────────────────────────────

export function inspectColumns(ctx: SheetContext, args: SheetArgs): SheetToolOutcome {
  const defError = validateDefCode(ctx, args)
  if (defError) return { ok: false, reason: defError }
  const colCount = ctx.adapter.colCount()
  const headers = Array.from({ length: colCount }, (_, col) => ctx.adapter.header(col))
  const missing: string[] = []
  let matched = 0
  const usedColumns = new Set<number>()
  for (const field of ctx.fields) {
    const normalizedLabel = normalizeHeader(field.label)
    const normalizedCode = normalizeHeader(field.code)
    let found = -1
    for (let col = 0; col < colCount; col += 1) {
      const header = normalizeHeader(headers[col])
      if (header === normalizedLabel || header === normalizedCode) {
        found = col
        break
      }
    }
    if (found >= 0) {
      matched += 1
      usedColumns.add(found)
    } else {
      missing.push(field.label)
    }
  }
  const extras = headers
    .map((header, col) => ({ header: String(header ?? ''), col }))
    .filter((item) => !usedColumns.has(item.col) && item.header.trim() !== '')

  const suggestions: string[] = []
  for (const extra of extras) {
    const squashed = normalizeHeader(extra.header).replace(/\s+/g, '')
    if (squashed === '') continue
    const matchedField = ctx.fields.find((field) => normalizeHeader(field.label).replace(/\s+/g, '') === squashed)
      ?? ctx.fields.find((field) => normalizeHeader(field.code).replace(/\s+/g, '') === squashed)
    if (matchedField) {
      const reason = normalizeHeader(extra.header).replace(/\s+/g, '') === normalizeHeader(matchedField.label).replace(/\s+/g, '')
        ? '空白差异'
        : '大小写差异'
      suggestions.push(`列「${extra.header}」→ 字段 ${matchedField.code}（理由：${reason}）`)
    }
  }

  const lines: string[] = []
  lines.push(`已匹配 ${matched}/${ctx.fields.length} 列`)
  lines.push(`缺失：${missing.length > 0 ? missing.join('、') : '无'}`)
  lines.push(`多余：${extras.length > 0 ? extras.map((item) => item.header).join('、') : '无'}`)
  lines.push(`疑似映射（${Math.min(5, suggestions.length)} 条${suggestions.length > 5 ? `，共 ${suggestions.length} 条` : ''}）：`)
  const capped = capSheetResult({
    lines: [...lines, ...suggestions.slice(0, 5).map((line) => `  ${line}`)],
    total: suggestions.length,
    returned: Math.min(5, suggestions.length),
    unit: '列',
    prefixLines: lines.length
  }, { whole: true })
  // 列映射诊断只回列名与建议（0 行业务值）⇒ D1
  return { ok: true, text: capped.text, meta: readMeta(ctx, 'D1') }
}

// ── 读侧：本地校验摘要 ───────────────────────────────────────────────────────

export function reviewCells(ctx: SheetContext, args: SheetArgs): SheetToolOutcome {
  const defError = validateDefCode(ctx, args)
  if (defError) return { ok: false, reason: defError }
  const limit = parseLimit(args.limit, SHEET_TOOL_DEFAULT_SAMPLE_ROWS, SHEET_TOOL_MAX_SAMPLE_ROWS)
  if (limit === null) {
    return { ok: false, reason: `limit 只能是 1 到 ${SHEET_TOOL_MAX_SAMPLE_ROWS} 的整数。` }
  }
  if (args.scope !== undefined && args.scope !== 'sheet' && args.scope !== 'selection') {
    return { ok: false, reason: 'review_cells 的 scope 只能是 sheet 或 selection。' }
  }
  const scope = resolveScope(ctx, args, { defaultScope: 'sheet' })
  if (!scope.ok) return scope
  const { resolved } = scope
  const allColumns = allColumnsOf(ctx)
  const rows: Array<Record<string, unknown>> = []
  scanRows(ctx, allRowIndexes(ctx), allColumns, (_row, values) => {
    rows.push(rowRecord(ctx, allColumns, values))
  })
  const issues = validateRows(ctx.fields, rows)
  const wanted = new Set(resolved.rows.map((row) => excelRowNumber(row)))
  const filtered = issues.filter((issue) => wanted.has(issue.rowIndex))
  const fieldColumns = new Map(ctx.fields.map((field, col) => [field.code, col]))

  const byField = new Map<string, number>()
  for (const issue of filtered) {
    const label = issue.fieldLabel || issue.fieldCode
    const family = issue.reason.startsWith('主键重复') ? '主键重复' : issue.reason
    const key = `${label} ${family}`
    byField.set(key, (byField.get(key) ?? 0) + 1)
  }

  const lines: string[] = []
  lines.push(`范围：${resolved.scope === 'selection' ? '当前选区' : '整表'}${resolved.predicateSummary ? ` · ${resolved.predicateSummary}` : ''}（命中 ${resolved.rows.length} 行）`)
  lines.push(`按字段聚合：${byField.size > 0 ? [...byField.entries()].map(([key, count]) => `${key} ${count} 处`).join('、') : '无问题'}`)
  lines.push(`前 ${Math.min(limit, filtered.length)} 条（带单元格地址）：`)
  const detail = filtered.slice(0, limit).map((issue) => {
    const col = fieldColumns.get(issue.fieldCode)
    const address = col === undefined ? '' : `${cellAddress(col, issue.rowIndex - 2)} `
    return `  第 ${issue.rowIndex} 行 ${address}${issue.fieldCode}「${issue.value}」：${issue.reason}`
  })
  const capped = capSheetResult({ lines: [...lines, ...detail], total: filtered.length, returned: detail.length, unit: '处', prefixLines: lines.length }, { whole: true })
  // 明细里带 issue.value（单元格原文）⇒ 有明细即 D2；异常值也可能是业务值，不做"看起来像值"的猜测
  const detailChars = detail.reduce((sum, line) => sum + line.length + 1, 0)
  return {
    ok: true,
    text: capped.text,
    meta: {
      ...readMeta(ctx, detail.length > 0 ? 'D2' : 'D1'),
      sampleRows: detail.length,
      sampleChars: detailChars,
      projCols: new Set(filtered.slice(0, limit).map((issue) => issue.fieldCode)).size
    }
  }
}

// ── 读侧：全文检索（find_rows） ───────────────────────────────────────────────

const FIND_MATCH_MODES = new Set(['contains', 'equals', 'prefix'])

/**
 * `find_rows`：在当前范围里做**全列包含检索**，回命中行号 + 命中单元格地址 + 采样 + 共 N 行。
 *
 * 与业界「查找」同口径：默认 `contains` + 忽略大小写，`equals` 按整格相等、`prefix` 按前缀；
 * 采样恒走 {@link capSheetResult} 整条闸（≤20 行 / 2000 字符），命中总数不受采样影响。
 */
export function findRows(ctx: SheetContext, args: SheetArgs): SheetToolOutcome {
  const defError = validateDefCode(ctx, args)
  if (defError) return { ok: false, reason: defError }
  const search = typeof args.search === 'string' ? args.search : ''
  if (search.trim() === '') {
    return { ok: false, reason: 'search 必填且不能为空：请给出要查找的文字（本工具做全列包含检索），本次未做任何修改。' }
  }
  const match = typeof args.match === 'string' && args.match !== '' ? args.match : 'contains'
  if (!FIND_MATCH_MODES.has(match)) {
    return { ok: false, reason: `match 只支持 contains/equals/prefix，实际为 ${match}。` }
  }
  const ignoreCase = args.ignoreCase === undefined ? true : Boolean(args.ignoreCase)
  const limit = parseLimit(args.limit, SHEET_TOOL_DEFAULT_SAMPLE_ROWS, SHEET_TOOL_MAX_SAMPLE_ROWS)
  if (limit === null) {
    return { ok: false, reason: `limit 只能是 1 到 ${SHEET_TOOL_MAX_SAMPLE_ROWS} 的整数，本次未做任何修改。` }
  }
  let columns: number[]
  let columnLabel: string
  if (Array.isArray(args.columnCodes)) {
    if (args.columnCodes.length === 0) {
      return { ok: false, reason: 'columnCodes 不能为空数组：要么不传（检索全部列），要么给出至少 1 个列。' }
    }
    columns = []
    for (const code of args.columnCodes as unknown[]) {
      const col = resolveColumn(ctx, String(code))
      if (col === null) {
        return { ok: false, reason: `检索列 ${String(code)} 不在当前表格中。` }
      }
      columns.push(col)
    }
    columnLabel = `列 ${(args.columnCodes as unknown[]).map((code) => String(code)).join('、')}`
    columns = Array.from(new Set(columns))
  } else {
    columns = allColumnsOf(ctx)
    columnLabel = `全部 ${columns.length} 列`
  }
  const scope = resolveScope(ctx, args, { defaultScope: 'sheet' })
  if (!scope.ok) return scope
  const { resolved } = scope

  const needle = ignoreCase ? search.toLowerCase() : search
  const hits: number[] = []
  const perColumn = new Map<number, number>()
  const sampleLines: string[] = []
  scanRows(ctx, resolved.rows, columns, (row, values) => {
    const matchedCells: string[] = []
    columns.forEach((col, index) => {
      const text = cellDisplay(values[index])
      if (text === '') return
      const haystack = ignoreCase ? text.toLowerCase() : text
      const hit = match === 'equals'
        ? haystack === needle
        : match === 'prefix'
          ? haystack.startsWith(needle)
          : haystack.includes(needle)
      if (!hit) return
      perColumn.set(col, (perColumn.get(col) ?? 0) + 1)
      if (matchedCells.length < SHEET_TOOL_MAX_COLUMNS) {
        matchedCells.push(`${cellAddress(col, row)} ${columnCode(ctx, col)}=${text}`)
      }
    })
    if (matchedCells.length === 0) return
    hits.push(row)
    if (sampleLines.length < limit) {
      sampleLines.push(`  第 ${excelDisplay(row)} 行 | ${matchedCells.join(' | ')}`)
    }
  })

  const lines: string[] = []
  lines.push(`检索：${match}「${search}」（${columnLabel}，区分大小写=${!ignoreCase}）`)
  lines.push(`范围：${resolved.summary}${resolved.predicateSummary ? ` · ${resolved.predicateSummary}` : ''}（命中 ${hits.length} 行 / 共 ${resolved.rows.length} 行）`)
  const hitColumns = [...perColumn.entries()]
    .sort((left, right) => left[0] - right[0])
    .map(([col, count]) => `${columnCode(ctx, col)} ${count} 行`)
  if (hitColumns.length > 0) {
    lines.push(`命中列：${hitColumns.join('、')}`)
  }
  if (hits.length === 0) {
    lines.push('没有命中的行（可换关键字，或去掉 match/columnCodes 收窄条件）。')
  } else {
    lines.push(`采样（前 ${sampleLines.length} 行，命中单元格带地址）：`)
  }
  const capped = capSheetResult({
    lines: [...lines, ...sampleLines],
    total: hits.length,
    returned: sampleLines.length,
    unit: '行',
    prefixLines: lines.length
  }, { whole: true })
  const sampleChars = sampleLines.reduce((sum, line) => sum + line.length + 1, 0)
  return {
    ok: true,
    text: capped.text,
    meta: {
      dataClass: sampleLines.length > 0 ? 'D2' : 'D1',
      sampleRows: sampleLines.length,
      sampleChars,
      // 检索列面 = 实际扫过的列（可能全表列），不是投影点；如实报，别让"5 列"的直觉盖住真实面
      projCols: columns.length,
      totalRows: totalRows(ctx),
      sheetVer: ctx.version
    }
  }
}

// ── 写侧：计划与预览文本 ─────────────────────────────────────────────────────

let planSequence = 0

function nextPlanId(toolCallId: string): string {
  planSequence += 1
  return `${toolCallId || 'plan'}-${planSequence}`
}

// ── 预览身份（用户 2026-10-25「同文件后写可覆盖」口径） ──────────────────────
//
// previewId 只回答一件事：**这张卡还是不是当前这张表、这份内容的卡**。
// 不逐格比对（那是另一套 CAS），也不引入 summaryHash —— 身份由四个已有事实拼成：
// toolCallId（哪次调用）+ sheetInstanceId（哪张表）+ loadGeneration（第几次程序化载入）
// + scope（按什么范围算的）。上传新文件 / 重跑导出 / 程序化重载 / 换任务 / 换选区
// 都会让其中至少一项变化，应用与撤销因此 fail-closed。

let fallbackInstanceSequence = 0
const fallbackInstanceIds = new WeakMap<SheetAdapter, string>()

/** 当前表格实例身份（适配器方法优先；缺省用入参快照，再缺省按适配器身份兜底）。 */
export function sheetInstanceIdOf(ctx: SheetContext): string {
  if (typeof ctx.adapter.instanceId === 'function') {
    try {
      const value = ctx.adapter.instanceId()
      if (typeof value === 'string' && value !== '') {
        return value
      }
    } catch {
      // 适配器已不可用：回落到快照（随后 apply 会因代数/指纹不符拒绝）
    }
  }
  if (typeof ctx.sheetInstanceId === 'string' && ctx.sheetInstanceId !== '') {
    return ctx.sheetInstanceId
  }
  let fallback = fallbackInstanceIds.get(ctx.adapter)
  if (!fallback) {
    fallbackInstanceSequence += 1
    fallback = `adapter-${fallbackInstanceSequence}`
    fallbackInstanceIds.set(ctx.adapter, fallback)
  }
  return fallback
}

/** 当前程序化载入代数（适配器方法优先；缺省用入参快照，再缺省 0）。 */
export function sheetLoadGenerationOf(ctx: SheetContext): number {
  if (typeof ctx.adapter.loadGeneration === 'function') {
    try {
      const value = ctx.adapter.loadGeneration()
      if (typeof value === 'number' && Number.isFinite(value)) {
        return value
      }
    } catch {
      // 同上：回落到快照
    }
  }
  if (typeof ctx.loadGeneration === 'number' && Number.isFinite(ctx.loadGeneration)) {
    return ctx.loadGeneration
  }
  return 0
}

/** 预览身份（toolCallId + sheetInstanceId + loadGeneration + scope）。 */
export function previewIdentityOf(
  toolCallId: string,
  ctx: SheetContext,
  scope?: SheetWritePlan['scope']
): string {
  return `${toolCallId}::${sheetInstanceIdOf(ctx)}::${sheetLoadGenerationOf(ctx)}::${scope ?? 'sheet'}`
}

/**
 * 计划身份是否已与当前表格对不上。
 *
 * <p>缺 `previewId` 的手搭/旧载荷不在此拒绝（没有可判身份），真实计划恒有值。
 */
function previewIdentityMismatch(plan: SheetWritePlan, ctx: SheetContext): boolean {
  if (!plan.previewId) {
    return false
  }
  return plan.previewId !== previewIdentityOf(plan.toolCallId, ctx, plan.scope)
}

const PREVIEW_INVALID_REASON = '该预览已失效或已处理，本次未应用任何改动。请重新发起。'
const UNDO_PREVIEW_INVALID_REASON = '该预览已失效或已处理，本次未执行撤销。请重新发起。'
const STALE_PREVIEW_REASON = '预览已失效（表格在此期间被修改），未产出新的明细。请让 AI 重新发起。'

function makePlan(input: {
  ctx: SheetContext
  tool: string
  toolCallId: string
  preview: SheetPreviewLevel
  /** 作用域（B12：选区作用域的计划在选区变化时失效；其余作用域与选区无关） */
  scope?: SheetWritePlan['scope']
  scopeSummary: string
  predicateSummary: string
  targetRows: number[]
  changes: SheetChange[]
  skippedCount: number
  rulesText: string
  warnings: string[]
  /** 明细粒度（缺省 full；count 快路径传 none 并同时给 counts） */
  detail?: 'none' | 'full'
  /** 计数档的精确计数（detail=none 时必给：changes 为空，计数不能从数组长度推导） */
  counts?: { rowsCount: number; cellsCount: number }
  distribution?: SheetDistribution
  args?: SheetArgs
  /** 重算时沿用的 planId（同一份计划只换粒度，id 不变 —— 撤销补丁按它绑定） */
  planId?: string
}): SheetWritePlan {
  const detail = input.detail ?? 'full'
  const rows = new Set(input.changes.map((change) => change.row))
  const rowsCount = input.counts?.rowsCount ?? rows.size
  const cellsCount = input.counts?.cellsCount ?? input.changes.length
  const sheetInstanceId = sheetInstanceIdOf(input.ctx)
  const loadGeneration = sheetLoadGenerationOf(input.ctx)
  return {
    planId: input.planId ?? nextPlanId(input.toolCallId),
    toolCallId: input.toolCallId,
    defCode: input.ctx.defCode,
    tool: input.tool,
    level: rowsCount > SHEET_WRITE_LARGE_ROW_THRESHOLD || cellsCount > SHEET_WRITE_SMALL_DIFF_MAX_CELLS ? 'large' : 'small',
    preview: input.preview,
    ...(input.scope ? { scope: input.scope } : {}),
    // 预览身份：与当前表/代数/范围绑定（缺省 scope = sheet，与 apply 侧同口径）
    previewId: previewIdentityOf(input.toolCallId, input.ctx, input.scope),
    sheetInstanceId,
    loadGeneration,
    scopeSummary: input.scopeSummary,
    predicateSummary: input.predicateSummary,
    rowsCount,
    cellsCount,
    skippedCount: input.skippedCount,
    samples: input.changes.slice(0, SHEET_PREVIEW_SAMPLE_PAIRS),
    changes: input.changes,
    targetRows: input.targetRows,
    // 指纹按 targetRows 现算（计数档也必须有）：否则"预览后手改一格再应用"就检测不到 ——
    // 应用前要物化明细，物化会按**当时**的表状态重算，等于把改动后的状态当成预览时的状态（T-25 的洞）。
    fingerprint: input.targetRows.length > 0
      ? fingerprintRows(input.ctx, input.targetRows, keyColumns(input.ctx))
      : [],
    warnings: input.warnings,
    rulesText: input.rulesText,
    detail,
    ...(input.distribution ? { distribution: input.distribution } : {}),
    ...(input.args ? { args: input.args } : {}),
    sheetVer: input.ctx.version,
    status: 'pending'
  }
}

function ruleSampleText(plan: SheetWritePlan, ctx: SheetContext): string {
  return plan.samples.map((change) => {
    const code = columnCode(ctx, change.col)
    return `第 ${excelRowNumber(change.row)} 行 ${code}「${cellDisplay(change.oldValue)}」→「${cellDisplay(change.newValue)}」`
  }).join('；')
}

/** 预览卡/回灌共用的“范围”行。 */
function scopeLine(plan: SheetWritePlan): string {
  return `范围：${plan.scopeSummary}${plan.predicateSummary ? ` · ${plan.predicateSummary}` : ''}`
}

/**
 * 分布的一行式文本（卡面与预览文案共用；**不进回灌**）。
 *
 * <p>格式：`分布（按 region，前 5 组）：XN 812 行 / 1624 格 · SH 428 行 / 856 格 · …（其余 3 组）`。
 */
export function distributionText(distribution: SheetDistribution | undefined): string {
  if (!distribution || distribution.groups.length === 0) {
    return ''
  }
  const by = distribution.byLabel ? `${distribution.byLabel}(${distribution.by})` : distribution.by
  const items = distribution.groups.map((group) => `${group.key} ${group.rows} 行 / ${group.cells} 格`)
  const folded = distribution.otherGroups > 0 ? ` · 其余 ${distribution.otherGroups} 组` : ''
  const truncated = distribution.truncated ? '（组数超过 20，其余不再细分）' : ''
  return `分布（按 ${by}，前 ${distribution.groups.length} 组）：${items.join(' · ')}${folded}${truncated}`
}

/** 预览卡文案（含三档与大件二次确认；数据只在浏览器，0 行进模型）。 */
export function planPreviewText(plan: SheetWritePlan | null): string {
  if (!plan) return ''
  const lines: string[] = []
  lines.push(`预览（尚未修改任何单元格）：${plan.tool}`)
  lines.push(`将影响 ${plan.rowsCount} 行 / ${plan.cellsCount} 格`)
  lines.push(scopeLine(plan))
  lines.push(`规则：${plan.rulesText}`)
  if (plan.samples.length > 0) {
    lines.push(`样例（前 ${plan.samples.length} 处）：${plan.changes.length > 0 ? plan.samples.map((change) => `第 ${excelRowNumber(change.row)} 行 列 ${change.col + 1}「${cellDisplay(change.oldValue)}」→「${cellDisplay(change.newValue)}」`).join('；') : ''}`)
  }
  // 分布只用于卡面（planPreviewText 不进回灌）⇒ 加它不扩大模型侧 D2 体积
  const distribution = distributionText(plan.distribution)
  if (distribution !== '') {
    lines.push(distribution)
  }
  if (plan.detail === 'none') {
    lines.push('明细未加载（计数级预览）：点「看样例」或「全量 diff」才会逐格计算。')
  }
  if (plan.skippedCount > 0) {
    lines.push(`跳过：${plan.skippedCount} 格（校验器拒绝，未写入）`)
  }
  for (const warning of plan.warnings) {
    lines.push(warning)
  }
  if (plan.level === 'large') {
    lines.push(`本次将修改 ${plan.rowsCount} 行 / ${plan.cellsCount} 格（超过 ${SHEET_WRITE_LARGE_ROW_THRESHOLD} 行）。请勾选「我了解影响范围」后再点「应用」。`)
  }
  return lines.join('\n')
}

/**
 * 成功应用后的回灌文案（必须含“用户已确认 / 未保存”，可断言）。
 *
 * <p>R3-02：不得再声称“仅在浏览器内存” —— 导入向导离开第 1 步会整表写回服务端文件，
 * 回执须把这条写回路径和“此刻尚未保存到服务端”同时说清。
 */
export function planAppliedText(plan: SheetWritePlan, ctx?: SheetContext): string {
  const lines: string[] = []
  lines.push(`已应用（用户已确认）：${plan.tool} 改动 ${plan.cellsCount} 格 / ${plan.rowsCount} 行`)
  lines.push(scopeLine(plan))
  lines.push(`规则：${plan.rulesText}`)
  if (plan.samples.length > 0 && ctx) {
    lines.push(`样例（前 ${plan.samples.length} 处，Excel 行号）：${ruleSampleText(plan, ctx)}`)
  }
  if (plan.skippedCount > 0) {
    lines.push(`跳过：${plan.skippedCount} 格（校验器拒绝）`)
  }
  lines.push('未保存到服务端：改动只在当前页面表格内（不在服务端）；导入向导离开第 1 步（「下一步」/ confirm_step）会整表写回服务端文件，导出向导需点「保存编辑到服务端」；可撤销（undo_last_sheet_edit）')
  lines.push(`共 ${plan.cellsCount} 格`)
  return lines.join('\n')
}

export function planCancelledText(): string {
  return '用户取消了本次修改，未改动任何单元格。\n共 0 格'
}

// ── 写侧：replace_values ─────────────────────────────────────────────────────

/**
 * 计数档的累加器（§6.2 快路径）：只记数，不建 change 对象。
 *
 * <p>
 * 为什么单列一个类型：三个写工具的扫描结构不同（整列替换 / 逐 set 项 / 逐列归一化），
 * 但"数到多少格就中止、按哪个键分组"是同一件事 —— 把这两件收紧在一处，
 * 三处快路径才不会各写一套口径（K-5 的漂移面）。
 */
interface SheetCounts {
  rows: number
  cells: number
  skipped: number
  groups: Map<string, GroupCount>
  /** 组数是否超过内部上限（20）：超过即只统计前 20 组，其余折叠计"其他 N 组" */
  truncated: boolean
  /** 已越过硬上限（扫描应立即停止） */
  overflow: boolean
}

function newCounts(): SheetCounts {
  return { rows: 0, cells: 0, skipped: 0, groups: new Map(), truncated: false, overflow: false }
}

/**
 * 记一行：`hitCells` 为该行命中格数（0 = 未命中，不计行）。
 *
 * @param key 分布分组键（null = 不做分布）
 * @returns true = 已越硬上限（调用方应立即停扫）
 */
function countRow(counts: SheetCounts, hitCells: number, key: string | null): boolean {
  if (hitCells > 0) {
    counts.rows += 1
    counts.cells += hitCells
    if (key !== null) {
      const entry = counts.groups.get(key) ?? { key, rows: 0, cells: 0 }
      entry.rows += 1
      entry.cells += hitCells
      counts.groups.set(key, entry)
      if (counts.groups.size > SHEET_AGGREGATE_MAX_GROUPS) {
        counts.truncated = true
      }
    }
  }
  if (counts.cells > SHEET_WRITE_HARD_MAX_CELLS) {
    counts.overflow = true
    return true
  }
  return false
}

/**
 * 分布按哪一列分组（§6.3）：入参 `distributionBy` 优先；缺省 = 谓词首个字段；再无 = 投影首列。
 *
 * <p>解析失败不回败（分布是"锦上添花"）：`distributionBy` 给了但不存在的列 ⇒ 退回缺省推断。
 */
function resolveDistributionColumn(
  ctx: SheetContext,
  args: SheetArgs,
  fallbackColumns: readonly number[]
): number | null {
  if (typeof args.distributionBy === 'string' && args.distributionBy !== '') {
    const explicit = resolveColumn(ctx, args.distributionBy)
    if (explicit !== null) return explicit
  }
  const predicate = args.predicate as FieldQueryCondition | null | undefined
  const firstField = predicate && Array.isArray(predicate.fields) ? predicate.fields[0] : undefined
  const fieldCode = firstField && typeof firstField.fieldCode === 'string' ? firstField.fieldCode : ''
  if (fieldCode !== '') {
    const resolved = resolveColumn(ctx, fieldCode)
    if (resolved !== null) return resolved
  }
  return fallbackColumns.length > 0 ? (fallbackColumns[0] ?? null) : null
}

/** 把累加器转成卡面分布（前 5 组 + 折叠计数；§6.3 排序口径：rows 降序、同值按键字典序）。 */
function buildDistribution(ctx: SheetContext, byCol: number | null, counts: SheetCounts): SheetDistribution | undefined {
  if (byCol === null || counts.groups.size === 0) {
    return undefined
  }
  const sorted = [...counts.groups.values()].sort((left, right) => {
    if (right.rows !== left.rows) return right.rows - left.rows
    return compareValues(left.key, right.key)
  })
  const shown = sorted.slice(0, SHEET_DISTRIBUTION_SHOW_GROUPS)
  const field = fieldOfColumn(ctx, byCol)
  return {
    by: columnCode(ctx, byCol),
    ...(field && field.label ? { byLabel: field.label } : {}),
    groups: shown.map((group) => ({ key: group.key, rows: group.rows, cells: group.cells })),
    otherGroups: Math.max(0, sorted.length - shown.length),
    truncated: counts.truncated
  }
}

function buildMatcher(args: SheetArgs): { ok: true; match: (text: string) => { hit: boolean; next: string } , rulesText: string } | { ok: false; reason: string } {
  let kind: string
  if (Array.isArray(args.match)) {
    const values = args.match.map((item) => String(item))
    if (values.includes('wildcard') && values.includes('regex')) {
      return { ok: false, reason: 'match 不能同时给 wildcard 与 regex（两者互斥，语义不确定），本次未做任何修改。' }
    }
    kind = values[0] ?? 'wholeCell'
  } else {
    kind = typeof args.match === 'string' && args.match !== '' ? args.match : 'wholeCell'
  }
  const allowed = new Set(['wholeCell', 'contains', 'prefix', 'wildcard', 'regex'])
  if (!allowed.has(kind)) {
    return { ok: false, reason: `match 只支持 wholeCell/contains/prefix/wildcard/regex，实际为 ${kind}。` }
  }
  const search = typeof args.search === 'string' ? args.search : ''
  if (search === '') {
    return { ok: false, reason: 'search 不能为空：请给出要查找的原文。' }
  }
  const replace = typeof args.replace === 'string' ? args.replace : ''
  const ignoreCase = args.ignoreCase === undefined ? true : Boolean(args.ignoreCase)
  const flags = ignoreCase ? 'i' : ''
  const label: Record<string, string> = {
    wholeCell: '整格相等',
    contains: '包含（子串替换）',
    prefix: '前缀替换',
    wildcard: '通配符',
    regex: '正则'
  }
  const rulesText = `${label[kind]} · 区分大小写=${!ignoreCase} · 「${search}」→「${replace}」`
  if (kind === 'regex') {
    try {
      const re = new RegExp(search, `${flags}g`)
      return {
        ok: true,
        rulesText,
        match: (text) => ({ hit: re.test(text), next: text.replace(re, replace) })
      }
    } catch (error) {
      return { ok: false, reason: `正则表达式不合法：${error instanceof Error ? error.message : String(error)}。本次未做任何修改。` }
    }
  }
  if (kind === 'wildcard') {
    const escaped = search.replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*').replace(/\?/g, '.')
    const re = new RegExp(`^${escaped}$`, flags)
    return {
      ok: true,
      rulesText,
      match: (text) => ({ hit: re.test(text), next: replace })
    }
  }
  if (kind === 'wholeCell') {
    const target = ignoreCase ? search.toLowerCase() : search
    return {
      ok: true,
      rulesText,
      match: (text) => ({
        hit: (ignoreCase ? text.toLowerCase() : text) === target,
        next: replace
      })
    }
  }
  if (kind === 'prefix') {
    const target = ignoreCase ? search.toLowerCase() : search
    return {
      ok: true,
      rulesText,
      match: (text) => ({
        hit: (ignoreCase ? text.toLowerCase() : text).startsWith(target),
        next: replace + text.slice(search.length)
      })
    }
  }
  // contains：保留非命中片段（只替换第一处）
  const escaped = search.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const re = new RegExp(escaped, flags)
  return {
    ok: true,
    rulesText,
    match: (text) => ({ hit: re.test(text), next: text.replace(re, replace) })
  }
}

export function planReplace(ctx: SheetContext, args: SheetArgs, toolCallId = ''): SheetPlanOutcome {
  const defError = validateDefCode(ctx, args)
  if (defError) return { ok: false, reason: defError }
  if (!Array.isArray(args.columnCodes) || args.columnCodes.length === 0) {
    return { ok: false, reason: 'columnCodes 必填：请给出要替换的 1 到 5 个列（字段编码）。' }
  }
  const columnCodes = (args.columnCodes as unknown[]).map((item) => String(item))
  if (columnCodes.length > 5) {
    return { ok: false, reason: 'columnCodes 最多 5 列，请缩小范围。' }
  }
  const columns: number[] = []
  for (const code of columnCodes) {
    const col = resolveColumn(ctx, code)
    if (col === null) {
      return { ok: false, reason: `列 ${code} 不在当前表格中。` }
    }
    columns.push(col)
  }
  const matcher = buildMatcher(args)
  if (!matcher.ok) return matcher
  const preview = normalizePreview(args.preview)
  if (preview === null) {
    return { ok: false, reason: 'preview 只能是 count/sample-diff/full-diff。' }
  }
  const scope = resolveScope(ctx, args, { defaultScope: 'selection', columns })
  if (!scope.ok) return scope

  // P0 快路径（§6.2）：count 档只数不物化；明细档（sample-diff/full-diff）才建 change 对象。
  const countMode = preview === 'count'
  const byCol = countMode ? resolveDistributionColumn(ctx, args, columns) : null
  const scanColumns = countMode && byCol !== null && !columns.includes(byCol) ? [...columns, byCol] : columns
  const byIndex = byCol === null ? -1 : scanColumns.indexOf(byCol)
  const counts = newCounts()
  const changes: SheetChange[] = []
  const matchedRows: number[] = []
  scanRows(ctx, scope.resolved.rows, scanColumns, (row, values) => {
    let hitCells = 0
    columns.forEach((col, colIndex) => {
      const oldValue = values[colIndex]
      const text = cellDisplay(oldValue)
      const matched = matcher.match(text)
      if (!matched.hit || matched.next === text) return
      const field = fieldOfColumn(ctx, col)
      if (field && validateCell(field, matched.next) !== null) {
        counts.skipped += 1
        return
      }
      hitCells += 1
      if (!countMode || changes.length < SHEET_PREVIEW_SAMPLE_PAIRS) {
        // 计数档只留前 3 处样例（卡面"看样例前 3 条"的承诺），全量明细一个都不建
        changes.push({ row, col, oldValue, newValue: matched.next })
      }
    })
    if (countMode) {
      if (hitCells > 0) matchedRows.push(row)
      // 返回 true ⇒ scanRows 立即停扫（越界后不必再读下一批）
      return !countRow(counts, hitCells, byIndex >= 0 ? groupKeyOf(values[byIndex]) : null)
    }
    return undefined
  })
  if (countMode && counts.overflow) {
    return {
      ok: false,
      reason: `本次改动超过硬上限 ${SHEET_WRITE_HARD_MAX_CELLS} 格（已扫描到第 ${counts.rows} 行时中止），已拒绝。请缩小条件或列范围。`
    }
  }
  if (!countMode && changes.length > SHEET_WRITE_HARD_MAX_CELLS) {
    return {
      ok: false,
      reason: `本次改动 ${changes.length} 格，超过硬上限 ${SHEET_WRITE_HARD_MAX_CELLS} 格，已拒绝。请缩小条件或列范围。`
    }
  }
  const plan = makePlan({
    ctx,
    tool: 'replace_values',
    toolCallId,
    preview,
    scope: scope.resolved.scope as SheetWritePlan['scope'],
    scopeSummary: scope.resolved.summary,
    predicateSummary: scope.resolved.predicateSummary,
    // 计数档只保留命中行（应用/指纹要用），不保留逐格明细
    targetRows: countMode ? matchedRows : scope.resolved.rows,
    changes,
    skippedCount: counts.skipped,
    rulesText: matcher.rulesText,
    warnings: [],
    detail: countMode ? 'none' : 'full',
    ...(countMode
      ? {
          counts: { rowsCount: counts.rows, cellsCount: counts.cells },
          ...distributionOf(ctx, byCol, counts)
        }
      : {}),
    args
  })
  return { ok: true, plan }
}

/** 分布字段的可选展开（无命中/无分组列 ⇒ 空对象，不写键）。 */
function distributionOf(
  ctx: SheetContext,
  byCol: number | null,
  counts: SheetCounts
): { distribution?: SheetDistribution } {
  const distribution = buildDistribution(ctx, byCol, counts)
  return distribution ? { distribution } : {}
}

// ── 写侧：update_rows ────────────────────────────────────────────────────────

interface SetItem {
  columnCode: string
  mode: string
  value?: unknown
  template?: unknown
  start?: unknown
  step?: unknown
}

function parseSetItems(args: SheetArgs): { ok: true; items: SetItem[] } | { ok: false; reason: string } {
  if (!Array.isArray(args.set) || args.set.length === 0) {
    return { ok: false, reason: 'set 必填：请给出 1 到 5 条要写入的列与写法。' }
  }
  if (args.set.length > 5) {
    return { ok: false, reason: 'set 最多 5 条，请缩小范围。' }
  }
  const allowed = new Set(['literal', 'fillDown', 'fillSeries', 'template'])
  const items: SetItem[] = []
  for (const raw of args.set as unknown[]) {
    if (!raw || typeof raw !== 'object') {
      return { ok: false, reason: 'set 的每一条必须是对象（columnCode + mode）。' }
    }
    const item = raw as Record<string, unknown>
    const columnCode = typeof item.columnCode === 'string' ? item.columnCode : ''
    const mode = typeof item.mode === 'string' && item.mode !== '' ? item.mode : 'literal'
    if (columnCode === '') {
      return { ok: false, reason: 'set 的每一条都需要 columnCode。' }
    }
    if (!allowed.has(mode)) {
      return { ok: false, reason: `mode 只支持 literal/fillDown/fillSeries/template，实际为 ${mode}。` }
    }
    items.push({
      columnCode,
      mode,
      value: item.value,
      template: item.template,
      start: item.start,
      step: item.step
    })
  }
  return { ok: true, items }
}

export function planUpdate(ctx: SheetContext, args: SheetArgs, toolCallId = ''): SheetPlanOutcome {
  const defError = validateDefCode(ctx, args)
  if (defError) return { ok: false, reason: defError }
  const parsed = parseSetItems(args)
  if (!parsed.ok) return parsed
  const preview = normalizePreview(args.preview)
  if (preview === null) {
    return { ok: false, reason: 'preview 只能是 count/sample-diff/full-diff。' }
  }
  const scope = resolveScope(ctx, args, { defaultScope: 'predicate' })
  if (!scope.ok) return scope
  const rows = scope.resolved.rows
  const rowSet = new Set(rows)

  // P0 快路径（§6.2）：count 档只数不物化。命中先按行记数（一行的命中格数），
  // 全部 set 项跑完后再一次性折算 rows/cells/分布 —— 这样"10 万格"不会变成 10 万个对象。
  const countMode = preview === 'count'
  const counts = newCounts()
  const changes: SheetChange[] = []
  const perRowHits = new Map<number, number>()
  const matchedRows: number[] = []
  let hitTotal = 0
  let fillCells = 0
  const warnings: string[] = []
  const ruleParts: string[] = []
  const recordHit = (row: number, col: number, oldValue: unknown, newValue: unknown): void => {
    hitTotal += 1
    if (countMode) {
      perRowHits.set(row, (perRowHits.get(row) ?? 0) + 1)
      // 计数档只留前 3 处样例（卡面承诺），其余一个对象都不建
      if (changes.length < SHEET_PREVIEW_SAMPLE_PAIRS) {
        changes.push({ row, col, oldValue, newValue })
      }
      return
    }
    changes.push({ row, col, oldValue, newValue })
  }

  for (const item of parsed.items) {
    const col = resolveColumn(ctx, item.columnCode)
    if (col === null) {
      return { ok: false, reason: `列 ${item.columnCode} 不在当前表格中。` }
    }
    const field = fieldOfColumn(ctx, col)
    if (item.mode === 'literal') {
      const value = item.value === undefined ? '' : item.value
      ruleParts.push(`${item.columnCode} ← 「${cellDisplay(value)}」`)
      const current = readSingleColumn(ctx, rows, col)
      rows.forEach((row, index) => {
        const oldValue = current[index]
        if (cellDisplay(oldValue) === cellDisplay(value)) return
        if (field && validateCell(field, value) !== null) {
          counts.skipped += 1
          return
        }
        recordHit(row, col, oldValue, value)
      })
    } else if (item.mode === 'template') {
      const template = typeof item.template === 'string' ? item.template : ''
      if (template === '') {
        return { ok: false, reason: 'mode=template 需要 template（如 "${code}-${name}"）。' }
      }
      ruleParts.push(`${item.columnCode} ← 模板「${template}」`)
      const refCodes = Array.from(template.matchAll(/\$\{([^}]+)\}/g), (matched) => String(matched[1]))
      const resolvedRefs = new Map<string, number | null>()
      for (const code of refCodes) {
        if (!resolvedRefs.has(code)) resolvedRefs.set(code, resolveColumn(ctx, code))
      }
      const scanColumns = Array.from(new Set([col, ...[...resolvedRefs.values()].filter((ref): ref is number => ref !== null)]))
      const refIndexByCode = new Map<string, number>()
      for (const [code, refCol] of resolvedRefs) {
        refIndexByCode.set(code, refCol === null ? -1 : scanColumns.indexOf(refCol))
      }
      const values: unknown[][] = []
      scanRows(ctx, rows, scanColumns, (_row, cells) => {
        values.push([...cells])
      })
      const startIndex = scanColumns.indexOf(col)
      rows.forEach((row, index) => {
        const cells = values[index] ?? []
        const oldValue = cells[startIndex]
        const next = template.replace(/\$\{([^}]+)\}/g, (_all, code: string) => {
          const refIndex = refIndexByCode.get(code) ?? -1
          return refIndex < 0 ? '' : cellDisplay(cells[refIndex])
        })
        if (next === cellDisplay(oldValue)) return
        if (field && validateCell(field, next) !== null) {
          counts.skipped += 1
          return
        }
        recordHit(row, col, oldValue, next)
      })
    } else if (item.mode === 'fillDown') {
      ruleParts.push(`${item.columnCode} ← 用上方最近的非空值补齐空单元格`)
      const allRows = allRowIndexes(ctx)
      const current = readSingleColumn(ctx, allRows, col)
      let last: unknown = ''
      for (let row = 0; row < current.length; row += 1) {
        const value = current[row]
        if (!isBlank(value)) {
          last = value
          continue
        }
        if (!rowSet.has(row)) continue
        if (isBlank(last)) continue
        fillCells += 1
        if (field && validateCell(field, last) !== null) {
          counts.skipped += 1
          continue
        }
        recordHit(row, col, value, last)
      }
    } else {
      const start = Number(item.start ?? 1)
      const step = Number(item.step ?? 1)
      if (!Number.isFinite(start) || !Number.isFinite(step)) {
        return { ok: false, reason: 'mode=fillSeries 需要数值型 start / step。' }
      }
      ruleParts.push(`${item.columnCode} ← 等差数列（start=${start}，step=${step}）`)
      const current = readSingleColumn(ctx, rows, col)
      let index = 0
      rows.forEach((row, rowIndex) => {
        const value = current[rowIndex]
        if (!isBlank(value)) return
        const next = String(start + step * index)
        index += 1
        fillCells += 1
        if (field && validateCell(field, next) !== null) {
          counts.skipped += 1
          return
        }
        recordHit(row, col, value, next)
      })
    }
    // 越界即停：后续 set 项不再跑（它们只会让超限数字更大，报错文案不需要精确值）
    if (countMode && hitTotal > SHEET_WRITE_HARD_MAX_CELLS) {
      counts.overflow = true
      break
    }
  }

  let byCol: number | null = null
  if (countMode) {
    if (counts.overflow || hitTotal > SHEET_WRITE_HARD_MAX_CELLS) {
      return {
        ok: false,
        reason: `本次改动超过硬上限 ${SHEET_WRITE_HARD_MAX_CELLS} 格（扫描中途已中止），已拒绝。请缩小条件或列范围。`
      }
    }
    // 分布的分组列 = distributionBy / 谓词首字段 / 首个 set 列（§6.3）
    const itemColumns = parsed.items
      .map((item) => resolveColumn(ctx, item.columnCode))
      .filter((col): col is number => col !== null)
    byCol = resolveDistributionColumn(ctx, args, itemColumns)
    const groupValues = byCol === null ? null : readSingleColumn(ctx, rows, byCol)
    for (let index = 0; index < rows.length; index += 1) {
      const row = rows[index]
      if (row === undefined) continue
      const hits = perRowHits.get(row) ?? 0
      const key = groupValues === null ? null : groupKeyOf(groupValues[index])
      if (hits > 0) matchedRows.push(row)
      if (countRow(counts, hits, key)) {
        counts.overflow = true
        break
      }
    }
    if (counts.overflow) {
      return {
        ok: false,
        reason: `本次改动超过硬上限 ${SHEET_WRITE_HARD_MAX_CELLS} 格（已统计到第 ${counts.rows} 行时中止），已拒绝。请缩小条件或列范围。`
      }
    }
  } else if (changes.length > SHEET_WRITE_HARD_MAX_CELLS) {
    return {
      ok: false,
      reason: `本次改动 ${changes.length} 格，超过硬上限 ${SHEET_WRITE_HARD_MAX_CELLS} 格，已拒绝。请缩小条件或列范围。`
    }
  }
  if (parsed.items.some((item) => item.mode === 'fillDown' || item.mode === 'fillSeries')) {
    warnings.push(`注意：本次会用其他行的值补齐空单元格（共 ${fillCells} 格），请确认这些空缺确实应当补值。`)
  }
  const plan = makePlan({
    ctx,
    tool: 'update_rows',
    toolCallId,
    preview,
    scope: scope.resolved.scope as SheetWritePlan['scope'],
    scopeSummary: scope.resolved.summary,
    predicateSummary: scope.resolved.predicateSummary,
    targetRows: countMode ? matchedRows : rows,
    changes,
    skippedCount: counts.skipped,
    rulesText: ruleParts.join('；'),
    warnings,
    detail: countMode ? 'none' : 'full',
    ...(countMode
      ? { counts: { rowsCount: counts.rows, cellsCount: counts.cells }, ...distributionOf(ctx, byCol, counts) }
      : {}),
    args
  })
  return { ok: true, plan }
}

// ── 写侧：normalize_column（一工具双档） ─────────────────────────────────────

const EXECUTABLE_RULES = new Set(['trim', 'case', 'width'])
const SUGGEST_RULES = new Set(['trim', 'case', 'width', 'space', 'number', 'date'])

function toHalfWidth(text: string): string {
  return text.replace(/[\uFF01-\uFF5E]/g, (char) => String.fromCharCode(char.charCodeAt(0) - 0xFEE0))
    .replace(/\u3000/g, ' ')
}

function normalizeText(text: string, rule: string, upper: boolean): string {
  switch (rule) {
    case 'trim':
      return text.replace(/^[\s\u00A0\u200B]+|[\s\u00A0\u200B]+$/g, '')
    case 'case':
      return upper ? text.toUpperCase() : text.toLowerCase()
    case 'width':
      return toHalfWidth(text)
    default:
      return text
  }
}

interface NormalizeSetup {
  columns: number[]
  rules: string[]
  upper: boolean
  commit: boolean
}

function parseNormalizeArgs(ctx: SheetContext, args: SheetArgs): { ok: true; setup: NormalizeSetup } | { ok: false; reason: string } {
  if (!Array.isArray(args.columnCodes) || args.columnCodes.length === 0) {
    return { ok: false, reason: 'columnCodes 必填：请给出要归一化的 1 到 5 个列（字段编码）。' }
  }
  if (args.columnCodes.length > 5) {
    return { ok: false, reason: 'columnCodes 最多 5 列，请缩小范围。' }
  }
  const columns: number[] = []
  for (const code of args.columnCodes as unknown[]) {
    const col = resolveColumn(ctx, String(code))
    if (col === null) {
      return { ok: false, reason: `列 ${String(code)} 不在当前表格中。` }
    }
    columns.push(col)
  }
  if (!Array.isArray(args.rules) || args.rules.length === 0) {
    return { ok: false, reason: 'rules 必填：请给出 1 到 3 条归一化规则。' }
  }
  if (args.rules.length > 3) {
    return { ok: false, reason: 'rules 最多 3 条，请缩小范围。' }
  }
  const rules = (args.rules as unknown[]).map((item) => String(item))
  for (const rule of rules) {
    if (!SUGGEST_RULES.has(rule)) {
      return { ok: false, reason: `规则 ${rule} 不在支持范围（trim/case/width/space/number/date）。` }
    }
  }
  const commit = args.commit === true
  if (commit) {
    const illegal = rules.filter((rule) => !EXECUTABLE_RULES.has(rule))
    if (illegal.length > 0) {
      return {
        ok: false,
        reason: `提交执行只支持 trim / case / width；${illegal.join('、')} 只会猜业务事实，只能出建议（commit=false）。`
      }
    }
  }
  const caseMode = args.caseMode
  const upper = String(caseMode ?? 'upper').toLowerCase() !== 'lower'
  if (rules.includes('case') && caseMode !== undefined && caseMode !== 'upper' && caseMode !== 'lower') {
    return { ok: false, reason: 'rule=case 时 caseMode 只能是 upper 或 lower。' }
  }
  return { ok: true, setup: { columns, rules, upper, commit } }
}

interface NormalizeDiff {
  changes: SheetChange[]
  ruleCounts: Map<string, number>
  skipped: number
  /** 计数档用：命中行（应用前物化与指纹都靠它） */
  matchedRows?: number[]
}

function computeNormalize(
  ctx: SheetContext,
  rows: number[],
  setup: NormalizeSetup,
  options: { materialize?: boolean } = {}
): NormalizeDiff & { perRowHits: Map<number, number> } {
  const materialize = options.materialize !== false
  const changes: SheetChange[] = []
  const ruleCounts = new Map<string, number>()
  const perRowHits = new Map<number, number>()
  let skipped = 0
  for (const col of setup.columns) {
    const field = fieldOfColumn(ctx, col)
    scanRows(ctx, rows, [col], (row, values) => {
      const oldValue = values[0]
      let next = cellDisplay(oldValue)
      const before = next
      for (const rule of setup.rules) {
        if (!EXECUTABLE_RULES.has(rule)) continue
        next = normalizeText(next, rule, setup.upper)
      }
      if (next === before) return
      // 每条规则的影响计数（对原值单独应用该规则）
      for (const rule of setup.rules) {
        if (!EXECUTABLE_RULES.has(rule)) continue
        if (normalizeText(before, rule, setup.upper) !== before) {
          ruleCounts.set(rule, (ruleCounts.get(rule) ?? 0) + 1)
        }
      }
      if (field && validateCell(field, next) !== null) {
        skipped += 1
        return
      }
      if (materialize || changes.length < SHEET_PREVIEW_SAMPLE_PAIRS) {
        // 计数档只留前 3 处样例（卡面"看样例前 3 条"的承诺），全量明细一个都不建
        changes.push({ row, col, oldValue, newValue: next })
      }
      if (!materialize) {
        perRowHits.set(row, (perRowHits.get(row) ?? 0) + 1)
      }
    })
  }
  return { changes, ruleCounts, skipped, perRowHits }
}

function ruleLabel(rule: string, setup: NormalizeSetup): string {
  if (rule === 'case') return `case → ${setup.upper ? 'upper' : 'lower'}`
  return rule
}

/** `normalize_column(commit=false)`：只出建议，零写入。 */
export function normalizeSuggest(ctx: SheetContext, args: SheetArgs): SheetToolOutcome {
  const defError = validateDefCode(ctx, args)
  if (defError) return { ok: false, reason: defError }
  const parsed = parseNormalizeArgs(ctx, args)
  if (!parsed.ok) return parsed
  const scope = resolveScope(ctx, args, { defaultScope: 'selection', columns: parsed.setup.columns })
  if (!scope.ok) return scope
  const diff = computeNormalize(ctx, scope.resolved.rows, parsed.setup)

  const lines: string[] = []
  lines.push(`归一化建议（未做任何修改）：${ctx.defCode}.${parsed.setup.columns.map((col) => columnCode(ctx, col)).join('、')}`)
  for (const rule of parsed.setup.rules) {
    const count = rule === 'space'
      ? countInternalSpaces(ctx, scope.resolved.rows, parsed.setup.columns)
      : rule === 'number'
        ? countNonCanonicalNumbers(ctx, scope.resolved.rows, parsed.setup.columns)
        : rule === 'date'
          ? countAmbiguousDates(ctx, scope.resolved.rows, parsed.setup.columns)
          : diff.ruleCounts.get(rule) ?? 0
    lines.push(`- ${ruleLabel(rule, parsed.setup)}：${count} 格`)
  }
  const samples = diff.changes.slice(0, 5).map((change) =>
    `第 ${excelRowNumber(change.row)} 行 ${columnCode(ctx, change.col)}「${cellDisplay(change.oldValue)}」→「${cellDisplay(change.newValue)}」`)
  if (samples.length > 0) {
    lines.push(`样例（前 ${samples.length} 处）：${samples.join('；')}`)
  }
  lines.push('提示：这些值会影响主键判重与必填判空。执行请让 AI 用 commit=true 并确认。')
  lines.push(`共 ${diff.changes.length} 格`)
  return { ok: true, text: lines.join('\n') }
}

function countNonCanonicalNumbers(ctx: SheetContext, rows: number[], columns: number[]): number {
  let count = 0
  scanRows(ctx, rows, columns, (_row, values) => {
    values.forEach((value) => {
      const text = cellDisplay(value).trim()
      if (text === '') return
      const num = Number(text)
      if (Number.isFinite(num) && String(num) !== text) count += 1
    })
  })
  return count
}

function countAmbiguousDates(ctx: SheetContext, rows: number[], columns: number[]): number {
  let count = 0
  scanRows(ctx, rows, columns, (_row, values) => {
    values.forEach((value) => {
      const text = cellDisplay(value).trim()
      if (text === '') return
      if (/^\d{8}$/.test(text) || /^\d{1,2}\/\d{1,2}\/\d{4}$/.test(text)) count += 1
    })
  })
  return count
}

function countInternalSpaces(ctx: SheetContext, rows: number[], columns: number[]): number {
  let count = 0
  scanRows(ctx, rows, columns, (_row, values) => {
    values.forEach((value) => {
      if (/\S\s+\S/.test(cellDisplay(value))) count += 1
    })
  })
  return count
}

/** `normalize_column(commit=true)`：只允许 trim/case/width。 */
export function planNormalize(ctx: SheetContext, args: SheetArgs, toolCallId = ''): SheetPlanOutcome {
  const defError = validateDefCode(ctx, args)
  if (defError) return { ok: false, reason: defError }
  const parsed = parseNormalizeArgs(ctx, args)
  if (!parsed.ok) return parsed
  if (!parsed.setup.commit) {
    return { ok: false, reason: 'commit=false 属于只读建议档，请用 normalizeSuggest（读路径）执行。' }
  }
  const preview = normalizePreview(args.preview)
  if (preview === null) {
    return { ok: false, reason: 'preview 只能是 count/sample-diff/full-diff。' }
  }
  const scope = resolveScope(ctx, args, { defaultScope: 'selection', columns: parsed.setup.columns })
  if (!scope.ok) return scope
  // P0 快路径（§6.2）：count 档不物化逐格 change（10 万+ 的对象是内存杀手），只累计每行命中格数
  const countMode = preview === 'count'
  const diff = computeNormalize(ctx, scope.resolved.rows, parsed.setup, { materialize: !countMode })
  const counts = newCounts()
  counts.skipped = diff.skipped
  let byCol: number | null = null
  if (countMode) {
    byCol = resolveDistributionColumn(ctx, args, parsed.setup.columns)
    const groupValues = byCol === null ? null : readSingleColumn(ctx, scope.resolved.rows, byCol)
    const matchedRows: number[] = []
    for (let index = 0; index < scope.resolved.rows.length; index += 1) {
      const row = scope.resolved.rows[index]
      if (row === undefined) continue
      const hits = diff.perRowHits.get(row) ?? 0
      const key = groupValues === null ? null : groupKeyOf(groupValues[index])
      if (hits > 0) matchedRows.push(row)
      if (countRow(counts, hits, key)) {
        counts.overflow = true
        break
      }
    }
    // 指纹要用命中行：塞回 diff 的可见面（避免再声明一个变量穿到 makePlan）
    diff.matchedRows = matchedRows
    if (counts.overflow) {
      return {
        ok: false,
        reason: `本次改动超过硬上限 ${SHEET_WRITE_HARD_MAX_CELLS} 格（已统计到第 ${counts.rows} 行时中止），已拒绝。请缩小条件或列范围。`
      }
    }
  } else if (diff.changes.length > SHEET_WRITE_HARD_MAX_CELLS) {
    return {
      ok: false,
      reason: `本次改动 ${diff.changes.length} 格，超过硬上限 ${SHEET_WRITE_HARD_MAX_CELLS} 格，已拒绝。请缩小条件或列范围。`
    }
  }
  const plan = makePlan({
    ctx,
    tool: 'normalize_column',
    toolCallId,
    preview,
    scope: scope.resolved.scope as SheetWritePlan['scope'],
    scopeSummary: scope.resolved.summary,
    predicateSummary: scope.resolved.predicateSummary,
    targetRows: countMode ? (diff.matchedRows ?? []) : scope.resolved.rows,
    changes: diff.changes,
    skippedCount: diff.skipped,
    rulesText: parsed.setup.rules.map((rule) => ruleLabel(rule, parsed.setup)).join(' + '),
    warnings: [],
    detail: countMode ? 'none' : 'full',
    ...(countMode
      ? { counts: { rowsCount: counts.rows, cellsCount: counts.cells }, ...distributionOf(ctx, byCol, counts) }
      : {}),
    args
  })
  return { ok: true, plan }
}

// ── 写侧：应用与撤销 ─────────────────────────────────────────────────────────

function normalizePreview(value: unknown): SheetPreviewLevel | null {
  if (value === undefined || value === null || value === '') {
    return 'count'
  }
  return value === 'count' || value === 'sample-diff' || value === 'full-diff' ? value : null
}

/**
 * 撤销条目（§5.2）：一次 apply 的**完整反向补丁** + 应用瞬间的"新值"快照。
 *
 * <p>
 * `appliedValues` 是冲突判定的唯一依据：撤销前逐格比对"应用时的新值"与"现在的值"，
 * 任一不等 = 这批改动之后有人（或另一个 AI 计划）动过这一格 ⇒ 整批拒撤（§5.3）。
 * 没有它就只能盲撤，把用户的手改一起回滚掉。
 */
export interface SheetUndoEntry {
  planId: string
  toolCallId: string
  tool: string
  defCode: string
  atMs: number
  changes: SheetChange[]
  rowsCount: number
  cellsCount: number
  /** 与 changes 等长：应用瞬间写入的值（"这批改动之后被改过没有"的比对基准） */
  appliedValues: unknown[]
}

const undoStack = new WeakMap<SheetAdapter, SheetUndoEntry[]>()

/** 当前可撤销层数（0 = 没有可撤的 AI 修改）。 */
export function sheetUndoDepth(adapter: SheetAdapter): number {
  return undoStack.get(adapter)?.length ?? 0
}

/**
 * 栈顶条目的"人工改动"格数（应用后被人改过的格；§5.4 的卡面一行要显示它）。
 *
 * <p>为什么在渲染时现算而不是记账：逐格记账需要订阅每一次 `data-changed` 并维护坐标集，
 * 那等于给用户行为建一份台账（刷新即失效、收益低，O-6 裁决取"只计数"）。
 * 现算的代价 = 扫一遍上一批改动格（≤ 上次计划的格数），换来的是一个**当场为真**的数字。
 */
export function sheetManualChangeCount(ctx: SheetContext): number {
  const stack = undoStack.get(ctx.adapter)
  const entry = stack && stack.length > 0 ? stack[stack.length - 1] : undefined
  if (!entry || entry.defCode !== ctx.defCode) {
    return 0
  }
  let changed = 0
  for (let index = 0; index < entry.changes.length; index += 1) {
    const change = entry.changes[index]
    if (!change) continue
    if (cellDisplay(ctx.adapter.getCell(change.row, change.col)) !== cellDisplay(entry.appliedValues[index])) {
      changed += 1
    }
  }
  return changed
}

/** 已应用改动的展示摘要（卡面一行 + 高亮状态的单一来源）。 */
export interface SheetAppliedSummary {
  planId: string
  toolCallId: string
  tool: string
  /** 这批改动属于哪个配置定义（现算摘要时要用它把表格找回来） */
  defCode: string
  cells: number
  rows: number
  /** 当前层号（1 = 栈顶；深度 3 中第 N 层） */
  layer: number
  /** 可撤销层数（含本层） */
  undoDepth: number
  /** 应用后被人改过的格数（现算） */
  manualChanges: number
}

/** 卡面摘要（无 AI 改动 ⇒ null，不渲染空块）。 */
export function sheetAppliedSummary(ctx: SheetContext): SheetAppliedSummary | null {
  const stack = undoStack.get(ctx.adapter)
  if (!stack || stack.length === 0) {
    return null
  }
  const entry = stack[stack.length - 1]
  if (!entry || entry.defCode !== ctx.defCode) {
    return null
  }
  return {
    planId: entry.planId,
    toolCallId: entry.toolCallId,
    tool: entry.tool,
    defCode: entry.defCode,
    cells: entry.cellsCount,
    rows: entry.rowsCount,
    layer: stack.length,
    undoDepth: stack.length,
    manualChanges: sheetManualChangeCount(ctx)
  }
}

export type ApplySheetPlanOutcome =
  | { ok: true; text: string; cells: number }
  | { ok: false; reason: string }

/**
 * 应用计划：先逐行复核指纹（任一不符 ⇒ 整批拒绝），再分块 `writeCells`，最后登记反向补丁。
 *
 * <p>
 * 计数档（`detail === 'none'`）**不可直接应用**：它没有物化明细，也就没有逐格的新值可写。
 * 这条不是限制而是诚实 —— 让它"应用 0 格"才是真正的谎（用户会以为改了）。
 * 卡面在计数档下会先请求重算（`replanSheet`）再允许应用。
 */
export function applySheetPlan(ctx: SheetContext, plan: SheetWritePlan): ApplySheetPlanOutcome {
  const readOnly = requireReadable(ctx)
  if (readOnly) return { ok: false, reason: readOnly }
  if (plan.defCode !== ctx.defCode) {
    return { ok: false, reason: `表格已切换（当前 ${ctx.defCode}，预览属于 ${plan.defCode}），本次未应用任何改动。请重新发起。` }
  }
  // 身份闸（换文件/程序化重载/换任务/换选区）：与逐格 CAS 无关，只看"还是不是这张表的这份内容"
  if (previewIdentityMismatch(plan, ctx)) {
    return { ok: false, reason: PREVIEW_INVALID_REASON }
  }
  if (plan.status !== 'pending') {
    return { ok: false, reason: PREVIEW_INVALID_REASON }
  }
  if (plan.detail === 'none') {
    return { ok: false, reason: '计数级预览没有逐格明细，无法应用。请先切到「看样例」或「全量 diff」把明细算出来再点应用。' }
  }
  const columns = keyColumns(ctx)
  const current = fingerprintRows(ctx, plan.targetRows, columns)
  for (let index = 0; index < plan.targetRows.length; index += 1) {
    const row = plan.targetRows[index]
    if (row === undefined) continue
    if (current[index] !== plan.fingerprint[index]) {
      return {
        ok: false,
        reason: `表格在预览期间已变动（第 ${excelRowNumber(row)} 行的主键值已改变），本次未应用任何改动。请重新发起。`
      }
    }
  }
  for (const chunk of chunkUpdates(changesToUpdates(plan.changes))) {
    ctx.adapter.writeCells(chunk)
  }
  if (plan.changes.length > 0) {
    const stack = undoStack.get(ctx.adapter) ?? []
    // 同一 planId 不重复入栈（防双击应用把同一批改动记两次）
    if (!stack.some((entry) => entry.planId === plan.planId)) {
      stack.push({
        planId: plan.planId,
        toolCallId: plan.toolCallId,
        tool: plan.tool,
        defCode: plan.defCode,
        atMs: Date.now(),
        changes: plan.changes,
        rowsCount: plan.rowsCount,
        cellsCount: plan.cellsCount,
        appliedValues: plan.changes.map((change) => change.newValue)
      })
      // 超出深度即 FIFO 丢掉栈底（静默 —— 但撤销回执会如实报"剩余可撤销 N 层"）
      while (stack.length > SHEET_UNDO_DEPTH) {
        stack.shift()
      }
      undoStack.set(ctx.adapter, stack)
    }
    // 屏幕态标记（L-23 的复用点）：只画底色，不写 cell style / conditionalFormats
    // —— 后者会污染 toWorkbookJson() 产物（导出走 collectRows()，只读值，故底色进不了文件）。
    ctx.adapter.markCells?.(plan.changes.map((change) => ({ row: change.row, col: change.col })), 'applied')
  }
  // 消费计划（恰好一次）：第二次直接 apply 同一份计划 ⇒ 身份闸/状态闸拒执，不再写一格。
  plan.status = 'applied'
  return { ok: true, text: planAppliedText(plan, ctx), cells: plan.changes.length }
}

/**
 * 预览态高亮（L-23）：把**待改格**在预览期就上屏幕底色 —— 与已应用态共用
 * {@link SheetAdapter#markCells} 这一条绘制入口（治理方案 §5.4「一次做两次用」）。
 *
 * <p>
 * 计数档没有逐格明细（`changes` 为空）⇒ 清掉预览色而不是留一张过期高亮：卡面此时也没有
 * 逐格信息可核对，留着只会让人以为"这些格要改"。只走屏幕绘制，预览期写入调用恒为 0（T-31 口径）。
 *
 * @return 本次上色的格数（0 = 没有逐格明细 / 适配器无该能力）
 */
export function markPreviewCells(ctx: SheetContext, plan: SheetWritePlan | null): number {
  if (!plan || plan.detail === 'none' || plan.changes.length === 0) {
    clearPreviewMarks(ctx)
    return 0
  }
  ctx.adapter.markCells?.(plan.changes.map((change) => ({ row: change.row, col: change.col })), 'preview')
  return plan.changes.length
}

/** 清预览色（取消 / 放弃 / 应用前置 / 切回计数档）；已应用色的清理仍走 `clearMarks()`（撤销后全清）。 */
export function clearPreviewMarks(ctx: SheetContext): void {
  ctx.adapter.clearMarks?.('preview')
}

/** 撤销计划的 id 前缀：`undo:<被撤销计划的 planId>`（幂等判据 = 栈顶是否仍是那一层）。 */
const UNDO_PLAN_PREFIX = 'undo:'

/** 撤销的可撤销层（栈顶条目 + 冲突计数）；不可撤时给如实原因。 */
type UndoTop =
  | { ok: true; stack: SheetUndoEntry[]; entry: SheetUndoEntry; conflicts: number }
  | { ok: false; reason: string }

function undoTop(ctx: SheetContext): UndoTop {
  const stack = undoStack.get(ctx.adapter)
  const entry = stack && stack.length > 0 ? stack[stack.length - 1] : undefined
  if (!stack || !entry || entry.defCode !== ctx.defCode || entry.changes.length === 0) {
    return { ok: false, reason: '没有可撤销的 AI 修改（AI 改动只在本次页面有效，刷新后不可撤）。\n共 0 格' }
  }
  // 冲突判定：逐格比对"应用时的新值"与当前值
  let conflicts = 0
  for (let index = 0; index < entry.changes.length; index += 1) {
    const change = entry.changes[index]
    if (!change) continue
    if (cellDisplay(ctx.adapter.getCell(change.row, change.col)) !== cellDisplay(entry.appliedValues[index])) {
      conflicts += 1
    }
  }
  return { ok: true, stack, entry, conflicts }
}

/** 撤销的反向补丁：`oldValue` = 当前值（应用时的新值），`newValue` = 修改前的值。 */
function undoReverseChanges(entry: SheetUndoEntry): SheetChange[] {
  return entry.changes.map((change, index) => ({
    row: change.row,
    col: change.col,
    oldValue: entry.appliedValues[index],
    newValue: change.oldValue
  }))
}

/**
 * 撤销最近一次 AI 修改（深度 3，反向补丁；§5.2/§5.3/§5.6）。
 *
 * <p>
 * 三条硬口径：
 * <ol>
 * <li><b>整批全或无</b>：任一格被人改过 ⇒ 整批拒撤、栈不弹出（不做逐格跳过 —— 那会产出
 * "半撤销"，与"半批执行毒化历史"同族）；</li>
 * <li><b>回执必报层数</b>：成功说"剩余可撤销 N 层"，无补丁说"只在本次页面有效"，
 * 让用户不必猜；</li>
 * <li><b>不承诺 Ctrl+Z</b>：本栈与用户撤销栈完全独立（共用就回答不了"哪一笔记的是 AI"）。</li>
 * </ol>
 *
 * <p><b>工具安全边界批（2026-10-25 裁决）</b>：本函数是"低层执行"，<b>不是</b>可被模型直接触发的入口 ——
 * 撤销会覆盖当前表格数据，必须经用户在预览卡上确认（{@link planUndo} → {@link applyUndoPlan}）。
 * 前端工具执行器（{@code frontend-tools.ts}）因此不再直接调用本函数。
 */
export function undoLastSheetEdit(ctx: SheetContext): SheetToolOutcome {
  const readOnly = requireReadable(ctx)
  if (readOnly) return { ok: false, reason: readOnly }
  const top = undoTop(ctx)
  if (!top.ok) {
    return { ok: true, text: `${top.reason}\n共 0 格` }
  }
  if (top.conflicts > 0) {
    return {
      ok: true,
      text: `未撤销：这批改动之后有 ${top.conflicts} 格被你手动改过（撤掉会覆盖你的修改）。如需继续，请先手动改回或让 AI 重新发起。\n共 0 格`
    }
  }
  return applyUndoEntry(ctx, top.stack, top.entry)
}

/** 反向补丁落笔 + 弹栈 + 清已应用色（撤销的唯一写入点；调用方须已判定无冲突）。 */
function applyUndoEntry(ctx: SheetContext, stack: SheetUndoEntry[], entry: SheetUndoEntry): ApplySheetPlanOutcome {
  const reverse: SheetCellUpdate[] = entry.changes.map((change) => ({
    row: change.row,
    col: change.col,
    value: change.oldValue
  }))
  for (const chunk of chunkUpdates(reverse)) {
    ctx.adapter.writeCells(chunk)
  }
  stack.pop()
  if (stack.length === 0) {
    undoStack.delete(ctx.adapter)
  }
  // 被回滚的格清标记；其余层保留（§5.4 第 3 条）。**只清已应用色**：撤销与"另一张尚未应用
  // 的预览"无关，无参全清会把那张卡刚上的预览琥珀也一并抹掉（卡还在、高亮没了）。
  ctx.adapter.clearMarks?.('applied')
  return {
    ok: true,
    text: `已撤销 ${entry.cellsCount} 格（plan #${entry.planId}，回到修改前值）。剩余可撤销 ${stack.length} 层。\n共 ${entry.cellsCount} 格`,
    cells: entry.cellsCount
  }
}

/**
 * 撤销的<b>确认计划</b>（工具安全边界批）：把"将要回滚的逐格 diff"算成一张预览计划，
 * 等用户在预览卡上点「应用」才写（{@link applyUndoPlan}）。
 *
 * <p>为什么撤销也要预览：它会覆盖当前表格数据（不是只读、也不是"无后果"），而模型单方面就能触发；
 * 卡片把"哪几格、从什么回到什么"摆出来，用户的一次点击就是本仓既有的<b>人围栏</b>
 * （与三个写工具同一条链，无新帧型）。没有可撤销层 / 有人工改动时<b>不建卡</b>，直接如实回执。
 */
export function planUndo(ctx: SheetContext, _args: SheetArgs, toolCallId = ''): SheetPlanOutcome {
  const readOnly = requireReadable(ctx)
  if (readOnly) return { ok: false, reason: readOnly }
  const top = undoTop(ctx)
  if (!top.ok) return { ok: false, reason: top.reason }
  if (top.conflicts > 0) {
    return {
      ok: false,
      reason: `未撤销：这批改动之后有 ${top.conflicts} 格被你手动改过（撤掉会覆盖你的修改）。如需继续，请先手动改回或让 AI 重新发起。\n共 0 格`
    }
  }
  const { entry, stack } = top
  const reverse = undoReverseChanges(entry)
  return {
    ok: true,
    plan: makePlan({
      ctx,
      tool: 'undo_last_sheet_edit',
      toolCallId,
      // 撤销要求用户核对**全部**反向变更（不是样例）⇒ 直接全量 diff（卡面分页；不产 0 行进模型）
      preview: 'full-diff',
      scope: 'sheet',
      // i18n（本批范围）：撤销卡的两句用户可见文案走 locale，不再新增中文残留。
      scopeSummary: i18n.global.t('sheet.undoScopeSummary', { planId: entry.planId }),
      predicateSummary: '',
      targetRows: [...new Set(reverse.map((change) => change.row))],
      changes: reverse,
      skippedCount: 0,
      rulesText: i18n.global.t('sheet.undoRulesText', {
        cells: entry.cellsCount,
        layers: stack.length
      }),
      warnings: [],
      // 计划身份绑定"要撤销的那一层"：栈顶变了 ⇒ 计划自动失效（不会撤到别的一层，也不会撤两次）
      planId: `${UNDO_PLAN_PREFIX}${entry.planId}`
    })
  }
}

/**
 * 应用撤销计划（用户已确认）：身份校验 → 冲突复核 → 反向补丁 → 弹栈。
 *
 * <p><b>恰好一次</b>：判据是"栈顶仍是本计划要撤的那一层"（{@link UNDO_PLAN_PREFIX} + planId）。
 * 第一次成功后栈顶被弹出，第二次（双击/重放/重复回灌）必然对不上 ⇒ 如实拒绝，不会再写一格。
 */
export function applyUndoPlan(ctx: SheetContext, plan: SheetWritePlan): ApplySheetPlanOutcome {
  const readOnly = requireReadable(ctx)
  if (readOnly) return { ok: false, reason: readOnly }
  if (plan.tool !== 'undo_last_sheet_edit' || !plan.planId.startsWith(UNDO_PLAN_PREFIX)) {
    return { ok: false, reason: '这不是撤销计划，本次未做任何修改。请重新发起。' }
  }
  if (plan.defCode !== ctx.defCode) {
    return { ok: false, reason: `表格已切换（当前 ${ctx.defCode}，预览属于 ${plan.defCode}），本次未执行撤销。请重新发起。` }
  }
  // 身份闸：撤销同样绑定"预览时那张表/那份内容"（换文件/程序化重载后不得回滚新数据）
  if (previewIdentityMismatch(plan, ctx)) {
    return { ok: false, reason: UNDO_PREVIEW_INVALID_REASON }
  }
  const targetPlanId = plan.planId.slice(UNDO_PLAN_PREFIX.length)
  const stack = undoStack.get(ctx.adapter)
  const entry = stack && stack.length > 0 ? stack[stack.length - 1] : undefined
  if (!stack || !entry || entry.planId !== targetPlanId) {
    return { ok: false, reason: '这次撤销已执行或撤销栈已变化（同一次撤销不会执行第二次），本次未做任何修改。' }
  }
  if (plan.status !== 'pending') {
    return { ok: false, reason: UNDO_PREVIEW_INVALID_REASON }
  }
  if (entry.defCode !== ctx.defCode || entry.changes.length === 0) {
    return { ok: false, reason: '这次撤销的目标已不可用（表格或撤销栈已变化），本次未做任何修改。请重新发起。' }
  }
  const top = undoTop(ctx)
  if (!top.ok) {
    return { ok: false, reason: top.reason }
  }
  if (top.conflicts > 0) {
    return {
      ok: false,
      reason: `未撤销：这批改动之后有 ${top.conflicts} 格被你手动改过（撤掉会覆盖你的修改）。本次未做任何修改。`
    }
  }
  const outcome = applyUndoEntry(ctx, top.stack, top.entry)
  if (outcome.ok) {
    // 消费撤销计划（栈已弹出；计划状态同步落定，重放同一对象也不会再进写路径）
    plan.status = 'applied'
  }
  return outcome
}

/**
 * 写工具 + 撤销的统一分发（预览入口与切档重算共用：同一个工具名只能落到同一个确定性函数）。
 */
export function planSheetWithLevel(
  ctx: SheetContext,
  tool: string,
  args: SheetArgs,
  toolCallId = ''
): SheetPlanOutcome | null {
  if (tool === 'replace_values') return planReplace(ctx, args, toolCallId)
  if (tool === 'update_rows') return planUpdate(ctx, args, toolCallId)
  if (tool === 'normalize_column') return planNormalize(ctx, args, toolCallId)
  // 撤销也要预览（工具安全边界批）：会覆盖当前表格数据 ⇒ 必须由用户在卡上确认
  if (tool === 'undo_last_sheet_edit') return planUndo(ctx, args, toolCallId)
  return null
}

/**
 * 切档重算（§6.2 的明细按需计算）：用计划的入参快照重跑同一确定性函数，`planId` 不变。
 *
 * <p>
 * 重跑前必须比对 `sheetVer`：版本变了说明表格已被改动 ⇒ 交给调用方走"预览已失效"，不静默套用旧结果。
 * 物化后仍超 `HARD_MAX` ⇒ 回 `null` 的 detail 说明（调用方保持计数档并提示"明细不可用"）。
 */
export function replanSheet(
  ctx: SheetContext,
  plan: SheetWritePlan,
  level: SheetPreviewLevel
): SheetPlanOutcome {
  if (plan.tool === 'undo_last_sheet_edit') {
    // 撤销没有"明细档"：反向补丁本身就是全量明细 ⇒ 切档不重算、计划身份不变
    return { ok: true, plan }
  }
  if (!plan.args) {
    return { ok: false, reason: '预览缺少入参快照，无法重算明细。请让 AI 重新发起。' }
  }
  if (plan.sheetVer !== undefined && plan.sheetVer !== ctx.version) {
    return { ok: false, reason: STALE_PREVIEW_REASON }
  }
  if (previewIdentityMismatch(plan, ctx)) {
    // 程序化重载（loadGeneration 变）不一定会 bump workspace.dataVersion ⇒ 必须单独比对身份
    return { ok: false, reason: STALE_PREVIEW_REASON }
  }
  const args: SheetArgs = { ...plan.args, preview: level }
  const replanned = planSheetWithLevel(ctx, plan.tool, args, plan.toolCallId)
  if (!replanned) {
    return { ok: false, reason: `未识别的表格写工具 ${plan.tool}：无法重算明细。` }
  }
  if (!replanned.ok) {
    return replanned
  }
  // 同一份计划只换粒度：planId / toolCallId / 入参快照沿用，撤销绑定不断
  return {
    ok: true,
    plan: {
      ...replanned.plan,
      planId: plan.planId,
      toolCallId: plan.toolCallId,
      args: plan.args,
      status: 'pending'
    }
  }
}

/** 写工具名 → 是否需要人围栏预览（normalize 只有 commit=true；撤销恒需要 —— 工具安全边界批）。 */
export function needsSheetPreview(name: string, args: SheetArgs): boolean {
  if (name === 'replace_values' || name === 'update_rows') return true
  if (name === 'normalize_column') return args.commit === true
  // 撤销会覆盖当前表格数据，不能当成"无后果的即时动作"（用户 2026-10-25 裁决）
  if (name === 'undo_last_sheet_edit') return true
  return false
}
