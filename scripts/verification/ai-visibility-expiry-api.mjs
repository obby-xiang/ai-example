#!/usr/bin/env node

import {execFileSync} from 'node:child_process'
import {createHash, randomUUID} from 'node:crypto'
import {lstat, mkdir, open, realpath, stat, writeFile} from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {setTimeout as sleep} from 'node:timers/promises'

const repoRoot = fileURLToPath(new URL('../..', import.meta.url))
const scenarios = new Set(['C-149', 'C-150'])
const localHosts = new Set(['localhost', '127.0.0.1', '::1', '[::1]'])
const terminalRuns = new Set(['DONE', 'FAILED', 'CANCELLED'])
const maxLogBytes = 1024 * 1024
const maxSseFrames = 256
const expiryClockSkewMs = 2_000

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
  return createHash('sha256').update(typeof value === 'string' ? value : canonicalJson(value)).digest('hex')
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

function outsidePath(name) {
  const raw = process.env[name]?.trim() ?? ''
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

function httpUrl(name, required = true) {
  const raw = process.env[name]?.trim() ?? ''
  if (!raw && !required) return null
  let parsed
  try {
    parsed = new URL(raw)
  } catch {
    throw new Error(`${name} must be an absolute HTTP(S) URL`)
  }
  if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new Error(`${name} must not contain credentials, query or fragment`)
  }
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
  const baseUrlB = httpUrl('BASE_URL_B', scenario === 'C-150')
  if (scenario === 'C-150' && baseUrlB === httpUrl('BASE_URL')) throw new Error('BASE_URL_B must address a different instance')
  return {
    scenario,
    baseUrl: httpUrl('BASE_URL'),
    baseUrlB,
    tenantId: process.env.TENANT_ID.trim(),
    userId: process.env.USER_ID.trim(),
    executorIdentity: process.env.EXECUTOR_IDENTITY.trim(),
    outDir: outsidePath('OUT_DIR'),
    backendLogFile: outsidePath('BACKEND_LOG_FILE'),
    backendLogFileB: scenario === 'C-150' ? outsidePath('BACKEND_LOG_FILE_B') : null,
    timeoutMs: integerEnv('TIMEOUT_MS', 240_000, 30_000, 900_000),
    instanceTimeoutMs: integerEnv('INSTANCE_TIMEOUT_MS', 180_000, 10_000, 600_000),
    pollMs: integerEnv('POLL_MS', 500, 100, 5_000)
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
  const directory = path.join(target, 'ai-visibility-expiry', config.scenario,
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

  async capture(parked = []) {
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
    const oneShotToolPattern = /\bINFO\b.*frontend receipt expired after park; entry converged/
    const periodicToolPattern = /\bDEBUG\b.*park expiry entry converged by this instance/
    const lines = text.split(/\r?\n/)
    const identityMatches = parked.slice(0, 3).map((entry) => {
      let oneShotInfoMatchCount = 0
      let periodicDebugMatchCount = 0
      for (const line of lines) {
        if (!line.includes(entry.runId) || !line.includes(entry.request.toolCallId)) continue
        if (oneShotToolPattern.test(line)) oneShotInfoMatchCount += 1
        if (periodicToolPattern.test(line)) periodicDebugMatchCount += 1
      }
      return {
        sessionId: entry.sessionId,
        runId: entry.runId,
        toolCallId: entry.request.toolCallId,
        matchCount: oneShotInfoMatchCount + periodicDebugMatchCount,
        oneShotInfoMatchCount,
        periodicDebugMatchCount
      }
    })
    return {
      status: 'COLLECTED',
      bytes: length,
      sha256: sha256(text),
      warningCount: (text.match(/\bWARN\b/g) ?? []).length,
      errorCount: (text.match(/\bERROR\b/g) ?? []).length,
      identityMatches,
      rawLinesRetained: false
    }
  }
}

class ApiClient {
  constructor(config, baseUrl) {
    this.config = config
    this.baseUrl = baseUrl
  }

  async request(label, urlPath, options = {}) {
    let response
    try {
      response = await fetch(`${this.baseUrl}${urlPath}`, {
        method: options.method ?? 'GET',
        headers: {
          'X-Tenant-Id': this.config.tenantId,
          'X-User-Id': this.config.userId,
          ...(options.body === undefined ? {} : {'Content-Type': 'application/json'})
        },
        body: options.body,
        redirect: 'error',
        signal: options.signal ?? AbortSignal.timeout(45_000)
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

  async createSession() {
    const data = await this.expect('session.create', '/api/ai/session', {method: 'POST'})
    const sessionId = data?.sessionIdText
    if (typeof sessionId !== 'string' || !/^[1-9]\d*$/.test(sessionId)) throw new VerificationFailure('SESSION_ID_INVALID')
    return sessionId
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

  async jobs(taskId) {
    return await this.expect('task.jobs', `/api/tasks/${taskId}/jobs`)
  }

  async files(taskId) {
    return await this.expect('task.files', `/api/tasks/${taskId}/files`)
  }

  async health() {
    try {
      const response = await this.request('health.live', '/health/live')
      return {reachable: true, status: response.status, errorName: null}
    } catch (error) {
      return {reachable: false, status: null, errorName: safeToken(error?.businessCode, 'CONNECTION_FAILED')}
    }
  }
}

function parseSseBlock(block) {
  const data = []
  for (const line of block.split(/\r?\n/)) if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
  if (data.length === 0) return null
  try {
    return JSON.parse(data.join('\n'))
  } catch {
    throw new VerificationFailure('INVALID_APPLICATION_SSE_JSON')
  }
}

async function parkFrontendTool(api, sessionId, message, timeoutMs) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  let response
  try {
    response = await fetch(`${api.baseUrl}/api/ai/chat`, {
      method: 'POST',
      headers: {
        'X-Tenant-Id': api.config.tenantId,
        'X-User-Id': api.config.userId,
        'Content-Type': 'application/json',
        Accept: 'text/event-stream'
      },
      body: JSON.stringify({
        sessionId,
        message,
        clientRequestId: `ai-visibility-${randomUUID()}`,
        context: {
          page: 'tasks', taskType: null, step: null, taskId: null, taskStatus: null,
          extra: {pageId: 'tasks', selectedDefs: [], selectionSynced: false, importMode: null}
        }
      }),
      redirect: 'error',
      signal: controller.signal
    })
  } catch (error) {
    throw new VerificationFailure('ai.chat.open', null, safeToken(error?.name, 'Error'))
  }
  if (!response.ok || !(response.headers.get('content-type') ?? '').includes('text/event-stream')) {
    clearTimeout(timer)
    throw new VerificationFailure('ai.chat.sse', response.status)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffered = ''
  let runId = null
  let request = null
  const frameTypes = []
  let streamClosed = false
  try {
    while (!request) {
      const {done, value} = await reader.read()
      if (done) break
      buffered += decoder.decode(value, {stream: true})
      if (buffered.length > 256 * 1024 && !/\r?\n\r?\n/.test(buffered)) throw new VerificationFailure('SSE_EVENT_TOO_LARGE')
      let separator
      while (!request && (separator = /\r?\n\r?\n/.exec(buffered)) !== null) {
        const block = buffered.slice(0, separator.index)
        buffered = buffered.slice(separator.index + separator[0].length)
        const frame = parseSseBlock(block)
        if (!frame) continue
        if (frameTypes.length >= maxSseFrames) throw new VerificationFailure('SSE_FRAME_LIMIT_EXCEEDED')
        frameTypes.push(safeToken(frame.type, 'unknown'))
        if (frame.type === 'start') runId = frame.runId
        if (frame.type === 'frontend_tool_request') {
          request = {
            runId: frame.runId ?? runId,
            toolCallId: frame.toolCallId,
            name: frame.name,
            seq: Number(frame.seq),
            expiresAt: Number(frame.expiresAt ?? 0) || null,
            timeoutSeconds: Number(frame.timeoutSeconds ?? 0) || null
          }
        }
      }
    }
    if (!request) throw new VerificationFailure('FRONTEND_TOOL_REQUEST_NOT_OBSERVED')
    await reader.cancel()
    streamClosed = true
    controller.abort()
    return {
      request,
      runId: request.runId ?? runId,
      frameEvidence: {count: frameTypes.length, tail: frameTypes.slice(-16), truncated: frameTypes.length > 16},
      streamClosed,
      applicationSseFabricated: false
    }
  } finally {
    clearTimeout(timer)
    if (!streamClosed) {
      await reader.cancel().catch(() => {})
      controller.abort()
    }
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
    if (message.toolCallStates?.[toolCallId]) states.push(message.toolCallStates[toolCallId].status ?? null)
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

function pendingEntry(run, toolCallId) {
  return (run.pending ?? []).find((entry) => entry.toolCallId === toolCallId) ?? null
}

async function businessFingerprint(api) {
  const tasks = []
  let total = null
  for (let page = 0; page < 100; page += 1) {
    const data = await api.expect('tasks.page', `/api/tasks?page=${page}&size=100`)
    if (!Array.isArray(data?.content)) throw new VerificationFailure('TASK_PAGE_INVALID')
    if (total === null) total = Number(data.totalElements)
    if (total !== Number(data.totalElements)) throw new VerificationFailure('TASK_TOTAL_CHANGED')
    for (const entry of data.content) {
      const task = entry.task ?? entry
      const id = Number(task.id)
      const jobs = await api.jobs(id)
      const files = await api.files(id)
      const items = Array.isArray(task.items) ? task.items : []
      if (items.length > 512 || jobs.length > 512 || files.length > 512) throw new VerificationFailure('BUSINESS_ENTRY_LIMIT_EXCEEDED')
      const fileProjection = files.map((file) => {
        const projection = {
          id: Number(file.id),
          defCode: safeToken(file.defCode),
          fileType: safeToken(file.fileType),
          size: Number(file.size ?? file.fileSize ?? 0)
        }
        return {...projection, digest: sha256(projection)}
      })
      tasks.push({
        id,
        currentStep: task.currentStep ?? null,
        status: task.status ?? null,
        items: items.map((item) => ({
          defCode: safeToken(item.defCode),
          status: safeToken(item.status),
          condition: item.conditionJson == null ? null : textEvidence(String(item.conditionJson))
        })),
        jobs: jobs.map((job) => ({id: Number(job.id), status: job.status ?? null, jobType: job.jobType ?? null})),
        files: fileProjection,
        filesDigest: sha256(fileProjection)
      })
    }
    if (tasks.length >= total || data.content.length === 0) break
    if (page === 99) throw new ScenarioNotRun('BUSINESS_PAGE_LIMIT_EXCEEDED')
  }
  if (total === null || tasks.length !== total) throw new VerificationFailure('BUSINESS_SNAPSHOT_INCOMPLETE')
  return sha256(tasks.sort((left, right) => left.id - right.id))
}

async function assertParked(context, parked) {
  const {checks, apiA} = context
  const {request, runId} = parked
  checks.require('park.identity', request.name === 'navigate_to' && typeof request.toolCallId === 'string'
    && request.toolCallId !== '' && typeof runId === 'string' && runId !== '', 'real navigate_to request and run identity', request)
  checks.require('park.real-stream-closed', parked.streamClosed === true && parked.applicationSseFabricated === false,
    'real application SSE observed then closed; zero fabricated frames', {streamClosed: parked.streamClosed})
  const run = await apiA.run(runId)
  const entry = pendingEntry(run, request.toolCallId)
  checks.require('park.session-run-binding', run.snapshot?.sessionId === parked.sessionId
    && run.snapshot?.runId === runId, 'authoritative run snapshot binds sessionId and runId',
  {sessionId: parked.sessionId, runId, snapshotSessionId: run.snapshot?.sessionId ?? null, snapshotRunId: run.snapshot?.runId ?? null})
  checks.require('park.suspended-pending', run.snapshot?.status === 'SUSPENDED' && entry?.status === 'PENDING'
    && entry.executed === false, 'SUSPENDED with one unexecuted PENDING frontend entry', runSummary(run))
  checks.require('park.absolute-expiry', Number.isSafeInteger(entry?.expiresAt) && entry.expiresAt > Date.now()
    && entry.expiresAt === request.expiresAt, 'public pending expiresAt matches real request frame and is in the future',
  {frameExpiresAt: request.expiresAt, pendingExpiresAt: entry?.expiresAt ?? null})
  parked.beforeRun = run
  parked.expiresAt = entry.expiresAt
}

async function waitForExpiry(context, parked, api, label) {
  const deadline = Date.now() + context.config.timeoutMs
  while (Date.now() < deadline) {
    const run = await api.run(parked.runId)
    const entry = pendingEntry(run, parked.request.toolCallId)
    if (entry?.status === 'TIMEOUT' && terminalRuns.has(run.snapshot?.status)) {
      parked.convergedAtMs = Date.now()
      parked.finalRun = run
      parked.finalInstanceId = safeToken(run.instanceId)
      parked.convergedBy = label
      return run
    }
    if (entry?.status !== 'PENDING' && entry?.status !== 'TIMEOUT') throw new VerificationFailure('UNEXPECTED_EXPIRY_OUTCOME')
    await sleep(context.config.pollMs)
  }
  throw new VerificationFailure('FRONTEND_ENTRY_DID_NOT_EXPIRE')
}

async function assertExpired(context, parked, api, label) {
  const {checks} = context
  const run = await api.run(parked.runId)
  const entry = pendingEntry(run, parked.request.toolCallId)
  checks.require(`${label}.timeout`, run.snapshot?.status !== 'SUSPENDED' && entry?.status === 'TIMEOUT'
    && entry.outcome === 'timeout' && entry.executed === false, 'terminal run with TIMEOUT, outcome=timeout and executed=false', runSummary(run))
  checks.require(`${label}.terminal-run`, terminalRuns.has(run.snapshot?.status), [...terminalRuns], run.snapshot?.status ?? null)
  if (entry?.resolvedAtMs === undefined || entry?.resolvedAtMs === null) {
    throw new ScenarioNotRun('PUBLIC_RESOLVED_AT_MS_REQUIRED')
  }
  const resolvedAtMs = Number(entry.resolvedAtMs)
  checks.require(`${label}.authoritative-resolution-time`, Number.isSafeInteger(resolvedAtMs) && resolvedAtMs > 0
    && resolvedAtMs + expiryClockSkewMs >= parked.expiresAt,
  {expiresAt: parked.expiresAt, clockSkewAllowanceMs: expiryClockSkewMs},
  {resolvedAtMs: entry.resolvedAtMs, expiresAt: parked.expiresAt})
  checks.add(`${label}.observation-is-auxiliary`, Number.isSafeInteger(parked.convergedAtMs), true,
    parked.convergedAtMs ?? null)
  const history = await api.history(parked.sessionId)
  const occurrences = historyOccurrences(history, parked.request.toolCallId)
  checks.require(`${label}.history-once`, occurrences.states.length === 1 && occurrences.states[0] === 'TIMEOUT'
    && occurrences.responses.length === 1 && occurrences.responses[0] === 'navigate_to',
  'exactly one TIMEOUT state and one tool response', occurrences)
  parked.history = history
  parked.historySignature = canonicalJson(historySummary(history))
  parked.runSignature = canonicalJson({...runSummary(run), instanceId: null})
}

async function assertQuiescent(context, parked, api, label) {
  await sleep(Math.max(1_000, context.config.pollMs * 2))
  const run = await api.run(parked.runId)
  const history = await api.history(parked.sessionId)
  const signature = canonicalJson({...runSummary(run), instanceId: null})
  context.checks.require(`${label}.quiescent-run`, signature === parked.runSignature,
    'no duplicate run/ledger transition after convergence; reader instanceId excluded', signature)
  context.checks.require(`${label}.quiescent-history`, canonicalJson(historySummary(history)) === parked.historySignature,
    'no duplicate history response after convergence', canonicalJson(historySummary(history)))
}

async function waitInstance(api, expectedUp, timeoutMs, pollMs, notRunCode) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const health = await api.health()
    const isUp = health.reachable === true && health.status === 200
    const isStopped = health.reachable === false && health.status === null
    if ((expectedUp && isUp) || (!expectedUp && isStopped)) return health
    await sleep(pollMs)
  }
  throw new ScenarioNotRun(notRunCode)
}

async function cleanup(context) {
  const result = {status: 'COMPLETE', sessions: [], instanceA: null}
  for (const parked of context.parked) {
    const entry = {sessionId: parked.sessionId, runId: parked.runId, status: 'INCOMPLETE', attempts: []}
    try {
      const run = await context.apiB.run(parked.runId).catch(() => null)
      if (run && !terminalRuns.has(run.snapshot?.status)) {
        await context.apiB.request('cleanup.cancel', `/api/ai/cancel/${encodeURIComponent(parked.runId)}`, {method: 'POST'})
      }
      for (let attempt = 1; attempt <= 2; attempt += 1) {
        const deleted = await context.apiB.request('cleanup.session', `/api/ai/session/${encodeURIComponent(parked.sessionId)}`, {method: 'DELETE'})
        const body = dataOf(deleted)
        entry.attempts.push({attempt, httpStatus: deleted.status, complete: body?.complete === true,
          deferredCount: Array.isArray(body?.deferred) ? body.deferred.length : null,
          errorCount: Array.isArray(body?.errors) ? body.errors.length : null})
      }
      const history = await context.apiB.request('cleanup.history', `/api/ai/history/${encodeURIComponent(parked.sessionId)}?latest=1`)
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
  if (context.config.scenario === 'C-150') {
    let health = await context.apiA.health()
    if (health.reachable !== true || health.status !== 200) {
      console.log('ACTION_REQUIRED: restore backend instance A before process exit; cleanup is waiting for /health/live HTTP 200')
      try {
        health = await waitInstance(context.apiA, true, context.config.instanceTimeoutMs, context.config.pollMs, 'INSTANCE_A_RESTORE_REQUIRED')
      } catch (error) {
        result.instanceRestoreError = safeError(error, 'INSTANCE_A_RESTORE_REQUIRED')
      }
    }
    const restored = health.reachable === true && health.status === 200
    result.instanceA = {health, restored}
    if (!restored) result.status = 'INCOMPLETE'
  }
  return result
}

const config = loadConfig()
const evidenceDirectory = await prepareEvidenceDirectory(config)
const evidence = new Evidence(evidenceDirectory)
const startedAt = new Date().toISOString()
const checks = new Checks()
const apiA = new ApiClient(config, config.baseUrl)
const apiB = new ApiClient(config, config.baseUrlB ?? config.baseUrl)
const logA = new LogProbe(config.backendLogFile)
const logB = config.backendLogFileB ? new LogProbe(config.backendLogFileB) : null
const context = {config, checks, apiA, apiB, parked: []}
let fatal = null
let notRun = null
let cleanupResult = null
let businessBefore = null
let businessAfter = null
let logs = null
const seenSessions = new Set()
const seenRuns = new Set()

try {
  await logA.begin()
  if (logB) await logB.begin()
  businessBefore = await businessFingerprint(apiA)
  for (let round = 1; round <= 3; round += 1) {
    const sessionId = await apiA.createSession()
    if (seenSessions.has(sessionId)) throw new VerificationFailure('SESSION_NOT_FRESH')
    seenSessions.add(sessionId)
    const message = `tool=navigate_to&args={"page":"tasks"} verification=${randomUUID()}`
    const parked = await parkFrontendTool(apiA, sessionId, message, config.timeoutMs)
    parked.sessionId = sessionId
    parked.round = round
    parked.promptEvidence = textEvidence(message)
    if (seenRuns.has(parked.runId)) throw new VerificationFailure('RUN_NOT_FRESH')
    seenRuns.add(parked.runId)
    context.parked.push(parked)
    await assertParked(context, parked)
  }
  checks.require('sessions.three-new', seenSessions.size === 3 && seenRuns.size === 3,
    'three distinct backend-issued sessions and runs', {sessionCount: seenSessions.size, runCount: seenRuns.size})

  if (config.scenario === 'C-150') {
    console.log('ACTION_REQUIRED: stop backend instance A now; only a real connection failure/timeout counts as stopped')
    const stoppedHealth = await waitInstance(apiA, false, config.instanceTimeoutMs, config.pollMs, 'INSTANCE_A_STOP_REQUIRED')
    checks.require('C-150.instance-a-stopped', stoppedHealth.reachable === false && stoppedHealth.status === null,
      'instance A is unreachable; HTTP 401/403/500 never count as stopped', stoppedHealth)
    const aInstanceIds = new Set(context.parked.map((parked) => safeToken(parked.beforeRun.instanceId)).filter(Boolean))
    checks.require('C-150.parked-on-one-instance', aInstanceIds.size === 1, 'all three runs created on instance A', [...aInstanceIds])
  }

  for (const parked of context.parked) await waitForExpiry(context, parked, apiB, config.scenario === 'C-150' ? 'B' : 'A')
  for (const parked of context.parked) await assertExpired(context, parked, apiB, config.scenario === 'C-150' ? 'B' : 'A')
  for (const parked of context.parked) await assertQuiescent(context, parked, apiB, config.scenario === 'C-150' ? 'B' : 'A')

  if (config.scenario === 'C-150') {
    const bInstanceIds = new Set(context.parked.map((parked) => parked.finalInstanceId).filter(Boolean))
    checks.require('C-150.reads-on-one-b-instance', bInstanceIds.size === 1, 'all B reads report one instance identity', [...bInstanceIds])
    const aIds = new Set(context.parked.map((parked) => safeToken(parked.beforeRun.instanceId)).filter(Boolean))
    checks.require('C-150.instance-identities-differ', [...aIds].every((id) => !bInstanceIds.has(id)),
      'B instance identity differs from A', {a: [...aIds], b: [...bInstanceIds]})
    console.log('ACTION_REQUIRED: restart backend instance A now; the script will wait for /health/live HTTP 200 before cross-check and cleanup')
    await waitInstance(apiA, true, config.instanceTimeoutMs, config.pollMs, 'INSTANCE_A_RESTART_REQUIRED')
    for (const parked of context.parked) await assertQuiescent(context, parked, apiA, 'A-after-restart')
  }

  businessAfter = await businessFingerprint(apiB)
  checks.require('business.zero-side-effects', businessAfter === businessBefore,
    'no task/job changes from parked expiry or instance failover', {before: businessBefore, after: businessAfter})
  logs = {A: await logA.capture(context.parked), B: logB ? await logB.capture(context.parked) : null}
  checks.require('logs.no-new-error-A', logs.A.errorCount === 0, 0, logs.A.errorCount)
  if (logs.B) checks.require('logs.no-new-error-B', logs.B.errorCount === 0, 0, logs.B.errorCount)
  const expiryLogs = config.scenario === 'C-150' ? logs.B : logs.A
  for (const parked of context.parked) {
    const match = expiryLogs.identityMatches.find((entry) => entry.sessionId === parked.sessionId
      && entry.runId === parked.runId && entry.toolCallId === parked.request.toolCallId)
    checks.require(`logs.expiry.${parked.runId}`, match?.matchCount >= 1,
      'at least one expiry/convergence log line contains this session runId and toolCallId', match ?? {
        sessionId: parked.sessionId,
        runId: parked.runId,
        toolCallId: parked.request.toolCallId,
        matchCount: 0
      })
  }
} catch (error) {
  if (error instanceof ScenarioNotRun) notRun = {code: safeToken(error.code, 'SCENARIO_NOT_RUN')}
  else fatal = safeError(error)
} finally {
  cleanupResult = await cleanup(context).catch((error) => ({status: 'INCOMPLETE', error: safeError(error, 'CLEANUP_FAILED')}))
  if (logs === null) {
    try {
      logs = {A: await logA.capture(context.parked), B: logB ? await logB.capture(context.parked) : null}
    } catch (error) {
      logs = {status: 'NOT_RUN', error: safeError(error, 'LOG_CAPTURE_FAILED')}
    }
  }
}

const status = fatal || cleanupResult.status !== 'COMPLETE' || checks.failures.length > 0 || logs?.status === 'NOT_RUN'
  ? 'FAIL' : notRun ? 'NOT_RUN' : 'PASS'
const summary = {
  schemaVersion: 1,
  scenario: config.scenario,
  status,
  statusScope: config.scenario === 'C-149'
    ? 'THREE_REAL_NO_CLIENT_EXPIRIES_ON_ONE_INSTANCE; PUBLIC_CHAT_SSE_HISTORY_RUN_BUSINESS_APIS_AND_LOG_COUNTS; DOM=NOT_APPLICABLE_NO_CLIENT; DIRECT_REDIS=NOT_RUN'
    : 'THREE_REAL_NO_CLIENT_EXPIRIES_WITH_MANUAL_A_STOP_RESTART_AND_B_TAKEOVER; PUBLIC_HEALTH_CHAT_SSE_HISTORY_RUN_BUSINESS_APIS_AND_LOG_COUNTS; DOM=NOT_APPLICABLE_NO_CLIENT; DIRECT_REDIS=NOT_RUN',
  startedAt,
  finishedAt: new Date().toISOString(),
  codeHead: codeHead(),
  executorIdentity: config.executorIdentity,
  evidenceDirectory,
  requiredProcessCountPerScenario: 1,
  requiredFreshSessionsPerProcess: 3,
  sessions: context.parked.map((parked) => ({
    round: parked.round,
    sessionId: parked.sessionId,
    runId: parked.runId,
    toolCallId: parked.request.toolCallId,
    frameEvidence: parked.frameEvidence ?? null,
    realStreamClosed: parked.streamClosed,
    expiresAt: parked.expiresAt,
    convergedAtMs: parked.convergedAtMs ?? null,
    clockSkewAllowanceMs: expiryClockSkewMs,
    before: parked.beforeRun ? runSummary(parked.beforeRun) : null,
    final: parked.finalRun ? runSummary(parked.finalRun) : null,
    convergedBy: parked.convergedBy ?? null,
    history: parked.history ? historySummary(parked.history) : null
  })),
  assertions: checks.items,
  failedAssertions: checks.failures,
  fatal,
  notRun,
  business: {before: businessBefore, after: businessAfter, noSideEffects: businessBefore !== null && businessBefore === businessAfter},
  logs,
  cleanup: cleanupResult,
  evidencePolicy: 'BOUNDED_WHITELIST_ONLY; no raw API/SSE responses, prompt/tool text, log lines or exception messages; no resume/startup-scan/events call is used to force expiry'
}
await evidence.json('summary.json', summary)
console.log(JSON.stringify({scenario: config.scenario, status, evidenceDirectory,
  sessions: context.parked.length, cleanup: cleanupResult.status, failedAssertions: checks.failures.length}, null, 2))
if (status !== 'PASS') process.exitCode = status === 'NOT_RUN' ? 2 : 1
