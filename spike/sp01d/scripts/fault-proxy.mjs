// SP-01d 故障注入代理：AI 服务 base-url 指向本代理，代理再转发到真实上游（默认 https://api.deepseek.com）。
// 故障模式（对 /v1/chat/completions 生效，按 SSE 事件边界转发，不切碎半个事件）：
//   none                透传
//   reset               收到请求立即 RST（连接被重置，首包前）
//   stall               不回任何字节（连响应头都不发），stallMs 后 RST —— 制造"首包静默"
//   stall-after-content 先透传，直到"已转发 contentEvents 个带正文的 delta"后完全停止发送（保持连接），
//                       stallMs 后 RST —— 制造"已产出内容后的事件间静默"
//   fin-after-content   同上但达到阈值后立即 res.end() —— 制造"已产出内容后的静默截断（TCP 正常 FIN）"
//   truncate            先透传 relayEvents 个事件，然后 RST —— 制造"硬截断"
//   truncate-fin        先透传 relayEvents 个事件，然后正常 end() —— 制造"静默截断（TCP 正常关闭）"
//
// 控制面：
//   POST /__fault  {mode, relayEvents, contentEvents, stallMs, onlyRequestIndex, resetCounter}
//   GET  /__wire   返回逐请求报文台账
//   POST /__reset  清空台账与请求计数
//   POST /__kill   强制断开当前所有连接（解除 stall 实验）
//
// 运行：node scripts/fault-proxy.mjs --port 18304 --upstream https://api.deepseek.com --evidence evidence
import http from 'node:http';
import https from 'node:https';
import fs from 'node:fs';
import path from 'node:path';

const argv = process.argv.slice(2);
const arg = (name, def) => {
  const i = argv.indexOf('--' + name);
  return i >= 0 ? argv[i + 1] : def;
};

const PORT = Number(arg('port', '18304'));
const UPSTREAM = arg('upstream', 'https://api.deepseek.com');
const EVIDENCE = arg('evidence', 'evidence');
const upstreamUrl = new URL(UPSTREAM);

let fault = { mode: 'none', relayEvents: 0, contentEvents: 1, stallMs: 8000, onlyRequestIndex: 0 };
let requestIndex = 0;
let wireSeq = 0;
const wire = [];

fs.mkdirSync(EVIDENCE, { recursive: true });

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');

  if (url.pathname === '/__fault') {
    collect(req).then((body) => {
      const cfg = body ? JSON.parse(body) : {};
      fault = {
        mode: cfg.mode ?? 'none',
        relayEvents: Number(cfg.relayEvents ?? 0),
        contentEvents: Number(cfg.contentEvents ?? 1),
        stallMs: Number(cfg.stallMs ?? 8000),
        onlyRequestIndex: Number(cfg.onlyRequestIndex ?? 0)
      };
      if (cfg.resetCounter) {
        requestIndex = 0;
      }
      json(res, 200, { fault, requestIndex });
    });
    return;
  }
  if (url.pathname === '/__wire') {
    json(res, 200, wire);
    return;
  }
  if (url.pathname === '/__reset') {
    wire.length = 0;
    requestIndex = 0;
    json(res, 200, { ok: true });
    return;
  }
  if (url.pathname === '/__kill') {
    // 强制断开当前所有打开的连接（含处于 stall 状态的），用于解除"无限挂起"实验。
    // 先回响应、再断开，避免把本请求自己的连接一起掐掉。
    json(res, 200, { killed: true });
    setTimeout(() => server.closeAllConnections(), 250);
    return;
  }

  requestIndex += 1;
  const seq = ++wireSeq;
  const idx = requestIndex;
  const entry = {
    seq,
    requestIndex: idx,
    tsMs: Date.now(),
    method: req.method,
    path: req.url,
    mode: fault.mode,
    applied: shouldApply(idx),
    status: null,
    relayedBytes: 0,
    chunkCount: 0,
    outcome: null,
    headerBytes: 0
  };
  wire.push(entry);
  console.log(`[proxy] #${idx} seq=${seq} ${req.method} ${req.url} fault=${fault.mode} applied=${entry.applied}`);

  const finish = (outcome) => {
    entry.outcome = outcome;
    entry.endTsMs = Date.now();
    entry.durationMs = entry.endTsMs - entry.tsMs;
    fs.writeFileSync(path.join(EVIDENCE, `wire-${String(seq).padStart(2, '0')}.json`),
      JSON.stringify(entry, null, 2));
    console.log(`[proxy] #${idx} seq=${seq} -> ${outcome} status=${entry.status} relayed=${entry.relayedBytes}B dur=${entry.durationMs}ms`);
  };

  if (entry.applied && fault.mode === 'reset') {
    req.socket.destroy();
    finish('CLIENT_SOCKET_RESET_BEFORE_FORWARD');
    return;
  }
  if (entry.applied && fault.mode === 'stall') {
    setTimeout(() => {
      try { req.socket.destroy(); } catch { /* ignore */ }
      finish('STALLED_THEN_SOCKET_RESET');
    }, fault.stallMs);
    return;
  }

  const chunk = [];
  req.on('data', (d) => chunk.push(d));
  req.on('end', () => {
    const body = Buffer.concat(chunk);
    entry.requestBytes = body.length;
    const u = new URL(req.url, UPSTREAM);
    const proxyReq = https.request({
      hostname: u.hostname,
      port: u.port || 443,
      path: u.pathname + u.search,
      method: req.method,
      headers: { ...req.headers, host: u.hostname },
      agent: false
    }, (proxyRes) => {
      entry.status = proxyRes.statusCode;
      entry.upstreamHeadersAtMs = Date.now();

      if (!entry.applied) {
        res.writeHead(proxyRes.statusCode, proxyRes.headers);
        proxyRes.pipe(res);
        proxyRes.on('end', () => finish('PASSTHROUGH_COMPLETE'));
        proxyRes.on('error', (e) => { try { res.destroy(); } catch { /* */ } finish('UPSTREAM_ERROR:' + e.message); });
        return;
      }

      // 故障模式：手工转发 SSE（按事件边界转发，不切碎半个事件）
      res.writeHead(proxyRes.statusCode, proxyRes.headers);
      let relayedEvents = 0;
      let relayedBytes = 0;
      let contentSeen = 0;
      let buffer = Buffer.alloc(0);
      let stopped = false;
      let silent = false;
      let stallTimer = null;

      const stop = () => {
        if (stopped) return;
        stopped = true;
        if (stallTimer) clearTimeout(stallTimer);
        try { proxyRes.destroy(); } catch { /* */ }
        if (fault.mode === 'truncate-fin' || fault.mode === 'fin-after-content') {
          try { res.end(); } catch { /* */ }
          finish('TRUNCATED_CLEAN_FIN');
        } else {
          try { res.socket.destroy(); } catch { /* */ }
          finish(fault.mode === 'stall-after-content' ? 'STALLED_AFTER_CONTENT_THEN_SOCKET_RESET'
            : 'TRUNCATED_SOCKET_RESET');
        }
      };

      // 触发条件：stall-after-content / fin-after-content 看"已转发几个带正文的 delta"，其余看"已转发几个事件"
      const budgetReached = () =>
        (fault.mode === 'stall-after-content' || fault.mode === 'fin-after-content')
          ? contentSeen >= fault.contentEvents
          : relayedEvents >= fault.relayEvents;

      const onBudgetReached = () => {
        if (fault.mode === 'stall-after-content') {
          // 停止转发但保持连接（真正的"静默"），stallMs 后再 RST
          silent = true;
          entry.stalledAtMs = Date.now();
          console.log(`[proxy] #${idx} seq=${seq} going silent after ${relayedBytes}B / ${contentSeen} content events`);
          stallTimer = setTimeout(() => stop(), fault.stallMs);
        } else {
          entry.relayedBytes = relayedBytes;
          entry.contentEvents = contentSeen;
          stop();
        }
      };

      proxyRes.on('data', (d) => {
        if (stopped || silent) return;
        entry.chunkCount += 1;
        buffer = Buffer.concat([buffer, d]);
        for (;;) {
          const i = buffer.indexOf('\n\n');
          if (i < 0) break;
          const event = buffer.subarray(0, i + 2);
          buffer = buffer.subarray(i + 2);
          if (budgetReached()) { onBudgetReached(); return; }
          try { res.write(event); } catch { /* */ }
          relayedEvents += 1;
          relayedBytes += event.length;
          if (/"content":"[^"]/.test(event.toString('utf8'))) contentSeen += 1;
          entry.relayedBytes = relayedBytes;
          entry.relayedEvents = relayedEvents;
          entry.contentEvents = contentSeen;
          if (budgetReached()) { onBudgetReached(); return; }
        }
      });
      proxyRes.on('error', () => {
        if (!stopped) stop();
      });
      proxyRes.on('end', () => {
        // silent = 已按故障配置停止转发但保持连接：此时绝不能把上游的正常结束透传给客户端，
        // 否则"静默"退化成"静默截断"（这是 vd1-*-mid 第一轮失效的原因）。
        if (!stopped && !silent) {
          try { res.end(); } catch { /* */ }
          stopped = true;
          if (stallTimer) clearTimeout(stallTimer);
          finish('UPSTREAM_ENDED');
        }
      });
    });
    proxyReq.on('error', (e) => {
      try { res.destroy(); } catch { /* */ }
      finish('PROXY_UPSTREAM_ERROR:' + e.message);
    });
    proxyReq.end(body);
  });
});

function shouldApply(idx) {
  if (fault.mode === 'none') return false;
  if (fault.onlyRequestIndex > 0) return idx === fault.onlyRequestIndex;
  return true;
}

function collect(req) {
  return new Promise((resolve) => {
    const c = [];
    req.on('data', (d) => c.push(d));
    req.on('end', () => resolve(Buffer.concat(c).toString('utf8')));
  });
}

function json(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'content-type': 'application/json; charset=utf-8', 'content-length': Buffer.byteLength(body) });
  res.end(body);
}

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[proxy] listening on http://127.0.0.1:${PORT} -> ${UPSTREAM} (evidence=${EVIDENCE})`);
});
