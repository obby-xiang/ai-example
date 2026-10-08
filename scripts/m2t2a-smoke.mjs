// M2-T2a 唯一投影化 CDP 冒烟（T2a-D#5：三断言清单脚本化，可重复执行）
//
// 用法（仓库根目录，Node ≥ 22 —— 仅用内置 WebSocket + fetch，零依赖安装）：
//   0. 前置：Memurai 127.0.0.1:6379 在线；后端已起（默认 http://localhost:18318，
//      与 frontend/.env.local 的 VITE_API_BASE 对齐）；前端 dev server 已起（默认 5202）。
//   1. node scripts/m2t2a-smoke.mjs
//   2. 可选环境变量：
//      SMOKE_APP=http://localhost:5202/     前端地址
//      SMOKE_CDP_PORT=9333                  Chrome 远程调试端口
//      SMOKE_CHROME=<可执行文件>            Chrome 路径（缺省按 ProgramFiles 环境变量组装，源码不写死盘符）
//      SMOKE_OUT=<dir>                      截图/结果输出目录（默认 <repo>/../m2t2a-smoke-out）
//   3. 产物分轮保留：结果写 result-<UTC 时间戳>.json，同一 SMOKE_OUT 重复执行不覆盖前一轮
//
// 三断言（全部 PASS 退出码 0，任一 FAIL 退出码 1）：
//   ① 挂起中刷新后页面只有一张表单卡（单卡）
//   ② 同一 toolCallId 不出现双份 FormRenderer（同 toolCallId 至多一个表单实例）
//   ③ 取消后所有持该 toolCallId 的消息 pendingCall.status 收敛为 cancelled
//
// 方法：CDP 直连独立 Chrome（独立 user-data-dir），经 Pinia store 注入
// frontend_tool_request 帧复现挂起形态（与 GFd 冒烟同法，无模型参与）。
// 本脚本只驱动自己启动的 Chrome，不触碰用户浏览器。
import { spawn } from 'node:child_process'
import { writeFileSync, mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { setTimeout as sleep } from 'node:timers/promises'

// ── S7（红队建议）：断言②③ 的计数与点击依赖 UI **文案**，属已知的脆弱耦合 —— 集中在此处 ──
// 来源：FormRenderer.vue 的两个操作按钮（"取消"/"提交"）、AiPanel.vue 表单卡的取消终态 tag
//       （"表单已取消"）。任一处文案变更而未同步本表，冒烟会静默误报（计不到 = 断言误判）。
// T2b 候选改造：按 data-testid / 组件实例定位，解掉文案耦合（本轮不做，仅集中 + 注释）。
const TEXT_SUBMIT = '提交'
const TEXT_CANCEL = '取消'
const TEXT_FORM_CANCELLED = '表单已取消'

const OUT = process.env.SMOKE_OUT ?? new URL('../../m2t2a-smoke-out', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')
// Chrome 可执行文件不写死盘符：SMOKE_CHROME 优先，缺省按 ProgramFiles 环境变量组装
const CHROME = process.env.SMOKE_CHROME
  ?? join(process.env.ProgramFiles || '', 'Google/Chrome/Application/chrome.exe')
const PORT = Number(process.env.SMOKE_CDP_PORT ?? 9333)
const URL_APP = process.env.SMOKE_APP ?? 'http://localhost:5202/'
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

const countExpr = (toolCallId) => `(() => {
  const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
  return {
    formRendererCount: [...document.querySelectorAll('button')].filter(b => b.textContent.trim() === '${TEXT_SUBMIT}').length,
    cancelButtons: [...document.querySelectorAll('button')].filter(b => b.textContent.trim() === '${TEXT_CANCEL}').length,
    pendingCards: ai.messages.filter(m => m.pendingCall && m.pendingCall.toolCallId === '${toolCallId}').length,
    pendingStatuses: ai.messages.filter(m => m.pendingCall && m.pendingCall.toolCallId === '${toolCallId}').map(m => m.pendingCall.status),
    activeFormStatus: ai.activeForm ? ai.activeForm.status : null,
    cancelledTags: [...document.querySelectorAll('.el-tag')].filter(e => e.textContent.trim() === '${TEXT_FORM_CANCELLED}').length
  }
})()`

const RESULT = { assertions: {}, phases: {} }
const failures = []
function assert(name, cond, detail) {
  RESULT.assertions[name] = { pass: !!cond, detail }
  console.log(`[断言${name}] ${cond ? 'PASS' : 'FAIL'} ${JSON.stringify(detail)}`)
  if (!cond) failures.push(name)
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
    const btn = [...document.querySelectorAll('button')].find(b => b.textContent.trim() === '${TEXT_CANCEL}')
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

  // ── 阶段 3：单消息挂起 → 刷新 → 断言①单卡 ─────────────────────────────
  // 注意：不用 resetSession（它只写新 sessionId 进 storage、不改 store.sessionId，
  // 与刷新恢复路径无关）；改用新 toolCallId 注入第三条消息，旧卡保留不影响计数。
  const R3 = crypto.randomUUID(), T3 = crypto.randomUUID()
  await evaluate(injectExpr(R3, T3, 1))
  await sleep(800) // 等镜像防抖（300ms）落盘
  const preRefresh = await evaluate(countExpr(T3))
  console.log('[阶段3 刷新前]', JSON.stringify(preRefresh))
  await send('Page.navigate', { url: URL_APP }) // 整页刷新（镜像经 sessionStorage 恢复）
  if (!await waitAppReady()) { console.error('FATAL: 刷新后应用未就绪'); process.exit(3) }
  await sleep(800)
  const postRefresh = await evaluate(countExpr(T3))
  RESULT.phases.refresh = { runId: R3, toolCallId: T3, preRefresh, postRefresh }
  console.log('[阶段3 刷新后]', JSON.stringify(postRefresh))
  // 刷新后 activeForm 不进镜像（T2a 范围外，T2b 刷新续填）：卡应为单张挂起卡
  // （后端历史为空 → pending 按既有规则降级 expired 展示，仍只此一张）
  assert('①', postRefresh.pendingCards === 1 && postRefresh.formRendererCount === 0,
    { pendingCards: postRefresh.pendingCards, formRendererCount: postRefresh.formRendererCount, pendingStatuses: postRefresh.pendingStatuses })

  // ── 收尾清理 ──────────────────────────────────────────────────────────
  await evaluate(`(() => {
    const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
    ai.resetSession(); return true
  })()`)
} catch (err) {
  console.error('FATAL:', err)
  failures.push('exception')
} finally {
  RESULT.round = { startedAt: STAMP, outDir: OUT, chrome: CHROME, resultFile: RESULT_FILE }
  writeFileSync(RESULT_FILE, JSON.stringify(RESULT, null, 2))
  console.log('[done] result written to', RESULT_FILE)
  ws.close()
  chrome.kill()
  await sleep(1500)
  process.exit(failures.length === 0 ? 0 : 1)
}
