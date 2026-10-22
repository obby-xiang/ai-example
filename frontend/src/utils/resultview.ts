/**
 * resultView 的前端契约工具（设计稿 2026-10-22 §3 / §4 / §7.2）。
 *
 * 四组能力，全部是**纯函数**（可快照对账、可在测试里直接钉）：
 * 1. {@link buildPublishConflictView}：发布冲突 → `kind=table` 视图（确定性构造，首个迁移对象）；
 * 2. {@link normalizeResultView}：入站视图的白名单归一化 + 规模收敛 + **未知一律降级**；
 * 3. {@link resolveViewRows} / {@link resolveJsonPointer}：`data.ref`（RFC6901）与 `data.inline` 求值；
 * 4. {@link sanitizeResultViewText}：出端消毒（HTML 标签 / 事件处理器 / `javascript:`）。
 *
 * 与后端 `com.example.configmgr.ai.resultview.ResultViews` 同值同口径（上限、消毒占位符、目录标识），
 * 两侧各有一组用例钉住同一份契约 —— 跨端漂移在任一侧都会红。
 */
import type {
  ResultView,
  ResultViewColumn,
  ResultViewColumnType,
  ResultViewKind,
  ResultViewStatus
} from '@/types/resultview'

/** 契约版本（major-only：major 不同即不兼容，走 fallback）。 */
export const RESULTVIEW_VERSION = '1.0'

/** 宿主组件目录标识（v1 只有一个目录）。 */
export const RESULTVIEW_CATALOG_ID = 'configmgr/resultview/v1'

/** 规模上限（与后端 `ResultViews` 同值）：行 / 列 / UTF-8 字节。 */
export const RESULTVIEW_LIMITS = { maxRows: 200, maxColumns: 50, maxBytes: 256 * 1024 } as const

/** 消毒占位符（与后端 `ResultViews.REDACTED` 同值）。 */
export const RESULTVIEW_REDACTED = '[redacted]'

/** 连 `fallback` 都没有时的通用兜底文案 key。 */
export const RESULTVIEW_GENERIC_FALLBACK = 'i18n:resultview.fallback.generic'

/** 发布冲突视图只展示前 5 条（与旧摘要 `slice(0, 5)` 及模型侧口径一致）。 */
export const PUBLISH_CONFLICT_SHOWN_ROWS = 5

/** v1 已实现的 kind（未知 / 占位 kind 一律走 fallback）。 */
const IMPLEMENTED_KINDS: readonly ResultViewKind[] = ['table', 'text']

const COLUMN_TYPES: readonly ResultViewColumnType[] = [
  'string', 'number', 'integer', 'boolean', 'date', 'datetime', 'enum', 'link', 'badge'
]

const STATUSES: readonly ResultViewStatus[] = ['ok', 'empty', 'partial', 'error']

/**
 * 禁形：HTML 可执行/可外联标签、`javascript:` 伪协议、事件处理器属性。
 *
 * 与后端 `ResultViews.FORBIDDEN_MARKUP` 同形 —— 刻意**不**拦裸尖括号（`值 <ABC> 不合法`
 * 是正常业务文本，拦了会把正常结果误判成攻击）。
 */
const FORBIDDEN_MARKUP = /<\s*\/?\s*(script|iframe|object|embed|svg|style|link|meta|base)\b[^>]*>?|javascript\s*:|\bon[a-z]+\s*=/gi
const FORBIDDEN_MARKUP_TEST = /<\s*\/?\s*(script|iframe|object|embed|svg|style|link|meta|base)\b[^>]*>?|javascript\s*:|\bon[a-z]+\s*=/i

export interface NormalizedResultView {
  /** null = 不可渲（调用方渲 `fallbackText`）。 */
  view: ResultView | null
  /** 降级文案（视图自带 `fallback.text` 优先，缺省用通用兜底 key）。 */
  fallbackText: string
}

/** 发布冲突明细的一行（来源 = 后端 `ValidationIssue` 的结构化字段）。 */
export interface PublishConflictIssue {
  defCode: string
  rowKey?: string | null
  message: string
}

// ── 消毒 ──────────────────────────────────────────────────────────────────

/** 是否含禁形（HTML 可执行标签 / 事件处理器 / 伪协议）。 */
export function containsForbiddenMarkup(text: string | null | undefined): boolean {
  return typeof text === 'string' && FORBIDDEN_MARKUP_TEST.test(text)
}

/** 自由文本消毒：命中的片段替换为 {@link RESULTVIEW_REDACTED}。 */
export function sanitizeResultViewText(text: string | null | undefined): string {
  if (typeof text !== 'string' || text.length === 0) {
    return typeof text === 'string' ? text : ''
  }
  return text.replace(FORBIDDEN_MARKUP, RESULTVIEW_REDACTED)
}

/** i18n 解析：`i18n:` 前缀走 `t()`，其余按原文（业务字段码 / 用户数据不翻译）。 */
export function resolveViewText(translate: (key: string) => string, text: string | null | undefined): string {
  if (typeof text !== 'string' || text.length === 0) {
    return ''
  }
  if (!text.startsWith('i18n:')) {
    return text
  }
  const key = text.slice('i18n:'.length)
  const translated = translate(key)
  return translated === key ? '' : translated
}

// ── 数据引用 ──────────────────────────────────────────────────────────────

/** RFC6901 指针求值（`/` = 整份；`~1` → `/`，`~0` → `~`；越界 / 形状不符返回 undefined）。 */
export function resolveJsonPointer(document: unknown, ref: string): unknown {
  if (typeof ref !== 'string' || ref.length === 0 || ref === '/') {
    return document
  }
  if (!ref.startsWith('/')) {
    return undefined
  }
  let cursor: unknown = document
  for (const rawToken of ref.slice(1).split('/')) {
    const token = rawToken.replace(/~1/g, '/').replace(/~0/g, '~')
    if (Array.isArray(cursor)) {
      const index = Number(token)
      if (!Number.isInteger(index) || index < 0 || index >= cursor.length) {
        return undefined
      }
      cursor = cursor[index]
      continue
    }
    if (cursor === null || typeof cursor !== 'object' || !(token in (cursor as object))) {
      return undefined
    }
    cursor = (cursor as Record<string, unknown>)[token]
  }
  return cursor
}

/** 把"行容器"（数组 / `{rows|issues|items}`）规范成行数组。 */
function rowsOf(document: unknown): Record<string, unknown>[] {
  if (Array.isArray(document)) {
    return document.filter((row): row is Record<string, unknown> => row !== null && typeof row === 'object')
  }
  if (document !== null && typeof document === 'object') {
    for (const key of ['rows', 'issues', 'items'] as const) {
      const candidate = (document as Record<string, unknown>)[key]
      if (Array.isArray(candidate)) {
        return candidate.filter((row): row is Record<string, unknown> => row !== null && typeof row === 'object')
      }
    }
  }
  return []
}

/**
 * 视图的行数组。
 *
 * @param documentText `result` 原文（`data.ref` 的求值域；非 JSON 或指针不中 ⇒ null）
 * @returns null = 解不出（渲染走 fallback）；`[]` = 解得出但零行（渲染空态）
 */
export function resolveViewRows(
  view: ResultView,
  documentText?: string | null
): Record<string, unknown>[] | null {
  const data = view.data
  if (!data) {
    return []
  }
  if (data.inline !== undefined && data.inline !== null) {
    return rowsOf(data.inline)
  }
  if (typeof data.ref === 'string') {
    if (typeof documentText !== 'string' || documentText.trim().length === 0) {
      return null
    }
    let document: unknown
    try {
      document = JSON.parse(documentText)
    } catch {
      return null
    }
    const target = resolveJsonPointer(document, data.ref)
    return target === undefined ? null : rowsOf(target)
  }
  return []
}

// ── 归一化（白名单 + 规模收敛 + 降级） ────────────────────────────────────

function fallbackTextOf(raw: unknown): string {
  if (raw !== null && typeof raw === 'object') {
    const fallback = (raw as Record<string, unknown>).fallback
    if (fallback !== null && typeof fallback === 'object') {
      const text = (fallback as Record<string, unknown>).text
      if (typeof text === 'string' && text.trim().length > 0) {
        return text
      }
    }
  }
  return RESULTVIEW_GENERIC_FALLBACK
}

function majorOf(version: unknown): string | null {
  if (typeof version !== 'string') {
    return null
  }
  const [major] = version.split('.')
  return major && /^\d+$/.test(major) ? major : null
}

/** 视图序列化后的 UTF-8 字节数（契约的第三把尺，与帧预算无关 —— 帧侧另算）。 */
function viewBytes(view: ResultView): number {
  try {
    return new TextEncoder().encode(JSON.stringify(view)).length
  } catch {
    return 0
  }
}

function isColumn(raw: unknown): raw is ResultViewColumn {
  if (raw === null || typeof raw !== 'object') {
    return false
  }
  const column = raw as Record<string, unknown>
  return typeof column.key === 'string'
    && column.key.length > 0
    && typeof column.type === 'string'
    && (COLUMN_TYPES as readonly string[]).includes(column.type)
}

/**
 * 入站视图归一化：**只认白名单，未知一律降级**（§4-规则 1）。
 *
 * 归一的动作有三类（都不改语义，只收敛形状）：
 * - 版本 / kind / 列类型 / status / fallback 任一项不合法 ⇒ `view: null` + 降级文案；
 * - 列数 > 50 ⇒ 截到 50；行数 > 200 ⇒ 截到 200，并置 `status: partial` + `meta.truncated`；
 * - `chart` / `form` 在 v1 只占位 ⇒ 同"未知 kind"处理（走 fallback，不白屏）。
 *
 * @param documentText `result` 原文（供 `data.ref` 求值；缺省 = 只有 inline 能解）
 */
export function normalizeResultView(raw: unknown, documentText?: string | null): NormalizedResultView {
  const fallbackText = fallbackTextOf(raw)
  if (raw === null || typeof raw !== 'object') {
    return { view: null, fallbackText }
  }
  const candidate = raw as Record<string, unknown>
  if (majorOf(candidate.version) !== '1') {
    return { view: null, fallbackText }
  }
  if (typeof candidate.kind !== 'string' || !(IMPLEMENTED_KINDS as readonly string[]).includes(candidate.kind)) {
    return { view: null, fallbackText }
  }
  if (typeof candidate.status !== 'string' || !(STATUSES as readonly string[]).includes(candidate.status)) {
    return { view: null, fallbackText }
  }
  const fallback = candidate.fallback
  if (fallback === null || typeof fallback !== 'object'
    || typeof (fallback as Record<string, unknown>).text !== 'string'
    || ((fallback as Record<string, unknown>).text as string).trim().length === 0) {
    return { view: null, fallbackText }
  }
  const kind = candidate.kind as ResultViewKind
  let columns: ResultViewColumn[] | undefined
  if (kind === 'table') {
    const rawColumns = candidate.columns
    if (!Array.isArray(rawColumns) || rawColumns.length === 0 || !rawColumns.every(isColumn)) {
      return { view: null, fallbackText }
    }
    columns = rawColumns.slice(0, RESULTVIEW_LIMITS.maxColumns)
  }

  const view = { ...(candidate as unknown as ResultView), columns }
  if (kind !== 'table') {
    return viewBytes(view) > RESULTVIEW_LIMITS.maxBytes ? { view: null, fallbackText } : { view, fallbackText }
  }

  const rows = resolveViewRows(view, documentText)
  if (rows === null) {
    return { view: null, fallbackText }
  }
  let status = view.status
  let meta = view.meta
  const clippedColumns = (columns?.length ?? 0) < ((candidate.columns as unknown[]).length)
  let trimmed: ResultView = view
  if (rows.length > RESULTVIEW_LIMITS.maxRows) {
    const totalRows = rows.length
    const kept = rows.slice(0, RESULTVIEW_LIMITS.maxRows)
    status = 'partial'
    meta = {
      ...(meta ?? {}),
      truncated: {
        shownRows: RESULTVIEW_LIMITS.maxRows,
        totalRows: (meta?.truncated?.totalRows ?? 0) > totalRows ? (meta?.truncated?.totalRows as number) : totalRows
      }
    }
    trimmed = {
      ...view,
      status,
      meta,
      data: view.data?.inline !== undefined
        ? { ...view.data, inline: { rows: kept } }
        : { inline: { rows: kept } }
    }
  }
  if (clippedColumns && status === 'ok') {
    status = 'partial'
  }
  const finalView: ResultView = trimmed === view ? { ...view, status, meta } : { ...trimmed, status }
  // §4-规则 8 的第三把尺：超 256KB ⇒ 不渲（走 fallback），绝不把超大载荷交给渲染层。
  // 行数收敛之后仍超预算的情形也由这里兜住（行 / 列 / 字节三把尺都可独立触发降级）。
  return viewBytes(finalView) > RESULTVIEW_LIMITS.maxBytes ? { view: null, fallbackText } : { view: finalView, fallbackText }
}

// ── 发布冲突视图（首个迁移对象，§5.3） ────────────────────────────────────

function labelOrNull(label: string): string | null {
  return containsForbiddenMarkup(label) ? null : label
}

/**
 * 发布冲突明细 → `kind=table` 视图。列 = 配置 / 行键 / 原因，行 = 结构化 issues（前 5 条）。
 *
 * 为什么在前端构造：发布是**异步作业**，冲突在作业终态后由前端拉 `/issues` 才发现
 * （后端 `start_publish` 只回「作业已启动」）。这条路径上没有 `tool_result` 帧可挂视图，
 * 所以视图在**检出点**按同一份契约确定性构造，并经 `ToolPart.resultView` 随段持久化。
 * 后端契约模型仍是口径权威（上限 / 消毒 / 降级判据两侧同值）。
 */
export function buildPublishConflictView(input: {
  /** 视图 id（约定 `rv-<toolCallId>`） */
  id: string
  issues: PublishConflictIssue[]
  /** 真实冲突总数（issues 可能已被上游裁剪） */
  total: number
}): ResultView {
  const shown = input.issues.slice(0, PUBLISH_CONFLICT_SHOWN_ROWS).map((issue) => ({
    defCode: sanitizeResultViewText(issue.defCode),
    rowKey: sanitizeResultViewText(issue.rowKey ?? '-') || '-',
    message: sanitizeResultViewText(issue.message)
  }))
  const totalRows = Math.max(input.total, input.issues.length)
  const truncated = totalRows > shown.length
    ? { shownRows: shown.length, totalRows }
    : undefined
  const columns: ResultViewColumn[] = [
    {
      key: 'defCode',
      type: 'string',
      label: labelOrNull('i18n:resultview.publishConflict.col.defCode') ?? undefined,
      width: 4
    },
    {
      key: 'rowKey',
      type: 'string',
      label: labelOrNull('i18n:resultview.publishConflict.col.rowKey') ?? undefined,
      width: 3
    },
    {
      key: 'message',
      type: 'string',
      label: labelOrNull('i18n:resultview.publishConflict.col.message') ?? undefined,
      width: 5
    }
  ]
  return {
    version: RESULTVIEW_VERSION,
    id: input.id,
    revision: 1,
    kind: 'table',
    title: labelOrNull('i18n:resultview.publishConflict.title') ?? undefined,
    status: truncated ? 'partial' : 'ok',
    data: { inline: { rows: shown } },
    columns,
    a11y: { label: 'i18n:resultview.publishConflict.label', role: 'table' },
    fallback: { text: 'i18n:resultview.publishConflict.fallback', format: 'plain' },
    meta: {
      catalogId: RESULTVIEW_CATALOG_ID,
      source: 'validation_issue',
      ...(truncated ? { truncated } : {})
    }
  }
}
