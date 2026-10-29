/**
 * 预览硬闸回归锁（2026-10-29）：
 *
 * 旧实现把 `workspace.dataVersion` 当作 `sheetVer`，而 `dataVersion` 是**工作区全局**版本：
 * 任何无关 touch（记一条动作、换步骤之外的契约同步…）都会让它自增 ⇒ 切档重算
 * (`replanSheet`) 把"表根本没变"误判成"表已被修改"，预览被无故判失效。
 *
 * 新口径：
 * 1. 硬闸只看**表格身份**（previewId = toolCallId + instanceId + loadGeneration + scope）
 *    与**目标行指纹**；
 * 2. `sheetVer` 是**表内**内容版本（适配器 contentVersion / loadGeneration 派生），
 *    与工作区全局 `dataVersion` 无任何关系，且只作元数据（不是硬闸判据）。
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { registerPageHandler, unregisterPageHandler, useWorkspaceStore } from '@/stores/workspace'
import { resolveSheetContext } from '@/utils/frontend-tools'
import { planReplace, replanSheet, sheetContentVersionOf } from '@/utils/sheet-tools'
import { field, makeFakeSheet, type FakeSheet } from './sheet-fixtures'

const fields = [
  field('code', '编码', { required: true, key: true }),
  field('name', '名称'),
  field('status', '状态')
]

let sheet: FakeSheet

const args = {
  defCode: 'DEMO',
  scope: 'sheet' as const,
  columnCodes: ['status'],
  search: '停用',
  replace: '禁用',
  preview: 'count' as const
}

beforeEach(() => {
  setActivePinia(createPinia())
  useWorkspaceStore().enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'EXPORT', taskId: 7 })
  sheet = makeFakeSheet({
    fields,
    rows: [
      { code: 'CNY', name: '人民币', status: '启用' },
      { code: 'USD', name: '美元', status: '停用' },
      { code: 'CNH', name: '离岸', status: '停用' }
    ]
  })
})

describe('R1 无关 touch 不得使预览失效（全局 dataVersion ≠ 表内 sheetVer）', () => {
  it('工作区 dataVersion 变化（表没动）⇒ replanSheet 照常成功且 planId 不变', () => {
    const planned = planReplace(sheet.ctx, args, 't-touch')
    if (!planned.ok) throw new Error(planned.reason)

    // 无关 touch：只推进工作区全局版本，表格内容/身份都不变
    useWorkspaceStore().bumpVersion()
    const unrelated = { ...sheet.ctx, version: (sheet.ctx.version ?? 0) + 9 }

    const replanned = replanSheet(unrelated, planned.plan, 'sample-diff')

    expect(replanned.ok).toBe(true)
    if (!replanned.ok) return
    expect(replanned.plan.planId).toBe(planned.plan.planId)
  })

  it('目标行主键值被改（表真的变了）⇒ replanSheet 仍判"预览已失效"', () => {
    const planned = planReplace(sheet.ctx, args, 't-keyedit')
    if (!planned.ok) throw new Error(planned.reason)

    // 版本号不变（旧实现的唯一判据看不到这次改动）；行指纹必须自己抓到
    sheet.rows[1]!.code = 'USD-EDITED'

    const replanned = replanSheet(sheet.ctx, planned.plan, 'sample-diff')

    expect(replanned.ok).toBe(false)
    if (replanned.ok) return
    expect(replanned.reason).toContain('预览已失效')
  })

  it('loadGeneration 递增（同表程序化重载）⇒ 身份闸拒绝重算（反向保留）', () => {
    const planned = planReplace(sheet.ctx, args, 't-reload')
    if (!planned.ok) throw new Error(planned.reason)

    sheet.reload()

    const replanned = replanSheet(sheet.ctx, planned.plan, 'sample-diff')

    expect(replanned.ok).toBe(false)
    if (replanned.ok) return
    expect(replanned.reason).toContain('预览已失效')
  })
})

describe('R2 表格上下文版本来自表内，不来自 workspace.dataVersion', () => {
  it('页面 handler 回报的全局 version 被覆盖为适配器 contentVersion；无关 touch 不改变它', async () => {
    registerPageHandler('sheet.current', () => ({
      ok: true,
      defCode: 'DEMO',
      sheetName: '演示表',
      readOnly: false,
      // 旧写法：全局工作区版本（本用例要证明它不再进入 ctx.version）
      version: useWorkspaceStore().dataVersion,
      fields,
      adapter: { ...sheet.ctx.adapter, contentVersion: () => 7 }
    }))

    const first = await resolveSheetContext('DEMO')
    if (!first.ok) throw new Error(first.reason)
    expect(first.ctx.version).toBe(7)

    useWorkspaceStore().bumpVersion()
    useWorkspaceStore().bumpVersion()

    const second = await resolveSheetContext('DEMO')
    if (!second.ok) throw new Error(second.reason)
    expect(second.ctx.version).toBe(7)

    unregisterPageHandler('sheet.current')
  })

  it('适配器无 contentVersion ⇒ 回落到表内指纹计数：loadGeneration 变才自增', () => {
    const adapter = sheet.ctx.adapter
    const first = sheetContentVersionOf(adapter)
    expect(sheetContentVersionOf(adapter)).toBe(first)

    useWorkspaceStore().bumpVersion()
    expect(sheetContentVersionOf(adapter)).toBe(first)

    sheet.reload()
    expect(sheetContentVersionOf(adapter)).toBe(first + 1)
  })
})
