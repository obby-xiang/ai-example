/**
 * 页面危险操作确认：后果写在正文，并要求用户勾选确认后才放行。
 *
 * beforeClose 是唯一确认闸：未勾选时不关闭对话框，取消和关闭始终放行。
 * 动作对象与后果保留在标题和正文，按钮使用简洁动作文案。
 */
import { h, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { translate } from '@/composables/useCopy'
import type { ImportMode } from '@/types/task'
import { logCaughtError } from '@/utils/log-caught-error'


export interface IrreversibleConfirmOptions {
  /** 对话框标题（如「取消作业」/「删除任务」） */
  title: string
  /** 正文说明（须点明动作对象与后果；换行按 `whitespace-pre-line` 还原） */
  message: string
  /** 复选框文案（须点明确认动作与不可恢复后果） */
  ack: string
  /** 简洁确认按钮 */
  confirmText: string
  /** 取消按钮 */
  cancelText: string
  type?: 'warning' | 'error' | 'info'
}

/** 返回 true = 用户勾选并确认；false = 取消 / 关闭。 */
export async function confirmIrreversible(options: IrreversibleConfirmOptions): Promise<boolean> {
  const acked = ref(false)
  try {
    return await ElMessageBox.confirm(
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
        confirmButtonText: options.confirmText,
        cancelButtonText: options.cancelText,
        beforeClose: (action, _instance, done) => {
          if (action === 'confirm' && !acked.value) {
            return
          }
          done()
        }
      }
    ).then(
      () => true,
      (error) => {
        if (error === 'cancel' || error === 'close') {
          return false
        }
        throw error
      }
    )
  } catch (error) {
      logCaughtError(error, 'utils/confirm-irreversible.ts:65')
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
    ack: translate('views.import.dialog.publishReplaceAck'),
    confirmText: translate('views.import.dialog.publishReplaceConfirm'),
    cancelText: translate('views.import.dialog.publishReplaceKeep')
  })
}
