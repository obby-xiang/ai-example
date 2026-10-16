/**
 * 对话时序包 批4④（A5'）→ R-154 #13+14（用户直裁「彻底拆除」组壳）：消息段的**块分派**纯函数 —— `groupParts`。
 *
 * R-154 改判后口径（裁决依据 = 用户直裁，非调研建议）：
 * - **组壳彻底拆除**：每个 reasoning / tool 段独立成一个 Block（恒 1 段，不再相邻合并成组）；
 *   原「相邻连续 activity 合成组」「requiresAction 独立成组」特判随之退役（每段天然独立）；
 * - `text` 段一律独立成块（正文永不进组，A5' 规则③）；空正文段不渲染、不成块；
 * - `blockId` 取成员业务稳定 id（toolCallId / `reasoning-${seg}`），禁数组下标（D5），
 *   并加消息命名空间前缀 `${messageId}:`（F1：seg 逐消息自 0 起，跨消息会撞 id 致 expandedBlocks 串味）。
 *
 * 本文件**不依赖 store**（纯函数层，循 utils/parts.ts 分层自述）；展开态三态判定在
 * {@link resolveBlockExpanded}，用户触碰记录存 store 的 `expandedBlocks`（D4：会话内内存态）。
 */
import { GENERATIVE_FORM_TOOL, type MessagePart, type ReasoningPart, type ToolPart } from '@/types/ai'

export type ActivityPart = ToolPart | ReasoningPart

/**
 * 渲染块：text = 单段正文；activity = 单段 reasoning/tool 卡（R-154 起恒 1 段，组壳已拆）。
 *
 * S6 应用批·批1（裁决 2026-10-15 第 3 条「回执卡独立块退役」）：**receipt 块整体删除** ——
 * 已决 HITL（确认门/GF）不再派生第二块，改由**该工具段自己的卡**原地切只读态渲染
 * （卡头恒显行 = 状态徽标 + 人话摘要 + 时间戳 + chevron，见 {@link isReadOnlyCard}）。
 * R-160 兼容条件（摘要/徽标恒可见）由「卡头恒显行」承接：卡体折叠与否都可见。
 */
export interface Block {
  /** 成员稳定 id（加消息命名空间前缀，F1）：`${messageId}:${toolCallId}` / `${messageId}:reasoning-${seg}`（D5） */
  id: string
  kind: 'text' | 'activity'
  /** 恒 1 个元素（R-154 #13+14：每段一卡）；成员 kind ∈ {'reasoning','tool'} */
  parts: MessagePart[]
  /** R-154 重定义：requiresAction 段 true（强制展开）；其余一律 false（每卡默认折叠，单行摘要） */
  defaultExpanded: boolean
}

function stablePartId(part: MessagePart, messageId: string): string {
  const base = part.kind === 'tool' ? part.toolCallId : `${part.kind}-${part.seg}`
  // F1（红队 M-1）：seg 逐消息自 0 起（parts.ts nextSeg），跨消息同 seg 会撞 id ⇒ 全量加消息命名空间前缀
  return `${messageId}:${base}`
}

function isActivityPart(part: MessagePart): part is ActivityPart {
  return part.kind === 'tool' || part.kind === 'reasoning'
}

/**
 * defaultExpanded 判定（R-154 #13+14 重定义，用户直裁「每卡默认折叠（单行摘要）」）：
 * 唯 requiresAction === true ⇒ true（强制展开，R4-5 + D-C6，手动收起不生效）；
 * 其余（含 reason 非空、pending/running 进行中、纯 reasoning 段）一律 false。
 * 原 D6 四档短路判定序随组壳整体退役 —— 卡面折叠单行已带状态 tag（toolStatusLabel），
 * 进行中/失败态在折叠行即可辨识，详情点击展开。
 */
export function defaultExpandedOf(parts: ActivityPart[]): boolean {
  return parts.some((part) => part.kind === 'tool' && part.requiresAction)
}

/**
 * 展开态三态判定（D-C6）：manual = store.expandedBlocks[block.id]。
 * true = 显式展开 / false = 显式收起 / undefined（键缺省）= 未触碰 —— 自动逻辑只作用于未触碰卡。
 * 例外：requiresAction 卡强制展开优先级最高（用户手动收起不生效）。
 */
export function resolveBlockExpanded(block: Block, manual: boolean | undefined): boolean {
  if (block.parts.some((part) => part.kind === 'tool' && part.requiresAction)) {
    return true
  }
  if (manual !== undefined) {
    return manual
  }
  return block.defaultExpanded
}

/**
 * 段序列 → 渲染块序列（纯函数，不改段）。
 * R-154 #13+14：每个 activity 段独立成块（恒 1 段）；DEV 下断言 activity 成员 kind ∈ {'reasoning','tool'}。
 * S6 批1：已决 HITL 段的派生 receipt 块删除（只读态由工具段自己的卡承担）。
 */
export function groupParts(parts: MessagePart[], messageId: string): Block[] {
  const blocks: Block[] = []

  for (const part of parts) {
    if (part.kind === 'text') {
      if (!part.text) {
        // 空正文段不渲染（模板 v-if="part.text"），不成块
        continue
      }
      blocks.push({ id: stablePartId(part, messageId), kind: 'text', parts: [part], defaultExpanded: true })
      continue
    }
    if (!isActivityPart(part)) {
      continue
    }
    // R-154：每段一卡 —— requiresAction 段不再需要"独立成组"特判（天然独立）
    blocks.push({
      id: stablePartId(part, messageId),
      kind: 'activity',
      parts: [part],
      defaultExpanded: defaultExpandedOf([part])
    })
  }
  return blocks
}

/* ============================ S6 批1：卡体行数与只读态判据 ============================
 * 判据一律取「渲染后的内容行数」（v7.10 ajv11 / 场景 19-e 同一句话）：
 *   ≥ 1 行 ⇒ 给 chevron + 卡头可点；0 行 ⇒ 不渲 chevron、卡头 disabled、无 hover、点击 no-op。
 * 两种「空」都算空：`args` 的 `'{}'`（空对象）与 `''` / 全空白；`result` / `reason` 的空白串同理。
 * 全部为纯函数（无 store 依赖），供 AiPanel 模板与测试同源取用。
 * ================================================================================== */

/** 单行字段（label-value 只读表 / 参数表）：key = 字段名或人话标签，value = 可读化后的值。 */
export interface FieldRow {
  key: string
  value: string
}

/** 发布冲突明细行（N8 表格卡列：配置 / 行键 / 原因）。 */
export interface ConflictRow {
  defCode: string
  rowKey: string
  message: string
}

/** 空文本判据：undefined / 空串 / 全空白一律为空（`'{}'` 之外的第二类空兜底）。 */
export function isBlankText(text: string | undefined | null): boolean {
  return text === undefined || text === null || text.trim() === ''
}

/** 敏感键名（门卡参数掩码）：password / passwd / secret / token / apiKey / api_key 等。 */
const SENSITIVE_KEY_RE = /pass(word)?|secret|token|api_?key/i

/** 敏感值掩码字形（不做长度泄露 —— 定长 6 个圆点）。 */
export const MASKED_VALUE = '••••••'

/**
 * 值可读化（v7.10 规则 5 / 场景 19-f）：
 * 布尔 → 是 / 否；数组 → 顿号列表（空数组 →（空））；空值 →（空）；
 * 文件路径（绝对路径 + 带扩展名）→ 文件名；对象 →（复杂值）（不把 JSON 泄进卡面）。
 */
export function readableValue(value: unknown): string {
  if (typeof value === 'boolean') {
    return value ? '是' : '否'
  }
  if (Array.isArray(value)) {
    return value.length > 0 ? value.map((item) => readableValue(item)).join('、') : '（空）'
  }
  if (value === null || value === undefined || value === '') {
    return '（空）'
  }
  if (typeof value === 'number') {
    return String(value)
  }
  if (typeof value === 'string') {
    return fileBasename(value)
  }
  // 对象 / 其它：只报"有值"，不把 JSON 泄进卡面
  return '（复杂值）'
}

/** 绝对路径（含 Windows 盘符）且带扩展名 ⇒ 取文件名；其余字符串原样（v7.10 19-f「路径 → 文件名」）。 */
function fileBasename(value: string): string {
  if (!/^([A-Za-z]:)?[\\/].*\.[A-Za-z0-9]{1,8}$/.test(value)) {
    return value
  }
  const parts = value.split(/[\\/]/)
  return parts[parts.length - 1] ?? value
}

/** 解析 args JSON：仅接受对象形态；空对象 / 非 JSON / 历史脏数据 ⇒ 空数组（不渲 raw 文本）。 */
function parseArgObject(args: string | null | undefined): Record<string, unknown> | null {
  if (isBlankText(args)) {
    return null
  }
  try {
    const parsed = JSON.parse(args as string) as unknown
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return null
    }
    const keys = Object.keys(parsed as Record<string, unknown>)
    return keys.length === 0 ? null : (parsed as Record<string, unknown>)
  } catch {
    return null
  }
}

/**
 * 工具参数行（普通工具卡折叠区字段表 / 门卡字段表共用）。
 * `maskSensitive` = 门卡口径：键名命中敏感词的值显示定长圆点（v7.10 门卡全键）。
 */
export function argRows(args: string | null | undefined, maskSensitive = false): FieldRow[] {
  const parsed = parseArgObject(args)
  if (parsed === null) {
    return []
  }
  return Object.entries(parsed).map(([key, value]) => ({
    key,
    value: maskSensitive && SENSITIVE_KEY_RE.test(key) ? MASKED_VALUE : readableValue(value)
  }))
}

/** GF 表单字段（宽松提取：只认 key / label 两个非空字符串 + options 的中文标签）。 */
export interface FormFieldLite {
  key: string
  label: string
  type?: string
  options?: Array<{ value: string; label?: string }>
}

/**
 * GF 字段表（宽松口径，与 RC-1 的 `formFields` 同源）：`args.form.fields[]` 的 `key / label`
 * 保持 schema 序；类型与 options 取到就用（枚举中文标签），取不到不阻断 —— **不重复 schema 强度校验**
 * （那属 FormRenderer / 后端闸门的职责，回执摘要不能因脏字段整列失效）。
 */
export function formFieldsOf(args: string | null | undefined): FormFieldLite[] {
  const parsed = parseArgObject(args)
  const raw = parsed === null ? undefined : (parsed as { form?: { fields?: unknown } }).form?.fields
  if (!Array.isArray(raw)) {
    return []
  }
  const fields: FormFieldLite[] = []
  for (const item of raw) {
    if (!item || typeof item !== 'object') {
      continue
    }
    const key = (item as { key?: unknown }).key
    const label = (item as { label?: unknown }).label
    if (typeof key !== 'string' || key === '' || typeof label !== 'string' || label === '') {
      continue
    }
    const field: FormFieldLite = { key, label }
    const type = (item as { type?: unknown }).type
    if (typeof type === 'string') {
      field.type = type
    }
    const options = (item as { options?: unknown }).options
    if (Array.isArray(options)) {
      const parsedOptions: Array<{ value: string; label?: string }> = []
      for (const option of options) {
        if (option && typeof option === 'object' && typeof (option as { value?: unknown }).value === 'string') {
          const value = (option as { value: string }).value
          const optionLabel = (option as { label?: unknown }).label
          parsedOptions.push(typeof optionLabel === 'string' ? { value, label: optionLabel } : { value })
        }
      }
      field.options = parsedOptions
    }
    fields.push(field)
  }
  return fields
}

/** GF 已提交值的行（label 取 `args.form.fields[].label`，值可读化；枚举走 options 的中文标签）。 */
export function submitRows(part: ToolPart): FieldRow[] {
  const values = part.submittedValues
  if (!values || Object.keys(values).length === 0) {
    return []
  }
  const fields = formFieldsOf(part.args)
  const byKey = new Map(fields.map((field) => [field.key, field]))
  const rows: FieldRow[] = []
  const seen = new Set<string>()
  for (const field of fields) {
    if (Object.prototype.hasOwnProperty.call(values, field.key)) {
      rows.push({ key: field.label, value: optionLabel(field, values[field.key]) })
      seen.add(field.key)
    }
  }
  for (const [key, value] of Object.entries(values)) {
    if (!seen.has(key)) {
      rows.push({ key: byKey.get(key)?.label ?? key, value: readableValue(value) })
    }
  }
  return rows
}

/**
 * 枚举 / 多选的值可读化：`options[].value → label`（v7.10 19-f「枚举 → 中文标签」）；
 * 无匹配 label（值不在选项内 / 非枚举字段 / 缺 type）回落通用可读化。
 */
function optionLabel(field: FormFieldLite, value: unknown): string {
  if (field.type !== 'enum' && field.type !== 'multi_select') {
    return readableValue(value)
  }
  const labels = new Map((field.options ?? []).map((option) => [option.value, option.label ?? option.value]))
  const pick = (item: unknown): string =>
    typeof item === 'string' && labels.has(item) ? (labels.get(item) as string) : readableValue(item)
  if (Array.isArray(value)) {
    return value.length > 0 ? value.map(pick).join('、') : '（空）'
  }
  return pick(value)
}

/**
 * 已决 HITL 段判据（R-160 M2 判据原样保留，S6 起驱动**原卡只读态**而非独立回执块）：
 * 确认门/GF 的**已决**段（requiresAction=false）。kind2 口径：CONFIRM 按 kind2 判；
 * name 兜底**仅** GENERATIVE_FORM_TOOL（旧数据 kind2=UNKNOWN 时 GF 仍认名，其余工具不认名）。
 */
export function isReadOnlyCard(part: ToolPart): boolean {
  return !part.requiresAction
    && (part.kind2 === 'CONFIRM' || part.name === GENERATIVE_FORM_TOOL)
}

/** 原卡只读态展开体（只读 label-value 表）：CONFIRM 渲 args；GF 渲 submittedValues。 */
export function readOnlyRows(part: ToolPart): FieldRow[] {
  return part.name === GENERATIVE_FORM_TOOL ? submitRows(part) : argRows(part.args)
}

/**
 * 普通工具卡展开体行数 ≥ 1 判据（v7.10 ajv11 / 场景 19-e）：
 * 参数字段表（requiresAction 段不渲参数区 —— 门卡字段表 / GF 表单本身就是待填内容）
 * ＋ 输出 ＋ 原因，三者任一非空即有行；requiresAction 待动作卡例外（恒 true，强制展开）。
 */
export function hasToolBody(part: ToolPart): boolean {
  if (part.requiresAction) {
    return true
  }
  if (argRows(part.args).length > 0) {
    return true
  }
  if (!isBlankText(part.result)) {
    return true
  }
  return !isBlankText(part.reason)
}

/** 原卡只读态展开体行数 ≥ 1 判据（19-e：0 行不给 chevron、整卡不可点）。 */
export function hasReadOnlyBody(part: ToolPart): boolean {
  return readOnlyRows(part).length > 0
}

/**
 * 卡头行数判据（模板唯一入口）：已决 HITL 走只读表判据；普通工具卡走展开体判据。
 * 卡头 `disabled` / chevron / hover 三者同源本判据（0 行 ⇒ 三者全无）。
 */
export function hasBodyRows(part: ToolPart): boolean {
  return isReadOnlyCard(part) ? hasReadOnlyBody(part) : hasToolBody(part)
}

/**
 * 发布冲突明细解析（N8）：把写回段的 reason 文本拆成
 *   ① headline = 首行（人话摘要，卡面 reason 行只显这一句）；
 *   ② rows = 编号明细行 `N. defCode#rowKey：message` → 表格数据（配置 / 行键 / 原因）。
 * 非冲突失败（普通错误文案）⇒ rows 为空，reason 原样渲染。
 */
export function parsePublishConflicts(reason: string | null | undefined): ConflictRow[] {
  if (isBlankText(reason)) {
    return []
  }
  const rows: ConflictRow[] = []
  for (const line of (reason as string).split('\n')) {
    const match = /^\s*\d+\.\s*(.+?)#(.+?)[：:](.+)$/.exec(line)
    if (match) {
      rows.push({ defCode: match[1].trim(), rowKey: match[2].trim(), message: match[3].trim() })
    }
  }
  return rows
}

/** reason 首行（人话摘要）：明细回落表格后，卡面红底行只留这一句。 */
export function reasonHeadline(reason: string | null | undefined): string {
  if (isBlankText(reason)) {
    return ''
  }
  const [head] = (reason as string).split('\n')
  return (head ?? '').trim()
}
