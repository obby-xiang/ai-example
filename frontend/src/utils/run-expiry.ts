/**
 * 本轮运行态（Redis 24h 滑动保留期）的**后台对账**口径。
 *
 * <p>数据来源唯一：后端 {@code GET /api/ai/runs/current} 的 {@code snapshotRemainingMs}
 * 是对 {@code ai:run:<runId>} 做 {@code PTTL} 的只读推导。前端**不**使用 24h 常量、
 * 也**不**另推一套到期时间 —— 只按"收到该响应的时刻 + 服务端给的剩余毫秒"递减。
 *
 * <p>C 端界面不再展示保留期倒计时；本模块只服务 store 的"运行态是否已按保留期消失"
 * 对账与提示，不改 TTL、不续期、不缩短，也不新增任何持久化。
 */
import type { AiRunCurrent } from '@/types/ai'

/** 一轮运行态的权威寿命锚点。`remainingMs` 必须来自后端，缺失即不落（不猜）。 */
export interface RunExpiryAnchor {
  /** 该寿命属于哪一轮（防止旧轮的锚点落到新轮的卡上）。 */
  runId: string
  /** 收到响应时的剩余毫秒（服务端权威值）。 */
  remainingMs: number
  /** 服务端给出的到期时刻（epoch 毫秒，服务端钟）；null = 后端未给。 */
  expiresAt: number | null
  /** 客户端收到该响应的时刻：只用来扣掉本地流逝，不参与"何时到期"的定义。 */
  receivedAtMs: number
}

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
