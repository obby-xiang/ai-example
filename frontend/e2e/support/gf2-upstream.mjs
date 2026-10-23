/**
 * GF2 域自建上游桩（OpenAI 兼容）：**同一会话连发两张生成式表单卡**。
 *
 * 为什么需要它（L-92 前置）：官方 `scripts/ci/stub-upstream.mjs` 的两条收敛契约与本用例的形态直接冲突，
 * 且那张表是共享契约（只读，不得为本域扩表）：
 *   ① `convergence.maxCallsPerTurn` / 「上下文中已有 `role=tool` ⇒ 只发文本」——同会话第二轮永不发工具调用；
 *   ② 路由台账按「首条 user 消息指纹」记 `used`，同一路由在一个会话里只准发一次。
 * 本域要的恰恰是「同一会话、同一表单 schema、两轮各一张卡」（第二轮提交不收敛的现场），
 * 因此本桩自造形状；其余请求（非本域标记）一律回纯文本。
 *
 * 判定口径（确定性，无随机）：
 *   - 末条消息是 `role=tool` ⇒ **续轮**，回文本收尾（= 官方桩收敛契约③的同构实现）；
 *   - 末条消息是 `role=user` ⇒ **新的一轮**，按 user 消息条数定轮次：第 1 条 → 卡①、第 2 条 → 卡②、更多 → 文本；
 *   - 未披露 `generative_form`（披露清单闸）⇒ 退化为文本（不制造请求风暴，也让"披露坏了"可归因）。
 *
 * 落档（与官方桩同形，`support/hgta.ts` 的 `stubRequestsFor` / `toolMessagesOf` 直接可读）：
 *   `<dir>/req-NNN.json`（请求原文）+ `.decision.json`（本轮判定）+ `.head.txt`（模型可见消息摘要）。
 *
 * 启动：node gf2-upstream.mjs --port 22599 --dir <run-dir>/stub
 */
import fs from 'node:fs'
import path from 'node:path'
import http from 'node:http'

const argv = process.argv.slice(2)
function argOf(name, fallback) {
  const index = argv.indexOf(`--${name}`)
  return index >= 0 && argv[index + 1] ? argv[index + 1] : fallback
}

const PORT = Number(argOf('port', '22599'))
const DIR = argOf('dir', path.resolve(process.cwd(), 'gf2-dump'))
const LOG = (...parts) => fs.writeSync(1, `[gf2-upstream] ${parts.join(' ')}\n`)

/** 本域提示词标记：只有带它的请求才发卡（其余请求保持纯文本，不干扰别面）。 */
const MARKER = 'GF-RND'

let frameSeq = 0
let callSeq = 0
let reqSeq = 0

function chunk(delta, finishReason = null, model = 'gf2-stub') {
  const body = {
    id: `chatcmpl-gf2-${++frameSeq}`,
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

function disclosedTools(body) {
  const tools = Array.isArray(body?.tools) ? body.tools : []
  return tools.map((t) => t?.function?.name ?? t?.name ?? '').filter(Boolean)
}

/**
 * 两轮同一 schema（字段键必须一样，这才是"同页两卡字段撞车"的现场），只换标题以示区分。
 * 形状对齐 `stub-routes.json` 的 GF-FILTER：keyword 必填 text + scope 可选 enum。
 */
function formArgs(round) {
  return {
    form: {
      scenario: 'FILTER',
      title: `导出筛选条件（第 ${round} 轮）`,
      fields: [
        { key: 'keyword', label: '关键字', type: 'text', required: true, placeholder: '编码或名称关键字' },
        {
          key: 'scope',
          label: '范围',
          type: 'enum',
          options: [
            { value: 'XN', label: 'XN' },
            { value: 'HD', label: 'HD' }
          ]
        }
      ]
    }
  }
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

/** 与官方桩同形的落档：请求原文 + 判定 + 模型可见消息摘要。 */
function record(raw, body, decision) {
  try {
    fs.mkdirSync(DIR, { recursive: true })
    const name = `req-${String(++reqSeq).padStart(3, '0')}.json`
    fs.writeFileSync(path.join(DIR, name), raw)
    fs.writeFileSync(path.join(DIR, `${name}.decision.json`), JSON.stringify(decision, null, 2))
    const head = messagesOf(body)
      .map((m) => `${m?.role ?? '?'}: ${textOf(m).slice(0, 200)}`)
      .join('\n')
    fs.writeFileSync(path.join(DIR, `${name}.head.txt`), head)
    fs.writeFileSync(path.join(DIR, `${name}.tools.txt`), disclosedTools(body).join('\n'))
  } catch (error) {
    LOG(`落档失败（不影响链路）：${error?.message ?? error}`)
  }
}

/** 本轮判定：见文件头「判定口径」。 */
function decide(body) {
  const messages = messagesOf(body)
  const last = messages[messages.length - 1]
  if (last?.role === 'tool') {
    return { kind: 'text', text: '已收到表单结果，本轮结束。', reason: '续轮（末条消息是工具结果）' }
  }
  if (last?.role !== 'user') {
    return { kind: 'text', text: '（空轮）', reason: `末条消息角色=${last?.role ?? '无'}` }
  }
  const userText = textOf(last)
  if (!userText.includes(MARKER)) {
    return { kind: 'text', text: '本轮为纯文本轮。', reason: '未带本域标记' }
  }
  if (!disclosedTools(body).includes('generative_form')) {
    return { kind: 'text', text: '当前页面没有开放生成式表单。', reason: '披露清单里没有 generative_form' }
  }
  const round = messages.filter((m) => m?.role === 'user').length
  if (round > 2) {
    return { kind: 'text', text: '两轮已跑完，本轮按文本收尾。', reason: `第 ${round} 个用户消息超本域用例面` }
  }
  const callId = `call-gf2-r${round}-${++callSeq}`
  return {
    kind: 'tool_call',
    tool: 'generative_form',
    args: formArgs(round),
    callId,
    route: `GF-RND-${round}`,
    reason: `第 ${round} 轮新用户消息 ⇒ 发第 ${round} 张表单卡`
  }
}

function handleChat(req, res, raw) {
  let body = null
  try {
    body = JSON.parse(raw)
  } catch {
    body = null
  }
  const model = body?.model ?? 'gf2-stub'
  const decision = body ? decide(body) : { kind: 'text', text: '（非 JSON 请求）', reason: '解析失败' }
  record(raw, body, decision)
  if (decision.kind === 'tool_call') {
    LOG(`第 ${decision.route} 轮 → generative_form ${decision.callId}`)
    sse(res, toolCallFrames(decision.tool, decision.args, decision.callId, model))
    return
  }
  LOG(`文本收尾：${decision.reason}`)
  if (body && body.stream === false) {
    json(res, 200, {
      id: `chatcmpl-gf2-${++frameSeq}`,
      object: 'chat.completion',
      created: Math.floor(Date.now() / 1000),
      model,
      choices: [{ index: 0, message: { role: 'assistant', content: decision.text }, finish_reason: 'stop' }]
    })
    return
  }
  sse(res, textFrames(decision.text, model))
}

const server = http.createServer((req, res) => {
  req.on('error', (error) => LOG(`请求流错误：${error.message}`))
  res.on('error', (error) => LOG(`响应流错误：${error.message}`))
  if ((req.url ?? '').startsWith('/probe')) {
    json(res, 200, { ok: true, stub: 'gf2-upstream' })
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
          json(res, 500, { error: { message: 'gf2-upstream-failed' } })
        } catch {
          /* 响应已结束 */
        }
      }
      return
    }
    json(res, 200, { ok: true, stub: 'gf2-upstream' })
  })
})

server.on('error', (error) => {
  fs.writeSync(2, `[gf2-upstream] 致命：${error?.stack ?? error}\n`)
  process.exit(1)
})
server.listen(PORT, '127.0.0.1', () => LOG(`就绪 http://127.0.0.1:${PORT}（标记 ${MARKER}）`))
