> 来源=仓库外过程报告补档（2026-10-11，DC-19 前历史文件，署名信息以文内为准）

# 施工报告 — 小批苞 B（`list_tasks` 截断 10→20 + 截断告知 + 回归锁，裁决②）+ 三项文档注记

- 日期：2026-10-10
- 仓库：`<MAIN_REPO>`（主仓，分支 `main`）；施工基线 = 苞 A 落盘后的**未提交工作树**（苞 A 的 17 个文件改动全部保留，本苞**未还原、未覆盖**任何既有未提交改动）
- 依据：`<SCRATCH>/numeric-spec-audit/小批施工卡.md` §2「苞 B」（B-1 / B-2）为最高施工依据；另依据指挥官本苞任务书的三个文档注记小项
- 纪律：本苞未做任何 git 写操作（无 `commit` / `push` / `reset`）；未触碰工作区另外六个源分支克隆目录；报告与文档内容全部脱敏（无盘符路径 / 用户名 / 内网 IP / 禁用命名组合，仓库名一律用 `<MAIN_REPO>`）

---

## 1. 改动文件清单（仓库相对路径 + 行级说明）

### 1.1 后端主代码

| # | 文件 | 行（改后实读） | 改动 |
|---|---|---|---|
| 1 | `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java` | `133-144` | **新增**常量 `static final int LIST_TASKS_MAX_ROWS = 20;`（`:144`）+ 卡片 B-1 指定的依据注释（javadoc：业界依据 §8 取值依据 / 必须 `< app.ai.tool-result.max-rows`(200) 的理由 / "为什么必须带告知"） |
| | 同上 | `146-148` | 苞 A 留下的**过渡注释**（"现为 10，待 B 苞把上限改成 20 …本描述须同步改为 20 与超出告知口径"）改写为现态三处同改口径（常量 / `limit(...)` / 截断告知同改） |
| | 同上 | `149` | `@Tool(name = "list_tasks")` 的 **description 改为 20 口径**（见 §2 前后对照） |
| | 同上 | `152-161` | 方法体：`:155` `limit(10)` → `limit(LIST_TASKS_MAX_ROWS)`；`:159-160` **新增截断告知分支**（`tasks.size() <= LIST_TASKS_MAX_ROWS` 时原样返回，超出时追加 `"\n（共 N 条，仅显示前 20 条）"`，文案按卡片 B-1.3 逐字） |

### 1.2 后端测试

| # | 文件 | 行 | 改动 |
|---|---|---|---|
| 2 | `backend/src/test/java/com/example/configmgr/ai/tools/AiToolsListTasksTruncationTest.java` | 全 80 行 | **新建测试类**（卡片 B-2 指定路径与形态），纯 JUnit 5 + Mockito，不经 Spring 容器；用例矩阵见 §3 |

### 1.3 文档（三项注记小项，全部落在行尾注记 / 追加）

| # | 文件 | 行（改后实读） | 改动 |
|---|---|---|---|
| 3 | `docs/03-技术方案文档-v2.1.md`（冻结文档） | `:270`（§5.2 第 7 条）、`:551`（配置键表行）、`:642`（风险表取舍条目行） | **仅加行尾注记**（注记文本见 §4），被引正文逐字节零删 |
| 4 | `docs/M2-排期计划.md` | `:233`（`R-41` 行） | **仅加行尾注记**（R-41 的"至多 120s（`hitl.timeout`）"拆键说明 + 端口口径改锚） |
| | 同上 | `:351-354`（新增小节，位于 `## 等待治理排查登记注记（2026-10-10）` 节末、文件 EOF 前） | **追加**「小批苞 A 观察项登记：`SseChatEmitterWriteBudgetTest` 时序脆弱面（2026-10-10，只追加）」小节 |

**未改动**：前端零改动（故 §5.3 的 `yarn typecheck` / `yarn build` 不适用）；`application.yml` / `AiProperties` / `ConfirmGate` / 脚本 / `scripts/ci/*` 均未触碰。

---

## 2. `@Tool` 描述前后对照（裁决要点 3 的显式交付）

| | 内容 |
|---|---|
| **苞 A 落盘的"现态真值"（改前）** | `列出最近的任务列表（含类型/步骤/状态），最多返回前 10 条（超出部分不列出，也不回报总条数）` |
| **本苞（改后）** | `列出最近的任务列表（含类型/步骤/状态），最多返回前 20 条，超出时给出总条数` |
| 取值来源 | 卡片 B-1.4 指定文案（逐字采用；该条在卡片 §6 第 6 项标记为【待指挥官裁决】，指挥官本苞任务书明确授权执行） |
| 与实现的一致性 | 描述里的「前 20 条」= 常量 `LIST_TASKS_MAX_ROWS` = `limit(LIST_TASKS_MAX_ROWS)`；「超出时给出总条数」= `:159-160` 的截断告知分支。三处同源，注释 `:146-148` 已写明"改值即改描述" |
| 影响面核对（实读） | `grep -rn "列出最近的任务" backend/src/test scripts` 0 命中；`scripts/ci/stub-routes.json` 只登记工具名 `list_tasks`；`ToolRegistryDisclosureTest` / `ToolFrameSequenceConformanceTest` / `ConfirmGateWiringTest` 只断言工具**名**不断言描述 ⇒ 改描述**无测试面破坏**（实跑亦证：见 §5） |

---

## 3. 新测试用例矩阵（`AiToolsListTasksTruncationTest`，2 个用例）

形态：`new AiTools(mock(DefinitionService.class), taskService, mock(ConfigDataService.class), mock(JobService.class))`，只桩 `taskService.findAll()` 返回 N 个 `Task`（`id/type/title/currentStep/status` 全填充，形态同 `ConfirmGateDeadlockResumeTest` 的纯单测风格）。

| # | 用例 | 输入 | 断言 |
|---|---|---|---|
| 1 | `truncatesToMaxRowsAndAppendsTotalCountNotice` | `findAll()` = **21** 条 | ① `AiTools.LIST_TASKS_MAX_ROWS == 20`（常量与裁决值互为锁）；② 文本行数 = **21**（20 条任务行 + 1 行告知）；③ 末行**全等** `（共 21 条，仅显示前 20 条）`；④ 含子串 `共 21 条` 与 `仅显示前 20 条`；⑤ `- #` 开头的任务行恰 **20** 条；⑥ 文本**不含** `任务 21`（第 21 条只允许作为告知里的数字出现） |
| 2 | `doesNotAppendNoticeWhenRowCountEqualsMaxRows` | `findAll()` = **恰 20** 条 | ① 行数 = 20（无告知行）；② **不含** `仅显示前`、**不含** `（共 `；③ 含 `任务 20`（防"总是追加"的**假告知**，卡片断言 3） |

**判据覆盖映射**：卡片的 3 条断言（行数=20+告知行 / 含 `共 21 条` 与 `仅显示前 20 条` / 恰 20 条不出现告知）收敛进上述 2 个用例，使实测恰为卡片绿判据 `Tests run: 2, Failures: 0`（实跑结果见 §5.1）。

---

## 4. 三项文档注记落点

### 4.1 `docs/03-技术方案文档-v2.1.md`（冻结文档，`grep -n "hitl"` 定位全部命中行 = 3 行）

改前先 `grep -n "hitl" docs/03-技术方案文档-v2.1.md` → 命中 `:270` / `:551` / `:642`（全部为旧键 `app.ai.hitl-timeout`，无其它命中行）。三处**只加行尾注记，正文逐字节零删**；注记文本统一：

> `〔行尾注记（2026-10-10 数字规格小批苞 A，只追加）：该键已于苞 A 拆分为 `app.ai.hitl.confirm-timeout`（240s）/ `app.ai.hitl.frontend-tool-timeout`（120s）；本行为冻结原文，不回改。〕`

| 行 | 位置 | 注记落点（形态） |
|---|---|---|
| `:270` | §5.2 第 7 条（确认门位置与上限约束段末） | 段末行尾（`取舍记入风险表（§13.1）。` 之后） |
| `:551` | 配置键表行 `| app.ai.hitl-timeout | 确认门等待上限 | … | FR-5.3、ADR-2 补记 |` | **该行末单元格内**（末列内容之后、闭合 `|` 之前）—— 表格行结构不变（实读 `|` 计数仍为 5 = 4 列） |
| `:642` | 风险表取舍条目行 | 同上，末单元格内（`…（ADR-6 硬规范①，§5.6）` 之后） |

**偏差说明**：卡片 A-4-14 只授权"加行尾注记"，未指定表格行的落位。本苞对 `:551` / `:642` 两条**表格行**把注记落在**末单元格内**而非整行闭合 `|` 之后 —— 理由：GFM 表格行在闭合 `|` 之后追加文本会被解析为**新单元格**（列数从 4 变 5，破坏表体渲染），落入末单元格既满足"行尾注记"语义、又保持列数不变。整行旧文本逐字节保留，"内容零删"纪律未破。此形态与仓库既有先例一致（`R-99` 行的"完成留痕"即追加在末单元格内）。

### 4.2 `docs/M2-排期计划.md` `:233`（`R-41` 行）

编辑前**重新实读**该行现值（`sed -n '233p'`，与任务书给的"约 :233"一致）。行尾注记追加在**末单元格内**（同上理由，`|` 计数实读仍为 3 = 2 列）：

> `**行尾注记（2026-10-10 数字规格小批苞 A，只追加）**：该键已于苞 A 拆分为 `app.ai.hitl.confirm-timeout`（240s）/ `app.ai.hitl.frontend-tool-timeout`（120s）；故本行正文的「至多 120s」现对应**确认门** `confirm-timeout`，本轮实取 **240s**（600s 目标值受 `app.ai.resilience.total-budget` 300s 约束不可达，见 `R-99` 行留痕），`issue#6` 的端口口径澄清随之改锚。本行为登记原文，不回改。`

行内旧文本（`CountDownLatch` 阻塞运行线程至多 120s（`hitl.timeout`）…`→ 新轮 503 属设计内准入；挂起期心跳由该线程自己每 2s 发` / `**待施工**：明确「多挂起累计预算」+ 心跳改独立调度；并补 issue#6 端口口径…）**零删**。

### 4.3 `docs/M2-排期计划.md` 追加观察项小节（`:351-354`，文件 EOF）

编辑前**重新实读**：该节原文末行 = `:350`（批 A 红队观察项表第 5 行），文件当时共 350 行、EOF 即该行。追加后共 **354 行**（新增 4 行：空行 / 标题 / 空行 / 引文），原文零改动。

新增小节标题：`### 小批苞 A 观察项登记：SseChatEmitterWriteBudgetTest 时序脆弱面（2026-10-10，只追加）`

内容要点（只追加，逐项登记）：

- 观察项 1（**登记不修**）：`SseChatEmitterWriteBudgetTest#writeTimeoutEvictsTheSubscriberAndClosesItWhenTheStuckWriteFinallyReturns` 存在**既有时序脆弱面** —— 断言"收尾发生在写出线程"（期望线程名以 `budget-writer` 开头），全量并发负载下偶发落在投递池线程 `budget-delivery-1`；
- 实测三态：全量 r1 = `Tests run: 387, Failures: 1`（失败项即本用例）；单类重跑 `Tests run: 6, Failures: 0`；全量复跑 r2 = `Tests run: 387, Failures: 0`；
- 判定 = 既有时序脆弱面、与改动无关（该用例只依赖 `AiProperties.Frame.DEFAULT_MAX_BYTES`，不触 `Hitl` 键 / 超时文案 / `/health` 面），不作为门禁；
- 处置 = 登记观察、本轮不修（不属数字规格小批授权面）；候选修法（未授权）：线程归属断言从**瞬时线程名**改为**条件等待 / 事件驱动**。
- 来源与证据指向 `<SCRATCH>/numeric-spec-audit/施工报告-苞A.md` §5 D-4。

---

## 5. 实测结果（全部实跑）

### 5.1 定向

命令（**Maven 包装脚本透传参数**，未使用 `commit-gov/mvn-wrap.sh`）：

```
bash <SCRATCH>/redteam-batch-a/.run-fix/mvn-run.sh -B test \
  -Dtest='AiTools*,ConfirmGate*' -DfailIfNoSpecifiedTests=false
```

结果：`Tests run: 33, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS` / `MVN_EXIT=0`（日志 `<SCRATCH>/numeric-spec-audit/mvn-baoB-target.log`）

| 测试类 | 用例数 | 备注 |
|---|---|---|
| `AiToolsListTasksTruncationTest`（**本苞新增**） | **2** | `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`，耗时 0.341s —— **逐字命中卡片 B-2 绿判据** |
| `AiToolsInvocationTest` | 7 | 回归绿 |
| `AiToolsDisclosureEndpointTest` | 6 | 回归绿（工具描述改动的对外披露面未破） |
| `ConfirmGateDeadlockResumeTest` | 9 | 回归绿 |
| `ConfirmGateFrontendTimeoutTextTest` | 5 | 回归绿（苞 A 新增参数化用例仍在） |
| `ConfirmGateWiringTest` | 4 | 回归绿 |
| **合计** | **33** | 0 失败 / 0 错误 / 0 跳过 |

### 5.2 全量

```
bash <SCRATCH>/redteam-batch-a/.run-fix/mvn-run.sh -B test
```

| 轮次 | 结果 | 备注 |
|---|---|---|
| r1 | `Tests run: 389, Failures: 1, Errors: 0, Skipped: 0` | 唯一失败项 = `SseChatEmitterWriteBudgetTest`（断言消息 `Expecting all elements of: ["budget-delivery-1"]…`，即 §4.3 登记的既有脆弱面）。**与本苞改动无关**：本苞改动面 = `AiTools` 的常量 / 描述 / 截断分支 + 新测试 + 三处文档注记，不触投递预算路径 |
| 单类重跑 | `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`（耗时 1.812s） | `-Dtest=SseChatEmitterWriteBudgetTest`，全绿 ⇒ 偶发时序（日志 `<SCRATCH>/numeric-spec-audit/mvn-baoB-flaky-single.log`） |
| r2（定稿口径） | `Tests run: 389, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS` / `MVN_EXIT=0` | 同一工作树、同一命令，全绿 —— 反证 r1 的 1 处失败为偶发（日志 `<SCRATCH>/numeric-spec-audit/mvn-baoB-full-r2.log`） |

**XML 口径（定稿）**：`backend/target/surefire-reports/` 下 **60 个 `TEST-*.xml`**，逐文件汇总 `tests=389 / failures=0 / errors=0 / skipped=0`。

| 口径 | 苞 A 基线 | 本苞 | 变化 |
|---|---|---|---|
| 测试总数（XML 汇总） | 387 | **389** | **+2**（新测试类 2 个用例） |
| XML 文件数 | 59 | **60** | **+1**（新增一个测试类 ⇒ 多一个 XML；只增不减） |

### 5.3 前端

**未触及前端**（`git status` 中 `frontend/` 的 3 个文件改动全部来自苞 A，本苞未新增任何前端改动）⇒ `yarn typecheck` / `yarn build` 不适用，未跑。

### 5.4 文档门禁

```
node scripts/ci/check-docs.mjs
```

结果：**6/6 全绿**（`全部通过：6/6 断言绿。`）。关键断言：① 导航/口径文档无盘符绝对路径 PASS（新增注记零路径）；③ 引用文件存在 PASS；⑤ 命名纪律禁则 PASS（`git ls-files` 919 个路径、实扫文本 916、豁免 3；规则自检 5/5 + 24/24 + 负对照 11 全过）。**注**：断言⑤只扫 `git ls-files` 的**已跟踪**文本，本苞新增的测试类为未跟踪文件、不在其扫描面内 —— 该文件已人工核对，不含禁用命名组合。

### 5.5 端口纪律

本苞**未起任何服务**（无 E2E、无 jar 启动、无 `netstat` 需求），未占用 18330 起的任何端口。

---

## 6. 与施工卡的对照（逐条）

| 卡片条目 | 状态 |
|---|---|
| B-1.1 类内新增常量 + 依据注释 | ✅（`AiTools.java:133-144`，注释文案按卡片原文） |
| B-1.2 `:139` `limit(10)` → `limit(LIST_TASKS_MAX_ROWS)` | ✅（`AiTools.java:155`） |
| B-1.3 列表末尾追加告知（`共 N 条，仅显示前 M 条`） | ✅（`AiTools.java:159-160`，文案逐字） |
| B-1.4 `@Tool` description 补上限（卡片标【待指挥官裁决】） | ✅ **已按指挥官授权执行**（§2 对照表） |
| B-1.5 明确不做：不改 `findAll()` 全表读；不加分页/游标参数 | ✅ 未动（边界遵守） |
| B-2 新增 `AiToolsListTasksTruncationTest`（3 条断言） | ✅（2 个用例覆盖 3 条断言，§3 映射表） |
| B-2 绿判据 `Tests run: 2, Failures: 0` | ✅ 逐字命中（§5.1） |
| 三、附带注记项 1（`docs/03` 冻结文档行尾注记） | ✅（3 行，§4.1） |
| 附带注记项 2（`docs/M2-排期计划.md` R-41 行尾注记） | ✅（§4.2） |
| 附带注记项 3（M2 等待治理登记节追加观察项） | ✅（§4.3） |

**与施工卡的冲突 / 卡片未覆盖处**：无实质冲突。唯一需要点明的两处（均已在 §4.1 / §7 D-2 详述）：① 卡片 A-4-14 / B-1.4 标记的【待指挥官裁决】由指挥官任务书直接授权落地；② 表格行的"行尾注记"落位（末单元格内 vs 闭合 `|` 之后）卡片未规定，本苞按 Markdown 表格完整性择前者。

---

## 7. 偏差与处置

| # | 偏差 / 风险 | 处置 |
|---|---|---|
| D-1 | 卡片 B-1.4 / A-4-14 标【待指挥官裁决】，本苞按指挥官任务书执行（改 `@Tool` description 为 20 口径） | 已执行并在 §2 记录前后对照；如指挥官改判，回滚面 = `AiTools.java:146-149` 一处注释 + 一行注解 |
| D-2 | 两处**表格行**的"行尾注记"落在末单元格内（非闭合 `|` 之后） | 保持表格列数不变（`:233` 的 `|` 计数 = 3；`03` 文档 `:551` = 5、`:642` = 3），旧文本零删；已实读核对 |
| D-3 | 全量 r1 出现 1 处失败（`SseChatEmitterWriteBudgetTest`） | 判为**既有偶发时序**（单类 6/6 绿 + 全量 r2 全绿 + 失败面与本苞改动无交集）⇒ 不阻塞验收；**已按任务书登记到 `docs/M2-排期计划.md` 观察项节**（§4.3）。**附加事实**：该用例在**苞 A 的 r1 与本苞的 r1 连续两批均偶发**（均为全量首次运行），二次复现增强"既有时序脆弱面"的判定强度，建议后续单独立条（见 §8） |
| D-4 | 苞 A 在 `AiTools.java:133-138` 留的过渡注释本苞已改写为现态口径，苞 A 报告 §1.1 对该行的行级描述随之过时 | 属预期的跨苞交接（苞 A 注释原文即写明"待 B 苞同步改"）；苞 A 报告为历史证据文档，**不回改**（如需回改请指示） |
| D-5 | 未跑 E2E / 未跑前端命令 | 本苞改动不涉 E2E 与前端面（§5.3 / §5.5）；卡片苞 B 未要求 E2E |

---

## 8. 遗留问题（未做 / 待后续）

1. **`docs/M2-排期计划.md` `:290`（`R-98` 行）内的 `.limit(10)` 已过时**（现值 20）—— 该行是 W-12「`list_tasks` 全表读 + limit 丢弃」的**登记原文**，本苞**未获授权编辑**，故只登记不改。**建议**（【待裁决】，文案可直接粘贴）：
   > `**行尾注记（2026-10-10 数字规格小批苞 B，只追加）**：本行所述 `.limit(10)` 已于苞 B 改为 `.limit(20)`（`AiTools.LIST_TASKS_MAX_ROWS`），并补"共 N 条仅显示前 M 条"截断告知；**全表 `findAll()` 读取面未变**（本行 W-12 的"浪费面"主张不受影响）。本行为登记原文，不回改。`
2. **`taskService.findAll()` 全表读未优化**（卡片 B-1.5 明确不做，属 `R-98` W-12）；**未加分页/游标参数**（工具 schema 不变，卡片边界）。
3. **`SseChatEmitterWriteBudgetTest` 时序脆弱面未修**（§4.3 登记；候选修法已写入注记）。**注意**：该用例已连续两批（苞 A r1、苞 B r1）在全量首次运行中偶发，建议后续批次**单独立条**（当前只挂在 `M2-排期计划.md` 的苞 A 观察项小节内）。
4. **苞 A 报告 §1.1 的行级描述对 `AiTools.java:133-138` 已过时**（D-4）—— 是否回改该历史报告由指挥官决定。
5. 未触碰项（与卡片 §7 边界一致）：`AsyncConfig` 数值、Hikari 取值、`api/sse.ts` 重连、`api/tasks.ts` 接管、Excel 面、`ContextBuilder`、`api/jobs.ts` 作业超时等，本苞一律未动。

---

## 9. 证据落点（全部在 `<SCRATCH>` 内，无盘符路径）

| 证据 | 落点 |
|---|---|
| 本报告 | `<SCRATCH>/numeric-spec-audit/施工报告-苞B.md` |
| 定向测试日志（33/0/0/0） | `<SCRATCH>/numeric-spec-audit/mvn-baoB-target.log` |
| 全量 r1 日志（389/1/0/0） | `<SCRATCH>/numeric-spec-audit/mvn-baoB-full-r1.log` |
| 全量 r2 日志（389/0/0/0，定稿） | `<SCRATCH>/numeric-spec-audit/mvn-baoB-full-r2.log` |
| 偶发用例单类重跑日志（6/0/0/0） | `<SCRATCH>/numeric-spec-audit/mvn-baoB-flaky-single.log` |
| 测试 XML 明细 | `<MAIN_REPO>/backend/target/surefire-reports/`（60 个 `TEST-*.xml`） |
| 工作树改动查阅命令 | `git -C <MAIN_REPO> status --porcelain` / `git -C <MAIN_REPO> diff` |
| 门禁 | `node <MAIN_REPO>/scripts/ci/check-docs.mjs` → 6/6 绿 |
