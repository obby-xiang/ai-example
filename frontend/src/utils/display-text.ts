import { markdownCodeRanges } from '@/utils/markdown'
import { logCaughtError } from '@/utils/log-caught-error'
import { translate } from '@/composables/useCopy'


const PROTOCOL_TAG_RE = /<\s*\/?\s*(knowledge_catalog|knowledge|tool_result)\s*>/gi

export function unicodeCharacterCount(value: string): number {
  return Array.from(value).length
}

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

function containsOwn(value: Record<string, unknown>, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(value, key)
}

function isEnvelopeScalarShape(value: Record<string, unknown>): boolean {
  return typeof value.ok === 'boolean' && typeof value.outcome === 'string' && OUTCOMES.has(value.outcome)
    && typeof value.retryable === 'boolean' && typeof value.message === 'string'
    && typeof value.truncated === 'boolean' && Number.isInteger(value.originalChars)
    && Number.isInteger(value.returnedChars) && (value.originalChars as number) >= 0
    && (value.returnedChars as number) >= 0
}

function isEnvelopeOptionalFieldValid(value: Record<string, unknown>): boolean {
  if (containsOwn(value, 'code') && value.code !== null && typeof value.code !== 'string') {
    return false
  }
  if (containsOwn(value, 'target') && value.target !== null
    && (typeof value.target !== 'object' || Array.isArray(value.target))) {
    return false
  }
  if (containsOwn(value, 'strategy') && value.strategy !== null && typeof value.strategy !== 'string') {
    return false
  }
  return !containsOwn(value, 'notice') || value.notice === null || typeof value.notice === 'string'
}

function isEnvelope(value: Record<string, unknown>): boolean {
  if (!Object.keys(value).every((key) => ENVELOPE_FIELDS.has(key))) {
    return false
  }
  if (!ENVELOPE_REQUIRED_FIELDS.every((key) => containsOwn(value, key))) {
    return false
  }
  return isEnvelopeScalarShape(value) && isEnvelopeOptionalFieldValid(value)
}

function isNonNegativeInteger(value: unknown): value is number {
  return Number.isInteger(value) && (value as number) >= 0
}

function isReadToolResultShape(value: Record<string, unknown>): value is Record<string, unknown> & { content: string } {
  for (const key of Object.keys(value)) {
    if (!READ_TOOL_RESULT_FIELDS.has(key)) {
      return false
    }
  }
  for (const key of READ_TOOL_RESULT_REQUIRED_FIELDS) {
    if (!containsOwn(value, key)) {
      return false
    }
  }
  if (typeof value.toolCallId !== 'string' || value.toolCallId === '' || typeof value.name !== 'string'
    || !isNonNegativeInteger(value.offset) || !Number.isInteger(value.maxChars)
    || (value.maxChars as number) < 2 || (value.maxChars as number) > 8000
    || !isNonNegativeInteger(value.originalChars) || !isNonNegativeInteger(value.returnedChars)
    || typeof value.content !== 'string') {
    return false
  }
  return value.nextOffset === null || isNonNegativeInteger(value.nextOffset)
}

function isReadToolResultOffsetValid(value: Record<string, unknown> & { content: string }, contentEnd: number): boolean {
  if ((value.offset as number) > (value.originalChars as number)
    || (value.returnedChars as number) > (value.maxChars as number)
    || (value.returnedChars as number) !== value.content.length
    || contentEnd > (value.originalChars as number)) {
    return false
  }
  if (value.nextOffset === null) {
    return contentEnd === value.originalChars
  }
  return contentEnd === value.nextOffset && contentEnd < (value.originalChars as number)
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
    if (value === null || typeof value !== 'object' || Array.isArray(value) || !isReadToolResultShape(value)) {
      return { status: 'notProtocol', end }
    }
    const contentEnd = (value.offset as number) + (value.returnedChars as number)
    if (!isReadToolResultOffsetValid(value, contentEnd)) {
      return { status: 'notProtocol', end }
    }
    return {
      status: 'valid',
      display: translate(`ai.panel.readToolResultPage.${value.nextOffset === null ? 'complete' : 'hasMore'}`, {
        content: value.content as string,
        shown: contentEnd,
        total: value.originalChars as number
      }),
      end
    }
  } catch (error) {
      logCaughtError(error, 'utils/display-text.ts:214')
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
  } catch (error) {
      logCaughtError(error, 'utils/display-text.ts:240')
    return { status: 'notProtocol', end }
  }
}

interface MaskedCharacter {
  token: string
  source: string
}

interface MarkdownMask {
  text: string
  characters: MaskedCharacter[]
  exhausted: boolean
}

function maskToken(unavailable: Set<string>): string | null {
  for (const [first, last] of [[0xE000, 0xF8FF], [0x1000, 0xD7FF]]) {
    for (let code = first; code <= last; code += 1) {
      const token = String.fromCharCode(code)
      if (!unavailable.has(token)) {
        unavailable.add(token)
        return token
      }
    }
  }
  return null
}

function maskMarkdownCode(text: string): MarkdownMask {
  const ranges = markdownCodeRanges(text)
  if (ranges.length === 0) {
    return { text, characters: [], exhausted: false }
  }
  const unavailable = new Set(text)
  const tokens = new Map<string, string>()
  const masked = text.split('')
  for (const range of ranges) {
    let cursor = range.start
    while (cursor < range.end) {
      const char = text[cursor]
      if (char === '\\') {
        const unicode = text[cursor + 1] === 'u' && /^[0-9A-Fa-f]{4}$/.test(text.slice(cursor + 2, cursor + 6))
        cursor += unicode ? 6 : Math.min(2, range.end - cursor)
        continue
      }
      const code = char.charCodeAt(0)
      if (code >= 0x20 && code <= 0x7E) {
        let token = tokens.get(char)
        if (token === undefined) {
          const created = maskToken(unavailable)
          if (created === null) {
            return { text, characters: [], exhausted: true }
          }
          token = created
          tokens.set(char, token)
        }
        masked[cursor] = token
      }
      cursor += 1
    }
  }
  const characters = Array.from(tokens, ([source, token]) => ({ source, token }))
  return { text: masked.join(''), characters, exhausted: false }
}

function restoreMarkdownCode(text: string, characters: MaskedCharacter[]): string {
  let restored = text
  for (const character of characters) {
    restored = restored.split(character.token).join(character.source)
  }
  return restored
}

function plainSegment(text: string, protocol = false): string {
  return text
    .split('\n')
    .map((line) => projectLine(line, protocol))
    .filter((line): line is string => line !== null)
    .join('\n')
}

function projectLine(line: string, protocol: boolean): string | null {
  const trimmed = line.trim()
  if (trimmed === '' || !protocol) {
    return line
  }
  if (PROTOCOL_NOTICE_RE.test(trimmed) || trimmed.startsWith('app.ai.') || trimmed.startsWith('/api/ai/')) {
    return null
  }
  const match = /^([A-Za-z_][A-Za-z0-9_]*)\s*[:=]\s*/.exec(trimmed)
  if (match !== null && METADATA_KEYS.has(match[1])) {
    return null
  }
  return line
}

function joinDisplaySegments(segments: string[]): string {
  let text = ''
  for (const segment of segments) {
    if (segment === '') {
      continue
    }
    if (text !== '' && !text.endsWith('\n') && !segment.startsWith('\n')) {
      text += '\n'
    }
    text += segment
  }
  return text
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
  const masked = maskMarkdownCode(text)
  if (masked.exhausted) {
    return text
  }
  return restoreMarkdownCode(projectDisplayTextCore(masked.text), masked.characters)
}

interface TagProjection {
  cursor: number
  result?: string
}

function projectToolResultTag(text: string, index: number, token: string, output: string[]): TagProjection {
  const bodyStart = index + token.length
  const closeMatch = /<\s*\/\s*tool_result\s*>/i.exec(text.slice(bodyStart))
  const nested = readEnvelope(text.slice(bodyStart))
  if (nested.status === 'incomplete') {
    return closeMatch === null
      ? { cursor: bodyStart, result: joinDisplaySegments(output) }
      : { cursor: bodyStart + closeMatch.index + closeMatch[0].length }
  }
  if (nested.end < 0) {
    const bodyEnd = closeMatch === null ? text.length : bodyStart + closeMatch.index
    output.push(plainSegment(text.slice(bodyStart, bodyEnd), true))
    return closeMatch === null
      ? { cursor: bodyEnd, result: joinDisplaySegments(output) }
      : { cursor: bodyEnd + closeMatch[0].length }
  }
  output.push(nested.status === 'valid' ? nested.display ?? '' : plainSegment(text.slice(bodyStart, bodyStart + nested.end), true))
  return { cursor: bodyStart + nested.end }
}

function projectGenericTag(text: string, index: number, token: string, tag: string, output: string[]): TagProjection {
  const closeMatch = new RegExp('<\\s*\\/\\s*' + tag + '\\s*>', 'i').exec(text.slice(index + token.length))
  if (closeMatch === null) {
    if (tag !== 'knowledge_catalog') {
      output.push(plainSegment(text.slice(index + token.length), true))
    }
    return { cursor: text.length }
  }
  const bodyEnd = index + token.length + closeMatch.index
  if (tag !== 'knowledge_catalog') {
    output.push(plainSegment(text.slice(index + token.length, bodyEnd), true))
  }
  return { cursor: bodyEnd + closeMatch[0].length }
}

function projectDisplayTextCore(text: string): string {
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
    const projected = tag === 'tool_result'
      ? projectToolResultTag(text, index, token, output)
      : projectGenericTag(text, index, token, tag, output)
    if (projected.result !== undefined) {
      return projected.result
    }
    cursor = projected.cursor
  }
  const remainder = text.slice(cursor)
  const partial = partialTagSuffix(remainder)
  output.push(plainSegment(remainder.slice(0, remainder.length - partial.length)))
  return joinDisplaySegments(output)
}
