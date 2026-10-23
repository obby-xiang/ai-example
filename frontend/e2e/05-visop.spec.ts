/**
 * 批① P0 · V 域（可视可操作）
 * V-01 ui_snapshot：真页面已注册区域出元素（快照行带 data-ai-ref）
 * V-08 ui_click 未看先动：拒执 UI_REF_NOT_SNAPSHOTTED、零副作用
 * V-02/V-11 ui_click 后果性（consequential）：一次不点 + 指路注册动作（真模块真 DOM）
 */
import { expect, test } from '@playwright/test'
import { createImportTaskAtUpload, openPath, sendMessage, waitRoundSettled } from './support/helpers'

const IMPORT_PAGE = '/import'

test.describe('@stub V 可视可操作', () => {
  test('E2E-V01 ui_snapshot：抓到的元素来自真实页面区域（含 data-ai-ref 引用）', async ({ page }) => {
    await openPath(page, IMPORT_PAGE)
    await expect(page.locator('footer')).toContainText('task:IMPORT/UPLOAD', { timeout: 30_000 })
    await sendMessage(page, 'scenario=UI-SNAPSHOT 用例-V01')

    // 桩只下发调用形状；真正的元素行由浏览器端 ui_snapshot 现场遍历产出
    await expect(page.locator('[data-testid^="tool-card-"]').last()).toBeVisible({ timeout: 30_000 })
    await expect(page.locator('[data-testid="tool-output-text"]').last()).toContainText('import.upload.choose', {
      timeout: 20_000
    })
    await waitRoundSettled(page, 40_000)
    await expect(page.locator('[data-testid="alert-interrupted"]')).toHaveCount(0)
  })

  test('E2E-V08 未看先动：ui_click 被快照闸拒执，且零副作用', async ({ page }) => {
    let chooserFired = false
    page.on('filechooser', () => {
      chooserFired = true
    })
    await openPath(page, IMPORT_PAGE)
    await expect(page.locator('footer')).toContainText('task:IMPORT/UPLOAD', { timeout: 30_000 })
    await sendMessage(page, 'scenario=UI-CLICK 用例-V08')

    await expect(page.locator('[data-testid="tool-output-text"]').last()).toContainText('未在本轮界面快照中出现', {
      timeout: 30_000
    })
    await waitRoundSettled(page, 40_000)
    // 零副作用：上传文件选择器一次都没被打开
    expect(chooserFired).toBe(false)
  })

  test('E2E-V11 ui_click 目标禁用：拒执 UI_TARGET_DISABLED 式结论，不谎报「已点击」', async ({ page }) => {
    let chooserFired = false
    page.on('filechooser', () => {
      chooserFired = true
    })
    await openPath(page, IMPORT_PAGE)
    await expect(page.locator('footer')).toContainText('task:IMPORT/UPLOAD', { timeout: 30_000 })
    const outcome = await page.evaluate(async () => {
      const dom = await import('/src/utils/ai-dom-tools.ts') as unknown as {
        aiSnapshot: (args: Record<string, unknown>) => { result?: string }
        aiClick: (args: Record<string, unknown>) => Promise<{ outcome?: string; result?: string }>
      }
      const reg = await import('/src/utils/ai-regions.ts') as unknown as { currentAiEpoch: () => string }
      dom.aiSnapshot({ limit: 20, kinds: ['button', 'input'] })
      return dom.aiClick({ ref: 'import.upload.choose', epoch: reg.currentAiEpoch() })
    })
    expect(outcome.outcome).toBe('blocked')
    expect(outcome.result).toContain('未执行')
    expect(outcome.result).toContain('禁用')
    expect(outcome.result).not.toContain('已点击')
    expect(chooserFired).toBe(false)
  })

  test('E2E-V02 ui_click 后果性：由真模块真 DOM 判定，一次不点并指路注册动作', async ({ page, request }) => {
    let chooserFired = false
    page.on('filechooser', () => {
      chooserFired = true
    })
    const taskId = await createImportTaskAtUpload(request)
    await openPath(page, `${IMPORT_PAGE}?taskId=${taskId}`)
    await expect(page.locator('footer')).toContainText('task:IMPORT/UPLOAD', { timeout: 30_000 })
    await expect(page.locator('[data-ai-ref="import.upload.choose"]')).toBeEnabled()

    // 桩的 ui_click 参数带固定 epoch（a1b2c3-1），而 epoch 是每个页面随机的 —— 模型路径
    // 必然停在"快照闸/epoch"层，到不了风险分流。这里在真页面里直接调真模块（同一 Vite 模块实例），
    // 用当前 epoch 走完四段链，验证"后果性 = 零点击 + 指路"这条安全性质。
    const outcome = await page.evaluate(async () => {
      const dom = await import('/src/utils/ai-dom-tools.ts') as unknown as {
        aiSnapshot: (args: Record<string, unknown>) => { result?: string }
        aiClick: (args: Record<string, unknown>) => Promise<{ outcome?: string; result?: string; code?: string }>
      }
      const reg = await import('/src/utils/ai-regions.ts') as unknown as { currentAiEpoch: () => string }
      dom.aiSnapshot({ limit: 20, kinds: ['button', 'input'] })
      return dom.aiClick({ ref: 'import.upload.choose', epoch: reg.currentAiEpoch() })
    })

    expect(['failed', 'blocked']).toContain(outcome.outcome ?? '')
    expect(outcome.result).toContain('未执行')
    expect(outcome.result).toContain('后果性动作')
    expect(outcome.result).toContain('请改用')
    expect(outcome.result).toContain('ui_request_upload')
    expect(chooserFired).toBe(false)
  })
})
