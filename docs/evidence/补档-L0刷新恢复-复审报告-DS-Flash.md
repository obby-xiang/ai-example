# 补档 · L0 刷新恢复漏洞修复 复审报告（DS-Flash，2026-10-21）

## 0 自证行（wire 实测，非照抄本卡）

- 会话 id：`session_081d49e8-17a5-451b-befe-c0422c23a69b`
- 复审 agent：`agent-1294`；wire = `<KIMI_HOME>/sessions/<SESSION_ID>/agents/agent-1294/wire.jsonl`（首行为 metadata，下文按卡要求 `grep profile.bind` 取单行）
- 实测单行（截取）：`{"type":"profile.bind","agentId":"agent-1294","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high",...}`
- 只读纪律：**零仓库写操作**（无 commit / push / stash）、**零服务启停**；唯一新增文件 = 本报告。diff 取数 = 5 个未跟踪新件逐个 `git add -N <file>` → `git diff` → `git reset -q` 还原（已复核还原后 `git status` 仅余原 6 个 `??` 条目，`data/` 未动）。

### diff 锚（SHA-256，取数可复现）

| 取数面 | 行数 | SHA-256 |
|---|---|---|
| 全量 diff（生产 7 + 测试 4 改 3 新 + 文档 6，含 `-N` 新件） | 1518 | `e79a1ca9a30e3aa2115d37f63a827e984cbae410c158fd170067b94bb8ea7161` |
| 生产-only（`backend/src/main` + `frontend/src`） | 276 | `794d53f2f017211830e5d6d26cea816fd26b5396678572bed8f41e804b4d0077` |

两次独立取数（先分面、后全量）哈希一致，未见工作树漂移。

## 1 结论：**CHANGES-REQUESTED**（0C / 1I / 4M / 3N）

A~E 五条裁决**逐条落地、无人违反**；实时路径确为零改动；门禁全绿（后端 584/584、前端 510/510、typecheck 0、check-docs 6/6）。打回的唯一实质原因在**「修复后残余窗口的如实申报」**：施工报告 §7 偏差 1 把「`suspended=true` 但无卡无连接」判为「毫秒级、自愈」，实测该窗口的真实上界是**前一张卡的工具执行时长**，且**不存在自动自愈路径**（需用户再刷新一次）；同一段的「旧载荷再刷新一次即恢复」对**旧挂起轮**同样不成立。这两条不改代码也要改结论与挂账。另附 1 个 3 行可补的代码洞（M-1）、1 处多实例丢失决策窗口（M-2）与 2 处申报/测试现实性问题（M-2b、M-3）。

## 2 裁决 A~E 逐条核对

| 裁决 | 落点（实测行号） | 判定 | 说明 |
|---|---|---|---|
| **A** pending 增 `req`/`reqAtMs`，发射时置位，`/runs/current` 透出 | `backend/.../ai/run/PendingToolCall.java:156`（`Boolean req`）、`:159`（`long reqAtMs`）、`:224-225`（`asMap` 透出）；`RunStore.java:413-422`（`markRequestEmitted`）；`ConfirmGate.java:313-316` + `:439-460`（`reserveRequestSlot`，调用早于发帧 `:328-335`）；`AiController.java:466-467` | **PASS** | 置位点确在 `confirmRequest`/`frontendToolRequest` **之前**（`reserveRequestSlot` 在 `await` 第一行）。`Boolean` 三态（null=旧数据/未发）语义自洽；`/pending/{runId}`（`AiController.java:873-878`）同口径，纯增量、无严格键集消费方。 |
| **B** 透出真实 kind 三值，前端按真值过滤 | 后端 `AiController.java:458`（`pending.getKind()` 原文，本批未改）；前端 `stores/ai.ts:1312-1314`（`kind !== 'CONFIRM' && kind !== 'FRONTEND' → continue`）、`:1321`（`const kind = entry.kind`，三元兜底已删）；用例 `AiControllerRunsCurrentTest.java:195-211` | **PASS** | 旧二值映射（非 CONFIRM 一律 FRONTEND）已消除；`unresolvedExternal` 仍排除 BACKEND（`:477-480`），与 ResumeService 口径一致。 |
| **C** 发帧前读 `resolved()`，已决跳过发帧与 `completeSubscriptions` | `ConfirmGate.java:313-316`（早退）、`:439-460`（同 `submitLock` 内读+判）、`:352`（断连点未被走到） | **PASS（带 M-1/M-2 保留）** | 早退分支行为与「进循环后读到已决」（`:366-371`）**等价**，只少一次 emit + 断连；调用方语义（APPROVED→executeOnce、其余→未执行回填）由 `SpToolCallingManager.java:579-587` 承担，未变。 |
| **D** 三判据建卡 + GF 同条件 + `suspended`=任意 PENDING + 未发卡不占位 | `stores/ai.ts:1304`（PENDING 过滤）、`:1305-1309`（`suspended`/`activeRunId`，与 req/kind 无关）、`:1315-1317`（`req !== true` 跳过）、`:1353-1366`（GF 武装在过滤之后，`req=false` 既不建段也不武装） | **PASS** | 未新增任何占位 UI；`host` 仅在真正建卡时产生（`:1369-1372`），无卡即无空助手消息、无续收连接。 |
| **E** 实时路径零改动 / 无新帧类型 / `/runs/current` 只增字段 | diff 面：`stores/ai.ts` 只有 `restoreSuspendedSnapshot`；`utils/parts.ts` 仅注释（`:148-154`）；`types/ai.ts` 仅两字段（`:531-541`）；无帧类型增删；`AiController` 只 +2 键 | **PASS** | 实时 `suspended` handler 本就只为首个 CONFIRM/FRONTEND 建段（`stores/ai.ts:2783-2797`、`parts.ts:766-777`），故「一批多卡在实时路径同时铺卡」不成立，与施工报告 §2 洞 2 的「实时路径无此问题」相符。 |

## 3 缺陷攻击面逐条

### 3.1 `reserveRequestSlot` 的锁语义与竞态窗口
同进程内**无窗口**：`reserveRequestSlot` 的「读→判→置位」全在 `submitLock` 内（`ConfirmGate.java:441-459`），而 `submitDecision`/`submitFrontendResult`/`dismiss` 的「落库→发回执帧→唤醒」也在同一把锁内（`:631-635`、`:703-707`、`:741-745`）。`submitDecision` 的**读**在锁外（`:604`）但**写**在锁内，且读到的旧副本写回时状态已变终态，`req` 被覆盖也无消费者（前端只为 PENDING 建卡）—— 无危害。跨进程（多实例）才有窗口，见 M-2。

### 3.2 `req` 的「读→判→写」原子性
**非原子**：`markRequestEmitted` = `pending()`（`RunStore.java:386`，反序列化新副本）→ 判 PENDING（`:415`）→ `putPending`（`:361-369`，整对象 `HSET` 覆写）。Redis 层无 CAS / Lua。单实例（DC-11 裁决的部署形态）不可达；多实例下窗口 = 一次 `HGET` + 一次 `HSET`，后果是**丢失决策**（把已决条目回写回 PENDING）。危害与既有 `submitDecision` 的「锁外读 + 全量覆写」同族（非本批新增类别），但本批**新开了一个写者**。报告 §7① 称「`markRequestEmitted` 的『只置位 PENDING』守卫兜底」——该守卫只覆盖「读时已决」，**不覆盖「读后写前被决」**，措辞需改。见 M-2。

### 3.3 已决早退归还 arm 预登记闸门的正确性
**正确，无误放亦无误堵**。三向核对：
- 不误堵：`arm` 在 `suspended` 帧之前为**整批**预登记（`SpToolCallingManager.java:255`），`disarm` 只在整批结束的 `finally`（`:343`）；若已决条目不自还，`hasActiveLatch` 会在整批结算前恒真，挡住死卡兜底（`ConfirmGate.java:785`、`:825`）。自还是必要的。
- 不误放：删除只针对**本 key**；同批其他仍在等待/预登记的条目各自持有自己的 key，`hasActiveLatch` 仍真 ⇒ 不会诱发 `ACTIVE_LATCH_IN_PROCESS` 失效后的双驱动。
- 顺序正确：先 `latches.remove` 后 `armedKeys.remove`（`:445-448`），与 `disarm`（`:281-285`）和 `await.finally`（`:408-409`）同序；后续 `disarm` 对已摘键幂等。
- 早退不进 `finally` 不泄漏资源：早退点在 `cancelHandle`/`beacon`/`sessionId` 创建之前（`:318-357`），无需收尾。

### 3.4 三判据 × `suspended`=任意 PENDING 的场景矩阵
| 场景 | `awaitingExternal`（真实口径 `AiController.java:484`） | 建卡 | `suspended` | 续收连接 | 评价 |
|---|---|---|---|---|---|
| 单卡批次、`req=true` | true | 1 张 | true | 有 | 正常 |
| 多卡批次、卡 2 未发（卡 1 等待中） | true | 仅卡 1 | true | 有 | 目标形态 |
| 多卡批次、卡 1 已决且工具执行中（卡 2 `req=false`） | true（卡 2 计入 `unresolvedExternal`） | **0 张** | true | **无** | **死胡同窗口**，见 I-1 |
| 全 BACKEND 批次 | **false**（BACKEND 被 `:477-480` 排除） | 0 张 | **false**（`:1279` 早退） | 无 | 与 `restore-request-emitted.spec.ts:110-124` 的断言不一致，见 M-3 |
| 旧载荷缺 `req`（含**旧挂起轮**） | true | 0 张 | true | 无 | 永久无卡，见 M-2 |
| 全部条目为已决残留 | true | 0 张 | true（旧行为为 false） | 无 | 裁决 D 字面允许，属副产品变化，N-3 |

「未发卡不占位不渲染」本身**没有**带来额外渲染面：`messages` 不增条、不增段（用例 1/2/3/5 都断言了段数），无空卡。

### 3.5 毫秒级窗口「suspended=true 但无卡无连接」的真实可达性与自愈
**可达性：真实，且上界远大于毫秒**。两段窗口：
- 段 ①（真·毫秒级）：`suspended` 帧发出（`SpToolCallingManager.java:263-264`）到卡 1 的 `reserveRequestSlot`+emit 之间，仅一次 Redis 读 + 闸门查找 + emit。
- 段 ②（**可达数秒～数分钟**）：卡 1 已决（状态非 PENDING）→ 同批循环继续执行卡 1 的工具（`SpToolCallingManager.java:271-331`，`settle` 置 RUNNING 在整批结束的 `:338`/`:794`，期间快照仍是 SUSPENDED）→ 才轮到卡 2 的 `await`。此段内卡 2 一直 `req=false`，`unresolvedExternal` 非空 ⇒ `awaitingExternal=true`。**刷新落在任何一次导出/导入类工具执行里，就会命中**。
**自愈：不存在**。无卡 ⇒ `host===null` ⇒ 不调 `resumeSnapshotStream`（`stores/ai.ts:1369-1372`）；卡 2 的请求帧到达时后端无订阅者，帧只入归档（B1：`confirmRequest` → `emit` → 归档），浏览器侧什么都收不到；前端只有两处调 `runsCurrent`（`stores/ai.ts:1269`、`:1840`），均**非定时**。因此「后端帧到达即恢复」不成立，**必须用户再手动刷新一次**。唯一即时出口是「停止」按钮（`AiPanel.vue:744` 的 `v-if="ai.loading || ai.suspended"` → `stop()` `stores/ai.ts:2305` 清 `suspended` 并发取消）。
**用户可见面**：输入框硬禁用（`AiPanel.vue:1285-1291`），占位文案「请先在上方处理确认/执行」（`:1445-1447`）而上方无卡。→ **I-1**。

### 3.6 测试断言只升不降
逐处核对既有 spec 的 7 处夹具改动（`restore-reconcile.spec.ts:52`、`:214`；`ai-panel-parts-render.spec.ts:384`、`:2602`、`:2625`、`:2645`；`frontend-tool-result-terminal.spec.ts:134`）：**只补 `req: true`，零断言删除/弱化**；后端 `AiControllerRunsCurrentTest` 为纯新增 2 例（9→11），无改动既有断言。新增 6 例（前端）+ 6 例（后端 2 类）覆盖：逐卡置位、BACKEND 不置位、`frontend_tool_request` 置位、已决不重发请求帧、不收流、闸门不残留、`req`/`reqAtMs` 两态、kind 三值。**结论成立**。

### 3.7 脱敏
全量 diff（1518 行）脱敏扫描：密钥类前缀、Bearer 令牌、`api-key` 赋值、盘符绝对路径、用户主目录、仓外工作目录（`_cu-` / `_b1-` / `_cpk-` 前缀）、内网地址与 `localhost` 端口 —— **除一处历史遗留外零命中**。唯一命中在 `docs/决策看板.md` 的「现状速览」长段（`_cu-gf-fix-r26/`、`_cu-docs-census/`），该段本批只改了 117→118 / 95→96 / 86→87 三个数字，路径为**入库前既有内容**，非本批引入。调研件与仓外原件逐行 `diff`：**仅第 7 行「边界/入库」声明一行不同**，其余一字未改，与报告 §4 相符。

## 4 夹带扫描（设计未点名的行为变更 / 文案 / 样式）

| 面 | 结论 |
|---|---|
| 生产代码 | 无。`parts.ts` 仅注释；`types/ai.ts` 仅两字段；`stores/ai.ts` 仅 `restoreSuspendedSnapshot`；后端 4 文件均在 A/C 范围内。 |
| 行为副作用 | 1 处（**N-3**）：`suspended` 由「循环内、建卡后置位」改为「循环外、任意 PENDING 置位」，使「全部条目为已决残留」的场景从「不禁用输入」变为「禁用输入」。裁决 D 字面允许（「suspended 置位 = 存在任意 PENDING」），但报告未点名为副产品。 |
| 文案 / 样式 | 无新增文案、无 Tailwind/样式改动、无新 UI 元素。 |
| 帧协议 | 无新帧类型、无字段删除；`/runs/current` 只 +`req`/`reqAtMs`；`/pending/{runId}` 同口径 +2 键。 |
| 文档 | 6 件均为本批声明面（技术方案只追加注记、两个看板、调研件入库 + 索引、施工报告）。`docs/research` 件数 37 − 7 raw − README = **29**，与索引计数一致。 |

## 5 门禁实测（复审自跑，非引用施工自报）

| 门禁 | 命令 | 实测结果 | 与施工报告 |
|---|---|---|---|
| 前端全量单测 | `cd frontend && node node_modules/vitest/vitest.mjs run`（未加 `--reporter=basic`） | **510/510 · 26 文件**（10.49s） | 一致 |
| 后端全量单测 | `<MAVEN_HOME>/mvn.cmd -o -q test`（`backend/`） | surefire 汇总 **93 类 / 584 例 / 0 Failures / 0 Errors / 0 skipped**；报告文件 mtime 均落本次运行窗（00:24–00:26） | 一致 |
| 前端类型 | `node node_modules/vue-tsc/bin/vue-tsc.js --noEmit` | **exit 0** | 一致 |
| 文档门禁 | `node scripts/ci/check-docs.mjs` | **6/6 PASS**；`git ls-files` **1320** 路径 / 实扫 1317 / 豁免 3（比报告的 1315 多 5 —— 本次把 5 个新件 `-N` 进索引后复跑，新件**也过了命名纪律**） | 更强 |
| E2E | 未运行 | 按分派卡「服务归主会话」 | 一致 |

## 6 问题清单

### I-1（Important）偏差 1 的窗口与自愈路径申报失实，且该残余窗口未挂账
- 事实：窗口上界 = **前一张卡的工具执行时长**（`SpToolCallingManager.java:271-331`，期间快照仍 SUSPENDED、`awaitingExternal` 仍 true），不是「<1 个 emit 周期」；且**无自动自愈**（无卡 → 无连接 `stores/ai.ts:1369-1372`；帧只入归档；`runsCurrent` 无定时调用）。
- 影响：用户看到「输入禁用 + 提示按上方卡处理 + 上方无卡」，只能点「停止」或**再手动刷新**。
- 要求：① 报告 §7 偏差 1 与 §1/§3-D 的「毫秒级/自愈」表述改为实测口径；② 该窗口登记挂账（现文明写「未登记挂账，理由：窗口 <1 个 emit 周期且自愈」——理由不成立）；③ 若要真正消掉，需「有 PENDING 但无卡也续收/轮询一次」，属新行为，另行裁决（不得顺手做）。

### M-1（Minor）`markRequestEmitted` 的返回值未再判 `resolved()`
- `ConfirmGate.java:453-458`：`marked` 可能因「读后写前被决」而返回一个**已决条目**（`RunStore.java:415-417` 原样返回），但代码只取 `getReqAtMs()`，随后 `return null` ⇒ `await` 照常 emit 请求帧 + `completeSubscriptions`。裁决 C 的洞在更窄窗口复发（跨进程；单实例由 `submitLock` 挡住）。
- 修法（3 行）：`if (marked == null || marked.resolved()) { /* 归位闸门 */ return marked; }`，并补一例「置位瞬间已决」用例（可用 `InMemoryRunStore` 的 `setDeepCopyPending(true)` 注入）。

### M-2（Minor）跨实例丢失决策窗口 + 报告措辞
- `RunStore.putPending` 是整对象覆写（`:361-369`），`markRequestEmitted` 无 CAS；多实例下「读 PENDING → 他实例 APPROVED → 本实例写回 PENDING」会**丢决策**。DC-11 为单实例部署 ⇒ 当前不可达，属 B3 面。
- 要求：① 报告 §7① 删掉「只置位 PENDING 守卫兜底」这一误导表述，改为「守卫只覆盖读时已决；读后写前被决存在 lost-update 窗口，单实例不可达、多实例随 B3 收敛」；② 与既有「多实例确认误路由」挂账同列。

### M-2b（Minor）「旧载荷再刷新一次即恢复」对旧挂起轮不成立
- 旧挂起轮（升级前挂起、重启后无人等待）的条目 `req` 永远是 `null` —— 没有进程会再走到它的 `await`，任何次数刷新都不会建卡，直到 session TTL。报告 §3-D 末段与 §7⑤ 的「再刷新一次或等下一次决策后重连即恢复」只对**本轮仍在跑**的条目成立。
- 要求：改措辞 + 在挂账里写明「升级穿越期旧挂起轮 = 无卡僵尸（靠取消/TTL 收敛）」。

### M-3（Minor）`restore-request-emitted.spec.ts` 例 2 断言了 API 不产生的状态
- 该例夹具 `unresolvedExternal=[]` 却硬写 `awaitingExternal: true`（`snapshot()` 默认值），而生产口径是 `awaitingExternal = SUSPENDED && !unresolvedExternal.isEmpty()`（`AiController.java:484`）⇒ 全 BACKEND 批次真实返回 `awaitingExternal=false`，`restoreSuspendedSnapshot` 在 `stores/ai.ts:1279` 早退，`suspended` **不会**置位。故 `expect(store.suspended).toBe(true)`（`:124`）锁的是不可达状态（「无卡」断言本身仍有效，锁的是 kind 判据）。
- 要求：把该例拆为「kind 过滤（无卡）」+「awaitingExternal=false 时整体早退」两段，或如实在注释里标注「本夹具的 awaitingExternal 与生产口径不一致，仅验证 kind 过滤」。

### N-1（Nit）`reqAtMs` 的 `doesNotExist()` 并未验证「键仍在」
`AiController.java:467` 恒 put `reqAtMs`（未发射为 null），`AiControllerRunsCurrentTest.java:183` 用 `doesNotExist()`；JsonPath 对显式 null 与缺键都返回「不存在」，断言通过但**不区分**两者，与用例注释「键仍在」不符。建议改 `jsonPath("$.data.pendingEntries[0].reqAtMs").value(nullValue())` 或 `isEmpty()`。

### N-2（Nit）「先置位、后发帧」的顺序没有被测试直接证明
`ConfirmGateRequestEmittedTest.java:92-119` 在**观测到帧之后**读 Redis 断言 `req=true`，正反两种顺序都能过。若要锁顺序，需在 `FrameWire` 侧加「emit 时回调读条目」的钩子。

### N-3（Nit）`suspended` 前置置位的副产品未点名
见 §4 表第 2 行：全部条目为已决残留时，快照恢复现在会禁用输入（旧行为不禁用）。符合裁决 D 字面，建议在报告 §3-D 加一句留痕。

### 观察（非本批缺陷，供下批）
- 全 BACKEND 批次：`awaitingExternal=false` ⇒ 刷新恢复完全不介入、输入不禁用，而该批仍在执行。属既有口径（`unresolvedExternal` 排除 BACKEND），本批未改变，但 M-3 的夹具正是踩在这个口径上。
- 报告 §5 末行建议的 E2E TC23 补 `req` 断言（施工方主动申报「未加、建议下批补」）—— 合理，记为后续项。

## 7 复现路径（供复核者）

```bash
cd <REPO_ROOT>
git add -N backend/src/test/java/com/example/configmgr/ai/gate/ConfirmGateRequestEmittedTest.java \
           backend/src/test/java/com/example/configmgr/ai/gate/ConfirmGateResolvedSkipTest.java \
           frontend/tests/restore-request-emitted.spec.ts \
           docs/evidence/补档-L0刷新恢复-施工报告.md \
           docs/research/调研-批量HITL呈现与推进-业界惯例-2026-10-20.md
git diff | sha256sum          # 期望 e79a1ca9…7161
git diff -- backend/src/main frontend/src | sha256sum   # 期望 794d53f2…0077
git reset -q
```
