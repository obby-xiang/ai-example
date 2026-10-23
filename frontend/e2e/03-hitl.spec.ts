/**
 * 批① P0 · H 域（HITL 确认门）+ P-05（挂起中停止）
 * H-02 单卡拒绝 → 零执行
 * H-09 不可撤销卡复选框：未勾禁用 → 勾选可用 → 取消勾选回退 → 勾选批准真实执行
 * P-05 HITL 挂起中停止：不锁死，随后可发新消息
 */
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import {
  api,
  createPublishableImportTask,
  openPath,
  sendMessage,
  until,
  waitRoundSettled,
  type JobView
} from './support/helpers'

async function openPublishStep(page: Page, taskId: number): Promise<void> {
  await openPath(page, `/import?taskId=${taskId}`)
  // 就绪信号：工作区上下文切到发布步（披露面依赖它）
  await expect(page.locator('footer')).toContainText('task:IMPORT/PUBLISH', { timeout: 30_000 })
}

function gate(page: Page) {
  return page.locator('[data-testid="hitl-gate"]')
}

function gateButton(page: Page, name: string) {
  return gate(page).getByRole('button', { name, exact: true })
}

async function publishJobs(request: APIRequestContext, taskId: number): Promise<JobView[]> {
  const jobs = await api.get<JobView[]>(request, `/api/tasks/${taskId}/jobs`)
  return jobs.filter((job) => job.jobType === 'PUBLISH')
}

test.describe('@stub H HITL 确认门', () => {
  test('E2E-H02 单卡拒绝：卡转已拒绝、零发布作业、本轮可继续', async ({ page, request }) => {
    const taskId = await createPublishableImportTask(request)
    await openPublishStep(page, taskId)
    await sendMessage(page, `请立即调用 start_publish 发布任务 ${taskId}`)

    await expect(gate(page)).toBeVisible({ timeout: 30_000 })
    await expect(gate(page)).toContainText('确认启动发布作业')
    await expect(gate(page)).toContainText('执行后无法恢复')
    // 拒绝永远可用（即使不可撤销位未勾选）
    await expect(gateButton(page, '拒绝')).toBeEnabled()
    await gateButton(page, '拒绝').click()

    await expect(gate(page)).toHaveCount(0, { timeout: 20_000 })
    await waitRoundSettled(page, 30_000)
    expect(await publishJobs(request, taskId)).toHaveLength(0)
    await expect(page.locator('[data-testid="send-btn"]')).toBeVisible({ timeout: 10_000 })
  })

  test('E2E-H09 不可撤销卡复选框：未勾禁用 → 勾选 → 批准 → 真实执行', async ({ page, request }) => {
    const taskId = await createPublishableImportTask(request)
    await openPublishStep(page, taskId)
    await sendMessage(page, `scenario=IRREV-PUBLISH 任务 ${taskId} 发布`)

    await expect(gate(page)).toBeVisible({ timeout: 30_000 })
    const ack = page.locator('[data-testid="gate-ack-box"]')
    const approve = gateButton(page, '批准')

    // ① 未勾选：批准禁用 + 提示行可见
    await expect(ack).not.toBeChecked()
    await expect(page.locator('[data-testid="gate-ack-hint"]')).toBeVisible()
    await expect(approve).toBeDisabled()

    // 三通道同源之一：后端快照里的 irreversible 必须为 true
    const sid = await page.evaluate(() => sessionStorage.getItem('ai-session-id') ?? '')
    const current = await api.get<{ found: boolean; pendingEntries?: Array<{ irreversible?: boolean }> }>(
      request,
      `/api/ai/runs/current?sessionId=${encodeURIComponent(sid)}`
    )
    expect(current.found).toBe(true)
    expect(current.pendingEntries?.some((entry) => entry.irreversible === true)).toBe(true)

    // ② 真鼠标勾选：批准可用
    await ack.click()
    await expect(ack).toBeChecked()
    await expect(approve).toBeEnabled()

    // ③ 取消勾选回写 false：批准再次禁用
    await ack.click()
    await expect(ack).not.toBeChecked()
    await expect(approve).toBeDisabled()

    // ④ 重新勾选并批准：发布作业真实产生并完成
    await ack.click()
    await approve.click()
    await expect(gate(page)).toHaveCount(0, { timeout: 20_000 })
    await waitRoundSettled(page, 90_000)

    await until(async () => (await publishJobs(request, taskId)).length > 0, 20_000, '发布作业创建')
    const jobs = await publishJobs(request, taskId)
    await until(async () => {
      const list = await publishJobs(request, taskId)
      return list.every((job) => ['COMPLETED', 'FAILED', 'CANCELLED'].includes(job.status))
    }, 60_000, '发布作业终态')
    expect(jobs[0]?.status).toBe('COMPLETED')
  })

  test('E2E-P05 HITL 挂起中点停止：输入解禁、卡收敛，随后可发新消息', async ({ page, request }) => {
    const taskId = await createPublishableImportTask(request)
    await openPublishStep(page, taskId)
    await sendMessage(page, `scenario=IRREV-PUBLISH 任务 ${taskId} 发布`)

    await expect(gate(page)).toBeVisible({ timeout: 30_000 })
    await page.locator('[data-testid="stop-btn"]').click()

    await expect(gate(page)).toHaveCount(0, { timeout: 20_000 })
    await expect(page.locator('[data-testid="send-btn"]')).toBeVisible({ timeout: 15_000 })
    await waitRoundSettled(page, 20_000)

    // 停止后新消息不被 409 拦：真发送 + 真回复
    await sendMessage(page, 'turn-text')
    await expect(page.locator('[data-testid^="msg-bubble-"].w-full')).toHaveCount(2, { timeout: 30_000 })
    await waitRoundSettled(page, 30_000)
    expect(await publishJobs(request, taskId)).toHaveLength(0)
  })
})
