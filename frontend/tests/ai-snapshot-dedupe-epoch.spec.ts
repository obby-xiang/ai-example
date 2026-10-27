/**
 * 第 7 项：`ui_snapshot` 同参去重**绑定页面 epoch**。
 *
 * 现场：去重键只有 `runId + 工具名 + args`，于是同一轮里"换页 / 区域重注册 / 显式推进 epoch"
 * 之后再取快照，模型拿到的仍是上一个 epoch 的快照文本（连头行的 epoch 都是旧的）——它据此
 * 操作旧 ref，必然被元素围栏以「epoch 不匹配」拒执，白跑一轮。
 *
 * 本组锁住四条确定性：
 * - 同 run 同 epoch 同参 ⇒ 仍去重（不因绑定 epoch 把既有的去重能力丢掉）；
 * - 换页（`noteAiPage`）/ 区域注册 / 注销 / 显式 `bumpAiEpoch` ⇒ 重新遍历 DOM，回新 epoch + 新内容；
 * - runId 变化 ⇒ 清缓存（既有语义不破）；
 * - `navigate_to` 的旧行为不破（同页同参幂等直返 + 同轮同参去重）。
 */
import { describe, expect, it, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  executeFrontendTool,
  makeExecution,
  registerFrontendToolExecutor,
  unregisterFrontendToolExecutor
} from '@/utils/frontend-tools'
import { bumpAiEpoch, currentAiEpoch, noteAiPage, unregisterAiRegion } from '@/utils/ai-regions'
import { enterImportPage, makeRoot, registerRegion, resetAiTestState } from './ai-fixtures'

const push = vi.hoisted(() => vi.fn(async () => undefined))

vi.mock('@/router', () => ({
  default: { push },
  ROUTE_NAMES: new Proxy({}, { get: (_target, key) => String(key) })
}))

beforeEach(() => {
  setActivePinia(createPinia())
  resetAiTestState()
  push.mockClear()
})

function button(ref: string, label: string): string {
  return `<button data-ai-ref="${ref}" data-ai-label="${label}">${label}</button>`
}

function snapshot(runId: string, args: Record<string, unknown> = { limit: 20 }) {
  return executeFrontendTool({ name: 'ui_snapshot', args, runId, budgetMs: 5_000 })
}

describe('同 epoch 同参：仍去重（既有能力不丢）', () => {
  it('第二次直返「本轮已执行过同参调用」，回执仍是本 epoch 的快照', async () => {
    enterImportPage()
    registerRegion(makeRoot(button('import.upload.choose', '上传')))
    const runId = 'epoch-dedupe-a'

    const first = await snapshot(runId)
    expect(first.outcome).toBe('ok')
    expect(first.result).toContain('界面快照')
    expect(first.result).toContain(currentAiEpoch())

    const second = await snapshot(runId)
    expect(second.outcome).toBe('ok')
    expect(second.result).toContain('本轮已执行过同参调用')
    expect(second.result).toContain('import.upload.choose')
  })

  it('同 epoch 内 DOM 变化但 epoch 未推进 ⇒ 仍去重（去重绑定的是 epoch，不是 DOM 内容）', async () => {
    enterImportPage()
    const root = makeRoot(button('import.upload.choose', '上传'))
    registerRegion(root)
    const runId = 'epoch-dedupe-b'

    await snapshot(runId)
    root.innerHTML = button('import.upload.other', '其他')

    const again = await snapshot(runId)
    expect(again.result).toContain('本轮已执行过同参调用')
    expect(again.result).toContain('import.upload.choose')
  })
})

describe('epoch 推进：同参必须重新遍历 DOM', () => {
  it('noteAiPage 换页 ⇒ 重新取快照：epoch 新、内容新、真实遍历 DOM', async () => {
    enterImportPage()
    const root = makeRoot(button('import.upload.choose', '上传'))
    registerRegion(root)
    const runId = 'epoch-dedupe-c'

    const first = await snapshot(runId)
    const epochBefore = currentAiEpoch()
    expect(first.result).toContain(epochBefore)

    // 真实换页链路：视图卸载/目标页 onMounted 时调用 noteAiPage（见 ImportWizardView / ExportWizardView）
    noteAiPage('tasks')
    root.innerHTML = button('import.upload.choose', '上传') + button('import.upload.fresh', '新按钮')

    const querySpy = vi.spyOn(root, 'querySelectorAll')
    const second = await snapshot(runId)

    expect(currentAiEpoch()).not.toBe(epochBefore)
    expect(second.result).not.toContain('本轮已执行过同参调用')
    expect(second.result).toContain(currentAiEpoch())
    expect(second.result).not.toContain(`epoch=${epochBefore}`)
    expect(second.result).toContain('import.upload.fresh')
    expect(querySpy).toHaveBeenCalled()
    querySpy.mockRestore()
  })

  it('区域注册 ⇒ 重新取快照', async () => {
    enterImportPage()
    const root = makeRoot(button('import.upload.choose', '上传'))
    registerRegion(root)
    const runId = 'epoch-dedupe-d'

    const first = await snapshot(runId)
    const epochBefore = currentAiEpoch()
    expect(first.result).toContain(epochBefore)
    expect(first.result).toContain('import.upload.choose')

    root.innerHTML = button('import.upload.choose', '上传') + button('import.upload.added', '新增')
    registerRegion(root)

    const second = await snapshot(runId)
    expect(currentAiEpoch()).not.toBe(epochBefore)
    expect(second.result).not.toContain('本轮已执行过同参调用')
    expect(second.result).toContain(currentAiEpoch())
    expect(second.result).toContain('import.upload.added')
  })

  it('区域注销 ⇒ 重新取快照', async () => {
    enterImportPage()
    const root = makeRoot(button('import.upload.choose', '上传'))
    registerRegion(root)
    const runId = 'epoch-dedupe-e'

    const first = await snapshot(runId)
    const epochBefore = currentAiEpoch()
    expect(first.result).toContain(epochBefore)
    expect(first.result).toContain('import.upload.choose')

    unregisterAiRegion('import.upload')
    registerRegion(makeRoot(button('import.upload.reborn', '重建')))

    const second = await snapshot(runId)
    expect(currentAiEpoch()).not.toBe(epochBefore)
    expect(second.result).not.toContain('本轮已执行过同参调用')
    expect(second.result).toContain('import.upload.reborn')
    expect(second.result).not.toContain('import.upload.choose')
  })

  it('显式 bumpAiEpoch ⇒ 重新取快照', async () => {
    enterImportPage()
    registerRegion(makeRoot(button('import.upload.choose', '上传')))
    const runId = 'epoch-dedupe-f'

    const first = await snapshot(runId)
    const epochBefore = currentAiEpoch()
    expect(first.result).toContain(epochBefore)

    bumpAiEpoch()

    const second = await snapshot(runId)
    expect(second.result).not.toContain('本轮已执行过同参调用')
    expect(second.result).toContain(currentAiEpoch())
    expect(second.result).not.toContain(`epoch=${epochBefore}`)
  })
})

describe('runId 变化：清缓存（既有语义不破）', () => {
  it('换 runId 同参必须真执行；换回旧 runId 也不复用旧缓存', async () => {
    enterImportPage()
    registerRegion(makeRoot(button('import.upload.choose', '上传')))

    await snapshot('epoch-dedupe-g1')
    const other = await snapshot('epoch-dedupe-g2')
    expect(other.result).not.toContain('本轮已执行过同参调用')
    expect(other.result).toContain('界面快照')

    const back = await snapshot('epoch-dedupe-g1')
    expect(back.result).not.toContain('本轮已执行过同参调用')
  })
})

describe('navigate_to：旧行为不破', () => {
  it('同页同参幂等直返；同 run 同参第二次仍去重且零 push', async () => {
    enterImportPage('UPLOAD', 7)
    const call = () => executeFrontendTool({
      name: 'navigate_to',
      args: { page: 'import' },
      runId: 'epoch-dedupe-nav',
      budgetMs: 5_000
    })

    const first = await call()
    expect(first.outcome).toBe('ok')
    expect(first.code).toBe('UI_ALREADY_ON_PAGE')
    expect(first.result).toContain('已在目标页')

    const second = await call()
    expect(second.result).toContain('本轮已执行过同参调用')
    expect(push).toHaveBeenCalledTimes(0)
  })
})

/**
 * NAV3：去重表只收**可复用**的结局（`ok` + 能力可用）。
 *
 * 毒化现场：一次 `failed` / `timeout` / 降级成功被记进表，同轮同参重试就命中它、直接回
 * "本轮已执行过"——模型据此认定这条路走不通，而现场可能早已可执行（详情加载完 / 页面恢复）。
 */
describe('NAV3：失败结局不进去重表（同参可重试）', () => {
  it('failed / timeout 不缓存，第三次 ok 才缓存，之后同参才去重', async () => {
    enterImportPage()
    registerRegion(makeRoot(button('import.upload.choose', '上传')))
    const runId = 'nav3-poison'
    const script = ['failed', 'timeout', 'ok'] as const
    let attempt = 0
    const executor = vi.fn(() => {
      const outcome = script[Math.min(attempt, script.length - 1)]
      attempt += 1
      return makeExecution({
        outcome,
        availability: outcome === 'ok' ? 'available' : 'degraded',
        result: `第 ${attempt} 次执行的结局：${outcome}`
      })
    })
    registerFrontendToolExecutor('ui_snapshot', executor)
    try {
      const first = await snapshot(runId)
      expect(first.outcome).toBe('failed')

      const second = await snapshot(runId)
      expect(second.outcome).toBe('timeout')
      expect(second.result).not.toContain('本轮已执行过同参调用')

      const third = await snapshot(runId)
      expect(third.outcome).toBe('ok')
      expect(third.result).toContain('第 3 次执行的结局')
      expect(third.result).not.toContain('本轮已执行过同参调用')
      expect(executor).toHaveBeenCalledTimes(3)

      // 成功（ok + available）仍然进表：同参第四次被去重挡回，不再执行
      const fourth = await snapshot(runId)
      expect(fourth.result).toContain('本轮已执行过同参调用')
      expect(executor).toHaveBeenCalledTimes(3)
    } finally {
      unregisterFrontendToolExecutor('ui_snapshot')
    }
  })

  it('降级成功（ok + degraded）不缓存：同参再调必须真执行', async () => {
    enterImportPage()
    registerRegion(makeRoot(button('import.upload.choose', '上传')))
    const runId = 'nav3-degraded'
    const executor = vi
      .fn()
      .mockReturnValueOnce(makeExecution({ outcome: 'ok', availability: 'degraded', result: '降级结果' }))
      .mockReturnValueOnce(makeExecution({ outcome: 'ok', availability: 'available', result: '恢复后的完整结果' }))
    registerFrontendToolExecutor('ui_snapshot', executor)
    try {
      const first = await snapshot(runId)
      expect(first.outcome).toBe('ok')

      const second = await snapshot(runId)
      expect(second.result).toContain('恢复后的完整结果')
      expect(second.result).not.toContain('本轮已执行过同参调用')
      expect(executor).toHaveBeenCalledTimes(2)
    } finally {
      unregisterFrontendToolExecutor('ui_snapshot')
    }
  })
})
