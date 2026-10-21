# 提交报告 · AI 输入输出安全防护 批②③（信任隔离 + 输出收敛 + DLP + 观测）合流

> DC-19 首页：本页第一屏即可判断"合了什么、sha 是什么、门禁是什么数、冲突怎么处置、挂账落在哪"。

## 0. 身份与结论（DC-19）

- 日期：2026-10-21
- 提交官自证（`profile.bind` 单行，取自本 agent 自己的 wire
  `.../session_ced79706-.../agents/agent-1383/wire.jsonl` 第 6 行）：
  `{"type":"profile.bind","agentId":"agent-1383","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high"}`
  → **modelAlias `DeepSeek/deepseek-flash` / profileName `coder` / thinkingEffort `high`**；
  身份核对 = 该目录 `turn.prompt` 首句「你是提交官，执行「AI 输入输出安全防护 批②③」合流 main 全流程」。
  （`ls -lat` 按建目录时间取最新 = `agent-1383`，18:10 建；并发的 `agent-1381/1382` 为其它棒，本笔未触碰其分支/worktree。）
- 授权：用户 2026-10-21「前序的任务直接实施」。
- **结论 = 已合流、门禁全绿、已推送。**
  - 施工单笔：**`a13c750`**（分支 `feature/guardrails-b23`，基点 `6bde8bd`，31 件 = 30 代码/脚本/文档 + 施工报告）
  - **合并笔：`edc98f5`**（`main`，`--no-ff`，父 = `1cf6eab` + `a13c750`；31 文件 +2593 / −35）
  - 后续证据笔：见 `main` HEAD（本文件所在笔）

## 1. 门禁（合流后主仓复跑）

| 门禁 | main 基线（合流前） | 合流后实测 | 判定 |
|---|---|---|---|
| 后端 `mvn -o test` | 1144 / 174 文件 | **1204 / 179 文件 · 0F / 0E / 0S** | ✅ 只升不降（= 1144 + 60） |
| 前端 `vitest run` | 623 / 38 文件 | **633 / 39 文件** | ✅（= 623 + 10 例 / 38 + 1 文件） |
| 前端 `vue-tsc --noEmit` | 0 | **0 error** | ✅ |
| 前端 `vite build` | ✓ | **✓（built in 46.31s）** | ✅ |
| `scripts/ci/check-docs.mjs` | 6/6 | **6/6** | ✅ |
| 脱敏扫描（合流 diff + 31 件全文 + 暂存面） | — | **0 命中** | ✅ |
| 服务 18330 / 18399 | 在跑 | **未起停、未触碰** | ✅ |

**后端计数双口径核对**：Maven 汇总行 `Tests run: 1204, Failures: 0, Errors: 0, Skipped: 0`；
`target/surefire-reports/TEST-*.xml` 求和 = `files=179 tests=1204 failures=0 errors=0 skipped=0` ⇒ **逐值一致**。
**预判数**（1144 + 60）与实际 **1204** 逐数吻合；179 文件 = 174 + 新增 5 个测试类（`SafetyRulesTest` / `OutputGuardTest` /
`ContextFieldGateTest` / `SecurityEventsTest` / `SecurityHeadersFilterTest`）。

**脱敏扫描口径与取证**：`scripts/githooks/scan-lib.sh` 的 `scan_engine_blob` 逐文件扫 31 件（`FILES_SCANNED=31 FILES_WITH_HITS=0`），
另对合流 diff 全文（`git diff 1cf6eab`，含 SECRET / PATH / USER 三规则）扫 0 命中。
正向对照证明规则未失效（构造串命中 SECRET）。**补充核查**（本机 scan-lib 的 PATH 规则在 Git Bash + awk 下对反斜杠路径不触发，
故另跑独立正则）：盘符绝对路径（`<盘符>:\temp` / `<盘符>:/temp` / `<盘符>:\Users` 等）**0 命中**、`/<盘符>/<temp>` 形式 **0 命中**、
完整 `sk-` 字面 **0 命中**、私钥头字面 **0 命中**（防护测试样本确认为拼接构造，符合施工报告口径）。

## 2. 施工分支提交（第 1 步）

- `git status --porcelain` = 18 改 + 13 新 = **31 条**，逐条对照施工报告 §7 文件清单（后端新 6 / 后端改 6 / 测试新 5 改 6 /
  前端改 5 新 1 / 脚本改 1 = 30 件 + 施工报告 1 件）⇒ **无多出项、无遗漏**。
- 显式路径 `git add`（31 条路径逐个列出，未用 `-A` / 通配）→ commit
  `feat(security): 防护批②③ 信任隔离+输出收敛+DLP+观测` → **`a13c750`**（pre-commit 脱敏扫描：暂存区无命中）。

## 3. 合流与冲突处置（第 2 步）

`git merge --no-ff feature/guardrails-b23 -m "merge: 防护批②③合流"` ⇒ **三处冲突**（预判面命中 3/4；`docs/test-cases.md` 自动合）。

| # | 文件 | 性质 | 处置 |
|---|---|---|---|
| 1 | `ResilientChatService.java`（`done` 终局 `extra` 装配） | 琐碎：双方在同一处追加 additive 键 | **保留双方新增**：`putParamsLocked(extra, turn)`（批③ 请求级运行态）+ `attachSafety(extra, guard, runId, sessionId)`（防护 `done.safety`） |
| 2 | `ResilientChatService.java`（`cancelTerminal` 的 `extra` 装配） | 同上 | **保留双方新增**：同样两行并列 |
| 3 | `AiController.java`（`chat` 的 `effective` 构造区） | **语义合并** | 闸序 = 必填 → 探活(503) → 限流(429) → 归一化 → 空拒 → 长度(400) → **`context` 形状闸 + 输入 DLP**（批②，非阻断）→ `effective` 构造（`modelText` + `effectiveContext` + `request.modelId()` + `request.params()` 五分量）→ **`MODEL_NOT_FOUND` / `INVALID_PARAM` 校验**（批③）→ 会话门(409) ⇒ 两侧语义零丢失：批② 的脱敏文本与闸后 context 真正进模型，批③ 的模型/参数存在性校验仍在会话门前 |
| 4 | `types/sse.ts`（尾部 `done` 接口字段） | 琐碎：两段 additive 字段共用了一个 `/**` | 各留一个独立 `/** */`：`paramsLocked?: boolean`（批③）+ `safety?: {…}`（防护），**帧型仍 16 种** |

- **自动合**（无人工介入）：`RunStore`、`ContextBuilder`、`ToolResultEnvelope`、`WebConfig`、6 个后端测试类、前端
  `types/api.ts` / `utils/error-card.ts` / `utils/markdown.ts` / `tests/ai-panel-parts-render.spec.ts`、`scripts/b23l1-dual-instance.ps1`、`docs/test-cases.md`。
- **语义冲突 = 0**；冲突标记复查（`^<<<<<<<` / `^>>>>>>>`）全仓 **0 命中**（仅历史报告引用该字样）。
- 合流后 `guard` 生命周期复核：`OutputGuard` 在 `consume` 前创建、经 `done/fail/blockTerminal/cancelTerminal` 四路透传，
  `attachSafety` 只在有事件时写键 —— 与批③ 的 `putParamsLocked` 互不影响（两条纯追加，键名无重叠）。

## 4. 自决 13 条（施工官，摘录）

| # | 自决 |
|---|---|
| 1 | **mask / block 边界**：block 只有一档（完整私钥块 / 未闭合私钥头），其余全部 mask（API key、AWS、Bearer、JDBC 口令、URL userinfo、`password=`） |
| 2 | **输入侧从不 block**：命中私钥也只 mask，HTTP 照常 200（主场景是用户来粘配置/问报错，整条拒掉等于打断主流程） |
| 3 | **DLP 落点**：输出在 `consume` 的 accumulator **之前**、不经 emitter；输入在 `chat` 长度闸之后 —— 同一份脱敏文本喂「模型」与「帧」，满足 `message_end.chars == Σ delta` |
| 4 | **尾部窗口**：`TAIL_WINDOW = 128`，只在尾部出现规则锚字符时才扣留（`SafetyRules#holdFrom`），普通中文正文零延迟 |
| 5 | **流中私钥头**：发现「有 BEGIN 无 END」即从头部起不再外发，轮末按 block 收尾（避免先发头、再发 base64 的部分泄露） |
| 6 | **未知 page**：归一为哨兵 `"unknown"` → 渲染「未知页面（未识别）」（降级不回显；置空会被误渲染成「任务中心」） |
| 7 | **工具结果定界**：包在信封 JSON **外面**（`<tool_result>` 包裹），信封字段逐字不变，`fromJson` 自动剥壳（零语义损失、不牵动 17 处既有断言） |
| 8 | **前端回灌超限**：截断 + `truncated:true`，不 400（机器数据本来就长；拒绝回灌会让该轮永久挂起） |
| 9 | **CSP**：只发 Report-Only，另加 `nosniff` / `no-referrer`（生效面 = `/api/*` 响应；页面由 Vite 承载 ⇒ 见 L-28） |
| 10 | **外链图标**：自绘 `<svg viewBox>`，无 emoji（`ALLOWED_TAGS` 已放行 svg/path） |
| 11 | **安全计数口径**：进程内 `AtomicLong`，`/health.security.scope = "process"`（审计事实是本实例处理了什么） |
| 12 | **台账 `turnNo`**：本棒不写（轮号在 `ai:ctx:` 真值里，写侧要多一次 Redis 往返）⇒ 见 L-31 |
| 13 | **配置项**：不新增 `app.ai.dlp.*`，单一事实源 = `SafetyRules` 常量（阈值是安全下限，不是调参项） |

## 5. 看板与挂账落点（第 4 步）

- `docs/任务看板.md` 防护批②③ 行（原 `:20`）：`批②/批③ 📋 待立项发令` → **✅ 批②③ 结**，补合并笔 / 四项门禁 / 交付面 /
  冲突处置 / 证据件；行内 **批① 段落原文保留**（`e62d375` + `5a48940`）。
- 挂账小节：**L-28 ~ L-34 新增 7 条**（承接施工报告 §6 的 7 条建议，逐条单列、各带依据与归属）：
  - L-28 CSP 只发 Report-Only 且覆盖不到页面本身（指挥官 · 独立小批）
  - L-29 注入分类器（远端 Prompt Shields / Lakera）维持不引（用户 · 数据出境口径）
  - L-30 `/history/{sessionId}` 无鉴权（DC-10）（指挥官 · 多用户/多实例部署硬前置，与 I-1 同批）
  - L-31 安全台账缺 `turnNo`（指挥官 · 与「安全事件进压缩台账」同批）
  - L-32 `/health.security` 只有进程内视图（指挥官 · 多实例可观测性专项）
  - L-33 规则表误报率无实测抽样（指挥官 · 需真实数据授权）
  - L-34 `AI_FEEDBACK` 摘录命中即整条 `[已隐去]`（指挥官 · 反馈质量专项）
- 速览行：挂账小节 **88 → 95** 项，追加「10-21 补记第十三批」两条（批次行 📋→✅ + 挂账 +7）；
  批次总量与状态分布**不变**（66 / ✅50 / 📋11）。
- **G1 疑似被 8192 闸覆盖的登记行：不动**（未裁 —— 本笔不代决）。
- `docs/决策看板.md`：**不动**（本笔无新裁决）。

## 6. 红线自查

- **不改产品代码**：本笔只 `add`/`commit`/`merge` 与两份文档（本报告 + 看板）；三处冲突按「双方新增全留 / 闸序语义合并」解，未删任何一侧逻辑。
- **门禁红即停手**：无红（全部实测绿，数字见 §1）。
- **不碰** `wt-visual-ops` / `wt-e2e-ctx`（其 worktree 与分支零触碰）。
- **不停服务**：18330 / 18399 全程未起停。
- **`rate-limit-shared` harness 用例不运行**：脚本与 SKIP 守卫随码入库，本笔未执行 harness。

## 7. 清理与净态（第 7、8 步）

- `git worktree remove ../wt-guardrails-b23` + `git branch -d feature/guardrails-b23`（合并后已无未合并提交）。
- 台账 `_shared/services.state`（仓外，路径见派遣单）追加一行（合流笔 / 门禁 / 挂账落点）。
- 净态判据：`git status --porcelain` 仅 `?? data/`；`HEAD == origin/main`。
