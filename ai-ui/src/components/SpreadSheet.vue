<template>
  <div class="spread-host" ref="hostRef" :style="{ height }"></div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import GC from '@grapecity/spread-sheets'
import { ElMessage } from 'element-plus'
import { applyColumnsToSheet } from '@/utils/excel-io'

const props = defineProps({
  configDefinition: { type: Object, required: true },
  initialRows: { type: Array, default: () => [] },
  mode: { type: String, default: 'edit' }, // read/edit/modify
  // 为 MODIFY 场景追踪变化用:若传入true,表格内部维护diff
  trackChanges: { type: Boolean, default: false },
  // 表格高度(导出页预览等场景需要指定)
  height: { type: String, default: '400px' }
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
    applyColumnsToSheet(sheet, props.configDefinition, 'runtime')
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

// mode 切换时重设保护状态(避免 :key 强制重挂的开销,导出页只读↔编辑切换用)
watch(() => props.mode, (m) => {
  if (!sheet) return
  if (m === 'read') {
    sheet.options.isProtected = true
    sheet.options.protectionOption = {
      allowInsertRows: false,
      allowDeleteRows: false,
      allowEditObjects: false
    }
  } else {
    sheet.options.isProtected = false
  }
})

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
function applyRules() { /* 已在applyColumnsToSheet里使用原生 DataValidator */ }

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
 * 从服务端重新拉取并刷新数据(ai-ui 后端 Agent Loop 模式:数据走 configApi.all → /api/configs/data/all)
 */
async function refreshFromServer() {
  if (!props.configDefinition?.id) return
  const { configApi } = await import('@/api/config')
  const rows = await configApi.all(props.configDefinition.id, 100000)
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
