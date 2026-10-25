/**
 * i18n L-69：**脚本侧文案面**（模板侧之外的那一半）。
 *
 * <p>覆盖面 = 四类「拿不到组件 `t`」的文案出口：
 * ① `utils/blocks.ts` 的工具卡状态词（19 串）；
 * ② `utils/error-card.ts` 的错误卡三型标题/引导/按钮；
 * ③ `stores/ai.ts` 的 `notice`（`code + args` 结构化形态 + `noticeText` 渲染读点）；
 * ④ `stores/workspace.ts` 的双受众面（`recentActions.summary` 仍是**送模型**的中文，
 *    界面 chip 走 `display` 结构化形态）。
 *
 * <p>另锁两条口径：`ai-settings-copy` 的键表取值随语言；后端 `message` 原文**不被翻译**（数据不是文案）。
 *
 * <p>C5 红线的反向断言在 `workspace-audience.spec.ts`（切 en 后送模型的串仍中文）—— 本文件不重复。
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { applyLocale } from '@/locales'
import { translate } from '@/composables/useCopy'
import { cardStatusOf, readableValue } from '@/utils/blocks'
import { buildErrorCard } from '@/utils/error-card'
import { describeAiConfigError, useAiSettingsCopy } from '@/constants/ai-settings-copy'
import { useAiStore } from '@/stores/ai'
import { useWorkspaceStore } from '@/stores/workspace'
import { ApiError } from '@/api/http'
import { ErrorCode } from '@/types/api'
import type { ToolPart, ToolRunStatus } from '@/types/ai'

function makeTool(overrides: Partial<ToolPart> = {}): ToolPart {
  return {
    kind: 'tool',
    seg: 0,
    toolCallId: 'tc-l69',
    name: 'probe.tool',
    kind2: 'BACKEND',
    status: 'succeeded',
    requiresAction: false,
    ...overrides
  } as ToolPart
}

beforeEach(() => {
  setActivePinia(createPinia())
  applyLocale('zh-CN')
})

describe('L-69-① 工具卡状态词（blocks.ts）随语言', () => {
  it('普通段状态：zh 逐字不变，en 为英文', () => {
    const cases: Array<[ToolRunStatus, string, string]> = [
      ['running', '调用中…', 'Running…'],
      ['succeeded', '已成功', 'Succeeded'],
      ['failed', '执行失败', 'Failed'],
      ['rejected', '已拒绝', 'Rejected'],
      ['expired', '会话已失效', 'Session expired'],
      ['cancelled', '已取消', 'Cancelled'],
      ['unknown', '结局未知（未确认成功/失败）', 'Outcome unknown (not confirmed)'],
      ['skipped', '已跳过', 'Skipped']
    ]
    for (const [status, zh, en] of cases) {
      const part = makeTool({ status })
      expect(cardStatusOf(part).label).toBe(zh)
      applyLocale('en-US')
      expect(cardStatusOf(part).label).toBe(en)
      applyLocale('zh-CN')
    }
  })

  it('blocked 的 en 文案是「超出可展示范围」而非 out of scope（语义修正）', () => {
    applyLocale('en-US')
    expect(cardStatusOf(makeTool({ status: 'blocked' })).label).toBe('Out of presentable scope')
    expect(translate('tool.status.blocked')).toBe('beyond the presentable range')
  })

  it('pending 按类型分工 + 已决只读六态', () => {
    applyLocale('en-US')
    expect(cardStatusOf(makeTool({ status: 'pending' })).label).toBe('Pending')
    expect(cardStatusOf(makeTool({ status: 'pending', kind2: 'CONFIRM', requiresAction: true })).label)
      .toBe('Awaiting confirmation')
    expect(cardStatusOf(makeTool({ kind2: 'CONFIRM', decision: 'approved' })).label).toBe('✓ Approved')
    expect(cardStatusOf(makeTool({ kind2: 'CONFIRM', decision: 'timeout' })).label).toBe('! Timed out')
  })

  it('值可读化（是/否/空/复杂值/顿号）随语言', () => {
    expect(readableValue(true)).toBe('是')
    expect(readableValue([])).toBe('（空）')
    expect(readableValue({ a: 1 })).toBe('（复杂值）')
    applyLocale('en-US')
    expect(readableValue(true)).toBe('Yes')
    expect(readableValue(['a', 'b'])).toBe('a, b')
    expect(readableValue([])).toBe('(empty)')
    expect(readableValue({ a: 1 })).toBe('(complex value)')
  })
})

describe('L-69-② 错误卡（error-card.ts）随语言，detail 不被翻译', () => {
  it('三型标题/引导/按钮：zh 逐字，en 英文；detail 原样', () => {
    const quota = buildErrorCard('429', '上游 429')
    expect(quota).toMatchObject({
      variant: 'quota',
      title: '请求超出配额',
      action: '稍后重试',
      detail: '上游 429'
    })
    applyLocale('en-US')
    const quotaEn = buildErrorCard('429', 'upstream 429')
    expect(quotaEn).toMatchObject({
      variant: 'quota',
      title: 'Request over quota',
      guide: 'Tip: retry later; for batch jobs, lower concurrency before resending.',
      action: 'Retry later',
      // 后端原文 = 数据，不翻译
      detail: 'upstream 429'
    })
    expect(buildErrorCard('content_filter', 'blocked')?.title).toBe('Content blocked by safety policy')
    expect(buildErrorCard('context_length_exceeded', 'too long')?.title).toBe('Context length exceeded')
    expect(buildErrorCard('AI_RUN_FAILED', 'boom')).toMatchObject({
      variant: 'generic', title: 'Something went wrong this turn', action: '', guide: ''
    })
  })

  it('专属 Alert 的码仍不出卡（行为未随文案变动）', () => {
    applyLocale('en-US')
    expect(buildErrorCard(ErrorCode.SESSION_BUSY, 'busy')).toBeNull()
  })
})

describe('L-69-③ notice：code + args 结构化，渲染按当前语言重译', () => {
  it('SESSION_BUSY 落 code，noticeText 随语言；notice 保留设定时刻文本', () => {
    const store = useAiStore()
    applyLocale('en-US')
    store.handleChatError(new ApiError('该会话已有一轮进行中', 409, ErrorCode.SESSION_BUSY, null))
    expect(store.noticeCode).toBe('ai.notice.sendFailed')
    expect(store.noticeText).toBe('This message was not sent. Please try again later.')
    // 设定时刻快照（既有单槽字符串断言仍可读）
    expect(store.notice).toBe('This message was not sent. Please try again later.')
    // 切回 zh：同一 code 重新释义
    applyLocale('zh-CN')
    expect(store.noticeText).toBe('这条消息没有发出去，请稍后再试。')
  })

  it('turnError 模板只带 message，不展示机器码（码留错误卡诊断）', () => {
    const store = useAiStore()
    applyLocale('en-US')
    store.handleFrame({ type: 'error', runId: 'r-1', code: 'UPSTREAM_STREAM_ERROR', message: 'Service is too busy' } as never)
    expect(store.noticeCode).toBe('ai.notice.turnError')
    expect(store.noticeText).toBe('Error this turn: Service is too busy')
  })

  it('后端 message 原文（数据不是文案）不翻译、不带 code', () => {
    const store = useAiStore()
    applyLocale('en-US')
    store.handleChatError(new ApiError('后端原文：服务暂不可用', 503, 'SOME_BACKEND_CODE', null))
    expect(store.noticeCode).toBeNull()
    expect(store.noticeText).toBe('后端原文：服务暂不可用')
  })

  it('清空通知槽同时清 code（否则 getter 会把旧模板重新翻出来）', () => {
    const store = useAiStore()
    store.handleChatError(new ApiError('x', 409, ErrorCode.SESSION_BUSY, null))
    expect(store.noticeText).not.toBeNull()
    store.notice = null
    expect(store.noticeText).toBeNull()
  })
})

describe('L-69-④ 工作区事件：summary 送模型（中文），display 给界面（随语言）', () => {
  it('enterPage：recentActions 仍中文，display 渲染英文；contextSummary 随语言', () => {
    const workspace = useWorkspaceStore()
    applyLocale('en-US')
    workspace.enterPage({ pageId: 'tasks', page: 'tasks' })
    const record = workspace.recentActions.at(-1)
    // C5：送模型那一份不随 UI 语言变
    expect(record?.summary).toBe('进入任务中心')
    expect(translate(record?.display?.code ?? '', record?.display?.args)).toBe('Entered Task Center')
    expect(workspace.contextSummary).toBe('Task Center')
    applyLocale('zh-CN')
    expect(workspace.contextSummary).toBe('任务中心')
  })

  it('选中集 / 绑定任务 / 导入模式的 display 插值', () => {
    const workspace = useWorkspaceStore()
    applyLocale('en-US')
    workspace.setTaskId(12)
    workspace.setSelectedDefs(['CURRENCY', 'TAX_RATE'])
    workspace.setImportMode('MERGE')
    const last = (): string => {
      const record = workspace.recentActions.at(-1)
      return translate(record?.display?.code ?? '', record?.display?.args)
    }
    expect(last()).toBe('Import mode set to merge (incremental)')
    expect(workspace.recentActions.at(-2)?.summary).toBe('已选择配置项：CURRENCY、TAX_RATE')
    expect(translate(
      workspace.recentActions.at(-2)?.display?.code ?? '',
      workspace.recentActions.at(-2)?.display?.args
    )).toBe('Selected definitions: CURRENCY、TAX_RATE')
    expect(workspace.recentActions.at(-3)?.summary).toBe('绑定任务 #12')
  })
})

describe('L-69-⑤ AI 设置文案键表（ai-settings-copy）随语言', () => {
  it('useAiSettingsCopy 的属性是 getter（切语言即变）', () => {
    const copy = useAiSettingsCopy()
    expect(copy.title).toBe('AI 设置')
    expect(copy.saved).toBe('已保存')
    applyLocale('en-US')
    expect(copy.title).toBe('AI settings')
    expect(copy.saved).toBe('Saved')
  })

  it('错误码文案：zh/en 都按 code 查表；未知码走兜底且不回显后端 message', () => {
    expect(describeAiConfigError({ code: 'KEY_EMPTY' })).toBe('API Key 为空：请填写密钥，或用「清除密钥」。')
    applyLocale('en-US')
    expect(describeAiConfigError({ code: 'KEY_EMPTY' })).toBe('API Key is empty: enter a key, or use "Clear key".')
    expect(describeAiConfigError({ code: 'NO_SUCH_CODE', message: '后端细节' })).toBe('Operation failed; please retry.')
    expect(describeAiConfigError(new Error('后端细节'))).toBe('Operation failed; please retry.')
  })

  /**
   * 复核 I-2：改主机名保留旧 key 的拒绝必须有<b>专属</b>文案（告诉用户重录 key），
   * 不能落进通用 INVALID_PARAM 的"参数不在允许范围/重选供应商"。
   *
   * <p>缺映射或缺任一语言词条 ⇒ 这里会退化成兜底文案，用例即红（两种语言都断言）。
   */
  it('KEY_BOUND_TO_HOST 有专属 zh/en 文案（不是通用参数错误）', () => {
    const zh = describeAiConfigError({ code: 'KEY_BOUND_TO_HOST', message: '后端细节' })
    expect(zh).toContain('重新录入密钥')
    expect(zh).not.toContain('参数不在允许范围')
    expect(zh).not.toBe(describeAiConfigError({ code: 'INVALID_PARAM' }))
    applyLocale('en-US')
    const en = describeAiConfigError({ code: 'KEY_BOUND_TO_HOST', message: 'details' })
    expect(en.toLowerCase()).toContain('re-enter the key')
    expect(en).not.toBe(describeAiConfigError({ code: 'INVALID_PARAM' }))
    expect(en).not.toBe('Operation failed; please retry.')
  })
})
