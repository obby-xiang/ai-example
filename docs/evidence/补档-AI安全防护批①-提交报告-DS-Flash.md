# 补档 · AI 输入输出安全防护批①（入口闸门）提交报告（合流提交官 · DS-Flash · 2026-10-21）

## 0 自证行（wire 实测，非照抄卡片）

- 提交官 agentId = `agent-1346`；wire = `<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_604b3bc4-1812-4fe3-b259-6c0a97160f45/agents/agent-1346/wire.jsonl`
- 取数方式：`grep -n 'profile.bind'` 命中**第 5 行**（前 4 行为 `metadata` / `runtime.set_binding` / `mcp.tools_discovered`），原文首部（逐字）：

```json
{"type":"profile.bind","agentId":"agent-1346","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high",...}
```

- 实测三字段：`modelAlias` = **`DeepSeek/deepseek-flash`**、`profileName` = **`coder`**、`thinkingEffort` = **`high`**
- 仓根 = `<REPO_ROOT>`（唯一可操作仓）；职责边界 = **合流 + 三条复核微修 + 机械验收 + commit + push + CI 跟进**。除 §2 第 2 笔的三条注释/防御微修外，**零产品代码改动**（闸门代码由分支施工/收口链交付）。零服务启停（E2E 不在本地跑，由 CI 执行）；`?? data/` 未动未入库；施工 worktree 只读（未在其中做任何提交）。

## 1 机械验收逐项

### 1.1 合并（`--no-ff`）与冲突面

| 项 | 事实 |
| --- | --- |
| 命令 | `git merge --no-ff feature/ai-guardrails-b1 -m "merge: AI输入输出安全防护批①（入口闸门）"` |
| 基点 / 分支 HEAD | `d8056d5` / `65f3dc2` |
| 主线侧同期改动 | 批③（`c00606e` 族）与 L-19 修复（`a96cd06`）；文档侧 L-19 销记笔（`0083a74`） |
| **冲突** | **唯一 1 处**：`backend/.../ai/config/AiProperties.java` —— 双方在同一锚点（`private Lease lease` 之后）各插字段（主线 `Lock` / 分支 `Input` + `Limit`） |
| 解法 | 两边都留、语义并集：`lock` → `input` → `limit` 三字段按序声明；`Lock` / `Input` / `Limit` 三个静态内部类各自完整保留（注意 `Session.Lock` 是**既有**嵌套类，与顶层 `AiProperties.Lock` 不同作用域，合并后同名不同级，编译无歧义） |
| 预期"可能撞"但**未撞** | `AiController.java`（批② 内容双方共有、批③ 未动该文件）⇒ 自动合；`application.yml`（批③ 未改，分支只加 `input`/`limit` 段）⇒ 自动合 |
| 合并后校验 | 全量后端测试 **811/811** 绿、前端 **522/522** 绿 ⇒ 无"编译过但语义丢"的静默合并 |

### 1.2 计数口径（**以实测为准，显著标注**）

| 项 | 任务书预期 | 实测 | 差 | 处置 |
| --- | --- | --- | --- | --- |
| 后端用例（surefire `tests` 求和） | 780 + 33 = **813** | **811**（**127** suite，0F/0E/0S） | **−2** | 分支相对基点 702 净增 **31**（非 33）：主线 780 + 31 = 811。两口径对账见 `-复核确认-` §5 |
| 后端连跑 | 2 次 | **2 次均 811/811 · 127 suite · 0F/0E/0S**（日志 `-q` 无汇总行，计数取自 `target/surefire-reports/TEST-*.xml` 属性求和） | — | 一致 |
| 前端用例 | **521** · 27 文件 | **522** · 27 文件（`exit 0`、**0 Unhandled Rejection**） | **+1** | 合流前主线已 **513**（批③ +1）；513 + 9（新 spec）= 522 |
| 前端 typecheck | 0 | **0**（`node node_modules/vue-tsc/bin/vue-tsc.js --noEmit`） | 0 | — |
| 前端 build | ✓ | **✓ built in 48.62s**（`node scripts/build.mjs`，exit 0） | 0 | — |
| check-docs | 6/6 | **6/6**（`git ls-files` 1406 路径 / 实扫 1403 / 豁免 3） | 0 | — |
| 脱敏 | 0 | **0**（三笔提交 pre-commit 均 `✓ 暂存区无命中`；未用 `AI_SECRETS_ALLOW`、未 `--no-verify`） | 0 | — |
| E2E（29 用例） | — | **不本地跑**（需起服务）；CI `e2e` job **success**（§3） | — | 由 CI 执行 |

### 1.3 测试门禁实测（提交官本人，合并后）

| 门禁 | 命令 | 实测 |
| --- | --- | --- |
| 后端全量 · 第 1 次 | `cd backend && <MAVEN_HOME>/bin/mvn -q -o test` | **exit 0 / BUILD SUCCESS**；surefire 127 suite、`tests` 求和 **811**、0F/0E/0S |
| 后端全量 · 第 2 次 | 同上 | **exit 0**；同上 **811 / 127 / 0F/0E/0S** |
| 前端全量 | `cd frontend && node node_modules/vitest/vitest.mjs run` | **522/522 · 27 文件**，`exit 0`，**0 Unhandled Rejection**（`ai-panel-parts-render` 187 例、`ai-panel-composer-limit` 9 例均绿） |
| 前端类型检查 | `node node_modules/vue-tsc/bin/vue-tsc.js --noEmit` | **exit 0** |
| 前端构建 | `node scripts/build.mjs`（= `package.json` 的 `build` 去掉已单列的 `vue-tsc --noEmit`） | **✓ built in 48.62s**，exit 0 |
| 文档门禁 | `node scripts/ci/check-docs.mjs` | **6/6** |
| 脱敏 | `pre-commit`（与 CI `scan-history` 同源规则） | 三笔均 **0 命中** |

> 前端门禁在**三条微修落地之后**实测（不是修前数字）；后端两跑在合并提交之后、微修之前/之后各一跑，微修不触后端代码。

### 1.4 看板核对（行号按入库后最终态）

| 位置 | 内容 |
| --- | --- |
| `docs/任务看板.md:20` | 防护专项行 **🔧 → ✅**：合并笔 `e62d375` + 微修笔 `5a48940`；门禁 811/811 · 127 suite（连跑 2 次）/ 522/522 · 27 文件 / typecheck 0 / build ✓ / check-docs 6/6 / 脱敏 0；三道闸 + 闸门顺序 + 自决 5 条 + 边界登记（M2/M3/M4/E2E 不覆盖限流）+ 三条微修 + flake 根因 |
| `docs/任务看板.md:79` | degraded 行**注记更新**：**测试面跨例污染已根治并合流**（`e62d375` + `5a48940`，合流后 522/522 与加压实测复跑）；**24ms 预算路径实测覆盖仍缺 ⇒ 行仍挂**（状态仍 📋，计数不变） |
| `docs/任务看板.md:423` | L-19 行**已为 ✅**（上一轮 `0083a74` 已做）：本笔只核对 —— 修复笔 `a96cd06`、白名单口径、780/780 连跑 3 次、CI 不再按 flaky 处置，内容完整，**未再改** |
| `docs/任务看板.md:432` | 速览行同步：已闭环 46 → **47**、批次在飞 🔧 **清零**、状态分布 ✅46/📋14/🔧1 → **✅47/📋14/🔧0**（总 66 条不变）；追加"10-21 补记第五批 2 条" |
| `docs/决策看板.md:16` | 「安全防护立项与批①范围裁决」**🔧 → ✅**：加合流 sha、门禁数字、四件补档；批②③ 仍 📋 待发令 |
| `docs/决策看板.md:18` | 「L-19 定性」**🔧 → ✅**（销记留痕）：定性正文不删，追加修复口径（归还白名单 + `ACTIVE_LATCH_IN_PROCESS` 早退不还 + 替身串行化 + 2 例确定性用例）与红率 50% → 0% |

### 1.5 证据件（四件，本笔入库）

| 文件 | 内容 |
| --- | --- |
| `docs/evidence/补档-AI安全防护批①-施工报告-DS-Flash.md` | 测试矩阵（G1/G2/G3/M1/前端/回归面）+ **红测四条**（8193 照过 / 零宽原样进模型 / 第 21 次照过 / 前端 5 例红）+ 三道闸落地 + 改动清单 18 文件 + 施工链门禁 + **自决 5 条** + 未做/偏差 |
| `docs/evidence/补档-AI安全防护批①-复审报告-DS-Flash.md` | **CHANGES-REQUESTED（1C/3I/6M/4N）** 逐条 + **攻击面 a–h** + 复审时点门禁 + 去向 |
| `docs/evidence/补档-AI安全防护批①-复核确认-DS-Flash.md` | **READY-WITH-NITS（0C/0I/0M）** + C1/I1/I2/I3/M1/M5/M6 核验 + **`resetDegradation` 根因七步** + **M-a/N-a/N-b 三条微修** + 数字仲裁 + 断言只升不降 + 遗留 6 条 |
| `docs/evidence/补档-AI安全防护批①-提交报告-DS-Flash.md` | 本件 |

## 2 提交与推送（三笔，逐笔 push）

| # | 短哈希 | message | 内容 |
| --- | --- | --- | --- |
| 1 | **`e62d375`** | `merge: AI输入输出安全防护批①（入口闸门）` | 合并笔：18 文件（+1657/−11）+ `AiProperties.java` 冲突解（两边都留） |
| 2 | **`5a48940`** | `fix(ai-guard): 复核微修 M-a/N-a/N-b` | 3 文件（+12/−8）：`scripts/verify-e2e.ps1`（限流注释口径）/ `frontend/tests/setup.ts`（`typeof` 防御）/ `frontend/src/utils/markdown.ts`（措辞「页签级」） |
| 3 | **本件**（push 后回执） | `docs(evidence): 补档 AI 安全防护批① 四件 + 看板/决策看板销记` | `docs/evidence/` 四件 + `docs/任务看板.md` + `docs/决策看板.md` |

- 三笔全程 `git add` **显式路径**，无 `add -A` / `add .`；三笔均过 `pre-commit` 阻断式扫描（`✓ 暂存区无命中`）。
- 第 1/2 笔一次 push：`0083a74..5a48940 main -> main` ✓。
- origin 保持 **HTTPS**，未改远端配置；施工 worktree `wt-ai-guardrails-b1` **未触碰**（只读）。
- 微修笔落地方式的取舍：**不 amend 合并笔**（合并笔的 message 只该说"合了什么"，微修是复核意见的产物，独立一笔便于回滚与追溯）。

## 3 CI 确认

| 笔 | run id | 触发 | 结论 |
| --- | --- | --- | --- |
| `e62d375` + `5a48940`（同一次 push，GitHub 按 head `5a48940` 建 run） | **39547700726** | push→main | **后端 ✓ · 前端 ✓ · check-docs ✓ · E2E ✓**（`scan-history` 报告制，不计门禁）；**首跑即绿，L-19 已修，无 flaky 重跑** |
| 本件（第 3 笔） | 见提交官交付回执 | push→main | 见交付回执 |

- 轮询方式 = `gh run list --limit 3` / `gh run view <id> --json status,conclusion,jobs` / `gh run watch <id> --exit-status`（`gh` = `<GH_CLI>`，Git Bash 下 api 端点无前导斜杠）。
- E2E job 的限流参数按裁决 I3 生效（`--app.ai.limit.session-per-minute=200 --app.ai.limit.ip-per-minute=2000`）⇒ 该 job 的绿不能被解读为"限流在 E2E 被验证"。

## 4 异常与留痕

1. **冲突只有一处，且是"同锚点插字段"**：`AiProperties` 两侧都往 `private Lease lease` 后面加字段。3-way 合并无法自动排列，手工取"两边都留 + 按 `lock`/`input`/`limit` 顺序"。**没有语义取舍**（三字段互不依赖）。
2. **813 → 811 的差额已对账**（§1.2 / 复核 §5）：不是丢用例 —— 主线 780 + 分支净增 31 = 811；预估的 "+33" 把收口新增多算 2 例。
3. **521 → 522 的差额已对账**：合流前主线已到 513，不是 512。
4. **M-a 的算术**（复核 §7 注①）：TC18 同一 sessionId 的绝对上界是 23（2 次确认尝试 + 1 次并发探针 + 20 次取消后重试），注释按任务书口径记 22（不含并发探针那条 409）。两者都远超默认 20/分 ⇒ **结论不变**（E2E 必须显式抬高），故按原口径留痕、不再改注释。
5. **E2E 不在本地跑**：本地零服务启停纪律；29 用例由 CI 执行，本报告只引 CI 结论。
6. **施工期工作日志不入库**：仓外多轮后端/前端日志（含 C1 修前 `exit=1`、父提交 12 红复现）仅作取证来源，不入 `docs/`。

## 5 挂账异动清单（本批）

| 动作 | 条目 | 依据 |
| --- | --- | --- |
| **无新增** | — | 本批 = 合流 + 三条注释/防御微修，未发现新缺陷 |
| 保留 | **M2** 前后端 8192 无交叉校验 | 施工报告 §6、复核 §2；批②或配置下发时收 |
| 保留 | **M3** `context.*` 不归一化（fail-closed 边界，已注释） | `InputSanitizer:30-45` |
| 保留 | **M4** `/confirm.reason` 与 `/frontend-tool-result` 无长度闸 | 批②③ |
| 保留 | **24ms 预算路径无实测覆盖**（degraded 硬化本体） | 看板 `:79` 仍挂；本批只销记"跨例污染"那一半 |
| 保留 | **L-19**（已修复销记，行不删） | `a96cd06`；本笔只核对 `:423` 与决策看板 `:18` |
