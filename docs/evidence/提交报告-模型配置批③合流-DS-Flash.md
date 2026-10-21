# 提交报告 · 模型配置批③（对话面板 + 契约）合流

> DC-19 首页：本页第一屏即可判断"合了什么、sha 是什么、门禁是什么数、冲突怎么处置、挂账落在哪"。

## 0. 身份与结论（DC-19）

- 日期：2026-10-21
- 提交官自证（`profile.bind` 单行，取自本 agent 自己的 wire
  `.../session_ced79706-.../agents/agent-1381/wire.jsonl`）：
  `{"type":"profile.bind","agentId":"agent-1381","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high"}`
  → **modelAlias `DeepSeek/deepseek-flash` / profileName `coder` / thinkingEffort `high`**；
  身份核对 = 该目录 `turn.prompt` 首句「你是提交官，执行「模型配置批③（对话面板+契约）」合流 main 全流程」。
  （同秒另建 `agent-1382` 为并发施工官〔AI 可视可操作专项〕，本笔未触碰其分支/worktree。）
- 授权：用户 2026-10-21「前序的任务直接实施」。
- **结论 = 已合流、门禁全绿、已推送。**
  - 施工单笔：`ba32a53`（分支 `feature/model-config-b3`，基点 `64c1caf`）
  - **合并笔：`4096ef7`**（`main`，`--no-ff`，父 = `e80e316` + `ba32a53`）
  - 后续证据笔：见 `main` HEAD（本文件所在笔）

## 1. 门禁（合流后主仓复跑）

| 门禁 | main 基线（合流前） | 合流后实测 | 判定 |
|---|---|---|---|
| 后端 `mvn -o test` | 1101 / 164 文件 | **1144 / 174 文件 · 0F / 0E / 0S** | ✅ 只升不降（= 1101 + 43） |
| 前端 `vitest run` | 598 / 35 文件 | **623 / 38 文件** | ✅（= 598 + 25 例 / 35 + 3 文件） |
| 前端 `vue-tsc --noEmit` | 0 | **0 error** | ✅ |
| 前端 `vite build` | ✓ | **✓（built in 1m 26s）** | ✅ |
| `scripts/ci/check-docs.mjs` | 6/6 | **6/6** | ✅ |
| 脱敏扫描（本笔提交面） | — | **0 命中** | ✅ |
| 服务 | 18330 / 18399 在跑 | **未起停、未触碰** | ✅ |

**后端计数双口径核对**：Maven 汇总行 `Tests run: 1144, Failures: 0, Errors: 0, Skipped: 0`；
`target/surefire-reports/TEST-*.xml` 求和 = `files=174 tests=1144 failures=0 errors=0 skipped=0` ⇒ **逐值一致**。
**预判数**（1101 + 43）与实际 **1144** 逐数吻合，无反扣。

## 2. 施工分支提交（第 1 步）

- `git status --porcelain` 逐条对照施工报告 §6 文件清单：**改 16 + 新 16 = 32 件，无多出项**。
- 显式路径 `git add`（32 条路径逐个列出，未用 `-A` / 通配）→ commit
  `feat(model-config): 批③ 对话面板模型选择+契约+帧集冻结守卫` → **`ba32a53`**。
- **一次预提交拦截并处置**：`docs/evidence/施工报告-模型配置批③-DS-Flash.md:15` 命中「本机绝对路径」⇒
  按 DC-08 换占位符 `<REPO>/wt-model-config-b3` 后重提（**只改占位符，报告正文零改写**）。

## 3. 合流与冲突处置（第 2 步）

命令：`git merge --no-ff feature/model-config-b3 -m "merge: 模型配置批③（面板+契约）合流"` → **`4096ef7`**。

预判冲突面五个文件，实测分布：

| 文件 | 预测 | 实测 | 处置 |
|---|---|---|---|
| `frontend/src/stores/ai.ts` | 可能与表格相邻 hunk | **CONFLICT（1 处）** | **import 块**冲突（批③ 加 `ai-model` / 文案表；表格加 `sheet-tools` 五函数 + `resolveSheetContext`）⇒ **保留双方新增**：`useAiModelStore` + `AI_CONFIG_ERROR_TEXT` + 合并后的 `frontend-tools` 导入行 + `sheet-tools` 多行导入 |
| `frontend/src/components/AiPanel/AiPanel.vue` | 区域不同应自动合 | 自动合 | 表格 `SheetPreviewCard` 链与批③ composer 底栏（`model-slot` / `params-toggle`）/ 副标题 / 黄条区域**不邻**，双方俱在 |
| `frontend/src/types/ai.ts` | 批③ 尾追加 vs 表格新增 | 自动合 | 零删除行，双方新增全留 |
| `frontend/tests/ai-panel-parts-render.spec.ts` | 未预判 | 自动合 | 表格新增用例 + 批③ composer 口径收紧，双方俱在 |
| `backend/.../run/ResilientChatService.java` | 未预判 | 自动合 | 上下文批③ 的 `ContextOverflow.matches` 越窗识别（错误分类区）与批③ 的请求级运行态/`driveResumed` 不同 hunk |

**语义冲突 = 0**。冲突面外核查：
- `stores/ai.ts` 合流后相对 `main` 的删除行**仅一条** = `{ sessionId, message, content, context }` → `{ …, ...modelPatch }`（批③ 独占改动），其余 17 行全为新增。
- 全仓 `^<<<<<<<` / `^>>>>>>>` 扫描 **0 命中**。
- `ResilientChatService.java` 内 `ContextOverflow.matches` 与 `driveResumed(…, null, null, …)` 三参旧路**同时在位**（双批共存无互踩）。

**未触碰**：`wt-guardrails-b23` / `wt-e2e-ctx` / 任何新 worktree；18330 / 18399 全程未起停。

## 4. 施工自决 11 条（摘录 · 全文见施工报告 §3）

| # | 决策 | 一句话理由 |
|---|---|---|
| D-1 | 新建 `GET /api/ai/models` | `/providers` 前端二次过滤会与后端漂移 ⇒ "列出来点不通" |
| D-2 | `done.model` = 上游回显优先、回落解析值 | 回显是"这一轮真跑了谁"的最强证据 |
| D-3 | `paramsLocked` 载体 = `done.extra`（additive） | 帧型不变；策略态（`/health`）与事实态（本轮）必须分开 |
| D-4 | 能力位降级**不报错**（不发 `MODEL_TOOLCALL_UNSUPPORTED`） | 模型能力不是用户错误 |
| D-5 | 续跑**不重放** params | 不扩批① 的 `RunSnapshot` 字段面（→ 挂账 L-26） |
| D-6 | provider 靠 `modelId` **反查**，不加 `providerId` 请求字段 | 设计稿只要求可选 `modelId`（→ 挂账 L-25） |
| D-7 | 参数越界 = 400 `INVALID_PARAM` | 本批**零新增错误码**，与设置页写路径同一把尺 |
| D-8 | `allowUserParams` **缺省 = 允许** | 未设置时谈"锁定"没有依据 |
| D-9 | 选择器用**原生 `<select>`** | 与 R-165「EP 全退场」一致；`data-testid` 与语义不变 |
| D-10 | 请求级模型**不写缓存**（新增 `forRequest`） | 套用 `runtimeFor` 会污染默认路径（下一轮带上一轮模型） |
| D-11 | 前端 `allowedParams` 未知按**允许** | 不把"未知"渲染成"锁定"把面板锁死 |

## 5. 设计稿纠偏 6 条（摘录 · 全文见施工报告 §4）

| # | 位置 | 纠偏 |
|---|---|---|
| C-1 | §3.6 | 设计稿称 `/health.providers[]` 带 models ⇒ **不成立**（实际无 models 字段），改一次 `GET /api/ai/models` 挂载请求 |
| C-2 | §3.5 错误码封闭表 | 批② 已增 5 码未收表；**批③ 新增 0 码**，三码用表内既有 |
| C-3 | §3.5 `/health` | 只给 `allowedParams` 布尔、未给初值来源 ⇒ additive 补 `defaultParams{…}` |
| C-4 | §3.6 落点 | `el-select` 与「EP 全退场」冲突 ⇒ 原生 `<select>` |
| C-5 | §11 批③ 项 | 「快照固化 model/provider」**批① 已完成**，批③ 只做消费侧 |
| C-6 | §3.5 端点表 | `GET/POST /providers/{id}/models` 实为同路径双方法（批② 已有），批③ 未动 |

## 6. 四条挂账落点（第 4 步）

全部按指挥官裁决**登记挂账、不扩权修**；行落在 `docs/任务看板.md` 挂账小节（L-23 之后、I-1 之前，保持 L-系连号），
速览行"挂账小节"计数 **84 → 88**，并加注"2026-10-21 模型配置批③ 新增 4 条"。

| 编号 | 落点 | 要点 | 承接人 |
|---|---|---|---|
| **L-24** | 看板挂账小节 L-23 之后 | `start` 帧不加 `providerId`/`modelId`（16 帧冻结维持）⇒ 在飞轮模型显示缺位（显示"将要用的模型"） | 指挥官（破帧冻结需单独裁） |
| **L-25** | 同上 | 跨 provider 同名模型按库序取第一个 + `WARN`；分流需新增 `providerId` 请求字段（契约面变更未批） | 指挥官（契约面变更单独裁，须"后端先上"） |
| **L-26** | 同上 | 续跑不回放 params（params 未快照化，续跑用全局默认，`paramsLocked` 恒 false） | 指挥官（扩 `RunSnapshot` 留待批④/专门批次） |
| **L-27** | 同上 | `MODEL_TOOLCALL_UNSUPPORTED` 码留位**无发射点**（能力位降级走 D-4 不报错） | 指挥官（设置页是否用它拒绝另裁） |

## 7. 看板与台账落点（第 4 / 7 步）

- `docs/任务看板.md` 批·模型配置功能行：行内「批③ 仍 📋 待发令」→ **✅ 已合流**，补合并笔 `4096ef7` /
  施工 `ba32a53` / 基点 `64c1caf`、合流后门禁六项、交付面、**合流处置**（一处 import 冲突 + 四处自动合）、
  证据件两件、挂账四条；**首项 I-3 已修**显式标注（B3-40~42 / F-24~F-25 覆盖）。
- 速览行：批次总量与状态分布**不变**（该行早已 ✅，批③ 是行内子项）⇒ **66 / ✅50 / 📋11**；
  挂账 **84 → 88**；补记第十二批 2 条（本笔入库 + 四条挂账）。
- `docs/决策看板.md`：**未改动**（本批无新裁决，四条待裁已按裁决挂账）。
- 台账 `<_SHARED>/services.state`：追加本笔一行（sha / 门禁 / 挂账）。

## 8. 清理与净态（第 7 / 8 步）

- `git worktree remove ../wt-model-config-b3` + `git branch -d feature/model-config-b3`（见 §9 实测）。
- 合并后净态：`git status --porcelain` **仅 `?? data/`**（运行态数据目录，不入库）；`HEAD == origin/main`。

## 9. 实测留痕（命令与结果）

| 项 | 结果 |
|---|---|
| 分支状态对照 | 改 16 + 新 16 = 32 件，与施工报告 §6 **逐件一致、无多出** |
| 分支提交 | `ba32a53`（首次被 pre-commit 路径规则拦下 → 占位符化后通过） |
| 合流 | `4096ef7`，冲突文件 = `frontend/src/stores/ai.ts` **1 件** |
| 后端 | `Tests run: 1144 …` + `BUILD SUCCESS`；surefire 求和 1144 |
| 前端 | `Test Files 38 passed` / `Tests 623 passed` |
| typecheck | `TSC_EXIT=0`（无输出） |
| build | `✓ built in 1m 26s` |
| check-docs | 6/6 |
| 脱敏 | 0 命中（盘符绝对路径 / 用户家目录 / 密钥前缀三形态全扫本笔提交面；复核中一次真命中 = 本行原样例字面 ⇒ 改文字描述，未用放行） |
| worktree 清理 | `git worktree remove ../wt-model-config-b3` ✓；`git branch -d feature/model-config-b3` ✓（was `ba32a53`，`-d` 即证明已并入 main） |
| 清理后 worktree list | 主仓 `main` + `wt-e2e-ctx`（跑中 jar 句柄，按红线保留）+ `wt-guardrails-b23` + `wt-visual-ops`（并发行）——**三者零操作** |
