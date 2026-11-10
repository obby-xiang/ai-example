#!/usr/bin/env node

import {createHash, randomUUID} from 'node:crypto'
import {execFileSync} from 'node:child_process'
import {mkdir, writeFile} from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {setTimeout as sleep} from 'node:timers/promises'

const repoRoot = fileURLToPath(new URL('../..', import.meta.url))
const variants = {
  'chat-probe-done': 'C-171',
  'chat-probe-failed': 'C-172',
  'chat-probe-cancelled': 'C-173',
  duplicate: 'C-174',
  'awaiting-input': 'C-175',
  'snapshot-expired': 'C-176',
  unconfirmed: 'C-177',
  'budget-gate': 'C-178',
  'combined-error': 'C-179',
  'snapshot-only-transcript': 'C-180',
  'snapshot-only-unconfirmed': 'C-181',
  'duplicate-failed': 'C-182',
  'duplicate-cancelled': 'C-183',
  'history-inflight-unconfirmed': 'C-184'
}

class VerificationFailure extends Error {
  constructor(code, detail = null) {
    super(code)
    this.code = code
    this.detail = detail
  }
}

function requiredEnv(name) {
  const value = process.env[name]?.trim() ?? ''
  if (!value) throw new Error(`${name} is required`)
  return value
}

function config() {
  for (const gate of ['EVAL_ISOLATED_ENV', 'EVAL_ALLOW_WRITES', 'EVAL_STUB_ONLY']) {
    if (process.env[gate] !== 'true') throw new Error(`${gate}=true is required`)
  }
  const variant = requiredEnv('VARIANT')
  if (!variants[variant]) throw new Error('one supported VARIANT is required')
  return {
    variant,
    scenario: variants[variant],
    appUrl: requiredEnv('APP_URL').replace(/\/$/, ''),
    baseUrl: requiredEnv('BASE_URL').replace(/\/$/, ''),
    proxyUrl: requiredEnv('PROXY_URL').replace(/\/$/, ''),
    cdpUrl: requiredEnv('CDP_URL').replace(/\/$/, ''),
    tenantId: requiredEnv('TENANT_ID'),
    userId: requiredEnv('USER_ID'),
    executorIdentity: requiredEnv('EXECUTOR_IDENTITY'),
    outDir: path.resolve(requiredEnv('OUT_DIR')),
    timeoutMs: Number(process.env.TIMEOUT_MS ?? 45_000)
  }
}

function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`
  }
  return JSON.stringify(value) ?? 'null'
}

function sha256(value) {
  return createHash('sha256').update(typeof value === 'string' ? value : canonicalJson(value)).digest('hex')
}

function sourceFingerprint() {
  const files = [
    'backend/src/main/java/com/example/configmgr/ai/web/AiController.java',
    'frontend/src/api/sse.ts',
    'frontend/src/components/AiPanel/AiPanel.vue',
    'frontend/src/stores/ai.ts',
    'frontend/src/types/ai.ts'
  ]
  return {
    codeHead: execFileSync('git', ['rev-parse', 'HEAD'], {cwd: repoRoot, encoding: 'utf8'}).trim(),
    productionDiffSha256: sha256(execFileSync('git', ['diff', '--', ...files], {cwd: repoRoot, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024})),
    productionFiles: files
  }
}

async function boundedFetch(url, options = {}, timeoutMs = 30_000) {
  return await fetch(url, {...options, signal: AbortSignal.timeout(timeoutMs)})
}

class CdpPage {
  constructor(settings) {
    this.settings = settings
    this.nextId = 0
    this.waiters = new Map()
    this.requests = []
    this.errors = []
    this.targetId = null
    this.ws = null
  }

  async open() {
    const response = await boundedFetch(`${this.settings.cdpUrl}/json/new?${encodeURIComponent(this.settings.appUrl)}`, {method: 'PUT'})
    const target = await response.json()
    if (!response.ok || !target?.webSocketDebuggerUrl) throw new VerificationFailure('CDP_TARGET_CREATE_FAILED')
    this.targetId = target.id
    this.ws = new WebSocket(target.webSocketDebuggerUrl)
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new VerificationFailure('CDP_OPEN_TIMEOUT')), 15_000)
      this.ws.onopen = () => {
        clearTimeout(timer)
        resolve()
      }
      this.ws.onerror = () => {
        clearTimeout(timer)
        reject(new VerificationFailure('CDP_OPEN_FAILED'))
      }
    })
    this.ws.onmessage = (event) => this.onMessage(event)
    await this.send('Page.enable')
    await this.send('Runtime.enable')
    await this.send('Network.enable')
    await this.send('Page.addScriptToEvaluateOnNewDocument', {
      source: `try { localStorage.setItem('cm.locale', 'zh-CN'); sessionStorage.setItem('ai-identity', JSON.stringify({tenantId: ${JSON.stringify(this.settings.tenantId)}, userId: ${JSON.stringify(this.settings.userId)}})) } catch {}`
    })
    await this.send('Page.navigate', {url: this.settings.appUrl})
    await this.waitFor(`(() => {
      const ai = document.getElementById('app')?.__vue_app__?.config.globalProperties.$pinia?._s.get('ai')
      return ai?.historyLoaded === true
    })()`, 'APPLICATION_NOT_READY')
  }

  onMessage(event) {
    let message
    try {
      message = JSON.parse(event.data)
    } catch {
      this.errors.push('CDP_MESSAGE_INVALID')
      return
    }
    if (message.id && this.waiters.has(message.id)) {
      const waiter = this.waiters.get(message.id)
      this.waiters.delete(message.id)
      clearTimeout(waiter.timer)
      if (message.error) waiter.reject(new VerificationFailure('CDP_METHOD_ERROR', message.error))
      else waiter.resolve(message.result)
      return
    }
    if (message.method === 'Network.requestWillBeSent') {
      try {
        const url = new URL(message.params.request.url)
        if (!url.pathname.startsWith('/api/')) return
        this.requests.push({
          atMs: Date.now(),
          method: message.params.request.method,
          path: url.pathname,
          lastSeq: url.searchParams.get('lastSeq'),
          probe: url.searchParams.get('probe')
        })
      } catch {
        this.errors.push('NETWORK_REQUEST_INVALID')
      }
    }
    if (message.method === 'Runtime.exceptionThrown') this.errors.push('BROWSER_EXCEPTION')
  }

  send(method, params = {}) {
    const id = ++this.nextId
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.waiters.delete(id)
        reject(new VerificationFailure('CDP_METHOD_TIMEOUT', method))
      }, 35_000)
      this.waiters.set(id, {resolve, reject, timer})
      this.ws.send(JSON.stringify({id, method, params}))
    })
  }

  async evaluate(expression, awaitPromise = true) {
    const result = await this.send('Runtime.evaluate', {expression, returnByValue: true, awaitPromise, userGesture: true})
    if (result.exceptionDetails) throw new VerificationFailure('BROWSER_EVALUATION_FAILED', result.exceptionDetails)
    return result.result.value
  }

  async waitFor(expression, code, timeoutMs = this.settings.timeoutMs) {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      try {
        const value = await this.evaluate(expression, false)
        if (value) return value
      } catch {}
      await sleep(50)
    }
    throw new VerificationFailure(code)
  }

  async startSend(prompt) {
    await this.evaluate(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      window.__variantSendResult = null
      window.__variantSendPromise = ai.send(${JSON.stringify(prompt)}).then((result) => {
        window.__variantSendResult = result
        return result
      })
      return true
    })()`, false)
  }

  async sendResult() {
    return await this.waitFor('window.__variantSendResult', 'SEND_NOT_SETTLED')
  }

  async reload() {
    await this.send('Page.reload', {ignoreCache: true})
    await this.waitFor(`(() => {
      const ai = document.getElementById('app')?.__vue_app__?.config.globalProperties.$pinia?._s.get('ai')
      return ai?.historyLoaded === true
    })()`, 'RELOAD_NOT_READY')
  }

  async snapshot() {
    return await this.evaluate(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      return {
        sessionId: ai.sessionId,
        activeRunId: ai.activeRunId ?? null,
        loading: ai.loading,
        streamInterrupted: ai.streamInterrupted,
        lastRoundOutcome: ai.lastRoundOutcome,
        preparingRun: ai.preparingRun,
        pendingClientRequestId: ai.pendingClientRequestId ?? null,
        activeStreamRunId: ai.activeStreamRunId ?? null,
        activeStreamParked: ai.activeStreamParked,
        suspended: ai.suspended,
        restoredPendingQueueLength: ai.restoredPendingQueue.length,
        activeForm: ai.activeForm === null ? null : {runId: ai.activeForm.runId, toolCallId: ai.activeForm.toolCallId},
        gateSubmissionCount: Object.keys(ai.gateSubmissions).length,
        busyRun: ai.busyRun,
        streamDriveCount: Object.keys(ai.streamDrives).length,
        canSend: ai.canSend,
        manualRecovery: ai.manualRecovery ? {scope: ai.manualRecovery.scope, reason: ai.manualRecovery.reason} : null,
        noticeCode: ai.noticeCode ?? null,
        messages: ai.messages.map((message) => ({
          id: message.id,
          role: message.role,
          runId: message.runId ?? null,
          done: message.done,
          streaming: message.streaming,
          stopped: message.stopped === true,
          unconfirmed: message.unconfirmed === true,
          transcriptDegraded: message.transcriptDegraded === true,
          code: message.code ?? null,
          errorCardBaseCode: message.errorCard?.baseCode ?? null,
          textLength: message.content.length
        }))
      }
    })()`, false)
  }

  async close() {
    try {
      this.ws?.close()
    } catch {}
    if (this.targetId) {
      try {
        await boundedFetch(`${this.settings.cdpUrl}/json/close/${this.targetId}`, {}, 5_000)
      } catch {}
    }
  }
}

class ApiClient {
  constructor(settings) {
    this.settings = settings
  }

  async request(urlPath, options = {}) {
    const response = await boundedFetch(`${this.settings.baseUrl}${urlPath}`, {
      ...options,
      headers: {'X-Tenant-Id': this.settings.tenantId, 'X-User-Id': this.settings.userId, ...(options.headers ?? {})},
      redirect: 'error'
    })
    const body = await response.json().catch(() => null)
    return {status: response.status, body: body?.data ?? body}
  }

  async run(runId) {
    return await this.request(`/api/ai/runs/${encodeURIComponent(runId)}`)
  }

  async current(sessionId) {
    return await this.request(`/api/ai/runs/current?sessionId=${encodeURIComponent(sessionId)}`)
  }

  async probe(runId) {
    return await this.request(`/api/ai/events/${encodeURIComponent(runId)}?probe=1`)
  }

  async cancel(runId) {
    return await this.request(`/api/ai/cancel/${encodeURIComponent(runId)}`, {method: 'POST'})
  }

  async terminalSeq(runId) {
    const response = await boundedFetch(`${this.settings.baseUrl}/api/ai/events/${encodeURIComponent(runId)}?lastSeq=0`, {
      headers: {'X-Tenant-Id': this.settings.tenantId, 'X-User-Id': this.settings.userId, Accept: 'text/event-stream'},
      redirect: 'error'
    })
    const text = await response.text()
    let terminalSeq = 0
    let terminal = null
    for (const line of text.split(/\r?\n/)) {
      if (!line.startsWith('data:')) continue
      try {
        const frame = JSON.parse(line.slice(5))
        if ((frame.type === 'done' || frame.type === 'error') && Number.isSafeInteger(frame.seq)) {
          terminalSeq = Math.max(terminalSeq, frame.seq)
          terminal = frame
        }
      } catch {}
    }
    return {terminalSeq, terminal}
  }

  async proxyMode(mode) {
    const response = await boundedFetch(`${this.settings.proxyUrl}/__verification/mode`, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(mode)
    })
    return await response.json()
  }

  async proxyEvidence() {
    const response = await boundedFetch(`${this.settings.proxyUrl}/__verification/evidence`)
    return await response.json()
  }

  async release() {
    await boundedFetch(`${this.settings.proxyUrl}/__verification/release`, {method: 'POST'})
  }
}

function host(snapshot, runId) {
  return snapshot?.messages.find((message) => message.role === 'assistant' && message.runId === runId)
    ?? snapshot?.messages.filter((message) => message.role === 'assistant').at(-1) ?? null
}

function requestCounts(requests, runId = null) {
  const selected = runId ? requests.filter((request) => request.path.endsWith(`/${runId}`)) : requests
  return {
    chat: requests.filter((request) => request.path === '/api/ai/chat' && request.method === 'POST').length,
    events: selected.filter((request) => request.path.startsWith('/api/ai/events/') && request.probe !== '1').length,
    probes: selected.filter((request) => request.path.startsWith('/api/ai/events/') && request.probe === '1').length,
    history: requests.filter((request) => request.path.startsWith('/api/ai/history/')).length
  }
}

function cancelEvidence(response) {
  return {
    status: response.status,
    runId: response.body?.runId ?? null,
    cancelled: response.body?.cancelled === true,
    alreadyCancelled: response.body?.alreadyCancelled === true,
    alreadyTerminal: response.body?.alreadyTerminal === true,
    statusBeforeCancel: response.body?.statusBeforeCancel ?? null
  }
}

function probeEvidence(response) {
  return {
    status: response.status,
    ready: response.body?.ready === true,
    runStatus: response.body?.runStatus ?? response.body?.status ?? null,
    code: response.body?.code ?? null,
    transcriptDegraded: response.body?.transcriptDegraded === true,
    historyComplete: typeof response.body?.historyComplete === 'boolean' ? response.body.historyComplete : null,
    unconfirmed: response.body?.unconfirmed === true,
    cancelled: response.body?.cancelled === true
  }
}

function archiveEvidence(archive) {
  return {
    terminalSeq: archive.terminalSeq,
    type: archive.terminal?.type ?? null,
    runStatus: archive.terminal?.runStatus ?? null,
    code: archive.terminal?.code ?? null,
    cancelled: archive.terminal?.cancelled === true,
    unconfirmed: archive.terminal?.unconfirmed === true
  }
}

function commonCleanup(snapshot) {
  return snapshot?.preparingRun === null && snapshot?.pendingClientRequestId === null
    && snapshot?.activeStreamRunId === null && snapshot?.activeStreamParked === false
    && snapshot?.suspended === false && snapshot?.restoredPendingQueueLength === 0
    && snapshot?.activeForm === null && snapshot?.gateSubmissionCount === 0
    && snapshot?.busyRun === null && snapshot?.streamDriveCount === 0
}

async function waitRun(api, runId, statuses, timeoutMs) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const current = await api.probe(runId)
    const status = current.body?.runStatus ?? current.body?.status
    if (statuses.includes(status)) return current.body
    await sleep(50)
  }
  throw new VerificationFailure('AUTHORITATIVE_RUN_NOT_FORMED', {runId, statuses})
}

async function waitCurrentRunId(api, sessionId, timeoutMs) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const current = await api.current(sessionId)
    if (current.body?.found === true && typeof current.body?.runId === 'string') return current.body.runId
    await sleep(50)
  }
  throw new VerificationFailure('RUN_NOT_STARTED')
}

async function runChatProbeVariant(browser, api, settings, checks) {
  const prompt = settings.variant === 'chat-probe-failed'
    ? `context-overflow verification=${randomUUID()}`
    : settings.variant === 'chat-probe-cancelled'
      ? 'turn-text stall-headers=5000'
      : settings.variant === 'unconfirmed'
        ? `turn-text verification=${randomUUID()}`
        : `turn-text verification=${randomUUID()}`
  if (settings.variant === 'unconfirmed') {
    await browser.evaluate(`(async () => {
      const {aiApi} = await import('/src/api/ai.ts')
      window.__closeCount = 0
      const original = aiApi.chat
      aiApi.chat = (request, handlers, options) => original(request, {
        ...handlers,
        onClose: (info) => {
          window.__closeCount += 1
          handlers.onClose?.(info)
        }
      }, options)
      return true
    })()`)
  }
  await browser.startSend(prompt)
  if (settings.injectHistoryFailure === true) {
    await api.proxyMode({historyFailures: 2})
    await browser.evaluate(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      return ai.loadHistory()
    })()`)
  }
  if (settings.variant === 'chat-probe-cancelled') {
    const {sessionId} = await browser.snapshot()
    const runId = await waitCurrentRunId(api, sessionId, settings.timeoutMs)
    const cancelled = await api.cancel(runId)
    checks.push({id: 'fixture.real-cancel', pass: cancelled.status === 200 && cancelled.body?.cancelled === true, actual: cancelEvidence(cancelled)})
  }
  const result = await browser.sendResult()
  if (settings.variant === 'unconfirmed') {
    await browser.waitFor(`(() => {
      const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
      return ai.manualRecovery?.reason === 'unconfirmed' && ai.loading === false && ai.activeStreamRunId === null
    })()`, 'UNCONFIRMED_RECOVERY_NOT_PUBLISHED')
  }
  const snapshot = await browser.snapshot()
  const runId = snapshot.activeRunId
  const current = host(snapshot, runId)
  const expected = settings.variant === 'chat-probe-done'
    ? {outcome: 'ok', code: null, lastRoundOutcome: 'ok'}
    : settings.variant === 'chat-probe-cancelled'
      ? {outcome: 'cancelled', code: null, lastRoundOutcome: 'cancelled'}
      : {outcome: 'error', code: settings.variant === 'chat-probe-failed' ? 'CONTEXT_OVERFLOW' : 'AI_REDIS_UNAVAILABLE', lastRoundOutcome: settings.variant === 'unconfirmed' ? 'unconfirmed' : 'error'}
  checks.push({id: 'send.outcome-not-false-ok', pass: result?.outcome === expected.outcome, expected: expected.outcome, actual: result})
  checks.push({id: 'host.authoritative-terminal-sealed', pass: current?.done === true && current?.streaming === false, actual: current})
  checks.push({id: 'host.terminal-facts-preserved', pass: current?.code === expected.code
    && current?.stopped === (settings.variant === 'chat-probe-cancelled')
    && current?.unconfirmed === (settings.variant === 'unconfirmed'), expected, actual: current})
  checks.push({id: 'round.outcome-truthful', pass: snapshot.lastRoundOutcome === expected.lastRoundOutcome
    && snapshot.streamInterrupted === false, expected: expected.lastRoundOutcome, actual: snapshot})
  checks.push({id: 'round.transport-state-cleaned', pass: commonCleanup(snapshot), actual: snapshot})
  if (settings.variant === 'unconfirmed') {
    const counts = requestCounts(browser.requests, runId)
    checks.push({id: 'unconfirmed.special-recovery', pass: current?.errorCardBaseCode === null
      && snapshot.manualRecovery?.reason === 'unconfirmed', actual: snapshot})
    const historyBudget = settings.historyBudget ?? 3
    checks.push({id: 'unconfirmed.single-history-followup', pass: counts.history <= historyBudget,
      expected: `bounded history recovery within ${historyBudget} requests`, actual: counts})
    checks.push({id: 'unconfirmed.single-on-close', pass: await browser.evaluate('window.__closeCount', false) === 1,
      expected: 'main chat publishes exactly one onClose', actual: await browser.evaluate('window.__closeCount', false)})
  }
}

async function runDuplicateOutcomeVariant(browser, api, settings, checks) {
  const cancelledRun = settings.variant === 'duplicate-cancelled'
  await browser.startSend(cancelledRun ? 'turn-text stall-headers=5000' : `context-overflow verification=${randomUUID()}`)
  if (cancelledRun) {
    const deadline = Date.now() + settings.timeoutMs
    while (requestCounts(browser.requests).chat < 2) {
      if (Date.now() >= deadline) throw new VerificationFailure('DUPLICATE_REQUEST_NOT_FORMED')
      await sleep(50)
    }
    const currentSnapshot = await browser.snapshot()
    const proxy = await api.proxyEvidence()
    const runId = currentSnapshot.activeRunId ?? host(currentSnapshot, null)?.runId ?? proxy.duplicateRunId
      ?? await waitCurrentRunId(api, currentSnapshot.sessionId, settings.timeoutMs)
    if (!runId) throw new VerificationFailure('DUPLICATE_RUN_ID_NOT_FORMED')
    const cancelled = await api.cancel(runId)
    checks.push({id: 'fixture.real-cancel', pass: cancelled.status === 200 && cancelled.body?.cancelled === true, actual: cancelEvidence(cancelled)})
    await waitRun(api, runId, ['CANCELLED'], settings.timeoutMs)
  }
  const result = await browser.sendResult()
  const snapshot = await browser.snapshot()
  const finalRunId = snapshot.activeRunId ?? host(snapshot, null)?.runId ?? (await api.proxyEvidence()).duplicateRunId ?? null
  const current = host(snapshot, finalRunId)
  const counts = requestCounts(browser.requests, finalRunId)
  const expectedOutcome = cancelledRun ? 'cancelled' : 'error'
  checks.push({id: 'fixture.real-duplicate-request', pass: counts.chat === 2, actual: counts})
  checks.push({id: 'duplicate.outcome-after-authoritative-probe', pass: result?.outcome === expectedOutcome,
    expected: expectedOutcome, actual: result})
  checks.push({id: 'duplicate.terminal-facts-preserved', pass: current?.done === true && current?.streaming === false
    && current?.stopped === cancelledRun && current?.code === (cancelledRun ? null : 'CONTEXT_OVERFLOW')
    && snapshot.lastRoundOutcome === expectedOutcome, actual: {current, lastRoundOutcome: snapshot.lastRoundOutcome}})
  checks.push({id: 'duplicate.no-redundant-reattach', pass: counts.events === 0, actual: counts})
  checks.push({id: 'duplicate.transport-state-cleaned', pass: commonCleanup(snapshot), actual: snapshot})
}

async function main() {
  const settings = config()
  const evidenceDir = path.join(settings.outDir, 'ai-terminal-recovery', settings.scenario,
    `${new Date().toISOString().replace(/[:.]/g, '-')}-${randomUUID()}`)
  await mkdir(evidenceDir, {recursive: true})
  const checks = []
  const browser = new CdpPage(settings)
  const api = new ApiClient(settings)
  const summary = {
    scenario: settings.scenario,
    variant: settings.variant,
    status: 'FAIL',
    ...sourceFingerprint(),
    executorIdentity: settings.executorIdentity,
    isolation: {stubOnly: true, directPiniaWrites: false, directHistoryWrites: false, directRedisWrites: false, applicationSseForged: false},
    checks
  }
  try {
    if (settings.variant === 'combined-error') {
      summary.status = 'NOT_RUN'
      summary.notRunReason = 'The real backend emits transcriptDegraded only for a business-DONE terminal. Producing a second, different terminal error code on that same run would require forging the application terminal frame or direct history/Pinia writes, which this evidence forbids.'
      return
    }
    await browser.open()
    if (['chat-probe-done', 'chat-probe-failed', 'chat-probe-cancelled', 'unconfirmed'].includes(settings.variant)) {
      await runChatProbeVariant(browser, api, settings, checks)
    } else if (settings.variant === 'history-inflight-unconfirmed') {
      await browser.evaluate(`(() => {
        const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
        window.__terminalCalls = []
        const recover = ai.recoverUnconfirmedTerminal
        ai.recoverUnconfirmedTerminal = (message) => {
          window.__terminalCalls.push({method: 'recover', runId: message.runId})
          return recover(message)
        }
        const request = ai.requestTerminalHistoryRecovery
        ai.requestTerminalHistoryRecovery = (followup) => {
          window.__terminalCalls.push({method: 'request', runId: followup.runId})
          return request(followup)
        }
        const schedule = ai.scheduleHistoryRecovery
        ai.scheduleHistoryRecovery = (sessionId, followup) => {
          window.__terminalCalls.push({method: 'schedule', runId: followup?.runId ?? null, used: ai.autoRecoveryUsed.history ?? 0})
          const result = schedule(sessionId, followup)
          window.__terminalCalls.push({method: 'scheduled', runId: followup?.runId ?? null, used: ai.autoRecoveryUsed.history ?? 0, result})
          return result
        }
        const bounded = ai.canAutoRecoverBounded
        ai.canAutoRecoverBounded = (kind, attempt, onExhausted, operation) => bounded(kind, async () => {
          const result = await attempt()
          window.__terminalCalls.push({method: 'attempt', runId: operation?.terminalFollowupRunId ?? null, result, used: ai.autoRecoveryUsed.history ?? 0})
          return result
        }, onExhausted, operation)
        return true
      })()`, false)
      await runChatProbeVariant(browser, api, {...settings, variant: 'unconfirmed', historyBudget: 6, injectHistoryFailure: true}, checks)
      const settled = await browser.snapshot()
      checks.push({id: 'history-inflight.unconfirmed-manual-recovery', pass: settled.manualRecovery?.reason === 'unconfirmed'
        && settled.canSend === false && settled.lastRoundOutcome === 'unconfirmed',
      expected: 'ordinary history chain flushes into unconfirmed recovery and keeps sends blocked', actual: settled})
    } else if (settings.variant === 'duplicate') {
      await browser.startSend(`turn-text verification=${randomUUID()}`)
      const result = await browser.sendResult()
      await sleep(2_000)
      const snapshot = await browser.snapshot()
      const current = host(snapshot, snapshot.activeRunId)
      const counts = requestCounts(browser.requests, snapshot.activeRunId)
      checks.push({id: 'fixture.real-duplicate-request', pass: result?.outcome === 'ok' && counts.chat === 2, actual: {result, counts}})
      checks.push({id: 'duplicate.terminal-sealed', pass: current?.done === true && current?.streaming === false && snapshot.loading === false, actual: current})
      checks.push({id: 'duplicate.no-redundant-reattach', pass: counts.events === 0, expected: 'terminal probe seals without another SSE', actual: counts})
      checks.push({id: 'duplicate.transport-state-cleaned', pass: commonCleanup(snapshot), actual: snapshot})
    } else if (settings.variant === 'duplicate-failed' || settings.variant === 'duplicate-cancelled') {
      await runDuplicateOutcomeVariant(browser, api, settings, checks)
    } else if (settings.variant === 'snapshot-only-transcript') {
      await browser.startSend(`turn-text verification=${randomUUID()}`)
      await browser.sendResult()
      const before = await browser.snapshot()
      const runId = before.activeRunId
      const beforeHost = host(before, runId)
      const probe = await api.probe(runId)
      checks.push({id: 'fixture.real-transcript-degraded', pass: beforeHost?.transcriptDegraded === true
        && before.manualRecovery?.reason === 'transcript-degraded' && probe.body?.status === 'DONE', actual: {beforeHost, probe: probeEvidence(probe)}})
      await browser.evaluate(`(() => {
        const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
        ai.sealAuthoritativeTerminal(${JSON.stringify(runId)}, {snapshotStatus: 'DONE'}, ${JSON.stringify(beforeHost?.id)})
        return true
      })()`, false)
      const after = await browser.snapshot()
      const afterHost = host(after, runId)
      checks.push({id: 'snapshot-only.transcript-marker-preserved', pass: afterHost?.transcriptDegraded === true
        && after.manualRecovery?.reason === 'transcript-degraded', actual: {afterHost, manualRecovery: after.manualRecovery}})
      checks.push({id: 'snapshot-only.transcript-recovery-not-restarted', pass: requestCounts(browser.requests, runId).history === 1,
        expected: 'no automatic history followup', actual: requestCounts(browser.requests, runId)})
      await browser.reload()
      await browser.waitFor(`(() => {
        const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
        return ai?.historyLoaded === true && ai.manualRecovery?.reason === 'transcript-degraded'
      })()`, 'TRANSCRIPT_RECOVERY_NOT_PRESERVED_AFTER_RELOAD')
      const reloaded = await browser.snapshot()
      checks.push({id: 'snapshot-only.transcript-reload-preserved', pass: host(reloaded, runId)?.transcriptDegraded === true
        && reloaded.manualRecovery?.reason === 'transcript-degraded'
        && requestCounts(browser.requests, runId).history === 2
        && requestCounts(browser.requests, runId).events === 0,
      expected: 'one history per load, marker retained, no reattach', actual: {host: host(reloaded, runId), recovery: reloaded.manualRecovery, counts: requestCounts(browser.requests, runId)}})
    } else if (settings.variant === 'snapshot-only-unconfirmed') {
      await browser.evaluate(`(() => {
        const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
        window.__mergeEvidence = []
        const original = ai.mergeMirrorInFlightRound
        ai.mergeMirrorInFlightRound = (...args) => {
          const tail = args[1]?.at(-1)
          window.__mergeEvidence.push({inFlight: args[2], preserve: args[3], tail: tail ? {role: tail.role, runId: tail.runId, unconfirmed: tail.unconfirmed === true, transcriptDegraded: tail.transcriptDegraded === true} : null})
          return original(...args)
        }
        return true
      })()`, false)
      await runChatProbeVariant(browser, api, {...settings, variant: 'unconfirmed'}, checks)
      const before = await browser.snapshot()
      const runId = before.activeRunId
      const beforeHost = host(before, runId)
      const probe = await api.probe(runId)
      checks.push({id: 'fixture.real-unconfirmed', pass: beforeHost?.unconfirmed === true
        && beforeHost?.code === 'AI_REDIS_UNAVAILABLE' && probe.body?.status === 'FAILED',
      actual: {beforeHost, probe: probeEvidence(probe), merge: await browser.evaluate('window.__mergeEvidence', false)}})
      await browser.evaluate(`(() => {
        const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
        ai.sealAuthoritativeTerminal(${JSON.stringify(runId)}, {snapshotStatus: 'FAILED'}, ${JSON.stringify(beforeHost?.id)})
        return true
      })()`, false)
      await sleep(2_000)
      const after = await browser.snapshot()
      const afterHost = host(after, runId)
      checks.push({id: 'snapshot-only.unconfirmed-marker-and-code-preserved', pass: afterHost?.unconfirmed === true
        && afterHost?.code === 'AI_REDIS_UNAVAILABLE' && after.lastRoundOutcome === 'unconfirmed'
        && after.manualRecovery?.reason === 'unconfirmed', actual: {afterHost, lastRoundOutcome: after.lastRoundOutcome, manualRecovery: after.manualRecovery}})
      checks.push({id: 'snapshot-only.unconfirmed-history-not-restarted', pass: requestCounts(browser.requests, runId).history <= 3,
        expected: 'no additional recovery chain after the bounded attempts', actual: requestCounts(browser.requests, runId)})
    } else if (settings.variant === 'awaiting-input') {
      await browser.startSend(`turn-text verification=${randomUUID()}`)
      await browser.sendResult()
      const snapshot = await browser.snapshot()
      const runId = snapshot.activeRunId
      const archive = await api.terminalSeq(runId)
      checks.push({id: 'fixture.terminal-archive', pass: archive.terminalSeq > 0 && archive.terminal?.type === 'done', actual: archiveEvidence(archive)})
      const before = requestCounts(browser.requests, runId)
      const close = await browser.evaluate(`(async () => {
        const sse = await import('/src/api/sse.ts')
        return await sse.reattach(${JSON.stringify(runId)}, {}, {lastSeq: ${archive.terminalSeq}, awaitingInput: true, random: () => 0.5})
      })()`)
      const counts = requestCounts(browser.requests, runId)
      checks.push({id: 'awaiting-input.probe-before-settle', pass: counts.probes - before.probes === 1, actual: {before, after: counts}})
      checks.push({id: 'awaiting-input.terminal-close', pass: close?.terminal === true && close?.snapshotStatus === 'DONE', actual: close})
    } else if (settings.variant === 'snapshot-expired') {
      await browser.startSend(`turn-text verification=${randomUUID()}`)
      await browser.sendResult()
      const snapshot = await browser.snapshot()
      const runId = snapshot.activeRunId
      await waitRun(api, runId, ['DONE'], settings.timeoutMs)
      await api.proxyMode({snapshotExpired: true})
      const probe = await api.probe(runId)
      checks.push({id: 'snapshot-expired.status-from-archive', pass: probe.status === 200 && probe.body?.status === 'DONE' && probe.body?.runStatus === 'DONE', actual: probeEvidence(probe)})
      checks.push({id: 'snapshot-expired.terminal-additive-facts', pass: probe.body?.historyComplete === undefined
        && probe.body?.transcriptDegraded === false && probe.body?.unconfirmed === false && probe.body?.cancelled === false,
      expected: 'historyComplete omitted when the real terminal frame does not carry it', actual: probeEvidence(probe)})
    } else if (settings.variant === 'budget-gate') {
      await browser.startSend('turn-text stall-headers=30000')
      const runId = await browser.waitFor(`(() => {
        const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
        return ai.activeRunId || null
      })()`, 'RUN_NOT_STARTED')
      const reattachPromise = browser.evaluate(`(async () => {
        const sse = await import('/src/api/sse.ts')
        window.__budgetClose = await sse.reattach(${JSON.stringify(runId)}, {}, {lastSeq: 0, random: () => 0.5})
        return window.__budgetClose
      })()`)
      await browser.waitFor(`(() => {
        const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
        return ai.activeRunId === ${JSON.stringify(runId)}
      })()`, 'RUN_LOST')
      const deadline = Date.now() + settings.timeoutMs
      while (browser.requests.filter((request) => request.path === `/api/ai/events/${runId}` && request.probe !== '1').length < 5) {
        if (Date.now() >= deadline) throw new VerificationFailure('LAST_RECONNECT_NOT_FORMED')
        await sleep(50)
      }
      const cancelled = await api.cancel(runId)
      checks.push({id: 'fixture.cancel-before-last-attempt', pass: cancelled.status === 200 && cancelled.body?.cancelled === true, actual: cancelEvidence(cancelled)})
      await waitRun(api, runId, ['CANCELLED'], settings.timeoutMs)
      await api.release()
      const close = await reattachPromise
      const counts = requestCounts(browser.requests, runId)
      checks.push({id: 'budget.last-generation-probed', pass: counts.probes === 5 && close?.reconnects === 4, actual: {counts, close}})
      checks.push({id: 'budget.terminal-close', pass: close?.terminal === true && close?.snapshotStatus === 'CANCELLED', actual: close})
      await browser.sendResult()
    }
    summary.status = checks.length > 0 && checks.every((check) => check.pass) ? 'PASS' : 'FAIL'
  } catch (error) {
    summary.error = {name: String(error?.name ?? 'Error'), code: String(error?.code ?? 'UNEXPECTED_ERROR'), detail: error?.detail ?? null}
    if (settings.variant === 'history-inflight-unconfirmed') {
      try {
        summary.debug = {
          snapshot: await browser.snapshot(),
          terminalCalls: await browser.evaluate('window.__terminalCalls', false)
        }
      } catch {}
    }
  } finally {
    if (settings.variant === 'history-inflight-unconfirmed') {
      try {
        summary.terminalCalls = await browser.evaluate('window.__terminalCalls', false)
      } catch {}
    }
    summary.checks = checks
    summary.failedCheckIds = checks.filter((check) => !check.pass).map((check) => check.id)
    summary.requests = browser.requests
    summary.browserErrors = browser.errors
    await writeFile(path.join(evidenceDir, 'summary.json'), `${JSON.stringify(summary, null, 2)}\n`)
    await browser.close()
    try {
      await api.release()
    } catch {}
  }
  console.log(JSON.stringify({scenario: settings.scenario, variant: settings.variant, status: summary.status, evidenceDir, failedCheckIds: summary.failedCheckIds, error: summary.error ?? null}, null, 2))
  if (summary.status === 'FAIL') process.exitCode = 1
}

await main()
