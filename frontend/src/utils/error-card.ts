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
 * - SSE `error` 帧：`frame.code` + `frame.message`（后端 15 类帧里唯一的错误终帧，
 *   实测上游 503 走 `UPSTREAM_STREAM_ERROR`）—— 三变体分派在此路生效；
 * - 开流前失败（`handleChatError`）：ApiError 的 `code` = 响应体 `code`，缺省为 HTTP 状态码字符串
 *   （429 配额即走这一形态）；
 * - 断流（ADR-6，缺终帧）/ 超时 / 取消：非错误卡族（前者已有 `alert-interrupted` 顶部 Alert，
 *   后两者属通知槽），本模块不产出卡片；
 * - 409 SESSION_BUSY / 503 SUSPEND_POOL_SATURATED / 503 AI_UNAVAILABLE / AI_REDIS_UNAVAILABLE
 *   已有**专属 Alert**（alert-busy-conflict / alert-pool-saturated / alert-degraded），
 *   不重复出卡（见 `DEDICATED_ALERT_CODES`）。
 */
import { ErrorCode } from '@/types/api'

/** 三变体 + 通用红卡（demo 判据里的「其余 = 断流/未分类」）。 */
export type ErrorCardVariant = 'quota' | 'filter' | 'context' | 'generic'

/** 错误卡数据（纯数据，渲染在 AiPanel —— 本模块不碰 DOM）。 */
export interface ErrorCard {
  variant: ErrorCardVariant
  /** 卡头标题 */
  title: string
  /** 依据行（= 现实错误文案，逐字透传，不编造额度/时刻等本仓拿不到的量） */
  detail: string
  /** 引导行（demo 原文的引导文案；通用红卡为空串 = 不渲染该行） */
  guide: string
  /** 动作钮文案（空串 = 无钮） */
  action: string
  /** 机器码（原样透传，供取证与后续分派） */
  code: string
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

/** 三变体文案（demo v7.7 裁决区 08 逐字；`detail` 由调用方喂现实文案）。 */
const VARIANT_TEXT: Record<Exclude<ErrorCardVariant, 'generic'>, { title: string; guide: string; action: string }> = {
  quota: {
    title: '请求超出配额',
    guide: '引导：稍后重试；若在批量任务里，先降低并发再重发。',
    action: '稍后重试'
  },
  filter: {
    title: '内容被安全策略拦截',
    guide: '引导：改为描述「目标 + 约束」后重发，不要直接贴可执行命令原文。',
    action: '改写后重发'
  },
  context: {
    title: '上下文长度超限',
    guide: '引导：开新会话并把关键结论带过去（重试不会变短）。',
    action: '开新会话'
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
  if (code !== '' && DEDICATED_ALERT_CODES.has(code)) {
    return null
  }
  const variant = classifyErrorVariant(code, message)
  if (variant === 'generic') {
    return {
      variant,
      title: '本轮出错',
      detail: message,
      guide: '',
      action: '',
      code
    }
  }
  const text = VARIANT_TEXT[variant]
  return { variant, title: text.title, detail: message, guide: text.guide, action: text.action, code }
}
