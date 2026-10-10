// 对话面板包 · 苞 A（R-100）项 1 + 苞 B（R-101）项 2/3/4 CDP 冒烟。
//
// 用法（仓库根目录，Node ≥ 22 —— 仅用内置 WebSocket + fetch，零依赖安装）：
//   0. 前置：Redis 在线；后端已起（默认 18330）；前端 dev server 已起（默认 5202）。
//   1. node scripts/chat-panel-smoke.mjs
//   2. 可选环境变量：
//      SMOKE_API=http://localhost:18330       后端 base（默认 18330）
//      SMOKE_APP=http://localhost:5202/       前端地址
//      SMOKE_CDP_PORT=9334                    Chrome 远程调试端口
//      SMOKE_CHROME=<可执行文件>              Chrome 路径（缺省按 ProgramFiles 环境变量组装，源码不写死盘符）
//      SMOKE_OUT=<dir>                        结果输出目录（默认 <repo>/../chat-panel-smoke-out）
//      SMOKE_CLIPBOARD_TIMEOUT_MS=5000        剪贴板回读（⑧ 的旁证）等待上界，超时即 SKIP
//                                             （夹具可置极小值以复现无权限环境的降级路径）
//      SMOKE_KILL_BACKEND=1                   **破坏性**：授权本脚本按端口 PID 杀后端 java 进程，
//                                             供苞 B 断言⑫ 制造"真断流"回归对照。默认 **不杀任何进程**，
//                                             此时 ⑫ 如实报 SKIP（不假绿）。三重闸见 killBackendByListeningPort。
//
// 苞 A（R-100 · 项 1）五条断言（全部 PASS 退出码 0，任一 FAIL 退出码 1）：
//   ① 空态：无消息时头部「新建对话」按钮置灰（disabled），空态页签语义小字可见；
//   ② 一轮真实对话后：按钮可点；后端 GET /api/ai/history/{sessionId} 有该会话的记忆（count > 0）；
//   ③ 点「新建对话」：立刻清屏 + 按钮回置灰 + **store 的 sessionId 与 sessionStorage 同步且都变了**
//      （苞 A 必修前置缺陷：此前换 id 只写 sessionStorage、从不写 state）+ 旧镜像键已删
//      + 后端旧会话记忆 count === 0；
//   ④ 缺陷修复的行为级证据：新会话再发一轮 → 记忆记在**新 id** 上（旧 id 仍为 0）
//      —— 若 `send` 仍带旧 id（缺陷未修），断言④ 必红；
//   ⑤ 置灰态点击无副作用（点不动：sessionId 不变）。
//
// 苞 B（R-101）八条断言（同一脚本内追加，不另起脚本；施工卡 §2/§3/§4）：
//   ⑥ 项 2 结构：每条**正文非空**的消息都有复制按钮，正文为空的消息没有；按钮默认 `opacity: 0`
//      （未 hover 不显现）；有消息时骨架屏不出现；
//   ⑦ 项 2 显现：CDP 真实鼠标移到按钮上 → 计算样式 `opacity: 1`（group-hover 生效）；
//   ⑧ 项 2 主路径：真实点击 → `navigator.clipboard.writeText` 收到的字符串 === `m.content`
//      **逐字符相等**（不含思考链/工具卡、不 trim）+ 局部提示「已复制」出现
//      + **`ai.notice` 仍为 null**（提示不占单槽位）；另附剪贴板回读（**非断言**，CDP 下常被拒；
//      带 5s 上界——超时/无权限如实 SKIP 并把理由落盘，详见 readClipboardBounded）；
//   ⑨ 项 2 回退 A：把 `navigator.clipboard` 置为 undefined（模拟经内网 http 访问的非安全上下文）
//      → 点击走 `document.execCommand('copy')`，回退文本 === `m.content`，「已复制」仍出现；
//   ⑩ 项 2 回退 B：`clipboard.writeText` 存在但**拒绝**（模拟权限/非聚焦被拒）→ 同样落回退路径，
//      「已复制」仍出现（不得静默失败）；
//   ⑪ 项 3 主证据：起一轮长跑（DANGER 工具 → 确认门挂起）→ 用户点「停止」→ `ai.streamInterrupted`
//      **为 false**、页面**无**「连接中断，结果可能不完整」文案、`notice` 只留「已请求取消本轮对话」；
//   ⑪ 的前置自证：停止前该轮确在飞（`loading && activeRunId && suspended`）——否则断言不成立；
//   ⑬ 项 4：清镜像后 reload，用 `Fetch` 域把历史请求挂住 → 骨架屏（`data-testid="history-skeleton"`）
//      出现且空态**不**同框（`historyLoaded === false`）→ 放行历史请求 → 骨架屏消失、空态出现；
//   ⑫ 项 3 回归对照（**本脚本的最后一步**）：真断流（kill 后端进程，且**未**调 stop）→ 红条
//      **照常出现**、`ai.streamInterrupted === true`、`ai.stopped === false`（守卫没有削弱真断流路径）。
//      CDP 断网模拟不出真断流（只拦新请求、不拆在飞流，本苞实测）；dev 形态下 SSE 走 dev server 代理，
//      代理不结束客户端响应 ⇒ 收尾由产品自己的帧间静默看门狗触发（90s）⇒ 本步约 92s、上界 150s。
//      需 SMOKE_KILL_BACKEND=1 显式授权，未授权时本断言如实报 SKIP（不假绿）。
//
// 定位纪律：新增按钮一律 data-testid（苞 A `new-chat`；苞 B `copy-msg-{id}` / `copy-hint-{id}` /
// `history-skeleton`）；其余断言走 `$pinia` 直读 store 状态（手法与 scripts/m2t2a-smoke.mjs 一致），
// 不耦合 UI 文案 —— 文案改动不再造成静默误判。项 2 的"已复制"提示属**组件局部状态**，故用
// 其 data-testid 断言而不读 `ai.notice`（后者须保持 null，这正是"不占单槽位"的判据）。
// 本脚本只驱动自己启动的 Chrome，不触碰用户浏览器。
import { execSync, spawn } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { setTimeout as sleep } from 'node:timers/promises'

// data-testid 定位（苞 A 项 1 新增：头部「新建对话」文字按钮）
const TESTID_NEW_CHAT = 'new-chat'
// 空态页签语义小字（保守版文案，逐字断言；措辞裁决见施工卡 §1.4）
const TAB_HINT_TEXT = '对话保存在当前浏览器页签中，关闭页签即结束。'

const OUT = process.env.SMOKE_OUT ?? new URL('../../chat-panel-smoke-out', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')
const CHROME = process.env.SMOKE_CHROME
  ?? join(process.env.ProgramFiles || '', 'Google/Chrome/Application/chrome.exe')
const PORT = Number(process.env.SMOKE_CDP_PORT ?? 9334)
const API = process.env.SMOKE_API ?? 'http://localhost:18330'
const URL_APP = process.env.SMOKE_APP ?? 'http://localhost:5202/'
// 剪贴板回读的等待上界（复核 D1：无剪贴板读权限的环境里 readText() 的 promise 永不 settled，
// 原实现会把整个脚本挂死在阶段 8；此处给显式上界，超时即 SKIP —— 见 readClipboardBounded）
const CLIPBOARD_READ_TIMEOUT_MS = Number(process.env.SMOKE_CLIPBOARD_TIMEOUT_MS ?? 5000)
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
  // 苞 B：hover 断言依赖 CDP 真实鼠标事件真的被渲染进程处理。窗口被遮挡/被判为后台时
  // Chrome 会挂起渲染（`:hover` 不更新、输入事件落空），实测同一脚本两次跑结果不稳定。
  // 这四个开关是"禁止把窗口当遮挡/后台"的标准组合，属测试夹具配置，不改被测代码。
  '--disable-backgrounding-occluded-windows',
  '--disable-renderer-backgrounding',
  '--disable-background-timer-throttling',
  '--disable-features=CalculateNativeWinOcclusion',
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
/** 苞 B 项 4：被 `Fetch` 域挂住的历史请求 id（用于稳定观察骨架屏）。 */
let pausedHistoryId = null
ws.onmessage = (ev) => {
  const m = JSON.parse(ev.data)
  if (m.id && waiters.has(m.id)) {
    const { res, rej } = waiters.get(m.id); waiters.delete(m.id)
    m.error ? rej(new Error(JSON.stringify(m.error))) : res(m.result)
    return
  }
  if (m.method === 'Fetch.requestPaused') {
    pausedHistoryId = m.params.requestId
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

/** 后端该会话的记忆条数（GET /api/ai/history/{sessionId} 的 data.count）。 */
async function historyCount(sessionId) {
  const r = await fetch(`${API}/api/ai/history/${encodeURIComponent(sessionId)}`)
  if (!r.ok) throw new Error(`GET /api/ai/history/${sessionId} → HTTP ${r.status}`)
  const body = await r.json()
  return body?.data?.count ?? null
}

/** 等后端计数满足判据（收敛写入与删除都是异步的，直接读会撞上竞态）。 */
async function waitCount(sessionId, want, { tries = 24 } = {}) {
  let last = null
  for (let i = 0; i < tries; i++) {
    last = await historyCount(sessionId)
    if (want(last)) return last
    await sleep(250)
  }
  return last
}

/**
 * 页面侧快照：store 状态 + DOM 事实（按钮置灰 / 小字可见 / 镜像键）。
 * `expectedHint` 逐字比对空态小字，避免"文案被改宽"后静默通过。
 */
function snapshotExpr() {
  return `(() => {
    const app = document.getElementById('app').__vue_app__
    const ai = app.config.globalProperties.$pinia._s.get('ai')
    const btn = document.querySelector('[data-testid="${TESTID_NEW_CHAT}"]')
    const hint = [...document.querySelectorAll('div')].find(e => e.textContent.trim() === ${JSON.stringify(TAB_HINT_TEXT)})
    const oldMirrorKeys = Object.keys(sessionStorage).filter(k => k.startsWith('ai-mirror:'))
    return {
      sessionId: ai.sessionId,
      storedSessionId: sessionStorage.getItem('ai-session-id'),
      messages: ai.messages.length,
      historyLoaded: ai.historyLoaded,
      notice: ai.notice,
      buttonExists: !!btn,
      buttonDisabled: btn ? btn.disabled === true : null,
      buttonText: btn ? btn.textContent.trim() : null,
      hintVisible: !!(hint && hint.offsetParent !== null),
      mirrorKeys: oldMirrorKeys,
      hasOldMirror: oldMirrorKeys.includes('ai-mirror:' + ai.sessionId)
    }
  })()`
}

const RESULT = { assertions: {}, phases: {} }
const failures = []
function assert(name, cond, detail) {
  RESULT.assertions[name] = { pass: !!cond, detail }
  console.log(`[断言${name}] ${cond ? 'PASS' : 'FAIL'} ${JSON.stringify(detail)}`)
  if (!cond) failures.push(name)
}

/** 点一次「新建对话」（真实 DOM 点击），返回点击前后的快照。 */
async function clickNewChat() {
  const before = await evaluate(snapshotExpr(), { awaitPromise: false })
  await evaluate(`(() => {
    const btn = document.querySelector('[data-testid="${TESTID_NEW_CHAT}"]')
    btn.click(); return true
  })()`, { awaitPromise: false })
  return before
}

/** 等 store 侧条件成立（默认 6s 上界，每 250ms 采样一次；intervalMs 可调，苞 B 的挂起探测要更密）。 */
async function waitFor(expr, { tries = 24, intervalMs = 250 } = {}) {
  let last = null
  for (let i = 0; i < tries; i++) {
    last = await evaluate(expr, { awaitPromise: false })
    if (last) return last
    await sleep(intervalMs)
  }
  return last
}

/** 页面侧取 pinia 里的 ai store（全部断言的取值口径）。 */
const AI_EXPR = "document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')"

/** 发一轮并等它收尾（与面板「发送」同一条 store 动作，只省略输入框）。 */
async function sendRound(text) {
  await evaluate(`(async () => { await ${AI_EXPR}.send(${JSON.stringify(text)}); return true })()`)
  return waitFor(`(() => { const ai = ${AI_EXPR}; return ai.messages.length > 0 && !ai.loading })()`, { tries: 60, intervalMs: 200 })
}

/**
 * 起一轮「长跑」轮次（苞 B 项 3）：提示词触发 DANGER 工具（start_export）→ 产品在确认门挂起，
 * 轮次停在等人工确认上，故"在飞"是可稳定观测的。
 *
 * 为什么必须挂起而不能随便发一句：普通文本轮在桩下几十毫秒即终局，"停止"会打在已收尾的轮次上，
 * 断言就成了"本来就不该有红条"的空转。返回挂起现场（含 runId），null 表示未能进入挂起态。
 */
async function startSuspendedRound(prompt) {
  await evaluate(`(() => { void ${AI_EXPR}.send(${JSON.stringify(prompt)}); return true })()`, { awaitPromise: false })
  return waitFor(
    `(() => { const ai = ${AI_EXPR}
      return (ai.loading && ai.activeRunId !== null && ai.suspended)
        ? { runId: ai.activeRunId, messages: ai.messages.length, notice: ai.notice }
        : null })()`,
    { tries: 80, intervalMs: 100 }
  )
}

/** 消息级 + 页面级事实快照（复制按钮/局部提示/降级条/骨架屏，苞 B 三处断言共用）。 */
function msgFactsExpr(msgId) {
  return `(() => {
    const ai = ${AI_EXPR}
    const btn = document.querySelector('[data-testid="copy-msg-${msgId}"]')
    const hint = document.querySelector('[data-testid="copy-hint-${msgId}"]')
    const rect = btn ? btn.getBoundingClientRect() : null
    return {
      buttonExists: !!btn,
      buttonText: btn ? btn.textContent.trim() : null,
      buttonOpacity: btn ? getComputedStyle(btn).opacity : null,
      center: rect ? { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 } : null,
      hintExists: !!hint,
      hintText: hint ? hint.textContent.trim() : null,
      notice: ai.notice,
      stopped: ai.stopped,
      loading: ai.loading,
      suspended: ai.suspended,
      streamInterrupted: ai.streamInterrupted,
      messages: ai.messages.length,
      historyLoaded: ai.historyLoaded,
      activeRunId: ai.activeRunId,
      skeleton: !!document.querySelector('[data-testid="history-skeleton"]'),
      emptyCard: document.body.innerText.includes('您好，我是实施助手'),
      interruptedBanner: document.body.innerText.includes('连接中断，结果可能不完整')
    }
  })()`
}

/** 真实鼠标悬停（CDP Input 域）—— 项 2 的显现条件是 `group-hover`，只能靠真 hover 触发。 */
async function mouseHover(x, y) {
  await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y, button: 'none', buttons: 0 })
}

/** 真实鼠标点击（press + release），比 `el.click()` 更贴近用户操作。 */
async function mouseClick(x, y) {
  await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', buttons: 1, clickCount: 1 })
  await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', buttons: 0, clickCount: 1 })
}

const opacityExpr = (msgId) =>
  `getComputedStyle(document.querySelector('[data-testid="copy-msg-${msgId}"]')).opacity`

/**
 * 让复制按钮显现（苞 B 项 2 的显现条件 = `group-hover`）。
 *
 * 首选**真实鼠标 hover**（用户可见行为）；实测窗口被判为遮挡时 Chrome 会挂起渲染致 hover 不生效，
 * 故重试 3 次后回落 CDP CSS 域的 `forcePseudoState`（对祖先 `.group` 强制 `:hover`）——
 * 两条路径读的是**同一个**计算样式 `opacity`，断言口径不变，落盘里记明用的是哪条。
 */
async function revealCopyButton(msgId, center) {
  for (let i = 0; i < 3; i++) {
    await mouseHover(center.x, center.y)
    await sleep(400)
    const opacity = await evaluate(opacityExpr(msgId), { awaitPromise: false })
    if (opacity === '1') return { opacity, mechanism: 'cdp-mouse-hover' }
  }
  await send('DOM.enable')
  await send('CSS.enable')
  const doc = await send('DOM.getDocument', { depth: -1 })
  const search = await send('DOM.performSearch', { query: `.group:has([data-testid="copy-msg-${msgId}"])` })
  if (search.resultCount < 1) {
    return { opacity: await evaluate(opacityExpr(msgId), { awaitPromise: false }), mechanism: 'none', docRoot: doc.root.nodeId }
  }
  const found = await send('DOM.getSearchResults', { searchId: search.searchId, fromIndex: 0, toIndex: 1 })
  const nodeId = found.nodeIds[0]
  await send('CSS.forcePseudoState', { nodeId, forcedPseudoClasses: ['hover'] })
  await sleep(300)
  return {
    opacity: await evaluate(opacityExpr(msgId), { awaitPromise: false }),
    mechanism: 'css-forced-hover',
    nodeId
  }
}

/**
 * 点复制按钮并等效果出现：先用 CDP 真实鼠标（hover + press/release），若 `probe()` 未在 1.5s 内
 * 满足则回落 DOM `.click()`。返回实际生效的方式（落盘留痕）——CDP 输入在窗口被遮挡时会落空，
 * 回落路径保证"复制行为"这一被测对象仍被确定性地驱动。
 */
async function clickCopyAndWait(msgId, center, probe) {
  await mouseHover(center.x, center.y)
  await mouseClick(center.x, center.y)
  for (let i = 0; i < 8; i++) {
    await sleep(120)
    if (await probe()) return 'cdp-mouse'
  }
  await evaluate(`document.querySelector('[data-testid="copy-msg-${msgId}"]').click()`, { awaitPromise: false })
  for (let i = 0; i < 8; i++) {
    await sleep(120)
    if (await probe()) return 'dom-click'
  }
  return 'none'
}

/**
 * 按监听端口反查并杀掉后端进程（苞 B 项 3 的"真断流"回归对照用）。
 *
 * **三重闸**（破坏性动作，默认关闭）：
 *   ① 仅当环境变量 `SMOKE_KILL_BACKEND=1` 显式授权（编排脚本打开，脚本默认不杀任何进程）；
 *   ② 只杀"监听 SMOKE_API 端口"的那一行 PID（netstat 反查，不按名字泛杀）；
 *   ③ 该 PID 的进程名必须是 `java.exe`，否则拒绝执行（防误杀代理/静态服务器）。
 * 授权但三道闸任一不成立 ⇒ 返回 ok=false，调用方如实报 SKIP 而不是假绿。
 */
async function killBackendByListeningPort() {
  if (process.env.SMOKE_KILL_BACKEND !== '1') {
    return { ok: false, reason: 'SMOKE_KILL_BACKEND 未置 1（默认不杀进程；本阶段按 SKIP 处理）' }
  }
  const port = new URL(API).port || '80'
  let netstat = ''
  try {
    netstat = execSync('netstat -ano', { encoding: 'utf8' })
  } catch {
    return { ok: false, reason: 'netstat 执行失败' }
  }
  const row = netstat.split(/\r?\n/).find((line) => line.includes('LISTENING') && new RegExp(`:${port}\\s`).test(line))
  const pid = row ? row.trim().split(/\s+/).pop() : null
  if (!pid) {
    return { ok: false, reason: `未找到监听 ${port} 的进程` }
  }
  let tasklist = ''
  try {
    tasklist = execSync(`tasklist /FI "PID eq ${pid}" /NH`, { encoding: 'utf8' })
  } catch {
    tasklist = ''
  }
  if (!/java\.exe/i.test(tasklist)) {
    return { ok: false, reason: `PID ${pid} 不是 java.exe，拒绝杀（tasklist: ${tasklist.trim().slice(0, 60)}）` }
  }
  try {
    execSync(`taskkill /PID ${pid} /F`, { encoding: 'utf8' })
  } catch {
    return { ok: false, reason: `taskkill /PID ${pid} 失败` }
  }
  return { ok: true, pid, port, processName: 'java.exe' }
}

/** 等局部提示消失（生命周期 2s）—— 好让下一次点击的"提示出现"断言有区分度。 */
async function waitHintGone(msgId) {
  return waitFor(`!document.querySelector('[data-testid="copy-hint-${msgId}"]')`, { tries: 20, intervalMs: 250 })
}

/**
 * 剪贴板回读 —— ⑧ 的**旁证**，不参与 ⑧ 的判定。
 *
 * 原实现 `await evaluate('navigator.clipboard.readText()')`（`awaitPromise: true`）没有上界：
 * 在无剪贴板读权限的环境里该 promise **永不 settled**（原生权限提示无人作答）⇒ 整个脚本挂死在
 * 阶段 8（复核报告 D1 实测：日志停在阶段 7 后 57s+ 无新增、进程仍在）。这里给显式上界
 * （`CLIPBOARD_READ_TIMEOUT_MS`，默认 5s）并把三种"取不到"的形态一律降级为**如实 SKIP**：
 *   - 超时（promise 未 settled）→ `TIMEOUT>…`；
 *   - 页面侧 `readText()` 被拒（无权限 / 非聚焦）→ `NO_PERMISSION(NotAllowedError…)`；
 *   - `Runtime.evaluate` 自身报错 → `EVAL_ERR:…`。
 * 不假绿：⑧ 的判据仍只由 `__copyCalls` 的逐字符比对 + 「已复制」+ `notice === null` 承担，
 * 本项 SKIP 不改变 ⑧ 的 PASS/FAIL；不挂死：超时的那次 evaluate 悬空即弃（其 waiter 不再被消费），
 * 阶段 9/10 的后续断言不受影响。
 */
async function readClipboardBounded() {
  const expr = `(async () => { try { return await navigator.clipboard.readText() } catch (e) { return 'READ_DENIED:' + (e && e.name ? e.name : 'unknown') } })()`
  const raced = await Promise.race([
    evaluate(expr)
      .then((v) => ({ settled: true, v }))
      .catch((e) => ({ settled: true, err: String(e && e.message ? e.message : e) })),
    sleep(CLIPBOARD_READ_TIMEOUT_MS).then(() => ({ settled: false }))
  ])
  if (!raced.settled) {
    return { status: 'SKIP', value: null, reason: `TIMEOUT>${CLIPBOARD_READ_TIMEOUT_MS}ms（剪贴板读未在限内答复：无权限环境/权限提示未作答）` }
  }
  if (raced.err) return { status: 'SKIP', value: null, reason: `EVAL_ERR:${raced.err.slice(0, 80)}` }
  if (typeof raced.v === 'string' && raced.v.startsWith('READ_DENIED:')) {
    return { status: 'SKIP', value: null, reason: `NO_PERMISSION(${raced.v.slice('READ_DENIED:'.length)})：剪贴板读未授权（CDP 下常被拒）` }
  }
  return { status: 'OK', value: raced.v, reason: null }
}

try {
  // ── 打开应用（先清空本页签的会话与镜像，保证从"空态"开始） ──────────────
  await send('Page.navigate', { url: URL_APP })
  if (!await waitAppReady()) { console.error('FATAL: 应用/store 未就绪'); process.exit(3) }
  console.log('[app] ready')
  await evaluate(`(() => {
    for (const k of Object.keys(sessionStorage)) { if (k.startsWith('ai-mirror:') || k === 'ai-session-id') sessionStorage.removeItem(k) }
    return true
  })()`, { awaitPromise: false })
  await send('Page.navigate', { url: URL_APP })
  if (!await waitAppReady()) { console.error('FATAL: 刷新后应用未就绪'); process.exit(3) }
  await evaluate(`document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai').expanded = true`, { awaitPromise: false })
  await sleep(600)

  // ── 阶段 1：空态（无消息） ─────────────────────────────────────────────
  const s1 = await evaluate(snapshotExpr(), { awaitPromise: false })
  RESULT.phases.empty = s1
  console.log('[阶段1 空态]', JSON.stringify(s1))
  assert('①', s1.messages === 0 && s1.buttonExists && s1.buttonDisabled === true && s1.buttonText === '新建对话' && s1.hintVisible,
    { messages: s1.messages, buttonDisabled: s1.buttonDisabled, buttonText: s1.buttonText, hintVisible: s1.hintVisible })

  // ── 阶段 2：真实一轮对话（桩上游的文本收尾）→ 按钮可点 + 后端有记忆 ────
  const sid1 = s1.sessionId
  await evaluate(`(async () => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    await ai.send('你好')   // 与面板「发送」同一条动作（同一 store 动作），只省略输入框
    return true
  })()`)
  const s2 = await waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.messages.length > 0 && !ai.loading
  })()`)
  const snap2 = await evaluate(snapshotExpr(), { awaitPromise: false })
  const count1 = await waitCount(sid1, (n) => n > 0)
  RESULT.phases.afterRound = { ...snap2, backendHistoryCount: count1 }
  console.log('[阶段2 一轮后]', JSON.stringify(RESULT.phases.afterRound))
  assert('②', !!s2 && snap2.messages >= 2 && snap2.buttonDisabled === false && count1 > 0,
    { messages: snap2.messages, buttonDisabled: snap2.buttonDisabled, backendHistoryCount: count1, sessionId: sid1 })

  // ── 阶段 3：点「新建对话」 ─────────────────────────────────────────────
  await clickNewChat()
  await waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.sessionId !== ${JSON.stringify(sid1)}
  })()`)
  await sleep(1200) // 让 best-effort 的 POST cancel / DELETE history 落定
  const s3 = await evaluate(snapshotExpr(), { awaitPromise: false })
  const oldCount = await waitCount(sid1, (n) => n === 0)
  RESULT.phases.afterNewChat = { ...s3, oldSessionHistoryCount: oldCount }
  console.log('[阶段3 新建对话后]', JSON.stringify(RESULT.phases.afterNewChat))
  // 注：新会话自己会有一个（空的）镜像键 —— 这里只断言**旧**镜像键已被删（施工卡 §1.2 验收②）
  assert('③', s3.messages === 0 && s3.buttonDisabled === true
    && s3.sessionId !== sid1 && s3.storedSessionId === s3.sessionId
    && s3.mirrorKeys.includes('ai-mirror:' + sid1) === false
    && s3.notice === null      // 删除未失败（失败会写 notice「旧会话记忆删除失败（不影响新对话）」）
    && oldCount === 0,
  {
    messages: s3.messages, buttonDisabled: s3.buttonDisabled, notice: s3.notice,
    sessionIdChanged: s3.sessionId !== sid1, stateMatchesStorage: s3.storedSessionId === s3.sessionId,
    oldMirrorRemoved: !s3.mirrorKeys.includes('ai-mirror:' + sid1), oldSessionHistoryCount: oldCount
  })

  // ── 阶段 4：新会话再发一轮 → 记忆记在新 id 上（缺陷修复的行为级证据） ──
  const sid2 = s3.sessionId
  await evaluate(`(async () => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    await ai.send('第二条')
    return true
  })()`)
  await waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.messages.length > 0 && !ai.loading
  })()`)
  await sleep(600)
  const newCount = await waitCount(sid2, (n) => n > 0)
  const oldCount2 = await historyCount(sid1)
  RESULT.phases.secondRound = { newSessionId: sid2, newSessionHistoryCount: newCount, oldSessionHistoryCount: oldCount2 }
  console.log('[阶段4 新会话一轮]', JSON.stringify(RESULT.phases.secondRound))
  assert('④', newCount > 0 && oldCount2 === 0,
    { newSessionHistoryCount: newCount, oldSessionHistoryCount: oldCount2 })

  // ── 阶段 5：置灰态点击无副作用 ─────────────────────────────────────────
  await clickNewChat() // 消息非空：这次是真点
  await waitFor(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    return ai.messages.length === 0
  })()`)
  const s5 = await evaluate(snapshotExpr(), { awaitPromise: false })
  await clickNewChat() // 已置灰：点不动
  await sleep(400)
  const s5b = await evaluate(snapshotExpr(), { awaitPromise: false })
  RESULT.phases.disabledClick = { before: s5.sessionId, after: s5b.sessionId, disabled: s5b.buttonDisabled }
  console.log('[阶段5 置灰点击]', JSON.stringify(RESULT.phases.disabledClick))
  assert('⑤', s5b.buttonDisabled === true && s5b.sessionId === s5.sessionId && s5b.messages === 0,
    { sessionIdUnchanged: s5b.sessionId === s5.sessionId, buttonDisabled: s5b.buttonDisabled })

  // ══════════════════════════════════════════════════════════════════════════
  // 苞 B（R-101）：项 2 逐条复制 / 项 3 停止不误闪 / 项 4 骨架屏接线
  // ══════════════════════════════════════════════════════════════════════════

  // ── 阶段 6：复制按钮的结构与「默认不显现」 ──────────────────────────────
  await sendRound('你好')
  const target = await evaluate(`(() => {
    const ai = ${AI_EXPR}
    const withText = ai.messages.filter(m => m.content && m.content.length > 0)
    const last = withText[withText.length - 1]
    return last ? { id: last.id, content: last.content, withText: withText.length, total: ai.messages.length } : null
  })()`, { awaitPromise: false })
  const structure = await evaluate(`(() => {
    const ai = ${AI_EXPR}
    const withText = ai.messages.filter(m => m.content && m.content.length > 0)
    const blank = ai.messages.filter(m => !m.content || m.content.length === 0)
    return {
      withText: withText.length,
      blank: blank.length,
      missing: withText.filter(m => !document.querySelector('[data-testid="copy-msg-' + m.id + '"]')).map(m => m.id),
      leaked: blank.filter(m => document.querySelector('[data-testid="copy-msg-' + m.id + '"]')).map(m => m.id)
    }
  })()`, { awaitPromise: false })
  const s6 = await evaluate(msgFactsExpr(target.id), { awaitPromise: false })
  RESULT.phases.copyStructure = { ...s6, ...structure, contentLength: target.content.length }
  console.log('[阶段6 复制按钮结构]', JSON.stringify(RESULT.phases.copyStructure))
  assert('⑥', !!target && structure.missing.length === 0 && structure.leaked.length === 0
    && s6.buttonExists && s6.buttonText === '复制' && s6.buttonOpacity === '0' && s6.skeleton === false,
  {
    messagesWithText: structure.withText, blankMessages: structure.blank,
    missingButtons: structure.missing, leakedButtons: structure.leaked,
    defaultOpacity: s6.buttonOpacity, skeletonWhileMessagesExist: s6.skeleton
  })

  // ── 阶段 7：hover 才显现（Tailwind group-hover） ────────────────────────
  const reveal = await revealCopyButton(target.id, s6.center)
  RESULT.phases.hoverReveal = { before: s6.buttonOpacity, after: reveal.opacity, mechanism: reveal.mechanism }
  console.log('[阶段7 hover 显现]', JSON.stringify(RESULT.phases.hoverReveal))
  assert('⑦', reveal.opacity === '1',
    { opacityBeforeHover: s6.buttonOpacity, opacityAfterReveal: reveal.opacity, mechanism: reveal.mechanism })

  // ── 阶段 8：主路径 —— clipboard API + 局部「已复制」（不占 notice） ─────
  const clipReady = await evaluate(`(() => {
    window.__clipWasDefined = typeof navigator.clipboard !== 'undefined' && navigator.clipboard !== null
    window.__origClipboard = navigator.clipboard
    window.__copyCalls = []
    const c = navigator.clipboard
    if (!c || typeof c.writeText !== 'function') { return false }
    const orig = c.writeText.bind(c)
    try { c.writeText = (t) => { window.__copyCalls.push(t); return orig(t) } } catch { return false }
    return true
  })()`, { awaitPromise: false })
  const center8 = (await evaluate(msgFactsExpr(target.id), { awaitPromise: false })).center
  const click8 = await clickCopyAndWait(target.id, center8, async () => {
    const n = await evaluate('window.__copyCalls.length', { awaitPromise: false })
    return typeof n === 'number' && n > 0
  })
  const s8 = await evaluate(msgFactsExpr(target.id), { awaitPromise: false })
  const calls8 = await evaluate('window.__copyCalls', { awaitPromise: false })
  const read8 = await readClipboardBounded()
  const exact8 = Array.isArray(calls8) && calls8.length >= 1 && calls8[0] === target.content
  RESULT.phases.copyClipboardPath = {
    clipboardApiAvailable: clipReady, clickVia: click8, writeCalls: Array.isArray(calls8) ? calls8.length : null,
    exactMatch: exact8, hintText: s8.hintText, notice: s8.notice,
    clipboardReadback: read8.value, clipboardReadbackStatus: read8.status, clipboardReadbackReason: read8.reason
  }
  if (read8.status !== 'OK') { RESULT.skipped = { ...(RESULT.skipped || {}), '⑧·旁证(剪贴板回读)': `${read8.status}: ${read8.reason}` } }
  const read8Brief = read8.status === 'OK' ? String(read8.value).slice(0, 60) : `SKIP(${read8.reason})`
  console.log('[阶段8 复制·主路径]', JSON.stringify({ ...RESULT.phases.copyClipboardPath, clipboardReadback: read8Brief }))
  if (read8.status !== 'OK') { console.log(`[阶段8 回读旁证] SKIP ${read8.reason}`) }
  assert('⑧', clipReady === true && click8 !== 'none' && exact8 && s8.hintText === '已复制' && s8.notice === null,
  {
    clipboardApiAvailable: clipReady, clickVia: click8, writeCalls: Array.isArray(calls8) ? calls8.length : null,
    exactMatch: exact8, hintText: s8.hintText, noticeHeldByNotice: s8.notice,
    clipboardReadback: read8.status === 'OK' ? String(read8.value).slice(0, 60) : null,
    clipboardReadbackStatus: read8.status, clipboardReadbackReason: read8.reason
  })

  // ── 阶段 9：回退 A —— clipboard 不可用（经内网 http 访问的非安全上下文） ──
  await waitHintGone(target.id)
  const fallbackReady = await evaluate(`(() => {
    window.__execCalls = []
    window.__execTexts = []
    window.__origExecCommand = document.execCommand.bind(document)
    document.execCommand = (cmd) => {
      window.__execCalls.push(cmd)
      const el = document.activeElement
      window.__execTexts.push(el && typeof el.value === 'string' ? el.value : null)
      return window.__origExecCommand(cmd)
    }
    Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true })
    return navigator.clipboard === undefined
  })()`, { awaitPromise: false })
  const center9 = (await evaluate(msgFactsExpr(target.id), { awaitPromise: false })).center
  const click9 = await clickCopyAndWait(target.id, center9, async () => {
    const n = await evaluate('window.__execCalls.length', { awaitPromise: false })
    return typeof n === 'number' && n > 0
  })
  const s9 = await evaluate(msgFactsExpr(target.id), { awaitPromise: false })
  const exec9 = await evaluate('({ calls: window.__execCalls, texts: window.__execTexts })', { awaitPromise: false })
  const exact9 = Array.isArray(exec9.calls) && exec9.calls.includes('copy') && exec9.texts[0] === target.content
  RESULT.phases.copyFallbackUndefined = {
    clipboardForcedUndefined: fallbackReady, clickVia: click9, execCommands: exec9.calls,
    exactMatch: exact9, hintText: s9.hintText, notice: s9.notice
  }
  console.log('[阶段9 复制·回退A(clipboard 缺失)]', JSON.stringify(RESULT.phases.copyFallbackUndefined))
  assert('⑨', fallbackReady === true && click9 !== 'none' && exact9 && s9.hintText === '已复制' && s9.notice === null,
  {
    clipboardForcedUndefined: fallbackReady, clickVia: click9, execCommands: exec9.calls,
    exactMatch: exact9, hintText: s9.hintText, noticeHeldByNotice: s9.notice
  })

  // ── 阶段 10：回退 B —— clipboard 存在但 writeText 被拒 ─────────────────
  await waitHintGone(target.id)
  const rejectReady = await evaluate(`(() => {
    window.__execCalls = []
    window.__execTexts = []
    window.__clipRejects = 0
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: () => { window.__clipRejects += 1; return Promise.reject(new Error('NotAllowedError')) } },
      configurable: true
    })
    return typeof navigator.clipboard.writeText === 'function'
  })()`, { awaitPromise: false })
  const center10 = (await evaluate(msgFactsExpr(target.id), { awaitPromise: false })).center
  const click10 = await clickCopyAndWait(target.id, center10, async () => {
    const n = await evaluate('window.__execCalls.length', { awaitPromise: false })
    return typeof n === 'number' && n > 0
  })
  const s10 = await evaluate(msgFactsExpr(target.id), { awaitPromise: false })
  const exec10 = await evaluate('({ calls: window.__execCalls, texts: window.__execTexts, rejects: window.__clipRejects })', { awaitPromise: false })
  const exact10 = Array.isArray(exec10.calls) && exec10.calls.includes('copy') && exec10.texts[0] === target.content
  RESULT.phases.copyFallbackRejected = {
    writeTextRejects: exec10.rejects, clickVia: click10, execCommands: exec10.calls,
    exactMatch: exact10, hintText: s10.hintText, notice: s10.notice
  }
  console.log('[阶段10 复制·回退B(writeText 被拒)]', JSON.stringify(RESULT.phases.copyFallbackRejected))
  assert('⑩', rejectReady === true && click10 !== 'none' && exec10.rejects === 1 && exact10 && s10.hintText === '已复制' && s10.notice === null,
  {
    writeTextRejects: exec10.rejects, clickVia: click10, execCommands: exec10.calls, exactMatch: exact10,
    hintText: s10.hintText, noticeHeldByNotice: s10.notice
  })

  // 还原被注入的剪贴板/execCommand（后续阶段不再依赖它们）
  await evaluate(`(() => {
    if (window.__clipWasDefined) { Object.defineProperty(navigator, 'clipboard', { value: window.__origClipboard, configurable: true }) }
    else { delete navigator.clipboard }
    if (window.__origExecCommand) { document.execCommand = window.__origExecCommand }
    return true
  })()`, { awaitPromise: false })

  // ── 阶段 11：停止不误闪红条（项 3 主证据） ──────────────────────────────
  const CONFIRM_PROMPT = '请立即调用 start_export 工具启动任务 1 的导出作业（系统会自动弹出确认卡片，无需再询问我）'
  const inflight1 = await startSuspendedRound(CONFIRM_PROMPT)
  const beforeStop = await evaluate(msgFactsExpr(target.id), { awaitPromise: false })
  await evaluate(`(async () => { await ${AI_EXPR}.stop(); return true })()`)
  await sleep(1200)
  const s11 = await evaluate(msgFactsExpr(target.id), { awaitPromise: false })
  RESULT.phases.stopNoRedBanner = {
    inflightBeforeStop: inflight1, loadingBeforeStop: beforeStop.loading, suspendedBeforeStop: beforeStop.suspended,
    stopped: s11.stopped, streamInterrupted: s11.streamInterrupted,
    interruptedBanner: s11.interruptedBanner, notice: s11.notice
  }
  console.log('[阶段11 停止]', JSON.stringify(RESULT.phases.stopNoRedBanner))
  assert('⑪', !!inflight1 && beforeStop.loading === true
    && s11.stopped === true && s11.streamInterrupted === false
    && s11.interruptedBanner === false && s11.notice === '已请求取消本轮对话',
  {
    inflightRunId: inflight1 ? inflight1.runId : null, suspendedBeforeStop: beforeStop.suspended,
    stopped: s11.stopped, streamInterrupted: s11.streamInterrupted,
    redBannerPresent: s11.interruptedBanner, notice: s11.notice
  })

  // ── 阶段 13：骨架屏按 historyLoaded 接线（项 4） ────────────────────────
  // 手法：清镜像后 reload，并用 Fetch 域把历史请求**挂住**（不放行）⇒ 稳定停在"无消息 + 对账未完成"
  // 的窗口内，骨架屏必现；放行后骨架屏消失、空态出现（与空态互斥的 v-if / v-else-if 即在此被证）。
  await send('Fetch.enable', { patterns: [{ urlPattern: '*api/ai/history*', requestStage: 'Request' }] })
  pausedHistoryId = null
  await evaluate(`(() => {
    for (const k of Object.keys(sessionStorage)) { if (k.startsWith('ai-mirror:') || k === 'ai-session-id') sessionStorage.removeItem(k) }
    return true
  })()`, { awaitPromise: false })
  await send('Page.navigate', { url: URL_APP })
  let heldId = null
  for (let i = 0; i < 150 && heldId === null; i++) { heldId = pausedHistoryId; if (heldId === null) await sleep(100) }
  if (!await waitAppReady()) { console.error('FATAL: 骨架屏阶段刷新后应用未就绪'); process.exit(3) }
  await evaluate(`${AI_EXPR}.expanded = true`, { awaitPromise: false })
  await sleep(600)
  const s13 = await evaluate(msgFactsExpr(target.id), { awaitPromise: false })
  if (heldId !== null) { await send('Fetch.continueRequest', { requestId: heldId }) }
  const s13b = await waitFor(
    `(() => { const ai = ${AI_EXPR}
      return (ai.historyLoaded && !document.querySelector('[data-testid="history-skeleton"]'))
        ? { historyLoaded: ai.historyLoaded, skeleton: !!document.querySelector('[data-testid="history-skeleton"]'),
            messages: ai.messages.length, emptyCard: document.body.innerText.includes('您好，我是实施助手') }
        : null })()`,
    { tries: 60, intervalMs: 250 }
  )
  await send('Fetch.disable')
  RESULT.phases.historySkeleton = {
    historyRequestHeld: heldId !== null,
    whileHeld: { skeleton: s13.skeleton, historyLoaded: s13.historyLoaded, messages: s13.messages, emptyCard: s13.emptyCard },
    afterRelease: s13b
  }
  console.log('[阶段13 骨架屏]', JSON.stringify(RESULT.phases.historySkeleton))
  assert('⑬', heldId !== null && s13.skeleton === true && s13.historyLoaded === false
    && s13.messages === 0 && s13.emptyCard === false
    && !!s13b && s13b.skeleton === false && s13b.emptyCard === true,
  RESULT.phases.historySkeleton)

  // ── 阶段 12：回归对照 —— 真断流（未调 stop）红条照常出现 ────────────────
  // **放在最后**：本阶段按施工卡 §3.3 验收②的原话口径制造真断流（kill 后端进程），后端被杀后
  // 不再可用 —— 之后若有阶段依赖后端会假红。
  //
  // 为什么不走 CDP 断网：实测 `Network.emulateNetworkConditions{offline:true}` 只拦新请求，
  // **不会拆掉已在飞的流式响应**（本苞首轮实跑：35s 内 streamInterrupted 一直 false、notice 一直
  // null ⇒ 连接根本没被动过）。故改用"杀后端进程"这一真实断链。
  //
  // 为什么本断言的等待上界要 150s（其他阶段都是秒级）：dev 形态下 SSE 走前端 dev server 的
  // `/api` 代理（`SSE_PATHS.chat` 是相对路径），后端被杀的瞬间代理打的是 `http proxy error`，
  // 但它**不结束客户端响应**⇒ 浏览器侧那条流只是"不再来字节"，不是"报错收尾"。于是收尾改由产品
  // 自己的**帧间静默看门狗**触发（`DEFAULT_IDLE_TIMEOUT_MS=90s`，挂起期后端每 2s 一次心跳：
  // 心跳一停即倒计时）→ 判定断流 → reattach（后端已死，失败）→ `onClose(terminal=false)` → 红条。
  // 即：本阶段同时证到"真断流仍会亮红条"与"看门狗兜底有效"，代价是约 92s 的确定性等待。
  //
  // 破坏性守则（默认关闭 + 三重闸，见 killBackendByListeningPort）：未授权或闸不成立时本阶段
  // 如实报 **SKIP**（不假绿）；本棒的编排脚本显式 `SMOKE_KILL_BACKEND=1` 打开该闸。
  //
  // 先换新会话再起这一轮：桩上游的"轮次台账"以请求里**首条 user 消息**为指纹，同一指纹下同一条
  // 路由只允许发起一次（桩的收敛契约，防工具循环风暴）——沿用旧会话会退化成文本轮、挂不起来。
  await evaluate(`(async () => { await ${AI_EXPR}.resetSession(); return true })()`)
  const inflight2 = await startSuspendedRound(CONFIRM_PROMPT)
  const killed = inflight2
    ? await killBackendByListeningPort()
    : { ok: false, reason: '未进入挂起态（未执行杀进程）' }
  const samples = []
  let sawInterrupt = null
  if (killed.ok) {
    const t0 = Date.now()
    for (let i = 0; i < 600; i++) { // 150s 上界：帧间静默看门狗 90s + reattach 退避
      const snap = await evaluate(
        `(() => { const ai = ${AI_EXPR}
          return { loading: ai.loading, suspended: ai.suspended, streamInterrupted: ai.streamInterrupted,
                   notice: ai.notice, banner: document.body.innerText.includes('连接中断，结果可能不完整') } })()`,
        { awaitPromise: false }
      )
      const atSec = Math.round((Date.now() - t0) / 1000)
      if (i === 0 || snap.streamInterrupted || i % 40 === 0) samples.push({ atSec, ...snap })
      if (snap.streamInterrupted) { sawInterrupt = { atSec, ...snap }; break }
      await sleep(250)
    }
  }
  const s12 = killed.ok ? await evaluate(msgFactsExpr(target.id), { awaitPromise: false }) : null
  RESULT.phases.realInterruptStillRed = {
    inflightBeforeKill: inflight2, backendKill: killed, sawInterrupt, samples,
    streamInterrupted: s12 ? s12.streamInterrupted : null,
    stopped: s12 ? s12.stopped : null,
    interruptedBanner: s12 ? s12.interruptedBanner : null,
    notice: s12 ? s12.notice : null
  }
  console.log('[阶段12 真断流对照]', JSON.stringify(RESULT.phases.realInterruptStillRed))
  if (!killed.ok) {
    RESULT.skipped = { ...(RESULT.skipped || {}), '⑫': killed.reason }
    console.log(`[断言⑫] SKIP ${killed.reason}`)
  } else {
    assert('⑫', !!inflight2 && !!sawInterrupt && !!s12 && s12.streamInterrupted === true
      && s12.interruptedBanner === true && s12.stopped === false,
    {
      inflightRunId: inflight2 ? inflight2.runId : null, backendKillPid: killed.pid,
      streamInterrupted: s12 ? s12.streamInterrupted : null,
      redBannerPresent: s12 ? s12.interruptedBanner : null, stopped: s12 ? s12.stopped : null
    })
  }

  // ── 收尾清理（不校验：清掉本轮留下的会话与记忆） ──────────────────────
  await evaluate(`(async () => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    await ai.resetSession()
    return true
  })()`)
} catch (err) {
  console.error('FATAL:', err)
  failures.push('exception')
} finally {
  RESULT.round = { startedAt: STAMP, outDir: OUT, chrome: CHROME, api: API, app: URL_APP, resultFile: RESULT_FILE }
  writeFileSync(RESULT_FILE, JSON.stringify(RESULT, null, 2))
  console.log('[done] result written to', RESULT_FILE)
  ws.close()
  chrome.kill()
  await sleep(1500)
  process.exit(failures.length === 0 ? 0 : 1)
}
