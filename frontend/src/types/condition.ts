/**
 * 查询条件契约（**后端口径**）。
 *
 * 核对源：backend/.../data/service/QueryCondition.java、ConditionEvaluator.java
 *         backend/.../data/controller/DataController.java#list / #count（两处 conditions 入参）
 *         backend/.../job/service/ExportJobRunner.java#parseCondition（读 TaskItem.conditionJson）
 *
 * 形态（**与 types/task.ts 的 QueryCondition 不同**，后者是 a 棒按需求文档先写的
 * groups/conditions 草案；后端实际解析的是本文件的 `scopeKeys` + `fields[]`）：
 * ```json
 * { "scopeKeys": ["XN"], "fields": [{ "fieldCode": "monthlyFee", "operator": "GT", "value": "100" }] }
 * ```
 *
 * S4.4c 变更（补丁棒移交项⑤⑦）：
 * - ⑤ 操作符白名单补 `LIKE`（后端 `ConditionEvaluator.matchesOp` 有该 case，与 CONTAINS 等价）；
 * - ⑦ 列表端点 `GET /api/data/{defCode}` 已补 `conditions` 入参（S4.4 后端 P2），
 *   数据浏览页改走后端口径分页，**客户端镜像求值器已删除**（不再有第二套语义）。
 */

import type { ConfigField, FieldType } from './definition'
import { parseFieldOptions } from './definition'

/**
 * 操作符白名单（ConditionEvaluator.matchesOp 的 case 全量，含 LIKE）。
 *
 * 后端对未知操作符的处理是**白名单拒绝**：`ConditionEvaluator.validate` 在逐行求值之前先校验，
 * 未登记的操作符抛 `IllegalArgumentException`（`matchesOp` 的 default 分支同样抛），端点据此返回 400，
 * 不再"视为通过"（M1 收尾守卫③）。前端类型只收窄登记在册的算子。
 */
export type ConditionOperator =
  | 'EQ'
  | 'NE'
  | 'CONTAINS'
  | 'LIKE'
  | 'STARTS_WITH'
  | 'IN'
  | 'EMPTY'
  | 'NOT_EMPTY'
  | 'GT'
  | 'GTE'
  | 'LT'
  | 'LTE'

/**
 * 前端条件表单的**草案操作符**：比后端多一个 `BETWEEN`（介于）。
 *
 * 后端没有 BETWEEN，展开为同一字段的两条条件（GTE + LTE）——后端求值器逐条 AND，
 * 语义等价于"介于"。详见 {@link buildFieldConditions}。
 *
 * 注意 `BETWEEN` 只存在于**表单草案**层：提交前必被展开，因此
 * `FieldCondition.operator`（落库/传输口径）里永远不会出现它。
 */
export type DraftOperator = ConditionOperator | 'BETWEEN'

/** 单字段条件（后端 QueryCondition.FieldCondition）。 */
export interface FieldCondition {
  fieldCode: string
  operator: ConditionOperator
  /** 单值；IN 时为字符串数组 */
  value?: unknown
}

/** 完整查询条件（后端 QueryCondition）。 */
export interface FieldQueryCondition {
  /** 范围过滤：地区/项目编码列表（GLOBAL 级配置忽略） */
  scopeKeys?: string[]
  /** 字段级过滤（AND 关系） */
  fields?: FieldCondition[]
}

/** 操作符选项（中文标签 + 需要值的形态）。 */
export interface OperatorOption {
  value: DraftOperator
  label: string
  /** 是否需要用户填值（EMPTY/NOT_EMPTY 不需要） */
  needsValue: boolean
  /** 是否双值（BETWEEN） */
  range?: boolean
}

const OP_NEEDS_NO_VALUE: readonly ConditionOperator[] = ['EMPTY', 'NOT_EMPTY']

/** 按字段类型给出可选操作符（类型感知：枚举/布尔用 EQ/NE/IN，数值/日期加区间）。 */
export function operatorsForFieldType(type: FieldType): OperatorOption[] {
  switch (type) {
    case 'NUMBER':
    case 'DATE':
      return [
        { value: 'EQ', label: '等于', needsValue: true },
        { value: 'NE', label: '不等于', needsValue: true },
        { value: 'BETWEEN', label: '介于', needsValue: true, range: true },
        { value: 'GT', label: '大于', needsValue: true },
        { value: 'GTE', label: '大于等于', needsValue: true },
        { value: 'LT', label: '小于', needsValue: true },
        { value: 'LTE', label: '小于等于', needsValue: true }
      ]
    case 'ENUM':
    case 'REFERENCE':
      return [
        { value: 'EQ', label: '等于', needsValue: true },
        { value: 'NE', label: '不等于', needsValue: true },
        { value: 'IN', label: '包含于（多选）', needsValue: true },
        { value: 'CONTAINS', label: '包含文本', needsValue: true }
      ]
    case 'BOOLEAN':
      return [
        { value: 'EQ', label: '等于', needsValue: true },
        { value: 'NE', label: '不等于', needsValue: true }
      ]
    case 'STRING':
    default:
      return [
        { value: 'EQ', label: '等于', needsValue: true },
        { value: 'NE', label: '不等于', needsValue: true },
        { value: 'CONTAINS', label: '包含', needsValue: true },
        { value: 'STARTS_WITH', label: '以…开头', needsValue: true },
        { value: 'EMPTY', label: '为空', needsValue: false },
        { value: 'NOT_EMPTY', label: '不为空', needsValue: false }
      ]
  }
}

/** 该操作符是否要求用户填值。 */
export function operatorNeedsValue(operator: DraftOperator): boolean {
  if (operator === 'BETWEEN') {
    return true
  }
  return !OP_NEEDS_NO_VALUE.includes(operator)
}

/** 表单里一个字段的条件草案。 */
export interface ConditionDraft {
  operator: DraftOperator
  /** 单值（BETWEEN 时为下限） */
  value: string
  /** BETWEEN 的上限 */
  value2: string
}

/** 表单模型：字段编码 → 草案；scopeKeys 为范围（地区/项目编码）多选。 */
export interface ConditionDraftModel {
  scopeKeys: string[]
  fields: Record<string, ConditionDraft>
}

/** 空草案模型。 */
export function emptyDraftModel(scopeKeys: string[] = []): ConditionDraftModel {
  return { scopeKeys: [...scopeKeys], fields: {} }
}

/** 取某字段的草案（不存在则建默认 EQ 草案）。 */
export function draftOf(model: ConditionDraftModel, fieldCode: string): ConditionDraft {
  const current = model.fields[fieldCode]
  if (current) {
    return current
  }
  const created: ConditionDraft = { operator: 'EQ', value: '', value2: '' }
  model.fields[fieldCode] = created
  return created
}

/**
 * 草案 → 后端条件。
 *
 * - EMPTY/NOT_EMPTY 不带值；其余操作符无值即视为"用户未填 → 忽略该字段"；
 * - BETWEEN 展开成 GTE + LTE 两条（同字段多条件，求值器逐条 AND）；
 * - 全部为空时返回 null（调用方据此不提交条件）。
 */
export function buildFieldConditions(model: ConditionDraftModel): FieldCondition[] {
  const result: FieldCondition[] = []
  for (const [fieldCode, draft] of Object.entries(model.fields)) {
    const operator = draft.operator
    if (!operatorNeedsValue(operator)) {
      if (operator === 'EMPTY' || operator === 'NOT_EMPTY') {
        result.push({ fieldCode, operator })
      }
      continue
    }
    if (operator === 'BETWEEN') {
      const from = draft.value.trim()
      const to = draft.value2.trim()
      if (from !== '') {
        result.push({ fieldCode, operator: 'GTE', value: from })
      }
      if (to !== '') {
        result.push({ fieldCode, operator: 'LTE', value: to })
      }
      continue
    }
    const value = draft.value.trim()
    if (value === '') {
      continue
    }
    result.push({ fieldCode, operator, value })
  }
  return result
}

/** 草案模型 → 后端条件对象（无任何有效条件时返回 null）。 */
export function buildQueryCondition(model: ConditionDraftModel): FieldQueryCondition | null {
  const condition: FieldQueryCondition = {}
  const scopeKeys = model.scopeKeys.map((key) => key.trim()).filter((key) => key !== '')
  if (scopeKeys.length > 0) {
    condition.scopeKeys = scopeKeys
  }
  const fields = buildFieldConditions(model)
  if (fields.length > 0) {
    condition.fields = fields
  }
  return condition.scopeKeys || condition.fields ? condition : null
}

/** 条件是否为空（空 = 导出全部）。 */
export function isConditionEmpty(condition: FieldQueryCondition | null | undefined): boolean {
  if (!condition) {
    return true
  }
  return (!condition.scopeKeys || condition.scopeKeys.length === 0)
    && (!condition.fields || condition.fields.length === 0)
}

/** 条件 JSON 字符串（提交给 setCondition / count 的 conditions 入参）。 */
export function stringifyCondition(condition: FieldQueryCondition | null): string {
  return JSON.stringify(condition ?? {})
}

/** 解析 conditionJson（任务条目回读 / 恢复向导用）。 */
export function parseConditionJson(json: string | null | undefined): FieldQueryCondition | null {
  if (!json || !json.trim()) {
    return null
  }
  try {
    const parsed: unknown = JSON.parse(json)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return null
    }
    const candidate = parsed as { scopeKeys?: unknown; fields?: unknown }
    const condition: FieldQueryCondition = {}
    if (Array.isArray(candidate.scopeKeys)) {
      const keys = candidate.scopeKeys.filter((key): key is string => typeof key === 'string')
      if (keys.length > 0) {
        condition.scopeKeys = keys
      }
    }
    if (Array.isArray(candidate.fields)) {
      const fields: FieldCondition[] = []
      for (const raw of candidate.fields) {
        if (!raw || typeof raw !== 'object') {
          continue
        }
        const item = raw as { fieldCode?: unknown; operator?: unknown; value?: unknown }
        if (typeof item.fieldCode !== 'string' || typeof item.operator !== 'string') {
          continue
        }
        fields.push({
          fieldCode: item.fieldCode,
          operator: item.operator as ConditionOperator,
          value: item.value
        })
      }
      if (fields.length > 0) {
        condition.fields = fields
      }
    }
    return isConditionEmpty(condition) ? null : condition
  } catch {
    return null
  }
}

/** 条件 → 中文摘要（列表/详情/工具结果文案）。 */
export function conditionSummary(condition: FieldQueryCondition | null | undefined): string {
  if (isConditionEmpty(condition)) {
    return '全部数据（无条件）'
  }
  const parts: string[] = []
  if (condition?.scopeKeys && condition.scopeKeys.length > 0) {
    parts.push(`范围 = ${condition.scopeKeys.join('、')}`)
  }
  for (const field of condition?.fields ?? []) {
    const value = Array.isArray(field.value) ? field.value.join('、') : field.value === undefined ? '' : String(field.value)
    parts.push(`${field.fieldCode} ${operatorLabel(field.operator)}${value === '' ? '' : ` ${value}`}`)
  }
  return parts.join('；')
}

/** 操作符中文名。 */
export function operatorLabel(operator: ConditionOperator): string {
  const table: Record<ConditionOperator, string> = {
    EQ: '等于',
    NE: '不等于',
    CONTAINS: '包含',
    LIKE: '模糊匹配',
    STARTS_WITH: '以…开头',
    IN: '包含于',
    EMPTY: '为空',
    NOT_EMPTY: '不为空',
    GT: '大于',
    GTE: '大于等于',
    LT: '小于',
    LTE: '小于等于'
  }
  return table[operator] ?? operator
}

// ── 展示辅助 ─────────────────────────────────────────────────────────────────

/** 行数据按定义字段做中文标签展示（数据浏览页表头）。 */
export function displayFieldValue(field: ConfigField, value: unknown): string {
  if (value === null || value === undefined) {
    return ''
  }
  if (field.fieldType === 'ENUM') {
    const options = parseFieldOptions(field)
    const hit = options.find((option) => option.value === String(value))
    return hit ? hit.label : String(value)
  }
  if (field.fieldType === 'BOOLEAN') {
    return String(value).toLowerCase() === 'true' ? '是' : '否'
  }
  return String(value)
}

/** 单个条件值 → 表单文本（数组按 IN 的逗号约定回写）。 */
function draftValue(value: unknown): string {
  if (value === null || value === undefined) {
    return ''
  }
  if (Array.isArray(value)) {
    return value.map((item) => String(item)).join(',')
  }
  return String(value)
}

/**
 * 后端条件 → 表单草案模型（恢复向导 / 详情回显 / AI 回填共用）。
 *
 * 同一个字段上的 GTE+LTE 合并回一个 `BETWEEN` 草案（`buildFieldConditions` 的逆运算），
 * 其余操作符原样保留；空条件返回空模型。
 */
export function draftModelFromCondition(condition: FieldQueryCondition | null | undefined): ConditionDraftModel {
  const model = emptyDraftModel(condition?.scopeKeys ?? [])
  const byField = new Map<string, FieldCondition[]>()
  for (const field of condition?.fields ?? []) {
    const list = byField.get(field.fieldCode) ?? []
    list.push(field)
    byField.set(field.fieldCode, list)
  }
  for (const [fieldCode, list] of byField) {
    const lower = list.find((item) => item.operator === 'GTE')
    const upper = list.find((item) => item.operator === 'LTE')
    if (lower && upper) {
      model.fields[fieldCode] = {
        operator: 'BETWEEN',
        value: draftValue(lower.value),
        value2: draftValue(upper.value)
      }
      continue
    }
    const first = list[0]
    if (!first) {
      continue
    }
    model.fields[fieldCode] = {
      operator: first.operator,
      value: draftValue(first.value),
      value2: ''
    }
  }
  return model
}

