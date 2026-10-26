# 历史补档：批G 修复后真机回归（刷新矩阵 10 + 停止矩阵 11）

- **归档来源（已脱敏）**：`<LOCAL_WORK>/_rg-probe/regression/report.md`（源 167 行 / 14,739 B）· 源件仅仓外，未移动、未改写
- **归档日期**：2026-10-26（本批仅归档）
- **历史取证 · 非现行基线**：本文是 2026-10-22 时点的历史证据，结论未经当前代码复验，**不表示当前已验证 / 已修复**；正文逐字保留（仅路径、会话标识、用户名/主机名与旧目录名脱敏），不改写旧结论。
- **会话（体内代号）**：<KIMI_SESSION_A>（缩写；模型与 `agentId` 自证字段按 DC-19 保留原值）
- **源行 / 源字节**：167 行 / 14,739 B

---

# 批G 修复后真机回归（刷新矩阵 10 + 停止矩阵 11）

- 执行者：子智能体 agent-1500（profile.bind 自证：`profileName=coder` / `modelAlias=DeepSeek/deepseek-flash` / `thinkingEffort=high`，wire 见 `<KIMI_SESSIONS_ROOT>/<WORKSPACE>/<KIMI_SESSION_A>-.../agents/agent-1500/wire.jsonl`）
- 环境：后端 8081（真模型 deepseek-flash）、前端 5202，均在运行；`git log -1` = `0e8bc7c fix(ai): 批G 刷新恢复与停止收敛根治…`（工作树仅 `?? data/`）。**未改仓库、未启停服务**。
- 方法：Playwright(chromium, 1440×950) 驱动真实 UI；每场景落 PNG + `results.jsonl` 判定 + 证据 JSON；后端真值走 `/api/ai/runs`、`/runs/{id}`、`/runs/current`、`/history`、`/events/{id}` 归档回放。
- 证据目录：`<LOCAL_WORK>/_rg-probe/regression/`（`results.jsonl` = 机器可读判定；`A-`/`B-`/`C-`/`E-`/`F-` 子目录 = 截图与 evidence.json）

---

## 0. 头号结论（必须先看）：任务中心页对话 100% 打挂（P0，阻断 6 个场景）

**症状**：在 `/tasks` 页发任何一句话（含"只回答一句话：1+1 等于几？"），首帧前即失败，顶部红条：

```
本轮出错（UPSTREAM_STREAM_ERROR）：上游返回 HTTP 400：
{"error":{"message":"Invalid schema for function 'generative_form':
  Pointer '/$defs/FormCondition' does not exist (...)",
 "type":"invalid_request_error","code":"invalid_request_error"}}
```

**证据**：
- 前端实发请求体（抓包）：`{sessionId, message, context:{page:"tasks", taskType:null, step:null, taskId:null, extra:{...}}}` → SSE `start → retry → error`（`DIAG/diag.json`）。
- curl 对照矩阵：`page=tasks` → **400 + error**；`page=config` / `page=defs` / 无 context → **正常出 delta**。⇒ 与"是否带上下文"无关，**只与工具集里是否含 `generative_form` 有关**。
- 代码锚点：`backend/.../ai/tools/AiTools.java:547` 的 `@ToolScope({"page:tasks","task:*"})` 把 `generative_form` 挂进任务页/任务向导页；其参数 `FormField`（`:607-632`）引用了三个自定义 record `FormCondition`（`:645`）/`FormOptionsFrom`/`FormDefaultFrom`。Spring AI 生成的 JSON Schema 里对它们发的是 `$ref: #/$defs/FormCondition`，而顶层没有对应 `$defs`（上游按 JSON Schema 规范校验直接 400）。
- 影响面：`page:tasks`（任务中心，默认落地页）与**所有 `task:*` 向导页**的对话全部不可用；`page:definitions` / `page:data` 正常。
- 因此 **R4/R5/R6/S13/S14/S21 六个场景无法实测（BLOCKED）**，本报告的其余 15 项改在 `/definitions` 页执行（该页不含 GF 工具，行为与页面无关；端到端链路一致）。
- 次要观察：出错轮的助手气泡是**空气泡（0 字）**，错误只在顶部 notice 里；同一会话连续三次提问，用户气泡会叠在同一个列表里而错误轮留下空助手气泡（截图 `F-blocked/BLOCK-0*.png`）。

---

## 1. 结果总览（21 项：**14 PASS / 1 FAIL / 6 BLOCKED**）

| # | 场景 | 判定 | 关键实测 |
|---|---|---|---|
| ① | 文本流式中刷新 = 正文续显/无横幅无 runId/停止钮在 | **PASS** | 刷新前 102 字 → 刷新瞬间 369 字（全量回放），续收仍在流，停止钮在，无横幅，页面无 runId |
| ② | 老会话第 2 轮流式中刷新 | **FAIL** | 刷新后只有 3 个气泡：`[user1:15, assistant:1024, user2:51]` ⇒ **第 1 轮回答(9 字)被第 2 轮回答整段覆盖、回答排到提问前面**（对照：轮次结束后再刷新，4 气泡顺序正确） |
| ③ | 工具连环执行中刷新 | **PASS** | 刷新前 3 卡（含 1 张「调用中…」）→ 刷新后 8 卡全「已成功」，轮次续收到 `DONE` |
| ④ | HITL 挂起刷新（文字+已执行卡+确认卡，批准跑完） | **BLOCKED** | 任务页 400（见 §0） |
| ⑤ | 批准后执行中刷新 = 静默续收 | **BLOCKED** | 同上 |
| ⑥ | GF 表单挂起刷新 = 结构+草稿（含 boolean=true） | **BLOCKED** | 同上（GF 工具 schema 本身即 400 根因） |
| ⑦ | 刷新后回车 = 无反应且零提示、草稿保留、停后再发成功 | **PASS** | 气泡 2→2、`banners={}`（零提示）、草稿原样保留；停止后回车发送成功 |
| ⑧ | 刷新后停止可用、停后出「已停止」 | **PASS** | 停止钮 present 且 enabled；点后出现 `msg-stopped-*`，半截正文 260 字保留，再发 2→4 气泡 |
| ⑨ | 切后台瞬间正文落镜像 | **PASS** | `visibilityState=hidden` + `visibilitychange` 后 200ms：DOM 155 字 / 镜像 157 字、`inFlight:true`（切前镜像 0 字） |
| ⑩ | 双页签第二页签发 = 一句人话且无幻影气泡 | **PASS（口径=零提示）** | 第二页（同 session）打开即在途轮被**静默续收**（显示首屏那轮 1427 字），回车**零提示不发送**，无幻影用户气泡，草稿保留；未触发 409（在途轮已被接管） |
| ⑪ | 文本流式中停止 = 正文保留+「已停止」+刷新后仍在 | **PASS** | 停时 145→284 字保留、出 chip、输入可用 |
| ⑫ | 工具执行中停止 = 卡变「已取消」、输入立刻可用 | **PASS** | 卡状态 `["已成功","已取消"]`，无「调用中」残留，`taDisabled=false`，出 chip |
| ⑬ | HITL 挂起中停止 = 输入立刻解禁/卡只读/再点不 409 | **BLOCKED** | 任务页 400（P0 回归点未能验收，见 §0/§4） |
| ⑭ | 表单挂起中停止 | **BLOCKED** | 同 §0 |
| ⑮ | 连点停止 = 仅 1 次 cancel、按钮停止中 disabled | **PASS** | cancel POST 恰好 1 次；按钮在同 tick 第二击后 ~30ms 内从 DOM 移除（无"停止中…"可观测窗口，防重靠 store `stopping` 幂等） |
| ⑯ | 停止后立刻刷新 = 半截正文 + 已停止 | **PASS** | 刷新后 `[user:51, assistant:284]`，chip 在，正文保留 |
| ⑰ | 停止后发新消息 = 200 无幻影 | **PASS** | `/api/ai/chat` 200，新增 2 气泡，无 notice、无幻影 |
| ⑱ | 双页签停止同步 | **PASS** | 页签1 停止后，页签2 发送 200（会话门已放行），无 409 |
| ⑲ | 停止后 60ms 内无新工具卡 | **PASS** | 点停止时 2 卡，+60ms=2、+300ms=2；状态 `[已成功,已取消]` |
| ⑳ | 僵尸轮放行 | **PASS** | 用 Redis 夹具 `ai:session:lock:<sid>` = 无快照 runId（TTL 60s）占住会话门 → 发送仍 200、轮次 `DONE`、夹具锁被释放（`GET → nil`） |
| ㉑ | 真鼠标点 boolean 开关即勾选 | **BLOCKED** | 表单渲染不出来（§0） |

> 说明：首轮在 `/tasks` 跑 R1/R2 时得的两个"结果"被 400 打挂，属环境阻断而非产品行为；R1 已在 `/definitions` 复测 PASS，R2 已复测 FAIL 并给出对照实验。

---

## 2. FAIL 根因（R2）——两条链叠加

### 2.1 启动期"空镜像覆盖"竞态（新发现，P0 级）
页面加载时序：

```
AiPanel.vue:3328  ai.startMirror()        // 装 $subscribe → writeMirrorNow（节流）
AiPanel.vue:3335  ai.ensureTabOwnership() // 改 this.tabOwnership（state 变更）
                  ↳ $subscribe 触发 → writeMirrorNow() → 此时 this.messages = []（尚未装载）
AiPanel.vue:3340  ai.loadHistory()        // 才 readMirror(...)
```

实测（`diag-r2-timeline.mjs`，页内给 `Storage` 打点，同一次刷新内的顺序）：

```
SET ai-mirror:<sid>  n=0  inFlight=false      ← 启动期空写，把持久化镜像抹掉
GET ai-mirror:<sid>  n=0  inFlight=false      ← loadHistory 读到的是空镜像
SET ai-mirror:<sid>  n=3  inFlight=true       ← 之后 store 只剩历史 3 条
```

刷新前镜像本来写对了（`SET n=4 inFlight=true`，多次）。空写调用栈经页内 `setItem` 打点确认：`writeMirror ← writeMirrorNow ← $subscribe.detached (ai.ts:778 编译行) `。
⇒ `mirrorInFlight=false` + `mirrorMessages=[]`，G3 的「历史 + 镜像在途轮合并」**直接空转**（`mergeMirrorInFlightRound` 首行 `if (!inFlight || mirrorMessages.length === 0) return historical`）。
代码锚点：`frontend/src/stores/ai.ts:1103-1121`（subscribe → writeMirrorNow，无"是否已装载"闸门）+ `AiPanel.vue:3328/3335/3340` 的顺序。
**注**：S16 同一路径却 PASS（镜像没被抹），说明这是**竞态**——刷新恢复是否成立取决于启动期那次空写与 `readMirror` 的先后，本身即缺陷。

### 2.2 在途轮宿主回落"最后一条助手消息"，抢占上一轮回答
`frontend/src/stores/ai.ts:1749-1753`：

```ts
let host = [...messages].reverse().find(m => m.role === 'assistant' && !m.done)
        ?? [...messages].reverse().find(m => m.role === 'assistant')   // ← 兜底：任意最后一条助手
        ?? null
host.parts = [{ kind:'text', seg:0, text:'', streaming:true }]         // 整段清空
...
reattachActive(runId, { hostMessageId: host.id, lastSeq: 0 })          // 全量重放到它身上
```

`loadHistory` 归一后所有历史消息都是 `done:true`，所以第一分支必然落空；当镜像合并没把在途助手接回（§2.1），`host` 就取到了**上一轮**的助手消息 → 清空 → 把本轮全量回放写进去。
观测结果正是 `[user1, assistant(第2轮回答), user2]`，且第 1 轮回答（"1+1 等于 2。"）消失。
**对照实验**：等轮次结束再刷新 → `[15, 9, 28, 567]` 顺序正确 ⇒ 只在"在途 + 刷新"路径发生。

### 2.3 建议修法
1. `writeMirrorNow` 加闸：`if (!historyLoaded && !this.messages.length) return`（或把 `startMirror()` 移到 `loadHistory` 的 `readMirror` 之后），彻底消除启动期空写。
2. `restoreInFlightRound` 的宿主只认"本轮"：优先镜像 `lastRunId` 对应的未收尾助手；否则**追加新助手消息**（`appendAssistantMessage`），绝不回落到历史里已完成的助手。
3. `mergeMirrorInFlightRound` 判据补强：`inFlight` 为真但镜像被清空时，用 `mirror.lastRunId` 与 `runSeq` 兜底判断"本轮是否在途"，避免静默退化。

---

## 3. 通过项的实证要点（挑关键）

- **①/③ 刷新续收**：刷新后走 `/runs/current → found:true(RUNNING) → /events/{runId}?lastSeq=0` 全量回放（R1 刷新瞬间就已有 369 字，4ms 内判定"已超过刷新前长度"）。无横幅、无 runId、停止钮在——批G G1/G2/G5 验收通过。
- **⑦ 在途零提示**：刷新后回车，气泡数不变、`banners={}`、草稿原样；与 G6 口径一致。
- **⑧/⑪/⑯ 停止收敛 + 可见**：停止后 `msg-stopped-*` 出现、正文保留、输入解禁；刷新后 chip 与半截正文都还在（`S16` 气泡 `[51,284]`）。
- **⑫/⑲ 工具停止**：在执行中的那张卡由「调用中…」直接变「已取消」（不再永久卡住），停止后 60ms 内没有新卡长出。
- **⑮ 连点**：`cancel` 只发 1 次（`stop()` 的 `stopping` 幂等），按钮在首点后 ~30ms 被移除。
- **⑳ 僵尸轮**：真夹具（Redis 失效会话锁 + 无快照）下发送 200、轮次 DONE、锁被清——`RunLiveness.isDead` 放行生效。
- **⑩/⑱ 双页签**：同 session 的第二页开启即在途轮被静默续收（无 409、无幻影、零提示）；页签1 停止后页签2 发送 200。

---

## 4. 未验收 / 建议下一步

1. **先修 §0 的 GF schema（阻断级）**：`generative_form` 的参数树里对 `FormCondition/FormOptionsFrom/FormDefaultFrom` 的 `$ref` 与 `$defs` 不匹配。最小修法：把这三个内联 record 展平（改成 `Map<String,Object>`/`List<Object>` 之类的弱类型）或显式提供顶层 `$defs`；修好后 R4/R5/R6/S13/S14/S21 必须重跑——尤其 **S13（HITL 挂起中停止 → 输入立刻解禁）是批G 的 P0 回归点**，本次未能验收。
2. **修 §2 的启动空写竞态**（一行闸门），然后重跑 R2（以及 R4/R6 这类需要"镜像在途"的场景，它们很可能受同一竞态影响）。
3. `restoreInFlightRound` 宿主兜底二选一必须删掉（§2.2），否则任何"镜像未接回"的刷新都会吞掉上一轮回答。
4. ⑩ 的口径需与需求方对齐：批G G6 定的是"在途轮零提示"，而本次验收清单写的是"一句人话"。实测为**零提示**，按 G6 判 PASS；若产品要"一句人话"，需另开一条。
5. 次要（本次未列矩阵）：错误轮的助手空气泡 + 顶部 notice；连续失败会留多个空助手气泡。

---

## 5. 逐场景证据索引

| # | 截图 / 证据 |
|---|---|
| ① | `A-refresh-text/R1-01-before-reload.png`、`R1-02-after-reload.png`、`R1-03-resumed.png`、`R1.json` |
| ② | `A-refresh-text/R2b-01-before-reload.png`、`R2b-02-after-reload.png`、`R2b.json`（对照：`DIAG/r2diag`、`diag-r2-ctrl.mjs` 输出） |
| ③ | `B-stop-tools/R3-01-tools-before-reload.png`、`R3-02-after-reload.png`、`R3-03-settled.png`、`R3.json` |
| ④⑤⑥ | `F-blocked/BLOCK-*.png`、`F-blocked/evidence.json` |
| ⑦ | `A-refresh-text/R78-01..04*.png`、`R78.json` |
| ⑧ | `A-refresh-text/R8b-01..03*.png`、`R8b.json` |
| ⑨ | `A-refresh-text/R9-visibility-hidden.png`、`R9.json` |
| ⑩ | `C-twotab/R10-01-tab2-send-during-inflight.png`、`R10S18.json` |
| ⑪ | `B-stop-tools/S11-01-before-stop.png`、`S11-02-stopped.png`、`S11S16.json` |
| ⑫ | `B-stop-tools/S12-01-after-stop-900ms.png`（已成功 / 已取消 + 已停止 + 输入可用）、`S12-02-after-stop-4s.png` |
| ⑬⑭㉑ | `F-blocked/BLOCK-*.png` |
| ⑮ | `B-stop-tools/S15-01-double-click.png`、`S15.json` |
| ⑯ | `B-stop-tools/S16-01-after-reload.png`、`S11S16.json` |
| ⑰ | `B-stop-tools/S17-01-new-send.png`、`S12S17S19.json` |
| ⑱ | `C-twotab/S18-01-tab1-stopped.png`、`S18-02-tab2-after-stop.png` |
| ⑲ | `C-twotab/S19-01-after-stop.png`、`S19.json` |
| ⑳ | `E-zombie/S20-01-zombie-release.png`、`E-zombie/evidence.json`（含夹具 SET/GET 与锁释放） |
| 根因 | `diag-r2-timeline.mjs`（SET n=0 → GET n=0 时间线）、`diag-bootwrite.mjs`（空写调用栈）、`DIAG/diag.json`（400 的请求体与 SSE） |

## 6. 复现方式

```bash
cd <LOCAL_WORK>/_rg-probe/regression
node a-refresh-text.mjs      # R1 R2 R7 R8 R9（/definitions）
node a2-rerun-r2-r8.mjs      # R2/R8 复跑（带 inFlight 与 chip 取证）
node b-stop-tools.mjs        # R3 S12 S19 S17 S11 S16 S15
node c-twotab.mjs            # S19 复跑 + R10 S18（双页签）
node e-zombie.mjs            # S20（Redis 夹具，TTL 60s 自过期）
node f-blocked.mjs           # R4 R5 R6 S13 S14 S21 的阻断取证（/tasks）
node diag-r2-timeline.mjs    # §2.1 的 SET/GET 时间线（根因取证）
```

夹具说明：`e-zombie.mjs` 会往 Redis 写 `ai:session:lock:<测试会话>`（值=无快照的 runId，TTL 60s），跑完自动过期；不触碰任何业务数据与仓库。
