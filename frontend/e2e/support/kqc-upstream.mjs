#!/usr/bin/env node
/**
 * KQC 域（K 知识问答 / Q 快捷建议 / C 上下文）自建上游桩（OpenAI 兼容）。
 *
 * <h2>为什么需要它</h2>
 * 官方 `scripts/ci/stub-upstream.mjs` 的 `productTools` 是**共享契约表**（只读）：KB 三工具
 * （`kb_search` / `kb_read_doc` / `kb_lookup_term`）不在其中，`tool=<name>&args=<json>` 请求级标记
 * 会被披露闸前的 productTools 闸拦成文本轮 ⇒ K 域在官方桩下**结构上不可达**。
 * 本文件按 `E2E_STUB_SCRIPT` 契约提供同族的窄面替身（NFEZUI 域 `nfezui-upstream.mjs` 同形态）：
 *
 *   - `tool=kb_search|kb_read_doc|kb_lookup_term&args={…}`  让上游发工具调用（结果由**真后端**
 *     真执行：真 Lucene 索引 / 真样例知识仓 / 真 KbText 定界与 8000 字符闸）；带 role=tool 的续轮
 *     只发文本收尾（收敛契约 ③：不制造请求风暴）；
 *   - `tool=get_config_def&args={…}`  压缩档（C 域）**唯一**额外放行的业务工具：压缩是上下文机制，
 *     与工具语义无关，而 KB 三工具可能因装配缺陷缺席（见 `C_DOMAIN_TOOLS` 注）；
 *   - `usage-shape=<last|repeat|zero-last|none>`  四种 usage 形状（数值取 `stub-routes.json`
 *     `usageShapes` 的唯一取数点，与本文件不各写一套）；
 *   - 摘要请求（`RollingSummarizer` 指令头）回确定性六段摘要（L2/L3 的"摘要成功"面）；
 *   - `summary-500`  摘要请求 500：带标记的**普通轮** arm，其后的**下一条**摘要请求回 5xx（一次性）；
 *   - `context-overflow`  上游 400 + 越窗措辞（措辞取产品 `ContextOverflow.MARKERS` 的真短语）。
 *
 * 未带标记的请求一律走确定性文本轮（与官方桩 `turn-text` 同形态），帧序固定：
 * role 帧 → 分片 → finish_reason=stop → （usage 帧）→ [DONE]。
 *
 * <h2>落档契约（与官方桩逐键同形，E2E 用例只读这一份）</h2>
 * `<dir>/req-NNN.json`（请求原文）/ `.tools.txt`（disclosedCount + 披露工具名）/
 * `.head.txt`（消息摘要）/ `.decision.json`（本轮判定：kind/tool/args/route/reason）。
 *
 * 用法：`node kqc-upstream.mjs --port 19799 --dir <RUN_DIR>/stub`
 */
import fs from 'node:fs'
import http from 'node:http'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const argv = process.argv.slice(2)
function argOf(name, fallback) {
  const index = argv.indexOf(`--${name}`)
  return index >= 0 && argv[index + 1] !== undefined ? argv[index + 1] : fallback
}

const PORT = Number(argOf('port', '19799'))
const DIR = argOf('dir', path.resolve(process.cwd(), 'kqc-stub-dump'))
fs.mkdirSync(DIR, { recursive: true })

const ROUTES_PATH = argOf('routes', path.join(HERE, '..', '..', '..', 'scripts', 'ci', 'stub-routes.json'))
const LOG = (...parts) => fs.writeSync(1, `[kqc-upstream] ${parts.join(' ')}\n`)

/** 确定性文本轮的正文（用例只断言"非空且稳定"，文本本身不承载语义）。 */
const TURN_TEXT = 'KQC 桩文本：这是本域上游给出的确定性文本轮，用于验证上下文计量与快捷建议的确定性底座，'
  + '不依赖模型、不承载业务语义。'

const OVERFLOW_BODY = {
  error: {
    message: "This model's maximum context length is 200000 tokens. However, your messages resulted in 300001 tokens.",
    type: 'invalid_request_error',
    code: 'context_length_exceeded'
  }
}

// ── 请求解析 ─────────────────────────────────────────────────────────────────

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
  return users.length === 0 ? '' : textOf(users[users.length - 1])
}

/** 系统段正文（摘要指令头在这里；摘要请求的 user 段只是定界 transcript）。 */
function systemText(body) {
  return messagesOf(body)
    .filter((m) => m?.role === 'system')
    .map(textOf)
    .join('\n')
}

function disclosedTools(body) {
  const tools = Array.isArray(body?.tools) ? body.tools : []
  return tools.map((tool) => tool?.function?.name ?? tool?.name ?? '').filter(Boolean)
}

const REQUEST_TOOL_MARKER = /\btool=([A-Za-z_][A-Za-z0-9_]{0,63})\b/

/** 平衡括号取 JSON（允许嵌套与字符串里的 `}`；不平衡返回 null）。 */
function balancedJson(text, from) {
  let depth = 0
  let inString = false
  let escaped = false
  for (let i = from; i < text.length; i++) {
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

function requestToolOf(body) {
  const text = lastUserText(body)
  if (!text) return null
  const match = REQUEST_TOOL_MARKER.exec(text)
  if (!match) return null
  const name = match[1]
  const rest = text.slice(match.index + match[0].length)
  if (!/^&args=/.test(rest)) return { name, args: {} }
  const from = rest.indexOf('{', 5)
  if (from < 0) return { name, args: {}, badArgs: '缺少 JSON 对象' }
  const json = balancedJson(rest, from)
  if (json === null) return { name, args: {}, badArgs: 'JSON 括号不平衡' }
  let parsed
  try {
    parsed = JSON.parse(json)
  } catch (error) {
    return { name, args: {}, badArgs: `JSON 解析失败：${error.message}` }
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { name, args: {}, badArgs: 'JSON 不是对象' }
  }
  return { name, args: parsed }
}

const USAGE_SHAPE_MARKER = /\busage-shape=(last|repeat|zero-last|none)\b/

/**
 * usage 形状是**用例级**开关，跟着用例消息走 —— 不是"最后一条 user"。
 *
 * 轮内上下文预算（`SpToolCallingManager.TURN_BUDGET_WRAP_UP`）会在工具结果之后追加一条
 * 自己的收尾指令（role=user），那一跳的末条 user 里没有标记；只看末条会让续轮掉回 `last`
 * （prompt=1200），而轮首压缩的判据值取的就是"上一跳的 usage 真值" ⇒ 分母被算成
 * 1200/6000 ≈ 0.21，L1 再也不触发。故从末条往前找，取最近一条带标记的 user 消息。
 */
function usageShapeOf(body) {
  const users = messagesOf(body).filter((message) => message?.role === 'user').map(textOf)
  for (let index = users.length - 1; index >= 0; index -= 1) {
    const match = USAGE_SHAPE_MARKER.exec(users[index] ?? '')
    if (match) return match[1]
  }
  return 'last'
}

/** usage 数值唯一取数点 = `scripts/ci/stub-routes.json` 的 `usageShapes`。 */
function usageShapes() {
  try {
    const parsed = JSON.parse(fs.readFileSync(ROUTES_PATH, 'utf8'))
    return parsed.usageShapes ?? {}
  } catch (error) {
    LOG(`读 usageShapes 失败（回落到内建基线）：${error.message}`)
    return {}
  }
}

function usageOf(shape) {
  const prompt = Number(shape?.promptTokens ?? 1200)
  const completion = Number(shape?.completionTokens ?? 40)
  const total = Number(shape?.totalTokens ?? prompt + completion)
  const usage = { prompt_tokens: prompt, completion_tokens: completion, total_tokens: total }
  if (Number.isFinite(Number(shape?.cachedTokens))) usage.prompt_tokens_details = { cached_tokens: Number(shape.cachedTokens) }
  if (Number.isFinite(Number(shape?.reasoningTokens))) usage.completion_tokens_details = { reasoning_tokens: Number(shape.reasoningTokens) }
  return usage
}

// ── 落档（与官方桩同形）──────────────────────────────────────────────────────

let requestSeq = 0

function headLines(messages) {
  return messages
    .map((message) => {
      const content = textOf(message).replace(/\s+/g, ' ').trim()
      const toolCalls = Array.isArray(message?.tool_calls)
        ? ` tool_calls=[${message.tool_calls.map((call) => call?.function?.name).join(',')}]`
        : ''
      return `${message?.role ?? '?'}: ${content.slice(0, 120)}${toolCalls}`
    })
    .join('\n')
}

function record(index, raw, body, decision) {
  const tag = String(index).padStart(3, '0')
  fs.writeFileSync(path.join(DIR, `req-${tag}.json`), raw, 'utf8')
  const tools = disclosedTools(body)
  fs.writeFileSync(path.join(DIR, `req-${tag}.tools.txt`), `disclosedCount=${tools.length}\n${tools.join('\n')}\n`, 'utf8')
  fs.writeFileSync(path.join(DIR, `req-${tag}.head.txt`), headLines(messagesOf(body)), 'utf8')
  fs.writeFileSync(path.join(DIR, `req-${tag}.decision.json`), JSON.stringify(decision, null, 2), 'utf8')
}

// ── SSE 帧 ───────────────────────────────────────────────────────────────────

let frameSeq = 0

function chunk(delta, finishReason = null, model = 'kqc-stub', usage = null) {
  const body = {
    id: `chatcmpl-kqc-${++frameSeq}`,
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{ index: 0, delta, finish_reason: finishReason }]
  }
  if (usage) body.usage = usage
  return `data: ${JSON.stringify(body)}\n\n`
}

function usageChunk(usage, model) {
  return `data: ${JSON.stringify({
    id: `chatcmpl-kqc-${++frameSeq}`,
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [],
    usage
  })}\n\n`
}

const ZERO_USAGE = { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 }

/**
 * 非流式补全回执（`POST /chat/completions` 不带 `stream`）。
 *
 * 为什么摘要请求必须走这里：`ContextSummaryClient` 用 `ChatModel#call`（**非流式**），
 * Spring AI 按 `chat.completion` 的 JSON 解析；回 SSE 帧会解析失败 ⇒ 摘要永远失败
 * （实测：C-13 的 L2 卡在 L1、熔断 3 次打开）。
 */
function completion(res, text, model) {
  const usage = usageOf(usageShapes().last)
  json(res, 200, {
    id: `chatcmpl-kqc-${++frameSeq}`,
    object: 'chat.completion',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{ index: 0, message: { role: 'assistant', content: text }, finish_reason: 'stop' }],
    usage
  })
}

/**
 * 文本轮的四种 usage 落点（与官方桩 `textRound` 同语义）：
 * last = 只在末个 usage-only 帧；repeat = 每个分片都挂；zero-last = 首片真值 + 末帧 0/0/0；none = 不发。
 */
function textFrames(text, model, shapeName) {
  const shapes = usageShapes()
  const name = ['last', 'repeat', 'zero-last', 'none'].includes(shapeName) ? shapeName : 'last'
  const usage = name === 'none' ? null : usageOf(shapes[name])
  const out = [chunk({ role: 'assistant', content: '' }, null, model)]
  const pieces = String(text).match(/[\s\S]{1,24}/g) || [String(text)]
  pieces.forEach((piece, index) => {
    const attach = usage && (name === 'repeat' || (name === 'zero-last' && index === 0))
    out.push(attach ? chunk({ content: piece }, null, model, usage) : chunk({ content: piece }, null, model))
  })
  out.push(chunk({}, 'stop', model))
  if (usage) out.push(usageChunk(name === 'zero-last' ? ZERO_USAGE : usage, model))
  out.push('data: [DONE]\n\n')
  return out
}

function toolFrames(tool, args, callId, model) {
  return [
    chunk({ role: 'assistant', content: '' }, null, model),
    chunk({
      tool_calls: [{
        index: 0,
        id: callId,
        type: 'function',
        function: { name: tool, arguments: JSON.stringify(args) }
      }]
    }, null, model),
    chunk({}, 'tool_calls', model),
    'data: [DONE]\n\n'
  ]
}

// ── 判定 ─────────────────────────────────────────────────────────────────────

const KB_TOOLS = new Set(['kb_search', 'kb_read_doc', 'kb_lookup_term'])

/**
 * 压缩档（C 域）额外放行的**唯一**一个业务工具。
 *
 * 压缩是上下文机制，与工具语义无关：只要"真后端真执行过一条工具结果"就够。
 * 之所以不复用 KB 三工具：L-87 合并后 `KbTools` 经 `ToolResultLimiter` 反向依赖 `ToolRegistry`，
 * 而 `ToolRegistry` 的 `@PostConstruct` 扫描又在 `getBean` 这三件工具 —— 启动期循环依赖被那段
 * `catch (Exception) { continue; }` 静默吞掉，三工具**整批消失**（37 → 34 个工具；见施工报告 B-KB-1）。
 * 压缩谱系用例不该把判据绑在另一条缺陷上，故 C 域压缩档走本仓恒常披露的只读工具 `get_config_def`。
 */
const C_DOMAIN_TOOLS = new Set(['get_config_def'])

/**
 * 摘要请求识别（`RollingSummarizer` 的两段固定指令头；与产品提示词同源，不猜调用方身份）。
 *
 * 为什么必须显式区分：摘要请求的正文里带着 transcript（含历史 user 消息的 `tool=` 标记），
 * 若按普通轮判定会把它当成"再来一次工具调用"，摘要器拿到空正文 ⇒ 被产品判成摘要失败。
 * 这里的回执必须是**确定性非空文本**，L2 的"摘要成功"路径才有可断言的面。
 *
 * 指令头在 **system 段**（`ContextSummaryClient.production` 固定发 SystemMessage + UserMessage）；
 * user 段只是不可信的定界 transcript。用 user 段识别会永远不命中，且 transcript 里的历史标记
 * 会先把请求路由成普通工具轮 —— 非流式请求收到 SSE 帧 ⇒ 产品侧解析失败 ⇒ 摘要恒失败。
 */
const SUMMARY_REQUEST = /You (summarize an AI assistant conversation|merge partial summaries of one AI assistant conversation)/

/**
 * 摘要回执（六项保留清单与 `RollingSummarizer.REQUIRED_SECTIONS` 同序；文本确定性）。
 *
 * 必须闭合在 `<conversation_summary>…</conversation_summary>` 内：产品侧
 * `ContextSummaryClient#requireClosedSummary` 要求块外无文字，裸正文会按失败处理。
 */
const SUMMARY_OPEN = '<conversation_summary>'

const SUMMARY_CLOSE = '</conversation_summary>'

const SUMMARY_TEXT = [
  SUMMARY_OPEN,
  '（压缩摘要 · 桩）',
  '1) USER GOAL: 反复核对标准费率口径并取工具结果作答。',
  '2) CONFIRMED WORKSPACE AND PAGE OPERATIONS: 任务中心，未做任何页面写操作。',
  '3) EXECUTED IRREVERSIBLE ACTIONS AND RESOURCE IDS: none。',
  '4) UNRESOLVED ERRORS AND NEXT STEPS: none，继续按用户问题作答。',
  '5) PENDING APPROVALS (toolCallId + tool name + argument digest): none。',
  '6) COMPLETED - DO NOT REDO: 已完成的工具查询不再重跑。',
  SUMMARY_CLOSE
].join('\n')

/**
 * `summary-500` 的 arm 状态：mark 命中后 **5 秒内 / 最多 8 次**摘要请求一律回 500。
 *
 * <p>为什么不是"一次性"：Spring AI 的 `ChatModel#call` 自带重试（5xx 会再试），只 500 一次
 * 会被重试吃成"摘要成功"（实测：C-10 的熔断计数恒 0、档位直接进 L2）。窗口 + 次数双上限
 * 既保证这一轮的所有尝试都失败，又不会把后续轮次一起毒死。
 */
const summaryFault = { armedUntilMs: 0, remaining: 0 }

const SUMMARY_FAULT_MARKER = /\bsummary-500\b/

function decide(body) {
  const userText = lastUserText(body)
  if (!userText) {
    return { kind: 'text', text: TURN_TEXT, reason: '无用户消息（探活/异常请求）' }
  }
  const wanted = requestToolOf(body)
  if (wanted) {
    if (!KB_TOOLS.has(wanted.name) && !C_DOMAIN_TOOLS.has(wanted.name)) {
      return { kind: 'text', text: TURN_TEXT, reason: `请求级工具 ${wanted.name} 不属于本域（K 域只承载 KB 三工具）` }
    }
    if (!disclosedTools(body).includes(wanted.name)) {
      return { kind: 'text', text: TURN_TEXT, reason: `请求级工具 ${wanted.name} 未在本轮披露清单（requiresTools 闸）` }
    }
    if (wanted.badArgs) {
      return { kind: 'text', text: TURN_TEXT, reason: `请求级工具 ${wanted.name} 入参不可解：${wanted.badArgs}` }
    }
    // 续轮判据只看**最后一条消息**：历史里早先的 role=tool 不算本轮（同一会话第二轮仍要能发起调用）
    if (messagesOf(body).length > 0 && messagesOf(body)[messagesOf(body).length - 1]?.role === 'tool') {
      return { kind: 'text', text: '已按知识库工具结果作答（本域桩不模拟模型措辞）。', reason: '工具续轮（最后一条是 role=tool）→ 文本收尾' }
    }
    return {
      kind: 'tool_call',
      tool: wanted.name,
      args: wanted.args,
      route: 'KQC-REQUEST-TOOL',
      reason: `请求级工具 ${wanted.name}（披露闸通过）`
    }
  }
  return { kind: 'text', text: TURN_TEXT, reason: '无标记 → 确定性文本轮' }
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

/** 帧间静默（`stall-frames=<ms>`）：首帧立即写，其后每帧前等 ms（与官方桩同语义）。 */
function ssePaced(res, frames, gapMs) {
  res.writeHead(200, {
    'content-type': 'text/event-stream; charset=utf-8',
    'cache-control': 'no-cache',
    connection: 'keep-alive'
  })
  let index = 0
  const step = () => {
    if (index >= frames.length) {
      res.end()
      return
    }
    res.write(frames[index])
    index += 1
    setTimeout(step, gapMs)
  }
  step()
}

const STALL_FRAMES_MARKER = /\bstall-frames=(\d+)\b/

function stallFramesOf(body) {
  const match = STALL_FRAMES_MARKER.exec(lastUserText(body) ?? '')
  return match ? Number(match[1]) : 0
}

function handleChat(raw, res) {
  const index = ++requestSeq
  let body = null
  try {
    body = JSON.parse(raw)
  } catch {
    body = null
  }
  const model = typeof body?.model === 'string' && body.model ? body.model : 'kqc-stub'
  const userText = body ? lastUserText(body) : ''
  const system = body ? systemText(body) : ''
  const shape = usageShapeOf(body)
  // 摘要请求：LLM 侧旁路调用（`ContextSummaryClient` 固定 SystemMessage 指令头 + UserMessage transcript）
  const summaryRequest = SUMMARY_REQUEST.test(system)

  if (/\bcontext-overflow\b/.test(userText) && !summaryRequest) {
    record(index, raw, body ?? {}, { kind: 'error', status: 400, reason: 'context-overflow 注入（越窗 400）' })
    LOG(`req-${index} context-overflow → 400`)
    json(res, 400, OVERFLOW_BODY)
    return
  }

  if (summaryRequest) {
    // `summary-500`：arm 窗口内（5s / 8 次）摘要请求一律 500 —— 覆盖 Spring AI 的 5xx 重试，
    // 否则第二次尝试会成功、熔断计数留不下痕（arm 由带标记的普通轮写入：压缩发生在轮起点，
    // 故 arm 必须早一轮，否则标记还没到上游、摘要请求已经发出）
    if (summaryFault.remaining > 0 && summaryFault.armedUntilMs > Date.now()) {
      summaryFault.remaining -= 1
      record(index, raw, body ?? {}, { kind: 'error', status: 500, reason: 'summary-500 注入（摘要请求回 5xx）' })
      LOG(`req-${index} summary-500 → 500（余 ${summaryFault.remaining}）`)
      json(res, 500, { error: { message: 'kqc-stub: summary model unavailable', type: 'server_error' } })
      return
    }
    record(index, raw, body ?? {}, { kind: 'summary', reason: '摘要请求（RollingSummarizer 指令头命中）' })
    LOG(`req-${index} → summary`)
    completion(res, SUMMARY_TEXT, model)
    return
  }
  if (SUMMARY_FAULT_MARKER.test(userText)) {
    summaryFault.armedUntilMs = Date.now() + 5000
    summaryFault.remaining = 8
    LOG(`req-${index} summary-500 armed`)
  }

  const decision = body === null
    ? { kind: 'text', text: TURN_TEXT, reason: '请求体不是合法 JSON（退化为文本）' }
    : decide(body)
  if (shape !== 'last') decision.usageShape = shape
  record(index, raw, body ?? {}, decision)
  LOG(`req-${index} → ${decision.kind}${decision.tool ? `:${decision.tool}` : ''} shape=${shape}`)

  if (decision.kind === 'tool_call') {
    sse(res, toolFrames(decision.tool, decision.args, `call-kqc-${index}-${Date.now()}`, model))
    return
  }
  const frames = textFrames(decision.text ?? TURN_TEXT, model, shape)
  const gap = body ? stallFramesOf(body) : 0
  if (gap > 0) {
    ssePaced(res, frames, gap)
    return
  }
  sse(res, frames)
}

const server = http.createServer((req, res) => {
  req.on('error', (error) => LOG(`请求流错误：${error.message}`))
  res.on('error', (error) => LOG(`响应流错误：${error.message}`))
  if ((req.url ?? '').startsWith('/probe')) {
    json(res, 200, { ok: true, stub: 'kqc-upstream', dir: DIR })
    return
  }
  const chunks = []
  req.on('data', (piece) => chunks.push(piece))
  req.on('end', () => {
    const raw = Buffer.concat(chunks).toString('utf8')
    if (req.method === 'POST' && (req.url ?? '').includes('/chat/completions')) {
      try {
        handleChat(raw, res)
      } catch (error) {
        LOG(`处理失败（进程存活）：${error?.stack ?? error}`)
        try {
          json(res, 500, { error: { message: 'kqc-upstream-failed' } })
        } catch {
          /* 响应已结束 */
        }
      }
      return
    }
    json(res, 404, { error: { message: `kqc-upstream 无此路径：${req.method} ${req.url}` } })
  })
})

server.on('error', (error) => {
  fs.writeSync(2, `[kqc-upstream] 致命：${error?.stack ?? error}\n`)
  process.exit(1)
})
server.listen(PORT, '127.0.0.1', () => LOG(`就绪 http://127.0.0.1:${PORT} dir=${DIR}`))
