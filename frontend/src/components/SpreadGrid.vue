<template>
  <div class="relative w-full">
    <div
      ref="host"
      class="w-full border border-solid border-[#e4e7ed] rounded overflow-hidden"
      :style="{ height }"
    />
    <div
      v-if="engineState !== 'ready'"
      class="absolute inset-0 flex items-center justify-center bg-white/85 text-[13px] text-[#909399]"
    >
      <span v-if="engineState === 'loading'">表格引擎加载中…（SpreadJS 按需加载）</span>
      <span v-else class="text-[#f56c6c]">{{ engineError }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * SpreadJS 表格宿主（向导在线编辑基座）。
 *
 * 懒加载（裁决⑥）：SpreadJS 运行时**不**在模块顶层 import，而是经 `utils/spreadjs`
 * 动态加载 —— 路由 chunk 与首屏 chunk 都不背这 4.7MB。首次真正需要渲染表格时才拉取，
 * 期间显示"表格引擎加载中"。
 *
 * 职责边界（与后端 ExcelReader 对齐）：
 * - 视口第 0 行写表头、数据从第 1 行开始（`utils/excel.ts` 口径）——ExcelIO **不**导出
 *   colHeader 区域，后端又按表头认列，故表头必须是真实单元格（此处与 kimi 蓝本把表头
 *   写进列头区的做法不同，见证据文档 V6 差异表：功能差异，理由=后端 ExcelIO/ExcelReader 往返）；
 * - 动态校验器（ENUM 下拉/数值/日期/布尔）由 `applyColumnsToSheet` 挂到整列；
 * - 上传的 xlsx 用 ExcelIO 打开后按表头映射回字段行，读回用 `readRows`。
 */
import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import type GC from '@grapecity/spread-sheets'
import {
  applyColumnsToSheet,
  blobToWorkbookJson,
  buildRowsXlsxBlob,
  findDataSheet,
  readRows,
  setRows,
  validateRows,
  type ExcelRowIssue,
  type SpreadWorksheet
} from '@/utils/excel'
import { loadSpreadCore, type SpreadSheets } from '@/utils/spreadjs'
import type { ConfigField } from '@/types/definition'
import { parseRowData } from '@/types/data'

const props = withDefaults(
  defineProps<{
    height?: string
    readOnly?: boolean
    /** 所属配置编码（写回 xlsx 的 `_meta` 用） */
    defCode?: string
    /** 工作表名（后端按定义名优先） */
    sheetName?: string | null
  }>(),
  {
    height: '420px',
    readOnly: false,
    defCode: '',
    sheetName: null
  }
)

const emit = defineEmits<{
  /** 用户改动了单元格（AI 侧据此判断工作区状态变化） */
  (event: 'data-changed'): void
  /** 引擎就绪（含从 xlsx/JSON 载入后的就绪） */
  (event: 'ready'): void
}>()

const host = ref<HTMLDivElement | null>(null)
const engineState = ref<'loading' | 'ready' | 'failed'>('loading')
const engineError = ref('')

const workbook = shallowRef<GC.Spread.Sheets.Workbook | null>(null)
let spreadNs: SpreadSheets | null = null
let fields: readonly ConfigField[] = []
let restoring = false
let resizeObserver: ResizeObserver | null = null
let resizeTimer: ReturnType<typeof setTimeout> | null = null
/** 引擎就绪前到来的载入请求（el-tabs 懒渲染时很常见） */
let pendingLoad: { fields: readonly ConfigField[]; rows: readonly Record<string, unknown>[] } | null = null
let readyResolve: (() => void) | null = null
const readyPromise = new Promise<void>((resolve) => {
  readyResolve = resolve
})

onMounted(async () => {
  try {
    const gs: SpreadSheets = await loadSpreadCore()
    if (!host.value) {
      return
    }
    const instance = new gs.Spread.Sheets.Workbook(host.value)
    const sheet = instance.getActiveSheet()
    const events = gs.Spread.Sheets.Events
    const onChanged = (): void => {
      if (!restoring) {
        emit('data-changed')
      }
    }
    sheet.bind(events.CellChanged, onChanged)
    sheet.bind(events.RangeChanged, onChanged)
    sheet.bind(events.RowChanged, onChanged)

    workbook.value = instance
    spreadNs = gs
    applyReadOnly()
    engineState.value = 'ready'
    if (pendingLoad) {
      const request = pendingLoad
      pendingLoad = null
      applyLoad(request.fields, request.rows)
    }
    readyResolve?.()
    readyResolve = null
    emit('ready')

    // 宿主尺寸从 0 变为有效值（el-tabs 懒渲染）时需要一次 refresh
    resizeObserver = new ResizeObserver(() => {
      if (resizeTimer !== null) {
        clearTimeout(resizeTimer)
      }
      resizeTimer = setTimeout(() => {
        workbook.value?.refresh()
      }, 50)
    })
    resizeObserver.observe(host.value)
  } catch (error) {
    engineState.value = 'failed'
    engineError.value = `表格引擎加载失败：${error instanceof Error ? error.message : String(error)}`
  }
})

onBeforeUnmount(() => {
  if (resizeTimer !== null) {
    clearTimeout(resizeTimer)
  }
  resizeObserver?.disconnect()
  resizeObserver = null
  try {
    workbook.value?.destroy()
  } catch {
    // 销毁失败无需上抛（宿主即将移除）
  }
  workbook.value = null
})

function activeSheet(): SpreadWorksheet | null {
  return workbook.value ? workbook.value.getActiveSheet() : null
}

function applyReadOnly(): void {
  const sheet = activeSheet()
  if (sheet) {
    sheet.options.isProtected = props.readOnly
  }
}

/** 建列（表头 + 校验器 + 冻结）并写数据行（引擎已就绪时调用）。 */
function applyLoad(fieldDefs: readonly ConfigField[], rows: readonly Record<string, unknown>[]): void {
  const sheet = activeSheet()
  if (!sheet || !spreadNs) {
    return
  }
  fields = fieldDefs
  restoring = true
  sheet.suspendPaint()
  try {
    const gs = spreadNs
    sheet.clear(
      0,
      0,
      sheet.getRowCount(),
      sheet.getColumnCount(),
      gs.Spread.Sheets.SheetArea.viewport,
      gs.Spread.Sheets.StorageType.data
    )
    applyColumnsToSheet(gs, sheet, fieldDefs)
    setRows(sheet, fieldDefs, rows)
  } finally {
    sheet.resumePaint()
    restoring = false
  }
  applyReadOnly()
}

/** 建列 + 填数据（引擎未就绪时自动挂起，就绪后补做）。 */
function loadData(fieldDefs: readonly ConfigField[], rows: readonly Record<string, unknown>[] = []): void {
  fields = fieldDefs
  if (!workbook.value || !spreadNs) {
    pendingLoad = { fields: fieldDefs, rows }
    return
  }
  applyLoad(fieldDefs, rows)
}

/** 从工作簿 JSON 载入（模板 JSON / 已保存的编辑结果）。 */
async function loadFromJson(json: string, fieldDefs: readonly ConfigField[]): Promise<void> {
  await readyPromise
  const instance = workbook.value
  if (!instance || !spreadNs) {
    throw new Error('表格引擎尚未就绪')
  }
  restoring = true
  try {
    instance.fromJSON(json)
    const sheet = findDataSheet(instance) ?? instance.getActiveSheet()
    fields = fieldDefs
    if (fieldDefs.length > 0) {
      applyColumnsToSheet(spreadNs, sheet, fieldDefs)
    }
  } finally {
    restoring = false
  }
  applyReadOnly()
}

/**
 * 从 xlsx 字节载入（上传的文件 / 服务端已存的文件）。
 *
 * 返回缺失表头，供页面提示"文件缺少字段列"（不静默丢列）。
 */
async function loadFromXlsx(blob: Blob, fieldDefs: readonly ConfigField[]): Promise<{ missingHeaders: string[] }> {
  const json = await blobToWorkbookJson(blob)
  await loadFromJson(json, fieldDefs)
  const instance = workbook.value
  if (!instance || fieldDefs.length === 0) {
    return { missingHeaders: fieldDefs.map((field) => field.label) }
  }
  const sheet = findDataSheet(instance) ?? instance.getActiveSheet()
  return { missingHeaders: readRows(sheet, fieldDefs).missingHeaders }
}

/** 读回非空行（字段编码 → 字符串值），全空行自动跳过。 */
function collectRows(): Array<Record<string, string>> {
  const sheet = activeSheet()
  if (!sheet || fields.length === 0) {
    return []
  }
  return readRows(sheet, fields).rows
}

/** 工作簿 JSON（回写后端前的中间形态）。 */
function toWorkbookJson(): string | null {
  return workbook.value ? JSON.stringify(workbook.value.toJSON()) : null
}

/**
 * xlsx 字节（PUT /tasks/{id}/files/{defCode} 的载荷）。
 *
 * 用 `buildRowsXlsxBlob` 自产 OOXML：ExcelIO 保存会插入评估版水印表，
 * 后端会把它当数据表读（见 utils/excel.ts 的说明与证据文档 V3）。
 */
async function toXlsxBlob(): Promise<Blob> {
  if (!spreadNs) {
    throw new Error('表格尚未就绪，无法导出文件')
  }
  return await buildRowsXlsxBlob(fields, collectRows(), { defCode: props.defCode, sheetName: props.sheetName })
}

/** 本地动态校验（必填/类型/枚举/主键重复），与后端预检查同口径但不出网。 */
function validateLocal(): ExcelRowIssue[] {
  return validateRows(fields, collectRows())
}

/** 从已发布数据行装配表格（只读浏览用）。 */
function loadFromRows(fieldDefs: readonly ConfigField[], rows: readonly { dataJson: string }[]): void {
  loadData(
    fieldDefs,
    rows.map((row) => parseRowData(row))
  )
}

defineExpose({
  loadData,
  loadFromJson,
  loadFromXlsx,
  loadFromRows,
  collectRows,
  toWorkbookJson,
  toXlsxBlob,
  validateLocal
})
</script>
