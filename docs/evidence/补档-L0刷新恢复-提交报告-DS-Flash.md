# 补档 · L0 刷新恢复漏洞修复 提交报告（提交官 · DS-Flash · 2026-10-21）

## 0 自证行（wire 实测，非照抄卡片）

`grep 'profile.bind' <KIMI_HOME>/sessions/<SESSION_ID>/agents/agent-1298/wire.jsonl` 实测单行：

```
{"type":"profile.bind","agentId":"agent-1298","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high", ...}
```

- 提交官 agentId = `agent-1298`；wire = `<KIMI_HOME>/sessions/<SESSION_ID>/agents/agent-1298/wire.jsonl`（首行为 metadata，故用 `grep` 定位；`grep -c` = 7 条 `profile.bind`，取首条与本次派单相符）
- 实测 `modelAlias` = **`DeepSeek/deepseek-flash`**、`profileName` = **`coder`**、`thinkingEffort` = **`high`**
- 仓根 = `<REPO_ROOT>`（唯一可操作仓）
- 职责边界 = 机械验收 + 四笔 commit + 逐笔 push + CI 跟跑至 conclusion；**零代码内容改动**（仅看板 L0 行状态/计数、`docs/research/README.md` 索引、队列四行、决策看板一行、本报告），零服务启停，`?? data/` 未动未入库

## 1 机械验收逐项

### 1.1 `git status --porcelain`（验收前基线）

预期 = L0 改动 15 M + 新件若干 + 既有 `?? data/`。实测 **完全吻合**：

- 15 M = 后端 5（`ai/gate/ConfirmGate.java`、`ai/run/PendingToolCall.java`、`ai/run/RunStore.java`、`ai/web/AiController.java`、`ai/web/AiControllerRunsCurrentTest.java`）+ 前端 6（`src/stores/ai.ts`、`src/types/ai.ts`、`src/utils/parts.ts`、`tests/ai-panel-parts-render.spec.ts`、`tests/frontend-tool-result-terminal.spec.ts`、`tests/restore-reconcile.spec.ts`）+ docs 4（`03-技术方案文档-v2.1.md`、`research/README.md`、`任务看板.md`、`决策看板.md`）
- 8 新 = 后端测试 2（`ConfirmGateRequestEmittedTest` / `ConfirmGateResolvedSkipTest`）+ 前端测试 1（`restore-request-emitted.spec.ts`）+ 证据 3（`补档-L0刷新恢复-{施工报告,复审报告-DS-Flash,复核确认-DS-Flash}.md`）+ 调研 1（`research/调研-批量HITL呈现与推进-业界惯例-2026-10-20.md`）
- `?? data/` = 历史遗留，**未动、未入库**（四笔 staged 中 `^data/` 计数 = 0）
- 无脚本 / 配置面意外改动（`scripts/`、`pom.xml`、`package.json`、`.github/` 零改动）

### 1.2 脱敏终扫（密钥/令牌 + 本机路径）

三条口径分别扫描，**均 0 命中**（未使用 `AI_SECRETS_ALLOW`，未 `--no-verify`；pre-commit 钩子两笔均放行且打印「敏感信息扫描通过」）：

| 扫描面 | 模式 | 命中 |
|---|---|---|
| 全量工作树 diff（`git diff`，619 行） | 密钥族（`sk-` 前缀 / PEM 私钥 / `api_key` 与 `token`·`secret`·`password` 赋值 / `AKIA` / `gh[pousr]_`）+ 本机路径族（盘符用户目录 / `/home/<user>/`） | **0** |
| 未跟踪新件逐文件（7 件，排除 `data/`） | 同上 | **0** |
| 各笔提交前暂存区（`git diff --cached`，逐笔终扫） | 同上 + `git diff --cached --check` | **0**（check 0） |
| 仓外待入 docs 四件（逐件预扫） | 同上 | **1**（i18n 件自证行用户目录）→ 改写为 `<KIMI_HOME>/sessions/<SESSION_ID>`；模型配置件目标仓根盘符路径 → 「本仓」；复扫 **0** |

真实密钥口径一律 `***`，本批无任何真实密钥落盘。

### 1.3 文档门禁 check-docs（复跑）

`node scripts/ci/check-docs.mjs` 提交前复跑 = **6/6 断言绿**（①导航无盘符绝对路径 ②栈版本一致 ③引用存在 ④端口表一致 ⑤命名纪律全量 ⑥`.java` 探针不参与构建；`git ls-files` 1315 → 1322）。

### 1.4 测试门禁复跑（提交官实跑）

| 门禁 | 命令 | 实测 |
|---|---|---|
| 前端全量 | `cd frontend && node node_modules/vitest/vitest.mjs run`（**无 `--reporter=basic`**） | **512 / 512 · 26 文件**（16.66s，exit 0） |
| 后端全量 | `<MAVEN_HOME>/mvn.cmd -o -q test`（`backend/`） | **586 / 586 · 93 类 · 0F / 0E / 0S**（exit 0；surefire XML 汇总，最新 mtime 00:50） |

与施工自报（586/586 · 93、512/512 · 26、typecheck 0、`build` ✓、check-docs 6/6）一致，与复审/复核口径一致。

## 2 四笔入库（逐笔 push，origin 保持 HTTPS）

| # | commit | 主题 | 规模 | push | CI run | conclusion |
|---|---|---|---|---|---|---|
| 1 | `503bd4f` | `fix(ai-hitl): L0 刷新恢复只重建请求帧已发的待决卡` | 22 文件 · +1635 / −26 | `42f80af..503bd4f` | **39461007217** | **success**（5/5 job） |
| 2 | `19e1bb9` | `docs(board): L0 批入库锚定 503bd4f` | 1 文件 · +1 / −1 | `503bd4f..19e1bb9` | **39461790266** | **success**（5/5 job） |
| 3 | `baade8e` | `docs(research): 批量HITL后续四件调研与设计稿入库 + 队列登记` | 7 文件 · +676 / −5 | `19e1bb9..baade8e` | **39462713002** | **success**（5/5 job） |
| 4 | 本笔 | `docs(evidence): L0 提交报告入库` | 1 文件（本报告） | `baade8e..` | push 后跟跑；绿则闭环 | 见交付汇总 |

- 五 job = 文档一致性门禁（check-docs）｜阻断 / 全历史脱敏扫描（报告制）/ 后端编译与单测 / 前端类型检查与构建 / E2E 全流程（29 用例）
- 第一笔内容 = 15 M + 7 新（码 / 测试 / 文档，含 `docs/research/调研-批量HITL呈现与推进-业界惯例-2026-10-20.md` 与索引第 29 件）；message body 带裁决 A~E 要点 + 门禁 + 证据清单
- 第二笔 = 看板 L0 行「本批」→ `503bd4f`（`docs(board):` 体例同 B1 批）
- 第三笔 = 仓外四件复制入 `docs/research/`（原名保留）+ README 索引 29 → **33 件** + 任务看板 10-21 组四条 📋 待开工行 + 决策看板 10-21 追加一行（代裁三件事 + 四件立项依据）
- 看板 L0 行 = 🔧 → ✅（日期 2026-10-21、证据件三名、门禁数字、哈希位先「本批」后锚定），批次计数 ✅43→44 / 🔧1→0，第三笔再 57→61 / 📋8→12；决策看板计数 118→119 / 96→97 / 87→88

## 3 验收结论

**READY-WITH-NITS（0C / 0I / 0M / 3N，准予入库）** 的机械面复核通过：基线吻合、脱敏 0 命中、四门禁全绿（check-docs 6/6、前端 512/512、后端 586/586、CI 五 job 全绿）、四笔逐笔入库推送、`?? data/` 未动。

### 遗留（不阻塞，如实登记）

1. 复核 3N（N-1 `loadHistory` 生产分支无用例 / N-2 报告行号精度 / N-3 宿主可选无类型层标记）与挂账 M-2（`markRequestEmitted` 跨实例 lost update，归 B3）/ M-2b（升级穿越期旧挂起轮 `req` 永 null）—— 均已在 `docs/任务看板.md` 挂账小节登记，本批不修。
2. 本报告自身所在笔（第四笔）的 CI run id 由 push 后跟跑确认；若红则另开修复笔，不改写本报告结论。
