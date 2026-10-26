<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-133 · 其它 · 1. `Stop-Child -Name`（`:200-231`）（604b3bc4 / agent-1310）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`b23l1-dual-instance`、`AiController.java`、`ConfirmGate`、`ResumeService.java`。
> **稿态**：历史中间稿 —— 该 wire 第 `m242` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt14` 条有正文的 assistant 消息（共 23 条有正文），末条有正文消息为 `m386`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `604b3bc4` / agent `agent-1310`（`main` = 主会话）；原字符 4,745（链内 2 段、合计 9,306）。
> **链号 / 类别**：`MT-133` / **其它**；判定：助手结论/验收记录（标题无主题词）；**不是用户批准**。
> **轮次**：位于该 wire 第 5 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `0%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `4%`。
> **与末条归档（138 件）的关系**：末条 `m386`（2,759 字符）未入末条归档。
> **脱敏**：路径 1 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

### 〔段 1／2 · assistant 消息 m242 · 4,745 字符〕

批② 用例已全部落地（只改 `scripts/b23l1-dual-instance.ps1` + README §3），SYNTAX_OK，未 commit、未运行 harness、未起停服务。

## 1. `Stop-Child -Name`（`:200-231`）
- 按 name 精确找子进程；kill 前用 `Get-Process -Id` 与 `$proc.StartTime` 比对（复审 M6），进程已退出或 PID 已复用则**跳过误杀**；否则 `taskkill /PID <id> /T /F`，兜底 `$proc.Kill()`。
- 杀完 **从 `$script:children` 移除**并单独回收事件订阅/Writer/Process 句柄（`Remove-ChildResources`，`:164-175`），finally 不再二次杀。
- `-NoStart` 下 `instance-kill` 直接 `Skip`（原因：实例不是脚本起的，`Stop-Child` 只动自己的子进程）。

## 2. `suspend-decision`（`:651-695`）
- 复用新抽出的挂起骨架 `New-SuspendedExportRun`（`:465-549`，建任务→select-defs→context→start_export→读 confirm_request，返回 `runId/toolCallId/lastSeq/sessionId`）。
- 决策 POST 打 **B**：断言 `accepted=true`、`resumeOutcome=LEASE_HELD_ELSEWHERE`、`wokeInProcessGate=false`。
  字段依据：`AiController.java:250-263`（accepted / wokeInProcessGate / resumeTriggered / resumeOutcome），`ConfirmGate.java:1127-1134`（租约被占 → `LEASE_HELD_ELSEWHERE`），等待方持租见 `ConfirmGate.java:419-424`。
- 从 B `?lastSeq=0` 全量回放等终帧：`tool_start`/`tool_result` 各**恰 1 帧**（挂起前的 tool_start 也在归档里，全量才能证明"续跑恰一次"）；`confirm_request` 前的增量不参与计数。
- A、B 双侧同 `?lastSeq` 重挂都收到 `done`。

## 3. `suspend-decision-concurrent`（`:697-743`，可选用例）
- 两路 POST（A/B）用同一 **发令墙钟**（`Start-PostJob ... $fireAt`，`:407-434`）真正并发，避免 `Start-Job` 启动耗时把并发变成先后。
- 断言：恰一个 **200 + accepted**、恰一个 **409 + `code=DUPLICATE_TOOL_CALL_ID`**（依据 `AiController.java:246-248` 与 `duplicateToolCall` 体 `:858-868`）；`confirm_decision`/`tool_start` 各恰 1 帧；终帧到达。失败时把两个作业的错误流原文一并报出。

## 4. `instance-kill`（`:745-811`）
- A 挂起 → `Stop-Child 'instance-a'`（真杀，`taskkill /T`）→ B 的 `GET /api/ai/runs/current?sessionId=` 断言 `found/runId/snapshotStatus=SUSPENDED/awaitingExternal=true/pendingEntries` 含该 toolCallId（字段依据 `AiController.java:458-505`）。
- 决策打 B：`accepted=true`，`resumeOutcome ∈ {LEASE_HELD_ELSEWHERE, SCHEDULED}`（首跳允许 A 的 30s 租约未过期）。
- 用 `Read-Sse` 等终帧，预算 `-KillTakeoverTimeoutSec`（新增，默认 90s）：B 的延迟重试（默认 35s）在租约过期后抢租接管。
- 断言：全量回放 `tool_start` 恰 1；`GET /api/ai/runs/{runId}` 的 `ledger` 该 toolCallId 恰 1 条且 `executedBy=B 的 instanceId`、`ledgerCounts.start_export=1`（字段依据 `ResumeService.java:198-217`、`RunStore.java:735-748`、健康体 `AiController.java:786`）。

## 5. `lease-scripts`（`:813-910`，可选、终止性）
- `/health.leaseOk=true`（`AiController.java:789`）为前置。
- 真 Redis 只读取证（`Invoke-RedisCommand`，`:437-460`，RESP `GET`/`PTTL`，只读不写）：
  - **ACQUIRE**：挂起后 `keys.lease`（`ResumeService.describe`）在 Redis 存在；
  - **RENEW**：等待 12s 后 `PTTL > 22000`（等待方每 10s 续租，未续租只剩 ~18s）；
  - **FENCED-SET**：终态快照 `runEpoch > 0`（`RunStore.java:163-170` 的 `LEASE_FENCE_SET`）；
  - **RELEASE**：终态后该租约键消失。
- 并发两路 `POST /api/ai/runs/{runId}/resume`（同发令时刻）：断言恰一个 **202**、一个 **409**（reason ∈ LEASE_HELD_ELSEWHERE/IN_PROGRESS/ALREADY_RUNNING；依据 `AiController.java:552-559`、`ResumeService.java:114-123`），并断言接管后 `tool_start` 恰 1、台账 `executedBy=B`。
- 覆盖映射：ACQUIRE=挂起期 GET 存在 + 并发抢租；RENEW=PTTL 回升；FENCED-SET=runEpoch>0；RELEASE=终态键消失。耗时 ~45-55s（等续租 + 租约过期），**会 kill A，须单独跑或排最后**（README 已注明）。

## 6. 默认集与参数
- `-Cases` 默认改为 `reattach-full-replay,reattach-diff,suspend-reattach-wake,suspend-decision,instance-kill`（`:60`）。
- 新增 `-KillTakeoverTimeoutSec 90`（`:63`，文档 `:42-45`）。
- `pendingCases` 只剩 `resource-lock`/`idempotency`，文案改「批③」（`:911-914`）。
- 新增 `Count-Frames` / `Get-Percentile` / `Start-PostJob` / `Invoke-RedisCommand`（`:388-460`）。

## 7. P95 帧延迟（L-5）
- 后台长连接作业给每帧打客户端收帧时刻 `_recvAtMs`（`:606-612`）；主线程在 `confirm POST` 返回后取 `$approveAtMs`，样本 = 首续跑帧收帧 − confirm 完成（`:626-636`）。
- 摘要前打印：`帧延迟（A confirm 完成 → B 首续跑帧）：P50=<x>ms P95=<x>ms 样本=N（样本少时 P50=P95=单值）`（`:993-999`）。

## 附带的两个必要修复
- **作业返回形状**：原 `return , @($out)` 在 `Start-Job` 里会被序列化成**单个数组对象**，主线程拿到的 `type/seq` 会变成数组（一旦走到断言就崩）。改为逐帧输出（`:616-619`），离线验证：3 帧 → `received=3`，`_recvAtMs` 保留。
- `GetJson`/`PostJson` 增加 `.Result` 返回 `$null` 的显式报错（`:241-268`），避免连接失败退化成空值方法调用。

## 离线验证结果
- `SYNTAX_OK`。
- `Stop-Child`：`按名停止 victim（pid 2680）` → `alive=False children=0`；未知名字抛明确错误；`Stop-Children` 二次调用无副作用。
- 并发 POST 收集：同步发令下 `statuses=200,409`（体分别为 `{"accepted":true}` / `{"code":"DUPLICATE_TOOL_CALL_ID"}`），发令后 ~2.0s 同时返回。
- 作业错误上报：坏 URI → `B 侧 SSE 订阅未就绪：后台长连接作业已结束（state=Failed）：…未取得响应`（2.4s 内，`jobsLeft=0`），不再是 30s 超时。
- RESP 只读：`PING=+PONG`、缺失键 `GET=null`、`PTTL=-2`。
- 百分位：`P50=20 P95=40`，单样本 `7`。

## diff / 自证
```
 scripts/README.md               |  15 +-
 scripts/b23l1-dual-instance.ps1 | 664 ++++++++++++++++++++++++++++++++++++---
 2 files changed, 634 insertions(+), 45 deletions(-)
```
（`scripts/` 下仅这两件；工作树里另有并发的 `backend/` 批②改动，非本 agent 所为。）

自证（agent-1310 `wire.jsonl:5` `profile.bind`）：`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`。

**残余风险**：`lease-scripts` 的租约过期/延迟重试窗口依赖真实时钟（等待方 10s 续租、TTL 30s、retry 35s）；若首跑在该用例上出现"两路都 409 LEASE_HELD_ELSEWHERE 直至预算耗尽"或"延迟重试先接管"，报错会明确指向并发/接管时序，便于一次性调参。默认五用例不受该用例影响。

---

### 〔段 2／2 · assistant 消息 m314 · 4,561 字符〕

批③ 两条用例已从占位提升为实作（只改 `scripts/b23l1-dual-instance.ps1` + README harness 段），`SYNTAX_OK`；未 commit、未运行 harness、未起停服务。

## 用例 1：`resource-lock`（`:838-971`）

**分发**：`-SharedDb` 开 → 跨实例共享库版（`:905`）；未开 → **H3 确定性预置锁变体**（`:852`，仍可跑，不 Skip）。

**H3 变体（默认路径，不依赖 SharedDb）**
1. `New-SuspendedExportRun` 建任务/挂起拿 runId+toolCallId（锁在确认卡之后预置，保证 preflight 不被提前拦掉）；
2. `Invoke-RedisWrite SET ai:lock:job:<taskId>:EXPORT "1|other-instance" PX 60000`；
3. 批准 → 等终帧 → 断言 `tool_result`：`code=RESOURCE_LOCKED`、`status=BLOCKED`、`outcome=blocked`、`executed=false`、`retryable=true`；
4. 被锁调用**不进台账、不建作业**（`ledgerCounts.start_export=0`、`jobs=0`）；
5. `DEL` 预置锁 → **同一 task** 重跑：`executed=true`、`outcome=ok`、台账 1、作业 1；
6. `Wait-RedisKeyGone` 断言终态后锁键不存在；finally 兜底 DEL。

**SharedDb 变体（跨实例双轮竞态）**
- A 建共享任务 + select-defs；`GET B /api/tasks/{id}` 先验 B 可见（不可见即点名 L-12/SharedDb 未生效）；
- A/B 各挂起一轮（同一 taskId，`New-SuspendedExportRun -TaskId` + `-Base`）；
- 两路批准用 `Start-PostJob -fireAtMs` **同刻发令**；
- 断言：两侧都收到 done；`RESOURCE_LOCKED` 帧恰 1（字段同上）；`executed=true` 的 tool_result 恰 1；两 run 的 `ledgerCounts.start_export` 合计 **1**；`GET /api/tasks/{id}/jobs` 作业数 **1**；锁键终态后不存在。

**字段依据**
- `backend/.../run/SseChatEmitter.java:735-755`：`tool_result` 帧含 `ok/executed/status/outcome/code/retryable`；
- `backend/.../tool/ToolOutcome.java:33,99,135-145`：`BLOCKED("blocked")`，仅 `RESOURCE_LOCKED` 映射 blocked 且 `retryable=true`；
- `backend/.../run/LeaseKeys.java:52-55`：键形 `ai:lock:job:<taskId>:<JOBTYPE>`（JOBTYPE 大写归一）；
- `backend/.../job/controller/JobController.java:34-37`：`GET /tasks/{id}/jobs` → `ApiResponse<List<Job>>`（`.data` 数组，无分页参数）；
- `backend/.../run/RunStore.java:790-796`：`ledgerCounts` = 工具名→次数；
- 设计稿 `docs/research/设计-B23L1...:34-35`：W/J/P **执行前 acquire**，冲突=blocked/RESOURCE_LOCKED、不断流。

## 用例 2：`idempotency`（`:973-1040`）

- 挂起 → A 批准 → 等终帧 → 首次执行恰一次（tool_result executed=true、`ledgerCounts.start_export=1`、jobs=1）；
- 用同一 `runId+toolCallId` 向 **B** 再发一次确认 → 断言 **409 + `code=DUPLICATE_TOOL_CALL_ID`**（依据 `AiController.java:246-248` + `duplicateToolCall` 体）；
- 台账仍 1、作业仍 1、全量回放 `tool_start` 仍 1；
- 只读取证幂等索引：`HGET ai:idem:<runId> <runId>:<toolCallId>` 非空（`RunStore.java:304-316` idemKey/idemField、`:1103` 的 `IDEM_PUSH_SCRIPT`）。
- **SharedDb 结论：不需要**。B 侧 `confirm → ConfirmGate.submitDecision → RunStore.pending(runId, toolCallId)` 只读 Redis 的 pending 状态，不触碰任务库；因此本用例在任何模式下可跑。

## SharedDb 实现与回退（`:73`、`:244-270`、`:1214-1258`）

- 新开关 `-SharedDb`；URL = `jdbc:h2:file:<ArtifactDir 绝对路径>/h2/b23l1_shared;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1`，作为单个 java argv 整体加引号（防路径空格）。
- **交错启动**：A 先起并等健康（H2 AUTO_SERVER 服务端就绪）→ 再起 B；任一步不健康直接 `FAIL: SharedDb 启动失败（H2 AUTO_SERVER 共享不可行）…`，并提示改用 H3。
- `-SharedDb` 与 `instance-kill`/`lease-scripts` 同棒时打印警告（杀 A 会断开 B 的共享库连接）。
- L-12 边界已写入 README §3.2。

## `Invoke-RedisWrite`（`:504-524`）

- 仅允许 `SET <key> <value> PX <ms>`（必须带正整数 TTL）与单键 `DEL <key>`；其它命令（GET/INCR/多键 DEL/PX 0）一律抛错；
- 作用域固定为 harness 的 `$RedisHost:$RedisPort`；判定路径仍只用只读 `Invoke-RedisCommand`；
- 另加 `Wait-RedisKeyGone`（`:526-534`）做锁释放轮询。

## 默认集与参数

- 默认 `-Cases` 改为 7 条：`reattach-full-replay,reattach-diff,suspend-reattach-wake,suspend-decision,resource-lock,idempotency,instance-kill`（`instance-kill` 恒最后）。
- `pendingCases` 清空（未知用例仍按 `No` 报）；`resource-lock` 默认 H3、`-SharedDb` 时跨实例版。
- 新增 `-SharedDb`；README 用例表/用法/边界同步更新。

## 离线验证

- `Invoke-RedisWrite`（真 Redis）：`SET PX 5000 → +OK`、`GET=1|other-instance`、`PTTL=4981`、`DEL=1`、删后 GET=null；白名单拒绝 `SET k v` / `GET` / `DEL a b` / `PX 0` / `INCR` 全部按预期抛错。`Wait-RedisKeyGone`：存在时 false，过期后 true。
- `Start-Instance` 参数拼接：非共享 = `.../data/b23l1_instance-a;DB_CLOSE_DELAY=-1`；共享 = `--spring.datasource.url="jdbc:h2:file:<DRIVE>/.../h2/b23l1_shared;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1"`（引号、Redis 参数、workdir 均正确）。
- 分发/Skip：未开 SharedDb → H3；开 → Shared；`-NoStart` 的 `instance-kill` → Skip 且原因正确。
- `SYNTAX_OK`。

## diff / 自证

```
 scripts/README.md               |  19 +-
 scripts/b23l1-dual-instance.ps1 | 392 ++++++++++++++++++++++++++++++++++------
 2 files changed, 352 insertions(+), 59 deletions(-)
```
（HEAD 已推进到 `d8056d5`，批② 改动已入库，本次 diff 仅批③。）

自证（agent-1310 `wire.jsonl:5`）：`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`。

## 残余风险 / 首跑关注点

1. **后端锁的 acquire 落点**：当前工作树只有 `LeaseKeys`/`ToolOutcome.BLOCKED`/`ai:idem:` 设施，`acquire` 还没接进工具执行路径。用例按设计稿「执行前 acquire」写；若实际接在 **preflight 之前**，H3/Shared 都会在“拿不到确认卡/没有 RESOURCE_LOCKED”处失败，需要按落点调整（把预置锁提前到 chat 之前并处理无卡路径）。
2. `AUTO_SERVER` 可行性未实跑验证；交错启动 + 显式 FAIL 文案已就位，首跑若失败请直接看 `instance-a/b.log` 的 H2 报错。
3. 断言依赖 `status=BLOCKED`、`outcome=blocked`、`retryable=true`（`ToolOutcome` 口径）；失败信息会打印实得值。
