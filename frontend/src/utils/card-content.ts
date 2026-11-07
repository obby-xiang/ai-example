import { translate } from '@/composables/useCopy'
import type { SheetWritePlan } from '@/types/ai'
import { conditionSummaryText, conditionSummaryView, isConditionEmpty, type FieldQueryCondition } from '@/types/condition'

const PUBLIC_ENUM_CODE_RE = /^[A-Z][A-Z0-9_]{0,63}$/

function enumRaw(value: unknown): string {
  return typeof value === 'string' ? value.trim() : String(value ?? '').trim()
}

function unknownEnumText(value: unknown): string {
  const raw = enumRaw(value)
  return PUBLIC_ENUM_CODE_RE.test(raw)
    ? translate('ai.value.unknownWithCode', { code: raw })
    : translate('ai.value.unknown')
}

function translated(key: string, value: unknown): string {
  const localized = translate(key)
  return localized === key ? unknownEnumText(value) : localized
}

export function taskTypeText(value: unknown): string {
  const raw = enumRaw(value)
  switch (raw.toUpperCase()) {
    case 'EXPORT':
      return translate('views.tasks.exportType')
    case 'IMPORT':
      return translate('views.tasks.importType')
    default:
      return unknownEnumText(raw)
  }
}

export function jobTypeText(value: unknown): string {
  const raw = enumRaw(value)
  switch (raw.toUpperCase()) {
    case 'EXPORT':
      return translate('tool.start_export.title')
    case 'PRECHECK':
      return translate('tool.start_precheck.title')
    case 'IMPORT':
      return translate('tool.start_import.title')
    case 'PUBLISH':
      return translate('tool.start_publish.title')
    default:
      return unknownEnumText(raw)
  }
}

export function configLevelText(value: unknown): string {
  const raw = enumRaw(value)
  switch (raw.toUpperCase()) {
    case 'GLOBAL':
      return translated('ai.values.level.GLOBAL', raw)
    case 'REGION':
      return translated('ai.values.level.REGION', raw)
    case 'PROJECT':
      return translated('ai.values.level.PROJECT', raw)
    default:
      return unknownEnumText(raw)
  }
}

export function importModeText(value: unknown): string {
  const raw = enumRaw(value)
  switch (raw.toUpperCase()) {
    case 'MERGE':
      return translate('views.import.mergeMode')
    case 'REPLACE':
      return translate('views.import.replaceMode')
    case 'ADD':
      return translated('ai.values.mode.ADD', raw)
    default:
      return unknownEnumText(raw)
  }
}

export function taskStepText(value: unknown): string {
  const raw = enumRaw(value)
  switch (raw.toUpperCase()) {
    case 'SELECT_DEFS':
      return translate('views.export.steps.select')
    case 'QUERY_COND':
      return translate('views.export.steps.query')
    case 'EXPORT':
      return translate('views.export.steps.export')
    case 'UPLOAD':
      return translate('views.import.steps.upload')
    case 'PRECHECK':
      return translate('views.import.steps.check')
    case 'IMPORT':
      return translate('views.import.steps.import')
    case 'PUBLISH':
      return translate('views.import.steps.publish')
    case 'DONE':
      return translated('ai.values.step.DONE', raw)
    default:
      return unknownEnumText(raw)
  }
}

export function workspacePageText(value: unknown): string {
  const raw = enumRaw(value)
  return translated(`ai.workspace.page.${raw.toLowerCase()}`, raw)
}

export function sheetScopeText(value: unknown): string {
  const raw = enumRaw(value)
  return translated(`ai.values.scope.${raw}`, raw)
}

export function textFormatText(value: unknown): string {
  const raw = enumRaw(value)
  return translated(`ai.gf.formats.${raw.toLowerCase()}`, raw)
}

export function formFieldTypeText(value: unknown): string {
  const raw = enumRaw(value)
  return translated(`ai.gf.types.${raw.toLowerCase()}`, raw)
}

export function configFieldTypeText(value: unknown): string {
  const raw = enumRaw(value)
  return translated(`views.definitions.types.${raw.toUpperCase()}`, raw)
}

function taskStatusText(value: string): string {
  const raw = enumRaw(value)
  switch (raw.toUpperCase()) {
    case 'ACTIVE':
      return translate('views.tasks.active')
    case 'COMPLETED':
      return translate('views.tasks.completed')
    case 'CANCELLED':
      return translate('views.tasks.cancelled')
    case 'FAILED':
      return translate('views.tasks.failed')
    default:
      return translated(`tool.status.${raw.toLowerCase()}`, raw)
  }
}

const FIELD_LABEL_KEYS: Record<string, string> = {
  taskId: 'ai.fields.taskId',
  jobId: 'ai.fields.jobId',
  defCode: 'ai.fields.defCode',
  defCodes: 'ai.fields.defCodes',
  code: 'ai.fields.code',
  type: 'ai.fields.type',
  taskType: 'ai.fields.type',
  task: 'ai.fields.type',
  title: 'ai.fields.title',
  conditions: 'ai.fields.conditions',
  predicate: 'ai.fields.conditions',
  scope: 'ai.fields.scope',
  format: 'ai.fields.format',
  level: 'ai.fields.level',
  mode: 'ai.fields.mode',
  option: 'ai.fields.option',
  step: 'ai.fields.step',
  page: 'ai.fields.page',
  jobType: 'ai.fields.jobType',
  status: 'ai.fields.status',
  filename: 'ai.fields.filename',
  accept: 'ai.fields.accept',
  query: 'ai.fields.query',
  term: 'ai.fields.term',
  search: 'ai.fields.search',
  replace: 'ai.fields.replace',
  groupBy: 'ai.fields.groupBy',
  columnCode: 'ai.fields.columnCode',
  columnCodes: 'ai.fields.columnCodes',
  metrics: 'ai.fields.metrics',
  sort: 'ai.fields.sort',
  limit: 'ai.fields.limit',
  rules: 'ai.fields.rules',
  caseMode: 'ai.fields.caseMode',
  commit: 'ai.fields.commit',
  preview: 'ai.fields.preview',
  set: 'ai.fields.set',
  distributionBy: 'ai.fields.distributionBy',
  regions: 'ai.fields.regions',
  kinds: 'ai.fields.kinds',
  text: 'ai.fields.text',
  value: 'ai.fields.value',
  note: 'ai.fields.note',
  delta: 'ai.fields.delta',
  block: 'ai.fields.block',
  rowRange: 'ai.fields.rowRange',
  match: 'ai.fields.match',
  ignoreCase: 'ai.fields.ignoreCase',
  fieldType: 'ai.fields.fieldType',
  maxChars: 'ai.fields.maxChars',
  offset: 'ai.fields.offset'
}

const INTERNAL_FIELDS = new Set([
  'runid',
  'toolcallid',
  'fileid',
  'sessionid',
  'messageid',
  'ref',
  'epoch',
  'path',
  'url',
  'endpoint',
  'baseurl',
  'filepath',
  'fileurl',
  'downloadurl',
  'reporthash'
])

export function canShowCardField(key: string): boolean {
  return !INTERNAL_FIELDS.has(key.replace(/[_-]/g, '').toLowerCase())
}

export function cardFieldLabel(key: string): string {
  const labelKey = FIELD_LABEL_KEYS[key]
  return labelKey === undefined ? key : translate(labelKey)
}

function scalarCardFieldValue(key: string, value: unknown): string | undefined {
  if (typeof value !== 'string') {
    return undefined
  }
  switch (key) {
    case 'type':
    case 'taskType':
    case 'task':
      return taskTypeText(value)
    case 'jobType':
      return jobTypeText(value)
    case 'level':
      return configLevelText(value)
    case 'mode':
    case 'option':
      return importModeText(value)
    case 'step':
      return taskStepText(value)
    case 'page':
      return workspacePageText(value)
    case 'scope':
      return sheetScopeText(value)
    case 'format':
      return textFormatText(value)
    case 'fieldType':
      return configFieldTypeText(value)
    case 'match':
      return translated(`ai.values.match.${value}`, value)
    case 'preview':
      return translated(`ai.values.preview.${value}`, value)
    case 'sort':
      return translated(`ai.values.sort.${value}`, value)
    case 'metrics':
      return translated(`ai.values.metric.${value}`, value)
    case 'status':
      return taskStatusText(value)
    default:
      return undefined
  }
}

export function localizedCardFieldValue(key: string, value: unknown): string | undefined {
  if (Array.isArray(value)) {
    if (value.length === 0) {
      return undefined
    }
    const items = value.map((item) => scalarCardFieldValue(key, item))
    if (items.every((item) => item !== undefined)) {
      return items.join(translate('ai.value.listSeparator'))
    }
    return undefined
  }
  return scalarCardFieldValue(key, value)
}

function planCondition(plan: SheetWritePlan): FieldQueryCondition | null {
  const raw = plan.args?.predicate
  return raw !== null && typeof raw === 'object' && !Array.isArray(raw)
    ? raw as FieldQueryCondition
    : null
}

export function sheetPlanPredicateSummary(plan: SheetWritePlan): string {
  const condition = planCondition(plan)
  return condition !== null && !isConditionEmpty(condition)
    ? conditionSummaryText(conditionSummaryView(condition), translate)
    : plan.predicateSummary
}

export function sheetPlanScopeSummary(plan: SheetWritePlan): string {
  if (plan.args === undefined) {
    return plan.scopeSummary
  }
  const def = plan.defCode
  switch (plan.scope) {
    case 'sheet':
      return translate('ai.sheetCard.scopeValues.sheet', { def })
    case 'columns': {
      const raw = plan.args.columnCodes
      if (!Array.isArray(raw) || raw.length === 0) {
        return plan.scopeSummary
      }
      const columns = raw.map(String).filter((code) => code.trim() !== '').join(translate('ai.value.listSeparator'))
      return columns === ''
        ? plan.scopeSummary
        : translate('ai.sheetCard.scopeValues.columns', { def, columns })
    }
    case 'rows': {
      const rowRange = typeof plan.args.rowRange === 'string' ? plan.args.rowRange : ''
      return rowRange === ''
        ? plan.scopeSummary
        : translate('ai.sheetCard.scopeValues.rows', { def, rowRange })
    }
    case 'predicate': {
      const condition = planCondition(plan)
      return condition === null || isConditionEmpty(condition)
        ? plan.scopeSummary
        : translate('ai.sheetCard.scopeValues.predicate', { def })
    }
    case 'selection':
      return translate('ai.sheetCard.scopeValues.selection', { def })
    default:
      return plan.scopeSummary
  }
}

function replaceRulesText(plan: SheetWritePlan, args: Record<string, unknown>): string {
  if (typeof args.search !== 'string' || args.search.trim() === '') {
    return plan.rulesText
  }
  const match = typeof args.match === 'string'
    ? args.match.trim()
    : Array.isArray(args.match)
      ? args.match.map((item) => typeof item === 'string' ? item.trim() : '').find((item) => item !== '') ?? ''
      : ''
  if (match === '') {
    return plan.rulesText
  }
  const ignoreCase = args.ignoreCase !== false
  return translate('ai.sheetCard.ruleValues.replace', {
    match: translated(`ai.values.match.${match}`, match),
    search: args.search,
    replace: typeof args.replace === 'string' ? args.replace : '',
    ignoreCase: translate(ignoreCase ? 'ai.value.yes' : 'ai.value.no')
  })
}

function updateRulesText(plan: SheetWritePlan, args: Record<string, unknown>): string {
  if (!Array.isArray(args.set) || args.set.length === 0) {
    return plan.rulesText
  }
  const updates = args.set.map((raw) => {
    if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) {
      return null
    }
    const item = raw as Record<string, unknown>
    const columnCode = typeof item.columnCode === 'string' ? item.columnCode.trim() : ''
    if (columnCode === '') {
      return null
    }
    const mode = typeof item.mode === 'string' && item.mode !== '' ? item.mode : 'literal'
    return translate('ai.sheetCard.ruleValues.updateItem', {
      columnCode,
      mode: translated(`ai.values.updateMode.${mode}`, mode)
    })
  })
  if (updates.some((item) => item === null)) {
    return plan.rulesText
  }
  return translate('ai.sheetCard.ruleValues.update', {
    updates: updates.join(translate('ai.value.listSeparator'))
  })
}

function normalizeRulesText(plan: SheetWritePlan, args: Record<string, unknown>): string {
  if (!Array.isArray(args.rules) || args.rules.length === 0
    || args.rules.some((rule) => typeof rule !== 'string' || rule.trim() === '')) {
    return plan.rulesText
  }
  const labels = args.rules.map((rule) => translated(`ai.values.normalizeRule.${rule}`, rule))
    .join(translate('ai.value.listSeparator'))
  return translate('ai.sheetCard.ruleValues.normalize', { rules: labels })
}

export function sheetPlanRulesText(plan: SheetWritePlan): string {
  if (plan.args === undefined) {
    return plan.rulesText
  }
  if (plan.tool === 'replace_values') {
    return replaceRulesText(plan, plan.args)
  }
  if (plan.tool === 'update_rows') {
    return updateRulesText(plan, plan.args)
  }
  if (plan.tool === 'normalize_column') {
    return normalizeRulesText(plan, plan.args)
  }
  return plan.rulesText
}
