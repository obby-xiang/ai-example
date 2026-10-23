/**
 * NFEZUI 域的**独占** E2E 基建（不共用 support/harness.ts 的运行目录与端口）。
 *
 * 为什么另起一份：`support/harness.ts` 的运行目录、状态文件、H2 数据目录都是进程内的固定常量，
 * 同族子智能体并行跑各自的 spec 时会互相删数据面、互相按 pid 收尾。本域用独立端口段
 * （19130/19131/19198/19199/19201/19202）与独立运行目录，做到「同一台机器上并行互不可见」。
 *
 * 形态：
 *   - 上游桩 A  scripts/ci/stub-upstream.mjs          19198（官方路由表，只读复用）
 *   - 上游桩 B  support/nfezui-upstream.mjs           19199（本域自建；未命中自定义标记时转 19198）
 *   - 后端 A    独立 H2 + Redis DB 13                  19130
 *   - 后端 B    无 key（降级面）+ Redis DB 14          19131
 *   - 前端 A    vite dev server（代理到后端 A）         19201
 *   - 前端 B    vite dev server（代理到后端 B）         19202
 *
 * 纪律：绝不占用常开的 8081 / 5202；端口被占即失败并给出可行动提示；日志与数据落
 * `frontend/test-results/.e2e-run-nfezui`（.gitignore 已排除）。
 */
import { spawn, spawnSync, type ChildProcess } from 'node:child_process'
import { closeSync, existsSync, mkdirSync, openSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import net from 'node:net'
import { delimiter, join, resolve } from 'node:path'
import { setTimeout as sleep } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'

const HERE = fileURLToPath(new URL('.', import.meta.url))
export const FRONTEND_DIR = resolve(HERE, '../..')
export const REPO_ROOT = resolve(FRONTEND_DIR, '..')
export const NFEZUI_RUN_DIR = join(FRONTEND_DIR, 'test-results', '.e2e-run-nfezui')
const STATE_FILE = join(NFEZUI_RUN_DIR, 'state.json')

export const NFEZUI_PORTS = {
  backend: 19130,
  degradedBackend: 19131,
  stub: 19199,
  upstream: 19198,
  app: 19201,
  degradedApp: 19202
} as const

export const NFEZUI_REDIS_DB = { main: 13, degraded: 14 } as const

export const NFEZUI_URLS = {
  api: `http://127.0.0.1:${NFEZUI_PORTS.backend}`,
  degradedApi: `http://127.0.0.1:${NFEZUI_PORTS.degradedBackend}`,
  app: `http://127.0.0.1:${NFEZUI_PORTS.app}`,
  degradedApp: `http://127.0.0.1:${NFEZUI_PORTS.degradedApp}`,
  stub: `http://127.0.0.1:${NFEZUI_PORTS.stub}`
}

function log(line: string): void {
  // eslint-disable-next-line no-console
  console.log(`[nfezui-harness] ${line}`)
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
  throw new Error(`端口 ${port}（${label}）已被占用：NFEZUI harness 需要独占端口段，harness 不会杀别人的进程。`)
}

/** 极简 RESP：SELECT + FLUSHDB（仅本域 DB，绝不碰别人的库）。 */
export function flushRedisDb(db: number): Promise<void> {
  const host = process.env.E2E_REDIS_HOST ?? '127.0.0.1'
  const port = Number(process.env.E2E_REDIS_PORT ?? '6379')
  return new Promise((done) => {
    const socket = net.connect(port, host)
    let buffer = ''
    let step = 0
    let finished = false
    const finish = (): void => {
      if (finished) return
      finished = true
      socket.destroy()
      done()
    }
    socket.setTimeout(3000, finish)
    socket.on('error', finish)
    socket.on('data', (chunk) => {
      buffer += chunk.toString('utf8')
      while (buffer.includes('\r\n')) {
        if (step === 0) {
          if (buffer.startsWith('+OK')) {
            buffer = buffer.slice(buffer.indexOf('\r\n') + 2)
            step = 1
            socket.write('*1\r\n$7\r\nFLUSHDB\r\n')
            continue
          }
          return
        }
        if (buffer.startsWith('+OK')) finish()
        return
      }
    })
    socket.on('connect', () => {
      socket.write(`*2\r\n$6\r\nSELECT\r\n$${String(db).length}\r\n${db}\r\n`)
    })
  })
}

/** 通用 Redis 命令（仅本域 DB；`SELECT` 由调用方先发）。 */
export function redisCommand(db: number, args: string[]): Promise<string> {
  const host = process.env.E2E_REDIS_HOST ?? '127.0.0.1'
  const port = Number(process.env.E2E_REDIS_PORT ?? '6379')
  return new Promise((done, fail) => {
    const socket = net.connect(port, host)
    let buffer = ''
    let selected = false
    let sent = false
    const timer = setTimeout(() => {
      socket.destroy()
      fail(new Error('redis 命令超时'))
    }, 5000)
    const finish = (): void => {
      clearTimeout(timer)
      socket.destroy()
      done(buffer)
    }
    socket.on('error', (error) => {
      clearTimeout(timer)
      fail(error)
    })
    socket.on('data', (chunk) => {
      buffer += chunk.toString('utf8')
      if (!selected && buffer.startsWith('+OK')) {
        selected = true
        buffer = ''
        const bulk = `*${args.length}\r\n${args.map((a) => `$${Buffer.byteLength(a)}\r\n${a}\r\n`).join('')}`
        socket.write(bulk)
        sent = true
        return
      }
      if (sent && buffer.length > 0 && (buffer.includes('\r\n') || buffer.startsWith('$-1'))) finish()
    })
    socket.on('connect', () => {
      socket.write(`*2\r\n$6\r\nSELECT\r\n$${String(db).length}\r\n${db}\r\n`)
    })
  })
}

// ── Java classpath ──────────────────────────────────────────────────────────

const MAIN_CLASS = 'com.example.configmgr.ConfigMgrApplication'

function javaHome(): string | undefined {
  if (process.env.JAVA_HOME) return process.env.JAVA_HOME
  const probe = spawnSync('java', ['-XshowSettings:properties', '-version'], { encoding: 'utf8' })
  const text = `${probe.stdout ?? ''}${probe.stderr ?? ''}`
  const match = /^\s*java\.home\s*=\s*(.+)$/m.exec(text)
  return match?.[1]?.trim() || undefined
}

function javaBin(): string {
  const home = javaHome()
  return home ? join(home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java'
}

function jarListIn(dir: string): string[] {
  if (!existsSync(dir)) return []
  return readdirSync(dir)
    .filter((name) => name.endsWith('.jar'))
    .sort()
    .map((name) => join(dir, name))
}

function resolveDependencyClasspath(): string {
  if (process.env.E2E_BACKEND_CLASSPATH) return process.env.E2E_BACKEND_CLASSPATH
  const cpFile = join(REPO_ROOT, 'backend', 'target', 'e2e-classpath.txt')
  if (existsSync(cpFile)) {
    const text = readFileSync(cpFile, 'utf8').trim()
    if (text) return text
  }
  const jar = join(REPO_ROOT, 'backend', 'target', 'config-mgr.jar')
  const outDir = join(NFEZUI_RUN_DIR, 'jar')
  const libDir = join(outDir, 'BOOT-INF', 'lib')
  const cached = jarListIn(libDir)
  if (cached.length > 0) return cached.join(delimiter)
  if (!existsSync(jar)) {
    throw new Error('缺少依赖 classpath：backend/target/classes 与 config-mgr.jar 都不存在，请先在 backend 执行 mvn -q -DskipTests package')
  }
  mkdirSync(outDir, { recursive: true })
  const home = javaHome()
  const jarTool = home ? join(home, 'bin', process.platform === 'win32' ? 'jar.exe' : 'jar') : 'jar'
  const result = spawnSync(jarTool, ['--extract', `--file=${jar}`, 'BOOT-INF/lib'], { cwd: outDir, encoding: 'utf8' })
  if (result.status !== 0) {
    throw new Error(`从胖包解依赖失败（jar --extract）：${result.stderr || result.stdout || result.error?.message || '未知错误'}`)
  }
  return jarListIn(libDir).join(delimiter)
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
    `--spring.datasource.url=jdbc:h2:file:./${dataDir}/e2e;DB_CLOSE_DELAY=-1`,
    `--spring.data.redis.database=${redisDb}`,
    '--app.job.batch-size=10',
    '--app.job.demo-batch-delay-ms=150',
    '--app.ai.limit.session-per-minute=400',
    '--app.ai.limit.ip-per-minute=4000',
    ...extra
  ]
}

// ── 进程 ────────────────────────────────────────────────────────────────────

const children: Record<string, ChildProcess> = {}

function spawnLogged(name: string, cmd: string, args: string[], options: { cwd: string; env?: NodeJS.ProcessEnv }): ChildProcess {
  const logPath = join(NFEZUI_RUN_DIR, `${name}.log`)
  const fd = openSync(logPath, 'a')
  const child = spawn(cmd, args, {
    cwd: options.cwd,
    env: options.env ?? process.env,
    stdio: ['ignore', fd, fd],
    windowsHide: true,
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
  timeoutMs = 150_000
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

export async function startNfezuiServices(): Promise<void> {
  mkdirSync(NFEZUI_RUN_DIR, { recursive: true })
  for (const [port, label] of [
    [NFEZUI_PORTS.backend, '后端 A'],
    [NFEZUI_PORTS.degradedBackend, '后端 B（降级面）'],
    [NFEZUI_PORTS.stub, '本域上游桩'],
    [NFEZUI_PORTS.upstream, '官方上游桩'],
    [NFEZUI_PORTS.app, '前端 A'],
    [NFEZUI_PORTS.degradedApp, '前端 B（降级面）']
  ] as Array<[number, string]>) {
    await assertPortFree(port, label)
  }

  for (const dir of ['data', 'data-degraded', 'stub', 'upstream']) {
    rmSync(join(NFEZUI_RUN_DIR, dir), { recursive: true, force: true })
  }
  await flushRedisDb(NFEZUI_REDIS_DB.main)
  await flushRedisDb(NFEZUI_REDIS_DB.degraded)

  const pids: Record<string, number> = {}
  // ownerPid = 本次运行的主进程：并发起第二棒时，第二棒 setup 失败**不得**按别人的 pid 收尾
  // （实测过：另一棒的 state.json 被读走 ⇒ 把在跑的 vite/后端一并 kill）。
  const persist = (): void => {
    writeFileSync(STATE_FILE, JSON.stringify({ startedAt: Date.now(), ownerPid: process.pid, pids, ports: NFEZUI_PORTS }, null, 2))
  }

  // ① 官方桩（本域自建桩未命中自定义标记时的转发目标）
  for (const dir of ['upstream', 'stub']) mkdirSync(join(NFEZUI_RUN_DIR, dir), { recursive: true })
  const upstream = spawnLogged('upstream', process.execPath, [
    join(REPO_ROOT, 'scripts', 'ci', 'stub-upstream.mjs'),
    '--port', String(NFEZUI_PORTS.upstream),
    '--dir', join(NFEZUI_RUN_DIR, 'upstream')
  ], { cwd: REPO_ROOT })
  if (upstream.pid) pids.upstream = upstream.pid
  persist()
  await waitForHttp(`http://127.0.0.1:${NFEZUI_PORTS.upstream}/probe`, '官方上游桩', () => true, 60_000)

  // ② 本域桩（list_tasks / 错误形态）；未命中标记的请求原样转发官方桩
  const stub = spawnLogged('stub', process.execPath, [
    join(FRONTEND_DIR, 'e2e', 'support', 'nfezui-upstream.mjs'),
    '--port', String(NFEZUI_PORTS.stub),
    '--upstream', `http://127.0.0.1:${NFEZUI_PORTS.upstream}`
  ], { cwd: REPO_ROOT })
  if (stub.pid) pids.stub = stub.pid
  persist()
  await waitForHttp(`${NFEZUI_URLS.stub}/probe`, '本域上游桩', () => true, 30_000)

  // ③ 后端 A（有 key、指本域桩）
  const envA: NodeJS.ProcessEnv = {
    ...process.env,
    AI_API_KEY: 'e2e-stub-placeholder',
    AI_BASE_URL: NFEZUI_URLS.stub,
    AI_MODEL: 'e2e-stub-model'
  }
  const backendA = spawnLogged('backend', javaBin(), javaArgs(NFEZUI_PORTS.backend, NFEZUI_REDIS_DB.main, 'data', [
    '--app.ai.sheet.write-enabled=true',
    '--app.ai.resume.on-startup=false',
    // E-08 的池饱和面：并发轮次池压到 3（第 4 轮并发即 503 SUSPEND_POOL_SATURATED）
    '--app.ai.suspend.pool-size=3'
  ]), { cwd: NFEZUI_RUN_DIR, env: envA })
  if (backendA.pid) pids.backend = backendA.pid
  persist()

  // ④ 后端 B（无 key ⇒ 降级面）
  const backendB = spawnLogged('backend-degraded', javaBin(), javaArgs(
    NFEZUI_PORTS.degradedBackend,
    NFEZUI_REDIS_DB.degraded,
    'data-degraded',
    []
  ), { cwd: NFEZUI_RUN_DIR, env: envWithoutAi(process.env) })
  if (backendB.pid) pids.backend_degraded = backendB.pid
  persist()

  await waitForHttp(`${NFEZUI_URLS.api}/api/ai/health`, '后端 A（available + redis.available）', (body) => {
    try {
      const json = JSON.parse(body) as { available?: boolean; redis?: { available?: boolean } }
      return json.available === true && json.redis?.available === true
    } catch {
      return false
    }
  })
  await waitForHttp(`${NFEZUI_URLS.api}/api/definitions`, '后端 A 种子', (body) => {
    try {
      const json = JSON.parse(body) as { data?: unknown[] }
      return Array.isArray(json.data) && json.data.length >= 15
    } catch {
      return false
    }
  }, 120_000)

  await waitForHttp(`${NFEZUI_URLS.degradedApi}/api/ai/health`, '后端 B（降级面）', (body, status) => {
    if (status !== 503) return false
    try {
      return (JSON.parse(body) as { available?: boolean }).available === false
    } catch {
      return false
    }
  })

  const startApp = async (name: string, port: number, apiBase: string): Promise<void> => {
    const viteBin = join(FRONTEND_DIR, 'node_modules', 'vite', 'bin', 'vite.js')
    if (!existsSync(viteBin)) throw new Error('缺少 frontend/node_modules/vite：请先 yarn install')
    const child = spawnLogged(name, process.execPath, [viteBin, '--port', String(port), '--strictPort', '--host', '127.0.0.1'], {
      cwd: FRONTEND_DIR,
      env: { ...process.env, VITE_API_BASE: apiBase, VITE_DEV_PORT: String(port) }
    })
    if (child.pid) pids[name === 'app' ? 'app' : 'app_degraded'] = child.pid
    persist()
  }

  await startApp('app', NFEZUI_PORTS.app, NFEZUI_URLS.api)
  await startApp('app-degraded', NFEZUI_PORTS.degradedApp, NFEZUI_URLS.degradedApi)
  await waitForHttp(`${NFEZUI_URLS.app}/`, '前端 A', () => true, 120_000)
  await waitForHttp(`${NFEZUI_URLS.degradedApp}/`, '前端 B（降级面）', () => true, 120_000)

  persist()
  log(`就绪：api=${NFEZUI_URLS.api} app=${NFEZUI_URLS.app} degraded=${NFEZUI_URLS.degradedApi}/${NFEZUI_URLS.degradedApp}`)
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

export async function stopNfezuiServices(): Promise<void> {
  for (const child of Object.values(children)) {
    if (child.pid) killTree(child.pid)
  }
  if (existsSync(STATE_FILE)) {
    try {
      const state = JSON.parse(readFileSync(STATE_FILE, 'utf8')) as { ownerPid?: number; pids?: Record<string, number> }
      // 只收自己起的进程：别人的运行（ownerPid 不同）一律不动
      if (state.ownerPid === undefined || state.ownerPid === process.pid) {
        for (const pid of Object.values(state.pids ?? {})) killTree(pid)
        rmSync(STATE_FILE, { force: true })
      }
    } catch {
      /* 状态文件损坏不影响收尾 */
    }
  }
  await sleep(1500)
  await flushRedisDb(NFEZUI_REDIS_DB.main)
  await flushRedisDb(NFEZUI_REDIS_DB.degraded)
}
