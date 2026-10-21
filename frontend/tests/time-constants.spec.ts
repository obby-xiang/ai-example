/**
 * 前端 60s 常量的单一事实源（超时面治理批① 第 4 项）。
 *
 * 锁定三处引用同一绑定：`utils/time-constants.ts` → `api/http.ts`（axios 上限 + 转出）、
 * `utils/frontend-tools.ts`（回灌 POST 余量）、`stores/workspace.ts`（下载拉取上限）。
 * 此前是三份 `60_000` 字面量，只在注释里互相"对齐"——改一处不会带动另外两处。
 */
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

import { http, HTTP_TIMEOUT_MS as RE_EXPORTED } from '@/api/http'
import { HTTP_TIMEOUT_MS } from '@/utils/time-constants'
import { FRONTEND_TOOL_RESULT_HEADROOM_MS, FRONTEND_TOOL_TIMEOUT_BUDGET_MS } from '@/utils/frontend-tools'

const SRC = path.join(process.cwd(), 'src')

describe('HTTP 超时常量单源（超时面治理批①）', () => {
  it('时限本体 60s，axios 实例直接用它', () => {
    expect(HTTP_TIMEOUT_MS).toBe(60_000)
    expect(http.defaults.timeout).toBe(HTTP_TIMEOUT_MS)
  })

  it('api/http.ts 的转出与原值同一绑定（既有调用方零改动）', () => {
    expect(RE_EXPORTED).toBe(HTTP_TIMEOUT_MS)
  })

  it('回灌 POST 余量与 axios 上限同源（保证链 预算 + 余量 ≤ 服务端上限）', () => {
    expect(FRONTEND_TOOL_RESULT_HEADROOM_MS).toBe(HTTP_TIMEOUT_MS)
    expect(FRONTEND_TOOL_TIMEOUT_BUDGET_MS + FRONTEND_TOOL_RESULT_HEADROOM_MS).toBe(120_000)
  })

  it('stores/workspace.ts 的下载上限引用同一常量（不再自带字面量）', () => {
    const source = readFileSync(path.join(SRC, 'stores/workspace.ts'), 'utf8')
    expect(source).toContain("import { HTTP_TIMEOUT_MS } from '@/utils/time-constants'")
    expect(source).toContain('const DOWNLOAD_FETCH_TIMEOUT_MS = HTTP_TIMEOUT_MS')
    expect(source).not.toContain('const DOWNLOAD_FETCH_TIMEOUT_MS = 60_000')
  })

  it('余量常量不再自带字面量（预算常量自身的 60_000 是另一语义，不在此列）', () => {
    const source = readFileSync(path.join(SRC, 'utils/frontend-tools.ts'), 'utf8')
    expect(source).toContain('export const FRONTEND_TOOL_RESULT_HEADROOM_MS = HTTP_TIMEOUT_MS')
    expect(source).not.toContain('export const FRONTEND_TOOL_RESULT_HEADROOM_MS = 60_000')
  })
})
