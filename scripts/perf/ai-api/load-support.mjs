const DEFAULT_CONCURRENCY = 4
const MAX_CONCURRENCY = 16
const DEFAULT_REQUESTS_PER_ENDPOINT = 8
const MAX_REQUESTS_PER_ENDPOINT = 100

function integerInRange(raw, name, min, max) {
  const text = String(raw).trim()
  if (!/^\d+$/.test(text)) {
    throw new Error(`${name} must be an integer between ${min} and ${max}: got ${JSON.stringify(raw)}`)
  }
  const value = Number(text)
  if (!Number.isSafeInteger(value) || value < min || value > max) {
    throw new Error(`${name} must be an integer between ${min} and ${max}: got ${JSON.stringify(raw)}`)
  }
  return value
}

/**
 * 解析可选 API 负载阶段的配置。默认关闭，只有字面量 true（大小写不敏感）才启用。
 *
 * 未设置的变量使用默认值；显式设置但越界/非整数时立即失败，即使负载阶段处于关闭状态。
 */
export function parseApiLoadConfig(env = process.env) {
  const enabled = env.EVAL_ENABLE_API_LOAD !== undefined
    && String(env.EVAL_ENABLE_API_LOAD).trim().toLowerCase() === 'true'
  const concurrency = env.EVAL_API_CONCURRENCY === undefined
    ? DEFAULT_CONCURRENCY
    : integerInRange(env.EVAL_API_CONCURRENCY, 'EVAL_API_CONCURRENCY', 1, MAX_CONCURRENCY)
  const requestsPerEndpoint = env.EVAL_API_LOAD_REQUESTS === undefined
    ? DEFAULT_REQUESTS_PER_ENDPOINT
    : integerInRange(env.EVAL_API_LOAD_REQUESTS, 'EVAL_API_LOAD_REQUESTS', 1, MAX_REQUESTS_PER_ENDPOINT)
  return { enabled, concurrency, requestsPerEndpoint }
}

/**
 * 全局 API 负载并发池：同一阶段内所有负载请求共享一个上限。
 *
 * 任务在调用 run 时同步占位，因此同时发起的 N 个任务会真正达到 min(N, concurrency) 的
 * 在途数，而不是被 Promise 调度串行成 1。maxObserved 记录实际达到的峰值。
 */
export function createLoadRunner({ concurrency }) {
  if (!Number.isInteger(concurrency) || concurrency < 1) {
    throw new Error('load runner concurrency must be a positive integer')
  }
  let inFlight = 0
  let maxObserved = 0
  const waiters = []

  async function acquire() {
    while (inFlight >= concurrency) {
      await new Promise((resolve) => waiters.push(resolve))
    }
    inFlight += 1
    if (inFlight > maxObserved) maxObserved = inFlight
  }

  function release() {
    inFlight -= 1
    const next = waiters.shift()
    if (next) next()
  }

  async function run(task) {
    await acquire()
    try {
      return await task()
    }
    finally {
      release()
    }
  }

  return {
    run,
    snapshot: () => ({ concurrency, inFlight, maxObserved })
  }
}

/**
 * 服务端正常 EOF 且 predicate 仍未命中时，是否还有预算做一次有界重挂。
 * matched 非空表示已命中，永不重试；余额 <= 0 表示用完，永不重试。
 */
export function shouldRetrySseEof(matched, eofRetryRemaining, terminalSeen = false) {
  return matched === null && terminalSeen !== true
    && Number.isInteger(eofRetryRemaining) && eofRetryRemaining > 0
}

/** 帧的稳定文本形态：type:name:seq，缺失字段用 - 占位。 */
export function formatSseFrame(frame) {
  const type = frame?.type ?? 'unknown'
  const name = frame?.name ?? '-'
  const seq = frame?.seq ?? '-'
  return `${type}:${name}:${seq}`
}

/** 依次格式化累计帧，供 EOF/重挂失败文本取证。 */
export function formatSseFrames(frames) {
  return (Array.isArray(frames) ? frames : []).map(formatSseFrame).join(',')
}

function roundToTwo(value) {
  return Math.round(value * 100) / 100
}

/**
 * 配对 DELETE 的计划：只对“已成功创建”的 ID 发删除，未创建的空位记为 skipped。
 *
 * configured=成功创建的 ID 数（也是实际要发起的 DELETE 数量）；requested 由调用方在执行时
 * 按 configured 递增。零目标返回 SKIPPED，表示这一条没有可执行的删除，而不是通过或失败。
 */
export function planPairedDeletes(createdIds, requestedCount) {
  if (!Number.isInteger(requestedCount) || requestedCount < 0) {
    throw new Error('requested count must be a non-negative integer')
  }
  const targets = (Array.isArray(createdIds) ? createdIds : [])
    .filter((id) => typeof id === 'string' && id.length > 0)
  return {
    targets,
    configured: targets.length,
    skipped: Math.max(0, requestedCount - targets.length),
    status: targets.length === 0 ? 'SKIPPED' : 'READY'
  }
}

/**
 * 延迟统计。p50/p95 使用 nearest-rank（ceil(p/100 * n) - 1），返回真实样本值；
 * mean 保留两位小数。非有限数值被忽略；空样本的所有统计量为 null。
 */
export function summarizeLatencies(values) {
  const samples = (values ?? [])
    .filter((value) => typeof value === 'number' && Number.isFinite(value))
    .sort((left, right) => left - right)
  if (samples.length === 0) {
    return { count: 0, min: null, max: null, mean: null, p50: null, p95: null }
  }
  const nearestRank = (percentile) => {
    const rank = Math.ceil((percentile / 100) * samples.length)
    const index = Math.min(samples.length - 1, Math.max(0, rank - 1))
    return samples[index]
  }
  const sum = samples.reduce((total, value) => total + value, 0)
  return {
    count: samples.length,
    min: samples[0],
    max: samples[samples.length - 1],
    mean: roundToTwo(sum / samples.length),
    p50: nearestRank(50),
    p95: nearestRank(95)
  }
}
