/**
 * Task 5 / FE-33（两层）：**动作后重拍必须是新鲜的**。
 *
 * 缺陷（审查口径）：
 * - 层① `ai-dom-tools` 的快照缓存键 = `epoch|args`：写动词改完界面后同参重拍逐字返回动作前文本；
 * - 层② `frontend-tools` 的同参去重（`ui_snapshot` 在名单里）命中即直返"动作前快照"，
 *   键 = `runId|epoch|工具名|args` —— 与层①是不同代码、不同修复点。
 *
 * 后果：模型看到"和刚才一样"的画面 ⇒ 判没生效 ⇒ 再点一次（勾选被点回原态）；「可再
 * ui_snapshot 确认」这句回执结构性不可兑现。
 *
 * 保留项：**没有写动作**时同参去重照旧（既有能力不破，见 ai-snapshot-dedupe-epoch.spec.ts）。
 */
import { describe, expect, it, beforeEach, vi } from 'vitest'
import { aiFill, aiSnapshot, beginUiTurn } from '@/utils/ai-dom-tools'
import { executeFrontendTool } from '@/utils/frontend-tools'
import { currentAiEpoch } from '@/utils/ai-regions'
import { enterImportPage, makeRoot, registerRegion, resetAiTestState } from './ai-fixtures'

vi.mock('@/router', () => ({
  default: { push: vi.fn(async () => undefined) },
  ROUTE_NAMES: new Proxy({}, { get: (_target, key) => String(key) })
}))

beforeEach(() => {
  resetAiTestState()
  beginUiTurn('t-freshness')
})

function inputRoot(): HTMLElement {
  return makeRoot('<input type="text" data-ai-ref="import.upload.query" data-ai-label="筛选" data-ai-fill="allow" value="" />')
}

function rawSnapshotCall(runId: string, args: Record<string, unknown> = {}) {
  return executeFrontendTool({ name: 'ui_snapshot', args, runId, budgetMs: 5_000 })
}

describe('层①：ai-dom-tools 快照缓存在写动作后必须失效', () => {
  it('ui_fill 之后同参重拍能看到新值（不是动作前文本）', async () => {
    enterImportPage()
    const root = inputRoot()
    registerRegion(root, { risk: 'mutate-local' })

    const first = aiSnapshot({})
    expect(first.outcome).toBe('ok')
    expect(first.result).not.toContain('value="COUNTRY"')

    const write = await aiFill({ ref: 'import.upload.query', value: 'COUNTRY', epoch: currentAiEpoch() })
    expect(write.outcome).toBe('ok')

    const second = aiSnapshot({})
    expect(second.result).toContain('value="COUNTRY"')
    // 旧缓存里没有 value（空值不回显）——它绝不该被当成"动作后的画面"再返回一次
    expect(second.result).not.toBe(first.result)
  })

  it('新一轮（beginUiTurn 换 token）同参重拍不得复用上一轮缓存', async () => {
    enterImportPage()
    const root = inputRoot()
    registerRegion(root, { risk: 'mutate-local' })

    expect(aiSnapshot({}).result).not.toContain('value="PAGE-OWN"')
    // 页面自己刷新了数据（不是 AI 写的）：DOM 变了、epoch 没变
    ;(root.querySelector('[data-ai-ref="import.upload.query"]') as HTMLInputElement).value = 'PAGE-OWN'
    beginUiTurn('t-freshness-next-round')

    const next = aiSnapshot({})
    expect(next.result).toContain('value="PAGE-OWN"')
  })
})

describe('层②：frontend-tools 同参去重不得跨写动作返回旧快照', () => {
  it('ui_snapshot → ui_fill → 同参 ui_snapshot：必须重新遍历，回动作后的画面', async () => {
    enterImportPage()
    registerRegion(inputRoot(), { risk: 'mutate-local' })
    const runId = 'freshness-run-b'

    const first = await rawSnapshotCall(runId)
    expect(first.outcome).toBe('ok')
    expect(first.result).not.toContain('value="COUNTRY"')

    const write = await executeFrontendTool({
      name: 'ui_fill',
      args: { ref: 'import.upload.query', value: 'COUNTRY', epoch: currentAiEpoch() },
      runId,
      budgetMs: 5_000
    })
    expect(write.outcome).toBe('ok')

    const again = await rawSnapshotCall(runId)
    expect(again.result).not.toContain('本轮已执行过同参调用')
    expect(again.result).toContain('value="COUNTRY"')
  })

  it('无写动作时同参仍去重（既有能力不破）', async () => {
    enterImportPage()
    registerRegion(inputRoot(), { risk: 'mutate-local' })
    const runId = 'freshness-run-c'

    await rawSnapshotCall(runId)
    const again = await rawSnapshotCall(runId)
    expect(again.result).toContain('本轮已执行过同参调用')
  })
})
