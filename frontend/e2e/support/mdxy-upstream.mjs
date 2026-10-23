/**
 * MD / X / Y 三域自建上游桩（OpenAI 兼容），只为「正文本体形状」与「安全样本」提供确定性上游。
 *
 * <h2>为什么本域要自建一个桩</h2>
 * 官方 `scripts/ci/stub-upstream.mjs` 的路由表是**共享契约**（只读）：它只发固定纯文本
 * {@code TURN_TEXT}，不含 markdown 结构、mermaid 围栏、站外图链、敏感凭据样本 ——
 * MD（渲染）/ X（DLP）两域的核心断言面恰好就是这些正文形状。Y 域（配置页）需要
 * `GET /models` 的确定性响应，官方桩虽有，但本域仍需把"模型字段真值"落盘核对。
 * 因此本桩由 `E2E_STUB_SCRIPT` 注入（harness 已全 env 参数化），**不改共享桩一个字节**。
 *
 * <h2>选择口径（请求级标记，不猜调用方身份）</h2>
 * 提示词里的标记决定本轮正文形状，未带标记 ⇒ 默认纯文本轮（与官方桩同族的确定性文本）：
 *   - `md=basic|highlight|mermaid|mermaid-broken|xss|long|stream|deltas`
 *   - `safety=mask|pem`（输出侧 DLP 样本：跨分片被切开的 `sk-` 串 / 完整私钥块）
 *   - `tool=<name>&args=<json>`（工具轮；受披露闸约束 —— 未披露 ⇒ 退化为文本轮）
 *
 * <h2>落盘（与官方桩同形，便于用例读同一套解析）</h2>
 * `--dir` 下逐请求写 `req-NNN.json`（上游收到的**原文**）、`req-NNN.tools.txt`、
 * `req-NNN.head.txt`、`req-NNN.decision.json`；启动时写 `content.json`（本轮所有正文常量，
 * 用例从这里取期望值 ⇒ 用例与桩不会各写一份字面量而漂移）。
 *
 * 启动：node mdxy-upstream.mjs --port 19399 --dir <run-dir>/stub
 */
import fs from 'node:fs'
import http from 'node:http'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const argv = process.argv.slice(2)
function argOf(name, fallback) {
  const index = argv.indexOf(`--${name}`)
  return index >= 0 && argv[index + 1] ? argv[index + 1] : fallback
}

const PORT = Number(argOf('port', '19399'))
const DIR = argOf('dir', path.join(process.cwd(), 'stub'))
fs.mkdirSync(DIR, { recursive: true })

/**
 * 共享路由表（只读复用 `scripts/ci/stub-routes.json`）：本域不新增路由，只借它把
 * `gf-expr-linkage` / `gf-attachment` 这类既有形状发出来（X-10 / X-12 的前置）。
 * 表缺失 ⇒ 数组为空，其余用例不受影响（唯一影响是那两条用例的提示词退化为文本轮）。
 */
const ROUTES_FILE = argOf('routes', fileURLToPath(new URL('../../../scripts/ci/stub-routes.json', import.meta.url)))
const ROUTES = (() => {
  try {
    const table = JSON.parse(fs.readFileSync(ROUTES_FILE, 'utf8'))
    return Array.isArray(table?.routes) ? table.routes : []
  } catch (error) {
    fs.writeSync(2, `[WARN] 共享路由表未加载（${error?.message ?? error}）：GF 形状用例会退化为文本轮\n`)
    return []
  }
})()

/** 桩暴露的模型清单（`GET /models`，「测试连接」与「拉取模型」两条用例的取数点）。 */
const STUB_MODEL_IDS = ['stub-md-model', 'stub-md-model-alt']

// ── 正文样本（唯一事实源；用例从 content.json 读同一份） ──────────────────────

const BASIC = [
  '# 一级标题',
  '',
  '正文段落带 **加粗**、*斜体* 与 ~~删除线~~，末尾收口。',
  '',
  '## 二级标题',
  '',
  '- 列表项一',
  '- 列表项二',
  '',
  '1. 有序项一',
  '2. 有序项二',
  '',
  '### 三级标题',
  '',
  '| 列A | 列B | 列C |',
  '| --- | --- | --- |',
  '| a1 | b1 | c1 |',
  '| a2 | b2 | c2 |',
  '',
  '> 引用行：这里是一段被引用的说明文字。',
  '',
  '---',
  '',
  '结尾段落。'
].join('\n')

/** 围栏里带一条超长行：代码块必须自己横向滚动（不撑破气泡），见 MD-02。 */
const LONG_CODE_LINE = "const longLine = '" + 'abcdefghij'.repeat(20) + "'"

const HIGHLIGHT = [
  '行内代码 `const inlineValue = 42` 与围栏：',
  '',
  '```js',
  'function add(a, b) {',
  '  return a + b',
  '}',
  LONG_CODE_LINE,
  '```',
  '',
  '围栏之后收口。'
].join('\n')

const MERMAID = [
  '下面是一张流程图：',
  '',
  '```mermaid',
  'graph TD',
  '  A[开始] --> B{判断}',
  '  B -->|是| C[结束]',
  '  B -->|否| D[重试]',
  '```',
  '',
  '图之后收口。'
].join('\n')

const MERMAID_BROKEN = [
  '下面是一张坏图：',
  '',
  '```mermaid',
  'graph TD',
  '  A[ --> 这不是合法的 mermaid @@@',
  '```',
  '',
  '坏图之后收口。'
].join('\n')

const XSS = [
  '普通文本前段。',
  '',
  '<img src=x onerror="window.__mdxss=(window.__mdxss||0)+1">',
  '',
  '<script>window.__mdxss=99</script>',
  '',
  '<iframe src="https://evil.example/frame"></iframe>',
  '',
  '[站外链接](https://evil.example/path)',
  '',
  '![外图](https://evil.example/a.png)',
  '',
  '[站内链接](/tasks)',
  '',
  '[危险协议](javascript:alert(1))',
  '',
  '结束段。'
].join('\n')

/** 长文样本（> 10k 字符 ⇒ 渲染降级：结构照渲、高亮与 mermaid 关闭）。 */
function buildLong() {
  const parts = ['# 长文标题', '', '开头段落。', '', '```js', 'const longValue = 1', '```', '']
  parts.push('```mermaid', 'graph LR', '  X[入口] --> Y[出口]', '```', '')
  let index = 0
  while (parts.join('\n').length < 11_600) {
    index += 1
    parts.push(`## 小节 ${index}`, '', `这是第 ${index} 段用于推高字符数的正文，含中文与 English 混排，长度可控。`, '')
  }
  return parts.join('\n')
}

const LONG = buildLong()

/**
 * 流式未闭合围栏：前半段停在围栏内，后半段才闭合（MD-08 的唯一载体）。
 *
 * 必须**足够长**：输出侧 DLP 有 128 字符的尾部保留窗口（`SafetyRules.TAIL_WINDOW`），
 * 正文末尾 128 字符要等下一片才放行 ⇒ 短样本在"停顿期"一个字节都到不了浏览器，
 * 未闭合围栏的中间态就无从观测。故前半段带一段围栏内填充（≥300 字符）。
 */
const STREAM_PAD = Array.from({ length: 24 }, (_v, i) => `const pad${String(i).padStart(3, '0')} = ${i}`)
const STREAM_PART1 = ['## 流式标题', '', '```js', 'const first = 1', ...STREAM_PAD].join('\n')
const STREAM_PART2 = ['', 'const second = 2', '```', '', '尾部段落收口。'].join('\n')

/** 300 个 delta 的编号片段（MD-12：顺次渲染、不丢不乱）。 */
function deltaPieces() {
  return Array.from({ length: 300 }, (_v, i) => `<${String(i).padStart(3, '0')}>`)
}

/** 输出侧 DLP 样本：key 串会被 24 字符切片切开（跨分片检测）。前缀按仓内扫描库同款手法拆分，避免入库被密钥规则误报。 */
const SAFETY_MASK = '密钥一：s' + 'k-abcdefghijklmnopqrstuvwxyz012345 出现在这里，其后是普通正文收口。'
/** 输出侧 DLP 私钥块（唯一走 block 的一档）。私钥头同样拆分构造。 */
const PEM_HEAD = '-----BEGIN' + ' RSA PRIVATE KEY-----'
const PEM_TAIL = '-----END' + ' RSA PRIVATE KEY-----'
const SAFETY_PEM = [
  '下面是配置片段：',
  '',
  PEM_HEAD,
  'MIIEowIBAAKCAQEA0Z3VS5JJcds3xfn',
  PEM_TAIL,
  '',
  '片段结束。'
].join('\n')

const CLOSING_TEXT = '工具结果已读，收口段落。'
const DEFAULT_TEXT = '确定性纯文本轮：用于未带域标记的请求（与官方桩同族的基线语义）。'

const CONTENT = {
  basic: BASIC,
  highlight: HIGHLIGHT,
  mermaid: MERMAID,
  mermaidBroken: MERMAID_BROKEN,
  xss: XSS,
  long: LONG,
  streamPart1: STREAM_PART1,
  streamPart2: STREAM_PART2,
  deltaPieces: deltaPieces(),
  safetyMask: SAFETY_MASK,
  safetyPem: SAFETY_PEM,
  closingText: CLOSING_TEXT,
  defaultText: DEFAULT_TEXT,
  stubModelIds: STUB_MODEL_IDS
}
fs.writeFileSync(path.join(DIR, 'content.json'), JSON.stringify(CONTENT, null, 2), 'utf8')

// ── 帧构造（与官方桩同形） ───────────────────────────────────────────────────

let frameSeq = 0
function chunk(delta, finishReason = null, model = 'mdxy-stub') {
  return `data: ${JSON.stringify({
    id: `chatcmpl-mdxy-${++frameSeq}`,
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{ index: 0, delta, finish_reason: finishReason }]
  })}\n\n`
}

function splitPieces(text) {
  return String(text).match(/[\s\S]{1,24}/g) || [String(text)]
}

/** 文本轮：role 帧 → 24 字符切片 → stop → [DONE]。 */
function textRound(text, model) {
  const out = [chunk({ role: 'assistant', content: '' }, null, model)]
  for (const piece of splitPieces(text)) out.push(chunk({ content: piece }, null, model))
  out.push(chunk({}, 'stop', model))
  out.push('data: [DONE]\n\n')
  return out
}

/** 工具轮：单帧聚合 tool_calls → finish_reason=tool_calls → [DONE]。 */
function toolRound(tool, args, callId, model) {
  return [
    chunk({ role: 'assistant', content: '' }, null, model),
    chunk({
      tool_calls: [{ index: 0, id: callId, type: 'function', function: { name: tool, arguments: JSON.stringify(args) } }]
    }, null, model),
    chunk({}, 'tool_calls', model),
    'data: [DONE]\n\n'
  ]
}

// ── 请求解析 ─────────────────────────────────────────────────────────────────

function messagesOf(body) {
  return Array.isArray(body?.messages) ? body.messages : []
}

function textOf(message) {
  const content = message?.content
  if (typeof content === 'string') return content
  if (Array.isArray(content)) {
    return content
      .map((part) => (typeof part === 'string' ? part : typeof part?.text === 'string' ? part.text : ''))
      .join('')
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

/** 带界 JSON（工具入参可能含嵌套对象 / 字符串里的 `}`）。 */
function balancedJson(text, from) {
  let depth = 0
  let inString = false
  let escaped = false
  for (let i = from; i < text.length; i += 1) {
    const ch = text[i]
    if (inString) {
      if (escaped) escaped = false
      else if (ch === '\\') escaped = true
      else if (ch === '"') inString = false
      continue
    }
    if (ch === '"') inString = true
    else if (ch === '{') depth += 1
    else if (ch === '}') {
      depth -= 1
      if (depth === 0) return text.slice(from, i + 1)
    }
  }
  return null
}

const TOOL_MARKER = /\btool=([A-Za-z_][A-Za-z0-9_]{0,63})\b/

function requestTool(text) {
  const match = TOOL_MARKER.exec(text ?? '')
  if (!match) return null
  const name = match[1]
  const rest = text.slice(match.index + match[0].length)
  if (!/^&args=/.test(rest)) return { name, args: {} }
  const from = rest.indexOf('{', '&args='.length)
  if (from < 0) return { name, args: {}, badArgs: '缺少 JSON 对象' }
  const json = balancedJson(rest, from)
  if (json === null) return { name, args: {}, badArgs: 'JSON 括号不平衡' }
  try {
    const parsed = JSON.parse(json)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return { name, args: {}, badArgs: 'JSON 不是对象' }
    }
    return { name, args: parsed }
  } catch {
    return { name, args: {}, badArgs: 'JSON 解析失败' }
  }
}

/** 本轮判定（纯函数，便于自检）。 */
function decide(body) {
  const messages = messagesOf(body)
  const userText = lastUserText(body)
  const hasToolResult = messages.some((m) => m?.role === 'tool')
  if (hasToolResult) {
    return { kind: 'text', text: CLOSING_TEXT, reason: '工具续轮收口' }
  }
  const tool = requestTool(userText)
  if (tool) {
    const disclosed = disclosedTools(body)
    if (!disclosed.includes(tool.name)) {
      return { kind: 'text', text: DEFAULT_TEXT, reason: `工具 ${tool.name} 未披露 ⇒ 退化为文本轮` }
    }
    return { kind: 'tool_call', tool: tool.name, args: tool.args, route: 'MDXY-TOOL', reason: '请求级工具路由' }
  }
  const md = /\bmd=([a-z-]+)\b/.exec(userText ?? '')
  if (md) return { kind: 'md', name: md[1], reason: `正文形状 md=${md[1]}` }
  const safety = /\bsafety=([a-z]+)\b/.exec(userText ?? '')
  if (safety) return { kind: 'safety', name: safety[1], reason: `安全样本 safety=${safety[1]}` }
  // 共享路由表（只读复用 `scripts/ci/stub-routes.json`，不为本域扩表）：X-10/X-12 的 GF 形状走这里
  const route = matchRoute(messages, body)
  if (route) {
    return { kind: 'tool_call', tool: route.tool, args: route.arguments, route: route.id, reason: `共享路由 ${route.id}` }
  }
  return { kind: 'text', text: DEFAULT_TEXT, reason: '默认纯文本轮' }
}

/** 共享路由匹配（`messageAny` 任一命中 + `requiresTools` 全部已披露）。
 *
 * **只看 user 消息**：官方桩的口径是"提示词里写标记"，而系统提示词本身就含「配置定义」等词，
 * 若把 system 也纳入 haystack，`TC15-LIST-DEFS` 会抢在任何业务标记之前命中（实测踩到）。 */
function matchRoute(messages, body) {
  if (ROUTES.length === 0) return null
  const haystack = messages.filter((m) => m?.role === 'user').map((m) => textOf(m)).join('\n')
  const disclosed = disclosedTools(body)
  for (const route of ROUTES) {
    const any = route?.match?.messageAny ?? []
    const requires = route?.match?.requiresTools ?? []
    if (any.length === 0) continue
    if (!any.some((needle) => haystack.includes(needle))) continue
    if (!requires.every((name) => disclosed.includes(name))) continue
    return route
  }
  return null
}

/** 请求落盘（与官方桩同形的四件套）。 */
let requestIndex = 0
function record(raw, body, decision) {
  requestIndex += 1
  const tag = String(requestIndex).padStart(3, '0')
  try {
    fs.writeFileSync(path.join(DIR, `req-${tag}.json`), raw, 'utf8')
    fs.writeFileSync(path.join(DIR, `req-${tag}.tools.txt`),
      `disclosedCount=${disclosedTools(body).length}\n${disclosedTools(body).join('\n')}\n`, 'utf8')
    fs.writeFileSync(path.join(DIR, `req-${tag}.head.txt`),
      messagesOf(body).map((m) => `${m?.role ?? '?'}: ${textOf(m).replace(/\s+/g, ' ').slice(0, 160)}`).join('\n'),
      'utf8')
    fs.writeFileSync(path.join(DIR, `req-${tag}.decision.json`), JSON.stringify(decision, null, 2), 'utf8')
  } catch (error) {
    fs.writeSync(2, `[WARN] 落盘失败：${error?.message ?? error}\n`)
  }
  return tag
}

function handleModels(res) {
  res.writeHead(200, { 'content-type': 'application/json' })
  res.end(JSON.stringify({ object: 'list', data: STUB_MODEL_IDS.map((id) => ({ id, object: 'model', owned_by: 'mdxy' })) }))
}

async function handleChat(req, res, raw) {
  let body = {}
  try {
    body = JSON.parse(raw)
  } catch {
    body = {}
  }
  const decision = decide(body)
  const tag = record(raw, body, decision)
  const model = body?.model ?? 'mdxy-stub'

  res.writeHead(200, {
    'content-type': 'text/event-stream; charset=utf-8',
    'cache-control': 'no-cache',
    connection: 'keep-alive'
  })

  const write = (frame) => res.write(frame)
  const sleep = (ms) => new Promise((done) => setTimeout(done, ms))

  if (decision.kind === 'tool_call') {
    for (const frame of toolRound(decision.tool, decision.args, `call-mdxy-${tag}`, model)) write(frame)
    res.end()
    return
  }
  if (decision.kind === 'md' && decision.name === 'stream') {
    // 未闭合围栏在流中停留：前半段 → 停顿 → 闭合段（MD-08 的中间态断言面）
    const first = [chunk({ role: 'assistant', content: '' }, null, model)]
    for (const piece of splitPieces(STREAM_PART1)) first.push(chunk({ content: piece }, null, model))
    for (const frame of first) write(frame)
    await sleep(2500)
    const second = []
    for (const piece of splitPieces(STREAM_PART2)) second.push(chunk({ content: piece }, null, model))
    second.push(chunk({}, 'stop', model))
    second.push('data: [DONE]\n\n')
    for (const frame of second) write(frame)
    res.end()
    return
  }
  if (decision.kind === 'md' && decision.name === 'deltas') {
    const out = [chunk({ role: 'assistant', content: '' }, null, model)]
    for (const piece of deltaPieces()) out.push(chunk({ content: piece }, null, model))
    out.push(chunk({}, 'stop', model))
    out.push('data: [DONE]\n\n')
    for (const frame of out) {
      write(frame)
      await sleep(4)
    }
    res.end()
    return
  }

  let text = DEFAULT_TEXT
  if (decision.kind === 'md') {
    const byName = {
      basic: BASIC,
      highlight: HIGHLIGHT,
      mermaid: MERMAID,
      'mermaid-broken': MERMAID_BROKEN,
      xss: XSS,
      long: LONG
    }
    text = byName[decision.name] ?? DEFAULT_TEXT
  } else if (decision.kind === 'safety') {
    text = decision.name === 'pem' ? SAFETY_PEM : SAFETY_MASK
  } else if (decision.kind === 'text') {
    text = decision.text
  }
  for (const frame of textRound(text, model)) write(frame)
  res.end()
}

const server = http.createServer((req, res) => {
  req.on('error', (error) => fs.writeSync(2, `[WARN] 请求流错误：${error?.message ?? error}\n`))
  res.on('error', (error) => fs.writeSync(2, `[WARN] 响应流错误：${error?.message ?? error}\n`))
  const url = req.url ?? '/'
  if (url.split('?')[0] === '/probe') {
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end('{"ok":true}')
    return
  }
  if (url.split('?')[0].endsWith('/models')) {
    handleModels(res)
    return
  }
  if (req.method !== 'POST') {
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end('{"ok":true,"stub":"mdxy"}')
    return
  }
  const chunks = []
  req.on('data', (c) => chunks.push(c))
  req.on('end', () => {
    const raw = Buffer.concat(chunks).toString('utf8')
    if (!url.includes('/chat/completions')) {
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end('{"ok":true}')
      return
    }
    handleChat(req, res, raw).catch((error) => {
      fs.writeSync(2, `[ERROR] 处理请求失败（进程存活）：${error?.stack ?? error}\n`)
      try {
        res.writeHead(500, { 'content-type': 'application/json' })
        res.end('{"error":"mdxy-stub-failed"}')
      } catch {
        /* 已结束 */
      }
    })
  })
})

server.listen(PORT, '127.0.0.1', () => {
  fs.writeSync(1, `[mdxy-stub] listening on ${PORT}, dir=${DIR}\n`)
})
