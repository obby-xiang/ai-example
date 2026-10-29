# AI 对话日志链路追踪手册

- 版本：2026-10-22（AI 日志最小补齐批；首版 2026-10-21 = AI 可观测性专项 · 日志链路追踪批）
- 适用基线：`main` 之后含 `feature/ai-logtrace` 的后端
- 读者：排障值班、开发、评审
- 定位：**只凭日志就能还原一轮 AI 对话**。帧（SSE）与 Redis 键族仍是真值；日志是本批新增的**可 grep 的骨架**
- 本批增量（2026-10-22）：新增 3 个事件（`turn_denied` / `turn_exception` / `sse_detach`）、补齐 6 处字段（`turn_end` 的排队/跨段/可信度、`turn_retry` 的单次尝试、`tool_call_end` 的 `execMs`、`approval_decided` 的 `kind/timeoutS/suspendMs`、`turn_start` 的 `resumeReason`、行首第三列 `instanceId`），并订正首版 6 处口径出入（`memory_window` 补登、`upstream_first_byte` 两字段语义、`approval_decided` 与 suspend 分列、`turn_end.status` 取值域、`tool_call` 成对性实况）
- 本批增量（**2026-10-26**，AI 交互整改批次 0–8 后的文档同步）：**只追加 §8**（`turn_end.status` 五值含 `unconfirmed`、新增 `turn_parked` / `turn_empty_retry`、`turn_accepted.chars` 去 DLP 措辞、三类载体保留期、压缩日志面边界、挂起 / 准备中排障判据）。上文 §0–§7 **未改**；冲突处以 §8 为准。

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
| **行首锚点**（MDC） | 级别之后紧跟 `runId` `sessionId` `instanceId` 三列（无锚点的行这三列都为空） | `grep <runId>` 即可捞到这一轮**所有**日志，包括既有 WARN/ERROR 细节行；`instanceId` 回答"多实例下这是哪个进程写的" |
| **结构化事件**（`event=`） | `event=<名> runId=.. sessionId=.. k=v k=v`，INFO，单行 | 按事件名聚合、按字段切片、机器可读 |

行首锚点由 `logging.pattern.level`（`backend/src/main/resources/application.yml`）渲染：

```
%5p %X{runId:-} %X{sessionId:-} %X{instanceId:-}
```

实测行形（三段前缀之外与批之前逐字相同）：

```
2026-10-22T10:12:03.311+08:00  INFO 1f9c...e2  s-42  instance-hostA-pid27140-9f3c1a 27140 --- [ai-suspend-3] c.e.c.ai.o.AiTrace : event=turn_end runId=1f9c...e2 sessionId=s-42 status=done ...
```

> 锚点为空时不渲染占位符 —— 非 AI 日志的行形与批之前一致，既有按行首匹配的脚本不受影响。
>
> **`instanceId` 的取值** = `InstanceId.get()`（`instance-<host>-pid<pid>-<6 位随机>`，进程内恒定），随 `AiTrace.bind` 进 MDC。它只在**绑过锚点的线程**上出现：非 AI 日志不会凭空多出一列。
>
> **旁路线程的补绑**：`AiTrace.eventOn` 的显式锚点现在会在发射期临时补绑 MDC（发射后还原）。此前 `compaction` / `approval_decided` 这类"由旁路线程发射"的事件出现过"事件体里 `runId=xxx`、行首却空白"的自相矛盾形态 —— 按行首 grep 的脚本捞不到它。

---

## 2. 事件字典

字段约定：所有事件都带 `runId` / `sessionId`（取不到渲染 `-`）；值一律单行、超 160 字符截断；**任何事件都不含对话正文、工具入参、工具出参全文**。

> **`-1` 与 `none` 的分工**（本批统一）：`none` = 语义上不存在/无上限；`-1` = 该测但没测到（缺失/未记录）。两者都不写 `0` —— `0` 是"测到了，值就是 0"。

### 2.1 `turn_accepted`（INFO，HTTP 入口）

| 字段 | 含义 |
|---|---|
| `context` | 页面上下文（`page` 名；无则 `none`） |
| `model` | 请求指定的模型（未指定 = `default`，实际模型见 `turn_start`） |
| `chars` | 归一 + DLP 之后的输入字符数 |

> 本行是 `queueMs` 的**起点**（受理时刻另行记在 MDC，见 §2.8 的 `queueMs`）。

### 2.2 `turn_start`（INFO，一次一轮，必有）

| 字段 | 含义 |
|---|---|
| `model` / `provider` | 本轮实际使用的模型与端点 |
| `attempt` | 恒为 `1`（重试另见 `turn_retry`） |
| `resumed` | `true` = 续跑（挂起后重连、重启接管、死卡兜底）；`false` = 首轮 |
| `resumeReason` | 续跑原因：`none`（首轮）/ `startup`（启动扫描）/ `manual`（运维或用户显式调 `POST /api/ai/runs/{runId}/resume`）/ `deadcard`（启动扫描接管"状态仍 RUNNING 但已超龄"的僵尸轮）/ `reattach`（其余续跑入口：决策回灌、挂起重挂、调试直驱 —— **未标注原因的兜底口径**）。首轮固定写 `none`，不省略字段（两种口径混用会让 grep 漏行） |
| `context` | 上下文键（`page:tasks` / `task:IMPORT/PUBLISH` …） |
| `tools` / `inputEst` | 披露工具数 / 输入估算 token（续跑行为 `history`） |
| `compact` | 本轮起点时的压缩级别（`none`/`L1`/`L2`/`L3`） |
| `maxAttempts` / `budgetMs` | 最大尝试次数 / 总预算（毫秒，不含人工等待） |

### 2.3 `upstream_first_byte`（INFO，每次尝试最多一行）

| 字段 | 含义 |
|---|---|
| `attempt` | 第几次尝试 |
| `ms` | 从"本次尝试开始组装请求"到"第一条上游分片到达"的毫秒数 |
| `chars` | **落定时刻**的正文字符数（不是首包那一瞬的值：本行在尝试落定后才写出，见实现注） |
| `events` | **采到的非空 usage 帧数**（`ContextMeter.frames()`），不是"上游事件总数"；上游事件总数见 `turn_retry.upstreamEvents` / `turn_end.upstreamEvents` |

> 首包超时（`UPSTREAM_FIRST_BYTE_TIMEOUT`）时本行**不会出现** —— "没有首包行"本身就是判据。

### 2.4 `turn_retry`（INFO，可重试失败时）

| 字段 | 含义 |
|---|---|
| `attempt` / `nextAttempt` | 本次失败序号 / 下一次序号 |
| `code` | 断流分类（`UPSTREAM_CONNECTION_BROKEN` / `UPSTREAM_STREAM_INCOMPLETE` / `UPSTREAM_STREAM_ERROR` …） |
| `reason` | 归类时的说明（≤160 字符，单行） |
| `emitted` / `sideEffect` | 是否已产出正文 / 是否已有工具副作用（决定能否重试） |
| `attemptMs` | **本次尝试**的耗时（本次尝试开始 → 失败落定） |
| `firstByteMs` | 本次尝试的首包耗时；`-1` = 本次尝试一个分片都没到 |
| `inputTokens` / `outputTokens` | **本次尝试**的 token（`est` 口径同 `turn_end`：取不到上游真值时是估算） |
| `upstreamEvents` / `elapsedMs` | 已收上游事件数 / **本轮**已耗时（不是本次尝试） |

### 2.5 `tool_call_start` / `tool_call_end`（INFO）

| 字段 | start | end |
|---|---|---|
| `toolCallId` | ✓ | ✓（与 start 对齐） |
| `tool` | ✓ 工具名 | ✓ |
| `kind` | ✓ `BACKEND`/`CONFIRM`/`FRONTEND` | ✓ |
| `approval` | ✓ 是否需要人/前端参与 | — |
| `index` | ✓ 本批内序号 | — |
| `ms` | — | ✓ 开始到结算的毫秒数（**含审批等待**） |
| `execMs` | — | ✓ **执行耗时 = `ms` − 审批等待**：只对 `CONFIRM`/`FRONTEND` 扣，扣的是 `reqAtMs → resolvedAtMs`，缺任一端就不扣（宁可少扣，不凭猜造数）；后端工具恒等于 `ms`。**park 结算**（见下）发生在另一个执行体，钟点全取自条目：`ms` 的起点取 `reqAtMs`/`claimedAtMs` 里较早的那个、终点 `executedAtMs → resolvedAtMs → now` 逐级回落；前端工具没有认领 ⇒ `execMs=0`（后端确实没执行它的桩体） |
| `skipped` | — | ✓ `true` = 本次调用**没有执行**（预检拦截 / 步数到顶 / 首错即停的连带跳过） |
| `ok` / `outcome` / `code` | — | ✓ 结局真值（`ok`/`failed`/`unknown`/`blocked`/`skipped`/`cancelled`…） |
| `executed` / `chars` | — | ✓ 是否真的执行过 / 结果字符数（不记内容） |
| `cancelled` | — | 仅在"开始之后被取消/抛出"的行上出现（`true`） |

**成对性口径（本批施工后的实况）**：`tool_call_start` / `tool_call_end` **不是绝对成对**，三种情形：

1. **未执行（有一行 end，没有 start）** —— 预检拦截、步数到顶、批策略连带跳过：只写一条 `tool_call_end skipped=true`。**刻意不补 start**：补了就把"start 没有 end"这个判据稀释成永远成对，本批要堵的洞会被掩掉。
2. **开始之后没走到结算（有一行 start，就必定有一行 end）** —— 取消（事件边界轮询命中）与执行段抛错：现在都在异常穿出**之前**补一条 `tool_call_end`（`outcome=cancelled` + `code=RUN_CANCELLED`，或 `code=TOOL_EXECUTION_THREW`），两者都带 `cancelled`。本批之前这两条路径只有 start。
   **park 结算（2026-10-25 补；同日按独立复审 I-1/I-2 收紧）**：人工等待 park 之后线程已交还，结算可能发生在续跑里，也可能发生在**另一个实例**的决策入口上 —— 因此这一行不再用"本进程有没有等待方"（`woke`）判发不发，而是由**持久首写者**仲裁：发点抢 `ai:observed:<runId>` 的 `HSETNX`（field=`toolCallId`），**抢到才发、且同时记一次审计计数**；后端执行另有 `ai:claim` 认领胜者兜住"恰执行一次"。候选发点 = ① 决策/放弃写胜者（park 开启时一律参与）② 内联批循环 ③ 续跑认领胜者；谁先到谁发，其余自动让位（跨实例双记、多卡乱序 0 条两条序都由这一处收口）。续跑的"复用结果"分支**刻意不补**：重挂/重试会反复经过它，在那里补会把 `readCalls` 与 end 行数放大成"重挂次数"。字段口径见上表 `ms`/`execMs` 的 park 注。
3. **进程死在 start 与 end 之间** —— 仍是"只有 start"，这是**只有日志才能表达**的事实（`ms` 不会有值）。对齐手段 = 快照 `ai:run:<runId>` 的 `status` + 续跑行的 `turn_start(resumed=true, resumeReason=…)`。

### 2.6 `approval_suspend`（INFO，卡发出、等待开始）

| 字段 | 含义 |
|---|---|
| `toolCallId` / `tool` | 哪张卡 |
| `kind` | `confirm`（人工确认）/ `frontend`（前端工具） |
| `timeoutS` | 等待上限秒数；`none` = 无上限（确认门与 `generative_form` 等"等人"工具） |
| `preflight` | 是否带预检报告 |

### 2.6b `approval_decided`（INFO，决策落地；与 suspend 分列）

| 字段 | 含义 |
|---|---|
| `toolCallId` / `tool` | 哪张卡 |
| `kind` | `confirm` / `frontend`（与 `approval_suspend` 同一把尺；条目缺失时 `none`） |
| `timeoutS` | 本条目等待上限；`none` = 无上限（同上 `suspendWaitSeconds`） |
| `decision` | `approve` / `reject` / `frontend_result` / `frontend_cancel` / `cancel` / `timeout` |
| `actor` | `user`（人拍的板）/ `system`（超时等系统动作） |
| `outcome` | 提交结局（`ACCEPTED`/`DUPLICATE`/`NOT_FOUND`/`REJECTED`） |
| `waitedMs` | **卡送达（`req` 位）→ 决策落地**的毫秒数；`none` = 没有请求时刻（卡从未送达 / 旧数据）。**不再写 `-1`** |
| `suspendMs` | **挂起开始（`arm`，即 `suspended` 帧之前）→ 决策落地**的毫秒数；`-1` = 本实例没记到挂起起点（跨实例决策）。一批多卡时后面的卡 `suspendMs` 明显更长（它在前几张卡的等待里排过队），而 `waitedMs` 只算自己那张卡 |
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

### 2.7b `memory_window`（INFO，仅记忆窗口真改动时）

与 `compaction` **同族不同事件**：`compaction` 回答"模型上下文占比到了哪一级"，`memory_window` 回答"读进来的历史为什么变短了"。混成一条会把"窗口本来就这么长"误读成"压缩把历史吃掉了"。

| 字段 | 含义 |
|---|---|
| `reason` | `turn_trim`（按轮淘汰）/ `tool_result_clearing`（先清旧工具结果）/ `nothing_to_trim`（判过但没动；本行只在真改动时发） |
| `before` / `after` | 消息条数（处理前 / 后） |
| `tokensBefore` / `tokensAfter` | 历史 token 估算（处理前 / 后） |
| `budget` | `app.ai.memory.token-budget` 的实际值（0 = 关闭预算、只按条数） |
| `droppedTurns` / `clearedToolResults` | 淘汰的轮数 / 清掉的旧工具结果条数 |
| `stillOver` | `true` = 处理完仍超预算（触到条数下限 `min-messages` 或只剩最后一轮）—— 这时该看 `compaction` 是否接手 |

### 2.8 `turn_cancelled` / `turn_end`（INFO，一轮一行）

`turn_cancelled`：`attempts` / `partialChars` / `upstreamEvents`。取消后紧随 `turn_end`。

`turn_end` 字段（**唯一终态出口**）：

| 字段 | 含义 |
|---|---|
| `status` | 只有四值：`done` / `failed` / `blocked` / `cancelled`。**没有 `empty_reply`** —— 空回复 = `failed` + `code=EMPTY_REPLY`（模型没给出任何正文） |
| `code` | 失败码（成功为 `none`）；含 `_AFTER_TOOL_SIDE_EFFECT` / `_AFTER_PARTIAL` 后缀说明副作用/半截正文 |
| `attempts` / `attempt` | 尝试次数 / 终态所在尝试序号（当前同值：前者给"跑了几次"，后者与 `turn_start`/`turn_retry` 的 `attempt` 同名同口径） |
| `totalMs` | **本段**墙钟总耗时（段起点见"段级预算重置式"） |
| `activeMs` | **机器耗时** = `totalMs - gateWaitMs`（与总预算同一口径） |
| `gateWaitMs` | 人工等待累计（确认门 + 前端工具挂起） |
| `queueMs` | **排队耗时** = `turn_accepted` 那一行 → `turn_start` 这一行的间隔；`-1` = 没有受理标记（续跑 / 直接驱动）。刻意与 `activeMs` 分开：这一段落在 `activeMs` 之外，"提交后迟迟没开始"不该读成"模型慢"。**口径**：该窗口含"开始前的本地装配"（记忆窗口读 + 请求前压缩 + 快照写）—— 它们既不是模型也不是工具；要再拆出纯池排队需要第三个时间戳，本批不加 |
| `runTotalMs` | **跨段总时长** = run 创建（`RunSnapshot.createdAtMs`）→ 终态；`-1` = 读不到快照。挂起/续跑后本段时钟被重置，只有这个数回答"这个 run 从我点发送到现在多久" |
| `toolCalls` | 本轮开始过的工具执行次数 |
| `chars` | 产出正文字符数（不记内容） |
| `inputTokens` / `outputTokens` / `totalTokens` | 三维 token（无上游真值时按估算） |
| `est` | `true` = 上述为估算值 |
| `frames` / `dirty` | usage 可信度：采到的非空 usage 帧数 / 被判脏回退的帧数（`est=true` 时这两个数是唯一佐证） |
| `model` / `provider` | 上游回显模型（回落本轮解析值）/ 本轮 provider（与 `turn_start.provider` 同源） |
| `upstreamEvents` | 已收上游事件数 |

### 2.9 `turn_rejected`（INFO，挂起池饱和）

`code=SUSPEND_POOL_SATURATED`。出现在 `turn_accepted` 之后、`turn_start` 之前 —— 该轮**没有真正开始**。

### 2.10 `turn_denied`（INFO，请求在"开始之前"被拒）

**本批补的最大盲区**：所有拒绝都发生在 `AiTrace.bind` 之前（既无 `turn_accepted`，也不会有 `turn_end`），此前日志里一行不留。

| 字段 | 含义 |
|---|---|
| `status` | HTTP 状态（400 / 409 / 429 / 503） |
| `code` | 稳定机器码，**与响应体同源**：`AI_UNAVAILABLE` / `AI_KEY_MISSING` / `KEY_UNREADABLE`（503）、`REDIS_UNAVAILABLE`（503）、`RATE_LIMITED`（429）、`SESSION_BUSY`（409）、`MODEL_NOT_FOUND` / `INVALID_PARAM` / `MESSAGE_TOO_LONG` / `BAD_REQUEST`（400） |
| `runId` | **只有两种情况有值**：409 会话忙 = **占着会话的那一轮**（与 `heldBy` 同值，那才是要看的那条链）；其余渲染 `-`（"这次请求还没有轮次"本身是信息） |
| `sessionId` | 能取到就带（必填校验失败时可能也为 `-`） |

各拒绝点追加的少量字段：`reason`（`ai_unavailable` / `redis_unavailable` / `missing_session_or_message` / `empty_after_sanitize` / 参数越界原文）、`chars`+`limit`（超长 / 归一后为空）、`scope`+`used`+`limit`+`retryAfterS`（429）、`model`（模型不存在）、`heldBy`+`degraded`（409）。

### 2.11 `turn_exception`（INFO，环外未捕获异常 = 终态行）

`code=UNCAUGHT` + `type`（异常类简名）+ `msg`（异常消息，单行截断）。覆盖"压缩 / 记忆写 / 快照建 / 取模型"这些跑在**尝试环之外**、异常一路穿到控制器 catch 的位置。

> 与 `turn_end` 的关系：一轮**必有**一条终态行 —— 环内失败走 `turn_end`，环外抛出走 `turn_exception`。按 runId 只看到 `turn_start` 而**没有任何**终态行，才是真正的"进程死了"。

### 2.12 `sse_detach`（INFO，订阅者被摘）

| 字段 | 含义 |
|---|---|
| `kind` | `write_timeout`（单帧写出超预算）/ `evicted`（其它摘除：队列溢出、写出被中断等） |
| `reason` | 摘除原因（`write_timeout` / 自由文本，≤160 字符） |
| `budgetMs` | 写预算（仅 `write_timeout` 有） |
| `writeTimeouts` / `subscribers` | 进程内累计写超时次数 / 当前订阅者数（**process 口径**，多实例要手工相加） |

> 摘除 ≠ 本轮失败：前端会带 `lastSeq` 重挂差量补发。它是"这一轮被摘过几次"的唯一可聚合痕迹。

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
 | awk '{for(i=1;i<=NF;i++){split($i,a,"="); v[a[1]]=a[2]} print v["activeMs"], v["totalMs"], v["gateWaitMs"], v["queueMs"], v["runTotalMs"], v["status"], v["runId"]}' \
 | sort -rn | head -20
```

三个时间口径别混：`queueMs`（受理→开始）/ `activeMs`（机器，扣人工等待）/ `runTotalMs`（run 创建→终态，跨挂起与续跑）。

### 3.5 谁的等待耗在人身上

```bash
grep 'event=turn_end' app.log \
 | awk '{for(i=1;i<=NF;i++){split($i,a,"="); v[a[1]]=a[2]} if (v["gateWaitMs"]+0 > 0) print v["gateWaitMs"], v["activeMs"], v["runId"]}' \
 | sort -rn | head
```

`gateWaitMs` 占比高 = 不是模型/工具慢，是在等人（或等前端回执）。

### 3.5b 排队 vs 机器 vs 人（三段拆解）

```bash
# queueMs 高的轮：请求在池子里等过 —— 去看池/并发，不要去查模型
grep 'event=turn_end' app.log \
 | awk '{for(i=1;i<=NF;i++){split($i,a,"="); v[a[1]]=a[2]} if ((v["queueMs"]+0) > 500) print v["queueMs"], v["activeMs"], v["runId"]}' | sort -rn | head
```

### 3.5c 被拒绝的请求（`turn_denied`）

```bash
# 按机器码统计拒绝面（429 / 409 / 400 各占多少）
grep 'event=turn_denied' app.log \
 | awk '{for(i=1;i<=NF;i++){split($i,a,"="); v[a[1]]=a[2]} print v["status"], v["code"]}' | sort | uniq -c | sort -rn

# 429 的退避建议（retryAfterS）
grep 'event=turn_denied' app.log | grep 'code=RATE_LIMITED'
```

### 3.6 重试与首包

```bash
grep -E 'event=(turn_retry|upstream_first_byte)' app.log
# 某轮"一次尝试都没有首包行" ⇒ 首包超时或连接未建立
grep -F "<R>" app.log | grep -c 'event=upstream_first_byte'
```

### 3.7 工具面

```bash
# 某轮工具清单与耗时（execMs = 扣掉审批等待的执行时间；ms 含等待）
grep -F "<R>" app.log | grep 'event=tool_call_end' \
 | awk '{for(i=1;i<=NF;i++){split($i,a,"="); v[a[1]]=a[2]} print v["execMs"], v["ms"], v["tool"], v["outcome"], v["code"]}' | sort -rn

# 需要审批的工具
grep -F "<R>" app.log | grep 'event=tool_call_start' | grep 'approval=true'

# 没执行的调用（预检拦截 / 步数到顶 / 首错即停连带跳过）
grep -F "<R>" app.log | grep 'event=tool_call_end' | grep 'skipped=true'

# 审批等待分布（waitedMs 缺失写 none，不再是 -1）
grep 'event=approval_decided' app.log | grep -v 'waitedMs=none' \
 | awk '{for(i=1;i<=NF;i++){split($i,a,"="); v[a[1]]=a[2]} print v["waitedMs"], v["suspendMs"], v["decision"], v["actor"], v["tool"]}' | sort -rn
```

`waitedMs` 与 `suspendMs` 的差别就是"卡送达 → 决策"与"挂起开始 → 决策"：一批多卡时后者更长（后面的卡在前面的等待里排过队）。

### 3.8 压缩

```bash
grep 'event=compaction' app.log
# 压缩是否频繁触发（同一会话连续多行）说明分母偏小或历史增长过快
grep 'event=compaction' app.log | grep -F "<S>"
```

### 3.8b 记忆窗口（`memory_window`）

```bash
# 历史为什么变短：是窗口淘汰（本事件）还是压缩（compaction）？
grep 'event=memory_window' app.log | grep -F "<S>"
# 淘汰了还超预算 ⇒ 该看 compaction 是否接手
grep 'event=memory_window' app.log | grep 'stillOver=true'
```

### 3.8c SSE 摘除（`sse_detach`）

```bash
# 这一轮被摘过几次、为什么被摘
grep -F "<R>" app.log | grep 'event=sse_detach'
```

---

## 4. 常见故障模式 → 日志行

| 现象 | 先看哪行 | 判据 |
|---|---|---|
| 用户说"点了没反应" | **先找有没有 `turn_denied`**；有 `turn_accepted` 却**没有** `turn_start` | ① 有 `turn_denied` = 请求在开始前就被拒（码见 `code`：429 限流 / 409 会话忙 / 400 模型或长度 / 503 不可用）② 没有它但有 `turn_accepted` = 跑起来了却停在半路：看 `turn_rejected`（池饱和）或 `turn_exception`（环外异常） |
| 一轮日志有 `turn_start` 却**没有任何终态行** | 既无 `turn_end` 也无 `turn_exception` | 进程在环内死了（或日志被截断）；对齐 `ai:run:<runId>` 的 status 与续跑行的 `resumeReason` |
| "提交后半天才开始跑" | `turn_end.queueMs` 大（`activeMs` 不大） | 排队/池等待，不是模型慢 |
| "这个 run 从我发到现在多久" | `turn_end.runTotalMs` | 跨挂起与续跑的墙钟总时长（`totalMs` 只是本段） |
| "这一轮怎么老掉线" | `sse_detach` 行 | `kind=write_timeout` = 单帧写出超预算（客户端太慢/网络差），前端会重挂补差量；不是本轮失败 |
| "一直在转圈" | 有 `turn_start`、**无** `upstream_first_byte` | 首包从未到达（首包超时 / 上游不可用），随后应出现 `turn_retry` 或 `turn_end(status=failed, code=UPSTREAM_FIRST_BYTE_TIMEOUT)` |
| "等很久才出字" | `upstream_first_byte.ms` 大 | 上游慢；与 `totalMs` 对比确认不是工具/审批占用 |
| "卡在确认卡上" | `approval_suspend` 之后**无** `approval_decided` | 卡还挂着（无上限）。`approval_decided.waitedMs` 大 = 人决策慢 |
| "审批通过了却没执行" | `approval_decided(decision=approve)` 后**无** `tool_call_end`，或 `turn_end.status=cancelled` | 被取消（`turn_cancelled`）或进程在两次写之间退出（看台账/续跑） |
| "工具失败但界面说成功" | `tool_call_end.ok=false` 与帧不同源 | 以 `outcome/code` 为准；帧的 `ok` 是 `outcome` 派生值 |
| "回答被截断" | `turn_end.chars` 小于预期 + `code` 带 `UPSTREAM_STREAM_INCOMPLETE` | 断流；看 `turn_retry.reason` 与既有 `末帧序列=` 细节行 |
| "上下文突然变短" | `compaction.level=L3` / `lostTokens>0` | 硬裁剪丢了历史；看 `reason` 与 `/api/ai/context/{sessionId}?ledger=1` |
| "同一个会话开不了新一轮" | `turn_denied status=409 code=SESSION_BUSY` 的 `heldBy`（= 占着会话的 runId） | 用该 runId grep：多半停在 `approval_suspend`（上一轮还挂着） |
| "重启后自己跑起来了" | `turn_start.resumed=true` + `启动自动续跑` 行 | 启动扫描接管；用 runId 看上一轮停在何处 |
| "慢，但 CPU/上游都不忙" | `turn_end.gateWaitMs` 占比高 | 人机等待；不是性能问题 |
| "工具慢还是人在犹豫" | `tool_call_end.ms` vs `execMs` | 差值 = 审批等待；`execMs` 小 = 工具本身不慢 |
| "token 数可信吗" | `turn_end.est` + `frames` + `dirty` | `est=false` 才是上游真值；`est=true` 时看 `frames`（采到几帧）/`dirty`（几帧被判脏） |

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
event=turn_end runId=.. sessionId=.. status=done code=none attempts=1 attempt=1 totalMs=8421 activeMs=7719
              gateWaitMs=702 queueMs=18 runTotalMs=8610 toolCalls=2 chars=418 inputTokens=5120
              outputTokens=302 totalTokens=5422 est=false frames=3 dirty=0 model=deepseek-chat
              provider=deepseek upstreamEvents=37
```

---

## 8. 现行口径更正（2026-10-26）

> **性质**：本节是 **2026-10-26 AI 交互整改（批次 0–8）**后的**日志面追加 / 更正段**。上文（§0–§7）**一字未改、不回改**，它们是 2026-10-22 日志最小补齐批的成文事实。**凡本节与上文冲突处，以本节为准**；本节不改变"只记元数据、不记正文"的安全边界，也不新增帧类型。
> **依据**：`docs/research/裁决-AI交互整改实施口径-2026-10-26.md` + 各批真实 commit（见 `docs/evidence/ai-interaction-remediation-2026-10-26/README.md`）。
> **边界**：本节描述的是**已入库代码的现行口径**；本轮**未跑**后端 / 前端全量与真实环境验证，不得把本节当"日志面已验证"。

### 8.1 `turn_end.status` 是**五值**，不是四值（更正 §2.8）

| 取值 | 含义 |
|---|---|
| `done` | 正常完成 |
| `failed` | 失败（含 `EMPTY_REPLY`、上游断流、工具异常等；`code` 给具体码） |
| `blocked` | 被门 / 策略阻断（未执行） |
| `cancelled` | 取消 |
| **`unconfirmed`** | **终态暂不可确认**：成功终局已落库，但权威快照无法确认（如 Redis 不可用）⇒ 不写快照、不发 `done`，用 `error{unconfirmed:true}` 收尾 |

- `unconfirmed` **不是普通失败**：它表达"不知道这一轮最终算不算成功"，前端据此落**中性"状态未确认"**视图（重新载入 / 新开一轮），**不得**包装成"可一键重试"。
- 判据仍以**显式标记**为准：`error.unconfirmed=true` 或 `turnEnd=unconfirmed`；不要从 `code` 反推（`code` 仍是真实故障码，例如 `AI_REDIS_UNAVAILABLE`）。
- **`empty_reply` 仍不是 status 值**：空回复 = `failed` + `code=EMPTY_REPLY`（§2.8 原句仍成立）。

### 8.2 新增两个事件：`turn_parked` / `turn_empty_retry`（补 §2）

#### `turn_parked`（INFO，轮进入人工等待挂起态）

| 字段 | 含义 |
|---|---|
| `kind` | `CONFIRM`（人工确认）/ `FRONTEND`（前端工具）；另有 `PERSISTED_TOOL_ROUND` = "挂起工具轮已持久化、等 resume 物化" |
| `toolCallId` | 哪张卡（`PERSISTED_TOOL_ROUND` 行没有该字段） |
| `attempt` / `code` | 仅 `PERSISTED_TOOL_ROUND` 行有：第几次尝试 / 流式违规码 |

> **读法**：`turn_parked` 是**一轮的正常中间态**，不是失败、不是取消。**之后的日志可能长时间空白**（线程已交还），直到决策落地出现新的 `turn_start(resumed=true)` 或 `approval_decided`。**不要**把"有 `turn_parked`、无 `turn_end`"判成进程死了——这是等人在看卡。

#### `turn_empty_retry`（INFO，空回复的"仅模型步"重试）

| 字段 | 含义 |
|---|---|
| `attempt` / `nextAttempt` | 本次空回复的尝试序号 / 下一次序号 |
| `code` | 恒为 `EMPTY_REPLY` |
| `elapsedMs` | 本轮已耗时 |

> **与 `turn_retry` 的分工**：`turn_retry` = **上游断流**类可重试失败（三条件：剩余次数 / 未超机器段预算 / 未产出且无副作用）；`turn_empty_retry` = **零产出空回复**的一次"仅模型步重跑"（不重执行工具、不追加用户消息、同模型、同固定历史）。两者**不可互相替代**，也不共用 `code` 语义。
> 人工触发的 `POST /api/ai/runs/{runId}/retry-model-step` 走**续跑入口**，日志形如 `turn_start(resumed=true, resumeReason=…)`，**不写** `turn_empty_retry`。

### 8.3 `turn_accepted.chars` 不再含 DLP（更正 §2.1）

上文写"归一 + **DLP** 之后的输入字符数"。**DLP 三件套已于 2026-10-24 整体删除**（`AR-01` 明确不恢复），故现行口径 = **归一 / 清洗后的输入字符数**（`modelText.length()`）。事件字段名与位置不变，**不要**再按"DLP 之后"解释该值，也不要据此推断"输入被脱敏过"。

### 8.4 保留期：日志 / 帧面 24h，对话正文不落业务数据库（补 §6）

| 载体 | 现行保留期 | 对排障的含义 |
|---|---|---|
| 日志（本手册对象） | 由部署侧日志轮转决定，**不属本仓 TTL** | 有日志就能回溯；**没有日志就无法用本手册还原** |
| Redis 运行态 / 记忆 / 事件日志 | **24h 滑动 TTL**（活跃即续、无绝对上限） | `/api/ai/events/{runId}` 的逐帧回放**只在 24h 内**可用；超期后帧面归零 |
| 对话正文（业务数据库） | **不落库**：`ai_chat_message` 与完整历史端点已于 2026-10-30 整条移除，表已由 `V10__drop_ai_chat_message.sql` 删除 | **没有 DB 兜底**：`GET /api/ai/history/{sessionId}` 只读 Redis 记忆窗口，键到期即空；`ai_feedback` 仍保留（只有反馈记录，没有正文） |

> **因此**：§6「需要正文时走 `/api/ai/events/{runId}`（24h 内）或记忆窗口」仍然成立，但**要补一句**：24h 之后**既看不到逐帧现场，也看不到对话正文**（业务数据库不再有对话历史，Redis 键已到期）；能留下的只有反馈行（`ai_feedback`）与部署侧日志。不得把"日志里还有"读成"历史还在"。

### 8.5 压缩：日志面无"压缩开始"事件（补 §2.7 / §3.8）

- `compaction` 行**仍只在真实发生压缩时**写出（判据不变：没压缩不写，可区分"没发生"与"发生 0 次"）。
- 本轮首帧前移后，**压缩期的可见性由帧承担**：`out.start` 仍是首帧，紧随 `phase=compacting` 的**阶段心跳帧**，面板显示"正在压缩·已用 N 秒"；**日志面没有新增"压缩开始 / 结束"事件**。
- **不要**用"日志里没有 `compaction` 行"推断"没有压缩期等待"——零帧窗口的判据是**帧与前端阶段超时**（connect 15s / first-frame 30s / idle 45s），不是日志。
- `compaction` 行的字段（`level`/`reason`/`before`/`after`/`savedTokens`/`lostTokens`/`turns`/`clearedPairs`/`summaryChars`/`limit`）**不变**；摘要仍使用**主模型**（`summary-model` 已删键，`AR-11`）。

### 8.6 等待上限的字段口径（补 §2.6 / §2.6b）

| 工具 / 卡 | `timeoutS` 取值 | 说明 |
|---|---|---|
| 确认门（`CONFIRM`） | **`none`** | 等**人**，**无上限**；`confirm-timeout` 已退役 |
| `generative_form` 等"等人"前端工具 | **`none`** | 等**人**填表，无上限 |
| 即时执行类前端工具（`navigate_to` 等） | **`120`** | 等**机器**回执的后端兜底；只决定"系统何时收摊" |

- `approval_suspend.timeoutS=none` **不再**表示"无超时配置"这种异常态，而是**设计口径**；`approval_decided.timeoutS` 与之同尺。
- `waitedMs` / `suspendMs` 的 `-1` / `none` 分工**不变**（§2.6b）。

### 8.7 挂起 / 准备中在日志上的样子（新增排障判据）

| 现象 | 日志形态 | 判据 |
|---|---|---|
| 卡在确认 / 表单上 | `approval_suspend`（或无该行，取决于卡类型）→ **`turn_parked`** → 长时间无终态行 | **正常**：线程已交还，等人在看卡；决策后应出现 `approval_decided` 与新的 `turn_start(resumed=true)` |
| 决策后没续跑 | `approval_decided` 有，之后**无** `turn_start(resumed=true)` | 续跑没被驱动（看成对：`runId` / `toolCallId`）；对齐 `/api/ai/runs/current` 的待决条目 |
| "连上了但一个字都没来"（首轮压缩窗口） | 只有 `turn_accepted` / `turn_start`，**没有** `turn_end`；HTTP 侧重挂可能拿到 **202 preparing** | 压缩期零帧窗口；前端阶段超时与独立保活负责可见性；**不要**判成进程死 |
| 终态"说不清" | `turn_end status=unconfirmed` + `error` 帧带 `unconfirmed=true` | 不是普通失败：不写快照、不发 done；按中性"状态未确认"处理 |
| 空回复 | `turn_empty_retry` 一次，第二次仍空 ⇒ `turn_end status=failed code=EMPTY_REPLY` | 只在零产出、零副作用时才会看到重试行 |

### 8.8 grep 菜谱补充（接 §3）

```bash
# 挂起与准备中：这一轮进过几次人工等待
grep -F "<R>" app.log | grep -E 'event=(turn_parked|approval_suspend|approval_decided)'

# 空回复的仅模型步重试
grep -F "<R>" app.log | grep 'event=turn_empty_retry'

# 终态不可确认的轮
grep 'event=turn_end' app.log | grep 'status=unconfirmed'
```

### 8.9 不得据日志面声称的事项

1. **日志不能替代帧与记忆窗口**：正文 / 工具入参出参仍只在帧与 Redis 记忆窗口里；日志**永远不记**这些内容。
2. **24h 之后帧面归零**：不能凭"日志还在"声称"这一轮还能逐帧回放"；对话正文也不在业务数据库里（`ai_chat_message` 与完整历史端点已移除），**失败现场不可恢复**。
3. **日志面无压缩进度**：`compaction` 行只回答"压过没有、压到哪一级"，不回答"当前正压到哪一步"。
4. **单点验证 ≠ 全量**：本轮未跑后端 / 前端全量与真实环境；本手册描述的字段与事件不因"代码已入库"而成为"已验证"。
