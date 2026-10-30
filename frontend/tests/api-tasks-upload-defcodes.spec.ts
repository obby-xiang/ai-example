/**
 * `api/tasks.ts#uploadFile` 的 multipart 契约：**当前草案的 defCodes 随上传请求携带**。
 *
 * 后端 `FileController#upload` 用请求里的 `defCodes` 做文件名匹配（不再依赖"上传前已写入的
 * TaskItem"）；因此前端必须把页面此刻的选择集放进同一个 multipart 请求，而不是先发一次
 * `POST /select-defs`。
 *
 * 本 spec 只钉 client 层契约（FormData 的字段形态），页面行为见
 * `import-draft-selection-commit-point.spec.ts`。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

const httpUpload = vi.hoisted(() => vi.fn())

vi.mock('@/api/http', async (importOriginal) => ({
  ...(await importOriginal<object>()) as object,
  upload: httpUpload
}))

import { uploadFile } from '@/api/tasks'

beforeEach(() => {
  vi.clearAllMocks()
  httpUpload.mockResolvedValue({ matchedFiles: [], unmatchedFiles: [], count: 0 })
})

function formOf(call: unknown[]): FormData {
  const form = call[1]
  expect(form).toBeInstanceOf(FormData)
  return form as FormData
}

describe('tasksApi.uploadFile · defCodes 随请求携带', () => {
  it('defCodes 作为重复的 multipart 字段携带，file 字段不变', async () => {
    const file = new File(['x'], 'COUNTRY.xlsx')

    await uploadFile(5, file, ['CURRENCY', 'COUNTRY'])

    expect(httpUpload).toHaveBeenCalledTimes(1)
    const [url] = httpUpload.mock.calls[0] ?? []
    expect(url).toBe('/tasks/5/files/upload')
    const form = formOf(httpUpload.mock.calls[0] ?? [])
    expect(form.get('file')).toBe(file)
    // 修复前（RED）：FormData 里只有 file ⇒ 后端只能退回"按已写入的 TaskItem 匹配"
    expect(form.getAll('defCodes')).toEqual(['CURRENCY', 'COUNTRY'])
  })

  it('未给 defCodes（旧调用面）⇒ 不发空字段，请求形态与改动前一致', async () => {
    const file = new File(['x'], 'CURRENCY.xlsx')

    await uploadFile(5, file)

    const form = formOf(httpUpload.mock.calls[0] ?? [])
    expect(form.get('file')).toBe(file)
    expect(form.getAll('defCodes')).toEqual([])
  })
})
