/**
 * ExcelIO 能力封装 —— 纯前端,浏览器内完成 xlsx 导入/导出/模板生成。
 *
 * 依赖:@grapecity/spread-sheets + @grapecity/spread-excelio(均在 package.json)。
 * 不引入 file-saver,saveBlob 用手写 Blob + <a download>。
 *
 * 核心复用点:applyColumnsToSheet 从 SpreadSheet.vue 抽取,既能给挂载到 DOM 的
 * 运行时 Workbook 用(mode='runtime',含 __id/__mark 隐藏列),也能给内存中的
 * 离线 Workbook 用(mode='template',无隐藏列,数据列从列 0 开始)。
 *
 * DataValidation 保留性(已验证):SpreadJS 的 createListValidator/createNumberValidator/
 * createFormulaValidator 经 ExcelIO.save 导出 xlsx 后,保留为 Excel 原生 Data Validation
 * (下拉/数字范围/必填);反向导入亦保留。所以模板内嵌的校验在 Excel/WPS 打开即可用。
 *
 * 接口(与 ai-ui/src/utils/excel-io.js 签名一致,便于两范式复制):
 *   applyColumnsToSheet(sheet, configDef, mode)   列头+DataValidation(纯函数,SpreadSheet.vue 复用)
 *   createOfflineWorkbook(configDef, mode)        离线 workbook(不挂载可见 DOM)
 *   generateTemplate(configDef, opts)             生成 .xlsx 模板并下载
 *   importExcel(file, configDef, opts)            解析 .xlsx → 表头映射 → 校验 → rows
 *   exportExcel(spread, fileName, opts)            spread 实例 → 导出 .xlsx
 *   exportExcelByRows(configDef, rows, fileName)   rows + configDef → 离线 workbook → 导出
 *   exportExcelMultiSheet(defsAndRows, fileName)   多 configDef → 多 sheet 合并导出
 *   saveBlob(blob, fileName)                      浏览器下载
 */
import GC from '@grapecity/spread-sheets'
import * as spreadExcel from '@grapecity/spread-excelio'

const DV = GC.Spread.Sheets.DataValidation
const CMP = GC.Spread.Sheets.ConditionalFormatting.ComparisonOperators
const AREA = GC.Spread.Sheets.SheetArea

/**
 * 列索引(0 基)→ Excel 列字母(0→A, 25→Z, 26→AA...)。用于长选项辅助列 DV 公式引用。
 */
function columnLetter(idx) {
  let s = ''
  let n = idx
  while (n >= 0) {
    s = String.fromCharCode(65 + (n % 26)) + s
    n = Math.floor(n / 26) - 1
  }
  return s
}

/**
 * 列头 + DataValidation 应用(从 SpreadSheet.vue applyColumns 抽取的纯函数)。
 * @param sheet           SpreadJS worksheet
 * @param configDef       { columns: [{key,label,type,required,options,defaultValue}] }
 * @param mode            'runtime'(运行时,含 __id/__mark 隐藏列) | 'template'(模板/导出,无隐藏列)
 */
export function applyColumnsToSheet(sheet, configDef, mode = 'runtime') {
  const cols = (configDef && configDef.columns) || []
  const offset = mode === 'template' ? 0 : 2

  if (mode === 'runtime') {
    sheet.setColumnCount(cols.length + 2, AREA.viewport)
    // Col 0: __id (hidden)
    sheet.setValue(0, 0, '__id', AREA.colHeader)
    sheet.setColumnVisible(0, false)
    sheet.setTag(0, 0, '__id', AREA.colHeader)
    // Col 1: __mark (hidden)
    sheet.setValue(0, 1, '__mark', AREA.colHeader)
    sheet.setColumnVisible(1, false)
    sheet.setTag(0, 1, '__mark', AREA.colHeader)
  } else {
    sheet.setColumnCount(cols.length, AREA.viewport)
  }

  for (let i = 0; i < cols.length; i++) {
    const c = cols[i] || {}
    const colIdx = i + offset
    const label = c.label || c.key || ('col' + i)
    // template 模式:表头写入 viewport 行 0(ExcelIO 导出 xlsx 时 colHeader 区默认不导出,
    // 必须把表头放到 viewport 数据区行 0,xlsx 第 1 行才是表头)
    // runtime 模式:表头写入 SpreadJS UI colHeader 区(在线编辑用原生表头 UI)
    if (mode === 'template') {
      sheet.setValue(0, colIdx, label, AREA.viewport)
    } else {
      sheet.setValue(0, colIdx, label, AREA.colHeader)
    }
    sheet.setTag(0, colIdx, c.key, AREA.colHeader)
    sheet.setColumnWidth(colIdx, Math.max(100, 14 * String(label).length + 40))

    const type = c.type || 'string'

    if (type === 'select' && Array.isArray(c.options) && c.options.length) {
      // Excel List Validation 单元格公式上限 255 字符;留余量,超过 200 警告
      const joined = c.options.map(o => String(o)).join(',')
      if (joined.length <= 200) {
        const dv = DV.createListValidator(joined)
        dv.showInputMessage(true)
        dv.inputMessage('请从下拉中选择:' + c.options.join('、'))
        dv.showErrorMessage(true)
        dv.errorMessage('选项不在允许的列表中')
        sheet.setDataValidator(-1, colIdx, dv)
      } else {
        // 长选项兜底:Excel List 公式上限 255 字符,join 会超限被截断。
        // 改用本 sheet 远离数据区的隐藏列写入选项,DV 公式引用该列区域(本 sheet 引用免 sheet 名)。
        const optCol = colIdx + 200
        sheet.setColumnCount(Math.max(sheet.getColumnCount(), optCol + 1))
        c.options.forEach((o, i) => sheet.setValue(i, optCol, String(o)))
        sheet.setColumnVisible(optCol, false)
        const colL = columnLetter(optCol)
        const dv = DV.createListValidator('=$' + colL + '$1:$' + colL + '$' + c.options.length)
        dv.showInputMessage(true)
        dv.inputMessage('请从下拉中选择')
        dv.showErrorMessage(true)
        dv.errorMessage('选项不在允许的列表中')
        sheet.setDataValidator(-1, colIdx, dv)
      }
      // ComboBox CellType 仅运行时设(导出 xlsx 不保留,Excel 用原生下拉)
      if (mode === 'runtime') {
        sheet.setCellType(-1, colIdx, new GC.Spread.Sheets.CellTypes.ComboBox().items(
          c.options.map(o => ({ text: String(o), value: String(o) }))
        ))
      }
    } else if (type === 'number') {
      const dv = DV.createNumberValidator(CMP.greaterThanOrEqualsTo, -1e18, 1e18)
      dv.showErrorMessage(true)
      dv.errorMessage('请输入数字')
      sheet.setDataValidator(-1, colIdx, dv)
      sheet.setFormatter(-1, colIdx, '0.####')
    } else if (type === 'date') {
      sheet.setFormatter(-1, colIdx, 'yyyy-mm-dd')
    } else if (type === 'boolean') {
      if (mode === 'runtime') {
        const cellType = new GC.Spread.Sheets.CellTypes.CheckBox().textTrue('启用').textFalse('停用')
        sheet.setCellType(-1, colIdx, cellType)
      }
    }

    if (c.required) {
      const dv = DV.createFormulaValidator('NOT(ISBLANK(INDIRECT(ADDRESS(ROW(),COLUMN()))))')
      dv.showErrorMessage(true)
      dv.errorMessage((c.label || c.key) + '为必填项')
      sheet.setDataValidator(-1, colIdx, dv)
      // 列头加红点前缀
      sheet.getCell(0, colIdx, AREA.colHeader)
        .value('* ' + label)
        .foreColor('#f56c6c')
        .font('bold 12px PingFang SC')
    }
  }

  sheet.frozenRowCount(1)
  if (mode === 'runtime') {
    sheet.rowFilter = new GC.Spread.Sheets.Filter.HideRowFilter(
      new GC.Spread.Sheets.Range(-1, offset, -1, cols.length)
    )
  }
  sheet.setRowHeight(0, 28)
}

/**
 * 离线 Workbook(不挂载到可见 DOM)。非零尺寸,否则 SpreadJS 部分 API 异常。
 */
export function createOfflineWorkbook(configDef, mode = 'template') {
  const host = document.createElement('div')
  host.style.cssText = 'position:fixed;left:-99999px;top:0;width:800px;height:600px'
  document.body.appendChild(host)
  const spread = new GC.Spread.Sheets.Workbook(host, { sheetCount: 1 })
  const sheet = spread.getActiveSheet()
  sheet.name((configDef && (configDef.name || configDef.code)) || 'Sheet1')
  applyColumnsToSheet(sheet, configDef, mode)
  return { spread, sheet, host }
}

export function destroyOfflineWorkbook(ctx) {
  if (!ctx) return
  try { ctx.spread && ctx.spread.dispose && ctx.spread.dispose() } catch (e) {}
  if (ctx.host && ctx.host.parentNode) ctx.host.parentNode.removeChild(ctx.host)
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

// ===================== 模板下载 =====================

/**
 * 生成 .xlsx 模板(内嵌 Excel 原生数据验证)并下载。
 */
export async function generateTemplate(configDef, opts = {}) {
  const ctx = createOfflineWorkbook(configDef, 'template')
  try {
    // 留 50 行空白(继承列 DataValidation)
    ctx.sheet.setRowCount(51)
    const json = JSON.stringify(ctx.spread.toJSON())
    return await new Promise((resolve, reject) => {
      const excelIO = new spreadExcel.IO()
      excelIO.save(json, (blob) => {
        const fileName = opts.fileName || `${configDef.code || configDef.name || 'template'}-模板.xlsx`
        saveBlob(blob, fileName)
        resolve({ ok: true, fileName })
      }, (e) => reject(e))
    })
  } finally {
    destroyOfflineWorkbook(ctx)
  }
}

// ===================== Excel 导入 =====================

function openExcelToJSON(file) {
  return new Promise((resolve, reject) => {
    const excelIO = new spreadExcel.IO()
    excelIO.open(file, (json) => resolve(json), (e) => reject(e))
  })
}

function normalizeHeader(s) {
  if (s == null) return ''
  return String(s).trim().toLowerCase()
}

/**
 * 读 viewport 行 0 作为表头,按 label/key 反查 configDef.columns.key。
 * 匹配:去首尾空格 + 忽略大小写 + 去必填前缀 "* " + 兜底匹配 key。
 */
function buildHeaderMap(sheet, configDef) {
  const cols = (configDef && configDef.columns) || []
  const colCount = sheet.getColumnCount()
  const map = {} // { colIdx: key }
  const used = new Set()
  for (let c = 0; c < colCount; c++) {
    let header = sheet.getValue(0, c)
    if (header == null) continue
    let h = String(header).trim()
    if (h.startsWith('* ')) h = h.slice(2).trim()
    else if (h.startsWith('*')) h = h.slice(1).trim()
    const norm = normalizeHeader(h)
    if (!norm) continue
    for (const col of cols) {
      if (used.has(col.key)) continue
      if (normalizeHeader(col.label) === norm || normalizeHeader(col.key) === norm) {
        map[c] = col.key
        used.add(col.key)
        break
      }
    }
  }
  return map
}

function convertValue(v, colDef) {
  if (!colDef) return v
  const type = colDef.type || 'string'
  if (type === 'number') {
    const n = Number(v)
    return isNaN(n) ? v : n
  }
  if (type === 'boolean') {
    const s = String(v).toLowerCase()
    if (v === true || v === 1 || s === 'true' || s === '1' || s === '是') return true
    if (v === false || v === 0 || s === 'false' || s === '0' || s === '否') return false
    return !!v
  }
  return v
}

function collectRowsFromSheet(sheet, headerMap, configDef) {
  const cols = (configDef && configDef.columns) || []
  const rowCount = sheet.getRowCount()
  const rows = []
  const errors = []
  const warnings = []
  const headerCols = Object.keys(headerMap).map(Number)
  const presentKeys = new Set(Object.values(headerMap))
  let matched = 0, skipped = 0

  // 缺失必填列检查
  for (const c of cols) {
    if (c.required && !presentKeys.has(c.key)) {
      errors.push({ row: 0, field: c.label || c.key, msg: `必填列「${c.label || c.key}」在 Excel 中缺失` })
    }
  }
  // 多余列提示
  for (let c = 0; c < sheet.getColumnCount(); c++) {
    if (!(c in headerMap) && sheet.getValue(0, c) != null && String(sheet.getValue(0, c)).trim() !== '') {
      warnings.push({ row: 0, msg: `Excel 列「${sheet.getValue(0, c)}」未匹配到配置字段,忽略` })
    }
  }

  for (let r = 1; r < rowCount; r++) {
    let hasData = false
    const data = {}
    for (const colIdx of headerCols) {
      const key = headerMap[colIdx]
      const colDef = cols.find(c => c.key === key)
      let v = sheet.getValue(r, colIdx)
      if (v !== null && v !== undefined && String(v) !== '') {
        hasData = true
        v = convertValue(v, colDef)
        if (colDef && colDef.type === 'select' && Array.isArray(colDef.options) && colDef.options.length) {
          if (!colDef.options.map(String).includes(String(v))) {
            errors.push({ row: r + 1, field: colDef.label || key, msg: `值「${v}」不在选项 [${colDef.options.join(',')}] 中` })
          }
        }
        data[key] = v
      } else {
        data[key] = colDef ? colDef.defaultValue : null
      }
    }
    if (!hasData) { skipped++; continue }
    matched++
    rows.push({ id: null, data, mark: 'added' })
  }
  return { rows, errors, warnings, stats: { total: matched + skipped, matched, skipped } }
}

/**
 * 解析 .xlsx → 表头映射 → 类型转换 → 必填/选项校验 → rows(可直接喂 batchSave)。
 * @returns { ok, rows, errors, warnings, stats }
 */
export async function importExcel(file, configDef, opts = {}) {
  if (file && file.size > 5 * 1024 * 1024) {
    // eslint-disable-next-line no-console
    console.warn('[excel-io] 导入文件 ' + (file.size / 1024 / 1024).toFixed(1) + 'MB,解析可能较慢,请等待')
  }
  await new Promise(r => setTimeout(r, 0)) // 让出一帧,便于 UI 显示 loading
  const host = document.createElement('div')
  host.style.cssText = 'position:fixed;left:-99999px;top:0;width:800px;height:600px'
  document.body.appendChild(host)
  const spread = new GC.Spread.Sheets.Workbook(host, { sheetCount: 1 })
  try {
    const json = await openExcelToJSON(file)
    spread.fromJSON(json)
    const sheet = spread.getActiveSheet()
    const headerMap = buildHeaderMap(sheet, configDef)
    if (Object.keys(headerMap).length === 0) {
      return { ok: false, rows: [], errors: [{ row: 0, field: '', msg: '未能从 Excel 表头匹配到任何配置字段,请使用系统下载的模板填写' }], warnings: [], stats: { total: 0, matched: 0, skipped: 0 } }
    }
    const result = collectRowsFromSheet(sheet, headerMap, configDef)
    return { ok: result.errors.length === 0, ...result }
  } finally {
    try { spread.dispose && spread.dispose() } catch (e) {}
    if (host.parentNode) host.parentNode.removeChild(host)
  }
}

// ===================== Excel 导出 =====================

function fillSheetWithData(sheet, configDef, rows, mode = 'template') {
  const cols = (configDef && configDef.columns) || []
  const offset = mode === 'template' ? 0 : 2
  // template 模式:行 0 是表头(applyColumnsToSheet 已写入),数据从行 1 开始
  // runtime 模式:行 0 是数据(__id/__mark 在 colHeader),数据从行 0 开始
  const dataStartRow = mode === 'template' ? 1 : 0
  const list = rows || []
  sheet.suspendPaint()
  try {
    sheet.setRowCount(Math.max(list.length + dataStartRow, dataStartRow))
    list.forEach((row, r) => {
      const data = row.data || row.rowData || {}
      for (let i = 0; i < cols.length; i++) {
        const key = cols[i].key
        let value = data[key]
        if (value === undefined || value === null) value = ''
        sheet.setValue(r + dataStartRow, i + offset, value)
      }
    })
  } finally {
    sheet.resumePaint()
  }
}

/** spread 实例 → 导出 .xlsx */
export function exportExcel(spread, fileName, opts = {}) {
  return new Promise((resolve, reject) => {
    const excelIO = new spreadExcel.IO()
    const json = JSON.stringify(spread.toJSON())
    excelIO.save(json, (blob) => {
      const fn = fileName || `export-${Date.now()}.xlsx`
      saveBlob(blob, fn)
      resolve({ ok: true, fileName: fn })
    }, (e) => reject(e))
  })
}

/** rows + configDef → 离线 workbook → 导出 .xlsx */
export async function exportExcelByRows(configDef, rows, fileName, opts = {}) {
  const ctx = createOfflineWorkbook(configDef, 'template')
  try {
    fillSheetWithData(ctx.sheet, configDef, rows, 'template')
    return await exportExcel(ctx.spread, fileName || `${configDef.code || 'export'}-${Date.now()}.xlsx`)
  } finally {
    destroyOfflineWorkbook(ctx)
  }
}

function safeSheetName(name, fallback = 'Sheet') {
  let s = String(name || fallback).replace(/[:\\/?*[\]]/g, '_').slice(0, 31)
  return s || fallback
}

/** 多 configDef → 多 sheet 合并导出。defsAndRows: [{ def, rows }] */
export async function exportExcelMultiSheet(defsAndRows, fileName, opts = {}) {
  const list = defsAndRows || []
  const host = document.createElement('div')
  host.style.cssText = 'position:fixed;left:-99999px;top:0;width:800px;height:600px'
  document.body.appendChild(host)
  const spread = new GC.Spread.Sheets.Workbook(host, { sheetCount: Math.max(list.length, 1) })
  try {
    const usedNames = new Set()
    for (let i = 0; i < list.length; i++) {
      const { def, rows } = list[i]
      let sname = safeSheetName(def.name || def.code || ('Sheet' + (i + 1)))
      let n = 2
      while (usedNames.has(sname)) { sname = safeSheetName(def.name || def.code) + '_' + n; n++ }
      usedNames.add(sname)
      let s = spread.getSheet(i)
      if (!s) { spread.addSheet(i); s = spread.getSheet(i) }
      s.name(sname)
      applyColumnsToSheet(s, def, 'template')
      fillSheetWithData(s, def, rows, 'template')
    }
    return await exportExcel(spread, fileName || `export-${Date.now()}.xlsx`)
  } finally {
    try { spread.dispose && spread.dispose() } catch (e) {}
    if (host.parentNode) host.parentNode.removeChild(host)
  }
}
