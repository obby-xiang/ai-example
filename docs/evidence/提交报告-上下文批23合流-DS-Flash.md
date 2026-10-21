# 提交报告 · 上下文统计与压缩专项批②③（L2 滑窗 + 递归摘要 + L3 硬截断 + 熔断）合流（提交官 · DS-Flash · 2026-10-21）

## 0 自证行（DC-19 · wire 实测，非照抄卡片）

- 提交官 agentId = `agent-1374`；wire = `<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_ced79706-4f65-44a8-a80c-eb2eb2542928/agents/agent-1374/wire.jsonl`
- 取数方式：`ls` 该 session 的 `agents/` 目录，**最新新建目录** = `agent-1374`（17:35；其余 `agent-1360`~`agent-1373` 与 `main` 均早于本棒）；再 `grep '"profile.bind"'` 命中单行，原文首部（逐字）：

```json
{"type":"profile.bind","agentId":"agent-1374","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high",...}
```

- 实测三字段：`modelAlias` = **`DeepSeek/deepseek-flash`**、`profileName` = **`coder`**、`thinkingEffort` = **`high`**
- 授权来源 = **用户 2026-10-21 指令「前序的任务直接实施」**；施工官报告结论 = 门禁全绿（后端 975 / 前端 545 / typecheck 0 / build ✓ / check-docs 6/6 / 脱敏 0 命中）。
- 职责边界 = **纯合流 + 文书**；**零生产代码改动**（合并笔为施工分支原样合入，本棒两笔只含看板 + 本报告）。`?? data/` 未动未入库；未碰并行 worktree `wt-table-ai` / `wt-model-config-b3` / `wt-e2e-ctx`；E2E 服务（18330/18399）未停。

## 1 开工态核实

| 项 | 实测 |
| --- | --- |
| 主仓 | `<REPO_ROOT>` |
| `git fetch --all --prune` 后 `main` / `origin/main` | **均为 `4222c5f`**（`4222c5fde002d2cfeb3dc1b14659c439163f9613`）⇒ 无落后、无需 `pull --ff-only` |
| 施工 worktree | `../wt-ctx-b23` @ 分支 `feature/ctx-b23`，HEAD = `a5539c2`（未 commit） |
| 改动集核对 | `git status --porcelain` = **13 M + 14 ??**，与施工报告 §6 文件清单**一一对应，无多出文件** |
| worktree 并行面 | `wt-e2e-ctx` / `wt-table-ai` / `wt-model-config-b3` 均在列表内，**零操作** |

## 2 合流对象与结果

| 项 | 值 |
| --- | --- |
| 分支提交 | **`fe12250`**（`fe1225084a8358fe1ffe232426cb40db756c93b2`）= `feat(ctx): 上下文批②③ L2滑窗+递归摘要+L3硬截断+熔断`，**27 文件 / +2043 / −80** |
| 落笔方式 | 按施工报告清单**逐条显式路径 `git add`**（27 条，**未用** `-A` / `.`）；staged 清单与清单逐项对齐，暂存后工作区零残留 |
| merge-base | `a5539c2` |
| 合流方式 | `git merge --no-ff feature/ctx-b23 -m "merge: 上下文统计与压缩批②③合流"` ⇒ **合并笔 `9176446`**（`917644676c0b5dcd26ed771de0790eb69b4be22e`） |
| 合流规模 | **27 文件，+2043 / −80**（14 新建 / 13 改） |
| 冲突处理 | **零冲突、零强解**（`application.yml` 仅注释段的预判冲突面实际未触发；`AiProperties` / `ContextCompactor` 等均自动合） |

### 2.1 合流内容（批②③ 交付面）

- **新增主代码 6**：`ConversationTurns`（Turn 切分，裁剪单位）、`RollingSummarizer`（L2 按 Turn 滑窗 + 递归摘要）、`ContextSummaryClient`（摘要模型客户端：超时 + 主模型回落）、`HardTrim`（L3 按 Turn 硬截断）、`CompactionFuse`（三态熔断 + 冷却半开）、`ContextOverflow`（上游越窗识别 ⇒ `CONTEXT_OVERFLOW`）。
- **改主代码 8**：`ContextCompactor`（逐级升格 / P7 挂起期只 L1 / 原子回滚 / 熔断接线 / 帧载荷写实）、`ContextState`（`openUntilMs`）、`ContextConfig`（摘要客户端 + 4 参 compactor）、`ContextBreakdownService`、`ResilientChatService`（越窗归类）、`StreamViolationException`（新码）、`AiProperties`、`application.yml`（仅注释）。
- **测试**：新增 7 类（`ConversationTurnsTest` 4 / `RollingSummarizerTest` 7 / `HardTrimTest` 4 / `CompactionFuseTest` 5 / `CompactionPairingTest` 4 / `ContextSummaryClientTest` 3 / `ContextOverflowTest` 2 = 29 例）+ 扩 2 类（`ContextCompactorTest` 11→17、`ContextFrameWiringTest` 6→7 = +7 例）⇒ **净增 36**。
- **文档**：`technical-design.md`（L1 段改三级谱系）、`test-cases.md`（CTX-09..CTX-15）、`补档-NUMSPEC-清单总账.md`（"批③ 已消费"）、施工报告。
- **帧契约零变更**：`context` 键集不变（`{level, before, after, turns, lostTokens, reason, est, savedTokens, clearedPairs}`），`turns` / `lostTokens` 由"恒 0"改如实值；前端**零改动**（提示条渲染归批④）。

## 3 合流后门禁实测（提交官本人，主仓）

| 门禁 | 命令 | 实测 |
| --- | --- | --- |
| 后端全量 | `cd backend && <MAVEN_HOME>/bin/mvn -o test`（Maven 3.9.16 离线，**非 `-q`**） | **exit 0 / BUILD SUCCESS**；`Tests run: 1079, Failures: 0, Errors: 0, Skipped: 0`；`Total time: 02:54 min` |
| 后端计数（XML 求和） | `backend/target/surefire-reports/TEST-*.xml` 逐文件求和 | **161 文件 / 1079 / 0F / 0E / 0S** —— 与 Maven 汇总行**逐值相同** |
| 前端全量 | `cd frontend && node node_modules/vitest/vitest.mjs run` | **30 文件 / 559/559 passed**（`exit 0`，首跑即绿；本批前端零改动 ⇒ 与 main 持平） |
| 类型检查 | `node node_modules/vue-tsc/bin/vue-tsc.js --noEmit` | **exit 0，0 行输出** |
| 生产构建 | `TMP=<TMP> TEMP=<TMP> node node_modules/vite/bin/vite.js build` | **exit 0**，`✓ built in 52.94s` |
| 文档门禁 | `node scripts/ci/check-docs.mjs` | **6/6 断言绿**（`git ls-files` 1509 路径 / 实扫 1506 / 豁免 3；规则自检连写组 5/5、分隔组 24/24、负对照 11 全不误伤） |
| 脱敏扫描 | `scripts/githooks/scan-lib.sh` 三规则（SECRET / PATH / USER） | **0 命中**（见 §4） |

**计数与派单预判的吻合度**：预判后端 "≥975+104=1079 左右" ⇒ 实测 **1079**（main 1043 + 36）；预判前端 559/559 ⇒ 实测 **559/559 · 30 文件**。逐值吻合，无偏差需解释。

**flake 面**：`ResumeServiceLeaseTest`（既有 CI 面 flake）本次 **11/11 绿**，未触发单例重跑；其余全量 0F/0E/0S。

## 4 脱敏终扫（DC-08 · 三规则）

| 面 | 扫描对象 | 命中 |
| --- | --- | --- |
| ① | 合流 diff 新增行（`git diff 4222c5f HEAD` 的 `+` 行，经 `SCAN_DIFF_AWK`；**实扫 2326 行**） | **0** |
| ② | 新增文件全文（`--diff-filter=A` 14 件，逐件 `scan_engine_blob`） | **0** |
| ③ | 暂存区（`pre-commit` 阻断式，`core.hooksPath=scripts/githooks` 已启用） | **0**（分支笔 `fe12250` 由钩子实测放行；本笔提交同机制；**未**用 `AI_SECRETS_ALLOW` / `--no-verify`） |

- **正对照已验证管线有效**：向同一管道灌入一段「盘符 + 用户目录」形态的样例路径 ⇒ 如实报 `PATH` 命中（证明非空管道假绿）。
- PATH 规则专项核对（派单点名「证据件里不得出现本机绝对路径字面」）：施工报告 §0 工地路径已为 `<repo-parent>/wt-ctx-b23` 占位；`ContextSummaryClientTest` 样例路径已占位 ⇒ **均 0 命中**，无需二次替换。
- **自我更正留痕**：本报告初稿 §4 正对照句里照抄了盘符样例字面 ⇒ 首扫 **1 命中**（本文件 `:69`，规则 PATH）；改为「盘符 + 用户目录」文字描述后**复扫 0 命中**（仅措辞改动，语义与数字未动）。

## 5 自决要点摘录（施工报告 §4 的 13 条，取 6 条与本批边界相关）

| # | 要点 |
| --- | --- |
| 1 | **不做批② 重复实现**：L1 / 归档 / 原子落库已随批① 合流（`a5539c2` 上在运行），本棒只补批③ 谱系 |
| 2 | **L1 的 `lostTokens` 保持 0**：口径 = "丢掉的轮次里的 token"；L1 不折叠轮次，省下的量走 `savedTokens`；L2/L3 折叠/丢弃整轮才计 `lostTokens`（`ResumeContextCompactionTest` 既有断言只升不降） |
| 3 | **L2/L3 失败全原子**：摘要失败时不保留 L1 的清除效果（历史逐字不变），防"模型看到的"与"记忆里的"两套历史分叉 |
| 4 | **挂起期（P7）判定用 `protectedToolCallIds` 非空**：不给 `Request` 加字段（未决 HITL 条目正是"外部未决项"的定义来源） |
| 5 | **摘要客户端直连 `ChatModel`**：不用 `ProviderRuntime#chatClient`（带 `MessageChatMemoryAdvisor`，会把摘要提示词写进主会话记忆）；`summary-model` 空 = 主模型 |
| 6 | **熔断只被摘要尝试驱动**：L1 成功不清零（否则"摘要坏 + L1 一直可用"计数永不归零）；冷却到点先 `half_open`，成功清零、失败重开窗（不升指数） |

**双实例偏差（DC-11，施工报告 §4-11）**：fuse 的"读 → 判 → 写回"非原子，两实例可能各放一次半开摘要尝试；后果 = 多重试一次摘要（不丢历史、不越窗、不双写快照），已用单 JVM 双实例用例钉住 ⇒ **已按施工报告原措辞登记挂账 L-20**（本棒不裁、不修）。

## 6 看板落点

| 文件 | 落点 | 内容 |
| --- | --- | --- |
| `docs/任务看板.md` | `:17`（批·上下文统计与压缩专项行） | 行内「L2/L3/递归摘要/熔断仍 📋 待发令」→ **批②③ ✅ 已合流**（合并笔 `9176446`、分支 `fe12250`、基点 `a5539c2`、零冲突）+ 批②③ 门禁数字 + 证据件两件 + 自决摘 5 条；原批① 门禁块改标「批① 门禁」保留不删 |
| 同上 | `:424`（挂账小节，L-19 之后 / I-1 之前） | **新增 L-20**「摘要熔断跨实例无锁（DC-11）」，措辞照施工报告 §4-11 / §7，待谁 = 指挥官 |
| 同上 | `:436`（现状速览） | 挂账小节 **80 → 81** 项；追加「10-21 补记第十批 2 条」（批②③ 合流 + L-20 挂账）；批次总量与状态分布**不变**（仍 66 / ✅49 / 📋12，行状态本就 ✅） |
| `docs/决策看板.md` | **未动** | 本笔无新裁决（G1 未裁不动；DC-11 属已登记可接受偏差，不构成新裁决） |

## 7 文件清单（本笔提交面）

显式路径 add（**严禁** `git add -A` / `git add .`）：

1. `docs/evidence/提交报告-上下文批23合流-DS-Flash.md` —— 本文件
2. `docs/任务看板.md` —— 批②③ 状态迁移 + 门禁数字 + 合流锚 `9176446`；挂账新增 **L-20**；速览 80 → 81 + 第十批补记

合并笔 `9176446` 的 27 文件清单见 `git show --stat 9176446`（14 新建：6 主代码 + 7 测试 + 施工报告；13 改：7 主代码 + `application.yml` + 2 测试 + 3 文档）。

## 8 提交、推送、清理（结果台账留痕）

- 提交：`docs(evidence+board): 上下文批②③合流证据与看板销记`
- 推送：`git push origin main`；**push 后不 watch CI**（纪律：CI 不同步盯）
- 清理：`git worktree remove ../wt-ctx-b23` + `git branch -d feature/ctx-b23`
- 台账：`<SHARED>/services.state` **只追加一行**
- 净态复核：`git status --porcelain` 仅 `?? data/`；`git rev-parse HEAD origin/main` 一致

## 9 红线条目核对

| 红线 | 执行 |
| --- | --- |
| 改任何代码 | **未改**（合并笔为施工分支原样合入；本棒两笔全为 docs） |
| 门禁红即停 | 合流后六门禁**全绿**（含脱敏 0 命中），无停手点 |
| 语义冲突即停 | **零冲突**，未触发判定 |
| 禁 `git add -A` / `git add .` | 遵守（分支笔 27 条显式路径、本笔 2 条显式路径） |
| 不改 `?? data/` | 遵守（全程未暂存、未入库） |
| 不碰并行 worktree | 遵守（`wt-table-ai` / `wt-model-config-b3` / `wt-e2e-ctx` 零操作） |
| 不停 E2E 服务 | 遵守（18330 / 18399 未触碰） |
