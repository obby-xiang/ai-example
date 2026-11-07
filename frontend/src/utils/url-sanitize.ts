const SOURCE_PATH_RE = /(?:\/src\/|\.(?:ts|tsx|vue|jsx?|mjs|cjs|css|html)(?:$|[?#]))/i
const SCHEME_RE = /^[a-z][a-z\d+.-]*:/i

function normalizeHostname(hostname: string): string {
  return hostname.toLowerCase().replace(/^\[|\]$/g, '').replace(/\.$/, '')
}

function ipv4Octets(host: string): number[] | null {
  const parts = host.split('.')
  if (parts.length !== 4 || parts.some((part) => !/^\d{1,3}$/.test(part))) {
    return null
  }
  const octets = parts.map(Number)
  return octets.every((part) => part >= 0 && part <= 255) ? octets : null
}

function isInternalHostname(hostname: string): boolean {
  const host = normalizeHostname(hostname)
  if (host === 'localhost' || host === '::' || host === '::1' || host === '0.0.0.0' || host.endsWith('.local')) {
    return true
  }
  const octets = ipv4Octets(host)
  if (octets !== null) {
    const [first, second] = octets
    return first === 127 || first === 10 || (first === 169 && second === 254)
      || (first === 192 && second === 168) || (first === 172 && second >= 16 && second <= 31)
  }
  if (!host.includes(':')) {
    return false
  }
  if (host.startsWith('fc') || host.startsWith('fd')) {
    return true
  }
  const firstHextet = Number.parseInt(host.split(':')[0], 16)
  return !Number.isNaN(firstHextet) && firstHextet >= 0xfe80 && firstHextet <= 0xfebf
}

/** URL 仅保留非内部 origin + path；userinfo/query/hash 和源码路径不得进入日志。 */
export function sanitizeUrl(raw: string): string {
  const value = raw.trim()
  if (value === '') {
    return ''
  }
  if (SOURCE_PATH_RE.test(value) || /^file:\/\//i.test(value)) {
    return '[source-path]'
  }
  if (/^[a-z]:[\\/]/i.test(value)) {
    return '[path]'
  }
  try {
    const parsed = new URL(value)
    if (isInternalHostname(parsed.hostname)) {
      return '[internal-origin]'
    }
    const host = normalizeHostname(parsed.hostname)
    const port = parsed.port === '' ? '' : `:${parsed.port}`
    return `${parsed.protocol}//${host}${port}${parsed.pathname}`
  } catch {
    if (SCHEME_RE.test(value) || value.startsWith('//')) {
      return '[url]'
    }
    return value.split(/[?#]/, 1)[0]
  }
}
