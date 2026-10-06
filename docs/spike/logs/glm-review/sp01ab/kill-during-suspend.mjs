// SP-01ab 反例 1：确认门挂起期间杀死应用进程。
// 步骤：发起敏感删除对话 → 等 confirm_request 挂起 → taskkill 应用 → 观察 SSE 断开；
// 重启应用后：会话状态是否还在？挂起流程能否续跑？补交 confirm 会怎样？
const BASE = 'http://127.0.0.1:18301';
const SID = 'glm-kill-' + Date.now();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  const phase = process.argv[2];
  if (phase === 'suspend') {
    // 1) 订阅 SSE
    const ctrl = new AbortController();
    const resp = await fetch(`${BASE}/api/events/${SID}`, { headers: { Accept: 'text/event-stream' }, signal: ctrl.signal });
    const reader = resp.body.getReader();
    const dec = new TextDecoder();
    let buf = '';
    const events = [];
    (async () => {
      try {
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          buf += dec.decode(value, { stream: true });
          let idx;
          while ((idx = buf.indexOf('\n\n')) >= 0) {
            const frame = buf.slice(0, idx); buf = buf.slice(idx + 2);
            const evm = frame.match(/^event:(.+)$/m);
            const dtm = frame.match(/^data:(.+)$/m);
            if (evm && dtm) events.push({ event: evm[1].trim(), data: dtm[1].trim(), atMs: Date.now() });
          }
        }
      } catch (e) { events.push({ event: '__stream_error__', data: String(e), atMs: Date.now() }); }
    })();

    // 2) 发起敏感删除对话（不确认，等挂起）
    const chat = await fetch(BASE + '/api/chat', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId: SID, message: '请删除配置 def-777（这是一次真实的删除操作，按工具规则执行）。' }),
    });
    console.log('POST /api/chat ->', chat.status, await chat.text());

    // 3) 等 confirm_request 出现（最多 40s）
    const t0 = Date.now();
    while (Date.now() - t0 < 40000) {
      await sleep(500);
      const req = events.find((e) => e.event === 'confirm_request');
      if (req) {
        console.log('SUSPENDED confirm_request at +' + (req.atMs - t0) + 'ms:', req.data);
        console.log('KILLING_APP_NOW');
        console.log('SESSION_ID=' + SID);
        // 保持 SSE 打开 30s，观察杀进程瞬间的表现
        await sleep(30000);
        console.log('EVENTS_AFTER_KILL=' + JSON.stringify(events.slice(-6)));
        process.exit(0);
      }
    }
    console.log('NO_CONFIRM_REQUEST events=' + JSON.stringify(events.map((e) => e.event)));
    process.exit(1);
  }

  if (phase === 'probe-after-restart') {
    const sid = process.argv[3];
    // 重启后：查询会话状态
    for (const p of [`/api/events/${sid}/timeline`, `/api/dev/info`]) {
      try {
        const r = await fetch(BASE + p);
        console.log(p, '->', r.status, (await r.text()).slice(0, 300));
      } catch (e) { console.log(p, 'ERR', String(e)); }
    }
    // 补交 confirm（挂起期进程死掉前的决策）
    try {
      const r = await fetch(BASE + '/api/confirm', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: sid, decision: 'approve' }),
      });
      console.log('POST /api/confirm(approve) ->', r.status, (await r.text()).slice(0, 300));
    } catch (e) { console.log('confirm ERR', String(e)); }
    process.exit(0);
  }
}
main();
