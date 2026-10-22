/**
 * 批4-D（用户 2026-10-23 裁决）：页面侧不可撤销动作的确认对话框 = **复选框版**。
 *
 * 锁三件事（页面侧「一点即发」缺口的安全语义）：
 * 1. 放行闸只有一处 —— `beforeClose`：未勾选时点「确定」**不关框**；
 * 2. 取消/关闭永远放行（不因未勾选把用户困在对话框里）；
 * 3. 勾选后才放行，且对话框正文里确有那句动作化文案（如「确认取消作业，执行后无法恢复」）。
 *
 * 挂载方式：`ElMessageBox.confirm` 用桩（真组件在 jsdom 里会走 teleport + 过渡，本用例只
 * 关心"挡不挡得住"与"文案在不在"），返回的 VNode 用 mount 渲出来取证。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import type { VNode } from 'vue'

const confirmMock = vi.hoisted(() => vi.fn())

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return { ...actual, ElMessageBox: { confirm: confirmMock } }
})

import { confirmIrreversible } from '@/utils/confirm-irreversible'

interface CapturedCall {
  message: VNode
  title: string
  options: { beforeClose?: (action: string, instance: unknown, done: () => void) => void }
}

function lastCall(): CapturedCall {
  const call = confirmMock.mock.calls.at(-1)
  expect(call).toBeDefined()
  return { message: call![0] as VNode, title: call![1] as string, options: call![2] as CapturedCall['options'] }
}

beforeEach(() => {
  confirmMock.mockReset()
  confirmMock.mockResolvedValue(undefined)
})

describe('confirmIrreversible：复选框确认对话框', () => {
  it('未勾选点「确定」不放行；「取消」任何时候都放行', async () => {
    const done = vi.fn()
    const promise = confirmIrreversible({
      title: '取消作业',
      message: '作业将立即终止，已处理的部分不会回滚。',
      ack: '确认取消作业，执行后无法恢复'
    })
    expect(await promise).toBe(true)

    const { options } = lastCall()
    expect(options.beforeClose).toBeTypeOf('function')

    options.beforeClose!('confirm', {}, done)
    expect(done).not.toHaveBeenCalled()

    options.beforeClose!('cancel', {}, done)
    expect(done).toHaveBeenCalledTimes(1)
  })

  it('对话框正文：原生复选框 + 动作化文案；勾选后同一份 beforeClose 放行', async () => {
    void confirmIrreversible({
      title: '取消作业',
      message: '作业将立即终止，已处理的部分不会回滚。',
      ack: '确认取消作业，执行后无法恢复'
    })
    const { message, title, options } = lastCall()
    expect(title).toBe('取消作业')

    const wrapper = mount({ render: () => message })
    const box = wrapper.find('[data-testid="irreversible-ack-box"]')
    expect(box.exists()).toBe(true)
    expect(box.attributes('type')).toBe('checkbox')
    expect((box.element as HTMLInputElement).checked).toBe(false)
    expect(wrapper.text()).toContain('作业将立即终止，已处理的部分不会回滚。')
    expect(wrapper.find('[data-testid="irreversible-ack-label"]').text()).toBe('确认取消作业，执行后无法恢复')
    expect(wrapper.text()).not.toMatch(/[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}]/u)

    await box.setValue(true)
    const done = vi.fn()
    options.beforeClose!('confirm', {}, done)
    expect(done).toHaveBeenCalledTimes(1)

    // 取消勾选 ⇒ 立刻恢复拦截（勾选不是一次性开关）
    await box.setValue(false)
    const done2 = vi.fn()
    options.beforeClose!('confirm', {}, done2)
    expect(done2).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('用户取消/关闭对话框 ⇒ 返回 false（调用方据此不发请求）', async () => {
    confirmMock.mockRejectedValueOnce('cancel')
    const ok = await confirmIrreversible({ title: '取消作业', message: 'm', ack: '确认取消作业，执行后无法恢复' })
    expect(ok).toBe(false)
  })
})
