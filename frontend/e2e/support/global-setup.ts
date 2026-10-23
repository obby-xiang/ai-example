import { startServices, stopServices } from './harness'

export default async function globalSetup(): Promise<void> {
  try {
    await startServices()
  } catch (error) {
    // setup 失败也要收掉已起的子进程，避免占住专用端口段。
    await stopServices()
    throw error
  }
}
