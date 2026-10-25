#!/usr/bin/env node
/**
 * G-09…G-12 桩跨用例台账回归（零依赖，node --test 运行）。
 *
 * 背景（CI run 40214177582）：G-11 与 G-12 的提示词除了落点字段外完全相同（都走 `gf-options-from`），
 * 而桩的会话台账（`turns`）只按**首条 user 文本**做指纹，且仅当 `messages.length` 严格回退时才清零。
 * 桩是「每提示词路由一次」：G-11 首次请求占掉 `GF-OPTIONS-FROM` 名额后，续跑（4 条消息）只回文本；
 * 若 G-12 的首请（2 条消息）在 G-11 的续跑**之前**到达，长度没有回退 ⇒ 台账不清零 ⇒ 路由已在
 * `used` 里 ⇒ G-12 退化成 18 字文本收尾。CI 另一次运行顺序相反，所以同一份代码时红时绿。
 * G-09/G-10 共用 `gf-linkage`，同型风险。
 *
 * 本回归不做静态字符串比对：它从**实际的 scripts/verify-e2e.ps1** 里读出 G-09…G-12 的
 * `Ai-FormSuspend` 标记与消息模板，按产品首请形状（2 条消息：system+user，披露 generative_form）
 * 在**同一个真实 stub-upstream 进程**上按 G-09→G-10→G-11→G-12 连续发，中间不插入任何续跑请求
 * —— 也就是失败那次 CI 的到达顺序。旧编排下 G-10/G-12 会拿到文本收尾（RED）；标记各自可辨识后
 * 四次都必须是 generative_form 工具轮（GREEN）。
 *
 * 只读产品/桩事实源，不改它们的导出面；端口取随机空闲端口，测试结束必关桩。
 */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '..', '..');
const PS1 = path.join(REPO, 'scripts', 'verify-e2e.ps1');
const STUB = path.join(HERE, 'stub-upstream.mjs');
const CASES = ['G-09', 'G-10', 'G-11', 'G-12'];
/** 桩文本收尾的固定串（`decide` 的 CLOSING_TEXT）；RED 时用它说明现场。 */
const CLOSING_TEXT = '好的，本轮已完成，请查看上方的结果。';

/** `Ai-FormSuspend` 的提示词模板（形如 `【AIE2E】$marker 请按标记出表单。`）。 */
function readTemplate(src) {
  const m = /message\s*=\s*\("([^"]*\$marker[^"]*)"\)/.exec(src);
  assert.ok(m, 'verify-e2e.ps1 的 Ai-FormSuspend 里读不到 $marker 消息模板（脚本形态已变，需同步本回归）');
  return m[1];
}

/** 取某个 `Add-AiCase '<id>'` 的块（到下一个 Add-AiCase 为止）。 */
function caseBlock(src, id) {
  const start = src.indexOf(`Add-AiCase '${id}'`);
  assert.ok(start >= 0, `verify-e2e.ps1 里找不到 ${id} 的 Add-AiCase`);
  const next = src.indexOf("Add-AiCase '", start + 1);
  return src.slice(start, next < 0 ? src.length : next);
}

/** 该用例传给 Ai-FormSuspend 的标记字面量。 */
function readMarker(src, id) {
  const m = /Ai-FormSuspend\s+\$sid\s+'([^']*)'/.exec(caseBlock(src, id));
  assert.ok(m, `${id} 的块里没有 Ai-FormSuspend $sid '<marker>'`);
  return m[1];
}

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.once('error', reject);
    srv.listen(0, '127.0.0.1', () => {
      const { port } = srv.address();
      srv.close(() => resolve(port));
    });
  });
}

function startStub(port, dir) {
  const child = spawn(process.execPath, [STUB, '--port', String(port), '--dir', dir], {
    cwd: REPO,
    stdio: ['ignore', 'pipe', 'pipe']
  });
  let output = '';
  const ready = new Promise((resolve, reject) => {
    child.stdout.on('data', (buf) => {
      output += buf.toString();
      if (output.includes('已就绪')) resolve();
    });
    child.stderr.on('data', (buf) => {
      output += buf.toString();
    });
    child.once('exit', (code) => reject(new Error(`stub 提前退出 code=${code}\n${output}`)));
  });
  return { child, ready, output: () => output };
}

/** 产品首请的 2 条消息形状（system + user），并披露 generative_form。 */
function firstRequest(prompt) {
  return {
    model: 'ci-stub',
    stream: true,
    messages: [
      { role: 'system', content: '你是配置管理助手。' },
      { role: 'user', content: prompt }
    ],
    tools: [
      {
        type: 'function',
        function: {
          name: 'generative_form',
          description: '生成式表单',
          parameters: { type: 'object', properties: {} }
        }
      },
      {
        type: 'function',
        function: {
          name: 'list_tasks',
          description: '列出任务',
          parameters: { type: 'object', properties: {} }
        }
      }
    ]
  };
}

/** 从 SSE 帧还原桩的判定：出现 tool_calls ⇒ 工具轮，否则把所有 content 拼成文本。 */
function decisionOf(sse) {
  let text = '';
  let tool = null;
  for (const line of sse.split('\n')) {
    if (!line.startsWith('data: ')) continue;
    const payload = line.slice('data: '.length).trim();
    if (payload === '[DONE]') continue;
    let chunk;
    try {
      chunk = JSON.parse(payload);
    } catch {
      continue;
    }
    const delta = chunk?.choices?.[0]?.delta ?? {};
    if (typeof delta.content === 'string') text += delta.content;
    for (const call of delta.tool_calls ?? []) {
      if (call?.function?.name) tool = call.function.name;
    }
  }
  return { kind: tool ? 'tool_call' : 'text', tool, text };
}

test('G-09→G-12 首请连续到达（无续跑插入）：四个标记各自独立命中 generative_form', { timeout: 30_000 }, async (t) => {
  const src = fs.readFileSync(PS1, 'utf8');
  const template = readTemplate(src);
  const prompts = CASES.map((id) => {
    const marker = readMarker(src, id);
    return { id, marker, prompt: template.replace('$marker', marker) };
  });

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'stub-order-'));
  const port = await freePort();
  const stub = startStub(port, dir);
  t.after(() => {
    stub.child.kill('SIGKILL');
    fs.rmSync(dir, { recursive: true, force: true });
  });
  await stub.ready;

  const seen = [];
  for (const p of prompts) {
    const res = await fetch(`http://127.0.0.1:${port}/v1/chat/completions`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(firstRequest(p.prompt))
    });
    assert.equal(res.status, 200, `${p.id} 请求 HTTP ${res.status}（桩日志：\n${stub.output()}）`);
    seen.push({ ...p, decision: decisionOf(await res.text()) });
  }

  // 桩真的收到了四份首请（每条 2 条消息），没有续跑混进来 —— 顺序即失败 CI 的到达顺序。
  const records = fs.readdirSync(dir).filter((n) => /^req-\d+\.json$/.test(n)).sort();
  assert.equal(records.length, CASES.length, `桩收到 ${records.length} 次请求，期望 ${CASES.length}（只发首请）`);
  for (const name of records) {
    const body = JSON.parse(fs.readFileSync(path.join(dir, name), 'utf8'));
    assert.equal(body.messages.length, 2, `${name} 不是 2 条消息的首请形状，复现前提不成立`);
  }

  const wrong = seen.filter((s) => s.decision.kind !== 'tool_call' || s.decision.tool !== 'generative_form');
  assert.deepEqual(
    wrong.map((s) => ({
      case: s.id,
      marker: s.marker,
      got: s.decision.tool ?? 'text',
      text: s.decision.text,
      hitClosingText: s.decision.text === CLOSING_TEXT
    })),
    [],
    `这些用例的标记没有独立命中 generative_form —— 首请文本相同 ⇒ 共用桩的会话台账，\n`
      + `前一用例占掉路由名额后本用例退化为文本（本次 2 条消息未触发台账清零）。\n`
      + `桩日志：\n${stub.output()}`
  );
});
