import {lstat, mkdir, realpath, writeFile} from 'node:fs/promises'
import path from 'node:path'
import {execFileSync} from 'node:child_process'
import {fileURLToPath} from 'node:url'

const repoRoot = fileURLToPath(new URL('../..', import.meta.url))
const baseInput = process.env.BASE_URL ?? 'http://127.0.0.1:8081'
let parsedBase
try {
    parsedBase = new URL(baseInput)
} catch {
    throw new Error('BASE_URL must be an absolute HTTP(S) URL')
}
if (!['http:', 'https:'].includes(parsedBase.protocol) || parsedBase.username || parsedBase.password || parsedBase.search || parsedBase.hash) {
    throw new Error('BASE_URL must be an absolute HTTP(S) URL without credentials, query, or fragment')
}
if (process.env.EVAL_ISOLATED_ENV !== 'true') throw new Error('EVAL_ISOLATED_ENV=true is required')
const localHosts = new Set(['localhost', '127.0.0.1', '::1', '[::1]'])
const remote = !localHosts.has(parsedBase.hostname)
if (remote && process.env.EVAL_CONFIRM_REMOTE !== 'true') {
    throw new Error('EVAL_CONFIRM_REMOTE=true is required for a non-local BASE_URL')
}
if (process.env.EVAL_ALLOW_WRITES !== 'true') {
    throw new Error('EVAL_ALLOW_WRITES=true is required for session create/delete and KB reload')
}
const base = parsedBase.toString().replace(/\/$/, '')
const tenantId = process.env.TENANT_ID ?? '1'
const userId = process.env.USER_ID ?? '101'
const outDir = requiredOutsidePath('OUT_DIR')
const aiUnavailableCodes = new Set(['AI_UNAVAILABLE', 'AI_KEY_MISSING', 'AI_KEY_UNREADABLE', 'NO_MODEL_CONFIGURED'])
const results = []
const startedAt = new Date().toISOString()

class VerificationFailure extends Error {
}

function isInside(parent, candidate) {
    const relative = path.relative(path.resolve(parent), path.resolve(candidate))
    return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative))
}

function requiredOutsidePath(name) {
    const raw = process.env[name]?.trim() ?? ''
    if (!path.isAbsolute(raw)) throw new Error(`${name} must be an absolute path outside the repository`)
    const resolved = path.resolve(raw)
    if (isInside(repoRoot, resolved)) throw new Error(`${name} must point outside the repository`)
    return resolved
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

function assertResolvedOutsideRepo(resolvedRepoRoot, candidate, label) {
    if (isInside(resolvedRepoRoot, candidate)) {
        throw new Error(`${label} must resolve outside the repository after following links`)
    }
}

async function prepareEvidenceDirectory() {
    const resolvedRepoRoot = await realpath(repoRoot)
    const existingAncestor = await nearestExistingAncestor(outDir)
    const resolvedAncestor = await realpath(existingAncestor)
    assertResolvedOutsideRepo(resolvedRepoRoot, resolvedAncestor, 'OUT_DIR existing ancestor')
    const resolvedTarget = path.resolve(resolvedAncestor, path.relative(existingAncestor, outDir))
    assertResolvedOutsideRepo(resolvedRepoRoot, resolvedTarget, 'OUT_DIR resolved target')
    await mkdir(outDir, {recursive: true})
    const [verifiedRepoRoot, resolvedOutDir] = await Promise.all([realpath(repoRoot), realpath(outDir)])
    assertResolvedOutsideRepo(verifiedRepoRoot, resolvedOutDir, 'OUT_DIR')
    return resolvedOutDir
}

function codeHead() {
    if (process.env.CODE_HEAD?.trim()) return process.env.CODE_HEAD.trim()
    try {
        return execFileSync('git', ['rev-parse', 'HEAD'], {
            cwd: repoRoot,
            encoding: 'utf8',
            stdio: ['ignore', 'pipe', 'ignore']
        }).trim()
    } catch {
        return 'UNKNOWN'
    }
}

function headers(json = false) {
    return {
        'X-Tenant-Id': tenantId,
        'X-User-Id': userId,
        ...(json ? {'Content-Type': 'application/json'} : {})
    }
}

function businessCodeOf(body) {
    const code = body && typeof body === 'object' ? body.code ?? body.data?.code : null
    return typeof code === 'string' && /^[A-Z][A-Z0-9_]{0,127}$/.test(code) ? code : null
}

function httpResult(response) {
    return {httpStatus: response.status, businessCode: businessCodeOf(response.body)}
}

function errorName(error) {
    const name = error?.name
    return typeof name === 'string' && /^[A-Za-z][A-Za-z0-9]*$/.test(name) ? name : 'Error'
}

function errorMessage(error) {
    if (error instanceof VerificationFailure) return error.message.slice(0, 2048)
    return `UNEXPECTED_ERROR: ${errorName(error)}`
}

async function request(pathName, options = {}, requests = []) {
    const method = options.method ?? 'GET'
    const {json = false, ...fetchOptions} = options
    let response
    try {
        response = await fetch(`${base}${pathName}`, {
            ...fetchOptions,
            redirect: 'error',
            headers: {...headers(json), ...(options.headers ?? {})},
            signal: AbortSignal.timeout(45_000)
        })
    } catch (error) {
        requests.push({
            method,
            path: pathName,
            httpStatus: null,
            businessCode: null,
            error: `REQUEST_FAILED: ${errorName(error)}`
        })
        throw error
    }
    const text = await response.text()
    let body = null
    try {
        body = text === '' ? null : JSON.parse(text)
    } catch {
        body = text
    }
    requests.push({method, path: pathName, ...httpResult({status: response.status, body})})
    return {status: response.status, body, text}
}

function assert(condition, message) {
    if (!condition) throw new VerificationFailure(message)
}

function payloadOf(response) {
    const body = response.body
    return body?.data ?? body
}

function objectPayloadOf(response) {
    const payload = payloadOf(response)
    return payload && typeof payload === 'object' && !Array.isArray(payload) ? payload : {}
}

function booleanValue(value) {
    return typeof value === 'boolean' ? value : null
}

function countValue(value) {
    return Number.isSafeInteger(value) && value >= 0 ? value : null
}

function textValue(value) {
    return typeof value === 'string' ? value.slice(0, 256) : null
}

async function run(id, times, action) {
    for (let round = 1; round <= times; round += 1) {
        const started = Date.now()
        const requests = []
        const detail = {}
        try {
            await action(requests, detail)
            results.push({id, round, status: 'PASS', ms: Date.now() - started, requests, detail})
            console.log(`PASS ${id} round=${round} requests=${requests.length}`)
        } catch (error) {
            const message = errorMessage(error)
            results.push({id, round, status: 'FAIL', ms: Date.now() - started, requests, detail, error: message})
            console.error(`FAIL ${id} round=${round} ${message}`)
        }
    }
}

function sessionIdOf(response) {
    assert(response.status === 200, `创建会话 HTTP ${response.status} code=${businessCodeOf(response.body)}`)
    const body = response.body
    if (body && typeof body === 'object') {
        return body.sessionIdText ?? body.data?.sessionIdText ?? null
    }
    const match = /"sessionIdText"\s*:\s*"([1-9]\d*)"/.exec(response.text) ?? /^\s*([1-9]\d*)\s*$/.exec(response.text)
    return match?.[1] ?? null
}

function healthResult(response) {
    const body = objectPayloadOf(response)
    return {
        ...httpResult(response),
        available: booleanValue(body.available),
        model: textValue(body.model),
        providerId: textValue(body.providerId)
    }
}

function historyResult(response, latest) {
    const payload = payloadOf(response)
    return {
        ...httpResult(response),
        latest,
        messageCount: payload && Array.isArray(payload.messages) ? payload.messages.length : null
    }
}

function deleteResult(response) {
    const body = objectPayloadOf(response)
    return {
        ...httpResult(response),
        complete: booleanValue(body.complete),
        deferredCount: Array.isArray(body.deferred) ? body.deferred.length : null,
        errorCount: Array.isArray(body.errors) ? body.errors.length : null
    }
}

function assertPurgeComplete(response, label) {
    const body = objectPayloadOf(response)
    assert(body.complete === true, `${label} complete!==true`)
    assert(Array.isArray(body.deferred) && body.deferred.length === 0, `${label} deferred 非空或缺失`)
    assert(Array.isArray(body.errors) && body.errors.length === 0, `${label} errors 非空或缺失`)
}

function reloadResult(response) {
    const body = objectPayloadOf(response)
    return {
        ...httpResult(response),
        rebuilt: booleanValue(body.rebuilt),
        ready: booleanValue(body.ready),
        documentCount: countValue(body.doc_count),
        chunkCount: countValue(body.chunk_count)
    }
}

const evidenceDirectory = await prepareEvidenceDirectory()

await run('API-READY', 2, async (requests, detail) => {
    const ready = await request('/health/ready', {}, requests)
    detail.ready = httpResult(ready)
    assert(ready.status === 200, `readiness HTTP ${ready.status}`)
    const ai = await request('/api/ai/health', {}, requests)
    const aiCode = businessCodeOf(ai.body)
    detail.ai = healthResult(ai)
    assert(ai.status === 200 || (ai.status === 503 && aiUnavailableCodes.has(aiCode)),
        `AI health HTTP ${ai.status} code=${aiCode}`)
})

await run('API-SESSION-HISTORY-PURGE', 3, async (requests, detail) => {
    const created = await request('/api/ai/session', {method: 'POST', json: true}, requests)
    detail.create = httpResult(created)
    const sessionId = sessionIdOf(created)
    detail.create.sessionId = sessionId
    assert(typeof sessionId === 'string' && /^[1-9]\d*$/.test(sessionId), '缺少字符串 sessionIdText')
    const history = await request(`/api/ai/history/${sessionId}?latest=1`, {}, requests)
    detail.history = historyResult(history, 1)
    assert(history.status === 200, `history HTTP ${history.status} code=${businessCodeOf(history.body)}`)
    const historyPayload = payloadOf(history)
    assert(historyPayload && Array.isArray(historyPayload.messages), 'history.messages 不是数组')
    assert(historyPayload.messages.length === 0, `空会话历史不为空: ${historyPayload.messages.length}`)
    const firstDelete = await request(`/api/ai/session/${sessionId}`, {method: 'DELETE'}, requests)
    detail.firstDelete = deleteResult(firstDelete)
    assert(firstDelete.status === 200, `首次删除 HTTP ${firstDelete.status} code=${businessCodeOf(firstDelete.body)}`)
    assertPurgeComplete(firstDelete, '首次删除')
    const secondDelete = await request(`/api/ai/session/${sessionId}`, {method: 'DELETE'}, requests)
    detail.secondDelete = deleteResult(secondDelete)
    assert(secondDelete.status === 200, `重复删除 HTTP ${secondDelete.status} code=${businessCodeOf(secondDelete.body)}`)
    assertPurgeComplete(secondDelete, '重复删除')
    const after = await request(`/api/ai/history/${sessionId}?latest=1`, {}, requests)
    detail.afterDelete = historyResult(after, 1)
    assert(after.status === 200 || after.status === 410, `删除后 history HTTP ${after.status}`)
    if (after.status === 200) {
        const afterPayload = payloadOf(after)
        assert(afterPayload && Array.isArray(afterPayload.messages) && afterPayload.messages.length === 0, '删除后历史复活')
    }
})

await run('API-KB-RELOAD', 2, async (requests, detail) => {
    const response = await request('/api/ai/kb/reload', {method: 'POST', json: true}, requests)
    detail.reload = reloadResult(response)
    assert(response.status === 200, `KB reload HTTP ${response.status} code=${businessCodeOf(response.body)}`)
})

const failed = results.filter((item) => item.status === 'FAIL')
const status = failed.length > 0 ? 'FAIL' : 'PASS'
const report = {
    schemaVersion: 2,
    status,
    statusScope: 'GENERAL_PROTOCOL_AND_CONTROLLED_WRITE_REGRESSION; not an AI availability gate',
    evidencePolicy: 'WHITELISTED_SCALARS_AND_BUSINESS_CODES_ONLY',
    startedAt,
    finishedAt: new Date().toISOString(),
    codeHead: codeHead(),
    tenantId,
    userId,
    isolation: {asserted: true, remote, writesAllowed: true},
    summary: {total: results.length, pass: results.length - failed.length, fail: failed.length, notRun: 0},
    verdicts: {
        protocolAndControlledWrites: status,
        aiAvailability: 'NOT_RUN',
        browser: 'NOT_RUN',
        redisAndMultiInstance: 'NOT_RUN'
    },
    rounds: results
}
const reportPath = path.join(evidenceDirectory, `ai-current-protocol-${Date.now()}.json`)
await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8')
console.log(JSON.stringify({reportPath, status, summary: report.summary, verdicts: report.verdicts}, null, 2))
if (failed.length > 0) process.exitCode = 1
