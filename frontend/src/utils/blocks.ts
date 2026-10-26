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
import { GENERATIVE_FORM_TOOL, type MessagePart, type ReasoningPart, type ToolPart, type ToolRunStatus } from '@/types/ai'
import type { ToolPreflight } from '@/types/sse'
import { parseFormSchema, type FormSpec } from '@/types/form-schema'
import { translate } from '@/composables/useCopy'

export type ActivityPart = ToolPart | ReasoningPart

/**
 * 渲染块：text = 单段正文；activity = 单段 reasoning/tool 卡（R-154 起恒 1 段，组壳已拆）。
 *
 * S6 应用批·批1（裁决 2026-10-15 第 3 条「回执卡独立块退役」）：**receipt 块整体删除** ——
 * 已决 HITL（确认门/GF）不再派生第二块，改由**该工具段自己的卡**原地切只读态渲染。
 *
 * 2A 批（裁决 2026-10-17 §9 K1 + §10 F9 + §15 K12）：只读态 = **原样 UI 只读渲染**（门卡参数区
 * 字段表原样 / GF 同一张表单控件原生 disabled 值回填 / 零操作按钮），卡头 = 左固定类型裸 SVG +
 * 中性标题/摘要 + 右状态文字标签 + chevron（可折叠时）；已决多态（已批准 / 已拒绝 / 已超时 /
 * 已批准·执行失败 / 已批准·未执行 / 未批准·未执行 / 已提交 / 已取消）回合结束后**一律默认折叠**，
 * 卡头单行（标题/摘要/状态标签）
 * 保留可辨识信息。卡面不渲染 decidedAt（§16 M1：后端照记、不上卡面）—— 标题/摘要/状态标签由
 * 卡头恒显行承接 R-160 兼容条件。
 */
export interface Block {
  /** 成员稳定 id（加消息命名空间前缀，F1）：`${messageId}:${toolCallId}` / `${messageId}:reasoning-${seg}`（D5） */
  id: string
  /** 所属消息 id（消息命名空间前缀的原文）——「回合是否仍在进行」判据用（已决终态回合未结束时保持展开）。 */
  messageId: string
  kind: 'text' | 'activity'
  /** 恒 1 个元素（R-154 #13+14：每段一卡）；成员 kind ∈ {'reasoning','tool'} */
  parts: MessagePart[]
  /** requiresAction 段 true（强制展开）；已决 HITL 多态一律折叠（见 {@link readOnlyDefaultExpanded}）；其余 false */
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
 * defaultExpanded 判定（R-154 #13+14 → 2A 批裁决 §15 K12 → r15 直裁）：
 * ① requiresAction === true ⇒ true（强制展开，R4-5 + D-C6，手动收起不生效）；
 * ② 已决 HITL 段（{@link readOnlyDefaultExpanded}）⇒ 多态一律折叠；
 * ③ 其余（含 reason 非空、pending/running 进行中、纯 reasoning 段）一律 false。
 * 决策瞬间「不折叠」的时序不在这里判 —— 那是回合级时序（见 AiPanel 的 turnStillOpen）。
 */
export function defaultExpandedOf(parts: ActivityPart[]): boolean {
  return parts.some((part) => part.kind === 'tool'
    && (part.requiresAction || (isReadOnlyCard(part) && readOnlyDefaultExpanded(part))))
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
      blocks.push({ id: stablePartId(part, messageId), messageId, kind: 'text', parts: [part], defaultExpanded: true })
      continue
    }
    if (!isActivityPart(part)) {
      continue
    }
    // R-154：每段一卡 —— requiresAction 段不再需要"独立成组"特判（天然独立）
    blocks.push({
      id: stablePartId(part, messageId),
      messageId,
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

/** 单行字段（参数字段表 / GF 提交摘要）：key = 字段名或人话标签，value = 可读化后的值。 */
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
    return value ? translate('ai.value.yes') : translate('ai.value.no')
  }
  if (Array.isArray(value)) {
    const separator = translate('ai.value.listSeparator')
    return value.length > 0 ? value.map((item) => readableValue(item)).join(separator) : translate('ai.value.empty')
  }
  if (value === null || value === undefined || value === '') {
    return translate('ai.value.empty')
  }
  if (typeof value === 'number') {
    return String(value)
  }
  if (typeof value === 'string') {
    return fileBasename(value)
  }
  // 对象 / 其它：只报"有值"，不把 JSON 泄进卡面
  return translate('ai.value.complex')
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
    const separator = translate('ai.value.listSeparator')
    return value.length > 0 ? value.map(pick).join(separator) : translate('ai.value.empty')
  }
  return pick(value)
}

/**
 * 已决 HITL 段判据（R-160 M2 判据原样保留，S6 起驱动**原卡只读态**而非独立回执块）：
 * 确认门/GF 的**已决**段（requiresAction=false）。kind2 口径：CONFIRM 按 kind2 判；
 * name 兜底**仅** GENERATIVE_FORM_TOOL（旧数据 kind2=UNKNOWN 时 GF 仍认名，其余工具不认名）。
 *
 * 2A 批（裁决 §9 K2）：`blocked` / `unknown` / `pending` **不是决策态** —— 它们是工具运行侧
 * 的「未形成裁决」（越 scope 拦下 / 结局未知 / 仍在途），继续走普通工具卡（右状态标签承接
 * 「超出当前范围 / 结局未知 / 待确认」），不进审批卡的只读状态机，避免把非决策读成超时。
 * 契约增量：`skipped`（上游失败导致未执行）同属非决策态 —— 必须由普通状态标签显示「已跳过」，
 * 不得被只读状态机折成「已超时 / 已批准」。
 *
 * 2026-10-26 批4 例外（{@link isApprovedNotExecuted}）：**已有 approved 决策**的 blocked /
 * skipped 是「已批准但未执行」，不是普通工具结局；它必须进只读卡，且不能显示成执行失败。
 * 无决策的 blocked / skipped 仍按 2A 口径走普通卡（不把"未批准"冒充成已批准）。
 */
const NON_DECISION_STATUSES: ReadonlySet<ToolRunStatus> = new Set(['pending', 'blocked', 'unknown', 'skipped'])

/**
 * 2026-10-26 批4：**已批准但执行前被挡下**（target 校验 / 上游 skipped）不是普通工具结局，
 * 必须进只读卡显示「已批准 · 未执行」；`blocked` 的无决策段（越 scope 拦下）仍走普通工具卡
 * 「超出当前范围」，不被本条收编。
 */
function isApprovedNotExecuted(part: ToolPart): boolean {
  return part.kind2 === 'CONFIRM'
    && part.decision === 'approved'
    && (part.status === 'blocked' || part.status === 'skipped')
}

export function isReadOnlyCard(part: ToolPart): boolean {
  return !part.requiresAction
    && (part.kind2 === 'CONFIRM' || part.name === GENERATIVE_FORM_TOOL)
    && (!NON_DECISION_STATUSES.has(part.status) || isApprovedNotExecuted(part))
}

/**
 * 已决只读态（2A 批裁决 §9 K2 + §10 F6 + §15 K12 定名；批G · S13 起门卡增至五态；
 * 2026-10-26 批4 增至七态）：
 * - 门卡（H1）七态：已批准 / 已拒绝 / 已超时 / 已批准·执行失败 / **已取消** /
 *   **已批准·未执行** / **未批准·未执行**；
 * - 表单系（GF）两态：已提交 / 已取消。
 *
 * 判定序 = 决策元数据优先、段状态兜底（与 R-160 M2「decision 缺省回退 status」同源）：
 * - decision=timeout ⇒ 已超时（2A 挂账①：超时是**第三种门结局** —— 闸门未开、本次未执行）；
 * - decision=approved：status=failed ⇒ 执行失败（K3：失败是执行结果，不是决策结果）；
 *   status=blocked / skipped ⇒ **已批准·未执行**（批4：批准后执行前被 target 校验/上游挡下）；
 * - decision=rejected ⇒ 已拒绝；
 * - decision=cancel ⇒ 已取消（批G · S13：**用户停止是一次决策**，不是"没人管"，更不是系统超时；
 *   写口见 `cancelRoundParts`（本地停止）/ confirm_decision 帧 / 历史 toolCallStates）；
 * - 无 decision 的历史段：succeeded/running ⇒ 已批准、rejected ⇒ 已拒绝、
 *   **failed / blocked / skipped ⇒ 未批准·未执行**（批4：没有批准证据就不得声称「已批准」——
 *   F5 预检未通过 / 预检不可用就地结算的 `failed` 段即走此支）、其余（expired / cancelled）
 *   ⇒ 已超时。
 *   无决策元数据的 `cancelled` 归入「已超时」是 K2 旧口径的兜底：旧载荷/旧镜像没有 cancel
 *   决策可读，两者同属**未批准收尾**（闸门未开、本次操作未执行）。
 *   `blocked` / `unknown` / `pending` 无决策时仍不属只读卡，已由 {@link isReadOnlyCard} 挡住；
 *   有 approved 决策的 blocked / skipped 例外进只读卡（{@link isApprovedNotExecuted}）。
 */
export type ReadOnlyState = 'approved' | 'rejected' | 'expired' | 'failed' | 'submitted' | 'cancelled' | 'notApproved' | 'notExecuted'

export function readOnlyStateOf(part: ToolPart): ReadOnlyState {
  if (part.name === GENERATIVE_FORM_TOOL) {
    return part.status === 'succeeded' ? 'submitted' : 'cancelled'
  }
  if (part.decision === 'timeout') {
    return 'expired'
  }
  if (part.decision === 'approved') {
    if (part.status === 'failed') {
      return 'failed'
    }
    if (part.status === 'blocked' || part.status === 'skipped') {
      return 'notExecuted'
    }
    return 'approved'
  }
  if (part.decision === 'rejected') {
    return 'rejected'
  }
  if (part.decision === 'cancel') {
    return 'cancelled'
  }
  switch (part.status) {
    case 'succeeded':
    case 'running':
      return 'approved'
    case 'failed':
    case 'blocked':
    case 'skipped':
      // 批4：无批准证据的未执行/失败一律「未批准 · 未执行」—— 预检未通过不得显示「已批准」。
      return 'notApproved'
    case 'rejected':
      return 'rejected'
    default:
      return 'expired'
  }
}

/**
 * 已决只读卡的默认展开态（§15 K12 → r15 直裁）：**多态一律折叠** ——
 * 回合结束后统一收成卡头单行（类型图标 + 中性标题/摘要 + 右状态标签 + chevron），
 * 由用户手动展开回溯。
 * `pending` / `blocked` / `unknown` 与 requiresAction 待决门卡不属已决只读态（{@link isReadOnlyCard}
 * 挡在门外），本函数不承担它们的展开判定。
 * 「回合未结束仍展开」属回合时序，由 AiPanel 的 turnStillOpen 承担（不写进纯函数）。
 */
export function readOnlyDefaultExpanded(_part: ToolPart): boolean {
  return false
}

/* ============================ 批4 / AR-02：确认卡知情摘要三字段 ============================
 * 只展示后端已经算好的 影响面 / 条件摘要 / 权威发布模式；缺哪个就不渲染哪一行：
 * - 条件摘要**不由前端反解** args / 模型文本（那会把"前端猜测"伪装成后端事实）；
 * - 发布模式**不默认 MERGE**、不从模型回复猜；无值 = 未知，整行不出；
 * - 旧预检区的 checks / reportHash / 五段式版式不恢复（10-20 撤除理由仍成立）。
 * 返回 FieldRow 供门卡与只读卡共用；文案 key 经 translate（切语言即重算）。
 * ========================================================================================== */

/** 权威发布模式 → 人读文案：只认后端枚举 MERGE/REPLACE；其余原样展示（不推测、不改写）。 */
function publishModeText(mode: string): string {
  const normalized = mode.trim().toUpperCase()
  if (normalized === 'MERGE') {
    return translate('ai.hitl.facts.mode.merge')
  }
  if (normalized === 'REPLACE') {
    return translate('ai.hitl.facts.mode.replace')
  }
  return mode.trim()
}

/** 确认卡知情摘要行（空值不出行；顺序 = 影响面 → 条件摘要 → 发布模式）。 */
export function gateFactsRows(preflight: ToolPreflight | null | undefined): FieldRow[] {
  if (preflight === null || preflight === undefined) {
    return []
  }
  const rows: FieldRow[] = []
  if (!isBlankText(preflight.impact)) {
    rows.push({ key: translate('ai.hitl.facts.impact'), value: preflight.impact as string })
  }
  if (!isBlankText(preflight.conditionSummary)) {
    rows.push({ key: translate('ai.hitl.facts.condition'), value: preflight.conditionSummary as string })
  }
  if (typeof preflight.publishMode === 'string' && !isBlankText(preflight.publishMode)) {
    rows.push({ key: translate('ai.hitl.facts.publishMode'), value: publishModeText(preflight.publishMode) })
  }
  return rows
}

/* ============================ 卡片三类型统一判据（tool / hitl / form） ============================
 * 卡头通道统一后的单一状态来源：**类型**只认 name / kind2（CONFIRM→hitl、generative_form→form、
 * 其余→tool，没有第四类型「decided」）；**状态**只读既有判据 —— 已决 HITL / GF 走
 * {@link isReadOnlyCard} + {@link readOnlyStateOf}（不另起第二套只读状态机），
 * 其余走段状态 `status`（pending 文案按类型分工）。返回 key / label / tone 三元组：
 * AiPanel 只用 tone 映射右标签色族，标题/摘要/左侧类型 SVG 恒中性色。
 * ================================================================================================ */

export type CardType = 'tool' | 'hitl' | 'form'
/** 右状态标签色族（与既有胶囊/徽标色族一一对应；neutral = 原 warning/info 中性灰族）。 */
export type CardTone = 'primary' | 'success' | 'danger' | 'warning' | 'neutral'

export interface CardStatus {
  /** 段状态 key，或已决只读态 key（approved / rejected / expired / failed / submitted / cancelled） */
  key: ToolRunStatus | ReadOnlyState
  /** 右标签文案（已决 HITL / GF 带既有 ✓ / ✗ / ! 字形前缀） */
  label: string
  /** 状态色族；只作用于右标签 */
  tone: CardTone
}

/** pending 文案按类型分工：普通工具「待执行」、HITL「待确认」（琥珀）、GF「待填写」（i18n L-69：文案走 locale）。 */
const CARD_PENDING_LABEL_KEY: Record<CardType, string> = {
  tool: 'ai.card.status.pending.tool',
  hitl: 'ai.card.status.pending.hitl',
  form: 'ai.card.status.pending.form'
}

/**
 * 待决卡色族：确认卡（hitl）承载"等用户拍板"的唯一状态色 = 琥珀（对齐 card-channels 定稿
 * `#b45309` / `#fffbeb`）；工具/表单待决仍是蓝族（动作已就绪，等执行/填写）。
 */
const CARD_PENDING_TONE: Record<CardType, CardTone> = {
  tool: 'primary',
  hitl: 'warning',
  form: 'primary'
}

/** 普通状态色族（R-165 既有口径保持；pending / running 蓝、成功绿、失败红、其余中性灰）。 */
const TOOL_STATUS_TONE: Record<ToolRunStatus, CardTone> = {
  pending: 'primary',
  running: 'primary',
  succeeded: 'success',
  failed: 'danger',
  rejected: 'neutral',
  blocked: 'neutral',
  expired: 'neutral',
  cancelled: 'neutral',
  unknown: 'neutral',
  skipped: 'neutral'
}

/**
 * 已决只读态文案（2A 批 §9 K2 / §10 F6 原样；批G · S13 起门卡的 `cancelled` 不再被折叠成
 * 「已超时」—— 用户停止是一次决策，文案与 GF 取消同字：`✗ 已取消`）。
 *
 * i18n L-69：这里存 **locale key**，取文案经 {@link translate}（调用点在渲染/computed 内 ⇒ 切语言即重算）。
 */
const READ_ONLY_LABEL_KEY: Record<ReadOnlyState, string> = {
  approved: 'ai.card.readonly.approved',
  rejected: 'ai.card.readonly.rejected',
  expired: 'ai.card.readonly.expired',
  failed: 'ai.card.readonly.failed',
  submitted: 'ai.card.readonly.submitted',
  cancelled: 'ai.card.readonly.cancelled',
  notApproved: 'ai.card.readonly.notApproved',
  notExecuted: 'ai.card.readonly.notExecuted'
}

/**
 * 已决多态色族（与旧 READONLY_BADGE_TONE 同源：通过琥珀、提交/批准绿、拒绝/失败/取消红）。
 * 批4：未批准/已批准未执行都用中性色 —— 它们不是执行失败（不得染红），也不是成功（不得染绿）。
 */
const READ_ONLY_TONE: Record<ReadOnlyState, CardTone> = {
  approved: 'success',
  rejected: 'danger',
  expired: 'warning',
  failed: 'danger',
  submitted: 'success',
  cancelled: 'danger',
  notApproved: 'neutral',
  notExecuted: 'warning'
}

/**
 * 卡片类型（唯一来源）：generative_form 按名优先 ⇒ form；kind2=CONFIRM ⇒ hitl；其余 ⇒ tool。
 * 已决 / 待决不改变类型（不存在「decided」第四类型），左图标因此整生命周期不变。
 */
export function cardTypeOf(part: ToolPart): CardType {
  if (part.name === GENERATIVE_FORM_TOOL) {
    return 'form'
  }
  if (part.kind2 === 'CONFIRM') {
    return 'hitl'
  }
  return 'tool'
}

/**
 * 普通段状态文案（排除 pending —— 已由 {@link cardStatusOf} 按类型分工先判；
 * 只读状态机走 {@link readOnlyStateOf}，不在此重复）。
 *
 * i18n L-69：文案走 `ai.card.status.*`；`blocked` 的 en 由「out of scope」改准为
 * 「超出可展示范围」（zh「超出当前范围」= 工具/结果越出本轮可展示面，不是"不归本功能管"）。
 */
function toolStatusLabelOf(status: Exclude<ToolRunStatus, 'pending'>): string {
  return translate(`ai.card.status.${status}`)
}

/**
 * 卡片状态（key / label / tone）：
 * - 已决 HITL / GF（{@link isReadOnlyCard}）⇒ key / label / tone 全由 {@link readOnlyStateOf} 派生；
 * - 其余 pending ⇒ 文案按 {@link cardTypeOf} 分工；
 * - 其余段状态（running / succeeded / failed / rejected / blocked / expired / cancelled / unknown / skipped）
 *   ⇒ 既有九态文案 + 色族（blocked / unknown / skipped 无批准决策时不落只读状态机；pending 已在前一分支处理）。
 */
export function cardStatusOf(part: ToolPart): CardStatus {
  if (isReadOnlyCard(part)) {
    const state = readOnlyStateOf(part)
    return { key: state, label: translate(READ_ONLY_LABEL_KEY[state]), tone: READ_ONLY_TONE[state] }
  }
  if (part.status === 'pending') {
    const type = cardTypeOf(part)
    return { key: 'pending', label: translate(CARD_PENDING_LABEL_KEY[type]), tone: CARD_PENDING_TONE[type] }
  }
  return {
    key: part.status,
    label: toolStatusLabelOf(part.status),
    tone: TOOL_STATUS_TONE[part.status]
  }
}

/**
 * GF 表单定义（`args.form` 的防御性解析）—— 已决只读态要复用**同一张表单**（K1），
 * 七型控件（GF 优化批新增 attachment）与字段序都取自这里；解析失败返回 null
 * （卡面只留兜底文案，不臆造控件）。
 */
export function formSpecOf(part: ToolPart): FormSpec | null {
  const parsed = parseArgObject(part.args)
  if (parsed === null) {
    return null
  }
  return parseFormSchema((parsed as { form?: unknown }).form)
}

/**
 * 普通工具卡展开体行数 ≥ 1 判据（v7.10 ajv11 / 场景 19-e）：
 * 参数字段表（requiresAction 段不渲参数区 —— 门卡字段表 / GF 表单本身就是待填内容）
 * ＋ 输出 ＋ 原因 ＋ **视图通道**，四者任一非空即有行；requiresAction 待动作卡例外（恒 true，强制展开）。
 *
 * S6 批2 迁移步④：`resultView` 也是卡体内容 —— 只认原文会让"视图能渲但原文为空"的段
 * 连带丢掉展开入口，视图就永远进不去（静默丢失比降级更糟；降级至少有 fallback 文案）。
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
  if (part.resultView != null) {
    return true
  }
  return !isBlankText(part.reason)
}

/**
 * 卡体判据（普通工具卡唯一入口）：渲染后 0 行 ⇒ 不渲展开体 / chevron / 卡头 disabled。
 * 已决 HITL 卡不走本判据 —— 只读态卡体**恒在**（门卡字段表 / GF 同一张表单），卡头恒可折叠。
 */
export function hasBodyRows(part: ToolPart): boolean {
  return hasToolBody(part)
}

/**
 * 发布冲突明细解析（N8）：把写回段的 reason 文本拆成
 *   ① headline = 首行（人话摘要，卡面 reason 行只显这一句）；
 *   ② rows = 编号明细行 `N. defCode#rowKey：message` → 表格数据（配置 / 行键 / 原因）。
 * 非冲突失败（普通错误文案）⇒ rows 为空，reason 原样渲染。
 *
 * **删除闸（设计稿 §5.4 / §7.1-⑤，本批不达）**：本函数 + {@link ConflictRow} + AiPanel 的旧反解表分支
 * 只有在「`start_publish` 的冲突结果 100% 带 `resultView`」且「跨一个发布版本无回退（有视图但渲不出表格的
 * 告警计数为 0）」两条同时成立时才删；旧镜像 / 旧归档里的段永远没有视图，所以这一支是**兜底**而非死码。
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
