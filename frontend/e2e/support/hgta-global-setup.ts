/**
 * H/G/T/A 域的 globalSetup（只被 `playwright.hgta.config.ts` 引用）。
 *
 * 三条口径：
 * 1. 已有实例（整仓跑的 globalSetup 或 E2E_EXTERNAL=1）⇒ 一律不动，只写"不是我们起的"标记；
 * 2. 自起时用 `harness-hgta`（独立 RUN_DIR）并写标记文件，teardown 据此决定收不收；
 * 3. setup 中途失败也要先收干净，不占专用端口段。
 */
import { existsSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { RUN_DIR, startServices, stopServices, URLS } from './harness-hgta'
import { mkdirSync } from 'node:fs'

const MARKER = join(RUN_DIR, 'hgta-started.by-us')

async function alive(url: string): Promise<boolean> {
  try {
    const res = await fetch(url)
    return res.status < 500
  } catch {
    return false
  }
}

export default async function globalSetup(): Promise<void> {
  if (process.env.E2E_EXTERNAL === '1') return
  if (await alive(`${URLS.api}/api/ai/health`) && await alive(`${URLS.app}/`)) {
    // 别的入口已经起好了（整仓 globalSetup 用默认端口时端口不同，不会命中这里；
    // 命中即说明是本域端口段上的现成实例）—— 不接管、不收尾。
    return
  }
  try {
    await startServices({ only: ['main', 'app'] })
  } catch (error) {
    await stopServices()
    throw error
  }
  mkdirSync(RUN_DIR, { recursive: true })
  writeFileSync(MARKER, String(Date.now()))
}

export function ownsServices(): boolean {
  return existsSync(MARKER)
}
