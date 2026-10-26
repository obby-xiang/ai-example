/**
 * 本轮运行态（Redis 24h 滑动保留期）的**只可见化**口径（AR-10）。
 *
 * <p>数据来源唯一：后端 {@code GET /api/ai/runs/current} 的 {@code snapshotRemainingMs}
 * 是对 {@code ai:run:<runId>} 做 {@code PTTL} 的只读推导。前端**不**使用 24h 常量、
 * 也**不**另推一套到期时间 —— 只按"收到该响应的时刻 + 服务端给的剩余毫秒"递减。
 *
 * <p>AR-10 第 2 条：本模块只读展示，不改 TTL、不续期、不缩短，也不新增任何持久化。
 *
 * <p><b>滑动的后果（必须记住）</b>：该 TTL 随活动滑动续期（心跳 / 写回都把键重新养满），
 * 所以"剩余毫秒"只是**上次对账时刻**的下界。本地时钟走到 0 仅代表"按上次对账已到期"，
 * 因此到期态一律配可达出口（刷新状态 = 再对账一次 / 新建会话），本模块**从不**自行移除卡面。
 */
import type { AiRunCurrent } from '@/types/ai'

/** 一轮运行态的权威寿命锚点。`remainingMs` 必须来自后端，缺失即不显示（不猜）。 */
export interface RunExpiryAnchor {
  /** 该寿命属于哪一轮（防止旧轮的锚点落到新轮的卡上）。 */
  runId: string
  /** 收到响应时的剩余毫秒（服务端权威值）。 */
  remainingMs: number
  /** 服务端给出的到期时刻（epoch 毫秒，服务端钟）；null = 后端未给（旧后端）。 */
  expiresAt: number | null
  /** 客户端收到该响应的时刻：只用来扣掉本地流逝，不参与"何时到期"的定义。 */
  receivedAtMs: number
}

/** 读秒分档阈值（既有业界口径：&lt;10 分钟读秒，超过 1 小时只给粗粒度）。 */
const SECOND_MS = 1000
const MINUTE_MS = 60 * SECOND_MS
const HOUR_MS = 60 * MINUTE_MS
/**
 * 读秒窗口（导出给面板的本地时钟：只有进入这个窗口才值得按 1s 重渲染；
 * 24h 量级的寿命按 30s 粗粒度推进即可）。
 */
export const RUN_EXPIRY_COUNTDOWN_WINDOW_MS = 10 * MINUTE_MS

function isFinitePositive(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0
}

/**
 * 从 `runs/current` 载荷取锚点。
 *
 * @param current 快照载荷（`found=false` / 缺字段 ⇒ null，绝不折算默认 24h）
 * @param receivedAtMs 客户端收到时刻
 */
export function runExpiryAnchorOf(current: AiRunCurrent, receivedAtMs: number): RunExpiryAnchor | null {
  if (!current.found || !current.runId) {
    return null
  }
  if (!isFinitePositive(current.snapshotRemainingMs)) {
    return null
  }
  const expiresAt = isFinitePositive(current.snapshotExpiresAt) ? current.snapshotExpiresAt : null
  return {
    runId: current.runId,
    remainingMs: current.snapshotRemainingMs,
    expiresAt,
    receivedAtMs
  }
}

/** 当前剩余毫秒（下界 0 = 已到期）。只扣本地流逝，不引入第二套到期判定。 */
export function runExpiryRemainingMs(anchor: RunExpiryAnchor, nowMs: number): number {
  return Math.max(0, anchor.remainingMs - (nowMs - anchor.receivedAtMs))
}

/** 卡面寿命文案（locale key + 插值参数）；`expired=true` 时由调用方给可达出口。 */
export interface RunExpiryLabel {
  key: string
  params: Record<string, string | number>
  expired: boolean
}

function pad2(value: number): string {
  return value < 10 ? `0${value}` : String(value)
}

/**
 * 读秒分档（对齐既有裁决：&lt;10 分钟 mm:ss / &lt;1 小时按分钟 / 更长按小时）：
 * 24h 量级的寿命不逐秒播报，避免制造焦虑。
 */
export function runExpiryLabel(remainingMs: number): RunExpiryLabel {
  if (remainingMs <= 0) {
    return { key: 'ai.panel.runExpiry.expired', params: {}, expired: true }
  }
  if (remainingMs < RUN_EXPIRY_COUNTDOWN_WINDOW_MS) {
    const seconds = Math.ceil(remainingMs / SECOND_MS)
    return {
      key: 'ai.panel.runExpiry.countdown',
      params: { time: `${pad2(Math.floor(seconds / 60))}:${pad2(seconds % 60)}` },
      expired: false
    }
  }
  if (remainingMs < HOUR_MS) {
    return {
      key: 'ai.panel.runExpiry.minutes',
      params: { minutes: Math.ceil(remainingMs / MINUTE_MS) },
      expired: false
    }
  }
  return {
    key: 'ai.panel.runExpiry.hours',
    params: { hours: Math.ceil(remainingMs / HOUR_MS) },
    expired: false
  }
}
