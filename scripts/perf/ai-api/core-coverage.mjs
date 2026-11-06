import { mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { randomUUID } from 'node:crypto'

const baseInput = process.env.BASE_URL?.trim()
if (!baseInput) throw new Error('BASE_URL is required')
const base = new URL(baseInput)
if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password || base.search || base.hash) {
  throw new Error('BASE_URL must be an absolute HTTP(S) URL without credentials, query, or fragment')
}
if (process.env.EVAL_ISOLATED_ENV !== 'true') throw new Error('EVAL_ISOLATED_ENV=true is required')
if (process.env.EVAL_ALLOW_WRITES !== 'true') throw new Error('EVAL_ALLOW_WRITES=true is required')
const localHosts = new Set(['localhost', '127.0.0.1', '::1', '[::1]'])
if (!localHosts.has(base.hostname) && process.env.EVAL_CONFIRM_REMOTE !== 'true') {
  throw new Error('EVAL_CONFIRM_REMOTE=true is required for a remote BASE_URL')
}
const tenantId = process.env.TENANT_ID?.trim()
const userId = process.env.USER_ID?.trim()
if (!tenantId || !userId) throw new Error('TENANT_ID and USER_ID are required')
const modelConcurrencyConfigured = Number.parseInt(process.env.EVAL_MODEL_CONCURRENCY ?? '6', 10)
if (!Number.isInteger(modelConcurrencyConfigured) || modelConcurrencyConfigured < 1 || modelConcurrencyConfigured > 10) {
  throw new Error('EVAL_MODEL_CONCURRENCY must be an integer between 1 and 10')
}
const timeoutMs = Number.parseInt(process.env.TIMEOUT_MS ?? '120000', 10)
const outDir = path.resolve(process.env.OUT_DIR ?? 'verification-results/ai-core-coverage')
const runPrefix = process.env.COVERAGE_PREFIX?.trim() || `core-${randomUUID()}`
const createdTasks = new Set()
const createdSessions = new Set()
const activeRuns = new Set()
const cleanupLog = []
const cleanupErrors = []
let modelInFlight = 0
let maxObservedInFlight = 0
let modelFailure = null
const modelWaiters = []
const safelyRecoveredModelErrors = new WeakSet()

class CoverageError extends Error {}

function assert(condition, message) {
  if (!condition) throw new CoverageError(message)
}

function payloadOf(response) {
  return response.body?.data ?? response.body
}

function headers(json = false) {
  return {
    'X-Tenant-Id': tenantId,
    'X-User-Id': userId,
    ...(json ? { 'Content-Type': 'application/json' } : {})
  }
}

function tripModelCircuit(error, phase = 'model-execution') {
  const failure = error instanceof Error ? error : new CoverageError(String(error))
  if (modelFailure === null) modelFailure = { error: failure, phase }
  for (const waiter of modelWaiters.splice(0)) waiter.reject(modelFailure.error)
}

async function acquireModel() {
  if (modelFailure !== null) throw modelFailure.error
  while (modelInFlight >= modelConcurrencyConfigured) {
    await new Promise((resolve, reject) => modelWaiters.push({ resolve, reject }))
    if (modelFailure !== null) throw modelFailure.error
  }
  modelInFlight += 1
  maxObservedInFlight = Math.max(maxObservedInFlight, modelInFlight)
}

function releaseModel() {
  modelInFlight -= 1
  if (modelFailure !== null) return
  const next = modelWaiters.shift()
  if (next) next.resolve()
}

function isSafelyRecoveredModelError(error) {
  return error !== null && (typeof error === 'object' || typeof error === 'function')
    && safelyRecoveredModelErrors.has(error)
}

async function modelCall(fn) {
  await acquireModel()
  let safeToRelease = false
  try {
    const result = await fn()
    safeToRelease = true
    return result
  }
  catch (error) {
    tripModelCircuit(error)
    safeToRelease = isSafelyRecoveredModelError(error)
    throw error
  }
  finally {
    if (safeToRelease) releaseModel()
  }
}

const coverage = Object.fromEntries([
  ['POST /api/ai/chat', true],
  ['POST /api/ai/confirm', true],
  ['POST /api/ai/frontend-tool-result', true],
  ['GET /api/ai/events/{runId}', true],
  ['POST /api/ai/cancel/{runId}', true],
  ['POST /api/ai/cancel/client-request', true],
  ['GET /api/ai/cancel/{runId}', true],
  ['GET /api/ai/startup-resume', true],
  ['POST /api/ai/startup-resume/scan', true],
  ['GET /api/ai/runs', true],
  ['GET /api/ai/runs/{runId}', true],
  ['GET /api/ai/runs/current', true],
  ['POST /api/ai/runs/{runId}/resume', true],
  ['GET /api/ai/history/{sessionId}', true],
  ['GET /api/ai/context/{sessionId}', true],
  ['GET /api/ai/health', true],
  ['GET /api/ai/pending/{runId}', true],
  ['POST /api/ai/feedback', true],
  ['GET /api/ai/feedback', true],
  ['GET /api/ai/kb/status', true],
  ['GET /api/ai/kb/docs', true],
  ['POST /api/ai/form/attachments', true],
  ['GET /api/ai/form/option-sources/{sourceId}', true],
  ['POST /api/ai/session', true],
  ['DELETE /api/ai/session/{sessionId}', true],
  ['GET /api/ai/tools', true],
  ['GET /api/ai/tenants', true]
].map(([key]) => [key, {
  method: key.split(' ')[0],
  path: key.slice(key.indexOf(' ') + 1),
  covered: false,
  latencyMs: null,
  httpStatus: null,
  assertions: [],
  error: null,
  cleanup: { done: false, detail: 'pending finally cleanup' },
  attempts: []
}]))

function record(key, { httpStatus, latencyMs, assertions = [], error = null }) {
  const entry = coverage[key]
  assert(entry, `unregistered endpoint ${key}`)
  if (error !== null) entry.covered = false
  else if (entry.error === null) entry.covered = true
  entry.latencyMs = latencyMs
  entry.httpStatus = httpStatus
  entry.assertions.push(...assertions)
  if (entry.error === null) entry.error = error
  entry.attempts.push({ httpStatus, latencyMs, assertions, error })
}

async function request(url, options = {}) {
  const started = Date.now()
  const response = await fetch(`${base.toString().replace(/\/$/, '')}${url}`, {
    ...options,
    headers: { ...headers(options.json === true), ...(options.headers ?? {}) },
    signal: AbortSignal.timeout(timeoutMs)
  })
  const text = await response.text()
  let body = null
  try { body = text === '' ? null : JSON.parse(text) } catch { body = text }
  return {
    status: response.status,
    body,
    text,
    contentType: response.headers.get('content-type') ?? '',
    latencyMs: Date.now() - started
  }
}

async function call(key, url, options, assertion) {
  const started = Date.now()
  let response = null
  try {
    response = await request(url, options)
    const assertions = await assertion(response)
    record(key, {
      httpStatus: response.status,
      latencyMs: response.latencyMs,
      assertions: Array.isArray(assertions) ? assertions : [assertions]
    })
    return response
  }
  catch (error) {
    record(key, {
      httpStatus: response?.status ?? null,
      latencyMs: response?.latencyMs ?? Date.now() - started,
      assertions: [],
      error: error instanceof Error ? error.message : String(error)
    })
    throw error
  }
}

function ok(response, detail = 'HTTP 2xx') {
  assert(response.status >= 200 && response.status < 300, `${detail}: got ${response.status}: ${response.text}`)
  return detail
}

function dataObject(response) {
  const data = payloadOf(response)
  assert(data !== null && typeof data === 'object' && !Array.isArray(data), `expected object payload: ${response.text}`)
  return data
}

function dataArray(response) {
  const data = payloadOf(response)
  assert(Array.isArray(data), `expected array payload: ${response.text}`)
  return data
}

function parseSseBlock(block) {
  const data = block.split(/\r?\n/)
    .filter((line) => line.startsWith('data:'))
    .map((line) => `${line.slice(5).trimStart()}\n`)
    .join('')
  if (data === '') return null
  try { return JSON.parse(data) } catch { return null }
}

async function settleRunAfterSseError(runId, lastSeq, error) {
  if (!runId) {
    const detail = 'runId=unknown:SSE cleanup ownership unknown'
    cleanupLog.push(detail)
    cleanupErrors.push(detail)
    return false
  }
  activeRuns.add(runId)
  try {
    const response = await request(`/api/ai/cancel/${runId}`, { method: 'POST' })
    assert(response.status >= 200 && response.status < 300, `SSE error cancel ${response.status}`)
    assert(dataObject(response).cancelled === true, `SSE error cancel not accepted: ${response.text}`)
    const terminal = await readSseRaw(runId, lastSeq, (frame) => frame.type === 'done' || frame.type === 'error', false)
    assert(terminal.matched.type === 'done' && terminal.matched.cancelled === true,
      'SSE error cancel accepted but no cancelled terminal frame')
    activeRuns.delete(runId)
    cleanupLog.push(`sse-error-cancel:${runId}=ok`)
    if (error !== null && (typeof error === 'object' || typeof error === 'function')) {
      safelyRecoveredModelErrors.add(error)
    }
    return true
  }
  catch (cleanupError) {
    const detail = `sse-error-cancel:${runId}=${cleanupError instanceof Error ? cleanupError.message : String(cleanupError)}`
    cleanupLog.push(detail)
    cleanupErrors.push(detail)
    return false
  }
}

async function readSseRaw(runId, lastSeq, predicate, recoverOnError = true) {
  const started = Date.now()
  let reader = null
  let buffer = ''
  let previousSeq = lastSeq ?? 0
  let recoverySeq = previousSeq
  let matched = null
  const frames = []
  try {
    const response = await fetch(`${base.toString().replace(/\/$/, '')}/api/ai/events/${runId}${lastSeq == null ? '' : `?lastSeq=${lastSeq}`}`, {
      headers: { ...headers(), Accept: 'text/event-stream' },
      signal: AbortSignal.timeout(timeoutMs)
    })
    const contentType = response.headers.get('content-type') ?? ''
    assert(response.status === 200 && contentType.includes('text/event-stream'),
      `SSE expected 200 text/event-stream, got ${response.status} ${contentType}`)
    reader = response.body.getReader()
    const decoder = new TextDecoder()
    const processFrame = (frame) => {
      runId = frame.runId ?? runId
      if (runId !== null) activeRuns.add(runId)
      if (typeof frame.seq === 'number') {
        assert(frame.seq > previousSeq, `SSE seq must increase: ${frame.seq} <= ${previousSeq}`)
        previousSeq = frame.seq
      }
      if (recoverOnError) trackCreatedTaskFrame(frame)
      frames.push(frame)
      if (predicate(frame)) matched = frame
      recoverySeq = previousSeq
    }
    while (matched === null) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let boundary
      while ((boundary = buffer.search(/\r?\n\r?\n/)) >= 0 && matched === null) {
        const block = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary).replace(/^\r?\n/, '')
        const frame = parseSseBlock(block)
        if (frame !== null) processFrame(frame)
      }
    }
    if (matched === null && buffer.trim() !== '') {
      const frame = parseSseBlock(buffer)
      if (frame !== null) processFrame(frame)
    }
    assert(matched !== null, `SSE predicate not reached; frames=${frames.map((frame) => frame.type).join(',')}`)
    return { frames, matched, lastSeq: previousSeq, latencyMs: Date.now() - started }
  }
  catch (error) {
    if (recoverOnError) {
      tripModelCircuit(error, runId ? 'sse-failed' : 'sse-unknown')
      await settleRunAfterSseError(runId, recoverySeq, error)
    }
    throw error
  }
  finally {
    if (reader !== null) await reader.cancel().catch(() => {})
  }
}

async function startChat(sessionId, message, context, predicate, awaitTerminal = false) {
  const started = Date.now()
  return modelCall(async () => {
    let chat
    try {
      chat = await readSseChat(sessionId, message, context, predicate)
    }
    catch (error) {
      record('POST /api/ai/chat', {
        httpStatus: null,
        latencyMs: Date.now() - started,
        assertions: [],
        error: error instanceof Error ? error.message : String(error)
      })
      throw error
    }
    if (!awaitTerminal) return chat
    const terminal = await callSse(chat.runId, chat.lastSeq, (frame) => {
      if (frame.type !== 'done' && frame.type !== 'error') return false
      assert(frame.type === 'done' && frame.cancelled !== true,
        `model scenario did not complete normally: ${frame.type}`)
      return true
    })
    return { ...chat, terminal: terminal.matched, terminalLatencyMs: terminal.latencyMs }
  })
}

async function readSseChat(sessionId, message, context, predicate, recoverOnError = true) {
  const started = Date.now()
  const body = { sessionId, message, context, clientRequestId: `${runPrefix}-${randomUUID()}` }
  let reader = null
  let buffer = ''
  let previousSeq = 0
  let recoverySeq = 0
  let runId = null
  let matched = null
  const frames = []
  try {
    const response = await fetch(`${base.toString().replace(/\/$/, '')}/api/ai/chat`, {
      method: 'POST',
      headers: headers(true),
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(timeoutMs)
    })
    const contentType = response.headers.get('content-type') ?? ''
    assert(response.status === 200 && contentType.includes('text/event-stream'),
      `chat expected SSE, got ${response.status} ${contentType}: ${await response.text()}`)
    reader = response.body.getReader()
    const decoder = new TextDecoder()
    const processFrame = (frame) => {
      runId = frame.runId ?? runId
      if (runId !== null) activeRuns.add(runId)
      if (typeof frame.seq === 'number') {
        assert(frame.seq > previousSeq, `chat seq must increase: ${frame.seq} <= ${previousSeq}`)
        previousSeq = frame.seq
      }
      trackCreatedTaskFrame(frame)
      frames.push(frame)
      if (predicate(frame)) matched = frame
      recoverySeq = previousSeq
    }
    while (matched === null) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let boundary
      while ((boundary = buffer.search(/\r?\n\r?\n/)) >= 0 && matched === null) {
        const block = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary).replace(/^\r?\n/, '')
        const frame = parseSseBlock(block)
        if (frame !== null) processFrame(frame)
      }
    }
    assert(runId !== null, 'chat start did not carry runId')
    activeRuns.add(runId)
    assert(matched !== null, `chat predicate not reached; frames=${frames.map((frame) => `${frame.type}:${frame.name ?? ''}`).join(',')}`)
    return { runId, frames, matched, lastSeq: previousSeq, latencyMs: Date.now() - started }
  }
  catch (error) {
    if (recoverOnError) {
      tripModelCircuit(error, runId ? 'sse-failed' : 'sse-unknown')
      await settleRunAfterSseError(runId, recoverySeq, error)
    }
    throw error
  }
  finally {
    if (reader !== null) await reader.cancel().catch(() => {})
  }
}

async function callSse(runId, lastSeq, predicate) {
  const started = Date.now()
  try {
    const result = await readSseRaw(runId, lastSeq, predicate)
    record('GET /api/ai/events/{runId}', {
      httpStatus: 200,
      latencyMs: result.latencyMs,
      assertions: [`SSE ${result.matched.type}`]
    })
    return result
  }
  catch (error) {
    record('GET /api/ai/events/{runId}', {
      httpStatus: null,
      latencyMs: Date.now() - started,
      assertions: [],
      error: error instanceof Error ? error.message : String(error)
    })
    throw error
  }
}

function formOf(frame) {
  if (frame.name !== 'generative_form') return null
  try { return JSON.parse(frame.args ?? '{}').form ?? null } catch { return null }
}

function trackCreatedTaskFrame(frame) {
  if (frame.type !== 'tool_result' || frame.name !== 'create_task' || frame.outcome !== 'ok') return
  const taskId = taskIdFrom([frame])
  if (!Number.isSafeInteger(taskId) || taskId <= 0) {
    throw new CoverageError('successful create_task result has no task id; cleanup ownership unknown')
  }
  createdTasks.add(taskId)
}

function taskIdFrom(frames) {
  for (const frame of frames) {
    if (frame.type === 'tool_result' && frame.name === 'create_task') {
      const structured = Number(frame.createdTaskId ?? frame.data?.createdTaskId)
      if (Number.isSafeInteger(structured) && structured > 0) return structured
      const match = /任务\s*#(\d+)/.exec(String(frame.result ?? ''))
      if (match) return Number(match[1])
    }
  }
  return null
}

async function cleanupOne(kind, id, fn) {
  try {
    await fn()
    cleanupLog.push(`${kind}=${id}:ok`)
  }
  catch (error) {
    const detail = `${kind}=${id}:${error instanceof Error ? error.message : String(error)}`
    cleanupLog.push(detail)
    cleanupErrors.push(detail)
  }
}

async function knowledgeWorkflow() {
  const sessionId = `${runPrefix}-kb-${randomUUID()}`
  createdSessions.add(sessionId)
  const knowledge = await startChat(sessionId, '请查询区域费率的正式管理口径，并给出引用。', null,
    (frame) => frame.type === 'tool_start' && frame.name === 'kb_search', true)
  record('POST /api/ai/chat', {
    httpStatus: 200,
    latencyMs: knowledge.latencyMs,
    assertions: ['real KB retrieval through chat', 'kb_search observed', 'done terminal confirmed']
  })
  activeRuns.delete(knowledge.runId)
}

async function attachmentWorkflow() {
  const attachmentSession = `${runPrefix}-attachment-${randomUUID()}`
  createdSessions.add(attachmentSession)
  const attachmentChat = await startChat(attachmentSession,
    '请调用 generative_form，purpose=GENERAL_CLARIFY，只生成一个名为 evidence 的 attachment 字段用于收集一个文本附件，不要调用其它工具。',
    null,
    (frame) => {
      const form = formOf(frame)
      return form !== null && Array.isArray(form.fields) && form.fields.some((field) => field.type === 'attachment')
    })
  const form = formOf(attachmentChat.matched)
  const attachmentField = form.fields.find((field) => field.type === 'attachment')
  assert(attachmentField?.key, 'attachment field key missing')
  record('POST /api/ai/chat', {
    httpStatus: 200,
    latencyMs: attachmentChat.latencyMs,
    assertions: ['real generative_form with attachment field pending']
  })
  const formData = new FormData()
  formData.append('file', new Blob([`core coverage ${attachmentSession}\n`], { type: 'text/plain' }), 'core-evidence.txt')
  formData.append('runId', attachmentChat.runId)
  formData.append('toolCallId', attachmentChat.matched.toolCallId)
  formData.append('sessionId', attachmentSession)
  formData.append('fieldKey', attachmentField.key)
  const uploaded = await call('POST /api/ai/form/attachments', '/api/ai/form/attachments', { method: 'POST', body: formData }, async (response) => {
    ok(response)
    const data = dataObject(response)
    assert(typeof data.fileId === 'string' && data.fileId.length > 0, 'attachment fileId missing')
    return `uploaded ${data.fileId}`
  })
  const fileId = dataObject(uploaded).fileId
  const { terminal } = await modelCall(async () => {
    const submitted = await call('POST /api/ai/frontend-tool-result', '/api/ai/frontend-tool-result', {
      method: 'POST',
      json: true,
      body: JSON.stringify({
        runId: attachmentChat.runId,
        toolCallId: attachmentChat.matched.toolCallId,
        result: JSON.stringify({ [attachmentField.key]: fileId }),
        source: 'http-post', outcome: 'ok'
      })
    }, async (response) => {
      ok(response)
      assert(dataObject(response).accepted === true, 'attachment form result not accepted')
      return 'real attachment form result accepted'
    })
    const terminal = await callSse(attachmentChat.runId, attachmentChat.lastSeq, (frame) => {
      if (frame.type !== 'done' && frame.type !== 'error') return false
      assert(frame.type === 'done' && frame.cancelled !== true, 'attachment scenario did not complete normally')
      return true
    })
    record('POST /api/ai/chat', {
      httpStatus: 200,
      latencyMs: terminal.latencyMs,
      assertions: ['attachment scenario done terminal confirmed']
    })
    return { submitted, terminal }
  })
  activeRuns.delete(attachmentChat.runId)
}

async function independentModelWorker(workflow) {
  try {
    return await workflow()
  }
  catch (error) {
    tripModelCircuit(error, 'model-worker')
    throw error
  }
}

async function runIndependentModelScenarios() {
  const results = await Promise.allSettled([
    knowledgeWorkflow, attachmentWorkflow, knowledgeWorkflow, knowledgeWorkflow, knowledgeWorkflow, knowledgeWorkflow
  ].map((workflow) => independentModelWorker(workflow)))
  if (modelFailure !== null) throw modelFailure.error
  const failed = results.find((result) => result.status === 'rejected')
  if (failed) throw failed.reason
}

const mainSession = `${runPrefix}-main-${randomUUID()}`
const feedbackMessageId = `${runPrefix}-feedback-${randomUUID()}`
let feedbackCreated = false

async function main() {
  await call('GET /api/ai/health', '/api/ai/health', {}, async (response) => {
    const data = dataObject(response)
    assert([200, 503].includes(response.status), `health unexpected ${response.status}`)
    assert(typeof data.code === 'string' && data.code.length > 0, 'health code missing')
    return `health ${response.status} ${data.code}`
  })
  await call('GET /api/ai/tools', '/api/ai/tools', {}, async (response) => {
    ok(response)
    const data = dataObject(response)
    assert(Array.isArray(data.toolNames) && data.toolNames.includes('create_task'), 'create_task not disclosed')
    return `tools=${data.toolNames.length}`
  })
  await call('GET /api/ai/tenants', '/api/ai/tenants', {}, async (response) => {
    ok(response)
    const data = dataArray(response)
    assert(data.some((tenant) => String(tenant.tenantId ?? tenant.id) === tenantId), 'current tenant not in directory')
    return `tenants=${data.length}`
  })
  await call('GET /api/ai/kb/status', '/api/ai/kb/status', {}, async (response) => {
    ok(response)
    const data = dataObject(response)
    assert(typeof data.enabled === 'boolean' && typeof data.ready === 'boolean', 'KB enabled/ready missing')
    return `kb enabled=${data.enabled} ready=${data.ready}`
  })
  await call('GET /api/ai/kb/docs', '/api/ai/kb/docs', {}, async (response) => {
    ok(response)
    const data = dataArray(response)
    assert(data.every((doc) => typeof doc.id === 'string' && typeof doc.title === 'string'), 'KB doc shape invalid')
    return `kb docs=${data.length}`
  })
  await call('GET /api/ai/form/option-sources/{sourceId}', '/api/ai/form/option-sources/definitions?value=GLOBAL', {}, async (response) => {
    ok(response)
    const data = dataObject(response)
    assert(data.sourceId === 'definitions' && Array.isArray(data.options) && data.options.length > 0,
      'definition option source is empty')
    return `options=${data.options.length}`
  })

  const clientCancelSession = `${runPrefix}-client-cancel-${randomUUID()}`
  createdSessions.add(clientCancelSession)
  await call('POST /api/ai/cancel/client-request', '/api/ai/cancel/client-request', {
    method: 'POST',
    json: true,
    body: JSON.stringify({ sessionId: clientCancelSession, clientRequestId: `${runPrefix}-pre-cancel-${randomUUID()}` })
  }, async (response) => {
    ok(response)
    const data = dataObject(response)
    assert(data.cancelled === true && data.pendingRun === true, 'clientRequestId pre-cancel tombstone missing')
    return `clientRequestId pre-cancel ${data.clientRequestId}`
  })

  createdSessions.add(mainSession)
  const mainChat = await startChat(mainSession, '创建导出任务。', null,
    (frame) => frame.type === 'confirm_request' && frame.name === 'create_task')
  const mainRun = mainChat.runId
  const createCallId = mainChat.matched.toolCallId
  record('POST /api/ai/chat', {
    httpStatus: 200,
    latencyMs: mainChat.latencyMs,
    assertions: ['real create_task confirm pending', 'chat seq strictly increasing']
  })

  await call('GET /api/ai/pending/{runId}', `/api/ai/pending/${mainRun}`, {}, async (response) => {
    ok(response)
    const data = dataArray(response)
    assert(data.some((pending) => pending.toolCallId === createCallId && pending.name === 'create_task'
      && pending.status === 'PENDING'), 'create_task pending missing')
    return `pending=${createCallId}`
  })
  await call('GET /api/ai/runs/{runId}', `/api/ai/runs/${mainRun}`, {}, async (response) => {
    ok(response)
    const data = dataObject(response)
    assert(data.exists === true && data.runId === mainRun, 'run detail missing')
    return `run exists ${mainRun}`
  })
  await call('GET /api/ai/runs/current', `/api/ai/runs/current?sessionId=${encodeURIComponent(mainSession)}`, {}, async (response) => {
    ok(response)
    const data = dataObject(response)
    assert(data.found === true && data.runId === mainRun, 'current run missing')
    return `current run ${mainRun}`
  })
  await call('GET /api/ai/runs', '/api/ai/runs', {}, async (response) => {
    ok(response)
    const data = dataArray(response)
    assert(data.some((run) => run.runId === mainRun), 'run list lacks current run')
    return `runs=${data.length}`
  })
  await call('GET /api/ai/history/{sessionId}', `/api/ai/history/${mainSession}?latest=20`, {}, async (response) => {
    ok(response)
    const data = dataObject(response)
    assert(Array.isArray(data.messages) && data.messages.length > 0, 'history is empty after chat')
    return `history messages=${data.messages.length}`
  })
  await call('GET /api/ai/context/{sessionId}', `/api/ai/context/${mainSession}`, {}, async (response) => {
    ok(response)
    const data = dataObject(response)
    assert(data.sessionId === mainSession, 'context sessionId mismatch')
    return `context turnNo=${data.turnNo ?? 'unknown'}`
  })

  const replay = await callSse(mainRun, 0, (frame) => frame.type === 'confirm_request' && frame.name === 'create_task')
  record('GET /api/ai/events/{runId}', {
    httpStatus: 200,
    latencyMs: replay.latencyMs,
    assertions: ['real SSE replay reached create_task confirm', 'replay seq strictly increasing']
  })
  await call('GET /api/ai/events/{runId}', `/api/ai/events/${mainRun}?probe=1`, {
    headers: { Accept: 'application/json' }
  }, async (response) => {
    ok(response)
    const data = dataObject(response)
    assert(data.ready === true && !response.contentType.includes('text/event-stream'), 'events probe did not return ready JSON')
    return 'events probe=1 ready JSON'
  })
  await call('GET /api/ai/events/{runId}', `/api/ai/events/${mainRun}`, {
    headers: { Accept: 'application/json' }
  }, async (response) => {
    assert(response.status === 406, `events cross representation expected 406, got ${response.status}`)
    return 'events without probe and Accept:application/json is 406'
  })

  const confirmed = await modelCall(async () => {
    const approval = await call('POST /api/ai/confirm', '/api/ai/confirm', {
      method: 'POST',
      json: true,
      body: JSON.stringify({ runId: mainRun, toolCallId: createCallId, approved: true, reason: 'core coverage approval' })
    }, async (response) => {
      ok(response)
      const data = dataObject(response)
      assert(data.accepted === true && data.approved === true, 'create_task approval not accepted')
      return 'real create_task approved'
    })
    return callSse(mainRun, mainChat.lastSeq, (frame) => frame.type === 'frontend_tool_request' && frame.name === 'navigate_to')
  })
  const taskId = taskIdFrom(confirmed.frames)
  assert(Number.isSafeInteger(taskId) && taskId > 0, 'create_task result has no task id after approval')
  createdTasks.add(taskId)
  const navigationCallId = confirmed.matched.toolCallId
  const navigationResult = `已恢复到任务 #${taskId} 的向导（导出）（页面已就绪，任务 #${taskId} 已绑定，可继续执行本页动作）`
  const navigationResponse = await modelCall(async () => {
    const response = await call('POST /api/ai/frontend-tool-result', '/api/ai/frontend-tool-result', {
      method: 'POST',
      json: true,
      body: JSON.stringify({
        runId: mainRun,
        toolCallId: navigationCallId,
        result: navigationResult,
        source: 'frontend-executor',
        outcome: 'ok',
        context: {
          page: 'export', taskType: 'EXPORT', step: 'SELECT_DEFS', taskId, taskStatus: 'ACTIVE',
          extra: { pageId: 'export', selectedDefs: [], selectionSynced: true, importMode: null }
        }
      })
    }, async (response) => {
      ok(response)
      const data = dataObject(response)
      assert(data.accepted === true && data.status === 'FRONTEND_RESULT', 'navigate_to result not accepted')
      return 'real navigate_to frontend result accepted'
    })
    return callSse(mainRun, confirmed.lastSeq, (frame) => frame.type === 'suspended')
  })

  await call('POST /api/ai/cancel/{runId}', `/api/ai/cancel/${mainRun}`, { method: 'POST' }, async (response) => {
    ok(response)
    const data = dataObject(response)
    assert(data.cancelled === true, 'run cancellation not accepted')
    await callSse(mainRun, navigationResponse.lastSeq, (frame) => {
      if (frame.type !== 'done' && frame.type !== 'error') return false
      assert(frame.type === 'done' && frame.cancelled === true,
        'cancel accepted but terminal frame was not confirmed cancelled')
      return true
    })
    activeRuns.delete(mainRun)
    return `cancelled ${mainRun}`
  })
  await call('GET /api/ai/cancel/{runId}', `/api/ai/cancel/${mainRun}`, {}, async (response) => {
    ok(response)
    const data = dataObject(response)
    assert(data.redisFlag === true || data.localFlag === true, 'cancel flag missing')
    return `cancel status ${mainRun}`
  })
  await modelCall(() => call('POST /api/ai/runs/{runId}/resume', `/api/ai/runs/${mainRun}/resume`, { method: 'POST' }, async (response) => {
    ok(response)
    const data = dataObject(response)
    assert(data.reason === 'ALREADY_CANCELLED' && data.resumed === false, `unexpected resume outcome ${response.text}`)
    return `terminal resume idempotent ${data.reason}`
  }))
  await call('GET /api/ai/startup-resume', '/api/ai/startup-resume', {}, async (response) => {
    ok(response)
    const data = dataObject(response)
    assert(typeof data.scanned === 'boolean' || typeof data.reason === 'string', 'startup scan status missing')
    return `startup scanned=${data.scanned ?? data.reason}`
  })
  await modelCall(() => call('POST /api/ai/startup-resume/scan', '/api/ai/startup-resume/scan', { method: 'POST' }, async (response) => {
    ok(response)
    const data = dataObject(response)
    assert(typeof data.scanned === 'boolean' || typeof data.reason === 'string', 'manual startup scan missing result')
    return `manual startup scanned=${data.scanned ?? data.reason}`
  }))

  await runIndependentModelScenarios()

  await call('POST /api/ai/feedback', '/api/ai/feedback', {
    method: 'POST',
    json: true,
    body: JSON.stringify({ messageId: feedbackMessageId, sessionId: mainSession, rating: 1, reason: 'core coverage' })
  }, async (response) => {
    ok(response)
    assert(dataObject(response).saved === true, 'feedback was not saved')
    feedbackCreated = true
    return `feedback saved ${feedbackMessageId}`
  })
  await call('GET /api/ai/feedback', '/api/ai/feedback', {}, async (response) => {
    ok(response)
    const data = dataArray(response)
    assert(data.some((entry) => entry.messageId === feedbackMessageId), 'feedback list lacks created entry')
    return `feedback listed ${feedbackMessageId}`
  })
  const lifecycle = await call('POST /api/ai/session', '/api/ai/session', { method: 'POST', json: true }, async (response) => {
    ok(response)
    const data = dataObject(response)
    assert(typeof data.sessionIdText === 'string' && data.sessionIdText.length > 0, 'sessionIdText missing')
    return `session created ${data.sessionIdText}`
  })
  const lifecycleSession = dataObject(lifecycle).sessionIdText
  createdSessions.add(lifecycleSession)
  await call('DELETE /api/ai/session/{sessionId}', `/api/ai/session/${lifecycleSession}`, { method: 'DELETE' }, async (response) => {
    ok(response)
    const data = dataObject(response)
    assert(data.complete === true, `session purge incomplete: ${response.text}`)
    return `session purge complete ${lifecycleSession}`
  })
  createdSessions.delete(lifecycleSession)
}

let fatalError = null
try {
  await main()
}
catch (error) {
  fatalError = error instanceof Error ? error.message : String(error)
}
finally {
  if (feedbackCreated) {
    await cleanupOne('feedback-rating-zero', feedbackMessageId, async () => {
      const response = await request('/api/ai/feedback', {
        method: 'POST', json: true,
        body: JSON.stringify({ messageId: feedbackMessageId, sessionId: mainSession, rating: 0 })
      })
      assert(response.status >= 200 && response.status < 300, `feedback cleanup ${response.status}`)
      assert(dataObject(response).cancelled === true, `feedback cleanup not cancelled: ${response.text}`)
    })
  }
  for (const runId of activeRuns) {
    await cleanupOne(`cancel-run:${runId}`, runId, async () => {
      const response = await request(`/api/ai/cancel/${runId}`, { method: 'POST' })
      assert(response.status >= 200 && response.status < 300, `cancel cleanup ${response.status}`)
    })
  }
  for (const taskId of createdTasks) {
    await cleanupOne(`delete-task:${taskId}`, taskId, async () => {
      const response = await request(`/api/tasks/${taskId}`, { method: 'DELETE' })
      assert([200, 404].includes(response.status), `task cleanup ${response.status}`)
    })
  }
  for (const sessionId of createdSessions) {
    await cleanupOne(`delete-session:${sessionId}`, sessionId, async () => {
      const response = await request(`/api/ai/session/${sessionId}`, { method: 'DELETE' })
      assert(response.status >= 200 && response.status < 300, `session cleanup ${response.status}`)
      const data = dataObject(response)
      const deferred = Array.isArray(data.deferred) ? data.deferred : []
      const errors = Array.isArray(data.errors) ? data.errors : []
      assert(data.complete === true && deferred.length === 0 && errors.length === 0,
        `session cleanup incomplete: ${response.text}`)
    })
  }
  const missing = Object.values(coverage).filter((entry) => !entry.covered || entry.assertions.length === 0)
  const complete = missing.length === 0 && cleanupErrors.length === 0 && fatalError === null && modelFailure === null
  for (const entry of Object.values(coverage)) {
    entry.cleanup = {
      done: cleanupErrors.length === 0,
      detail: cleanupErrors.length === 0 ? 'created resources cleaned' : cleanupErrors.join('; ')
    }
  }
  const report = {
    generatedAt: new Date().toISOString(),
    baseUrl: base.toString(),
    tenantId,
    userId,
    configured: modelConcurrencyConfigured,
    modelConcurrencyConfigured,
    independentModelWorkers: 6,
    maxObservedInFlight,
    modelCircuitBreaker: {
      failed: modelFailure !== null,
      phase: modelFailure?.phase ?? null,
      error: modelFailure?.error.message ?? null,
      modelInFlight,
      slotsNotSafelyReleased: modelInFlight
    },
    coverage: {
      complete,
      expected: Object.keys(coverage).length,
      covered: Object.values(coverage).filter((entry) => entry.covered).length,
      notRun: missing.map((entry) => `${entry.method} ${entry.path}`)
    },
    endpoints: coverage,
    cleanup: cleanupLog,
    cleanupErrors,
    fatalError,
    deleteEvidenceDependencies: [
      'scripts/perf/redis/perf_redis/scenarios.py:226-233 (repeat_delete and concurrent_delete)',
      'scripts/perf/redis/perf_redis/verify.py:248-337 (owned keys, guard/pending and gen/ver final state)',
      'scripts/verification/ai-current-protocol-api.mjs:67-87 (repeat delete and empty history)'
    ]
  }
  await mkdir(outDir, { recursive: true })
  await writeFile(path.join(outDir, 'coverage-results.json'), JSON.stringify(report, null, 2) + '\n')
  console.log(JSON.stringify({
    complete,
    expected: report.coverage.expected,
    covered: report.coverage.covered,
    notRun: report.coverage.notRun,
    modelConcurrencyConfigured,
    maxObservedInFlight,
    fatalError,
    output: path.join(outDir, 'coverage-results.json')
  }, null, 2))
  if (!complete) process.exitCode = 1
}
