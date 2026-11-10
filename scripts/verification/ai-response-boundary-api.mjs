#!/usr/bin/env node

import {spawn, execFileSync} from 'node:child_process'
import {createHash, randomUUID} from 'node:crypto'
import fs from 'node:fs'
import {appendFile, lstat, mkdir, open, readFile, readdir, realpath, rm, stat, writeFile} from 'node:fs/promises'
import net from 'node:net'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

const repoRoot = fileURLToPath(new URL('../..', import.meta.url))
const cases = new Set(['late-reasoning', 'second-tool-aggregate', 'multi-choice', 'cancel-before-complete', 'standard-no-reasoning', 'confirm-step-timeline'])
const healthyCases = new Set(['late-reasoning', 'second-tool-aggregate', 'multi-choice', 'standard-no-reasoning', 'confirm-step-timeline'])
const localHosts = new Set(['localhost', '127.0.0.1', '::1', '[::1]'])
const transcriptErrorCodes = new Set(['AI_TRANSCRIPT_INCOMPLETE', 'AI_TRANSCRIPT_WRITE_FAILED'])
const toolFrameTypes = new Set(['tool_start', 'tool_result', 'confirm_request', 'confirm_decision', 'frontend_tool_request', 'frontend_tool_result'])
const lateReasoningPieces = [
  '迟到思考甲：工具 finish 已到达，但完整响应尚未结束；',
  '迟到思考乙：这两片与末尾 usage 必须先收齐，再释放工具聚合。'
]
const multiChoicePieces = ['多选甲-A：', '多选乙-A：', '甲-B；', '乙-B；', '甲-C。', '乙-C。']
const standardTextBlueprint = '命名轮蓝本：这是一段确定性文本，用于验证纯文本流的帧序完整性（start 恰 1 / delta ≥ 1 / done 恰 1），以及 done 之后零帧的迟到帧闸门。文本按 24 字符切片发送，切片数由本串长度决定，不依赖模型。'
const standardTextPieces = standardTextBlueprint.match(/[\s\S]{1,24}/g) ?? []
const startedAt = new Date().toISOString()

class VerificationFailure extends Error {}

function checkValueEvidence(value, depth = 0) {
  if (value === null || typeof value === 'boolean') return value
  if (typeof value === 'number') return Number.isSafeInteger(value) || Number.isFinite(value) ? value : null
  if (typeof value === 'string') {
    return /^[A-Za-z][A-Za-z0-9_.:/-]{0,127}$/.test(value)
      ? value
      : {sha256: sha256(value), length: Array.from(value).length}
  }
  if (Array.isArray(value)) {
    return {
      count: value.length,
      sha256: sha256(value),
      items: value.slice(0, 32).map((item) => checkValueEvidence(item, depth + 1)),
      truncated: value.length > 32
    }
  }
  if (isRecord(value)) {
    if (depth >= 5) return {sha256: sha256(value)}
    const entries = Object.entries(value)
    return {
      ...Object.fromEntries(entries.slice(0, 64).map(([key, item]) => [key.slice(0, 128), checkValueEvidence(item, depth + 1)])),
      ...(entries.length > 64 ? {truncated: true, fieldCount: entries.length, sha256: sha256(value)} : {})
    }
  }
  return null
}

class Checks {
  constructor() {
    this.items = []
  }

  add(id, pass, expected, actual) {
    this.items.push({
      id,
      pass: pass === true,
      expected: checkValueEvidence(expected),
      actual: checkValueEvidence(actual)
    })
  }

  require(id, pass, expected, actual) {
    this.add(id, pass, expected, actual)
    if (pass !== true) throw new VerificationFailure(`${id}: expected ${JSON.stringify(expected)}, actual ${JSON.stringify(actual)}`)
  }

  get failures() {
    return this.items.filter((item) => !item.pass)
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

function isInside(parent, candidate) {
  const relative = path.relative(path.resolve(parent), path.resolve(candidate))
  return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative))
}

function requiredOutsidePath(name, optional = false) {
  const raw = process.env[name]?.trim() ?? ''
  if (!raw && optional) return null
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

function parseHttpUrl(name, value) {
  let parsed
  try {
    parsed = new URL(value)
  } catch {
    throw new Error(`${name} must be an absolute HTTP URL`)
  }
  if (parsed.protocol !== 'http:' || parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new Error(`${name} must be HTTP without credentials, query, or fragment`)
  }
  if (!localHosts.has(parsed.hostname)) throw new Error(`${name} must be loopback; real providers and remote API keys are forbidden`)
  return parsed.toString().replace(/\/$/, '')
}

function loadConfig() {
  const caseName = (process.env.CASE ?? '').trim()
  if (!cases.has(caseName) || caseName.includes(',')) throw new Error(`CASE must select exactly one of ${[...cases].join(', ')}`)
  if (process.env.ROUNDS && process.env.ROUNDS !== '1') throw new Error('ROUNDS is unsupported; invoke once per CASE variant')
  for (const gate of ['EVAL_ISOLATED_ENV', 'EVAL_ALLOW_WRITES', 'EVAL_STUB_ONLY', 'REASONING_DISPLAY_ENABLED', 'REASONING_PERSIST_CONTENT']) {
    if (process.env[gate] !== 'true') throw new Error(`${gate}=true is required`)
  }
  if (!process.env.EXECUTOR_IDENTITY?.trim()) throw new Error('EXECUTOR_IDENTITY must identify the actual executing model')
  if (!process.env.TENANT_ID?.trim() || !process.env.USER_ID?.trim()) throw new Error('TENANT_ID and USER_ID must be explicit')
  const managedInput = process.env.START_SERVICES
  if (!['true', 'false'].includes(managedInput)) throw new Error('START_SERVICES=true|false is required')
  const managed = managedInput === 'true'
  const base = managed
    ? `http://127.0.0.1:${integerEnv('BACKEND_PORT', 18530, 1024, 65535)}`
    : parseHttpUrl('BASE_URL', process.env.BASE_URL ?? '')
  return {
    caseName,
    managed,
    base,
    backendPort: integerEnv('BACKEND_PORT', 18530, 1024, 65535),
    stubPort: integerEnv('STUB_PORT', 18599, 1024, 65535),
    tenantId: process.env.TENANT_ID.trim(),
    userId: process.env.USER_ID.trim(),
    executorIdentity: process.env.EXECUTOR_IDENTITY.trim(),
    finalTextLength: process.env.FINAL_TEXT_LENGTH
      ? integerEnv('FINAL_TEXT_LENGTH', 415, 32, 4096)
      : null,
    outDir: requiredOutsidePath('OUT_DIR'),
    backendJar: managed ? path.resolve(process.env.BACKEND_JAR ?? '') : null,
    stubScript: requiredOutsidePath('STUB_SCRIPT', true),
    javaBin: process.env.JAVA_BIN?.trim() || 'java',
    redisHost: process.env.REDIS_HOST?.trim() || '127.0.0.1',
    redisPort: integerEnv('REDIS_PORT', 6379, 1, 65535),
    stubDumpDir: managed ? null : requiredOutsidePath('STUB_DUMP_DIR'),
    backendLogFile: managed ? null : requiredOutsidePath('BACKEND_LOG_FILE'),
    stubLogFile: managed ? null : requiredOutsidePath('STUB_LOG_FILE'),
    timeoutMs: integerEnv('TIMEOUT_MS', 45_000, 1_000, 120_000),
    runTimeoutMs: integerEnv('RUN_TIMEOUT_MS', 120_000, 10_000, 600_000),
    bootTimeoutMs: integerEnv('BOOT_TIMEOUT_MS', 90_000, 5_000, 300_000),
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

async function prepareEvidenceDirectory(outDir) {
  const resolvedRepo = await realpath(repoRoot)
  const ancestor = await nearestExistingAncestor(outDir)
  const resolvedAncestor = await realpath(ancestor)
  if (isInside(resolvedRepo, resolvedAncestor)) throw new Error('OUT_DIR existing ancestor resolves inside the repository')
  const target = path.resolve(resolvedAncestor, path.relative(ancestor, outDir))
  if (isInside(resolvedRepo, target)) throw new Error('OUT_DIR target resolves inside the repository')
  await mkdir(outDir, {recursive: true})
  const resolvedOut = await realpath(outDir)
  if (isInside(await realpath(repoRoot), resolvedOut)) throw new Error('OUT_DIR resolves inside the repository')
  return resolvedOut
}

function codeHead() {
  if (process.env.CODE_HEAD?.trim()) return process.env.CODE_HEAD.trim()
  try {
    return execFileSync('git', ['rev-parse', 'HEAD'], {cwd: repoRoot, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore']}).trim()
  } catch {
    return 'UNKNOWN'
  }
}

function dataOf(response) {
  return response.body?.data ?? response.body
}

function businessCode(body) {
  const code = body?.code ?? body?.data?.code
  return typeof code === 'string' && /^[A-Z][A-Z0-9_]{0,127}$/.test(code) ? code : null
}

function errorName(error) {
  return typeof error?.name === 'string' && /^[A-Za-z][A-Za-z0-9]*$/.test(error.name) ? error.name : 'Error'
}

function safeFailure(error) {
  if (error instanceof VerificationFailure) {
    const id = /^([a-z][a-z0-9.-]{0,159}):/.exec(error.message)?.[1] ?? 'verification'
    return `${id}: assertion failed; see failedAssertions`
  }
  return `UNEXPECTED_ERROR: ${errorName(error)}`
}

class Evidence {
  constructor(directory) {
    this.directory = directory
    this.sequence = 0
  }

  async init() {
    await mkdir(this.directory, {recursive: true})
  }

  async json(name, value) {
    await writeFile(path.join(this.directory, name), `${JSON.stringify(value, null, 2)}\n`, 'utf8')
  }

  async append(name, value) {
    this.sequence += 1
    await appendFile(path.join(this.directory, name), `${JSON.stringify({sequence: this.sequence, atMs: Date.now(), ...value})}\n`, 'utf8')
  }
}

function responseSummary(label, body) {
  const data = body?.data ?? body
  const summary = {code: businessCode(body)}
  if (label === 'session.create') summary.sessionIdText = data?.sessionIdText ?? null
  if (label === 'ai.cancel') {
    summary.cancelled = data?.cancelled ?? null
    summary.alreadyCancelled = data?.alreadyCancelled ?? null
    summary.statusBeforeCancel = data?.statusBeforeCancel ?? null
  }
  if (label.startsWith('cleanup.delete')) {
    summary.complete = data?.complete ?? null
    summary.deferredCount = Array.isArray(data?.deferred) ? data.deferred.length : null
    summary.errorCount = Array.isArray(data?.errors) ? data.errors.length : null
    summary.aborted = data?.aborted ?? null
  }
  if (label === 'ai.run') {
    summary.exists = data?.exists ?? null
    summary.status = data?.snapshot?.status ?? null
  }
  if (label === 'ai.history') {
    summary.messageCount = Array.isArray(data?.messages) ? data.messages.length : null
    summary.hasMore = data?.hasMore ?? null
  }
  if (label === 'ai.health') {
    summary.available = data?.available ?? null
    summary.redisAvailable = data?.redis?.available ?? null
  }
  return summary
}

function boundedToken(value) {
  return typeof value === 'string' && /^[A-Za-z0-9_.:/-]{1,256}$/.test(value) ? value : null
}

function textEvidence(value) {
  if (value === null || value === undefined) return null
  return {
    sha256: sha256(value),
    length: typeof value === 'string' ? Array.from(value).length : null
  }
}

function digestEvidence(value) {
  return isRecord(value) && /^[a-f0-9]{64}$/.test(value.sha256)
    && Number.isSafeInteger(value.length) && value.length >= 0
    ? {sha256: value.sha256, length: value.length}
    : null
}

function digestMatches(value, text) {
  return value?.sha256 === sha256(text) && value?.length === Array.from(text).length
}

function publicRequestSummary(body) {
  if (!isRecord(body)) return null
  return {
    sessionId: boundedToken(body.sessionId),
    clientRequestId: boundedToken(body.clientRequestId),
    contextIsNull: body.context === null,
    message: textEvidence(body.message)
  }
}

function publicRequestTarget(urlPath) {
  const parsed = new URL(urlPath, 'http://verification.local')
  const query = {}
  for (const key of ['latest', 'lastSeq', 'page', 'size']) {
    const value = parsed.searchParams.get(key)
    if (value !== null && /^\d{1,10}$/.test(value)) query[key] = value
  }
  return {path: parsed.pathname, query}
}

function usageEvidence(usage) {
  if (!isRecord(usage)) return null
  const summary = {}
  for (const key of ['promptTokens', 'completionTokens', 'totalTokens', 'cachedTokens', 'reasoningTokens']) {
    summary[key] = Number.isSafeInteger(usage[key]) ? usage[key] : null
  }
  summary.est = usage.est === true
  summary.source = boundedToken(usage.source)
  return summary
}

function publicFrameEvidence(frame) {
  if (!isRecord(frame)) return null
  const summary = {}
  for (const key of ['type', 'runId', 'kind', 'code', 'name', 'toolCallId', 'status']) {
    if (frame[key] !== undefined) summary[key] = boundedToken(frame[key])
  }
  if (Number.isSafeInteger(frame.seq)) summary.seq = frame.seq
  for (const key of ['cancelled', 'executed', 'transcriptDegraded', 'historyComplete', 'unconfirmed']) {
    if (frame[key] !== undefined) summary[key] = frame[key] === true
  }
  if (frame.text !== undefined) summary.text = textEvidence(frame.text)
  if (frame.usage !== undefined) summary.usage = usageEvidence(frame.usage)
  return summary
}

class ApiClient {
  constructor(config, evidence) {
    this.config = config
    this.evidence = evidence
    this.requestSequence = 0
  }

  headers(json = false) {
    return {
      'X-Tenant-Id': this.config.tenantId,
      'X-User-Id': this.config.userId,
      ...(json ? {'Content-Type': 'application/json'} : {})
    }
  }

  async request(label, urlPath, options = {}) {
    const id = ++this.requestSequence
    const method = options.method ?? 'GET'
    let response
    try {
      response = await fetch(`${this.config.base}${urlPath}`, {
        method,
        headers: {...this.headers(options.json === true), ...(options.headers ?? {})},
        body: options.body,
        redirect: 'error',
        signal: AbortSignal.timeout(options.timeoutMs ?? this.config.timeoutMs)
      })
    } catch (error) {
      await this.evidence.append('public-requests.jsonl', {id, label, method, ...publicRequestTarget(urlPath), error: errorName(error)})
      throw error
    }
    const text = await response.text()
    let body = null
    try {
      body = text === '' ? null : JSON.parse(text)
    } catch {
      body = null
    }
    await this.evidence.append('public-requests.jsonl', {
      id,
      label,
      method,
      ...publicRequestTarget(urlPath),
      request: publicRequestSummary(options.publicBody),
      response: {httpStatus: response.status, ...responseSummary(label, body)}
    })
    const result = {status: response.status, body, text}
    if (options.expected && !options.expected.includes(response.status)) {
      throw new VerificationFailure(`${label}: HTTP ${response.status}, code=${businessCode(body)}`)
    }
    return result
  }

  async openSse(label, urlPath, options = {}) {
    const id = ++this.requestSequence
    let response
    try {
      response = await fetch(`${this.config.base}${urlPath}`, {
        method: options.method ?? 'GET',
        headers: {...this.headers(options.json === true), Accept: 'text/event-stream'},
        body: options.body,
        redirect: 'error',
        signal: AbortSignal.timeout(this.config.runTimeoutMs)
      })
    } catch (error) {
      await this.evidence.append('public-requests.jsonl', {
        id, label, method: options.method ?? 'GET', ...publicRequestTarget(urlPath), request: publicRequestSummary(options.publicBody), error: errorName(error)
      })
      throw error
    }
    await this.evidence.append('public-requests.jsonl', {
      id,
      label,
      method: options.method ?? 'GET',
      ...publicRequestTarget(urlPath),
      request: publicRequestSummary(options.publicBody),
      response: {httpStatus: response.status, contentType: response.headers.get('content-type') ?? ''}
    })
    if (!response.ok || !(response.headers.get('content-type') ?? '').includes('text/event-stream')) {
      throw new VerificationFailure(`${label}: expected SSE, HTTP ${response.status}`)
    }
    return response
  }
}

async function taskBaseline(api) {
  const entries = []
  const ids = []
  let total = null
  for (let pageNumber = 0; pageNumber < 100; pageNumber += 1) {
    const response = await api.request('business.tasks', `/api/tasks?page=${pageNumber}&size=100`, {expected: [200]})
    const page = dataOf(response)
    if (!isRecord(page) || !Array.isArray(page.content)) throw new VerificationFailure('business.tasks: missing content array')
    const pageTotal = Number(page.totalElements)
    if (!Number.isSafeInteger(pageTotal) || pageTotal < 0) throw new VerificationFailure('business.tasks: invalid totalElements')
    if (total === null) total = pageTotal
    if (total !== pageTotal) throw new VerificationFailure('business.tasks: total changed during bounded pagination')
    for (const entry of page.content) {
      const id = Number(entry?.task?.id ?? entry?.id)
      if (!Number.isSafeInteger(id) || id <= 0 || ids.includes(id)) throw new VerificationFailure('business.tasks: invalid or duplicate task id')
      ids.push(id)
      entries.push(entry)
    }
    if (entries.length >= total || page.content.length === 0) break
    if (pageNumber === 99) throw new VerificationFailure('business.tasks: pagination limit reached')
  }
  if (total === null || entries.length !== total) throw new VerificationFailure(`business.tasks: incomplete ${entries.length}/${total ?? 'unknown'}`)
  const jobs = []
  for (const taskId of [...ids].sort((left, right) => left - right)) {
    const response = await api.request('business.jobs', `/api/tasks/${taskId}/jobs`, {expected: [200]})
    const value = dataOf(response)
    if (!Array.isArray(value)) throw new VerificationFailure(`business.jobs: task ${taskId} response is not an array`)
    jobs.push({taskId, value})
  }
  return {
    complete: true,
    taskTotal: total,
    taskIds: ids.sort((left, right) => left - right),
    jobIds: jobs.flatMap((entry) => entry.value.map((job) => Number(job?.id))).filter(Number.isSafeInteger).sort((left, right) => left - right),
    tasksSha256: sha256(entries),
    jobsSha256: sha256(jobs)
  }
}

function sameBusinessSnapshot(left, right) {
  return left && right && canonicalJson(left) === canonicalJson(right)
}

function parseSseBlock(block) {
  const data = []
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
  }
  if (data.length === 0) return null
  try {
    const frame = JSON.parse(data.join('\n'))
    return isRecord(frame) ? frame : {type: 'invalid'}
  } catch {
    return {type: 'invalid'}
  }
}

class RunDriver {
  constructor(context) {
    this.context = context
    this.runId = null
    this.lastSeq = 0
    this.terminal = null
    this.frames = []
    this.drivenToolCalls = new Set()
  }

  async processBlock(block, channel) {
    const frame = parseSseBlock(block)
    if (!frame) return
    if (frame.type !== 'invalid') {
      if (this.runId && frame.runId && frame.runId !== this.runId) throw new VerificationFailure('cross-run public SSE frame')
      if (frame.type === 'start') {
        if (typeof frame.runId !== 'string' || !frame.runId) throw new VerificationFailure('start frame has no runId')
        this.runId = frame.runId
        this.context.runId = frame.runId
      }
      if (frame.seq !== undefined && frame.seq !== null) {
        if (!Number.isSafeInteger(frame.seq) || frame.seq <= this.lastSeq) {
          throw new VerificationFailure(`non-increasing public SSE seq ${frame.seq} after ${this.lastSeq}`)
        }
        this.lastSeq = frame.seq
      }
    }
    const entry = {channel, observedAtMs: Date.now(), blockSha256: sha256(block), frame}
    this.frames.push(entry)
    if (this.frames.length > 2048) throw new VerificationFailure('public SSE frame count exceeds 2048')
    await this.context.evidence.append('sse.jsonl', {
      channel,
      observedAtMs: entry.observedAtMs,
      blockSha256: entry.blockSha256,
      frame: publicFrameEvidence(frame)
    })
    if (frame.type === 'invalid') {
      this.context.checks.add('sse.valid-data-frame', false, 'valid JSON object public SSE data frame', {
        blockSha256: entry.blockSha256
      })
      return
    }
    if (this.context.config.caseName === 'confirm-step-timeline' && frame.type === 'frontend_tool_request'
      && !this.drivenToolCalls.has(frame.toolCallId)) {
      this.drivenToolCalls.add(frame.toolCallId)
      await driveConfirmStepFrontendTool(this.context, {...frame, runId: frame.runId ?? this.runId})
    }
    if (this.terminal) {
      this.context.checks.add('sse.no-data-after-terminal', false,
        'no data frames after the first terminal frame', publicFrameEvidence(frame))
    } else if (frame.type === 'done' || frame.type === 'error') {
      this.terminal = frame
      this.context.terminal = frame
    }
  }

  async pump(response, channel) {
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffered = ''
    let reachedEof = false
    try {
      while (true) {
        const {done, value} = await reader.read()
        if (done) {
          reachedEof = true
          buffered += decoder.decode()
          if (buffered.trim()) await this.processBlock(buffered, channel)
          break
        }
        buffered += decoder.decode(value, {stream: true})
        if (buffered.length > 256 * 1024 && !/\r?\n\r?\n/.test(buffered)) {
          throw new VerificationFailure('single public SSE event exceeds 256 KiB')
        }
        let separator
        while ((separator = /\r?\n\r?\n/.exec(buffered)) !== null) {
          const block = buffered.slice(0, separator.index)
          buffered = buffered.slice(separator.index + separator[0].length)
          await this.processBlock(block, channel)
        }
      }
    } finally {
      if (!reachedEof) await reader.cancel().catch(() => {})
    }
  }

  async start(message) {
    const context = this.context.config.caseName === 'confirm-step-timeline'
      ? {taskType: 'EXPORT', step: 'SELECT_DEFS', taskId: String(this.context.fixtureTaskId)}
      : null
    const publicBody = {sessionId: this.context.sessionId, message, context, clientRequestId: this.context.clientRequestId}
    let response = await this.context.api.openSse('ai.chat', '/api/ai/chat', {
      method: 'POST', json: true, body: JSON.stringify(publicBody), publicBody
    })
    await this.pump(response, 'chat')
    for (let attempt = 0; !this.terminal && attempt < 32; attempt += 1) {
      if (!this.runId) throw new VerificationFailure('chat ended before start/runId without a terminal frame')
      await new Promise((resolve) => setTimeout(resolve, this.context.config.pollMs))
      const suffix = this.lastSeq > 0 ? `?lastSeq=${this.lastSeq}` : ''
      response = await this.context.api.openSse('ai.events', `/api/ai/events/${encodeURIComponent(this.runId)}${suffix}`)
      await this.pump(response, 'events')
    }
    if (!this.terminal) throw new VerificationFailure('public SSE did not reach a terminal frame within 32 reads')
    return this.terminal
  }
}

function frontendToolArguments(frame) {
  try {
    const args = typeof frame.args === 'string' ? JSON.parse(frame.args) : frame.args
    if (!isRecord(args)) throw new VerificationFailure('confirm-step.frontend-args-invalid')
    return args
  } catch (error) {
    if (error instanceof VerificationFailure) throw error
    throw new VerificationFailure('confirm-step.frontend-args-invalid')
  }
}

async function driveConfirmStepFrontendTool(context, frame) {
  const args = frontendToolArguments(frame)
  let result
  if (frame.name === 'confirm_step') {
    const step = typeof args.step === 'string' && args.step ? args.step : 'EXPORT'
    await context.api.request('task.confirm-step', `/api/tasks/${context.fixtureTaskId}/step`, {
      method: 'PUT', json: true, body: JSON.stringify({step}), expected: [200]
    })
    result = JSON.stringify({step, defCode: 'CURRENCY', conditions: {fields: []}, remaining: 0, jobs: [], state: 'ACTIVE'})
  } else if (frame.name === 'generative_form' && args.form?.purpose === 'SET_CONDITIONS') {
    result = JSON.stringify({conditions: ''})
  } else {
    throw new VerificationFailure(`confirm-step.unsupported-frontend-tool-${frame.name}`)
  }
  const response = await context.api.request('ai.frontend-tool-result', '/api/ai/frontend-tool-result', {
    method: 'POST',
    json: true,
    body: JSON.stringify({runId: frame.runId, toolCallId: frame.toolCallId, result, source: 'api-verification', outcome: 'OK'}),
    expected: [200]
  })
  context.frontendResults.push({name: frame.name, toolCallId: frame.toolCallId, accepted: dataOf(response)?.accepted === true})
}

async function readJsonLines(file) {
  const info = await stat(file)
  if (info.size > 8 * 1024 * 1024) throw new VerificationFailure('upstream events exceed 8 MiB')
  const text = await readFile(file, 'utf8')
  const events = []
  for (const line of text.split(/\r?\n/)) {
    if (!line.trim()) continue
    const value = JSON.parse(line)
    if (!isRecord(value)) throw new VerificationFailure(`invalid JSONL event in ${path.basename(file)}`)
    events.push(value)
    if (events.length > 4098) throw new VerificationFailure('upstream event count exceeds 4098')
  }
  return events
}

async function readJsonFile(file) {
  const info = await stat(file)
  if (info.size > 1024 * 1024) throw new VerificationFailure('upstream JSON file exceeds 1 MiB')
  return JSON.parse(await readFile(file, 'utf8'))
}

async function firstStubToolChunk(config) {
  const names = (await readdir(config.stubDumpDir)).filter((name) => /^req-\d{3}\.events\.jsonl$/.test(name)).sort()
  for (const name of names) {
    const events = await readJsonLines(path.join(config.stubDumpDir, name))
    for (const event of events) {
      if (event.type !== 'upstream-chunk') continue
      if ((event.choices ?? []).some((choice) => (choice?.toolCalls ?? []).length > 0)) {
        return {file: name, event}
      }
    }
  }
  return null
}

async function cancelAfterToolChunk(context, driver) {
  const deadline = Date.now() + context.config.runTimeoutMs
  while (!driver.terminal && Date.now() < deadline) {
    const toolChunk = await firstStubToolChunk(context.config)
    if (toolChunk && driver.runId) {
      const cancelRequestedAtMs = Date.now()
      const response = await context.api.request('ai.cancel', `/api/ai/cancel/${encodeURIComponent(driver.runId)}`, {
        method: 'POST', json: true, expected: [200]
      })
      const body = dataOf(response)
      context.cancelEvidence = {
        upstreamRequestId: /^req-\d{3}/.exec(toolChunk.file)?.[0] ?? null,
        upstreamSequence: toolChunk.event.sequence,
        upstreamWrittenAtMs: toolChunk.event.writtenAtMs,
        cancelRequestedAtMs,
        cancelAcceptedAtMs: Date.now(),
        cancelled: body?.cancelled ?? null,
        alreadyCancelled: body?.alreadyCancelled ?? null
      }
      context.checks.require('cancel.after-upstream-tool-chunk', context.cancelEvidence.cancelAcceptedAtMs >= toolChunk.event.writtenAtMs,
        'public cancel accepted after the actual upstream tool chunk', context.cancelEvidence)
      context.checks.require('cancel.accepted', body?.cancelled === true || body?.alreadyCancelled === true,
        'cancelled=true or alreadyCancelled=true', body?.cancelled ?? body?.alreadyCancelled ?? null)
      await context.evidence.json('cancel.json', context.cancelEvidence)
      return
    }
    await new Promise((resolve) => setTimeout(resolve, context.config.pollMs))
  }
  if (!driver.terminal) throw new VerificationFailure('cancel-before-complete: no observable upstream tool chunk and runId before timeout')
}

function waitExit(child, timeoutMs) {
  if (child.exitCode !== null || child.signalCode !== null) {
    return Promise.resolve({code: child.exitCode, signal: child.signalCode})
  }
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      child.removeListener('exit', onExit)
      reject(new Error('child exit timeout'))
    }, timeoutMs)
    function onExit(code, signal) {
      clearTimeout(timer)
      resolve({code, signal})
    }
    child.once('exit', onExit)
  })
}

async function portIsOpen(port) {
  return new Promise((resolve) => {
    const socket = net.connect({host: '127.0.0.1', port})
    const finish = (value) => {
      socket.destroy()
      resolve(value)
    }
    socket.setTimeout(750)
    socket.once('connect', () => finish(true))
    socket.once('timeout', () => finish(false))
    socket.once('error', () => finish(false))
  })
}

async function assertPortFree(port) {
  if (await portIsOpen(port)) throw new VerificationFailure(`managed port ${port} is already occupied`)
}

function childEnv(extra = {}) {
  const env = {}
  for (const name of ['PATH', 'LANG', 'HOME', 'USERPROFILE', 'TMPDIR', 'TEMP', 'TMP', 'SystemRoot', 'WINDIR', 'COMSPEC', 'PATHEXT']) {
    if (process.env[name]) env[name] = process.env[name]
  }
  return {...env, ...extra}
}

class ServiceManager {
  constructor(context) {
    this.context = context
    this.children = new Map()
    this.logFds = new Map()
  }

  spawnTracked(name, command, args, logFile, env, cwd) {
    const fd = fs.openSync(logFile, 'a')
    this.logFds.set(name, fd)
    const child = spawn(command, args, {cwd, env, stdio: ['ignore', fd, fd]})
    this.children.set(name, child)
    child.on('error', (error) => {
      fs.appendFileSync(logFile, `\n[MANAGED] ${name} spawn error: ${errorName(error)}\n`)
    })
    child.on('exit', (code, signal) => {
      fs.appendFileSync(logFile, `\n[MANAGED] ${name} exited code=${code} signal=${signal}\n`)
    })
    return child
  }

  async start() {
    const config = this.context.config
    if (!config.managed) return
    const jarStat = await stat(config.backendJar)
    if (!jarStat.isFile()) throw new VerificationFailure('BACKEND_JAR is not a file')
    await assertPortFree(config.stubPort)
    await assertPortFree(config.backendPort)
    const directories = {
      stub: path.join(this.context.runDirectory, 'upstream-live'),
      backendWork: path.join(this.context.runDirectory, 'backend-work'),
      files: path.join(this.context.runDirectory, 'files'),
      h2: path.join(this.context.runDirectory, 'h2')
    }
    for (const directory of Object.values(directories)) await mkdir(directory, {recursive: true})
    config.stubDumpDir = directories.stub
    config.stubLogFile = path.join(this.context.runDirectory, 'stub.log')
    config.backendLogFile = path.join(this.context.runDirectory, 'backend.log')
    this.context.logOffsets = {backend: 0, stub: 0}
    this.directories = directories
    const stubScript = config.stubScript ?? path.join(repoRoot, 'scripts', 'ci', 'stub-upstream.mjs')
    const routes = path.join(repoRoot, 'scripts', 'ci', 'stub-routes.json')
    this.spawnTracked('stub', process.execPath,
      [stubScript, '--port', String(config.stubPort), '--dir', config.stubDumpDir, '--routes', routes],
      config.stubLogFile, childEnv(), this.context.runDirectory)
    await this.waitStub()
    const h2Path = path.join(directories.h2, 'response-boundary').replace(/\\/g, '/')
    const args = [
      '-jar', config.backendJar,
      `--server.port=${config.backendPort}`,
      `--spring.datasource.url=jdbc:h2:file:${h2Path};DB_CLOSE_DELAY=-1`,
      '--spring.datasource.username=sa',
      '--spring.datasource.password=',
      `--spring.data.redis.host=${config.redisHost}`,
      `--spring.data.redis.port=${config.redisPort}`,
      `--app.file.storage-path=${directories.files}`,
      '--app.ai.reasoning.display-enabled=true',
      '--app.ai.reasoning.persist-content=true',
      '--logging.level.com.example.configmgr=DEBUG'
    ]
    this.spawnTracked('backend', config.javaBin, args, config.backendLogFile, childEnv({
      AI_API_KEY: 'response-boundary-stub-placeholder',
      AI_BASE_URL: `http://127.0.0.1:${config.stubPort}`,
      AI_MODEL: 'ci-stub'
    }), directories.backendWork)
    await this.waitBackend()
    await this.context.evidence.json('managed-services.json', {
      managed: true,
      isolatedH2: true,
      isolatedBackendWork: true,
      stubOnly: true,
      placeholderKey: true,
      backendPort: config.backendPort,
      stubPort: config.stubPort,
      reasoningDisplayEnabled: true,
      reasoningPersistContent: true
    })
  }

  async waitStub() {
    const deadline = Date.now() + this.context.config.bootTimeoutMs
    while (Date.now() < deadline) {
      const child = this.children.get('stub')
      if (child.exitCode !== null || child.signalCode !== null) throw new VerificationFailure('managed stub exited before readiness')
      try {
        const response = await fetch(`http://127.0.0.1:${this.context.config.stubPort}/models`, {signal: AbortSignal.timeout(2_000)})
        if (response.status === 200) {
          await response.arrayBuffer()
          return
        }
      } catch {
        // Bounded startup polling only; final failure remains visible.
      }
      await new Promise((resolve) => setTimeout(resolve, this.context.config.pollMs))
    }
    throw new VerificationFailure('managed stub did not become ready')
  }

  async waitBackend() {
    const deadline = Date.now() + this.context.config.bootTimeoutMs
    while (Date.now() < deadline) {
      const child = this.children.get('backend')
      if (child.exitCode !== null || child.signalCode !== null) throw new VerificationFailure('managed backend exited before readiness')
      try {
        const ready = await this.context.api.request('health.ready', '/health/ready', {timeoutMs: 2_000})
        const ai = await this.context.api.request('ai.health', '/api/ai/health', {timeoutMs: 2_000})
        if (ready.status === 200 && ai.status === 200 && dataOf(ai)?.available === true && dataOf(ai)?.redis?.available === true) return
      } catch {
        // Bounded startup polling only; final failure remains visible.
      }
      await new Promise((resolve) => setTimeout(resolve, this.context.config.pollMs))
    }
    throw new VerificationFailure('managed backend did not become AI/Redis ready')
  }

  async stopOne(name, port) {
    const child = this.children.get(name)
    const result = {name, stopped: true, code: null, signal: null, portClosed: true, error: null}
    if (!child) return result
    try {
      if (child.exitCode === null && child.signalCode === null) child.kill('SIGTERM')
      try {
        Object.assign(result, await waitExit(child, 15_000))
      } catch {
        if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL')
        Object.assign(result, await waitExit(child, 5_000))
      }
      const deadline = Date.now() + 15_000
      while (await portIsOpen(port)) {
        if (Date.now() >= deadline) {
          result.portClosed = false
          break
        }
        await new Promise((resolve) => setTimeout(resolve, this.context.config.pollMs))
      }
    } catch (error) {
      result.stopped = false
      result.error = errorName(error)
    } finally {
      const fd = this.logFds.get(name)
      if (fd !== undefined) fs.closeSync(fd)
      this.logFds.delete(name)
    }
    result.stopped = result.stopped && result.portClosed
    return result
  }

  async stop() {
    if (!this.context.config.managed) return {status: 'NOT_MANAGED', processes: [], temporaryDataRemoved: false}
    const processes = []
    try {
      processes.push(await this.stopOne('backend', this.context.config.backendPort))
    } catch (error) {
      processes.push({name: 'backend', stopped: false, error: errorName(error)})
    }
    try {
      processes.push(await this.stopOne('stub', this.context.config.stubPort))
    } catch (error) {
      processes.push({name: 'stub', stopped: false, error: errorName(error)})
    }
    const stopped = processes.every((entry) => entry.stopped)
    let temporaryDataRemoved = false
    const removalErrors = []
    if (stopped && this.directories) {
      for (const name of ['backendWork', 'files', 'h2']) {
        try {
          await rm(this.directories[name], {recursive: true, force: false})
        } catch (error) {
          if (error?.code !== 'ENOENT') removalErrors.push({name, error: errorName(error)})
        }
      }
      temporaryDataRemoved = removalErrors.length === 0
    }
    return {status: stopped && temporaryDataRemoved ? 'COMPLETE' : 'INCOMPLETE', processes, temporaryDataRemoved, removalErrors}
  }
}

async function assertDedicatedStubDirectory(directory) {
  const names = await readdir(directory)
  const existing = names.filter((name) => /^req-\d{3}[.]/.test(name))
  if (existing.length > 0) throw new VerificationFailure('STUB_DUMP_DIR must be dedicated and empty before the single CASE run')
}

async function assertExternalInputsOutsideEvidence(config, evidenceRoot) {
  const inputs = [
    ['STUB_DUMP_DIR', config.stubDumpDir],
    ['BACKEND_LOG_FILE', config.backendLogFile],
    ['STUB_LOG_FILE', config.stubLogFile]
  ]
  for (const [name, input] of inputs) {
    const resolved = await realpath(input)
    if (isInside(evidenceRoot, resolved)) throw new VerificationFailure(`${name} must resolve outside OUT_DIR`)
  }
}

function upstreamToolCallEvidence(call) {
  return {
    id: boundedToken(call?.id),
    type: boundedToken(call?.type),
    name: boundedToken(call?.function?.name),
    arguments: textEvidence(call?.function?.arguments)
  }
}

function upstreamMessageEvidence(message) {
  const calls = Array.isArray(message?.tool_calls) ? message.tool_calls : []
  return {
    role: boundedToken(message?.role),
    toolCallId: boundedToken(message?.tool_call_id),
    content: textEvidence(message?.content),
    reasoningContent: textEvidence(message?.reasoning_content),
    toolCallCount: calls.length,
    toolCalls: calls.slice(0, 64).map(upstreamToolCallEvidence),
    toolCallsTruncated: calls.length > 64
  }
}

function upstreamEventToolCallEvidence(call) {
  return {
    id: boundedToken(call?.id),
    type: boundedToken(call?.type),
    name: boundedToken(call?.name),
    arguments: digestEvidence(call?.arguments)
  }
}

function upstreamUsageEvidence(usage) {
  if (!isRecord(usage)) return null
  return {
    promptTokens: Number.isSafeInteger(usage.promptTokens) ? usage.promptTokens : null,
    completionTokens: Number.isSafeInteger(usage.completionTokens) ? usage.completionTokens : null,
    totalTokens: Number.isSafeInteger(usage.totalTokens) ? usage.totalTokens : null,
    cachedTokens: Number.isSafeInteger(usage.cachedTokens) ? usage.cachedTokens : null,
    reasoningTokens: Number.isSafeInteger(usage.reasoningTokens) ? usage.reasoningTokens : null
  }
}

function upstreamChunkEvidence(chunk) {
  const choices = Array.isArray(chunk.choices) ? chunk.choices : []
  return {
    sequence: chunk.sequence,
    writtenAtMs: chunk.writtenAtMs,
    done: chunk.done === true,
    choices: choices.slice(0, 8).map((choice) => {
      const calls = Array.isArray(choice?.toolCalls) ? choice.toolCalls : []
      return {
        index: Number.isSafeInteger(choice?.index) ? choice.index : null,
        finishReason: boundedToken(choice?.finishReason),
        hasContent: choice?.hasContent === true,
        hasReasoningContent: choice?.hasReasoningContent === true,
        content: digestEvidence(choice?.content),
        reasoningContent: digestEvidence(choice?.reasoningContent),
        toolCallCount: calls.length,
        toolCalls: calls.slice(0, 32).map(upstreamEventToolCallEvidence),
        toolCallsTruncated: calls.length > 32
      }
    }),
    choicesTruncated: choices.length > 8,
    usage: upstreamUsageEvidence(chunk.usage)
  }
}

function upstreamRequestEvidence(request) {
  const messages = Array.isArray(request.body?.messages) ? request.body.messages : []
  return {
    id: `req-${request.id}`,
    kind: boundedToken(request.decision?.kind),
    route: boundedToken(request.decision?.route),
    responseBoundary: boundedToken(request.decision?.responseBoundary),
    receivedAtMs: request.receivedAtMs ?? null,
    bodySha256: sha256(request.body),
    messageCount: messages.length,
    messages: messages.slice(0, 128).map(upstreamMessageEvidence),
    messagesTruncated: messages.length > 128,
    chunkCount: request.chunks.length,
    chunks: request.chunks.map(upstreamChunkEvidence),
    events: request.events.filter((event) => event.type !== 'upstream-chunk').map((event) => ({
      sequence: event.sequence,
      type: boundedToken(event.type),
      responseBoundary: boundedToken(event.responseBoundary),
      receivedAtMs: event.receivedAtMs ?? null,
      finishedAtMs: event.finishedAtMs ?? null,
      outcome: boundedToken(event.outcome),
      errorName: boundedToken(event.errorName)
    }))
  }
}

async function collectUpstreamEvidence(context) {
  const source = context.config.stubDumpDir
  // cancel-before-complete：桩在停顿结束后才写唯一 outcome，必须在采集前有界等待它真正落地，
  // 且 finishedAtMs 不早于取消请求时间；超时记 FAIL，不用固定 sleep 冒充。
  if (context.config.caseName === 'cancel-before-complete' && context.cancelEvidence?.upstreamRequestId) {
    const requestId = String(context.cancelEvidence.upstreamRequestId).replace(/^req-/, '')
    const deadline = Date.now() + context.config.timeoutMs
    let ready = false
    let observed = {count: 0, finishedAtMs: null}
    while (Date.now() < deadline) {
      try {
        const events = await readJsonLines(path.join(source, `req-${requestId}.events.jsonl`))
        const outcomes = events.filter((event) => event.type === 'outcome')
        observed = {count: outcomes.length, finishedAtMs: outcomes[0]?.finishedAtMs ?? null}
        ready = outcomes.length === 1 && Number.isSafeInteger(observed.finishedAtMs)
          && observed.finishedAtMs >= context.cancelEvidence.cancelRequestedAtMs
      } catch {
        ready = false
      }
      if (ready) break
      await new Promise((resolve) => setTimeout(resolve, 100))
    }
    context.checks.add('upstream.outcome-ready-before-evidence', ready,
      'bound req-NNN.events.jsonl has exactly one outcome finished at/after the cancel request',
      {...observed, cancelRequestedAtMs: context.cancelEvidence.cancelRequestedAtMs})
  }
  const allowed = /^req-\d{3}(?:\.events\.jsonl|\.decision\.json|\.json)$/
  const names = (await readdir(source)).filter((name) => allowed.test(name)).sort()
  const ids = [...new Set(names.map((name) => /^req-(\d{3})/.exec(name)?.[1]).filter(Boolean))].sort()
  if (ids.length > 16) throw new VerificationFailure('upstream request count exceeds 16')
  const requests = []
  for (const id of ids) {
    const body = await readJsonFile(path.join(source, `req-${id}.json`))
    const decision = await readJsonFile(path.join(source, `req-${id}.decision.json`))
    const events = await readJsonLines(path.join(source, `req-${id}.events.jsonl`))
    const chunks = events.filter((event) => event.type === 'upstream-chunk')
    const receivedAtMs = events[0]?.receivedAtMs ?? null
    requests.push({id, body, decision, events, chunks, receivedAtMs})
  }
  const upstream = {requestCount: requests.length, requests}
  context.upstream = upstream
  await context.evidence.json('upstream-summary.json', {
    requestCount: requests.length,
    requests: requests.map(upstreamRequestEvidence)
  })
  return upstream
}

function runEvidence(detail) {
  return {
    exists: detail?.exists === true,
    archiveReadable: detail?.archiveReadable === true,
    archiveTrimmed: detail?.archiveTrimmed === true,
    recoverable: boundedToken(detail?.recoverable),
    snapshot: {
      runId: boundedToken(detail?.snapshot?.runId),
      sessionId: boundedToken(detail?.snapshot?.sessionId),
      status: boundedToken(detail?.snapshot?.status),
      finalText: textEvidence(detail?.snapshot?.finalText)
    }
  }
}

function historyMessageEvidence(message) {
  const summary = {
    type: boundedToken(messageType(message)),
    entryId: boundedToken(message?.entryId),
    runId: boundedToken(message?.runId),
    timestamp: Number.isSafeInteger(message?.timestamp) ? message.timestamp : null,
    text: textEvidence(messageText(message))
  }
  if (messageType(message) === 'ASSISTANT') {
    summary.reasoning = textEvidence(messageReasoning(message))
    summary.unconfirmed = message?.unconfirmed === true
    summary.errorCode = boundedToken(message?.errorCode)
    summary.transcriptDegraded = message?.transcriptDegraded === true
    summary.historyComplete = message?.historyComplete === false ? false : null
    const toolCalls = Array.isArray(message?.toolCalls) ? message.toolCalls : []
    summary.toolCallCount = toolCalls.length
    summary.toolCalls = toolCalls.slice(0, 64).map((call) => ({
      id: boundedToken(call?.id),
      type: boundedToken(call?.type),
      name: boundedToken(call?.name),
      arguments: textEvidence(call?.arguments)
    }))
    summary.toolCallsTruncated = toolCalls.length > 64
    const states = Object.entries(message?.toolCallStates ?? {})
    summary.toolCallStates = Object.fromEntries(states.slice(0, 64).map(([id, state]) => [
      id.slice(0, 256),
      {status: boundedToken(state?.status)}
    ]))
    summary.toolCallStatesTruncated = states.length > 64
    const segments = Array.isArray(message?.segments) ? message.segments : []
    summary.segments = segments.slice(0, 64).map((segment) => ({
      kind: boundedToken(segment?.kind),
      toolCallId: boundedToken(segment?.toolCallId),
      seq: Number.isSafeInteger(segment?.seq) ? segment.seq : null,
      text: textEvidence(segment?.text)
    }))
    summary.segmentCount = segments.length
    summary.segmentsTruncated = segments.length > 64
  }
  if (messageType(message) === 'TOOL') {
    const responses = Array.isArray(message?.toolResponses) ? message.toolResponses : []
    summary.toolResponses = responses.slice(0, 64).map((response) => ({
      id: boundedToken(response?.id),
      name: boundedToken(response?.name),
      responseData: textEvidence(response?.responseData),
      userTextCode: boundedToken(response?.userTextCode)
    }))
    summary.toolResponseCount = responses.length
    summary.toolResponsesTruncated = responses.length > 64
  }
  return summary
}

async function collectFinalEvidence(context) {
  const runResponse = await context.api.request('ai.run', `/api/ai/runs/${encodeURIComponent(context.runId)}`, {expected: [200]})
  context.runDetail = dataOf(runResponse)
  await context.evidence.json('run.json', runEvidence(context.runDetail))
  const pages = []
  let before = null
  for (let pageNumber = 0; pageNumber < 20; pageNumber += 1) {
    const query = new URLSearchParams({latest: '100'})
    if (before) query.set('before', before)
    const response = await context.api.request('ai.history', `/api/ai/history/${encodeURIComponent(context.sessionId)}?${query}`, {expected: [200]})
    const data = dataOf(response)
    if (!Array.isArray(data?.messages)) throw new VerificationFailure('history.messages is not an array')
    if (data?.truncated !== undefined && typeof data.truncated !== 'boolean') {
      throw new VerificationFailure('history.truncated is not boolean when present')
    }
    pages.push({
      hasMore: data.hasMore === true,
      nextCursor: data.nextCursor ?? null,
      truncated: data.truncated === true,
      messages: data.messages
    })
    if (!data.hasMore) break
    if (!data.nextCursor) throw new VerificationFailure('history hasMore=true without nextCursor')
    before = data.nextCursor
    if (pageNumber === 19) throw new VerificationFailure('history pagination exceeds 20 pages')
  }
  const truncated = pages.some((page) => page.truncated)
  context.history = {
    complete: !truncated,
    truncated,
    pageCount: pages.length,
    messages: pages.slice().reverse().flatMap((page) => page.messages)
  }
  await context.evidence.json('history.json', {
    complete: context.history.complete,
    truncated: context.history.truncated,
    pageTruncated: pages.map((page) => page.truncated),
    pageCount: context.history.pageCount,
    messageCount: context.history.messages.length,
    messagesSha256: sha256(context.history.messages),
    messages: context.history.messages.map(historyMessageEvidence)
  })
}

function messageType(message) {
  return String(message?.type ?? message?.role ?? '').toUpperCase()
}

function messageText(message) {
  return message?.text ?? message?.content ?? ''
}

function messageReasoning(message) {
  return message?.reasoningContent ?? message?.metadata?.reasoningContent ?? message?.properties?.reasoningContent ?? ''
}

function publicDeltas(driver, reasoning = false) {
  return driver.frames.map((entry) => entry.frame).filter((frame) => frame.type === 'delta'
    && (reasoning ? frame.kind === 'reasoning' : frame.kind !== 'reasoning'))
}

function publicText(driver, reasoning = false) {
  return publicDeltas(driver, reasoning).map((frame) => frame.text ?? '').join('')
}

function toolFrames(driver, types = toolFrameTypes) {
  return driver.frames.map((entry) => entry.frame).filter((frame) => types.has(frame.type))
}

function historyTools(context) {
  return context.history.messages.filter((message) => messageType(message) === 'TOOL' && message.runId === context.runId)
}

function historyAssistants(context) {
  return context.history.messages.filter((message) => messageType(message) === 'ASSISTANT' && message.runId === context.runId)
}

function allErrorCodes(value, out = []) {
  if (Array.isArray(value)) {
    for (const item of value) allErrorCodes(item, out)
  } else if (isRecord(value)) {
    for (const [key, item] of Object.entries(value)) {
      if (key === 'code' && typeof item === 'string') out.push(item)
      else allErrorCodes(item, out)
    }
  }
  return out
}

function containsKey(value, wanted) {
  if (Array.isArray(value)) return value.some((item) => containsKey(item, wanted))
  if (!isRecord(value)) return false
  return Object.entries(value).some(([key, item]) => key === wanted || containsKey(item, wanted))
}

function containsReasoningKey(value) {
  if (Array.isArray(value)) return value.some(containsReasoningKey)
  if (!isRecord(value)) return false
  return Object.entries(value).some(([key, item]) => key === 'reasoningContent'
    || key === 'reasoning_content' || containsReasoningKey(item))
}

function containsReasoningSegment(value) {
  if (Array.isArray(value)) return value.some(containsReasoningSegment)
  if (!isRecord(value)) return false
  if (Array.isArray(value.segments) && value.segments.some((segment) => segment?.kind === 'reasoning')) return true
  return Object.values(value).some(containsReasoningSegment)
}

function expectedUsage(usage) {
  return {
    promptTokens: usage.promptTokens,
    completionTokens: usage.completionTokens,
    totalTokens: usage.totalTokens,
    cachedTokens: usage.cachedTokens,
    reasoningTokens: usage.reasoningTokens,
    est: false,
    source: 'stream'
  }
}

function usageMatches(actual, expected) {
  return Object.entries(expected).every(([key, value]) => value === undefined || actual?.[key] === value)
}

function upstreamToolCalls(request) {
  return request.chunks.flatMap((chunk) => (chunk.choices ?? []).flatMap((choice) => choice?.toolCalls ?? []))
}

function upstreamReasoningChoices(request) {
  return request.chunks.flatMap((chunk) => (chunk.choices ?? []).filter((choice) => choice?.hasReasoningContent === true))
}

function upstreamContentChoices(request) {
  return request.chunks.flatMap((chunk) => (chunk.choices ?? []).filter((choice) => choice?.hasContent === true))
}

function assertUpstreamEventInvariant(context) {
  for (const request of context.upstream.requests) {
    const id = `req-${request.id}`
    const outcomes = request.events.filter((event) => event.type === 'outcome')
    const outcome = outcomes[0]
    context.checks.require(`upstream.unique-outcome-${id}`, outcomes.length === 1 && request.events.at(-1) === outcome,
      'exactly one outcome as the final event', outcomes.map((event) => ({sequence: event.sequence, outcome: event.outcome})))
    const sequenceValid = request.events.length === request.chunks.length + 2
      && request.events[0]?.type === 'request'
      && request.events[0]?.sequence === 0
      && request.chunks.every((chunk, index) => request.events[index + 1] === chunk
        && chunk.sequence === index + 1 && Number.isSafeInteger(chunk.writtenAtMs))
      && outcome?.sequence === request.chunks.length + 1
      && Number.isSafeInteger(outcome?.finishedAtMs)
    context.checks.require(`upstream.event-sequence-${id}`, sequenceValid,
      'request=0, chunks=1..N, one final outcome=N+1', request.events.map((event) => ({
        sequence: event.sequence,
        type: event.type,
        writtenAtMs: event.writtenAtMs ?? null,
        finishedAtMs: event.finishedAtMs ?? null
      })))
    if (healthyCases.has(context.config.caseName)) {
      context.checks.require(`upstream.healthy-ok-done-${id}`, outcomes.length === 1
        && outcome?.outcome === 'ok' && request.chunks.at(-1)?.done === true,
      'unique outcome=ok and final chunk done=true for every healthy upstream request', {
          outcome: outcome?.outcome,
          finalChunkSequence: request.chunks.at(-1)?.sequence,
          finalChunkDone: request.chunks.at(-1)?.done === true
        })
    }
  }
}

function assertHealthyTerminal(context) {
  const {checks, terminal, runDetail} = context
  checks.require('terminal.healthy-done', terminal?.type === 'done' && terminal.cancelled !== true,
    'done without cancelled=true', terminal)
  checks.require('run.done', runDetail?.snapshot?.status === 'DONE', 'DONE', runDetail?.snapshot?.status)
  const text = publicText(context.driver)
  const expectedFinalText = context.config.caseName === 'confirm-step-timeline'
    ? messageText(historyAssistants(context).at(-1))
    : text
  checks.require('run.final-text', runDetail?.snapshot?.finalText === expectedFinalText, expectedFinalText, runDetail?.snapshot?.finalText)
  const historyText = historyAssistants(context).map(messageText).join('')
  checks.require('history.public-text-exact', historyText === text, text, historyText)
}

function assertCommon(context, expectedUpstreamCount) {
  const {checks, driver, terminal, runDetail, history} = context
  checks.require('terminal.present', Boolean(terminal), 'one public terminal frame', terminal)
  const degraded = driver.frames.filter((entry) => entry.frame.transcriptDegraded === true || entry.frame.historyComplete === false)
  checks.require('transcript.not-degraded', degraded.length === 0, 'no transcriptDegraded/historyComplete=false', degraded)
  const codes = driver.frames.flatMap((entry) => allErrorCodes(entry.frame))
  checks.require('transcript.no-typed-errors', !codes.some((code) => transcriptErrorCodes.has(code)),
    'no AI_TRANSCRIPT_INCOMPLETE/AI_TRANSCRIPT_WRITE_FAILED', codes)
  checks.require('run.exists', runDetail?.exists === true, true, runDetail?.exists)
  checks.require('run.identity', runDetail?.snapshot?.runId === context.runId && runDetail?.snapshot?.sessionId === context.sessionId,
    {runId: context.runId, sessionId: context.sessionId}, {
      runId: runDetail?.snapshot?.runId,
      sessionId: runDetail?.snapshot?.sessionId,
      status: runDetail?.snapshot?.status
    })
  checks.require('run.archive-readable', runDetail?.archiveReadable === true && runDetail?.archiveTrimmed === false
    && runDetail?.recoverable === 'complete', 'readable, untrimmed, complete', {
      archiveReadable: runDetail?.archiveReadable,
      archiveTrimmed: runDetail?.archiveTrimmed,
      recoverable: runDetail?.recoverable
    })
  checks.require('history.complete-read', history?.complete === true && history?.truncated === false,
    {complete: true, truncated: false}, {complete: history?.complete, truncated: history?.truncated})
  const assistants = historyAssistants(context)
  checks.require('history.assistant-confirmed', assistants.every((message) => message.unconfirmed !== true),
    'no same-run ASSISTANT with unconfirmed=true', assistants.map((message) => ({
      entryId: message.entryId,
      unconfirmed: message.unconfirmed === true
    })))
  const assistantDegraded = assistants.filter((message) => message.transcriptDegraded === true || message.historyComplete === false)
  checks.require('history.transcript-not-degraded', assistantDegraded.length === 0,
    'no same-run ASSISTANT transcript degradation', assistantDegraded.map((message) => ({
      entryId: message.entryId,
      transcriptDegraded: message.transcriptDegraded === true,
      historyComplete: message.historyComplete
    })))
  const historyCodes = assistants.flatMap((message) => [
    ...(typeof message.errorCode === 'string' ? [message.errorCode] : []),
    ...allErrorCodes(message)
  ])
  checks.require('history.no-transcript-error-codes', !historyCodes.some((code) => transcriptErrorCodes.has(code)),
    'no history ASSISTANT AI_TRANSCRIPT_INCOMPLETE/AI_TRANSCRIPT_WRITE_FAILED', historyCodes)
  const users = history.messages.filter((message) => messageType(message) === 'USER' && message.runId === context.runId)
  checks.require('history.one-user', users.length === 1 && messageText(users[0]) === context.message,
    context.message, users.map(messageText))
  checks.require('upstream.request-count', context.upstream.requestCount === expectedUpstreamCount,
    expectedUpstreamCount, context.upstream.requestCount)
  assertUpstreamEventInvariant(context)
  const after = context.businessAfter
  if (context.config.caseName !== 'confirm-step-timeline') {
    checks.require('business.no-public-side-effects', sameBusinessSnapshot(context.businessBefore, after),
      context.businessBefore, after)
  }
  if (healthyCases.has(context.config.caseName)) assertHealthyTerminal(context)
}

function assertLateReasoning(context) {
  const {checks, driver, upstream, terminal} = context
  const [first, second] = upstream.requests
  checks.require('late.first-tool-decision', first.decision.kind === 'tool_call' && first.decision.tool === 'list_config_defs',
    'list_config_defs tool_call', first.decision)
  const finishAt = first.chunks.findIndex((chunk) => (chunk.choices ?? [])
    .some((choice) => choice?.finishReason === 'tool_calls'))
  const reasoningChunks = first.chunks.filter((chunk) => (chunk.choices ?? [])
    .some((choice) => choice?.hasReasoningContent === true))
  checks.require('late.event-reasoning-after-finish', finishAt >= 0 && reasoningChunks.length === 2
    && reasoningChunks.every((chunk) => first.chunks.indexOf(chunk) > finishAt),
  'two reasoning chunks after finish(tool_calls)', {finishAt, reasoningAt: reasoningChunks.map((chunk) => first.chunks.indexOf(chunk))})
  const reasoningChoices = upstreamReasoningChoices(first)
  checks.require('late.upstream-reasoning-blueprint', reasoningChoices.length === lateReasoningPieces.length
    && reasoningChoices.every((choice, index) => digestMatches(choice.reasoningContent, lateReasoningPieces[index])),
  lateReasoningPieces.map(textEvidence), reasoningChoices.map((choice) => ({
      index: choice.index,
      reasoningContent: digestEvidence(choice.reasoningContent)
    })))
  const reasoning = lateReasoningPieces.join('')
  const usageChunks = first.chunks.filter((chunk) => chunk.usage)
  const usageChunk = usageChunks[0]
  checks.require('late.event-usage-after-reasoning', usageChunks.length === 1
    && first.chunks.indexOf(usageChunk) > first.chunks.indexOf(reasoningChunks.at(-1)),
  'exactly one usage chunk after late reasoning', usageChunks.map((chunk) => chunk.usage))
  const expected = expectedUsage(usageChunk?.usage ?? {})
  checks.require('late.done-usage-truth', usageMatches(terminal.usage, expected), expected, terminal.usage)
  checks.require('late.continuation-has-no-usage', second.chunks.every((chunk) => !chunk.usage),
    'no second-response usage to mask the late truth', second.chunks.map((chunk) => chunk.usage ?? null))
  const firstDoneChunk = first.chunks.at(-1)
  checks.require('late.tool-after-complete-response', Number.isSafeInteger(firstDoneChunk?.writtenAtMs)
    && Number.isSafeInteger(second.receivedAtMs)
    && second.receivedAtMs >= firstDoneChunk.writtenAtMs,
  'second upstream request received only after the first final done chunk', {
      firstDoneWrittenAtMs: firstDoneChunk?.writtenAtMs,
      secondReceivedAtMs: second.receivedAtMs
    })
  const starts = toolFrames(driver, new Set(['tool_start']))
  const results = toolFrames(driver, new Set(['tool_result']))
  const reasoningDeltas = publicDeltas(driver, true)
  checks.require('late.public-reasoning-complete', publicText(driver, true) === reasoning,
    reasoning, publicText(driver, true))
  checks.require('late.reasoning-before-tool', starts.length === 1 && reasoningDeltas.length > 0
    && reasoningDeltas.at(-1).seq < starts[0].seq,
  'all public reasoning deltas precede tool_start', {
      lastReasoningSeq: reasoningDeltas.at(-1)?.seq,
      toolStartSeq: starts[0]?.seq
    })
  checks.require('late.one-executed-tool', starts.length === 1 && results.length === 1
    && results[0].name === 'list_config_defs' && results[0].executed === true,
  'one executed list_config_defs', {starts, results})
  const tools = historyTools(context)
  const assistants = historyAssistants(context)
  const toolResponses = tools.flatMap((message) => Array.isArray(message.toolResponses) ? message.toolResponses : [])
  const publicToolCallId = results[0]?.toolCallId
  const historyState = assistants.map((message) => message.toolCallStates?.[publicToolCallId])
    .find((state) => state !== undefined)
  checks.require('late.history-one-tool', tools.length === 1 && toolResponses.length === 1
    && toolResponses[0].id === publicToolCallId && historyState?.status === 'EXECUTED',
  'one toolResponses[].id matching assistant.toolCallStates[id].status=EXECUTED', {
      toolMessageCount: tools.length,
      responses: toolResponses.map((response) => ({id: response.id, name: response.name})),
      publicToolCallId,
      historyStatus: historyState?.status ?? null
    })
  checks.require('late.history-reasoning-complete', assistants.map(messageReasoning).join('') === reasoning,
    reasoning, assistants.map(messageReasoning))
  const replayMessages = second.body.messages.filter((message) => message?.role === 'assistant'
    && typeof message.reasoning_content === 'string')
  checks.require('late.second-request-reasoning-replay', replayMessages.map((message) => message.reasoning_content).join('') === reasoning,
    reasoning, replayMessages.map((message) => message.reasoning_content))
  checks.require('late.second-request-has-tool-result', second.body.messages.some((message) => message?.role === 'tool'
    && message?.tool_call_id === results[0].toolCallId), 'matching role=tool result in second request',
  second.body.messages.map((message) => ({role: message?.role, toolCallId: message?.tool_call_id ?? null})))
}

function assertSecondToolAggregate(context) {
  const {checks, driver, terminal, upstream} = context
  const [first, continuation] = upstream.requests
  checks.require('second.continuation-request', upstream.requestCount === 2 && Boolean(continuation),
    'one continuation request after both independent tools', upstream.requestCount)
  const calls = upstreamToolCalls(first)
  const ids = calls.map((call) => call.id)
  const finishAt = first.chunks.findIndex((chunk) => (chunk.choices ?? [])
    .some((choice) => choice?.finishReason === 'tool_calls'))
  const secondAt = first.chunks.findIndex((chunk) => (chunk.choices ?? [])
    .some((choice) => (choice?.toolCalls ?? []).some((call) => call.id?.endsWith('-second'))))
  checks.require('second.event-independent-call-after-finish', calls.length === 2
    && new Set(ids).size === 2 && finishAt >= 0 && secondAt > finishAt,
  'two independent tool calls in upstream order after the first finish', {calls, finishAt, secondAt})
  const starts = toolFrames(driver, new Set(['tool_start']))
  const results = toolFrames(driver, new Set(['tool_result']))
  checks.require('second.public-two-tools-in-order', canonicalJson(starts.map((frame) => frame.toolCallId)) === canonicalJson(ids)
    && canonicalJson(results.map((frame) => frame.toolCallId)) === canonicalJson(ids)
    && results.every((frame) => frame.name === 'list_config_defs' && frame.executed === true),
  'both IDs execute exactly once in upstream order', {ids, starts, results})
  const continuationToolIds = continuation.body.messages.filter((message) => message?.role === 'tool')
    .map((message) => message.tool_call_id)
  const continuationAssistantIds = continuation.body.messages.filter((message) => message?.role === 'assistant')
    .flatMap((message) => message.tool_calls ?? []).map((call) => call.id)
  checks.require('second.continuation-ids-in-order', canonicalJson(continuationToolIds) === canonicalJson(ids)
    && canonicalJson(continuationAssistantIds) === canonicalJson(ids),
  'continuation replays both assistant calls and tool results with identical IDs in order', {
      ids,
      continuationToolIds,
      continuationAssistantIds
    })
  const responses = historyTools(context).flatMap((message) => message.toolResponses ?? [])
  const historyIds = responses.map((response) => response.id)
  const assistants = historyAssistants(context)
  const historyCallIds = assistants.flatMap((message) => message.toolCalls ?? []).map((call) => call.id)
  checks.require('second.history-two-tools-in-order', canonicalJson(historyIds) === canonicalJson(ids)
    && canonicalJson(historyCallIds) === canonicalJson(ids)
    && ids.every((id) => assistants.some((message) => message.toolCallStates?.[id]?.status === 'EXECUTED')),
  'history preserves both tool responses and EXECUTED states in order', {
      ids,
      historyIds,
      historyCallIds,
      states: assistants.map((message) => message.toolCallStates)
    })
  const usageChunk = upstream.requests.flatMap((request) => request.chunks).findLast((chunk) => chunk.usage)
  const upstreamUsage = usageChunk?.usage
  const actualUsage = terminal.usage
  const expected = isRecord(upstreamUsage) ? expectedUsage(upstreamUsage) : null
  if (expected) {
    for (const key of ['cachedTokens', 'reasoningTokens']) {
      expected[key] = isRecord(actualUsage) && Object.hasOwn(actualUsage, key) ? upstreamUsage[key] : 'ABSENT'
    }
  }
  const validInteger = (value) => Number.isSafeInteger(value) && value >= 0
  const coreUsageMatches = isRecord(upstreamUsage) && isRecord(actualUsage)
    && ['promptTokens', 'completionTokens', 'totalTokens'].every((key) => validInteger(upstreamUsage[key])
      && validInteger(actualUsage[key]) && actualUsage[key] === upstreamUsage[key])
  const optionalUsageMatches = isRecord(upstreamUsage) && isRecord(actualUsage)
    && ['cachedTokens', 'reasoningTokens'].every((key) => !Object.hasOwn(actualUsage, key)
      || (Object.hasOwn(upstreamUsage, key) && validInteger(upstreamUsage[key])
        && validInteger(actualUsage[key]) && actualUsage[key] === upstreamUsage[key]))
  checks.require('second.done-usage-truth', coreUsageMatches && optionalUsageMatches
    && actualUsage.est === false && actualUsage.source === 'stream', expected, actualUsage)
}

function assertCancelBeforeComplete(context) {
  const {checks, driver, terminal, runDetail, upstream, cancelEvidence} = context
  checks.require('cancel.terminal', terminal?.type === 'done' && terminal.cancelled === true,
    'done cancelled=true', terminal)
  checks.require('cancel.run-cancelled', runDetail?.snapshot?.status === 'CANCELLED', 'CANCELLED', runDetail?.snapshot?.status)
  checks.require('cancel.zero-public-tools', toolFrames(driver).length === 0, 'zero released tool frames', toolFrames(driver))
  checks.require('cancel.zero-history-tools', historyTools(context).length === 0, 'zero history tool results', historyTools(context))
  checks.require('cancel.no-continuation-request', upstream.requestCount === 1, 1, upstream.requestCount)
  const cancelRequest = upstream.requests.find((request) => `req-${request.id}` === cancelEvidence?.upstreamRequestId)
  checks.require('cancel.request-bound-to-tool-chunk', Boolean(cancelRequest)
    && Number.isSafeInteger(cancelEvidence?.cancelRequestedAtMs)
    && cancelEvidence.cancelRequestedAtMs >= cancelEvidence.upstreamWrittenAtMs
    && cancelEvidence.cancelAcceptedAtMs >= cancelEvidence.cancelRequestedAtMs,
  'tool chunk <= cancel request <= cancel acceptance for the same req-NNN', {
      upstreamRequestId: cancelEvidence?.upstreamRequestId,
      upstreamWrittenAtMs: cancelEvidence?.upstreamWrittenAtMs,
      cancelRequestedAtMs: cancelEvidence?.cancelRequestedAtMs,
      cancelAcceptedAtMs: cancelEvidence?.cancelAcceptedAtMs
    })
  const doneChunk = upstream.requests[0].chunks.find((chunk) => chunk.done === true)
  checks.require('cancel.before-upstream-complete', Boolean(cancelEvidence)
    && (!doneChunk || cancelEvidence.cancelAcceptedAtMs < doneChunk.writtenAtMs),
  'cancel accepted before upstream done=true, or upstream cancelled before done', {
      cancelAcceptedAtMs: cancelEvidence?.cancelAcceptedAtMs,
      doneWrittenAtMs: doneChunk?.writtenAtMs ?? null
    })
}

function assertMultiChoice(context) {
  const {checks, driver, upstream} = context
  const contentChoices = upstreamContentChoices(upstream.requests[0])
  checks.require('multi.upstream-two-choices', contentChoices.length === multiChoicePieces.length
    && contentChoices.every((choice, index) => digestMatches(choice.content, multiChoicePieces[index]))
    && upstream.requests[0].chunks.some((chunk) => (chunk.choices ?? []).length === 2),
  multiChoicePieces.map(textEvidence), contentChoices.map((choice) => ({
      index: choice.index,
      content: digestEvidence(choice.content)
    })))
  const expectedText = multiChoicePieces.join('')
  checks.require('multi.public-exact-no-duplicates', publicText(driver) === expectedText, expectedText, publicText(driver))
  checks.require('multi.zero-tools', toolFrames(driver).length === 0, 'zero tool frames', toolFrames(driver))
}

function assertStandardNoReasoning(context) {
  const {checks, driver, upstream} = context
  const first = upstream.requests[0]
  const contentChoices = upstreamContentChoices(first)
  checks.require('standard.upstream-text-blueprint', contentChoices.length === standardTextPieces.length
    && contentChoices.every((choice, index) => digestMatches(choice.content, standardTextPieces[index])),
  standardTextPieces.map(textEvidence), contentChoices.map((choice) => ({
      index: choice.index,
      content: digestEvidence(choice.content)
    })))
  checks.require('standard.public-text-exact', publicText(driver) === standardTextBlueprint,
    standardTextBlueprint, publicText(driver))
  checks.require('standard.no-request-extension', !containsKey(first.body, 'reasoning_content'),
    'no reasoning_content anywhere in public upstream request', {reasoningContentPresent: containsKey(first.body, 'reasoning_content')})
  const reasoningKeyPresence = first.chunks.flatMap((chunk) => (chunk.choices ?? [])
    .map((choice) => choice?.hasReasoningContent === true))
  checks.require('standard.no-upstream-extension', first.chunks.every((chunk) => (chunk.choices ?? [])
    .every((choice) => choice?.hasReasoningContent === false)) && first.decision.responseBoundary === undefined,
  'hasReasoningContent=false for every choice and no responseBoundary marker', {
      reasoningKeyPresence,
      responseBoundary: first.decision.responseBoundary ?? null
    })
  checks.require('standard.no-public-reasoning', publicDeltas(driver, true).length === 0
    && driver.frames.every((entry) => entry.frame.kind !== 'reasoning'
      && !containsReasoningKey(entry.frame) && !containsReasoningSegment(entry.frame)),
  'no public reasoning delta, recursive reasoning key, or reasoning segment', driver.frames.map((entry) => ({
      seq: entry.frame.seq,
      type: entry.frame.type,
      reasoningKeyPresent: containsReasoningKey(entry.frame),
      reasoningSegmentPresent: containsReasoningSegment(entry.frame)
    })))
  const assistants = historyAssistants(context)
  checks.require('standard.no-history-reasoning', assistants.every((message) => !containsReasoningKey(message)
    && !containsReasoningSegment(message)),
  'no recursive reasoningContent/reasoning_content or segments[kind=reasoning]', assistants.map((message) => ({
      entryId: message.entryId,
      reasoningKeyPresent: containsReasoningKey(message),
      reasoningSegmentPresent: containsReasoningSegment(message)
    })))
  checks.require('standard.zero-tools', toolFrames(driver).length === 0, 'zero tool frames', toolFrames(driver))
}

function messageForCase(caseName) {
  switch (caseName) {
    case 'late-reasoning':
      return 'response-boundary=late-reasoning tool=list_config_defs&args={} usage-shape=none'
    case 'second-tool-aggregate':
      return 'response-boundary=second-tool-aggregate tool=list_config_defs&args={}'
    case 'multi-choice':
      return 'response-boundary=multi-choice'
    case 'cancel-before-complete':
      return 'response-boundary=cancel-before-complete tool=list_config_defs&args={} stall-frames=5000'
    case 'standard-no-reasoning':
      return 'turn-text'
    case 'confirm-step-timeline':
      return 'tool=confirm_step&args={"step":"QUERY_COND"}'
    default:
      throw new VerificationFailure(`unsupported CASE ${caseName}`)
  }
}

async function prepareConfirmStepFixture(context) {
  const created = await context.api.request('task.create', '/api/tasks', {
    method: 'POST', json: true, body: JSON.stringify({type: 'EXPORT', title: `response-boundary-${randomUUID()}`}), expected: [201]
  })
  context.fixtureTaskId = Number(dataOf(created)?.id)
  if (!Number.isSafeInteger(context.fixtureTaskId) || context.fixtureTaskId <= 0) {
    throw new VerificationFailure('confirm-step.fixture-id-invalid')
  }
  await context.api.request('task.select-defs', `/api/tasks/${context.fixtureTaskId}/select-defs`, {
    method: 'POST', json: true, body: JSON.stringify({defCodes: ['CURRENCY']}), expected: [200]
  })
}

function assistantSegmentFacts(message) {
  const segments = Array.isArray(message?.segments) ? message.segments : []
  return {
    count: segments.length,
    text: segments.filter((segment) => segment?.kind === 'text').map((segment) => segment.text ?? '').join(''),
    reasoning: segments.filter((segment) => segment?.kind === 'reasoning').map((segment) => segment.text ?? '').join(''),
    toolCallIds: segments.filter((segment) => segment?.kind === 'tool').map((segment) => segment.toolCallId ?? null)
  }
}

async function assertConfirmStepTimeline(context) {
  const {checks, history} = context
  const assistants = historyAssistants(context)
  const plainText = assistants.filter((message) => (message.toolCalls ?? []).length === 0)
  checks.require('timeline.pure-text-entry-count', plainText.length === 2, 'exactly two no-tool ASSISTANT entries in one run',
    plainText.map((message) => ({entryId: message.entryId, text: textEvidence(messageText(message))})))
  // 不再硬编码任何长度：按 upstream.requests 中 decision.kind=text 的顺序逐条对账纯文本 ASSISTANT 全文。
  const upstreamTexts = (context.upstream?.requests ?? [])
    .filter((request) => request.decision?.kind === 'text')
    .map((request) => String(request.decision.text ?? ''))
  checks.require('timeline.pure-text-upstream-order-match',
    upstreamTexts.length === plainText.length
    && upstreamTexts.every((text, index) => text === messageText(plainText[index])),
  'each pure-text assistant body equals the corresponding upstream text decision in order', {
    upstream: upstreamTexts.map((text) => textEvidence(text)),
    history: plainText.map((message) => ({entryId: message.entryId, text: textEvidence(messageText(message))}))
  })
  if (context.config.finalTextLength !== null) {
    checks.require('timeline.final-text-length', upstreamTexts.length > 0
      && Array.from(upstreamTexts.at(-1)).length === context.config.finalTextLength,
    context.config.finalTextLength, upstreamTexts.map((text) => Array.from(text).length))
  }
  const distinctTexts = new Set(plainText.map(messageText))
  checks.require('timeline.pure-text-nonprefix', distinctTexts.size >= 2 && plainText.every((message, index) =>
    plainText.slice(index + 1).every((later) => {
      const left = messageText(message)
      const right = messageText(later)
      return !left.startsWith(right) && !right.startsWith(left)
    })), 'multiple distinct non-prefix pure-text bodies', plainText.map(messageText))
  checks.require('timeline.every-entry-segments-self-contained', assistants.every((message) => {
    const actual = assistantSegmentFacts(message)
    return actual.count > 0 && actual.text === messageText(message) && actual.reasoning === messageReasoning(message)
      && canonicalJson(actual.toolCallIds) === canonicalJson((message.toolCalls ?? []).map((call) => call.id))
  }), 'every ASSISTANT has non-empty segments containing only its own text/reasoning/tool anchors', assistants.map((message) => ({
      entryId: message.entryId,
      expected: {text: messageText(message), reasoning: messageReasoning(message), toolCallIds: (message.toolCalls ?? []).map((call) => call.id)},
      actual: assistantSegmentFacts(message)
    })))
  checks.require('timeline.frontend-results-accepted', context.frontendResults.length >= 2
    && context.frontendResults.every((result) => result.accepted), 'real frontend results accepted', context.frontendResults)
  const task = await context.api.request('task.get', `/api/tasks/${context.fixtureTaskId}`, {expected: [200]})
  const currentStep = dataOf(task)?.currentStep
  checks.require('timeline.business-bounded', ['QUERY_COND', 'EXPORT'].includes(currentStep)
    && context.businessAfter.taskTotal === context.businessBefore.taskTotal
    && canonicalJson(context.businessAfter.jobIds) === canonicalJson(context.businessBefore.jobIds),
  'only the fixture advances without new tasks or jobs', {
      currentStep,
      before: {taskTotal: context.businessBefore.taskTotal, jobIds: context.businessBefore.jobIds},
      after: {taskTotal: context.businessAfter.taskTotal, jobIds: context.businessAfter.jobIds}
    })
}

async function runCase(context) {
  const created = await context.api.request('session.create', '/api/ai/session', {method: 'POST', json: true, expected: [200]})
  const sessionId = created.body?.sessionIdText ?? created.body?.data?.sessionIdText
  if (typeof sessionId !== 'string' || !/^[1-9]\d*$/.test(sessionId)) throw new VerificationFailure('session.create returned no string sessionIdText')
  context.sessionId = sessionId
  if (context.config.caseName === 'confirm-step-timeline') {
    context.cleanupBusinessBaseline = await taskBaseline(context.api)
    await prepareConfirmStepFixture(context)
  }
  context.businessBefore = await taskBaseline(context.api)
  await context.evidence.json('business-before.json', context.businessBefore)
  context.message = messageForCase(context.config.caseName)
  context.driver = new RunDriver(context)
  const driving = context.driver.start(context.message)
  if (context.config.caseName === 'cancel-before-complete') {
    const [driveResult, cancelResult] = await Promise.allSettled([driving, cancelAfterToolChunk(context, context.driver)])
    if (driveResult.status === 'rejected') throw driveResult.reason
    if (cancelResult.status === 'rejected') throw cancelResult.reason
  } else {
    await driving
  }
  await collectFinalEvidence(context)
  context.businessAfter = await taskBaseline(context.api)
  await context.evidence.json('business-after.json', context.businessAfter)
  await collectUpstreamEvidence(context)
  const expectedRequests = context.config.caseName === 'confirm-step-timeline' ? 3
    : ['late-reasoning', 'second-tool-aggregate'].includes(context.config.caseName) ? 2 : 1
  assertCommon(context, expectedRequests)
  switch (context.config.caseName) {
    case 'late-reasoning':
      assertLateReasoning(context)
      break
    case 'second-tool-aggregate':
      await assertSecondToolAggregate(context)
      break
    case 'multi-choice':
      assertMultiChoice(context)
      break
    case 'cancel-before-complete':
      assertCancelBeforeComplete(context)
      break
    case 'standard-no-reasoning':
      assertStandardNoReasoning(context)
      break
    case 'confirm-step-timeline':
      await assertConfirmStepTimeline(context)
      break
    default:
      throw new VerificationFailure(`unsupported CASE ${context.config.caseName}`)
  }
}

async function cleanupSession(context) {
  const result = {status: 'NOT_REQUIRED', deletes: [], afterHistory: null, businessAfterCleanup: null}
  if (!context.sessionId) {
    await context.evidence.json('cleanup.json', result)
    return result
  }
  result.status = 'INCOMPLETE'
  const failureCountBefore = context.checks.failures.length
  if (context.runId && !context.terminal) {
    try {
      const cancelled = await context.api.request('ai.cancel', `/api/ai/cancel/${encodeURIComponent(context.runId)}`, {
        method: 'POST', json: true, expected: [200]
      })
      result.preCleanupCancel = responseSummary('ai.cancel', cancelled.body)
    } catch (error) {
      result.preCleanupCancel = {error: errorName(error)}
    }
  }
  for (let attempt = 1; attempt <= 2; attempt += 1) {
    try {
      const response = await context.api.request(`cleanup.delete.${attempt}`, `/api/ai/session/${encodeURIComponent(context.sessionId)}`, {
        method: 'DELETE', expected: [200]
      })
      const body = dataOf(response)
      const item = {
        attempt,
        httpStatus: response.status,
        complete: body?.complete === true,
        deferredCount: Array.isArray(body?.deferred) ? body.deferred.length : null,
        errorCount: Array.isArray(body?.errors) ? body.errors.length : null
      }
      result.deletes.push(item)
      context.checks.add(`cleanup.delete-${attempt}-complete`, item.complete && item.deferredCount === 0 && item.errorCount === 0,
        {complete: true, deferredCount: 0, errorCount: 0}, item)
    } catch (error) {
      result.deletes.push({attempt, error: errorName(error)})
      context.checks.add(`cleanup.delete-${attempt}-complete`, false, 'HTTP 200 complete cleanup', errorName(error))
    }
  }
  try {
    const after = await context.api.request('cleanup.history', `/api/ai/history/${encodeURIComponent(context.sessionId)}?latest=1`, {
      expected: [200, 410]
    })
    const messages = after.status === 200 ? dataOf(after)?.messages : []
    result.afterHistory = {httpStatus: after.status, messageCount: Array.isArray(messages) ? messages.length : null}
    context.checks.add('cleanup.history-absent', after.status === 410 || (after.status === 200 && Array.isArray(messages) && messages.length === 0),
      '410 or 200 with zero messages', result.afterHistory)
  } catch (error) {
    result.afterHistory = {error: errorName(error)}
    context.checks.add('cleanup.history-absent', false, 'post-delete history read', errorName(error))
  }
  if (context.fixtureTaskId) {
    try {
      const deleted = await context.api.request('cleanup.fixture-task', `/api/tasks/${context.fixtureTaskId}`, {
        method: 'DELETE', expected: [200, 404]
      })
      result.fixtureTask = {taskId: context.fixtureTaskId, httpStatus: deleted.status}
      context.checks.add('cleanup.fixture-task-removed', [200, 404].includes(deleted.status), 'fixture task deleted or already absent', result.fixtureTask)
    } catch (error) {
      result.fixtureTask = {taskId: context.fixtureTaskId, error: errorName(error)}
      context.checks.add('cleanup.fixture-task-removed', false, 'fixture task deleted or already absent', result.fixtureTask)
    }
  }
  if (context.businessBefore) {
    const baseline = context.cleanupBusinessBaseline ?? context.businessBefore
    try {
      result.businessAfterCleanup = await taskBaseline(context.api)
      context.checks.add('cleanup.business-no-side-effects', sameBusinessSnapshot(baseline, result.businessAfterCleanup),
        baseline, result.businessAfterCleanup)
    } catch (error) {
      result.businessAfterCleanup = {error: errorName(error)}
      context.checks.add('cleanup.business-no-side-effects', false, 'post-cleanup business snapshot', errorName(error))
    }
  }
  result.status = context.checks.failures.length === failureCountBefore ? 'COMPLETE' : 'INCOMPLETE'
  await context.evidence.json('cleanup.json', result)
  return result
}

async function captureLogOffsets(context) {
  context.logOffsets = {
    backend: (await stat(context.config.backendLogFile)).size,
    stub: (await stat(context.config.stubLogFile)).size
  }
  await context.evidence.json('log-offsets.json', {
    startedAt,
    files: Object.entries(context.logOffsets).map(([file, offset]) => ({file, offset}))
  })
}

async function readLogSince(file, offset, maximum = 2_000_000) {
  if (!Number.isSafeInteger(offset) || offset < 0) throw new VerificationFailure('missing current-run log offset')
  const info = await stat(file)
  if (info.size < offset) throw new VerificationFailure('log file shrank after the current-run offset')
  const readOffset = Math.max(offset, info.size - maximum)
  const handle = await open(file, 'r')
  try {
    const buffer = Buffer.alloc(info.size - readOffset)
    await handle.read(buffer, 0, buffer.length, readOffset)
    return {
      text: buffer.toString('utf8'),
      size: info.size,
      offset,
      readOffset,
      truncated: readOffset > offset
    }
  } finally {
    await handle.close()
  }
}

function stackEnd(lines, start) {
  let end = start + 1
  while (end < lines.length && end - start <= 64
    && !/^\d{4}-\d{2}-\d{2}[T ]/.test(lines[end])
    && !/^\[(?:INFO|WARN|ERROR|FATAL)\]/.test(lines[end])) {
    end += 1
  }
  return end
}

function addStackLines(target, lines, start) {
  for (let index = start; index < stackEnd(lines, start); index += 1) target.add(index)
}

function stubWriteFailureRequestId(line) {
  const match = /\bupstream response write failed req-(\d+) errorName=[A-Za-z][A-Za-z0-9]*\b/.exec(line)
  return match ? `req-${match[1].padStart(3, '0')}` : null
}

async function cancellationAssociations(backendLines, stubLines, context) {
  const expectedBackend = new Set()
  const expectedStub = new Set()
  backendLines.forEach((line, index) => {
    if (line.includes('attempt failed') && line.includes(context.runId) && line.includes('CANCELLED')) {
      addStackLines(expectedBackend, backendLines, index)
    }
  })
  const evidence = context.cancelEvidence
  const request = context.upstream?.requests.find((entry) => `req-${entry.id}` === evidence?.upstreamRequestId)
  const outcomes = request?.events.filter((event) => event.type === 'outcome') ?? []
  const outcome = outcomes.length === 1 ? outcomes[0] : null
  const warningIndexes = stubLines.flatMap((line, index) => stubWriteFailureRequestId(line) === evidence?.upstreamRequestId ? [index] : [])
  const writeError = outcome?.outcome === 'write-error'
  const supportedOutcome = outcome?.outcome === 'ok' || writeError
  const timingValid = Number.isSafeInteger(evidence?.cancelRequestedAtMs)
    && Number.isSafeInteger(outcome?.finishedAtMs)
    && outcome.finishedAtMs >= evidence.cancelRequestedAtMs
  const bound = Boolean(request && outcome && supportedOutcome && timingValid)
    && (writeError ? warningIndexes.length === 1 : warningIndexes.length === 0)
  if (bound && writeError) expectedStub.add(warningIndexes[0])
  const actual = {
    upstreamRequestId: evidence?.upstreamRequestId ?? null,
    outcome: outcome?.outcome ?? null,
    outcomeFinishedAtMs: outcome?.finishedAtMs ?? null,
    cancelRequestedAtMs: evidence?.cancelRequestedAtMs ?? null,
    warningCount: warningIndexes.length,
    warningLines: warningIndexes.map((index) => index + 1)
  }
  context.checks.add('cancel.write-error-log-binding', bound,
    'same req-NNN and cancel timing: ok has zero write WARN; write-error has exactly one matching WARN', actual)
  if (evidence) {
    await context.evidence.json('cancel.json', {
      ...evidence,
      writeError: {
        bound,
        ...actual,
        warningSha256: warningIndexes.length === 1 ? sha256(stubLines[warningIndexes[0]]) : null
      }
    })
  }
  return {expectedBackend, expectedStub}
}

async function expectedLogAssociations(logLines, context) {
  const expected = {backend: new Set(), stub: new Set(), labels: {}}
  if (context.config.caseName === 'cancel-before-complete') {
    const association = await cancellationAssociations(logLines.backend, logLines.stub, context)
    expected.backend = association.expectedBackend
    expected.stub = association.expectedStub
  }
  // 隔离单实例没有 lease 设施时，本 run 首次写状态会固定出现一条 RunStore lease-less WARN。
  // 只备案"本 runId + 完整固定文案 + WARN 级别"的这一条，其他 WARN/ERROR 一律仍是 UNEXPECTED。
  const directDriveCases = ['confirm-step-timeline', 'multi-choice', 'second-tool-aggregate']
  const expectedMessage = `lease-less write runId=${context.runId}: fence advanced to epoch=1 (write allowed without lease; no resume-authority semantics; check lease view if this is a stale write after owner change)`
  const warnLevel = /^\d{4}-\d{2}-\d{2}T\S+\s+WARN\s+/
  const indexes = logLines.backend.flatMap((line, index) => warnLevel.test(line) && line.endsWith(expectedMessage) ? [index] : [])
  for (const index of indexes) expected.backend.add(index)
  const label = directDriveCases.includes(context.config.caseName) ? 'EXPECTED_DIRECT_DRIVE' : 'EXPECTED_ISOLATED_NO_LEASE'
  if (indexes.length > 0) expected.labels.backend = label
  context.checks.add(`${context.config.caseName}.lease-less-warning-observed`, indexes.length === 1,
    'exactly one explicit lease-less warning for this run in the no-lease isolated instance',
    {count: indexes.length, lines: indexes.map((index) => index + 1), classification: label})
  return expected
}

async function collectLogEvidence(context) {
  const result = {files: [], unexpected: [], currentRunId: context.runId}
  const logs = {}
  const logLines = {}
  for (const [name, source] of [['backend', context.config.backendLogFile], ['stub', context.config.stubLogFile]]) {
    try {
      logs[name] = await readLogSince(source, context.logOffsets?.[name])
    } catch (error) {
      logs[name] = {text: '', size: 0, offset: context.logOffsets?.[name] ?? 0, readOffset: 0, truncated: false}
      context.checks.add(`logs.${name}-readable`, false, 'current-run log window must be readable',
        {file: name, errorName: errorName(error)})
    }
    logLines[name] = logs[name].text.split(/\r?\n/)
  }
  const expected = await expectedLogAssociations(logLines, context)
  const expectedClassification = ['confirm-step-timeline', 'multi-choice', 'second-tool-aggregate'].includes(context.config.caseName)
    ? 'EXPECTED_DIRECT_DRIVE'
    : 'EXPECTED_CANCELLATION'
  for (const name of ['backend', 'stub']) {
    const findings = []
    logLines[name].forEach((line, index) => {
      if (!/\b(ERROR|WARN)\b|\[FATAL\]|Exception\b/.test(line)) return
      const expectedLabel = expected.labels?.[name] ?? expectedClassification
      const classification = expected[name].has(index) ? expectedLabel : 'UNEXPECTED'
      const finding = {file: name, line: index + 1, classification, lineSha256: sha256(line)}
      findings.push(finding)
      if (classification === 'UNEXPECTED') result.unexpected.push(finding)
    })
    result.files.push({
      file: name,
      bytes: logs[name].size,
      startOffset: logs[name].offset,
      readOffset: logs[name].readOffset,
      appendedBytes: logs[name].size - logs[name].offset,
      truncated: logs[name].truncated,
      findings
    })
  }
  context.checks.add('logs.current-run-window-complete', Object.values(logs).every((log) => !log.truncated),
    'complete appended log window since current-run offsets', result.files.map((entry) => ({
      file: entry.file,
      startOffset: entry.startOffset,
      readOffset: entry.readOffset,
      truncated: entry.truncated
    })))
  context.checks.add('logs.no-unexpected-warn-error', result.unexpected.length === 0,
    'no unclassified WARN/ERROR/FATAL/Exception in current-run log windows', result.unexpected)
  await context.evidence.json('log-analysis.json', result)
  return result
}

async function sanitizeManagedWorkFiles(context) {
  if (!context.config.managed) {
    const result = {status: 'NOT_MANAGED', items: []}
    await context.evidence.json('sanitization.json', result)
    return result
  }
  const targets = [
    {name: 'upstream-live', path: context.config.stubDumpDir, recursive: true},
    {name: 'backend.log', path: context.config.backendLogFile, recursive: false},
    {name: 'stub.log', path: context.config.stubLogFile, recursive: false}
  ]
  const items = []
  for (const target of targets) {
    const item = {name: target.name, removed: false, error: null}
    if (typeof target.path !== 'string' || !isInside(context.runDirectory, target.path)) {
      item.error = 'UNSAFE_PATH'
    } else {
      try {
        await rm(target.path, {recursive: target.recursive, force: false})
        item.removed = true
      } catch (error) {
        if (error?.code === 'ENOENT') item.removed = true
        else item.error = errorName(error)
      }
    }
    items.push(item)
  }
  const result = {status: items.every((item) => item.removed) ? 'COMPLETE' : 'INCOMPLETE', items}
  context.checks.add('managed.sanitization-complete', result.status === 'COMPLETE',
    'upstream-live, backend.log and stub.log removed after evidence collection', items)
  await context.evidence.json('sanitization.json', result)
  return result
}

const config = loadConfig()
const evidenceRoot = await prepareEvidenceDirectory(config.outDir)
if (!config.managed) await assertExternalInputsOutsideEvidence(config, evidenceRoot)
const runDirectory = path.join(evidenceRoot, 'ai-response-boundary', config.caseName,
  `${new Date().toISOString().replace(/[:.]/g, '-')}-${randomUUID()}`)
const evidence = new Evidence(runDirectory)
await evidence.init()
const checks = new Checks()
const context = {
  config,
  checks,
  evidence,
  api: new ApiClient(config, evidence),
  runDirectory,
  sessionId: null,
  clientRequestId: `response-boundary-${randomUUID()}`,
  runId: null,
  driver: null,
  terminal: null,
  runDetail: null,
  history: null,
  message: null,
  upstream: null,
  businessBefore: null,
  businessAfter: null,
  cleanupBusinessBaseline: null,
  cancelEvidence: null,
  logOffsets: null,
  fixtureTaskId: null,
  frontendResults: []
}
const services = new ServiceManager(context)
let fatal = null
let cleanup = {status: 'NOT_REQUIRED'}
let managedCleanup = {status: 'NOT_MANAGED'}
let logAnalysis = {unexpected: []}
let sanitization = {status: 'NOT_MANAGED'}

try {
  if (!config.managed) {
    await assertDedicatedStubDirectory(config.stubDumpDir)
    await captureLogOffsets(context)
  }
  await services.start()
  await runCase(context)
} catch (error) {
  fatal = {message: safeFailure(error), type: errorName(error)}
  if (context.runId && !context.runDetail) {
    try {
      await collectFinalEvidence(context)
      if (!context.businessAfter) context.businessAfter = await taskBaseline(context.api)
    } catch (collectionError) {
      await evidence.append('collection-errors.jsonl', {error: errorName(collectionError)})
    }
  }
}

try {
  cleanup = await cleanupSession(context)
} catch (error) {
  cleanup = {status: 'INCOMPLETE', error: errorName(error)}
  checks.add('cleanup.fatal', false, 'cleanup must complete', errorName(error))
  await evidence.json('cleanup.json', cleanup)
}

try {
  if (!context.upstream && config.stubDumpDir && fs.existsSync(config.stubDumpDir)) await collectUpstreamEvidence(context)
} catch (error) {
  checks.add('upstream.evidence-complete', false, 'complete bounded upstream request/event evidence', errorName(error))
}

try {
  managedCleanup = await services.stop()
} catch (error) {
  managedCleanup = {status: 'INCOMPLETE', error: errorName(error)}
}
if (config.managed) checks.add('managed.cleanup-complete', managedCleanup.status === 'COMPLETE', 'COMPLETE', managedCleanup)
await evidence.json('managed-cleanup.json', managedCleanup)

try {
  logAnalysis = await collectLogEvidence(context)
} catch (error) {
  checks.add('logs.evidence-readable', false, 'backend and stub logs must be readable and classified', errorName(error))
}

sanitization = await sanitizeManagedWorkFiles(context)

const status = fatal || checks.failures.length > 0 || cleanup.status === 'INCOMPLETE'
  || managedCleanup.status === 'INCOMPLETE' || sanitization.status === 'INCOMPLETE'
  ? 'FAIL'
  : 'PASS'
const expectedOutcome = config.caseName === 'cancel-before-complete'
  ? 'EXPECTED_CANCELLATION'
  : 'HEALTHY_PASS'
const summary = {
  schemaVersion: 1,
  case: config.caseName,
  status,
  expectedOutcome,
  observedOutcome: status === 'PASS' ? expectedOutcome : 'UNEXPECTED_FAILURE',
  statusScope: 'PUBLIC_API_SSE_AND_STUB_CHUNK_ASSERTIONS_ONLY; bounded evidence; no fabricated application frames; REAL_PROVIDER_VERDICT=NOT_RUN; DIRECT_REDIS_VERDICT=NOT_RUN; DIRECT_DATABASE_VERDICT=NOT_RUN',
  startedAt,
  finishedAt: new Date().toISOString(),
  codeHead: codeHead(),
  executorIdentity: config.executorIdentity,
  evidenceDirectory: runDirectory,
  sessionId: context.sessionId,
  clientRequestId: context.clientRequestId,
  runId: context.runId,
  fatal,
  terminal: publicFrameEvidence(context.terminal),
  assertions: checks.items,
  failedAssertions: checks.failures,
  upstreamRequestCount: context.upstream?.requestCount ?? null,
  businessPublicApi: {
    before: context.businessBefore,
    after: context.businessAfter,
    unchanged: sameBusinessSnapshot(context.businessBefore, context.businessAfter)
  },
  cleanup,
  managedCleanup,
  sanitization,
  logs: logAnalysis,
  directRedisEvidence: 'NOT_RUN',
  directDatabaseEvidence: 'NOT_RUN',
  realProvider: 'NOT_USED',
  browser: 'NOT_RUN'
}
await evidence.json('summary.json', summary)
console.log(JSON.stringify({
  case: config.caseName,
  status,
  expectedOutcome,
  failedAssertions: checks.failures.length,
  evidenceDirectory: runDirectory
}, null, 2))
if (status !== 'PASS') process.exitCode = 1
