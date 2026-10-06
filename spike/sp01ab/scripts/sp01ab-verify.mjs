// SP-01a / SP-01b 验证脚本：用 HTTP + SSE 模拟“前端”，驱动官方循环里的挂起-恢复。
//
//   node sp01ab-verify.mjs <outDir> [all|A|B|C|D]
//
// 产出（全部为原始证据，不含任何密钥）：
//   <outDir>/sp01ab-<场景>-sse-raw.txt   SSE 逐帧原文（含客户端到达时间戳，心跳帧可见）
//   <outDir>/sp01ab-<场景>-timeline.json 服务端事件时序（seq/ts/event/data）
//   <outDir>/sp01ab-<场景>-checks.json   断言结果
//   <outDir>/sp01ab-summary.json         汇总
import { appendFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const BASE = process.env.SPIKE_BASE ?? 'http://127.0.0.1:18301';
const OUT = process.argv[2] ?? '.';
const WHICH = (process.argv[3] ?? 'all').toUpperCase();
// 非流式（/api/chat）与流式（/api/chat-stream）两条官方循环路径都测，用环境变量切换
const CHAT_PATH = process.env.SPIKE_STREAM === '1' ? '/api/chat-stream' : '/api/chat';
mkdirSync(OUT, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const fmt = (d = new Date()) => {
  const p = (n, w = 2) => String(n).padStart(w, '0');
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
};
const log = (...a) => console.log(...a);

async function post(path, body) {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* 保留原文 */ }
  return { status: res.status, json, text };
}
async function get(path) {
  const res = await fetch(BASE + path);
  return { status: res.status, json: await res.json() };
}

class SseClient {
  constructor(name, outPath) {
    this.name = name;
    this.outPath = outPath;
    this.events = [];
    this.pings = [];
    this.frames = [];
    this.closed = false;
  }

  async open(sessionId) {
    this.ctrl = new AbortController();
    this.t0 = Date.now();
    appendFileSync(this.outPath, `# SSE subscribe ${BASE}/api/events/${sessionId} at ${new Date().toISOString()}\n`);
    this.resp = await fetch(`${BASE}/api/events/${sessionId}`, {
      headers: { Accept: 'text/event-stream' },
      signal: this.ctrl.signal,
    });
    appendFileSync(this.outPath, `# HTTP ${this.resp.status} content-type=${this.resp.headers.get('content-type')}\n`);
    this.readLoop = this.#read();
    return this;
  }

  async #read() {
    const reader = this.resp.body.getReader();
    const dec = new TextDecoder();
    let buf = '';
    let cur = { event: null, data: null };
    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buf += dec.decode(value, { stream: true });
        let idx;
        while ((idx = buf.indexOf('\n')) >= 0) {
          const raw = buf.slice(0, idx).replace(/\r$/, '');
          buf = buf.slice(idx + 1);
          appendFileSync(this.outPath, `[+${String(Date.now() - this.t0).padStart(6, '0')}ms] ${raw}\n`);
          if (raw.startsWith('event:')) cur.event = raw.slice(6).trim();
          else if (raw.startsWith('data:')) cur.data = raw.slice(5).trim();
          else if (raw === '' && cur.event) {
            const ev = this.#dispatch(cur);
            cur = { event: null, data: null };
            if (ev?.event === 'done') return;
          }
        }
      }
    } catch (e) {
      if (!this.closed) appendFileSync(this.outPath, `# read error ${e.name}: ${e.message}\n`);
    }
  }

  #dispatch(frame) {
    let data = null;
    try { data = JSON.parse(frame.data); } catch { data = { _raw: frame.data }; }
    const ev = { clientTs: Date.now() - this.t0, event: frame.event, ...data };
    if (frame.event === 'ping') this.pings.push(ev);
    else this.events.push(ev);
    this.frames.push(ev);
    return ev;
  }

  /** 等待（或已在缓冲中命中）满足谓词的事件。 */
  async waitFor(pred, timeoutMs, label = 'event') {
    const hit = this.events.find(pred);
    if (hit) return hit;
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      await sleep(50);
      const found = this.events.find(pred);
      if (found) return found;
      if (this.closed) break;
    }
    throw new Error(`timeout waiting ${label} (${timeoutMs}ms)`);
  }

  close() {
    this.closed = true;
    try { this.ctrl.abort(); } catch { /* ignore */ }
  }
}

function record(checks, id, ok, detail) {
  checks.push({ id, ok: !!ok, detail });
  log(`  ${ok ? 'PASS' : 'FAIL'} ${id} :: ${detail}`);
}

async function collect(sessionId, scenario, sse, checks) {
  const timeline = await get(`/api/events/${sessionId}/timeline`);
  const snapshot = await get(`/api/session/${sessionId}`);
  const info = await get('/api/dev/info');
  writeFileSync(join(OUT, `sp01ab-${scenario}-timeline.json`), JSON.stringify(timeline.json, null, 2));
  writeFileSync(join(OUT, `sp01ab-${scenario}-checks.json`),
    JSON.stringify({ scenario, sessionId, checks, snapshot: snapshot.json, sse: { pings: sse.pings.length, events: sse.events.map((e) => e.event) } }, null, 2));
  sse.close();
  await sleep(300);
  return { timeline: timeline.json.events, snapshot: snapshot.json, info: info.json };
}

const SENSITIVE_PROMPT = '请删除配置 def-777（这是一次真实的删除操作，按工具规则执行）。';

// ---------------------------------------------------------------- SP-01a
async function scenarioA() {
  const sid = `sp01ab-A-${fmt()}`;
  log(`\n=== SP-01a 前端工具暂停-恢复  sessionId=${sid} ===`);
  const sse = new SseClient('A', join(OUT, 'sp01ab-A-sse-raw.txt'));
  await sse.open(sid);
  await sleep(800);

  const nonce = 'FRONTEND-' + Math.random().toString(36).slice(2, 8).toUpperCase();
  const before = (await get('/api/dev/info')).json.toolCounters;
  const chat = await post(CHAT_PATH, {
    sessionId: sid,
    message: '请做两件事：1) 调用 calculate 计算 6 乘 7；2) 调用 get_user_profile 获取用户 u-1024 的会员资料。两个结果都拿到后再一起回答我。',
  });
  log(`  POST /api/chat -> ${chat.status} ${chat.text}`);

  const checks = [];
  record(checks, 'A0', chat.status === 202, `POST ${CHAT_PATH} 受理 ${chat.status}（streaming=${chat.json?.streaming}）`);

  let req = null;
  try {
    req = await sse.waitFor((e) => e.event === 'frontend_tool_request', 45000, 'frontend_tool_request');
  } catch (e) {
    record(checks, 'A1', false, String(e.message));
    const c = await collect(sid, 'A', sse, checks);
    return { scenario: 'A', sid, content: '', checks, timeline: c.timeline, sse };
  }
  record(checks, 'A1', req.data.name === 'get_user_profile' && !!req.data.toolCallId,
    `收到透出请求 name=${req.data.name} toolCallId=${req.data.toolCallId} args=${JSON.stringify(req.data.args)} timeoutSeconds=${req.data.timeoutSeconds}`);

  const tPending = Date.now();
  const feResult = `用户 u-1024 的前端缓存资料（来源=浏览器 localStorage）：姓名=张三-${nonce}，等级=VIP-${nonce}，积分=8800`;
  const res = await post('/api/frontend-tool-result', {
    sessionId: sid, toolCallId: req.data.toolCallId, result: feResult, source: 'browser-localStorage',
  });
  log(`  POST /api/frontend-tool-result -> ${res.status} ${res.text}`);
  record(checks, 'A2', res.status === 200 && res.json?.accepted === true, `回灌被受理 ${res.text}`);

  const echo = await sse.waitFor((e) => e.event === 'frontend_tool_result', 30000, 'frontend_tool_result');
  record(checks, 'A3', echo.data.ok === true && String(echo.data.result).includes(nonce),
    `循环内确认回灌结果（waitedMs=${echo.data.waitedMs}, source=${echo.data.source}）`);

  const done = await sse.waitFor((e) => e.event === 'done', 90000, 'done');
  record(checks, 'A4', done.data.failed === false, `循环正常结束 elapsedMs=${done.data.elapsedMs}`);

  const content = done.data.content ?? '';
  record(checks, 'A5', content.includes(nonce), `最终回答含前端回灌数据（姓名/等级的随机令牌 ${nonce}）`);
  record(checks, 'A6', /\b42\b/.test(content), '最终回答含后端工具结果 42');

  const c = await collect(sid, 'A', sse, checks);
  const errors = c.timeline.filter((e) => e.event === 'error');
  record(checks, 'A7', errors.length === 0, `无 error 事件（${errors.length}）`);
  const kinds = c.timeline.filter((e) => e.event === 'tool_start').map((e) => `${e.data.name}:${e.data.kind}`);
  record(checks, 'A8', kinds.includes('calculate:backend') && kinds.includes('get_user_profile:frontend'),
    `工具分类正确 ${JSON.stringify(kinds)}`);
  const after = c.info.toolCounters;
  record(checks, 'A9', after.get_user_profile_stub_body === before.get_user_profile_stub_body,
    `后端未执行前端工具方法体（桩计数 ${before.get_user_profile_stub_body} -> ${after.get_user_profile_stub_body}）`);
  record(checks, 'A10', after.calculate - before.calculate === 1, `后端工具 calculate 执行 1 次（${after.calculate - before.calculate}）`);
  return { scenario: 'A', sid, content, checks, timeline: c.timeline, sse, pendingMs: tPending };
}

// ------------------------------------------------- SP-01b 结局一：确认放行
async function scenarioB() {
  const sid = `sp01ab-B-${fmt()}`;
  log(`\n=== SP-01b 结局① 确认放行  sessionId=${sid} ===`);
  const sse = new SseClient('B', join(OUT, 'sp01ab-B-sse-raw.txt'));
  await sse.open(sid);
  await sleep(800);
  const before = (await get('/api/dev/info')).json.toolCounters;
  await post(CHAT_PATH, { sessionId: sid, message: SENSITIVE_PROMPT });

  const checks = [];
  const req = await sse.waitFor((e) => e.event === 'confirm_request', 45000, 'confirm_request');
  record(checks, 'B1', req.data.name === 'delete_config' && !!req.data.toolCallId,
    `确认门挂起并下发确认请求 toolCallId=${req.data.toolCallId} timeoutSeconds=${req.data.timeoutSeconds}`);

  const res = await post('/api/confirm', {
    sessionId: sid, toolCallId: req.data.toolCallId, approved: true, reason: 'PoC：人工确认放行', source: 'http-post',
  });
  log(`  POST /api/confirm(approve) -> ${res.status} ${res.text}`);
  record(checks, 'B2', res.status === 200 && res.json?.accepted === true, `批准被受理 ${res.text}`);

  const decision = await sse.waitFor((e) => e.event === 'confirm_decision', 30000, 'confirm_decision');
  record(checks, 'B3', decision.data.decision === 'approve' && decision.data.approved === true,
    `确认门结论 approve（waitedMs=${decision.data.waitedMs}）`);

  const done = await sse.waitFor((e) => e.event === 'done', 90000, 'done');
  const content = done.data.content ?? '';
  const c = await collect(sid, 'B', sse, checks);
  const exec = c.timeline.find((e) => e.event === 'tool_result' && e.data.name === 'delete_config');
  record(checks, 'B4', exec?.data.ok === true && exec?.data.executed === true,
    `工具已执行 tool_result=${JSON.stringify(exec?.data.result)}`);
  record(checks, 'B5', c.info.toolCounters.delete_config - before.delete_config === 1,
    `后端 delete_config 执行次数 +1（${before.delete_config} -> ${c.info.toolCounters.delete_config}）`);
  record(checks, 'B6', content.includes('删除'), `最终回答基于真实执行结果：${content.slice(0, 120)}`);
  return { scenario: 'B', sid, content, checks, timeline: c.timeline, sse };
}

// ------------------------------------------------- SP-01b 结局二：拒绝
async function scenarioC() {
  const sid = `sp01ab-C-${fmt()}`;
  log(`\n=== SP-01b 结局② 确认门拒绝  sessionId=${sid} ===`);
  const sse = new SseClient('C', join(OUT, 'sp01ab-C-sse-raw.txt'));
  await sse.open(sid);
  await sleep(800);
  const before = (await get('/api/dev/info')).json.toolCounters;
  await post(CHAT_PATH, { sessionId: sid, message: SENSITIVE_PROMPT });

  const checks = [];
  const req = await sse.waitFor((e) => e.event === 'confirm_request', 45000, 'confirm_request');
  record(checks, 'C1', req.data.name === 'delete_config', `确认门挂起 toolCallId=${req.data.toolCallId}`);

  const reason = '该配置在生产环境仍被引用，禁止删除';
  const res = await post('/api/confirm', {
    sessionId: sid, toolCallId: req.data.toolCallId, approved: false, reason, source: 'http-post',
  });
  log(`  POST /api/confirm(reject) -> ${res.status} ${res.text}`);
  record(checks, 'C2', res.status === 200 && res.json?.accepted === true && res.json?.approved === false, `拒绝被受理 ${res.text}`);

  const decision = await sse.waitFor((e) => e.event === 'confirm_decision', 30000, 'confirm_decision');
  record(checks, 'C3', decision.data.decision === 'reject' && decision.data.reason === reason,
    `确认门结论 reject reason=${decision.data.reason}`);

  const done = await sse.waitFor((e) => e.event === 'done', 90000, 'done');
  const content = done.data.content ?? '';
  record(checks, 'C4', done.data.failed === false, `循环未被中断，正常结束 elapsedMs=${done.data.elapsedMs}`);

  const c = await collect(sid, 'C', sse, checks);
  const exec = c.timeline.find((e) => e.event === 'tool_result' && e.data.name === 'delete_config');
  record(checks, 'C5', exec?.data.ok === false && exec?.data.executed === false,
    `工具未执行并以拒绝语义回填 ${JSON.stringify(exec?.data.result)}`);
  record(checks, 'C6', c.info.toolCounters.delete_config - before.delete_config === 0,
    `后端 delete_config 执行次数 +0（${before.delete_config} -> ${c.info.toolCounters.delete_config}）`);
  record(checks, 'C7', /未执行|没有执行|拒绝|取消/.test(content), `模型如实说明未执行：${content.slice(0, 160)}`);
  return { scenario: 'C', sid, content, checks, timeline: c.timeline, sse };
}

// --------------------------------------------- SP-01b 结局三：超时自动取消
async function scenarioD() {
  const sid = `sp01ab-D-${fmt()}`;
  log(`\n=== SP-01b 结局③ 超时自动取消（不确认，等满超时）  sessionId=${sid} ===`);
  const sse = new SseClient('D', join(OUT, 'sp01ab-D-sse-raw.txt'));
  await sse.open(sid);
  await sleep(800);
  const before = (await get('/api/dev/info')).json.toolCounters;
  await post(CHAT_PATH, { sessionId: sid, message: SENSITIVE_PROMPT });

  const checks = [];
  const req = await sse.waitFor((e) => e.event === 'confirm_request', 45000, 'confirm_request');
  const tPending = Date.now();
  const timeout = req.data.timeoutSeconds;
  record(checks, 'D1', req.data.name === 'delete_config', `确认门挂起 toolCallId=${req.data.toolCallId} timeoutSeconds=${timeout}`);

  const done = await sse.waitFor((e) => e.event === 'done', (timeout + 40) * 1000, 'done');
  const waited = Date.now() - tPending;
  const content = done.data.content ?? '';
  record(checks, 'D2', waited >= timeout * 1000, `挂起到自动放行用时 ${waited}ms（>= ${timeout * 1000}ms）`);

  const c = await collect(sid, 'D', sse, checks);
  const decision = c.timeline.find((e) => e.event === 'confirm_decision');
  record(checks, 'D3', decision?.data.decision === 'timeout' && decision?.data.waitedMs >= timeout * 1000 - 1500,
    `确认门结论 timeout waitedMs=${decision?.data.waitedMs}`);
  const exec = c.timeline.find((e) => e.event === 'tool_result' && e.data.name === 'delete_config');
  record(checks, 'D4', exec?.data.ok === false && exec?.data.executed === false,
    `超时语义为“已取消、未执行”：${JSON.stringify(exec?.data.result)}`);
  record(checks, 'D5', c.info.toolCounters.delete_config - before.delete_config === 0,
    `后端 delete_config 执行次数 +0（${before.delete_config} -> ${c.info.toolCounters.delete_config}）`);
  record(checks, 'D6', done.data.failed === false, `循环未被中断（未抛超时异常），正常结束 elapsedMs=${done.data.elapsedMs}`);

  const windowPings = sse.pings.filter((p) => p.data.pending && String(p.data.pending).startsWith('confirm:'));
  record(checks, 'D7', windowPings.length >= 5,
    `挂起窗口内 SSE 收到 ${windowPings.length} 帧携挂起态心跳（间隔约 2000ms），连接未断`);
  const first = windowPings[0]?.clientTs, last = windowPings[windowPings.length - 1]?.clientTs;
  record(checks, 'D8', first !== undefined && last - first > timeout * 1000 * 0.5,
    `心跳覆盖挂起期的 ${first === undefined ? 'n/a' : last - first}ms（首帧 +${first}ms，末帧 +${last}ms）`);
  record(checks, 'D9', /取消|超时|未执行|没有执行/.test(content), `模型如实说明取消：${content.slice(0, 160)}`);
  return { scenario: 'D', sid, content, checks, timeline: c.timeline, sse };
}

// ---------------------------------------------------------------- main
const summary = { base: BASE, startedAt: new Date().toISOString(), scenarios: [] };
const runAll = WHICH === 'ALL';
const runners = [
  ['A', scenarioA, runAll || WHICH === 'A'],
  ['B', scenarioB, runAll || WHICH === 'B'],
  ['C', scenarioC, runAll || WHICH === 'C'],
  ['D', scenarioD, runAll || WHICH === 'D'],
];
for (const [name, fn, enabled] of runners) {
  if (!enabled) continue;
  try {
    const r = await fn();
    summary.scenarios.push({
      scenario: r.scenario, sessionId: r.sid, content: r.content,
      pass: r.checks.every((c) => c.ok), checks: r.checks,
    });
  } catch (e) {
    log(`  SCENARIO ${name} ERROR: ${e.message}`);
    summary.scenarios.push({ scenario: name, error: e.message, pass: false });
  }
}
summary.finishedAt = new Date().toISOString();
writeFileSync(join(OUT, 'sp01ab-summary.json'), JSON.stringify(summary, null, 2));

log('\n================ 汇总 ================');
for (const s of summary.scenarios) {
  const pass = s.checks ? s.checks.filter((c) => c.ok).length : 0;
  const total = s.checks ? s.checks.length : 0;
  log(`场景 ${s.scenario}: ${s.pass ? 'ALL PASS' : 'HAS FAIL'} (${pass}/${total}) ${s.error ?? ''}`);
  if (s.content) log(`   最终回答：${s.content.replace(/\s+/g, ' ').slice(0, 200)}`);
}
process.exit(0);
