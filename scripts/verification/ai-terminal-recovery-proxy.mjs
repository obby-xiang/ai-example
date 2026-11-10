#!/usr/bin/env node

import {createHash} from 'node:crypto'
import http from 'node:http'
import net from 'node:net'

const integerEnv = (name, fallback) => {
  const value = Number(process.env[name] ?? fallback)
  if (!Number.isSafeInteger(value) || value < 1024 || value > 65535) throw new Error(`${name} must be a TCP port`)
  return value
}

const httpPort = integerEnv('PROXY_PORT', 18643)
const redisPort = integerEnv('REDIS_PROXY_PORT', 16380)
const backendPort = integerEnv('BACKEND_PORT', 18640)
const redisTargetPort = integerEnv('REDIS_TARGET_PORT', 16379)
const proxyMode = process.env.PROXY_MODE ?? 'degraded'
if (!['degraded', 'passthrough', 'hide-terminal', 'duplicate', 'duplicate-early', 'snapshot-expired', 'unconfirmed', 'history-then-unconfirmed', 'budget-gate'].includes(proxyMode)) {
  throw new Error('unsupported PROXY_MODE')
}
const holdMs = Number(process.env.TERMINAL_EOF_HOLD_MS ?? 30_000)
if (!Number.isSafeInteger(holdMs) || holdMs < 1_000 || holdMs > 120_000) throw new Error('TERMINAL_EOF_HOLD_MS must be in 1000..120000')

const evidence = {
  chatResponses: 0,
  degradedTerminalEofHolds: 0,
  heldEofReleased: 0,
  transcriptAppendRejects: 0,
  terminalSnapshotRejects: 0,
  snapshotReadsExpired: 0,
  hiddenTerminalFrames: 0,
  duplicateFirstResponsesDropped: 0,
  duplicateRunId: null,
  historyFailures: 0,
  eventAttempts: 0,
  redisConnections: 0,
  commandCounts: {},
  transcriptKeyCommands: 0,
  expectedDisconnects: 0,
  proxyErrors: []
}
const heldResponses = new Set()
const heldBudgetResponses = new Set()
const transcriptScriptHashes = new Set(['2dc85a54b6288ea69e0d7e60932702257e754229'])
const terminalScriptHashes = new Set()
let transcriptFaultEnabled = proxyMode === 'degraded'
let holdChatEofEnabled = proxyMode === 'degraded'
let snapshotExpired = false
let historyFailuresRemaining = 0
let unconfirmedRunStarted = false

function recordError(error) {
  if (evidence.proxyErrors.length < 32) evidence.proxyErrors.push(String(error?.code ?? error?.name ?? 'Error'))
}

function recordSocketError(error) {
  if (['ECONNRESET', 'EPIPE', 'ERR_STREAM_DESTROYED'].includes(error?.code)) {
    evidence.expectedDisconnects += 1
    return
  }
  recordError(error)
}

function lineEnd(buffer, offset) {
  return buffer.indexOf('\r\n', offset)
}

function respLength(buffer, offset = 0) {
  if (offset >= buffer.length) return null
  const end = lineEnd(buffer, offset)
  if (end < 0) return null
  const prefix = String.fromCharCode(buffer[offset])
  const header = buffer.toString('utf8', offset + 1, end)
  const next = end + 2
  if ('+-:,_#('.includes(prefix)) return next
  if (prefix === '$' || prefix === '!' || prefix === '=') {
    const length = Number(header)
    if (!Number.isSafeInteger(length)) throw new Error('invalid RESP bulk length')
    if (length === -1) return next
    if (length < 0 || buffer.length < next + length + 2) return null
    return next + length + 2
  }
  if (prefix === '*' || prefix === '%' || prefix === '>' || prefix === '~') {
    const declared = Number(header)
    if (!Number.isSafeInteger(declared)) throw new Error('invalid RESP aggregate length')
    if (declared === -1) return next
    if (declared < 0) throw new Error('invalid RESP aggregate length')
    let cursor = next
    const values = prefix === '%' ? declared * 2 : declared
    for (let index = 0; index < values; index += 1) {
      const length = respLength(buffer, cursor)
      if (length === null) return null
      cursor = length
    }
    return cursor
  }
  throw new Error(`unsupported RESP prefix ${prefix}`)
}

function respRequestValues(buffer) {
  if (buffer[0] !== 42) return []
  const end = lineEnd(buffer, 0)
  const count = Number(buffer.toString('utf8', 1, end))
  const values = []
  let cursor = end + 2
  for (let index = 0; index < count; index += 1) {
    if (buffer[cursor] !== 36) return []
    const bulkEnd = lineEnd(buffer, cursor)
    const length = Number(buffer.toString('utf8', cursor + 1, bulkEnd))
    const start = bulkEnd + 2
    values.push(buffer.toString('utf8', start, start + length))
    cursor = start + length + 2
  }
  return values
}

const redisServer = net.createServer((client) => {
  evidence.redisConnections += 1
  const upstream = net.createConnection({host: '127.0.0.1', port: redisTargetPort})
  let requestBuffer = Buffer.alloc(0)
  const responseFaults = []
  client.on('data', (chunk) => {
    requestBuffer = Buffer.concat([requestBuffer, chunk])
    try {
      for (;;) {
        const length = respLength(requestBuffer)
        if (length === null) break
        const raw = requestBuffer.subarray(0, length)
        requestBuffer = requestBuffer.subarray(length)
        const values = respRequestValues(raw)
        const command = String(values[0] ?? '').toUpperCase()
        const script = String(values[1] ?? '')
        evidence.commandCounts[command] = (evidence.commandCounts[command] ?? 0) + 1
        const transcriptKeys = values.slice(3, 7).some((key) => key.startsWith('ai:tx:'))
        if (transcriptKeys) evidence.transcriptKeyCommands += 1
        const transcriptAppend = transcriptFaultEnabled && (command === 'EVAL' || command === 'EVALSHA')
          && ((command === 'EVAL' && script.includes('TRANSCRIPT_CANONICAL_UPSERT_FENCED'))
            || (command === 'EVALSHA' && transcriptScriptHashes.has(script.toLowerCase())))
        if (command === 'EVAL' && script.includes('TRANSCRIPT_CANONICAL_UPSERT_FENCED')) {
          transcriptScriptHashes.add(createHash('sha1').update(script).digest('hex'))
        }
        if (command === 'EVAL' && (script.includes('TERMINAL_SET_IF_NOT_CANCELLED') || script.includes('FENCED_TERMINAL_SET'))) {
          terminalScriptHashes.add(createHash('sha1').update(script).digest('hex'))
        }
        if (transcriptAppend) {
          evidence.transcriptAppendRejects += 1
          client.write('-ERR verification transcript append rejected\r\n')
          continue
        }
        const terminalSnapshotWrite = unconfirmedRunStarted
          && ['unconfirmed', 'history-then-unconfirmed'].includes(proxyMode)
          && (command === 'EVAL' || command === 'EVALSHA')
          && ((command === 'EVAL' && (script.includes('TERMINAL_SET_IF_NOT_CANCELLED') || script.includes('FENCED_TERMINAL_SET')))
            || (command === 'EVALSHA' && terminalScriptHashes.has(script.toLowerCase()))
            || (String(values[3] ?? '').startsWith('ai:run:') && values.slice(6).includes('DONE')))
        if (terminalSnapshotWrite) {
          evidence.terminalSnapshotRejects += 1
          client.write('-ERR verification terminal snapshot write rejected\r\n')
          continue
        }
        const runSnapshotRead = snapshotExpired && command === 'GET'
          && (String(values[1] ?? '').startsWith('ai:run:') || String(values[1] ?? '').startsWith('ai:runstate:'))
        if (runSnapshotRead) {
          evidence.snapshotReadsExpired += 1
          client.write('$-1\r\n')
          continue
        }
        responseFaults.push(null)
        if (!upstream.write(raw)) upstream.once('drain', () => {})
      }
    } catch (error) {
      recordError(error)
      client.destroy()
      upstream.destroy()
    }
  })
  // Keep request and response framing separate so an injected error cannot overtake a previous command.
  let inbound = Buffer.alloc(0)
  upstream.on('data', (chunk) => {
    inbound = Buffer.concat([inbound, chunk])
    try {
      for (;;) {
        const length = respLength(inbound)
        if (length === null || responseFaults.length === 0) break
        const raw = inbound.subarray(0, length)
        inbound = inbound.subarray(length)
        const fault = responseFaults.shift()
        const response = fault === 'noscript'
          ? Buffer.from('-NOSCRIPT No matching script. Please use EVAL.\r\n')
          : fault === 'reject' ? Buffer.from('-ERR verification transcript append rejected\r\n') : raw
        client.write(response)
      }
    } catch (error) {
      recordError(error)
      client.destroy()
      upstream.destroy()
    }
  })
  client.on('error', recordSocketError)
  upstream.on('error', recordSocketError)
  client.on('close', () => upstream.destroy())
  upstream.on('close', () => client.destroy())
})

const httpServer = http.createServer(async (req, res) => {
  if (req.url === '/__verification/evidence') {
    res.writeHead(200, {'Content-Type': 'application/json', 'Cache-Control': 'no-store'})
    res.end(JSON.stringify({...evidence, transcriptScriptHashes: [...transcriptScriptHashes]}))
    return
  }
  if (req.url === '/__verification/mode' && req.method === 'POST') {
    let body = ''
    req.on('data', (chunk) => {
      body += chunk
      if (body.length > 4096) req.destroy()
    })
    req.on('end', () => {
      try {
        const mode = JSON.parse(body)
        if (typeof mode.transcriptFault === 'boolean') transcriptFaultEnabled = mode.transcriptFault
        if (typeof mode.holdChatEof === 'boolean') holdChatEofEnabled = mode.holdChatEof
        if (typeof mode.snapshotExpired === 'boolean') snapshotExpired = mode.snapshotExpired
        if (Number.isSafeInteger(mode.historyFailures) && mode.historyFailures >= 0 && mode.historyFailures <= 2) {
          historyFailuresRemaining = mode.historyFailures
        }
        res.writeHead(200, {'Content-Type': 'application/json'})
        res.end(JSON.stringify({transcriptFault: transcriptFaultEnabled, holdChatEof: holdChatEofEnabled, snapshotExpired, historyFailuresRemaining}))
      } catch (error) {
        recordError(error)
        res.writeHead(400, {'Content-Type': 'text/plain; charset=utf-8'})
        res.end('invalid mode')
      }
    })
    return
  }
  if (req.url === '/__verification/release' && req.method === 'POST') {
    for (const response of [...heldResponses]) {
      clearTimeout(response.terminalHoldTimer)
      if (!response.writableEnded) response.end()
      heldResponses.delete(response)
      evidence.heldEofReleased += 1
    }
    for (const held of [...heldBudgetResponses]) {
      held.backendResponse.resume()
      if (!held.res.writableEnded) {
        held.res.writeHead(500, {'Content-Type': 'text/plain; charset=utf-8'})
        held.res.end('verification bounded reconnect attempt')
      }
      heldBudgetResponses.delete(held)
    }
    res.writeHead(200, {'Content-Type': 'application/json'})
    res.end(JSON.stringify({released: true}))
    return
  }
  evidence.proxyErrors.length = Math.min(evidence.proxyErrors.length, 32)
  if (historyFailuresRemaining > 0 && req.method === 'GET' && req.url?.startsWith('/api/ai/history/')) {
    historyFailuresRemaining -= 1
    evidence.historyFailures += 1
    res.writeHead(503, {'Content-Type': 'application/json'})
    res.end(JSON.stringify({code: 'HISTORY_TRANSIENT'}))
    return
  }
  if (proxyMode === 'duplicate-early' && req.method === 'GET' && req.url?.includes('probe=1')) {
    await new Promise((resolve) => setTimeout(resolve, 750))
  }
  const upstream = http.request({
    hostname: '127.0.0.1',
    port: backendPort,
    path: req.url,
    method: req.method,
    headers: req.headers
  }, (backendResponse) => {
    const isChat = req.method === 'POST' && req.url === '/api/ai/chat'
    const isEvent = req.method === 'GET' && req.url.startsWith('/api/ai/events/') && !req.url.includes('probe=1')
    const isProbe = req.method === 'GET' && req.url.startsWith('/api/ai/events/') && req.url.includes('probe=1')
    if (isChat) evidence.chatResponses += 1
    if (isChat && ['unconfirmed', 'history-then-unconfirmed'].includes(proxyMode)) {
      backendResponse.on('data', (chunk) => {
        if (chunk.toString('utf8').includes('"type":"delta"')) unconfirmedRunStarted = true
      })
    }
    if (isEvent && proxyMode === 'budget-gate') {
      evidence.eventAttempts += 1
      if (evidence.eventAttempts === 5) {
        heldBudgetResponses.add({req, res, backendResponse})
        return
      }
      if (evidence.eventAttempts < 5) {
        backendResponse.resume()
        res.writeHead(500, {'Content-Type': 'text/plain; charset=utf-8'})
        res.end('verification bounded reconnect attempt')
        return
      }
    }
    if (isChat && (proxyMode === 'duplicate' || proxyMode === 'duplicate-early') && evidence.chatResponses === 1) {
      evidence.duplicateFirstResponsesDropped += 1
      backendResponse.on('data', (chunk) => {
        if (evidence.duplicateRunId === null) {
          const match = chunk.toString('utf8').match(/"type"\s*:\s*"start"[\s\S]*?"runId"\s*:\s*"([0-9a-f-]+)"/)
          if (match) evidence.duplicateRunId = match[1]
        }
      })
      backendResponse.resume()
      if (proxyMode === 'duplicate-early') {
        res.writeHead(503, {'Content-Type': 'application/json'})
        res.end(JSON.stringify({code: 'GATEWAY_TRANSIENT'}))
        return
      }
      backendResponse.on('end', () => {
        if (!res.writableEnded) {
          res.writeHead(503, {'Content-Type': 'application/json'})
          res.end(JSON.stringify({code: 'GATEWAY_TRANSIENT'}))
        }
      })
      return
    }
    if (['hide-terminal', 'unconfirmed', 'history-then-unconfirmed'].includes(proxyMode) && (isChat || isEvent)) {
      const chunks = []
      backendResponse.on('data', (chunk) => chunks.push(chunk))
      backendResponse.on('end', () => {
        const events = Buffer.concat(chunks).toString('utf8').split(/(?<=^|\n)\n/)
        const visible = events.filter((event) => {
          const terminal = /"type"\s*:\s*"(?:done|error)"/.test(event)
          if (terminal) evidence.hiddenTerminalFrames += 1
          return !terminal
        })
        res.writeHead(backendResponse.statusCode ?? 502, backendResponse.headers)
        res.end(visible.join('\n'))
      })
      return
    }
    res.writeHead(backendResponse.statusCode ?? 502, backendResponse.headers)
    if (!isChat) {
      if (isProbe) {
        setTimeout(() => backendResponse.pipe(res), 2_000)
      } else {
        backendResponse.pipe(res)
      }
      return
    }
    backendResponse.on('data', (chunk) => {
      if (!res.destroyed) res.write(chunk)
    })
    backendResponse.on('end', () => {
      if (!holdChatEofEnabled) {
        res.end()
        return
      }
      evidence.degradedTerminalEofHolds += 1
      heldResponses.add(res)
      res.terminalHoldTimer = setTimeout(() => {
        heldResponses.delete(res)
        evidence.heldEofReleased += 1
        if (!res.writableEnded) res.end()
      }, holdMs)
    })
    backendResponse.on('error', (error) => {
      recordError(error)
      res.destroy()
    })
  })
  upstream.on('error', (error) => {
    recordError(error)
    if (!res.headersSent) res.writeHead(502, {'Content-Type': 'text/plain; charset=utf-8'})
    res.end('verification proxy upstream unavailable')
  })
  res.on('close', () => {
    heldResponses.delete(res)
    upstream.destroy()
  })
  req.pipe(upstream)
})

await new Promise((resolve, reject) => {
  redisServer.once('error', reject)
  httpServer.once('error', reject)
  redisServer.listen(redisPort, '127.0.0.1', () => {
    httpServer.listen(httpPort, '127.0.0.1', resolve)
  })
})
console.log(JSON.stringify({status: 'READY', proxyMode, httpPort, redisPort, backendPort, redisTargetPort, holdMs}))

function shutdown() {
  for (const response of heldResponses) {
    clearTimeout(response.terminalHoldTimer)
    if (!response.writableEnded) response.end()
  }
  for (const held of heldBudgetResponses) {
    held.backendResponse.resume()
    if (!held.res.writableEnded) held.res.end()
  }
  redisServer.close()
  httpServer.close()
}
process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
