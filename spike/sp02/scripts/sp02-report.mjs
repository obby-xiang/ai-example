// 把 evidence/ 里的原始证据整理成决策卡可直接引用的附件（仍在本机绝对路径态，随后由 sp02-sanitize.mjs 脱敏）。
//
//   node sp02-report.mjs <evidenceDir> <reportDir>
import { mkdirSync, readdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const EVIDENCE = process.argv[2] ?? 'evidence';
const OUT = process.argv[3] ?? join(EVIDENCE, 'report');
mkdirSync(OUT, { recursive: true });
const WIRE = join(EVIDENCE, 'wire');
const SCENARIOS = ['E1', 'E2', 'E3', 'E4', 'E5', 'E6'];

const readJson = (p) => JSON.parse(readFileSync(p, 'utf8'));
const write = (name, value) => writeFileSync(join(OUT, name), typeof value === 'string' ? value : JSON.stringify(value, null, 2));

/** 解析取证代理记下的 SSE 响应体：拼出正文、finish_reason、tool_calls（分块累加）。 */
function parseWireResponse(body) {
  if (typeof body !== 'string' || !body.includes('data:')) {
    return { json: body };
  }
  let content = '';
  let reasoning = '';
  let finishReason = null;
  const toolCalls = new Map();
  let done = false;
  for (const chunk of body.split('\n\n')) {
    const line = chunk.trim();
    if (!line.startsWith('data:')) continue;
    const payload = line.slice(5).trim();
    if (payload === '[DONE]') { done = true; continue; }
    let json;
    try { json = JSON.parse(payload); } catch { continue; }
    const choice = json.choices?.[0];
    if (!choice) continue;
    const delta = choice.delta ?? {};
    if (delta.content) content += delta.content;
    if (delta.reasoning_content) reasoning += delta.reasoning_content;
    for (const tc of delta.tool_calls ?? []) {
      const key = tc.index ?? 0;
      const acc = toolCalls.get(key) ?? { id: null, name: null, arguments: '' };
      if (tc.id) acc.id = tc.id;
      if (tc.function?.name) acc.name = tc.function.name;
      if (tc.function?.arguments) acc.arguments += tc.function.arguments;
      toolCalls.set(key, acc);
    }
    if (choice.finish_reason) finishReason = choice.finish_reason;
  }
  return { content, reasoningChars: reasoning.length, finishReason, done, toolCalls: [...toolCalls.values()] };
}

function wireSummary(files) {
  return files.map((name) => {
    const base = name.replace(/-request\.json$|-response\.json$/, '');
    const reqPath = join(WIRE, `${base}-request.json`);
    const resPath = join(WIRE, `${base}-response.json`);
    const out = { file: base };
    if (existsSync(reqPath)) {
      const req = readJson(reqPath);
      out.request = {
        model: req.body?.model,
        stream: req.body?.stream,
        tools: (req.body?.tools ?? []).map((t) => t.function?.name),
        messages: (req.body?.messages ?? []).map((m) => ({
          role: m.role,
          contentChars: (m.content ?? '').length,
          contentHead: (m.content ?? '').slice(0, 120),
          toolCalls: (m.tool_calls ?? []).map((t) => ({ id: t.id, name: t.function?.name, arguments: t.function?.arguments })),
          toolCallId: m.tool_call_id ?? null,
        })),
      };
    }
    if (existsSync(resPath)) {
      const res = readJson(resPath);
      out.response = { status: res.status, elapsedMs: res.elapsedMs, ...parseWireResponse(res.body) };
    }
    return out;
  });
}

const allChecks = [];
for (const scenario of SCENARIOS) {
  const dir = join(EVIDENCE, scenario);
  if (!existsSync(dir)) continue;
  const checksPath = join(dir, 'checks.json');
  if (existsSync(checksPath)) {
    const checks = readJson(checksPath);
    allChecks.push({ scenario, passed: checks.filter((c) => c.pass).length, total: checks.length, checks });
    write(`${scenario}-checks.json`, checks);
  }
  const summaryPath = join(dir, 'summary.json');
  if (existsSync(summaryPath)) write(`${scenario}-summary.json`, readJson(summaryPath));

  const finalPath = join(dir, 'final-state.json');
  if (existsSync(finalPath)) {
    const final = readJson(finalPath);
    write(`${scenario}-final-snapshot.json`, {
      snapshot: final.snapshot,
      pending: final.pending,
      ledger: final.ledger,
      ledgerCounts: final.ledgerCounts,
      keys: final.keys,
      instanceAtRead: final.instanceId,
    });
    write(`${scenario}-final-answer.txt`, final.snapshot?.finalText ?? '');
    write(`${scenario}-key-events.json`,
      (final.persistedEvents ?? []).filter((e) => e.event !== 'delta'));
    write(`${scenario}-rebuild-preview.json`, final.rebuildPreview ?? {});
  }
  for (const name of ['resume-request.json', 'resume-rebuilt-prompt.json', 'suspend-persisted.json', 'suspend-1.json',
    'redis-pending-after-kill.txt', 'redis-pending-after-approve.txt', 'redis-ledger-after-kill.txt',
    'frontend-tool-request.json', 'confirm-request.json', 'confirm-approve.json', 'frontend-tool-result-post.json',
    'state-before-resume.json', 'instance1-info.json', 'instance2-info.json', 'resume-again.json',
    'model-round-1.json', 'resumed-suspend-events.json', 'run-suspended.json']) {
    const p = join(dir, name);
    if (existsSync(p)) write(`${scenario}-${name}`, readFileSync(p, 'utf8'));
  }
  for (const logName of readdirSync(dir).filter((f) => f.startsWith('app-') && f.endsWith('.log'))) {
    write(`${scenario}-${logName}`, readFileSync(join(dir, logName), 'utf8'));
  }
  for (const sse of readdirSync(dir).filter((f) => f.includes('sse-raw') && f.endsWith('.txt'))) {
    write(`${scenario}-${sse}`, readFileSync(join(dir, sse), 'utf8'));
  }
  const plan = existsSync(join(dir, 'wire-files-plan.json')) ? readJson(join(dir, 'wire-files-plan.json')) : null;
  const resumeFiles = existsSync(join(dir, 'resume-wire-files.json')) ? readJson(join(dir, 'resume-wire-files.json')) : [];
  write(`${scenario}-wire-index.json`, { plan, resumeFiles });
}

// 全部实验用到的 wire 文件：逐条整理请求/响应形状
const allWire = readdirSync(WIRE).filter((f) => f.endsWith('-request.json')).map((f) => f.replace('-request.json', '')).sort();
const summaries = {};
for (const base of allWire) {
  const s = wireSummary([`${base}-request.json`])[0];
  summaries[base] = s;
}
write('wire-all-summary.json', summaries);
write('wire-status-counts.json', allWire.reduce((acc, base) => {
  const p = join(WIRE, `${base}-response.json`);
  if (!existsSync(p)) return acc;
  const status = readJson(p).status;
  acc[status] = (acc[status] ?? 0) + 1;
  return acc;
}, {}));
write('all-checks.json', {
  scenarios: allChecks.map((s) => ({ scenario: s.scenario, passed: s.passed, total: s.total })),
  grandTotal: allChecks.reduce((a, s) => a + s.total, 0),
  grandPassed: allChecks.reduce((a, s) => a + s.passed, 0),
  failures: allChecks.flatMap((s) => s.checks.filter((c) => !c.pass).map((c) => `${s.scenario}:${c.id} ${c.name}`)),
});
console.log(`report written to ${OUT}`);
