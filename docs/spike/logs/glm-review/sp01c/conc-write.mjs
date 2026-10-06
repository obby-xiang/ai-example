// SP-01c 反例实验 1：同 sessionId 并发写（攻击卡 §5.4 声称的"后写者胜、可能丢消息"未保护）
// 并发 8 个 POST /api/chat，同一 sessionId，每个记住不同编号；完成后核对 Redis LLEN 与内容完整性。
const BASE = process.env.BASE || 'http://127.0.0.1:18302';
const SID = process.env.SID || 'glm-conc';
const N = Number(process.env.N || 8);

const post = (path, body) => fetch(BASE + path, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
}).then(async (r) => ({ status: r.status, body: await r.json().catch(() => null) }));

const results = await Promise.all(Array.from({ length: N }, (_, i) =>
  post('/api/chat', { sessionId: SID, message: `请记住编号 ${i + 1}。只回复“已记住”。` })
    .then((r) => ({ i: i + 1, status: r.status, reply: r.body && r.body.reply, historySize: r.body && r.body.historySize }))));

await new Promise((r) => setTimeout(r, 500));
const hist = await fetch(`${BASE}/api/chat/${SID}/history`).then((r) => r.json());
const userMsgs = hist.filter((m) => m.type === 'USER').map((m) => m.text);
const asstMsgs = hist.filter((m) => m.type === 'ASSISTANT').map((m) => m.text);
const seen = new Set(userMsgs.map((t) => (t.match(/编号 (\d+)/) || [])[1]).filter(Boolean));

console.log(JSON.stringify({
  sessionId: SID, concurrency: N,
  perRequest: results,
  historyLen: hist.length, userCount: userMsgs.length, assistantCount: asstMsgs.length,
  expectedUserCount: N, missingNumbers: Array.from({ length: N }, (_, i) => String(i + 1)).filter((x) => !seen.has(x)),
  duplicateUserTexts: userMsgs.filter((t, i) => userMsgs.indexOf(t) !== i),
}, null, 2));
