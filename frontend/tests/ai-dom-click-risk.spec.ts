/**
 * AI 可视可操作专项 · `ui_click` 的风险分流（设计稿 §3.6，本专项的安全性质）。
 *
 * 覆盖：T-31（consequential **零点击** + 指路注册动作）、T-32（元素未标 risk ⇒ 继承区域）。
 * "点了再提示"是明确禁止的伪确认形态：点击次数一律 spy 钉死。
 */
import { describe, expect, it, beforeEach, vi } from 'vitest'
import { aiClick, aiSnapshot, beginUiTurn } from '@/utils/ai-dom-tools'
import { currentAiEpoch } from '@/utils/ai-regions'
import { enterExportPage, makeRoot, registerRegion, resetAiTestState } from './ai-fixtures'

beforeEach(() => {
  resetAiTestState()
  beginUiTurn('t-risk')
})

describe('T-31：consequential 拒执', () => {
  it('export.start：零点击 + 「未执行」+「请改用注册动作 start_export」', async () => {
    enterExportPage()
    registerRegion(makeRoot(
      '<button data-ai-ref="export.start" data-ai-risk="consequential">启动导出</button>'
    ), { id: 'export.start', risk: 'consequential' })
    aiSnapshot({})
    const node = document.querySelector('[data-ai-ref="export.start"]') as HTMLElement
    const spy = vi.spyOn(node, 'click')
    const out = await aiClick({ ref: 'export.start', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('failed')
    expect(out.result).toContain('未执行')
    expect(out.result).toContain('请改用注册动作 start_export')
    expect(spy).toHaveBeenCalledTimes(0)
  })

  it('无对应注册动作的后果性按钮：指路「请提示用户手动完成」', async () => {
    enterExportPage()
    registerRegion(makeRoot(
      '<button data-ai-ref="export.save" data-ai-risk="consequential">保存到服务端</button>'
    ), { id: 'export.save', risk: 'consequential' })
    const spy = vi.spyOn(document.querySelector('[data-ai-ref="export.save"]') as HTMLElement, 'click')
    aiSnapshot({})
    const out = await aiClick({ ref: 'export.save', epoch: currentAiEpoch() })
    expect(out.result).toContain('未执行')
    expect(out.result).toContain('请提示用户手动完成')
    expect(spy).toHaveBeenCalledTimes(0)
  })

  it('上传入口（consequential）只能走 ui_request_upload，不能 click', async () => {
    enterExportPage()
    registerRegion(makeRoot(
      '<button data-ai-ref="export.upload.choose" data-ai-risk="consequential">上传</button>'
    ), { id: 'export.upload', risk: 'consequential', uploadEntry: 'export.upload.choose' })
    const spy = vi.spyOn(document.querySelector('[data-ai-ref="export.upload.choose"]') as HTMLElement, 'click')
    aiSnapshot({})
    const out = await aiClick({ ref: 'export.upload.choose', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('failed')
    expect(out.result).toContain('ui_request_upload')
    expect(spy).toHaveBeenCalledTimes(0)
  })
})

describe('T-32：元素级 risk 缺省继承区域风险', () => {
  it('区域 consequential、元素无 risk ⇒ 拒执', async () => {
    enterExportPage()
    registerRegion(makeRoot('<button data-ai-ref="export.publish.go">发布</button>'), {
      id: 'export.publish',
      risk: 'consequential'
    })
    const spy = vi.spyOn(document.querySelector('[data-ai-ref="export.publish.go"]') as HTMLElement, 'click')
    aiSnapshot({})
    const out = await aiClick({ ref: 'export.publish.go', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('failed')
    expect(out.result).toContain('consequential')
    expect(spy).toHaveBeenCalledTimes(0)
  })

  it('元素显式声明 read ⇒ 覆盖区域 consequential（显式即授权，允许降低）', async () => {
    enterExportPage()
    registerRegion(makeRoot(
      '<button data-ai-ref="export.download.tpl" data-ai-risk="read">下载模板</button>'
    ), { id: 'export.download', risk: 'consequential' })
    const spy = vi.spyOn(document.querySelector('[data-ai-ref="export.download.tpl"]') as HTMLElement, 'click')
    aiSnapshot({})
    const out = await aiClick({ ref: 'export.download.tpl', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('ok')
    expect(spy).toHaveBeenCalledTimes(1)
  })
})
