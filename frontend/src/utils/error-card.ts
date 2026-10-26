/**
 * R-165 S5c：错误卡族（demo v7.7 裁决区 08 改后 v7.4 定稿口径）。
 *
 * 铁律（demo 原文）：错误卡**内联于对话流、按错误发生的位置插入，绝不做顶部固定**
 * —— 顶部固定会让「错误 ↔ 触发它的那一步」失去位置关联。落点因此是**出错那一轮的助手消息**
 * （`ChatMessage.errorCard`），面板在该消息元信息行之后原样渲染，不参与任何 sticky/fixed 定位。
 *
 * 判据（demo 原文）：**按错误码分派** —— 429 → 红族；content_filter → 灰族；
 * context_length_exceeded → 蓝族；其余（断流 / 未分类故障）沿用红卡。
 *
 * 现实化（本仓 store 实际错误产生点，见施工报告 §错误卡映射表）：
 * - SSE `error` 帧：`frame.code` + `frame.message`（后端 16 类帧里唯一的错误终帧，
 *   实测上游 503 走 `UPSTREAM_STREAM_ERROR`）—— 三变体分派在此路生效；
 * - 开流前失败（`handleChatError`）：ApiError 的 `code` = 响应体 `code`，缺省为 HTTP 状态码字符串
 *   （429 配额即走这一形态）；
 * - 断流（ADR-6，缺终帧）/ 超时 / 取消：非错误卡族（前者已有 `alert-interrupted` 顶部 Alert，
 *   后两者属通知槽），本模块不产出卡片；
 * - 409 SESSION_BUSY / 503 SUSPEND_POOL_SATURATED / 503 AI_UNAVAILABLE / AI_REDIS_UNAVAILABLE
 *   已有**专属 Alert**（alert-busy-conflict / alert-pool-saturated / alert-degraded），
 *   不重复出卡（见 `DEDICATED_ALERT_CODES`）。
 *
 * 批次2（AR-05 / AR-09 / AR-13）两层文案：
 * - **码面不坍缩**：派生后缀（`_AFTER_PARTIAL` / `_AFTER_TOOL_SIDE_EFFECT`）不再被静默削掉；
 *   完整码留在 {@link ErrorCard#code}，基础码另记 {@link ErrorCard#baseCode}，后缀语义记
 *   {@link ErrorCard#derived}，标题与摘要按后缀各说各的现场（三态不再共用一句）。
 * - **用户向 ≠ 诊断**：{@link ErrorCard#summary} 是本地化的人类短句，首屏只读它；
 *   {@link ErrorCard#detail} 是后端原文（数据，不翻译），只在「诊断详情」里展示，连同机器码。
 */
import { ErrorCode } from '@/types/api'
import { translate } from '@/composables/useCopy'

/** 三变体 + 通用红卡（demo 判据里的「其余 = 断流/未分类」）。 */
export type ErrorCardVariant = 'quota' | 'filter' | 'context' | 'generic'

/** 终局失败派生后缀的现场语义（缺省 = 无派生后缀）。 */
export type ErrorCardDerived = 'partial' | 'tool-side-effect' | null

/** 错误卡数据（纯数据，渲染在 AiPanel —— 本模块不碰 DOM）。 */
export interface ErrorCard {
  variant: ErrorCardVariant
  /** 卡头标题 */
  title: string
  /** 用户向短句（批次2 新增；本地化，首屏正文，不承载后端原文/机器码） */
  summary: string
  /**
   * 诊断详情（= 现实错误文案，逐字透传，不编造额度/时刻等本仓拿不到的量）。
   * 只作为「诊断详情」的载荷展示，不再充当首屏正文。
   */
  detail: string
  /** 引导行（demo 原文的引导文案；通用红卡为空串 = 不渲染该行） */
  guide: string
  /** 动作钮文案（空串 = 无钮） */
  action: string
  /** 机器码（完整码原样透传，供取证与后续分派） */
  code: string
  /** 基础码（剥派生后缀后；无后缀时与 {@link ErrorCard#code} 相同） */
  baseCode: string
  /** 派生后缀语义（`null` = 完整码本身） */
  derived: ErrorCardDerived
}

/** 已有专属 Alert 的码：出卡会与顶部 Alert 重复报同一件事，故不内联。 */
const DEDICATED_ALERT_CODES: ReadonlySet<string> = new Set([
  ErrorCode.SESSION_BUSY,
  ErrorCode.SUSPEND_POOL_SATURATED,
  ErrorCode.AI_UNAVAILABLE,
  ErrorCode.AI_REDIS_UNAVAILABLE
])

/** 429 配额（码或文案任一命中）。HTTP 状态码形态由 axios 拦截器写成 `'429'`。 */
const QUOTA_RE = /(^|\D)429(\D|$)|rate[_-]?limit|quota|配额|额度/i
/** 内容过滤 / 策略拒绝（OpenAI 侧码名 `content_filter`）。 */
const FILTER_RE = /content[_-]?filter|safety|policy|安全策略|内容过滤|内容被拦截|拒绝生成/i
/** 上下文长度超限（OpenAI 侧码名 `context_length_exceeded`）。 */
const CONTEXT_RE = /context[_-]?length|context[_-]?window|max[_-]?tokens|too[_-]?long|上下文(长度)?(超限|超出)|超长|token\s*超/i

/**
 * R-09 派生后缀（基础码 + 现场）。批次2 起**不再削回**：后缀携带自己的标题标签与用户向短句，
 * 完整码逐字保留在 {@link ErrorCard#code}，基础码另存 {@link ErrorCard#baseCode}。
 */
const DERIVED_SUFFIXES: ReadonlyArray<{ suffix: string; kind: Exclude<ErrorCardDerived, null> }> = [
  { suffix: '_AFTER_TOOL_SIDE_EFFECT', kind: 'tool-side-effect' },
  { suffix: '_AFTER_PARTIAL', kind: 'partial' }
]

export function baseErrorCode(code: string): string {
  return splitErrorCode(code).base
}

/** 拆出基础码与派生语义（完整码本身不丢，调用方按需保留）。 */
export function splitErrorCode(code: string): { base: string; derived: ErrorCardDerived } {
  for (const item of DERIVED_SUFFIXES) {
    if (code.endsWith(item.suffix)) {
      return { base: code.slice(0, -item.suffix.length), derived: item.kind }
    }
  }
  return { base: code, derived: null }
}

/** A10 新 9 码 + R-12 key 码的专属卡标题/引导（不再是"未分类故障"通用卡）。 */
const CODE_CARD_TEXT: Record<string, { title: string; guide: string }> = {
  EMPTY_REPLY: { title: 'ai.card.error.emptyReply.title', guide: 'ai.card.error.emptyReply.guide' },
  AI_RUN_CANCELLED: { title: 'ai.card.error.cancelled.title', guide: 'ai.card.error.cancelled.guide' },
  UPSTREAM_FIRST_BYTE_TIMEOUT: { title: 'ai.card.error.timeout.title', guide: 'ai.card.error.timeout.guide' },
  UPSTREAM_SILENT_TIMEOUT: { title: 'ai.card.error.timeout.title', guide: 'ai.card.error.timeout.guide' },
  TOTAL_BUDGET_EXCEEDED: { title: 'ai.card.error.budget.title', guide: 'ai.card.error.budget.guide' },
  UPSTREAM_STREAM_INCOMPLETE: { title: 'ai.card.error.interrupted.title', guide: 'ai.card.error.interrupted.guide' },
  UPSTREAM_CONNECTION_BROKEN: { title: 'ai.card.error.connection.title', guide: 'ai.card.error.connection.guide' },
  UPSTREAM_STREAM_ERROR: { title: 'ai.card.error.upstream.title', guide: 'ai.card.error.upstream.guide' },
  UPSTREAM_UNAVAILABLE: { title: 'ai.card.error.upstream.title', guide: 'ai.card.error.upstream.guide' },
  UPSTREAM_AUTH_FAILED: { title: 'ai.card.error.authFailed.title', guide: 'ai.card.error.authFailed.guide' },
  UPSTREAM_REJECTED: { title: 'ai.card.error.rejected.title', guide: 'ai.card.error.rejected.guide' },
  AI_KEY_MISSING: { title: 'ai.card.error.keyMissing.title', guide: 'ai.card.error.keyMissing.guide' },
  AI_KEY_UNREADABLE: { title: 'ai.card.error.keyUnreadable.title', guide: 'ai.card.error.keyUnreadable.guide' },
  NO_MODEL_CONFIGURED: { title: 'ai.card.error.noModel.title', guide: 'ai.card.error.noModel.guide' }
}

/**
 * 用户向摘要（批次2）：一码一句的**本地化短句**，不承载后端原文。
 * 查表顺序 = 完整码 → 基础码 → 变体兜底；派生后缀在基础摘要后再追加一句现场说明。
 */
const CODE_SUMMARY_KEY: Record<string, string> = {
  EMPTY_REPLY: 'ai.card.error.summary.emptyReply',
  AI_RUN_CANCELLED: 'ai.card.error.summary.cancelled',
  UPSTREAM_FIRST_BYTE_TIMEOUT: 'ai.card.error.summary.timeout',
  UPSTREAM_SILENT_TIMEOUT: 'ai.card.error.summary.timeout',
  TOTAL_BUDGET_EXCEEDED: 'ai.card.error.summary.budget',
  UPSTREAM_STREAM_INCOMPLETE: 'ai.card.error.summary.interrupted',
  UPSTREAM_CONNECTION_BROKEN: 'ai.card.error.summary.connection',
  UPSTREAM_STREAM_ERROR: 'ai.card.error.summary.upstream',
  UPSTREAM_UNAVAILABLE: 'ai.card.error.summary.upstream',
  UPSTREAM_AUTH_FAILED: 'ai.card.error.summary.authFailed',
  UPSTREAM_REJECTED: 'ai.card.error.summary.rejected',
  AI_KEY_MISSING: 'ai.card.error.summary.keyMissing',
  AI_KEY_UNREADABLE: 'ai.card.error.summary.keyUnreadable',
  NO_MODEL_CONFIGURED: 'ai.card.error.summary.noModel'
}

/** 有专属色族/动作的码（上下文超限走蓝族"开新会话"，限流走红族，内容过滤走灰族）。 */
const CODE_VARIANT: Record<string, ErrorCardVariant> = {
  CONTEXT_OVERFLOW: 'context',
  UPSTREAM_RATE_LIMITED: 'quota',
  CONTENT_FILTERED: 'filter'
}

/** 三变体文案（demo v7.7 裁决区 08 逐字；`detail` 由调用方喂现实文案）。
 *
 * i18n L-69：只存 locale key（`ai.card.error.*`），建卡时经 {@link translate} 取当前语言文本 ——
 * `detail` 本就是"错误发生那一刻的后端原文"，标题/引导/按钮同属**建卡定格**的文本，不随后续
 * 切语言回翻（卡片是要留档的一轮现场）。批次2 的 `summary` 同属建卡定格。
 */
const VARIANT_TEXT_KEY: Record<Exclude<ErrorCardVariant, 'generic'>, { title: string; summary: string; guide: string; action: string }> = {
  quota: {
    title: 'ai.card.error.quota.title',
    summary: 'ai.card.error.summary.quota',
    guide: 'ai.card.error.quota.guide',
    action: 'ai.card.error.quota.action'
  },
  filter: {
    title: 'ai.card.error.filter.title',
    summary: 'ai.card.error.summary.filter',
    guide: 'ai.card.error.filter.guide',
    action: 'ai.card.error.filter.action'
  },
  context: {
    title: 'ai.card.error.context.title',
    summary: 'ai.card.error.summary.context',
    guide: 'ai.card.error.context.guide',
    action: 'ai.card.error.context.action'
  }
}

/** 派生后缀的现场标签 + 用户向补充句（完整码语义在此落地，不再静默削回基础码）。 */
const DERIVED_TEXT_KEY: Record<Exclude<ErrorCardDerived, null>, { label: string; summary: string }> = {
  partial: {
    label: 'ai.card.error.derived.partialLabel',
    summary: 'ai.card.error.derived.partialSummary'
  },
  'tool-side-effect': {
    label: 'ai.card.error.derived.toolSideEffectLabel',
    summary: 'ai.card.error.derived.toolSideEffectSummary'
  }
}

/** 变体分派（纯函数，判据 = 码优先、文案兜底）。 */
export function classifyErrorVariant(code: string, message: string): ErrorCardVariant {
  const haystack = `${code} ${message}`
  if (QUOTA_RE.test(haystack)) {
    return 'quota'
  }
  if (FILTER_RE.test(haystack)) {
    return 'filter'
  }
  if (CONTEXT_RE.test(haystack)) {
    return 'context'
  }
  return 'generic'
}

/**
 * 建立错误卡；返回 `null` = 本错误不内联出卡（专属 Alert 承载 / 通知槽承载）。
 * `code` 允许为空串（网络异常等无码路径 ⇒ 落通用红卡）。
 */
export function buildErrorCard(code: string, message: string): ErrorCard | null {
  const { base, derived } = splitErrorCode(code)
  if (base !== '' && DEDICATED_ALERT_CODES.has(base)) {
    return null
  }

  let variant: ErrorCardVariant
  let title: string
  let summary: string
  let guide: string
  let action: string

  const forcedVariant = CODE_VARIANT[code] ?? CODE_VARIANT[base]
  const dedicated = CODE_CARD_TEXT[code] ?? CODE_CARD_TEXT[base]
  if (forcedVariant !== undefined && forcedVariant !== 'generic') {
    variant = forcedVariant
    const text = VARIANT_TEXT_KEY[forcedVariant]
    title = translate(text.title)
    summary = translate(text.summary)
    guide = translate(text.guide)
    action = translate(text.action)
  } else if (dedicated !== undefined) {
    variant = 'generic'
    title = translate(dedicated.title)
    summary = translate(CODE_SUMMARY_KEY[code] ?? CODE_SUMMARY_KEY[base] ?? 'ai.card.error.summary.generic')
    guide = translate(dedicated.guide)
    action = ''
  } else {
    variant = classifyErrorVariant(code, message)
    if (variant === 'generic') {
      title = translate('ai.card.error.generic.title')
      summary = translate('ai.card.error.summary.generic')
      guide = ''
      action = ''
    } else {
      const text = VARIANT_TEXT_KEY[variant]
      title = translate(text.title)
      summary = translate(text.summary)
      guide = translate(text.guide)
      action = translate(text.action)
    }
  }

  if (derived !== null) {
    const derivedText = DERIVED_TEXT_KEY[derived]
    title = `${title} · ${translate(derivedText.label)}`
    summary = `${summary}${translate(derivedText.summary)}`
  }

  return {
    variant,
    title,
    summary,
    detail: message,
    guide,
    action,
    code,
    baseCode: base,
    derived
  }
}
