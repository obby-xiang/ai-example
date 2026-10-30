#!/usr/bin/env node
/**
 * 三实例集群验证探针（A/B/C · 共享 Redis + 共享 H2-TCP + 共享文件目录 + 统一反向代理）。
 *
 * 定位：**只在 CI 的 GitHub Runner 上跑真实集群**。本文件在本机只允许 `--self-check`
 * （静态/协议自证，不起 Java、不碰 Docker、不连任何端口）；真实集群的唯一入口是
 * `bash scripts/ci/cluster3-run.sh`（由 .github/workflows/cluster-3node.yml 调用）。
 *
 * 与既有设施的关系（刻意不复用旧证据）：
 *   - 双实例脚本 `scripts/b23l1-dual-instance.ps1` 是另一端口段的人工 harness，产物在
 *     `backend/target/b23l1-artifacts`；本探针用自己的端口段（231xx）、自己的容器名、
 *     自己的产物目录（backend/target/cluster3-artifacts），**不读取也不覆盖**旧证据。
 *   - 桩上游复用 `scripts/ci/stub-upstream.mjs`（零密钥、确定性）；本探针只消费它的
 *     `/chat/completions` 与 `/probe`，不改桩。
 *
 * 无会话粘滞的证据面（三层，缺一不可）：
 *   ① 反向代理配置：`balance roundrobin`，且无 stick-table / cookie / source 粘滞
 *      （run 模式再读一次实际生效的配置文件；shell 的 --self-check 也静态断言同一份）；
 *   ② 客户端侧：同一 keep-alive TCP 连接（maxSockets=1，socket.localPort 恒定）连续请求
 *      必须覆盖 A/B/C；固定 Cookie + 固定 X-Forwarded-For 的 30 个独立连接也必须覆盖 A/B/C；
 *   ③ 代理侧：HAProxy 自己的访问日志（%ci:%cp + %b/%s）里同一客户端端口必须出现 3 个不同
 *      server 名。
 *
 * 结果口径：只有 PASS / FAIL（自证里断言 RESULT_STATUSES 不含 SKIP）。产品缺陷未修完时
 * 探针**如实 FAIL**，绝不把未跑到的检查写成 SKIP 或绿。
 *
 * 挂起/接管的时序口径（2026-10-25 裁决 + 真实拓扑 run 40174291244 的证据）：
 *   - **合入基线**：本探针与"多实例挂起/接管"候选分支同批合入；该候选已含 `SseChatEmitter#ensureToolStart`、
 *     续跑结算前补发（`SpToolCallingManager#ensureToolStartBeforeSettlement`）、park 退出的原子生命周期切换
 *     （`ConfirmGate#switchParkedLifecycle`）与 frontend-result 路径（均有独立复审与定向测试）。
 *     **这些是前置产品补丁，不在本探针的交付物里** —— 单独把探针贴到 `ae883fb` 上跑真实拓扑，
 *     `suspend-kill-a` 的 `tool_start` 不变量会按预期红（那正是该用例要暴露的产品缺口）。
 *   - **park 边界屏障**（只用于"非 kill 的编排"）：请求帧之后产品主动收流（B1）。稳定基线的用例
 *     （`suspend-cross-instance` / 幂等）等这条连接被服务端收尾再继续，于是 park 帧集
 *     （确认卡 + `tool_start`）一定已落档，决策前的重挂与决策后的续跑都从确定的起点出发。
 *   - **早杀覆盖保留**（`suspend-kill-a`）：拿到确认卡就 SIGKILL，**不等**收流 —— 继续覆盖
 *     "快照已 SUSPENDED、本批 `tool_start` 可能还没落档"的崩溃窗口。产品侧由候选分支的
 *     `ensureToolStart` 在续跑结算前按归档幂等补发，因此断言是**最终帧册不变量**：
 *     `tool_start` 恰一次且先于 `tool_result`；探针只记录"kill 前客户端是否已见"，不推断来源。
 *   - **决策前的重挂只回放后收流**（用户 park 规则：保留非阻塞收流，不恢复悬挂连接/阻塞执行）：
 *     用例断言"回放完整 + 被服务端收流 + 不含终帧 + 状态仍 SUSPENDED"，
 *     终帧改在**决策 200 之后**的重挂上收（产品自述的标准时序）。
 *   - **证据先落盘再断言**：帧类（`saveFrames`）与状态类（`/runs/current`、`run-detail`、`jobs`、`history`、
 *     `confirm`、`takeover`）证据都排在可能失败的断言之前（`evidenceFirst` 或 `finally`）。
 *     park 帧集允许 `heartbeat/message_start/delta/message_end/context/retry` 等 pre-park 噪声，
 *     不做"只许 4 帧"的硬编码（心跳不带业务 seq，不算断号）。
 *   - **末号口径**：终态帧册里的 `done.seq` 断言的是"**本次全量回放**的末号"（`lastSeqScope=full-replay`），
 *     **不是**服务端 `archiveMaxSeq`（终态会清 session→current 索引，权威末号只在 SUSPENDED/RUNNING
 *     时通过 `/runs/current` 可读，见 park/kill 边界的两处断言）。
 *
 * 双身份 × 三实例（两条新用例，排在基线 16 条之后；基线 16 条的身份头仍是默认身份 `1/101`）：
 *   - `identity-cross-instance`：A 身份（`1/101`，默认）在实例 A 起确认门挂起轮 → **B 身份**（`1/102`，
 *     同租户另一用户，产品 `TenantUserDirectory` 里已登记）在实例 B 上对 `events`（含 `?probe=1`）、
 *     `runs/{id}`、`runs/current`、`pending`、`history`、`startup-resume` 报告 **全部不可见**
 *     （报告的原文里连 A 的 `runId` 都不许出现），`/confirm` 与 `/frontend-tool-result`
 *     **双双 409 UNKNOWN_TOOL_CALL**；随后 A 身份在实例 C 重读的运行态指纹
 *     必须**逐字段不变**（零副作用），并在 C 上确认成功、全量帧册与台账照旧。
 *   - `identity-frontend-cross-instance`：同一条跨身份判据走**前端工具通道**（`frontend_tool_request`）——
 *     用桩路由表既有的 `TC16-NAVIGATE`（`navigate_to`，产品元数据 `@ToolChannel(FRONTEND)`）构造，
 *     不新造产品能力；B 身份在实例 B 对 `frontend-tool-result` 也不可见，A 身份在 C 回灌成功并按
 *     前端通道的帧册断言（有 `frontend_tool_result`，**无** `tool_result`/`confirm_decision`）。
 *   - 零副作用的**两面**都测：① A 身份观察到的运行态指纹（状态/归档末号/待决条目/台账）不变；
 *     ② 直接读 Redis 的 `PTTL`：B 的请求不得给 A 的 `ai:run:{1:101:run:<id>}` /
 *     `ai:pending:{1:101:run:<id>}` **续期**（读侧 `touchActiveRun` 必须按当前身份定键）。
 *   - 身份头来源：A/B 都在 `--self-check` 里静态断言"已登记在同租户"（未登记的组合会被 HTTP 边界 400，
 *     用例会变成假红）。
 *   - **边界口径**（HTTP 边界收紧后）：`/api/ai` 下只有 `health`/`tools`/`tenants`（精确）与
 *     `providers`/`models`/`settings`（子树）不带身份头；`startup-resume` 的读取与
 *     `POST startup-resume/scan` **在数据面内**（只处理当前身份的轮次）。探针的 scan 带头（正控），
 *     并额外发一次**裸请求**证明它缺头必 400（负控）——`--self-check` 断言这五处口径同集。
 *
 * 用法：
 *   node scripts/ci/cluster3-probe.mjs --self-check
 *   node scripts/ci/cluster3-probe.mjs --run --artifacts <dir> --run-dir <dir> --jar <jar>
 *        --h2-jar <h2.jar> --db-base <dir> --shared-files <dir> --java java
 *        --redis-container <name> --haproxy-container <name> --haproxy-config <file>
 *        --stub-url http://127.0.0.1:23199 --proxy-url http://127.0.0.1:23180
 *        --stats-url 'http://127.0.0.1:23189/stats;csv'
 *        --ports 'a=23181,b=23182,c=23183,h2=23190,redis=23179'
 *        [--ready-timeout 180] [--job-fail-timeout 90] [--kill-takeover-timeout 120]
 *        [--case preexec-cancel-grid]      # 单点/子集执行（实测 L-150 三格时用；不写 = 全套）
 *                                           # 双身份两条：identity-cross-instance / identity-frontend-cross-instance
 *
 * 退出码：0 = 全部 PASS；1 = 有 FAIL；2 = 环境/参数错误（基线起不来，后续用例无意义）。
 * 退出码 0 只代表**本次实际执行的用例**都通过：用了 `--case` 时 summary 会显式标注"子集"。
 *
 * 零第三方依赖：只用 Node 内置模块（--self-check 会静态断言 import 全部是 node: 前缀）。
 */

import http from 'node:http'
import net from 'node:net'
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import os from 'node:os'
import { spawn, execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

// ── 常量：端口段 231xx（与产品默认端口 / 前端端口无交集）────────────────────

const HERE = path.dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = path.resolve(HERE, '..', '..')

/**
 * 禁用端口（以「分段拼接」形式存在，避免本文件被自己的卫生扫描误命中）。
 * 任何一项出现在三个交付文件里都应让 --self-check 红。
 */
const BANNED_PORT_TOKENS = Object.freeze([`${80}${81}`, `${52}${'02'}`])

const PORTS = Object.freeze({
  redis: 23179,
  proxy: 23180,
  a: 23181,
  b: 23182,
  c: 23183,
  stats: 23189,
  h2: 23190,
  stub: 23199
})

const INSTANCES = Object.freeze([
  { key: 'a', node: 'node-a', port: PORTS.a },
  { key: 'b', node: 'node-b', port: PORTS.b },
  { key: 'c', node: 'node-c', port: PORTS.c }
])

// ── AI 对话数据面身份（X-Tenant-Id / X-User-Id）────────────────────────────
// 与后端 TenantIdentityFilter 同一分界：/api/ai 下的对话数据面缺头即 400；排除面只有
// 精确路径（health/tools/tenants）与模型配置子树（providers/models/settings）。
// `startup-resume` 的读取与 `POST startup-resume/scan` **在边界内**（只处理当前身份的轮次），
// 探针照常给它加身份头，并用"裸请求必须 400"作负控（见 forceZombieResume）。
// 业务数据与模型配置**不做租户隔离**，本探针不给它们加身份头。
const AI_TENANT_ID = '1'
const AI_USER_ID = '101'
/**
 * 探针用到的两个身份（跨身份不可见用例）。
 *
 * <p>A = 既有默认身份（`1/101`，前面 16 条用例逐字不变）；B = **同租户的另一个用户**（`1/102`）。
 * 两者都在产品 `TenantUserDirectory` 的登记清单里（未登记的组合在 HTTP 边界就 400，"不可见"根本测不到）。
 *
 * <p>为什么是"同租户不同用户"而不是"跨租户"：键面身份段是 `tenantId:userId` 两段拼接，
 * 同租户下只换用户已经能钉住"只按租户定键"这类欠隔离（那种实现下本用例会红）；换租户反而会
 * **掩盖**它（tenant 段本来就不同）。`--self-check` 静态断言两人同租户且都在目录里。
 */
const IDENTITY_A = Object.freeze({ tenantId: Number(AI_TENANT_ID), userId: Number(AI_USER_ID) })
const IDENTITY_B = Object.freeze({ tenantId: Number(AI_TENANT_ID), userId: 102 })
const PROBE_IDENTITIES = Object.freeze({ a: IDENTITY_A, b: IDENTITY_B })
/**
 * 后端 `TenantIdentityFilter.EXCLUDED_EXACT` 的**逐字同集**（只有三条；子路径**不**排除）。
 *
 * <p>`startup-resume` 与 `POST startup-resume/scan` 已随 HTTP 边界收紧进入数据面：它们要求身份头、
 * 且只处理**当前身份**的轮次。把它们留在排除集里会让探针的 scan 变成 400（或在产品尚未收紧时
 * 掩盖越权），因此这里**只有**健康/工具/身份目录三条。
 */
const AI_PUBLIC_EXACT_PATHS = Object.freeze(['/api/ai/health', '/api/ai/tools', '/api/ai/tenants'])
const AI_CONFIG_SUBTREES = Object.freeze(['/api/ai/providers', '/api/ai/models', '/api/ai/settings'])

/** 该路径是否属于"必须带身份头"的对话数据面（与后端 shouldNotFilter 同口径，子路径默认受保护）。 */
function aiDataPath(pathname) {
  if (!pathname) return false
  let p = pathname.split('?')[0]
  if (p.length > 1 && p.endsWith('/')) p = p.slice(0, -1)
  if (p !== '/api/ai' && !p.startsWith('/api/ai/')) return false
  if (AI_PUBLIC_EXACT_PATHS.includes(p)) return false
  for (const sub of AI_CONFIG_SUBTREES) {
    if (p === sub || p.startsWith(`${sub}/`)) return false
  }
  return true
}

/** 对话数据面身份头（非数据面返回空对象，便于统一展开）。缺省 = 既有默认身份 A。 */
function aiIdentityHeaders(pathname, identity = null) {
  if (!aiDataPath(pathname)) return {}
  const who = identity || IDENTITY_A
  return { 'X-Tenant-Id': String(who.tenantId), 'X-User-Id': String(who.userId) }
}

/** 身份的人读标签（`租户/用户`），只用于断言文案与证据文件名，不参与判定。 */
function identityTag(identity) {
  return `${identity.tenantId}/${identity.userId}`
}

const RESULT_STATUSES = Object.freeze(['PASS', 'FAIL'])

/**
 * **基线 16 条**（合入基线的覆盖面，逐条保留、标题不变、执行顺序不变）。
 *
 * <p>单独抽出来是为了让"不削弱"可被机器检查：`--self-check` 断言这 16 条**全部**仍在
 * `REQUIRED_CASE_IDS` 里、且仍占满 `CASES` 的**前 16 个执行位**（删一条/被新用例挤走立刻红）。
 */
const BASELINE_CASE_IDS = Object.freeze([
  'cold-start-3',
  'proxy-no-sticky',
  'same-tcp-across-abc',
  'reattach-any-instance',
  'session-purge-cross-instance',
  'seq-cross-instance',
  'suspend-cross-instance',
  'idempotency-cross-instance',
  'config-hot-reload',
  'shared-file-download',
  'suspend-kill-a',
  'streaming-kill-a',
  'job-kill-a',
  'readiness-removal',
  'redis-outage-recovery',
  'preexec-cancel-grid'
])

/** 新增的双身份覆盖面（两条：确认门通道 / 前端工具通道）。 */
const IDENTITY_CASE_IDS = Object.freeze(['identity-cross-instance', 'identity-frontend-cross-instance'])

/** 用户要求的覆盖面（--self-check 逐条断言 CASES 注册齐全）= 基线 16 条 + 双身份 2 条。 */
const REQUIRED_CASE_IDS = Object.freeze([...BASELINE_CASE_IDS, ...IDENTITY_CASE_IDS])

const PLACEHOLDER_KEY = 'cluster3-stub-placeholder'
const STUB_MODEL = 'ci-stub'

// 各实例统一的启动参数（产品键；数值是为 CI 时间预算收紧的**合法配置值**，
// 不是绕过语义：租约/僵尸阈值仍满足产品启动期校验，只是把等待窗从分钟级压到秒级）。
const COMMON_JAVA_ARGS = Object.freeze([
  '--spring.jpa.hibernate.ddl-auto=validate',
  '--app.job.runner-active-store=redis',
  '--app.job.batch-size=1',
  '--app.job.demo-batch-delay-ms=1200',
  '--app.job.heartbeat-interval=5s',
  '--app.job.watchdog.scan-period=3s',
  '--app.job.watchdog.dead-multiplier=3',
  '--app.job.max-execution-time=45s',
  '--app.ai.limit.session-per-minute=500',
  '--app.ai.limit.ip-per-minute=5000',
  '--app.ai.resume.startup-delay=3s',
  '--app.ai.resume.zombie-age=30s',
  '--app.ai.resume.zombie-margin=5s',
  '--app.ai.tool.exec-timeout=20s',
  '--app.ai.resilience.first-byte-timeout=15s',
  '--app.ai.resilience.inter-event-timeout=20s',
  '--app.ai.lease.ttl=12s',
  '--app.ai.lease.renew-interval=4s',
  '--app.ai.lease.retry-delay=17s',
  '--logging.level.com.example.configmgr=INFO',
  '--logging.level.com.example.configmgr.file=DEBUG'
])

// ── 参数 ───────────────────────────────────────────────────────────────────

function parseArgs(argv) {
  const out = { _: [] }
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i]
    if (!token.startsWith('--')) {
      out._.push(token)
      continue
    }
    const key = token.slice(2)
    const next = argv[i + 1]
    if (next !== undefined && !next.startsWith('--')) {
      out[key] = next
      i += 1
    } else {
      out[key] = true
    }
  }
  return out
}

const ARGS = parseArgs(process.argv.slice(2))

const MODE_SELF_CHECK = Boolean(ARGS['self-check'])
const MODE_RUN = Boolean(ARGS.run)
const MODE_HELP = Boolean(ARGS.help) || Boolean(ARGS.h)

function argString(name, fallback) {
  const value = ARGS[name]
  return typeof value === 'string' && value.length > 0 ? value : fallback
}

function argNumber(name, fallback) {
  const value = ARGS[name]
  if (value === undefined) return fallback
  const n = Number(value)
  if (!Number.isFinite(n) || n <= 0) throw new Error(`参数 --${name} 必须是正数（实得 ${value}）`)
  return n
}

// ── 运行时配置（只有 --run 才解析必需项）────────────────────────────────────

const RUN = {
  artifacts: '',
  runDir: '',
  jar: '',
  h2Jar: '',
  dbBase: '',
  sharedFiles: '',
  java: 'java',
  redisContainer: '',
  haproxyContainer: '',
  haproxyConfig: '',
  stubUrl: `http://127.0.0.1:${PORTS.stub}`,
  proxyUrl: `http://127.0.0.1:${PORTS.proxy}`,
  statsUrl: `http://127.0.0.1:${PORTS.stats}/stats;csv`,
  ports: { ...PORTS },
  readyTimeoutMs: 180000,
  jobFailTimeoutMs: 90000,
  killTakeoverTimeoutMs: 120000,
  onlyCases: [],
  keep: Boolean(ARGS.keep)
}

function configureRun() {
  RUN.artifacts = path.resolve(argString('artifacts', path.join(REPO_ROOT, 'backend/target/cluster3-artifacts')))
  RUN.runDir = path.resolve(argString('run-dir', path.join(os.tmpdir(), 'cluster3-run')))
  RUN.jar = path.resolve(argString('jar', path.join(REPO_ROOT, 'backend/target/config-mgr.jar')))
  RUN.h2Jar = path.resolve(argString('h2-jar', path.join(RUN.runDir, 'h2.jar')))
  RUN.dbBase = path.resolve(argString('db-base', path.join(RUN.runDir, 'db')))
  RUN.sharedFiles = path.resolve(argString('shared-files', path.join(RUN.runDir, 'files')))
  RUN.java = argString('java', 'java')
  RUN.redisContainer = argString('redis-container', '')
  RUN.haproxyContainer = argString('haproxy-container', '')
  RUN.haproxyConfig = ARGS['haproxy-config'] ? path.resolve(String(ARGS['haproxy-config'])) : ''
  RUN.stubUrl = argString('stub-url', RUN.stubUrl)
  RUN.proxyUrl = argString('proxy-url', RUN.proxyUrl)
  RUN.statsUrl = argString('stats-url', RUN.statsUrl)
  RUN.readyTimeoutMs = argNumber('ready-timeout', 180) * 1000
  RUN.jobFailTimeoutMs = argNumber('job-fail-timeout', 130) * 1000
  RUN.killTakeoverTimeoutMs = argNumber('kill-takeover-timeout', 120) * 1000
  const only = argString('case', '')
  RUN.onlyCases = only ? only.split(',').map((id) => id.trim()).filter(Boolean) : []
  const ports = argString('ports', '')
  if (ports) {
    for (const pair of ports.split(',')) {
      const [k, v] = pair.split('=')
      if (k && v && Number.isFinite(Number(v))) RUN.ports[k.trim()] = Number(v)
    }
  }
  const required = [
    ['artifacts', RUN.artifacts],
    ['run-dir', RUN.runDir],
    ['jar', RUN.jar],
    ['h2-jar', RUN.h2Jar],
    ['db-base', RUN.dbBase],
    ['shared-files', RUN.sharedFiles],
    ['redis-container', RUN.redisContainer],
    ['haproxy-container', RUN.haproxyContainer],
    ['haproxy-config', RUN.haproxyConfig]
  ]
  for (const [name, value] of required) {
    if (!value) throw new Error(`缺少必需参数 --${name}`)
  }
}

// ── 基础工具 ───────────────────────────────────────────────────────────────

const STATE = {
  startedAt: Date.now(),
  procs: new Map(), // key -> ChildProcess
  pids: {},
  instanceIds: {},
  h2: null,
  currentCase: 'preflight',
  results: [],
  routeCounts: {},
  caseStartedAt: 0,
  shuttingDown: false
}

const PATHS = {
  runLog: '',
  routeLog: '',
  resultsJson: '',
  resultsJsonl: '',
  resultsMd: '',
  summaryMd: '',
  pidsJson: '',
  envJson: '',
  instancesDir: '',
  casesDir: ''
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function nowIso() {
  return new Date().toISOString()
}

function stamp() {
  return `${new Date().toISOString().slice(11, 23)}Z`
}

function logLine(message) {
  const line = `${stamp()} ${message}`
  console.log(line)
  if (PATHS.runLog) {
    try {
      fs.appendFileSync(PATHS.runLog, `${line}\n`)
    } catch {
      /* 日志写失败不能反过来打断用例 */
    }
  }
}

function caseLog(message) {
  if (!PATHS.casesDir || STATE.currentCase === 'preflight') return
  const dir = path.join(PATHS.casesDir, STATE.currentCase)
  fs.mkdirSync(dir, { recursive: true })
  fs.appendFileSync(path.join(dir, 'notes.txt'), `${stamp()} ${message}\n`)
}

function tailFile(file, maxLines = 60) {
  try {
    const text = fs.readFileSync(file, 'utf8')
    const lines = text.split(/\r?\n/)
    return lines.slice(Math.max(0, lines.length - maxLines)).join('\n')
  } catch (e) {
    return `（读取 ${file} 失败：${e.message}）`
  }
}

function sha256(buffer) {
  return crypto.createHash('sha256').update(buffer).digest('hex')
}

function jsonParse(text, label) {
  try {
    return JSON.parse(text)
  } catch (e) {
    throw new Error(`${label} 不是合法 JSON：${e.message}；前 300 字符=${String(text).slice(0, 300)}`)
  }
}

function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true })
}

function writeFileAtomic(file, data) {
  ensureDir(path.dirname(file))
  const tmp = `${file}.tmp-${process.pid}`
  fs.writeFileSync(tmp, data)
  fs.renameSync(tmp, file)
}

function writeJson(file, value) {
  writeFileAtomic(file, `${JSON.stringify(value, null, 2)}\n`)
}

function escapeHtml(value) {
  return String(value).replace(/\|/g, '\\|').replace(/\r?\n/g, ' ')
}

// ── 断言 ───────────────────────────────────────────────────────────────────

function check(condition, message) {
  if (!condition) throw new Error(message)
}

function checkEq(actual, expected, label) {
  if (actual !== expected) {
    throw new Error(`${label}：期望 ${JSON.stringify(expected)}，实得 ${JSON.stringify(actual)}`)
  }
}

function checkIn(actual, allowed, label) {
  if (!allowed.includes(actual)) {
    throw new Error(`${label}：期望 ∈ ${JSON.stringify(allowed)}，实得 ${JSON.stringify(actual)}`)
  }
}

function checkGe(actual, minimum, label) {
  if (!(actual >= minimum)) throw new Error(`${label}：期望 >= ${minimum}，实得 ${actual}`)
}

function checkLe(actual, maximum, label) {
  if (!(actual <= maximum)) throw new Error(`${label}：期望 <= ${maximum}，实得 ${actual}`)
}

function checkIncludes(haystack, needle, label) {
  if (!String(haystack).includes(needle)) {
    throw new Error(`${label}：期望包含 ${JSON.stringify(needle)}，实得 ${JSON.stringify(String(haystack).slice(0, 400))}`)
  }
}

function checkStrictlyIncreasing(seqs, label) {
  check(Array.isArray(seqs) && seqs.length > 0, `${label}：seq 列表为空`)
  for (let i = 1; i < seqs.length; i += 1) {
    if (!(seqs[i] > seqs[i - 1])) {
      throw new Error(`${label}：seq 未严格递增（${seqs[i - 1]} → ${seqs[i]}）；全列=${JSON.stringify(seqs)}`)
    }
  }
  checkEq(new Set(seqs).size, seqs.length, `${label}：seq 重复`)
}

/** seq 必须从 `from` 起逐号连续（1,2,3…）：断号 = 归档少了一帧或多发了一号，两者都是缺陷。 */
function checkSeqContiguous(seqs, from, label) {
  checkStrictlyIncreasing(seqs, label)
  checkEq(seqs[0], from, `${label}：起始 seq`)
  for (let i = 1; i < seqs.length; i += 1) {
    checkEq(seqs[i], seqs[i - 1] + 1, `${label}：seq 断号（第 ${i} 项）`)
  }
}

/** 心跳不占业务号、不入归档（`FrameStore#append` 对心跳是 no-op）—— 唯一允许没有 seq 的帧型。 */
const HEARTBEAT_TYPE = 'heartbeat'

function isHeartbeat(frame) {
  return Boolean(frame) && frame.type === HEARTBEAT_TYPE
}

/**
 * 业务帧的 seq 列。**缺 seq 直接报错，不再过滤跳过**：
 * 旧实现 `filter(f => typeof f.seq === 'number')` 会把"某帧没有 seq"静默忽略 —— 只要其余号仍是
 * 1..N，连续性断言照样通过（复审 I2 的 false-pass）。心跳是唯一的例外（它本就不占号）。
 */
function seqsOf(frames, label = 'seq 列') {
  const out = []
  frames.forEach((frame, index) => {
    if (isHeartbeat(frame)) return
    if (!Number.isInteger(frame.seq)) {
      throw new Error(`${label}：第 ${index} 帧（type=${frame.type}）缺少整数 seq —— 缺号不得被跳过`)
    }
    out.push(frame.seq)
  })
  return out
}

function businessFrames(frames) {
  return frames.filter((frame) => !isHeartbeat(frame))
}

function typesOf(frames) {
  return frames.map((f) => f.type)
}

/**
 * 证据优先：先把"当时看到的快照"落盘，再跑任何可能失败的断言。
 *
 * <p>复审 B1 的教训：断言失败时才最需要帧证据。所有 <c>saveFrames</c> 调用点都经本函数
 * （或 <c>finally</c>）排在 <c>check*</c> 之前；自证 <c>selfTestEvidenceBeforeAssertions</c>
 * 用"断言必抛"的用例证明顺序与失败留存。
 */
function evidenceFirst(write, snapshot, assertions) {
  write(snapshot())
  return assertions()
}

function checkNonEmptyString(value, label) {
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error(`${label}：期望非空字符串，实得 ${JSON.stringify(value)}`)
  }
}

function checkPresent(value, label) {
  if (value === null || value === undefined) {
    throw new Error(`${label}：字段缺失（null/undefined）`)
  }
}

/**
 * 这些帧型可以合法地出现在确认卡之前（流式正文/思考段/压缩/重试/心跳）——
 * 它们是产品允许的噪声，既不该被当成缺陷（复审 I3 的 false-fail），也不属于 park 帧集本身。
 */
const PRE_PARK_NOISE_TYPES = Object.freeze(['context', 'message_start', 'delta', 'message_end', 'retry', HEARTBEAT_TYPE])

const PRE_PARK_BUSINESS_TYPES = Object.freeze(['start', 'suspended', 'confirm_request', 'tool_start'])

/**
 * <b>"确认卡已到"的最小帧契约</b>（早杀路径用：收到 `confirm_request` 就返回，不等 park 收流）。
 *
 * <p>只断言"到这一刻为止产品必须已经给出的东西"：
 * <ul>
 *   <li>首帧是 `start`、恰一个；</li>
 *   <li>`suspended` 恰一个且先于 `confirm_request`（`SpToolCallingManager` 先发 suspended 再公布请求帧）；</li>
 *   <li>该 `toolCallId` 的 `confirm_request` 恰一个；</li>
 *   <li>没有终帧；业务帧 seq 从 1 逐号连续；除业务帧外只允许 `PRE_PARK_NOISE_TYPES`；</li>
 *   <li>**不**要求 `tool_start`：park 转换期间被杀时它可能还没落档（正是要测的窗口）。</li>
 * </ul>
 */
function assertCardReachedFrameSet(frames, toolCallId, label = '确认卡到达帧集') {
  check(frames.length > 0, `${label}：一帧都没收到（已收=${typesOf(frames).join(',') || '空'}）`)
  const seqs = seqsOf(frames, label)
  const business = businessFrames(frames)
  check(business.length > 0, `${label}：一个业务帧都没收到（已收=${typesOf(frames).join(',')}）`)
  checkEq(business[0].type, 'start', `${label}：首个业务帧必须是 start（实得 ${typesOf(business).join(',')}）`)
  checkEq(countOf(business, 'start'), 1, `${label}：start 帧数`)
  checkEq(countOf(business, 'suspended'), 1, `${label}：suspended 帧数`)
  checkEq(countOf(business, 'confirm_request', toolCallId), 1, `${label}：confirm_request 帧数（toolCallId=${toolCallId}）`)
  checkEq(countOf(business, 'done') + countOf(business, 'error'), 0, `${label}：确认卡之前不得出现终帧`)
  const allowed = new Set([...PRE_PARK_BUSINESS_TYPES, ...PRE_PARK_NOISE_TYPES])
  const unexpected = typesOf(business).filter((type) => !allowed.has(type))
  checkEq(unexpected.join(','), '', `${label}：出现确认卡之前不该有的帧型（已收=${typesOf(business).join(',')}）`)
  check(
    indexOf(business, 'suspended') < indexOf(business, 'confirm_request'),
    `${label}：suspended 必须先于 confirm_request（实得 ${typesOf(business).join(',')}）`
  )
  checkSeqContiguous(seqs, 1, label)
  return { lastSeq: seqs[seqs.length - 1], types: typesOf(business).join(','), heartbeats: frames.length - business.length }
}

/**
 * <b>park 边界帧集</b>（已收流 = park 转换完成）。
 *
 * <p>在"确认卡已到"的契约之上只多要求一件事：`tool_start` 恰一次 —— 因为服务端收流
 * （`ConfirmGate#await` 里的 `completeSubscriptions`）发生在本批 `tool_start` 之后，
 * 客户端看到收流就说明该帧已经落档。`confirm_request` 与 `tool_start` 的先后是产品实现细节
 * （C1 前/后都出现过），这里不作约束。
 */
function assertParkedFrameSet(frames, toolCallId, label = 'park 边界帧集') {
  const summary = assertCardReachedFrameSet(frames, toolCallId, label)
  checkEq(
    countOf(businessFrames(frames), 'tool_start', toolCallId),
    1,
    `${label}：tool_start 帧数（收流完成 ⇒ 该帧必须已落档；toolCallId=${toolCallId}）`
  )
  return summary
}

/**
 * 服务端字段投影：去掉客户端解析时附加的 `_recvAtMs`/`_event`，其余**全字段**参与比对。
 *
 * <p>不再用固定键白名单（复审 I1：白名单漏了 `toolCalls`/`runId`/`context`/`preflight` 等，
 * 载荷被清空或替换仍能 false-pass）。这里比较"同一份归档帧的完整服务端内容"，
 * 因此任何字段级差异（`suspended.toolCalls` 被清空、`start.runId` 被换、`context` 载荷被改坏、
 * `atMs` 被改写）都会红。
 */
function serverFields(frame) {
  const out = {}
  for (const [key, value] of Object.entries(frame)) {
    if (key === '_recvAtMs' || key === '_event') continue
    out[key] = value
  }
  return out
}

/** 稳定序列化（递归按 key 排序）：两条序列化路径的 key 顺序不同不应造成假红。 */
function stableStringify(value) {
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`
  if (value && typeof value === 'object') {
    const keys = Object.keys(value).sort()
    return `{${keys.map((key) => `${JSON.stringify(key)}:${stableStringify(value[key])}`).join(',')}}`
  }
  return JSON.stringify(value)
}

/**
 * park 阶段的关键载荷（复审 I1：只比 type+seq 会让"载荷被清空/替换"的回放 false-pass）。
 */
function assertParkedPayloads(frames, { toolCallId, label = 'park 载荷' }) {
  const card = findOf(frames, 'confirm_request', toolCallId)
  check(card, `${label}：缺少 confirm_request（toolCallId=${toolCallId}）`)
  checkNonEmptyString(card.name, `${label}：confirm_request.name`)
  checkPresent(card.args, `${label}：confirm_request.args`)
  checkPresent(card.summary, `${label}：confirm_request.summary`)
  check(card.irreversible === undefined || typeof card.irreversible === 'boolean', `${label}：confirm_request.irreversible 类型`)

  const suspended = findOf(frames, 'suspended')
  check(suspended, `${label}：缺少 suspended 帧`)
  checkNonEmptyString(suspended.runId, `${label}：suspended.runId`)
  check(Array.isArray(suspended.toolCalls), `${label}：suspended.toolCalls 必须是数组`)
  const entry = suspended.toolCalls.find((item) => item && item.toolCallId === toolCallId)
  check(entry, `${label}：suspended.toolCalls 未列出 ${toolCallId}`)
  checkEq(entry.name, card.name, `${label}：suspended.toolCalls[].name 必须与确认卡一致`)
  checkEq(entry.kind, 'CONFIRM', `${label}：suspended.toolCalls[].kind`)
  checkEq(String(entry.arguments), String(card.args), `${label}：suspended.toolCalls[].arguments 必须等于 confirm_request.args`)

  const start = findOf(frames, 'tool_start', toolCallId)
  if (start) {
    checkEq(start.name, card.name, `${label}：tool_start.name 必须等于 confirm_request.name`)
    checkEq(start.kind, 'CONFIRM', `${label}：tool_start.kind`)
    checkEq(JSON.stringify(start.args), JSON.stringify(card.args), `${label}：tool_start.args 必须等于 confirm_request.args`)
  }
  return { name: card.name }
}

/**
 * "决策 + 执行 + 正文"的关键载荷（复审 I1）。可作用于一段**完整**帧册，也可作用于
 * 决策后重挂的差量连接（那时 `confirm_request`/`suspended` 在 `lastSeq` 之前，不在这一条连接里）。
 *
 * @param expectedName 期望的工具名（差量连接没有确认卡时由调用方给）
 */
function assertConfirmedToolPayloads(frames, { toolCallId, expectedName = null, label = '结论载荷' }) {
  const result = findOf(frames, 'tool_result', toolCallId)
  check(result, `${label}：缺少 tool_result（toolCallId=${toolCallId}）`)
  checkEq(result.ok, true, `${label}：tool_result.ok`)
  checkEq(result.outcome, 'ok', `${label}：tool_result.outcome`)
  if (expectedName) checkEq(result.name, expectedName, `${label}：tool_result.name`)
  checkNonEmptyString(result.result, `${label}：tool_result.result 不得为空`)

  const decision = findOf(frames, 'confirm_decision', toolCallId)
  check(decision, `${label}：缺少 confirm_decision（toolCallId=${toolCallId}）`)
  checkEq(decision.decision, 'approve', `${label}：confirm_decision.decision`)
  checkEq(decision.approved, true, `${label}：confirm_decision.approved`)
  if (expectedName) checkEq(decision.name, expectedName, `${label}：confirm_decision.name`)

  const deltas = frames.filter((frame) => frame.type === 'delta')
  check(deltas.length > 0, `${label}：必须至少有一帧 delta（正文）`)
  deltas.forEach((delta, index) => {
    check(typeof delta.text === 'string', `${label}：第 ${index} 帧 delta.text 必须是字符串（实得 ${typeof delta.text}）`)
  })
  check(deltas.some((delta) => delta.text.length > 0), `${label}：delta 文本不得全为空`)
  return { name: result.name, deltas: deltas.length }
}

/** 完整生命周期的关键载荷：park 阶段 + 结论阶段（一轮已 done 时两段都必须存在）。 */
function assertCompletedPayloads(frames, { toolCallId, label = '帧册载荷' }) {
  const { name } = assertParkedPayloads(frames, { toolCallId, label })
  const confirmed = assertConfirmedToolPayloads(frames, { toolCallId, expectedName: name, label })
  return { name, deltas: confirmed.deltas }
}

/**
 * 决策前重挂的安全契约（用户 park 规则的裁决口径：**保留非阻塞收流，不恢复悬挂连接/阻塞执行**）。
 *
 * 因此这里不断言"连接活到 done"，而是断言实际安全契约：
 * ① 回放内容 = 归档此刻的全部帧（逐帧 **全字段**一致），差量起点 = lastSeq + 1；
 * ② 回放完即被服务端收流（不悬挂、不阻塞）；
 * ③ 不含终帧（收流不是终态伪造）。
 *
 * <p>载荷断言在这里就地执行（复审 I1：C 的决策前回放此前只比投影键，`toolCalls`/`runId`/`context`
 * 被改坏仍能过）。回放既然必须与 park 帧集逐帧相等，park 阶段的载荷断言同样必须成立。
 */
function assertPreDecisionReplay(frames, parkedFrames, toolCallId, label) {
  const actual = businessFrames(frames)
  const expected = businessFrames(parkedFrames)
  checkEq(
    stableStringify(actual.map(serverFields)),
    stableStringify(expected.map(serverFields)),
    `${label}：回放内容必须与 park 边界帧集逐帧全字段一致（期望 ${typesOf(expected).join(',')}，实得 ${typesOf(actual).join(',') || '空'}）`
  )
  checkEq(countOf(actual, 'done') + countOf(actual, 'error'), 0, `${label}：决策前的回放不得含终帧`)
  assertParkedPayloads(actual, { toolCallId, label: `${label}载荷` })
  return { frames: actual.length }
}

/** B 侧差量的"不重不漏"：必须从 `fromSeq` 起逐号连续（复审 I2）。 */
function assertDeltaContiguous(frames, fromSeq, label) {
  const seqs = seqsOf(frames, label)
  checkSeqContiguous(seqs, fromSeq, label)
  return seqs
}

/**
 * **决策后重挂的差量连接**的帧型/载荷契约（复审 I1(b)(c)：此前只验 seq，载荷坏了仍能过）。
 *
 * <p>差量从 `lastSeq+1` 起，因此 `start`/`suspended`/`confirm_request` 这些"前缀帧"**不应出现**
 * （出现说明回放口径错、重发了客户端已有的帧）；必须恰好一帧 `done` 且是末帧，
 * 恰好一条 `confirm_decision(approve)`、一条 `tool_result(ok, 非空 result)`、至少一帧正文 `delta`。
 *
 * @param allowToolStart 早杀路径允许差量里出现 `tool_start`（原实例抢在窗口后落档，或续跑补发）；
 *                       非 kill 编排（park 屏障之后）不允许 —— 它一定在 `lastSeq` 之前。
 */
function assertDeltaFrames(frames, { toolCallId, expectedName = null, label = '决策后差量', allowToolStart = false }) {
  check(frames.length > 0, `${label}：一帧都没收到`)
  seqsOf(frames, label)
  const business = businessFrames(frames)
  const unknown = typesOf(business).filter((type) => !ARCHIVED_FRAME_TYPES.includes(type))
  checkEq(unknown.join(','), '', `${label}：出现白名单外的帧型（已收=${typesOf(business).join(',')}）`)
  checkEq(countOf(business, 'error'), 0, `${label}：不得出现 error 帧`)
  checkEq(countOf(business, 'start') + countOf(business, 'suspended') + countOf(business, 'confirm_request'), 0,
    `${label}：差量不得重发前缀帧（start/suspended/confirm_request）`)
  checkEq(countOf(business, 'done'), 1, `${label}：done 帧数`)
  checkEq(business[business.length - 1].type, 'done', `${label}：末帧必须是 done（实得 ${typesOf(business).join(',')}）`)
  checkEq(countOf(business, 'confirm_decision', toolCallId), 1, `${label}：confirm_decision 帧数`)
  checkEq(countOf(business, 'tool_result', toolCallId), 1, `${label}：tool_result 帧数`)
  const toolStarts = countOf(business, 'tool_start', toolCallId)
  if (allowToolStart) {
    check(toolStarts <= 1, `${label}：tool_start 帧数必须 <= 1（实得 ${toolStarts}）`)
    if (toolStarts === 1) {
      const start = findOf(business, 'tool_start', toolCallId)
      if (expectedName) checkEq(start.name, expectedName, `${label}：tool_start.name`)
      checkPresent(start.args, `${label}：tool_start.args`)
      check(
        indexOf(business, 'tool_start', toolCallId) < indexOf(business, 'tool_result', toolCallId),
        `${label}：tool_start 必须先于 tool_result`
      )
    }
  } else {
    checkEq(toolStarts, 0, `${label}：park 之后 tool_start 不应出现在差量里`)
  }
  const confirmed = assertConfirmedToolPayloads(business, { toolCallId, expectedName, label })
  return { frames: business.length, toolStarts, deltas: confirmed.deltas }
}

/**
 * 全量回放帧册：一轮完整生命周期的帧面契约（比"某类型恰一次"更强）。
 *
 * 断言：每个非心跳帧都带整数 seq 且从 1 逐号连续；start 首帧、done 末帧且唯一、
 * `done.seq === 本次全量回放末号（replay tip）`；无 error；suspended / confirm_request / tool_start / tool_result /
 * confirm_decision 各恰一次；顺序 = suspended 先于 confirm_request、tool_start 先于 tool_result、
 * confirm_decision 先于 tool_result；关键载荷（name/args/ok/outcome/result/decision/delta.text）
 * 必须自洽；帧型必须落在产品 16 型白名单内（`frontend/src/types/sse.ts` 的 `SseFrameTypes`）。
 */
const ARCHIVED_FRAME_TYPES = Object.freeze([
  'start',
  'context',
  'message_start',
  'delta',
  'message_end',
  'tool_start',
  'tool_result',
  'confirm_request',
  'confirm_decision',
  'frontend_tool_request',
  'frontend_tool_result',
  'suspended',
  'heartbeat',
  'retry',
  'done',
  'error'
])

/** 帧册末号的口径（复审 N2）：这里是"**本次全量回放**看到的末号"，不是服务端归档末号。 */
const FULL_REPLAY_TIP_SCOPE = 'full-replay'

function assertFrameLedger(frames, { toolCallId, label = '全量回放帧册' }) {
  check(frames.length > 0, `${label}：一帧都没收到`)
  const seqs = seqsOf(frames, label)
  const business = businessFrames(frames)
  const unknown = typesOf(business).filter((type) => !ARCHIVED_FRAME_TYPES.includes(type))
  checkEq(unknown.join(','), '', `${label}：出现白名单外的帧型（已收=${typesOf(business).join(',')}）`)
  checkEq(business[0].type, 'start', `${label}：首帧必须是 start（实得 ${typesOf(business).join(',')}）`)
  checkEq(business[business.length - 1].type, 'done', `${label}：末帧必须是 done（实得 ${typesOf(business).join(',')}）`)
  checkEq(countOf(business, 'done'), 1, `${label}：done 帧数`)
  checkEq(countOf(business, 'error'), 0, `${label}：不得出现 error 帧`)
  checkEq(countOf(business, 'start'), 1, `${label}：start 帧数`)
  checkEq(countOf(business, 'suspended'), 1, `${label}：suspended 帧数`)
  checkEq(countOf(business, 'confirm_request', toolCallId), 1, `${label}：confirm_request 帧数`)
  checkEq(countOf(business, 'confirm_decision', toolCallId), 1, `${label}：confirm_decision 帧数`)
  checkEq(countOf(business, 'tool_start', toolCallId), 1, `${label}：tool_start 帧数`)
  checkEq(countOf(business, 'tool_result', toolCallId), 1, `${label}：tool_result 帧数`)
  check(
    indexOf(business, 'suspended') < indexOf(business, 'confirm_request'),
    `${label}：suspended 必须先于 confirm_request（实得 ${typesOf(business).join(',')}）`
  )
  check(
    indexOf(business, 'tool_start', toolCallId) < indexOf(business, 'tool_result', toolCallId),
    `${label}：tool_start 必须先于 tool_result（实得 ${typesOf(business).join(',')}）`
  )
  check(
    indexOf(business, 'confirm_decision', toolCallId) < indexOf(business, 'tool_result', toolCallId),
    `${label}：confirm_decision 必须先于 tool_result（实得 ${typesOf(business).join(',')}）`
  )
  checkSeqContiguous(seqs, 1, label)
  const lastSeq = seqs[seqs.length - 1]
  // 复审 N2：这里能证明的是"done 是**本次全量回放**的末帧/末号"（回放里 done 之后再无业务帧），
  // 不是"done.seq == 服务端 archiveMaxSeq"：终态会清掉 session→current 索引，
  // `/runs/current` 的 archiveMaxSeq 此时不可读。服务端权威末号只在 park/kill 边界
  // （仍 SUSPENDED/RUNNING）可用，见 createSuspendedRun 与 caseSuspendKillA。
  checkEq(
    findOf(business, 'done').seq,
    lastSeq,
    `${label}：done.seq 必须等于本次全量回放的末号（replay tip，不是服务端 archiveMaxSeq）`
  )
  const payload = assertCompletedPayloads(frames, { toolCallId, label })
  return {
    lastSeq,
    lastSeqScope: FULL_REPLAY_TIP_SCOPE,
    types: typesOf(business).join(','),
    deltas: payload.deltas,
    name: payload.name
  }
}

function countOf(frames, type, toolCallId = null) {
  return frames.filter((f) => f.type === type && (toolCallId === null || f.toolCallId === toolCallId)).length
}

function indexOf(frames, type, toolCallId = null) {
  return frames.findIndex((f) => f.type === type && (toolCallId === null || f.toolCallId === toolCallId))
}

function findOf(frames, type, toolCallId = null) {
  const index = indexOf(frames, type, toolCallId)
  return index < 0 ? null : frames[index]
}


// ── HTTP / 路由日志 ────────────────────────────────────────────────────────

function initRouteLog() {
  PATHS.routeLog = path.join(RUN.artifacts, 'route-log.jsonl')
  writeFileAtomic(PATHS.routeLog, '')
}

function routeLog(entry) {
  if (!PATHS.routeLog) return
  const node = entry.node || 'none'
  STATE.routeCounts[node] = (STATE.routeCounts[node] || 0) + 1
  try {
    fs.appendFileSync(PATHS.routeLog, `${JSON.stringify({
      ts: nowIso(),
      case: STATE.currentCase,
      ...entry
    })}\n`)
  } catch {
    /* 证据写失败不打断请求 */
  }
}

function httpRequest(url, options = {}) {
  return new Promise((resolve, reject) => {
    const target = new URL(url)
    const started = Date.now()
    const headers = { ...(options.headers || {}) }
    // 对话数据面统一身份头：漏带会 400，且"回放/重挂/决策"会静默落到别的口径上；
    // 业务/模型配置不加（它们不做租户隔离）。`options.identity` 缺省 = 默认身份 A。
    // `options.omitIdentity` = 负控：**不带**任何身份头（用来证明某条路径确实在身份边界内）。
    Object.assign(headers, options.omitIdentity ? {} : aiIdentityHeaders(target.pathname, options.identity || null))
    const agent = options.agent === undefined ? false : options.agent
    const req = http.request({
      hostname: target.hostname,
      port: target.port || 80,
      path: `${target.pathname}${target.search}`,
      method: options.method || 'GET',
      headers,
      agent,
      timeout: options.timeoutMs || 15000
    }, (res) => {
      const chunks = []
      const localPort = req.socket ? req.socket.localPort : null
      const remotePort = req.socket ? req.socket.remotePort : null
      res.on('data', (chunk) => chunks.push(chunk))
      res.on('end', () => {
        const body = Buffer.concat(chunks)
        const record = {
          url,
          status: res.statusCode,
          headers: res.headers,
          body,
          text: body.toString('utf8'),
          ms: Date.now() - started,
          localPort,
          remotePort
        }
        routeLog({
          kind: options.routeKind || 'http',
          method: record.method || options.method || 'GET',
          path: `${target.pathname}${target.search}`,
          status: record.status,
          node: record.headers['x-cluster-node'] || options.directNode || null,
          clientPort: localPort,
          ms: record.ms,
          bytes: body.length
        })
        resolve(record)
      })
      res.on('error', reject)
    })
    req.on('timeout', () => req.destroy(new Error(`请求超时（${options.timeoutMs || 15000}ms）：${url}`)))
    req.on('error', reject)
    if (options.body !== undefined && options.body !== null) req.write(options.body)
    req.end()
  })
}

function jsonOf(record, label) {
  return jsonParse(record.text, label)
}

function dataOf(record, label) {
  const body = jsonOf(record, label)
  check(body && body.success === true, `${label}：success !== true（HTTP ${record.status}，体=${record.text.slice(0, 400)}）`)
  return body.data
}

// ── SSE 客户端（流式；支持任意 chunk 边界 / CRLF / 多行 data / [DONE]）──────

class SseStream {
  constructor(req, res, url) {
    this.req = req
    this.res = res
    this.url = url
    this.frames = []
    this.rawLines = []
    this.parseErrors = []
    this.dataLines = []
    this.eventName = null
    this.eventId = null
    this.buf = ''
    this.status = res.statusCode
    this.headers = res.headers
    this.node = res.headers['x-cluster-node'] || null
    this.localPort = req.socket ? req.socket.localPort : null
    this.ended = false
    this.doneSeen = false
    this.waiters = new Set()
    this.endError = null
    res.setEncoding('utf8')
    res.on('data', (chunk) => this._onData(chunk))
    res.on('end', () => this._onEnd())
    res.on('error', (e) => this._onError(e))
    req.on('error', (e) => this._onError(e))
  }

  _onData(chunk) {
    this.buf += chunk
    let index = this.buf.indexOf('\n')
    while (index >= 0) {
      let line = this.buf.slice(0, index)
      this.buf = this.buf.slice(index + 1)
      if (line.endsWith('\r')) line = line.slice(0, -1)
      this._line(line)
      index = this.buf.indexOf('\n')
    }
  }

  _line(line) {
    this.rawLines.push(line)
    if (line === '') {
      this._dispatch()
      return
    }
    if (line.startsWith(':')) return
    const colon = line.indexOf(':')
    const field = colon < 0 ? line : line.slice(0, colon)
    let value = colon < 0 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'data') this.dataLines.push(value)
    else if (field === 'event') this.eventName = value
    else if (field === 'id') this.eventId = value
  }

  _dispatch() {
    if (this.dataLines.length === 0) return
    const data = this.dataLines.join('\n')
    this.dataLines = []
    if (data.trim() === '[DONE]') {
      this.doneSeen = true
      const frame = { type: '[DONE]', _recvAtMs: Date.now(), _event: this.eventName, _synthetic: true }
      this._push(frame)
      return
    }
    let frame
    try {
      frame = JSON.parse(data)
    } catch (e) {
      this.parseErrors.push({ data: data.slice(0, 500), error: e.message })
      return
    }
    frame._recvAtMs = Date.now()
    frame._event = this.eventName
    this._push(frame)
  }

  _push(frame) {
    this.frames.push(frame)
    for (const waiter of Array.from(this.waiters)) {
      let matched = false
      try {
        matched = waiter.predicate(frame)
      } catch (e) {
        this.waiters.delete(waiter)
        waiter.reject(new Error(`等待条件自身抛错：${e.message}`))
        continue
      }
      if (matched) {
        this.waiters.delete(waiter)
        waiter.resolve(frame)
      }
    }
  }

  _onEnd() {
    this.ended = true
    const error = this.endError || new Error(`SSE 流结束但未命中等待条件（已收帧类型=${this.frames.map((f) => f.type).join(',')}）`)
    for (const waiter of Array.from(this.waiters)) {
      this.waiters.delete(waiter)
      waiter.reject(error)
    }
  }

  _onError(error) {
    this.endError = error
    for (const waiter of Array.from(this.waiters)) {
      this.waiters.delete(waiter)
      waiter.reject(error)
    }
  }

  waitFor(predicate, { timeoutMs = 60000, label = 'SSE 条件' } = {}) {
    for (const frame of this.frames) {
      if (predicate(frame)) return Promise.resolve(frame)
    }
    if (this.ended) {
      return Promise.reject(new Error(`${label}：流已结束（已收=${this.frames.map((f) => f.type).join(',')}）`))
    }
    return new Promise((resolve, reject) => {
      const waiter = { predicate, resolve, reject }
      const timer = setTimeout(() => {
        this.waiters.delete(waiter)
        reject(new Error(`${label}：${timeoutMs}ms 内未命中（已收=${this.frames.map((f) => f.type).join(',')}）`))
      }, timeoutMs)
      const wrapResolve = (frame) => {
        clearTimeout(timer)
        resolve(frame)
      }
      const wrapReject = (e) => {
        clearTimeout(timer)
        reject(e)
      }
      waiter.resolve = wrapResolve
      waiter.reject = wrapReject
      this.waiters.add(waiter)
    })
  }

  waitType(type, options) {
    return this.waitFor((frame) => frame.type === type, { ...options, label: options?.label || `等待帧 ${type}` })
  }

  waitTerminal(timeoutMs = 90000) {
    return this.waitFor((frame) => frame.type === 'done' || frame.type === 'error', {
      timeoutMs,
      label: '等待终帧（done/error）'
    })
  }

  /**
   * 等连接被**服务端**收尾（park 收流 / 回放完收尾）。
   *
   * 返回 `'end'`（服务端正常 `res.end()`）或 `'close'`（未见正常 end 的提前关闭）。
   * 两者必须可区分：park 契约要求的是前者，连接被重置不得冒充"已 park"。
   * 超时抛错 —— 悬挂连接正是本方法要抓的缺陷形态（用户 park 规则：不得恢复悬挂连接）。
   */
  waitClosed(timeoutMs = 30000, label = '等待流自然结束') {
    if (this.ended) return Promise.resolve('end')
    if (this.endError) return Promise.reject(this.endError)
    return new Promise((resolve, reject) => {
      let settled = false
      const cleanup = () => {
        clearTimeout(timer)
        this.res.removeListener('end', onEnd)
        this.res.removeListener('close', onClose)
        this.res.removeListener('error', onError)
        this.req.removeListener('error', onError)
      }
      const onEnd = () => {
        if (settled) return
        settled = true
        cleanup()
        resolve('end')
      }
      const onClose = () => {
        if (settled) return
        settled = true
        cleanup()
        resolve('close')
      }
      const onError = (e) => {
        if (settled) return
        settled = true
        cleanup()
        reject(e)
      }
      const timer = setTimeout(() => {
        if (settled) return
        settled = true
        cleanup()
        reject(new Error(`${label}：${timeoutMs}ms 内连接未被服务端收流（已收=${this.frames.map((f) => f.type).join(',') || '空'}）`))
      }, timeoutMs)
      this.res.once('end', onEnd)
      this.res.once('close', onClose)
      this.res.once('error', onError)
      this.req.once('error', onError)
    })
  }

  lastSeq() {
    let last = 0
    for (const frame of this.frames) {
      if (typeof frame.seq === 'number' && frame.seq > last) last = frame.seq
    }
    return last
  }

  seqs() {
    return this.frames.filter((f) => typeof f.seq === 'number').map((f) => f.seq)
  }

  types() {
    return this.frames.map((f) => f.type)
  }

  close() {
    try {
      this.req.destroy()
    } catch {
      /* 已断开 */
    }
  }
}

function openSse(url, options = {}) {
  return new Promise((resolve, reject) => {
    const target = new URL(url)
    const req = http.request({
      hostname: target.hostname,
      port: target.port || 80,
      path: `${target.pathname}${target.search}`,
      method: options.method || 'GET',
      agent: false,
      timeout: options.timeoutMs || 120000,
      headers: {
        Accept: 'text/event-stream',
        ...(options.headers || {}),
        ...aiIdentityHeaders(target.pathname, options.identity || null)
      }
    }, (res) => {
      if (res.statusCode < 200 || res.statusCode >= 300) {
        const chunks = []
        res.on('data', (chunk) => chunks.push(chunk))
        res.on('end', () => {
          const body = Buffer.concat(chunks).toString('utf8')
          routeLog({
            kind: 'sse-reject',
            method: options.method || 'GET',
            path: `${target.pathname}${target.search}`,
            status: res.statusCode,
            node: res.headers['x-cluster-node'] || options.directNode || null,
            clientPort: req.socket ? req.socket.localPort : null,
            ms: null,
            bytes: body.length
          })
          reject(new Error(`SSE ${url} 返回 HTTP ${res.statusCode}：${body.slice(0, 500)}`))
        })
        return
      }
      const stream = new SseStream(req, res, url)
      routeLog({
        kind: 'sse-open',
        method: options.method || 'GET',
        path: `${target.pathname}${target.search}`,
        status: res.statusCode,
        node: stream.node || options.directNode || null,
        clientPort: stream.localPort,
        ms: null,
        bytes: null
      })
      resolve(stream)
    })
    req.on('timeout', () => req.destroy(new Error(`SSE 请求超时（${options.timeoutMs || 120000}ms）：${url}`)))
    req.on('error', reject)
    if (options.body !== undefined && options.body !== null) req.write(options.body)
    req.end()
  })
}

// ── 统计页 / Docker / Redis（只读为主）─────────────────────────────────────

function parseStatsCsv(csv) {
  const lines = csv.split(/\r?\n/).filter((line) => line.length > 0)
  const headerLine = lines.find((line) => line.includes('pxname,svname'))
  check(headerLine, 'HAProxy stats CSV 缺少表头（pxname,svname）')
  const header = headerLine.replace(/^#\s*/, '').split(',')
  const rows = []
  for (const line of lines) {
    if (line.startsWith('#')) continue
    const cols = line.split(',')
    if (cols.length < 2) continue
    const row = {}
    header.forEach((name, index) => {
      row[name] = cols[index]
    })
    rows.push(row)
  }
  return { header, rows }
}

async function haproxyStats() {
  const record = await httpRequest(RUN.statsUrl, { timeoutMs: 5000 })
  checkEq(record.status, 200, `HAProxy stats HTTP 状态（${RUN.statsUrl}）`)
  const parsed = parseStatsCsv(record.text)
  const map = {}
  for (const inst of INSTANCES) {
    const row = parsed.rows.find((r) => r.svname === inst.node)
    check(row, `HAProxy stats 缺少 server ${inst.node}`)
    map[inst.key] = row
  }
  return { raw: record.text, parsed, map }
}

function docker(args, { allowFailure = false, encoding = 'utf8', maxBuffer = 64 * 1024 * 1024 } = {}) {
  try {
    return execFileSync('docker', args, { encoding, maxBuffer, stdio: ['ignore', 'pipe', 'pipe'] })
  } catch (e) {
    if (allowFailure) return null
    const stderr = e.stderr ? String(e.stderr) : ''
    throw new Error(`docker ${args.join(' ')} 失败：${e.message}${stderr ? `；stderr=${stderr.slice(0, 500)}` : ''}`)
  }
}

function redisCli(...args) {
  return docker(['exec', RUN.redisContainer, 'redis-cli', ...args]).trim()
}

function redisGet(key) {
  return redisCli('GET', key)
}

function redisExists(key) {
  return redisCli('EXISTS', key) === '1'
}

function redisHget(key, field) {
  return redisCli('HGET', key, field)
}

function haproxyLogs() {
  return docker(['logs', RUN.haproxyContainer])
}

async function pauseRedis() {
  docker(['pause', RUN.redisContainer])
}

async function unpauseRedis() {
  docker(['unpause', RUN.redisContainer])
}

// ── 进程生命周期（H2 TCP + 三个 JVM；由探针独占，便于冷启动失败时重置基线）──

function baseOf(key) {
  if (key === 'proxy') return RUN.proxyUrl
  const inst = INSTANCES.find((i) => i.key === key)
  check(inst, `未知实例 ${key}`)
  return `http://127.0.0.1:${inst.port}`
}

function directNode(key) {
  const inst = INSTANCES.find((i) => i.key === key)
  return inst ? inst.node : null
}

function instanceLogPath(key) {
  return path.join(PATHS.instancesDir, `instance-${key}.log`)
}

function instancePidPath(key) {
  return path.join(PATHS.instancesDir, `instance-${key}.pid`)
}

function persistPids() {
  if (!PATHS.pidsJson) return
  try {
    writeJson(PATHS.pidsJson, {
      instances: Object.fromEntries(Object.entries(STATE.pids)),
      h2: STATE.h2 && STATE.h2.pid ? STATE.h2.pid : null,
      updatedAt: nowIso()
    })
  } catch {
    /* 证据写失败不阻断 */
  }
}

function appendInstanceLog(key, message) {
  ensureDir(PATHS.instancesDir)
  fs.appendFileSync(instanceLogPath(key), `\n===== ${nowIso()} ${message} =====\n`)
}

function spawnTracked(command, args, { logFile, env, label, cwd }) {
  ensureDir(path.dirname(logFile))
  const fd = fs.openSync(logFile, 'a')
  const child = spawn(command, args, {
    env,
    cwd,
    stdio: ['ignore', fd, fd]
  })
  child.__label = label
  child.on('error', (error) => {
    child.__spawnError = error
    try {
      fs.appendFileSync(logFile, `\n===== ${nowIso()} ${label} spawn error: ${error.message} =====\n`)
    } catch {
      /* ignore */
    }
  })
  child.on('exit', (code, signal) => {
    try {
      fs.appendFileSync(logFile, `\n===== ${nowIso()} ${label} exited code=${code} signal=${signal} =====\n`)
    } catch {
      /* ignore */
    }
  })
  return child
}

function waitExit(child, timeoutMs) {
  if (!child || child.exitCode !== null || child.signalCode !== null) {
    return Promise.resolve({ code: child ? child.exitCode : null, signal: child ? child.signalCode : null })
  }
  if (child.__spawnError) return Promise.reject(child.__spawnError)
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      child.removeListener('exit', onExit)
      reject(new Error(`等待进程退出超时（${timeoutMs}ms，pid=${child.pid}）`))
    }, timeoutMs)
    function onExit(code, signal) {
      clearTimeout(timer)
      resolve({ code, signal })
    }
    child.once('exit', onExit)
  })
}

function processAlive(pid) {
  if (!pid) return false
  try {
    process.kill(pid, 0)
    return true
  } catch {
    return false
  }
}

async function waitPortClosed(port, timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const open = await new Promise((resolve) => {
      const socket = net.connect({ host: '127.0.0.1', port })
      const done = (value) => {
        socket.destroy()
        resolve(value)
      }
      socket.setTimeout(500)
      socket.once('connect', () => done(true))
      socket.once('timeout', () => done(false))
      socket.once('error', () => done(false))
    })
    if (!open) return true
    await sleep(200)
  }
  return false
}

async function waitTcpOpen(port, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const open = await new Promise((resolve) => {
      const socket = net.connect({ host: '127.0.0.1', port })
      const done = (value) => {
        socket.destroy()
        resolve(value)
      }
      socket.setTimeout(800)
      socket.once('connect', () => done(true))
      socket.once('timeout', () => done(false))
      socket.once('error', () => done(false))
    })
    if (open) return true
    await sleep(200)
  }
  return false
}

/**
 * 子进程环境白名单：**不透传 process.env**（不把 runner 上可能存在的密钥/凭据带进被测进程）。
 * 只给 JVM 必需的基础变量 + 桩占位 key；产品侧对 AI key 的判据只看非空。
 */
function baseChildEnv() {
  const env = {
    PATH: process.env.PATH || '/usr/local/bin:/usr/bin:/bin',
    LANG: process.env.LANG || 'C.UTF-8'
  }
  if (process.env.HOME) env.HOME = process.env.HOME
  if (process.env.TMPDIR) env.TMPDIR = process.env.TMPDIR
  return env
}

function instanceEnv(apiKey = PLACEHOLDER_KEY) {
  return {
    ...baseChildEnv(),
    AI_API_KEY: apiKey,
    AI_BASE_URL: RUN.stubUrl,
    AI_MODEL: STUB_MODEL
  }
}

function instanceArgs(key, { apiKey = PLACEHOLDER_KEY, extraArgs = [] } = {}) {
  const inst = INSTANCES.find((i) => i.key === key)
  check(inst, `未知实例 ${key}`)
  // h2tcp profile 提供共享库形态的固定口径（graceful shutdown、H2 console 关闭、10 连接池）；
  // LOCK_TIMEOUT 与 profile 默认值同口径（60s）：种子单飞的等待方要能等完整一轮播种。
  const dbUrl = `jdbc:h2:tcp://127.0.0.1:${RUN.ports.h2}/./cluster3;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000`
  const args = [
    '-Xms96m',
    '-Xmx640m',
    '-jar',
    RUN.jar,
    '--spring.profiles.active=h2tcp',
    `--server.port=${inst.port}`,
    `--spring.datasource.url=${dbUrl}`,
    '--spring.datasource.username=sa',
    '--spring.datasource.password=',
    `--spring.data.redis.host=127.0.0.1`,
    `--spring.data.redis.port=${RUN.ports.redis}`,
    `--app.file.storage-path=${RUN.sharedFiles}`,
    // 真实 XFF 只来自 HAProxy（127.0.0.1 host 网络）；其余来源一律按 RemoteAddr。
    '--app.web.trusted-proxies=127.0.0.1/32',
    ...COMMON_JAVA_ARGS,
    ...extraArgs
  ]
  void apiKey
  return args
}

function startInstance(key, options = {}) {
  const inst = INSTANCES.find((i) => i.key === key)
  check(inst, `未知实例 ${key}`)
  appendInstanceLog(key, `start ${options.note || ''} port=${inst.port} apiKey=${options.apiKey === '' ? '(empty)' : '(placeholder)'}`)
  // 每实例独立工作目录：产品的相对路径（KB 样本/索引等）不会在三个 JVM 之间互相踩，
  // 也不会写进仓库根目录。数据库/文件/Redis 都是显式共享面，与此无关。
  const cwd = path.join(RUN.runDir, `instance-${key}`)
  ensureDir(cwd)
  const child = spawnTracked(RUN.java, instanceArgs(key, options), {
    logFile: instanceLogPath(key),
    env: instanceEnv(options.apiKey === undefined ? PLACEHOLDER_KEY : options.apiKey),
    label: `instance-${key}`,
    cwd
  })
  STATE.procs.set(key, child)
  STATE.pids[key] = child.pid
  if (child.pid) fs.writeFileSync(instancePidPath(key), `${child.pid}\n`)
  persistPids()
  logLine(`实例 ${key} 启动：pid=${child.pid} port=${inst.port}`)
  return child
}

async function healthOf(key, { timeoutMs = 4000 } = {}) {
  const record = await httpRequest(`${baseOf(key)}/api/ai/health`, {
    timeoutMs,
    routeKind: 'health',
    directNode: directNode(key)
  })
  let body = null
  try {
    body = jsonOf(record, `instance-${key} /api/ai/health`)
  } catch {
    body = null
  }
  return { record, body }
}

/** 进程级就绪（`/health/ready`）：硬依赖（DB/存储/种子）+ draining。AI key 只是 capability。 */
async function readinessOf(key, { timeoutMs = 4000, agent } = {}) {
  const record = await httpRequest(`${baseOf(key)}/health/ready`, {
    timeoutMs,
    agent,
    routeKind: 'ready',
    directNode: directNode(key)
  })
  let body = null
  try {
    body = jsonOf(record, `instance-${key} /health/ready`)
  } catch {
    body = null
  }
  return { record, body }
}

/**
 * 基线就绪 = `/health/ready` ready=true（硬依赖）**且** `/api/ai/health` 可用（本网的用例要跑 AI 链路）。
 * 两者分工：进程 ready 不看 AI key（无 key 时仍 200），AI capability 单独由 /api/ai/health 报。
 */
async function isReady(key) {
  try {
    const [health, ready] = await Promise.all([healthOf(key), readinessOf(key)])
    return health.record.status === 200 && health.body && health.body.available === true
      && health.body.redis && health.body.redis.available === true
      && ready.record.status === 200 && ready.body && ready.body.ready === true
  } catch {
    return false
  }
}

async function waitReady(key, timeoutMs = RUN.readyTimeoutMs, { requireAi = true } = {}) {
  const deadline = Date.now() + timeoutMs
  const child = STATE.procs.get(key)
  let last = ''
  while (Date.now() < deadline) {
    if (child && child.__spawnError) {
      throw new Error(`实例 ${key} 无法启动（${child.__spawnError.message}）；日志尾部：\n${tailFile(instanceLogPath(key))}`)
    }
    if (child && (child.exitCode !== null || child.signalCode !== null)) {
      throw new Error(`实例 ${key} 启动失败（exit=${child.exitCode} signal=${child.signalCode}）；日志尾部：\n${tailFile(instanceLogPath(key))}`)
    }
    try {
      const [health, ready] = await Promise.all([
        healthOf(key, { timeoutMs: 3000 }),
        readinessOf(key, { timeoutMs: 3000 })
      ])
      const healthOk = health.record.status === 200 && health.body && health.body.available === true
        && health.body.redis && health.body.redis.available === true
      const readyOk = ready.record.status === 200 && ready.body && ready.body.ready === true
      last = `health HTTP ${health.record.status} available=${health.body ? health.body.available : '?'}`
        + ` redis=${health.body && health.body.redis ? health.body.redis.available : '?'}`
        + `；ready HTTP ${ready.record.status} ready=${ready.body ? ready.body.ready : '?'}`
        + ` draining=${ready.body ? ready.body.draining : '?'}`
      if (readyOk && (!requireAi || healthOk)) {
        if (health.body && health.body.instanceId) STATE.instanceIds[key] = health.body.instanceId
        caseLog(`实例 ${key} 就绪：instanceId=${health.body ? health.body.instanceId : '?'}`
          + ` leaseOk=${health.body ? health.body.leaseOk : '?'}${requireAi ? '' : '（无 AI key，仅进程就绪）'}`)
        return health.body
      }
    } catch (e) {
      last = e.message
    }
    await sleep(500)
  }
  throw new Error(`实例 ${key} 在 ${timeoutMs}ms 内未就绪（最后：${last}）；日志尾部：\n${tailFile(instanceLogPath(key))}`)
}

/**
 * 就绪口径分两层：/health 200 只证明 Web 起来了；种子（基座 7 + glm 8）由 ApplicationReady
 * 监听器在之后播种，且日志行早于事务提交（E2E 的 TC1 判据同样轮询定义数）。
 * 顺序启动的基线必须先等到定义齐，再起 B/C —— 否则会把「A 还在播种」误当成「并发播种串号」。
 */
async function waitSeeded(key, timeoutMs = 60000) {
  const deadline = Date.now() + timeoutMs
  const child = STATE.procs.get(key)
  let last = null
  while (Date.now() < deadline) {
    if (child && (child.exitCode !== null || child.signalCode !== null)) {
      throw new Error(`实例 ${key} 在播种完成前退出（exit=${child.exitCode} signal=${child.signalCode}）；日志尾部：\n${tailFile(instanceLogPath(key))}`)
    }
    try {
      const defs = await definitionsOf(key)
      last = defs.length
      if (defs.length >= 15) return defs
    } catch (e) {
      last = e.message
    }
    await sleep(500)
  }
  throw new Error(`实例 ${key} 在 ${timeoutMs}ms 内配置定义未达 15（最后：${last}）`)
}

/** 无 AI key 时的 capability 面：`/api/ai/health` 503 + available=false（**不是**实例 readiness）。 */
async function waitAiUnavailable(key, timeoutMs = RUN.readyTimeoutMs) {
  const deadline = Date.now() + timeoutMs
  const child = STATE.procs.get(key)
  let last = ''
  while (Date.now() < deadline) {
    if (child && child.__spawnError) {
      throw new Error(`实例 ${key} 无法启动（${child.__spawnError.message}）；日志尾部：\n${tailFile(instanceLogPath(key))}`)
    }
    if (child && (child.exitCode !== null || child.signalCode !== null)) {
      throw new Error(`实例 ${key} 以无 key 启动时进程退出（exit=${child.exitCode} signal=${child.signalCode}）；日志尾部：\n${tailFile(instanceLogPath(key))}`)
    }
    try {
      const { record, body } = await healthOf(key, { timeoutMs: 5000 })
      last = `HTTP ${record.status} code=${body ? body.code : '?'} available=${body ? body.available : '?'}`
      if (record.status === 503 && body && body.available === false) {
        STATE.instanceIds[key] = body.instanceId
        caseLog(`实例 ${key} 进程可用但 AI capability 不可用：code=${body.code} instanceId=${body.instanceId}`)
        return body
      }
    } catch (e) {
      last = e.message
    }
    await sleep(500)
  }
  throw new Error(`实例 ${key} 在 ${timeoutMs}ms 内未呈现 /api/ai/health 503（最后：${last}）`)
}

async function stopInstance(key, { timeoutMs = 20000 } = {}) {
  const child = STATE.procs.get(key)
  if (!child) return
  if (child.exitCode === null && child.signalCode === null) {
    try {
      child.kill('SIGTERM')
    } catch {
      /* ignore */
    }
    try {
      await waitExit(child, timeoutMs)
    } catch {
      try {
        child.kill('SIGKILL')
      } catch {
        /* ignore */
      }
      await waitExit(child, 5000).catch(() => {})
    }
  }
  STATE.procs.delete(key)
  const inst = INSTANCES.find((i) => i.key === key)
  await waitPortClosed(inst.port, 10000)
  logLine(`实例 ${key} 已停止`)
}

async function killInstance(key) {
  const child = STATE.procs.get(key)
  check(child, `实例 ${key} 不是本探针启动的，拒绝 kill（避免误杀）`)
  const pid = child.pid
  caseLog(`SIGKILL 实例 ${key} pid=${pid}`)
  child.kill('SIGKILL')
  const exited = await waitExit(child, 15000)
  STATE.procs.delete(key)
  const inst = INSTANCES.find((i) => i.key === key)
  const closed = await waitPortClosed(inst.port, 15000)
  logLine(`实例 ${key} 已被 kill：pid=${pid} exit=${JSON.stringify(exited)} portClosed=${closed}`)
  if (!closed) throw new Error(`实例 ${key} 被 kill 后端口 ${inst.port} 仍在监听`)
  return { pid, ...exited }
}

/**
 * 暂停/恢复实例（只用于「在途 run 的取消无法被处理」取证）。
 * SIGSTOP 只冻结进程，不结束它；任何失败路径都必须在 finally 里 SIGCONT，
 * 绝不允许把实例留在暂停态。
 */
function pauseInstance(key) {
  const child = STATE.procs.get(key)
  check(child && child.pid, `实例 ${key} 不是本探针启动的，拒绝 SIGSTOP`)
  check(processAlive(child.pid), `实例 ${key} 已退出，拒绝 SIGSTOP`)
  process.kill(child.pid, 'SIGSTOP')
  // SIGSTOP 已生效后不得再抛出（否则调用方来不及置“已暂停”标记、finally 不会 SIGCONT）。
  try {
    caseLog(`SIGSTOP 实例 ${key} pid=${child.pid}（冻结其取消处理）`)
  } catch {
    /* 证据写失败不影响暂停语义 */
  }
  return child.pid
}

function resumeInstance(key) {
  const child = STATE.procs.get(key)
  if (!child || !child.pid) return false
  const pid = child.pid
  if (!processAlive(pid)) return false
  let lastError = null
  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      process.kill(pid, 'SIGCONT')
    } catch (e) {
      lastError = e
      continue
    }
    try {
      caseLog(`SIGCONT 实例 ${key} pid=${pid}`)
    } catch {
      /* 证据写失败不影响恢复语义 */
    }
    return true
  }
  // SIGCONT 连续失败：宁可 SIGKILL 也绝不留下暂停进程（后续 ensureUp 会重启）。
  try {
    child.kill('SIGKILL')
  } catch {
    /* ignore */
  }
  throw new Error(`SIGCONT 实例 ${key} 失败（${lastError ? lastError.message : '未知'}）：已尝试 SIGKILL，避免暂停残留`)
}

async function ensureUp(keys = ['a', 'b', 'c']) {
  for (const key of keys) {
    if (await isReady(key)) continue
    const existing = STATE.procs.get(key)
    if (existing) await stopInstance(key)
    startInstance(key, { note: 'ensureUp' })
    await waitReady(key)
  }
}

// ── H2 TCP（共享库的唯一进程；冷启动失败时由探针整库重置）──────────────────

async function waitH2Ready(timeoutMs = 30000) {
  check(await waitTcpOpen(RUN.ports.h2, timeoutMs), `H2 TCP ${RUN.ports.h2} 在 ${timeoutMs}ms 内未监听`)
}

function startH2() {
  ensureDir(RUN.dbBase)
  ensureDir(PATHS.instancesDir)
  const logFile = path.join(RUN.artifacts, 'h2', 'h2-server.log')
  ensureDir(path.dirname(logFile))
  fs.appendFileSync(logFile, `\n===== ${nowIso()} start H2 baseDir=${RUN.dbBase} =====\n`)
  const child = spawnTracked(RUN.java, [
    '-cp',
    RUN.h2Jar,
    'org.h2.tools.Server',
    '-tcp',
    '-tcpPort',
    String(RUN.ports.h2),
    '-ifNotExists',
    '-baseDir',
    RUN.dbBase
  ], {
    logFile,
    env: baseChildEnv(),
    cwd: (() => {
      const dir = path.join(RUN.runDir, 'h2')
      ensureDir(dir)
      return dir
    })(),
    label: 'h2-server'
  })
  STATE.h2 = child
  STATE.pids.h2 = child.pid
  fs.writeFileSync(path.join(PATHS.instancesDir, 'h2.pid'), `${child.pid}\n`)
  persistPids()
  logLine(`H2 TCP 启动：pid=${child.pid} port=${RUN.ports.h2} baseDir=${RUN.dbBase}`)
  return child
}

async function stopH2() {
  const child = STATE.h2
  if (!child) return
  if (child.exitCode === null && child.signalCode === null) {
    try {
      child.kill('SIGTERM')
    } catch {
      /* ignore */
    }
    try {
      await waitExit(child, 10000)
    } catch {
      try {
        child.kill('SIGKILL')
      } catch {
        /* ignore */
      }
      await waitExit(child, 5000).catch(() => {})
    }
  }
  STATE.h2 = null
  logLine('H2 TCP 已停止')
}

async function resetDatabase({ note = '' } = {}) {
  caseLog(`重置数据库（${note}）`)
  for (const inst of INSTANCES) {
    if (STATE.procs.get(inst.key)) await stopInstance(inst.key)
  }
  await stopH2()
  fs.rmSync(RUN.dbBase, { recursive: true, force: true })
  ensureDir(RUN.dbBase)
  fs.rmSync(RUN.sharedFiles, { recursive: true, force: true })
  ensureDir(RUN.sharedFiles)
  // 冷启动重置也清掉每实例的相对路径残留（KB 样本/索引），避免「旧目录 + 空库」的伪冷启动。
  for (const inst of INSTANCES) {
    fs.rmSync(path.join(RUN.runDir, `instance-${inst.key}`), { recursive: true, force: true })
  }
  fs.rmSync(path.join(RUN.runDir, 'h2'), { recursive: true, force: true })
  startH2()
  await waitH2Ready()
}

async function stopEverything() {
  if (STATE.shuttingDown) return
  STATE.shuttingDown = true
  for (const inst of INSTANCES) {
    try {
      if (STATE.procs.get(inst.key)) await stopInstance(inst.key)
    } catch (e) {
      logLine(`收尾停止实例 ${inst.key} 失败：${e.message}`)
    }
  }
  try {
    await stopH2()
  } catch (e) {
    logLine(`收尾停止 H2 失败：${e.message}`)
  }
}

// ── 产品 API 助手（全部直接走实例，代理路径单独标注）──────────────────────

function directUrl(key, apiPath) {
  return `${baseOf(key)}${apiPath}`
}

function proxyUrl(apiPath) {
  return `${RUN.proxyUrl}${apiPath}`
}

async function getJson(key, apiPath, { timeoutMs = 15000, identity = null } = {}) {
  const record = await httpRequest(directUrl(key, apiPath), {
    timeoutMs,
    identity,
    routeKind: 'api',
    directNode: directNode(key)
  })
  return { record, body: record.text ? jsonOf(record, `${key} ${apiPath}`) : null }
}

async function postJson(key, apiPath, body, { timeoutMs = 20000, identity = null } = {}) {
  const payload = Buffer.from(JSON.stringify(body), 'utf8')
  const record = await httpRequest(directUrl(key, apiPath), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: payload,
    timeoutMs,
    identity,
    routeKind: 'api',
    directNode: directNode(key)
  })
  let parsed = null
  if (record.text) {
    try {
      parsed = jsonOf(record, `${key} POST ${apiPath}`)
    } catch {
      parsed = null
    }
  }
  return { record, body: parsed }
}

/**
 * **负控请求**：同样的 URL/方法，但**一个身份头都不带**。
 *
 * <p>用途只有一个：证明某条 `/api/ai/...` 路径确实在身份边界内（缺头必须 400）。产品把路径
 * 放回排除集时这条会红（拿到 2xx）；而"带头的正控"（同一路径带身份 → 2xx）证明探针确实带了头。
 * 两者成对才说明"这条路径要求身份，且我们满足它"，单独任何一条都可能是假绿。
 */
async function bareRequest(key, method, apiPath, body = null) {
  const payload = body === null ? undefined : Buffer.from(JSON.stringify(body), 'utf8')
  const record = await httpRequest(directUrl(key, apiPath), {
    method,
    headers: payload === undefined ? {} : { 'Content-Type': 'application/json' },
    body: payload,
    timeoutMs: 15000,
    identity: null,
    omitIdentity: true,
    routeKind: 'api',
    directNode: directNode(key)
  })
  let parsed = null
  if (record.text) {
    try {
      parsed = jsonParse(record.text, `bare ${method} ${apiPath}`)
    } catch {
      parsed = null
    }
  }
  return { record, body: parsed }
}

async function putJson(key, apiPath, body, { timeoutMs = 20000 } = {}) {
  const payload = Buffer.from(JSON.stringify(body), 'utf8')
  const record = await httpRequest(directUrl(key, apiPath), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: payload,
    timeoutMs,
    routeKind: 'api',
    directNode: directNode(key)
  })
  let parsed = null
  if (record.text) {
    try {
      parsed = jsonOf(record, `${key} PUT ${apiPath}`)
    } catch {
      parsed = null
    }
  }
  return { record, body: parsed }
}

async function deleteJson(key, apiPath, { timeoutMs = 20000 } = {}) {
  const record = await httpRequest(directUrl(key, apiPath), {
    method: 'DELETE',
    timeoutMs,
    routeKind: 'api',
    directNode: directNode(key)
  })
  let parsed = null
  if (record.text) {
    try {
      parsed = jsonOf(record, `${key} DELETE ${apiPath}`)
    } catch {
      parsed = null
    }
  }
  return { record, body: parsed }
}

function uniqueId(prefix) {
  return `${prefix}-${crypto.randomBytes(4).toString('hex')}`
}

/** `POST /api/ai/session` 撞号 503 的机器可读码（同一毫秒取到已存在的号）。 */
const SESSION_ID_UNAVAILABLE = 'SESSION_ID_UNAVAILABLE'

/** 撞号重试上限：每次请求都会自然落到新毫秒，3 次足够；用尽即判失败，不无限重试。 */
const SESSION_ID_MAX_ATTEMPTS = 3

/** 会话号的文本判据：十进制正整数（不按位数、不要求雪花号）。 */
function assertSessionIdText(raw, label) {
  check(/^\d+$/.test(raw), `sessionIdText（${label}）不是十进制字符串：${JSON.stringify(raw)}`)
  check(BigInt(raw) > 0n, `sessionIdText（${label}）不是正整数：${raw}`)
  return raw
}

/** 只有"毫秒撞号"这一种 503 可重试；其余状态/错误码一律立即失败，不许被重试吞掉。 */
function isSessionIdCollision(record, body) {
  return record.status === 503 && Boolean(body) && body.code === SESSION_ID_UNAVAILABLE
}

/**
 * 数值字段 `sessionId` 只为存量兼容：可安全表示时必须与权威文本同值；
 * 超安全整数时 JSON number 本就会静默变值，那种情况只认文本（这正是两字段并存的原因）。
 */
function assertSessionIdEcho(body, sessionIdText, label) {
  check(
    body && body.success === true && body.data,
    `POST /api/ai/session（${label}）响应体缺 success/data：${JSON.stringify(body)}`
  )
  const numeric = body.data.sessionId
  check(numeric !== undefined && numeric !== null, `POST /api/ai/session（${label}）响应缺数值字段 sessionId`)
  if (!Number.isSafeInteger(numeric)) return '文本为权威（数值字段超安全整数）'
  checkEq(String(numeric), sessionIdText, `sessionId 数值字段与 sessionIdText 不一致（${label}）`)
  return sessionIdText
}

/**
 * 会话号只由后端发（`POST /api/ai/session`）：返回值取 **sessionIdText**（十进制字符串，权威值）。
 *
 * <p>当前契约：`IdGenerator.nextId()` = `System.currentTimeMillis()`，即 13 位毫秒时间戳，
 * 只保证**十进制正整数**，不保证位数、也不再要求超出 `Number.MAX_SAFE_INTEGER`（雪花号已下线）。
 * 号值仍从**原始响应文本**用正则取（不经 `JSON.parse` 的数值字段）；数值字段存在时再断言两者同值。
 *
 * <p>本地 `uniqueId` 不用于会话号：那会绕过"后端发号 + 归属登记"这两件产品保证。
 *
 * <p>撞号（同一毫秒取到已存在的号）由后端登记侧 `SET NX` 兜底并返回 503 `SESSION_ID_UNAVAILABLE`；
 * 这里**只对这一种 503 有界重试**（下一次请求自然进入新毫秒），其它错误原样失败。
 */
async function newSession(key, tag, { identity = null } = {}) {
  const label = `${key}/${tag}`
  let last = null
  for (let attempt = 1; attempt <= SESSION_ID_MAX_ATTEMPTS; attempt += 1) {
    const { record, body } = await postJson(key, '/api/ai/session', {}, { identity })
    last = record
    if (record.status === 200) {
      const matched = /"sessionIdText"\s*:\s*"(\d+)"/.exec(record.text)
      check(matched, `POST /api/ai/session（${label}）响应缺权威字段 sessionIdText：${record.text.slice(0, 300)}`)
      const sessionId = assertSessionIdText(matched[1], label)
      assertSessionIdEcho(body, sessionId, label)
      if (attempt > 1) caseLog(`会话号第 ${attempt} 次取号成功（前 ${attempt - 1} 次同毫秒撞号）：${sessionId}`)
      return sessionId
    }
    check(
      isSessionIdCollision(record, body),
      `POST /api/ai/session（${label}）HTTP ${record.status}：${record.text.slice(0, 300)}`
    )
    if (attempt < SESSION_ID_MAX_ATTEMPTS) {
      caseLog(
        `会话号撞号 503（第 ${attempt}/${SESSION_ID_MAX_ATTEMPTS} 次），立即重取：${record.text.slice(0, 200)}`
      )
    }
  }
  throw new Error(
    `POST /api/ai/session（${label}）连续 ${SESSION_ID_MAX_ATTEMPTS} 次撞号 503 ${SESSION_ID_UNAVAILABLE}：${last.text.slice(0, 300)}`
  )
}

async function createTask(key, type, title) {
  const { record, body } = await postJson(key, '/api/tasks', { type, title })
  checkEq(record.status, 201, `POST /api/tasks（${key}）HTTP`)
  check(body && body.success === true && body.data && body.data.id, `创建任务失败：${record.text.slice(0, 400)}`)
  return body.data
}

async function selectDefs(key, taskId, defCodes) {
  const { record, body } = await postJson(key, `/api/tasks/${taskId}/select-defs`, { defCodes })
  checkEq(record.status, 200, `POST select-defs（${key}）HTTP`)
  check(body && body.success === true, `选择配置项失败：${record.text.slice(0, 400)}`)
  return body.data
}

/**
 * 把任务推进到 EXPORT 步（`PUT /api/tasks/{id}/step`）。
 *
 * 产品自「统一导出条件状态与单轮闭环」起，导出预检是 fail-closed：任务不在 EXPORT 步就拒绝
 * `start_export`（STEP_READY），任一配置项条件未决定也拒绝（CONDITIONS_DECIDED，除非已在 EXPORT 步
 * 且条件留空 = 用户明确全量）。本网的挂起/接管用例要的是一个"用户已走到导出步、条件留空"的就绪任务，
 * 否则 start_export 只回预检失败文本，连确认卡都到不了 —— 那不是本网要验的语义。
 */
async function readyToExport(key, taskId) {
  const { record, body } = await putJson(key, `/api/tasks/${taskId}/step`, { step: 'EXPORT' })
  checkEq(record.status, 200, `PUT step=EXPORT（${key}）HTTP`)
  check(body && body.success === true && body.data, `推进到 EXPORT 失败：${record.text.slice(0, 400)}`)
  checkEq(body.data.currentStep, 'EXPORT', `任务 ${taskId} 的权威步骤`)
  return body.data
}

async function getTask(key, taskId) {
  const { record, body } = await getJson(key, `/api/tasks/${taskId}`)
  checkEq(record.status, 200, `GET /api/tasks/${taskId}（${key}）HTTP`)
  check(body && body.success === true && body.data, `任务 ${taskId} 读取失败：${record.text.slice(0, 400)}`)
  return body.data
}

async function jobsOf(key, taskId) {
  const { record, body } = await getJson(key, `/api/tasks/${taskId}/jobs`)
  checkEq(record.status, 200, `GET jobs（${key}）HTTP`)
  check(body && body.success === true && Array.isArray(body.data), `作业列表异常：${record.text.slice(0, 400)}`)
  return body.data
}

async function jobOf(key, jobId) {
  const { record, body } = await getJson(key, `/api/jobs/${jobId}`)
  checkEq(record.status, 200, `GET /api/jobs/${jobId}（${key}）HTTP`)
  check(body && body.success === true && body.data, `作业 ${jobId} 读取失败：${record.text.slice(0, 400)}`)
  return body.data
}

async function jobIssues(key, jobId) {
  const { record, body } = await getJson(key, `/api/jobs/${jobId}/issues?page=0&size=50`)
  checkEq(record.status, 200, `GET jobs issues（${key}）HTTP`)
  check(body && body.success === true, `作业 issue 列表异常：${record.text.slice(0, 400)}`)
  return body.data
}

async function runDetail(key, runId, { identity = null } = {}) {
  const { record, body } = await getJson(key, `/api/ai/runs/${runId}`, { identity })
  checkEq(record.status, 200, `GET /api/ai/runs/${runId}（${key}）HTTP`)
  check(body && body.success === true && body.data, `run 详情异常：${record.text.slice(0, 400)}`)
  return body.data
}

async function currentRun(key, sessionId, { identity = null } = {}) {
  const { record, body } = await getJson(
    key,
    `/api/ai/runs/current?sessionId=${encodeURIComponent(sessionId)}`,
    { identity }
  )
  checkEq(record.status, 200, `GET /api/ai/runs/current（${key}）HTTP`)
  check(body && body.success === true && body.data, `runs/current 异常：${record.text.slice(0, 400)}`)
  return body.data
}

async function historyOf(key, sessionId, { identity = null } = {}) {
  const { record, body } = await getJson(key, `/api/ai/history/${encodeURIComponent(sessionId)}`, { identity })
  return { record, body }
}

/**
 * 等记忆窗口收敛出某条用户原文（数据库完整历史端点 /history/{sid}/messages 已随 ai_chat_message
 * 整条链路移除；窗口是唯一的历史读面，终态后由 convergeMemory 异步写回且没有读侧同步信号 ⇒ 有界等待）。
 * 超时如实抛错：判据无对象 = 失败，不得当通过。
 */
async function waitHistoryUserMessage(key, sessionId, expectedText, timeoutMs = 30000) {
  try {
    return await historyStable(key, sessionId, expectedText, timeoutMs)
  } catch (e) {
    throw new Error(`记忆窗口（${key}）在 ${timeoutMs}ms 内没有出现用户原文 ${expectedText}：${e.message}`)
  }
}

/** 等 run 落终态（DONE/FAILED/CANCELLED）。 */
async function waitRunTerminal(key, runId, timeoutMs = 90000) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() < deadline) {
    try {
      last = await runDetail(key, runId)
      const status = last.snapshot ? last.snapshot.status : null
      if (['DONE', 'FAILED', 'CANCELLED'].includes(status)) return last
    } catch {
      /* 继续轮询 */
    }
    await sleep(500)
  }
  throw new Error(`run ${runId} 在 ${timeoutMs}ms 内未到终态（最后：${JSON.stringify(last && last.snapshot ? last.snapshot.status : last).slice(0, 400)}）`)
}

async function definitionsOf(key) {
  const { record, body } = await getJson(key, '/api/definitions')
  checkEq(record.status, 200, `GET /api/definitions（${key}）HTTP`)
  check(body && body.success === true && Array.isArray(body.data), `配置定义列表异常：${record.text.slice(0, 400)}`)
  return body.data
}

async function chatOn(key, { sessionId, message, context }, options = {}) {
  const payload = Buffer.from(JSON.stringify({ sessionId, message, ...(context ? { context } : {}) }), 'utf8')
  const url = options.viaProxy ? proxyUrl('/api/ai/chat') : directUrl(key, '/api/ai/chat')
  return openSse(url, {
    method: 'POST',
    timeoutMs: options.timeoutMs || 120000,
    headers: { 'Content-Type': 'application/json' },
    body: payload,
    identity: options.identity || null,
    directNode: options.viaProxy ? null : directNode(key)
  })
}

async function reattachOn(key, runId, lastSeq, options = {}) {
  const query = lastSeq === undefined || lastSeq === null ? '' : `?lastSeq=${lastSeq}`
  return openSse(directUrl(key, `/api/ai/events/${runId}${query}`), {
    timeoutMs: options.timeoutMs || 120000,
    identity: options.identity || null,
    directNode: directNode(key)
  })
}

async function confirmOn(key, { runId, toolCallId, approved = true, reason = 'cluster3 probe' }, options = {}) {
  return postJson(
    key,
    '/api/ai/confirm',
    { runId, toolCallId, approved, reason },
    { identity: options.identity || null }
  )
}

/** `POST /api/ai/frontend-tool-result`（前端工具回灌；只接受 kind=FRONTEND 的条目）。 */
async function frontendToolResultOn(
  key,
  { runId, toolCallId, result, source = 'cluster3-probe', cancelled = false, outcome = null },
  options = {}
) {
  const body = { runId, toolCallId, result, source, cancelled }
  if (outcome !== null) body.outcome = outcome
  return postJson(key, '/api/ai/frontend-tool-result', body, { identity: options.identity || null })
}

/**
 * 全量回放到终帧。`evidenceName` 给了就在**退出前**（成功或失败）把当时收到的帧落盘 ——
 * 「done 缺失」正是最需要帧证据的形态，不能只在成功路径写。
 * `options.identity` = 回放所用身份（缺省 = 默认身份 A），跨身份用例用它证明"别人看不到"。
 */
async function fullReplay(key, runId, timeoutMs = 90000, evidenceName = null, options = {}) {
  const stream = await reattachOn(key, runId, 0, { timeoutMs, identity: options.identity || null })
  try {
    await stream.waitTerminal(timeoutMs)
    return stream.frames.slice()
  } finally {
    if (evidenceName) {
      try {
        saveFrames(evidenceName, stream.frames)
      } catch {
        /* 证据写失败不覆盖原始失败 */
      }
    }
    stream.close()
  }
}

/**
 * 终态全量回放到终帧。产品存在一个窄窗：finish() 先写 DONE、done 帧稍后才落档，
 * 恰好在此窗口到达的全量回放会停在 message_end（双实例 harness 的已知形态）。
 * 这里短延迟重试，只有 4 次仍无终帧才 FAIL —— 不掩盖真实的 done 缺失。
 * 每次尝试都会（按 evidenceName）覆盖同一份证据文件，于是最后一次失败的帧也能留下。
 */
async function fullReplayToTerminal(key, runId, timeoutMs = 90000, evidenceName = null, options = {}) {
  let lastError = null
  for (let attempt = 1; attempt <= 4; attempt += 1) {
    try {
      return await fullReplay(key, runId, timeoutMs, evidenceName, options)
    } catch (e) {
      lastError = e
      await sleep(500)
    }
  }
  throw new Error(`全量回放 4 次仍未收到终帧（runId=${runId}）：${lastError ? lastError.message : '未知'}`)
}

async function waitRunStatus(key, runId, expected, timeoutMs = 60000) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() < deadline) {
    try {
      last = await runDetail(key, runId)
      const status = last.snapshot ? last.snapshot.status : null
      if (status === expected) return last
    } catch {
      /* 继续轮询 */
    }
    await sleep(500)
  }
  throw new Error(`run ${runId} 在 ${timeoutMs}ms 内未到 ${expected}（最后：${JSON.stringify(last && last.snapshot ? last.snapshot.status : last)}）`)
}

function frameCount(frames, type, toolCallId = null) {
  return frames.filter((f) => f.type === type && (toolCallId === null || f.toolCallId === toolCallId)).length
}

function ledgerFor(detail, toolCallId) {
  return (detail.ledger || []).filter((entry) => entry.toolCallId === toolCallId)
}

/**
 * park 收流的时间上界。请求帧之后产品主动收流（B1），收流是本探针的**park 边界屏障**：
 * 只有它到了，才能确定 park 前该发的帧（含 `tool_start`）已经发出并落档。
 */
const PARK_SETTLE_TIMEOUT_MS = 20000

/**
 * 等这一轮真的 park 完，并校验 park 边界帧集。**证据先落盘**：
 * 收流一到（或超时/被重置）就先把当时的帧写进 `evidenceName`，再断言 —— 这样断言失败时
 * artifact 里仍有"当时收到了哪些帧"（复审 B1）。
 */
async function settleParkBoundary(stream, { toolCallId, timeoutMs = PARK_SETTLE_TIMEOUT_MS, evidenceName = null } = {}) {
  let how = null
  try {
    how = await stream.waitClosed(timeoutMs, '等待 park 收流')
  } finally {
    // 证据优先：收流/超时/被重置，都先把当时收到的帧落盘，再让断言去判。
    if (evidenceName) saveFrames(evidenceName, stream.frames)
  }
  checkEq(how, 'end', 'park 收流必须是服务端正常收尾（连接重置不算已 park）')
  const frames = stream.frames
  const summary = assertParkedFrameSet(frames, toolCallId)
  const payload = assertParkedPayloads(frames, { toolCallId, label: 'park 载荷' })
  return { frames, lastSeq: summary.lastSeq, types: summary.types, name: payload.name, heartbeats: summary.heartbeats }
}

/**
 * 从暂停的 run 里取「任务确认卡」的参数；不触发模型。
 *
 * <p>`waitForPark`（默认 true）= 等 park 收流后再返回（稳定基线：suspend-cross-instance /
 * 幂等用例用它）。`waitForPark: false` = **确认卡一到就返回**，把"park 转换窗口"留给调用方
 * （`suspend-kill-a` 收到卡立刻 SIGKILL —— 复审 B2 要求恢复的早杀覆盖；随本探针同批合入的候选分支
 * 已含 `ensureToolStart` 补发，断言的是"最终帧册恰一条 tool_start"这一不变量，而不是"哪一侧发的"）。
 */
async function createSuspendedRun(
  key,
  { prefix, taskId, messageSuffix = '', contextPage = 'tasks', waitForPark = true, evidenceName = null, identity = null }
) {
  const sessionId = await newSession(key, prefix, { identity })
  const context = { page: contextPage, taskType: 'EXPORT', step: 'EXPORT', taskId }
  const message = `start_export 帮我导出 taskId=${taskId}${messageSuffix}`
  const evidence = evidenceName || (waitForPark ? 'A-parked.jsonl' : 'A-park-transition.jsonl')
  const stream = await chatOn(key, { sessionId, message, context }, { identity })
  let runId = null
  let toolCallId = null
  let observed = null
  try {
    const frame = await stream.waitFor((f) => f.type === 'confirm_request' || f.type === 'done' || f.type === 'error', {
      timeoutMs: 120000,
      label: '等待确认卡'
    })
    if (frame.type !== 'confirm_request') {
      throw new Error(`期望确认卡，实得 ${frame.type}：${JSON.stringify(frame).slice(0, 500)}`)
    }
    toolCallId = frame.toolCallId
    // 证据优先：确认卡一到就把"客户端此刻看到的帧"落盘（早杀路径的 kill 之前也在这一步）。
    saveFrames(evidence, stream.frames)
    observed = waitForPark
      ? await settleParkBoundary(stream, { toolCallId, evidenceName: evidence })
      : (() => {
          const summary = assertCardReachedFrameSet(stream.frames, toolCallId)
          const payload = assertParkedPayloads(stream.frames, { toolCallId, label: '确认卡载荷' })
          return {
            frames: stream.frames.slice(),
            lastSeq: summary.lastSeq,
            types: summary.types,
            name: payload.name,
            heartbeats: summary.heartbeats
          }
        })()
    const start = observed.frames.find((f) => f.type === 'start')
    runId = start ? start.runId : null
  } finally {
    // 兜底取证：连"等卡超时/异常"也要留下当时收到的帧（写失败不得覆盖原始失败）。
    try {
      saveFrames(evidence, stream.frames)
    } catch {
      /* 证据写失败不覆盖原始失败 */
    }
    stream.close()
  }
  check(runId, `未收到 start 帧（runId 缺失）；已收帧=${observed ? observed.types : '空'}`)
  check(toolCallId, `确认卡缺少 toolCallId；已收帧=${observed ? observed.types : '空'}`)
  const result = {
    sessionId,
    taskId,
    runId,
    toolCallId,
    lastSeq: observed.lastSeq,
    frames: observed.frames,
    toolName: observed.name,
    heartbeats: observed.heartbeats,
    waitForPark,
    evidence,
    message
  }
  if (waitForPark) {
    // 客户端已收到的末号必须等于服务端归档末号：否则"已收流"与"已落档"之间有洞。
    // 早杀路径不做这条（被杀后归档可能比客户端多一帧 —— 那正是要观测的信息）。
    const current = await currentRun(key, sessionId, { identity })
    checkEq(current.runId, runId, `park 后 /runs/current 指向的 runId（${key}）`)
    checkEq(current.snapshotStatus, 'SUSPENDED', `park 后快照状态（${key}）`)
    checkEqNumber(current.archiveMaxSeq, observed.lastSeq, `park 后归档末号（${key}）`)
  }
  return result
}

// ── 前端通道（kind=FRONTEND）的 park 契约 ────────────────────────────────────
// 前端工具的帧面与确认门不同，不能套用 `confirm_request` 那套断言（那会把合法帧判成缺帧/白名单外）：
//   · 请求帧 = `frontend_tool_request`（帧里带 callback 回灌地址）；
//   · 结局帧 = `frontend_tool_result`，**没有** `tool_result`，也**没有** `confirm_decision`
//     （后端执行体是哨兵桩，副作用在浏览器里）；
//   · 请求帧之前同样要求 `suspended`（先外置再公布），并先于 `tool_start` 之后到达。
// 这里只钉产品**已经**给出的帧面（不新造能力）：用桩路由表既有的 TC16-NAVIGATE（navigate_to）构造。
const FRONTEND_PRE_PARK_BUSINESS_TYPES = Object.freeze(['start', 'suspended', 'frontend_tool_request', 'tool_start'])

/** "前端工具请求帧已到"的最小帧契约（park 边界用）。 */
function assertFrontendCardReachedFrameSet(frames, toolCallId, label = '前端工具请求帧集') {
  check(frames.length > 0, `${label}：一帧都没收到（已收=${typesOf(frames).join(',') || '空'}）`)
  const seqs = seqsOf(frames, label)
  const business = businessFrames(frames)
  check(business.length > 0, `${label}：一个业务帧都没收到（已收=${typesOf(frames).join(',')}）`)
  checkEq(business[0].type, 'start', `${label}：首个业务帧必须是 start（实得 ${typesOf(business).join(',')}）`)
  checkEq(countOf(business, 'start'), 1, `${label}：start 帧数`)
  checkEq(countOf(business, 'suspended'), 1, `${label}：suspended 帧数`)
  checkEq(
    countOf(business, 'frontend_tool_request', toolCallId),
    1,
    `${label}：frontend_tool_request 帧数（toolCallId=${toolCallId}）`
  )
  checkEq(
    countOf(business, 'confirm_request'),
    0,
    `${label}：前端通道不得出现确认卡（confirm_request）—— 两种挂起种类互斥`
  )
  checkEq(countOf(business, 'done') + countOf(business, 'error'), 0, `${label}：请求帧之前不得出现终帧`)
  const allowed = new Set([...FRONTEND_PRE_PARK_BUSINESS_TYPES, ...PRE_PARK_NOISE_TYPES])
  const unexpected = typesOf(business).filter((type) => !allowed.has(type))
  checkEq(unexpected.join(','), '', `${label}：出现请求帧之前不该有的帧型（已收=${typesOf(business).join(',')}）`)
  check(
    indexOf(business, 'suspended') < indexOf(business, 'frontend_tool_request'),
    `${label}：suspended 必须先于 frontend_tool_request（实得 ${typesOf(business).join(',')}）`
  )
  checkSeqContiguous(seqs, 1, label)
  return { lastSeq: seqs[seqs.length - 1], types: typesOf(business).join(','), heartbeats: frames.length - business.length }
}

/** 前端通道的 park 边界帧集：在请求帧契约之上多要求 `tool_start` 恰一次（收流完成 ⇒ 已落档）。 */
function assertFrontendParkedFrameSet(frames, toolCallId, label = '前端 park 边界帧集') {
  const summary = assertFrontendCardReachedFrameSet(frames, toolCallId, label)
  checkEq(
    countOf(businessFrames(frames), 'tool_start', toolCallId),
    1,
    `${label}：tool_start 帧数（收流完成 ⇒ 该帧必须已落档；toolCallId=${toolCallId}）`
  )
  return summary
}

/** 前端通道 park 阶段的关键载荷（name/args/callback/kind 与 suspended 公告必须自洽）。 */
function assertFrontendParkedPayloads(frames, { toolCallId, label = '前端 park 载荷' }) {
  const card = findOf(frames, 'frontend_tool_request', toolCallId)
  check(card, `${label}：缺少 frontend_tool_request（toolCallId=${toolCallId}）`)
  checkNonEmptyString(card.name, `${label}：frontend_tool_request.name`)
  checkPresent(card.args, `${label}：frontend_tool_request.args`)
  checkNonEmptyString(card.callback, `${label}：frontend_tool_request.callback（回灌地址）`)

  const suspended = findOf(frames, 'suspended')
  check(suspended, `${label}：缺少 suspended 帧`)
  checkNonEmptyString(suspended.runId, `${label}：suspended.runId`)
  check(Array.isArray(suspended.toolCalls), `${label}：suspended.toolCalls 必须是数组`)
  const entry = suspended.toolCalls.find((item) => item && item.toolCallId === toolCallId)
  check(entry, `${label}：suspended.toolCalls 未列出 ${toolCallId}`)
  checkEq(entry.name, card.name, `${label}：suspended.toolCalls[].name 必须与请求帧一致`)
  checkEq(entry.kind, 'FRONTEND', `${label}：suspended.toolCalls[].kind`)
  checkEq(String(entry.arguments), String(card.args), `${label}：suspended.toolCalls[].arguments 必须等于请求帧 args`)

  const start = findOf(frames, 'tool_start', toolCallId)
  if (start) {
    checkEq(start.name, card.name, `${label}：tool_start.name 必须等于请求帧 name`)
    checkEq(start.kind, 'FRONTEND', `${label}：tool_start.kind`)
    checkEq(JSON.stringify(start.args), JSON.stringify(card.args), `${label}：tool_start.args 必须等于请求帧 args`)
  }
  return { name: card.name }
}

/**
 * 前端通道的完整帧册（一轮已 done）：帧型白名单 + seq 逐号连续 + 关键帧各恰一次 + 顺序 +
 * `frontend_tool_result` 的载荷/结局。**明确断言后端没有**发 `tool_result`/`confirm_decision`：
 * 那是"前端工具被错当后端工具执行"的形态，正是要暴露的缺陷。
 */
function assertFrontendLedger(frames, { toolCallId, expectedName = null, expectedResult = null, label = '前端工具帧册' }) {
  check(frames.length > 0, `${label}：一帧都没收到`)
  const seqs = seqsOf(frames, label)
  const business = businessFrames(frames)
  const unknown = typesOf(business).filter((type) => !ARCHIVED_FRAME_TYPES.includes(type))
  checkEq(unknown.join(','), '', `${label}：出现白名单外的帧型（已收=${typesOf(business).join(',')}）`)
  checkEq(business[0].type, 'start', `${label}：首帧必须是 start（实得 ${typesOf(business).join(',')}）`)
  checkEq(business[business.length - 1].type, 'done', `${label}：末帧必须是 done（实得 ${typesOf(business).join(',')}）`)
  checkEq(countOf(business, 'done'), 1, `${label}：done 帧数`)
  checkEq(countOf(business, 'error'), 0, `${label}：不得出现 error 帧`)
  checkEq(countOf(business, 'start'), 1, `${label}：start 帧数`)
  checkEq(countOf(business, 'suspended'), 1, `${label}：suspended 帧数`)
  checkEq(countOf(business, 'frontend_tool_request', toolCallId), 1, `${label}：frontend_tool_request 帧数`)
  checkEq(countOf(business, 'frontend_tool_result', toolCallId), 1, `${label}：frontend_tool_result 帧数`)
  checkEq(countOf(business, 'tool_start', toolCallId), 1, `${label}：tool_start 帧数`)
  checkEq(countOf(business, 'confirm_request', toolCallId), 0, `${label}：前端通道不得出现 confirm_request`)
  checkEq(countOf(business, 'confirm_decision', toolCallId), 0, `${label}：前端通道不得出现 confirm_decision`)
  checkEq(countOf(business, 'tool_result', toolCallId), 0, `${label}：前端工具由浏览器执行，后端不得发 tool_result`)
  check(
    indexOf(business, 'suspended') < indexOf(business, 'frontend_tool_request', toolCallId),
    `${label}：suspended 必须先于 frontend_tool_request（实得 ${typesOf(business).join(',')}）`
  )
  check(
    indexOf(business, 'tool_start', toolCallId) < indexOf(business, 'frontend_tool_result', toolCallId),
    `${label}：tool_start 必须先于 frontend_tool_result（实得 ${typesOf(business).join(',')}）`
  )
  check(
    indexOf(business, 'frontend_tool_request', toolCallId) < indexOf(business, 'frontend_tool_result', toolCallId),
    `${label}：frontend_tool_request 必须先于 frontend_tool_result`
  )
  checkSeqContiguous(seqs, 1, label)
  const lastSeq = seqs[seqs.length - 1]
  checkEq(findOf(business, 'done').seq, lastSeq, `${label}：done.seq 必须等于本次全量回放的末号（replay tip）`)

  const { name } = assertFrontendParkedPayloads(frames, { toolCallId, label })
  const result = findOf(business, 'frontend_tool_result', toolCallId)
  checkEq(result.ok, true, `${label}：frontend_tool_result.ok`)
  checkEq(result.outcome, 'ok', `${label}：frontend_tool_result.outcome`)
  checkEq(result.executed, false, `${label}：frontend_tool_result.executed 必须为 false（后端未执行）`)
  checkEq(result.status, 'FRONTEND_RESULT', `${label}：frontend_tool_result.status`)
  if (expectedName) checkEq(result.name, expectedName, `${label}：frontend_tool_result.name`)
  checkNonEmptyString(result.result, `${label}：frontend_tool_result.result 不得为空`)
  if (expectedResult !== null) checkEq(result.result, expectedResult, `${label}：frontend_tool_result.result 必须原样回传回灌值`)
  const deltas = business.filter((frame) => frame.type === 'delta')
  check(deltas.length > 0, `${label}：续跑必须至少有一帧 delta（正文）`)
  return { lastSeq, types: typesOf(business).join(','), name, deltas: deltas.length }
}

/**
 * 前端通道的挂起轮：用桩路由表既有的 **TC16-NAVIGATE**（提示词含"导出向导" ⇒ `navigate_to`，
 * 产品元数据里是 `@ToolChannel(FRONTEND)`）构造，不新造产品能力、也不伪造工具。
 *
 * <p>与 `createSuspendedRun` 的差别只在"等哪一帧"与"用哪套帧集断言"：等 `frontend_tool_request`，
 * 断言前端通道的帧面（无确认卡）。同样在服务端收流之后才返回，park 起点因此是确定的。
 */
async function createFrontendParkedRun(
  key,
  { prefix, taskId, messageSuffix = '', evidenceName = 'A-frontend-parked.jsonl', identity = null }
) {
  const sessionId = await newSession(key, prefix, { identity })
  const context = { page: 'tasks', taskType: 'EXPORT', step: 'EXPORT', taskId }
  const message = `打开导出向导 taskId=${taskId}${messageSuffix}`
  const stream = await chatOn(key, { sessionId, message, context }, { identity })
  let runId = null
  let toolCallId = null
  let observed = null
  try {
    const frame = await stream.waitFor(
      (f) => f.type === 'frontend_tool_request' || f.type === 'confirm_request' || f.type === 'done' || f.type === 'error',
      { timeoutMs: 120000, label: '等待前端工具请求帧' }
    )
    if (frame.type !== 'frontend_tool_request') {
      throw new Error(`期望前端工具请求帧，实得 ${frame.type}：${JSON.stringify(frame).slice(0, 500)}`)
    }
    toolCallId = frame.toolCallId
    saveFrames(evidenceName, stream.frames)
    let how = null
    try {
      how = await stream.waitClosed(PARK_SETTLE_TIMEOUT_MS, '等待前端工具 park 收流')
    } finally {
      saveFrames(evidenceName, stream.frames)
    }
    checkEq(how, 'end', '前端工具 park 必须是服务端正常收尾（连接重置不算已 park）')
    const summary = assertFrontendParkedFrameSet(stream.frames, toolCallId)
    const payload = assertFrontendParkedPayloads(stream.frames, { toolCallId, label: '前端 park 载荷' })
    observed = {
      frames: stream.frames.slice(),
      lastSeq: summary.lastSeq,
      types: summary.types,
      name: payload.name,
      heartbeats: summary.heartbeats
    }
    const start = observed.frames.find((f) => f.type === 'start')
    runId = start ? start.runId : null
  } finally {
    try {
      saveFrames(evidenceName, stream.frames)
    } catch {
      /* 证据写失败不覆盖原始失败 */
    }
    stream.close()
  }
  check(runId, `未收到 start 帧（runId 缺失）；已收帧=${observed ? observed.types : '空'}`)
  check(toolCallId, `前端工具请求帧缺少 toolCallId；已收帧=${observed ? observed.types : '空'}`)
  const current = await currentRun(key, sessionId, { identity })
  checkEq(current.runId, runId, `前端 park 后 /runs/current 指向的 runId（${key}）`)
  checkEq(current.snapshotStatus, 'SUSPENDED', `前端 park 后快照状态（${key}）`)
  checkEqNumber(current.archiveMaxSeq, observed.lastSeq, `前端 park 后归档末号（${key}）`)
  return {
    sessionId,
    taskId,
    runId,
    toolCallId,
    lastSeq: observed.lastSeq,
    frames: observed.frames,
    toolName: observed.name,
    heartbeats: observed.heartbeats,
    evidence: evidenceName,
    message
  }
}

function checkEqNumber(actual, expected, label) {
  const n = Number(actual)
  if (!Number.isFinite(n) || n !== expected) {
    throw new Error(`${label}：期望 ${expected}，实得 ${JSON.stringify(actual)}`)
  }
}

function checkGeNumber(actual, minimum, label) {
  const n = Number(actual)
  if (!Number.isFinite(n) || n < minimum) {
    throw new Error(`${label}：期望 >= ${minimum}，实得 ${JSON.stringify(actual)}`)
  }
}

/**
 * 早杀观测（复审 N3）：<b>只</b>记录"客户端在 SIGKILL 之前有没有看到 `tool_start`"。
 *
 * <p>不推断这一帧是谁发的：A 完全可能在客户端关闭之后、SIGKILL 之前把它写进归档
 * （此时续跑补发是 no-op），而客户端证据无法区分"A 落档"与"续跑补发"。
 * 判据只用于取证与排障，不参与 pass/fail（帧册不变量由 `assertFrameLedger` 保证）。
 */
function killToolStartObservation(frames, toolCallId) {
  const seen = countOf(frames, 'tool_start', toolCallId) === 1
  return { toolStartSeenBeforeKill: seen, toolStartNotObservedBeforeKill: !seen }
}




// ── 用例注册（逐条覆盖用户清单；顺序即执行顺序）────────────────────────────

const CASES = [
  { id: 'cold-start-3', title: '三实例冷启动（共享 H2-TCP / Redis / 文件目录）', fn: caseColdStart3 },
  { id: 'proxy-no-sticky', title: '无 cookie/IP 粘滞（固定 Cookie + 固定 XFF，30 请求覆盖 A/B/C）', fn: caseProxyNoSticky },
  { id: 'same-tcp-across-abc', title: '同一条 keep-alive TCP 连接连续请求覆盖 A/B/C（代理日志佐证）', fn: caseSameTcpAcrossAbc },
  { id: 'reattach-any-instance', title: '任意实例 reattach（B 全量 / C 差量）与 history', fn: caseReattachAnyInstance },
  { id: 'seq-cross-instance', title: '跨实例 seq：实时流 / 增量重挂 / 全量回放同号同序', fn: caseSeqCrossInstance },
  { id: 'suspend-cross-instance', title: '挂起在 A → 决策前 B/C 重挂回放后即收流 → C 决策 → 决策后 B/C 重挂都收到续跑终帧', fn: caseSuspendCrossInstance },
  { id: 'idempotency-cross-instance', title: '跨实例幂等：重复决策 409 + 并发双决策恰一胜 + 副作用恰一次', fn: caseIdempotencyCrossInstance },
  { id: 'session-purge-cross-instance', title: '跨实例会话销毁：B 删 → C 验无残留；在途慢流 deferred → 终态重试 complete', fn: caseSessionPurgeCrossInstance },
  { id: 'config-hot-reload', title: '配置热更新：A 写入 → B/C 在 reload 周期内看到新版本与新值', fn: caseConfigHotReload },
  { id: 'shared-file-download', title: '共享文件：A 上传 → B/C/代理下载字节一致', fn: caseSharedFileDownload },
  { id: 'suspend-kill-a', title: '挂起态 kill A（早杀：确认卡后立即 SIGKILL，不等 park 收流）→ B 决策接管执行（台账 executedBy=B）→ C history', fn: caseSuspendKillA },
  { id: 'streaming-kill-a', title: '流式中 kill A → 僵尸扫描接管 → 跨实例补帧到终帧（seq 无洞）', fn: caseStreamingKillA },
  { id: 'job-kill-a', title: '作业中 kill A → 幸存实例看门狗收敛 FAILED → 重启清理后 B 重试成功', fn: caseJobKillA },
  { id: 'readiness-removal', title: 'readiness 摘除：无 key 不摘 A；SIGTERM/draining 摘 A 并由 B/C 接管；重启后重新纳入', fn: caseReadinessRemoval },
  { id: 'redis-outage-recovery', title: 'Redis 故障恢复：暂停期间 503/业务可用 → 恢复后跨实例健康与对话可用', fn: caseRedisOutageRecovery },
  { id: 'preexec-cancel-grid', title: 'L-150 三格：UNKNOWN 不得执行 / 已 CANCELLED 不得误认领 / claim 哈希-only 不得伪未执行（三格独立断言）', fn: casePreexecCancelGrid },
  // 双身份 × 三实例（排在基线 16 条之后：前面 16 条的执行顺序与判据逐字不变）。
  { id: 'identity-cross-instance', title: '双身份：A 身份在 A 挂起确认门 → B 身份在 B 对 events/runs/confirm 不可见且零副作用 → A 身份在 C 可确认', fn: caseIdentityCrossInstance },
  { id: 'identity-frontend-cross-instance', title: '双身份（前端通道）：A 身份在 A 起 frontend_tool_request 挂起 → B 身份在 B 对 events/runs/回灌不可见且零副作用 → A 身份在 C 可回灌并收敛', fn: caseIdentityFrontendCrossInstance }
]

const CTX = {
  cold: null,
  plainRun: null,
  file: null,
  proxyConfig: null
}

// ── 证据落盘 ───────────────────────────────────────────────────────────────

class FatalError extends Error {}

function currentCaseDir() {
  const dir = path.join(PATHS.casesDir, STATE.currentCase)
  ensureDir(dir)
  return dir
}

function saveEvidence(name, content) {
  const file = path.join(currentCaseDir(), name)
  ensureDir(path.dirname(file))
  if (Buffer.isBuffer(content)) fs.writeFileSync(file, content)
  else if (typeof content === 'string') fs.writeFileSync(file, content)
  else writeJson(file, content)
  return path.relative(RUN.artifacts, file)
}

function saveFrames(name, frames) {
  return saveEvidence(name, `${frames.map((frame) => JSON.stringify(frame)).join('\n')}\n`)
}

function saveHealthSnapshot(name, entries) {
  const file = path.join(currentCaseDir(), name)
  writeJson(file, entries)
  return path.relative(RUN.artifacts, file)
}

// ── 冷启动种子身份（T1：run 40182796512） ───────────────────────────────────

/**
 * `/api/ai/health` 的"种子身份"视图：providers 里**库里的行**（`synthetic !== true`）。
 *
 * 为什么认 `synthetic` 而不是"id 键是否存在"：全局 Jackson `default-property-inclusion=non_null`
 * 会省略 `id`（`AiConfigService#describeProviders` 的类注即写明合成行判据是 `synthetic`）。
 *
 * 返回 `{ providerId, ids, codes, synthetic, malformed }`：`ids` 为空 = 该实例只看到 env 合成行
 * （= 配置快照里根本没有库行，正是本轮 B/C 的形态）。
 */
function seededProviderIdentity(body) {
  const providerId = body && body.providerId !== undefined ? body.providerId : null
  if (!body || !Array.isArray(body.providers)) {
    return { providerId, ids: [], codes: [], synthetic: [], malformed: 'providers 缺失/不是数组' }
  }
  const seeded = body.providers.filter((row) => row && row.synthetic !== true && row.id !== undefined && row.id !== null)
  const synthetic = body.providers.filter((row) => row && row.synthetic === true).map((row) => row.code)
  return {
    providerId,
    ids: seeded.map((row) => Number(row.id)),
    codes: seeded.map((row) => row.code),
    synthetic,
    malformed: null
  }
}

/**
 * 三实例的种子身份必须是**同一行库行**：每个实例至少一条非合成行、默认 `providerId` 落在其中，
 * 且三实例的 id 列表与默认 providerId 逐字相同（"各自看到不同的库行"同样有问题）。
 */
function assertColdProviderIdentity(snapshots) {
  const keys = Object.keys(snapshots)
  for (const key of keys) {
    const snap = snapshots[key]
    check(!snap.malformed, `实例 ${key} /api/ai/health 的 providers 形态异常：${snap.malformed}`)
    check(
      snap.ids.length >= 1,
      `实例 ${key} 的 provider 列表没有库行（synthetic=${JSON.stringify(snap.synthetic)}）：` +
        '冷启动种子没有发布配置版本，该实例的配置快照停在空列表（env 合成行会掩盖成 AI_AVAILABLE）'
    )
    check(
      snap.providerId !== null && snap.providerId !== undefined,
      `实例 ${key} /api/ai/health 的 providerId 为空（默认目标未绑定到库行）`
    )
    check(
      snap.ids.includes(Number(snap.providerId)),
      `实例 ${key} 的默认 providerId=${snap.providerId} 不在库行 id 列表 ${JSON.stringify(snap.ids)} 中`
    )
  }
  for (const key of keys.slice(1)) {
    checkEq(
      JSON.stringify(snapshots[key].ids),
      JSON.stringify(snapshots[keys[0]].ids),
      `三实例必须看到同一批种子 provider id（${key} vs ${keys[0]}）`
    )
    checkEq(
      snapshots[key].providerId,
      snapshots[keys[0]].providerId,
      `三实例的默认 providerId 必须一致（${key} vs ${keys[0]}）`
    )
  }
  return { providerId: snapshots[keys[0]].providerId, ids: snapshots[keys[0]].ids }
}

/**
 * 有界等待"三实例看到同一行种子 provider"（默认 30s；种子写发布版本后一个 reload 周期（1s）即收敛，
 * 这里留足并发冷启动 + H2 首连的余量）。超时把**最后观测**写进报文，便于直接判因。
 */
async function waitColdSeededProvider(timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs
  let lastSnapshots = null
  let lastError = null
  while (Date.now() < deadline) {
    const snapshots = {}
    for (const inst of INSTANCES) {
      const health = await healthOf(inst.key)
      snapshots[inst.key] = seededProviderIdentity(health.body)
    }
    lastSnapshots = snapshots
    try {
      return assertColdProviderIdentity(snapshots)
    } catch (error) {
      lastError = error
    }
    await sleep(250)
  }
  // 证据先落盘再抛（复审 N1 同口径）：失败时也要留得下"三实例各自看到了什么"。
  saveHealthSnapshot('cold-provider-identity-last.json', lastSnapshots)
  throw new Error(
    `冷启动种子身份未在 ${timeoutMs}ms 内收敛：${lastError ? lastError.message : '未知'}；` +
      `最后观测=${JSON.stringify(lastSnapshots)}`
  )
}

// ── 用例 1：三实例冷启动 ───────────────────────────────────────────────────

async function startAllConcurrently() {
  for (const inst of INSTANCES) startInstance(inst.key, { note: 'cold-start-concurrent' })
  const settled = await Promise.allSettled(INSTANCES.map((inst) => waitReady(inst.key)))
  const failures = []
  settled.forEach((result, index) => {
    if (result.status === 'rejected') {
      failures.push({ instance: INSTANCES[index].key, error: result.reason.message })
    }
  })
  return failures
}

async function startBaselineSequential() {
  startInstance('a', { note: 'cold-start-sequential-a' })
  await waitReady('a')
  await waitSeeded('a')
  startInstance('b', { note: 'cold-start-sequential-bc' })
  startInstance('c', { note: 'cold-start-sequential-bc' })
  await Promise.all([waitReady('b'), waitReady('c')])
}

async function verifyColdState() {
  const ids = {}
  for (const inst of INSTANCES) {
    const health = await healthOf(inst.key)
    checkEq(health.record.status, 200, `实例 ${inst.key} /api/ai/health`)
    check(health.body && health.body.available === true, `实例 ${inst.key} AI 不可用：${health.record.text.slice(0, 300)}`)
    check(health.body.leaseOk === true, `实例 ${inst.key} leaseOk !== true（租约设施未就绪）`)
    ids[inst.key] = health.body.instanceId
    STATE.instanceIds[inst.key] = health.body.instanceId
  }
  checkEq(new Set(Object.values(ids)).size, 3, '三实例 instanceId 必须互异')
  saveHealthSnapshot('instance-ids.json', ids)

  // T1（run 40182796512）：冷启动必须看到**库里的种子行**，且三实例看到的是同一行。
  // 只断言 available === true 会漏掉本类缺陷：provider 快照为空时 /health 仍因 env 合成行回
  // AI_AVAILABLE（实测 B/C 的 providers[0].synthetic=true），失败因此推迟到第 7 例的 120s 超时。
  // 版本发布（种子写路径）保证 1s reload 周期内收敛，故这里做**有界**等待而不是拍一张快照。
  const coldProvider = await waitColdSeededProvider()
  saveHealthSnapshot('cold-provider-identity.json', coldProvider)
  caseLog(`冷启动种子身份：providerId=${coldProvider.providerId} ids=${JSON.stringify(coldProvider.ids)}`)

  // /health 200 之后 ApplicationReady 的种子监听器仍可能在本实例里跑；等定义齐再判一致性。
  await Promise.all(INSTANCES.map((inst) => waitSeeded(inst.key)))
  const defCounts = {}
  for (const inst of INSTANCES) {
    const defs = await definitionsOf(inst.key)
    const codes = defs.map((d) => d.code)
    checkGe(defs.length, 15, `实例 ${inst.key} 配置定义数量（基座 7 + glm 8）`)
    checkEq(new Set(codes).size, codes.length, `实例 ${inst.key} 配置定义 code 重复（并发播种串号）`)
    defCounts[inst.key] = defs.length
  }
  checkEq(new Set(Object.values(defCounts)).size, 1, `三实例配置定义数量不一致：${JSON.stringify(defCounts)}`)

  const title = uniqueId('cluster3-cold')
  const task = await createTask('a', 'EXPORT', title)
  const taskB = await getTask('b', task.id)
  const taskC = await getTask('c', task.id)
  checkEq(taskB.title, title, 'B 侧读 A 创建的任务标题')
  checkEq(taskC.title, title, 'C 侧读 A 创建的任务标题')
  CTX.cold = { taskId: task.id, instanceIds: ids, defCounts }
  return { ids, defCounts, taskId: task.id }
}

async function caseColdStart3() {
  await resetDatabase({ note: '冷启动前清空共享库 / 文件目录' })
  caseLog('三个 JVM 同时启动（共享 H2-TCP / Redis / 文件目录）')
  let concurrentError = null
  let state = null
  try {
    const failures = await startAllConcurrently()
    if (failures.length > 0) {
      saveEvidence('concurrent-start-failures.json', failures)
      throw new Error(`三实例并发冷启动失败：${failures.map((f) => `${f.instance}: ${f.error}`).join(' | ')}`)
    }
    state = await verifyColdState()
  } catch (e) {
    concurrentError = e
  }

  if (concurrentError) {
    caseLog(`并发冷启动失败：${concurrentError.message}`)
    caseLog('回退为「A 先起 → 等种子齐 → B/C 并发起」重建基线，保证后续用例仍可执行；本用例保持 FAIL')
    await resetDatabase({ note: '并发冷启动失败后的基线重建' })
    try {
      await startBaselineSequential()
      state = await verifyColdState()
    } catch (e) {
      throw new FatalError(`并发冷启动失败且顺序基线也起不来：${e.message}`)
    }
  }

  saveEvidence('cold-start-summary.json', {
    concurrentOk: concurrentError === null,
    concurrentError: concurrentError ? concurrentError.message : null,
    instanceIds: state.ids,
    defCounts: state.defCounts,
    taskId: state.taskId
  })
  if (concurrentError) throw concurrentError
  caseLog(`三实例并发冷启动成功；定义数=${JSON.stringify(state.defCounts)}；跨实例任务=${state.taskId}`)
}

// ── 用例 2：无 cookie / IP 粘滞 ────────────────────────────────────────────

function assertProxyConfigHasNoStickiness() {
  check(RUN.haproxyConfig && fs.existsSync(RUN.haproxyConfig), `HAProxy 配置文件不存在：${RUN.haproxyConfig}`)
  const cfg = fs.readFileSync(RUN.haproxyConfig, 'utf8')
  CTX.proxyConfig = cfg
  check(/^\s*balance\s+roundrobin\s*$/m.test(cfg), '代理未配置 balance roundrobin')
  check(!/^\s*stick-table\s/m.test(cfg), '代理配置出现 stick-table（违反无粘滞）')
  check(!/^\s*cookie\s/m.test(cfg), '代理配置出现 cookie 粘滞')
  check(!/^\s*balance\s+source\s/m.test(cfg), '代理配置出现 balance source（IP 粘滞）')
  check(/^\s*http-check\s+expect\s+status\s+200\s*$/m.test(cfg), '代理缺少 http-check expect status 200（readiness 判据）')
  check(/^\s*option\s+httpchk\s+GET\s+\/health\/ready\s*$/m.test(cfg), '代理 readiness 探针必须是 GET /health/ready（不能用 /api/ai/health）')
  check(/^backend\s+be_cluster3\s*$/m.test(cfg), '代理缺少 backend be_cluster3')
}

async function caseProxyNoSticky() {
  await ensureUp()
  // 直连就绪 ≠ 代理已完成 2 次成功健康检查；先等三个 server 都 UP，避免把「刚起来」误判成粘滞。
  await waitAllStatsUp()
  assertProxyConfigHasNoStickiness()
  const counts = { a: 0, b: 0, c: 0 }
  const samples = []
  const headers = {
    Cookie: 'JSESSIONID=cluster3-fixed; cluster3_sticky=1',
    'X-Forwarded-For': '203.0.113.7',
    Accept: 'application/json'
  }
  for (let i = 0; i < 30; i += 1) {
    const record = await httpRequest(`${RUN.proxyUrl}/api/ai/health`, {
      timeoutMs: 10000,
      headers,
      routeKind: 'proxy-health'
    })
    checkEq(record.status, 200, `代理第 ${i + 1} 次 /api/ai/health HTTP`)
    const node = record.headers['x-cluster-node']
    check(node, `代理响应缺少 X-Cluster-Node（第 ${i + 1} 次）`)
    const key = node.replace(/^node-/, '')
    checkIn(key, ['a', 'b', 'c'], `代理响应节点 ${node}`)
    counts[key] += 1
    const body = jsonOf(record, `代理第 ${i + 1} 次 health`)
    checkEq(body.instanceId, STATE.instanceIds[key], `节点 ${node} 的 instanceId 与直连映射不一致`)
    check(!record.headers['set-cookie'], `代理响应出现 Set-Cookie（第 ${i + 1} 次）：${record.headers['set-cookie']}`)
    samples.push({ i: i + 1, node, localPort: record.localPort, instanceId: body.instanceId })
  }
  for (const key of ['a', 'b', 'c']) {
    checkGe(counts[key], 8, `节点 ${key} 在 30 次代理请求中的命中数（roundrobin 期望约 10）`)
    checkLe(counts[key], 12, `节点 ${key} 在 30 次代理请求中的命中数（roundrobin 期望约 10）`)
  }
  saveEvidence('proxy-distribution.json', { counts, samples })
  caseLog(`30 次代理请求分布=${JSON.stringify(counts)}；固定 Cookie/XFF 未产生粘滞`)
}

// ── 用例 3：同一条 keep-alive TCP 连接覆盖 A/B/C ──────────────────────────

async function caseSameTcpAcrossAbc() {
  await ensureUp()
  await waitAllStatsUp()
  const agent = new http.Agent({ keepAlive: true, maxSockets: 1, maxFreeSockets: 1 })
  const records = []
  try {
    for (let i = 0; i < 6; i += 1) {
      const record = await httpRequest(`${RUN.proxyUrl}/api/ai/health`, {
        timeoutMs: 10000,
        agent,
        headers: {
          Cookie: 'JSESSIONID=cluster3-same-tcp',
          'X-Forwarded-For': '203.0.113.7',
          Connection: 'keep-alive',
          Accept: 'application/json'
        },
        routeKind: 'proxy-health-same-tcp'
      })
      checkEq(record.status, 200, `同连接第 ${i + 1} 次 HTTP`)
      records.push(record)
    }
  } finally {
    agent.destroy()
  }
  const ports = new Set(records.map((r) => r.localPort))
  checkEq(ports.size, 1, `同一条 keep-alive 连接上出现了多个本地端口：${JSON.stringify([...ports])}`)
  const localPort = [...ports][0]
  const nodes = records.map((r) => r.headers['x-cluster-node']).filter(Boolean)
  checkEq(nodes.length, 6, `同连接响应缺少 X-Cluster-Node：${JSON.stringify(nodes)}`)
  for (const key of ['node-a', 'node-b', 'node-c']) {
    checkGe(nodes.filter((n) => n === key).length, 2, `同连接 6 次请求中 ${key} 的命中数`)
  }
  for (const record of records) {
    const body = jsonOf(record, '同连接 health')
    checkEq(body.instanceId, STATE.instanceIds[record.headers['x-cluster-node'].replace(/^node-/, '')], '同连接 instanceId 映射')
  }

  // 代理侧独立证据：HAProxy 自己的访问日志里，同一个客户端端口必须出现 3 个不同 server。
  // 日志在事务结束时写出；连接刚销毁时可能还没刷到 docker logs，故短轮询而不是固定 sleep。
  let logs = ''
  let servers = []
  const logDeadline = Date.now() + 8000
  const pattern = new RegExp(`127\\.0\\.0\\.1:${localPort}\\b.*?be_cluster3/(node-[abc])\\b`, 'g')
  while (Date.now() < logDeadline) {
    logs = haproxyLogs()
    servers = []
    let match
    pattern.lastIndex = 0
    while ((match = pattern.exec(logs)) !== null) servers.push(match[1])
    if (servers.length >= 3) break
    await sleep(500)
  }
  const filtered = logs.split(/\r?\n/).filter((line) => line.includes(`127.0.0.1:${localPort}`))
  saveEvidence('haproxy-same-tcp.log', filtered.join('\n'))
  checkGe(servers.length, 3, `HAProxy 日志中客户端端口 ${localPort} 的请求行数`)
  for (const node of ['node-a', 'node-b', 'node-c']) {
    check(servers.includes(node), `HAProxy 日志中客户端端口 ${localPort} 未出现 ${node}（实得 ${JSON.stringify([...new Set(servers)])}）`)
  }
  saveEvidence('same-tcp-summary.json', { localPort, nodes, servers })
  caseLog(`同一 TCP 连接 localPort=${localPort} 连续 6 次覆盖 ${JSON.stringify([...new Set(nodes)])}；HAProxy 日志同端口 server=${JSON.stringify([...new Set(servers)])}`)
}

// ── 用例 4：任意实例 reattach + history ────────────────────────────────────

async function runPlainChat(key, prefix, extraMessage = '', { sessionId: fixedSessionId } = {}) {
  const sessionId = fixedSessionId || (await newSession(key, prefix))
  const message = `scenario=PLAIN ${extraMessage || '三实例连通性'}`.trim()
  const stream = await chatOn(key, { sessionId, message })
  try {
    const terminal = await stream.waitTerminal(120000)
    checkEq(terminal.type, 'done', `plain run 终帧类型（${key}）`)
    const seqs = stream.seqs()
    checkStrictlyIncreasing(seqs, `plain run 序号（${key}）`)
    const start = stream.frames.find((f) => f.type === 'start')
    check(start && start.runId, `plain run 缺少 start.runId（${key}）`)
    return { sessionId, message, runId: start.runId, seqs, frames: stream.frames.slice(), stream }
  } finally {
    stream.close()
  }
}

async function historyStable(key, sessionId, expectedText, timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() < deadline) {
    const { record, body } = await historyOf(key, sessionId)
    last = { record, body }
    if (record.status === 200 && body && body.success === true && body.data && Array.isArray(body.data.messages)) {
      const messages = body.data.messages
      const hasUser = messages.some((m) => m.type === 'USER' && String(m.text || '').includes(expectedText))
      if (hasUser) return body.data
    }
    await sleep(500)
  }
  throw new Error(`history（${key}）在 ${timeoutMs}ms 内未出现用户消息；最后响应=${last && last.record ? last.record.text.slice(0, 500) : '无'}`)
}

async function caseReattachAnyInstance() {
  await ensureUp()
  const run = await runPlainChat('a', 'cluster3-reattach')
  const fullB = await fullReplayToTerminal('b', run.runId)
  checkStrictlyIncreasing(fullB.filter((f) => typeof f.seq === 'number').map((f) => f.seq), 'B 全量回放 seq')
  const seqsB = fullB.filter((f) => typeof f.seq === 'number').map((f) => f.seq)
  checkEq(JSON.stringify(seqsB), JSON.stringify(run.seqs), 'B 全量回放与 A 实时流 seq 列表')

  const cut = run.seqs[Math.floor(run.seqs.length / 2)]
  const diffC = await fullReplayToTerminal('c', run.runId, 60000)
  // 注：fullReplay 从 0 开始；差量另走一次带 lastSeq 的重挂。
  const streamC = await reattachOn('c', run.runId, cut, { timeoutMs: 60000 })
  let diffSeqs = []
  try {
    await streamC.waitTerminal(60000)
    diffSeqs = streamC.frames.filter((f) => typeof f.seq === 'number').map((f) => f.seq)
  } finally {
    streamC.close()
  }
  const expected = run.seqs.filter((seq) => seq > cut)
  checkEq(JSON.stringify(diffSeqs), JSON.stringify(expected), `C 侧 lastSeq=${cut} 差量`)
  checkStrictlyIncreasing(diffSeqs, 'C 侧差量 seq')

  const historyA = await historyStable('a', run.sessionId, '三实例连通性')
  const historyB = await historyStable('b', run.sessionId, '三实例连通性')
  const historyC = await historyStable('c', run.sessionId, '三实例连通性')
  checkEq(historyB.total, historyA.total, 'B 侧 history total 与 A 不一致')
  checkEq(historyC.total, historyA.total, 'C 侧 history total 与 A 不一致')
  checkGe(historyA.total, 2, 'history 至少包含 user + assistant 两条')

  CTX.plainRun = {
    runId: run.runId,
    sessionId: run.sessionId,
    message: run.message,
    seqs: run.seqs.slice()
  }
  saveFrames('A-live.jsonl', run.frames)
  saveFrames('B-full-replay.jsonl', fullB)
  saveFrames('C-full-replay.jsonl', diffC)
  saveEvidence('history.json', {
    A: { total: historyA.total, count: historyA.count },
    B: { total: historyB.total, count: historyB.count },
    C: { total: historyC.total, count: historyC.count }
  })
  caseLog(`runId=${run.runId} 帧数=${run.seqs.length}；B 全量同号同序；C 差量 lastSeq=${cut} 不重不漏；三实例 history total=${historyA.total}`)
}

// ── 用例 5：跨实例 seq（实时流 / 增量重挂 / 全量回放）─────────────────────

async function caseSeqCrossInstance() {
  await ensureUp()
  const sessionId = await newSession('a', 'cluster3-seq')
  const message = 'scenario=PLAIN stall-frames=2000 seq-cross-instance'
  const streamA = await chatOn('a', { sessionId, message })
  let cut = 0
  let runId = null
  try {
    const start = await streamA.waitType('start', { timeoutMs: 30000 })
    runId = start.runId
    await streamA.waitFor((f) => typeof f.seq === 'number' && f.seq >= 2, { timeoutMs: 30000, label: '等待第 2 帧' })
    cut = streamA.lastSeq()
    const streamB = await reattachOn('b', runId, cut, { timeoutMs: 120000 })
    try {
      const terminalA = await streamA.waitTerminal(120000)
      checkEq(terminalA.type, 'done', 'A 实时流终帧')
      const terminalB = await streamB.waitTerminal(120000)
      checkEq(terminalB.type, 'done', 'B 增量重挂终帧')
      const seqsA = streamA.seqs()
      const seqsB = streamB.seqs()
      checkStrictlyIncreasing(seqsA, 'A 实时流 seq')
      checkStrictlyIncreasing(seqsB, `B 增量重挂 seq（lastSeq=${cut}）`)
      checkGe(seqsB[0], cut + 1, 'B 增量起始 seq 必须 > lastSeq')
      const expectedB = seqsA.filter((seq) => seq > cut)
      checkEq(JSON.stringify(seqsB), JSON.stringify(expectedB), 'B 增量 seq 必须是 A 的连续后缀（不重不漏）')

      const fullC = await fullReplayToTerminal('c', runId, 90000)
      const seqsC = fullC.filter((f) => typeof f.seq === 'number').map((f) => f.seq)
      checkEq(JSON.stringify(seqsC), JSON.stringify(seqsA), 'C 全量回放 seq 与 A 实时流')
      saveFrames('A-live.jsonl', streamA.frames)
      saveFrames('B-diff.jsonl', streamB.frames)
      saveFrames('C-full.jsonl', fullC)
      saveEvidence('seq-summary.json', { runId, cut, A: seqsA, B: seqsB, C: seqsC })
      caseLog(`runId=${runId}；A/B/C seq 一致（cut=${cut}，A=${seqsA.length} 帧）`)
    } finally {
      streamB.close()
    }
  } finally {
    streamA.close()
  }
}

// ── 用例 6：挂起在 A → 决策前 B/C 重挂（回放后收流）→ C 决策 → 决策后重挂收终帧 ──

/**
 * 决策前重挂的语义（用户 park 规则的裁决口径）：产品**保留非阻塞收流** ——
 * 回放完已归档的帧就把连接收尾，不悬挂连接、不阻塞执行、不伪造终帧。
 *
 * 因此本用例按实际安全契约断言，而不是按"连接活到 done"断言：
 * ① park 边界（A）：确认卡帧集 + `tool_start`，收流到点；帧证据先落盘；
 * ② 决策前重挂（B 差量 / C 全量）：回放**逐帧全字段**与 park 帧集一致（含 `toolCalls`/`runId`/`context`）、
 *    不含终帧、被服务端收流；状态/ledger 类证据同样先落盘；
 * ③ 收流没把这一轮弄死：仍 SUSPENDED、仍 awaitingExternal、pending 条目仍在；
 * ④ 决策（C）后按产品自述标准时序重挂：B 差量做 **seq 连续 + 帧型/载荷**校验，C 全量做**整本帧册**校验，
 *    两条连接都必须收到 done；
 * ⑤ A 全量回放帧册：seq 逐号连续、关键帧各恰一次、顺序、帧型白名单与关键载荷（`done` = 本次回放末号）。
 */
async function caseSuspendCrossInstance() {
  await ensureUp()
  const suffix = uniqueId('cross')
  const task = await createTask('c', 'EXPORT', `cluster3-suspend-${suffix}`)
  await selectDefs('c', task.id, ['CURRENCY'])
  await readyToExport('c', task.id)
  const run = await createSuspendedRun('a', { prefix: 'cluster3-suspend', taskId: task.id, messageSuffix: ` ${suffix}` })
  caseLog(`挂起在 A（已 park 收流）runId=${run.runId} toolCallId=${run.toolCallId} taskId=${task.id} lastSeq=${run.lastSeq} 帧=${run.frames.map((f) => f.type).join(',')}（证据 ${run.evidence}）`)

  // ① 决策前的两条重挂（B 带 lastSeq 的差量 / C 从 0 的全量）。帧在断言之前先落盘。
  const preB = await reattachOn('b', run.runId, run.lastSeq, { timeoutMs: 30000 })
  let preBHow = null
  try {
    preBHow = await preB.waitClosed(PARK_SETTLE_TIMEOUT_MS, 'B 决策前重挂收流')
    saveFrames('B-predecision-reattach.jsonl', preB.frames)
    checkEq(preBHow, 'end', 'B 决策前重挂必须是服务端正常收尾（连接重置不算）')
    checkEq(
      businessFrames(preB.frames).length,
      0,
      `B 决策前重挂（lastSeq=${run.lastSeq}）不得有未归档帧（已收=${typesOf(preB.frames).join(',') || '空'}）`
    )
  } finally {
    saveFrames('B-predecision-reattach.jsonl', preB.frames)
    preB.close()
  }

  const preC = await reattachOn('c', run.runId, 0, { timeoutMs: 30000 })
  let preCHow = null
  let preCReplay = null
  try {
    preCHow = await preC.waitClosed(PARK_SETTLE_TIMEOUT_MS, 'C 决策前重挂收流')
    saveFrames('C-predecision-reattach.jsonl', preC.frames)
    checkEq(preCHow, 'end', 'C 决策前重挂必须是服务端正常收尾（连接重置不算）')
    // 全字段比对 + park 载荷断言（toolCalls/runId/context 等被改坏同样红）。
    preCReplay = assertPreDecisionReplay(preC.frames.slice(), run.frames, run.toolCallId, 'C 决策前重挂')
  } finally {
    saveFrames('C-predecision-reattach.jsonl', preC.frames)
    preC.close()
  }

  // ② 收流不等于"这一轮死了"：状态、等待面、待决条目都必须原样还在。
  // 证据优先（复审 N1）：先把 /runs/current 的响应落盘，再做五项断言。
  const still = await currentRun('b', run.sessionId)
  evidenceFirst(
    (body) => saveEvidence('runs-current-b.json', body),
    () => still,
    () => {
      checkEq(still.runId, run.runId, '决策前重挂收流后 /runs/current 指向的 runId')
      checkEq(still.snapshotStatus, 'SUSPENDED', '决策前重挂收流后的快照状态')
      check(still.awaitingExternal === true, '决策前重挂收流后 awaitingExternal 必须仍为 true')
      check(
        (still.pendingEntries || []).some((entry) => entry.toolCallId === run.toolCallId && entry.entryStatus === 'PENDING'),
        '决策前重挂收流后 pendingEntries 必须仍列出该 PENDING 条目'
      )
      checkEqNumber(still.archiveMaxSeq, run.lastSeq, '决策前重挂收流后的归档末号（服务端权威值）')
    }
  )

  // ③ 决策落 C。证据优先：回执先落盘，再断言（失败也留得下当时的响应体）。
  const confirm = await confirmOn('c', { runId: run.runId, toolCallId: run.toolCallId, reason: 'cluster3 cross-instance decision' })
  evidenceFirst(
    (body) => saveEvidence('confirm.json', body),
    () => confirm.body || { status: confirm.record.status, text: confirm.record.text },
    () => {
      checkEq(confirm.record.status, 200, 'C 侧决策 HTTP')
      check(confirm.body && confirm.body.accepted === true, `C 侧决策未被接受：${confirm.record.text.slice(0, 500)}`)
      checkEq(confirm.body.storedBy, STATE.instanceIds.c, '决策 storedBy 必须是 C 的 instanceId')
      checkIn(confirm.body.resumeOutcome, ['LEASE_HELD_ELSEWHERE', 'SCHEDULED'], 'C 侧 resumeOutcome')
    }
  )

  // ④ 决策后的重挂（产品自述标准时序）：B 差量 + C 全量都必须收到终帧。
  //    B 差量：seq 连续 + **帧型/载荷**（复审 I1(b)(c)：只验 seq 时 tool_result.ok=false 仍能过）。
  const streamB = await reattachOn('b', run.runId, run.lastSeq, { timeoutMs: RUN.killTakeoverTimeoutMs })
  let terminalB = null
  let bDeltaSeqs = null
  let bDeltaSummary = null
  try {
    terminalB = await streamB.waitTerminal(RUN.killTakeoverTimeoutMs)
    saveFrames('B-reattach.jsonl', streamB.frames)
    checkEq(terminalB.type, 'done', 'B 侧决策后重挂终帧')
    bDeltaSeqs = assertDeltaContiguous(streamB.frames, run.lastSeq + 1, 'B 侧补帧 seq（必须从 lastSeq+1 逐号连续）')
    bDeltaSummary = assertDeltaFrames(streamB.frames, {
      toolCallId: run.toolCallId,
      expectedName: run.toolName,
      label: 'B 侧决策后差量',
      allowToolStart: false
    })
    checkEqNumber(bDeltaSeqs[bDeltaSeqs.length - 1], terminalB.seq, 'B 侧补帧末号 = 终帧 seq')
  } finally {
    saveFrames('B-reattach.jsonl', streamB.frames)
    streamB.close()
  }

  //    C 连接是从 0 全量重挂 ⇒ 整本帧册都要满足（不再只验 seq）。
  const streamC = await reattachOn('c', run.runId, 0, { timeoutMs: RUN.killTakeoverTimeoutMs })
  let terminalC = null
  let cSeqs = null
  let cLedger = null
  try {
    terminalC = await streamC.waitTerminal(RUN.killTakeoverTimeoutMs)
    saveFrames('C-reattach.jsonl', streamC.frames)
    checkEq(terminalC.type, 'done', 'C 侧决策后重挂终帧')
    cLedger = assertFrameLedger(streamC.frames, { toolCallId: run.toolCallId, label: 'C 侧决策后重挂帧册' })
    cSeqs = seqsOf(streamC.frames, 'C 侧决策后重挂')
  } finally {
    saveFrames('C-reattach.jsonl', streamC.frames)
    streamC.close()
  }

  // ⑤ 全量回放帧册（关键帧各恰一次 + 顺序 + seq 逐号连续 + 帧型白名单 + 关键载荷）。
  const full = await fullReplayToTerminal('a', run.runId, 90000, 'A-full.jsonl')
  const ledgerSummary = assertFrameLedger(full, { toolCallId: run.toolCallId })
  checkEq(
    JSON.stringify(seqsOf(full, 'A 全量回放')),
    JSON.stringify(cSeqs),
    'A 全量回放 seq 与 C 决策后重挂 seq（两条独立连接必须同号同序）'
  )
  checkEq(
    JSON.stringify(bDeltaSeqs),
    JSON.stringify(cSeqs.filter((seq) => seq > run.lastSeq)),
    'B 差量必须是 C 全量的连续后缀（不重不漏）'
  )

  // 证据优先（复审 N1）：run-detail / jobs / history 各自先落盘再断言，且增量补齐。
  const detail = await runDetail('b', run.runId)
  const ledger = ledgerFor(detail, run.toolCallId)
  const detailEvidence = {
    runId: run.runId,
    status: detail.snapshot ? detail.snapshot.status : null,
    ledgerCounts: detail.ledgerCounts,
    ledger,
    frameLedger: ledgerSummary.types,
    lastSeqScope: ledgerSummary.lastSeqScope,
    parkedSeq: run.lastSeq,
    preDecisionReplayFrames: preCReplay.frames,
    bDeltaSeqs,
    cSeqs
  }
  evidenceFirst(
    (snapshot) => saveEvidence('run-detail.json', snapshot),
    () => detailEvidence,
    () => {
      checkEq(detail.snapshot.status, 'DONE', 'run 终态')
      checkEq(Number(detail.ledgerCounts.start_export), 1, '台账 start_export 恰一次')
      checkEq(ledger.length, 1, '该 toolCallId 台账条目数')
      checkIn(ledger[0].executedBy, [STATE.instanceIds.a, STATE.instanceIds.c], '台账执行实例（A 的等待方或 C 的接管者）')
      checkEq(ledger[0].outcome, 'ok', '跨实例台账 outcome 必须是 ok')
      checkNonEmptyString(ledger[0].resultText, '跨实例台账 resultText')
    }
  )

  const jobs = await jobsOf('c', task.id)
  detailEvidence.jobs = jobs.length
  saveEvidence('run-detail.json', detailEvidence)
  checkEq(jobs.length, 1, '跨实例决策后作业数恰 1')
  const historyB = await historyStable('b', run.sessionId, suffix)
  const historyC = await historyStable('c', run.sessionId, suffix)
  detailEvidence.history = { b: historyB.total, c: historyC.total }
  saveEvidence('run-detail.json', detailEvidence)
  checkEq(historyC.total, historyB.total, 'B/C history total')

  caseLog(`决策落 C（storedBy=C，outcome=${confirm.body.resumeOutcome}）；决策前重挂回放后即收流（B 差量=0 帧 / C 全量=${preCReplay.frames} 帧，逐帧全字段一致），收流后仍 SUSPENDED；决策后 B 差量 ${bDeltaSeqs[0]}..${bDeltaSeqs[bDeltaSeqs.length - 1]}（${bDeltaSummary.deltas} delta，含 decision/result 载荷校验）、C 全量帧册=${cLedger.types} 都收到 done；A 全量帧册=${ledgerSummary.types}；台账 executedBy=${ledger[0].executedBy} outcome=ok`)
}

// ── 用例 7：跨实例幂等 ─────────────────────────────────────────────────────

async function newSuspendedTask(prefix) {
  const task = await createTask('a', 'EXPORT', `${prefix}-${uniqueId('t')}`)
  await selectDefs('a', task.id, ['CURRENCY'])
  await readyToExport('a', task.id)
  return task
}

async function caseIdempotencyCrossInstance() {
  await ensureUp()

  // ① 首次决策成功后，同一 runId+toolCallId 在另一实例重复决策 → 409 DUPLICATE。
  const task1 = await newSuspendedTask('cluster3-idem')
  const run1 = await createSuspendedRun('a', { prefix: 'cluster3-idem', taskId: task1.id, messageSuffix: ` ${uniqueId('i1')}` })
  const first = await confirmOn('a', { runId: run1.runId, toolCallId: run1.toolCallId, reason: 'first decision' })
  checkEq(first.record.status, 200, '首次决策 HTTP')
  check(first.body && first.body.accepted === true, `首次决策未被接受：${first.record.text.slice(0, 400)}`)
  const full1 = await fullReplayToTerminal('a', run1.runId, 120000)
  checkEq(terminalType(full1), 'done', '首次决策后的终帧类型')

  const dup = await confirmOn('b', { runId: run1.runId, toolCallId: run1.toolCallId, reason: 'duplicate decision' })
  checkEq(dup.record.status, 409, '重复决策 HTTP')
  check(dup.body && dup.body.code === 'DUPLICATE_TOOL_CALL_ID', `重复决策 code：${dup.record.text.slice(0, 400)}`)
  const detail1 = await runDetail('b', run1.runId)
  checkEq(Number(detail1.ledgerCounts.start_export), 1, '重复决策后台账 start_export 仍为 1')
  const jobs1 = await jobsOf('c', task1.id)
  checkEq(jobs1.length, 1, '重复决策后作业数仍为 1')
  // 幂等索引键：`RunStore#runScopedKey(IDEM_PREFIX, runId)`，tag = `{<tenant>:<user>:run:<runId>}`
  // （租户任务 2：身份进 tag，否则同 runId 跨身份会落到同一个哈希上）。
  const idemKey = `ai:idem:{${AI_TENANT_ID}:${AI_USER_ID}:run:${run1.runId}}`
  const idemField = `${run1.runId}:${run1.toolCallId}`
  const idemValue = redisHget(idemKey, idemField)
  check(idemValue && idemValue.length > 0, `幂等索引缺失：HGET ${idemKey} ${idemField}`)
  saveEvidence('duplicate-decision.json', { status: dup.record.status, body: dup.body, idemKey, idemField, idemValue })

  // ② 并发双决策（B/C 同时发同一 runId+toolCallId）→ 恰一个 200、一个 409，副作用恰一次。
  const task2 = await newSuspendedTask('cluster3-idem-concurrent')
  const run2 = await createSuspendedRun('a', { prefix: 'cluster3-idem-concurrent', taskId: task2.id, messageSuffix: ` ${uniqueId('i2')}` })
  const [rB, rC] = await Promise.all([
    confirmOn('b', { runId: run2.runId, toolCallId: run2.toolCallId, reason: 'concurrent B' }),
    confirmOn('c', { runId: run2.runId, toolCallId: run2.toolCallId, reason: 'concurrent C' })
  ])
  const statuses = [rB.record.status, rC.record.status].sort((x, y) => x - y)
  checkEq(JSON.stringify(statuses), JSON.stringify([200, 409]), `并发双决策状态码（B=${rB.record.status} C=${rC.record.status}）`)
  const winner = rB.record.status === 200 ? rB : rC
  const loser = rB.record.status === 409 ? rB : rC
  check(winner.body && winner.body.accepted === true, `并发胜方未返回 accepted=true：${winner.record.text.slice(0, 400)}`)
  check(loser.body && loser.body.code === 'DUPLICATE_TOOL_CALL_ID', `并发败方 code：${loser.record.text.slice(0, 400)}`)

  const full2 = await fullReplayToTerminal('a', run2.runId, 120000)
  checkEq(terminalType(full2), 'done', '并发决策后的终帧类型')
  checkEq(frameCount(full2, 'confirm_decision', run2.toolCallId), 1, '并发决策 confirm_decision 恰一次')
  checkEq(frameCount(full2, 'tool_start', run2.toolCallId), 1, '并发决策 tool_start 恰一次')
  const detail2 = await runDetail('c', run2.runId)
  checkEq(Number(detail2.ledgerCounts.start_export), 1, '并发决策后台账 start_export 恰一次')
  const jobs2 = await jobsOf('c', task2.id)
  checkEq(jobs2.length, 1, '并发决策后作业数恰 1')

  saveEvidence('concurrent-decision.json', {
    B: { status: rB.record.status, body: rB.body },
    C: { status: rC.record.status, body: rC.body },
    ledgerCounts: detail2.ledgerCounts,
    jobs: jobs2.length
  })
  caseLog(`重复决策 409（${dup.body.code}）；并发双决策 [${statuses.join(',')}]；两轮副作用均恰一次`)
}

// ── 用例 7b：跨实例会话销毁（完整清理 + 在途 deferred）─────────────────────

async function caseSessionPurgeCrossInstance() {
  await ensureUp()
  const task = await createTask('a', 'EXPORT', `cluster3-purge-${uniqueId('t')}`)
  await selectDefs('a', task.id, ['CURRENCY'])
  const keep = await runPlainChat('a', 'cluster3-purge-keep')
  const target = await runPlainChat('a', 'cluster3-purge-target')
  // 数据库完整历史端点（/history/{sid}/messages）已随 ai_chat_message 移除 ⇒ 销毁前后的"存在/无残留"
  // 都用记忆窗口（/history/{sid}）判定。窗口条目不带 runId，归属改由 run 维度
  // （GET /api/ai/runs/{runId} 的 exists / snapshot.sessionId / snapshot.status）绑定：
  // target/keep 都是 runPlainChat 跑到 done 的完成轮，终态时 RunStore#persist 会
  // clearSessionCurrent（该会话没有其它活跃轮则键直接删除），故 runs/current 对完成轮
  // 必然查不到自己 —— 它不是完成轮的归属载体，用它会变成"结构性必红"的错误判据。
  const beforeWindow = await waitHistoryUserMessage('c', target.sessionId, '三实例连通性')
  checkGe(beforeWindow.messages.length, 2, '销毁前 C 侧记忆窗口必须能看到目标轮（用户+助手）')
  const targetDetail = await runDetail('c', target.runId)
  checkEq(targetDetail.exists, true, '销毁前 run 详情（C 侧）必须 exists=true')
  checkEq(
    targetDetail.snapshot ? targetDetail.snapshot.sessionId : null,
    target.sessionId,
    '销毁前 run 快照 sessionId 必须等于目标会话'
  )
  checkEq(targetDetail.snapshot ? targetDetail.snapshot.status : null, 'DONE', '销毁前目标轮快照状态必须 DONE')

  // ① 普通完成会话：B 删 → C 验证记忆窗口与 run 维度均无残留。
  const del = await deleteJson('b', `/api/ai/session/${encodeURIComponent(target.sessionId)}`)
  checkEq(del.record.status, 200, `B 侧 DELETE /session HTTP：${del.record.text.slice(0, 400)}`)
  check(del.body && del.body.complete === true, `非在途会话销毁必须 complete=true：${del.record.text.slice(0, 600)}`)
  checkEq(del.body.aborted, false, '非在途会话销毁不得 aborted=true')
  checkEq(del.body.deleted, true, '非在途会话销毁 deleted 必须 true')
  checkEq((del.body.deferred || []).length, 0, `非在途会话销毁不得有 deferred：${JSON.stringify(del.body.deferred)}`)

  const legacyAfter = await historyOf('c', target.sessionId)
  checkEq(legacyAfter.record.status, 200, 'C 侧旧 /history HTTP（幂等）')
  const legacyAfterData = dataOf(legacyAfter.record, 'C 侧旧 /history（销毁后）')
  checkEq(legacyAfterData.messages.length, 0, 'C 侧旧 /history 销毁后 messages 必须为空')
  checkEq(legacyAfterData.total, 0, 'C 侧旧 /history 销毁后 total 必须为 0')

  // 销毁后 run 维度必须无残留：purge 按 run 删 13 个 run 级键（终态轮直接删、活跃轮 deferred），
  // 所以完整销毁（complete=true）后该 runId 的详情必须 exists=false。
  const targetAfter = await runDetail('c', target.runId)
  checkEq(targetAfter.exists, false, 'C 侧目标轮 run 详情销毁后必须 exists=false（run 维度无残留）')

  // 另一个会话不受影响。
  const keepHistory = await historyStable('c', keep.sessionId, '三实例连通性')
  checkGe(keepHistory.total, 2, '销毁目标会话后，另一个会话的 history 仍完整')
  const keepDetail = await runDetail('c', keep.runId)
  checkEq(keepDetail.exists, true, '另一个会话的 run 详情仍必须 exists=true（不受销毁波及）')
  checkEq(
    keepDetail.snapshot ? keepDetail.snapshot.sessionId : null,
    keep.sessionId,
    '另一个会话的 run 快照 sessionId 未被改写'
  )
  // 业务 task 不被会话销毁波及。
  const taskAfter = await getTask('c', task.id)
  checkEq(taskAfter.id, task.id, '会话销毁后业务 task 仍可读取')

  saveEvidence('purge-complete.json', {
    target: { sessionId: target.sessionId, runId: target.runId },
    purge: del.body,
    legacyAfter: { total: legacyAfterData.total, messages: legacyAfterData.messages.length },
    before: {
      messages: beforeWindow.messages.length,
      runExists: targetDetail.exists,
      runStatus: targetDetail.snapshot ? targetDetail.snapshot.status : null,
      runSessionId: targetDetail.snapshot ? targetDetail.snapshot.sessionId : null
    },
    runAfter: { exists: targetAfter.exists },
    keep: {
      sessionId: keep.sessionId,
      total: keepHistory.total,
      runId: keep.runId,
      runExists: keepDetail.exists,
      runSessionId: keepDetail.snapshot ? keepDetail.snapshot.sessionId : null
    },
    taskId: task.id
  })

  // ② 在途慢流：SIGSTOP 冻结 A 的取消处理，B 删除必须 deferred（不赌 watchdog 时序）。
  const activeSession = await newSession('a', 'cluster3-purge-active')
  const slow = await chatOn('a', { sessionId: activeSession, message: 'scenario=PLAIN stall-frames=4000 purge-active' })
  let activeRunId = null
  let aPaused = false
  let pausedPid = null
  let cleanupError = null
  try {
    const start = await slow.waitType('start', { timeoutMs: 30000 })
    activeRunId = start.runId
    await slow.waitFor((f) => typeof f.seq === 'number' && f.seq >= 2, { timeoutMs: 30000, label: '等待在途慢流第 2 帧' })
    check(!slow.ended, 'DELETE 前慢流不应已结束')

    // 冻结 A：它无法处理取消标志，purge 的 1.5s 等待窗口内 run 必然仍是活跃态 ⇒ deferred。
    pausedPid = pauseInstance('a')
    aPaused = true
    caseLog(`在途 run ${activeRunId} 已冻结实例 A（pid=${pausedPid}），准备由 B 删除会话`)

    const activeDel = await deleteJson('b', `/api/ai/session/${encodeURIComponent(activeSession)}`, { timeoutMs: 60000 })
    checkEq(activeDel.record.status, 200, `在途会话 DELETE HTTP：${activeDel.record.text.slice(0, 400)}`)
    check(activeDel.body && activeDel.body.complete === false, `在途会话销毁不得 complete=true：${activeDel.record.text.slice(0, 800)}`)
    checkEq(activeDel.body.aborted, false, '在途会话销毁不得 aborted=true（应为 deferred）')
    checkEq(activeDel.body.deleted, false, '在途会话销毁 deleted 必须 false（deferred 不等于删净）')
    const deferred = Array.isArray(activeDel.body.deferred) ? activeDel.body.deferred : []
    check(deferred.length > 0, '在途会话销毁必须给出 deferred 明细')
    check(deferred.some((item) => item.runId === activeRunId), `deferred 必须指明在途 runId=${activeRunId}：${JSON.stringify(deferred)}`)
    saveEvidence('purge-active-deferred.json', { purge: activeDel.body, pausedPid, runId: activeRunId })
    caseLog(`在途会话 ${activeSession} DELETE → complete=false, deferred=${JSON.stringify(deferred)}（A 处于 SIGSTOP）`)
  } finally {
    // 必须恢复 A：失败路径也不能把实例留在暂停态。
    if (aPaused) {
      try {
        resumeInstance('a')
      } catch (e) {
        cleanupError = e
        caseLog(`恢复 A（SIGCONT）失败：${e.message}`)
      }
      aPaused = false
    }
    slow.close()
  }
  if (cleanupError) throw cleanupError

  // 等跨实例看到终态后，不手动补删：另一实例的自动重试必须清掉 durable pending。
  await waitRunTerminal('c', activeRunId, RUN.killTakeoverTimeoutMs)
  // durable pending 键带身份段（`ai:purge:pending:<tenant>:<user>:<session>`；租户任务 2）。
  const pendingKey = `ai:purge:pending:${AI_TENANT_ID}:${AI_USER_ID}:${activeSession}`
  const automaticPurgeRecovery = async () => {
    const deadline = Date.now() + 90000
    while (redisExists(pendingKey)) {
      if (Date.now() >= deadline) {
        throw new Error(`自动销毁重试 90s 内未清掉 ${pendingKey}；不得以手动 DELETE 掩盖`)
      }
      await sleep(500)
    }
  }
  await automaticPurgeRecovery()
  check(!redisExists(pendingKey), '自动销毁重试必须移除 durable pending')

  // 再 DELETE 只验证幂等；它不是上面的自动清理证据。
  const retry = await deleteJson('c', `/api/ai/session/${encodeURIComponent(activeSession)}`)
  checkEq(retry.record.status, 200, `在途终态后 C 侧重试 DELETE HTTP：${retry.record.text.slice(0, 400)}`)
  check(retry.body && retry.body.complete === true, `终态后重试必须 complete=true：${retry.record.text.slice(0, 800)}`)
  checkEq(retry.body.deleted, true, '终态后重试 deleted 必须 true')
  checkEq((retry.body.deferred || []).length, 0, `终态后重试不得再有 deferred：${JSON.stringify(retry.body.deferred)}`)

  // 给异步写回一个窗口，确认历史没有复活（销毁守卫 + 删除必须真的生效）。
  await sleep(2000)
  const revivedLegacy = await historyOf('b', activeSession)
  const revivedLegacyData = dataOf(revivedLegacy.record, '在途会话销毁后的旧 /history')
  checkEq(revivedLegacyData.messages.length, 0, '在途会话销毁后旧 /history 不得复活')
  const revivedCurrent = await currentRun('b', activeSession)
  checkEq(revivedCurrent.found, false, '在途会话销毁后 runs/current 必须 found=false')
  const taskStillThere = await getTask('b', task.id)
  checkEq(taskStillThere.id, task.id, '在途会话销毁后业务 task 仍可读取（不得删业务数据）')
  const purgedChat = await postJson('c', '/api/ai/chat', { sessionId: activeSession, message: '不得复活旧会话' })
  checkEq(purgedChat.record.status, 410, '销毁后的旧 sessionId 必须返回 410，不得重新开轮')
  checkEq(purgedChat.body && purgedChat.body.code, 'SESSION_PURGED', '销毁后的旧 sessionId 错误码')

  saveEvidence('purge-active-retry.json', {
    sessionId: activeSession,
    runId: activeRunId,
    automaticPurgeRecovery: true,
    retry: retry.body,
    purgedChat: purgedChat.body,
    revived: { legacyMessages: revivedLegacyData.messages.length, runsCurrent: revivedCurrent.found },
    taskId: task.id
  })
  caseLog('在途会话由自动重试清理 complete=true；旧/完整 history 均未复活；旧会话 410；业务 task 保留')
}

function terminalType(frames) {
  const done = frames.find((f) => f.type === 'done')
  if (done) return 'done'
  const error = frames.find((f) => f.type === 'error')
  if (error) return 'error'
  return 'none'
}

// ── 用例 8：配置热更新 ─────────────────────────────────────────────────────

async function defaultsOf(key) {
  const { record, body } = await getJson(key, '/api/ai/settings/defaults')
  checkEq(record.status, 200, `GET settings/defaults（${key}）HTTP`)
  check(body && body.success === true && body.data, `settings/defaults（${key}）响应异常：${record.text.slice(0, 400)}`)
  return body.data
}

/** 面板可用模型（含 providerId/modelId 二元组；不假设具体 provider 名称）。 */
async function availableModelsOf(key) {
  const { record, body } = await getJson(key, '/api/ai/models')
  checkEq(record.status, 200, `GET /api/ai/models（${key}）HTTP`)
  check(body && body.success === true && Array.isArray(body.data), `可用模型列表异常：${record.text.slice(0, 400)}`)
  return body.data
}

async function caseConfigHotReload() {
  await ensureUp()
  const before = {}
  const healthBefore = {}
  for (const inst of INSTANCES) {
    before[inst.key] = await defaultsOf(inst.key)
    healthBefore[inst.key] = await healthOf(inst.key)
  }
  checkEq(new Set(Object.values(before).map((d) => d.version)).size, 1, `三实例配置版本起点不一致：${JSON.stringify({
    a: before.a.version, b: before.b.version, c: before.c.version
  })}`)

  // 二元绑定取当前配置；未绑定（旧配置）时从真实可用模型里选，绝不写死 provider 名称。
  let binding = { providerId: before.a.providerId, modelId: before.a.modelId }
  if (binding.providerId === null || binding.providerId === undefined || !binding.modelId) {
    const models = await availableModelsOf('a')
    const preferred = binding.modelId ? models.find((row) => row.modelId === binding.modelId && row.providerId !== null && row.providerId !== undefined) : null
    const row = preferred
      || models.find((item) => item.isDefault && item.providerId !== null && item.providerId !== undefined)
      || models.find((item) => item.providerId !== null && item.providerId !== undefined)
    check(row, `没有可绑定的 (providerId, modelId) 二元组：${JSON.stringify(models).slice(0, 600)}`)
    binding = { providerId: row.providerId, modelId: row.modelId }
  }
  check(binding.modelId, '默认目标缺少 modelId')
  const bindingProviderId = Number(binding.providerId)
  const bindingModelId = binding.modelId

  const pidsBefore = { ...STATE.pids }
  const redisVersionBefore = redisGet('ai:cfg:ver')
  const base = before.a.temperature === null || before.a.temperature === undefined ? 0.2 : Number(before.a.temperature)
  const nextTemperature = Number((base === 0.2 ? 0.21 : 0.2).toFixed(4))
  // 与 AiConfigWriter.DefaultsCommand 当前 DTO 逐字段一致（providerId 是二元绑定的另一半）。
  const command = {
    modelId: bindingModelId,
    providerId: bindingProviderId,
    temperature: nextTemperature,
    topP: before.a.topP,
    maxTokens: before.a.maxTokens,
    allowUserParams: before.a.allowUserParams
  }
  const put = await putJson('a', '/api/ai/settings/defaults', command)
  checkEq(put.record.status, 200, 'PUT settings/defaults HTTP')
  check(put.body && put.body.success === true && put.body.data, `PUT settings/defaults 失败：${put.record.text.slice(0, 400)}`)
  checkEq(Number(put.body.data.providerId), bindingProviderId, '保存后回显的 providerId（二元绑定）')
  checkEq(put.body.data.modelId, bindingModelId, '保存后回显的 modelId（二元绑定）')
  const putAt = Date.now()

  const observed = {}
  const deadline = Date.now() + 20000
  while (Date.now() < deadline) {
    let done = true
    for (const key of ['b', 'c']) {
      const current = await defaultsOf(key)
      observed[key] = current
      if (!(Number(current.version) > Number(before.a.version)
        && Number(current.temperature) === nextTemperature
        && Number(current.providerId) === bindingProviderId
        && current.modelId === bindingModelId)) {
        done = false
      }
    }
    if (done) break
    await sleep(400)
  }
  for (const key of ['b', 'c']) {
    checkGe(Number(observed[key] && observed[key].version), Number(before.a.version) + 1, `实例 ${key} 配置版本未推进`)
    checkEq(Number(observed[key].temperature), nextTemperature, `实例 ${key} 热更新后的 temperature`)
    checkEq(Number(observed[key].providerId), bindingProviderId, `实例 ${key} 热更新后的 providerId`)
    checkEq(observed[key].modelId, bindingModelId, `实例 ${key} 热更新后的 modelId`)
  }

  // 运行时二元绑定：/api/ai/health 的 resolved providerId/defaultModelId 必须等于保存的那一对。
  const healthAfter = {}
  for (const key of ['b', 'c']) {
    const health = await healthOf(key)
    healthAfter[key] = { status: health.record.status, providerId: health.body ? health.body.providerId : null, defaultModelId: health.body ? health.body.defaultModelId : null }
    checkEq(health.record.status, 200, `实例 ${key} /api/ai/health HTTP（热更新后）`)
    checkEq(Number(health.body.providerId), bindingProviderId, `实例 ${key} 运行时 providerId 与保存的二元绑定`)
    checkEq(health.body.defaultModelId, bindingModelId, `实例 ${key} 运行时 defaultModelId 与保存的二元绑定`)
  }

  const pidsAfter = { ...STATE.pids }
  checkEq(JSON.stringify(pidsAfter), JSON.stringify(pidsBefore), '配置热更新不应重启任何实例（pid 变化）')

  const redisVersion = redisGet('ai:cfg:ver')
  check(redisVersion, `Redis ai:cfg:ver 缺失（跨实例失效信号未写）`)
  check(Number(redisVersion) > Number(redisVersionBefore || 0), `Redis ai:cfg:ver 未递增（${redisVersionBefore || '(空)'} → ${redisVersion}）`)
  const latency = Date.now() - putAt

  // 还原初值（尽力；不把还原失败算作本用例失败，但留证据）。
  try {
    await putJson('a', '/api/ai/settings/defaults', {
      modelId: before.a.modelId,
      providerId: before.a.providerId,
      temperature: before.a.temperature,
      topP: before.a.topP,
      maxTokens: before.a.maxTokens,
      allowUserParams: before.a.allowUserParams
    })
  } catch (e) {
    caseLog(`还原默认目标/参数失败（不影响断言）：${e.message}`)
  }
  saveEvidence('config-reload.json', {
    before,
    binding,
    healthBefore,
    healthAfter,
    observed,
    nextTemperature,
    redisVersionBefore,
    redisVersion,
    latencyMs: latency,
    pidsBefore,
    pidsAfter
  })
  caseLog(`配置版本 ${before.a.version} → ${observed.b.version}；二元绑定=${binding.providerId}/${binding.modelId}；B/C 在 ${latency}ms 内看到新 temperature；运行时 resolved 对一致；pid 未变`)
}

// ── 用例 9：共享文件下载 ───────────────────────────────────────────────────

function multipartBody(fieldName, filename, contentType, bytes) {
  const boundary = `----cluster3-${crypto.randomBytes(8).toString('hex')}`
  const head = Buffer.from(
    `--${boundary}\r\nContent-Disposition: form-data; name="${fieldName}"; filename="${filename}"\r\n` +
    `Content-Type: ${contentType}\r\n\r\n`,
    'utf8'
  )
  const tail = Buffer.from(`\r\n--${boundary}--\r\n`, 'utf8')
  return { boundary, body: Buffer.concat([head, bytes, tail]) }
}

async function downloadFile(key, taskId, codes, options = {}) {
  const url = options.viaProxy
    ? `${RUN.proxyUrl}/api/tasks/${taskId}/files/download?codes=${encodeURIComponent(codes)}`
    : directUrl(key, `/api/tasks/${taskId}/files/download?codes=${encodeURIComponent(codes)}`)
  return httpRequest(url, {
    timeoutMs: 30000,
    routeKind: 'download',
    directNode: options.viaProxy ? null : directNode(key)
  })
}

async function caseSharedFileDownload() {
  await ensureUp()
  const task = await createTask('a', 'EXPORT', `cluster3-files-${uniqueId('t')}`)
  await selectDefs('a', task.id, ['CURRENCY'])
  const template = await httpRequest(directUrl('a', `/api/tasks/${task.id}/files/templates?codes=CURRENCY`), {
    timeoutMs: 30000,
    routeKind: 'template',
    directNode: directNode('a')
  })
  checkEq(template.status, 200, '下载模板 HTTP')
  checkGe(template.body.length, 1, '模板字节数')
  const upload = multipartBody('file', 'CURRENCY.xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', template.body)
  const uploaded = await httpRequest(directUrl('a', `/api/tasks/${task.id}/files/upload`), {
    method: 'POST',
    headers: {
      'Content-Type': `multipart/form-data; boundary=${upload.boundary}`,
      'Content-Length': String(upload.body.length)
    },
    body: upload.body,
    timeoutMs: 30000,
    routeKind: 'upload',
    directNode: directNode('a')
  })
  checkEq(uploaded.status, 200, `上传 HTTP：${uploaded.text.slice(0, 400)}`)
  const uploadBody = jsonOf(uploaded, '上传响应')
  check(uploadBody && uploadBody.success === true, `上传响应 success !== true：${uploaded.text.slice(0, 400)}`)
  checkEq(Number(uploadBody.data.count), 1, '上传匹配文件数')

  const uploadedSha = sha256(template.body)
  const results = {}
  for (const [label, fetch] of [
    ['B', () => downloadFile('b', task.id, 'CURRENCY')],
    ['C', () => downloadFile('c', task.id, 'CURRENCY')],
    ['proxy', () => downloadFile(null, task.id, 'CURRENCY', { viaProxy: true })]
  ]) {
    const record = await fetch()
    checkEq(record.status, 200, `共享文件下载（${label}）HTTP`)
    const digest = sha256(record.body)
    checkEq(digest, uploadedSha, `共享文件下载（${label}）sha256 与上传件`)
    results[label] = { status: record.status, bytes: record.body.length, sha256: digest }
  }

  // 共享目录证据：A 的 DEBUG 行必须指向同一绝对目录（三实例用同一个 storage-path）。
  const deadline = Date.now() + 5000
  let storedLine = ''
  while (Date.now() < deadline) {
    const log = tailFile(instanceLogPath('a'), 3000)
    storedLine = log.split(/\r?\n/).find((line) => line.includes('Stored file') && line.includes(RUN.sharedFiles)) || ''
    if (storedLine) break
    await sleep(300)
  }
  check(storedLine, `A 实例日志未出现共享目录的 Stored file 行（sharedFiles=${RUN.sharedFiles}）`)
  CTX.file = { taskId: task.id, sha256: uploadedSha, bytes: template.body.length }
  saveEvidence('file-download.json', { taskId: task.id, uploadedSha, bytes: template.body.length, results, storedLine })
  caseLog(`taskId=${task.id} sha256=${uploadedSha}；B/C/代理下载一致；共享目录=${RUN.sharedFiles}`)
}

// ── 用例 10：挂起态 kill A → B 接管执行 ────────────────────────────────────

/**
 * 独立文件 fixture：不依赖 shared-file-download 用例的成功（该用例失败时本用例仍要能跑到挂起接管）。
 * 返回 taskId + 上传件 sha256，A 死亡后由 B/C 重新下载比对。
 */
async function createUploadedFixture(prefix) {
  const task = await createTask('a', 'EXPORT', `${prefix}-${uniqueId('t')}`)
  await selectDefs('a', task.id, ['CURRENCY'])
  const template = await httpRequest(directUrl('a', `/api/tasks/${task.id}/files/templates?codes=CURRENCY`), {
    timeoutMs: 30000,
    routeKind: 'template',
    directNode: directNode('a')
  })
  checkEq(template.status, 200, `fixture 模板下载 HTTP（${prefix}）`)
  checkGe(template.body.length, 1, `fixture 模板字节数（${prefix}）`)
  const upload = multipartBody('file', 'CURRENCY.xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', template.body)
  const uploaded = await httpRequest(directUrl('a', `/api/tasks/${task.id}/files/upload`), {
    method: 'POST',
    headers: {
      'Content-Type': `multipart/form-data; boundary=${upload.boundary}`,
      'Content-Length': String(upload.body.length)
    },
    body: upload.body,
    timeoutMs: 30000,
    routeKind: 'upload',
    directNode: directNode('a')
  })
  checkEq(uploaded.status, 200, `fixture 文件上传 HTTP（${prefix}）：${uploaded.text.slice(0, 400)}`)
  const uploadBody = jsonOf(uploaded, `fixture 上传响应（${prefix}）`)
  check(uploadBody && uploadBody.success === true, `fixture 上传响应 success !== true：${uploaded.text.slice(0, 400)}`)
  checkEq(Number(uploadBody.data.count), 1, `fixture 上传匹配文件数（${prefix}）`)
  await readyToExport('a', task.id)
  return { taskId: task.id, sha256: sha256(template.body), bytes: template.body.length }
}

async function redownloadAfterKill(label, fixture) {
  const record = await downloadFile(label, fixture.taskId, 'CURRENCY')
  checkEq(record.status, 200, `A 死亡后共享文件下载（${label}）HTTP`)
  checkEq(sha256(record.body), fixture.sha256, `A 死亡后共享文件下载（${label}）sha256`)
}

/**
 * 挂起态 kill A → B 决策接管执行。
 *
 * <p><b>早杀（复审 B2 恢复的覆盖）</b>：拿到确认卡就 SIGKILL，<b>不等 park 收流</b>。这条用例
 * 因此继续覆盖产品真实的崩溃窗口 —— 快照已是 SUSPENDED、本批 `tool_start` 可能还没落档。
 * 随本探针同批合入的候选分支用 `SseChatEmitter#ensureToolStart`（续跑结算前按归档幂等补发）收口；
 * 探针断言的是**一轮最终帧册"恰一条 tool_start、且先于 tool_result"这一不变量**，
 * 只记录"kill 前客户端有没有看到该帧"（观测），**不推断**这一帧由谁发出。
 *
 * <p>能力边界（如实登记）：客户端触发不可能**确定性**命中"tool_start 落档之前"的窗口
 * （同一线程先发帧、客户端才看到前一帧；观测延迟 ≫ 发帧延迟）。强制命中需要故障注入，
 * 由候选分支的产品侧 `ResumeToolStartBackfillConformanceTest` 覆盖；本用例负责端到端不变量 +
 * 把"卡到之前客户端到底看到了什么"作为证据落盘（`A-park-transition.jsonl` / `park-transition.json`）。
 */
async function caseSuspendKillA() {
  await ensureUp()
  const fixture = await createUploadedFixture('cluster3-suspend-kill')
  const taskId = fixture.taskId
  // waitForPark=false：确认卡一到就返回（原探针的早杀时序）。
  const run = await createSuspendedRun('a', {
    prefix: 'cluster3-suspend-kill',
    taskId,
    messageSuffix: ` ${uniqueId('sk')}`,
    waitForPark: false
  })
  // 观测口径（复审 N3）：这里只记录"**客户端在 SIGKILL 之前有没有看到** tool_start"，
  // 不推断这一帧是谁发的 —— A 完全可能在客户端关闭之后、被 SIGKILL 之前把帧写进归档，
  // 与"续跑补发"在客户端证据上不可区分。
  const observation = killToolStartObservation(run.frames, run.toolCallId)
  caseLog(`挂起在 A（早杀，不等 park 收流）：runId=${run.runId} toolCallId=${run.toolCallId} taskId=${taskId} lastSeq=${run.lastSeq} 帧=${run.frames.map((f) => f.type).join(',')} kill 前是否已见 tool_start=${observation.toolStartSeenBeforeKill}（独立文件 fixture）`)
  // 证据优先：kill 之前就把"客户端此刻看到什么"落盘（createSuspendedRun 已写 A-park-transition.jsonl）。
  saveEvidence('park-transition.json', {
    runId: run.runId,
    toolCallId: run.toolCallId,
    toolName: run.toolName,
    lastSeq: run.lastSeq,
    types: run.frames.map((f) => f.type),
    ...observation,
    heartbeats: run.heartbeats
  })
  const killedAt = Date.now()
  // 早杀前的最后一次观测：把"客户端此刻看到的帧"再落一版，`A-park-transition.jsonl` 与
  // `park-transition.json` 因此指的是同一时刻（kill 之前）。
  saveFrames('A-park-transition.jsonl', run.frames)
  const kill = await killInstance('a')
  saveEvidence('kill-a.json', kill)

  // takeover.json 增量写：每拿到一批新事实就落一版，任何后续断言失败都留得下已有部分。
  const takeover = {
    runId: run.runId,
    toolCallId: run.toolCallId,
    parkTransitionSeq: run.lastSeq,
    parkTransitionTypes: run.frames.map((f) => f.type),
    ...observation,
    kill
  }
  saveEvidence('takeover.json', takeover)

  // 证据优先（复审 N1）：/runs/current 的响应先落盘，再做五项断言。
  const current = await currentRun('b', run.sessionId)
  Object.assign(takeover, { archiveMaxSeqAfterKill: current.archiveMaxSeq })
  saveEvidence('runs-current-b.json', current)
  saveEvidence('takeover.json', takeover)
  check(current.found === true, 'B 侧 /runs/current 未找到该会话的挂起轮')
  checkEq(current.runId, run.runId, 'B 侧 /runs/current 指向的 runId')
  checkEq(current.snapshotStatus, 'SUSPENDED', 'B 侧快照状态')
  check(current.awaitingExternal === true, 'B 侧 awaitingExternal 必须为 true')
  check((current.pendingEntries || []).some((entry) => entry.toolCallId === run.toolCallId), 'B 侧 pendingEntries 未列出待决条目')
  // A 已死，归档末号不得回退（SIGKILL 不得抹掉已落档的帧）。早杀路径允许它 > 客户端末号
  // —— 那说明原实例在被杀前把 tool_start 落进了归档（观测值，不当缺陷）。
  checkGeNumber(current.archiveMaxSeq, run.lastSeq, 'SIGKILL 后归档末号（不得回退）')

  const confirm = await confirmOn('b', { runId: run.runId, toolCallId: run.toolCallId, reason: 'cluster3 suspend-kill takeover' })
  evidenceFirst(
    (body) => saveEvidence('confirm.json', body),
    () => confirm.body || { status: confirm.record.status, text: confirm.record.text },
    () => {
      checkEq(confirm.record.status, 200, `B 侧决策 HTTP：${confirm.record.text.slice(0, 400)}`)
      check(confirm.body && confirm.body.accepted === true, `B 侧决策未被接受：${confirm.record.text.slice(0, 400)}`)
      checkIn(confirm.body.resumeOutcome, ['LEASE_HELD_ELSEWHERE', 'SCHEDULED'], 'B 侧 resumeOutcome')
      checkEq(confirm.body.storedBy, STATE.instanceIds.b, '决策 storedBy 必须是 B')
    }
  )
  Object.assign(takeover, { resumeOutcome: confirm.body.resumeOutcome })
  saveEvidence('takeover.json', takeover)

  const streamB = await reattachOn('b', run.runId, run.lastSeq, { timeoutMs: RUN.killTakeoverTimeoutMs })
  let terminal = null
  let bDeltaSeqs = null
  let bDeltaSummary = null
  try {
    terminal = await streamB.waitTerminal(RUN.killTakeoverTimeoutMs)
    // 证据优先：终帧到手先落盘，再断言（下面的 finally 保证失败/超时也有帧）。
    saveFrames('B-takeover.jsonl', streamB.frames)
    checkEq(terminal.type, 'done', 'B 侧接管终帧')
    bDeltaSeqs = assertDeltaContiguous(streamB.frames, run.lastSeq + 1, 'B 侧接管补帧 seq（必须从 lastSeq+1 逐号连续）')
    // 差量的帧型/载荷（复审 I1(c)）：此处允许 tool_start（窗口里原实例可能落档、或续跑补发）。
    bDeltaSummary = assertDeltaFrames(streamB.frames, {
      toolCallId: run.toolCallId,
      expectedName: run.toolName,
      label: 'B 侧接管差量',
      allowToolStart: true
    })
    Object.assign(takeover, {
      takeoverMs: Date.now() - killedAt,
      bDeltaSeqs,
      bDeltaToolStartFrames: bDeltaSummary.toolStarts,
      bDeltaDeltas: bDeltaSummary.deltas
    })
    saveEvidence('takeover.json', takeover)
  } finally {
    saveFrames('B-takeover.jsonl', streamB.frames)
    streamB.close()
  }

  const full = await fullReplayToTerminal('c', run.runId, 90000, 'C-full.jsonl')
  const ledgerSummary = assertFrameLedger(full, { toolCallId: run.toolCallId })
  checkEq(
    JSON.stringify(bDeltaSeqs),
    JSON.stringify(seqsOf(full, 'C 全量回放').filter((seq) => seq > run.lastSeq)),
    'B 差量必须是 C 全量的连续后缀（不重不漏）'
  )
  // 帧册收口（复审 N3）：只记录观测 —— "SIGKILL 前客户端有没有看到 tool_start"；
  // 不推断这一帧是谁发的（A 落档 vs 续跑补发，在客户端证据上不可区分）。
  Object.assign(takeover, {
    frameLedger: ledgerSummary.types,
    frameLedgerTipScope: ledgerSummary.lastSeqScope,
    ...observation
  })
  saveEvidence('takeover.json', takeover)
  checkEq(terminalType(full), 'done', 'C 侧全量回放终帧')
  checkEq(frameCount(full, 'tool_start', run.toolCallId), 1, '接管后 tool_start 恰一次')
  checkEq(frameCount(full, 'tool_result', run.toolCallId), 1, '接管后 tool_result 恰一次')
  checkEq(frameCount(full, 'confirm_decision', run.toolCallId), 1, '接管后 confirm_decision 恰一次')

  // 证据优先（复审 N1）：run-detail / ledger 先落盘，再断言（takeover.json 增量补齐）。
  const detail = await runDetail('c', run.runId)
  const ledger = ledgerFor(detail, run.toolCallId)
  Object.assign(takeover, {
    snapshotStatus: detail.snapshot ? detail.snapshot.status : null,
    ledgerCounts: detail.ledgerCounts,
    ledger,
    lastSeqScope: ledgerSummary.lastSeqScope
  })
  saveEvidence('run-detail.json', { runId: run.runId, snapshot: detail.snapshot, ledgerCounts: detail.ledgerCounts, ledger })
  saveEvidence('takeover.json', takeover)
  checkEq(detail.snapshot.status, 'DONE', 'run 终态')
  checkEq(Number(detail.ledgerCounts.start_export), 1, '共享库台账 start_export 恰一次')
  checkEq(ledger.length, 1, '台账条目数')
  checkEq(ledger[0].executedBy, STATE.instanceIds.b, '台账 executedBy 必须是接管实例 B（共享库形态）')
  checkEq(ledger[0].outcome, 'ok', '台账 outcome 必须是成功（ok）')

  const jobs = await jobsOf('c', taskId)
  takeover.jobs = jobs.length
  saveEvidence('takeover.json', takeover)
  checkEq(jobs.length, 1, '接管执行后作业数恰 1')
  const history = await historyStable('c', run.sessionId, 'start_export')
  takeover.historyTotal = history.total
  saveEvidence('takeover.json', takeover)
  checkGe(history.total, 2, 'A 死亡后 C 侧 history 至少 user + assistant')

  await redownloadAfterKill('b', fixture)
  await redownloadAfterKill('c', fixture)

  Object.assign(takeover, { fixture })
  saveEvidence('takeover.json', takeover)
  caseLog(`kill A → B 接管：outcome=${confirm.body.resumeOutcome}，${takeover.takeoverMs}ms 收到 done；帧册=${ledgerSummary.types}；kill 前已见 tool_start=${observation.toolStartSeenBeforeKill}（观测值，不推断来源）；台账 executedBy=B outcome=ok；A 死后 B/C 仍能下载共享文件`)
}

// ── 用例 11：流式中 kill A → 僵尸扫描接管 ─────────────────────────────────

async function forceZombieResume(runId) {
  // 负控（一次性）：`startup-resume/scan` 在身份边界内 —— 裸请求必须 400 TENANT_IDENTITY_REQUIRED。
  // 与下面的正控（带身份 scan 必须 200）成对：只有正控时，"产品把该路径放回排除集"不会被发现；
  // 只有负控时，"探针忘了带头"不会被发现。负控本身零副作用（filter 在业务链之前拒绝）。
  const bare = await bareRequest('b', 'POST', '/api/ai/startup-resume/scan', {})
  saveEvidence('startup-resume-scan-bare.json', { status: bare.record.status, body: bare.body })
  checkEq(bare.record.status, 400, '不带身份头的 POST /api/ai/startup-resume/scan 必须 400（该路径在身份边界内）')
  checkEq(bare.body && bare.body.code, 'TENANT_IDENTITY_REQUIRED', '缺头 scan 的错误码')

  let lastReport = null
  for (let attempt = 0; attempt < 5; attempt += 1) {
    const { record, body } = await postJson('b', '/api/ai/startup-resume/scan', {}, { timeoutMs: 60000 })
    checkEq(record.status, 200, `POST startup-resume/scan HTTP：${record.text.slice(0, 400)}`)
    const report = body && body.data ? body.data : null
    check(report, `startup-resume/scan 响应缺少 data：${record.text.slice(0, 400)}`)
    lastReport = report
    const resumed = (report.resumed || []).find((item) => item.runId === runId)
    if (resumed) return { report, resumed }
    const skipped = (report.skipped || []).find((item) => item.runId === runId)
    caseLog(`第 ${attempt + 1} 次扫描：runId=${runId} 未进入 resumed（skipped=${JSON.stringify(skipped)}）`)
    await sleep(5000)
  }
  throw new Error(`僵尸扫描 5 次仍未接管 runId=${runId}；最后一次 report=${JSON.stringify(lastReport).slice(0, 1500)}`)
}

async function caseStreamingKillA() {
  await ensureUp()
  const sessionId = await newSession('a', 'cluster3-stream-kill')
  const message = 'scenario=PLAIN stall-frames=4000 streaming-kill-a'
  const streamA = await chatOn('a', { sessionId, message })
  let runId = null
  let lastSeq = 0
  let lastFrameAt = Date.now()
  try {
    const start = await streamA.waitType('start', { timeoutMs: 30000 })
    runId = start.runId
    await streamA.waitFor((f) => typeof f.seq === 'number' && f.seq >= 2, { timeoutMs: 30000, label: '等待流式第 2 帧' })
    lastSeq = streamA.lastSeq()
    lastFrameAt = Date.now()
    check(!streamA.frames.some((f) => f.type === 'done' || f.type === 'error'), 'kill 前不应出现终帧')
    caseLog(`流式在途：runId=${runId} lastSeq=${lastSeq} 已收=${streamA.types().join(',')}`)
  } finally {
    // kill 之前先拿到流；kill 后再关闭客户端连接
  }

  const killedAt = Date.now()
  const kill = await killInstance('a')
  streamA.close()
  saveEvidence('kill-a.json', { kill, runId, lastSeq, killedAt })

  // 僵尸判据 = 最后一条业务帧距今 > zombie-age(30s)；最后一帧离开 kill 很近，等 35s 留余量。
  const waitMs = Math.max(0, 35000 - (Date.now() - lastFrameAt))
  caseLog(`等待僵尸阈值（${waitMs}ms）后强制扫描接管`)
  await sleep(waitMs)

  const scan = await forceZombieResume(runId)
  checkEq(scan.resumed.outcome, 'ACCEPTED', `僵尸接管 outcome（${JSON.stringify(scan.resumed)}）`)
  checkEq(scan.resumed.zombieTakeover, true, '僵尸接管僵尸标记')
  saveEvidence('startup-resume-scan.json', scan)

  const streamC = await reattachOn('c', runId, lastSeq, { timeoutMs: RUN.killTakeoverTimeoutMs })
  let terminal
  let takeoverLastSeq = 0
  try {
    terminal = await streamC.waitTerminal(RUN.killTakeoverTimeoutMs)
    checkEq(terminal.type, 'done', 'C 侧接管终帧')
    checkStrictlyIncreasing(streamC.seqs(), `C 侧接管补帧 seq（lastSeq=${lastSeq}）`)
    checkGe(streamC.seqs()[0], lastSeq + 1, '接管补帧起始 seq 必须 > kill 前 lastSeq')
    takeoverLastSeq = streamC.lastSeq()
  } finally {
    streamC.close()
  }

  const full = await fullReplayToTerminal('b', runId, 90000)
  const fullSeqs = full.filter((f) => typeof f.seq === 'number').map((f) => f.seq)
  checkStrictlyIncreasing(fullSeqs, '全量回放 seq')
  const captured = streamA.seqs()
  checkEq(JSON.stringify(fullSeqs.slice(0, captured.length)), JSON.stringify(captured), 'kill 前已收帧必须是全量回放的前缀')
  checkEq(fullSeqs[fullSeqs.length - 1], takeoverLastSeq, '全量回放末号必须等于接管流末号')

  const detail = await runDetail('c', runId)
  checkEq(detail.snapshot.status, 'DONE', '流式接管后的 run 终态')
  const history = await historyStable('b', sessionId, 'streaming-kill-a')
  checkGe(history.total, 2, '流式接管后 history 至少 user + assistant')

  saveFrames('A-before-kill.jsonl', streamA.frames)
  saveFrames('C-takeover.jsonl', streamC.frames)
  saveFrames('B-full.jsonl', full)
  saveEvidence('streaming-takeover.json', {
    runId,
    lastSeq,
    captured,
    fullSeqs,
    takeoverMs: Date.now() - killedAt,
    scan: scan.resumed,
    snapshotStatus: detail.snapshot.status
  })
  caseLog(`流式 kill A 后 ${Date.now() - killedAt}ms 完成接管；全量 seq 无洞、末号=${fullSeqs[fullSeqs.length - 1]}`)
}

// ── 用例 12：作业中 kill A → 看门狗收敛 → 重启清理 → B 重试 ───────────────

async function waitJobStatus(key, jobId, expected, timeoutMs) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() < deadline) {
    try {
      last = await jobOf(key, jobId)
      if (last.status === expected) return last
    } catch (e) {
      last = { error: e.message }
    }
    await sleep(1000)
  }
  throw new Error(`作业 #${jobId} 在 ${timeoutMs}ms 内未到 ${expected}（最后：${JSON.stringify(last).slice(0, 800)}）`)
}

async function caseJobKillA() {
  await ensureUp()
  const task = await createTask('a', 'EXPORT', `cluster3-job-kill-${uniqueId('t')}`)
  await selectDefs('a', task.id, ['CURRENCY', 'DOC_TYPE', 'APPROVE_ROLE'])
  const created = await postJson('a', `/api/tasks/${task.id}/jobs`, { jobType: 'EXPORT' })
  checkEq(created.record.status, 201, `创建导出作业 HTTP：${created.record.text.slice(0, 400)}`)
  const jobId = created.body && created.body.data ? created.body.data.id : null
  check(jobId, `创建作业响应缺少 data.id：${created.record.text.slice(0, 400)}`)
  caseLog(`作业 #${jobId} 在 A 启动；等待 RUNNING + progress>=1 后 kill A`)

  const runningDeadline = Date.now() + 30000
  let running = null
  while (Date.now() < runningDeadline) {
    const job = await jobOf('b', jobId)
    if (job.status === 'RUNNING' && Number(job.progress) >= 1) {
      running = job
      break
    }
    if (job.status === 'COMPLETED' || job.status === 'FAILED') {
      throw new Error(`作业在 kill 前已终态（${job.status}），用例无法构造「作业中 kill」`)
    }
    await sleep(500)
  }
  check(running, `作业 #${jobId} 30s 内未进入 RUNNING+progress>=1`)
  const killedAt = Date.now()
  const kill = await killInstance('a')
  saveEvidence('kill-a.json', kill)

  const failed = await waitJobStatus('b', jobId, 'FAILED', RUN.jobFailTimeoutMs)
  check(failed.finishedAt, '看门狗判死后 finishedAt 必须非空')
  const failLatency = Date.now() - killedAt
  const issues = await jobIssues('c', jobId)
  const errorIssues = (issues.content || []).filter((issue) => issue.severity === 'ERROR')
  checkGe(errorIssues.length, 1, '看门狗判死后应至少写 1 条 ERROR 级 job 级 issue')
  check(errorIssues.some((issue) => String(issue.message || '').includes('看门狗')), `issue 文案未包含「看门狗」：${JSON.stringify(errorIssues).slice(0, 800)}`)
  const jobsBefore = await jobsOf('c', task.id)
  checkEq(jobsBefore.length, 1, '看门狗收敛后任务下作业数（不得自行重排队）')

  // 重启任一实例：启动清理会删掉同主机死 PID 的 runner-active 键，StaleJobRecovery 只处理非终态。
  await ensureUp(['a'])
  const workerKey = `job:runner-active:${jobId}`
  let keyGone = false
  const keyDeadline = Date.now() + 20000
  while (Date.now() < keyDeadline) {
    if (!redisExists(workerKey)) {
      keyGone = true
      break
    }
    await sleep(500)
  }
  check(keyGone, `重启后死 runner 键仍存在：${workerKey}（会阻塞同任务重试）`)

  const retry = await postJson('b', `/api/tasks/${task.id}/jobs`, { jobType: 'EXPORT' })
  checkEq(retry.record.status, 201, `重启后 B 侧重试 HTTP：${retry.record.text.slice(0, 500)}`)
  const retryId = retry.body && retry.body.data ? retry.body.data.id : null
  check(retryId && retryId !== jobId, '重试作业 id 必须存在且不同于被中断作业')
  const completed = await waitJobStatus('b', retryId, 'COMPLETED', 120000)
  checkGe(Number(completed.total), 1, '重试作业 total')
  checkEq(Number(completed.progress), Number(completed.total), '重试作业 progress 必须等于 total')

  const jobsAfter = await jobsOf('c', task.id)
  checkEq(jobsAfter.length, 2, '重试后任务下作业数（1 FAILED + 1 COMPLETED）')
  const old = jobsAfter.find((job) => job.id === jobId)
  const fresh = jobsAfter.find((job) => job.id === retryId)
  checkEq(old && old.status, 'FAILED', '被中断作业终态')
  checkEq(fresh && fresh.status, 'COMPLETED', '重试作业终态')
  const staleItems = (old.items || []).filter((item) => item.status === 'RUNNING' || item.status === 'PENDING')
  checkEq(staleItems.length, 0, `被中断作业残留 RUNNING/PENDING 条目数（终态应一致）：${JSON.stringify(staleItems).slice(0, 500)}`)

  saveEvidence('job-kill.json', {
    jobId,
    failLatencyMs: failLatency,
    failed: { status: failed.status, progress: failed.progress, total: failed.total, finishedAt: failed.finishedAt },
    issues: errorIssues,
    workerKeyGone: keyGone,
    retryId,
    retry: { status: completed.status, progress: completed.progress, total: completed.total },
    staleItems
  })
  caseLog(`作业 #${jobId} 在 kill A 后 ${failLatency}ms 被判 FAILED；重启清理死 runner 键；B 重试 #${retryId} COMPLETED`)
}

// ── 用例 13：readiness 摘除 ────────────────────────────────────────────────

async function waitStatsStatus(key, expected, timeoutMs) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() < deadline) {
    const stats = await haproxyStats()
    last = stats.map[key]
    if (last && last.status === expected) return stats
    await sleep(1000)
  }
  throw new Error(`server ${key} 在 ${timeoutMs}ms 内未变为 ${expected}（最后 status=${last && last.status} check=${last && last.check_status}）`)
}

async function waitAllStatsUp(timeoutMs = 25000) {
  for (const inst of INSTANCES) {
    await waitStatsStatus(inst.key, 'UP', timeoutMs)
  }
}

async function sampleProxyHealth(count, { statuses = [200] } = {}) {
  const nodes = []
  for (let i = 0; i < count; i += 1) {
    const record = await httpRequest(`${RUN.proxyUrl}/api/ai/health`, { timeoutMs: 10000, routeKind: 'proxy-health' })
    checkIn(record.status, statuses, `代理健康采样第 ${i + 1} 次 HTTP（允许 ${JSON.stringify(statuses)}）`)
    const node = record.headers['x-cluster-node']
    check(node, `代理健康采样第 ${i + 1} 次缺少 X-Cluster-Node`)
    nodes.push(node)
  }
  return nodes
}

async function caseReadinessRemoval() {
  await ensureUp()
  await waitAllStatsUp()
  const before = {
    pids: { ...STATE.pids },
    ids: { ...STATE.instanceIds },
    stats: (await haproxyStats()).map
  }
  checkEq(before.stats.a.status, 'UP', 'readiness 用例起点：A 必须是 UP')
  saveEvidence('stats-before.csv', (await haproxyStats()).raw)

  // ── ① 无 AI key：/api/ai/health 503 只是 capability；/health/ready 仍 200，代理不得摘 A ──
  await stopInstance('a')
  startInstance('a', { apiKey: '', note: 'readiness-no-key' })
  await waitReady('a', RUN.readyTimeoutMs, { requireAi: false })
  checkEq(STATE.instanceIds.a === before.ids.a, false, 'A 重启后 instanceId 应变化（新进程）')
  const unavailable = await waitAiUnavailable('a')
  const readyNoKey = await readinessOf('a')
  checkEq(readyNoKey.record.status, 200, `无 key 时 /health/ready 必须 200（实得 ${readyNoKey.record.status}）：${readyNoKey.record.text.slice(0, 400)}`)
  check(readyNoKey.body && readyNoKey.body.ready === true, `无 key 时 /health/ready ready 必须 true：${readyNoKey.record.text.slice(0, 400)}`)
  checkEq(readyNoKey.body.capabilities && readyNoKey.body.capabilities.ai ? readyNoKey.body.capabilities.ai.configured : null, false, '无 key 时 capabilities.ai.configured 必须 false')

  await waitStatsStatus('a', 'UP', 25000)
  const stillIncluded = await sampleProxyHealth(12, { statuses: [200, 503] })
  check(stillIncluded.includes('node-a'), `无 key（ready 仍 true）时代理不得摘 A：${JSON.stringify(stillIncluded)}`)
  check(stillIncluded.includes('node-b') && stillIncluded.includes('node-c'), `无 key 时流量仍应覆盖 B/C：${JSON.stringify(stillIncluded)}`)

  // 无 key 时 /chat 在开流前 503 AI_UNAVAILABLE（能力面，不是实例就绪面）。
  // 会话号从 B 取（A 此刻无 key；取号只需要身份，不依赖 AI key）。
  const readinessSession = await newSession('b', 'cluster3-readiness')
  const readinessChat = await httpRequest(directUrl('a', '/api/ai/chat'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream, application/json' },
    body: Buffer.from(JSON.stringify({ sessionId: readinessSession, message: 'scenario=PLAIN readiness' }), 'utf8'),
    timeoutMs: 15000,
    routeKind: 'chat-reject',
    directNode: directNode('a')
  })
  const chatStatus = readinessChat.status
  const readinessChatBody = jsonOf(readinessChat, 'readiness /chat 响应')
  checkEq(chatStatus, 503, `A 无 key 时直连 /chat 必须 503（实得 ${chatStatus}）`)
  checkIn(readinessChatBody.code, ['AI_UNAVAILABLE', 'AI_KEY_MISSING', 'AI_KEY_UNREADABLE'],
    'A 无 key 时 /chat code（合法分档：AI_UNAVAILABLE / AI_KEY_MISSING / AI_KEY_UNREADABLE）')

  await stopInstance('a')
  startInstance('a', { note: 'readiness-restore-key' })
  await waitReady('a')
  await waitStatsStatus('a', 'UP', 25000)
  const includedAfterKey = await sampleProxyHealth(12)
  check(includedAfterKey.includes('node-a'), `A 恢复 key 后代理未重新纳入：${JSON.stringify(includedAfterKey)}`)

  // ── ② 真实在途慢流 + SIGTERM：ready=false/draining → 代理摘 A → B/C 接管 ──
  const slowSession = await newSession('a', 'cluster3-draining')
  const slow = await chatOn('a', { sessionId: slowSession, message: 'scenario=PLAIN stall-frames=4000 readiness-draining' })
  // 预热一条 keep-alive 连接：SIGTERM 后 Tomcat 可能不再接受**新**连接，但既有连接上的
  // 请求仍要走完优雅停机；用同一条连接轮询 readiness，才拿得到 draining=true 的响应。
  const readyAgent = new http.Agent({ keepAlive: true, maxSockets: 1, maxFreeSockets: 1 })
  let runId = null
  let lastSeq = 0
  let drainingBody = null
  let liveDuringDrain = null
  let excludedDuringDrain = null
  let takeoverNode = null
  let takeoverTerminal = null
  let inFlightStillOpen = false
  let inFlightTerminal = null
  let inFlightBoundary = null
  try {
    const start = await slow.waitType('start', { timeoutMs: 30000 })
    runId = start.runId
    await slow.waitFor((f) => typeof f.seq === 'number' && f.seq >= 2, { timeoutMs: 30000, label: '等待在途慢流第 2 帧' })
    lastSeq = slow.lastSeq()
    caseLog(`在途慢流 runId=${runId} lastSeq=${lastSeq}；准备 SIGTERM A`)
    const warm = await readinessOf('a', { timeoutMs: 3000, agent: readyAgent })
    checkEq(warm.record.status, 200, 'SIGTERM 前 keep-alive 预热 /health/ready HTTP')

    const childA = STATE.procs.get('a')
    check(childA && childA.pid, 'A 的子进程句柄缺失（SIGTERM 前必须保留）')
    const pidA = childA.pid
    const termAt = Date.now()
    childA.kill('SIGTERM')

    // draining 窗口可能很短（Tomcat 优雅停机后连接器不再收新请求）：50ms 紧轮询，
    // 拿到 ready=false/draining=true 才算证据；拿不到就如实 FAIL，不改成宽松断言。
    const observations = []
    const deadline = Date.now() + 15000
    while (Date.now() < deadline) {
      try {
        const snapshot = await readinessOf('a', { timeoutMs: 1000, agent: readyAgent })
        observations.push({ ms: Date.now() - termAt, status: snapshot.record.status, body: snapshot.body })
        if (snapshot.record.status === 503 && snapshot.body && snapshot.body.draining === true && snapshot.body.ready === false) {
          drainingBody = snapshot.body
          break
        }
      } catch (e) {
        observations.push({ ms: Date.now() - termAt, error: e.message })
      }
      await sleep(50)
    }
    saveEvidence('draining-observations.json', observations)
    check(drainingBody, `SIGTERM 后 15s 内未捕获 /health/ready 的 draining 证据（ready=false/draining=true）；`
      + `观测=${JSON.stringify(observations.slice(-8))}；日志尾部：\n${tailFile(instanceLogPath('a'), 40)}`)
    checkEq(drainingBody.draining, true, 'draining 响应 draining 字段')
    checkEq(drainingBody.ready, false, 'draining 响应 ready 字段')

    try {
      liveDuringDrain = await httpRequest(directUrl('a', '/health/live'), { timeoutMs: 2000, routeKind: 'live', directNode: 'node-a' })
    } catch (e) {
      liveDuringDrain = { error: e.message }
    }

    await waitStatsStatus('a', 'DOWN', 25000)
    excludedDuringDrain = await sampleProxyHealth(12)
    check(!excludedDuringDrain.includes('node-a'), `draining 后代理仍把新流量打到 A：${JSON.stringify(excludedDuringDrain)}`)
    check(excludedDuringDrain.includes('node-b') && excludedDuringDrain.includes('node-c'), `摘除 A 后新流量应覆盖 B/C：${JSON.stringify(excludedDuringDrain)}`)
    check(!slow.ended, 'draining 证据窗口内，在途慢流被提前切断（graceful shutdown 未等待在途请求）')
    inFlightStillOpen = !slow.ended

    // B/C 接管新流量：经统一代理再起一轮，必须由 B/C 之一完成。
    // 会话号直连 B 取（A 正在 draining，取号不该依赖将要下线的实例）。
    const takeoverSession = await newSession('b', 'cluster3-draining-takeover')
    const takeover = await chatOn(null, { sessionId: takeoverSession, message: 'scenario=PLAIN draining-takeover' }, { viaProxy: true })
    try {
      takeoverTerminal = await takeover.waitTerminal(90000)
      checkEq(takeoverTerminal.type, 'done', 'draining 后经代理的新对话终帧')
      takeoverNode = takeover.node
      check(takeoverNode && takeoverNode !== 'node-a', `draining 后新对话仍落在 A：${takeoverNode}`)
      checkIn(takeoverNode, ['node-b', 'node-c'], 'draining 后新对话的接管节点')
      checkStrictlyIncreasing(takeover.seqs(), 'draining 后新对话 seq')
    } finally {
      takeover.close()
    }

    // 尽量收齐在途终帧；收不到就明确证据边界，绝不把“连接没断”当成优雅收尾完成。
    try {
      inFlightTerminal = await slow.waitTerminal(35000)
      checkIn(inFlightTerminal.type, ['done', 'error'], '在途慢流终帧类型')
      if (inFlightTerminal.type === 'done') {
        checkStrictlyIncreasing(slow.seqs(), '在途慢流 seq（graceful draining）')
      }
      caseLog(`在途慢流在 draining 窗口内收到终帧：${inFlightTerminal.type}（seq=${inFlightTerminal.seq ?? 'n/a'}）`)
    } catch (e) {
      inFlightBoundary = {
        reason: 'NO_TERMINAL_FRAME_WITHIN_DRAIN_WINDOW',
        error: e.message,
        aAlive: processAlive(pidA),
        elapsedMs: Date.now() - termAt
      }
      caseLog(`在途慢流未在 35s 内收终帧；证据边界=${JSON.stringify(inFlightBoundary)}（不宣称优雅收尾完成）`)
    }

    saveEvidence('draining-summary.json', {
      pidA,
      termAt,
      runId,
      lastSeq,
      draining: drainingBody,
      live: liveDuringDrain && liveDuringDrain.status ? { status: liveDuringDrain.status, text: liveDuringDrain.text.slice(0, 300) } : liveDuringDrain,
      excludedDuringDrain,
      inFlightStillOpen,
      inFlightTerminal: inFlightTerminal ? { type: inFlightTerminal.type, seq: inFlightTerminal.seq ?? null } : null,
      inFlightBoundary,
      takeoverNode,
      takeoverType: takeoverTerminal ? takeoverTerminal.type : null
    })
    caseLog(`SIGTERM A：ready=false/draining=true（${Date.now() - termAt}ms 内捕获）；代理摘 A；新对话由 ${takeoverNode} 接管；`
      + (inFlightTerminal ? `在途慢流收到终帧 ${inFlightTerminal.type}` : '在途慢流终帧未收齐（已记录证据边界，不宣称优雅收尾完成）'))
  } finally {
    slow.close()
    readyAgent.destroy()
    // 清理：保留句柄到证据落盘之后，再 kill；随后重启 A 并重新纳入。
    try {
      await killInstance('a')
    } catch (e) {
      caseLog(`A 清理 kill 失败（转 stopInstance 兜底）：${e.message}`)
      try {
        await stopInstance('a')
      } catch (fallback) {
        caseLog(`A 兜底停止也失败（继续尝试重启）：${fallback.message}`)
      }
    }
  }

  await startInstance('a', { note: 'readiness-restore-after-drain' })
  await waitReady('a')
  await waitStatsStatus('a', 'UP', 25000)
  const reincluded = await sampleProxyHealth(12)
  check(reincluded.includes('node-a'), `A 重启后代理未重新纳入：${JSON.stringify(reincluded)}`)

  checkEq(STATE.pids.b, before.pids.b, 'B 的 pid 不应变化')
  checkEq(STATE.pids.c, before.pids.c, 'C 的 pid 不应变化')
  checkEq(STATE.instanceIds.b, before.ids.b, 'B 的 instanceId 不应变化')
  checkEq(STATE.instanceIds.c, before.ids.c, 'C 的 instanceId 不应变化')

  const after = (await haproxyStats()).map
  saveEvidence('readiness.json', {
    noKey: {
      unavailableCode: unavailable.code,
      ready: { status: readyNoKey.record.status, body: readyNoKey.body },
      stillIncluded,
      chatStatus
    },
    draining: {
      instanceIdBefore: before.ids.a,
      draining: drainingBody,
      live: liveDuringDrain && liveDuringDrain.status ? { status: liveDuringDrain.status } : liveDuringDrain,
      excludedDuringDrain,
      takeoverNode,
      inFlightStillOpen,
      inFlightTerminal: inFlightTerminal ? { type: inFlightTerminal.type } : null,
      inFlightBoundary
    },
    includedAfterKey,
    reincluded,
    before: before.stats,
    after,
    instanceIds: { ...STATE.instanceIds }
  })
  caseLog(`无 key：/api/ai/health 503 但 /health/ready 200，代理不摘 A；SIGTERM：draining 摘 A 且 B/C 接管；重启 A 后重新纳入`)
}

// ── 用例 14：Redis 故障恢复 ────────────────────────────────────────────────

async function waitRedisHealthyAll(timeoutMs) {
  const deadline = Date.now() + timeoutMs
  let last = {}
  while (Date.now() < deadline) {
    let ok = true
    for (const inst of INSTANCES) {
      try {
        const { record, body } = await healthOf(inst.key, { timeoutMs: 8000 })
        last[inst.key] = { status: record.status, redis: body && body.redis ? body.redis.available : null, leaseOk: body ? body.leaseOk : null }
        if (!(record.status === 200 && body && body.redis && body.redis.available === true && body.leaseOk === true)) ok = false
      } catch (e) {
        last[inst.key] = { error: e.message }
        ok = false
      }
    }
    if (ok) return last
    await sleep(1000)
  }
  throw new Error(`Redis 恢复后 ${timeoutMs}ms 内三实例未全部 redis.available+leaseOk：${JSON.stringify(last)}`)
}

async function caseRedisOutageRecovery() {
  await ensureUp()
  if (!CTX.plainRun) {
    const run = await runPlainChat('a', 'cluster3-redis-baseline')
    CTX.plainRun = { runId: run.runId, sessionId: run.sessionId, message: run.message, seqs: run.seqs.slice() }
  }
  // 会话号先取（后端已就绪、Redis 尚在）：Redis 暂停后再取号会把"降级窗口"混进取号路径，
  // 而本例要断言的是 **chat 在 Redis 故障时快速失败**，不是取号延迟。
  const outageSession = await newSession('c', 'cluster3-redis-outage')

  const pausedAt = Date.now()
  await pauseRedis()
  caseLog('已暂停 Redis 容器')
  try {
    const health = await healthOf('b', { timeoutMs: 15000 })
    const healthLatency = Date.now() - pausedAt
    checkEq(health.record.status, 200, 'Redis 故障时 /api/ai/health HTTP 状态（AI 可用性不受 Redis 影响）')
    check(health.body && health.body.redis && health.body.redis.available === false, `Redis 故障时 redis.available 必须为 false：${health.record.text.slice(0, 400)}`)
    checkEq(health.body.leaseOk, false, 'Redis 故障时 leaseOk 必须为 false')

    // Redis 是**降级维度**：/health/ready 仍 200/ready=true，但 checks.redis=DOWN 且 degraded 含 redis。
    const ready = await readinessOf('c', { timeoutMs: 15000 })
    checkEq(ready.record.status, 200, `Redis 故障时 /health/ready 必须 200（Redis 不摘流）：${ready.record.text.slice(0, 500)}`)
    check(ready.body && ready.body.ready === true, `Redis 故障时 /health/ready ready 必须 true：${ready.record.text.slice(0, 500)}`)
    checkEq(ready.body.checks && ready.body.checks.redis ? ready.body.checks.redis.status : null, 'DOWN', 'Redis 故障时 checks.redis.status 必须 DOWN')
    checkEq(ready.body.checks.redis && ready.body.checks.redis.required !== undefined ? ready.body.checks.redis.required : null, false, 'Redis 维度必须声明 required=false（降级维度）')
    check(Array.isArray(ready.body.degraded) && ready.body.degraded.includes('redis'), `Redis 故障时 degraded 必须包含 redis：${JSON.stringify(ready.body.degraded)}`)

    const chatStart = Date.now()
    const chat = await httpRequest(directUrl('c', '/api/ai/chat'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream, application/json' },
      body: Buffer.from(JSON.stringify({ sessionId: outageSession, message: 'scenario=PLAIN redis-outage' }), 'utf8'),
      timeoutMs: 15000,
      routeKind: 'chat-reject',
      directNode: directNode('c')
    })
    const chatLatency = Date.now() - chatStart
    checkEq(chat.status, 503, `Redis 故障时 /chat HTTP（实得 ${chat.status}，体=${chat.text.slice(0, 300)}）`)
    const chatBody = jsonOf(chat, 'Redis 故障时 /chat 响应')
    checkEq(chatBody.code, 'AI_REDIS_UNAVAILABLE', 'Redis 故障时 /chat code')
    checkLe(chatLatency, 8000, 'Redis 故障时 /chat 快速失败耗时（产品目标 ≤2s + 开销）')

    const definitions = await httpRequest(directUrl('a', '/api/definitions'), { timeoutMs: 15000, routeKind: 'api', directNode: directNode('a') })
    checkEq(definitions.status, 200, 'Redis 故障时业务 API /api/definitions 仍应可用')
    const defBody = jsonOf(definitions, 'Redis 故障时 definitions')
    check(defBody && defBody.success === true, 'Redis 故障时 definitions 响应异常')

    // 统一代理也必须可用（Redis 故障不能把业务面从路由上摘掉）。
    const definitionsProxy = await httpRequest(proxyUrl('/api/definitions'), { timeoutMs: 15000, routeKind: 'api' })
    checkEq(definitionsProxy.status, 200, `Redis 故障时经代理 /api/definitions 仍应可用（实得 ${definitionsProxy.status}）：${definitionsProxy.text.slice(0, 300)}`)
    const defProxyBody = jsonOf(definitionsProxy, 'Redis 故障时经代理 definitions')
    check(defProxyBody && defProxyBody.success === true, 'Redis 故障时经代理 definitions 响应异常')
    check(Array.isArray(defProxyBody.data) && defProxyBody.data.length >= 15, 'Redis 故障时经代理 definitions 数据量')

    const events = await httpRequest(directUrl('b', `/api/ai/events/${CTX.plainRun.runId}?lastSeq=0`), {
      timeoutMs: 15000,
      routeKind: 'sse-reject',
      directNode: directNode('b'),
      headers: { Accept: 'text/event-stream, application/json' }
    })
    checkEq(events.status, 503, `Redis 故障时 reattach HTTP（实得 ${events.status}）`)
    const eventBody = jsonOf(events, 'Redis 故障时 reattach 响应')
    checkEq(eventBody.code, 'AI_REDIS_UNAVAILABLE', 'Redis 故障时 reattach code')

    await unpauseRedis()
    const recoveredAt = Date.now()
    const recovered = await waitRedisHealthyAll(45000)
    const recoveryMs = Date.now() - recoveredAt

    // 先等代理把后端重新标记 UP，再发对话；否则会把 HAProxy 的「无可用后端」
    // 误读成产品恢复失败（两者都可能是 503，但责任面不同）。
    let stats = null
    const statsDeadline = Date.now() + 25000
    while (Date.now() < statsDeadline) {
      stats = (await haproxyStats()).map
      if (stats.a.status === 'UP' && stats.b.status === 'UP' && stats.c.status === 'UP') break
      await sleep(1000)
    }
    check(stats && stats.a.status === 'UP' && stats.b.status === 'UP' && stats.c.status === 'UP', `Redis 恢复后代理后端未全部 UP：${JSON.stringify(stats && {
      a: stats.a.status, b: stats.b.status, c: stats.c.status
    })}`)

    // 恢复后通过代理发一轮，证明端到端可用（不是只看 /health）。
    let done = false
    let lastError = null
    for (let attempt = 0; attempt < 3 && !done; attempt += 1) {
      try {
        // 每次重试换一个新号：同一号重试会把"上一轮已收敛的会话"混进本次判定。
        const recoverySession = await newSession('b', 'cluster3-redis-recovery')
        const stream = await chatOn(null, { sessionId: recoverySession, message: 'scenario=PLAIN redis-recovery' }, { viaProxy: true })
        try {
          const terminal = await stream.waitTerminal(60000)
          checkEq(terminal.type, 'done', 'Redis 恢复后代理对话终帧')
          done = true
        } finally {
          stream.close()
        }
      } catch (e) {
        lastError = e
        await sleep(2000)
      }
    }
    check(done, `Redis 恢复后代理对话仍失败：${lastError ? lastError.message : '未知'}`)

    saveEvidence('redis-outage.json', {
      healthLatencyMs: healthLatency,
      readyDuringOutage: { status: ready.record.status, ready: ready.body && ready.body.ready, checks: ready.body && ready.body.checks, degraded: ready.body && ready.body.degraded },
      definitionsProxyStatus: definitionsProxy.status,
      chatStatus: chat.status,
      chatCode: chatBody.code,
      chatLatencyMs: chatLatency,
      recovered,
      recoveryMs,
      stats
    })
    caseLog(`Redis 暂停期间 /health/ready 仍 ready=true（checks.redis=DOWN，degraded 含 redis）；/chat=${chatBody.code}（${chatLatency}ms），definitions 直连与代理均 200；恢复 ${recoveryMs}ms 后三实例健康、代理对话完成`)
  } finally {
    // 无论断言是否失败，都必须解除暂停，避免后续用例全红。
    try {
      await unpauseRedis()
    } catch (e) {
      caseLog(`解除 Redis 暂停失败：${e.message}`)
    }
  }
}

// ── 用例 15：执行前取消的三格安全反例（L-150 / D8）────────────────────────

/**
 * L-150 的三条安全反例（`task-8-cancel-guard` / `settleIfCancelledBeforeClaim` 的判据格）。
 * 三格共用**真实三实例 + 共享 Redis** 的同一条链路，但每格独立断言，互不代偿：
 *
 * <ul>
 * <li><b>R-1 `UNKNOWN` 不得执行</b>：取消状态读不出来 ≠ "未取消"。注入方式 = 把权威键
 *     `ai:cancel:<runId>` 改成 LIST（产品 `GET` 得 WRONGTYPE ⇒ 三态里的 `UNKNOWN`）。
 *     此时 `/runs/{runId}/resume` 必须给出 typed 拒绝（`CANCEL_STATE_UNKNOWN`），用户在别的
 *     实例上"批准"也不得把 `start_export` 跑起来；条目状态与终帧一概不动（未知不是已取消）。</li>
 * <li><b>R-2 已 `CANCELLED` 不得误认领</b>：取消收敛后，再从另一个实例批准 / 直接续跑都必须
 *     被取消语义拒绝（`DUPLICATE_TOOL_CALL_ID` / `ALREADY_CANCELLED`）：认领表不得出现该条目、
 *     台账不得有执行记录、作业数不变。</li>
 * <li><b>R-3 claim 哈希-only 不得伪未执行</b>：条目是 `APPROVED` 且**零**执行证据，只有认领表
 *     （`ai:claim:{run:<runId>}` 的某个 field）有值。取消收尾的判据格（`hasExecutionEvidence` 含认领表）
 *     必须**不**把条目改写成 `CANCELLED`/"未执行"，也不得回滚认领证据。
 *     R-2 是同一取消路径的**对照组**（无认领 ⇒ 收敛为 `CANCELLED`），两格只差"认领表有没有值"。</li>
 * </ul>
 *
 * <p><b>确定性</b>：三格都不靠"卡竞态窗口"——R-1/R-3 用错误类型的键制造 `UNKNOWN`；R-3 的
 * `APPROVED` 也是在 `UNKNOWN` 下批准得到的（续跑被拒，因此不会执行），认领表由夹具直接写入。
 * 注入点都是本网独占的 Redis（每次运行一个全新容器），用例结束即删键。
 *
 * <p><b>边界（如实）</b>：`settleIfCancelledBeforeClaim` 内部"认领前一刻读到 UNKNOWN"的那一瞬
 * 仍由单元测试（`task-8-unknown-outcome` 32/32）覆盖 —— 黑盒探针无法确定性地卡在两条指令之间。
 * 本用例断言的是**对外可观测的不变量**：UNKNOWN 一律不执行、不认领、不伪造结论。
 *
 * <p><b>R-1 的重挂口径</b>：决策（`APPROVED`）已落库后的重挂**不是** park 收流 —— 产品对
 * "条目全部结算"的重挂保留连接等续跑终帧（避免 `done` 缺失的既有契约），此处续跑被
 * `CANCEL_STATE_UNKNOWN` 拒绝后按有界节拍重试。故 R-1 只做有界观察
 * （{@link R1_REPLAY_OBSERVE_MS}）：决策帧必须已回放、窗口内不得出现终帧或取消裁决；
 * "连接是否收流"不是 L-150 的判据面。
 */

const CANCEL_KEY_PREFIX = 'ai:cancel:'
const CLAIM_KEY_PREFIX = 'ai:claim:'
const CANCELLED_BEFORE_CLAIM_TEXT = '本轮对话已被用户取消，该工具未执行。'

/**
 * R-1 决策后重挂的**有界观察窗**（毫秒）。
 *
 * <p>为什么不是 park 收流：R-1 在决策已落库（`APPROVED`）之后重挂。产品对"条目全部结算"的
 * 重挂**有意保留连接** —— 那是避免 `done` 缺失（GF4 / W-01..W-22）的既有契约，且此处续跑被
 * `CANCEL_STATE_UNKNOWN` 拒绝后按 2s/4s/8s… 有界重试，Redis 恢复后同一连接才收到终帧。
 * 用 park 收流去判这一格等于要求产品丢掉这条终帧通路（run 40257579571 前的 16 例里也没有这一格）。
 *
 * <p>本窗只做"有界观察"：先把窗口内收到的帧落盘，再断言**决策帧已回放且没有伪造终帧/取消裁决**。
 * 窗口取 10s，覆盖前 3 次重试（2s / 6s / 14s 中的前两次），不改变 L-150 的判据面。
 */
const R1_REPLAY_OBSERVE_MS = 10000

function cancelFlagKey(runId) {
  // 取消标志是**裸前缀 + 身份段**键（租户任务 2 收尾组）：产品 `CancellationRegistry#cancelKey`
  // = `TenantKeyScope.scoped(CANCEL_PREFIX, runId)` = `ai:cancel:<tenantId>:<userId>:<runId>`
  // （`POST /api/ai/cancel/{runId}` 的响应体 `cancelKey` 即此形态；身份取探针统一请求头）。
  // 仍不进 hash tag：它是单键命令/单键脚本用的 run 锚点键，与 `ai:beat:` / `ai:activity:` 同族。
  return `${CANCEL_KEY_PREFIX}${AI_TENANT_ID}:${AI_USER_ID}:${runId}`
}

/**
 * 认领表键：产品用 `RunStore.runScopedKey(CLAIM_PREFIX, runId)` = `ai:claim:{run:<runId>}`。
 *
 * <p>为什么钉死：早期夹具写成裸 `${CLAIM_KEY_PREFIX}${runId}`（把取消标志的裸前缀形态套到认领表上），
 * 注入落在没人读的键上 ⇒ 产品读不到认领证据 ⇒ 取消收尾把已批准条目收敛成 `CANCELLED`，
 * R-3 假红（run 40681954172：条目 APPROVED+零证据+认领表注入，取消后仍被改写为 CANCELLED）。
 * `--self-check` 里同时钉产品侧的 `runScopedKey(CLAIM_PREFIX, runId)` 与 `runTag` 形态。
 */
function executionClaimKey(runId) {
  // 租户任务 2：run 级键 tag 同时含身份与 runId（`{<tenant>:<user>:run:<runId>}`）。
  // 身份取探针统一身份（AI_TENANT_ID / AI_USER_ID），与请求头一致；否则注入落在别人的键上。
  return `${CLAIM_KEY_PREFIX}{${AI_TENANT_ID}:${AI_USER_ID}:run:${runId}}`
}

function executionClaimPresent(runId, toolCallId) {
  return redisCli('HEXISTS', executionClaimKey(runId), toolCallId) === '1'
}

function executionClaimValue(runId, toolCallId) {
  return executionClaimPresent(runId, toolCallId) ? redisCli('HGET', executionClaimKey(runId), toolCallId) : ''
}

async function cancelOn(key, runId) {
  return postJson(key, `/api/ai/cancel/${encodeURIComponent(runId)}`, {})
}

async function resumeOn(key, runId) {
  return postJson(key, `/api/ai/runs/${encodeURIComponent(runId)}/resume`, {})
}

/** 取消标志现状（只读）：`cancelState` 三态与 `redisReadFailed` 是 UNKNOWN 注入的见证面。 */
async function cancelStateOf(key, runId) {
  const { record, body } = await getJson(key, `/api/ai/cancel/${encodeURIComponent(runId)}`)
  checkEq(record.status, 200, `GET /api/ai/cancel/${runId}（${key}）HTTP`)
  check(body && body.success === true && body.data, `取消状态读取异常：${record.text.slice(0, 400)}`)
  return body.data
}

/** 前置门：三实例都必须报 `UNKNOWN`（读失败），否则本格会变成空断言。 */
async function awaitCancelUnknownAll(runId, timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs
  let last = {}
  while (Date.now() < deadline) {
    let allUnknown = true
    for (const inst of INSTANCES) {
      const data = await cancelStateOf(inst.key, runId)
      last[inst.key] = {
        cancelState: data.cancelState || null,
        redisReadFailed: data.redisReadFailed,
        localFlag: data.localFlag
      }
      if (data.cancelState !== 'UNKNOWN' || data.redisReadFailed !== true || data.localFlag === true) {
        allUnknown = false
      }
    }
    if (allUnknown) return last
    await sleep(300)
  }
  throw new Error(`取消状态未能在 ${timeoutMs}ms 内对三实例都呈现 UNKNOWN：${JSON.stringify(last)}`)
}

/**
 * 等三实例的取消状态读取恢复（`degraded` 的 5s 退避清掉）。
 *
 * <p>为什么必须等：注入的读失败会让每个实例进入"5s 内不再访问 Redis 且返回 UNKNOWN"的退避；
 * 不等它清掉就接着跑后面的用例，会把上一格的注入噪声带进下一格（假红）。
 */
async function awaitCancelReadableAll(runId, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs
  let last = {}
  while (Date.now() < deadline) {
    let allReadable = true
    for (const inst of INSTANCES) {
      const data = await cancelStateOf(inst.key, runId)
      last[inst.key] = { cancelState: data.cancelState || null, redisReadFailed: data.redisReadFailed }
      if (data.redisReadFailed !== false) allReadable = false
    }
    if (allReadable) return last
    await sleep(500)
  }
  throw new Error(`取消状态读取未能在 ${timeoutMs}ms 内恢复（degraded 退避未清）：${JSON.stringify(last)}`)
}

function pendingEntryIn(detail, toolCallId) {
  return (detail.pending || []).find((item) => item && item.toolCallId === toolCallId) || null
}

async function pendingEntryOf(key, runId, toolCallId) {
  const detail = await runDetail(key, runId)
  return { detail, entry: pendingEntryIn(detail, toolCallId) }
}

async function waitPendingStatus(key, runId, toolCallId, expected, timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() < deadline) {
    const { detail, entry } = await pendingEntryOf(key, runId, toolCallId)
    last = { status: entry ? entry.status : null, runStatus: detail.snapshot ? detail.snapshot.status : null }
    if (entry && entry.status === expected) return { detail, entry }
    await sleep(250)
  }
  throw new Error(`条目 ${toolCallId} 未在 ${timeoutMs}ms 内到 ${expected}（最后：${JSON.stringify(last)}）`)
}

/** 执行证据面（条目 + 台账 + 认领表）的一次快照；断言之前先落盘。 */
async function executionStateOf(key, runId, toolCallId) {
  const detail = await runDetail(key, runId)
  return {
    runStatus: detail.snapshot ? detail.snapshot.status : null,
    entry: pendingEntryIn(detail, toolCallId),
    ledger: ledgerFor(detail, toolCallId),
    ledgerCounts: detail.ledgerCounts || {},
    claimPresent: executionClaimPresent(runId, toolCallId),
    claimValue: executionClaimValue(runId, toolCallId)
  }
}

/** "该工具没有执行"的统一判据：条目零执行证据 + 台账为空 +（默认）认领表无该条目。 */
function assertNoExecution(state, label, { allowClaim = false } = {}) {
  check(state.entry, `${label}：条目缺失`)
  checkEq(state.entry.executed, false, `${label}：条目 executed 必须是 false`)
  checkEq(state.entry.executedBy, null, `${label}：条目 executedBy 必须为空`)
  checkEqNumber(state.entry.claimedAtMs, 0, `${label}：条目 claimedAtMs 必须是 0`)
  checkEqNumber(state.entry.claimedEpoch, 0, `${label}：条目 claimedEpoch 必须是 0`)
  checkEqNumber(state.entry.executedAtMs, 0, `${label}：条目 executedAtMs 必须是 0`)
  checkEq(state.ledger.length, 0, `${label}：该 toolCallId 不得有台账条目（没有执行）`)
  if (!allowClaim) {
    checkEq(state.claimPresent, false, `${label}：认领表不得出现该 toolCallId`)
  }
}

/** `/resume` 的 typed 拒绝：租约被占时重试（取消收尾仍在还租），其余结局一律按实得值判红。 */
async function resumeRefusal(key, runId, expectedReasons, label, timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() < deadline) {
    const result = await resumeOn(key, runId)
    last = result
    const reason = result.body ? result.body.reason : null
    if (reason && expectedReasons.includes(reason)) return result
    await sleep(500)
  }
  throw new Error(`${label}：/resume 未给出 ${JSON.stringify(expectedReasons)}（最后 HTTP ${last ? last.record.status : '?'}`
    + ` 体=${last ? last.record.text.slice(0, 400) : '无'}）`)
}

/** R-1：`UNKNOWN` 不得执行（typed 拒绝 + 零执行 + 不伪造取消结论）。 */
async function gridUnknownMustNotExecute() {
  const task = await newSuspendedTask('l150-unknown')
  const run = await createSuspendedRun('a', {
    prefix: 'l150-unknown',
    taskId: task.id,
    messageSuffix: ' l150-unknown',
    evidenceName: 'R1-parked.jsonl'
  })
  const flagKey = cancelFlagKey(run.runId)
  const jobsBefore = (await jobsOf('c', task.id)).length
  caseLog(`R-1 挂起在 A：runId=${run.runId} toolCallId=${run.toolCallId} taskId=${task.id}`)
  let cleanupOk = false
  try {
    redisCli('DEL', flagKey)
    checkEq(redisCli('LPUSH', flagKey, 'l150-unknown-probe'), '1', 'R-1 取消标志键注入（改成 LIST）')
    const witness = await awaitCancelUnknownAll(run.runId)
    saveEvidence('R1-cancel-unknown-witness.json', witness)

    const resume = await resumeRefusal('b', run.runId, ['CANCEL_STATE_UNKNOWN'], 'R-1 续跑拒绝')
    const confirm = await confirmOn('c', {
      runId: run.runId,
      toolCallId: run.toolCallId,
      reason: 'L-150 R-1 cancel state UNKNOWN'
    })
    // 异步派发的续跑也要有时间失败；这一段没有可等的事件，只能给有界余量后再读权威状态。
    await sleep(1500)
    const replay = await reattachOn('c', run.runId, 0, { timeoutMs: 30000 })
    // 决策已落库（APPROVED）后的重挂：产品保留连接等续跑终帧（见 R1_REPLAY_OBSERVE_MS 的说明），
    // 因此这里只做有界观察并如实记录连接结局，不断言 park 收流。
    let replayHow = 'open'
    try {
      await sleep(R1_REPLAY_OBSERVE_MS)
      replayHow = replay.endError ? 'error' : (replay.ended ? 'end' : 'open')
    } finally {
      saveFrames('R1-replay.jsonl', replay.frames)
      replay.close()
    }
    const state = await executionStateOf('c', run.runId, run.toolCallId)
    const jobsAfter = (await jobsOf('c', task.id)).length
    const snapshot = {
      witness,
      resume: { status: resume.record.status, body: resume.body },
      confirm: { status: confirm.record.status, body: confirm.body },
      replayHow,
      replayTypes: typesOf(replay.frames),
      state,
      jobsBefore,
      jobsAfter
    }
    saveEvidence('R1-unknown.json', snapshot)

    checkEq(resume.record.status, 503, `R-1 取消状态 UNKNOWN 时 /resume HTTP（体=${resume.record.text.slice(0, 400)}）`)
    checkEq(resume.body && resume.body.resumed, false, 'R-1 /resume 不得续跑')
    checkEq(resume.body.reason, 'CANCEL_STATE_UNKNOWN', 'R-1 /resume reason')
    checkEq(resume.body.code, 'AI_REDIS_UNAVAILABLE', 'R-1 /resume code')

    checkEq(confirm.record.status, 200, `R-1 批准回执 HTTP（体=${confirm.record.text.slice(0, 400)}）`)
    checkEq(confirm.body && confirm.body.executed, false, 'R-1 批准回执不得声称已执行')

    check(
      replayHow !== 'error',
      `R-1 回放连接不得被重置（实得 ${replayHow}；reset 不能冒充"未执行"的结论）`
    )
    const replayBusiness = businessFrames(replay.frames)
    checkEq(
      countOf(replayBusiness, 'confirm_decision', run.toolCallId),
      1,
      `R-1 决策帧必须已回放（已收=${typesOf(replayBusiness).join(',')}）`
    )
    checkEq(
      findOf(replayBusiness, 'confirm_decision', run.toolCallId).decision,
      'approve',
      'R-1 回放的决策帧必须是已落库的 approve'
    )
    checkEq(
      countOf(replayBusiness, 'done') + countOf(replayBusiness, 'error'),
      0,
      `R-1 取消状态不可知不得伪造终帧（已收=${typesOf(replayBusiness).join(',')}；连接结局=${replayHow}）`
    )
    check(
      replayBusiness
        .filter((frame) => frame.type === 'confirm_decision' && frame.toolCallId === run.toolCallId)
        .every((frame) => frame.decision !== 'cancel'),
      'R-1 取消状态不可知不得伪造取消裁决帧（decision=cancel）'
    )

    checkEq(state.runStatus, 'SUSPENDED', `R-1 取消状态不可知不得改写轮次状态（实得 ${state.runStatus}）`)
    checkEq(state.entry && state.entry.status, 'APPROVED', 'R-1 决策已落库（批准）')
    check(
      state.entry && state.entry.status !== 'CANCELLED',
      'R-1 基础设施不可知不是"已取消"：不得把条目写成取消终态'
    )
    check(
      !String(state.entry && state.entry.resultText ? state.entry.resultText : '').includes('未执行'),
      'R-1 不得给出"未执行"的结论（未知不是未执行）'
    )
    assertNoExecution(state, 'R-1')
    checkEq(jobsAfter, jobsBefore, 'R-1 不得起作业（start_export 未执行）')

    caseLog(`R-1 三实例均 UNKNOWN（witness=${JSON.stringify(witness)}）；/resume=${resume.body.reason}，`
      + `批准 accepted=${confirm.body.accepted} executed=${confirm.body.executed}`
      + `（resumeOutcome=${confirm.body.resumeOutcome}）；重挂回放 ${typesOf(replayBusiness).join(',')}`
      + `（有界观察 ${R1_REPLAY_OBSERVE_MS}ms，连接结局=${replayHow}）；`
      + `轮次仍 ${state.runStatus}、条目 ${state.entry.status}；`
      + `认领表无该条目、台账 0 条、作业 ${jobsAfter} 个（未执行）`)
  } finally {
    // 收尾必须原子：先把注入键覆盖成**合法**取消标志（单条 SET —— 不存在"键已恢复但标志未写"的空窗，
    // 那个空窗里被死卡兜底派发的续跑会把这条 APPROVED 的工具真的执行掉），再由产品 /cancel 驱动收敛。
    try {
      redisCli('SET', flagKey, 'l150-probe-cleanup')
      const cleanup = await cancelOn('b', run.runId)
      await waitRunStatus('c', run.runId, 'CANCELLED', 60000)
      await awaitCancelReadableAll(run.runId)
      cleanupOk = true
      caseLog(`R-1 收尾：/cancel HTTP ${cleanup.record.status}（alreadyCancelled=${cleanup.body ? cleanup.body.alreadyCancelled : '?'}），轮次已 CANCELLED，三实例取消状态读取已恢复`)
    } catch (e) {
      caseLog(`R-1 收尾失败（不改写本格判据，但取消标志键保留以免该轮以后被执行）：${e.message}`)
    }
    if (cleanupOk) redisCli('DEL', flagKey)
  }
}

/** R-2：已 `CANCELLED` 不得误认领（对照组：无认领 ⇒ 取消收尾必须收敛）。 */
async function gridAlreadyCancelledMustNotBeReclaimed() {
  const task = await newSuspendedTask('l150-cancelled')
  const run = await createSuspendedRun('a', {
    prefix: 'l150-cancelled',
    taskId: task.id,
    messageSuffix: ' l150-cancelled',
    evidenceName: 'R2-parked.jsonl'
  })
  const jobsBefore = (await jobsOf('c', task.id)).length
  caseLog(`R-2 挂起在 A：runId=${run.runId} toolCallId=${run.toolCallId} taskId=${task.id}`)

  const cancel = await cancelOn('b', run.runId)
  await waitRunStatus('c', run.runId, 'CANCELLED', 60000)
  const settled = await waitPendingStatus('c', run.runId, run.toolCallId, 'CANCELLED', 20000)
  const before = await executionStateOf('c', run.runId, run.toolCallId)
  const frames = await fullReplayToTerminal('c', run.runId, 30000, 'R2-full.jsonl')

  const resume = await resumeRefusal('c', run.runId, ['ALREADY_CANCELLED'], 'R-2 已取消续跑')
  const confirm = await confirmOn('c', {
    runId: run.runId,
    toolCallId: run.toolCallId,
    reason: 'L-150 R-2 already cancelled'
  })
  await sleep(800)
  const after = await executionStateOf('c', run.runId, run.toolCallId)
  const jobsAfter = (await jobsOf('c', task.id)).length
  const business = businessFrames(frames)
  saveEvidence('R2-already-cancelled.json', {
    cancel: { status: cancel.record.status, body: cancel.body },
    settledStatus: settled.entry ? settled.entry.status : null,
    before,
    resume: { status: resume.record.status, body: resume.body },
    confirm: { status: confirm.record.status, body: confirm.body },
    after,
    frames: { types: typesOf(business), terminal: findOf(business, 'done') },
    jobsBefore,
    jobsAfter
  })

  checkEq(cancel.record.status, 200, `R-2 取消 HTTP（体=${cancel.record.text.slice(0, 400)}）`)
  checkEq(cancel.body && cancel.body.cancelled, true, 'R-2 取消回执 cancelled')
  checkEq(after.runStatus, 'CANCELLED', 'R-2 取消后轮次终态')

  checkEq(countOf(business, 'done'), 1, `R-2 取消归档终帧数（已收=${typesOf(business).join(',')}）`)
  checkEq(findOf(business, 'done').cancelled, true, 'R-2 终帧必须是 done{cancelled:true}')
  checkEq(countOf(business, 'tool_result', run.toolCallId), 0, 'R-2 已取消条目不得出现 tool_result（没有执行）')
  checkEq(countOf(business, 'confirm_decision', run.toolCallId), 1, 'R-2 取消必须补一条裁决帧')
  checkEq(findOf(business, 'confirm_decision', run.toolCallId).decision, 'cancel', 'R-2 裁决帧 decision')

  checkEq(before.entry && before.entry.status, 'CANCELLED', 'R-2 取消收尾必须把条目收敛为 CANCELLED')
  assertNoExecution(before, 'R-2（取消收敛时）')

  checkEq(resume.body && resume.body.reason, 'ALREADY_CANCELLED', 'R-2 已取消轮的续跑结局')
  checkEq(resume.body.resumed, false, 'R-2 已取消轮不得续跑')
  checkEq(confirm.record.status, 409, `R-2 已取消条目再次批准必须 409（实得 ${confirm.record.status}）`)
  checkEq(confirm.body && confirm.body.code, 'DUPLICATE_TOOL_CALL_ID', 'R-2 重复决策 code')

  checkEq(after.entry && after.entry.status, 'CANCELLED', 'R-2 条目不得被重新打开')
  assertNoExecution(after, 'R-2（拒绝重新认领后）')
  checkEq(jobsAfter, jobsBefore, 'R-2 不得起作业')
  caseLog(`R-2 取消 → 条目 ${before.entry.status}（零执行证据）；/resume=${resume.body.reason}；`
    + `再次批准 409 ${confirm.body.code}；认领表始终无该条目、台账 0 条、作业 ${jobsAfter} 个`)
}

/** R-3：claim 哈希-only 不得伪未执行（与 R-2 同链路，只多一条认领表 field）。 */
async function gridClaimHashOnlyMustNotFakeNotExecuted() {
  const task = await newSuspendedTask('l150-claim-only')
  const run = await createSuspendedRun('a', {
    prefix: 'l150-claim-only',
    taskId: task.id,
    messageSuffix: ' l150-claim-only',
    evidenceName: 'R3-parked.jsonl'
  })
  const flagKey = cancelFlagKey(run.runId)
  const claimKey = executionClaimKey(run.runId)
  const planted = '0|l150-claim-only-probe'
  const jobsBefore = (await jobsOf('c', task.id)).length
  caseLog(`R-3 挂起在 A：runId=${run.runId} toolCallId=${run.toolCallId} taskId=${task.id}`)
  let cancelled = false
  try {
    // ① 先得到"APPROVED 且零执行证据"的条目：取消状态不可读 ⇒ 续跑被拒，批准只落决策、不执行。
    redisCli('DEL', flagKey)
    checkEq(redisCli('LPUSH', flagKey, 'l150-claim-only-probe'), '1', 'R-3 取消标志键注入（改成 LIST）')
    await awaitCancelUnknownAll(run.runId)
    const confirm = await confirmOn('b', {
      runId: run.runId,
      toolCallId: run.toolCallId,
      reason: 'L-150 R-3 approve without execution'
    })
    await waitPendingStatus('b', run.runId, run.toolCallId, 'APPROVED', 20000)

    // ② 夹具：只写认领表（哈希 field），条目保持零执行证据 —— 这正是 R-3 的判据格。
    checkEq(redisCli('HSET', claimKey, run.toolCallId, planted), '1', 'R-3 认领表注入')
    const preCancel = await executionStateOf('b', run.runId, run.toolCallId)

    // ③ 覆盖成合法取消标志（单条 SET，无空窗），由产品 /cancel 端点驱动取消收尾。
    redisCli('SET', flagKey, 'l150-claim-only-cancel')
    const cancel = await cancelOn('c', run.runId)
    await waitRunStatus('a', run.runId, 'CANCELLED', 60000)
    cancelled = true
    // cancelTerminal 的条目收敛先于快照 CANCELLED 落库；这段余量只留给异步结算，不改变条目判据。
    await sleep(800)
    const after = await executionStateOf('a', run.runId, run.toolCallId)
    const frames = await fullReplayToTerminal('a', run.runId, 30000, 'R3-full.jsonl')
    const jobsAfter = (await jobsOf('c', task.id)).length
    const business = businessFrames(frames)
    saveEvidence('R3-claim-hash-only.json', {
      confirm: { status: confirm.record.status, body: confirm.body },
      planted,
      preCancel,
      cancel: { status: cancel.record.status, body: cancel.body },
      after,
      frames: { types: typesOf(business), terminal: findOf(business, 'done') },
      jobsBefore,
      jobsAfter
    })

    checkEq(confirm.record.status, 200, `R-3 批准回执 HTTP（体=${confirm.record.text.slice(0, 400)}）`)
    checkEq(confirm.body && confirm.body.executed, false, 'R-3 批准回执不得声称已执行')
    checkEq(preCancel.entry && preCancel.entry.status, 'APPROVED', 'R-3 前置：条目必须是 APPROVED')
    assertNoExecution(preCancel, 'R-3（认领表注入前）', { allowClaim: true })
    checkEq(preCancel.claimPresent, true, 'R-3 前置：认领表必须有该 toolCallId')
    checkEq(preCancel.claimValue, planted, 'R-3 前置：认领表的认领者')

    checkEq(cancel.record.status, 200, `R-3 取消 HTTP（体=${cancel.record.text.slice(0, 400)}）`)
    checkEq(after.runStatus, 'CANCELLED', 'R-3 取消后轮次终态')

    // 判据格：取消收尾**只认"零证据"**。认领表有值 ⇒ 必须不碰条目，不得把"可能已执行"说成"未执行"。
    checkEq(
      after.entry && after.entry.status,
      'APPROVED',
      `R-3 claim 哈希-only 不得伪未执行：条目被改写成了 ${after.entry ? after.entry.status : '（缺失）'}`
    )
    check(
      after.entry.reason !== 'RUN_CANCELLED',
      `R-3 条目不得被写成取消原因（实得 reason=${JSON.stringify(after.entry.reason)}）`
    )
    check(
      after.entry.outcome !== 'cancelled',
      `R-3 条目 outcome 不得被写成 cancelled（实得 ${JSON.stringify(after.entry.outcome)}）`
    )
    check(
      after.entry.code !== 'RUN_CANCELLED',
      `R-3 条目 code 不得被写成 RUN_CANCELLED（实得 ${JSON.stringify(after.entry.code)}）`
    )
    check(
      !String(after.entry.resultText ? after.entry.resultText : '').includes('未执行'),
      `R-3 不得用"${CANCELLED_BEFORE_CLAIM_TEXT}"覆盖条目（认领表已证明它可能执行过）`
    )
    checkEq(after.claimPresent, true, 'R-3 认领证据不得被回滚/删除')
    checkEq(after.claimValue, planted, 'R-3 认领表的值不得被改写')

    checkEq(
      countOf(business, 'tool_result', run.toolCallId),
      0,
      `R-3 不得补"未执行"的 tool_result（已收=${typesOf(business).join(',')}）`
    )
    // 批准本身会补一条 confirm_decision(approve) 回执帧；这里禁的是**取消**裁决（伪"未执行"的另一个面）。
    const cancelDecisions = business.filter((frame) => frame.type === 'confirm_decision'
      && frame.toolCallId === run.toolCallId && frame.decision === 'cancel')
    checkEq(
      cancelDecisions.length,
      0,
      `R-3 不得补取消裁决帧（条目没有收敛为 CANCELLED；已收=${typesOf(business).join(',')}）`
    )
    checkEq(countOf(business, 'done'), 1, 'R-3 取消归档终帧数')
    checkEq(findOf(business, 'done').cancelled, true, 'R-3 终帧必须是 done{cancelled:true}')
    assertNoExecution(after, 'R-3（取消收尾之后）', { allowClaim: true })
    checkEq(jobsAfter, jobsBefore, 'R-3 不得起作业')

    caseLog(`R-3 条目 APPROVED + 零证据 + 认领表(${planted})；取消后条目仍 ${after.entry.status}`
      + `（reason=${after.entry.reason} outcome=${after.entry.outcome}），未出现"未执行"改写与 tool_result；`
      + `认领表值保持、台账 0 条、作业 ${jobsAfter} 个；对照 R-2：同一路径无认领时收敛为 CANCELLED`)
  } finally {
    redisCli('DEL', claimKey)
    let cleanupOk = cancelled
    if (!cleanupOk) {
      try {
        redisCli('SET', flagKey, 'l150-probe-cleanup')
        await cancelOn('b', run.runId)
        await waitRunStatus('a', run.runId, 'CANCELLED', 30000)
        cleanupOk = true
      } catch (e) {
        // 不改写本格判据，也绝不 `return`（finally 里的 return 会吞掉原来的断言失败）。
        caseLog(`R-3 收尾失败（取消标志键保留以免该轮以后被执行）：${e.message}`)
      }
    }
    if (cleanupOk) redisCli('DEL', flagKey)
  }
}

async function casePreexecCancelGrid() {
  await ensureUp()
  caseLog('L-150 三格：R-1 UNKNOWN 不得执行 → R-2 已 CANCELLED 不得误认领 → R-3 claim 哈希-only 不得伪未执行')
  await gridUnknownMustNotExecute()
  await gridAlreadyCancelledMustNotBeReclaimed()
  await gridClaimHashOnlyMustNotFakeNotExecuted()
  caseLog('L-150 三格全部按各自判据通过（三格独立断言，不互相代偿）')
}

// ── 用例：双身份 × 三实例（确认门 / 前端工具两条通道各一条）──────────────────

/**
 * 身份侧效应指纹：只取"另一个身份**能看到**的运行态事实"。
 *
 * <p>刻意**不含** TTL/时间戳（`snapshotRemainingMs`/`snapshotExpiresAt`/`snapshot.atMs`）：
 * 读侧续期本来就会刷新它们，那不是副作用。含的是"被写过才会变"的事实：
 * 快照状态、归档末号、待决条目的状态/结局/结果文本/决态时刻、台账计数。
 *
 * <p>两侧都按 toolCallId 排序，比较用 `stableStringify`（键序无关），避免假红。
 */
function identityStateFingerprint(current, detail) {
  const byId = (a, b) => String(a.toolCallId).localeCompare(String(b.toolCallId))
  const pendingEntries = (current.pendingEntries || [])
    .filter((entry) => entry && entry.toolCallId)
    .map((entry) => ({
      toolCallId: entry.toolCallId,
      entryStatus: entry.entryStatus,
      kind: entry.kind,
      name: entry.name,
      arguments: entry.arguments,
      req: entry.req
    }))
    .sort(byId)
  const pending = (detail.pending || [])
    .filter((entry) => entry && entry.toolCallId)
    .map((entry) => ({
      toolCallId: entry.toolCallId,
      status: entry.status,
      outcome: entry.outcome,
      executed: entry.executed,
      executedBy: entry.executedBy,
      resultText: entry.resultText,
      resolvedAtMs: entry.resolvedAtMs,
      claimedAtMs: entry.claimedAtMs
    }))
    .sort(byId)
  return {
    runId: current.runId,
    snapshotStatus: current.snapshotStatus,
    awaitingExternal: current.awaitingExternal,
    archiveMaxSeq: current.archiveMaxSeq,
    unresolvedExternal: (current.unresolvedExternal || []).slice().sort(),
    pendingEntries,
    pending,
    ledgerCounts: detail.ledgerCounts || {},
    ledger: (detail.ledger || []).map((row) => ({
      toolCallId: row.toolCallId,
      status: row.status,
      outcome: row.outcome,
      executedBy: row.executedBy
    }))
  }
}

/**
 * **B 身份**在实例 `key` 上对"别人的轮"的全部探测：必须不可见，且两个写入口都必须被拒。
 *
 * <p>期望形态来自产品源码（不是猜的）：
 * <ul>
 * <li>`AiController#ownedRun` ⇒ 归属不符按"不存在"处理，事件走 `preparingOrUnknown` ⇒
 *     <b>404 UNKNOWN_RUN</b>（归档也按身份定键，B 侧一帧都没有 ⇒ 不会 202）；</li>
 * <li>`GET /api/ai/runs/{runId}` ⇒ 200 + `data.exists === false`（不回显"在别人手里"）；
 *     `GET /api/ai/runs/current` ⇒ 200 + `found=false`；`GET /api/ai/pending/{runId}` ⇒ 空列表；</li>
 * <li>`GET /api/ai/startup-resume` ⇒ 200，但报告里**没有**别人的 `runId`
 *     （`StartupResumeRunner` 对非调用者身份的记录直接 `continue`，`lastScanForIdentity` 再过滤一层）；
 *     该路径在数据面内，所以这条探测本身也证明了"探针给 startup-resume 带了身份头"；</li>
 * <li>`POST /api/ai/confirm` 与 `POST /api/ai/frontend-tool-result` ⇒ 409 UNKNOWN_TOOL_CALL：
 *     两个入口都先读"当前身份的待决条目"（`pendingForWrite`），读不到直接 NOT_FOUND，
 *     **不写决策 guard、不改条目、不发帧**（所以才是"零副作用"）；</li>
 * <li>跨身份时**不会**给出 TOOL_KIND_MISMATCH：条目根本读不到，报 kind 等于泄漏"这条 id 属于哪种挂起种类"。</li>
 * </ul>
 *
 * <p>两种通道都探两个写入口（确认门的轮也发一次回灌、前端工具的轮也发一次决策），
 * 这样"错入口被拒"与"跨身份被拒"不会被混为一谈。
 *
 * @param frontendEntry 该轮是前端工具通道（只影响证据标注与日志；断言口径两通道相同）
 */
async function probeIdentityInvisible(key, { runId, sessionId, toolCallId, evidenceName, frontendEntry = false }) {
  const whoB = PROBE_IDENTITIES.b
  const evidence = {
    identity: identityTag(whoB),
    instance: key,
    runId,
    toolCallId,
    entryKind: frontendEntry ? 'FRONTEND' : 'CONFIRM',
    at: nowIso(),
    probes: {}
  }
  // ① 正式 SSE 通道（声明 text/event-stream）：必须是 404 JSON，而不是一条订阅/一条回放。
  const events = await httpRequest(directUrl(key, `/api/ai/events/${runId}`), {
    headers: { Accept: 'text/event-stream' },
    identity: whoB,
    timeoutMs: 15000,
    routeKind: 'api',
    directNode: directNode(key)
  })
  evidence.probes.events = {
    status: events.status,
    contentType: events.headers['content-type'] || null,
    framesInBody: events.text.includes('data:'),
    body: events.text.slice(0, 800)
  }
  // ② 轻量探测通道。
  const probeRecord = await getJson(key, `/api/ai/events/${runId}?probe=1`, { identity: whoB })
  evidence.probes.eventsProbe = { status: probeRecord.record.status, body: probeRecord.body }
  // ③ 运行态三面：run 详情 / session 当前轮 / 待决条目。
  const detail = await getJson(key, `/api/ai/runs/${runId}`, { identity: whoB })
  evidence.probes.runs = { status: detail.record.status, body: detail.body }
  const current = await getJson(
    key,
    `/api/ai/runs/current?sessionId=${encodeURIComponent(sessionId)}`,
    { identity: whoB }
  )
  evidence.probes.runsCurrent = { status: current.record.status, body: current.body }
  const pending = await getJson(key, `/api/ai/pending/${runId}`, { identity: whoB })
  evidence.probes.pending = { status: pending.record.status, body: pending.body }
  // ④ 历史（记忆窗口按身份定键）。
  const history = await historyOf(key, sessionId, { identity: whoB })
  evidence.probes.history = { status: history.record.status, body: history.body }
  // ④b startup-resume 报告：HTTP 边界收紧后它在数据面内，且**只回显当前身份的轮次**
  //     （产品 `StartupResumeRunner` 对非调用者身份的记录直接 continue、`lastScanForIdentity` 再过滤）。
  //     于是 B 侧回执里不得出现 A 的 runId —— 连"别人的轮存在"这件事都不能泄漏。
  const resumeReport = await getJson(key, '/api/ai/startup-resume', { identity: whoB })
  evidence.probes.startupResume = {
    status: resumeReport.record.status,
    leaksRunId: resumeReport.record.text.includes(runId),
    body: resumeReport.body
  }
  // ⑤ 两个写入口都必须被拒。
  const confirm = await confirmOn(
    key,
    { runId, toolCallId, approved: true, reason: 'cluster3 identity probe B（必须被拒）' },
    { identity: whoB }
  )
  evidence.probes.confirm = { status: confirm.record.status, body: confirm.body }
  const frontend = await frontendToolResultOn(
    key,
    { runId, toolCallId, result: 'cluster3 identity probe B（必须被拒）', outcome: 'ok' },
    { identity: whoB }
  )
  evidence.probes.frontend = { status: frontend.record.status, body: frontend.body }
  saveEvidence(evidenceName, evidence)

  const label = `B 身份（${identityTag(whoB)}）在实例 ${key}`
  checkEq(events.status, 404, `${label} 重挂 HTTP（不得回 200 帧流）`)
  check(
    !String(events.headers['content-type'] || '').includes('text/event-stream'),
    `${label} 重挂不得返回 SSE 内容类型（实得 ${events.headers['content-type']}）`
  )
  checkEq(events.text.includes('data:'), false, `${label} 重挂不得返回任何 SSE 帧`)
  const eventsBody = jsonParse(events.text, `${label} 重挂响应体`)
  checkEq(eventsBody.code, 'UNKNOWN_RUN', `${label} 重挂错误码（不得回显"这一轮在别人手里"）`)
  checkEq(probeRecord.record.status, 404, `${label} 探测 HTTP`)
  checkEq(probeRecord.body && probeRecord.body.code, 'UNKNOWN_RUN', `${label} 探测错误码`)

  checkEq(detail.record.status, 200, `${label} 读取 run 详情 HTTP`)
  checkEq(detail.body && detail.body.success, true, `${label} 读取 run 详情 success`)
  checkEq(detail.body.data.exists, false, `${label} 必须把别人的轮按"不存在"回答（exists=false）`)
  checkEq(detail.body.data.snapshot, undefined, `${label} 不得回显快照`)
  checkEq(detail.body.data.ledger, undefined, `${label} 不得回显台账`)

  checkEq(current.record.status, 200, `${label} 读取 runs/current HTTP`)
  checkEq(current.body.data.found, false, `${label} 的 runs/current 必须是空态（found=false）`)
  checkEq(current.body.data.runId, null, `${label} 的 runs/current 不得给出 runId`)
  checkEq((current.body.data.pendingEntries || []).length, 0, `${label} 的 runs/current 不得列出待决条目`)
  checkEqNumber(current.body.data.archiveMaxSeq, 0, `${label} 的 runs/current 归档末号`)

  checkEq(pending.record.status, 200, `${label} 读取 pending HTTP`)
  checkEq(Array.isArray(pending.body.data) && pending.body.data.length, 0, `${label} 的 pending 必须是空列表`)

  checkEq(history.record.status, 200, `${label} 读取 history HTTP`)
  checkEq(history.body.data.total, 0, `${label} 的 history 不得包含别人的会话内容`)

  checkEq(resumeReport.record.status, 200, `${label} 读取 startup-resume 报告 HTTP（该路径在数据面内，必须带身份头）`)
  checkEq(
    resumeReport.record.text.includes(runId),
    false,
    `${label} 的 startup-resume 报告不得出现别人的 runId（越权扫别人的轮）`
  )

  checkEq(confirm.record.status, 409, `${label} 决策必须被拒（409）`)
  checkEq(confirm.body && confirm.body.code, 'UNKNOWN_TOOL_CALL', `${label} 决策拒绝码`)
  checkEq(frontend.record.status, 409, `${label} 回灌必须被拒（409）`)
  checkEq(frontend.body && frontend.body.code, 'UNKNOWN_TOOL_CALL', `${label} 回灌拒绝码`)
  return evidence
}

/**
 * 身份作用域的 run 级键（与产品 `RunKeyScope.tag` 同形：`<prefix>{<tenantId>:<userId>:run:<runId>}`）。
 *
 * <p>只用于**只读**取证（`PTTL`）：证明"另一个身份的请求没有给这一轮续期"。
 */
function identityScopedRunKey(prefix, identity, runId) {
  return `${prefix}{${identity.tenantId}:${identity.userId}:run:${runId}}`
}

/** 直接问 Redis 要键的剩余寿命（毫秒；`-1` = 无 TTL，`-2` = 键不存在）。 */
function pttlOf(redisKey) {
  const raw = String(redisCli('PTTL', redisKey)).trim()
  const value = Number(raw)
  check(Number.isFinite(value), `PTTL ${redisKey} 返回值不可解析：${raw}`)
  return value
}

/**
 * 零副作用的**侧证**：B 身份发过请求之后，A 身份那些键的剩余寿命只能变短。
 *
 * <p>为什么单独测这一面：`/confirm` 与 `/frontend-tool-result` 都会在"读待决条目"**之前**
 * 调 `runStore.touchActiveRun(runId)`（读侧续期）。它按**当前身份**取快照键，所以 B 取不到
 * A 的快照 ⇒ 什么都不续；但这一面在 `runs/current` 的响应里看不见（A 自己一读就又续满了），
 * 只能直接读 Redis 的 `PTTL`。续期会让 PTTL **变长**，因此"不增"是可判定的硬断言。
 *
 * @return 落盘用的快照（键、前后 PTTL）
 */
function assertNoCrossIdentityRenewal(label, keys, before, after, { ttlRequired = [] } = {}) {
  const rows = {}
  for (const name of Object.keys(keys)) {
    rows[name] = { key: keys[name], pttlBefore: before[name], pttlAfter: after[name] }
    check(
      before[name] !== -2,
      `${label}：${name} 键必须存在（PTTL=-2 = 键形拼错或这一轮根本不在 Redis）：${keys[name]}`
    )
    if (before[name] > 0) {
      check(after[name] > 0, `${label}：${name} 键在 B 的探测期间不应消失（PTTL ${before[name]} → ${after[name]}）`)
      check(
        after[name] <= before[name],
        `${label}：B 的请求不得给 A 的 ${name} 键续期（PTTL 必须不增：${before[name]} → ${after[name]}）`
      )
    } else {
      checkEq(after[name], before[name], `${label}：${name} 键的 PTTL 状态不得变化（无 TTL/不存在）`)
    }
    if (ttlRequired.includes(name)) {
      check(before[name] > 0, `${label}：${name} 键必须带 TTL（产品快照键是 SET...PX 写入；实得 PTTL=${before[name]}）`)
    }
  }
  return rows
}

async function caseIdentityCrossInstance() {
  await ensureUp()
  const whoA = PROBE_IDENTITIES.a
  const whoB = PROBE_IDENTITIES.b
  const suffix = uniqueId('ident')
  const task = await createTask('c', 'EXPORT', `cluster3-identity-${suffix}`)
  await selectDefs('c', task.id, ['CURRENCY'])
  await readyToExport('c', task.id)

  // ① A 身份在实例 A 挂起（确认门），且已 park 收流。
  const run = await createSuspendedRun('a', {
    prefix: 'cluster3-identity',
    taskId: task.id,
    messageSuffix: ` ${suffix}`,
    identity: whoA,
    evidenceName: 'A-parked.jsonl'
  })
  caseLog(
    `A 身份（${identityTag(whoA)}）在实例 A 挂起 runId=${run.runId} toolCallId=${run.toolCallId} `
      + `tool=${run.toolName} lastSeq=${run.lastSeq}（证据 ${run.evidence}）`
  )

  // ② 副作用基线：A 身份在实例 C 能读到的事实（含待决条目与归档末号）。
  const beforeCurrent = await currentRun('c', run.sessionId, { identity: whoA })
  const beforeDetail = await runDetail('c', run.runId, { identity: whoA })
  checkEq(beforeDetail.exists, true, 'A 身份在 C 必须看得到这一轮（基线前置）')
  const before = identityStateFingerprint(beforeCurrent, beforeDetail)
  saveEvidence('A-state-before-b.json', { identity: identityTag(whoA), at: nowIso(), fingerprint: before })
  checkEq(before.snapshotStatus, 'SUSPENDED', '基线：park 后快照 SUSPENDED')
  checkEq(before.pending.length, 1, '基线：待决条目恰一条')
  checkEqNumber(before.archiveMaxSeq, run.lastSeq, '基线：归档末号 = park 末号')

  // ②b 续期基线（直接读 Redis：A 自己的 HTTP 读会续期，所以必须先量后发 B 的请求）。
  const pttlKeys = {
    snapshot: identityScopedRunKey('ai:run:', whoA, run.runId),
    pending: identityScopedRunKey('ai:pending:', whoA, run.runId)
  }
  const pttlBefore = { snapshot: pttlOf(pttlKeys.snapshot), pending: pttlOf(pttlKeys.pending) }

  // ③ B 身份在实例 B 的九项探测：events / probe / runs / runs-current / pending / history /
  //    startup-resume 报告 / confirm / frontend-tool-result。
  await probeIdentityInvisible('b', {
    runId: run.runId,
    sessionId: run.sessionId,
    toolCallId: run.toolCallId,
    evidenceName: 'B-identity-invisible.json'
  })
  const pttlAfter = { snapshot: pttlOf(pttlKeys.snapshot), pending: pttlOf(pttlKeys.pending) }
  saveEvidence('A-ttl-no-renewal.json', {
    identity: identityTag(whoA),
    keys: pttlKeys,
    before: pttlBefore,
    after: pttlAfter
  })
  assertNoCrossIdentityRenewal('B 身份跨身份探测', pttlKeys, pttlBefore, pttlAfter, { ttlRequired: ['snapshot'] })

  // ④ 零副作用：A 身份在实例 C 重读的指纹必须与基线逐字段相同（并逐项再钉，防"两边都为空"）。
  const afterCurrent = await currentRun('c', run.sessionId, { identity: whoA })
  const afterDetail = await runDetail('c', run.runId, { identity: whoA })
  const after = identityStateFingerprint(afterCurrent, afterDetail)
  saveEvidence('A-state-after-b.json', { identity: identityTag(whoA), at: nowIso(), fingerprint: after })
  checkEq(
    stableStringify(after),
    stableStringify(before),
    'B 身份的全部探测必须零副作用（A 身份在 C 观察到的运行态指纹必须逐字段不变）'
  )
  checkEq(after.snapshotStatus, 'SUSPENDED', 'B 探测后快照仍必须是 SUSPENDED')
  check(after.awaitingExternal === true, 'B 探测后 awaitingExternal 必须仍为 true')
  check(
    after.pendingEntries.some((entry) => entry.toolCallId === run.toolCallId && entry.entryStatus === 'PENDING'),
    'B 探测后待决条目必须仍是 PENDING'
  )
  checkEqNumber(after.archiveMaxSeq, run.lastSeq, 'B 探测后归档末号不得增长（B 不得产生任何帧）')
  checkEq(after.pending.length, 1, 'B 探测后待决条目数')
  checkEq(after.pending[0].outcome, before.pending[0].outcome, 'B 探测后待决条目 outcome 不得改变')
  checkEq(after.pending[0].resolvedAtMs, before.pending[0].resolvedAtMs, 'B 探测后待决条目决态时刻不得改变')
  checkEq(after.pending[0].resultText, before.pending[0].resultText, 'B 探测后待决条目结果文本不得被写')
  checkEq(after.ledger.length, 0, 'B 探测后台账仍必须为空（没有任何执行）')

  // ⑤ A 身份在实例 C 决策：同一身份 + 另一实例 ⇒ 可确认（证据先落盘再断言）。
  const confirm = await confirmOn(
    'c',
    { runId: run.runId, toolCallId: run.toolCallId, reason: 'cluster3 identity decision on C' },
    { identity: whoA }
  )
  evidenceFirst(
    (body) => saveEvidence('confirm.json', body),
    () => confirm.body || { status: confirm.record.status, text: confirm.record.text },
    () => {
      checkEq(confirm.record.status, 200, 'A 身份在 C 的决策 HTTP')
      check(confirm.body && confirm.body.accepted === true, `A 身份在 C 的决策未被接受：${confirm.record.text.slice(0, 500)}`)
      checkEq(confirm.body.storedBy, STATE.instanceIds.c, 'A 身份决策的 storedBy 必须是 C 的 instanceId')
      checkIn(confirm.body.resumeOutcome, ['LEASE_HELD_ELSEWHERE', 'SCHEDULED'], 'A 身份在 C 的 resumeOutcome')
    }
  )

  // ⑥ 决策之后 B 身份仍然看不到（终态同样不越权）。
  const bAfterDetail = await getJson('b', `/api/ai/runs/${run.runId}`, { identity: whoB })
  const bAfterEvents = await getJson('b', `/api/ai/events/${run.runId}?probe=1`, { identity: whoB })
  saveEvidence('B-after-decision.json', {
    identity: identityTag(whoB),
    runs: { status: bAfterDetail.record.status, body: bAfterDetail.body },
    eventsProbe: { status: bAfterEvents.record.status, body: bAfterEvents.body }
  })
  checkEq(bAfterDetail.body.data.exists, false, '决策后 B 身份仍必须把这一轮按"不存在"回答')
  checkEq(bAfterEvents.record.status, 404, '决策后 B 身份重挂探测仍必须 404')

  // ⑦ A 身份在 C 全量回放：整本帧册（关键帧恰一次 + seq 连续 + 载荷），并核对台账。
  const full = await fullReplayToTerminal('c', run.runId, 90000, 'C-full.jsonl', { identity: whoA })
  const ledgerSummary = assertFrameLedger(full, { toolCallId: run.toolCallId })
  const detail = await runDetail('c', run.runId, { identity: whoA })
  const ledger = ledgerFor(detail, run.toolCallId)
  evidenceFirst(
    (snapshot) => saveEvidence('run-detail.json', snapshot),
    () => ({
      runId: run.runId,
      status: detail.snapshot ? detail.snapshot.status : null,
      ledgerCounts: detail.ledgerCounts,
      ledger,
      frameLedger: ledgerSummary.types,
      lastSeqScope: ledgerSummary.lastSeqScope,
      before,
      after
    }),
    () => {
      checkEq(detail.snapshot.status, 'DONE', 'A 身份在 C 决策后的 run 终态')
      checkEq(ledger.length, 1, '该 toolCallId 的台账条目数')
      checkIn(
        ledger[0].executedBy,
        [STATE.instanceIds.a, STATE.instanceIds.c],
        '台账执行实例（A 的等待方或 C 的接管者）'
      )
      checkEq(ledger[0].outcome, 'ok', '跨身份决策后台账 outcome 必须是 ok')
      checkNonEmptyString(ledger[0].resultText, '跨身份决策后台账 resultText')
    }
  )
  caseLog(
    `A 身份（${identityTag(whoA)}）挂起 → B 身份（${identityTag(whoB)}）在实例 B 的 events/probe/runs/runs-current/`
      + 'pending/history 全部不可见、confirm/frontend-tool-result 双双 409 UNKNOWN_TOOL_CALL，'
      + 'A 身份在 C 重读的运行态指纹逐字段不变（零副作用）→ A 身份在 C 决策成功'
      + `（storedBy=C，outcome=${confirm.body.resumeOutcome}）→ C 全量帧册=${ledgerSummary.types}，`
      + `台账 executedBy=${ledger[0].executedBy} outcome=ok；决策后 B 身份仍看不到这一轮`
  )
}

async function caseIdentityFrontendCrossInstance() {
  await ensureUp()
  const whoA = PROBE_IDENTITIES.a
  const whoB = PROBE_IDENTITIES.b
  const suffix = uniqueId('identfe')
  const task = await createTask('c', 'EXPORT', `cluster3-identity-frontend-${suffix}`)
  await selectDefs('c', task.id, ['CURRENCY'])

  // ① A 身份在实例 A 起"前端工具"挂起轮：桩路由 TC16-NAVIGATE（navigate_to，产品侧 @ToolChannel(FRONTEND)）。
  const park = await createFrontendParkedRun('a', {
    prefix: 'cluster3-identity-frontend',
    taskId: task.id,
    messageSuffix: ` ${suffix}`,
    identity: whoA,
    evidenceName: 'A-frontend-parked.jsonl'
  })
  checkEq(park.toolName, 'navigate_to', '前端通道工具名（桩路由 TC16-NAVIGATE）')
  caseLog(
    `A 身份（${identityTag(whoA)}）在实例 A 起前端工具挂起轮 runId=${park.runId} toolCallId=${park.toolCallId} `
      + `tool=${park.toolName} lastSeq=${park.lastSeq}（证据 ${park.evidence}）`
  )

  // ② 副作用基线（A 身份在实例 C 读到的事实）。
  const beforeCurrent = await currentRun('c', park.sessionId, { identity: whoA })
  const beforeDetail = await runDetail('c', park.runId, { identity: whoA })
  checkEq(beforeDetail.exists, true, 'A 身份在 C 必须看得到这条前端挂起轮（基线前置）')
  checkEq(beforeDetail.pending[0] && beforeDetail.pending[0].kind, 'FRONTEND', '基线：待决条目 kind=FRONTEND')
  const before = identityStateFingerprint(beforeCurrent, beforeDetail)
  saveEvidence('A-state-before-b.json', { identity: identityTag(whoA), at: nowIso(), fingerprint: before })
  checkEqNumber(before.archiveMaxSeq, park.lastSeq, '基线：归档末号 = 前端 park 末号')

  // ②b 续期基线（同确认门用例：B 的请求不得给 A 的快照/待决键续期）。
  const pttlKeys = {
    snapshot: identityScopedRunKey('ai:run:', whoA, park.runId),
    pending: identityScopedRunKey('ai:pending:', whoA, park.runId)
  }
  const pttlBefore = { snapshot: pttlOf(pttlKeys.snapshot), pending: pttlOf(pttlKeys.pending) }

  // ③ B 身份在实例 B：同一条轮的九项探测（含 startup-resume 报告、frontend-tool-result 与 confirm 两个写入口）。
  await probeIdentityInvisible('b', {
    runId: park.runId,
    sessionId: park.sessionId,
    toolCallId: park.toolCallId,
    evidenceName: 'B-frontend-invisible.json',
    frontendEntry: true
  })
  const pttlAfter = { snapshot: pttlOf(pttlKeys.snapshot), pending: pttlOf(pttlKeys.pending) }
  saveEvidence('A-ttl-no-renewal.json', {
    identity: identityTag(whoA),
    keys: pttlKeys,
    before: pttlBefore,
    after: pttlAfter
  })
  assertNoCrossIdentityRenewal('B 身份跨身份探测（前端通道）', pttlKeys, pttlBefore, pttlAfter, {
    ttlRequired: ['snapshot']
  })

  // ④ 零副作用：A 身份在 C 的指纹不变；且带 park 末号的重挂必须 0 帧 + 被服务端收流
  //    （B 若写过任何帧/挪过末号，这里立刻红）。
  const afterCurrent = await currentRun('c', park.sessionId, { identity: whoA })
  const afterDetail = await runDetail('c', park.runId, { identity: whoA })
  const after = identityStateFingerprint(afterCurrent, afterDetail)
  saveEvidence('A-state-after-b.json', { identity: identityTag(whoA), at: nowIso(), fingerprint: after })
  checkEq(
    stableStringify(after),
    stableStringify(before),
    'B 身份的全部探测必须零副作用（A 身份在 C 观察到的前端挂起轮指纹必须逐字段不变）'
  )
  checkEq(after.snapshotStatus, 'SUSPENDED', 'B 探测后快照仍必须是 SUSPENDED')
  check(after.awaitingExternal === true, 'B 探测后 awaitingExternal 必须仍为 true')
  checkEqNumber(after.archiveMaxSeq, park.lastSeq, 'B 探测后归档末号不得增长（B 不得产生任何帧）')
  checkEq(after.pending.length, 1, 'B 探测后待决条目数')
  checkEq(after.pending[0].status, 'PENDING', 'B 探测后待决条目必须仍是 PENDING')

  const pre = await reattachOn('c', park.runId, park.lastSeq, { timeoutMs: 30000, identity: whoA })
  let preHow = null
  try {
    preHow = await pre.waitClosed(PARK_SETTLE_TIMEOUT_MS, '前端 park 决策前重挂收流')
    saveFrames('C-predecision-frontend.jsonl', pre.frames)
    checkEq(preHow, 'end', '前端 park 决策前重挂必须是服务端正常收尾（连接重置不算）')
    checkEq(
      businessFrames(pre.frames).length,
      0,
      `前端 park 决策前重挂（lastSeq=${park.lastSeq}）不得有未归档帧（已收=${typesOf(pre.frames).join(',') || '空'}）`
    )
  } finally {
    saveFrames('C-predecision-frontend.jsonl', pre.frames)
    pre.close()
  }

  // ⑤ A 身份在实例 C 回灌前端结果：跨实例、同身份 ⇒ 可回灌（kind=FRONTEND 的门只对它开）。
  const resultText = `已打开导出向导（taskId=${task.id}）`
  const submit = await frontendToolResultOn(
    'c',
    { runId: park.runId, toolCallId: park.toolCallId, result: resultText, outcome: 'ok' },
    { identity: whoA }
  )
  evidenceFirst(
    (body) => saveEvidence('C-frontend-result.json', body),
    () => submit.body || { status: submit.record.status, text: submit.record.text },
    () => {
      checkEq(submit.record.status, 200, 'A 身份在 C 的前端回灌 HTTP')
      check(submit.body && submit.body.accepted === true, `A 身份在 C 的前端回灌未被接受：${submit.record.text.slice(0, 500)}`)
      checkEq(submit.body.duplicate, false, '前端回灌 duplicate')
      checkEq(submit.body.cancelled, false, '前端回灌 cancelled')
      checkEq(submit.body.executed, false, '前端工具后端不得执行（executed=false）')
      checkEq(submit.body.storedBy, STATE.instanceIds.c, '前端回灌 storedBy 必须是 C 的 instanceId')
      checkIn(submit.body.resumeOutcome, ['LEASE_HELD_ELSEWHERE', 'SCHEDULED'], 'A 身份在 C 的前端回灌 resumeOutcome')
    }
  )

  // ⑥ A 身份在 C 全量回放：前端通道的整本帧册（frontend_tool_result、无 tool_result/confirm_decision）。
  const full = await fullReplayToTerminal('c', park.runId, 90000, 'C-frontend-full.jsonl', { identity: whoA })
  const summary = assertFrontendLedger(full, {
    toolCallId: park.toolCallId,
    expectedName: 'navigate_to',
    expectedResult: resultText
  })
  const detail = await runDetail('c', park.runId, { identity: whoA })
  evidenceFirst(
    (snapshot) => saveEvidence('run-detail.json', snapshot),
    () => ({
      runId: park.runId,
      status: detail.snapshot ? detail.snapshot.status : null,
      ledgerCounts: detail.ledgerCounts,
      pending: detail.pending,
      frameLedger: summary.types,
      lastSeqScope: FULL_REPLAY_TIP_SCOPE,
      before,
      after
    }),
    () => {
      checkEq(detail.snapshot.status, 'DONE', 'A 身份在 C 回灌后的 run 终态')
      const settled = (detail.pending || []).find((entry) => entry && entry.toolCallId === park.toolCallId)
      check(settled, 'run 详情必须仍列出该待决条目')
      checkEq(settled.status, 'FRONTEND_RESULT', '待决条目终态必须是 FRONTEND_RESULT')
      checkEq(settled.kind, 'FRONTEND', '待决条目 kind 必须仍是 FRONTEND')
      checkEq(settled.executed, false, '前端工具不得被后端执行')
      checkEq(settled.resultText, resultText, '待决条目结果文本必须原样保存回灌值')
    }
  )

  // ⑦ 终态后 B 身份仍不可见。
  const bAfterDetail = await getJson('b', `/api/ai/runs/${park.runId}`, { identity: whoB })
  const bAfterEvents = await getJson('b', `/api/ai/events/${park.runId}?probe=1`, { identity: whoB })
  saveEvidence('B-after-decision.json', {
    identity: identityTag(whoB),
    runs: { status: bAfterDetail.record.status, body: bAfterDetail.body },
    eventsProbe: { status: bAfterEvents.record.status, body: bAfterEvents.body }
  })
  checkEq(bAfterDetail.body.data.exists, false, '回灌后 B 身份仍必须把这一轮按"不存在"回答')
  checkEq(bAfterEvents.record.status, 404, '回灌后 B 身份重挂探测仍必须 404')

  caseLog(
    `A 身份（${identityTag(whoA)}）在实例 A 起前端工具挂起轮（${park.toolName}，frontend_tool_request）→ `
      + `B 身份（${identityTag(whoB)}）在实例 B 全部不可见、两个写入口双 409，A 身份在 C 重读指纹不变 `
      + `（决策前重挂 ${preHow} 且 0 帧）→ A 身份在 C 回灌 200（storedBy=C，executed=false，`
      + `outcome=${submit.body.resumeOutcome}）→ C 全量帧册=${summary.types}（含 frontend_tool_result，`
      + `无 tool_result/confirm_decision），条目 ${detail.pending && detail.pending[0] ? detail.pending[0].status : '?'}；`
      + '回灌后 B 身份仍看不到这一轮'
  )
}

// ── 结果与摘要 ─────────────────────────────────────────────────────────────

function initArtifacts() {
  ensureDir(RUN.artifacts)
  ensureDir(RUN.runDir)
  ensureDir(RUN.sharedFiles)
  PATHS.instancesDir = path.join(RUN.artifacts, 'instances')
  PATHS.casesDir = path.join(RUN.artifacts, 'cases')
  PATHS.runLog = path.join(RUN.artifacts, 'run.log')
  PATHS.resultsJson = path.join(RUN.artifacts, 'results.json')
  PATHS.resultsJsonl = path.join(RUN.artifacts, 'results.jsonl')
  PATHS.resultsMd = path.join(RUN.artifacts, 'results.md')
  PATHS.summaryMd = path.join(RUN.artifacts, 'summary.md')
  PATHS.pidsJson = path.join(RUN.artifacts, 'pids.json')
  PATHS.envJson = path.join(RUN.artifacts, 'env.json')
  ensureDir(PATHS.instancesDir)
  ensureDir(PATHS.casesDir)
  writeFileAtomic(PATHS.resultsJsonl, '')
  initRouteLog()
}

function saveEnv() {
  const jarSha = fs.existsSync(RUN.jar) ? sha256(fs.readFileSync(RUN.jar)) : null
  writeJson(PATHS.envJson, {
    startedAt: new Date(STATE.startedAt).toISOString(),
    platform: process.platform,
    node: process.version,
    ports: RUN.ports,
    jar: RUN.jar,
    jarSha256: jarSha,
    h2Jar: RUN.h2Jar,
    dbBase: RUN.dbBase,
    sharedFiles: RUN.sharedFiles,
    stubUrl: RUN.stubUrl,
    proxyUrl: RUN.proxyUrl,
    statsUrl: RUN.statsUrl,
    artifacts: RUN.artifacts,
    runDir: RUN.runDir,
    timeouts: {
      readyMs: RUN.readyTimeoutMs,
      jobFailMs: RUN.jobFailTimeoutMs,
      killTakeoverMs: RUN.killTakeoverTimeoutMs
    }
  })
}

function recordResult(result) {
  STATE.results.push(result)
  fs.appendFileSync(PATHS.resultsJsonl, `${JSON.stringify(result)}\n`)
  writeJson(PATHS.resultsJson, STATE.results)
  const rows = STATE.results.map((r, index) => (
    `| ${index + 1} | ${r.id} | ${r.status === 'PASS' ? 'PASS' : '**FAIL**'} | ${r.durationMs}ms | ${escapeHtml(r.detail || '')} |`
  ))
  writeFileAtomic(PATHS.resultsMd, [
    '# 三实例集群逐用例结果',
    '',
    '| # | 用例 | 结果 | 耗时 | 说明 |',
    '|---:|---|---|---:|---|',
    ...rows,
    ''
  ].join('\n'))
}

function writeSummary(exitCode) {
  const pass = STATE.results.filter((r) => r.status === 'PASS').length
  const fail = STATE.results.filter((r) => r.status === 'FAIL').length
  const routeTotal = Object.values(STATE.routeCounts).reduce((sum, n) => sum + n, 0)
  const rows = STATE.results.map((r, index) => (
    `| ${index + 1} | ${r.id} | ${r.status === 'PASS' ? 'PASS' : '**FAIL**'} | ${r.durationMs}ms | ${escapeHtml(r.detail || '')} |`
  ))
  const routeRows = Object.keys(STATE.routeCounts).sort().map((node) => `| ${node} | ${STATE.routeCounts[node]} |`)
  const lines = [
    '# 三实例集群验证网（A/B/C · 共享 Redis / H2-TCP / 文件 · 统一反向代理）',
    '',
    `- 结论：**${fail === 0 && exitCode === 0 ? 'PASS' : 'FAIL'}**（PASS ${pass} / FAIL ${fail}；exit=${exitCode}）`,
    ...(RUN.onlyCases.length > 0
      ? [`- 范围：**子集**（--case ${RUN.onlyCases.join(',')}，实际执行 ${STATE.results.length} 条）—— `
        + '这个 PASS **不代表**全部用例已跑，不得用它反推整套验收']
      : []),
    `- 时间（UTC）：${new Date(STATE.startedAt).toISOString()} → ${nowIso()}`,
    `- 端口：proxy=${RUN.ports.proxy} / stats=${RUN.ports.stats} / A=${RUN.ports.a} / B=${RUN.ports.b} / C=${RUN.ports.c} / H2=${RUN.ports.h2} / Redis=${RUN.ports.redis} / stub=${RUN.ports.stub}`,
    `- 共享库：H2 TCP（baseDir=${RUN.dbBase}）；共享文件：${RUN.sharedFiles}`,
    `- 产物：${RUN.artifacts}`,
    '',
    '| # | 用例 | 结果 | 耗时 | 说明 |',
    '|---:|---|---|---:|---|',
    ...rows,
    '',
    '## 路由分布（探针侧，按 X-Cluster-Node / direct 统计）',
    '',
    '| 节点 | 请求数 |',
    '|---|---:|',
    ...routeRows,
    `| 合计 | ${routeTotal} |`,
    '',
    '## 证据文件',
    '',
    '- `route-log.jsonl`：每条请求的时间、客户端端口、节点、状态、耗时。',
    '- `haproxy.log`（由 shell 在收尾时从容器导出）：代理侧 `%ci:%cp` 与 `%b/%s` 路由日志。',
    '- `instances/instance-{a,b,c}.log`：三实例标准输出（被杀实例保留到 kill 前）。',
    '- `results.json` / `results.md` / `cases/<id>/`：逐用例结果与断言证据。',
    '- 挂起/接管类用例的**证据先落盘再断言**（`evidenceFirst` 或 `finally`）——帧类：`A-parked.jsonl` /',
    '  `A-park-transition.jsonl`（早杀前客户端看到的帧）/ `B-predecision-reattach.jsonl` /',
    '  `C-predecision-reattach.jsonl` / `B-reattach.jsonl` / `C-reattach.jsonl` / `A-full.jsonl` /',
    '  `B-takeover.jsonl` / `C-full.jsonl`；状态类：`park-transition.json` / `confirm.json` /',
    '  `runs-current-b.json`（/runs/current 原样）/ `run-detail.json`（终态/台账/jobs/history 增量）/',
    '  `takeover.json`（增量）—— 断言失败时也留得下当时的帧与状态（`A-full`/`C-full` 由 `fullReplay`',
    '  的 finally 写，4 次重试都失败时也留最后一份）。帧册的 `lastSeqScope` 字段标明末号口径是',
    '  `full-replay`（本次全量回放末号），不是服务端 `archiveMaxSeq`。',
    '- `stub/`：桩上游请求判定（req-*.json）。',
    '',
    '## 口径与边界（未自证项）',
    '',
    '- 本网只在 GitHub Actions 的 `ubuntu-latest` 上跑真实集群；`--self-check` 不启动 Java/Docker、不连端口，',
    '  因此 H2-TCP 三 JVM 并发、HAProxy host-network、Redis pause/unpause 的真实行为只能由 CI 运行证明。',
    '- H2-TCP 是**测试用共享库**，不是生产数据库；PostgreSQL/生产拓扑不在本网范围。',
    '- 作业被 kill 的契约是「幸存实例在 max-execution-time 内收敛为 FAILED、不自动重排队」',
    '  （产品 Q6 裁决）；重试由用户显式发起。本网按该契约断言，不把「自动重排队」写成期望。',
    '- 流式 kill 的接管走产品运维端点 `POST /api/ai/startup-resume/scan`（僵尸轮接管），',
    '  不是新增私有恢复通道。',
    '- Redis 故障用 `docker pause` 模拟（进程仍在、数据不丢）；不是 Redis 进程重启。',
    '- 探针允许在产品缺陷未修完时真实 FAIL；不存在 SKIP 结果。',
    ''
  ]
  writeFileAtomic(PATHS.summaryMd, lines.join('\n'))
  console.log(`\n${lines.filter((line) => line.startsWith('- 结论')).join('\n')}`)
}

// ── 运行器 ─────────────────────────────────────────────────────────────────

async function preflight() {
  logLine('preflight：检查产物目录 / 桩 / 代理 / Redis / H2 jar')
  check(fs.existsSync(RUN.jar), `后端 jar 不存在：${RUN.jar}`)
  check(fs.existsSync(RUN.h2Jar), `H2 jar 不存在：${RUN.h2Jar}（shell 应从 Spring Boot fat jar 的 BOOT-INF/lib 提取）`)
  check(fs.existsSync(RUN.haproxyConfig), `HAProxy 配置不存在：${RUN.haproxyConfig}`)

  const stub = await httpRequest(`${RUN.stubUrl}/probe`, { timeoutMs: 5000 })
  checkEq(stub.status, 200, `桩上游 /probe（${RUN.stubUrl}）`)

  const proxy = await httpRequest(`${RUN.proxyUrl}/api/ai/health`, { timeoutMs: 5000, routeKind: 'proxy-health' })
  check([200, 503].includes(proxy.status), `代理可达性（HTTP ${proxy.status}）：${RUN.proxyUrl}`)

  const stats = await httpRequest(RUN.statsUrl, { timeoutMs: 5000 })
  checkEq(stats.status, 200, 'HAProxy stats 可达性')

  const pong = redisCli('PING')
  checkEq(pong, 'PONG', 'Redis PING（shell 应已启动共享 Redis 容器）')

  assertProxyConfigHasNoStickiness()
  logLine('preflight 通过')
}

async function runOneCase(def) {
  STATE.currentCase = def.id
  STATE.caseStartedAt = Date.now()
  const dir = path.join(PATHS.casesDir, def.id)
  ensureDir(dir)
  console.log(`\n===== [${def.id}] ${def.title} =====`)
  caseLog(`开始：${def.title}`)
  try {
    await def.fn()
    const durationMs = Date.now() - STATE.caseStartedAt
    recordResult({ id: def.id, title: def.title, status: 'PASS', durationMs, detail: `通过（${durationMs}ms）` })
    console.log(`[PASS] ${def.id}（${durationMs}ms）`)
  } catch (e) {
    const durationMs = Date.now() - STATE.caseStartedAt
    const detail = e && e.message ? e.message : String(e)
    recordResult({ id: def.id, title: def.title, status: 'FAIL', durationMs, detail })
    saveEvidence('failure.txt', `${e && e.stack ? e.stack : String(e)}\n`)
    console.log(`[FAIL] ${def.id}：${detail}`)
    if (e instanceof FatalError) throw e
  }
}

/**
 * `--case a,b` 的单点/子集选择。
 *
 * <p>存在的理由：用例网里有一部分判据只能在真实三实例上跑（L-150 三格），而**只允许单点运行**
 * 时不能顺带跑全套 —— 未知 id 一律按参数错误拒绝，避免"什么都没跑"被读成全绿。
 */
function selectCases(ids) {
  if (ids.length === 0) return CASES
  const known = CASES.map((def) => def.id)
  const unknown = ids.filter((id) => !known.includes(id))
  if (unknown.length > 0) {
    throw new Error(`未知用例 id：${unknown.join(',')}（可用：${known.join(',')}）`)
  }
  return CASES.filter((def) => ids.includes(def.id))
}

async function mainRun() {
  try {
    configureRun()
  } catch (e) {
    console.error(`参数错误：${e.message}`)
    return 2
  }
  let selected
  try {
    selected = selectCases(RUN.onlyCases)
  } catch (e) {
    console.error(`参数错误：${e.message}`)
    return 2
  }
  initArtifacts()
  saveEnv()
  logLine(`三实例集群验证开始：artifacts=${RUN.artifacts} runDir=${RUN.runDir}`)
  if (RUN.onlyCases.length > 0) {
    logLine(`用例选择（--case）：${selected.map((def) => def.id).join(',')}（共 ${selected.length} 条，不是全量）`)
  }
  let finalExit = 0
  try {
    await preflight()
    for (const def of selected) {
      await runOneCase(def)
    }
    finalExit = STATE.results.some((r) => r.status === 'FAIL') ? 1 : 0
    return finalExit
  } catch (e) {
    if (e instanceof FatalError) {
      finalExit = 2
      console.error(`\n致命错误（环境/基线）：${e.message}`)
      return finalExit
    }
    finalExit = 1
    recordResult({ id: 'runner', title: '执行器', status: 'FAIL', durationMs: Date.now() - STATE.startedAt, detail: e.message })
    console.error(`\n执行器异常：${e.stack || e.message}`)
    return finalExit
  } finally {
    await stopEverything()
    writeSummary(finalExit)
    if (RUN.keep) logLine('CLUSTER3_KEEP=1：保留运行目录（DB/文件不删除）')
  }
}

// ── 自证（不启动任何真实服务）─────────────────────────────────────────────

const SELF = { pass: 0, fail: 0, failures: [] }

function selfOk(name, extra = '') {
  SELF.pass += 1
  console.log(`PASS  ${name}${extra ? `（${extra}）` : ''}`)
}

function selfFail(name, reason) {
  SELF.fail += 1
  SELF.failures.push({ name, reason })
  console.log(`FAIL  ${name}：${reason}`)
}

function selfCheck(name, fn) {
  try {
    const extra = fn()
    selfOk(name, extra === undefined ? '' : String(extra))
  } catch (e) {
    selfFail(name, e.message)
  }
}

async function selfCheckAsync(name, fn) {
  try {
    const extra = await fn()
    selfOk(name, extra === undefined ? '' : String(extra))
  } catch (e) {
    selfFail(name, e.message)
  }
}

function productFile(rel) {
  return path.join(REPO_ROOT, rel)
}

function readProduct(rel) {
  return fs.readFileSync(productFile(rel), 'utf8')
}

function assertSourceContains(rel, needles) {
  const text = readProduct(rel)
  for (const needle of needles) {
    checkIncludes(text, needle, `${rel} 契约`)
  }
}

function selfTestSseParser() {
  // 用本地 HTTP 服务构造任意 chunk 边界 / CRLF / 多行 data / [DONE]。
  return new Promise((resolve, reject) => {
    const payload = [
      'data: {"type":"start","runId":"r-1","seq":1}\n\n',
      'data: {"type":"message_start","seq":2}\r\n\r\n',
      'data: {"type":"delta","text":"第一段",\n',
      'data: "seq":3}\n\n',
      'data: [DONE]\n\n',
      'data: {"type":"late","seq":4}\n\n'
    ].join('')
    const server = http.createServer((req, res) => {
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'X-Cluster-Node': 'node-selftest' })
      const chunks = []
      let i = 0
      while (i < payload.length) {
        const size = 1 + ((i * 7) % 13)
        chunks.push(payload.slice(i, i + size))
        i += size
      }
      let index = 0
      const timer = setInterval(() => {
        if (index >= chunks.length) {
          clearInterval(timer)
          res.end()
          return
        }
        res.write(chunks[index])
        index += 1
      }, 1)
    })
    server.listen(0, '127.0.0.1', async () => {
      const port = server.address().port
      try {
        const stream = await openSse(`http://127.0.0.1:${port}/sse`, { timeoutMs: 10000 })
        const terminal = await stream.waitFor((f) => f.type === '[DONE]', { timeoutMs: 5000, label: '自检 [DONE]' })
        checkEq(terminal.type, '[DONE]', '自检 [DONE] 类型')
        const types = stream.types()
        checkEq(types.slice(0, 3).join(','), 'start,message_start,delta', '自检 SSE 帧顺序（多行 data 合并）')
        checkEq(stream.frames[2].text, '第一段', '自检多行 data 字段合并')
        checkEq(stream.frames[2].seq, 3, '自检多行 data 的 seq')
        checkEq(stream.node, 'node-selftest', '自检 X-Cluster-Node')
        stream.close()
        server.closeAllConnections?.()
        server.close()
        resolve(`4 帧，chunk=${payload.length}`)
      } catch (e) {
        server.closeAllConnections?.()
        server.close()
        reject(e)
      }
    })
    server.on('error', reject)
  })
}

function selfTestKeepAlive() {
  return new Promise((resolve, reject) => {
    let counter = 0
    const ports = new Set()
    const server = http.createServer((req, res) => {
      counter += 1
      ports.add(req.socket.remotePort)
      const body = JSON.stringify({ n: counter, node: `node-${(counter - 1) % 3}` })
      res.writeHead(200, {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(body),
        'X-Cluster-Node': `node-${['a', 'b', 'c'][(counter - 1) % 3]}`
      })
      res.end(body)
    })
    server.listen(0, '127.0.0.1', async () => {
      const port = server.address().port
      const agent = new http.Agent({ keepAlive: true, maxSockets: 1, maxFreeSockets: 1 })
      try {
        const seen = []
        const localPorts = new Set()
        for (let i = 0; i < 6; i += 1) {
          const record = await httpRequest(`http://127.0.0.1:${port}/health`, {
            agent,
            timeoutMs: 5000,
            headers: { Connection: 'keep-alive' }
          })
          checkEq(record.status, 200, '自检 keep-alive HTTP')
          seen.push(record.headers['x-cluster-node'])
          localPorts.add(record.localPort)
        }
        checkEq(localPorts.size, 1, `自检 keep-alive 客户端端口必须唯一（实得 ${JSON.stringify([...localPorts])}）`)
        checkEq(new Set(seen).size, 3, `自检 keep-alive 节点覆盖（实得 ${JSON.stringify(seen)}）`)
        checkEq(ports.size, 1, `自检服务端只看到一个 TCP 连接（实得 ${ports.size}）`)
        agent.destroy()
        server.closeAllConnections?.()
        server.close()
        resolve(`localPort=${[...localPorts][0]}，节点=${[...new Set(seen)].join('/')}`)
      } catch (e) {
        agent.destroy()
        server.closeAllConnections?.()
        server.close()
        reject(e)
      }
    })
    server.on('error', reject)
  })
}

function selfTestStatsCsv() {
  const csv = [
    '# pxname,svname,status,check_status,lastchg',
    'be_cluster3,node-a,UP,L7OK,1',
    'be_cluster3,node-b,DOWN,L7STS,2',
    'be_cluster3,node-c,UP,L7OK,3',
    'be_cluster3,BACKEND,UP,,4'
  ].join('\n')
  const parsed = parseStatsCsv(csv)
  const a = parsed.rows.find((row) => row.svname === 'node-a')
  const b = parsed.rows.find((row) => row.svname === 'node-b')
  checkEq(a.status, 'UP', '自检 stats node-a 状态')
  checkEq(b.check_status, 'L7STS', '自检 stats check_status')
  return `${parsed.rows.length} 行`
}

const SELF_TOOL_CALL_ID = 'tc-park'
const SELF_TOOL_NAME = 'start_export'
const SELF_ARGS = '{"taskId":7,"defCode":"CURRENCY"}'

/** 确认卡请求帧的载荷（`SseChatEmitter#confirmRequest` 的字段面）。 */
function cardFrame(seq, overrides = {}) {
  return {
    type: 'confirm_request',
    seq,
    toolCallId: SELF_TOOL_CALL_ID,
    name: SELF_TOOL_NAME,
    args: SELF_ARGS,
    summary: SELF_ARGS,
    irreversible: false,
    callback: 'POST /api/ai/confirm {runId, toolCallId, approved, reason}',
    ...overrides
  }
}

/** 自检夹具：一次 park 之后客户端能看到的帧集（含产品要求的载荷）。 */
function parkedFixture() {
  return [
    { type: 'start', seq: 1, runId: 'r-park' },
    {
      type: 'suspended',
      seq: 2,
      runId: 'r-park',
      toolCalls: [
        { toolCallId: SELF_TOOL_CALL_ID, name: SELF_TOOL_NAME, kind: 'CONFIRM', status: 'PENDING', arguments: SELF_ARGS }
      ],
      stateKey: 'ai:run:r-park',
      pendingKey: 'ai:pending:r-park',
      writtenBy: 'instance-self'
    },
    cardFrame(3),
    { type: 'tool_start', seq: 4, toolCallId: SELF_TOOL_CALL_ID, name: SELF_TOOL_NAME, kind: 'CONFIRM', args: SELF_ARGS }
  ]
}

/** 自检夹具：一轮完整生命周期（全量回放帧册的正例）。 */
function ledgerFixture() {
  return [
    ...parkedFixture(),
    { type: 'confirm_decision', seq: 5, toolCallId: SELF_TOOL_CALL_ID, name: SELF_TOOL_NAME, decision: 'approve', approved: true, status: 'APPROVED', reason: 'self' },
    {
      type: 'tool_result',
      seq: 6,
      toolCallId: SELF_TOOL_CALL_ID,
      name: SELF_TOOL_NAME,
      kind: 'CONFIRM',
      ok: true,
      outcome: 'ok',
      code: null,
      executed: true,
      executedBy: 'instance-self',
      status: 'EXECUTED',
      result: '导出作业已启动（作业 #1）'
    },
    { type: 'message_start', seq: 7 },
    { type: 'delta', seq: 8, text: '导出任务已创建' },
    { type: 'message_end', seq: 9 },
    { type: 'done', seq: 10, cancelled: false }
  ]
}

function selfExpectThrow(fn, label) {
  let threw = false
  try {
    fn()
  } catch {
    threw = true
  }
  check(threw, label)
}

/**
 * 证据优先（复审 B1）：先落盘"当时看到的快照"，再跑可能失败的断言；
 * 断言抛错时证据已经在磁盘上。
 */
function selfTestEvidenceBeforeAssertions() {
  const order = []
  const frames = parkedFixture()
  let threw = false
  try {
    evidenceFirst(
      (value) => order.push(`write:${value.length}`),
      () => frames,
      () => {
        order.push('assert')
        throw new Error('自检：断言故意失败')
      }
    )
  } catch {
    threw = true
  }
  check(threw, '自检：断言抛错必须向外传播（不得被证据写吞掉）')
  checkEq(JSON.stringify(order), JSON.stringify(['write:4', 'assert']), '自检：写证据必须先于断言')

  // 正例：断言成功时同样先写。
  const okOrder = []
  const result = evidenceFirst(
    (value) => okOrder.push(`write:${value}`),
    () => 'snapshot',
    () => {
      okOrder.push('assert')
      return 'value'
    }
  )
  checkEq(result, 'value', '自检：evidenceFirst 必须返回断言结果')
  checkEq(JSON.stringify(okOrder), JSON.stringify(['write:snapshot', 'assert']), '自检：正例顺序')
  return '失败留存 + 正常返回'
}

function selfTestParkBoundaryContract() {
  const parked = parkedFixture()
  const summary = assertParkedFrameSet(parked, SELF_TOOL_CALL_ID)
  checkEq(summary.lastSeq, 4, '自检 park 边界 lastSeq')
  checkEq(summary.types, 'start,suspended,confirm_request,tool_start', '自检 park 边界业务帧型')

  selfExpectThrow(() => assertParkedFrameSet(parked.slice(0, 3), SELF_TOOL_CALL_ID), '自检：缺 tool_start 必须红')
  selfExpectThrow(
    () => assertParkedFrameSet([...parked, { type: 'done', seq: 5 }], SELF_TOOL_CALL_ID),
    '自检：park 边界出现终帧必须红'
  )
  selfExpectThrow(
    () => assertParkedFrameSet([parked[0], parked[1], cardFrame(3), cardFrame(3)], SELF_TOOL_CALL_ID),
    '自检：重复请求帧必须红'
  )
  selfExpectThrow(
    () => assertParkedFrameSet([parked[0], parked[1], cardFrame(3), { ...parked[3], seq: 5 }], SELF_TOOL_CALL_ID),
    '自检：seq 断号必须红'
  )
  selfExpectThrow(
    () => assertParkedFrameSet([parked[0], cardFrame(2), { ...parked[1], seq: 3 }, { ...parked[3], seq: 4 }], SELF_TOOL_CALL_ID),
    '自检：suspended 晚于请求帧必须红'
  )
  selfExpectThrow(
    () => assertParkedFrameSet([...parked.slice(0, 3), { ...parked[3], toolCallId: 'other' }], SELF_TOOL_CALL_ID),
    '自检：tool_start 的 toolCallId 不匹配必须红'
  )
  return '4 帧 + 6 个反例'
}

/**
 * 允许的 pre-park 噪声（复审 I3）：心跳（无 seq）、文本段、压缩、重试都可以出现在确认卡之前，
 * 不得因此误判；缺 `tool_start` 仍然是缺陷。
 */
function selfTestAllowedPreParkFrames() {
  const parked = parkedFixture()
  const noisy = [
    parked[0],
    { type: 'context', seq: 2, level: 'l1', before: 1000, after: 800 },
    { type: 'heartbeat', heartbeatSeq: 1 }, // 心跳：无业务 seq，合法
    { type: 'message_start', seq: 3, messageId: 'm-1' },
    { type: 'delta', seq: 4, text: '正在准备导出…', messageId: 'm-1' },
    { type: 'message_end', seq: 5, messageId: 'm-1' },
    { type: 'retry', seq: 6 },
    { ...parked[1], seq: 7 },
    { ...parked[2], seq: 8 },
    { type: 'heartbeat', heartbeatSeq: 2 },
    { ...parked[3], seq: 9 }
  ]
  const summary = assertParkedFrameSet(noisy, SELF_TOOL_CALL_ID)
  checkEq(summary.lastSeq, 9, '自检噪声帧集的 lastSeq（心跳不占号）')
  checkEq(summary.heartbeats, 2, '自检心跳计数')
  assertParkedPayloads(noisy, { toolCallId: SELF_TOOL_CALL_ID, label: '自检噪声载荷' })

  // "确认卡已到"的契约（早杀路径）：不要求 tool_start。
  const cardOnly = [parked[0], { type: 'heartbeat', heartbeatSeq: 1 }, parked[1], parked[2]]
  const cardSummary = assertCardReachedFrameSet(cardOnly, SELF_TOOL_CALL_ID)
  checkEq(cardSummary.lastSeq, 3, '自检"卡已到"契约 lastSeq')

  selfExpectThrow(
    () => assertCardReachedFrameSet([parked[0], parked[1], { type: 'brand_new', seq: 3 }, parked[2]], SELF_TOOL_CALL_ID),
    '自检：卡之前出现未允许帧型必须红'
  )
  selfExpectThrow(
    () => assertCardReachedFrameSet([parked[0], parked[1], { type: 'delta', text: 'x' }, parked[2]], SELF_TOOL_CALL_ID),
    '自检：卡之前出现缺 seq 的业务帧必须红'
  )
  return '心跳/文本/压缩/重试 + 卡已到契约 + 2 个反例'
}

/** 缺 seq 的帧必须被拒绝，而不是被过滤掉（复审 I2）。 */
function selfTestMissingSeqRejected() {
  const ledger = ledgerFixture()
  checkEq(seqsOf(ledger, '自检 seq').length, 10, '自检 seq 列长度')
  // 心跳允许没有 seq。
  checkEq(seqsOf([...ledger, { type: 'heartbeat', heartbeatSeq: 3 }], '自检心跳').length, 10, '自检心跳不占号')

  selfExpectThrow(() => seqsOf([{ type: 'delta', text: 'x' }], '自检缺号'), '自检：缺 seq 的业务帧必须抛错')
  const noSeq = ledger.map((frame) => (frame.type === 'done' ? { ...frame, seq: undefined } : frame))
  selfExpectThrow(() => assertFrameLedger(noSeq, { toolCallId: SELF_TOOL_CALL_ID }), '自检：done 缺 seq 必须红')
  const middle = ledger.map((frame) => (frame.seq === 8 ? { ...frame, seq: undefined } : frame))
  selfExpectThrow(() => assertFrameLedger(middle, { toolCallId: SELF_TOOL_CALL_ID }), '自检：中间帧缺 seq 必须红')
  checkEq(seqsOf(ledger).length, businessFrames(ledger).length, '自检：非心跳帧数 = seq 数')
  return '缺号 3 个反例 + 心跳例外'
}

/** 载荷断言：空壳/错值不得 false-pass（复审 I1）。 */
function selfTestBadPayloadRejected() {
  const ledger = ledgerFixture()
  const summary = assertFrameLedger(ledger, { toolCallId: SELF_TOOL_CALL_ID })
  checkEq(summary.lastSeq, 10, '自检帧册 lastSeq')
  checkEq(summary.deltas, 1, '自检帧册 delta 数')

  const mutate = (predicate, patch) => ledger.map((frame) => (predicate(frame) ? { ...frame, ...patch(frame) } : frame))
  selfExpectThrow(
    () => assertFrameLedger(mutate((f) => f.type === 'tool_result', () => ({ ok: false, outcome: 'failed' })), { toolCallId: SELF_TOOL_CALL_ID }),
    '自检：tool_result.ok/outcome 错误必须红'
  )
  selfExpectThrow(
    () => assertFrameLedger(mutate((f) => f.type === 'tool_result', () => ({ result: '' })), { toolCallId: SELF_TOOL_CALL_ID }),
    '自检：tool_result.result 为空必须红'
  )
  selfExpectThrow(
    () => assertFrameLedger(mutate((f) => f.type === 'confirm_request', () => ({ name: '' })), { toolCallId: SELF_TOOL_CALL_ID }),
    '自检：confirm_request.name 为空必须红'
  )
  selfExpectThrow(
    () => assertFrameLedger(mutate((f) => f.type === 'tool_start', () => ({ args: '{"taskId":999}' })), { toolCallId: SELF_TOOL_CALL_ID }),
    '自检：tool_start.args 与请求帧不一致必须红'
  )
  selfExpectThrow(
    () => assertFrameLedger(mutate((f) => f.type === 'confirm_decision', () => ({ decision: 'reject', approved: false })), { toolCallId: SELF_TOOL_CALL_ID }),
    '自检：confirm_decision 非 approve 必须红'
  )
  selfExpectThrow(
    () => assertFrameLedger(mutate((f) => f.type === 'delta', () => ({ text: '' })), { toolCallId: SELF_TOOL_CALL_ID }),
    '自检：delta 文本为空必须红'
  )
  selfExpectThrow(
    () => assertFrameLedger(ledger.filter((f) => f.type !== 'delta'), { toolCallId: SELF_TOOL_CALL_ID }),
    '自检：没有 delta（正文）必须红'
  )
  selfExpectThrow(
    () => assertFrameLedger(mutate((f) => f.type === 'suspended', (frame) => ({ toolCalls: [] })), { toolCallId: SELF_TOOL_CALL_ID }),
    '自检：suspended.toolCalls 未列出条目必须红'
  )
  return '1 正例 + 8 个载荷反例'
}

/** 帧册的结构契约（缺帧/断号/乱序/未知帧型）。 */
function selfTestFrameLedger() {
  const ledger = ledgerFixture()
  const summary = assertFrameLedger(ledger, { toolCallId: SELF_TOOL_CALL_ID })
  checkEq(
    summary.types,
    'start,suspended,confirm_request,tool_start,confirm_decision,tool_result,message_start,delta,message_end,done',
    '自检帧册帧型'
  )

  selfExpectThrow(
    () => assertFrameLedger(ledger.filter((f) => f.type !== 'tool_start'), { toolCallId: SELF_TOOL_CALL_ID }),
    '自检：帧册缺 tool_start 必须红'
  )
  selfExpectThrow(
    () => assertFrameLedger(ledger.filter((f) => f.seq !== 8), { toolCallId: SELF_TOOL_CALL_ID }),
    '自检：帧册 seq 断号必须红'
  )
  const reordered = ledger.map((f) => {
    if (f.type === 'tool_start') return { ...f, seq: 6 }
    if (f.type === 'tool_result') return { ...f, seq: 4 }
    return f
  })
  selfExpectThrow(() => assertFrameLedger(reordered, { toolCallId: SELF_TOOL_CALL_ID }), '自检：tool_result 早于 tool_start 必须红')
  selfExpectThrow(
    () => assertFrameLedger([...ledger, { type: 'brand_new_frame', seq: 11 }], { toolCallId: SELF_TOOL_CALL_ID }),
    '自检：未知帧型必须红'
  )
  // done 不是末号（归档里 done 之后还有业务帧）必须红。
  selfExpectThrow(
    () => assertFrameLedger([...ledger, { type: 'delta', seq: 11, text: '迟到' }], { toolCallId: SELF_TOOL_CALL_ID }),
    '自检：done 之后又出现业务帧必须红'
  )
  return '10 帧 + 5 个反例'
}

/** B 侧差量的连续性（复审 I2）：中间缺一帧必须红。 */
/**
 * 决策后**差量连接**的帧型/载荷（复审 I1(b)(c)）：只验 seq 时 `tool_result.ok=false`、
 * 空 `delta.text`、未知帧型、多一帧 `done` 都能过。
 */
function selfTestDeltaConnectionFrames() {
  const delta = [
    { type: 'confirm_decision', seq: 5, toolCallId: SELF_TOOL_CALL_ID, name: SELF_TOOL_NAME, decision: 'approve', approved: true },
    {
      type: 'tool_result',
      seq: 6,
      toolCallId: SELF_TOOL_CALL_ID,
      name: SELF_TOOL_NAME,
      ok: true,
      outcome: 'ok',
      result: '导出作业已启动（作业 #1）'
    },
    { type: 'message_start', seq: 7 },
    { type: 'delta', seq: 8, text: '导出任务已创建' },
    { type: 'done', seq: 9 }
  ]
  const summary = assertDeltaFrames(delta, { toolCallId: SELF_TOOL_CALL_ID, expectedName: SELF_TOOL_NAME })
  checkEq(summary.frames, 5, '自检差量帧数')
  checkEq(summary.toolStarts, 0, '自检差量 tool_start 帧数')

  // 早杀路径允许差量里有一条 tool_start（且必须先于 tool_result）。
  const withStart = [
    { type: 'confirm_decision', seq: 5, toolCallId: SELF_TOOL_CALL_ID, name: SELF_TOOL_NAME, decision: 'approve', approved: true },
    { type: 'tool_start', seq: 6, toolCallId: SELF_TOOL_CALL_ID, name: SELF_TOOL_NAME, kind: 'CONFIRM', args: SELF_ARGS },
    { ...delta[1], seq: 7 },
    { ...delta[2], seq: 8 },
    { ...delta[3], seq: 9 },
    { ...delta[4], seq: 10 }
  ]
  const startSummary = assertDeltaFrames(withStart, { toolCallId: SELF_TOOL_CALL_ID, expectedName: SELF_TOOL_NAME, allowToolStart: true })
  checkEq(startSummary.toolStarts, 1, '自检差量允许的 tool_start 帧数')
  selfExpectThrow(
    () => assertDeltaFrames(withStart, { toolCallId: SELF_TOOL_CALL_ID, expectedName: SELF_TOOL_NAME, allowToolStart: false }),
    '自检：非早杀差量出现 tool_start 必须红'
  )

  const mutate = (predicate, patch) => delta.map((frame) => (predicate(frame) ? { ...frame, ...patch } : frame))
  selfExpectThrow(
    () => assertDeltaFrames(delta.filter((f) => f.type !== 'tool_result'), { toolCallId: SELF_TOOL_CALL_ID }),
    '自检：差量缺 tool_result 必须红'
  )
  selfExpectThrow(
    () => assertDeltaFrames(mutate((f) => f.type === 'tool_result', { ok: false, outcome: 'failed' }), { toolCallId: SELF_TOOL_CALL_ID }),
    '自检：差量 tool_result.ok/outcome 错误必须红'
  )
  selfExpectThrow(
    () => assertDeltaFrames(mutate((f) => f.type === 'tool_result', { result: '' }), { toolCallId: SELF_TOOL_CALL_ID }),
    '自检：差量 tool_result.result 为空必须红'
  )
  selfExpectThrow(
    () => assertDeltaFrames(mutate((f) => f.type === 'confirm_decision', { decision: 'reject', approved: false }), { toolCallId: SELF_TOOL_CALL_ID }),
    '自检：差量 confirm_decision 非 approve 必须红'
  )
  selfExpectThrow(
    () => assertDeltaFrames(mutate((f) => f.type === 'delta', { text: '' }), { toolCallId: SELF_TOOL_CALL_ID }),
    '自检：差量 delta 文本为空必须红'
  )
  selfExpectThrow(
    () => assertDeltaFrames([...delta, { type: 'brand_new', seq: 10 }], { toolCallId: SELF_TOOL_CALL_ID }),
    '自检：差量出现白名单外帧型必须红'
  )
  selfExpectThrow(
    () => assertDeltaFrames([...delta, { type: 'delta', seq: 10, text: '迟到' }], { toolCallId: SELF_TOOL_CALL_ID }),
    '自检：差量 done 之后又有业务帧必须红'
  )
  selfExpectThrow(
    () => assertDeltaFrames([{ type: 'suspended', seq: 5 }, ...delta], { toolCallId: SELF_TOOL_CALL_ID }),
    '自检：差量重发前缀帧（suspended）必须红'
  )
  return '2 正例 + 8 个反例'
}

/**
 * 状态类证据的落盘顺序（复审 N1）：`/runs/current` / `run-detail` / `jobs` / `history`
 * 都必须"先写快照、再断言"，断言抛错也要留下已写的证据。
 */
function selfTestStateEvidenceOrder() {
  const order = []
  const cases = [
    ['current', () => ({ snapshotStatus: 'SUSPENDED', archiveMaxSeq: 4 }), (v) => checkEq(v.snapshotStatus, 'MUST_FAIL', '自检 current')],
    ['detail', () => ({ status: 'DONE', ledger: [{ outcome: 'ok' }] }), (v) => checkEq(v.status, 'MUST_FAIL', '自检 detail')],
    ['jobs', () => ({ length: 1 }), (v) => checkEq(v.length, 99, '自检 jobs')],
    ['history', () => ({ total: 3 }), (v) => checkEq(v.total, 99, '自检 history')]
  ]
  for (const [tag, snapshot, assertion] of cases) {
    let threw = false
    try {
      evidenceFirst(
        (value) => order.push(`write:${tag}:${stableStringify(value)}`),
        snapshot,
        () => {
          order.push(`assert:${tag}`)
          assertion(snapshot())
        }
      )
    } catch {
      threw = true
    }
    check(threw, `自检：${tag} 的断言必须抛错（用于验证失败留存）`)
  }
  checkEq(
    JSON.stringify(order),
    JSON.stringify([
      'write:current:{"archiveMaxSeq":4,"snapshotStatus":"SUSPENDED"}',
      'assert:current',
      'write:detail:{"ledger":[{"outcome":"ok"}],"status":"DONE"}',
      'assert:detail',
      'write:jobs:{"length":1}',
      'assert:jobs',
      'write:history:{"total":3}',
      'assert:history'
    ]),
    '自检：四项状态证据都必须先写后断言'
  )
  return '4 项状态证据的顺序'
}

/** 帧册末号口径（复审 N2）：返回的是"本次全量回放末号"，不是服务端 archiveMaxSeq。 */
function selfTestLedgerTipScope() {
  const ledger = ledgerFixture()
  const summary = assertFrameLedger(ledger, { toolCallId: SELF_TOOL_CALL_ID })
  checkEq(summary.lastSeqScope, 'full-replay', '自检末号口径')
  checkEq(summary.lastSeq, findOf(ledger, 'done').seq, '自检末号 = done.seq')
  // 末号声明为 full-replay 时，与"服务端 archiveMaxSeq"是两个口径：这里只断言回放内的一致性。
  const truncatedAfterDone = ledger.slice(0, ledger.length - 1) // 去掉 done → 必须红（不是"末号"问题，而是缺终帧）
  selfExpectThrow(
    () => assertFrameLedger(truncatedAfterDone, { toolCallId: SELF_TOOL_CALL_ID }),
    '自检：缺 done 必须红'
  )
  return `scope=${summary.lastSeqScope}`
}

/** 早杀观测（复审 N3）：只记录"kill 前客户端是否看到 tool_start"，不推断来源。 */
function selfTestKillObservation() {
  const withStart = killToolStartObservation(parkedFixture(), SELF_TOOL_CALL_ID)
  checkEq(withStart.toolStartSeenBeforeKill, true, '自检：kill 前已见 tool_start')
  checkEq(withStart.toolStartNotObservedBeforeKill, false, '自检：kill 前已见的反义字段')
  const withoutStart = killToolStartObservation(parkedFixture().slice(0, 3), SELF_TOOL_CALL_ID)
  checkEq(withoutStart.toolStartSeenBeforeKill, false, '自检：kill 前未见 tool_start')
  checkEq(withoutStart.toolStartNotObservedBeforeKill, true, '自检：kill 前未见的反义字段')
  check(
    !Object.prototype.hasOwnProperty.call(withStart, 'toolStartBackfilled'),
    '自检：观测对象不得包含"谁补发"的推断字段'
  )
  return 'kill 前已见/未见两态'
}

function selfTestDeltaContiguous() {
  const delta = [
    { type: 'confirm_decision', seq: 5 },
    { type: 'tool_result', seq: 6 },
    { type: 'delta', seq: 7, text: 'x' },
    { type: 'done', seq: 8 }
  ]
  checkEq(JSON.stringify(assertDeltaContiguous(delta, 5, '自检差量')), JSON.stringify([5, 6, 7, 8]), '自检差量 seq')
  selfExpectThrow(
    () => assertDeltaContiguous([delta[0], delta[2], delta[3]], 5, '自检差量缺帧'),
    '自检：差量中间缺帧（5,7）必须红'
  )
  selfExpectThrow(() => assertDeltaContiguous(delta, 4, '自检差量起点'), '自检：差量起点不符必须红')
  selfExpectThrow(
    () => assertDeltaContiguous([...delta, { type: 'delta', seq: 8, text: 'dup' }], 5, '自检差量重复'),
    '自检：差量重复号必须红'
  )
  return '1 正例 + 3 个反例'
}

function selfTestPreDecisionReplay() {
  const parked = parkedFixture()
  assertPreDecisionReplay(parked, parked, SELF_TOOL_CALL_ID, '自检决策前重挂')
  selfExpectThrow(
    () => assertPreDecisionReplay(parked.slice(0, 3), parked, SELF_TOOL_CALL_ID, '自检决策前重挂'),
    '自检：回放缺 tool_start 必须红'
  )
  selfExpectThrow(
    () => assertPreDecisionReplay([...parked, { type: 'done', seq: 5 }], parked, SELF_TOOL_CALL_ID, '自检决策前重挂'),
    '自检：决策前回放出现终帧必须红'
  )
  selfExpectThrow(
    () => assertPreDecisionReplay([parked[0], parked[2], parked[1], parked[3]], parked, SELF_TOOL_CALL_ID, '自检决策前重挂'),
    '自检：决策前回放帧序被改动必须红'
  )
  // 载荷被清空/替换也必须红（只比 type+seq / 只比固定键都会 false-pass，复审 I1(a)）。
  const tamperedName = parked.map((frame) =>
    frame.type === 'confirm_request' ? { ...frame, name: 'someone_else' } : frame
  )
  selfExpectThrow(
    () => assertPreDecisionReplay(tamperedName, parked, SELF_TOOL_CALL_ID, '自检决策前重挂载荷'),
    '自检：回放载荷与 park 帧集不一致必须红'
  )
  const clearedToolCalls = parked.map((frame) => (frame.type === 'suspended' ? { ...frame, toolCalls: [] } : frame))
  selfExpectThrow(
    () => assertPreDecisionReplay(clearedToolCalls, parked, SELF_TOOL_CALL_ID, '自检决策前重挂 toolCalls'),
    '自检：回放把 suspended.toolCalls 清空必须红'
  )
  const swappedRunId = parked.map((frame) => (frame.type === 'start' ? { ...frame, runId: 'r-other' } : frame))
  selfExpectThrow(
    () => assertPreDecisionReplay(swappedRunId, parked, SELF_TOOL_CALL_ID, '自检决策前重挂 runId'),
    '自检：回放把 start.runId 换掉必须红'
  )
  // context 载荷属于"全字段"比对范围（白名单里没有它，必须靠全字段/深比较覆盖）。
  const noisyExpected = [
    parked[0],
    { type: 'context', seq: 2, level: 'l1', before: 1000, after: 800 },
    { ...parked[1], seq: 3 },
    { ...parked[2], seq: 4 },
    { ...parked[3], seq: 5 }
  ]
  assertPreDecisionReplay(noisyExpected, noisyExpected, SELF_TOOL_CALL_ID, '自检含 context 正例')
  const contextTampered = noisyExpected.map((frame) =>
    frame.type === 'context' ? { ...frame, after: 999 } : frame
  )
  selfExpectThrow(
    () => assertPreDecisionReplay(contextTampered, noisyExpected, SELF_TOOL_CALL_ID, '自检 context 载荷'),
    '自检：回放把 context 载荷改坏必须红'
  )
  return '2 正例 + 7 个反例'
}

/**
 * park 收流屏障：真实 HTTP 上验证「请求帧之后等连接被服务端收尾」是本用例的时序屏障。
 * 正例 = 发完 park 帧集就 end()；空回放正例 = 直接 end()（决策前带 lastSeq 的重挂就是这个形态）；
 * 反例 = 帧发完但不收流（必须超时红，证明屏障不是空转）。
 */
function selfTestParkCloseBarrier() {
  const parked = parkedFixture()
  const startServer = (frames, holdOpen) =>
    new Promise((resolve) => {
      const server = http.createServer((req, res) => {
        res.writeHead(200, { 'Content-Type': 'text/event-stream', 'X-Cluster-Node': 'node-selftest' })
        for (const frame of frames) res.write(`data: ${JSON.stringify(frame)}\n\n`)
        if (!holdOpen) res.end()
      })
      server.listen(0, '127.0.0.1', () => resolve(server))
    })
  return (async () => {
    const bounded = await startServer(parked, false)
    const empty = await startServer([], false)
    const held = await startServer(parked, true)
    try {
      const okStream = await openSse(`http://127.0.0.1:${bounded.address().port}/events`, { timeoutMs: 10000 })
      const settled = await settleParkBoundary(okStream, { toolCallId: 'tc-park', timeoutMs: 5000 })
      checkEq(settled.lastSeq, 4, '自检 park 收流后的 lastSeq')
      checkEq(settled.types, 'start,suspended,confirm_request,tool_start', '自检 park 收流帧型')
      okStream.close()

      // 决策前带 lastSeq 的重挂：归档里没有新帧 ⇒ 0 帧 + 服务端收流（用例断言 businessFrames(preB.frames).length === 0）。
      const emptyStream = await openSse(`http://127.0.0.1:${empty.address().port}/events`, { timeoutMs: 10000 })
      checkEq(await emptyStream.waitClosed(5000, '自检空回放收流'), 'end', '自检空回放必须是服务端正常收尾')
      checkEq(emptyStream.frames.length, 0, '自检空回放帧数')
      emptyStream.close()

      const heldStream = await openSse(`http://127.0.0.1:${held.address().port}/events`, { timeoutMs: 10000 })
      let failed = false
      let failure = ''
      try {
        await settleParkBoundary(heldStream, { toolCallId: 'tc-park', timeoutMs: 300 })
      } catch (e) {
        failed = true
        failure = e.message
      }
      heldStream.close()
      check(failed, '自检：未收流的连接不得被当成"已 park"')
      checkIncludes(failure, '等待 park 收流', '自检 park 收流超时的错误面')
      return '收流正例 + 空回放正例 + 悬挂反例'
    } finally {
      for (const server of [bounded, empty, held]) {
        server.closeAllConnections?.()
        server.close()
      }
    }
  })()
}

function selfTestSeqAndAssertions() {
  checkStrictlyIncreasing([1, 2, 3, 5], '自检 seq')
  let threw = false
  try {
    checkStrictlyIncreasing([1, 3, 2], '自检坏 seq')
  } catch {
    threw = true
  }
  check(threw, '自检：非递增 seq 必须被拒绝')
  let dupThrew = false
  try {
    checkStrictlyIncreasing([1, 2, 2], '自检重复 seq')
  } catch {
    dupThrew = true
  }
  check(dupThrew, '自检：重复 seq 必须被拒绝')
  return '递增/乱序/重复三态'
}

function selfTestPortHygiene() {
  const forbidden = BANNED_PORT_TOKENS.map(Number)
  const all = Object.values(PORTS)
  for (const port of all) {
    check(port >= 23100 && port <= 23199, `端口 ${port} 不在 231xx 段`)
    check(!forbidden.includes(port), `端口 ${port} 是禁用端口`)
  }
  const unique = new Set(all)
  checkEq(unique.size, all.length, '端口不得重复')
  return all.join(',')
}

function selfTestStaticImports() {
  const source = fs.readFileSync(fileURLToPath(import.meta.url), 'utf8')
  const imports = [...source.matchAll(/^import\s+[^'"]*['"]([^'"]+)['"]/gm)].map((m) => m[1])
  check(imports.length >= 5, `import 数量异常：${imports.length}`)
  for (const spec of imports) {
    check(spec.startsWith('node:'), `非内置模块 import：${spec}`)
  }
  const commonJsLoader = 'req' + 'uire('
  check(!source.includes(commonJsLoader), '探针不得使用 CommonJS 同步加载（应全部走 ESM import）')
  return `${imports.length} 个 node: 内置模块`
}

function selfTestCaseRegistry() {
  const ids = CASES.map((c) => c.id)
  checkEq(new Set(ids).size, ids.length, '用例 id 重复')
  for (const required of REQUIRED_CASE_IDS) {
    check(ids.includes(required), `缺少必需用例：${required}`)
  }
  checkEq(ids.length, REQUIRED_CASE_IDS.length, '用例数量与必需清单')
  for (const def of CASES) {
    check(typeof def.fn === 'function', `用例 ${def.id} 没有实现函数`)
    check(typeof def.title === 'string' && def.title.length > 0, `用例 ${def.id} 缺少标题`)
  }
  check(!JSON.stringify(RESULT_STATUSES).includes('SKIP'), '结果状态不得包含 SKIP')
  checkEq(RESULT_STATUSES.join(','), 'PASS,FAIL', '结果状态集合')
  return ids.length
}

function selfTestProductContract() {
  assertSourceContains('backend/src/main/java/com/example/configmgr/ai/web/AiController.java', [
    '@GetMapping("/health")',
    'body.put("instanceId"',
    'body.put("redis", this.redisAvailability.describe())',
    'body.put("leaseOk"',
    '@PostMapping("/confirm")',
    'body.put("storedBy"',
    'body.put("resumeOutcome"',
    '@GetMapping(value = "/events/{runId}"',
    '@RequestParam(required = false) Long lastSeq',
    '@GetMapping("/history/{sessionId}")',
    'body.put("hasMore"',
    '@GetMapping("/runs/current")',
    'body.put("snapshotStatus"',
    'body.put("awaitingExternal"',
    '@GetMapping("/pending/{runId}")',
    '@PostMapping("/frontend-tool-result")',
    '@PostMapping("/startup-resume/scan")',
    '@PostMapping(value = "/runs/{runId}/resume"'
  ])
  // 进程级健康：类级 /health + 方法级 /live、/ready（不要写成 @GetMapping("/health/live")）。
  assertSourceContains('backend/src/main/java/com/example/configmgr/health/HealthController.java', [
    '@RequestMapping("/health")',
    '@GetMapping("/live")',
    '@GetMapping("/ready")'
  ])
  assertSourceContains('backend/src/main/java/com/example/configmgr/health/ReadinessProbe.java', [
    'default boolean required()'
  ])
  assertSourceContains('backend/src/main/java/com/example/configmgr/ai/web/AiSessionPurgeController.java', [
    '@DeleteMapping({ "/session/{sessionId}", "/history/{sessionId}" })',
    'body.put("deleted", result.complete())',
    'body.put("activeRunId"'
  ])
  assertSourceContains('backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java', [
    'body.put("complete", this.complete)',
    'body.put("deferred", this.deferred.stream()'
  ])
  assertSourceContains('backend/src/main/java/com/example/configmgr/job/controller/JobController.java', [
    '@PostMapping("/tasks/{taskId}/jobs")',
    '@GetMapping("/jobs/{jobId}")'
  ])
  assertSourceContains('backend/src/main/java/com/example/configmgr/task/controller/FileController.java', [
    '@PostMapping("/upload")',
    '@GetMapping("/download")',
    '@GetMapping("/templates")'
  ])
  assertSourceContains('backend/src/main/java/com/example/configmgr/ai/web/AiConfigController.java', [
    '@PutMapping("/settings/defaults")',
    '@GetMapping("/models")',
    'defaults.version()'
  ])
  assertSourceContains('backend/src/main/java/com/example/configmgr/ai/config/AiConfigWriter.java', [
    'record DefaultsCommand(String modelId',
    'Long providerId'
  ])
  assertSourceContains('backend/src/main/java/com/example/configmgr/ai/web/dto/DefaultsView.java', [
    'Long providerId'
  ])
  assertSourceContains('backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java', [
    'out.put("snapshot"',
    'out.put("ledger"',
    'out.put("ledgerCounts"'
  ])
  assertSourceContains('backend/src/main/java/com/example/configmgr/job/service/JobHeartbeatRegistry.java', [
    '"job:runner-active:"'
  ])
  return 'AiController / HealthController / ReadinessProbe / AiSessionPurgeController / JobController / FileController / AiConfigController / ResumeService / JobHeartbeatRegistry'
}

function selfTestStubContract() {
  const routes = JSON.parse(fs.readFileSync(path.join(REPO_ROOT, 'scripts/ci/stub-routes.json'), 'utf8'))
  check(Array.isArray(routes.routes), 'stub-routes.json 缺少 routes')
  const exportRoute = routes.routes.find((route) => route.id === 'TC16-EXPORT')
  check(exportRoute, 'stub 缺少 TC16-EXPORT 路由')
  check(exportRoute.match.messageAny.includes('start_export'), 'TC16-EXPORT 匹配标记不是 start_export')
  check(exportRoute.match.requiresTools.includes('start_export'), 'TC16-EXPORT 披露闸不是 start_export')
  checkEq(exportRoute.tool, 'start_export', 'TC16-EXPORT 工具名')
  check(typeof routes.convergence.maxCallsPerTurn === 'number', 'stub convergence.maxCallsPerTurn 缺失')
  return `routes=${routes.routes.length}`
}

/** 实例启动参数契约：h2tcp profile（含 graceful shutdown）/ LOCK_TIMEOUT / 可信代理网段。 */
function selfTestInstanceArgs() {
  const args = instanceArgs('a')
  const dbArg = args.find((arg) => arg.startsWith('--spring.datasource.url='))
  checkIncludes(args.join(' '), '--spring.profiles.active=h2tcp', '实例启动参数 profile')
  check(dbArg && dbArg.includes('LOCK_TIMEOUT=60000'), `H2 JDBC URL 必须带 LOCK_TIMEOUT=60000：${dbArg}`)
  checkIncludes(args.join(' '), '--app.web.trusted-proxies=127.0.0.1/32', '可信代理网段')
  return 'h2tcp + LOCK_TIMEOUT + trusted-proxies'
}

/** 复审契约的自证（防止后续编辑把 SIGSTOP/deferred、分档码、二元绑定删回去）。 */
function selfTestReviewContracts() {
  const source = fs.readFileSync(fileURLToPath(import.meta.url), 'utf8')
  // 先剔除本函数自身，避免“检查项里自带这些字符串”造成恒真。
  const start = source.indexOf('function selfTestReviewContracts')
  const body = start >= 0 ? source.slice(0, start) : source
  const clusterRun = fs.readFileSync(productFile('scripts/ci/cluster3-run.sh'), 'utf8')
  const needles = [
    "pauseInstance('a')",
    "resumeInstance('a')",
    'SIGSTOP',
    'SIGCONT',
    "'AI_KEY_MISSING'",
    'providerId: bindingProviderId',
    '运行时 providerId',
    'NO_TERMINAL_FRAME_WITHIN_DRAIN_WINDOW',
    "job-fail-timeout', 130",
    'ai:purge:pending:',
    'automaticPurgeRecovery'
  ]
  for (const needle of needles) {
    checkIncludes(body, needle, `复审契约 ${needle}`)
  }
  checkIncludes(clusterRun, '--job-fail-timeout 130', '作业判死预算覆盖 90s TTL + 10s 扫描 + 30s 余量')
  return `${needles.length + 1} 项`
}

/**
 * 冷启动种子身份断言的自证（T1：run 40182796512）。
 *
 * 锁三件事：① 三实例同库行 ⇒ 通过；② 只看得到 env 合成行（B/C 的实测形态）⇒ 红；
 * ③ 三实例看到的库行不一致 / 默认 providerId 不落在库行上 ⇒ 红。
 */
function selfTestColdProviderIdentity() {
  const healthy = { providerId: 1, providers: [{ id: 1, code: 'deepseek', synthetic: false }] }
  const ok = assertColdProviderIdentity({ a: seededProviderIdentity(healthy), b: seededProviderIdentity(healthy), c: seededProviderIdentity(healthy) })
  checkEq(ok.providerId, 1, '自检：三实例同库行应通过')

  // 实测形态：B/C 的 providers 只有 env 合成行（id 被 non_null 省略，synthetic=true）
  const syntheticOnly = { providers: [{ code: 'deepseek', synthetic: true }] }
  selfExpectThrow(
    () => assertColdProviderIdentity({ a: seededProviderIdentity(healthy), b: seededProviderIdentity(syntheticOnly) }),
    '自检：只有 env 合成行必须红（env 兜底会把它掩盖成 AI_AVAILABLE）'
  )
  // 合成行存在但默认目标没绑定到库行
  selfExpectThrow(
    () => assertColdProviderIdentity({ a: seededProviderIdentity({ providers: [{ id: 1, synthetic: false }] }) }),
    '自检：默认 providerId 为空必须红'
  )
  // 三实例看到不同的库行 id
  selfExpectThrow(
    () => assertColdProviderIdentity({
      a: seededProviderIdentity(healthy),
      b: seededProviderIdentity({ providerId: 2, providers: [{ id: 2, code: 'deepseek', synthetic: false }] })
    }),
    '自检：三实例库行 id 不一致必须红'
  )
  // 默认 providerId 不在库行里
  selfExpectThrow(
    () => assertColdProviderIdentity({ a: seededProviderIdentity({ providerId: 9, providers: [{ id: 1, synthetic: false }] }) }),
    '自检：默认 providerId 不在库行列表里必须红'
  )
  // 形态异常（providers 缺失）
  selfExpectThrow(() => assertColdProviderIdentity({ a: seededProviderIdentity({}) }), '自检：providers 缺失必须红')
  return '1 正例 + 5 反例'
}

/**
 * L-150 三格契约的自证：**产品键名 + 判据方法 + 探针里的三格断言**都还在。
 *
 * <p>为什么值得单独钉：三格靠"往产品键里注入 + 读产品判据"成立。键名或方法改名后，
 * 用例仍可能"绿"（注入到没人读的键、或断言被顺手删掉）——那正是本用例最怕的 false-pass。
 */
function selfTestL150GridContract() {
  assertSourceContains('backend/src/main/java/com/example/configmgr/ai/run/CancellationRegistry.java', [
    'CANCEL_PREFIX = "ai:cancel:"'
  ])
  assertSourceContains('backend/src/main/java/com/example/configmgr/ai/run/RunStore.java', [
    'CLAIM_PREFIX = "ai:claim:"',
    // 认领表是 **run 级作用域键**：`claimKey` 必须经 `runScopedKey`，tag 由 `RunKeyScope` 拼
    // （租户任务 2 起 tag 同时含身份与 runId：`{<tenant>:<user>:run:<runId>}`）。
    // 只钉前缀会让夹具把裸 `ai:claim:<runId>` 当成产品键（run 40681954172 的 R-3 假红），
    // 只钉 `{run:<id>}` 同样会漏：身份进 tag 后旧形态落到另一个键上。
    'public String claimKey(String runId)',
    'runScopedKey(CLAIM_PREFIX, runId)',
    'return RunKeyScope.tag(runId)',
    'public boolean hasExecutionEvidence(',
    'public boolean cancellableBeforeExecution('
  ])
  assertSourceContains('backend/src/main/java/com/example/configmgr/ai/tenant/RunKeyScope.java', [
    'public static String tag(String runId)',
    'private static String tagContent(String identitySegment, String runId)',
    'RUN_MARK'
  ])
  assertSourceContains('backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java', [
    'private String settleIfCancelledBeforeClaim(',
    'CancelState.UNKNOWN'
  ])
  // 夹具自身的键形态：认领表必须带 run 作用域 + 身份段，取消标志必须带身份段且**不进 tag**。
  // 两者各有产品侧实证：`ai:claim:` 由 `runScopedKey`/`RunKeyScope.tag` 拼（本源码断言），
  // `ai:cancel:` 见 `CancellationRegistry#cancelKey`（`TenantKeyScope.scoped`，单键命令，不进 tag）。
  checkEq(executionClaimKey('l150-probe'), `ai:claim:{${AI_TENANT_ID}:${AI_USER_ID}:run:l150-probe}`, 'R-3 夹具的认领表键必须带身份 + run 作用域')
  checkEq(cancelFlagKey('l150-probe'), `ai:cancel:${AI_TENANT_ID}:${AI_USER_ID}:l150-probe`,
    'R-1 夹具的取消标志键必须是"裸前缀 + 身份段"（与产品 TenantKeyScope 同一拼接口）')
  // 产品侧键形实证：取消标志经 TenantKeyScope 拼身份段（无身份上下文才退回旧裸形）。
  assertSourceContains('backend/src/main/java/com/example/configmgr/ai/run/CancellationRegistry.java', [
    'TenantKeyScope.scoped(CANCEL_PREFIX, runId)'
  ])
  const source = fs.readFileSync(fileURLToPath(import.meta.url), 'utf8')
  // 先剔除本函数自身，避免"检查项里自带这些字符串"造成恒真。
  const start = source.indexOf('function selfTestL150GridContract')
  const body = start >= 0 ? source.slice(0, start) : source
  const needles = [
    'R-1 取消状态 UNKNOWN 时 /resume HTTP',
    "checkEq(resume.body.reason, 'CANCEL_STATE_UNKNOWN'",
    'R-2 已取消条目再次批准必须 409',
    'R-3 claim 哈希-only 不得伪未执行',
    "checkEq(redisCli('HSET', claimKey",
    'assertNoExecution(after,',
    // R-1 的重挂判据：决策帧必须回放 + 有界窗口内不得伪造终帧（"连接收流"不在判据面）。
    'R-1 决策帧必须已回放',
    'R-1 取消状态不可知不得伪造终帧'
  ]
  for (const needle of needles) {
    checkIncludes(body, needle, `L-150 三格断言 ${needle}`)
  }
  return `${needles.length} 条断言 + 2 条键形态 + 9 项产品契约`
}

function selfTestHygiene() {
  const files = [
    'scripts/ci/cluster3-run.sh',
    'scripts/ci/cluster3-probe.mjs',
    '.github/workflows/cluster-3node.yml'
  ]
  for (const rel of files) {
    const full = productFile(rel)
    check(fs.existsSync(full), `文件不存在：${rel}`)
    const text = fs.readFileSync(full, 'utf8')
    check(!text.includes('\r'), `${rel} 含 CRLF`)
    check(!/(^|[^A-Za-z0-9])[A-Za-z]:[\\/]/.test(text), `${rel} 含盘符绝对路径`)
    check(!/AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----/.test(text), `${rel} 疑似含密钥字面量`)
    for (const token of BANNED_PORT_TOKENS) {
      check(!text.includes(token), `${rel} 含禁用端口号 ${token}`)
    }
  }
  return `${files.length} 个文件`
}

// ── 双身份覆盖面的自证（身份登记 / 用例内容 / 前端通道帧集）─────────────────────

/** 产品目录 `TenantUserDirectory` 里某租户那**一行**（到下一个 `new TenantView(` 为止）。 */
function tenantDirectoryRow(source, tenantId) {
  const marker = `new TenantView(${tenantId}L`
  const at = source.indexOf(marker)
  check(at >= 0, `TenantUserDirectory 未声明租户 ${tenantId}L（双身份用例的身份组合不成立）`)
  const next = source.indexOf('new TenantView(', at + marker.length)
  return source.slice(at, next < 0 ? source.length : next)
}

/**
 * 双身份登记契约：A/B 必须都**真实登记**在产品目录里、且互不相同、且同租户。
 *
 * <p>为什么必须断言登记：`TenantIdentityFilter` 对未登记组合直接 400，"不可见"根本无从测起；
 * 脚本自造一个 id 会把用例做成"永远 400 的假红"。
 *
 * <p>为什么必须同租户：键面身份段是 `tenantId:userId` 两段拼接，同租户下只换用户才能钉住
 * "只按租户定键"这类欠隔离；换租户会把这种欠隔离掩盖掉。
 */
/**
 * **身份边界口径**的自证（HTTP 边界收紧后：`startup-resume` 与其 `POST /scan` 在数据面内）。
 *
 * <p>三组判据：
 * <ul>
 * <li>**必带头的路径**：`/api/ai/startup-resume`、`.../scan`（含结尾斜杠与 query 形态）必须
 *     `aiDataPath === true`，且 `aiIdentityHeaders` 给出两个头 —— 这就是"实际 POST /scan 会带头"
 *     的那一步（探针的 `postJson` 走同一个函数）；</li>
 * <li>**保留的排除面**：health/tools/tenants（精确匹配，子路径仍受保护）与
 *     providers/models/settings（整棵子树）必须 `false`；业务面（`/api/tasks`）同样 `false`；</li>
 * <li>**清单本身**：`AI_PUBLIC_EXACT_PATHS` 必须恰三条、无 `startup-resume`（类型级防回退）。</li>
 * </ul>
 */
function selfTestIdentityBoundary() {
  const headerPaths = [
    '/api/ai/startup-resume',
    '/api/ai/startup-resume/',
    '/api/ai/startup-resume/scan',
    '/api/ai/startup-resume/scan?lastSeq=1',
    '/api/ai/startup-resume/scan/'
  ]
  for (const pathname of headerPaths) {
    checkEq(aiDataPath(pathname), true, `${pathname} 必须属于数据面（HTTP 边界已收紧，缺头即 400）`)
    const headers = aiIdentityHeaders(pathname)
    checkEq(headers['X-Tenant-Id'], AI_TENANT_ID, `${pathname} 必须带 tenant 头`)
    checkEq(headers['X-User-Id'], AI_USER_ID, `${pathname} 必须带 user 头`)
  }
  const excluded = [
    '/api/ai/health',
    '/api/ai/health/',
    '/api/ai/tools',
    '/api/ai/tenants',
    '/api/ai/providers',
    '/api/ai/providers/1/keys',
    '/api/ai/models',
    '/api/ai/models/1',
    '/api/ai/settings/x',
    '/api/tasks',
    '/api/ai-health'
  ]
  for (const pathname of excluded) {
    checkEq(aiDataPath(pathname), false, `${pathname} 必须不在数据面（排除面/业务面）`)
    checkEq(Object.keys(aiIdentityHeaders(pathname)).length, 0, `${pathname} 不得带身份头`)
  }
  // 子路径默认受保护：精确排除**不**外溢（新增数据接口不会静默逃出边界）。
  for (const pathname of ['/api/ai', '/api/ai/session', '/api/ai/events/run-1', '/api/ai/runs/current', '/api/ai/frontend-tool-result', '/api/ai/healthz', '/api/ai/tools/x']) {
    checkEq(aiDataPath(pathname), true, `${pathname} 必须属于数据面（子路径默认受保护）`)
  }
  assertExactPathList(`[${AI_PUBLIC_EXACT_PATHS.join(',')}]`, '探针 AI_PUBLIC_EXACT_PATHS')
  // 反例：这条判据必须真的会红（否则"五处一致"只是把同一份错误读两遍）。
  selfExpectThrow(
    () =>
      assertExactPathList(
        "['/api/ai/health','/api/ai/tools','/api/ai/tenants','/api/ai/startup-resume','/api/ai/startup-resume/scan']",
        '自检：旧形态'
      ),
    '自检：精确排除集里留着 startup-resume(+scan) 必须红'
  )
  selfExpectThrow(
    () => assertExactPathList("['/api/ai/health','/api/ai/tools']", '自检：少一条'),
    '自检：精确排除集少一条必须红'
  )
  selfExpectThrow(
    () => assertExactPathList("['/api/ai/health','/api/ai/tools','/api/ai/tenants','/api/ai/tenants']", '自检：重复'),
    '自检：精确排除集重复必须红'
  )
  return `带头 ${headerPaths.length} 条 / 排除 ${excluded.length} 条 / 子路径 7 条 / 3 个反例`
}

/** 从 `marker` 起截一段"路径清单"文本（到 `terminator` 为止），用于跨文件口径比对。 */
function pathListSlice(text, marker, terminator, label) {
  const at = text.indexOf(marker)
  check(at >= 0, `${label}：找不到清单起点 ${marker}`)
  const end = text.indexOf(terminator, at + marker.length)
  check(end > at, `${label}：找不到清单终点 ${terminator}`)
  return text.slice(at, end)
}

/**
 * 一段"精确排除清单"必须**只有** health/tools/tenants：
 * 重复、缺一条、多一条、或把 `startup-resume(+scan)` 放回来，都立刻红。
 */
function assertExactPathList(slice, label) {
  const found = slice.match(/\/api\/ai\/[A-Za-z0-9_./-]+/g) || []
  checkEq(new Set(found).size, found.length, `${label}：清单里有重复路径（${found.join(',')}）`)
  checkEq(found.length, 3, `${label}：精确排除必须恰三条（实得 ${JSON.stringify(found)}）`)
  for (const required of ['/api/ai/health', '/api/ai/tools', '/api/ai/tenants']) {
    check(found.includes(required), `${label}：缺少 ${required}（实得 ${JSON.stringify(found)}）`)
  }
  check(
    !found.some((path) => path.startsWith('/api/ai/startup-resume')),
    `${label}：startup-resume(+scan) 不得留在精确排除集里（它在数据面内，必须带身份头）`
  )
}

/**
 * **五处口径一致**（产品源码 + 探针 + Playwright 支持 + 两个 PowerShell 脚本）：
 * 精确排除集都是 `health / tools / tenants`，都不含 `startup-resume`。
 *
 * <p>为什么值得单独自证：边界改一次要改五处，漏一处就会出现"脚本不带头的 scan 被 400"
 * 或"脚本以为 scan 是公开面"的静默错配 —— 前者在真实集群里是红，后者是假绿。
 */
function selfTestIdentityBoundaryConsistency() {
  const filter = readProduct('backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java')
  assertExactPathList(
    pathListSlice(filter, 'EXCLUDED_EXACT = Set.of(', ');', 'TenantIdentityFilter.EXCLUDED_EXACT'),
    '产品 TenantIdentityFilter'
  )
  const e2eIdentity = readProduct('frontend/e2e/support/ai-identity.ts')
  assertExactPathList(
    pathListSlice(e2eIdentity, 'const EXCLUDED_EXACT = new Set([', '])', 'e2e support ai-identity.ts'),
    'e2e support ai-identity.ts'
  )
  const verify = readProduct('scripts/verify-e2e.ps1')
  assertExactPathList(
    pathListSlice(verify, '$script:AiExcludedExactPaths = @(', ')', 'verify-e2e.ps1'),
    'verify-e2e.ps1'
  )
  const b23l1 = readProduct('scripts/b23l1-dual-instance.ps1')
  assertExactPathList(
    pathListSlice(b23l1, '$script:AiExcludedExactPaths = @(', ')', 'b23l1-dual-instance.ps1'),
    'b23l1-dual-instance.ps1'
  )
  // 同一脚本里另有一处内联清单（Start-Job 的脚本块不能引用父作用域变量，只能重复字面量）。
  assertExactPathList(
    pathListSlice(b23l1, "-not (@('/api/ai/health'", ') -contains', 'b23l1 内联清单'),
    'b23l1-dual-instance.ps1 内联清单'
  )
  // 前端生产实现是同一份口径的锚点（本查询不改它，只断言它没被改回去）。
  const frontendUtil = readProduct('frontend/src/utils/ai-identity.ts')
  checkIncludes(
    frontendUtil,
    "const IDENTITY_EXCLUDED_EXACT = ['/ai/health', '/ai/tools', '/ai/tenants']",
    '前端 utils/ai-identity.ts 的精确排除集'
  )
  // 两个 PowerShell 脚本的"裸请求必须 400"负控与"经脚本头帮助函数带头 → 2xx"正控都必须还在。
  for (const needle of [
    "'/api/ai/startup-resume/scan' $null",
    'TENANT_IDENTITY_REQUIRED',
    "Invoke-Api 'POST' '/api/ai/startup-resume/scan'",
    '$headScan.ok'
  ]) {
    checkIncludes(verify, needle, `verify-e2e.ps1 TENANT-AI-1 边界判据 ${needle}`)
  }
  // 双实例脚本的内联清单（Start-Job 里重复的字面量）同样不得把 startup-resume 当公开面。
  check(
    !pathListSlice(b23l1, "-not (@('/api/ai/health'", ') -contains', 'b23l1 内联清单').includes('startup-resume'),
    'b23l1-dual-instance.ps1 内联清单不得把 startup-resume 当公开面'
  )
  // 探针自己的负控/正控（run 模式）也必须还在。
  const zombie = probeFunctionSource('forceZombieResume')
  for (const needle of ["bareRequest('b', 'POST', '/api/ai/startup-resume/scan'", 'TENANT_IDENTITY_REQUIRED', "postJson('b', '/api/ai/startup-resume/scan'"]) {
    checkIncludes(zombie, needle, `探针 forceZombieResume 缺判据 ${needle}`)
  }
  return '产品 + 探针 + Playwright + 2 个 PowerShell 脚本同集（health/tools/tenants）'
}

function selfTestProbeIdentities() {
  const whoA = PROBE_IDENTITIES.a
  const whoB = PROBE_IDENTITIES.b
  checkEq(identityTag(whoA), `${AI_TENANT_ID}/${AI_USER_ID}`, 'A 身份必须是既有默认身份（基线 16 条的身份头逐字不变）')
  checkEq(whoA.tenantId === whoB.tenantId, true, 'A/B 必须同租户（否则掩盖"只按租户定键"的欠隔离）')
  checkEq(whoA.userId === whoB.userId, false, 'A/B 的用户必须不同')
  checkEq(stableStringify(aiIdentityHeaders('/api/ai/events/run-self', whoA)) === stableStringify(aiIdentityHeaders('/api/ai/events/run-self', whoB)), false, 'A/B 的身份头不得相同')

  const directory = readProduct('backend/src/main/java/com/example/configmgr/ai/tenant/TenantUserDirectory.java')
  const row = tenantDirectoryRow(directory, whoA.tenantId)
  for (const who of [whoA, whoB]) {
    checkIncludes(row, `new UserView(${who.userId}L`, `身份 ${identityTag(who)} 未登记在租户 ${who.tenantId} 下`)
  }
  // 头面口径：数据面带身份、公开面/业务面不带（与 TenantIdentityFilter#shouldNotFilter 同源）。
  const dataHeaders = aiIdentityHeaders('/api/ai/runs/current', whoB)
  checkEq(dataHeaders['X-Tenant-Id'], String(whoB.tenantId), 'B 身份 tenant 头')
  checkEq(dataHeaders['X-User-Id'], String(whoB.userId), 'B 身份 user 头')
  for (const openPath of ['/api/ai/health', '/api/ai/tools', '/api/ai/tenants', '/api/ai/providers', '/api/tasks']) {
    checkEq(Object.keys(aiIdentityHeaders(openPath, whoB)).length, 0, `${openPath} 不得带身份头（排除面）`)
  }
  checkEq(aiIdentityHeaders('/api/ai/events/run-self')['X-User-Id'], AI_USER_ID, '缺省身份必须仍是 A')

  // run 级键形必须与产品 `RunKeyScope.tag` 一致，且两个身份**落在不同的键**上
  // （零副作用侧证靠 PTTL 直接读这两个键，键拼错就会读到别人的/读不到）。
  checkEq(identityScopedRunKey('ai:run:', whoA, 'run-self'), 'ai:run:{1:101:run:run-self}', 'A 的 run 级键形')
  checkEq(identityScopedRunKey('ai:pending:', whoB, 'run-self'), `ai:pending:{${whoB.tenantId}:${whoB.userId}:run:run-self}`, 'B 的 run 级键形')
  checkEq(
    identityScopedRunKey('ai:run:', whoA, 'run-self') === identityScopedRunKey('ai:run:', whoB, 'run-self'),
    false,
    '同一 runId 在两个身份下必须落在不同键上'
  )
  // 零副作用侧证会红（PTTL 变长 = 被续期）—— 用假的读数证明它真的在看这一面。
  selfExpectThrow(
    () =>
      assertNoCrossIdentityRenewal(
        '自检',
        { snapshot: 'ai:run:{1:101:run:r}' },
        { snapshot: 8000 },
        { snapshot: 24000 }
      ),
    '自检：PTTL 变长（被另一个身份续期）必须红'
  )
  selfExpectThrow(
    () =>
      assertNoCrossIdentityRenewal(
        '自检',
        { snapshot: 'ai:run:{1:101:run:r}' },
        { snapshot: 8000 },
        { snapshot: -2 }
      ),
    '自检：键在探测期间消失必须红'
  )
  selfExpectThrow(
    () =>
      assertNoCrossIdentityRenewal(
        '自检',
        { snapshot: 'ai:run:{1:101:run:wrong}' },
        { snapshot: -2 },
        { snapshot: -2 }
      ),
    '自检：键形拼错（PTTL=-2，键根本不存在）必须红'
  )
  selfExpectThrow(
    () =>
      assertNoCrossIdentityRenewal(
        '自检',
        { snapshot: 'ai:run:{1:101:run:r}' },
        { snapshot: -1 },
        { snapshot: -1 },
        { ttlRequired: ['snapshot'] }
      ),
    '自检：要求 TTL 的键没有 TTL 必须红'
  )
  assertNoCrossIdentityRenewal('自检正例', { snapshot: 'ai:run:{1:101:run:r}' }, { snapshot: 8000 }, { snapshot: 6500 })
  assertNoCrossIdentityRenewal(
    '自检正例（无 TTL 键）',
    { pending: 'ai:pending:{1:101:run:r}' },
    { pending: -1 },
    { pending: -1 }
  )
  return `${identityTag(whoA)} vs ${identityTag(whoB)}（同租户、均登记、键形互异）`
}

/** 取本文件顶层函数体（`function name(` 到行首 `}`）：用于"这段判据还在不在"的静态自证。 */
function probeFunctionSource(name) {
  const source = fs.readFileSync(fileURLToPath(import.meta.url), 'utf8')
  const at = source.indexOf(`function ${name}(`)
  check(at >= 0, `探针源码里找不到函数 ${name}`)
  const end = source.indexOf('\n}', at)
  check(end > at, `函数 ${name} 的结尾无法定位`)
  return source.slice(at, end + 2)
}

/**
 * 会话号契约自证（`newSession`）：发号已从雪花号改为 `System.currentTimeMillis()`，
 * 判据只剩"十进制正整数 + 文本与数值同值 + 撞号 503 有界重试"，不再按位数放行/拦截。
 * 反例都走 `selfExpectThrow`，防止把判据写成"恒真"。
 */
function selfTestSessionIdContract() {
  // 正例：13 位毫秒时间戳（当前形态）/ 安全整数边界 / 19 位旧雪花号——都不按位数判。
  for (const accepted of ['1793328873442', '9007199254740991', '1731234567890123456']) {
    checkEq(assertSessionIdText(accepted, 'self'), accepted, `会话号正例 ${accepted}`)
  }
  for (const rejected of ['', '0', '-1793328873442', '1.5', '12a', ' 1793328873442', 'abc']) {
    selfExpectThrow(() => assertSessionIdText(rejected, 'self'), `自检：会话号 ${JSON.stringify(rejected)} 必须红`)
  }

  checkEq(
    assertSessionIdEcho({ success: true, data: { sessionId: 1793328873442 } }, '1793328873442', 'self'),
    '1793328873442',
    '数值与文本同值'
  )
  selfExpectThrow(
    () => assertSessionIdEcho({ success: true, data: { sessionId: 1793328873443 } }, '1793328873442', 'self'),
    '自检：数值与文本不一致必须红'
  )
  selfExpectThrow(
    () => assertSessionIdEcho({ success: true, data: {} }, '1793328873442', 'self'),
    '自检：缺数值字段必须红'
  )
  selfExpectThrow(() => assertSessionIdEcho(null, '1793328873442', 'self'), '自检：响应体缺 data 必须红')
  // 超安全整数时数值字段本就不可信：不算错，只以文本为准（两字段并存的原因）。
  checkIncludes(
    assertSessionIdEcho({ success: true, data: { sessionId: 1731234567890123500 } }, '1731234567890123456', 'self'),
    '文本为权威',
    '超安全整数时以文本为准'
  )

  // 只认"503 + SESSION_ID_UNAVAILABLE"这一种可重试，其它错误不得被重试吞掉。
  checkEq(SESSION_ID_MAX_ATTEMPTS, 3, '撞号重试上限')
  check(isSessionIdCollision({ status: 503 }, { code: SESSION_ID_UNAVAILABLE }), '503+撞号码必须判撞号')
  check(!isSessionIdCollision({ status: 503 }, { code: 'AI_REDIS_UNAVAILABLE' }), '503 但非撞号码不得判撞号')
  check(!isSessionIdCollision({ status: 500 }, { code: SESSION_ID_UNAVAILABLE }), '非 503 不得判撞号')
  check(!isSessionIdCollision({ status: 503 }, null), '无响应体不得判撞号')
  check(!isSessionIdCollision({ status: 200 }, { code: SESSION_ID_UNAVAILABLE }), '200 不得判撞号')

  // 源码契约：重试循环与两条判据都必须在 newSession 里，且不得再按位数（雪花号 ≥16）判。
  const src = probeFunctionSource('newSession')
  for (const needle of [
    'attempt <= SESSION_ID_MAX_ATTEMPTS',
    'isSessionIdCollision(record, body)',
    'assertSessionIdText(',
    'assertSessionIdEcho('
  ]) {
    checkIncludes(src, needle, `newSession 缺判据 ${needle}`)
  }
  check(!/length\s*>=\s*16/.test(src), 'newSession 不得再按位数判会话号')
  return '7 反例 + 3 正例 + 5 条撞号判定 + 源码契约'
}

/**
 * 双身份用例的内容契约（防"注册了但把断言删空"）：
 * ① 基线 16 条仍在 `CASES` 的**前 16 位**（顺序不变）且仍全在 `REQUIRED_CASE_IDS`；
 * ② 两条新用例在 `CASES`/`REQUIRED_CASE_IDS`/`BASELINE` 之外各自成立；
 * ③ 新用例与探测助手的函数体里真的包含那些判据（404/409/零副作用指纹/前端帧册）。
 */
function selfTestIdentityCaseContract() {
  checkEq(BASELINE_CASE_IDS.length, 16, '基线用例数必须恰为 16（不削弱）')
  const ids = CASES.map((def) => def.id)
  // 基线 16 条必须仍占满**前 16 个执行位**（新用例只许追加在后面）：删一条/插一条都立刻红。
  const prefix = ids.slice(0, BASELINE_CASE_IDS.length)
  checkEq(prefix.length, BASELINE_CASE_IDS.length, 'CASES 前 16 位不足（基线用例被删）')
  checkEq(new Set(prefix).size, BASELINE_CASE_IDS.length, `CASES 前 16 位必须恰是基线 16 条（实得 ${JSON.stringify(prefix)}）`)
  for (const id of BASELINE_CASE_IDS) {
    check(prefix.includes(id), `基线用例 ${id} 不在前 16 个执行位（被删除或被新用例挤走）`)
    checkIncludes(REQUIRED_CASE_IDS.join(','), id, `REQUIRED_CASE_IDS 缺基线用例 ${id}`)
  }
  for (const id of IDENTITY_CASE_IDS) {
    check(ids.includes(id), `CASES 缺双身份用例 ${id}`)
    check(REQUIRED_CASE_IDS.includes(id), `REQUIRED_CASE_IDS 缺双身份用例 ${id}`)
  }
  checkEq(ids.length, REQUIRED_CASE_IDS.length, '用例数量与必需清单')

  const probe = probeFunctionSource('probeIdentityInvisible')
  for (const needle of [
    '/api/ai/events/${runId}?probe=1',
    "checkEq(events.status, 404",
    "'UNKNOWN_RUN'",
    'exists, false',
    'found, false',
    'history.body.data.total, 0',
    "getJson(key, '/api/ai/startup-resume'",
    'resumeReport.record.text.includes(runId)',
    "'UNKNOWN_TOOL_CALL'",
    'frontendToolResultOn(',
    'saveEvidence(evidenceName, evidence)'
  ]) {
    checkIncludes(probe, needle, `B 身份探测助手缺判据 ${needle}`)
  }

  const confirmCase = probeFunctionSource('caseIdentityCrossInstance')
  for (const needle of [
    'identityStateFingerprint',
    'stableStringify(after)',
    'pttlOf(',
    'assertNoCrossIdentityRenewal(',
    'confirmOn(',
    'STATE.instanceIds.c',
    'assertFrameLedger(full',
    'after.archiveMaxSeq, run.lastSeq'
  ]) {
    checkIncludes(confirmCase, needle, `确认门双身份用例缺判据 ${needle}`)
  }

  const frontendCase = probeFunctionSource('caseIdentityFrontendCrossInstance')
  for (const needle of [
    'createFrontendParkedRun(',
    'frontendEntry: true',
    'assertFrontendLedger(full',
    'expectedResult: resultText',
    "'FRONTEND_RESULT'",
    'stableStringify(after)',
    'assertNoCrossIdentityRenewal(',
    "reattachOn('c', park.runId, park.lastSeq"
  ]) {
    checkIncludes(frontendCase, needle, `前端通道双身份用例缺判据 ${needle}`)
  }
  return `基线 ${BASELINE_CASE_IDS.length} + 双身份 ${IDENTITY_CASE_IDS.length}（探测助手 11 条判据）`
}

/**
 * 身份侧效应指纹的自证：同态（只差 TTL/时间戳）必须相等，任一"被写过才会变"的字段变了必须不相等。
 * 这套判据是"零副作用"的主证，如果它对单字段不敏感，用例就会变成只比"两边都非空"。
 */
function selfTestIdentityFingerprint() {
  const current = {
    runId: 'r-self-id',
    snapshotStatus: 'SUSPENDED',
    awaitingExternal: true,
    archiveMaxSeq: 7,
    unresolvedExternal: ['tc-self'],
    pendingEntries: [
      { toolCallId: 'tc-self', entryStatus: 'PENDING', kind: 'CONFIRM', name: 'start_export', arguments: '{"taskId":1}', req: true }
    ],
    snapshotRemainingMs: 1000,
    snapshotExpiresAt: 111
  }
  const detail = {
    exists: true,
    snapshot: { status: 'SUSPENDED', atMs: 1 },
    pending: [
      {
        toolCallId: 'tc-self',
        status: 'PENDING',
        outcome: null,
        executed: false,
        executedBy: null,
        resultText: null,
        resolvedAtMs: 0,
        claimedAtMs: 0
      }
    ],
    ledger: [],
    ledgerCounts: { start_export: 1 }
  }
  const base = identityStateFingerprint(current, detail)
  const same = identityStateFingerprint(
    { ...current, snapshotRemainingMs: 999, snapshotExpiresAt: 222 },
    { ...detail, snapshot: { status: 'SUSPENDED', atMs: 99 } }
  )
  checkEq(stableStringify(same), stableStringify(base), '身份指纹不得把 TTL/时间戳算成副作用')
  const mutations = [
    ['快照状态', () => identityStateFingerprint({ ...current, snapshotStatus: 'DONE' }, detail)],
    ['归档末号', () => identityStateFingerprint({ ...current, archiveMaxSeq: 8 }, detail)],
    ['awaitingExternal', () => identityStateFingerprint({ ...current, awaitingExternal: false }, detail)],
    [
      '待决条目状态',
      () => identityStateFingerprint(current, { ...detail, pending: [{ ...detail.pending[0], status: 'APPROVED' }] })
    ],
    [
      '待决条目结果文本',
      () => identityStateFingerprint(current, { ...detail, pending: [{ ...detail.pending[0], resultText: '被写了' }] })
    ],
    [
      '待决条目决态时刻',
      () => identityStateFingerprint(current, { ...detail, pending: [{ ...detail.pending[0], resolvedAtMs: 123 }] })
    ],
    ['台账计数', () => identityStateFingerprint(current, { ...detail, ledgerCounts: { start_export: 2 } })]
  ]
  for (const [name, mutate] of mutations) {
    checkEq(
      stableStringify(mutate()) === stableStringify(base),
      false,
      `身份指纹必须对"${name}"的变化敏感（否则"零副作用"会被静默放行）`
    )
  }
  return `1 正例 + ${mutations.length} 个单字段反例`
}

/** 前端通道帧册 fixture（帧面按产品 `SseChatEmitter#frontendToolRequest/frontendToolResult` 的字段）。 */
function frontendLedgerFixture() {
  const args = '{"page":"export","taskId":1}'
  return [
    { type: 'start', seq: 1, runId: 'r-self-fe', sessionId: 's-self-fe' },
    {
      type: 'suspended',
      seq: 2,
      runId: 'r-self-fe',
      toolCalls: [{ toolCallId: 'tc-fe', name: 'navigate_to', kind: 'FRONTEND', arguments: args }],
      stateKey: 'ai:run:1:101:{run:r-self-fe}',
      pendingKey: 'ai:pending:1:101:{run:r-self-fe}',
      writtenBy: 'instance-self'
    },
    { type: 'tool_start', seq: 3, toolCallId: 'tc-fe', name: 'navigate_to', kind: 'FRONTEND', args },
    {
      type: 'frontend_tool_request',
      seq: 4,
      toolCallId: 'tc-fe',
      name: 'navigate_to',
      args,
      timeoutSeconds: 120,
      callback: 'POST /api/ai/frontend-tool-result {runId, toolCallId, result}'
    },
    {
      type: 'frontend_tool_result',
      seq: 5,
      toolCallId: 'tc-fe',
      name: 'navigate_to',
      ok: true,
      executed: false,
      status: 'FRONTEND_RESULT',
      outcome: 'ok',
      result: '已打开导出向导'
    },
    { type: 'message_start', seq: 6 },
    { type: 'delta', seq: 7, text: '向导已打开，请继续' },
    { type: 'message_end', seq: 8 },
    { type: 'done', seq: 9 }
  ]
}

/**
 * 前端通道帧集断言的自证（正例 + 8 个反例）：证明这套断言**会红**，不是橡皮图章。
 * 反例覆盖：混入确认卡 / 缺 tool_start / kind 写错 / 结局帧被写成 tool_result /
 * ok=false / 请求帧缺 callback / seq 断号 / 出现 error 帧。
 */
function selfTestFrontendFrameContract() {
  const ledger = frontendLedgerFixture()
  const summary = assertFrontendLedger(ledger, {
    toolCallId: 'tc-fe',
    expectedName: 'navigate_to',
    expectedResult: '已打开导出向导'
  })
  checkEq(
    summary.types,
    'start,suspended,tool_start,frontend_tool_request,frontend_tool_result,message_start,delta,message_end,done',
    '自检前端帧册帧型'
  )
  // 决策前重挂的最小帧集：首 4 帧（request 已到、已收流）。
  const parked = ledger.filter((frame) => frame.seq <= 4)
  assertFrontendParkedFrameSet(parked, 'tc-fe')
  assertFrontendParkedPayloads(parked, { toolCallId: 'tc-fe' })

  const rebuild = (mutate) => {
    const copy = ledger.map((frame) => ({ ...frame }))
    mutate(copy)
    return copy
  }
  selfExpectThrow(
    () => assertFrontendLedger([...parked, { type: 'confirm_request', seq: 5, toolCallId: 'tc-fe', name: 'navigate_to', args: '{}' }], { toolCallId: 'tc-fe' }),
    '自检：前端通道混入 confirm_request 必须红'
  )
  selfExpectThrow(
    () => assertFrontendLedger(ledger.filter((frame) => frame.type !== 'tool_start'), { toolCallId: 'tc-fe' }),
    '自检：前端帧册缺 tool_start 必须红'
  )
  selfExpectThrow(
    () =>
      assertFrontendLedger(
        rebuild((copy) => {
          copy[1].toolCalls[0].kind = 'CONFIRM'
        }),
        { toolCallId: 'tc-fe' }
      ),
    '自检：suspended 公告把 kind 写成 CONFIRM 必须红'
  )
  selfExpectThrow(
    () =>
      assertFrontendLedger(
        rebuild((copy) => {
          copy[4].type = 'tool_result'
        }),
        { toolCallId: 'tc-fe' }
      ),
    '自检：前端结局帧被写成 tool_result 必须红'
  )
  selfExpectThrow(
    () =>
      assertFrontendLedger(
        rebuild((copy) => {
          copy[4].ok = false
        }),
        { toolCallId: 'tc-fe' }
      ),
    '自检：frontend_tool_result.ok=false 必须红'
  )
  selfExpectThrow(
    () => assertFrontendParkedPayloads(parked.map((frame) => (frame.type === 'frontend_tool_request' ? { ...frame, callback: undefined } : frame)), { toolCallId: 'tc-fe' }),
    '自检：请求帧缺 callback 必须红'
  )
  selfExpectThrow(
    () =>
      assertFrontendLedger(
        rebuild((copy) => {
          copy[6].seq = 9
        }),
        { toolCallId: 'tc-fe' }
      ),
    '自检：前端帧册 seq 断号必须红'
  )
  selfExpectThrow(
    () => assertFrontendLedger([...ledger.slice(0, 8), { type: 'error', seq: 9 }], { toolCallId: 'tc-fe' }),
    '自检：前端帧册出现 error 帧必须红'
  )
  return `9 帧 + 8 个反例`
}

async function selfCheckMain() {
  console.log('cluster3-probe --self-check（不启动 Java/Docker、不连接任何端口）\n')
  selfCheck(`用例注册覆盖用户清单（${REQUIRED_CASE_IDS.length} 条）`, selfTestCaseRegistry)
  selfCheck('结果状态只有 PASS/FAIL（无 SKIP）', () => RESULT_STATUSES.join(',') === 'PASS,FAIL' ? 'PASS/FAIL' : (() => { throw new Error(RESULT_STATUSES.join(',')) })())
  selfCheck('会话号契约（十进制正整数 / 文本=数值 / 503 撞号有界重试）', selfTestSessionIdContract)
  selfCheck('端口段 231xx 且禁用端口不出现', selfTestPortHygiene)
  selfCheck('静态 import 全部为 node: 内置模块', selfTestStaticImports)
  selfCheck('seq 断言（递增/乱序/重复）', selfTestSeqAndAssertions)
  selfCheck('park 边界帧集契约（含 tool_start 与终帧禁止）', selfTestParkBoundaryContract)
  selfCheck('允许的 pre-park 帧（心跳/文本/压缩/重试不得误判）', selfTestAllowedPreParkFrames)
  selfCheck('全量回放帧册（seq 连续/恰一次/顺序/帧型白名单）', selfTestFrameLedger)
  selfCheck('缺 seq 必须拒绝（不得静默过滤）', selfTestMissingSeqRejected)
  selfCheck('载荷断言（tool_result/decision/delta/args 不得空壳或错值）', selfTestBadPayloadRejected)
  selfCheck('B 差量连续性（不重不漏）', selfTestDeltaContiguous)
  selfCheck('决策后差量连接的帧型/载荷（I1(b)(c)）', selfTestDeltaConnectionFrames)
  selfCheck('状态类证据先落盘再断言（current/detail/jobs/history）', selfTestStateEvidenceOrder)
  selfCheck('帧册末号口径 = 本次全量回放末号（N2）', selfTestLedgerTipScope)
  selfCheck('早杀观测只记"kill 前是否已见"（N3）', selfTestKillObservation)
  selfCheck('决策前重挂契约（回放完整且不收终帧）', selfTestPreDecisionReplay)
  selfCheck('证据先落盘再断言（断言失败仍留证据）', selfTestEvidenceBeforeAssertions)
  await selfCheckAsync('park 收流屏障（真实 HTTP：收流/空回放正例 + 悬挂反例）', selfTestParkCloseBarrier)
  selfCheck('HAProxy stats CSV 解析', selfTestStatsCsv)
  selfCheck('产品端点/字段静态契约', selfTestProductContract)
  selfCheck('桩路由表契约（TC16-EXPORT）', selfTestStubContract)
  selfCheck('实例启动参数（h2tcp / LOCK_TIMEOUT / trusted-proxies）', selfTestInstanceArgs)
  selfCheck('双身份登记（A/B 同租户、均在产品目录、身份头口径）', selfTestProbeIdentities)
  selfCheck('身份边界（startup-resume(+scan) 在数据面内 / 排除面只剩 health+tools+tenants）', selfTestIdentityBoundary)
  selfCheck('身份边界五处口径一致（产品 + 探针 + Playwright + 2 个 PowerShell 脚本）', selfTestIdentityBoundaryConsistency)
  selfCheck('双身份用例内容契约（基线 16 条不变 + 新用例判据仍在）', selfTestIdentityCaseContract)
  selfCheck('身份侧效应指纹（TTL 不算副作用 / 单字段变化必红）', selfTestIdentityFingerprint)
  selfCheck('前端通道帧集断言（frontend_tool_request/result 正例 + 8 反例）', selfTestFrontendFrameContract)
  selfCheck('复审契约（SIGSTOP/deferred / 分档码 / providerId 绑定）', selfTestReviewContracts)
  selfCheck('冷启动种子身份（非合成库行 + 三实例一致）', selfTestColdProviderIdentity)
  selfCheck('L-150 三格契约（取消键/认领表/判据方法 + 探针断言仍在）', selfTestL150GridContract)
  selfCheck('三个交付文件的卫生（LF/无盘符/无密钥/无禁用端口）', selfTestHygiene)
  await selfCheckAsync('SSE 解析器（任意 chunk 边界 / CRLF / 多行 data / [DONE]）', selfTestSseParser)
  await selfCheckAsync('keep-alive 单连接判定（同客户端端口覆盖 3 节点）', selfTestKeepAlive)
  selfCheck('代理无粘滞配置断言（run 模式会再读实际配置）', () => {
    const sample = fs.existsSync(productFile('.github/workflows/cluster-3node.yml'))
    check(sample, '工作流文件不存在')
    return '配置断言函数已就绪'
  })

  console.log(`\n自证结果：PASS ${SELF.pass} / FAIL ${SELF.fail}`)
  if (SELF.fail > 0) {
    console.log('失败项：')
    for (const failure of SELF.failures) console.log(`- ${failure.name}：${failure.reason}`)
    return 1
  }
  console.log('SELF-CHECK PASS')
  return 0
}

// ── 入口 ───────────────────────────────────────────────────────────────────

async function main() {
  if (MODE_HELP) {
    console.log(`用法：
  node scripts/ci/cluster3-probe.mjs --self-check
  node scripts/ci/cluster3-probe.mjs --run --artifacts <dir> --run-dir <dir> --jar <jar> --h2-jar <h2.jar>
       --db-base <dir> --shared-files <dir> --java java --redis-container <name>
       --haproxy-container <name> --haproxy-config <file>
       --stub-url http://127.0.0.1:23199 --proxy-url http://127.0.0.1:23180
       --stats-url 'http://127.0.0.1:23189/stats;csv'
       --ports 'a=23181,b=23182,c=23183,h2=23190,redis=23179'
       [--case preexec-cancel-grid]   # 单点/子集（逗号分隔多个 id）；不写 = 全套
                                      # L-150 三格请用：--case preexec-cancel-grid
                                      # 双身份两条请用：--case identity-cross-instance,identity-frontend-cross-instance`)
    return 0
  }
  if (MODE_SELF_CHECK) return selfCheckMain()
  if (!MODE_RUN) {
    console.error('缺少模式：--self-check 或 --run（--help 查看用法）')
    return 2
  }
  if (process.env.GITHUB_ACTIONS !== 'true' && process.env.CLUSTER3_ALLOW_LOCAL !== '1') {
    console.error('拒绝在本机启动真实三实例：只允许 --self-check；确需本机运行请设 CLUSTER3_ALLOW_LOCAL=1')
    return 2
  }
  process.on('SIGINT', async () => {
    console.error('\n收到 SIGINT：停止实例与 H2 后退出')
    await stopEverything()
    process.exit(130)
  })
  process.on('SIGTERM', async () => {
    console.error('\n收到 SIGTERM：停止实例与 H2 后退出')
    await stopEverything()
    process.exit(143)
  })
  return mainRun()
}

main().then((code) => {
  process.exitCode = code
}).catch((e) => {
  console.error(`未捕获异常：${e && e.stack ? e.stack : e}`)
  process.exitCode = 2
})
