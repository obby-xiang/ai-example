# 提交报告 · AI 对话日志链路追踪批 合流

> DC-19 首页：第一屏即可判断「合了什么、sha 是什么、门禁什么数、冲突怎么处置、删除行清单是否吻合、样例日志长什么样」。

## 0. 身份与结论（DC-19）

- 日期：2026-10-21
- 提交官自证（`profile.bind` 单行，取自本 agent 自己的 wire）：
  `{"type":"profile.bind","agentId":"agent-1413","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high"}`
  → **modelAlias `DeepSeek/deepseek-flash` / profileName `coder` / thinkingEffort `high`**；
  身份核对 = 同 session 目录内 `ls -lat` 取最新建 = `agent-1413`，其 `turn.prompt` 首句为
  「你是提交官，执行「AI 对话日志链路追踪批」合流 main 全流程」，与本棒任务逐字一致；
  前一棒 `agent-1411` = 超时面治理批① 提交官，`agent-1408` / `agent-1407` 为其上游笔；本批**施工官 = `agent-1407`**（施工报告 §0 自证）。
- 授权：用户 2026-10-21「AI 可观测就做日志打印就行，直接施工」。
- **结论 = 已合流、门禁全绿、已推送。**
  - 分支单笔：**`d170416`**（`feature/ai-logtrace`，基点 `0dad9ba`；22 路径 = 11 改 + 11 新，
    `git diff --shortstat` = 22 files / +2297 −11，与施工报告 §6 文件清单**逐条相符、无多出、无缺件**）
  - **合并笔：`d20d75d`**（`main`，`git merge --no-ff`，父 = `0850a90` + `d170416`）
  - 证据笔：`main` HEAD（本文件所在笔，`docs(evidence+board): 日志链路追踪批合流证据与看板销记`）
- 开工基线：`main` = `origin/main` = **`0850a90`**（`git fetch` 后两值相同，无需 pull；该笔 = 超时面治理批① 合流入库，后端基线 **1279** / 前端 **819**）。
- 边界：**未改任何产品代码**；**未起停任何服务**；**未碰其它工作树**（仅本批 `wt-ai-logtrace`，合流后已清理）。

## 1. 门禁（合流后主仓复跑）

| 门禁 | main 基线（`0850a90`） | 合流后实测 | 判定 |
|---|---|---|---|
| 后端 `mvn -o test` | 1279 / 190 文件 | **1307 / 197 文件 · 0F / 0E / 0S** | ✅（= 1279 + 28 例 / 190 + 7 类；`BUILD SUCCESS`） |
| 后端两口径核对 | — | Maven 汇总行 **1307** = `TEST-*.xml` 求和 **1307**（197 文件，F/E/S 全 0） | ✅ 逐值一致 |
| 前端 `vitest run` | 819 / 56 文件 | **819 / 56 文件**（`exit 0`） | ✅ 持平（本批零前端改动） |
| 前端 `vue-tsc --noEmit` | 0 | **0**（exit 0） | ✅ |
| 前端 `vite build`（`TMP`/`TEMP` 指向仓外 scratch） | ✓ | **✓ built in 55.00s**（exit 0） | ✅ |
| `scripts/ci/check-docs.mjs` | 6/6 | **6/6**（1638 路径实扫 1635 / 豁免 3；规则自检全绿） | ✅ |
| 脱敏（本批引入面） | — | **0 命中** | ✅ |
| 帧契约 16 | 16 | **16**（本批零新增/零改帧型，`SseChatEmitter` 仅投递线程加 MDC 包装） | ✅ |

### 1.1 后端增量对账

`1307 = 1279（main 基线）+ 28（本批新增 7 测试类）`：`AiTraceTest` 8 / `AiTurnLogTraceTest` 8 / `AiApprovalLogTraceTest` 3 / `AiToolCallLogTraceTest` 2 / `AiCompactionLogTraceTest` 2 / `AiMdcPropagationTest` 3 / `AiLogPatternConfigTest` 2 = **28**。既有用例零删除、零跳过（`Skipped: 0`）。

### 1.2 前端首跑的 1 例超时（如实登记）

- 首跑（与后端 Maven 全量**并发**执行）：`818 passed + 1 failed`，唯一失败 = `tests/restore-request-emitted.spec.ts > 一批三卡…`，失败形态 = **`Test timed out in 15000ms`**（L0 批既有用例，与本批零关联）。
- 处置：等 Maven 结束后隔离复跑该 spec **8/8 连过两次** → 全量复跑 **819/819 · 56 文件 · exit 0**。
- 判定 = **并发争用下的既有超时 flake**，非本批引入；**未改任何测试或代码**。若后续复现，按「看板既有 flake」体例挂账（参照 L-19 先例）。

### 1.3 脱敏（四面独立正则）

| 面 | 范围 | 结果 |
|---|---|---|
| ① 合流 diff 加行 | `0850a90..d20d75d` 全部 `+` 行 | **0 命中** |
| ② 新增件全文 | 11 件新增文件全量正文 | **0 命中** |
| ③ 变更件全文 | 22 件改动文件的 HEAD 版全文 | **0 命中** |
| ④ 全域基线对照 | `git ls-files` 全量（1638 路径） | **40 命中 = 既有基线**（全部落在 10-09 / 10-11 的旧证据件，规则为 USER；与本批 22 变更件**文件集合零交集**），本批**增量 0** |
| ⑤ 暂存面 | 本笔 `git diff --cached`（pre-commit 钩子阻断式） | **通过**（两次 commit 均打印「敏感信息扫描通过」） |

## 2. 合流处置（两处冲突，均"保留双方"）

`git merge --no-ff feature/ai-logtrace` 报 **2 处内容冲突**，其余 9 个改动文件与 11 个新增件自动合（含 `application.yml`：批① 改 `app.ai.resilience` 段、本批在 `logging:` 段，位置不重叠 ⇒ 自动合；`SseChatEmitter` 亦自动合）。

| # | 文件 | 双方各自新增 | 处置 | 判定 |
|---|---|---|---|---|
| C1 | `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java` | 批① 新增 `awaitRetryBackoff(...)`（指数退避 + jitter，切片轮询取消）；本批新增 `emitFirstByte(...)`（首包耗时事件，驱动线程打点） | 两者**插在同一位置**（`awaitSettled` 之前）被 git 判为同一 hunk ⇒ **两方法并列全留**：批① 方法在前、本批方法在后；共用块首 `/**` 归批① 方法，本批方法补自己的 `/**` 与收尾 `}` | ✅ 琐碎冲突，语义正交 |
| C2 | `backend/src/main/java/com/example/configmgr/ai/context/ContextSummaryClient.java` | 批① 把提交包进 `try/catch (RejectedExecutionException)`（池饱和 ⇒ `AI_SUMMARY_UNAVAILABLE`）；本批把 lambda 包成 `AiTrace.wrapCallable(...)` | **合并为 try 内包裹**：`future = executor.submit(AiTrace.wrapCallable(...))` 置于 `try` 内，`catch` 原文保留 ⇒ 池饱和语义不变 **且** 摘要线程带 `runId` 锚点 | ✅ 双方语义均保留 |

- **语义冲突：0**。合并结果对 `main` 的净 diff = **22 文件 / +2297 −11**，与分支单笔 `d170416` 的 `--shortstat` **逐值相同** ⇒ 批① 的改动一行未丢。

## 3. 删除行清单核对（施工报告 §5）

施工报告声明「全量 11 行，均为必要替换」。提交官以 `git diff -U0 | grep '^-[^-]'` 实测：**删除行 = 11 行，与声明逐条对应**。

```
ContextSummaryClient.java    executor.submit(lambda)           → wrapCallable(lambda)          ✔（合流后又在 try 内）
ConfirmGate.java             public submitDecision(...)         → 拆成 public 包装 + private doSubmitDecision   ✔
ResilientChatService.java    fail(..., int partialChars, ...)   → fail(..., Accumulator, ...)    ✔
ResilientChatService.java    emptyReply(...)                    → 多一个 TurnContext 参数         ✔
SseChatEmitter.java          deliveryExecutor.execute(lambda)   → AiTrace.wrap(lambda)          ✔
AiController.java            submission = confirmGate.submit…   → 包 try/finally 装锚点（两处 = 2 行）  ✔
```

核对结论：**无计划外删除**（`+2297 / −11`，11 行删除全部落在上述 6 处签名/构造点）。

## 4. 样例 `turn_end` 行（合流后全量门禁实收）

数据来自 `mvn -o test` 的真实输出（`InMemoryRunStore` + `ChatClient` 桩；生产形态在级别后另有两列 `%X{runId:-}` / `%X{sessionId:-}` 锚点）：

```
event=turn_end runId=run-chat-turn sessionId=s-chat-turn status=done code=none attempts=1 totalMs=17 activeMs=17 gateWaitMs=0 toolCalls=0 chars=5 inputTokens=2 outputTokens=0 totalTokens=2 est=true model=test-model upstreamEvents=3
```

两个非成功出口（同样每轮必有收尾行）：

```
event=turn_end runId=run-chat-turn sessionId=s-chat-turn status=cancelled code=AI_RUN_CANCELLED attempts=1 totalMs=38 activeMs=38 gateWaitMs=0 toolCalls=0 chars=2 inputTokens=2 outputTokens=0 totalTokens=2 est=true model=test-model upstreamEvents=1
event=turn_end runId=run-chat-turn sessionId=s-chat-turn status=blocked code=SENSITIVE_OUTPUT_BLOCKED attempts=1 totalMs=14 activeMs=14 gateWaitMs=0 toolCalls=0 chars=28 inputTokens=2 outputTokens=0 totalTokens=2 est=true model=test-model upstreamEvents=0
```

**日志形制旁证**：合流后门禁日志的行首已呈现 `... INFO   <pid> --- [config-mgr]` 的**三空格**形态（`%5p` 后接两个空锚点列再落到默认 ` --- `），即 `logging.pattern.level` 已生效、未绑锚点的既有行仅多出两个空列、文本逐字不变。

## 5. 文件清单（22 = 11 改 + 11 新）

### 5.1 修改（11）

`backend/src/main/resources/application.yml`（+4 行 `logging.pattern.level`）·
`.../ai/exec/AiExecutorConfig.java`（+5）·
`.../ai/web/AiController.java`（+38/−3）·
`.../ai/run/ResilientChatService.java`（+101/−4）·
`.../ai/gate/ConfirmGate.java`（+67/−1）·
`.../ai/gate/SpToolCallingManager.java`（+14）·
`.../ai/context/ContextCompactor.java`（+5）·
`.../ai/context/ContextSummaryClient.java`（+3/−1）·
`.../ai/run/SseChatEmitter.java`（+8/−1）·
`.../ai/run/ResumeService.java`（+6）·
`.../ai/run/StartupResumeRunner.java`（+6）

### 5.2 新增 · 主代码（2）

`.../ai/observe/AiTrace.java`（199 行）· `.../ai/observe/AiMdcTaskDecorator.java`（22 行）

### 5.3 新增 · 测试（7 类 28 条）

`AiTraceTest`(8) · `AiTurnLogTraceTest`(8) · `AiMdcPropagationTest`(3) · `AiLogPatternConfigTest`(2) · `AiApprovalLogTraceTest`(3) · `AiToolCallLogTraceTest`(2) · `AiCompactionLogTraceTest`(2)

### 5.4 新增 · 文档（2）

`docs/AI对话日志链路追踪手册.md`（300 行）· `docs/evidence/施工报告-日志链路追踪批-DS-Flash.md`（280 行）

## 6. 提交官自决（5 条）

| # | 议题 | 裁决 | 理由 |
|---|---|---|---|
| S1 | `ResilientChatService` 的「方法 vs 方法」冲突怎么排 | **两方法并列全留**，批① 退避在前、本批首包事件在后，各自补全 javadoc 与收尾 `}` | 两方法语义正交（重试等待 vs 首包打点），删任何一个都是功能倒退；排序只影响阅读，不影响调用（均为私有方法、各自唯一调用点） |
| S2 | `ContextSummaryClient` 取谁 | **不取单侧**，把本批 `wrapCallable` 放进批① 的 `try` 内 | 批① 的池饱和快速失败与 本批的锚点传递是两个正交不变式；取任一侧都会静默丢一条既有保障 |
| S3 | 前端首跑 1 例超时算不算门禁红 | **不算本批红**：判并发争用 flake，隔离复跑 8/8×2 + 全量复跑 819 全绿后才记绿 | 与后端 Maven 全量并发时 CPU 争用下超时；该 spec 属 L0 批既有用例、与后端改动零耦合；不据此改测试或代码（不扩权） |
| S4 | 看板体例：可观测性是「关行」还是「行内单列」 | **整行转 ✅（📋 → ✅）**，缓议的 metrics/OTel/eval 用**新增挂账 L-55** 承接 | 用户在 2026-10-21 已对**路线**定稿（零组件日志面），专项不再"评审中"，故不能留 📋；但缓议项必须可被检索，故按本板惯例（模型配置/上下文行「行内单列 + 挂账承接」）落 L-55 |
| S5 | 脱敏怎么报才不失真 | **「本批引入面」与「全域基线」分开报**：引入面 0 命中；全域 40 命中全部为既有证据件、与本批 22 变更件零交集 | 只报「全域 0 命中」会失真（全域本就有 40 条既有命中），只报「全域 40」会误导为本批引入；两口径并列 + 文件集合交集为空 = 可复核 |

## 7. 看板 / 台账落点

- `docs/任务看板.md`
  - `:15` **AI 可观测性专项** 行：状态 📋 → **✅**；进展改为「日志链路追踪批 ✅ 合流（方案收缩 = 零组件日志面，用户 2026-10-21 裁）」+ 门禁实测 + 证据件；**原四批切分**改写为现状映射（观① run 摘要层 → 以 `turn_end` 落地为准；观② MDC → 以本批落地为准；观③ metrics / 观④ OTel / 评估面 → 缓议转挂账）。
  - 挂账小节新增 **L-55**「metrics / OTel / 评估面缓议（用户裁：先不做那么好）」；`:473` 速览同步：批次 **69** 条不变、✅ **53 → 54** / 📋 **11 → 10**；挂账 **115 → 116**；并追加「10-21 补记第十八批」本条合流入库留痕。
- `docs/决策看板.md`：10-21 追加 **「可观测性路线裁定为零组件日志面（2026-10-21）」✅** 一条（MDC + 结构化事件 + 手册；日志只记元数据、禁对话内容，与 🏛️「对话数据不落库」一致；metrics/OTel/eval 缓议）；速览同步 124 → **125** 条（批次/流程级 102 → 103、已定稿 88 → 89）+ 本笔留痕。
- 台账 `_shared/services.state`：追加一行合流记录（详见 §9）。

## 8. 未做 / 风险

| # | 项 | 说明 |
|---|---|---|
| G1 | **未做运行时实证**（未起服务、未连真上游） | 承接施工报告 G1：事件由单测在真实生产类路径上产出，但「真上游 + 真 Redis」下的行数与耗时分布未抽样；建议后续按手册 §grep 菜谱在真服环境抽一眼 |
| G2 | 前端首跑 flake | 见 §1.2。已隔离连过两次并全量复绿；若同类再现在其它棒复现，按看板既有 flake 体例挂账（参照 L-19） |
| G3 | 事件行字段顺序为契约 | 手册 §2 已登记；后续新增字段**追加在末尾**（grep/awk 菜谱位置无关，但人工比对依赖顺序稳定） |
| G4 | 日志 pattern 的进程内渲染 | 已用 `PatternLayout` 离屏实证 + 合流后门禁日志行首形态旁证（§4）；真实进程内两列取值仍建议真服抽样确认 |
| G5 | metrics / OTel / 评估面 | **本批明确不做**（用户裁），已挂 L-55，续做须用户复启令 |

## 9. 复跑命令

```bash
cd backend && <MAVEN_HOME>/bin/mvn -o test          # 期望 Tests run: 1307, Failures: 0, Errors: 0, Skipped: 0
cd frontend && node node_modules/vitest/vitest.mjs run   # 期望 819 passed (56 files)
cd frontend && node node_modules/vue-tsc/bin/vue-tsc.js --noEmit        # 期望 0
cd frontend && TMP=<SCRATCH> TEMP=<SCRATCH> node node_modules/vite/bin/vite.js build   # 期望 ✓ built
node scripts/ci/check-docs.mjs                       # 期望 6/6 PASS
git log --oneline -3                                 # d20d75d merge / d170416 feat / 0850a90 base
```
