> 来源=仓库外过程报告补档（2026-10-11，DC-19 前历史文件，署名信息以文内为准）

# 施工报告 — 小批苞 A（`app.ai.hitl` 双键拆分，裁决③）

- 日期：2026-10-10
- 仓库：`<MAIN_REPO>`（主仓，分支 `main`；施工基线提交 = 苞 E 落盘后现态，工作树在本苞开工前干净）
- 依据：`<SCRATCH>/numeric-spec-audit/小批施工卡.md` §2 苞 A（A-1 ~ A-5）为最高施工依据；指挥官的取值裁决覆盖卡片 §0.3 的 P-1/P-2 默认值（见 §5 偏差）
- 纪律：本苞未做任何 git 写操作（无 commit / push / reset）；未触碰工作区另外六个源分支克隆目录

---

## 1. 改动文件清单（仓库相对路径 + 行级说明）

### 1.1 后端主代码

| # | 文件 | 行 | 改动 |
|---|---|---|---|
| 1 | `backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java` | `115-160` | `Hitl` 内部类重写：删单键 `timeout`，新增 `confirmTimeout`（`:139`，240s）与 `frontendToolTimeout`（`:160`，120s），两键各带依据注释（业界依据 §5/§6 + 刻意分开的理由 + 取值可达性声明） |
| | 同上 | `176-183` | `interEventTimeout` javadoc 同步：改为两键各自与 90s 的关系（保留"工具执行期不计静默"论证），【待裁决】指向 `confirm-timeout` |
| 2 | `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java` | `38-40` | 类注「超时」条目改为两键名 + 各自默认值（240s / 120s），保留"结局语义分场景"说明 |
| | 同上 | `168-182` | `confirmTimeoutSeconds()` 改读 `confirmTimeout`（`:170`）；**新增** `frontendToolTimeoutSeconds()`（`:180-182`）读 `frontendToolTimeout`，两者 `Math.max(1, …)` 收口一致 |
| | 同上 | `191` / `200` | `awaitDecision` 仍取确认门键；`awaitFrontendResult` 改取**前端工具键**（原与确认门共用） |
| | 同上 | `192-199` | `awaitFrontendResult` javadoc 整段重写（原"规格 §4 只定义了这一个挂起上限键"已不成立） |
| | 同上 | `508-511` | `dismiss` javadoc 里的旧键名改为前端工具键 |
| 3 | `backend/src/main/java/com/example/configmgr/ai/web/AiController.java` | `537-538` | `/health` 新增 `frontendToolTimeoutSeconds`（值与 `confirmTimeoutSeconds` 并列，各取各的取键方法） |
| 4 | `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java` | `769-771` | 诊断快照：旧 `hitlTimeout` 一条 → 拆为 `confirmTimeout` / `frontendToolTimeout` 两条 |
| 5 | `backend/src/main/java/com/example/configmgr/ai/run/StreamWatchdog.java` | `20-24` | 类注：不再引单一键名，改为两键并列（确认门等人 240s / 前端工具等机器 120s） |
| 6 | `backend/src/main/java/com/example/configmgr/ai/exec/AiExecutorConfig.java` | `17-19` | 类注：确认门键改名 + 默认值 240s |
| | 同上 | `31-35` | **新增登记段**（卡片 A-4-5 的"顺手登记"）：120s→240s 后池满 503 饱和窗口同比例翻倍（单次 2→4 分钟级、池级最坏累计 40→80 分钟级；若日后抬到 600s 则单次 10 分钟级），关联等待治理 R-41 |
| 7 | `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java` | `486` | `generative_form` javadoc 里的键名改为 `app.ai.hitl.frontend-tool-timeout`（默认 120s） |
| | 同上 | `133-138` | 次要项（指挥官指定 B-1.4）：`list_tasks` 的 `@Tool` description **补调用前上限告知**，并加 3 行说明注释（详见 §5 偏差 D-2） |

### 1.2 配置

| # | 文件 | 行 | 改动 |
|---|---|---|---|
| 8 | `backend/src/main/resources/application.yml` | `112-128` | `hitl` 段拆两键 + 分场景依据注释（§2 给全文）：`confirm-timeout: 240s`、`frontend-tool-timeout: 120s`；注释含两键可达性约束（P-2 的 total-budget 约束、P-1 的前端折算约束） |
| | 同上 | `135-137` | `resilience` 段硬规范①注释：`hitl.timeout(120s)` → "确认门 240s / 前端工具 120s"，【待裁决】指向新键名 |

### 1.3 后端测试

| # | 文件 | 行 | 改动 |
|---|---|---|---|
| 9 | `backend/src/test/java/com/example/configmgr/ai/gate/ConfirmGateFrontendTimeoutTextTest.java` | `28-61` | 类注更新（两个默认值、拆键后两条路径各自注入、总时长口径 `2+3+1+2+3`） |
| | 同上 | `73-84` | 新增确认门文案正则 `CONFIRM_SECONDS_IN_TEXT`（`确认等待超过 (\d+) 秒`） |
| | 同上 | `101` / `130` | 前端工具用例改注入 `frontend-tool-timeout`；确认门 1s 用例改注入 `confirm-timeout` |
| | 同上 | `146-170` | **新增参数化用例** `confirmTimeoutTextFollowsConfiguredSeconds`（注入 2/3s）：覆盖新确认门键生效路径 + 断言"前端工具措辞不得串入" + `verify(confirmRequest/confirmDecision)` |
| | 同上 | `175-187` | helper 拆为 `gateWithFrontendTimeoutSeconds` / `gateWithConfirmTimeoutSeconds`（共用一个 `Consumer<AiProperties>` 注入器）；**保留**原"旧措辞必须消失"负向断言（R-95 回归锁不削弱） |
| | 同上 | `208-213` | 新增 `confirmSecondsInText`（正则读回秒数，不与实现同源） |
| 10 | `backend/src/test/java/com/example/configmgr/ai/config/AiPropertiesT35DefaultsTest.java` | `63-89` | **新增用例** `hitlKeysAreSplitAndConfirmTimeoutStaysWithinTotalBudget`：锁两键默认值（240s / 120s）+ **上限关系不变量** `confirm-timeout < total-budget`（N-2 的机器化落点） |
| | 同上 | `23-33` | 类注补第三条锚定 |
| 11 | `backend/src/test/java/com/example/configmgr/ai/web/AiControllerHealthSseDeliveryTest.java` | `68-83` | **新增用例** `healthExposesBothSuspendTimeoutsAfterKeySplit`：断言 `/health` 两键配对正确（stub 240/120，两值不同 ⇒ 可抓"两键写反/串读"） |
| | 同上 | `85-95` | `controller()` 里的 `ConfirmGate` 由匿名 mock 改为显式 stub 两键 |

### 1.4 前端 / 脚本 / 文档

| # | 文件 | 行 | 改动 |
|---|---|---|---|
| 12 | `frontend/src/types/ai.ts` | `102-103` | `AiHealth` 增 `frontendToolTimeoutSeconds: number`（后端 `/health` 新键的类型契约） |
| 13 | `frontend/src/utils/frontend-tools.ts` | `165-167` / `219` / `265` | 三处注释引的键名改为 `app.ai.hitl.frontend-tool-timeout`（并说明"只供前端工具"；`AiProperties$Hitl#timeout` → `#frontendToolTimeout`） |
| 14 | `frontend/src/components/SpreadGrid.vue` | `90-101` | 卡片 A-4-10 / N-3 的必改面：`READY_TIMEOUT_MS` 论证由"明显小于后端 `ConfirmGate` 的挂起超时（120s）"改为如实口径（对比**前端工具**键 120s；确认门键现为 240s），并补一句"本常量与服务端键无逻辑绑定，前端工具键若下调须同批复核" |
| 15 | `scripts/verify-e2e.ps1` | `1387` | 挂起帧契约断言里的解耦说明由 `app.ai.hitl.timeout` 改为 `app.ai.hitl.frontend-tool-timeout`（断言本身 `> 0` **未改**，纪律不削弱） |
| 16 | `docs/s4/S4.2-AI-Runtime-合流规格.md` | `69-70` | 键表一行拆两行（新键名、取值、分场景与两条可达性约束各一句话） |
| 17 | `docs/M2-排期计划.md` | `291`（R-99 行末） | 卡片 A-4-15 的**只追加**完成留痕：写清实际取值（240s / 120s）、P-1/P-2 两个约束、闭环与未闭环项、`/health` 观测面 |

合计 **17 个文件**（后端主代码 7、配置 1、后端测试 3、前端 3、脚本 1、规格文档 1、排期表 1）。

---

## 2. 新增 / 修改键的最终值与注释原文

### 2.1 键与取值

| 键 | 旧 | 新 | 说明 |
|---|---|---|---|
| `app.ai.hitl.timeout` | 120s | **删除** | 旧单键同供确认门 / 前端工具 / `generative_form` 三场景 |
| `app.ai.hitl.confirm-timeout` | — | **240s** | 确认门（等人）。**过渡值**：裁决③目标 600s，但受 `total-budget` 300s 约束，240s 真实可达 |
| `app.ai.hitl.frontend-tool-timeout` | — | **120s** | 前端工具 + `generative_form`（等机器）。**暂维现状**；降到 60s 的前置 = 前端折算专项 |
| `/health` `frontendToolTimeoutSeconds` | — | **新增**（值 = 前端工具键折算秒，现态 120） | 与既有 `confirmTimeoutSeconds`（现态 240）对称，可观测 |
| 诊断快照 `hitlTimeout` | 一条 | 拆为 `confirmTimeout` / `frontendToolTimeout` | 键名与语义一对齐（实读确认无测试断言该键） |

### 2.2 `backend/src/main/resources/application.yml`（`:112-128` 原文）

```yaml
    hitl:
      # 挂起等待上限**分场景**（2026-10-10 数字规格清点裁决③；原单键 timeout: 120s 同供三场景）：
      #   confirm-timeout（等人）：确认破坏性操作时等人类决策。
      #     依据 = 业界依据 §5：该节唯一硬结论是"不设超时=永久卡死"（AWS Step Functions），
      #     取值无统一标准，对内网/小用户量的交互式确认门推荐 10 分钟（600s），超时自动拒绝。
      #     本键**本轮取 240s 为过渡值**：600s 才是目标值，但实际可达上界受本文件
      #     app.ai.resilience.total-budget（300s）约束 —— 看门狗的总预算判据先于"工具活跃期
      #     不计静默"生效，SseEmitter 寿命亦取该值。240s < 300s（留 60s 余量）故值真实可达；
      #     抬到 600s 须与"轮次预算 / emitter 寿命 / 兜底等待三语义拆分"（等待治理 R-99③）同批。
      #   frontend-tool-timeout（等机器）：前端工具与 generative_form 的回执等待。
      #     依据 = 业界依据 §6：MCP 只规定 SHOULD 设超时；实现层默认常见 60s，Vercel AI SDK
      #     toolMs 区间 5–60s。**本键本轮暂维现状 120s**：降到 60s 会把前端执行器预算折算压到
      #     5s 下限（frontend/src/utils/frontend-tools.ts 的"预算 = 服务端上限 − 回灌 POST 60s"，
      #     该文件自述 ≤65s 为病态配置，而实测最慢成功回灌 30.6s）—— 降到 60s 的前置 = 前端折算面
      #     （回灌 POST 单独给更短上限）专项（数字规格清点 P-1 / 等待治理 R-99②），本次不动。
      confirm-timeout: 240s
      frontend-tool-timeout: 120s
```

### 2.3 `AiProperties.Hitl` 两个字段的 javadoc 原文

`confirmTimeout`（`AiProperties.java:118-139`）：

> 确认门等待上限（**等人**）：AI 请求人类对破坏性操作（导出/检查/导入/发布）作出决策。
>
> 取值依据（业界依据 §5「人机确认门（HITL approval）超时」）：该节唯一有官方硬依据的结论是"不设超时 = 永久卡死"（AWS Step Functions 官方最佳实践），取值本身业界无统一标准；同节对"内网、用户量小、有明确操作人的交互式确认门"给出的推荐值是 **10 分钟**，超时后自动拒绝并释放锁（fail-closed，不自动放行）。本键当前取 **240s**（过渡值，见下）。
>
> 与前端工具键（`#frontendToolTimeout`）**刻意分开**：本键等的是**人**的决策延迟（分钟级），那个键等的是**机器**的回执延迟（秒级），同值共用会让两者互相迁就（2026-10-10 数字规格清点裁决③；登记见 `docs/M2-排期计划.md` 去向表 R-99①）。
>
> 超过本上限即收口（`status=TIMEOUT, executed=false`），结局语义为"工具未执行"（确定未发生）—— 与前端工具键的"结局未知"**不同**，文案也分开（见 `ConfirmGate#expire`）。
>
> **取值可达性（过渡声明）**：裁决③的目标值是 600s，但本轮还受 `total-budget`（默认 300s）约束 —— `StreamWatchdog` 的总预算判据先于"工具活跃期不计静默"生效，`SseEmitter` 寿命亦取该值。故本键本轮取 **240s**（< 300s，留 60s 余量，值真实可达）；抬到 600s 须与"轮次预算 / emitter 寿命 / 兜底等待三语义拆分"（等待治理 R-99③）同批。

`frontendToolTimeout`（`AiProperties.java:141-160`）：

> 前端工具（含 `generative_form`）回执等待上限（**等机器**）。
>
> 取值依据（业界依据 §6「前端工具调用超时（AI 工具调用执行预算）」）：MCP 规范只要求 SHOULD 设超时、不规定数值；实现层默认常见 **60s**，Vercel AI SDK 官方 `toolMs` 区间为 5–60s（跨系统工具取 60s）。本键本轮**暂维现状 120s**，理由见下。
>
> 超过本上限即收口（`status=TIMEOUT, executed=false`），但结局语义只说明"回执没回来"、**结局未知**（副作用可能已经发生，如文件已落盘）—— 与确认门键的"工具未执行"**不同**（见 `ConfirmGate#expire` 的分支文案与 `ConfirmGateFrontendTimeoutTextTest` 的回归锁）。
>
> **已知约束（为什么暂维 120s）**：前端执行器预算由帧内 `timeoutSeconds` 折算（`frontend/src/utils/frontend-tools.ts#deriveFrontendToolBudgetMs`，折算式 `预算 = 服务端上限 − 回灌 POST 上限(60s)`）；本键若降到 60s，该式即为 0、预算落 5s 下限 —— 该文件自己把 ≤65s 列为病态配置，而实测最慢的一次**成功**回灌发生在 30.6s，5s 会掐掉已知会成功的路径。故降到 60s 的前置 = 前端折算面（回灌 POST 单独给更短上限）专项裁决（数字规格清点 P-1 / 等待治理 R-99②），**本次不动**。

---

## 3. 实测结果

### 3.1 定向（XML 口径 = surefire XML 的 `tests` 汇总）

命令（Maven 包装脚本透传参数，**未**使用 `commit-gov/mvn-wrap.sh`）：

```
bash <SCRATCH>/redteam-batch-a/.run-fix/mvn-run.sh -B test \
  -Dtest='ConfirmGate*,AiPropertiesT35DefaultsTest,AiControllerHealthSseDeliveryTest' \
  -DfailIfNoSpecifiedTests=false
```

结果：`Tests run: 24, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS` / `MVN_EXIT=0`

| 测试类 | 用例数 | 变化 |
|---|---|---|
| `ConfirmGateFrontendTimeoutTextTest` | 5 | 原 3 → 5（新增 2 个确认门参数化用例） |
| `ConfirmGateDeadlockResumeTest` | 9 | 不变（默认确认门 240s 下仍即时收口，未出现"等满新默认值"） |
| `ConfirmGateWiringTest` | 4 | 不变 |
| `AiPropertiesT35DefaultsTest` | 3 | 原 2 → 3（新增拆键 + 上限关系） |
| `AiControllerHealthSseDeliveryTest` | 3 | 原 2 → 3（新增 /health 两键配对） |

**敏感指标**：`ConfirmGateFrontendTimeoutTextTest` 实测 11.14s（= 2+3+1+2+3 秒的真等到期 + 装配），无用例等满 240s/120s 新默认值。

### 3.2 全量

```
bash <SCRATCH>/redteam-batch-a/.run-fix/mvn-run.sh -B test
```

| 轮次 | 结果 | 备注 |
|---|---|---|
| r1 | `Tests run: 387, Failures: 1, Errors: 0, Skipped: 0` | 失败项 = `SseChatEmitterWriteBudgetTest#writeTimeoutEvictsTheSubscriberAndClosesItWhenTheStuckWriteFinallyReturns:119`（断言"收尾发生在写出线程"，实测收尾落在 `budget-delivery-1` 线程）—— 与本次改动**无关**（该测试只用到 `AiProperties.Frame.DEFAULT_MAX_BYTES`，不触 `Hitl`）；单类重跑 `Tests run: 6, Failures: 0` 全绿 ⇒ 判为**既有时序脆弱面**（全量并发负载下的线程交错），见 §5 偏差 D-4 |
| r2 | 见下（冻结态复跑） | |

| r2（冻结态复跑，**定稿口径**） | `Tests run: 387, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS` / `MVN_EXIT=0` | 全绿 —— 反证 r1 的 1 处失败为偶发时序（同一工作树、同一命令） |

**XML 口径（定稿）**：`backend/target/surefire-reports/` 下 **59 个 `TEST-*.xml`**，`tests=387 / failures=0 / errors=0 / skipped=0`。
基线 = **383/0/0/0（59 个 XML）** → 本苞 **387/0/0/0**（**+4**：确认门参数化 2 + AiProperties 拆键 1 + `/health` 配对 1；未新增测试类，故 XML 文件数仍 59），**只增不减**。

### 3.3 前端

```
cd frontend && yarn typecheck   # vue-tsc --noEmit
cd frontend && yarn build
```

- typecheck：绿（首跑 `Done in 12.85s`；冻结态复跑 `Done in 5.15s`），0 error
- build：绿（首跑 `✓ built in 1m 17s`；冻结态复跑 `✓ built in 45.92s`），成功产出 `dist/`（`dist/` 已被忽略，工作树无新增未跟踪文件）

（改动只触及 `types/ai.ts` 一行新增字段与三处注释/注释文案，两命令在冻结态复跑均绿。）

### 3.4 文档门禁

```
node scripts/ci/check-docs.mjs      # 卡片路径为准；提示词写的 scripts/check-docs.mjs 在本仓不存在
```

结果：**6/6 全绿**（`全部通过：6/6 断言绿。`，退出码 0）。其中断言⑤命名纪律实扫 916 个文本（919 个路径、豁免 3），规则自检 5/5 + 24/24 + 负对照 11 全过；断言①导航文档无盘符路径亦绿。冻结态复跑同样 6/6（0.56s）。

### 3.5 端口纪律

本苞**未起任何服务**（无 E2E / 无 jar 启动），故未占用任何端口，未触及 18330 起的端口纪律；`netstat` 未使用。

---

## 4. 与施工卡的对照（逐条）

| 卡片条目 | 状态 |
|---|---|
| A-1 拆键（`AiProperties.Hitl`） | ✅（取值按指挥官裁决，见 §5 D-1） |
| A-2 两条等待路径分别取键 | ✅（`confirmTimeoutSeconds` / 新增 `frontendToolTimeoutSeconds`；类注、`awaitFrontendResult` javadoc、`dismiss` javadoc 同步；`expire` 按卡片"不改"保持原样） |
| A-3 回归测试适配（含 A-3.5 建议项） | ✅（helper 拆分 + 两类用例各自注入 + 新增确认门参数化；旧措辞负向断言保留） |
| A-4-1 ~ A-4-13 | ✅ 全部落盘（A-4-11 按卡片"只登记"未改 `workspace.ts`） |
| A-4-14（`docs/03` 冻结正文引旧键名） | ⛔ **未做**（卡片标【待指挥官裁决】，提示词未授权；见 §6 遗留） |
| A-4-15（R-99 行末留痕，只追加） | ✅ |
| A-5 验收 | ✅（定向 + 全量 + 前端 + 门禁全跑，见 §3） |
| §8 边界（不改 `api/jobs.ts`、`AsyncConfig` 数值、Hikari、Excel 面、spike 等） | ✅ 未触碰；`spike/` 的 `confirm-timeout-seconds` / `frontend-tool-timeout-seconds` 未对齐（卡片明示非产品路径，登记备查） |

---

## 5. 偏差与处置

**D-1（取值 vs 卡片默认值——按指挥官裁决执行，非冲突）**
卡片 §0.3 的默认草案是 **A0 + B0**（`confirm-timeout = 600s`、`frontend-tool-timeout = 60s`），但卡片把这两个取值明确标为【待指挥官裁决】，并写明"若指挥官选 A1/A2/B1/B2，只改 §2 苞 A 的取值与注释段落，其余步骤不变"。指挥官裁决 = **B1 + A0**：`confirm-timeout = 240s`（< 300s，留 60s 余量，真实可达）、`frontend-tool-timeout = 120s`（暂维现状）。故本苞**按裁决取值执行**，卡片其余结构步骤一字不改 —— 卡片与提示词**不构成冲突**，是"卡片留空 + 指挥官填值"的关系。连带调整的注释段落：
- `confirm-timeout` 注释由"本键取 600s"改为"本轮取 240s 为过渡值，600s 为后续 total-budget 三语义拆分批次的目标"；
- `frontend-tool-timeout` 注释写明"暂维 120s，降到 60s 的前置 = 前端折算专项，本次不动"；
- `AiProperties.interEventTimeout` 注释：卡片写"说明前端工具 60s < 90s 仍正确"，实际取值为 120s ⇒ 改为"两个挂起上限（240s / 120s）均大于 90s"，论证方向不变（工具执行期不计静默）；
- `SpreadGrid.vue`（卡片 A-4-10 / N-3）原假设"拆键后前端工具键 60s 与 `READY_TIMEOUT_MS` 60s 同值、'明显小于'失效"；实际取 120s ⇒ "60s 明显小于 120s"**仍然成立**，但原句指向的是 `ConfirmGate`（现为确认门 240s）**指向错误**，故按**如实口径**改写为对比前端工具键，并补"无逻辑绑定、下调时须同批复核"的登记。仍**未留任何假话**。

**D-2（`@Tool` description 的上限数字：10 而非卡片写的 20）**
提示词要求把卡片指定的 `@Tool` 注解描述补上限说明（即卡片 B-1.4）。卡片 B-1.4 的原文是"最多返回前 20 条，超出时给出总条数"，但那与卡片 B-1 的代码改动（`limit(10)` → `limit(LIST_TASKS_MAX_ROWS=20)` + 追加"共 N 条仅显示前 M 条"告知）是**同一步的成套改动**；本苞只做 description（B 苞未落），若照抄 20 会**给模型一句假话**（现实现 `limit(10)` 且无总条数告知），与本批"消除谎报"的主题相反。
处置：description 按**现态真实值**表述 —— `"列出最近的任务列表（含类型/步骤/状态），最多返回前 10 条（超出部分不列出，也不回报总条数）"`，并在方法上方注释中写明"取值必须与 `limit(...)` 一致；待 B 苞把上限改成 20 并加告知后，本描述须同步改为 20 与超出告知口径"。
→ **标【待裁决】**：若指挥官要求此处就取 20 的口径，须把 B 苞的 `limit` 与告知一并并入本苞（跨苞夹带），否则即为谎报。

**D-3（`expire` 未改）**
按卡片 A-2.4 明确"`expire` 无需改动"（文案秒数由实参传入），保留原样 —— 这正是批 A 回归锁能继续生效的原因。实测 `ConfirmGateFrontendTimeoutTextTest` 两条分支的负向断言（旧措辞消失 / 不串分支）全绿。

**D-4（全量 r1 出现 1 处与本次改动无关的失败）**
`SseChatEmitterWriteBudgetTest#writeTimeoutEvictsTheSubscriberAndClosesItWhenTheStuckWriteFinallyReturns`（SSE 写预算摘除路径的线程归属断言）在全量负载下偶发红：期望收尾线程名以 `budget-writer` 开头，实测落在 `budget-delivery-1`。论据：① 该测试只依赖 `AiProperties.Frame.DEFAULT_MAX_BYTES`，与本苞改动的 `Hitl` 键/文案/`/health` 无关；② 单类重跑 `Tests run: 6, Failures: 0`；③ 失败用例耗时 0.334s（单跑 1.888s，交错不同）；④ **全量复跑 r2 全绿（387/0/0/0）**。判为**既有时序脆弱面**，不作为本苞门禁。
→ **标【待裁决】**：是否单独立项（"SSE 写预算收尾线程归属断言的时序加固"）由指挥官决定；本苞不动该测试（越界）。

**D-5（提示词的一处路径笔误）**
提示词写门禁路径 `node scripts/check-docs.mjs`，本仓实际路径为 `scripts/ci/check-docs.mjs`（与施工卡一致）。已按仓库实际路径执行，并顺带说明。

**D-6（命名纪律自查）**
本苞新增/改动的全部文本不含"main + 分隔符 + v2"的任意大小写/零分隔连写形态；报告与证据文件全部使用 `<MAIN_REPO>` / `<SCRATCH>` 等占位符，无盘符路径、用户名、内网 IP。门禁断言⑤（含连写第三态）对本苞全部改动文本实扫通过。

---

## 6. 遗留问题（未做 / 待后续）

| # | 项 | 状态 |
|---|---|---|
| 1 | `confirm-timeout` 抬到 **600s** | **未做**（本苞过渡取 240s）。前置 = `total-budget` 三语义拆分（等待治理 R-99③）；本苞已落"`confirm-timeout < total-budget`"的机器化断言，届时该断言会先红，防静默越界 |
| 2 | `frontend-tool-timeout` 降到 **60s** | **未做**（暂维 120s）。前置 = 前端折算面专项（回灌 POST 单独给更短上限，P-1 / R-99②），届时 `SpreadGrid.vue` 的"明显小于"论证与 `frontend-tools.ts` 的病态区注释须同批复核 |
| 3 | `docs/03-技术方案文档-v2.1.md:270/:551/:642` 引旧键名 `app.ai.hitl-timeout` | **未改**（卡片 A-4-14 标【待指挥官裁决】，建议只加行尾注记；该文档为冻结文档） |
| 4 | `docs/M2-排期计划.md:233`（R-41 行）仍写"`hitl.timeout` 至多 120s" | **未改**（不在卡片 A-4 清单内）。本苞已在 R-99 留痕中标注与 R-41 的关联；建议 R-41 施工时同步该行口径 |
| 5 | `docs/evidence/S44a-前端基座验证.md:109` 的 `/health` 字段清单未含新键 | **未改**（历史证据文档按纪律不回改）。登记备查 |
| 6 | `docs/M2-排期计划.md` 的 R-41 / 观察项表：N-1 / N-2（S1 级）单独成行登记 | **未做**（卡片 §5.2 写"建议同批追加"，非 A-4 清单项）。本苞把两者的实质与约束写进了 R-99 行末留痕；是否另立观察行待指挥官定 |
| 7 | `AiExecutorConfig` 提出的 503 饱和窗口翻倍（R-41 关联面） | 已**登记**（代码注释），未做容量调整（超出授权） |
| 8 | `SseChatEmitterWriteBudgetTest` 时序脆弱面（§5 D-4） | 登记，未修（越界） |
| 9 | `spike/` 的 `confirm-timeout-seconds` / `frontend-tool-timeout-seconds` 与产品键命名未对齐 | 登记备查（卡片明示 spike 非产品路径，本小批不动） |
| 10 | 本报告未包含的验证面：未跑 E2E（`verify-e2e.ps1`）与冒烟（`m2t2a-smoke.mjs`） | 卡片把 E2E 归苞 F；本苞只改了 `verify-e2e.ps1` 的一句断言文案（`> 0` 判据未变），无行为面 |

---

## 7. 证据落点

- 定向测试日志：终端输出（`Tests run: 24, Failures: 0`）
- 全量 r1 日志：`<SCRATCH>/numeric-spec-audit/mvn-baoA-full.log`
- 全量 r2 日志：`<SCRATCH>/numeric-spec-audit/mvn-baoA-full-r2.log`
- surefire XML（定稿口径）：`<MAIN_REPO>/backend/target/surefire-reports/`（59 个 `TEST-*.xml`，`tests=387 / failures=0 / errors=0 / skipped=0`）
- 前端：`cd frontend && yarn typecheck`（冻结态 5.15s 绿）/ `yarn build`（冻结态 45.92s 绿）
- 门禁：`node scripts/ci/check-docs.mjs` → 6/6 绿
- 工作树：`git status --porcelain` = 17 个 `M`、**0 个未跟踪新增**（`dist/` 已被忽略）
