/**
 * 上下文占用的展示口径（上下文统计与压缩专项批①；设计稿 §3.4(c)）。
 *
 * <h2>为什么把格式化抽成纯函数</h2>
 * pill 的三档边界（70/85/95）与 `k` 缩写规则是**逐点可错的判定**（`>=` 写成 `>` 就差一格），
 * 而这类判定一旦埋在模板里就只能靠"挂载 + 读 class"来测，读数与判定混在一起。
 * 抽出来后：判定是纯函数（逐点断言），模板只负责把结果贴到 class 上。
 *
 * <h2>数字纪律</h2>
 * - `< 1000` 不缩写（"842 tokens" 比 "0.8k" 可读且不会被误读成 800）；
 * - `>= 1000` 用 `k`，**保留一位**、整数不补 `.0`（84.2k / 84k / 128k）；
 * - 百分比由**服务端**算（一位小数），前端不重复折算 —— 前后端各 round 一次会出现同源分歧。
 */

import type { ContextBreakdownItem, ContextUsage } from '@/types/ai'

/**
 * 三档换色的阈值：70 / 85 / 95（设计稿 §3.4(c) 逐字）。
 *
 * <p>与后端 `app.ai.context.l1-percent` 是**语义对齐但不是同一个配置**：界面分档是为了"读到进度"，
 * 后端阈值是为了"判定压缩"，两者可以独立调；共用一个键会把"界面换色"绑死在"何时压缩"上。
 */
export const CONTEXT_TIER_WARN = 70

export const CONTEXT_TIER_HIGH = 85

export const CONTEXT_TIER_DANGER = 95

export type ContextTier = 'none' | 'safe' | 'warn' | 'high' | 'danger'

/** token 数的缩写（见文件头"数字纪律"）。 */
export function formatTokens(tokens: number): string {
  const value = Number.isFinite(tokens) ? Math.max(0, Math.trunc(tokens)) : 0
  if (value < 1000) {
    return String(value)
  }
  const scaled = value / 1000
  const oneDecimal = Math.round(scaled * 10) / 10
  return `${Number.isInteger(oneDecimal) ? oneDecimal.toFixed(0) : oneDecimal.toFixed(1)}k`
}

/**
 * 档位判定（边界逐点：69→safe、70→warn、84→warn、85→high、94→high、95→danger）。
 *
 * @param percent 服务端给的百分比；null = 上限未知/未测 ⇒ `none`（不换色、不显示百分比）
 */
export function contextTier(percent: number | null): ContextTier {
  if (percent === null || !Number.isFinite(percent)) {
    return 'none'
  }
  if (percent >= CONTEXT_TIER_DANGER) {
    return 'danger'
  }
  if (percent >= CONTEXT_TIER_HIGH) {
    return 'high'
  }
  if (percent >= CONTEXT_TIER_WARN) {
    return 'warn'
  }
  return 'safe'
}

/** 档位 → 药丸配色（全 Tailwind；与面板既有配色族同源：灰/琥珀/橙/红）。 */
export function tierClass(tier: ContextTier): string {
  switch (tier) {
    case 'warn':
      return 'border-[#f0d9a8] bg-[#fff8e6] text-[#9a6700]'
    case 'high':
      return 'border-[#ffd8b5] bg-[#fff1e5] text-[#bc4c00]'
    case 'danger':
      return 'border-[#ffcecb] bg-[#ffebe9] text-[#d1242f]'
    case 'none':
    case 'safe':
    default:
      return 'border-[#e3e6ea] bg-white text-[#57606a]'
  }
}

/** 档位 → 措辞（纯文本，禁 emoji；`title` 用完整句，视觉上仍是一行小字）。 */
export function tierTitle(usage: ContextUsage): string {
  const tier = contextTier(usage.percent)
  const used = formatTokens(usage.usedTokens)
  const limit = usage.limitTokens === null ? null : formatTokens(usage.limitTokens)
  const estimated = usage.est ? '（估算值：上游未返回用量）' : ''
  if (limit === null) {
    return `本轮上下文已用 ${used} tokens${estimated}`
  }
  const head = `本轮上下文已用 ${used} / ${limit} tokens${estimated}`
  switch (tier) {
    case 'danger':
      // 批① 只做 L1（清工具结果）；摘要属批③ —— 文案不得承诺尚未落地的手段
      return `${head}；接近上限，下一次请求前会先清理历史工具结果`
    case 'high':
      return `${head}；已进入压缩区（工具结果优先清理）`
    case 'warn':
      return `${head}；已超过轻量清理线`
    default:
      return head
  }
}

/**
 * pill 文本（C-31/C-33 的判据本体）。
 *
 * 三形态：
 * - 有上限：`66% · 84k/128k`（估算时前缀 `≈`）；
 * - 上限未知（`limitTokens: null`）：`已用 84k`（有百分比才算百分比 —— 没有分母就没有百分比）；
 * - 未测（`usedTokens === 0` 且 `percent === null`）：空串（调用方据此不渲染 pill）。
 */
export function contextPillText(usage: ContextUsage | null): string {
  if (!usage) {
    return ''
  }
  const used = formatTokens(usage.usedTokens)
  const prefix = usage.est ? '≈' : ''
  if (usage.limitTokens === null || usage.percent === null) {
    if (usage.usedTokens === 0) {
      return ''
    }
    return `${prefix}已用 ${used}`
  }
  const percent = formatPercent(usage.percent)
  return `${prefix}${percent}% · ${used}/${formatTokens(usage.limitTokens)}`
}

/** 百分比展示：整数不带小数（66%），非整数保留一位（65.8%）。 */
export function formatPercent(percent: number): string {
  if (!Number.isFinite(percent)) {
    return '0'
  }
  const rounded = Math.round(percent * 10) / 10
  return Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1)
}

/** 七项的显示名（顺序由服务端返回决定，这里只做"键 → 人话"）。 */
export const CONTEXT_PART_LABELS: Record<string, string> = {
  history: '历史对话',
  toolResults: '工具结果',
  summary: '压缩摘要',
  system: '系统提示',
  tools: '工具定义',
  input: '本轮输入',
  free: '剩余可用'
}

/** 分项名（未知键原样显示，绝不吞项 —— 后端加类目时界面自动多一行）。 */
export function partLabel(key: string): string {
  return CONTEXT_PART_LABELS[key] ?? key
}

/** `free` 的显示：负值 = 已超（界面上必须与"剩余"完全不同的措辞）。 */
export function formatPartTokens(key: string, tokens: number): string {
  if (key === 'free' && tokens < 0) {
    return `已超 ${formatTokens(-tokens)}`
  }
  return `${formatTokens(tokens)} tokens`
}

/** 分项按服务端顺序原样保留（不排序、不按对象键序 —— C-34 的判据）。 */
export function orderedParts(parts: ContextBreakdownItem[] | null): ContextBreakdownItem[] {
  return parts ? [...parts] : []
}
