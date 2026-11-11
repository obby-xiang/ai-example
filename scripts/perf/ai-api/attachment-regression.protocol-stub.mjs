import assert from 'node:assert/strict'
import {spawn} from 'node:child_process'
import {createServer} from 'node:http'
import {existsSync} from 'node:fs'
import {mkdir, readFile, writeFile} from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

const scenario = process.argv[2]
const supported = new Set([
  'healthy-attachment',
  'hitl-continuity',
  'hitl-receipt-timeout',
  'script-timeout-classification',
  'evidence-before-cleanup',
  'load-result-classification',
  'real-error-still-trips',
  'real-circuit-hitl-receipt',
  'cancel-failure-withholds-cleanup',
  'terminal-missing-withholds-cleanup',
  'failed-race-still-trips',
  'unknown-runid-withholds-cleanup'
])
assert(supported.has(scenario), `usage: node attachment-regression.protocol-stub.mjs <${[...supported].join('|')}>`)

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const coreScript = path.join(repoRoot, 'scripts/perf/ai-api/core-coverage.mjs')
const outDir = path.resolve(repoRoot, '../../../../_attachment-probes', `${scenario}-${process.pid}`)
const artifactPath = process.env.PROBE_ARTIFACT?.trim() || path.join(outDir, 'probe-result.json')
const runs = new Map()
const requests = []
const openResponses = new Set()
let chatCount = 0
let serverError = null

function json(response, status, body) {
  response.writeHead(status, {'Content-Type': 'application/json'})
  response.end(JSON.stringify(body))
}

function frame(response, value) {
  response.write(`data: ${JSON.stringify(value)}\n\n`)
}

function formArguments() {
  return JSON.stringify({
    form: {
      scenario: 'CLARIFY',
      purpose: 'GENERAL_CLARIFY',
      title: 'upload evidence',
      fields: [{key: 'evidence', label: 'evidence', type: 'attachment', required: true}]
    }
  })
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

async function bodyJson(request) {
  const chunks = []
  for await (const chunk of request) chunks.push(chunk)
  return JSON.parse(Buffer.concat(chunks).toString('utf8'))
}

async function drain(request) {
  for await (const chunk of request) void chunk
}

function record(type, run, extra = {}) {
  const entry = {type, runId: run?.id ?? null, atMs: Date.now(), ...extra}
  requests.push(entry)
  if (run) run.timeline.push(entry)
}

function sendChat(response, run) {
  response.writeHead(200, {'Content-Type': 'text/event-stream'})
  if (scenario === 'unknown-runid-withholds-cleanup') {
    frame(response, {
      type: 'frontend_tool_request',
      name: 'generative_form',
      toolCallId: run.toolCallId,
      args: formArguments(),
      seq: 2
    })
    return
  }
  frame(response, {type: 'start', runId: run.id, seq: 1})
  frame(response, {
    type: 'frontend_tool_request',
    name: 'generative_form',
    runId: run.id,
    toolCallId: run.toolCallId,
    args: formArguments(),
    seq: 2
  })
}

async function handleChat(request, response) {
  const body = await bodyJson(request)
  chatCount += 1
  const bad = scenario !== 'healthy-attachment' && chatCount === 1
  const id = `run-${chatCount}`
  const run = {
    id,
    toolCallId: `call-${chatCount}`,
    sessionId: body.sessionId,
    role: !bad ? 'healthy'
      : ['real-error-still-trips', 'real-circuit-hitl-receipt'].includes(scenario) ? 'error'
        : scenario === 'failed-race-still-trips' ? 'failed' : 'timeout',
    submitted: false,
    cancelled: false,
    deleted: false,
    timeline: []
  }
  runs.set(id, run)
  record('chat', run, {sessionId: run.sessionId, role: run.role})
  if (run.role === 'healthy' && ['hitl-continuity', 'real-circuit-hitl-receipt'].includes(scenario)) await sleep(100)
  sendChat(response, run)
}

async function handleEvents(request, response, run, url) {
  record('events', run, {lastSeq: url.searchParams.get('lastSeq'), cancelled: run.cancelled, submitted: run.submitted})
  response.writeHead(200, {'Content-Type': 'text/event-stream'})
  if (run.role === 'healthy' && run.submitted) {
    frame(response, {type: 'done', runId: run.id, seq: 3, cancelled: false, historyComplete: true})
    response.end()
    return
  }
  if ((run.role === 'error' || (run.role === 'failed' && run.failedAcknowledged))
    && run.submitted && !run.cancelled) {
    frame(response, {type: 'error', runId: run.id, seq: 3, code: 'UPSTREAM_STREAM_ERROR', historyComplete: true})
    response.end()
    return
  }
  if (run.cancelled && scenario === 'terminal-missing-withholds-cleanup') {
    response.end()
    return
  }
  if (run.cancelled) {
    frame(response, {
      type: 'done', runId: run.id, seq: 4, cancelled: true, code: 'AI_RUN_CANCELLED', historyComplete: true
    })
    response.end()
  }
}

async function handleRequest(request, response) {
  openResponses.add(response)
  response.once('close', () => openResponses.delete(response))
  const url = new URL(request.url, 'http://127.0.0.1')
  if (request.method === 'POST' && url.pathname === '/api/ai/chat') {
    await handleChat(request, response)
    return
  }
  const eventsMatch = /^\/api\/ai\/events\/(run-\d+)$/.exec(url.pathname)
  if (request.method === 'GET' && eventsMatch) {
    await handleEvents(request, response, runs.get(eventsMatch[1]), url)
    return
  }
  if (request.method === 'POST' && url.pathname === '/api/ai/form/attachments') {
    await drain(request)
    const run = [...runs.values()].find((candidate) => !candidate.uploaded)
    run.uploaded = true
    record('upload', run)
    if (run.role === 'healthy' && ['hitl-continuity', 'real-circuit-hitl-receipt'].includes(scenario)) await sleep(250)
    json(response, 200, {fileId: `file-${run.id}`})
    return
  }
  if (request.method === 'POST' && url.pathname === '/api/ai/frontend-tool-result') {
    const body = await bodyJson(request)
    const run = runs.get(body.runId)
    run.submitted = true
    record('frontend-result', run, {toolCallId: body.toolCallId, result: body.result})
    if (scenario === 'hitl-receipt-timeout') return
    json(response, 200, {accepted: true, status: 'FRONTEND_RESULT'})
    return
  }
  const pendingMatch = /^\/api\/ai\/pending\/(run-\d+)$/.exec(url.pathname)
  if (request.method === 'GET' && pendingMatch) {
    const run = runs.get(pendingMatch[1])
    record('pending', run)
    json(response, 200, [{
      toolCallId: run.toolCallId,
      name: 'generative_form',
      status: run.cancelled ? 'CANCELLED' : run.submitted ? 'FRONTEND_RESULT' : 'PENDING'
    }])
    return
  }
  const cancelMatch = /^\/api\/ai\/cancel\/(run-\d+)$/.exec(url.pathname)
  if (request.method === 'POST' && cancelMatch) {
    const run = runs.get(cancelMatch[1])
    record('cancel', run, {evidenceExists: existsSync(path.join(outDir, 'attachment-evidence', `${run.id}.json`))})
    if (scenario === 'cancel-failure-withholds-cleanup') {
      json(response, 500, {code: 'CANCEL_FAILED'})
      return
    }
    if (scenario === 'failed-race-still-trips') {
      run.failedAcknowledged = true
      json(response, 200, {cancelled: false, alreadyTerminal: true, statusBeforeCancel: 'FAILED'})
      return
    }
    run.cancelled = true
    json(response, 200, {cancelled: true, alreadyCancelled: false})
    return
  }
  const sessionMatch = /^\/api\/ai\/session\/(.+)$/.exec(url.pathname)
  if (request.method === 'DELETE' && sessionMatch) {
    const sessionId = decodeURIComponent(sessionMatch[1])
    const run = [...runs.values()].find((candidate) => candidate.sessionId === sessionId)
    if (run) {
      run.deleted = true
      record('delete-session', run, {
        evidenceExists: existsSync(path.join(outDir, 'attachment-evidence', `${run.id}.json`))
      })
    }
    await drain(request)
    json(response, 200, {complete: true, deferred: [], errors: []})
    return
  }
  await drain(request)
  json(response, 404, {code: 'NOT_FOUND', path: url.pathname})
}

const server = createServer((request, response) => {
  handleRequest(request, response).catch((error) => {
    serverError = error
    if (!response.headersSent) json(response, 500, {code: 'STUB_ERROR'})
    else response.destroy(error)
  })
})
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
const address = server.address()
const baseUrl = `http://127.0.0.1:${address.port}`
await mkdir(outDir, {recursive: true})

const child = spawn(process.execPath, [coreScript], {
  cwd: repoRoot,
  env: {
    ...process.env,
    BASE_URL: baseUrl,
    EVAL_ISOLATED_ENV: 'true',
    EVAL_ALLOW_WRITES: 'true',
    TENANT_ID: '1',
    USER_ID: '101',
    OUT_DIR: outDir,
    TIMEOUT_MS: '300',
    EVAL_MODEL_CONCURRENCY: '6',
    EVAL_API_CONCURRENCY: '6',
    EVAL_API_LOAD_REQUESTS: '8',
    EVAL_ATTACHMENT_PROBE: 'true',
    EVAL_ATTACHMENT_PROBE_LOAD: scenario === 'load-result-classification' ? 'true' : 'false',
    EVAL_ATTACHMENT_PROBE_COUNT: ['hitl-continuity', 'real-circuit-hitl-receipt'].includes(scenario) ? '2' : '1'
  },
  stdio: ['ignore', 'pipe', 'pipe']
})
let stdout = ''
let stderr = ''
child.stdout.on('data', (chunk) => { stdout += chunk })
child.stderr.on('data', (chunk) => { stderr += chunk })
const childTimeout = setTimeout(() => child.kill(), 15_000)
const exitCode = await new Promise((resolve) => child.once('close', resolve))
clearTimeout(childTimeout)
for (const response of openResponses) response.destroy()
await new Promise((resolve) => server.close(resolve))
if (serverError) throw serverError

let report = null
try {
  report = JSON.parse(await readFile(path.join(outDir, 'coverage-results.json'), 'utf8'))
} catch {
  report = {fatalError: 'coverage-results.json was not written', modelCircuitBreaker: null}
}
const healthyRun = [...runs.values()].find((run) => run.role === 'healthy')
const badRun = [...runs.values()].find((run) => run.role !== 'healthy')
const evidence = {}
for (const run of runs.values()) {
  const evidencePath = path.join(outDir, 'attachment-evidence', `${run.id}.json`)
  evidence[run.id] = existsSync(evidencePath) ? JSON.parse(await readFile(evidencePath, 'utf8')) : null
}
const result = {
  scenario,
  evidenceLevel: 'SCRIPT_PROTOCOL_STUB_UNIT',
  productEvidenceStatus: 'NOT_RUN',
  exitCode,
  baseUrl,
  outDir,
  report: {
    fatalError: report.fatalError,
    modelCircuitBreaker: report.modelCircuitBreaker,
    attachmentLoad: report.load?.endpoints?.['POST /api/ai/form/attachments'] ?? null,
    attachmentAttempts: report.attachmentEvidence?.attempts ?? []
  },
  runs: [...runs.values()].map((run) => ({
    id: run.id,
    role: run.role,
    submitted: run.submitted,
    cancelled: run.cancelled,
    deleted: run.deleted,
    timeline: run.timeline,
    evidence: evidence[run.id]
  })),
  stdout: stdout.slice(-4000),
  stderr: stderr.slice(-4000),
  assertions: []
}

function check(name, pass, actual) {
  result.assertions.push({name, pass, actual})
  assert(pass, `${name}: ${JSON.stringify(actual)}`)
}

try {
  if (scenario === 'healthy-attachment') {
    check('healthy form is submitted through the protocol stub API', healthyRun.submitted, healthyRun)
    check('healthy run is not cancelled', !healthyRun.cancelled, healthyRun)
  } else if (scenario === 'hitl-continuity') {
    check('another script timeout does not suppress the healthy GF receipt', healthyRun.submitted, healthyRun)
    check('healthy run reaches its own terminal instead of cleanup cancellation', !healthyRun.cancelled, healthyRun)
  } else if (scenario === 'hitl-receipt-timeout') {
    check('HITL receipt timeout does not trip the model circuit', report.modelCircuitBreaker.failed === false,
      report.modelCircuitBreaker)
    check('HITL receipt timeout has a distinct wait kind', String(report.fatalError).includes('waitKind=HITL_RECEIPT'),
      report.fatalError)
    check('HITL receipt cancellation retains its real code', String(report.fatalError).includes('AI_RUN_CANCELLED'),
      report.fatalError)
  } else if (scenario === 'script-timeout-classification') {
    check('script timeout does not trip the model circuit', report.modelCircuitBreaker.failed === false,
      report.modelCircuitBreaker)
    check('script timeout is classified separately', String(report.fatalError).includes('SCRIPT_TIMEOUT_CANCEL'),
      report.fatalError)
    check('real terminal code is retained', String(report.fatalError).includes('AI_RUN_CANCELLED'), report.fatalError)
  } else if (scenario === 'evidence-before-cleanup') {
    const deletion = badRun.timeline.find((entry) => entry.type === 'delete-session')
    check('evidence exists before session deletion', deletion?.evidenceExists === true, deletion)
    check('evidence retains run id and real code', evidence[badRun.id]?.runId === badRun.id
      && evidence[badRun.id]?.realCode === 'AI_RUN_CANCELLED', evidence[badRun.id])
    check('evidence retains pending, cancellation source and timeline', Array.isArray(evidence[badRun.id]?.pending?.beforeCancel)
      && evidence[badRun.id]?.cancellation?.source === 'SCRIPT_TIMEOUT'
      && Array.isArray(evidence[badRun.id]?.timeline), evidence[badRun.id])
  } else if (scenario === 'load-result-classification') {
    const load = report.load.endpoints['POST /api/ai/form/attachments']
    check('script timeout remains a failed load attempt', load.failed === 1 && load.status === 'FAIL', load)
    check('script timeout has a separate load count', load.scriptTimeoutCancelled === 1, load)
    check('load error retains classification, run id and real code', load.errors[0]?.classification === 'SCRIPT_TIMEOUT_CANCEL'
      && load.errors[0]?.runId === badRun.id && load.errors[0]?.realCode === 'AI_RUN_CANCELLED', load.errors)
    check('load timeout does not trip the model circuit', report.modelCircuitBreaker.failed === false,
      report.modelCircuitBreaker)
  } else if (scenario === 'real-error-still-trips') {
    check('real upstream error still trips the model circuit', report.modelCircuitBreaker.failed === true,
      report.modelCircuitBreaker)
    check('real upstream error is not relabelled as script cancellation',
      !String(report.fatalError).includes('SCRIPT_TIMEOUT_CANCEL'), report.fatalError)
  } else if (scenario === 'real-circuit-hitl-receipt') {
    check('real circuit does not block the pending GF direct receipt', healthyRun.submitted, healthyRun)
    check('real circuit remains tripped', report.modelCircuitBreaker.failed === true, report.modelCircuitBreaker)
  } else if (['cancel-failure-withholds-cleanup', 'terminal-missing-withholds-cleanup'].includes(scenario)) {
    const cancelAttempts = badRun.timeline.filter((entry) => entry.type === 'cancel').length
    check('failed settlement does not retry cancellation', cancelAttempts === 1, badRun.timeline)
    check('failed settlement does not delete the session', !badRun.deleted, badRun)
    check('failed settlement withholds cleanup and stays incomplete', evidence[badRun.id]?.cleanupWithheld === true
      && evidence[badRun.id]?.evidenceComplete === false, evidence[badRun.id])
  } else if (scenario === 'failed-race-still-trips') {
    check('FAILED race trips the model circuit', report.modelCircuitBreaker.failed === true, report.modelCircuitBreaker)
    check('FAILED race is not classified as script timeout', !String(report.fatalError).includes('SCRIPT_TIMEOUT_CANCEL'),
      report.fatalError)
    check('FAILED race retains its real terminal code', evidence[badRun.id]?.realCode === 'UPSTREAM_STREAM_ERROR'
      && evidence[badRun.id]?.classification === 'FAIL', evidence[badRun.id])
  } else if (scenario === 'unknown-runid-withholds-cleanup') {
    check('unknown run id does not delete the session', !badRun.deleted, badRun)
    check('unknown run id withholds cleanup', report.attachmentEvidence.attempts[0]?.cleanupWithheld === true
      && report.attachmentEvidence.attempts[0]?.evidenceComplete === false, report.attachmentEvidence.attempts)
  }
  result.status = 'UNIT_PASS'
} catch (error) {
  result.status = 'FAIL'
  result.failure = error.message
} finally {
  await mkdir(path.dirname(artifactPath), {recursive: true})
  await writeFile(artifactPath, `${JSON.stringify(result, null, 2)}\n`)
  console.log(JSON.stringify({scenario, status: result.status, productEvidenceStatus: result.productEvidenceStatus,
    artifactPath, failure: result.failure ?? null}, null, 2))
}
if (result.status !== 'UNIT_PASS') process.exitCode = 1
