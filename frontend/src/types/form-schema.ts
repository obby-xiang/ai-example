/**
 * 生成式表单 schema（GF-B / DC-15 `generative_form` 前端形态）。
 *
 * 契约来源：docs/evidence/GFa-生成式表单后端工具验证.md §2（白名单）与 §8（对接契约），
 * GF 优化批（G-A/G-C/G-D/G-E/G-F）在此之上补齐：结构化错误、校验最小集、联动五原语、
 * attachment 字段与 longText 变体。
 *
 * 后端 `GenerativeFormGuard` 已用闸门锁死合法 schema —— 前端这里只做**防御性兜底**：
 * parse 失败即视为"渲染器不可用"，由 store 带 `cancelled:true` 回灌收敛（GFa 裁决 #3）。
 * 但**取值约束**（纯空格算空 / 200 字硬顶 / longText 2000 / 字段≤20 / 选项≤50 / 命名格式 /
 * number·date·multi_select 的边界）与后端**同源同判据**：前端先校验，避免"前端放行、后端 400"。
 *
 * 安全边界：文案（title/label/placeholder/选项文本）一律文本插值渲染，永不 v-html；
 * 联动条件只做**数据比较**（固定 op 闭集），**永不执行表达式**；`format` 只接受命名枚举，
 * **不接受模型自带正则**（ReDoS 面不开放）。
 */

/** 字段类型白名单：GF 优化批后为七型（新增 attachment）。 */
export type FormFieldType = 'text' | 'number' | 'boolean' | 'date' | 'enum' | 'multi_select' | 'attachment'

/** 联动条件 op 闭集（与后端 `GenerativeFormRules.CONDITION_OPS` 同一份）。 */
export type FormConditionOp = 'eq' | 'in' | 'notIn' | 'empty' | 'notEmpty' | 'between'

/** 联动条件：始终是对象 + 固定 op，**不是表达式**。 */
export interface FormCondition {
  key: string
  op: FormConditionOp
  value?: unknown
  values?: unknown[]
}

/** 选项形态：只能是 `{value, label?}` 对象，不接受裸字符串（GFa 裁决 #4）。 */
export interface FormFieldOption {
  value: string
  label?: string
}

/** 选项级联：静态映射或后端注册数据源（`sourceId` 闭集，模型给 URL/脚本一律进不来）。 */
export interface FormOptionsFrom {
  dependsOn: string
  map?: Record<string, FormFieldOption[]>
  sourceId?: string
}

/** 默认值静态映射（联动原语⑤）。 */
export interface FormDefaultFrom {
  dependsOn: string
  map: Record<string, unknown>
}

/** 字段级白名单属性（与后端 `FIELD_ATTRIBUTES` 逐项对应）。 */
export interface FormFieldSpec {
  key: string
  label: string
  type: FormFieldType
  /** 静态必填（parse 后恒有值；字面量夹具可省略） */
  required?: boolean
  defaultValue?: unknown
  options?: FormFieldOption[]
  placeholder?: string
  variant?: 'longText'
  minLength?: number
  maxLength?: number
  format?: TextFormat
  min?: number | string
  max?: number | string
  step?: number
  integer?: boolean
  minItems?: number
  maxItems?: number
  visibleWhen?: FormCondition
  requiredWhen?: FormCondition
  disabledWhen?: FormCondition
  optionsFrom?: FormOptionsFrom
  defaultFrom?: FormDefaultFrom
  /**
   * 防御性解析标记：该字段的联动声明不可用（引用未声明字段 / 非法 op / 非对象形态）。
   * 处置口径 = **fail-closed**：字段按不可见处理（宁可少渲染，也不猜模型想表达什么）。
   * 正常路径不会出现（后端 schema 闸门会直接拒绝这类表单）。
   */
  linkageInvalid?: true
}

/** 表单级白名单属性（GFa §2.1：scenario 必填、fields 必填 1..20、title 可选）。 */
export interface FormSpec {
  scenario: string
  title?: string
  fields: FormFieldSpec[]
}

/** 命名格式枚举（禁模型自带正则；与后端 `TEXT_FORMATS` 同源）。 */
export const TEXT_FORMATS = ['email', 'tel', 'url', 'idcard'] as const
export type TextFormat = (typeof TEXT_FORMATS)[number]

export const CONDITION_OPS: readonly FormConditionOp[] = ['eq', 'in', 'notIn', 'empty', 'notEmpty', 'between']

/** 上限常量（与后端同名同值：前端先校验的上限来源）。 */
export const MAX_FORM_FIELDS = 20
export const MAX_FORM_OPTIONS = 50
export const MAX_TEXT_VALUE_CHARS = 200
export const MAX_LONGTEXT_VALUE_CHARS = 2000
/** 字段键形态（与后端 KEY_PATTERN 同口径）。 */
const KEY_PATTERN = /^[A-Za-z_][A-Za-z0-9_]{0,39}$/
const ATTACHMENT_ID_PATTERN = /^att_[A-Za-z0-9_-]{8,64}$/

const FIELD_TYPES: readonly FormFieldType[] = [
  'text',
  'number',
  'boolean',
  'date',
  'enum',
  'multi_select',
  'attachment'
]

/** 原型键黑名单（红队问题 3）：三者都能通过键名正则，赋到 `{}` 上会污染原型或无法取值；后端已同步拒绝（GenerativeFormRules.FORBIDDEN_FIELD_KEYS）。 */
const FORBIDDEN_FIELD_KEYS = new Set(['__proto__', 'constructor', 'prototype'])

/** 结构化取值问题（G-A）：key 归属字段、code 机器可读、message 人读兜底（渲染层优先按 code 查 i18n）。 */
export interface FormValueIssue {
  key: string
  code: string
  message: string
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}

function asString(value: unknown): string | null {
  return typeof value === 'string' ? value : null
}

function asFiniteNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function asInt(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isInteger(value) ? value : undefined
}

function parseOptions(raw: unknown): FormFieldOption[] | null {
  if (!Array.isArray(raw) || raw.length === 0 || raw.length > MAX_FORM_OPTIONS) {
    return null
  }
  const values = new Set<string>()
  const options: FormFieldOption[] = []
  for (const item of raw) {
    if (!isRecord(item) || typeof item.value !== 'string' || item.value === '') {
      return null
    }
    if (values.has(item.value)) {
      return null
    }
    values.add(item.value)
    options.push(typeof item.label === 'string' ? { value: item.value, label: item.label } : { value: item.value })
  }
  return options
}

function parseCondition(raw: unknown): FormCondition | null | undefined {
  if (raw === undefined) {
    return undefined
  }
  if (!isRecord(raw)) {
    return null
  }
  const key = asString(raw.key)
  const op = asString(raw.op) as FormConditionOp | null
  if (!key || !op || !(CONDITION_OPS as readonly string[]).includes(op)) {
    return null
  }
  if (op === 'eq') {
    if (!('value' in raw)) {
      return null
    }
    return { key, op, value: raw.value }
  }
  if (op === 'in' || op === 'notIn' || op === 'between') {
    if (!Array.isArray(raw.values) || raw.values.length === 0) {
      return null
    }
    if (op === 'between' && raw.values.length !== 2) {
      return null
    }
    return { key, op, values: raw.values }
  }
  return { key, op }
}

function parseOptionsFrom(raw: unknown): FormOptionsFrom | null | undefined {
  if (raw === undefined) {
    return undefined
  }
  if (!isRecord(raw)) {
    return null
  }
  const dependsOn = asString(raw.dependsOn)
  if (!dependsOn) {
    return null
  }
  if (raw.map !== undefined) {
    if (!isRecord(raw.map)) {
      return null
    }
    const map: Record<string, FormFieldOption[]> = {}
    for (const [parentValue, list] of Object.entries(raw.map)) {
      const options = parseOptions(list)
      if (options === null) {
        return null
      }
      map[parentValue] = options
    }
    return { dependsOn, map }
  }
  const sourceId = asString(raw.sourceId)
  if (!sourceId) {
    return null
  }
  return { dependsOn, sourceId }
}

function parseDefaultFrom(raw: unknown): FormDefaultFrom | null | undefined {
  if (raw === undefined) {
    return undefined
  }
  if (!isRecord(raw) || !isRecord(raw.map)) {
    return null
  }
  const dependsOn = asString(raw.dependsOn)
  if (!dependsOn) {
    return null
  }
  const map: Record<string, unknown> = {}
  for (const [parentValue, value] of Object.entries(raw.map)) {
    map[parentValue] = value
  }
  return { dependsOn, map }
}

function parseField(raw: unknown): FormFieldSpec | null {
  if (!isRecord(raw)) {
    return null
  }
  if (typeof raw.key !== 'string' || raw.key === '' || FORBIDDEN_FIELD_KEYS.has(raw.key)
    || typeof raw.label !== 'string' || raw.label === '') {
    return null
  }
  if (typeof raw.type !== 'string' || !(FIELD_TYPES as readonly string[]).includes(raw.type)) {
    return null
  }
  const type = raw.type as FormFieldType
  const field: FormFieldSpec = {
    key: raw.key,
    label: raw.label,
    type,
    required: raw.required === true
  }
  if (raw.defaultValue !== undefined && raw.defaultValue !== null) {
    field.defaultValue = raw.defaultValue
  }
  if (raw.placeholder !== undefined && typeof raw.placeholder === 'string') {
    field.placeholder = raw.placeholder
  }
  if (type === 'text' && raw.variant === 'longText') {
    field.variant = 'longText'
  }
  const minLength = asInt(raw.minLength)
  const maxLength = asInt(raw.maxLength)
  if (minLength !== undefined) {
    field.minLength = minLength
  }
  if (maxLength !== undefined) {
    field.maxLength = maxLength
  }
  if (typeof raw.format === 'string' && (TEXT_FORMATS as readonly string[]).includes(raw.format)) {
    field.format = raw.format as TextFormat
  }
  if (typeof raw.min === 'number' || typeof raw.min === 'string') {
    field.min = raw.min
  }
  if (typeof raw.max === 'number' || typeof raw.max === 'string') {
    field.max = raw.max
  }
  const step = asFiniteNumber(raw.step)
  if (step !== null) {
    field.step = step
  }
  if (typeof raw.integer === 'boolean') {
    field.integer = raw.integer
  }
  const minItems = asInt(raw.minItems)
  const maxItems = asInt(raw.maxItems)
  if (minItems !== undefined) {
    field.minItems = minItems
  }
  if (maxItems !== undefined) {
    field.maxItems = maxItems
  }
  if (type === 'enum' || type === 'multi_select') {
    if (raw.options !== undefined) {
      const options = parseOptions(raw.options)
      if (options === null) {
        return null
      }
      field.options = options
    }
    const optionsFrom = parseOptionsFrom(raw.optionsFrom)
    if (optionsFrom === null) {
      return null
    }
    if (optionsFrom) {
      field.optionsFrom = optionsFrom
    }
    if (field.options === undefined && field.optionsFrom === undefined) {
      return null
    }
  }
  const defaultFrom = parseDefaultFrom(raw.defaultFrom)
  if (defaultFrom === null) {
    return null
  }
  if (defaultFrom) {
    field.defaultFrom = defaultFrom
  }
  return field
}

/**
 * 防御性解析 `args.form`：任何不符白名单的形状一律返回 null（调用方走取消收敛）。
 * 只判"能不能安全渲染"，不重复后端闸门的强度校验；但**计数与上限**与后端同源
 * （字段 ≤20、选项 ≤50）—— 这些是前端先校验的硬边界。
 */
export function parseFormSchema(raw: unknown): FormSpec | null {
  if (!isRecord(raw)) {
    return null
  }
  if (typeof raw.scenario !== 'string' || raw.scenario === '') {
    return null
  }
  if (raw.title !== undefined && typeof raw.title !== 'string') {
    return null
  }
  if (!Array.isArray(raw.fields) || raw.fields.length === 0 || raw.fields.length > MAX_FORM_FIELDS) {
    return null
  }
  const keys = new Set<string>()
  const fields: FormFieldSpec[] = []
  for (const item of raw.fields) {
    const field = parseField(item)
    if (field === null || keys.has(field.key) || !KEY_PATTERN.test(field.key)) {
      return null
    }
    keys.add(field.key)
    fields.push(field)
  }
  // 联动条件的引用必须落在**已声明字段**上，且不得自引用；任何不可用声明 ⇒ 该字段 fail-closed。
  for (let index = 0; index < fields.length; index++) {
    const field = fields[index] as FormFieldSpec
    const rawField = raw.fields[index] as Record<string, unknown>
    let invalid = false
    for (const attr of ['visibleWhen', 'requiredWhen', 'disabledWhen'] as const) {
      const condition = parseCondition(rawField[attr])
      if (condition === null) {
        invalid = true
        continue
      }
      if (condition === undefined) {
        continue
      }
      if (!keys.has(condition.key) || condition.key === field.key) {
        invalid = true
        continue
      }
      field[attr] = condition
    }
    for (const attr of ['optionsFrom', 'defaultFrom'] as const) {
      const linkage = field[attr]
      if (linkage && (!keys.has(linkage.dependsOn) || linkage.dependsOn === field.key)) {
        invalid = true
      }
    }
    if (invalid) {
      field.linkageInvalid = true
    }
  }
  return {
    scenario: raw.scenario,
    ...(typeof raw.title === 'string' && raw.title !== '' ? { title: raw.title } : {}),
    fields
  }
}

/**
 * 按 schema 建初始值模型：defaultValue 类型相符才采用，否则给类型空值。
 * （boolean 无"空"态，落 false —— 后端 boolean 必填语义即"必须有一个布尔值"。）
 */
export function buildInitialValues(spec: FormSpec): Record<string, unknown> {
  // Object.create(null) 双保险（红队问题 3）：即使未来黑名单漏放，畸形键也改写不了原型
  const model = Object.create(null) as Record<string, unknown>
  for (const field of spec.fields) {
    model[field.key] = initialValueOf(field)
  }
  return model
}

function initialValueOf(field: FormFieldSpec): unknown {
  const candidate = field.defaultValue
  switch (field.type) {
    case 'text':
      return typeof candidate === 'string' ? candidate : ''
    case 'number':
      return typeof candidate === 'number' && Number.isFinite(candidate) ? candidate : undefined
    case 'boolean':
      return typeof candidate === 'boolean' ? candidate : false
    case 'date':
      return typeof candidate === 'string' ? candidate : ''
    // 2A 批（裁决 §1.4 / §3「枚举初值合法默认」）：enum 的初值必须落在合法枚举内 ——
    // defaultValue 缺省 / 非法（不在 options 内）时取**首个选项**，不再回落空串。
    case 'enum':
      if (typeof candidate === 'string' && inOptions(field, candidate)) {
        return candidate
      }
      return field.options?.[0]?.value ?? ''
    case 'multi_select':
      return Array.isArray(candidate)
        ? candidate.filter((item): item is string => typeof item === 'string' && inOptions(field, item))
        : []
    // attachment：值只能由用户上传产生（fileId），模型给的 defaultValue 一律忽略
    case 'attachment':
      return ''
  }
}

function inOptions(field: FormFieldSpec, value: string): boolean {
  return (field.options ?? []).some((option) => option.value === value)
}

const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/

function isFilled(value: unknown): boolean {
  if (value === undefined || value === null || value === '') {
    return false
  }
  if (typeof value === 'string') {
    return value.trim() !== ''
  }
  return !Array.isArray(value) || value.length > 0
}

// ── 联动求值（纯数据比较，永不执行表达式） ────────────────────────────────

function comparable(value: unknown): string | number | boolean | null {
  if (value === undefined || value === null) {
    return null
  }
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return value
  }
  return null
}

function isEmptyValue(value: unknown): boolean {
  if (value === undefined || value === null) {
    return true
  }
  if (typeof value === 'string') {
    return value.trim() === ''
  }
  return Array.isArray(value) && value.length === 0
}

function equalValue(actual: unknown, expected: unknown): boolean {
  const left = comparable(actual)
  const right = comparable(expected)
  if (left === null || right === null) {
    return false
  }
  if (typeof left === 'number' && typeof right === 'number') {
    return left === right
  }
  return String(left) === String(right)
}

/** 条件求值（op 闭集；未知 op 恒 false）。 */
export function evaluateCondition(condition: FormCondition | undefined, values: Record<string, unknown>): boolean {
  if (!condition) {
    return false
  }
  const actual = values[condition.key]
  switch (condition.op) {
    case 'empty':
      return isEmptyValue(actual)
    case 'notEmpty':
      return !isEmptyValue(actual)
    case 'eq':
      return equalValue(actual, condition.value)
    case 'in':
      return (condition.values ?? []).some((item) => equalValue(actual, item))
    case 'notIn':
      return !(condition.values ?? []).some((item) => equalValue(actual, item))
    case 'between': {
      const [low, high] = condition.values ?? []
      if (typeof actual === 'number' && typeof low === 'number' && typeof high === 'number') {
        return actual >= low && actual <= high
      }
      const text = comparable(actual)
      if (text === null) {
        return false
      }
      return String(text) >= String(low ?? '') && String(text) <= String(high ?? '')
    }
    default:
      return false
  }
}

/** 显隐联动：联动声明不可用（fail-closed）或有 visibleWhen 未成立 ⇒ 不渲染。 */
export function isFieldVisible(field: FormFieldSpec, values: Record<string, unknown>): boolean {
  if (field.linkageInvalid === true) {
    return false
  }
  return field.visibleWhen === undefined || evaluateCondition(field.visibleWhen, values)
}

/** 必填联动：静态 required 或 requiredWhen 成立。 */
export function isFieldRequired(field: FormFieldSpec, values: Record<string, unknown>): boolean {
  return field.required === true || evaluateCondition(field.requiredWhen, values)
}

/** 禁用联动：disabledWhen 成立 ⇒ 只读（值保留、不参与修改）。 */
export function isFieldDisabled(field: FormFieldSpec, values: Record<string, unknown>): boolean {
  return evaluateCondition(field.disabledWhen, values)
}

/**
 * 解析字段当前可见的选项：静态 options → optionsFrom.map 的父值分支 → 外部数据源结果。
 * `external` 按**字段 key** 提供（组件取到 sourceId 的分支后回填；数据源闭集由后端把关）。
 *
 * 解析不出选项时返回**空数组**（渲染口径：控件只剩占位项）。
 */
export function resolveFieldOptions(
  field: FormFieldSpec,
  values: Record<string, unknown>,
  external?: Record<string, FormFieldOption[]>
): FormFieldOption[] {
  return resolvableOptions(field, values, external) ?? []
}

/**
 * 与 {@link resolveFieldOptions} 同源，但**区分"空集"与"无法枚举"**：
 * - 能枚举的选项源（静态 options / optionsFrom.map）解析不出选项时返回空数组 ——
 *   级联语义：父值没选、或父值没有对应分支，子选项就不存在，提交任何值都是"不在选项内"；
 * - 规则层无法枚举的选项源（optionsFrom.sourceId，选项在后端数据源里、还没取到）返回 null ——
 *   调用方据此**跳过**成员校验（与后端 `GenerativeFormRules.runtimeOptionValues` 同口径）。
 *
 * 两者混用会让"空集"被当成"不用校验"，级联子值就能任意通过。
 */
function resolvableOptions(
  field: FormFieldSpec,
  values: Record<string, unknown>,
  external?: Record<string, FormFieldOption[]>
): FormFieldOption[] | null {
  if (field.options) {
    return field.options
  }
  const resolved = external?.[field.key]
  if (resolved && resolved.length > 0) {
    return resolved
  }
  if (field.optionsFrom?.sourceId) {
    return null
  }
  const from = field.optionsFrom
  if (!from?.map) {
    return null
  }
  const parentValue = values[from.dependsOn]
  if (parentValue === undefined || parentValue === null || parentValue === '') {
    return []
  }
  return from.map[String(parentValue)] ?? []
}

/**
 * 默认值联动（原语⑤）：父值变化时套用静态映射。
 * 已"被用户碰过"的字段不覆盖（`touched` 由渲染层维护）；返回新对象（不原地改）。
 */
export function applyDefaultFrom(
  spec: FormSpec,
  values: Record<string, unknown>,
  changedKey: string,
  options: { touched?: ReadonlySet<string> } = {}
): Record<string, unknown> {
  const next = { ...values }
  for (const field of spec.fields) {
    const from = field.defaultFrom
    if (!from || from.dependsOn !== changedKey || options.touched?.has(field.key)) {
      continue
    }
    const parentValue = next[changedKey]
    if (parentValue === undefined || parentValue === null) {
      continue
    }
    const mapped = from.map[String(parentValue)]
    if (mapped !== undefined) {
      next[field.key] = mapped
    }
  }
  return next
}

// ── 级联裁剪（父值变化后清掉不再合法的子值） ──────────────────────────────

/**
 * 级联裁剪（I2）：父值变化后，把依赖它的子字段里"按新的可解析选项已经不合法"的值清掉。
 *
 * <p>为什么必须有：多选的复选框只渲染**当前**选项（`FormRenderer` 的 `optionsOf`），父值一改成
 * 没有对应分支的值，整组复选框就消失了，而模型里仍留着旧值 ⇒ 提交被判
 * `VALUE_OUT_OF_OPTIONS`，用户既无法取消勾选、也无法重交（只能把父值改回去或重开卡片）。
 * enum 靠空白占位项能人工清空，但同一条判据一并裁掉 —— 否则控件显示"未选"、模型仍带旧值，
 * 提交仍被拒（显示与提交两套状态）。
 *
 * <p>口径：
 * - 只碰**选项级联**字段（`optionsFrom.dependsOn`），且只清"不再合法"的值；
 * - enum ⇒ `''`（未选）；multi_select ⇒ 保留仍合法的子集（子集为空即 `[]`）；
 * - 无法枚举的选项源（`optionsFrom.sourceId` 且外部选项还没取到）不动 —— 判不了就不猜；
 * - 值未变就不下探它的下游（它的分支键没变，下游不受影响）；值变了才继续递归（A→B→C）。
 *
 * 纯函数：返回新对象，不原地改；`values` 里应已带上本次父值的新值。
 */
export function pruneCascadeValues(
  spec: FormSpec,
  values: Record<string, unknown>,
  changedKey: string
): Record<string, unknown> {
  const next = { ...values }
  const queue: string[] = [changedKey]
  const visited = new Set<string>([changedKey])
  while (queue.length > 0) {
    const parent = queue.shift() as string
    for (const field of spec.fields) {
      if (field.optionsFrom?.dependsOn !== parent || visited.has(field.key)) {
        continue
      }
      const current = next[field.key]
      const pruned = prunedCascadeValue(field, current, next)
      if (sameValue(pruned, current)) {
        continue
      }
      next[field.key] = pruned
      visited.add(field.key)
      queue.push(field.key)
    }
  }
  return next
}

/** 单个级联字段的值裁剪：不合法 ⇒ 清空；合法或无法判定 ⇒ 原样返回。 */
function prunedCascadeValue(
  field: FormFieldSpec,
  value: unknown,
  values: Record<string, unknown>
): unknown {
  if (field.type !== 'enum' && field.type !== 'multi_select') {
    return value
  }
  if (!isFilled(value)) {
    return value
  }
  const options = resolvableOptions(field, values)
  if (options === null) {
    return value
  }
  const allowed = new Set(options.map((option) => option.value))
  if (field.type === 'enum') {
    return typeof value === 'string' && allowed.has(value) ? value : ''
  }
  if (!Array.isArray(value)) {
    return value
  }
  return value.filter((item): item is string => typeof item === 'string' && allowed.has(item))
}

function sameValue(left: unknown, right: unknown): boolean {
  if (!Array.isArray(left) || !Array.isArray(right)) {
    return left === right
  }
  return left.length === right.length && left.every((item, index) => item === right[index])
}

// ── 取值校验（与后端同源判据） ─────────────────────────────────────────────

const EMAIL_PATTERN = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/
const TEL_PATTERN = /^\+?[0-9][0-9\s\-()]{5,19}$/
const URL_PATTERN = /^https?:\/\/[^\s]+$/
const IDCARD_PATTERN = /^(\d{15}|\d{17}[\dXx])$/

function matchesFormat(format: TextFormat, text: string): boolean {
  switch (format) {
    case 'email':
      return EMAIL_PATTERN.test(text)
    case 'tel':
      return TEL_PATTERN.test(text)
    case 'url':
      return URL_PATTERN.test(text)
    case 'idcard':
      return IDCARD_PATTERN.test(text)
    default:
      return true
  }
}

function textLimitOf(field: FormFieldSpec): number {
  return field.variant === 'longText' ? MAX_LONGTEXT_VALUE_CHARS : MAX_TEXT_VALUE_CHARS
}

function isIsoDate(value: string): boolean {
  return DATE_PATTERN.test(value) && !Number.isNaN(Date.parse(value))
}

function numberOutOfRange(field: FormFieldSpec, value: number): boolean {
  const min = typeof field.min === 'number' ? field.min : undefined
  const max = typeof field.max === 'number' ? field.max : undefined
  return (min !== undefined && value < min) || (max !== undefined && value > max)
}

function dateOutOfRange(field: FormFieldSpec, value: string): boolean {
  const min = typeof field.min === 'string' ? field.min : undefined
  const max = typeof field.max === 'string' ? field.max : undefined
  return (min !== undefined && value < min) || (max !== undefined && value > max)
}

function stepMismatch(field: FormFieldSpec, value: number): boolean {
  if (field.step === undefined || field.step <= 0) {
    return false
  }
  const base = typeof field.min === 'number' ? field.min : 0
  const units = (value - base) / field.step
  return Math.abs(units - Math.round(units)) > 1e-6
}

/**
 * 提交前的结构化复核（防御性兜底，不替代后端闸门）。
 * 返回 {key, code, message} 清单；message 是中文兜底，渲染层优先按 code 取 i18n 文案。
 */
export function checkFormIssues(spec: FormSpec, values: Record<string, unknown>): FormValueIssue[] {
  const issues: FormValueIssue[] = []
  const knownKeys = new Set(spec.fields.map((field) => field.key))
  for (const key of Object.keys(values)) {
    if (!knownKeys.has(key)) {
      issues.push({ key, code: 'RESULT_UNKNOWN_FIELD', message: `存在 schema 之外的字段：${key}` })
    }
  }
  for (const field of spec.fields) {
    if (!isFieldVisible(field, values)) {
      continue
    }
    const value = values[field.key]
    const required = isFieldRequired(field, values)
    if (!isFilled(value)) {
      if (required) {
        issues.push({
          key: field.key,
          code: 'VALUE_REQUIRED_MISSING',
          message: `「${field.label}」为必填项`
        })
      }
      continue
    }
    issues.push(...fieldValueIssues(field, value, values))
  }
  return issues
}

function fieldValueIssues(
  field: FormFieldSpec,
  value: unknown,
  values: Record<string, unknown>
): FormValueIssue[] {
  const issues: FormValueIssue[] = []
  const push = (code: string, message: string): void => {
    issues.push({ key: field.key, code, message })
  }
  switch (field.type) {
    case 'text': {
      if (typeof value !== 'string') {
        push('VALUE_TYPE_MISMATCH', `「${field.label}」应为文本`)
        break
      }
      const limit = textLimitOf(field)
      if (value.length > limit) {
        push('VALUE_TOO_LONG', `「${field.label}」过长：${value.length} 字符（上限 ${limit}）`)
      }
      if (field.minLength !== undefined && value.length < field.minLength) {
        push('VALUE_TOO_SHORT', `「${field.label}」过短：${value.length} 字符（下限 ${field.minLength}）`)
      }
      if (field.maxLength !== undefined && value.length > field.maxLength) {
        push('VALUE_TOO_LONG', `「${field.label}」过长：${value.length} 字符（上限 ${field.maxLength}）`)
      }
      if (field.format && !matchesFormat(field.format, value)) {
        push('VALUE_FORMAT_MISMATCH', `「${field.label}」不符合 ${field.format} 格式`)
      }
      break
    }
    case 'number': {
      if (typeof value !== 'number' || !Number.isFinite(value)) {
        push('VALUE_TYPE_MISMATCH', `「${field.label}」应为数字`)
        break
      }
      if (numberOutOfRange(field, value)) {
        push('VALUE_OUT_OF_RANGE', `「${field.label}」超出范围：${value}`)
      }
      if (stepMismatch(field, value)) {
        push('VALUE_STEP_MISMATCH', `「${field.label}」必须按步长 ${field.step} 取值（实际 ${value}）`)
      }
      if (field.integer === true && !Number.isInteger(value)) {
        push('VALUE_NOT_INTEGER', `「${field.label}」必须是整数（实际 ${value}）`)
      }
      break
    }
    case 'boolean': {
      if (typeof value !== 'boolean') {
        push('VALUE_TYPE_MISMATCH', `「${field.label}」应为布尔值`)
      }
      break
    }
    case 'date': {
      if (typeof value !== 'string' || !isIsoDate(value)) {
        push('VALUE_TYPE_MISMATCH', `「${field.label}」应为 yyyy-MM-dd 格式的合法日期`)
        break
      }
      if (dateOutOfRange(field, value)) {
        push('VALUE_OUT_OF_RANGE', `「${field.label}」超出日期范围：${value}`)
      }
      break
    }
    case 'enum': {
      const options = resolvableOptions(field, values)
      if (typeof value !== 'string') {
        push('VALUE_TYPE_MISMATCH', `「${field.label}」应为单选值`)
      } else if (options !== null && !options.some((option) => option.value === value)) {
        push('VALUE_OUT_OF_OPTIONS', `「${field.label}」的取值不在选项内`)
      }
      break
    }
    case 'multi_select': {
      const options = resolvableOptions(field, values)
      if (!Array.isArray(value) || value.some((item) => typeof item !== 'string')) {
        push('VALUE_TYPE_MISMATCH', `「${field.label}」应为多选值数组`)
        break
      }
      const list = value as string[]
      if (options !== null && list.some((item) => !options.some((option) => option.value === item))) {
        push('VALUE_OUT_OF_OPTIONS', `「${field.label}」的取值应在选项内`)
      }
      if (new Set(list).size !== list.length) {
        push('VALUE_DUPLICATE', `「${field.label}」的取值不得重复`)
      }
      if (field.minItems !== undefined && list.length < field.minItems) {
        push('VALUE_TOO_FEW_ITEMS', `「${field.label}」至少选 ${field.minItems} 项（实际 ${list.length}）`)
      }
      if (field.maxItems !== undefined && list.length > field.maxItems) {
        push('VALUE_TOO_MANY_ITEMS', `「${field.label}」最多选 ${field.maxItems} 项（实际 ${list.length}）`)
      }
      break
    }
    case 'attachment': {
      if (typeof value !== 'string') {
        push('VALUE_TYPE_MISMATCH', `「${field.label}」应为附件 fileId`)
      } else if (!ATTACHMENT_ID_PATTERN.test(value)) {
        push('VALUE_ATTACHMENT_ID_INVALID', `「${field.label}」的值不是有效的附件 fileId`)
      }
      break
    }
  }
  return issues
}

/**
 * 提交前的客户端复核（兼容形态）：返回人读问题清单（空数组 = 通过）。
 * 新代码请用 {@link checkFormIssues}（按 key/code 点亮字段）。
 */
export function checkFormValues(spec: FormSpec, values: Record<string, unknown>): string[] {
  return checkFormIssues(spec, values).map((issue) => issue.message)
}

/**
 * 提交值投影：只保留可见字段（隐藏字段不进回灌，与后端"隐藏不校验"同口径）。
 *
 * <p>L-77（真机 E2E 批② HGTA 域）：**可选字段的空串 = 未填，不提交**。
 * 与本地校验同口径（`checkFormIssues` 对空值只判「必填缺失」、不算格式错），
 * 否则「表单侧认为没填、照常提交」与「后端按类型校验空串」两侧打架：
 * `filter` 场景的可选日期留空被提交成 `""` ⇒ 后端判 `VALUE_TYPE_MISMATCH` 400。
 * 裁定修法 = **前端丢弃空串可选值**（后端不改）。必填字段的空值走本地校验拦截，不会走到这里。
 */
export function visibleValues(spec: FormSpec, values: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const field of spec.fields) {
    if (!isFieldVisible(field, values)) {
      continue
    }
    const value = values[field.key]
    if (value === '' && !isFieldRequired(field, values)) {
      // 可选且未填：不提交该键（"没填"不等于"填了空"）
      continue
    }
    out[field.key] = value
  }
  return out
}

/**
 * 不可重试的错误码（G-A：`code 决定是否可重试`）。
 *
 * <p>这些码表达"这张表已经没救了"：schema 不可用 / 参数形态错 —— 再点提交只会得到同一个拒绝。
 * 其余码（值不合法、类型不符、附件过期/被消费）都意味着"改对后可以重发"，表单保持可编辑。
 */
const NON_RETRYABLE_CODES = new Set([
  'FORM_SCHEMA_REJECTED',
  'RESULT_SCHEMA_UNAVAILABLE',
  'ARG_NOT_OBJECT',
  'ARG_EXTRA_KEY',
  'ARG_MISSING_FORM',
  'FORM_NOT_OBJECT',
  'FORM_FIELDS_MISSING',
  'FORM_FIELDS_NOT_ARRAY',
  'FORM_FIELDS_EMPTY'
])

/** 该错误码是否允许"改对后重试"（未知码按可重试处理：宁可让用户再试，也不无路可走）。 */
export function isRetryableIssue(code: string): boolean {
  return !NON_RETRYABLE_CODES.has(code)
}
