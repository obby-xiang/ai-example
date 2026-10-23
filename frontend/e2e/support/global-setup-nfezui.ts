import { startNfezuiServices, stopNfezuiServices } from './harness-nfezui'

export default async function globalSetup(): Promise<void> {
  try {
    await startNfezuiServices()
  } catch (error) {
    await stopNfezuiServices()
    throw error
  }
}
