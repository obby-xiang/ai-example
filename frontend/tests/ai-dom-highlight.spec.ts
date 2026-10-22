/**
 * AI 可视可操作专项 · `ui_highlight` / `ui_scroll`（设计稿 §3.3.5 / §3.3.6 / §3.10）。
 *
 * 覆盖：T-23（三重冗余：描边 + 图标 + 文案；reduced-motion 无过渡类）、
 * T-24（滚动只改视口：dataVersion 不变、无撤销点）。
 *
 * jsdom 无布局（`scrollIntoView` 恒为 no-op），故视觉只断言「类名 / 属性 / 徽标 / 播报 / 调用次数」。
 */
import { describe, expect, it, beforeEach, vi } from 'vitest'
import {
  aiHighlight,
  aiScroll,
  aiSnapshot,
  beginUiTurn,
  clearUiHighlights,
  peekUiAnnouncement,
  peekUiUndo
} from '@/utils/ai-dom-tools'
import { currentAiEpoch } from '@/utils/ai-regions'
import { useWorkspaceStore } from '@/stores/workspace'
import { enterImportPage, makeRoot, registerRegion, resetAiTestState } from './ai-fixtures'

beforeEach(() => {
  resetAiTestState()
  clearUiHighlights()
  beginUiTurn('t-highlight')
  // jsdom 不实现 scrollIntoView：补一个可 spy 的桩（断言"被调用"，不断言视觉位移）
  Element.prototype.scrollIntoView = vi.fn()
})

describe('T-23：三重冗余高亮', () => {
  it('描边类 + data-ai-highlighted + 内联 SVG 徽标 + 播报文案', async () => {
    enterImportPage()
    registerRegion(makeRoot('<button data-ai-ref="import.upload.choose" aria-label="上传入口">上传</button>'))
    aiSnapshot({})
    const out = await aiHighlight({ ref: 'import.upload.choose', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('ok')
    expect(out.result).toContain('已高亮')
    const node = document.querySelector('[data-ai-ref="import.upload.choose"]') as HTMLElement
    expect(node.getAttribute('data-ai-highlighted')).toBe('true')
    expect(node.className).toContain('ring-2')
    const badge = node.parentElement?.querySelector('[data-ai-highlight-badge]') as HTMLElement
    expect(badge).toBeTruthy()
    expect(badge.querySelector('svg')).toBeTruthy()
    expect(badge.textContent).toContain('上传入口')
    expect(peekUiAnnouncement()).toContain('上传入口')
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled()
  })

  it('prefers-reduced-motion 下不挂过渡动画类（静态描边）', async () => {
    const original = window.matchMedia
    window.matchMedia = ((query: string) => ({
      matches: query.includes('prefers-reduced-motion'),
      media: query,
      onchange: null,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      addListener: () => undefined,
      removeListener: () => undefined,
      dispatchEvent: () => false
    })) as unknown as typeof window.matchMedia
    try {
      enterImportPage()
      registerRegion(makeRoot('<button data-ai-ref="import.upload.choose" aria-label="上传入口">上传</button>'))
      aiSnapshot({})
      await aiHighlight({ ref: 'import.upload.choose', epoch: currentAiEpoch() })
      const node = document.querySelector('[data-ai-ref="import.upload.choose"]') as HTMLElement
      expect(node.className).toContain('ring-2')
      expect(node.className).not.toContain('transition-shadow')
    } finally {
      window.matchMedia = original
    }
  })

  it('note 参与徽标文案（≤60 字符）', async () => {
    enterImportPage()
    registerRegion(makeRoot('<button data-ai-ref="import.upload.choose" aria-label="上传入口">上传</button>'))
    aiSnapshot({})
    await aiHighlight({ ref: 'import.upload.choose', note: '请在这里选择文件', epoch: currentAiEpoch() })
    const badge = document.querySelector('[data-ai-highlight-badge]') as HTMLElement
    expect(badge.textContent).toContain('请在这里选择文件')
  })

  it('高亮不改 dataVersion、不记撤销点', async () => {
    enterImportPage()
    const workspace = useWorkspaceStore()
    registerRegion(makeRoot('<button data-ai-ref="import.upload.choose" aria-label="上传入口">上传</button>'))
    const before = workspace.dataVersion
    aiSnapshot({})
    await aiHighlight({ ref: 'import.upload.choose', epoch: currentAiEpoch() })
    expect(workspace.dataVersion).toBe(before)
    expect(peekUiUndo()).toBeNull()
  })
})

describe('T-24：ui_scroll 只改视口', () => {
  it('容器 delta 滚动：scrollTop 变、dataVersion 不变、无撤销点', async () => {
    enterImportPage()
    const workspace = useWorkspaceStore()
    const root = makeRoot('<div data-ai-ref="import.grid.viewport" data-ai-label="编辑区"></div>')
    registerRegion(root, { id: 'import.grid' })
    const viewport = root.querySelector('[data-ai-ref="import.grid.viewport"]') as HTMLElement
    const before = workspace.dataVersion
    aiSnapshot({})
    const out = await aiScroll({ ref: 'import.grid.viewport', delta: 120, epoch: currentAiEpoch() })
    expect(out.outcome).toBe('ok')
    expect(viewport.scrollTop).toBe(120)
    expect(workspace.dataVersion).toBe(before)
    expect(peekUiUndo()).toBeNull()
    expect(out.result).toContain('已滚动')
  })

  it('block=center 时走 scrollIntoView', async () => {
    enterImportPage()
    registerRegion(makeRoot('<div data-ai-ref="import.publish.panel" data-ai-label="发布结果"></div>'), { id: 'import.publish' })
    aiSnapshot({})
    const out = await aiScroll({ ref: 'import.publish.panel', block: 'center', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('ok')
    expect(out.result).toContain('发布结果')
  })

  it('delta 超 ±2000 拒绝', async () => {
    enterImportPage()
    registerRegion(makeRoot('<div data-ai-ref="import.grid.viewport"></div>'), { id: 'import.grid' })
    aiSnapshot({})
    const out = await aiScroll({ ref: 'import.grid.viewport', delta: 5000, epoch: currentAiEpoch() })
    expect(out.outcome).toBe('failed')
  })
})
