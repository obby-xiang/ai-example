#!/usr/bin/env node

import {execFileSync} from 'node:child_process'
import {createHash, randomUUID} from 'node:crypto'
import {lstat, mkdir, realpath, writeFile} from 'node:fs/promises'
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
const scenarios = new Set([...toolCases.keys(), 'tool-skipped', 'form-mixed-copy', 'recovery-success', 'recovery-final-failure'])
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
    appUrl: httpUrl('APP_URL'),
    baseUrl: httpUrl('BASE_URL'),
    cdpUrl: httpUrl('CDP_URL', {loopbackOnly: true}),
    tenantId: process.env.TENANT_ID.trim(),
    userId: process.env.USER_ID.trim(),
    executorIdentity: process.env.EXECUTOR_IDENTITY.trim(),
    outDir: outsidePath('OUT_DIR'),
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
      toolCallStates: Object.fromEntries(Object.entries(message.toolCallStates ?? {}).map(([id, state]) => [id, {status: state?.status ?? null}])),
      toolResponses: (message.toolResponses ?? []).map((response) => ({id: response.id ?? null, name: response.name ?? null}))
    }))
  }
}

function historyToolOccurrences(history, toolCallId) {
  const states = []
  const responses = []
  for (const message of history.messages) {
    if (message.toolCallStates?.[toolCallId]) states.push(message.toolCallStates[toolCallId].status ?? null)
    for (const response of message.toolResponses ?? []) {
      if (response.id === toolCallId) responses.push(response.name ?? null)
    }
  }
  return {states, responses}
}

const browserObservationSource = String.raw`
(() => {
  const state = window.__toolCardRecoveryVerification = {
    samples: [],
    noticeSamples: [],
    copyCalls: [],
    errors: []
  }
  const recordError = (error, code) => {
    state.errors.push({code, errorName: typeof error?.name === 'string' ? error.name : 'Error'})
    if (state.errors.length > 64) state.errors.splice(0, state.errors.length - 64)
  }
  const visible = (element) => !!(element && (element.offsetParent !== null || element.getClientRects().length > 0))
  const appStore = () => {
    const app = document.getElementById('app')
    const pinia = app && app.__vue_app__ && app.__vue_app__.config.globalProperties.$pinia
    return pinia ? pinia._s.get('ai') : null
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
        const serialized = JSON.stringify({tools, cards})
        if (serialized !== previousTools) {
          previousTools = serialized
          state.samples.push({atMs: Date.now(), tools, cards})
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
    this.cdpErrors = []
    this.evidenceLimitExceeded = false
    this.historySequence = 0
    this.interceptionEnabled = false
    this.interceptionTransitions = []
    this.faultMode = 'off'
    this.historyFaultsRemaining = 0
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
      if (url.pathname.startsWith('/api/ai/history/')) {
        if (this.historyRequests.length >= HISTORY_REQUEST_LIMIT) {
          this.evidenceLimitExceeded = true
          this.recordCdpError(new VerificationFailure('HISTORY_EVIDENCE_LIMIT_EXCEEDED'), 'HISTORY_EVIDENCE_LIMIT_EXCEEDED')
          return
        }
        this.historySequence += 1
        this.historyRequests.push({
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
        })
      }
    } catch (error) {
      this.recordCdpError(error, 'NETWORK_REQUEST_EVIDENCE_FAILED')
    }
  }

  historyRecord(requestId) {
    return this.historyRequests.find((record) => record.requestId === requestId) ?? null
  }

  onResponseReceived(params) {
    const record = this.historyRecord(params.requestId)
    if (record) record.responseStatus = params.response.status
  }

  onLoadingFinished(params) {
    const record = this.historyRecord(params.requestId)
    if (record) record.finished = true
  }

  onLoadingFailed(params) {
    const record = this.historyRecord(params.requestId)
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
    if (result.exceptionDetails) throw new VerificationFailure('BROWSER_EVALUATION_FAILED')
    return result.result.value
  }

  async waitFor(expression, code, timeoutMs = this.config.timeoutMs) {
    const deadline = Date.now() + timeoutMs
    let lastError = null
    while (Date.now() < deadline) {
      try {
        const value = await this.evaluate(expression, false)
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
    await this.evaluate(`document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai').expanded = true`, false)
  }

  async click(selector) {
    const center = await this.evaluate(`(() => {
      const element = document.querySelector(${JSON.stringify(selector)})
      if (!element) return null
      element.scrollIntoView({block: 'center', inline: 'center'})
      const rect = element.getBoundingClientRect()
      return {x: rect.x + rect.width / 2, y: rect.y + rect.height / 2}
    })()`, false)
    if (!center) throw new AssertionFailure('CLICK_TARGET_MISSING')
    await this.send('Input.dispatchMouseEvent', {type: 'mousePressed', ...center, button: 'left', buttons: 1, clickCount: 1})
    await this.send('Input.dispatchMouseEvent', {type: 'mouseReleased', ...center, button: 'left', buttons: 0, clickCount: 1})
  }

  async sendPrompt(prompt) {
    await this.evaluate(`document.querySelector('[data-testid="composer-input"]').focus()`, false)
    await this.send('Input.insertText', {text: prompt})
    await this.click('[data-testid="send-btn"]')
  }

  async snapshot() {
    return await this.evaluate(`(() => {
      const pinia = document.getElementById('app').__vue_app__.config.globalProperties.$pinia
      const ai = pinia._s.get('ai')
      const workspace = pinia._s.get('workspace')
      const visible = (element) => !!(element && (element.offsetParent !== null || element.getClientRects().length > 0))
      return {
        sessionId: ai.sessionId,
        activeRunId: ai.activeRunId,
        loading: ai.loading === true,
        historyLoaded: ai.historyLoaded === true,
        locale: document.documentElement.lang,
        messages: ai.messages.map((message) => ({
          id: message.id,
          role: message.role,
          runId: message.runId ?? null,
          content: message.content ?? '',
          done: message.done === true,
          parts: message.parts.map((part) => part.kind === 'tool' ? {
            kind: part.kind,
            toolCallId: part.toolCallId,
            name: part.name,
            status: part.status,
            args: part.args ?? null,
            requiresAction: part.requiresAction === true,
            decision: part.decision ?? null
          } : {kind: part.kind, text: part.text ?? ''})
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
          state: element.querySelector('[data-card-state]')?.getAttribute('data-card-state') ?? null,
          title: element.querySelector('[data-testid^="card-title-"]')?.innerText ?? null,
          text: element.innerText
        })),
        recoverySurfaces: [...document.querySelectorAll(${JSON.stringify(recoverySurfaceSelector)})].filter(visible)
          .map((element) => ({testId: element.getAttribute('data-testid'), text: element.innerText})),
        recoveryRetries: [...document.querySelectorAll(${JSON.stringify(recoveryRetrySelector)})].filter(visible)
          .map((element) => ({testId: element.getAttribute('data-testid'), text: element.innerText})),
        observation: window.__toolCardRecoveryVerification ?? null
      }
    })()`, false)
  }

  recordInterceptionTransition(enabled, proof) {
    if (this.interceptionTransitions.length >= EVIDENCE_TAIL_LIMIT) {
      this.evidenceLimitExceeded = true
      return
    }
    this.interceptionTransitions.push({phase: this.phase, enabled, proof})
  }

  async setHistoryFault(mode) {
    this.faultMode = mode
    this.historyFaultsRemaining = mode === 'once' ? 1 : mode === 'always' ? -1 : 0
    if (mode === 'off') {
      await this.send('Fetch.disable')
      this.interceptionEnabled = false
      this.recordInterceptionTransition(false, 'FETCH_DISABLE_COMPLETED')
      return
    }
    await this.send('Fetch.enable', {patterns: [{urlPattern: '*/api/ai/history/*', requestStage: 'Request'}]})
    this.interceptionEnabled = true
    this.recordInterceptionTransition(true, 'FETCH_ENABLE_COMPLETED')
  }

  async handlePaused(params) {
    const record = this.historyRecord(params.networkId ?? params.requestId)
    if (record) record.interceptionEnabledAtPause = this.interceptionEnabled
    const shouldFault = this.faultMode === 'always' || (this.faultMode === 'once' && this.historyFaultsRemaining > 0)
    try {
      if (shouldFault) {
        if (this.historyFaultsRemaining > 0) this.historyFaultsRemaining -= 1
        if (record) {
          record.action = 'HTTP_503'
          record.injectedStatus = 503
        }
        await this.send('Fetch.fulfillRequest', {
          requestId: params.requestId,
          responseCode: 503,
          responseHeaders: [{name: 'Content-Type', value: 'application/json; charset=utf-8'}],
          body: Buffer.from('{"code":"AI_HISTORY_UNAVAILABLE","data":null}').toString('base64')
        })
      } else {
        if (record) record.action = 'CONTINUE_UNCHANGED'
        await this.send('Fetch.continueRequest', {requestId: params.requestId})
      }
    } catch (error) {
      if (record) record.errorName = safeErrorName(error)
      this.recordCdpError(error, 'HISTORY_FAULT_INTERCEPTION_FAILED')
    }
  }

  historyPhase(phase) {
    const records = this.historyRequests.filter((record) => record.phase === phase)
    const isSuccess = (record) => ['CONTINUE_UNCHANGED', 'OBSERVED'].includes(record.action)
      && record.responseStatus === 200 && record.finished && record.errorName === null
    return {
      phase,
      count: records.length,
      faultCount: records.filter((record) => record.injectedStatus === 503).length,
      successCount: records.filter(isSuccess).length,
      uninterceptedSuccessCount: records.filter((record) => isSuccess(record)
        && record.action === 'OBSERVED' && record.interceptionEnabledAtRequest === false).length,
      pendingCount: records.filter((record) => !record.finished).length,
      statuses: [...new Set(records.map((record) => record.responseStatus))],
      first503Sequence: records.find((record) => record.responseStatus === 503)?.sequence ?? null,
      firstReal200Sequence: records.find(isSuccess)?.sequence ?? null,
      interceptionEnabled: this.interceptionEnabled,
      records: records.slice(-EVIDENCE_TAIL_LIMIT)
    }
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

  requestCounts() {
    const count = (method, suffix) => this.networkRequests.filter((request) => request.method === method && request.path === suffix).length
    return {
      chat: count('POST', '/api/ai/chat'),
      frontendToolResult: count('POST', '/api/ai/frontend-tool-result'),
      confirm: count('POST', '/api/ai/confirm')
    }
  }

  async close() {
    const result = {status: 'COMPLETE', targetId: this.targetId, closeHttpStatus: null,
      targetAbsent: this.targetId === null, webSocketClosed: false, errors: []}
    if (this.faultMode !== 'off') {
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
        const listResponse = await boundedFetch(`${this.config.cdpUrl}/json/list`)
        const targets = await listResponse.json()
        result.targetAbsent = listResponse.ok && !targets.some((target) => target.id === this.targetId)
        if (!result.targetAbsent) result.errors.push({errorName: 'TARGET_STILL_PRESENT', code: 'CDP_TARGET_CLOSE_UNVERIFIED', httpStatus: listResponse.status, businessCode: null})
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
    id: message.id,
    role: message.role,
    runId: message.runId,
    done: message.done,
    content: textEvidence(message.content),
    parts: message.parts.map((part) => part.kind === 'tool' ? part : {kind: part.kind, text: textEvidence(part.text)})
  }))
}

function targetPart(snapshot, toolCallId) {
  for (const message of snapshot.messages) {
    const part = message.parts.find((candidate) => candidate.toolCallId === toolCallId)
    if (part) return {message, part}
  }
  return null
}

function targetCard(snapshot, toolCallId) {
  return snapshot.cards.filter((card) => card.testId === `tool-card-${toolCallId}` || card.testId === `pending-card-${toolCallId}`)
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
}

function promptFor(context) {
  const nonce = randomUUID()
  if (toolCases.has(context.config.scenario)) return `${toolCases.get(context.config.scenario).prompt} verification=${nonce}`
  if (context.config.scenario === 'tool-skipped') {
    return `tool=create_task&args={"type":"EXPORT","conditions":{},"openWizard":false} multi-card=2 verification=${nonce}`
  }
  if (context.config.scenario === 'form-mixed-copy') return `timeline-nonprefix verification=${nonce}`
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

async function copyMessageAndAssert(context, phase, messageId, {requiredTitle = null, forbiddenTitle = null, requiredTexts = [], expectedText = null} = {}) {
  const {browser, evidence, checks} = context
  const before = await browser.evaluate(`(window.__toolCardRecoveryVerification?.copyCalls ?? []).length`, false)
  await browser.click(`[data-testid="copy-msg-${messageId}"]`)
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
    requiredTexts,
    expectedText,
    text: copy.text,
    sha256: sha256(copy.text)
  })
  return copy
}

async function savePhase(context, phase, snapshot, historyEvidence, business) {
  context.checks.require(`${phase}.observer-no-errors`, (snapshot.observation?.errors ?? []).length === 0,
    'page observation and clipboard hooks report no errors', snapshot.observation?.errors ?? [])
  await context.evidence.json(`dom-${phase}.json`, {
    ...snapshot,
    messages: messageContentSignature(snapshot),
    observation: snapshot.observation
  })
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
  checks.require(`${phase}.fixture-task.present`, !!task && !!before, context.fixtureTaskId, task ?? null)
  checks.require(`${phase}.fixture.zero-jobs`, task.jobs.length === 0, 0, task.jobs)
  if (selected.tool === 'select_definitions') {
    checks.require(`${phase}.business.public-selection-unchanged`, canonicalJson(task) === canonicalJson(before),
      'public task unchanged by draft-only select_definitions', {before, task})
    if (phase === 'live-succeeded') {
      checks.require(`${phase}.workspace.selection-draft-only-live`, snapshot.workspace.selectedDefs.length === 1
        && snapshot.workspace.selectedDefs[0] === 'CURRENCY',
      'live workspace contains exactly the CURRENCY draft; no cross-refresh draft requirement', snapshot.workspace.selectedDefs)
    }
  }
  if (selected.tool === 'confirm_step') {
    checks.require(`${phase}.business.step-advanced-with-preselected-fixture`, task.currentStep === 'QUERY_COND'
      && before.currentStep === 'SELECT_DEFS'
      && before.items.filter((item) => item.defCode === 'CURRENCY').length === 1
      && task.items.filter((item) => item.defCode === 'CURRENCY').length === 1,
    'preselected CURRENCY remains while confirm_step advances SELECT_DEFS to QUERY_COND exactly once', {before, task})
  }
  if (selected.tool === 'set_condition') {
    checks.require(`${phase}.business.public-condition-unchanged`, canonicalJson(task) === canonicalJson(before),
      'public task unchanged by draft-only set_condition', {before, task})
    if (phase === 'live-succeeded') {
      const drafts = snapshot.workspace.queryConditions
      const condition = drafts.CURRENCY
      const fields = Array.isArray(condition?.fields) ? condition.fields : []
      checks.require(`${phase}.workspace.exact-cny-condition-only-live`, Object.keys(drafts).length === 1
        && fields.length === 1 && fields[0]?.fieldCode === 'code' && fields[0]?.operator === 'EQ' && fields[0]?.value === 'CNY'
        && (condition?.scopeKeys === undefined || (Array.isArray(condition.scopeKeys) && condition.scopeKeys.length === 0)),
      'live workspace has only CURRENCY code EQ CNY and no additional condition draft', drafts)
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
  checks.require(`${phase}.history.executed-once`, occurrences.states.length === 1 && occurrences.states[0] === 'EXECUTED'
    && occurrences.responses.length === 1 && occurrences.responses[0] === toolName,
  {states: ['EXECUTED'], responses: [toolName]}, occurrences)
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
  const phases = []
  phases.push(await assertToolPhase(context, 'live-succeeded', selected.tool, toolCallId))
  const requestBaseline = browser.requestCounts()
  checks.require('live.single-chat', requestBaseline.chat === 1, 1, requestBaseline)
  checks.require('live.single-frontend-result', requestBaseline.frontendToolResult === 1, 1, requestBaseline)
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
  for (let decision = 1; decision <= 2; decision += 1) {
    const pending = await browser.waitFor(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      const part = ai.messages.flatMap((message) => message.parts).find((part) => part.kind === 'tool'
        && part.name === 'create_task' && part.requiresAction === true)
      if (part) return {toolCallId: part.toolCallId}
      return ai.loading === false ? {finished: true} : null
    })()`, `create_task decision ${decision}`)
    if (pending.finished) return
    await browser.click(`[data-testid="pending-card-${pending.toolCallId}"] [data-testid="gate-actions"] button:last-child`)
    await browser.waitFor(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      return !ai.messages.flatMap((message) => message.parts).some((part) => part.toolCallId === '${pending.toolCallId}' && part.requiresAction === true)
    })()`, `create_task decision ${decision} accepted`)
  }
}

function skippedToolSummary(snapshot, toolCallId, locale) {
  const target = targetPart(snapshot, toolCallId)
  if (target?.part.name !== 'create_task' || target.part.status !== 'skipped') {
    throw new AssertionFailure('SKIPPED_PART_REQUIRED_FOR_SUMMARY')
  }
  let args
  try {
    args = typeof target.part.args === 'string' ? JSON.parse(target.part.args) : target.part.args
  } catch {
    throw new AssertionFailure('SKIPPED_TOOL_ARGS_INVALID')
  }
  if (args?.type !== 'EXPORT' || (typeof args.title === 'string' && args.title.trim() !== '')) {
    throw new AssertionFailure('SKIPPED_TOOL_SUMMARY_UNAVAILABLE')
  }
  return locale === 'zh-CN' ? '创建导出配置任务' : 'Create Export task'
}

async function assertSkippedPhase(context, phase, skippedToolCallId, approvedToolCallId) {
  const {browser, api, checks} = context
  const snapshot = await browser.snapshot()
  const historyEvidence = await api.history(snapshot.sessionId)
  const history = requiredHistory(historyEvidence)
  const business = await businessSnapshot(api, relevantTaskIds(context))
  context.sessionId = snapshot.sessionId
  checks.require(`${phase}.skipped.store-present`, targetPart(snapshot, skippedToolCallId)?.part.status === 'skipped',
    'ordinary skipped remains in store/history projection input', targetPart(snapshot, skippedToolCallId))
  checks.require(`${phase}.skipped.dom-filtered`, targetCard(snapshot, skippedToolCallId).length === 0,
    'zero visible cards for ordinary skipped toolCallId', targetCard(snapshot, skippedToolCallId))
  checks.require(`${phase}.approved.single-visible-card`, targetCard(snapshot, approvedToolCallId).length === 1,
    'exactly one visible approved create_task card', targetCard(snapshot, approvedToolCallId))
  const occurrences = historyToolOccurrences(history, skippedToolCallId)
  checks.require(`${phase}.skipped.history-preserved`, occurrences.states.length === 1 && occurrences.states[0] === 'SKIPPED',
    ['SKIPPED'], occurrences)
  const newTasks = business.allTasks.filter((task) => !context.businessAfterFixture.allTasks.some((before) => before.id === task.id))
  checks.require(`${phase}.business.exactly-one-new-task`, newTasks.length === 1 && newTasks[0].jobs.length === 0,
    'one scenario-created task with zero jobs', newTasks)
  context.createdTaskIds.add(newTasks[0].id)
  business.relevantTasks = newTasks
  const skippedSummary = skippedToolSummary(snapshot, skippedToolCallId, context.config.locale)
  checks.require(`${phase}.skipped.copy-summary-known`, skippedSummary.trim() !== '',
    'full toolSummary is generated from the skipped part name and args, never from the approved card', skippedSummary)
  const target = targetPart(snapshot, skippedToolCallId)
  const requiredTexts = assertMixedBodyInHistory(context, phase, target.message, history)
  const requiredTitle = visibleToolSummary(snapshot, approvedToolCallId, context.config.locale)
  const expectedText = [...requiredTexts, requiredTitle].filter((text) => text.trim() !== '').join('\n\n')
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
    return ai.loading === false && ai.messages.some((message) => message.done === true)
  })()`, 'SKIPPED_TERMINAL_NOT_REACHED')
  const snapshot = await browser.snapshot()
  const parts = snapshot.messages.flatMap((message) => message.parts).filter((part) => part.name === 'create_task')
  const skipped = parts.filter((part) => part.status === 'skipped' && part.decision === null && part.requiresAction === false)
  if (skipped.length !== 1) throw new ScenarioNotRun('ORDINARY_SKIPPED_NOT_FORMED')
  context.toolCallId = skipped[0].toolCallId
  context.runId = snapshot.activeRunId
  const approved = parts.find((part) => part.status === 'succeeded')
  checks.require('skipped.one-approved-create', !!approved, 'one approved create_task', parts)
  const visibleApprovedCards = targetCard(snapshot, approved.toolCallId)
  checks.require('live.approved-card-visible', visibleApprovedCards.length === 1, 1, visibleApprovedCards)
  const live = await assertSkippedPhase(context, 'live', context.toolCallId, approved.toolCallId)
  const requestBaseline = browser.requestCounts()
  const phases = [live]
  for (const phase of ['history', 'refresh-1', 'refresh-2']) {
    browser.setPhase(phase)
    await browser.reload()
    phases.push(await assertSkippedPhase(context, phase, context.toolCallId, approved.toolCallId))
    checks.require(`${phase}.no-repeat-decision-or-side-effect`, canonicalJson(browser.requestCounts()) === canonicalJson(requestBaseline),
      requestBaseline, browser.requestCounts())
  }
  checks.require('refresh.skipped-history-stable', phases.every((phase) => phase.historySignature === phases[0].historySignature),
    phases[0].historySignature, phases.map((phase) => phase.historySignature))
  checks.require('refresh.skipped-business-stable', phases.every((phase) => phase.business.fingerprint === live.business.fingerprint),
    live.business.fingerprint, phases.map((phase) => phase.business.fingerprint))
}

const FORM_BODY_PARTS = ['甲段独立内容', '乙段不是甲段前缀', '丙段收尾且不同于前两段']

function hasCompleteFormBody(message) {
  if (!message) return false
  const body = bodyPartsForCopy(message).join('\n\n')
  return FORM_BODY_PARTS.every((expected) => body.includes(expected))
}

async function assertFormPhase(context, phase, toolCallId) {
  const {browser, api, checks} = context
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
  checks.require(`${phase}.form.single-succeeded-card`, cards.length === 1 && cards[0].state === 'succeeded',
    'one visible succeeded generative form card', cards)
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
    const body = host.parts.filter((part) => part.kind === 'text').map((part) => part.text).join('\n\n')
    return ai.loading === false && host.done === true
      && ${JSON.stringify(FORM_BODY_PARTS)}.every((expected) => body.includes(expected))
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
    await context.evidence.json('history-network-manual-retry-success.json', {
      ...browser.historyPhase('manual-retry-success'),
      interception: interceptionOff
    })
  }

  for (const phase of ['refresh-1', 'refresh-2']) {
    browser.setPhase(phase)
    await browser.reload()
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
    accepted = response.status === 200 || response.status === 409
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
      const status = response.body?.data?.snapshot?.status ?? response.body?.snapshot?.status
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
  const result = {status: 'COMPLETE', sessionDeletes: [], historyAfter: null, cancel: null, tasks: [], failures: []}
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
  if (context.sessionId) {
    for (let attempt = 1; attempt <= 2; attempt += 1) {
      try {
        const response = await context.api.request(`cleanup.session.delete.${attempt}`, `/api/ai/session/${encodeURIComponent(context.sessionId)}`, {method: 'DELETE'})
        const body = response.body?.data ?? response.body
        result.sessionDeletes.push({attempt, httpStatus: response.status, businessCode: businessCode(response.body), complete: body?.complete === true,
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
      const history = await context.api.request('cleanup.history', `/api/ai/history/${encodeURIComponent(context.sessionId)}?latest=1`)
      const messages = history.body?.data?.messages ?? history.body?.messages
      result.historyAfter = {httpStatus: history.status, businessCode: businessCode(history.body), messageCount: Array.isArray(messages) ? messages.length : null}
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
    appUrl: config.appUrl,
    baseUrl: config.baseUrl,
    tenantId: config.tenantId,
    userId: config.userId,
    isolationAsserted: true,
    writesAllowed: true,
    executorIdentity: config.executorIdentity,
    codeHead: codeHead(),
    startedAt,
    faultBoundary: config.scenario.startsWith('recovery-')
      ? 'CDP history HTTP 503 injection only; no application SSE, store frame, history body or business result is fabricated'
      : 'real UI prompt through the configured upstream; public history and business APIs; no application SSE injection'
  })
  await context.browser.open()
  await prepareFixture(context)
  if (config.scenario === 'tool-skipped') context.createdTaskIds.add(context.fixtureTaskId)
  if (toolCases.has(config.scenario)) await runToolScenario(context)
  else if (config.scenario === 'tool-skipped') await runSkippedScenario(context)
  else if (config.scenario === 'form-mixed-copy') await runFormScenario(context)
  else await runRecoveryScenario(context)
} catch (error) {
  if (error instanceof ScenarioNotRun) notRun = {code: safeToken(error.code, 'SCENARIO_NOT_RUN')}
  else fatal = safeError(error)
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
  'historyRequests/cdpErrors/networkRequests stay within their independent hard limits', context.browser.evidenceLimitExceeded)
await evidence.json('network-faults.json', {
  history: {
    count: context.browser.historyRequests.length,
    faultCount: context.browser.historyRequests.filter((record) => record.injectedStatus === 503).length,
    successCount: context.browser.historyRequests.filter((record) => ['CONTINUE_UNCHANGED', 'OBSERVED'].includes(record.action)
      && record.responseStatus === 200 && record.finished && record.errorName === null).length,
    limitExceeded: context.browser.evidenceLimitExceeded,
    tail: context.browser.historyRequests.slice(-EVIDENCE_TAIL_LIMIT)
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
  status,
  statusScope: toolCases.has(config.scenario) || ['tool-skipped', 'form-mixed-copy'].includes(config.scenario)
    ? 'REAL_BROWSER_DOM_STORE_PUBLIC_HISTORY_CLIPBOARD_AND_PUBLIC_BUSINESS_API; configured upstream evidence only; REAL_PROVIDER_VERDICT=NOT_RUN; DIRECT_REDIS_VERDICT=NOT_RUN; DIRECT_DATABASE_VERDICT=NOT_RUN'
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
  networkFaultCount: context.browser.historyRequests.filter((record) => record.injectedStatus === 503).length,
  historyHttpStatuses: [...new Set(context.browser.historyRequests.map((record) => record.responseStatus))],
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
