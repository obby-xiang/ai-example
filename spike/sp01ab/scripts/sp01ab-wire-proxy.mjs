// 协议取证代理：监听 127.0.0.1:18305，把 Spring AI 的 OpenAI 兼容请求原样转发到 DeepSeek，
// 并把每次请求/响应原文落盘（Authorization 头一律脱敏为 ***）。
//
//   node sp01ab-wire-proxy.mjs <outDir> [listenPort]
//
// 用途：证明“前端工具”确实以 tools 数组下发给模型（而非后端臆造），
// 以及多轮回填时 assistant.tool_calls / role:tool 消息的真实形状。
import { createServer } from 'node:http';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const OUT = process.argv[2] ?? '.';
const PORT = Number(process.argv[3] ?? 18305);
const UPSTREAM = 'https://api.deepseek.com';
mkdirSync(OUT, { recursive: true });

let n = 0;
const redact = (headers) => {
  const out = { ...headers };
  for (const k of Object.keys(out)) {
    if (/authorization|api-key|apikey/i.test(k)) out[k] = '***';
  }
  return out;
};

const server = createServer((req, res) => {
  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', async () => {
    const body = Buffer.concat(chunks);
    const id = String(++n).padStart(2, '0');
    const t0 = Date.now();
    // 只保留端到端头，剔除 hop-by-hop / host（转发的 host 会被上游拒绝）
    const fwd = {};
    for (const [k, v] of Object.entries(req.headers)) {
      if (/^(host|connection|content-length|accept-encoding|transfer-encoding|keep-alive|upgrade|te|expect|proxy-.*)$/i.test(k)) continue;
      fwd[k] = v;
    }
    try {
      const upstream = await fetch(UPSTREAM + req.url, {
        method: req.method,
        headers: fwd,
        body: req.method === 'GET' ? undefined : body,
      });
      const text = await upstream.text();
      writeFileSync(join(OUT, `wire-${id}-request.json`),
        JSON.stringify({ url: UPSTREAM + req.url, headers: redact(req.headers), forwardedHeaders: redact(fwd), body: safeJson(body) }, null, 2));
      writeFileSync(join(OUT, `wire-${id}-response.json`),
        JSON.stringify({ status: upstream.status, elapsedMs: Date.now() - t0, headers: redact(Object.fromEntries(upstream.headers)), body: safeJson(Buffer.from(text)) }, null, 2));
      res.writeHead(upstream.status, { 'content-type': upstream.headers.get('content-type') ?? 'application/json' });
      res.end(text);
    } catch (e) {
      writeFileSync(join(OUT, `wire-${id}-error.txt`), `${e.stack ?? e}\ncause: ${e.cause?.stack ?? e.cause}`);
      res.writeHead(502, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: String(e.message) }));
    }
  });
});

function safeJson(buf) {
  try { return JSON.parse(buf.toString('utf8')); } catch { return buf.toString('utf8'); }
}

server.listen(PORT, '127.0.0.1', () => console.log(`wire proxy listening on ${PORT} -> ${UPSTREAM}, out=${OUT}`));
