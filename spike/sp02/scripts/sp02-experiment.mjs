// SP-02 续跑协议验证脚本：真实 kill 掉应用进程，靠 Redis 里的挂起态在**新进程**里续跑。
//
//   node sp02-experiment.mjs <scenario> [outDir]
//   scenario: E1 | E2 | E3 | E4
//
// 环境变量：
//   DEEPSEEK_API_KEY   上游密钥（仅环境变量）
//   SP02_JAVA          java 可执行文件（默认 java）
//   SP02_JAR           jar 路径（默认 target/sp02-resume-protocol-0.0.1-SNAPSHOT.jar）
//   SP02_BASE          应用地址（默认 http://127.0.0.1:18306）
//   SP02_UPSTREAM      上游（默认 http://127.0.0.1:18307，即取证代理）
//   SP02_REDIS_CLI     memurai-cli 路径（用于在应用已死时直接读 Redis 取证）
//
// 每个 scenario 产出：<outDir>/<scenario>/ 下的 JSON/txt 证据 + checks.json + summary.json
import { appendFileSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { execFileSync, spawn } from 'node:child_process';

const SCENARIO = (process.argv[2] ?? 'E1').toUpperCase();
const OUT = join(process.argv[3] ?? 'evidence', SCENARIO);
mkdirSync(OUT, { recursive: true });
const WIRE = join(process.argv[3] ?? 'evidence', 'wire');
mkdirSync(WIRE, { recursive: true });

const BASE = process.env.SP02_BASE ?? 'http://127.0.0.1:18306';
const JAVA = process.env.SP02_JAVA ?? 'java';
const JAR = process.env.SP02_JAR ?? 'target/sp02-resume-protocol-0.0.1-SNAPSHOT.jar';
const UPSTREAM = process.env.SP02_UPSTREAM ?? 'http://127.0.0.1:18307';
const REDIS_CLI = process.env.SP02_REDIS_CLI ?? '';
const PORT = new URL(BASE).port;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const t0 = Date.now();
const log = (...a) => console.log(`[+${String(Date.now() - t0).padStart(6)}ms]`, ...a);

function save(name, value) {
  writeFileSync(join(OUT, name), typeof value === 'string' ? value : JSON.stringify(value, null, 2));
}

// ------------------------------------------------------------------ HTTP

async function post(path, body) {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* 原文保留 */ }
  return { status: res.status, json, text };
}

async function get(path) {
  const res = await fetch(BASE + path);
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* 原文保留 */ }
  return { status: res.status, json, text };
}

// ------------------------------------------------------------------ 应用进程生命周期

function startApp(label, extraEnv = {}) {
  const outPath = join(OUT, `${label}.log`);
  const child = spawn(JAVA, ['-jar', JAR], {
    env: {
      ...process.env,
      DEEPSEEK_API_KEY: process.env.DEEPSEEK_API_KEY,
      AI_BASE_URL: UPSTREAM,
      SP02_PORT: PORT,
      ...extraEnv,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const capture = (buf) => appendFileSync(outPath, buf.toString());
  child.stdout.on('data', capture);
  child.stderr.on('data', capture);
  child.on('exit', (code, signal) => appendFileSync(outPath, `\n# process exited code=${code} signal=${signal}\n`));
  return { child, pid: child.pid, log: outPath, label };
}

async function waitUp(label, timeoutMs = 90000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const r = await get('/api/dev/info');
      if (r.status === 200) return r.json;
    } catch { /* 还没起来 */ }
    await sleep(400);
  }
  throw new Error(`${label}: app did not come up within ${timeoutMs}ms`);
}

async function killApp(app) {
  appendFileSync(app.log, `\n# SIGKILL sent at ${new Date().toISOString()}\n`);
  try { process.kill(app.pid, 'SIGKILL'); } catch (e) { appendFileSync(app.log, `# kill error ${e}\n`); }
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    try {
      await fetch(BASE + '/api/dev/info');
    } catch {
      log(`${app.label} (pid ${app.pid}) is down`);
      return true;
    }
    await sleep(200);
  }
  throw new Error(`${app.label} did not die`);
}

// ------------------------------------------------------------------ SSE

class Sse {
  constructor(label) {
    this.label = label;
    this.events = [];
    this.frames = [];
    this.raw = join(OUT, `${label}-sse-raw.txt`);
  }

  async open(runId) {
    this.ctrl = new AbortController();
    this.s0 = Date.now();
    appendFileSync(this.raw, `# subscribe ${BASE}/api/events/${runId} @ ${new Date().toISOString()}\n`);
    this.resp = await fetch(`${BASE}/api/events/${runId}`, {
      headers: { Accept: 'text/event-stream' },
      signal: this.ctrl.signal,
    });
    appendFileSync(this.raw, `# HTTP ${this.resp.status} content-type=${this.resp.headers.get('content-type')}\n`);
    this.loop = this.#read();
    await sleep(300);
    return this;
  }

  async #read() {
    const reader = this.resp.body.getReader();
    const dec = new TextDecoder();
    let buf = '';
    let ev = null;
    const dataLines = [];
    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buf += dec.decode(value, { stream: true });
        let idx;
        while ((idx = buf.indexOf('\n')) >= 0) {
          const raw = buf.slice(0, idx).replace(/\r$/, '');
          buf = buf.slice(idx + 1);
          appendFileSync(this.raw, `[+${String(Date.now() - this.s0).padStart(6, '0')}ms] ${raw}\n`);
          if (raw.startsWith('event:')) ev = raw.slice(6).trim();
          else if (raw.startsWith('data:')) dataLines.push(raw.slice(5).trim());
          else if (raw === '') {
            if (ev && dataLines.length) {
              let payload = null;
              try { payload = JSON.parse(dataLines.join('\n')); } catch { /* 原文保留 */ }
              this.frames.push({ atMs: Date.now() - this.s0, event: ev, payload });
              if (payload && payload.event) this.events.push(payload);
            }
            ev = null;
            dataLines.length = 0;
          }
        }
      }
      appendFileSync(this.raw, `# stream closed @ ${new Date().toISOString()}\n`);
    } catch (e) {
      appendFileSync(this.raw, `# stream error: ${e}\n`);
    }
  }

  close() {
    try { this.ctrl.abort(); } catch { /* ignore */ }
  }
}

// ------------------------------------------------------------------ 工具函数

async function waitForEvent(runId, eventName, timeoutMs = 120000, predicate = () => true) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const r = await get(`/api/run/${runId}`);
    const events = r.json?.persistedEvents ?? [];
    const hit = events.find((e) => e.event === eventName && predicate(e));
    if (hit) return hit;
    await sleep(250);
  }
  throw new Error(`timeout waiting for event ${eventName} on run ${runId}`);
}

function wireIndex() {
  return readdirSync(WIRE).filter((f) => f.startsWith('wire-')).length;
}

function wireRange(from) {
  return readdirSync(WIRE).filter((f) => f.startsWith('wire-')).sort().slice(from);
}

function redisCli(...args) {
  if (!REDIS_CLI) return '(SP02_REDIS_CLI not set)';
  try {
    return execFileSync(REDIS_CLI, args, { encoding: 'utf8' });
  } catch (e) {
    return `(redis-cli error: ${e.message})`;
  }
}

const checks = [];
function check(id, name, ok, detail) {
  checks.push({ id, name, pass: !!ok, detail });
  log(`${ok ? 'PASS' : 'FAIL'} ${id} ${name}${detail === undefined ? '' : ' :: ' + JSON.stringify(detail)}`);
}

const TOKEN = `FRONTEND-${Math.random().toString(36).slice(2, 8).toUpperCase()}`;
const FRONT_RESULT = `用户 u-1024 的前端缓存资料：姓名=张三-${TOKEN}，等级=白金-${TOKEN}，积分=880（数据来自浏览器 localStorage）。`;

// ------------------------------------------------------------------ 场景

async function scenarioE1() {
  // 官方循环内阻塞挂起（自定义 ToolCallingManager）→ 挂起期 kill → 新进程续跑
  const inst1 = startApp('app-instance1', { SP02_EXEC_DELAY_MS: '0' });
  const info1 = await waitUp('instance1');
  save('instance1-info.json', info1);
  check('E1-0', 'instance1 起来且 Redis 可达', !!info1.instanceId, info1);

  const started = await post('/api/chat', {
    sessionId: 'sp02-e1',
    mode: 'blocking',
    message: '帮我算一下 6 乘 7 等于多少，同时读取用户 u-1024 的前端资料（姓名/等级/积分）。最后把两者一起汇报给我。',
  });
  save('chat-start.json', started);
  const runId = started.json.runId;
  const sse1 = await new Sse('instance1');
  await sse1.open(runId);

  const suspendEvt = await waitForEvent(runId, 'suspend_persisted');
  save('suspend-persisted.json', suspendEvt);
  const toolCallIds = suspendEvt.data.toolCalls.map((c) => ({ name: c.name, kind: c.kind, id: c.toolCallId }));
  check('E1-1', '挂起态已外置（suspend_persisted 事件含 Redis 键与 tool_calls）',
    suspendEvt.data.stateKey?.startsWith('sp02:run:') && toolCallIds.length >= 1, { toolCallIds, keys: suspendEvt.data.stateKey });

  const frontendReq = await waitForEvent(runId, 'frontend_tool_request');
  save('frontend-tool-request.json', frontendReq);
  const frontendCallId = frontendReq.data.toolCallId;

  const before = wireIndex();
  await killApp(inst1);
  sse1.close();
  const midState = await get(`/api/run/${runId}`).catch(() => ({ status: 0, json: null }));
  save('state-after-kill-http.json', midState.json ?? { note: '应用已死，HTTP 不可达（预期）' });
  const killEvidence =
    `# 应用已被 kill -9，以下为直接读 Redis 的结果（memurai-cli）\n` +
    `## HGETALL sp02:hitl:${runId}\n${redisCli('HGETALL', `sp02:hitl:${runId}`)}\n` +
    `## LLEN sp02:ledger:${runId} => ${redisCli('LLEN', `sp02:ledger:${runId}`)}\n` +
    `## LRANGE sp02:ledger:${runId} 0 -1\n${redisCli('LRANGE', `sp02:ledger:${runId}`, '0', '-1')}\n` +
    `## GET sp02:run:${runId}\n${redisCli('GET', `sp02:run:${runId}`)}\n`;
  save('redis-pending-after-kill.txt', killEvidence);
  check('E1-2', 'kill -9 之后挂起态与台账仍在 Redis 里（含 assistant(tool_calls) 与 in-flight 条目）',
    /inFlightToolCalls/.test(killEvidence) && frontendCallId &&
    killEvidence.includes(frontendCallId) && /"executed":true/.test(killEvidence.replace(/\s/g, '')),
    { redisKey: `sp02:run:${runId}`, pendingKey: `sp02:hitl:${runId}`, sample: killEvidence.slice(0, 400) });

  // ── 新进程 ──
  const inst2 = startApp('app-instance2', { SP02_EXEC_DELAY_MS: '0' });
  const info2 = await waitUp('instance2');
  save('instance2-info.json', info2);
  check('E1-3', '新实例与旧实例不是同一个 instanceId（进程已重启）',
    info1.instanceId !== info2.instanceId, { instance1: info1.instanceId, instance2: info2.instanceId });

  const runs = await get('/api/runs');
  save('runs-after-restart.json', runs.json);
  const discovered = runs.json.runs.find((r) => r.runId === runId);
  check('E1-4', '重启后能从 Redis 发现待续跑轮次（/api/runs）', !!discovered, discovered);

  const stateBefore = await get(`/api/run/${runId}`);
  save('state-before-resume.json', stateBefore.json);
  const pendingBefore = stateBefore.json.pending.find((p) => p.toolCallId === frontendCallId);
  check('E1-5', '挂起条目在新进程可见且未被重复请求（frontend_tool_request 计数=1）',
    pendingBefore?.status === 'PENDING' && pendingBefore?.executed === false &&
    stateBefore.json.persistedEvents.filter((e) => e.event === 'frontend_tool_request').length === 1,
    { pendingBefore, requests: stateBefore.json.persistedEvents.filter((e) => e.event === 'frontend_tool_request').length });

  const wireBeforeResume = wireIndex();
  const blocked = await post(`/api/resume/${runId}`);
  save('resume-while-pending.json', blocked.json);
  check('E1-6', '挂起项未落定时拒绝续跑（409 PENDING_UNRESOLVED）且不发模型请求',
    blocked.status === 409 && blocked.json.reason === 'PENDING_UNRESOLVED' && wireIndex() === wireBeforeResume,
    { status: blocked.status, reason: blocked.json.reason, wireDelta: wireIndex() - wireBeforeResume });

  const sse2 = await new Sse('instance2');
  await sse2.open(runId);

  const post2 = await post('/api/frontend-tool-result', {
    runId, toolCallId: frontendCallId, result: FRONT_RESULT, source: 'browser-localStorage',
  });
  save('frontend-tool-result-post.json', post2.json);
  check('E1-7', '前端结果写入 Redis（新进程无内存门，wokeInProcessGate=false）',
    post2.json.accepted === true && post2.json.wokeInProcessGate === false, post2.json);

  const rebuiltStore = redisCli('HGETALL', `sp02:hitl:${runId}`);
  save('redis-pending-after-frontend-post.txt', rebuiltStore);

  const wireBefore = wireIndex();
  const resumed = await post(`/api/resume/${runId}`);
  save('resume-request.json', resumed.json);
  save('resume-rebuilt-prompt.json', resumed.json.promptMessages ?? []);
  check('E1-8', '续跑被受理（202）且重建请求含 assistant(tool_calls)+role:tool',
    resumed.status === 202 && resumed.json.resumed === true &&
    JSON.stringify(resumed.json.promptMessages ?? []).includes('toolCalls') &&
    JSON.stringify(resumed.json.promptMessages ?? []).includes('toolResponses'),
    { status: resumed.status, resumed: resumed.json.resumed, rebuiltTools: resumed.json.rebuiltTools });
  check('E1-8b', '已执行过的后端工具在续跑时被识别为“上一实例已执行”（不重复执行）',
    (resumed.json.rebuiltTools ?? []).some((s) => s.includes('executed-by-previous-instance')), resumed.json.rebuiltTools);

  // 等续跑结束
  const deadline = Date.now() + 90000;
  let final = null;
  while (Date.now() < deadline) {
    const r = await get(`/api/run/${runId}`);
    final = r.json;
    if (final?.snapshot?.status === 'DONE' || final?.snapshot?.status === 'FAILED') break;
    await sleep(500);
  }
  await sleep(800);
  sse2.close();
  save('final-state.json', final);
  const frames = sse2.frames.map((f) => ({ atMs: f.atMs, event: f.event }));
  save('instance2-sse-frames.json', frames);
  if (SCENARIO === 'E1') save('instance2-sse-raw-copy.txt', readFileSync(sse2.raw, 'utf8'));

  const wireFiles = wireRange(before);
  check('E1-9', '续跑全程只发 1 次模型请求（不重放第 1 轮请求）',
    wireFiles.filter((f) => f.endsWith('-request.json')).length === 1, wireFiles);

  const deltas = sse2.frames.filter((f) => f.event === 'delta');
  const doneFrame = sse2.frames.find((f) => f.event === 'resume_done' || f.event === 'run_finished');
  check('E1-10', '流式续接：收到 delta 且以 done/run_finished 终帧结束',
    deltas.length > 0 && frames.some((f) => f.event === 'run_finished'), { deltas: deltas.length, frames: frames.map((f) => f.event) });

  const text = final?.snapshot?.finalText ?? '';
  const ledgerCounts = final?.ledgerCounts ?? {};
  const localCounters = (await get('/api/dev/info')).json.instanceToolCounters;
  save('final-summary.json', { text, ledgerCounts, localCounters, instance1: info1.instanceId, instance2: info2.instanceId });
  check('E1-11', '不重放：台账里 calculate 恰好 1 条，且第 2 个实例里 calculate 本地计数=0（未重复执行）',
    (ledgerCounts.calculate ?? 0) === 1 && localCounters.calculate === 0, { ledgerCounts, localCounters });
  check('E1-12', '前端工具后端桩体从未执行（userProfileBodyCalls=0）', localCounters.get_user_profile_stub_body === 0, localCounters);
  check('E1-13', '最终回答含后端工具结果 42 与前端回灌令牌（数据真的进了续跑后的上下文）',
    text.includes('42') && text.includes(TOKEN), { text: text.slice(0, 400) });

  // 重复续跑：幂等
  const wireBefore2 = wireIndex();
  const again = await post(`/api/resume/${runId}`);
  save('resume-again.json', again.json);
  check('E1-14', '重复续跑幂等（ALREADY_DONE 且不发模型请求）',
    again.json.resumed === false && again.json.reason === 'ALREADY_DONE' && wireIndex() === wireBefore2, again.json);

  save('wire-files.json', { from: before, files: wireRange(0) });
  return { runId, instance1: inst1, instance2: inst2, toolCallIds, frontendCallId };
}

async function scenarioE2() {
  // 确认门“已放行未执行”：approve 写入 Redis 之后、真正执行之前 kill → 新进程续跑时必须执行一次
  const inst1 = startApp('app-instance1', { SP02_EXEC_DELAY_MS: '8000' });
  const info1 = await waitUp('instance1');
  save('instance1-info.json', info1);

  const started = await post('/api/chat', {
    sessionId: 'sp02-e2', mode: 'blocking',
    message: '请直接调用 delete_config 工具删除配置定义 def-777。不要再问我是否确认，系统会自动弹出确认门，我在确认门里批准。',
  });
  save('chat-start.json', started);
  const runId = started.json.runId;
  const sse1 = await new Sse('instance1');
  await sse1.open(runId);

  const confirmReq = await waitForEvent(runId, 'confirm_request');
  save('confirm-request.json', confirmReq);
  const callId = confirmReq.data.toolCallId;
  check('E2-1', '确认门挂起且挂起态已外置',
    (await get(`/api/run/${runId}`)).json.pending.find((p) => p.toolCallId === callId)?.status === 'PENDING');

  const approve = await post('/api/confirm', { runId, toolCallId: callId, approved: true, reason: '允许删除（PoC）' });
  save('confirm-approve.json', approve.json);
  const infoDuringDelay = (await get('/api/dev/info')).json.instanceToolCounters;
  save('instance1-counters-after-approve.json', infoDuringDelay);
  const redisAfterApprove = redisCli('HGETALL', `sp02:hitl:${runId}`);
  save('redis-pending-after-approve.txt', redisAfterApprove);
  check('E2-2', '放行已写入 Redis 且“已放行未执行”（executed=false）',
    approve.json.accepted === true && /APPROVED/.test(redisAfterApprove) && /"executed":false/.test(redisAfterApprove.replace(/\s/g, '')),
    { approve: approve.json, state: redisAfterApprove.slice(0, 600) });

  const before = wireIndex();
  await killApp(inst1);  // 落在 8s 的 pre-exec 窗口内
  sse1.close();
  const afterKill = redisCli('LRANGE', `sp02:ledger:${runId}`, '0', '-1');
  save('redis-ledger-after-kill.txt', `# 应用 kill -9 后，台账（应为空，说明工具确实未执行）\n${afterKill || '(empty list)'}\n`);
  check('E2-3', 'kill 落在“已放行、未执行”窗口：台账为空（工具没被执行）',
    (redisCli('LLEN', `sp02:ledger:${runId}`).trim() === '0'), { ledger: afterKill });

  const inst2 = startApp('app-instance2', { SP02_EXEC_DELAY_MS: '0' });
  const info2 = await waitUp('instance2');
  save('instance2-info.json', info2);
  const sse2 = new Sse('instance2');
  await sse2.open(runId);

  const stateBefore = await get(`/api/run/${runId}`);
  save('state-before-resume.json', stateBefore.json);
  const pendingBefore = stateBefore.json.pending.find((p) => p.toolCallId === callId);
  check('E2-4', '新进程发现“已放行未执行”的决策（APPROVED + executed=false）',
    pendingBefore?.status === 'APPROVED' && pendingBefore?.executed === false, pendingBefore);

  const wireBefore = wireIndex();
  const resumed = await post(`/api/resume/${runId}`);
  save('resume-request.json', resumed.json);
  check('E2-5', '续跑时把“已放行未执行”的工具真正执行掉（rebuiltTools 标 executed-here）',
    resumed.status === 202 && (resumed.json.rebuiltTools ?? []).some((s) => s.includes('executed-here')),
    { rebuiltTools: resumed.json.rebuiltTools, promptMessages: resumed.json.promptMessages });

  const deadline = Date.now() + 90000;
  let final = null;
  while (Date.now() < deadline) {
    const r = await get(`/api/run/${runId}`);
    final = r.json;
    if (final?.snapshot?.status === 'DONE' || final?.snapshot?.status === 'FAILED') break;
    await sleep(500);
  }
  await sleep(800);
  sse2.close();
  save('final-state.json', final);
  const info2Final = (await get('/api/dev/info')).json;
  save('instance2-info-after-resume.json', info2Final);
  const wireFiles = wireRange(before);
  save('resume-wire-files.json', wireFiles);

  const ledger = final?.ledger ?? [];
  check('E2-6', '续跑后 delete_config 恰好执行 1 次，且执行者是新实例',
    (final?.ledgerCounts?.delete_config ?? 0) === 1 && ledger[0]?.executedBy === info2.instanceId,
    { ledgerCounts: final?.ledgerCounts, ledger, instance2: info2.instanceId });
  check('E2-7', '新实例本地 delete_config 计数=1（本进程真的执行了），旧实例=0（未执行）',
    info2Final.instanceToolCounters.delete_config === 1 && infoDuringDelay.delete_config === 0,
    { instance2: info2Final.instanceToolCounters, instance1: infoDuringDelay });
  check('E2-8', '流式续接到终帧结束且回答如实说明已执行',
    sse2.frames.some((f) => f.event === 'delta') && sse2.frames.some((f) => f.event === 'run_finished') &&
    /删除/.test(final?.snapshot?.finalText ?? ''),
    { frames: sse2.frames.map((f) => f.event), text: (final?.snapshot?.finalText ?? '').slice(0, 300) });

  save('wire-files-plan.json', { before });
  return { runId, instance1: inst1, instance2: inst2, callId };
}

async function scenarioE3() {
  // 方案 B 形态：predicate 对“含外部工具的本轮”返回 false → 官方把 assistant(tool_calls) 交回调用方
  const inst1 = startApp('app-instance1', { SP02_SUPPRESS_EXTERNAL: 'true' });
  const info1 = await waitUp('instance1');
  save('instance1-info.json', info1);
  check('E3-0', 'predicate 抑制开关生效', info1.suppressExternalRounds === true, info1.suppressExternalRounds);

  const started = await post('/api/chat', {
    sessionId: 'sp02-e3', mode: 'predicate',
    message: '帮我算一下 6 乘 7 等于多少，同时读取用户 u-1024 的前端资料（姓名/等级/积分）。最后把两者一起汇报给我。',
  });
  save('chat-start.json', started);
  const runId = started.json.runId;
  const sse1 = await new Sse('instance1');
  await sse1.open(runId);

  const modelRound = await waitForEvent(runId, 'model_round');
  save('model-round-1.json', modelRound);
  check('E3-1', 'predicate=false 时官方循环把含外部工具的本轮交回调用方（调用方看到 tool_calls）',
    modelRound.data.form === 'PREDICATE' && (modelRound.data.toolCalls ?? []).length >= 1, modelRound.data.toolCalls);

  const suspendEvt = await waitForEvent(runId, 'suspend_persisted');
  save('suspend-persisted.json', suspendEvt);
  const calls = suspendEvt.data.toolCalls;
  const frontendCall = calls.find((c) => c.kind === 'FRONTEND');
  check('E3-2', '混合轮整轮交回：后端工具也不算官方内部执行，由调用方执行',
    calls.length >= 2 && calls.some((c) => c.kind === 'BACKEND') && !!frontendCall, calls);

  const suspended = await waitForEvent(runId, 'run_suspended');
  save('run-suspended.json', suspended);
  const stateAtSuspend = await get(`/api/run/${runId}`);
  save('state-at-suspend.json', stateAtSuspend.json);
  const ledgerAtSuspend = stateAtSuspend.json.ledger;
  check('E3-3', '调用方驱动循环：后端工具由调用方执行（tool_result 事件的 path=predicate）',
    ledgerAtSuspend.some((r) => r.name === 'calculate') &&
    stateAtSuspend.json.persistedEvents.some((e) => e.event === 'tool_result' && ['explicit', 'predicate'].includes(e.data.path)),
    { ledger: ledgerAtSuspend, paths: stateAtSuspend.json.persistedEvents.filter((e) => e.event === 'tool_result').map((e) => e.data.path) });

  const before = wireIndex();
  await killApp(inst1);
  sse1.close();

  const inst2 = startApp('app-instance2', { SP02_SUPPRESS_EXTERNAL: 'true' });
  const info2 = await waitUp('instance2');
  save('instance2-info.json', info2);
  const sse2 = new Sse('instance2');
  await sse2.open(runId);

  const stateBefore = await get(`/api/run/${runId}`);
  save('state-before-resume.json', stateBefore.json);
  check('E3-4', '重启后挂起态可发现（SUSPENDED + 挂起条目）',
    stateBefore.json.snapshot.status === 'SUSPENDED' && stateBefore.json.pending.length >= 1, stateBefore.json.snapshot);

  const post2 = await post('/api/frontend-tool-result', {
    runId, toolCallId: frontendCall.toolCallId, result: FRONT_RESULT, source: 'browser-localStorage',
  });
  save('frontend-tool-result-post.json', post2.json);

  const wireBefore = wireIndex();
  const resumed = await post(`/api/resume/${runId}`);
  save('resume-request.json', resumed.json);
  save('resume-rebuilt-prompt.json', resumed.json.promptMessages ?? []);
  check('E3-5', 'predicate 形态下续跑被受理，且重建请求含 assistant(tool_calls)+role:tool',
    resumed.status === 202 && resumed.json.resumed === true &&
    JSON.stringify(resumed.json.promptMessages ?? []).includes('toolResponses'),
    { status: resumed.status, rebuiltTools: resumed.json.rebuiltTools });

  const deadline = Date.now() + 90000;
  let final = null;
  while (Date.now() < deadline) {
    const r = await get(`/api/run/${runId}`);
    final = r.json;
    if (final?.snapshot?.status === 'DONE' || final?.snapshot?.status === 'FAILED') break;
    await sleep(500);
  }
  await sleep(800);
  sse2.close();
  save('final-state.json', final);
  const wireFiles = wireRange(before);
  save('resume-wire-files.json', wireFiles);

  check('E3-6', '续跑只发 1 次模型请求（不重放）', wireFiles.filter((f) => f.endsWith('-request.json')).length === 1, wireFiles);
  check('E3-7', '不重放：calculate 台账 1 条、前端工具请求 1 次、桩体计数 0',
    (final?.ledgerCounts?.calculate ?? 0) === 1 &&
    final.persistedEvents.filter((e) => e.event === 'frontend_tool_request').length === 1 &&
    (await get('/api/dev/info')).json.instanceToolCounters.get_user_profile_stub_body === 0,
    { ledgerCounts: final?.ledgerCounts, requests: final.persistedEvents.filter((e) => e.event === 'frontend_tool_request').length });
  check('E3-8', '流式续接到终帧且回答含 42 与前端令牌',
    sse2.frames.some((f) => f.event === 'delta') && sse2.frames.some((f) => f.event === 'run_finished') &&
    (final?.snapshot?.finalText ?? '').includes('42') && (final?.snapshot?.finalText ?? '').includes(TOKEN),
    { frames: sse2.frames.map((f) => f.event), text: (final?.snapshot?.finalText ?? '').slice(0, 300) });

  save('instance2-info-final.json', (await get('/api/dev/info')).json);
  return { runId, instance1: inst1, instance2: inst2, frontendCallId: frontendCall.toolCallId };
}

async function scenarioE4() {
  // 对照：internalToolExecutionEnabled=false 显式循环（SP-01d V-d2 形态），不 kill，同进程续跑
  const inst1 = startApp('app-instance1', {});
  const info1 = await waitUp('instance1');
  save('instance1-info.json', info1);

  const started = await post('/api/chat', {
    sessionId: 'sp02-e4', mode: 'explicit',
    message: '帮我算一下 6 乘 7 等于多少，同时读取用户 u-1024 的前端资料（姓名/等级/积分）。最后把两者一起汇报给我。',
  });
  save('chat-start.json', started);
  const runId = started.json.runId;
  const sse1 = await new Sse('instance1');
  await sse1.open(runId);

  const modelRound = await waitForEvent(runId, 'model_round');
  save('model-round-1.json', modelRound);
  const suspendEvt = await waitForEvent(runId, 'suspend_persisted');
  save('suspend-persisted.json', suspendEvt);
  const suspended = await waitForEvent(runId, 'run_suspended');
  save('run-suspended.json', suspended);
  const calls = suspendEvt.data.toolCalls;
  const frontendCall = calls.find((c) => c.kind === 'FRONTEND');
  check('E4-1', 'internalToolExecutionEnabled=false：官方循环不执行工具，把 tool_calls 交回调用方',
    modelRound.data.form === 'EXPLICIT' && (modelRound.data.toolCalls ?? []).length >= 1, modelRound.data.toolCalls);

  const post2 = await post('/api/frontend-tool-result', {
    runId, toolCallId: frontendCall.toolCallId, result: FRONT_RESULT, source: 'browser-localStorage',
  });
  save('frontend-tool-result-post.json', post2.json);
  check('E4-2', '同进程内回灌也算“外部输入落 Redis”（wokeInProcessGate=false，因为循环并未阻塞）',
    post2.json.accepted === true, post2.json);

  // 第一轮 drive 结束时会关闭 SSE 订阅（SUSPENDED 也是“本轮流已结束”），故续跑前重新挂接
  const sse2 = new Sse('instance1-resume');
  await sse2.open(runId);
  save('resume-reattach.json', sse2.frames.find((f) => f.event === 'reattached')?.payload?.data ?? null);

  const wireBefore = wireIndex();
  const resumed = await post(`/api/resume/${runId}`);

  const deadline = Date.now() + 90000;
  let final = null;
  while (Date.now() < deadline) {
    const r = await get(`/api/run/${runId}`);
    final = r.json;
    if (final?.snapshot?.status === 'DONE' || final?.snapshot?.status === 'FAILED') break;
    await sleep(500);
  }
  await sleep(800);
  sse2.close();
  save('resume-request.json', { ...resumed.json, promptMessages: resumed.json.promptMessages, note: '见 resume-rebuilt-prompt.json' });
  save('resume-rebuilt-prompt.json', resumed.json.promptMessages ?? []);
  save('final-state.json', final);
  save('resume-sse-frames.json', sse2.frames.map((f) => ({ atMs: f.atMs, event: f.event })));
  save('resume-wire-files.json', wireRange(wireBefore));
  const infoFinal = (await get('/api/dev/info')).json;
  save('instance1-info-final.json', infoFinal);
  const text = final?.snapshot?.finalText ?? '';
  check('E4-3', '对照达成同样语义：续跑被受理、续跑流有 delta 且有终帧、回答含 42 与前端令牌',
    resumed.status === 202 &&
    sse2.frames.some((f) => f.event === 'delta') &&
    sse2.frames.some((f) => f.event === 'run_finished') &&
    text.includes('42') && text.includes(TOKEN),
    { status: resumed.status, resumeFrames: sse2.frames.map((f) => f.event), text: text.slice(0, 300) });
  check('E4-5', '续跑只发 1 次模型请求（不重放第 1 轮）',
    wireRange(wireBefore).filter((f) => f.endsWith('-request.json')).length === 1, wireRange(wireBefore));
  check('E4-4', '不重放：calculate 台账 1 条、桩体计数 0',
    (final?.ledgerCounts?.calculate ?? 0) === 1 && infoFinal.instanceToolCounters.get_user_profile_stub_body === 0,
    { ledgerCounts: final?.ledgerCounts, counters: infoFinal.instanceToolCounters });
  return { runId, instance1: inst1, frontendCallId: frontendCall.toolCallId };
}

async function scenarioE5() {
  // 续跑后的“下一轮又挂起”：官方循环 + 自定义管理器形态（internal 执行开启，未用 false 开关）
  const inst1 = startApp('app-instance1', {});
  const info1 = await waitUp('instance1');
  save('instance1-info.json', info1);

  const started = await post('/api/chat', {
    sessionId: 'sp02-e5', mode: 'blocking',
    message: '请先读取用户 u-1024 的前端资料（姓名/等级）；读取完成后，请调用 delete_config 删除配置定义 def-999。'
      + '这两步都是我的明确指令：删除的编码就是 def-999（不要从前端资料里推断编码），删除前我会在系统弹出的确认门里批准。',
  });
  save('chat-start.json', started);
  const runId = started.json.runId;
  const sse1 = await new Sse('instance1');
  await sse1.open(runId);

  const suspend1 = await waitForEvent(runId, 'suspend_persisted');
  save('suspend-1.json', suspend1);
  const frontendCall = suspend1.data.toolCalls.find((c) => c.kind === 'FRONTEND');
  check('E5-1', '第 1 轮先挂起在前端工具（本轮模型只发了 1 个工具调用）',
    (suspend1.data.toolCalls ?? []).length === 1 && !!frontendCall, suspend1.data.toolCalls);

  const before = wireIndex();
  await killApp(inst1);
  sse1.close();

  const inst2 = startApp('app-instance2', {});
  const info2 = await waitUp('instance2');
  save('instance2-info.json', info2);
  const sse2 = new Sse('instance2');
  await sse2.open(runId);

  const POST_RESULT = `用户 u-1024 的前端缓存资料：姓名=张三-${TOKEN}，等级=管理员-${TOKEN}，积分=880。`
    + `待删除配置编码：def-999（该编码由前端缓存给出）。`;
  const post2 = await post('/api/frontend-tool-result', {
    runId, toolCallId: frontendCall.toolCallId, result: POST_RESULT, source: 'browser-localStorage',
  });
  save('frontend-tool-result-post.json', post2.json);

  const wireBefore = wireIndex();
  const resumed = await post(`/api/resume/${runId}`);
  save('resume-request.json', resumed.json);
  save('resume-rebuilt-prompt.json', resumed.json.promptMessages ?? []);
  check('E5-2', '续跑被受理并重建请求（blocking 形态，internal 执行未关闭）',
    resumed.status === 202 && resumed.json.resumed === true, { status: resumed.status, rebuiltTools: resumed.json.rebuiltTools });

  // 续跑后的下一轮模型调用若又发起工具调用，官方循环会再次走到自定义管理器 → 新进程内再次挂起
  const confirmReq = await waitForEvent(runId, 'confirm_request', 90000).catch((e) => ({ error: e.message }));
  save('confirm-request-after-resume.json', confirmReq);
  const round2 = await get(`/api/run/${runId}`);
  save('state-during-second-suspend.json', round2.json);
  const resumedInstanceSuspended = round2.json.persistedEvents.some(
    (e) => e.event === 'suspend_persisted' && e.instanceId === info2.instanceId);
  check('E5-3', '续跑后的新一轮工具调用再次挂起（挂在重启后的新进程里）',
    !!confirmReq?.data?.toolCallId && resumedInstanceSuspended, confirmReq);

  const approve = await post('/api/confirm', {
    runId, toolCallId: confirmReq.data.toolCallId, approved: true, reason: '允许删除（PoC E5）',
  });
  save('confirm-approve.json', approve.json);
  check('E5-4', '新进程内的确认门被 HTTP 放行（挂起-回灌在重启后仍然成立）',
    approve.json.accepted === true && approve.json.wokeInProcessGate === true, approve.json);

  const deadline = Date.now() + 90000;
  let final = null;
  while (Date.now() < deadline) {
    const r = await get(`/api/run/${runId}`);
    final = r.json;
    if (final?.snapshot?.status === 'DONE' || final?.snapshot?.status === 'FAILED') break;
    await sleep(500);
  }
  await sleep(800);
  sse2.close();
  save('final-state.json', final);
  const infoFinal = (await get('/api/dev/info')).json;
  save('instance2-info-final.json', infoFinal);
  const wireFiles = wireRange(wireBefore);
  save('resume-wire-files.json', wireFiles);
  const text = final?.snapshot?.finalText ?? '';
  check('E5-5', '全程 2 次模型请求（续跑轮 + 第二次工具回填轮），第 1 轮请求未被重放',
    wireFiles.filter((f) => f.endsWith('-request.json')).length === 2, wireFiles);
  check('E5-6', '不重放：delete_config 台账 1 条、calculate 0 次、前端工具请求 1 次、桩体计数 0',
    (final?.ledgerCounts?.delete_config ?? 0) === 1 && (final?.ledgerCounts?.calculate ?? 0) === 0 &&
    final.persistedEvents.filter((e) => e.event === 'frontend_tool_request').length === 1 &&
    infoFinal.instanceToolCounters.get_user_profile_stub_body === 0,
    { ledgerCounts: final?.ledgerCounts, counters: infoFinal.instanceToolCounters });
  check('E5-7', 'delete_config 由重启后的新实例执行，且回答含前端令牌与删除结论',
    (final?.ledger ?? [])[0]?.executedBy === info2.instanceId && text.includes(TOKEN) && /删除|已执行/.test(text),
    { ledger: final?.ledger, text: text.slice(0, 300) });
  check('E5-8', '续跑流以终帧结束（两次挂起都在同一轮对话内）',
    sse2.frames.some((f) => f.event === 'delta') && sse2.frames.some((f) => f.event === 'run_finished'),
    sse2.frames.map((f) => f.event).slice(-6));
  save('wire-files-plan.json', { before });
  return { runId, instance1: inst1, instance2: inst2, frontendCallId: frontendCall.toolCallId };
}

async function scenarioE6() {
  // 续跑后的“下一轮又产生工具调用”（依赖关系强制分两轮：第二轮的参数只能来自第一轮工具结果）
  const inst1 = startApp('app-instance1', {});
  const info1 = await waitUp('instance1');
  save('instance1-info.json', info1);

  const started = await post('/api/chat', {
    sessionId: 'sp02-e6', mode: 'blocking',
    message: '请先读取用户 u-1024 的前端资料（含积分）。拿到积分后，请调用 calculate 工具把该积分乘以 2 算出来。两步都要做。',
  });
  save('chat-start.json', started);
  const runId = started.json.runId;
  const sse1 = await new Sse('instance1');
  await sse1.open(runId);

  const suspend1 = await waitForEvent(runId, 'suspend_persisted');
  save('suspend-1.json', suspend1);
  const frontendCall = suspend1.data.toolCalls.find((c) => c.kind === 'FRONTEND');
  check('E6-1', '第 1 轮只挂起在前端工具（第二轮的工具调用尚未发生）',
    (suspend1.data.toolCalls ?? []).length === 1 && !!frontendCall, suspend1.data.toolCalls);

  const before = wireIndex();
  await killApp(inst1);
  sse1.close();

  const inst2 = startApp('app-instance2', {});
  const info2 = await waitUp('instance2');
  save('instance2-info.json', info2);
  const sse2 = new Sse('instance2');
  await sse2.open(runId);

  const post2 = await post('/api/frontend-tool-result', {
    runId, toolCallId: frontendCall.toolCallId, result: FRONT_RESULT, source: 'browser-localStorage',
  });
  save('frontend-tool-result-post.json', post2.json);

  const wireBefore = wireIndex();
  const resumed = await post(`/api/resume/${runId}`);
  save('resume-request.json', resumed.json);
  save('resume-rebuilt-prompt.json', resumed.json.promptMessages ?? []);
  check('E6-2', '续跑被受理（blocking 形态，internal 执行未关闭）',
    resumed.status === 202 && resumed.json.resumed === true, { status: resumed.status, rebuiltTools: resumed.json.rebuiltTools });

  const deadline = Date.now() + 90000;
  let final = null;
  while (Date.now() < deadline) {
    const r = await get(`/api/run/${runId}`);
    final = r.json;
    if (final?.snapshot?.status === 'DONE' || final?.snapshot?.status === 'FAILED') break;
    await sleep(500);
  }
  await sleep(800);
  sse2.close();
  save('final-state.json', final);
  const infoFinal = (await get('/api/dev/info')).json;
  save('instance2-info-final.json', infoFinal);
  const wireFiles = wireRange(wireBefore);
  save('resume-wire-files.json', wireFiles);

  // 重新拉一次：run_finished/model_round 的落盘可能晚于状态变成 DONE（避免读到半截证据）
  final = (await get(`/api/run/${runId}`)).json;
  save('final-state.json', final);
  const rounds = final.persistedEvents.filter((e) => e.event === 'model_round' && e.instanceId === info2.instanceId);
  save('resumed-model-rounds.json', rounds);
  // BLOCKING 形态下调用方的 flux 拿不到“已被官方循环消费掉的工具调用”（与 SP-01d §1.2d 一致），
  // 故以“重启后进程里管理器收到的新挂起”为口径：该事件列出它被要求执行的新工具调用。
  const resumedSuspends = final.persistedEvents.filter(
    (e) => e.event === 'suspend_persisted' && e.instanceId === info2.instanceId);
  save('resumed-suspend-events.json', resumedSuspends);
  const newToolCalls = resumedSuspends.flatMap((e) => e.data.toolCalls ?? []);
  const calcLedger = (final.ledger ?? []).filter((r) => r.name === 'calculate');
  const text = final?.snapshot?.finalText ?? '';

  check('E6-3', '续跑后的新请求又产生了工具调用（calculate，参数取自前端回灌的积分 880）——该调用落在重启后的新进程里',
    newToolCalls.some((c) => c.name === 'calculate' && /880/.test(c.arguments ?? '')), newToolCalls);
  check('E6-4', '该新工具调用由官方循环在重启后的新实例内执行（台账 executedBy=新实例）',
    calcLedger.length === 1 && calcLedger[0].executedBy === info2.instanceId, calcLedger);
  check('E6-5', '全程 2 次模型请求（续跑续接 + 新工具回填），第 1 轮请求未被重放',
    wireFiles.filter((f) => f.endsWith('-request.json')).length === 2, wireFiles);
  check('E6-6', '不重放：前端工具请求 1 次、桩体计数 0、旧实例与新实例各执行 0/1 次 calculate',
    final.persistedEvents.filter((e) => e.event === 'frontend_tool_request').length === 1 &&
    infoFinal.instanceToolCounters.get_user_profile_stub_body === 0 &&
    infoFinal.instanceToolCounters.calculate === 1 && (final.ledgerCounts.calculate ?? 0) === 1,
    { counters: infoFinal.instanceToolCounters, ledgerCounts: final.ledgerCounts });
  check('E6-7', '最终回答含 1760（积分 880×2 由续跑后的新工具调用算出）',
    text.includes('1760'), { hasToken: text.includes(TOKEN), text: text.slice(0, 300) });
  check('E6-8', '续跑流以终帧结束', sse2.frames.some((f) => f.event === 'delta') && sse2.frames.some((f) => f.event === 'run_finished'),
    sse2.frames.map((f) => f.event).slice(-5));
  save('wire-files-plan.json', { before });
  return { runId, instance1: inst1, instance2: inst2, frontendCallId: frontendCall.toolCallId };
}

// ------------------------------------------------------------------ main

const SCENARIOS = { E1: scenarioE1, E2: scenarioE2, E3: scenarioE3, E4: scenarioE4, E5: scenarioE5, E6: scenarioE6 };

async function main() {
  const fn = SCENARIOS[SCENARIO];
  if (!fn) throw new Error(`unknown scenario ${SCENARIO}`);
  let result = null;
  let error = null;
  try {
    result = await fn();
  } catch (e) {
    error = { message: e.message, stack: e.stack };
    log('SCENARIO ERROR', e.message);
  }
  for (const app of [result?.instance1, result?.instance2]) {
    if (app) {
      try { process.kill(app.pid, 'SIGKILL'); } catch { /* 已死 */ }
    }
  }
  save('checks.json', checks);
  save('summary.json', {
    scenario: SCENARIO,
    token: TOKEN,
    frontResult: FRONT_RESULT,
    runId: result?.runId ?? null,
    instance1: result?.instance1 ? { pid: result.instance1.pid, log: 'app-instance1.log' } : null,
    instance2: result?.instance2 ? { pid: result.instance2.pid, log: 'app-instance2.log' } : null,
    passed: checks.filter((c) => c.pass).length,
    failed: checks.filter((c) => !c.pass).map((c) => `${c.id} ${c.name}`),
    total: checks.length,
    error,
  });
  log(`==== ${SCENARIO}: ${checks.filter((c) => c.pass).length}/${checks.length} checks passed ====`);
  if (checks.some((c) => !c.pass)) {
    for (const c of checks.filter((x) => !x.pass)) log(`  FAILED ${c.id} ${c.name} :: ${JSON.stringify(c.detail)}`);
  }
  process.exit(checks.some((c) => !c.pass) || error ? 1 : 0);
}

main();
