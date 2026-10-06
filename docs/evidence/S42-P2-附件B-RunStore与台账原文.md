# S4.2-P2 附件 B：RunStore / 待决条目 / 执行台账 的 Redis 原文

> 读取方式：`<MEMURAI_HOME>/memurai-cli.exe`（只读命令 `type/ttl/get/hgetall/lrange/smembers/--scan`）。
> 键：`ai:run:`（轮次快照）/ `ai:pending:`（待决条目）/ `ai:claim:`（执行认领）/ `ai:ledger:`（执行台账）/
> `ai:events:`（帧外置）/ `ai:runs`（索引）/ `ai:session:lock:`（会话锁）/ `ai:session:`（会话登记）；轮次类键 TTL = `app.ai.session-ttl`（6h）。

## 1. V3：挂起中的 RunStore 原文

runId=`db033545-df04-46d4-93ab-fab00b01c62d`，sessionId=`s42p2-a1d`（写作者 = 发起轮次的实例）。

```
$ type ai:run:db033545-df04-46d4-93ab-fab00b01c62d -> string
$ ttl  ai:run:db033545-df04-46d4-93ab-fab00b01c62d -> 21592
$ get ai:run:db033545-df04-46d4-93ab-fab00b01c62d   （原文；messageJson 逐条折叠为角色/工具摘要，其余字段逐字原样）
{
 "runId": "db033545-df04-46d4-93ab-fab00b01c62d",
 "sessionId": "s42p2-a1d",
 "status": "SUSPENDED",
 "round": 0,
 "assistantContent": "",
 "inFlightToolCalls": [
  {
   "toolCallId": "call_00_4VsSANGB4JNXc9vrb7aN9998",
   "name": "start_publish",
   "type": "function",
   "kind": "CONFIRM",
   "arguments": "{\"taskId\": 129}",
   "status": "PENDING",
   "reason": null,
   "resultText": null,
   "executed": false,
   "executedBy": null,
   "executedAtMs": 0,
   "requestedAtMs": 1791311637475,
   "resolvedAtMs": 0
  }
 ],
 "context": {
  "page": "",
  "taskId": 129,
  "taskType": "IMPORT",
  "step": "PUBLISH",
  "extra": {}
 },
 "finalText": null,
 "error": null,
 "createdBy": "instance-Lenovo-pid31436-b261c9",
 "createdAtMs": 1791311634868,
 "updatedAtMs": 1791311637478,
 "resumedBy": []
}

messageJson[2] 逐条：
  [0] SYSTEM
  [1] USER
```

```
$ hgetall ai:pending:db033545-df04-46d4-93ab-fab00b01c62d
call_00_4VsSANGB4JNXc9vrb7aN9998
{"toolCallId":"call_00_4VsSANGB4JNXc9vrb7aN9998","name":"start_publish","type":"function","kind":"CONFIRM","arguments":"{\"taskId\": 129}","status":"PENDING","reason":null,"resultText":null,"executed":false,"executedBy":null,"executedAtMs":0,"requestedAtMs":1791311637475,"resolvedAtMs":0}

$ type ai:ledger:db033545-df04-46d4-93ab-fab00b01c62d -> none
$ llen ai:ledger:db033545-df04-46d4-93ab-fab00b01c62d -> (未收录)
（无台账条目 —— 该轮没有任何工具被真实执行）

$ type ai:session:lock:s42p2-a1d -> string
$ ttl  ai:session:lock:s42p2-a1d -> 589
$ get ai:session:lock:s42p2-a1d -> db033545-df04-46d4-93ab-fab00b01c62d
$ get ai:session:s42p2-a1d -> {"sessionId":"s42p2-a1d","lastActivity":"2026-10-06T18:33:54.804656500Z","rounds":1}

$ smembers ai:runs（索引；本次施工累计 26 个 runId，均为本轮测试产物）
```

> `inFlightToolCalls[0]`：`kind=CONFIRM`、`status=PENDING`、`arguments={"taskId": 129}`、`executed=false` —— 这就是硬规范①
> 「挂起前先写 RunStore（含 assistant(tool_calls) 完整快照）」的落点：快照里已带着这次工具调用的完整副本
> （id/type/name/arguments 全部原样），进程内的闸门只负责唤醒；此刻 `ai:claim`/`ai:ledger` 都还不存在（尚未执行）。

## 2. V4：重启续跑的三个中间态与终态（runId=`8f169b99-01e1-4e9e-92de-790a459e8d47`，sessionId=`s42p2-b1b`）

### ① 挂起中（实例 1 = `instance-Lenovo-pid11860-8169e3`，即 runId 的 `createdBy`）

```
$ hgetall ai:pending:8f169b99-01e1-4e9e-92de-790a459e8d47
call_00_5By1F6ULY70xEzp9tzAT1565
{"toolCallId":"call_00_5By1F6ULY70xEzp9tzAT1565","name":"start_publish","type":"function","kind":"CONFIRM","arguments":"{\"taskId\": 130}","status":"PENDING","reason":null,"resultText":null,"executed":false,"executedBy":null,"executedAtMs":0,"requestedAtMs":1791311728881,"resolvedAtMs":0}

$ type ai:ledger:8f169b99-01e1-4e9e-92de-790a459e8d47 -> none
$ llen ai:ledger:8f169b99-01e1-4e9e-92de-790a459e8d47 -> (未收录)
（无台账条目 —— 该轮没有任何工具被真实执行）
```

### ② 实例 2（重启后只用来写决策的进程）`POST /api/ai/confirm` 放行后立刻 kill —— E2 窗口：已放行、未执行

```
$ hgetall ai:pending:8f169b99-01e1-4e9e-92de-790a459e8d47
call_00_5By1F6ULY70xEzp9tzAT1565
{"toolCallId":"call_00_5By1F6ULY70xEzp9tzAT1565","name":"start_publish","type":"function","kind":"CONFIRM","arguments":"{\"taskId\": 130}","status":"APPROVED","reason":"S4.2-P2 跨进程放行（E2 窗口）","resultText":null,"executed":false,"executedBy":null,"executedAtMs":0,"requestedAtMs":1791311728881,"resolvedAtMs":1791311745755}

$ type ai:ledger:8f169b99-01e1-4e9e-92de-790a459e8d47 -> none
$ llen ai:ledger:8f169b99-01e1-4e9e-92de-790a459e8d47 -> (未收录)
（无台账条目 —— 该轮没有任何工具被真实执行）
```

### ③ 实例 3（`instance-Lenovo-pid19420-a0ade0`）启动、`POST /resume` 之前：决策在、执行不在

```
$ hgetall ai:pending:8f169b99-01e1-4e9e-92de-790a459e8d47
call_00_5By1F6ULY70xEzp9tzAT1565
{"toolCallId":"call_00_5By1F6ULY70xEzp9tzAT1565","name":"start_publish","type":"function","kind":"CONFIRM","arguments":"{\"taskId\": 130}","status":"APPROVED","reason":"S4.2-P2 跨进程放行（E2 窗口）","resultText":null,"executed":false,"executedBy":null,"executedAtMs":0,"requestedAtMs":1791311728881,"resolvedAtMs":1791311745755}

$ type ai:ledger:8f169b99-01e1-4e9e-92de-790a459e8d47 -> none
$ llen ai:ledger:8f169b99-01e1-4e9e-92de-790a459e8d47 -> (未收录)
（无台账条目 —— 该轮没有任何工具被真实执行）
```

### ④ 续跑完成（实例 3 = `instance-Lenovo-pid19420-a0ade0`）

```
$ type ai:run:8f169b99-01e1-4e9e-92de-790a459e8d47 -> string
$ ttl  ai:run:8f169b99-01e1-4e9e-92de-790a459e8d47 -> 21594
$ get ai:run:8f169b99-01e1-4e9e-92de-790a459e8d47   （原文；messageJson 逐条折叠为角色/工具摘要，其余字段逐字原样）
{
 "runId": "8f169b99-01e1-4e9e-92de-790a459e8d47",
 "sessionId": "s42p2-b1b",
 "status": "DONE",
 "round": 1,
 "assistantContent": null,
 "inFlightToolCalls": [],
 "context": {
  "page": "",
  "taskId": 130,
  "taskType": "IMPORT",
  "step": "PUBLISH",
  "extra": {}
 },
 "finalText": "发布作业已启动，我来查一下进度。✅ 发布作业已发起并完成。\n\n**执行结果**\n\n| 项目 | 内容 |\n|---|---|\n| 任务 | #130 [IMPORT] S42P2 restart-resume scenario FINAL |\n| 发布作业 | #193 [PUBLISH] |\n| 状态 | COMPLETED |\n| 进度 | 1/1 |\n| 错误数 | 0 |\n| 配置项 | CURRENCY |\n\n暂存数据已成功写入正式库，无错误记录。\n\n**关于确认门**：我按你的要求直接调用了 `start_publish(taskId=130)`，未在对话中二次询问。发布确认由平台内置确认门负责，工具侧未向我返回确认细节；从作业能正常启动并跑到 COMPLETED 来看，确认环节已放行。如果确认门卡片实际被你拒绝过，请以页面显示为准并告诉我，我再核对。\n\n**建议的下一步**\n\n1. 回到任务中心确认 #130 的步骤是否已推进到终态（当前快照显示停留在 PUBLISH、状态 ACTIVE）。\n2. 抽查一下生产库中 CURRENCY 的数据条数/关键字段，与导入前基线比对。\n3. 若本任务还有后续配置项（当前仅选了 CURRENCY），可在同一任务内继续导入或新建任务。\n\n需要我帮你查询 #130 的最新任务状态，或核对某个配置定义的行数吗？",
 "error": null,
 "createdBy": "instance-Lenovo-pid11860-8169e3",
 "createdAtMs": 1791311727223,
 "updatedAtMs": 1791311765613,
 "resumedBy": [
  "instance-Lenovo-pid19420-a0ade0"
 ]
}

messageJson[7] 逐条：
  [0] SYSTEM
  [1] USER
  [2] ASSISTANT tool_calls=[call_00_5By1F6ULY70xEzp9tzAT1565:start_publish{"taskId": 130}]
  [3] TOOL toolResponses=[call_00_5By1F6ULY70xEzp9tzAT1565]
  [4] ASSISTANT tool_calls=[call_00_zVZrS1SaG1A3eSVt02Op8852:check_job_status{"jobId": 193}]
  [5] TOOL toolResponses=[call_00_zVZrS1SaG1A3eSVt02Op8852]
  [6] ASSISTANT
```

```
$ hgetall ai:pending:8f169b99-01e1-4e9e-92de-790a459e8d47
call_00_zVZrS1SaG1A3eSVt02Op8852
{"toolCallId":"call_00_zVZrS1SaG1A3eSVt02Op8852","name":"check_job_status","type":"function","kind":"BACKEND","arguments":"{\"jobId\": 193}","status":"EXECUTED","reason":"IN_PROCESS_EXECUTION","resultText":"\"作业 #193 [PUBLISH] 状态:COMPLETED 进度:1/1 错误:0\"","executed":true,"executedBy":"instance-Lenovo-pid19420-a0ade0","executedAtMs":1791311762876,"requestedAtMs":1791311762864,"resolvedAtMs":1791311762876}
call_00_5By1F6ULY70xEzp9tzAT1565
{"toolCallId":"call_00_5By1F6ULY70xEzp9tzAT1565","name":"start_publish","type":"function","kind":"CONFIRM","arguments":"{\"taskId\": 130}","status":"APPROVED","reason":"S4.2-P2 跨进程放行（E2 窗口）","resultText":"\"发布作业已启动（作业 #193），正在将数据写入正式库…\"","executed":true,"executedBy":"instance-Lenovo-pid19420-a0ade0","executedAtMs":1791311761906,"requestedAtMs":1791311728881,"resolvedAtMs":1791311745755}

$ hgetall ai:claim:8f169b99-... （认领者 = 执行者）
  call_00_5By1F6ULY70xEzp9tzAT1565
  instance-Lenovo-pid19420-a0ade0
  call_00_zVZrS1SaG1A3eSVt02Op8852
  instance-Lenovo-pid19420-a0ade0

$ type ai:ledger:8f169b99-01e1-4e9e-92de-790a459e8d47 -> list
$ llen ai:ledger:8f169b99-01e1-4e9e-92de-790a459e8d47 -> 2
$ lrange ai:ledger:8f169b99-01e1-4e9e-92de-790a459e8d47 0 -1
{"atMs":1791311761903,"at":"2026-10-06T18:36:01.903257100Z","executedBy":"instance-Lenovo-pid19420-a0ade0","toolCallId":"call_00_5By1F6ULY70xEzp9tzAT1565","name":"start_publish","kind":"CONFIRM","resultText":"\"发布作业已启动（作业 #193），正在将数据写入正式库…\""}
{"atMs":1791311762875,"at":"2026-10-06T18:36:02.875818300Z","executedBy":"instance-Lenovo-pid19420-a0ade0","toolCallId":"call_00_zVZrS1SaG1A3eSVt02Op8852","name":"check_job_status","kind":"BACKEND","resultText":"\"作业 #193 [PUBLISH] 状态:COMPLETED 进度:1/1 错误:0\""}
```

> `start_publish` 在台账里恰好一条，`executedBy = instance-Lenovo-pid19420-a0ade0`（第三个实例、即续跑实例），
> 而轮次 `createdBy = instance-Lenovo-pid11860-8169e3`（第一个实例，已被 kill）—— 跨进程「补执行恰好一次」的直接证据。
> `messageJson` 末段是重建出来的 `assistant(tool_calls=start_publish)` + `role:tool`，
> 即续跑用完整历史（system/user 一条未丢）继续了官方循环。

## 3. 其余结局的待决条目与台账（同一构建）

### V2 拒绝：`start_publish` REJECTED，台账为空（未执行）

```
$ hgetall ai:pending:b42e7126-c2b7-44a4-9b4b-f144e628292a
call_00_L5yJnXu3K22nIviJvu1T9136
{"toolCallId":"call_00_L5yJnXu3K22nIviJvu1T9136","name":"start_publish","type":"function","kind":"CONFIRM","arguments":"{\"taskId\": 35}","status":"REJECTED","reason":"S4.2-P2 联调：拒绝发布（数据未核对）","resultText":"用户拒绝了该操作，工具未执行。拒绝原因：S4.2-P2 联调：拒绝发布（数据未核对）","executed":false,"executedBy":null,"executedAtMs":0,"requestedAtMs":1791311656219,"resolvedAtMs":1791311663173}

$ type ai:ledger:b42e7126-c2b7-44a4-9b4b-f144e628292a -> none
$ llen ai:ledger:b42e7126-c2b7-44a4-9b4b-f144e628292a -> (未收录)
（无台账条目 —— 该轮没有任何工具被真实执行）
```

### V2 超时（20s）：`start_publish` TIMEOUT，台账里没有它

```
$ hgetall ai:pending:1d24bf1a-289f-45d1-aef4-3e94024b94a4
call_00_F2tsZOgDBjPgzQNh82s83351
{"toolCallId":"call_00_F2tsZOgDBjPgzQNh82s83351","name":"start_publish","type":"function","kind":"CONFIRM","arguments":"{\"taskId\": 38}","status":"TIMEOUT","reason":"CONFIRM_TIMEOUT","resultText":"确认等待超过 20 秒，系统已自动取消该操作（工具未执行）。","executed":false,"executedBy":null,"executedAtMs":0,"requestedAtMs":1791311697042,"resolvedAtMs":1791311717053}
call_01_6Z7l8eHuPQ7EWtsHY6Rc0702
{"toolCallId":"call_01_6Z7l8eHuPQ7EWtsHY6Rc0702","name":"list_tasks","type":"function","kind":"BACKEND","arguments":"{}","status":"EXECUTED","reason":"IN_PROCESS_EXECUTION","resultText":"\"- #130 [IMPORT] S42P2 restart-resume scenario FINAL 步骤:PUBLISH 状态:ACTIVE\\n- #129 [IMPORT] S42P2 approve scenario FINAL 步骤:PUBLISH 状态:COMPLETED\\n- #97 [IMPORT] S42P2 approve scenario R3 步骤:PUBLISH 状态:COMPLETED\\n- #65 [IMPORT] S42P2 approve scenario R2 步骤:PUBLISH 状态:ACTIVE\\n- #41 [EXPORT] S42P2 frontend-tool scenario 步骤:EXPORT 状态:COMPLETED\\n- #40 [IMPORT] S42P2 pool-saturation B 步骤:PUBLISH 状态:ACTIVE\\n- #39 [IMPORT] S42P2 pool-saturation A 步骤:PUBLISH 状态:ACTIVE\\n- #38 [IMPORT] S42P2 sessiongate scenario 步骤:PUBLISH 状态:ACTIVE\\n- #37 [IMPORT] S42P2 restart-resume scenario 步骤:PUBLISH 状态:COMPLETED\\n- #36 [IMPORT] S42P2 timeout scenario 步骤:PUBLISH 状态:ACTIVE\"","executed":true,"executedBy":"instance-Lenovo-pid11860-8169e3","executedAtMs":1791311695138,"requestedAtMs":1791311695097,"resolvedAtMs":1791311695138}
call_00_0lryid5dOZlVC92LYEku5078
{"toolCallId":"call_00_0lryid5dOZlVC92LYEku5078","name":"get_workspace_state","type":"function","kind":"BACKEND","arguments":"{\"taskId\": 38}","status":"EXECUTED","reason":"IN_PROCESS_EXECUTION","resultText":"\"任务 #38: S42P2 sessiongate scenario\\n类型: IMPORT，步骤: PUBLISH，状态: ACTIVE\\n配置项: CURRENCY(PENDING)\\n\"","executed":true,"executedBy":"instance-Lenovo-pid11860-8169e3","executedAtMs":1791311695116,"requestedAtMs":1791311695095,"resolvedAtMs":1791311695116}

$ type ai:ledger:1d24bf1a-289f-45d1-aef4-3e94024b94a4 -> list
$ llen ai:ledger:1d24bf1a-289f-45d1-aef4-3e94024b94a4 -> 2
$ lrange ai:ledger:1d24bf1a-289f-45d1-aef4-3e94024b94a4 0 -1
{"atMs":1791311695114,"at":"2026-10-06T18:34:55.114521400Z","executedBy":"instance-Lenovo-pid11860-8169e3","toolCallId":"call_00_0lryid5dOZlVC92LYEku5078","name":"get_workspace_state","kind":"BACKEND","resultText":"\"任务 #38: S42P2 sessiongate scenario\\n类型: IMPORT，步骤: PUBLISH，状态: ACTIVE\\n配置项: CURRENCY(PENDING)\\n\""}
{"atMs":1791311695137,"at":"2026-10-06T18:34:55.137521700Z","executedBy":"instance-Lenovo-pid11860-8169e3","toolCallId":"call_01_6Z7l8eHuPQ7EWtsHY6Rc0702","name":"list_tasks","kind":"BACKEND","resultText":"\"- #130 [IMPORT] S42P2 restart-resume scenario FINAL 步骤:PUBLISH 状态:ACTIVE\\n- #129 [IMPORT] S42P2 approve scenario FINAL 步骤:PUBLISH 状态:COMPLETED\\n- #97 [IMPORT] S42P2 approve scenario R3 步骤:PUBLISH 状态:COMPLETED\\n- #65 [IMPORT] S42P2 approve scenario R2 步骤:PUBLISH 状态:ACTIVE\\n- #41 [EXPORT] S42P2 frontend-tool scenario 步骤:EXPORT 状态:COMPLETED\\n- #40 [IMPORT] S42P2 pool-saturation B 步骤:PUBLISH 状态:ACTIVE\\n- #39 [IMPORT] S42P2 pool-saturation A 步骤:PUBLISH 状态:ACTIVE\\n- #38 [IMPORT] S42P2 sessiongate scenario 步骤:PUBLISH 状态:ACTIVE\\n- #37 [IMPORT] S42P2 restart-resume scenario 步骤:PUBLISH 状态:COMPLETED\\n- #36 [IMPORT] S42P2 timeout scenario 步骤:PUBLISH 状态:ACTIVE\""}
```

### 前端工具：`FRONTEND_RESULT`，桩体未执行（台账键不存在）

```
$ hgetall ai:pending:c2010157-6e7a-4315-b62c-70628d3bed91
call_00_8bmyWowlNMnACONqdPKq9079
{"toolCallId":"call_00_8bmyWowlNMnACONqdPKq9079","name":"open_export_file_editor","type":"function","kind":"FRONTEND","arguments":"{\"taskId\": 41, \"defCode\": \"CURRENCY\"}","status":"FRONTEND_RESULT","reason":"source=s42p2-driver","resultText":"已在浏览器打开 CURRENCY 的在线编辑器（S42P2 联调 mock）","executed":false,"executedBy":null,"executedAtMs":0,"requestedAtMs":1791311668632,"resolvedAtMs":1791311675547}

$ type ai:ledger:c2010157-6e7a-4315-b62c-70628d3bed91 -> none
$ llen ai:ledger:c2010157-6e7a-4315-b62c-70628d3bed91 -> (未收录)
（无台账条目 —— 该轮没有任何工具被真实执行）
```

