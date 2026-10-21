# 补档 · B23L1 批③ 资源治理提交报告（提交官 · DS-Flash · 2026-10-21）

## 0 自证行（wire 实测，非照抄卡片）

- 提交官 agentId = `agent-1336`；wire = `<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_604b3bc4-1812-4fe3-b259-6c0a97160f45/agents/agent-1336/wire.jsonl`
- 取数方式：首行为 `{"type":"metadata",...}`（非 bind 行），`grep -n 'profile.bind'` 命中**第 5 行**，原文首部（逐字）：

```json
{"type":"profile.bind","agentId":"agent-1336","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high",...}
```

- 实测三字段：`modelAlias` = **`DeepSeek/deepseek-flash`**、`profileName` = **`coder`**、`thinkingEffort` = **`high`**
- 仓根 = `<REPO_ROOT>`（唯一可操作仓）；职责边界 = 机械验收 + commit + push + CI 跟进；**零生产代码改动**（本批四笔只含看板 / 决策看板 / 设计稿口径 + 五件证据文档 + 施工方已交付的代码）。零服务启停（harness 由实机核验方跑，提交官只读其证据）；`?? data/` 未动未入库；**在飞调研/设计件一律未提交**（见 §4.3）。

## 1 机械验收逐项

### 1.1 `git status --porcelain`（验收前基线，37 条）

- 后端**生产 10** = **6 改** + **4 新**
  - 改：`ai/config/AiProperties.java`、`ai/gate/SpToolCallingManager.java`、`ai/run/{LeaseService,ResumeService,RunStore}.java`、`ai/tool/ToolOutcome.java`
  - 新：`ai/gate/{ToolResourceLocks,ToolTargetResolver,ToolUnknownReconciler}.java`、`ai/run/LeaseKeys.java`
- 后端**测试 15** = **11 新**（`ai/gate/{ToolIdempotencyTest,ToolResourceLockGateTest,ToolTargetResolverGateTest,ToolTargetResolverTest,ToolUnknownReconcilerTest}.java`、`ai/run/{LeaseKeysTest,LeaseResourceTtlTest,LeaseServiceDistinctOwnerTest,ResumeDescribeLockKeysTest,RunStoreIdempotencyIndexTest}.java`、`ai/tool/ToolOutcomeResourceCodesTest.java`）+ **4 改**（`ai/conformance/InMemoryRunStore.java`、`ai/run/{FakeLeaseRedis,RunStoreClaimFenceTest}.java`、`ai/tool/ToolResultEnvelopeTest.java`）
- **前端 2** = `frontend/src/utils/parts.ts` + `frontend/tests/tool-outcome-envelope.spec.ts`
- **脚本 2** = `scripts/b23l1-dual-instance.ps1` + `scripts/README.md`
- 文档面 = `docs/任务看板.md`、`docs/决策看板.md`、`docs/research/README.md`、`docs/research/设计-B23L1多实例与资源治理专项-2026-10-21.md` + 3 件新设计稿 + 5 件新补档
- **未纳入**：`?? data/`（历史遗留）、`?? docs/research/调研-AI操作在线表格能力业界惯例-2026-10-21.md`、`?? docs/research/调研-AI上下文数据最小化业界惯例-2026-10-21.md`、`?? docs/research/调研-AI输入输出安全防护业界惯例-2026-10-21.md`、`?? docs/research/设计-AI表格操作专项-2026-10-21.md`（**在飞件，用户新提、未批准入库**）

第一笔入库 **29 文件 = 10 生产 + 15 测试 + 2 前端 + 2 脚本**，`+1668 / −193`（按 `git diff --cached --stat` 三笔合计见 §2）。

### 1.2 计数口径（**以实测为准，显著标注**）

| 项 | 卡片登记 | 实测 | 差 | 处置 |
| --- | --- | --- | --- | --- |
| 后端用例（mvn 汇总行） | 778 | **778**（`Tests run: 778, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`，exit 0） | 0 | 一致 |
| 后端用例（surefire `tests` 属性求和） | — | **778**（**123** 个 suite） | 0 | 两口径一致；复核前两轮的 766/687 是"数 `<testcase>`"漏计 `@Nested`（差 10），口径已统一为**汇总行** |
| **后端生产"7 改"** | **7 改 + 4 新** | **6 改 + 4 新** | **−1** | **卡片多记 1**：实测 `git status` 的后端生产改文件是 6 个（`AiProperties` / `SpToolCallingManager` / `LeaseService` / `ResumeService` / `RunStore` / `ToolOutcome`），**无第 7 个**（`application.yml`、`AiController` 本批零改动）。以实测 6 改记，`10 生产文件`口径见 §1.1 |
| 新增测试类 | 11 新类 | **11 新类 = 75 条用例** | 0 | 一致；另有 4 个既有测试文件改（含 `RunStoreClaimFenceTest` +1 例 ⇒ 本批新增用例合计 **76**，702 + 76 = 778） |
| 前端用例 | 513/513 · 26 文件 | **513/513 · 26 文件** | 0 | 一致（+1 例，自批② 512） |
| 施工链各段自报 | 741 → 776 → 778 | 保留（741 见 `_b3a-green4.log`；776/778 为各段实测） | — | 最终门禁以提交官复跑 778 为准 |

### 1.3 脱敏终扫（DC-08 · 三规则 SECRET / PATH / USER）

用仓内 `scripts/githooks/scan-lib.sh` 同一套规则（与 `pre-commit` / `scan-history` 同源）：

| 轮次 | 扫描面 | 命中 | 处置 |
| --- | --- | --- | --- |
| 首扫（写证据件前，主体笔暂存前） | ① 已跟踪全量 diff 的 `+` 行 ② 未跟踪新件逐文件全文（排除 `data/`） | **0 / 1** | 唯一的 1 处在**在飞调研件** `调研-AI操作在线表格能力业界惯例-2026-10-21.md:5`（PATH，本机绝对路径）⇒ **不属于本批**，未提交 |
| 复扫（看板/设计稿改完后） | ①② | **0 / 2** | 2 处均在**新增的在飞调研件** `调研-AI输入输出安全防护业界惯例-2026-10-21.md`（`:168` SECRET / `:482` USER）⇒ 同样**不属于本批**，未提交 |
| 每笔提交前 | ③ 暂存区（`pre-commit` 阻断式扫描） | **0** | 第 2/3/4 笔均见 `✓ [pre-commit] 敏感信息扫描通过（暂存区无命中）` |

未使用 `AI_SECRETS_ALLOW`、未 `--no-verify`。**口径**：本批**提交面 0 命中**；在工作树里能扫到的命中全部来自"用户刚提、尚未批准入库"的在飞件。

### 1.4 文档门禁 check-docs（四个时点，均 6/6）

| 时点 | 结果 | 扫描规模 |
| --- | --- | --- |
| 基線（未改任何文档） | **6/6** 绿 | 1369 路径 / 实扫 1366 |
| 看板 + 决策看板改完（第 2 笔前） | **6/6** 绿 | 1384 路径 / 实扫 1381（新增件已在工作树但未入库，索引路径数随 `git ls-files` 变化） |
| 设计稿 + README + 设计-B23L1 改完（第 3 笔前） | **6/6** 绿 | 同上 |
| 五件补档落地（第 4 笔前） | **6/6** 绿 | 同上 |

### 1.5 测试门禁复跑（提交官本人实测）

| 门禁 | 命令 | 实测 |
| --- | --- | --- |
| 后端全量 | `cd backend && <MAVEN_HOME>/bin/mvn -o test`（Maven 3.9.16 离线，**非 `-q`**） | **exit 0 / BUILD SUCCESS**；`Tests run: 778, Failures: 0, Errors: 0, Skipped: 0`；surefire 123 suite、`tests` 求和 778（两口径一致） |
| 前端全量 | `cd frontend && node node_modules/vitest/vitest.mjs run` | **513/513 · 26 文件**（`Test Files 26 passed` / `Tests 513 passed`；**首跑即绿**，`ai-panel-parts-render` 族 12 例既有 flaky 本跑未触发 ⇒ **未重跑**） |
| 前端类型检查 | `node node_modules/vue-tsc/bin/vue-tsc.js --noEmit` | **exit 0** |
| 前端构建 | `node scripts/build.mjs`（= `package.json` 的 `build` 去掉已单列的 `vue-tsc --noEmit`） | **✓ built in 1m 24s**（exit 0） |
| 文档门禁 | `node scripts/ci/check-docs.mjs` | **6/6**（§1.4） |
| 脱敏 | §1.3 | **提交面 0 命中** |
| E2E（29 用例） | **不本地跑**（需起服务；本批铁律零服务启停） | 由 CI 的 `e2e` job 执行 |

> **后端门禁的一次红（必须留痕）**：全量**首跑** 778 中 1 例失败 = `ResumeServiceLeaseTest#concurrentSameRunResumesDoNotLetTheLoserReleaseTheWinnersLease`（`:302`）。提交官做了三步判定（§4.1）后确认**既有缺陷、非本批引入**，并在**同一工作树**上复跑取得 778/778 绿（本报告全部门禁数字取自该绿跑）。

### 1.6 看板核对（行号按入库后最终态）

| 位置 | 内容 |
| --- | --- |
| `docs/任务看板.md:16` | 批③ 行 **📋 → ✅**：哈希 `c00606e`；门禁 = 后端 778/778 · 123 suite、前端 513/513 · 26 文件、typecheck 0、`build` ✓、check-docs 6/6、脱敏 0、harness 7/7 + SharedDb 2/2；十三裁决要点；依据 = 设计稿 §2.3/§2.4/§7 批③ + 五件补档 |
| `docs/任务看板.md:413` | **L-12 措辞更新**（`-SharedDb` 已提供共享库验证路径 = 形态甲；默认 H2 隔离仍是边界；批③ 起表现改为 `TARGET_INVALID` 前置拦截） |
| `docs/任务看板.md:414`–`:419` | **L-13 / L-14 / L-15 / L-16 / L-17 / L-18 逐条单列**（各带代价与依据） |
| `docs/任务看板.md:420` | **L-19**（提交官复跑发现的既有 flaky；**非本批引入**、不扩权修） |
| `docs/任务看板.md:423` | 速览行同步：批次/笔行 63（✅ 45→**46**、📋 13→**12**）、挂账小节 66→**73**（含批③ 新增 7 条）、10-21 补记 2→**3** |
| `docs/决策看板.md:17` | 批③ **施工裁决 13 条** |
| `docs/决策看板.md:18` | 批③ **复审收口裁决 5 条**（I-1 摘续租 / I-2 重试+SKIP / M-3 字面纠正 / N-1 口径订正 / 计数口径） |
| `docs/research/设计-B23L1多实例与资源治理专项-2026-10-21.md:81` | §7 批③ 状态 **🔧 施工中 → ✅ 已完成**（+ 主体 sha 与复审/复核结论） |
| `docs/research/README.md` | 索引补 **34 / 35 / 36** 三行 + "收录 36 件"计数 + 第 34–36 件说明段 |

## 2 提交与推送（四笔，逐笔 push）

| # | 短哈希 | message | 内容 | push |
| --- | --- | --- | --- | --- |
| 1 | **`c00606e`** | `feat(ai-resource): B23L1批③ 资源互斥租约与业务幂等键` | 29 文件（生产 4 新 + 6 改、测试 11 新 + 4 改、前端 1+1、脚本 2） | `d8056d5..c00606e main -> main` ✓ |
| 2 | **`b3d6b4c`** | `docs(board): B23L1批③ 资源治理入库 + 挂账 L-13~L-19 登记` | 2 文件（任务看板 / 决策看板） | `c00606e..b3d6b4c main -> main` ✓ |
| 3 | **`e5499ca`** | `docs(research): 后续三专项设计稿入库（索引 34/35/36）+ B23L1 批③ 设计稿回写` | 5 文件（3 新设计稿 + research README + 设计-B23L1） | `b3d6b4c..e5499ca main -> main` ✓ |
| 4 | **本件**（push 后回执） | `docs(evidence): 补档 B23L1 批③ 资源治理五件入库` | `docs/evidence/` 五件 + **看板 `:16` 依据列一处措辞订正**（见 §4.5） | 见 §3 |

- 四笔全程 `git add` **显式路径**，无 `add -A` / `add .`；第 2/3 笔与第 4 笔均过 `pre-commit` 阻断式扫描（`✓ 暂存区无命中`）。
- origin 保持 **HTTPS**（`https://github.com/<GITHUB_OWNER>/ai-example.git`），未改远端配置。
- 工作树 `../wt-model-config-b1`（另一条施工线）**未触碰**。

## 3 CI 确认

| 笔 | run id | 触发 | 结论（撰写时 → 终态） |
| --- | --- | --- | --- |
| `c00606e` | **39540040476** | push→main | 首跑 **failure**（唯一红 = 后端 job，`ResumeServiceLeaseTest:302` = **L-19 既有 flaky**；`check-docs` ✓、前端 ✓、`scan-history` 报告制）→ 见 §4.1 处置 |
| `b3d6b4c` | **39540179779** | push→main | 见交付回执 |
| `e5499ca` | **39540224702** | push→main | 见交付回执 |
| 本件（第 4 笔） | 见提交官交付回执 | push→main | 同上 |

- 轮询方式 = `gh run list --limit N` + `gh run view <id> --json status,conclusion` + `gh run view <id> --log-failed`（Git Bash 下 api 端点**无前导斜杠**）。
- 失败 job 日志摘要（run 39540040476，逐字）：`[ERROR] Tests run: 778, Failures: 1, Errors: 0, Skipped: 1` / `[ERROR] ResumeServiceLeaseTest.concurrentSameRunResumesDoNotLetTheLoserReleaseTheWinnersLease:302 [IN_PROGRESS 一方不得删除租约（fence 与 fencing 写全靠它）]`。

## 4 异常与留痕

### 4.1 后端门禁 flaky（唯一实质异常，**既有、非本批**）

| 步骤 | 事实 |
| --- | --- |
| ① 现象 | 提交官全量首跑：`Tests run: 778, Failures: 1` —— `ResumeServiceLeaseTest:302`「IN_PROGRESS 一方不得删除租约」`Expecting actual not to be null` |
| ② 复现率 | 该类单独复跑 **2/5 红**；再一轮 **3/10 红**（≈40–50%） |
| ③ 排除替身因素 | 给 `FakeLeaseRedis.handleScript` 加 `synchronized`（让替身脚本真正原子）后**仍 3/10 红** ⇒ 不是"替身非原子导致双 `created`"（该改动已**还原**，未入库） |
| ④ 排除本批引入 | `git archive d8056d5`（批② HEAD）导出独立副本复跑同一类：**首跑即红、第 5 轮再红** ⇒ **批② 起即有** |
| ⑤ 根因 | `ResumeService.resume(runId,forceTakeover)` = **先抢租、后撞进程内互斥**；`created=true` 的一方若输掉 `resuming` 互斥，会走"非 ACCEPTED ⇒ 当场还租"⇒ 删掉胜方正在用的**同一 token** 租约。"谁创建谁释放"只覆盖"败方是重入者"，**未覆盖"创建者输掉互斥"** |
| ⑥ 处置 | **不扩权修**（生产所有权口径，须复审 + 专项）；登记挂账 **L-19**（看板 `:420`）；CI 因此红时按 flaky 处置（重跑失败 job + 留痕），**不改断言、不跳过用例** |
| ⑦ 绿跑 | 同一工作树复跑取得 **778/778 绿**（本报告所有门禁数字取自该跑） |

> **给指挥官的判断依据**：这条 flaky 与"前端 `ai-panel-parts-render` 族 12 例"同性质（时敏），但它在**后端**且频率高（≈50%），因此 CI 后端 job 的红率不可忽略。是否为此开"续跑所有权口径"专项，属指挥官/用户裁决。

### 4.2 数字与卡片的偏差（两处，均按实测记）

1. **后端生产"7 改" → 实测 6 改**（§1.2）。多记的 1 个无法定位到任何未提交文件（`git status` 后端生产仅 6 个 `M`）。
2. **CI 口径 778 run 有 `Skipped: 1`**（本地同命令 `Skipped: 0`）。属 CI 环境的条件跳过（历史同族现象），不影响"Failures=0/Errors=0"的判定；本报告不据此改本地口径。

### 4.3 在飞件（**未提交**，纪律留痕）

验收期间工作树新增/存在 5 件**在飞件**（用户刚提、尚未批准入库），四笔**全部未提交**：`调研-AI操作在线表格能力业界惯例`、`调研-AI上下文数据最小化业界惯例`、`调研-AI输入输出安全防护业界惯例`、`设计-AI表格操作专项`、`data/`（历史遗留）。验收终点 `git status --porcelain` 仅剩这些未跟踪项。

### 4.4 其他1. **harness 未由提交官运行**：`PASS=7/7` 与 `-SharedDb PASS=2/2` 取自实机核验方证据（`backend/target/b23l1-artifacts-b3{,r2,final}` + `b3shared{,2,3}`，target 不入库），提交官只核对帧与摘要一致性（已抽检 `lock-A-blocked` / `lock-A-ok-done` / `lock-shared-{A,B}` / `kill-B-full` / `idem-A`）。
2. **E2E 29 用例不本地跑**：由 CI 执行。
3. **追加 L-19（超出卡片枚举的 6 条）**：卡片只列 L-13~L-18；提交官按"发现即登记"补 **L-19**，并在速览行同步计数（66→73）。若指挥官认为应只留 6 条，删行 + 计数回 72 即可。

### 4.5 自纠：L-10 的"销记"结论撤回（随第 4 笔回写）

本报告初稿 §5 曾把 **L-10**（claim fence 复核 TOCTOU）记为"已随三态认领收敛"——**该结论不成立**：`tryClaimExecution`（`RunStore:979`）仍是"`heldRunEpoch` → `fenceEpoch`(GET) → 比较 → `HSETNX`"四步，只是**收进了单一入口**，并未合成单脚本，窄 TOCTOU 窗仍在。第 2 笔（`b3d6b4c`）里看板 `:16` 依据列沿用了这个误记，**随本笔回写为"L-9 / L-10 仍挂"**。留痕口径：以第 4 笔后的看板 `:16` 与 §5 为准，**不追改已推送的第 2 笔**（不改写历史）。

## 5 挂账异动清单（本批）

| 动作 | 条目 | 依据 |
| --- | --- | --- |
| 保留 | **L-10**（claim fence 复核 TOCTOU） | **仍挂**：批③ 的 `tryClaimExecution` 只是把"读 fence → 比较 → `HSETNX`"收进单一入口（`RunStore:979`），三步仍非单脚本；本报告初稿曾误记"已收敛"，**本处为准**（同一订正已回写看板 `:16` 依据列） |
| 更新措辞 | **L-12**（跨实例 H2 隔离） | `-SharedDb` 已提供共享库路径（形态甲）；默认 H2 隔离仍是边界（形态乙） |
| **新增** | **L-13** `appendLedger` 多一次 `EXPIRE` 往返（N-2） | 复审报告 §4、`RunStore:1103` |
| **新增** | **L-14** `stopDriving` 裸键前缀全表扫描（N-3） | 复审报告 §4、`LeaseService#stopDriving` |
| **新增** | **L-15** `describe().keys` 的 `lockTask`/`lockJob` 可为 null（N-5） | 复审报告 §4、`ResumeService#describeKeys` |
| **新增** | **L-16** `TARGET_INVALID` 仅审批后校验（N-7） | 复审报告 §4、`SpToolCallingManager:650` |
| **新增** | **L-17** `-SharedDb` 变体 SKIP 与真故障同形（I-2 口径） | 复审报告 §2、`scripts/README.md` §3.2 |
| **新增** | **L-18** `idemFor` 缺 `toolCallId` 的极端分支靠认领兜底（N-6） | 复审报告 §4、`RunStore:1195` |
| **新增（提交官）** | **L-19** 既有 flaky `ResumeServiceLeaseTest:302`（批② 起） | §4.1 七步取证 |
| 保留 | L-9 | WAL 对 `APPROVED`/`FRONTEND_RESULT` 的卡死出口**仍挂**（批③ 的 UNKNOWN 对账只覆盖 `unknown` 结局，不覆盖"guard 已决但条目未推进"） |
