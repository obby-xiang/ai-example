/**
 * NFEZUI 域自建上游桩（OpenAI 兼容），带「官方桩转发」兜底。
 *
 * 为什么需要它：域内几条用例要求上游按**本域提示词**给出确定性形状，而官方
 * `scripts/ci/stub-upstream.mjs` 的路由表是共享契约（只读，不得为本域扩表）：
 *   - `NFZ-LIST-TASKS`  让上游发 `list_tasks` 工具调用（结果由**真后端**执行、真 resultView 渲染）
 *   - `NFZ-QUOTA-429`   上游回 429（错误卡 quota 变体的真实来源）
 *   - `NFZ-FILTER`      上游回 400 + `content_filter`（错误卡 filter 变体）
 *   - `NFZ-GENERIC`     上游回 500（错误卡 generic 变体）
 * 其余一切请求**逐字节转发**官方桩，故 GF/HITL/表格/界面原语等既有路由行为一格不变。
 *
 * 启动：node nfezui-upstream.mjs --port 19199 --upstream http://127.0.0.1:19198
 */
import fs from 'node:fs'
import http from 'node:http'

const argv = process.argv.slice(2)
function argOf(name, fallback) {
  const index = argv.indexOf(`--${name}`)
  return index >= 0 && argv[index + 1] ? argv[index + 1] : fallback
}

const PORT = Number(argOf('port', '19199'))
const UPSTREAM = argOf('upstream', 'http://127.0.0.1:19198')
const LOG = (...parts) => fs.writeSync(1, `[nfezui-upstream] ${parts.join(' ')}\n`)

let frameSeq = 0
function chunk(delta, finishReason = null, model = 'nfezui-stub') {
  const body = {
    id: `chatcmpl-nfezui-${++frameSeq}`,
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{ index: 0, delta, finish_reason: finishReason }]
  }
  return `data: ${JSON.stringify(body)}\n\n`
}

function textFrames(text, model) {
  const out = [chunk({ role: 'assistant', content: '' }, null, model)]
  for (const piece of (String(text).match(/[\s\S]{1,24}/g) || [String(text)])) {
    out.push(chunk({ content: piece }, null, model))
  }
  out.push(chunk({}, 'stop', model))
  out.push('data: [DONE]\n\n')
  return out
}

function toolCallFrames(tool, args, callId, model) {
  return [
    chunk({ role: 'assistant', content: '' }, null, model),
    chunk({
      tool_calls: [{ index: 0, id: callId, type: 'function', function: { name: tool, arguments: JSON.stringify(args) } }]
    }, null, model),
    chunk({}, 'tool_calls', model),
    'data: [DONE]\n\n'
  ]
}

function messagesOf(body) {
  return Array.isArray(body?.messages) ? body.messages : []
}

function textOf(message) {
  const content = message?.content
  if (typeof content === 'string') return content
  if (Array.isArray(content)) {
    return content.map((part) => (typeof part === 'string' ? part : typeof part?.text === 'string' ? part.text : '')).join('')
  }
  return ''
}

function lastUserText(body) {
  const users = messagesOf(body).filter((m) => m?.role === 'user')
  return textOf(users[users.length - 1])
}

function disclosedTools(body) {
  const tools = Array.isArray(body?.tools) ? body.tools : []
  return tools.map((t) => t?.function?.name ?? t?.name ?? '').filter(Boolean)
}

const MARKERS = {
  listTasks: 'NFZ-LIST-TASKS',
  quota: 'NFZ-QUOTA-429',
  filter: 'NFZ-FILTER',
  generic: 'NFZ-GENERIC',
  emptyText: 'NFZ-EMPTY'
}

function json(res, status, payload) {
  const body = JSON.stringify(payload)
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) })
  res.end(body)
}

function sse(res, frames) {
  res.writeHead(200, {
    'content-type': 'text/event-stream; charset=utf-8',
    'cache-control': 'no-cache',
    connection: 'keep-alive'
  })
  for (const frame of frames) res.write(frame)
  res.end()
}

function proxy(req, res, raw) {
  const target = new URL(UPSTREAM)
  const upstreamReq = http.request(
    {
      host: target.hostname,
      port: target.port,
      method: req.method,
      path: req.url,
      headers: { ...req.headers, host: `${target.hostname}:${target.port}` }
    },
    (upstreamRes) => {
      res.writeHead(upstreamRes.statusCode ?? 502, upstreamRes.headers)
      upstreamRes.pipe(res)
    }
  )
  upstreamReq.on('error', (error) => {
    LOG(`转发失败：${error.message}`)
    json(res, 502, { error: { message: `nfezui-upstream proxy failed: ${error.message}` } })
  })
  if (raw && raw.length > 0) upstreamReq.write(raw)
  upstreamReq.end()
}

function handleChat(req, res, raw) {
  let body = null
  try {
    body = JSON.parse(raw)
  } catch {
    /* 非 JSON：原样转发（官方桩自己会处理异常形态） */
  }
  const userText = body ? lastUserText(body) : ''
  const model = body?.model ?? 'nfezui-stub'
  const hasToolResult = body ? messagesOf(body).some((m) => m?.role === 'tool') : false
  const lower = String(userText)

  if (lower.includes(MARKERS.quota)) {
    LOG('命中 NFZ-QUOTA-429 → 429')
    json(res, 429, { error: { message: '429 rate limit exceeded: quota exhausted (nfezui)', type: 'rate_limit_error' } })
    return
  }
  if (lower.includes(MARKERS.filter)) {
    LOG('命中 NFZ-FILTER → 400 content_filter')
    json(res, 400, { error: { message: 'content_filter triggered: request rejected by safety policy', code: 'content_filter' } })
    return
  }
  if (lower.includes(MARKERS.generic)) {
    LOG('命中 NFZ-GENERIC → 500')
    json(res, 500, { error: { message: 'nfezui-generic-upstream-failure', type: 'server_error' } })
    return
  }
  if (lower.includes(MARKERS.listTasks)) {
    if (hasToolResult) {
      LOG('list_tasks 已有工具结果 → 文本收尾')
      sse(res, textFrames('任务清单已读取完毕，本轮结束。', model))
      return
    }
    if (!disclosedTools(body).includes('list_tasks')) {
      LOG('list_tasks 未在披露清单 → 文本收尾')
      sse(res, textFrames('当前页面没有开放任务清单工具，本轮按纯文本收尾。', model))
      return
    }
    LOG('命中 NFZ-LIST-TASKS → list_tasks 调用')
    sse(res, toolCallFrames('list_tasks', {}, `call-nfezui-list-${Date.now()}`, model))
    return
  }
  if (lower.includes(MARKERS.emptyText)) {
    sse(res, textFrames('', model))
    return
  }
  proxy(req, res, raw)
}

const server = http.createServer((req, res) => {
  req.on('error', (error) => LOG(`请求流错误：${error.message}`))
  res.on('error', (error) => LOG(`响应流错误：${error.message}`))
  if ((req.url ?? '').startsWith('/probe')) {
    json(res, 200, { ok: true, stub: 'nfezui-upstream', upstream: UPSTREAM })
    return
  }
  const chunks = []
  req.on('data', (c) => chunks.push(c))
  req.on('end', () => {
    const raw = Buffer.concat(chunks).toString('utf8')
    if (req.method === 'POST' && (req.url ?? '').includes('/chat/completions')) {
      try {
        handleChat(req, res, raw)
      } catch (error) {
        LOG(`处理失败（进程存活）：${error?.stack ?? error}`)
        try {
          json(res, 500, { error: { message: 'nfezui-upstream-failed' } })
        } catch {
          /* 响应已结束 */
        }
      }
      return
    }
    proxy(req, res, raw)
  })
})

server.on('error', (error) => {
  fs.writeSync(2, `[nfezui-upstream] 致命：${error?.stack ?? error}\n`)
  process.exit(1)
})
server.listen(PORT, '127.0.0.1', () => LOG(`就绪 http://127.0.0.1:${PORT} → 转发 ${UPSTREAM}`))
