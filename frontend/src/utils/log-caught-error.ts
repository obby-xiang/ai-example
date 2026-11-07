import { sanitizeUrl } from '@/utils/url-sanitize'

const LOG_DEDUP_INTERVAL_MS = 5000
const LOG_KEY_LIMIT = 512
const recentLogKeys = new Map<string, number>()

interface ErrorLogProjection {
  name: string
  stack?: string
  code?: string | number
  status?: number
}

function sanitizeStack(stack: string): string | undefined {
  const frames = stack.split(/\r?\n/).filter((line) => /^\s*at\s/.test(line) || /^\s*[\w$.\[\]]+@/.test(line))
  if (frames.length === 0) {
    return undefined
  }
  return frames.map((frame) => frame
    .replace(/file:\/\/\S+/g, '[path]')
    .replace(/https?:\/\/[^\s)]+/g, (url) => sanitizeUrl(url))
    .replace(/[A-Za-z]:[\\/][^\s):]*/g, '[path]')
    .replace(/(^|[\s(])\/(?:[^/\s):]+\/)+[^\s):]*/g, (_match, prefix: string) => `${prefix}[path]`)
  ).join('\n')
}

function errorLogProjection(error: unknown): ErrorLogProjection {
  const source = error !== null && typeof error === 'object'
    ? error as { name?: unknown; stack?: unknown; code?: unknown; status?: unknown; response?: { status?: unknown } }
    : null
  const name = typeof source?.name === 'string' && source.name !== ''
    ? source.name
    : error instanceof Error && error.name !== '' ? error.name : typeof error
  const code = typeof source?.code === 'string' || typeof source?.code === 'number' ? source.code : undefined
  const rawStatus = source?.status ?? source?.response?.status
  const status = typeof rawStatus === 'number' ? rawStatus : undefined
  const stack = typeof source?.stack === 'string' ? sanitizeStack(source.stack) : undefined
  return {
    name,
    ...(stack === undefined ? {} : { stack }),
    ...(code === undefined ? {} : { code }),
    ...(status === undefined ? {} : { status })
  }
}

function rememberLogKey(key: string, now: number): void {
  recentLogKeys.delete(key)
  recentLogKeys.set(key, now)
  if (recentLogKeys.size > LOG_KEY_LIMIT) {
    const oldest = recentLogKeys.keys().next().value
    if (oldest !== undefined) {
      recentLogKeys.delete(oldest)
    }
  }
}

export function logCaughtError(error: unknown, key?: string): void {
  const projection = errorLogProjection(error)
  const stableKey = key ?? `${projection.name}:${projection.code ?? ''}:${projection.status ?? ''}`
  const now = Date.now()
  const previous = recentLogKeys.get(stableKey)
  if (previous !== undefined && now >= previous && now - previous < LOG_DEDUP_INTERVAL_MS) {
    return
  }
  rememberLogKey(stableKey, now)
  console.error('[ui] caught error', projection)
}
