<template>
  <div ref="hostRef" class="spread-grid" :class="{ 'spread-grid--readonly': readonly }"></div>
</template>

<script setup>
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import GC from '@grapecity/spread-sheets'
import { applyColumnsToSheet, setRows } from '@/utils/excel'

/**
 * SpreadJS 表格组件：动态字段渲染 + 可编辑 + 数据校验。
 */
const props = defineProps({
  fields: { type: Array, required: true },
  rows: { type: Array, default: () => [] },
  readonly: { type: Boolean, default: false },
  sheetName: { type: String, default: 'Sheet1' },
  height: { type: String, default: '100%' }
})
const emit = defineEmits(['changed'])

const hostRef = ref(null)
let spread = null

onMounted(() => {
  if (!hostRef.value) return
  spread = new GC.Spread.Sheets.Workbook(hostRef.value)
  applyProps()
})

watch(() => [props.fields, props.rows, props.readonly, props.sheetName], () => {
  applyProps()
}, { deep: false })

function applyProps() {
  if (!spread || !props.fields?.length) return
  const sheet = spread.getActiveSheet()
  if (!sheet) return
  sheet.suspendPaint()
  try {
    sheet.name(props.sheetName || 'Sheet1')
    applyColumnsToSheet(sheet, props.fields)
    setRows(sheet, props.fields, props.rows || [])
    sheet.options.isProtected = !!props.readonly
    if (props.readonly) {
      sheet.options.protectionOption = {
        allowInsertRows: false,
        allowDeleteRows: false,
        allowEditObjects: false,
        allowSelectLockedCells: true,
        allowSelectUnlockedCells: true
      }
    }
  } finally {
    sheet.resumePaint()
  }
}

function markChanged() {
  emit('changed')
}

onBeforeUnmount(() => {
  if (spread) {
    try {
      const sheet = spread.getActiveSheet()
      if (sheet) {
        sheet.unbind(GC.Spread.Sheets.Events.CellChanged, markChanged)
        sheet.unbind(GC.Spread.Sheets.Events.RowChanged, markChanged)
        sheet.unbind(GC.Spread.Sheets.Events.RangeChanged, markChanged)
      }
    } catch { /* ignore */ }
    spread.dispose()
    spread = null
  }
})

defineExpose({
  getSpread: () => spread
})
</script>

<style scoped>
.spread-grid {
  width: 100%;
  height: v-bind('props.height');
  min-height: 240px;
}
</style>
