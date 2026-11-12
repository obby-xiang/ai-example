import { describe, expect, it } from 'vitest'
import { chunkGraphemes, takeGraphemePrefix } from '@/utils/graphemes'

const FAMILY = '👨‍👩‍👧‍👦'
const SKIN = '👍🏽'
const FLAG = '🇨🇳'
const KEYCAP = '1️⃣'
const TAG = '🏴󠁧󠁢󠁥󠁮󠁧󠁿'

describe('grapheme prefix and chunks', () => {
  it('keeps CJK and combines acute', () => {
    expect(takeGraphemePrefix('你好啊', 2)).toEqual({ text: '你好', nextOffset: 2, omitted: true })
    expect(takeGraphemePrefix('e\u0301x', 1)).toEqual({ text: '', nextOffset: 2, omitted: true })
  })

  it('keeps the whole family ZWJ sequence', () => {
    expect(takeGraphemePrefix(`ab${FAMILY}x`, 3)).toEqual({ text: 'ab', nextOffset: 2, omitted: true })
  })

  it('keeps emoji skin tone modifiers', () => {
    expect(takeGraphemePrefix(`ab${SKIN}x`, 3)).toEqual({ text: 'ab', nextOffset: 2, omitted: true })
  })

  it('keeps regional indicator flags', () => {
    expect(takeGraphemePrefix(`ab${FLAG}x`, 3)).toEqual({ text: 'ab', nextOffset: 2, omitted: true })
  })

  it('keeps keycap sequences', () => {
    expect(takeGraphemePrefix(`ab${KEYCAP}x`, 3)).toEqual({ text: 'ab', nextOffset: 2, omitted: true })
  })

  it('keeps emoji tag sequences', () => {
    expect(takeGraphemePrefix(`ab${TAG}x`, 3)).toEqual({ text: 'ab', nextOffset: 2, omitted: true })
  })

  it('keeps CRLF as one cluster', () => {
    expect(takeGraphemePrefix('ab\r\nx', 3)).toEqual({ text: 'ab', nextOffset: 2, omitted: true })
  })

  it('reports an overlong cluster and resumes after it', () => {
    const overlong = `a${'\u0301'.repeat(140)}`
    expect(takeGraphemePrefix(overlong, 4)).toEqual({ text: '', nextOffset: overlong.length, omitted: true })
    expect(chunkGraphemes(`${overlong}Z`, 4)).toEqual([
      { text: '', nextOffset: overlong.length, omitted: true },
      { text: 'Z', nextOffset: overlong.length + 1, omitted: false }
    ])
  })

  it('preserves ASCII text and exact chunk boundaries', () => {
    expect(takeGraphemePrefix('abcdef', 6)).toEqual({ text: 'abcdef', nextOffset: 6, omitted: false })
    expect(chunkGraphemes('abcdef', 2)).toEqual([
      { text: 'ab', nextOffset: 2, omitted: true },
      { text: 'cd', nextOffset: 4, omitted: true },
      { text: 'ef', nextOffset: 6, omitted: false }
    ])
  })
})
