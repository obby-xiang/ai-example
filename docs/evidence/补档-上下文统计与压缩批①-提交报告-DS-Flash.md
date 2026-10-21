# 补档 · 上下文统计与压缩专项批① 提交报告（DS-Flash，2026-10-21）

## 0 自证与入库锚

- 提交官自证（wire `profile.bind`：**`grep -c '"type":"profile.bind"'` 全文件命中数 = 1**，该行是文件第 **5** 行〔前 4 行是 metadata / runtime 绑定 / 工具发现 / 权限模式〕，**未用 `head -1`**）：`agentId=agent-1353`、`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`。
- 三笔入库（逐笔 push，基线 = `3c79eaa`，`origin/main` 同步至第三笔）：
  - **主体笔 `35a30ad`**（`feat(ai-context): 上下文统计与压缩批①（计量+展示+L1清除）`，44 文件 +5416 / −34）；
  - **看板笔 `0507a37`**（`docs(board): 上下文统计与压缩批① 销记 + 挂账 A-4（分布式 Redis Cluster 适配审查）`，2 文件 +7 / −5）；
  - **证据笔**（本件所在提交）的 sha **不可自引用**（`git log --oneline -3`）。

## 1 门禁（提交官本机复跑；口径以本表为准）

| 面 | 命令 | 实测 | 判据 |
| --- | --- | --- | --- |
| 后端 | `cd backend && mvn -o test`（`<MAVEN_HOME>/bin/mvn`，本地 3.9.16） | **939 / 939 · 0F / 0E / 0S**，**连跑 2 次**同数（`BUILD SUCCESS`、`exit 0`；每跑前清 `target/surefire-reports`） | `Tests run: 939, Failures: 0, Errors: 0, Skipped: 0` |
| 前端 | `cd frontend && node node_modules/vitest/vitest.mjs run` | **545 / 545 · 29 文件**（`exit 0`；Duration 16.31s） | `Test Files 29 passed (29)` / `Tests 545 passed (545)`；无 Unhandled Rejection |
| typecheck | `node node_modules/vue-tsc/bin/vue-tsc.js --noEmit` | **exit 0**（0 错，输出 0 行） | — |
| build | `npm run build` | **exit 0**，`✓ built in 1m 2s`（vite 6.4.4） | — |
| check-docs | `node scripts/ci/check-docs.mjs` | **6/6 断言绿**（命名纪律扫描 `git ls-files` **1440** 路径 / 实扫 **1437** / 豁免 3） | 六条逐条 `[PASS]` |
| 脱敏 | `bash scripts/githooks/pre-commit`（每笔提交前手跑 + 钩子复跑） | **0 命中**（三笔均 ✓，未用 `AI_SECRETS_ALLOW` / `--no-verify`） | `✓ [pre-commit] 敏感信息扫描通过（暂存区无命中）` |

**数字对账**：939 = 主线 859 + 施工 68 + 收口 10 + **提交官加固性用例 2**（基数 937 → 939，与任务书"939 量级"**逐数吻合**）；前端 545 = 522 + 23（`context-pill` 17 + `context-restore` 6），29 = 27 + 2 文件。**939 与 937 的差仅这 2 例**，无其它增删。

## 2 主体笔内容（44 文件）

| 面 | 文件 |
| --- | --- |
| 后端新增（11） | `ai/context/`：`ContextEstimator` / `ContextMeter` / `ContextBreakdown` / `ContextLimitResolver` / `ContextTrigger` / `ToolResultClearing` / `ContextState` / `ContextStateStore` / `ContextCompactor` / `ContextBreakdownService` / `ContextConfig` |
| 后端修改（6 + 1） | `AiProperties`（Context 13 键 + `verifyContext`）· `ResilientChatService`（meter / `done.usage` 四增量键 / `done.context` / `context` 帧 / 轮末记账 / `disclosedToolTokens` / `noteCompaction` / **`clearCompaction`**）· `ResumeService`（第二个压缩调用点 / 发帧 / 级别交接 / **池饱和清交接位** / **发帧 try-catch**）· `RunStore`（`ai:ctx:` 三键族）· `SseChatEmitter`（`context` 帧 + `done(..., context)`）· `AiController`（`GET /api/ai/context/{sid}` + `/health` 两字段） · `application.yml`（`app.ai.context.*`） |
| 后端测试（11） | `ai/context/` 8（含替身 `InMemoryContextStateStore`）+ `AiPropertiesContextGuardTest` + `ContextFrameWiringTest` + `ResumeContextCompactionTest` + `AiControllerContextEndpointTest` |
| 前端新增（3 + 2 spec） | `ContextPill.vue` / `ContextPopover.vue` / `utils/context-usage.ts`；`tests/context-pill.spec.ts` / `tests/context-restore.spec.ts` |
| 前端修改（6） | `types/sse.ts`（帧类型 15 → 16 + `SseContextFrame`）· `types/ai.ts` · `api/ai.ts` · `stores/ai.ts`（三态 + 轮末入库 + 刷新重建）· `AiPanel.vue`（底栏 pill 位）· `utils/error-card.ts`（"15 类帧"注释同步） |
| 既有文档（3） | `docs/technical-design.md`（「上下文计量与压缩」节）· `docs/test-cases.md`（CTX-01…08）· `docs/evidence/补档-NUMSPEC-清单总账.md`（13 键 + 3 常量） |

## 3 三条复核微修（M-a / N-a / N-b）与红/绿证据

| # | 改动 | 落点 | 反向验证（把修法改回错误形态 ⇒ 红） |
| --- | --- | --- | --- |
| **M-a** | `compact()` 方法注「快照 → 记忆」→「**记忆 → 快照**」+ 指向类注差异段 | `ContextCompactor.java:157-159` | 文案面，无用例（注释不参与执行）；与类注、`compactBeforeRequest`、`ResumeService` 三处措辞已对齐 |
| **N-a** | `POOL_SATURATED` catch 里 `chatService.clearCompaction(runId)`（新增方法 = `compactionLevels.remove`，**不是** `noteCompaction(runId, null)`〔后者写 `"none"` 仍占位〕） | `ResumeService.java:651-656` · `ResilientChatService#clearCompaction` | `ResumeContextCompactionTest#poolSaturationClearsTheCompactionHandoffSlot`：改回（删清理行）⇒ **`Wanted but not invoked: chatService.clearCompaction(...)`** |
| **N-b** | `out.context(...)` 包 `try/catch(RuntimeException)` + **WARN**；`noteCompaction` 与交棒不在 catch 内 | `ResumeService.java:614-629` | `ResumeContextCompactionTest#contextFrameFailureDoesNotBlockResume`：改回（`catch` 里重新抛出）⇒ **`java.lang.IllegalStateException: emitter closed`** 穿出 `resume` |

**红/绿实测**：两处修法临时改回错误形态后跑 `-Dtest=ResumeContextCompactionTest` ⇒ `Tests run: 4, Failures: 1, Errors: 1`（新 2 例红、既有 2 例仍绿）；恢复后 4/4 绿、全量 939/939。

> **口径说明（任务书要求注明）**：N-a / N-b 这两条**不是红测先行**——复核微修先落地、用例随后补，故按"**加固性用例 + 反向验证**"登记（红证据 = 把修法改回错误形态后复红，见上表）。三条微修均未改动任何既有断言。

## 4 看板留痕

| 面 | 位置 | 内容 |
| --- | --- | --- |
| 本批行 | `docs/任务看板.md:17` | 📤 → **✅**；回填 `35a30ad` + 门禁 + 帧契约 15 → 16 + 四件证据 + **自决 8 条** + **缺口登记 3 条** |
| 挂账新增 | `docs/任务看板.md:429` | **A-4 分布式 Redis（Cluster）适配审查**（⏸️ 待用户启动令；⏸️ 启动前先业界调研） |
| 共同注记 | `docs/任务看板.md:431` | A-1~A-3 → **A-1~A-4** |
| 速览 | `docs/任务看板.md:433` | 批次 **✅48 / 📋13 → ✅49 / 📋12**（总数 66 不变）；挂账 **77 → 78**；补记第七批 2 条；数据截止 → 14:50 |
| 决策看板 | `docs/决策看板.md:13` | 新增「上下文统计与压缩批① 收口裁决」行（I-1 方案① / M-4 文案修正非降格 / 复核 M-a·N-a·N-b 随提交官收口、N-c·N-d 登记不改） |

**A-4 的三条已知线索**（写进看板原文）：①单键脚本已核 Cluster 安全（`ai:lease:` / `ai:fence:`，批② 的全 Lua 只操作单键）；②`RedisChatMemoryRepository` 的**多键**脚本有 CROSSSLOT 面（需 hash tag 或拆键）；③`ai:idem:` / `ai:cfg:` / `ai:rate:` / `ai:ctx:` 与后续批新增键族**逐面待核**。

## 5 CI

repo = 本仓远程 `origin`（GitHub），workflow = 「CI 门禁」（后端编译与单测 / 前端类型检查与构建 / check-docs〔阻断〕/ 全历史脱敏扫描〔报告制〕/ E2E 全流程 29 用例）。

| run | 笔 | 结果 | 说明 |
| --- | --- | --- | --- |
| **39557930065**（`35a30ad`） | 主体笔 | **attempt1 红 → attempt2 `success`（5/5 绿）** | attempt1 红于 **L-19 同族既有抖动**：`ResumeServiceLeaseTest.concurrentSameRunResumesDoNotLetTheLoserReleaseTheWinnersLease:302`，`Expecting actual [ACCEPTED, ALREADY_RUNNING] to contain exactly in any order [ACCEPTED, IN_PROGRESS]`（`Tests run: 939, Failures: 1, Skipped: 1`，其余全绿）⇒ `gh run rerun 39557930065 --failed` → attempt2 **5/5**（后端 2m34s / 前端 1m51s / check-docs 9s / 脱敏报告制 / **E2E 29 用例 2m5s**） |
| **39558013421**（`0507a37`） | 看板笔 | **`success`（首跑 5/5 绿，未 rerun）** | 后端 2m32s / 前端 1m41s / check-docs 8s / E2E 1m55s —— **同一份后端代码在另一 run 首跑即绿**，是"抖动而非回归"的直接反证 |
| 证据笔（本件所在提交） | 证据笔 | **待回填**（run 号不可自引用；见后续 `提交报告 CI 回填` 笔） | 与模型配置批① 同处置 |

**L-19 抖动的判据（四条，照 `docs/任务看板.md:423` 的既有口径，不扩权改用例）**：
1. **零 diff**：`ResumeServiceLeaseTest` 在本批 44 文件里**一行未改**（`git log -1 --stat` 可核）；
2. **同码异果**：同一份代码，run `39558013421` 首跑绿、run `39557930065` attempt2 绿、仅 attempt1 红；
3. **机理已在看板登记**：屏障只架在 `acquireRunLease` 之前 ⇒ 胜方若在败方进 `resume` **之前**已把快照推进到 RUNNING，败方看到的就是 `ALREADY_RUNNING`（同样是"恰一个驱动"，产品面修复不受影响，是**断言把 `IN_PROGRESS` 钉死**过严）；
4. **处置** = `rerun --failed` + 登记，**不改断言、不 `@Disabled`、不跳过**（断言宽窄属该用例维护方）。


## 6 纪律与净台

- **显式路径 add**：三次 `git add -- <显式路径>`，未用 `-A` / `.`；`?? data/` **零触碰**（仍在工作树、未暂存、未提交）。
- **零服务启停**：全程只有 `mvn` / `vitest` / `vue-tsc` / `vite build` / `node check-docs` / `git` / `gh`；未起后端、未起桩、未触真 key（测试全桩 + 内存/模拟 Redis）。
- **无实机核验**（如实说明）：本批**无实例面改动**（无新增 Lua、无跨键原子、无租约/栅栏语义），故未做真 Redis / 双实例实机跑；缺口在 **E2E 面**（E2E-CTX-1..4 + 三形态 usage 桩未落，归后续 E2E 批）。判据与理由见 `-复核确认-` §5。
- **后台任务**：CI watch 等外部命令均已自然结束；无残留进程。
