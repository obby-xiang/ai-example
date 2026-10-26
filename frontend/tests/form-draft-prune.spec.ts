/**
 * 批次6B（N10）：表单草稿孤儿键清理只按前缀 + 在场白名单，**禁止** `sessionStorage.clear()`。
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { FORM_DRAFT_PREFIX, formDraftKey, pruneFormDrafts } from '@/utils/form-draft'

beforeEach(() => {
  sessionStorage.clear()
})

describe('pruneFormDrafts', () => {
  it('只删 draft 前缀且不在白名单的键，返回删除数', () => {
    sessionStorage.setItem(formDraftKey('a'), '{}')
    sessionStorage.setItem(formDraftKey('b'), '{}')
    sessionStorage.setItem('business', 'keep')
    sessionStorage.setItem('cm.ai.form.draftx', 'not-a-draft')

    expect(pruneFormDrafts(new Set(['b']))).toBe(1)

    expect(sessionStorage.getItem(formDraftKey('a'))).toBeNull()
    expect(sessionStorage.getItem(formDraftKey('b'))).toBe('{}')
    expect(sessionStorage.getItem('business')).toBe('keep')
    expect(sessionStorage.getItem('cm.ai.form.draftx')).toBe('not-a-draft')
  })

  it('空白名单 ⇒ 清光本页签草稿，但绝不调用 clear()', () => {
    sessionStorage.setItem(formDraftKey('a'), '{}')
    sessionStorage.setItem(FORM_DRAFT_PREFIX + 'b', '{}')
    sessionStorage.setItem('ai-session-id', 'sid')

    expect(pruneFormDrafts([])).toBe(2)
    expect(sessionStorage.length).toBe(1)
    expect(sessionStorage.getItem('ai-session-id')).toBe('sid')
  })
})
