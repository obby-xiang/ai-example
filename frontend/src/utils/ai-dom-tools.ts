/**
 * AI 可视可操作专项 · 通用原语（设计稿 §3.2 / §3.3 / §3.5 / §3.7）。
 *
 * 职责切分：`ai-regions.ts` 只管「谁被注册 / ref 反查 / epoch」，本模块只管
 * 「遍历 / 裁剪 / 动词 / 闸门」。两者都不碰 `stores/workspace.ts`（避让表格专项）。
 *
 * <h2>安全性质（不是增强项）</h2>
 * <ol>
 * <li><b>看得见才可操作</b>：只遍历已注册区域内带 `data-ai-ref` 的元素；</li>
 * <li><b>后果性动作永不执行</b>：`data-ai-risk="consequential"` 的元素 `ui_click` 一次不点，
 *   回灌点名应改用的注册动作 —— 通用原语因此**结构上不可能**成为绕过确认门的后门；</li>
 * <li><b>不猜</b>：ref 只做属性逐字比对（不拼选择器），入参不接受任何路径/选择器类键。</li>
 * </ol>
 *
 * <h2>为什么原语不直接产 `FrontendToolExecution`</h2>
 * 结局三态的唯一工厂是 `utils/frontend-tools.ts#makeExecution`（D1）。原语只返回
 * {@link UiToolOutcome}（无 `ok` 字段），由 `frontend-tools` 的默认执行器过工厂 ——
 * 这样既守住"单点工厂"，也避免 utils 之间形成 import 环。
 */
import { useWorkspaceStore } from '@/stores/workspace'
import type { AiRisk } from './ai-regions'
import {
  currentAiEpoch,
  listAiRegions,
  rememberAiRef,
  resolveAiRef,
  riskOfElement,
  verbAllowed,
  type AiRegion,
  type AiRegionHit,
  type AiVerb
} from './ai-regions'

// ── 常量（设计稿 §3.7；与后端 Guard / 工具描述同源） ──────────────────────────

/** 快照默认行数（R3 同值）。 */
export const UI_SNAPSHOT_DEFAULT_ROWS = 20
/** 快照硬顶行数。 */
export const UI_SNAPSHOT_MAX_ROWS = 40
/** 快照硬顶字符数（不可破）。 */
export const UI_SNAPSHOT_MAX_CHARS = 2000
/** 单行字符上限。 */
export const UI_SNAPSHOT_LINE_MAX_CHARS = 120
/** 回显值字符上限。 */
export const UI_SNAPSHOT_VALUE_MAX_CHARS = 24
/** 单轮写动词配额。 */
export const UI_WRITE_VERBS_PER_TURN = 12
/** 撤销深度（与表格专项 SHEET_UNDO_DEPTH 同口径）。 */
export const UI_UNDO_DEPTH = 1
/** 写动词最小间隔（毫秒）。 */
export const UI_WRITE_THROTTLE_MS = 80
/** 标签截断长度（快照里的 "label"）。 */
export const UI_LABEL_MAX_CHARS = 24
/** `ui_fill` 的值上限。 */
export const UI_FILL_VALUE_MAX_CHARS = 200
/** `ui_scroll` 的 delta 上界。 */
export const UI_SCROLL_DELTA_MAX = 2000
/** `ui_highlight` 的 note 上限。 */
export const UI_HIGHLIGHT_NOTE_MAX_CHARS = 60
/** 上传文件名回显上限。 */
export const UI_UPLOAD_NAME_MAX_CHARS = 64

/** 原语结局（无 `ok` 字段：由 makeExecution 单点推导，见文件头）。 */
export interface UiToolOutcome {
  outcome: 'ok' | 'failed'
  availability: 'available' | 'degraded' | 'unavailable'
  result: string
}

/** 撤销条目（深度 1；`retoggle` 只对声明 `data-ai-undo` 的元素记）。 */
export type UiUndoEntry =
  | { kind: 'fill'; ref: string; label: string; previous: string }
  | { kind: 'select'; ref: string; label: string; previous: string }
  | { kind: 'retoggle'; ref: string; label: string }

// ── 运行时状态（单轮配额 / 停止 / 撤销 / 高亮 / 播报） ────────────────────────

let turnToken = 'default'
let writeVerbCount = 0
let aborted = false
let lastWriteAt = 0
let writeChain: Promise<void> = Promise.resolve()
let undoEntry: UiUndoEntry | null = null
let announcement: string | null = null
let snapshotCache: { key: string; text: string } | null = null
let highlights: Array<{ el: HTMLElement; badge: HTMLElement }> = []

/** 新一轮开始：token 变化即重置配额与停止位（同一轮内多次调用无副作用）。 */
export function beginUiTurn(token: string): void {
  if (token === turnToken) {
    return
  }
  turnToken = token
  writeVerbCount = 0
  aborted = false
  lastWriteAt = 0
}

/** 用户点「停止」：后续写动词一律拒绝（已执行的不回滚）。 */
export function abortUiOperations(): void {
  aborted = true
}

/** 当前轮写了几个动词（排障/用例用）。 */
export function uiWriteVerbCount(): number {
  return writeVerbCount
}

/** 撤销栈顶（只读；面板据此显示「撤销」）。 */
export function peekUiUndo(): UiUndoEntry | null {
  return undoEntry
}

/** 取走撤销栈顶（深度 1：取走即清空）。 */
export function takeUiUndo(): UiUndoEntry | null {
  const entry = undoEntry
  undoEntry = null
  return entry
}

/** 清空撤销栈（新轮/换页时收口）。 */
export function clearUiUndo(): void {
  undoEntry = null
}

/** 最近一次播报（面板的 aria-live 区据此播报）。 */
export function peekUiAnnouncement(): string | null {
  return announcement
}

/** 清空播报（面板消费后调用）。 */
export function clearUiAnnouncement(): void {
  announcement = null
}

/** 测试/页面卸载复位（不进生产语义：真实路径靠区域逐个注销）。 */
export function resetUiRuntime(): void {
  clearUiHighlights()
  turnToken = 'default'
  writeVerbCount = 0
  aborted = false
  lastWriteAt = 0
  writeChain = Promise.resolve()
  undoEntry = null
  announcement = null
  snapshotCache = null
}

// ── 小工具 ────────────────────────────────────────────────────────────────────

function ok(result: string, availability: 'available' | 'degraded' = 'available'): UiToolOutcome {
  return { outcome: 'ok', availability, result }
}

function fail(result: string, availability: 'available' | 'degraded' | 'unavailable' = 'degraded'): UiToolOutcome {
  return { outcome: 'failed', availability, result }
}

function asString(value: unknown): string | null {
  return typeof value === 'string' && value !== '' ? value : null
}

function normalizeSpace(text: string): string {
  return text.replace(/\s+/g, ' ').trim()
}

function truncate(text: string, max: number): string {
  return text.length > max ? `${text.slice(0, max)}…` : text
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms)
  })
}

function prefersReducedMotion(): boolean {
  const media = typeof window !== 'undefined' ? window.matchMedia : undefined
  if (typeof media !== 'function') {
    return false
  }
  try {
    return media.call(window, '(prefers-reduced-motion: reduce)').matches === true
  } catch {
    return false
  }
}

interface UiContext {
  pageId: string | null
  step: string | null
  taskId: number | null
}

/** 当前工作区上下文（无活动 pinia 时退化为"未知页面"，仅测试/工具脚本会走到）。 */
export function currentUiContext(): UiContext {
  try {
    const snapshot = useWorkspaceStore().snapshot()
    return { pageId: snapshot.pageId ?? null, step: snapshot.step ?? null, taskId: snapshot.taskId ?? null }
  } catch {
    return { pageId: null, step: null, taskId: null }
  }
}

/** 工作区动作记录（审计：ref + verb + epoch 进 recentActions）。 */
function touchWorkspace(summary: string): number {
  try {
    return useWorkspaceStore().touch(summary, 'AI')
  } catch {
    return 0
  }
}

// ── label / role / state 推导 ────────────────────────────────────────────────

/** 徽标里的文字不进 label（否则高亮会污染快照口径）。 */
function labelTextOf(el: HTMLElement): string {
  const explicit = el.getAttribute('data-ai-label')
  if (explicit) {
    return truncate(normalizeSpace(explicit), UI_LABEL_MAX_CHARS)
  }
  const aria = el.getAttribute('aria-label')
  if (aria && aria.trim() !== '') {
    return truncate(normalizeSpace(aria), UI_LABEL_MAX_CHARS)
  }
  const title = el.getAttribute('title')
  if (title && title.trim() !== '') {
    return truncate(normalizeSpace(title), UI_LABEL_MAX_CHARS)
  }
  const text = childTextExcludingBadges(el)
  return truncate(normalizeSpace(text), UI_LABEL_MAX_CHARS)
}

function childTextExcludingBadges(el: HTMLElement): string {
  let out = ''
  for (const node of Array.from(el.childNodes)) {
    if (node.nodeType === Node.TEXT_NODE) {
      out += node.textContent ?? ''
      continue
    }
    if (node.nodeType !== Node.ELEMENT_NODE) {
      continue
    }
    const element = node as HTMLElement
    if (element.hasAttribute('data-ai-highlight-badge')) {
      continue
    }
    out += element.textContent ?? ''
  }
  return out
}

type UiRole = 'button' | 'input' | 'select' | 'toggle' | 'tab' | 'link'

const ROLE_FILTER_DEFAULT: readonly UiRole[] = ['button', 'input', 'select', 'toggle']

function roleOf(el: HTMLElement): UiRole | null {
  const tag = el.tagName.toLowerCase()
  const role = (el.getAttribute('role') ?? '').toLowerCase()
  if (tag === 'button' || role === 'button') {
    return 'button'
  }
  if (tag === 'select' || role === 'combobox' || role === 'listbox') {
    return 'select'
  }
  if (tag === 'a' && el.hasAttribute('href')) {
    return 'link'
  }
  if (role === 'link') {
    return 'link'
  }
  if (role === 'tab') {
    return 'tab'
  }
  if (tag === 'input') {
    const type = (el.getAttribute('type') ?? 'text').toLowerCase()
    if (type === 'checkbox' || type === 'radio') {
      return 'toggle'
    }
    return 'input'
  }
  if (tag === 'textarea' || el.isContentEditable || el.getAttribute('contenteditable') === 'true') {
    return 'input'
  }
  if (role === 'switch' || el.hasAttribute('aria-pressed') || role === 'checkbox' || role === 'radio') {
    return 'toggle'
  }
  if (role === 'textbox' || role === 'searchbox') {
    return 'input'
  }
  return null
}

function stateOf(el: HTMLElement, role: UiRole): string {
  const disabled = (el as HTMLButtonElement).disabled === true
    || el.getAttribute('aria-disabled') === 'true'
    || el.hasAttribute('disabled')
  if (disabled) {
    return 'disabled'
  }
  if (role === 'toggle') {
    const checked = (el as HTMLInputElement).checked === true
      || el.getAttribute('aria-checked') === 'true'
      || el.getAttribute('aria-pressed') === 'true'
    return checked ? 'checked' : 'unchecked'
  }
  if (role === 'tab') {
    return el.getAttribute('aria-selected') === 'true' ? 'selected' : 'unselected'
  }
  return 'enabled'
}

/** 内置 deny（不依赖标注，代码硬判；设计稿 §3.4.2）。 */
function isBuiltinDenied(el: HTMLElement): boolean {
  const tag = el.tagName.toLowerCase()
  if (tag === 'input') {
    const type = (el.getAttribute('type') ?? 'text').toLowerCase()
    if (type === 'password' || type === 'file' || type === 'hidden') {
      return true
    }
  }
  const autocomplete = (el.getAttribute('autocomplete') ?? '').toLowerCase()
  if (autocomplete.startsWith('cc-')) {
    return true
  }
  if (tag === 'a' && el.hasAttribute('download')) {
    return true
  }
  if (tag === 'a') {
    const href = el.getAttribute('href') ?? ''
    if (/^https?:\/\//i.test(href) && typeof window !== 'undefined') {
      try {
        const url = new URL(href, window.location.href)
        if (url.origin !== window.location.origin) {
          return true
        }
      } catch {
        return true
      }
    }
  }
  return el.hasAttribute('data-ai-deny')
}

function fillAllowed(el: HTMLElement): boolean {
  return el.getAttribute('data-ai-fill') === 'allow'
}

/** `fill=allow` 的输入框才回显值，且 ≤24 字符。 */
function valueEchoOf(el: HTMLElement): string | null {
  if (!fillAllowed(el)) {
    return null
  }
  const role = roleOf(el)
  if (role !== 'input') {
    return null
  }
  const raw = el.tagName.toLowerCase() === 'input' || el.tagName.toLowerCase() === 'textarea'
    ? (el as HTMLInputElement).value
    : (el.textContent ?? '')
  if (!raw) {
    return null
  }
  return truncate(raw, UI_SNAPSHOT_VALUE_MAX_CHARS)
}

const PAGE_LABELS: Readonly<Record<string, string>> = {
  import: '导入向导',
  export: '导出向导',
  tasks: '任务中心',
  definitions: '配置定义',
  data: '数据浏览'
}

function pageLabelOf(pageId: string | null): string {
  if (!pageId) {
    return '未知页面'
  }
  return PAGE_LABELS[pageId] ?? pageId
}

// ── 快照 ──────────────────────────────────────────────────────────────────────

interface SnapshotArgs {
  regions: string[] | null
  kinds: readonly UiRole[]
  limit: number
}

function parseSnapshotArgs(args: Record<string, unknown>): { ok: true; value: SnapshotArgs } | { ok: false; reason: string } {
  const rawLimit = args.limit
  let limit = UI_SNAPSHOT_DEFAULT_ROWS
  if (rawLimit !== undefined && rawLimit !== null) {
    const numeric = typeof rawLimit === 'number' ? rawLimit : Number(rawLimit)
    if (!Number.isInteger(numeric) || numeric < 1 || numeric > UI_SNAPSHOT_MAX_ROWS) {
      return {
        ok: false,
        reason: `limit 必须是 1..${UI_SNAPSHOT_MAX_ROWS} 的整数（收到 ${String(rawLimit)}）。`
      }
    }
    limit = numeric
  }
  let regions: string[] | null = null
  if (args.regions !== undefined && args.regions !== null) {
    if (!Array.isArray(args.regions)) {
      return { ok: false, reason: 'regions 必须是区域 id 的字符串数组（缺省 = 全部已注册区域）。' }
    }
    regions = args.regions.map((item) => String(item)).filter((item) => item !== '')
  }
  let kinds: readonly UiRole[] = ROLE_FILTER_DEFAULT
  if (args.kinds !== undefined && args.kinds !== null) {
    if (!Array.isArray(args.kinds)) {
      return { ok: false, reason: 'kinds 必须是角色枚举的字符串数组（button/input/select/toggle/tab/link/all）。' }
    }
    const requested = args.kinds.map((item) => String(item).toLowerCase())
    if (requested.includes('all')) {
      kinds = ['button', 'input', 'select', 'toggle', 'tab', 'link']
    } else {
      const valid: UiRole[] = []
      for (const item of requested) {
        if (item === 'button' || item === 'input' || item === 'select' || item === 'toggle'
          || item === 'tab' || item === 'link') {
          valid.push(item)
        } else {
          return {
            ok: false,
            reason: `kinds 里出现未知角色「${item}」（可选：button/input/select/toggle/tab/link/all）。`
          }
        }
      }
      kinds = valid
    }
  }
  return { ok: true, value: { regions, kinds, limit } }
}

/** `ui_snapshot`：只回已注册区域内、带 `data-ai-ref`、非 deny 的元素。 */
export function aiSnapshot(args: Record<string, unknown> = {}): UiToolOutcome {
  const parsed = parseSnapshotArgs(args)
  if (!parsed.ok) {
    return fail(`参数不合法，界面快照未执行：${parsed.reason}`, 'unavailable')
  }
  const { regions: regionFilter, kinds, limit } = parsed.value
  const epoch = currentAiEpoch()
  const cacheKey = `${epoch}|${JSON.stringify([regionFilter, kinds, limit])}`
  if (snapshotCache && snapshotCache.key === cacheKey) {
    return ok(snapshotCache.text)
  }

  const ctx = currentUiContext()
  const selected = listAiRegions().filter((region) => !regionFilter || regionFilter.includes(region.id))
  const rows: string[] = []
  let total = 0
  for (const region of selected) {
    for (const node of Array.from(region.root.querySelectorAll('[data-ai-ref]'))) {
      const el = node as HTMLElement
      const ref = el.getAttribute('data-ai-ref')
      if (!ref) {
        continue
      }
      rememberAiRef(ref, el, region.id)
      if (isBuiltinDenied(el) || el.isConnected !== true) {
        continue
      }
      const role = roleOf(el)
      if (!role || !kinds.includes(role)) {
        continue
      }
      total += 1
      rows.push(formatRow(ref, role, el))
    }
  }

  const head = `界面快照：${pageLabelOf(ctx.pageId)} · 步骤「${ctx.step ?? '未进入步骤'}」  epoch=${epoch}`
    + `  区域 ${selected.length} 个（${selected.map((region) => region.id).join(' / ') || '无'}）  共 ${total} 个元素`
  const built = capUiSnapshot(head, rows, total, limit, selected.length)
  snapshotCache = { key: cacheKey, text: built }
  return ok(built)
}

function formatRow(ref: string, role: UiRole, el: HTMLElement): string {
  const label = labelTextOf(el)
  const state = stateOf(el, role)
  const fill = fillAllowed(el) ? ' fill=allow' : ''
  const value = valueEchoOf(el)
  const valuePart = value === null ? '' : ` value="${value}"`
  const head = `[${ref}] ${role} "${label}" state=${state}${fill}`
  const line = `${head}${valuePart}`
  if (line.length <= UI_SNAPSHOT_LINE_MAX_CHARS) {
    return line
  }
  const budget = UI_SNAPSHOT_LINE_MAX_CHARS - (`[${ref}] ${role} "${''}" state=${state}${fill}${valuePart}`.length)
  const trimmed = budget > 1 ? `${label.slice(0, budget - 1)}…` : ''
  return `[${ref}] ${role} "${trimmed}" state=${state}${fill}${valuePart}`
}

function thousands(value: number): string {
  return value.toLocaleString('en-US')
}

/**
 * 尺寸闸（设计稿 §3.7）：**行数优先于字符数**，尾行恒在。
 *
 * 尾行是硬要求（模型据此知道"看到的是不是全部"）：未截断也回「共 N（本次回 M）」。
 */
export function capUiSnapshot(
  head: string,
  rows: readonly string[],
  total: number,
  limit: number,
  regionCount: number
): string {
  let lines = [...rows]
  let truncatedBy: 'rows' | 'chars' | null = null
  if (lines.length > limit) {
    lines = lines.slice(0, limit)
    truncatedBy = 'rows'
  }
  const originalChars = [head, ...rows, '共 X'].join('\n').length

  const compose = (body: string[], by: 'rows' | 'chars' | null): string => {
    const footer = `共 ${total} 个元素（本次回 ${body.length} 个；上限 ${UI_SNAPSHOT_MAX_ROWS} 行 / ${UI_SNAPSHOT_MAX_CHARS} 字符）`
    const marker = by === null
      ? []
      : [`…[已截断：原 ${total} ${by === 'rows' ? '个' : '行'} / ${thousands(originalChars)} 字符，已回 ${body.length} 个]`]
    return [head, ...body, ...marker, footer].join('\n')
  }

  let text = compose(lines, truncatedBy)
  if (text.length > UI_SNAPSHOT_MAX_CHARS) {
    if (truncatedBy === null) {
      truncatedBy = 'chars'
    }
    while (lines.length > 0 && compose(lines, truncatedBy).length > UI_SNAPSHOT_MAX_CHARS) {
      lines.pop()
    }
    text = compose(lines, truncatedBy)
  }
  // regionCount 参与头行构造（保留参数语义：头行里的区域数由调用方写死，此处仅做兜底校验）
  void regionCount
  return text
}

// ── 围栏与写动词底座 ─────────────────────────────────────────────────────────

interface FenceResult {
  ok: true
  hit: AiRegionHit
  label: string
  risk: AiRisk
}

type FenceFailure = { ok: false; outcome: UiToolOutcome }

function checkFences(args: Record<string, unknown>, verb: AiVerb): FenceResult | FenceFailure {
  const ref = asString(args.ref)
  if (!ref) {
    return { ok: false, outcome: fail(`参数不合法，${verb} 未执行：缺少 ref（请先 ui_snapshot 取得引用）。`, 'unavailable') }
  }
  // ① 区域围栏
  const hit = resolveAiRef(ref)
  if (!hit) {
    return {
      ok: false,
      outcome: fail(`该 ref 不在当前页面（可能已切页或刷新），请重新 ui_snapshot。ref=${ref}`)
    }
  }
  // ② 上下文围栏（page / step / task 三段，与 WORKSPACE_ACTIONS 同口径）
  const ctx = currentUiContext()
  if (ctx.pageId && hit.region.page !== ctx.pageId) {
    return {
      ok: false,
      outcome: fail(`当前页面为「${ctx.pageId}」，该控件属于「${hit.region.page}」。`
        + `请先导航到「${hit.region.page}」再操作（ref=${ref}）。`)
    }
  }
  if (hit.region.allowSteps && hit.region.allowSteps.length > 0
    && (!ctx.step || !hit.region.allowSteps.includes(ctx.step))) {
    return {
      ok: false,
      outcome: fail(`当前步骤为「${ctx.step ?? '未进入步骤'}」，该控件只在步骤 ${hit.region.allowSteps.join('/')} 可用。`)
    }
  }
  if (hit.region.needsTask && ctx.taskId === null) {
    return { ok: false, outcome: fail('当前没有进行中的任务，该控件需要绑定任务后使用。') }
  }
  // ③ 元素围栏（epoch / detached / deny / verbs）
  const epoch = asString(args.epoch)
  if (!epoch) {
    return { ok: false, outcome: fail('缺少 epoch 参数：该 ref 可能已过期，请重新 ui_snapshot。') }
  }
  if (epoch !== currentAiEpoch()) {
    return {
      ok: false,
      outcome: fail(`界面已变化（epoch 不匹配：当前 ${currentAiEpoch()}，调用时 ${epoch}），`
        + '请重新 ui_snapshot 后再操作。')
    }
  }
  if (hit.el.isConnected !== true) {
    return { ok: false, outcome: fail(`元素已不在页面上（可能已被卸载），请重新 ui_snapshot。ref=${ref}`) }
  }
  if (hit.el.hasAttribute('data-ai-deny')) {
    return { ok: false, outcome: fail(`该元素声明了 data-ai-deny（禁止 AI 操作），本次未执行。ref=${ref}`) }
  }
  if (!verbAllowed(hit.el, hit.region, verb)) {
    return { ok: false, outcome: fail(`该元素不允许「${verb}」动词（data-ai-verbs 收紧了允许集）。ref=${ref}`) }
  }
  return { ok: true, hit, label: labelTextOf(hit.el), risk: riskOfElement(hit.el, hit.region) }
}

/** 写动词底座：配额 + 停止 + 串行节流（返回 null = 可执行）。 */
async function acquireWriteSlot(): Promise<UiToolOutcome | null> {
  const gate = writeChain.then(async () => {
    const wait = UI_WRITE_THROTTLE_MS - (Date.now() - lastWriteAt)
    if (wait > 0) {
      await delay(wait)
    }
    lastWriteAt = Date.now()
  })
  writeChain = gate.catch(() => undefined)
  await gate
  if (aborted) {
    return fail('本轮已停止，后续界面操作一律不执行（已执行的不回滚）。')
  }
  if (writeVerbCount >= UI_WRITE_VERBS_PER_TURN) {
    return fail(`本轮界面操作已达上限（${UI_WRITE_VERBS_PER_TURN} 次），请拆成多轮后再继续。`)
  }
  writeVerbCount += 1
  return null
}

// ── ui_click ─────────────────────────────────────────────────────────────────

/** 拒执指路映射（consequential → 注册动作；单点常量）。 */
function suggestActionFor(ref: string): { kind: 'action'; name: string } | { kind: 'handoff' } | null {
  if (/(\.choose|\.upload)$/.test(ref)) {
    return { kind: 'handoff' }
  }
  if (/(\.start)$/.test(ref)) {
    return { kind: 'action', name: 'start_export' }
  }
  if (/(\.precheck)$/.test(ref)) {
    return { kind: 'action', name: 'start_precheck' }
  }
  if (/(\.publish)$/.test(ref)) {
    return { kind: 'action', name: 'start_publish' }
  }
  return null
}

function consequentialText(ref: string): string {
  const head = `未执行：ref=${ref} 属于后果性动作（data-ai-risk=consequential），通用原语不能点击它。`
  const target = suggestActionFor(ref)
  if (target === null) {
    return `${head}当前没有对应的注册动作，请提示用户手动完成。`
  }
  if (target.kind === 'handoff') {
    return `${head}请改用 ui_request_upload（对话内交接卡片，由用户手势选择文件）。`
  }
  return `${head}请改用注册动作 ${target.name}（它会进确认门，由用户确认后才执行）。`
}

/** `ui_click`：区域 → 上下文 → 元素 → 风险四段链；consequential **零点击**。 */
export async function aiClick(args: Record<string, unknown> = {}): Promise<UiToolOutcome> {
  const fenced = checkFences(args, 'click')
  if (!fenced.ok) {
    return fenced.outcome
  }
  const { hit, label, risk } = fenced
  const ref = String(args.ref)
  const epoch = String(args.epoch)

  if (risk === 'consequential') {
    // 安全性质：一次不点。这里绝不调用 el.click()。
    return fail(consequentialText(ref))
  }

  const slot = await acquireWriteSlot()
  if (slot) {
    return slot
  }
  if (!hit.el.isConnected) {
    return fail(`元素已不在页面上（可能已被卸载），请重新 ui_snapshot。ref=${ref}`)
  }
  highlightElement(hit.el, label)
  hit.el.click()
  if (risk === 'mutate-local') {
    const undoable = hit.el.getAttribute('data-ai-undo') === 'retoggle'
    if (undoable) {
      undoEntry = { kind: 'retoggle', ref, label }
    }
  }
  const version = touchWorkspace(`AI 点击：${label}（ref=${ref}，epoch=${epoch}）`)
  const base = `已点击「${label}」（ref=${ref}，verb=click，risk=${risk}，epoch=${epoch}）。`
    + `界面已同步（数据版本 #${version}）。`
  const undoHint = undoEntry && undoEntry.ref === ref ? '可撤销：对话内「撤销」或让用户再点一次。' : ''
  return ok(`${base}${undoHint}`)
}

// ── ui_fill ──────────────────────────────────────────────────────────────────

function nativeSetValue(el: HTMLElement, value: string): void {
  const tag = el.tagName.toLowerCase()
  if (tag === 'input' || tag === 'textarea') {
    const proto = tag === 'input' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype
    const descriptor = Object.getOwnPropertyDescriptor(proto, 'value')
    if (descriptor?.set) {
      descriptor.set.call(el, value)
    } else {
      (el as HTMLInputElement).value = value
    }
    return
  }
  el.textContent = value
}

function readValueOf(el: HTMLElement): string {
  const tag = el.tagName.toLowerCase()
  if (tag === 'input' || tag === 'textarea') {
    return (el as HTMLInputElement).value
  }
  return el.textContent ?? ''
}

/** `ui_fill`：只对原生 input/textarea/contenteditable 且 `data-ai-fill="allow"` 生效。 */
export async function aiFill(args: Record<string, unknown> = {}): Promise<UiToolOutcome> {
  const value = typeof args.value === 'string' ? args.value : null
  if (value === null) {
    return fail('参数不合法，填写未执行：缺少 value。', 'unavailable')
  }
  if (value.length > UI_FILL_VALUE_MAX_CHARS) {
    return fail(`参数不合法，填写未执行：value 超过 ${UI_FILL_VALUE_MAX_CHARS} 字符（共 ${value.length} 字符）。`, 'unavailable')
  }
  const fenced = checkFences(args, 'fill')
  if (!fenced.ok) {
    return fenced.outcome
  }
  const { hit, label, risk } = fenced
  const ref = String(args.ref)
  const epoch = String(args.epoch)
  if (!fillAllowed(hit.el)) {
    return fail(`该输入框未声明可填写（data-ai-fill="allow"），拒绝填写。ref=${ref}`)
  }
  if (risk !== 'mutate-local') {
    return fail(`该元素风险为 ${risk}，填写属于本地写动作，只在 mutate-local 元素上允许（ref=${ref}）。`)
  }
  const slot = await acquireWriteSlot()
  if (slot) {
    return slot
  }
  const previous = readValueOf(hit.el)
  nativeSetValue(hit.el, value)
  hit.el.dispatchEvent(new Event('input', { bubbles: true }))
  hit.el.dispatchEvent(new Event('change', { bubbles: true }))
  const readback = readValueOf(hit.el)
  if (readback !== value) {
    return fail(`值未生效（页面可能做了格式化或校验替换），当前值「${truncate(readback, UI_SNAPSHOT_VALUE_MAX_CHARS)}」`
      + `与目标「${truncate(value, UI_SNAPSHOT_VALUE_MAX_CHARS)}」不一致，请确认后重试。ref=${ref}`)
  }
  highlightElement(hit.el, label)
  undoEntry = { kind: 'fill', ref, label, previous }
  const version = touchWorkspace(`AI 填写：${label}（ref=${ref}，epoch=${epoch}）`)
  return ok(`已填写「${label}」= ${truncate(value, UI_SNAPSHOT_VALUE_MAX_CHARS)}（ref=${ref}，数据版本 #${version}）。`
    + `原值「${truncate(previous, UI_SNAPSHOT_VALUE_MAX_CHARS)}」已记录，可撤销。`)
}

// ── ui_select ────────────────────────────────────────────────────────────────

function optionNodes(region: AiRegion): HTMLElement[] {
  const local = Array.from(region.root.querySelectorAll('[data-ai-option]')) as HTMLElement[]
  if (local.length > 0) {
    return local
  }
  // 弹层（Element Plus 的 teleport 形态）：只认 [data-ai-option]，绝不按类名匹配
  return Array.from(document.querySelectorAll('[data-ai-option]')) as HTMLElement[]
}

function optionOf(node: HTMLElement): string {
  return node.getAttribute('data-ai-option') ?? ''
}

function isOptionChecked(node: HTMLElement): boolean {
  if ((node as HTMLInputElement).checked === true) {
    return true
  }
  return node.getAttribute('aria-checked') === 'true' || node.getAttribute('data-ai-selected') === 'true'
}

function selectedOptionInRegion(region: AiRegion): string {
  for (const node of optionNodes(region)) {
    if (isOptionChecked(node)) {
      return optionOf(node)
    }
  }
  return ''
}

function readSelectedOption(hit: AiRegionHit): string {
  const declared = hit.el.getAttribute('data-ai-selected')
  if (declared !== null) {
    return declared
  }
  const fromRegion = selectedOptionInRegion(hit.region)
  if (fromRegion) {
    return fromRegion
  }
  return ''
}

/** `ui_select`：setter 优先（Element Plus 自绘弹层只能靠它），回退「点开→精确匹配→点选→回读」。 */
export async function aiSelect(args: Record<string, unknown> = {}): Promise<UiToolOutcome> {
  const option = asString(args.option)
  if (!option) {
    return fail('参数不合法，选择未执行：缺少 option（选项的语义值，不是索引）。', 'unavailable')
  }
  const fenced = checkFences(args, 'select')
  if (!fenced.ok) {
    return fenced.outcome
  }
  const { hit, label, risk } = fenced
  const ref = String(args.ref)
  const epoch = String(args.epoch)
  if (risk !== 'mutate-local') {
    return fail(`该元素风险为 ${risk}，选择属于本地写动作，只在 mutate-local 元素上允许（ref=${ref}）。`)
  }
  const slot = await acquireWriteSlot()
  if (slot) {
    return slot
  }
  const previous = readSelectedOption(hit)
  const setter = hit.region.setters?.[ref]
  if (setter) {
    const applied = await setter(option)
    if (!applied) {
      return fail(`未选择：setter 未能把「${label}」设为「${option}」。ref=${ref}`)
    }
  } else {
    hit.el.click()
    const candidates = optionNodes(hit.region)
    const exact = candidates.find((node) => optionOf(node) === option)
    const trimmed = exact ?? candidates.find((node) => optionOf(node).trim() === option.trim())
    if (!trimmed) {
      const available = candidates.slice(0, 10).map(optionOf).filter((item) => item !== '')
      const list = available.length > 0 ? available.join('、') : '（当前没有可见选项）'
      return fail(`未选择：下拉选项里没有「${option}」。可选：${list}。ref=${ref}`)
    }
    trimmed.click()
  }
  const readback = readSelectedOption(hit)
  if (readback !== '' && readback !== option) {
    return fail(`选择未生效：当前选中「${readback}」，目标是「${option}」，请确认后重试。ref=${ref}`)
  }
  highlightElement(hit.el, label)
  undoEntry = { kind: 'select', ref, label, previous }
  const version = touchWorkspace(`AI 选择：${label}=${option}（ref=${ref}，epoch=${epoch}）`)
  return ok(`已选择「${label}」= ${option}（ref=${ref}，数据版本 #${version}）。`
    + `原选择「${previous || '（空）'}」已记录，可撤销。`)
}

// ── ui_scroll / ui_highlight ─────────────────────────────────────────────────

/** `ui_scroll`：只改视口 —— 不改 dataVersion、不记撤销点。 */
export function aiScroll(args: Record<string, unknown> = {}): UiToolOutcome {
  const ref = asString(args.ref)
  if (!ref) {
    return fail('参数不合法，滚动未执行：缺少 ref（要滚到的元素或容器）。', 'unavailable')
  }
  const delta = args.delta === undefined ? null : Number(args.delta)
  if (delta !== null && (!Number.isFinite(delta) || Math.abs(delta) > UI_SCROLL_DELTA_MAX)) {
    return fail(`参数不合法，滚动未执行：delta 必须是不超过 ±${UI_SCROLL_DELTA_MAX} 的数字。`, 'unavailable')
  }
  const block = asString(args.block) ?? 'nearest'
  if (!['start', 'center', 'end', 'nearest'].includes(block)) {
    return fail('参数不合法，滚动未执行：block 只能是 start / center / end / nearest。', 'unavailable')
  }
  const fenced = checkFences(args, 'scroll')
  if (!fenced.ok) {
    return fenced.outcome
  }
  const { hit, label } = fenced
  if (delta !== null) {
    hit.el.scrollTop += delta
  }
  if (typeof hit.el.scrollIntoView === 'function') {
    hit.el.scrollIntoView({ block: block as ScrollLogicalPosition, behavior: 'auto' })
  }
  return ok(`已滚动到「${label}」（ref=${ref}）。只改了视口，界面数据未变化。`)
}

/** `ui_highlight`：描边 + 图标 + 文案三重冗余（WCAG 1.4.1），并播报给读屏。 */
export function aiHighlight(args: Record<string, unknown> = {}): UiToolOutcome {
  const note = asString(args.note)
  if (note && note.length > UI_HIGHLIGHT_NOTE_MAX_CHARS) {
    return fail(`参数不合法，高亮未执行：note 超过 ${UI_HIGHLIGHT_NOTE_MAX_CHARS} 字符。`, 'unavailable')
  }
  const fenced = checkFences(args, 'highlight')
  if (!fenced.ok) {
    return fenced.outcome
  }
  const { hit, label } = fenced
  highlightElement(hit.el, label, note ?? undefined)
  return ok(`已高亮「${label}」，请用户在左侧工作区查看。`)
}

// ── 高亮（三重冗余） ─────────────────────────────────────────────────────────

const HIGHLIGHT_CLASSES = ['ring-2', 'ring-offset-1', 'ring-[#2563eb]']

function highlightElement(el: HTMLElement, label: string, note?: string): void {
  clearUiHighlights()
  const reduce = prefersReducedMotion()
  for (const name of HIGHLIGHT_CLASSES) {
    el.classList.add(name)
  }
  if (!reduce) {
    el.classList.add('transition-shadow')
  }
  el.setAttribute('data-ai-highlighted', 'true')
  if (typeof el.scrollIntoView === 'function') {
    el.scrollIntoView({ block: 'nearest', behavior: reduce ? 'auto' : 'smooth' })
  }
  const badge = document.createElement('span')
  badge.setAttribute('data-ai-highlight-badge', '')
  badge.className = 'inline-flex items-center gap-1 ml-1 px-1.5 py-0.5 rounded-[4px] '
    + 'bg-[#eff6ff] text-[#2563eb] text-[11px] leading-[1.4] align-middle'
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
  svg.setAttribute('viewBox', '0 0 16 16')
  svg.setAttribute('width', '12')
  svg.setAttribute('height', '12')
  svg.setAttribute('aria-hidden', 'true')
  svg.setAttribute('fill', 'currentColor')
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path')
  // AI 提示图标（四角星）：纯几何，不含 emoji
  path.setAttribute('d', 'M8 1l1.6 4.1L14 7l-4.4 1.9L8 13 6.4 8.9 2 7l4.4-1.9z')
  svg.appendChild(path)
  badge.appendChild(svg)
  const text = note ? `AI 提示：${truncate(note, UI_HIGHLIGHT_NOTE_MAX_CHARS)}` : `AI 提示：${label}`
  badge.appendChild(document.createTextNode(text))
  const host = el.parentElement ?? el
  host.appendChild(badge)
  highlights.push({ el, badge })
  announcement = `AI 正在操作左侧工作区：${note ?? label}`
  setTimeout(() => {
    clearUiHighlights()
  }, 2500)
}

/** 撤掉全部高亮（含徽标），供页面卸载与用例复位。 */
export function clearUiHighlights(): void {
  for (const { el, badge } of highlights) {
    for (const name of [...HIGHLIGHT_CLASSES, 'transition-shadow']) {
      el.classList.remove(name)
    }
    el.removeAttribute('data-ai-highlighted')
    badge.remove()
  }
  highlights = []
}

// ── 撤销（只给用户） ─────────────────────────────────────────────────────────

/** 应用一条撤销（深度 1；ref 失效如实说"没有可撤销的界面操作"）。 */
export function applyUiUndo(entry: UiUndoEntry): UiToolOutcome {
  const hit = resolveAiRef(entry.ref)
  if (!hit || hit.el.isConnected !== true) {
    return fail(`没有可撤销的界面操作：${entry.ref} 已不在页面上（刷新或切页后撤销失效）。`)
  }
  if (entry.kind === 'retoggle') {
    hit.el.click()
    touchWorkspace(`已撤销：${entry.label}`)
    return ok(`已撤销「${entry.label}」（ref=${entry.ref}），界面回到点击前的状态。`)
  }
  if (entry.kind === 'fill') {
    nativeSetValue(hit.el, entry.previous)
    hit.el.dispatchEvent(new Event('input', { bubbles: true }))
    hit.el.dispatchEvent(new Event('change', { bubbles: true }))
    const readback = readValueOf(hit.el)
    if (readback !== entry.previous) {
      return fail(`撤销未生效：「${entry.label}」当前值是「${truncate(readback, UI_SNAPSHOT_VALUE_MAX_CHARS)}」，`
        + `目标是「${truncate(entry.previous, UI_SNAPSHOT_VALUE_MAX_CHARS)}」。`)
    }
    touchWorkspace(`已撤销：填写「${entry.label}」`)
    return ok(`已撤销「${entry.label}」，值已回到「${truncate(entry.previous, UI_SNAPSHOT_VALUE_MAX_CHARS) || '（空）'}」。`)
  }
  const setter = hit.region.setters?.[entry.ref]
  if (setter) {
    const applied = setter(entry.previous)
    void applied
    touchWorkspace(`已撤销：选择「${entry.label}」`)
    return ok(`已撤销「${entry.label}」，选项已回到「${entry.previous || '（空）'}」。`)
  }
  return fail(`撤销未生效：「${entry.label}」没有可用于回退的 setter，请提示用户手动改回。`)
}

// ── 上传交接（唯一「等人」工具） ──────────────────────────────────────────────

/** `ui_request_upload` 入参闸：**永不接受**路径/选择器类键。 */
const FORBIDDEN_UPLOAD_KEYS = ['path', 'filePath', 'filename', 'file', 'dir', 'directory', 'selector', 'xpath', 'html', 'css']

export interface UploadArgs {
  ref: string | null
  accept: string[] | null
}

export function validateUploadArgs(
  args: Record<string, unknown> = {}
): { ok: true; value: UploadArgs } | { ok: false; outcome: UiToolOutcome } {
  for (const key of FORBIDDEN_UPLOAD_KEYS) {
    if (Object.prototype.hasOwnProperty.call(args, key)) {
      return {
        ok: false,
        outcome: fail(`参数不合法，上传交接未执行：不接受「${key}」参数（本工具永不经手文件路径，`
          + '文件只能由用户在浏览器手势里选择）。', 'unavailable')
      }
    }
  }
  const ref = asString(args.ref)
  let accept: string[] | null = null
  if (args.accept !== undefined && args.accept !== null) {
    if (!Array.isArray(args.accept)) {
      return { ok: false, outcome: fail('参数不合法，上传交接未执行：accept 必须是扩展名数组（只能收窄页面允许的 .xlsx/.zip）。', 'unavailable') }
    }
    const normalized = args.accept
      .map((item) => String(item).replace(/^\./, '').toLowerCase())
      .filter((item) => item !== '')
    const illegal = normalized.filter((item) => item !== 'xlsx' && item !== 'zip')
    if (illegal.length > 0) {
      return {
        ok: false,
        outcome: fail(`参数不合法，上传交接未执行：accept 只能收窄到 xlsx / zip（收到 ${illegal.join('、')}）。`, 'unavailable')
      }
    }
    accept = normalized
  }
  return { ok: true, value: { ref, accept } }
}

/**
 * 唤起系统文件选择器（**必须在用户手势的同步调用栈里**被调用）。
 *
 * 返回 null ⇒ 浏览器不支持（Safari/Firefox/旧 Chromium）；返回 Promise<File[]> ⇒ 用户取消会 reject
 * 一个 `AbortError`。**注意**：本函数体内不得出现任何 `await`（首个 await 会丢掉手势上下文）。
 */
export function requestFilePicker(accept: readonly string[] | null): Promise<File[]> | null {
  if (typeof window === 'undefined') {
    return null
  }
  const picker = (window as unknown as { showOpenFilePicker?: (options?: unknown) => Promise<Array<{ getFile: () => Promise<File> }>> }).showOpenFilePicker
  if (typeof picker !== 'function') {
    return null
  }
  const extensions = (accept && accept.length > 0 ? accept : ['xlsx', 'zip']).map((ext) => `.${ext}`)
  const options = {
    multiple: false,
    types: [{ description: '配置数据文件', accept: { 'application/octet-stream': extensions } }],
    excludeAcceptAllOption: false
  }
  // 同步调用：手势上下文只在这一帧内有效
  return picker.call(window, options).then(async (handles) => {
    const files: File[] = []
    for (const handle of handles) {
      files.push(await handle.getFile())
    }
    return files
  })
}

/** 上传文件名的回显口径：只回 basename、≤64 字符（超长保留扩展名）、永不含盘符/路径分隔。 */
export function uploadFileNameOf(file: File): string {
  const raw = typeof file.name === 'string' ? file.name : ''
  const base = raw.split(/[\\/]/).pop() ?? ''
  if (base.length <= UI_UPLOAD_NAME_MAX_CHARS) {
    return base
  }
  const dot = base.lastIndexOf('.')
  const ext = dot > 0 ? base.slice(dot) : ''
  const keep = Math.max(1, UI_UPLOAD_NAME_MAX_CHARS - ext.length - 1)
  return `${base.slice(0, keep)}…${ext}`
}

/** 浏览器是否支持 `showOpenFilePicker`（供 store 提前分流 available/degraded）。 */
export function supportsFilePicker(): boolean {
  return typeof window !== 'undefined'
    && typeof (window as unknown as { showOpenFilePicker?: unknown }).showOpenFilePicker === 'function'
}
