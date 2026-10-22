/**
 * 依赖合规批（2026-10-22）：ZIP 打包/解包只走 JSZip。
 *
 * `utils/excel.ts` 的 `zipBlobs` / `unzip` 是全仓唯一的 ZIP 读写出口（`fflate` 从未进入依赖树，
 * 合规章 §E 的「fflate 换 jszip」按现状即已满足）。既有用例对 `zipBlobs` 全是 mock 计数断言，
 * 故这里直接驱动真实 JSZip 做往返，锁住"换库/升库后解包结果仍与打包输入逐字节一致"。
 */
import { describe, expect, it } from 'vitest'
import JSZip from 'jszip'
import { unzip, zipBlobs } from '@/utils/excel'

const text = (data: ArrayBuffer) => new TextDecoder().decode(data)

describe('excel zip 往返（真实 JSZip）', () => {
  it('打包后可解回同名、同序、同字节的条目', async () => {
    const blob = await zipBlobs([
      { name: 'a.txt', blob: new Blob(['alpha']) },
      { name: 'b.txt', blob: new Blob(['beta']) }
    ])
    const entries = await unzip(blob)
    expect(entries.map((entry) => entry.name)).toEqual(['a.txt', 'b.txt'])
    expect(entries.map((entry) => text(entry.data))).toEqual(['alpha', 'beta'])
  })

  it('重名条目自动加 (2) 后缀，原条目不被覆盖', async () => {
    const blob = await zipBlobs([
      { name: 'dup.xlsx', blob: new Blob(['first']) },
      { name: 'dup.xlsx', blob: new Blob(['second']) }
    ])
    const entries = await unzip(blob)
    expect(entries.map((entry) => entry.name)).toEqual(['dup.xlsx', 'dup(2).xlsx'])
    expect(entries.map((entry) => text(entry.data))).toEqual(['first', 'second'])
  })

  it('解包跳过目录条目、只返回文件', async () => {
    const zip = new JSZip()
    zip.folder('dir')
    zip.file('dir/inner.txt', 'inner')
    zip.file('top.txt', 'top')
    const blob = await zip.generateAsync({ type: 'blob' })
    const entries = await unzip(blob)
    expect(entries.map((entry) => entry.name).sort()).toEqual(['dir/inner.txt', 'top.txt'])
  })

  it('非 ZIP 字节解包报错（不静默返回空集）', async () => {
    await expect(unzip(new Blob(['not a zip at all']))).rejects.toThrow()
  })
})
