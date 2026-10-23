/** H/G/T/A 域的 globalTeardown：只收自己起的实例（标记文件由 hgta-global-setup 写）。 */
import { rmSync } from 'node:fs'
import { join } from 'node:path'
import { RUN_DIR, stopServices } from './harness'

const MARKER = join(RUN_DIR, 'hgta-started.by-us')

export default async function globalTeardown(): Promise<void> {
  if (process.env.E2E_EXTERNAL === '1') return
  try {
    rmSync(MARKER)
    await stopServices()
  } catch (error) {
    // 收尾失败不吞掉断言结果，但要让日志看得见（下次起服务前还有端口占用自检兜底）
    // eslint-disable-next-line no-console
    console.warn('[hgta-teardown]', error instanceof Error ? error.message : String(error))
  }
}
