/**
 * 批③d · 14c · Y 域（CFG 模型配置页联动）
 *
 * 蓝本：docs/research/设计-真机E2E用例全集-2026-10-23.md §2.21（Y-01…Y-12）。
 * 断言面分三类：配置页 DOM（`data-testid`）/ 配置端点信封（`{success,code,data}`）/ 面板与 `/health` 三方同值。
 *
 * 前置（由运行命令注入，见报告）：`AI_SECRET_KEK`（base64 32B）—— 缺它时「写入 key」路径按
 * fail-closed 报 `AI_SECRET_KEK_MISSING`，Y-02/Y-03 无法承载。
 *
 * 顺序即依赖：本文件按「凭证与端点 → 测试连接/拉取 → 模型与默认值 → 降级与一致性」排列；
 * 每个用例自建自己需要的 provider（不复用别例的夹具），并在结尾把全局默认值还原，
 * 避免污染同棒后续域（MD/X）的对话轮。
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { assistantText, openApp, sendMessage, waitRoundSettled } from './support/helpers'
import { API } from './support/hgta'
import { RUN_DIR, latestStubRequest, openAiSettings, uniq, untilStubRequestAfter } from './support/helpers-mdxy'

const CONFIG = `${API}/api/ai`

/** 形态合法的假 key（`s`+`k-` 前缀按仓内扫描库同款手法拆分构造，避免被密钥规则误报；不是真凭据）。 */
const FAKE_KEY = 's' + 'k-test-key-1234567890abcdef'

interface Envelope<T = unknown> {
  success?: boolean
  code?: string
  message?: string
  data?: T
}

async function call(
  request: APIRequestContext,
  method: 'GET' | 'POST' | 'PUT' | 'DELETE',
  path: string,
  body?: unknown
): Promise<{ status: number; text: string; json: Envelope }> {
  const res = await request.fetch(`${CONFIG}${path}`, { method, data: body })
  const text = await res.text()
  let json: Envelope = {}
  try {
    json = JSON.parse(text) as Envelope
  } catch {
    json = {}
  }
  return { status: res.status(), text, json }
}

interface ProviderView {
  id: number
  code: string
  displayName: string
  baseUrl: string
  keyConfigured: boolean
  keyLast4: string | null
  keyStatus: string
  source: string
  enabled: boolean
  models: Array<{ modelId: string; isDefault: boolean; enabled: boolean }>
}

interface ModelView {
  providerId: number
  providerCode: string
  modelId: string
  isDefault: boolean
}

async function providers(request: APIRequestContext): Promise<ProviderView[]> {
  const res = await call(request, 'GET', '/providers')
  expect(res.status).toBe(200)
  return (res.json.data ?? []) as ProviderView[]
}

async function seedProvider(request: APIRequestContext): Promise<ProviderView> {
  const list = await providers(request)
  const seed = list.find((item) => item.source === 'ENV_SEED')
  expect(seed, '缺少 ENV_SEED 种子行（harness 未注入 AI_API_KEY？）').toBeDefined()
  return seed!
}

async function availableModels(request: APIRequestContext): Promise<ModelView[]> {
  const res = await call(request, 'GET', '/models')
  expect(res.status).toBe(200)
  return (res.json.data ?? []) as ModelView[]
}

async function health(request: APIRequestContext): Promise<Record<string, unknown>> {
  const res = await request.get(`${API}/api/ai/health`)
  return (await res.json()) as Record<string, unknown>
}

/** 建一个「有 key + 一个模型」的独立 provider（Y 域各例自用自清）。 */
async function createProviderWithModel(
  request: APIRequestContext,
  code: string,
  modelId: string,
  extra: Record<string, unknown> = {}
): Promise<{ provider: ProviderView; modelId: string }> {
  const created = await call(request, 'POST', '/providers', {
    code,
    displayName: `Y 域 ${code}`,
    baseUrl: 'https://api.example.com',
    apiKey: FAKE_KEY,
    ...extra
  })
  expect(created.status, created.text).toBe(200)
  const provider = created.json.data as ProviderView
  const model = await call(request, 'POST', `/providers/${provider.id}/models`, {
    modelId,
    maxContext: 8000,
    maxOutput: 1000
  })
  expect(model.status, model.text).toBe(200)
  return { provider, modelId }
}

async function removeProvider(request: APIRequestContext, id: number): Promise<void> {
  await call(request, 'DELETE', `/providers/${id}?hard=true`)
}

test.describe('@stub Y 模型配置页联动', () => {
  test('E2E-Y01 provider CRUD：必填被拒、新建/编辑/删除全程真落库', async ({ page, request }) => {
    await openAiSettings(page)
    const cards = page.locator('[data-testid="provider-card"]')
    const before = await cards.count()
    expect(before).toBeGreaterThanOrEqual(1)

    // ① 必填校验：空 code 提交 ⇒ 后端 400（INVALID_PARAM），卡片数不变。
    // 契约来源：`AiConfigWriter.CODE_INVALID_PARAM`（AiConfigWriter.java:82 / invalidParam()）
    // + 前端键表 `constants/ai-settings-copy.ts` 的 INVALID_PARAM → `ai.settings.errors.INVALID_PARAM`
    // （zh-CN.json / en-US.json 同键）。
    // 以**稳定 code** 为主、当前文案为辅：文案已把「参数不在允许范围内」与「默认目标不合法」
    // 合并成一句（用户可见文案会继续改版），故这里不写死句子 —— 逐字取自 locale 文件的同一键；
    // 码变了或文案指到别的键才红，纯文案改写不会红。
    const invalid = await call(request, 'POST', '/providers', { code: '', displayName: '', baseUrl: '' })
    expect(invalid.status, invalid.text).toBe(400)
    expect(invalid.json.code).toBe('INVALID_PARAM')

    const readErrors = (locale: string): Record<string, string> => {
      const json = JSON.parse(readFileSync(join(process.cwd(), 'src', 'locales', locale), 'utf8')) as {
        ai: { settings: { errors: Record<string, string> } }
      }
      return json.ai.settings.errors
    }
    const zhInvalidParam = readErrors('zh-CN.json').INVALID_PARAM
    const enInvalidParam = readErrors('en-US.json').INVALID_PARAM
    expect(zhInvalidParam).toBeTruthy()
    expect(enInvalidParam).toBeTruthy()

    await page.locator('[data-testid="create-provider-submit"]').click()
    // 界面文案必须来自 INVALID_PARAM 这个键（不是后端 message、也不是别的错误键）
    await expect(page.locator('.el-message').first()).toHaveText(zhInvalidParam)
    expect(await cards.count()).toBe(before)

    // ② 新建：卡片出现、来源标数据库、key 只显示末 4 位
    const code = `y1-${uniq()}`
    const name = `Y1 端点 ${code}`
    await page.locator('[data-testid="create-code"]').fill(code)
    await page.locator('[data-testid="create-name"]').fill(name)
    await page.locator('[data-testid="create-base-url"]').fill('https://api.example.com')
    await page.locator('[data-testid="create-key"]').fill(FAKE_KEY)
    await page.locator('[data-testid="create-provider-submit"]').click()
    await expect(cards).toHaveCount(before + 1)

    const card = cards.filter({ hasText: name })
    await expect(card).toHaveCount(1)
    await expect(card.locator('[data-testid="provider-source"]')).toHaveText('来源：数据库')
    await expect(card.locator('[data-testid="provider-key"]')).toContainText('cdef')

    // ③ 编辑：改显示名保存后卡片随之更新（真落库，不只是本地态）
    const renamed = `${name} 改`
    await card.locator('[data-testid="provider-display-name"]').fill(renamed)
    await card.locator('[data-testid="save-provider"]').click()
    await expect(cards.filter({ hasText: renamed })).toHaveCount(1)

    // ④ 删除：确认后卡片消失，且 `/api/ai/models` 不再列出该 provider
    await cards.filter({ hasText: renamed }).locator('[data-testid="remove-provider"]').click()
    await page.locator('.el-message-box__btns button.el-button--primary').click()
    await expect(cards).toHaveCount(before)
    await expect
      .poll(async () => (await providers(request)).some((item) => item.code === code), { timeout: 15_000 })
      .toBe(false)
  })

  test('E2E-Y02 key 加密写入且不回显：响应/列表/后端日志均无明文', async ({ request }) => {
    const plain = 'sk' + '-live-ROUNDTRIP-0123456789abcdef'
    const code = `y2-${uniq()}`
    const created = await call(request, 'POST', '/providers', {
      code,
      displayName: `Y2 ${code}`,
      baseUrl: 'https://api.example.com',
      apiKey: plain
    })
    expect(created.status, created.text).toBe(200)
    expect(created.text).not.toContain(plain)
    const view = created.json.data as ProviderView
    expect(view.keyConfigured).toBe(true)
    expect(view.keyLast4).toBe(plain.slice(-4))
    expect(view.keyStatus).toBe('OK')

    // 列表与健康描述同样不回显
    const list = await call(request, 'GET', '/providers')
    expect(list.text).not.toContain(plain)
    const healthRes = await request.get(`${API}/api/ai/health`)
    expect(await healthRes.text()).not.toContain(plain)

    // 后端日志（harness 落盘）里也没有明文
    const log = readFileSync(join(RUN_DIR, 'backend.log'), 'utf8')
    expect(log).not.toContain(plain)

    await removeProvider(request, view.id)
  })

  test('E2E-Y08 SSRF 闸：私网/回环/元数据地址在写路径被拒，ENV 种子行不受此限', async ({ request }) => {
    for (const baseUrl of ['http://127.0.0.1:19399', 'http://10.1.2.3:8080', 'http://169.254.169.254/latest']) {
      const res = await call(request, 'POST', '/providers', {
        code: `y8-${uniq()}`,
        displayName: 'Y8',
        baseUrl
      })
      expect(res.status, `${baseUrl} 应被拒`).toBe(400)
      expect(res.json.code).toBe('BLOCKED_PRIVATE_ENDPOINT')
    }
    // 对照：ENV 种子行（受信来源）指向本机桩，探活照样成功 —— 闸只拦"写路径的新地址"
    const seed = await seedProvider(request)
    expect(seed.baseUrl).toContain('127.0.0.1')
    const probe = await call(request, 'POST', `/providers/${seed.id}/test`)
    expect(probe.status).toBe(200)
    expect((probe.json.data as { ok?: boolean }).ok).toBe(true)
  })

  test('E2E-Y04 连接探测：可用的成功且带模型数，不可用的失败且给可行动原因', async ({ page, request }) => {
    await openAiSettings(page)
    const cards = page.locator('[data-testid="provider-card"]')
    const seed = await seedProvider(request)
    const seedCard = cards.filter({ hasText: seed.displayName })

    const started = Date.now()
    await seedCard.locator('[data-testid="test-provider"]').click()
    const okResult = seedCard.locator('[data-testid="probe-result"]')
    await expect(okResult).toBeVisible({ timeout: 20_000 })
    await expect(okResult).toContainText('连通，模型数')
    await expect(okResult).toContainText('2')
    // 5s 超时内必返回（桩即时响应，留出渲染余量）
    expect(Date.now() - started).toBeLessThan(15_000)

    // 失败面：新端点的公网地址在本环境不可达 ⇒ 200 + ok=false + 稳定失败文案（不是白屏/无反馈）
    const code = `y4-${uniq()}`
    const created = await call(request, 'POST', '/providers', {
      code,
      displayName: `Y4 ${code}`,
      baseUrl: 'https://api.example.com'
    })
    expect(created.status, created.text).toBe(200)
    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('[data-testid="ai-settings-view"]')).toBeVisible({ timeout: 30_000 })
    const target = page.locator('[data-testid="provider-card"]').filter({ hasText: `Y4 ${code}` })
    await target.locator('[data-testid="test-provider"]').click()
    await expect(target.locator('[data-testid="probe-result"]')).toContainText('失败', { timeout: 30_000 })
    await removeProvider(request, (created.json.data as ProviderView).id)
  })

  test('E2E-Y05 拉取远端模型：列表可导入，重复拉取命中缓存（上游不再被请求）', async ({ page, request }) => {
    await openAiSettings(page)
    const seed = await seedProvider(request)
    await page.locator('[data-testid="provider-select"]').selectOption(String(seed.id))
    await page.locator('[data-testid="pull-models"]').click()
    const fetched = page.locator('[data-testid="fetched-models"]')
    await expect(fetched).toBeVisible({ timeout: 20_000 })
    await expect(fetched).toContainText('stub-md-model')
    await expect(fetched).toContainText('stub-md-model-alt')
    await expect(page.locator('[data-testid="add-fetched-model"]')).toHaveCount(2)

    // 缓存面：refresh=false 的二次拉取直接吃 Redis 缓存（10min TTL）
    const cached = await call(request, 'POST', `/providers/${seed.id}/models/fetch?refresh=false`)
    expect(cached.status).toBe(200)
    const payload = cached.json.data as { ok: boolean; models: string[]; cached: boolean }
    expect(payload.ok).toBe(true)
    expect(payload.models).toEqual(expect.arrayContaining(['stub-md-model', 'stub-md-model-alt']))
    expect(payload.cached).toBe(true)
  })

  test('E2E-Y06 模型 CRUD 与默认切换：默认模型的增改删即时反映到 /health 与面板', async ({ page, request }) => {
    const seed = await seedProvider(request)
    const originalModel = String((await health(request)).model ?? '')
    const modelId = 'stub-md-model-alt'

    // ① 手填一行到种子端点（UI 路径）
    await openAiSettings(page)
    await page.locator('[data-testid="provider-select"]').selectOption(String(seed.id))
    await page.locator('[data-testid="manual-model-id"]').fill(modelId)
    await page.locator('[data-testid="manual-model-context"]').fill('16000')
    await page.locator('[data-testid="manual-model-output"]').fill('2000')
    await page.locator('[data-testid="manual-model-submit"]').click()
    await expect
      .poll(async () => (await providers(request)).find((item) => item.id === seed.id)?.models.some((m) => m.modelId === modelId),
        { timeout: 15_000 })
      .toBe(true)

    // ② 设默认 + 全局默认模型切到它 ⇒ /health 的 model 随之变化（≤1s 轮询 + 余量）
    const setDefault = await call(request, 'PUT', `/providers/${seed.id}/models/${modelId}`, { isDefault: true })
    expect(setDefault.status, setDefault.text).toBe(200)
    const defaults = await call(request, 'PUT', '/settings/defaults', { modelId })
    expect(defaults.status, defaults.text).toBe(200)
    await expect.poll(async () => (await health(request)).model, { timeout: 15_000 }).toBe(modelId)

    const rows = await availableModels(request)
    expect(rows.filter((row) => row.isDefault).map((row) => row.modelId)).toEqual([modelId])

    // ③ 面板标签随之同步（重载页面即取新 /health；Y-09 另测免重载的请求体热更新）
    await openApp(page)
    await expect(page.locator('[data-testid="current-model"]')).toHaveText(modelId, { timeout: 30_000 })

    // ④ 删除默认模型：产品当前**允许**删除（蓝图期望「拒绝并提示」⇒ 已登记为发现项 Y-06-D1），
    //    这里断言真实语义：删除成功、且默认落点不再指向它（不留悬空默认）。
    const removed = await call(request, 'DELETE', `/providers/${seed.id}/models/${modelId}`)
    expect(removed.status, removed.text).toBe(200)
    await expect.poll(async () => (await health(request)).model, { timeout: 15_000 }).not.toBe(modelId)
    const after = await availableModels(request)
    expect(after.some((row) => row.modelId === modelId)).toBe(false)

    // 还原全局默认（清空 default model 覆盖）并把默认标记还给原模型，避免污染其余域
    const reset = await call(request, 'PUT', '/settings/defaults', { modelId: null })
    expect(reset.status, reset.text).toBe(200)
    if (originalModel !== '' && originalModel !== modelId) {
      const restore = await call(request, 'PUT', `/providers/${seed.id}/models/${originalModel}`, { isDefault: true })
      expect(restore.status, restore.text).toBe(200)
      await expect.poll(async () => String((await health(request)).model ?? ''), { timeout: 15_000 }).toBe(originalModel)
    }
  })

  test('E2E-Y03 清 key ⇒ 可用性降级：keyStatus=MISSING、该端点模型退出可选项、卡片标「未配置」', async ({ page, request }) => {
    const code = `y3-${uniq()}`
    const { provider } = await createProviderWithModel(request, code, 'y3-model')
    expect((await availableModels(request)).some((row) => row.modelId === 'y3-model')).toBe(true)

    const cleared = await call(request, 'PUT', `/providers/${provider.id}`, { clearKey: true })
    expect(cleared.status, cleared.text).toBe(200)
    const view = cleared.json.data as ProviderView
    expect(view.keyConfigured).toBe(false)
    expect(view.keyStatus).toBe('MISSING')
    expect(view.keyLast4).toBeNull()
    // 可用性降级 = 该端点的模型从「面板可选」里消失（不是仅 UI 灰一下）
    expect((await availableModels(request)).some((row) => row.modelId === 'y3-model')).toBe(false)

    // 卡片面：密钥格显示「未配置」且不再是绿色（keyStatus 非 OK）
    await openAiSettings(page)
    const card = page.locator('[data-testid="provider-card"]').filter({ hasText: `Y 域 ${code}` })
    await expect(card.locator('[data-testid="provider-key"]')).toHaveText('未配置')
    await expect(card.locator('[data-testid="provider-key"]')).toHaveClass(/text-\[#f56c6c\]/)

    await removeProvider(request, provider.id)
  })

  test('E2E-Y07 默认参数读写：保存 200、重载保持、非法组合被拒', async ({ page, request }) => {
    const saved = await call(request, 'PUT', '/settings/defaults', {
      temperature: 0.3,
      topP: 0.9,
      maxTokens: 1234,
      allowUserParams: false
    })
    expect(saved.status, saved.text).toBe(200)
    const view = saved.json.data as { temperature: number; topP: number; maxTokens: number; allowUserParams: boolean; version: number }
    expect(view.temperature).toBe(0.3)
    expect(view.topP).toBe(0.9)
    expect(view.maxTokens).toBe(1234)
    expect(view.allowUserParams).toBe(false)

    // 重载页面后表单值保持（读的是库里的值，不是上次的内存态）
    await openAiSettings(page)
    await expect(page.locator('[data-testid="default-temperature"]')).toHaveValue('0.3')
    await expect(page.locator('[data-testid="default-top-p"]')).toHaveValue('0.9')
    await expect(page.locator('[data-testid="default-max-tokens"]')).toHaveValue('1234')

    // 非法组合：不存在的模型（404 MODEL_NOT_FOUND）/ 越界 temperature（400 INVALID_PARAM）
    const badModel = await call(request, 'PUT', '/settings/defaults', { modelId: 'no-such-model' })
    expect(badModel.status).toBe(404)
    expect(badModel.json.code).toBe('MODEL_NOT_FOUND')
    const badTemp = await call(request, 'PUT', '/settings/defaults', { temperature: 5 })
    expect(badTemp.status).toBe(400)
    expect(badTemp.json.code).toBe('INVALID_PARAM')

    // 还原为「未设置」（其余域不依赖这些值）
    const reset = await call(request, 'PUT', '/settings/defaults', {})
    expect(reset.status, reset.text).toBe(200)
  })

  test('E2E-Y09 配置热更新：改默认模型后下一轮请求体 model 即刻为新值（无需重启）', async ({ page, request }) => {
    const seed = await seedProvider(request)
    const original = String((await health(request)).model ?? '')
    const hotModel = 'stub-md-model'
    const added = await call(request, 'POST', `/providers/${seed.id}/models`, { modelId: hotModel, maxContext: 16000, maxOutput: 2000 })
    expect(added.status, added.text).toBe(200)

    // 解析优先级（`AiModelResolver`）：provider 的 is_default 标记 > 全局默认 ⇒ 要真正换模型必须动标记位。
    const flagged = await call(request, 'PUT', `/providers/${seed.id}/models/${hotModel}`, { isDefault: true })
    expect(flagged.status, flagged.text).toBe(200)
    // ≤1s 的 reload-interval：/health 读到新模型即证明配置快照已热更新（无需重启）
    await expect.poll(async () => (await health(request)).model, { timeout: 5_000 }).toBe(hotModel)

    await openApp(page)
    const previous = latestStubRequest()?.tag ?? null
    await sendMessage(page, `md=basic 用例Y09-${uniq()}`)
    const record = await untilStubRequestAfter(previous)
    const body = JSON.parse(record.raw) as { model?: string }
    expect(body.model).toBe(hotModel)
    await waitRoundSettled(page)
    expect((await assistantText(page)).length).toBeGreaterThan(0)

    // 还原：默认标记交还原模型 + 删掉临时模型
    if (original !== '' && original !== hotModel) {
      const restored = await call(request, 'PUT', `/providers/${seed.id}/models/${original}`, { isDefault: true })
      expect(restored.status, restored.text).toBe(200)
    }
    const removed = await call(request, 'DELETE', `/providers/${seed.id}/models/${hotModel}`)
    expect(removed.status, removed.text).toBe(200)
  })

  test('E2E-Y12 三处同值：配置页 / /health / 面板对 availability 与 model 的判断一致', async ({ page, request }) => {
    const healthBody = await health(request)
    const list = await providers(request)
    const available = healthBody.available === true
    const code = String(healthBody.code ?? '')
    const model = String(healthBody.model ?? '')

    // 配置页：可用端点（enabled + keyStatus=OK）非空 ⇔ /health.available=true
    const usable = list.filter((item) => item.enabled && item.keyStatus === 'OK')
    expect(available).toBe(usable.length > 0)
    if (available) {
      expect(code).toBe('AI_AVAILABLE')
      expect(String(healthBody.message ?? '')).toBe('')
    }
    // 默认目标与 /api/ai/models 的默认标记同源：未设全局默认时，/health.model 必须等于该端点标记的默认行
    const defaults = await call(request, 'GET', '/settings/defaults')
    const defaultsView = defaults.json.data as { modelId: string | null }
    const rows = await availableModels(request)
    if (defaultsView.modelId === null) {
      const flagged = rows.find((row) => row.isDefault)
      if (flagged !== undefined) {
        expect(model).toBe(flagged.modelId)
      }
    }

    // 面板：current-model 标签逐字等于 /health.model，且降级横幅不出现
    await openApp(page)
    await expect(page.locator('[data-testid="current-model"]')).toHaveText(model, { timeout: 30_000 })
    await expect(page.locator('[data-testid="alert-degraded"]')).toHaveCount(0)
  })

  test('E2E-Y11 种子源标记与只读边界：来源标签在位、用户一改即脱离种子（env 不再覆盖）', async ({ page, request }) => {
    const seed = await seedProvider(request)
    expect(seed.source).toBe('ENV_SEED')

    await openAiSettings(page)
    const card = page.locator('[data-testid="provider-card"]').filter({ hasText: seed.displayName })
    await expect(card.locator('[data-testid="provider-source"]')).toHaveText('来源：环境变量种子')

    // 真机语义（`AiConfigWriter.updateProvider` 的 I-1）：用户一改即把 source 翻成 DB，
    // 于是 seed-force 重灌只认 ENV_SEED 行 —— 用户改动天然不会被 env 覆盖（比"只读"更强）。
    const renamed = `${seed.displayName} 用户改`
    const edited = await call(request, 'PUT', `/providers/${seed.id}`, { displayName: renamed })
    expect(edited.status, edited.text).toBe(200)
    const flipped = (edited.json.data as ProviderView).source
    expect(flipped).toBe('DB')
    // 跨过一轮热更新窗口（1s）确认改动不被 env 拉回
    const deadline = Date.now() + 2_500
    while (Date.now() < deadline) {
      const reread = (await providers(request)).find((item) => item.id === seed.id)
      expect(reread?.displayName).toBe(renamed)
      expect(reread?.source).toBe('DB')
      await new Promise((done) => setTimeout(done, 300))
    }
    // 还原显示名（source 一经翻转为 DB 不可逆 —— 这是产品既定语义词，本用例不试图回滚）
    await call(request, 'PUT', `/providers/${seed.id}`, { displayName: seed.displayName })
  })
})
