/**
 * V（可视可操作）+ L（工具全量）真机用例专用助手。
 *
 * 为什么要自带一套起停：
 *   `support/harness.ts` 的端口与 Redis DB 都可由环境变量参数化，但**运行目录写死**
 *   （`test-results/.e2e-run`，状态文件 `state.json`、H2 数据目录 `data/` 同名）。
 *   本批有多个同族子智能体并行跑各自的 spec，共用一个状态文件会互相 `taskkill`、
 *   共用 H2 目录会在对方在跑时 `rmSync` 失败或删掉对方数据。为守住「不互相踩」这条线，
 *   本文件只在自己的运行目录（`test-results/.e2e-run-vl`）里起自己的三件套，
 *   端口基 18930 / 18999 / 19001、Redis DB 12（全部可用 `VL_*` 环境变量覆盖）。
 *   `harness.ts` 里与目录无关的两个纯工具（`isPortFree` / `flushRedisDb`）直接复用。
 *
 * 与常开服务的边界：绝不碰 8081 / 5202；端口被占即失败并提示换端口（不杀别人的进程）。
 */
import { spawn, spawnSync, type ChildProcess } from 'node:child_process'
import { closeSync, existsSync, mkdirSync, openSync, readdirSync, readFileSync, rmSync, statSync } from 'node:fs'
import { delimiter, join, resolve } from 'node:path'
import { setTimeout as sleep } from 'node:timers/promises'
import { flushRedisDb, FRONTEND_DIR, isPortFree, REPO_ROOT } from './harness'

export const VL_RUN_DIR = process.env.VL_RUN_DIR ?? join(FRONTEND_DIR, 'test-results', '.e2e-run-vl')
export const VL_PORTS = {
  backend: Number(process.env.VL_BACKEND_PORT ?? '18930'),
  stub: Number(process.env.VL_STUB_PORT ?? '18999'),
  app: Number(process.env.VL_APP_PORT ?? '19001')
}
export const VL_REDIS_DB = Number(process.env.VL_REDIS_DB ?? '12')
export const VL_API = process.env.VL_API_BASE ?? `http://127.0.0.1:${VL_PORTS.backend}`
export const VL_APP = process.env.VL_APP_BASE ?? `http://127.0.0.1:${VL_PORTS.app}`
export const VL_STUB_DIR = join(VL_RUN_DIR, 'stub')

const MAIN_CLASS = 'com.example.configmgr.ConfigMgrApplication'
const children: Record<string, ChildProcess> = {}
let ownedByThisProcess = false

function log(line: string): void {
  // eslint-disable-next-line no-console
  console.log(`[vl-harness] ${line}`)
}

function javaBin(): string {
  const home = process.env.JAVA_HOME
  if (home) {
    return join(home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  }
  const probe = spawnSync('java', ['-XshowSettings:properties', '-version'], { encoding: 'utf8' })
  const text = `${probe.stdout ?? ''}${probe.stderr ?? ''}`
  const match = /^\s*java\.home\s*=\s*(.+)$/m.exec(text)
  const base = match?.[1]?.trim()
  return base ? join(base, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java'
}

function jarListIn(dir: string): string[] {
  if (!existsSync(dir)) return []
  return readdirSync(dir)
    .filter((name) => name.endsWith('.jar'))
    .sort()
    .map((name) => join(dir, name))
}

/**
 * 依赖 classpath：显式环境变量 → `backend/target/e2e-classpath.txt` →
 * 已解好的共享 lib 缓存（只读复用）→ 从胖包现解到自己的运行目录。
 */
function dependencyClasspath(): string {
  const explicit = process.env.VL_BACKEND_CLASSPATH ?? process.env.E2E_BACKEND_CLASSPATH
  if (explicit) return explicit

  const cpFile = join(REPO_ROOT, 'backend', 'target', 'e2e-classpath.txt')
  if (existsSync(cpFile)) {
    const text = readFileSync(cpFile, 'utf8').trim()
    if (text) return text
  }

  const sharedLib = join(FRONTEND_DIR, 'test-results', '.e2e-run', 'jar', 'BOOT-INF', 'lib')
  const shared = jarListIn(sharedLib)
  if (shared.length > 0) return shared.join(delimiter)

  const jar = join(REPO_ROOT, 'backend', 'target', 'config-mgr.jar')
  if (!existsSync(jar)) {
    throw new Error('缺少依赖 classpath：backend/target/classes 与 config-mgr.jar 都不存在，请先在 backend 执行 mvn -q -DskipTests package')
  }
  const outDir = join(VL_RUN_DIR, 'jar')
  mkdirSync(outDir, { recursive: true })
  const home = process.env.JAVA_HOME
  const jarTool = home ? join(home, 'bin', process.platform === 'win32' ? 'jar.exe' : 'jar') : 'jar'
  const result = spawnSync(jarTool, ['--extract', `--file=${jar}`, 'BOOT-INF/lib'], { cwd: outDir, encoding: 'utf8' })
  if (result.status !== 0) {
    throw new Error(`从胖包解依赖失败：${result.stderr || result.stdout || '未知错误'}`)
  }
  return jarListIn(join(outDir, 'BOOT-INF', 'lib')).join(delimiter)
}

function spawnLogged(name: string, cmd: string, args: string[], cwd: string, env: NodeJS.ProcessEnv): ChildProcess {
  mkdirSync(VL_RUN_DIR, { recursive: true })
  const fd = openSync(join(VL_RUN_DIR, `${name}.log`), 'a')
  const child = spawn(cmd, args, {
    cwd,
    env,
    stdio: ['ignore', fd, fd],
    windowsHide: true,
    detached: process.platform !== 'win32'
  })
  child.once('exit', () => {
    try {
      closeSync(fd)
    } catch {
      /* 已关闭 */
    }
  })
  children[name] = child
  log(`${name} pid=${child.pid} → ${join(VL_RUN_DIR, `${name}.log`)}`)
  return child
}

async function waitForHttp(url: string, label: string, accept: (body: string, status: number) => boolean, timeoutMs = 150_000): Promise<void> {
  const deadline = Date.now() + timeoutMs
  let last = ''
  while (Date.now() < deadline) {
    try {
      const res = await fetch(url)
      const text = await res.text()
      if (accept(text, res.status)) return
      last = `HTTP ${res.status}`
    } catch (error) {
      last = error instanceof Error ? error.message : String(error)
    }
    await sleep(400)
  }
  throw new Error(`${label} 未在 ${Math.round(timeoutMs / 1000)}s 内就绪（${url}）：${last}`)
}

async function assertPortFree(port: number, label: string): Promise<void> {
  if (await isPortFree(port)) return
  throw new Error(`端口 ${port}（${label}）已被占用：VL 域用例独占 18930/18999/19001 段，请用 VL_*_PORT 换一段；本助手不杀别人的进程。`)
}

/**
 * 域内基址一致性（L-81 残余面）：本域页面固定走 `VL_APP`，而 `support/helpers` 的 `api` /
 * `request` 基址来自 `E2E_API_BASE ?? E2E_BACKEND_PORT`。两者一旦不一致（典型：忘了
 * `E2E_EXTERNAL=1`，`harness.ts` 又在 183xx 自起一套），夹具就在两套后端之间来回：
 * 任务建在 A、模板/上传打到 B ⇒ 模板 404 → 404 的错误体被当 `.xlsx` 上传 → PRECHECK 以
 * 「未找到上传文件」FAILED —— 看着像产品回归（V17 复扫即此形态）。这里一次性拦住并给出
 * 可直接抄的运行命令，别让装配错配伪装成红测。
 */
function assertSingleApiBase(): void {
  const strip = (value: string): string => value.replace(/\/+$/, '')
  const apiBase = strip(process.env.E2E_API_BASE ?? `http://127.0.0.1:${process.env.E2E_BACKEND_PORT ?? '18330'}`)
  const appBase = strip(process.env.E2E_APP ?? `http://127.0.0.1:${process.env.E2E_APP_PORT ?? '18401'}`)
  const hint = `VL 域跑法：E2E_EXTERNAL=1 E2E_API_BASE=${VL_API} E2E_APP=${VL_APP} npx playwright test e2e/12a-vl-vis.spec.ts e2e/12b-vl-tol.spec.ts`
  if (apiBase !== strip(VL_API)) {
    throw new Error(`本域后端基址不一致：api 助手指向 ${apiBase}，VL 域栈是 ${VL_API}。${hint}`)
  }
  if (appBase !== strip(VL_APP)) {
    throw new Error(`本域页面基址不一致：页面助手指向 ${appBase}，VL 域栈是 ${VL_APP}。${hint}`)
  }
}

/** 起三件套（桩上游 + 后端 + 前端 dev server）。已在跑则直接返回。 */
export async function ensureVlStack(): Promise<void> {
  assertSingleApiBase()
  const health = await fetch(`${VL_API}/api/ai/health`).then((r) => r.text()).catch(() => '')
  const appUp = await fetch(`${VL_APP}/`).then((r) => r.status === 200).catch(() => false)
  if (health.includes('"available":true') && appUp) {
    log('已就绪，复用现有实例')
    return
  }

  for (const [port, label] of [
    [VL_PORTS.backend, '后端'],
    [VL_PORTS.stub, '桩上游'],
    [VL_PORTS.app, '前端']
  ] as Array<[number, string]>) {
    await assertPortFree(port, label)
  }

  mkdirSync(VL_STUB_DIR, { recursive: true })
  rmSync(join(VL_RUN_DIR, 'data'), { recursive: true, force: true })
  await flushRedisDb(VL_REDIS_DB)

  spawnLogged('stub', process.execPath, [
    join(REPO_ROOT, 'scripts', 'ci', 'stub-upstream.mjs'),
    '--port', String(VL_PORTS.stub),
    '--dir', VL_STUB_DIR
  ], REPO_ROOT, process.env)
  await waitForHttp(`http://127.0.0.1:${VL_PORTS.stub}/probe`, '桩上游', () => true, 30_000)

  const classes = join(REPO_ROOT, 'backend', 'target', 'classes')
  if (!existsSync(join(classes, 'application.yml'))) {
    throw new Error('缺少 backend/target/classes（含 application.yml）：请先在 backend 执行 mvn -q -DskipTests package')
  }
  const classpath = [classes, dependencyClasspath()].join(delimiter)
  spawnLogged('backend', javaBin(), [
    '-cp', classpath, MAIN_CLASS,
    `--server.port=${VL_PORTS.backend}`,
    '--spring.datasource.url=jdbc:h2:file:./data/e2e;DB_CLOSE_DELAY=-1',
    `--spring.data.redis.database=${VL_REDIS_DB}`,
    '--app.job.batch-size=10',
    '--app.job.demo-batch-delay-ms=150',
    '--app.ai.limit.session-per-minute=200',
    '--app.ai.limit.ip-per-minute=2000',
    '--app.ai.sheet.write-enabled=true',
    '--app.ai.resume.on-startup=false'
  ], VL_RUN_DIR, {
    ...process.env,
    AI_API_KEY: 'e2e-stub-placeholder',
    AI_BASE_URL: `http://127.0.0.1:${VL_PORTS.stub}`,
    AI_MODEL: 'e2e-stub-model'
  })

  await waitForHttp(`${VL_API}/api/ai/health`, '后端（available + redis）', (body) => {
    try {
      const json = JSON.parse(body) as { available?: boolean; redis?: { available?: boolean } }
      return json.available === true && json.redis?.available === true
    } catch {
      return false
    }
  })
  await waitForHttp(`${VL_API}/api/definitions`, '后端种子', (body) => {
    try {
      const json = JSON.parse(body) as { data?: unknown[] }
      return Array.isArray(json.data) && json.data.length >= 15
    } catch {
      return false
    }
  }, 120_000)

  const viteBin = join(FRONTEND_DIR, 'node_modules', 'vite', 'bin', 'vite.js')
  if (!existsSync(viteBin)) throw new Error('缺少 frontend/node_modules/vite：请先 yarn install')
  spawnLogged('app', process.execPath, [viteBin, '--port', String(VL_PORTS.app), '--strictPort', '--host', '127.0.0.1'], FRONTEND_DIR, {
    ...process.env,
    VITE_API_BASE: VL_API,
    VITE_DEV_PORT: String(VL_PORTS.app)
  })
  await waitForHttp(`${VL_APP}/`, '前端', () => true, 120_000)

  ownedByThisProcess = true
  log(`就绪：api=${VL_API} app=${VL_APP} stub=${VL_PORTS.stub} redisDb=${VL_REDIS_DB}`)
}

function killTree(pid: number): void {
  if (process.platform === 'win32') {
    spawnSync('taskkill', ['/PID', String(pid), '/T', '/F'], { stdio: 'ignore' })
    return
  }
  try {
    process.kill(-pid, 'SIGTERM')
  } catch {
    try {
      process.kill(pid, 'SIGTERM')
    } catch {
      /* 已退出 */
    }
  }
}

/** 收尾自建实例（只杀本进程起的子进程；端口 LISTEN 归零）。 */
export async function stopVlStack(): Promise<void> {
  if (!ownedByThisProcess) return
  for (const [name, child] of Object.entries(children)) {
    if (child.pid) {
      log(`收尾 ${name} pid=${child.pid}`)
      killTree(child.pid)
    }
  }
  ownedByThisProcess = false
  await sleep(1500)
  await flushRedisDb(VL_REDIS_DB)
}

// ── 桩请求记录（披露清单与判定的真值来源） ────────────────────────────────────

export interface StubRequestRecord {
  tag: string
  tools: string[]
  decision: Record<string, unknown> | null
}

/** 读到桩的**最后一次**上游请求记录（reql 序号最大者）。 */
export function latestStubRequest(): StubRequestRecord | null {
  if (!existsSync(VL_STUB_DIR)) return null
  const files = readdirSync(VL_STUB_DIR).filter((name) => /^req-\d+\.json$/.test(name))
  if (files.length === 0) return null
  const ordered = files.sort((a, b) => statSync(join(VL_STUB_DIR, b)).mtimeMs - statSync(join(VL_STUB_DIR, a)).mtimeMs)
  const tag = ordered[0].replace(/^req-/, '').replace(/\.json$/, '')
  const toolsFile = join(VL_STUB_DIR, `req-${tag}.tools.txt`)
  const decisionFile = join(VL_STUB_DIR, `req-${tag}.decision.json`)
  const tools = existsSync(toolsFile)
    ? readFileSync(toolsFile, 'utf8').split(/\r?\n/).map((line) => line.trim()).filter((line) => line !== '')
    : []
  let decision: Record<string, unknown> | null = null
  if (existsSync(decisionFile)) {
    try {
      decision = JSON.parse(readFileSync(decisionFile, 'utf8')) as Record<string, unknown>
    } catch {
      decision = null
    }
  }
  return { tag, tools, decision }
}

/** 等下一次上游请求落盘（用于「发一条消息 → 读到该请求披露的工具集」）。 */
export async function untilStubRequestAfter(previousTag: string | null, timeoutMs = 60_000): Promise<StubRequestRecord> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const record = latestStubRequest()
    if (record && record.tag !== previousTag) return record
    await sleep(200)
  }
  throw new Error(`等待上游请求记录超时（在 ${previousTag ?? '(none)'} 之后无新请求）`)
}

export function resolveVlRunDir(): string {
  return resolve(VL_RUN_DIR)
}

// ── 页面侧：真模块直调（同一 Vite 模块实例） ──────────────────────────────────
//
// 为什么走「真模块直调」而不是桩路由：
//   桩路由里 6 个 UI 原语的 `epoch` 是固定串（`a1b2c3-1`），而 epoch 是模块加载期随机
//   nonce + 计数器（`ai-regions.ts` 的 `EPOCH_NONCE`）—— 模型路径必然停在「epoch 不匹配」
//   这一层，到不了"原语成功路径"。本轮**不改** `scripts/ci/stub-upstream.mjs`（它属共用
//   基建、且另有三路子智能体在并行读它），故成功路径在真页面里直接 import 真模块（与页面
//   共享同一份实例），从 `executeFrontendTool` 入口走完"路由 → 围栏 → 执行 → 回执"整链；
//   拒执路径仍走**真模型路径**（桩下发帧 → store → 执行器），两条路都落在真 DOM 上。

export interface VlExecution {
  outcome?: string
  availability?: string
  code?: string
  result?: string
  reason?: string
}

export interface VlCallOptions {
  /** 先做一次真实快照（默认 true；V-08/V-18 这类"未看先动"用例要传 false）。 */
  snapshot?: boolean
  /** 显式 epoch：`undefined` = 用当前 epoch，`null` = 不传 epoch 参数。 */
  epoch?: string | null
  runId?: string
  /** 新一轮 token（`beginUiTurn`）；同 token 不重置配额。 */
  turn?: string
  budgetMs?: number
  snapshotArgs?: Record<string, unknown>
}

/**
 * 装一个"按应用实际 URL 取模块"的导入器。
 *
 * 为什么不能直接 `import('/src/utils/ai-dom-tools.ts')`：dev server 的 HMR 会给**改动过的**
 * 模块加 `?t=<ts>` 版本号，裸路径 import 拿到的是**另一份模块实例**（区域表、快照闸、配额
 * 都是模块级状态，两份实例各存一份 —— 现象是"快照刚做，操作却报未快照"）。这里按
 * `performance` 里应用**真实加载过**的 URL 取同一份实例；没有记录时回退裸路径。
 */
function installImporter(): void {
  const host = window as unknown as { __vlImport?: (path: string) => Promise<unknown> }
  host.__vlImport = async (path: string) => {
    const loaded = performance.getEntriesByType('resource').map((entry) => entry.name)
    const exact = loaded.find((name) => name.includes(path))
    return import(exact ?? path)
  }
}

/** 每个 page 只装一次（addInitScript 对后续导航与 reload 同样生效）。 */
const prepared = new WeakSet<object>()

async function vlPrepare(page: import('@playwright/test').Page): Promise<void> {
  if (prepared.has(page)) return
  prepared.add(page)
  await page.addInitScript(installImporter)
  // 当前文档立刻装一份（addInitScript 只对**下一次**导航生效）
  await page.evaluate(installImporter).catch(() => undefined)
}

/** 在真页面里调一次前端工具（真模块、真 DOM、真回执）。 */
export async function vlCall(page: import('@playwright/test').Page, name: string, args: Record<string, unknown> = {}, options: VlCallOptions = {}): Promise<VlExecution> {
  await vlPrepare(page)
  const payload = {
    name,
    args,
    snapshot: options.snapshot !== false,
    epoch: options.epoch === undefined ? 'current' : options.epoch,
    runId: options.runId,
    turn: options.turn,
    budgetMs: options.budgetMs,
    snapshotArgs: options.snapshotArgs ?? { limit: 40 }
  }
  return page.evaluate(async (input) => {
    const load = async (path: string) => {
      const host = window as unknown as { __vlImport?: (value: string) => Promise<unknown> }
      return host.__vlImport ? host.__vlImport(path) : import(path)
    }
    const tools = await load('/src/utils/frontend-tools.ts') as {
      executeFrontendTool: (call: Record<string, unknown>) => Promise<VlExecution>
    }
    const dom = await load('/src/utils/ai-dom-tools.ts') as {
      aiSnapshot: (args: Record<string, unknown>) => { result?: string }
      beginUiTurn: (token: string) => void
    }
    const regions = await load('/src/utils/ai-regions.ts') as { currentAiEpoch: () => string }
    if (input.turn) dom.beginUiTurn(input.turn)
    if (input.snapshot) dom.aiSnapshot(input.snapshotArgs)
    const callArgs: Record<string, unknown> = { ...input.args }
    // 显式写在 args 里的 epoch 优先（"用旧 epoch 复现失效"这类用例靠它）
    if (callArgs.epoch === undefined) {
      if (input.epoch === 'current') callArgs.epoch = regions.currentAiEpoch()
      else if (input.epoch !== null && input.epoch !== undefined) callArgs.epoch = input.epoch
    }
    const call: Record<string, unknown> = { name: input.name, args: callArgs }
    if (input.runId) call.runId = input.runId
    if (input.budgetMs) call.budgetMs = input.budgetMs
    return tools.executeFrontendTool(call)
  }, payload)
}

export interface VlRefInfo {
  ref: string
  regionId: string
  risk: string
  label: string
  tag: string
  disabled: boolean
  value: string | null
}

export interface VlSnapshotView {
  epoch: string
  page: string | null
  regionIds: string[]
  refs: VlRefInfo[]
  text: string
}

/** 做一次真快照并回读"本轮页面里可被看见的 ref 集"（真 DOM 遍历产出）。 */
export async function vlSnapshot(page: import('@playwright/test').Page, args: Record<string, unknown> = { limit: 40 }): Promise<VlSnapshotView> {
  await vlPrepare(page)
  return page.evaluate(async (snapshotArgs) => {
    const load = async (path: string) => {
      const host = window as unknown as { __vlImport?: (value: string) => Promise<unknown> }
      return host.__vlImport ? host.__vlImport(path) : import(path)
    }
    const dom = await load('/src/utils/ai-dom-tools.ts') as {
      aiSnapshot: (args: Record<string, unknown>) => { result?: string }
    }
    const regions = await load('/src/utils/ai-regions.ts') as {
      currentAiEpoch: () => string
      aiCurrentPage: () => string | null
      listAiRegions: () => ReadonlyArray<{ id: string; root: HTMLElement }>
      queryRegistered: (scope: ParentNode, selector: string) => HTMLElement[]
      riskOfElement: (el: HTMLElement, region: { risk: string }) => string
    }
    const outcome = dom.aiSnapshot(snapshotArgs)
    const refs: VlRefInfo[] = []
    const regionIds: string[] = []
    for (const region of regions.listAiRegions()) {
      regionIds.push(region.id)
      for (const el of regions.queryRegistered(region.root, '[data-ai-ref]')) {
        refs.push({
          ref: el.getAttribute('data-ai-ref') ?? '',
          regionId: region.id,
          risk: regions.riskOfElement(el, region as unknown as { risk: string }),
          label: el.getAttribute('data-ai-label') ?? '',
          tag: el.tagName.toLowerCase(),
          disabled: el.hasAttribute('disabled') || el.getAttribute('aria-disabled') === 'true',
          value: (el as HTMLInputElement).value ?? null
        })
      }
    }
    return {
      epoch: regions.currentAiEpoch(),
      page: regions.aiCurrentPage(),
      regionIds,
      refs,
      text: outcome.result ?? ''
    }
  }, args)
}

/** 读 UI 原语运行时状态（配额 / 撤销 / 播报），用于断言"副作用/配额/播报"这类离散量。 */
export async function vlRuntime(page: import('@playwright/test').Page): Promise<{
  writeCount: number
  undo: unknown
  announcement: string | null
  epoch: string
}> {
  await vlPrepare(page)
  return page.evaluate(async () => {
    const load = async (path: string) => {
      const host = window as unknown as { __vlImport?: (value: string) => Promise<unknown> }
      return host.__vlImport ? host.__vlImport(path) : import(path)
    }
    const dom = await load('/src/utils/ai-dom-tools.ts') as {
      uiWriteVerbCount: () => number
      peekUiUndo: () => unknown
      peekUiAnnouncement: () => string | null
    }
    const regions = await load('/src/utils/ai-regions.ts') as {
      currentAiEpoch: () => string
    }
    return {
      writeCount: dom.uiWriteVerbCount(),
      undo: dom.peekUiUndo(),
      announcement: dom.peekUiAnnouncement(),
      epoch: regions.currentAiEpoch()
    }
  })
}

/** 开始新一轮（重置配额 / 停止位 / 去重作用域）。 */
export async function vlBeginTurn(page: import('@playwright/test').Page, token: string): Promise<void> {
  await vlPrepare(page)
  await page.evaluate(async (value) => {
    const host = window as unknown as { __vlImport?: (path: string) => Promise<unknown> }
    const dom = await (host.__vlImport ? host.__vlImport('/src/utils/ai-dom-tools.ts') : import('/src/utils/ai-dom-tools.ts')) as {
      beginUiTurn: (token: string) => void
    }
    dom.beginUiTurn(value)
  }, token)
}

/** 模拟用户点「停止」对界面动词的收口（`abortUiOperations`）。 */
export async function vlAbortOperations(page: import('@playwright/test').Page): Promise<void> {
  await vlPrepare(page)
  await page.evaluate(async () => {
    const host = window as unknown as { __vlImport?: (path: string) => Promise<unknown> }
    const dom = await (host.__vlImport ? host.__vlImport('/src/utils/ai-dom-tools.ts') : import('/src/utils/ai-dom-tools.ts')) as {
      abortUiOperations: () => void
    }
    dom.abortUiOperations()
  })
}

/** 打开应用并等首屏就绪（composer 可见）。 */
export async function vlOpenApp(page: import('@playwright/test').Page, path = '/'): Promise<void> {
  await vlPrepare(page)
  await page.goto(path, { waitUntil: 'domcontentloaded' })
  await page.locator('[data-testid="composer-input"]').waitFor({ state: 'visible', timeout: 30_000 })
}

/** 等页面把 AI 区域注册上来（区域存在 = 页面挂载并且控件真在 DOM 里）。 */
export async function vlWaitRegions(page: import('@playwright/test').Page, regionId?: string): Promise<void> {
  await vlPrepare(page)
  await page.waitForFunction(async (id) => {
    const host = window as unknown as { __vlImport?: (path: string) => Promise<unknown> }
    const regions = await (host.__vlImport ? host.__vlImport('/src/utils/ai-regions.ts') : import('/src/utils/ai-regions.ts')) as {
      listAiRegions: () => ReadonlyArray<{ id: string }>
    }
    const list = regions.listAiRegions()
    return id ? list.some((region) => region.id === id) : list.length > 0
  }, regionId ?? null, { timeout: 30_000 })
}

/** 等页脚工作区上下文键出现（`page:tasks` / `task:IMPORT/UPLOAD` 这类对外契约串）。 */
export async function vlWaitContext(page: import('@playwright/test').Page, key: string): Promise<void> {
  await page.locator('footer').filter({ hasText: key }).first().waitFor({ state: 'visible', timeout: 30_000 })
}
