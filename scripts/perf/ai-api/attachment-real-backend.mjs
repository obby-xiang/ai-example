import assert from 'node:assert/strict'
import {spawn} from 'node:child_process'
import {createWriteStream} from 'node:fs'
import {createServer} from 'node:http'
import {mkdir, readFile, writeFile} from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const backendJar = path.join(repoRoot, 'backend/target/config-mgr.jar')
const coreScript = path.join(repoRoot, 'scripts/perf/ai-api/core-coverage.mjs')
const outDir = path.resolve(repoRoot, '../../../../_k31-real-attachment', `run-${process.pid}`)
const artifactPath = process.env.PROBE_ARTIFACT?.trim() || path.join(outDir, 'product-evidence.json')
const upstreamRequests = []
const openResponses = new Set()
let upstreamError = null

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

async function bodyJson(request) {
  const chunks = []
  for await (const chunk of request) chunks.push(chunk)
  return JSON.parse(Buffer.concat(chunks).toString('utf8'))
}

function chunk(response, delta, finishReason = null) {
  response.write(`data: ${JSON.stringify({
    id: `chatcmpl-real-stub-${upstreamRequests.length}`,
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model: 'ci-stub',
    choices: [{index: 0, delta, finish_reason: finishReason}]
  })}\n\n`)
}

function startSse(response) {
  response.writeHead(200, {'Content-Type': 'text/event-stream'})
}

function endSse(response) {
  response.write('data: [DONE]\n\n')
  response.end()
}

function sendForm(response, requestId) {
  startSse(response)
  chunk(response, {role: 'assistant', content: ''})
  chunk(response, {
    tool_calls: [{
      index: 0,
      id: `call-real-gf-${requestId}`,
      type: 'function',
      function: {
        name: 'generative_form',
        arguments: JSON.stringify({
          form: {
            scenario: 'CLARIFY',
            purpose: 'GENERAL_CLARIFY',
            title: 'upload evidence',
            fields: [{key: 'evidence', label: 'evidence', type: 'attachment', required: true}]
          }
        })
      }
    }]
  })
  chunk(response, {}, 'tool_calls')
  endSse(response)
}

function sendProtocolError(response) {
  response.writeHead(401, {'Content-Type': 'application/json'})
  response.end(JSON.stringify({error: {message: 'independent upstream induced auth failure', type: 'authentication_error'}}))
}

function sendText(response) {
  startSse(response)
  chunk(response, {role: 'assistant', content: ''})
  chunk(response, {content: 'real backend continuation accepted'})
  chunk(response, {}, 'stop')
  endSse(response)
}

const upstream = createServer((request, response) => {
  const handle = async () => {
    openResponses.add(response)
    response.once('close', () => openResponses.delete(response))
    if (request.method !== 'POST' || !request.url.endsWith('/chat/completions')) {
      response.writeHead(404)
      response.end()
      return
    }
    const body = await bodyJson(request)
    const id = upstreamRequests.length + 1
    upstreamRequests.push({
      id,
      hasToolResult: (body.messages ?? []).some((message) => message.role === 'tool'),
      messageCount: body.messages?.length ?? 0
    })
    if (id === 2) await sleep(1000)
    if (id <= 2) sendForm(response, id)
    else if (id === 3) sendProtocolError(response)
    else sendText(response)
  }
  handle().catch((error) => {
    upstreamError = error
    response.destroy(error)
  })
})
await new Promise((resolve) => upstream.listen(0, '127.0.0.1', resolve))
const upstreamPort = upstream.address().port
const backendPort = await new Promise((resolve) => {
  const probe = createServer()
  probe.listen(0, '127.0.0.1', () => {
    const port = probe.address().port
    probe.close(() => resolve(port))
  })
})
await mkdir(outDir, {recursive: true})
const backendLog = path.join(outDir, 'backend.log')
const backendWork = path.join(outDir, 'backend-work')
const filesDir = path.join(outDir, 'files')
const h2Dir = path.join(outDir, 'h2')
await Promise.all([backendWork, filesDir, h2Dir].map((directory) => mkdir(directory, {recursive: true})))
const backend = spawn('java', [
  '-jar', backendJar,
  `--server.port=${backendPort}`,
  `--spring.datasource.url=jdbc:h2:file:${path.join(h2Dir, 'attachment').replace(/\\/g, '/')};DB_CLOSE_DELAY=-1`,
  '--spring.datasource.username=sa',
  '--spring.datasource.password=',
  '--spring.data.redis.host=127.0.0.1',
  '--spring.data.redis.port=6379',
  `--app.file.storage-path=${filesDir}`,
  '--app.ai.reasoning.display-enabled=true',
  '--app.ai.reasoning.persist-content=true',
  '--logging.level.com.example.configmgr=INFO'
], {
  cwd: backendWork,
  env: {
    ...process.env,
    AI_API_KEY: 'attachment-real-stub-placeholder',
    AI_BASE_URL: `http://127.0.0.1:${upstreamPort}`,
    AI_MODEL: 'ci-stub'
  },
  stdio: ['ignore', 'pipe', 'pipe']
})
const logStream = createWriteStream(backendLog, {flags: 'a'})
backend.stdout.on('data', (chunk) => logStream.write(chunk))
backend.stderr.on('data', (chunk) => logStream.write(chunk))
let backendExited = false
backend.once('exit', () => { backendExited = true })

async function stopBackend() {
  if (!backendExited && process.platform === 'win32') {
    await new Promise((resolve) => {
      const killer = spawn('taskkill', ['/pid', String(backend.pid), '/T', '/F'], {stdio: 'ignore'})
      killer.once('exit', resolve)
    })
  } else if (!backendExited) {
    backend.kill('SIGTERM')
  }
  if (!backendExited) {
    const exited = await Promise.race([
      new Promise((resolve) => backend.once('exit', resolve)),
      sleep(5_000).then(() => false)
    ])
    if (exited === false && !backendExited) throw new Error('backend did not exit after forced cleanup')
  }
  await new Promise((resolve) => logStream.end(resolve))
}

let result = {
  scenario: 'real-circuit-hitl-receipt',
  evidenceLevel: 'REAL_BACKEND_PUBLIC_API_WITH_INDEPENDENT_UPSTREAM_STUB',
  status: 'NOT_RUN',
  outDir,
  backendLog,
  upstreamRequests,
  failure: null
}
let core = null
try {
  const deadline = Date.now() + 90_000
  let ready = false
  while (Date.now() < deadline && !ready) {
    if (backendExited) throw new Error('backend exited before AI/Redis readiness')
    try {
      const [readyResponse, aiResponse] = await Promise.all([
        fetch(`http://127.0.0.1:${backendPort}/health/ready`, {signal: AbortSignal.timeout(2_000)}),
        fetch(`http://127.0.0.1:${backendPort}/api/ai/health`, {signal: AbortSignal.timeout(2_000)})
      ])
      const ai = await aiResponse.json()
      if (readyResponse.status === 200 && aiResponse.status === 200
        && (ai.data ?? ai).available === true && (ai.data ?? ai).redis?.available === true) ready = true
    } catch {
    }
    if (!ready) await sleep(200)
  }
  if (!ready) throw new Error('backend did not become AI/Redis ready')

  core = spawn(process.execPath, [coreScript], {
    cwd: repoRoot,
    env: {
      ...process.env,
      BASE_URL: `http://127.0.0.1:${backendPort}`,
      EVAL_ISOLATED_ENV: 'true',
      EVAL_ALLOW_WRITES: 'true',
      TENANT_ID: '1',
      USER_ID: '101',
      OUT_DIR: path.join(outDir, 'core'),
      TIMEOUT_MS: '5000',
      EVAL_MODEL_CONCURRENCY: '6',
      EVAL_API_CONCURRENCY: '6',
      EVAL_API_LOAD_REQUESTS: '8',
      EVAL_ATTACHMENT_PROBE: 'true',
      EVAL_ATTACHMENT_PROBE_COUNT: '2'
    },
    stdio: ['ignore', 'pipe', 'pipe']
  })
  let stdout = ''
  let stderr = ''
  core.stdout.on('data', (chunk) => { stdout += chunk })
  core.stderr.on('data', (chunk) => { stderr += chunk })
  const coreExitCode = await new Promise((resolve) => core.once('close', resolve))
  const report = JSON.parse(await readFile(path.join(outDir, 'core/coverage-results.json'), 'utf8'))
  const attempts = report.attachmentEvidence.attempts
  const secondEvidence = attempts.find((evidence) => evidence.lastSse?.cancelled === true)
  result = {
    ...result,
    coreExitCode,
    modelCircuitBreaker: report.modelCircuitBreaker,
    attachmentEvidence: {
      complete: report.attachmentEvidence.complete,
      missing: report.attachmentEvidence.missing,
      attempts
    },
    cleanupErrors: report.cleanupErrors,
    stdout: stdout.slice(-4000),
    stderr: stderr.slice(-4000),
    assertions: []
  }
  const check = (name, pass, actual) => {
    result.assertions.push({name, pass, actual})
    assert(pass, `${name}: ${JSON.stringify(actual)}`)
  }
  check('backend produced a real model circuit failure', report.modelCircuitBreaker.failed === true,
    report.modelCircuitBreaker)
  check('first GF drove the real failing continuation request', upstreamRequests.length >= 3
    && upstreamRequests[2]?.hasToolResult === true, upstreamRequests)
  check('second run evidence contains backend accepted receipt, pending state and terminal',
    secondEvidence?.realCode === secondEvidence?.lastSse?.code
    && secondEvidence.timeline.some((entry) => entry.phase === 'HITL_RECEIPT_ACCEPTED')
    && secondEvidence.pending.beforeCancel?.[0]?.status === 'FRONTEND_RESULT'
    && secondEvidence.lastSse?.type === 'done'
    && secondEvidence.cancellation.response?.cancelled === true, secondEvidence)
  check('both real backend attempts have complete evidence', attempts.length === 2
    && report.attachmentEvidence.complete === true && report.attachmentEvidence.missing.length === 0,
    report.attachmentEvidence)
  result.status = 'PRODUCT_EVIDENCE_PASS'
} catch (error) {
  result.status = 'FAIL'
  result.failure = error.message
} finally {
  for (const response of openResponses) response.destroy()
  upstream.closeAllConnections()
  await new Promise((resolve) => upstream.close(resolve))
  if (core && core.exitCode === null) core.kill('SIGTERM')
  await stopBackend()
  await mkdir(path.dirname(artifactPath), {recursive: true})
  await writeFile(artifactPath, `${JSON.stringify(result, null, 2)}\n`)
  console.log(JSON.stringify({scenario: result.scenario, status: result.status, artifactPath, failure: result.failure}, null, 2))
}
process.exit(result.status === 'PRODUCT_EVIDENCE_PASS' ? 0 : 1)
