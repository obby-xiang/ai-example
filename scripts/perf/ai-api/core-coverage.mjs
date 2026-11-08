import {mkdir, writeFile} from 'node:fs/promises'
import path from 'node:path'
import {createHash, randomUUID} from 'node:crypto'
import {execFile} from 'node:child_process'
import {promisify} from 'node:util'
import {fileURLToPath} from 'node:url'
import {
    classifyCancelProbe,
    createLoadRunner,
    formatSseFrames,
    parseApiLoadConfig,
    planPairedDeletes,
    shouldRetrySseEof,
    summarizeLatencies
} from './load-support.mjs'

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
const SSE_EOF_RETRY_ATTEMPTS = 8
const SSE_EOF_RETRY_DELAY_MS = 200
const CANCEL_STATUS_POLL_ATTEMPTS = 180
const CANCEL_STATUS_POLL_INTERVAL_MS = 250
const outDirInput = process.env.OUT_DIR?.trim()
if (!outDirInput) throw new Error('OUT_DIR is required and must be outside the repository')
const outDir = path.resolve(outDirInput)
const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const outDirRelative = path.relative(repoRoot, outDir)
if (outDirRelative === '' || (!outDirRelative.startsWith(`..${path.sep}`) && outDirRelative !== '..')
    || path.isAbsolute(outDirRelative)) {
    throw new Error('OUT_DIR must be outside the repository')
}
function boundedId(value, maxLength) {
    return value.length <= maxLength
        ? value
        : createHash('sha256').update(value).digest('hex').slice(0, maxLength)
}
const runPrefix = boundedId(process.env.COVERAGE_PREFIX?.trim() || `core-${randomUUID()}`, 16)
const apiLoadConfig = parseApiLoadConfig(process.env)
const redisVerificationEnabled = process.env.EVAL_ENABLE_REDIS_VERIFY === 'true'
const redisCli = process.env.REDIS_CLI?.trim() || process.env.MEMURAI_CLI?.trim() || 'redis-cli'
const redisHost = process.env.REDIS_HOST?.trim() || '127.0.0.1'
const redisPort = process.env.REDIS_PORT?.trim() || '6379'
const redisDb = process.env.REDIS_DB?.trim() || '0'
if (!/^\d+$/.test(redisPort) || !/^\d+$/.test(redisDb)) throw new Error('REDIS_PORT and REDIS_DB must be non-negative integers')
const execFileAsync = promisify(execFile)
const redisConnectionArgs = [
    '--raw', '-h', redisHost, '-p', redisPort, '-n', redisDb,
    ...(process.env.REDIS_CLUSTER === 'true' ? ['-c'] : []),
    ...(process.env.REDIS_TLS === 'true' ? ['--tls'] : [])
]
const redisEvidence = {
    status: 'NOT_RUN',
    directlyVerifiedByCoreCoverage: false,
    mode: 'READ_ONLY_REDIS_CLI',
    preCleanup: {status: 'NOT_RUN', keys: {}, assertions: []},
    postCleanup: {status: 'NOT_RUN', keys: {}, assertions: []},
    note: redisVerificationEnabled
        ? '等待本轮真实 redis-cli 定向只读与清理后复核；静态实现不构成直验。'
        : 'EVAL_ENABLE_REDIS_VERIFY is not true: real Redis verification NOT_RUN; API coverage cannot become overall PASS.'
}
const createdTasks = new Set()
const createdSessions = new Set()
const loadCreatedSessions = new Set()
const loadCreatedTasks = new Set()
const loadActiveRuns = new Set()
const loadFeedbackMessageIds = new Set()
const loadSessionIds = []
const loadBaselines = {captured: false, tasks: new Set(), runs: new Set()}
const activeRuns = new Set()
const cleanupLog = []
const cleanupErrors = []
const coreCleanupErrors = []
let modelInFlight = 0
let maxObservedInFlight = 0
let modelFailure = null
const modelWaiters = []
const safelyRecoveredModelErrors = new WeakSet()

const API_LOAD_PLAN = [
    {
        key: 'GET /api/ai/health',
        route: 'GET /api/ai/health',
        variant: 'shared-readonly',
        scenario: 'shared-readonly',
        note: '200 or 503 with a code is accepted by the existing health semantics'
    },
    {
        key: 'GET /api/ai/tools',
        route: 'GET /api/ai/tools',
        variant: 'shared-readonly',
        scenario: 'shared-readonly',
        note: 'toolNames must still disclose create_task'
    },
    {
        key: 'GET /api/ai/tenants',
        route: 'GET /api/ai/tenants',
        variant: 'shared-readonly',
        scenario: 'shared-readonly',
        note: 'current identity must remain in the directory'
    },
    {
        key: 'GET /api/ai/kb/status',
        route: 'GET /api/ai/kb/status',
        variant: 'shared-readonly',
        scenario: 'shared-readonly'
    },
    {key: 'GET /api/ai/kb/docs', route: 'GET /api/ai/kb/docs', variant: 'shared-readonly', scenario: 'shared-readonly'},
    {
        key: 'GET /api/ai/form/option-sources/{sourceId}',
        route: 'GET /api/ai/form/option-sources/{sourceId}',
        variant: 'shared-readonly',
        scenario: 'shared-readonly',
        note: 'loads the registered definitions source; unregistered sourceId 400 stays in core coverage'
    },
    {key: 'GET /api/ai/runs', route: 'GET /api/ai/runs', variant: 'shared-readonly', scenario: 'shared-readonly'},
    {
        key: 'GET /api/ai/runs/{runId}',
        route: 'GET /api/ai/runs/{runId}',
        variant: 'shared-readonly',
        scenario: 'shared-readonly'
    },
    {
        key: 'GET /api/ai/runs/current',
        route: 'GET /api/ai/runs/current',
        variant: 'shared-readonly',
        scenario: 'shared-readonly',
        note: 'terminal cancel clears the session current index; found=false is the expected terminal state'
    },
    {
        key: 'GET /api/ai/history/{sessionId}',
        route: 'GET /api/ai/history/{sessionId}',
        variant: 'shared-readonly',
        scenario: 'shared-readonly'
    },
    {
        key: 'GET /api/ai/context/{sessionId}',
        route: 'GET /api/ai/context/{sessionId}',
        variant: 'shared-readonly',
        scenario: 'shared-readonly'
    },
    {
        key: 'GET /api/ai/pending/{runId}',
        route: 'GET /api/ai/pending/{runId}',
        variant: 'shared-readonly',
        scenario: 'shared-readonly',
        note: 'after cancellation pending entries may be CANCELLED; shape is asserted, not PENDING'
    },
    {
        key: 'GET /api/ai/feedback',
        route: 'GET /api/ai/feedback',
        variant: 'shared-readonly',
        scenario: 'shared-readonly'
    },
    {
        key: 'GET /api/ai/cancel/{runId}',
        route: 'GET /api/ai/cancel/{runId}',
        variant: 'shared-readonly',
        scenario: 'shared-readonly'
    },
    {
        key: 'GET /api/ai/startup-resume',
        route: 'GET /api/ai/startup-resume',
        variant: 'shared-readonly',
        scenario: 'shared-readonly'
    },
    {
        key: 'GET /api/ai/events/{runId} [probe=1]',
        route: 'GET /api/ai/events/{runId}',
        variant: 'probe=1',
        scenario: 'shared-readonly',
        note: 'JSON readiness probe only; it is not evidence of SSE concurrency'
    },
    {
        key: 'GET /api/ai/events/{runId} [sse-replay]',
        route: 'GET /api/ai/events/{runId}',
        variant: 'sse-replay',
        scenario: 'shared-readonly',
        note: 'real SSE replay of the cancelled run until its terminal frame'
    },
    {
        key: 'POST /api/ai/cancel/{runId} [terminal-idempotent]',
        route: 'POST /api/ai/cancel/{runId}',
        variant: 'terminal-idempotent',
        scenario: 'terminal-idempotent',
        note: 'run is already CANCELLED; this is not a first cancel of a running run'
    },
    {
        key: 'POST /api/ai/runs/{runId}/resume [terminal-already-cancelled]',
        route: 'POST /api/ai/runs/{runId}/resume',
        variant: 'terminal-already-cancelled',
        scenario: 'terminal-resume',
        modelGate: false,
        serial: true,
        note: 'terminal ALREADY_CANCELLED does not call the model, so no model slot is taken; serialized because the process-local resuming mutex answers 409 RESUME_IN_PROGRESS to overlapping calls'
    },
    {
        key: 'POST /api/ai/cancel/client-request',
        route: 'POST /api/ai/cancel/client-request',
        variant: 'independent-session-tombstone',
        scenario: 'independent-resource',
        note: 'each request uses a unique session and clientRequestId; tombstone session purged in finally'
    },
    {
        key: 'POST /api/ai/feedback',
        route: 'POST /api/ai/feedback',
        variant: 'independent-message-id',
        scenario: 'independent-resource',
        note: 'each request uses a unique messageId and is removed with rating=0 in finally'
    },
    {
        key: 'POST /api/ai/form/attachments',
        route: 'POST /api/ai/form/attachments',
        variant: 'independent-form',
        scenario: 'independent-model-workflow',
        modelGate: true,
        note: 'each request owns a separate real pending form; single-field supersede is never raced'
    },
    {
        key: 'POST /api/ai/session',
        route: 'POST /api/ai/session',
        variant: 'independent-resource',
        scenario: 'independent-resource',
        serial: true,
        note: 'serial on purpose: the millisecond id generator can legitimately answer 503 on same-millisecond collisions; serial measurement avoids manufactured collisions and does not retry them away'
    },
    {
        key: 'DELETE /api/ai/session/{sessionId}',
        route: 'DELETE /api/ai/session/{sessionId}',
        variant: 'paired-delete',
        scenario: 'paired-delete',
        pairedDeletes: true,
        note: 'deletes only sessions created successfully by the previous entry; configured=created count, skipped=remaining planned slots, zero targets are SKIPPED'
    }
]

const apiLoad = {
    enabled: apiLoadConfig.enabled,
    ran: false,
    status: 'NOT_RUN',
    config: {
        concurrency: apiLoadConfig.concurrency,
        requestsPerEndpoint: apiLoadConfig.requestsPerEndpoint
    },
    maxObservedApiInFlight: 0,
    skipped: 0,
    complete: null,
    planned: API_LOAD_PLAN.map((plan) => plan.key),
    endpoints: {},
    cleanup: {done: true, detail: 'no load-created resources'},
    cleanupErrors: [],
    fatalError: null,
    notes: apiLoadConfig.enabled
        ? []
        : ['EVAL_ENABLE_API_LOAD is not true: API load stage NOT_RUN; NOT_RUN is not PASS']
}

class CoverageError extends Error {
}

class PlainTextModelResponseError extends CoverageError {
}

let plainTextRetryCount = 0

function errorDetail(error) {
    if (error instanceof CoverageError) return error.message
    if (error instanceof Error) return error.name
    return 'non-Error rejection'
}

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
        ...(json ? {'Content-Type': 'application/json'} : {})
    }
}

function tripModelCircuit(error, phase = 'model-execution') {
    const failure = error instanceof Error ? error : new CoverageError('non-Error rejection')
    if (modelFailure === null) modelFailure = {error: failure, phase}
    for (const waiter of modelWaiters.splice(0)) waiter.reject(modelFailure.error)
}

async function acquireModel() {
    if (modelFailure !== null) throw modelFailure.error
    while (modelInFlight >= modelConcurrencyConfigured) {
        await new Promise((resolve, reject) => modelWaiters.push({resolve, reject}))
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

async function modelCall(fn, allowPlainTextRetry = false) {
    await acquireModel()
    let safeToRelease = false
    try {
        const result = await fn()
        safeToRelease = true
        return result
    } catch (error) {
        const retryPlainText = allowPlainTextRetry && error instanceof PlainTextModelResponseError
        const safelyRecovered = isSafelyRecoveredModelError(error)
        if (!retryPlainText || !safelyRecovered) tripModelCircuit(error)
        safeToRelease = safelyRecovered
        throw error
    } finally {
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
    cleanup: {done: false, detail: 'pending finally cleanup'},
    attempts: []
}]))

function record(key, {httpStatus, latencyMs, assertions = [], error = null, stage = null}) {
    const entry = coverage[key]
    assert(entry, `unregistered endpoint ${key}`)
    if (error !== null) entry.covered = false
    else if (entry.error === null) entry.covered = true
    entry.latencyMs = latencyMs
    entry.httpStatus = httpStatus
    entry.assertions.push(...assertions)
    if (entry.error === null) entry.error = error
    entry.attempts.push({httpStatus, latencyMs, assertions, error, stage})
}

async function request(url, options = {}) {
    const started = Date.now()
    const response = await fetch(`${base.toString().replace(/\/$/, '')}${url}`, {
        ...options,
        headers: {...headers(options.json === true), ...(options.headers ?? {})},
        signal: AbortSignal.timeout(timeoutMs)
    })
    const text = await response.text()
    let body = null
    try {
        body = text === '' ? null : JSON.parse(text)
    } catch {
        body = text
    }
    return {
        status: response.status,
        body,
        text,
        contentType: response.headers.get('content-type') ?? '',
        latencyMs: Date.now() - started
    }
}

async function call(key, url, options, assertion, recordResult = true) {
    const started = Date.now()
    let response = null
    try {
        response = await request(url, options)
        const assertions = await assertion(response)
        if (recordResult) {
            record(key, {
                httpStatus: response.status,
                latencyMs: response.latencyMs,
                assertions: Array.isArray(assertions) ? assertions : [assertions]
            })
        }
        return response
    } catch (error) {
        const httpStatus = response?.status ?? null
        if (recordResult) {
            record(key, {
                httpStatus,
                latencyMs: response?.latencyMs ?? Date.now() - started,
                assertions: [],
                error: errorDetail(error)
            })
        }
        if (httpStatus !== null && error !== null && typeof error === 'object' && error.httpStatus === undefined) {
            error.httpStatus = httpStatus
        }
        throw error
    }
}

function ok(response, detail = 'HTTP 2xx') {
    assert(response.status >= 200 && response.status < 300, `${detail}: got ${response.status}`)
    return detail
}

function dataObject(response) {
    const data = payloadOf(response)
    assert(data !== null && typeof data === 'object' && !Array.isArray(data), `expected object payload: HTTP ${response.status}`)
    return data
}

function dataArray(response) {
    const data = payloadOf(response)
    assert(Array.isArray(data), `expected array payload: HTTP ${response.status}`)
    return data
}

const TRANSCRIPT_FAILURE_CODES = new Set(['AI_TRANSCRIPT_INCOMPLETE', 'AI_TRANSCRIPT_WRITE_FAILED'])

function assertHealthyTranscript(frame) {
    assert(frame !== null && typeof frame === 'object' && !Array.isArray(frame), 'terminal/history entry must be an object')
    assert(frame.transcriptDegraded !== true, 'transcriptDegraded=true is never a healthy terminal')
    assert(frame.historyComplete !== false, 'historyComplete=false is never a healthy terminal')
    const codes = [frame.code, frame.errorCode, frame.error?.code, frame.turnEnd?.code]
        .filter((code) => typeof code === 'string')
        .map((code) => code.toUpperCase())
    const degradedCode = codes.find((code) => TRANSCRIPT_FAILURE_CODES.has(code))
    assert(degradedCode === undefined, `transcript failure code is never healthy: ${degradedCode ?? 'unknown'}`)
}

function assertHealthyTerminal(frame, {runId = null, cancelled = false} = {}) {
    assertHealthyTranscript(frame)
    assert(frame.type === 'done', 'ordinary business/model error terminal remains FAIL; expected a healthy done frame')
    if (runId !== null) assert(frame.runId === runId, 'terminal runId does not belong to the current run')
    if (cancelled) assert(frame.cancelled === true, 'expected a cancelled done terminal')
    else assert(frame.cancelled !== true, 'normal model scenario unexpectedly ended cancelled')
    return cancelled ? 'healthy cancelled done; transcript complete' : 'healthy normal done; transcript complete'
}

function normalizeTranscriptMessages(messages) {
    assert(Array.isArray(messages), 'history messages must be an array')
    return messages.map((message) => {
        assert(message !== null && typeof message === 'object' && !Array.isArray(message), 'history message must be an object')
        const kind = String(message.kind ?? message.type ?? '').toLowerCase()
        const toolResponses = Array.isArray(message.toolResponses)
            ? message.toolResponses
            : kind === 'tool' && typeof message.toolCallId === 'string'
                ? [{id: message.toolCallId, name: message.name ?? null, responseData: message.result ?? ''}]
                : []
        return {
            kind,
            runId: message.runId ?? null,
            text: message.text ?? '',
            stopped: message.stopped === true,
            outcome: message.outcome ?? null,
            errorCode: message.errorCode ?? null,
            errorMessage: message.errorMessage ?? null,
            unconfirmed: message.unconfirmed === true,
            toolCalls: Array.isArray(message.toolCalls)
                ? message.toolCalls.map((call) => ({
                    id: call?.id ?? null,
                    type: call?.type ?? null,
                    name: call?.name ?? null,
                    arguments: call?.arguments ?? null
                }))
                : [],
            toolCallStates: message.toolCallStates ?? {},
            segments: Array.isArray(message.segments)
                ? message.segments.filter((segment) => segment?.kind !== 'reasoning').map((segment) => ({
                    kind: segment?.kind ?? null,
                    text: segment?.text ?? null,
                    toolCallId: segment?.toolCallId ?? null,
                    seq: typeof segment?.seq === 'number' || typeof segment?.seq === 'string' ? String(segment.seq) : null
                }))
                : [],
            toolResponses: toolResponses.map((response) => ({
                id: response?.id ?? null,
                name: response?.name ?? null,
                responseData: response?.responseData ?? null,
                createdTaskId: response?.createdTaskId ?? response?.data?.createdTaskId ?? null
            }))
        }
    })
}

function stableValue(value) {
    if (Array.isArray(value)) return value.map((item) => stableValue(item))
    if (value !== null && typeof value === 'object') {
        return Object.fromEntries(Object.keys(value).sort().map((key) => [key, stableValue(value[key])]))
    }
    return value
}

function transcriptFingerprint(entries) {
    const comparable = entries.map((entry) => ({
        ...entry,
        toolResponses: entry.toolResponses.map((response) => ({
            id: response.id,
            name: response.name,
            responseData: response.responseData
        }))
    }))
    return createHash('sha256').update(JSON.stringify(stableValue(comparable))).digest('hex')
}

function visibleTextFromFrames(frames) {
    let previousSeq = null
    let text = ''
    for (const frame of frames) {
        if (typeof frame.seq === 'number' || typeof frame.seq === 'string') {
            const seq = BigInt(frame.seq)
            if (previousSeq !== null) assert(seq > previousSeq, 'combined SSE evidence sequence must strictly increase')
            previousSeq = seq
        }
        if (frame.type !== 'delta' || frame.kind === 'reasoning') continue
        assert(typeof frame.text === 'string', 'text delta must carry string text')
        text += frame.text
    }
    return text
}

function assertCreateTaskResponse(response, expectedTaskId, source) {
    assert(response !== null && response !== undefined, `${source}: create_task response is missing`)
    let structuredId = response.createdTaskId
    if (structuredId === null && typeof response.responseData === 'string' && response.responseData.trimStart().startsWith('{')) {
        try {
            const parsed = JSON.parse(response.responseData)
            structuredId = parsed?.createdTaskId ?? parsed?.data?.createdTaskId ?? null
        } catch {
            structuredId = null
        }
    }
    if (structuredId !== null && structuredId !== undefined) {
        assert(normalizeTaskId(structuredId) === expectedTaskId,
            `${source}: structured createdTaskId does not match the created task`)
        return 'structured-createdTaskId'
    }
    assert(typeof response.responseData === 'string', `${source}: create_task response body is missing`)
    const explicitIds = [...response.responseData.matchAll(/#([1-9]\d*)/g)]
        .map((match) => normalizeTaskId(match[1]))
    assert(explicitIds.includes(expectedTaskId), `${source}: create_task response lacks the exact #taskId boundary`)
    return 'explicit-#taskId-boundary'
}

function assertMainTerminalHistory(messages, expectation, source = 'HTTP history') {
    const entries = normalizeTranscriptMessages(messages)
    assert(entries.length > 0, `${source}: history is empty`)
    assert(entries.every((entry) => entry.runId === expectation.runId), `${source}: entry belongs to another or unknown run`)
    for (const entry of entries) assertHealthyTranscript(entry)
    const users = entries.filter((entry) => entry.kind === 'user')
    assert(users.length === 1 && users[0].text === expectation.userMessage,
        `${source}: expected exactly one original USER body`)
    const assistants = entries.filter((entry) => entry.kind === 'assistant')
    assert(assistants.length > 0, `${source}: same-run ASSISTANT is missing`)
    const parts = []
    for (const assistant of assistants) {
        if (assistant.segments.length === 0) {
            assert(assistant.toolCalls.length === 0, `${source}: assistant tool calls have no persisted parts`)
            if (assistant.text !== '') parts.push({kind: 'text', text: assistant.text, toolCallId: null, seq: null})
            continue
        }
        let previousSeq = null
        for (const segment of assistant.segments) {
            assert(segment.seq !== null && /^\d+$/.test(segment.seq), `${source}: part has no valid seq`)
            const seq = BigInt(segment.seq)
            if (previousSeq !== null) assert(seq >= previousSeq, `${source}: parts are out of order`)
            previousSeq = seq
            if (segment.kind === 'text') {
                assert(typeof segment.text === 'string', `${source}: text part has no string body`)
                parts.push({kind: 'text', text: segment.text, toolCallId: null, seq: segment.seq})
            } else if (segment.kind === 'tool') {
                assert(typeof segment.toolCallId === 'string' && segment.toolCallId.length > 0,
                    `${source}: tool part has no stable toolCallId`)
                parts.push({kind: 'tool', text: null, toolCallId: segment.toolCallId, seq: segment.seq})
            } else {
                throw new CoverageError(`${source}: unsupported persisted part kind`)
            }
        }
    }
    const toolParts = parts.filter((part) => part.kind === 'tool')
    for (const assistant of assistants) {
        for (const call of assistant.toolCalls) {
            assert(typeof call.id === 'string' && toolParts.filter((part) => part.toolCallId === call.id).length === 1,
                `${source}: assistant tool call must have exactly one ordered part`)
        }
    }
    const createIndex = toolParts.findIndex((part) => part.toolCallId === expectation.createCallId)
    const navigateIndex = toolParts.findIndex((part) => part.toolCallId === expectation.navigationCallId)
    assert(createIndex >= 0 && navigateIndex > createIndex,
        `${source}: create_task part must precede navigate_to part`)
    const actualText = parts.filter((part) => part.kind === 'text').map((part) => part.text).join('')
    assert(actualText === expectation.assistantText, `${source}: assistant正文与有序 SSE text deltas 不一致`)
    const responses = entries.flatMap((entry) => entry.toolResponses)
    const createResponse = responses.find((response) => response.id === expectation.createCallId)
    const navigateResponse = responses.find((response) => response.id === expectation.navigationCallId)
    const createTaskIdSource = assertCreateTaskResponse(createResponse, expectation.taskId, source)
    assert(navigateResponse?.responseData === expectation.navigationResult,
        `${source}: navigate_to final response body mismatch`)
    const createState = assistants.map((assistant) => assistant.toolCallStates?.[expectation.createCallId])
        .find((state) => state !== undefined)
    const navigateState = assistants.map((assistant) => assistant.toolCallStates?.[expectation.navigationCallId])
        .find((state) => state !== undefined)
    assert(createState?.status === 'EXECUTED' && createState?.outcome === 'ok',
        `${source}: create_task did not retain its executed/ok outcome`)
    assert(navigateState?.status === 'FRONTEND_RESULT' && navigateState?.outcome === 'ok',
        `${source}: navigate_to did not retain its frontend-result/ok outcome`)
    assert(assistants.every((assistant) => assistant.outcome === 'CANCELLED' && !assistant.unconfirmed),
        `${source}: final assistant outcome must be confirmed CANCELLED`)
    assertHealthyTerminal(expectation.terminalFrame, {runId: expectation.runId, cancelled: true})
    return {
        entryCount: entries.length,
        assistantCount: assistants.length,
        partCount: parts.length,
        textChars: actualText.length,
        createTaskIdSource,
        parts: parts.map((part) => ({
            kind: part.kind,
            toolCallId: part.toolCallId,
            seq: part.seq,
            textLength: part.text?.length ?? null
        })),
        fingerprint: transcriptFingerprint(entries)
    }
}

async function redisCommand(...args) {
    try {
        const {stdout} = await execFileAsync(redisCli, [...redisConnectionArgs, ...args], {
            timeout: timeoutMs,
            maxBuffer: 4 * 1024 * 1024,
            windowsHide: true
        })
        return stdout.replace(/\r?\n$/, '')
    } catch (error) {
        throw new CoverageError(`redis-cli ${args[0]} failed: ${errorDetail(error)}`)
    }
}

function redisLines(output) {
    return output === '' ? [] : output.split(/\r?\n/)
}

function parseRedisJson(raw, label) {
    assert(typeof raw === 'string' && raw.length > 0, `${label}: Redis value is missing`)
    try {
        const value = JSON.parse(raw)
        assert(value !== null && typeof value === 'object' && !Array.isArray(value), `${label}: expected JSON object`)
        return value
    } catch (error) {
        if (error instanceof CoverageError) throw error
        throw new CoverageError(`${label}: invalid JSON value`)
    }
}

function redisKeysFor({sessionId, runId}) {
    const runTag = `{${tenantId}:${userId}:run:${runId}}`
    const escapedSession = sessionId.replaceAll('%', '%25').replaceAll('{', '%7B').replaceAll('}', '%7D')
    const sessionTag = `{${escapedSession}}`
    const identity = `${tenantId}:${userId}:`
    const transcript = `ai:tx:${identity}${sessionTag}`
    const memory = `chat:mem:${identity}${sessionTag}`
    return {
        run: `ai:run:${runTag}`,
        runstate: `ai:runstate:${runTag}`,
        pending: `ai:pending:${runTag}`,
        claim: `ai:claim:${runTag}`,
        ledger: `ai:ledger:${runTag}`,
        idem: `ai:idem:${runTag}`,
        seq: `ai:seq:${runTag}`,
        xevents: `ai:xevents:${runTag}`,
        lease: `ai:lease:run:${runTag}`,
        fence: `ai:fence:run:${runTag}`,
        req: `ai:req:${runTag}`,
        decided: `ai:decided:${runTag}`,
        leaseEpoch: `ai:lease:epoch:${runTag}`,
        cancel: `ai:cancel:${runTag}`,
        transcript,
        transcriptOrder: `${transcript}:order`,
        transcriptMeta: `${transcript}:meta`,
        transcriptTruncated: `${transcript}:truncated`,
        context: `ai:ctx:${identity}${sessionId}`,
        contextLedger: `ai:ctx:ledger:${identity}${sessionId}`,
        contextEvents: `ai:ctx:events:${identity}${sessionId}`,
        memory,
        memoryVersion: `${memory}:ver`,
        memoryGeneration: `${memory}:gen`,
        sessionRecord: `ai:session:${identity}${sessionId}`,
        sessionCurrent: `ai:session:current:${identity}${sessionId}`,
        sessionRuns: `ai:session:runs:${identity}${sessionId}`,
        sessionActive: `ai:session:active:${identity}${sessionId}`,
        runListIndex: `ai:runs:${tenantId}:${userId}`,
        recoveryIndex: 'ai:recovery:due',
        identityRecoveryIndex: `ai:recovery:due:${tenantId}:${userId}`,
        purgeGuard: `ai:purge:guard:${identity}${sessionId}`,
        purgePending: `ai:purge:pending:${identity}${sessionId}`,
        purgeDue: 'ai:purge:retry:due'
    }
}

async function readRedisKey(target, name, key, expectedType, required = true, maxTtlMs = 86400000) {
    const type = (await redisCommand('TYPE', key)).trim()
    const pttlMs = Number.parseInt(await redisCommand('PTTL', key), 10)
    if (type === 'none') {
        assert(!required, `${name}: required Redis key is absent`)
        target[name] = {key, type, pttlMs, count: 0}
        return target[name]
    }
    assert(type === expectedType, `${name}: expected Redis type ${expectedType}, got ${type}`)
    assert(Number.isSafeInteger(pttlMs) && pttlMs > 0 && pttlMs <= maxTtlMs,
        `${name}: TTL must be bounded in (0, ${maxTtlMs}] ms`)
    const countCommand = {string: 'STRLEN', hash: 'HLEN', list: 'LLEN', stream: 'XLEN', zset: 'ZCARD', set: 'SCARD'}[type]
    assert(countCommand !== undefined, `${name}: unsupported Redis type ${type}`)
    const count = Number.parseInt(await redisCommand(countCommand, key), 10)
    assert(Number.isSafeInteger(count) && count >= 0, `${name}: invalid Redis count`)
    target[name] = {key, type, pttlMs, count}
    return target[name]
}

function parseSingleStreamEntry(raw) {
    const lines = redisLines(raw)
    assert(lines.length === 5, 'terminal XRANGE evidence must contain exactly one id and seq/frame field pairs')
    const fields = {}
    for (let index = 1; index < lines.length; index += 2) fields[lines[index]] = lines[index + 1]
    const frame = parseRedisJson(fields.frame, 'events terminal frame')
    assert(fields.seq === String(frame.seq), 'events stream seq field and frame.seq differ')
    assert(lines[0] === `${frame.seq}-0`, 'events stream id must be <seq>-0')
    return {id: lines[0], frame}
}

async function verifyRedisPreCleanup(expectation) {
    const keys = redisKeysFor(expectation)
    const observed = {}
    const specs = [
        ['run', keys.run, 'string', true, 86400000],
        ['runstate', keys.runstate, 'string', true, 86400000],
        ['pending', keys.pending, 'hash', true, 86400000],
        ['claim', keys.claim, 'hash', true, 86400000],
        ['ledger', keys.ledger, 'list', true, 86400000],
        ['idem', keys.idem, 'hash', true, 86400000],
        ['seq', keys.seq, 'string', true, 86400000],
        ['xevents', keys.xevents, 'stream', true, 86400000],
        ['lease', keys.lease, 'string', false, 30000],
        ['fence', keys.fence, 'string', true, 86400000],
        ['req', keys.req, 'hash', true, 86400000],
        ['decided', keys.decided, 'hash', true, 86400000],
        ['leaseEpoch', keys.leaseEpoch, 'string', true, 86400000],
        ['cancel', keys.cancel, 'string', true, 86400000],
        ['transcript', keys.transcript, 'hash', true, 86400000],
        ['transcriptOrder', keys.transcriptOrder, 'zset', true, 86400000],
        ['transcriptMeta', keys.transcriptMeta, 'hash', true, 86400000],
        ['transcriptTruncated', keys.transcriptTruncated, 'string', false, 86400000],
        ['context', keys.context, 'hash', true, 86400000],
        ['contextLedger', keys.contextLedger, 'list', false, 86400000],
        ['contextEvents', keys.contextEvents, 'list', false, 86400000],
        ['memory', keys.memory, 'list', true, 86400000],
        ['memoryVersion', keys.memoryVersion, 'string', false, 86400000],
        ['memoryGeneration', keys.memoryGeneration, 'string', false, 86400000],
        ['sessionRecord', keys.sessionRecord, 'string', false, 86400000],
        ['sessionCurrent', keys.sessionCurrent, 'string', false, 86400000],
        ['sessionRuns', keys.sessionRuns, 'set', true, 86400000],
        ['sessionActive', keys.sessionActive, 'zset', false, 86400000],
        ['runListIndex', keys.runListIndex, 'zset', true, 86400000],
        ['recoveryIndex', keys.recoveryIndex, 'zset', false, 86400000],
        ['identityRecoveryIndex', keys.identityRecoveryIndex, 'zset', false, 86400000]
    ]
    for (const [name, key, type, required, maxTtl] of specs) {
        await readRedisKey(observed, name, key, type, required, maxTtl)
    }
    assert(observed.sessionCurrent.type === 'none', 'terminal session current index must be absent')
    assert(await redisCommand('SISMEMBER', keys.sessionRuns, expectation.runId) === '1',
        'session runs index lacks current run')
    assert((await redisCommand('ZSCORE', keys.sessionActive, expectation.runId)).trim() === '',
        'terminal run remains in session active index')
    assert((await redisCommand('ZSCORE', keys.runListIndex, expectation.runId)).trim() !== '',
        'identity run list index lacks current run')
    assert((await redisCommand('ZSCORE', keys.recoveryIndex, `${tenantId}/${userId}/${expectation.runId}`)).trim() === '',
        'terminal run remains in global recovery index')
    assert((await redisCommand('ZSCORE', keys.identityRecoveryIndex, expectation.runId)).trim() === '',
        'terminal run remains in identity recovery index')

    const snapshot = parseRedisJson(await redisCommand('GET', keys.run), 'run snapshot')
    assert(snapshot.runId === expectation.runId && snapshot.sessionId === expectation.sessionId,
        'Redis run snapshot ownership mismatch')
    assert(String(snapshot.tenantId) === tenantId && String(snapshot.userId) === userId,
        'Redis run snapshot identity mismatch')
    assert(snapshot.status === 'CANCELLED', 'Redis run snapshot is not CANCELLED')
    assert((await redisCommand('GET', keys.runstate)).trim() === 'CANCELLED', 'Redis runstate is not CANCELLED')
    assert((await redisCommand('GET', keys.cancel)).trim() !== '', 'Redis cancel fact is missing')

    const createPending = parseRedisJson(await redisCommand('HGET', keys.pending, expectation.createCallId), 'create_task pending')
    const navigatePending = parseRedisJson(await redisCommand('HGET', keys.pending, expectation.navigationCallId), 'navigate_to pending')
    assert(createPending.toolCallId === expectation.createCallId
        && createPending.status === 'EXECUTED' && createPending.outcome === 'ok'
        && normalizeTaskId(createPending.createdTaskId) === expectation.taskId,
        'Redis create_task pending outcome/createdTaskId mismatch')
    assert(navigatePending.toolCallId === expectation.navigationCallId
        && navigatePending.status === 'FRONTEND_RESULT' && navigatePending.outcome === 'ok',
        'Redis navigate_to pending outcome mismatch')
    assert(observed.pending.count >= 2 && observed.pending.count <= 100, 'pending count is incomplete or exceeds bounded evidence')
    assert(observed.ledger.count >= 1 && observed.ledger.count <= 100, 'ledger count is incomplete or exceeds bounded evidence')
    const ledger = redisLines(await redisCommand('LRANGE', keys.ledger, '0', '99'))
        .map((entry) => parseRedisJson(entry, 'ledger entry'))
    assert(ledger.some((entry) => entry.toolCallId === expectation.createCallId
        && entry.name === 'create_task' && entry.outcome === 'ok'), 'Redis ledger lacks executed create_task/ok entry')

    assert(observed.transcript.count > 0 && observed.transcript.count <= 100,
        'transcript count is empty or exceeds bounded evidence')
    assert(observed.transcriptOrder.count === observed.transcript.count, 'transcript data/order counts differ')
    const entryIds = redisLines(await redisCommand('ZRANGE', keys.transcriptOrder, '0', '99'))
    assert(entryIds.length === observed.transcript.count, 'transcript order read is incomplete')
    const rawEntries = redisLines(await redisCommand('HMGET', keys.transcript, ...entryIds))
    assert(rawEntries.length === entryIds.length, 'transcript HMGET count mismatch')
    const transcriptEntries = rawEntries.map((raw, index) => {
        const entry = parseRedisJson(raw, `transcript ${entryIds[index]}`)
        assert(entry.entryId === entryIds[index], 'transcript order entryId mismatch')
        return entry
    })
    const redisHistory = assertMainTerminalHistory(transcriptEntries, expectation, 'Redis transcript')
    const historyResponse = await request(`/api/ai/history/${encodeURIComponent(expectation.sessionId)}?latest=100`)
    ok(historyResponse)
    const historyData = dataObject(historyResponse)
    assert(historyData.hasMore !== true, 'HTTP history comparison requires the complete bounded page')
    const httpHistory = assertMainTerminalHistory(historyData.messages, expectation, 'HTTP history for Redis comparison')
    assert(httpHistory.fingerprint === redisHistory.fingerprint, 'HTTP history and Redis transcript content/order differ')

    const terminalEvent = parseSingleStreamEntry(await redisCommand('XREVRANGE', keys.xevents, '+', '-', 'COUNT', '1'))
    assertHealthyTerminal(terminalEvent.frame, {runId: expectation.runId, cancelled: true})
    assert(BigInt((await redisCommand('GET', keys.seq)).trim()) === BigInt(terminalEvent.frame.seq),
        'Redis seq does not match the final event')
    return {
        keys: observed,
        httpHistory,
        redisHistory,
        terminalEvent: {id: terminalEvent.id, seq: terminalEvent.frame.seq, type: terminalEvent.frame.type},
        assertions: [
            'run/session/identity ownership and CANCELLED post-state',
            'transcript HTTP/Redis canonical content and ordered parts are identical',
            'events terminal frame, pending outcomes, bounded ledger and context are directly read',
            'all observed keys have expected types and bounded TTL; no KEYS/SCAN/write command used'
        ]
    }
}

async function verifyRedisPostCleanup(expectation) {
    const keys = redisKeysFor(expectation)
    const observed = {}
    const absentSpecs = [
        ['run', keys.run, 'string'], ['runstate', keys.runstate, 'string'], ['pending', keys.pending, 'hash'],
        ['claim', keys.claim, 'hash'], ['ledger', keys.ledger, 'list'], ['idem', keys.idem, 'hash'],
        ['seq', keys.seq, 'string'], ['xevents', keys.xevents, 'stream'], ['lease', keys.lease, 'string'],
        ['fence', keys.fence, 'string'], ['req', keys.req, 'hash'], ['decided', keys.decided, 'hash'],
        ['leaseEpoch', keys.leaseEpoch, 'string'], ['cancel', keys.cancel, 'string'],
        ['transcript', keys.transcript, 'hash'], ['transcriptOrder', keys.transcriptOrder, 'zset'],
        ['transcriptMeta', keys.transcriptMeta, 'hash'], ['transcriptTruncated', keys.transcriptTruncated, 'string'],
        ['context', keys.context, 'hash'], ['contextLedger', keys.contextLedger, 'list'],
        ['contextEvents', keys.contextEvents, 'list'], ['memory', keys.memory, 'list'],
        ['sessionRecord', keys.sessionRecord, 'string'], ['sessionCurrent', keys.sessionCurrent, 'string']
    ]
    for (const [name, key, type] of absentSpecs) {
        await readRedisKey(observed, name, key, type, false)
        assert(observed[name].type === 'none', `${name}: session-owned key survived post-cleanup`)
    }
    for (const [name, key, type, maxTtl] of [
        ['sessionRuns', keys.sessionRuns, 'set', 86400000],
        ['sessionActive', keys.sessionActive, 'zset', 86400000],
        ['runListIndex', keys.runListIndex, 'zset', 86400000],
        ['recoveryIndex', keys.recoveryIndex, 'zset', 86400000],
        ['identityRecoveryIndex', keys.identityRecoveryIndex, 'zset', 86400000],
        ['memoryVersion', keys.memoryVersion, 'string', 86400000],
        ['memoryGeneration', keys.memoryGeneration, 'string', 86400000],
        ['purgeGuard', keys.purgeGuard, 'string', 86400000],
        ['purgePending', keys.purgePending, 'string', 86400000],
        ['purgeDue', keys.purgeDue, 'zset', 86400000]
    ]) {
        const required = ['purgeGuard', 'purgePending', 'purgeDue'].includes(name)
        await readRedisKey(observed, name, key, type, required, maxTtl)
    }
    assert(await redisCommand('SISMEMBER', keys.sessionRuns, expectation.runId) === '0',
        'post-cleanup session runs index still contains run')
    assert((await redisCommand('ZSCORE', keys.sessionActive, expectation.runId)).trim() === '',
        'post-cleanup session active index still contains run')
    assert((await redisCommand('ZSCORE', keys.runListIndex, expectation.runId)).trim() === '',
        'post-cleanup identity run list still contains run')
    assert((await redisCommand('ZSCORE', keys.recoveryIndex, `${tenantId}/${userId}/${expectation.runId}`)).trim() === '',
        'post-cleanup global recovery index still contains run')
    assert((await redisCommand('ZSCORE', keys.identityRecoveryIndex, expectation.runId)).trim() === '',
        'post-cleanup identity recovery index still contains run')
    const purgePending = parseRedisJson(await redisCommand('GET', keys.purgePending), 'purge memory-verification pending')
    assert(purgePending.sessionId === expectation.sessionId
        && String(purgePending.tenantId) === tenantId && String(purgePending.userId) === userId,
        'purge memory-verification pending identity mismatch')
    const isEpochMs = (value) => (typeof value === 'number' && Number.isSafeInteger(value) && value > 0)
        || (typeof value === 'string' && /^[1-9]\d*$/.test(value))
    assert(isEpochMs(purgePending.verifyAtMs) && isEpochMs(purgePending.nextAtMs),
        'purge memory verification requires positive verifyAtMs/nextAtMs')
    assert(BigInt(purgePending.verifyAtMs) === BigInt(purgePending.nextAtMs),
        'purge memory verification verifyAtMs must equal nextAtMs')
    const dueScore = (await redisCommand('ZSCORE', keys.purgeDue, keys.purgePending)).trim()
    assert(/^[1-9]\d*$/.test(dueScore) && BigInt(dueScore) === BigInt(purgePending.nextAtMs),
        'purge retry due member/score must match pending key and nextAtMs')
    return {
        keys: observed,
        purgeVerification: {
            verifyAtMs: String(purgePending.verifyAtMs),
            nextAtMs: String(purgePending.nextAtMs),
            dueMember: keys.purgePending,
            dueScore
        },
        assertions: [
            'current run/transcript/events/pending/ledger/context/memory/session-owned keys are absent post-cleanup',
            'shared indexes no longer reference the run; guard and memory-verification pending/due retain bounded TTL',
            'memory verification verifyAtMs=nextAtMs and ai:purge:retry:due member/score are directly confirmed',
            'post-cleanup evidence is directly read with the same read-only redis-cli boundary'
        ]
    }
}

async function runRedisPreCleanup(expectation) {
    if (!redisVerificationEnabled) return
    redisEvidence.status = 'RUNNING'
    redisEvidence.preCleanup.status = 'RUNNING'
    try {
        const result = await verifyRedisPreCleanup(expectation)
        redisEvidence.preCleanup = {status: 'PASS', ...result}
        redisEvidence.note = '本轮 pre-cleanup 已直读；等待 post-cleanup 复核后才能声明核心脚本直验完成。'
    } catch (error) {
        redisEvidence.status = 'FAIL'
        redisEvidence.preCleanup.status = 'FAIL'
        redisEvidence.preCleanup.error = errorDetail(error)
        throw error
    }
}

async function runRedisPostCleanup(expectation) {
    if (!redisVerificationEnabled || redisEvidence.preCleanup.status !== 'PASS') return
    redisEvidence.postCleanup.status = 'RUNNING'
    try {
        const result = await verifyRedisPostCleanup(expectation)
        redisEvidence.postCleanup = {status: 'PASS', ...result}
        redisEvidence.status = 'PASS'
        redisEvidence.note = '本轮真实 redis-cli 定向只读及 post-cleanup 均已执行；不代表 B 套件或 Cluster/多实例验收。'
    } catch (error) {
        redisEvidence.status = 'FAIL'
        redisEvidence.postCleanup.status = 'FAIL'
        redisEvidence.postCleanup.error = errorDetail(error)
        console.error('Redis post-cleanup verification failed:', errorDetail(error))
    } finally {
        redisEvidence.directlyVerifiedByCoreCoverage = redisEvidence.status === 'PASS'
    }
}

function parseSseBlock(block) {
    const data = block.split(/\r?\n/)
        .filter((line) => line.startsWith('data:'))
        .map((line) => `${line.slice(5).trimStart()}\n`)
        .join('')
    if (data === '') return null
    try {
        return JSON.parse(data)
    } catch {
        return null
    }
}

async function settleRunAfterSseError(runId, lastSeq, error, scopeErrors = coreCleanupErrors) {
    if (!runId) {
        const detail = 'runId=unknown:SSE cleanup ownership unknown'
        cleanupLog.push(detail)
        cleanupErrors.push(detail)
        scopeErrors.push(detail)
        return false
    }
    activeRuns.add(runId)
    try {
        const response = await request(`/api/ai/cancel/${runId}`, {method: 'POST'})
        assert(response.status >= 200 && response.status < 300, `SSE error cancel ${response.status}`)
        const data = dataObject(response)
        if (data.alreadyTerminal === true && data.cancelled === false
            && ['DONE', 'FAILED'].includes(data.statusBeforeCancel)) {
            activeRuns.delete(runId)
            cleanupLog.push(`sse-error-cancel:${runId}=ok`)
            if (error !== null && (typeof error === 'object' || typeof error === 'function')) {
                safelyRecoveredModelErrors.add(error)
            }
            return true
        }
        assert(data.cancelled === true, `SSE error cancel not accepted: HTTP ${response.status}`)
        const terminal = await readSseRaw(runId, lastSeq, (frame) => frame.type === 'done' || frame.type === 'error', false, scopeErrors)
        assertHealthyTerminal(terminal.matched, {runId, cancelled: true})
        activeRuns.delete(runId)
        cleanupLog.push(`sse-error-cancel:${runId}=ok`)
        if (error !== null && (typeof error === 'object' || typeof error === 'function')) {
            safelyRecoveredModelErrors.add(error)
        }
        return true
    } catch (cleanupError) {
        console.error('SSE error cleanup failed:', cleanupError)
        const detail = `sse-error-cancel:${runId}=${errorDetail(cleanupError)}`
        cleanupLog.push(detail)
        cleanupErrors.push(detail)
        scopeErrors.push(detail)
        return false
    }
}

async function readSseRaw(runId, lastSeq, predicate, recoverOnError = true, scopeErrors = coreCleanupErrors,
                          eofRetry = 0, drainToEof = false) {
    const started = Date.now()
    let reader = null
    let previousSeq = lastSeq ?? 0
    let recoverySeq = previousSeq
    let matched = null
    let terminalSeen = false
    let httpStatus = null
    let cursor = lastSeq
    let remainingEofRetries = Number.isInteger(eofRetry) && eofRetry > 0 ? eofRetry : 0
    const frames = []
    const shouldRead = () => matched === null || drainToEof
    try {
        while (shouldRead()) {
            let buffer = ''
            const response = await fetch(`${base.toString().replace(/\/$/, '')}/api/ai/events/${runId}${cursor == null ? '' : `?lastSeq=${cursor}`}`, {
                headers: {...headers(), Accept: 'text/event-stream'},
                signal: AbortSignal.timeout(timeoutMs)
            })
            httpStatus = response.status
            const contentType = response.headers.get('content-type') ?? ''
            assert(response.status === 200 && contentType.includes('text/event-stream'),
                `SSE expected 200 text/event-stream, got ${response.status} ${contentType}`)
            reader = response.body.getReader()
            const decoder = new TextDecoder()
            const processFrame = (frame) => {
                runId = frame.runId ?? runId
                if (runId !== null) activeRuns.add(runId)
                if (frame.type === 'done' || frame.type === 'error') terminalSeen = true
                if (typeof frame.seq === 'number') {
                    assert(frame.seq > previousSeq, `SSE seq must increase: ${frame.seq} <= ${previousSeq}`)
                    previousSeq = frame.seq
                }
                if (recoverOnError) trackCreatedTaskFrame(frame)
                frames.push(frame)
                if (matched === null && predicate(frame)) matched = frame
                recoverySeq = previousSeq
            }
            const processBufferedFrames = () => {
                let boundary
                while ((boundary = buffer.search(/\r?\n\r?\n/)) >= 0 && shouldRead()) {
                    const block = buffer.slice(0, boundary)
                    buffer = buffer.slice(boundary).replace(/^\r?\n/, '')
                    const frame = parseSseBlock(block)
                    if (frame !== null) processFrame(frame)
                }
            }
            while (shouldRead()) {
                const {done, value} = await reader.read()
                if (done) break
                buffer += decoder.decode(value, {stream: true})
                processBufferedFrames()
            }
            buffer += decoder.decode()
            processBufferedFrames()
            if (shouldRead() && buffer.trim() !== '') {
                const frame = parseSseBlock(buffer)
                buffer = ''
                if (frame !== null) processFrame(frame)
            }
            await reader.cancel().catch(() => {
            })
            reader = null
            if (matched !== null) break
            if (!shouldRetrySseEof(matched, remainingEofRetries, terminalSeen)) break
            remainingEofRetries -= 1
            cursor = recoverySeq
            await sleep(SSE_EOF_RETRY_DELAY_MS)
        }
        assert(matched !== null,
            `SSE predicate not reached; lastSeq=${recoverySeq}; frames=${formatSseFrames(frames)}`)
        return {frames, matched, lastSeq: previousSeq, latencyMs: Date.now() - started, drainedToEof: drainToEof}
    } catch (error) {
        if (httpStatus !== null && error !== null && (typeof error === 'object' || typeof error === 'function')
            && error.httpStatus === undefined) {
            error.httpStatus = httpStatus
        }
        if (recoverOnError) {
            tripModelCircuit(error, runId ? 'sse-failed' : 'sse-unknown')
            await settleRunAfterSseError(runId, recoverySeq, error, scopeErrors)
        }
        throw error
    } finally {
        if (reader !== null) await reader.cancel().catch(() => {
        })
    }
}

async function startChat(sessionId, message, context, predicate, awaitTerminal = false, recordCoverage = true,
                         scopeErrors = coreCleanupErrors, params = null, allowPlainTextRetry = false) {
    const started = Date.now()
    return modelCall(async () => {
        let chat
        try {
            chat = await readSseChat(sessionId, message, context, predicate, true, scopeErrors,
                params, allowPlainTextRetry)
        } catch (error) {
            if (recordCoverage && !(allowPlainTextRetry && error instanceof PlainTextModelResponseError)) {
                record('POST /api/ai/chat', {
                    httpStatus: null,
                    latencyMs: Date.now() - started,
                    assertions: [],
                    error: errorDetail(error)
                })
            }
            throw error
        }
        if (!awaitTerminal) return chat
        const terminal = await callSse(chat.runId, chat.lastSeq, (frame) => {
            if (frame.type !== 'done' && frame.type !== 'error') return false
            assertHealthyTerminal(frame, {runId: chat.runId})
            return true
        }, true, scopeErrors, 'model-terminal')
        return {...chat, terminal: terminal.matched, terminalLatencyMs: terminal.latencyMs}
    }, allowPlainTextRetry)
}

async function readSseChat(sessionId, message, context, predicate, recoverOnError = true,
                           scopeErrors = coreCleanupErrors, params = null, allowPlainTextRetry = false) {
    const started = Date.now()
    const body = {
        sessionId, message, context, clientRequestId: `${runPrefix}-${randomUUID()}`,
        ...(params === null ? {} : {params})
    }
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
        if (response.status !== 200 || !contentType.includes('text/event-stream')) {
            throw new CoverageError(`chat expected SSE, got ${response.status} ${contentType}`)
        }
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
            if (frame.type === 'done' || frame.type === 'error') assertHealthyTerminal(frame, {runId})
            frames.push(frame)
            if (predicate(frame)) matched = frame
            recoverySeq = previousSeq
            if (allowPlainTextRetry && matched === null && frame.type === 'done' && frame.cancelled !== true
                && frames.every((item) => !['tool_start', 'tool_result', 'confirm_request', 'frontend_tool_request'].includes(item.type))) {
                throw new PlainTextModelResponseError('model returned normal done without tool activity before predicate matched')
            }
        }
        while (matched === null) {
            const {done, value} = await reader.read()
            if (done) break
            buffer += decoder.decode(value, {stream: true})
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
        return {runId, frames, matched, lastSeq: previousSeq, latencyMs: Date.now() - started}
    } catch (error) {
        if (recoverOnError) {
            if (!allowPlainTextRetry || !(error instanceof PlainTextModelResponseError)) {
                tripModelCircuit(error, runId ? 'sse-failed' : 'sse-unknown')
            }
            await settleRunAfterSseError(runId, recoverySeq, error, scopeErrors)
        }
        throw error
    } finally {
        if (reader !== null) await reader.cancel().catch(() => {
        })
    }
}

async function callSse(runId, lastSeq, predicate, recordResult = true, scopeErrors = coreCleanupErrors,
                       stage = 'unspecified', drainToEof = false, recoverOnError = true) {
    const started = Date.now()
    try {
        const result = await readSseRaw(runId, lastSeq, predicate, recoverOnError, scopeErrors,
            SSE_EOF_RETRY_ATTEMPTS, drainToEof)
        if (recordResult) {
            record('GET /api/ai/events/{runId}', {
                httpStatus: 200,
                latencyMs: result.latencyMs,
                assertions: [`SSE ${result.matched.type}`, ...(drainToEof ? [`batch drained to EOF at seq=${result.lastSeq}`] : [])],
                stage
            })
        }
        return result
    } catch (error) {
        if (recordResult) {
            record('GET /api/ai/events/{runId}', {
                httpStatus: error?.httpStatus ?? null,
                latencyMs: Date.now() - started,
                assertions: [],
                error: `${stage}: ${errorDetail(error)}`,
                stage
            })
        }
        throw error
    }
}

async function waitForCancelStatus(runId, evidence) {
    const probe = evidence.statusProbe
    let lastObservation = null
    for (let attempt = 1; attempt <= CANCEL_STATUS_POLL_ATTEMPTS; attempt += 1) {
        const started = Date.now()
        let response = null
        try {
            response = await request(`/api/ai/events/${runId}?probe=1`, {headers: {Accept: 'application/json'}})
            const data = dataObject(response)
            assert([200, 202, 503].includes(response.status), `cancel status probe unexpected HTTP ${response.status}`)
            if (response.status === 200) {
                assert(data.ready === true && !response.contentType.includes('text/event-stream'),
                    'cancel status probe did not return ready JSON')
            }
            if (response.status === 202) assert(data.preparing === true, 'cancel status probe 202 was not preparing')
            if (response.status === 503) assert(data.degraded === true, 'cancel status probe 503 was not marked degraded')
            const degraded = response.status === 503 || data.degraded === true
            const observation = {
                attempt,
                httpStatus: response.status,
                latencyMs: response.latencyMs,
                status: data.status ?? null,
                ready: data.ready ?? null,
                preparing: data.preparing ?? null,
                degraded
            }
            lastObservation = observation
            probe.trajectory.push(observation)
            probe.degraded = probe.degraded || degraded
            const outcome = classifyCancelProbe(observation)
            probe.outcome = outcome
            record('GET /api/ai/events/{runId}', {
                httpStatus: response.status,
                latencyMs: response.latencyMs,
                assertions: [`cancel probe ${attempt}: ${outcome}; status=${observation.status ?? 'unknown'}; degraded=${degraded}`],
                stage: 'after-cancel-status-probe'
            })
            if (outcome === 'CANCELLED' || outcome === 'OTHER_TERMINAL') {
                probe.finalStatus = observation.status
                evidence.runStatus = observation.status
                return observation
            }
        } catch (error) {
            const message = errorDetail(error)
            probe.error = message
            record('GET /api/ai/events/{runId}', {
                httpStatus: response?.status ?? null,
                latencyMs: response?.latencyMs ?? Date.now() - started,
                assertions: [],
                error: `after-cancel-status-probe: ${message}`,
                stage: 'after-cancel-status-probe'
            })
            throw error
        }
        if (attempt < CANCEL_STATUS_POLL_ATTEMPTS) await sleep(CANCEL_STATUS_POLL_INTERVAL_MS)
    }
    probe.timedOut = true
    probe.outcome = classifyCancelProbe({...lastObservation, attemptsExhausted: true})
    if (lastObservation?.status === 'SUSPENDED' && evidence.redisFlagWritten === true) {
        throw new CoverageError(`cancel accepted but not driven: status remained SUSPENDED after ${CANCEL_STATUS_POLL_ATTEMPTS} events probes with redisFlagWritten=true; done{cancelled:true} was not observed`)
    }
    if (probe.outcome === 'DEGRADED') {
        throw new CoverageError(`cancel status probe degraded after ${CANCEL_STATUS_POLL_ATTEMPTS} attempts; no terminal status confirmed`)
    }
    throw new CoverageError(`cancel status probe timed out after ${CANCEL_STATUS_POLL_ATTEMPTS} attempts; last status=${lastObservation?.status ?? 'unknown'}`)
}

function formOf(frame) {
    if (frame.name !== 'generative_form') return null
    try {
        return JSON.parse(frame.args ?? '{}').form ?? null
    } catch {
        return null
    }
}

function trackCreatedTaskFrame(frame) {
    if (frame.type !== 'tool_result' || frame.name !== 'create_task' || frame.outcome !== 'ok') return
    const taskId = taskIdFrom([frame])
    if (taskId === null) {
        throw new CoverageError('successful create_task result has no positive safe-integer task id (number or decimal string); cleanup ownership unknown')
    }
    createdTasks.add(taskId)
}

function normalizeTaskId(value) {
    if (typeof value === 'number') {
        return Number.isSafeInteger(value) && value > 0 ? String(value) : null
    }
    if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value)) return null
    const numeric = Number(value)
    return Number.isSafeInteger(numeric) && numeric > 0 ? String(numeric) : null
}

function taskIdFrom(frames) {
    for (const frame of frames) {
        if (frame.type === 'tool_result' && frame.name === 'create_task') {
            const structured = normalizeTaskId(frame.createdTaskId ?? frame.data?.createdTaskId)
            if (structured !== null) return structured
            const match = /任务\s*#(\d+)/.exec(String(frame.result ?? ''))
            if (match) return normalizeTaskId(match[1])
        }
    }
    return null
}

async function cleanupOne(kind, id, fn, scopeErrors = coreCleanupErrors) {
    try {
        await fn()
        cleanupLog.push(`${kind}=${id}:ok`)
    } catch (error) {
        console.error(`Cleanup failed for ${kind}=${id}:`, error)
        const detail = `${kind}=${id}:${errorDetail(error)}`
        cleanupLog.push(detail)
        cleanupErrors.push(detail)
        scopeErrors.push(detail)
    }
}

function ensureLoadEntry(plan) {
    const existing = apiLoad.endpoints[plan.key]
    if (existing !== undefined) return existing
    const entry = {
        route: plan.route,
        variant: plan.variant,
        scenario: plan.scenario,
        modelGate: plan.modelGate === true,
        serial: plan.serial === true,
        configured: apiLoadConfig.requestsPerEndpoint,
        requested: 0,
        completed: 0,
        failed: 0,
        skipped: 0,
        status: 'PENDING',
        latencyMs: null,
        httpStatuses: {},
        errors: [],
        durationMs: null,
        notes: plan.note ? [plan.note] : []
    }
    apiLoad.endpoints[plan.key] = entry
    return entry
}

function attachHttpStatus(error, status) {
    if (status !== null && error !== null && (typeof error === 'object' || typeof error === 'function')
        && error.httpStatus === undefined) {
        error.httpStatus = status
    }
    return error
}

function limitedMessage(message) {
    const text = String(message)
    return text.length > 500 ? `${text.slice(0, 500)}...` : text
}

function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms))
}

function httpUnit(url, options, assertion) {
    return async () => {
        const response = await request(url, options)
        try {
            await assertion(response)
        } catch (error) {
            throw attachHttpStatus(error, response.status)
        }
        return {status: response.status, latencyMs: response.latencyMs}
    }
}

async function runLoadScenario(runner, plan, unit, overrides = {}) {
    const entry = ensureLoadEntry(plan)
    const startedAt = Date.now()
    const configured = overrides.configured ?? entry.configured
    entry.configured = configured
    entry.skipped = overrides.skipped ?? 0
    entry.requested = 0
    entry.completed = 0
    entry.failed = 0
    entry.latencyMs = summarizeLatencies([])
    entry.httpStatuses = {}
    entry.errors = []
    if (configured === 0) {
        entry.durationMs = 0
        entry.status = 'SKIPPED'
        return entry
    }
    const attempt = (index) => runner.run(async () => {
        entry.requested += 1
        try {
            const result = await unit(index)
            return {
                ok: true,
                status: result?.status ?? null,
                latencyMs: Number.isFinite(result?.latencyMs) ? result.latencyMs : null
            }
        } catch (error) {
            console.error(`API load attempt failed for ${plan.key} #${index}:`, error)
            return {
                ok: false,
                status: error?.httpStatus ?? null,
                latencyMs: null,
                message: limitedMessage(errorDetail(error))
            }
        }
    })
    let attempts
    if (entry.serial) {
        attempts = []
        for (let index = 0; index < configured; index += 1) {
            try {
                attempts.push({status: 'fulfilled', value: await attempt(index)})
            } catch (reason) {
                attempts.push({status: 'rejected', reason})
            }
        }
    } else {
        attempts = await Promise.allSettled(Array.from({length: configured}, (_, index) => attempt(index)))
    }
    const samples = []
    attempts.forEach((settled, index) => {
        if (settled.status === 'rejected') {
            console.error(`API load attempt rejected for ${plan.key} #${index}:`, settled.reason)
            entry.failed += 1
            entry.errors.push({
                index,
                httpStatus: null,
                message: limitedMessage(errorDetail(settled.reason))
            })
            return
        }
        const attempt = settled.value
        if (attempt.status !== null) {
            const code = String(attempt.status)
            entry.httpStatuses[code] = (entry.httpStatuses[code] ?? 0) + 1
        }
        if (attempt.ok) {
            entry.completed += 1
            if (attempt.latencyMs !== null) samples.push(attempt.latencyMs)
        } else {
            entry.failed += 1
            entry.errors.push({index, httpStatus: attempt.status, message: attempt.message})
        }
    })
    entry.latencyMs = summarizeLatencies(samples)
    entry.durationMs = Date.now() - startedAt
    entry.status = entry.requested === entry.configured
    && entry.completed === entry.configured
    && entry.failed === 0
        ? 'PASS'
        : 'FAIL'
    return entry
}

function buildLoadUnits({sessionId, runId, historyExpectation}) {
    const units = new Map()

    units.set('GET /api/ai/health', httpUnit('/api/ai/health', {}, (response) => {
        const data = dataObject(response)
        assert([200, 503].includes(response.status), `health unexpected ${response.status}`)
        assert(typeof data.code === 'string' && data.code.length > 0, 'health code missing')
    }))
    units.set('GET /api/ai/tools', httpUnit('/api/ai/tools', {}, (response) => {
        ok(response)
        const data = dataObject(response)
        assert(Array.isArray(data.toolNames) && data.toolNames.includes('create_task'), 'create_task not disclosed')
    }))
    units.set('GET /api/ai/tenants', httpUnit('/api/ai/tenants', {}, (response) => {
        ok(response)
        const data = dataArray(response)
        assert(data.some((tenant) => String(tenant.tenantId ?? tenant.id) === tenantId), 'current tenant not in directory')
    }))
    units.set('GET /api/ai/kb/status', httpUnit('/api/ai/kb/status', {}, (response) => {
        ok(response)
        const data = dataObject(response)
        assert(typeof data.enabled === 'boolean' && typeof data.ready === 'boolean', 'KB enabled/ready missing')
    }))
    units.set('GET /api/ai/kb/docs', httpUnit('/api/ai/kb/docs', {}, (response) => {
        ok(response)
        const data = dataArray(response)
        assert(data.every((doc) => typeof doc.id === 'string' && typeof doc.title === 'string'), 'KB doc shape invalid')
    }))
    units.set('GET /api/ai/form/option-sources/{sourceId}',
        httpUnit('/api/ai/form/option-sources/definitions?value=GLOBAL', {}, (response) => {
            ok(response)
            const data = dataObject(response)
            assert(data.sourceId === 'definitions' && Array.isArray(data.options) && data.options.length > 0,
                'definition option source is empty')
        }))
    units.set('GET /api/ai/runs', httpUnit('/api/ai/runs', {}, (response) => {
        ok(response)
        const data = dataArray(response)
        assert(data.some((run) => run.runId === runId), 'run list lacks the shared terminal run')
    }))
    units.set('GET /api/ai/runs/{runId}', httpUnit(`/api/ai/runs/${runId}`, {}, (response) => {
        ok(response)
        const data = dataObject(response)
        assert(data.exists === true && data.snapshot?.runId === runId, 'run detail missing for the shared terminal run')
        assert(data.snapshot.sessionId === sessionId && data.snapshot.status === 'CANCELLED',
            'shared terminal run ownership/final status mismatch')
    }))
    units.set('GET /api/ai/runs/current',
        httpUnit(`/api/ai/runs/current?sessionId=${encodeURIComponent(sessionId)}`, {}, (response) => {
            ok(response)
            const data = dataObject(response)
            assert(data.found === false, 'terminal cancel should clear the session current index')
        }))
    units.set('GET /api/ai/history/{sessionId}',
        httpUnit(`/api/ai/history/${sessionId}?latest=100`, {}, (response) => {
            ok(response)
            const data = dataObject(response)
            assert(data.sessionId === sessionId && data.hasMore !== true, 'terminal history must be one complete bounded page')
            assertMainTerminalHistory(data.messages, historyExpectation, 'API load history')
        }))
    units.set('GET /api/ai/context/{sessionId}', httpUnit(`/api/ai/context/${sessionId}`, {}, (response) => {
        ok(response)
        const data = dataObject(response)
        assert(data.sessionId === sessionId, 'context sessionId mismatch')
    }))
    units.set('GET /api/ai/pending/{runId}', httpUnit(`/api/ai/pending/${runId}`, {}, (response) => {
        ok(response)
        const data = dataArray(response)
        assert(data.every((pending) => typeof pending.toolCallId === 'string'), 'pending entry shape invalid')
    }))
    units.set('GET /api/ai/feedback', httpUnit('/api/ai/feedback', {}, (response) => {
        ok(response)
        dataArray(response)
    }))
    units.set('GET /api/ai/cancel/{runId}', httpUnit(`/api/ai/cancel/${runId}`, {}, (response) => {
        ok(response)
        const data = dataObject(response)
        assert(data.redisFlag === true || data.localFlag === true, 'cancel flag missing on the terminal run')
    }))
    units.set('GET /api/ai/startup-resume', httpUnit('/api/ai/startup-resume', {}, (response) => {
        ok(response)
        const data = dataObject(response)
        assert(typeof data.scanned === 'boolean' || typeof data.reason === 'string', 'startup scan status missing')
    }))
    units.set('GET /api/ai/events/{runId} [probe=1]',
        httpUnit(`/api/ai/events/${runId}?probe=1`, {headers: {Accept: 'application/json'}}, (response) => {
            ok(response)
            const data = dataObject(response)
            assert(data.ready === true && !response.contentType.includes('text/event-stream'),
                'events probe did not return ready JSON')
            assert(data.status === 'CANCELLED', `shared terminal run status was ${data.status ?? 'unknown'}`)
        }))
    units.set('GET /api/ai/events/{runId} [sse-replay]', async () => {
        try {
            const result = await readSseRaw(runId, 0, (frame) => {
                if (frame.type !== 'done' && frame.type !== 'error') return false
                assertHealthyTerminal(frame, {runId, cancelled: true})
                return true
            }, false)
            return {status: 200, latencyMs: result.latencyMs}
        } finally {
            activeRuns.delete(runId)
        }
    })
    units.set('POST /api/ai/cancel/{runId} [terminal-idempotent]',
        httpUnit(`/api/ai/cancel/${runId}`, {method: 'POST'}, (response) => {
            ok(response)
            const data = dataObject(response)
            assert(data.cancelled === true, 'terminal cancel did not report cancelled=true')
            assert(data.alreadyCancelled === true, 'terminal cancel did not report alreadyCancelled=true')
        }))
    units.set('POST /api/ai/runs/{runId}/resume [terminal-already-cancelled]',
        httpUnit(`/api/ai/runs/${runId}/resume`, {method: 'POST'}, (response) => {
            ok(response)
            const data = dataObject(response)
            assert(data.resumed === false, 'terminal resume unexpectedly resumed a cancelled run')
            assert(data.reason === 'ALREADY_CANCELLED', 'unexpected terminal resume outcome')
        }))
    units.set('POST /api/ai/cancel/client-request', async (index) => {
        const loadSession = `${runPrefix}-load-client-cancel-${index}-${randomUUID()}`
        createdSessions.add(loadSession)
        loadCreatedSessions.add(loadSession)
        const clientRequestId = `${runPrefix}-load-client-request-${index}-${randomUUID()}`
        const response = await request('/api/ai/cancel/client-request', {
            method: 'POST',
            json: true,
            body: JSON.stringify({sessionId: loadSession, clientRequestId})
        })
        try {
            ok(response)
            const data = dataObject(response)
            assert(data.cancelled === true && data.pendingRun === true, 'clientRequestId pre-cancel tombstone missing')
            assert(data.clientRequestId === clientRequestId, 'clientRequestId echo mismatch')
        } catch (error) {
            throw attachHttpStatus(error, response.status)
        }
        return {status: response.status, latencyMs: response.latencyMs}
    })
    units.set('POST /api/ai/feedback', async (index) => {
        const messageId = boundedId(`${runPrefix}-load-feedback-${index}-${randomUUID()}`, 64)
        const response = await request('/api/ai/feedback', {
            method: 'POST',
            json: true,
            body: JSON.stringify({messageId, sessionId, rating: 1, reason: 'api load'})
        })
        try {
            ok(response)
            assert(dataObject(response).saved === true, 'feedback was not saved')
            loadFeedbackMessageIds.add(messageId)
        } catch (error) {
            throw attachHttpStatus(error, response.status)
        }
        return {status: response.status, latencyMs: response.latencyMs}
    })
    units.set('POST /api/ai/form/attachments', async () => {
        let upload = null
        try {
            await attachmentWorkflow({
                recordCoverage: false,
                onSession: (loadSession) => loadCreatedSessions.add(loadSession),
                onUpload: (info) => {
                    upload = info
                }
            })
        } catch (error) {
            throw attachHttpStatus(error, upload?.status ?? null)
        }
        assert(upload !== null && upload.ok === true, 'independent attachment workflow produced no upload evidence')
        return {status: upload.status, latencyMs: upload.latencyMs}
    })
    units.set('POST /api/ai/session', async () => {
        const response = await request('/api/ai/session', {method: 'POST', json: true})
        try {
            ok(response)
            const data = dataObject(response)
            assert(typeof data.sessionIdText === 'string' && data.sessionIdText.length > 0, 'sessionIdText missing')
            loadSessionIds.push(data.sessionIdText)
            createdSessions.add(data.sessionIdText)
            loadCreatedSessions.add(data.sessionIdText)
        } catch (error) {
            throw attachHttpStatus(error, response.status)
        }
        return {status: response.status, latencyMs: response.latencyMs}
    })
    units.set('DELETE /api/ai/session/{sessionId}', async (index) => {
        const loadSession = loadSessionIds[index]
        assert(typeof loadSession === 'string' && loadSession.length > 0,
            'no successfully created session is available for the paired DELETE load')
        const response = await request(`/api/ai/session/${loadSession}`, {method: 'DELETE'})
        try {
            ok(response)
            const data = dataObject(response)
            const deferred = Array.isArray(data.deferred) ? data.deferred : []
            const errors = Array.isArray(data.errors) ? data.errors : []
            assert(data.complete === true && deferred.length === 0 && errors.length === 0,
                `session purge incomplete: HTTP ${response.status}`)
            createdSessions.delete(loadSession)
            loadCreatedSessions.delete(loadSession)
        } catch (error) {
            throw attachHttpStatus(error, response.status)
        }
        return {status: response.status, latencyMs: response.latencyMs}
    })

    return units
}

async function runApiLoadStage({sessionId, runId, historyExpectation}) {
    if (!apiLoad.enabled) return
    apiLoad.ran = true
    apiLoad.status = 'RUNNING'
    loadBaselines.captured = true
    loadBaselines.tasks = new Set(createdTasks)
    loadBaselines.runs = new Set(activeRuns)
    const runner = createLoadRunner({concurrency: apiLoadConfig.concurrency})
    try {
        const units = buildLoadUnits({sessionId, runId, historyExpectation})
        for (const plan of API_LOAD_PLAN) {
            const entry = ensureLoadEntry(plan)
            const unit = units.get(plan.key)
            if (unit === undefined) {
                entry.status = 'FAIL'
                entry.failed = entry.configured
                entry.errors.push({index: null, httpStatus: null, message: 'no load unit registered for plan entry'})
                continue
            }
            const overrides = plan.pairedDeletes === true
                ? (() => {
                    const planned = planPairedDeletes(loadSessionIds, apiLoadConfig.requestsPerEndpoint)
                    return {configured: planned.configured, skipped: planned.skipped}
                })()
                : undefined
            await runLoadScenario(runner, plan, unit, overrides)
        }
    } catch (error) {
        console.error('API load stage failed:', error)
        apiLoad.fatalError = limitedMessage(errorDetail(error))
        apiLoad.notes.push('load stage aborted before every planned entry finished')
    } finally {
        apiLoad.maxObservedApiInFlight = runner.snapshot().maxObserved
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

async function attachmentWorkflow(options = {}) {
    const {recordCoverage = true, onSession = null, onUpload = null} = options
    const cleanupScopeErrors = recordCoverage ? coreCleanupErrors : apiLoad.cleanupErrors
    let attachmentSession = null
    let attachmentChat = null
    for (let attempt = 0; attempt < 2; attempt += 1) {
        const firstAttempt = attempt === 0
        attachmentSession = `${runPrefix}-attachment-${randomUUID()}`
        createdSessions.add(attachmentSession)
        onSession?.(attachmentSession)
        try {
            attachmentChat = await startChat(attachmentSession,
                '请调用 generative_form 工具，不要只回复文字，也不要调用其它工具。工具入参必须且只能是：'
                + '{"form":{"scenario":"CLARIFY","purpose":"GENERAL_CLARIFY","title":"上传文本附件","fields":[{"key":"evidence","label":"文本附件","type":"attachment","required":true}]}}',
                null,
                (frame) => {
                    const form = formOf(frame)
                    return form !== null && Array.isArray(form.fields) && form.fields.some((field) => field.type === 'attachment')
                },
                false,
                recordCoverage,
                cleanupScopeErrors,
                {temperature: 0},
                firstAttempt)
        } catch (error) {
            if (!firstAttempt || !(error instanceof PlainTextModelResponseError)) throw error
            plainTextRetryCount += 1
        }
        if (attachmentChat !== null) break
    }
    const form = formOf(attachmentChat.matched)
    const attachmentField = form.fields.find((field) => field.type === 'attachment')
    assert(attachmentField?.key, 'attachment field key missing')
    if (recordCoverage) {
        record('POST /api/ai/chat', {
            httpStatus: 200,
            latencyMs: attachmentChat.latencyMs,
            assertions: ['real generative_form with attachment field pending']
        })
    }
    const formData = new FormData()
    formData.append('file', new Blob([`core coverage ${attachmentSession}\n`], {type: 'text/plain'}), 'core-evidence.txt')
    formData.append('runId', attachmentChat.runId)
    formData.append('toolCallId', attachmentChat.matched.toolCallId)
    formData.append('sessionId', attachmentSession)
    formData.append('fieldKey', attachmentField.key)
    let uploaded
    try {
        uploaded = await call('POST /api/ai/form/attachments', '/api/ai/form/attachments', {
            method: 'POST',
            body: formData
        }, async (response) => {
            ok(response)
            const data = dataObject(response)
            assert(typeof data.fileId === 'string' && data.fileId.length > 0, 'attachment fileId missing')
            return `uploaded ${data.fileId}`
        }, recordCoverage)
    } catch (error) {
        onUpload?.({ok: false, status: error?.httpStatus ?? null, latencyMs: null})
        throw error
    }
    onUpload?.({ok: true, status: uploaded.status, latencyMs: uploaded.latencyMs})
    const fileId = dataObject(uploaded).fileId
    const {terminal} = await modelCall(async () => {
        const submitted = await call('POST /api/ai/frontend-tool-result', '/api/ai/frontend-tool-result', {
            method: 'POST',
            json: true,
            body: JSON.stringify({
                runId: attachmentChat.runId,
                toolCallId: attachmentChat.matched.toolCallId,
                result: JSON.stringify({[attachmentField.key]: fileId}),
                source: 'http-post', outcome: 'ok'
            })
        }, async (response) => {
            ok(response)
            assert(dataObject(response).accepted === true, 'attachment form result not accepted')
            return 'real attachment form result accepted'
        }, recordCoverage)
        const terminal = await callSse(attachmentChat.runId, attachmentChat.lastSeq, (frame) => {
            if (frame.type !== 'done' && frame.type !== 'error') return false
            assertHealthyTerminal(frame, {runId: attachmentChat.runId})
            return true
        }, recordCoverage, cleanupScopeErrors, 'attachment-terminal')
        if (recordCoverage) {
            record('POST /api/ai/chat', {
                httpStatus: 200,
                latencyMs: terminal.latencyMs,
                assertions: ['attachment scenario done terminal confirmed']
            })
        }
        return {submitted, terminal}
    })
    activeRuns.delete(attachmentChat.runId)
}

async function independentModelWorker(workflow) {
    try {
        return await workflow()
    } catch (error) {
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
const feedbackMessageId = boundedId(`${runPrefix}-feedback-${randomUUID()}`, 64)
const cancelEvidence = {
    runId: null,
    httpStatus: null,
    statusBeforeCancel: null,
    parkedResume: null,
    cancelFinalize: null,
    redisFlagWritten: null,
    alreadyCancelled: null,
    degraded: null,
    suspendedBatch: {matched: null, frames: [], lastSeq: null, drainedToEof: false},
    statusProbe: {
        maxAttempts: CANCEL_STATUS_POLL_ATTEMPTS,
        intervalMs: CANCEL_STATUS_POLL_INTERVAL_MS,
        trajectory: [],
        outcome: 'NOT_RUN',
        finalStatus: null,
        degraded: false,
        timedOut: false,
        error: null
    },
    runStatus: null,
    terminalFrame: null,
    terminalFrames: [],
    cancelFlag: null
}
let feedbackCreated = false
let redisExpectation = null

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
        body: JSON.stringify({
            sessionId: clientCancelSession,
            clientRequestId: `${runPrefix}-pre-cancel-${randomUUID()}`
        })
    }, async (response) => {
        ok(response)
        const data = dataObject(response)
        assert(data.cancelled === true && data.pendingRun === true, 'clientRequestId pre-cancel tombstone missing')
        return `clientRequestId pre-cancel ${data.clientRequestId}`
    })

    createdSessions.add(mainSession)
    const mainUserMessage = '请调用 create_task 工具创建导出任务，不要调用 ui_snapshot 或其他工具。'
    const mainChat = await startChat(mainSession, mainUserMessage, null,
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
        assert(data.exists === true && data.snapshot?.runId === mainRun, 'run detail missing')
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

    const replay = await callSse(mainRun, 0, (frame) => frame.type === 'confirm_request' && frame.name === 'create_task',
        true, coreCleanupErrors, 'replay-confirm')
    record('GET /api/ai/events/{runId}', {
        httpStatus: 200,
        latencyMs: replay.latencyMs,
        assertions: ['real SSE replay reached create_task confirm', 'replay seq strictly increasing']
    })
    await call('GET /api/ai/events/{runId}', `/api/ai/events/${mainRun}?probe=1`, {
        headers: {Accept: 'application/json'}
    }, async (response) => {
        ok(response)
        const data = dataObject(response)
        assert(data.ready === true && !response.contentType.includes('text/event-stream'), 'events probe did not return ready JSON')
        return 'events probe=1 ready JSON'
    })
    await call('GET /api/ai/events/{runId}', `/api/ai/events/${mainRun}`, {
        headers: {Accept: 'application/json'}
    }, async (response) => {
        assert(response.status === 406, `events cross representation expected 406, got ${response.status}`)
        return 'events without probe and Accept:application/json is 406'
    })

    const confirmed = await modelCall(async () => {
        const approval = await call('POST /api/ai/confirm', '/api/ai/confirm', {
            method: 'POST',
            json: true,
            body: JSON.stringify({
                runId: mainRun,
                toolCallId: createCallId,
                approved: true,
                reason: 'core coverage approval'
            })
        }, async (response) => {
            ok(response)
            const data = dataObject(response)
            assert(data.accepted === true && data.approved === true, 'create_task approval not accepted')
            return 'real create_task approved'
        })
        return callSse(mainRun, mainChat.lastSeq,
            (frame) => frame.type === 'frontend_tool_request' && frame.name === 'navigate_to',
            true, coreCleanupErrors, 'post-confirm-navigate')
    })
    const taskId = taskIdFrom(confirmed.frames)
    assert(taskId !== null, 'create_task result after approval must carry a positive safe-integer number or decimal string')
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
                    extra: {pageId: 'export', selectedDefs: [], selectionSynced: true, importMode: null}
                }
            })
        }, async (response) => {
            ok(response)
            const data = dataObject(response)
            assert(data.accepted === true && data.status === 'FRONTEND_RESULT', 'navigate_to result not accepted')
            return 'real navigate_to frontend result accepted'
        })
        return callSse(mainRun, confirmed.lastSeq, (frame) => frame.type === 'suspended',
            true, coreCleanupErrors, 'after-frontend-result-suspended', true)
    })
    cancelEvidence.runId = mainRun
    cancelEvidence.suspendedBatch = {
        matched: navigationResponse.matched,
        frames: navigationResponse.frames,
        lastSeq: navigationResponse.lastSeq,
        drainedToEof: navigationResponse.drainedToEof
    }

    await call('POST /api/ai/cancel/{runId}', `/api/ai/cancel/${mainRun}`, {method: 'POST'}, async (response) => {
        ok(response)
        const data = dataObject(response)
        Object.assign(cancelEvidence, {
            httpStatus: response.status,
            statusBeforeCancel: data.statusBeforeCancel ?? null,
            parkedResume: data.parkedResume ?? null,
            cancelFinalize: data.cancelFinalize ?? null,
            redisFlagWritten: data.redisFlagWritten ?? null,
            alreadyCancelled: data.alreadyCancelled ?? null,
            degraded: data.degraded ?? null
        })
        assert(data.cancelled === true, 'run cancellation not accepted')
        assert(data.alreadyCancelled === false, 'first cancellation unexpectedly reported alreadyCancelled=true')
        await waitForCancelStatus(mainRun, cancelEvidence)
        const terminal = await callSse(mainRun, navigationResponse.lastSeq, (frame) => {
            if (frame.type !== 'done' && frame.type !== 'error') return false
            assertHealthyTerminal(frame, {runId: mainRun, cancelled: true})
            return true
        }, true, coreCleanupErrors, 'after-cancel-terminal', false, false)
        cancelEvidence.terminalFrame = terminal.matched
        cancelEvidence.terminalFrames = terminal.frames
        activeRuns.delete(mainRun)
        return `cancelled ${mainRun}; runStatus=${cancelEvidence.runStatus}; done.cancelled=true`
    })
    await call('GET /api/ai/cancel/{runId}', `/api/ai/cancel/${mainRun}`, {}, async (response) => {
        ok(response)
        const data = dataObject(response)
        cancelEvidence.cancelFlag = {
            redisFlag: data.redisFlag ?? null,
            localFlag: data.localFlag ?? null,
            cancelState: data.cancelState ?? null,
            degraded: data.degraded ?? null
        }
        assert(data.redisFlag === true || data.localFlag === true, 'cancel flag missing')
        return `cancel status ${mainRun}`
    })
    await modelCall(() => call('POST /api/ai/runs/{runId}/resume', `/api/ai/runs/${mainRun}/resume`, {method: 'POST'}, async (response) => {
        ok(response)
        const data = dataObject(response)
        assert(data.reason === 'ALREADY_CANCELLED' && data.resumed === false,
            'unexpected terminal resume outcome')
        return `terminal resume idempotent ${data.reason}`
    }))
    await call('GET /api/ai/startup-resume', '/api/ai/startup-resume', {}, async (response) => {
        ok(response)
        const data = dataObject(response)
        assert(typeof data.scanned === 'boolean' || typeof data.reason === 'string', 'startup scan status missing')
        return `startup scanned=${data.scanned ?? data.reason}`
    })
    await modelCall(() => call('POST /api/ai/startup-resume/scan', '/api/ai/startup-resume/scan', {method: 'POST'}, async (response) => {
        ok(response)
        const data = dataObject(response)
        assert(typeof data.scanned === 'boolean' || typeof data.reason === 'string', 'manual startup scan missing result')
        return `manual startup scanned=${data.scanned ?? data.reason}`
    }))

    await call('POST /api/ai/feedback', '/api/ai/feedback', {
        method: 'POST',
        json: true,
        body: JSON.stringify({messageId: feedbackMessageId, sessionId: mainSession, rating: 1, reason: 'core coverage'})
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
    const lifecycle = await call('POST /api/ai/session', '/api/ai/session', {
        method: 'POST',
        json: true
    }, async (response) => {
        ok(response)
        const data = dataObject(response)
        assert(typeof data.sessionIdText === 'string' && data.sessionIdText.length > 0, 'sessionIdText missing')
        return `session created ${data.sessionIdText}`
    })
    const lifecycleSession = dataObject(lifecycle).sessionIdText
    createdSessions.add(lifecycleSession)
    await call('DELETE /api/ai/session/{sessionId}', `/api/ai/session/${lifecycleSession}`, {method: 'DELETE'}, async (response) => {
        ok(response)
        const data = dataObject(response)
        assert(data.complete === true, `session purge incomplete: HTTP ${response.status}`)
        return `session purge complete ${lifecycleSession}`
    })
    createdSessions.delete(lifecycleSession)

    await runIndependentModelScenarios()

    redisExpectation = {
        sessionId: mainSession,
        runId: mainRun,
        userMessage: mainUserMessage,
        createCallId,
        navigationCallId,
        navigationResult,
        taskId,
        terminalFrame: cancelEvidence.terminalFrame,
        assistantText: visibleTextFromFrames([
            ...mainChat.frames,
            ...confirmed.frames,
            ...navigationResponse.frames,
            ...cancelEvidence.terminalFrames
        ])
    }
    await runApiLoadStage({sessionId: mainSession, runId: mainRun, historyExpectation: redisExpectation})
    await runRedisPreCleanup(redisExpectation)
}

let fatalError = null
try {
    await main()
} catch (error) {
    console.error('Core coverage failed:', error)
    fatalError = errorDetail(error)
} finally {
    if (loadBaselines.captured) {
        for (const taskId of createdTasks) {
            if (!loadBaselines.tasks.has(taskId)) loadCreatedTasks.add(taskId)
        }
        for (const runId of activeRuns) {
            if (!loadBaselines.runs.has(runId)) loadActiveRuns.add(runId)
        }
    }
    if (feedbackCreated) {
        await cleanupOne('feedback-rating-zero', feedbackMessageId, async () => {
            const response = await request('/api/ai/feedback', {
                method: 'POST', json: true,
                body: JSON.stringify({messageId: feedbackMessageId, sessionId: mainSession, rating: 0})
            })
            assert(response.status >= 200 && response.status < 300, `feedback cleanup ${response.status}`)
            assert(dataObject(response).cancelled === true, `feedback cleanup not cancelled: HTTP ${response.status}`)
        })
    }
    for (const messageId of loadFeedbackMessageIds) {
        await cleanupOne('load-feedback-rating-zero', messageId, async () => {
            const response = await request('/api/ai/feedback', {
                method: 'POST', json: true,
                body: JSON.stringify({messageId, sessionId: mainSession, rating: 0})
            })
            assert(response.status >= 200 && response.status < 300, `load feedback cleanup ${response.status}`)
            const data = dataObject(response)
            assert(data.saved === false, `load feedback cleanup unexpectedly saved: HTTP ${response.status}`)
            assert(data.cancelled === true, `load feedback cleanup not cancelled: HTTP ${response.status}`)
        }, apiLoad.cleanupErrors)
    }
    for (const runId of activeRuns) {
        await cleanupOne(`cancel-run:${runId}`, runId, async () => {
            const response = await request(`/api/ai/cancel/${runId}`, {method: 'POST'})
            assert(response.status >= 200 && response.status < 300, `cancel cleanup ${response.status}`)
        }, loadActiveRuns.has(runId) ? apiLoad.cleanupErrors : coreCleanupErrors)
    }
    for (const taskId of createdTasks) {
        await cleanupOne(`delete-task:${taskId}`, taskId, async () => {
            const response = await request(`/api/tasks/${taskId}`, {method: 'DELETE'})
            assert([200, 404].includes(response.status), `task cleanup ${response.status}`)
        }, loadCreatedTasks.has(taskId) ? apiLoad.cleanupErrors : coreCleanupErrors)
    }
    for (const sessionId of createdSessions) {
        await cleanupOne(`delete-session:${sessionId}`, sessionId, async () => {
            const response = await request(`/api/ai/session/${sessionId}`, {method: 'DELETE'})
            assert(response.status >= 200 && response.status < 300, `session cleanup ${response.status}`)
            const data = dataObject(response)
            const deferred = Array.isArray(data.deferred) ? data.deferred : []
            const errors = Array.isArray(data.errors) ? data.errors : []
            assert(data.complete === true && deferred.length === 0 && errors.length === 0,
                `session cleanup incomplete: complete=${data.complete} deferred=${deferred.length} errors=${errors.length}`)
        }, loadCreatedSessions.has(sessionId) ? apiLoad.cleanupErrors : coreCleanupErrors)
    }
    if (redisExpectation !== null) await runRedisPostCleanup(redisExpectation)
    const loadEntries = Object.values(apiLoad.endpoints)
    if (!apiLoad.enabled) {
        apiLoad.complete = null
        apiLoad.status = 'NOT_RUN'
    } else if (!apiLoad.ran) {
        apiLoad.complete = false
        apiLoad.status = 'NOT_RUN'
        apiLoad.notes.push('EVAL_ENABLE_API_LOAD=true but the load stage was not reached; NOT_RUN is not PASS')
    } else {
        const everyPlannedRan = API_LOAD_PLAN.every((plan) => apiLoad.endpoints[plan.key] !== undefined)
        const everyEntryPassed = everyPlannedRan && loadEntries.every((entry) => {
            if (entry.status === 'SKIPPED') {
                return entry.configured === 0 && entry.requested === 0 && entry.completed === 0 && entry.failed === 0
            }
            return entry.status === 'PASS'
                && entry.requested === entry.configured
                && entry.completed === entry.configured
                && entry.failed === 0
        })
        apiLoad.complete = everyEntryPassed && apiLoad.fatalError === null && apiLoad.cleanupErrors.length === 0
        apiLoad.status = apiLoad.complete ? 'PASS' : 'FAIL'
    }
    apiLoad.skipped = loadEntries.reduce((total, entry) => total + (entry.skipped ?? 0), 0)
    apiLoad.cleanup = {
        done: apiLoad.cleanupErrors.length === 0,
        detail: !apiLoad.enabled || !apiLoad.ran
            ? 'no load-created resources'
            : apiLoad.cleanupErrors.length === 0
                ? 'load-created resources cleaned'
                : apiLoad.cleanupErrors.join('; ')
    }
    const missing = Object.values(coverage).filter((entry) => !entry.covered || entry.assertions.length === 0)
    const loadComplete = apiLoad.enabled ? apiLoad.complete === true : true
    const httpComplete = missing.length === 0 && cleanupErrors.length === 0 && fatalError === null
        && modelFailure === null && loadComplete
    const complete = httpComplete && redisEvidence.status === 'PASS'
    const verificationVerdict = complete
        ? 'PASS'
        : !httpComplete || redisEvidence.status === 'FAIL' ? 'FAIL' : 'NOT_RUN'
    const notRun = [
        ...missing.map((entry) => `${entry.method} ${entry.path}`),
        ...(redisEvidence.status === 'NOT_RUN' ? ['READ_ONLY Redis pre/post verification'] : [])
    ]
    for (const entry of Object.values(coverage)) {
        entry.cleanup = {
            done: coreCleanupErrors.length === 0,
            detail: coreCleanupErrors.length === 0 ? 'core-created resources cleaned' : coreCleanupErrors.join('; ')
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
        plainTextRetryCount,
        modelCircuitBreaker: {
            failed: modelFailure !== null,
            phase: modelFailure?.phase ?? null,
            error: modelFailure === null ? null : errorDetail(modelFailure.error),
            modelInFlight,
            slotsNotSafelyReleased: modelInFlight
        },
        verificationVerdict,
        statusScope: 'PUBLIC_API_SSE_REDIS_ONLY; business database and broader B-suite/Cluster/multi-instance verdicts remain separate',
        coverage: {
            complete,
            httpComplete,
            expected: Object.keys(coverage).length,
            covered: Object.values(coverage).filter((entry) => entry.covered).length,
            notRun
        },
        endpoints: coverage,
        load: apiLoad,
        cleanup: cleanupLog,
        cleanupErrors,
        coreCleanupErrors,
        fatalError,
        cancelEvidence,
        redisVerification: {
            ...redisEvidence,
            owner: 'core-coverage 本轮定向只读；B 套件（scripts/perf/redis）继续负责其独立验收范围',
            scope: [
                '13 个 run 键及 cancel',
                'session current/runs/active 与 run/recovery 索引',
                'transcript/memory/context/guard/pending',
                'HTTP history 与 Redis transcript 同 run/parts/正文/顺序/结局对照',
                'post-cleanup 残留与有界 TTL'
            ],
            notCovered: ['跨身份不误删', '真实 Redis Cluster', '多实例随机路由', 'B 套件全量场景']
        },
        businessDatabaseVerification: {
            status: 'NOT_RUN',
            directlyVerifiedByCoreCoverage: false,
            reason: '本脚本没有可行的仓外业务数据库只读连接契约；公开任务/反馈 API 证据不冒充数据库直验。',
            aiDataBoundary: '未新增数据库访问；AI 会话及运行数据未读取或写入任何数据库表，既有反馈仅走允许的用户反馈 API。'
        },
        deleteEvidenceDependencies: [
            'scripts/perf/redis/perf_redis/scenarios.py:226-233 (repeat_delete and concurrent_delete)',
            'scripts/perf/redis/perf_redis/verify.py:248-337 (owned keys, guard/pending and gen/ver final state)',
            'scripts/verification/ai-current-protocol-api.mjs:67-87 (repeat delete and empty history)'
        ]
    }
    await mkdir(outDir, {recursive: true})
    await writeFile(path.join(outDir, 'coverage-results.json'), JSON.stringify(report, null, 2) + '\n')
    console.log(JSON.stringify({
        complete,
        verificationVerdict,
        expected: report.coverage.expected,
        covered: report.coverage.covered,
        notRun: report.coverage.notRun,
        modelConcurrencyConfigured,
        maxObservedInFlight,
        redis: {
            status: redisEvidence.status,
            directlyVerifiedByCoreCoverage: redisEvidence.directlyVerifiedByCoreCoverage,
            preCleanup: redisEvidence.preCleanup.status,
            postCleanup: redisEvidence.postCleanup.status
        },
        load: {
            enabled: apiLoad.enabled,
            status: apiLoad.status,
            complete: apiLoad.complete,
            maxObservedApiInFlight: apiLoad.maxObservedApiInFlight
        },
        fatalError,
        output: path.join(outDir, 'coverage-results.json')
    }, null, 2))
    if (!complete) process.exitCode = 1
}
