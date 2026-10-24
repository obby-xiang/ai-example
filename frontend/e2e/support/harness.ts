/**
 * 真机 E2E harness（**全仓唯一**一套起停；运行目录 / 状态文件 / H2 目录 / 端口 / Redis DB 全部参数化）。
 *
 * 形态：
 *   - 桩上游  scripts/ci/stub-upstream.mjs（或 `E2E_STUB_SCRIPT` 指定的本域桩）  默认 18399（桩模式）
 *   - 可选二级桩 `E2E_STUB_UPSTREAM_PORT`（本域桩转发用的官方桩，如 NFEZUI 的 19198）
 *   - 后端 A  独立 H2 + 独立 Redis DB                   默认 18330 / DB 5（真 key 或桩上游）
 *   - 后端 B  无 key（降级面）                           默认 18331 / DB 6
 *   - 前端 A  vite dev server（代理到后端 A）            默认 18401
 *   - 前端 B  vite dev server（代理到后端 B）            默认 18402
 *
 * 并行隔离（L-81 收口）：**一域 = 一组环境变量**，不再各自 fork 副本。
 *   | 变量 | 默认 | 含义 |
 *   | --- | --- | --- |
 *   | `E2E_RUN_DIR` | `frontend/test-results/.e2e-run` | 运行目录（日志 / state.json / H2 都在这棵树下） |
 *   | `E2E_STATE_FILE` | `<RUN_DIR>/state.json` | pid 台账（跨进程收尾的唯一依据） |
 *   | `E2E_DATA_DIR` / `E2E_DEGRADED_DATA_DIR` | `<RUN_DIR>/data` / `<RUN_DIR>/data-degraded` | 两个 H2 数据目录 |
 *   | `E2E_BACKEND_PORT` / `E2E_DEGRADED_BACKEND_PORT` / `E2E_STUB_PORT` / `E2E_STUB_UPSTREAM_PORT` / `E2E_APP_PORT` / `E2E_DEGRADED_APP_PORT` | 18330 / 18331 / 18399 / （不启） / 18401 / 18402 | 端口 |
 *   | `E2E_REDIS_DB` / `E2E_REDIS_DB_DEGRADED` | 5 / 6 | Redis 库号（合法 0-15；起栈前 SELECT 校验，越界立即失败） |
 *   | `E2E_AI_LIMIT_SESSION_PER_MINUTE` / `E2E_AI_LIMIT_IP_PER_MINUTE` | 200 / 2000 | 限流抬高（E2E 不在限流覆盖面） |
 *   | `E2E_EXTRA_BACKEND_ARGS` | 空 | 追加给 java 的 `--k=v`（空格分隔，如 `--app.ai.suspend.pool-size=3`） |
 *   | `E2E_READY_TIMEOUT_MS` / `E2E_SEED_TIMEOUT_MS` / `E2E_APP_READY_TIMEOUT_MS` | 120000 / 90000 / 90000 | 就绪等待 |
 *   | `E2E_STUB_READY_TIMEOUT_MS` | 30000 | 桩（含二级官方桩）探活等待 |
 *   | `E2E_STUB_SCRIPT` | `scripts/ci/stub-upstream.mjs` | 本域桩脚本（需要自定义上游形状时换掉） |
 *   - 相对路径一律按 `frontend/` 解析（与 `E2E_*` 端口变量的使用习惯一致）。
 *
 * 纪律：
 *   - 绝不占用常开的 8081 / 5202；端口被占即失败并给出可行动提示（不杀别人的进程）；
 *   - Java 走 backend/target/classes + 依赖 classpath（不锁 config-mgr.jar）；
 *   - 日志与 H2 数据全部落 `E2E_RUN_DIR`（.gitignore 已排除）；
 *   - 环境变量值（含真 key）一律不回显。
 */
import { spawn, spawnSync, type ChildProcess } from 'node:child_process'
import { closeSync, existsSync, mkdirSync, openSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import net from 'node:net'
import { delimiter, isAbsolute, join, resolve } from 'node:path'
import { setTimeout as sleep } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import Redis from 'ioredis'

const HERE = fileURLToPath(new URL('.', import.meta.url))
export const FRONTEND_DIR = resolve(HERE, '../..')
export const REPO_ROOT = resolve(FRONTEND_DIR, '..')

/** 相对路径按 `frontend/` 解析；绝对路径原样使用。 */
function pathFromEnv(value: string | undefined, fallback: string): string {
  if (value === undefined || value === '') return fallback
  return isAbsolute(value) ? value : resolve(FRONTEND_DIR, value)
}

export const RUN_DIR = pathFromEnv(process.env.E2E_RUN_DIR, join(FRONTEND_DIR, 'test-results', '.e2e-run'))
const STATE_FILE = pathFromEnv(process.env.E2E_STATE_FILE, join(RUN_DIR, 'state.json'))
export const DATA_DIR = pathFromEnv(process.env.E2E_DATA_DIR, join(RUN_DIR, 'data'))
export const DEGRADED_DATA_DIR = pathFromEnv(process.env.E2E_DEGRADED_DATA_DIR, join(RUN_DIR, 'data-degraded'))

export const MODE: 'stub' | 'real' = process.env.E2E_MODE === 'real' ? 'real' : 'stub'

export const PORTS = {
  backend: Number(process.env.E2E_BACKEND_PORT ?? '18330'),
  degradedBackend: Number(process.env.E2E_DEGRADED_BACKEND_PORT ?? '18331'),
  stub: Number(process.env.E2E_STUB_PORT ?? '18399'),
  /** 0 = 不起二级官方桩（本域桩转发用；NFEZUI 域用 19198）。 */
  stubUpstream: Number(process.env.E2E_STUB_UPSTREAM_PORT ?? '0'),
  app: Number(process.env.E2E_APP_PORT ?? '18401'),
  degradedApp: Number(process.env.E2E_DEGRADED_APP_PORT ?? '18402')
}

export const REDIS_DB = Number(process.env.E2E_REDIS_DB ?? '5')
export const DEGRADED_REDIS_DB = Number(process.env.E2E_REDIS_DB_DEGRADED ?? '6')

/** 本域上游桩脚本（默认 = 官方桩；NFEZUI 域指向 support/nfezui-upstream.mjs）。 */
export const STUB_SCRIPT = pathFromEnv(
  process.env.E2E_STUB_SCRIPT,
  join(REPO_ROOT, 'scripts', 'ci', 'stub-upstream.mjs')
)

const AI_LIMIT_SESSION_PER_MINUTE = process.env.E2E_AI_LIMIT_SESSION_PER_MINUTE ?? '200'
const AI_LIMIT_IP_PER_MINUTE = process.env.E2E_AI_LIMIT_IP_PER_MINUTE ?? '2000'
/** 追加给 java 的启动参数（空格分隔；值里不允许带空格 —— 需要空格的参数请走 `startServices({ backendArgs })`）。 */
const EXTRA_BACKEND_ARGS = (process.env.E2E_EXTRA_BACKEND_ARGS ?? '').split(/\s+/).filter((s) => s !== '')

const READY_TIMEOUT_MS = Number(process.env.E2E_READY_TIMEOUT_MS ?? '120000')
const SEED_TIMEOUT_MS = Number(process.env.E2E_SEED_TIMEOUT_MS ?? '90000')
const APP_READY_TIMEOUT_MS = Number(process.env.E2E_APP_READY_TIMEOUT_MS ?? '90000')
const STUB_READY_TIMEOUT_MS = Number(process.env.E2E_STUB_READY_TIMEOUT_MS ?? '30000')

export const URLS = {
  api: process.env.E2E_API_BASE ?? `http://127.0.0.1:${PORTS.backend}`,
  degradedApi: process.env.E2E_DEGRADED_API_BASE ?? `http://127.0.0.1:${PORTS.degradedBackend}`,
  app: process.env.E2E_APP ?? `http://127.0.0.1:${PORTS.app}`,
  degradedApp: process.env.E2E_DEGRADED_APP ?? `http://127.0.0.1:${PORTS.degradedApp}`,
  stub: `http://127.0.0.1:${PORTS.stub}`
}

interface RunState {
  mode: string
  startedAt: number
  /** 起服务的进程 pid：收尾时只有 owner 死亡（或与当前进程相同）才按台账杀，避免第二棒 setup 失败误杀第一棒。 */
  ownerPid?: number
  pids: Record<string, number>
  ports: typeof PORTS
}

function readState(): RunState | null {
  if (!existsSync(STATE_FILE)) return null
  try {
    return JSON.parse(readFileSync(STATE_FILE, 'utf8')) as RunState
  } catch {
    return null
  }
}

function log(line: string): void {
  // eslint-disable-next-line no-console
  console.log(`[e2e-harness] ${line}`)
}

export function isPortFree(port: number): Promise<boolean> {
  return new Promise((done) => {
    const server = net.createServer()
    server.once('error', () => done(false))
    server.once('listening', () => server.close(() => done(true)))
    server.listen(port, '127.0.0.1')
  })
}

async function assertPortFree(port: number, label: string): Promise<void> {
  if (await isPortFree(port)) return
  throw new Error(
    `端口 ${port}（${label}）已被占用：E2E harness 必须使用独立端口段（默认 18330/18399/18401/18402）。`
      + '请先释放该端口，或用 E2E_*_PORT 环境变量换一段端口；harness 不会杀别人的进程。'
  )
}

// ── Redis（ioredis；SELECT 越界前置校验见 assertRedisDbRange / L-114）──────────────

const REDIS_DB_MIN = 0
const REDIS_DB_MAX = 15

function redisEndpoint(): { host: string; port: number } {
  return {
    host: process.env.E2E_REDIS_HOST ?? '127.0.0.1',
    port: Number(process.env.E2E_REDIS_PORT ?? '6379')
  }
}

function redisDbHint(): string {
  return '合法范围 0-15。库号来源：E2E_REDIS_DB / E2E_REDIS_DB_DEGRADED（各域 playwright.*.config.ts 或 harness 默认值）、'
    + 'VL 域的 VL_REDIS_DB；请改对应环境变量或 config 里的默认值。'
}

function redisDbRangeError(db: number, variable?: string): Error {
  const shown = Number.isNaN(db) ? 'NaN（不是数字）' : String(db)
  const source = variable ? `${variable}=${shown}` : `Redis 库号 ${shown}`
  return new Error(`Redis 库号越界：${source}；${redisDbHint()}`)
}

function isDbIndexOutOfRange(error: unknown): boolean {
  return error instanceof Error && /DB index is out of range/i.test(error.message)
}

function translateRedisError(error: unknown, db: number, variable?: string): Error {
  if (isDbIndexOutOfRange(error)) return redisDbRangeError(db, variable)
  const message = error instanceof Error ? error.message : String(error)
  if (/ECONNREFUSED|ECONNRESET|ETIMEDOUT|Connection is closed|connect/i.test(message)) {
    const { host, port } = redisEndpoint()
    return new Error(
      `连接 Redis 失败（${host}:${port}）：${message.replace(/[。.]$/, '')}。E2E 需要本机 Redis 在线`
        + '（可用 E2E_REDIS_HOST / E2E_REDIS_PORT 指向别的实例）。'
    )
  }
  return error instanceof Error ? error : new Error(message)
}

/** 一次性连接：lazyConnect 让错误走 Promise；不重试（E2E 要快速失败，不要静默挂住）。 */
function createRedisClient(): Redis {
  const client = new Redis({
    ...redisEndpoint(),
    lazyConnect: true,
    connectTimeout: 3000,
    commandTimeout: 5000,
    maxRetriesPerRequest: 1,
    retryStrategy: () => null
  })
  // ioredis 在连接层失败时会发 'error'；没有监听器会变成未捕获异常。
  // 单条命令的失败仍由各自的 Promise 拒绝送出（这里只兜底，不把错误吞成成功）。
  client.on('error', () => {})
  return client
}

/** 连接 → SELECT → 执行 → 断开。SELECT / 连接失败在这里统一翻成人话。 */
async function withRedis<T>(db: number, run: (client: Redis) => Promise<T>, variable?: string): Promise<T> {
  if (!Number.isInteger(db) || db < REDIS_DB_MIN || db > REDIS_DB_MAX) {
    throw redisDbRangeError(db, variable)
  }
  const client = createRedisClient()
  try {
    await client.connect()
    await client.select(db)
    return await run(client)
  } catch (error) {
    throw translateRedisError(error, db, variable)
  } finally {
    client.disconnect()
  }
}

/**
 * L-114：起栈前先验“目标库号能不能选中”。
 *
 * 旧形态下库号写超 15 时，清库在 socket 层静默失败 → 后端照常起 → 最后卡在健康检查 120s 超时，
 * 报错完全指错方向。这里对目标库真发一次 SELECT：越界立刻以「哪个变量 / 当前值 / 合法范围 /
 * 去哪改」失败（毫秒级），而不是烧满 120s。
 */
export async function assertRedisDbRange(db: number, variable: string): Promise<void> {
  await withRedis(db, async () => undefined, variable)
}

/** 清空本域专用库（SELECT + FLUSHDB；ioredis 负责完整收包与 UTF-8）。 */
export async function flushRedisDb(db: number): Promise<void> {
  await withRedis(db, async (client) => {
    await client.flushdb()
  })
}

function respBulk(value: string): string {
  return `$${Buffer.byteLength(value)}\r\n${value}\r\n`
}

/** ioredis 的已解析回包 → RESP 原文（`redisCommand` 的历史返回形态）。 */
function encodeResp(reply: unknown): string {
  if (reply === null || reply === undefined) return '$-1\r\n'
  if (typeof reply === 'number') return `:${reply}\r\n`
  if (Buffer.isBuffer(reply)) return respBulk(reply.toString('utf8'))
  if (Array.isArray(reply)) return `*${reply.length}\r\n${reply.map((item) => encodeResp(item)).join('')}`
  return respBulk(String(reply))
}

/**
 * 通用 Redis 命令（仅本域 DB）：`SELECT <db>` 之后发一条命令，返回 RESP 原文。
 * 导出签名与返回形态保持兼容（helpers-mdxy 的 LRANGE 解析、helpers-nfezui 的 TTL/整数前缀）。
 * 注意：状态回包（`+OK`）在 ioredis 里与 bulk string 无法区分，这里统一编码为 bulk（`$2\r\nOK`）——
 * 现有调用方只做 trim / 取 `:` 或用 RESP 数组解析，不依赖 `+` 前缀。
 */
export async function redisCommand(db: number, args: string[]): Promise<string> {
  return withRedis(db, async (client) => {
    const [command, ...rest] = args
    const reply = await client.call(command ?? '', rest)
    return encodeResp(reply)
  })
}

/** KQC 域的命令面（`{ type, value }`）；类型与取值口径与旧自写客户端一致。 */
export async function redisExec(db: number, args: string[]): Promise<{ type: 'int' | 'bulk' | 'array'; value: string }> {
  return withRedis(db, async (client) => {
    const [command, ...rest] = args
    const reply = await client.call(command ?? '', rest)
    if (reply === null || reply === undefined) return { type: 'bulk' as const, value: '' }
    if (typeof reply === 'number') return { type: 'int' as const, value: String(reply) }
    if (Array.isArray(reply)) {
      return { type: 'array' as const, value: reply.map((item) => (item === null ? '' : String(item))).join('\n') }
    }
    if (Buffer.isBuffer(reply)) return { type: 'bulk' as const, value: reply.toString('utf8') }
    return { type: 'bulk' as const, value: String(reply) }
  })
}

/** `KEYS <pattern>`（本域独占 DB，量小无需 SCAN 分页）。 */
export async function redisKeys(db: number, pattern: string): Promise<string[]> {
  return withRedis(db, async (client) => client.keys(pattern))
}

// ── Java classpath（classes + 依赖，避免 jar 锁） ─────────────────────────────
const MAIN_CLASS = 'com.example.configmgr.ConfigMgrApplication'

function javaBin(): string {
  const home = javaHome()
  return home ? join(home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java'
}

/** 解析 JDK 目录：优先 JAVA_HOME，否则读 `java -XshowSettings:properties` 的 java.home。 */
function javaHome(): string | undefined {
  if (process.env.JAVA_HOME) return process.env.JAVA_HOME
  const probe = spawnSync('java', ['-XshowSettings:properties', '-version'], { encoding: 'utf8' })
  const text = `${probe.stdout ?? ''}${probe.stderr ?? ''}`
  const match = /^\s*java\.home\s*=\s*(.+)$/m.exec(text)
  return match?.[1]?.trim() || undefined
}

function jarListIn(dir: string): string[] {
  if (!existsSync(dir)) return []
  return readdirSync(dir)
    .filter((name) => name.endsWith('.jar'))
    .sort()
    .map((name) => join(dir, name))
}

/** 从 Spring Boot 胖包中解出 BOOT-INF/lib（首次运行后缓存在 RUN_DIR/jar）。 */
function extractJarLibs(): string[] {
  const jar = join(REPO_ROOT, 'backend', 'target', 'config-mgr.jar')
  const outDir = join(RUN_DIR, 'jar')
  const libDir = join(outDir, 'BOOT-INF', 'lib')
  const cached = jarListIn(libDir)
  if (cached.length > 0) return cached
  if (!existsSync(jar)) {
    throw new Error('缺少依赖 classpath：backend/target/classes 与 config-mgr.jar 都不存在，请先在 backend 执行 mvn -q -DskipTests package')
  }
  mkdirSync(outDir, { recursive: true })
  const home = javaHome()
  const jarTool = home
    ? join(home, 'bin', process.platform === 'win32' ? 'jar.exe' : 'jar')
    : 'jar'
  const result = spawnSync(jarTool, ['--extract', `--file=${jar}`, 'BOOT-INF/lib'], { cwd: outDir, encoding: 'utf8' })
  if (result.status !== 0) {
    throw new Error(`从胖包解依赖失败（jar --extract，工具 ${jarTool}）：${result.stderr || result.stdout || result.error?.message || '未知错误'}`)
  }
  return jarListIn(libDir)
}

function resolveDependencyClasspath(): string {
  const explicit = process.env.E2E_BACKEND_CLASSPATH
  if (explicit) return explicit

  const cpFile = join(REPO_ROOT, 'backend', 'target', 'e2e-classpath.txt')
  if (existsSync(cpFile)) {
    const text = readFileSync(cpFile, 'utf8').trim()
    if (text) return text
  }

  const mvn = process.platform === 'win32' ? 'mvn.cmd' : 'mvn'
  const probe = spawnSync(mvn, ['-v'], { encoding: 'utf8' })
  if (probe.status === 0) {
    const generated = spawnSync(
      mvn,
      ['-q', '-B', 'dependency:build-classpath', '-Dmdep.outputFile=target/e2e-classpath.txt'],
      { cwd: join(REPO_ROOT, 'backend'), encoding: 'utf8' }
    )
    if (generated.status === 0 && existsSync(cpFile)) {
      return readFileSync(cpFile, 'utf8').trim()
    }
  }

  // 本地无 Maven 时从胖包解依赖（首次 ~1s，之后走缓存）
  return extractJarLibs().join(delimiter)
}

function javaArgs(port: number, redisDb: number, dataDir: string, extra: string[]): string[] {
  const classes = join(REPO_ROOT, 'backend', 'target', 'classes')
  if (!existsSync(join(classes, 'application.yml'))) {
    throw new Error('缺少 backend/target/classes（含 application.yml）：请先在 backend 执行 mvn -q -DskipTests package')
  }
  const classpath = [classes, resolveDependencyClasspath()].join(delimiter)
  return [
    '-cp', classpath, MAIN_CLASS,
    `--server.port=${port}`,
    `--spring.datasource.url=jdbc:h2:file:${dataDir}/e2e;DB_CLOSE_DELAY=-1`,
    `--spring.data.redis.database=${redisDb}`,
    '--app.job.batch-size=10',
    '--app.job.demo-batch-delay-ms=150',
    `--app.ai.limit.session-per-minute=${AI_LIMIT_SESSION_PER_MINUTE}`,
    `--app.ai.limit.ip-per-minute=${AI_LIMIT_IP_PER_MINUTE}`,
    ...extra
  ]
}

// ── 进程与就绪 ────────────────────────────────────────────────────────────────

const children: Record<string, ChildProcess> = {}

function spawnLogged(name: string, cmd: string, args: string[], options: {
  cwd: string
  env?: NodeJS.ProcessEnv
}): ChildProcess {
  const logPath = join(RUN_DIR, `${name}.log`)
  const fd = openSync(logPath, 'a')
  const child = spawn(cmd, args, {
    cwd: options.cwd,
    env: options.env ?? process.env,
    stdio: ['ignore', fd, fd],
    windowsHide: true,
    // POSIX 下自成进程组：收尾可用 kill(-pid) 连 vite 的 esbuild 子进程一起带走。
    detached: process.platform !== 'win32'
  })
  child.once('exit', () => closeSync(fd))
  child.once('error', (error) => {
    try {
      closeSync(fd)
    } catch {
      /* 已关闭 */
    }
    log(`${name} 启动失败：${error.message}`)
  })
  if (child.pid) children[name] = child
  log(`${name} pid=${child.pid} → ${logPath}`)
  return child
}

async function waitForHttp(
  url: string,
  label: string,
  accept: (body: string, status: number) => boolean = () => true,
  timeoutMs = 120_000
): Promise<void> {
  const deadline = Date.now() + timeoutMs
  let lastError = ''
  while (Date.now() < deadline) {
    try {
      const res = await fetch(url)
      const text = await res.text()
      if (accept(text, res.status)) return
      lastError = `HTTP ${res.status}`
    } catch (error) {
      lastError = error instanceof Error ? error.message : String(error)
    }
    await sleep(500)
  }
  throw new Error(`${label} 未在 ${Math.round(timeoutMs / 1000)}s 内就绪（${url}）：${lastError}`)
}

function envWithoutAi(env: NodeJS.ProcessEnv): NodeJS.ProcessEnv {
  const copy = { ...env }
  delete copy.AI_API_KEY
  delete copy.DEEPSEEK_API_KEY
  delete copy.AI_BASE_URL
  delete copy.AI_MODEL
  return copy
}

export interface StartOptions {
  /** 只起指定组件（调试用）；缺省全起。 */
  only?: Array<'main' | 'degraded' | 'app' | 'degradedApp'>
  /** 追加给 java 的启动参数（值里可带空格；比 `E2E_EXTRA_BACKEND_ARGS` 更安全）。 */
  backendArgs?: string[]
}

export async function startServices(options: StartOptions = {}): Promise<void> {
  if (process.env.E2E_EXTERNAL === '1') {
    log('E2E_EXTERNAL=1：不自起服务，直接使用现有实例')
    return
  }
  const only = options.only ?? (MODE === 'stub'
    ? ['main', 'degraded', 'app', 'degradedApp']
    : ['main', 'app'])
  const needStub = MODE === 'stub' && only.includes('main')
  const extraArgs = [...EXTRA_BACKEND_ARGS, ...(options.backendArgs ?? [])]

  // L-114：库号写超 15 时，清库会静默失败、后端照常起，最后卡在健康检查 120s 超时上。
  // 起栈前先对目标库真发一次 SELECT（本机预检 + 服务端回包），毫秒级给出「哪个变量 / 合法范围 / 去哪改」。
  await assertRedisDbRange(REDIS_DB, 'E2E_REDIS_DB')
  if (only.includes('degraded')) await assertRedisDbRange(DEGRADED_REDIS_DB, 'E2E_REDIS_DB_DEGRADED')

  mkdirSync(RUN_DIR, { recursive: true })

  const portChecks: Array<[number, string]> = [
    [PORTS.backend, '后端 A'],
    [PORTS.app, '前端 A']
  ]
  if (only.includes('degraded')) portChecks.push([PORTS.degradedBackend, '后端 B（降级面）'])
  if (only.includes('degradedApp')) portChecks.push([PORTS.degradedApp, '前端 B（降级面）'])
  if (needStub) portChecks.push([PORTS.stub, '桩上游'])
  if (needStub && PORTS.stubUpstream > 0) portChecks.push([PORTS.stubUpstream, '二级官方桩'])
  for (const [port, label] of portChecks) await assertPortFree(port, label)

  // 每棒从干净的数据面开始（H2 文件 + 专用 Redis DB），避免上一棒的在途轮/会话污染。
  for (const dir of [DATA_DIR, DEGRADED_DATA_DIR]) {
    rmSync(dir, { recursive: true, force: true })
  }
  // 旧版 URL（jdbc:h2:file:./data）留下的库文件也要清：否则 ai_provider 残留会让 strict seed 跳种。
  for (const file of ['data.mv.db', 'data.trace.db', 'data-degraded.mv.db', 'data-degraded.trace.db']) {
    rmSync(join(RUN_DIR, file), { force: true })
  }
  await flushRedisDb(REDIS_DB)
  // 降级库只在本棒真的要起降级面时才清：并行时别把别域的降级库一起 FLUSH 掉。
  if (only.includes('degraded')) await flushRedisDb(DEGRADED_REDIS_DB)

  const pids: Record<string, number> = {}
  // 每起一个进程就落一次状态：setup 中途失败时 globalTeardown 也能按 pid 收干净。
  const persist = (): void => {
    writeFileSync(
      STATE_FILE,
      JSON.stringify({ mode: MODE, startedAt: Date.now(), ownerPid: process.pid, pids, ports: PORTS }, null, 2)
    )
  }

  if (needStub && PORTS.stubUpstream > 0) {
    // 二级官方桩：本域桩（`E2E_STUB_SCRIPT`）转发用的兜底上游（NFEZUI 域的形状）。
    const upstreamDir = join(RUN_DIR, 'upstream')
    mkdirSync(upstreamDir, { recursive: true })
    const upstream = spawnLogged('upstream', process.execPath, [
      join(REPO_ROOT, 'scripts', 'ci', 'stub-upstream.mjs'),
      '--port', String(PORTS.stubUpstream),
      '--dir', upstreamDir
    ], { cwd: REPO_ROOT })
    if (upstream.pid) pids.upstream = upstream.pid
    persist()
    await waitForHttp(`http://127.0.0.1:${PORTS.stubUpstream}/probe`, '二级官方桩', () => true, STUB_READY_TIMEOUT_MS)
  }

  if (needStub) {
    const stubDir = join(RUN_DIR, 'stub')
    // L-97：桩的请求落档序号（`req-NNN`）是**进程内**计数，每棒从 0 重来；复用同一 RUN_DIR 时
    // 残留的上一棒文件会让 `maxStubIndex()` 读到旧的大序号 ⇒ `waitStubRequestsAfter` 永远等不到。
    // 故起桩前整目录清空（与 H2 / Redis 同一清理时点；只在端口检查通过后动手，不碰别域在跑的目录）。
    rmSync(stubDir, { recursive: true, force: true })
    mkdirSync(stubDir, { recursive: true })
    const stubArgs = [STUB_SCRIPT, '--port', String(PORTS.stub), '--dir', stubDir]
    if (PORTS.stubUpstream > 0 && resolve(STUB_SCRIPT) !== resolve(REPO_ROOT, 'scripts', 'ci', 'stub-upstream.mjs')) {
      stubArgs.push('--upstream', `http://127.0.0.1:${PORTS.stubUpstream}`)
    }
    const stub = spawnLogged('stub', process.execPath, stubArgs, { cwd: REPO_ROOT })
    if (stub.pid) pids.stub = stub.pid
    persist()
    await waitForHttp(`${URLS.stub}/probe`, '桩上游', () => true, STUB_READY_TIMEOUT_MS)
  }

  if (only.includes('main')) {
    const extra = [
      ...extraArgs,
      '--app.ai.sheet.write-enabled=true',
      // 挂起确认门永不超时；续跑扫描在每棒干净数据面下无需参与。
      '--app.ai.resume.on-startup=false'
    ]
    const env: NodeJS.ProcessEnv = { ...process.env }
    if (MODE === 'stub') {
      env.AI_API_KEY = 'e2e-stub-placeholder'
      env.AI_BASE_URL = URLS.stub
      env.AI_MODEL = 'e2e-stub-model'
    } else {
      const key = process.env.AI_API_KEY ?? process.env.DEEPSEEK_API_KEY
      if (!key) throw new Error('E2E_MODE=real 需要 AI_API_KEY（CI 从 environment e2e-real 注入；本地只经环境变量传入）')
      env.AI_API_KEY = key
      env.AI_BASE_URL = process.env.AI_BASE_URL ?? 'https://api.deepseek.com'
      env.AI_MODEL = process.env.AI_MODEL ?? 'deepseek-flash'
    }
    const backend = spawnLogged('backend', javaBin(), javaArgs(PORTS.backend, REDIS_DB, DATA_DIR, extra), {
      cwd: RUN_DIR,
      env
    })
    if (backend.pid) pids.backend = backend.pid
    persist()
  }

  if (only.includes('degraded')) {
    // 降级面：无 key、无上游 → /api/ai/health 的 available 必须为 false（D-02/E-07 的底座）。
    const backend = spawnLogged(
      'backend-degraded',
      javaBin(),
      javaArgs(PORTS.degradedBackend, DEGRADED_REDIS_DB, DEGRADED_DATA_DIR, extraArgs),
      { cwd: RUN_DIR, env: envWithoutAi(process.env) }
    )
    if (backend.pid) pids.backend_degraded = backend.pid
    persist()
  }

  if (only.includes('main')) {
    await waitForHttp(
      `${URLS.api}/api/ai/health`,
      '后端 A（available + redis.available）',
      (body) => {
        try {
          const json = JSON.parse(body) as { available?: boolean; redis?: { available?: boolean } }
          return json.available === true && json.redis?.available === true
        } catch {
          return false
        }
      },
      READY_TIMEOUT_MS
    )
    await waitForHttp(`${URLS.api}/api/definitions`, '后端 A 种子', (body) => {
      try {
        const json = JSON.parse(body) as { data?: unknown[] }
        return Array.isArray(json.data) && json.data.length >= 15
      } catch {
        return false
      }
    }, SEED_TIMEOUT_MS)
  }

  if (only.includes('degraded')) {
    await waitForHttp(`${URLS.degradedApi}/api/ai/health`, '后端 B（降级面）', (body, status) => {
      if (status !== 503) return false
      try {
        const json = JSON.parse(body) as { available?: boolean }
        return json.available === false
      } catch {
        return false
      }
    })
  }

  const startApp = async (name: string, port: number, apiBase: string): Promise<void> => {
    const viteBin = join(FRONTEND_DIR, 'node_modules', 'vite', 'bin', 'vite.js')
    if (!existsSync(viteBin)) throw new Error('缺少 frontend/node_modules/vite：请先 yarn install')
    const child = spawnLogged(name, process.execPath, [viteBin, '--port', String(port), '--strictPort', '--host', '127.0.0.1'], {
      cwd: FRONTEND_DIR,
      // L-115：E2E 的页面必须看到的是一份**运行期不变**的模块图 —— 工作树被并发编辑时，
      // HMR 的 `?t=` 重新取模块会让同一页面里出现两份 `stores/workspace.ts`（页面能力注册表
      // 是模块级 Map）⇒ 「当前页面没有可操作的表格」/ SpreadGrid 中途重挂。故关掉 HMR。
      env: { ...process.env, VITE_API_BASE: apiBase, VITE_DEV_PORT: String(port), VITE_HMR: '0' }
    })
    if (child.pid) pids[name === 'app' ? 'app' : 'app_degraded'] = child.pid
    persist()
  }

  if (only.includes('app')) {
    await startApp('app', PORTS.app, URLS.api)
    await waitForHttp(`${URLS.app}/`, '前端 A', () => true, APP_READY_TIMEOUT_MS)
  }
  if (only.includes('degradedApp')) {
    await startApp('app-degraded', PORTS.degradedApp, URLS.degradedApi)
    await waitForHttp(`${URLS.degradedApp}/`, '前端 B（降级面）', () => true, APP_READY_TIMEOUT_MS)
  }

  persist()
  log(`就绪：api=${URLS.api} app=${URLS.app} degraded=${URLS.degradedApi}/${URLS.degradedApp} mode=${MODE}`)
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

/** 进程是否还活着（跨进程收尾判据；win32 上 signal 0 同样可用）。 */
function isAlive(pid: number): boolean {
  try {
    process.kill(pid, 0)
    return true
  } catch {
    return false
  }
}

export async function stopServices(): Promise<void> {
  if (process.env.E2E_EXTERNAL === '1') return
  const state = readState()
  if (!state) return
  // 归属闸：state 由**本次运行**写下的（ownerPid 相同）或 owner 已退出（上一棒残留）才收；
  // 别人还在跑（同一 RUN_DIR 上的第二棒 setup 失败）时**一律不碰**，否则会替别路 taskkill。
  const owner = state.ownerPid
  if (owner !== undefined && owner !== process.pid && isAlive(owner)) {
    log(`state.json 归属 pid=${owner}（仍在运行）：本进程不收尾（避免误杀同目录的别路实例）`)
    return
  }
  const killed = new Set<number>()
  for (const [name, pid] of Object.entries(state.pids)) {
    log(`收尾 ${name} pid=${pid}`)
    killTree(pid)
    killed.add(pid)
  }
  // 本进程内存里还挂着的子进程（state 落盘前的极短窗口）也一并收掉。
  for (const [name, child] of Object.entries(children)) {
    if (child.pid && !killed.has(child.pid)) {
      log(`收尾 ${name} pid=${child.pid}（内存台账）`)
      killTree(child.pid)
    }
  }
  await sleep(1500)
  // 收尾阶段清库是 best-effort：库号错 / Redis 不可达时不能让 teardown 掩盖 setup 的原始报错。
  try {
    await flushRedisDb(REDIS_DB)
    // 只有本棒真起过降级面才清降级库（与 startServices 的清理条件对称）。
    if (state.pids.backend_degraded !== undefined) await flushRedisDb(DEGRADED_REDIS_DB)
  } catch (error) {
    log(`收尾清库失败（忽略，不覆盖 setup 的原始报错）：${error instanceof Error ? error.message : String(error)}`)
  }
  rmSync(STATE_FILE, { force: true })
}

/** 供用例读取/复用（例如断言端口纪律）。 */
export function harnessState(): RunState | null {
  return readState()
}
