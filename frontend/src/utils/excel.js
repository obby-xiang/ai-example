import GC from '@grapecity/spread-sheets'
import * as spreadExcel from '@grapecity/spread-excelio'
import JSZip from 'jszip'

const DV = GC.Spread.Sheets.DataValidation
const CMP = GC.Spread.Sheets.ConditionalFormatting.ComparisonOperators

/** 字段显示名（必填加 *） */
export function headerText(f) {
  return f.required ? `*${f.name}` : f.name
}

/** 把动态字段定义应用到 sheet 视口第 0 行（ExcelIO 不导出 colHeader 区域，必须写视口） */
export function applyColumnsToSheet(sheet, fields) {
  sheet.suspendPaint()
  try {
    sheet.setRowCount(Math.max(sheet.getRowCount(), 200), GC.Spread.Sheets.SheetArea.viewport)
    sheet.setColumnCount(fields.length, GC.Spread.Sheets.SheetArea.viewport)
    fields.forEach((f, i) => {
      const col = sheet.getColumn(i, GC.Spread.Sheets.SheetArea.viewport)
      col.width(Math.min(200, Math.max(90, headerText(f).length * 16 + 30)))
      col.formatter(f.dataType === 'DATE' ? 'yyyy-mm-dd' : undefined)
      // 必填列高亮
      col.backColor(f.required ? '#fff7e6' : undefined)
      // 数据校验
      if (f.dataType === 'ENUM' && f.options?.length) {
        const list = f.options.map(normalizeScope).join(',')
        const validator = DV.createListValidator(list)
        validator.inputTitle('请选择')
        validator.inputMessage('可选值：' + list)
        validator.errorTitle('取值不合法')
        validator.errorMessage('可选值：' + list)
        sheet.getCell(-1, i, GC.Spread.Sheets.SheetArea.viewport).validator(validator)
      } else if (f.dataType === 'SCOPE' && f.options?.length) {
        const list = f.options.map((o) => o.split('|')[0]).join(',')
        const validator = DV.createListValidator(list)
        validator.errorTitle('取值不合法')
        validator.errorMessage('可选值：' + list)
        sheet.getCell(-1, i, GC.Spread.Sheets.SheetArea.viewport).validator(validator)
      } else if (f.dataType === 'INT') {
        const validator = DV.createNumberValidator('Integer', null, null, false)
        validator.errorTitle('类型不合法')
        validator.errorMessage('必须为整数')
        sheet.getCell(-1, i, GC.Spread.Sheets.SheetArea.viewport).validator(validator)
      } else if (f.dataType === 'DECIMAL') {
        const validator = DV.createNumberValidator('Decimal', null, null, false)
        validator.errorTitle('类型不合法')
        validator.errorMessage('必须为数值')
        sheet.getCell(-1, i, GC.Spread.Sheets.SheetArea.viewport).validator(validator)
      } else if (f.dataType === 'DATE') {
        const validator = DV.createDateValidator(
          new Date(2000, 0, 1), new Date(2099, 11, 31), 'yyyy-mm-dd')
        validator.errorTitle('日期不合法')
        validator.errorMessage('格式应为 yyyy-mm-dd')
        sheet.getCell(-1, i, GC.Spread.Sheets.SheetArea.viewport).validator(validator)
      } else if (f.dataType === 'BOOL') {
        const validator = DV.createFormulaListValidator('"true,false"')
        validator.errorTitle('布尔值不合法')
        validator.errorMessage('必须为 true/false')
        sheet.getCell(-1, i, GC.Spread.Sheets.SheetArea.viewport).validator(validator)
      }
      if (f.key) {
        sheet.getCell(0, i, GC.Spread.Sheets.SheetArea.viewport)
          .foreColor('#c0392b')
        col.tag({ key: f.code })
      }
    })
    // 表头写视口第 0 行
    fields.forEach((f, i) => {
      sheet.setValue(0, i, headerText(f), GC.Spread.Sheets.SheetArea.viewport)
      sheet.getCell(0, i).font('bold 12px sans-serif')
      sheet.getCell(0, i).backColor('#eef2f7')
      sheet.getCell(0, i).hAlign(GC.Spread.Sheets.HorizontalAlign.center)
    })
    sheet.frozenRowCount(1)
    sheet.options.selectionBackColor('rgba(64,158,255,0.2)')
  } finally {
    sheet.resumePaint()
  }
}

function normalizeScope(s) {
  if (s == null) return ''
  const idx = s.indexOf('|')
  return idx > 0 ? s.substring(0, idx) : String(s).trim()
}

/** 写入数据行（从第 1 行开始） */
export function setRows(sheet, fields, rows) {
  sheet.suspendPaint()
  try {
    sheet.setRowCount(Math.max(rows.length + 2, 200), GC.Spread.Sheets.SheetArea.viewport)
    fields.forEach((f, c) => {
      rows.forEach((row, r) => {
        sheet.setValue(r + 1, c, cellValue(f, row[f.code]))
      })
    })
  } finally {
    sheet.resumePaint()
  }
}

function cellValue(f, v) {
  if (v == null || v === '') return ''
  if (f.dataType === 'INT' || f.dataType === 'DECIMAL') {
    const n = Number(v)
    return Number.isNaN(n) ? v : n
  }
  if (f.dataType === 'BOOL') {
    return String(v).toLowerCase() === 'true'
  }
  return normalizeScope(v)
}

/** 表头归一化匹配：trim、去 * 前缀、忽略大小写与全角差异 */
function normalizeHeader(h) {
  return String(h == null ? '' : h).trim()
    .replace(/^\*\s*/, '')
    .replace(/（/g, '(').replace(/）/g, ')')
    .toLowerCase()
}

/**
 * 从 sheet 读出数据行：按表头匹配字段（名称或编码），做类型转换。
 * 返回 { rows, missingHeaders, invalidValues }
 */
export function readRows(sheet, fields) {
  const colCount = sheet.getColumnCount(GC.Spread.Sheets.SheetArea.viewport)
  const rowCount = sheet.getRowCount(GC.Spread.Sheets.SheetArea.viewport)
  // 表头映射
  const colToField = {}
  const headerSet = new Set()
  for (let c = 0; c < colCount; c++) {
    const raw = sheet.getValue(0, c, GC.Spread.Sheets.SheetArea.viewport)
    if (raw == null || String(raw).trim() === '') continue
    const norm = normalizeHeader(raw)
    headerSet.add(norm)
    const f = fields.find((x) => normalizeHeader(x.name) === norm
      || normalizeHeader(x.code) === norm)
    if (f) colToField[c] = f
  }
  const missing = fields.filter((f) =>
    !headerSet.has(normalizeHeader(f.name)) && !headerSet.has(normalizeHeader(f.code)))
  const rows = []
  let blankTail = 0
  for (let r = 1; r < rowCount; r++) {
    const row = {}
    let hasValue = false
    for (const [cStr, f] of Object.entries(colToField)) {
      const v = sheet.getValue(r, Number(cStr), GC.Spread.Sheets.SheetArea.viewport)
      if (v !== null && v !== undefined && String(v).trim() !== '') {
        hasValue = true
      }
      row[f.code] = v == null ? '' : String(v).trim()
    }
    if (!hasValue) {
      blankTail++
      continue
    }
    rows.push(row)
  }
  void blankTail
  return { rows, missingHeaders: missing.map((m) => m.name) }
}

/** 离线 Workbook：模板/解析文件用（隐藏 div，必须非零尺寸） */
export function createHiddenWorkbook() {
  const host = document.createElement('div')
  host.style.cssText = 'position:fixed;left:-99999px;top:0;width:800px;height:600px;'
  document.body.appendChild(host)
  const workbook = new GC.Spread.Sheets.Workbook(host)
  return { workbook, dispose: () => { try { workbook.destroy() } catch { /* ignore */ } host.remove() } }
}

/** spread 实例导出为 xlsx Blob */
export function workbookToBlob(spreadOrWorkbook) {
  return new Promise((resolve, reject) => {
    const excelIO = new spreadExcel.IO()
    excelIO.save(JSON.stringify(spreadOrWorkbook.toJSON()), (blob) => resolve(blob), (e) => reject(e))
  })
}

/** 模板：fields + 示例行 */
export async function buildTemplate(fields, configName) {
  const { workbook, dispose } = createHiddenWorkbook()
  try {
    const sheet = workbook.getSheet(0)
    sheet.name(safeSheetName(configName))
    applyColumnsToSheet(sheet, fields)
    const sample = {}
    fields.forEach((f) => {
      sample[f.code] = f.sampleValue ?? (f.dataType === 'BOOL' ? 'true' : '')
      if (f.dataType === 'SCOPE' && f.options?.length) {
        sample[f.code] = f.options[0].split('|')[0]
      }
      if (f.dataType === 'ENUM' && f.options?.length) {
        sample[f.code] = f.options[0]
      }
    })
    setRows(sheet, fields, [sample])
    return await workbookToBlob(workbook)
  } finally {
    dispose()
  }
}

/** 文件 → rows（xlsx 解析 + 表头匹配 + 类型转换） */
export function parseExcelFile(file) {
  return new Promise((resolve, reject) => {
    const excelIO = new spreadExcel.IO()
    excelIO.open(file, (json) => {
      try {
        const { workbook, dispose } = createHiddenWorkbook()
        try {
          workbook.fromJSON(json)
          resolve(workbook)
        } finally {
          dispose()
        }
      } catch (e) {
        reject(e)
      }
    }, (e) => reject(new Error('Excel 解析失败：' + (e?.errorMessage || e))))
  })
}

export function safeSheetName(name) {
  return String(name || 'Sheet1')
    .replace(/[\\\/\?\*\[\]:]/g, '_')
    .slice(0, 31)
}

/** 下载 blob */
export function downloadBlob(blob, fileName) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 3000)
}

/** zip 打包下载多个文件 */
export async function zipAndDownload(files, zipName) {
  const zip = new JSZip()
  const used = new Set()
  for (const f of files) {
    let name = f.name
    let i = 2
    while (used.has(name)) {
      const dot = f.name.lastIndexOf('.')
      name = dot > 0 ? `${f.name.slice(0, dot)}(${i})${f.name.slice(dot)}` : `${f.name}(${i})`
      i++
    }
    used.add(name)
    zip.file(name, f.blob)
  }
  const blob = await zip.generateAsync({ type: 'blob' })
  downloadBlob(blob, zipName)
}
