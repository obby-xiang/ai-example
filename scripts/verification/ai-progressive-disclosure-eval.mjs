import { mkdir, readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { randomUUID } from 'node:crypto'
import { isDeepStrictEqual } from 'node:util'

const baseInput = process.env.BASE_URL?.trim()
if (!baseInput) throw new Error('BASE_URL must be set explicitly')
let parsedBase
try { parsedBase = new URL(baseInput) } catch { throw new Error('BASE_URL must be an absolute HTTP(S) URL') }
if (!['http:', 'https:'].includes(parsedBase.protocol) || parsedBase.username || parsedBase.password || parsedBase.search || parsedBase.hash) {
  throw new Error('BASE_URL must be an absolute HTTP(S) URL without credentials, query, or fragment')
}
if (process.env.EVAL_ISOLATED_ENV !== 'true') throw new Error('EVAL_ISOLATED_ENV=true is required')
const localHosts = new Set(['localhost', '127.0.0.1', '::1', '[::1]'])
const remote = !localHosts.has(parsedBase.hostname)
if (remote && process.env.EVAL_CONFIRM_REMOTE !== 'true') throw new Error('EVAL_CONFIRM_REMOTE=true is required for a non-local BASE_URL')
const base = parsedBase.toString().replace(/\/$/, '')
const tenantId = process.env.TENANT_ID
const userId = process.env.USER_ID
if (!tenantId || !userId) throw new Error('TENANT_ID and USER_ID must be set explicitly')
const rounds = Math.max(1, Number.parseInt(process.env.ROUNDS ?? '1', 10) || 1)
const timeoutMs = Math.max(1_000, Number.parseInt(process.env.TIMEOUT_MS ?? '120000', 10) || 120_000)
const fixturePath = path.resolve(process.env.FIXTURE ?? 'scripts/verification/fixtures/ai-rag-eval.json')
const outDir = path.resolve(process.env.OUT_DIR ?? 'verification-results/ai-progressive-disclosure-eval')
const disclosureUrl = process.env.DISCLOSURE_URL ?? null
const fixture = JSON.parse(await readFile(fixturePath, 'utf8'))
const definitions = Array.isArray(fixture.cases) ? fixture.cases : []
const writesRequired = definitions.some((definition) => definition.kind === 'ACTION' || definition.kind === 'MIXED' || definition.autoApprove === true)
const allowWrites = process.env.EVAL_ALLOW_WRITES === 'true'
if (writesRequired && !allowWrites) throw new Error('EVAL_ALLOW_WRITES=true is required for ACTION/MIXED cases and confirm writes')
const queryCondDefinitions = definitions.filter((definition) => definition.context?.step === 'QUERY_COND')
const queryCondTaskId = process.env.QUERY_COND_TASK_ID?.trim() ?? ''
const reasoningDisplaySetting = process.env.REASONING_DISPLAY_ENABLED?.trim()
if (reasoningDisplaySetting !== undefined && !['true', 'false'].includes(reasoningDisplaySetting)) {
  throw new Error('REASONING_DISPLAY_ENABLED must be true or false when provided')
}
const reasoningDisplayEnabled = reasoningDisplaySetting === undefined ? null : reasoningDisplaySetting === 'true'

function headers(json = false) {
  return {
    'X-Tenant-Id': tenantId,
    'X-User-Id': userId,
    ...(json ? { 'Content-Type': 'application/json' } : {})
  }
}

async function request(url, options = {}) {
  const response = await fetch(`${base}${url}`, {
    ...options,
    headers: { ...headers(options.json === true), ...(options.headers ?? {}) },
    signal: AbortSignal.timeout(timeoutMs)
  })
  const text = await response.text()
  let body = null
  try { body = text === '' ? null : JSON.parse(text) } catch { body = text }
  return { status: response.status, body, text, contentType: response.headers.get('content-type') ?? '' }
}

function parseSseBlock(block) {
  let data = ''
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith(':') || line.startsWith('event:') || line.startsWith('id:') || line.startsWith('retry:')) continue
    if (line.startsWith('data:')) data += `${line.slice(5).trimStart()}\n`
  }
  if (data === '') return null
  try { return JSON.parse(data) } catch { return null }
}

const createdTasks = new Map()
const createdJobs = new Map()
const trackingGaps = []
const jobStartTools = new Set(['start_export', 'start_precheck', 'start_import', 'start_publish'])

function positiveId(value) {
  if (value === null || value === undefined || !/^\d+$/.test(String(value))) return null
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
}

function trackCreatedResources(frame, runId) {
  if (frame.outcome !== 'ok') return
  const result = String(frame.result ?? '')
  if (frame.name === 'create_task') {
    if (/本次复用任务\s*#\d+/.test(result)) return
    const structuredId = positiveId(frame.createdTaskId ?? frame.data?.createdTaskId)
    const textMatch = structuredId === null ? /已创建(?:导出|导入)任务\s*#(\d+)/.exec(result) : null
    const taskId = structuredId ?? (textMatch === null ? null : Number(textMatch[1]))
    if (taskId !== null) {
      if (!createdTasks.has(taskId)) {
        createdTasks.set(taskId, {
          taskId,
          runId,
          toolCallId: frame.toolCallId,
          idSource: structuredId === null ? 'text-fallback' : 'structured-createdTaskId'
        })
      }
    } else {
      trackingGaps.push({ kind: 'task', runId, toolCallId: frame.toolCallId, result: frame.result ?? null, reason: 'successful create_task result did not contain a new task id' })
    }
  } else if (jobStartTools.has(frame.name)) {
    const structuredId = positiveId(frame.createdJobId ?? frame.data?.createdJobId)
    const textMatch = structuredId === null ? /作业\s*#(\d+)/.exec(result) : null
    const jobId = structuredId ?? (textMatch === null ? null : Number(textMatch[1]))
    if (jobId !== null) {
      if (!createdJobs.has(jobId)) {
        createdJobs.set(jobId, {
          jobId,
          runId,
          toolCallId: frame.toolCallId,
          toolName: frame.name,
          idSource: structuredId === null ? 'text-fallback' : 'structured-createdJobId'
        })
      }
    } else {
      trackingGaps.push({ kind: 'job', runId, toolCallId: frame.toolCallId, toolName: frame.name, result: frame.result ?? null, reason: 'successful job-start result did not contain a job id' })
    }
  }
}

async function confirm(runId, toolCallId) {
  if (!allowWrites) throw new Error('confirm write attempted without EVAL_ALLOW_WRITES=true')
  return request('/api/ai/confirm', {
    method: 'POST',
    json: true,
    body: JSON.stringify({ runId, toolCallId, approved: true, reason: 'progressive-disclosure-eval approval' })
  })
}

function errorInfo(stage, category, details = {}) {
  return { stage, category, ...details }
}

const transcriptErrorCodes = new Set(['AI_TRANSCRIPT_INCOMPLETE', 'AI_TRANSCRIPT_WRITE_FAILED'])

function isTranscriptDegraded(frame) {
  const code = String(frame.code ?? '').toUpperCase()
  return frame.transcriptDegraded === true || frame.historyComplete === false || transcriptErrorCodes.has(code)
}

function terminalCategory(frame) {
  if (isTranscriptDegraded(frame)) return 'transcript'
  const code = String(frame.code ?? '').toUpperCase()
  return /MODEL|UPSTREAM|PROVIDER|RATE_LIMIT|EMPTY_REPLY/.test(code) ? 'model' : 'infra'
}

function terminalError(frame) {
  return errorInfo('terminal', terminalCategory(frame), {
    code: frame.code ?? null,
    message: frame.message ?? null,
    transcriptDegraded: frame.transcriptDegraded === true,
    historyComplete: frame.historyComplete ?? null,
    runStatus: frame.runStatus ?? null,
    frame
  })
}

function notRunCase(definition, round, error) {
  const result = {
    id: definition.id,
    kind: definition.kind,
    round,
    sessionId: null,
    runId: null,
    latencyMs: null,
    answer: null,
    reasoning: '',
    orderedSegments: [],
    notRun: true,
    error,
    terminal: null,
    toolCalls: [],
    usage: null,
    eof: null,
    disclosure: { status: 'unknown', reason: 'case was not run because its fixture or HITL precondition failed' },
    persistenceEvidence: unavailablePersistenceEvidence('case was not run; history/run evidence was not collected')
  }
  return { ...result, mechanical: mechanical(result, definition), manual: manualTemplate() }
}

async function validateQueryCondTask() {
  if (queryCondTaskId === '') {
    return {
      valid: false,
      error: errorInfo('fixture_validation', 'fixture', {
        code: 'QUERY_COND_TASK_ID_REQUIRED',
        message: 'QUERY_COND_TASK_ID is required for QUERY_COND cases; no task id is hardcoded',
        notRun: true
      })
    }
  }
  if (positiveId(queryCondTaskId) === null) {
    return {
      valid: false,
      error: errorInfo('fixture_validation', 'fixture', {
        code: 'QUERY_COND_TASK_ID_INVALID',
        message: `QUERY_COND_TASK_ID must be a positive integer, received ${queryCondTaskId}`,
        notRun: true
      })
    }
  }
  let response
  try {
    response = await request(`/api/tasks/${queryCondTaskId}`)
  } catch (error) {
    return {
      valid: false,
      error: errorInfo('fixture_validation', 'fixture', {
        code: 'QUERY_COND_TASK_LOOKUP_FAILED',
        message: String(error?.message ?? error),
        notRun: true
      })
    }
  }
  const task = response.body?.data
  const items = Array.isArray(task?.items) ? task.items : []
  const problems = []
  if (response.status !== 200 || response.body?.success !== true || !isRecord(task)) {
    problems.push('GET /api/tasks/{id} did not return success=true with a task object')
  } else {
    if (task.type !== 'EXPORT') problems.push(`expected EXPORT, received ${task.type ?? 'missing type'}`)
    if (task.currentStep !== 'QUERY_COND') problems.push(`expected currentStep=QUERY_COND, received ${task.currentStep ?? 'missing step'}`)
    if (!items.some((item) => item?.defCode === 'CURRENCY')) problems.push('task has no CURRENCY item')
  }
  if (problems.length > 0) {
    return {
      valid: false,
      error: errorInfo('fixture_validation', 'fixture', {
        code: 'QUERY_COND_TASK_PRECONDITION_FAILED',
        message: problems.join('; '),
        httpStatus: response.status,
        body: response.body ?? response.text,
        notRun: true
      })
    }
  }
  return {
    valid: true,
    taskId: Number(queryCondTaskId),
    evidence: {
      id: Number(queryCondTaskId),
      type: task.type,
      currentStep: task.currentStep,
      defCodes: items.map((item) => item?.defCode).filter(Boolean)
    }
  }
}

async function executeCase(definition, round) {
  const started = Date.now()
  const sessionId = `eval-${definition.id}-${round}-${randomUUID()}`
  const body = {
    sessionId,
    message: definition.message.repeat(definition.repeat ?? 1),
    context: definition.context ?? null,
    clientRequestId: `eval-${definition.id}-${round}-${randomUUID()}`
  }
  const response = await fetch(`${base}/api/ai/chat`, {
    method: 'POST',
    headers: headers(true),
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(timeoutMs)
  })
  const contentType = response.headers.get('content-type') ?? ''
  if (!contentType.includes('text/event-stream')) {
    const text = await response.text()
    let payload = text
    try { payload = JSON.parse(text) } catch { /* retain raw error */ }
    return {
      id: definition.id,
      kind: definition.kind,
      round,
      sessionId,
      runId: null,
      latencyMs: Date.now() - started,
      answer: null,
      reasoning: '',
      orderedSegments: [],
      error: errorInfo('chat_http', 'infra', { httpStatus: response.status, body: payload }),
      terminal: null,
      toolCalls: [],
      usage: null,
      eof: null,
      disclosure: { status: 'unknown', reason: 'chat did not return SSE' },
      persistenceEvidence: unavailablePersistenceEvidence('chat did not return SSE; history/run evidence was not collected')
    }
  }

  const toolCalls = new Map()
  const approvedToolCalls = new Set()
  const orderedSegments = []
  const orderedToolCallIds = new Set()
  let answer = ''
  let reasoning = ''
  let runId = null
  let usage = null
  let terminal = null
  let streamError = null
  let stopRequested = false
  let reachedEof = false
  let eofTail = null
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffered = ''
  const stopAfterApproval = definition.stopAfter === 'approval'

  function recordOrderedSegment(frame) {
    if (frame.type === 'delta') {
      const text = String(frame.text ?? '')
      if (text === '') return
      const kind = frame.kind === 'reasoning' ? 'reasoning' : 'text'
      const messageId = frame.messageId ?? null
      const previous = orderedSegments.at(-1)
      if (previous?.kind === kind && previous.messageId === messageId) {
        previous.text += text
      } else {
        orderedSegments.push({ kind, text, toolCallId: null, messageId, seq: frame.seq ?? null })
      }
      return
    }
    const calls = frame.type === 'suspended' && Array.isArray(frame.toolCalls) ? frame.toolCalls : [frame]
    const toolFrame = frame.type === 'suspended' || ['tool_start', 'tool_result', 'confirm_request', 'confirm_decision',
      'frontend_tool_request', 'frontend_tool_result'].includes(frame.type)
    if (!toolFrame) return
    for (const call of calls) {
      const toolCallId = typeof call?.toolCallId === 'string' ? call.toolCallId : ''
      if (toolCallId === '' || orderedToolCallIds.has(toolCallId)) continue
      orderedToolCallIds.add(toolCallId)
      orderedSegments.push({ kind: 'tool', text: null, toolCallId, seq: frame.seq ?? call.seq ?? null })
    }
  }

  async function processFrame(frame) {
    const atMs = Date.now() - started
    recordOrderedSegment(frame)
    if (frame.type === 'start') {
      runId = frame.runId ?? runId
    } else if (frame.type === 'delta') {
      const text = String(frame.text ?? '')
      if (frame.kind === 'reasoning') reasoning += text
      else answer += text
    } else if (frame.type === 'tool_start') {
      toolCalls.set(frame.toolCallId, {
        toolCallId: frame.toolCallId,
        name: frame.name,
        args: frame.args ?? null,
        startedAtMs: atMs,
        result: null,
        outcome: null,
        code: null,
        status: null,
        kind: frame.kind ?? null,
        executed: frame.executed ?? null,
        decision: null,
        userTextCode: null,
        resultView: null,
        preflight: null,
        truncated: null,
        noticeCode: null,
        strategy: null,
        originalChars: null,
        returnedChars: null,
        resultAtMs: null
      })
    } else if (frame.type === 'tool_result' || frame.type === 'frontend_tool_result') {
      const previous = toolCalls.get(frame.toolCallId) ?? {
        toolCallId: frame.toolCallId,
        name: frame.name,
        args: null,
        startedAtMs: null,
        result: null,
        outcome: null,
        code: null,
        status: null,
        kind: null,
        executed: null,
        decision: null,
        userTextCode: null,
        resultView: null,
        preflight: null,
        truncated: null,
        noticeCode: null,
        strategy: null,
        originalChars: null,
        returnedChars: null,
        resultAtMs: null
      }
      previous.name = frame.name ?? previous.name
      previous.result = frame.result ?? null
      previous.outcome = frame.outcome ?? null
      previous.code = frame.code ?? null
      previous.status = frame.status ?? null
      previous.kind = frame.kind ?? (frame.type === 'frontend_tool_result' ? 'FRONTEND' : previous.kind)
      previous.executed = frame.executed ?? previous.executed
      previous.userTextCode = frame.userTextCode ?? null
      previous.resultView = frame.resultView ?? null
      previous.preflight = frame.preflight ?? null
      previous.truncated = frame.truncated ?? null
      previous.noticeCode = frame.noticeCode ?? null
      previous.strategy = frame.strategy ?? null
      previous.originalChars = frame.originalChars ?? null
      previous.returnedChars = frame.returnedChars ?? null
      previous.resultAtMs = atMs
      toolCalls.set(frame.toolCallId, previous)
      trackCreatedResources(frame, runId)
      if (approvedToolCalls.has(frame.toolCallId)) {
        if (frame.outcome !== 'ok') {
          streamError = errorInfo('confirm', 'hitl', {
            code: 'confirm_rejected',
            message: 'the approved action did not return tool_result.outcome=ok',
            frame,
            notRun: true
          })
          stopRequested = true
        } else if (stopAfterApproval) {
          stopRequested = true
        }
      }
    } else if (frame.type === 'confirm_decision') {
      const previous = toolCalls.get(frame.toolCallId)
      if (previous !== undefined) {
        previous.decision = frame.decision ?? null
        previous.status = frame.status ?? previous.status
      }
    } else if (frame.type === 'confirm_request') {
      if (definition.autoApprove === true) {
        const confirmation = await confirm(runId, frame.toolCallId)
        if (confirmation.status === 200 && confirmation.body?.accepted === true) {
          approvedToolCalls.add(frame.toolCallId)
        } else {
          streamError = errorInfo('confirm', 'hitl', {
            code: 'confirm_rejected',
            httpStatus: confirmation.status,
            body: confirmation.body ?? confirmation.text,
            notRun: true
          })
          stopRequested = true
        }
      } else {
        streamError = errorInfo('confirm', 'hitl', { code: 'approval_required', notRun: true })
        stopRequested = true
      }
    } else if (frame.type === 'done') {
      terminal = frame
      usage = frame.usage ?? null
      if (isTranscriptDegraded(frame)) {
        streamError = terminalError(frame)
        stopRequested = true
      }
    } else if (frame.type === 'error') {
      terminal = frame
      streamError = terminalError(frame)
      stopRequested = true
    }
  }

  async function processBlock(block) {
    const frame = parseSseBlock(block)
    if (frame !== null) await processFrame(frame)
    return frame
  }

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) {
        reachedEof = true
        break
      }
      buffered += decoder.decode(value, { stream: true })
      let separator
      while ((separator = /\r?\n\r?\n/.exec(buffered)) !== null) {
        const block = buffered.slice(0, separator.index)
        buffered = buffered.slice(separator.index + separator[0].length)
        await processBlock(block)
        if (stopRequested) break
      }
      if (stopRequested) {
        await reader.cancel()
        break
      }
    }
  } finally {
    buffered += decoder.decode()
    if (reachedEof) {
      const tail = buffered
      const frame = parseSseBlock(tail)
      eofTail = { present: tail.trim().length > 0, chars: tail.length, parsed: frame !== null, frameType: frame?.type ?? null }
      if (frame !== null) await processFrame(frame)
    }
  }
  if (streamError === null) {
    for (const toolCallId of approvedToolCalls) {
      const call = toolCalls.get(toolCallId)
      if (call?.outcome !== 'ok') {
        streamError = errorInfo('confirm', 'hitl', {
          code: 'confirm_rejected',
          message: 'the approved action ended without tool_result.outcome=ok; execution is unproven',
          toolCallId,
          observedOutcome: call?.outcome ?? null,
          notRun: true
        })
        break
      }
    }
  }
  const result = {
    id: definition.id,
    kind: definition.kind,
    round,
    sessionId,
    runId,
    latencyMs: Date.now() - started,
    answer,
    reasoning,
    orderedSegments,
    notRun: streamError?.notRun === true,
    error: streamError,
    terminal,
    toolCalls: [...toolCalls.values()],
    usage,
    eof: { reached: reachedEof, tail: eofTail },
    disclosure: { status: 'unknown', reason: 'system prompt and disclosed tool set are not exposed by public SSE' }
  }
  return { ...result, persistenceEvidence: await collectPersistenceEvidence(result) }
}

function unavailablePersistenceEvidence(reason) {
  return {
    history: { observed: false, verdict: 'NOT_RUN', reason, pages: [], messages: [] },
    run: { observed: false, verdict: 'NOT_RUN', reason, data: null },
    orderedSegments: { verdict: 'NOT_RUN', reason, public: [], persisted: [], mismatchCount: null, mismatches: [] },
    tools: { verdict: 'NOT_RUN', applicable: false, reason, public: [], persisted: [], mismatchCount: null, mismatches: [] },
    reasoning: {
      verdict: 'NOT_RUN',
      toolHopVerdict: 'NOT_RUN',
      visibilityVerdict: 'NOT_RUN',
      displayEnabled: reasoningDisplayEnabled,
      reason,
      publicChars: 0,
      persistedChars: null,
      publicSegments: [],
      persistedSegments: []
    }
  }
}

async function readEvidence(url) {
  let response
  try {
    response = await request(url)
  } catch (error) {
    return { observed: false, verdict: 'NOT_RUN', url, error: String(error?.message ?? error) }
  }
  const evidence = { url, httpStatus: response.status, contentType: response.contentType }
  if (response.status !== 200 || response.body?.success !== true || !isRecord(response.body?.data)) {
    return {
      observed: false,
      verdict: 'NOT_RUN',
      ...evidence,
      body: response.body ?? response.text,
      reason: 'expected HTTP 200 with ApiResponse success=true and object data'
    }
  }
  return { observed: true, verdict: 'OBSERVED', ...evidence, data: response.body.data }
}

async function readRunEvidence(runId, sessionId) {
  if (!runId) return { observed: false, verdict: 'NOT_RUN', reason: 'no runId was observed on public SSE', data: null }
  const evidence = await readEvidence(`/api/ai/runs/${encodeURIComponent(runId)}`)
  if (!evidence.observed) return evidence
  const snapshot = evidence.data.snapshot
  const matches = evidence.data.exists === true && isRecord(snapshot)
    && snapshot.runId === runId && snapshot.sessionId === sessionId
  return {
    ...evidence,
    observed: matches,
    verdict: matches ? 'OBSERVED' : 'NOT_RUN',
    reason: matches ? undefined : 'run evidence must exist and match the current runId/sessionId'
  }
}

async function readHistoryEvidence(sessionId) {
  const pages = []
  const messages = []
  let before = null
  for (let pageNumber = 0; pageNumber < 20; pageNumber += 1) {
    const query = new URLSearchParams({ latest: '100' })
    if (before !== null) query.set('before', before)
    const page = await readEvidence(`/api/ai/history/${encodeURIComponent(sessionId)}?${query}`)
    pages.push(page)
    if (!page.observed) {
      return { observed: false, verdict: 'NOT_RUN', reason: 'history page was not readable', pages, messages }
    }
    if (page.data.sessionId !== sessionId || !Array.isArray(page.data.messages)) {
      return { observed: false, verdict: 'NOT_RUN', reason: 'history page has invalid shape or a different sessionId', pages, messages }
    }
    messages.unshift(...page.data.messages)
    if (page.data.hasMore !== true) return { observed: true, verdict: 'OBSERVED', pages, messages }
    if (!['string', 'number'].includes(typeof page.data.nextCursor) || String(page.data.nextCursor) === '') {
      return { observed: false, verdict: 'NOT_RUN', reason: 'history hasMore=true without a usable nextCursor', pages, messages }
    }
    before = String(page.data.nextCursor)
  }
  return { observed: false, verdict: 'NOT_RUN', reason: 'history pagination exceeded 20 pages', pages, messages }
}

function segmentView(segment) {
  return {
    kind: segment?.kind ?? null,
    text: segment?.text ?? null,
    toolCallId: segment?.toolCallId ?? null,
    messageId: segment?.messageId ?? null,
    seq: segment?.seq ?? null
  }
}

function segmentIdentity(segment) {
  return { kind: segment.kind, text: segment.text, toolCallId: segment.toolCallId }
}

function orderedSegmentEvidence(publicSegments, persistedSegments, evidenceReady) {
  const evidence = {
    verdict: 'NOT_RUN',
    reason: 'matching terminal history/run evidence is required',
    public: publicSegments,
    persisted: persistedSegments,
    mismatchCount: null,
    mismatches: []
  }
  if (!evidenceReady) return evidence
  if (publicSegments.length === 0 && persistedSegments.length === 0) {
    return { ...evidence, reason: 'no public or persisted ordered segment sample was observed', mismatchCount: 0 }
  }
  const mismatches = []
  let mismatchCount = 0
  const length = Math.max(publicSegments.length, persistedSegments.length)
  for (let index = 0; index < length; index += 1) {
    const publicSegment = publicSegments[index] === undefined ? null : segmentIdentity(publicSegments[index])
    const persistedSegment = persistedSegments[index] === undefined ? null : segmentIdentity(persistedSegments[index])
    if (JSON.stringify(publicSegment) === JSON.stringify(persistedSegment)) continue
    mismatchCount += 1
    if (mismatches.length < 20) mismatches.push({ index, public: publicSegment, persisted: persistedSegment })
  }
  return {
    ...evidence,
    verdict: mismatchCount === 0 ? 'PASS' : 'FAIL',
    reason: mismatchCount === 0 ? 'public and same-run persisted ordered segments match' : 'ordered segment content or sequence differs',
    mismatchCount,
    mismatches
  }
}

function publicToolResponse(call) {
  if (call.resultAtMs === null) return null
  return {
    name: call.name ?? null,
    result: call.result ?? null,
    userTextCode: call.userTextCode ?? null,
    resultView: call.resultView ?? null,
    truncated: call.truncated ?? null,
    noticeCode: call.noticeCode ?? null,
    strategy: call.strategy ?? null,
    originalChars: call.originalChars ?? null,
    returnedChars: call.returnedChars ?? null
  }
}

function persistedToolResponse(response) {
  return {
    name: response?.name ?? null,
    result: response?.responseData ?? null,
    userTextCode: response?.userTextCode ?? null,
    resultView: response?.resultView ?? null,
    truncated: response?.truncated ?? null,
    noticeCode: response?.noticeCode ?? null,
    strategy: response?.strategy ?? null,
    originalChars: response?.originalChars ?? null,
    returnedChars: response?.returnedChars ?? null
  }
}

function expectedToolStatus(call, run) {
  if (!['PENDING', 'APPROVED'].includes(call.status)) return call.status ?? null
  if (call.outcome === 'blocked') return 'BLOCKED'
  const ledgerHit = Array.isArray(run.data?.ledger)
    && run.data.ledger.some((entry) => isRecord(entry) && entry.toolCallId === call.toolCallId)
  return call.executed === true || ledgerHit ? 'EXECUTED' : call.status
}

function publicToolState(call, run) {
  return {
    status: expectedToolStatus(call, run),
    outcome: call.outcome ?? null,
    code: call.code ?? null,
    kind: call.kind ?? null,
    decision: call.decision ?? null,
    submittedValues: call.kind === 'FRONTEND' && call.resultAtMs !== null ? call.result ?? null : null,
    preflight: call.preflight ?? null
  }
}

function persistedToolState(state) {
  return {
    status: state?.status ?? null,
    outcome: state?.outcome ?? null,
    code: state?.code ?? null,
    kind: state?.kind ?? null,
    decision: state?.decision ?? null,
    submittedValues: state?.submittedValues ?? null,
    preflight: state?.preflight ?? null
  }
}

const frameTruncationCodes = new Set(['frame_truncated', 'frame_limit_unreached'])
const toolTruncationFields = ['truncated', 'noticeCode', 'strategy', 'originalChars', 'returnedChars']

const toolTruncationStrategies = new Set(['head', 'tail', 'head_tail'])

function hasValidTruncationCounts(value) {
  return Number.isSafeInteger(value.originalChars) && value.originalChars >= 0
    && Number.isSafeInteger(value.returnedChars) && value.returnedChars >= 0
    && value.returnedChars <= value.originalChars
}

function validateModelViewTruncation(value, side, toolCallId, addMismatch) {
  if (value.truncated !== true) addMismatch(toolCallId, `response.${side}Truncated`, value.truncated, true)
  if (!toolTruncationStrategies.has(value.strategy)) {
    addMismatch(toolCallId, `response.${side}Strategy`, value.strategy, 'head, tail or head_tail')
  }
  if (!hasValidTruncationCounts(value)) {
    addMismatch(toolCallId, `response.${side}TruncationCounts`,
      { originalChars: value.originalChars, returnedChars: value.returnedChars },
      'integers with 0 <= returnedChars <= originalChars required')
  }
}

function compareToolResponse(publicValue, persistedValue, toolCallId, addMismatch) {
  for (const field of ['name', 'userTextCode', 'resultView']) {
    if (!isDeepStrictEqual(publicValue[field], persistedValue[field])) {
      addMismatch(toolCallId, `response.${field}`, publicValue[field], persistedValue[field])
    }
  }
  if (frameTruncationCodes.has(publicValue.noticeCode)) {
    if (publicValue.truncated !== true) {
      addMismatch(toolCallId, 'response.truncated', publicValue.truncated, true)
    }
    const countsValid = hasValidTruncationCounts(publicValue)
    if (!countsValid) {
      addMismatch(toolCallId, 'response.truncationCounts',
        { originalChars: publicValue.originalChars, returnedChars: publicValue.returnedChars },
        'integers with 0 <= returnedChars <= originalChars required by FrameSizeLimiter')
    }
    if (typeof publicValue.result === 'string') {
      if (publicValue.strategy !== 'head') addMismatch(toolCallId, 'response.strategy', publicValue.strategy, 'head')
      if (countsValid && publicValue.result.length !== publicValue.returnedChars) {
        addMismatch(toolCallId, 'response.returnedChars', publicValue.returnedChars, publicValue.result.length)
      }
      if (typeof persistedValue.result !== 'string') {
        addMismatch(toolCallId, 'response.result', 'history original string required', persistedValue.result)
      } else if (countsValid && persistedValue.result.length !== publicValue.originalChars) {
        addMismatch(toolCallId, 'response.originalChars', publicValue.originalChars, persistedValue.result.length)
      }
    } else {
      if (publicValue.strategy !== null) addMismatch(toolCallId, 'response.strategy', publicValue.strategy, null)
      if (countsValid && (publicValue.originalChars !== 0 || publicValue.returnedChars !== 0)) {
        addMismatch(toolCallId, 'response.truncationCounts',
          { originalChars: publicValue.originalChars, returnedChars: publicValue.returnedChars },
          'non-string result requires zero counts')
      }
      if (!isDeepStrictEqual(publicValue.result, persistedValue.result)) {
        addMismatch(toolCallId, 'response.result', publicValue.result, persistedValue.result)
      }
    }
    if (persistedValue.noticeCode !== null && persistedValue.noticeCode !== 'model_view_truncated') {
      addMismatch(toolCallId, 'response.persistedNoticeCode', publicValue.noticeCode, persistedValue.noticeCode)
    }
    if (persistedValue.noticeCode === 'model_view_truncated') {
      validateModelViewTruncation(persistedValue, 'persisted', toolCallId, addMismatch)
    }
    if (persistedValue.truncated === true && persistedValue.noticeCode === null) {
      addMismatch(toolCallId, 'response.persistedNoticeCode', publicValue.noticeCode, null)
    }
    return
  }
  if (publicValue.noticeCode !== null && publicValue.noticeCode !== 'model_view_truncated') {
    addMismatch(toolCallId, 'response.noticeCode', publicValue.noticeCode, 'supported truncation noticeCode')
  }
  if (publicValue.noticeCode === 'model_view_truncated') {
    validateModelViewTruncation(publicValue, 'public', toolCallId, addMismatch)
  }
  if (persistedValue.noticeCode === 'model_view_truncated') {
    validateModelViewTruncation(persistedValue, 'persisted', toolCallId, addMismatch)
  }
  if (publicValue.truncated === true && publicValue.noticeCode === null) {
    addMismatch(toolCallId, 'response.noticeCode', null, 'a declared truncation noticeCode')
  }
  if (publicValue.truncated !== true && publicValue.noticeCode !== null
    && publicValue.noticeCode !== 'model_view_truncated') {
    addMismatch(toolCallId, 'response.noticeCode', publicValue.noticeCode, 'no notice without truncation')
  }
  if (!isDeepStrictEqual(publicValue.result, persistedValue.result)) {
    addMismatch(toolCallId, 'response.result', publicValue.result, persistedValue.result)
  }
  for (const field of toolTruncationFields) {
    if (!isDeepStrictEqual(publicValue[field], persistedValue[field])) {
      addMismatch(toolCallId, `response.${field}`, publicValue[field], persistedValue[field])
    }
  }
}

const javaWhitespaceOnly = /^[\u0009-\u000D\u001C-\u0020\u1680\u2000-\u2006\u2008-\u200A\u2028\u2029\u205F\u3000]*$/

function submittedValuesMatch(publicValue, persistedValue) {
  const isBlank = (value) => value === null || (typeof value === 'string' && javaWhitespaceOnly.test(value))
  if (isBlank(publicValue) || isBlank(persistedValue)) return isBlank(publicValue) && isBlank(persistedValue)
  return isDeepStrictEqual(publicValue, persistedValue)
}

function compareToolState(publicValue, persistedValue, publicResponse, persistedResponse, toolCallId, addMismatch) {
  for (const field of Object.keys(publicValue)) {
    if (field !== 'submittedValues') {
      if (!isDeepStrictEqual(publicValue[field], persistedValue[field])) {
        addMismatch(toolCallId, `state.${field}`, publicValue[field], persistedValue[field])
      }
      continue
    }
    if (publicValue.submittedValues === null && persistedValue.submittedValues === null) continue
    if (frameTruncationCodes.has(publicResponse?.noticeCode)) {
      if (!submittedValuesMatch(persistedResponse?.result ?? null, persistedValue.submittedValues)) {
        addMismatch(toolCallId, 'state.submittedValues',
          { historyToolResult: persistedResponse?.result ?? null, frameTruncatedPublicValue: publicValue.submittedValues },
          persistedValue.submittedValues)
      }
    } else if (!submittedValuesMatch(publicValue.submittedValues, persistedValue.submittedValues)) {
      addMismatch(toolCallId, 'state.submittedValues', publicValue.submittedValues, persistedValue.submittedValues)
    }
  }
}

function toolPersistenceEvidence(caseResult, assistantMessages, historyMessages, run, evidenceReady) {
  const publicById = new Map(caseResult.toolCalls.map((call) => [call.toolCallId, {
    toolCallId: call.toolCallId,
    observedStatus: call.status ?? null,
    response: publicToolResponse(call),
    state: publicToolState(call, run)
  }]))
  const persistedStateById = new Map()
  for (const message of assistantMessages) {
    if (!isRecord(message.toolCallStates)) continue
    for (const [toolCallId, state] of Object.entries(message.toolCallStates)) {
      persistedStateById.set(toolCallId, persistedToolState(state))
    }
  }
  const persistedResponseById = new Map()
  const invalid = []
  for (const message of historyMessages) {
    if (!isRecord(message) || message.type !== 'TOOL' || message.runId !== caseResult.runId) continue
    if (!Array.isArray(message.toolResponses) || message.toolResponses.length === 0) {
      invalid.push({ toolCallId: null, field: 'toolResponses', reason: 'TOOL entry has no toolResponses array' })
      continue
    }
    for (const response of message.toolResponses) {
      if (!isRecord(response) || typeof response.id !== 'string' || response.id === '') {
        invalid.push({ toolCallId: null, field: 'toolResponses.id', reason: 'TOOL response has no toolCallId' })
        continue
      }
      if (persistedResponseById.has(response.id)) {
        invalid.push({ toolCallId: response.id, field: 'toolResponses', reason: 'duplicate persisted TOOL response' })
      }
      persistedResponseById.set(response.id, persistedToolResponse(response))
    }
  }
  const persistedIds = [...new Set([...persistedStateById.keys(), ...persistedResponseById.keys()])]
  const persistedById = new Map(persistedIds.map((toolCallId) => [toolCallId, {
    toolCallId,
    response: persistedResponseById.get(toolCallId) ?? null,
    state: persistedStateById.get(toolCallId) ?? null
  }]))
  const evidence = {
    verdict: 'NOT_RUN',
    applicable: publicById.size > 0 || persistedById.size > 0 || invalid.length > 0,
    reason: 'matching terminal history/run evidence is required',
    public: [...publicById.values()],
    persisted: [...persistedById.values()],
    mismatchCount: null,
    mismatches: []
  }
  if (!evidenceReady) return evidence
  if (publicById.size === 0 && persistedById.size === 0 && invalid.length === 0) {
    return { ...evidence, verdict: 'NOT_RUN', reason: 'no public or persisted tool calls; tool comparison is not applicable', mismatchCount: 0 }
  }
  const mismatches = []
  let mismatchCount = invalid.length
  mismatches.push(...invalid.slice(0, 20))
  const addMismatch = (toolCallId, field, publicValue, persistedValue) => {
    mismatchCount += 1
    if (mismatches.length < 20) mismatches.push({ toolCallId, field, public: publicValue, persisted: persistedValue })
  }
  const ids = [...new Set([...publicById.keys(), ...persistedById.keys()])]
  for (const toolCallId of ids) {
    const publicTool = publicById.get(toolCallId)
    const persistedTool = persistedById.get(toolCallId)
    const publicResponse = publicTool?.response ?? null
    const persistedResponse = persistedTool?.response ?? null
    if (publicResponse === null || persistedResponse === null) {
      if (publicResponse !== persistedResponse) addMismatch(toolCallId, 'response', publicResponse, persistedResponse)
    } else {
      compareToolResponse(publicResponse, persistedResponse, toolCallId, addMismatch)
    }
    const publicState = publicTool?.state ?? null
    const persistedState = persistedTool?.state ?? null
    if (publicState === null || persistedState === null) {
      if (publicState !== persistedState) addMismatch(toolCallId, 'state', publicState, persistedState)
    } else {
      compareToolState(publicState, persistedState, publicResponse, persistedResponse, toolCallId, addMismatch)
    }
  }
  return {
    ...evidence,
    verdict: mismatchCount === 0 ? 'PASS' : 'FAIL',
    reason: mismatchCount === 0
      ? 'public tool responses, submitted values and toolCallStates match same-run history'
      : 'tool responses, submitted values or toolCallStates differ from same-run history',
    mismatchCount,
    mismatches
  }
}

function reasoningEvidence(caseResult, assistantMessages, publicSegments, persistedSegments, evidenceReady, orderedVerdict) {
  const publicReasoningSegments = publicSegments.filter((segment) => segment.kind === 'reasoning')
  const persistedReasoningSegments = persistedSegments.filter((segment) => segment.kind === 'reasoning')
  const publicReasoning = caseResult.reasoning
  const legacyPersistedReasoning = assistantMessages
    .map((message) => typeof message.reasoningContent === 'string' ? message.reasoningContent : '')
    .join('')
  const persistedReasoning = persistedReasoningSegments.length > 0
    ? persistedReasoningSegments.map((segment) => String(segment.text ?? '')).join('')
    : legacyPersistedReasoning
  let visibilityVerdict = 'NOT_RUN'
  let visibilityReason = 'matching terminal history/run evidence is required'
  if (evidenceReady && reasoningDisplayEnabled === false) {
    visibilityVerdict = publicReasoning === '' && persistedReasoning === '' ? 'PASS' : 'FAIL'
    visibilityReason = 'display-disabled requires reasoning to be absent from public SSE and history projections'
  } else if (evidenceReady && (publicReasoning !== '' || persistedReasoning !== '')) {
    visibilityVerdict = publicReasoning === persistedReasoning ? 'PASS' : 'FAIL'
    visibilityReason = reasoningDisplayEnabled === true
      ? 'display-enabled public and persisted reasoning visibility was compared'
      : 'visible reasoning was compared; REASONING_DISPLAY_ENABLED was not provided'
  } else if (evidenceReady) {
    visibilityReason = 'no reasoning sample proves display behavior'
  }

  let verdict = 'NOT_RUN'
  let reason = 'matching terminal history/run evidence is required; SSE alone cannot prove persistence'
  if (evidenceReady && publicReasoning === '') {
    reason = reasoningDisplayEnabled === false
      ? 'display-disabled hides reasoning; hidden persistence cannot be inferred from public projections'
      : 'no public reasoning sample was observed'
  } else if (evidenceReady && persistedReasoning === '') {
    verdict = 'FAIL'
    reason = 'public reasoning is absent from same-run persisted history'
  } else if (evidenceReady && publicReasoning !== persistedReasoning) {
    verdict = 'FAIL'
    reason = 'public and same-run persisted reasoning differ'
  } else if (evidenceReady) {
    verdict = 'PASS'
    reason = 'public and same-run persisted reasoning match'
  }

  const firstToolIndex = publicSegments.findIndex((segment) => segment.kind === 'tool')
  const reasoningBeforeTool = firstToolIndex >= 0
    && publicSegments.slice(0, firstToolIndex).some((segment) => segment.kind === 'reasoning')
  let toolHopVerdict = 'NOT_RUN'
  let toolHopReason = 'a reasoning segment before a real tool hop is required'
  if (reasoningBeforeTool && !evidenceReady) {
    toolHopReason = 'matching terminal history/run evidence is required'
  } else if (reasoningBeforeTool && (verdict === 'FAIL' || orderedVerdict === 'FAIL')) {
    toolHopVerdict = 'FAIL'
    toolHopReason = 'reasoning or ordered segments were not preserved across the tool hop'
  } else if (reasoningBeforeTool && verdict === 'PASS' && orderedVerdict === 'PASS' && persistedReasoningSegments.length > 0) {
    toolHopVerdict = 'PASS'
    toolHopReason = 'same-run persisted ordered segments preserve reasoning before the tool hop'
  } else if (reasoningBeforeTool) {
    toolHopReason = 'ordered persisted reasoning evidence is unavailable'
  }
  return {
    verdict,
    reason,
    toolHopVerdict,
    toolHopReason,
    visibilityVerdict,
    visibilityReason,
    displayEnabled: reasoningDisplayEnabled,
    publicChars: publicReasoning.length,
    persistedChars: evidenceReady ? persistedReasoning.length : null,
    publicSegments: publicReasoningSegments,
    persistedSegments: persistedReasoningSegments,
    legacyPersistedChars: persistedReasoningSegments.length === 0 && evidenceReady ? legacyPersistedReasoning.length : null
  }
}

async function collectPersistenceEvidence(caseResult) {
  if (!caseResult.runId) return unavailablePersistenceEvidence('no runId was observed; history/run persistence remains unverified')
  const [history, run] = await Promise.all([
    readHistoryEvidence(caseResult.sessionId),
    readRunEvidence(caseResult.runId, caseResult.sessionId)
  ])
  const evidenceReady = history.observed && run.observed && caseResult.terminal !== null
  const assistantMessages = history.messages.filter((message) => isRecord(message)
    && message.type === 'ASSISTANT' && message.runId === caseResult.runId)
  const persistedSegments = assistantMessages.flatMap((message) => Array.isArray(message.segments)
    ? message.segments.map(segmentView)
    : [])
  const publicSegments = caseResult.orderedSegments.map(segmentView)
  const orderedSegments = orderedSegmentEvidence(publicSegments, persistedSegments, evidenceReady)
  if (caseResult.terminal === null) orderedSegments.reason = 'no terminal done/error frame was observed'
  const tools = toolPersistenceEvidence(caseResult, assistantMessages, history.messages, run, evidenceReady)
  if (tools.verdict === 'FAIL') {
    orderedSegments.verdict = 'FAIL'
    orderedSegments.reason = 'tool responses, submitted values or toolCallStates differ from same-run history'
  } else if (tools.verdict === 'NOT_RUN' && tools.applicable === true && orderedSegments.verdict === 'PASS') {
    orderedSegments.verdict = 'NOT_RUN'
    orderedSegments.reason = 'tool persistence evidence is incomplete'
  }
  const reasoning = reasoningEvidence(caseResult, assistantMessages, publicSegments, persistedSegments,
    evidenceReady, orderedSegments.verdict)
  if (caseResult.terminal === null) {
    reasoning.verdict = 'NOT_RUN'
    reasoning.reason = 'no terminal done/error frame was observed'
    reasoning.toolHopVerdict = 'NOT_RUN'
    reasoning.toolHopReason = 'no terminal done/error frame was observed'
    reasoning.persistedChars = null
  }
  return { history, run, orderedSegments, tools, reasoning }
}

function kbOutcome(result) {
  if (result === null || result === undefined) return 'unknown'
  const match = /^\s*状态:\s*([a-z_]+)\s*$/m.exec(String(result))
  return match?.[1] ?? 'unknown'
}

function mechanical(caseResult, definition) {
  if (caseResult.notRun === true) {
    return {
      toolChoice: { pass: null, missing: [], forbiddenHits: [] },
      retrieval: {
        expected: definition.expectRetrieval === true ? 'required' : 'forbidden',
        pass: null,
        falseTrigger: false
      },
      calls: { total: 0, kbSearch: 0, byTool: {} },
      kb: {
        outcomes: [],
        expectedOutcome: definition.expectedKbOutcome ?? null,
        outcomePass: null,
        effectiveHit: 0,
        noHit: 0,
        unknown: 0,
        approximateTitle: false,
        expectedApproximateTitle: definition.expectApproximateTitle ?? null,
        approximateTitlePass: null,
        minCallsExpected: definition.minKbSearchCalls ?? null,
        minCallsPass: null
      }
    }
  }
  const calls = caseResult.toolCalls
  const names = calls.map((call) => call.name).filter(Boolean)
  const required = definition.requiredTools ?? []
  const forbidden = definition.forbiddenTools ?? []
  const missing = required.filter((name) => !names.includes(name))
  const forbiddenHits = forbidden.filter((name) => names.includes(name))
  const kbCalls = calls.filter((call) => call.name === 'kb_search')
  const expectsRetrieval = definition.expectRetrieval === true
  const executionFailed = caseResult.error !== null && caseResult.error !== undefined
  const retrievalMatches = !executionFailed && (expectsRetrieval ? kbCalls.length > 0 : kbCalls.length === 0)
  const outcomes = kbCalls.map((call) => kbOutcome(call.result))
  return {
    toolChoice: {
      pass: !executionFailed && missing.length === 0 && forbiddenHits.length === 0,
      missing,
      forbiddenHits
    },
    retrieval: {
      expected: expectsRetrieval ? 'required' : 'forbidden',
      pass: retrievalMatches,
      falseTrigger: !expectsRetrieval && kbCalls.length > 0
    },
    calls: {
      total: calls.length,
      kbSearch: kbCalls.length,
      byTool: Object.fromEntries([...new Set(names)].map((name) => [name, names.filter((item) => item === name).length]))
    },
    kb: {
      outcomes,
      expectedOutcome: definition.expectedKbOutcome ?? null,
      outcomePass: definition.expectedKbOutcome === undefined || definition.expectedKbOutcome === 'any'
        ? null
        : definition.expectedKbOutcome === 'hit'
          ? outcomes.some((outcome) => outcome === 'ok' || outcome === 'low_confidence')
          : outcomes.includes(definition.expectedKbOutcome),
      effectiveHit: outcomes.filter((outcome) => outcome === 'ok' || outcome === 'low_confidence').length,
      noHit: outcomes.filter((outcome) => outcome === 'no_match').length,
      unknown: outcomes.filter((outcome) => outcome === 'unknown').length,
      approximateTitle: kbCalls.some((call) => String(call.result ?? '').includes('可参考的标题')),
      expectedApproximateTitle: definition.expectApproximateTitle ?? null,
      approximateTitlePass: definition.expectApproximateTitle === undefined
        ? null
        : kbCalls.some((call) => String(call.result ?? '').includes('可参考的标题')) === definition.expectApproximateTitle,
      minCallsExpected: definition.minKbSearchCalls ?? null,
      minCallsPass: definition.minKbSearchCalls === undefined ? null : kbCalls.length >= definition.minKbSearchCalls
    }
  }
}

function evaluatedCase(result, definition) {
  const modelBehaviorFailure = result.notRun !== true && (result.error === null || result.error === undefined)
    && (result.mechanical.toolChoice.pass === false || result.mechanical.retrieval.pass === false
      || result.mechanical.kb.outcomePass === false || result.mechanical.kb.approximateTitlePass === false
      || result.mechanical.kb.minCallsPass === false)
  return { ...result, modelBehaviorFailure }
}

function manualTemplate() {
  return {
    answerCorrectness: null,
    faithfulness: null,
    citations: [],
    reviewer: null,
    notes: null,
    scale: {
      answerCorrectness: '0=incorrect, 1=partially correct, 2=correct',
      faithfulness: '0=unsupported/contradictory, 1=partly supported, 2=fully supported by cited evidence',
      citationVerdicts: 'unchecked | verified | wrong_source | unsupported'
    }
  }
}

function isRecord(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function toolsDisclosureProblem(payload) {
  if (!isRecord(payload) || payload.success !== true || !isRecord(payload.data)) return 'expected ApiResponse success=true with an object data field'
  const data = payload.data
  if (typeof data.context !== 'string' || data.context.length === 0) return 'data.context must be a non-empty string'
  if (!Number.isInteger(data.count) || data.count < 0 || !Number.isInteger(data.registeredCount) || data.registeredCount < data.count) {
    return 'data.count and data.registeredCount must be consistent non-negative integers'
  }
  if (typeof data.definitionsVersion !== 'string' || data.definitionsVersion.length === 0) return 'data.definitionsVersion must be a non-empty string'
  if (!Array.isArray(data.toolNames) || !data.toolNames.every((name) => typeof name === 'string' && name.length > 0)) {
    return 'data.toolNames must contain non-empty strings'
  }
  if (!Array.isArray(data.tools) || data.tools.length !== data.count || data.toolNames.length !== data.count) {
    return 'data.count must equal both toolNames.length and tools.length'
  }
  for (const [index, tool] of data.tools.entries()) {
    if (!isRecord(tool) || typeof tool.name !== 'string' || tool.name.length === 0 || tool.name !== data.toolNames[index]) {
      return `tools[${index}].name must match toolNames[${index}]`
    }
    if (typeof tool.displayName !== 'string' || (tool.titleKey !== null && typeof tool.titleKey !== 'string') || typeof tool.lang !== 'string') {
      return `tools[${index}] has invalid displayName/titleKey/lang fields`
    }
    if (typeof tool.riskLevel !== 'string' || tool.riskLevel.length === 0 || typeof tool.channel !== 'string' || tool.channel.length === 0) {
      return `tools[${index}] must include riskLevel and channel`
    }
    if (!Array.isArray(tool.scopePatterns) || !tool.scopePatterns.every((scope) => typeof scope === 'string' && scope.length > 0)) {
      return `tools[${index}].scopePatterns must contain non-empty strings`
    }
  }
  return null
}

async function disclosureEvidence() {
  if (!disclosureUrl) return { status: 'unknown', reason: 'no public disclosure evidence endpoint configured' }
  let target
  try { target = new URL(disclosureUrl, `${base}/`) } catch {
    return { status: 'invalid', url: disclosureUrl, reason: 'DISCLOSURE_URL must be a valid /api/ai/tools URL' }
  }
  if (!disclosureUrl.startsWith('/') || target.origin !== parsedBase.origin || target.pathname !== '/api/ai/tools') {
    return { status: 'invalid', url: disclosureUrl, reason: 'only the same-origin /api/ai/tools response is valid disclosure evidence' }
  }
  try {
    const response = await request(disclosureUrl)
    const evidence = { url: disclosureUrl, httpStatus: response.status, contentType: response.contentType }
    if (response.status !== 200) return { status: 'unknown', ...evidence, body: response.body ?? response.text, reason: 'disclosure endpoint did not return HTTP 200' }
    const mediaType = response.contentType.split(';', 1)[0].trim().toLowerCase()
    if (mediaType !== 'application/json' && !mediaType.endsWith('+json')) {
      return { status: 'invalid', ...evidence, body: response.body ?? response.text, reason: 'disclosure endpoint Content-Type is not JSON' }
    }
    const problem = toolsDisclosureProblem(response.body)
    if (problem !== null) return { status: 'invalid', ...evidence, body: response.body ?? response.text, reason: problem }
    return { status: 'observed', ...evidence, body: response.body }
  } catch (error) {
    return { status: 'unknown', url: disclosureUrl, error: String(error?.message ?? error) }
  }
}

async function cleanupCreatedResources() {
  const cleanup = { status: 'not_required', writeAllowed: allowWrites, jobs: [], tasks: [], trackingGaps, failures: [] }
  if (!allowWrites && (createdTasks.size > 0 || createdJobs.size > 0)) {
    for (const job of createdJobs.values()) {
      const entry = { ...job, action: 'cancel', status: 'failed', error: 'cleanup blocked without EVAL_ALLOW_WRITES=true' }
      cleanup.jobs.push(entry)
      cleanup.failures.push(entry)
    }
    for (const task of createdTasks.values()) {
      const entry = { ...task, action: 'delete', status: 'failed', error: 'cleanup blocked without EVAL_ALLOW_WRITES=true' }
      cleanup.tasks.push(entry)
      cleanup.failures.push(entry)
    }
    cleanup.status = 'incomplete'
    return cleanup
  }
  for (const job of createdJobs.values()) {
    let entry
    try {
      const response = await request(`/api/jobs/${job.jobId}`, { method: 'DELETE' })
      const status = response.status === 200
        ? 'cancel_requested'
        : response.status === 404
          ? 'already_absent'
          : response.status === 409 && response.body?.code === 'JOB_ALREADY_FINAL'
            ? 'already_final'
            : 'failed'
      entry = { ...job, action: 'cancel', status, httpStatus: response.status, body: response.body ?? response.text }
    } catch (error) {
      entry = { ...job, action: 'cancel', status: 'failed', error: String(error?.message ?? error) }
    }
    cleanup.jobs.push(entry)
    if (entry.status === 'failed') cleanup.failures.push(entry)
  }
  for (const task of createdTasks.values()) {
    let entry
    try {
      const response = await request(`/api/tasks/${task.taskId}`, { method: 'DELETE' })
      const status = response.status === 200 ? 'deleted' : response.status === 404 ? 'already_absent' : 'failed'
      entry = { ...task, action: 'delete', status, httpStatus: response.status, body: response.body ?? response.text }
    } catch (error) {
      entry = { ...task, action: 'delete', status: 'failed', error: String(error?.message ?? error) }
    }
    cleanup.tasks.push(entry)
    if (entry.status === 'failed') cleanup.failures.push(entry)
  }
  if (trackingGaps.length > 0) cleanup.failures.push(...trackingGaps.map((gap) => ({ ...gap, status: 'unresolved_creation' })))
  cleanup.status = cleanup.failures.length > 0 ? 'incomplete' : cleanup.jobs.length + cleanup.tasks.length > 0 ? 'complete' : 'not_required'
  return cleanup
}

const cases = []
let fatalError = null
let disclosure = { status: 'unknown', reason: 'disclosure evidence was not collected' }
let cleanup
let queryCondValidation = { valid: true, evidence: null }
try {
  if (queryCondDefinitions.length > 0) queryCondValidation = await validateQueryCondTask()
  for (const definition of definitions) {
    const queryCondCase = definition.context?.step === 'QUERY_COND'
    for (let round = 1; round <= rounds; round += 1) {
      if (queryCondCase && !queryCondValidation.valid) {
        cases.push(notRunCase(definition, round, queryCondValidation.error))
        continue
      }
      const effectiveDefinition = queryCondCase
        ? { ...definition, context: { ...definition.context, taskId: queryCondValidation.taskId } }
        : definition
      try {
        const result = await executeCase(effectiveDefinition, round)
        cases.push(evaluatedCase({ ...result, mechanical: mechanical(result, effectiveDefinition), manual: manualTemplate() },
          effectiveDefinition))
      } catch (error) {
        const result = {
          id: definition.id,
          kind: definition.kind,
          round,
          sessionId: null,
          runId: null,
          latencyMs: null,
          answer: null,
          reasoning: '',
          orderedSegments: [],
          error: errorInfo('execute', 'infra', { message: String(error?.message ?? error) }),
          terminal: null,
          toolCalls: [],
          usage: null,
          eof: null,
          disclosure: { status: 'unknown', reason: 'case execution failed before disclosure evidence' },
          persistenceEvidence: unavailablePersistenceEvidence('case execution failed; history/run evidence was not collected'),
          mechanical: {
            toolChoice: { pass: false, missing: definition.requiredTools ?? [], forbiddenHits: [] },
            retrieval: { expected: definition.expectRetrieval === true ? 'required' : 'forbidden', pass: false, falseTrigger: false },
            calls: { total: 0, kbSearch: 0, byTool: {} },
            kb: { outcomes: [], effectiveHit: 0, noHit: 0, unknown: 0, approximateTitle: false, minCallsExpected: null, minCallsPass: null }
          },
          manual: manualTemplate()
        }
        cases.push(evaluatedCase(result, effectiveDefinition))
      }
    }
  }
  disclosure = await disclosureEvidence()
} catch (error) {
  fatalError = { message: String(error?.message ?? error), stack: error?.stack ?? null }
} finally {
  cleanup = await cleanupCreatedResources()
}

function scopeMetrics(scope) {
  const required = scope.filter((item) => item.mechanical.retrieval.expected === 'required')
  const forbidden = scope.filter((item) => item.mechanical.retrieval.expected === 'forbidden')
  const correctRetrieval = scope.filter((item) => item.mechanical.retrieval.pass === true).length
  const toolPass = scope.filter((item) => item.mechanical.toolChoice.pass === true).length
  return {
    correctRetrieval,
    retrievalChecks: scope.length,
    retrievalAccuracy: scope.length === 0 ? null : correctRetrieval / scope.length,
    retrievalRequiredPass: required.filter((item) => item.mechanical.retrieval.pass === true).length,
    retrievalRequired: required.length,
    retrievalRecall: required.length === 0
      ? null
      : required.filter((item) => item.mechanical.retrieval.pass === true).length / required.length,
    falseTriggers: forbidden.filter((item) => item.mechanical.retrieval.falseTrigger === true).length,
    retrievalForbidden: forbidden.length,
    falseTriggerRate: forbidden.length === 0
      ? null
      : forbidden.filter((item) => item.mechanical.retrieval.falseTrigger === true).length / forbidden.length,
    toolAccuracy: {
      pass: toolPass,
      total: scope.length,
      ratio: scope.length === 0 ? null : toolPass / scope.length
    }
  }
}

const executable = cases.filter((item) => item.notRun !== true)
const modelEvaluable = cases.filter((item) => item.notRun !== true
  && item.error?.category !== 'infra' && item.error?.category !== 'transcript')
const rawMetrics = scopeMetrics(cases)
const executableMetrics = scopeMetrics(executable)
const modelMetrics = scopeMetrics(modelEvaluable)
const effectiveHits = cases.reduce((total, item) => total + item.mechanical.kb.effectiveHit, 0)
const noHits = cases.reduce((total, item) => total + item.mechanical.kb.noHit, 0)
const unknownKbOutcomes = cases.reduce((total, item) => total + item.mechanical.kb.unknown, 0)
const kbOutcomeCount = effectiveHits + noHits + unknownKbOutcomes
const summary = {
  cases: cases.length,
  executionErrors: cases.filter((item) => item.notRun !== true && item.error !== null && item.error !== undefined).length,
  executableCases: executable.length,
  modelEvaluableCases: modelEvaluable.length,
  notRun: cases.filter((item) => item.notRun === true).length,
  notRunByCategory: {
    fixture: cases.filter((item) => item.notRun === true && item.error?.category === 'fixture').length,
    hitl: cases.filter((item) => item.notRun === true && item.error?.category === 'hitl').length
  },
  infrastructureErrors: cases.filter((item) => item.error?.category === 'infra').length,
  transcriptErrors: cases.filter((item) => item.error?.category === 'transcript').length,
  modelTerminalErrors: cases.filter((item) => item.error?.category === 'model').length,
  modelBehaviorErrors: cases.filter((item) => item.modelBehaviorFailure === true).length,
  realModelErrors: cases.filter((item) => item.error?.category === 'model' || item.modelBehaviorFailure === true).length,
  fatalError: fatalError !== null,
  cleanupStatus: cleanup.status,
  cleanupFailures: cleanup.failures.length,
  ...rawMetrics,
  metrics: {
    raw: rawMetrics,
    executable: executableMetrics,
    modelEvaluable: modelMetrics
  },
  effectiveHits,
  noHits,
  unknownKbOutcomes,
  kbOutcomeCount,
  effectiveHitRate: kbOutcomeCount === 0 ? null : effectiveHits / kbOutcomeCount,
  noHitRate: kbOutcomeCount === 0 ? null : noHits / kbOutcomeCount,
  approximateTitleCases: cases.filter((item) => item.mechanical.kb.approximateTitle).length,
  averageToolCalls: cases.length === 0 ? null : cases.reduce((total, item) => total + item.mechanical.calls.total, 0) / cases.length,
  averageKbSearchCalls: cases.length === 0 ? null : cases.reduce((total, item) => total + item.mechanical.calls.kbSearch, 0) / cases.length,
  note: 'Raw metrics keep every case. Executable excludes NOT_RUN only. Model-evaluable also excludes infra and transcript errors, but keeps model terminal/behavior failures. Transcript, NOT_RUN and real model errors are reported separately; no error is silently dropped.'
}

const report = {
  schemaVersion: 4,
  generatedAt: new Date().toISOString(),
  base,
  tenantId,
  userId,
  rounds,
  fixture: fixturePath,
  fixtureValidation: { queryCond: queryCondValidation },
  isolation: {
    asserted: true,
    remote,
    writesRequired,
    writesAllowed: allowWrites,
    queryCondTaskIdProvided: queryCondTaskId !== ''
  },
  fatalError,
  disclosure,
  cleanup,
  summary,
  cases
}

await mkdir(outDir, { recursive: true })
const reportPath = path.join(outDir, `report-${Date.now()}.json`)
await writeFile(reportPath, JSON.stringify(report, null, 2) + '\n', 'utf8')
if (fatalError !== null || cleanup.status === 'incomplete') process.exitCode = 1
console.log(JSON.stringify({ reportPath, summary }, null, 2))
