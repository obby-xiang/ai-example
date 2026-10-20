# 补档：B1「挂起即断 SSE」实机核验（API 级）

- 日期：2026-10-20
- 核验范围：设计稿 `docs/research/设计-B1挂起即断SSE-2026-10-20.md` §5.4（E2E）与 §5.5（实机核验，API 级）
- 环境：后端 8081（桩模式：`AI_BASE_URL=http://127.0.0.1:18399`、`AI_MODEL=ci-stub`）、桩上游 18399、前端 dev 5202。核验期间未启停任何服务、未改动代码。
- **入库脱敏（提交官，2026-10-20）**：本机绝对路径按 DC-08 换占位符（`<KIMI_HOME>` / `<TMP>`），正文事实与结论未改。

## 0. 自证行（实测，非卡片转抄）

grep 本子智能体自身 wire 文件 `.../agents/agent-1284/wire.jsonl` 第 5 行 `profile.bind`：

```
"type":"profile.bind","agentId":"agent-1284","modelAlias":"DeepSeek/deepseek-flash",
"profileName":"coder","thinkingEffort":"high"
```

即：**modelAlias = DeepSeek/deepseek-flash，profileName = coder，thinkingEffort = high**。

## 1. 服务健康实测（步骤 0）

```
$ curl -s http://127.0.0.1:18399/probe
{"ok":true,"stub":"ci-upstream"}

$ curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/api/ai/health   → 200
$ curl -s -o /dev/null -w '%{http_code}' http://localhost:5202/                → 200
```

健康体关键字段（截取）：

```json
{"available":true,"model":"ci-stub","baseUrl":"http://127.0.0.1:18399",
 "memoryBackend":"RedisChatMemoryRepository","suspendPoolSize":20,
 "redis":{"available":true,"failures":0},"activeSuspendGates":0,
 "instanceId":"instance-Lenovo-pid32892-441934"}
```

三通，进入步骤 1。

## 2. 步骤 1 · E2E（`scripts/verify-e2e.ps1`，桩模式，全量 31 用例含 GF6）

调用方式按脚本头部的既有用法（本机无 `pwsh`，用 `powershell.exe`；桩模式置 `E2E_STUB_MODE=1`；
`-BackendLog` 指向后端 stdout 任务日志；GF6 按头部说明在桩模式下启用）。脚本不支持选用例，
故三轮均为全量：

```
E2E_STUB_MODE=1 powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-e2e.ps1 \
  -Base http://127.0.0.1:8081 \
  -BackendLog "<KIMI_HOME>/sessions/.../agents/main/tasks/bash-va6kvaym/output.log" \
  -EnableGf6 -ArtifactDir "<TMP>/_b1-e2e-artifacts"
```

### 2.1 首轮（23:11，启动参数缺失）

结果（原始日志 `<TMP>/_b1-e2e.log`，耗时 52s）：

```
结果：PASS=29  FAIL=2  （主用例计数，含 TC1–TC22、TC23 与 GF1–GF6）
GFSKIP=0
Q8 项：PASS=2  FAIL=0
EXIT=1
```

两条 FAIL 及归因（**均为启动参数与脚本头部约定不一致，不是 B1 改动引入**）：

| 用例 | 关键输出 | 归因证据 |
| --- | --- | --- |
| TC11 SSE 进度事件流 | `收到 JOB_PROGRESS 帧 1 条（后端需以 --app.job.batch-size=10 启动）`；断言要求 ≥2 条 | 后端进程命令行实为 `java -jar target/config-mgr.jar`（无 `--app.job.batch-size=10`）；`application.yml:201` 默认 `batch-size: 100`，120 行的小配置项只跨 1 个分片 → 只有 1 条进度帧。脚本注释第 858 行明确该前置条件 |
| GF6 入参闸门 | `桩模式 3 次均未产出非法 schema（应由 --violate-form-type 构造保证，属桩路由或产品链路异常）` | 桩进程命令行实为 `node scripts/ci/stub-upstream.mjs --port 18399 --dir <TMP>/_shared/stub-dump`，**未带 `--violate-form-type`**；非法 schema 由该开关按请求级注入，不带开关时桩只会产出合法表单 |

其余与 B1 直接相关的用例全绿，特别是 **TC16（确认门放行 + 前端工具挂起回灌）**、**TC17（拒绝/确认双向 + 重复决策 409）**、**TC18（409 SESSION_BUSY + 取消）**、**TC23（真挂起 → runs/current 重建 → 差量续收 → confirm → confirm_decision+tool_result+done 收敛）**、GF1–GF5。

### 2.2 复跑（23:16，后端已按头部约定重启）

主会话按脚本头部约定重启了后端（新任务 `bash-6pin7tec`），进程实启命令核实为：

```
java -jar target/config-mgr.jar --app.job.batch-size=10 --app.job.demo-batch-delay-ms=150 --violate-form-type
```

脚本无"选用例"参数（`param` 仅 `-Base/-BackendLog/-EnableGf6/-ArtifactDir`），故全量重跑：

```
E2E_STUB_MODE=1 powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-e2e.ps1 \
  -Base http://127.0.0.1:8081 \
  -BackendLog "<KIMI_HOME>/sessions/.../agents/main/tasks/bash-6pin7tec/output.log" \
  -EnableGf6 -ArtifactDir "<TMP>/_b1-e2e-artifacts-r2"
```

结果（原始日志 `<TMP>/_b1-e2e-r2.log`，耗时 61s）：

```
结果：PASS=30  FAIL=1  （主用例计数，共 31 条）
GFSKIP=0
Q8 项：PASS=2  FAIL=0
EXIT=1
```

- **TC11 → PASS**：`收到进度事件流 12 条（末条 120/120）+ JOB_DONE(COMPLETED)`。`--app.job.batch-size=10` 生效后进度帧恢复，首轮失败确认为启动参数问题。
- **GF6 → 仍 FAIL**：`桩模式 3 次均未产出非法 schema（应由 --violate-form-type 构造保证，属桩路由或产品链路异常）`。**根因未消除**：`--violate-form-type` 是**桩进程**的 CLI 开关（`scripts/ci/stub-upstream.mjs:62`），不具备后端属性；本次该参数被追加到了 java 命令行，后端不识别亦无效果。桩进程仍是首轮 23:10:12 启动的 PID 35272，命令行 `node scripts/ci/stub-upstream.mjs --port 18399 --dir <TMP>/_shared/stub-dump`，未带该开关。
- GF6 工件佐证（`<TMP>/_b1-e2e-artifacts-r2/gfc-GF6.sse.txt`）：桩下发的是**完全合法**的表单（scope 字段 `type=enum`，非 html），产品链路按合法表单挂起 → 脚本取消 → `FRONTEND_CANCELLED` 收敛；未出现 `REJECTED_ARGUMENTS` 说明闸门根本没有被喂到非法输入，属"桩未注入"，不是产品闸门失效。

> 复跑结论：**30/31 主用例通过，唯一未过为 GF6**，且失败点已精确落在"桩进程未以 `--violate-form-type` 重启"。后端侧参数问题（TC11）已由复跑证实修复。按铁律本卡不得启停服务，GF6 需主会话重启桩（`node scripts/ci/stub-upstream.mjs --port 18399 --dir <TMP>/_shared/stub-dump --violate-form-type`）后再跑一次。

### 2.3 第三轮（23:18，桩已按约定带 `--violate-form-type` 重启）— 最终口径

主会话重启桩（新任务 `bash-nq2yf07a`），进程核实：

```
node scripts/ci/stub-upstream.mjs --port 18399 --dir <TMP>/_shared/stub-dump --violate-form-type
启动时间 23:17:56（旧无开关进程 PID 35272 @23:10:12 已被替换）
```

后端 8081 维持 `bash-6pin7tec` 不动。前置探活：`18399/probe → 200 {"ok":true,"stub":"ci-upstream"}`，`8081/api/ai/health → 200 model=ci-stub`。
仍为全量重跑：

```
E2E_STUB_MODE=1 powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-e2e.ps1 \
  -Base http://127.0.0.1:8081 \
  -BackendLog "<KIMI_HOME>/sessions/.../agents/main/tasks/bash-6pin7tec/output.log" \
  -EnableGf6 -ArtifactDir "<TMP>/_b1-e2e-artifacts-r3"
```

结果（原始日志 `<TMP>/_b1-e2e-r3.log`，耗时 52s）：

```
结果：PASS=31  FAIL=0  （主用例计数，含 TC1–TC22、TC23 与 GF1–GF6）
GFSKIP=0
Q8 项：PASS=2  FAIL=0
EXIT=0
```

- **TC11 → PASS**：`收到进度事件流 12 条（末条 120/120）+ JOB_DONE(COMPLETED)`。
- **GF6 → PASS**：`GF6 入参闸门：非法 schema 不挂起 + REJECTED_ARGUMENTS 结局帧 + 闸门日志`。桩按请求级注入（提示词含"故意写成 html"）后，产品闸门在挂起前拒绝非法 schema，`REJECTED_ARGUMENTS` 结局帧与闸门日志均命中。
- 全程无 `[FAIL]` 行，`GFSKIP=0`（桩模式零容忍门通过），Q8 2/2，退出码 0。

> 最终口径：**31/31 主用例全绿，exit 0**。首轮（29/2）、复跑（30/1）两次失败均为启动参数问题：TC11 由后端 `--app.job.batch-size=10` 修复，GF6 由桩 `--violate-form-type` 修复；B1 改动本身在两轮失败期间的关键用例（TC16/TC17/TC18/TC23、GF1–GF5）始终全绿，未观察到回归。

## 3. 步骤 2 · B1 API 级实机核验

请求体按 `verify-e2e.ps1` 的 `Invoke-AiTurn` 同款（`POST /api/ai/chat {sessionId,message,context}`）。
所有 SSE 读取均带 `--max-time`，**curl 退出码 0 且耗时远小于上限**即证明"服务端主动关流"，不是超时被杀。

### 3.1 确认卡：挂起即断（核验点 1）

会话 `b1v-confirm-1792509177`，任务 441（EXPORT，step=EXPORT），提示词"请立即调用 start_export…"：

```
$ timeout 45 curl -N -s --max-time 40 -X POST http://127.0.0.1:8081/api/ai/chat \
    -H 'Content-Type: application/json' -d @body-chat.json
curl_rc=0  elapsed_ms=161
[CURL http=200 time=0.029397s]

seq 1  start
seq 2  suspended            (toolCalls[0]=start_export status=PENDING kind=CONFIRM)
seq 3  tool_start           call-stub-34 start_export
seq 4  confirm_request      call-stub-34 start_export
<EOF —— curl 自行退出>
```

**核验点 1 通过**：`confirm_request`（seq 4）送达后连接由服务端关闭；curl 退出码 0、总耗时 161ms，`--max-time 40` 未触发。末帧 seq = **4**，runId = `d9542640-342d-4352-9b97-bf6c13bd864f`。

原始末帧（preflight 中段略）：

```json
{"type":"confirm_request","atMs":1792509180161,"toolCallId":"call-stub-34","name":"start_export",
 "args":"{\"taskId\":441}","summary":"{\"taskId\":441}",
 "preflight":{"target":{"taskId":441,"title":"B1核验-挂起即断SSE","type":"EXPORT","status":"ACTIVE"},
 "checks":[{"name":"任务存在","pass":true},…],"impact":"将导出 1 个配置定义 · 影响面暂不可估",
 "reportHash":"82e46609"},"…","seq":4}
```

### 3.2 挂起态在档

```
$ curl -s "http://127.0.0.1:8081/api/ai/runs/current?sessionId=b1v-confirm-1792509177"
found=true  snapshotStatus=SUSPENDED  archiveMaxSeq=4  awaitingExternal=true
pending[0] = {toolCallId: call-stub-34, name: start_export, kind: CONFIRM}
```

### 3.3 批准决策

```
$ curl -s -X POST http://127.0.0.1:8081/api/ai/confirm \
    -d '{"runId":"d9542640-…","toolCallId":"call-stub-34","approved":true,"reason":"b1-api-verify"}'
HTTP 200
{"accepted":true,"duplicate":false,"status":"APPROVED","approved":true,"executed":false,
 "wokeInProcessGate":true,"resumeTriggered":false,"resumeOutcome":"WOKE_IN_PROCESS_GATE",…}
```

### 3.4 `?lastSeq=4` 续读：差量补帧（核验点 2）

```
$ timeout 60 curl -N -s --max-time 50 \
    "http://127.0.0.1:8081/api/ai/events/d9542640-…?lastSeq=4"
curl_rc=0   （done 后正常结束）
seq 5  confirm_decision  start_export APPROVED
seq 6  tool_result       start_export APPROVED
seq 7  message_start
seq 8  delta
seq 9  delta
seq 10 delta
seq 11 delta
seq 12 delta
seq 13 delta
seq 14 message_end
seq 15 done
FRAMES=11  nonmonotonic=0
```

**核验点 2 通过**：从 4 之后补回 `confirm_decision` 及其后全部帧（5…15 连续、严格单调、无重复、无缺口），直至 `done`；正文（delta）完整。

**delta 入档的更强证据**（同一 run，已完成态，从流中间续读）：

```
$ curl -N -s "http://127.0.0.1:8081/api/ai/events/d9542640-…?lastSeq=10"
seq 11 delta  ":441} evidence= message="
seq 12 delta  "\"导出作业已启动（作业 #448），请稍后查看进"
seq 13 delta  "度\""
seq 14 message_end
seq 15 done
```

即 delta 确实入档，可按任意 `lastSeq` 做帧级差量回放（不是整段重放）。

### 3.5 GF 链路（frontend_tool_request → frontend-tool-result → ?lastSeq）

会话 `b1v-gf-1792509195`，`context={page:'tasks'}`，提示词 `scenario=FILTER`：

```
seq 1 start
seq 2 suspended
seq 3 tool_start            call-stub-36 generative_form (kind=FRONTEND)
seq 4 frontend_tool_request call-stub-36 generative_form
<EOF —— curl_rc=0, elapsed_ms=143, http=200 time=0.014s>
```

挂起态：`found=true SUSPENDED archiveMaxSeq=4 awaitingExternal=true`，pending 条目 `generative_form:FRONTEND`。

回灌：

```
$ curl -s -X POST /api/ai/frontend-tool-result \
    -d '{"runId":"02ae34a9-…","toolCallId":"call-stub-36","result":"{\"keyword\":\"E2EGFC\"}","source":"b1-api-verify"}'
HTTP 200
{"accepted":true,"duplicate":false,"status":"FRONTEND_RESULT","cancelled":false,"executed":false,
 "wokeInProcessGate":true,"resumeTriggered":false,"resumeOutcome":"WOKE_IN_PROCESS_GATE",…}
```

续读 `?lastSeq=4`：

```
seq 5  frontend_tool_result generative_form FRONTEND_RESULT
seq 6  message_start
seq 7–11 delta
seq 12 message_end
seq 13 done
FRAMES=9  nonmonotonic=0
```

**GF 链路通过**：`frontend_tool_request` 后服务端同样主动断流；回灌后 `?lastSeq` 补回结局帧、正文与 `done`，无重复/缺口。两次 run 终态均为 `status=DONE`（`GET /api/ai/runs/{runId}`），`finalText` 与 delta 拼接一致。

### 3.6 挂起中重连（反方向核验）

会话 `b1v-attach-…`，任务 442（新 EXPORT 任务，CURRENCY，step=EXPORT），挂起末帧 seq=4、runId `df919f20-b20f-4452-9754-e1c2109c3893`。

```
# 不带任何决策，先挂一条续读连接
$ timeout 30 curl -N -s --max-time 25 "…/api/ai/events/df919f20-…?lastSeq=4" > sse-f-attach.txt &
ATTACH_ALIVE_AFTER_4s=yes
ATTACH_BYTES_BEFORE_DECISION=490   # 仅 heartbeat，无业务帧（回放空集）
data:{"type":"heartbeat",…,"status":"PENDING","waitedSeconds":2,"heartbeatSeq":1}
data:{"type":"heartbeat",…}
# 连接保持期间提交决策
$ curl -s -X POST /api/ai/confirm -d '{…,"toolCallId":"call-stub-38","approved":true}'
{"accepted":true,"status":"APPROVED","wokeInProcessGate":true,…}
# 决策帧实时到达同一条挂起中的连接
- heartbeat start_export PENDING
- heartbeat start_export PENDING
seq 5  confirm_decision start_export APPROVED
seq 6  tool_result start_export APPROVED
- heartbeat
seq 7  message_start
seq 8–13 delta
seq 14 message_end
seq 15 done
ATTACH_RC=0   （done 后正常结束，非超时）
```

**反方向核验通过**：挂起中 `?lastSeq` 重连可正常 attach（回放空集 + heartbeat 保活），决策后 `confirm_decision`/`tool_result`/delta/`done` 在同一条连接上实时送达，seq 5…15 连续单调。heartbeat 无 seq 属预期（不参与归档）。

## 4. 结论

| 核验项 | 结论 | 依据 |
| --- | --- | --- |
| 核验点 1：挂起即断 | **通过（3/3 场景）** | confirm / GF / attach 复连三次挂起均在请求帧后由服务端主动关流，curl rc=0、耗时 143–174ms（上限 40s 未触发） |
| 挂起态在档 | 通过 | `runs/current`：`SUSPENDED`、`awaitingExternal=true`、PENDING 条目及 `archiveMaxSeq` 正确 |
| 核验点 2：`?lastSeq` 补帧 | 通过 | seq 5…15 / 5…13 连续单调、无重复无缺口，含 `confirm_decision`、delta、`done` |
| delta 差量回放 | 通过 | 完成态 `lastSeq=10` 仅回放 11…15，证明 delta 入档且按帧差量 |
| GF 链路 | 通过 | `frontend_tool_request` 断流 → 回灌 200 → 结局帧 + 正文 + `done` 全补回 |
| 挂起中重连 | 通过 | 空回放 + heartbeat 保活，决策帧实时到达，连接在 `done` 后正常结束 |
| E2E（§5.4） | **31/31 主用例全绿，Q8 2/2，exit 0（第三轮，最终口径）** | 后端带 `--app.job.batch-size=10` + 桩带 `--violate-form-type` 后：TC11 `12 条进度帧/120/120 + JOB_DONE`；GF6 非法 schema 不挂起 + `REJECTED_ARGUMENTS` + 闸门日志；无 `[FAIL]`、`GFSKIP=0` |

遗留/建议（交主会话）：
1. E2E 已收口（31/31、exit 0），无遗留返工项。三次运行记录保留以证明失败归因：首轮 29/2（两项启动参数缺失）→ 复跑 30/1（TC11 修复、桩未重启）→ 第三轮 31/0。
2. 若后续再跑：`--violate-form-type` 必须加在**桩**命令行（`node scripts/ci/stub-upstream.mjs … --violate-form-type`），不是后端 java 参数；后端需 `--app.job.batch-size=10 --app.job.demo-batch-delay-ms=150`。
3. E2E 产物：首轮日志 `<TMP>/_b1-e2e.log`、复跑 `<TMP>/_b1-e2e-r2.log`、第三轮 `<TMP>/_b1-e2e-r3.log`；GF 证据 `<TMP>/_b1-e2e-artifacts`、`-r2`、`-r3`；API 核验原始 SSE 与请求/响应原文在 `<TMP>/_b1-api/`（`sse-a.txt`、`sse-d.txt`、`sse-d-mid.txt`、`sse-gf-a.txt`、`sse-gf-d.txt`、`sse-f-a.txt`、`sse-f-attach.txt`、`current-*.json`、`confirm-*.json`）。
3. 未发现与 B1 语义相关的异常：挂起后无多余帧、无重连风暴、无重复 seq；两次挂起轮次均在决策后一次续读内收敛到 `done`。
