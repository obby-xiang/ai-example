#!/usr/bin/env node

import {createHash, randomUUID} from 'node:crypto'
import {execFileSync} from 'node:child_process'
import {mkdir, writeFile} from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {setTimeout as sleep} from 'node:timers/promises'

const repoRoot = fileURLToPath(new URL('../..', import.meta.url))
const localHosts = new Set(['localhost', '127.0.0.1', '::1', '[::1]'])
const scenarioId = 'C-170'

class VerificationFailure extends Error {
  constructor(code) {
    super(code)
    this.code = code
  }
}

class Checks {
  items = []

  add(id, pass, expected, actual) {
    this.items.push({id, pass: pass === true, expected, actual})
  }

  require(id, pass, expected, actual) {
    this.add(id, pass, expected, actual)
    if (!pass) throw new VerificationFailure(id)
  }

  get failures() {
    return this.items.filter((item) => !item.pass)
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
  const productionFiles = [
    'backend/src/main/java/com/example/configmgr/ai/web/AiController.java',
    'frontend/src/api/sse.ts',
    'frontend/src/components/AiPanel/AiPanel.vue',
    'frontend/src/stores/ai.ts',
    'frontend/src/types/ai.ts'
  ]
  const diff = execFileSync('git', ['diff', '--', ...productionFiles], {cwd: repoRoot, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024})
  return {
    codeHead: execFileSync('git', ['rev-parse', 'HEAD'], {cwd: repoRoot, encoding: 'utf8'}).trim(),
    productionDiffSha256: sha256(diff),
    productionFiles
  }
}

function textEvidence(value) {
  return {length: Array.from(String(value ?? '')).length, sha256: sha256(String(value ?? ''))}
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

function httpUrl(name, loopbackOnly = false) {
  let parsed
  try {
    parsed = new URL(process.env[name]?.trim() ?? '')
  } catch {
    throw new Error(`${name} must be an absolute HTTP URL`)
  }
  if (parsed.protocol !== 'http:' || parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new Error(`${name} must not contain credentials, query or fragment`)
  }
  if (loopbackOnly && !localHosts.has(parsed.hostname)) throw new Error(`${name} must be loopback`)
  if (!localHosts.has(parsed.hostname) && process.env.EVAL_CONFIRM_REMOTE !== 'true') {
    throw new Error(`EVAL_CONFIRM_REMOTE=true is required for ${name}`)
  }
  return parsed.toString().replace(/\/$/, '')
}

function integerEnv(name, fallback, minimum, maximum) {
  const value = Number(process.env[name] ?? fallback)
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) throw new Error(`${name} must be in ${minimum}..${maximum}`)
  return value
}

function loadConfig() {
  if (process.env.SCENARIO !== scenarioId) throw new Error(`SCENARIO=${scenarioId} is required; one process runs exactly one scenario`)
  if ((process.env.LOCALE ?? 'zh-CN') !== 'zh-CN') throw new Error('This deterministic scenario requires LOCALE=zh-CN')
  for (const gate of ['EVAL_ISOLATED_ENV', 'EVAL_ALLOW_WRITES', 'EVAL_STUB_ONLY']) {
    if (process.env[gate] !== 'true') throw new Error(`${gate}=true is required`)
  }
  if (!process.env.TENANT_ID?.trim() || !process.env.USER_ID?.trim() || !process.env.EXECUTOR_IDENTITY?.trim()) {
    throw new Error('TENANT_ID, USER_ID and EXECUTOR_IDENTITY are required')
  }
  return {
    appUrl: httpUrl('APP_URL'),
    baseUrl: httpUrl('BASE_URL'),
    proxyUrl: httpUrl('PROXY_URL', true),
    cdpUrl: httpUrl('CDP_URL', true),
    tenantId: process.env.TENANT_ID.trim(),
    userId: process.env.USER_ID.trim(),
    executorIdentity: process.env.EXECUTOR_IDENTITY.trim(),
    outDir: outsidePath('OUT_DIR'),
    timeoutMs: integerEnv('TIMEOUT_MS', 45_000, 5_000, 120_000),
    pollMs: integerEnv('POLL_MS', 50, 10, 500),
    refreshObservationMs: integerEnv('REFRESH_OBSERVATION_MS', 20_000, 20_000, 30_000)
  }
}

const observationSource = String.raw`(() => {
  const state = window.__terminalRecoveryVerification = {samples: [], errors: [], truncated: false}
  const visible = (element) => !!(element && (element.offsetParent !== null || element.getClientRects().length > 0))
  const count = (selector) => [...document.querySelectorAll(selector)].filter(visible).length
  let previous = ''
  const sample = () => {
    try {
      const app = document.getElementById('app')
      const ai = app?.__vue_app__?.config.globalProperties.$pinia?._s.get('ai')
      if (!ai) return
      const value = {
        atMs: Date.now(),
        sessionId: ai.sessionId,
        activeRunId: ai.activeRunId ?? null,
        loading: ai.loading === true,
        restoring: ai.restoringRecovery === true,
        canSend: ai.canSend === true,
        historyLoaded: ai.historyLoaded === true,
        runSeq: {...(ai.runSeq ?? {})},
        noticeCode: ai.noticeCode ?? null,
        manualRecovery: ai.manualRecovery ? {scope: ai.manualRecovery.scope, reason: ai.manualRecovery.reason} : null,
        messages: ai.messages.map((message) => ({
          id: message.id,
          role: message.role,
          runId: message.runId ?? null,
          done: message.done === true,
          streaming: message.streaming === true,
          interrupted: message.interrupted === true,
          stopped: message.stopped === true,
          transcriptDegraded: message.transcriptDegraded === true,
          code: message.code ?? null,
          errorCardBaseCode: message.errorCard?.baseCode ?? null,
          timestampPresent: message.timestamp !== undefined && message.timestamp !== null && message.timestamp !== '',
          parts: message.parts.map((part) => part.kind === 'tool' ? {
            kind: part.kind,
            toolCallId: part.toolCallId,
            name: part.name,
            status: part.status,
            args: part.args ?? '',
            result: part.result ?? ''
          } : {kind: part.kind, text: part.text ?? ''})
        })),
        dom: {
          working: count('[data-testid="loading-breath"]'),
          recovering: count('[data-testid="recovery-loading"],[data-testid="collapsed-recovery-loading"]'),
          timestamps: count('[data-testid="msg-timestamp"]'),
          copyActions: count('[data-testid^="copy-msg-"]'),
          feedbackActions: count('[data-testid^="feedback-up-"]'),
          retryHistory: count('[data-testid="retry-history"]'),
          historyAlert: count('[data-testid="alert-history-error"]'),
          noticeBar: count('[data-testid="notice-bar"]')
        }
      }
      const degraded = value.messages.find((message) => message.role === 'assistant' && message.transcriptDegraded)
      if (degraded && !sessionStorage.getItem('__terminalRecoveryAutoReloaded')) {
        sessionStorage.setItem('__terminalRecoveryAutoReloaded', '1')
        sessionStorage.setItem('__terminalRecoveryLive', JSON.stringify({
          atMs: Date.now(),
          sessionId: value.sessionId,
          runId: degraded.runId,
          loading: value.loading,
          runSeq: value.runSeq,
          projection: {runId: degraded.runId, transcriptDegraded: true, timestampPresent: degraded.timestampPresent, parts: degraded.parts}
        }))
        location.reload()
        return
      }
      const serialized = JSON.stringify(value)
      if (serialized === previous) return
      previous = serialized
      if (state.samples.length >= 4096) {
        state.truncated = true
        return
      }
      state.samples.push(value)
    } catch (error) {
      if (state.errors.length < 32) state.errors.push(String(error?.name ?? 'Error'))
    }
  }
  let subscribed = false
  const installSynchronousObserver = () => {
    if (subscribed) return
    const ai = document.getElementById('app')?.__vue_app__?.config.globalProperties.$pinia?._s.get('ai')
    if (!ai) return
    subscribed = true
    ai.$subscribe((mutation, current) => {
      const degraded = current.messages.find((message) => message.role === 'assistant' && message.transcriptDegraded === true)
      if (!degraded || sessionStorage.getItem('__terminalRecoveryAutoReloaded')) return
      sessionStorage.setItem('__terminalRecoveryAutoReloaded', '1')
      sessionStorage.setItem('__terminalRecoveryLive', JSON.stringify({
        atMs: Date.now(),
        sessionId: current.sessionId,
        runId: degraded.runId,
        loading: current.loading,
        runSeq: {...(current.runSeq ?? {})},
        projection: {
          runId: degraded.runId,
          transcriptDegraded: true,
          timestampPresent: degraded.timestamp !== undefined && degraded.timestamp !== null && degraded.timestamp !== '',
          parts: degraded.parts.map((part) => part.kind === 'tool' ? {
            kind: part.kind,
            toolCallId: part.toolCallId,
            name: part.name,
            status: part.status,
            args: part.args ?? '',
            result: part.result ?? ''
          } : {kind: part.kind, text: part.text ?? ''})
        }
      }))
      debugger
    }, {flush: 'sync'})
  }
  document.addEventListener('DOMContentLoaded', () => {
    installSynchronousObserver()
    sample()
    new MutationObserver(sample).observe(document.documentElement, {subtree: true, childList: true, attributes: true, characterData: true})
  }, {once: true})
  setInterval(() => {
    installSynchronousObserver()
    sample()
  }, 5)
})()`

async function boundedFetch(url, options = {}, timeoutMs = 30_000) {
  return await fetch(url, {...options, signal: AbortSignal.timeout(timeoutMs)})
}

class CdpPage {
  constructor(config) {
    this.config = config
    this.nextId = 0
    this.waiters = new Map()
    this.targetId = null
    this.ws = null
    this.requests = []
    this.phase = 'initial'
    this.errors = []
    this.paused = false
  }

  async open() {
    const response = await boundedFetch(`${this.config.cdpUrl}/json/new?${encodeURIComponent(this.config.appUrl)}`, {method: 'PUT'})
    const target = await response.json()
    if (!response.ok || !target?.id || !target?.webSocketDebuggerUrl) throw new VerificationFailure('CDP_TARGET_CREATE_FAILED')
    this.targetId = target.id
    this.ws = new WebSocket(target.webSocketDebuggerUrl)
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new VerificationFailure('CDP_WEBSOCKET_OPEN_TIMEOUT')), 15_000)
      this.ws.onopen = () => {
        clearTimeout(timer)
        resolve()
      }
      this.ws.onerror = () => {
        clearTimeout(timer)
        reject(new VerificationFailure('CDP_WEBSOCKET_OPEN_FAILED'))
      }
    })
    this.ws.onmessage = (event) => this.onMessage(event)
    await this.send('Page.enable')
    await this.send('Runtime.enable')
    await this.send('Debugger.enable')
    await this.send('Network.enable')
    await this.send('Page.addScriptToEvaluateOnNewDocument', {
      source: `try { localStorage.setItem('cm.locale', 'zh-CN'); sessionStorage.setItem('ai-identity', JSON.stringify({tenantId: ${JSON.stringify(this.config.tenantId)}, userId: ${JSON.stringify(this.config.userId)}})) } catch (error) {}\n${observationSource}`
    })
    await this.send('Page.navigate', {url: this.config.appUrl})
    await this.waitFor(`(() => {
      const app = document.getElementById('app')
      const ai = app?.__vue_app__?.config.globalProperties.$pinia?._s.get('ai')
      return !!ai && ai.historyLoaded === true
    })()`, 'APPLICATION_NOT_READY')
    await this.evaluate(`(() => {
      const app = document.getElementById('app')
      const ai = app.__vue_app__.config.globalProperties.$pinia._s.get('ai')
      if (!ai.expanded) {
        const collapsed = document.querySelector('.h-full.flex.flex-col.items-center.cursor-pointer')
        if (collapsed) collapsed.click()
      }
      return true
    })()`, false)
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
      if (message.error) waiter.reject(new VerificationFailure('CDP_METHOD_ERROR'))
      else waiter.resolve(message.result)
      return
    }
    if (message.method === 'Network.requestWillBeSent') {
      try {
        const url = new URL(message.params.request.url)
        if (!url.pathname.startsWith('/api/')) return
        this.requests.push({
          atMs: Date.now(),
          phase: this.phase,
          method: message.params.request.method,
          path: url.pathname,
          lastSeq: url.searchParams.get('lastSeq'),
          probe: url.searchParams.get('probe')
        })
        if (this.requests.length > 2048) this.errors.push('NETWORK_EVIDENCE_LIMIT_EXCEEDED')
      } catch {
        this.errors.push('NETWORK_REQUEST_INVALID')
      }
    }
    if (message.method === 'Debugger.paused') this.paused = true
    if (message.method === 'Runtime.exceptionThrown' && this.errors.length < 64) this.errors.push('BROWSER_EXCEPTION')
  }

  send(method, params = {}) {
    const id = ++this.nextId
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.waiters.delete(id)
        reject(new VerificationFailure('CDP_METHOD_TIMEOUT'))
      }, 30_000)
      this.waiters.set(id, {resolve, reject, timer})
      this.ws.send(JSON.stringify({id, method, params}))
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
      try {
        const value = await this.evaluate(expression, false)
        if (value) return value
      } catch {}
      await sleep(this.config.pollMs)
    }
    throw new VerificationFailure(code)
  }

  async waitForPaused(code, timeoutMs = this.config.timeoutMs) {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      if (this.paused) return
      await sleep(this.config.pollMs)
    }
    throw new VerificationFailure(code)
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

  async sendPrompt(prompt) {
    await this.waitFor(`(() => {
      const ai = document.getElementById('app')?.__vue_app__?.config.globalProperties.$pinia?._s.get('ai')
      return !!ai && ai.historyLoaded === true && ai.loading === false && ai.restoringRecovery === false
    })()`, 'COMPOSER_NOT_READY')
    await this.evaluate(`document.querySelector('[data-testid="composer-input"]').focus()`, false)
    await this.send('Input.insertText', {text: prompt})
    await this.click('[data-testid="send-btn"]')
  }

  async snapshot() {
    return await this.evaluate(`(() => {
      const ai = document.getElementById('app')?.__vue_app__?.config.globalProperties.$pinia?._s.get('ai')
      const observation = window.__terminalRecoveryVerification ?? null
      return observation ? {
        sessionId: ai.sessionId,
        activeRunId: ai.activeRunId ?? null,
        runSeq: {...(ai.runSeq ?? {})},
        current: observation.samples.at(-1) ?? null,
        samples: observation.samples,
        errors: observation.errors,
        truncated: observation.truncated
      } : null
    })()`, false)
  }

  async mirror(sessionId) {
    return await this.evaluate(`(() => {
      const entries = []
      for (let index = 0; index < sessionStorage.length; index += 1) {
        const key = sessionStorage.key(index)
        if (!key || !key.startsWith('ai-mirror:') || !(key === 'ai-mirror:${sessionId}' || key.endsWith(':${sessionId}'))) continue
        try {
          const value = JSON.parse(sessionStorage.getItem(key) ?? 'null')
          entries.push({
            inFlight: value?.inFlight === true,
            lastRunId: value?.lastRunId ?? null,
            runSeq: value?.runSeq ?? {},
            messageCount: Array.isArray(value?.messages) ? value.messages.length : null,
            transcriptDegraded: Array.isArray(value?.messages) && value.messages.some((message) => message?.transcriptDegraded === true)
          })
        } catch {
          entries.push({inFlight: null, lastRunId: null, runSeq: {}, messageCount: null, transcriptDegraded: null})
        }
      }
      return entries
    })()`, false)
  }

  async reload() {
    await this.send('Page.reload', {ignoreCache: true})
    await this.waitFor(`(() => {
      const ai = document.getElementById('app')?.__vue_app__?.config.globalProperties.$pinia?._s.get('ai')
      return !!ai && Array.isArray(ai.messages) && ai.messages.length > 0
    })()`, 'RELOAD_NOT_READY')
  }

  async screenshot(file) {
    const result = await this.send('Page.captureScreenshot', {format: 'png', captureBeyondViewport: false})
    await writeFile(file, Buffer.from(result.data, 'base64'))
  }

  async close() {
    try {
      if (this.ws) this.ws.close()
    } catch {}
    if (this.targetId) {
      try {
        await boundedFetch(`${this.config.cdpUrl}/json/close/${this.targetId}`, {}, 5_000)
      } catch {}
    }
  }
}

class ApiClient {
  constructor(config) {
    this.config = config
  }

  async get(urlPath) {
    const response = await boundedFetch(`${this.config.baseUrl}${urlPath}`, {
      headers: {'X-Tenant-Id': this.config.tenantId, 'X-User-Id': this.config.userId},
      redirect: 'error'
    })
    const body = await response.json().catch(() => null)
    if (!response.ok) throw new VerificationFailure(`PUBLIC_API_${response.status}`)
    return body?.data ?? body
  }

  async run(runId) {
    return await this.get(`/api/ai/runs/${encodeURIComponent(runId)}`)
  }

  async probe(runId) {
    return await this.get(`/api/ai/events/${encodeURIComponent(runId)}?probe=1`)
  }

  async terminalSeq(runId) {
    const response = await boundedFetch(`${this.config.baseUrl}/api/ai/events/${encodeURIComponent(runId)}?lastSeq=0`, {
      headers: {'X-Tenant-Id': this.config.tenantId, 'X-User-Id': this.config.userId, Accept: 'text/event-stream'},
      redirect: 'error'
    })
    const text = await response.text()
    let terminalSeq = 0
    for (const line of text.split(/\r?\n/)) {
      if (!line.startsWith('data:')) continue
      try {
        const frame = JSON.parse(line.slice(5))
        if ((frame.type === 'done' || frame.type === 'error') && Number.isSafeInteger(frame.seq)) {
          terminalSeq = Math.max(terminalSeq, frame.seq)
        }
      } catch {}
    }
    return terminalSeq
  }

  async history(sessionId) {
    return await this.get(`/api/ai/history/${encodeURIComponent(sessionId)}?latest=100`)
  }

  async tasks() {
    return await this.get('/api/tasks?page=0&size=100')
  }
}

function assistantProjection(snapshot, runId) {
  const message = snapshot?.current?.messages.find((item) => item.role === 'assistant' && item.runId === runId)
  return message ? {
    runId: message.runId,
    transcriptDegraded: message.transcriptDegraded,
    timestampPresent: message.timestampPresent,
    parts: message.parts
  } : null
}

function projectionEvidence(projection) {
  return projection ? {
    runId: projection.runId,
    transcriptDegraded: projection.transcriptDegraded,
    timestampPresent: projection.timestampPresent,
    partCount: projection.parts.length,
    toolParts: projection.parts.filter((part) => part.kind === 'tool').map((part) => ({
      toolCallId: part.toolCallId,
      name: part.name,
      status: part.status
    })),
    contentSha256: sha256(projection.parts)
  } : null
}

function sanitizedSamples(samples) {
  return samples.map((sample) => ({
    atMs: sample.atMs,
    loading: sample.loading,
    restoring: sample.restoring,
    historyLoaded: sample.historyLoaded,
    noticeCode: sample.noticeCode,
    manualRecovery: sample.manualRecovery,
    messages: sample.messages.map((message) => ({
      role: message.role,
      runId: message.runId,
      done: message.done,
      streaming: message.streaming,
      interrupted: message.interrupted,
      stopped: message.stopped,
      transcriptDegraded: message.transcriptDegraded,
      timestampPresent: message.timestampPresent,
      contentSha256: sha256(message.parts)
    })),
    dom: sample.dom
  }))
}

function terminalRegressions(samples, runId) {
  let terminalSeen = false
  const violations = []
  for (const sample of samples) {
    const host = sample.messages.find((item) => item.role === 'assistant' && item.runId === runId)
    if (!host) continue
    const terminal = host.done && !host.streaming && !sample.loading && !sample.restoring
    if (terminal) terminalSeen = true
    if (terminalSeen && (!host.done || host.streaming || sample.loading || sample.restoring)) {
      violations.push({atMs: sample.atMs, done: host.done, streaming: host.streaming, loading: sample.loading, restoring: sample.restoring})
    }
  }
  return violations
}

function domRegressions(samples) {
  let terminalSeen = false
  const violations = []
  for (const sample of samples) {
    const dom = sample.dom
    const terminal = dom.timestamps > 0 && dom.copyActions > 0 && dom.feedbackActions > 0 && dom.working === 0 && dom.recovering === 0
    if (terminal) terminalSeen = true
    if (terminalSeen && (dom.working > 0 || dom.recovering > 0 || dom.timestamps === 0 || dom.copyActions === 0 || dom.feedbackActions === 0)) {
      violations.push({atMs: sample.atMs, dom})
    }
  }
  return violations
}

function requestCounts(requests, phase) {
  const selected = requests.filter((request) => request.phase === phase)
  return {
    chat: selected.filter((request) => request.path === '/api/ai/chat' && request.method === 'POST').length,
    events: selected.filter((request) => request.path.startsWith('/api/ai/events/') && !request.probe).length,
    probes: selected.filter((request) => request.path.startsWith('/api/ai/events/') && request.probe === '1').length,
    history: selected.filter((request) => request.path.startsWith('/api/ai/history/')).length,
    confirms: selected.filter((request) => request.path.includes('/confirm') && request.method === 'POST').length,
    toolResults: selected.filter((request) => request.path.includes('/frontend-result') && request.method === 'POST').length,
    businessWrites: selected.filter((request) => request.path.startsWith('/api/tasks') && request.method !== 'GET').length
  }
}

async function proxyEvidence(config) {
  const response = await boundedFetch(`${config.proxyUrl}/__verification/evidence`)
  return await response.json()
}

async function releaseHeldEof(config) {
  await boundedFetch(`${config.proxyUrl}/__verification/release`, {method: 'POST'})
}

async function main() {
  const config = loadConfig()
  const evidenceDir = path.join(config.outDir, 'ai-terminal-recovery', scenarioId,
    `${new Date().toISOString().replace(/[:.]/g, '-')}-${randomUUID()}`)
  await mkdir(evidenceDir, {recursive: true})
  const checks = new Checks()
  const browser = new CdpPage(config)
  const api = new ApiClient(config)
  const summary = {
    scenario: scenarioId,
    status: 'FAIL',
    ...sourceFingerprint(),
    executorIdentity: config.executorIdentity,
    isolation: {stubOnly: true, writesAllowed: true, directPiniaWrites: false, directHistoryWrites: false, directRedisWrites: false, applicationSseForged: false},
    checks: checks.items
  }
  try {
    const tasksBefore = await api.tasks()
    const proxyBaseline = await proxyEvidence(config)
    await browser.open()
    const prompt = `turn-text verification=${randomUUID()}`
    browser.phase = 'live'
    await browser.sendPrompt(prompt)
    await browser.waitForPaused('LIVE_DEGRADED_TERMINAL_NOT_FORMED')
    await browser.reload()
    const live = await browser.waitFor(`(() => {
      const raw = sessionStorage.getItem('__terminalRecoveryLive')
      const ai = document.getElementById('app')?.__vue_app__?.config.globalProperties.$pinia?._s.get('ai')
      if (!raw || !ai || ai.messages.length === 0) return null
      try { return JSON.parse(raw) } catch { return null }
    })()`, 'LIVE_DEGRADED_TERMINAL_NOT_FORMED')
    const sessionId = live.sessionId
    const runId = live.runId
    const liveProjection = live.projection
    for (const request of browser.requests) {
      if (request.atMs >= live.atMs) request.phase = 'refresh1'
    }
    browser.phase = 'refresh1'
    checks.require('live.body-formed', liveProjection?.parts.some((part) => part.kind === 'text' && part.text.trim() !== ''),
      'real deterministic text', projectionEvidence(liveProjection))
    let run
    const runDeadline = Date.now() + config.timeoutMs
    for (;;) {
      run = await api.run(runId)
      if (run?.snapshot?.status === 'DONE') break
      if (Date.now() >= runDeadline) throw new VerificationFailure('RUN_DONE_NOT_FORMED')
      await sleep(config.pollMs)
    }
    const [probe, history, terminalSeq] = await Promise.all([
      api.probe(runId),
      api.history(sessionId),
      api.terminalSeq(runId)
    ])
    checks.require('fixture.run-done', run?.snapshot?.status === 'DONE' && probe?.status === 'DONE',
      'public run and reattach probe both DONE', {run: run?.snapshot?.status, probe: probe?.status})
    checks.require('fixture.history-has-no-done', Array.isArray(history?.messages)
      && history.messages.every((message) => String(message?.outcome ?? '').toUpperCase() !== 'DONE'),
    'history contains no DONE evidence', history?.messages?.map((message) => ({type: message?.type, outcome: message?.outcome ?? null})))
    checks.require('fixture.terminal-seq-positive', Number.isSafeInteger(terminalSeq) && terminalSeq > 0, 'archiveMaxSeq > 0', terminalSeq)
    await browser.waitFor(`(() => {
      for (let index = 0; index < sessionStorage.length; index += 1) {
        const key = sessionStorage.key(index)
        if (!key || !key.startsWith('ai-mirror:') || !(key === 'ai-mirror:${sessionId}' || key.endsWith(':${sessionId}'))) continue
        try {
          const mirror = JSON.parse(sessionStorage.getItem(key) ?? 'null')
          return mirror?.inFlight === true && mirror?.lastRunId === '${runId}'
            && mirror?.messages?.some((message) => message?.transcriptDegraded === true)
        } catch {}
      }
      return false
    })()`, 'MIRROR_INFLIGHT_DEGRADED_NOT_FORMED')
    const mirrors = await browser.mirror(sessionId)
    const mirror = mirrors.find((entry) => entry.lastRunId === runId)
    const lastSeq = Number(mirror?.runSeq?.[runId] ?? 0)
    checks.require('fixture.mirror-inflight-degraded', mirror?.inFlight === true && mirror?.transcriptDegraded === true,
      'inFlight=true + transcriptDegraded=true', mirror)
    checks.require('fixture.lastseq-at-or-past-terminal', lastSeq >= terminalSeq && lastSeq > 0,
      `lastSeq >= archiveMaxSeq (${terminalSeq})`, lastSeq)
    const liveProxy = await proxyEvidence(config)
    checks.require('fixture.real-terminal-eof-hold', liveProxy.degradedTerminalEofHolds - proxyBaseline.degradedTerminalEofHolds === 1
      && liveProxy.chatResponses - proxyBaseline.chatResponses === 1,
    'one unchanged degraded terminal response with delayed EOF', {baseline: proxyBaseline, live: liveProxy})
    checks.require('fixture.real-transcript-write-failures', liveProxy.transcriptAppendRejects > proxyBaseline.transcriptAppendRejects
      && liveProxy.proxyErrors.length === proxyBaseline.proxyErrors.length,
    'real transcript append rejection and no new proxy errors', {baseline: proxyBaseline, live: liveProxy})

    browser.phase = 'refresh1'
    const refresh1Started = live.atMs
    await releaseHeldEof(config)
    await sleep(config.refreshObservationMs)
    const refresh1 = await browser.snapshot()
    const refresh1Projection = assistantProjection(refresh1, runId)
    const refresh1Requests = requestCounts(browser.requests, 'refresh1')
    const refresh1Terminal = refresh1.current?.messages.find((message) => message.role === 'assistant' && message.runId === runId)
    const storeViolations = terminalRegressions(refresh1.samples, runId)
    const domViolations = domRegressions(refresh1.samples)
    checks.add('refresh1.host-done-not-streaming', refresh1Terminal?.done === true && refresh1Terminal?.streaming === false,
      'done=true + streaming=false', refresh1Terminal && {done: refresh1Terminal.done, streaming: refresh1Terminal.streaming})
    checks.add('refresh1.loading-and-recovery-stopped', refresh1.current?.loading === false && refresh1.current?.restoring === false,
      'loading=false + restoringRecovery=false', {loading: refresh1.current?.loading, restoring: refresh1.current?.restoring})
    checks.add('refresh1.host-does-not-reopen', storeViolations.length === 0, 'no terminal to streaming/restoring regression', storeViolations.slice(0, 16))
    checks.add('refresh1.dom-does-not-alternate', domViolations.length === 0,
      'no recovery/working/timestamp/action alternation after terminal presentation', domViolations.slice(0, 16))
    checks.add('refresh1.no-reattach-after-authoritative-terminal', refresh1Requests.events === 0, 'zero reattach requests', refresh1Requests)
    const recentEvents = browser.requests.filter((request) => request.phase === 'refresh1'
      && request.path.startsWith('/api/ai/events/') && !request.probe && request.atMs >= Date.now() - 2_000).length
    checks.add('refresh1.reattach-bounded-and-zero', refresh1Requests.events <= 4 && recentEvents === 0,
      'at most one bounded legacy cycle and zero trailing reattach', {total: refresh1Requests.events, recent: recentEvents})
    checks.add('refresh1.no-automatic-history-loop', refresh1Requests.history === 1, 'exactly the initial loadHistory request', refresh1Requests.history)
    checks.add('refresh1.body-preserved', canonicalJson(refresh1Projection?.parts) === canonicalJson(liveProjection?.parts),
      'same target text projection as live', projectionEvidence(refresh1Projection))
    checks.add('refresh1.transcript-warning-and-manual-retry-remain', refresh1.current?.noticeCode === null
      && refresh1.current?.manualRecovery?.reason === 'transcript-degraded'
      && refresh1.current?.dom?.noticeBar === 0 && refresh1.current?.dom?.historyAlert === 1
      && refresh1.current?.dom?.retryHistory === 1 && refresh1.current?.canSend === true,
    'dedicated warning plus one manual retry', {
      noticeCode: refresh1.current?.noticeCode,
      manualRecovery: refresh1.current?.manualRecovery,
      dom: refresh1.current?.dom
    })
    checks.add('refresh1.terminal-dom-actions-remain', refresh1.current?.dom?.working === 0
      && refresh1.current?.dom?.recovering === 0 && refresh1.current?.dom?.timestamps > 0
      && refresh1.current?.dom?.copyActions > 0 && refresh1.current?.dom?.feedbackActions > 0,
    'no recovery/working; timestamp/copy/feedback visible', refresh1.current?.dom)
    checks.add('refresh1.no-new-side-effects', refresh1Requests.chat === 0 && refresh1Requests.confirms === 0
      && refresh1Requests.toolResults === 0 && refresh1Requests.businessWrites === 0,
    'zero chat/confirm/tool-result/business writes during refresh', refresh1Requests)
    checks.add('refresh1.observation-bounded', !refresh1.truncated && refresh1.errors.length === 0 && browser.errors.length === 0,
      'bounded samples and no browser/CDP errors', {truncated: refresh1.truncated, errors: [...refresh1.errors, ...browser.errors]})
    await writeFile(path.join(evidenceDir, 'samples-refresh1.json'), `${JSON.stringify(sanitizedSamples(refresh1.samples), null, 2)}\n`)
    await browser.screenshot(path.join(evidenceDir, 'refresh1.png'))

    browser.phase = 'refresh2'
    await browser.reload()
    await sleep(3_000)
    const refresh2 = await browser.snapshot()
    const refresh2Projection = assistantProjection(refresh2, runId)
    const refresh2Requests = requestCounts(browser.requests, 'refresh2')
    const refresh2Terminal = refresh2.current?.messages.find((message) => message.role === 'assistant' && message.runId === runId)
    const refresh2DomViolations = domRegressions(refresh2.samples)
    checks.add('refresh2.host-done-not-streaming', refresh2Terminal?.done === true && refresh2Terminal?.streaming === false,
      'done=true + streaming=false', refresh2Terminal && {done: refresh2Terminal.done, streaming: refresh2Terminal.streaming})
    checks.add('refresh2.loading-and-recovery-stopped', refresh2.current?.loading === false && refresh2.current?.restoring === false,
      'loading=false + restoringRecovery=false', {loading: refresh2.current?.loading, restoring: refresh2.current?.restoring})
    checks.add('refresh2.dom-does-not-alternate', refresh2DomViolations.length === 0,
      'no recovery/working/timestamp/action alternation', refresh2DomViolations.slice(0, 16))
    checks.add('refresh2.no-reattach', refresh2Requests.events === 0, 'zero reattach requests', refresh2Requests)
    checks.add('refresh2.no-automatic-history-loop', refresh2Requests.history === 1, 'exactly the initial loadHistory request', refresh2Requests.history)
    checks.add('refresh2.body-preserved', canonicalJson(refresh2Projection?.parts) === canonicalJson(liveProjection?.parts),
      'same target text projection as live', projectionEvidence(refresh2Projection))
    checks.add('refresh2.transcript-warning-and-manual-retry-remain', refresh2.current?.noticeCode === null
      && refresh2.current?.manualRecovery?.reason === 'transcript-degraded'
      && refresh2.current?.dom?.noticeBar === 0 && refresh2.current?.dom?.historyAlert === 1
      && refresh2.current?.dom?.retryHistory === 1 && refresh2.current?.canSend === true,
    'dedicated warning plus one manual retry', {
      noticeCode: refresh2.current?.noticeCode,
      manualRecovery: refresh2.current?.manualRecovery,
      dom: refresh2.current?.dom
    })
    checks.add('refresh2.terminal-dom-actions-remain', refresh2.current?.dom?.working === 0
      && refresh2.current?.dom?.recovering === 0 && refresh2.current?.dom?.timestamps > 0
      && refresh2.current?.dom?.copyActions > 0 && refresh2.current?.dom?.feedbackActions > 0,
    'no recovery/working; timestamp/copy/feedback visible', refresh2.current?.dom)
    checks.add('refresh2.no-new-side-effects', refresh2Requests.chat === 0 && refresh2Requests.confirms === 0
      && refresh2Requests.toolResults === 0 && refresh2Requests.businessWrites === 0,
    'zero chat/confirm/tool-result/business writes during refresh', refresh2Requests)
    browser.phase = 'manual-retry'
    await browser.click('[data-testid="retry-history"]')
    await browser.waitFor(`(() => {
      const ai = document.getElementById('app')?.__vue_app__?.config.globalProperties.$pinia?._s.get('ai')
      return ai?.restoringRecovery === false
    })()`, 'MANUAL_RETRY_NOT_SETTLED')
    await sleep(3_000)
    const manualRetry = await browser.snapshot()
    const manualRetryRequests = requestCounts(browser.requests, 'manual-retry')
    checks.add('manual-retry.exactly-one-history-reread', manualRetryRequests.history === 1,
      'one real click causes exactly one history GET', manualRetryRequests)
    checks.add('manual-retry.no-reattach-or-followup-loop', manualRetryRequests.events === 0 && manualRetryRequests.probes === 0,
      'no reattach/probe/followup loop after the manual reread', manualRetryRequests)
    checks.add('manual-retry.warning-remains-truthful', manualRetry.current?.noticeCode === null
      && manualRetry.current?.manualRecovery?.reason === 'transcript-degraded'
      && manualRetry.current?.dom?.noticeBar === 0
      && manualRetry.current?.dom?.historyAlert === 1 && manualRetry.current?.dom?.retryHistory === 1,
    'persistent transcript fault keeps one dedicated warning and retry', manualRetry.current)
    checks.add('manual-retry.body-and-send-state-stable', canonicalJson(assistantProjection(manualRetry, runId)?.parts) === canonicalJson(liveProjection?.parts)
      && manualRetry.current?.loading === false && manualRetry.current?.restoring === false && manualRetry.current?.canSend === true,
    'same body, no recovery activity, composer unlocked', assistantProjection(manualRetry, runId))
    checks.add('manual-retry.no-new-side-effects', manualRetryRequests.chat === 0 && manualRetryRequests.confirms === 0
      && manualRetryRequests.toolResults === 0 && manualRetryRequests.businessWrites === 0,
    'zero chat/confirm/tool-result/business writes caused by retry', manualRetryRequests)
    await writeFile(path.join(evidenceDir, 'samples-manual-retry.json'), `${JSON.stringify(sanitizedSamples(manualRetry.samples), null, 2)}\n`)
    await browser.screenshot(path.join(evidenceDir, 'manual-retry.png'))

    const tasksAfter = await api.tasks()
    checks.add('business-fingerprint-unchanged', canonicalJson(tasksAfter) === canonicalJson(tasksBefore),
      'public task page unchanged', {before: textEvidence(canonicalJson(tasksBefore)), after: textEvidence(canonicalJson(tasksAfter))})
    const totalCounts = requestCounts(browser.requests, 'live')
    checks.add('live-only-one-chat-and-no-tool-result', totalCounts.chat === 1 && totalCounts.toolResults === 0,
      'one real chat and zero frontend tool results', totalCounts)
    checks.add('refresh2.observation-bounded', !refresh2.truncated && refresh2.errors.length === 0 && browser.errors.length === 0,
      'bounded samples and no browser/CDP errors', {truncated: refresh2.truncated, errors: [...refresh2.errors, ...browser.errors]})
    await writeFile(path.join(evidenceDir, 'samples-refresh2.json'), `${JSON.stringify(sanitizedSamples(refresh2.samples), null, 2)}\n`)
    await writeFile(path.join(evidenceDir, 'network.json'), `${JSON.stringify(browser.requests, null, 2)}\n`)
    await writeFile(path.join(evidenceDir, 'fixture.json'), `${JSON.stringify({
      sessionId,
      runId,
      runStatus: run?.snapshot?.status,
      historyEntries: history?.messages?.length,
      archiveMaxSeq: terminalSeq,
      mirror: mirror && {inFlight: mirror.inFlight, transcriptDegraded: mirror.transcriptDegraded, lastSeq},
      proxy: liveProxy,
      refresh1Started
    }, null, 2)}\n`)
    await browser.screenshot(path.join(evidenceDir, 'refresh2.png'))
    summary.status = checks.failures.length === 0 ? 'PASS' : 'FAIL'
  } catch (error) {
    summary.error = {name: String(error?.name ?? 'Error'), code: String(error?.code ?? 'UNEXPECTED_ERROR')}
    try {
      const debug = await browser.evaluate(`(() => ({
        keys: Object.keys(sessionStorage),
        live: sessionStorage.getItem('__terminalRecoveryLive'),
        reloaded: sessionStorage.getItem('__terminalRecoveryAutoReloaded'),
        observation: window.__terminalRecoveryVerification ?? null,
        current: (() => {
          const ai = document.getElementById('app')?.__vue_app__?.config.globalProperties.$pinia?._s.get('ai')
          return ai ? {sessionId: ai.sessionId, activeRunId: ai.activeRunId, loading: ai.loading, messages: ai.messages} : null
        })()
      }))()`, false)
      const safeDebug = {
        keys: debug.keys,
        live: debug.live ? textEvidence(debug.live) : null,
        reloaded: debug.reloaded,
        observation: debug.observation ? {
          samples: sanitizedSamples(debug.observation.samples ?? []),
          errors: debug.observation.errors,
          truncated: debug.observation.truncated
        } : null,
        current: debug.current ? {
          sessionId: debug.current.sessionId,
          activeRunId: debug.current.activeRunId,
          loading: debug.current.loading
        } : null
      }
      await writeFile(path.join(evidenceDir, 'failure-debug.json'), `${JSON.stringify(safeDebug, null, 2)}\n`)
      if (debug.current?.sessionId) {
        await writeFile(path.join(evidenceDir, 'failure-mirror.json'),
          `${JSON.stringify(await browser.mirror(debug.current.sessionId), null, 2)}\n`)
      }
      await browser.screenshot(path.join(evidenceDir, 'failure.png'))
    } catch {}
  } finally {
    summary.checks = checks.items
    summary.failedCheckIds = checks.failures.map((item) => item.id)
    await writeFile(path.join(evidenceDir, 'summary.json'), `${JSON.stringify(summary, null, 2)}\n`)
    await browser.close()
    try {
      await releaseHeldEof(config)
    } catch {}
  }
  console.log(JSON.stringify({scenario: scenarioId, status: summary.status, evidenceDir, failedCheckIds: summary.failedCheckIds, error: summary.error ?? null}, null, 2))
  if (summary.status !== 'PASS') process.exitCode = 1
}

await main()
