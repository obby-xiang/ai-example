/**
 * 会话号收窄的精度契约。
 *
 * <p>number 分支只收安全整数：后端 long 若以 JSON 数字下发且超过 2^53-1，`JSON.parse`
 * 已经把值舍成别的整数，此时宁可返回 ''（不发请求）也不能把失真的号当会话号发出去。
 * 十进制字符串分支是长整的载体，必须原样保留。
 */
import { describe, expect, it } from 'vitest'
import { normalizeSessionId } from '@/utils/ai-identity'

describe('normalizeSessionId 精度契约', () => {
  it('number 分支只收安全整数，已失真的大 long 一律拒绝', () => {
    expect(normalizeSessionId(1001)).toBe('1001')
    expect(normalizeSessionId(Number.MAX_SAFE_INTEGER)).toBe('9007199254740991')
    expect(normalizeSessionId(Number.MAX_SAFE_INTEGER + 1)).toBe('')
    expect(normalizeSessionId(Number.MAX_SAFE_INTEGER + 2)).toBe('')

    // 9223372036854775807 经 JSON.parse 后变成 9223372036854776000：不是真号，必须拒绝。
    const parsed = JSON.parse('{"sessionId":9223372036854775807}') as { sessionId: number }
    expect(Number.isSafeInteger(parsed.sessionId)).toBe(false)
    expect(String(parsed.sessionId)).not.toBe('9223372036854775807')
    expect(normalizeSessionId(parsed.sessionId)).toBe('')
  })

  it('十进制字符串原样保留，超长也不改写', () => {
    expect(normalizeSessionId('1001')).toBe('1001')
    expect(normalizeSessionId('9223372036854775807')).toBe('9223372036854775807')
    expect(normalizeSessionId('0')).toBe('')
    expect(normalizeSessionId('01')).toBe('')
  })
})
