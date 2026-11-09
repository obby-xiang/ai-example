#!/usr/bin/env node

import {execFileSync} from 'node:child_process'
import {createHash, randomUUID} from 'node:crypto'
import {lstat, mkdir, open, readFile, readdir, realpath, rm, stat, writeFile} from 'node:fs/promises'
import {createRequire} from 'node:module'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {setTimeout as sleep} from 'node:timers/promises'

const repoRoot = fileURLToPath(new URL('../..', import.meta.url))
const frontendRequire = createRequire(new URL('../../frontend/package.json', import.meta.url))
const scenarios = new Set(['C-147', 'C-148', 'C-151', 'C-152'])
const localHosts = new Set(['localhost', '127.0.0.1', '::1', '[::1]'])
const terminalParts = new Set(['succeeded', 'failed', 'rejected', 'blocked', 'expired', 'unknown', 'cancelled', 'skipped', 'timeout'])
const terminalRuns = new Set(['DONE', 'FAILED', 'CANCELLED'])
const duplicateCode = 'DUPLICATE_TOOL_CALL_ID'
const maxLogBytes = 1024 * 1024
const maxXlsxBytes = 16 * 1024 * 1024
const maxZipEntries = 256
const maxXlsxEntryChars = 8 * 1024 * 1024
const maxXlsxTotalChars = 32 * 1024 * 1024
const maxSharedStrings = 10_000
const maxXlsxSheets = 32
const maxRowsPerSheet = 10_000
const maxTotalRows = 50_000
const maxCellsPerRow = 256
const maxTotalCells = 250_000

class ScenarioNotRun extends Error {
  constructor(code) {
    super(code)
    this.code = code
  }
}

class VerificationFailure extends Error {
  constructor(code, httpStatus = null, businessCode = null) {
    super(code)
    this.code = code
    this.httpStatus = httpStatus
    this.businessCode = businessCode
  }
}

function isRecord(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`
  if (isRecord(value)) return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`
  return JSON.stringify(value) ?? 'null'
}

function sha256(value) {
  const input = Buffer.isBuffer(value) ? value : typeof value === 'string' ? value : canonicalJson(value)
  return createHash('sha256').update(input).digest('hex')
}

function textEvidence(value) {
  return {length: Array.from(String(value ?? '')).length, sha256: sha256(String(value ?? ''))}
}

function safeToken(value, fallback = null) {
  return typeof value === 'string' && /^[A-Za-z][A-Za-z0-9_.:-]{0,127}$/.test(value) ? value : fallback
}

function safeError(error, fallback = 'UNEXPECTED_ERROR') {
  return {
    errorName: safeToken(error?.name, 'Error'),
    code: safeToken(error?.code, fallback),
    httpStatus: Number.isSafeInteger(error?.httpStatus) ? error.httpStatus : null,
    businessCode: safeToken(error?.businessCode)
  }
}

function businessCode(body) {
  return safeToken(body?.code ?? body?.data?.code)
}

function dataOf(response) {
  return response.body?.data ?? response.body
}

function isInside(parent, candidate) {
  const relative = path.relative(path.resolve(parent), path.resolve(candidate))
  return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative))
}

function outsidePath(name, optional = false) {
  const raw = process.env[name]?.trim() ?? ''
  if (!raw && optional) return null
  if (!path.isAbsolute(raw)) throw new Error(`${name} must be an absolute path outside the repository`)
  const resolved = path.resolve(raw)
  if (isInside(repoRoot, resolved)) throw new Error(`${name} must point outside the repository`)
  return resolved
}

function integerEnv(name, fallback, minimum, maximum) {
  const value = Number(process.env[name] ?? fallback)
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) throw new Error(`${name} must be in ${minimum}..${maximum}`)
  return value
}

function httpUrl(name, loopbackOnly = false) {
  let parsed
  try {
    parsed = new URL(process.env[name]?.trim() ?? '')
  } catch {
    throw new Error(`${name} must be an absolute HTTP(S) URL`)
  }
  if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new Error(`${name} must not contain credentials, query or fragment`)
  }
  if (loopbackOnly && !localHosts.has(parsed.hostname)) throw new Error(`${name} must be loopback`)
  if (!localHosts.has(parsed.hostname) && process.env.EVAL_CONFIRM_REMOTE !== 'true') throw new Error(`EVAL_CONFIRM_REMOTE=true is required for ${name}`)
  return parsed.toString().replace(/\/$/, '')
}

function loadConfig() {
  const scenario = (process.env.SCENARIO ?? '').trim()
  if (!scenarios.has(scenario)) throw new Error(`SCENARIO must select exactly one of ${[...scenarios].join(',')}`)
  if ((process.env.SESSIONS ?? '3') !== '3') throw new Error('SESSIONS=3 is mandatory; use one process per scenario')
  for (const gate of ['EVAL_ISOLATED_ENV', 'EVAL_ALLOW_WRITES', 'EVAL_STUB_ONLY']) {
    if (process.env[gate] !== 'true') throw new Error(`${gate}=true is required`)
  }
  if (!process.env.TENANT_ID?.trim() || !process.env.USER_ID?.trim() || !process.env.EXECUTOR_IDENTITY?.trim()) {
    throw new Error('TENANT_ID, USER_ID and EXECUTOR_IDENTITY are required')
  }
  return {
    scenario,
    appUrl: httpUrl('APP_URL'),
    baseUrl: httpUrl('BASE_URL'),
    cdpUrl: httpUrl('CDP_URL', true),
    tenantId: process.env.TENANT_ID.trim(),
    userId: process.env.USER_ID.trim(),
    executorIdentity: process.env.EXECUTOR_IDENTITY.trim(),
    outDir: outsidePath('OUT_DIR'),
    backendLogFile: outsidePath('BACKEND_LOG_FILE'),
    timeoutMs: integerEnv('TIMEOUT_MS', 180_000, 10_000, 600_000),
    pollMs: integerEnv('POLL_MS', 100, 25, 2_000)
  }
}

async function nearestExistingAncestor(directory) {
  let ancestor = directory
  while (true) {
    try {
      await lstat(ancestor)
      return ancestor
    } catch (error) {
      if (error?.code !== 'ENOENT') throw error
    }
    const parent = path.dirname(ancestor)
    if (parent === ancestor) throw new Error('OUT_DIR has no existing ancestor')
    ancestor = parent
  }
}

async function prepareEvidenceDirectory(config) {
  const resolvedRepo = await realpath(repoRoot)
  const ancestor = await nearestExistingAncestor(config.outDir)
  const resolvedAncestor = await realpath(ancestor)
  if (isInside(resolvedRepo, resolvedAncestor)) throw new Error('OUT_DIR ancestor resolves inside the repository')
  const target = path.resolve(resolvedAncestor, path.relative(ancestor, config.outDir))
  const directory = path.join(target, 'ai-visibility-action', config.scenario,
    `${new Date().toISOString().replace(/[:.]/g, '-')}-${randomUUID()}`)
  await mkdir(directory, {recursive: true})
  const resolved = await realpath(directory)
  if (isInside(resolvedRepo, resolved)) throw new Error('evidence directory resolves inside the repository')
  return resolved
}

function codeHead() {
  if (process.env.CODE_HEAD?.trim()) return process.env.CODE_HEAD.trim()
  try {
    return execFileSync('git', ['rev-parse', 'HEAD'], {cwd: repoRoot, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore']}).trim()
  } catch {
    return 'UNKNOWN'
  }
}

class Checks {
  constructor() {
    this.items = []
  }

  add(id, pass, expected, actual) {
    this.items.push({id, pass: pass === true, expected, actual})
  }

  require(id, pass, expected, actual) {
    this.add(id, pass, expected, actual)
    if (pass !== true) throw new VerificationFailure(id)
  }

  get failures() {
    return this.items.filter((item) => !item.pass)
  }
}

class Evidence {
  constructor(directory) {
    this.directory = directory
  }

  async json(name, value) {
    await writeFile(path.join(this.directory, name), `${JSON.stringify(value, null, 2)}\n`, 'utf8')
  }
}

class LogProbe {
  constructor(file) {
    this.file = file
    this.offset = 0
  }

  async begin() {
    this.offset = (await stat(this.file)).size
  }

  async capture() {
    const size = (await stat(this.file)).size
    const length = size - this.offset
    if (!Number.isSafeInteger(length) || length < 0) throw new VerificationFailure('LOG_FILE_SHRANK')
    if (length > maxLogBytes) throw new ScenarioNotRun('LOG_WINDOW_EXCEEDS_1_MIB')
    const handle = await open(this.file, 'r')
    let text
    try {
      const buffer = Buffer.alloc(length)
      await handle.read(buffer, 0, length, this.offset)
      text = buffer.toString('utf8')
    } finally {
      await handle.close()
    }
    return {
      status: 'COLLECTED',
      bytes: length,
      sha256: sha256(text),
      warningCount: (text.match(/\bWARN\b/g) ?? []).length,
      errorCount: (text.match(/\bERROR\b/g) ?? []).length,
      duplicateCount: (text.match(/duplicate|DUPLICATE/g) ?? []).length,
      rawLinesRetained: false
    }
  }
}

class ApiClient {
  constructor(config) {
    this.config = config
  }

  async request(label, urlPath, options = {}) {
    let response
    try {
      response = await fetch(`${this.config.baseUrl}${urlPath}`, {
        method: options.method ?? 'GET',
        headers: {
          'X-Tenant-Id': this.config.tenantId,
          'X-User-Id': this.config.userId,
          ...(options.body === undefined ? {} : {'Content-Type': 'application/json'})
        },
        body: options.body,
        redirect: 'error',
        signal: AbortSignal.timeout(45_000)
      })
    } catch (error) {
      throw new VerificationFailure(label, null, safeToken(error?.name, 'Error'))
    }
    const text = await response.text()
    let body = null
    try {
      body = text === '' ? null : JSON.parse(text)
    } catch {
      body = null
    }
    return {label, status: response.status, body}
  }

  async expect(label, urlPath, options = {}, statuses = [200]) {
    const response = await this.request(label, urlPath, options)
    if (!statuses.includes(response.status)) throw new VerificationFailure(label, response.status, businessCode(response.body))
    return dataOf(response)
  }

  async createTask(title) {
    return await this.expect('task.create', '/api/tasks', {method: 'POST', body: JSON.stringify({type: 'EXPORT', title})}, [201])
  }

  async selectDefs(taskId) {
    return await this.expect('task.select-defs', `/api/tasks/${taskId}/select-defs`, {
      method: 'POST', body: JSON.stringify({defCodes: ['CURRENCY']})
    })
  }

  async setCondition(taskId) {
    return await this.expect('task.condition', `/api/tasks/${taskId}/items/CURRENCY/condition`, {
      method: 'PUT', body: JSON.stringify({condition: {}})
    })
  }

  async goToStep(taskId, step) {
    return await this.expect('task.step', `/api/tasks/${taskId}/step`, {method: 'PUT', body: JSON.stringify({step})})
  }

  async task(taskId) {
    return await this.request('task.get', `/api/tasks/${taskId}`)
  }

  async jobs(taskId) {
    return await this.expect('task.jobs', `/api/tasks/${taskId}/jobs`)
  }

  async files(taskId) {
    return await this.expect('task.files', `/api/tasks/${taskId}/files`)
  }

  async history(sessionId) {
    const response = await this.request('ai.history', `/api/ai/history/${encodeURIComponent(sessionId)}?latest=100`)
    if (response.status !== 200) throw new VerificationFailure('ai.history', response.status, businessCode(response.body))
    const data = dataOf(response)
    if (!Array.isArray(data?.messages) || data.hasMore === true || data.truncated === true) throw new VerificationFailure('COMPLETE_HISTORY_REQUIRED')
    if (data.messages.length > 100) throw new VerificationFailure('HISTORY_EVIDENCE_LIMIT_EXCEEDED')
    for (const message of data.messages) {
      if (Object.keys(message.toolCallStates ?? {}).length > 64 || (message.toolResponses ?? []).length > 64) {
        throw new VerificationFailure('HISTORY_TOOL_EVIDENCE_LIMIT_EXCEEDED')
      }
    }
    return data
  }

  async run(runId) {
    const data = await this.expect('ai.run', `/api/ai/runs/${encodeURIComponent(runId)}`)
    if (data?.exists !== true) throw new VerificationFailure('RUN_NOT_FOUND')
    if (!Array.isArray(data.pending) || data.pending.length > 64) throw new VerificationFailure('RUN_PENDING_EVIDENCE_LIMIT_EXCEEDED')
    if (!isRecord(data.ledgerCounts) || Object.keys(data.ledgerCounts).length > 64) {
      throw new VerificationFailure('RUN_LEDGER_COUNTS_LIMIT_EXCEEDED')
    }
    return data
  }

  async download(taskId) {
    const response = await fetch(`${this.config.baseUrl}/api/tasks/${taskId}/files/download?codes=CURRENCY`, {
      headers: {'X-Tenant-Id': this.config.tenantId, 'X-User-Id': this.config.userId},
      redirect: 'error',
      signal: AbortSignal.timeout(45_000)
    })
    if (!response.ok) throw new VerificationFailure('task.download', response.status)
    const declaredBytes = Number(response.headers.get('content-length') ?? 0)
    if (declaredBytes > maxXlsxBytes) throw new VerificationFailure('XLSX_BYTES_LIMIT_EXCEEDED')
    const bytes = Buffer.from(await response.arrayBuffer())
    if (bytes.length > maxXlsxBytes) throw new VerificationFailure('XLSX_BYTES_LIMIT_EXCEEDED')
    return bytes
  }
}

function taskSummary(task, jobs, files = []) {
  return {
    id: Number(task?.id),
    currentStep: task?.currentStep ?? null,
    status: task?.status ?? null,
    items: (task?.items ?? []).map((item) => ({defCode: item.defCode ?? null, condition: item.conditionJson == null ? null : textEvidence(String(item.conditionJson))})),
    jobs: (jobs ?? []).map((job) => ({id: Number(job.id), jobType: job.jobType ?? null, status: job.status ?? null})),
    files: files.map((file) => ({defCode: file.defCode ?? null, fileType: file.fileType ?? null, size: Number(file.size ?? file.fileSize ?? 0)}))
  }
}

async function businessSnapshot(api, fixtureTaskId = null) {
  const allTasks = []
  let total = null
  for (let page = 0; page < 100; page += 1) {
    const data = await api.expect('tasks.page', `/api/tasks?page=${page}&size=100`)
    if (!Array.isArray(data?.content)) throw new VerificationFailure('TASK_PAGE_INVALID')
    if (total === null) total = Number(data.totalElements)
    if (total !== Number(data.totalElements)) throw new VerificationFailure('TASK_TOTAL_CHANGED')
    for (const entry of data.content) {
      const task = entry.task ?? entry
      const id = Number(task.id)
      if (!Number.isSafeInteger(id) || id <= 0) throw new VerificationFailure('TASK_ID_INVALID')
      const jobs = await api.jobs(id)
      const files = id === fixtureTaskId ? await api.files(id) : []
      if ((task.items ?? []).length > 512 || jobs.length > 512 || files.length > 512) {
        throw new VerificationFailure('BUSINESS_EVIDENCE_ARRAY_LIMIT_EXCEEDED')
      }
      allTasks.push(taskSummary(task, jobs, files))
    }
    if (allTasks.length >= total || data.content.length === 0) break
    if (page === 99) throw new ScenarioNotRun('BUSINESS_PAGE_LIMIT_EXCEEDED')
  }
  if (total === null || allTasks.length !== total) throw new VerificationFailure('BUSINESS_SNAPSHOT_INCOMPLETE')
  allTasks.sort((left, right) => left.id - right.id)
  return {
    complete: true,
    total,
    fingerprint: sha256(allTasks),
    unrelatedFingerprint: sha256(allTasks.filter((task) => task.id !== fixtureTaskId)),
    relevant: allTasks.find((task) => task.id === fixtureTaskId) ?? null
  }
}

function historySummary(history) {
  return {
    messageCount: history.messages.length,
    messages: history.messages.map((message) => ({
      type: String(message.type ?? message.role ?? '').toUpperCase(),
      runId: message.runId ?? null,
      text: textEvidence(message.text ?? message.content ?? ''),
      toolCallStates: Object.fromEntries(Object.entries(message.toolCallStates ?? {}).map(([id, state]) => [id, {
        status: state?.status ?? null,
        outcome: state?.outcome ?? null,
        code: state?.code ?? null
      }])),
      toolResponses: (message.toolResponses ?? []).map((response) => ({id: response.id ?? null, name: response.name ?? null}))
    }))
  }
}

function historyOccurrences(history, toolCallId) {
  const states = []
  const responses = []
  for (const message of history.messages) {
    if (message.toolCallStates?.[toolCallId]) states.push({
      status: message.toolCallStates[toolCallId].status ?? null,
      outcome: message.toolCallStates[toolCallId].outcome ?? null
    })
    for (const response of message.toolResponses ?? []) if (response.id === toolCallId) responses.push(response.name ?? null)
  }
  return {states, responses}
}

function runSummary(run) {
  return {
    exists: run.exists === true,
    status: run.snapshot?.status ?? null,
    instanceId: safeToken(run.instanceId),
    pending: (run.pending ?? []).map((entry) => ({
      toolCallId: entry.toolCallId ?? null,
      name: entry.name ?? null,
      status: entry.status ?? null,
      outcome: entry.outcome ?? null,
      executed: entry.executed === true,
      expiresAt: Number(entry.expiresAt ?? 0) || null,
      resolvedAtMs: Number(entry.resolvedAtMs ?? 0) || null
    })),
    ledgerCounts: isRecord(run.ledgerCounts) ? run.ledgerCounts : null
  }
}

class CdpPage {
  constructor(config) {
    this.config = config
    this.nextId = 0
    this.waiters = new Map()
    this.targetId = null
    this.occluderId = null
    this.ws = null
    this.requests = []
    this.cdpErrors = []
    this.cdpErrorCount = 0
  }

  recordCdpError(code) {
    this.cdpErrorCount += 1
    if (this.cdpErrors.length < 64) this.cdpErrors.push({code})
  }

  networkFailureEvidence() {
    const failures = this.requests.filter((record) => record.failed === true
      || (Number.isSafeInteger(record.responseStatus) && record.responseStatus >= 400))
    return {
      count: failures.length,
      tail: failures.slice(-16).map((record) => ({
        method: record.method,
        path: record.path,
        responseStatus: record.responseStatus,
        errorName: record.errorName
      })),
      truncated: failures.length > 16
    }
  }

  async boundedFetch(url, options = {}) {
    try {
      return await fetch(url, {...options, signal: AbortSignal.timeout(30_000)})
    } catch (error) {
      throw new VerificationFailure('CDP_HTTP_FAILED', null, safeToken(error?.name, 'Error'))
    }
  }

  async open(urlPath, query = '') {
    const url = new URL(this.config.appUrl)
    url.pathname = urlPath
    url.search = query
    const response = await this.boundedFetch(`${this.config.cdpUrl}/json/new?${encodeURIComponent(url.toString())}`, {method: 'PUT'})
    const target = await response.json().catch(() => null)
    if (!response.ok || !target?.id || !target?.webSocketDebuggerUrl) throw new ScenarioNotRun('CDP_TARGET_CREATE_FAILED')
    this.targetId = target.id
    this.ws = new WebSocket(target.webSocketDebuggerUrl)
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new VerificationFailure('CDP_OPEN_TIMEOUT')), 30_000)
      this.ws.onopen = () => {
        clearTimeout(timer)
        resolve()
      }
      this.ws.onerror = () => {
        clearTimeout(timer)
        reject(new ScenarioNotRun('CDP_WEBSOCKET_UNAVAILABLE'))
      }
    })
    this.ws.onmessage = (event) => this.onMessage(event)
    await this.send('Page.enable')
    await this.send('Runtime.enable')
    await this.send('Network.enable')
    await this.send('Emulation.setDeviceMetricsOverride', {width: 1280, height: 720, deviceScaleFactor: 1, mobile: false})
    await this.send('Page.navigate', {url: url.toString()})
    await this.waitFor(`(() => {
      const app = document.getElementById('app')
      const pinia = app && app.__vue_app__ && app.__vue_app__.config.globalProperties.$pinia
      return !!(pinia && pinia._s.get('ai') && pinia._s.get('workspace'))
    })()`, 'APPLICATION_STORE_NOT_READY')
    await this.evaluate(`document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai').expanded = true`, false)
  }

  onMessage(event) {
    let message
    try {
      message = JSON.parse(event.data)
    } catch {
      this.recordCdpError('CDP_MESSAGE_INVALID')
      return
    }
    if (message.id && this.waiters.has(message.id)) {
      const waiter = this.waiters.get(message.id)
      this.waiters.delete(message.id)
      clearTimeout(waiter.timer)
      if (message.error) waiter.reject(new VerificationFailure('CDP_METHOD_ERROR'))
      else waiter.resolve(message.result)
      return
    }
    if (message.method === 'Network.requestWillBeSent') this.onRequest(message.params)
    if (message.method === 'Network.responseReceived') this.onResponse(message.params)
    if (message.method === 'Network.loadingFinished') this.onFinished(message.params)
    if (message.method === 'Network.loadingFailed') this.onLoadingFailed(message.params)
  }

  onRequest(params) {
    try {
      const url = new URL(params.request.url)
      if (!url.pathname.startsWith('/api/')) return
      const record = {requestId: params.requestId, method: params.request.method, path: url.pathname,
        postData: params.request.postData ?? null, responseStatus: null, finished: false, failed: false, errorName: null}
      if (this.requests.length < 4096) this.requests.push(record)
      else this.recordCdpError('NETWORK_LIMIT_EXCEEDED')
    } catch {
      this.recordCdpError('NETWORK_OBSERVATION_FAILED')
    }
  }

  onResponse(params) {
    const record = this.requests.find((entry) => entry.requestId === params.requestId)
    if (record) record.responseStatus = params.response.status
  }

  onFinished(params) {
    const record = this.requests.find((entry) => entry.requestId === params.requestId)
    if (record) record.finished = true
  }

  onLoadingFailed(params) {
    const record = this.requests.find((entry) => entry.requestId === params.requestId)
    if (record) {
      record.finished = true
      record.failed = true
      record.errorName = safeToken(params.errorText, 'NETWORK_LOADING_FAILED')
    }
  }

  send(method, params = {}) {
    const id = ++this.nextId
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.waiters.delete(id)
        reject(new VerificationFailure('CDP_METHOD_TIMEOUT'))
      }, 45_000)
      this.waiters.set(id, {resolve, reject, timer})
      try {
        this.ws.send(JSON.stringify({id, method, params}))
      } catch (error) {
        clearTimeout(timer)
        this.waiters.delete(id)
        reject(new VerificationFailure('CDP_SEND_FAILED', null, safeToken(error?.name, 'Error')))
      }
    })
  }

  async evaluate(expression, awaitPromise = true) {
    const result = await this.send('Runtime.evaluate', {expression, returnByValue: true, awaitPromise, userGesture: true})
    if (result.exceptionDetails) throw new VerificationFailure('BROWSER_EVALUATION_FAILED')
    return result.result.value
  }

  async waitFor(expression, code, timeoutMs = this.config.timeoutMs) {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      const value = await this.evaluate(expression, false)
      if (value) return value
      await sleep(this.config.pollMs)
    }
    throw new VerificationFailure(code)
  }

  async click(selector) {
    const center = await this.evaluate(`(() => {
      const element = document.querySelector(${JSON.stringify(selector)})
      if (!element) return null
      element.scrollIntoView({block: 'center', inline: 'center'})
      const rect = element.getBoundingClientRect()
      return {x: rect.x + rect.width / 2, y: rect.y + rect.height / 2}
    })()`, false)
    if (!center) throw new VerificationFailure('CLICK_TARGET_MISSING')
    await this.send('Input.dispatchMouseEvent', {type: 'mousePressed', ...center, button: 'left', buttons: 1, clickCount: 1})
    await this.send('Input.dispatchMouseEvent', {type: 'mouseReleased', ...center, button: 'left', buttons: 0, clickCount: 1})
  }

  async newChat(trackedSessions) {
    const before = await this.evaluate(`document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai').sessionId`, false)
    await this.click('[data-testid="new-chat"]')
    const dialog = await this.evaluate(`document.querySelector('[data-testid="new-chat-dialog"]')?.open === true`, false)
    if (dialog) await this.click('[data-testid="new-chat-dialog"] button[value="confirm"]')
    const sessionId = await this.waitFor(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      return ai.sessionId && ai.sessionId !== ${JSON.stringify(before)} && ai.messages.length === 0 && !ai.loading ? ai.sessionId : null
    })()`, 'NEW_SESSION_NOT_READY')
    trackedSessions.add(sessionId)
    return sessionId
  }

  async navigate(urlPath, query, expected) {
    const url = new URL(this.config.appUrl)
    url.pathname = urlPath
    url.search = query
    await this.send('Page.navigate', {url: url.toString()})
    await this.waitFor(`(() => {
      const app = document.getElementById('app')
      const pinia = app && app.__vue_app__ && app.__vue_app__.config.globalProperties.$pinia
      const ai = pinia && pinia._s.get('ai')
      const workspace = pinia && pinia._s.get('workspace')
      return !!(ai && ai.historyLoaded && workspace && (${expected}))
    })()`, 'FIXTURE_PAGE_NOT_READY')
    await this.evaluate(`document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai').expanded = true`, false)
  }

  async hide() {
    if (!this.occluderId) {
      const response = await this.boundedFetch(`${this.config.cdpUrl}/json/new?about:blank`, {method: 'PUT'})
      const target = await response.json().catch(() => null)
      if (!response.ok || !target?.id) throw new ScenarioNotRun('CDP_OCCLUDER_CREATE_FAILED')
      this.occluderId = target.id
    }
    const response = await this.boundedFetch(`${this.config.cdpUrl}/json/activate/${this.occluderId}`)
    if (!response.ok) throw new ScenarioNotRun('CDP_BACKGROUND_ACTIVATE_FAILED')
    try {
      await this.waitFor(`document.hidden === true && document.visibilityState === 'hidden'`, 'REAL_BACKGROUND_NOT_OBSERVED', 5_000)
    } catch (error) {
      throw new ScenarioNotRun('REAL_BACKGROUND_VISIBILITY_UNAVAILABLE')
    }
  }

  async show() {
    const response = await this.boundedFetch(`${this.config.cdpUrl}/json/activate/${this.targetId}`)
    if (!response.ok) throw new VerificationFailure('CDP_FOREGROUND_ACTIVATE_FAILED')
    await this.waitFor(`document.hidden === false && document.visibilityState === 'visible'`, 'REAL_FOREGROUND_NOT_OBSERVED', 5_000)
  }

  async sendPrompt(prompt) {
    await this.evaluate(`document.querySelector('[data-testid="composer-input"]').focus()`, false)
    await this.send('Input.insertText', {text: prompt})
    await this.click('[data-testid="send-btn"]')
  }

  async snapshot() {
    const snapshot = await this.evaluate(`(() => {
      const pinia = document.getElementById('app').__vue_app__.config.globalProperties.$pinia
      const ai = pinia._s.get('ai')
      const workspace = pinia._s.get('workspace')
      return {
        hidden: document.hidden,
        visibilityState: document.visibilityState,
        sessionId: ai.sessionId,
        activeRunId: ai.activeRunId,
        loading: ai.loading === true,
        historyLoaded: ai.historyLoaded === true,
        messages: ai.messages.map((message) => ({
          id: message.id,
          runId: message.runId ?? null,
          done: message.done === true,
          parts: message.parts.map((part) => part.kind === 'tool' ? {
            kind: part.kind,
            toolCallId: part.toolCallId,
            name: part.name,
            status: part.status,
            requiresAction: part.requiresAction === true,
            code: part.code ?? null,
            result: part.result ?? ''
          } : {kind: part.kind})
        })),
        workspace: {
          pageId: workspace.pageId,
          taskId: workspace.taskId,
          step: workspace.step,
          selectedDefs: [...(workspace.selectedDefs ?? [])],
          queryConditions: workspace.queryConditions ?? {}
        },
        cards: [...document.querySelectorAll('article[data-testid^="tool-card-"],article[data-testid^="pending-card-"]')]
          .filter((element) => element.getClientRects().length > 0)
          .map((element) => ({
            testId: element.getAttribute('data-testid'),
            state: element.querySelector('[data-card-state]')?.getAttribute('data-card-state') ?? null
          }))
      }
    })()`, false)
    if (snapshot.messages.length > 64 || snapshot.messages.some((message) => message.parts.length > 64)
      || snapshot.cards.length > 256 || snapshot.workspace.selectedDefs.length > 64) {
      throw new VerificationFailure('BROWSER_EVIDENCE_ARRAY_LIMIT_EXCEEDED')
    }
    return snapshot
  }

  findPart(snapshot, toolCallId) {
    for (const message of snapshot.messages) {
      const part = message.parts.find((candidate) => candidate.toolCallId === toolCallId)
      if (part) return {message, part}
    }
    return null
  }

  receiptRecords(toolCallId, urlPath = '/api/ai/frontend-tool-result') {
    return this.requests.filter((record) => record.path === urlPath && record.postData !== null).filter((record) => {
      try {
        return JSON.parse(record.postData)?.toolCallId === toolCallId
      } catch {
        return false
      }
    }).map((record) => ({status: record.responseStatus, finished: record.finished, payload: record.postData}))
  }

  requestCounts() {
    const count = (method, suffix) => this.requests.filter((request) => request.method === method && request.path === suffix).length
    return {
      chat: count('POST', '/api/ai/chat'),
      frontendToolResult: count('POST', '/api/ai/frontend-tool-result'),
      confirm: count('POST', '/api/ai/confirm')
    }
  }

  async reload() {
    await this.send('Page.reload', {ignoreCache: true})
    await this.waitFor(`(() => {
      const app = document.getElementById('app')
      const pinia = app && app.__vue_app__ && app.__vue_app__.config.globalProperties.$pinia
      const ai = pinia && pinia._s.get('ai')
      return !!(ai && ai.historyLoaded && !ai.loading)
    })()`, 'RELOAD_NOT_READY')
    await this.evaluate(`document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai').expanded = true`, false)
  }

  async close() {
    const result = {status: 'COMPLETE', errors: []}
    if (this.config.scenario === 'C-152' && this.ws && this.ws.readyState === WebSocket.OPEN) {
      try {
        await this.send('Browser.setDownloadBehavior', {behavior: 'default'})
      } catch (error) {
        result.errors.push(safeError(error, 'DOWNLOAD_BEHAVIOR_RESTORE_FAILED'))
      }
    }
    for (const id of [this.occluderId, this.targetId]) {
      if (!id) continue
      try {
        const response = await this.boundedFetch(`${this.config.cdpUrl}/json/close/${id}`)
        if (!response.ok) result.errors.push({code: 'CDP_TARGET_CLOSE_FAILED'})
      } catch (error) {
        result.errors.push(safeError(error, 'CDP_TARGET_CLOSE_FAILED'))
      }
    }
    if (this.ws && this.ws.readyState !== WebSocket.CLOSED) {
      try {
        this.ws.close()
      } catch (error) {
        result.errors.push(safeError(error, 'CDP_WEBSOCKET_CLOSE_FAILED'))
      }
    }
    if (result.errors.length > 0) result.status = 'INCOMPLETE'
    return result
  }
}

function safeSnapshot(snapshot) {
  return {
    hidden: snapshot.hidden === true,
    visibilityState: safeToken(snapshot.visibilityState),
    sessionId: snapshot.sessionId ?? null,
    activeRunId: snapshot.activeRunId ?? null,
    loading: snapshot.loading === true,
    historyLoaded: snapshot.historyLoaded === true,
    messages: snapshot.messages.slice(-64).map((message) => ({
      id: message.id ?? null,
      runId: message.runId ?? null,
      done: message.done === true,
      parts: message.parts.slice(-64).map((part) => part.kind === 'tool' ? {
        kind: 'tool',
        toolCallId: part.toolCallId ?? null,
        name: safeToken(part.name),
        status: safeToken(part.status),
        requiresAction: part.requiresAction === true,
        code: safeToken(part.code),
        result: textEvidence(part.result)
      } : {kind: safeToken(part.kind, 'unknown')})
    })),
    messagesTruncated: snapshot.messages.length > 64,
    workspace: {
      pageId: safeToken(snapshot.workspace.pageId),
      taskId: Number.isSafeInteger(snapshot.workspace.taskId) ? snapshot.workspace.taskId : null,
      step: safeToken(snapshot.workspace.step),
      selectedDefs: snapshot.workspace.selectedDefs.slice(0, 64).map((value) => safeToken(value)).filter(Boolean),
      queryConditions: textEvidence(canonicalJson(snapshot.workspace.queryConditions))
    },
    cards: snapshot.cards.slice(-256).map((card) => ({testId: safeToken(card.testId), state: safeToken(card.state)})),
    cardsTruncated: snapshot.cards.length > 256
  }
}

async function beginTool(context, name, args, epoch = null) {
  const {browser} = context
  const before = await browser.snapshot()
  const beforeIds = new Set(before.messages.flatMap((message) => message.parts.map((part) => part.toolCallId).filter(Boolean)))
  const nonce = randomUUID()
  const prompt = `tool=${name}&args=${JSON.stringify(args)}${epoch ? ` epoch=${epoch}` : ''} verification=${nonce}`
  await browser.sendPrompt(prompt)
  const found = await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    const before = ${JSON.stringify([...beforeIds])}
    for (const message of ai.messages) {
      for (const part of message.parts) {
        if (part.kind === 'tool' && part.name === '${name}' && !before.includes(part.toolCallId)
          && (part.requiresAction === true || ${JSON.stringify([...terminalParts])}.includes(part.status))) {
          return {toolCallId: part.toolCallId, runId: message.runId ?? null, pending: part.requiresAction === true}
        }
      }
    }
    return null
  })()`, `TOOL_${name}_NOT_OBSERVED`)
  return {...found, promptEvidence: textEvidence(prompt)}
}

async function finishTool(context, begun, name, expectSuccess = true) {
  const {browser, api, checks} = context
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    const host = ai.messages.find((message) => message.parts.some((part) => part.toolCallId === '${begun.toolCallId}'))
    const part = host?.parts.find((candidate) => candidate.toolCallId === '${begun.toolCallId}')
    return ai.loading === false && host?.done === true && ${JSON.stringify([...terminalParts])}.includes(part?.status)
  })()`, `TOOL_${name}_NOT_TERMINAL`)
  const snapshot = await browser.snapshot()
  const target = browser.findPart(snapshot, begun.toolCallId)
  const runId = begun.runId ?? target?.message.runId
  checks.require(`${name}.identity`, !!target && typeof runId === 'string' && runId !== '', 'tool part and runId present', {toolCallId: begun.toolCallId, runId})
  const history = await api.history(snapshot.sessionId)
  const occurrences = historyOccurrences(history, begun.toolCallId)
  const run = await api.run(runId)
  const cards = snapshot.cards.filter((card) => card.testId === `tool-card-${begun.toolCallId}` || card.testId === `pending-card-${begun.toolCallId}`)
  checks.require(`${name}.history.once`, occurrences.states.length === 1 && occurrences.responses.length === 1,
    'one history state and one response', occurrences)
  checks.require(`${name}.dom.once`, cards.length === 1, 'one real DOM tool card', cards)
  checks.require(`${name}.run.terminal`, terminalRuns.has(run.snapshot?.status), [...terminalRuns], run.snapshot?.status ?? null)
  if (expectSuccess) {
    checks.require(`${name}.store.succeeded`, target.part.status === 'succeeded', 'succeeded', target.part.status)
    checks.require(`${name}.history.executed`, occurrences.states[0]?.status === 'EXECUTED', 'EXECUTED', occurrences.states[0])
    checks.require(`${name}.run.done`, run.snapshot?.status === 'DONE', 'DONE', run.snapshot?.status ?? null)
  } else {
    checks.require(`${name}.store.not-operable`, ['failed', 'rejected', 'blocked'].includes(target.part.status),
      ['failed', 'rejected', 'blocked'], target.part.status)
    checks.require(`${name}.history.not-executed`, occurrences.states[0]?.status !== 'EXECUTED', 'not EXECUTED', occurrences.states[0])
  }
  if (name !== 'start_export') {
    const receipts = browser.receiptRecords(begun.toolCallId)
    checks.require(`${name}.receipt.once`, receipts.length === 1 && receipts[0].status === 200 && receipts[0].finished,
      'one completed frontend-tool-result HTTP 200', receipts.map((entry) => ({status: entry.status, finished: entry.finished})))
  }
  context.toolRuns.push({name, runId, toolCallId: begun.toolCallId})
  return {toolCallId: begun.toolCallId, runId, snapshot, history, run, target, resultText: target.part.result}
}

async function sendTool(context, name, args, expectSuccess = true, epoch = null) {
  const begun = await beginTool(context, name, args, epoch)
  context.checks.require(`${name}.not-hitl`, begun.pending === false, 'automatic frontend execution', begun)
  return await finishTool(context, begun, name, expectSuccess)
}

async function prepareTask(context, mode) {
  const task = await context.api.createTask(`ai-visibility-${context.config.scenario}-${randomUUID()}`)
  const taskId = Number(task.id)
  if (!Number.isSafeInteger(taskId) || taskId <= 0) throw new VerificationFailure('FIXTURE_TASK_ID_INVALID')
  context.taskId = taskId
  if (mode !== 'empty') await context.api.selectDefs(taskId)
  if (mode === 'export') {
    await context.api.setCondition(taskId)
    await context.api.goToStep(taskId, 'QUERY_COND')
    await context.api.goToStep(taskId, 'EXPORT')
  }
  return taskId
}

async function openFixture(context) {
  const {browser, config, taskId} = context
  if (config.scenario === 'C-147') {
    await browser.navigate('/tasks', '', `workspace.pageId === 'tasks'`)
  } else {
    await browser.navigate('/export', `?taskId=${taskId}`, `workspace.pageId === 'export' && workspace.taskId === ${taskId}`)
  }
}

function assertBusinessStable(context, before, after, label) {
  context.checks.require(`${label}.business.unrelated-stable`, before.unrelatedFingerprint === after.unrelatedFingerprint,
    before.unrelatedFingerprint, after.unrelatedFingerprint)
}

async function runC147(context) {
  await prepareTask(context, 'empty')
  await openFixture(context)
  const baseline = await businessSnapshot(context.api, context.taskId)
  await context.browser.hide()
  const results = []
  results.push(await sendTool(context, 'navigate_to', {page: 'export', taskId: context.taskId}))
  results.push(await sendTool(context, 'select_definitions', {defCodes: ['CURRENCY']}))
  results.push(await sendTool(context, 'confirm_step', {step: 'QUERY_COND'}))
  results.push(await sendTool(context, 'set_condition', {defCode: 'CURRENCY', conditions: {fields: [{fieldCode: 'code', operator: 'EQ', value: 'CNY'}]}}))
  for (const result of results) context.checks.require('C-147.execution-hidden', result.snapshot.hidden === true && result.snapshot.visibilityState === 'hidden',
    'actual hidden document at every tool completion', {hidden: result.snapshot.hidden, visibilityState: result.snapshot.visibilityState})
  const finalBusiness = await businessSnapshot(context.api, context.taskId)
  assertBusinessStable(context, baseline, finalBusiness, 'C-147')
  context.checks.require('C-147.business.complete', finalBusiness.relevant?.currentStep === 'QUERY_COND'
    && finalBusiness.relevant.items.filter((item) => item.defCode === 'CURRENCY').length === 1 && finalBusiness.relevant.jobs.length === 0,
  'CURRENCY selected, QUERY_COND reached once, zero jobs', finalBusiness.relevant)
  const workspace = results.at(-1).snapshot.workspace
  context.checks.require('C-147.workspace.condition', workspace.pageId === 'export' && workspace.taskId === context.taskId
    && workspace.step === 'QUERY_COND' && workspace.selectedDefs.length === 1 && workspace.selectedDefs[0] === 'CURRENCY'
    && canonicalJson(workspace.queryConditions).includes('CNY'), 'real workspace has CNY condition draft', safeSnapshot(results.at(-1).snapshot).workspace)
  context.evidenceExtra.toolRuns = results.map((result) => ({toolCallId: result.toolCallId, runId: result.runId, name: result.target.part.name}))
}

function geometryEvidence(state, expectedValue = null) {
  if (!state) return null
  const {value, ...geometry} = state
  return {
    ...geometry,
    value: textEvidence(value),
    valueMatchesExpected: expectedValue === null ? null : value === expectedValue
  }
}

async function setGeometryFixture(browser, mode) {
  const state = await browser.evaluate(`(() => {
    const el = document.querySelector('[data-ai-ref="export.selectDefs.search"]')
    if (!el) return null
    window.__aiVisibilityOriginalStyle = el.style.cssText
    window.scrollTo(0, 0)
    if (${JSON.stringify(mode)} === 'scrollable') el.style.marginTop = '1400px'
    else el.style.cssText += ';position:fixed;top:2000px;left:16px;z-index:9999'
    const rect = el.getBoundingClientRect()
    const input = el.matches('input,textarea') ? el : el.querySelector('input,textarea')
    return {
      top: rect.top,
      bottom: rect.bottom,
      viewportHeight: window.innerHeight,
      scrollHeight: document.documentElement.scrollHeight,
      value: input?.value ?? ''
    }
  })()`, false)
  return geometryEvidence(state)
}

async function geometryState(browser, expectedValue) {
  const state = await browser.evaluate(`(() => {
    const el = document.querySelector('[data-ai-ref="export.selectDefs.search"]')
    if (!el) return null
    const rect = el.getBoundingClientRect()
    const input = el.matches('input,textarea') ? el : el.querySelector('input,textarea')
    const hit = rect.width > 0 && rect.height > 0
      ? document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2) : null
    return {
      top: rect.top,
      bottom: rect.bottom,
      viewportHeight: window.innerHeight,
      value: input?.value ?? '',
      visibleInViewport: rect.bottom > 0 && rect.top < window.innerHeight && rect.width > 0 && rect.height > 0,
      fullyInViewport: rect.top >= 0 && rect.bottom <= window.innerHeight
        && rect.left >= 0 && rect.right <= window.innerWidth && rect.width > 0 && rect.height > 0,
      hitTarget: !!(hit && (el.contains(hit) || hit.contains(el)))
    }
  })()`, false)
  return geometryEvidence(state, expectedValue)
}

async function restoreGeometryFixture(browser) {
  return await browser.evaluate(`(() => {
    const el = document.querySelector('[data-ai-ref="export.selectDefs.search"]')
    if (el && typeof window.__aiVisibilityOriginalStyle === 'string') el.style.cssText = window.__aiVisibilityOriginalStyle
    delete window.__aiVisibilityOriginalStyle
    return !window.__aiVisibilityOriginalStyle
  })()`, false)
}

async function runC148(context) {
  await prepareTask(context, 'empty')
  await openFixture(context)
  const baseline = await businessSnapshot(context.api, context.taskId)
  const searchReady = `(() => {
    const el = document.querySelector('[data-ai-ref="export.selectDefs.search"]')
    return !!(el && el.isConnected && el.getClientRects().length > 0)
  })()`
  await context.browser.waitFor(searchReady, 'C-148.search-element-not-mounted')
  const snapshot = await sendTool(context, 'ui_snapshot', {limit: 40, kinds: ['input']})
  const searchInSnapshot = snapshot.resultText.includes('[export.selectDefs.search]')
  context.checks.require('C-148.snapshot.search-registered', searchInSnapshot,
    'real ui_snapshot contains export.selectDefs.search, proving AI region registration', {contains: searchInSnapshot})
  const epoch = /\bepoch=([a-z0-9]{6}-\d{1,6})\b/.exec(snapshot.resultText)?.[1] ?? null
  context.checks.require('C-148.epoch.real', epoch !== null, 'epoch obtained from real ui_snapshot result', epoch)
  const before = await setGeometryFixture(context.browser, 'scrollable')
  context.checks.require('C-148.offscreen.constructed', before && before.top > before.viewportHeight && before.scrollHeight > before.viewportHeight,
    'real element starts below viewport with scrollable document', before)
  await sendTool(context, 'ui_fill', {ref: 'export.selectDefs.search', value: 'CNY', epoch: 'a1b2c3-1'}, true, epoch)
  const afterFill = await geometryState(context.browser, 'CNY')
  context.checks.require('C-148.scroll-and-write', afterFill?.valueMatchesExpected === true
    && afterFill.fullyInViewport && afterFill.hitTarget,
  'tool scrolled the real element fully into viewport, preserved hit-testing and wrote CNY', afterFill)
  await restoreGeometryFixture(context.browser)
  await context.browser.waitFor(searchReady, 'C-148.search-element-not-remounted')
  const snapshot2 = await sendTool(context, 'ui_snapshot', {limit: 40, kinds: ['input']})
  const searchInSecondSnapshot = snapshot2.resultText.includes('[export.selectDefs.search]')
  context.checks.require('C-148.snapshot.second-search-registered', searchInSecondSnapshot,
    'second real ui_snapshot also contains the registered search element', {contains: searchInSecondSnapshot})
  const epoch2 = /\bepoch=([a-z0-9]{6}-\d{1,6})\b/.exec(snapshot2.resultText)?.[1] ?? null
  context.checks.require('C-148.epoch.second', epoch2 !== null, 'second real epoch', epoch2)
  const impossible = await setGeometryFixture(context.browser, 'impossible')
  context.checks.require('C-148.impossible.constructed', impossible?.top > impossible?.viewportHeight,
    'fixed real element cannot be reached by document scrolling', impossible)
  const rejected = await sendTool(context, 'ui_fill', {ref: 'export.selectDefs.search', value: 'USD', epoch: 'a1b2c3-1'}, false, epoch2)
  const afterRejected = await geometryState(context.browser, 'CNY')
  context.checks.require('C-148.impossible.no-write', afterRejected?.valueMatchesExpected === true
    && afterRejected.visibleInViewport === false, 'unreachable element remains offscreen and its value is unchanged', afterRejected)
  context.checks.require('C-148.impossible.fast-terminal', rejected.run.snapshot?.status !== 'SUSPENDED', 'no pending receipt', rejected.run.snapshot?.status)
  context.checks.require('C-148.geometry.cleanup', await restoreGeometryFixture(context.browser) === true, 'fixture style restored', null)
  const finalBusiness = await businessSnapshot(context.api, context.taskId)
  assertBusinessStable(context, baseline, finalBusiness, 'C-148')
  context.checks.require('C-148.business.zero-side-effect', finalBusiness.fingerprint === baseline.fingerprint,
    baseline.fingerprint, finalBusiness.fingerprint)
  context.evidenceExtra.geometry = {before, afterFill, impossible, afterRejected}
}

async function runC151(context) {
  await prepareTask(context, 'selected')
  await openFixture(context)
  const baseline = await businessSnapshot(context.api, context.taskId)
  await context.browser.hide()
  const executed = await sendTool(context, 'confirm_step', {step: 'QUERY_COND'})
  await context.browser.show()
  const originalRecords = context.browser.receiptRecords(executed.toolCallId)
  context.checks.require('C-151.replay.original-captured', originalRecords.length === 1 && originalRecords[0].status === 200,
    'exactly one original real browser receipt', originalRecords.map((entry) => ({status: entry.status})))
  const originalBody = originalRecords[0].payload
  const originalHash = sha256(originalBody)
  const historyBefore = canonicalJson(historySummary(executed.history))
  const runBefore = canonicalJson(runSummary(executed.run))
  const businessBefore = await businessSnapshot(context.api, context.taskId)
  assertBusinessStable(context, baseline, businessBefore, 'C-151.pre-replay')
  context.checks.require('C-151.business.executed-once', businessBefore.relevant?.currentStep === 'QUERY_COND'
    && businessBefore.relevant.jobs.length === 0, 'QUERY_COND once and zero jobs', businessBefore.relevant)
  const replay = await context.api.request('replay.same', '/api/ai/frontend-tool-result', {method: 'POST', body: originalBody})
  const replayBody = dataOf(replay)
  context.checks.require('C-151.replay.same-idempotent', (replay.status === 200 && replayBody?.duplicate === true)
    || (replay.status === 409 && businessCode(replay.body) === duplicateCode && replayBody?.duplicate === true),
  'same receipt accepted as duplicate or rejected with neutral DUPLICATE_TOOL_CALL_ID',
  {httpStatus: replay.status, businessCode: businessCode(replay.body), duplicate: replayBody?.duplicate === true})
  const conflictValue = JSON.parse(originalBody)
  conflictValue.result = `${conflictValue.result ?? ''}\nC-151 conflicting replay ${randomUUID()}`
  conflictValue.outcome = 'failed'
  delete conflictValue.code
  const conflictBody = JSON.stringify(conflictValue)
  const conflict = await context.api.request('replay.conflict', '/api/ai/frontend-tool-result', {method: 'POST', body: conflictBody})
  context.checks.require('C-151.replay.conflict-visible', conflict.status === 409 && businessCode(conflict.body) === duplicateCode,
    'conflicting receipt is not accepted as success', {httpStatus: conflict.status, businessCode: businessCode(conflict.body)})
  const historyAfter = await context.api.history(executed.snapshot.sessionId)
  const runAfter = await context.api.run(executed.runId)
  const businessAfter = await businessSnapshot(context.api, context.taskId)
  context.checks.require('C-151.replay.history-stable', canonicalJson(historySummary(historyAfter)) === historyBefore,
    'same and conflicting replays add zero history entries', canonicalJson(historySummary(historyAfter)))
  context.checks.require('C-151.replay.run-stable', canonicalJson(runSummary(runAfter)) === runBefore,
    'run and pending outcome unchanged', canonicalJson(runSummary(runAfter)))
  context.checks.require('C-151.replay.business-stable', businessAfter.fingerprint === businessBefore.fingerprint,
    businessBefore.fingerprint, businessAfter.fingerprint)
  await context.browser.reload()
  const recovered = await context.browser.snapshot()
  const target = context.browser.findPart(recovered, executed.toolCallId)
  context.checks.require('C-151.replay.dom-stable', target?.part.status === 'succeeded'
    && recovered.cards.filter((card) => card.testId === `tool-card-${executed.toolCallId}`).length === 1,
  'one succeeded card after recovery', {status: target?.part.status ?? null})
  context.checks.require('C-151.replay.browser-receipt-count', context.browser.receiptRecords(executed.toolCallId).length === 1,
    'recovery does not emit another browser receipt', context.browser.receiptRecords(executed.toolCallId).length)
  const recoveredHistory = await context.api.history(recovered.sessionId)
  context.checks.require('C-151.recovery.history-stable', canonicalJson(historySummary(recoveredHistory)) === historyBefore,
    'recovery adds no history state or response', canonicalJson(historySummary(recoveredHistory)))
  const recoveredBusiness = await businessSnapshot(context.api, context.taskId)
  context.checks.require('C-151.recovery.business-stable', recoveredBusiness.fingerprint === businessBefore.fingerprint,
    businessBefore.fingerprint, recoveredBusiness.fingerprint)
  context.evidenceExtra.replay = {
    originalPayloadSha256: originalHash,
    replayPayloadSha256: sha256(originalBody),
    conflictPayloadSha256: sha256(conflictBody),
    same: {httpStatus: replay.status, businessCode: businessCode(replay.body), duplicate: replayBody?.duplicate === true},
    conflict: {httpStatus: conflict.status, businessCode: businessCode(conflict.body)},
    browserReceiptCount: 1,
    manualReceiptCount: 2
  }
}

function xmlDecode(value) {
  return value.replace(/&(#x[0-9a-f]+|#\d+|amp|lt|gt|quot|apos);/gi, (whole, entity) => {
    const lower = entity.toLowerCase()
    if (lower.startsWith('#x')) return String.fromCodePoint(Number.parseInt(lower.slice(2), 16))
    if (lower.startsWith('#')) return String.fromCodePoint(Number.parseInt(lower, 10))
    return {amp: '&', lt: '<', gt: '>', quot: '"', apos: "'"}[lower] ?? whole
  })
}

async function xlsxEvidence(bytes) {
  if (!Buffer.isBuffer(bytes) || bytes.length > maxXlsxBytes) throw new VerificationFailure('XLSX_BYTES_LIMIT_EXCEEDED')
  let JSZip
  try {
    JSZip = frontendRequire('jszip')
  } catch {
    throw new ScenarioNotRun('EXISTING_JSZIP_UNAVAILABLE')
  }
  const zip = await JSZip.loadAsync(bytes)
  if (Object.keys(zip.files).length > maxZipEntries) throw new VerificationFailure('XLSX_ZIP_ENTRY_LIMIT_EXCEEDED')
  let parsedChars = 0
  const read = async (name) => {
    const entry = zip.file(name)
    if (!entry) return ''
    const declaredChars = entry._data?.uncompressedSize
    if (Number.isSafeInteger(declaredChars) && declaredChars > maxXlsxEntryChars) {
      throw new VerificationFailure('XLSX_ENTRY_CONTENT_LIMIT_EXCEEDED')
    }
    if (Number.isSafeInteger(declaredChars) && parsedChars + declaredChars > maxXlsxTotalChars) {
      throw new VerificationFailure('XLSX_TOTAL_CONTENT_LIMIT_EXCEEDED')
    }
    const text = await entry.async('string')
    if (text.length > maxXlsxEntryChars) throw new VerificationFailure('XLSX_ENTRY_CONTENT_LIMIT_EXCEEDED')
    parsedChars += text.length
    if (parsedChars > maxXlsxTotalChars) throw new VerificationFailure('XLSX_TOTAL_CONTENT_LIMIT_EXCEEDED')
    return text
  }
  const sharedXml = await read('xl/sharedStrings.xml')
  const sharedMatches = [...sharedXml.matchAll(/<si\b[^>]*>([\s\S]*?)<\/si>/g)]
  if (sharedMatches.length > maxSharedStrings) throw new VerificationFailure('XLSX_SHARED_STRING_LIMIT_EXCEEDED')
  const shared = sharedMatches.map((match) =>
    [...match[1].matchAll(/<t\b[^>]*>([\s\S]*?)<\/t>/g)].map((text) => xmlDecode(text[1])).join(''))
  const sheetNames = Object.keys(zip.files).filter((entry) => /^xl\/worksheets\/sheet\d+\.xml$/.test(entry)).sort()
  if (sheetNames.length > maxXlsxSheets) throw new VerificationFailure('XLSX_SHEET_LIMIT_EXCEEDED')
  const sheets = []
  let rowCount = 0
  let cellCount = 0
  for (const name of sheetNames) {
    const xml = await read(name)
    const rows = []
    for (const row of xml.matchAll(/<row\b[^>]*>([\s\S]*?)<\/row>/g)) {
      if (rows.length >= maxRowsPerSheet || rowCount >= maxTotalRows) throw new VerificationFailure('XLSX_ROW_LIMIT_EXCEEDED')
      const cellMatches = [...row[1].matchAll(/<c\b([^>]*)>([\s\S]*?)<\/c>|<c\b([^>]*)\/>/g)]
      if (cellMatches.length > maxCellsPerRow) throw new VerificationFailure('XLSX_ROW_CELL_LIMIT_EXCEEDED')
      cellCount += cellMatches.length
      if (cellCount > maxTotalCells) throw new VerificationFailure('XLSX_TOTAL_CELL_LIMIT_EXCEEDED')
      const values = []
      for (const cell of cellMatches) {
        const attrs = cell[1] ?? cell[3] ?? ''
        const body = cell[2] ?? ''
        const type = /\bt="([^"]+)"/.exec(attrs)?.[1] ?? 'n'
        const raw = /<v[^>]*>([\s\S]*?)<\/v>/.exec(body)?.[1] ?? ''
        const inline = [...body.matchAll(/<t\b[^>]*>([\s\S]*?)<\/t>/g)].map((text) => xmlDecode(text[1])).join('')
        const value = type === 'inlineStr' || type === 'str' ? inline || xmlDecode(raw)
          : type === 's' ? shared[Number.parseInt(raw, 10)] ?? '' : xmlDecode(raw)
        values.push(value)
      }
      rows.push(values)
      rowCount += 1
    }
    sheets.push(rows)
  }
  return {bytes: bytes.length, sha256: sha256(bytes), pk: bytes[0] === 0x50 && bytes[1] === 0x4b,
    sheetCount: sheets.length, rowCount, cellCount, parsedChars, cellDigest: sha256(sheets)}
}

async function waitForDownload(directory, timeoutMs, pollMs) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const names = (await readdir(directory)).filter((name) => !name.endsWith('.crdownload') && !name.endsWith('.tmp'))
    if (names.length === 1) {
      const file = path.join(directory, names[0])
      const info = await stat(file)
      if (info.isFile() && info.size > 500) return {file, name: names[0], size: info.size}
    }
    if (names.length > 1) throw new VerificationFailure('DOWNLOAD_COUNT_NOT_ONE')
    await sleep(pollMs)
  }
  throw new VerificationFailure('DOWNLOAD_NOT_COMPLETED')
}

async function waitForExport(context) {
  const deadline = Date.now() + context.config.timeoutMs
  while (Date.now() < deadline) {
    const jobs = await context.api.jobs(context.taskId)
    const files = await context.api.files(context.taskId)
    const exportJobs = jobs.filter((job) => job.jobType === 'EXPORT')
    const exportFiles = files.filter((file) => file.fileType === 'EXPORT' && file.defCode === 'CURRENCY')
    if (exportJobs.length === 1 && exportJobs[0].status === 'COMPLETED' && exportFiles.length === 1) return {jobs, files}
    if (exportJobs.some((job) => ['FAILED', 'CANCELLED'].includes(job.status))) throw new VerificationFailure('EXPORT_JOB_FAILED')
    await sleep(context.config.pollMs)
  }
  throw new VerificationFailure('EXPORT_JOB_NOT_COMPLETED')
}

async function runC152(context) {
  await prepareTask(context, 'export')
  await openFixture(context)
  const baseline = await businessSnapshot(context.api, context.taskId)
  const downloadDir = path.join(context.evidence.directory, `download-${context.round}`)
  await mkdir(downloadDir, {recursive: true})
  context.downloadDir = downloadDir
  await context.browser.send('Browser.setDownloadBehavior', {behavior: 'allow', downloadPath: downloadDir})
  const begun = await beginTool(context, 'start_export', {taskId: context.taskId})
  context.checks.require('C-152.hitl.required', begun.pending === true, 'human approval required before background execution', begun)
  await context.browser.click(`[data-testid="pending-card-${begun.toolCallId}"] [data-testid="gate-actions"] button:last-child`)
  await context.browser.hide()
  const started = await finishTool(context, begun, 'start_export', true)
  const confirms = context.browser.receiptRecords(begun.toolCallId, '/api/ai/confirm')
  context.checks.require('C-152.confirm.once', confirms.length === 1 && confirms[0].status === 200,
    'one real human confirmation', confirms.map((entry) => ({status: entry.status})))
  const exported = await waitForExport(context)
  const exportReadySnapshot = await context.browser.snapshot()
  context.checks.require('C-152.export.hidden', exportReadySnapshot.hidden === true && exportReadySnapshot.visibilityState === 'hidden',
    'job and file became ready while document remained hidden',
    {hidden: exportReadySnapshot.hidden, visibilityState: exportReadySnapshot.visibilityState})
  const download = await sendTool(context, 'download_export_files', {taskId: context.taskId, defCodes: ['CURRENCY']})
  const downloaded = await waitForDownload(downloadDir, context.config.timeoutMs, context.config.pollMs)
  const downloadCompletedSnapshot = await context.browser.snapshot()
  context.checks.require('C-152.download.completed-hidden', downloadCompletedSnapshot.hidden === true
    && downloadCompletedSnapshot.visibilityState === 'hidden', 'download completed while document remained hidden',
  {hidden: downloadCompletedSnapshot.hidden, visibilityState: downloadCompletedSnapshot.visibilityState})
  const browserBytes = await readFile(downloaded.file)
  const publicBytes = await context.api.download(context.taskId)
  const browserXlsx = await xlsxEvidence(browserBytes)
  const publicXlsx = await xlsxEvidence(publicBytes)
  context.checks.require('C-152.download.complete', browserXlsx.pk && browserXlsx.sheetCount > 0 && browserXlsx.rowCount > 0
    && browserXlsx.cellDigest === publicXlsx.cellDigest, 'browser download and public export have identical workbook cell content',
  {browser: browserXlsx, public: publicXlsx})
  const historyBefore = canonicalJson(historySummary(download.history))
  const businessBefore = await businessSnapshot(context.api, context.taskId)
  assertBusinessStable(context, baseline, businessBefore, 'C-152')
  context.checks.require('C-152.business.one-job-one-file', businessBefore.relevant?.jobs.filter((job) => job.jobType === 'EXPORT' && job.status === 'COMPLETED').length === 1
    && businessBefore.relevant.files.filter((file) => file.fileType === 'EXPORT' && file.defCode === 'CURRENCY').length === 1,
  'one completed EXPORT job and one CURRENCY file', businessBefore.relevant)
  const countsBefore = context.browser.requestCounts()
  await context.browser.show()
  for (let index = 1; index <= 2; index += 1) {
    await context.browser.reload()
    const recovered = await context.browser.snapshot()
    context.checks.require(`C-152.recovery.${index}.dom`, ['start_export', 'download_export_files'].every((name) => {
      const id = name === 'start_export' ? started.toolCallId : download.toolCallId
      const target = context.browser.findPart(recovered, id)
      return target?.part.status === 'succeeded' && recovered.cards.filter((card) => card.testId === `tool-card-${id}`).length === 1
    }), 'both succeeded cards restored exactly once', recovered.cards)
    const historyAfter = await context.api.history(recovered.sessionId)
    context.checks.require(`C-152.recovery.${index}.history`, canonicalJson(historySummary(historyAfter)) === historyBefore,
      'history stable across two recoveries', canonicalJson(historySummary(historyAfter)))
    const businessAfter = await businessSnapshot(context.api, context.taskId)
    context.checks.require(`C-152.recovery.${index}.business`, businessAfter.fingerprint === businessBefore.fingerprint,
      businessBefore.fingerprint, businessAfter.fingerprint)
    context.checks.require(`C-152.recovery.${index}.no-repeat`, canonicalJson(context.browser.requestCounts()) === canonicalJson(countsBefore),
      countsBefore, context.browser.requestCounts())
  }
  context.evidenceExtra.export = {
    startRunId: started.runId,
    downloadRunId: download.runId,
    jobCount: exported.jobs.length,
    fileCount: exported.files.length,
    browserWorkbook: browserXlsx,
    publicWorkbook: publicXlsx,
    recoveryCount: 2
  }
}

async function cleanupTask(context) {
  if (!context.taskId) return {status: 'NOT_REQUIRED'}
  const result = {taskId: context.taskId, status: 'INCOMPLETE', postStatus: null}
  try {
    const current = await context.api.task(context.taskId)
    if (current.status === 404) return {taskId: context.taskId, status: 'COMPLETE', postStatus: 404}
    const jobs = await context.api.jobs(context.taskId)
    const query = new URLSearchParams()
    if (jobs.length === 0) query.append('expectedJobIds', '')
    else for (const job of jobs) query.append('expectedJobIds', String(job.id))
    const deleted = await context.api.request('cleanup.task', `/api/tasks/${context.taskId}?${query}`, {method: 'DELETE'})
    const after = await context.api.task(context.taskId)
    result.postStatus = after.status
    result.status = [200, 204, 404].includes(deleted.status) && after.status === 404 ? 'COMPLETE' : 'INCOMPLETE'
  } catch (error) {
    result.error = safeError(error, 'TASK_CLEANUP_FAILED')
  }
  return result
}

async function cleanupRound(context) {
  const result = {sessions: [], task: null, download: null, browser: null, status: 'COMPLETE'}
  for (const sessionId of context.trackedSessions) {
    const entry = {sessionId, status: 'INCOMPLETE', attempts: []}
    try {
      if (context.runIds.size > 0) {
        for (const runId of context.runIds) {
          const run = await context.api.run(runId).catch(() => null)
          if (run && !terminalRuns.has(run.snapshot?.status)) await context.api.request('cleanup.cancel', `/api/ai/cancel/${encodeURIComponent(runId)}`, {method: 'POST'})
        }
      }
      for (let attempt = 1; attempt <= 2; attempt += 1) {
        const deleted = await context.api.request('cleanup.session', `/api/ai/session/${encodeURIComponent(sessionId)}`, {method: 'DELETE'})
        const body = dataOf(deleted)
        entry.attempts.push({attempt, httpStatus: deleted.status, complete: body?.complete === true,
          deferredCount: Array.isArray(body?.deferred) ? body.deferred.length : null,
          errorCount: Array.isArray(body?.errors) ? body.errors.length : null})
      }
      const history = await context.api.request('cleanup.history', `/api/ai/history/${encodeURIComponent(sessionId)}?latest=1`)
      const messages = dataOf(history)?.messages
      entry.status = entry.attempts.every((attempt) => attempt.httpStatus === 200 && attempt.complete
        && attempt.deferredCount === 0 && attempt.errorCount === 0)
        && (history.status === 410 || (history.status === 200 && Array.isArray(messages) && messages.length === 0)) ? 'COMPLETE' : 'INCOMPLETE'
    } catch (error) {
      entry.error = safeError(error, 'SESSION_CLEANUP_FAILED')
    }
    result.sessions.push(entry)
    if (entry.status !== 'COMPLETE') result.status = 'INCOMPLETE'
  }
  result.task = await cleanupTask(context)
  if (!['COMPLETE', 'NOT_REQUIRED'].includes(result.task.status)) result.status = 'INCOMPLETE'
  result.browser = await context.browser.close()
  if (result.browser.status !== 'COMPLETE') result.status = 'INCOMPLETE'
  if (context.downloadDir) {
    try {
      await rm(context.downloadDir, {recursive: true, force: true})
      result.download = {status: 'COMPLETE', rawBytesRetained: false}
    } catch (error) {
      result.download = {status: 'INCOMPLETE', error: safeError(error, 'DOWNLOAD_CLEANUP_FAILED')}
      result.status = 'INCOMPLETE'
    }
  }
  return result
}

const config = loadConfig()
const evidenceDirectory = await prepareEvidenceDirectory(config)
const evidence = new Evidence(evidenceDirectory)
const startedAt = new Date().toISOString()
const rounds = []
const seenSessions = new Set()
const seenRunIds = new Set()
const seenToolCallIds = new Set()
const expectedRunCounts = {'C-147': 4, 'C-148': 4, 'C-151': 1, 'C-152': 2}
let fatal = null

for (let round = 1; round <= 3; round += 1) {
  const checks = new Checks()
  const context = {
    config,
    round,
    checks,
    api: new ApiClient(config),
    browser: new CdpPage(config),
    evidence: new Evidence(path.join(evidenceDirectory, `round-${round}`)),
    taskId: null,
    trackedSessions: new Set(),
    runIds: new Set(),
    toolRuns: [],
    downloadDir: null,
    evidenceExtra: {}
  }
  await mkdir(context.evidence.directory, {recursive: true})
  const log = new LogProbe(config.backendLogFile)
  const record = {round, status: 'FAIL', assertions: checks.items, fatal: null, notRun: null, cleanup: null, log: null}
  rounds.push(record)
  try {
    await log.begin()
    await context.browser.open(config.scenario === 'C-147' ? '/tasks' : '/export')
    const sessionId = await context.browser.newChat(context.trackedSessions)
    if (seenSessions.has(sessionId)) throw new VerificationFailure('SESSION_NOT_FRESH')
    seenSessions.add(sessionId)
    if (config.scenario === 'C-147') await runC147(context)
    if (config.scenario === 'C-148') await runC148(context)
    if (config.scenario === 'C-151') await runC151(context)
    if (config.scenario === 'C-152') await runC152(context)
    const expectedRunCount = expectedRunCounts[config.scenario]
    const runIds = context.toolRuns.map((entry) => entry.runId)
    const toolCallIds = context.toolRuns.map((entry) => entry.toolCallId)
    checks.require('runs.exact-count-and-unique', context.toolRuns.length === expectedRunCount
      && new Set(runIds).size === expectedRunCount && new Set(toolCallIds).size === expectedRunCount,
    {expectedRunCount, uniqueRunIds: expectedRunCount, uniqueToolCallIds: expectedRunCount},
    {actualRunCount: context.toolRuns.length, uniqueRunIds: new Set(runIds).size, uniqueToolCallIds: new Set(toolCallIds).size, runIds, toolCallIds})
    for (const runId of runIds) {
      if (seenRunIds.has(runId)) throw new VerificationFailure('RUN_ID_REUSED_ACROSS_ROUNDS')
      seenRunIds.add(runId)
      context.runIds.add(runId)
    }
    for (const toolCallId of toolCallIds) {
      if (seenToolCallIds.has(toolCallId)) throw new VerificationFailure('TOOL_CALL_ID_REUSED_ACROSS_ROUNDS')
      seenToolCallIds.add(toolCallId)
    }
    context.evidenceExtra.runIdentity = {expectedRunCount, runIds, toolCallIds}
    for (const value of Object.values(context.evidenceExtra)) {
      if (isRecord(value)) for (const key of ['startRunId', 'downloadRunId']) if (value[key]) context.runIds.add(value[key])
    }
    record.status = checks.failures.length === 0 ? 'PASS' : 'FAIL'
  } catch (error) {
    if (error instanceof ScenarioNotRun) {
      record.status = 'NOT_RUN'
      record.notRun = {code: safeToken(error.code, 'SCENARIO_NOT_RUN')}
    } else {
      record.status = 'FAIL'
      record.fatal = safeError(error)
    }
  } finally {
    try {
      const snapshot = await context.browser.snapshot()
      for (const message of snapshot.messages) if (message.runId) context.runIds.add(message.runId)
      if (snapshot.sessionId) context.trackedSessions.add(snapshot.sessionId)
    } catch {
      record.browserIdentityCapture = 'UNAVAILABLE'
    }
    record.networkFailures = context.browser.networkFailureEvidence()
    record.cdpErrors = {
      count: context.browser.cdpErrorCount,
      tail: context.browser.cdpErrors.slice(-64),
      truncated: context.browser.cdpErrorCount > context.browser.cdpErrors.length
    }
    record.cleanup = await cleanupRound(context).catch((error) => ({status: 'INCOMPLETE', error: safeError(error, 'CLEANUP_FAILED')}))
    try {
      record.log = await log.capture()
      checks.add('logs.no-new-error', record.log.errorCount === 0, 0, record.log.errorCount)
    } catch (error) {
      record.log = {status: 'NOT_RUN', error: safeError(error, 'LOG_CAPTURE_FAILED')}
      record.status = 'FAIL'
    }
    checks.add('cdp.no-errors-or-overflow', record.cdpErrors.count === 0, 0, record.cdpErrors)
    checks.add('network.no-loading-failures', record.networkFailures.count === 0, 0, record.networkFailures)
    if (record.cleanup.status !== 'COMPLETE' || checks.failures.length > 0 || record.log?.errorCount > 0) record.status = 'FAIL'
    record.assertions = checks.items
    record.failedAssertions = checks.failures
    record.taskId = context.taskId
    record.sessionIds = [...context.trackedSessions]
    record.runIds = [...context.runIds]
    Object.assign(record, context.evidenceExtra)
    await context.evidence.json('round.json', record)
  }
}

const status = fatal || rounds.some((round) => round.status === 'FAIL') ? 'FAIL'
  : rounds.some((round) => round.status === 'NOT_RUN') ? 'NOT_RUN' : 'PASS'
const summary = {
  schemaVersion: 1,
  scenario: config.scenario,
  status,
  statusScope: 'THREE_FRESH_SESSIONS_IN_ONE_PROCESS; REAL_CDP_DOM_AND_VISIBILITY; REAL_STUB_UPSTREAM; PUBLIC_HISTORY_RUN_AND_BUSINESS_APIS; REAL_PROVIDER=NOT_RUN; DIRECT_REDIS=NOT_RUN; DIRECT_DATABASE=NOT_RUN',
  startedAt,
  finishedAt: new Date().toISOString(),
  codeHead: codeHead(),
  executorIdentity: config.executorIdentity,
  evidenceDirectory,
  requiredProcessCountPerScenario: 1,
  requiredFreshSessionsPerProcess: 3,
  rounds,
  evidencePolicy: 'BOUNDED_WHITELIST_ONLY; no raw API/SSE responses, prompt/tool text, DOM text, log lines or exception messages; workbook bytes deleted after cell-content comparison',
  cleanupComplete: rounds.every((round) => round.cleanup?.status === 'COMPLETE')
}
await evidence.json('summary.json', summary)
console.log(JSON.stringify({scenario: config.scenario, status, evidenceDirectory,
  rounds: rounds.map((round) => ({round: round.round, status: round.status, cleanup: round.cleanup?.status}))}, null, 2))
if (status !== 'PASS') process.exitCode = status === 'NOT_RUN' ? 2 : 1
