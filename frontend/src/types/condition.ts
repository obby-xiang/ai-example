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
 *
 * 批次1A（2026-10-26）：压平不再"空即丢弃、静默变全量"。`buildConditionOutcome` 把
 * **显式清空**（`cleared`，语义不变：无条件 = 全部数据）与 **非法空输入**（`invalid`：
 * 值算子的空串/纯空白、IN 的空取值）分成两种结局；落库 / 起作业的调用方必须据结局阻断，
 * 需要抛错口径时用 `assertConditionBuildable`。
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

/**
 * 表单模型：字段编码 → 草案；scopeKeys 为范围（地区/项目编码）多选。
 *
 * `fields` 每个字段只放得下**一条**草案，因此并非所有后端条件都表达得出来（同字段多条件、
 * IN 取值里含分隔符等）。这类条件下的逆映射必须是无损的：模型把原始条件原样带在
 * {@link ConditionDraftModel.verbatim} 上，`buildConditionOutcome` 直接返回它；落库方据
 * 「是否带 verbatim」判断能否用草案覆盖服务端（不等价禁止覆盖）。
 */
export interface ConditionDraftModel {
  scopeKeys: string[]
  fields: Record<string, ConditionDraft>
  /**
   * 草案无法无损表达时保留的**原始条件**（`draftModelFromCondition` 写入）。
   *
   * 存在即代表：这份模型的重建结果与服务端条件不是一一对应，任何"按草案重建再 PUT"的写入
   * 都会换义（多值 IN 压成逗号串、同字段多条件只剩第一条）。因此：
   * - 重建口径（{@link buildConditionOutcome}）直接返回本字段，保证"往返"等价；
   * - 落库方在模型未被真正改动时不得覆盖服务端；
   * - 用户/AI 改过条件（换了一份新模型）后本字段消失，按新草案落库。
   */
  verbatim?: FieldQueryCondition
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

/** 压平后"丢弃了字段"的原因：值算子的空值，或 IN 的空取值（表单以逗号串承载，空串即空列表）。 */
export type ConditionInputIssueCode = 'EMPTY_VALUE' | 'EMPTY_IN'

/** 压平阶段的非法输入问题（`fieldCode` 定位；保留草案算子，便于文案区分 EQ 空值与 IN 空）。 */
export interface ConditionInputIssue {
  fieldCode: string
  operator: DraftOperator
  code: ConditionInputIssueCode
  /** 草案里填的值（已 trim；IN 时为空串） */
  value: string
}

/**
 * 草案 → 后端条件的三种结局。
 *
 * - `ok`：有可提交条件；
 * - `cleared`：草案里没有任何条件条目 —— **显式清空**，语义仍是"无条件 = 全部数据"（不变）；
 * - `invalid`：有条目但压平后会被丢弃（值空 / IN 空）—— **非法空输入**，绝不可当成"无条件"，
 *   调用方必须在落库与起作业之前阻断。
 */
export type ConditionBuildOutcome =
  | { status: 'ok'; condition: FieldQueryCondition; issues: [] }
  | { status: 'cleared'; condition: null; issues: [] }
  | { status: 'invalid'; condition: null; issues: ConditionInputIssue[] }

/**
 * 以抛出方式阻断非法空输入（fail-closed 调用方用）。
 *
 * 消息只是诊断串（`fieldCode:CODE` 列表），**不是用户文案**：面向用户的成文由
 * `ExportWizardView` 按 `views.export.conditionInput.*` 本地化。本模块不得硬编码中文，
 * 否则每次新增调用方都会把 i18n 残留基线顶上去。
 */
export class InvalidConditionInputError extends Error {
  readonly issues: ConditionInputIssue[]

  constructor(issues: ConditionInputIssue[]) {
    super(`invalid condition input: ${issues.map((issue) => `${issue.fieldCode}:${issue.code}`).join(', ')}`)
    this.name = 'InvalidConditionInputError'
    this.issues = issues
  }
}

/** 逐字段展开；空值 / IN 空不再静默丢弃，而是同时记进 `issues`。 */
function expandFields(model: ConditionDraftModel): {
  conditions: FieldCondition[]
  issues: ConditionInputIssue[]
} {
  const conditions: FieldCondition[] = []
  const issues: ConditionInputIssue[] = []
  for (const [fieldCode, draft] of Object.entries(model.fields)) {
    const operator = draft.operator
    if (!operatorNeedsValue(operator)) {
      if (operator === 'EMPTY' || operator === 'NOT_EMPTY') {
        conditions.push({ fieldCode, operator })
      }
      continue
    }
    if (operator === 'BETWEEN') {
      const from = draft.value.trim()
      const to = draft.value2.trim()
      if (from === '' && to === '') {
        issues.push({ fieldCode, operator, code: 'EMPTY_VALUE', value: '' })
        continue
      }
      if (from !== '') {
        conditions.push({ fieldCode, operator: 'GTE', value: from })
      }
      if (to !== '') {
        conditions.push({ fieldCode, operator: 'LTE', value: to })
      }
      continue
    }
    if (operator === 'IN') {
      // 表单以逗号串承载多选（`ConditionForm` 的 placeholder 同口径），但后端 IN 的口径是
      // **数组**（`ConditionEvaluator.matchesOp` 的 List 分支）：压成单个字符串会退化成
      // "整串等于行值"（几乎必不命中）。这里拆回数组，空列表仍判非法（不得静默变全量）。
      const values = draft.value.split(',').map((item) => item.trim()).filter((item) => item !== '')
      if (values.length === 0) {
        issues.push({ fieldCode, operator, code: 'EMPTY_IN', value: '' })
        continue
      }
      conditions.push({ fieldCode, operator, value: values })
      continue
    }
    const value = draft.value.trim()
    if (value === '') {
      issues.push({ fieldCode, operator, code: 'EMPTY_VALUE', value })
      continue
    }
    conditions.push({ fieldCode, operator, value })
  }
  return { conditions, issues }
}

/**
 * 草案 → 后端条件（`ConditionForm` 的预览面用；**落库 / 起作业请用 {@link buildConditionOutcome}**）。
 *
 * - EMPTY/NOT_EMPTY 不带值；其余操作符无值即视为"用户未填 → 本函数不展开该字段"；
 * - BETWEEN 展开成 GTE + LTE 两条（同字段多条件，求值器逐条 AND）；两端都空则不展开；
 * - 被跳过的空值**不是**"无条件"：可提交性由 {@link buildConditionOutcome} 判定，
 *   不得只凭本函数的返回值决定落库。
 */
export function buildFieldConditions(model: ConditionDraftModel): FieldCondition[] {
  return expandFields(model).conditions
}

/**
 * 草案 → 条件结局：显式清空（`cleared`）与非法空输入（`invalid`）分开。
 *
 * `invalid` 时 `condition` 恒为 null，但**不能**当成"无条件"使用（那正是导出全量的来源）；
 * 调用方必须阻断并成文 `issues`。
 *
 * 模型带 `verbatim`（草案无法无损表达的原始条件）时直接返回它：那才是这份条件的事实，
 * 按有损草案重建出来的东西不是。
 */
export function buildConditionOutcome(model: ConditionDraftModel): ConditionBuildOutcome {
  const verbatim = model.verbatim
  if (verbatim && !isConditionEmpty(verbatim)) {
    return { status: 'ok', condition: verbatim, issues: [] }
  }
  const { conditions, issues } = expandFields(model)
  if (issues.length > 0) {
    return { status: 'invalid', condition: null, issues }
  }
  const condition: FieldQueryCondition = {}
  const scopeKeys = model.scopeKeys.map((key) => key.trim()).filter((key) => key !== '')
  if (scopeKeys.length > 0) {
    condition.scopeKeys = scopeKeys
  }
  if (conditions.length > 0) {
    condition.fields = conditions
  }
  return condition.scopeKeys || condition.fields
    ? { status: 'ok', condition, issues: [] }
    : { status: 'cleared', condition: null, issues: [] }
}

/**
 * 兼容入口：`invalid` 与 `cleared` 都返回 null。
 *
 * **不得**把 null 直接当"无条件 = 全部数据"：要区分"非法空输入"和"显式清空"必须看
 * {@link buildConditionOutcome}（或走 {@link assertConditionBuildable} 的抛错口径）。
 */
export function buildQueryCondition(model: ConditionDraftModel): FieldQueryCondition | null {
  return buildConditionOutcome(model).condition
}

/**
 * fail-closed 入口：非法空输入抛 {@link InvalidConditionInputError}，显式清空返回 null。
 *
 * 用于"条件一旦丢了就会静默变全量"的提交点（条件落库、起作业）；纯预览可继续用
 * {@link buildQueryCondition} + {@link buildConditionOutcome}。
 */
export function assertConditionBuildable(model: ConditionDraftModel): FieldQueryCondition | null {
  const outcome = buildConditionOutcome(model)
  if (outcome.status === 'invalid') {
    throw new InvalidConditionInputError(outcome.issues)
  }
  return outcome.condition
}

/** 条件草案的本地校验问题（`ConditionForm` 的 `role=alert` 面据此成文）。 */
export type ConditionIssueCode = 'NUMBER' | 'DATE' | 'RANGE'

export interface ConditionIssue {
  fieldCode: string
  code: ConditionIssueCode
  /** 触发问题的值（BETWEEN 时为下限） */
  value: string
  /** BETWEEN 的上限（其余为空串） */
  value2: string
}

/** 数字文本判定（空串不算数字；与后端解析同口径的常见形态）。 */
function isNumberText(text: string): boolean {
  return text !== '' && Number.isFinite(Number(text))
}

/** 日期文本判定（表单的日期控件固定 `YYYY-MM-DD`）。 */
function isDateText(text: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(text)) {
    return false
  }
  const date = new Date(`${text}T00:00:00Z`)
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === text
}

/**
 * 条件草案的本地校验（**提交前**的判定点，页面侧 `role=alert` 的数据源）。
 *
 * 只覆盖后端会硬拒的三类：数值字段填非数字、日期字段格式不对、区间上下限倒置。
 * 空值不在这里报类型错 —— 值空 / IN 空由 {@link buildConditionOutcome} 判为 `invalid`
 * 并在提交前阻断（不是"未填即忽略"），本函数只报字段类型与区间问题。
 * 返回定位信息而非成文文案：文案走 i18n（双 locale），本函数保持无 UI 依赖。
 */
export function validateDraftModel(
  model: ConditionDraftModel,
  fields: readonly ConfigField[]
): ConditionIssue[] {
  const byCode = new Map(fields.map((field) => [field.code, field]))
  const issues: ConditionIssue[] = []
  for (const [fieldCode, draft] of Object.entries(model.fields)) {
    const field = byCode.get(fieldCode)
    if (!field || !operatorNeedsValue(draft.operator)) {
      continue
    }
    const value = draft.value.trim()
    const value2 = draft.operator === 'BETWEEN' ? draft.value2.trim() : ''
    if (value === '' && value2 === '') {
      continue
    }
    if (field.fieldType === 'NUMBER') {
      if ((value !== '' && !isNumberText(value)) || (value2 !== '' && !isNumberText(value2))) {
        issues.push({ fieldCode, code: 'NUMBER', value, value2 })
        continue
      }
      if (value !== '' && value2 !== '' && Number(value) > Number(value2)) {
        issues.push({ fieldCode, code: 'RANGE', value, value2 })
      }
      continue
    }
    if (field.fieldType === 'DATE') {
      if ((value !== '' && !isDateText(value)) || (value2 !== '' && !isDateText(value2))) {
        issues.push({ fieldCode, code: 'DATE', value, value2 })
        continue
      }
      if (value !== '' && value2 !== '' && value > value2) {
        issues.push({ fieldCode, code: 'RANGE', value, value2 })
      }
    }
  }
  return issues
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

/**
 * 单个条件值 → 表单文本（数组按 IN 的逗号约定回写）。
 *
 * 逗号是**表单承载约定**，不是后端口径：`expandFields` 会把 IN 的逗号串拆回数组。
 * 值本身含逗号时拆分会变义，这类条件由 {@link draftModelFromCondition} 的等价判定兜住
 * （往返不等价 ⇒ 保留原始条件）。
 */
function draftValue(value: unknown): string {
  if (value === null || value === undefined) {
    return ''
  }
  if (Array.isArray(value)) {
    return value.map((item) => String(item)).join(',')
  }
  return String(value)
}

/** 条件值的比较串：后端逐条 AND、按字符串比较（`toString`），IN 的列表视为无序集合。 */
function canonicalValue(value: unknown): string {
  if (value === null || value === undefined) {
    return '\u0000null'
  }
  if (Array.isArray(value)) {
    return JSON.stringify(value.map((item) => (item === null || item === undefined ? '\u0000null' : String(item))).sort())
  }
  return String(value)
}

/** 单条字段条件的比较键（字段 + 算子 + 归一化取值）。 */
function fieldConditionKey(field: FieldCondition): string {
  return `${field.fieldCode}\u0000${String(field.operator).toUpperCase()}\u0000${canonicalValue(field.value)}`
}

/**
 * 两份条件是否**语义等价**（落库前"能不能用这份覆盖那份"的判据）。
 *
 * 口径：
 * - `scopeKeys` 按集合比较；字段条件按**多重集**比较（后端逐条 AND ⇒ 顺序无关）；
 * - 取值按后端字符串比较口径归一（`100` 与 `"100"` 同值；IN 的数组顺序无关）。
 *
 * 它比"逐字节相等"宽松一点（顺序/标量文字形态不算换义），但比"草案重建完事"严格：
 * 类型变化（`IN` 数组 → 逗号串）、条目丢失、取值被拆散都会判不等价。
 */
export function conditionsEquivalent(
  a: FieldQueryCondition | null | undefined,
  b: FieldQueryCondition | null | undefined
): boolean {
  if (isConditionEmpty(a) || isConditionEmpty(b)) {
    return isConditionEmpty(a) && isConditionEmpty(b)
  }
  const aScope = new Set(a?.scopeKeys ?? [])
  const bScope = new Set(b?.scopeKeys ?? [])
  if (aScope.size !== bScope.size || [...aScope].some((key) => !bScope.has(key))) {
    return false
  }
  const countKeys = (condition: FieldQueryCondition | null | undefined): Map<string, number> => {
    const counts = new Map<string, number>()
    for (const field of condition?.fields ?? []) {
      const key = fieldConditionKey(field)
      counts.set(key, (counts.get(key) ?? 0) + 1)
    }
    return counts
  }
  const aCounts = countKeys(a)
  const bCounts = countKeys(b)
  if (aCounts.size !== bCounts.size) {
    return false
  }
  for (const [key, count] of aCounts) {
    if (bCounts.get(key) !== count) {
      return false
    }
  }
  return true
}

/** 两份草案的**内容**是否相同（scopeKeys + 逐字段草案；忽略 `verbatim` 这类元信息）。 */
export function draftContentEqual(a: ConditionDraftModel, b: ConditionDraftModel): boolean {
  return JSON.stringify({ scopeKeys: a.scopeKeys, fields: a.fields })
    === JSON.stringify({ scopeKeys: b.scopeKeys, fields: b.fields })
}

/**
 * 后端条件 → 表单草案模型（恢复向导 / 详情回显 / AI 回填共用）。
 *
 * 同一个字段上的 GTE+LTE 合并回一个 `BETWEEN` 草案（`buildFieldConditions` 的逆运算），
 * 其余操作符原样保留；空条件返回空模型。
 *
 * **无损纪律**：每字段只放得下一条草案，所以逆映射不是天然的一一对应。构建完立即做一次
 * 往返等价判定；不等价（同字段多条件、IN 取值含逗号、类型/空值等）时把原始条件挂到
 * {@link ConditionDraftModel.verbatim}，后续任何重建都返回原条件、落库方也不会用它覆盖
 * 服务端（否则 `confirm_step` 会把 AI 设的条件换义）。
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
  if (condition && !isConditionEmpty(condition)) {
    const rebuilt = buildConditionOutcome(model).condition
    if (!conditionsEquivalent(condition, rebuilt)) {
      // 冻结一份：调用方（工作区 / 落库）可能继续持有原对象，别名会让两处互相改
      model.verbatim = JSON.parse(JSON.stringify(condition)) as FieldQueryCondition
    }
  }
  return model
}

