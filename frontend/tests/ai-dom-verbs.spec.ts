/**
 * AI 可视可操作专项 · 动词与三道围栏（设计稿 §3.5 / §3.6 / §3.10）。
 *
 * 覆盖：T-06（data-ai-deny 零点击）、T-08（跨页 ref）、T-09（epoch）、T-10（detached）、
 * T-27（单轮配额）、T-28（停止后零执行）、T-29（read 分级）、T-30（mutate-local）、
 * T-45（审计）、T-48（跳转后旧 ref）、T-52（写动词排队）。
 *
 * 风格：只驱动真实模块 + 真实 DOM；点击次数一律用 spy 钉死（"零点击"是本专项的安全性质）。
 */
import { describe, expect, it, beforeEach, vi } from 'vitest'
import {
  UI_WRITE_VERBS_PER_TURN,
  abortUiOperations,
  aiClick,
  aiSnapshot,
  beginUiTurn,
  peekUiUndo
} from '@/utils/ai-dom-tools'
import { currentAiEpoch } from '@/utils/ai-regions'
import { useWorkspaceStore } from '@/stores/workspace'
import { enterExportPage, enterImportPage, makeRoot, registerRegion, resetAiTestState } from './ai-fixtures'

beforeEach(() => {
  resetAiTestState()
  beginUiTurn('t-default')
})

function el(ref: string): HTMLElement {
  const node = document.querySelector(`[data-ai-ref="${ref}"]`)
  if (!node) {
    throw new Error(`missing ${ref}`)
  }
  return node as HTMLElement
}

describe('T-06：data-ai-deny 硬禁用', () => {
  it('不进快照；ui_click 拒绝且零点击', async () => {
    enterImportPage()
    registerRegion(makeRoot(
      '<button data-ai-ref="import.upload.choose">上传</button>'
        + '<button data-ai-ref="import.upload.nuke" data-ai-deny>清空全部</button>'
    ))
    expect(aiSnapshot({}).result).not.toContain('import.upload.nuke')
    const spy = vi.spyOn(el('import.upload.nuke'), 'click')
    const out = await aiClick({ ref: 'import.upload.nuke', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('failed')
    expect(out.result).toContain('data-ai-deny')
    expect(spy).toHaveBeenCalledTimes(0)
  })
})

describe('T-08：跨页 ref（上下文围栏）', () => {
  it('导入页 ref 在导出页调用 ⇒ 拒绝 + 指路导航', async () => {
    enterImportPage()
    registerRegion(makeRoot('<button data-ai-ref="import.upload.choose">上传</button>'))
    const spy = vi.spyOn(el('import.upload.choose'), 'click')
    enterExportPage()
    const out = await aiClick({ ref: 'import.upload.choose', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('failed')
    expect(out.result).toContain('当前页面为「export」')
    expect(out.result).toContain('import')
    expect(spy).toHaveBeenCalledTimes(0)
  })

  it('区域声明 allowSteps 时，步骤不符也拒绝', async () => {
    enterImportPage('PRECHECK')
    registerRegion(makeRoot('<button data-ai-ref="import.upload.choose">上传</button>'), { allowSteps: ['UPLOAD'] })
    const out = await aiClick({ ref: 'import.upload.choose', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('failed')
    expect(out.result).toContain('UPLOAD')
  })

  it('区域声明 needsTask 且当前无任务 ⇒ 拒绝', async () => {
    enterImportPage('UPLOAD', null)
    registerRegion(makeRoot('<button data-ai-ref="import.upload.choose">上传</button>'), { needsTask: true })
    const out = await aiClick({ ref: 'import.upload.choose', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('failed')
    expect(out.result).toContain('任务')
  })
})

describe('T-09：epoch 竞态', () => {
  it('过期 epoch ⇒ 拒绝且零副作用', async () => {
    enterImportPage()
    registerRegion(makeRoot('<button data-ai-ref="import.upload.choose" data-ai-risk="mutate-local">上传</button>'))
    const spy = vi.spyOn(el('import.upload.choose'), 'click')
    const out = await aiClick({ ref: 'import.upload.choose', epoch: 'abcdef-999' })
    expect(out.outcome).toBe('failed')
    expect(out.result).toContain('epoch')
    expect(spy).toHaveBeenCalledTimes(0)
  })

  it('缺 epoch ⇒ 拒绝（不静默放行）', async () => {
    enterImportPage()
    registerRegion(makeRoot('<button data-ai-ref="import.upload.choose" data-ai-risk="mutate-local">上传</button>'))
    const spy = vi.spyOn(el('import.upload.choose'), 'click')
    expect((await aiClick({ ref: 'import.upload.choose' })).outcome).toBe('failed')
    expect(spy).toHaveBeenCalledTimes(0)
  })
})

describe('T-10：元素已卸载', () => {
  it('快照过的元素被移除 ⇒ 拒绝 + 「元素已不在页面上」 + 零点击', async () => {
    enterImportPage()
    const root = makeRoot('<button data-ai-ref="import.upload.choose">上传</button>')
    registerRegion(root)
    aiSnapshot({}) // 建 ref 索引（快照过 = 模型手里有这个 ref）
    const node = el('import.upload.choose')
    const spy = vi.spyOn(node, 'click')
    node.remove()
    const out = await aiClick({ ref: 'import.upload.choose', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('failed')
    expect(out.result).toContain('元素已不在页面上')
    expect(spy).toHaveBeenCalledTimes(0)
  })
})

describe('T-27 / T-28：单轮配额与停止', () => {
  it(`T-27 第 ${UI_WRITE_VERBS_PER_TURN + 1} 个写动词被拒绝`, async () => {
    enterImportPage()
    registerRegion(makeRoot('<button data-ai-ref="import.upload.choose" data-ai-risk="mutate-local">上传</button>'))
    const spy = vi.spyOn(el('import.upload.choose'), 'click')
    for (let i = 0; i < UI_WRITE_VERBS_PER_TURN; i += 1) {
      const ok = await aiClick({ ref: 'import.upload.choose', epoch: currentAiEpoch() })
      expect(ok.outcome).toBe('ok')
    }
    const overflow = await aiClick({ ref: 'import.upload.choose', epoch: currentAiEpoch() })
    expect(overflow.outcome).toBe('failed')
    expect(overflow.result).toContain(`${UI_WRITE_VERBS_PER_TURN} 次`)
    expect(spy).toHaveBeenCalledTimes(UI_WRITE_VERBS_PER_TURN)
  })

  it('T-28 停止后后续零执行、已执行不回滚', async () => {
    enterImportPage()
    registerRegion(makeRoot('<button data-ai-ref="import.upload.choose" data-ai-risk="mutate-local">上传</button>'))
    const spy = vi.spyOn(el('import.upload.choose'), 'click')
    expect((await aiClick({ ref: 'import.upload.choose', epoch: currentAiEpoch() })).outcome).toBe('ok')
    abortUiOperations()
    const out = await aiClick({ ref: 'import.upload.choose', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('failed')
    expect(out.result).toContain('已停止')
    expect(spy).toHaveBeenCalledTimes(1)
    // 已执行的不回滚：DOM 仍在页面上
    expect(el('import.upload.choose').isConnected).toBe(true)
  })

  it('新一轮（新 turn token）重置配额与停止位', async () => {
    enterImportPage()
    registerRegion(makeRoot('<button data-ai-ref="import.upload.choose" data-ai-risk="mutate-local">上传</button>'))
    abortUiOperations()
    beginUiTurn('t-next')
    expect((await aiClick({ ref: 'import.upload.choose', epoch: currentAiEpoch() })).outcome).toBe('ok')
  })
})

describe('T-29 / T-30：风险分级', () => {
  it('T-29 read：click 1 次、无撤销点、文案不带「可撤销」', async () => {
    enterImportPage()
    registerRegion(makeRoot('<button data-ai-ref="import.upload.tab" data-ai-risk="read">切换</button>'))
    const spy = vi.spyOn(el('import.upload.tab'), 'click')
    const out = await aiClick({ ref: 'import.upload.tab', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('ok')
    expect(spy).toHaveBeenCalledTimes(1)
    expect(out.result).toContain('risk=read')
    expect(out.result).not.toContain('可撤销')
    expect(peekUiUndo()).toBeNull()
  })

  it('T-30 mutate-local：click 1 次 + 高亮 + 撤销点 + touch(source=AI)', async () => {
    enterImportPage()
    registerRegion(makeRoot(
      '<input type="checkbox" data-ai-ref="import.selectDefs.CURRENCY" data-ai-risk="mutate-local" data-ai-undo="retoggle" />'
    ), { id: 'import.selectDefs' })
    const workspace = useWorkspaceStore()
    const before = workspace.dataVersion
    const spy = vi.spyOn(el('import.selectDefs.CURRENCY'), 'click')
    const out = await aiClick({ ref: 'import.selectDefs.CURRENCY', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('ok')
    expect(spy).toHaveBeenCalledTimes(1)
    expect(el('import.selectDefs.CURRENCY').getAttribute('data-ai-highlighted')).toBe('true')
    expect(peekUiUndo()).toMatchObject({ kind: 'retoggle', ref: 'import.selectDefs.CURRENCY' })
    expect(workspace.dataVersion).toBeGreaterThan(before)
    expect(workspace.recentActions.at(-1)?.source).toBe('AI')
    expect(out.result).toContain('可撤销')
  })
})

describe('T-45：审计（ref + verb + epoch 进回执与 recentActions）', () => {
  it('回执含 ref/verb/epoch；recentActions 恰 1 条/次', async () => {
    enterImportPage()
    registerRegion(makeRoot('<button data-ai-ref="import.upload.tab" data-ai-risk="read">切换</button>'))
    const workspace = useWorkspaceStore()
    const before = workspace.recentActions.length
    const epoch = currentAiEpoch()
    const out = await aiClick({ ref: 'import.upload.tab', epoch })
    expect(out.result).toContain('import.upload.tab')
    expect(out.result).toContain('click')
    expect(out.result).toContain(epoch)
    expect(workspace.recentActions.length).toBe(before + 1)
  })
})

describe('T-48：点击触发跳转后旧 ref 立刻失效', () => {
  it('第二次调用被上下文围栏拒绝', async () => {
    enterImportPage()
    const root = makeRoot('<button data-ai-ref="import.upload.go" data-ai-risk="mutate-local">去导出</button>')
    registerRegion(root)
    el('import.upload.go').addEventListener('click', () => enterExportPage())
    const first = await aiClick({ ref: 'import.upload.go', epoch: currentAiEpoch() })
    expect(first.outcome).toBe('ok')
    const second = await aiClick({ ref: 'import.upload.go', epoch: currentAiEpoch() })
    expect(second.outcome).toBe('failed')
    expect(second.result).toContain('当前页面为「export」')
  })
})

describe('T-52：写动词串行化（≥80ms 间隔）', () => {
  it('两个写动词连发 ⇒ 第二个排队而非并行', async () => {
    enterImportPage()
    registerRegion(makeRoot('<button data-ai-ref="import.upload.choose" data-ai-risk="mutate-local">上传</button>'))
    const starts: number[] = []
    el('import.upload.choose').addEventListener('click', () => starts.push(Date.now()))
    const epoch = currentAiEpoch()
    await Promise.all([
      aiClick({ ref: 'import.upload.choose', epoch }),
      aiClick({ ref: 'import.upload.choose', epoch })
    ])
    expect(starts).toHaveLength(2)
    expect((starts[1] ?? 0) - (starts[0] ?? 0)).toBeGreaterThanOrEqual(60)
  })
})
