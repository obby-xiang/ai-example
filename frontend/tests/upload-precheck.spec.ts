/**
 * F9 上传预检：扩展名 / 大小常量的**单一口径**与判定。
 *
 * 上传链（`ImportWizardView#uploadOne`）在发包前用 `checkUploadFile` 拦掉明显非法的文件；
 * 这里锁的是常量的取值与判定边界（后端权威值 = `spring.servlet.multipart.max-file-size: 5MB`），
 * 以及"选择器 accept 与硬拦判据同源"（防止两处各写一份 `.xlsx` / `.zip`）。
 */
import { describe, expect, it } from 'vitest'
import {
  UPLOAD_ACCEPT,
  UPLOAD_EXTENSIONS,
  UPLOAD_MAX_BYTES,
  UPLOAD_MSG_KEYS,
  checkUploadFile,
  isUploadExtensionAllowed,
  uploadExtensionOf
} from '@/constants/upload'

describe('constants/upload.ts · 扩展名口径', () => {
  it('accept 串由扩展名表派生（选择器与硬拦同源）', () => {
    expect([...UPLOAD_EXTENSIONS]).toEqual(['.xlsx', '.zip'])
    expect(UPLOAD_ACCEPT).toBe(UPLOAD_EXTENSIONS.join(','))
  })

  it('扩展名取小写、无扩展名（或点在末尾）返回空串', () => {
    expect(uploadExtensionOf('COUNTRY.XLSX')).toBe('.xlsx')
    expect(uploadExtensionOf('batch.Zip')).toBe('.zip')
    expect(uploadExtensionOf('COUNTRY')).toBe('')
    expect(uploadExtensionOf('COUNTRY.')).toBe('')
  })

  it('只放行 .xlsx / .zip', () => {
    expect(isUploadExtensionAllowed('COUNTRY.xlsx')).toBe(true)
    expect(isUploadExtensionAllowed('batch.zip')).toBe(true)
    expect(isUploadExtensionAllowed('COUNTRY.xls')).toBe(false)
    expect(isUploadExtensionAllowed('COUNTRY.csv')).toBe(false)
    expect(isUploadExtensionAllowed('COUNTRY.xlsx.exe')).toBe(false)
  })
})

describe('constants/upload.ts · checkUploadFile 预检', () => {
  it('大小上限 = 后端 5MB（5 × 1024 × 1024 字节）', () => {
    expect(UPLOAD_MAX_BYTES).toBe(5 * 1024 * 1024)
  })

  it('合法文件通过（边界值恰好 5MB 放行，交给后端裁决）', () => {
    expect(checkUploadFile({ name: 'COUNTRY.xlsx', size: 1 })).toBeNull()
    expect(checkUploadFile({ name: 'batch.zip', size: UPLOAD_MAX_BYTES })).toBeNull()
  })

  it('扩展名不符 ⇒ type 拒绝（locale 键走常量，不在代码里硬编码文案）', () => {
    expect(checkUploadFile({ name: 'COUNTRY.csv', size: 10 })).toEqual({
      kind: 'type',
      key: UPLOAD_MSG_KEYS.unsupportedType,
      name: 'COUNTRY.csv'
    })
  })

  it('超过 5MB ⇒ size 拒绝', () => {
    expect(checkUploadFile({ name: 'COUNTRY.xlsx', size: UPLOAD_MAX_BYTES + 1 })).toEqual({
      kind: 'size',
      key: UPLOAD_MSG_KEYS.tooLarge,
      name: 'COUNTRY.xlsx'
    })
  })

  it('扩展名先于大小判定（两者都不符时报格式）', () => {
    expect(checkUploadFile({ name: 'COUNTRY.csv', size: UPLOAD_MAX_BYTES * 2 })?.kind).toBe('type')
  })
})

describe('预检文案键：locale 契约（实体由 locales 维护，本模块只引用键名）', () => {
  it('中英两套 locale 都提供两个键，且都带 {name} 插值', async () => {
    const zhCN = (await import('@/locales/zh-CN.json')).default as Record<string, unknown>
    const enUS = (await import('@/locales/en-US.json')).default as Record<string, unknown>
    for (const messages of [zhCN, enUS]) {
      for (const key of [UPLOAD_MSG_KEYS.unsupportedType, UPLOAD_MSG_KEYS.tooLarge]) {
        const text = key.split('.').reduce<unknown>(
          (node, part) => (node && typeof node === 'object' ? (node as Record<string, unknown>)[part] : undefined),
          messages
        )
        expect(typeof text, `${key} 应在 locale 中存在`).toBe('string')
        expect(String(text)).toContain('{name}')
      }
    }
  })
})
