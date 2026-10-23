/**
 * NFEZUI 域的 globalSetup —— 统一 harness（`support/harness.ts`）+ 本域 env。
 *
 * 本域不再有 harness 副本：端口段 / 运行目录 / Redis 库 / 本域桩脚本 / 后端附加参数全部由
 * `e2e/playwright.nfezui.config.ts` 在加载期补进 `process.env`（显式 env 优先），
 * 这里只负责「起 / 失败即收」。
 */
import { startServices, stopServices } from './harness'

export default async function globalSetup(): Promise<void> {
  try {
    await startServices()
  } catch (error) {
    await stopServices()
    throw error
  }
}
