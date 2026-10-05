/**
 * ExcelIO 封装 —— 纯浏览器端完成 xlsx 模板生成 / 上传解析 / 导出 / zip 打包。
 * 字段模型（契约）：{ name, label, type, required, options?, maxLength?, ref? }
 * 类型：STRING / NUMBER / DATE(yyyy-MM-dd) / ENUM(options 下拉) / BOOLEAN
 *
 * mode:
 *   'runtime'  —— 挂载到 DOM 的运行时 Workbook：表头写 colHeader 区，数据从行 0 开始
 *   'template' —— 离线 Workbook（模板/导出）：表头写 viewport 行 0，数据从行 1 开始
 */
import GC from '@grapecity/spread-sheets'
import * as spreadExcel from '@grapecity/spread-excelio'
import JSZip from 'jszip'
import dayjs from 'dayjs'

const DV = GC.Spread.Sheets.DataValidation
const CMP = GC.Spread.Sheets.ConditionalFormatting.ComparisonOperators
const AREA = GC.Spread.Sheets.SheetArea

function columnLetter(idx) {
  let s = ''
  let n = idx
  while (n >= 0) {
    s = String.fromCharCode(65 + (n % 26)) + s
    n = Math.floor(n / 26) - 1
  }
  return s
}

/** 浏览器下载 Blob */
export function saveBlob(blob, fileName) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  setTimeout(() => URL.revokeObjectURL(url), 2000)
}

/**
 * 按字段定义建列 + DataValidation（SpreadSheet.vue 与离线 Workbook 共用）。
 */
export function applyFieldsToSheet(sheet, fields, mode = 'runtime') {
  const cols = fields || []
  sheet.setColumnCount(Math.max(cols.length, 1), AREA.viewport)

  for (let i = 0; i < cols.length; i++) {
    const f = cols[i] || {}
    const label = f.label || f.name || ('列' + (i + 1))
    if (mode === 'template') {
      sheet.setValue(0, i, label, AREA.viewport)
    } else {
      sheet.setValue(0, i, label, AREA.colHeader)
    }
    sheet.setTag(0, i, f.name, AREA.colHeader)
    sheet.setColumnWidth(i, Math.max(100, 16 * String(label).length + 40))

    const type = f.type || 'STRING'

    if (type === 'ENUM' && Array.isArray(f.options) && f.options.length) {
      const joined = f.options.map(String).join(',')
      let dv
      if (joined.length <= 200) {
        dv = DV.createListValidator(joined)
      } else {
        // 长选项兜底：Excel List 公式上限 255 字符，改用隐藏列区域引用
        const optCol = i + 200
        sheet.setColumnCount(Math.max(sheet.getColumnCount(), optCol + 1))
        f.options.forEach((o, r) => sheet.setValue(r, optCol, String(o)))
        sheet.setColumnVisible(optCol, false)
        const colL = columnLetter(optCol)
        dv = DV.createListValidator('=$' + colL + '$1:$' + colL + '$' + f.options.length)
      }
      dv.showInputMessage(true)
      dv.inputMessage('请从下拉中选择:' + f.options.join('、'))
      dv.showErrorMessage(true)
      dv.errorMessage('选项不在允许的列表中')
      sheet.setDataValidator(-1, i, dv)
      if (mode === 'runtime') {
        sheet.setCellType(-1, i, new GC.Spread.Sheets.CellTypes.ComboBox().items(
          f.options.map((o) => ({ text: String(o), value: String(o) }))
        ))
      }
    } else if (type === 'NUMBER') {
      const dv = DV.createNumberValidator(CMP.greaterThanOrEqualsTo, -1e18, 1e18)
      dv.showErrorMessage(true)
      dv.errorMessage('请输入数字')
      sheet.setDataValidator(-1, i, dv)
      sheet.setFormatter(-1, i, '0.####')
    } else if (type === 'DATE') {
      sheet.setFormatter(-1, i, 'yyyy-mm-dd')
    } else if (type === 'BOOLEAN') {
      if (mode === 'runtime') {
        sheet.setCellType(-1, i, new GC.Spread.Sheets.CellTypes.CheckBox().textTrue('是').textFalse('否'))
      } else {
        const dv = DV.createListValidator('true,false')
        dv.showErrorMessage(true)
        dv.errorMessage('请输入 true 或 false')
        sheet.setDataValidator(-1, i, dv)
      }
    }

    if (f.required) {
      const dv = DV.createFormulaValidator('NOT(ISBLANK(INDIRECT(ADDRESS(ROW(),COLUMN()))))')
      dv.showErrorMessage(true)
      dv.errorMessage(label + '为必填项')
      sheet.setDataValidator(-1, i, dv)
      const area = mode === 'template' ? AREA.viewport : AREA.colHeader
      sheet.getCell(0, i, area)
        .value('* ' + label)
        .foreColor('#f56c6c')
        .font('bold 12px PingFang SC')
    }
  }

  sheet.frozenRowCount(1)
  sheet.setRowHeight(0, 28)
}

/** 离线 Workbook（不挂载到可见 DOM；非零尺寸避免部分 API 异常） */
export function createOfflineWorkbook(def, mode = 'template') {
  const host = document.createElement('div')
  host.style.cssText = 'position:fixed;left:-99999px;top:0;width:800px;height:600px'
  document.body.appendChild(host)
  const spread = new GC.Spread.Sheets.Workbook(host, { sheetCount: 1 })
  const sheet = spread.getActiveSheet()
  sheet.name(safeSheetName((def && (def.name || def.code)) || 'Sheet1'))
  applyFieldsToSheet(sheet, (def && def.fields) || [], mode)
  return { spread, sheet, host }
}

export function destroyOfflineWorkbook(ctx) {
  if (!ctx) return
  try { ctx.spread && ctx.spread.dispose && ctx.spread.dispose() } catch (e) { /* ignore */ }
  if (ctx.host && ctx.host.parentNode) ctx.host.parentNode.removeChild(ctx.host)
}

function safeSheetName(name, fallback = 'Sheet') {
  const s = String(name || fallback).replace(/[:\\/?*[\]]/g, '_').slice(0, 31)
  return s || fallback
}

function workbookToBlob(spread) {
  return new Promise((resolve, reject) => {
    const excelIO = new spreadExcel.IO()
    excelIO.save(JSON.stringify(spread.toJSON()), (blob) => resolve(blob), (e) => reject(e))
  })
}

// ===================== 模板生成 =====================

/** 单个配置项 → 模板 xlsx Blob（表头 + 下拉校验 + 50 空行） */
export async function buildTemplateBlob(def) {
  const ctx = createOfflineWorkbook(def, 'template')
  try {
    ctx.sheet.setRowCount(51)
    return await workbookToBlob(ctx.spread)
  } finally {
    destroyOfflineWorkbook(ctx)
  }
}

/** 单个配置项 → 数据 xlsx Blob（表头 + rows 数据） */
export async function buildRowsBlob(def, rows) {
  const ctx = createOfflineWorkbook(def, 'template')
  try {
    fillSheetWithRows(ctx.sheet, def.fields || [], rows || [], 'template')
    return await workbookToBlob(ctx.spread)
  } finally {
    destroyOfflineWorkbook(ctx)
  }
}

function fillSheetWithRows(sheet, fields, rows, mode = 'template') {
  const startRow = mode === 'template' ? 1 : 0
  sheet.suspendPaint()
  try {
    sheet.setRowCount(Math.max(rows.length + startRow, startRow + 1))
    rows.forEach((row, r) => {
      const data = row.data || row || {}
      for (let i = 0; i < fields.length; i++) {
        let value = data[fields[i].name]
        if (value === undefined || value === null) value = ''
        sheet.setValue(r + startRow, i, value)
      }
    })
  } finally {
    sheet.resumePaint()
  }
}

/**
 * 下载模板：1 个配置项 → <code>.xlsx；多个 → 模板.zip（内各 <code>.xlsx）
 * @param defs ConfigDefinition 数组
 */
export async function downloadTemplates(defs) {
  const list = defs || []
  if (!list.length) return { fileName: null }
  if (list.length === 1) {
    const blob = await buildTemplateBlob(list[0])
    const fileName = `${list[0].code}.xlsx`
    saveBlob(blob, fileName)
    return { fileName }
  }
  const zip = new JSZip()
  for (const def of list) {
    zip.file(`${def.code}.xlsx`, await buildTemplateBlob(def))
  }
  const blob = await zip.generateAsync({ type: 'blob' })
  const fileName = '配置模板.zip'
  saveBlob(blob, fileName)
  return { fileName }
}

/**
 * 下载导出文件：1 个 → <code>.xlsx；多个 → zip。
 * @param items [{defCode, fileName, fields, rows}] rows 为调用方收集的最终数据
 * @param codes 缺省为全部
 */
export async function downloadExportFiles(items, codes = null) {
  const picked = (items || []).filter((it) => !codes || codes.includes(it.defCode))
  if (!picked.length) return { fileName: null }
  const toDef = (it) => ({ code: it.defCode, name: it.defCode, fields: it.fields })
  if (picked.length === 1) {
    const it = picked[0]
    const blob = await buildRowsBlob(toDef(it), it.rows)
    const fileName = it.fileName || `${it.defCode}.xlsx`
    saveBlob(blob, fileName)
    return { fileName }
  }
  const zip = new JSZip()
  for (const it of picked) {
    zip.file(it.fileName || `${it.defCode}.xlsx`, await buildRowsBlob(toDef(it), it.rows))
  }
  const blob = await zip.generateAsync({ type: 'blob' })
  const fileName = '导出配置.zip'
  saveBlob(blob, fileName)
  return { fileName }
}

// ===================== 上传解析 =====================

function openExcelToJSON(file) {
  return new Promise((resolve, reject) => {
    const excelIO = new spreadExcel.IO()
    excelIO.open(file, (json) => resolve(json), (e) => reject(e))
  })
}

function normalizeHeader(s) {
  return s == null ? '' : String(s).trim().toLowerCase()
}

/** viewport 行 0 表头 → 字段名映射；兼容 label / name / 必填前缀 "* " */
function buildHeaderMap(sheet, fields) {
  const map = {}
  const used = new Set()
  for (let c = 0; c < sheet.getColumnCount(); c++) {
    let header = sheet.getValue(0, c)
    if (header == null) continue
    let h = String(header).trim()
    if (h.startsWith('* ')) h = h.slice(2).trim()
    else if (h.startsWith('*')) h = h.slice(1).trim()
    const norm = normalizeHeader(h)
    if (!norm) continue
    for (const f of fields) {
      if (used.has(f.name)) continue
      if (normalizeHeader(f.label) === norm || normalizeHeader(f.name) === norm) {
        map[c] = f.name
        used.add(f.name)
        break
      }
    }
  }
  return map
}

export function convertValue(v, field) {
  if (v === null || v === undefined) return null
  if (!field) return v
  const type = field.type || 'STRING'
  if (type === 'NUMBER') {
    const n = Number(v)
    return Number.isNaN(n) ? v : n
  }
  if (type === 'BOOLEAN') {
    const s = String(v).toLowerCase()
    if (v === true || v === 1 || s === 'true' || s === '1' || s === '是') return true
    if (v === false || v === 0 || s === 'false' || s === '0' || s === '否') return false
    return !!v
  }
  if (type === 'DATE') {
    if (v instanceof Date) return dayjs(v).format('YYYY-MM-DD')
    const d = dayjs(v)
    return d.isValid() ? d.format('YYYY-MM-DD') : v
  }
  return String(v)
}

/** 解析单个 xlsx Blob → { rows, errors }（rows: [{字段名: 值}]，跳过全空行） */
export async function parseXlsxBlob(blob, def) {
  const host = document.createElement('div')
  host.style.cssText = 'position:fixed;left:-99999px;top:0;width:800px;height:600px'
  document.body.appendChild(host)
  const spread = new GC.Spread.Sheets.Workbook(host, { sheetCount: 1 })
  try {
    const json = await openExcelToJSON(blob)
    spread.fromJSON(json)
    const sheet = spread.getActiveSheet()
    const fields = def.fields || []
    const headerMap = buildHeaderMap(sheet, fields)
    if (!Object.keys(headerMap).length) {
      return { rows: [], errors: ['未能从表头匹配到任何配置字段，请使用系统模板填写'] }
    }
    const errors = []
    for (const f of fields) {
      if (f.required && !Object.values(headerMap).includes(f.name)) {
        errors.push(`必填列「${f.label || f.name}」在 Excel 中缺失`)
      }
    }
    const rows = []
    for (let r = 1; r < sheet.getRowCount(); r++) {
      let hasData = false
      const data = {}
      for (const [colIdxStr, name] of Object.entries(headerMap)) {
        const field = fields.find((f) => f.name === name)
        let v = sheet.getValue(r, Number(colIdxStr))
        if (v !== null && v !== undefined && String(v) !== '') {
          hasData = true
          v = convertValue(v, field)
          if (field && field.type === 'ENUM' && Array.isArray(field.options) && field.options.length &&
              !field.options.map(String).includes(String(v))) {
            errors.push(`第 ${r + 1} 行「${field.label || name}」值「${v}」不在选项 [${field.options.join(',')}] 中`)
          }
          data[name] = v
        }
      }
      if (hasData) rows.push(data)
    }
    return { rows, errors }
  } finally {
    try { spread.dispose && spread.dispose() } catch (e) { /* ignore */ }
    if (host.parentNode) host.parentNode.removeChild(host)
  }
}

/** 按文件名中包含的配置项编码匹配（大小写不敏感，按编码长度降序优先精确） */
export function matchDefByFileName(fileName, defs) {
  const upper = String(fileName || '').toUpperCase()
  const sorted = [...(defs || [])].sort((a, b) => b.code.length - a.code.length)
  return sorted.find((d) => upper.includes(String(d.code).toUpperCase())) || null
}

/**
 * 解析上传文件（单 xlsx / 多 xlsx / zip 内多个 xlsx），按文件名编码匹配到配置项。
 * @param file  File（.xlsx 或 .zip）
 * @param defs  候选 ConfigDefinition 数组
 * @returns [{ fileName, def, rows, errors }] 未匹配到时 def 为 null
 */
export async function parseUpload(file, defs) {
  const name = file.name || ''
  const out = []
  if (/\.zip$/i.test(name)) {
    const zip = await JSZip.loadAsync(file)
    const entries = Object.values(zip.files).filter((e) => !e.dir && /\.xlsx$/i.test(e.name))
    for (const entry of entries) {
      const blob = await entry.async('blob')
      const def = matchDefByFileName(entry.name, defs)
      if (!def) {
        out.push({ fileName: entry.name, def: null, rows: [], errors: ['文件名未匹配到已选配置项编码'] })
        continue
      }
      const parsed = await parseXlsxBlob(blob, def)
      out.push({ fileName: entry.name, def, rows: parsed.rows, errors: parsed.errors })
    }
    if (!entries.length) out.push({ fileName: name, def: null, rows: [], errors: ['zip 内未发现 xlsx 文件'] })
  } else {
    const def = matchDefByFileName(name, defs)
    if (!def) {
      out.push({ fileName: name, def: null, rows: [], errors: ['文件名未匹配到已选配置项编码'] })
    } else {
      const parsed = await parseXlsxBlob(file, def)
      out.push({ fileName: name, def, rows: parsed.rows, errors: parsed.errors })
    }
  }
  return out
}
