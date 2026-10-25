> 来源=<仓外证据>/main-ci-34fd8f4-analysis.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# main CI 40182795560 @ 34fd8f4 · E2E 失败根因分析

> 只读取证：未改代码、未起本地服务、未重跑 workflow。证据目录
> `<仓外证据>/main-ci-34fd8f4-evidence/`（`gh run download -n e2e-evidence`，920 个文件，未打印任何密钥）。
> 行号均对应 commit `34fd8f405d0a7c57cb2b7bae387d04249fce5ba1`（经 GitHub contents API 取原文核对）。

## 0. 结论摘要

| 项 | 结论 |
|---|---|
| 失败 job | `E2E 全流程（35 用例 · 桩上游 + redis）`（job 121138168537），`E2E_RESULT=failure` |
| 失败用例数 | **1 / 165**（`PASS=165 FAIL=1` 口径里唯一的红）；批① `129/130`，T 域 `9/10`，P0 17 条全绿 |
| 唯一失败用例 | `AIE2E-T-13 [P1] 聚合卡主用例：aggregate_sheet 工具帧 → 挂起 → 回灌 → 结局帧/done 收敛` |
| 失败断言 | `scripts/verify-e2e.ps1:3809` → `readCalls 未增长（0 → 0）：读工具结算没有进审计面` |
| 链路本身 | **完全正常**：工具帧/挂起/回灌/结局帧/done 全部齐备（boot.log + stub `req-124/125/126` 三面一致） |
| 归因 | **产品缺陷**（观测面塌陷）：park 化的挂起续跑路径不再发射 `tool_call_end`、也不再调 `SheetAudit.record`；断言合法，不是 harness 过严，不是外部取消 |
| 与三实例 40182796512 的关系 | **独立**。三实例红在 `suspend-cross-instance`（跨实例续跑被判 `PROVIDER_GONE` → SSE 120s 超时），机位、代码位置、症状都不同（见 §7） |
| 是否回归 | 是。同一用例在绿跑 40158538458（`2ed8f95`）PASS，且当时 `tool_call_end` 带 `dataClass=D1 …`；`approval_park` 在本仓历史里只出现在 `34fd8f4`（更早的 2ed8f95/20c56bb/965e6ef/ae883fb 一律 0 次） |

## 1. 失败现场（E2E 日志 + boot 日志 + 桩判定，三面对齐）

失败行（`main-ci-34fd8f4-failed.log:156`）：

```
[FAIL] AIE2E-T-13 [P1] 聚合卡主用例：aggregate_sheet 工具帧 → 挂起 → 回灌 → 结局帧/done 收敛
      => readCalls 未增长（0 → 0）：读工具结算没有进审计面
```

同一轮收尾（`main-ci-34fd8f4-failed.log:253-258`）：`PASS=165 FAIL=1`；`批① AIE2E：PASS=129 FAIL=1`；退出码 1。

boot.log 里该轮（runId `5da926f8-…`，sessionId `aie2e-t13-…`，toolCallId `call-stub-124`）：

| 时刻 | 事件 | 说明 |
|---|---|---|
| 10:07:43.390 | `turn_start … tools=31` | 表格工具已披露（含 aggregate_sheet） |
| 10:07:43.411 | `tool_call_start … tool=aggregate_sheet kind=FRONTEND` | 左端 |
| 10:07:43.422 | `approval_park` + `人工等待 park … 线程交还` + `tool_call_park` | 挂起（新范式：park 而非阻塞等待） |
| 10:07:43.430 | `approval_decided … status=FRONTEND_RESULT` | 回灌成功 |
| 10:07:43.434 | `续跑结算复用结果 … status=FRONTEND_RESULT executedBy=null` | **复用分支**，不执行工具 |
| 10:07:43.446 / .476 / .497 | `turn_start resumed=true` / `upstream_first_byte` / `turn_end status=done` | 续跑收尾正常 |
| — | **没有 `tool_call_end`，`sheet.counters` 零增长** | 断言失败点 |

桩侧同样自证链路是通的：`stub/req-124.decision.json` 判 `route=SHEET-AGG / tool=aggregate_sheet`；`req-125` 是"续轮（本轮已出现工具结果）"；`req-126` 文本收尾。

前置 `before.readCalls = 0` 也说明：整棒里 T-13 是唯一一次"表格读工具结算"，它没记账 ⇒ 后值必然还是 0。

## 2. 代码路径定位（34fd8f4）

### 2.1 计数器只为"内联批循环"结算记账

- `backend/src/main/java/com/example/configmgr/ai/tool/SheetAudit.java:150` `record(...)`；读工具计数在 `:161`（`readCalls.incrementAndGet()`）；
- 对外面：`ai/web/AiController.java:1676-1680`（`sheet.put("counters", sheetAudit.metrics())` → `GET /api/ai/health` 的 `sheet.counters`）；
- **唯一调用点**：`ai/gate/SpToolCallingManager.java:664-666`，位于内联工具批循环的 `tool_call_end`（`:662`）之后。

### 2.2 续跑（park → 回灌 → resume）路径完全绕过它

- `SpToolCallingManager.java:564-566` 写着不变量：**"这一行（tool_call_start）之后必然有对应的 tool_call_end（同一 toolCallId）"**；
- 但 `resolvePending(...)` 的复用分支 `SpToolCallingManager.java:1816-1847` 只 `return text`（`:1845` 的 `续跑结算复用结果` 日志就是证据），**不发 end、不记账**；
- `ResumeService.java:842` 在续跑循环里逐条调 `resolvePending`，之后只拼 `role:tool` 消息（`:847-856`），同样没有 trace/audit；
- 全文件 `tool_call_end` 发射点只有三处：`:662`（内联批）、`:1055 traceToolSkipped`、`:1068 traceToolAborted` —— 都不覆盖"挂起后被结算"的常规路径。

### 2.3 为什么"必然"没了：park 化把结算挪出了内联循环

- 绿跑 `2ed8f95` 的同一场景是"线程内等待"：`tool_call_start` → `approval_suspend` → `approval_decided` → 继续内联循环 → **`tool_call_end … tool=aggregate_sheet kind=FRONTEND … dataClass=D1 totalRows=5 sheetVer=1`**（`main-ci-2ed8f95-evidence/boot.log:2788`）→ `SheetAudit.record` → `readCalls=1` → T-13 PASS。
- `34fd8f4` 改成 `approval_park`（线程交还）+ reattach 续跑后，结算发生在 `resolvePending` 复用分支，正好落在上面两个缺口里。

量化对比（同一套 grep 口径，按 toolCallId 去重）：

| 运行 | tool_call_start | tool_call_end | start 无 end | approval_park |
|---|---:|---:|---:|---:|
| 34fd8f4（红） | 97 | 37 | **64** | **64** |
| 2ed8f95（绿） | 101 | 100 | 2 | 0 |

64 = 64：**每一个 park 过的工具都丢了自己的 `tool_call_end`**，不只是 T-13 那一个。

## 3. 影响面（不止一个计数）

9 个表格工具全部是 `ToolMeta.Channel.FRONTEND`（`ai/tools/AiTools.java:742` 注释 + 各方法 `@ToolChannel(FRONTEND)`；`SheetSwitches.java:46` 的 `READ_TOOLS` 同属这批），也就是**生产里表格工具 100% 走 park → 回灌 → 续跑复用**。后果：

1. `GET /api/ai/health → sheet.counters` 的 8 个计数（`readCalls/writePlans/writeApplied/writeCancelled/undoCalls/sampleRowsTotal/sampleCharsMax/disabledRejects`）对表格工具恒为 0；
2. `tool_call_end` 的 8 个采样字段（`dataClass/sampleRows/sampleChars/projCols/totalRows/sheetVer/planId/previewLevel`）对表格工具永久缺席（`SheetAudit.LOG_FIELDS`）；
3. `dataClass=D2` 的 `ai:safety:<sessionId>` `kind=sheet_sample` 台账（`SheetAudit.appendLedger`）同样不落；
4. 破坏 `tool_call_start → tool_call_end` 的日志不变量，任何按该不变量做审计/告警的下游都会漏。

T-13 只是这条链上最先被断言到的那一处。

## 4. 归因裁决

| 候选 | 判定 | 依据 |
|---|---|---|
| 产品缺陷 | **是** | 结算语义（`SheetAudit` 类注："读工具结算 ⇒ readCalls"）与日志不变量都在代码里写死；红跑 64/64 park 工具无 end |
| harness 问题 | 否 | 该断言在 `2ed8f95` 是绿的；before/after 取同一端点；T-13 的前置断言（帧序/结局帧/pending kind/seq）这次全过，只卡最后一行计数 |
| 外部取消 | 否 | 本 run `conclusion=failure`，E2E 跑满 165 条并给 exit=1；无 cancel（被 cancel 的是同期 40174289141） |

## 5. 最小修复（建议，未实施）

在**已经幂等**的两个单写者处补终态结算，不另造守卫：

1. **前端工具（T-13 这条）**：`ai/gate/ConfirmGate.java:1519-1607` 的 `submitFrontendResult`，在 `:1601 out.frontendToolResult(...)` 的同一临界区、取得 `tryRecordDecision` 胜者之后，调用一次 `SheetAudit.record(sessionId, pending)` + 发一条 `tool_call_end`（字段复用 `:613-661` 的 tracePairs 构造，抽成私有方法即可）。`tryRecordDecision` 已是"全集群唯一写者"，天然 exactly-once；重复回灌在 `:1533` 就已 409 返回。
   - 取消分支（`dismiss`，`ConfirmGate.java:999`）同样补一次，覆盖 `FRONTEND_CANCELLED`。
2. **CONFIRM/后端工具在续跑里真正执行的那一支**：`resolvePending` → `executeOnce`（`SpToolCallingManager.java:1368`）成功后补一次同样的结算（认领/`executed` 位已是唯一事实）。
3. 只补第 1 条即可让 T-13 转绿；但要恢复"start 必有 end"不变量，第 2 条也要做。**不建议**在 `resolvePending` 的复用分支直接记账 —— 续跑可被重试/重挂多次，会把 `readCalls` 放大。

## 6. 回归证据（修复后应满足）

- **单测（新增，落点最小）**：`backend/src/test/java/com/example/configmgr/ai/gate/` 下新增（或扩展 `SpToolCallingManagerTraceFallbackTest`）：
  1. 构造 `FRONTEND_RESULT` 已决条目 → 走 `resolvePending` 复用分支 → 断言 `SheetAudit.metrics().readCalls == 1`，且恰 1 条 `tool_call_end`（8 字段带 `defCode/totalRows/sheetVer/dataClass`）；
  2. 同一 runId/toolCallId 再结算一次 → 仍为 1（幂等）；
  3. 现有 `ai/tool/SheetAuditTest.java` 加一条"非空 sheetMeta 的读工具 ⇒ readCalls+1"作为口径锚。
- **E2E（与 CI 同命令）**：`./scripts/verify-e2e.ps1 -Base http://127.0.0.1:18330 -BackendLog <boot.log> -ArtifactDir <gfc> -EnableGf6` ⇒ 期望 `FAIL=0`、`AIE2E-T-13` PASS；另加两条可 grep 的旁证：
  - `tool_call_start` 去重数 − `tool_call_end` 去重数 ≈ 0（绿跑基线 101/100；红跑 97/37）；
  - `GET /api/ai/health → sheet.counters.readCalls > 0`。
- 不需要扩用例集：T-13 本身就是这条缺陷的回归位，已经连续挡住两次（`20c56bb..` 是"done 帧 0 个"，34fd8f4 是"计数不增长"）。

## 7. 与三实例 40182796512 是否同根因：**独立**

同一提交的两个 run，机位完全不同：

| 维度 | main CI 40182795560 | 三实例 40182796512 |
|---|---|---|
| 失败用例 | `AIE2E-T-13`（计数断言） | `suspend-cross-instance`：`SSE 请求超时（120000ms）…?lastSeq=4`（探针 `scripts/ci/cluster3-probe.mjs:1142`） |
| 现象 | 帧/终态全对，只少审计计数 | 决策后**没有续跑**，B 的 SSE 等不到新帧，120s 超时 |
| 代码位置 | 结算缺 `tool_call_end`/`SheetAudit.record`（`SpToolCallingManager:662/664` vs `:1816-1847`） | 续跑前置门 `ResumeService.java:730-746` 的 `PROVIDER_GONE` + 每实例配置快照陈旧（`AiConfigService.java:218/440`） |
| 是否阻塞链路 | 不阻塞（done 正常收敛） | 阻塞（run 卡在 APPROVED/SUSPENDED，直到实例重启才 startup-resume 收尾） |

三实例红的具体链条（证据目录 `cluster3-34fd8f4-evidence/cluster3-evidence-40182796512/`，均为产物原文）：

1. A 起轮并 park（`cases/suspend-cross-instance/A-parked.jsonl` seq1..4；A 日志 `approval_park`）。
2. 冷启动健康探测在 **09:59:18.240（C）/ 18.447（B）** 打 `/api/ai/health`；A 的 AI 配置种子提交在 **09:59:18.470**（`instances/instance-a.log:87` `AI 配置种子已写入`）—— B/C 的配置快照正好落在"库里还没有 provider 行"的窗口（差 230ms / 23ms）。
3. 快照陈旧且**永不刷新**：`AiConfigService.checkReload`（`:218`）只在版本变化时重载；种子不推版本、也不写 Redis `ai:cfg:ver`（`AiConfigSeeder.java:79-108` 只对自己 `reload()`），空表快照在 version=1 上被冻住。旁证：A 在 v1 装配成功（`instance-a.log:94`，`configVersion=1`），B/C 直到 **v3** 才装配成功（`instance-b.log:111` @10:02:22、`instance-c.log:146` @10:05:14）；v3 来自 10:02:03 的 config-hot-reload 用例。
4. 空快照时 `effectiveProviders` 会合成一行 `id=null` 的 env provider（`AiConfigService.java:440-445`），于是 **`/api/ai/health` 判"可用"（200）**；而精确绑定 `resolveChatTarget(providerId=1, modelId=ci-stub)`（`AiConfigService.java:661-678`，被 `ResumeService.java:742` 调用）找不到 id=1 的行 ⇒ 空 ⇒ 硬 409 `PROVIDER_GONE`。
5. B、C 的决策触发续跑都被这条判据拒（`instance-c.log:134`、`instance-b.log:94`），帧停在 seq5 `confirm_decision`（`B-reattach.jsonl`）；run 直到 A 重启（10:02:23 pid4341）后 `resumeReason=startup` 才在 10:02:36 收尾（`instance-a.log:409-411`）—— 探针 120s 早已到期。

最小修复（这条独立排期）：
1. `AiConfigSeeder` 种子提交后**推进 `ai_setting['config.version']` 并写 Redis `ai:cfg:ver`**（一处失效广播，所有实例重载）；
2. `AiConfigService.checkReload` 把"加载成功但 providers 为空而库里已有行"当作未加载（与 `lastLoadFailed` 同待遇）；
3. `ResumeService` 的 `PROVIDER_GONE` 在"本实例快照找不到该 providerId、但库里有"时先重载一次再判，而不是把陈旧视图说成"端点已删除"；
4. 可选加固：`/health/ready` 纳入 AI 配置可解析（现 `SeedReadinessProbe` 只管主数据种子）。
回归：`gh workflow run cluster-3node.yml` 后要求 `suspend-cross-instance` PASS、实例日志无决策触发续跑的 `PROVIDER_GONE`、B/C 的 `configVersion` 与写者一致；单测落点 `AiConfigSeederTest` / `AiConfigServiceTest`。

## 8. 未自证项 / 边界

- 计数器是**进程内**口径（`SheetAudit.metrics()` 注释已声明），单实例 CI 下 `0→0` 判据成立；多实例下该断言本就不能跨实例求和（既有设计边界，不影响本次归因）。
- 三实例那条"B/C 快照为空"与"缺 model 行"两种形态都产出同一个 `PROVIDER_GONE`；现有证据只能证明"B/C 在 v1 上解析不出 (1, ci-stub)、v3 上可以"，`/api/ai/health` 原文未归档，未再细分（A=2750B、B/C=2754B 的字节差只是弱旁证）。
- 未重跑 workflow、未起服务、未改代码；上述修复建议均未实施。
