/**
 * 作废时的"步骤未推进"回执（2026-10-29 复审 M7）。
 *
 * 导入向导的 `nextStep` 在离开第 1 步前会 `flushEditors`（把编辑内容写回服务端文件）；
 * 若随后被预算/停止作废，**已写回的事实不能丢**：回执必须如实带出 savedDefs，
 * 否则模型会以为"什么都没发生"而重复写文件/重复推进。
 *
 * <p>本文件只锁回执组装（纯函数）；"在 import.nextStep 的真实挂载链上命中 flush 后作废"
 * 的装配过重，未在单测覆盖 —— 生产接线见 `ImportWizardView.vue#import.nextStep`。
 */
import { describe, expect, it } from 'vitest'
import { uiStepAbandonedReply } from '@/utils/ui-lifetime'

describe('R1 作废回执保留已写回事实', () => {
  it('无已写回配置项 ⇒ 只报原因（不虚构写回事实）', () => {
    const reply = uiStepAbandonedReply('前端工具预算已到点或本轮已停止：步骤未推进', [])

    expect(reply.ok).toBe(false)
    expect(reply.reason).toContain('步骤未推进')
    expect(reply.reason).not.toContain('已写回服务端文件')
  })

  it('flushEditors 已写回 2 个配置项 ⇒ 回执点名数量与编码，不只说"未推进"', () => {
    const reply = uiStepAbandonedReply(
      '前端工具预算已到点或本轮已停止：步骤未推进（工作区与页面步骤均未改动）',
      ['CURRENCY', 'REGION']
    )

    expect(reply.ok).toBe(false)
    expect(reply.reason).toContain('步骤未推进')
    expect(reply.reason).toContain('2 个配置项（CURRENCY、REGION）')
    expect(reply.reason).toContain('已写回服务端文件')
  })
})
