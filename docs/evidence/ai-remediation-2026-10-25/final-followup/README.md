# 最终收尾证据 · 索引（final-followup，2026-10-26）

> **性质**：本目录是 `<主仓>` 当前收尾阶段（W-30 取消收尾、G-12 桩编排、取消中断的租约健康分类、既有 workflow 归因）的**历史证据**归档。**不代替**终稿与现行基线；与 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md` 冲突时以终稿为准。
> **原登记状态（保留当时事实）**：三份补丁与两处 workflow 接线**已合入 `<主仓>` 工作树，尚未 commit / push**；**本批修复尚未触发新的 GitHub 验收**（第 6 份里的旧 run 只用于**失败归因**，不是本批的通过证据；因此不能说"从未跑过 GitHub"）。本目录只登记"本机验过什么、还差什么"，**不宣告验收通过**；最终精确 SHA 与 GitHub 结论由主控回写。
> **后续事实补记**：上述三份补丁后来以 `d373ae6` 推送；主 CI `40218428575` 通过（E2E 166/166），真实三实例 `40219304040` 通过 16/16，Redis 5 环境级 `40219307462` 为 PASS 73 / FAIL 0 / KNOWN-LIMIT 1 / SKIP 3。随后 nightly `40219310874` 与真机 `40220039653` 失败，故从未据此宣称五工作流验收完成。新一批修复以 `636dd78` 推送，详情见第 9 份；当前远端仍在验收，最终 SHA 与结论以实际运行回写。**【2026-10-26 追注，原句保留】** 最终 SHA 已落地为 `61de820`：五工作流中 `nightly` / `cluster-3node` / `redis-5-smoke` / `e2e-real` 各**三次 success**，`ci.yml` 只有两次尝试且 attempt 2 因 `AIE2E-Q-08` 为红 ⇒ **4/5 满足、主 CI 未达成**，逐 run 事实见本目录 `final-acceptance-2026-10-26.md` 与本文件 §5。
> **口径**：只做三类替换（本机盘符路径根段 → `<仓外证据>`/`<主仓>`/`<验证副本>`/`<工具安装目录>`；旧主仓目录名 → `<主仓>`；个人用户名 → `<仓库所有者>`）+ 排版清理（去行尾空白、单个结尾换行）。**不改红/绿历史、不改计数、不改结论**；推断出的 cause 链一律标注为推断，**不写成 CI 逐层日志事实**。

## 1. 文件清单

| # | 文件 | 来源（相对 `<仓外证据>`） | 用途 | 本机已验 | 未验 / 待 GitHub |
|---|---|---|---|---|---|
| 1 | `w30-fix-report.md` | `w30-fix-report.md` | W-30 取消收尾交棒**第二轮返修报告**（第一轮拒审后的逐项闭合 + 最终 E2E 断言） | 新用例 9/9、基线红用例在修复树 10/10、相邻 22 类 425/425、Spring 上下文 4/4、装配探针 1/1；`verify-e2e.ps1` **仅 PowerShell 语法解析**（`PARSE-OK tokens=47870 lines=5737`，+11 行） | **W-30 E2E 本机未跑**（只是语法检查，**不是** E2E 通过）；真实归档「`done` 恰 1 条且是最后一帧 + seq 严格递增」、空 `stopOn` 整读按时 EOF、生产 profile 装配、`aiResumeTrigger` 池不饱和、多实例窗口、"快照已 CANCELLED 但 done 缺失"的真实恢复 |
| 2 | `w30-independent-review.md` | `w30-independent-review.md` | W-30 **第一轮**独立只读复审（**REQUEST_CHANGES**，I-1/I-2/I-3/I-4 + M-1…M-6） | 静态审查 + `boot.log` 对读；**未跑** Maven/单测/E2E（作者已声明） | 属**历史留痕**：其结论已被第 3 份的第二轮复审闭合，**不代表现状**，但也不撤销（保留拒审过程） |
| 3 | `w30-independent-rereview.md` | `w30-independent-rereview.md` | W-30 **第二轮**窄复审（**APPROVED / Ready to merge: Yes**） | **实跑 13/13**（`CancelFinalizeHandoffTest` 9/9 + `ResilientChatStaleDriverGuardTest` 4/4，BUILD SUCCESS，21s，日志 `w30-rereview-tests.log`）；核对了 9 份日志计数 | `Declined to judge` 10 项：GitHub 上的 W-30 E2E、真实 Redis Stream 的「done 恰一条且最后」、持续"读降级但写可用"、多实例接管、取消风暴池饱和、首轮驱动会话门接管、生产全上下文对最终字节的装载、既有并发帧序、`markFacilityUnhealthy` 触发面、多用户/真 PG |
| 4 | `g12-stub-order-report.md` | `g12-stub-order-report.md` | G-12 **桩跨用例状态**回归（根因＝测试编排：桩按首条 user 文本建桶、跨用例共享 + 异步续跑到达顺序；**不是产品/SSE 缺陷**） | 红 `node --test` 1 跑 1 败（G-10/G-12 精确复现 18 字文本收尾）→ 只改两处标记后 1/1 通过（连跑 3 次一致）；`stub-upstream.mjs --self-check` PASS；`verify-e2e.ps1` 仅 `Parser::ParseFile` 语法解析；两个 workflow 各 `hits=1`、位置与前后步骤已静态核对 | 真实 workflow 首次 e2e job 需观察该步骤与 G-09…G-12 全绿（最好跨两种到达顺序）；`scenario=FILTER`（G-04/G-16/G-17 共用）同型风险**未改**；桩的指纹/清零机制未改 |
| 5 | `lease-local-interrupt-review.md` | `lease-local-interrupt-review.md` | 取消中断的租约健康分类补丁 · 小范围只读复审（**APPROVED**；4 条非阻塞 Minor） | 与实现侧日志逐项对账一致（红 4/2、绿 14/14、探针 8/8、主仓 12/12）；补丁与工作树逐行相同 | 复审当时**未跑**"W-30 + 本补丁"的组合；**主控其后在主仓工作树串行跑出 16/16**（§3 第 7 项）；**真实 Lettuce/Redis 中断**、集群多实例、`@real` 未验；`QueryTimeoutException` / CROSSSLOT 分类未单列；4 条 Minor 保留 |
| 6 | `github-workflow-previous-runs.md` | `old-workflow-triage/README.md`（**入库改名**） | 既有 GitHub workflow **失败归因**（cluster-3node / e2e-real / nightly 三次历史 run + 当前主 CI 的 W-30） | 历史日志与代码/回归用例的**只读核对** | **不是新环境验收**：这些 run 的代码点都早于当前收尾代码，**不能当作当前通过**；真实重跑见 §3 |
| 7 | `lease-local-interrupt-implementation.md` | **本批新建**（素材：三个只读会话 output.log + 主控组合日志） | 该补丁的实现记录：根因（事实 vs 推断）、改动面、红→绿计数、m1/m3 后续最小闭合、组合 16/16、边界 | 见该文 §3 / §4 / §5（红 4/2、14/14、4/4、8/8、12/12；m1 红 5/1 → 绿 7/7；组合 **16/16**） | 见该文 §5：m2/m4 未做；**真实 Lettuce/Redis 中断、W-30 完整 E2E、集群多实例、`@real` 未验** |
| 8 | `README.md` | 本索引 | 本目录用途与状态口径 | — | — |
| 9 | `ci-failure-followup-2026-10-26.md` | 本批新建（主控核对实现、独立复审与实测日志） | nightly、K-10、R-06、KQC 与桩契约的后续修复；本地验证与 MDO 未决边界 | 后端 2860/2860、前端 145 文件/1672 用例、前端构建、文档 6/6、迁移 52/52；定向与独立复审见该文（**该 2860 已被 §5 的 2889 取代，原值只作当时留痕**） | 最终五工作流与生产环境边界尚未完成；MDO 已取到 `PROVIDER_GONE`，共享拓扑修复与分组回归通过，真实双 JVM 待复验 |
| 10 | `final-acceptance-2026-10-26.md` | **本批新建**（`gh run list` / `gh run view --log` 逐条取数） | 同一 SHA `61de820` 上五工作流的实跑事实：4/5 各三次成功、主 CI 仅两次尝试且最新为红、e2e-real 两 job 均执行、`sample=10` 的可证程度与降级口径 | run 级结论 + job 日志计数行（后端 2889/2889、前端 145/1672、cluster 16/0、redis 73/0/1/3、桩 268 passed + 2 skipped、真模型 9 passed） | 见该文 §5：H2 ≠ 生产 PG、Redis 5 环境级 ≠ 生产验证、真机采样为概率口径、真实 Lettuce/Redis 中断与真实 PG 迁移未验；`L-143` 未闭环 |
| 11 | `q08-premise-timing-analysis.md` | **本批新建**（失败日志 + 源码行号对读） | Q-08「第二次请求 200 而预期 409」的归因：**测试前提 / 时序竞态**（park 释放会话门），并给两种处置方向的代价对照 | `verify-e2e.ps1:3993-4008`（断言 `:4003`）与 `AiController.java:480-484`（`finally` 释放门）+ `:412`/`:443-444`（409 唯一来源）；attempt 1 绿 / attempt 2 红同码对照 | **未修、未裁决**：两方向（改测试 vs 改产品持锁）均待用户选路；200 分支下历史是否增长未观测 |

## 2. 当前可写结论（截至 2026-10-26，本地）

1. **W-30**：第一轮 `REQUEST_CHANGES` → 返修 → 第二轮 **APPROVED**（复审实跑 13/13）。**但 W-30 的 E2E 断言本机只做了 PowerShell 语法检查，必须等 GitHub 真跑**；"该用例转绿""归档 done 恰一条且最后""seq 严格递增"目前**都不是已验事实**。
2. **G-12**：定性为**测试编排**问题（桩跨用例台账 + 异步续跑顺序），**不是模型服从率、不是产品/SSE 缺陷**；红绿都在桩上完成，已接入 `ci.yml` / `nightly.yml` 的 e2e job（各 1 步，位置在"桩上游自检"之后、"启动桩上游"之前）。
3. **本地构建与测试（照录主控交付值，未在本批复跑）**：
   - 前端 **145/1666** 通过，且前端 **build 成功**；
   - 后端全量 **2846/2846，BUILD SUCCESS**（`<仓外证据>/final-w30/backend-full.log`）。
   - ⚠ 该全量里 `LeaseLocalInterruptHealthTest` 只跑了 **4** 条 —— m1 的第 5 条反例（`aLocallyInterruptedResourceLockLeavesNoTombstone`）是**之后**才加的，因此这次全量**早于"资源锁条件一行 + 新增用例"**，**不能当作最终精确 SHA 的验证**；**不要写成 2847**，也不要说成"最终代码全量通过"。最终精确 SHA 需另跑一次全量。
   - **【2026-10-26 追注，原值保留】** 上述 `2846/2846` 与 `145/1666` 已被后续取代：`ci-failure-followup-2026-10-26.md` §7 记为 **2860/2860 + 145/1672**，最终 SHA `61de820` 的 GitHub 实测为 **2889/2889 + 145 文件 / 1672 用例**（见 §5 与 `final-acceptance-2026-10-26.md`）。三组数字各属不同时点，**都不得当最终值**，引用时必须带时点。
4. **组合聚焦已在本机完成**：主控在主仓工作树串行跑 `CancelFinalizeHandoffTest` 9 + `LeaseAuthorityProbeFailClosedTest` 2 + `LeaseLocalInterruptHealthTest` 5 = **16/16，Failures 0 / Errors 0，BUILD SUCCESS**（`<仓外证据>/final-w30/backend-combined-targeted.log`）。
   - **仍待真实结果**：① **五个 GitHub workflow**（§3 第 1–5 项）；② **W-30 完整 E2E**（§3 第 6 项）；③ **真实 Lettuce/Redis 中断**下的分类行为 —— 上述 16/16 是**替身/单测组合**，不是真实环境证明。
5. **未提交/未验收**：三份补丁（W-30、G-12、`cancel-lease-health`）与两处 workflow 接线**已合入 `<主仓>` 工作树**，但**未 commit、未 push**；任何"通过"都只是**本地**结论。

## 3. 待主控回写（真实 SHA / run ID / 结论）

| # | 面 | 判据 | 回写字段 |
|---|---|---|---|
| 1 | `ci.yml`（主 CI） | 修复后实际 success（含 E2E 用例集） | run ID：____ / SHA：____ / 结论：____ |
| 2 | `nightly.yml` | 后端双 locale、前端、E2E、testmap、汇总均实际 success | run ID：____ / SHA：____ / 结论：____ |
| 3 | `cluster-3node.yml` | 真实三 JVM + 共享基础设施 + 无粘滞路由 + 跨实例与 kill 接管；重点 `suspend-cross-instance` | run ID：____ / SHA：____ / 结论：____ |
| 4 | `redis-5-smoke.yml` | 实际成功（环境级结论，不冒充生产验证） | run ID：____ / SHA：____ / 结论：____ |
| 5 | `e2e-real.yml` | `suite=both`、`sample=10`，桩 job 与真模型 job 都执行且 success | run ID：____ / SHA：____ / 结论：____ |
| 6 | W-30 E2E 断言 | 20s 内快照 CANCELLED、条目非 PENDING、迟到回灌 409、同会话下一轮非 409；归档 `done` 恰 1 条且最后、seq 严格递增 | run ID：____ / 结论：____ |
| 7 | 最终组合聚焦（**本地已完成**） | `CancelFinalizeHandoffTest` 9 + `LeaseAuthorityProbeFailClosedTest` 2 + `LeaseLocalInterruptHealthTest` 5（串行） | **16/16 · Failures 0 · Errors 0 · BUILD SUCCESS**（`<仓外证据>/final-w30/backend-combined-targeted.log`）；GitHub 侧**无对应 run**，仅本地证据 |

> 只有上表 1–6 拿到真实 GitHub success，才谈得上当前开发验收；**第 7 项是本地串行组合，不计入验收**。未执行、skipped、未覆盖的边界必须单独登记，不得用"已有单测"替代真实验收。

## 4. 保留的未验边界（不得因本目录存在而视为已验）

- **真实 Lettuce/Redis 的中断形态**：租约分类的判据来自用例复刻的 cause 链，CI 日志只有顶层 message；真实中断未验。
- **真实 Redis Stream 归档**形态下的终帧语义（本机全是 InMemory / 替身）。
- **多实例 / 集群**窗口：`LEASE_HELD_ELSEWHERE` 重试期间另一实例合法接管跑成 DONE；集群下的墓碑与 1s 探针。
- **`@real` 真模型**行为与服从率（G-12 的桩通过只证明"产品链路正确消费了桩给的表单"）。
- **取消风暴**下 `aiResumeTrigger` 池饱和。
- 非阻塞 Minor（W-30 复审 4 条、租约复审 4 条）与其中 m2/m4 的缺口：**只登记，不扩新任务**。

## 5. 2026-10-26 回写：真实 SHA / run ID / 结论（追加，不改上表原文）

> **基线**：`HEAD == origin/main == 61de820`（`61de820`「test(frontend): 固定英文面板用例的Pinia实例」）。下表 run 的 `headSha` 全部为该提交（逐条核对）。**§3 的 `____` 保留原样**——它记录的是"当时待回写"的状态；本节的回写是**追加事实**，两者不互相覆盖。

| # | 面（对应 §3 行） | 三次 run（同 SHA） | 结论 |
|---|---|---|---|
| 1 | `ci.yml` 主 CI | `40255993044`（**仅 2 次尝试**） | attempt 1 `success`（E2E `PASS=166 FAIL=0`）→ attempt 2 `failure`（`PASS=165 FAIL=1`，唯一失败 `AIE2E-Q-08`）⇒ **run 级 failure，不满足三轮，不得写成绿** |
| 2 | `nightly.yml` | `40256321755` / `40257551220` / `40258703173` | 3/3 `success`；后端 **2889/2889**（三次同数，双 locale 各一遍）、前端 **145 文件 / 1672 用例**、E2E job 绿、倒排表重建与校验绿 |
| 3 | `cluster-3node.yml` | `40256321742` / `40256956378` / `40257579571` | 3/3 `success`；末次 `PASS 16 / FAIL 0`（含 `suspend-cross-instance`、`idempotency-cross-instance`、kill 接管） |
| 4 | `redis-5-smoke.yml` | `40256321631` / `40256402773` / `40256512660` | 3/3 `success`；`PASS 73 / FAIL 0 / KNOWN-LIMIT 1 / SKIP 3`（**环境级**，网内不启动后端） |
| 5 | `e2e-real.yml` | `40256960524` / `40258550325` / `40260513930` | 3/3 `success`；**每次桩 job 与真模型 job 都实际执行**：桩末次逐段求和 `268 passed`、三次日志均见 `2 skipped`；真模型 `9 passed`（三次均见）；`sample=10` 由真模型 job 日志 `E2E_REAL_SAMPLING: 10` 与用例名「采样 10 次 ≥9」留证（**输入值 REST 不可回查**，详见新件 §3） |
| 6 | W-30 E2E 断言 | 同上 `ci.yml` attempt 1 与 nightly 三次 | `[PASS] AIE2E-W-30` 实际执行并绿（含"迟到回灌 409 + 同会话可发下一轮"）；**注意**：attempt 1 绿但该 run 整体红，且 W-30 的"归档 `done` 恰 1 条且最后 / seq 严格递增"仍只在桩态被覆盖，**真实 Redis Stream 形态未验** |
| 7 | 最终组合聚焦 | 无 GitHub run | 本地 **16/16**（原 §3 第 7 项口径不变，不计入 GitHub 验收） |

**本批并行产出**：`final-acceptance-2026-10-26.md`（五工作流逐 run 取证 + 未验边界）、`q08-premise-timing-analysis.md`（Q-08 归因：测试前提 / 时序，**未修未裁**）。

## 6. 口径提醒（新增，避免旧数字被当最终）

- **§2 与 §4 的"未验"不因 §5 而自动消解**：§5 只能证明"这些 run 在这个 SHA 上跑过且结论如此"，不能证明 H2 等同生产 PG、Redis 5 冒烟等同生产验证、真机采样等同服从率。
- **`L-149` 仍不销记**：4/5 满足三轮，`ci.yml` 未达成（2 次尝试、最新红、Q-08 未修）。
- **`L-150` 仍不销记**：cluster 的 16/16 不含三条安全反例（`UNKNOWN` 不得执行 / 已 `CANCELLED` 不得误认领 / claim 哈希-only 不得伪未执行）。
- **`L-143` 仍不销记**：三次绿不构成"间歇竞态已消失"的证据。
- **九册正文**：`docs/delivery/` 不存在，正文零启动，等用户明确指令。

## 7. 附件：Q-08 根因原始分析（源稿，2026-10-26 追加）

- **文件**：[`q08-source-analysis.md`](q08-source-analysis.md)
- **性质**：**历史分析 / 过程证据 — 非现行基线**；未改测试、未改产品、**未裁决**。**不把 Q-08 当产品已确认缺陷**：结论仍是“**测试前提 / 时序**”，`ci.yml` 依旧为红，`L-149` 不销记。
- **与第 11 项的关系**：第 11 项 `q08-premise-timing-analysis.md` 是**结论层摘要**（断言行号、409 的唯一来源、两种处置方向的代价）；本附件是它的**源稿**，保留摘要覆盖不到的细节 —— attempt 2 的逐毫秒事件表（`turn_accepted` → `approval_park` → `turn_parked` → POST#2 → 200，该会话 `turn_denied` 计数 0）、同 run 内 Q-05 的 4 ms 对照、**跨 6 次历史 run 的 park↔deny 余量表（0–1 ms；本次因多一次 `GET /api/ai/history` 变成 12 ms）**、以及“前端不产生该请求、Q-08 是协议层探针”的 UI 侧排除。**引用细节以本附件为准，引用结论两者可互证。**
- **仍待裁决的语义冲突**：`docs/03-技术方案文档-v2.1.md:313`（挂起中再发一轮返回 409）与 2026-10-25 park 裁决（`ConfirmGate.java:653-661`）及现行实现相反 ⇒ **产品意图未定**，两条处置方向都要用户选路，本目录**不自裁**。
- **残留取证缺口**：attempt 1 同 SHA 上该用例 PASS，但其 `boot.log` 已被 attempt 2 的 artifact 覆盖，**attempt 1 的确切余量不可测**。
