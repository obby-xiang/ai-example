/** NFEZUI 域的 globalTeardown —— 统一 harness 按本域 `E2E_RUN_DIR` 的 pid 台账收尾。 */
import { stopServices } from './harness'

export default async function globalTeardown(): Promise<void> {
  await stopServices()
}
