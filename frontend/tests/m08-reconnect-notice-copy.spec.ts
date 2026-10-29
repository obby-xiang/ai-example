/**
 * M08 · R2（N1）· 重连通告文案不得带过期倒计时。
 *
 * 背景：C1 把 `onReconnect(attempt, delayMs)` 的触发点从"排期当刻"移到了"退避到点、即将 open
 * 新连接时"。回调参数没变（`{seconds, attempt}` 保留兼容），但文案仍写死
 * "{seconds} 秒后重试 / retrying in {seconds}s" —— 用户在**正在重连的这一刻**看到的却是
 * 一个还没走完的倒计时（实际上倒计时早已结束），既过时又误导。
 *
 * 本用例钉住的契约：
 * 1. zh/en 两份 `ai.notice.reconnect` 与 `ai.notice.resumeRetry` 都**不出现**"N 秒后 / in Ns"
 *    这类未来时表述（不得显示虚假等待）；
 * 2. 尝试次数（`attempt`）仍在文案里 —— 用户要知道"这是第几次"；
 * 3. 真机 E2E 的提示正则 `第 ?[1-4] ?次重试|连接中断` 仍能匹配（不放宽、不改用例）。
 */
import { describe, expect, it } from 'vitest'
import { applyLocale, i18n } from '@/locales'

/** 真机 `frontend/e2e/15-mdo.spec.ts` E2E-M08 对提示文案的断言（逐字照抄，不得放宽）。 */
const E2E_NOTICE_PATTERN = /第 ?[1-4] ?次重试|连接中断/

const KEYS = ['ai.notice.reconnect', 'ai.notice.resumeRetry'] as const

describe('M08 · 重连通告文案（不带过期倒计时）', () => {
  it('onReconnect 已移到"到点"发出 ⇒ zh/en 四个文案都不再承诺 N 秒后重试', () => {
    const args = { seconds: 8, attempt: 2 }

    applyLocale('zh-CN')
    for (const key of KEYS) {
      const text = i18n.global.t(key, args)
      expect(text, `${key}(zh) 不得出现未来时倒计时`).not.toMatch(/秒后/)
      expect(text, `${key}(zh) 不得残留占位符`).not.toContain('{seconds}')
      expect(text, `${key}(zh) 仍要给出尝试次数`).toContain('2')
    }
    expect(i18n.global.t('ai.notice.reconnect', args)).toMatch(E2E_NOTICE_PATTERN)
    expect(i18n.global.t('ai.notice.resumeRetry', args)).toMatch(E2E_NOTICE_PATTERN)

    applyLocale('en-US')
    for (const key of KEYS) {
      const text = i18n.global.t(key, args)
      expect(text, `${key}(en) 不得出现未来时倒计时`).not.toMatch(/\bin \d+s\b/)
      expect(text, `${key}(en) 不得残留占位符`).not.toContain('{seconds}')
      expect(text, `${key}(en) 仍要给出尝试次数`).toContain('2')
    }
    expect(i18n.global.t('ai.notice.reconnect', args)).toContain('reconnecting now')
    expect(i18n.global.t('ai.notice.resumeRetry', args)).toContain('retrying now')
  })
})
