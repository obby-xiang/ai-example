/**
 * GF 优化批（G-G）：生成式表单的 UI chrome 文案必须两份 locale **键集逐项相等**，且不含 emoji。
 *
 * 与 `tool-i18n-coverage.spec.ts` 同一口径（那里锁的是工具名/工具文案）：本文件把 GF 卡片自己的
 * 文案面（标题/场景/按钮/占位/校验/附件）单独钉住 —— 漏翻一处就是一处中文漂到英文界面。
 */
import { describe, expect, it } from 'vitest'
import zhCN from '@/locales/zh-CN.json'
import enUS from '@/locales/en-US.json'

type Tree = Record<string, unknown>

function flatKeys(tree: unknown, prefix = ''): string[] {
  if (!tree || typeof tree !== 'object') {
    return []
  }
  const keys: string[] = []
  for (const [key, value] of Object.entries(tree as Tree)) {
    const path = prefix ? `${prefix}.${key}` : key
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      keys.push(...flatKeys(value, path))
    } else {
      keys.push(path)
    }
  }
  return keys
}

const EMOJI = /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE0F}]/u

describe('GF 文案双语覆盖', () => {
  it('ai.gf 子树在 zh-CN / en-US 键集完全一致', () => {
    const zh = flatKeys((zhCN as Tree).ai).filter((key) => key.startsWith('gf.'))
    const en = flatKeys((enUS as Tree).ai).filter((key) => key.startsWith('gf.'))
    expect(zh.length).toBeGreaterThan(15)
    expect(en.sort()).toEqual(zh.sort())
  })

  it('GF 文案无 emoji（界面禁 emoji）', () => {
    const zh = (zhCN as Tree).ai as Tree
    const en = (enUS as Tree).ai as Tree
    for (const tree of [zh, en]) {
      const gf = tree.gf as Tree
      for (const key of flatKeys(gf)) {
        const value = key.split('.').reduce<unknown>((node, part) => (node as Tree)?.[part], gf)
        expect(typeof value).toBe('string')
        expect(EMOJI.test(String(value))).toBe(false)
      }
    }
  })

  it('关键文案存在且是本地语言（不是占位/键名）', () => {
    const gf = ((zhCN as Tree).ai as Tree).gf as Tree
    expect(gf.title).toBeTypeOf('string')
    expect(gf.submit).toBeTypeOf('string')
    expect(gf.cancel).toBeTypeOf('string')
    expect((gf.attachment as Tree).choose).toBeTypeOf('string')
    expect((gf.errors as Tree).VALUE_REQUIRED_MISSING).toBeTypeOf('string')
    const gfEn = ((enUS as Tree).ai as Tree).gf as Tree
    expect(String(gfEn.submit).toLowerCase()).toContain('submit')
    expect(String(gfEn.cancel).toLowerCase()).toContain('cancel')
  })
})
