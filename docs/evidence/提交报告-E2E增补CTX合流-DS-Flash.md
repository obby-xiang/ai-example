# 提交报告 · E2E 增补批（CTX 部分：E2E-CTX-1..4 + 三形态 usage 桩）合流（提交官 · DS-Flash · 2026-10-21）

## 0 自证行（DC-19 · wire 实测，非照抄卡片）

- 提交官 agentId = `agent-1378`；wire = `<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_ced79706-4f65-44a8-a80c-eb2eb2542928/agents/agent-1378/wire.jsonl`
- 取数方式：`ls -lat` 该 session 的 `agents/` 目录，**最新新建两组** = `agent-1377`（17:42）/ `agent-1378`（17:42）⇒ 逐个 `grep '"profile.bind"'`，`agent-1377` 的 `turn.prompt` 为「防护批②③ 施工官」（非本棒），`agent-1378` 的 `turn.prompt` 首句为「你是提交官，执行「E2E 增补批·CTX 部分」合流 main 全流程」⇒ **本棒 = `agent-1378`**；其 `profile.bind` 命中单行，原文首部（逐字）：

```json
{"type":"profile.bind","agentId":"agent-1378","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high",...}
```

- 实测三字段：`modelAlias` = **`DeepSeek/deepseek-flash`**、`profileName` = **`coder`**、`thinkingEffort` = **`high`**
- 授权来源 = **用户 2026-10-21 指令「前序的任务直接实施」**；主会话已组织实跑 `PASS=35 FAIL=0`（证据 `<SCRATCH>/_ctx-run/e2e-run3.log`）。
- 职责边界 = **纯合流 + 文书**；生产代码**零改动**（后端 Java / 前端源码 / SSE 帧契约均未碰；本棒改动面 = 施工分支原样合入 + 本报告 + 任务看板两处）。

## 1 开工态核实

| 项 | 实测 |
| --- | --- |
| 主仓 | `<REPO_ROOT>` |
| `git fetch` 后 `main` / `origin/main` | **均为 `6bde8bd`**（`6bde8bd4025e82e6da7715800d2378413b95584d`）⇒ ≥ 派单下限，无落后、未 `pull` |
| 主仓工作树 | `git status --porcelain` = 仅 `?? data/`（历史遗留未跟踪产物，全程零触碰） |
| 施工 worktree | `../wt-e2e-ctx` @ 分支 `feature/e2e-ctx`，HEAD = `a5539c2`（**未 commit**，与派单一致） |
| 改动集核对 | `git status --porcelain` = **8 M + 1 ??**，与施工报告 §6 文件清单（`scripts/` 3 + `.github/workflows/ci.yml` + `docs/` 4 + 新报告 1）**一一对应，无多出文件** |
| worktree 并行面 | `wt-table-ai`（`feature/table-ai` @ `a5539c2`）/ `wt-model-config-b3`（`feature/model-config-b3` @ `64c1caf`）/ `wt-guardrails-b23`（`feature/guardrails-b23` @ `6bde8bd`）均在列表内，**零操作** |

## 2 合流对象与结果

| 项 | 值 |
| --- | --- |
| 分支提交 | **`7a98513`**（`7a98513e6e1bd81247ca32da2ec13845c9dcf8a0`）= `test(e2e): E2E-CTX-1..4 + 三形态usage桩（31→35）`，**9 文件 / +706 / −39** |
| 落笔方式 | 按施工报告 §6 清单**逐条显式路径 `git add`**（9 条，**未用** `-A` / `.`） |
| 合流前预处理 | 首提交被 `pre-commit` 阻断 **27 处 PATH 命中**（本机绝对路径字面，全在施工报告正文）⇒ 按 DC-08 换占位符：`<REPO_ROOT>` / `<SCRATCH>/_ctx-*` / `<MAVEN_HOME>/bin/mvn`，**语义与数字零改动**；钩子复跑 ✓ 后提交 |
| merge-base | `a5539c2` |
| 合流方式 | `git merge --no-ff feature/e2e-ctx -m "merge: E2E增补CTX（4用例+usage桩三形态）合流"` ⇒ **合并笔 `4a4baf4`**（`4a4baf414c97ebfd8542e7f9b3bef1a439974b1a`） |
| 合流规模 | **9 文件，+706 / −39**（1 新建 = 施工报告；8 改 = `scripts/` 3 + `ci.yml` 1 + `docs/` 4） |
| 冲突处理 | **1 处真冲突（琐碎）**：`docs/test-cases.md` 双方在 §5.4 AI 表尾同锚点各追加行 ⇒ 解 = **保留双方新增**（CTX-09..15 在前、E2E-CTX-1..4 在后，`git diff 6bde8bd 4a4baf4` 实测净 +4 行）；其余 8 文件自动合 |

### 2.1 冲突解 + 一处判据口径同步（唯一非"原样合入"的改动，显式登记）

- 施工分支的 `docs/test-cases.md` 里 **E2E-CTX-3 行仍写 `frames=1`**，而施工报告 §3.3 已把该判据改为「`frames` 与 E2E-CTX-1 实测同值（同构对照，基准缺失即判红）」，`scripts/verify-e2e.ps1` 亦按新判据实现。冲突解时**顺手同步该单元格**为「`frames` 与 E2E-CTX-1 实测同值（同构对照，基准缺失即判红）」。
- 依据 = 本棒实跑复核实测 `E2E-CTX-1 frames=2` / `E2E-CTX-3 frames=2`（见 §4），旧文案 `frames=1` 与产品行为相反；改动面仅该行描述文字，**不动任何用例、断言与数值**。
- 其余"非原样合入"项 = 施工报告内的路径占位化（§2 表内已记），仅替换字面。

### 2.2 合流内容（本批交付面）

- **桩侧（`scripts/ci/`）**：`stub-routes.json` 新增顶层 `usageShapes`（`last` / `repeat` / `zero-last` / `none` + `note`），成为「桩 + E2E 脚本」的唯一取数点；`stub-upstream.mjs` 新增形状解析与 `usageChunk()`、`chunk()` 可选 `usage`（不传则键不写 ⇒ 基线逐字节不变）、`textRound()` 按形状落 usage、非默认形状写 `req-NN.decision.json.usageShape`（归因面）、`selfCheck` 段 ⑧。
- **E2E 侧（`scripts/verify-e2e.ps1`）**：新增 E2E-CTX 段（`Get-CtxShapes` + 纯函数断言核 `Test-CtxCase` + 4 条用例循环 + CTX-4 第二轮与 `/health` 附加断言）；头部口径 29 → 35 并补登 R-137 两例（真实基数 31）；摘要行改「基数不写死、随用例演进」并显式列 E2E-CTX-1..4。
- **文档/CI 口径**：`scripts/README.md`（35 用例 + §1.4 两行 + §2.3b usage 形状表）、`.github/workflows/ci.yml`（job/step 名 29 → 35，**启动命令零改动**）、`docs/E2E-桩模式校准配方.md`、`docs/README.md`、`docs/test-cases.md`。
- **帧契约零变更**：SSE 16 帧原样；`done.usage` 语义不变（本批只**验证**计量面，不改计量实现）。

## 3 合流后门禁实测（提交官本人，主仓）

| 门禁 | 命令 | 实测 |
| --- | --- | --- |
| 后端全量 | `cd backend && <MAVEN_HOME>/bin/mvn -o test`（Maven 3.9.16 离线） | **exit 0 / BUILD SUCCESS**；`Tests run: 1079, Failures: 0, Errors: 0, Skipped: 0` |
| 后端计数（XML 求和） | `backend/target/surefire-reports/TEST-*.xml` 逐文件求和 | **161 文件 / 1079 / 0F / 0E / 0S** —— 与 Maven 汇总行**逐值相同**（本批零后端改动 ⇒ 与批②③ 合流后持平） |
| 前端全量 | `cd frontend && node node_modules/vitest/vitest.mjs run` | **30 文件 / 559/559 passed**（`exit 0`，首跑即绿；本批零前端改动） |
| 类型检查 | `node node_modules/vue-tsc/bin/vue-tsc.js --noEmit` | **exit 0，0 行输出** |
| 生产构建 | `TMP=<TMP> TEMP=<TMP> node node_modules/vite/bin/vite.js build` | **exit 0**，`✓ built in 1m 18s` |
| 文档门禁 | `node scripts/ci/check-docs.mjs` | **6/6 断言绿**（`git ls-files` 1511 路径 / 实扫 1508 / 豁免 3；规则自检连写组 5/5、分隔组 24/24、负对照 11 全不误伤） |
| 桩自检 | `node scripts/ci/stub-upstream.mjs --self-check` | **11/11 `[PASS]`**，末尾 `SELF-CHECK PASS`（含段 ⑧ usage 形状：`last` 逐值不变 / `repeat` 逐帧同值不累加 / `zero-last` 末帧 0/0/0 / `none` 零 usage 仍收尾 + 标记解析） |
| 脱敏扫描 | `pre-commit`（`core.hooksPath=scripts/githooks`，SECRET / PATH / USER 三规则） | **0 命中**（见 §5） |

**计数吻合度**：后端预判 1079/1079 ⇒ 实测 **1079/1079 · 161 文件**；前端预判 559/559 ⇒ 实测 **559/559 · 30 文件**。逐值吻合，无需解释偏差。

## 4 E2E 33+4 实跑复核（主会话首轮 + 本棒复核轮）

**命令（在合流后的主仓执行，服务由施工棒起、本棒零启停）**：

```
E2E_STUB_MODE=1 powershell.exe -NoProfile -ExecutionPolicy Bypass \
  -File scripts/verify-e2e.ps1 -Base http://127.0.0.1:18330 \
  -BackendLog <SCRATCH>/_ctx-run/boot.log -ArtifactDir <SCRATCH>/_ctx-run/gfc3 -EnableGf6
```

| 轮次 | 执行方 | 结果 | 说明 |
| --- | --- | --- | --- |
| 首轮 | 主会话 | **`PASS=34 FAIL=1`** | 唯一红 = `E2E-CTX-3 frames=2，期望恰 1` ⇒ 判据错、非产品错（施工报告 §3.3） |
| 判据修正后 | 主会话 | **`PASS=35 FAIL=0`** | 日志 `<SCRATCH>/_ctx-run/e2e-run3.log` |
| **合流后复核** | **本棒** | **`PASS=35 FAIL=0` · `GFSKIP=0` · `Q8 项：PASS=2 FAIL=0` · `exit 0`** | 日志 `<SCRATCH>/_ctx-run/e2e-verify-main.log`；4 条 E2E-CTX 全 PASS |

**4 条新增用例实测取样（逐字）**：

| 用例 | 实测 |
| --- | --- |
| E2E-CTX-1（`usage-shape=last`） | `usage=1200/40/1240 est=False frames=2`；端点 `usedTokens=1200` 与 `done.usage`/`done.context` 同源；分项守恒 |
| E2E-CTX-2（`usage-shape=repeat`） | `usage=4321/77/4398 est=False frames=4`（≥2 证明去重被行使过）；端点同源；分项守恒 |
| E2E-CTX-3（`usage-shape=zero-last`） | `usage=3100/50/3150 est=False frames=2`（**与 CTX-1 同值** ⇒ 同构判据按预期生效）；端点同源；分项守恒 |
| E2E-CTX-4（`usage-shape=none`） | `usage=3068/0/3068 est=True frames=0`；第二轮估算随历史增长；`/health` 的 `compactionOn` 与 `contextLimitTokens` 同源 |

**服务面**：18330 / 18399 全程 LISTENING，**未停未重启**（开工态与收尾态一致；桩进程 PID 35172、后端 PID 24460 均未触碰）。

## 5 脱敏终扫（DC-08 · 三规则）

| 面 | 扫描对象 | 命中 |
| --- | --- | --- |
| ① | 分支笔暂存区（`pre-commit` 阻断式） | **首扫 27 命中**（全为施工报告内本机绝对路径）⇒ 换占位符后 **0**；提交被钩子实测放行 |
| ② | 合并笔暂存区（`4a4baf4`） | **0**（钩子 ✓；`docs/test-cases.md` 冲突解中新增文字零路径） |
| ③ | 本笔（提交报告 + 看板） | **0**（钩子 ✓） |

- **未用** `AI_SECRETS_ALLOW=1`、**未用** `--no-verify`（三笔均走钩子）。
- 占位符口径：施工 worktree 的盘符绝对路径 → `<REPO_ROOT>`；仓外临时目录（`_ctx-harness` / `_ctx-run` / `_b1-e2e-artifacts-r3`）→ `<SCRATCH>/…`；Maven 安装全路径 → `<MAVEN_HOME>/bin/mvn`。
- 正对照：钩子在首提交时如实报出 27 处 `PATH` 命中（证明非空管道假绿）。

## 6 判据更正摘要（施工报告 §3.3，本批唯一"红→绿"来源）

| 项 | 旧（施工初版） | 新（裁决后） | 理由 |
| --- | --- | --- | --- |
| E2E-CTX-3 的 `frames` | 写死 `=1` | **运行期取 E2E-CTX-1 实测值（同构对照）**，基准缺失即判红 | 写死只描述"今天这台 Spring AI 的投递次数"，升级即红；同构判据描述的是**形状间关系**（每个 usage 候选投递次数相同），升级后自动跟随 |
| 处置 | — | **修判据，不改产品 / 不改桩 / 不放宽数值** | 首轮 `PASS=34 FAIL=1` 的唯一红属判据错；产品与桩行为均正确（实测 `frames=2` 两种形状一致） |
| 代价（已接受） | — | CTX-3 依赖 CTX-1 先跑 | 加了"基准缺失即判红"的显式分支，不静默跳过 |

## 7 看板落点

| 文件 | 落点 | 内容 |
| --- | --- | --- |
| `docs/任务看板.md` | `:17`（批·上下文统计与压缩专项行 · 缺口登记） | ①**E2E-CTX-1..4 + 三形态 usage 桩** `未落` → **✅ 已销记**（合并笔 `4a4baf4`、分支 `7a98513`、基点 `a5539c2`、四形状落点、`PASS=35 FAIL=0`、证据两件）；并就地登记**用例计数口径更正：29 → 实为 31 → 35**（29 漏计 R-137 两例；+4 = 本批；四处口径同步改如实） |
| 同上 | `:363`（pending 小节 · `verify-e2e.ps1` 末尾汇总文案 MINOR） | 待修 → **已销记**（摘要行改「基数不写死、随用例演进」+ 显式列 E2E-CTX-1..4；责任方 = 本批） |
| `docs/test-cases.md` | `:206-209`（§5.4 AI 表） | E2E-CTX-1..4 四行**随合流笔入库**（冲突解保留双方新增；CTX-3 行判据描述同步，见 §2.1） |
| `docs/决策看板.md` | **未动** | 本笔无新裁决（判据更正由指挥官已裁，属执行面） |

## 8 文件清单（本笔提交面）

显式路径 add（**严禁** `git add -A` / `git add .`）：

1. `docs/evidence/提交报告-E2E增补CTX合流-DS-Flash.md` —— 本文件
2. `docs/任务看板.md` —— 缺口登记① 销记 + 计数口径更正 + `:363` MINOR 销记

合并笔 `4a4baf4` 的 9 文件清单见 `git show --stat 4a4baf4`（1 新建 = 施工报告；8 改 = `scripts/ci/stub-upstream.mjs` / `scripts/ci/stub-routes.json` / `scripts/verify-e2e.ps1` / `scripts/README.md` / `.github/workflows/ci.yml` / `docs/E2E-桩模式校准配方.md` / `docs/README.md` / `docs/test-cases.md`）。

## 9 提交、推送、清理（结果台账留痕）

- 提交：`docs(evidence+board): E2E增补CTX合流证据与缺口销记`
- 推送：`git push origin main`；**push 后不 watch CI**（纪律：CI 不同步盯）
- 清理：`git worktree remove ../wt-e2e-ctx` + `git branch -d feature/e2e-ctx`
- 台账：`<SHARED>/services.state` **只追加一行**
- 净态复核：`git status --porcelain` 仅 `?? data/`；`git rev-parse HEAD origin/main` 一致

## 10 红线条目核对

| 红线 | 执行 |
| --- | --- |
| 改产品代码 | **未改**（合并笔为施工分支原样合入；本棒两笔全为 docs） |
| 门禁红即停 | 合流后七门禁**全绿**（含脱敏 0 命中、桩自检 11/11），无停手点 |
| 语义冲突即停 | 仅 1 处**琐碎**冲突（同锚点各追加行），按「保留双方新增」解；无语义冲突 |
| 禁 `git add -A` / `git add .` | 遵守（分支笔 9 条显式路径、本笔 2 条显式路径） |
| 不改 `?? data/` | 遵守（全程未暂存、未入库） |
| 不碰 `wt-table-ai` / `wt-model-config-b3` / `wt-guardrails-b23` | 遵守（三者零操作，`git worktree list` 可证） |
| 不停 / 不重启 E2E 服务 | 遵守（18330 / 18399 全程 LISTENING，未触碰） |
