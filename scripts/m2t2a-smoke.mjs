// M2-T2b 冒烟升级（T2a 唯一投影 CDP 冒烟 → 断言①真实挂起轮场景；断言②③回归原样）
//
// 用法（仓库根目录，Node ≥ 22 —— 仅用内置 WebSocket + fetch，零依赖安装）：
//   0. 前置：Memurai 127.0.0.1:6379 在线；后端已起；前端 dev server 已起。
//   1. node scripts/m2t2a-smoke.mjs
//   2. 可选环境变量：
//      SMOKE_API=http://localhost:18318       后端 base（默认 18318；真实验证用 18330）
//      SMOKE_APP=http://localhost:5202/       前端地址
//      SMOKE_CDP_PORT=9333                    Chrome 远程调试端口
//      SMOKE_CHROME=<可执行文件>              Chrome 路径（缺省按 ProgramFiles 环境变量组装，源码不写死盘符）
//      SMOKE_OUT=<dir>                        结果输出目录（默认 <repo>/../m2t2a-smoke-out）
//      SMOKE_SKIP_MODEL=1                     零模型回归守卫模式：跳过阶段 3（真实模型挂起轮），
//                                             断言① 记 skipped（不计 fail 也不计 pass），
//                                             退出码仅由 ②③③b 决定（无 key / 无模型环境用）
//   3. 产物分轮保留：结果写 result-<UTC 时间戳>.json，同一 SMOKE_OUT 重复执行不覆盖前一轮
//
// 零模型回归守卫模式（SMOKE_SKIP_MODEL=1）：无可用 AI key / 无真实模型的环境，只跑阶段 1/2 的
// 零模型前端回归（断言②③③b），阶段 3 整段（3a 模型轮 / 3b 刷新重建 / 3d 收敛）跳过，
// 断言① 在结果 JSON 中标记 skipped（不计 fail 也不计 pass），退出码仅由 ②③③b 决定。
// 用途：CI / 本地无 key 环境的零模型回归守卫；默认（未设置）行为完全不变。
//
// 三断言（全部 PASS 退出码 0，任一 FAIL 退出码 1）：
//   ① 真实挂起轮 → 刷新 → 快照重建语义（T2b 升级形态，取代 T2a 占位回归守卫；
//      来源：GLM 复核 F5 / 返修决策卡勘误 5 —— 本形态落地后该遗留项关闭）
//      a. Node 侧直连后端 POST /api/ai/chat（真实模型轮，诱导 generative_form），
//         流式读到 frontend_tool_request 后保持连接不关（挂起轮活着）；
//      b. 页面注入 sessionId + 清该 session 镜像键后整页刷新 → loadHistory →
//         GET /api/ai/runs/current 命中 → 快照重建挂起卡；
//      c. 断言：data-testid="pending-card-<toolCallId>" 恰好 1 张、
//         data-testid="gf-submit"（FormRenderer）恰好 1 个（真实重建，与旧守卫
//         "无 FormRenderer" 相反）、全消息 toolRuns 中该 toolCallId 恰好 1 条（无重复工具卡）；
//      d. 收敛退出：按 activeForm 实际 schema 合成值 submitGenerativeForm 提交（被 400 拒则
//         降级点 gf-cancel 取消，detail 记录走的哪条路径）；Node 侧原连接须收到
//         frontend_tool_result 与 done 帧（计入① detail）。
//   ② 同一 toolCallId 不出现双份 FormRenderer（同 toolCallId 至多一个表单实例）
//   ③ 取消后所有持该 toolCallId 的消息 pendingCall.status 收敛为 cancelled
//
// 定位口径（T2b 顺手项④，S7 红队建议落地）：断言①②③ 的按钮/tag/卡片计数一律走
// data-testid（gf-submit / gf-cancel / pending-card-<toolCallId> / tag-form-cancelled），
// 不再耦合 UI 文案 —— 文案改动不再造成静默误判。
//
// 方法：断言②③ 经 Pinia store 注入 frontend_tool_request 帧复现挂起形态（与 GFd 冒烟同法，
// 无模型参与）；断言① 为真实后端挂起轮（需可用 AI key + 后端 + Redis）。
// 本脚本只驱动自己启动的 Chrome，不触碰用户浏览器。
import { spawn } from 'node:child_process'
import { writeFileSync, mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { setTimeout as sleep } from 'node:timers/promises'

// data-testid 定位（T2b 顺手项④）：FormRenderer 提交/取消按钮、表单已取消 tag、挂起卡根 div
const TESTID_SUBMIT = 'gf-submit'
const TESTID_CANCEL = 'gf-cancel'
const TESTID_TAG_CANCELLED = 'tag-form-cancelled'

// 诱导 generative_form 的已验证提示词（scripts/verify-e2e.ps1:1478 $promptGf1 原文）：
// 四字段 keyword/minRows/effectiveDate/scope；verify-e2e 发 /chat 时 context 为 @{ page = 'tasks' }，此处照搬
const PROMPT_GF1 = '请调用 generative_form 工具（scenario=FILTER）出一张收集导出筛选条件的表单，字段就用这四个，key 和类型必须一致：keyword（文本，必填，占位提示"编码或名称关键字"）、minRows（数字，非必填）、effectiveDate（日期，格式 yyyy-MM-dd，非必填）、scope（下拉单选，选项 XN 和 HD）。不要用文字向我提问，也不要调用 generative_form 以外的任何工具，不会出现确认卡片。我填完后，请把每个字段按 key=值 原样逐行回报。'

const OUT = process.env.SMOKE_OUT ?? new URL('../../m2t2a-smoke-out', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')
// Chrome 可执行文件不写死盘符：SMOKE_CHROME 优先，缺省按 ProgramFiles 环境变量组装
const CHROME = process.env.SMOKE_CHROME
  ?? join(process.env.ProgramFiles || '', 'Google/Chrome/Application/chrome.exe')
const PORT = Number(process.env.SMOKE_CDP_PORT ?? 9333)
const API = process.env.SMOKE_API ?? 'http://localhost:18318'
const URL_APP = process.env.SMOKE_APP ?? 'http://localhost:5202/'
// 零模型回归守卫模式（SMOKE_SKIP_MODEL=1）：跳过阶段 3 真实模型挂起轮，断言① 记 skipped
const SKIP_MODEL = process.env.SMOKE_SKIP_MODEL === '1'
// 分轮保留：每轮写独立结果文件（UTC 时间戳），复跑不覆盖上一轮证据
const STAMP = new Date().toISOString().replace(/[-:]/g, '').replace(/\.\d+Z$/, 'Z')
const RESULT_FILE = `${OUT}/result-${STAMP}.json`
mkdirSync(OUT, { recursive: true })

const chrome = spawn(CHROME, [
  `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${OUT}/chrome-profile`,
  '--no-first-run',
  '--no-default-browser-check',
  '--disable-sync',
  '--window-size=1400,1800',
  '--window-position=0,0',
  'about:blank'
], { detached: false, stdio: 'ignore' })
console.log('[chrome] pid =', chrome.pid)

let ver = null
for (let i = 0; i < 60; i++) {
  try {
    const r = await fetch(`http://127.0.0.1:${PORT}/json/version`)
    if (r.ok) { ver = await r.json(); break }
  } catch { /* 未就绪 */ }
  await sleep(500)
}
if (!ver) { console.error('FATAL: CDP 端点未就绪'); process.exit(2) }
console.log('[cdp] browser =', ver.Browser)

const list = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json()
const target = list.find(t => t.type === 'page')
if (!target) { console.error('FATAL: 无 page target'); process.exit(2) }

const ws = new WebSocket(target.webSocketDebuggerUrl)
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('ws error')) })

let msgId = 0
const waiters = new Map()
ws.onmessage = (ev) => {
  const m = JSON.parse(ev.data)
  if (m.id && waiters.has(m.id)) {
    const { res, rej } = waiters.get(m.id); waiters.delete(m.id)
    m.error ? rej(new Error(JSON.stringify(m.error))) : res(m.result)
  }
}
function send(method, params = {}) {
  const id = ++msgId
  ws.send(JSON.stringify({ id, method, params }))
  return new Promise((res, rej) => waiters.set(id, { res, rej }))
}
async function evaluate(expr, { awaitPromise = true } = {}) {
  const r = await send('Runtime.evaluate', {
    expression: expr, returnByValue: true, awaitPromise,
    userGesture: true
  })
  if (r.exceptionDetails) throw new Error('EVAL_ERR: ' + JSON.stringify(r.exceptionDetails.exception?.description ?? r.exceptionDetails))
  return r.result.value
}

await send('Runtime.enable')
await send('Page.enable')

async function waitAppReady() {
  for (let i = 0; i < 60; i++) {
    await sleep(500)
    try {
      const ok = await evaluate(`!!(document.getElementById('app') && document.getElementById('app').__vue_app__ &&
        document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai'))`, { awaitPromise: false })
      if (ok) return true
    } catch { /* 未就绪 */ }
  }
  return false
}

function frameExpr(runId, toolCallId) {
  return `{ type:'frontend_tool_request', runId:'${runId}', toolCallId:'${toolCallId}',
    name:'generative_form', timeoutSeconds:120, expiresAt:Date.now()+120000,
    args: JSON.stringify({ form:{ scenario:'FILTER', title:'导出筛选条件', fields:[
      {key:'keyword',label:'配置编码关键词',type:'text',required:true}
    ]}})}`
}

/** 注入 n 条助手消息、各挂同一 toolCallId 的 generative_form 帧；返回消息 id 列表。 */
function injectExpr(runId, toolCallId, n) {
  const lines = []
  for (let i = 1; i <= n; i++) {
    lines.push(`const m${i} = ai.appendAssistantMessage('（T2a冒烟${i}）'); ai.handleFrame(FRAME, m${i}.id)`)
  }
  return `(async () => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    ai.expanded = true
    const FRAME = ${frameExpr(runId, toolCallId)}
    ${lines.join('\n    ')}
    await new Promise(r => setTimeout(r, 400))
    return [${Array.from({ length: n }, (_, i) => `m${i + 1}.id`).join(', ')}]
  })()`
}

// 定位一律 data-testid（顺手项④）：gf-submit/gf-cancel 计数、pending-card-<toolCallId> DOM 计数、
// tag-form-cancelled 计数；store 侧 pendingStatuses 仅供 detail；toolRunCount 判无重复工具卡
const countExpr = (toolCallId) => `(() => {
  const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
  return {
    formRendererCount: document.querySelectorAll('[data-testid="${TESTID_SUBMIT}"]').length,
    cancelButtons: document.querySelectorAll('[data-testid="${TESTID_CANCEL}"]').length,
    pendingCards: [...document.querySelectorAll('[data-testid^="pending-card-"]')]
      .filter(e => e.getAttribute('data-testid') === 'pending-card-${toolCallId}').length,
    pendingStatuses: ai.messages.filter(m => m.pendingCall && m.pendingCall.toolCallId === '${toolCallId}').map(m => m.pendingCall.status),
    activeFormStatus: ai.activeForm ? ai.activeForm.status : null,
    cancelledTags: document.querySelectorAll('[data-testid="${TESTID_TAG_CANCELLED}"]').length,
    toolRunCount: ai.messages.reduce((n, m) => n + m.toolRuns.filter(r => r.toolCallId === '${toolCallId}').length, 0)
  }
})()`

const RESULT = { assertions: {}, phases: {} }
const failures = []
function assert(name, cond, detail) {
  RESULT.assertions[name] = { pass: !!cond, detail }
  console.log(`[断言${name}] ${cond ? 'PASS' : 'FAIL'} ${JSON.stringify(detail)}`)
  if (!cond) failures.push(name)
}

// 等待上界不写死：由服务端自报的轮次预算（/api/ai/health 的 resilience.totalBudget）
// 推导为 budgetMs + 30s（余量 = 一个心跳周期 + 抖动）。写死 240s 的问题是它**大于**后端
// app.ai.hitl 的挂起上限（原 120s 单键三场景共用；拆键后等人 240s 过渡值 / 等机器 120s）——
// 任何"后端根本没给帧"的失败都要先烧满窗才报出，而期间后端早已给出终帧（done / error），
// 断言于是退化为"等超时"而非"看事件"。
// 回落值 = app.ai.resilience.total-budget 缺省 300s + 30s 余量；解析成功时以服务端值为准。
// 2026-10-10 数字规格清点裁决⑤（小批苞 F）：改事件驱动（早停于 done / error，硬上限由事实源推导）。
const FALLBACK_AWAIT_MS = 330_000

/** 解析 ISO-8601 时长（如 PT5M）为毫秒；非时长或非正值返回 null（由调用方回落具名常量）。 */
function parseIsoDurationMs(raw) {
  if (typeof raw !== 'string') return null
  const m = raw.trim().match(/^P(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$/)
  if (!m || !m[0]) return null
  const [d, h, mi, s] = m.slice(1).map(v => Number(v ?? 0))
  if (![d, h, mi, s].some(v => v > 0)) return null
  return ((d * 24 + h) * 60 + mi) * 60_000 + s * 1000
}

/** 事实源 = 已在跑的服务端 /api/ai/health：读 resilience.totalBudget（不依赖任何新增端点）。 */
async function deriveAwaitCap() {
  const fallbackSource = 'FALLBACK_AWAIT_MS（app.ai.resilience.total-budget 缺省 300s + 30s 余量）'
  try {
    const r = await fetch(`${API}/api/ai/health`, { headers: { Accept: 'application/json' } })
    if (!r.ok) {
      console.warn('[阶段3a] /health 非 2xx（HTTP ' + r.status + '），等待上界回落具名常量')
      return { capMs: FALLBACK_AWAIT_MS, capSource: fallbackSource }
    }
    const body = await r.json()
    const raw = body?.resilience?.totalBudget
    const budgetMs = parseIsoDurationMs(raw)
    if (!budgetMs) {
      console.warn('[阶段3a] /health 的 resilience.totalBudget 无法解析（' + JSON.stringify(raw) + '），等待上界回落具名常量')
      return { capMs: FALLBACK_AWAIT_MS, capSource: fallbackSource }
    }
    return { capMs: budgetMs + 30_000, capSource: `/api/ai/health resilience.totalBudget=${raw}` }
  } catch (e) {
    console.warn('[阶段3a] /health 读取失败（' + e.message + '），等待上界回落具名常量')
    return { capMs: FALLBACK_AWAIT_MS, capSource: fallbackSource }
  }
}

/**
 * 流式读 SSE（data: 行 JSON）。onFrame 返回 true 时停读并保持连接不关（由调用方继续读或取消）；
 * 返回 { reader, seenTypes, stopped, frame, stopReason }，frame 为触发停读的那一帧。
 *
 * 第三参兼容两种形态：数字 = 绝对截止时刻（旧签名，等价 { deadlineMs }）；
 * 对象 = { deadlineMs, until, label } —— until 是"终止帧"谓词（done / error 任一命中即停读，
 * 事件驱动早停，不必烧满整窗）。stopReason 取值：
 *   matched         = onFrame 命中（调用方要等的帧到了）
 *   terminal:done / terminal:error = until 命中（后端已给终帧，结局可判）
 *   window          = 窗口到点 / 对端关闭（窗内什么都没读到 —— 环境或超时面）
 * 有了 stopReason，失败即可区分"后端给了终帧（问题在业务）"与"窗内空读（问题在环境/超时）"。
 */
async function readSse(response, onFrame, opts = {}) {
  const cfg = typeof opts === 'number' ? { deadlineMs: opts } : opts
  const deadlineMs = typeof cfg.deadlineMs === 'number' ? cfg.deadlineMs : Infinity
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  const seenTypes = []
  let buf = ''
  try {
    while (Date.now() < deadlineMs) {
      const { done, value } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      const lines = buf.split('\n')
      buf = lines.pop()
      for (const line of lines) {
        if (!line.startsWith('data:')) continue
        let frame
        try { frame = JSON.parse(line.slice(5).trim()) } catch { continue }
        seenTypes.push(frame.type)
        if (onFrame(frame)) return { reader, seenTypes, stopped: true, frame, stopReason: 'matched', label: cfg.label ?? null }
        if (cfg.until && cfg.until(frame)) {
          return { reader, seenTypes, stopped: true, frame, stopReason: `terminal:${frame.type}`, label: cfg.label ?? null }
        }
      }
    }
  } catch { /* 超时/对端断开：按停读处理 */ }
  return { reader, seenTypes, stopped: false, frame: null, stopReason: 'window', label: cfg.label ?? null }
}

try {
  // ── 打开应用 ──────────────────────────────────────────────────────────
  await send('Page.navigate', { url: URL_APP })
  if (!await waitAppReady()) { console.error('FATAL: 应用/store 未就绪'); process.exit(3) }
  console.log('[app] ready')

  // ── 阶段 1：双消息同 toolCallId（GFd 双卡形态）→ 断言②唯一实例 ──────────
  const R1 = crypto.randomUUID(), T1 = crypto.randomUUID()
  await evaluate(injectExpr(R1, T1, 2))
  await sleep(600)
  const c1 = await evaluate(countExpr(T1))
  RESULT.phases.dualMessage = { runId: R1, toolCallId: T1, count: c1 }
  console.log('[阶段1 双消息]', JSON.stringify(c1))
  assert('②', c1.formRendererCount === 1 && c1.cancelButtons === 1,
    { formRendererCount: c1.formRendererCount, cancelButtons: c1.cancelButtons, expect: 1 })

  // ── 阶段 2：取消 → 断言③所有消息 pendingCall.status 收敛 cancelled ──────
  await evaluate(`(() => {
    const btn = document.querySelector('[data-testid="${TESTID_CANCEL}"]')
    btn.click(); return !!btn
  })()`)
  let c2 = null
  for (let i = 0; i < 12; i++) { await sleep(500); c2 = await evaluate(countExpr(T1)); if (c2.pendingStatuses.every(s => s === 'cancelled')) break }
  RESULT.phases.cancelDual = { count: c2 }
  console.log('[阶段2 取消]', JSON.stringify(c2))
  assert('③', c2.pendingStatuses.length === 2 && c2.pendingStatuses.every(s => s === 'cancelled'),
    { pendingStatuses: c2.pendingStatuses })
  assert('③b', c2.formRendererCount === 0 && c2.cancelledTags === 2,
    { formRendererCount: c2.formRendererCount, cancelledTags: c2.cancelledTags })

  // ── 阶段 3（断言① 升级形态）：真实挂起轮 → 刷新快照重建 → 收敛退出 ─────
  // SMOKE_SKIP_MODEL=1（零模型回归守卫模式）：阶段 3 整段跳过 —— 3a 模型轮 / 3b 刷新重建 /
  // 3d 收敛一律不执行（不发 /chat、不需 key），断言① 记 skipped（不计 fail 也不计 pass），
  // 退出码仅由 ②③③b 决定；默认（未设置）行为完全不变。
  if (SKIP_MODEL) {
    console.log('[阶段3] SMOKE_SKIP_MODEL=1，跳过真实模型挂起轮（断言① skipped）')
    RESULT.assertions['①'] = {
      pass: null,
      skipped: true,
      detail: 'SMOKE_SKIP_MODEL=1：零模型回归守卫模式，跳过阶段 3（3a 模型轮 / 3b 刷新重建 / 3d 收敛），不计 fail 也不计 pass，退出码仅由 ②③③b 决定'
    }
  }
  else {
    const S = 'm2t2b-' + crypto.randomUUID()
    console.log('[阶段3] 真实挂起轮 session =', S, 'api =', API)

    // a. 造真实挂起轮：Node 侧直连后端 /chat（真实模型），读到 frontend_tool_request 为止，
    //    连接保持不关（挂起轮活着）。事件驱动等待：读到 done / error 任一终止帧立即早停，
    //    硬上限由服务端自报的轮次预算推导（见 deriveAwaitCap），不再写死 240s；
    //    未等到 → 断言① FAIL（detail 记 stopReason + 帧类型序列，可区分"后端给了终帧"与"窗内空读"）
    const chatRes = await fetch(`${API}/api/ai/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ sessionId: S, message: PROMPT_GF1, context: { page: 'tasks' } })
    })
    if (!chatRes.ok || !chatRes.body) {
      throw new Error(`POST /chat 失败：HTTP ${chatRes.status}`)
    }
    const { capMs: AWAIT_CAP_MS, capSource: AWAIT_CAP_SOURCE } = await deriveAwaitCap()
    const awaited = await readSse(chatRes,
      f => f.type === 'frontend_tool_request' && f.name === 'generative_form',
      {
        deadlineMs: Date.now() + AWAIT_CAP_MS,
        until: f => f.type === 'done' || f.type === 'error',
        label: 'a-await-frontend_tool_request'
      })
    console.log('[阶段3a] await stopReason =', awaited.stopReason, 'stopped =', awaited.stopped,
      'capMs =', AWAIT_CAP_MS, 'capSource =', AWAIT_CAP_SOURCE, 'seen =', JSON.stringify(awaited.seenTypes))
    if (awaited.stopReason !== 'matched' || !awaited.frame) {
      try { await awaited.reader.cancel() } catch { /* 忽略 */ }
      RESULT.phases.realRound = {
        sessionId: S, stage: 'a-await', awaitStopReason: awaited.stopReason,
        awaitCapMs: AWAIT_CAP_MS, awaitCapSource: AWAIT_CAP_SOURCE, seenTypes: awaited.seenTypes
      }
      assert('①', false, {
        stage: 'a-await-frontend_tool_request', awaitStopReason: awaited.stopReason,
        awaitCapMs: AWAIT_CAP_MS, awaitCapSource: AWAIT_CAP_SOURCE, seenTypes: awaited.seenTypes
      })
      throw new Error(`阶段3a 未收到 frontend_tool_request（awaitStopReason=${awaited.stopReason}，capMs=${AWAIT_CAP_MS}）`)
    }
    const realRunId = awaited.frame.runId
    const realToolCallId = awaited.frame.toolCallId
    RESULT.phases.realRound = {
      sessionId: S, runId: realRunId, toolCallId: realToolCallId,
      awaitStopReason: awaited.stopReason, awaitCapMs: AWAIT_CAP_MS, awaitCapSource: AWAIT_CAP_SOURCE,
      awaitSeenTypes: awaited.seenTypes
    }

    // b. 刷新重建：注入 sessionId + 清该 session 镜像键 → 整页刷新 →
    //    loadHistory → runs/current 命中 → 快照重建（挂起轮在后台保持活着）
    await evaluate(`(() => {
      sessionStorage.setItem('ai-session-id', '${S}')
      sessionStorage.removeItem('ai-mirror:${S}')
      return true
    })()`)
    await send('Page.navigate', { url: URL_APP })
    if (!await waitAppReady()) { console.error('FATAL: 刷新后应用未就绪'); process.exit(3) }
    // 轮询等快照重建落地（loadHistory + runs/current 均异步）
    let rebuilt = null
    for (let i = 0; i < 30; i++) {
      await sleep(500)
      rebuilt = await evaluate(countExpr(realToolCallId))
      if (rebuilt.pendingCards >= 1 && rebuilt.formRendererCount >= 1) break
    }
    RESULT.phases.rebuild = { count: rebuilt }
    console.log('[阶段3b 刷新重建]', JSON.stringify(rebuilt))

    // c. 断言①（升级形态）：单挂起卡 + FormRenderer 真实重建（与旧守卫"无 FormRenderer"相反）
    //    + 无重复工具卡（toolRunCount 恰好 1）
    // d. 收敛退出：按 activeForm 实际 schema 合成值提交；被 400 拒则降级取消；
    //    Node 侧原连接须收到 frontend_tool_result 与 done 帧（计入① detail）
    let converge = { path: 'not-attempted' }
    if (rebuilt.pendingCards === 1 && rebuilt.formRendererCount === 1) {
      converge = await evaluate(`(async () => {
        const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
        if (!ai.activeForm || ai.activeForm.status !== 'filling') {
          return { path: 'no-active-form', activeFormStatus: ai.activeForm ? ai.activeForm.status : null }
        }
        const spec = ai.activeForm.form
        const values = {}
        for (const f of spec.fields) {
          switch (f.type) {
            case 'number': values[f.key] = 1; break
            case 'boolean': values[f.key] = true; break
            case 'date': values[f.key] = '2026-10-08'; break
            case 'enum': values[f.key] = (f.options && f.options[0]) ? f.options[0].value : 'XN'; break
            case 'multi_select': values[f.key] = (f.options && f.options[0]) ? [f.options[0].value] : []; break
            default: values[f.key] = 'smoke'
          }
        }
        await ai.submitGenerativeForm(values)
        // 结局帧可能比 POST 回执更快到达（页面 reattach 也订阅着本轮）：activeForm 翻 submitted
        // 或被结局帧清空都算提交路径已收敛；只有表单回到 filling（被拒/失败）才走取消降级
        let status = ai.activeForm ? ai.activeForm.status : null
        for (let i = 0; i < 8 && status === 'submitting'; i++) {
          await new Promise(r => setTimeout(r, 500))
          status = ai.activeForm ? ai.activeForm.status : null
        }
        if (status === 'submitted' || ai.activeForm === null) {
          return { path: 'submit', values, finalFormStatus: status }
        }
        // 提交被拒/失败 → 降级取消路径收敛（store 直调，等价于点 gf-cancel）
        const formError = ai.activeForm ? ai.activeForm.error : null
        await ai.cancelGenerativeForm()
        await new Promise(r => setTimeout(r, 1500))
        return { path: 'submit-rejected-then-cancel', values, rejectedFormStatus: status, formError,
          finalFormStatus: ai.activeForm ? ai.activeForm.status : null }
      })()`)
    }
    console.log('[阶段3d 收敛]', JSON.stringify(converge))
    RESULT.phases.converge = converge

    // Node 侧原挂起连接：读 frontend_tool_result 与 done 帧后关闭（两条收敛路径后端都会发）
    const tail = await (async () => {
      const reader = awaited.reader
      const decoder = new TextDecoder()
      const seenTypes = []
      const got = { frontendToolResult: false, done: false }
      let buf = ''
      const deadline = Date.now() + 150_000
      try {
        while (Date.now() < deadline && !(got.frontendToolResult && got.done)) {
          const { done: rd, value } = await reader.read()
          if (rd) break
          buf += decoder.decode(value, { stream: true })
          const lines = buf.split('\n')
          buf = lines.pop()
          for (const line of lines) {
            if (!line.startsWith('data:')) continue
            let frame
            try { frame = JSON.parse(line.slice(5).trim()) } catch { continue }
            seenTypes.push(frame.type)
            if (frame.type === 'frontend_tool_result' && frame.toolCallId === realToolCallId) {
              got.frontendToolResult = true
            }
            if (frame.type === 'done') got.done = true
          }
        }
      } catch { /* 超时/断开：按已收帧判定 */ }
      try { await reader.cancel() } catch { /* 忽略 */ }
      return { ...got, seenTypes }
    })()
    console.log('[阶段3d 结局帧]', JSON.stringify({ ftr: tail.frontendToolResult, done: tail.done }))
    RESULT.phases.settleFrames = { frontendToolResult: tail.frontendToolResult, done: tail.done, seenTypes: tail.seenTypes }

    assert('①',
      rebuilt.pendingCards === 1 && rebuilt.formRendererCount === 1 && rebuilt.toolRunCount === 1
        && tail.frontendToolResult && tail.done,
      {
        pendingCards: rebuilt.pendingCards,
        formRendererCount: rebuilt.formRendererCount,
        toolRunCount: rebuilt.toolRunCount,
        pendingStatuses: rebuilt.pendingStatuses,
        activeFormStatus: rebuilt.activeFormStatus,
        convergePath: converge.path,
        frontendToolResult: tail.frontendToolResult,
        done: tail.done
      })
  }

  // ── 收尾清理 ──────────────────────────────────────────────────────────
  await evaluate(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    ai.resetSession(); return true
  })()`)
} catch (err) {
  console.error('FATAL:', err)
  failures.push('exception')
} finally {
  RESULT.round = { startedAt: STAMP, outDir: OUT, chrome: CHROME, api: API, resultFile: RESULT_FILE }
  writeFileSync(RESULT_FILE, JSON.stringify(RESULT, null, 2))
  console.log('[done] result written to', RESULT_FILE)
  ws.close()
  chrome.kill()
  await sleep(1500)
  process.exit(failures.length === 0 ? 0 : 1)
}
