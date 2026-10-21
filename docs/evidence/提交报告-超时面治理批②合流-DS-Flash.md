# 提交报告 · 超时面治理批②（语义 6 项）合流

> DC-19 首页：第一屏即可判断「合了什么、sha 是什么、门禁什么数、冲突怎么处置、6 项落在哪、五条待裁去了哪」。

## 0. 身份与结论（DC-19）

- 日期：2026-10-21
- 提交官自证（`profile.bind` 单行，取自本 agent 自己的 wire）：
  `{"type":"profile.bind","agentId":"agent-1420","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high"}`
  → **modelAlias `DeepSeek/deepseek-flash` / profileName `coder` / thinkingEffort `high`**；
  身份核对 = 同 session 目录内 `ls -lat` 取最新建 = `agent-1420`，其 `turn.prompt` 首句为
  「你是提交官，执行「超时面治理批②（语义 6 项）」合流 main 全流程」，与本棒任务逐字一致。
- 授权：用户 2026-10-21「超时治理开始实施」+ 决策看板 `docs/决策看板.md:14`（段级预算重置式 + `max-tool-steps=25`）、`:16`（批② 数值集）。
- **结论 = 已合流、门禁全绿、已推送。**
  - 分支单笔：**`8ba1058`**（`feature/timeout-b2`，基点 `d20d75d`；**23 路径 = 17 改 + 6 新**，`git diff --shortstat` = 23 files / +1479 −87，与施工报告 §五 文件清单**逐条相符、无多出、无缺件**）
  - **合并笔：`ac864e1`**（`main`，`git merge --no-ff`，父 = `74aaf96` + `8ba1058`）
  - 证据笔：`main` HEAD（本文件所在笔，`docs(evidence+board): 超时面治理批②合流证据与挂账登记`）
- 开工基线：`main` = `origin/main` = **`74aaf96`**（`git fetch` 后三值相同，无需 pull；≥ 派单要求的 `74aaf96`）。
  自基线 `d20d75d` 起 main 只前进过 3 个文档（`提交报告-日志链路追踪批合流` / `任务看板` / `决策看板`），与本批施工面**零文件重叠**。
- 边界：**未改产品代码**（本棒两笔只含看板 + 证据件）；**未碰 `wt-kb-v1`**（知识问答批并行施工，已 staged 未 commit，零读写零 add）；**未起停任何服务**；`?? data/` 未动未入库。

## 1. 门禁（合流后主仓复跑）

| 门禁 | main 基线（`74aaf96`） | 合流后实测 | 判定 |
|---|---|---|---|
| 后端 `mvn -o test` | 1307 / 197 文件 | **1324 / 202 文件 · 0F / 0E / 0S** | ✅（= 1307 + 17 例 / 197 + 5 类；双口径逐值一致） |
| 前端 `vitest run` | 819 / 56 文件 | **822 / 822 · 56 文件** | ✅（= 819 + 3 例；56 文件不变） |
| 前端 `vue-tsc --noEmit` | 0 | **0 error**（exit 0） | ✅ |
| 前端 `vite build`（`TMP`/`TEMP` 指向 `<SCRATCH>`） | ✓ | **✓ built in 45.86s**（exit 0） | ✅ |
| `scripts/ci/check-docs.mjs` | 6/6 | **6/6**（1645 路径 / 实扫 1642 / 豁免 3；规则自检全绿） | ✅ |
| `ResumeServiceLeaseTest`（既有偶发 flake） | — | **11/11 绿** | ✅ 本轮未复现，无需单例重跑甄别 |
| 脱敏四面独立正则 | — | **0 命中** | ✅ 见 §3 |

**后端计数双口径**：Maven 汇总行 `Tests run: 1324, Failures: 0, Errors: 0, Skipped: 0`；
`target/surefire-reports/TEST-*.xml` 求和 = `files=202 tests=1324 failures=0 errors=0 skipped=0` ⇒ **逐值一致**。
+17 例分布 = `StreamWatchdogSegmentBudgetTest` 3 / `ToolActivityBeaconSegmentTest` 4 / `SpToolCallingManagerStepLimitTest` 4 /
`SpToolCallingManagerToolTimeoutTest` 4 / `AiProviderRegistryUpstreamTimeoutTest` 2（逐个 `Tests run:` 行实读核对）。
基线 1307 与施工报告「worktree 基线 1297 + 17 = 1314」的差 = main 在 `d20d75d` 之后另有日志链路批 +17 类增量，**两口径互不矛盾**。

## 2. 冲突处置

- **合流零冲突**：`git merge --no-ff feature/timeout-b2` 一次成单树，无琐碎冲突、无语义冲突；合流后 `git status --porcelain` 仅 `?? data/`。
- 预判冲突面（`application.yml` / `docs/03-技术方案文档-v2.1.md` / 两看板）均未触发：施工分支未触看板，main 侧只动文档，`application.yml` 在 main 上自 `d20d75d` 未变。
- **施工报告脱敏微修（提交官代手，唯一非施工原文改动）**：pre-commit 首跑拦下施工报告 2 处（`:6` 工地绝对路径、`:118` `TMP/TEMP` 绝对路径）⇒ 按 DC-08 仓内体例改占位符（`../wt-timeout-b2`、`<SCRATCH>/_tmpx`），**纯路径措辞、零语义改动**，改后复扫 0 命中。

## 3. 脱敏（四面独立正则，0 命中）

复用仓内 `scripts/githooks/scan-lib.sh` 的 SECRET / PATH / USER 三规则、四个独立扫描面：

| 面 | 范围 | 命中 |
|---|---|---|
| A 合流 diff | `d20d75d..HEAD` 全部新增行（+1479） | **0** |
| B 分支 23 件全文 | 17 改 + 6 新，合流后工作树状态 | **0** |
| C 本棒文档改动全文 | `任务看板` / 施工报告 / 本报告 | **0** |
| D 暂存面 | 本笔 `git diff --cached` | **0** |

口径说明：施工面 `8ba1058` 的 pre-commit 钩子亦在同一工作面通过（修后“暂存区无命中”），与 A/B 两面互证。

## 4. 语义变更清单核对（对照施工报告 §三，逐条实测）

> 提交官只做**核对**（不重写施工结论）：口径 = 断言只升不降、旧断言未静默删除。

| # | 施工声明 | 提交官实测 | 判定 |
|---|---|---|---|
| S1 | `StreamWatchdogGateWaitBudgetTest` 3 例**一字未改** | `git diff d20d75d..HEAD -- …StreamWatchdogGateWaitBudgetTest.java` = **空** | ✅ 保留 |
| S2 | `DEFAULT_IDLE_TIMEOUT_MS` 90_000 → 45_000；新增 `DEFAULT_MAX_RECONNECTS` | `sse.ts:30` = `45_000`、`:38` = `4` | ✅ 改数值 |
| S3 | 建连黑洞 3 → 5 条连接、退避 1/2/4/8s、`reconnects===4` 值断言 | `sse.ts:44` `RECONNECT_JITTER_RATIO = 0.25`、`:53-54` 指数 + ±25% jitter；前端 822 全绿含该 spec | ✅ 改次数 + 加断言 |
| S4 | 用例标题「90s 帧间」→ 45s（仅措辞） | 改动行均在标题/注记 | ✅ 措辞 |
| S6 | `DEFAULT_CONNECT_TIMEOUT_MS` 口径注重写 | 注释同步，无断言改动 | ✅ 口径注 |
| S7 | NUMSPEC 总账 F1-02/03/05/06 + B1-14 改、B2-01~04 追加 | `docs/evidence/补档-NUMSPEC-清单总账.md` 改 19 行，含 B2 四行 | ✅ 数字台账 |
| S8 | `docs/03-技术方案文档-v2.1.md` 原文不回改、只追加注记 | 该文件 +2 行（注记），原文零删 | ✅ 追加注记 |

**断言只升不降核查**：分支内**未删除任何测试用例**（23 路径中无 `D` 状态）；新增 5 类 17 例；六项语义在合流后代码中逐个落地复核：
`AiProperties:390` `maxToolSteps = 25`、`:411` `execTimeout = 120s`；`application.yml:188/210` 两键显化；
`ErrorCodes:93/100` `TOOL_TIMEOUT` / `TOOL_STEP_LIMIT` 与 `messages_zh/en` 各 2 键、`frontend/src/types/api.ts` 2 键**三方同步**；
`AiProviderRegistry:86` `UPSTREAM_SOCKET_IDLE_TIMEOUT = 300s`。

## 5. 五待裁处置（施工报告 §六）

| # | 待裁项 | 处置 |
|---|---|---|
| 1 | socket 兜底形态 = JDK `setReadTimeout(300s)` 而非 reactor-netty `responseTimeout` | **接受 JDK 读空闲口径**：语义等价（半开连接照样回收）、零依赖新增；本仓编译面确实无 `reactor-netty-http`，为对齐措辞而加依赖不划算。已由 `AiProviderRegistryUpstreamTimeoutTest` 2 例锁定，注释写明「读空闲」而非「响应时长」 | 
| 2 | 长任务 opt-in 免超时（IMPORT/EXPORT） | **转挂账 `L-57`**（批③ 候选）；本批只在 `AiProperties.Tool` 与 `application.yml` 注释登记，不落开关 | 
| 3 | `TOOL_STEP_LIMIT` 码名 + 结局 `skipped` | **照施工**：看板 `:14` 只裁数值 25、未裁码名与结局；`skipped` 不触发「首错即停」符合「部分完成收尾」意图 | 
| 4 | 到顶后不再下发 tools（options 面） | **转挂账 `L-56`**：属请求面结构性改造，与本批「零帧型新增 / 小面改动」边界冲突 | 
| 5 | 整段恢复窗口最坏 90s > 45s 帧间判据 | **接受登记、不改数值**：两者维度不同 —— 45s 是「单条流无进展」判据，90s 是「断流后放弃重连前的总恢复时长」。若强行要求恢复窗口 < 45s，须下调建连上限或重连次数（会削弱抖动容忍），本次不改；已写入 `sse.ts` 注释与 NUMSPEC |
| 6 | 诚实边界（`cancel(true)` 只中断请求 / 池饱和退化同步执行） | **照录不改**：两点已写在 `SpToolCallingManager` 代码注释，本报告同步留存，不掩饰 |

## 6. 看板与台账落点

- `docs/任务看板.md`
  - **`:14` 行** `批·超时面治理②（语义 6 项）`：📋 → ✅；合并笔 `ac864e1` / 分支 `8ba1058` / 基点 `d20d75d`；门禁六项 + 证据件 + **6 项摘要** + **语义变更摘要**（S1~S8 口径），立项原文 6 项清单保留。
  - **挂账小节**：新增 **L-56**（到顶后仍下发 tools，options 面需另批）/ **L-57**（长任务 opt-in 免超时，批③候选）两行，插在 L-55 之后、I-1 之前，编号沿用 L-系连续序号。
  - **速览行**：`挂账小节 116 → 118` 项；批次分布 `✅54 / 📋10` → `✅55 / 📋9`（总数 69 不变）；补「10-21 补记第六批 2 条」说明。
- `docs/决策看板.md`：**未动**（裁决已在 `:14` / `:16`，本棒无新裁决）。
- 台账 `<SCRATCH>/_shared/services.state`：追加一行（合流笔 + 门禁 + 清理结果）。

## 7. 文件清单

### 入选文件（23，分支单笔 + 合并笔同集合）

生产代码 10 = 后端 8（`ai/config/AiProperties.java` / `ai/config/AiProviderRegistry.java` / `ai/gate/SpToolCallingManager.java` /
`ai/run/ResilientChatService.java` / `ai/run/RunSnapshot.java` / `ai/run/StreamWatchdog.java` / `ai/run/ToolActivityBeacon.java` /
`ai/tool/ToolOutcome.java`）+ `common/ErrorCodes.java`，前端 2（`src/api/sse.ts` / `src/types/api.ts`）。

配置与消息 3 = `application.yml` / `messages_zh.properties` / `messages_en.properties`。

测试 6 = 新 5（`StreamWatchdogSegmentBudgetTest` / `ToolActivityBeaconSegmentTest` / `SpToolCallingManagerStepLimitTest` /
`SpToolCallingManagerToolTimeoutTest` / `AiProviderRegistryUpstreamTimeoutTest`）+ 改 1（`frontend/tests/sse-watchdog.spec.ts`）。

文档 4 = `施工报告-超时面治理批②-DS-Flash.md`（新）/ `补档-NUMSPEC-清单总账.md` / `docs/03-技术方案文档-v2.1.md` / 本报告（新）。

### 本棒（证据笔）改动

`docs/任务看板.md`（`:14` 行 + 挂账 L-56/L-57 + 速览行）、本文件。

## 8. 清理与净态

- `git worktree remove ../wt-timeout-b2` ✓；`git branch -d feature/timeout-b2`（was `8ba1058`）✓
- 主仓 `git status --porcelain` = 仅 `?? data/`；`HEAD == origin/main`（推送后复核）
