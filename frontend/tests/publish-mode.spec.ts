/**
 * R-09 fix round 2 / Minor 4-5：发布模式解析的边界。
 *
 * <p>与后端 `PublishJobRunner#resolveImportMode` 对齐：空 settings ⇒ MERGE；
 * `replace` 忽略大小写；JSON 不合法 / 非字符串输入 ⇒ fail-closed（不得抛 TypeError）。
 */
import { describe, expect, it, vi } from 'vitest'

vi.mock('@/api/tasks', () => ({ tasksApi: { getTask: vi.fn() } }))

import { parsePublishMode } from '@/utils/publish-mode'

describe('parsePublishMode：服务端 settingsJson → 发布模式', () => {
  it('空值 / 空白 ⇒ MERGE（后端未配置的默认语义）', () => {
    expect(parsePublishMode(null)).toEqual({ ok: true, mode: 'MERGE' })
    expect(parsePublishMode(undefined)).toEqual({ ok: true, mode: 'MERGE' })
    expect(parsePublishMode('')).toEqual({ ok: true, mode: 'MERGE' })
    expect(parsePublishMode('   ')).toEqual({ ok: true, mode: 'MERGE' })
  })

  it('REPLACE / 小写 replace ⇒ REPLACE（与后端 equalsIgnoreCase 对齐）', () => {
    expect(parsePublishMode('{"importMode":"REPLACE"}')).toEqual({ ok: true, mode: 'REPLACE' })
    expect(parsePublishMode('{"importMode":"replace"}')).toEqual({ ok: true, mode: 'REPLACE' })
    expect(parsePublishMode('{"importMode":"RePlAcE"}')).toEqual({ ok: true, mode: 'REPLACE' })
  })

  it('MERGE / 缺失 / 非字符串 importMode ⇒ MERGE', () => {
    expect(parsePublishMode('{"importMode":"MERGE"}')).toEqual({ ok: true, mode: 'MERGE' })
    expect(parsePublishMode('{}')).toEqual({ ok: true, mode: 'MERGE' })
    expect(parsePublishMode('{"importMode":null}')).toEqual({ ok: true, mode: 'MERGE' })
  })

  it('JSON 不合法 ⇒ fail-closed；合法但非对象 JSON ⇒ 后端同口径 MERGE', () => {
    expect(parsePublishMode('{not-json').ok).toBe(false)
    expect(parsePublishMode('[]')).toEqual({ ok: true, mode: 'MERGE' })
  })

  it('非字符串 settingsJson ⇒ fail-closed，不抛 TypeError', () => {
    expect(() => parsePublishMode(123 as unknown as string)).not.toThrow()
    expect(parsePublishMode(123 as unknown as string).ok).toBe(false)
    expect(parsePublishMode({ importMode: 'REPLACE' } as unknown as string).ok).toBe(false)
  })
})
