#!/usr/bin/env node

import {execFileSync} from 'node:child_process'
import {createHash, randomUUID} from 'node:crypto'
import {lstat, mkdir, readdir, readFile, realpath, stat, writeFile} from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {setTimeout as sleep} from 'node:timers/promises'

const repoRoot = fileURLToPath(new URL('../..', import.meta.url))
const localHosts = new Set(['localhost', '127.0.0.1', '::1', '[::1]'])
const toolCases = new Map([
  ['tool-navigate', {
    tool: 'navigate_to',
    fixture: 'none',
    prompt: 'tool=navigate_to&args={"page":"tasks"}'
  }],
  ['tool-select-definitions', {
    tool: 'select_definitions',
    fixture: 'select-defs',
    prompt: 'tool=select_definitions&args={"defCodes":["CURRENCY"]}'
  }],
  ['tool-confirm-step', {
    tool: 'confirm_step',
    fixture: 'confirm-step',
    prompt: 'tool=confirm_step&args={"step":"QUERY_COND"}'
  }],
  ['tool-set-condition', {
    tool: 'set_condition',
    fixture: 'set-condition',
    prompt: 'tool=set_condition&args={"defCode":"CURRENCY","conditions":{"fields":[{"fieldCode":"code","operator":"EQ","value":"CNY"}]}}'
  }]
])
const scenarios = new Set([...toolCases.keys(), 'tool-skipped', 'ordinary-skipped', 'form-mixed-copy', 'recovery-success', 'recovery-final-failure',
  'recovery-hitl-gate', 'recovery-stream-refresh', 'recovery-mixed-messages', 'recovery-context-usage',
  'recovery-stream-history-race', 'recovery-pending-integrity', 'recovery-cross-run-order', 'recovery-form-queue',
  'correction-prefix', 'correction-prefix-preflight', 'recovery-history-generation', 'recovery-stopped', 'content-matrix', 'recovery-timing-proxy', 'sheet-preview'])
const recoverySurfaceSelector = [
  '[data-testid="alert-history-error"]',
  '[data-testid="alert-recovery-gap"]',
  '[data-testid^="recovery-gap-msg-"]',
  '[data-testid="alert-interrupted"]',
  '[data-testid="notice-bar"]',
  '[data-testid="continue-round"]',
  '[data-testid^="degrade-notice-"]',
  'div[data-testid^="error-card-"]:not([data-testid^="error-card-summary-"]):not([data-testid^="error-card-action-"])'
].join(',')
const recoveryRetrySelector = [
  '[data-testid="retry-history"]',
  '[data-testid="retry-recovery-gap"]',
  '[data-testid="continue-round"]',
  '[data-testid^="error-card-action-"]'
].join(',')
const configLocalePlaceholder = '__AI_VERIFICATION_LOCALE__'
const HISTORY_REQUEST_LIMIT = 512
const CDP_ERROR_LIMIT = 64
const NETWORK_REQUEST_LIMIT = 4096
const EVIDENCE_TAIL_LIMIT = 16
const FRONTEND_TOOL_NAMES = new Set(['navigate_to', 'select_definitions', 'confirm_step', 'set_condition', 'generative_form'])
const HOST_FORM_SCENARIOS = new Set(['tool-select-definitions', 'tool-confirm-step'])
const EXPECTED_FRONTEND_RESULT_COUNTS = new Map([
  ['tool-navigate', 1],
  ['tool-select-definitions', 4],
  ['tool-confirm-step', 3],
  ['tool-set-condition', 1]
])

class ScenarioNotRun extends Error {
  constructor(code) {
    super(code)
    this.name = 'ScenarioNotRun'
    this.code = code
  }
}

class AssertionFailure extends Error {
  constructor(code) {
    super(code)
    this.name = 'AssertionFailure'
    this.code = code
  }
}

class VerificationFailure extends Error {
  constructor(code, httpStatus = null, businessCode = null) {
    super(code)
    this.name = 'VerificationFailure'
    this.code = code
    this.httpStatus = httpStatus
    this.businessCode = businessCode
  }
}

function safeToken(value, fallback = null) {
  return typeof value === 'string' && /^[A-Za-z][A-Za-z0-9_.:-]{0,127}$/.test(value) ? value : fallback
}

function safeErrorName(error) {
  return safeToken(error?.name, 'Error')
}

function businessCode(body) {
  return safeToken(body?.code ?? body?.data?.code)
}

function safeError(error, fallbackCode = 'UNEXPECTED_ERROR') {
  return {
    errorName: safeErrorName(error),
    code: safeToken(error?.code, fallbackCode),
    httpStatus: Number.isSafeInteger(error?.httpStatus) ? error.httpStatus : null,
    businessCode: safeToken(error?.businessCode)
  }
}

function isRecord(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`
  if (isRecord(value)) {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`
  }
  return JSON.stringify(value) ?? 'null'
}

function sha256(value) {
  return createHash('sha256').update(typeof value === 'string' ? value : canonicalJson(value)).digest('hex')
}

function fnv1a32(value) {
  const text = canonicalJson(value)
  let hash = 2166136261
  for (let index = 0; index < text.length; index += 1) {
    hash ^= text.charCodeAt(index)
    hash = Math.imul(hash, 16777619)
  }
  return (hash >>> 0).toString(16)
}

function textEvidence(value) {
  return {length: Array.from(value ?? '').length, sha256: sha256(value ?? '')}
}

function isInside(parent, candidate) {
  const relative = path.relative(path.resolve(parent), path.resolve(candidate))
  return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative))
}

function outsidePath(name) {
  const raw = process.env[name]?.trim() ?? ''
  if (!path.isAbsolute(raw)) throw new Error(`${name} must be an absolute path outside the repository`)
  const resolved = path.resolve(raw)
  if (isInside(repoRoot, resolved)) throw new Error(`${name} must point outside the repository`)
  return resolved
}

function integerEnv(name, fallback, minimum, maximum) {
  const value = Number(process.env[name] ?? fallback)
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
    throw new Error(`${name} must be an integer in ${minimum}..${maximum}`)
  }
  return value
}

function httpUrl(name, {loopbackOnly = false} = {}) {
  const raw = process.env[name]?.trim() ?? ''
  let parsed
  try {
    parsed = new URL(raw)
  } catch {
    throw new Error(`${name} must be an absolute HTTP(S) URL`)
  }
  if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new Error(`${name} must not contain credentials, query or fragment`)
  }
  if (loopbackOnly && !localHosts.has(parsed.hostname)) throw new Error(`${name} must be loopback`)
  if (!localHosts.has(parsed.hostname) && process.env.EVAL_CONFIRM_REMOTE !== 'true') {
    throw new Error(`EVAL_CONFIRM_REMOTE=true is required for non-local ${name}`)
  }
  return parsed.toString().replace(/\/$/, '')
}

function loadConfig() {
  const scenario = (process.env.SCENARIO ?? '').trim()
  if (!scenarios.has(scenario) || scenario.includes(',')) {
    throw new Error(`SCENARIO must select exactly one of ${[...scenarios].join(', ')}`)
  }
  if (process.env.ROUNDS && process.env.ROUNDS !== '1') throw new Error('ROUNDS is unsupported; invoke once per scenario round')
  const locale = (process.env.LOCALE ?? '').trim()
  if (!['zh-CN', 'en-US'].includes(locale)) throw new Error('LOCALE must be zh-CN or en-US')
  for (const gate of ['EVAL_ISOLATED_ENV', 'EVAL_ALLOW_WRITES']) {
    if (process.env[gate] !== 'true') throw new Error(`${gate}=true is required`)
  }
  if (!process.env.TENANT_ID?.trim() || !process.env.USER_ID?.trim()) throw new Error('TENANT_ID and USER_ID are required')
  if (!process.env.EXECUTOR_IDENTITY?.trim()) throw new Error('EXECUTOR_IDENTITY must identify the actual executing model')
  return {
    scenario,
    locale,
    variant: process.env.RECOVERY_VARIANT?.trim() ?? '',
    historyOrder: process.env.HISTORY_ORDER?.trim() ?? '',
    pendingFixture: process.env.PENDING_FIXTURE?.trim() ?? 'standard',
    appUrl: httpUrl('APP_URL'),
    baseUrl: httpUrl('BASE_URL'),
    cdpUrl: httpUrl('CDP_URL', {loopbackOnly: true}),
    tenantId: process.env.TENANT_ID.trim(),
    userId: process.env.USER_ID.trim(),
    executorIdentity: process.env.EXECUTOR_IDENTITY.trim(),
    outDir: outsidePath('OUT_DIR'),
    stubDumpDir: process.env.STUB_DUMP_DIR?.trim() ? outsidePath('STUB_DUMP_DIR') : null,
    proxyLogDir: process.env.PROXY_LOG_DIR?.trim() ? outsidePath('PROXY_LOG_DIR') : null,
    xlsxFixture: process.env.XLSX_FIXTURE?.trim() ? outsidePath('XLSX_FIXTURE') : null,
    contentVariant: (process.env.CONTENT_VARIANT ?? 'timeline').trim(),
    bootstrapIdentity: process.env.BOOTSTRAP_IDENTITY?.trim() || null,
    timeoutMs: integerEnv('TIMEOUT_MS', 120_000, 5_000, 600_000),
    pollMs: integerEnv('POLL_MS', 50, 10, 1_000)
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
  if (isInside(resolvedRepo, target)) throw new Error('OUT_DIR target resolves inside the repository')
  const directory = path.join(target, 'ai-tool-card-recovery', config.scenario, config.locale,
    `${safeToken(config.variant, 'default')}-${new Date().toISOString().replace(/[:.]/g, '-')}-${randomUUID()}`)
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
    if (pass !== true) throw new AssertionFailure(id)
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

  async screenshot(name, data) {
    await writeFile(path.join(this.directory, name), Buffer.from(data, 'base64'))
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
        signal: AbortSignal.timeout(30_000)
      })
    } catch (error) {
      throw new VerificationFailure(label, null, safeErrorName(error))
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

  async expect(label, urlPath, options, statuses) {
    const response = await this.request(label, urlPath, options)
    if (!statuses.includes(response.status)) {
      throw new VerificationFailure(label, response.status, businessCode(response.body))
    }
    return response.body?.data ?? response.body
  }

  async createTask(title) {
    return await this.expect('task.create', '/api/tasks', {
      method: 'POST', body: JSON.stringify({type: 'EXPORT', title})
    }, [201])
  }

  async selectDefs(taskId) {
    return await this.expect('task.select-defs', `/api/tasks/${taskId}/select-defs`, {
      method: 'POST', body: JSON.stringify({defCodes: ['CURRENCY']})
    }, [200])
  }

  async setImportMode(taskId, mode) {
    return await this.expect('task.import-mode', `/api/tasks/${taskId}/import-mode`, {
      method: 'PUT', body: JSON.stringify({mode})
    }, [200])
  }

  async dataCount(defCode = 'CURRENCY') {
    const response = await this.request('data.count', `/api/data/${defCode}/count`)
    const data = response.body?.data ?? response.body
    return response.status === 200 && Number.isSafeInteger(data?.count) ? data.count : null
  }

  async setCondition(taskId, defCode = 'CURRENCY', condition = {}) {
    return await this.expect('task.condition', `/api/tasks/${taskId}/items/${defCode}/condition`, {
      method: 'PUT', body: JSON.stringify({condition})
    }, [200])
  }

  async goToStep(taskId, step) {
    return await this.expect('task.step', `/api/tasks/${taskId}/step`, {
      method: 'PUT', body: JSON.stringify({step})
    }, [200])
  }

  async task(taskId) {
    const response = await this.request('task.get', `/api/tasks/${taskId}`)
    return {status: response.status, body: response.body, value: response.body?.data ?? response.body}
  }

  async jobs(taskId) {
    return await this.expect('task.jobs', `/api/tasks/${taskId}/jobs`, {}, [200])
  }

  async createTaskTyped(title, type) {
    return await this.expect('task.create', '/api/tasks', {
      method: 'POST', body: JSON.stringify({type, title})
    }, [201])
  }

  async uploadTaskFile(taskId, bytes, filename, defCodes) {
    const form = new FormData()
    form.append('file', new Blob([bytes]), filename)
    for (const code of defCodes) form.append('defCodes', code)
    const response = await fetch(`${this.config.baseUrl}/api/tasks/${taskId}/files/upload`, {
      method: 'POST',
      headers: {'X-Tenant-Id': this.config.tenantId, 'X-User-Id': this.config.userId},
      body: form,
      redirect: 'error',
      signal: AbortSignal.timeout(60_000)
    })
    if (response.status !== 200) throw new VerificationFailure('TASK_FILE_UPLOAD_FAILED', response.status, businessCode(null))
    return response.json()
  }

  async startJobAndWait(taskId, jobType, timeoutMs = 120_000) {
    const created = await this.expect('task.job-start', `/api/tasks/${taskId}/jobs`, {
      method: 'POST', body: JSON.stringify({jobType})
    }, [201])
    const jobId = Number(created?.id)
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      const jobs = await this.jobs(taskId)
      const job = (Array.isArray(jobs) ? jobs : []).find((entry) => Number(entry.id) === jobId)
      if (job && ['COMPLETED', 'FAILED', 'CANCELLED'].includes(job.status)) return job
      await sleep(250)
    }
    throw new VerificationFailure('TASK_JOB_TIMEOUT', null, jobType)
  }


  async history(sessionId) {
    const response = await this.request('ai.history', `/api/ai/history/${encodeURIComponent(sessionId)}?latest=100`)
    const data = response.body?.data ?? response.body
    if (response.status !== 200) {
      return {status: 'UNAVAILABLE', httpStatus: response.status, errorName: 'HTTP_ERROR', businessCode: businessCode(response.body)}
    }
    if (!Array.isArray(data?.messages)) {
      return {status: 'UNAVAILABLE', httpStatus: 200, errorName: 'INVALID_HISTORY_PAYLOAD', businessCode: businessCode(response.body)}
    }
    if (data.hasMore === true || data.truncated === true) throw new ScenarioNotRun('COMPLETE_SINGLE_PAGE_HISTORY_REQUIRED')
    return {status: 'AVAILABLE', httpStatus: 200, errorName: null, businessCode: null, data}
  }

  async context(sessionId) {
    const response = await this.request('ai.context', `/api/ai/context/${encodeURIComponent(sessionId)}`)
    const data = response.body?.data ?? response.body
    if (response.status !== 200) {
      return {status: 'UNAVAILABLE', httpStatus: response.status, errorName: 'HTTP_ERROR', businessCode: businessCode(response.body)}
    }
    if (!isRecord(data) || typeof data.source !== 'string' || !Number.isSafeInteger(data.usedTokens) || data.usedTokens < 0) {
      return {status: 'UNAVAILABLE', httpStatus: 200, errorName: 'INVALID_CONTEXT_PAYLOAD', businessCode: businessCode(response.body)}
    }
    return {status: 'AVAILABLE', httpStatus: 200, errorName: null, businessCode: null, data}
  }

  async runCurrent(sessionId) {
    const response = await this.request('ai.run-current', `/api/ai/runs/current?sessionId=${encodeURIComponent(sessionId)}`)
    const data = response.body?.data ?? response.body
    if (response.status !== 200 || !isRecord(data)) {
      return {status: 'UNAVAILABLE', httpStatus: response.status, errorName: 'INVALID_RUN_CURRENT_PAYLOAD', businessCode: businessCode(response.body)}
    }
    return {status: 'AVAILABLE', httpStatus: 200, errorName: null, businessCode: null, data}
  }

  async run(runId) {
    const response = await this.request('ai.run', `/api/ai/runs/${encodeURIComponent(runId)}`)
    const data = response.body?.data ?? response.body
    if (response.status !== 200 || !isRecord(data?.snapshot)) {
      return {status: 'UNAVAILABLE', httpStatus: response.status, errorName: 'INVALID_RUN_PAYLOAD', businessCode: businessCode(response.body)}
    }
    return {status: 'AVAILABLE', httpStatus: 200, errorName: null, businessCode: null, data}
  }

  /**
   * C-138：以公开 `?lastSeq=N` 做增量续传，只读取有界帧摘要（type/seq），不保存原始 SSE。
   */
  async eventsReplay(runId, lastSeq, timeoutMs = 8_000) {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), timeoutMs)
    const frames = []
    const consume = (chunk) => {
      const dataLines = chunk.split('\n').filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trim())
      for (const line of dataLines) {
        if (line === '[DONE]') {
          frames.push({type: 'done', seq: null})
          continue
        }
        try {
          const parsed = JSON.parse(line)
          frames.push({type: typeof parsed?.type === 'string' ? parsed.type : null,
            seq: Number.isSafeInteger(parsed?.seq) ? parsed.seq : null})
        } catch {
          frames.push({type: null, seq: null})
        }
      }
    }
    try {
      const response = await fetch(`${this.config.baseUrl}/api/ai/events/${encodeURIComponent(runId)}?lastSeq=${lastSeq}`, {
        headers: {'X-Tenant-Id': this.config.tenantId, 'X-User-Id': this.config.userId, Accept: 'text/event-stream'},
        redirect: 'error',
        signal: controller.signal
      })
      if (response.status !== 200 || !response.body) {
        return {status: 'UNAVAILABLE', httpStatus: response.status, frames}
      }
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      try {
        while (true) {
          const {done, value} = await reader.read()
          if (done) break
          buffer += decoder.decode(value, {stream: true})
          let index
          while ((index = buffer.indexOf('\n\n')) >= 0) {
            consume(buffer.slice(0, index))
            buffer = buffer.slice(index + 2)
          }
          if (frames.some((frame) => ['done', 'error', 'cancelled'].includes(frame.type))) {
            await reader.cancel().catch(() => {})
            return {status: 'AVAILABLE', httpStatus: 200, frames}
          }
        }
      } finally {
        await reader.cancel().catch(() => {})
      }
      return {status: 'AVAILABLE', httpStatus: 200, frames}
    } catch (error) {
      return {status: 'ABORTED_OR_ERROR', errorName: errorName(error), frames}
    } finally {
      clearTimeout(timer)
    }
  }

  async startChat(sessionId, message) {
    const clientRequestId = `recovery-${randomUUID()}`
    const response = await fetch(`${this.config.baseUrl}/api/ai/chat`, {
      method: 'POST',
      headers: {
        'X-Tenant-Id': this.config.tenantId,
        'X-User-Id': this.config.userId,
        'Content-Type': 'application/json',
        Accept: 'text/event-stream'
      },
      body: JSON.stringify({sessionId, message, context: null, clientRequestId}),
      redirect: 'error',
      signal: AbortSignal.timeout(30_000)
    })
    if (response.status !== 200 || !response.body) throw new VerificationFailure('PUBLIC_CHAT_START_FAILED', response.status, businessCode(null))
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffered = ''
    try {
      while (buffered.length <= 256 * 1024) {
        const {done, value} = await reader.read()
        if (done) break
        buffered += decoder.decode(value, {stream: true})
        const start = /"type"\s*:\s*"start"[\s\S]*?"runId"\s*:\s*"([A-Za-z0-9_.:-]+)"/.exec(buffered)
          ?? /"runId"\s*:\s*"([A-Za-z0-9_.:-]+)"[\s\S]*?"type"\s*:\s*"start"/.exec(buffered)
        if (start) return {runId: start[1], clientRequestId, httpStatus: response.status}
      }
    } finally {
      await reader.cancel().catch(() => {})
    }
    throw new ScenarioNotRun('PUBLIC_CHAT_START_NOT_FORMED')
  }
}

function requiredHistory(evidence) {
  if (evidence.status !== 'AVAILABLE') {
    const error = new VerificationFailure('AUTHORITATIVE_HISTORY_UNAVAILABLE', evidence.httpStatus, evidence.businessCode)
    error.name = evidence.errorName
    throw error
  }
  return evidence.data
}

function taskSummary(task, jobs) {
  return {
    id: Number(task?.id ?? task?.task?.id),
    type: task?.type ?? task?.task?.type ?? null,
    currentStep: task?.currentStep ?? task?.task?.currentStep ?? null,
    status: task?.status ?? task?.task?.status ?? null,
    items: (task?.items ?? task?.task?.items ?? []).map((item) => ({
      defCode: item.defCode ?? null,
      status: item.status ?? null,
      condition: item.conditionJson == null ? null : textEvidence(String(item.conditionJson))
    })),
    jobs: (jobs ?? []).map((job) => ({id: Number(job.id), jobType: job.jobType ?? null, status: job.status ?? null}))
  }
}

async function businessSnapshot(api, relevantTaskIds = []) {
  const tasks = []
  const relevant = new Set(relevantTaskIds.filter((id) => Number.isSafeInteger(id) && id > 0))
  let total = null
  for (let page = 0; page < 100; page += 1) {
    const data = await api.expect('tasks.page', `/api/tasks?page=${page}&size=100`, {}, [200])
    if (!Array.isArray(data?.content)) throw new VerificationFailure('TASK_PAGE_CONTENT_INVALID')
    if (total === null) total = Number(data.totalElements)
    if (total !== Number(data.totalElements)) throw new VerificationFailure('TASK_PAGE_TOTAL_CHANGED')
    for (const entry of data.content) {
      const task = entry.task ?? entry
      const id = Number(task.id)
      if (!Number.isSafeInteger(id) || id <= 0) throw new VerificationFailure('TASK_ID_INVALID')
      tasks.push(taskSummary(task, await api.jobs(id)))
    }
    if (tasks.length >= total || data.content.length === 0) break
    if (page === 99) throw new ScenarioNotRun('BUSINESS_TASK_PAGE_LIMIT_EXCEEDED')
  }
  if (total === null || tasks.length !== total) throw new VerificationFailure('TASK_BASELINE_INCOMPLETE')
  tasks.sort((left, right) => left.id - right.id)
  const snapshot = {
    complete: true,
    total,
    fingerprint: sha256(tasks),
    relevantTasks: tasks.filter((task) => relevant.has(task.id))
  }
  Object.defineProperty(snapshot, 'allTasks', {value: tasks, enumerable: false})
  return snapshot
}

function relevantTaskIds(context) {
  return [...context.createdTaskIds, context.fixtureTaskId].filter((id) => Number.isSafeInteger(id) && id > 0)
}

function historySummary(history) {
  return {
    messageCount: history.messages.length,
    messages: history.messages.map((message) => ({
      type: String(message.type ?? message.role ?? '').toUpperCase(),
      entryId: message.entryId ?? null,
      runId: message.runId ?? null,
      text: textEvidence(message.text ?? message.content ?? ''),
      toolCallStates: Object.fromEntries(Object.entries(message.toolCallStates ?? {}).map(([id, state]) => [id, {
        status: state?.status ?? null,
        code: state?.code ?? state?.userTextCode ?? null
      }])),
      toolResponses: (message.toolResponses ?? []).map((response) => ({id: response.id ?? null, name: response.name ?? null}))
    }))
  }
}

function historyToolOccurrences(history, toolCallId) {
  const states = []
  const codes = []
  const responses = []
  for (const message of history.messages) {
    const state = message.toolCallStates?.[toolCallId]
    if (state) {
      states.push(state.status ?? null)
      codes.push(state.code ?? state.userTextCode ?? null)
    }
    for (const response of message.toolResponses ?? []) {
      if (response.id === toolCallId) responses.push(response.name ?? null)
    }
  }
  return {states, codes, responses}
}

const CORRECTION_PREFIX_TEXT = '纠正前缀：先保留这段已经展示的回答。'
const CORRECTION_SUFFIX_TEXT = '纠正后缀：补齐剩余回答且不得重复前缀。'
const CORRECTION_FINAL_TEXT = '最终收尾：工具结果与正文顺序完整。'
const INTERNAL_CORRECTION_MARKER = '（内部纠正，不要向用户复述本段）'
const TURN_TEXT = '命名轮蓝本：这是一段确定性文本，用于验证纯文本流的帧序完整性（start 恰 1 / delta ≥ 1 / done 恰 1），'
  + '以及 done 之后零帧的迟到帧闸门。文本按 24 字符切片发送，切片数由本串长度决定，不依赖模型。'
const PREFIX_EXPECTATIONS = new Map([
  ['text', {requests: 2, text: `${CORRECTION_PREFIX_TEXT}${CORRECTION_SUFFIX_TEXT}`, tools: []}],
  ['tool-suffix', {requests: 3, text: `${CORRECTION_PREFIX_TEXT}${CORRECTION_SUFFIX_TEXT}${CORRECTION_FINAL_TEXT}`, tools: ['list_config_defs']}],
  ['prefix-prefix', {requests: 2, text: CORRECTION_PREFIX_TEXT, tools: []}],
  ['empty-suffix-tool', {requests: 3, text: `${CORRECTION_PREFIX_TEXT}${CORRECTION_FINAL_TEXT}`, tools: ['list_config_defs']}],
  ['park-resume', {requests: 3, text: `${CORRECTION_PREFIX_TEXT}${CORRECTION_FINAL_TEXT}`, tools: ['generative_form']}],
  ['other-run-control', {requests: 1, text: TURN_TEXT, tools: []}]
])

function upstreamText(message) {
  if (typeof message?.content === 'string') return message.content
  if (Array.isArray(message?.content)) return message.content.map((part) => part?.text ?? '').join('')
  return ''
}

function upstreamMessageEvidence(message) {
  return {
    role: message?.role ?? null,
    toolCallId: message?.tool_call_id ?? null,
    content: textEvidence(upstreamText(message)),
    toolCalls: (message?.tool_calls ?? []).map((call) => ({id: call?.id ?? null, name: call?.function?.name ?? null}))
  }
}

async function readJsonEvidenceFile(file, maximumBytes) {
  const info = await stat(file)
  if (info.size > maximumBytes) throw new VerificationFailure('UPSTREAM_EVIDENCE_FILE_TOO_LARGE')
  return JSON.parse(await readFile(file, 'utf8'))
}

async function assertStubDirectoryEmpty(config) {
  if (!config.stubDumpDir) throw new Error('STUB_DUMP_DIR is required for correction-prefix')
  const existing = (await readdir(config.stubDumpDir)).filter((name) => name.startsWith('req-')).sort()
  if (existing.length > 0) throw new VerificationFailure('STUB_DUMP_DIR_NOT_EMPTY')
}

async function readUpstreamRequests(config) {
  if (!config.stubDumpDir) throw new Error('STUB_DUMP_DIR is required for correction-prefix')
  const names = (await readdir(config.stubDumpDir)).filter((name) => /^req-\d{3}\.json$/.test(name)).sort()
  if (names.length === 0) throw new ScenarioNotRun('UPSTREAM_REQUESTS_NOT_FORMED')
  if (names.length > 16) throw new VerificationFailure('UPSTREAM_REQUEST_LIMIT_EXCEEDED')
  const requests = []
  for (const name of names) {
    const id = name.slice(0, -5)
    const body = await readJsonEvidenceFile(path.join(config.stubDumpDir, name), 1024 * 1024)
    const decision = await readJsonEvidenceFile(path.join(config.stubDumpDir, `${id}.decision.json`), 1024 * 1024)
    const eventsFile = path.join(config.stubDumpDir, `${id}.events.jsonl`)
    if ((await stat(eventsFile)).size > 8 * 1024 * 1024) throw new VerificationFailure('UPSTREAM_EVENT_LIMIT_EXCEEDED')
    const eventsText = await readFile(eventsFile, 'utf8')
    const events = eventsText.split(/\r?\n/).filter((line) => line.trim() !== '').map((line) => JSON.parse(line))
    if (events.length > 4098) throw new VerificationFailure('UPSTREAM_EVENT_COUNT_EXCEEDED')
    requests.push({id, body, decision, events})
  }
  return requests
}

function prefixCount(request, prefix) {
  return request.body.messages.filter((message) => message?.role === 'assistant')
    .reduce((count, message) => count + upstreamText(message).split(prefix).length - 1, 0)
}

function correctionDirectiveCount(request) {
  return request.body.messages.filter((message) => message?.role === 'user')
    .reduce((count, message) => count + upstreamText(message).split(INTERNAL_CORRECTION_MARKER).length - 1, 0)
}

function digestMatches(value, text) {
  return value?.sha256 === sha256(text) && value?.length === Array.from(text).length
}

function assertPrefixUpstream(context, requests) {
  const {checks, config} = context
  const expected = PREFIX_EXPECTATIONS.get(config.variant)
  checks.require('prefix.upstream-request-count', requests.length === expected.requests, expected.requests, requests.map((request) => request.id))
  const requestEvidence = []
  for (const [index, request] of requests.entries()) {
    const prefixes = prefixCount(request, CORRECTION_PREFIX_TEXT)
    const directives = correctionDirectiveCount(request)
    const isControl = config.variant === 'other-run-control'
    checks.require(`prefix.request-${index + 1}-count`, isControl ? prefixes === 0 : index === 0 ? prefixes === 0 : prefixes === 1,
      isControl ? 'control has no correction prefix' : index === 0 ? 'initial request has no prior prefix' : 'each later model request has exactly one current prefix',
      {requestId: request.id, prefixes})
    checks.require(`prefix.request-${index + 1}-directive-count`, isControl ? directives === 0 : directives <= 1,
      'no internal correction directive is duplicated', {requestId: request.id, directives})
    const assistantIds = request.body.messages.filter((message) => message?.role === 'assistant')
      .flatMap((message) => message.tool_calls ?? []).map((call) => call?.id).filter(Boolean)
    const resultIds = request.body.messages.filter((message) => message?.role === 'tool')
      .map((message) => message?.tool_call_id).filter(Boolean)
    checks.require(`prefix.request-${index + 1}-tool-result-pairing`, resultIds.every((id) => assistantIds.includes(id)),
      'every role=tool result has a preceding assistant tool_call with the same ID', {requestId: request.id, assistantIds, resultIds})
    if (config.variant === 'prefix-prefix' && index === 1) {
      // 已确认规格：第二响应仍是“无工具的纯文本重复 prefix”，唯一一次纠正已发生，不存在第三次请求。
      const chunks = request.events.filter((event) => event.type === 'upstream-chunk')
      const contentChoices = chunks.flatMap((event) => event.choices ?? []).filter((choice) => choice.hasContent === true)
      const responseCalls = chunks.flatMap((event) => event.choices ?? []).flatMap((choice) => choice.toolCalls ?? [])
      checks.require('prefix.request-2-repeated-prefix-response', request.decision?.kind === 'text'
        && request.decision?.text === CORRECTION_PREFIX_TEXT && (request.decision?.tool ?? null) === null
        && contentChoices.length === 1 && digestMatches(contentChoices[0]?.content, CORRECTION_PREFIX_TEXT)
        && responseCalls.length === 0,
      'the second upstream response is exactly one repeated prefix as plain text with no tool calls', {
        decision: {
          kind: request.decision?.kind ?? null,
          text: textEvidence(request.decision?.text ?? ''),
          tool: request.decision?.tool ?? null
        },
        contentChoices: contentChoices.map((choice) => choice.content),
        responseCalls: responseCalls.map((call) => ({id: call.id ?? null, name: call.name ?? null}))
      })
    }
    if (['empty-suffix-tool', 'park-resume'].includes(config.variant)) {
      const toolAssistants = request.body.messages.filter((message) => message?.role === 'assistant' && (message.tool_calls ?? []).length > 0)
      let originalResponse = null
      if (index === 1) {
        const chunks = request.events.filter((event) => event.type === 'upstream-chunk')
        const contentChoices = chunks.flatMap((event) => event.choices ?? []).filter((choice) => choice.hasContent === true)
        const responseCalls = chunks.flatMap((event) => event.choices ?? []).flatMap((choice) => choice.toolCalls ?? [])
        const expectedTool = config.variant === 'park-resume' ? 'generative_form' : 'list_config_defs'
        checks.require(`prefix.request-${index + 1}-prefix-only-tool-assistant`, request.decision?.kind === 'tool_call'
          && request.decision?.prefixText === CORRECTION_PREFIX_TEXT && request.decision?.tool === expectedTool
          && contentChoices.length === 1 && digestMatches(contentChoices[0]?.content, CORRECTION_PREFIX_TEXT)
          && responseCalls.length === 1 && responseCalls[0]?.id && responseCalls[0]?.name === expectedTool,
        'the original second response has exactly one prefix, no suffix and one tool_call', {
          decision: {
            kind: request.decision?.kind ?? null,
            tool: request.decision?.tool ?? null,
            prefixText: textEvidence(request.decision?.prefixText ?? '')
          },
          contentChoices: contentChoices.map((choice) => choice.content),
          responseCalls: responseCalls.map((call) => ({id: call.id, name: call.name}))
        })
        originalResponse = {
          content: contentChoices.map((choice) => choice.content),
          toolCalls: responseCalls.map((call) => ({id: call.id, name: call.name}))
        }
      }
      if (index >= 2) {
        checks.require(`prefix.request-${index + 1}-sanitized-tool-assistant-empty`, toolAssistants.length > 0
          && toolAssistants.every((message) => upstreamText(message) === ''),
        'later model history sanitizes tool assistant text to empty while preserving tool_calls', toolAssistants.map((message) => ({
          toolCallIds: (message.tool_calls ?? []).map((call) => call?.id ?? null),
          text: textEvidence(upstreamText(message))
        })))
        const originalCalls = requests[1].events.filter((event) => event.type === 'upstream-chunk')
          .flatMap((event) => event.choices ?? []).flatMap((choice) => choice.toolCalls ?? []).map((call) => call.id).filter(Boolean)
        checks.require(`prefix.request-${index + 1}-sanitized-tool-id-preserved`, originalCalls.length === 1
          && assistantIds.includes(originalCalls[0]) && resultIds.includes(originalCalls[0]),
        'the original response toolCallId survives in the sanitized assistant and tool result metadata', {
          originalCalls,
          assistantIds,
          resultIds
        })
      }
      request.originalResponse = originalResponse
    }
    const outcomes = request.events.filter((event) => event.type === 'outcome')
    checks.require(`prefix.request-${index + 1}-healthy-outcome`, outcomes.length === 1 && outcomes[0].outcome === 'ok',
      'each upstream request has one successful outcome', {requestId: request.id, outcomes: outcomes.map((event) => event.outcome)})
    requestEvidence.push({
      requestId: request.id,
      variant: request.decision?.correctionVariant ?? null,
      prefixes,
      directives,
      outcome: outcomes[0]?.outcome ?? null,
      originalResponse: request.originalResponse ?? null,
      messages: request.body.messages.map(upstreamMessageEvidence)
    })
  }
  if (config.variant !== 'other-run-control') {
    checks.require('prefix.correction-directive-formed', requests.some((request) => correctionDirectiveCount(request) === 1),
      'the deterministic prefix actually triggers one internal correction request', requests.map((request) => ({
        requestId: request.id,
        directives: correctionDirectiveCount(request)
      })))
  }
  return requestEvidence
}

async function assertPrefixFinalProjection(context, requestEvidence) {
  const {checks, config} = context
  const expected = PREFIX_EXPECTATIONS.get(config.variant)
  const snapshot = await context.browser.snapshot()
  const uiText = snapshot.messages.filter((message) => message.role === 'assistant').map((message) => message.content).join('')
  if (expected.text !== null) {
    checks.require('prefix.ui-text-complete', uiText === expected.text, textEvidence(expected.text), textEvidence(uiText))
  }
  checks.require('prefix.ui-terminal', snapshot.loading === false
    && snapshot.messages.filter((message) => message.role === 'assistant').every((message) => message.done),
  'the correction run is terminal in the real browser', snapshot.messages.map((message) => ({id: message.id, done: message.done})))
  const runEvidence = await context.api.run(context.runId)
  checks.require('prefix.run-available', runEvidence.status === 'AVAILABLE', 'public run snapshot is available', runEvidence)
  if (runEvidence.status !== 'AVAILABLE') throw new AssertionFailure('PREFIX_RUN_UNAVAILABLE')
  checks.require('prefix.run-final-text-complete', runEvidence.data.snapshot.finalText === uiText,
    textEvidence(uiText), textEvidence(runEvidence.data.snapshot.finalText))
  const history = requiredHistory(await context.api.history(context.sessionId))
  const historyText = history.messages.filter((message) => String(message.type ?? message.role ?? '').toUpperCase() === 'ASSISTANT')
    .map((message) => message.text ?? message.content ?? '').join('')
  checks.require('prefix.history-text-complete', historyText === uiText, textEvidence(uiText), textEvidence(historyText))
  if (config.variant !== 'other-run-control') {
    const countPrefix = (text) => text.split(CORRECTION_PREFIX_TEXT).length - 1
    const occurrences = {
      ui: countPrefix(uiText),
      run: countPrefix(runEvidence.data.snapshot.finalText ?? ''),
      history: countPrefix(historyText)
    }
    checks.require('prefix.single-prefix-in-final-surfaces',
      occurrences.ui === 1 && occurrences.run === 1 && occurrences.history === 1,
      'UI, public run finalText and history each carry exactly one correction prefix (no duplicate display)', occurrences)
  }
  const upstreamIds = requestEvidence.flatMap((request) => request.messages.flatMap((message) => message.toolCalls.map((call) => call.id).filter(Boolean)))
  const resultIds = requestEvidence.flatMap((request) => request.messages.map((message) => message.toolCallId).filter(Boolean))
  checks.require('prefix.all-tool-results-paired', canonicalJson([...new Set(upstreamIds)].sort()) === canonicalJson([...new Set(resultIds)].sort()),
    [...new Set(upstreamIds)].sort(), [...new Set(resultIds)].sort())
  const toolNameById = new Map(requestEvidence.flatMap((request) => request.messages.flatMap((message) => message.toolCalls
    .map((call) => [call.id, call.name]))))
  for (const [index, toolCallId] of [...new Set(upstreamIds)].entries()) {
    const occurrences = historyToolOccurrences(history, toolCallId)
    const toolName = toolNameById.get(toolCallId)
    const expectedStatus = toolName === 'generative_form' ? 'FRONTEND_RESULT' : 'EXECUTED'
    checks.require(`prefix.history-tool-pair-${index + 1}`, occurrences.states.length === 1 && occurrences.responses.length === 1
      && occurrences.states[0] === expectedStatus && occurrences.responses[0] === toolName,
    'public history metadata pairs each toolCallId with its exact name, terminal state and response', {
      toolCallId,
      toolName,
      expectedStatus,
      occurrences
    })
  }
  await context.evidence.json('prefix-upstream.json', requestEvidence)
  return snapshot
}

const browserObservationSource = String.raw`
(() => {
  const state = window.__toolCardRecoveryVerification = {
    observerId: crypto.randomUUID(),
    sampleSequence: 0,
    samples: [],
    noticeSamples: [],
    copyCalls: [],
    errors: []
  }
  const recordError = (error, code) => {
    state.errors.push({code, errorName: typeof error?.name === 'string' ? error.name : 'Error'})
    if (state.errors.length > 64) state.errors.splice(0, state.errors.length - 64)
  }
  const visible = (element) => !!(element && element.getClientRects().length > 0
    && getComputedStyle(element).display !== 'none' && getComputedStyle(element).visibility === 'visible')
  const cloneJson = (value) => value === undefined ? undefined : JSON.parse(JSON.stringify(value))
  const canonicalJson = (value) => {
    if (Array.isArray(value)) return '[' + value.map(canonicalJson).join(',') + ']'
    if (value !== null && typeof value === 'object') {
      return '{' + Object.keys(value).sort().map((key) => JSON.stringify(key) + ':' + canonicalJson(value[key])).join(',') + '}'
    }
    return JSON.stringify(value) ?? 'null'
  }
  const hashValue = (value) => {
    const text = canonicalJson(value)
    let hash = 2166136261
    for (let index = 0; index < text.length; index += 1) {
      hash ^= text.charCodeAt(index)
      hash = Math.imul(hash, 16777619)
    }
    return {algorithm: 'fnv1a32', value: (hash >>> 0).toString(16)}
  }
  const appStore = () => {
    const app = document.getElementById('app')
    const pinia = app && app.__vue_app__ && app.__vue_app__.config.globalProperties.$pinia
    return pinia ? pinia._s.get('ai') : null
  }
  const operation = (selector, root = document) => [...root.querySelectorAll(selector)].map((element) => ({
    testId: element.getAttribute('data-testid'),
    disabled: element.disabled === true || element.getAttribute('aria-disabled') === 'true',
    visible: visible(element)
  }))
  const contextUsageOf = (ai) => ai.contextUsage ? {
    usedTokens: ai.contextUsage.usedTokens,
    limitTokens: ai.contextUsage.limitTokens,
    percent: ai.contextUsage.percent,
    level: ai.contextUsage.level,
    est: ai.contextUsage.est === true,
    circuit: ai.contextUsage.circuit
  } : null
  const recoveryAnimationSemantics = (spans) => {
    if (spans.length === 0) return {customClassDots: 0, animationName: null, duration: null, keyframesOpacity: false, keyframesTranslateY: false, valid: false}
    let keyframeText = ''
    for (const sheet of document.styleSheets) {
      let rules
      try { rules = sheet.cssRules } catch { continue }
      for (const rule of rules) {
        if (rule.type === 7 && (rule.name === 'recovery-loading-pulse' || rule.name.startsWith('recovery-loading-pulse-'))) keyframeText = rule.cssText
      }
    }
    const keyframesOpacity = /opacity\s*:/.test(keyframeText)
    const keyframesTranslateY = /translateY\(/.test(keyframeText)
    const first = getComputedStyle(spans[0])
    const customClassDots = spans.filter((element) => {
      const style = getComputedStyle(element)
      return element.classList.contains('recovery-loading-dot') && (element.textContent ?? '').trim() === ''
        && /^recovery-loading-pulse(-[0-9a-f]{6,8})?$/.test(style.animationName) && style.animationDuration === '1s'
    }).length
    return {
      customClassDots,
      animationName: first.animationName,
      duration: first.animationDuration,
      keyframesOpacity,
      keyframesTranslateY,
      valid: customClassDots === 3 && keyframesOpacity && keyframesTranslateY
    }
  }
  const messageDom = (message) => {
    const row = document.querySelector('[data-msg-id="' + CSS.escape(message.id) + '"]')
    const meta = row?.querySelector('[data-testid^="msg-ops-"]') ?? null
    const count = (selector) => meta ? [...meta.querySelectorAll(selector)].filter(visible).length : 0
    const breath = meta?.querySelector('[data-testid="recovery-loading"]') ?? null
    const breathVisible = visible(breath)
    const pulseSpans = breathVisible && (breath.innerText ?? '').trim() === '' ? [...breath.querySelectorAll(':scope > span')] : []
    const recoveryAnimation = recoveryAnimationSemantics(pulseSpans)
    const working = meta?.querySelector('[data-testid="loading-breath"]') ?? null
    const terminalMarkers = meta ? [...meta.querySelectorAll('[data-testid]')].filter((element) => {
      const id = element.getAttribute('data-testid') ?? ''
      return /^msg-(stopped|interrupted|truncated|cancelled|failed)-/.test(id) && visible(element)
    }).map((element) => element.getAttribute('data-testid')) : []
    const copyButtons = operation('[data-testid^="copy-msg-"]', meta ?? document.createElement('div'))
    return {
      messageId: message.id,
      metaPresent: meta !== null,
      waitingCount: count('[data-testid="park-waiting"]'),
      compactingCount: count('[data-testid="compaction-message"]'),
      timestampCount: count('[data-testid="msg-timestamp"]'),
      activityTextPresent: (breathVisible && (breath.innerText ?? '').trim().length > 0)
        || (visible(working) && (working.innerText ?? '').trim().length > 0),
      workingTextPresent: visible(working) && (working.innerText ?? '').trim().length > 0,
      pulseDotTotal: pulseSpans.length,
      recoveryAnimation,
      terminalMarkers,
      copyHintCount: count('[data-testid^="copy-hint-"]'),
      copySuccess: copyButtons.some((entry) => entry.visible && entry.testId
        && meta.querySelector('[data-testid="' + CSS.escape(entry.testId) + '"]')?.classList.contains('is-copied')),
      actionGroupVisible: visible(meta?.querySelector(':scope > div')),
      actions: {
        copy: copyButtons,
        feedback: operation('[data-testid^="feedback-up-"],[data-testid^="feedback-down-"]', meta ?? document.createElement('div')),
        edit: operation('[data-testid^="edit-msg-"]', meta ?? document.createElement('div')),
        continueRound: operation('[data-testid="continue-round"]', meta ?? document.createElement('div'))
      }
    }
  }
  let previousTools = ''
  let previousNotices = ''
  const sample = () => {
    try {
      const ai = appStore()
      if (ai) {
        const tools = ai.messages.flatMap((message) => message.parts.filter((part) => part.kind === 'tool').map((part) => ({
          messageId: message.id,
          toolCallId: part.toolCallId,
          name: part.name,
          status: part.status,
          requiresAction: part.requiresAction === true,
          decision: part.decision ?? null
        })))
        const cards = [...document.querySelectorAll('article[data-testid^="tool-card-"],article[data-testid^="pending-card-"]')]
          .filter(visible).map((element) => ({
            testId: element.getAttribute('data-testid'),
            state: element.querySelector('[data-card-state]')?.getAttribute('data-card-state') ?? null
          }))
        const app = document.getElementById('app')
        const pinia = app?.__vue_app__?.config.globalProperties.$pinia
        const workspace = pinia?._s.get('workspace')
        const workspaceState = workspace ? cloneJson({
          pageId: workspace.pageId,
          taskType: workspace.taskType,
          taskId: workspace.taskId,
          taskStatus: workspace.taskStatus,
          step: workspace.step,
          selectedDefs: workspace.selectedDefs,
          queryConditions: workspace.queryConditions,
          importMode: workspace.importMode,
          dataVersion: workspace.dataVersion
        }) : null
        const recovery = {
          restoringRecovery: ai.restoringRecovery === true,
          runPhase: ai.runPhase,
          loading: ai.loading === true,
          suspended: ai.suspended === true,
          canSend: ai.canSend === true,
          historyLoaded: ai.historyLoaded === true,
          stopped: ai.stopped === true,
          clientRequestStopped: ai.clientRequestStopped === true,
          historyProjection: {
            total: ai.historyTotal,
            offset: ai.historyOffset,
            hasMore: ai.historyHasMore === true,
            nextCursor: ai.historyNextCursor ?? null,
            revision: ai.historyRevision ?? 0,
            eventCount: (ai.historyEvents ?? []).length,
            windowEntryCount: (ai.historyWindowEntries ?? []).length,
            events: (ai.historyEvents ?? []).map((event) => ({
              eventId: event.eventId ?? null,
              runId: event.runId ?? null,
              anchorToolCallId: event.anchorToolCallId ?? null,
              atMs: event.atMs ?? null
            })),
            windowEntries: (ai.historyWindowEntries ?? []).map((entry) => ({
              entryId: entry.entryId ?? null,
              runId: entry.runId ?? null,
              type: String(entry.type ?? entry.role ?? '').toUpperCase(),
              textHash: hashValue(entry.text ?? entry.content ?? '')
            }))
          },
          manualRecovery: ai.manualRecovery ? {scope: ai.manualRecovery.scope ?? null, reason: ai.manualRecovery.reason ?? null} : null,
          contextUsage: contextUsageOf(ai),
          activeForm: ai.activeForm ? {
            toolCallId: ai.activeForm.toolCallId,
            runId: ai.activeForm.runId,
            status: ai.activeForm.status,
            values: hashValue(ai.activeForm.values ?? {}),
            fields: (ai.activeForm.form?.fields ?? []).map((field) => ({key: field.key, type: field.type}))
          } : null,
          queuedForms: (ai.queuedForms ?? []).map((entry) => ({toolCallId: entry.toolCallId, runId: entry.runId})),
          messages: ai.messages.map((message) => ({
            id: message.id,
            messageId: message.messageId ?? null,
            role: message.role,
            runId: message.runId ?? null,
            streaming: message.streaming === true,
            done: message.done === true,
            stopped: message.stopped === true,
            truncated: message.truncated === true,
            timestampPresent: message.timestamp !== undefined && message.timestamp !== null && message.timestamp !== ''
          }))
        }
        const operations = {
          send: operation('[data-testid="send-btn"]'),
          stop: operation('[data-testid="stop-btn"]'),
          continueRound: operation('[data-testid="continue-round"]'),
          newChat: operation('[data-testid="new-chat"]'),
          feedback: operation('[data-testid^="feedback-up-"],[data-testid^="feedback-down-"]'),
          edit: operation('[data-testid^="edit-msg-"]'),
          copy: operation('[data-testid^="copy-msg-"]'),
          gate: operation('[data-testid="gate-actions"] button'),
          form: operation('[data-testid="gf-submit"],[data-testid="gf-cancel"],[data-testid="gf-queue-cancel"]'),
          upload: operation('[data-testid="upload-handoff-choose"],[data-testid="upload-handoff-cancel"]'),
          sheetWrite: operation('[data-testid="sheet-preview-apply"],[data-testid="sheet-preview-cancel"],[data-testid="sheet-preview-abandon"],[data-testid="sheet-preview-ack-box"]'),
          previewLevel: operation('[data-testid^="sheet-preview-level-"]'),
          previewPagination: operation('[data-testid="sheet-preview-full"] button'),
          composer: operation('[data-testid="composer-input"]'),
          waitingCount: [...document.querySelectorAll('[data-testid="park-waiting"]')].filter(visible).length,
          workingCount: [...document.querySelectorAll('[data-testid="loading-breath"]')].filter(visible).length,
          compactingCount: [...document.querySelectorAll('[data-testid="compaction-message"]')].filter(visible).length,
          timestampCount: [...document.querySelectorAll('[data-testid="msg-timestamp"]')].filter(visible).length,
          messages: ai.messages.map(messageDom)
        }
        const serialized = JSON.stringify({tools, cards, workspace: workspaceState, recovery, operations})
        if (serialized !== previousTools) {
          previousTools = serialized
          state.sampleSequence += 1
          state.samples.push({observerId: state.observerId, sequence: state.sampleSequence,
            atMs: Date.now(), tools, cards, workspace: workspaceState, recovery, operations})
          if (state.samples.length > 2048) state.samples.splice(0, state.samples.length - 2048)
        }
      }
      const notices = [...document.querySelectorAll(${JSON.stringify(recoverySurfaceSelector)})].filter(visible)
        .map((element) => ({testId: element.getAttribute('data-testid'), text: element.innerText}))
      const serializedNotices = JSON.stringify(notices)
      if (serializedNotices !== previousNotices) {
        previousNotices = serializedNotices
        state.noticeSamples.push({atMs: Date.now(), notices})
        if (state.noticeSamples.length > 512) state.noticeSamples.splice(0, state.noticeSamples.length - 512)
      }
    } catch (error) {
      recordError(error, 'OBSERVATION_SAMPLE_FAILED')
    }
  }
  const recordCopy = (text, mechanism) => {
    state.copyCalls.push({atMs: Date.now(), mechanism, text: String(text ?? '').slice(0, 16384)})
    if (state.copyCalls.length > 64) state.copyCalls.splice(0, state.copyCalls.length - 64)
  }
  try {
    const clipboard = navigator.clipboard
    if (clipboard && typeof clipboard.writeText === 'function') {
      const original = clipboard.writeText.bind(clipboard)
      clipboard.writeText = async (text) => {
        recordCopy(text, 'navigator.clipboard.writeText')
        return await original(text)
      }
    }
  } catch (error) {
    recordError(error, 'CLIPBOARD_OBSERVER_INIT_FAILED')
  }
  try {
    const originalExecCommand = document.execCommand.bind(document)
    document.execCommand = (command, ...args) => {
      if (command === 'copy') {
        const active = document.activeElement
        recordCopy(active && 'value' in active ? active.value : '', 'document.execCommand.copy')
      }
      return originalExecCommand(command, ...args)
    }
  } catch (error) {
    recordError(error, 'LEGACY_CLIPBOARD_OBSERVER_INIT_FAILED')
  }
  try { localStorage.setItem('cm.locale', ${JSON.stringify(configLocalePlaceholder)}) } catch (error) {
    recordError(error, 'LOCALE_SETUP_FAILED')
  }
  document.addEventListener('DOMContentLoaded', () => {
    sample()
    new MutationObserver(sample).observe(document.documentElement, {subtree: true, childList: true, attributes: true, characterData: true})
  }, {once: true})
  setInterval(sample, 5)
})()
`

async function boundedFetch(url, options = {}, timeoutMs = 30_000) {
  try {
    return await fetch(url, {...options, signal: AbortSignal.timeout(timeoutMs)})
  } catch (error) {
    throw new VerificationFailure('BOUNDED_FETCH_FAILED', null, safeErrorName(error))
  }
}

class CdpPage {
  constructor(config) {
    this.config = config
    this.nextId = 0
    this.waiters = new Map()
    this.targetId = null
    this.ws = null
    this.networkRequests = []
    this.historyRequests = []
    this.contextRequests = []
    this.runCurrentRequests = []
    this.cdpErrors = []
    this.evidenceLimitExceeded = false
    // Fetch 在 Network.requestWillBeSent 之前暂停时还没有跟踪记录；注入的 503 必须暂存，
    // 等 Network 事件建记录后回填，否则证据面会出现"503 但 injectedStatus=null"的漏记。
    this.pendingInjectedFaults = new Map()
    this.historySequence = 0
    this.contextSequence = 0
    this.runCurrentSequence = 0
    this.interceptionEnabled = false
    this.interceptionTransitions = []
    this.faultMode = 'off'
    this.historyFaultsRemaining = 0
    this.contextFaultMode = 'off'
    this.contextFaultsRemaining = 0
    this.contextResponseDelayRemaining = 0
    this.delayedContextResponses = []
    this.historyResponseDelayRemaining = 0
    this.delayedHistoryResponses = []
    this.runCurrentResponseDelayRemaining = 0
    this.delayedRunCurrentResponses = []
    this.runCurrentFaultMode = 'off'
    this.runCurrentFaultsRemaining = 0
    this.chatRequestIdentities = []
    this.phase = 'initial'
  }

  setPhase(phase) {
    this.phase = phase
  }

  recordCdpError(error, code) {
    if (this.cdpErrors.length >= CDP_ERROR_LIMIT) {
      this.evidenceLimitExceeded = true
      return
    }
    this.cdpErrors.push(safeError(error, code))
  }

  async open() {
    const response = await boundedFetch(`${this.config.cdpUrl}/json/new?${encodeURIComponent(this.config.appUrl)}`, {method: 'PUT'})
    let target = null
    try {
      target = await response.json()
    } catch (error) {
      throw new VerificationFailure('CDP_TARGET_RESPONSE_INVALID', response.status, safeErrorName(error))
    }
    if (!response.ok || !target?.id || !target?.webSocketDebuggerUrl) throw new ScenarioNotRun('CDP_TARGET_CREATE_FAILED')
    this.targetId = target.id
    this.ws = new WebSocket(target.webSocketDebuggerUrl)
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        try { this.ws.close() } catch (error) { this.recordCdpError(error, 'CDP_WEBSOCKET_TIMEOUT_CLOSE_FAILED') }
        reject(new VerificationFailure('CDP_WEBSOCKET_OPEN_TIMEOUT'))
      }, 30_000)
      this.ws.onopen = () => {
        clearTimeout(timer)
        resolve()
      }
      this.ws.onerror = () => {
        clearTimeout(timer)
        reject(new VerificationFailure('CDP_WEBSOCKET_OPEN_FAILED'))
      }
      this.ws.onclose = () => {
        clearTimeout(timer)
        reject(new VerificationFailure('CDP_WEBSOCKET_CLOSED_BEFORE_OPEN'))
      }
    })
    this.ws.onmessage = (event) => this.onMessage(event)
    await this.send('Page.enable')
    await this.send('Runtime.enable')
    await this.send('Network.enable')
    await this.send('Page.addScriptToEvaluateOnNewDocument', {
      source: browserObservationSource.replace(configLocalePlaceholder, this.config.locale)
    })
    if (this.config.bootstrapIdentity) {
      // 测试环境引导：等价于“本页签此前已选定身份”（产品把身份存 sessionStorage['ai-identity']）。
      // 691d9fee 首装无身份时 adoptFreshSession 会在 sessionIdentity=null 上崩，需既有身份才能进入被测路径；
      // 首装缺陷单独作为产品 FAIL 证据，不通过本引导掩盖。
      const [tenantId, userId] = this.config.bootstrapIdentity.split(':')
      await this.send('Page.addScriptToEvaluateOnNewDocument', {
        source: `try { sessionStorage.setItem('ai-identity', JSON.stringify({tenantId: ${JSON.stringify(tenantId)}, userId: ${JSON.stringify(userId)}})) } catch (error) {}`
      })
    }
    await this.send('Page.navigate', {url: this.config.appUrl})
    await this.waitFor(`(() => {
      const app = document.getElementById('app')
      const pinia = app && app.__vue_app__ && app.__vue_app__.config.globalProperties.$pinia
      return !!(pinia && pinia._s.get('ai') && pinia._s.get('workspace'))
    })()`, 'APPLICATION_STORE_NOT_READY')
    await this.evaluate(`(() => {
      const pinia = document.getElementById('app').__vue_app__.config.globalProperties.$pinia
      pinia._s.get('ai').expanded = true
      return document.documentElement.lang
    })()`, false)
  }

  onMessage(event) {
    let message
    try {
      message = JSON.parse(event.data)
    } catch (error) {
      this.recordCdpError(error, 'CDP_MESSAGE_INVALID')
      return
    }
    if (message.id && this.waiters.has(message.id)) {
      const waiter = this.waiters.get(message.id)
      this.waiters.delete(message.id)
      clearTimeout(waiter.timer)
      if (message.error) waiter.reject(new VerificationFailure('CDP_METHOD_ERROR', null, safeToken(message.error.code)))
      else waiter.resolve(message.result)
      return
    }
    if (message.method === 'Network.requestWillBeSent') this.onRequestWillBeSent(message.params)
    if (message.method === 'Network.responseReceived') this.onResponseReceived(message.params)
    if (message.method === 'Network.loadingFinished') this.onLoadingFinished(message.params)
    if (message.method === 'Network.loadingFailed') this.onLoadingFailed(message.params)
    if (message.method === 'Fetch.requestPaused') void this.handlePaused(message.params)
  }

  onRequestWillBeSent(params) {
    try {
      const url = new URL(params.request.url)
      if (!url.pathname.startsWith('/api/')) return
      if (this.networkRequests.length >= NETWORK_REQUEST_LIMIT) {
        this.evidenceLimitExceeded = true
        this.recordCdpError(new VerificationFailure('NETWORK_EVIDENCE_LIMIT_EXCEEDED'), 'NETWORK_EVIDENCE_LIMIT_EXCEEDED')
      } else {
        this.networkRequests.push({requestId: params.requestId, method: params.request.method, path: url.pathname, phase: this.phase})
      }
      if (url.pathname === '/api/ai/chat' && params.request.method === 'POST' && this.chatRequestIdentities.length < 64) {
        try {
          const body = JSON.parse(params.request.postData ?? 'null')
          const clientRequestId = safeToken(body?.clientRequestId)
          if (clientRequestId) this.chatRequestIdentities.push({clientRequestId, phase: this.phase})
        } catch {
          this.recordCdpError(new VerificationFailure('CHAT_CLIENT_REQUEST_ID_INVALID'), 'CHAT_CLIENT_REQUEST_ID_INVALID')
        }
      }
      if (url.pathname.startsWith('/api/ai/history/')) {
        if (this.historyRequests.length >= HISTORY_REQUEST_LIMIT) {
          this.evidenceLimitExceeded = true
          this.recordCdpError(new VerificationFailure('HISTORY_EVIDENCE_LIMIT_EXCEEDED'), 'HISTORY_EVIDENCE_LIMIT_EXCEEDED')
          return
        }
        this.historySequence += 1
        this.historyRequests.push(this.attachInjectedFault({
          sequence: this.historySequence,
          requestId: params.requestId,
          phase: this.phase,
          path: url.pathname,
          action: 'OBSERVED',
          interceptionEnabledAtRequest: this.interceptionEnabled,
          injectedStatus: null,
          responseStatus: null,
          finished: false,
          errorName: null
        }))
      }
      if (url.pathname.startsWith('/api/ai/context/')) {
        if (this.contextRequests.length >= HISTORY_REQUEST_LIMIT) {
          this.evidenceLimitExceeded = true
          this.recordCdpError(new VerificationFailure('CONTEXT_EVIDENCE_LIMIT_EXCEEDED'), 'CONTEXT_EVIDENCE_LIMIT_EXCEEDED')
          return
        }
        this.contextSequence += 1
        this.contextRequests.push(this.attachInjectedFault({
          sequence: this.contextSequence,
          requestId: params.requestId,
          phase: this.phase,
          path: url.pathname,
          action: 'OBSERVED',
          interceptionEnabledAtRequest: this.interceptionEnabled,
          injectedStatus: null,
          responseStatus: null,
          finished: false,
          errorName: null
        }))
      }
      if (url.pathname === '/api/ai/runs/current') {
        if (this.runCurrentRequests.length >= HISTORY_REQUEST_LIMIT) {
          this.evidenceLimitExceeded = true
          this.recordCdpError(new VerificationFailure('RUN_CURRENT_EVIDENCE_LIMIT_EXCEEDED'), 'RUN_CURRENT_EVIDENCE_LIMIT_EXCEEDED')
          return
        }
        this.runCurrentSequence += 1
        this.runCurrentRequests.push(this.attachInjectedFault({
          sequence: this.runCurrentSequence,
          requestId: params.requestId,
          phase: this.phase,
          path: url.pathname,
          action: 'OBSERVED',
          interceptionEnabledAtRequest: this.interceptionEnabled,
          injectedStatus: null,
          responseStatus: null,
          finished: false,
          errorName: null
        }))
      }
    } catch (error) {
      this.recordCdpError(error, 'NETWORK_REQUEST_EVIDENCE_FAILED')
    }
  }

  attachInjectedFault(record) {
    const pendingFault = this.pendingInjectedFaults.get(record.requestId) ?? null
    if (!pendingFault) return record
    this.pendingInjectedFaults.delete(record.requestId)
    record.action = pendingFault.action
    record.injectedStatus = pendingFault.injectedStatus
    return record
  }

  historyRecord(requestId) {
    return this.historyRequests.find((record) => record.requestId === requestId) ?? null
  }

  contextRecord(requestId) {
    return this.contextRequests.find((record) => record.requestId === requestId) ?? null
  }

  runCurrentRecord(requestId) {
    return this.runCurrentRequests.find((record) => record.requestId === requestId) ?? null
  }

  apiRecord(requestId) {
    return this.historyRecord(requestId) ?? this.contextRecord(requestId) ?? this.runCurrentRecord(requestId)
  }

  onResponseReceived(params) {
    const record = this.apiRecord(params.requestId)
    if (record) record.responseStatus = params.response.status
  }

  onLoadingFinished(params) {
    const record = this.apiRecord(params.requestId)
    if (record) record.finished = true
  }

  onLoadingFailed(params) {
    const record = this.apiRecord(params.requestId)
    if (record) {
      record.finished = true
      record.errorName = safeToken(params.errorText, 'NETWORK_LOADING_FAILED')
    }
  }

  send(method, params = {}) {
    const id = ++this.nextId
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.waiters.delete(id)
        reject(new VerificationFailure('CDP_METHOD_TIMEOUT', null, safeToken(method)))
      }, 30_000)
      this.waiters.set(id, {resolve, reject, timer})
      try {
        this.ws.send(JSON.stringify({id, method, params}))
      } catch (error) {
        clearTimeout(timer)
        this.waiters.delete(id)
        reject(new VerificationFailure('CDP_SEND_FAILED', null, safeErrorName(error)))
      }
    })
  }

  async evaluate(expression, awaitPromise = true) {
    const result = await this.send('Runtime.evaluate', {
      expression,
      returnByValue: true,
      awaitPromise,
      userGesture: true
    })
    if (result.exceptionDetails) {
      const exceptionName = safeToken(result.exceptionDetails.exception?.name ?? result.exceptionDetails.exception?.className, 'BrowserException')
      throw new VerificationFailure('BROWSER_EVALUATION_FAILED', null, exceptionName)
    }
    return result.result.value
  }

  async waitFor(expression, code, timeoutMs = this.config.timeoutMs, awaitPromise = false) {
    const deadline = Date.now() + timeoutMs
    let lastError = null
    while (Date.now() < deadline) {
      try {
        const value = await this.evaluate(expression, awaitPromise)
        if (value) return value
      } catch (error) {
        lastError = error
      }
      await sleep(this.config.pollMs)
    }
    if (lastError) this.recordCdpError(lastError, 'WAIT_EVALUATION_FAILED')
    throw new AssertionFailure(code)
  }

  async reload({allowRecoveryFailure = false, waitForHistory = true} = {}) {
    await this.send('Page.reload', {ignoreCache: true})
    await this.waitFor(`(() => {
      const app = document.getElementById('app')
      const pinia = app && app.__vue_app__ && app.__vue_app__.config.globalProperties.$pinia
      const ai = pinia && pinia._s.get('ai')
      if (!ai) return false
      if (!${waitForHistory}) return true
      const recoveryFailed = ${allowRecoveryFailure} && (ai.historyError != null || ai.currentError != null)
      return (ai.historyLoaded && !ai.loading) || recoveryFailed
    })()`, 'RELOAD_NOT_READY')
    await this.waitFor(`(() => {
      const app = document.getElementById('app')
      const ai = app?.__vue_app__?.config.globalProperties.$pinia?._s.get('ai')
      if (!ai) return false
      ai.expanded = true
      return true
    })()`, 'AI_PANEL_EXPAND_NOT_READY')
  }

  async click(selector) {
    const center = await this.waitFor(`(() => {
      const element = document.querySelector(${JSON.stringify(selector)})
      if (!element || element.disabled || element.getAttribute('aria-disabled') === 'true') return null
      element.scrollIntoView({block: 'center', inline: 'center'})
      const rect = element.getBoundingClientRect()
      if (rect.width <= 0 || rect.height <= 0) return null
      const x = rect.x + rect.width / 2
      const y = rect.y + rect.height / 2
      const hit = document.elementFromPoint(x, y)
      return hit && (hit === element || element.contains(hit)) ? {x, y} : null
    })()`, 'CLICK_TARGET_NOT_INTERACTABLE')
    await this.send('Input.dispatchMouseEvent', {type: 'mousePressed', ...center, button: 'left', buttons: 1, clickCount: 1})
    await this.send('Input.dispatchMouseEvent', {type: 'mouseReleased', ...center, button: 'left', buttons: 0, clickCount: 1})
  }

  async messageState() {
    return await this.evaluate(`(() => {
      const app = document.getElementById('app')
      const pinia = app && app.__vue_app__ && app.__vue_app__.config.globalProperties.$pinia
      const ai = pinia && pinia._s.get('ai')
      const input = document.querySelector('[data-testid="composer-input"]')
      return ai ? {
        messages: ai.messages.length,
        loading: ai.loading === true,
        runId: ai.activeRunId ?? null,
        historyLoaded: ai.historyLoaded === true,
        restoring: ai.restoringRecovery === true,
        draft: input && typeof input.value === 'string' ? input.value : null
      } : null
    })()`, false)
  }

  async waitForChatAcceptance(chatBaseline, messageBaseline, code, timeoutMs) {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      if (this.requestCounts().chat > chatBaseline) return {via: 'chat'}
      const state = await this.messageState()
      if (state && (state.messages > messageBaseline || state.loading || state.runId)) return {via: 'store', ...state}
      await sleep(this.config.pollMs)
    }
    throw new AssertionFailure(code)
  }

  async typeAndSend(prompt) {
    await this.evaluate(`document.querySelector('[data-testid="composer-input"]').focus()`, false)
    const draft = (await this.messageState())?.draft ?? ''
    if (draft !== prompt) {
      await this.evaluate(`(() => {
        const element = document.querySelector('[data-testid="composer-input"]')
        if (!element) return false
        if (typeof element.select === 'function') element.select()
        return true
      })()`, false)
      await this.send('Input.insertText', {text: prompt})
    }
    await this.click('[data-testid="send-btn"]')
  }

  async sendPrompt(prompt) {
    await this.waitFor(`(() => {
      const app = document.getElementById('app')
      const pinia = app && app.__vue_app__ && app.__vue_app__.config.globalProperties.$pinia
      const ai = pinia && pinia._s.get('ai')
      return !!ai && ai.historyLoaded === true && ai.loading === false
        && ai.restoringRecovery === false
    })()`, 'COMPOSER_NOT_READY', 30_000)
    const chatBaseline = this.requestCounts().chat
    const messageBaseline = (await this.messageState())?.messages ?? 0
    await this.typeAndSend(prompt)
    try {
      await this.waitForChatAcceptance(chatBaseline, messageBaseline, 'CHAT_NOT_ACCEPTED', 15_000)
    } catch (error) {
      if (!(error instanceof AssertionFailure)) throw error
      const state = await this.messageState()
      if (this.requestCounts().chat !== chatBaseline || !state || state.messages > messageBaseline
        || state.loading || state.runId || state.draft !== prompt) {
        throw error
      }
      await this.typeAndSend(prompt)
      await this.waitForChatAcceptance(chatBaseline, messageBaseline, 'CHAT_NOT_ACCEPTED_AFTER_RETRY', 30_000)
    }
  }

  async snapshot() {
    return await this.waitFor(`(() => {
      const app = document.getElementById('app')
      const pinia = app?.__vue_app__?.config.globalProperties.$pinia
      const ai = pinia?._s.get('ai')
      const workspace = pinia?._s.get('workspace')
      if (!ai || !workspace) return null
      const visible = (element) => !!(element && (element.offsetParent !== null || element.getClientRects().length > 0))
      const hashValue = (value) => {
        const text = JSON.stringify(value ?? null)
        let hash = 2166136261
        for (let index = 0; index < text.length; index += 1) {
          hash ^= text.charCodeAt(index)
          hash = Math.imul(hash, 16777619)
        }
        return {algorithm: 'fnv1a32', value: (hash >>> 0).toString(16)}
      }
      return {
        sessionId: ai.sessionId,
        activeRunId: ai.activeRunId,
        loading: ai.loading === true,
        restoringRecovery: ai.restoringRecovery === true,
        runPhase: ai.runPhase,
        suspended: ai.suspended === true,
        canSend: ai.canSend === true,
        historyLoaded: ai.historyLoaded === true,
        stopped: ai.stopped === true,
        clientRequestStopped: ai.clientRequestStopped === true,
        historyProjection: {
          total: ai.historyTotal,
          offset: ai.historyOffset,
          hasMore: ai.historyHasMore === true,
          nextCursor: ai.historyNextCursor ?? null,
          revision: ai.historyRevision ?? 0,
          eventCount: (ai.historyEvents ?? []).length,
          windowEntryCount: (ai.historyWindowEntries ?? []).length,
          events: (ai.historyEvents ?? []).map((event) => ({
            eventId: event.eventId ?? null,
            runId: event.runId ?? null,
            anchorToolCallId: event.anchorToolCallId ?? null,
            atMs: event.atMs ?? null
          })),
          windowEntries: (ai.historyWindowEntries ?? []).map((entry) => ({
            entryId: entry.entryId ?? null,
            runId: entry.runId ?? null,
            type: String(entry.type ?? entry.role ?? '').toUpperCase(),
            textHash: hashValue(entry.text ?? entry.content ?? '')
          }))
        },
        manualRecovery: ai.manualRecovery ? {scope: ai.manualRecovery.scope ?? null, reason: ai.manualRecovery.reason ?? null} : null,
        contextUsage: ai.contextUsage ? {
          usedTokens: ai.contextUsage.usedTokens,
          limitTokens: ai.contextUsage.limitTokens,
          percent: ai.contextUsage.percent,
          level: ai.contextUsage.level,
          est: ai.contextUsage.est === true,
          circuit: ai.contextUsage.circuit
        } : null,
        activeForm: ai.activeForm ? {
          toolCallId: ai.activeForm.toolCallId,
          runId: ai.activeForm.runId,
          status: ai.activeForm.status,
          values: ai.activeForm.values ?? {},
          fields: (ai.activeForm.form?.fields ?? []).map((field) => ({key: field.key, type: field.type}))
        } : null,
        queuedForms: (ai.queuedForms ?? []).map((entry) => ({toolCallId: entry.toolCallId, runId: entry.runId})),
        locale: document.documentElement.lang,
        messages: ai.messages.map((message) => ({
          id: message.id,
          messageId: message.messageId ?? null,
          role: message.role,
          runId: message.runId ?? null,
          content: message.content ?? '',
          streaming: message.streaming === true,
          done: message.done === true,
          stopped: message.stopped === true,
          truncated: message.truncated === true,
          timestampPresent: message.timestamp !== undefined && message.timestamp !== null && message.timestamp !== '',
          parts: message.parts.map((part) => part.kind === 'tool' ? {
            kind: part.kind,
            entryId: part.entryId ?? null,
            seq: part.seq ?? null,
            seg: part.seg,
            toolCallId: part.toolCallId,
            name: part.name,
            kind2: part.kind2 ?? null,
            status: part.status,
            userTextCode: part.userTextCode ?? null,
            args: part.args ?? null,
            result: part.result ?? null,
            reason: part.reason ?? null,
            submittedValues: part.submittedValues ?? null,
            requiresAction: part.requiresAction === true,
            decision: part.decision ?? null
          } : {
            kind: part.kind,
            entryId: part.entryId ?? null,
            seq: part.seq ?? null,
            seg: part.seg,
            text: part.text ?? '',
            eventType: part.eventType ?? null,
            eventId: part.eventId ?? null
          })
        })),
        workspace: {
          pageId: workspace.pageId,
          taskId: workspace.taskId,
          step: workspace.step,
          selectedDefs: [...(workspace.selectedDefs ?? [])],
          queryConditions: workspace.queryConditions ?? {}
        },
        cards: [...document.querySelectorAll('article[data-testid]')].filter((element) => {
          const id = element.getAttribute('data-testid') ?? ''
          return visible(element) && (id.startsWith('tool-card-') || id.startsWith('pending-card-') || id === 'hitl-receipt')
        }).map((element) => ({
          testId: element.getAttribute('data-testid'),
          bodyTestId: element.querySelector('[data-testid^="ro-body-"],[data-testid^="ro-form-"]')?.getAttribute('data-testid') ?? null,
          state: element.querySelector('[data-card-state]')?.getAttribute('data-card-state') ?? null,
          title: element.querySelector('[data-testid^="card-title-"]')?.innerText ?? null,
          // contentText 取 textContent（含折叠/隐藏文案），用于内容散列；text 取 innerText，仅用于可见文案扫描。
          contentText: element.textContent ?? '',
          text: element.innerText
        })),
        recoverySurfaces: [...document.querySelectorAll(${JSON.stringify(recoverySurfaceSelector)})].filter(visible)
          .map((element) => ({testId: element.getAttribute('data-testid'), text: element.innerText})),
        recoveryRetries: [...document.querySelectorAll(${JSON.stringify(recoveryRetrySelector)})].filter(visible)
          .map((element) => ({
            testId: element.getAttribute('data-testid'),
            text: element.innerText,
            disabled: element.disabled === true || element.getAttribute('aria-disabled') === 'true'
          })),
        observation: window.__toolCardRecoveryVerification ?? null
      }
    })()`, 'SNAPSHOT_NOT_READY')
  }

  recordInterceptionTransition(enabled, proof) {
    if (this.interceptionTransitions.length >= EVIDENCE_TAIL_LIMIT) {
      this.evidenceLimitExceeded = true
      return
    }
    this.interceptionTransitions.push({phase: this.phase, enabled, proof})
  }

  async syncFaultInterception() {
    const patterns = []
    if (this.faultMode !== 'off') patterns.push({urlPattern: '*/api/ai/history/*', requestStage: 'Request'})
    if (this.contextFaultMode !== 'off') patterns.push({urlPattern: '*/api/ai/context/*', requestStage: 'Request'})
    if (this.runCurrentFaultMode !== 'off') patterns.push({urlPattern: '*/api/ai/runs/current*', requestStage: 'Request'})
    if (this.contextResponseDelayRemaining > 0 || this.delayedContextResponses.length > 0) {
      patterns.push({urlPattern: '*/api/ai/context/*', requestStage: 'Response'})
    }
    if (this.historyResponseDelayRemaining > 0 || this.delayedHistoryResponses.length > 0) {
      patterns.push({urlPattern: '*/api/ai/history/*', requestStage: 'Response'})
    }
    if (this.runCurrentResponseDelayRemaining > 0 || this.delayedRunCurrentResponses.length > 0) {
      patterns.push({urlPattern: '*/api/ai/runs/current*', requestStage: 'Response'})
    }
    if (patterns.length === 0) {
      await this.send('Fetch.disable')
      this.interceptionEnabled = false
      this.recordInterceptionTransition(false, 'FETCH_DISABLE_COMPLETED')
      return
    }
    await this.send('Fetch.enable', {patterns})
    this.interceptionEnabled = true
    this.recordInterceptionTransition(true, 'FETCH_ENABLE_COMPLETED')
  }

  async delayNextContextResponse() {
    if (this.contextResponseDelayRemaining > 0 || this.delayedContextResponses.length > 0) {
      throw new AssertionFailure('CONTEXT_RESPONSE_DELAY_ALREADY_ACTIVE')
    }
    this.contextResponseDelayRemaining = 1
    try {
      await this.syncFaultInterception()
    } catch {
      this.contextResponseDelayRemaining = 0
      throw new ScenarioNotRun('CONTEXT_RESPONSE_DELAY_UNSUPPORTED')
    }
  }

  async waitForContextResponseHeld(phase) {
    const deadline = Date.now() + this.config.timeoutMs
    while (Date.now() < deadline) {
      const held = this.contextRequests.find((record) => record.phase === phase && record.delayedResponse === true && !record.finished)
      if (held) {
        return {
          phase,
          sequence: held.sequence,
          responseStatus: held.responseStatus,
          action: held.action,
          delayedResponse: true
        }
      }
      await sleep(this.config.pollMs)
    }
    throw new ScenarioNotRun('CONTEXT_RESPONSE_DELAY_NOT_FORMED')
  }

  async releaseDelayedContextResponse() {
    const held = this.delayedContextResponses.shift()
    if (!held) throw new AssertionFailure('CONTEXT_DELAYED_RESPONSE_NOT_HELD')
    await this.send('Fetch.continueRequest', {requestId: held.pauseId})
    const record = this.contextRecord(held.requestId)
    if (record) {
      record.action = 'CONTINUE_UNCHANGED'
      record.delayedResponseReleased = true
    }
    await this.syncFaultInterception()
  }

  async delayNextHistoryResponse() {
    if (this.historyResponseDelayRemaining > 0 || this.delayedHistoryResponses.length > 0) {
      throw new AssertionFailure('HISTORY_RESPONSE_DELAY_ALREADY_ACTIVE')
    }
    this.historyResponseDelayRemaining = 1
    try {
      await this.syncFaultInterception()
    } catch {
      this.historyResponseDelayRemaining = 0
      throw new ScenarioNotRun('HISTORY_RESPONSE_DELAY_UNSUPPORTED')
    }
  }

  async waitForHistoryResponseHeld(phase) {
    const deadline = Date.now() + this.config.timeoutMs
    while (Date.now() < deadline) {
      const held = this.historyRequests.find((record) => record.phase === phase && record.delayedResponse === true && !record.finished)
      if (held) return {phase, sequence: held.sequence, responseStatus: held.responseStatus, action: held.action, delayedResponse: true}
      await sleep(this.config.pollMs)
    }
    throw new ScenarioNotRun('HISTORY_RESPONSE_DELAY_NOT_FORMED')
  }

  async releaseDelayedHistoryResponse() {
    const held = this.delayedHistoryResponses.shift()
    if (!held) throw new AssertionFailure('HISTORY_DELAYED_RESPONSE_NOT_HELD')
    await this.send('Fetch.continueRequest', {requestId: held.pauseId})
    const record = this.historyRecord(held.requestId)
    if (record) {
      record.action = 'CONTINUE_UNCHANGED'
      record.delayedResponseReleased = true
    }
    await this.syncFaultInterception()
  }

  async delayNextRunCurrentResponse() {
    if (this.runCurrentResponseDelayRemaining > 0 || this.delayedRunCurrentResponses.length > 0) {
      throw new AssertionFailure('RUN_CURRENT_RESPONSE_DELAY_ALREADY_ACTIVE')
    }
    this.runCurrentResponseDelayRemaining = 1
    try {
      await this.syncFaultInterception()
    } catch {
      this.runCurrentResponseDelayRemaining = 0
      throw new ScenarioNotRun('RUN_CURRENT_RESPONSE_DELAY_UNSUPPORTED')
    }
  }

  async waitForRunCurrentResponseHeld(phase) {
    const deadline = Date.now() + this.config.timeoutMs
    while (Date.now() < deadline) {
      const held = this.runCurrentRequests.find((record) => record.phase === phase && record.delayedResponse === true && !record.finished)
      if (held) return {phase, sequence: held.sequence, responseStatus: held.responseStatus, action: held.action, delayedResponse: true}
      await sleep(this.config.pollMs)
    }
    throw new ScenarioNotRun('RUN_CURRENT_RESPONSE_DELAY_NOT_FORMED')
  }

  async releaseDelayedRunCurrentResponse() {
    const held = this.delayedRunCurrentResponses.shift()
    if (!held) throw new AssertionFailure('RUN_CURRENT_DELAYED_RESPONSE_NOT_HELD')
    await this.send('Fetch.continueRequest', {requestId: held.pauseId})
    const record = this.runCurrentRecord(held.requestId)
    if (record) {
      record.action = 'CONTINUE_UNCHANGED'
      record.delayedResponseReleased = true
    }
    await this.syncFaultInterception()
  }

  async setHistoryFault(mode) {
    this.faultMode = mode
    this.historyFaultsRemaining = mode === 'once' ? 1 : mode === 'always' ? -1 : 0
    await this.syncFaultInterception()
  }

  async setContextFault(mode) {
    this.contextFaultMode = mode
    this.contextFaultsRemaining = mode === 'once' ? 1 : mode === 'always' ? -1 : 0
    await this.syncFaultInterception()
  }

  async setRunCurrentFault(mode) {
    this.runCurrentFaultMode = mode
    this.runCurrentFaultsRemaining = mode === 'once' ? 1 : mode === 'always' ? -1 : 0
    await this.syncFaultInterception()
  }

  async handlePaused(params) {
    const requestId = params.networkId ?? params.requestId
    const history = this.historyRecord(requestId)
    const context = this.contextRecord(requestId)
    const runCurrent = this.runCurrentRecord(requestId)
    const record = history ?? context ?? runCurrent
    if (record) record.interceptionEnabledAtPause = this.interceptionEnabled
    // Fetch 在 Request 阶段可能先于 Network.requestWillBeSent 暂停；此时没有跟踪记录，
    // 必须按 URL 归类，否则刷新后的第一条 history 会漏注入 503（表现为 OBSERVED 200）。
    const requestUrl = params.request?.url ?? ''
    const historyKind = !!history || /\/api\/ai\/history\//.test(requestUrl)
    const contextKind = !!context || /\/api\/ai\/context\//.test(requestUrl)
    const runCurrentKind = !!runCurrent || /\/api\/ai\/runs\/current/.test(requestUrl)
    const shouldFault = historyKind
      ? this.faultMode === 'always' || (this.faultMode === 'once' && this.historyFaultsRemaining > 0)
      : runCurrentKind
        ? this.runCurrentFaultMode === 'always' || (this.runCurrentFaultMode === 'once' && this.runCurrentFaultsRemaining > 0)
        : contextKind
          ? this.contextFaultMode === 'always' || (this.contextFaultMode === 'once' && this.contextFaultsRemaining > 0)
          : false
    try {
      if (context && params.responseStatusCode !== undefined && this.contextResponseDelayRemaining > 0) {
        this.contextResponseDelayRemaining -= 1
        context.action = 'DELAYED_RESPONSE'
        context.delayedResponse = true
        context.responseStatus = params.responseStatusCode
        this.delayedContextResponses.push({pauseId: params.requestId, requestId, phase: context.phase})
        return
      }
      if (history && params.responseStatusCode !== undefined && this.historyResponseDelayRemaining > 0) {
        this.historyResponseDelayRemaining -= 1
        history.action = 'DELAYED_RESPONSE'
        history.delayedResponse = true
        history.responseStatus = params.responseStatusCode
        this.delayedHistoryResponses.push({pauseId: params.requestId, requestId, phase: history.phase})
        return
      }
      if (runCurrent && params.responseStatusCode !== undefined && this.runCurrentResponseDelayRemaining > 0) {
        this.runCurrentResponseDelayRemaining -= 1
        runCurrent.action = 'DELAYED_RESPONSE'
        runCurrent.delayedResponse = true
        runCurrent.responseStatus = params.responseStatusCode
        this.delayedRunCurrentResponses.push({pauseId: params.requestId, requestId, phase: runCurrent.phase})
        return
      }
      if (shouldFault) {
        if (historyKind && this.historyFaultsRemaining > 0) this.historyFaultsRemaining -= 1
        if (contextKind && this.contextFaultsRemaining > 0) this.contextFaultsRemaining -= 1
        if (runCurrentKind && this.runCurrentFaultsRemaining > 0) this.runCurrentFaultsRemaining -= 1
        if (record) {
          record.action = 'HTTP_503'
          record.injectedStatus = 503
        } else {
          // Fetch 先于 Network.requestWillBeSent 暂停：记录尚不存在，按 networkId 暂存，
          // 由 attachInjectedFault 在建记录时回填（否则 503 会以 injectedStatus=null 落证据）。
          this.pendingInjectedFaults.set(requestId, {action: 'HTTP_503', injectedStatus: 503})
        }
        await this.send('Fetch.fulfillRequest', {
          requestId: params.requestId,
          responseCode: 503,
          responseHeaders: [{name: 'Content-Type', value: 'application/json; charset=utf-8'}],
          body: Buffer.from(JSON.stringify({code: runCurrentKind ? 'AI_CURRENT_UNAVAILABLE' : contextKind ? 'AI_CONTEXT_UNAVAILABLE' : 'AI_HISTORY_UNAVAILABLE', data: null})).toString('base64')
        })
      } else {
        if (record) record.action = 'CONTINUE_UNCHANGED'
        await this.send('Fetch.continueRequest', {requestId: params.requestId})
      }
    } catch (error) {
      if (record) record.errorName = safeErrorName(error)
      this.recordCdpError(error, context ? 'CONTEXT_FAULT_INTERCEPTION_FAILED' : 'HISTORY_FAULT_INTERCEPTION_FAILED')
    }
  }

  requestPhase(records, phase) {
    const selected = records.filter((record) => record.phase === phase)
    const isSuccess = (record) => ['CONTINUE_UNCHANGED', 'OBSERVED'].includes(record.action)
      && record.responseStatus === 200 && record.finished && record.errorName === null
    return {
      phase,
      count: selected.length,
      faultCount: selected.filter((record) => record.injectedStatus === 503).length,
      successCount: selected.filter(isSuccess).length,
      uninterceptedSuccessCount: selected.filter((record) => isSuccess(record)
        && record.action === 'OBSERVED' && record.interceptionEnabledAtRequest === false).length,
      pendingCount: selected.filter((record) => !record.finished).length,
      delayedResponseCount: selected.filter((record) => record.delayedResponse === true).length,
      delayedResponseReleasedCount: selected.filter((record) => record.delayedResponseReleased === true).length,
      statuses: [...new Set(selected.map((record) => record.responseStatus))],
      first503Sequence: selected.find((record) => record.responseStatus === 503)?.sequence ?? null,
      firstReal200Sequence: selected.find(isSuccess)?.sequence ?? null,
      interceptionEnabled: this.interceptionEnabled,
      records: selected.slice(-EVIDENCE_TAIL_LIMIT)
    }
  }

  historyPhase(phase) {
    return this.requestPhase(this.historyRequests, phase)
  }

  contextPhase(phase) {
    return this.requestPhase(this.contextRequests, phase)
  }

  runCurrentPhase(phase) {
    return this.requestPhase(this.runCurrentRequests, phase)
  }

  interceptionEvidence() {
    return {
      enabled: this.interceptionEnabled,
      transitions: this.interceptionTransitions.slice(-EVIDENCE_TAIL_LIMIT)
    }
  }

  async waitForHistoryPhase(phase, {exactFaultCount = null, minimumSuccessCount = 0, minimumFaultCount = 0} = {}) {
    const deadline = Date.now() + this.config.timeoutMs
    let summary = this.historyPhase(phase)
    while (Date.now() < deadline) {
      summary = this.historyPhase(phase)
      const faultReady = exactFaultCount === null ? summary.faultCount >= minimumFaultCount : summary.faultCount === exactFaultCount
      if (summary.count > 0 && faultReady && summary.successCount >= minimumSuccessCount && summary.pendingCount === 0) return summary
      await sleep(this.config.pollMs)
    }
    throw new AssertionFailure('HISTORY_NETWORK_TERMINAL_EVIDENCE_MISSING')
  }

  async waitForContextPhase(phase, {exactFaultCount = null, minimumSuccessCount = 0, minimumFaultCount = 0, allowPending = false} = {}) {
    const deadline = Date.now() + this.config.timeoutMs
    let summary = this.contextPhase(phase)
    while (Date.now() < deadline) {
      summary = this.contextPhase(phase)
      const faultReady = exactFaultCount === null ? summary.faultCount >= minimumFaultCount : summary.faultCount === exactFaultCount
      if (summary.count > 0 && faultReady && summary.successCount >= minimumSuccessCount
        && (allowPending || summary.pendingCount === 0)) return summary
      await sleep(this.config.pollMs)
    }
    throw new AssertionFailure('CONTEXT_NETWORK_TERMINAL_EVIDENCE_MISSING')
  }

  async waitForRunCurrentPhase(phase, {exactFaultCount = null, minimumFaultCount = 0, minimumSuccessCount = 0, allowPending = false} = {}) {
    const deadline = Date.now() + this.config.timeoutMs
    let summary = this.runCurrentPhase(phase)
    while (Date.now() < deadline) {
      summary = this.runCurrentPhase(phase)
      const faultReady = exactFaultCount === null ? summary.faultCount >= minimumFaultCount : summary.faultCount === exactFaultCount
      if (summary.count > 0 && faultReady && summary.successCount >= minimumSuccessCount
        && (allowPending || summary.pendingCount === 0)) return summary
      await sleep(this.config.pollMs)
    }
    throw new AssertionFailure('RUN_CURRENT_NETWORK_TERMINAL_EVIDENCE_MISSING')
  }

  async waitForChatClientIdentity() {
    const deadline = Date.now() + this.config.timeoutMs
    while (Date.now() < deadline) {
      const identity = this.chatRequestIdentities.at(-1)
      if (identity) return identity
      await sleep(this.config.pollMs)
    }
    throw new ScenarioNotRun('CHAT_CLIENT_REQUEST_ID_NOT_CAPTURED')
  }

  requestCounts() {
    const count = (method, suffix) => this.networkRequests.filter((request) => request.method === method && request.path === suffix).length
    const countPrefix = (method, prefix) => this.networkRequests.filter((request) => request.method === method && request.path.startsWith(prefix)).length
    return {
      chat: count('POST', '/api/ai/chat'),
      frontendToolResult: count('POST', '/api/ai/frontend-tool-result'),
      confirm: count('POST', '/api/ai/confirm'),
      feedback: count('POST', '/api/ai/feedback'),
      events: countPrefix('GET', '/api/ai/events/'),
      resume: this.networkRequests.filter((request) => request.method === 'POST' && request.path.endsWith('/resume')).length
    }
  }

  async close() {
    const result = {status: 'COMPLETE', targetId: this.targetId, closeHttpStatus: null,
      targetAbsent: this.targetId === null, webSocketClosed: false, errors: []}
    if (this.faultMode !== 'off' || this.contextFaultMode !== 'off' || this.runCurrentFaultMode !== 'off'
      || this.contextResponseDelayRemaining > 0 || this.delayedContextResponses.length > 0
      || this.historyResponseDelayRemaining > 0 || this.delayedHistoryResponses.length > 0
      || this.runCurrentResponseDelayRemaining > 0 || this.delayedRunCurrentResponses.length > 0) {
      try {
        await this.send('Fetch.disable')
      } catch (error) {
        result.errors.push(safeError(error, 'FETCH_DISABLE_FAILED'))
      }
    }
    if (this.ws && this.ws.readyState !== WebSocket.CLOSED) {
      try {
        await new Promise((resolve, reject) => {
          const timer = setTimeout(() => reject(new VerificationFailure('CDP_WEBSOCKET_CLOSE_TIMEOUT')), 5_000)
          this.ws.addEventListener('close', () => {
            clearTimeout(timer)
            resolve()
          }, {once: true})
          this.ws.close()
        })
        result.webSocketClosed = true
      } catch (error) {
        result.errors.push(safeError(error, 'CDP_WEBSOCKET_CLOSE_FAILED'))
      }
    } else {
      result.webSocketClosed = true
    }
    if (this.targetId) {
      try {
        const response = await boundedFetch(`${this.config.cdpUrl}/json/close/${this.targetId}`)
        result.closeHttpStatus = response.status
        if (!response.ok) result.errors.push({errorName: 'HTTP_ERROR', code: 'CDP_TARGET_CLOSE_HTTP_ERROR', httpStatus: response.status, businessCode: null})
        let listStatus = null
        const deadline = Date.now() + 5_000
        do {
          const listResponse = await boundedFetch(`${this.config.cdpUrl}/json/list`)
          listStatus = listResponse.status
          const targets = await listResponse.json()
          result.targetAbsent = listResponse.ok && !targets.some((target) => target.id === this.targetId)
          if (result.targetAbsent) break
          await sleep(this.config.pollMs)
        } while (Date.now() < deadline)
        if (!result.targetAbsent) result.errors.push({errorName: 'TARGET_STILL_PRESENT', code: 'CDP_TARGET_CLOSE_UNVERIFIED', httpStatus: listStatus, businessCode: null})
      } catch (error) {
        result.errors.push(safeError(error, 'CDP_TARGET_CLOSE_FAILED'))
      }
    }
    if ((this.targetId && result.closeHttpStatus !== 200) || !result.targetAbsent || !result.webSocketClosed || result.errors.length > 0) {
      result.status = 'INCOMPLETE'
    }
    return result
  }
}

function messageContentSignature(snapshot) {
  return snapshot.messages.map((message) => ({
    messageId: message.messageId,
    role: message.role,
    runId: message.runId,
    done: message.done,
    content: textEvidence(message.content),
    parts: message.parts.map((part) => {
      const stablePart = {...part}
      delete stablePart.entryId
      return part.kind === 'tool' ? {
        ...stablePart,
        args: part.args === null ? null : textEvidence(part.args),
        result: part.result === null ? null : textEvidence(part.result),
        reason: part.reason === null ? null : textEvidence(part.reason),
        submittedValues: part.submittedValues === null ? null : sha256(part.submittedValues)
      } : {...stablePart, text: textEvidence(part.text)}
    })
  }))
}

// C-154/C-157：只忽略“空字符串 text 占位”（产品首部空段不持久化），正文/工具/卡片/回执仍严格比较。
function messageContentSignatureDroppingEmptyText(snapshot) {
  return canonicalJson(messageContentSignature(snapshot).map((message) => ({
    ...message,
    parts: message.parts
      .filter((part) => !(part.kind === 'text' && part.text && part.text.length === 0))
      .map((part) => {
        const {seg, seq, ...rest} = part
        return rest
      })
  })))
}

function observationEvidence(observation) {
  return {
    observerId: observation?.observerId ?? null,
    sampleSequence: observation?.sampleSequence ?? null,
    samples: (observation?.samples ?? []).map((sample) => ({
      observerId: sample.observerId,
      sequence: sample.sequence,
      atMs: sample.atMs,
      tools: sample.tools,
      cards: sample.cards,
      workspace: sample.workspace,
      recovery: sample.recovery ?? null,
      operations: sample.operations ?? null
    })),
    noticeSamples: (observation?.noticeSamples ?? []).map((sample) => ({
      atMs: sample.atMs,
      notices: sample.notices.map((notice) => ({testId: notice.testId, text: textEvidence(notice.text)}))
    })),
    copyCalls: (observation?.copyCalls ?? []).map((call) => ({atMs: call.atMs, mechanism: call.mechanism, text: textEvidence(call.text)})),
    errors: observation?.errors ?? []
  }
}

function snapshotEvidence(snapshot) {
  return {
    ...snapshot,
    activeForm: snapshot.activeForm ? {...snapshot.activeForm, values: sha256(snapshot.activeForm.values)} : null,
    messages: messageContentSignature(snapshot),
    cards: snapshot.cards.map((card) => ({...card, text: textEvidence(card.text)})),
    recoverySurfaces: snapshot.recoverySurfaces.map((surface) => ({testId: surface.testId, text: textEvidence(surface.text)})),
    recoveryRetries: snapshot.recoveryRetries.map((retry) => ({testId: retry.testId, text: textEvidence(retry.text)})),
    observation: observationEvidence(snapshot.observation)
  }
}

async function recoveryView(browser) {
  const snapshot = await browser.snapshot()
  const operations = await browser.evaluate(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    const visible = (element) => !!(element && element.getClientRects().length > 0
      && getComputedStyle(element).display !== 'none' && getComputedStyle(element).visibility === 'visible')
    const operation = (selector, root = document) => [...root.querySelectorAll(selector)].map((element) => ({
      testId: element.getAttribute('data-testid'),
      disabled: element.disabled === true || element.getAttribute('aria-disabled') === 'true',
      visible: visible(element)
    }))
    const recoveryAnimationSemantics = (spans) => {
      if (spans.length === 0) return {customClassDots: 0, animationName: null, duration: null, keyframesOpacity: false, keyframesTranslateY: false, valid: false}
      let keyframeText = ''
      for (const sheet of document.styleSheets) {
        let rules
        try { rules = sheet.cssRules } catch { continue }
        for (const rule of rules) {
          if (rule.type === 7 && (rule.name === 'recovery-loading-pulse' || rule.name.startsWith('recovery-loading-pulse-'))) keyframeText = rule.cssText
        }
      }
      const keyframesOpacity = /opacity\\s*:/.test(keyframeText)
      const keyframesTranslateY = /translateY\\(/.test(keyframeText)
      const first = getComputedStyle(spans[0])
      const customClassDots = spans.filter((element) => {
        const style = getComputedStyle(element)
        return element.classList.contains('recovery-loading-dot') && (element.textContent ?? '').trim() === ''
          && /^recovery-loading-pulse(-[0-9a-f]{6,8})?$/.test(style.animationName) && style.animationDuration === '1s'
      }).length
      return {
        customClassDots,
        animationName: first.animationName,
        duration: first.animationDuration,
        keyframesOpacity,
        keyframesTranslateY,
        valid: customClassDots === 3 && keyframesOpacity && keyframesTranslateY
      }
    }
    const messageDom = (message) => {
      const row = document.querySelector('[data-msg-id="' + CSS.escape(message.id) + '"]')
      const meta = row?.querySelector('[data-testid^="msg-ops-"]') ?? null
      const empty = document.createElement('div')
      const count = (selector) => meta ? [...meta.querySelectorAll(selector)].filter(visible).length : 0
      const breath = meta?.querySelector('[data-testid="recovery-loading"]') ?? null
      const breathVisible = visible(breath)
      const pulseSpans = breathVisible && (breath.innerText ?? '').trim() === '' ? [...breath.querySelectorAll(':scope > span')] : []
      const recoveryAnimation = recoveryAnimationSemantics(pulseSpans)
      const working = meta?.querySelector('[data-testid="loading-breath"]') ?? null
      const copy = operation('[data-testid^="copy-msg-"]', meta ?? empty)
      return {
        messageId: message.id,
        metaPresent: meta !== null,
        waitingCount: count('[data-testid="park-waiting"]'),
        compactingCount: count('[data-testid="compaction-message"]'),
        timestampCount: count('[data-testid="msg-timestamp"]'),
        activityTextPresent: (breathVisible && (breath.innerText ?? '').trim().length > 0)
          || (visible(working) && (working.innerText ?? '').trim().length > 0),
        workingTextPresent: visible(working) && (working.innerText ?? '').trim().length > 0,
        pulseDotTotal: pulseSpans.length,
        recoveryAnimation,
        terminalMarkers: meta ? [...meta.querySelectorAll('[data-testid]')].filter((element) => {
          const id = element.getAttribute('data-testid') ?? ''
          return /^msg-(stopped|interrupted|truncated|cancelled|failed)-/.test(id) && visible(element)
        }).map((element) => element.getAttribute('data-testid')) : [],
        copyHintCount: count('[data-testid^="copy-hint-"]'),
        copySuccess: copy.some((entry) => entry.visible && entry.testId
          && meta.querySelector('[data-testid="' + CSS.escape(entry.testId) + '"]')?.classList.contains('is-copied')),
        actionGroupVisible: visible(meta?.querySelector(':scope > div')),
        actions: {
          copy,
          feedback: operation('[data-testid^="feedback-up-"],[data-testid^="feedback-down-"]', meta ?? empty),
          edit: operation('[data-testid^="edit-msg-"]', meta ?? empty),
          continueRound: operation('[data-testid="continue-round"]', meta ?? empty)
        }
      }
    }
    return {
      send: operation('[data-testid="send-btn"]'),
      stop: operation('[data-testid="stop-btn"]'),
      continueRound: operation('[data-testid="continue-round"]'),
      newChat: operation('[data-testid="new-chat"]'),
      feedback: operation('[data-testid^="feedback-up-"],[data-testid^="feedback-down-"]'),
      edit: operation('[data-testid^="edit-msg-"]'),
      copy: operation('[data-testid^="copy-msg-"]'),
      gate: operation('[data-testid="gate-actions"] button'),
      form: operation('[data-testid="gf-submit"],[data-testid="gf-cancel"],[data-testid="gf-queue-cancel"]'),
      upload: operation('[data-testid="upload-handoff-choose"],[data-testid="upload-handoff-cancel"]'),
      sheetWrite: operation('[data-testid="sheet-preview-apply"],[data-testid="sheet-preview-cancel"],[data-testid="sheet-preview-abandon"],[data-testid="sheet-preview-ack-box"]'),
      previewLevel: operation('[data-testid^="sheet-preview-level-"]'),
      previewPagination: operation('[data-testid="sheet-preview-full"] button'),
      composer: operation('[data-testid="composer-input"]'),
      waitingCount: [...document.querySelectorAll('[data-testid="park-waiting"]')].filter(visible).length,
      workingCount: [...document.querySelectorAll('[data-testid="loading-breath"]')].filter(visible).length,
      compactingCount: [...document.querySelectorAll('[data-testid="compaction-message"]')].filter(visible).length,
      timestampCount: [...document.querySelectorAll('[data-testid="msg-timestamp"]')].filter(visible).length,
      messages: ai.messages.map(messageDom)
    }
  })()`, false)
  return {snapshot, operations}
}

function targetPart(snapshot, toolCallId) {
  for (const message of snapshot.messages) {
    const part = message.parts.find((candidate) => candidate.toolCallId === toolCallId)
    if (part) return {message, part}
  }
  return null
}

function targetCard(snapshot, toolCallId) {
  return snapshot.cards.filter((card) => card.testId === `tool-card-${toolCallId}` || card.testId === `pending-card-${toolCallId}`
    || (card.testId === 'hitl-receipt' && [`ro-body-${toolCallId}`, `ro-form-${toolCallId}`].includes(card.bodyTestId)))
}

function bodyPartsForCopy(message) {
  return message.parts.filter((part) => part.kind === 'text' && part.text.trim() !== '').map((part) => part.text)
}

function assertMixedBodyInHistory(context, phase, message, history) {
  const bodyParts = bodyPartsForCopy(message)
  context.checks.require(`${phase}.mixed-body.store-present`, bodyParts.length > 0,
    'the tool/form host assistant message contains at least one non-empty text part', messageContentSignature({messages: [message]}))
  bodyParts.forEach((body, index) => {
    const found = history.messages.some((entry) => String(entry.type ?? entry.role ?? '').toUpperCase() === 'ASSISTANT'
      && String(entry.text ?? entry.content ?? '').includes(body))
    context.checks.require(`${phase}.mixed-body.history-${index + 1}`, found,
      'every store text part is also present in authoritative assistant history', {index, body: textEvidence(body)})
  })
  return bodyParts
}

function visibleToolSummary(snapshot, toolCallId, locale) {
  const target = targetPart(snapshot, toolCallId)
  if (!target) throw new AssertionFailure('VISIBLE_TOOL_PART_REQUIRED_FOR_SUMMARY')
  let args
  try {
    args = typeof target.part.args === 'string' ? JSON.parse(target.part.args) : target.part.args
  } catch {
    throw new AssertionFailure('VISIBLE_TOOL_ARGS_INVALID')
  }
  const zh = locale === 'zh-CN'
  switch (target.part.name) {
    case 'navigate_to':
      if (args?.page !== 'tasks') throw new AssertionFailure('VISIBLE_TOOL_SUMMARY_UNAVAILABLE')
      return zh ? '跳转页面（任务中心）' : 'Navigate (Task Center)'
    case 'select_definitions': {
      const codes = args?.codes ?? args?.defCodes
      if (!Array.isArray(codes) || codes.length === 0) throw new AssertionFailure('VISIBLE_TOOL_SUMMARY_UNAVAILABLE')
      return zh ? `选择配置项（${codes.length} 项）` : `Select definitions (${codes.length})`
    }
    case 'confirm_step':
      if (args?.step !== 'QUERY_COND') throw new AssertionFailure('VISIBLE_TOOL_SUMMARY_UNAVAILABLE')
      return zh ? '确认步骤 查询配置' : 'Confirm step Query conditions'
    case 'set_condition':
      return zh ? '设置查询条件' : 'Set query conditions'
    case 'create_task':
      if (args?.type !== 'EXPORT' || (typeof args.title === 'string' && args.title.trim() !== '')) {
        throw new AssertionFailure('VISIBLE_TOOL_SUMMARY_UNAVAILABLE')
      }
      return zh ? '创建导出配置任务' : 'Create Export task'
    case 'generative_form':
      return zh ? '生成式表单（待填写）' : 'Generated form (pending)'
    case 'list_tasks':
      return zh ? '列出任务' : 'List tasks'
    case 'start_export': {
      const taskId = args?.taskId
      if (taskId === undefined || taskId === null) return zh ? '启动导出作业' : 'Start export job'
      return zh ? `启动导出作业（任务 #${taskId}）` : `Start export job (task #${taskId})`
    }
    default:
      throw new AssertionFailure('VISIBLE_TOOL_SUMMARY_UNAVAILABLE')
  }
}

async function prepareFixture(context) {
  const {config, api} = context
  const selected = toolCases.get(config.scenario)
  if (!selected || selected.fixture === 'none') return
  const task = await api.createTask(`verification-${config.scenario}-${randomUUID()}`)
  context.fixtureTaskId = Number(task.id)
  if (!Number.isSafeInteger(context.fixtureTaskId) || context.fixtureTaskId <= 0) throw new Error('fixture task id invalid')
  if (selected.fixture === 'confirm-step' || selected.fixture === 'set-condition') {
    await api.selectDefs(context.fixtureTaskId)
  }
  if (selected.fixture === 'set-condition') await api.goToStep(context.fixtureTaskId, 'QUERY_COND')
  const url = new URL(config.appUrl)
  url.pathname = '/export'
  url.search = `?taskId=${context.fixtureTaskId}`
  await context.browser.send('Page.navigate', {url: url.toString()})
  const expectedStep = selected.fixture === 'set-condition' ? 'QUERY_COND' : 'SELECT_DEFS'
  await context.browser.waitFor(`(() => {
    const pinia = document.getElementById('app').__vue_app__.config.globalProperties.$pinia
    const workspace = pinia._s.get('workspace')
    const ai = pinia._s.get('ai')
    return workspace.taskId === ${context.fixtureTaskId} && workspace.step === '${expectedStep}' && ai.historyLoaded
  })()`, 'public export fixture page readiness')
  await context.browser.waitFor(`(async () => {
    const {getPageHandler} = await import('/src/stores/workspace.ts')
    return getPageHandler('export.taskDetailReady')?.({}) === true
  })()`, 'EXPORT_TASK_DETAIL_NOT_READY', context.config.timeoutMs, true)
}

function promptFor(context) {
  const nonce = randomUUID()
  if (toolCases.has(context.config.scenario)) return `${toolCases.get(context.config.scenario).prompt} verification=${nonce}`
  if (context.config.scenario === 'tool-skipped') {
    // C-144 的专门契约依赖 multi-card=2（第二笔 SKIPPED/CREATE_TASK_TURN_LIMIT）。
    return `tool=create_task&args={"type":"EXPORT","conditions":{},"openWizard":false} multi-card=2 host-def-select verification=${nonce}`
  }
  if (context.config.scenario === 'recovery-hitl-gate') {
    // C-154 要求单笔 create_task 真实批准 + 宿主续接，不使用 multi-card。
    return `tool=create_task&args={"type":"EXPORT","conditions":{},"openWizard":false} host-def-select verification=${nonce}`
  }
  if (context.config.scenario === 'ordinary-skipped') {
    return `tool-seq=start_export,list_tasks&seq-args={"taskId":${context.fixtureTaskId}} verification=${nonce}`
  }
  if (context.config.scenario === 'form-mixed-copy') return `timeline-nonprefix verification=${nonce}`
  if (context.config.scenario === 'recovery-stream-refresh') return `turn-text stall-frames=250 verification=${nonce}`
  return `turn-text verification=${nonce}`
}

async function waitForSucceededTool(context, toolName) {
  const snapshot = await context.browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    const parts = ai.messages.flatMap((message) => message.parts.filter((part) => part.kind === 'tool' && part.name === '${toolName}'))
    if (parts.some((part) => ['failed', 'unknown', 'cancelled', 'blocked'].includes(part.status))) {
      return {failed: parts.map((part) => ({toolCallId: part.toolCallId, status: part.status}))}
    }
    const succeeded = parts.filter((part) => part.status === 'succeeded')
    return succeeded.length === 1 ? {toolCallId: succeeded[0].toolCallId} : null
  })()`, `${toolName} succeeded`)
  if (snapshot.failed) throw new AssertionFailure(`${toolName} did not succeed: ${JSON.stringify(snapshot.failed)}`)
  context.runId = (await context.browser.snapshot()).activeRunId
  return snapshot.toolCallId
}

async function driveHostFormsToTerminal(context, toolCallId) {
  const {browser, config, checks} = context
  const submitted = []
  for (let attempt = 0; attempt < 4; attempt += 1) {
    const state = await browser.waitFor(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      const host = ai.messages.find((message) => message.parts.some((part) => part.toolCallId === '${toolCallId}'))
      const active = ai.activeForm
      if (active?.toolCallId && ai.messages.some((message) => message.parts.some((part) => part.toolCallId === active.toolCallId
        && part.name === 'generative_form' && part.requiresAction === true))) {
        const fields = Array.isArray(active.form?.fields) ? active.form.fields.map((field) => ({
          key: field.key, type: field.type, required: field.required === true
        })) : []
        return {form: {toolCallId: active.toolCallId, fields}}
      }
      if (ai.loading === false && !active && ai.queuedForms.length === 0 && host?.done === true) return {terminal: true}
      return null
    })()`, 'HOST_CONTINUATION_TERMINAL_NOT_REACHED')
    if (state.terminal) {
      // 这三个场景的宿主续接都要求先选配置项、再提交空白条件，二者各恰一次。
      const hostChainScenarios = new Set(['tool-select-definitions', 'tool-confirm-step', 'tool-skipped', 'recovery-hitl-gate', 'recovery-mixed-messages'])
      const expectedForms = hostChainScenarios.has(config.scenario) ? 1 : 0
      const definitions = submitted.filter((entry) => entry.purpose === 'SELECT_DEFINITIONS').length
      const conditions = submitted.filter((entry) => entry.purpose === 'SET_CONDITIONS').length
      checks.require('live.host-form.terminal-count', definitions === expectedForms && conditions === expectedForms,
        `exactly ${expectedForms} host definition form(s) and ${expectedForms} host condition form(s) submitted before terminal`, submitted)
      return
    }
    const form = state.form
    const expectedFormId = `host-cond-form-${context.runId}-${context.fixtureTaskId}`
    checks.require('live.host-form.expected-purpose', HOST_FORM_SCENARIOS.has(config.scenario)
      && form.toolCallId === expectedFormId && form.fields.length === 1
      && form.fields[0].key === 'conditions' && form.fields[0].type === 'text' && form.fields[0].required === false,
    'only the deterministic blank host condition form is driven for select_definitions/confirm_step', {expectedFormId, form})
    await browser.waitFor(`document.querySelector('[data-testid="gf-field-${form.toolCallId}-conditions"]')?.value === ''`,
      'HOST_CONDITION_BLANK_FIELD_NOT_READY')
    await browser.click(`[data-testid="gf-form-${form.toolCallId}"] [data-testid="gf-submit"]`)
    submitted.push(form.toolCallId)
    await browser.waitFor(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      const part = ai.messages.flatMap((message) => message.parts).find((candidate) => candidate.toolCallId === '${form.toolCallId}')
      return part?.requiresAction === false && ai.activeForm?.toolCallId !== '${form.toolCallId}'
    })()`, 'HOST_CONDITION_SUBMISSION_NOT_ACCEPTED')
  }
  throw new AssertionFailure('HOST_FORM_DRIVE_LIMIT_EXCEEDED')
}

async function driveSkippedHostContinuation(context) {
  const {browser, checks} = context
  const submitted = []
  for (let attempt = 0; attempt < 6; attempt += 1) {
    const state = await browser.waitFor(`(() => {
      const pinia = document.getElementById('app').__vue_app__.config.globalProperties.$pinia
      const ai = pinia._s.get('ai')
      const workspace = pinia._s.get('workspace')
      const active = ai.activeForm
      if (active?.toolCallId && ai.messages.some((message) => message.parts.some((part) => part.toolCallId === active.toolCallId
        && part.name === 'generative_form' && part.requiresAction === true))) {
        const fields = Array.isArray(active.form?.fields) ? active.form.fields.map((field) => ({
          key: field.key, type: field.type, required: field.required === true
        })) : []
        return {form: {toolCallId: active.toolCallId, taskId: workspace.taskId, fields}}
      }
      const startPending = ai.messages.flatMap((message) => message.parts).some((part) => part.kind === 'tool'
        && part.name === 'start_export' && part.status === 'pending' && part.requiresAction === true)
      if (startPending) return {startPending: true}
      const createParts = ai.messages.flatMap((message) => message.parts).filter((part) => part.kind === 'tool' && part.name === 'create_task')
      const terminal = ['succeeded', 'rejected', 'blocked', 'expired', 'cancelled', 'unknown', 'failed', 'skipped']
      const currentRunId = '${context.runId ?? ''}' || ai.activeRunId || null
      const lastSubmitted = '${context.lastSubmittedFormToolCallId ?? ''}'
      const host = ai.messages.find((message) => message.role === 'assistant'
        && message.parts.some((part) => part.kind === 'tool' && part.name === 'create_task'))
      const hostRunMatches = !currentRunId || (host && host.runId === currentRunId)
      const assistants = ai.messages.filter((message) => message.role === 'assistant')
      const latest = assistants[assistants.length - 1] ?? null
      if (ai.loading === false && !active && ai.queuedForms.length === 0 && createParts.length > 0
        && createParts.every((part) => terminal.includes(part.status) && part.requiresAction === false)
        && host && host.done === true && hostRunMatches) return {terminal: true}
      return null
    })()`, 'SKIPPED_HOST_CONTINUATION_NOT_READY')
    if (state.terminal || state.startPending) {
      if (state.startPending) {
        checks.require('live.host-forms.before-start-confirmation', submitted.filter((form) => form.purpose === 'SELECT_DEFINITIONS').length === 1
          && submitted.filter((form) => form.purpose === 'SET_CONDITIONS').length === 1,
        'the full host chain submits one CURRENCY selection and one blank condition before start_export waits unapproved', submitted)
      }
      await context.evidence.json('host-form-submissions.json', {startPending: state.startPending === true, submitted})
      return
    }
    const form = state.form
    // 条件表单 id 形如 host-cond-form-<runId(UUID)>-<taskId>；本轮 runId 在宿主续接前尚未采集，
    // 故按 id 形状 + 任务号判定，不依赖 context.runId（其值由驱动后的 activeRunId 回填）。
    const conditionFormId = new RegExp(`^host-cond-form-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-${form.taskId}$`)
    const purpose = form.toolCallId === `host-def-form-${form.taskId}` ? 'SELECT_DEFINITIONS'
      : conditionFormId.test(form.toolCallId) ? 'SET_CONDITIONS' : null
    checks.require('live.host-form.supported-purpose', purpose !== null, 'only deterministic host definition/condition forms are driven', form)
    if (purpose === 'SELECT_DEFINITIONS') {
      checks.require('live.host-form.definition-field', form.fields.length === 1 && form.fields[0].key === 'defCodes'
        && form.fields[0].type === 'multi_select' && form.fields[0].required === true, 'required flat defCodes multi_select', form)
      const option = `[data-testid="gf-field-${form.toolCallId}-defCodes-CURRENCY"]`
      await browser.waitFor(`document.querySelector('${option}')?.disabled === false`, 'HOST_DEFINITION_CURRENCY_NOT_READY')
      if (await browser.evaluate(`document.querySelector('${option}').checked === false`, false)) await browser.click(option)
      await browser.waitFor(`document.querySelector('${option}')?.checked === true`, 'HOST_DEFINITION_CURRENCY_NOT_SELECTED')
    } else {
      checks.require('live.host-form.condition-field', form.fields.length === 1 && form.fields[0].key === 'conditions'
        && form.fields[0].type === 'text' && form.fields[0].required === false, 'optional blank conditions text field', form)
      await browser.waitFor(`document.querySelector('[data-testid="gf-field-${form.toolCallId}-conditions"]')?.value === ''`,
        'HOST_CONDITION_BLANK_FIELD_NOT_READY')
    }
    await browser.click(`[data-testid="gf-form-${form.toolCallId}"] [data-testid="gf-submit"]`)
    submitted.push({toolCallId: form.toolCallId, purpose})
    context.lastSubmittedFormToolCallId = form.toolCallId
    await browser.waitFor(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      const part = ai.messages.flatMap((message) => message.parts).find((candidate) => candidate.toolCallId === '${form.toolCallId}')
      return part?.requiresAction === false && ai.activeForm?.toolCallId !== '${form.toolCallId}'
    })()`, 'SKIPPED_HOST_FORM_SUBMISSION_NOT_ACCEPTED')
    // 提交后必须观察到续接轮真的开始（loading=true、新的宿主表单或 start_export 待决），
    // 否则两段宿主表单之间的空档会被 terminal 分支误判为整轮结束。
    try {
      await browser.waitFor(`(() => {
        const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
        const startPending = ai.messages.flatMap((message) => message.parts).some((part) => part.kind === 'tool'
          && part.name === 'start_export' && part.status === 'pending' && part.requiresAction === true)
        const nextForm = ai.activeForm?.toolCallId && ai.activeForm.toolCallId !== '${form.toolCallId}'
        return ai.loading === true || startPending || !!nextForm || ai.queuedForms.length > 0
      })()`, 'HOST_CONTINUATION_NOT_STARTED', 10_000)
    } catch (error) {
      if (!(error instanceof AssertionFailure)) throw error
    }
  }
  throw new AssertionFailure('SKIPPED_HOST_FORM_DRIVE_LIMIT_EXCEEDED')
}

async function copyMessageAndAssert(context, phase, messageId, {requiredTitle = null, forbiddenTitle = null, requiredTexts = [], expectedText = null} = {}) {
  const {browser, evidence, checks} = context
  const before = await browser.evaluate(`(window.__toolCardRecoveryVerification?.copyCalls ?? []).length`, false)
  try {
    await browser.click(`[data-testid="copy-msg-${messageId}"]`)
  } catch (error) {
    if (!(error instanceof AssertionFailure) || !String(error.message).startsWith('CLICK_TARGET_NOT_INTERACTABLE')) throw error
    // 浮层/滚动容器可能挡住命中测试；DOM click 仍触发同一个真实处理函数（非伪造状态）。
    const clicked = await browser.evaluate(`(() => {
      const button = document.querySelector('[data-testid="copy-msg-${messageId}"]')
      if (!button || button.disabled) return false
      button.click()
      return true
    })()`, false)
    if (!clicked) throw error
  }
  await browser.waitFor(`(window.__toolCardRecoveryVerification?.copyCalls ?? []).length > ${before}
    && document.querySelector('[data-testid="copy-msg-${messageId}"]')?.classList.contains('is-copied')`, 'CLIPBOARD_WRITE_OR_FEEDBACK_MISSING')
  const copy = await browser.evaluate(`(() => {
    const calls = window.__toolCardRecoveryVerification.copyCalls
    return calls[calls.length - 1]
  })()`, false)
  if (requiredTitle !== null) {
    const occurrences = copy.text.split(requiredTitle).length - 1
    checks.require(`${phase}.copy.contains-visible-tool-summary`, requiredTitle.trim() !== '' && occurrences >= 1,
      'actual clipboard payload contains the complete visible toolSummary', {requiredTitle, occurrences, copy: textEvidence(copy.text)})
  }
  requiredTexts.forEach((requiredText, index) => {
    const occurrences = requiredText.trim() === '' ? 0 : copy.text.split(requiredText).length - 1
    checks.require(`${phase}.copy.contains-body-${index + 1}`, occurrences >= 1,
      'every non-empty text part occurs in the actual clipboard payload', {index, requiredText: textEvidence(requiredText), occurrences, copy: textEvidence(copy.text)})
  })
  if (expectedText !== null) {
    checks.require(`${phase}.copy.exact-visible-contract`, copy.text === expectedText,
      'clipboard is exactly the non-empty body parts followed by the visible tool summaries, with no hidden-part contribution',
      {expected: textEvidence(expectedText), actual: textEvidence(copy.text)})
  }
  if (forbiddenTitle !== null) {
    let skippedOnlyText = copy.text
    if (requiredTitle === forbiddenTitle) {
      const visibleSummaryAt = copy.text.indexOf(requiredTitle)
      skippedOnlyText = visibleSummaryAt < 0 ? copy.text
        : `${copy.text.slice(0, visibleSummaryAt)}${copy.text.slice(visibleSummaryAt + requiredTitle.length)}`
    }
    const occurrences = forbiddenTitle.trim() === '' ? -1 : skippedOnlyText.split(forbiddenTitle).length - 1
    checks.require(`${phase}.copy.excludes-skipped-tool`, occurrences === 0,
      'skipped-only summary occurs zero times after subtracting the one identical summary required by the visible approved part',
      {forbiddenTitle, occurrences, skippedOnlyText: textEvidence(skippedOnlyText), copy: textEvidence(copy.text)})
  }
  await evidence.json(`copy-${phase}.json`, {
    mechanism: copy.mechanism,
    requiredTitle,
    forbiddenTitle,
    requiredTexts: requiredTexts.map(textEvidence),
    expectedText: expectedText === null ? null : textEvidence(expectedText),
    text: textEvidence(copy.text)
  })
  return copy
}

async function savePhase(context, phase, snapshot, historyEvidence, business) {
  context.checks.require(`${phase}.observer-no-errors`, (snapshot.observation?.errors ?? []).length === 0,
    'page observation and clipboard hooks report no errors', snapshot.observation?.errors ?? [])
  await context.evidence.json(`dom-${phase}.json`, snapshotEvidence(snapshot))
  await context.evidence.json(`history-${phase}.json`, historyEvidence.status === 'AVAILABLE'
    ? {status: 'AVAILABLE', httpStatus: historyEvidence.httpStatus, ...historySummary(historyEvidence.data)}
    : {status: 'UNAVAILABLE', httpStatus: historyEvidence.httpStatus,
      errorName: historyEvidence.errorName, businessCode: historyEvidence.businessCode})
  await context.evidence.json(`business-${phase}.json`, business)
  const screenshot = await context.browser.send('Page.captureScreenshot', {format: 'png', captureBeyondViewport: false})
  await context.evidence.screenshot(`dom-${phase}.png`, screenshot.data)
}

function unrelatedBusinessFingerprint(snapshot, fixtureTaskId) {
  return sha256(snapshot.allTasks.filter((task) => task.id !== fixtureTaskId))
}

function assertFixtureBaseline(context) {
  const selected = toolCases.get(context.config.scenario)
  if (!selected || selected.fixture === 'none') return
  const task = context.businessAfterFixture.allTasks.find((entry) => entry.id === context.fixtureTaskId)
  context.checks.require('fixture.task.present', !!task, context.fixtureTaskId, task ?? null)
  context.checks.require('fixture.zero-jobs', task.jobs.length === 0, 0, task.jobs)
  if (selected.fixture === 'select-defs') {
    context.checks.require('fixture.select-defs-ready', task.currentStep === 'SELECT_DEFS', 'SELECT_DEFS', task.currentStep)
  }
  if (selected.fixture === 'confirm-step') {
    context.checks.require('fixture.confirm-preselected-currency', task.currentStep === 'SELECT_DEFS'
      && task.items.filter((item) => item.defCode === 'CURRENCY').length === 1,
    'SELECT_DEFS fixture already selected CURRENCY through the public API', task)
  }
  if (selected.fixture === 'set-condition') {
    context.checks.require('fixture.condition-preselected-currency', task.currentStep === 'QUERY_COND'
      && task.items.filter((item) => item.defCode === 'CURRENCY').length === 1,
    'QUERY_COND fixture already selected CURRENCY through the public API', task)
  }
}

function assertBusinessForTool(context, phase, business, snapshot) {
  const {config, checks} = context
  const selected = toolCases.get(config.scenario)
  const baseline = context.businessAfterFixture
  if (selected.fixture === 'none') {
    checks.require(`${phase}.business.no-new-task-or-job`, business.fingerprint === baseline.fingerprint,
      'business fingerprint identical before send, live, and after each refresh',
      {before: baseline.fingerprint, actual: business.fingerprint})
    return
  }
  checks.require(`${phase}.business.no-unrelated-changes`, unrelatedBusinessFingerprint(business, context.fixtureTaskId)
    === unrelatedBusinessFingerprint(baseline, context.fixtureTaskId),
  'no unrelated task/job changes', {
    before: unrelatedBusinessFingerprint(baseline, context.fixtureTaskId),
    actual: unrelatedBusinessFingerprint(business, context.fixtureTaskId)
  })
  const task = business.allTasks.find((entry) => entry.id === context.fixtureTaskId)
  const before = baseline.allTasks.find((entry) => entry.id === context.fixtureTaskId)
  const liveWorkspace = phase === 'live-succeeded' && context.targetLiveWorkspace
    ? context.targetLiveWorkspace : snapshot.workspace
  checks.require(`${phase}.fixture-task.present`, !!task && !!before, context.fixtureTaskId, task ?? null)
  checks.require(`${phase}.fixture.zero-jobs`, task.jobs.length === 0, 0, task.jobs)
  if (selected.tool === 'select_definitions') {
    const item = task.items.find((entry) => entry.defCode === 'CURRENCY')
    checks.require(`${phase}.business.host-selection-and-blank-condition-complete`, task.currentStep === 'EXPORT'
      && before.currentStep === 'SELECT_DEFS' && before.items.length === 0 && task.items.length === 1
      && canonicalJson(item?.condition) === canonicalJson(textEvidence('{}')),
    'host continuation persists CURRENCY and the submitted blank condition, then advances the fixture to EXPORT', {before, task})
    if (phase === 'live-succeeded') {
      checks.require(`${phase}.workspace.selection-draft-only-live`, liveWorkspace.selectedDefs.length === 1
        && liveWorkspace.selectedDefs[0] === 'CURRENCY',
      'target select_definitions succeeds with exactly CURRENCY in the live workspace', liveWorkspace.selectedDefs)
    }
  }
  if (selected.tool === 'confirm_step') {
    const item = task.items.find((entry) => entry.defCode === 'CURRENCY')
    checks.require(`${phase}.business.host-blank-condition-and-export-complete`, task.currentStep === 'EXPORT'
      && before.currentStep === 'SELECT_DEFS'
      && before.items.filter((entry) => entry.defCode === 'CURRENCY').length === 1
      && task.items.filter((entry) => entry.defCode === 'CURRENCY').length === 1
      && canonicalJson(item?.condition) === canonicalJson(textEvidence('{}')),
    'confirm_step reaches QUERY_COND, then the real blank host form is submitted and the fixture advances to EXPORT', {before, task})
  }
  if (selected.tool === 'set_condition') {
    checks.require(`${phase}.business.public-condition-unchanged`, canonicalJson(task) === canonicalJson(before),
      'public task unchanged by draft-only set_condition', {before, task})
    if (phase === 'live-succeeded') {
      const drafts = liveWorkspace.queryConditions
      const condition = drafts.CURRENCY
      const fields = Array.isArray(condition?.fields) ? condition.fields : []
      checks.require(`${phase}.workspace.exact-cny-condition-only-live`, Object.keys(drafts).length === 1
        && fields.length === 1 && fields[0]?.fieldCode === 'code' && fields[0]?.operator === 'EQ' && fields[0]?.value === 'CNY'
        && (condition?.scopeKeys === undefined || (Array.isArray(condition.scopeKeys) && condition.scopeKeys.length === 0)),
      'target set_condition succeeds with only CURRENCY code EQ CNY in the live workspace', drafts)
    }
  }
}

async function assertToolPhase(context, phase, toolName, toolCallId) {
  const {browser, api, checks} = context
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    const host = ai.messages.find((message) => message.parts.some((part) => part.toolCallId === '${toolCallId}'))
    return ai.loading === false && host?.done === true
  })()`, 'TERMINAL_AND_COPY_NOT_READY')
  const snapshot = await browser.snapshot()
  const historyEvidence = await api.history(snapshot.sessionId)
  const history = requiredHistory(historyEvidence)
  const business = await businessSnapshot(api, relevantTaskIds(context))
  context.sessionId = snapshot.sessionId
  const target = targetPart(snapshot, toolCallId)
  checks.require(`${phase}.store.succeeded`, target?.part.status === 'succeeded', 'succeeded', target?.part ?? null)
  const cards = targetCard(snapshot, toolCallId)
  checks.require(`${phase}.dom.single-succeeded-card`, cards.length === 1 && cards[0].state === 'succeeded',
    'one visible succeeded DOM card', cards)
  const occurrences = historyToolOccurrences(history, toolCallId)
  const expectedStatus = FRONTEND_TOOL_NAMES.has(toolName) ? 'FRONTEND_RESULT' : 'EXECUTED'
  checks.require(`${phase}.history.tool-channel-result-once`, occurrences.states.length === 1 && occurrences.states[0] === expectedStatus
    && occurrences.responses.length === 1 && occurrences.responses[0] === toolName,
  {states: [expectedStatus], responses: [toolName]}, occurrences)
  checks.require(`${phase}.locale`, snapshot.locale === context.config.locale, context.config.locale, snapshot.locale)
  assertBusinessForTool(context, phase, business, snapshot)
  const requiredTexts = assertMixedBodyInHistory(context, phase, target.message, history)
  const requiredTitle = visibleToolSummary(snapshot, toolCallId, context.config.locale)
  await copyMessageAndAssert(context, phase, target.message.id, {requiredTitle, requiredTexts})
  await savePhase(context, phase, snapshot, historyEvidence, business)
  return {snapshot, history, business, historySignature: canonicalJson(historySummary(history))}
}

async function runToolScenario(context) {
  const {config, browser, checks} = context
  const selected = toolCases.get(config.scenario)
  context.businessAfterFixture = await businessSnapshot(context.api, relevantTaskIds(context))
  assertFixtureBaseline(context)
  browser.setPhase('live-succeeded')
  await browser.sendPrompt(promptFor(context))
  const toolCallId = await waitForSucceededTool(context, selected.tool)
  context.toolCallId = toolCallId
  const runningEvidence = await browser.evaluate(`(() => {
    const samples = window.__toolCardRecoveryVerification?.samples ?? []
    return samples.filter((sample) => sample.tools.some((part) => part.toolCallId === '${toolCallId}' && part.status === 'running')
      && sample.cards.some((card) => (card.testId === 'pending-card-${toolCallId}' || card.testId === 'tool-card-${toolCallId}')
        && card.state === 'running'))
  })()`, false)
  checks.require('live.running.store-and-dom-state-observed', runningEvidence.length > 0,
    'store status=running and visible DOM card state=running captured before succeeded', runningEvidence)
  const targetLiveWorkspaceSamples = await browser.evaluate(`(() => {
    const samples = window.__toolCardRecoveryVerification?.samples ?? []
    return samples.filter((sample) => sample.workspace && sample.tools.some((part) => part.toolCallId === '${toolCallId}'
      && ['running', 'succeeded'].includes(part.status))).map((sample) => ({
      atMs: sample.atMs,
      tools: sample.tools.filter((part) => part.toolCallId === '${toolCallId}'),
      workspace: sample.workspace
    }))
  })()`, false)
  const targetSucceededWorkspaceSamples = targetLiveWorkspaceSamples.filter((sample) => sample.tools.some((part) => part.status === 'succeeded'))
  const targetLiveWorkspace = (targetSucceededWorkspaceSamples.find((sample) => Object.keys(sample.workspace.queryConditions ?? {}).length > 0)
    ?? targetSucceededWorkspaceSamples[0])?.workspace ?? null
  checks.require('live.tool-succeeded.workspace-observed', targetLiveWorkspace !== null,
    'a real observation sample contains the running/succeeded target and its live workspace', targetLiveWorkspaceSamples)
  context.targetLiveWorkspace = targetLiveWorkspace
  await context.evidence.json('workspace-live.json', targetLiveWorkspaceSamples)
  await driveHostFormsToTerminal(context, toolCallId)
  const phases = []
  phases.push(await assertToolPhase(context, 'live-succeeded', selected.tool, toolCallId))
  const requestBaseline = browser.requestCounts()
  checks.require('live.single-chat', requestBaseline.chat === 1, 1, requestBaseline)
  const expectedFrontendResults = EXPECTED_FRONTEND_RESULT_COUNTS.get(config.scenario)
  checks.require('live.exact-frontend-results', requestBaseline.frontendToolResult === expectedFrontendResults,
    expectedFrontendResults, requestBaseline)
  for (const phase of ['history', 'refresh-1', 'refresh-2']) {
    browser.setPhase(phase)
    await browser.reload()
    phases.push(await assertToolPhase(context, phase, selected.tool, toolCallId))
    checks.require(`${phase}.no-repeat-side-effect-requests`, canonicalJson(browser.requestCounts()) === canonicalJson(requestBaseline),
      requestBaseline, browser.requestCounts())
  }
  checks.require('refresh.history-stable', phases.every((phase) => phase.historySignature === phases[0].historySignature),
    phases[0].historySignature, phases.map((phase) => phase.historySignature))
  checks.require('refresh.business-stable', phases.every((phase) => phase.business.fingerprint === phases[0].business.fingerprint),
    phases[0].business.fingerprint, phases.map((phase) => phase.business.fingerprint))
}

async function approveCreateTasks(context) {
  const {browser} = context
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.messages.flatMap((message) => message.parts).some((part) => part.kind === 'tool' && part.name === 'create_task')
  })()`, 'CREATE_TASK_PART_NOT_FORMED')
  const pending = await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    const part = ai.messages.flatMap((message) => message.parts).find((part) => part.kind === 'tool'
      && part.name === 'create_task' && part.requiresAction === true)
    if (part) return {toolCallId: part.toolCallId}
    const parts = ai.messages.flatMap((message) => message.parts).filter((part) => part.kind === 'tool' && part.name === 'create_task')
    return parts.length > 0 && ai.loading === false ? {finished: true} : null
  })()`, 'CREATE_TASK_DECISION_NOT_FORMED')
  if (pending.finished) return
  await browser.click(`[data-testid="pending-card-${pending.toolCallId}"] [data-testid="gate-actions"] button:last-child`)
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return !ai.messages.flatMap((message) => message.parts).some((part) => part.toolCallId === '${pending.toolCallId}' && part.requiresAction === true)
  })()`, 'CREATE_TASK_DECISION_NOT_ACCEPTED')
}

function skippedToolSummary(snapshot, toolCallId, locale) {
  const target = targetPart(snapshot, toolCallId)
  if (!target || target.part.status !== 'skipped') {
    throw new AssertionFailure('SKIPPED_PART_REQUIRED_FOR_SUMMARY')
  }
  return visibleToolSummary(snapshot, toolCallId, locale)
}

async function assertSkippedPhase(context, phase, skippedToolCallId, otherToolCallId,
  {expectedCode, expectedNewTasks, requireOtherVisible = null, requireBody = true}) {
  const {browser, api, checks} = context
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    const part = ai.messages.flatMap((message) => message.parts).find((part) => part.toolCallId === '${skippedToolCallId}')
    const terminal = ['succeeded', 'rejected', 'blocked', 'expired', 'cancelled', 'unknown', 'failed', 'skipped']
    return !!part && terminal.includes(part.status) && ai.loading === false
  })()`, 'SKIPPED_PART_NOT_PROJECTED')
  const snapshot = await browser.snapshot()
  const historyEvidence = await api.history(snapshot.sessionId)
  const history = requiredHistory(historyEvidence)
  const business = await businessSnapshot(api, relevantTaskIds(context))
  context.sessionId = snapshot.sessionId
  const skippedPart = targetPart(snapshot, skippedToolCallId)?.part
  checks.require(`${phase}.skipped.store-present`, skippedPart?.status === 'skipped' && skippedPart.userTextCode === expectedCode,
    `skipped remains in store with exact ${expectedCode}`, skippedPart)
  checks.require(`${phase}.skipped.dom-filtered`, targetCard(snapshot, skippedToolCallId).length === 0,
    'zero visible cards for skipped toolCallId', targetCard(snapshot, skippedToolCallId))
  const otherCards = targetCard(snapshot, otherToolCallId)
  if (requireOtherVisible !== null) {
    checks.require(`${phase}.other.visible-card-count`, otherCards.length === (requireOtherVisible ? 1 : 0),
      requireOtherVisible ? 'exactly one visible create_task card' : 'no visible create_task card', otherCards)
  }
  const occurrences = historyToolOccurrences(history, skippedToolCallId)
  checks.require(`${phase}.skipped.history-preserved`, occurrences.states.length === 1 && occurrences.states[0] === 'SKIPPED'
    && occurrences.codes.length === 1 && occurrences.codes[0] === expectedCode,
  {states: ['SKIPPED'], code: expectedCode}, occurrences)
  const newTasks = business.allTasks.filter((task) => !context.businessAfterFixture.allTasks.some((before) => before.id === task.id))
  checks.require(`${phase}.business.exact-new-task-count`, newTasks.length === expectedNewTasks
    && newTasks.every((task) => task.jobs.length === 0),
  `exactly ${expectedNewTasks} scenario-created task(s) with zero jobs`, newTasks)
  for (const task of newTasks) context.createdTaskIds.add(task.id)
  business.relevantTasks = newTasks
  // 单轮上限跳过的第二张卡在上游就没有 args（store 里 args=null），产品也不渲染该卡；
  // 此时不存在可复述的 toolSummary：只要求该卡零可见，不伪造 locale 文案。
  const skippedArgsPresent = skippedPart?.args !== null && skippedPart?.args !== undefined
  const skippedSummary = skippedArgsPresent ? skippedToolSummary(snapshot, skippedToolCallId, context.config.locale) : null
  checks.require(`${phase}.skipped.copy-summary-known`, skippedArgsPresent
    ? skippedSummary.trim() !== ''
    : targetCard(snapshot, skippedToolCallId).length === 0,
  'skipped toolSummary is generated from its own name and args when args exist; without args the skipped card stays invisible', skippedSummary)
  const target = targetPart(snapshot, skippedToolCallId)
  const requiredTexts = requireBody ? assertMixedBodyInHistory(context, phase, target.message, history) : []
  const requiredTitle = otherCards.length === 1 ? visibleToolSummary(snapshot, otherToolCallId, context.config.locale) : null
  const expectedText = [...requiredTexts, ...(requiredTitle === null ? [] : [requiredTitle])].filter((text) => text.trim() !== '').join('\n\n')
  await copyMessageAndAssert(context, phase, target.message.id, {requiredTitle, forbiddenTitle: skippedSummary, requiredTexts, expectedText})
  await savePhase(context, phase, snapshot, historyEvidence, business)
  return {historySignature: canonicalJson(historySummary(history)), business}
}

async function runSkippedScenario(context) {
  const {browser, checks} = context
  context.businessAfterFixture = await businessSnapshot(context.api, relevantTaskIds(context))
  browser.setPhase('live')
  await browser.sendPrompt(promptFor(context))
  await approveCreateTasks(context)
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    const parts = ai.messages.flatMap((message) => message.parts).filter((part) => part.kind === 'tool' && part.name === 'create_task')
    const terminal = ['succeeded', 'rejected', 'blocked', 'expired', 'cancelled', 'unknown', 'failed', 'skipped']
    return parts.length > 0 && parts.every((part) => terminal.includes(part.status) && part.requiresAction === false)
  })()`, 'SKIPPED_TERMINAL_NOT_REACHED')
  await driveSkippedHostContinuation(context)
  const snapshot = await browser.snapshot()
  const startPending = snapshot.messages.flatMap((message) => message.parts).some((part) => part.kind === 'tool'
    && part.name === 'start_export' && part.status === 'pending' && part.requiresAction === true)
  const parts = snapshot.messages.flatMap((message) => message.parts).filter((part) => part.name === 'create_task')
  checks.require('live.two-create-task-parts', parts.length === 2, 'multi-card=2 yields exactly two create_task parts', parts)
  const skipped = parts.filter((part) => part.status === 'skipped' && part.decision === null && part.requiresAction === false)
  checks.require('live.one-turn-limit-skipped', skipped.length === 1 && skipped[0].userTextCode === 'CREATE_TASK_TURN_LIMIT',
    'the second create_task is skipped with the dedicated single-turn creation limit code', parts)
  if (!startPending) throw new ScenarioNotRun('START_EXPORT_PENDING_NOT_FORMED')
  context.toolCallId = skipped[0].toolCallId
  context.runId = snapshot.activeRunId
  const approved = parts.find((part) => part.status === 'succeeded')
  checks.require('skipped.one-approved-create', !!approved, 'one approved create_task', parts)
  const visibleApprovedCards = targetCard(snapshot, approved.toolCallId)
  checks.require('live.approved-card-visible', visibleApprovedCards.length === 1, 1, visibleApprovedCards)
  const live = await assertSkippedPhase(context, 'live', context.toolCallId, approved.toolCallId,
    {expectedCode: 'CREATE_TASK_TURN_LIMIT', expectedNewTasks: 1, requireOtherVisible: true})
  const requestBaseline = browser.requestCounts()
  checks.require('live.single-create-confirm', requestBaseline.confirm === 1, 1, requestBaseline)
  const phases = [live]
  for (const phase of ['history', 'refresh-1', 'refresh-2']) {
    browser.setPhase(phase)
    await browser.reload()
    phases.push(await assertSkippedPhase(context, phase, context.toolCallId, approved.toolCallId,
      {expectedCode: 'CREATE_TASK_TURN_LIMIT', expectedNewTasks: 1, requireOtherVisible: true}))
    checks.require(`${phase}.no-repeat-decision-or-side-effect`, canonicalJson(browser.requestCounts()) === canonicalJson(requestBaseline),
      requestBaseline, browser.requestCounts())
  }
  checks.require('refresh.skipped-history-stable', phases.every((phase) => phase.historySignature === phases[0].historySignature),
    phases[0].historySignature, phases.map((phase) => phase.historySignature))
  checks.require('refresh.skipped-business-stable', phases.every((phase) => phase.business.fingerprint === live.business.fingerprint),
    live.business.fingerprint, phases.map((phase) => phase.business.fingerprint))
}

async function runOrdinarySkippedScenario(context) {
  const {browser, api, checks, config} = context
  const task = await api.createTask(`verification-ordinary-skipped-${randomUUID()}`)
  context.fixtureTaskId = Number(task.id)
  if (!Number.isSafeInteger(context.fixtureTaskId) || context.fixtureTaskId <= 0) {
    throw new Error('ordinary-skipped fixture task id invalid')
  }
  await api.selectDefs(context.fixtureTaskId)
  await api.goToStep(context.fixtureTaskId, 'EXPORT')
  const fixtureUrl = new URL(config.appUrl)
  fixtureUrl.pathname = '/export'
  fixtureUrl.search = `?taskId=${context.fixtureTaskId}`
  await browser.send('Page.navigate', {url: fixtureUrl.toString()})
  await browser.waitFor(`(() => {
    const pinia = document.getElementById('app').__vue_app__.config.globalProperties.$pinia
    const workspace = pinia._s.get('workspace')
    return workspace.taskId === ${context.fixtureTaskId} && workspace.step === 'EXPORT'
  })()`, 'ORDINARY_SKIPPED_FIXTURE_NOT_READY')
  context.businessAfterFixture = await businessSnapshot(context.api, relevantTaskIds(context))
  browser.setPhase('live')
  await browser.sendPrompt(promptFor(context))
  const pending = await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    const part = ai.messages.flatMap((message) => message.parts).find((part) => part.kind === 'tool'
      && part.name === 'start_export' && part.requiresAction === true)
    return part ? {toolCallId: part.toolCallId} : null
  })()`, 'ORDINARY_SKIPPED_FIRST_CONFIRM_NOT_FORMED')
  await browser.click(`[data-testid="pending-card-${pending.toolCallId}"] [data-testid="gate-actions"] button:first-child`)
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    const parts = ai.messages.flatMap((message) => message.parts).filter((part) => part.kind === 'tool'
      && (part.name === 'start_export' || part.name === 'list_tasks'))
    const terminal = ['succeeded', 'rejected', 'blocked', 'expired', 'cancelled', 'unknown', 'failed', 'skipped']
    return parts.length === 2 && parts.every((part) => terminal.includes(part.status) && part.requiresAction === false)
  })()`, 'ORDINARY_SKIPPED_TERMINAL_NOT_REACHED')
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    const host = ai.messages.find((message) => message.parts.some((part) => ['start_export', 'list_tasks'].includes(part.name)))
    return ai.loading === false && host?.done === true
  })()`, 'ORDINARY_SKIPPED_TURN_NOT_DONE')
  const snapshot = await browser.snapshot()
  const parts = snapshot.messages.flatMap((message) => message.parts)
    .filter((part) => part.name === 'start_export' || part.name === 'list_tasks')
  const first = parts.find((part) => part.name === 'start_export')
  const skipped = parts.find((part) => part.name === 'list_tasks')
  if (!first || !skipped) throw new ScenarioNotRun('ORDINARY_SKIPPED_TWO_CALLS_NOT_FORMED')
  if (!(skipped.status === 'skipped' && skipped.userTextCode === 'UPSTREAM_FAILED'
    && skipped.decision === null && skipped.requiresAction === false)) {
    throw new ScenarioNotRun('ORDINARY_SKIPPED_FIXTURE_NOT_FORMED')
  }
  checks.require('live.first-halts-batch', ['rejected', 'failed', 'blocked'].includes(first.status),
    'the first W/J/P call halts the batch through a real decision', first)
  context.toolCallId = skipped.toolCallId
  context.runId = snapshot.activeRunId
  const phases = [await assertSkippedPhase(context, 'live', skipped.toolCallId, first.toolCallId,
    {expectedCode: 'UPSTREAM_FAILED', expectedNewTasks: 0, requireOtherVisible: null})]
  const requestBaseline = browser.requestCounts()
  checks.require('live.single-reject-confirm', requestBaseline.confirm === 1, 1, requestBaseline)
  checks.require('live.single-chat', requestBaseline.chat === 1, 1, requestBaseline)
  for (const phase of ['history', 'refresh-1', 'refresh-2']) {
    browser.setPhase(phase)
    await browser.reload()
    phases.push(await assertSkippedPhase(context, phase, skipped.toolCallId, first.toolCallId,
      {expectedCode: 'UPSTREAM_FAILED', expectedNewTasks: 0, requireOtherVisible: null}))
    checks.require(`${phase}.no-repeat-decision-or-side-effect`, canonicalJson(browser.requestCounts()) === canonicalJson(requestBaseline),
      requestBaseline, browser.requestCounts())
  }
  checks.require('refresh.skipped-history-stable', phases.every((phase) => phase.historySignature === phases[0].historySignature),
    phases[0].historySignature, phases.map((phase) => phase.historySignature))
  checks.require('refresh.skipped-business-stable', phases.every((phase) => phase.business.fingerprint === phases[0].business.fingerprint),
    phases[0].business.fingerprint, phases.map((phase) => phase.business.fingerprint))
}

const FORM_BODY_PARTS = ['甲段独立内容', '乙段不是甲段前缀', '丙段收尾且不同于前两段']

function hasCompleteFormBody(message) {
  if (!message) return false
  const body = bodyPartsForCopy(message).join('\n\n')
  return FORM_BODY_PARTS.every((expected) => body.includes(expected))
}

async function assertFormPhase(context, phase, toolCallId) {
  const {browser, api, checks} = context
  await browser.waitFor(`(() => {
    const visible = (element) => !!(element && (element.offsetParent !== null || element.getClientRects().length > 0))
    const cards = [...document.querySelectorAll('article[data-testid]')].filter((element) => {
      const id = element.getAttribute('data-testid')
      const receipt = id === 'hitl-receipt' && element.querySelector('[data-testid="ro-body-${toolCallId}"],[data-testid="ro-form-${toolCallId}"]')
      return id === 'tool-card-${toolCallId}' || id === 'pending-card-${toolCallId}' || receipt
    }).filter(visible)
    const state = cards[0]?.querySelector('[data-card-state]')?.getAttribute('data-card-state')
    const testId = cards[0]?.getAttribute('data-testid')
    return cards.length === 1 && (state === 'succeeded' || (testId === 'hitl-receipt' && state === 'submitted'))
  })()`, 'FORM_SUCCEEDED_CARD_NOT_VISIBLE')
  const snapshot = await browser.snapshot()
  const historyEvidence = await api.history(snapshot.sessionId)
  const history = requiredHistory(historyEvidence)
  const business = await businessSnapshot(api, relevantTaskIds(context))
  context.sessionId = snapshot.sessionId
  const target = targetPart(snapshot, toolCallId)
  checks.require(`${phase}.form.terminal-and-body-complete`, snapshot.loading === false
    && target?.message.done === true && hasCompleteFormBody(target.message),
  'host message is terminal and all three deterministic text parts are present', {
    loading: snapshot.loading,
    done: target?.message.done ?? null,
    completeBody: hasCompleteFormBody(target?.message)
  })
  checks.require(`${phase}.form.store-succeeded`, target?.part.status === 'succeeded' && target.message.done,
    'submitted generative form succeeded in a terminal assistant message', target ?? null)
  const cards = targetCard(snapshot, toolCallId)
  checks.require(`${phase}.form.single-succeeded-card`, cards.length === 1
    && (cards[0].state === 'succeeded' || (cards[0].testId === 'hitl-receipt' && cards[0].state === 'submitted')),
  'one visible succeeded tool card or submitted generative form receipt', cards)
  const occurrences = historyToolOccurrences(history, toolCallId)
  checks.require(`${phase}.form.history-frontend-result-once`, occurrences.states.length === 1
    && occurrences.states[0] === 'FRONTEND_RESULT' && occurrences.responses.length === 1
    && occurrences.responses[0] === 'generative_form',
  {states: ['FRONTEND_RESULT'], responses: ['generative_form']}, occurrences)
  checks.require(`${phase}.form.no-business-side-effects`, business.fingerprint === context.businessAfterFixture.fingerprint,
    context.businessAfterFixture.fingerprint, business.fingerprint)
  checks.require(`${phase}.locale`, snapshot.locale === context.config.locale, context.config.locale, snapshot.locale)
  const requiredTexts = assertMixedBodyInHistory(context, phase, target.message, history)
  const requiredTitle = visibleToolSummary(snapshot, toolCallId, context.config.locale)
  await copyMessageAndAssert(context, phase, target.message.id, {requiredTitle, requiredTexts})
  await savePhase(context, phase, snapshot, historyEvidence, business)
  return {historySignature: canonicalJson(historySummary(history)), business}
}

async function runFormScenario(context) {
  const {browser, checks} = context
  context.businessAfterFixture = await businessSnapshot(context.api, relevantTaskIds(context))
  browser.setPhase('live')
  await browser.sendPrompt(promptFor(context))
  const pending = await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    const active = ai.activeForm
    if (active?.toolCallId && ai.messages.some((message) => message.parts.some((part) => part.kind === 'tool'
      && part.toolCallId === active.toolCallId && part.name === 'generative_form' && part.requiresAction === true))) {
      return {toolCallId: active.toolCallId}
    }
    return ai.loading === false && ai.messages.some((message) => message.role === 'assistant' && message.done)
      ? {notFormed: true} : null
  })()`, 'GENERATIVE_FORM_MIXED_NOT_READY')
  if (pending.notFormed) throw new ScenarioNotRun('GENERATIVE_FORM_MIXED_NOT_FORMED')
  context.toolCallId = pending.toolCallId
  await browser.waitFor(`document.querySelector('[data-testid="gf-field-${pending.toolCallId}-keyword"]') !== null`,
    'GENERATIVE_FORM_REQUIRED_FIELD_MISSING')
  await browser.evaluate(`document.querySelector('[data-testid="gf-field-${pending.toolCallId}-keyword"]').focus()`, false)
  await browser.send('Input.insertText', {text: `verification-${randomUUID()}`})
  await browser.click(`[data-testid="gf-form-${pending.toolCallId}"] [data-testid="gf-submit"]`)
  const succeededToolCallId = await waitForSucceededTool(context, 'generative_form')
  checks.require('live.form.same-tool-call', succeededToolCallId === pending.toolCallId,
    pending.toolCallId, succeededToolCallId)
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    const host = ai.messages.find((message) => message.parts.some((part) => part.toolCallId === '${pending.toolCallId}'))
    if (!host) return false
    const bodyParts = host.parts.filter((part) => part.kind === 'text').map((part) => part.text)
    return ai.loading === false && host.done === true
      && ${JSON.stringify(FORM_BODY_PARTS)}.every((expected) => bodyParts.some((text) => text.includes(expected)))
  })()`, 'FORM_MIXED_TERMINAL_AND_BODY_NOT_COMPLETE')
  const phases = [await assertFormPhase(context, 'live', pending.toolCallId)]
  const requestBaseline = browser.requestCounts()
  checks.require('live.form.single-chat', requestBaseline.chat === 1, 1, requestBaseline)
  checks.require('live.form.single-frontend-result', requestBaseline.frontendToolResult === 1, 1, requestBaseline)
  for (const phase of ['history', 'refresh-1', 'refresh-2']) {
    browser.setPhase(phase)
    await browser.reload()
    phases.push(await assertFormPhase(context, phase, pending.toolCallId))
    checks.require(`${phase}.form.no-repeat-side-effect-requests`, canonicalJson(browser.requestCounts()) === canonicalJson(requestBaseline),
      requestBaseline, browser.requestCounts())
  }
  checks.require('refresh.form-history-stable', phases.every((phase) => phase.historySignature === phases[0].historySignature),
    phases[0].historySignature, phases.map((phase) => phase.historySignature))
  checks.require('refresh.form-business-stable', phases.every((phase) => phase.business.fingerprint === phases[0].business.fingerprint),
    phases[0].business.fingerprint, phases.map((phase) => phase.business.fingerprint))
}

async function waitRecoveryMessageProjection(browser, expectedCount) {
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.messages.length === ${expectedCount} && ai.messages.every((message) => message.done === true)
  })()`, 'RECOVERY_MESSAGE_PROJECTION_NOT_READY')
}

function operationEvidence(operations, names) {
  return Object.fromEntries(names.map((name) => [name, operations[name]])
    .filter((entry) => entry[1] !== undefined && entry[1].length > 0))
}

function assertWriteOperationsDisabled(context, phase, operations, messages = null) {
  const names = ['send', 'stop', 'continueRound', 'newChat', 'gate', 'form', 'upload', 'sheetWrite']
  const actual = operationEvidence(operations, names)
  const enabled = Object.entries(actual).flatMap(([name, entries]) => entries.filter((entry) => !entry.disabled)
    .map((entry) => ({name, testId: entry.testId, visible: entry.visible})))
  context.checks.require(`${phase}.write-operations-disabled`, enabled.length === 0,
    'new-round and pending business writes are disabled without disabling terminal message copy/feedback', {actual, enabled})
  context.checks.require(`${phase}.send-control-present`, operations.send.length + operations.stop.length > 0,
    'a real send or stop control exists to prove the disabled state', {
      send: operations.send,
      stop: operations.stop
    })
  // 终态历史消息的“编辑回填”与复制/赞踩同属消息级只读操作（C-146 规格已确认允许）；
  // 只禁止编辑当前未决/未终态消息。
  const pendingIds = new Set((messages ?? []).filter((message) => messagePending(message) || message.done !== true)
    .map((message) => message.id))
  const enabledEdits = operations.edit.filter((entry) => entry.visible && !entry.disabled)
  const forbiddenEdits = enabledEdits.filter((entry) => pendingIds.has(String(entry.testId).replace(/^edit-msg-/, '')))
  context.checks.require(`${phase}.edit-only-terminal-messages`, forbiddenEdits.length === 0,
    'edit/refill is allowed only on terminal messages, never on the unresolved current message',
    {enabledEdits, forbiddenEdits, pendingIds: [...pendingIds]})
  const levels = operations.previewLevel.filter((entry) => entry.visible)
  context.checks.require(`${phase}.readonly-preview-level-unrestricted`, levels.every((entry) => !entry.disabled),
    'read-only preview level controls are not disabled by recovery', levels)
  const pages = operations.previewPagination.filter((entry) => entry.visible)
  context.checks.require(`${phase}.readonly-preview-pagination-unrestricted`, pages.length === 0 || pages.some((entry) => !entry.disabled),
    'multi-page read-only preview keeps pagination available during recovery', pages)
}

/**
 * 手动恢复失败态的门禁：只禁"发送/继续/未决业务写"，允许新对话、编辑回填、复制赞踩与只读预览。
 * 与普通恢复（assertWriteOperationsDisabled）分开，避免把两类规格混成一个断言。
 */
function assertManualFailureOperations(context, phase, operations) {
  const disabledNames = ['send', 'stop', 'continueRound', 'gate', 'form', 'upload', 'sheetWrite']
  const actual = operationEvidence(operations, disabledNames)
  const enabled = Object.entries(actual).flatMap(([name, entries]) => entries.filter((entry) => !entry.disabled)
    .map((entry) => ({name, testId: entry.testId, visible: entry.visible})))
  context.checks.require(`${phase}.manual-writes-disabled`, enabled.length === 0,
    'manual failure keeps send/continue/pending business writes disabled', {actual, enabled})
  context.checks.require(`${phase}.send-control-present`, operations.send.length + operations.stop.length > 0,
    'a real send or stop control exists to prove the disabled state', {
      send: operations.send,
      stop: operations.stop
    })
  const newChat = operations.newChat.filter((entry) => entry.visible)
  context.checks.require(`${phase}.manual-new-chat-enabled`, newChat.length === 1 && !newChat[0].disabled,
    'new-chat stays enabled during manual failure', newChat)
  const edit = operations.edit.filter((entry) => entry.visible)
  context.checks.require(`${phase}.manual-edit-enabled`, edit.length > 0 && edit.every((entry) => !entry.disabled),
    'terminal message edit/refill stays enabled during manual failure', edit)
  const copy = operations.copy.filter((entry) => entry.visible)
  const feedback = operations.feedback.filter((entry) => entry.visible)
  if (copy.length > 0 || feedback.length > 0) {
    context.checks.require(`${phase}.manual-copy-feedback-enabled`,
      [...copy, ...feedback].every((entry) => !entry.disabled),
      'copy and feedback stay enabled during manual failure', {copy, feedback})
  }
  const levels = operations.previewLevel.filter((entry) => entry.visible)
  context.checks.require(`${phase}.readonly-preview-level-unrestricted`, levels.every((entry) => !entry.disabled),
    'read-only preview level controls are not disabled by manual failure', levels)
}

/** 先输入非空草稿，再断言发送仍禁用（避免"空输入禁用"假通过）。 */
async function assertSendDisabledWithDraft(context, phase) {
  const {browser, checks} = context
  await browser.evaluate(`document.querySelector('[data-testid="composer-input"]').focus()`, false)
  await browser.send('Input.insertText', {text: `manual-failure-draft-${Date.now()}`})
  const state = await browser.waitFor(`(() => {
    const input = document.querySelector('[data-testid="composer-input"]')
    const send = document.querySelector('[data-testid="send-btn"]')
    if (!input || !send || input.value.length === 0) return null
    return {valueLength: input.value.length, disabled: send.disabled === true || send.getAttribute('aria-disabled') === 'true'}
  })()`, 'DRAFT_NOT_ENTERED')
  checks.require(`${phase}.send-disabled-with-draft`, state.disabled === true,
    'send stays disabled even with a non-empty draft during manual failure', state)
  await browser.evaluate(`(() => {
    const input = document.querySelector('[data-testid="composer-input"]')
    if (!input) return null
    input.value = ''
    input.dispatchEvent(new Event('input', {bubbles: true}))
    return input.value.length
  })()`, false)
}

function messagePending(message) {
  return message.parts.some((part) => part.kind === 'tool' && part.requiresAction === true)
}

function visibleEnabled(entries) {
  return entries.filter((entry) => entry.visible && !entry.disabled)
}

function assertMessagePresentation(context, phase, view, mode) {
  const domById = new Map(view.operations.messages.map((message) => [message.messageId, message]))
  context.checks.require(`${phase}.message-dom-complete`, domById.size === view.snapshot.messages.length,
    'every store message has exactly one DOM projection', {
      store: view.snapshot.messages.map((message) => ({id: message.id, role: message.role})),
      dom: view.operations.messages.map((message) => message.messageId)
    })
  for (const message of view.snapshot.messages) {
    const dom = domById.get(message.id)
    if (!dom) throw new AssertionFailure(`${phase}.message-dom-missing`)
    const id = `${phase}.${message.id}`
    const activity = dom.waitingCount + dom.compactingCount + dom.pulseDotTotal + (dom.activityTextPresent ? 1 : 0)
    if (message.role === 'user') {
      context.checks.require(`${id}.user-always-terminal`, message.done === true && !message.streaming
        && dom.waitingCount === 0 && dom.compactingCount === 0 && dom.pulseDotTotal === 0 && !dom.activityTextPresent,
      'user messages never acquire assistant activity state', {message: {done: message.done, streaming: message.streaming}, dom})
      context.checks.require(`${id}.user-terminal-copy`, dom.timestampCount === 1 && visibleEnabled(dom.actions.copy).length === 1,
        'terminal user timestamp and copy remain available', dom)
      continue
    }
    const pending = messagePending(message)
    if (!message.done || pending) {
      const expectedActivity = mode === 'recovering' ? 'three-dot-pulse' : mode === 'pending' ? 'waiting' : 'none'
      const pulseValid = mode === 'recovering' && dom.pulseDotTotal === 3 && dom.recoveryAnimation.valid && !dom.activityTextPresent
      const waitingValid = mode === 'pending' && dom.waitingCount === 1
      context.checks.require(`${id}.nonterminal-activity-exclusive`, (pulseValid || waitingValid)
        && dom.compactingCount === 0 && dom.timestampCount === 0 && dom.terminalMarkers.length === 0
        && dom.copyHintCount === 0 && !dom.copySuccess,
      expectedActivity,
      {mode, pending, done: message.done, activity, dom})
      const actions = [...dom.actions.copy, ...dom.actions.feedback, ...dom.actions.edit, ...dom.actions.continueRound]
      context.checks.require(`${id}.nonterminal-message-actions-hidden`, !dom.actionGroupVisible
        && actions.every((entry) => !entry.visible),
      'the entire message action group is hidden; no copy or feedback click is attempted', {actionGroupVisible: dom.actionGroupVisible, actions})
      continue
    }
    context.checks.require(`${id}.terminal-no-activity`, dom.waitingCount === 0 && dom.compactingCount === 0
      && dom.pulseDotTotal === 0 && !dom.activityTextPresent,
    'terminal assistant has no working/waiting/compacting indicator', dom)
    context.checks.require(`${id}.terminal-actions-normal`, dom.timestampCount === 1 && dom.actionGroupVisible
      && visibleEnabled(dom.actions.copy).length === 1 && visibleEnabled(dom.actions.feedback).length === 2,
    'terminal assistant timestamp, copy and both feedback actions remain enabled during unrelated recovery', dom)
  }
}

async function assertDraftAndTerminalCopy(context, phase, view) {
  const {browser, checks} = context
  const before = browser.requestCounts()
  const terminal = view.snapshot.messages.find((message) => message.role === 'assistant' && message.done && !messagePending(message))
  if (terminal) await copyMessageAndAssert(context, `${phase}-terminal`, terminal.id)
  const draft = `draft-${randomUUID()}`
  await browser.evaluate(`document.querySelector('[data-testid="composer-input"]').focus()`, false)
  await browser.send('Input.insertText', {text: draft})
  const composer = await browser.evaluate(`(() => {
    const input = document.querySelector('[data-testid="composer-input"]')
    return {length: input.value.length, disabled: input.disabled === true}
  })()`, false)
  checks.require(`${phase}.composer-remains-editable`, composer.length > 0 && composer.disabled === false,
    'draft text can be edited while new-round actions remain disabled', composer)
  checks.require(`${phase}.no-send-side-effect`, browser.requestCounts().chat === before.chat,
    before.chat, browser.requestCounts())
}

async function saveRecoveryPhase(context, phase, view, historyEvidence, business) {
  await context.evidence.json(`operations-${phase}.json`, view.operations)
  await savePhase(context, phase, view.snapshot, historyEvidence, business)
}

async function assertRestoringGate(context, phase, historyEvidence, business) {
  const {browser, checks} = context
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    if (ai.restoringRecovery !== true) return false
    const runId = ai.activeRunId ?? ai.runId ?? null
    return ai.messages.some((message) => {
      if (message.role !== 'assistant') return false
      if (runId && message.runId !== runId) return false
      return message.done !== true || message.parts.some((part) => part.kind === 'tool' && part.requiresAction === true)
    })
  })()`, 'RESTORING_RECOVERY_NOT_ACTIVE')
  const view = await recoveryView(browser)
  checks.require(`${phase}.store-recovering`, view.snapshot.restoringRecovery && view.snapshot.runPhase === 'recovering',
    'Pinia is in restoringRecovery/recovering', {
      restoringRecovery: view.snapshot.restoringRecovery,
      runPhase: view.snapshot.runPhase
    })
  const nonterminal = view.snapshot.messages.filter((message) => message.role === 'assistant' && (!message.done || messagePending(message)))
  checks.require(`${phase}.nonterminal-assistant-present`, nonterminal.length > 0,
    'at least one genuinely nonterminal or pending assistant is restored', nonterminal.map((message) => ({id: message.id, done: message.done})))
  checks.require(`${phase}.no-waiting-or-compacting-during-recovery`, view.operations.waitingCount === 0
    && view.operations.compactingCount === 0,
  'recovery uses only per-message three-dot pulses, never working/waiting/compacting text', view.operations)
  checks.require(`${phase}.no-manual-failure-yet`, view.snapshot.manualRecovery === null,
    'automatic recovery has not published a manual failure', view.snapshot.manualRecovery)
  assertMessagePresentation(context, phase, view, 'recovering')
  assertWriteOperationsDisabled(context, phase, view.operations, view.snapshot.messages)
  await assertDraftAndTerminalCopy(context, phase, view)
  await saveRecoveryPhase(context, phase, view, historyEvidence, business)
}

async function assertManualRecoveryGate(context, phase, historyEvidence, business) {
  const {checks} = context
  const view = await recoveryView(context.browser)
  checks.require(`${phase}.manual-recovery-real-failure`, view.snapshot.restoringRecovery === false
    && view.snapshot.manualRecovery !== null && view.snapshot.recoveryRetries.length === 1,
  'manualRecovery exposes the real failure and exactly one retry', {
    restoringRecovery: view.snapshot.restoringRecovery,
    manualRecovery: view.snapshot.manualRecovery,
    retries: view.snapshot.recoveryRetries
  })
  checks.require(`${phase}.manual-retry-enabled`, view.snapshot.recoveryRetries.every((retry) => !retry.disabled),
    'manual recovery retry is enabled', view.snapshot.recoveryRetries)
  assertMessagePresentation(context, phase, view, 'terminal')
  assertManualFailureOperations(context, phase, view.operations)
  await assertSendDisabledWithDraft(context, phase)
  await assertDraftAndTerminalCopy(context, phase, view)
  await saveRecoveryPhase(context, phase, view, historyEvidence, business)
}

async function assertPendingRecoveryComplete(context, phase, historyEvidence, business) {
  const {checks} = context
  await context.browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    const pending = ai.messages.some((message) => message.parts.some((part) => part.kind === 'tool' && part.requiresAction === true))
    const controls = [...document.querySelectorAll('[data-testid="gate-actions"] button,[data-testid="gf-submit"],[data-testid="gf-cancel"],[data-testid="gf-queue-cancel"],[data-testid="upload-handoff-choose"],[data-testid="upload-handoff-cancel"]')]
    return ai.restoringRecovery === false && ai.manualRecovery === null && pending && controls.length > 0
      && controls.every((element) => !element.disabled && element.getAttribute('aria-disabled') !== 'true')
  })()`, 'PENDING_ACTIONS_NOT_READY_AFTER_RECOVERY')
  const view = await recoveryView(context.browser)
  checks.require(`${phase}.recovery-complete`, view.snapshot.restoringRecovery === false && view.snapshot.manualRecovery === null,
    'recovery completed without a manual failure', {
      restoringRecovery: view.snapshot.restoringRecovery,
      manualRecovery: view.snapshot.manualRecovery
    })
  const pending = view.snapshot.messages.filter((message) => message.role === 'assistant' && messagePending(message))
  checks.require(`${phase}.pending-message-present`, pending.length > 0,
    'the actual pending HITL/form host is still pending after recovery', pending.map((message) => message.id))
  const pendingControls = [...view.operations.gate, ...view.operations.form, ...view.operations.upload]
  checks.require(`${phase}.pending-actions-enabled`, pendingControls.length > 0
    && pendingControls.every((entry) => !entry.disabled),
  'the actual pending HITL/form/upload actions are enabled after recovery', pendingControls)
  assertMessagePresentation(context, phase, view, 'pending')
  await assertDraftAndTerminalCopy(context, phase, view)
  await saveRecoveryPhase(context, phase, view, historyEvidence, business)
}

async function assertTerminalRecoveryComplete(context, phase, historyEvidence, business) {
  const {checks} = context
  const view = await recoveryView(context.browser)
  checks.require(`${phase}.terminal-real-state`, view.snapshot.restoringRecovery === false
    && view.snapshot.manualRecovery === null && view.snapshot.loading === false
    && view.snapshot.messages.filter((message) => message.role === 'assistant').every((message) => message.done && !messagePending(message)),
  'all assistant messages are really terminal after recovery', {
    restoringRecovery: view.snapshot.restoringRecovery,
    manualRecovery: view.snapshot.manualRecovery,
    loading: view.snapshot.loading,
    messages: view.snapshot.messages.map((message) => ({role: message.role, done: message.done}))
  })
  checks.require(`${phase}.terminal-timestamp-no-activity`, view.operations.timestampCount > 0
    && view.operations.waitingCount === 0 && view.operations.workingCount === 0 && view.operations.compactingCount === 0,
  'terminal timestamps are visible with no working/waiting/compacting indicator', view.operations)
  const chatBeforeDraft = context.browser.requestCounts().chat
  await context.browser.evaluate(`document.querySelector('[data-testid="composer-input"]').focus()`, false)
  await context.browser.send('Input.insertText', {text: `recovery-draft-${Date.now()}`})
  const sendWithDraft = await context.browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    const input = document.querySelector('[data-testid="composer-input"]')
    const send = document.querySelector('[data-testid="send-btn"]')
    if (!input || !send || input.value.length === 0) return null
    return {canSend: ai.canSend === true, disabled: send.disabled === true || send.getAttribute('aria-disabled') === 'true', valueLength: input.value.length}
  })()`, 'SEND_NOT_EVALUABLE_WITH_DRAFT')
  checks.require(`${phase}.send-reopened-by-real-state`, sendWithDraft.canSend === true && sendWithDraft.disabled === false,
    'send is enabled only after the real terminal state, verified with a non-empty draft', sendWithDraft)
  checks.require(`${phase}.no-chat-from-draft`, context.browser.requestCounts().chat === chatBeforeDraft,
    'typing a draft never sends a chat request', {before: chatBeforeDraft, after: context.browser.requestCounts().chat})
  await context.browser.evaluate(`(() => {
    const input = document.querySelector('[data-testid="composer-input"]')
    if (!input) return null
    input.value = ''
    input.dispatchEvent(new Event('input', {bubbles: true}))
    return input.value.length
  })()`, false)
  assertMessagePresentation(context, phase, view, 'terminal')
  await assertDraftAndTerminalCopy(context, phase, view)
  await saveRecoveryPhase(context, phase, view, historyEvidence, business)
}

function assertNoRecoveryNotices(context, phase, snapshot) {
  const observed = snapshot.observation?.noticeSamples?.flatMap((sample) => sample.notices) ?? []
  context.checks.require(`${phase}.automatic-recovery-no-extra-notice`, snapshot.recoverySurfaces.length === 0 && observed.length === 0,
    'zero recovery banner/chip/message marker during and after automatic recovery', {
      current: snapshot.recoverySurfaces,
      observed
    })
  context.checks.require(`${phase}.automatic-recovery-no-retry`, snapshot.recoveryRetries.length === 0,
    'zero retry action after automatic success', snapshot.recoveryRetries)
}

async function waitAutomaticFinalFailure(context, phase) {
  const {browser, checks} = context
  await browser.waitFor(`(() => {
    const visible = (element) => !!(element && (element.offsetParent !== null || element.getClientRects().length > 0))
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.loading === false && [...document.querySelectorAll(${JSON.stringify(recoveryRetrySelector)})].some(visible)
  })()`, 'AUTOMATIC_RECOVERY_BUDGET_NOT_EXHAUSTED')
  const network = await browser.waitForHistoryPhase(phase, {minimumFaultCount: 1})
  checks.require(`${phase}.automatic-budget-terminal-http-evidence`, network.faultCount >= 1 && network.successCount === 0
    && network.pendingCount === 0 && network.statuses.every((status) => status === 503),
  'retry became visible only after the automatic budget; every history request actually completed with injected 503',
  network)
  const snapshot = await browser.snapshot()
  checks.require(`${phase}.final-state-still-requires-retry`, snapshot.recoveryRetries.length === 1,
    'final failure remains visible after all history requests are terminal', snapshot.recoveryRetries)
  return network
}

async function assertFinalRecoveryFailure(context, phase, initialSignature, initialBusiness, network) {
  await waitRecoveryMessageProjection(context.browser, JSON.parse(initialSignature).length)
  const snapshot = await context.browser.snapshot()
  context.checks.require(`${phase}.final-failure.single-notice`, snapshot.recoverySurfaces.length <= 1,
    'at most one concise recovery notice', snapshot.recoverySurfaces)
  context.checks.require(`${phase}.final-failure.one-retry`, snapshot.recoveryRetries.length === 1,
    'exactly one retry button', snapshot.recoveryRetries)
  const noticeText = snapshot.recoverySurfaces.map((surface) => surface.text).join('\n')
  const retryText = snapshot.recoveryRetries[0]?.text ?? ''
  const retryLabelValid = context.config.locale === 'zh-CN'
    ? retryText.trim() === '重试'
    : retryText.trim() === 'Retry'
  context.checks.require(`${phase}.final-failure.concise-localized-retry`, retryLabelValid,
    context.config.locale === 'zh-CN' ? '重试' : 'Retry', retryText)
  context.checks.require(`${phase}.final-failure.no-technical-or-legacy-copy`, noticeText.length <= 240
    && !/runId|toolCallId|Redis|recoveryGap|重新载入|立即重试|重试恢复|状态未确认|结局未知|结果未同步/i.test(noticeText),
  'short user-facing notice without technical or removed wording', {length: noticeText.length, text: noticeText})
  context.checks.require(`${phase}.final-failure.content-not-fabricated`, canonicalJson(messageContentSignature(snapshot)) === initialSignature,
    initialSignature, canonicalJson(messageContentSignature(snapshot)))
  const business = await businessSnapshot(context.api, relevantTaskIds(context))
  context.checks.require(`${phase}.final-failure.no-business-side-effects`, business.fingerprint === initialBusiness.fingerprint,
    initialBusiness.fingerprint, business.fingerprint)
  const historyEvidence = await context.api.history(snapshot.sessionId)
  await savePhase(context, phase, snapshot, historyEvidence, business)
  await context.evidence.json(`history-network-${phase}.json`, network)
  return snapshot
}

async function recoveryPhaseEvidence(context) {
  const snapshot = await context.browser.snapshot()
  const historyEvidence = await context.api.history(snapshot.sessionId)
  const business = await businessSnapshot(context.api, relevantTaskIds(context))
  context.sessionId = snapshot.sessionId
  context.runId ??= snapshot.activeRunId
  return {historyEvidence, business}
}

function recoveryFactsSignature(snapshot) {
  return canonicalJson({
    messages: messageContentSignature(snapshot),
    activeForm: snapshot.activeForm ? {
      toolCallId: snapshot.activeForm.toolCallId,
      runId: snapshot.activeForm.runId,
      status: snapshot.activeForm.status,
      values: sha256(snapshot.activeForm.values)
    } : null,
    queuedForms: snapshot.queuedForms,
    cards: snapshot.cards.map((card) => ({testId: card.testId, bodyTestId: card.bodyTestId, state: card.state}))
  })
}

// 仅 C-161 GF standard 使用：恢复重投影会给 parts 重新编号（seq 非用户可见），
// 稳定性指纹排除可选 seq；有 segments 的条目另按 entryId+seq 单列幂等核对。
function recoveryFactsSignatureWithoutSeq(snapshot) {
  const facts = JSON.parse(recoveryFactsSignature(snapshot))
  for (const message of facts.messages) {
    for (const part of message.parts ?? []) delete part.seq
  }
  return canonicalJson(facts)
}

async function mirrorState(browser, sessionId) {
  return await browser.evaluate(`(() => {
    const entries = []
    for (let index = 0; index < sessionStorage.length; index += 1) {
      const key = sessionStorage.key(index)
      if (!key || !key.startsWith('ai-mirror:') || !(key === 'ai-mirror:${sessionId}' || key.endsWith(':${sessionId}'))) continue
      try {
        const value = JSON.parse(sessionStorage.getItem(key) ?? 'null')
        entries.push({
          inFlight: value?.inFlight === true,
          lastRunId: value?.lastRunId ?? null,
          messageCount: Array.isArray(value?.messages) ? value.messages.length : null
        })
      } catch {
        entries.push({inFlight: null, lastRunId: null, messageCount: null})
      }
    }
    return {sessionId: document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai').sessionId, entries}
  })()`, false)
}

async function removeCurrentMirror(context) {
  const sessionId = context.sessionId
  const before = await mirrorState(context.browser, sessionId)
  const removed = await context.browser.evaluate(`(() => {
    const keys = []
    for (let index = 0; index < sessionStorage.length; index += 1) {
      const key = sessionStorage.key(index)
      if (key && key.startsWith('ai-mirror:') && (key === 'ai-mirror:${sessionId}' || key.endsWith(':${sessionId}'))) keys.push(key)
    }
    for (const key of keys) sessionStorage.removeItem(key)
    return keys.length
  })()`, false)
  const after = await mirrorState(context.browser, sessionId)
  context.checks.require('mirror.removed-without-changing-session', removed > 0 && after.entries.length === 0 && after.sessionId === sessionId,
    'only the current session mirror is removed; identity is preserved', {before, removed, after})
  return {before, removed, after}
}

function pendingHistoryCoverage(history, toolCallIds) {
  const assistants = history.messages.filter((message) => String(message.type ?? message.role ?? '').toUpperCase() === 'ASSISTANT')
  const states = assistants.flatMap((message) => Object.entries(message.toolCallStates ?? {})
    .filter(([id]) => toolCallIds.includes(id)).map(([id, state]) => ({id, status: state?.status ?? null, code: state?.code ?? null})))
  const running = states.some((state) => state.status === 'RUNNING') || assistants.some((message) =>
    (message.segments ?? []).some((segment) => toolCallIds.includes(segment?.toolCallId) && segment?.status === 'running'))
  return {
    messageCount: history.messages.length,
    emptyHistory: history.messages.length === 0,
    missingSegments: assistants.length > 0 && assistants.some((message) => !Array.isArray(message.segments)),
    missingToolCallStates: assistants.length > 0 && assistants.some((message) => !isRecord(message.toolCallStates)),
    oldRunning: running,
    states,
    assistantText: assistants.map((message) => textEvidence(message.text ?? message.content ?? ''))
  }
}

function requirePendingFixture(config, coverage) {
  const expected = config.pendingFixture
  const valid = new Set(['standard', 'missing-segments', 'missing-states', 'empty-history', 'old-running'])
  if (!valid.has(expected)) throw new Error(`PENDING_FIXTURE must select exactly one of ${[...valid].join(', ')}`)
  const formed = expected === 'standard'
    || (expected === 'missing-segments' && coverage.missingSegments)
    || (expected === 'missing-states' && coverage.missingToolCallStates)
    || (expected === 'empty-history' && coverage.emptyHistory)
    || (expected === 'old-running' && coverage.oldRunning)
  if (!formed) throw new ScenarioNotRun(`PENDING_${expected.replaceAll('-', '_').toUpperCase()}_NOT_FORMED`)
}

async function assertPendingTrajectory(context, phase, toolCallIds, {requireDecision = false} = {}) {
  const samples = context.latestSnapshot?.observation?.samples ?? []
  const violations = []
  const legalTransitions = []
  const presenceTransitions = []
  context.pendingPresenceTracks ??= new Map()
  for (const toolCallId of toolCallIds) {
    const track = context.pendingPresenceTracks.get(toolCallId) ?? {
      observerId: null,
      lastSequence: 0,
      previousPart: null,
      previousPartPresent: null,
      previousDomPresent: null,
      partSeen: false,
      domSeen: false,
      partVanished: false,
      domVanished: false
    }
    for (const sample of samples) {
      if (typeof sample.observerId !== 'string' || !Number.isSafeInteger(sample.sequence) || sample.sequence <= 0) {
        throw new AssertionFailure('OBSERVER_SEQUENCE_MISSING')
      }
      if (track.observerId === sample.observerId && sample.sequence <= track.lastSequence) continue
      // 刷新会换页/换 observer：新页首帧的“不在场”是页面重建过程，不是同页 1→0；
      // 因此换 observer 时只重置在场基线与消失标记，保留 previousPart 以便跨刷新的
      // “已决不再复活”“批准/提交转已决”事实仍可判定。
      if (track.observerId !== null && track.observerId !== sample.observerId) {
        track.previousPartPresent = null
        track.previousDomPresent = null
        track.partVanished = false
        track.domVanished = false
      }
      const part = sample.tools.find((candidate) => candidate.toolCallId === toolCallId) ?? null
      const partPresent = part !== null
      const domPresent = sample.cards.some((card) => card.testId === `tool-card-${toolCallId}` || card.testId === `pending-card-${toolCallId}`)
      track.partSeen ||= partPresent
      track.domSeen ||= domPresent
      // 只在“确实出现过之后又消失、随后再次出现”时判 1→0→1；单纯迟到出现不是违规。
      if (track.previousPartPresent === true && !partPresent) track.partVanished = true
      if (track.partVanished && partPresent) {
        violations.push({atMs: sample.atMs, toolCallId, code: 'STORE_PART_ONE_ZERO_ONE'})
      }
      if (track.previousDomPresent === true && !domPresent) track.domVanished = true
      if (track.domVanished && domPresent) {
        violations.push({atMs: sample.atMs, toolCallId, code: 'DOM_ONE_ZERO_ONE'})
      }
      presenceTransitions.push({
        observerId: sample.observerId,
        sequence: sample.sequence,
        atMs: sample.atMs,
        toolCallId,
        partPresent,
        domPresent,
        requiresAction: part?.requiresAction ?? null,
        status: part?.status ?? null
      })
      if (part) {
        if (part.requiresAction && part.status === 'running') {
          violations.push({atMs: sample.atMs, toolCallId, code: 'PENDING_RUNNING'})
        }
        if (track.previousPart?.requiresAction === false && part.requiresAction) {
          const decided = track.previousPart.decision !== null
            || ['succeeded', 'rejected', 'cancelled', 'submitted', 'skipped', 'failed', 'expired', 'blocked', 'unknown'].includes(track.previousPart.status)
          if (decided) violations.push({atMs: sample.atMs, toolCallId, code: 'REQUIRES_ACTION_RESURRECTED'})
          else legalTransitions.push({atMs: sample.atMs, toolCallId, kind: 'INCOMPLETE_TO_PENDING', status: part.status, decision: part.decision})
        }
        if (track.previousPart?.requiresAction === true && !part.requiresAction) {
          legalTransitions.push({atMs: sample.atMs, toolCallId, kind: 'DECIDED', status: part.status, decision: part.decision})
          context.pendingDecidedToolCalls ??= new Set()
          context.pendingDecidedToolCalls.add(toolCallId)
        }
        const message = sample.recovery?.messages?.find((candidate) => candidate.id === part.messageId)
        const dom = sample.operations?.messages?.find((candidate) => candidate.messageId === part.messageId)
        if (part.requiresAction && message && dom?.workingTextPresent) {
          violations.push({atMs: sample.atMs, toolCallId, code: 'WORKING_INSERTED_WHILE_PENDING'})
        }
      }
      track.observerId = sample.observerId
      track.lastSequence = sample.sequence
      track.previousPart = part
      track.previousPartPresent = partPresent
      track.previousDomPresent = domPresent
    }
    if (!track.partSeen) violations.push({toolCallId, code: 'STORE_PART_NEVER_FORMED'})
    if (!track.domSeen) violations.push({toolCallId, code: 'DOM_NEVER_FORMED'})
    context.pendingPresenceTracks.set(toolCallId, track)
  }
  const evidence = {toolCallIds, violations, legalTransitions, presenceTransitions: presenceTransitions.slice(-128)}
  await context.evidence.json(`pending-trajectory-${phase}.json`, evidence)
  context.checks.require(`${phase}.pending-trajectory-stable`, violations.length === 0,
    'no store part/DOM 1→0→1, pending running, requiresAction resurrection or inserted working state', violations)
  if (requireDecision) {
    // 决定发生在上一阶段，刷新后的阶段只能看到“已决稳定态”；判定必须跨阶段累计。
    context.pendingDecidedToolCalls ??= new Set()
    for (const entry of legalTransitions) {
      if (entry.kind === 'DECIDED') context.pendingDecidedToolCalls.add(entry.toolCallId)
    }
    context.checks.require(`${phase}.legal-requires-action-false-transition`,
      toolCallIds.every((id) => context.pendingDecidedToolCalls.has(id)),
      'a real reject/cancel transition is distinguished from later stable history', {
        legalTransitions,
        decidedToolCalls: [...context.pendingDecidedToolCalls]
      })
  }
  return evidence
}

function assertRecoveryDotsThenWorking(context, phase, messageId) {
  const samples = context.latestSnapshot?.observation?.samples ?? []
  const recovering = samples.find((sample) => sample.recovery?.restoringRecovery
    && sample.operations?.messages?.find((message) => message.messageId === messageId)?.recoveryAnimation?.valid)
  const working = samples.find((sample) => !sample.recovery?.restoringRecovery && sample.recovery?.loading
    && sample.operations?.messages?.find((message) => message.messageId === messageId)?.workingTextPresent)
  context.checks.require(`${phase}.recovery-dots-then-real-working`, recovering && working && working.atMs > recovering.atMs,
    'custom recovery dots finish before the real streaming working indicator', {
      recoveringAtMs: recovering?.atMs ?? null,
      workingAtMs: working?.atMs ?? null
    })
}

async function triggerPublicRunCurrentRead(browser, sessionId) {
  await browser.evaluate(`(async () => {
    const {aiApi} = await import('/src/api/ai.ts')
    void aiApi.runsCurrent('${sessionId}')
    return true
  })()`, true)
}

async function runHitlRecoveryGate(context) {
  const {browser, checks} = context
  context.businessAfterFixture = await businessSnapshot(context.api, relevantTaskIds(context))
  browser.setPhase('initial')
  await browser.sendPrompt(promptFor(context))
  await approveCreateTasks(context)
  await driveSkippedHostContinuation(context)
  const initial = await browser.snapshot()
  const startPending = initial.messages.flatMap((message) => message.parts).some((part) => part.kind === 'tool'
    && part.name === 'start_export' && part.status === 'pending' && part.requiresAction === true)
  if (!startPending) throw new ScenarioNotRun('START_EXPORT_PENDING_NOT_FORMED')
  context.sessionId = initial.sessionId
  context.runId = initial.activeRunId
  const afterHost = await businessSnapshot(context.api, relevantTaskIds(context))
  for (const task of afterHost.allTasks) {
    if (!context.businessAfterFixture.allTasks.some((before) => before.id === task.id)) context.createdTaskIds.add(task.id)
  }
  const initialHistory = await context.api.history(initial.sessionId)
  const initialSignature = messageContentSignatureDroppingEmptyText(initial)
  await savePhase(context, 'initial', initial, initialHistory, afterHost)
  const requestBaseline = browser.requestCounts()

  browser.setPhase('restore-gate')
  await browser.setHistoryFault('always')
  await browser.reload({waitForHistory: false})
  const restoring = await recoveryPhaseEvidence(context)
  await assertRestoringGate(context, 'restore-gate', restoring.historyEvidence, restoring.business)
  await browser.setHistoryFault('off')
  const network = await browser.waitForHistoryPhase('restore-gate', {minimumFaultCount: 1, minimumSuccessCount: 1})
  await context.evidence.json('history-network-restore-gate.json', network)
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.restoringRecovery === false && ai.manualRecovery === null
  })()`, 'HITL_RECOVERY_NOT_COMPLETED')
  const recovered = await recoveryPhaseEvidence(context)
  await assertPendingRecoveryComplete(context, 'recovered-pending', recovered.historyEvidence, recovered.business)
  checks.require('recovered-pending.decided-receipts-unchanged', messageContentSignatureDroppingEmptyText(await browser.snapshot()) === initialSignature,
    initialSignature, canonicalJson(messageContentSignature(await browser.snapshot())))

  browser.setPhase('refresh-1')
  await browser.reload()
  const refreshed = await recoveryPhaseEvidence(context)
  await assertPendingRecoveryComplete(context, 'refresh-1', refreshed.historyEvidence, refreshed.business)
  checks.require('refresh-1.decided-receipts-unchanged', messageContentSignatureDroppingEmptyText(await browser.snapshot()) === initialSignature,
    initialSignature, canonicalJson(messageContentSignature(await browser.snapshot())))
  const hitlSettled = browser.requestCounts()
  // 恢复重载 + refresh-1 各重挂一次活流（GET /events/）是必需动作；chat/confirm/feedback/resume 与工具回灌严格不变。
  checks.require('refresh.no-repeat-side-effect-requests',
    hitlSettled.chat === requestBaseline.chat
      && hitlSettled.frontendToolResult === requestBaseline.frontendToolResult
      && hitlSettled.confirm === requestBaseline.confirm
      && hitlSettled.feedback === requestBaseline.feedback
      && hitlSettled.resume === requestBaseline.resume
      && hitlSettled.events <= requestBaseline.events + 2,
  'two recovery reloads add no chat/confirm/feedback/resume or tool re-submit; each may reattach the live stream once', {
    baseline: requestBaseline,
    after: hitlSettled
  })
  checks.require('refresh.business-stable', refreshed.business.fingerprint === afterHost.fingerprint,
    afterHost.fingerprint, refreshed.business.fingerprint)
}

async function runStreamRecoveryRefresh(context) {
  const {browser, checks} = context
  context.businessAfterFixture = await businessSnapshot(context.api, relevantTaskIds(context))
  browser.setPhase('initial')
  await browser.sendPrompt(promptFor(context))
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.loading === true && ai.messages.some((message) => message.role === 'assistant' && message.streaming === true)
  })()`, 'STREAMING_REFRESH_WINDOW_NOT_FORMED')
  const initial = await browser.snapshot()
  context.sessionId = initial.sessionId
  context.runId = initial.activeRunId
  const initialHistory = await context.api.history(initial.sessionId)
  await savePhase(context, 'initial-streaming', initial, initialHistory, context.businessAfterFixture)

  browser.setPhase('restore-stream')
  await browser.setHistoryFault('always')
  await browser.reload({waitForHistory: false})
  const restoring = await recoveryPhaseEvidence(context)
  await assertRestoringGate(context, 'restore-stream', restoring.historyEvidence, restoring.business)
  await browser.setHistoryFault('off')
  const network = await browser.waitForHistoryPhase('restore-stream', {minimumFaultCount: 1, minimumSuccessCount: 1})
  await context.evidence.json('history-network-restore-stream.json', network)
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.restoringRecovery === false && ai.loading === false
      && ai.messages.filter((message) => message.role === 'assistant').every((message) => message.done === true)
  })()`, 'STREAM_RECOVERY_TERMINAL_NOT_REACHED')
  const recovered = await recoveryPhaseEvidence(context)
  await assertTerminalRecoveryComplete(context, 'recovered-terminal', recovered.historyEvidence, recovered.business)
  // 恢复中重连活流（GET /api/ai/events/<runId>）是恢复本身的前置动作，不属于刷新副作用；
  // 副作用基线取“恢复已终态”之后，仍要求 refresh-1 不新增任何 chat/confirm/feedback/events/resume。
  const settledBaseline = browser.requestCounts()

  browser.setPhase('refresh-1')
  await browser.reload()
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.restoringRecovery === false && ai.loading === false && ai.historyLoaded === true
  })()`, 'REFRESH_RECOVERY_NOT_SETTLED')
  const refreshed = await recoveryPhaseEvidence(context)
  await assertTerminalRecoveryComplete(context, 'refresh-1', refreshed.historyEvidence, refreshed.business)
  await context.evidence.json('network-requests-refresh-1.json', browser.networkRequests)
  checks.require('refresh.no-repeat-side-effect-requests', canonicalJson(browser.requestCounts()) === canonicalJson(settledBaseline),
    settledBaseline, browser.requestCounts())
  checks.require('refresh.business-stable', refreshed.business.fingerprint === context.businessAfterFixture.fingerprint,
    context.businessAfterFixture.fingerprint, refreshed.business.fingerprint)
}

async function exerciseTerminalFeedback(context, phase, messageId) {
  const {browser, checks} = context
  const before = browser.requestCounts().feedback
  await browser.click(`[data-testid="feedback-up-${messageId}"]`)
  await browser.waitFor(`document.querySelector('[data-testid="feedback-up-${messageId}"]')?.getAttribute('aria-pressed') === 'true'`,
    'TERMINAL_FEEDBACK_UP_NOT_ACCEPTED')
  await browser.click(`[data-testid="feedback-down-${messageId}"]`)
  await browser.waitFor(`document.querySelector('[data-testid="feedback-down-${messageId}"]')?.getAttribute('aria-pressed') === 'true'
    && document.querySelector('[data-testid="feedback-up-${messageId}"]')?.getAttribute('aria-pressed') === 'false'`,
  'TERMINAL_FEEDBACK_DOWN_NOT_ACCEPTED')
  await browser.click(`[data-testid="feedback-down-${messageId}"]`)
  await browser.waitFor(`document.querySelector('[data-testid="feedback-down-${messageId}"]')?.getAttribute('aria-pressed') === 'false'`,
    'TERMINAL_FEEDBACK_CLEAR_NOT_ACCEPTED')
  checks.require(`${phase}.terminal-feedback-real-requests`, browser.requestCounts().feedback === before + 3,
    'up/down/clear feedback each issues one real request while another round is recovering', {
      before,
      after: browser.requestCounts().feedback
    })
}

async function runMixedMessagesRecovery(context) {
  const {browser, checks} = context
  context.businessAfterFixture = await businessSnapshot(context.api, relevantTaskIds(context))
  browser.setPhase('initial-terminal')
  await browser.sendPrompt(`turn-text verification=${randomUUID()}`)
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.loading === false && ai.messages.some((message) => message.role === 'assistant' && message.done === true)
  })()`, 'MIXED_FIRST_TERMINAL_ROUND_NOT_COMPLETED')
  const first = await browser.snapshot()
  const historical = first.messages.find((message) => message.role === 'assistant' && message.done)
  checks.require('initial-terminal.historical-assistant-present', typeof historical?.id === 'string', historical?.id ?? null, null)
  context.sessionId = first.sessionId
  context.runId = first.activeRunId

  browser.setPhase('mixed-pending')
  await browser.sendPrompt(`tool=create_task&args={"type":"EXPORT","conditions":{},"openWizard":false} host-def-select verification=${randomUUID()}`)
  await approveCreateTasks(context)
  context.runId = (await browser.snapshot()).activeRunId ?? context.runId
  await driveSkippedHostContinuation(context)
  const initial = await browser.snapshot()
  const startPending = initial.messages.flatMap((message) => message.parts).some((part) => part.kind === 'tool'
    && part.name === 'start_export' && part.status === 'pending' && part.requiresAction === true)
  if (!startPending) throw new ScenarioNotRun('START_EXPORT_PENDING_NOT_FORMED')
  const pending = initial.messages.find((message) => message.role === 'assistant' && messagePending(message))
  checks.require('mixed-pending.two-distinct-assistant-states', initial.messages.some((message) => message.id === historical.id && message.done)
    && pending && pending.id !== historical.id,
  'one historical terminal assistant and one distinct currently pending assistant coexist', initial.messages.map((message) => ({
    id: message.id,
    role: message.role,
    done: message.done,
    pending: messagePending(message)
  })))
  context.sessionId = initial.sessionId
  const afterHost = await businessSnapshot(context.api, relevantTaskIds(context))
  for (const task of afterHost.allTasks) {
    if (!context.businessAfterFixture.allTasks.some((before) => before.id === task.id)) context.createdTaskIds.add(task.id)
  }
  const initialHistory = await context.api.history(initial.sessionId)
  await savePhase(context, 'mixed-pending', initial, initialHistory, afterHost)
  const initialSignature = messageContentSignatureDroppingEmptyText(initial)
  const requestBaseline = browser.requestCounts()

  browser.setPhase('restore-mixed')
  await browser.setHistoryFault('always')
  await browser.reload({waitForHistory: false})
  const restoring = await recoveryPhaseEvidence(context)
  await assertRestoringGate(context, 'restore-mixed', restoring.historyEvidence, restoring.business)
  await exerciseTerminalFeedback(context, 'restore-mixed', historical.id)
  await browser.setHistoryFault('off')
  const network = await browser.waitForHistoryPhase('restore-mixed', {minimumFaultCount: 1, minimumSuccessCount: 1})
  await context.evidence.json('history-network-restore-mixed.json', network)
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.restoringRecovery === false && ai.manualRecovery === null
  })()`, 'MIXED_RECOVERY_NOT_COMPLETED')
  const recovered = await recoveryPhaseEvidence(context)
  await assertPendingRecoveryComplete(context, 'recovered-mixed', recovered.historyEvidence, recovered.business)
  checks.require('recovered-mixed.per-message-content-and-receipts-stable', messageContentSignatureDroppingEmptyText(await browser.snapshot()) === initialSignature,
    initialSignature, canonicalJson(messageContentSignature(await browser.snapshot())))
  const expectedRequests = {...requestBaseline, feedback: requestBaseline.feedback + 3}
  const mixedSettled = browser.requestCounts()
  // 恢复重载会重挂一次活流；除 events 外，只允许终态助手的 3 次反馈请求增加。
  checks.require('recovered-mixed.only-terminal-feedback-added',
    mixedSettled.chat === expectedRequests.chat
      && mixedSettled.frontendToolResult === expectedRequests.frontendToolResult
      && mixedSettled.confirm === expectedRequests.confirm
      && mixedSettled.feedback === expectedRequests.feedback
      && mixedSettled.resume === expectedRequests.resume
      && mixedSettled.events <= expectedRequests.events + 1,
  'recovery adds only the three terminal-feedback requests plus one live-stream reattach', {
    expected: expectedRequests,
    after: mixedSettled
  })

  browser.setPhase('refresh-1')
  await browser.reload()
  const refreshed = await recoveryPhaseEvidence(context)
  await assertPendingRecoveryComplete(context, 'refresh-1', refreshed.historyEvidence, refreshed.business)
  checks.require('refresh-1.mixed-content-and-receipts-stable', messageContentSignatureDroppingEmptyText(await browser.snapshot()) === initialSignature,
    initialSignature, canonicalJson(messageContentSignature(await browser.snapshot())))
  const mixedRefreshCounts = browser.requestCounts()
  checks.require('refresh-1.no-repeat-side-effects',
    mixedRefreshCounts.chat === expectedRequests.chat
      && mixedRefreshCounts.frontendToolResult === expectedRequests.frontendToolResult
      && mixedRefreshCounts.confirm === expectedRequests.confirm
      && mixedRefreshCounts.feedback === expectedRequests.feedback
      && mixedRefreshCounts.resume === expectedRequests.resume
      && mixedRefreshCounts.events <= expectedRequests.events + 2,
  'two recovery reloads add no chat/confirm/feedback/resume or tool re-submit; each may reattach the live stream once', {
    expected: expectedRequests,
    after: mixedRefreshCounts
  })
  checks.require('refresh-1.business-stable', refreshed.business.fingerprint === afterHost.fingerprint,
    afterHost.fingerprint, refreshed.business.fingerprint)
}

async function assertContextUsageMatches(context, phase, expected) {
  const snapshot = await context.browser.snapshot()
  context.checks.require(`${phase}.context-store-exact`, canonicalJson(snapshot.contextUsage) === canonicalJson(expected),
    expected, snapshot.contextUsage)
  const evidence = await context.api.context(snapshot.sessionId)
  context.checks.require(`${phase}.context-public-api-available`, evidence.status === 'AVAILABLE',
    'authoritative context endpoint is available', evidence)
  if (evidence.status !== 'AVAILABLE') throw new AssertionFailure(`${phase}.context-public-api-available`)
  const data = evidence.data
  if (expected === null) {
    context.checks.require(`${phase}.context-source-none-exact`, data.source === 'none' && data.usedTokens === 0 && data.percent === null,
      'real source=none response clears usage instead of fabricating zero percent', {
        source: data.source,
        usedTokens: data.usedTokens,
        percent: data.percent
      })
  } else {
    const actual = {
      usedTokens: data.usedTokens,
      limitTokens: data.limitTokens,
      percent: data.percent,
      level: data.compaction?.level ?? 'none',
      est: data.estimated === true,
      circuit: data.compaction?.circuit ?? 'closed'
    }
    context.checks.require(`${phase}.context-public-api-exact`, canonicalJson(actual) === canonicalJson(expected),
      expected, actual)
  }
  await context.evidence.json(`context-${phase}.json`, {
    status: evidence.status,
    httpStatus: evidence.httpStatus,
    source: data.source,
    turnNo: data.turnNo ?? null,
    measuredAtMs: data.measuredAtMs ?? null,
    usage: expected
  })
  return snapshot
}

async function triggerPublicContextLoad(browser) {
  await browser.evaluate(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    void ai.loadContext()
    return true
  })()`, false)
}

async function runContextUsageRecovery(context) {
  const {browser, checks} = context
  context.businessAfterFixture = await businessSnapshot(context.api, relevantTaskIds(context))
  browser.setPhase('initial-none-baseline')
  await browser.evaluate(`(async () => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    await ai.loadContext()
    return true
  })()`, true)
  await browser.waitForContextPhase('initial-none-baseline', {minimumSuccessCount: 1})
  const emptyInitial = await assertContextUsageMatches(context, 'initial-none-baseline', null)
  context.sessionId = emptyInitial.sessionId

  browser.setPhase('stale-none-a')
  await browser.delayNextContextResponse()
  await triggerPublicContextLoad(browser)
  const staleNoneHeld = await browser.waitForContextResponseHeld('stale-none-a')
  checks.require('stale-none-a.real-old-response-held', staleNoneHeld.responseStatus === 200 && staleNoneHeld.delayedResponse,
    'the real pre-turn source=none response is held at the CDP response boundary', staleNoneHeld)
  browser.setPhase('fresh-after-none-b')
  await browser.sendPrompt(`turn-text usage-grow=800 verification=${randomUUID()}`)
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.loading === false && ai.contextUsage?.usedTokens > 0
      && ai.messages.some((message) => message.role === 'assistant' && message.done === true)
  })()`, 'NONZERO_CONTEXT_USAGE_NOT_FORMED')
  await triggerPublicContextLoad(browser)
  const freshAfterNone = await browser.waitForContextPhase('fresh-after-none-b', {minimumSuccessCount: 1, allowPending: true})
  checks.require('fresh-after-none-b.fast-real-new-response', freshAfterNone.count === 1 && freshAfterNone.successCount === 1
    && freshAfterNone.faultCount === 0 && freshAfterNone.pendingCount === 0,
  'the real post-turn context response completes while the old none response is still held', freshAfterNone)
  let expected = (await browser.snapshot()).contextUsage
  checks.require('fresh-after-none-b.nonzero-new-usage', expected?.usedTokens > 0, 'new context usage is non-zero', expected)
  const freshAfterNoneSnapshot = await assertContextUsageMatches(context, 'fresh-after-none-b', expected)
  checks.require('fresh-after-none-b.same-session', freshAfterNoneSnapshot.sessionId === context.sessionId,
    context.sessionId, freshAfterNoneSnapshot.sessionId)
  await browser.releaseDelayedContextResponse()
  const staleNoneLate = await browser.waitForContextPhase('stale-none-a', {minimumSuccessCount: 1})
  checks.require('stale-none-a.late-response-released', staleNoneLate.count === 1 && staleNoneLate.successCount === 1
    && staleNoneLate.pendingCount === 0 && staleNoneLate.records[0]?.delayedResponseReleased === true,
  'the delayed old none response completes only after the new response', staleNoneLate)
  await assertContextUsageMatches(context, 'stale-none-late-preserved', expected)
  await context.evidence.json('context-race-none.json', {held: staleNoneHeld, fresh: freshAfterNone, late: staleNoneLate})

  browser.setPhase('stale-value-a')
  await browser.delayNextContextResponse()
  await triggerPublicContextLoad(browser)
  const staleValueHeld = await browser.waitForContextResponseHeld('stale-value-a')
  checks.require('stale-value-a.real-old-response-held', staleValueHeld.responseStatus === 200 && staleValueHeld.delayedResponse,
    'the real previous non-zero context response is held at the CDP response boundary', staleValueHeld)
  browser.setPhase('fresh-value-b')
  await browser.sendPrompt(`turn-text usage-grow=800 verification=${randomUUID()} padding=${'上'.repeat(1500)}`)
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.loading === false && ai.contextUsage?.usedTokens > ${expected.usedTokens}
      && ai.messages.filter((message) => message.role === 'assistant').every((message) => message.done === true)
  })()`, 'GROWING_CONTEXT_USAGE_NOT_FORMED')
  await triggerPublicContextLoad(browser)
  const freshValue = await browser.waitForContextPhase('fresh-value-b', {minimumSuccessCount: 1, allowPending: true})
  checks.require('fresh-value-b.fast-real-new-response', freshValue.count === 1 && freshValue.successCount === 1
    && freshValue.faultCount === 0 && freshValue.pendingCount === 0,
  'the newer non-zero context response completes while the previous value is still held', freshValue)
  const newer = (await browser.snapshot()).contextUsage
  checks.require('fresh-value-b.usage-increased', newer?.usedTokens > expected.usedTokens,
    'the second real round increases context usage', {before: expected, after: newer})
  const freshValueSnapshot = await assertContextUsageMatches(context, 'fresh-value-b', newer)
  checks.require('fresh-value-b.same-session', freshValueSnapshot.sessionId === context.sessionId,
    context.sessionId, freshValueSnapshot.sessionId)
  await browser.releaseDelayedContextResponse()
  const staleValueLate = await browser.waitForContextPhase('stale-value-a', {minimumSuccessCount: 1})
  checks.require('stale-value-a.late-response-released', staleValueLate.count === 1 && staleValueLate.successCount === 1
    && staleValueLate.pendingCount === 0 && staleValueLate.records[0]?.delayedResponseReleased === true,
  'the delayed previous value completes only after the newer value', staleValueLate)
  await assertContextUsageMatches(context, 'stale-value-late-preserved', newer)
  await context.evidence.json('context-race-value.json', {held: staleValueHeld, fresh: freshValue, late: staleValueLate})
  expected = newer

  const initial = await browser.snapshot()
  context.runId = initial.activeRunId
  const initialSignature = canonicalJson(messageContentSignature(initial))
  const initialEvidence = await recoveryPhaseEvidence(context)
  await savePhase(context, 'initial-context', initial, initialEvidence.historyEvidence, initialEvidence.business)

  browser.setPhase('refresh-context')
  await browser.reload()
  await browser.waitForContextPhase('refresh-context', {minimumSuccessCount: 1})
  const refreshed = await assertContextUsageMatches(context, 'refresh-context', expected)
  checks.require('refresh-context.content-stable', canonicalJson(messageContentSignature(refreshed)) === initialSignature,
    initialSignature, canonicalJson(messageContentSignature(refreshed)))

  browser.setPhase('context-failure')
  await browser.setContextFault('once')
  await browser.evaluate(`(async () => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    await ai.loadContext()
    return true
  })()`, true)
  const failure = await browser.waitForContextPhase('context-failure', {exactFaultCount: 1})
  checks.require('context-failure.real-503-terminal', failure.faultCount === 1 && failure.successCount === 0
    && failure.pendingCount === 0 && failure.statuses.every((status) => status === 503),
  'the public loadContext action actually received the injected HTTP 503', failure)
  await context.evidence.json('context-network-context-failure.json', failure)
  await browser.setContextFault('off')
  await assertContextUsageMatches(context, 'context-failure-preserved', expected)

  browser.setPhase('history-independent')
  await browser.setHistoryFault('always')
  await browser.reload({waitForHistory: false})
  // 刷新后先证明 history 真的 503，再等恢复现场；否则先做外部采集会错过 29ms 的恢复窗口。
  let historyFaulted
  try {
    historyFaulted = await browser.waitForHistoryPhase('history-independent', {minimumFaultCount: 1})
  } catch (error) {
    if (!(error instanceof AssertionFailure)) throw error
    const observed = await browser.waitForContextPhase('history-independent', {allowPending: true, minimumSuccessCount: 1})
      .catch(() => null)
    await context.evidence.json('history-independent-fault-missed.json', observed
      ?? {note: 'history phase did not observe a 503 for this reload', networkFaults: browser.networkFaults?.() ?? null})
    throw new ScenarioNotRun('HISTORY_INDEPENDENT_FAULT_WINDOW_MISSED')
  }
  checks.require('history-independent.history-503-observed', historyFaulted.faultCount >= 1,
    'the browser history request really failed with 503 before any external evidence step', historyFaulted)
  await context.evidence.json('history-network-history-independent-503.json', historyFaulted)
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.restoringRecovery === true && ai.contextUsage?.usedTokens === ${expected.usedTokens}
  })()`, 'CONTEXT_NOT_RESTORED_DURING_HISTORY_FAILURE')
  const independentContext = await browser.waitForContextPhase('history-independent', {minimumSuccessCount: 1})
  checks.require('history-independent.context-real-200', independentContext.successCount >= 1
    && independentContext.faultCount === 0 && independentContext.pendingCount === 0,
  'context recovery completes independently while browser history remains faulted', independentContext)
  await context.evidence.json('context-network-history-independent.json', independentContext)
  const independent = await recoveryPhaseEvidence(context)
  const independentView = await recoveryView(browser)
  checks.require('history-independent.usage-preserved', canonicalJson(independentView.snapshot.contextUsage) === canonicalJson(expected),
    expected, independentView.snapshot.contextUsage)
  assertMessagePresentation(context, 'history-independent', independentView, 'terminal')
  assertWriteOperationsDisabled(context, 'history-independent', independentView.operations, independentView.snapshot.messages)
  await assertDraftAndTerminalCopy(context, 'history-independent', independentView)
  await saveRecoveryPhase(context, 'history-independent', independentView, independent.historyEvidence, independent.business)
  await browser.setHistoryFault('off')
  const historyNetwork = await browser.waitForHistoryPhase('history-independent', {minimumFaultCount: 1, minimumSuccessCount: 1})
  await context.evidence.json('history-network-history-independent.json', historyNetwork)
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.restoringRecovery === false && ai.manualRecovery === null
  })()`, 'HISTORY_INDEPENDENT_RECOVERY_NOT_COMPLETED')
  const recovered = await recoveryPhaseEvidence(context)
  await assertTerminalRecoveryComplete(context, 'history-independent-recovered', recovered.historyEvidence, recovered.business)
  await assertContextUsageMatches(context, 'history-independent-recovered', expected)

  const oldSessionId = context.sessionId
  context.additionalSessionIds.add(oldSessionId)
  browser.setPhase('new-session-none')
  await browser.click('[data-testid="new-chat"]')
  await browser.waitFor(`document.querySelector('[data-testid="new-chat-dialog"]')?.open === true`, 'NEW_CHAT_CONFIRMATION_NOT_OPEN')
  await browser.click('[data-testid="new-chat-dialog"] button[value="confirm"]')
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.sessionId !== '' && ai.sessionId !== '${oldSessionId}' && ai.messages.length === 0 && ai.contextUsage === null
  })()`, 'NEW_SESSION_CONTEXT_ISOLATION_NOT_READY')
  await browser.evaluate(`(async () => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    await ai.loadContext()
    return true
  })()`, true)
  await browser.waitForContextPhase('new-session-none', {minimumSuccessCount: 1})
  const empty = await assertContextUsageMatches(context, 'new-session-none', null)
  context.sessionId = empty.sessionId
  const pill = await browser.evaluate(`(() => {
    const element = document.querySelector('[data-testid="context-pill"]')
    return {
      present: element !== null,
      hasValue: element?.hasAttribute('value') === true,
      label: element?.getAttribute('aria-label') ?? ''
    }
  })()`, false)
  checks.require('new-session-none.neutral-pill-not-zero-percent', pill.present && !pill.hasValue
    && pill.label.trim() !== '' && !/(?:^|\D)0\s*%/.test(pill.label),
  'source=none keeps a neutral pill with no value and no fabricated 0%', pill)
  let oldHistory = null
  const deleteDeadline = Date.now() + context.config.timeoutMs
  while (Date.now() < deleteDeadline) {
    oldHistory = await context.api.history(oldSessionId)
    if (oldHistory.httpStatus === 410 || (oldHistory.status === 'AVAILABLE' && oldHistory.data.messages.length === 0)) break
    await sleep(context.config.pollMs)
  }
  checks.require('new-session-none.old-history-isolated', oldHistory?.httpStatus === 410
    || (oldHistory?.status === 'AVAILABLE' && oldHistory.data.messages.length === 0),
  'new chat destroys or empties the old session without carrying messages or usage forward', {
    httpStatus: oldHistory?.httpStatus ?? null,
    status: oldHistory?.status ?? null,
    messageCount: oldHistory?.data?.messages?.length ?? null
  })
  const emptyHistory = await context.api.history(context.sessionId)
  await savePhase(context, 'new-session-none', empty, emptyHistory, await businessSnapshot(context.api, relevantTaskIds(context)))
}

async function runStreamHistoryRace(context) {
  const {browser, checks, config} = context
  const variants = new Set(['mirror-inflight', 'no-mirror', 'mirror-terminal'])
  if (!variants.has(config.variant)) throw new ScenarioNotRun('RECOVERY_VARIANT_REQUIRED')
  context.businessAfterFixture = await businessSnapshot(context.api, relevantTaskIds(context))
  browser.setPhase('initial-stream')
  // C-160：不再假设“纯文本流式约 2s 后写入 history”。改用 timeline-nonprefix 的真实写点：
  // 工具跳（assistant 已有工具段且已有正文）时 run 仍在途，history 应已有前缀正文；
  // 纯文本在途没有前缀属夹具未形成，记 NOT_RUN 而不是 FAIL。
  await browser.sendPrompt(`timeline-nonprefix verification=${randomUUID()}`)
  const submitPendingFormIfAny = async () => {
    const form = await browser.evaluate(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      if (!ai.activeForm?.toolCallId) return null
      const field = (ai.activeForm.form?.fields ?? []).find((candidate) => candidate.type === 'text')
      return field ? {toolCallId: ai.activeForm.toolCallId, key: field.key} : null
    })()`, false)
    if (!form) return false
    await browser.evaluate(`document.querySelector('[data-testid="gf-field-${form.toolCallId}-${form.key}"]').focus()`, false)
    await browser.send('Input.insertText', {text: `suffix-${randomUUID()}`})
    await browser.click(`[data-testid="gf-form-${form.toolCallId}"] [data-testid="gf-submit"]`)
    return true
  }
  if (config.variant === 'mirror-terminal') {
    await browser.waitFor(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      if (ai.activeForm?.toolCallId) return true
      return ai.loading === false && ai.messages.some((message) => message.role === 'assistant' && message.done)
    })()`, 'TERMINAL_MIRROR_ROUND_NOT_COMPLETED')
    await submitPendingFormIfAny()
    await browser.waitFor(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      return ai.loading === false && ai.messages.filter((message) => message.role === 'assistant').every((message) => message.done)
    })()`, 'TERMINAL_MIRROR_ROUND_NOT_COMPLETED')
  } else {
    await browser.waitFor(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      const jumped = ai.messages.some((message) => message.role === 'assistant'
        && message.parts.some((part) => part.kind === 'tool'))
      const nonterminal = ai.loading === true || ai.activeForm !== null
        || ai.messages.some((message) => message.role === 'assistant' && message.done !== true)
      return jumped && nonterminal
    })()`, 'STREAM_HISTORY_TOOL_JUMP_NOT_FORMED')
  }
  const initial = await browser.snapshot()
  context.sessionId = initial.sessionId
  context.runId = initial.activeRunId
  checks.require('initial-stream.run-in-flight', config.variant === 'mirror-terminal'
    ? initial.loading === false
    : (initial.loading === true || initial.activeForm !== null
      || initial.messages.some((message) => message.role === 'assistant' && message.done !== true)),
  'the run is still in flight (or suspended on its pending form) at the real tool-jump write point', {
    variant: config.variant,
    loading: initial.loading,
    activeForm: initial.activeForm?.toolCallId ?? null,
    phase: initial.runPhase
  })
  const initialHistory = requiredHistory(await context.api.history(initial.sessionId))
  const prefix = initialHistory.messages.filter((message) => String(message.type ?? message.role ?? '').toUpperCase() === 'ASSISTANT')
    .map((message) => message.text ?? message.content ?? '').join('')
  if (prefix.length === 0 && config.variant !== 'mirror-terminal') {
    throw new ScenarioNotRun('STREAM_HISTORY_PREFIX_NOT_FORMED')
  }
  checks.require('initial-stream.history-prefix-nonempty', prefix.length > 0,
    'history carries the assistant text prefix written at the real tool-jump/suspend write point', textEvidence(prefix))
  // mirror-terminal：镜像写入有 300ms 节流 + 300ms idle 的窗口，不能在首个快照直接断言；
  // 按有界轮询等待“唯一当前镜像 inFlight=false”（约 600ms 窗口），超时才按产品 FAIL。
  const readMirrorWithBoundedWait = async (timeoutMs = 3_000) => {
    const startedAt = Date.now()
    let last = null
    let lastError = null
    while (Date.now() - startedAt < timeoutMs) {
      try {
        last = await mirrorState(browser, initial.sessionId)
      } catch (error) {
        lastError = error
        last = null
      }
      if (last && last.entries.length === 1 && last.entries[0].inFlight === false) {
        return {mirror: last, waitedMs: Date.now() - startedAt, timedOut: false}
      }
      await sleep(100)
    }
    return {mirror: last, waitedMs: Date.now() - startedAt, timedOut: true, errorName: lastError ? errorName(lastError) : null}
  }
  let mirror = null
  if (config.variant === 'mirror-terminal') {
    const waited = await readMirrorWithBoundedWait()
    mirror = waited.mirror
    checks.require('mirror.inflight-false', waited.timedOut === false,
      'the real completed mirror becomes inFlight=false within the bounded 300ms throttle + 300ms idle window', waited)
  } else {
    mirror = await mirrorState(browser, initial.sessionId)
    if (config.variant === 'mirror-inflight') {
      checks.require('mirror.inflight-true', mirror.entries.length === 1 && mirror.entries[0].inFlight === true,
        'the real streaming mirror is inFlight=true', mirror)
    } else {
      await removeCurrentMirror(context)
    }
  }
  const initialSignature = recoveryFactsSignature(initial)

  browser.setPhase('restore-stream')
  await browser.delayNextRunCurrentResponse()
  await browser.reload({waitForHistory: false})
  const held = await browser.waitForRunCurrentResponseHeld('restore-stream')
  const historyNetwork = await browser.waitForHistoryPhase('restore-stream', {minimumSuccessCount: 1})
  const restoringEvidence = await recoveryPhaseEvidence(context)
  if (config.variant === 'mirror-terminal') {
    const view = await recoveryView(browser)
    checks.require('restore-stream.terminal-mirror-recovering', view.snapshot.restoringRecovery,
      'terminal mirror waits for the delayed run snapshot', held)
    assertMessagePresentation(context, 'restore-stream-terminal', view, 'terminal')
    assertWriteOperationsDisabled(context, 'restore-stream-terminal', view.operations, view.snapshot.messages)
    await saveRecoveryPhase(context, 'restore-stream-terminal', view, restoringEvidence.historyEvidence, restoringEvidence.business)
  } else {
    await assertRestoringGate(context, 'restore-stream', restoringEvidence.historyEvidence, restoringEvidence.business)
  }
  await browser.releaseDelayedRunCurrentResponse()
  const runCurrentNetwork = await browser.waitForRunCurrentPhase('restore-stream', {minimumSuccessCount: 1})
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    if (ai.restoringRecovery !== false) return false
    if (ai.activeForm?.toolCallId) return true
    return ai.loading === false
      && ai.messages.filter((message) => message.role === 'assistant').every((message) => message.done)
  })()`, 'STREAM_HISTORY_RACE_NOT_SETTLED')
  // timeline-nonprefix 的第二段带 generative_form：恢复后须真实提交该表单，才有 B/C 后缀。
  const suffixForm = await browser.evaluate(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    if (!ai.activeForm?.toolCallId) return null
    const field = (ai.activeForm.form?.fields ?? []).find((candidate) => candidate.type === 'text')
    return field ? {toolCallId: ai.activeForm.toolCallId, key: field.key} : null
  })()`, false)
  if (suffixForm) {
    await browser.evaluate(`document.querySelector('[data-testid="gf-field-${suffixForm.toolCallId}-${suffixForm.key}"]').focus()`, false)
    await browser.send('Input.insertText', {text: `suffix-${randomUUID()}`})
    await browser.click(`[data-testid="gf-form-${suffixForm.toolCallId}"] [data-testid="gf-submit"]`)
  }
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.restoringRecovery === false && ai.loading === false
      && ai.messages.filter((message) => message.role === 'assistant').every((message) => message.done)
  })()`, 'STREAM_HISTORY_RACE_NOT_TERMINAL')
  const recoveredEvidence = await recoveryPhaseEvidence(context)
  await assertTerminalRecoveryComplete(context, 'recovered-stream', recoveredEvidence.historyEvidence, recoveredEvidence.business)
  const recovered = await browser.snapshot()
  context.latestSnapshot = recovered
  const finalText = recovered.messages.filter((message) => message.role === 'assistant').map((message) => message.content).join('')
  checks.require('recovered-stream.history-prefix-complete', finalText.startsWith(prefix)
    && (config.variant === 'mirror-terminal' ? finalText.length === prefix.length : finalText.length > prefix.length),
  'the resumed response retains the history prefix and only appends the real suffix', {
    prefix: textEvidence(prefix),
    final: textEvidence(finalText)
  })
  checks.require('recovered-stream.has-suffix', config.variant === 'mirror-terminal'
    ? finalText.length === prefix.length
    : (finalText.startsWith(prefix) && finalText.length > prefix.length),
  'streaming variants must resume from an in-flight prefix and append a real suffix', {
    prefixLength: prefix.length,
    finalLength: finalText.length,
    variant: config.variant
  })
  if (config.variant !== 'mirror-terminal') assertRecoveryDotsThenWorking(context, 'recovered-stream', recovered.messages.find((message) => message.role === 'assistant')?.id)
  checks.require('recovered-stream.content-stable', config.variant !== 'mirror-terminal'
    || recoveryFactsSignatureWithoutSeq(recovered) === recoveryFactsSignatureWithoutSeq(initial),
  'terminal mirror content (parts/cards/text, seq excluded as transient metadata) is unchanged', {
    before: recoveryFactsSignatureWithoutSeq(initial),
    after: recoveryFactsSignatureWithoutSeq(recovered)
  })
  await context.evidence.json('stream-history-race.json', {variant: config.variant, mirror, held, historyNetwork, runCurrentNetwork})
  // 连续两刷按“内容/顺序/状态/卡片”幂等核对；parts.seq 是恢复重投影元数据，仅留证不比断言。
  // 副作用基线取“本轮流程（含必需的表单提交）完成后”，仍要求两刷不新增 chat/confirm/feedback/events/resume。
  const refreshBaseline = browser.requestCounts()
  const finalSignature = recoveryFactsSignatureWithoutSeq(recovered)
  const finalSeq = canonicalJson(recovered.messages.map((message) => message.parts.map((part) => part.seq ?? null)))
  const seqDrift = []
  for (const phase of ['refresh-1', 'refresh-2']) {
    browser.setPhase(phase)
    await browser.reload()
    await browser.waitFor(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      return ai.historyLoaded === true && ai.restoringRecovery === false && ai.loading === false
    })()`, `${phase.toUpperCase()}_NOT_SETTLED`)
    const evidence = await recoveryPhaseEvidence(context)
    await assertTerminalRecoveryComplete(context, phase, evidence.historyEvidence, evidence.business)
    const refreshed = await browser.snapshot()
    checks.require(`${phase}.idempotent`, recoveryFactsSignatureWithoutSeq(refreshed) === finalSignature,
      finalSignature, recoveryFactsSignatureWithoutSeq(refreshed))
    const refreshedSeq = canonicalJson(refreshed.messages.map((message) => message.parts.map((part) => part.seq ?? null)))
    if (refreshedSeq !== finalSeq) seqDrift.push(phase)
  }
  await context.evidence.json('stream-history-race-seq.json', {finalSeq, seqDrift})
  checks.require('refresh.no-repeat-side-effects', canonicalJson(browser.requestCounts()) === canonicalJson(refreshBaseline),
    refreshBaseline, browser.requestCounts())
}

async function runPendingIntegrity(context) {
  const {browser, checks, config} = context
  if (!['hitl', 'gf'].includes(config.variant)) throw new Error('RECOVERY_VARIANT must be hitl or gf')
  if (!['history-first', 'current-first'].includes(config.historyOrder)) {
    throw new Error('HISTORY_ORDER must be history-first or current-first')
  }
  context.businessAfterFixture = await businessSnapshot(context.api, relevantTaskIds(context))
  browser.setPhase('initial-pending')
  const prompt = config.variant === 'hitl'
    ? `tool=create_task&args={"type":"EXPORT","conditions":{},"openWizard":false} verification=${randomUUID()}`
    : `timeline-nonprefix verification=${randomUUID()}`
  await browser.sendPrompt(prompt)
  const pending = await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    const part = ai.messages.flatMap((message) => message.parts).find((candidate) => candidate.kind === 'tool'
      && candidate.name === '${config.variant === 'hitl' ? 'create_task' : 'generative_form'}' && candidate.requiresAction === true)
    return part ? {toolCallId: part.toolCallId} : null
  })()`, 'PENDING_INTEGRITY_NOT_FORMED')
  const toolCallIds = [pending.toolCallId]
  const initial = await browser.snapshot()
  context.sessionId = initial.sessionId
  context.runId = initial.activeRunId
  context.latestSnapshot = initial
  const relaxedSeq = config.variant === 'gf' && config.pendingFixture === 'standard'
  const factsOf = (snapshot) => relaxedSeq ? recoveryFactsSignatureWithoutSeq(snapshot) : recoveryFactsSignature(snapshot)
  const initialSignature = factsOf(initial)
  const initialHistory = requiredHistory(await context.api.history(initial.sessionId))
  if (config.pendingFixture === 'empty-history') await removeCurrentMirror(context)
  const requestBaseline = browser.requestCounts()

  browser.setPhase('restore-pending')
  if (config.historyOrder === 'history-first') await browser.delayNextRunCurrentResponse()
  else await browser.delayNextHistoryResponse()
  await browser.reload({waitForHistory: false})
  let held
  let firstNetwork
  if (config.historyOrder === 'history-first') {
    held = await browser.waitForRunCurrentResponseHeld('restore-pending')
    firstNetwork = await browser.waitForHistoryPhase('restore-pending', {minimumSuccessCount: 1})
  } else {
    held = await browser.waitForHistoryResponseHeld('restore-pending')
    firstNetwork = await browser.waitForRunCurrentPhase('restore-pending', {minimumSuccessCount: 1})
  }
  const historyEvidence = await context.api.history(context.sessionId)
  const history = requiredHistory(historyEvidence)
  const coverage = pendingHistoryCoverage(history, toolCallIds)
  requirePendingFixture(config, coverage)
  await context.evidence.json('pending-history-restore.json', coverage)
  const restoringEvidence = await recoveryPhaseEvidence(context)
  await assertRestoringGate(context, 'restore-pending', restoringEvidence.historyEvidence, restoringEvidence.business)
  context.latestSnapshot = await browser.snapshot()
  await assertPendingTrajectory(context, 'restore-pending', toolCallIds)

  if (config.historyOrder === 'history-first') await browser.releaseDelayedRunCurrentResponse()
  else await browser.releaseDelayedHistoryResponse()
  const secondNetwork = config.historyOrder === 'history-first'
    ? await browser.waitForRunCurrentPhase('restore-pending', {minimumSuccessCount: 1})
    : await browser.waitForHistoryPhase('restore-pending', {minimumSuccessCount: 1})
  await browser.waitFor(`document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai').restoringRecovery === false`,
    'PENDING_RECOVERY_NOT_COMPLETED')
  const recoveredEvidence = await recoveryPhaseEvidence(context)
  await assertPendingRecoveryComplete(context, 'recovered-pending', recoveredEvidence.historyEvidence, recoveredEvidence.business)
  context.latestSnapshot = await browser.snapshot()
  await assertPendingTrajectory(context, 'recovered-pending', toolCallIds)
  checks.require('recovered-pending.parts-and-cards-stable', factsOf(context.latestSnapshot) === initialSignature,
    initialSignature, factsOf(context.latestSnapshot))
  if (relaxedSeq) {
    const recoveredHistory = requiredHistory(recoveredEvidence.historyEvidence)
    const segmentFacts = (history) => history.messages.filter((message) => Array.isArray(message.segments))
      .map((message) => ({
        entryId: message.entryId ?? null,
        seqs: message.segments.map((segment) => segment?.seq ?? null)
      }))
    const before = segmentFacts(initialHistory)
    const after = segmentFacts(recoveredHistory)
    const shape = before.length === 0 ? 'absent' : 'present'
    checks.require('recovered-pending.segments-shape', shape === 'absent'
      ? true
      : canonicalJson(before) === canonicalJson(after),
    shape === 'absent'
      ? 'public history exposes no segments for this fixture; shape recorded, seq idempotence not applicable'
      : 'entries that actually carry segments keep the same entryId and segment seq values',
    {shape, before, after})
    await context.evidence.json('pending-segments-shape.json', {shape, before, after, seqRelaxed: true})
  }
  await context.evidence.json('pending-order-restore.json', {order: config.historyOrder, held, firstNetwork, secondNetwork, coverage})

  for (const phase of ['refresh-1', 'refresh-2']) {
    browser.setPhase(phase)
    await browser.reload()
    const evidence = await recoveryPhaseEvidence(context)
    await assertPendingRecoveryComplete(context, phase, evidence.historyEvidence, evidence.business)
    context.latestSnapshot = await browser.snapshot()
    await assertPendingTrajectory(context, phase, toolCallIds)
    checks.require(`${phase}.pending-idempotent`, factsOf(context.latestSnapshot) === initialSignature,
      initialSignature, factsOf(context.latestSnapshot))
  }

  browser.setPhase('legal-decision')
  if (config.variant === 'hitl') {
    await browser.click(`[data-testid="pending-card-${pending.toolCallId}"] [data-testid="gate-actions"] button:first-child`)
  } else {
    await browser.click(`[data-testid="gf-form-${pending.toolCallId}"] [data-testid="gf-cancel"]`)
  }
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    const part = ai.messages.flatMap((message) => message.parts).find((candidate) => candidate.toolCallId === '${pending.toolCallId}')
    return part && !part.requiresAction && ai.loading === false
  })()`, 'LEGAL_PENDING_DECISION_NOT_ACCEPTED')
  context.latestSnapshot = await browser.snapshot()
  const decision = await assertPendingTrajectory(context, 'legal-decision', toolCallIds, {requireDecision: true})
  await context.evidence.json('pending-legal-decision.json', decision)
  browser.setPhase('decided-refresh')
  await browser.reload()
  context.latestSnapshot = await browser.snapshot()
  await assertPendingTrajectory(context, 'decided-refresh', toolCallIds, {requireDecision: true})
  checks.require('decided-refresh.no-repeat-chat', browser.requestCounts().chat === requestBaseline.chat,
    requestBaseline.chat, browser.requestCounts().chat)
}

async function runCrossRunOrder(context) {
  const {browser, checks} = context
  context.businessAfterFixture = await businessSnapshot(context.api, relevantTaskIds(context))
  browser.setPhase('run-1')
  await browser.sendPrompt(`turn-text verification=${randomUUID()}`)
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.loading === false && ai.messages.some((message) => message.role === 'assistant' && message.done)
  })()`, 'CROSS_RUN_FIRST_NOT_TERMINAL')
  const first = await browser.snapshot()
  const firstRunId = first.activeRunId
  context.sessionId = first.sessionId
  context.runId = firstRunId

  browser.setPhase('late-old-run')
  await browser.delayNextRunCurrentResponse()
  await triggerPublicRunCurrentRead(browser, first.sessionId)
  const held = await browser.waitForRunCurrentResponseHeld('late-old-run')
  browser.setPhase('run-2')
  await browser.sendPrompt(`turn-text verification=${randomUUID()}`)
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.loading === false && ai.activeRunId && ai.activeRunId !== '${firstRunId}'
      && ai.messages.filter((message) => message.role === 'assistant').every((message) => message.done)
  })()`, 'CROSS_RUN_SECOND_NOT_TERMINAL')
  const second = await browser.snapshot()
  const secondRunId = second.activeRunId
  context.runId = secondRunId
  await browser.releaseDelayedRunCurrentResponse()
  const late = await browser.waitForRunCurrentPhase('late-old-run', {minimumSuccessCount: 1})
  const afterLate = await browser.snapshot()
  checks.require('late-old-run.no-cross-run-overwrite', afterLate.activeRunId === secondRunId
    && afterLate.messages.every((message) => !message.runId || [firstRunId, secondRunId].includes(message.runId)),
  'late previous-run snapshot cannot overwrite the newer run identity or messages', {
    firstRunId,
    secondRunId,
    actualRunId: afterLate.activeRunId,
    messages: afterLate.messages.map((message) => ({id: message.id, runId: message.runId, done: message.done}))
  })
  const finalSignature = recoveryFactsSignature(afterLate)
  const requestBaseline = browser.requestCounts()
  await context.evidence.json('cross-run-late.json', {held, late, firstRunId, secondRunId})

  for (const phase of ['history-first', 'current-first']) {
    browser.setPhase(phase)
    if (phase === 'history-first') await browser.delayNextRunCurrentResponse()
    else await browser.delayNextHistoryResponse()
    await browser.reload({waitForHistory: false})
    if (phase === 'history-first') {
      await browser.waitForRunCurrentResponseHeld(phase)
      await browser.waitForHistoryPhase(phase, {minimumSuccessCount: 1})
      await browser.releaseDelayedRunCurrentResponse()
      await browser.waitForRunCurrentPhase(phase, {minimumSuccessCount: 1})
    } else {
      await browser.waitForHistoryResponseHeld(phase)
      await browser.waitForRunCurrentPhase(phase, {minimumSuccessCount: 1})
      await browser.releaseDelayedHistoryResponse()
      await browser.waitForHistoryPhase(phase, {minimumSuccessCount: 1})
    }
    const evidence = await recoveryPhaseEvidence(context)
    await assertTerminalRecoveryComplete(context, phase, evidence.historyEvidence, evidence.business)
    checks.require(`${phase}.two-refresh-idempotent`, recoveryFactsSignature(await browser.snapshot()) === finalSignature,
      finalSignature, recoveryFactsSignature(await browser.snapshot()))
  }
  checks.require('cross-run.no-repeat-chat', browser.requestCounts().chat === requestBaseline.chat,
    requestBaseline.chat, browser.requestCounts().chat)
}

async function runFormQueueRecovery(context) {
  const {browser, checks, config} = context
  if (!['with-mirror', 'no-mirror'].includes(config.variant)) throw new ScenarioNotRun('RECOVERY_VARIANT_REQUIRED')
  context.businessAfterFixture = await businessSnapshot(context.api, relevantTaskIds(context))
  browser.setPhase('initial-terminal')
  await browser.sendPrompt(`turn-text verification=${randomUUID()}`)
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.loading === false && ai.messages.some((message) => message.role === 'assistant' && message.done)
  })()`, 'FORM_QUEUE_HISTORICAL_ROUND_NOT_COMPLETED')
  browser.setPhase('form-queue')
  await browser.sendPrompt(`gf-seven-types multi-card=2 verification=${randomUUID()}`)
  const queued = await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.activeForm && ai.queuedForms.length === 1 ? {active: ai.activeForm.toolCallId, queued: ai.queuedForms[0].toolCallId} : null
  })()`, 'FORM_SINGLE_ACTIVE_AND_ONE_QUEUED_NOT_FORMED')
  const initial = await browser.snapshot()
  context.sessionId = initial.sessionId
  context.runId = initial.activeRunId
  const textField = initial.activeForm?.fields.find((field) => field.type === 'text')
  if (!textField) throw new ScenarioNotRun('FORM_QUEUE_TEXT_FIELD_NOT_FORMED')
  const draft = `queue-draft-${randomUUID()}`
  await browser.evaluate(`document.querySelector('[data-testid="gf-field-${queued.active}-${textField.key}"]').focus()`, false)
  await browser.send('Input.insertText', {text: draft})
  await browser.waitFor(`document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai').activeForm?.values?.['${textField.key}'] === '${draft}'`,
    'FORM_QUEUE_DRAFT_NOT_WRITTEN')
  const withDraft = await browser.snapshot()
  const initialSignature = recoveryFactsSignature(withDraft)
  const toolCallIds = [queued.active, queued.queued]
  context.latestSnapshot = withDraft
  await assertPendingTrajectory(context, 'form-queue', toolCallIds)
  if (config.variant === 'no-mirror') await removeCurrentMirror(context)
  const requestBaseline = browser.requestCounts()

  for (const phase of ['refresh-1', 'refresh-2']) {
    browser.setPhase(phase)
    await browser.reload()
    const evidence = await recoveryPhaseEvidence(context)
    await assertPendingRecoveryComplete(context, phase, evidence.historyEvidence, evidence.business)
    const current = await browser.snapshot()
    context.latestSnapshot = current
    checks.require(`${phase}.single-active-draft-queue-stable`, current.activeForm?.toolCallId === queued.active
      && current.queuedForms.length === 1 && current.queuedForms[0].toolCallId === queued.queued
      && recoveryFactsSignature(current) === initialSignature,
    'one active form, one queued form and the same draft survive without duplicate parts/cards', {
      expected: initialSignature,
      actual: recoveryFactsSignature(current),
      activeForm: current.activeForm ? {toolCallId: current.activeForm.toolCallId, status: current.activeForm.status} : null,
      queuedForms: current.queuedForms
    })
    await assertPendingTrajectory(context, phase, toolCallIds)
  }
  const settledCounts = browser.requestCounts()
  // 挂起轮在每次刷新时必须重挂活流（GET /events/）—— 每次刷新至多一次，不算重复副作用；
  // chat/confirm/feedback/resume 与前端工具回灌必须完全不变。
  checks.require('form-queue.no-repeat-side-effects',
    settledCounts.chat === requestBaseline.chat
      && settledCounts.frontendToolResult === requestBaseline.frontendToolResult
      && settledCounts.confirm === requestBaseline.confirm
      && settledCounts.feedback === requestBaseline.feedback
      && settledCounts.resume === requestBaseline.resume
      && settledCounts.events <= requestBaseline.events + 2,
  'two refreshes add no chat/confirm/feedback/resume or tool re-submit; each refresh may reattach the live stream once', {
    baseline: requestBaseline,
    after: settledCounts
  })
}

function historyProjectionExpectation(history) {
  return {
    total: history.total ?? history.messages.length,
    offset: Math.max(0, (history.total ?? history.messages.length) - history.messages.length),
    hasMore: history.hasMore === true,
    nextCursor: history.nextCursor ?? null,
    revision: history.revision ?? 0,
    eventCount: (history.events ?? []).length,
    windowEntryCount: history.messages.length,
    events: (history.events ?? []).map((event) => ({
      eventId: event.eventId ?? null,
      runId: event.runId ?? null,
      anchorToolCallId: event.anchorToolCallId ?? null,
      atMs: event.atMs ?? null
    })),
    windowEntries: history.messages.map((message) => ({
      entryId: message.entryId ?? null,
      runId: message.runId ?? null,
      type: String(message.type ?? message.role ?? '').toUpperCase(),
      textHash: {algorithm: 'fnv1a32', value: fnv1a32(message.text ?? message.content ?? '')}
    }))
  }
}

function assertHistoryProjectionCommit(context, phase, snapshot, history) {
  const actual = snapshot.historyProjection
  const expected = historyProjectionExpectation(history)
  context.checks.require(`${phase}.history-only-projection-commit`, canonicalJson(actual) === canonicalJson(expected),
    expected, actual)
}

/**
 * 投影提交是异步收敛的：UI 快照与权威 history 各自会被新轮次推进，单次采样会把
 * “快照之后服务端又多了一条”误判成不提交。这里轮询到两侧一致（真实回退永不收敛 ⇒ 仍失败）。
 */
async function waitForHistoryProjectionCommit(context, phase) {
  const deadline = Date.now() + context.config.timeoutMs
  let last = null
  while (Date.now() < deadline) {
    const snapshot = await context.browser.snapshot()
    const authoritative = requiredHistory(await context.api.history(context.sessionId))
    const expected = historyProjectionExpectation(authoritative)
    if (canonicalJson(snapshot.historyProjection) === canonicalJson(expected)) return {snapshot, authoritative}
    last = {actual: snapshot.historyProjection, expected}
    await sleep(context.config.pollMs)
  }
  context.checks.require(`${phase}.history-only-projection-commit`, false, last?.expected ?? null, last?.actual ?? null)
  return null
}

function assertNoReconnect(context, phase, baseline) {
  const current = context.browser.requestCounts()
  context.checks.require(`${phase}.no-events-or-resume`, current.events === baseline.events && current.resume === baseline.resume,
    {events: baseline.events, resume: baseline.resume}, {events: current.events, resume: current.resume})
}

async function runHistoryGenerationRace(context) {
  const {browser, checks} = context
  context.businessAfterFixture = await businessSnapshot(context.api, relevantTaskIds(context))
  browser.setPhase('initial')
  await browser.sendPrompt(`turn-text verification=${randomUUID()}`)
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.loading === false && ai.messages.some((message) => message.role === 'assistant' && message.done)
  })()`, 'HISTORY_GENERATION_INITIAL_NOT_TERMINAL')
  const initial = await browser.snapshot()
  const firstRunId = initial.activeRunId
  context.sessionId = initial.sessionId
  context.runId = firstRunId

  browser.setPhase('generation-old')
  await browser.delayNextHistoryResponse()
  await browser.reload({waitForHistory: false})
  const held = await browser.waitForHistoryResponseHeld('generation-old')
  browser.setPhase('new-occupancy')
  const started = await context.api.startChat(context.sessionId, `turn-text stall-frames=2000 verification=${randomUUID()}`)
  checks.require('new-occupancy.public-chat-started', started.runId && started.runId !== firstRunId,
    'a new run occupies the session while the first history response is still held', {firstRunId, secondRunId: started.runId})
  context.runId = started.runId
  browser.setPhase('generation-new')
  await browser.evaluate(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    void ai.loadHistory()
    return true
  })()`, false)
  const newer = await browser.waitForHistoryPhase('generation-new', {minimumSuccessCount: 1})
  await browser.releaseDelayedHistoryResponse()
  const older = await browser.waitForHistoryPhase('generation-old', {minimumSuccessCount: 1})
  const committed = await waitForHistoryProjectionCommit(context, 'history-generation-after-late-old')
  const after = committed?.snapshot ?? await browser.snapshot()
  const authoritativeHistory = committed?.authoritative ?? requiredHistory(await context.api.history(context.sessionId))
  await context.evidence.json('history-generation-projection.json', {
    actual: after.historyProjection,
    authoritative: historySummary(authoritativeHistory)
  })
  checks.require('history-generation.newer-arrived-first', after.messages.some((message) => message.runId === started.runId)
    && after.messages.some((message) => message.runId === firstRunId),
  'the second loadHistory completes first and the late old history cannot remove either run', {
    firstRunId,
    secondRunId: started.runId,
    messages: after.messages.map((message) => ({id: message.id, runId: message.runId, done: message.done}))
  })
  await context.evidence.json('history-generation-race.json', {held, newer, older, firstRunId, secondRunId: started.runId})
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.loading === false && ai.messages.filter((message) => message.role === 'assistant').every((message) => message.done)
  })()`, 'HISTORY_GENERATION_NEW_RUN_NOT_TERMINAL')
  const evidence = await recoveryPhaseEvidence(context)
  await assertTerminalRecoveryComplete(context, 'history-generation-terminal', evidence.historyEvidence, evidence.business)
}

async function runStoppedRecovery(context) {
  const {browser, checks, config} = context
  const variants = new Set(['stop', 'client-request-stopped', 'current-failure', 'expired', 'incomplete'])
  if (!variants.has(config.variant)) throw new ScenarioNotRun('RECOVERY_VARIANT_REQUIRED')
  context.businessAfterFixture = await businessSnapshot(context.api, relevantTaskIds(context))
  context.sessionId = (await browser.snapshot()).sessionId
  browser.setPhase('before-stop')
  await browser.sendPrompt(`turn-text stall-frames=2000 verification=${randomUUID()}`)
  if (config.variant === 'client-request-stopped') {
    const request = await browser.waitForChatClientIdentity()
    const cancellation = await browser.evaluate(`(async () => {
      const {aiApi} = await import('/src/api/ai.ts')
      const result = await aiApi.cancelClientRequest({sessionId: '${context.sessionId}', clientRequestId: '${request.clientRequestId}'})
      return {cancelled: result.cancelled === true, alreadyCancelled: result.alreadyCancelled === true}
    })()`, true)
    checks.require('client-request-stopped.cancel-accepted', cancellation.cancelled || cancellation.alreadyCancelled,
      'the public cancel/client-request endpoint accepts the real clientRequestId', cancellation)
    await browser.waitFor(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      return ai.clientRequestStopped === true && ai.loading === false
    })()`, 'CLIENT_REQUEST_STOP_NOT_APPLIED')
  } else {
    await browser.waitFor(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      return ai.loading === true && ai.messages.some((message) => message.role === 'assistant' && message.streaming === true)
    })()`, 'STOP_WINDOW_NOT_FORMED')
    await browser.click('[data-testid="stop-btn"]')
    await browser.waitFor(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      return ai.stopped === true && ai.loading === false
    })()`, 'STOP_NOT_APPLIED')
  }
  const stopped = await browser.snapshot()
  context.sessionId = stopped.sessionId
  context.runId = stopped.activeRunId ?? [...stopped.messages].reverse().find((message) => message.runId)?.runId ?? null
  const initialSignature = recoveryFactsSignature(stopped)
  const reconnectBaseline = browser.requestCounts()
  if (config.variant === 'current-failure') await browser.setRunCurrentFault('always')
  if (config.variant === 'expired' || config.variant === 'incomplete') {
    const current = await context.api.runCurrent(context.sessionId)
    if (current.status !== 'AVAILABLE') throw new ScenarioNotRun('STOPPED_CURRENT_FIXTURE_NOT_FORMED')
    if (config.variant === 'expired' && current.data.found !== false) throw new ScenarioNotRun('STOPPED_EXPIRED_FIXTURE_NOT_FORMED')
    if (config.variant === 'incomplete' && !(current.data.found === true
      && (!Array.isArray(current.data.pendingEntries) || typeof current.data.awaitingExternal !== 'boolean'))) {
      throw new ScenarioNotRun('STOPPED_INCOMPLETE_CURRENT_NOT_FORMED')
    }
  }

  browser.setPhase('stopped-recovery')
  await browser.reload({waitForHistory: false})
  if (config.variant === 'current-failure') {
    const network = await browser.waitForRunCurrentPhase('stopped-recovery', {minimumFaultCount: 1})
    await context.evidence.json('stopped-current-failure-network.json', network)
  } else {
    await browser.waitForHistoryPhase('stopped-recovery', {minimumSuccessCount: 1})
  }
  const recovered = await browser.snapshot()
  if (config.variant === 'stop' || config.variant === 'client-request-stopped') {
    await browser.waitFor(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      return ai.restoringRecovery === false && ai.historyLoaded && ai.canSend === true
    })()`, 'STOPPED_HISTORY_ONLY_RECOVERY_NOT_COMPLETED')
    const settled = await browser.snapshot()
    const history = requiredHistory(await context.api.history(context.sessionId))
    assertHistoryProjectionCommit(context, 'stopped-recovery', settled, history)
    checks.require('stopped-recovery.content-stable', recoveryFactsSignature(settled) === initialSignature,
      initialSignature, recoveryFactsSignature(settled))
    // 已确认口径：stop 不要求全局 stopped 复活；必须核到“目标 run 的助手消息”自带 stopped=true。
    const targetAssistant = settled.messages.find((message) => message.role === 'assistant'
      && (context.runId === null || message.runId === context.runId))
    if (config.variant === 'stop') {
      checks.require('stopped-recovery.stopped-state', targetAssistant?.stopped === true,
        'the target run assistant message keeps stopped=true after history-only commit', {
          runId: context.runId,
          messageId: targetAssistant?.id ?? null,
          messageStopped: targetAssistant?.stopped ?? null,
          globalStopped: settled.stopped,
          clientRequestStopped: settled.clientRequestStopped
        })
    } else {
      checks.require('stopped-recovery.client-request-stopped-state', settled.clientRequestStopped === true,
        'the real clientRequestStopped marker survives history-only commit', {
          stopped: settled.stopped,
          clientRequestStopped: settled.clientRequestStopped
        })
    }
    const view = await recoveryView(browser)
    // 发送按钮在空草稿时按设计禁用：先填非空草稿，再验证真实可发送且不触发 chat。
    const chatBeforeDraft = browser.requestCounts().chat
    await browser.evaluate(`document.querySelector('[data-testid="composer-input"]').focus()`, false)
    await browser.send('Input.insertText', {text: `resume-${randomUUID()}`})
    const sendWithDraft = await browser.waitFor(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      const input = document.querySelector('[data-testid="composer-input"]')
      const send = document.querySelector('[data-testid="send-btn"]')
      if (!input || !send || input.value.length === 0) return null
      return {canSend: ai.canSend === true, disabled: send.disabled === true || send.getAttribute('aria-disabled') === 'true'}
    })()`, 'STOPPED_SEND_NOT_EVALUABLE_WITH_DRAFT')
    checks.require('stopped-recovery.send-unlocked', view.snapshot.canSend && sendWithDraft.canSend && sendWithDraft.disabled === false,
      'stopped history-only commit unlocks sending (verified with a non-empty draft)', {...sendWithDraft, send: view.operations.send})
    checks.require('stopped-recovery.no-chat-from-draft', browser.requestCounts().chat === chatBeforeDraft,
      chatBeforeDraft, browser.requestCounts().chat)
    await browser.evaluate(`(() => {
      const input = document.querySelector('[data-testid="composer-input"]')
      if (!input) return false
      input.value = ''
      input.dispatchEvent(new Event('input', {bubbles: true}))
      return true
    })()`, false)
    assertNoReconnect(context, 'stopped-recovery', reconnectBaseline)
    await saveRecoveryPhase(context, 'stopped-recovery', view, await context.api.history(context.sessionId),
      await businessSnapshot(context.api, relevantTaskIds(context)))
  } else {
    checks.require('stopped-recovery.no-history-commit', recovered.historyProjection.windowEntryCount === 0
      && recovered.historyProjection.eventCount === 0,
    'expired/failed/incomplete current cannot commit history messages/events', recovered.historyProjection)
    assertNoReconnect(context, 'stopped-recovery', reconnectBaseline)
    checks.require('stopped-recovery.no-new-chat', browser.requestCounts().chat === reconnectBaseline.chat,
      reconnectBaseline.chat, browser.requestCounts().chat)
    await savePhase(context, 'stopped-recovery-negative', recovered, await context.api.history(context.sessionId),
      await businessSnapshot(context.api, relevantTaskIds(context)))
  }
  await context.evidence.json('stopped-recovery.json', {variant: config.variant, initialSignature, recovered: recovered.historyProjection})
  if (config.variant === 'current-failure') await browser.setRunCurrentFault('off')
}

async function runSheetPreviewScenario(context) {
  const {browser, checks, config, api, evidence} = context
  if (!config.xlsxFixture) throw new VerificationFailure('XLSX_FIXTURE_REQUIRED')
  const bytes = await readFile(config.xlsxFixture)
  const task = await api.createTaskTyped(`c159-${randomUUID()}`, 'IMPORT')
  const taskId = Number(task.id)
  if (!Number.isSafeInteger(taskId) || taskId <= 0) throw new VerificationFailure('SHEET_FIXTURE_TASK_INVALID')
  context.fixtureTaskId = taskId
  await api.selectDefs(taskId)
  await api.setImportMode(taskId, 'MERGE')
  await api.uploadTaskFile(taskId, bytes, 'CURRENCY_c159.xlsx', ['CURRENCY'])
  const precheck = await api.startJobAndWait(taskId, 'PRECHECK')
  checks.require('fixture.precheck-completed', precheck.status === 'COMPLETED',
    'public upload passes the real precheck job', {jobId: precheck.id, status: precheck.status})
  const imported = await api.startJobAndWait(taskId, 'IMPORT')
  checks.require('fixture.import-completed', imported.status === 'COMPLETED',
    'the real IMPORT job completes and leaves staged rows', {jobId: imported.id, status: imported.status})
  // 导入暂存区：IMPORT 后任务仍为 ACTIVE/可编辑，写预览应发生在暂存表；
  // EXPORT 变体才需要 PUBLISH 落库并确认 >50 行。
  let published = null
  let rowCount = null
  if (config.variant === 'export') {
    published = await api.startJobAndWait(taskId, 'PUBLISH')
    checks.require('fixture.publish-completed', published.status === 'COMPLETED',
      'the real PUBLISH job applies the staged rows to the data table', {jobId: published.id, status: published.status})
    rowCount = await api.dataCount('CURRENCY')
    checks.require('fixture.data-rows-over-page', Number.isSafeInteger(rowCount) && rowCount > 50,
      'the imported data table holds more than one preview page (>50 rows)', {rowCount})
  }
  const overview = await api.request('task.overview', `/api/tasks/${taskId}/overview`)
  const overviewData = overview.body?.data ?? overview.body
  await evidence.json('sheet-fixture.json', {
    taskId,
    precheck: {id: precheck.id, status: precheck.status},
    import: {id: imported.id, status: imported.status},
    publish: published ? {id: published.id, status: published.status} : null,
    rowCount,
    overview: {status: overview.status, keys: Object.keys(overviewData ?? {}), sha256: sha256(canonicalJson(overviewData ?? null))}
  })

  let targetTaskId = taskId
  let targetPage = 'import'
  let targetStep = null
  if (config.variant === 'export') {
    // 导入数据用于形成多页结果行；预览改在 EXPORT/EXPORT 结果编辑区进行。
    const exportTask = await api.createTaskTyped(`c159-export-${randomUUID()}`, 'EXPORT')
    targetTaskId = Number(exportTask.id)
    context.fixtureTaskId = targetTaskId
    await api.selectDefs(targetTaskId)
    await api.setCondition(targetTaskId)
    await api.goToStep(targetTaskId, 'QUERY_COND')
    await api.goToStep(targetTaskId, 'EXPORT')
    const exportJob = await api.startJobAndWait(targetTaskId, 'EXPORT')
    checks.require('fixture.export-completed', exportJob.status === 'COMPLETED',
      'the real EXPORT job completes over the imported rows', {jobId: exportJob.id, status: exportJob.status})
    targetPage = 'export'
    targetStep = 'EXPORT'
  }
  const url = new URL(config.appUrl)
  url.pathname = `/${targetPage}`
  url.search = `?taskId=${targetTaskId}`
  await browser.send('Page.navigate', {url: url.toString()})
  await browser.waitFor(`(() => {
    const pinia = document.getElementById('app').__vue_app__.config.globalProperties.$pinia
    const workspace = pinia._s.get('workspace')
    const ai = pinia._s.get('ai')
    return workspace.taskId === ${targetTaskId} && ai.historyLoaded === true && ai.restoringRecovery === false
      ${targetStep ? `&& workspace.step === '${targetStep}'` : ''}
  })()`, 'SHEET_PREVIEW_PAGE_NOT_READY')
  if (targetPage === 'export') {
    await browser.waitFor(`(async () => {
      const {getPageHandler} = await import('/src/stores/workspace.ts')
      return getPageHandler('export.taskDetailReady')?.({}) === true
    })()`, 'EXPORT_RESULT_EDITOR_NOT_READY', context.config.timeoutMs, true)
  }

  // 导入页只有“编辑标签页 + SpreadGrid 已挂载”时才交出 sheet.current；
  // 自动化前必须先打开 CURRENCY 标签页，否则预览计划会是 0 处变更。
  let tabReady = true
  try {
    await browser.waitFor(`document.querySelector('[data-ai-ref="import.grid.tab.CURRENCY"]') !== null`,
      'IMPORT_EDITOR_TAB_WAIT', 15_000)
  } catch {
    tabReady = false
  }
  if (targetPage === 'export') {
    await browser.waitFor(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      return ai.historyLoaded === true
    })()`, 'EXPORT_EDITOR_READY', context.config.timeoutMs)
  } else if (tabReady) {
    await browser.evaluate(`(() => {
      const tab = document.querySelector('[data-ai-ref="import.grid.tab.CURRENCY"]')
      if (tab && tab.getAttribute('aria-selected') !== 'true') tab.click()
      return true
    })()`, false)
    await browser.waitFor(`(() => {
      const tab = document.querySelector('[data-ai-ref="import.grid.tab.CURRENCY"]')
      return tab !== null && tab.getAttribute('aria-selected') === 'true'
    })()`, 'SHEET_EDITOR_TAB_NOT_ACTIVE', 10_000)
  } else {
    throw new ScenarioNotRun('SHEET_EDITOR_TAB_NOT_FORMED')
  }
  await browser.sendPrompt(`scenario=SHEET-MULTI verification=${randomUUID()}`)
  try {
    await browser.waitFor(`(() => {
      const card = document.querySelector('[data-testid="sheet-preview-card"]')
      return card !== null && document.querySelector('[data-testid^="sheet-preview-level-"]') !== null
    })()`, 'SHEET_PREVIEW_CARD_NOT_FORMED')
  } catch (error) {
    const failure = await browser.evaluate(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      const part = ai.messages.flatMap((message) => message.parts).filter((candidate) => candidate.name === 'update_rows').pop()
      return part ? {status: part.status ?? null, result: String(part.result ?? '').slice(0, 600), reason: String(part.reason ?? '').slice(0, 600)} : null
    })()`, false)
    await evidence.json('sheet-preview-failure.json', failure)
    throw error
  }
  // 默认是 sample 档；切到 full-diff 才会渲染分页（>50 条变更时多页）。
  await browser.click('[data-testid="sheet-preview-level-full-diff"]')
  const readPreview = `(() => {
    const card = document.querySelector('[data-testid="sheet-preview-card"]')
    const levels = [...document.querySelectorAll('[data-testid^="sheet-preview-level-"]')]
    const pager = document.querySelector('[data-testid="sheet-preview-full"]')
    const apply = document.querySelector('[data-testid="sheet-preview-apply"]')
    if (!card || levels.length === 0 || !pager) return null
    const buttons = [...pager.querySelectorAll('button')]
    const text = card.textContent ?? ''
    let hash = 2166136261
    for (let index = 0; index < text.length; index += 1) {
      hash ^= text.charCodeAt(index)
      hash = Math.imul(hash, 16777619)
    }
    return {
      levelCount: levels.length,
      levelsDisabled: levels.some((level) => level.disabled === true),
      pagerButtons: buttons.length,
      pageLabel: (pager.querySelector(':scope > div:last-child > span')?.textContent ?? '').trim(),
      prevDisabled: buttons[0]?.disabled === true,
      nextDisabled: buttons[1]?.disabled === true,
      applyPresent: apply !== null,
      applyDisabled: apply === null ? null : apply.disabled === true,
      contentHash: hash >>> 0,
      textLength: text.length
    }
  })()`
  let preview
  try {
    preview = await browser.waitFor(readPreview, 'SHEET_PREVIEW_NOT_FORMED')
  } catch (error) {
    const failure = await browser.evaluate(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      const part = ai.messages.flatMap((message) => message.parts).filter((candidate) => candidate.name === 'update_rows').pop()
      return part ? {status: part.status ?? null, result: String(part.result ?? '').slice(0, 600), reason: String(part.reason ?? '').slice(0, 600)} : null
    })()`, false)
    await evidence.json('sheet-preview-failure.json', failure)
    throw error
  }
  const pageMatch = /(\d+)\s*\/\s*(\d+)/.exec(preview.pageLabel)
  const pageTotal = pageMatch ? Number(pageMatch[2]) : 0
  checks.require('preview.multi-page', preview.levelCount > 0 && preview.pagerButtons === 2 && pageTotal >= 2,
  'a real multi-page preview renders levels and prev/next pagination', {...preview, pageTotal})
  checks.require('preview.write-requires-approval', preview.applyPresent && preview.applyDisabled === false,
    'preview exposes a clickable apply only after approval is allowed', preview)
  const pagedClick = await browser.evaluate(`(() => {
    const pager = document.querySelector('[data-testid="sheet-preview-full"]')
    const buttons = pager ? [...pager.querySelectorAll('button')] : []
    const next = buttons[buttons.length - 1]
    const before = (pager?.querySelector(':scope > div:last-child > span')?.textContent ?? '').trim()
    const clicked = !!(next && !next.disabled)
    if (clicked) next.click()
    const after = (pager?.querySelector(':scope > div:last-child > span')?.textContent ?? '').trim()
    return {clicked, before, after}
  })()`, false)
  checks.require('preview.pagination-readonly-works', preview.pageLabel !== ''
    && preview.nextDisabled === false && preview.prevDisabled === true,
  'multi-page preview exposes usable prev/next (page 1: prev disabled, next enabled) without submitting writes',
  {pageLabel: preview.pageLabel, prevDisabled: preview.prevDisabled, nextDisabled: preview.nextDisabled, click: pagedClick})

  const baseline = browser.requestCounts()
  browser.setPhase('sheet-restore')
  await browser.setHistoryFault('always')
  await browser.reload({waitForHistory: false})
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.restoringRecovery === true
  })()`, 'SHEET_RESTORE_NOT_ACTIVE')
  const fullLevel = await browser.evaluate(`(() => {
    const level = document.querySelector('[data-testid="sheet-preview-level-full-diff"]')
    if (!level || level.disabled) return false
    if (level.getAttribute('aria-pressed') !== 'true') level.click()
    return true
  })()`, false)
  const during = await browser.waitFor(`(() => {
    const card = document.querySelector('[data-testid="sheet-preview-card"]')
    const levels = [...document.querySelectorAll('[data-testid^="sheet-preview-level-"]')]
    const pager = document.querySelector('[data-testid="sheet-preview-full"]')
    const apply = document.querySelector('[data-testid="sheet-preview-apply"]')
    if (!card || levels.length === 0) return null
    const text = card.textContent ?? ''
    let hash = 2166136261
    for (let index = 0; index < text.length; index += 1) {
      hash ^= text.charCodeAt(index)
      hash = Math.imul(hash, 16777619)
    }
    return {
      levelCount: levels.length,
      levelsDisabled: levels.some((level) => level.disabled === true),
      pagerButtons: pager ? [...pager.querySelectorAll('button')].length : 0,
      applyPresent: apply !== null,
      applyDisabled: apply === null ? null : apply.disabled === true,
      contentHash: hash >>> 0
    }
  })()`, 'SHEET_PREVIEW_DURING_RECOVERY_NOT_READABLE')
  checks.require('restore.level-toggle-enabled', fullLevel === true,
    'the read-only preview level switch stays clickable during recovery', {fullLevel})
  checks.require('restore.levels-and-pagination-usable', during.levelCount === preview.levelCount
    && during.levelsDisabled === false && during.pagerButtons === 2,
  'read-only level/pagination controls stay usable during history-503 recovery', during)
  checks.require('restore.write-blocked', during.applyPresent === false || during.applyDisabled === true,
    'write/apply stays blocked while recovery has not committed', during)
  checks.require('restore.content-unchanged', during.contentHash === preview.contentHash,
    'card content hash is unchanged during recovery', {before: preview.contentHash, during: during.contentHash})

  await browser.setHistoryFault('off')
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.restoringRecovery === false
  })()`, 'SHEET_RESTORE_NOT_COMPLETED')
  const after = await browser.waitFor(readPreview, 'SHEET_PREVIEW_NOT_RESTORED')
  checks.require('restore.preview-stable', after.contentHash === preview.contentHash
    && after.levelCount === preview.levelCount && after.applyPresent === true,
  'preview content/summary is reproduced exactly after recovery', {before: preview, after})
  const finalCounts = browser.requestCounts()
  checks.require('restore.zero-duplicate-submits', finalCounts.frontendToolResult === baseline.frontendToolResult
    && finalCounts.confirm === baseline.confirm && finalCounts.chat === baseline.chat,
  'recovery/refresh never re-submits a preview write or re-opens a chat', {baseline, after: finalCounts})
  await evidence.json('sheet-preview.json', {taskId, preview, during, after, requests: finalCounts})
}

async function runTimingProxyScenario(context) {
  const {browser, checks, config, api, evidence} = context
  const variants = new Set(['full', 'incremental'])
  if (!variants.has(config.variant)) throw new ScenarioNotRun('RECOVERY_VARIANT_REQUIRED')
  if (!config.proxyLogDir) throw new VerificationFailure('PROXY_LOG_DIR_REQUIRED')
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.historyLoaded === true && ai.restoringRecovery === false && ai.loading === false
  })()`, 'TIMING_PROXY_APP_NOT_READY')

  const contentVariant = config.contentVariant ?? 'timeline'
  const contentPrompts = {
    timeline: `timeline-nonprefix verification=${randomUUID()}`,
    interleave: `turn-interleave multi-card=2 reasoning=120 stall-frames=250 verification=${randomUUID()}`,
    'gf-seven': `gf-seven-types multi-card=2 stall-frames=250 verification=${randomUUID()}`
  }
  if (!contentPrompts[contentVariant]) throw new ScenarioNotRun('CONTENT_VARIANT_INVALID')
  await browser.sendPrompt(contentPrompts[contentVariant])
  // 增量锚点：轮次仍在途时读取公开 runSeq（不等终态），作为 `?lastSeq=` 锚点。
  let anchorState = {runId: null, lastSeq: 0}
  const anchorDeadline = Date.now() + 8_000
  while (Date.now() < anchorDeadline) {
    anchorState = await browser.evaluate(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      const runId = ai.activeRunId ?? null
      const value = runId ? (ai.runSeq ?? {})[runId] : null
      return {runId, lastSeq: Number.isSafeInteger(value) ? value : 0}
    })()`, false)
    if (anchorState?.runId && anchorState.lastSeq > 0) break
    await sleep(100)
  }
  if (anchorState?.runId) context.runId = anchorState.runId
  const incrementalAnchor = Number(anchorState?.lastSeq ?? 0)
  const submitPendingForm = async () => {
    const form = await browser.evaluate(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      if (!ai.activeForm?.toolCallId) return null
      const field = (ai.activeForm.form?.fields ?? []).find((candidate) => candidate.type === 'text')
      return field ? {toolCallId: ai.activeForm.toolCallId, key: field.key} : null
    })()`, false)
    if (!form) return false
    await browser.evaluate(`document.querySelector('[data-testid="gf-field-${form.toolCallId}-${form.key}"]').focus()`, false)
    await browser.send('Input.insertText', {text: `proxy-${randomUUID()}`})
    const clicked = await browser.evaluate(`(() => {
      const submit = document.querySelector('[data-testid="gf-form-${form.toolCallId}"] [data-testid="gf-submit"]')
      if (!submit || submit.disabled) return false
      submit.click()
      return true
    })()`, false)
    if (!clicked) return false
    return form.toolCallId
  }
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.activeForm?.toolCallId || (ai.loading === false
      && ai.messages.some((message) => message.role === 'assistant' && message.done))
  })()`, 'TIMING_PROXY_RUN_NOT_FORMED')
  for (let attempt = 0; attempt < 5; attempt += 1) {
    const submittedId = await submitPendingForm()
    if (!submittedId) break
    await browser.waitFor(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      return ai.activeForm?.toolCallId !== ${JSON.stringify(submittedId)} || ai.loading === false
    })()`, 'TIMING_PROXY_FORM_NOT_SUBMITTED')
  }

  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.restoringRecovery === false && ai.loading === false
      && ai.messages.filter((message) => message.role === 'assistant').every((message) => message.done)
  })()`, 'TIMING_PROXY_RUN_NOT_TERMINAL')

  const snapshot = await browser.snapshot()
  context.sessionId = snapshot.sessionId
  context.runId = snapshot.activeRunId
  const parts = snapshot.messages.filter((message) => message.role === 'assistant').flatMap((message) => message.parts)
  const kinds = parts.map((part) => part.kind === 'tool' ? `tool:${part.name}` : part.kind)
  const texts = parts.filter((part) => part.kind === 'text').map((part) => part.text ?? '')
  const toolParts = parts.filter((part) => part.kind === 'tool')
  if (contentVariant === 'timeline') {
    checks.require('proxy.part-order', canonicalJson(kinds) === canonicalJson(['text', 'tool:list_config_defs', 'text', 'tool:generative_form', 'text']),
      ['text', 'tool:list_config_defs', 'text', 'tool:generative_form', 'text'], kinds)
    checks.require('proxy.three-non-prefix-texts', texts.length === 3
      && texts.every((text, index) => texts.slice(index + 1).every((later) => !text.startsWith(later) && !later.startsWith(text))),
    'three non-prefix text bodies', texts.map((text) => textEvidence(text)))
  } else if (contentVariant === 'interleave') {
    // 产品会在首部生成空字符串 text 占位；按 C-131A 口径只忽略“空字符串 text”，保留其余顺序。
    const nonEmptyKinds = parts
      .filter((part) => !(part.kind === 'text' && (part.text ?? '') === ''))
      .map((part) => part.kind === 'tool' ? `tool:${part.name}` : part.kind)
    checks.require('proxy.interleave-order', canonicalJson(nonEmptyKinds) === canonicalJson(['tool:list_config_defs', 'tool:list_config_defs', 'text']),
      ['tool:list_config_defs', 'tool:list_config_defs', 'text'], {kinds, nonEmptyKinds})
    checks.require('proxy.interleave-two-distinct-tools', toolParts.length === 2
      && new Set(toolParts.map((part) => part.toolCallId)).size === 2
      && texts.some((text) => text.trim() !== ''),
    'two distinct tool calls followed by closing text', {
      tools: toolParts.map((part) => part.toolCallId),
      texts: texts.map((text) => textEvidence(text))
    })
  } else {
    checks.require('proxy.gf-seven-two-forms', toolParts.filter((part) => part.name === 'generative_form').length === 2
      && new Set(toolParts.map((part) => part.toolCallId)).size === toolParts.length,
    'two distinct generative forms are driven to terminal without duplicate toolCallId', toolParts.map((part) => ({
      name: part.name, toolCallId: part.toolCallId, status: part.status
    })))
  }

  // historyProjection 由 loadHistory 提交；实时轮次只推进 messages，这里强制一次公开 loadHistory 再核对。
  await browser.evaluate(`(async () => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    await ai.loadHistory()
    return true
  })()`, true)
  const committed = await waitForHistoryProjectionCommit(context, 'proxy-history')
  checks.require('proxy.history-converged', committed !== null, 'UI projection converges to authoritative history',
    committed === null ? null : committed.authoritative)

  const logFiles = (await readdir(config.proxyLogDir)).filter((name) => name.startsWith('history-timing-proxy-') && name.endsWith('.jsonl')).sort()
  if (logFiles.length === 0) throw new VerificationFailure('PROXY_LOG_NOT_FOUND')
  const logPath = path.join(config.proxyLogDir, logFiles[logFiles.length - 1])
  const records = (await readFile(logPath, 'utf8')).trim().split('\n').filter(Boolean).map((line) => JSON.parse(line))
  const histories = records.filter((record) => String(record.path ?? record.pathname ?? '').startsWith('/api/ai/history/'))
  checks.require('proxy.first-history-delayed', histories.length >= 1 && histories[0].delayMs === 800,
    'the first history response is delayed by 800ms', histories.slice(0, 3))
  checks.require('proxy.later-history-not-delayed', histories.slice(1).every((record) => record.delayMs === 0),
    'every later history response is forwarded without delay', histories.slice(0, 6))
  checks.require('proxy.history-status-200', histories.every((record) => record.status === 200),
    'proxy forwards real 200 responses unchanged', histories.map((record) => record.status))
  await evidence.json('timing-proxy-log.json', {
    logFile: logFiles[logFiles.length - 1],
    historyCount: histories.length,
    histories: histories.slice(0, 8)
  })

  const proxyFacts = (value) => {
    const facts = JSON.parse(recoveryFactsSignatureWithoutSeq(value))
    if (contentVariant === 'interleave') {
      for (const message of facts.messages) {
        message.parts = (message.parts ?? [])
          .filter((part) => !(part.kind === 'text' && part.text && part.text.length === 0))
          .map((part) => {
            const {seg, ...rest} = part
            return rest
          })
      }
    }
    if (contentVariant === 'gf-seven') {
      // C-138 B 只核对 history 时序下的正文/parts/卡片；活动/排队表单的队列稳定性由 C-131B/C-163 单独跟踪。
      delete facts.activeForm
      delete facts.queuedForms
      for (const message of facts.messages) delete message.done
    }
    return canonicalJson(facts)
  }
  const before = proxyFacts(snapshot)
  browser.setPhase('full-replay')
  await browser.reload()
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.historyLoaded === true && ai.restoringRecovery === false && ai.loading === false
  })()`, 'TIMING_PROXY_FULL_REPLAY_NOT_SETTLED')
  const replayed = await browser.snapshot()
  checks.require('full.content-stable', proxyFacts(replayed) === before,
    'full history replay reproduces the same ordered content/parts/cards', {
      before,
      after: proxyFacts(replayed)
    })
  const replayCommitted = await waitForHistoryProjectionCommit(context, 'full-replay-history')
  checks.require('full.history-converged', replayCommitted !== null,
    'full replay also converges to the authoritative history', replayCommitted === null ? null : replayCommitted.authoritative)

  // 增量锚点取“后缀尚未收齐时”的公开 runSeq（incrementalAnchor），刷新后仍用它回放；
  // 这样回放必然包含后缀帧与终帧，而不是 lastSeq=archiveMax 时的空回放。
  const lastSeq = incrementalAnchor
  checks.require('incremental.last-seq-known', lastSeq > 0, 'store exposed a positive lastSeq while the suffix was in flight', {lastSeq, runId: context.runId})
  const replay = await api.eventsReplay(context.runId, lastSeq)
  checks.require('incremental.replay-available', replay.status === 'AVAILABLE' && replay.frames.length > 0,
    'public events?lastSeq replays frames', {status: replay.status, frameCount: replay.frames.length})
  const seqs = replay.frames.map((frame) => frame.seq).filter((seq) => Number.isSafeInteger(seq))
  checks.require('incremental.only-newer-frames', seqs.every((seq) => seq > lastSeq),
    'incremental reattach only returns frames with seq > lastSeq', {lastSeq, seqs: seqs.slice(0, 32)})
  checks.require('incremental.seq-unique-ascending', seqs.every((seq, index) => index === 0 || seq > seqs[index - 1]),
    'replayed seq values are unique and strictly ascending', seqs)
  if (contentVariant !== 'gf-seven') {
    checks.require('incremental.terminal-present', replay.frames.some((frame) => ['done', 'error'].includes(frame.type)),
      'incremental replay includes the terminal frame', replay.frames.slice(-8))
  } else {
    checks.require('incremental.gf-frames-present', replay.frames.length > 0,
      'incremental replay exposes the suspended-run frames for the GF variant', replay.frames.slice(-8))
  }
  await evidence.json('timing-proxy-incremental.json', {
    variant: config.variant,
    contentVariant,
    lastSeq,
    frameCount: replay.frames.length,
    frames: replay.frames.slice(0, 64)
  })
}

async function runContentMatrix(context) {
  const {browser, checks, config, api} = context
  const variants = new Set(['interleave', 'gf-seven', 'timeline', 'boundary', 'repeat-refresh', 'form-draft', 'composer-draft', 'copy-scan', 'tool-chain', 'tool-chain-known'])
  if (!variants.has(config.variant)) throw new ScenarioNotRun('CONTENT_MATRIX_VARIANT_REQUIRED')
  const nonce = randomUUID()

  const waitSettled = (code, {allowPendingForm = false} = {}) => browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    const done = ai.messages.filter((message) => message.role === 'assistant').every((message) => message.done === true)
    return ai.historyLoaded === true && ai.restoringRecovery === false && ai.loading === false
      && (done || (${allowPendingForm} && ai.activeForm !== null))
  })()`, code)

  const shapeOf = (snapshot) => {
    const assistants = snapshot.messages.filter((message) => message.role === 'assistant')
    return {
      userCount: snapshot.messages.filter((message) => message.role === 'user').length,
      assistantCount: assistants.length,
      toolCallIds: assistants.flatMap((message) => message.parts.filter((part) => part.kind === 'tool').map((part) => part.toolCallId)),
      textParts: assistants.flatMap((message) => message.parts.filter((part) => part.kind === 'text').map((part) => part.text ?? '')),
      cardCount: snapshot.cards.length
    }
  }

  const assertBase = (phase, snapshot) => {
    const shape = shapeOf(snapshot)
    checks.require(`${phase}.single-user-message`, shape.userCount === 1, 1, shape.userCount)
    checks.require(`${phase}.assistant-present`, shape.assistantCount >= 1, 'at least one assistant message', shape.assistantCount)
    checks.require(`${phase}.no-duplicate-tool-calls`,
      new Set(shape.toolCallIds).size === shape.toolCallIds.length, 'unique toolCallId across the run', shape.toolCallIds)
    return shape
  }

  // C-131 文档要求核对正文、parts 与顺序；`seq`/eventId 是恢复重投影的瞬态元数据
  // （C-161 的 seq 差异已单列交主会话），这里比较内容/顺序/状态/卡片，并把 seq 单独留证。
  // interleave：只过滤“空字符串 text part”（模型首部空占位），空白非空 text 保留并保持正文顺序。
  const contentFacts = (snapshot, {dropEmptyTextParts = false} = {}) => canonicalJson({
    messages: snapshot.messages.map((message) => ({
      role: message.role,
      runId: message.runId,
      done: message.done,
      content: sha256(message.content ?? ''),
      parts: message.parts
        .filter((part) => !(dropEmptyTextParts && part.kind === 'text' && (part.text ?? '') === ''))
        .map((part) => ({
          kind: part.kind,
          name: part.name ?? null,
          toolCallId: part.toolCallId ?? null,
          status: part.status ?? null,
          requiresAction: part.requiresAction ?? null,
          decision: part.decision ?? null,
          seq: part.seq ?? null,
          text: part.kind === 'text' ? sha256(part.text ?? '') : null,
          args: part.kind === 'tool' && part.args !== null && part.args !== undefined ? sha256(canonicalJson(part.args)) : null
        }))
    })),
    activeForm: snapshot.activeForm ? {
      toolCallId: snapshot.activeForm.toolCallId,
      status: snapshot.activeForm.status,
      fields: (snapshot.activeForm.fields ?? []).map((field) => `${field.key}:${field.type}`)
    } : null,
    queuedForms: snapshot.queuedForms.map((entry) => entry.toolCallId),
    // 卡片内容散列用 textContent（展开/折叠不改变内容）；innerText 仍用于可见文案扫描。
    cards: snapshot.cards.map((card) => ({testId: card.testId, state: card.state, text: sha256(card.contentText ?? card.text ?? '')}))
  })
  const factsOf = (snapshot) => contentFacts(snapshot, {dropEmptyTextParts: config.variant === 'interleave'})
  const seqFacts = (snapshot) => canonicalJson(snapshot.messages.map((message) => message.parts.map((part) => part.seq ?? null)))

  if (config.variant === 'tool-chain-known') {
    // C-137 已知/非空条件变体：真实 set_condition 调用必须保留自己的工具卡（不强制空调用）。
    const task = await api.createTask(`c137-known-${randomUUID()}`)
    const taskId = Number(task.id)
    context.fixtureTaskId = taskId
    await api.selectDefs(taskId)
    await api.goToStep(taskId, 'QUERY_COND')
    const fixtureUrl = new URL(config.appUrl)
    fixtureUrl.pathname = '/export'
    fixtureUrl.search = `?taskId=${taskId}`
    await browser.send('Page.navigate', {url: fixtureUrl.toString()})
    await browser.waitFor(`(() => {
      const pinia = document.getElementById('app').__vue_app__.config.globalProperties.$pinia
      const workspace = pinia._s.get('workspace')
      const ai = pinia._s.get('ai')
      return workspace.taskId === ${taskId} && workspace.step === 'QUERY_COND' && ai.historyLoaded === true
    })()`, 'CONDITION_FIXTURE_NOT_READY')
    await browser.sendPrompt(`tool=set_condition&args={"defCode":"CURRENCY","conditions":{"fields":[{"fieldCode":"code","operator":"EQ","value":"CNY"}]}} verification=${randomUUID()}`)
    await browser.waitFor(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      return ai.loading === false && ai.messages.flatMap((message) => message.parts)
        .some((part) => part.name === 'set_condition' && part.status === 'succeeded')
    })()`, 'SET_CONDITION_NOT_SUCCEEDED')
    const snapshot = await browser.snapshot()
    const conditionParts = snapshot.messages.flatMap((message) => message.parts).filter((part) => part.name === 'set_condition')
    checks.require('tool-chain-known.real-call-card', conditionParts.length === 1
      && conditionParts[0].status === 'succeeded'
      && targetCard(snapshot, conditionParts[0].toolCallId).length === 1,
    'a non-empty condition request keeps exactly one real set_condition card', conditionParts.map((part) => ({
      toolCallId: part.toolCallId,
      status: part.status,
      cards: targetCard(snapshot, part.toolCallId).map((card) => card.testId)
    })))
    checks.require('tool-chain-known.args-recorded', conditionParts[0].args !== null && conditionParts[0].args !== undefined,
      'the real call carries its own arguments (bounded hash in evidence)', {
        args: conditionParts[0].args === null ? null : sha256(canonicalJson(conditionParts[0].args))
      })
    await context.evidence.json('tool-chain-known.json', {
      taskId,
      parts: conditionParts.map((part) => ({toolCallId: part.toolCallId, status: part.status})),
      queryConditions: canonicalJson(snapshot.workspace?.queryConditions ?? {}) === '{}' ? 'empty' : 'non-empty'
    })
    return
  }

  if (config.variant === 'tool-chain') {
    // C-137：完整创建导出链，逐工具一张真实卡，启动确认停在 HITL，业务只 1 个任务 0 作业。
    context.businessAfterFixture = await businessSnapshot(context.api, relevantTaskIds(context))
    await browser.sendPrompt(`tool=create_task&args={"type":"EXPORT","conditions":{},"openWizard":false} host-def-select verification=${nonce}`)
    await approveCreateTasks(context)
    await driveSkippedHostContinuation(context)
    const snapshot = await browser.snapshot()
    context.sessionId = snapshot.sessionId
    context.runId = snapshot.activeRunId
    const parts = snapshot.messages.flatMap((message) => message.parts).filter((part) => part.kind === 'tool')
    const byName = {}
    for (const part of parts) (byName[part.name] ??= []).push({toolCallId: part.toolCallId, status: part.status, requiresAction: part.requiresAction})
    const required = ['create_task', 'navigate_to', 'select_definitions', 'confirm_step', 'start_export']
    checks.require('tool-chain.required-tools', required.every((name) => (byName[name] ?? []).length >= 1),
      required, Object.keys(byName))
    checks.require('tool-chain.start-export-pending', (byName.start_export ?? []).some((part) => part.requiresAction === true),
      'start_export waits for real HITL approval', byName.start_export ?? [])
    checks.require('tool-chain.one-card-per-tool-call', parts.filter((part) => part.name !== 'generative_form')
      .every((part) => targetCard(snapshot, part.toolCallId).length === 1),
    'each non-form toolCallId keeps exactly one visible card', parts.map((part) => ({
      name: part.name,
      toolCallId: part.toolCallId,
      cards: targetCard(snapshot, part.toolCallId).map((card) => card.testId)
    })))
    // 已确认口径：留空条件链不应出现 set_condition 卡，条件由宿主表单 + confirm_step 落地且 queryConditions 为空。
    checks.require('tool-chain.empty-conditions-no-set-condition', (byName.set_condition ?? []).length === 0,
      'an empty-condition chain must not fabricate a set_condition call', Object.keys(byName))
    checks.require('tool-chain.empty-conditions-state', (byName.confirm_step ?? []).length >= 1
      && canonicalJson(snapshot.workspace?.queryConditions ?? {}) === '{}',
    'the host condition receipt + confirm_step apply an empty query condition', {
      confirmStep: byName.confirm_step ?? [],
      queryConditions: snapshot.workspace?.queryConditions ?? null
    })
    const business = await businessSnapshot(context.api, relevantTaskIds(context))
    const newTasks = business.allTasks.filter((task) => !context.businessAfterFixture.allTasks.some((before) => before.id === task.id))
    checks.require('tool-chain.single-task-no-jobs', newTasks.length === 1 && newTasks.every((task) => task.jobs.length === 0),
      'exactly one scenario task with zero jobs', newTasks)
    await context.evidence.json('tool-chain.json', {byName, cards: snapshot.cards.map((card) => card.testId)})
    return
  }

  if (config.variant === 'composer-draft') {
    const draft = `draft-${nonce}`
    await browser.evaluate(`document.querySelector('[data-testid="composer-input"]').focus()`, false)
    await browser.send('Input.insertText', {text: draft})
    const before = await browser.messageState()
    checks.require('composer-draft.typed', before?.draft === draft, draft, before?.draft ?? null)
    await sleep(400)
    checks.require('composer-draft.still-typed-before-reload', (await browser.messageState())?.draft === draft, draft, null)
    browser.setPhase('refresh-1')
    await browser.reload()
    await browser.waitFor(`document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai').historyLoaded === true`,
      'COMPOSER_DRAFT_HISTORY_NOT_LOADED')
    const after = await browser.messageState()
    checks.require('composer-draft.preserved-after-refresh', after?.draft === draft, draft, after?.draft ?? null)
    checks.require('composer-draft.not-a-message', after?.messages === before?.messages, before?.messages ?? null, after?.messages ?? null)
    checks.require('composer-draft.no-chat-open', browser.requestCounts().chat === 0, 0, browser.requestCounts().chat)
    const baseline = browser.requestCounts().chat
    await browser.click('[data-testid="send-btn"]')
    await waitSettled('COMPOSER_DRAFT_SEND_NOT_TERMINAL')
    checks.require('composer-draft.sent-exactly-once', browser.requestCounts().chat === baseline + 1, baseline + 1, browser.requestCounts().chat)
    return
  }

  if (config.variant === 'form-draft') {
    await browser.sendPrompt(`gf-seven-types multi-card=2 verification=${nonce}`)
    const formed = await browser.waitFor(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      if (!ai.activeForm?.toolCallId || ai.queuedForms.length !== 1) return null
      const field = (ai.activeForm.form?.fields ?? []).find((candidate) => candidate.type === 'text')
      return field ? {toolCallId: ai.activeForm.toolCallId, queued: ai.queuedForms[0].toolCallId, key: field.key} : null
    })()`, 'FORM_DRAFT_NOT_FORMED')
    const draft = `queue-draft-${nonce}`
    await browser.evaluate(`document.querySelector('[data-testid="gf-field-${formed.toolCallId}-${formed.key}"]').focus()`, false)
    await browser.send('Input.insertText', {text: draft})
    const baseline = browser.requestCounts()
    browser.setPhase('refresh-1')
    await browser.reload()
    await waitSettled('FORM_DRAFT_REFRESH_NOT_SETTLED', {allowPendingForm: true})
    const snapshot = await browser.snapshot()
    checks.require('form-draft.active-preserved', snapshot.activeForm?.toolCallId === formed.toolCallId,
      formed.toolCallId, snapshot.activeForm?.toolCallId ?? null)
    const restored = await browser.evaluate(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      return ai.activeForm?.values?.['${formed.key}'] ?? null
    })()`, false)
    checks.require('form-draft.value-preserved', restored === draft, draft, restored)
    checks.require('form-draft.queued-preserved', snapshot.queuedForms.some((entry) => entry.toolCallId === formed.queued),
      formed.queued, snapshot.queuedForms)
    checks.require('form-draft.no-auto-submit', browser.requestCounts().frontendToolResult === baseline.frontendToolResult
      && browser.requestCounts().confirm === baseline.confirm, baseline, browser.requestCounts())
    return
  }

  const prompt = config.variant === 'interleave' || config.variant === 'boundary'
    ? `turn-interleave multi-card=2${config.variant === 'interleave' ? ' reasoning=120' : ''} stall-frames=${config.variant === 'boundary' ? 600 : 250} verification=${nonce}`
    : config.variant === 'gf-seven' ? `gf-seven-types multi-card=2 stall-frames=250 verification=${nonce}`
      : config.variant === 'timeline' || config.variant === 'copy-scan' ? `timeline-nonprefix verification=${nonce}`
        : `turn-text verification=${nonce}`

  if (config.variant === 'boundary') {
    await browser.sendPrompt(prompt)
    const samples = []
    const conditions = [
      `(() => {const ai=document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai'); return ai.messages.some((m)=>m.parts.some((p)=>p.kind==='tool'))})()`,
      `(() => {const ai=document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai'); return ai.messages.some((m)=>m.parts.some((p)=>p.kind==='tool' && ['succeeded','failed','rejected','blocked'].includes(p.status)))})()`,
      `(() => {const ai=document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai'); return ai.loading===false && ai.messages.filter((m)=>m.role==='assistant').every((m)=>m.done===true)})()`
    ]
    for (const [index, condition] of conditions.entries()) {
      await browser.waitFor(condition, `CONTENT_BOUNDARY_${index + 1}_NOT_REACHED`)
      const before = shapeOf(await browser.snapshot())
      browser.setPhase(`boundary-${index + 1}`)
      await browser.reload({waitForHistory: false})
      await waitSettled('CONTENT_BOUNDARY_REFRESH_NOT_SETTLED')
      const after = await browser.snapshot()
      assertBase(`boundary-${index + 1}`, after)
      checks.require(`boundary-${index + 1}.message-count-monotonic`,
        after.messages.length <= (index === 0 ? before.userCount + 2 : before.userCount + before.assistantCount + 2),
        'no duplicated user/assistant messages after boundary refresh', {
          before: {messages: before.userCount + before.assistantCount, tools: before.toolCallIds},
          afterMessages: after.messages.length
        })
      samples.push({index, before, after: shapeOf(after)})
    }
    await context.evidence.json('content-boundary-refreshes.json', samples)
    const final = await browser.snapshot()
    assertBase('final', final)
    const finalShape = shapeOf(final)
    checks.require('final.text-present', finalShape.textParts.some((text) => text.trim() !== ''), 'non-empty assistant text', finalShape.textParts)
    checks.require('final.chat-not-repeated', browser.requestCounts().chat === 1, 1, browser.requestCounts().chat)
    return
  }

  await browser.sendPrompt(prompt)
  if (config.variant === 'timeline' || config.variant === 'copy-scan') {
    // timeline-nonprefix 的 C 段在表单提交后才到达；先驱动真实表单再等终态。
    const form = await browser.waitFor(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      if (!ai.activeForm?.toolCallId) return null
      const field = (ai.activeForm.form?.fields ?? []).find((candidate) => candidate.type === 'text')
      return field ? {toolCallId: ai.activeForm.toolCallId, key: field.key} : null
    })()`, 'TIMELINE_FORM_NOT_FORMED')
    await browser.evaluate(`document.querySelector('[data-testid="gf-field-${form.toolCallId}-${form.key}"]').focus()`, false)
    await browser.send('Input.insertText', {text: `content-${nonce}`})
    await browser.click(`[data-testid="gf-form-${form.toolCallId}"] [data-testid="gf-submit"]`)
  }
  await waitSettled('CONTENT_MATRIX_NOT_TERMINAL', {allowPendingForm: config.variant === 'gf-seven'})
  const snapshot = await browser.snapshot()
  context.sessionId = snapshot.sessionId
  context.runId = snapshot.activeRunId
  const shape = assertBase('terminal', snapshot)

  if (config.variant === 'interleave') {
    checks.require('interleave.two-distinct-tools', shape.toolCallIds.length === 2, 2, shape.toolCallIds)
    checks.require('interleave.trailing-text', shape.textParts.some((text) => text.trim() !== ''), 'non-empty closing text', shape.textParts)
    checks.require('interleave.cards', snapshot.cards.length >= 2, '>= 2 tool cards', snapshot.cards.map((card) => card.testId))
    const history = requiredHistory(await context.api.history(snapshot.sessionId))
    const historyText = history.messages.filter((message) => String(message.type ?? message.role ?? '').toUpperCase() === 'ASSISTANT')
      .map((message) => message.text ?? message.content ?? '').join('')
    const uiText = snapshot.messages.filter((message) => message.role === 'assistant').map((message) => message.content).join('')
    checks.require('interleave.history-text-equal', historyText === uiText, textEvidence(uiText), textEvidence(historyText))
    const run = await context.api.run(context.runId)
    checks.require('interleave.run-final-text-equal', run.status === 'AVAILABLE' && run.data.snapshot.finalText === uiText,
      textEvidence(uiText), run.status === 'AVAILABLE' ? textEvidence(run.data.snapshot.finalText) : run)
  }

  if (config.variant === 'gf-seven') {
    await browser.waitFor(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      return ai.activeForm?.toolCallId && ai.queuedForms.length === 1
    })()`, 'GF_SEVEN_ACTIVE_AND_QUEUED_NOT_FORMED')
    const formed = await browser.snapshot()
    checks.require('gf-seven.active-and-queued', formed.activeForm !== null && formed.queuedForms.length === 1,
      'one active and one queued form', {activeForm: formed.activeForm?.toolCallId ?? null, queuedForms: formed.queuedForms})
    checks.require('gf-seven.distinct-form-ids', formed.activeForm && formed.queuedForms[0]
      && formed.activeForm.toolCallId !== formed.queuedForms[0].toolCallId,
    'active and queued forms have distinct toolCallId', {active: formed.activeForm?.toolCallId ?? null, queued: formed.queuedForms[0]?.toolCallId ?? null})
    checks.require('gf-seven.seven-field-types', (formed.activeForm?.fields ?? []).length === 7,
      7, (formed.activeForm?.fields ?? []).map((field) => field.type))
  }

  if (config.variant === 'timeline' || config.variant === 'copy-scan') {
    const parts = snapshot.messages.filter((message) => message.role === 'assistant').flatMap((message) => message.parts)
    const kinds = parts.map((part) => part.kind === 'tool' ? `tool:${part.name}` : part.kind)
    checks.require('timeline.part-order', canonicalJson(kinds) === canonicalJson(['text', 'tool:list_config_defs', 'text', 'tool:generative_form', 'text']),
      ['text', 'tool:list_config_defs', 'text', 'tool:generative_form', 'text'], kinds)
    checks.require('timeline.three-non-prefix-texts',
      shape.textParts.length === 3 && shape.textParts.every((text, index) => shape.textParts.slice(index + 1)
        .every((later) => !text.startsWith(later) && !later.startsWith(text))),
      'three non-prefix text bodies', shape.textParts.map((text) => textEvidence(text)))
  }

  if (config.variant === 'copy-scan') {
    const forbidden = ['宿主', '影响面暂不可估', '创建≠执行', '请填写以下信息']
    const visibleText = [snapshot.messages.filter((message) => message.role === 'assistant').map((message) => message.content).join('\n'),
      ...snapshot.cards.map((card) => card.text ?? '')].join('\n')
    const hits = forbidden.filter((phrase) => visibleText.includes(phrase))
    checks.require('copy.no-technical-phrases', hits.length === 0, 'no forbidden technical copy in visible assistant text/cards', hits)
    checks.require('copy.non-empty-card-text', snapshot.cards.some((card) => (card.text ?? '').trim() !== ''),
      'at least one visible card carries user-facing text', snapshot.cards.map((card) => card.testId))
    return
  }

  if (config.variant === 'repeat-refresh') {
    const baseline = browser.requestCounts().chat
    const initialSignature = factsOf(snapshot)
    const initialSeq = seqFacts(snapshot)
    const seqDrift = []
    for (const phase of ['refresh-1', 'refresh-2', 'refresh-3']) {
      browser.setPhase(phase)
      await browser.reload()
      await waitSettled(`${phase.toUpperCase()}_NOT_SETTLED`)
      const refreshed = await browser.snapshot()
      assertBase(phase, refreshed)
      checks.require(`${phase}.content-stable`, factsOf(refreshed) === initialSignature,
        initialSignature, factsOf(refreshed))
      if (seqFacts(refreshed) !== initialSeq) seqDrift.push(phase)
    }
    checks.require('repeat-refresh.no-new-chat', browser.requestCounts().chat === baseline, baseline, browser.requestCounts().chat)
    await context.evidence.json('content-matrix-seq.json', {variant: config.variant, initialSeq, seqDrift})
    return
  }

  // C-131A/B/C 的刷新稳定性：内容与卡片签名、run/history 正文一致。
  const initialSignature = factsOf(snapshot)
  const initialSeq = seqFacts(snapshot)
  browser.setPhase('refresh-1')
  await browser.reload()
  await waitSettled('CONTENT_MATRIX_REFRESH_NOT_SETTLED', {allowPendingForm: config.variant === 'gf-seven'})
  const refreshed = await browser.snapshot()
  assertBase('refresh-1', refreshed)
  checks.require('refresh-1.content-stable', factsOf(refreshed) === initialSignature,
    initialSignature, factsOf(refreshed))
  await context.evidence.json('content-matrix.json', {
    variant: config.variant,
    initial: shape,
    refreshed: shapeOf(refreshed),
    seqStable: seqFacts(refreshed) === initialSeq,
    initialSeq,
    refreshedSeq: seqFacts(refreshed)
  })
}

async function runCorrectionPrefixScenario(context) {
  const {browser, checks, config} = context
  if (!PREFIX_EXPECTATIONS.has(config.variant)) {
    throw new Error(`RECOVERY_VARIANT must select exactly one of ${[...PREFIX_EXPECTATIONS.keys()].join(', ')}`)
  }
  await assertStubDirectoryEmpty(config)
  context.businessAfterFixture = await businessSnapshot(context.api, relevantTaskIds(context))
  browser.setPhase('correction-prefix')
  const prompt = config.variant === 'other-run-control'
    ? `turn-text verification=${randomUUID()}`
    : `创建导出任务 correction=${config.variant} verification=${randomUUID()}`
  await browser.sendPrompt(prompt)
  if (config.variant === 'park-resume') {
    const pending = await browser.waitFor(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      return ai.activeForm?.toolCallId ? {toolCallId: ai.activeForm.toolCallId} : null
    })()`, 'PREFIX_PARK_FORM_NOT_FORMED')
    await browser.waitFor(`document.querySelector('[data-testid="gf-field-${pending.toolCallId}-keyword"]') !== null`,
      'PREFIX_PARK_FIELD_NOT_READY')
    await browser.evaluate(`document.querySelector('[data-testid="gf-field-${pending.toolCallId}-keyword"]').focus()`, false)
    await browser.send('Input.insertText', {text: `prefix-${randomUUID()}`})
    await browser.click(`[data-testid="gf-form-${pending.toolCallId}"] [data-testid="gf-submit"]`)
  }
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.loading === false && ai.messages.some((message) => message.role === 'assistant' && message.done === true)
  })()`, 'PREFIX_RUN_NOT_TERMINAL')
  const snapshot = await browser.snapshot()
  context.sessionId = snapshot.sessionId
  context.runId = snapshot.activeRunId ?? [...snapshot.messages].reverse().find((message) => message.runId)?.runId ?? null
  const requests = await readUpstreamRequests(config)
  const requestEvidence = assertPrefixUpstream(context, requests)
  await assertPrefixFinalProjection(context, requestEvidence)
  const business = await businessSnapshot(context.api, relevantTaskIds(context))
  checks.require('prefix.business-no-side-effects', business.fingerprint === context.businessAfterFixture.fingerprint,
    context.businessAfterFixture.fingerprint, business.fingerprint)
  await savePhase(context, 'correction-prefix', snapshot, await context.api.history(context.sessionId), business)
}

async function runCorrectionPrefixPreflight(context) {
  const {config, checks, browser} = context
  if (!config.stubDumpDir) throw new Error('STUB_DUMP_DIR is required for correction-prefix-preflight')
  const existing = (await readdir(config.stubDumpDir)).filter((name) => name.startsWith('req-')).sort()
  if (existing.length === 0) throw new ScenarioNotRun('NONEMPTY_STUB_FIXTURE_REQUIRED')
  let rejected = null
  try {
    await assertStubDirectoryEmpty(config)
  } catch (error) {
    rejected = error
  }
  checks.require('preflight.nonempty-rejected-before-actions', rejected?.code === 'STUB_DUMP_DIR_NOT_EMPTY',
    'existing req-* evidence is rejected before sendPrompt or business actions', {
      code: rejected?.code ?? null,
      existing
    })
  checks.require('preflight.zero-chat-and-business-actions', browser.requestCounts().chat === 0 && context.businessAfterFixture === null,
    'no scenario chat or business snapshot was executed', {
      chat: browser.requestCounts().chat,
      businessAfterFixture: context.businessAfterFixture
    })
  const after = (await readdir(config.stubDumpDir)).filter((name) => name.startsWith('req-')).sort()
  checks.require('preflight.no-new-upstream-evidence', canonicalJson(after) === canonicalJson(existing),
    'the rejected preflight does not create upstream requests', {before: existing, after})
  await context.evidence.json('stub-dir-preflight.json', {status: 'REJECTED', code: rejected.code, existing, chat: 0})
}

async function runRecoveryScenario(context) {
  const {config, browser, checks} = context
  browser.setPhase('initial')
  context.businessAfterFixture = await businessSnapshot(context.api, relevantTaskIds(context))
  await browser.sendPrompt(promptFor(context))
  await browser.waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.loading === false && ai.historyLoaded && ai.messages.some((message) => message.role === 'assistant' && message.done === true)
  })()`, 'INITIAL_TEXT_RUN_NOT_COMPLETED')
  const completed = await browser.snapshot()
  context.sessionId = completed.sessionId
  context.runId = completed.activeRunId
  const initialHistoryEvidence = await context.api.history(context.sessionId)
  const initialHistory = requiredHistory(initialHistoryEvidence)
  const initialSignature = canonicalJson(messageContentSignature(completed))
  const initialHistorySignature = canonicalJson(historySummary(initialHistory))
  const initialBusiness = await businessSnapshot(context.api, relevantTaskIds(context))
  const initialRequests = browser.requestCounts()
  await savePhase(context, 'initial', completed, initialHistoryEvidence, initialBusiness)

  if (config.scenario === 'recovery-success') {
    browser.setPhase('automatic-success-attempt')
    await browser.setHistoryFault('once')
    await browser.reload({waitForHistory: false})
    const network = await browser.waitForHistoryPhase('automatic-success-attempt', {exactFaultCount: 1, minimumSuccessCount: 1})
    checks.require('automatic-success.exactly-one-503-then-real-200', network.faultCount === 1 && network.successCount >= 1
      && network.first503Sequence !== null && network.firstReal200Sequence > network.first503Sequence
      && network.statuses.every((status) => status === 503 || status === 200),
    'exactly one history 503 followed by a real continued history HTTP 200 with loadingFinished', network)
    await browser.waitFor(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      return ai.historyLoaded && !ai.loading && ai.historyError == null && ai.currentError == null
    })()`, 'AUTOMATIC_RECOVERY_NOT_COMPLETED')
    const settledNetwork = browser.historyPhase('automatic-success-attempt')
    checks.require('automatic-success.no-pending-after-recovery', settledNetwork.pendingCount === 0
      && settledNetwork.faultCount === 1 && settledNetwork.successCount >= 1,
    'automatic recovery settled with no pending history request and exactly one 503', settledNetwork)
    await waitRecoveryMessageProjection(browser, completed.messages.length)
    const recovered = await browser.snapshot()
    assertNoRecoveryNotices(context, 'automatic-success-attempt', recovered)
    checks.require('automatic-success.content-stable', canonicalJson(messageContentSignature(recovered)) === initialSignature,
      initialSignature, canonicalJson(messageContentSignature(recovered)))
    const historyEvidence = await context.api.history(context.sessionId)
    const history = requiredHistory(historyEvidence)
    checks.require('automatic-success.history-stable', canonicalJson(historySummary(history)) === initialHistorySignature,
      initialHistorySignature, canonicalJson(historySummary(history)))
    await savePhase(context, 'automatic-success-attempt', recovered, historyEvidence,
      await businessSnapshot(context.api, relevantTaskIds(context)))
    await context.evidence.json('history-network-automatic-success-attempt.json', settledNetwork)
    await browser.setHistoryFault('off')
  } else {
    browser.setPhase('persistent-failure-1')
    await browser.setHistoryFault('always')
    await browser.reload({waitForHistory: false})
    const firstFailure = await waitAutomaticFinalFailure(context, 'persistent-failure-1')
    const manualGateHistory = await context.api.history(context.sessionId)
    await assertManualRecoveryGate(context, 'persistent-failure-gate', manualGateHistory, initialBusiness)
    await assertFinalRecoveryFailure(context, 'persistent-failure-1', initialSignature, initialBusiness, firstFailure)

    browser.setPhase('persistent-failure-refresh')
    await browser.reload({waitForHistory: false})
    const refreshFailure = await waitAutomaticFinalFailure(context, 'persistent-failure-refresh')
    await assertFinalRecoveryFailure(context, 'persistent-failure-refresh', initialSignature, initialBusiness, refreshFailure)

    browser.setPhase('persistent-failure-retry')
    await browser.click('[data-testid="retry-history"], [data-testid="retry-recovery-gap"], [data-testid="continue-round"], [data-testid^="error-card-action-"]')
    const retryFailure = await waitAutomaticFinalFailure(context, 'persistent-failure-retry')
    await assertFinalRecoveryFailure(context, 'persistent-failure-retry', initialSignature, initialBusiness, retryFailure)

    browser.setPhase('manual-retry-success')
    await browser.setHistoryFault('off')
    await browser.click('[data-testid="retry-history"], [data-testid="retry-recovery-gap"], [data-testid="continue-round"], [data-testid^="error-card-action-"]')
    const manualNetwork = await browser.waitForHistoryPhase('manual-retry-success', {exactFaultCount: 0, minimumSuccessCount: 1})
    const interceptionOff = browser.interceptionEvidence()
    checks.require('manual-retry.interception-disabled-state', interceptionOff.enabled === false
      && interceptionOff.transitions.at(-1)?.enabled === false
      && interceptionOff.transitions.at(-1)?.proof === 'FETCH_DISABLE_COMPLETED',
    'Fetch.disable completed and independent interception state is off', interceptionOff)
    checks.require('manual-retry.real-unintercepted-history-success', manualNetwork.successCount >= 1
      && manualNetwork.uninterceptedSuccessCount >= 1 && manualNetwork.pendingCount === 0
      && manualNetwork.statuses.every((status) => status === 200),
    'OBSERVED history request issued after interception was disabled completed with HTTP 200 and loadingFinished', manualNetwork)
    await browser.waitFor(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      return ai.historyLoaded && !ai.loading && ai.historyError == null && ai.currentError == null
        && ![...document.querySelectorAll(${JSON.stringify(recoveryRetrySelector)})]
          .some((element) => element.offsetParent !== null || element.getClientRects().length > 0)
    })()`, 'MANUAL_RETRY_NOT_COMPLETED')
    const manualTerminal = await recoveryPhaseEvidence(context)
    await assertTerminalRecoveryComplete(context, 'manual-retry-success', manualTerminal.historyEvidence, manualTerminal.business)
    await context.evidence.json('history-network-manual-retry-success.json', {
      ...browser.historyPhase('manual-retry-success'),
      interception: interceptionOff
    })
  }

  for (const phase of ['refresh-1', 'refresh-2']) {
    browser.setPhase(phase)
    await browser.reload()
    await waitRecoveryMessageProjection(browser, completed.messages.length)
    const snapshot = await browser.snapshot()
    assertNoRecoveryNotices(context, phase, snapshot)
    checks.require(`${phase}.content-stable`, canonicalJson(messageContentSignature(snapshot)) === initialSignature,
      initialSignature, canonicalJson(messageContentSignature(snapshot)))
    const historyEvidence = await context.api.history(context.sessionId)
    const history = requiredHistory(historyEvidence)
    checks.require(`${phase}.history-stable`, canonicalJson(historySummary(history)) === initialHistorySignature,
      initialHistorySignature, canonicalJson(historySummary(history)))
    const business = await businessSnapshot(context.api, relevantTaskIds(context))
    checks.require(`${phase}.business-no-side-effects`, business.fingerprint === initialBusiness.fingerprint,
      initialBusiness.fingerprint, business.fingerprint)
    checks.require(`${phase}.no-repeat-chat`, canonicalJson(browser.requestCounts()) === canonicalJson(initialRequests),
      initialRequests, browser.requestCounts())
    await savePhase(context, phase, snapshot, historyEvidence, business)
  }
}

async function cleanupTask(context, taskId) {
  const result = {taskId, status: 'INCOMPLETE', expectedJobIds: [], deleteStatus: null,
    deleteBusinessCode: null, postStatus: null, forcedRetry: false, errors: []}
  const current = await context.api.task(taskId)
  if (current.status === 404) {
    result.status = 'COMPLETE'
    result.postStatus = 404
    return result
  }
  if (current.status !== 200) {
    result.errors.push({errorName: 'HTTP_ERROR', code: 'PRE_DELETE_TASK_GET_FAILED', httpStatus: current.status, businessCode: businessCode(current.body)})
    return result
  }
  const jobs = await context.api.jobs(taskId)
  result.expectedJobIds = jobs.map((job) => Number(job.id)).filter(Number.isSafeInteger).sort((left, right) => left - right)
  const query = new URLSearchParams()
  if (result.expectedJobIds.length === 0) query.append('expectedJobIds', '')
  else for (const id of result.expectedJobIds) query.append('expectedJobIds', String(id))
  const deleted = await context.api.request('cleanup.task.delete', `/api/tasks/${taskId}?${query}`, {method: 'DELETE'})
  result.deleteStatus = deleted.status
  result.deleteBusinessCode = businessCode(deleted.body)
  const post = await context.api.task(taskId)
  result.postStatus = post.status
  result.status = [200, 204, 404].includes(deleted.status) && post.status === 404 ? 'COMPLETE' : 'INCOMPLETE'
  if (result.status !== 'COMPLETE') {
    result.errors.push({errorName: 'CLEANUP_INCOMPLETE', code: 'TASK_DELETE_OR_POST_STATE_INVALID',
      httpStatus: deleted.status, businessCode: result.deleteBusinessCode})
  }
  return result
}

async function cleanupCancel(context) {
  const result = {status: 'NOT_REQUIRED', runId: context.runId, httpStatus: null,
    businessCode: null, finalHttpStatus: null, finalStatus: null, attempts: 0, errors: []}
  if (!context.runId) return result
  result.status = 'INCOMPLETE'
  let accepted = false
  try {
    const response = await context.api.request('cleanup.cancel', `/api/ai/cancel/${encodeURIComponent(context.runId)}`, {method: 'POST'})
    result.httpStatus = response.status
    result.businessCode = businessCode(response.body)
    // 404 UNKNOWN_RUN 表示该 run 已不存在（会话清理/终态后已删除），等同于“无活跃 run 残留”，
    // 仍以公开读接口的 404 作为证据，不把“运行已消失”当成清理失败或产品取消成功。
    const runAbsent = response.status === 404 && result.businessCode === 'UNKNOWN_RUN'
    accepted = response.status === 200 || response.status === 409 || runAbsent
    if (runAbsent) result.finalStatus = 'ABSENT'
    if (!accepted) result.errors.push({errorName: 'HTTP_ERROR', code: 'CANCEL_NOT_ACCEPTED', httpStatus: response.status, businessCode: result.businessCode})
  } catch (error) {
    result.errors.push(safeError(error, 'CANCEL_REQUEST_FAILED'))
  }
  const deadline = Date.now() + context.config.timeoutMs
  while (Date.now() < deadline) {
    result.attempts += 1
    try {
      const response = await context.api.request('cleanup.run', `/api/ai/runs/${encodeURIComponent(context.runId)}`)
      result.finalHttpStatus = response.status
      const payload = response.body?.data ?? response.body
      if (response.status === 404 || payload?.exists === false) {
        result.finalStatus = 'ABSENT'
        result.status = accepted ? 'COMPLETE' : 'INCOMPLETE'
        return result
      }
      const status = payload?.snapshot?.status ?? response.body?.snapshot?.status
      result.finalStatus = safeToken(status)
      if (['DONE', 'FAILED', 'CANCELLED'].includes(status)) {
        result.status = accepted ? 'COMPLETE' : 'INCOMPLETE'
        return result
      }
    } catch (error) {
      result.errors.push(safeError(error, 'CANCEL_TERMINAL_READ_FAILED'))
    }
    await sleep(context.config.pollMs)
  }
  result.errors.push({errorName: 'TIMEOUT', code: 'CANCEL_TERMINAL_NOT_PROVEN', httpStatus: result.finalHttpStatus, businessCode: null})
  return result
}

async function cleanup(context) {
  const result = {status: 'COMPLETE', sessionDeletes: [], historyAfter: [], cancel: null, tasks: [], failures: []}
  if (context.config.scenario === 'tool-skipped' && context.businessAfterFixture) {
    try {
      const current = await businessSnapshot(context.api, relevantTaskIds(context))
      for (const task of current.allTasks) {
        if (!context.businessAfterFixture.allTasks.some((before) => before.id === task.id)) context.createdTaskIds.add(task.id)
      }
    } catch (error) {
      result.failures.push(safeError(error, 'BUSINESS_CLEANUP_BASELINE_FAILED'))
    }
  }
  result.cancel = await cleanupCancel(context)
  if (result.cancel.status !== 'COMPLETE' && result.cancel.status !== 'NOT_REQUIRED') {
    result.failures.push({errorName: 'CLEANUP_INCOMPLETE', code: 'CANCEL_CLEANUP_INCOMPLETE', httpStatus: result.cancel.httpStatus, businessCode: result.cancel.businessCode})
  }
  const sessionIds = [...new Set([context.sessionId, ...context.additionalSessionIds].filter((id) => typeof id === 'string' && id !== ''))]
  for (const sessionId of sessionIds) {
    for (let attempt = 1; attempt <= 2; attempt += 1) {
      try {
        const response = await context.api.request(`cleanup.session.delete.${attempt}`, `/api/ai/session/${encodeURIComponent(sessionId)}`, {method: 'DELETE'})
        const body = response.body?.data ?? response.body
        result.sessionDeletes.push({sessionId, attempt, httpStatus: response.status, businessCode: businessCode(response.body), complete: body?.complete === true,
          deferredCount: Array.isArray(body?.deferred) ? body.deferred.length : null,
          errorCount: Array.isArray(body?.errors) ? body.errors.length : null})
        if (response.status !== 200 || body?.complete !== true || body?.deferred?.length || body?.errors?.length) {
          result.failures.push({errorName: 'CLEANUP_INCOMPLETE', code: 'SESSION_DELETE_INCOMPLETE', httpStatus: response.status, businessCode: businessCode(response.body)})
        }
      } catch (error) {
        result.failures.push(safeError(error, 'SESSION_DELETE_FAILED'))
      }
    }
    try {
      const history = await context.api.request('cleanup.history', `/api/ai/history/${encodeURIComponent(sessionId)}?latest=1`)
      const messages = history.body?.data?.messages ?? history.body?.messages
      result.historyAfter.push({sessionId, httpStatus: history.status, businessCode: businessCode(history.body), messageCount: Array.isArray(messages) ? messages.length : null})
      if (history.status !== 410 && !(history.status === 200 && Array.isArray(messages) && messages.length === 0)) {
        result.failures.push({errorName: 'CLEANUP_INCOMPLETE', code: 'HISTORY_REMAINS_AFTER_DELETE', httpStatus: history.status, businessCode: businessCode(history.body)})
      }
    } catch (error) {
      result.failures.push(safeError(error, 'POST_CLEANUP_HISTORY_FAILED'))
    }
  }
  for (const taskId of [...context.createdTaskIds].filter((id) => Number.isSafeInteger(id) && id > 0)) {
    try {
      const taskResult = await cleanupTask(context, taskId)
      result.tasks.push(taskResult)
      if (taskResult.status !== 'COMPLETE') result.failures.push({errorName: 'CLEANUP_INCOMPLETE', code: 'TASK_CLEANUP_INCOMPLETE', httpStatus: taskResult.deleteStatus, businessCode: taskResult.deleteBusinessCode})
    } catch (error) {
      result.tasks.push({taskId, status: 'INCOMPLETE', errors: [safeError(error, 'TASK_CLEANUP_FAILED')]})
      result.failures.push(safeError(error, 'TASK_CLEANUP_FAILED'))
    }
  }
  if (result.failures.length > 0) result.status = 'INCOMPLETE'
  await context.evidence.json('cleanup.json', result)
  return result
}

const config = loadConfig()
const evidenceDirectory = await prepareEvidenceDirectory(config)
const evidence = new Evidence(evidenceDirectory)
const checks = new Checks()
const context = {
  config,
  evidence,
  checks,
  api: new ApiClient(config),
  browser: new CdpPage(config),
  sessionId: null,
  runId: null,
  toolCallId: null,
  fixtureTaskId: null,
  createdTaskIds: new Set(),
  additionalSessionIds: new Set(),
  businessAfterFixture: null,
  auxiliaryErrors: []
}
let fatal = null
let notRun = null
const startedAt = new Date().toISOString()

try {
  await evidence.json('input.json', {
    scenario: config.scenario,
    locale: config.locale,
    variant: config.variant || null,
    historyOrder: config.historyOrder || null,
    pendingFixture: config.pendingFixture,
    contentVariant: config.contentVariant ?? null,
    appUrl: config.appUrl,
    baseUrl: config.baseUrl,
    tenantId: config.tenantId,
    userId: config.userId,
    isolationAsserted: true,
    writesAllowed: true,
    executorIdentity: config.executorIdentity,
    codeHead: codeHead(),
    startedAt,
    faultBoundary: config.scenario === 'recovery-context-usage'
      ? 'CDP history/context HTTP 503 injection, response-stage delay of real context responses, and existing public loadContext action; no store writes, SSE frames, response bodies or business results are fabricated'
      : config.scenario.startsWith('recovery-')
        ? 'scenario-scoped CDP HTTP faults or response-stage delays on public history, runs/current or context plus public API reads; no application SSE, store frame, history body or business result is fabricated'
        : 'real UI prompt through the configured upstream; public history and business APIs; no application SSE injection'
  })
  await context.browser.open()
  await prepareFixture(context)
  if (config.scenario === 'tool-skipped') context.createdTaskIds.add(context.fixtureTaskId)
  if (toolCases.has(config.scenario)) await runToolScenario(context)
  else if (config.scenario === 'tool-skipped') await runSkippedScenario(context)
  else if (config.scenario === 'ordinary-skipped') await runOrdinarySkippedScenario(context)
  else if (config.scenario === 'form-mixed-copy') await runFormScenario(context)
  else if (config.scenario === 'recovery-hitl-gate') await runHitlRecoveryGate(context)
  else if (config.scenario === 'recovery-stream-refresh') await runStreamRecoveryRefresh(context)
  else if (config.scenario === 'recovery-mixed-messages') await runMixedMessagesRecovery(context)
  else if (config.scenario === 'recovery-context-usage') await runContextUsageRecovery(context)
  else if (config.scenario === 'recovery-stream-history-race') await runStreamHistoryRace(context)
  else if (config.scenario === 'recovery-pending-integrity') await runPendingIntegrity(context)
  else if (config.scenario === 'recovery-cross-run-order') await runCrossRunOrder(context)
  else if (config.scenario === 'recovery-form-queue') await runFormQueueRecovery(context)
  else if (config.scenario === 'correction-prefix') await runCorrectionPrefixScenario(context)
  else if (config.scenario === 'content-matrix') await runContentMatrix(context)
  else if (config.scenario === 'recovery-timing-proxy') await runTimingProxyScenario(context)
  else if (config.scenario === 'sheet-preview') await runSheetPreviewScenario(context)
  else if (config.scenario === 'correction-prefix-preflight') await runCorrectionPrefixPreflight(context)
  else if (config.scenario === 'recovery-history-generation') await runHistoryGenerationRace(context)
  else if (config.scenario === 'recovery-stopped') await runStoppedRecovery(context)
  else await runRecoveryScenario(context)
} catch (error) {
  if (error instanceof ScenarioNotRun) notRun = {code: safeToken(error.code, 'SCENARIO_NOT_RUN')}
  else fatal = safeError(error)
  try {
    const failedSnapshot = await context.browser.snapshot()
    await evidence.json('dom-fatal.json', snapshotEvidence(failedSnapshot))
  } catch (snapshotError) {
    context.auxiliaryErrors.push(safeError(snapshotError, 'FATAL_DOM_CAPTURE_FAILED'))
  }
} finally {
  if (context.fixtureTaskId) context.createdTaskIds.add(context.fixtureTaskId)
  try {
    const snapshot = await context.browser.snapshot()
    context.sessionId ??= snapshot.sessionId
    context.runId ??= snapshot.activeRunId ?? [...snapshot.messages].reverse().find((message) => message.runId)?.runId ?? null
  } catch (error) {
    context.auxiliaryErrors.push(safeError(error, 'FINAL_IDENTITY_CAPTURE_FAILED'))
  }
}

let browserClose
try {
  browserClose = await context.browser.close()
} catch (error) {
  browserClose = {status: 'INCOMPLETE', targetId: context.browser.targetId, errors: [safeError(error, 'BROWSER_CLOSE_FAILED')]}
}
await evidence.json('page-close.json', browserClose)
const cleanupResult = await cleanup(context).catch((error) => ({status: 'INCOMPLETE', failures: [safeError(error, 'CLEANUP_FAILED')]}))
checks.add('cdp.no-async-errors', context.browser.cdpErrors.length === 0, [], context.browser.cdpErrors)
checks.add('cdp.no-auxiliary-errors', context.auxiliaryErrors.length === 0, [], context.auxiliaryErrors)
checks.add('evidence.no-hard-limit-exceeded', context.browser.evidenceLimitExceeded === false,
  'history/context Requests, cdpErrors and networkRequests stay within their independent hard limits', context.browser.evidenceLimitExceeded)
await evidence.json('network-faults.json', {
  history: {
    count: context.browser.historyRequests.length,
    faultCount: context.browser.historyRequests.filter((record) => record.injectedStatus === 503).length,
    successCount: context.browser.historyRequests.filter((record) => ['CONTINUE_UNCHANGED', 'OBSERVED'].includes(record.action)
      && record.responseStatus === 200 && record.finished && record.errorName === null).length,
    limitExceeded: context.browser.evidenceLimitExceeded,
    tail: context.browser.historyRequests.slice(-EVIDENCE_TAIL_LIMIT)
  },
  context: {
    count: context.browser.contextRequests.length,
    faultCount: context.browser.contextRequests.filter((record) => record.injectedStatus === 503).length,
    successCount: context.browser.contextRequests.filter((record) => ['CONTINUE_UNCHANGED', 'OBSERVED'].includes(record.action)
      && record.responseStatus === 200 && record.finished && record.errorName === null).length,
    limitExceeded: context.browser.evidenceLimitExceeded,
    tail: context.browser.contextRequests.slice(-EVIDENCE_TAIL_LIMIT)
  },
  runCurrent: {
    count: context.browser.runCurrentRequests.length,
    successCount: context.browser.runCurrentRequests.filter((record) => ['CONTINUE_UNCHANGED', 'OBSERVED'].includes(record.action)
      && record.responseStatus === 200 && record.finished && record.errorName === null).length,
    delayedCount: context.browser.runCurrentRequests.filter((record) => record.delayedResponse === true).length,
    delayedReleasedCount: context.browser.runCurrentRequests.filter((record) => record.delayedResponseReleased === true).length,
    limitExceeded: context.browser.evidenceLimitExceeded,
    tail: context.browser.runCurrentRequests.slice(-EVIDENCE_TAIL_LIMIT)
  },
  publicApiRequestCounts: context.browser.requestCounts(),
  interception: context.browser.interceptionEvidence(),
  cdpErrors: {count: context.browser.cdpErrors.length, tail: context.browser.cdpErrors.slice(-EVIDENCE_TAIL_LIMIT)},
  auxiliaryErrors: {count: context.auxiliaryErrors.length, tail: context.auxiliaryErrors.slice(-EVIDENCE_TAIL_LIMIT)}
})
const status = fatal || cleanupResult.status !== 'COMPLETE' || browserClose.status !== 'COMPLETE'
  || checks.failures.length > 0 ? 'FAIL' : notRun ? 'NOT_RUN' : 'PASS'
const summary = {
  schemaVersion: 1,
  scenario: config.scenario,
  locale: config.locale,
  variant: config.variant || null,
  historyOrder: config.historyOrder || null,
  pendingFixture: config.pendingFixture,
  status,
  statusScope: toolCases.has(config.scenario) || ['tool-skipped', 'ordinary-skipped', 'form-mixed-copy', 'recovery-hitl-gate', 'recovery-stream-refresh', 'recovery-mixed-messages', 'recovery-context-usage', 'recovery-stream-history-race', 'recovery-pending-integrity', 'recovery-cross-run-order', 'recovery-form-queue', 'correction-prefix', 'correction-prefix-preflight', 'recovery-history-generation', 'recovery-stopped', 'content-matrix', 'recovery-timing-proxy', 'sheet-preview'].includes(config.scenario)
    ? 'REAL_BROWSER_DOM_STORE_PUBLIC_HISTORY_CONTEXT_CLIPBOARD_AND_PUBLIC_BUSINESS_API; configured upstream evidence only; REAL_PROVIDER_VERDICT=NOT_RUN; DIRECT_REDIS_VERDICT=NOT_RUN; DIRECT_DATABASE_VERDICT=NOT_RUN'
    : 'REAL_BROWSER_DOM_AND_PUBLIC_API_WITH_CDP_HISTORY_HTTP_FAULT; no application SSE fabrication; BACKEND_INTERNAL_FAILURE_VERDICT=NOT_RUN; DIRECT_REDIS_VERDICT=NOT_RUN; DIRECT_DATABASE_VERDICT=NOT_RUN',
  startedAt,
  finishedAt: new Date().toISOString(),
  codeHead: codeHead(),
  executorIdentity: config.executorIdentity,
  evidenceDirectory,
  sessionId: context.sessionId,
  runId: context.runId,
  toolCallId: context.toolCallId,
  fixtureTaskId: context.fixtureTaskId,
  fatal,
  notRun,
  assertions: checks.items,
  failedAssertions: checks.failures,
  networkFaultCount: [...context.browser.historyRequests, ...context.browser.contextRequests, ...context.browser.runCurrentRequests].filter((record) => record.injectedStatus === 503).length,
  historyHttpStatuses: [...new Set(context.browser.historyRequests.map((record) => record.responseStatus))],
  contextHttpStatuses: [...new Set(context.browser.contextRequests.map((record) => record.responseStatus))],
  runCurrentHttpStatuses: [...new Set(context.browser.runCurrentRequests.map((record) => record.responseStatus))],
  delayedResponseCount: [...context.browser.historyRequests, ...context.browser.contextRequests, ...context.browser.runCurrentRequests]
    .filter((record) => record.delayedResponse === true).length,
  delayedResponseReleasedCount: [...context.browser.historyRequests, ...context.browser.contextRequests, ...context.browser.runCurrentRequests]
    .filter((record) => record.delayedResponseReleased === true).length,
  evidenceLimitExceeded: context.browser.evidenceLimitExceeded,
  interception: context.browser.interceptionEvidence(),
  browserClose,
  cleanup: cleanupResult,
  realProvider: 'NOT_RUN',
  directRedis: 'NOT_RUN',
  directDatabase: 'NOT_RUN'
}
await evidence.json('summary.json', summary)
console.log(JSON.stringify({scenario: config.scenario, locale: config.locale, status, evidenceDirectory,
  failedAssertions: checks.failures.length, browserClose: browserClose.status, cleanup: cleanupResult.status}, null, 2))
if (status !== 'PASS') process.exitCode = status === 'NOT_RUN' ? 2 : 1
