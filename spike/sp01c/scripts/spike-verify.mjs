#!/usr/bin/env node
/**
 * SP-01c 验证驱动脚本。
 *
 * 用 Node 而不是 Git Bash + curl：Git Bash 会把命令行里的中文按 GBK 发送，
 * 造成 "Invalid UTF-8 middle byte"（实测踩坑），Node fetch 恒为 UTF-8。
 *
 * 用法：node spike-verify.mjs <phase> [...args]
 *   info      <baseUrl>
 *   vc1       <baseUrl>                        多轮记忆
 *   vc2       <baseUrl>                        Redis 键结构 + TTL 到期
 *   vc3-seed  <baseUrlA> <sessionId>           多实例：在 A 上跑 2 轮
 *   vc3-cont  <baseUrlB> <sessionId>           多实例：在 B 上同 sessionId 继续
 *   vc4-seed  <baseUrl> <sessionId>            重启恢复：重启前跑 2 轮
 *   vc4-cont  <baseUrl> <sessionId>            重启恢复：重启后继续对话
 *   vc5       <baseUrl>                        7 轮带工具调用
 *   roundtrip <baseUrl> <sessionId>            工具消息往返自检
 *   cleanup   <baseUrl> <sessionId...>         删除实验会话
 *
 * 原始证据写入 $EVIDENCE_DIR 下的 <phase>-raw.txt 与 <phase>*.json。
 *
 * 环境变量：
 *   MEMURAI_CLI   Memurai CLI 可执行文件，默认取 PATH 上的 `memurai-cli`。
 *                 CLI 不在 PATH 时用 `export MEMURAI_CLI=<MEMURAI_HOME>/memurai-cli.exe` 覆盖。
 *   REDIS_HOST   默认 127.0.0.1
 *   REDIS_PORT   默认 6379
 *   EVIDENCE_DIR 证据输出目录，默认 <spike/sp01c>/evidence（与 sp01ab 同级约定一致）
 * 另需在启动实例前 `export DEEPSEEK_API_KEY=***`（本脚本自身不接触该密钥）。
 */
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const EVIDENCE_DIR = process.env.EVIDENCE_DIR
  ? resolve(process.env.EVIDENCE_DIR)
  : resolve(HERE, '../evidence');
const MEMURAI_CLI = process.env.MEMURAI_CLI || 'memurai-cli';
const REDIS_HOST = process.env.REDIS_HOST || '127.0.0.1';
const REDIS_PORT = process.env.REDIS_PORT || '6379';

mkdirSync(EVIDENCE_DIR, { recursive: true });

const lines = [];
function log(text = '') {
  lines.push(text);
  console.log(text);
}
function flush(name) {
  const file = join(EVIDENCE_DIR, `${name}-raw.txt`);
  writeFileSync(file, lines.join('\n') + '\n');
  console.log(`\n[evidence] ${file}`);
}
function dump(name, data) {
  const file = join(EVIDENCE_DIR, `${name}.json`);
  writeFileSync(file, JSON.stringify(data, null, 2) + '\n');
  console.log(`[evidence] ${file}`);
}

/** 直连 memurai-cli，返回命令原文与输出原文。 */
function redis(...args) {
  const command = `memurai-cli ${args.join(' ')}`;
  let output;
  try {
    output = execFileSync(MEMURAI_CLI, ['-h', REDIS_HOST, '-p', REDIS_PORT, ...args], {
      encoding: 'utf8',
      timeout: 15000,
    }).trim();
  } catch (ex) {
    output = `ERROR: ${ex.message}`;
  }
  log(`$ ${command}`);
  log(output);
  log();
  return output;
}

async function post(baseUrl, path, body) {
  const started = Date.now();
  const response = await fetch(`${baseUrl}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  return { status: response.status, text, ms: Date.now() - started };
}

async function get(baseUrl, path) {
  const started = Date.now();
  const response = await fetch(`${baseUrl}${path}`);
  const text = await response.text();
  return { status: response.status, text, ms: Date.now() - started };
}

async function chat(baseUrl, sessionId, message, system) {
  const body = { sessionId, message };
  if (system) body.system = system;
  log(`POST ${baseUrl}/api/chat`);
  log(`  request  : ${JSON.stringify(body)}`);
  const result = await post(baseUrl, '/api/chat', body);
  log(`  HTTP     : ${result.status}  (${result.ms} ms)`);
  log(`  response : ${result.text}`);
  log();
  return result;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const PHASES = {
  async info([baseUrl]) {
    log(`GET ${baseUrl}/api/dev/info`);
    const result = await get(baseUrl, '/api/dev/info');
    log(`HTTP ${result.status}`);
    log(result.text);
  },

  // V-c1 多轮记忆
  async vc1([baseUrl]) {
    const sessionId = 'vc1-s1';
    log('=== V-c1 多轮记忆：第二轮应能引用第一轮内容 ===\n');
    await chat(baseUrl, sessionId, '请记住这个数字：42。只回复“已记住”，不要解释。');
    await chat(baseUrl, sessionId, '我刚才让你记住的数字是几？只回复数字。');
    log('GET history');
    const history = await get(baseUrl, `/api/chat/${sessionId}/history`);
    log(`HTTP ${history.status}`);
    log(JSON.stringify(JSON.parse(history.text), null, 2));
    log();
    redis('TYPE', `chat:mem:${sessionId}`);
    redis('TTL', `chat:mem:${sessionId}`);
    redis('LRANGE', `chat:mem:${sessionId}`, '0', '-1');
    flush('vc1');
  },

  // V-c2 Redis 键结构 + TTL 到期
  async vc2([baseUrl]) {
    log('=== V-c2 Redis 键结构与 TTL 到期 ===\n');
    const sessionId = 'vc1-s1';
    log(`--- 2.1 普通会话键结构（复用 V-c1 会话 ${sessionId}）---\n`);
    redis('TYPE', `chat:mem:${sessionId}`);
    redis('TTL', `chat:mem:${sessionId}`);
    redis('LLEN', `chat:mem:${sessionId}`);
    redis('LRANGE', `chat:mem:${sessionId}`, '0', '-1');
    redis('SMEMBERS', 'chat:mem:__ids__');

    log('--- 2.2 短 TTL 会话：5 秒后键应自动消失（证明"不持久化"）---\n');
    log('POST /api/dev/ttl?seconds=5');
    const ttlSet = await post(baseUrl, '/api/dev/ttl?seconds=5');
    log(`HTTP ${ttlSet.status}  ${ttlSet.text}`);
    log();

    const shortId = 'vc2-ttl-5s';
    await chat(baseUrl, shortId, '短 TTL 会话：只回复“ok”。');

    redis('TYPE', `chat:mem:${shortId}`);
    redis('TTL', `chat:mem:${shortId}`);
    redis('LRANGE', `chat:mem:${shortId}`, '0', '-1');
    redis('EXISTS', `chat:mem:${shortId}`);

    log('等待 8 秒（> TTL 5 秒）...\n');
    await sleep(8000);

    redis('TTL', `chat:mem:${shortId}`);
    redis('EXISTS', `chat:mem:${shortId}`);
    redis('LRANGE', `chat:mem:${shortId}`, '0', '-1');
    redis('SMEMBERS', 'chat:mem:__ids__');
    log('GET /api/chat/vc2-ttl-5s/history（键已消失，应返回空数组）');
    const history = await get(baseUrl, `/api/chat/${shortId}/history`);
    log(`HTTP ${history.status}  ${history.text}`);
    log();

    log('恢复默认 TTL 21600 秒');
    const restored = await post(baseUrl, '/api/dev/ttl?seconds=21600');
    log(`HTTP ${restored.status}  ${restored.text}`);
    log();
    flush('vc2');
  },

  // V-c3 多实例共享：A 上跑 2 轮
  async 'vc3-seed'([baseUrl, sessionId]) {
    log('=== V-c3 多实例共享（步骤 1：实例 A 完成 2 轮）===\n');
    log(`GET ${baseUrl}/api/dev/info`);
    const info = await get(baseUrl, '/api/dev/info');
    log(`HTTP ${info.status}  ${info.text}`);
    log();
    await chat(baseUrl, sessionId, '请记住这个数字：7。只回复“已记住”。');
    await chat(baseUrl, sessionId, '请再记住一个词：紫水晶。只回复“已记住”。');
    redis('TTL', `chat:mem:${sessionId}`);
    redis('LRANGE', `chat:mem:${sessionId}`, '0', '-1');
    flush('vc3-seed');
  },

  // V-c3 多实例共享：B 上继续
  async 'vc3-cont'([baseUrl, sessionId]) {
    log('=== V-c3 多实例共享（步骤 2：实例 B 用同一 sessionId 继续）===\n');
    log(`GET ${baseUrl}/api/dev/info`);
    const info = await get(baseUrl, '/api/dev/info');
    log(`HTTP ${info.status}  ${info.text}`);
    log();
    log(`GET ${baseUrl}/api/chat/${sessionId}/history（B 视角的历史）`);
    const history = await get(baseUrl, `/api/chat/${sessionId}/history`);
    log(`HTTP ${history.status}`);
    log(history.text);
    log();
    await chat(baseUrl, sessionId, '我刚才让你记的数字是几？我让你记的词是什么？用一句话回答。');
    flush('vc3-cont');
  },

  // V-c4 重启恢复：重启前
  async 'vc4-seed'([baseUrl, sessionId]) {
    log('=== V-c4 重启恢复（步骤 1：重启前完成 2 轮）===\n');
    log(`GET ${baseUrl}/api/dev/info`);
    const info = await get(baseUrl, '/api/dev/info');
    log(`HTTP ${info.status}  ${info.text}`);
    log();
    await chat(baseUrl, sessionId, '请记住一个暗号：青铜时代-314。只回复“已记住”。');
    await chat(baseUrl, sessionId, '再记住一个数字：1024。只回复“已记住”。');
    log('GET history（重启前基线）');
    const history = await get(baseUrl, `/api/chat/${sessionId}/history`);
    log(`HTTP ${history.status}`);
    log(history.text);
    log();
    redis('TTL', `chat:mem:${sessionId}`);
    redis('LLEN', `chat:mem:${sessionId}`);
    flush('vc4-seed');
  },

  // V-c4 重启恢复：重启后
  async 'vc4-cont'([baseUrl, sessionId]) {
    log('=== V-c4 重启恢复（步骤 2：同端口重启后核对历史）===\n');
    log(`GET ${baseUrl}/api/dev/info`);
    const info = await get(baseUrl, '/api/dev/info');
    log(`HTTP ${info.status}  ${info.text}`);
    log();
    log('GET history（重启后）');
    const history = await get(baseUrl, `/api/chat/${sessionId}/history`);
    log(`HTTP ${history.status}`);
    log(history.text);
    log();
    redis('TTL', `chat:mem:${sessionId}`);
    await chat(baseUrl, sessionId, '我让你记的暗号和数字分别是什么？用一句话回答。');
    flush('vc4-cont');
  },

  // V-c5 连续 7 轮带工具调用，不得出现 400
  async vc5([baseUrl]) {
    log('=== V-c5 连续 7 轮带工具调用，全程不得出现 400 / 异常 ===\n');
    const sessionId = 'vc5-tools';
    const system = '你是工具调用验证助手。硬性规则：每一轮回答前都必须至少调用一次工具'
      + '（getServerTime 或 calculate），禁止不调用工具就直接回答。回答用中文。';
    log(`system prompt: ${system}\n`);

    const rounds = [
      '第 1 轮：请调用 getServerTime 查看服务器当前时间，然后告诉我结果。',
      '第 2 轮：请调用 calculate 计算 123 加 456，然后告诉我结果。',
      '第 3 轮：请先调用 getServerTime，再调用 calculate 计算 88 乘 3，然后一起告诉我结果。',
      '第 4 轮：请调用 calculate 计算 1000 减 250，然后告诉我结果。',
      '第 5 轮：请调用 calculate 计算 144 除以 12，然后告诉我结果。',
      '第 6 轮：请再次调用 getServerTime，然后告诉我结果。',
      '第 7 轮：请调用 calculate 计算 9 乘 9，并说明本轮是第几轮对话。',
    ];

    const record = [];
    let previousToolCalls = 0;
    for (let i = 0; i < rounds.length; i++) {
      log(`--- 第 ${i + 1} 轮 ---`);
      const started = Date.now();
      const result = await chat(baseUrl, sessionId, rounds[i], system);
      let parsed = null;
      try {
        parsed = JSON.parse(result.text);
      } catch {
        // 保留原始文本
      }
      const stats = await get(baseUrl, '/api/dev/tool-stats');
      const statsJson = JSON.parse(stats.text);
      const delta = statsJson.total - previousToolCalls;
      previousToolCalls = statsJson.total;
      log(`  本轮新增工具调用次数: ${delta}`);
      log();
      record.push({
        round: i + 1,
        httpStatus: result.status,
        elapsedMs: result.ms || Date.now() - started,
        reply: parsed ? parsed.reply : result.text,
        historySize: parsed ? parsed.historySize : null,
        toolCallsThisRound: delta,
        toolCallsTotal: statsJson.total,
        raw: result.text,
      });
    }
    log('GET /api/dev/tool-stats（7 轮累计）');
    const finalStats = await get(baseUrl, '/api/dev/tool-stats');
    log(`HTTP ${finalStats.status}  ${finalStats.text}`);
    log();
    const statuses = record.map((r) => r.httpStatus);
    log(`7 轮 HTTP 状态序列: ${statuses.join(', ')}`);
    log(`是否出现 400: ${statuses.includes(400) ? '是' : '否'}`);
    log(`是否出现非 200: ${statuses.some((s) => s !== 200) ? '是' : '否'}`);
    log(`7 轮新增工具调用总次数: ${record.reduce((sum, r) => sum + r.toolCallsThisRound, 0)}`);
    log();
    dump('vc5-rounds', record);
    redis('LLEN', `chat:mem:${sessionId}`);
    redis('TTL', `chat:mem:${sessionId}`);
    flush('vc5');
  },

  // 补充证据：官方 MessageWindowChatMemory 的窗口裁剪是否会真实作用到 Redis LIST
  async window([baseUrl, sessionId]) {
    log('=== 补充证据：官方 MessageWindowChatMemory 窗口裁剪落到 Redis LIST ===\n');
    const info = await get(baseUrl, '/api/dev/info');
    log(`GET ${baseUrl}/api/dev/info`);
    log(`HTTP ${info.status}  ${info.text}`);
    log();
    for (let i = 1; i <= 3; i++) {
      await chat(baseUrl, sessionId, `第 ${i} 轮：请记住编号 ${i}。只回复“已记住”。`);
      redis('LLEN', `chat:mem:${sessionId}`);
    }
    log('GET history（应只剩窗口内的最后若干条）');
    const history = await get(baseUrl, `/api/chat/${sessionId}/history`);
    log(`HTTP ${history.status}`);
    log(JSON.stringify(JSON.parse(history.text).map((m) => `${m.type}: ${m.text}`), null, 2));
    log();
    flush('window-supplement');
  },

  // 工具消息往返自检
  async roundtrip([baseUrl, sessionId]) {
    log('=== 工具消息往返自检（AssistantMessage.toolCalls / ToolResponseMessage.toolResponses 经 Redis 往返）===\n');
    const result = await post(baseUrl, `/api/dev/roundtrip?sessionId=${encodeURIComponent(sessionId)}`);
    log(`POST ${baseUrl}/api/dev/roundtrip?sessionId=${sessionId}`);
    log(`HTTP ${result.status}`);
    log(JSON.stringify(JSON.parse(result.text), null, 2));
    log();
    redis('TYPE', `chat:mem:${sessionId}`);
    redis('LLEN', `chat:mem:${sessionId}`);
    redis('LRANGE', `chat:mem:${sessionId}`, '0', '-1');
    flush('roundtrip');
  },

  async cleanup([baseUrl, ...sessionIds]) {
    log('=== 清理实验会话 ===\n');
    for (const sessionId of sessionIds) {
      redis('DEL', `chat:mem:${sessionId}`);
    }
    for (const sessionId of sessionIds) {
      redis('EXISTS', `chat:mem:${sessionId}`);
    }
    redis('SMEMBERS', 'chat:mem:__ids__');
    flush('cleanup');
  },
};

const [phase, ...args] = process.argv.slice(2);
if (!phase || !PHASES[phase]) {
  console.error(`usage: node spike-verify.mjs <${Object.keys(PHASES).join('|')}> [...args]`);
  process.exit(2);
}
await PHASES[phase](args);
