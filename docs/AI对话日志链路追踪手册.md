# AI 对话日志链路追踪手册

- 版本：2026-10-21（AI 可观测性专项 · 日志链路追踪批）
- 适用基线：`main` 之后含 `feature/ai-logtrace` 的后端
- 读者：排障值班、开发、评审
- 定位：**只凭日志就能还原一轮 AI 对话**。帧（SSE）与 Redis 键族仍是真值；日志是本批新增的**可 grep 的骨架**

---

## 0. 一分钟上手

```bash
# 1) 拿到 runId：用户说的"刚才那轮"、前端 start 帧、409 SESSION_BUSY 的响应体、/api/ai/runs 列表
# 2) 一把梭：这个 runId 相关的所有日志（含行首锚点）
grep -F "<runId>" app.log

# 3) 只看结构化事件（跳过 WARN/ERROR 细节行）
grep -F "<runId>" app.log | grep -E '(^| )event='

# 4) 只看收尾摘要
grep -F "<runId>" app.log | grep 'event=turn_end'
```

一行 `turn_end` 就回答了"这一轮成没成、多久、几次工具、等人等了多久、多少 token"。

---

## 1. 日志的两个层次

| 层次 | 形态 | 用途 |
|---|---|---|
| **行首锚点**（MDC） | 级别之后紧跟 `runId` 与 `sessionId`（无锚点的行这两位为空） | `grep <runId>` 即可捞到这一轮**所有**日志，包括既有 WARN/ERROR 细节行 |
| **结构化事件**（`event=`） | `event=<名> runId=.. sessionId=.. k=v k=v`，INFO，单行 | 按事件名聚合、按字段切片、机器可读 |

行首锚点由 `logging.pattern.level`（`backend/src/main/resources/application.yml`）渲染：

```
%5p %X{runId:-} %X{sessionId:-}
```

实测行形（两段前缀之外与批之前逐字相同）：

```
2026-10-21T21:57:48.213+08:00  INFO 1f9c...e2  s-42 27140 --- [ai-suspend-3] c.e.c.ai.o.AiTrace : event=turn_end runId=1f9c...e2 sessionId=s-42 status=done ...
```

> 锚点为空时不渲染占位符 —— 非 AI 日志的行形与批之前一致，既有按行首匹配的脚本不受影响。

---

## 2. 事件字典

字段约定：所有事件都带 `runId` / `sessionId`（取不到渲染 `-`）；值一律单行、超 160 字符截断；**任何事件都不含对话正文、工具入参、工具出参全文**。

### 2.1 `turn_accepted`（INFO，HTTP 入口）

| 字段 | 含义 |
|---|---|
| `context` | 页面上下文（`page` 名；无则 `none`） |
| `model` | 请求指定的模型（未指定 = `default`，实际模型见 `turn_start`） |
| `chars` | 归一 + DLP 之后的输入字符数 |

### 2.2 `turn_start`（INFO，一次一轮，必有）

| 字段 | 含义 |
|---|---|
| `model` / `provider` | 本轮实际使用的模型与端点 |
| `attempt` | 恒为 `1`（重试另见 `turn_retry`） |
| `resumed` | `true` = 续跑（挂起后重连、重启接管、死卡兜底）；`false` = 首轮 |
| `context` | 上下文键（`page:tasks` / `task:IMPORT/PUBLISH` …） |
| `tools` / `inputEst` | 披露工具数 / 输入估算 token（续跑行为 `history`） |
| `compact` | 本轮起点时的压缩级别（`none`/`L1`/`L2`/`L3`） |
| `maxAttempts` / `budgetMs` | 最大尝试次数 / 总预算（毫秒，不含人工等待） |

### 2.3 `upstream_first_byte`（INFO，每次尝试最多一行）

| 字段 | 含义 |
|---|---|
| `attempt` | 第几次尝试 |
| `ms` | 从"本次尝试开始组装请求"到"第一条上游分片到达"的毫秒数 |
| `chars` / `events` | 已累计正文长度 / 已收上游事件数 |

> 首包超时（`UPSTREAM_FIRST_BYTE_TIMEOUT`）时本行**不会出现** —— "没有首包行"本身就是判据。

### 2.4 `turn_retry`（INFO，可重试失败时）

| 字段 | 含义 |
|---|---|
| `attempt` / `nextAttempt` | 本次失败序号 / 下一次序号 |
| `code` | 断流分类（`UPSTREAM_CONNECTION_BROKEN` / `UPSTREAM_STREAM_INCOMPLETE` / `UPSTREAM_STREAM_ERROR` …） |
| `reason` | 归类时的说明（≤160 字符，单行） |
| `emitted` / `sideEffect` | 是否已产出正文 / 是否已有工具副作用（决定能否重试） |
| `upstreamEvents` / `elapsedMs` | 已收上游事件数 / 本轮已耗时 |

### 2.5 `tool_call_start` / `tool_call_end`（INFO，成对）

| 字段 | start | end |
|---|---|---|
| `toolCallId` | ✓ | ✓（与 start 对齐） |
| `tool` | ✓ 工具名 | ✓ |
| `kind` | ✓ `BACKEND`/`CONFIRM`/`FRONTEND` | ✓ |
| `approval` | ✓ 是否需要人/前端参与 | — |
| `index` | ✓ 本批内序号 | — |
| `ms` | — | ✓ 开始到结算的毫秒数（**含审批等待**） |
| `ok` / `outcome` / `code` | — | ✓ 结局真值（`ok`/`failed`/`unknown`/`blocked`…） |
| `executed` / `chars` | — | ✓ 是否真的执行过 / 结果字符数（不记内容） |

### 2.6 `approval_suspend` / `approval_decided`（INFO）

| 字段 | 含义 |
|---|---|
| `toolCallId` / `tool` | 哪张卡 |
| `kind` | `confirm`（人工确认）/ `frontend`（前端工具） |
| `timeoutS` | 等待上限；`none` = 无上限（确认门与 `generative_form`） |
| `preflight` | 是否带预检报告 |
| `decision` | `approve` / `reject` / `frontend_result` / `frontend_cancel` / `cancel` / `timeout` |
| `actor` | `user`（人拍的板）/ `system`（超时等系统动作） |
| `outcome` | 提交结局（`ACCEPTED`/`DUPLICATE`/`NOT_FOUND`/`REJECTED`） |
| `waitedMs` | **卡发出 → 决策落地**的毫秒数（`-1` = 未记录到请求时刻） |
| `status` | 条目终态（`APPROVED`/`REJECTED`/`FRONTEND_RESULT`/`TIMEOUT` …） |

> `outcome=NOT_FOUND/DUPLICATE` 的行同样会写 —— "有人点了但没生效"也是线索。

### 2.7 `compaction`（INFO，仅实际压缩时）

| 字段 | 含义 |
|---|---|
| `level` | `L1`（清工具结果）/ `L2`（轮窗摘要）/ `L3`（硬裁剪） |
| `reason` | 执行原因（`l1_tool_result_clearing` / `l2_turn_window_summary` / `l3_hard_trim` …） |
| `before` / `after` | 历史 token 估算（压缩前 / 后） |
| `savedTokens` / `lostTokens` | 省下的 / **被丢弃**的估算 token（L2/L3 才有 lost） |
| `turns` / `clearedPairs` / `summaryChars` | 涉及轮数 / 清掉的工具结果条数 / 摘要字符数 |
| `limit` | 分母（0 = 未知） |

> 未压缩**不会**写这行 —— "没发生"与"发生 0 次"可以区分。

### 2.8 `turn_cancelled` / `turn_end`（INFO，一轮一行）

`turn_cancelled`：`attempts` / `partialChars` / `upstreamEvents`。取消后紧随 `turn_end`。

`turn_end` 字段（**终态出口唯一**：`done` / `failed` / `empty_reply` / `blocked` / `cancelled` 都写）：

| 字段 | 含义 |
|---|---|
| `status` | `done` / `failed` / `blocked` / `cancelled` |
| `code` | 失败码（成功为 `none`）；含 `_AFTER_TOOL_SIDE_EFFECT` / `_AFTER_PARTIAL` 后缀说明副作用/半截正文 |
| `attempts` | 尝试次数 |
| `totalMs` | 墙钟总耗时 |
| `activeMs` | **机器耗时** = `totalMs - gateWaitMs`（与总预算同一口径） |
| `gateWaitMs` | 人工等待累计（确认门 + 前端工具挂起） |
| `toolCalls` | 本轮开始过的工具执行次数 |
| `chars` | 产出正文字符数（不记内容） |
| `inputTokens` / `outputTokens` / `totalTokens` | 三维 token（无上游真值时按估算） |
| `est` | `true` = 上述为估算值 |
| `model` | 上游回显优先，回落本轮解析值 |
| `upstreamEvents` | 已收上游事件数 |

### 2.9 `turn_rejected`（INFO，挂起池饱和）

`code=SUSPEND_POOL_SATURATED`。出现在 `turn_accepted` 之后、`turn_start` 之前 —— 该轮**没有真正开始**。

---

## 3. grep 菜谱

> 约定：`app.log` = 后端 stdout 重定向文件；`<R>` = runId，`<S>` = sessionId。

### 3.1 按 runId 拉全链（最常用）

```bash
grep -F "<R>" app.log
```

拿到的是一条时间序：`turn_accepted` → `turn_start` → `upstream_first_byte` →（`tool_call_start`/`approval_suspend`/`approval_decided`/`tool_call_end` 若干）→ `turn_end`，中间夹着既有 WARN/ERROR 细节行（它们同样带行首锚点）。

### 3.2 只看事件行

```bash
grep -F "<R>" app.log | grep -E '(^|[[:space:]])event=' | sed -E 's/^.*event=/event=/'
```

### 3.3 按会话拉所有轮

```bash
grep -F "<S>" app.log | grep -E '(^|[[:space:]])event=turn_(start|end)'
```

### 3.4 找最慢的轮（按机器耗时）

```bash
grep 'event=turn_end' app.log \
 | awk '{for(i=1;i<=NF;i++){split($i,a,"="); v[a[1]]=a[2]} print v["activeMs"], v["totalMs"], v["gateWaitMs"], v["status"], v["runId"]}' \
 | sort -rn | head -20
```

### 3.5 谁的等待耗在人身上

```bash
grep 'event=turn_end' app.log \
 | awk '{for(i=1;i<=NF;i++){split($i,a,"="); v[a[1]]=a[2]} if (v["gateWaitMs"]+0 > 0) print v["gateWaitMs"], v["activeMs"], v["runId"]}' \
 | sort -rn | head
```

`gateWaitMs` 占比高 = 不是模型/工具慢，是在等人（或等前端回执）。

### 3.6 重试与首包

```bash
grep -E 'event=(turn_retry|upstream_first_byte)' app.log
# 某轮"一次尝试都没有首包行" ⇒ 首包超时或连接未建立
grep -F "<R>" app.log | grep -c 'event=upstream_first_byte'
```

### 3.7 工具面

```bash
# 某轮工具清单与耗时
grep -F "<R>" app.log | grep 'event=tool_call_end' \
 | awk '{for(i=1;i<=NF;i++){split($i,a,"="); v[a[1]]=a[2]} print v["ms"], v["tool"], v["outcome"], v["code"]}' | sort -rn

# 需要审批的工具
grep -F "<R>" app.log | grep 'event=tool_call_start' | grep 'approval=true'

# 审批等待分布
grep 'event=approval_decided' app.log | grep -v 'waitedMs=-1' \
 | awk '{for(i=1;i<=NF;i++){split($i,a,"="); v[a[1]]=a[2]} print v["waitedMs"], v["decision"], v["actor"], v["tool"]}' | sort -rn
```

### 3.8 压缩

```bash
grep 'event=compaction' app.log
# 压缩是否频繁触发（同一会话连续多行）说明分母偏小或历史增长过快
grep 'event=compaction' app.log | grep -F "<S>"
```

---

## 4. 常见故障模式 → 日志行

| 现象 | 先看哪行 | 判据 |
|---|---|---|
| 用户说"点了没反应" | `turn_accepted` 后面**没有** `turn_start` | 该轮没跑起来：看是否紧跟 `turn_rejected`（池饱和）；否则看 `AI 轮次未捕获异常` 的 ERROR |
| "一直在转圈" | 有 `turn_start`、**无** `upstream_first_byte` | 首包从未到达（首包超时 / 上游不可用），随后应出现 `turn_retry` 或 `turn_end(status=failed, code=UPSTREAM_FIRST_BYTE_TIMEOUT)` |
| "等很久才出字" | `upstream_first_byte.ms` 大 | 上游慢；与 `totalMs` 对比确认不是工具/审批占用 |
| "卡在确认卡上" | `approval_suspend` 之后**无** `approval_decided` | 卡还挂着（无上限）。`approval_decided.waitedMs` 大 = 人决策慢 |
| "审批通过了却没执行" | `approval_decided(decision=approve)` 后**无** `tool_call_end`，或 `turn_end.status=cancelled` | 被取消（`turn_cancelled`）或进程在两次写之间退出（看台账/续跑） |
| "工具失败但界面说成功" | `tool_call_end.ok=false` 与帧不同源 | 以 `outcome/code` 为准；帧的 `ok` 是 `outcome` 派生值 |
| "回答被截断" | `turn_end.chars` 小于预期 + `code` 带 `UPSTREAM_STREAM_INCOMPLETE` | 断流；看 `turn_retry.reason` 与既有 `末帧序列=` 细节行 |
| "上下文突然变短" | `compaction.level=L3` / `lostTokens>0` | 硬裁剪丢了历史；看 `reason` 与 `/api/ai/context/{sessionId}?ledger=1` |
| "同一个会话开不了新一轮" | 409 `SESSION_BUSY` 响应体里的 `runId` | 用该 runId grep：多半停在 `approval_suspend`（上一轮还挂着） |
| "重启后自己跑起来了" | `turn_start.resumed=true` + `启动自动续跑` 行 | 启动扫描接管；用 runId 看上一轮停在何处 |
| "慢，但 CPU/上游都不忙" | `turn_end.gateWaitMs` 占比高 | 人机等待；不是性能问题 |

---

## 5. 与端点配合

日志给"骨架与耗时"，端点给"原料"。按 runId 串起来用：

| 端点 | 何时用 | 与日志的对应 |
|---|---|---|
| `GET /api/ai/runs/{runId}` | 要看挂起详情、台账（工具执行真值）、待决条目、键名 | `tool_call_end.outcome/code` 与台账同源；`keys` 段给出可直接 `redis-cli` 的键名 |
| `GET /api/ai/events/{runId}?lastSeq=0` | 要逐帧回放（正文、工具入参/出参全文） | 日志刻意**不**记正文 —— 需要内容时只能从这里取 |
| `GET /api/ai/runs/current?sessionId=` | 挂起轮重建 | 与 `approval_suspend` 的 `toolCallId` 对得上 |
| `GET /api/ai/pending/{runId}` | 当前卡住的是哪张卡 | 对应 `approval_suspend` |
| `GET /api/ai/context/{sessionId}?ledger=1` | 上下文计量与压缩台账 | 与 `compaction` 行同源（`level/before/after/lostTokens/reason`） |
| `GET /api/ai/health` | 池/队列/租约/Redis 可用性 | 出现 `turn_rejected` 时先看 `sseDelivery` 与 `available` |
| `POST /api/ai/runs/{runId}/resume` | 日志显示"挂着但等不到人" | 续跑后应看到新的 `turn_start(resumed=true)` |

### 典型组合

```bash
# 1) 日志定"慢在哪一段"
grep -F "<R>" app.log | grep 'event=' | grep -E 'turn_start|upstream_first_byte|approval_|turn_end'
# 2) 端点定"内容是什么"（正文/入参/出参）
curl -s "$BASE/api/ai/events/<R>?lastSeq=0" | jq '.frames[] | select(.type=="tool_start") | {name, args}'
# 3) 台账定"到底执行了几次"
curl -s "$BASE/api/ai/runs/<R>" | jq '.ledger'
```

---

## 6. 安全边界（硬约束）

- 事件行**只记元数据**：标识、计数、耗时、状态码、工具名、规则名、token 数。
- **不记**：用户问句、模型答复、工具入参 JSON、工具出参全文、密钥片段。
- 既有 `ai_feedback` 的正文摘录是人工复盘用的**库内**快照；日志**不复刻**这条路径。
- 需要正文时走 `/api/ai/events/{runId}`（24h 内）或记忆窗口 `/api/ai/history/{sessionId}`，不要指望日志。
- 单轮新增 INFO 行上限约 15 行；事件**不在** delta 循环里打（只在边界与终态）。

---

## 7. 字段速查（一行）

```
event=turn_end runId=.. sessionId=.. status=done code=none attempts=1 totalMs=8421 activeMs=7719
              gateWaitMs=702 toolCalls=2 chars=418 inputTokens=5120 outputTokens=302 totalTokens=5422
              est=false model=deepseek-chat upstreamEvents=37
```
