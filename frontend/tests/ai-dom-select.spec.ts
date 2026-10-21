/**
 * AI 可视可操作专项 · `ui_select`（设计稿 §3.3.4）。
 *
 * 覆盖：T-20（setter 优先、DOM 零点击）、T-21（回退路径精确匹配）、T-22（选项不存在如实失败）。
 */
import { describe, expect, it, beforeEach, vi } from 'vitest'
import { aiSelect, beginUiTurn, peekUiUndo } from '@/utils/ai-dom-tools'
import { currentAiEpoch } from '@/utils/ai-regions'
import { enterImportPage, makeRoot, registerRegion, resetAiTestState } from './ai-fixtures'

beforeEach(() => {
  resetAiTestState()
  beginUiTurn('t-select')
})

describe('T-20：路径 1 —— 区域 setter（Element Plus 自绘弹层的唯一可靠路径）', () => {
  it('setter 调 1 次、DOM 零点击、回执带原选择', async () => {
    enterImportPage()
    const root = makeRoot(
      '<div data-ai-ref="import.mode.group" data-ai-selected="REPLACE">'
        + '<span data-ai-option="MERGE" aria-checked="false">增量合并</span>'
        + '<span data-ai-option="REPLACE" aria-checked="true">整体替换</span>'
        + '</div>'
    )
    const setter = vi.fn(async (option: string) => {
      root.querySelectorAll('[data-ai-option]').forEach((node) => {
        node.setAttribute('aria-checked', String(node.getAttribute('data-ai-option') === option))
      })
      root.querySelector('[data-ai-ref="import.mode.group"]')?.setAttribute('data-ai-selected', option)
      return true
    })
    registerRegion(root, { id: 'import.mode', risk: 'mutate-local', setters: { 'import.mode.group': setter } })
    const spy = vi.spyOn(root.querySelector('[data-ai-ref="import.mode.group"]') as HTMLElement, 'click')
    const out = await aiSelect({ ref: 'import.mode.group', option: 'MERGE', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('ok')
    expect(setter).toHaveBeenCalledTimes(1)
    expect(spy).toHaveBeenCalledTimes(0)
    expect(out.result).toContain('已选择')
    expect(out.result).toContain('REPLACE')
    expect(peekUiUndo()).toMatchObject({ kind: 'select', previous: 'REPLACE' })
  })

  it('setter 返回 false ⇒ failed（不谎报成功）', async () => {
    enterImportPage()
    const root = makeRoot('<div data-ai-ref="import.mode.group" data-ai-selected="REPLACE"></div>')
    registerRegion(root, {
      id: 'import.mode',
      risk: 'mutate-local',
      setters: { 'import.mode.group': () => false }
    })
    const out = await aiSelect({ ref: 'import.mode.group', option: 'MERGE', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('failed')
  })
})

describe('T-21：路径 2 —— 点开 → 精确匹配 data-ai-option → 点选 → 回读', () => {
  it('选项在 body 下的弹层里也能命中，回读成功', async () => {
    enterImportPage()
    const root = makeRoot('<button data-ai-ref="import.mode.trigger" type="button">选择模式</button>')
    // 弹层（Element Plus 的 teleport 形态：不在区域 root 内）
    const dropdown = document.createElement('div')
    dropdown.innerHTML = [
      '<input type="radio" data-ai-option="MERGE" name="mode" />',
      '<input type="radio" data-ai-option="REPLACE" name="mode" checked />'
    ].join('')
    document.body.appendChild(dropdown)
    registerRegion(root, { id: 'import.mode', risk: 'mutate-local' })
    const out = await aiSelect({ ref: 'import.mode.trigger', option: 'MERGE', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('ok')
    expect((dropdown.querySelector('[data-ai-option="MERGE"]') as HTMLInputElement).checked).toBe(true)
    expect(out.result).toContain('已选择')
  })

  it('按文案模糊匹配不成立：只有语义值精确相等才命中', async () => {
    enterImportPage()
    const root = makeRoot('<button data-ai-ref="import.mode.trigger" type="button">选择模式</button>')
    const dropdown = document.createElement('div')
    dropdown.innerHTML = '<input type="radio" data-ai-option="MERGE" name="m" />'
    document.body.appendChild(dropdown)
    registerRegion(root, { id: 'import.mode', risk: 'mutate-local' })
    const out = await aiSelect({ ref: 'import.mode.trigger', option: 'MER', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('failed')
    expect((dropdown.querySelector('[data-ai-option="MERGE"]') as HTMLInputElement).checked).toBe(false)
  })
})

describe('T-22：选项不存在', () => {
  it('failed + 「下拉选项里没有「XX」。可选：…」', async () => {
    enterImportPage()
    const root = makeRoot('<button data-ai-ref="import.mode.trigger" type="button">选择模式</button>')
    const dropdown = document.createElement('div')
    dropdown.innerHTML = [
      '<span data-ai-option="MERGE">增量合并</span>',
      '<span data-ai-option="REPLACE">整体替换</span>'
    ].join('')
    document.body.appendChild(dropdown)
    registerRegion(root, { id: 'import.mode', risk: 'mutate-local' })
    const out = await aiSelect({ ref: 'import.mode.trigger', option: 'APPEND', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('failed')
    expect(out.result).toContain('下拉选项里没有「APPEND」')
    expect(out.result).toContain('MERGE')
    expect(out.result).toContain('REPLACE')
  })
})

describe('风险分级：read 元素拒绝 select', () => {
  it('read 区域里的元素不接受 select（写动词需要 mutate-local）', async () => {
    enterImportPage()
    const root = makeRoot('<div data-ai-ref="import.upload.group" data-ai-selected="A"></div>')
    registerRegion(root, { id: 'import.upload', risk: 'read' })
    const out = await aiSelect({ ref: 'import.upload.group', option: 'B', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('failed')
    expect(out.result).toContain('风险')
  })
})
