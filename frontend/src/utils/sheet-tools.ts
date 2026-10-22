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
import type { SheetChange, SheetPreviewLevel, SheetWritePlan } from '@/types/ai'
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
export const SHEET_UNDO_DEPTH = 1
export const SHEET_WRITE_CHUNK_ROWS = 500
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
}

export type SheetToolOutcome = { ok: true; text: string } | { ok: false; reason: string }
export type SheetPlanOutcome = { ok: true; plan: SheetWritePlan } | { ok: false; reason: string }

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
 */
function scanRows(
  ctx: SheetContext,
  rows: readonly number[],
  columns: readonly number[],
  visit: (row: number, values: readonly unknown[]) => void
): void {
  if (rows.length === 0 || columns.length === 0) {
    return
  }
  const colIndex = new Map<number, number>()
  columns.forEach((col, index) => colIndex.set(col, index))
  const runs = columnRuns(columns)
  for (const run of rowRuns(rows)) {
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
        visit(run.start + offset + r, buffer[r] ?? [])
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
 */
function projectedColumns(ctx: SheetContext, args: SheetArgs, resolved: ResolvedScope): number[] {
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
  const defaults: number[] = []
  ctx.fields.forEach((field, index) => {
    if (field.key && defaults.length < SHEET_TOOL_MAX_COLUMNS) defaults.push(index)
  })
  for (let col = 0; col < ctx.adapter.colCount() && defaults.length < SHEET_TOOL_MAX_COLUMNS; col += 1) {
    if (!defaults.includes(col)) defaults.push(col)
  }
  return defaults
}

/**
 * `read_sheet_summary`：只回投影列的采样 + 规模/空值/重复摘要，**业务行不出浏览器**
 * 之外的只有这 ≤20 行采样（R3）。尺寸闸覆盖整条结果（表头/摘要/采样/尾行）。
 */
export function readSheetSummary(ctx: SheetContext, args: SheetArgs): SheetToolOutcome {
  const defError = validateDefCode(ctx, args)
  if (defError) return { ok: false, reason: defError }
  const limit = parseLimit(args.limit, SHEET_TOOL_DEFAULT_SAMPLE_ROWS, SHEET_TOOL_MAX_SAMPLE_ROWS)
  if (limit === null) {
    return { ok: false, reason: `limit 只能是 1 到 ${SHEET_TOOL_MAX_SAMPLE_ROWS} 的整数，本次未做任何修改。` }
  }
  if (Array.isArray(args.columnCodes)) {
    if (args.columnCodes.length > SHEET_TOOL_MAX_COLUMNS) {
      return {
        ok: false,
        reason: `columnCodes 最多 ${SHEET_TOOL_MAX_COLUMNS} 列，请缩小投影范围（或交给缺省投影：主键列 + 前 4 列）。`
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

  const sampleRows = resolved.rows.slice(0, limit)
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
  return { ok: true, text: capped.text }
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
    return { ok: true, text: capped.text }
  }

  const valueCol = metricCol ?? 0
  const scanColumns = Array.from(new Set([groupBy, valueCol]))
  const groupIdx = scanColumns.indexOf(groupBy)
  const valueIdx = scanColumns.indexOf(valueCol)
  const groups = new Map<string, GroupEntry>()
  scanRows(ctx, resolved.rows, scanColumns, (row, values) => {
    const key = cellDisplay(values[groupIdx]).trim()
    const label = key === '' ? '（空）' : key
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
  return { ok: true, text: capped.text }
}

/** 单列批读（一次 `getArray` 扫完给定行），供总计/条件计数复用。 */
function readSingleColumn(ctx: SheetContext, rows: readonly number[], col: number): unknown[] {
  const values: unknown[] = []
  scanRows(ctx, rows, [col], (_row, cells) => {
    values.push(cells[0])
  })
  return values
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
  return { ok: true, text: capped.text }
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
  return { ok: true, text: capped.text }
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
  return { ok: true, text: capped.text }
}

// ── 写侧：计划与预览文本 ─────────────────────────────────────────────────────

let planSequence = 0

function nextPlanId(toolCallId: string): string {
  planSequence += 1
  return `${toolCallId || 'plan'}-${planSequence}`
}

function makePlan(input: {
  ctx: SheetContext
  tool: string
  toolCallId: string
  preview: SheetPreviewLevel
  scopeSummary: string
  predicateSummary: string
  targetRows: number[]
  changes: SheetChange[]
  skippedCount: number
  rulesText: string
  warnings: string[]
}): SheetWritePlan {
  const rows = new Set(input.changes.map((change) => change.row))
  const rowsCount = rows.size
  return {
    planId: nextPlanId(input.toolCallId),
    toolCallId: input.toolCallId,
    defCode: input.ctx.defCode,
    tool: input.tool,
    level: rowsCount > SHEET_WRITE_LARGE_ROW_THRESHOLD || input.changes.length > SHEET_WRITE_SMALL_DIFF_MAX_CELLS ? 'large' : 'small',
    preview: input.preview,
    scopeSummary: input.scopeSummary,
    predicateSummary: input.predicateSummary,
    rowsCount,
    cellsCount: input.changes.length,
    skippedCount: input.skippedCount,
    samples: input.changes.slice(0, SHEET_PREVIEW_SAMPLE_PAIRS),
    changes: input.changes,
    targetRows: input.targetRows,
    fingerprint: fingerprintRows(input.ctx, input.targetRows, keyColumns(input.ctx)),
    warnings: input.warnings,
    rulesText: input.rulesText,
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

/** 成功应用后的回灌文案（必须含“用户已确认 / 未保存”，可断言）。 */
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
  lines.push('未保存：改动仅在浏览器内存，需用户点「保存到服务端」才落库；可撤销（undo_last_sheet_edit）')
  lines.push(`共 ${plan.cellsCount} 格`)
  return lines.join('\n')
}

export function planCancelledText(): string {
  return '用户取消了本次修改，未改动任何单元格。\n共 0 格'
}

// ── 写侧：replace_values ─────────────────────────────────────────────────────

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

  const changes: SheetChange[] = []
  let skipped = 0
  const cells: unknown[][] = []
  scanRows(ctx, scope.resolved.rows, columns, (_row, values) => {
    cells.push([...values])
  })
  scope.resolved.rows.forEach((row, rowIndex) => {
    const line = cells[rowIndex] ?? []
    columns.forEach((col, colIndex) => {
      const oldValue = line[colIndex]
      const text = cellDisplay(oldValue)
      const matched = matcher.match(text)
      if (!matched.hit || matched.next === text) return
      const field = fieldOfColumn(ctx, col)
      if (field && validateCell(field, matched.next) !== null) {
        skipped += 1
        return
      }
      changes.push({ row, col, oldValue, newValue: matched.next })
    })
  })
  if (changes.length > SHEET_WRITE_HARD_MAX_CELLS) {
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
    scopeSummary: scope.resolved.summary,
    predicateSummary: scope.resolved.predicateSummary,
    targetRows: scope.resolved.rows,
    changes,
    skippedCount: skipped,
    rulesText: matcher.rulesText,
    warnings: []
  })
  return { ok: true, plan }
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

  const changes: SheetChange[] = []
  let skipped = 0
  let fillCells = 0
  const warnings: string[] = []
  const ruleParts: string[] = []

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
          skipped += 1
          return
        }
        changes.push({ row, col, oldValue, newValue: value })
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
          skipped += 1
          return
        }
        changes.push({ row, col, oldValue, newValue: next })
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
          skipped += 1
          continue
        }
        changes.push({ row, col, oldValue: value, newValue: last })
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
          skipped += 1
          return
        }
        changes.push({ row, col, oldValue: value, newValue: next })
      })
    }
  }

  if (changes.length > SHEET_WRITE_HARD_MAX_CELLS) {
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
    scopeSummary: scope.resolved.summary,
    predicateSummary: scope.resolved.predicateSummary,
    targetRows: rows,
    changes,
    skippedCount: skipped,
    rulesText: ruleParts.join('；'),
    warnings
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
}

function computeNormalize(ctx: SheetContext, rows: number[], setup: NormalizeSetup): NormalizeDiff {
  const changes: SheetChange[] = []
  const ruleCounts = new Map<string, number>()
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
      changes.push({ row, col, oldValue, newValue: next })
    })
  }
  return { changes, ruleCounts, skipped }
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
  const diff = computeNormalize(ctx, scope.resolved.rows, parsed.setup)
  if (diff.changes.length > SHEET_WRITE_HARD_MAX_CELLS) {
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
    scopeSummary: scope.resolved.summary,
    predicateSummary: scope.resolved.predicateSummary,
    targetRows: scope.resolved.rows,
    changes: diff.changes,
    skippedCount: diff.skipped,
    rulesText: parsed.setup.rules.map((rule) => ruleLabel(rule, parsed.setup)).join(' + '),
    warnings: []
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

const undoPatches = new WeakMap<SheetAdapter, { planId: string; defCode: string; changes: SheetChange[] }>()

export type ApplySheetPlanOutcome =
  | { ok: true; text: string; cells: number }
  | { ok: false; reason: string }

/**
 * 应用计划：先逐行复核指纹（任一不符 ⇒ 整批拒绝），再分块 `writeCells`，最后登记反向补丁。
 */
export function applySheetPlan(ctx: SheetContext, plan: SheetWritePlan): ApplySheetPlanOutcome {
  const readOnly = requireReadable(ctx)
  if (readOnly) return { ok: false, reason: readOnly }
  if (plan.defCode !== ctx.defCode) {
    return { ok: false, reason: `表格已切换（当前 ${ctx.defCode}，预览属于 ${plan.defCode}），本次未应用任何改动。请重新发起。` }
  }
  if (plan.status !== 'pending') {
    return { ok: false, reason: '该预览已失效或已处理，本次未应用任何改动。请重新发起。' }
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
    undoPatches.set(ctx.adapter, { planId: plan.planId, defCode: plan.defCode, changes: plan.changes })
  }
  return { ok: true, text: planAppliedText(plan, ctx), cells: plan.changes.length }
}

/** 撤销最近一次 AI 修改（深度 1，反向补丁；不依赖 undo 栈）。 */
export function undoLastSheetEdit(ctx: SheetContext): SheetToolOutcome {
  const readOnly = requireReadable(ctx)
  if (readOnly) return { ok: false, reason: readOnly }
  const patch = undoPatches.get(ctx.adapter)
  if (!patch || patch.defCode !== ctx.defCode || patch.changes.length === 0) {
    return { ok: true, text: '没有可撤销的 AI 修改。' }
  }
  const reverse: SheetCellUpdate[] = patch.changes.map((change) => ({
    row: change.row,
    col: change.col,
    value: change.oldValue
  }))
  for (const chunk of chunkUpdates(reverse)) {
    ctx.adapter.writeCells(chunk)
  }
  undoPatches.delete(ctx.adapter)
  return { ok: true, text: `已撤销 ${patch.changes.length} 格（回到修改前值）。\n共 ${patch.changes.length} 格` }
}

/** 写工具名 → 是否需要预览（normalize 只有 commit=true 才走预览）。 */
export function needsSheetPreview(name: string, args: SheetArgs): boolean {
  if (name === 'replace_values' || name === 'update_rows') return true
  if (name === 'normalize_column') return args.commit === true
  return false
}
