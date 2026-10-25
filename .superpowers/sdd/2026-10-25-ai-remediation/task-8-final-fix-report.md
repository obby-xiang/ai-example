# Task 8 收尾小项 — 施工与实测报告（2026-10-25，含类型/文档一致性收口）

基线：主仓 `<主仓>`，HEAD `2eb2539` + 工作区全部已合流候选（含 Task8 controller 版与 Task3fix4）。
施工位置：隔离副本 `<隔离副本>/_t8-final-fix2`（= 当前 main 工作树全量 tracked 文件 + 所需 untracked source/test 的基线快照；上一版副本 `_t8-final-fix` 保留作后端逐字节对照）。
主仓代码**零改动、零提交、零推送**；主仓仅新增本报告与 `task-8-final-fix.patch` 两个 SDD 交付件。
Maven：`<本机工具链>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd`（未 clean、未全量、未起服务）；前端 `node_modules` 以目录联接指向主仓同一份（只读使用，未装任何包）。

本版相对上一版的差别只有一条：**把接口/文档一致性一并收进同一交付**（前端类型 2 处 + 1 处注释、技术方案 §5.5 与 §13.2、ADR-5 追加勘误）。**后端生产代码与上版逐字节一致**，故未重复 41 例（证明见 §4.1）。

---

## 1. 小项① 会话锁降级在 health 的可见性

### 事实核对（先查后改）

- 全仓（`backend/src`、`frontend/src`、`frontend/tests`、`docs`）grep `sessionLockDegraded`：**0 命中** —— 该字段此前不存在，不是"已实现待验证"。
- `AiController` 的 `/api/ai/health` 只回 `heldSessions` 与 `sessionLockRenewals`（**成功**续期次数）；`SessionGate#lockLost()` 在 `backend/src/main` 里**没有任何生产消费方**。
- `SessionGate` 字段注释原文称「`/health` 的接线在 `AiController`（不在本类职责内）」—— 与代码不符：接线不存在。

结论：**真缺口**。RT-34 承诺的"降级必须事后也可见"只落在 409 的 `degraded` 与 WARN 日志上，运维轮询的聚合面看不到跨实例互斥已失效。

**容易修错的地方**：降级有两条来源 —— ①取门时 Redis 不可用/结果不可知 ⇒ 只置 holder `degraded=true`，**不**累加 `lockLost`（没走到续期）；②续期失败 ⇒ 置降级**并**累加 `lockLost`。只按 `lockLost() > 0` 计算会把第 1 条（最常见的降级形态）继续漏报，故实现为 `lockLost() > 0 || degradedSessions() > 0`。

### 改动（additive，不改既有键语义）

- `SessionGate.java`：新增只读 `degradedSessions()`（当前仍降级的进程内持有者数）；修正 `lockLost` 字段注释为真实接线与判定式。
- `AiController.java`（health）：`sessionLockLost` = `lockLost()`；`sessionLockDegraded` = `lockLost() > 0 || degradedSessions() > 0`。

未改 `HealthController`（`/health/live|ready`）：那里的降级维度由探针声明（`capabilities.ai` 来自 Redis 探针），与会话锁不是同一事实面；会话锁既有装配点就在 `/api/ai/health` 体内（`heldSessions`/`sessionLockRenewals` 之邻）。未新增端点、未新增错误码。

## 2. 小项② `Busy.unknown` 时的 409 响应体

### RED 复现（真实旧行为）

```
{"code"="SESSION_BUSY", "degraded"=true, "message"="该会话已有一轮对话在进行中，请先处理或重挂该轮",
 "reattach"="/api/ai/events/null", "runId"=null, "sessionId"="s-busy-unknown-holder"}
```

持有者不可知（`Busy(null, true, true)`）时对外给出 **`/api/ai/events/null`** 这条不存在的流地址；同一路径还把 `null` 当 runId 送进 `isDeadRound`（`RunStore.get(null)` + 租约键读取），Redis 抖动时足以把"如实 409"变成异常路径。

### 最小安全口径（所选方案）

保留 **409 + `SESSION_BUSY`**（不重写状态码、不改语义），仅在 `busy.unknown()` 时：

- **不写** `runId`、**不写** `reattach`（没有可指向的轮就不编造；调用方按既有重试/退避恢复，无粘滞）；
- 回 `unknown: true`（唯一新增键）；
- 保留 `degraded`；跳过僵尸轮判定与强释放；审计行 `heldBy` 由 `null` 改显式 `"unknown"`。

持有者可知时行为**逐字节不变**（`runId` + `reattach = /api/ai/events/{runId}` + `degraded`）。

## 3. 一致性收口（本版新增，同一交付）

### 3.1 前端类型（`frontend/src/types/`）

- `api.ts#SessionBusyConflict`：`runId?` / `reattach?` / `unknown?`，注释改为条件式契约 —— 持有者**可知时**必须携 `runId`+`reattach`；**不可知时**后端不编造二者（缺省）、`unknown` 为 true，其余不变（409 + `SESSION_BUSY` + `degraded`），**不得**自行拼 `/api/ai/events/null`，按既有重试恢复。不写死任何具体运行时/实例行为。
- `ai.ts#AiHealth`：补 `sessionLockLost?: number`、`sessionLockDegraded?: boolean`（按实际类型补；可选 = "缺省即未上报，不得据此判为健康"，不引入任何读取逻辑/新功能）。
- `api/http.ts` 头注释：裸对象示例由 `{code,message,runId,reattach}` 改为 `{code,message,runId?,reattach?,degraded,unknown?}`。

全仓 grep：`SessionBusyConflict` 仅此一处声明，无运行时消费方 ⇒ 类型放宽不改变任何行为。

### 3.2 文档（只改与代码行为直接相关的现有文档）

- `docs/03-技术方案文档-v2.1.md` §5.5「刷新/断线恢复」行：改为条件式（持有者可知时必须给 `runId`+`reattach`；不可知时**不得编造**，仍 409 `SESSION_BUSY` + `degraded` 并标 `unknown`，调用方按重试恢复）。
- 同文件 §13.2 #12 行：补同口径的一句回指（不删原方向）。
- `docs/adr/DECISION-CARDS.md` ADR-5：**追加**一条 `【勘误：2026-10-25 · 追加口径，不改上条结论】`，说明"必须携 runId"有一个不可满足边界（诊断读失败/锁已消失 ⇒ 持有者不可知）及追加口径与施工位。**CH-P4 原文一字未改、未删除、未改写历史结论**（`git diff` 对该文件为纯 1 行新增）。

未改（有意）：`docs/03` 头部 v2.1.1 整改说明（历史 revision 记录）、`docs/adr/S3-Challenger审查报告.md` 与 `重审报告`（历史评审留痕）、`docs/evidence/*`（历史证据，其中大量"409 携 runId/reattach"记录属当次实测事实）、九册正文与双看板（本项无新决策、不改排期）。

## 4. 真实验证

### 4.1 后端：RED 5/4 → GREEN 41/41（与上版同一份字节，本版未重跑）

- RED（改前，仅新测试）：`Tests run: 5, Failures: 4` —— 3 例 `No value at JSON path "$.sessionLockDegraded"`；1 例响应体 `{... "reattach"="/api/ai/events/null" ... "runId"=null ...}` 缺 `unknown`；第 5 例（可知持有者反例）RED 阶段即绿。
- GREEN（6 类 41 例，`BUILD SUCCESS`）：`RedisChatMemoryWriteBackRaceTest 6` + `AiSessionPurgeServiceTest 27` + `SessionPurgeRetryRunnerTest 1`（Task3fix4 复验 **34/34**，与主仓期望 34 一致、无放宽）+ `AiControllerHealthSessionLockTest 3` + `ChatSessionBusyUnknownHolderTest 2` + 既有 `ChatSessionBusyBodyTest 2`。
- **本版未重复的证明**：本版副本的后端 4 文件由上一版补丁原样 `git apply` 得到，并与上一版文件逐字节比对 —— 生产 2 文件 `diff -q` = IDENTICAL；两个测试文件仅行尾不同（`git apply` 按 `core.autocrlf=true` 写回 CRLF，与仓内既有测试文件一致），`diff` 忽略 CR 后无差异。本版新增改动全部在 `frontend/src/**` 与 `docs/**`，不触碰任何后端编译单元 ⇒ 41/41 结论继续成立。
- 未复跑 `ParkSettlementAuditTest` + `ParkedFrontendExpiryTest`（12/12 已有结论；两处改动不触及 park/结算路径）。

### 4.2 前端类型检查

```
cd frontend && npx --no-install vue-tsc --noEmit     → exit 0（无输出）
```
`--listFiles` 实测包含 `src/types/api.ts` 与 `src/types/ai.ts`（2 命中）⇒ 门禁确实覆盖本次改动的两个类型文件，不是空跑。

### 4.3 文档门禁

```
node scripts/ci/check-docs.mjs
[PASS] ① 导航/口径文档无盘符绝对路径
[PASS] ② 技术栈版本与 pom.xml / package.json 一致
[PASS] ③ 导航/口径文档引用的文件存在
[PASS] ④ 端口表与 application.yml / vite.config.ts 一致
       （命名纪律扫描：git ls-files 2249 个路径，实扫文本 2246 个；豁免 3 个）
       （规则自检：连写组 5/5；分隔组 24/24；负对照 11 全不误伤）
[PASS] ⑤ 命名纪律禁则（git ls-files 全量）
[PASS] ⑥ .java 探针不参与构建
全部通过：6/6 断言绿。   exit 0
```

### 4.4 现有相关契约测试（前端 409 路径）

```
npx --no-install vitest run tests/aie2e-refresh.spec.ts tests/rg-round-restore-stop.spec.ts
Test Files  2 passed (2)
     Tests  25 passed (25)   exit 0
```
（`aie2e-refresh.spec.ts:221` 即"409 SESSION_BUSY：气泡数回到发送前，提示唯一且不含实现词"，其桩体仍给 `runId`/`reattach` —— 可知持有者路径未受类型放宽影响。）

## 5. 交付清单（`task-8-final-fix.patch`，基线 = 当前 main 工作区）

```
 backend/src/main/java/.../ai/session/SessionGate.java     |  22 ++-
 backend/src/main/java/.../ai/web/AiController.java        |  28 ++-
 backend/src/test/java/.../web/AiControllerHealthSessionLockTest.java  | 130 ++++++++++  （新）
 backend/src/test/java/.../web/ChatSessionBusyUnknownHolderTest.java   | 168 ++++++++++  （新）
 docs/03-技术方案文档-v2.1.md                               |   4 +-
 docs/adr/DECISION-CARDS.md                                |   1 +
 frontend/src/api/http.ts                                  |   2 +-
 frontend/src/types/ai.ts                                  |  11 ++
 frontend/src/types/api.ts                                 |  20 ++-
 9 files changed, 370 insertions(+), 16 deletions(-)
```

- 由隔离副本基线快照 + `git diff` 生成（两新文件带 `new file mode`），**一处补丁含生产/测试/类型/文档全部差异**，无旁路件。
- 已在主仓工作区执行 `git apply --check --verbose`：**9 个路径全部 OK，exit 0**（可与 Task1–8 现有未提交候选叠加）。
- 主仓核验：HEAD 仍 `2eb2539`，`git status --porcelain` 前后均 **86 项**（代码零改动、零提交、零推送；`.superpowers/` 被 gitignore，故交付件不出现在 status）。

## 6. 未验证边界（如实）

1. **未全量**：后端只 6 类 41 例；未跑全量后端/前端全量/`build`、未跑 en-US `-DargLine` 双跑。
2. **未 clean**：后端沿用 `target` 增量编译；前端 `node_modules` 以联接复用主仓同一份（未装包、未改 lockfile）。
3. **无真实依赖**：未起 8081/5202，未连真实 Redis / Redis Cluster / PG / KEK，未做浏览器或真机 E2E。`sessionLockDegraded` 在真实 Cluster 故障下（RENEW 返 0 与连接异常两子路径）仅单测桩证据；本报告**未**把真实 PG/Cluster 标为已验证。
4. **`sessionLockDegraded` 语义**：holder 部分随释放回落（只数当前持有者），`lockLost` 为进程内累计（一次续期失败后重启前恒 >0）⇒ 该键是"当前或历史发生过降级"的告警位，非瞬时值；前端类型按可选键登记且**无消费方**，未新增任何读取/展示逻辑。
5. **`unknown` 键无前端消费方**：仅作为契约诚实信号，未改变任何前端行为（`stores/ai.ts` 的 409 分支仍只给本地化提示 + 回收乐观气泡）。
6. **历史文档面**：`docs/evidence/*`、S3-Challenger 审查/重审报告、`docs/03` 头注等历史留痕未回改（按"不改写历史结论"）；ADR 采用追加勘误，未动 CH-P4 原文。
7. 未新增/修改任何错误码（`ErrorCodesSingleSourceTest` 面不受影响）；未改 TTL、purge、确认门、结算与审计幂等语义；未改 `HealthController` 两个探针端点。

## 7. 与上一版报告的差异

上一版登记为"未收口"的两项 —— ①前端 `SessionBusyConflict` 仍把 `runId`/`reattach` 写成必填；②`docs/03`/ADR-5 的"409 必须携 runId"契约措辞 —— **本版已全部收口并进同一补丁**，不再留给后续 Task。其余登记项（真实依赖、全量门禁、`sessionLockDegraded` 瞬时/累计语义、`unknown` 无消费方）维持"未验证/已登记"口径，未伪装为已完成。
