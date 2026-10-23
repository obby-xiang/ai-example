import { stopServices } from './harness'

export default async function globalTeardown(): Promise<void> {
  await stopServices()
}
