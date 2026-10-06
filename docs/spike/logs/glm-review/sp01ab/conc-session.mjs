// 反例 2：同一 sessionId 并发发起两轮对话（其中一轮在确认门挂起中）
const BASE='http://127.0.0.1:18301';
const SID='glm-conc-'+Date.now();
const post=(p,b)=>fetch(BASE+p,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)}).then(async r=>({status:r.status,body:await r.json().catch(()=>null)}));
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
// 1) 发起敏感删除（会挂起在确认门）
const first=await post('/api/chat',{sessionId:SID,message:'请删除配置 def-777（这是一次真实的删除操作，按工具规则执行）。'});
console.log('first  ->',first.status,JSON.stringify(first.body).slice(0,150));
await sleep(4000); // 确保进入挂起
// 2) 同会话并发第二轮
const second=await post('/api/chat',{sessionId:SID,message:'现在几点了？'});
console.log('second ->',second.status,JSON.stringify(second.body).slice(0,200));
// 3) 补交第一轮的 confirm
const conf=await post('/api/confirm',{sessionId:SID,decision:'reject',reason:'glm-review'});
console.log('confirm->',conf.status,JSON.stringify(conf.body).slice(0,200));
