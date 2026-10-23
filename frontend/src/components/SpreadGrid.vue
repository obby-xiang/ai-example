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
      <span v-if="engineState === 'loading'">{{ t('sheet.engineLoading') }}</span>
      <span v-else class="text-[#f56c6c]">{{ engineError }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
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
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
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
import type { SheetAdapter, SheetCellUpdate, SheetSelection } from '@/utils/sheet-tools'

const { t } = useI18n()

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
  /**
   * 用户换了选区（B12 收口判据）：只带新选区，不带任何单元格值。
   * `null` = 选区被清空。程序化载入 / 上色期间（`restoring`）不触发。
   */
  (event: 'selection-changed', selection: SheetSelection | null): void
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
 * 后端"前端工具"挂起上限（`app.ai.hitl.frontend-tool-timeout`，默认 120s），保证"前端先失败、
 * 先给出可读错误"，不再退化成"沉默到后端超时才收尾"。
 *
 * 该论证只在取值面成立：本常量与服务端键无逻辑绑定（前端工具执行器的预算是另一条折算链，
 * 见 `utils/frontend-tools.ts#deriveFrontendToolBudgetMs`）。若前端工具键下调（数字规格清点 P-1
 * 的前端折算专项），本值与它的差距会同步收窄，须同批复核此处"明显小于"是否仍成立。
 */
const READY_TIMEOUT_MS = 60_000

/**
 * AI 改动格的屏幕底色（AI 表格工具治理 §5.4）。
 *
 * <p>取卡面同族的浅蓝（`#eff4ff`）：只是"这些格是 AI 改的"的视觉标记，不是状态色
 * （四族状态色留给校验/审批），故不新增颜色语义。清标记时按值精确比对，不会误清用户自设底色。
 */
const AI_CHANGED_BACKCOLOR = '#eff4ff'

/**
 * 预览期待改格的屏幕底色（L-23）：浅琥珀，与已应用态的浅蓝**同形不同色** ——
 * 预览是"还没发生的事"，必须与应用后可区分（同一格两次上色时后者覆盖前者，
 * 不会出现混色；两种底色都不是状态色，不占用校验/审批的四族色）。
 */
const AI_PREVIEW_BACKCOLOR = '#fff4e0'

/** 色调（与 `sheet-tools.ts#SheetMarkTone` 同值；缺省 = applied，既有调用点语义不变）。 */
type MarkTone = 'applied' | 'preview'

/**
 * 已打标记的格（清标记只扫这一份，不扫全表 —— 3 万行应用后 `clearMarks` 若逐格
 * `getCell` 会把主线程卡住；这与写路径"批量 setArray"的取舍同源）。
 */
const markedCells: Array<{ row: number; col: number; tone: MarkTone }> = []

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
    // B12：选区变化是"预览计划可能不再匹配"的唯一信号源（只报坐标，不带值）。
    // 程序化载入 / 上色期间（restoring）不触发 —— 那是系统动作，不是用户换选区。
    sheet.bind(events.SelectionChanged, () => {
      if (restoring) {
        return
      }
      emit('selection-changed', selectionOf(sheet.getSelections()))
    })

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
  // B12：卸载即离开这张表 —— 模块级标记账本一并归零，新实例的 clearMarks 不得去擦旧表坐标
  markedCells.length = 0
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

// R-136 收口修 F2（红队 MAJOR-3）：只读态**场内翻转**（导出完成 ⟹ isReadOnly false→true）时
// 已加载的工作表须即时重保护，否则第 3 步结果表格在只读壳内仍可键盘编辑（内存态，零落库）。
watch(() => props.readOnly, applyReadOnly)

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

/**
 * 视口选区 → 数据面选区（行 = 数据行下标；视口第 0 行是表头）。
 * 适配器的 `selection()` 与 B12 的 `selection-changed` 事件共用这一份换算，不写两遍。
 */
function selectionOf(
  ranges: ReadonlyArray<{ row: number; rowCount: number; col: number; colCount: number }> | null | undefined
): SheetSelection | null {
  const first = ranges && ranges.length > 0 ? ranges[0] : null
  if (!first) {
    return null
  }
  const row = Math.max(0, first.row - 1)
  const rowCount = Math.max(0, first.rowCount - (first.row > 0 ? 0 : 1))
  return { row, col: first.col, rowCount, colCount: first.colCount }
}

/**
 * 表格适配器（AI 表格操作专项 §3.6 的窄接口）：只暴露纯数据面，不把 SpreadJS 类型漏进 store。
 *
 * 行口径：适配器行 = 数据行下标（0 基）⇒ 视口行 = row + 1（视口第 0 行是表头）。
 * 批量写走 `setArray`（逐格 `setValue` 在数千行上会卡主线程），且**只发一次 `data-changed`**
 * （否则一个 N 格操作会打 N 次事件）。
 */
function getSheetAdapter(): SheetAdapter {
  const sheet = activeSheet()
  if (!sheet) {
    throw new Error('表格引擎尚未就绪，无法提供表格适配器')
  }
  const rowCount = (): number => Math.max(0, sheet.getRowCount() - 1)
  const colCount = (): number => (fields.length > 0 ? fields.length : sheet.getColumnCount())
  return {
    rowCount,
    colCount,
    header: (col) => String(sheet.getValue(0, col) ?? ''),
    getCell: (row, col) => sheet.getValue(row + 1, col),
    getArray: (row, col, rows, cols) => sheet.getArray(row + 1, col, rows, cols) as unknown[][],
    writeCells: (updates) => {
      const list = updates.filter((update) => update.row >= 0 && update.row < rowCount() && update.col >= 0 && update.col < colCount())
      if (list.length === 0) {
        return
      }
      restoring = true
      sheet.suspendPaint()
      try {
        const byColumn = new Map<number, SheetCellUpdate[]>()
        for (const update of list) {
          const bucket = byColumn.get(update.col) ?? []
          bucket.push(update)
          byColumn.set(update.col, bucket)
        }
        for (const [col, bucket] of byColumn) {
          bucket.sort((left, right) => left.row - right.row)
          let runStart = 0
          for (let index = 1; index <= bucket.length; index += 1) {
            const prev = bucket[index - 1]
            const next = bucket[index]
            if (next && prev && next.row === prev.row + 1) {
              continue
            }
            const run = bucket.slice(runStart, index)
            const first = run[0]
            if (first) {
              sheet.setArray(first.row + 1, col, run.map((item) => [item.value]))
            }
            runStart = index
          }
        }
      } finally {
        sheet.resumePaint()
        restoring = false
      }
      emit('data-changed')
    },
    selection: (): SheetSelection | null => selectionOf(sheet.getSelections()),
    // 屏幕态标记（AI 表格工具治理 §5.4）：只设底色，不碰 cell style 的持久结构、
    // 不调 conditionalFormats —— 后者会进 toWorkbookJson()，而导出链只读值（collectRows）。
    markCells: (cells, tone) => {
      if (cells.length === 0) {
        return
      }
      const resolvedTone: MarkTone = tone === 'preview' ? 'preview' : 'applied'
      const color = resolvedTone === 'preview' ? AI_PREVIEW_BACKCOLOR : AI_CHANGED_BACKCOLOR
      restoring = true
      sheet.suspendPaint()
      try {
        for (const cell of cells) {
          if (cell.row < 0 || cell.row >= rowCount() || cell.col < 0 || cell.col >= colCount()) {
            continue
          }
          sheet.getCell(cell.row + 1, cell.col).backColor(color)
          markedCells.push({ row: cell.row, col: cell.col, tone: resolvedTone })
        }
      } finally {
        sheet.resumePaint()
        restoring = false
      }
    },
    clearMarks: (tone) => {
      if (markedCells.length === 0) {
        return
      }
      const colors = tone === 'preview'
        ? [AI_PREVIEW_BACKCOLOR]
        : tone === 'applied' ? [AI_CHANGED_BACKCOLOR] : [AI_CHANGED_BACKCOLOR, AI_PREVIEW_BACKCOLOR]
      restoring = true
      sheet.suspendPaint()
      try {
        let kept = 0
        for (const cell of markedCells) {
          if (tone !== undefined && cell.tone !== tone) {
            // 只清指定色调：预览色不能顺手把已应用色一起抹掉
            markedCells[kept++] = cell
            continue
          }
          const target = sheet.getCell(cell.row + 1, cell.col)
          // 按值精确比对：只清自己打的那层底色，不动用户自设的颜色
          if (colors.includes(target.backColor())) {
            target.backColor(undefined)
          }
        }
        markedCells.length = kept
      } finally {
        sheet.resumePaint()
        restoring = false
      }
    }
  }
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
  validateLocal,
  getSheetAdapter
})
</script>
