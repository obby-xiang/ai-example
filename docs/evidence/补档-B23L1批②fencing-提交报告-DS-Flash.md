# 补档 · B23L1 批② fencing 提交报告（提交官 · DS-Flash · 2026-10-21）

## 0 自证行（wire 实测，非照抄卡片）

- 提交官 agentId = `agent-1318`；wire = `<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_604b3bc4-1812-4fe3-b259-6c0a97160f45/agents/agent-1318/wire.jsonl`
- 取数方式：首行为 `{"type":"metadata",...}`（非 bind 行），`grep -n 'profile.bind'` 命中**第 5 行**，原文首部（逐字）：

```json
{"type":"profile.bind","agentId":"agent-1318","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high",...}
```

- 实测三字段：`modelAlias` = **`DeepSeek/deepseek-flash`**、`profileName` = **`coder`**、`thinkingEffort` = **`high`**
- 仓根 = `<REPO_ROOT>`（唯一可操作仓）；职责边界 = 机械验收 + commit + push + CI 跟进；**零代码改动**（除看板/决策看板/设计稿口径 + 五件证据文档），零服务启停（harness 由实机核验方跑，提交官只读其证据），`?? data/` 未动未入库。

## 1 机械验收逐项

### 1.1 `git status --porcelain`（验收前基线）

预期 = 批② 全部改动 + 新增 + 既有 `?? data/`；实测 **11 `M` + 19 `??`**（18 个本批新件 + `data/`），无意外文件：

- 后端生产 **7 改** = `ai/config/AiProperties.java`、`ai/gate/ConfirmGate.java`、`ai/run/{PendingToolCall,ResumeService,RunSnapshot,RunStore}.java`、`ai/web/AiController.java`
- 后端生产 **2 新** = `ai/run/InstanceId.java`、`ai/run/LeaseService.java`
- 后端测试 **2 改** = `ai/conformance/InMemoryRunStore.java`、`ai/web/AiControllerHealthSseDeliveryTest.java`；测试 **16 新**（14 用例类 + 2 替身）= `ai/gate/{ConfirmGateDecisionAtomicTest,ConfirmGateLeaseTest}.java` + `ai/run/{FakeLeaseRedis,FakeRenewScheduler,LeaseServiceTest,LeaseServiceTriStateTest,ResumeServiceLeaseTest,RunSnapshotEpochCompatTest,RunStoreClaimEpochTest,RunStoreClaimFenceTest,RunStoreDecisionGuardTest,RunStoreFencedWriteTest,RunStoreRequestEmittedAtomicTest,RunStoreTouchTest,RunStoreWalConvergeTest}.java` + `ai/web/AiControllerSuspendedTouchTest.java`
- 脚本 **2 改** = `scripts/b23l1-dual-instance.ps1`、`scripts/README.md`
- **前端零改动**（`git status` 无 `frontend/` 条目）
- `?? data/` = 历史遗留，**未动、未入库**（`git diff --cached --name-only | grep -c '^data/'` = 0）

第一笔入库 **29 文件 = 11 改 + 18 新建**，`+5990 / −149`。

### 1.2 计数口径订正（**以实测为准，显著标注**）

| 项 | 卡片登记 | 实测 | 差 | 处置 |
| --- | --- | --- | --- | --- |
| 后端用例（mvn 汇总） | 702 | **702**（`Tests run: 702, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`） | 0 | 一致 |
| 后端用例（surefire 求和） | 692 | **702**（`TEST-*.xml` 的 `tests` 求和 与 `<testcase>` 元素计数**都是 702**，112 个 suite） | **+10** | **不可复现** ⇒ 以 702 为准；差 10 无法由"排除某包/某类"或"`@ParameterizedTest` 展开"复现，判为施工收口阶段的自报口径偏差。本批所有门禁口径（看板/决策看板/证据件）**一律 702/702** |
| 后端测试类 | — | **112 suite**（批① 98 + 本批 15 个用例类 − 1 个既有类无独立报告） | — | 按实测写 |
| 新增测试文件 | 15 个新类 | **16 个新文件**（14 用例类 + 2 替身） | +1 | 按实测写；用例条数 = 75 |
| 施工链各段自报 | 657 → 682 → 687 → 689 → 692 | 保留原样（各段时点自报） | — | 施工报告 §4 保留、§5 标注"不据此推导最终门禁" |

> 结论：**门禁以 702/702 为准**；卡片 692 与"15 个新类"两处与实测不符，已在施工报告 §5 与 §1.3 显著标注。

### 1.3 脱敏终扫（DC-08 · 三规则 SECRET / PATH / USER）

用仓内 `scripts/githooks/scan-lib.sh` 同一套规则，分三个面扫：

| 轮次 | 扫描面 | 命中 | 处置 |
| --- | --- | --- | --- |
| 首扫（写证据件前） | ① 已跟踪全量 diff（`git diff --unified=0` 的 `+` 行）② 未跟踪新件逐文件全文（排除 `data/`） | **0 / 0** | 无需占位符替换（本批全程桩 + placeholder，无真 key、无本机绝对路径） |
| 复扫（五件证据落地后） | ①② | **0** | 证据件内一律用 `<KIMI_HOME>` / `<MAVEN_HOME>` / `<REPO_ROOT>` 占位；harness 证据摘录只取帧字段，不抄实例标识串 |
| 每笔提交前 | ③ 暂存区 + `git diff --cached --check` | **0 / 0** | 三笔均过 `pre-commit`（`✓ 暂存区无命中`） |

未使用 `AI_SECRETS_ALLOW`、未 `--no-verify`。

### 1.4 文档门禁 check-docs（三个时点）

`node scripts/ci/check-docs.mjs` 均 **6/6 断言绿**（exit 0）：

1. 基线（证据件未落、看板未改）：6/6（命名纪律扫 1346 路径）
2. 看板 + 决策看板 + 设计稿改完（第二笔前）：6/6（扫 **1364** 路径）
3. 五件证据落地（第三笔前）：6/6

### 1.5 测试门禁复跑（提交官本人实测）

| 门禁 | 命令 | 实测 |
| --- | --- | --- |
| 后端全量 | `<MAVEN_HOME>/bin/mvn -q -o test` 与 `<MAVEN_HOME>/bin/mvn -o test`（Maven 3.9.16 离线，两次） | **exit 0 / BUILD SUCCESS**；mvn 汇总 `Tests run: 702, Failures: 0, Errors: 0, Skipped: 0`；surefire 求和 **702**，**112 suite**，0F/0E/0S |
| 前端全量 | `cd frontend && node node_modules/vitest/vitest.mjs run`（**未加** `--reporter`） | **512/512 通过 · 26 文件**（`Test Files 26 passed (26)` / `Tests 512 passed (512)`，Duration 15.48s；前端零改动 ⇒ 与批① 同数） |
| 前端类型检查 | `node node_modules/vue-tsc/bin/vue-tsc.js --noEmit` | **exit 0** |
| 前端构建 | `node scripts/build.mjs`（`package.json` 的 `build` = `vue-tsc --noEmit && node scripts/build.mjs`；`--noEmit` 步已单列） | **✓ built in 46.63s**（exit 0，`dist/` 完整） |
| 文档门禁 | `node scripts/ci/check-docs.mjs` | **6/6**（见 §1.4） |
| 脱敏 | §1.3 | **0 命中** |
| E2E（29 用例） | **不本地跑**（需起服务；本批铁律零服务启停） | 由 CI 的 `e2e` job 执行，随本次推送盯绿（见 §3） |

### 1.6 看板核对

- `docs/任务看板.md:15` = 新增「批·B23L1 批② fencing」✅ 行；哈希 = 主体笔 **`cd18b37`**（主体笔先推、看板笔后推，故哈希可**直接回填**，不再单立锚定笔）；门禁 = 后端 702/702 · 112 suite（两口径一致）、前端 512/512 · 26 文件、typecheck 0、`build` ✓、check-docs 6/6、脱敏 0、harness 6/6 + `lease-scripts` PASS；依据 = 设计稿 §2.2/§5-2/§7 批② + 五件补档。
- `docs/任务看板.md:16` = 新增「批·B23L1 批③ 资源治理」📋 待开工行（依据 = 设计稿 §2.3/§7 批③ + 队列四件授权）。
- `docs/任务看板.md:391`（多实例确认误路由）与 `:404`（L0 M-2）**销记留痕、行不删**：标 ✅ 已由批② 承接闭环，依据 = 等待方持租 + 决策 guard + `ai:req:` `HSETNX`；harness `suspend-decision` / `instance-kill` 实证。
- 挂账小节（`:405`–`:413`）：**L-4/L-6/L-8 原样保留**（`:405/:407/:409`）、**L-5 销记**（`:406`）、**L-7 销记**（`:408`，残余转 L-11）、**新增 L-9/L-10/L-11/L-12**（`:410`–`:413`），逐条单列带依据。
- `:416` 速览行同步：批次 / 笔行 **61 → 63**、状态分布 ✅44 → **✅45** / 📋12 → **📋13**、挂账小节 **57 → 66**，并补 10-21 补记说明。
- `docs/决策看板.md:15/:16` = 批② 两条裁决行（施工九条 + 口径与纪律四条），每行带依据件。
- 设计稿 §5-3（`:65`）：E2E「31 用例」→ **29**，注明基数来源 `scripts/verify-e2e.ps1`（复核发现的口径漂移，已裁决采纳）。

## 2 提交与推送（三笔，逐笔 push）

| # | 短哈希 | message | 内容 | push |
| --- | --- | --- | --- | --- |
| 1 | **`cd18b37`** | `feat(ai-lease): B23L1批② 续跑权 fencing 租约与确认门改造` | 29 文件（生产 2 新 + 7 改、测试 16 新 + 2 改、脚本 2 改） | `43d24ad..cd18b37 main -> main` ✓ |
| 2 | **`fd150d6`** | `docs(board): B23L1批② fencing 入库 + 批③ 立项登记 + E2E 口径订正` | 3 文件（任务看板 19 行、决策看板 4 行、设计稿 3 行） | `cd18b37..fd150d6 main -> main` ✓ |
| 3 | **本件**（push 后回执） | `docs(evidence): 补档 B23L1 批② fencing 五件入库` | `docs/evidence/` 五件 | 见 §3 |

三笔均过 `pre-commit` 阻断式扫描（`✓ [pre-commit] 敏感信息扫描通过（暂存区无命中）`）；origin 保持 **HTTPS**（`https://github.com/<GITHUB_OWNER>/ai-example.git`），未改远端配置。

## 3 CI 确认

| 笔 | run id | 触发方式 | 撰写时状态 |
| --- | --- | --- | --- |
| `cd18b37` | **39529630440** | push→main | `in_progress`（五 job 全量：后端 / 前端 / e2e / docs / scan-history） |
| `fd150d6` | **39529754359** | push→main | `queued` |
| 本件（第三笔） | 见提交官交付回执 | push→main | 推送后同 workflow 触发 |

轮询方式 = `gh run list --limit N --json …` + `gh api "repos/<GITHUB_OWNER>/ai-example/actions/runs/<id>"`（Git Bash 下端点**无前导斜杠**）。**结论一律以交付回执为准**：本文件在被推送进库时，它自己的 run 尚未产生；前两笔的结论在交付回执中记录（截稿时两笔均未到 conclusion）。

## 4 异常与留痕

1. **计数不符（唯一实质差异）**：surefire 求和实测 **702**，卡片登记 **692**（差 +10），已按"以实测为准 + 显著标注"处置（§1.2 / 施工报告 §5）。**未**因此改任何断言或跳过用例。
2. **新增测试文件 16 个**（卡片写"15 个新类"）：14 用例类 + 2 替身，按实测写。
3. **`?? data/`**：三笔全程未入索引（终态 `git status` 只剩 `?? data/`）；`data/` 未入 `.gitignore` 属既有挂账，提交官不处置（复审 M9 亦裁决不采纳改忽略规则）。
4. **harness 未由提交官运行**：`PASS=6/6` 与 `lease-scripts` PASS 取自实机核验方证据（`backend/target/b23l1-artifacts-final{,2}`，target 不入库），提交官只核对帧与摘要一致性。
5. **E2E 29 用例不本地跑**：由 CI `e2e` job 执行（本机铁律：零服务启停）。
6. **挂账异动（L-5/L-7 销记依据）**：
   - L-5：P50/P95 **两样本**（94ms / 107ms）；样本少，**不据此给性能结论**。
   - L-7：harness 四项 + 三轮修复全收（订阅就绪哨兵 / `taskkill /T /F` + `StartTime` / 端口与 Redis 预检 / 绝对路径 ArtifactDir）；残余 `GetJson` Dispose 与 `Stop-Children` 全量 `StartTime` 转 **L-11**。

## 5 挂账异动清单（本批）

| 动作 | 条目 | 依据 |
| --- | --- | --- |
| **销记** | **L-5**（P95 随 harness 运行批取证） | 两样本 94ms / 107ms（实机核验 §1.1） |
| **销记** | **L-7**（harness 就绪信号 / 信号清理 / 事件注销 / HttpClient） | 四项 + 三轮修复全收（实机核验 §0） |
| 保留 | L-4 / L-6 / L-8 | 原样不动（批① 挂账） |
| **新增** | **L-9** WAL 对 `APPROVED`/`FRONTEND_RESULT` 只剩卡死出口 | 复核确认 §3-R1、施工报告 §6.1 S-3 |
| **新增** | **L-10** claim fence 复核 TOCTOU 未合成单脚本 | 复核确认 §3-R7 |
| **新增** | **L-11** scripts 侧 `GetJson` Dispose / `Stop-Children` StartTime | 复核确认 §3-R8 |
| **新增** | **L-12** harness 跨实例 H2 隔离边界（接管方 `RESOURCE_NOT_FOUND`） | 实机核验 §4 |
| **销记（历史行留痕）** | 多实例确认误路由 / L0 M-2 | 批② 承接闭环（看板 `:391` / `:404`） |
