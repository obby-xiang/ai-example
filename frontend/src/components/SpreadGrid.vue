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
 *
 * 就绪等待契约（B-07）：`readyPromise` 在**成功 / 加载失败 / 超时 / 卸载**四条路径上都结算，
 * 调用方不会无限悬挂；失败时 `loadFromJson` 抛出可展示的错误文本（含引擎失败原文）。
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

/**
 * 等待引擎就绪的兜底上限（B-07 双侧兜底）。
 *
 * 论证（对齐既有常量，非拍脑袋）：这里的等待实质是"同一条网络栈上拉一个 ~4.7MB 的
 * spreadjs chunk（`vite.config.ts` 懒加载口径）+ 模块求值"，与一次 REST 请求同族，故取
 * `api/http.ts` 的 axios 单请求上限同值（60s）；超过它仍未就绪即判卡死。同时 60s 仍明显小于
 * 后端"前端工具"挂起上限（`app.ai.hitl.frontend-tool-timeout`，默认 120s；裁决③拆键后与
 * 确认门键 `app.ai.hitl.confirm-timeout` 240s 分场景），保证"前端先失败、先给出可读错误"，
 * 不再退化成"沉默到后端超时才收尾"。
 *
 * 该论证只在取值面成立：本常量与服务端键无逻辑绑定（前端工具执行器的预算是另一条折算链，
 * 见 `utils/frontend-tools.ts#deriveFrontendToolBudgetMs`）。若前端工具键下调（数字规格清点 P-1
 * 的前端折算专项），本值与它的差距会同步收窄，须同批复核此处"明显小于"是否仍成立。
 */
const READY_TIMEOUT_MS = 60_000

let readyResolve: (() => void) | null = null
let readyTimer: ReturnType<typeof setTimeout> | null = null

/** 唤醒所有 `await readyPromise` 的等待者（B-07：成功/失败/超时/卸载四条路径都必须走这里）。 */
function settleReady(): void {
  if (readyTimer !== null) {
    clearTimeout(readyTimer)
    readyTimer = null
  }
  readyResolve?.()
  readyResolve = null
}

/**
 * 失败收尾：置可见错误 + 唤醒等待者。
 *
 * 注意 `readyPromise` 只承担"等待结束"的**唤醒**语义，不代表引擎就绪 —— 就绪与否由调用时的
 * `workbook.value` / `spreadNs` 实况判定（见 {@link waitForReady}）。故失败唤醒后若引擎又补上了
 * （`loadSpreadCore` 不缓存失败，可重试），后续调用仍能走通。
 */
function failReady(message: string): void {
  engineState.value = 'failed'
  engineError.value = message
  settleReady()
}

const readyPromise = new Promise<void>((resolve) => {
  readyResolve = resolve
})

// 看门狗与 promise 同点武装：chunk 拉取没有原生超时，挂起时只能由这里唤醒等待者
readyTimer = setTimeout(() => {
  readyTimer = null
  failReady(`表格引擎加载超时（超过 ${READY_TIMEOUT_MS / 1000} 秒未就绪），请检查网络后重试`)
}, READY_TIMEOUT_MS)

onMounted(async () => {
  try {
    const gs: SpreadSheets = await loadSpreadCore()
    if (!host.value) {
      // 宿主已卸载（与 onBeforeUnmount 的竞态，红队 RR1 场景）：实例无从创建，必须显式结算，
      // 否则 `await readyPromise` 的调用方永久悬挂 —— 整条回灌链（loadTask → 工具执行器）不 settle
      failReady('表格引擎未挂载（组件已卸载），请重新打开在线编辑')
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
    settleReady()
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
    // 引擎不可用也必须唤醒等待者（B-07）：否则调用方永久悬挂，回灌链不 settle
    failReady(`表格引擎加载失败：${error instanceof Error ? error.message : String(error)}`)
  }
})

onBeforeUnmount(() => {
  // 卸载竞态兜底（B-07）：此刻起宿主必然消失，再等引擎已无意义 —— 立即结算，
  // 让在途的 `await readyPromise` 在 `waitForReady` 处抛出可读错误（调用方已 try/catch）
  if (engineState.value === 'loading') {
    failReady('表格引擎未就绪（组件已卸载），请重新打开在线编辑')
  } else {
    settleReady()
  }
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

/**
 * 等引擎可用（B-07）：`readyPromise` 已在成功/失败/超时/卸载四条路径上结算，
 * 这里再按**活体状态**判定一次 —— 失败/超时唤醒时把引擎错误原文抛给调用方，
 * 调用方（`ensureGridLoaded` / `loadEditors`）既有 `try/catch` 会展示并允许重试。
 */
async function waitForReady(): Promise<void> {
  await readyPromise
  if (!workbook.value || !spreadNs) {
    throw new Error(engineError.value || '表格引擎尚未就绪')
  }
}

/** 从工作簿 JSON 载入（模板 JSON / 已保存的编辑结果）。 */
async function loadFromJson(json: string, fieldDefs: readonly ConfigField[]): Promise<void> {
  await waitForReady()
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
