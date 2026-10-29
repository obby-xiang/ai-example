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
//                                             此时 ⑫ 如实报 SKIP（不假绿）。**四重闸**见
//                                             killPrecondition / killBackendByListeningPort：8081 / 5202 /
//                                             6379 是保护端口，**授权也不杀** ⇒ 标准跑法（页面 5202 →
//                                             代理 → 8081）下 ⑫ 恒为显式 SKIP [PROTECTED_PORT]。
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
//   ⑥ 项 2 结构：每条**正文非空**的消息都有复制按钮，正文为空的消息没有；按钮是**图标钮**
//      （`svg.i-copy` + 隐藏的 `svg.i-check` + 气泡 tooltip，无障碍名由 `aria-label` 承载）且
//      **常驻可见**（R-165 S4 撤了 `opacity: 0` / group-hover 浮出机制，默认 `opacity: 1`）；
//      有消息时骨架屏不出现；
//   ⑦ 项 2 可点性：CDP 真实鼠标移到按钮上 → 按钮仍可见、命中点落在按钮自身（⑧⑨⑩ 的真鼠标
//      点击依赖这一性质；浮出机制撤掉后本条不再证「hover 才显现」）；
//   ⑧ 项 2 主路径：真实点击 → `navigator.clipboard.writeText` 收到的字符串 === **全部 text 段
//      `join('\n\n')`**（R-158 #11 复制口径，与 `deriveCopyText` 同源；不含思考链/工具卡、不 trim）
//      —— 注意它 ≠ `m.content`（content 是段间无间隔拼接），这是本批修的判据过时项之一；
//      成功反馈 = 钮挂 `is-copied`（绿勾 2s，v7 `14c` 口径）且**无**失败提示 `copy-hint-{id}`
//      + **`ai.notice` 仍为 null**（反馈不占单槽位）；另附剪贴板回读（**非断言**，CDP 下常被拒；
//      带 5s 上界——超时/无权限如实 SKIP 并把理由落盘，详见 readClipboardBounded）；
//   ⑨ 项 2 回退 A：把 `navigator.clipboard` 置为 undefined（模拟经内网 http 访问的非安全上下文）
//      → 点击走 `document.execCommand('copy')`，回退文本 === 同一 `join('\n\n')` 口径，`is-copied` 仍出现；
//   ⑩ 项 2 回退 B：`clipboard.writeText` 存在但**拒绝**（模拟权限/非聚焦被拒）→ 同样落回退路径，
//      `is-copied` 仍出现（不得静默失败）；
//   ⑪ 项 3 主证据：**先造在途轮**（甲 = 换新会话后起一轮 DANGER 工具 → 确认门挂起；乙 = 长流轮，
//      桩用 `turn-text stall-frames` 撑开帧间隔、真模型用长答提示词）→ 用户点「停止」→
//      `ai.streamInterrupted` **为 false**、页面**无**「连接中断，结果可能不完整」文案、
//      `notice` 保持 null（现行口径：停止不写单槽提示，证据改由消息尾「已停止」chip 承担）
//      + 宿主助手消息挂上 `msg-stopped-{id}` + `/runs/{runId}` 3s 内收敛为 CANCELLED；
//   ⑪ 的前置自证：停止前该轮确在飞（`loading && activeRunId !== null`，且停止钮可见可点）——否则不成立；
//      本批修的是**前置缺失**：原实现沿用上一阶段的会话（上下文已有纯文本轮），上游不会再发
//      DANGER 工具调用 ⇒ 从未进入在途态，点停止只是空转（inflightBeforeStop=null、按钮点不动）；
//   ⑬ 项 4：清镜像后 reload，用 `Fetch` 域把历史请求挂住 → 骨架屏（`data-testid="history-skeleton"`）
//      出现且空态**不**同框（`historyLoaded === false`）→ 放行历史请求 → 骨架屏消失、空态出现；
//   ⑫ 项 3 回归对照（**本脚本的最后一步**）：真断流（kill 后端进程，且**未**调 stop）→ 红条
//      **照常出现**、`ai.streamInterrupted === true`、`ai.stopped === false`（守卫没有削弱真断流路径）。
//      CDP 断网模拟不出真断流（只拦新请求、不拆在飞流，本苞实测）；dev 形态下 SSE 走 dev server 代理，
//      代理不结束客户端响应 ⇒ 收尾由产品自己的帧间静默看门狗触发（90s）⇒ 本步约 92s、上界 150s。
//      需 SMOKE_KILL_BACKEND=1 显式授权，未授权时本断言如实报 SKIP（不假绿）。
//      **可跑条件（本批写清，避免"永远 SKIP 且没人知道为什么"）**：被杀的后端必须就是**页面正在
//      说话的那一个**（SSE 经前端 dev server 的 `/api` 代理转给 `SMOKE_API`）。常开口径下页面 =
//      5202 dev server → 代理 → 8081 后端，而 8081 **禁碰**（本脚本内置 `PROTECTED_PORTS`
//      硬闸：8081 / 5202 / 6379 即使授权也拒杀）⇒ 标准跑法下本步恒为显式 SKIP。
//      可跑形态 = 一次性实例对：后端 22730 + 前端 22731（`VITE_API_BASE=http://localhost:22730`）
//      + Redis DB 2（与其他实例隔离），并令 `SMOKE_API=http://localhost:22730`、
//      `SMOKE_APP=http://localhost:22731/`、`SMOKE_KILL_BACKEND=1`。
//
// 定位纪律：新增按钮一律 data-testid（苞 A `new-chat`；苞 B `copy-msg-{id}` / `copy-hint-{id}` /
// `history-skeleton` / `msg-stopped-{id}`）；其余断言走 `$pinia` 直读 store 状态（手法与
// scripts/m2t2a-smoke.mjs 一致），不耦合 UI 文案 —— 文案改动不再造成静默误判。项 2 的复制成功
// 反馈属**组件局部状态**（钮上 `is-copied` 类 + 绿勾，2s 自清；失败才出 `copy-hint-{id}`），
// 故用该类与 data-testid 断言而不读 `ai.notice`（后者须保持 null，这正是"不占单槽位"的判据）。
// 本脚本只驱动自己启动的 Chrome，不触碰用户浏览器。
//
// ── 苞 C（测试扩充 V7 · RLD 真刷新试点）六条断言 ⑭~⑲ ──────────────────────────
// 承载面 = 决策看板 2026-10-23「UI 层载体 = 扩 chat-panel-smoke.mjs（CDP 零依赖）」。补的是
// jsdom 结构性覆盖不到的那一面：**真 reload**（`Page.navigate` 等于用户按 F5 / `location.href`）后
// 内存态全丢、只剩 sessionStorage 镜像 + 后端历史 —— RLD 域的 P0 场景全部依赖这个形态。
//   ⑭ R-01 在途轮续显：流式中真刷新 → 刷新后仍在途且有正文（不是"只剩用户气泡无任何提示"），
//      无红条、无「接管/重挂」类横幅（批G · G5：用户看不到重挂概念）；
//   ⑮ R-01 收敛：该轮在预算内收尾，正文**续收到尾**（不是停在半截）。判据两臂都严格，按环境取一：
//      桩环境（刷新时刻的部分正文是桩确定性文本的前缀）⇒ 与桩文本**逐字相等**；
//      非桩环境（真模型 / 上游被换）⇒ 与 `/history` 持久化的该轮 assistant 正文**逐字相等**且比
//      部分正文更长。为什么不写死桩文本：常开后端若接真模型，逐字比对桩文本永远红（不是产品缺陷）；
//   ⑯ R-13 历史+镜像合并不双份：同一提问恰 1 条 user 气泡、**user 气泡数 == `/history` 的 USER
//      条目数**（TOOL 条目不产气泡、同轮多条 ASSISTANT 由 R-150 合并成 1 条）、assistant 恰 1 条且
//      正文非空（既不"整替"丢在途，也不双份）。原判据「UI 气泡数 == `/history` 条数」只在**纯文本轮**
//      成立 —— 工具轮的历史含 `assistant(tool_calls)` + `role:tool` 两条，恒不相等；
//   ⑰ R-12 刷新后在途轮 → 停止入口：停止钮可见可点，点击后 3s 内 `/runs/{runId}` 为 CANCELLED
//      且输入框随即可用（loading/suspended 都归零）；
//   ⑱ 停止后真刷新页面态正确：气泡数不变、**已显示的正文逐字保留**（刷新后正文以 before 为前缀，
//      允许服务端在停止瞬间多收尾几个字符 —— 那属"历史更完整"，不是丢字；原判据要求长度逐字相等，
//      实测 280 vs 283 恒红）、无红条、`streamInterrupted=false`、`/runs/current` 明确空态；
//   ⑲ R-10 对照（防过度修复）：**已完成轮**刷新后完整恢复且**零横幅零提示**。
// 需要桩的确定性长文本轮（`turn-text stall-frames=<ms>`，见 scripts/ci/stub-upstream.mjs 的
// FAULT_MARKER）：在途窗口由帧间隔撑开，脚本才有时间 reload。桩侧指纹按首条 user 消息记，
// 故每轮提示词带唯一后缀（否则同一指纹的第二轮会被桩退化）。
// 参数化：`SMOKE_API` / `SMOKE_APP` 指向任意一对后端 + 前端 dev server（手工触发口径）；
// 施工期验证用自建实例（后端 18330 + 前端独立端口），不碰常开的 8081/5202。
import { execSync, spawn } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { setTimeout as sleep } from 'node:timers/promises'

// data-testid 定位（苞 A 项 1 新增：头部「新建对话」文字按钮）
const TESTID_NEW_CHAT = 'new-chat'
// 空态页签语义小字（保守版文案，逐字断言；措辞裁决见施工卡 §1.4）
const TAB_HINT_TEXT = '对话保存在当前浏览器页签中，关闭页签即结束。'
// 苞 C：桩的确定性文本轮（逐字比对；事实源 = scripts/ci/stub-upstream.mjs 的 TURN_TEXT）
const TURN_TEXT = '命名轮蓝本：这是一段确定性文本，用于验证纯文本流的帧序完整性（start 恰 1 / delta ≥ 1 / done 恰 1），'
  + '以及 done 之后零帧的迟到帧闸门。文本按 24 字符切片发送，切片数由本串长度决定，不依赖模型。'
// 苞 C：在途窗口的帧间隔（ms）—— 撑开 reload + 点击的预算；5 帧 ≈ 12s
const RLD_FRAME_GAP_MS = Number(process.env.SMOKE_RLD_FRAME_GAP_MS ?? 2500)

const OUT = process.env.SMOKE_OUT ?? new URL('../../chat-panel-smoke-out', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')
const CHROME = process.env.SMOKE_CHROME
  ?? join(process.env.ProgramFiles || '', 'Google/Chrome/Application/chrome.exe')
const PORT = Number(process.env.SMOKE_CDP_PORT ?? 9334)
const API = process.env.SMOKE_API ?? 'http://localhost:18330'
const URL_APP = process.env.SMOKE_APP ?? 'http://localhost:5202/'
/**
 * ⑫ 的**硬保护端口**：即使 `SMOKE_KILL_BACKEND=1` 显式授权，也不得杀这三个端口上的进程。
 * 依据 = 施工口径「严禁起停/重启常开服务 8081（后端）与 5202（前端 dev）」，外加 Redis 6379
 * （杀它等于把并行 agent 的台账一起打掉）。这一闸与 `SMOKE_KILL_BACKEND` 是**与**关系：
 * 授权只放开"可以杀自建实例"，不放开"可以杀常开实例"。
 */
const PROTECTED_PORTS = new Set(['8081', '5202', '6379'])
// 剪贴板回读的等待上界（复核 D1：无剪贴板读权限的环境里 readText() 的 promise 永不 settled，
// 原实现会把整个脚本挂死在阶段 8；此处给显式上界，超时即 SKIP —— 见 readClipboardBounded）
const CLIPBOARD_READ_TIMEOUT_MS = Number(process.env.SMOKE_CLIPBOARD_TIMEOUT_MS ?? 5000)
const STAMP = new Date().toISOString().replace(/[-:]/g, '').replace(/\.\d+Z$/, 'Z')
const RESULT_FILE = `${OUT}/result-${STAMP}.json`
mkdirSync(OUT, { recursive: true })

// ── AI 对话数据面身份（Task 4/5）────────────────────────────────────────────
// 后端 `TenantIdentityFilter` 要求 /api/ai 对话数据面每个请求带 `X-Tenant-Id` / `X-User-Id`，
// 否则 400。页面自身的请求由前端统一带头（本脚本不代发）；但本脚本**直连后端**的取证请求
// （/api/ai/history、/api/ai/runs/{id}、/api/ai/runs/current）也必须带同一身份，否则取证恒为空。
// 身份取值优先级：`SMOKE_TENANT_ID`/`SMOKE_USER_ID` 显式覆盖 > 页面当前身份（store/ai-identity）
// > 目录首项默认（1/101，与前端自动进入的首个身份一致）。
const TENANT_HEADER = 'X-Tenant-Id'
const USER_HEADER = 'X-User-Id'
const SMOKE_TENANT_ID = process.env.SMOKE_TENANT_ID
const SMOKE_USER_ID = process.env.SMOKE_USER_ID
/** 页面当前身份（`{tenantId,userId}`）；未取到返回 null。 */
async function pageAiIdentity() {
  if (SMOKE_TENANT_ID && SMOKE_USER_ID) {
    return { tenantId: Number(SMOKE_TENANT_ID), userId: Number(SMOKE_USER_ID) }
  }
  try {
    const fromStore = await evaluate(`(() => {
      const app = document.getElementById('app')
      if (!app || !app.__vue_app__) return null
      const ai = app.__vue_app__.config.globalProperties.$pinia._s.get('ai')
      const id = ai && ai.identity
      return id && id.tenantId > 0 && id.userId > 0 ? { tenantId: id.tenantId, userId: id.userId } : null
    })()`, { awaitPromise: false })
    if (fromStore) return fromStore
  } catch { /* 页面未就绪：退回 storage/默认 */ }
  try {
    const raw = await evaluate(`sessionStorage.getItem('ai-identity')`, { awaitPromise: false })
    if (raw) {
      const parsed = JSON.parse(raw)
      if (Number.isInteger(parsed.tenantId) && Number.isInteger(parsed.userId)
        && parsed.tenantId > 0 && parsed.userId > 0) return parsed
    }
  } catch { /* 忽略 */ }
  return { tenantId: 1, userId: 101 }
}
let aiIdentity = null
/** 取证请求的公共头（身份 + JSON）。 */
async function apiHeaders() {
  if (!aiIdentity) aiIdentity = await pageAiIdentity()
  return { [TENANT_HEADER]: String(aiIdentity.tenantId), [USER_HEADER]: String(aiIdentity.userId) }
}

/**
 * 会话镜像键的**身份无关**匹配：Task 4 起键形是 `ai-mirror:<tenant>:<user>:<sid>`，
 * 无身份时才退回 `ai-mirror:<sid>`。断言只关心"是不是这个会话的镜像"，
 * 故按 `: <sid>` 结尾匹配，两种键形都成立（不写死身份段，避免身份切换后恒假）。
 */
function isMirrorKeyOf(key, sessionId) {
  return key.startsWith('ai-mirror:') && (key === `ai-mirror:${sessionId}` || key.endsWith(`:${sessionId}`))
}


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
  const r = await fetch(`${API}/api/ai/history/${encodeURIComponent(sessionId)}`, { headers: await apiHeaders() })
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
 * `/history` 的**完整载荷**（`data` = `{count,total,messages}`）。
 *
 * 苞 C 的 ⑮/⑯ 需要按**条目类型**核对（USER / ASSISTANT / TOOL / SYSTEM），只数总数会把
 * 工具轮误判成"气泡多/少"：`role:tool` 条目不产气泡、同一轮的连续 ASSISTANT 条目由前端
 * R-150 合并成一条。返回 null = 取不到（调用方据此判红，不静默放过）。
 */
async function historyPayload(sessionId) {
  try {
    const r = await fetch(`${API}/api/ai/history/${encodeURIComponent(sessionId)}`, { headers: await apiHeaders() })
    if (!r.ok) return null
    const body = await r.json()
    return body?.data ?? null
  } catch {
    return null
  }
}

/** 历史条目的类型分布（大小写归一；缺 type 视作 UNKNOWN）。 */
function historyComposition(payload) {
  const items = Array.isArray(payload?.messages) ? payload.messages : []
  const byType = { USER: 0, ASSISTANT: 0, TOOL: 0, SYSTEM: 0, UNKNOWN: 0 }
  let assistantWithText = 0
  let lastAssistantText = ''
  for (const item of items) {
    const type = String(item?.type ?? 'UNKNOWN').toUpperCase()
    byType[type in byType ? type : 'UNKNOWN'] += 1
    if (type === 'ASSISTANT') {
      const text = String(item?.text ?? '')
      if (text.trim() !== '') { assistantWithText += 1; lastAssistantText = text }
    }
  }
  return { total: items.length, byType, assistantWithText, lastAssistantText }
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
    // Task 4 起会话 id 的存储键带身份指纹（ai-session-id:<tenant>:<user>）；无身份时才退回裸键。
    const sidKey = Object.keys(sessionStorage).find(k => k === 'ai-session-id' || k.startsWith('ai-session-id:'))
    // 镜像键同口径（ai-mirror:<tenant>:<user>:<sid>）；断言只关心"是不是本会话的镜像"。
    const mirrorOf = (sid) => oldMirrorKeys.filter(k => k === 'ai-mirror:' + sid || k.endsWith(':' + sid))
    const identityRaw = sessionStorage.getItem('ai-identity')
    return {
      sessionId: ai.sessionId,
      storedSessionId: sidKey ? sessionStorage.getItem(sidKey) : null,
      sessionIdKey: sidKey ?? null,
      identityRaw,
      messages: ai.messages.length,
      historyLoaded: ai.historyLoaded,
      notice: ai.notice,
      buttonExists: !!btn,
      buttonDisabled: btn ? btn.disabled === true : null,
      buttonText: btn ? btn.textContent.trim() : null,
      hintVisible: !!(hint && hint.offsetParent !== null),
      mirrorKeys: oldMirrorKeys,
      hasOldMirror: mirrorOf(ai.sessionId).length > 0
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
 *
 * ⚠️ **前置：必须换新会话**。上游的路由判定看本轮请求的上下文（真模型看对话语境、桩看
 * `fingerprint` = 首条 user 消息的轮次台账），沿用"已经聊过一轮纯文本"的会话时，DANGER 工具
 * 调用不会再被发起 —— 这正是 ⑪ 原实现红在 `inflightBeforeStop=null` 的原因（缺前置，不是产品缺陷）。
 */
async function startSuspendedRound(prompt) {
  await evaluate(`(() => { void ${AI_EXPR}.send(${JSON.stringify(prompt)}); return true })()`, { awaitPromise: false })
  return waitFor(
    `(() => { const ai = ${AI_EXPR}
      return (ai.loading && ai.activeRunId !== null && ai.suspended)
        ? { runId: ai.activeRunId, messages: ai.messages.length, notice: ai.notice }
        : null })()`,
    { tries: 100, intervalMs: 100 }
  )
}

/**
 * 为 ⑪ 造一轮**真的在飞**的轮次（停止判据的前置）。
 *
 * 两臂按序尝试，都要求"在飞"可观测（`loading && activeRunId !== null`）：
 *   甲 `suspended-confirm-gate` = 新会话 + DANGER 工具 → 确认门挂起（最稳：轮次完全停在等人工）；
 *   乙 `long-stream` = 新会话 + 长答提示词（桩用 `turn-text stall-frames` 把帧间隔撑开；真模型
 *      的长答本身就要几秒）→ 等到"已收到首段正文且仍在飞"即算达标。
 * 返回 `{runId, form, ...}`；两臂都不达标返回 null（调用方据此 SKIP 并落盘理由，不假绿）。
 */
async function startInflightRound(confirmPrompt) {
  for (const form of ['suspended-confirm-gate', 'long-stream']) {
    await evaluate(`(async () => { await ${AI_EXPR}.resetSession(); return true })()`)
    await sleep(400)
    let inflight = null
    if (form === 'suspended-confirm-gate') {
      inflight = await startSuspendedRound(confirmPrompt)
    } else {
      const prompt = `命名轮蓝本 SMKSTOP-${STAMP}：turn-text stall-frames=${RLD_FRAME_GAP_MS}`
        + '；请分 10 点逐条说明导出任务的推进步骤，每点三行。'
      await evaluate(`(() => { void ${AI_EXPR}.send(${JSON.stringify(prompt)}); return true })()`, { awaitPromise: false })
      inflight = await waitFor(
        `(() => { const ai = ${AI_EXPR}
          const last = [...ai.messages].reverse().find((m) => m.role === 'assistant')
          return (ai.loading && ai.activeRunId !== null && last && last.content && last.content.length > 0)
            ? { runId: ai.activeRunId, messages: ai.messages.length, notice: ai.notice,
                partialLen: last.content.length } : null })()`,
        { tries: 120, intervalMs: 100 }
      )
    }
    if (inflight) return { ...inflight, form }
    console.log(`[阶段11 前置] ${form} 未达在飞态，换下一臂`)
  }
  return null
}

/** 消息级 + 页面级事实快照（复制按钮/局部提示/降级条/骨架屏，苞 B 三处断言共用）。 */
function msgFactsExpr(msgId) {
  return `(() => {
    const ai = ${AI_EXPR}
    const msg = ai.messages.find((m) => m.id === ${JSON.stringify(msgId)}) || null
    const btn = document.querySelector('[data-testid="copy-msg-${msgId}"]')
    const hint = document.querySelector('[data-testid="copy-hint-${msgId}"]')
    const rect = btn ? btn.getBoundingClientRect() : null
    const tip = btn ? btn.querySelector('span') : null
    const copyIcon = btn ? btn.querySelector('svg.i-copy') : null
    const checkIcon = btn ? btn.querySelector('svg.i-check') : null
    const cs = btn ? getComputedStyle(btn) : null
    // 复制口径（R-158 #11 / deriveCopyText）：全部 text 段 join('\\n\\n')，与 m.content（段间无间隔拼接）
    // 有意分家 —— 断言按**复制的契约**取期望值，不按 content 取，也不直接调用产品函数（防自证）。
    const texts = msg ? msg.parts.filter((p) => p.kind === 'text').map((p) => p.text) : []
    const reasoning = msg ? msg.parts.filter((p) => p.kind === 'reasoning').map((p) => p.text).join('') : ''
    return {
      buttonExists: !!btn,
      buttonText: btn ? btn.textContent.trim() : null,
      buttonAriaLabel: btn ? btn.getAttribute('aria-label') : null,
      buttonTipText: tip ? tip.textContent.trim() : null,
      buttonOpacity: cs ? cs.opacity : null,
      buttonVisible: btn ? btn.offsetParent !== null : null,
      buttonColor: cs ? cs.color : null,
      copiedClass: btn ? btn.classList.contains('is-copied') : null,
      copyIconDisplay: copyIcon ? getComputedStyle(copyIcon).display : null,
      checkIconDisplay: checkIcon ? getComputedStyle(checkIcon).display : null,
      copyTextFromParts: texts.join('\\n\\n'),
      textSegments: texts.length,
      contentLen: msg ? (msg.content || '').length : null,
      reasoningLen: reasoning.length,
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

/**
 * 复制成功反馈的现行判据（R-165 S4 / demo v7 `14c`）：钮挂 `is-copied` 类 ⇒ 绿勾露出
 * （`svg.i-copy` 隐藏、`svg.i-check` 显示）+ 文字转绿（#1a7f45），2s 后自清；
 * **失败**才出 `copy-hint-{id}` 元素。旧判据读「已复制」文字提示 —— 该提示已不存在（判据过时）。
 */
function copyFeedbackOk(f) {
  return f.copiedClass === true
    && f.copyIconDisplay === 'none'
    && f.checkIconDisplay !== 'none'
    && f.hintExists === false
}

/** 等复制成功反馈自清（生命周期 2s）——好让下一次点击的"反馈出现"断言有区分度。 */
async function waitCopyStateCleared(msgId) {
  return waitFor(
    `(() => { const b = document.querySelector('[data-testid="copy-msg-${msgId}"]')
      return !(b && b.classList.contains('is-copied')) })()`,
    { tries: 20, intervalMs: 200 }
  )
}

/**
 * 苞 C（V7 · RLD 真刷新试点）：消息面 + 页面面快照 —— 刷新前后用**同一口径**读取，
 * 于是"续显 / 合并不双份 / 停止后页面态"三件事都能在真刷新前后逐字段对照。
 */
function rldFactsExpr() {
  return `(() => {
    const ai = ${AI_EXPR}
    const msgs = ai.messages.map(m => ({
      id: m.id, role: m.role, text: m.content || '', done: m.done !== false
    }))
    const lastAssistant = [...msgs].reverse().find(m => m.role === 'assistant') || null
    const stop = document.querySelector('[data-testid="stop-btn"]')
    return {
      sessionId: ai.sessionId,
      loading: ai.loading,
      suspended: ai.suspended,
      stopped: ai.stopped,
      streamInterrupted: ai.streamInterrupted,
      activeRunId: ai.activeRunId,
      notice: ai.notice,
      historyLoaded: ai.historyLoaded,
      count: msgs.length,
      roles: msgs.map(m => m.role),
      textHeads: msgs.map(m => String(m.text || '').slice(0, 24)),
      userTexts: msgs.filter(m => m.role === 'user').map(m => m.text),
      assistantTexts: msgs.filter(m => m.role === 'assistant').map(m => m.text),
      assistantIds: msgs.filter(m => m.role === 'assistant').map(m => m.id),
      lastAssistant,
      stopVisible: !!stop && stop.offsetParent !== null,
      stopDisabled: stop ? stop.disabled === true : null,
      sendVisible: !!document.querySelector('[data-testid="send-btn"]'),
      banner: document.body.innerText.includes('连接中断，结果可能不完整'),
      takeoverHint: document.body.innerText.includes('接管') || document.body.innerText.includes('重挂')
    }
  })()`
}

/** 一轮 CRUD：真刷新（等价 F5 / `location.href`）后等应用就绪，并重新展开面板。 */
async function realReload(why) {
  await send('Page.navigate', { url: URL_APP })
  if (!await waitAppReady()) { console.error(`FATAL: ${why} 刷新后应用未就绪`); process.exit(3) }
  await sleep(300)
  await evaluate(`${AI_EXPR}.expanded = true`, { awaitPromise: false })
}

/** `GET /api/ai/runs/{runId}` 的快照状态（null = 取不到 / 不存在）。 */
async function runStatus(runId) {
  try {
    const r = await fetch(`${API}/api/ai/runs/${encodeURIComponent(runId)}`, { headers: await apiHeaders() })
    if (!r.ok) return null
    const body = await r.json()
    return body?.data?.snapshot?.status ?? null
  } catch {
    return null
  }
}

/** `GET /api/ai/runs/current?sessionId=` 的 found 标记（刷新后"有没有幽灵在途轮"的判据）。 */
async function runCurrentFound(sessionId) {
  try {
    const r = await fetch(`${API}/api/ai/runs/current?sessionId=${encodeURIComponent(sessionId)}`, { headers: await apiHeaders() })
    if (!r.ok) return null
    const body = await r.json()
    return body?.data?.found === true
  } catch {
    return null
  }
}

/** 真实鼠标悬停（CDP Input 域）—— 项 2 的按钮虽已常驻可见，仍要用真 hover + 真点击驱动（用户可见行为）。 */
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
 * 把真鼠标移到复制按钮上（自 R-165 S4 起按钮**常驻可见**，本函数只剩"把鼠标真放上去"的作用；
 * 阶段 7 的判据改为**可点性**：命中点落在按钮上，为 ⑧⑨⑩ 的真鼠标点击铺路）。
 *
 * 实测窗口被判为遮挡时 Chrome 会挂起渲染致 hover 不生效，故重试 3 次后回落 CDP CSS 域的
 * `forcePseudoState`（对祖先 `.group` 强制 `:hover`）—— 两条路径读的是**同一个**计算样式 `opacity`，
 * 落盘里记明用的是哪条（`css-forced-hover` 已是兜底路径）。
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
 * ⑫ 的**非破坏性**前置判定（不打进程、不花模型调用）：一眼看出本跑法能不能跑 ⑫。
 * 两道前置都过才值得去造在途轮 —— 否则先造轮再发现杀不了，纯属浪费。
 */
function killPrecondition() {
  const port = new URL(API).port || '80'
  if (PROTECTED_PORTS.has(port)) {
    return {
      ok: false,
      code: 'PROTECTED_PORT',
      reason: `端口 ${port} 属常开服务（8081 后端 / 5202 前端 dev / 6379 Redis），本脚本拒杀`
        + '（授权也不放开；可跑形态见文件头 ⑫ 的"可跑条件"）'
    }
  }
  if (process.env.SMOKE_KILL_BACKEND !== '1') {
    return {
      ok: false,
      code: 'NO_AUTH',
      reason: 'SMOKE_KILL_BACKEND 未置 1（默认不杀进程；本阶段按 SKIP 处理）。'
        + '可跑条件 = 一次性实例对（后端 22730 + 前端 22731 + Redis DB 2）+ SMOKE_KILL_BACKEND=1'
    }
  }
  return { ok: true, port }
}

/**
 * 按监听端口反查并杀掉后端进程（苞 B 项 3 的"真断流"回归对照用）。
 *
 * **四重闸**（破坏性动作，默认关闭）：
 *   ① **保护端口**：`SMOKE_API` 的端口若在 `PROTECTED_PORTS`（8081 / 5202 / 6379）内一律拒杀 ——
 *      常开服务禁起停是硬口径，与授权无关（授权只放开"自建实例可以被杀"）；
 *   ② 仅当环境变量 `SMOKE_KILL_BACKEND=1` 显式授权（编排脚本打开，脚本默认不杀任何进程）；
 *   ③ 只杀"监听 SMOKE_API 端口"的那一行 PID（netstat 反查，不按名字泛杀）；
 *   ④ 该 PID 的进程名必须是 `java.exe`，否则拒绝执行（防误杀代理/静态服务器）。
 * 任一闸不成立 ⇒ 返回 ok=false + 结构化 `code`，调用方如实报 SKIP 而不是假绿。
 */
async function killBackendByListeningPort() {
  const pre = killPrecondition()
  if (!pre.ok) return pre
  const port = pre.port
  let netstat = ''
  try {
    netstat = execSync('netstat -ano', { encoding: 'utf8' })
  } catch {
    return { ok: false, code: 'NETSTAT_FAILED', reason: 'netstat 执行失败' }
  }
  const row = netstat.split(/\r?\n/).find((line) => line.includes('LISTENING') && new RegExp(`:${port}\\s`).test(line))
  const pid = row ? row.trim().split(/\s+/).pop() : null
  if (!pid) {
    return { ok: false, code: 'NOT_LISTENING', reason: `未找到监听 ${port} 的进程` }
  }
  let tasklist = ''
  try {
    tasklist = execSync(`tasklist /FI "PID eq ${pid}" /NH`, { encoding: 'utf8' })
  } catch {
    tasklist = ''
  }
  if (!/java\.exe/i.test(tasklist)) {
    return { ok: false, code: 'NOT_JAVA', reason: `PID ${pid} 不是 java.exe，拒绝杀（tasklist: ${tasklist.trim().slice(0, 60)}）` }
  }
  try {
    execSync(`taskkill /PID ${pid} /F`, { encoding: 'utf8' })
  } catch {
    return { ok: false, code: 'TASKKILL_FAILED', reason: `taskkill /PID ${pid} 失败` }
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
 * 不假绿：⑧ 的判据仍只由 `__copyCalls` 的逐字比对（text 段 `join('\n\n')` 口径）+ 钮 `is-copied`
 * 反馈 + `notice === null` 承担，本项 SKIP 不改变 ⑧ 的 PASS/FAIL；不挂死：超时的那次 evaluate 悬空即弃（其 waiter 不再被消费），
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
    for (const k of Object.keys(sessionStorage)) { if (k.startsWith('ai-mirror:') || k.startsWith('ai-session-id')) sessionStorage.removeItem(k) }
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
  // 镜像键形带身份段（Task 4），故按"是不是 sid1 的镜像"判，不写死 'ai-mirror:<sid>'。
  const oldMirrorLeft = s3.mirrorKeys.filter(k => isMirrorKeyOf(k, sid1)).length > 0
  assert('③', s3.messages === 0 && s3.buttonDisabled === true
    && s3.sessionId !== sid1 && s3.storedSessionId === s3.sessionId
    && oldMirrorLeft === false
    && s3.notice === null      // 删除未失败（失败会写 notice「旧会话记忆删除失败（不影响新对话）」）
    && oldCount === 0,
  {
    messages: s3.messages, buttonDisabled: s3.buttonDisabled, notice: s3.notice,
    sessionIdChanged: s3.sessionId !== sid1, stateMatchesStorage: s3.storedSessionId === s3.sessionId,
    oldMirrorRemoved: !oldMirrorLeft, oldSessionHistoryCount: oldCount
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
  RESULT.phases.copyStructure = {
    ...s6, ...structure, contentLength: target.content.length,
    copyTextLength: s6.copyTextFromParts.length, textSegments: s6.textSegments
  }
  console.log('[阶段6 复制按钮结构]', JSON.stringify({
    buttonExists: s6.buttonExists, buttonVisible: s6.buttonVisible, buttonOpacity: s6.buttonOpacity,
    ariaLabel: s6.buttonAriaLabel, tipText: s6.buttonTipText, copyIcon: s6.copyIconDisplay,
    checkIcon: s6.checkIconDisplay, copiedClass: s6.copiedClass, textSegments: s6.textSegments,
    contentLen: s6.contentLen, copyTextLen: s6.copyTextFromParts.length,
    withText: structure.withText, blank: structure.blank, missing: structure.missing, leaked: structure.leaked
  }))
  // ⑥ 判据（本批对齐现状）：图标钮结构（复制图标 + 隐藏的完成图标 + 气泡 tooltip，无障碍名走
  // aria-label 且与 tooltip 同源）+ **常驻可见**（opacity 1，R-165 S4 已撤浮出机制）+ 初始未复制态
  // + 无骨架屏。原判据 `buttonText === '复制'` / `opacity === '0'` 均为过时结构（按钮已图标化、
  // 文案改为「复制该条消息正文」并移入 tooltip；`opacity: 0` 的 hover 浮出已撤销）。
  assert('⑥', !!target && structure.missing.length === 0 && structure.leaked.length === 0
    && s6.buttonExists && s6.buttonVisible === true && s6.buttonOpacity === '1'
    && !!s6.buttonAriaLabel && s6.buttonAriaLabel === s6.buttonTipText
    && s6.copyIconDisplay !== 'none' && s6.checkIconDisplay === 'none'
    && s6.copiedClass === false && s6.skeleton === false,
  {
    messagesWithText: structure.withText, blankMessages: structure.blank,
    missingButtons: structure.missing, leakedButtons: structure.leaked,
    buttonVisible: s6.buttonVisible, defaultOpacity: s6.buttonOpacity,
    ariaLabel: s6.buttonAriaLabel, tipText: s6.buttonTipText,
    copyIconDisplay: s6.copyIconDisplay, checkIconDisplay: s6.checkIconDisplay,
    copiedClassAtRest: s6.copiedClass, skeletonWhileMessagesExist: s6.skeleton
  })

  // ── 阶段 7：可点性（常驻可见 + 命中点落在按钮自身） ──────────────────────
  // 判据（本批对齐现状）：R-165 S4 起按钮常驻（撤 `opacity-0` + group-hover 浮出），故本条不再证
  // 「hover 才显现」，改证 ⑧⑨⑩ 真鼠标点击所依赖的性质 = 按钮在视口内、可见、且该坐标的 hit-test
  // 落在按钮（或其子元素）上。`revealCopyButton` 保留为"把鼠标真移上去"的动作。
  // 坐标必须**现取**：元信息行在流式期是「工作中…」呼吸位、收尾后换成时间戳，宽度变化会让按钮
  // x 位移几十像素（实测按阶段 6 的旧坐标 hit-test 会落到 meta 行本身 ⇒ 假红）。
  const fx7 = await evaluate(msgFactsExpr(target.id), { awaitPromise: false })
  const reveal = await revealCopyButton(target.id, fx7.center)
  const hit7 = await evaluate(`(() => {
    const b = document.querySelector('[data-testid="copy-msg-${target.id}"]')
    if (!b) return { opacity: null, visible: false, hitIsButton: false, stack: [] }
    const r = b.getBoundingClientRect()
    // 用 elementsFromPoint（整栈）而非 elementFromPoint（只取最顶层）：按钮的悬浮 tooltip 可能压在
    // 按钮中心上 —— 只要按钮在命中栈里，真鼠标按下就打到它。
    const x = r.left + r.width / 2
    const y = r.top + r.height / 2
    const stack = document.elementsFromPoint(x, y)
    return {
      opacity: getComputedStyle(b).opacity,
      visible: b.offsetParent !== null,
      hitIsButton: stack.some((el) => el === b || b.contains(el)),
      point: { x, y },
      stack: stack.slice(0, 4).map((el) => el.tagName + (el.getAttribute('data-testid') ? '#' + el.getAttribute('data-testid') : ''))
    }
  })()`, { awaitPromise: false })
  RESULT.phases.hoverReveal = {
    before: s6.buttonOpacity, after: hit7.opacity, hitIsButton: hit7.hitIsButton, hitStack: hit7.stack,
    point: hit7.point,
    mechanism: 'always-visible（浮出机制已撤：真 hover 不改变可见性）', hoverMechanism: reveal.mechanism
  }
  console.log('[阶段7 可点性]', JSON.stringify(RESULT.phases.hoverReveal))
  assert('⑦', hit7.opacity === '1' && hit7.visible === true && hit7.hitIsButton === true,
    { opacityBeforeHover: s6.buttonOpacity, opacityAfterHover: hit7.opacity,
      visible: hit7.visible, hitIsButton: hit7.hitIsButton, hitStack: hit7.stack })

  // ── 阶段 8：主路径 —— clipboard API + `is-copied` 反馈（不占 notice） ────
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
  const fx8 = await evaluate(msgFactsExpr(target.id), { awaitPromise: false })
  const expected8 = fx8.copyTextFromParts
  const center8 = fx8.center
  const click8 = await clickCopyAndWait(target.id, center8, async () => {
    const n = await evaluate('window.__copyCalls.length', { awaitPromise: false })
    return typeof n === 'number' && n > 0
  })
  const s8 = await evaluate(msgFactsExpr(target.id), { awaitPromise: false })
  const calls8 = await evaluate('window.__copyCalls', { awaitPromise: false })
  const read8 = await readClipboardBounded()
  const exact8 = Array.isArray(calls8) && calls8.length >= 1 && calls8[0] === expected8
  RESULT.phases.copyClipboardPath = {
    clipboardApiAvailable: clipReady, clickVia: click8, writeCalls: Array.isArray(calls8) ? calls8.length : null,
    exactMatch: exact8, expectedLen: expected8.length, contentLen: fx8.contentLen,
    textSegments: fx8.textSegments, payloadHead: Array.isArray(calls8) && calls8.length ? String(calls8[0]).slice(0, 40) : null,
    expectedHead: expected8.slice(0, 40),
    copiedClass: s8.copiedClass, copyIconDisplay: s8.copyIconDisplay, checkIconDisplay: s8.checkIconDisplay,
    hintText: s8.hintText, notice: s8.notice,
    clipboardReadback: read8.value, clipboardReadbackStatus: read8.status, clipboardReadbackReason: read8.reason
  }
  if (read8.status !== 'OK') { RESULT.skipped = { ...(RESULT.skipped || {}), '⑧·旁证(剪贴板回读)': `${read8.status}: ${read8.reason}` } }
  const read8Brief = read8.status === 'OK' ? String(read8.value).slice(0, 60) : `SKIP(${read8.reason})`
  console.log('[阶段8 复制·主路径]', JSON.stringify({ ...RESULT.phases.copyClipboardPath, clipboardReadback: read8Brief }))
  if (read8.status !== 'OK') { console.log(`[阶段8 回读旁证] SKIP ${read8.reason}`) }
  // ⑧ 判据（本批对齐现状）：writeText 收到的是**复制契约**的文本（全部 text 段 join('\n\n')，
  // R-158 #11），成功反馈 = 钮 `is-copied`（绿勾替换复制图标、无失败提示），notice 仍为 null。
  // 旧判据 `=== m.content` 在"多个 text 段"的消息上恒不等（content 段间无间隔拼接），
  // 旧判据「已复制文字提示」已随 v7 `14c` 图标化撤销。
  assert('⑧', clipReady === true && click8 !== 'none' && exact8 && copyFeedbackOk(s8) && s8.notice === null,
  {
    clipboardApiAvailable: clipReady, clickVia: click8, writeCalls: Array.isArray(calls8) ? calls8.length : null,
    exactMatch: exact8, expectedLen: expected8.length, textSegments: fx8.textSegments,
    copiedClass: s8.copiedClass, copyIconDisplay: s8.copyIconDisplay, checkIconDisplay: s8.checkIconDisplay,
    failureHint: s8.hintText, noticeHeldByNotice: s8.notice,
    clipboardReadback: read8.status === 'OK' ? String(read8.value).slice(0, 60) : null,
    clipboardReadbackStatus: read8.status, clipboardReadbackReason: read8.reason
  })

  // ── 阶段 9：回退 A —— clipboard 不可用（经内网 http 访问的非安全上下文） ──
  await waitCopyStateCleared(target.id)
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
  const fx9 = await evaluate(msgFactsExpr(target.id), { awaitPromise: false })
  const expected9 = fx9.copyTextFromParts
  const center9 = fx9.center
  const click9 = await clickCopyAndWait(target.id, center9, async () => {
    const n = await evaluate('window.__execCalls.length', { awaitPromise: false })
    return typeof n === 'number' && n > 0
  })
  const s9 = await evaluate(msgFactsExpr(target.id), { awaitPromise: false })
  const exec9 = await evaluate('({ calls: window.__execCalls, texts: window.__execTexts })', { awaitPromise: false })
  const exact9 = Array.isArray(exec9.calls) && exec9.calls.includes('copy') && exec9.texts[0] === expected9
  RESULT.phases.copyFallbackUndefined = {
    clipboardForcedUndefined: fallbackReady, clickVia: click9, execCommands: exec9.calls,
    exactMatch: exact9, expectedLen: expected9.length,
    copiedClass: s9.copiedClass, checkIconDisplay: s9.checkIconDisplay, hintText: s9.hintText, notice: s9.notice
  }
  console.log('[阶段9 复制·回退A(clipboard 缺失)]', JSON.stringify(RESULT.phases.copyFallbackUndefined))
  assert('⑨', fallbackReady === true && click9 !== 'none' && exact9 && copyFeedbackOk(s9) && s9.notice === null,
  {
    clipboardForcedUndefined: fallbackReady, clickVia: click9, execCommands: exec9.calls,
    exactMatch: exact9, execTextLen: Array.isArray(exec9.texts) && exec9.texts[0] ? String(exec9.texts[0]).length : null,
    copiedClass: s9.copiedClass, copyIconDisplay: s9.copyIconDisplay, checkIconDisplay: s9.checkIconDisplay,
    failureHint: s9.hintText, noticeHeldByNotice: s9.notice
  })

  // ── 阶段 10：回退 B —— clipboard 存在但 writeText 被拒 ─────────────────
  await waitCopyStateCleared(target.id)
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
  const fx10 = await evaluate(msgFactsExpr(target.id), { awaitPromise: false })
  const expected10 = fx10.copyTextFromParts
  const center10 = fx10.center
  const click10 = await clickCopyAndWait(target.id, center10, async () => {
    const n = await evaluate('window.__execCalls.length', { awaitPromise: false })
    return typeof n === 'number' && n > 0
  })
  const s10 = await evaluate(msgFactsExpr(target.id), { awaitPromise: false })
  const exec10 = await evaluate('({ calls: window.__execCalls, texts: window.__execTexts, rejects: window.__clipRejects })', { awaitPromise: false })
  const exact10 = Array.isArray(exec10.calls) && exec10.calls.includes('copy') && exec10.texts[0] === expected10
  RESULT.phases.copyFallbackRejected = {
    writeTextRejects: exec10.rejects, clickVia: click10, execCommands: exec10.calls,
    exactMatch: exact10, expectedLen: expected10.length,
    copiedClass: s10.copiedClass, checkIconDisplay: s10.checkIconDisplay, hintText: s10.hintText, notice: s10.notice
  }
  console.log('[阶段10 复制·回退B(writeText 被拒)]', JSON.stringify(RESULT.phases.copyFallbackRejected))
  assert('⑩', rejectReady === true && click10 !== 'none' && exec10.rejects === 1 && exact10
    && copyFeedbackOk(s10) && s10.notice === null,
  {
    writeTextRejects: exec10.rejects, clickVia: click10, execCommands: exec10.calls, exactMatch: exact10,
    copiedClass: s10.copiedClass, copyIconDisplay: s10.copyIconDisplay, checkIconDisplay: s10.checkIconDisplay,
    failureHint: s10.hintText, noticeHeldByNotice: s10.notice
  })

  // 还原被注入的剪贴板/execCommand（后续阶段不再依赖它们）
  await evaluate(`(() => {
    if (window.__clipWasDefined) { Object.defineProperty(navigator, 'clipboard', { value: window.__origClipboard, configurable: true }) }
    else { delete navigator.clipboard }
    if (window.__origExecCommand) { document.execCommand = window.__origExecCommand }
    return true
  })()`, { awaitPromise: false })

  // ── 阶段 11：停止不误闪红条（项 3 主证据） ──────────────────────────────
  // 本批修的是**前置缺失**（原红因 = inflightBeforeStop: null / 停止钮点不动）：
  //   停止只能打在**真的在飞**的轮上。原实现沿用上一阶段的会话（上下文已有纯文本轮），上游
  //   （真模型 / 桩的轮次台账）都不会再发 DANGER 工具调用 ⇒ 从未进入在途态 ⇒ 点击空转。
  //   现口径 = 换新会话 + 两臂按序造在途轮（见 startInflightRound），并改用**现行**停止判据：
  //   `notice` 保持 null（store 已不再写「已请求取消本轮对话」——该文案全文检索为零），
  //   停止的正向证据改由消息尾 `msg-stopped-{id}` chip + `/runs/{runId}` 收敛为 CANCELLED 承担。
  const CONFIRM_PROMPT = '请立即调用 start_export 工具启动任务 1 的导出作业（系统会自动弹出确认卡片，无需再询问我）'
  const inflight1 = await startInflightRound(CONFIRM_PROMPT)
  let stop11 = null
  let stopChip11 = null
  let status11 = null
  let beforeStop = null
  let s11 = null
  let stopClickedAt11 = null
  let cancelLatency11 = null
  if (inflight1) {
    beforeStop = await evaluate(rldFactsExpr(), { awaitPromise: false })
    stopClickedAt11 = Date.now()
    stop11 = await evaluate(`(() => {
      const b = document.querySelector('[data-testid="stop-btn"]')
      if (!b) return { clicked: false, reason: 'no-stop-btn' }
      const r = { clicked: true, visible: b.offsetParent !== null, disabled: b.disabled === true }
      b.click()
      return r
    })()`, { awaitPromise: false })
    await sleep(1200)
    s11 = await evaluate(rldFactsExpr(), { awaitPromise: false })
    stopChip11 = await evaluate(`(() => {
      const ai = ${AI_EXPR}
      const host = [...ai.messages].reverse().find((m) => m.role === 'assistant')
      return host
        ? { id: host.id, chip: !!document.querySelector('[data-testid="msg-stopped-' + host.id + '"]'),
            textLen: (host.content || '').length }
        : null
    })()`, { awaitPromise: false })
    if (inflight1.runId) {
      for (let i = 0; i < 30; i++) { // 3s 预算：停止后 /runs/{runId} 应收敛为 CANCELLED
        status11 = await runStatus(inflight1.runId)
        if (status11 === 'CANCELLED') { cancelLatency11 = Date.now() - stopClickedAt11; break }
        await sleep(100)
      }
    }
  }
  RESULT.phases.stopNoRedBanner = {
    inflightBeforeStop: inflight1,
    stopEntry: stop11,
    loadingBeforeStop: beforeStop ? beforeStop.loading : null,
    suspendedBeforeStop: beforeStop ? beforeStop.suspended : null,
    stopped: s11 ? s11.stopped : null, streamInterrupted: s11 ? s11.streamInterrupted : null,
    interruptedBanner: s11 ? s11.banner : null, notice: s11 ? s11.notice : null,
    loadingAfterStop: s11 ? s11.loading : null, suspendedAfterStop: s11 ? s11.suspended : null,
    stoppedChip: stopChip11, runStatusAfterStop: status11,
    cancelLatencyMs: cancelLatency11
  }
  console.log('[阶段11 停止]', JSON.stringify(RESULT.phases.stopNoRedBanner))
  if (!inflight1) {
    RESULT.skipped = { ...(RESULT.skipped || {}), '⑪': '两臂（确认门挂起 / 长流）均未造出在飞轮，判据无前置；不是产品结论' }
    console.log('[断言⑪] SKIP 未造出在途轮（前置缺失，理由见 startInflightRound）')
  } else {
    assert('⑪', beforeStop.loading === true && beforeStop.activeRunId !== null
      && !!stop11 && stop11.clicked === true && stop11.visible === true && stop11.disabled === false
      && s11.stopped === true && s11.streamInterrupted === false && s11.notice === null
      && s11.banner === false && s11.loading === false && s11.suspended === false
      && !!stopChip11 && stopChip11.chip === true && status11 === 'CANCELLED',
    {
      inflightRunId: inflight1.runId, form: inflight1.form,
      stopEntry: stop11, stopped: s11.stopped, streamInterrupted: s11.streamInterrupted,
      redBannerPresent: s11.banner, notice: s11.notice,
      stoppedChip: stopChip11, runStatusAfterStop: status11
    })
  }

  // ── 阶段 13：骨架屏按 historyLoaded 接线（项 4） ────────────────────────
  // 手法：清镜像后 reload，并用 Fetch 域把历史请求**挂住**（不放行）⇒ 稳定停在"无消息 + 对账未完成"
  // 的窗口内，骨架屏必现；放行后骨架屏消失、空态出现（与空态互斥的 v-if / v-else-if 即在此被证）。
  await send('Fetch.enable', { patterns: [{ urlPattern: '*api/ai/history*', requestStage: 'Request' }] })
  pausedHistoryId = null
  await evaluate(`(() => {
    for (const k of Object.keys(sessionStorage)) { if (k.startsWith('ai-mirror:') || k.startsWith('ai-session-id')) sessionStorage.removeItem(k) }
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

  // ══════════════════════════════════════════════════════════════════════════
  // 苞 C（V7 · RLD 真刷新试点）：⑭ 在途轮续显 / ⑮ 收敛到底 / ⑯ 合并不双份
  //    / ⑰ 刷新后停止入口 / ⑱ 停止后刷新页面态 / ⑲ 已完成轮刷新零横幅（对照）
  // ══════════════════════════════════════════════════════════════════════════

  // ── 阶段 14：流式中真刷新 → 在途轮续显 + 合并不双份 ─────────────────────
  // 形态：长文本轮。桩侧由 `turn-text stall-frames` 撑开帧间隔（确定性 5 帧 ≈ 12s），
  // 真模型侧由"要求长答"撑开（模型的回答越长，在途窗口越宽）。真刷新（Page.navigate = F5）后
  // 内存态全丢，只剩 sessionStorage 镜像 + 后端 /history 与 /runs/current —— 这正是 R-01 / R-13
  // 要求的形态，jsdom 无法表达。
  //
  // 在途窗口守门（本批修）：真模型下回答可能 2~4s 就收尾，reload 本身要 1.5~2.5s ⇒ 偶尔 reload 落地时
  // 该轮**已经结束**（⑭/⑰ 就会红在"刷新后没有在途轮"，那是环境时序，不是产品结论）。故 reload 前
  // 复查 `ai.loading`，已收尾就换新会话重试一次（最多 2 次）。
  const RLD_LONG_ASK = '；请用不少于 600 字分点详细说明导出任务的完整推进流程。'
  let promptRld1 = ''
  let sidRld1 = null
  let inflightRld1 = null
  for (let attempt = 0; attempt < 2; attempt++) {
    await evaluate(`(async () => { await ${AI_EXPR}.resetSession(); return true })()`)
    await sleep(400)
    const tag = attempt === 0 ? '' : '-b'
    promptRld1 = `命名轮蓝本 RLD1-${STAMP}${tag}：turn-text stall-frames=${RLD_FRAME_GAP_MS}${RLD_LONG_ASK}`
    sidRld1 = await evaluate(`${AI_EXPR}.sessionId`, { awaitPromise: false })
    await evaluate(`(() => { void ${AI_EXPR}.send(${JSON.stringify(promptRld1)}); return true })()`, { awaitPromise: false })
    inflightRld1 = await waitFor(
      `(() => { const ai = ${AI_EXPR}
        const last = [...ai.messages].reverse().find(m => m.role === 'assistant')
        return (ai.loading && last && last.content && last.content.length > 0)
          ? { runId: ai.activeRunId, partialLen: last.content.length, partialText: last.content,
              count: ai.messages.length } : null })()`,
      { tries: 120, intervalMs: 100 }
    )
    const stillInFlight = await evaluate(`${AI_EXPR}.loading`, { awaitPromise: false })
    if (inflightRld1 && stillInFlight) break
    console.log(`[阶段14] 在途窗口已关闭（第 ${attempt + 1} 次：loading=${stillInFlight}），换新会话重试`)
  }
  const mirrorBeforeReload = await evaluate(
    `(() => { const sid = '' + ${AI_EXPR}.sessionId
      const key = Object.keys(sessionStorage).find(k => k.startsWith('ai-mirror:') && (k === 'ai-mirror:' + sid || k.endsWith(':' + sid)))
      const raw = key ? sessionStorage.getItem(key) : null
      if (!raw) return null
      const m = JSON.parse(raw)
      const last = (m.messages || []).filter(x => x.role === 'assistant').pop() || null
      return { inFlight: m.inFlight === true, lastRunId: m.lastRunId ?? null, assistantLen: last ? (last.content || '').length : 0 } })()`,
    { awaitPromise: false }
  )
  await realReload('阶段14')
  // 刷新后仍在途：historyLoaded（历史/镜像合并完成）且 loading（静默续收已接管）**且已续上一段正文**。
  // 第三个条件不是放宽而是**采样时点**：续收按 `lastSeq=0` 全量重放，`loading` 先真、正文随后到；
  // 桩下重放是瞬时的，真模型下要等首段 delta（本判据要证的正是"有正文"，故等它出现）。
  const afterReloadRld1 = await waitFor(
    `(() => { const ai = ${AI_EXPR}
      if (!(ai.historyLoaded && ai.loading)) return null
      const f = ${rldFactsExpr()}
      return (f.lastAssistant && f.lastAssistant.text.length > 0) ? f : null })()`,
    { tries: 120, intervalMs: 100 }
  )
  const phase14 = {
    inflightBeforeReload: inflightRld1,
    mirrorBeforeReload,
    afterReload: afterReloadRld1
  }
  RESULT.phases.rldReloadInFlight = phase14
  console.log('[阶段14 真刷新后续显]', JSON.stringify({
    inflightBeforeReload: inflightRld1, mirrorBeforeReload, afterReload: afterReloadRld1 && {
      count: afterReloadRld1.count, loading: afterReloadRld1.loading, banner: afterReloadRld1.banner,
      takeoverHint: afterReloadRld1.takeoverHint, assistantLen: afterReloadRld1.lastAssistant?.text.length
    }
  }))
  // ⑭ 不出现「只剩用户气泡且无任何提示」：刷新后必须有 assistant 气泡且正文非空，且在途仍在
  assert('⑭', !!inflightRld1 && !!afterReloadRld1
    && afterReloadRld1.loading === true
    && !!afterReloadRld1.lastAssistant && afterReloadRld1.lastAssistant.text.length > 0
    && afterReloadRld1.count >= 2
    && afterReloadRld1.banner === false && afterReloadRld1.takeoverHint === false,
  {
    inflightRunId: inflightRld1 ? inflightRld1.runId : null,
    mirrorInFlightBeforeReload: mirrorBeforeReload ? mirrorBeforeReload.inFlight : null,
    afterReloadMessages: afterReloadRld1 ? afterReloadRld1.count : null,
    afterReloadAssistantLen: afterReloadRld1 && afterReloadRld1.lastAssistant
      ? afterReloadRld1.lastAssistant.text.length : null,
    afterReloadLoading: afterReloadRld1 ? afterReloadRld1.loading : null,
    redBanner: afterReloadRld1 ? afterReloadRld1.banner : null,
    takeoverHint: afterReloadRld1 ? afterReloadRld1.takeoverHint : null
  })

  // ⑮ 续收到尾：正文**续上了且收尾完整**（不是"停在半截"）。判据两臂，按环境取一（都严格）：
  //   桩臂 = 刷新时刻的部分正文是桩确定性文本的前缀（桩下恒真）⇒ 收敛正文与 `TURN_TEXT` **逐字相等**；
  //   非桩臂 = 上游是别的模型/真模型（常开后端常见）⇒ 收敛正文必须与 `/history` 持久化的该轮
  //           assistant 正文**逐字相等**（UI 与后端同源收尾），且比部分正文更长。
  // 为什么加第二臂：写死桩文本时，一旦常开后端接了真模型，本条永远红 —— 那是环境依赖，不是产品结论。
  //
  // 收敛判定（本批修正）：只等 `!loading` **不够**。工具轮在"工具段结束 → 续收建立"之间会出现
  // 短暂的 `loading=false`（实测采到一次正文仅 31 字、而 `/history` 已 278 字的状态，被误判成
  // "停在半截"）。故判据 = `!loading && 末条 assistant 已 done/非 streaming` **且** 正文与期望
  // 逐字相等；`/history` 写入滞后靠重读吸收（250ms × 120 = 30s 预算）。
  const partialTextRld1 = String(inflightRld1?.partialText ?? '')
  const stubArmRld1 = partialTextRld1 !== '' && TURN_TEXT.startsWith(partialTextRld1)
  let convergedRld1 = null
  let lastFactsRld1 = null
  let convergedTriesRld1 = 0
  let compRld1 = historyComposition(await historyPayload(sidRld1))
  for (let i = 0; i < 120; i++) {
    convergedTriesRld1 = i + 1
    const facts = await evaluate(`(() => { const ai = ${AI_EXPR}
      if (ai.loading) return null
      const last = [...ai.messages].reverse().find((m) => m.role === 'assistant')
      if (!last || last.done === false || last.streaming === true) return null
      return { id: last.id, text: last.content || '' } })()`, { awaitPromise: false })
    if (facts) {
      lastFactsRld1 = facts
      const expectedNow = stubArmRld1 ? TURN_TEXT : compRld1.lastAssistantText
      if (expectedNow !== '' && facts.text === expectedNow) {
        convergedRld1 = await evaluate(rldFactsExpr(), { awaitPromise: false })
        break
      }
    }
    if (i % 4 === 3) compRld1 = historyComposition(await historyPayload(sidRld1))
    await sleep(250)
  }
  compRld1 = historyComposition(await historyPayload(sidRld1))
  const textRld1 = String(lastFactsRld1?.text ?? '')
  const textArmOk = convergedRld1 !== null
  RESULT.phases.rldReloadConverged = {
    converged: !!convergedRld1, textLen: textRld1.length,
    stubArm: stubArmRld1, exactStubMatch: textRld1 === TURN_TEXT,
    exactPersistMatch: textRld1 === compRld1.lastAssistantText,
    persistedLen: compRld1.lastAssistantText.length,
    partialLenAtReload: partialTextRld1.length,
    grewAfterReload: textRld1.length >= partialTextRld1.length,
    convergedTries: convergedTriesRld1,
    banner: convergedRld1 ? convergedRld1.banner : null,
    streamInterrupted: convergedRld1 ? convergedRld1.streamInterrupted : null,
    lastAssistantId: convergedRld1 && convergedRld1.lastAssistant ? convergedRld1.lastAssistant.id : null
  }
  console.log('[阶段14 续收到底]', JSON.stringify({
    ...RESULT.phases.rldReloadConverged, textHead: textRld1.slice(0, 24)
  }))
  assert('⑮', textArmOk
    && textRld1.length >= partialTextRld1.length
    && !!convergedRld1 && convergedRld1.banner === false && convergedRld1.streamInterrupted === false,
  {
    stubArm: stubArmRld1, exactStubMatch: textRld1 === TURN_TEXT,
    exactPersistMatch: textRld1 === compRld1.lastAssistantText,
    textLen: textRld1.length, persistedLen: compRld1.lastAssistantText.length,
    partialLenAtReload: partialTextRld1.length, convergedTries: convergedTriesRld1,
    textHead: textRld1.slice(0, 24), redBanner: convergedRld1 ? convergedRld1.banner : null
  })

  // ⑯ 历史 + 镜像合并**不双份**（本批改判据口径）：按**渲染等价**核对，而不是"UI 气泡数 ==
  // `/history` 条数"。后者只在纯文本轮成立：工具轮的历史含 `assistant(tool_calls)` 与 `role:tool`
  // 两类**不产气泡**的条目（`role:tool` 不建消息、同轮多条 ASSISTANT 由前端 R-150 合并成 1 条），
  // 于是"4 条历史 vs 2 个气泡"是正确形态（实证见 2026-10-23 复跑 ⑯ 详单）。
  // 现行判据 = user 气泡数 == 历史 USER 条目数（不丢不双份）+ 同一提问恰 1 条 + assistant 恰 1 条
  // 且正文非空（不"整替"）+ 气泡 id 唯一 + 计数自洽（user + assistant == 总气泡）。
  // 用户气泡文本经后端 InputSanitizer 的 NFKC 归一（全角标点折半角），故按**唯一标记**（提示词的
  // stamp 后缀，纯 ASCII、归一不动它）计数，不逐字比对整串 —— 归一不是缺陷，见批G · R5/R2 口径。
  const uiCountRld1 = convergedRld1 ? convergedRld1.count : null
  const pushRld1 = await historyPayload(sidRld1)
  const compRld1b = historyComposition(pushRld1)
  const historyCountRld1 = pushRld1 ? pushRld1.count : null
  const markerRld1 = `RLD1-${STAMP}`
  const userBubblesRld1 = convergedRld1
    ? convergedRld1.userTexts.filter((t) => t.includes(markerRld1)).length : null
  const uiUserRld1 = convergedRld1 ? convergedRld1.userTexts.length : null
  const uiAssistantRld1 = convergedRld1 ? convergedRld1.assistantTexts.length : null
  // 渲染等价：USER/SYSTEM 条目各产 1 条气泡；ASSISTANT 条目（不论几条）同轮合并成 1 条气泡；
  // TOOL 条目不产气泡。
  const expectedAssistantRld1 = compRld1b.assistantWithText > 0 ? 1 : 0
  RESULT.phases.rldMergeNoDuplicate = {
    uiMessages: uiCountRld1, backendHistoryCount: historyCountRld1,
    backendHistoryComposition: compRld1b,
    // 诊断留痕（"samePromptUserBubbles=0 闪红"复核用）：UI 侧角色/文本头 + 历史侧条目类型/文本头
    uiRoles: convergedRld1 ? convergedRld1.roles : null,
    uiTextHeads: convergedRld1 ? convergedRld1.textHeads : null,
    historyItemTypes: (pushRld1?.messages ?? []).map((i) => String(i?.type ?? 'UNKNOWN').toUpperCase()),
    historyTextHeads: (pushRld1?.messages ?? []).map((i) => String(i?.text ?? '').slice(0, 24)),
    uiUserBubbles: uiUserRld1, uiAssistantBubbles: uiAssistantRld1,
    expectedUserBubbles: compRld1b.byType.USER + compRld1b.byType.SYSTEM,
    expectedAssistantBubbles: expectedAssistantRld1,
    samePromptUserBubbles: userBubblesRld1,
    assistantIdsUnique: convergedRld1
      ? new Set(convergedRld1.assistantIds).size === convergedRld1.assistantIds.length : null
  }
  console.log('[阶段14 合并不双份]', JSON.stringify({
    uiMessages: uiCountRld1, backendHistoryCount: historyCountRld1,
    historyTotal: compRld1b.total, byType: compRld1b.byType,
    assistantWithText: compRld1b.assistantWithText, lastAssistantLen: compRld1b.lastAssistantText.length,
    uiRoles: RESULT.phases.rldMergeNoDuplicate.uiRoles,
    uiUserBubbles: uiUserRld1, uiAssistantBubbles: uiAssistantRld1,
    expectedUserBubbles: compRld1b.byType.USER + compRld1b.byType.SYSTEM,
    expectedAssistantBubbles: expectedAssistantRld1,
    samePromptUserBubbles: userBubblesRld1,
    assistantIdsUnique: RESULT.phases.rldMergeNoDuplicate.assistantIdsUnique
  }))
  assert('⑯', convergedRld1 != null && !!pushRld1
    && uiUserRld1 === compRld1b.byType.USER + compRld1b.byType.SYSTEM
    && userBubblesRld1 === 1
    && uiAssistantRld1 === expectedAssistantRld1
    && uiCountRld1 === uiUserRld1 + uiAssistantRld1
    && !!convergedRld1.lastAssistant && convergedRld1.lastAssistant.text.length > 0
    && new Set(convergedRld1.assistantIds).size === convergedRld1.assistantIds.length,
  // 断言详单只放**摘要**（全文落在 RESULT.phases.rldMergeNoDuplicate，避免日志被整段回答淹没）
  {
    uiMessages: uiCountRld1, backendHistoryCount: historyCountRld1,
    historyTotal: compRld1b.total, byType: compRld1b.byType,
    lastAssistantLen: compRld1b.lastAssistantText.length,
    uiRoles: RESULT.phases.rldMergeNoDuplicate.uiRoles,
    uiUserBubbles: uiUserRld1, uiAssistantBubbles: uiAssistantRld1,
    expectedUserBubbles: compRld1b.byType.USER + compRld1b.byType.SYSTEM,
    expectedAssistantBubbles: expectedAssistantRld1,
    samePromptUserBubbles: userBubblesRld1,
    assistantIdsUnique: RESULT.phases.rldMergeNoDuplicate.assistantIdsUnique
  })

  // ── 阶段 15：在途轮真刷新 → 停止入口（R-12） ───────────────────────────
  // 同 ⑭ 的在途窗口守门：reload 前复查 `loading`，已收尾就换新会话重试一次。
  const markerRld2 = `RLD2-${STAMP}`
  let inflightRld2 = null
  for (let attempt = 0; attempt < 2; attempt++) {
    await evaluate(`(async () => { await ${AI_EXPR}.resetSession(); return true })()`)
    await sleep(400)
    const tag = attempt === 0 ? '' : '-b'
    const promptRld2 = `命名轮蓝本 RLD2-${STAMP}${tag}：turn-text stall-frames=${RLD_FRAME_GAP_MS}${RLD_LONG_ASK}`
    await evaluate(`(() => { void ${AI_EXPR}.send(${JSON.stringify(promptRld2)}); return true })()`, { awaitPromise: false })
    inflightRld2 = await waitFor(
      `(() => { const ai = ${AI_EXPR}
        const last = [...ai.messages].reverse().find(m => m.role === 'assistant')
        return (ai.loading && last && last.content && last.content.length > 0)
          ? { runId: ai.activeRunId } : null })()`,
      { tries: 120, intervalMs: 100 }
    )
    const stillInFlight = await evaluate(`${AI_EXPR}.loading`, { awaitPromise: false })
    if (inflightRld2 && stillInFlight) break
    console.log(`[阶段15] 在途窗口已关闭（第 ${attempt + 1} 次：loading=${stillInFlight}），换新会话重试`)
  }
  await realReload('阶段15')
  const stopReadyRld2 = await waitFor(
    `(() => { const ai = ${AI_EXPR}
      if (!(ai.historyLoaded && ai.loading)) return null
      const f = ${rldFactsExpr()}
      // 停止要打在**有正文在流**的轮上（⑱ 的前提是"已显示正文逐字保留"）——桩下重放瞬时，
      // 真模型下要等首段 delta，故把"已有正文"一并作为就绪条件（采样时点，不是放宽判据）。
      return (f.stopVisible && f.stopDisabled === false
        && f.lastAssistant && f.lastAssistant.text.length > 0) ? f : null })()`,
    { tries: 120, intervalMs: 100 }
  )
  const runIdRld2 = stopReadyRld2 ? stopReadyRld2.activeRunId : null
  // 点停止（真实 DOM 点击：用户可见入口，不是 store 直调）
  const stopClickedAt = Date.now()
  await evaluate(`(() => {
    const btn = document.querySelector('[data-testid="stop-btn"]')
    if (!btn) return false
    btn.click(); return true
  })()`, { awaitPromise: false })
  let cancelledAtMs = null
  let statusRld2 = null
  let afterStopRld2 = null
  for (let i = 0; i < 30; i++) { // 3s 预算（每 100ms 一拍）
    if (runIdRld2) { statusRld2 = await runStatus(runIdRld2) }
    afterStopRld2 = await evaluate(rldFactsExpr(), { awaitPromise: false })
    if (statusRld2 === 'CANCELLED' && afterStopRld2.loading === false && afterStopRld2.suspended === false) {
      cancelledAtMs = Date.now() - stopClickedAt
      break
    }
    await sleep(100)
  }
  RESULT.phases.rldStopAfterReload = {
    inflightBeforeReload: inflightRld2, stopReady: stopReadyRld2 && {
      stopVisible: stopReadyRld2.stopVisible, stopDisabled: stopReadyRld2.stopDisabled,
      activeRunId: stopReadyRld2.activeRunId, count: stopReadyRld2.count
    },
    runId: runIdRld2, snapshotStatus: statusRld2, cancelledAfterMs: cancelledAtMs,
    afterStop: afterStopRld2 && {
      loading: afterStopRld2.loading, suspended: afterStopRld2.suspended, stopped: afterStopRld2.stopped,
      banner: afterStopRld2.banner, notice: afterStopRld2.notice, sendVisible: afterStopRld2.sendVisible
    }
  }
  console.log('[阶段15 刷新后停止]', JSON.stringify(RESULT.phases.rldStopAfterReload))
  assert('⑰', !!stopReadyRld2 && !!runIdRld2
    && statusRld2 === 'CANCELLED' && cancelledAtMs !== null && cancelledAtMs <= 3000
    && !!afterStopRld2 && afterStopRld2.loading === false && afterStopRld2.suspended === false
    && afterStopRld2.sendVisible === true && afterStopRld2.banner === false,
  {
    stopVisible: stopReadyRld2 ? stopReadyRld2.stopVisible : null,
    runId: runIdRld2, snapshotStatus: statusRld2, cancelledAfterMs: cancelledAtMs,
    loadingAfterStop: afterStopRld2 ? afterStopRld2.loading : null,
    suspendedAfterStop: afterStopRld2 ? afterStopRld2.suspended : null,
    sendVisible: afterStopRld2 ? afterStopRld2.sendVisible : null,
    redBanner: afterStopRld2 ? afterStopRld2.banner : null
  })

  // ── 阶段 16：停止后再真刷新 → 页面态正确（无幽灵在途轮） ────────────────
  const beforeStopReload = await evaluate(rldFactsExpr(), { awaitPromise: false })
  await realReload('阶段16')
  const afterStopReload = await waitFor(
    `(() => { const ai = ${AI_EXPR}
      if (!ai.historyLoaded) return null
      const f = ${rldFactsExpr()}
      return f })()`,
    { tries: 100, intervalMs: 100 }
  )
  const currentFoundRld2 = afterStopReload
    ? await runCurrentFound(afterStopReload.sessionId) : null
  const textBeforeRld2 = String(beforeStopReload?.lastAssistant?.text ?? '')
  const textAfterRld2 = String(afterStopReload?.lastAssistant?.text ?? '')
  const appendedRld2 = textAfterRld2.length - textBeforeRld2.length
  RESULT.phases.rldReloadAfterStop = {
    beforeReload: beforeStopReload && {
      count: beforeStopReload.count, lastAssistantLen: textBeforeRld2.length,
      lastAssistantTail: textBeforeRld2.slice(-24),
      roles: beforeStopReload.roles, textHeads: beforeStopReload.textHeads
    },
    afterReload: afterStopReload && {
      count: afterStopReload.count, lastAssistantLen: textAfterRld2.length,
      lastAssistantTail: textAfterRld2.slice(-24),
      roles: afterStopReload.roles, textHeads: afterStopReload.textHeads,
      loading: afterStopReload.loading, suspended: afterStopReload.suspended,
      streamInterrupted: afterStopReload.streamInterrupted, banner: afterStopReload.banner,
      takeoverHint: afterStopReload.takeoverHint,
      samePromptUserBubbles: afterStopReload.userTexts.filter((t) => t.includes(markerRld2)).length
    },
    // 诊断留痕（"闪红"复核用）：停止瞬间 UI 正文 vs 刷新后 `/history` 恢复的正文差几个字符、
    // 差在哪 —— 原判据要求长度逐字相等，实测出现 280 → 283（服务端在停止瞬间多收尾了几个字符），
    // 那是"历史更完整"，不是丢字。
    textPrefixPreserved: textAfterRld2.startsWith(textBeforeRld2),
    appendedAfterStopChars: appendedRld2,
    runsCurrentFound: currentFoundRld2
  }
  console.log('[阶段16 停止后刷新]', JSON.stringify(RESULT.phases.rldReloadAfterStop))
  // ⑱ 判据（本批改口径）：正文**不丢已显示的部分** = 刷新后正文以停止瞬间的正文为前缀（允许服务端
  // 多收尾若干字符），而不是长度逐字相等；其余（气泡数、无红条、无幽灵在途轮、用户提问恰 1 条）
  // 一字不动。
  assert('⑱', !!beforeStopReload && !!afterStopReload
    && afterStopReload.count === beforeStopReload.count
    && textBeforeRld2.length > 0 && textAfterRld2.startsWith(textBeforeRld2)
    && afterStopReload.streamInterrupted === false && afterStopReload.banner === false
    && afterStopReload.takeoverHint === false
    && afterStopReload.loading === false && afterStopReload.suspended === false
    && afterStopReload.userTexts.filter((t) => t.includes(markerRld2)).length === 1
    && currentFoundRld2 === false,
  RESULT.phases.rldReloadAfterStop)

  // ── 阶段 17：已完成轮刷新 → 完整恢复且**零横幅**（R-10 对照，防过度修复） ──
  await evaluate(`(async () => { await ${AI_EXPR}.resetSession(); return true })()`)
  await sleep(400)
  const promptDone = `你好 V7RLD3-${STAMP}`
  await sendRound(promptDone)
  const doneBeforeReload = await evaluate(rldFactsExpr(), { awaitPromise: false })
  await realReload('阶段17')
  const doneAfterReload = await waitFor(
    `(() => { const ai = ${AI_EXPR}
      if (!ai.historyLoaded) return null
      const f = ${rldFactsExpr()}
      return f })()`,
    { tries: 100, intervalMs: 100 }
  )
  RESULT.phases.rldReloadCompletedRound = {
    beforeReload: doneBeforeReload && {
      count: doneBeforeReload.count, lastAssistantText: doneBeforeReload.lastAssistant?.text
    },
    afterReload: doneAfterReload && {
      count: doneAfterReload.count, lastAssistantText: doneAfterReload.lastAssistant?.text,
      loading: doneAfterReload.loading, suspended: doneAfterReload.suspended,
      banner: doneAfterReload.banner, takeoverHint: doneAfterReload.takeoverHint,
      notice: doneAfterReload.notice
    }
  }
  console.log('[阶段17 已完成轮刷新]', JSON.stringify({
    before: { count: doneBeforeReload?.count, textLen: doneBeforeReload?.lastAssistant?.text.length },
    after: doneAfterReload && { count: doneAfterReload.count, textLen: doneAfterReload.lastAssistant?.text.length },
    banner: doneAfterReload?.banner, takeoverHint: doneAfterReload?.takeoverHint
  }))
  assert('⑲', !!doneBeforeReload && !!doneAfterReload
    && doneAfterReload.count === doneBeforeReload.count
    && doneAfterReload.lastAssistant?.text === doneBeforeReload.lastAssistant?.text
    && doneAfterReload.banner === false && doneAfterReload.takeoverHint === false
    && doneAfterReload.loading === false && doneAfterReload.suspended === false,
  RESULT.phases.rldReloadCompletedRound)

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
  // 破坏性守则（默认关闭 + **四重**闸，见 killBackendByListeningPort）：未授权、或在保护端口
  // （8081 / 5202 / 6379）上、或反查不到 java 进程时，本阶段如实报 **SKIP** 并把 `code` 与
  // 触发条件一起落盘（不假绿）。标准跑法（页面 5202 → 代理 → 8081）**必然**落在 PROTECTED_PORT 档：
  // 杀 8081 是施工硬红线，本脚本不提供任何绕过口子。
  //
  // 先换新会话再起这一轮：上游的路由判定看本轮上下文（真模型看对话语境、桩看首条 user 消息的
  // 轮次台账），沿用旧会话会退化成文本轮、挂不起来。
  // 前置先判（非破坏性）：跑不了就**不造轮、不花模型调用**，直接带 code 落 SKIP。
  const pre12 = killPrecondition()
  let inflight2 = null
  let killed = pre12
  if (pre12.ok) {
    await evaluate(`(async () => { await ${AI_EXPR}.resetSession(); return true })()`)
    inflight2 = await startSuspendedRound(CONFIRM_PROMPT)
    killed = inflight2
      ? await killBackendByListeningPort()
      : { ok: false, code: 'NO_INFLIGHT', reason: '未进入挂起态（未执行杀进程）' }
  }
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
    notice: s12 ? s12.notice : null,
    skipCode: killed.ok ? null : killed.code ?? 'UNKNOWN',
    runnableWhen: 'SMOKE_API/SMOKE_APP 指向一次性实例对（后端 22730 + 前端 22731，前端 VITE_API_BASE=http://localhost:22730，Redis DB 2）+ SMOKE_KILL_BACKEND=1'
  }
  console.log('[阶段12 真断流对照]', JSON.stringify(RESULT.phases.realInterruptStillRed))
  if (!killed.ok) {
    RESULT.skipped = { ...(RESULT.skipped || {}), '⑫': `${killed.code}: ${killed.reason}` }
    console.log(`[断言⑫] SKIP [${killed.code}] ${killed.reason}`)
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
