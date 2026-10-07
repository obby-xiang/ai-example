/**
 * Excel 工具层（TS 重写，形态来源 glm-5.3 `utils/excel.js` 267 行，字段契约改为 main-v2 的
 * `ConfigField.label / fieldType / optionsJson / key`，并补上按字段定义生成的**动态校验器**）。
 *
 * 四件事：
 * 1. 动态校验器：把一个配置定义的字段结构变成一套校验规则，同时用于
 *    a) SpreadJS 工作表上的 DataValidation（下拉/数值/日期/布尔，视觉约束）；
 *    b) 纯函数 {@link validateRows}（行级错误明细，用于上传前本地预检与检查结果展示）；
 * 2. 模板/工作簿：按字段结构生成模板 xlsx（含 `_meta` 隐藏表，列映射与后端
 *    `ExcelTemplateBuilder` 同构，后端 `ExcelReader` 优先读 `_meta`）；
 * 3. 读写：工作簿 JSON ↔ xlsx Blob（ExcelIO），sheet ↔ 行对象（表头归一化匹配）；
 * 4. 打包/解包：JSZip 解 ZIP（上传前逐文件做编码匹配预览）与打 ZIP（勾选打包下载）。
 *
 * 说明：本模块**不**在顶层 import SpreadJS 运行时（`utils/spreadjs` 动态加载），
 * 因此 import 本模块不会把 4.7MB 的 spreadjs 拉进首屏 chunk。
 */

import JSZip from 'jszip'
import type GC from '@grapecity/spread-sheets'
import { parseFieldOptions, type ConfigField } from '@/types/definition'
import { loadSpreadCore, loadSpreadExcelIO, type SpreadSheets } from '@/utils/spreadjs'

/** SpreadJS 工作表类型（默认导入的名称空间下的 Worksheet）。 */
export type SpreadWorksheet = GC.Spread.Sheets.Worksheet

/** 默认视口行数（预留空行供手工录入，与 glm 蓝本一致）。 */
export const DEFAULT_SHEET_ROWS = 200

/** 模板内 `_meta` 隐藏表名（与后端 ExcelTemplateBuilder / ExcelReader 约定一致）。 */
export const META_SHEET_NAME = '_meta'

/** `_dict` 隐藏表名（ENUM 选项来源，与后端模板一致）。 */
export const DICT_SHEET_NAME = '_dict'

/** Excel 行号口径：数据行下标 i（0 基）→ 表头在第 1 行 ⇒ 行号 = i + 2（与后端 issue.rowIndex 一致）。 */
export function excelRowNumber(rowIndex: number): number {
  return rowIndex + 2
}

/** 列标题：必填加 `*` 前缀（后端 ExcelReader 匹配时会剥掉 `*`）。 */
export function headerText(field: ConfigField): string {
  return field.required ? `*${field.label}` : field.label
}

/** 工作表名安全化（Excel 限制 31 字符且禁 `\ / ? * [ ] :`）。 */
export function safeSheetName(name: string | null | undefined): string {
  const cleaned = String(name ?? 'Sheet1').replace(/[\\/?*[\]:]/g, '_')
  return cleaned.slice(0, 31) || 'Sheet1'
}

// ── 动态校验器 ────────────────────────────────────────────────────────────────

/** 单条行级校验问题（与后端 ValidationIssue 同口径，供前端本地预检展示）。 */
export interface ExcelRowIssue {
  /** Excel 行号（1 基，含表头行） */
  rowIndex: number
  fieldCode: string
  fieldLabel: string
  value: string
  reason: string
}

/** 逐类型的取值规范化：空串 → null；布尔 → true/false；数值 → 数字串。 */
export function normalizeCellValue(field: ConfigField, value: unknown): string {
  if (value === null || value === undefined) {
    return ''
  }
  const text = String(value).trim()
  if (text === '') {
    return ''
  }
  switch (field.fieldType) {
    case 'BOOLEAN':
      return isTruthyText(text) ? 'true' : isFalsyText(text) ? 'false' : text
    case 'NUMBER': {
      const num = Number(text)
      return Number.isFinite(num) ? String(num) : text
    }
    default:
      return text
  }
}

function isTruthyText(text: string): boolean {
  const lower = text.toLowerCase()
  return lower === 'true' || lower === '1' || text === '是' || text === 'Y' || lower === 'y'
}

function isFalsyText(text: string): boolean {
  const lower = text.toLowerCase()
  return lower === 'false' || lower === '0' || text === '否' || text === 'N' || lower === 'n'
}

/** ENUM 字段的合法取值集合（同时接受 code 与 label，便于用户按中文填）。 */
export function enumValues(field: ConfigField): string[] {
  const values = new Set<string>()
  for (const option of parseFieldOptions(field)) {
    if (option.value) {
      values.add(option.value)
    }
    if (option.label) {
      values.add(option.label)
    }
  }
  return [...values]
}

/**
 * 单字段校验（按字段定义生成规则）。
 *
 * 返回 null 表示通过，否则返回中文原因。规则：
 * - required 非空（必填）；
 * - NUMBER 必须可解析为有限数字；
 * - DATE 必须为 `yyyy-MM-dd`（或可被 Date 解析的常见写法）；
 * - ENUM 必须命中 optionsJson 的 value 或 label；
 * - BOOLEAN 必须为 true/false/是/否/1/0/y/n；
 * - REFERENCE 不做本地校验（引用完整性由后端预检查的 DependencyResolver 把关）。
 */
export function validateCell(field: ConfigField, rawValue: unknown): string | null {
  const value = rawValue === null || rawValue === undefined ? '' : String(rawValue).trim()
  if (value === '') {
    return field.required ? `必填字段 [${field.label}] 不能为空` : null
  }
  switch (field.fieldType) {
    case 'NUMBER': {
      const num = Number(value)
      return Number.isFinite(num) ? null : `字段 [${field.label}] 应为数字，实际为「${value}」`
    }
    case 'DATE': {
      const normalized = value.replace(/\//g, '-')
      if (!/^\d{4}-\d{1,2}-\d{1,2}$/.test(normalized)) {
        return `字段 [${field.label}] 应为日期（yyyy-MM-dd），实际为「${value}」`
      }
      const parsed = Date.parse(`${normalized}T00:00:00`)
      return Number.isNaN(parsed) ? `字段 [${field.label}] 日期不合法：「${value}」` : null
    }
    case 'ENUM': {
      const allowed = enumValues(field)
      if (allowed.length === 0) {
        return null
      }
      return allowed.includes(value)
        ? null
        : `字段 [${field.label}] 取值「${value}」不在可选范围（${allowed.slice(0, 6).join('、')}${allowed.length > 6 ? '…' : ''}）`
    }
    case 'BOOLEAN': {
      if (isTruthyText(value) || isFalsyText(value)) {
        return null
      }
      return `字段 [${field.label}] 应为布尔值（true/false 或 是/否），实际为「${value}」`
    }
    default:
      return null
  }
}

/**
 * 行级批量校验（本地预检）。
 *
 * 覆盖：逐字段类型/必填 + 主键字段非空 + 主键组合重复。
 * 返回的 rowIndex 用 **Excel 行号**口径（表头第 1 行 ⇒ 首条数据第 2 行），与后端一致。
 */
export function validateRows(fields: readonly ConfigField[], rows: readonly Record<string, unknown>[]): ExcelRowIssue[] {
  const issues: ExcelRowIssue[] = []
  const keyFields = fields.filter((field) => field.key)
  const seenKeys = new Map<string, number>()

  rows.forEach((row, index) => {
    const rowIndex = excelRowNumber(index)
    for (const field of fields) {
      const reason = validateCell(field, row[field.code])
      if (reason) {
        issues.push({
          rowIndex,
          fieldCode: field.code,
          fieldLabel: field.label,
          value: row[field.code] === null || row[field.code] === undefined ? '' : String(row[field.code]),
          reason
        })
      }
    }
    if (keyFields.length > 0) {
      const keyText = keyFields.map((field) => String(row[field.code] ?? '').trim()).join('|')
      if (keyText.replace(/\|/g, '').trim() !== '') {
        const first = seenKeys.get(keyText)
        if (first !== undefined) {
          issues.push({
            rowIndex,
            fieldCode: keyFields.map((field) => field.code).join('+'),
            fieldLabel: keyFields.map((field) => field.label).join('+'),
            value: keyText,
            reason: `主键重复: ${keyText}（第 ${first} 行已存在）`
          })
        } else {
          seenKeys.set(keyText, rowIndex)
        }
      }
    }
  })
  return issues
}

/** 表头归一化匹配：trim、剥 `*` 前缀、全角括号转半角、小写。 */
function normalizeHeader(value: unknown): string {
  return String(value ?? '')
    .trim()
    .replace(/^\*\s*/, '')
    .replace(/（/g, '(')
    .replace(/）/g, ')')
    .toLowerCase()
}

// ── 工作表写入（模板/编辑） ────────────────────────────────────────────────────

/**
 * 把字段结构应用到工作表视口：列宽、必填底色、主键红字、类型化格式、**动态校验器**、表头。
 *
 * 校验器逐个字段 try/catch（glm 蓝本实践）：单个字段的校验器创建失败不影响整表渲染。
 * `row = -1` 表示整列（列级校验器），ExcelIO 不导出 colHeader 区域，故表头必须写视口第 0 行。
 */
export function applyColumnsToSheet(gs: SpreadSheets, sheet: SpreadWorksheet, fields: readonly ConfigField[]): void {
  const DataValidation = gs.Spread.Sheets.DataValidation
  const ComparisonOperators = gs.Spread.Sheets.ConditionalFormatting.ComparisonOperators
  const SheetArea = gs.Spread.Sheets.SheetArea

  sheet.suspendPaint()
  try {
    sheet.setRowCount(Math.max(sheet.getRowCount(), DEFAULT_SHEET_ROWS), SheetArea.viewport)
    sheet.setColumnCount(Math.max(fields.length, 1), SheetArea.viewport)

    fields.forEach((field, index) => {
      sheet.setColumnWidth(index, Math.min(200, Math.max(90, headerText(field).length * 16 + 30)), SheetArea.viewport)
      const columnCell = sheet.getCell(-1, index, SheetArea.viewport)
      if (field.fieldType === 'DATE') {
        columnCell.formatter('yyyy-mm-dd')
      } else if (field.fieldType === 'NUMBER') {
        columnCell.formatter('General')
      }
      if (field.required) {
        columnCell.backColor('#fff7e6')
      }
      try {
        const validator = buildValidator(field, DataValidation, ComparisonOperators)
        if (validator) {
          columnCell.validator(validator)
        }
      } catch (error) {
        console.warn('[excel] 校验器创建失败，字段：' + field.code, error)
      }
    })

    fields.forEach((field, index) => {
      sheet.setValue(0, index, headerText(field), SheetArea.viewport)
      const headCell = sheet.getCell(0, index, SheetArea.viewport)
      headCell.font('bold 12px sans-serif')
      headCell.backColor('#eef2f7')
      headCell.hAlign(gs.Spread.Sheets.HorizontalAlign.center)
      if (field.key) {
        headCell.foreColor('#c0392b')
      }
    })

    try {
      sheet.frozenRowCount(1)
    } catch (error) {
      console.warn('[excel] 冻结首行失败', error)
    }
    try {
      sheet.options.selectionBackColor = 'rgba(64,158,255,0.2)'
    } catch (error) {
      console.warn('[excel] 选区颜色设置失败', error)
    }
  } finally {
    sheet.resumePaint()
  }
}

/** 按字段类型创建一个 SpreadJS 校验器（ENUM→下拉、NUMBER→数值、DATE→日期、BOOLEAN→布尔）。 */
function buildValidator(
  field: ConfigField,
  DataValidation: typeof GC.Spread.Sheets.DataValidation,
  ComparisonOperators: typeof GC.Spread.Sheets.ConditionalFormatting.ComparisonOperators
): GC.Spread.Sheets.DataValidation.DefaultDataValidator | null {
  let validator: GC.Spread.Sheets.DataValidation.DefaultDataValidator | null = null
  switch (field.fieldType) {
    case 'ENUM': {
      const list = enumValues(field).filter((value) => !value.includes(','))
      if (list.length === 0) {
        return null
      }
      validator = DataValidation.createListValidator(list.join(','))
      validator.inputTitle('请选择')
      validator.inputMessage(`可选值：${list.join('、')}`)
      break
    }
    case 'BOOLEAN': {
      validator = DataValidation.createFormulaListValidator('"true,false"')
      validator.inputTitle('请选择')
      validator.inputMessage('可选值：true / false')
      break
    }
    case 'NUMBER': {
      validator = DataValidation.createNumberValidator(ComparisonOperators.between, -1e15, 1e15, false)
      break
    }
    case 'DATE': {
      validator = DataValidation.createDateValidator(
        ComparisonOperators.between,
        new Date(2000, 0, 1),
        new Date(2099, 11, 31)
      )
      break
    }
    default:
      break
  }
  if (validator) {
    validator.errorTitle('取值不合法')
    validator.errorMessage(`「${field.label}」的取值不符合字段定义（类型：${field.fieldType}）`)
    validator.showErrorMessage(true)
  }
  return validator
}

/** 写入数据行（数据从视口第 1 行开始，第 0 行是表头；area 缺省即 viewport）。 */
export function setRows(
  sheet: SpreadWorksheet,
  fields: readonly ConfigField[],
  rows: readonly Record<string, unknown>[]
): void {
  sheet.suspendPaint()
  try {
    sheet.setRowCount(Math.max(rows.length + 2, DEFAULT_SHEET_ROWS))
    fields.forEach((field, columnIndex) => {
      rows.forEach((row, rowIndex) => {
        sheet.setValue(rowIndex + 1, columnIndex, spreadValue(field, row[field.code]))
      })
    })
  } finally {
    sheet.resumePaint()
  }
}

/** 字段值 → 单元格值（数值/布尔写成原生类型，便于 Excel 端格式与排序）。 */
function spreadValue(field: ConfigField, value: unknown): string | number | boolean {
  const text = normalizeCellValue(field, value)
  if (text === '') {
    return ''
  }
  if (field.fieldType === 'NUMBER') {
    const num = Number(text)
    return Number.isFinite(num) ? num : text
  }
  if (field.fieldType === 'BOOLEAN') {
    return text === 'true'
  }
  return text
}

/** 从工作表读回数据行（按表头匹配字段名或编码；空行自动跳过）。 */
export function readRows(
  sheet: SpreadWorksheet,
  fields: readonly ConfigField[]
): { rows: Array<Record<string, string>>; missingHeaders: string[] } {
  const columnCount = sheet.getColumnCount()
  const rowCount = sheet.getRowCount()
  const columnToField = new Map<number, ConfigField>()
  const seenHeaders = new Set<string>()

  for (let column = 0; column < columnCount; column += 1) {
    const raw = sheet.getValue(0, column)
    if (raw === null || raw === undefined || String(raw).trim() === '') {
      continue
    }
    const normalized = normalizeHeader(raw)
    seenHeaders.add(normalized)
    const hit = fields.find(
      (field) => normalizeHeader(field.label) === normalized || normalizeHeader(field.code) === normalized
    )
    if (hit) {
      columnToField.set(column, hit)
    }
  }

  const missingHeaders = fields
    .filter(
      (field) =>
        !seenHeaders.has(normalizeHeader(field.label)) && !seenHeaders.has(normalizeHeader(field.code))
    )
    .map((field) => field.label)

  const rows: Array<Record<string, string>> = []
  for (let row = 1; row < rowCount; row += 1) {
    const record: Record<string, string> = {}
    let hasValue = false
    for (const field of fields) {
      record[field.code] = ''
    }
    for (const [column, field] of columnToField) {
      const raw = sheet.getValue(row, column)
      const text = raw === null || raw === undefined ? '' : String(raw).trim()
      if (text !== '') {
        hasValue = true
      }
      record[field.code] = normalizeCellValue(field, text)
    }
    if (hasValue) {
      rows.push(record)
    }
  }
  return { rows, missingHeaders }
}

// ── 工作簿 / 文件 ─────────────────────────────────────────────────────────────

interface HiddenWorkbook {
  workbook: GC.Spread.Sheets.Workbook
  dispose: () => void
}

/** 离线工作簿（隐藏 div，必须非零尺寸否则 SpreadJS 初始化异常）。 */
function createHiddenWorkbook(gs: SpreadSheets): HiddenWorkbook {
  const host = document.createElement('div')
  host.style.cssText = 'position:fixed;left:-99999px;top:0;width:800px;height:600px;'
  document.body.appendChild(host)
  const workbook = new gs.Spread.Sheets.Workbook(host, { sheetCount: 1 })
  return {
    workbook,
    dispose: () => {
      try {
        workbook.destroy()
      } catch {
        // 销毁失败无需上抛（宿主即将移除）
      }
      host.remove()
    }
  }
}

/**
 * 行数据 → xlsx Blob（**自写 OOXML + JSZip**，不经 SpreadJS ExcelIO 保存）。
 *
 * 为什么不用 ExcelIO 保存（S4.4b 实测缺陷，见证据文档 V3 差异）：评估版 ExcelIO 在
 * `save()` 时会往工作簿**首位插入一张可见的 “Evaluation Version” 水印表**；后端
 * `ExcelReader#findDataSheet` 取"第一个非 `_` 前缀且未隐藏的表" —— 于是它读的是水印表，
 * 列映射（来自 `_meta`）虽对，数据却变成水印文本三行，导入/预检查结果全是垃圾行。
 *
 * 本函数按后端 `ExcelTemplateBuilder` 的形状自产文件：
 * - 表 1（可见）= 数据表：第 0 行表头（必填加 `*`），数据从第 1 行起；
 * - 表 2 `_dict`（隐藏）= ENUM 选项列（与模板一致，供 Excel 下拉使用）；
 * - 表 3 `_meta`（隐藏）= 第 0 行标签、第 1 行字段编码，第 0 列 `def_code`
 *   —— 正是 `ExcelReader#readMetaSheet` 的读取口径。
 *
 * 数值按字段类型写为数字单元格，其余一律写文本（枚举/布尔/日期保持字符串，
 * 后端 DataFormatter 的取值因此稳定且与界面所见一致）。
 */
export async function buildRowsXlsxBlob(
  fields: readonly ConfigField[],
  rows: readonly Record<string, unknown>[],
  options: { defCode: string; sheetName?: string | null }
): Promise<Blob> {
  const sheets: XlsxSheet[] = [
    {
      name: safeSheetName(options.sheetName),
      rows: [
        fields.map((field) => headerText(field)),
        ...rows.map((row) => fields.map((field) => cellValueForXlsx(field, row[field.code])))
      ]
    },
    buildDictSheet(fields),
    {
      name: META_SHEET_NAME,
      hidden: true,
      rows: [
        ['def_code', ...fields.map((field) => field.label)],
        [options.defCode, ...fields.map((field) => field.code)]
      ]
    }
  ]
  return await buildXlsx(sheets)
}

/** `_dict` 隐藏表：每个 ENUM 字段一列（第 0 行字段编码，其下是选项标签）。 */
function buildDictSheet(fields: readonly ConfigField[]): XlsxSheet {
  const enumFields = fields.filter((field) => field.fieldType === 'ENUM')
  const rows: XlsxCell[][] = [enumFields.map((field) => field.code)]
  const optionColumns = enumFields.map((field) => parseFieldOptions(field).map((option) => option.label))
  const depth = optionColumns.reduce((max, list) => Math.max(max, list.length), 0)
  for (let index = 0; index < depth; index += 1) {
    rows.push(optionColumns.map((list) => list[index] ?? null))
  }
  return { name: DICT_SHEET_NAME, hidden: true, rows }
}

/** 字段值 → 单元格值（NUMBER 写数字，其余写文本；空值写 null 即跳过该单元格）。 */
function cellValueForXlsx(field: ConfigField, value: unknown): XlsxCell {
  const text = normalizeCellValue(field, value)
  if (text === '') {
    return null
  }
  if (field.fieldType === 'NUMBER') {
    const num = Number(text)
    return Number.isFinite(num) ? num : text
  }
  return text
}

/** 单元格取值：文本 / 数字 / 空。 */
type XlsxCell = string | number | null

interface XlsxSheet {
  name: string
  hidden?: boolean
  rows: XlsxCell[][]
}

/** 列号 → 列名（0 → A，26 → AA）。 */
function columnLetter(index: number): string {
  let value = index + 1
  let letters = ''
  while (value > 0) {
    const rest = (value - 1) % 26
    letters = String.fromCharCode(65 + rest) + letters
    value = Math.floor((value - 1) / 26)
  }
  return letters
}

/** XML 文本转义（含控制字符剔除，非法字符会让 Excel/POI 直接报错）。 */
function escapeXmlText(value: string): string {
  return value
    .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

const XML_HEADER = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
const MAIN_NS = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'
const REL_NS = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'

/** 最小可用工作簿（数据表 + 隐藏表 + 共享字符串表；无边框/样式，与模板形状一致）。 */
async function buildXlsx(sheets: readonly XlsxSheet[]): Promise<Blob> {
  const sharedStrings: string[] = []
  const sharedIndex = new Map<string, number>()
  const toShared = (text: string): number => {
    const existing = sharedIndex.get(text)
    if (existing !== undefined) {
      return existing
    }
    const index = sharedStrings.length
    sharedStrings.push(text)
    sharedIndex.set(text, index)
    return index
  }

  const sheetXmlList = sheets.map((sheet) => {
    const rowsXml = sheet.rows
      .map((cells, rowIndex) => {
        const cellsXml = cells
          .map((cell, columnIndex) => {
            if (cell === null || cell === undefined || cell === '') {
              return ''
            }
            const ref = `${columnLetter(columnIndex)}${rowIndex + 1}`
            if (typeof cell === 'number') {
              return `<c r="${ref}"><v>${cell}</v></c>`
            }
            return `<c r="${ref}" t="s"><v>${toShared(cell)}</v></c>`
          })
          .join('')
        return cellsXml === '' ? '' : `<row r="${rowIndex + 1}">${cellsXml}</row>`
      })
      .join('')
    return `${XML_HEADER}<worksheet xmlns="${MAIN_NS}"><sheetData>${rowsXml}</sheetData></worksheet>`
  })

  const workbookSheets = sheets
    .map((sheet, index) => {
      const state = sheet.hidden ? ' state="hidden"' : ''
      return `<sheet name="${escapeXmlText(sheet.name)}" sheetId="${index + 1}"${state} r:id="rId${index + 1}"/>`
    })
    .join('')
  const workbookXml = `${XML_HEADER}<workbook xmlns="${MAIN_NS}" xmlns:r="${REL_NS}"><sheets>${workbookSheets}</sheets></workbook>`

  const rels = sheets
    .map((_, index) => `<Relationship Id="rId${index + 1}" Type="${REL_NS}/worksheet" Target="worksheets/sheet${index + 1}.xml"/>`)
    .join('')
  const stylesId = sheets.length + 1
  const sharedStringsId = sheets.length + 2
  const workbookRels = `${XML_HEADER}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${rels}`
    + `<Relationship Id="rId${stylesId}" Type="${REL_NS}/styles" Target="styles.xml"/>`
    + `<Relationship Id="rId${sharedStringsId}" Type="${REL_NS}/sharedStrings" Target="sharedStrings.xml"/>`
    + '</Relationships>'

  const contentTypes = `${XML_HEADER}<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">`
    + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    + '<Default Extension="xml" ContentType="application/xml"/>'
    + `<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>`
    + sheets.map((_, index) => `<Override PartName="/xl/worksheets/sheet${index + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`).join('')
    + '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
    + '<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>'
    + '</Types>'

  const rootRels = `${XML_HEADER}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">`
    + `<Relationship Id="rId1" Type="${REL_NS}/officeDocument" Target="xl/workbook.xml"/>`
    + '</Relationships>'

  const stylesXml = `${XML_HEADER}<styleSheet xmlns="${MAIN_NS}">`
    + '<fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>'
    + '<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>'
    + '<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>'
    + '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
    + '<cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>'
    + '</styleSheet>'

  const sharedStringsXml = `${XML_HEADER}<sst xmlns="${MAIN_NS}" count="${sharedStrings.length}" uniqueCount="${sharedStrings.length}">`
    + sharedStrings.map((text) => `<si><t xml:space="preserve">${escapeXmlText(text)}</t></si>`).join('')
    + '</sst>'

  const zip = new JSZip()
  zip.file('[Content_Types].xml', contentTypes)
  zip.file('_rels/.rels', rootRels)
  zip.file('xl/workbook.xml', workbookXml)
  zip.file('xl/_rels/workbook.xml.rels', workbookRels)
  zip.file('xl/styles.xml', stylesXml)
  zip.file('xl/sharedStrings.xml', sharedStringsXml)
  sheetXmlList.forEach((xml, index) => {
    zip.file(`xl/worksheets/sheet${index + 1}.xml`, xml)
  })
  return await zip.generateAsync({
    type: 'blob',
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
  })
}

/** xlsx Blob → 工作簿 JSON（ExcelIO 解析；读路径不受评估版水印影响）。 */
export async function blobToWorkbookJson(blob: Blob): Promise<string> {
  const excelIoModule = await loadSpreadExcelIO()
  const excelIo = new excelIoModule.IO()
  return await new Promise<string>((resolve, reject) => {
    excelIo.open(
      blob,
      (json: string) => resolve(json),
      (error: unknown) => reject(new Error(`Excel 解析失败：${describeExcelError(error)}`))
    )
  })
}

function describeExcelError(error: unknown): string {
  if (error && typeof error === 'object' && 'errorMessage' in error) {
    return String((error as { errorMessage?: unknown }).errorMessage ?? '未知错误')
  }
  if (error instanceof Error) {
    return error.message
  }
  return String(error)
}

/** Blob → ArrayBuffer（上传回写用）。 */
export async function blobToArrayBuffer(blob: Blob): Promise<ArrayBuffer> {
  return await blob.arrayBuffer()
}

/**
 * 生成模板工作簿 JSON：字段列 + 动态校验器 + 首行示例值 + `_meta` 隐藏表（列映射）。
 *
 * `_meta` 结构与后端 ExcelTemplateBuilder 一致（row0=标签、row1=编码、第 0 列 def_code），
 * 因此模板在线编辑后上传，后端 ExcelReader 可直接按编码对齐列。
 */
export async function buildTemplateWorkbookJson(
  fields: readonly ConfigField[],
  options: { sheetName?: string; defCode?: string; sample?: boolean; initialRows?: readonly Record<string, unknown>[] } = {}
): Promise<string> {
  const gs = await loadSpreadCore()
  const { workbook, dispose } = await createHiddenWorkbook(gs)
  try {
    const sheet = workbook.getSheet(0)
    sheet.name(safeSheetName(options.sheetName))
    applyColumnsToSheet(gs, sheet, fields)

    const rows: Array<Record<string, unknown>> = options.initialRows ? [...options.initialRows] : []
    if (rows.length === 0 && options.sample !== false) {
      rows.push(sampleRow(fields))
    }
    if (rows.length > 0) {
      setRows(sheet, fields, rows)
    }

    if (options.defCode) {
      const meta = new gs.Spread.Sheets.Worksheet(META_SHEET_NAME)
      meta.setValue(0, 0, 'def_code')
      meta.setValue(1, 0, options.defCode)
      fields.forEach((field, index) => {
        meta.setValue(0, index + 1, field.label)
        meta.setValue(1, index + 1, field.code)
      })
      workbook.addSheet(workbook.getSheetCount(), meta)
      meta.visible(false)
    }

    return JSON.stringify(workbook.toJSON())
  } finally {
    dispose()
  }
}

/** 示例行：ENUM/BOOLEAN 取第一个合法值，其余留空（避免示例值被误当真实数据提交）。 */
function sampleRow(fields: readonly ConfigField[]): Record<string, unknown> {
  const sample: Record<string, unknown> = {}
  for (const field of fields) {
    if (field.fieldType === 'ENUM') {
      const first = parseFieldOptions(field)[0]
      sample[field.code] = first ? first.value : ''
    } else if (field.fieldType === 'BOOLEAN') {
      sample[field.code] = 'true'
    } else {
      sample[field.code] = ''
    }
  }
  return sample
}

/** 模板 xlsx Blob（单个配置项，下载用；自产 OOXML，不经 ExcelIO）。 */
export async function buildTemplateBlob(
  fields: readonly ConfigField[],
  options: { sheetName?: string; defCode?: string } = {}
): Promise<Blob> {
  return await buildRowsXlsxBlob(fields, [], {
    defCode: options.defCode ?? '',
    sheetName: options.sheetName ?? null
  })
}

/** 工作簿 JSON → 行对象（用于上传前本地预检 / 在线编辑后回读）。 */
export async function readWorkbookJsonRows(
  json: string,
  fields: readonly ConfigField[]
): Promise<{ rows: Array<Record<string, string>>; missingHeaders: string[] }> {
  const gs = await loadSpreadCore()
  const { workbook, dispose } = await createHiddenWorkbook(gs)
  try {
    workbook.fromJSON(json)
    const sheet = findDataSheet(workbook)
    if (!sheet) {
      return { rows: [], missingHeaders: fields.map((field) => field.label) }
    }
    return readRows(sheet, fields)
  } finally {
    dispose()
  }
}

/** 取数据工作表（跳过 `_` 前缀的隐藏表，与后端 findDataSheet 同规则）。 */
export function findDataSheet(workbook: GC.Spread.Sheets.Workbook): SpreadWorksheet | null {
  const count = workbook.getSheetCount()
  for (let index = 0; index < count; index += 1) {
    const sheet = workbook.getSheet(index)
    const name = sheet.name()
    if (!name.startsWith('_')) {
      return sheet
    }
  }
  return count > 0 ? workbook.getSheet(0) : null
}

// ── JSZip：解包 / 打包 ────────────────────────────────────────────────────────

/** ZIP 条目（解包结果）。 */
export interface ZipEntry {
  name: string
  data: ArrayBuffer
}

/** 解 ZIP（返回非目录条目；失败时抛中文错误）。 */
export async function unzip(blob: Blob | ArrayBuffer): Promise<ZipEntry[]> {
  const zip = await JSZip.loadAsync(blob)
  const entries: ZipEntry[] = []
  for (const [name, file] of Object.entries(zip.files)) {
    if (file.dir) {
      continue
    }
    entries.push({ name, data: await file.async('arraybuffer') })
  }
  return entries
}

/** 打 ZIP（重名自动加 `(2)` 后缀，与 glm 蓝本一致）。 */
export async function zipBlobs(entries: ReadonlyArray<{ name: string; blob: Blob }>): Promise<Blob> {
  const zip = new JSZip()
  const used = new Set<string>()
  for (const entry of entries) {
    let name = entry.name
    let index = 2
    while (used.has(name)) {
      const dot = entry.name.lastIndexOf('.')
      name = dot > 0 ? `${entry.name.slice(0, dot)}(${index})${entry.name.slice(dot)}` : `${entry.name}(${index})`
      index += 1
    }
    used.add(name)
    zip.file(name, entry.blob)
  }
  return await zip.generateAsync({ type: 'blob' })
}

// ── 文件名规则（与后端 FileController#matchDefCode 对齐） ──────────────────────

/**
 * 文件名 → 配置编码（最长前缀匹配，容忍序号后缀与分隔符）。
 *
 * 后端规则：`base = 去掉 .xlsx/.xls 后缀的大写文件名`，
 * 命中条件 `base === code || base.startsWith(code + '_') || base.startsWith(code + '-')`，
 * 多个命中取**最长**编码（避免 PROJECT_PRICE 被 PROJECT 抢先命中）。
 * 序号后缀（`CURRENCY_1.xlsx`、`CURRENCY-2.xlsx`、`CURRENCY(3).xlsx`）因此天然命中。
 */
export function matchDefCode(fileName: string, defCodes: readonly string[]): string | null {
  const base = fileName.replace(/(?:\.[^.]+)?$/, '').toUpperCase()
  let best: string | null = null
  let bestLength = 0
  for (const code of defCodes) {
    const upper = code.toUpperCase()
    if (base === upper || base.startsWith(`${upper}_`) || base.startsWith(`${upper}-`) || base.startsWith(`${upper}(`)) {
      if (upper.length > bestLength) {
        best = code
        bestLength = upper.length
      }
    }
  }
  return best
}

/** 模板/导出文件名（`CODE_名称.xlsx`，与后端 Content-Disposition 一致）。 */
export function xlsxFileName(defCode: string, defName?: string | null): string {
  return defName ? `${defCode}_${defName}.xlsx` : `${defCode}.xlsx`
}

/** 触发浏览器下载（Excel 层的本地下载，与 api/http#saveBlob 同实现，避免 utils→api 反向依赖）。 */
export function downloadBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  setTimeout(() => URL.revokeObjectURL(url), 3000)
}

/** 从响应头解析文件名（后端用 `filename*=UTF-8''` 形态）。 */
export function parseContentDisposition(header: string | null | undefined, fallback: string): string {
  if (!header) {
    return fallback
  }
  const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(header)
  if (utf8 && utf8[1]) {
    try {
      return decodeURIComponent(utf8[1])
    } catch {
      return utf8[1]
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(header)
  return plain && plain[1] ? plain[1] : fallback
}
