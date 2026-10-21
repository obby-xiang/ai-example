/**
 * AI 可视可操作专项 · ref 的 locale 无关性（设计稿 §3.4.2 第一性质 / T-44）。
 *
 * i18n 前置约束：`data-ai-ref` 是语义键，切语言后**必须逐字不变**；
 * 可见文案可以变（`data-ai-label` / 推导出的 label 参与展示，但 ref 不变）。
 */
import { describe, expect, it, beforeEach } from 'vitest'
import { aiSnapshot } from '@/utils/ai-dom-tools'
import { enterImportPage, makeRoot, registerRegion, resetAiTestState } from './ai-fixtures'

beforeEach(() => {
  resetAiTestState()
})

function refsOf(snapshot: string): string[] {
  return snapshot
    .split('\n')
    .filter((line) => line.startsWith('['))
    .map((line) => line.slice(1, line.indexOf(']')))
}

describe('T-44：英文 locale 下同一元素两次快照 ref 完全一致', () => {
  it('切换可见文案后 ref 不变、label 变', () => {
    enterImportPage()
    const root = makeRoot('<button data-ai-ref="import.upload.choose">上传 xlsx / zip</button>')
    registerRegion(root)
    const zh = aiSnapshot({}).result
    expect(refsOf(zh)).toEqual(['import.upload.choose'])
    expect(zh).toContain('上传 xlsx / zip')

    // 切到英文（可见文案变，ref 不动）
    const button = root.querySelector('button') as HTMLButtonElement
    button.textContent = 'Upload xlsx / zip'
    registerRegion(root) // 重新注册仅用于刷新 epoch 与缓存
    const en = aiSnapshot({}).result
    expect(refsOf(en)).toEqual(['import.upload.choose'])
    expect(en).not.toContain('上传 xlsx / zip')
    expect(en).toContain('Upload xlsx / zip')
  })

  it('ref 不从文案派生：两个文案相同的元素仍是两个不同 ref', () => {
    enterImportPage()
    registerRegion(makeRoot(
      '<button data-ai-ref="import.upload.a">确定</button>'
        + '<button data-ai-ref="import.upload.b">确定</button>'
    ))
    const refs = refsOf(aiSnapshot({}).result)
    expect(refs).toEqual(['import.upload.a', 'import.upload.b'])
  })

  it('epoch 也随刷新变化（旧快照不得据以断言当前界面）', () => {
    enterImportPage()
    registerRegion(makeRoot('<button data-ai-ref="import.upload.choose">上传</button>'))
    const first = aiSnapshot({}).result
    registerRegion(makeRoot('<button data-ai-ref="import.upload.choose">上传</button>'))
    const second = aiSnapshot({}).result
    const epochOf = (text: string) => /epoch=([a-z0-9]+-\d+)/.exec(text)?.[1]
    expect(epochOf(first)).toBeTruthy()
    expect(epochOf(second)).toBeTruthy()
    expect(epochOf(second)).not.toBe(epochOf(first))
  })
})
