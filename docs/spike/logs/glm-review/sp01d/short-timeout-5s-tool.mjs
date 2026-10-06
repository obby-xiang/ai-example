// 反例 A：inter-event-timeout=2s < 工具耗时 5s，无故障注入。预测：工具被误杀中断。
const BASE='http://127.0.0.1:18303';
const res=await fetch(BASE+'/api/chat',{method:'POST',headers:{'content-type':'application/json',accept:'text/event-stream'},
  body:JSON.stringify({sessionId:'glm-short-timeout',message:'请执行一个 5 秒的长任务。',strategy:'guarded',
    system:'你必须调用 longTask 工具执行长任务，参数 seconds=5，cooperative=false，然后据结果回答。回复使用中文。'})});
const dec=new TextDecoder();let buf='';const evs=[];
for await (const c of res.body){buf+=dec.decode(c,{stream:true});let i;
  while((i=buf.indexOf('\n\n'))>=0){const f=buf.slice(0,i);buf=buf.slice(i+2);
    const m=f.match(/^event:(.+)$/m);const d=f.match(/^data:(.+)$/m);
    if(m)evs.push({e:m[1].trim(),d:d?d[1].slice(0,220):''});}}
const state=await fetch(BASE+'/api/chat/glm-short-timeout/state').then(r=>r.json());
const toolLog=await fetch(BASE+'/api/dev/tool-exec-log').then(r=>r.json());
console.log(JSON.stringify({events:evs,state:{terminal:state.terminal,attempts:state.attempts,toolSideEffect:state.toolSideEffect,emittedChars:state.emittedChars,elapsedMs:state.elapsedMs},toolLog},null,1));
