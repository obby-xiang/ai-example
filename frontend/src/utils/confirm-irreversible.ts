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
 * （对话框不关、什么也不发生）；取消永远放行。按钮文案不在这里覆盖，交给根级
 * `<el-config-provider :locale>`（`App.vue`）跟随语言 —— 与仓库其余对话框同一口径。
 */
import { h, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { translate } from '@/composables/useCopy'
import type { ImportMode } from '@/types/task'

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

/**
 * R-09（用户 2026-10-24 裁决）：发布是不可撤销动作，但**只有 REPLACE 会删除范围内旧行**。
 *
 * <p>
 * 复用页面侧同一套复选框确认（{@link confirmIrreversible}），三个入口共用本函数，避免
 * "按钮加了确认、AI handler / run_job 仍是免确认"的口径漂移。MERGE 只按业务键增量更新，
 * 维持免确认（返回 true，不弹框）。
 *
 * <p>
 * 文案必须点名"范围内旧行会被删除"——只说"确认发布"等于没告诉用户代价。
 */
export async function confirmPublishByMode(mode: ImportMode | null): Promise<boolean> {
  if (mode !== 'REPLACE') {
    return true
  }
  return confirmIrreversible({
    title: translate('views.import.dialog.publishReplaceTitle'),
    message: translate('views.import.dialog.publishReplaceMessage'),
    ack: translate('views.import.dialog.publishReplaceAck')
  })
}
