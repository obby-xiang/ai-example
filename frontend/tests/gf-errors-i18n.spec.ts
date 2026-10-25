/**
 * 表单错误码的中英文案覆盖（I3）。
 *
 * `FormRenderer.issueText` 先查 `ai.gf.errors.<code>`，缺键就回落后端/本地给的中文 `message` ——
 * 英文界面因此会直接显示中文句子。本 spec 把这条回落路径封住：
 * 1. `ai.gf.errors` 两份 locale 键集相等（`gf-i18n.spec.ts` 覆盖整棵 `ai.gf`，这里单独钉错误码面）；
 * 2. 每个错误码的占位符集合一致（漏 `{label}` 会让英文文案里出现"字面量"或丢失字段名）；
 * 3. **英文值不含中文** —— 即"英文界面永不显示中文"；
 * 4. 本次新增的两个"schema 不可用"码（`FIELD_STEP_INVALID` / `FIELD_NUMBER_BOUND_INVALID`）必须存在。
 */
import { describe, expect, it } from 'vitest'
import zhCN from '@/locales/zh-CN.json'
import enUS from '@/locales/en-US.json'

const CJK = /[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]/

function errorsOf(tree: unknown): Record<string, string> {
  const root = tree as { ai?: { gf?: { errors?: Record<string, string> } } }
  return root.ai?.gf?.errors ?? {}
}

function placeholders(text: string): string[] {
  return [...text.matchAll(/\{(\w+)\}/g)].map((match) => match[1] ?? '').sort()
}

const ZH = errorsOf(zhCN)
const EN = errorsOf(enUS)

/** 新可见码（Task 7 引入）：schema 自相矛盾时回灌期下发的字段错误。 */
const NEW_CODES = ['FIELD_STEP_INVALID', 'FIELD_NUMBER_BOUND_INVALID'] as const

describe('GF 错误码双语（I3）', () => {
  it('新增两个码在中英两份 locale 都有键且非空', () => {
    for (const code of NEW_CODES) {
      expect(ZH[code], `zh-CN 缺 ${code}`).toBeTypeOf('string')
      expect(EN[code], `en-US 缺 ${code}`).toBeTypeOf('string')
      expect(ZH[code]?.trim()).not.toBe('')
      expect(EN[code]?.trim()).not.toBe('')
    }
  })

  it('英文错误文案不含中文（否则等于回落后端中文 prose）', () => {
    for (const code of NEW_CODES) {
      expect(EN[code], `en-US ${code} 必须是英文`).not.toMatch(CJK)
      expect(EN[code]).toMatch(/[A-Za-z]/)
    }
    // 回归面：整个 errors 子树都不许出现中文
    const withChinese = Object.entries(EN)
      .filter(([, value]) => CJK.test(value))
      .map(([code]) => code)
    expect(withChinese).toEqual([])
  })

  it('中文文案是本地语言（不是键名/占位）', () => {
    for (const code of NEW_CODES) {
      expect(ZH[code]).toMatch(CJK)
    }
  })

  it('中英 errors 键集与占位符一致', () => {
    expect(Object.keys(EN).sort()).toEqual(Object.keys(ZH).sort())
    for (const code of Object.keys(ZH)) {
      expect(placeholders(EN[code] ?? ''), `占位符不一致：${code}`)
        .toEqual(placeholders(ZH[code] ?? ''))
    }
  })
})
