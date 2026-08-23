<template>
  <div class="spread-host" ref="hostRef"></div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import GC from '@grapecity/spread-sheets'
import { ElMessage } from 'element-plus'
import { useConfigStore } from '@/stores/config'

const props = defineProps({
  configDefinition: { type: Object, required: true },
  initialRows: { type: Array, default: () => [] },
  mode: { type: String, default: 'edit' }, // read/edit/modify
  // 为 MODIFY 场景追踪变化用:若传入true,表格内部维护diff
  trackChanges: { type: Boolean, default: false }
})

const emit = defineEmits(['data-changed', 'changes-collected'])

const hostRef = ref(null)
let spread = null
let sheet = null

// 列顺序
const columns = () => props.configDefinition?.columns || []

// 公开给AI面板的代理句柄
function registerProxy() {
  if (!window.__spreadsheets) window.__spreadsheets = {}
  window.__spreadsheets[props.configDefinition.id] = {
    defId: props.configDefinition.id,
    refreshFromServer,
    getChanges: collectChanges,
    collectRows
  }
}
function unregisterProxy() {
  if (window.__spreadsheets && window.__spreadsheets[props.configDefinition.id]) {
    delete window.__spreadsheets[props.configDefinition.id]
  }
}

onMounted(async () => {
  await nextTick()
  initSpread()
  registerProxy()
})

onBeforeUnmount(() => {
  unregisterProxy()
  if (spread) {
    try { spread.dispose && spread.dispose() } catch (e) {}
    spread = null
    sheet = null
  }
})

watch(() => props.configDefinition?.id, (n, o) => {
  if (n && n !== o) {
    setTimeout(() => {
      unregisterProxy()
      if (spread) { try { spread.dispose && spread.dispose() } catch (e) {} }
      initSpread()
      registerProxy()
    }, 50)
  }
})

function initSpread() {
  if (!hostRef.value) return
  spread = new GC.Spread.Sheets.Workbook(hostRef.value)
  spread.options.allowUserEditFormula = true
  spread.options.copyPasteHeaderOptions = GC.Spread.Sheets.CopyPasteHeaderOptions.allHeaders
  sheet = spread.getActiveSheet()
  sheet.suspendPaint()
  try {
    sheet.name(props.configDefinition?.name || 'Sheet1')
    applyColumns()
    setRows(props.initialRows || [])
    applyRules()
    if (props.mode === 'read') {
      sheet.options.isProtected = true
      sheet.options.protectionOption = {
        allowInsertRows: false,
        allowDeleteRows: false,
        allowEditObjects: false
      }
    } else {
      sheet.options.isProtected = false
    }
    // 绑定变更事件
    const changedHandler = () => {
      emit('data-changed', collectRows())
      if (props.trackChanges) {
        emit('changes-collected', collectChanges())
      }
    }
    sheet.bind(GC.Spread.Sheets.Events.CellChanged, changedHandler)
    sheet.bind(GC.Spread.Sheets.Events.RowChanged, changedHandler)
    sheet.bind(GC.Spread.Sheets.Events.RangeChanged, changedHandler)
  } finally {
    sheet.resumePaint()
  }
}

function applyColumns() {
  const cols = columns()
  // 列 0: 序号(隐藏在行头),我们加一列 行ID(隐藏列)和 状态列
  sheet.setColumnCount(cols.length + 2, GC.Spread.Sheets.SheetArea.viewport)
  // Col 0: id (hidden)
  sheet.setValue(0, 0, '__id', GC.Spread.Sheets.SheetArea.colHeader)
  sheet.setColumnVisible(0, false)
  sheet.setTag(0, 0, '__id', GC.Spread.Sheets.SheetArea.colHeader)
  // Col 1: 状态(added/modified/deleted/empty)
  sheet.setValue(0, 1, '__mark', GC.Spread.Sheets.SheetArea.colHeader)
  sheet.setColumnVisible(1, false)
  sheet.setTag(0, 1, '__mark', GC.Spread.Sheets.SheetArea.colHeader)

  for (let i = 0; i < cols.length; i++) {
    const c = cols[i]
    const colIdx = i + 2
    sheet.setValue(0, colIdx, c.label || c.key, GC.Spread.Sheets.SheetArea.colHeader)
    sheet.setTag(0, colIdx, c.key, GC.Spread.Sheets.SheetArea.colHeader)
    sheet.setColumnWidth(colIdx, Math.max(100, 14 * (c.label || c.key || '').length + 40))

    // 类型设置
    const type = c.type || 'string'
    if (type === 'select' && Array.isArray(c.options) && c.options.length) {
      const dv = GC.Spread.Sheets.DataValidation.createListValidator(c.options.join(','))
      dv.showInputMessage(true)
      dv.inputMessage(`请从下拉中选择：${c.options.join('、')}`)
      dv.showErrorMessage(true)
      dv.errorMessage('选项不在允许的列表中')
      sheet.setDataValidator(-1, colIdx, dv)
      sheet.setCellType(-1, colIdx, new GC.Spread.Sheets.CellTypes.ComboBox().items(
        c.options.map(o => ({ text: String(o), value: String(o) }))
      ))
    } else if (type === 'number') {
      const dv = GC.Spread.Sheets.DataValidation.createNumberValidator(
        GC.Spread.Sheets.ConditionalFormatting.ComparisonOperators.greaterThanOrEqualsTo,
        -1e18, 1e18
      )
      dv.showErrorMessage(true)
      dv.errorMessage('请输入数字')
      sheet.setDataValidator(-1, colIdx, dv)
      sheet.setFormatter(-1, colIdx, '0.####')
    } else if (type === 'date') {
      sheet.setFormatter(-1, colIdx, 'yyyy-mm-dd')
    } else if (type === 'boolean') {
      const cellType = new GC.Spread.Sheets.CellTypes.CheckBox().textTrue('启用').textFalse('停用')
      sheet.setCellType(-1, colIdx, cellType)
    }

    if (c.required) {
      const dv = GC.Spread.Sheets.DataValidation.createFormulaValidator(
        `NOT(ISBLANK(INDIRECT(ADDRESS(ROW(),COLUMN()))))`
      )
      dv.showErrorMessage(true)
      dv.errorMessage(`${c.label || c.key}为必填项`)
      sheet.setDataValidator(-1, colIdx, dv)
      // 给列头加红点提示
      const oldVal = c.label || c.key
      sheet.getCell(0, colIdx, GC.Spread.Sheets.SheetArea.colHeader)
        .value('* ' + oldVal)
        .foreColor('#f56c6c')
        .font('bold 12px PingFang SC')
    }
  }
  sheet.frozenRowCount(1)
  sheet.rowFilter = new GC.Spread.Sheets.Filter.HideRowFilter(
    new GC.Spread.Sheets.Range(-1, 2, -1, cols.length)
  )
  sheet.setRowHeight(0, 28)
}

function setRows(rows) {
  const cols = columns()
  sheet.setRowCount(Math.max(rows.length + 50, 200))
  rows.forEach((row, r) => {
    const id = row.id || null
    const data = row.rowData || row.data || {}
    sheet.setValue(r, 0, id)
    sheet.setValue(r, 1, 'unchanged')
    for (let i = 0; i < cols.length; i++) {
      const key = cols[i].key
      let value = data[key]
      if (value === undefined || value === null) value = ''
      sheet.setValue(r, i + 2, value)
    }
  })
  // 留空行为新增
  for (let r = rows.length; r < sheet.getRowCount(); r++) {
    sheet.setValue(r, 1, '')
  }
}

/**
 * 将表格当前所有非空行 + 新增/修改/删除标记收集为后端batchSave格式
 */
function collectRows() {
  const cols = columns()
  const rowCount = sheet.getRowCount()
  const out = []
  for (let r = 0; r < rowCount; r++) {
    const id = sheet.getValue(r, 0)
    const mark = sheet.getValue(r, 1) || ''
    // 检查是否整行空
    let hasData = false
    const data = {}
    for (let i = 0; i < cols.length; i++) {
      const key = cols[i].key
      const v = sheet.getValue(r, i + 2)
      if (v !== null && v !== undefined && String(v) !== '') {
        hasData = true
        data[key] = (cols[i].type === 'boolean') ? !!v : v
      } else {
        data[key] = cols[i].defaultValue ?? null
      }
    }
    if (mark === 'deleted') {
      if (id) out.push({ id, data: {}, mark: 'deleted' })
      continue
    }
    if (!hasData && !id) continue
    if (id) {
      out.push({ id, data, mark: mark || 'unchanged' })
    } else if (hasData) {
      sheet.setValue(r, 1, 'added')
      out.push({ id: null, data, mark: 'added' })
    }
  }
  return out
}

/**
 * 收集行级变更清单(added/modified/deleted) - MODIFY场景用
 */
function collectChanges() {
  const list = collectRows()
  const added = list.filter(r => r.mark === 'added')
  const modified = list.filter(r => r.mark === 'modified')
  const deleted = list.filter(r => r.mark === 'deleted')
  return { summary: { added: added.length, modified: modified.length, deleted: deleted.length }, detail: list }
}

/**
 * 应用校验规则(非空/类型/枚举) -> 返回不合法的行
 */
function applyRules() { /* 已在applyColumns里使用原生 DataValidator */ }

/**
 * 执行数据校验(使用SpreadJS原生校验)
 * 返回: { valid: boolean, errors: [{row, col, msg}] }
 */
function validateAll() {
  const errors = []
  const cols = columns()
  const rowCount = sheet.getRowCount()
  for (let r = 0; r < rowCount; r++) {
    // 跳过整行空且无ID
    const id = sheet.getValue(r, 0)
    const mark = sheet.getValue(r, 1)
    if (mark === 'deleted') continue
    let hasData = false
    for (let i = 0; i < cols.length; i++) {
      const v = sheet.getValue(r, i + 2)
      if (v !== null && v !== undefined && String(v) !== '') { hasData = true; break }
    }
    if (!hasData && !id) continue

    for (let i = 0; i < cols.length; i++) {
      const c = cols[i]
      const dv = sheet.getDataValidator(r, i + 2) || sheet.getDataValidator(-1, i + 2)
      if (dv) {
        const value = sheet.getValue(r, i + 2)
        if (!dv.validate(value, sheet, r, i + 2)) {
          errors.push({
            row: r + 1,
            field: c.label || c.key,
            msg: dv.errorMessage() || '校验失败'
          })
        }
      }
    }
  }
  return { valid: errors.length === 0, errors }
}

/**
 * 从本地数据源重新拉取并刷新数据(前端 AI runtime 模式:数据在 configStore 内存中)
 */
async function refreshFromServer() {
  if (!props.configDefinition?.id) return
  const configStore = useConfigStore()
  const rows = await configStore.allData(props.configDefinition.id, 100000)
  sheet.suspendPaint()
  try {
    sheet.clearRows(0, sheet.getRowCount())
    setRows(rows || [])
  } finally {
    sheet.resumePaint()
  }
  emit('data-changed', collectRows())
  ElMessage.success('已刷新表格数据')
}

defineExpose({
  collectRows,
  collectChanges,
  validateAll,
  refreshFromServer,
  getSheet: () => sheet,
  getSpread: () => spread
})
</script>
