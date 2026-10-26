/**
 * 批次2 前端展示层 · 错误卡（AR-05 / AR-09 / AR-13）：
 *
 * 口径变化（相对 R-165 S5c 的「派生码削回基础码」）：
 * 1. **码面不坍缩**：`card.code` 逐字保留完整码，新增 `baseCode` / `derived` 供诊断与分派；
 * 2. **两层文案**：`summary` = 用户向短句（可读、随派生后缀变），`detail` = 后端原文，
 *    只作为「诊断详情」的载荷，不再当作首屏正文；
 * 3. `title/guide/action` 仍按基础码族取，但派生后缀会在标题追加短的现场标签，三态不再共用一句。
 *
 * 本文件只有第一个用例是聚焦 RED/GREEN 用例；其余用例只写不跑（批次2 纪律）。
 */
import { describe, expect, it } from 'vitest'
import { applyLocale } from '@/locales'
import { baseErrorCode, buildErrorCard } from '@/utils/error-card'

describe('批次2 · 错误卡派生码与两层文案', () => {
  it('keeps the full derived code and layers user copy above diagnostic detail', () => {
    applyLocale('zh-CN')
    const base = buildErrorCard('UPSTREAM_STREAM_INCOMPLETE', '上游流提前结束')
    const derived = buildErrorCard(
      'UPSTREAM_STREAM_INCOMPLETE_AFTER_TOOL_SIDE_EFFECT',
      '上游流提前结束'
    )

    expect(base).not.toBeNull()
    expect(derived).not.toBeNull()

    // 1) 码面不坍缩：完整码、基础码、派生语义三者都在卡上
    expect(derived?.code).toBe('UPSTREAM_STREAM_INCOMPLETE_AFTER_TOOL_SIDE_EFFECT')
    expect(derived?.baseCode).toBe('UPSTREAM_STREAM_INCOMPLETE')
    expect(baseErrorCode(derived?.code ?? '')).toBe('UPSTREAM_STREAM_INCOMPLETE')
    expect(derived?.derived).toBe('tool-side-effect')

    // 2) 三态不再共用同一句：派生卡标题/摘要有自己的现场语义
    expect(derived?.title).not.toBe(base?.title)
    expect(derived?.title).toContain('已执行工具')
    expect(derived?.summary).not.toBe(base?.summary)
    expect(derived?.summary).toContain('工具')

    // 3) 用户向与诊断分层：detail 仍是后端原文（不翻译、不加工），summary 是人类短句
    expect(derived?.detail).toBe('上游流提前结束')
    expect(derived?.summary).not.toBe(derived?.detail)
    expect(derived?.guide).toBe(base?.guide)
  })

  it('marks the AFTER_PARTIAL suffix with the incomplete-content wording', () => {
    applyLocale('zh-CN')
    const card = buildErrorCard('UPSTREAM_STREAM_INCOMPLETE_AFTER_PARTIAL', '缺终帧')
    expect(card?.derived).toBe('partial')
    expect(card?.baseCode).toBe('UPSTREAM_STREAM_INCOMPLETE')
    expect(card?.title).toContain('内容不完整')
    expect(card?.summary).toContain('不完整')
    expect(card?.detail).toBe('缺终帧')
  })

  it('keeps dedicated alert codes out of the inline card, even with a derived suffix', () => {
    expect(buildErrorCard('SESSION_BUSY', 'busy')).toBeNull()
    expect(buildErrorCard('SESSION_BUSY_AFTER_PARTIAL', 'busy')).toBeNull()
  })

  it('localizes the user-facing summary but never translates the diagnostic detail', () => {
    applyLocale('en-US')
    const card = buildErrorCard('UPSTREAM_STREAM_INCOMPLETE_AFTER_PARTIAL', 'upstream stream ended')
    expect(card?.title).toContain('Incomplete')
    expect(card?.summary).toContain('incomplete')
    expect(card?.detail).toBe('upstream stream ended')
    expect(card?.guide).toContain('try again')
  })
})
