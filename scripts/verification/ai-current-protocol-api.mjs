const base = (process.env.BASE_URL ?? 'http://127.0.0.1:8081').replace(/\/$/, '')
const tenantId = process.env.TENANT_ID ?? '1'
const userId = process.env.USER_ID ?? '101'
const results = []

function headers(json = false) {
  return {
    'X-Tenant-Id': tenantId,
    'X-User-Id': userId,
    ...(json ? { 'Content-Type': 'application/json' } : {})
  }
}

async function request(path, options = {}) {
  const response = await fetch(`${base}${path}`, {
    ...options,
    headers: { ...headers(options.json === true), ...(options.headers ?? {}) },
    signal: AbortSignal.timeout(45_000)
  })
  const text = await response.text()
  let body = null
  try { body = text === '' ? null : JSON.parse(text) } catch { body = text }
  return { status: response.status, body, text }
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

function payloadOf(response) {
  const body = response.body
  return body?.data ?? body
}

async function run(id, times, action) {
  for (let round = 1; round <= times; round += 1) {
    const started = Date.now()
    try {
      const detail = await action(round)
      results.push({ id, round, pass: true, ms: Date.now() - started, detail })
      console.log(`PASS ${id} round=${round} ${JSON.stringify(detail)}`)
    } catch (error) {
      results.push({ id, round, pass: false, ms: Date.now() - started, error: String(error?.message ?? error) })
      console.error(`FAIL ${id} round=${round} ${error?.message ?? error}`)
    }
  }
}

function sessionIdOf(response) {
  assert(response.status === 200, `创建会话 HTTP ${response.status}: ${response.text}`)
  const body = response.body
  if (body && typeof body === 'object') {
    return body.sessionIdText ?? body.data?.sessionIdText ?? null
  }
  const match = /"sessionIdText"\s*:\s*"([1-9]\d*)"/.exec(response.text) ?? /^\s*([1-9]\d*)\s*$/.exec(response.text)
  return match?.[1] ?? null
}

await run('API-READY', 2, async () => {
  const ready = await request('/health/ready')
  assert(ready.status === 200, `readiness HTTP ${ready.status}`)
  const ai = await request('/api/ai/health')
  assert(ai.status === 200, `AI health HTTP ${ai.status}`)
  return { ready: ready.status, ai: ai.status }
})

await run('API-SESSION-HISTORY-PURGE', 3, async () => {
  const created = await request('/api/ai/session', { method: 'POST', json: true })
  const sessionId = sessionIdOf(created)
  assert(typeof sessionId === 'string' && /^[1-9]\d*$/.test(sessionId), `缺少字符串 sessionIdText: ${created.text}`)
  const history = await request(`/api/ai/history/${sessionId}?latest=1`)
  assert(history.status === 200, `history HTTP ${history.status}: ${history.text}`)
  const historyPayload = payloadOf(history)
  assert(historyPayload && Array.isArray(historyPayload.messages), `history.messages 不是数组: ${history.text}`)
  assert(historyPayload.messages.length <= 1, `latest=1 未生效: ${historyPayload.messages.length}`)
  const firstDelete = await request(`/api/ai/session/${sessionId}`, { method: 'DELETE' })
  assert(firstDelete.status === 200, `首次删除 HTTP ${firstDelete.status}: ${firstDelete.text}`)
  const secondDelete = await request(`/api/ai/session/${sessionId}`, { method: 'DELETE' })
  assert(secondDelete.status === 200, `重复删除 HTTP ${secondDelete.status}: ${secondDelete.text}`)
  const after = await request(`/api/ai/history/${sessionId}?latest=1`)
  assert(after.status === 200 || after.status === 410, `删除后 history HTTP ${after.status}`)
  if (after.status === 200) {
    const afterPayload = payloadOf(after)
    assert(afterPayload && Array.isArray(afterPayload.messages) && afterPayload.messages.length === 0,
      `删除后历史复活: ${after.text}`)
  }
  return { sessionId, firstDelete: firstDelete.status, secondDelete: secondDelete.status, after: after.status }
})

await run('API-KB-RELOAD', 2, async () => {
  const response = await request('/api/ai/kb/reload', { method: 'POST', json: true })
  assert(response.status === 200, `KB reload HTTP ${response.status}: ${response.text}`)
  return { status: response.status, body: response.body }
})

const failed = results.filter((item) => !item.pass)
console.log(`SUMMARY total=${results.length} pass=${results.length - failed.length} fail=${failed.length}`)
if (failed.length > 0) process.exitCode = 1
