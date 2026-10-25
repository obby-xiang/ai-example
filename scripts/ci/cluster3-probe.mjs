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
 * 用法：
 *   node scripts/ci/cluster3-probe.mjs --self-check
 *   node scripts/ci/cluster3-probe.mjs --run --artifacts <dir> --run-dir <dir> --jar <jar>
 *        --h2-jar <h2.jar> --db-base <dir> --shared-files <dir> --java java
 *        --redis-container <name> --haproxy-container <name> --haproxy-config <file>
 *        --stub-url http://127.0.0.1:23199 --proxy-url http://127.0.0.1:23180
 *        --stats-url 'http://127.0.0.1:23189/stats;csv'
 *        --ports 'a=23181,b=23182,c=23183,h2=23190,redis=23179'
 *        [--ready-timeout 180] [--job-fail-timeout 90] [--kill-takeover-timeout 120]
 *
 * 退出码：0 = 全部 PASS；1 = 有 FAIL；2 = 环境/参数错误（基线起不来，后续用例无意义）。
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

const RESULT_STATUSES = Object.freeze(['PASS', 'FAIL'])

/** 用户要求的覆盖面（--self-check 逐条断言 CASES 注册齐全）。 */
const REQUIRED_CASE_IDS = Object.freeze([
  'cold-start-3',
  'proxy-no-sticky',
  'same-tcp-across-abc',
  'reattach-any-instance',
  'history-pagination',
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
  'redis-outage-recovery'
])

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
        ...(options.headers || {})
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

async function getJson(key, apiPath, { timeoutMs = 15000 } = {}) {
  const record = await httpRequest(directUrl(key, apiPath), {
    timeoutMs,
    routeKind: 'api',
    directNode: directNode(key)
  })
  return { record, body: record.text ? jsonOf(record, `${key} ${apiPath}`) : null }
}

async function postJson(key, apiPath, body, { timeoutMs = 20000 } = {}) {
  const payload = Buffer.from(JSON.stringify(body), 'utf8')
  const record = await httpRequest(directUrl(key, apiPath), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: payload,
    timeoutMs,
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

async function runDetail(key, runId) {
  const { record, body } = await getJson(key, `/api/ai/runs/${runId}`)
  checkEq(record.status, 200, `GET /api/ai/runs/${runId}（${key}）HTTP`)
  check(body && body.success === true && body.data, `run 详情异常：${record.text.slice(0, 400)}`)
  return body.data
}

async function currentRun(key, sessionId) {
  const { record, body } = await getJson(key, `/api/ai/runs/current?sessionId=${encodeURIComponent(sessionId)}`)
  checkEq(record.status, 200, `GET /api/ai/runs/current（${key}）HTTP`)
  check(body && body.success === true && body.data, `runs/current 异常：${record.text.slice(0, 400)}`)
  return body.data
}

async function historyOf(key, sessionId) {
  const { record, body } = await getJson(key, `/api/ai/history/${encodeURIComponent(sessionId)}`)
  return { record, body }
}

/** 完整历史游标页（`/history/{sid}/messages`；afterSeq/beforeSeq 二选一，limit 1..200）。 */
async function fullHistoryPage(key, sessionId, { afterSeq = null, beforeSeq = null, limit = 50 } = {}) {
  const params = new URLSearchParams()
  if (afterSeq !== null && afterSeq !== undefined) params.set('afterSeq', String(afterSeq))
  if (beforeSeq !== null && beforeSeq !== undefined) params.set('beforeSeq', String(beforeSeq))
  if (limit !== null && limit !== undefined) params.set('limit', String(limit))
  const query = params.toString()
  const { record, body } = await getJson(key, `/api/ai/history/${encodeURIComponent(sessionId)}/messages${query ? `?${query}` : ''}`)
  return { record, body }
}

/** 等完整历史落齐（终态后 recorder 异步补齐；轮询到至少 minCount 条）。 */
/** 等完整历史落齐（终态后 recorder 异步补齐；按 seq 正序轮询到至少 minCount 条）。 */
async function waitFullHistory(key, sessionId, minCount = 2, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() < deadline) {
    const page = await fullHistoryPage(key, sessionId, { afterSeq: 0, limit: 50 })
    last = page
    const data = page.body && page.body.success === true ? page.body.data : null
    if (page.record.status === 200 && data && Array.isArray(data.messages) && data.messages.length >= minCount) {
      return data
    }
    await sleep(500)
  }
  throw new Error(`完整历史（${key}）在 ${timeoutMs}ms 内不足 ${minCount} 条；最后响应=${last && last.record ? last.record.text.slice(0, 500) : '无'}`)
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
    directNode: options.viaProxy ? null : directNode(key)
  })
}

async function reattachOn(key, runId, lastSeq, options = {}) {
  const query = lastSeq === undefined || lastSeq === null ? '' : `?lastSeq=${lastSeq}`
  return openSse(directUrl(key, `/api/ai/events/${runId}${query}`), {
    timeoutMs: options.timeoutMs || 120000,
    directNode: directNode(key)
  })
}

async function confirmOn(key, { runId, toolCallId, approved = true, reason = 'cluster3 probe' }) {
  return postJson(key, '/api/ai/confirm', { runId, toolCallId, approved, reason })
}

async function fullReplay(key, runId, timeoutMs = 90000) {
  const stream = await reattachOn(key, runId, 0, { timeoutMs })
  try {
    await stream.waitTerminal(timeoutMs)
    return stream.frames.slice()
  } finally {
    stream.close()
  }
}

/**
 * 终态全量回放到终帧。产品存在一个窄窗：finish() 先写 DONE、done 帧稍后才落档，
 * 恰好在此窗口到达的全量回放会停在 message_end（双实例 harness 的已知形态）。
 * 这里短延迟重试，只有 4 次仍无终帧才 FAIL —— 不掩盖真实的 done 缺失。
 */
async function fullReplayToTerminal(key, runId, timeoutMs = 90000) {
  let lastError = null
  for (let attempt = 1; attempt <= 4; attempt += 1) {
    try {
      return await fullReplay(key, runId, timeoutMs)
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

/** 从暂停的 run 里取「任务确认卡」的参数；不触发模型。 */
async function createSuspendedRun(key, { prefix, taskId, messageSuffix = '', contextPage = 'tasks' }) {
  const sessionId = uniqueId(prefix)
  const context = { page: contextPage, taskType: 'EXPORT', step: 'EXPORT', taskId }
  const message = `start_export 帮我导出 taskId=${taskId}${messageSuffix}`
  const stream = await chatOn(key, { sessionId, message, context })
  let runId = null
  let toolCallId = null
  let lastSeq = 0
  const deadline = Date.now() + 120000
  try {
    while (Date.now() < deadline) {
      const frame = await stream.waitFor((f) => f.type === 'confirm_request' || f.type === 'done' || f.type === 'error', {
        timeoutMs: Math.max(1000, deadline - Date.now()),
        label: '等待确认卡'
      })
      if (frame.type === 'confirm_request') {
        toolCallId = frame.toolCallId
        break
      }
      throw new Error(`期望确认卡，实得 ${frame.type}：${JSON.stringify(frame).slice(0, 500)}`)
    }
    for (const frame of stream.frames) {
      if (frame.type === 'start') runId = frame.runId
      if (typeof frame.seq === 'number') lastSeq = Math.max(lastSeq, frame.seq)
    }
  } finally {
    stream.close()
  }
  check(runId, `未收到 start 帧（runId 缺失）；已收帧=${stream.types().join(',')}`)
  check(toolCallId, `确认卡缺少 toolCallId；已收帧=${stream.types().join(',')}`)
  return { sessionId, taskId, runId, toolCallId, lastSeq, message }
}

// ── 用例注册（逐条覆盖用户清单；顺序即执行顺序）────────────────────────────

const CASES = [
  { id: 'cold-start-3', title: '三实例冷启动（共享 H2-TCP / Redis / 文件目录）', fn: caseColdStart3 },
  { id: 'proxy-no-sticky', title: '无 cookie/IP 粘滞（固定 Cookie + 固定 XFF，30 请求覆盖 A/B/C）', fn: caseProxyNoSticky },
  { id: 'same-tcp-across-abc', title: '同一条 keep-alive TCP 连接连续请求覆盖 A/B/C（代理日志佐证）', fn: caseSameTcpAcrossAbc },
  { id: 'reattach-any-instance', title: '任意实例 reattach（B 全量 / C 差量）与 history', fn: caseReattachAnyInstance },
  { id: 'history-pagination', title: '完整历史游标分页：B/C 跨实例正序翻页（afterSeq/limit、hasMore/nextCursor、seq 连续）', fn: caseHistoryPagination },
  { id: 'seq-cross-instance', title: '跨实例 seq：实时流 / 增量重挂 / 全量回放同号同序', fn: caseSeqCrossInstance },
  { id: 'suspend-cross-instance', title: '挂起在 A → B/C 重挂 → C 决策 → 两连接都收到续跑终帧', fn: caseSuspendCrossInstance },
  { id: 'idempotency-cross-instance', title: '跨实例幂等：重复决策 409 + 并发双决策恰一胜 + 副作用恰一次', fn: caseIdempotencyCrossInstance },
  { id: 'session-purge-cross-instance', title: '跨实例会话销毁：B 删 → C 验无残留；在途慢流 deferred → 终态重试 complete', fn: caseSessionPurgeCrossInstance },
  { id: 'config-hot-reload', title: '配置热更新：A 写入 → B/C 在 reload 周期内看到新版本与新值', fn: caseConfigHotReload },
  { id: 'shared-file-download', title: '共享文件：A 上传 → B/C/代理下载字节一致', fn: caseSharedFileDownload },
  { id: 'suspend-kill-a', title: '挂起态 kill A → B 决策接管执行（台账 executedBy=B）→ C history', fn: caseSuspendKillA },
  { id: 'streaming-kill-a', title: '流式中 kill A → 僵尸扫描接管 → 跨实例补帧到终帧（seq 无洞）', fn: caseStreamingKillA },
  { id: 'job-kill-a', title: '作业中 kill A → 幸存实例看门狗收敛 FAILED → 重启清理后 B 重试成功', fn: caseJobKillA },
  { id: 'readiness-removal', title: 'readiness 摘除：无 key 不摘 A；SIGTERM/draining 摘 A 并由 B/C 接管；重启后重新纳入', fn: caseReadinessRemoval },
  { id: 'redis-outage-recovery', title: 'Redis 故障恢复：暂停期间 503/业务可用 → 恢复后跨实例健康与对话可用', fn: caseRedisOutageRecovery }
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
  const sessionId = fixedSessionId || uniqueId(prefix)
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

// ── 用例 4b：完整历史游标分页（跨实例正序）─────────────────────────────────

async function caseHistoryPagination() {
  await ensureUp()
  const ROUNDS = 21
  const sessionId = uniqueId('cluster3-history')
  const runs = []
  for (let i = 1; i <= ROUNDS; i += 1) {
    runs.push(await runPlainChat('a', 'cluster3-history', `history-round-${i}`, { sessionId }))
  }
  const expectedTotal = ROUNDS * 2
  const all = await waitFullHistory('b', sessionId, expectedTotal, 180000)
  const allOnC = dataOf((await fullHistoryPage('c', sessionId, { afterSeq: 0, limit: 200 })).record,
    'C 完整历史全量')
  checkGe(all.messages.length, 41, `同会话必须 >40 条真实消息（实得 ${all.messages.length}）`)
  checkEq(all.messages.length, expectedTotal, '完整历史条数（21 轮 = 42 条）')
  checkEq(allOnC.count, all.messages.length, 'C 全量条数与 B 一致')

  const tuple = (message) => `${message.seq}|${message.runId}|${message.messageId}`
  checkEq(JSON.stringify(allOnC.messages.map(tuple)), JSON.stringify(all.messages.map(tuple)),
    'B/C 全量 (seq,runId,messageId) 必须逐条同源')

  const first = all.messages[0]
  checkEq(first.seq, 1, '完整历史首条 seq')
  checkEq(first.type, 'USER', '完整历史首条类型（正序从 user 开始）')
  checkIncludes(first.text, 'history-round-1', '完整历史首条文本（首条仍在）')
  checkEq(first.runId, runs[0].runId, '完整历史首条 runId 与第 1 轮实时流同源')

  const last = all.messages[all.messages.length - 1]
  checkEq(last.seq, expectedTotal, '完整历史末条 seq')
  checkEq(last.type, 'ASSISTANT', '完整历史末条类型')
  checkEq(last.runId, runs[ROUNDS - 1].runId, '完整历史末条 runId 与最后一轮实时流同源')

  for (let i = 0; i < ROUNDS; i += 1) {
    const user = all.messages[i * 2]
    const assistant = all.messages[i * 2 + 1]
    checkEq(user.type, 'USER', `第 ${i + 1} 轮 user 类型`)
    checkEq(assistant.type, 'ASSISTANT', `第 ${i + 1} 轮 assistant 类型`)
    checkEq(user.runId, runs[i].runId, `第 ${i + 1} 轮 user runId 同源`)
    checkEq(assistant.runId, runs[i].runId, `第 ${i + 1} 轮 assistant runId 同源`)
    checkIncludes(user.text, `history-round-${i + 1}`, `第 ${i + 1} 轮 user 文本`)
    check(user.messageId && assistant.messageId && user.messageId !== assistant.messageId,
      `第 ${i + 1} 轮 messageId 必须存在且互异`)
  }
  const messageIds = all.messages.map((message) => message.messageId)
  checkEq(new Set(messageIds).size, messageIds.length, '完整历史 messageId 不得重复')
  const ascendingSeqs = all.messages.map((message) => message.seq)
  checkStrictlyIncreasing(ascendingSeqs, '完整历史全量 seq（afterSeq=0 正序）')

  // afterSeq 跨实例翻页（B/C 交替）：>40 条全量可读，游标接续不重不漏。
  const afterSeqs = []
  const afterTuples = []
  let afterCursor = 0
  let hop = 0
  while (true) {
    const key = hop % 2 === 0 ? 'b' : 'c'
    const page = dataOf((await fullHistoryPage(key, sessionId, { afterSeq: afterCursor, limit: 20 })).record,
      `${key} afterSeq=${afterCursor} 页`)
    checkEq(page.direction, 'after', `afterSeq 翻页方向（${key}）`)
    checkLe(page.messages.length, 20, 'afterSeq 页大小')
    afterSeqs.push(...page.messages.map((message) => message.seq))
    afterTuples.push(...page.messages.map(tuple))
    if (!page.hasMore) {
      checkEq(page.nextCursor, null, 'afterSeq 末页 nextCursor 必须 null')
      break
    }
    check(page.nextCursor !== null && page.nextCursor !== undefined, 'afterSeq hasMore=true 时 nextCursor 不得为空')
    check(page.nextCursor > afterCursor, `afterSeq nextCursor 必须前进（${afterCursor} → ${page.nextCursor}）`)
    afterCursor = page.nextCursor
    hop += 1
    check(hop <= 10, 'afterSeq 翻页次数异常（可能游标未前进）')
  }
  checkEq(JSON.stringify(afterSeqs), JSON.stringify(ascendingSeqs), 'afterSeq 跨实例翻页覆盖全部 seq（无重不漏）')
  checkEq(JSON.stringify(afterTuples), JSON.stringify(all.messages.map(tuple)), 'afterSeq 翻页消息元组与全量一致')

  // beforeSeq 从最新向最早翻页（C/B 交替），同样无重不漏。
  const beforeSeqs = []
  const beforeTuples = []
  let beforeCursor = null
  let backHop = 0
  while (true) {
    const key = backHop % 2 === 0 ? 'c' : 'b'
    const page = dataOf((await fullHistoryPage(key, sessionId, { beforeSeq: beforeCursor, limit: 13 })).record,
      `${key} beforeSeq=${beforeCursor === null ? '(最新)' : beforeCursor} 页`)
    checkEq(page.direction, 'before', `beforeSeq 翻页方向（${key}）`)
    checkLe(page.messages.length, 13, 'beforeSeq 页大小')
    beforeSeqs.push(...page.messages.map((message) => message.seq))
    beforeTuples.push(...page.messages.map(tuple))
    if (!page.hasMore) {
      checkEq(page.nextCursor, null, 'beforeSeq 末页 nextCursor 必须 null')
      break
    }
    check(page.nextCursor !== null && page.nextCursor !== undefined, 'beforeSeq hasMore=true 时 nextCursor 不得为空')
    check(beforeCursor === null || page.nextCursor < beforeCursor, `beforeSeq nextCursor 必须向更早推进（${beforeCursor} → ${page.nextCursor}）`)
    beforeCursor = page.nextCursor
    backHop += 1
    check(backHop <= 10, 'beforeSeq 翻页次数异常（可能游标未前进）')
  }
  for (let i = 1; i < beforeSeqs.length; i += 1) {
    if (!(beforeSeqs[i] < beforeSeqs[i - 1])) {
      throw new Error(`beforeSeq 翻页必须严格递减（${beforeSeqs[i - 1]} → ${beforeSeqs[i]}）`)
    }
  }
  checkEq(new Set(beforeSeqs).size, beforeSeqs.length, 'beforeSeq 翻页 seq 不得重复')
  checkEq(beforeSeqs.length, expectedTotal, 'beforeSeq 翻页条数')
  checkEq(beforeSeqs[beforeSeqs.length - 1], 1, 'beforeSeq 必须翻到最早一条')
  checkEq(JSON.stringify(beforeSeqs.slice().reverse()), JSON.stringify(ascendingSeqs), 'beforeSeq 逆序 = afterSeq 正序（无重不漏）')
  checkEq(JSON.stringify(beforeTuples.slice().reverse()), JSON.stringify(all.messages.map(tuple)),
    'beforeSeq 翻页消息元组与 afterSeq 全量一致')

  // beforeSeq 与 afterSeq 同传必须 400（产品契约，不靠客户端兜底）。
  const both = await fullHistoryPage('c', sessionId, { afterSeq: 10, beforeSeq: 20, limit: 1 })
  checkEq(both.record.status, 400, `beforeSeq+afterSeq 同传必须 400（实得 ${both.record.status}）`)

  // 旧 /history 仍是 40 条记忆窗口：42 条完整历史下必须只回 40 条，且不含完整历史游标字段。
  const legacy = await historyOf('c', sessionId)
  checkEq(legacy.record.status, 200, '旧 /history HTTP')
  const legacyData = dataOf(legacy.record, '旧 /history')
  checkEq(legacyData.count, legacyData.total, '旧 /history 默认全量窗口：count=total')
  checkEq(legacyData.hasMore, false, '旧 /history 默认 hasMore=false')
  check(!Object.prototype.hasOwnProperty.call(legacyData, 'nextCursor'), '旧 /history 不得混入完整历史的 nextCursor 字段')
  checkEq(legacyData.total, 40, '旧 /history 必须仍是 40 条记忆窗口（42 条完整历史下）')
  checkEq(legacyData.messages.length, 40, '旧 /history messages 长度 = 40 窗口')

  saveEvidence('history-pagination.json', {
    sessionId,
    rounds: ROUNDS,
    expectedTotal,
    fullCount: all.messages.length,
    first: { seq: first.seq, runId: first.runId, messageId: first.messageId },
    last: { seq: last.seq, runId: last.runId, messageId: last.messageId },
    afterSeqs,
    beforeSeqs,
    bothStatus: both.record.status,
    legacy: { count: legacyData.count, total: legacyData.total, hasMore: legacyData.hasMore }
  })
  caseLog(`会话 ${sessionId} 共 ${expectedTotal} 条消息；afterSeq 跨 B/C 覆盖 1..${expectedTotal}；beforeSeq 从最新翻回 1；旧 /history 仍 40 窗口；before+after 同传=400`)
}
// ── 用例 5：跨实例 seq（实时流 / 增量重挂 / 全量回放）─────────────────────

async function caseSeqCrossInstance() {
  await ensureUp()
  const sessionId = uniqueId('cluster3-seq')
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

// ── 用例 6：挂起在 A → B/C 重挂 → C 决策 ──────────────────────────────────

async function caseSuspendCrossInstance() {
  await ensureUp()
  const suffix = uniqueId('cross')
  const task = await createTask('c', 'EXPORT', `cluster3-suspend-${suffix}`)
  await selectDefs('c', task.id, ['CURRENCY'])
  const run = await createSuspendedRun('a', { prefix: 'cluster3-suspend', taskId: task.id, messageSuffix: ` ${suffix}` })
  caseLog(`挂起 runId=${run.runId} toolCallId=${run.toolCallId} taskId=${task.id} lastSeq=${run.lastSeq}`)

  const bPromise = reattachOn('b', run.runId, run.lastSeq, { timeoutMs: 120000 })
  // 先挂一个空 catch，避免 B 的请求在 confirm 之前失败时成为 unhandledRejection；
  // 下面仍会 await 原 promise 并把真实错误抛给用例。
  bPromise.catch(() => {})
  const streamC = await reattachOn('c', run.runId, 0, { timeoutMs: 120000 })
  let streamB = null
  try {
    const confirm = await confirmOn('c', { runId: run.runId, toolCallId: run.toolCallId, reason: 'cluster3 cross-instance decision' })
    checkEq(confirm.record.status, 200, 'C 侧决策 HTTP')
    check(confirm.body && confirm.body.accepted === true, `C 侧决策未被接受：${confirm.record.text.slice(0, 500)}`)
    checkEq(confirm.body.storedBy, STATE.instanceIds.c, '决策 storedBy 必须是 C 的 instanceId')
    checkIn(confirm.body.resumeOutcome, ['LEASE_HELD_ELSEWHERE', 'SCHEDULED'], 'C 侧 resumeOutcome')
    saveEvidence('confirm.json', confirm.body)

    streamB = await bPromise
    const terminalB = await streamB.waitTerminal(RUN.killTakeoverTimeoutMs)
    checkEq(terminalB.type, 'done', 'B 侧重挂终帧')
    const terminalC = await streamC.waitTerminal(RUN.killTakeoverTimeoutMs)
    checkEq(terminalC.type, 'done', 'C 侧重挂终帧')
    checkStrictlyIncreasing(streamB.seqs(), 'B 侧续跑补帧 seq')
    checkStrictlyIncreasing(streamC.seqs(), 'C 侧续跑补帧 seq')

    const full = await fullReplayToTerminal('a', run.runId, 90000)
    checkEq(frameCount(full, 'tool_start', run.toolCallId), 1, '全量回放 tool_start 恰一次')
    checkEq(frameCount(full, 'tool_result', run.toolCallId), 1, '全量回放 tool_result 恰一次')
    checkEq(frameCount(full, 'confirm_decision', run.toolCallId), 1, '全量回放 confirm_decision 恰一次')

    const detail = await runDetail('b', run.runId)
    checkEq(detail.snapshot.status, 'DONE', 'run 终态')
    checkEq(Number(detail.ledgerCounts.start_export), 1, '台账 start_export 恰一次')
    const ledger = ledgerFor(detail, run.toolCallId)
    checkEq(ledger.length, 1, '该 toolCallId 台账条目数')
    checkIn(ledger[0].executedBy, [STATE.instanceIds.a, STATE.instanceIds.c], '台账执行实例（A 的等待方或 C 的接管者）')

    const jobs = await jobsOf('c', task.id)
    checkEq(jobs.length, 1, '跨实例决策后作业数恰 1')
    const historyB = await historyStable('b', run.sessionId, suffix)
    const historyC = await historyStable('c', run.sessionId, suffix)
    checkEq(historyC.total, historyB.total, 'B/C history total')

    saveFrames('B-reattach.jsonl', streamB.frames)
    saveFrames('C-reattach.jsonl', streamC.frames)
    saveFrames('A-full.jsonl', full)
    saveEvidence('run-detail.json', { status: detail.snapshot.status, ledgerCounts: detail.ledgerCounts, ledger })
    caseLog(`决策落 C（storedBy=C，outcome=${confirm.body.resumeOutcome}）；B/C 两连接都收到 done；台账 executedBy=${ledger[0].executedBy}`)
  } finally {
    if (streamB) streamB.close()
    streamC.close()
  }
}

// ── 用例 7：跨实例幂等 ─────────────────────────────────────────────────────

async function newSuspendedTask(prefix) {
  const task = await createTask('a', 'EXPORT', `${prefix}-${uniqueId('t')}`)
  await selectDefs('a', task.id, ['CURRENCY'])
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
  const idemKey = `ai:idem:{run:${run1.runId}}`
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
  await waitFullHistory('b', target.sessionId, 2)
  await historyStable('c', target.sessionId, '三实例连通性')
  const beforeFull = await waitFullHistory('c', target.sessionId, 2)
  checkEq(beforeFull.messages[0].runId, target.runId, '销毁前完整历史 runId 与目标轮一致')

  // ① 普通完成会话：B 删 → C 验证旧 history / 完整 history / runs/current 均无残留。
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

  const fullAfter = await fullHistoryPage('c', target.sessionId, { limit: 50 })
  const fullAfterData = dataOf(fullAfter.record, 'C 侧完整历史（销毁后）')
  checkEq(fullAfterData.count, 0, 'C 侧完整历史销毁后 count 必须为 0')
  checkEq(fullAfterData.messages.length, 0, 'C 侧完整历史销毁后 messages 必须为空')

  const currentAfter = await currentRun('c', target.sessionId)
  checkEq(currentAfter.found, false, 'C 侧 runs/current 销毁后必须 found=false')

  // 另一个会话不受影响。
  const keepHistory = await historyStable('c', keep.sessionId, '三实例连通性')
  checkGe(keepHistory.total, 2, '销毁目标会话后，另一个会话的 history 仍完整')
  const keepFull = await waitFullHistory('c', keep.sessionId, 2)
  checkEq(keepFull.messages[0].runId, keep.runId, '另一个会话的完整历史 runId 不变')
  // 业务 task 不被会话销毁波及。
  const taskAfter = await getTask('c', task.id)
  checkEq(taskAfter.id, task.id, '会话销毁后业务 task 仍可读取')

  saveEvidence('purge-complete.json', {
    target: { sessionId: target.sessionId, runId: target.runId },
    purge: del.body,
    legacyAfter: { total: legacyAfterData.total, messages: legacyAfterData.messages.length },
    fullAfter: { count: fullAfterData.count },
    runsCurrent: currentAfter.found,
    keep: { sessionId: keep.sessionId, total: keepHistory.total },
    taskId: task.id
  })

  // ② 在途慢流：SIGSTOP 冻结 A 的取消处理，B 删除必须 deferred（不赌 watchdog 时序）。
  const activeSession = uniqueId('cluster3-purge-active')
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
  const automaticPurgeRecovery = async () => {
    const pendingKey = `ai:purge:pending:${activeSession}`
    const deadline = Date.now() + 90000
    while (redisExists(pendingKey)) {
      if (Date.now() >= deadline) {
        throw new Error(`自动销毁重试 90s 内未清掉 ${pendingKey}；不得以手动 DELETE 掩盖`)
      }
      await sleep(500)
    }
  }
  await automaticPurgeRecovery()
  check(!redisExists(`ai:purge:pending:${activeSession}`), '自动销毁重试必须移除 durable pending')

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
  const revivedFull = await fullHistoryPage('b', activeSession, { limit: 50 })
  const revivedFullData = dataOf(revivedFull.record, '在途会话销毁后的完整历史')
  checkEq(revivedFullData.count, 0, '在途会话销毁后完整历史不得复活')
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
    revived: { legacyMessages: revivedLegacyData.messages.length, fullCount: revivedFullData.count, runsCurrent: revivedCurrent.found },
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
  return { taskId: task.id, sha256: sha256(template.body), bytes: template.body.length }
}

async function redownloadAfterKill(label, fixture) {
  const record = await downloadFile(label, fixture.taskId, 'CURRENCY')
  checkEq(record.status, 200, `A 死亡后共享文件下载（${label}）HTTP`)
  checkEq(sha256(record.body), fixture.sha256, `A 死亡后共享文件下载（${label}）sha256`)
}

async function caseSuspendKillA() {
  await ensureUp()
  const fixture = await createUploadedFixture('cluster3-suspend-kill')
  const taskId = fixture.taskId
  const run = await createSuspendedRun('a', { prefix: 'cluster3-suspend-kill', taskId, messageSuffix: ` ${uniqueId('sk')}` })
  caseLog(`挂起在 A：runId=${run.runId} toolCallId=${run.toolCallId} taskId=${taskId}（独立文件 fixture）`)
  const killedAt = Date.now()
  const kill = await killInstance('a')
  saveEvidence('kill-a.json', kill)

  const current = await currentRun('b', run.sessionId)
  check(current.found === true, 'B 侧 /runs/current 未找到该会话的挂起轮')
  checkEq(current.runId, run.runId, 'B 侧 /runs/current 指向的 runId')
  checkEq(current.snapshotStatus, 'SUSPENDED', 'B 侧快照状态')
  check(current.awaitingExternal === true, 'B 侧 awaitingExternal 必须为 true')
  check((current.pendingEntries || []).some((entry) => entry.toolCallId === run.toolCallId), 'B 侧 pendingEntries 未列出待决条目')

  const confirm = await confirmOn('b', { runId: run.runId, toolCallId: run.toolCallId, reason: 'cluster3 suspend-kill takeover' })
  checkEq(confirm.record.status, 200, `B 侧决策 HTTP：${confirm.record.text.slice(0, 400)}`)
  check(confirm.body && confirm.body.accepted === true, `B 侧决策未被接受：${confirm.record.text.slice(0, 400)}`)
  checkIn(confirm.body.resumeOutcome, ['LEASE_HELD_ELSEWHERE', 'SCHEDULED'], 'B 侧 resumeOutcome')
  checkEq(confirm.body.storedBy, STATE.instanceIds.b, '决策 storedBy 必须是 B')

  const streamB = await reattachOn('b', run.runId, run.lastSeq, { timeoutMs: RUN.killTakeoverTimeoutMs })
  try {
    const terminal = await streamB.waitTerminal(RUN.killTakeoverTimeoutMs)
    checkEq(terminal.type, 'done', 'B 侧接管终帧')
    checkStrictlyIncreasing(streamB.seqs(), 'B 侧接管补帧 seq')
    const takeoverMs = Date.now() - killedAt

    const full = await fullReplayToTerminal('c', run.runId, 90000)
    checkEq(terminalType(full), 'done', 'C 侧全量回放终帧')
    checkEq(frameCount(full, 'tool_start', run.toolCallId), 1, '接管后 tool_start 恰一次')
    checkEq(frameCount(full, 'tool_result', run.toolCallId), 1, '接管后 tool_result 恰一次')

    const detail = await runDetail('c', run.runId)
    checkEq(detail.snapshot.status, 'DONE', 'run 终态')
    checkEq(Number(detail.ledgerCounts.start_export), 1, '共享库台账 start_export 恰一次')
    const ledger = ledgerFor(detail, run.toolCallId)
    checkEq(ledger.length, 1, '台账条目数')
    checkEq(ledger[0].executedBy, STATE.instanceIds.b, '台账 executedBy 必须是接管实例 B（共享库形态）')
    checkEq(ledger[0].outcome, 'ok', '台账 outcome 必须是成功（ok）')

    const jobs = await jobsOf('c', taskId)
    checkEq(jobs.length, 1, '接管执行后作业数恰 1')
    const history = await historyStable('c', run.sessionId, 'start_export')
    checkGe(history.total, 2, 'A 死亡后 C 侧 history 至少 user + assistant')

    await redownloadAfterKill('b', fixture)
    await redownloadAfterKill('c', fixture)

    saveFrames('B-takeover.jsonl', streamB.frames)
    saveFrames('C-full.jsonl', full)
    saveEvidence('takeover.json', {
      kill,
      resumeOutcome: confirm.body.resumeOutcome,
      takeoverMs,
      snapshotStatus: detail.snapshot.status,
      ledgerCounts: detail.ledgerCounts,
      ledger,
      jobs: jobs.length,
      historyTotal: history.total,
      fixture
    })
    caseLog(`kill A → B 接管：outcome=${confirm.body.resumeOutcome}，${takeoverMs}ms 收到 done；台账 executedBy=B；A 死后 B/C 仍能下载共享文件`)
  } finally {
    streamB.close()
  }
}

// ── 用例 11：流式中 kill A → 僵尸扫描接管 ─────────────────────────────────

async function forceZombieResume(runId) {
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
  const sessionId = uniqueId('cluster3-stream-kill')
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
  const readinessChat = await httpRequest(directUrl('a', '/api/ai/chat'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream, application/json' },
    body: Buffer.from(JSON.stringify({ sessionId: uniqueId('cluster3-readiness'), message: 'scenario=PLAIN readiness' }), 'utf8'),
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
  const slowSession = uniqueId('cluster3-draining')
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
    const takeover = await chatOn(null, { sessionId: uniqueId('cluster3-draining-takeover'), message: 'scenario=PLAIN draining-takeover' }, { viaProxy: true })
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
      body: Buffer.from(JSON.stringify({ sessionId: uniqueId('cluster3-redis-outage'), message: 'scenario=PLAIN redis-outage' }), 'utf8'),
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
        const stream = await chatOn(null, { sessionId: uniqueId('cluster3-redis-recovery'), message: 'scenario=PLAIN redis-recovery' }, { viaProxy: true })
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

async function mainRun() {
  configureRun()
  initArtifacts()
  saveEnv()
  logLine(`三实例集群验证开始：artifacts=${RUN.artifacts} runDir=${RUN.runDir}`)
  let finalExit = 0
  try {
    await preflight()
    for (const def of CASES) {
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
    '@GetMapping("/history/{sessionId}/messages")',
    '@RequestParam(name = "afterSeq", required = false) Long afterSeq',
    'body.put("nextCursor"',
    'body.put("hasMore"',
    '@GetMapping("/runs/current")',
    'body.put("snapshotStatus"',
    'body.put("awaitingExternal"',
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

/** 三条复审契约的自证（防止后续编辑把 SIGSTOP/deferred、分档码、>40 分页、二元绑定删回去）。 */
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
    'const ROUNDS = 21',
    'beforeSeq: beforeCursor',
    '必须 400',
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

async function selfCheckMain() {
  console.log('cluster3-probe --self-check（不启动 Java/Docker、不连接任何端口）\n')
  selfCheck('用例注册覆盖用户清单（16 条）', selfTestCaseRegistry)
  selfCheck('结果状态只有 PASS/FAIL（无 SKIP）', () => RESULT_STATUSES.join(',') === 'PASS,FAIL' ? 'PASS/FAIL' : (() => { throw new Error(RESULT_STATUSES.join(',')) })())
  selfCheck('端口段 231xx 且禁用端口不出现', selfTestPortHygiene)
  selfCheck('静态 import 全部为 node: 内置模块', selfTestStaticImports)
  selfCheck('seq 断言（递增/乱序/重复）', selfTestSeqAndAssertions)
  selfCheck('HAProxy stats CSV 解析', selfTestStatsCsv)
  selfCheck('产品端点/字段静态契约', selfTestProductContract)
  selfCheck('桩路由表契约（TC16-EXPORT）', selfTestStubContract)
  selfCheck('实例启动参数（h2tcp / LOCK_TIMEOUT / trusted-proxies）', selfTestInstanceArgs)
  selfCheck('复审契约（SIGSTOP/deferred / 分档码 / >40 分页 / providerId 绑定）', selfTestReviewContracts)
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
       --ports 'a=23181,b=23182,c=23183,h2=23190,redis=23179'`)
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
