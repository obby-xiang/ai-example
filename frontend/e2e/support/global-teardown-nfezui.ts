import { stopNfezuiServices } from './harness-nfezui'

export default async function globalTeardown(): Promise<void> {
  await stopNfezuiServices()
}
