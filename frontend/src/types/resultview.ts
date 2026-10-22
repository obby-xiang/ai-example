/**
 * resultView 数据契约（设计稿 2026-10-22 §3）的前端类型。
 *
 * 契约口径 = **白名单 + 数据引用 + 版本降级**：
 * - 四个封闭枚举（`kind` / `columns[].type` / `chart.type` / `actions[].action.type`），未知值一律降级；
 * - 一份行数据在帧内只出现一次：`data.ref`（RFC6901 指针，指向 `result` JSON 文档）或 `data.inline`；
 * - `fallback` **必填** —— 任何版本、任何降级都有可读出口。
 *
 * 本类型只用于**展示**；渲染器不渲原始 HTML、不做表达式求值、不认 CSS 类名与模板串（§4-规则 2）。
 */

/** 视图种类：封闭枚举，同时是渲染器注册表的查表键。v1 只实现 `table` / `text`。 */
export type ResultViewKind = 'table' | 'chart' | 'form' | 'text'

/** 内容完整度（≠ 工具 `outcome`）：`partial` = 有截断或部分字段缺失。 */
export type ResultViewStatus = 'ok' | 'empty' | 'partial' | 'error'

/** 列类型白名单（九种，§3.4）。 */
export type ResultViewColumnType =
  | 'string'
  | 'number'
  | 'integer'
  | 'boolean'
  | 'date'
  | 'datetime'
  | 'enum'
  | 'link'
  | 'badge'

/** `enum` / `badge` 列的取值表。 */
export interface ResultViewOption {
  value: string
  label: string
  tone?: 'ok' | 'warn' | 'danger' | 'muted'
}

/** 列定义：**只描述不携带值**。 */
export interface ResultViewColumn {
  key: string
  type: ResultViewColumnType
  /** 列头文案：i18n key（`i18n:` 前缀）或业务字段码；缺省 = key 原样展示 */
  label?: string
  /** 语义格式（`number:2` / `date` / `datetime` …）；禁 pattern 串 */
  format?: string
  /** 相对权重 1..12 */
  width?: number
  align?: 'left' | 'right' | 'center'
  options?: ResultViewOption[]
  hidden?: boolean
}

/** `chart` 规格：v1 只占位（枚举保留、渲染走 fallback，§8.2-O2 已裁）。 */
export interface ResultViewChartSpec {
  type: 'bar' | 'line' | 'area' | 'pie' | 'scatter'
  x: string
  series: { key: string; label?: string }[]
  valueFormat?: string
  stacked?: boolean
}

/** 只读表单（GF 已决态的展示形态；v1 只占位，§3.6）。 */
export interface ResultViewForm {
  fields: {
    key: string
    label: string
    type: 'text' | 'number' | 'boolean' | 'date' | 'enum' | 'multi_select'
    value: unknown
    options?: ResultViewOption[]
  }[]
  submittedAt?: string
}

/** 卡内动作：v1 只允许 `type: "tool"`，且写动作仍走既有确认门（不由 resultView 直发）。 */
export interface ResultViewAction {
  id: string
  label: string
  style?: 'default' | 'primary' | 'danger'
  scope: 'view' | 'row'
  action: { type: 'tool'; tool: string; args?: Record<string, unknown> }
}

/**
 * 视图实例。字段语义与后端 `com.example.configmgr.ai.resultview.ResultView` 一一对应。
 *
 * `data` 用宽松形态（`ref?` / `inline?`）而不是判别联合：线上旧载荷可能两个键都缺，
 * 判别联合会让"读到坏载荷"在类型层就抛错 —— 而契约要求**降级不报错**。
 */
export interface ResultView {
  version: string
  /** 视图实例稳定 id，约定 `rv-<toolCallId>` */
  id: string
  /** 同 id 单调递增：后到者覆盖先到（覆盖式更新，不是冲突检测） */
  revision: number
  kind: ResultViewKind
  /** 一句话标题（i18n key 或业务原文；≤80 字符） */
  title?: string
  /** 补充说明（≤200 字符） */
  description?: string
  status: ResultViewStatus
  /** 数据来源：`ref` = RFC6901 指针（指向 `result` JSON 文档），`inline` = 内联文档；二者互斥 */
  data?: { ref?: string; inline?: unknown }
  columns?: ResultViewColumn[]
  chart?: ResultViewChartSpec
  form?: ResultViewForm
  actions?: ResultViewAction[]
  empty?: { text: string; hint?: string }
  error?: { code: string; message: string; retryable?: boolean }
  a11y?: { label?: string; summary?: string; role?: string }
  /** 降级载荷：**必填**（`text` 必须有内容） */
  fallback: { text: string; format: 'plain' }
  meta?: {
    catalogId?: string
    /** 行数口径的截断（字符口径复用 G5 的 TruncationContract，二者并存不冲突） */
    truncated?: { shownRows: number; totalRows: number }
    source?: string
  }
}
