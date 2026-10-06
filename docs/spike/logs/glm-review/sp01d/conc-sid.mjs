// SP-01d 反例 B：同一 sessionId 并发两轮 SSE（攻击卡 §6.5 声称的"会话状态按 sessionId 持有、并发互相污染"）
const BASE = 'http://127.0.0.1:18303';
const SID = 'glm-conc-sid';

async function run(message, tag) {
  const res = await fetch(BASE + '/api/chat', {
    method: 'POST', headers: { 'content-type': 'application/json', accept: 'text/event-stream' },
    body: JSON.stringify({ sessionId: SID, message, strategy: 'guarded' }),
  });
  const events = [];
  let buf = '';
  const dec = new TextDecoder();
  for await (const chunk of res.body) {
    buf += dec.decode(chunk, { stream: true });
    let i;
    while ((i = buf.indexOf('\n\n')) >= 0) {
      const f = buf.slice(0, i); buf = buf.slice(i + 2);
      const m = f.match(/^event:(.+)$/m);
      if (m) events.push(m[1].trim());
    }
  }
  return { tag, http: res.status, events };
}

const [a, b] = await Promise.all([
  run('用一句话介绍你自己。', 'A'),
  run('用两句话介绍你自己。', 'B'),
]);
const state = await fetch(`${BASE}/api/chat/${SID}/state`).then((r) => r.json());
console.log(JSON.stringify({ runA: a, runB: b, finalState: { terminal: state.terminal, cancelled: state.cancelled, content: (state.content || '').slice(0, 60), attempts: state.attempts, runId: state.runId } }, null, 1));
