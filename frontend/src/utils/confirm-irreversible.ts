/**
 * 批4-D（用户 2026-10-23 裁决）：页面侧「一点即发」缺口收口 —— **不可撤销动作**的确认对话框
 * 统一升级为**复选框版**（勾选才放行「确定」）。
 *
 * <p>
 * 为什么不是普通 `ElMessageBox.confirm`：一次误点就等于执行（取消作业 / 删除任务是终态操作，
 * 没有撤销入口）。复选框把"读一遍后果"变成必须动作，且与 AI 侧不可撤销确认卡的形态同源
 * （同一句「确认{动作}，执行后无法恢复」）。
 *
 * <p>
 * 实现要点：`beforeClose` 是**唯一**放行闸 —— `action='confirm'` 且未勾选时不调 `done()`
 * （对话框不关、什么也不发生）；取消永远放行。Element Plus 的按钮文案保持「确定 / 取消」，
 * 与仓库既有对话框一致。
 */
import { h, ref } from 'vue'
import { ElMessageBox } from 'element-plus'

export interface IrreversibleConfirmOptions {
  /** 对话框标题（如「取消作业」/「删除任务」） */
  title: string
  /** 正文说明（原有文案原样保留；换行按 `whitespace-pre-line` 还原） */
  message: string
  /** 复选框文案（如「确认取消作业，执行后无法恢复」） */
  ack: string
  type?: 'warning' | 'error' | 'info'
}

/** 返回 true = 用户勾选并确认；false = 取消 / 关闭（调用方直接 return）。 */
export async function confirmIrreversible(options: IrreversibleConfirmOptions): Promise<boolean> {
  const acked = ref(false)
  try {
    await ElMessageBox.confirm(
      h('div', { class: 'flex flex-col gap-[8px]' }, [
        h('span', { class: 'whitespace-pre-line break-words' }, options.message),
        h(
          'label',
          { class: 'flex cursor-pointer items-start gap-[6px] text-[12.6px] leading-[1.5]' },
          [
            h('input', {
              type: 'checkbox',
              class: 'mt-[2px] flex-none cursor-pointer accent-[#2563eb]',
              'data-testid': 'irreversible-ack-box',
              onChange: (event: Event) => {
                acked.value = (event.target as HTMLInputElement | null)?.checked === true
              }
            }),
            h('span', { 'data-testid': 'irreversible-ack-label' }, options.ack)
          ]
        )
      ]),
      options.title,
      {
        type: options.type ?? 'warning',
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        beforeClose: (action, _instance, done) => {
          if (action === 'confirm' && !acked.value) {
            return
          }
          done()
        }
      }
    )
    return true
  } catch {
    return false
  }
}
