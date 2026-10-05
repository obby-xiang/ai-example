<template>
  <!-- SpreadJS 宿主：高度是组件 prop（每实例不同），保留内联 style 绑定；其余样式用 Tailwind -->
  <div ref="hostRef" class="w-full border border-solid border-[#e4e7ed] rounded overflow-hidden" :style="{ height }"></div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import GC from '@grapecity/spread-sheets'
import dayjs from 'dayjs'
import { applyFieldsToSheet } from '@/utils/excel-io'

const props = defineProps({
  height: { type: String, default: '420px' }
})
const emit = defineEmits(['data-changed'])

const hostRef = ref(null)
let spread = null
let sheet = null
let fields = []
let restoring = false
let resizeObserver = null

onMounted(() => {
  spread = new GC.Spread.Sheets.Workbook(hostRef.value)
  sheet = spread.getActiveSheet()
  sheet.bind(GC.Spread.Sheets.Events.CellChanged, onChanged)
  sheet.bind(GC.Spread.Sheets.Events.RowChanged, onChanged)
  sheet.bind(GC.Spread.Sheets.Events.RangeChanged, onChanged)
  // 宿主可能初始不可见（el-tabs 懒渲染等），尺寸从 0 变为有效值时刷新布局
  let resizeTimer = null
  resizeObserver = new ResizeObserver(() => {
    clearTimeout(resizeTimer)
    resizeTimer = setTimeout(() => { spread && spread.refresh() }, 50)
  })
  resizeObserver.observe(hostRef.value)
})

onBeforeUnmount(() => {
  if (resizeObserver) {
    resizeObserver.disconnect()
    resizeObserver = null
  }
  if (spread) {
    try { spread.dispose && spread.dispose() } catch (e) { /* ignore */ }
    spread = null
    sheet = null
  }
})

function onChanged() {
  if (restoring) return
  emit('data-changed')
}

/**
 * 建列 + 填充数据。
 * @param fieldDefs [{name, label, type, required, options?, maxLength?, ref?}]
 * @param rows      [{字段名: 值}]
 */
function loadData(fieldDefs, rows) {
  if (!sheet) return
  restoring = true
  sheet.suspendPaint()
  try {
    fields = fieldDefs || []
    sheet.name('数据')
    const AREA = GC.Spread.Sheets.SheetArea
    sheet.clear(0, 0, sheet.getRowCount(), sheet.getColumnCount(), AREA.viewport, GC.Spread.Sheets.StorageType.data)
    applyFieldsToSheet(sheet, fields, 'runtime')
    const list = rows || []
    sheet.setRowCount(Math.max(list.length + 50, 100))
    list.forEach((row, r) => {
      const data = row.data || row || {}
      for (let i = 0; i < fields.length; i++) {
        let v = data[fields[i].name]
        if (v === undefined || v === null) v = ''
        sheet.setValue(r, i, v)
      }
    })
  } finally {
    sheet.resumePaint()
    restoring = false
  }
}

function normalizeCellValue(v, field) {
  if (v === null || v === undefined || String(v) === '') return null
  const type = field.type || 'STRING'
  if (type === 'DATE') {
    if (v instanceof Date) return dayjs(v).format('YYYY-MM-DD')
    const d = dayjs(v)
    return d.isValid() ? d.format('YYYY-MM-DD') : v
  }
  if (type === 'BOOLEAN') return !!v
  if (type === 'NUMBER') {
    const n = Number(v)
    return Number.isNaN(n) ? v : n
  }
  return v
}

/** 收集全部非空行 → [{字段名: 值}]，跳过全空行 */
function collectRows() {
  if (!sheet) return []
  const out = []
  for (let r = 0; r < sheet.getRowCount(); r++) {
    let hasData = false
    const data = {}
    for (let i = 0; i < fields.length; i++) {
      const v = sheet.getValue(r, i)
      if (v !== null && v !== undefined && String(v) !== '') {
        hasData = true
        data[fields[i].name] = normalizeCellValue(v, fields[i])
      }
    }
    if (hasData) out.push(data)
  }
  return out
}

function setReadOnly(readOnly) {
  if (!sheet) return
  sheet.options.isProtected = !!readOnly
  if (readOnly) {
    sheet.options.protectionOption = {
      allowInsertRows: false,
      allowDeleteRows: false,
      allowEditObjects: false
    }
  }
}

defineExpose({ loadData, collectRows, setReadOnly, getSpread: () => spread })
</script>
