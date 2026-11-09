import http from 'node:http'
import {createWriteStream} from 'node:fs'
import {lstat, mkdir, realpath} from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

const repoRoot = fileURLToPath(new URL('../..', import.meta.url))
const hopByHopHeaders = [
    'connection',
    'keep-alive',
    'proxy-authenticate',
    'proxy-authorization',
    'te',
    'trailer',
    'transfer-encoding',
    'upgrade'
]
const maxSequence = 999_999_999
let nextSequence = 0
let firstHistoryRequest = true

class ConfigurationError extends Error {
}

function configurationError(message) {
    throw new ConfigurationError(message)
}

function requiredText(name, maximumLength) {
    const value = process.env[name]?.trim() ?? ''
    if (value === '') configurationError(`${name} is required`)
    if (value.length > maximumLength || /[\x00-\x1f\x7f]/.test(value)) {
        configurationError(`${name} must be at most ${maximumLength} characters without control characters`)
    }
    return value
}

function boundedInteger(name, fallback, minimum, maximum) {
    const raw = process.env[name]?.trim() || String(fallback)
    if (!/^\d+$/.test(raw)) configurationError(`${name} must be an integer`)
    const value = Number(raw)
    if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
        configurationError(`${name} must be between ${minimum} and ${maximum}`)
    }
    return value
}

function isLoopbackHostname(hostname) {
    if (hostname === '::1' || hostname === '[::1]') return true
    const match = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(hostname)
    return match !== null && Number(match[1]) === 127
}

function upstreamConfiguration() {
    const raw = requiredText('UPSTREAM_BASE_URL', 2048)
    let upstream
    try {
        upstream = new URL(raw)
    } catch {
        configurationError('UPSTREAM_BASE_URL must be an absolute loopback HTTP URL')
    }
    if (upstream.protocol !== 'http:' || !isLoopbackHostname(upstream.hostname)) {
        configurationError('UPSTREAM_BASE_URL must use HTTP and a loopback hostname')
    }
    if (upstream.username || upstream.password || upstream.search || upstream.hash) {
        configurationError('UPSTREAM_BASE_URL must not contain credentials, query, or fragment')
    }
    return upstream
}

function isInside(parent, candidate) {
    const relative = path.relative(path.resolve(parent), path.resolve(candidate))
    return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative))
}

function outsidePath(name) {
    const raw = requiredText(name, 4096)
    if (!path.isAbsolute(raw)) configurationError(`${name} must be an absolute path outside the repository`)
    const resolved = path.resolve(raw)
    if (isInside(repoRoot, resolved)) configurationError(`${name} must point outside the repository`)
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

function assertResolvedOutsideRepo(resolvedRepoRoot, candidate) {
    if (isInside(resolvedRepoRoot, candidate)) {
        throw new ConfigurationError('OUT_DIR must resolve outside the repository after following links')
    }
}

async function prepareEvidenceDirectory(outDir) {
    const resolvedRepoRoot = await realpath(repoRoot)
    const existingAncestor = await nearestExistingAncestor(outDir)
    const resolvedAncestor = await realpath(existingAncestor)
    assertResolvedOutsideRepo(resolvedRepoRoot, resolvedAncestor)
    const resolvedTarget = path.resolve(resolvedAncestor, path.relative(existingAncestor, outDir))
    assertResolvedOutsideRepo(resolvedRepoRoot, resolvedTarget)
    await mkdir(outDir, {recursive: true})
    const resolvedOutDir = await realpath(outDir)
    assertResolvedOutsideRepo(resolvedRepoRoot, resolvedOutDir)
    return resolvedOutDir
}

function errorName(error) {
    if (typeof error === 'string') return error
    const name = error?.name
    return typeof name === 'string' && /^[A-Za-z][A-Za-z0-9]{0,63}$/.test(name) ? name : 'Error'
}

function requestSequence() {
    nextSequence = nextSequence >= maxSequence ? 1 : nextSequence + 1
    return nextSequence
}

function parseRequestTarget(rawUrl) {
    if (typeof rawUrl !== 'string' || !rawUrl.startsWith('/') || rawUrl.startsWith('//')) {
        throw new SyntaxError('Origin-form request target required')
    }
    const parsed = new URL(rawUrl, 'http://proxy.invalid')
    return {pathname: parsed.pathname, target: `${parsed.pathname}${parsed.search}`}
}

function withoutHopByHopHeaders(source) {
    const headers = {...source}
    const connectionTokens = Array.isArray(source.connection) ? source.connection.join(',') : source.connection
    for (const token of String(connectionTokens ?? '').split(',')) {
        const name = token.trim().toLowerCase()
        if (name !== '') delete headers[name]
    }
    for (const name of hopByHopHeaders) delete headers[name]
    return headers
}

function upstreamTarget(upstream, target) {
    const prefix = upstream.pathname === '/' ? '' : upstream.pathname.replace(/\/+$/, '')
    return `${prefix}${target}`
}

function handleProxyRequest(req, res, configuration, logRecord) {
    res.sendDate = false
    const sequence = requestSequence()
    const startedAt = new Date().toISOString()
    let parsedTarget
    try {
        parsedTarget = parseRequestTarget(req.url)
    } catch (error) {
        res.writeHead(400, {'content-length': '0', connection: 'close'})
        res.end()
        logRecord({sequence, pathname: '', delayMs: 0, status: 400, startedAt, error})
        return
    }

    const isHistoryRequest = req.method === 'GET' && parsedTarget.pathname.startsWith('/api/ai/history/')
    const delayMs = isHistoryRequest && firstHistoryRequest ? configuration.historyDelayMs : 0
    if (isHistoryRequest) firstHistoryRequest = false

    let delayTimer = null
    let upstreamRequest = null
    let upstreamResponse = null
    let status = null
    let responseStarted = false
    let disconnected = false
    let completed = false

    function finish(error = null) {
        if (completed) return
        completed = true
        if (delayTimer !== null) clearTimeout(delayTimer)
        delayTimer = null
        logRecord({sequence, pathname: parsedTarget.pathname, delayMs, status, startedAt, error})
    }

    function destroyUpstream() {
        if (upstreamResponse !== null) upstreamResponse.destroy()
        if (upstreamRequest !== null) upstreamRequest.destroy()
    }

    function clientDisconnected() {
        if (completed || res.writableFinished) return
        disconnected = true
        destroyUpstream()
        if (!res.destroyed) res.destroy()
        finish('AbortError')
    }

    function upstreamFailed(error) {
        if (completed) return
        if (disconnected) {
            finish('AbortError')
            return
        }
        if (responseStarted || res.headersSent) {
            if (!res.destroyed) res.destroy()
            finish(error)
            return
        }
        status = 502
        res.writeHead(status, {'content-length': '0', connection: 'close'})
        res.end()
        finish(error)
    }

    req.on('aborted', clientDisconnected)
    req.on('error', (error) => {
        if (completed) return
        destroyUpstream()
        if (!res.destroyed) res.destroy()
        finish(error)
    })
    res.on('close', clientDisconnected)
    res.on('error', (error) => {
        destroyUpstream()
        finish(error)
    })
    res.on('finish', () => finish())

    try {
        const headers = withoutHopByHopHeaders(req.headers)
        headers.host = configuration.upstream.host
        upstreamRequest = http.request({
            protocol: 'http:',
            hostname: configuration.upstream.hostname === '[::1]' ? '::1' : configuration.upstream.hostname,
            port: configuration.upstream.port || '80',
            method: req.method,
            path: upstreamTarget(configuration.upstream, parsedTarget.target),
            headers
        })
        upstreamRequest.once('error', upstreamFailed)
        upstreamRequest.once('response', (incoming) => {
            upstreamResponse = incoming
            status = incoming.statusCode ?? null
            const headers = withoutHopByHopHeaders(incoming.headers)
            try {
                res.writeHead(incoming.statusCode ?? 502, incoming.statusMessage, headers)
                responseStarted = true
            } catch (error) {
                destroyUpstream()
                if (!res.destroyed) res.destroy()
                finish(error)
                return
            }
            incoming.once('error', upstreamFailed)
            if (delayMs === 0) {
                incoming.pipe(res)
                return
            }
            incoming.pause()
            delayTimer = setTimeout(() => {
                delayTimer = null
                if (completed || disconnected) return
                incoming.pipe(res)
            }, delayMs)
        })
        req.pipe(upstreamRequest)
    } catch (error) {
        upstreamFailed(error)
    }
}

async function openLogStream(logPath) {
    const stream = createWriteStream(logPath, {flags: 'ax', encoding: 'utf8', mode: 0o600})
    await new Promise((resolve, reject) => {
        function startupError(error) {
            reject(error)
        }
        stream.once('error', startupError)
        stream.once('open', () => {
            stream.off('error', startupError)
            resolve()
        })
    })
    return stream
}

async function main() {
    if (process.env.EVAL_ISOLATED_ENV !== 'true') {
        configurationError('EVAL_ISOLATED_ENV=true is required')
    }
    const configuration = {
        upstream: upstreamConfiguration(),
        proxyPort: boundedInteger('PROXY_PORT', 18_540, 1, 65_535),
        historyDelayMs: boundedInteger('HISTORY_DELAY_MS', 800, 0, 60_000),
        outDir: outsidePath('OUT_DIR'),
        executorIdentity: requiredText('EXECUTOR_IDENTITY', 128)
    }
    const evidenceDirectory = await prepareEvidenceDirectory(configuration.outDir)
    const logPath = path.join(evidenceDirectory, `history-timing-proxy-${process.pid}-${Date.now()}.jsonl`)
    const logStream = await openLogStream(logPath)
    const sockets = new Set()

    function logRecord({sequence, pathname, delayMs, status, startedAt, error}) {
        const record = {
            sequence,
            executorIdentity: configuration.executorIdentity,
            path: pathname.slice(0, 512),
            delayMs,
            status,
            startedAt,
            endedAt: new Date().toISOString(),
            errorName: error === null ? null : errorName(error)
        }
        logStream.write(`${JSON.stringify(record)}\n`)
    }

    const server = http.createServer((req, res) => handleProxyRequest(req, res, configuration, logRecord))
    server.on('connection', (socket) => {
        sockets.add(socket)
        socket.once('close', () => sockets.delete(socket))
    })
    logStream.on('error', (error) => {
        console.error(`LOG_STREAM_ERROR ${errorName(error)}`)
        process.exitCode = 1
        shutdown()
    })

    let shuttingDown = false
    let logEndScheduled = false
    function scheduleLogStreamEnd() {
        if (logEndScheduled) return
        logEndScheduled = true
        setTimeout(() => logStream.end(), 25)
    }

    function shutdown() {
        if (shuttingDown) return
        shuttingDown = true
        server.close(scheduleLogStreamEnd)
        const forceTimer = setTimeout(() => {
            for (const socket of sockets) socket.destroy()
            scheduleLogStreamEnd()
        }, 5_000)
        forceTimer.unref()
    }
    process.once('SIGINT', shutdown)
    process.once('SIGTERM', shutdown)

    try {
        await new Promise((resolve, reject) => {
            server.once('error', reject)
            server.listen(configuration.proxyPort, '127.0.0.1', resolve)
        })
    } catch (error) {
        logStream.end()
        throw error
    }
    console.log(JSON.stringify({
        proxyUrl: `http://127.0.0.1:${configuration.proxyPort}`,
        firstHistoryDelayMs: configuration.historyDelayMs,
        logPath
    }))
}

try {
    await main()
} catch (error) {
    if (error instanceof ConfigurationError) console.error(error.message)
    else console.error(`STARTUP_FAILED ${errorName(error)}`)
    process.exitCode = 1
}
