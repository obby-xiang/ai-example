/**
 * 生成式表单 schema（GF-B / DC-15 `generative_form` 前端形态）。
 *
 * 契约来源：docs/evidence/GFa-生成式表单后端工具验证.md §2（白名单）与 §8（对接契约）。
 * 后端 `GenerativeFormGuard` 已用闸门锁死合法 schema —— 前端这里只做**防御性兜底**：
 * parse 失败即视为"渲染器不可用"，由 store 带 `cancelled:true` 回灌收敛（GFa 裁决 #3），
 * 不重复实现后端校验逻辑（不查长度上限、不数选项个数）。
 *
 * 安全边界：文案（title/label/placeholder/选项文本）一律文本插值渲染，永不 v-html
 * （§2.5：内容级校验的豁免前提是前端不做富文本渲染）。
 */

/** 字段类型白名单六型（GFa §2.2）。 */
export type FormFieldType = 'text' | 'number' | 'boolean' | 'date' | 'enum' | 'multi_select'

/** 选项形态：只能是 `{value, label?}` 对象，不接受裸字符串（GFa 裁决 #4）。 */
export interface FormFieldOption {
  value: string
  label?: string
}

/** 字段级白名单属性（GFa §2.2）。 */
export interface FormFieldSpec {
  key: string
  label: string
  type: FormFieldType
  required: boolean
  defaultValue?: unknown
  options?: FormFieldOption[]
  placeholder?: string
}

/** 表单级白名单属性（GFa §2.1：scenario 必填、fields 必填 1..20、title 可选）。 */
export interface FormSpec {
  scenario: string
  title?: string
  fields: FormFieldSpec[]
}

const FIELD_TYPES: readonly FormFieldType[] = ['text', 'number', 'boolean', 'date', 'enum', 'multi_select']

/** 原型键黑名单（红队问题 3）：后端白名单正则不排除它们，赋到 `{}` 上会污染原型或无法取值。 */
const FORBIDDEN_FIELD_KEYS = new Set(['__proto__', 'constructor', 'prototype'])

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}

function parseOptions(raw: unknown): FormFieldOption[] | null {
  if (!Array.isArray(raw) || raw.length === 0) {
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
  if (type === 'enum' || type === 'multi_select') {
    const options = parseOptions(raw.options)
    if (options === null) {
      return null
    }
    field.options = options
  }
  return field
}

/**
 * 防御性解析 `args.form`：任何不符六型白名单的形状一律返回 null（调用方走取消收敛）。
 * 只判"能不能安全渲染"，不重复后端闸门的强度校验。
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
  if (!Array.isArray(raw.fields) || raw.fields.length === 0) {
    return null
  }
  const keys = new Set<string>()
  const fields: FormFieldSpec[] = []
  for (const item of raw.fields) {
    const field = parseField(item)
    if (field === null || keys.has(field.key)) {
      return null
    }
    keys.add(field.key)
    fields.push(field)
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
    case 'enum':
      return typeof candidate === 'string' && inOptions(field, candidate) ? candidate : ''
    case 'multi_select':
      return Array.isArray(candidate)
        ? candidate.filter((item): item is string => typeof item === 'string' && inOptions(field, item))
        : []
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
  return !Array.isArray(value) || value.length > 0
}

/**
 * 提交前的客户端复核（防御性兜底，不替代后端闸门）：required 齐全 + 各型取值形状 +
 * enum/multi_select 值在选项内 + 禁未知字段。返回问题清单（空数组 = 通过）。
 */
export function checkFormValues(spec: FormSpec, values: Record<string, unknown>): string[] {
  const problems: string[] = []
  const knownKeys = new Set(spec.fields.map((field) => field.key))
  for (const key of Object.keys(values)) {
    if (!knownKeys.has(key)) {
      problems.push(`存在 schema 之外的字段：${key}`)
    }
  }
  for (const field of spec.fields) {
    const value = values[field.key]
    if (!isFilled(value)) {
      if (field.required) {
        problems.push(`「${field.label}」为必填项`)
      }
      continue
    }
    switch (field.type) {
      case 'text':
        if (typeof value !== 'string') {
          problems.push(`「${field.label}」应为文本`)
        }
        break
      case 'number':
        if (typeof value !== 'number' || !Number.isFinite(value)) {
          problems.push(`「${field.label}」应为数字`)
        }
        break
      case 'boolean':
        if (typeof value !== 'boolean') {
          problems.push(`「${field.label}」应为布尔值`)
        }
        break
      case 'date':
        if (typeof value !== 'string' || !DATE_PATTERN.test(value) || Number.isNaN(Date.parse(value))) {
          problems.push(`「${field.label}」应为 yyyy-MM-dd 格式的合法日期`)
        }
        break
      case 'enum':
        if (typeof value !== 'string' || !inOptions(field, value)) {
          problems.push(`「${field.label}」的取值不在选项内`)
        }
        break
      case 'multi_select':
        if (!Array.isArray(value)
          || value.some((item) => typeof item !== 'string' || !inOptions(field, item as string))
          || new Set(value).size !== value.length) {
          problems.push(`「${field.label}」的取值应在选项内且不重复`)
        }
        break
    }
  }
  return problems
}
