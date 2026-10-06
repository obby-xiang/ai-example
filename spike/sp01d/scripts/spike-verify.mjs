// SP-01d 逐项验证脚本（Node 22，恒 UTF-8，避免 Git Bash + curl 的中文编码坑）。
//
// 用法：node scripts/spike-verify.mjs <case> [baseUrl] [proxyUrl]
//   case 见 CASES；证据写入 evidence/（可用 EVIDENCE_DIR 覆盖）
//
// 每个 case 自行：清空代理故障配置 / 清空工具台账 / 跑请求 / 落证据。
import fs from 'node:fs';
import path from 'node:path';

const CASE = process.argv[2];
const BASE = process.argv[3] || 'http://127.0.0.1:18303';
const PROXY = process.argv[4] || 'http://127.0.0.1:18304';
const EVIDENCE = process.env.EVIDENCE_DIR || 'evidence';
fs.mkdirSync(EVIDENCE, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(method, url, body) {
  try {
    const res = await fetch(url, {
      method,
      headers: body ? { 'content-type': 'application/json' } : {},
      body: body ? JSON.stringify(body) : undefined
    });
    const text = await res.text();
    let json = null;
    try { json = JSON.parse(text); } catch { /* not json */ }
    return { httpStatus: res.status, body: json ?? text };
  } catch (e) {
    return { httpStatus: null, body: null, error: String((e && e.message) || e) };
  }
}

const get = (u) => api('GET', u);
const post = (u, b) => api('POST', u, b);

async function setFault(cfg) {
  return (await post(PROXY + '/__fault', { resetCounter: true, ...cfg })).body;
}

async function resetProxy() {
  return (await post(PROXY + '/__reset')).body;
}

async function wire() {
  return (await get(PROXY + '/__wire')).body;
}

async function clearToolLog() {
  return (await api('DELETE', BASE + '/api/dev/tool-exec-log')).body;
}

function parseSseBlock(block, tsMs) {
  const lines = block.split('\n');
  let event = 'message';
  const dataLines = [];
  for (const line of lines) {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
  }
  if (dataLines.length === 0) return null;
  const raw = dataLines.join('\n');
  let data = raw;
  try { data = JSON.parse(raw); } catch { /* keep raw */ }
  return { tsMs, event, data, rawData: raw };
}

/** 读 SSE 流；onEvent 同步钩子（不可 await，避免阻塞读循环）。 */
async function readSse(sessionBody, opts = {}) {
  const { clientAbortMs = 40000, onEvent = null, path: p = '/api/chat' } = opts;
  const ac = new AbortController();
  let aborted = false;
  const t0 = Date.now();
  const timer = clientAbortMs > 0 ? setTimeout(() => { aborted = true; ac.abort(); }, clientAbortMs) : null;
  const events = [];
  let raw = '';
  let error = null;
  try {
    const res = await fetch(BASE + p, {
      method: 'POST',
      headers: { 'content-type': 'application/json', accept: 'text/event-stream' },
      body: JSON.stringify(sessionBody),
      signal: ac.signal
    });
    const dec = new TextDecoder('utf-8');
    let buf = '';
    for await (const chunk of res.body) {
      const s = dec.decode(chunk, { stream: true });
      raw += s;
      buf += s;
      let i;
      while ((i = buf.indexOf('\n\n')) >= 0) {
        const block = buf.slice(0, i);
        buf = buf.slice(i + 2);
        const ev = parseSseBlock(block, Date.now());
        if (ev) { events.push(ev); if (onEvent) onEvent(ev); }
      }
    }
  } catch (e) {
    error = String((e && e.message) || e);
  } finally {
    if (timer) clearTimeout(timer);
  }
  return { events, raw, startedAtMs: t0, endedAtMs: Date.now(), clientAborted: aborted, error };
}

function sseDigest(r) {
  const deltas = r.events.filter((e) => e.event === 'delta').map((e) => e.data.content);
  const concat = deltas.join('');
  // 检测"已产出的前缀被重放"：找最大的 L，使得 text 的前 L 个字符在后面再次出现
  let duplicatedPrefixLen = 0;
  for (let l = 1; l <= Math.floor(concat.length / 2); l++) {
    if (concat.slice(0, l) === concat.slice(l, 2 * l)) duplicatedPrefixLen = l;
  }
  return {
    eventCounts: r.events.reduce((m, e) => { m[e.event] = (m[e.event] || 0) + 1; return m; }, {}),
    eventSequence: r.events.map((e) => `${e.event}@${e.tsMs - r.startedAtMs}ms`),
    deltaCount: deltas.length,
    concatenatedDeltaText: concat,
    concatenatedDeltaLen: concat.length,
    duplicatedPrefixLen,
    duplicatedPrefixLikely: duplicatedPrefixLen > 0,
    terminalEvent: r.events.length ? r.events[r.events.length - 1].event : null,
    terminalData: r.events.length ? r.events[r.events.length - 1].data : null,
    elapsedMs: r.endedAtMs - r.startedAtMs,
    clientAborted: r.clientAborted,
    streamError: r.error
  };
}

function write(name, obj) {
  fs.writeFileSync(path.join(EVIDENCE, name), JSON.stringify(obj, null, 2));
  console.log('  -> evidence/' + name);
}

// ------------------------------------------------------------------ cases

const CASES = {};

/** 对照组：无故障，guarded 正常完成。 */
CASES['vd1-baseline'] = async () => {
  await resetProxy();
  await setFault({ mode: 'none' });
  await clearToolLog();
  const sessionId = 'vd1-baseline';
  const r = await readSse({ sessionId, message: '现在几点？必须调用 getServerTime 工具。', strategy: 'guarded' });
  const state = (await get(`${BASE}/api/chat/${sessionId}/state`)).body;
  return { case: 'vd1-baseline', description: '无故障对照（代理透传）', fault: { mode: 'none' },
    sse: sseDigest(r), sessionState: state, wire: await wire() };
};

/** V-d1③：无超时（plain）策略下，上游"一直静默但连接不关"时的实际行为。 */
CASES['vd1-plain-hang'] = async () => {
  await resetProxy();
  await setFault({ mode: 'stall', stallMs: 180000 });   // 两分钟内一直不回任何字节，也不关连接
  await clearToolLog();
  const sessionId = 'vd1-plain-hang';
  const r = await readSse({ sessionId, message: '你好', strategy: 'plain' }, { clientAbortMs: 15000 });
  const state1 = (await get(`${BASE}/api/chat/${sessionId}/state`)).body;
  const cancelState1 = (await get(`${BASE}/api/dev/cancel-state`)).body;
  await sleep(3000);
  const state2 = (await get(`${BASE}/api/chat/${sessionId}/state`)).body;
  const cancelState2 = (await get(`${BASE}/api/dev/cancel-state`)).body;
  const killed = (await post(PROXY + '/__kill')).body;
  await sleep(2500);
  const cancelState3 = (await get(`${BASE}/api/dev/cancel-state`)).body;
  const state3 = (await get(`${BASE}/api/chat/${sessionId}/state`)).body;
  return { case: 'vd1-plain-hang',
    description: 'plain 策略（无超时无重试）+ 上游持续静默且不关连接：客户端 15s 放弃后服务端线程是否还在挂',
    fault: { mode: 'stall', stallMs: 180000 },
    sse: sseDigest(r),
    afterClientAbort: { sessionState: state1, blockedThreads: cancelState1.activeThreads },
    threeSecondsLater: { sessionState: state2, blockedThreads: cancelState2.activeThreads },
    proxyKill: killed,
    afterProxyKill: { sessionState: state3, blockedThreads: cancelState3.activeThreads } };
};

/** V-d1①：首包静默 —— 有界重试次数与总时长。 */
async function firstEventSilence(strategy) {
  await resetProxy();
  await setFault({ mode: 'stall', stallMs: 9000 });
  await clearToolLog();
  const sessionId = `vd1-${strategy}-first`;
  const r = await readSse({ sessionId, message: '现在几点？', strategy }, { clientAbortMs: 30000 });
  const state = (await get(`${BASE}/api/chat/${sessionId}/state`)).body;
  return { case: `vd1-${strategy}-first`, description: `${strategy} 策略 + 首包静默（代理不发任何字节，9s 后 RST）`,
    fault: { mode: 'stall', stallMs: 9000 }, sse: sseDigest(r), sessionState: state, wire: await wire() };
}

CASES['vd1-guarded-first'] = () => firstEventSilence('guarded');
CASES['vd1-ref-first'] = () => firstEventSilence('ref');
CASES['vd1-plain-first'] = () => firstEventSilence('plain');

/** V-d1②③：已产出内容后的上游静默（事件间静默）。 */
async function midStreamSilence(strategy) {
  await resetProxy();
  await setFault({ mode: 'stall-after-content', contentEvents: 2, stallMs: 12000 });
  await clearToolLog();
  const sessionId = `vd1-${strategy}-mid`;
  const r = await readSse({ sessionId, message: '用三句话介绍你自己。', strategy }, { clientAbortMs: 40000 });
  const state = (await get(`${BASE}/api/chat/${sessionId}/state`)).body;
  return { case: `vd1-${strategy}-mid`,
    description: `${strategy} 策略 + 已产出 2 个正文 delta 后上游静默（代理停止发送、保持连接，12s 后 RST）`,
    fault: { mode: 'stall-after-content', contentEvents: 2, stallMs: 12000 },
    sse: sseDigest(r), sessionState: state, wire: await wire() };
}

CASES['vd1-guarded-mid'] = () => midStreamSilence('guarded');
CASES['vd1-ref-mid'] = () => midStreamSilence('ref');
CASES['vd1-refbare-mid'] = () => midStreamSilence('refbare');
CASES['vd1-refbare-first'] = () => firstEventSilence('refbare');
CASES['vd1-refbare-tooldup'] = () => toolReplay('refbare');
CASES['vd1-plain-mid'] = () => midStreamSilence('plain');

/** V-d1②③：已产出内容后上游"静默截断"（TCP 正常 FIN，无 [DONE]）—— 是否被当成正常完成。 */
CASES['vd1-fin-content'] = async () => {
  await resetProxy();
  await setFault({ mode: 'fin-after-content', contentEvents: 2 });
  await clearToolLog();
  const sessionId = 'vd1-fin-content';
  const r = await readSse({ sessionId, message: '用三句话介绍你自己。', strategy: 'guarded' }, { clientAbortMs: 30000 });
  const state = (await get(`${BASE}/api/chat/${sessionId}/state`)).body;
  return { case: 'vd1-fin-content',
    description: '上游已产出 2 个正文 delta 后静默截断（TCP 正常 FIN，无 [DONE]）：应用是否察觉',
    fault: { mode: 'fin-after-content', contentEvents: 2 },
    sse: sseDigest(r), sessionState: state, wire: await wire() };
};

/** V-d1 补充：工具执行期间下游无事件 —— "首包超时"小于工具耗时会误杀正在执行的工具。 */
async function longToolNoCancel(strategy) {
  await resetProxy();
  await setFault({ mode: 'none' });
  await clearToolLog();
  const sessionId = `vd1-${strategy}-longtool`;
  const sys = '你必须调用 longTask 工具执行长任务，参数 seconds=8，cooperative=false，然后据结果回答。回复使用中文。';
  const r = await readSse({ sessionId, message: '请执行一个 8 秒的长任务。', strategy, system: sys },
    { clientAbortMs: 40000 });
  const state = (await get(`${BASE}/api/chat/${sessionId}/state`)).body;
  const toolLog = (await get(`${BASE}/api/dev/tool-exec-log`)).body;
  return { case: `vd1-${strategy}-longtool`,
    description: `${strategy} 策略 + 8s 工具、首包超时 5s、无取消：验证工具执行期间无下游事件时超时的表现`,
    sse: sseDigest(r), sessionState: state, toolExecLog: toolLog, wire: await wire() };
}

CASES['vd1-guarded-longtool'] = () => longToolNoCancel('guarded');
CASES['vd1-ref-longtool'] = () => longToolNoCancel('ref');

/** V-d1②：重试是否会重放"整条上游交互"（含工具副作用）。 */
async function toolReplay(strategy) {
  await resetProxy();
  await setFault({ mode: 'stall-after-content', contentEvents: 1, stallMs: 12000, onlyRequestIndex: 2 });
  await clearToolLog();
  const sessionId = `vd1-${strategy}-tooldup`;
  const sys = '你必须先调用 getServerTime 工具获取时间，然后据结果回答。回复使用中文。';
  const r = await readSse({ sessionId, message: '现在几点？', strategy, system: sys }, { clientAbortMs: 60000 });
  const state = (await get(`${BASE}/api/chat/${sessionId}/state`)).body;
  const toolLog = (await get(`${BASE}/api/dev/tool-exec-log`)).body;
  return { case: `vd1-${strategy}-tooldup`,
    description: `${strategy} 策略 + 第 2 次上游请求（工具执行后的最终作答）已产出 1 个正文 delta 后静默：验证重试是否重放工具副作用`,
    fault: { mode: 'stall-after-content', contentEvents: 1, stallMs: 12000, onlyRequestIndex: 2 },
    sse: sseDigest(r), sessionState: state, toolExecLog: toolLog, wire: await wire() };
}

CASES['vd1-guarded-tooldup'] = () => toolReplay('guarded');
CASES['vd1-ref-tooldup'] = () => toolReplay('ref');

/** V-d1②：硬截断（RST）与静默截断（正常 FIN）。 */
async function truncation(mode) {
  await resetProxy();
  await setFault({ mode, relayEvents: mode === 'truncate-fin' ? 8 : 3, stallMs: 500 });
  await clearToolLog();
  const sessionId = `vd1-${mode}`;
  const r = await readSse({ sessionId, message: '用三句话介绍你自己。', strategy: 'guarded' }, { clientAbortMs: 30000 });
  const state = (await get(`${BASE}/api/chat/${sessionId}/state`)).body;
  return { case: `vd1-${mode}`,
    description: mode === 'truncate' ? '上游硬截断（透传 3 个事件后 RST）'
      : '上游静默截断（透传 8 个事件后 TCP 正常 FIN，SSE 无 [DONE]）',
    fault: { mode, relayEvents: mode === 'truncate-fin' ? 8 : 3 },
    sse: sseDigest(r), sessionState: state, wire: await wire() };
}

CASES['vd1-truncate-rst'] = () => truncation('truncate');
CASES['vd1-truncate-fin'] = () => truncation('truncate-fin');

/** V-d2 主实验：前端执行工具 → 回灌 → 重复回灌同一 toolCallId。 */
CASES['vd2-fronttool'] = async () => {
  await resetProxy();
  await setFault({ mode: 'none' });
  await clearToolLog();
  const sessionId = 'vd2-fronttool';
  const sys = '你必须调用 getServerTime 工具来获取时间，不允许不调用工具直接回答。回复使用中文。';
  const start = await post(BASE + '/api/fronttool/start',
    { sessionId, message: '现在几点？', system: sys });
  const runId = start.body.runId;
  const pending = start.body.pending || [];
  const out = { case: 'vd2-fronttool',
    description: '前端执行工具架构：start 暂停 → 回灌 tool-result → 重复回灌同一 toolCallId',
    start: start.body };

  if (pending.length === 0) {
    out.note = '模型未发起工具调用，无法进入回灌路径';
    out.finalState = (await get(`${BASE}/api/fronttool/${runId}`)).body;
    return out;
  }
  const id = pending[0].toolCallId;
  out.toolCallId = id;
  out.firstSubmit = (await post(`${BASE}/api/fronttool/${runId}/result`,
    { toolCallId: id, result: '2026-10-06 20:30:00' })).body;
  out.secondSubmitSameId = (await post(`${BASE}/api/fronttool/${runId}/result`,
    { toolCallId: id, result: '2026-10-06 20:30:00' })).body;
  out.thirdSubmitUnknownId = (await post(`${BASE}/api/fronttool/${runId}/result`,
    { toolCallId: 'call_not_exists', result: 'x' })).body;
  out.finalState = (await get(`${BASE}/api/fronttool/${runId}`)).body;
  return out;
};

/** V-d2 子实验 A：官方 ToolCallingManager 直调，重复 toolCallId。 */
CASES['vd2-spi-dup'] = async () => {
  const runId = 'vd2-spi-dup-' + Date.now();
  const before = (await get(`${BASE}/api/dev/tool-exec-log`)).body;
  const res = await post(BASE + '/api/dev/spi-duplicate-toolcall', { runId });
  const after = (await get(`${BASE}/api/dev/tool-exec-log`)).body;
  return { case: 'vd2-spi-dup',
    description: '把一个"两个 tool_call 共用同一 id"的 AssistantMessage 直接交给官方 ToolCallingManager.executeToolCalls',
    runId, response: res.body,
    toolExecLogDelta: after.slice(before.length),
    toolExecLogTotal: after.length };
};

/** V-d3：长耗时工具执行中取消（协作式 / 非协作式对照组）。
 *  seconds：协作式用 8s（靠分片检查提前返回）；非协作式用 3s（必须小于事件间超时 6s，
 *  否则会被"工具执行期间无下游事件"的超时打断，无法验证"轮次边界才生效"的语义）。 */
async function cancelCase(cooperative) {
  const seconds = cooperative ? 8 : 3;
  await resetProxy();
  await setFault({ mode: 'none' });
  await clearToolLog();
  const sessionId = `vd3-${cooperative ? 'coop' : 'hard'}`;
  const sys = '你必须调用 longTask 工具执行长任务，参数 seconds=' + seconds + '，'
    + `cooperative=${cooperative}，然后据结果回答。回复使用中文。`;
  const st = { runId: null, sawToolCallAtMs: null, toolActiveAtMs: null, cancelSentAtMs: null, cancelResp: null };
  const reader = readSse({ sessionId, message: '请执行一个 8 秒的长任务。', strategy: 'guarded', system: sys },
    { clientAbortMs: 45000, onEvent: (ev) => {
      if (ev.event === 'start') st.runId = ev.data.runId;
      if (ev.event === 'tool_call' && st.sawToolCallAtMs === null) st.sawToolCallAtMs = Date.now();
    } });

  const watcher = (async () => {
    const deadline = Date.now() + 30000;
    while (Date.now() < deadline && !st.runId) await sleep(50);
    while (Date.now() < deadline) {
      const s = (await get(BASE + '/api/dev/tool-activity')).body;
      if (st.runId && s.active && s.active[st.runId]) {
        st.toolActiveAtMs = Date.now();
        await sleep(cooperative ? 900 : 900);
        st.cancelSentAtMs = Date.now();
        st.cancelResp = (await post(`${BASE}/api/chat/${sessionId}/cancel`)).body;
        st.cancelAckAtMs = Date.now();
        return;
      }
      await sleep(80);
    }
    st.watcherTimeout = true;
  })();

  const r = await reader;
  await watcher;
  const state = (await get(`${BASE}/api/chat/${sessionId}/state`)).body;
  const toolLog = (await get(`${BASE}/api/dev/tool-exec-log`)).body;
  const activity = (await get(`${BASE}/api/dev/tool-activity`)).body;
  const cancelState = (await get(`${BASE}/api/dev/cancel-state`)).body;
  const done = r.events.find((e) => e.event === 'done');
  return { case: `vd3-${cooperative ? 'coop' : 'hard'}`,
    description: `longTask(${seconds}s, cooperative=${cooperative}) 执行中发送取消信号`,
    timeline: {
      runStartMs: r.startedAtMs,
      startEventAtMs: (r.events.find((e) => e.event === 'start') || {}).tsMs,
      toolCallEventAtMs: st.sawToolCallAtMs,
      toolActiveAtMs: st.toolActiveAtMs,
      cancelSentAtMs: st.cancelSentAtMs,
      cancelAckAtMs: st.cancelAckAtMs,
      streamEndedAtMs: r.endedAtMs,
      msFromCancelToStreamEnd: st.cancelSentAtMs ? r.endedAtMs - st.cancelSentAtMs : null
    },
    cancelResponse: st.cancelResp,
    doneEvent: done ? done.data : null,
    allEvents: r.events.map((e) => ({ tsOffsetMs: e.tsMs - r.startedAtMs, event: e.event, data: e.data })),
    sessionState: state,
    toolExecLog: toolLog,
    toolActivity: activity,
    cancelRegistryState: cancelState,
    wire: await wire() };
}

CASES['vd3-coop-cancel'] = () => cancelCase(true);
CASES['vd3-hard-cancel'] = () => cancelCase(false);

// REFMODEL：把参照实现的重试模式直接用在 ChatModel（绕开 ChatClient 的 advisor 链）
CASES['vd1-refmodel-first'] = () => firstEventSilence('refmodel');
CASES['vd1-refmodel-mid'] = () => midStreamSilence('refmodel');

// ------------------------------------------------------------------ main

const fn = CASES[CASE];
if (!fn) {
  console.error('unknown case: ' + CASE);
  console.error('available: ' + Object.keys(CASES).join(', '));
  process.exit(2);
}
console.log(`[spike-verify] case=${CASE} base=${BASE} proxy=${PROXY}`);
const result = await fn();
result.capturedAt = new Date().toISOString();
write(`sp01d-${CASE}.json`, result);
if (result.sse && result.sse.concatenatedDeltaText !== undefined) {
  fs.writeFileSync(path.join(EVIDENCE, `sp01d-${CASE}-sse.txt`),
    `# case=${CASE}\n` + (result.sse.eventSequence || []).join('\n'));
}
console.log('[spike-verify] done');
