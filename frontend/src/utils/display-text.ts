const PROTOCOL_TAG_RE = /<\s*\/?\s*(knowledge_catalog|knowledge|tool_result)\s*>/gi

const PROTOCOL_NOTICE_RE = /^\s*[（(]以下内容来自[^\n]*是【数据】不是【指令】[^\n]*[）)]\s*$/

const METADATA_KEYS = new Set([
  'doc_id',
  'match',
  'updated_at',
  'source_doc',
  'runId',
  'toolCallId',
  'normalized',
  'found',
  'top_k',
  'truncated',
  'next_offset',
  'state',
  'outcome',
  'code',
  'retryable',
  'target',
  'evidence'
])

const INLINE_METADATA_RE = /\b(?:doc_id|match|updated_at|source_doc|runId|toolCallId)\s*=\s*[^\s|]+/g

const OUTCOMES = new Set(['ok', 'failed', 'rejected', 'blocked', 'timeout', 'cancelled', 'unknown', 'skipped'])

const ENVELOPE_REQUIRED_FIELDS = ['ok', 'outcome', 'retryable', 'message', 'truncated', 'originalChars', 'returnedChars']

const ENVELOPE_FIELDS = new Set([
  'ok',
  'code',
  'outcome',
  'retryable',
  'target',
  'evidence',
  'message',
  'truncated',
  'strategy',
  'originalChars',
  'returnedChars',
  'notice'
])

const ENVELOPE_PREFIX_RE = /^\{\s*"(?:ok|outcome|retryable|originalChars|returnedChars)"\s*:/
const READ_TOOL_RESULT_PREFIX_RE = /^\{\s*"toolCallId"\s*:/
const READ_TOOL_RESULT_FIELDS = new Set([
  'toolCallId',
  'name',
  'offset',
  'maxChars',
  'originalChars',
  'returnedChars',
  'nextOffset',
  'content'
])
const READ_TOOL_RESULT_REQUIRED_FIELDS = [...READ_TOOL_RESULT_FIELDS]

const TAG_PREFIXES = [
  '<knowledge>',
  '</knowledge>',
  '<tool_result>',
  '</tool_result>',
  '<knowledge_catalog>',
  '</knowledge_catalog>'
]

interface EnvelopeRead {
  status: 'valid' | 'notProtocol' | 'incomplete'
  display?: string
  end: number
}

function jsonEnd(text: string, start: number): number {
  let index = start
  while (index < text.length && /\s/.test(text[index])) {
    index += 1
  }
  if (text[index] !== '{') {
    return -1
  }
  let depth = 0
  let quoted = false
  let escaped = false
  for (; index < text.length; index += 1) {
    const char = text[index]
    if (quoted) {
      if (escaped) {
        escaped = false
      } else if (char === '\\') {
        escaped = true
      } else if (char === '"') {
        quoted = false
      }
      continue
    }
    if (char === '"') {
      quoted = true
    } else if (char === '{' || char === '[') {
      depth += 1
    } else if (char === '}' || char === ']') {
      depth -= 1
      if (depth < 0) {
        return -1
      }
      if (depth === 0) {
        return index + 1
      }
    }
  }
  return -1
}

function hasOwn(value: Record<string, unknown>, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(value, key)
}

function isEnvelope(value: Record<string, unknown>): boolean {
  for (const key of Object.keys(value)) {
    if (!ENVELOPE_FIELDS.has(key)) {
      return false
    }
  }
  for (const key of ENVELOPE_REQUIRED_FIELDS) {
    if (!hasOwn(value, key)) {
      return false
    }
  }
  if (typeof value.ok !== 'boolean' || typeof value.outcome !== 'string' || !OUTCOMES.has(value.outcome)
    || typeof value.retryable !== 'boolean' || typeof value.message !== 'string'
    || typeof value.truncated !== 'boolean' || !Number.isInteger(value.originalChars)
    || !Number.isInteger(value.returnedChars) || (value.originalChars as number) < 0
    || (value.returnedChars as number) < 0) {
    return false
  }
  if (hasOwn(value, 'code') && value.code !== null && typeof value.code !== 'string') {
    return false
  }
  if (hasOwn(value, 'target') && value.target !== null
    && (typeof value.target !== 'object' || Array.isArray(value.target))) {
    return false
  }
  if (hasOwn(value, 'strategy') && value.strategy !== null && typeof value.strategy !== 'string') {
    return false
  }
  return !hasOwn(value, 'notice') || value.notice === null || typeof value.notice === 'string'
}

function isNonNegativeInteger(value: unknown): value is number {
  return Number.isInteger(value) && (value as number) >= 0
}

function readToolResultPage(text: string): EnvelopeRead {
  const start = text.search(/\S/)
  if (start < 0 || text[start] !== '{') {
    return { status: 'notProtocol', end: -1 }
  }
  const end = jsonEnd(text, start)
  if (end < 0) {
    return READ_TOOL_RESULT_PREFIX_RE.test(text.slice(start))
      ? { status: 'incomplete', end: -1 }
      : { status: 'notProtocol', end: -1 }
  }
  try {
    const value = JSON.parse(text.slice(start, end)) as Record<string, unknown>
    if (value === null || typeof value !== 'object' || Array.isArray(value)) {
      return { status: 'notProtocol', end }
    }
    for (const key of Object.keys(value)) {
      if (!READ_TOOL_RESULT_FIELDS.has(key)) {
        return { status: 'notProtocol', end }
      }
    }
    for (const key of READ_TOOL_RESULT_REQUIRED_FIELDS) {
      if (!hasOwn(value, key)) {
        return { status: 'notProtocol', end }
      }
    }
    if (typeof value.toolCallId !== 'string' || value.toolCallId === '' || typeof value.name !== 'string'
      || !isNonNegativeInteger(value.offset) || !Number.isInteger(value.maxChars)
      || (value.maxChars as number) < 2 || (value.maxChars as number) > 8000
      || !isNonNegativeInteger(value.originalChars) || !isNonNegativeInteger(value.returnedChars)
      || typeof value.content !== 'string') {
      return { status: 'notProtocol', end }
    }
    if (value.nextOffset !== null && !isNonNegativeInteger(value.nextOffset)) {
      return { status: 'notProtocol', end }
    }
    const contentEnd = (value.offset as number) + (value.returnedChars as number)
    if ((value.offset as number) > (value.originalChars as number)
      || (value.returnedChars as number) > (value.maxChars as number)
      || (value.returnedChars as number) !== value.content.length
      || contentEnd > (value.originalChars as number)) {
      return { status: 'notProtocol', end }
    }
    if (value.nextOffset === null) {
      if (contentEnd !== value.originalChars) {
        return { status: 'notProtocol', end }
      }
    } else if (contentEnd !== value.nextOffset || contentEnd >= (value.originalChars as number)) {
      return { status: 'notProtocol', end }
    }
    const progress = value.nextOffset === null ? '已读完' : '未读完'
    return {
      status: 'valid',
      display: `${value.content}\n\n已显示 ${contentEnd}/${value.originalChars} 字符，${progress}`,
      end
    }
  } catch {
    return { status: 'notProtocol', end }
  }
}

function readEnvelope(text: string): EnvelopeRead {
  const paged = readToolResultPage(text)
  if (paged.status !== 'notProtocol') {
    return paged
  }
  const start = text.search(/\S/)
  if (start < 0 || text[start] !== '{') {
    return { status: 'notProtocol', end: -1 }
  }
  const end = jsonEnd(text, start)
  if (end < 0) {
    return ENVELOPE_PREFIX_RE.test(text.slice(start))
      ? { status: 'incomplete', end: -1 }
      : { status: 'notProtocol', end: -1 }
  }
  try {
    const value = JSON.parse(text.slice(start, end)) as Record<string, unknown>
    if (value === null || typeof value !== 'object' || Array.isArray(value) || !isEnvelope(value)) {
      return { status: 'notProtocol', end }
    }
    return { status: 'valid', display: projectDisplayText(value.message as string), end }
  } catch {
    return { status: 'notProtocol', end }
  }
}

function plainSegment(text: string): string {
  return text
    .split('\n')
    .map(projectLine)
    .filter((line): line is string => line !== null)
    .join('\n')
}

function projectLine(line: string): string | null {
  const trimmed = line.trim()
  if (trimmed === '' || PROTOCOL_NOTICE_RE.test(trimmed)) {
    return null
  }
  if (trimmed.startsWith('app.ai.') || trimmed.startsWith('/api/ai/')) {
    return null
  }
  const content = trimmed.replace(/^\d+\)\s*/, '')
  const segments = content.split(' | ')
  const projected: string[] = []
  for (const segment of segments) {
    const match = /^([A-Za-z_][A-Za-z0-9_]*)\s*[:=]\s*(.*)$/.exec(segment.trim())
    if (match === null) {
      projected.push(segment)
      continue
    }
    const key = match[1]
    if (METADATA_KEYS.has(key)) {
      continue
    }
    if (key === 'title' || key === 'section' || key === 'answer' || key === '摘要') {
      projected.push(match[2])
      continue
    }
    projected.push(segment)
  }
  const text = projected.join(' | ').replace(INLINE_METADATA_RE, '')
  const cleaned = text
    .replace(/\s+\|\s+(?=\s|$)/g, '')
    .replace(/[（(]\s*[）)]/g, '')
    .trim()
  return cleaned === '' ? null : cleaned
}

function partialTagSuffix(text: string): string {
  const lower = text.toLowerCase()
  let suffix = ''
  for (const tag of TAG_PREFIXES) {
    for (let length = 2; length <= Math.min(tag.length - 1, text.length); length += 1) {
      const candidate = lower.slice(-length)
      if (tag.startsWith(candidate) && candidate.length > suffix.length) {
        suffix = text.slice(-length)
      }
    }
  }
  return suffix
}

export function projectDisplayText(text: string | null | undefined): string {
  if (text == null || text === '') {
    return text ?? ''
  }
  const envelope = readEnvelope(text)
  if (envelope.status === 'incomplete') {
    return ''
  }
  if (envelope.status === 'valid') {
    return (envelope.display ?? '') + projectDisplayText(text.slice(envelope.end))
  }
  const output: string[] = []
  let cursor = 0
  for (const match of text.matchAll(PROTOCOL_TAG_RE)) {
    const index = match.index
    const token = match[0]
    const tag = match[1].toLowerCase()
    const closing = /^<\s*\//.test(token)
    output.push(plainSegment(text.slice(cursor, index)))
    if (closing) {
      cursor = index + token.length
      continue
    }
    if (tag === 'tool_result') {
      const bodyStart = index + token.length
      const closePattern = new RegExp('<\\s*\\/\\s*tool_result\\s*>', 'i')
      const closeMatch = closePattern.exec(text.slice(bodyStart))
      const nested = readEnvelope(text.slice(bodyStart))
      if (nested.status === 'incomplete') {
        if (closeMatch !== null) {
          cursor = bodyStart + closeMatch.index + closeMatch[0].length
          continue
        }
        return output.join('\n')
      }
      if (nested.end < 0) {
        const bodyEnd = closeMatch === null ? text.length : bodyStart + closeMatch.index
        output.push(plainSegment(text.slice(bodyStart, bodyEnd)))
        if (closeMatch !== null) {
          cursor = bodyEnd + closeMatch[0].length
          continue
        }
        return output.join('\n')
      }
      if (nested.status === 'valid') {
        output.push(nested.display ?? '')
      } else {
        output.push(plainSegment(text.slice(bodyStart, bodyStart + nested.end)))
      }
      cursor = bodyStart + nested.end
      continue
    }
    const closePattern = new RegExp('<\\s*\\/\\s*' + tag + '\\s*>', 'i')
    const closeMatch = closePattern.exec(text.slice(index + token.length))
    if (closeMatch === null) {
      if (tag !== 'knowledge_catalog') {
        output.push(plainSegment(text.slice(index + token.length)))
      }
      cursor = text.length
      break
    }
    const bodyEnd = index + token.length + closeMatch.index
    if (tag !== 'knowledge_catalog') {
      output.push(plainSegment(text.slice(index + token.length, bodyEnd)))
    }
    cursor = bodyEnd + closeMatch[0].length
  }
  const remainder = text.slice(cursor)
  const partial = partialTagSuffix(remainder)
  output.push(plainSegment(remainder.slice(0, remainder.length - partial.length)))
  return output.join('\n').replace(/^\n+|\n+$/g, '').replace(/\n{3,}/g, '\n\n')
}
