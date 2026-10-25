/**
 * Task 5 / FE-32：**看得见才可操作** —— 隐藏步骤的控件不得被披露为可写。
 *
 * 缺陷（审查口径）：`aiSnapshot` 与写动词的元素围栏都只判 `el.isConnected`。两个向导的
 * 所有步骤都常驻 DOM（`v-show`），于是第 4 步时快照照样把第 1 步的上传按钮/输入框列成
 * `state=enabled`，`ui_click` / `ui_fill` 也真的执行并回执「界面已同步」—— 而屏幕上什么都没变。
 *
 * 本组钉死三条：
 * - 隐藏（`display:none` / `visibility:hidden` / `hidden` / `aria-hidden` 祖先）的控件不进快照；
 * - 对隐藏控件的写动词一律拒执（`UI_TARGET_HIDDEN`），且**零写入**；
 * - 可见控件不受影响（不得把整片界面误判成隐藏）。
 */
import { describe, expect, it, beforeEach, vi } from 'vitest'
import { aiClick, aiFill, aiSnapshot, beginUiTurn } from '@/utils/ai-dom-tools'
import { executeFrontendTool } from '@/utils/frontend-tools'
import { currentAiEpoch } from '@/utils/ai-regions'
import { enterImportPage, makeRoot, registerRegion, resetAiTestState } from './ai-fixtures'

vi.mock('@/router', () => ({
  default: { push: vi.fn(async () => undefined) },
  ROUTE_NAMES: new Proxy({}, { get: (_target, key) => String(key) })
}))

beforeEach(() => {
  resetAiTestState()
  beginUiTurn('t-visibility')
})

/**
 * 向导的 `v-show` 形态：所有步骤常驻，非当前步骤是 inline `display:none`。
 * 第 4 步可见区里放一个正常控件，作为"可见控件不受影响"的对照。
 */
function wizardRoot(): HTMLElement {
  return makeRoot(`
    <section data-step="1" style="display: none">
      <button data-ai-ref="import.upload.choose" data-ai-label="上传文件">上传文件</button>
      <input type="text" data-ai-ref="import.upload.query" data-ai-label="筛选" data-ai-fill="allow" value="" />
    </section>
    <section data-step="4">
      <input type="text" data-ai-ref="import.review.note" data-ai-label="备注" data-ai-fill="allow" value="" />
      <button data-ai-ref="import.review.submit" data-ai-label="提交预检">提交预检</button>
    </section>
  `)
}

describe('快照：隐藏步骤的控件不得披露为可用', () => {
  it('第 1 步隐藏时，其上传按钮/输入框不出现在快照里，第 4 步控件仍在', () => {
    enterImportPage('REVIEW')
    registerRegion(wizardRoot(), { risk: 'mutate-local' })

    const out = aiSnapshot({})
    expect(out.outcome).toBe('ok')
    expect(out.result).toContain('import.review.note')
    expect(out.result).toContain('import.review.submit')
    // 屏幕上没有的控件不得以 state=enabled 的身份进快照（模型据此就会去点）
    expect(out.result).not.toContain('import.upload.choose')
    expect(out.result).not.toContain('import.upload.query')
  })

  it('visibility:hidden / [hidden] / aria-hidden 祖先同样不披露', () => {
    enterImportPage('REVIEW')
    registerRegion(makeRoot(`
      <div style="visibility: hidden"><button data-ai-ref="x.invisible" data-ai-label="看不见">看不见</button></div>
      <div hidden><button data-ai-ref="x.hiddenattr" data-ai-label="隐藏属性">隐藏属性</button></div>
      <div aria-hidden="true"><button data-ai-ref="x.ariahidden" data-ai-label="读屏忽略">读屏忽略</button></div>
      <button data-ai-ref="x.visible" data-ai-label="可见">可见</button>
    `), { risk: 'mutate-local' })

    const out = aiSnapshot({})
    expect(out.result).toContain('x.visible')
    expect(out.result).not.toContain('x.invisible')
    expect(out.result).not.toContain('x.hiddenattr')
    expect(out.result).not.toContain('x.ariahidden')
  })
})

describe('写动词：隐藏控件拒执且零写入', () => {
  it('ui_click 隐藏步骤的按钮 ⇒ 拒绝（UI_TARGET_HIDDEN），不产生任何点击', async () => {
    enterImportPage('REVIEW')
    const root = wizardRoot()
    registerRegion(root, { risk: 'mutate-local' })
    const button = root.querySelector('[data-ai-ref="import.upload.choose"]') as HTMLButtonElement
    let clicked = 0
    button.addEventListener('click', () => {
      clicked += 1
    })
    // 快照闸：先取一次快照（隐藏按钮不在已见集里，故这里用 fill 之外的路径观察围栏归因）
    aiSnapshot({})

    const out = await aiClick({ ref: 'import.upload.choose', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('rejected')
    expect(out.code).toBe('UI_TARGET_HIDDEN')
    expect(clicked).toBe(0)
  })

  it('ui_fill 隐藏步骤的输入框 ⇒ 拒绝且值不变', async () => {
    enterImportPage('REVIEW')
    const root = wizardRoot()
    registerRegion(root, { risk: 'mutate-local' })
    const input = root.querySelector('[data-ai-ref="import.upload.query"]') as HTMLInputElement
    // 隐藏控件不在快照里 ⇒ 先用「可见」快照把 ref 记进索引，再单独验证隐藏围栏
    aiSnapshot({})

    const out = await aiFill({ ref: 'import.upload.query', value: 'HACKED', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('rejected')
    expect(out.code).toBe('UI_TARGET_HIDDEN')
    expect(input.value).toBe('')
  })

  it('同一区域的可见控件照常可写（不得误判成隐藏）', async () => {
    enterImportPage('REVIEW')
    const root = wizardRoot()
    registerRegion(root, { risk: 'mutate-local' })
    const note = root.querySelector('[data-ai-ref="import.review.note"]') as HTMLInputElement
    aiSnapshot({})

    const out = await aiFill({ ref: 'import.review.note', value: 'ok', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('ok')
    expect(note.value).toBe('ok')
  })

  it('机器码穿过执行器边界后仍保留（UI_TARGET_HIDDEN 进闭集，不被静默丢弃）', async () => {
    enterImportPage('REVIEW')
    registerRegion(wizardRoot(), { risk: 'mutate-local' })
    aiSnapshot({})

    const execution = await executeFrontendTool({
      name: 'ui_fill',
      args: { ref: 'import.upload.query', value: 'HACKED', epoch: currentAiEpoch() },
      runId: 'visibility-run-code',
      budgetMs: 5_000
    })
    expect(execution.outcome).toBe('rejected')
    expect(execution.code).toBe('UI_TARGET_HIDDEN')
  })

  it('步骤切换（只改 v-show）后：旧步骤控件立刻不可写、新步骤控件可写', async () => {
    enterImportPage('UPLOAD')
    const root = wizardRoot()
    registerRegion(root, { risk: 'mutate-local' })
    aiSnapshot({})
    // 起始现场反过来：第 1 步可见、第 4 步隐藏（与 `enterImportPage('UPLOAD')` 一致）
    const step1 = root.querySelector('[data-step="1"]') as HTMLElement
    const step4 = root.querySelector('[data-step="4"]') as HTMLElement
    step1.style.display = ''
    step4.style.display = 'none'

    expect(await aiFill({ ref: 'import.upload.query', value: 'a', epoch: currentAiEpoch() })).toMatchObject({ outcome: 'ok' })

    // 用户点「下一步」：Vue 只是把两个 section 的 display 对调（不重新挂载、ref 不变）
    step1.style.display = 'none'
    step4.style.display = ''

    const hiddenNow = await aiFill({ ref: 'import.upload.query', value: 'b', epoch: currentAiEpoch() })
    expect(hiddenNow.outcome).toBe('rejected')
    expect(hiddenNow.code).toBe('UI_TARGET_HIDDEN')
    // 新步骤（现在可见）照常可写
    const visibleNow = await aiFill({ ref: 'import.review.note', value: 'c', epoch: currentAiEpoch() })
    expect(visibleNow.outcome).toBe('ok')
  })
})
