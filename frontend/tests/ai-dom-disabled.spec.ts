/**
 * 挂账 L-65 · 禁用控件点击「假成功」收口（测试扩充 V6）。
 *
 * 现状（批4-B 验收观察）：`checkFences` 的元素闸只校验快照 / 区域 / 上下文 / 动词，
 * **不判 `disabled`** ⇒ 点一个已禁用（只读态 / 条件锁）的已标注控件照常回 `ok`，
 * 而界面没有任何变化 —— 回执与事实不符（与「诚实回执」口径相冲）。
 *
 * 本批口径（与批4-A 的 `UI_*` 闭集同批收口）：
 * - 写动词（click / fill / select）命中禁用元素 ⇒ `outcome=blocked` + 机器码 `UI_TARGET_DISABLED` + 人话；
 * - 禁用判定 = 原生 `disabled` / `disabled` 属性 / `aria-disabled="true"`（自身或祖先容器）；
 * - 只读动词（scroll / highlight）**不拦**（描边与滚动不改任何状态，拦了反而是能力倒退）；
 * - 快照仍如实显示 `state=disabled`（既有能力不降）；元素启用后同一 ref 的正常路径不受影响。
 *
 * 证红：把元素闸里的禁用判定去掉（或恒 false）⇒ 本文件 P1/P2/P4/P5/P7 全红。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  aiClick,
  aiFill,
  aiHighlight,
  aiScroll,
  aiSelect,
  aiSnapshot,
  beginUiTurn
} from '@/utils/ai-dom-tools'
import { currentAiEpoch } from '@/utils/ai-regions'
import { UI_CODES, isUiCode, sanitizeUiCode } from '@/utils/ui-receipt'
import { enterImportPage, makeRoot, registerRegion, resetAiTestState } from './ai-fixtures'

beforeEach(() => {
  resetAiTestState()
  beginUiTurn('t-l65')
})

function el(ref: string): HTMLElement {
  const node = document.querySelector(`[data-ai-ref="${ref}"]`)
  if (!node) {
    throw new Error(`missing ${ref}`)
  }
  return node as HTMLElement
}

describe('P0：机器码进闭集', () => {
  it('UI_TARGET_DISABLED 在 UI_CODES 闭集里，且入口归一放行、非法值仍丢', () => {
    expect((UI_CODES as readonly string[]).includes('UI_TARGET_DISABLED')).toBe(true)
    expect(isUiCode('UI_TARGET_DISABLED')).toBe(true)
    expect(sanitizeUiCode('UI_TARGET_DISABLED')).toBe('UI_TARGET_DISABLED')
    expect(sanitizeUiCode('ui_target_disabled')).toBeNull()
  })
})

describe('P1：原生 disabled 按钮 ⇒ 点击拒执（零点击、blocked、机器码）', () => {
  it('快照如实显示 state=disabled，且 ui_click 回 blocked 而不是 ok', async () => {
    enterImportPage()
    registerRegion(makeRoot(
      '<button data-ai-ref="import.upload.choose">上传</button>'
        + '<button data-ai-ref="import.upload.next" disabled>下一步</button>'
    ))
    const snap = aiSnapshot({})
    expect(snap.result).toMatch(/\[import\.upload\.next\][^\n]*state=disabled/)

    const spy = vi.spyOn(el('import.upload.next'), 'click')
    const out = await aiClick({ ref: 'import.upload.next', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('blocked')
    expect(out.code).toBe('UI_TARGET_DISABLED')
    expect(spy).toHaveBeenCalledTimes(0)
    // 人话：说清"未执行 + 为什么 + 下一步"，机器码不进用户可见文案
    expect(out.result).toContain('未执行')
    expect(out.result).toContain('禁用')
    expect(out.result).toContain('ui_snapshot')
    expect(out.result).not.toContain('UI_')
  })
})

describe('P2：aria-disabled 承载（label 根元素 / 自绘控件）同样拒执', () => {
  it('aria-disabled="true" 的 label 根元素被点 ⇒ blocked', async () => {
    enterImportPage()
    registerRegion(makeRoot(
      '<button data-ai-ref="import.upload.choose">上传</button>'
        + '<label data-ai-ref="import.upload.pick" aria-disabled="true"><span>勾选</span></label>'
    ))
    aiSnapshot({})
    const spy = vi.spyOn(el('import.upload.pick'), 'click')
    const out = await aiClick({ ref: 'import.upload.pick', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('blocked')
    expect(out.code).toBe('UI_TARGET_DISABLED')
    expect(spy).toHaveBeenCalledTimes(0)
  })

  it('祖先容器 aria-disabled="true" ⇒ 子元素同样视为禁用', async () => {
    enterImportPage()
    registerRegion(makeRoot(
      '<button data-ai-ref="import.upload.choose">上传</button>'
        + '<div aria-disabled="true"><button data-ai-ref="import.upload.child">子按钮</button></div>'
    ))
    aiSnapshot({})
    const spy = vi.spyOn(el('import.upload.child'), 'click')
    const out = await aiClick({ ref: 'import.upload.child', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('blocked')
    expect(out.code).toBe('UI_TARGET_DISABLED')
    expect(spy).toHaveBeenCalledTimes(0)
  })
})

describe('P3：启用后正常（拒执不误伤可用路径）', () => {
  it('同一 ref 解除禁用后点击照常 ok，且回执仍写「已点击」', async () => {
    enterImportPage()
    registerRegion(makeRoot('<button data-ai-ref="import.upload.next" disabled>下一步</button>'))
    aiSnapshot({})
    el('import.upload.next').removeAttribute('disabled')
    const spy = vi.spyOn(el('import.upload.next'), 'click')
    const out = await aiClick({ ref: 'import.upload.next', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('ok')
    expect(out.code).toBeUndefined()
    expect(out.result).toContain('已点击')
    expect(spy).toHaveBeenCalledTimes(1)
  })
})

describe('P4：ui_fill 命中禁用输入框 ⇒ 零写入', () => {
  it('禁用 input（fill=allow + mutate-local）被 fill ⇒ blocked，值不变', async () => {
    enterImportPage()
    registerRegion(makeRoot(
      '<input data-ai-ref="import.upload.choose" data-ai-fill="allow" data-ai-risk="mutate-local" value="旧值" />'
        + '<input data-ai-ref="import.upload.name" data-ai-fill="allow" data-ai-risk="mutate-local" value="旧值" disabled />'
    ))
    aiSnapshot({})
    const out = await aiFill({ ref: 'import.upload.name', value: '新值', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('blocked')
    expect(out.code).toBe('UI_TARGET_DISABLED')
    expect((el('import.upload.name') as HTMLInputElement).value).toBe('旧值')
  })
})

describe('P5：ui_select 命中禁用控件 ⇒ 零交互', () => {
  it('aria-disabled 的选择控件被 select ⇒ blocked（setter 一次不调）', async () => {
    enterImportPage()
    const setter = vi.fn(async () => true)
    registerRegion(makeRoot(
      '<div data-ai-ref="import.upload.mode" data-ai-risk="mutate-local" aria-disabled="true" role="combobox">增量</div>'
    ), { setters: { 'import.upload.mode': setter } })
    aiSnapshot({})
    const spy = vi.spyOn(el('import.upload.mode'), 'click')
    const out = await aiSelect({ ref: 'import.upload.mode', option: 'MERGE', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('blocked')
    expect(out.code).toBe('UI_TARGET_DISABLED')
    expect(setter).toHaveBeenCalledTimes(0)
    expect(spy).toHaveBeenCalledTimes(0)
  })
})

describe('P6：只读动词不拦（不误伤 scroll / highlight）', () => {
  it('禁用元素的 scroll 与 highlight 仍 ok（不改任何状态）', () => {
    enterImportPage()
    registerRegion(makeRoot('<button data-ai-ref="import.upload.next" disabled>下一步</button>'))
    aiSnapshot({})
    const scrolled = aiScroll({ ref: 'import.upload.next', delta: 10, epoch: currentAiEpoch() })
    expect(scrolled.outcome).toBe('ok')
    const highlighted = aiHighlight({ ref: 'import.upload.next', epoch: currentAiEpoch() })
    expect(highlighted.outcome).toBe('ok')
  })
})

describe('P7：回执三段式（结论 / 原因 / 下一步）', () => {
  it('disabled 回执是「结论：… 原因：… 下一步：…」而非裸一句', async () => {
    enterImportPage()
    registerRegion(makeRoot(
      '<button data-ai-ref="import.upload.choose">上传</button>'
        + '<button data-ai-ref="import.upload.next" disabled>下一步</button>'
    ))
    aiSnapshot({})
    const out = await aiClick({ ref: 'import.upload.next', epoch: currentAiEpoch() })
    expect(out.result).toContain('结论：')
    expect(out.result).toContain('原因：')
    expect(out.result).toContain('下一步：')
    expect(out.availability).toBe('degraded')
  })
})
