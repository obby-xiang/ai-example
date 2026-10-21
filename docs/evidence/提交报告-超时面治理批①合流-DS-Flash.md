# 提交报告 · 超时面治理批①（正确性/漂移 12 项）合流

> DC-19 首页：第一屏即可判断「合了什么、sha 是什么、门禁什么数、冲突怎么处置、12 项落在哪、四条待裁去了哪」。

## 0. 身份与结论（DC-19）

- 日期：2026-10-21
- 提交官自证（`profile.bind` 单行，取自本 agent 自己的 wire）：
  `{"type":"profile.bind","agentId":"agent-1411","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high"}`
  → **modelAlias `DeepSeek/deepseek-flash` / profileName `coder` / thinkingEffort `high`**；
  身份核对 = 同 session 目录内 `ls -lat` 取最新建 = `agent-1411`，其 `turn.prompt` 首句为
  「你是提交官，执行「超时面治理批①（正确性/漂移 12 项）」合流 main 全流程」，与本棒任务逐字一致；
  前一棒 `agent-1408` 首句为文书面任务，再前一棒 `agent-1406` = 本批**施工官**（施工报告 §0 自证）。
- 授权：用户 2026-10-21「超时治理开始实施」。
- **结论 = 已合流、门禁全绿、已推送。**
  - 分支单笔：**`4aaf4a2`**（`feature/timeout-b1`，基点 `0dad9ba`；34 路径 = 20 改 + 14 新，
    `git diff --shortstat` = 34 files / +1797 −89，与施工报告 §6 文件清单**逐条相符、无多出、无缺件**）
  - **合并笔：`5b08b0f`**（`main`，`git merge --no-ff`，父 = `0dad9ba` + `4aaf4a2`）
  - 证据笔：`main` HEAD（本文件所在笔，`docs(evidence+board): 超时面治理批①合流证据与挂账登记`）
- 开工基线：`main` = `origin/main` = **`0dad9ba`**（`git fetch` 后两值相同，无需 pull；
  该笔 = 调研 4 件 + 两批立项，7 文件全 docs ⇒ 后端/前端基线数不变 = 1243 / 814）。
- 边界：**未碰 `wt-ai-logtrace`**（日志链路批并行施工的工作树，零读写）；**未起停任何服务**；**未改产品代码**。

## 1. 门禁（合流后主仓复跑）

| 门禁 | main 基线（`0dad9ba`） | 合流后实测 | 判定 |
|---|---|---|---|
| 后端 `mvn -o test` | 1243 / 184 文件 | **1279 / 190 文件 · 0F / 0E / 0S** | ✅ 只升不降（= 1243 + 36 例 / 184 + 6 类） |
| 前端 `vitest run` | 814 / 55 文件 | **819 / 56 文件** | ✅（= 814 + 5 例 / 55 + 1 spec） |
| 前端 `vue-tsc --noEmit` | 0 | **0 error**（exit 0） | ✅ |
| 前端 `vite build`（`TMP/TEMP` 指向仓外 scratch） | ✓ | **✓ built in 1m 11s**（exit 0） | ✅ |
| `scripts/ci/check-docs.mjs` | 6/6 | **6/6**（1626 路径实扫 1623 / 豁免 3；规则自检全绿） | ✅ |
| 脱敏四面独立正则 | — | **0 命中** | ✅ 见 §3 |
| `ResumeServiceLeaseTest`（既有偶发 flake） | — | 本轮 **绿**，无需单例重跑甄别 | ✅ 未复现 |

**后端计数双口径**：Maven 汇总行 `Tests run: 1279, Failures: 0, Errors: 0, Skipped: 0`；
`target/surefire-reports/TEST-*.xml` 求和 = `files=190 tests=1279 failures=0 errors=0 skipped=0` ⇒ **逐值一致**。
+36 例分布 = `RetryBackoffTest` 6 / `AiRetryDefaultsTest` 10 / `AiConnectionProbeTimeoutTest` 3 /
`AiRetiredKeysWarnerTest` 3 / `SessionKeyRegistryTest` 8 / `SseChatEmitterWriteTimeoutMetricTest` 3 /
`ContextSummaryClientTest` +2 / `RunStoreTouchTest` +1。

## 2. 冲突处置与冻结文档注记

- **合流零冲突**：`main` 当时恰在本批基点 `0dad9ba`（分支 rebase 后已含该笔），`git merge --no-ff` 自动成单树；
  无琐碎冲突、无语义冲突，`git status` 合流后仅 `?? data/`（未跟踪运行数据）。
- **冻结规格（`docs/s4/S4.2-AI-Runtime-合流规格.md:73`）**：按冻结纪律**只追加注记行**，原文「`app.ai.session.lock.ttl` / `watchdog` | 轮上限/续期」**逐字未动**。
  注记落点 = §4 配置表之后（`spring.data.redis.*` 行与 `**Q2 修复（必须）**` 段之间），体例照该文件既有 `> ` 引用注记（与页首「日期/地位/依据/范围」同形）：
  说明该表述为 ADR-5 定稿口径，超时面治理批① 第 8③ 项已把**代码自述与 `application.yml` 注释**订正为「互斥窗口上界（非 total-budget）」，现行口径以代码与 yml 为准。

## 3. 脱敏（四面独立正则，0 命中）

复用仓内 `scripts/githooks/scan-lib.sh` 的 SECRET / PATH / USER 三规则、四个独立扫描面：

| 面 | 范围 | 命中 |
|---|---|---|
| A 合流 diff | `0dad9ba..4aaf4a2` 全部新增行（+1797） | **0** |
| B 分支 34 件全文 | 20 改 + 14 新，提交后工作树状态 | **0** |
| C 本棒文档改动全文 | 任务看板 / S4.2 规格 / 本报告 | **0** |
| D 暂存面 | 本笔 `git diff --cached` | **0** |

口径说明：施工面产物（`4aaf4a2`）的 pre-commit 钩子亦在同工作面通过（暂存区无命中），与 A/B 两面互证。

## 4. 12 项落点摘要（细节见施工报告 §1/§2）

| # | 项 | 落点 | 验收锚 |
|---|---|---|---|
| 1 | 流式重试退避 | `ai/run/RetryBackoff.java`（新）+ `ResilientChatService` 接线 | 第 n 次基线 `500·2^(n-1)`、抖动 ∈[0.75,1.25]、父预算内收缩；端到端 ≥350ms 墙钟 |
| 2 | 摘要重试 | `ai/config/AiRetryDefaults.java`（新）+ `AiRetryConfig` bean | 3 次 / 0.5s×2 顶 5s；4xx 不重试；最坏退避 1875ms ≤ 0.2×60s |
| 3 | `/health` 写预算 | `SseChatEmitter` + `RunRegistry` | 指标 = 配置值 24000（未装配回落 15000） |
| 4 | 前端 60s 字面 | `frontend/src/utils/time-constants.ts`（新）→ `api/http.ts` / `frontend-tools.ts` / `stores/workspace.ts` | 三处**同一绑定** = 60_000 |
| 5 | 摘要线程池 | `ai/context/ContextSummaryClient.java` | 非虚拟、池有界、超时 fail-fast 且底层被中断 |
| 6 | 7 键显化 | `application.yml` | 注释列 7 键 = 默认值 + `AiProperties` 出处；**值不变** |
| 7 | 退役键告警 | `ai/config/AiRetiredKeysWarner.java`（新） | 命中 4 键 ⇒ 1 条 WARN 点名新键；未命中 ⇒ 零 WARN |
| 8 | 漂移订正打包 | NUMSPEC 总账 / `AiProperties` 类注 / 锁语义 / `resume` 双缺省 / 看板 R-27·R-25 | 见施工报告 §1 第 8①–8⑤ |
| 9 | E2E 公式化 | `scripts/verify-e2e.ps1` | 180s 与 5min 改由 `total-budget 300s + 60s` 派生 |
| 10 | 探活超时 | `ai/config/AiConnectionProbe.java` | 同一实例两次不同 `probe-timeout` ⇒ 两次 `connectTimeout()` 各异 |
| 11 | 键族注册表 | `ai/run/SessionKeyRegistry.java`（新）+ `RunStore` / `CancellationRegistry` | 17 条登记（run 12 + session 5）；`renewSuspensionTtl` 仍 exactly 5；`ai:cancel` TTL = `session-ttl` |
| 12 | 24h 共亡 | `docs/technical-design.md` | 超时/HITL 节含「24h 滑动共亡 / 无待审批例外 / 取消随会话 / 不落库」 |

**红测留痕**（施工面，如实）：首跑 6 红（4 条改断言、3 条改实现分面）→ 55/55 绿；另有「临时回滚实现」反向红 4 例证明用例有牙齿；既有断言**零删除、零放宽**。

## 5. 四条待裁处置（施工报告 §5 的归宿）

| # | 事项 | 本棒处置 | 落点 |
|---|---|---|---|
| 1 | 摘要 HTTP 层 `responseTimeout` 到不了非流式 `RestClient` 路径（现以「平台线程 + `cancel(true)` + 父预算」收口） | **登记挂账 L-54**（结构性改动，须单独立批） | `docs/任务看板.md` 挂账小节 |
| 2 | `ai:runs`（SET 索引）/ `ai:beat` 读写存疑 | **登记挂账 L-52**，口径 = **仅登记未判定**（判定前不删、不改 TTL） | 同上 |
| 3 | `spring.ai.retry.*` 被自有 `RetryTemplate` 接管后失效 | **登记挂账 L-53**，注明如需可调应键化为 `app.ai.retry.*` | 同上 |
| 4 | `docs/s4/S4.2-AI-Runtime-合流规格.md:73`「锁 TTL = 轮上限」已过期 | **只追加注记行**（原文不动，见 §2） | `docs/s4/S4.2-AI-Runtime-合流规格.md` §4 末 |

**未新登一项**：施工报告 §5-3「`app.ai.sse.write-timeout` 与 `app.task.sse.write-timeout` 同值」已由 NUMSPEC B1-27/28 的既有「变化时复核」条目承接，本棒**不动值、不重复登记**。

## 6. 看板 / 台账落点

- `docs/任务看板.md:13`（超时面治理① 行）：状态 🔧 → **✅**；进展列改为「合并笔 `5b08b0f` / 分支 `4aaf4a2` / 基点 `0dad9ba` / 门禁七项 / 证据件两件 / 12 项摘要」，**立项原文 12 项清单保留在后**（不删史）。
- `docs/任务看板.md` 挂账小节：**新增 L-52 / L-53 / L-54 三条**（紧随 L-51，位于 I-1 之前），计数 **112 → 115**。
- `docs/任务看板.md` 速览行：批次分布 **✅52 → 53**、**🔧1 → 0**，**📋 维持 11**（超时② 与 AI 可观测性两行仍 📋，本批不动；
  故任务令中的「📋11→10」未采纳 —— 若减到 10 则 ✅53+📋10+🔧0+⏸️1+❌2+⏳2 = 68，与「总数 69」自相矛盾），总数**仍 69**；
  并追加「10-21 补记第十七批」两条留痕（本批入库 + 挂账 112 → 115）。
- `docs/决策看板.md`：**未动**（本批零新裁决）。
- 台账 `services.state`：追加一行（见 §7）。

## 7. 文件清单

**分支 `4aaf4a2`（34 = 20 改 + 14 新）**

- 后端主代码改 9：`ai/config/AiConnectionProbe.java`、`ai/config/AiProperties.java`、`ai/config/AiProviderRegistry.java`、
  `ai/context/ContextSummaryClient.java`、`ai/run/CancellationRegistry.java`、`ai/run/ResilientChatService.java`、
  `ai/run/RunRegistry.java`、`ai/run/RunStore.java`、`ai/run/SseChatEmitter.java`
- 后端主代码新 5：`ai/config/AiRetiredKeysWarner.java`、`ai/config/AiRetryConfig.java`、`ai/config/AiRetryDefaults.java`、
  `ai/run/RetryBackoff.java`、`ai/run/SessionKeyRegistry.java`
- 后端测试改 3：`ai/conformance/ChatTurnFrameSequenceConformanceTest.java`、`ai/context/ContextSummaryClientTest.java`、
  `ai/run/RunStoreTouchTest.java`
- 后端测试新 6：`ai/config/AiConnectionProbeTimeoutTest.java`、`ai/config/AiRetiredKeysWarnerTest.java`、
  `ai/config/AiRetryDefaultsTest.java`、`ai/run/RetryBackoffTest.java`、`ai/run/SessionKeyRegistryTest.java`、
  `ai/run/SseChatEmitterWriteTimeoutMetricTest.java`
- 前端改 3 / 新 1 / 测试 1：`src/api/http.ts`、`src/stores/workspace.ts`、`src/utils/frontend-tools.ts` /
  `src/utils/time-constants.ts` / `tests/time-constants.spec.ts`
- 配置 / 脚本 / 文档改 5：`backend/src/main/resources/application.yml`、`scripts/verify-e2e.ps1`、
  `docs/technical-design.md`、`docs/evidence/补档-NUMSPEC-清单总账.md`、`docs/任务看板.md`（仅 R-27 / R-25 两行）
- 文档新 1：`docs/evidence/施工报告-超时面治理批①-DS-Flash.md`
- **未触**：`docs/决策看板.md`、`.gitignore`、帧契约（16 冻结）、任何界面文案 / emoji、密钥字面。

**本棒（证据笔）改动 3**：`docs/任务看板.md`、`docs/s4/S4.2-AI-Runtime-合流规格.md`（仅注记 2 行）、
本文件 `docs/evidence/提交报告-超时面治理批①合流-DS-Flash.md`（新）。

**清理**：`feature/timeout-b1` 已合入 main 后删除分支与 worktree；`git status --porcelain` 终态仅 `?? data/`，HEAD = `origin/main`。
