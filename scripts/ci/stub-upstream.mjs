#!/usr/bin/env node
/**
 * E2E 门禁专用「上游替身」（M2-T1.2；设计草案 §3.2/§5.1/§5.2）。
 *
 * 作用：CI 内替代真实模型，让 `scripts/verify-e2e.ps1` 的 27 个用例在**零密钥、零外网**下确定性通过。
 * 后端只需 `AI_BASE_URL=http://127.0.0.1:<port>` + 任意非空 `AI_API_KEY` 占位串即可把上游整体指向本桩
 * （后端的可用性判据只看 key 是否非空，不看 key 真假，见 ai/config/AiAvailability）。
 *
 * 用途边界（重要）：
 *   - 本桩是**产品链路的替身**，不是模型行为的替身。CI 全绿只证明「桩给出的表单/工具调用被产品链路正确
 *     消费」，**不证明模型服从率**（设计草案 §4.3：GFSKIP=0 在桩下由构造保证，属恒真式而非观测）。
 *   - 真模型的 E2E 维持本地手动跑（S5d 裁决 #3）。
 *
 * 零依赖：只用 Node 内置模块（self-check 会断言这一点），单文件可直接 `node scripts/ci/stub-upstream.mjs`。
 *
 * 收敛契约（设计草案 §3.3，硬要求——后端工具循环<b>无迭代上限</b>，桩必须自证终止）：
 *   ① **披露清单驱动**：目标工具必须出现在本请求 `tools[]` 里才允许发起调用，否则退化为文本轮
 *      （避免"调用未披露工具 → 后端以 SCOPE_NOT_DISCLOSED 回灌 → 桩再调同一个 → 请求风暴"）；
 *   ② **每个会话轮次的上游调用上限**（`convergence.maxCallsPerTurn`，默认 6）：越界只发文本；
 *   ③ **续轮只发文本**：上下文中已有 `role=tool` 消息时不再发起工具调用；
 *   ④ **解析失败不猜**：任何识别/解析失败一律以文本收尾（宁可让用例 FAIL，也不制造请求风暴）。
 *   `--self-check` 用"首轮→工具调用、续轮→文本"的模拟循环逐条证明上述四条成立。
 *
 * 记录契约（可归因性，设计草案 §5.2.4）：每请求落 `<dir>/req-NN.json`（请求原文）、
 * `.tools.txt`（本请求实际披露的工具名清单）、`.head.txt`（模型可见消息摘要）、
 * `.decision.json`（桩本轮的判定：路由 id / 工具名 / 理由）——"桩坏 / 产品坏"的区分依据。
 *
 * 用法：
 *   node scripts/ci/stub-upstream.mjs --port 18399 --dir <dir> [--routes <stub-routes.json>]
 *   node scripts/ci/stub-upstream.mjs --self-check [--routes <stub-routes.json>]
 *   [--violate-form-keys | --violate-form-type]   # 违规模式，默认关闭（裁决 #8，仅缺陷注入时开启）
 */

import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))

// ── 命令行 ─────────────────────────────────────────────────────────────────

const argv = process.argv.slice(2)

function arg(name, fallback) {
  const i = argv.indexOf(`--${name}`)
  return i >= 0 && argv[i + 1] !== undefined && !argv[i + 1].startsWith('--') ? argv[i + 1] : fallback
}

function flag(name) {
  return argv.includes(`--${name}`)
}

const PORT = Number(arg('port', '18399'))
const DIR = arg('dir', path.resolve(process.cwd(), 'stub-dump'))
const ROUTES_PATH = arg('routes', path.join(HERE, 'stub-routes.json'))
const SELF_CHECK = flag('self-check')
const VIOLATE_FORM_KEYS = flag('violate-form-keys')
const VIOLATE_FORM_TYPE = flag('violate-form-type')

// ── 生成式表单白名单：以下 8 组常量 + 5 项上限是产品事实源的【手工副本】───────────
// 事实源：backend/src/main/java/com/example/configmgr/ai/form/GenerativeFormRules.java
//   （常量见该类 :57-115；上限 MAX_FIELDS/MAX_OPTIONS/MAX_TITLE_CHARS/MAX_LABEL_CHARS/
//     MAX_KEY_CHARS/MAX_OPTION_TEXT_CHARS/MAX_PLACEHOLDER_CHARS/MAX_FORM_CHARS）
// 副本清单（8 组常量）：FIELD_TYPES / SCENARIOS / FORM_ATTRIBUTES / FIELD_ATTRIBUTES /
//   OPTION_ATTRIBUTES / PLACEHOLDER_TYPES / FORBIDDEN_KEYS / KEY_PATTERN
// 副本清单（5 项上限）：MAX_TITLE_CHARS=100 / MAX_OPTIONS=50 / MAX_OPTION_TEXT_CHARS=100（且选项值不得重复）
//   / MAX_PLACEHOLDER_CHARS=100 / MAX_FORM_CHARS=8000（form 序列化长度）
// ⚠ 产品侧收紧/改动白名单（新增禁属性、改正则、调上限）时，必须同步对账本副本，否则
//   自检③仍绿而桩在运行期才以 FORM_SCHEMA_REJECTED → SKIP → 门禁红的形式暴露失配
//   （设计草案 R5/R9；事实源自动对账列为 M2-T4 子项）。
const FORBIDDEN_KEYS = ['__proto__', 'constructor', 'prototype']
const FIELD_TYPES = ['text', 'number', 'boolean', 'date', 'enum', 'multi_select']
const SCENARIOS = ['FILTER', 'CLARIFY']
const FORM_ATTRIBUTES = ['scenario', 'title', 'fields']
const FIELD_ATTRIBUTES = ['key', 'label', 'type', 'required', 'defaultValue', 'options', 'placeholder']
const OPTION_ATTRIBUTES = ['value', 'label']
const PLACEHOLDER_TYPES = ['text', 'number', 'date']
const KEY_PATTERN = /^[A-Za-z_][A-Za-z0-9_]{0,39}$/

const MAX_TITLE_CHARS = 100
const MAX_OPTIONS = 50
const MAX_OPTION_TEXT_CHARS = 100
const MAX_PLACEHOLDER_CHARS = 100
const MAX_FORM_CHARS = 8000

// ── 路由表 ─────────────────────────────────────────────────────────────────

function loadRoutes(file) {
  const raw = fs.readFileSync(file, 'utf8')
  const parsed = JSON.parse(raw)
  if (!parsed || typeof parsed !== 'object') throw new Error('路由表不是 JSON 对象')
  return parsed
}

/** 深度扫描敏感/危险键（撤销原型污染的入口，路由表里出现即视为缺陷）。 */
function forbiddenKeyPaths(value, trail = '$', out = []) {
  if (value === null || typeof value !== 'object') return out
  for (const key of Object.keys(value)) {
    if (FORBIDDEN_KEYS.includes(key)) out.push(`${trail}.${key}`)
    forbiddenKeyPaths(value[key], `${trail}.${key}`, out)
  }
  return out
}

/**
 * 违规模式（默认关闭）：故意把合法桩改成"坏桩"，用于 S7 的归因链路验证。
 * 两种形态刻意对应两条不同的产品侧分支：
 *   - `--violate-form-keys`：FILTER 模板缺字段（schema 漂移）→ 脚本记 SKIP；
 *   - `--violate-form-type`：字段类型越白名单（html）→ 产品入参闸门拒绝（REJECTED_ARGUMENTS）。
 */
function applyViolation(routes) {
  if (!VIOLATE_FORM_KEYS && !VIOLATE_FORM_TYPE) return []
  const applied = []
  for (const route of routes.routes || []) {
    if (route.id !== 'GF-FILTER') continue
    const fields = route.arguments?.form?.fields
    if (!Array.isArray(fields)) continue
    if (VIOLATE_FORM_KEYS) {
      route.arguments.form.fields = fields.filter((f) => f.key === 'keyword' || f.key === 'scope')
      applied.push('violate-form-keys：GF-FILTER 丢弃 minRows/effectiveDate/amount')
    }
    if (VIOLATE_FORM_TYPE) {
      const target = fields.find((f) => f.key === 'scope')
      if (target) {
        target.type = 'html'
        applied.push('violate-form-type：GF-FILTER 的 scope 字段类型改为 html（越白名单）')
      }
    }
  }
  return applied
}

// ── 请求解析辅助 ───────────────────────────────────────────────────────────

function safeJson(text) {
  try {
    return JSON.parse(text)
  } catch {
    return undefined
  }
}

function toolNames(body) {
  const tools = Array.isArray(body?.tools) ? body.tools : []
  return tools.map((t) => t?.function?.name).filter((n) => typeof n === 'string' && n.length > 0)
}

function messagesOf(body) {
  return Array.isArray(body?.messages) ? body.messages : []
}

function lastMessageWithRole(messages, role) {
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i]?.role === role) return messages[i]
  }
  return null
}

function textOf(message) {
  if (!message) return ''
  const content = message.content
  if (typeof content === 'string') return content
  if (Array.isArray(content)) {
    return content.map((p) => (typeof p === 'string' ? p : p?.text ?? '')).join('')
  }
  return ''
}

/**
 * 工具结果内容的 JSON 反解（设计草案 §2.4 的形状细节）：内容可能是对象、被序列化过一层的字符串、
 * 或"前缀文字 + JSON"的混合体——故先按 JSON 解（最多两层），再用括号配对取首个平衡的 `{...}`。
 */
function extractJsonObject(raw) {
  if (raw === null || raw === undefined) return null
  if (typeof raw === 'object') return Array.isArray(raw) ? null : raw
  if (typeof raw !== 'string') return null
  let value = raw.trim()
  for (let depth = 0; depth < 2; depth++) {
    const parsed = safeJson(value)
    if (parsed === undefined) break
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return parsed
    if (typeof parsed === 'string') {
      value = parsed.trim()
      continue
    }
    break
  }
  const start = value.indexOf('{')
  if (start < 0) return null
  let depth = 0
  let inString = false
  let escape = false
  for (let i = start; i < value.length; i++) {
    const ch = value[i]
    if (escape) {
      escape = false
      continue
    }
    if (ch === '\\') {
      escape = true
      continue
    }
    if (ch === '"') {
      inString = !inString
      continue
    }
    if (inString) continue
    if (ch === '{') depth++
    else if (ch === '}') {
      depth--
      if (depth === 0) {
        const candidate = safeJson(value.slice(start, i + 1))
        return candidate && typeof candidate === 'object' && !Array.isArray(candidate) ? candidate : null
      }
    }
  }
  return null
}

/** 从用户消息里取任务号：优先"任务 <n>"，退化为首个数字串。 */
function extractTaskId(text) {
  const explicit = /任务\s*(\d+)/.exec(text || '')
  if (explicit) return Number(explicit[1])
  const loose = /(\d+)/.exec(text || '')
  return loose ? Number(loose[1]) : null
}

/** 占位符替换：整串 `${taskId}` 换成数字，否则按字符串插值。 */
function resolveArguments(value, vars) {
  if (typeof value === 'string') {
    const whole = /^\$\{(\w+)\}$/.exec(value)
    if (whole) return vars[whole[1]] ?? null
    return value.replace(/\$\{(\w+)\}/g, (_, k) => String(vars[k] ?? ''))
  }
  if (Array.isArray(value)) return value.map((v) => resolveArguments(v, vars))
  if (value && typeof value === 'object') {
    const out = {}
    for (const [k, v] of Object.entries(value)) out[k] = resolveArguments(v, vars)
    return out
  }
  return value
}

// ── 判定（桩的大脑；纯函数，供 --self-check 直接调用） ─────────────────────

/** 会话指纹：首条 user 消息（同一提示词的重试会在 messages 长度回退时被识别为新轮次）。 */
function fingerprint(messages) {
  const first = messages.find((m) => m?.role === 'user')
  return (textOf(first) || '(空)').slice(0, 160)
}

function formatValue(value) {
  if (Array.isArray(value)) return value.map((v) => formatValue(v)).join(', ')
  if (typeof value === 'boolean') return value ? 'true' : 'false'
  if (value === null || value === undefined) return ''
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

const CLOSING_TEXT = '好的，本轮已完成，请查看上方的结果。'

/** 续轮的文本收尾：把最后一次工具结果里的值按 key=值 原样回报（满足 Gf-Assert-Echo）。 */
function echoText(messages) {
  const toolMessage = lastMessageWithRole(messages, 'tool')
  const parsed = extractJsonObject(textOf(toolMessage))
  if (!parsed) return CLOSING_TEXT
  const keys = Object.keys(parsed)
  if (keys.length === 0) return CLOSING_TEXT
  const lines = ['已按 key=值 原样回报：']
  for (const key of keys) lines.push(`${key}=${formatValue(parsed[key])}`)
  return lines.join('\n')
}

/**
 * 本轮判定。返回 `{kind, ...}`；调用方只认 `tool_call` 与 `text` 两种。
 *
 * @param body    上游请求体（OpenAI 兼容）
 * @param routes  路由表
 * @param turns   会话轮次台账（跨请求累积；收敛契约的载体）
 * @param overrides 自检用的注入项（{messages, tools}），不传则取自 body
 */
function decide(body, routes, turns, overrides = {}) {
  const messages = overrides.messages ?? messagesOf(body)
  const disclosed = new Set(overrides.tools ?? toolNames(body))
  const maxCalls = Number(routes.convergence?.maxCallsPerTurn ?? 6)

  const fp = fingerprint(messages)
  let bucket = turns.get(fp)
  if (!bucket) {
    bucket = { calls: 0, lastLen: -1, used: [] }
    turns.set(fp, bucket)
  }
  // 同一提示词的新轮次（重试/换会话）：**仅当消息数严格回退**时才视为新轮次并清零台账。
  // 等长（后端 ResilientChatService 流失败后原样重发同一请求的真实形态）不得清零——
  // 否则 calls 与 used 双双重置，轮次上限与"每路由一次"两道闸会被系统性绕过（红队问题 1）。
  if (messages.length < bucket.lastLen) {
    bucket.calls = 0
    bucket.used = []
  }
  bucket.lastLen = messages.length
  bucket.calls += 1

  const hasToolResult = messages.some((m) => m?.role === 'tool')
  if (hasToolResult) {
    // 收敛契约 ③：上下文中已有工具结果 = 续轮，只发文本
    return { kind: 'text', text: echoText(messages), reason: '续轮（上下文中已有工具结果）' }
  }
  if (bucket.calls > maxCalls) {
    // 收敛契约 ②：轮次上限兜底
    return { kind: 'text', text: CLOSING_TEXT, reason: `超出轮次上限 ${maxCalls}` }
  }
  const userText = textOf(lastMessageWithRole(messages, 'user'))
  if (!userText) {
    return { kind: 'text', text: CLOSING_TEXT, reason: '无用户消息（探活/异常请求）' }
  }
  for (const route of routes.routes || []) {
    const requires = Array.isArray(route.match?.requiresTools) ? route.match.requiresTools : []
    // 收敛契约 ①：披露清单先行——未披露的工具不允许发起调用
    if (requires.length === 0 || !requires.every((t) => disclosed.has(t))) continue
    const needles = Array.isArray(route.match?.messageAny) ? route.match.messageAny : []
    if (!needles.some((n) => userText.includes(n))) continue
    if (bucket.used.includes(route.id)) continue
    let args
    try {
      args = resolveArguments(route.arguments ?? {}, { taskId: extractTaskId(userText) })
    } catch (err) {
      // 收敛契约 ④：解析失败不猜，退化为文本
      return { kind: 'text', text: CLOSING_TEXT, reason: `路由 ${route.id} 参数解析失败：${err.message}` }
    }
    bucket.used.push(route.id)
    return { kind: 'tool_call', tool: route.tool, args, route: route.id, reason: `命中路由 ${route.id}` }
  }
  return { kind: 'text', text: CLOSING_TEXT, reason: '未命中任何路由（文本收尾）' }
}

// ── SSE 帧 ─────────────────────────────────────────────────────────────────

let frameSeq = 0

function chunk(delta, finishReason = null, model = 'ci-stub') {
  return `data: ${JSON.stringify({
    id: `chatcmpl-stub-${++frameSeq}`,
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{ index: 0, delta, finish_reason: finishReason }]
  })}\n\n`
}

/** 文本轮：分片 content → finish_reason=stop → usage 帧 → [DONE]（硬规范②：必须有终帧）。 */
function textRound(text, model) {
  const out = [chunk({ role: 'assistant', content: '' }, null, model)]
  const pieces = String(text).match(/[\s\S]{1,24}/g) || [String(text)]
  for (const piece of pieces) out.push(chunk({ content: piece }, null, model))
  out.push(chunk({}, 'stop', model))
  out.push(`data: ${JSON.stringify({
    id: `chatcmpl-stub-${++frameSeq}`,
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [],
    usage: {
      prompt_tokens: 1200,
      completion_tokens: 40,
      total_tokens: 1240,
      prompt_tokens_details: { cached_tokens: 640 },
      completion_tokens_details: { reasoning_tokens: 16 }
    }
  })}\n\n`)
  out.push('data: [DONE]\n\n')
  return out
}

/** 工具轮：单帧聚合 tool_calls（index=0 一次给全）→ finish_reason=tool_calls → [DONE]。 */
function toolRound(tool, args, callId, model) {
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

// ── 服务与落盘 ─────────────────────────────────────────────────────────────

const turns = new Map()
let requestSeq = 0

function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true })
}

function headLines(messages) {
  return messages.map((m, i) => {
    const text = textOf(m)
    const calls = Array.isArray(m?.tool_calls) ? m.tool_calls.map((c) => c?.function?.name).join(',') : ''
    const id = m?.tool_call_id ? ` tool_call_id=${m.tool_call_id}` : ''
    const toolCalls = calls ? ` tool_calls=[${calls}]` : ''
    return `[${i}] role=${m?.role}${id}${toolCalls} chars=${text.length}\n${text.slice(0, 300)}`
  }).join('\n\n')
}

function record(index, raw, body, decision) {
  const tag = String(index).padStart(3, '0')
  fs.writeFileSync(path.join(DIR, `req-${tag}.json`), raw, 'utf8')
  fs.writeFileSync(path.join(DIR, `req-${tag}.tools.txt`),
    `disclosedCount=${toolNames(body).length}\n${toolNames(body).join('\n')}\n`, 'utf8')
  fs.writeFileSync(path.join(DIR, `req-${tag}.head.txt`), headLines(messagesOf(body)), 'utf8')
  fs.writeFileSync(path.join(DIR, `req-${tag}.decision.json`),
    JSON.stringify(decision, null, 2), 'utf8')
}

function handleChat(req, res, raw) {
  const index = ++requestSeq
  const body = safeJson(raw)
  const model = typeof body?.model === 'string' && body.model ? body.model : 'ci-stub'
  let decision
  if (!body) {
    decision = { kind: 'text', text: CLOSING_TEXT, reason: '请求体不是合法 JSON（退化为文本）' }
  } else {
    try {
      decision = decide(body, ROUTES, turns)
    } catch (err) {
      decision = { kind: 'text', text: CLOSING_TEXT, reason: `判定异常：${err.message}` }
    }
  }
  record(index, raw, body ?? {}, decision)

  res.writeHead(200, {
    'content-type': 'text/event-stream; charset=utf-8',
    'cache-control': 'no-cache',
    connection: 'keep-alive'
  })
  const frames = decision.kind === 'tool_call'
    ? toolRound(decision.tool, decision.args, `call-stub-${index}`, model)
    : textRound(decision.text, model)
  try {
    for (const frame of frames) res.write(frame)
    res.end()
  } catch (err) {
    // 客户端在响应写出过程中断开（socket destroyed）属正常现象，不许升级为进程级异常
    fs.writeSync(2, `[WARN] 响应写出失败（客户端可能已断开）req-${index}：${err?.message ?? err}\n`)
  }
  console.log(`[req-${String(index).padStart(3, '0')}] ${decision.kind}${decision.tool ? ` tool=${decision.tool}` : ''} route=${decision.route ?? '-'} —— ${decision.reason}`)
}

/**
 * 崩溃兜底（红队问题 2）：`fs.writeSync` 直接写文件描述符，绕开可能丢失的流缓冲——
 * CI 上桩的 stdout/stderr 被重定向到文件，若走 process.stdout.write 的异步路径，
 * 进程被击杀时崩溃栈可能不落盘，导致"桩死无对证"（表现为 boot.log 里的 Connection refused）。
 * 同时把栈同步追写一份到 `<dir>/stub-crash.log`，随 artifact 一起归档。
 */
function crashReport(kind, err) {
  const stack = err && err.stack ? err.stack : String(err)
  const text = `\n[FATAL] ${kind} ${new Date().toISOString()}\n${stack}\n`
  try {
    fs.writeSync(2, text)
  } catch {
    /* ignore */
  }
  try {
    fs.writeSync(fs.openSync(path.join(DIR, 'stub-crash.log'), 'a'), text)
  } catch {
    /* ignore */
  }
  process.exit(1)
}

function startServer() {
  ensureDir(DIR)
  // 进程级兜底：任何未捕获异常/未处理拒绝都必须留下栈并以退出码 1 死亡（不许静默消失）
  process.on('uncaughtException', (err) => crashReport('uncaughtException', err))
  process.on('unhandledRejection', (reason) => crashReport('unhandledRejection', reason))

  const server = http.createServer((req, res) => {
    // 连接级兜底：客户端中途断开/重置属正常攻击面，只记日志，绝不升级为进程级异常
    req.on('error', (err) => fs.writeSync(2, `[WARN] 请求流错误：${err?.message ?? err}\n`))
    res.on('error', (err) => fs.writeSync(2, `[WARN] 响应流错误：${err?.message ?? err}\n`))
    if (req.method !== 'POST') {
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end('{"ok":true,"stub":"ci-upstream"}')
      return
    }
    const chunks = []
    req.on('data', (c) => chunks.push(c))
    req.on('end', () => {
      try {
        const raw = Buffer.concat(chunks).toString('utf8')
        if (!(req.url ?? '').includes('/chat/completions')) {
          res.writeHead(200, { 'content-type': 'application/json' })
          res.end('{"ok":true}')
          return
        }
        handleChat(req, res, raw)
      } catch (err) {
        // 判定/落盘/写出任一步骤异常都不许杀死桩：回 500 并记栈，进程继续服务后续请求
        fs.writeSync(2, `[ERROR] 处理请求失败（进程存活）：${err?.stack ?? err}\n`)
        try {
          res.writeHead(500, { 'content-type': 'application/json' })
          res.end('{"error":"stub-handle-failed"}')
        } catch {
          /* ignore */
        }
      }
    })
  })
  // 监听失败（如 EADDRINUSE）必须显式留栈退出，不留"半死"状态
  server.on('error', (err) => crashReport('server.error', err))
  server.listen(PORT, '127.0.0.1', () => {
    console.log(`stub-upstream 已就绪 http://127.0.0.1:${PORT} dir=${DIR} routes=${ROUTES_PATH} maxCallsPerTurn=${ROUTES.convergence?.maxCallsPerTurn ?? 6}`)
    for (const note of violations) console.log(`[违规模式] ${note}`)
  })
}

// ── 自检（--self-check：契约校验 + 收敛自证，不启动服务） ─────────────────────

function selfCheck() {
  const problems = []
  let mark = 0
  const ok = (msg) => {
    if (problems.length === mark) console.log(`  [PASS] ${msg}`)
  }
  const bad = (msg) => problems.push(msg)
  const section = () => {
    mark = problems.length
  }

  // ① 零依赖：源码的 import 只允许 node: 内置模块
  const source = fs.readFileSync(new URL(import.meta.url), 'utf8')
  const imports = [...source.matchAll(/^import\s+[^'"]*from\s+'([^']+)'/gm)].map((m) => m[1])
  const nonBuiltin = imports.filter((s) => !s.startsWith('node:'))
  if (nonBuiltin.length) bad(`非内置依赖：${nonBuiltin.join(',')}`)
  else ok(`零运行时依赖（import 仅 ${imports.join(', ')}）`)

  section()
  // ② 路由表结构
  if (ROUTES.version !== 1) bad(`路由表 version=${ROUTES.version}，期望 1`)
  const productTools = Array.isArray(ROUTES.productTools) ? ROUTES.productTools : []
  if (productTools.length === 0) bad('productTools 为空')
  const forbidden = forbiddenKeyPaths(ROUTES)
  if (forbidden.length) bad(`路由表含禁键：${forbidden.join(', ')}`)
  else ok('路由表无 __proto__/constructor/prototype 键')
  const maxCalls = Number(ROUTES.convergence?.maxCallsPerTurn)
  if (!Number.isInteger(maxCalls) || maxCalls <= 0) bad(`convergence.maxCallsPerTurn=${ROUTES.convergence?.maxCallsPerTurn} 非法`)

  const ids = new Set()
  for (const route of ROUTES.routes || []) {
    if (!route.id || ids.has(route.id)) bad(`路由 id 缺失或重复：${route.id}`)
    ids.add(route.id)
    const requires = Array.isArray(route.match?.requiresTools) ? route.match.requiresTools : []
    if (requires.length === 0) bad(`路由 ${route.id} 的 requiresTools 为空（披露清单驱动被绕过）`)
    for (const t of requires) if (!productTools.includes(t)) bad(`路由 ${route.id} 的 requiresTools 含未知工具 ${t}`)
    if (!requires.includes(route.tool)) bad(`路由 ${route.id} 的目标工具 ${route.tool} 不在自身 requiresTools 内`)
    if (!productTools.includes(route.tool)) bad(`路由 ${route.id} 的目标工具 ${route.tool} 不在 productTools 内`)
    const needles = Array.isArray(route.match?.messageAny) ? route.match.messageAny : []
    if (needles.length === 0) bad(`路由 ${route.id} 的 messageAny 为空`)
  }
  ok(`路由表结构自洽（${(ROUTES.routes || []).length} 条路由，均含非空 requiresTools）`)

  section()
  // ③ 生成式表单模板与产品白名单逐字段对账（GenerativeFormRules）
  for (const route of (ROUTES.routes || []).filter((r) => r.tool === 'generative_form')) {
    const form = route.arguments?.form
    const at = `路由 ${route.id}.form`
    if (!form || typeof form !== 'object') {
      bad(`${at} 缺失`)
      continue
    }
    for (const name of Object.keys(form)) if (!FORM_ATTRIBUTES.includes(name)) bad(`${at} 含白名单外属性 ${name}`)
    if (!SCENARIOS.includes(form.scenario)) bad(`${at}.scenario=${form.scenario} 越白名单`)
    if (form.title !== undefined) {
      if (typeof form.title !== 'string') bad(`${at}.title 必须是文本`)
      else if (form.title.length > MAX_TITLE_CHARS) bad(`${at}.title 过长（>${MAX_TITLE_CHARS} 字符）`)
    }
    // form 序列化上限（产品侧对入参原文 JsonNode.toString() 计数，与此处紧凑 JSON 同口径）
    const formChars = JSON.stringify(form).length
    if (formChars > MAX_FORM_CHARS) bad(`${at} 序列化 ${formChars} 字符，超过上限 ${MAX_FORM_CHARS}`)
    const fields = Array.isArray(form.fields) ? form.fields : []
    if (fields.length < 1 || fields.length > 20) bad(`${at}.fields 数量 ${fields.length} 越界（1..20）`)
    const seen = new Set()
    for (const f of fields) {
      for (const name of Object.keys(f)) if (!FIELD_ATTRIBUTES.includes(name)) bad(`${at} 字段 ${f.key} 含白名单外属性 ${name}`)
      if (!KEY_PATTERN.test(f.key || '')) bad(`${at} 字段键 ${f.key} 不合法`)
      if (FORBIDDEN_KEYS.includes(f.key)) bad(`${at} 字段键 ${f.key} 是原型污染键`)
      if (seen.has(f.key)) bad(`${at} 字段键 ${f.key} 重复`)
      seen.add(f.key)
      if (!f.label || String(f.label).length > 100) bad(`${at} 字段 ${f.key} 的 label 缺失/过长`)
      if (!FIELD_TYPES.includes(f.type)) bad(`${at} 字段 ${f.key} 类型 ${f.type} 越白名单`)
      if ('required' in f && typeof f.required !== 'boolean') bad(`${at} 字段 ${f.key} 的 required 不是布尔`)
      if (f.placeholder !== undefined) {
        if (!PLACEHOLDER_TYPES.includes(f.type)) bad(`${at} 字段 ${f.key} 带了不该有的 placeholder`)
        else if (typeof f.placeholder !== 'string' || f.placeholder.length > MAX_PLACEHOLDER_CHARS) {
          bad(`${at} 字段 ${f.key} 的 placeholder 非文本或过长（>${MAX_PLACEHOLDER_CHARS} 字符）`)
        }
      }
      if (f.type === 'enum' || f.type === 'multi_select') {
        if (!Array.isArray(f.options) || f.options.length === 0) bad(`${at} 字段 ${f.key} 缺 options`)
        if (Array.isArray(f.options) && f.options.length > MAX_OPTIONS) {
          bad(`${at} 字段 ${f.key} 的 options ${f.options.length} 项，超过上限 ${MAX_OPTIONS}`)
        }
        const optionSeen = new Set()
        for (const o of f.options || []) {
          if (!o || typeof o !== 'object' || Array.isArray(o)) bad(`${at} 字段 ${f.key} 的选项不是对象形态`)
          else {
            for (const name of Object.keys(o)) if (!OPTION_ATTRIBUTES.includes(name)) bad(`${at} 字段 ${f.key} 的选项含白名单外属性 ${name}`)
            const value = typeof o.value === 'string' ? o.value : ''
            if (!value) bad(`${at} 字段 ${f.key} 的选项 value 缺失或非文本`)
            else {
              if (value.length > MAX_OPTION_TEXT_CHARS) bad(`${at} 字段 ${f.key} 的选项 value 过长（>${MAX_OPTION_TEXT_CHARS} 字符）`)
              if (optionSeen.has(value)) bad(`${at} 字段 ${f.key} 的选项 value "${value}" 重复`)
              optionSeen.add(value)
            }
            if (o.label !== undefined && (typeof o.label !== 'string' || o.label.length > MAX_OPTION_TEXT_CHARS)) {
              bad(`${at} 字段 ${f.key} 的选项 label 非文本或过长（>${MAX_OPTION_TEXT_CHARS} 字符）`)
            }
          }
        }
      } else if (f.options !== undefined) {
        bad(`${at} 字段 ${f.key}（type=${f.type}）不该带 options`)
      }
    }
  }
  ok('生成式表单模板与 GenerativeFormRules 白名单逐字段对账通过（经桩内副本；含 5 项上限：title/options/option 文本与去重/placeholder/form 序列化）')

  section()
  // ④ 收敛自证：逐路由跑"首轮→工具调用、续轮→文本"的模拟循环（长度递增形态）
  const sim = new Map()
  for (const route of ROUTES.routes || []) {
    const userText = (route.match?.messageAny || [])[0]
    const tools = route.match?.requiresTools || []
    let messages = [{ role: 'system', content: 'sys' }, { role: 'user', content: userText }]
    const first = decide({}, ROUTES, sim, { messages, tools })
    if (first.kind !== 'tool_call' || first.tool !== route.tool) {
      bad(`收敛自证：路由 ${route.id} 首轮未发出期望的工具调用（${first.kind}/${first.tool ?? '-'}）`)
      continue
    }
    messages = messages.concat([
      { role: 'assistant', content: '', tool_calls: [{ id: 'c1', type: 'function', function: { name: first.tool, arguments: JSON.stringify(first.args) } }] },
      { role: 'tool', tool_call_id: 'c1', content: '{"keyword":"E2EGFC","scope":"XN"}' }
    ])
    let calls = 1
    let decision = decide({}, ROUTES, sim, { messages, tools })
    while (decision.kind === 'tool_call' && calls <= maxCalls + 2) {
      messages = messages.concat([{ role: 'tool', tool_call_id: `c${calls + 1}`, content: '{}' }])
      decision = decide({}, ROUTES, sim, { messages, tools })
      calls++
    }
    if (decision.kind !== 'text') bad(`收敛自证：路由 ${route.id} 未收敛到文本轮`)
    if (calls > maxCalls) bad(`收敛自证：路由 ${route.id} 上游调用数 ${calls} 超过上限 ${maxCalls}`)
  }
  ok(`收敛自证通过（长度递增形态：每条路由 ≤ ${maxCalls} 次上游调用即终止）`)

  section()
  // ④b 等长重复请求自证（红队问题 1 的回归用例）：后端 ResilientChatService 流失败重试时
  // **原样重发同一请求**（messages.length 不变），不得把 calls/used 台账清零——否则轮次上限
  // 与"每路由一次"两道闸被系统性绕过（修复前实测 8/8 全部发出 tool_call）。
  const equalSim = new Map()
  const repeats = maxCalls + 2
  for (const route of ROUTES.routes || []) {
    const userText = (route.match?.messageAny || [])[0]
    const tools = route.match?.requiresTools || []
    const messages = [{ role: 'system', content: 'sys' }, { role: 'user', content: userText }]
    let toolCalls = 0
    for (let i = 0; i < repeats; i++) {
      const d = decide({}, ROUTES, equalSim, { messages, tools })
      if (i === 0 && (d.kind !== 'tool_call' || d.tool !== route.tool)) {
        bad(`等长重发自证：路由 ${route.id} 首轮未发出期望的工具调用（用例不成立）`)
      }
      if (i > 0 && d.kind === 'tool_call') {
        bad(`等长重发自证：路由 ${route.id} 第 ${i + 1} 次等长请求仍发起工具调用（台账被误重置——红队问题 1 回归）`)
      }
      if (d.kind === 'tool_call') toolCalls++
    }
    if (toolCalls > maxCalls) bad(`等长重发自证：路由 ${route.id} 在 ${repeats} 次等长请求中发出 ${toolCalls} 次工具调用，超过上限 ${maxCalls}`)
  }
  ok(`等长重发自证通过（同一 fingerprint、长度不变的重复请求不再重置台账：${repeats} 次请求中仅首轮发工具调用，≤ ${maxCalls} 上限）`)

  section()
  // ⑤ 披露清单驱动：tools[] 为空时不得发出任何工具调用
  const emptyToolsSim = new Map()
  for (const route of ROUTES.routes || []) {
    const d = decide({}, ROUTES, emptyToolsSim, {
      messages: [{ role: 'system', content: 'sys' }, { role: 'user', content: (route.match?.messageAny || [])[0] }],
      tools: []
    })
    if (d.kind === 'tool_call') bad(`披露清单驱动失效：路由 ${route.id} 在 tools[] 为空时仍发起 ${d.tool}`)
  }
  ok('披露清单驱动通过（tools[] 为空时一律文本收尾）')

  section()
  // ⑥ SSE 帧形态：逐行可解析且字段齐全
  const toolFrames = toolRound('start_publish', { taskId: 7 }, 'call-check', 'ci-stub').filter((f) => f !== 'data: [DONE]\n\n')
  const textFrames = textRound('key=value 回报', 'ci-stub').filter((f) => f !== 'data: [DONE]\n\n')
  const parsedTool = toolFrames.map((f) => JSON.parse(f.replace(/^data: /, '').trim()))
  const parsedText = textFrames.map((f) => JSON.parse(f.replace(/^data: /, '').trim()))
  if (parsedTool[1]?.choices?.[0]?.delta?.tool_calls?.[0]?.function?.name !== 'start_publish') bad('工具轮帧形态异常')
  if (parsedTool[2]?.choices?.[0]?.finish_reason !== 'tool_calls') bad('工具轮缺 finish_reason=tool_calls 终帧')
  const stop = parsedText.find((c) => c.choices?.[0]?.finish_reason === 'stop')
  if (!stop) bad('文本轮缺 finish_reason=stop 终帧')
  const usage = parsedText.find((c) => c.usage)
  if (!usage?.usage?.total_tokens) bad('文本轮缺 usage 帧（硬规范②：正常收尾必须有终帧）')
  ok('SSE 帧形态通过（工具轮 tool_calls 终帧 / 文本轮 stop + usage 终帧）')

  section()
  // ⑦ 工具结果反解：三种形态都要能解出值
  const cases = [
    ['对象形态', { keyword: 'E2EGFC' }],
    ['双层字符串形态', JSON.stringify(JSON.stringify({ keyword: 'E2EGFC' }))],
    ['前缀混合形态', '前端工具结果：{"keyword":"E2EGFC","scope":"XN"}（已回灌）']
  ]
  for (const [name, raw] of cases) {
    const got = extractJsonObject(raw)
    if (!got || got.keyword !== 'E2EGFC') bad(`工具结果反解失败（${name}）`)
  }
  ok('工具结果反解通过（对象/双层字符串/前缀混合三形态）')

  console.log('------------------------------------------------------------')
  if (problems.length) {
    console.log(`SELF-CHECK FAIL：${problems.length} 项`)
    for (const p of problems) console.log(`  - ${p}`)
    process.exit(1)
  }
  console.log('SELF-CHECK PASS：路由表契约、白名单对账、收敛自证、披露驱动、帧形态、反解全部通过')
  process.exit(0)
}

// ── 入口 ──────────────────────────────────────────────────────────────────

const ROUTES = loadRoutes(ROUTES_PATH)
const violations = applyViolation(ROUTES)

if (SELF_CHECK) {
  console.log(`stub-upstream 自检 routes=${ROUTES_PATH}`)
  selfCheck()
} else {
  startServer()
}
