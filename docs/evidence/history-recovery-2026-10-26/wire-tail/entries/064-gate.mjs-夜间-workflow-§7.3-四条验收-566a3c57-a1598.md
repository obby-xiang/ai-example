> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-1598` 的 wire 末条 assistant 文本（消息序号 m=164；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-23 19:29 → 2026-10-23 20:09（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：`e8db0dd`（正文自动摘取的首次基线提及），未经本批复验
> - 原始体量：8708 B / 5381 字符 / 67 行；行指纹覆盖率（本批复算）8.3%（最高单文件 4%：`docs/research/调研-AI操作在线表格能力业界惯例-2026-10-21.md`）
> - 类别：**施工复核**
> - 与既有件关系：S2 修单；报告口径覆盖率 0.04
> - 脱敏：路径 3 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
所有改动与实测均已完成。以下是交付说明。

## 改动文件清单

| 文件 | 性质 | 说明 |
|---|---|---|
| `frontend/package.json` | 改（+1 行） | 新增 `"test:related": "node ../scripts/ci/frontend-related.mjs"`；`test`（`vitest run`）一字未动 |
| `scripts/ci/frontend-related.mjs` | 新，124 行 | §4.2 的跨平台实现，替代 `xargs -r -d '\n'` |
| `scripts/ci/gate.mjs` | 新，517 行 | 两级门禁入口 `--tier commit\|merge`，含 `--shadow` |
| `.github/workflows/nightly.yml` | 新，431 行 | 夜间全量（O2） |

## `frontend-related.mjs`（替代 xargs）

`-r` 与 `-d '\n'` 只有 GNU xargs 有，BSD/Windows 没有。改为：`git diff` 输出按**行**切分（不经 shell 词分割），空变更集 / 高扇出模块（`src/locales/**`、`src/api/http.ts`、`src/types/**`、`stores/ai.ts`、`stores/workspace.ts`）/ `related` 零命中 三种情况转全量。

实测（主仓，显式给叶模块）：
- `src/utils/tool-summaries.ts` → 选中 **23 文件 / 398 例 / 23.2s**（全量 51.6–76.3s）
- `src/types/definition.ts`（高扇出）→ 打印「转全量：命中高扇出模块」并跑全量 ✓
- 空变更集（clone）→ 「转全量：变更集为空」✓

## `gate.mjs` 两 tier 真实运行数字

演练环境：`<LOCAL_WORK>/_c-gate/clone`（`git clone --shared` 自本仓，HEAD `e8db0dd`，**读源仓对象、不写源仓**），倒排表用主仓录出的 `map.json`，前端 `node_modules` 用 junction，改动 = 一处合成的单类改动（`ConditionEvaluator` 尾部注释，不触发任何兜底规则）。

**主仓录制（B 期命令）**：238s，`1708 例 / 261 类 / 0F/0E/0S`，`mode=full`，`keyOverlapRatio=0.8735`、`keyOverlapRatioConcrete=0.9819`，index 221 键 / 7096 对。

| 运行 | 总耗时 | select | 后端 | vitest | vue-tsc | docs | i18n | build |
|---|---|---|---|---|---|---|---|---|
| commit（首录表，选择集 23 类） | **107.2s** 绿 | 0.4 | **47.4**（140 例·23 类·0F） | **51.1**（112 文件·1367） | 6.4 | 1.5 | 0.1 | — |
| commit `--shadow`（同一棵树） | **313.0s** 绿 | 0.4 | 选择集 61.6（140·23）+ 全量 **185.3**（1708·261） | 52.7 | 11.0 | 1.6 | 0.1 | — |
| commit（确定性检查后，选择集 54 类） | **187.5s** 绿 | 0.4 | **127.1**（356 例·54 类·0F） | 51.6 | 6.7 | 1.4 | 0.1 | — |
| merge | **479.7s** 红 | — | 默认 locale **168.9**（1708·261·0F/0E/0S）+ en-US **164.8**（1688? 同 1708·261·0F/0E/0S） | 76.3 **RED** | 12.7 | 2.0 | 0.1 | 54.3 |

影子对照结论：`漏检 missed=0`（全量 0 红、选择集 0 红），台账写 `backend/target/selection/shadow-ledger.json`，影子只多 **62s**（与设计稿「只多 1–1.5 分钟」吻合）。

merge 级唯一红 = 前端全量 6 例失败 / 4 文件（`rg-round-restore-stop`、`rg-s13-s14-stopped-label`、`r2-midstream-refresh`、`rg-r4-r5-mirror-dedupe`）。**不是本笔引入**：同一 clone 的 commit 级跑同一套件 112 文件/1367 全绿；merge 级连跑三次分别 6/4/7 红且**红集每次不同**；单文件隔离 `rg-s13-s14-stopped-label` 4/4 绿。属看板已登记的套件级负载 flake（L-93 面正在改的也是这一族 store/镜像用例）。

## 夜间 workflow 要点

- 触发 `schedule: 17 18 * * *`（UTC = 北京 02:17，避开整点）+ `workflow_dispatch`；`concurrency` 串行、不取消进行中。
- 五个 job：`backend`（全量**双 locale**，两跑之间清 surefire，两份报告都归档）、`frontend`（typecheck+vitest+build）、`e2e`（与 ci.yml 逐字同命令集，仅去掉变更集条件，保留 `needs.backend.result=='success'`）、`testmap`、`summary`。
- **无用变更集跳过逻辑**（夜间没有「变更集」，跳过=取消兜底）；`summary` 把任何 skipped 判红（e2e 因上游红跳过除外），防假静默。
- `testmap`：`changed-files --self-check` → `node --test select-tests.test.mjs`（27 例）→ 录制第一遍 → `--verify-map` → 录制第二遍 → 确定性判定 → 归档 `map.json`/`map-run1.json`/`selection/`（不落仓，O1）。
- 确定性口径写进文件头：差异类由录制器**并集修复**并钉永久冒烟集，判据 = `diff=0` 绿 / `0<diff≤阈值 且 unionRepaired` 绿+warning / 其余红。`DETERMINISM_MAX_DIFF=40`（见遗留 2）。

## §7.3 四条验收

1. **提交级 ≤3 min**：**部分达成**。首录表状态 = 107.2s（后端 47.4 ≤90s、前端 51.1 ≤60s，端到端 ≤180s 全达标）✓；但确定性检查把 33 类标 `unstable` 后冒烟集涨到 46 类 → 187.5s / 后端 127.1s，**超**。机制没问题，数字取决于表的 `unstableTests` 规模（见遗留 2）。
2. **合流级与今天逐项一致**：达成。7 项一一对应（后端全量默认 locale、后端全量 `-DargLine="-Duser.language=en -Duser.country=US"`、前端 `yarn test`、`yarn typecheck`、`yarn build`、`check-docs`、`check-i18n-residue --strict`），双 locale 数字与看板今日口径逐值相同（1708/261/0F/0E/0S 各一跑）。F13 命中时打印点名（合流级恒跑 en-US，故「强制 en-US」自动成立）。E2E 维持手动/CI。
3. **影子期判据进输出**：达成。`--shadow` 同跑、选择集结果**不计入结论**（汇总表标注）、漏检清单（区分「未选中」「选中但绿」）、每笔漏的根因归类位（`rootCauses`）、判据行 `漏检 ≤ 1/300` 与窗口累计。已记 1 笔 / missed 0。
4. **夜间 job**：达成（内容如上）。本机无法执行 Actions，只做了 YAML 解析 + 步骤清单校验；`--verify-map` 有额外处置（见遗留 1）。

## 语义/面变更

- `package.json` 仅新增一个本地脚本，`test` 语义不变；前端仍不做选择化。
- `gate.mjs` 是新的本地入口，**不替换**任何既有脚本；合流级命令逐字沿用今天。`mvn` 解析顺序 `--mvn` → `MVN` → PATH → Windows 上 IDE 自带 maven（本机实测命中）、Git Bash 形态 `<DRIVE>/...` 自动转 `.cmd`。
- gate 传给选择器的审计文件名用稳定 `selection/latest.json`（`treeSha` 在文件内容里），偏离 §2.5 的 `<treeSha>.json` 命名——避免 gate 自己再调一次 `git write-tree`。
- 夜间 workflow 不作为 required check；`DETERMINISM_MAX_DIFF` 是只许下调的棘轮。

## 遗留问题 / 发现（未修改，交裁决）

1. **B 期 `--verify-map` 口径与录制器不一致（真发现）**：`--verify-map` 以全量源码 FQN 为分母 → 本仓 221/253 = **87.35% < 95% → 退出码 2**（健康表也被判红）；录制器自身 B0 闸用**具体类**口径 `keyOverlapRatioConcrete=0.9819` 且 BUILD SUCCESS。夜间的处置 = `--verify-map` 记为报告位（`::warning::`）+ 具体类口径硬闸。建议 owner 改 `select-tests.mjs` 的 `--verify-map`（我未动该文件）。
2. **unstable 集合会永久拖慢提交级**：同 tree 连录两次 `deterministicDiff=33/261`（12.6%）→ 33 类进 `unstableTests` → 冒烟集 46 类 → 提交级 107s 涨到 187s。我的两次录制负载不同（一次在繁忙工作树），差异可能被放大，但机制后果是真的：**一次高负载录制会永久拖慢每一笔提交**。建议：录制只在静默环境（夜间 runner 即为此）；并考虑给 `unstableTests` 加时间窗或按 O6 降档——这是设计 §3.6「unstable 永久常跑」的缺口，需裁。
3. **前端全量套件在本机不稳**（见上），合流级"全绿"数字拿不到；若门禁要自动跑合流级，建议给已知 flake 清单加 1 次重试（§6.3 已有此规则，但只写在选择集侧）。
4. **主仓当前必然退化全量**：工作树里有三笔并行未提交改动（`ci.yml`/`scripts/**` → F11，`frontend/src/locales/**` → F13），选择器实测输出 `ALL`、原因 `F11 选择器/门禁自身变更`。这是正确行为；我在主仓**没有**再跑一遍全量门禁（会与并行 agent 抢 `backend/target` 与测试负载），退化路径改用 clone 的 O8 演练取证：删表后实测打印「⚠ O8 自动降级：…（map-missing）」并起全量。
5. **`git write-tree` 说明**：`select-tests.mjs` 写审计时会调用（B 期既有，设计 §5.1 也这么写），只在 `.git/objects` 落不可达 tree 对象，不动 refs/index/工作树。本笔未做任何 ref、commit、stash、checkout 变更。
6. 硬约束遵守：8081/5202 未起停未占用；未触碰 P7 的 `verify-e2e.ps1`/`stub-upstream.mjs`/`stub-routes.json`、L-93 的 `16-kqc-c.spec.ts`、以及 `ci.yml`/`e2e-real.yml`/`changed-files.mjs`/`select-tests.mjs`/`docs/`。演练件（clone、日志）全在仓外 `<LOCAL_WORK>/_c-gate/`。
