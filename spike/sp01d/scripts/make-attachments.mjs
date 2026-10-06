// 把 evidence/sp01d-*.json 汇总为脱敏附件，写入 <MAIN_V2>/docs/spike/。
// 运行：node scripts/make-attachments.mjs
// 脱敏：绝对路径 → 占位符；API key → ***；用户名 → <USER>
import fs from 'node:fs';
import path from 'node:path';

const EVIDENCE = process.env.EVIDENCE_DIR || 'evidence';
const OUT = process.env.OUT_DIR || '../../docs/spike';
fs.mkdirSync(OUT, { recursive: true });

function sanitize(s) {
  return String(s)
    .replace(/[A-Za-z]:[\\/]+temp[\\/]+ai-example-code[\\/]+ai-example-main-v2/g, '<MAIN_V2>')
    .replace(/\/e\/temp\/ai-example-code\/ai-example-main-v2/g, '<MAIN_V2>')
    .replace(/[A-Za-z]:[\\/]+Program Files[\\/]+JetBrains[\\/][^"'\s\\]*maven3[\\/]+bin/g, '<MAVEN_HOME>')
    .replace(/[A-Za-z]:[\\/]+Program Files[\\/]+Java[\\/][^"'\s\\]*jdk[^"'\s\\]*/g, '<JDK_HOME>')
    .replace(/[A-Za-z]:[\\/]+Users[\\/]+[^\\/]+/g, '<USER_HOME>')
    .replace(/started by [A-Za-z0-9_]+/g, 'started by <USER>')
    .replace(/sk-[A-Za-z0-9_-]{16,}/g, '***');
}

function read(name) {
  return JSON.parse(fs.readFileSync(path.join(EVIDENCE, `sp01d-${name}.json`), 'utf8'));
}

function wireLines(wire) {
  if (!wire || !wire.length) return '    （无代理台账）';
  return wire.map((w) => `    #${w.requestIndex} mode=${w.mode} applied=${w.applied} status=${w.status}
       转发 ${w.relayedBytes ?? 0}B / ${w.relayedEvents ?? '-'} 个事件 / ${w.contentEvents ?? '-'} 个正文 delta
       结果=${w.outcome} 耗时=${w.durationMs}ms`).join('\n');
}

function sseBlock(j) {
  if (!j.sse) return '';
  const t = j.sse.terminalData || {};
  const lines = [];
  lines.push(`  事件序列: ${(j.sse.eventSequence || []).join(' | ')}`);
  lines.push(`  计数: ${JSON.stringify(j.sse.eventCounts)}`);
  lines.push(`  delta 拼接文本(长度 ${j.sse.concatenatedDeltaLen}, 重放前缀长度 ${j.sse.duplicatedPrefixLen}): ${JSON.stringify(j.sse.concatenatedDeltaText)}`);
  if (t && Object.keys(t).length) {
    lines.push(`  终态事件数据: ${JSON.stringify(t)}`);
  }
  lines.push(`  客户端耗时=${j.sse.elapsedMs}ms 主动放弃=${j.sse.clientAborted} 客户端异常=${j.sse.streamError}`);
  return lines.join('\n');
}

function stateBlock(j) {
  const s = j.sessionState;
  if (!s) return '';
  const lines = [];
  lines.push(`  服务端终态=${s.terminal} cancelled=${s.cancelled} 上游订阅次数=${s.refSubscriptions} 累计时长=${s.elapsedMs}ms 已产出字符=${s.emittedChars}`);
  lines.push(`  尝试记录: ${JSON.stringify(s.attemptRecords)}`);
  if (s.content) lines.push(`  已产出内容: ${JSON.stringify(s.content)}`);
  return lines.join('\n');
}

function toolBlock(j) {
  if (!j.toolExecLog) return '';
  return `  工具执行台账: ${JSON.stringify(j.toolExecLog)}`;
}

function caseBlock(name) {
  const j = read(name);
  const out = [];
  out.push(`### ${name}`);
  if (j.description) out.push(`  说明: ${j.description}`);
  if (j.fault) out.push(`  故障配置: ${JSON.stringify(j.fault)}`);
  const sse = sseBlock(j);
  if (sse) out.push(sse);
  const st = stateBlock(j);
  if (st) out.push(st);
  const tl = toolBlock(j);
  if (tl) out.push(tl);
  if (j.wire) out.push(`  代理逐请求台账:\n${wireLines(j.wire)}`);
  if (j.afterClientAbort) {
    out.push(`  客户端放弃后: 服务端终态=${j.afterClientAbort.sessionState.terminal} 仍在执行本轮的线程=${j.afterClientAbort.blockedThreads.filter((t) => t.blockedInRun).map((t) => t.thread).join(',') || '（无）'}`);
    out.push(`  3 秒后再查: 服务端终态=${j.threeSecondsLater.sessionState.terminal} 仍在执行本轮的线程=${j.threeSecondsLater.blockedThreads.filter((t) => t.blockedInRun).map((t) => t.thread).join(',') || '（无）'}`);
    out.push(`  代理强断连接后: 服务端终态=${j.afterProxyKill.sessionState.terminal} 仍在执行本轮的线程=${j.afterProxyKill.blockedThreads.filter((t) => t.blockedInRun).map((t) => t.thread).join(',') || '（无）'}`);
    const bt = (j.afterClientAbort.blockedThreads || []).find((t) => t.blockedInRun);
    if (bt) out.push(`  阻塞线程栈顶: ${bt.topFrames.slice(0, 8).join(' <- ')}`);
  }
  if (j.start) {
    out.push(`  start 返回: status=${j.start.status} round=${j.start.round}`);
    out.push(`  待回灌 tool_calls: ${JSON.stringify(j.start.pending)}`);
    out.push(`  消息序列(初始): ${JSON.stringify(j.start.messageSequence)}`);
  }
  if (j.toolCallId) out.push(`  同一 toolCallId 两次提交:`);
  if (j.firstSubmit) out.push(`    第一次: ${JSON.stringify(j.firstSubmit)}`);
  if (j.secondSubmitSameId) out.push(`    第二次(同 id): ${JSON.stringify(j.secondSubmitSameId)}`);
  if (j.thirdSubmitUnknownId) out.push(`    第三次(未知 id): ${JSON.stringify(j.thirdSubmitUnknownId)}`);
  if (j.finalState) {
    out.push(`  最终状态: status=${j.finalState.status} round=${j.finalState.round} finalText=${JSON.stringify(j.finalState.finalText)}`);
    out.push(`  最终消息序列: ${JSON.stringify(j.finalState.messageSequence, null, 1)}`);
    out.push(`  提交记录: ${JSON.stringify(j.finalState.submissions)}`);
    out.push(`  该 run 工具执行: ${JSON.stringify(j.finalState.toolExecLogForRun)}`);
  }
  if (j.response) {
    out.push(`  入参 tool_call id 列表: ${JSON.stringify(j.response.inputAssistantToolCallIds)}`);
    out.push(`  官方 ToolCallingManager 返回历史: ${JSON.stringify(j.response.toolExecutionResultHistory)}`);
    out.push(`  历史中 tool 响应 id 序列: ${JSON.stringify(j.response.toolResponseIdsInOrder)} (去重后 ${JSON.stringify(j.response.distinctToolResponseIds)})`);
    out.push(`  实际执行次数: ${j.response.executionCount}；执行台账: ${JSON.stringify(j.response.executions)}`);
    out.push(`  异常: ${j.response.error}`);
  }
  if (j.timeline) {
    const t = j.timeline;
    out.push(`  时间线: 工具进入执行=+${t.toolActiveAtMs - t.runStartMs}ms 取消信号发出=+${t.cancelSentAtMs - t.runStartMs}ms`);
    out.push(`          流终止=+${t.streamEndedAtMs - t.runStartMs}ms 取消→终止=${t.msFromCancelToStreamEnd}ms`);
    out.push(`  取消接口返回: ${JSON.stringify(j.cancelResponse)}`);
    out.push(`  done 事件: ${JSON.stringify(j.doneEvent)}`);
    out.push(`  工具执行台账: ${JSON.stringify(j.toolExecLog)}`);
    out.push(`  工具活动信标: ${JSON.stringify(j.toolActivity)}`);
    out.push(`  取消注册表: registered=${JSON.stringify(j.cancelRegistryState.registered)}`);
    out.push(`  全部 SSE 事件: ${JSON.stringify((j.allEvents || []).map((e) => e.event + '@' + e.tsOffsetMs))}`);
    out.push(`  代理逐请求台账:\n${wireLines(j.wire)}`);
  }
  if (j.note) out.push(`  备注: ${j.note}`);
  return out.join('\n');
}

const VD1 = ['vd1-baseline', 'vd1-guarded-first', 'vd1-ref-first', 'vd1-refbare-first', 'vd1-refmodel-first', 'vd1-plain-hang',
  'vd1-guarded-mid', 'vd1-ref-mid', 'vd1-refbare-mid', 'vd1-refmodel-mid', 'vd1-plain-mid', 'vd1-fin-content',
  'vd1-truncate-rst', 'vd1-truncate-fin',
  'vd1-guarded-tooldup', 'vd1-ref-tooldup', 'vd1-refbare-tooldup',
  'vd1-guarded-longtool', 'vd1-ref-longtool'];
const VD2 = ['vd2-spi-dup', 'vd2-fronttool'];
const VD3 = ['vd3-coop-cancel', 'vd3-hard-cancel'];

const probe = fs.existsSync(path.join(EVIDENCE, 'flux-timeout-probe.txt'))
  ? fs.readFileSync(path.join(EVIDENCE, 'flux-timeout-probe.txt'), 'utf8')
  : '(未找到探针输出)';

const attachA = `SP-01d 附件 A：故障注入方法、复现步骤与 Reactor 超时语义探针

一、故障注入方式（不在被验证进程内做任何 mock）
    官方 ChatModel 的 base-url 指向本 spike 的本地故障代理（<MAIN_V2>/spike/sp01d/scripts/fault-proxy.mjs），
    代理再转发到真实上游（https://api.deepseek.com）。代理按 SSE 事件边界（"\\n\\n"）转发，
    绝不切碎半个事件；故障通过控制面 POST /__fault 实时切换。

    模式（relayEvents = 事件个数；contentEvents = 带正文的 delta 个数）：
      none                 透传（对照组）
      reset                收到请求立即 RST
      stall                完全不回字节（含响应头），stallMs 后 RST          → 首包静默
      stall-after-content  转发到 contentEvents 个正文 delta 后停止发送、
                           保持连接，stallMs 后 RST                        → 事件间静默
      fin-after-content    同上但立即 res.end()（TCP 正常 FIN，无 [DONE]） → 静默截断
      truncate             转发 relayEvents 个事件后 RST                   → 硬截断
      truncate-fin         转发 relayEvents 个事件后正常 FIN               → 无正文的静默截断
      onlyRequestIndex     只对该轮的第 N 次上游请求生效（用于让工具先成功执行、故障落在其后）

    复现步骤：
      export DEEPSEEK_API_KEY=***        # 仅环境变量，未写入任何文件
      cd <MAIN_V2>/spike/sp01d && <MAVEN_HOME>/mvn -B clean package
      node scripts/fault-proxy.mjs --port 18304 --upstream https://api.deepseek.com --evidence evidence &
      java -jar target/sp01d-resilience-0.0.1-SNAPSHOT.jar \\
           --spring.ai.openai.base-url=http://127.0.0.1:18304 --server.port=18303 --spring.profiles.active=verbose &
      node scripts/spike-verify.mjs <case>   # case 见 scripts/spike-verify.mjs 的 CASES
      每个 case 自行配置故障、清空工具台账、跑请求并把结果落到 evidence/。

二、Reactor 超时语义探针（scripts/FluxTimeoutProbe.java，reactive-streams 1.0.4 + 该 jar 自报版本见下）
${sanitize(probe)}

探针结论：
  B: Flux.timeout(单值) 覆盖"首包 + 事件间"两层超时（首元素后静默 303ms 即抛 TimeoutException）；
     因此参照实现的 .timeout(90s) 确实能兜住"上游静默"，不需要额外写法。
  D/E: .timeout(D).retry(1).doOnNext(..) 在已经产出了元素后出错/超时时会重订阅上游，
     并把已经发给下游的内容**再发一遍**（下游收到 ABAB），即"已产出内容被重放"。
`;

const attachB = `SP-01d 附件 B：V-d1 断流有界重试 逐 case 实测记录
（原始数据：<MAIN_V2>/spike/sp01d/evidence/sp01d-vd1-*.json；此处为脱敏摘要）

${VD1.filter((n) => fs.existsSync(path.join(EVIDENCE, `sp01d-${n}.json`))).map(caseBlock).join('\n\n')}
`;

const attachC = `SP-01d 附件 C：V-d2 tool-result 重复去重 实测记录

${VD2.filter((n) => fs.existsSync(path.join(EVIDENCE, `sp01d-${n}.json`))).map(caseBlock).join('\n\n')}
`;

const attachD = `SP-01d 附件 D：V-d3 执行中取消 实测记录

${VD3.filter((n) => fs.existsSync(path.join(EVIDENCE, `sp01d-${n}.json`))).map(caseBlock).join('\n\n')}
`;

function writeOut(file, content) {
  fs.writeFileSync(path.join(OUT, file), sanitize(content));
  console.log('wrote', path.join(OUT, file));
}

writeOut('SP-01d-附件-A-故障注入方法与探针.txt', attachA);
writeOut('SP-01d-附件-B-Vd1断流实测记录.txt', attachB);
writeOut('SP-01d-附件-C-Vd2工具重复实测记录.txt', attachC);
writeOut('SP-01d-附件-D-Vd3取消实测记录.txt', attachD);

// 附件 E：运行日志（脱敏 + 只保留关键行）
const logFiles = [
  ['logs/app.log', '应用日志（本轮）'],
  ['logs/proxy.log', '故障代理日志（本轮）']
];
let logOut = 'SP-01d 附件 E：运行日志关键行（已脱敏）\n\n';
for (const [f, label] of logFiles) {
  if (!fs.existsSync(f)) continue;
  const lines = fs.readFileSync(f, 'utf8').split('\n')
    .filter((l) => /RUN_END|ATTEMPT_FAILED|TOOL_EXEC|REF_UPSTREAM_SUBSCRIBE|REF_BEFORE_RETRY_ERROR|CLIENT_ABORT|proxy\]/.test(l));
  logOut += `===== ${label}：${f}（共 ${lines.length} 行关键行）=====\n` + lines.join('\n') + '\n\n';
}
writeOut('SP-01d-附件-E-运行日志关键行.log', logOut);
