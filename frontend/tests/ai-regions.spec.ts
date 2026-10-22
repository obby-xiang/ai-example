/**
 * AI 可视可操作专项 · 区域注册表与 epoch（设计稿 §3.4.1 / §8.4）。
 *
 * 覆盖：T-13（pageId 变化 ⇒ counter+1）、T-14（**刷新后旧 epoch 不得撞号**，nonce 关键）、
 * T-15（缺 risk fail-fast）、T-47（区域拆掉再挂回）、T-54（ref 段字符集含大写/下划线）。
 */
import { describe, expect, it, vi, beforeEach } from 'vitest'
import {
  currentAiEpoch,
  getAiRegion,
  listAiRegions,
  noteAiPage,
  registerAiRegion,
  resolveAiRef,
  unregisterAiRegion
} from '@/utils/ai-regions'
import { aiClick, aiSnapshot } from '@/utils/ai-dom-tools'
import { enterExportPage, enterImportPage, makeRoot, registerRegion, resetAiTestState } from './ai-fixtures'

beforeEach(() => {
  resetAiTestState()
})

describe('T-15：区域注册 fail-fast（risk 必填）', () => {
  it('缺 risk 抛错，且不产生半注册状态', () => {
    const root = makeRoot('<button data-ai-ref="import.upload.choose">上传</button>')
    expect(() => registerAiRegion({ id: 'import.upload', root } as never)).toThrowError(/risk/)
    expect(listAiRegions()).toHaveLength(0)
    expect(getAiRegion('import.upload')).toBeNull()
  })

  it('空 id / detached root 同样抛错', () => {
    const root = makeRoot('<button data-ai-ref="import.upload.choose">上传</button>')
    expect(() => registerAiRegion({ id: '', root, risk: 'read' })).toThrowError()
    const detached = document.createElement('div')
    expect(() => registerAiRegion({ id: 'import.upload', root: detached, risk: 'read' })).toThrowError()
    expect(listAiRegions()).toHaveLength(0)
  })
})

describe('T-13：pageId 变化 ⇒ epoch counter+1 且缓存失效', () => {
  it('noteAiPage 换页后 epoch 变化，counter 递增且 nonce 不变', () => {
    enterImportPage()
    const before = currentAiEpoch()
    noteAiPage('export')
    const after = currentAiEpoch()
    expect(after).not.toBe(before)
    const [nonceBefore, counterBefore] = before.split('-')
    const [nonceAfter, counterAfter] = after.split('-')
    expect(nonceAfter).toBe(nonceBefore)
    expect(Number(counterAfter)).toBe(Number(counterBefore) + 1)
  })

  it('同一页重复 noteAiPage 不增计数（进入页面不刷 epoch）', () => {
    enterImportPage()
    const before = currentAiEpoch()
    noteAiPage('import')
    expect(currentAiEpoch()).toBe(before)
  })
})

describe('T-14：刷新后旧 epoch 撞号必须被拒绝（nonce 关键用例）', () => {
  it('重新加载模块（新 nonce、counter 归位）后字符串不等', async () => {
    const first = currentAiEpoch()
    vi.resetModules()
    const reloaded = await import('@/utils/ai-regions')
    const second = reloaded.currentAiEpoch()
    expect(second).not.toBe(first)
    expect(second.split('-')[0]).not.toBe(first.split('-')[0])
  })

  it('epoch 形状 = 6 位 base36 nonce + 计数器（与后端 Guard 正则同款）', () => {
    expect(currentAiEpoch()).toMatch(/^[a-z0-9]{6}-\d+$/)
  })
})

describe('T-47：区域卸载再挂回', () => {
  it('卸载后旧 ref 解析失败；重注册后 epoch+1、新快照可取', () => {
    enterImportPage()
    const root = makeRoot('<button data-ai-ref="import.upload.choose">上传</button>')
    registerRegion(root)
    expect(resolveAiRef('import.upload.choose')).not.toBeNull()
    const epochBefore = currentAiEpoch()

    unregisterAiRegion('import.upload')
    expect(resolveAiRef('import.upload.choose')).toBeNull()
    expect(getAiRegion('import.upload')).toBeNull()
    expect(Number(currentAiEpoch().split('-')[1])).toBeGreaterThan(Number(epochBefore.split('-')[1]))

    registerRegion(root)
    expect(resolveAiRef('import.upload.choose')).not.toBeNull()
  })

  it('同 id 重复注册 = 覆盖 + epoch+1（幂等形态）', () => {
    enterImportPage()
    const root = makeRoot('<button data-ai-ref="import.upload.choose">上传</button>')
    registerRegion(root)
    const first = currentAiEpoch()
    registerRegion(root)
    expect(Number(currentAiEpoch().split('-')[1])).toBeGreaterThan(Number(first.split('-')[1]))
    expect(listAiRegions()).toHaveLength(1)
  })
})

describe('T-54（指挥官裁决 1）：ref 段字符集 [A-Za-z0-9_-]+', () => {
  it('大写 defCode 与下划线 ref 可注册、可解析（导入页真实形态）', () => {
    enterImportPage()
    const root = makeRoot(
      '<input type="checkbox" data-ai-ref="import.selectDefs.CURRENCY" />'
        + '<input type="checkbox" data-ai-ref="import.selectDefs.E2E_TEMP" />'
    )
    registerRegion(root, { id: 'import.selectDefs' })
    expect(resolveAiRef('import.selectDefs.CURRENCY')?.el).toBeTruthy()
    expect(resolveAiRef('import.selectDefs.E2E_TEMP')?.el).toBeTruthy()
  })

  it('ref 里的引号/空格/类选择器形态无法解析（不做选择器求值）', () => {
    enterImportPage()
    const root = makeRoot('<button data-ai-ref="import.upload.choose">上传</button>')
    registerRegion(root)
    expect(resolveAiRef('import.upload.choose"] , [data-x')).toBeNull()
    expect(resolveAiRef('#any')).toBeNull()
    expect(resolveAiRef('')).toBeNull()
  })
})

describe('区域解析与风险继承', () => {
  it('元素未标 risk ⇒ 继承区域风险', () => {
    enterImportPage()
    const root = makeRoot('<button data-ai-ref="import.upload.flush">保存</button>')
    registerRegion(root, { risk: 'consequential' })
    expect(resolveAiRef('import.upload.flush')?.risk).toBe('consequential')
  })

  it('元素 risk 显式声明即覆盖（允许低于区域，裁决：显式即授权）', () => {
    enterImportPage()
    const root = makeRoot('<button data-ai-ref="import.upload.templates" data-ai-risk="read">下载模板</button>')
    registerRegion(root, { risk: 'consequential' })
    expect(resolveAiRef('import.upload.templates')?.risk).toBe('read')
  })

  it('导出页拿导入页 ref：解析仍在表里，但任何动词都被上下文围栏拒绝（围栏② 的职责）', async () => {
    enterImportPage()
    const root = makeRoot('<button data-ai-ref="import.upload.choose">上传</button>')
    registerRegion(root)
    enterExportPage()
    expect(resolveAiRef('import.upload.choose')).not.toBeNull()
    aiSnapshot({})
    const out = await aiClick({ ref: 'import.upload.choose', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('failed')
    expect(out.result).toContain('当前页面为「export」')
  })

  it('区域 page 缺省取 ref 第一段', () => {
    enterImportPage()
    const root = makeRoot('<button data-ai-ref="import.upload.choose">上传</button>')
    registerRegion(root)
    expect(getAiRegion('import.upload')?.page).toBe('import')
  })
})
