/**
 * 挂账 L-66 · `ui_snapshot` 默认 kinds 纳入 `tab`（测试扩充 V6，**语义变更单列**）。
 *
 * 裁决（本批）：默认 kinds 加 `tab` —— `import.grid` 的编辑区标签页对 AI 可见是预期能力，
 * 「模型必须显式 `kinds:['all']` 才看得见标签页」属能力缺口而非安全边界。
 *
 * 边界（不借机放宽）：
 * - 默认 = button / input / select / toggle / **tab**；`link` 仍需显式声明；
 * - 显式 `kinds` 仍严格按声明过滤（`kinds:['tab']` 只回 tab、`kinds:['link']` 只回 link）；
 * - tab 的状态推导沿用既有口径（selected / unselected / disabled），且 `kinds` 参数说明同步点明默认含 tab。
 *
 * 证红：把 `ROLE_FILTER_DEFAULT` 里的 `tab` 去掉 ⇒ K-01 / K-05 红；
 * 把描述里的「默认含 tab」去掉 ⇒ K-06 红。
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { aiSnapshot, beginUiTurn } from '@/utils/ai-dom-tools'
import { findFrontendTool } from '@/types/tools'
import { enterImportPage, makeRoot, registerRegion, resetAiTestState } from './ai-fixtures'

beforeEach(() => {
  resetAiTestState()
  beginUiTurn('t-l66')
})

function elementLines(result: string): string[] {
  return result.split('\n').filter((line) => line.startsWith('['))
}

function refsOf(result: string): string[] {
  return elementLines(result).map((line) => line.slice(1, line.indexOf(']')))
}

/** 导入向导编辑区形态：两个标签页 + 一个普通按钮 + 一个链接。 */
function mountTabs(): void {
  enterImportPage()
  registerRegion(makeRoot(
    '<button data-ai-ref="import.upload.choose">上传</button>'
      + '<div role="tablist">'
      + '<div role="tab" data-ai-ref="import.grid.tab.CURRENCY" aria-selected="true">币种</div>'
      + '<div role="tab" data-ai-ref="import.grid.tab.REGION" aria-selected="false">地区</div>'
      + '</div>'
      + '<a href="#help" data-ai-ref="import.upload.help">帮助</a>'
  ))
}

describe('K-01：默认参数即可见标签页', () => {
  it('不带 kinds 的快照含两个 tab，且 state 推导沿用 selected / unselected', () => {
    mountTabs()
    const out = aiSnapshot({})
    expect(out.outcome).toBe('ok')
    expect(out.result).toMatch(/\[import\.grid\.tab\.CURRENCY\][^\n]*state=selected/)
    expect(out.result).toMatch(/\[import\.grid\.tab\.REGION\][^\n]*state=unselected/)
    // 尾行仍与正文一致（默认扩面不得破坏「看全」判据）
    const total = Number(/共 (\d+) 个元素/.exec(out.result)?.[1] ?? '-1')
    expect(total).toBe(elementLines(out.result).length)
  })

  it('禁用态标签页在默认快照里 state=disabled（读得到"点不动"）', () => {
    enterImportPage()
    registerRegion(makeRoot(
      '<div role="tablist">'
        + '<div role="tab" data-ai-ref="import.grid.tab.CURRENCY" aria-selected="false" aria-disabled="true">币种</div>'
        + '</div>'
    ))
    expect(aiSnapshot({}).result).toMatch(/\[import\.grid\.tab\.CURRENCY\][^\n]*state=disabled/)
  })
})

describe('K-02：默认不含 link（只加 tab，不借机放宽）', () => {
  it('默认快照不回 link 角色；kinds 显式声明才可见', () => {
    mountTabs()
    expect(refsOf(aiSnapshot({}).result)).not.toContain('import.upload.help')
    expect(refsOf(aiSnapshot({ kinds: ['link'] }).result)).toEqual(['import.upload.help'])
  })
})

describe('K-03：显式 kinds 仍严格按声明过滤', () => {
  it('kinds=[tab] 只回标签页（按钮与链接都不出现）', () => {
    mountTabs()
    const refs = refsOf(aiSnapshot({ kinds: ['tab'] }).result)
    expect(refs).toEqual(['import.grid.tab.CURRENCY', 'import.grid.tab.REGION'])
  })

  it('kinds=[all] 仍是全量（含 link 与 tab）', () => {
    mountTabs()
    const refs = refsOf(aiSnapshot({ kinds: ['all'] }).result)
    expect(refs).toEqual(expect.arrayContaining([
      'import.upload.choose',
      'import.grid.tab.CURRENCY',
      'import.upload.help'
    ]))
  })
})

describe('K-05：默认共四种旧角色全保留（扩面不替换）', () => {
  it('button / input / select / toggle 与 tab 同批可见', () => {
    enterImportPage()
    registerRegion(makeRoot(
      '<button data-ai-ref="import.upload.choose">上传</button>'
        + '<input data-ai-ref="import.upload.name" type="checkbox" />'
        + '<select data-ai-ref="import.upload.mode"><option>增量</option></select>'
        + '<input data-ai-ref="import.upload.search" />'
        + '<div role="tab" data-ai-ref="import.grid.tab.CURRENCY" aria-selected="true">币种</div>'
    ))
    expect(refsOf(aiSnapshot({}).result)).toEqual([
      'import.upload.choose',
      'import.upload.name',
      'import.upload.mode',
      'import.upload.search',
      'import.grid.tab.CURRENCY'
    ])
  })
})

describe('K-06：注册面（工具描述点明默认含 tab）', () => {
  it('ui_snapshot 的 kinds 参数说明写明默认含 tab', () => {
    const descriptor = findFrontendTool('ui_snapshot')
    const kinds = descriptor?.args.find((arg) => arg.name === 'kinds')
    expect(kinds?.description).toContain('tab')
    expect(kinds?.description).toContain('默认含 tab')
    expect(descriptor?.description).toContain('默认含 tab')
  })
})
