/**
 * AI 可视可操作专项 · `ui_snapshot` 与尺寸闸（设计稿 §3.3.1 / §3.7）。
 *
 * 覆盖：T-01/T-02（行数）、T-03（limit 参数闸）、T-04（字符闸）、T-05（无 ref 不收）、
 * T-07（内置 deny 四类不收）、T-11（label 推导链）、T-12（缓存）、T-50（value 回显最小化）、
 * T-53（kinds 全不匹配）。
 */
import { describe, expect, it, beforeEach, vi } from 'vitest'
import {
  UI_SNAPSHOT_DEFAULT_ROWS,
  UI_SNAPSHOT_MAX_CHARS,
  UI_SNAPSHOT_MAX_ROWS,
  UI_SNAPSHOT_VALUE_MAX_CHARS,
  aiSnapshot
} from '@/utils/ai-dom-tools'
import { currentAiEpoch } from '@/utils/ai-regions'
import { enterImportPage, makeRoot, registerRegion, resetAiTestState } from './ai-fixtures'

beforeEach(() => {
  resetAiTestState()
})

/** 行 = 以 `[` 起头的元素行。 */
function elementLines(result: string): string[] {
  return result.split('\n').filter((line) => line.startsWith('['))
}

function button(ref: string, label: string, extra = ''): string {
  return `<button data-ai-ref="${ref}" ${extra}>${label}</button>`
}

describe('T-01 / T-02：行数与尾行', () => {
  it('T-01 14 个元素、默认 limit=20 ⇒ 全回 + 尾行「共 14 个元素（本次回 14 个」', () => {
    enterImportPage()
    const rows = Array.from({ length: 14 }, (_, i) => button(`import.upload.b${i}`, `按钮${i}`)).join('')
    registerRegion(makeRoot(rows))
    const out = aiSnapshot({})
    expect(out.outcome).toBe('ok')
    expect(elementLines(out.result)).toHaveLength(14)
    expect(out.result).toContain('共 14 个元素（本次回 14 个')
    expect(out.result).toContain(String(UI_SNAPSHOT_MAX_ROWS))
    expect(out.result).toContain(String(UI_SNAPSHOT_MAX_CHARS))
  })

  it('T-02 30 个元素、limit=20 ⇒ 回 20 + 「共 30 …本次回 20…已截断」', () => {
    enterImportPage()
    const rows = Array.from({ length: 30 }, (_, i) => button(`import.upload.b${i}`, `按钮${i}`)).join('')
    registerRegion(makeRoot(rows))
    const out = aiSnapshot({ limit: 20 })
    expect(elementLines(out.result)).toHaveLength(20)
    expect(out.result).toContain('共 30 个元素')
    expect(out.result).toContain('本次回 20 个')
    expect(out.result).toContain('已截断')
  })

  it('默认 limit 即 UI_SNAPSHOT_DEFAULT_ROWS（20）', () => {
    expect(UI_SNAPSHOT_DEFAULT_ROWS).toBe(20)
    expect(UI_SNAPSHOT_MAX_ROWS).toBe(40)
    expect(UI_SNAPSHOT_MAX_CHARS).toBe(2000)
    expect(UI_SNAPSHOT_VALUE_MAX_CHARS).toBe(24)
  })
})

describe('T-03：limit 参数闸', () => {
  it('limit=50 越硬顶 ⇒ 参数拒绝（人话，不是 JSON）', () => {
    enterImportPage()
    registerRegion(makeRoot(button('import.upload.choose', '上传')))
    const out = aiSnapshot({ limit: 50 })
    expect(out.outcome).toBe('failed')
    expect(out.availability).toBe('unavailable')
    expect(out.result).toContain('40')
    expect(out.result).not.toContain('{')
  })

  it('limit=0 / 非数字同样拒绝', () => {
    enterImportPage()
    registerRegion(makeRoot(button('import.upload.choose', '上传')))
    expect(aiSnapshot({ limit: 0 }).outcome).toBe('failed')
    expect(aiSnapshot({ limit: 'abc' }).outcome).toBe('failed')
  })
})

describe('T-04：字符闸（先按行、再按字符）', () => {
  it('20 行但超 2000 字符 ⇒ 按字符截断，标记含「原 20 行」与「字符」，正文 ≤2000', () => {
    enterImportPage()
    const longRef = (i: number) => `import.upload.${'x'.repeat(50)}${i}`
    const rows = Array.from({ length: 20 }, (_, i) => button(longRef(i), `很长的按钮标题第${i}个`)).join('')
    registerRegion(makeRoot(rows))
    const out = aiSnapshot({})
    expect(out.result.length).toBeLessThanOrEqual(UI_SNAPSHOT_MAX_CHARS)
    expect(out.result).toContain('原 20 行')
    expect(out.result).toContain('字符')
    expect(elementLines(out.result).length).toBeLessThan(20)
    expect(out.result).toContain('共 20 个元素')
  })
})

describe('T-05：只有 data-ai-ref 的元素才可被看见', () => {
  it('无 ref 的元素不进快照（其可见文案零命中）', () => {
    enterImportPage()
    registerRegion(makeRoot(
      button('import.upload.choose', '上传')
        + '<button>无标注的危险按钮</button>'
        + '<div><span>游离文本</span></div>'
    ))
    const out = aiSnapshot({})
    expect(out.result).toContain('import.upload.choose')
    expect(out.result).not.toContain('无标注的危险按钮')
    expect(out.result).not.toContain('游离文本')
  })

  it('区域外元素不进快照（不做全文档遍历）', () => {
    enterImportPage()
    registerRegion(makeRoot(button('import.upload.choose', '上传')))
    const outside = makeRoot(button('export.start', '启动导出'))
    expect(outside.isConnected).toBe(true)
    const out = aiSnapshot({})
    expect(out.result).not.toContain('export.start')
  })
})

describe('T-07 / T-50：内置 deny 与 value 回显最小化', () => {
  it('password / file / hidden / autocomplete=cc-number 四类均不进快照（种子值负断言）', () => {
    enterImportPage()
    registerRegion(makeRoot([
      '<input type="password" data-ai-ref="import.upload.pwd" data-ai-fill="allow" value="SEED-PWD" />',
      '<input type="file" data-ai-ref="import.upload.file" />',
      '<input type="hidden" data-ai-ref="import.upload.hidden" value="SEED-HIDDEN" />',
      '<input type="text" autocomplete="cc-number" data-ai-ref="import.upload.cc" data-ai-fill="allow" value="SEED-CC" />',
      '<input type="text" data-ai-ref="import.upload.ok" data-ai-fill="allow" value="OK-VALUE" />'
    ].join('')))
    const out = aiSnapshot({})
    expect(out.result).toContain('import.upload.ok')
    for (const ref of ['import.upload.pwd', 'import.upload.file', 'import.upload.hidden', 'import.upload.cc']) {
      expect(out.result).not.toContain(ref)
    }
    expect(out.result).not.toContain('SEED-PWD')
    expect(out.result).not.toContain('SEED-HIDDEN')
    expect(out.result).not.toContain('SEED-CC')
    // 只有 fill=allow 的输入框回显值
    expect(out.result).toContain('value="OK-VALUE"')
  })

  it('未标 fill=allow 的输入框即使可写也不回显 value', () => {
    enterImportPage()
    registerRegion(makeRoot('<input type="text" data-ai-ref="import.upload.plain" value="PLAIN-VALUE" />'))
    const out = aiSnapshot({})
    expect(out.result).toContain('import.upload.plain')
    expect(out.result).not.toContain('PLAIN-VALUE')
  })

  it('超 24 字符的值被截断并带省略号', () => {
    enterImportPage()
    const long = 'A'.repeat(40)
    registerRegion(makeRoot(`<input type="text" data-ai-ref="import.upload.long" data-ai-fill="allow" value="${long}" />`))
    const out = aiSnapshot({})
    expect(out.result).not.toContain(long)
    expect(out.result).toContain('…')
  })
})

describe('T-11：label 推导链', () => {
  it('aria-label 优先于 title 与 textContent', () => {
    enterImportPage()
    registerRegion(makeRoot(
      '<button data-ai-ref="import.upload.a" aria-label="上传入口" title="标题文案">按钮文案</button>'
    ))
    expect(aiSnapshot({}).result).toContain('"上传入口"')
  })

  it('无 aria-label 时用 title，再退 textContent（空白归一 + 截 24）', () => {
    enterImportPage()
    const long = '这是一个非常非常长的按钮文案用来验证截断行为是否正确生效'
    registerRegion(makeRoot(
      '<button data-ai-ref="import.upload.b" title="标题文案">按钮文案</button>'
        + `<button data-ai-ref="import.upload.c">  ${long}  </button>`
    ))
    const out = aiSnapshot({})
    expect(out.result).toContain('"标题文案"')
    expect(out.result).not.toContain(long)
    expect(out.result).toContain(`"${long.slice(0, 24)}`)
  })
})

describe('T-12：同 epoch 同参缓存', () => {
  it('连调两次文本一致，且区域遍历只发生一次', () => {
    enterImportPage()
    const root = makeRoot(button('import.upload.choose', '上传'))
    registerRegion(root)
    // 注册期的一次性索引扫描不计入快照遍历
    const spy = vi.spyOn(root, 'querySelectorAll')
    const first = aiSnapshot({})
    const second = aiSnapshot({})
    expect(second.result).toBe(first.result)
    expect(spy).toHaveBeenCalledTimes(1)
  })

  it('epoch 变化后缓存失效（快照重新遍历）', () => {
    enterImportPage()
    const root = makeRoot(button('import.upload.choose', '上传'))
    registerRegion(root)
    const spy = vi.spyOn(root, 'querySelectorAll')
    aiSnapshot({})
    registerRegion(root) // 覆盖注册 ⇒ epoch+1（索引扫描会调一次，故先清计数）
    spy.mockClear()
    aiSnapshot({})
    expect(spy).toHaveBeenCalledTimes(1)
  })
})

describe('T-53：kinds 全不匹配', () => {
  it('头行 + 「共 0 个元素（本次回 0 个」', () => {
    enterImportPage()
    registerRegion(makeRoot(button('import.upload.choose', '上传')))
    const out = aiSnapshot({ kinds: ['link'] })
    expect(out.outcome).toBe('ok')
    expect(elementLines(out.result)).toHaveLength(0)
    expect(out.result).toContain('共 0 个元素（本次回 0 个')
    expect(out.result).toContain(`epoch=${currentAiEpoch()}`)
  })
})

describe('T-05b：区域过滤与头行', () => {
  it('regions 过滤只回指定区域；头行给出区域数', () => {
    enterImportPage()
    registerRegion(makeRoot(button('import.upload.choose', '上传')))
    registerRegion(makeRoot(button('import.mode.merge', '增量合并')), { id: 'import.mode', risk: 'mutate-local' })
    const out = aiSnapshot({ regions: ['import.mode'] })
    expect(out.result).toContain('import.mode.merge')
    expect(out.result).not.toContain('import.upload.choose')
    expect(out.result).toContain('区域 1 个')
  })
})

describe('路由 1 集成：ui_snapshot 经真实前端工具通道执行', () => {
  it('executeFrontendTool 走登记表 + 默认执行器，回灌带尾行（不是绕过通道直调）', async () => {
    const { executeFrontendTool } = await import('@/utils/frontend-tools')
    enterImportPage()
    registerRegion(makeRoot(button('import.upload.choose', '上传')))
    const execution = await executeFrontendTool({ name: 'ui_snapshot', args: {}, budgetMs: 5_000 })
    expect(execution.outcome).toBe('ok')
    expect(execution.availability).toBe('available')
    expect(execution.result).toContain('共 1 个元素（本次回 1 个')
    expect(execution.result).toContain('import.upload.choose')
  })
})
