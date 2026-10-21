# 提交报告 · i18n + 工具可配置专项合流（S0/S1/S2/S6）

> DC-19 首页：本页第一屏即可判断"合了什么、sha 是什么、门禁是什么数、冲突怎么处置、挂账落在哪"。
> 本批 = 2026-10-21 专项队列的**最后一批**。

## 0. 身份与结论（DC-19）

- 日期：2026-10-21
- 提交官自证（`profile.bind` 单行，取自本 agent 自己的 wire `.../session_ced79706-.../agents/agent-1387/wire.jsonl`）：
  `{"type":"profile.bind","agentId":"agent-1387","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high"}`
  → **modelAlias `DeepSeek/deepseek-flash` / profileName `coder` / thinkingEffort `high`**；
  身份核对 = 该目录 `turn.prompt` 首句「你是提交官，执行「i18n + 工具可配置」合流 main 全流程（本批 = 队列最后一批）」。
  （`ls -lat` 取最新建 = `agent-1387`（18:36 建）；前一棒 `agent-1386` 首句为「你是提交官，执行「AI 可视可操作专项」合流 main 全流程」，再前一棒 `agent-1385` 为施工官。）
- 授权：用户 2026-10-21「前序的任务直接实施」。
- **结论 = 已合流、门禁全绿、已推送。**
  - 施工单笔：**`d76b5ff`**（分支 `feature/i18n`，基点 `1cf6eab`，76 路径 = 74 个 porcelain 条目〔3 个新目录折叠〕，逐条对照施工报告 §11 文件清单 = 无多出件、无缺件）
  - **合并笔：`5e5d3ab`**（`main`，`--no-ff`，父 = `0c19553` + `d76b5ff`；77 文件 +4103 / −322）
  - 后续证据笔：见 `main` HEAD（本文件所在笔）
- 开工基线：`main` = `origin/main` = **`0c19553`**（= 可视可操作合流 + 其更正笔；无需 pull）。

## 1. 门禁（合流后主仓复跑）

| 门禁 | main 基线（合流前 `0c19553`） | 合流后实测 | 判定 |
|---|---|---|---|
| 后端 `mvn -o test` | 1216 / 180 suite | **1243 / 184 文件 · 0F / 0E / 0S** | ✅ 只升不降（= 1216 + 27） |
| 前端 `vitest run` | 743 / 50 文件 | **814 / 55 文件** | ✅（= 743 + 71 例 / 50 + 5 文件） |
| 前端 `vue-tsc --noEmit` | 0 | **0 error** | ✅ |
| 前端 `vite build`（`TMP/TEMP=<仓外 _tmpx>`） | ✓ | **✓ built in 33.33s** | ✅ |
| `scripts/ci/check-docs.mjs` | 6/6 | **6/6** | ✅ |
| 脱敏四面独立正则 | — | **0 命中**（合流 diff 4103 加行 + 77 件全文） | ✅ |
| `ResumeServiceLeaseTest`（既有偶发 flake 单例） | — | **11 / 11 绿**，surefire `flakes="0"` | ✅ 本轮未复现 |
| 服务 18330 / 18399 | 在跑 | **未起停、未触碰**（netstat 复核仍 LISTENING） | ✅ |

**后端计数双口径核对**：Maven 汇总行 `Tests run: 1243, Failures: 0, Errors: 0, Skipped: 0`；
`target/surefire-reports/TEST-*.xml` 求和 = `files=184 tests=1243 failures=0 errors=0 skipped=0` ⇒ **逐值一致**。
+27 例来自 4 个新测试类（`ErrorCodesContractTest` / `ErrorCodesSingleSourceTest` / `GlobalExceptionHandlerTest` / `ToolDefinitionOverridesTest`）与 `GlobalExceptionHandlerSseTest` 扩例。

**前端 +71 例 / +5 文件** = `i18n-bootstrap` / `locale-store` / `router-title` / `tool-i18n-coverage` / `workspace-audience` 五 spec；首跑即绿，无负载 flake、无复跑甄别需求。

## 2. 冲突处置（两处冲突 + 一处跨批登记补齐）

`AiController`（S0 错误码收口的 18 处 × 防护批②③ 闸序）**未冲突**：`git` 自动合，两侧 hunk 不相邻（分支改「响应构造 / 码常量」，主仓改「闸序 / DLP 调用」）。合流后复核闸序仍为「必填 → 探活 → 限流 → 归一化 → 空拒 → 长度 → context 形状闸 + 输入 DLP → `effective` 构造 → 模型/参数校验 → 会话门」，**防护逻辑零改动**。

真冲突两处，均在**前端**：

| # | 文件 | 冲突形态 | 处置 | 依据 |
|---|---|---|---|---|
| C-1 | `frontend/src/components/AiPanel/AiPanel.vue` | 主仓在 `TOOL_LABELS` 内新增 7 个 `ui_*` 原语 + 保留 `download_templates`；分支**整块删除** `TOOL_LABELS`（文案已迁 `t('tool.<name>.title')`） | **取分支侧删除**（保留"文案单一源"），并把主仓 7 个 `ui_*` 标题**补进** `zh-CN.json` / `en-US.json`（zh 值逐字取自主仓 `TOOL_LABELS`：查看界面 / 点击界面元素 / 填写输入框 / 选择选项 / 滚动界面 / 高亮界面元素 / 请用户选择文件） | 双方新增都必须保留：`ui_*` 工具本身在主仓已入库，缺标题会在 `toolLabel()` 回落成裸工具名（**可见回归**）⇒ 必须补键 |
| C-2 | `frontend/src/types/api.ts` | 主仓新增 `SENSITIVE_OUTPUT_BLOCKED`（+ `FRONTEND_CANCELLED` 注释）；分支给的是**全量码表**（S0 收口后 60+ 码） | **取分支全量码表** + 保留主仓 `SENSITIVE_OUTPUT_BLOCKED`（含原注释）；`FRONTEND_CANCELLED` 分支表已有 ⇒ 只把主仓的注释并入，不再重复一行 | 双方新增保留；`utils/error-card.ts:80` 仍按 `ErrorCode.SENSITIVE_OUTPUT_BLOCKED` 分支渲染，删键会打红 typecheck |

**跨批登记补齐（唯一一处触碰产品代码的非纯文档动作，显式登记）**：
`ErrorCodesContractTest` 的「前端镜像 ↔ 后端码表」双向对账在合流后**唯一红** = `SENSITIVE_OUTPUT_BLOCKED`
（防护批③ 在基点 `1cf6eab` 之后新增的 wire 码，天然不在 i18n 分支的码表里）。处置按设计稿自身纪律「**新增码只在 `ErrorCodes` 加一行**」：

1. `backend/.../common/ErrorCodes.java`：SSE 面 `AI_RESUME_FAILED` 之后加一行常量 + 注释；
2. `backend/src/main/resources/messages_zh.properties` / `messages_en.properties`：各加 `error.SENSITIVE_OUTPUT_BLOCKED` 一键（zh 语义直译，en 同义）；
3. `backend/.../ai/run/ResilientChatService.java`：原常量改**别名** `= ErrorCodes.SENSITIVE_OUTPUT_BLOCKED`（+ import），
   仅为满足 `ErrorCodesSingleSourceTest`「码字面量只在 ErrorCodes 声明」，**码值、发帧时机、闸判据、SSE 帧字节全部不变**。

复验：三测试类 + `OutputGuardTest` 定向 17/17 绿；随后全量 1243/1243 绿。**未改动**任何闸序、DLP 规则、限流参数、安全语义。

## 3. 脱敏四面（独立正则，逐面单跑）

扫描面 = 合流 diff（`0c19553..5e5d3ab`，6029 行 / 4103 加行）**加** 77 件触达文件全文。

| 面 | 正则 | 结果 |
|---|---|---|
| A 盘符路径（正反斜杠，带前导边界，避开 `https://` 假阳性） | `(?<![A-Za-z0-9_])[A-Za-z]:[\\/]` | **0** |
| B `sk-` 字面 | `\bsk-[A-Za-z0-9]{16,}` | **0** |
| C 私钥头 | `-----BEGIN [A-Z ]*PRIVATE KEY-----` | **0** |
| D 用户名面 | `(?:Users\|home)[\\/][A-Za-z0-9._-]+` 或本机用户名**字面**（报告内不落该字面，用 `<用户名>` 占位） | **0** |

`pre-commit` 敏感信息钩子在两次提交时均返回「暂存区无命中」。本报告与看板行内**不含**任何本机绝对路径字面量。

**基线等价说明（避免把既有命中读成增量）**：全域扫描在 `docs/任务看板.md:17` 有 **1 处既有命中** = `C:/…` 正对照样例（上下文批 报告文字，`0c19553` 已在库）；本棒新增的看板行与批次行均**不含**该形态 ⇒ **增量 0、文件集合与基线逐条相同**。

## 4. 自决摘录

1. **`ui_*` 七标题补进 locale**（而非回填 `TOOL_LABELS`）：分支的"文案单一源"是整批的地基，回填会让 S2 收口在合流处破口；补键只增不改，zh 逐字等价。
2. **`SENSITIVE_OUTPUT_BLOCKED` 登记而非白名单豁免**：`ErrorCodesContractTest` 的白名单只收「前端独有的网络层码（后端不发）」，而该码**后端确实发**（`ResilientChatService` SSE `error` 帧）⇒ 豁免会写进一句假事实；登记 + 别名是唯一自洽解。
3. **别名而非删除 `ResilientChatService` 常量**：`OutputGuard` / `SafetyRules` 注释与既有测试都引用该类常量名，删名会扩散改动面；别名把改动收敛到 1 行。
4. **`messages_zh` 也补一键**：`ErrorBodyFactory` 的 zh 口径是"用调用方原文"，该键在 zh 下不改变任何既有响应字节（仅给 en 提供码级兜底，并让「键集 = 码集」的对账成立）。
5. **`AiPanel.vue` 的 `TOOL_LABELS` 文档注释随块删除**：注释描述的是已不存在的映射表，留着会误导下一个读代码的人。
6. **不改 E2E 脚本**：14 处中文 `-like` / `-notlike` 仍在（施工报告 §10 已静态登记），按"不改产品代码 + 不起服务"纪律留挂账 L-50。

## 5. 挂账落点（`docs/任务看板.md`）

批次行（`:19`）📋 → **✅**，并注明「S0/S1/S2/S6 已合流，S3-S5 / S7 转挂账」。
挂账小节在 **L-46 之后、I-1 之前**新增 **L-47 ~ L-51** 五条（逐条单列）：

| 条目 | 一句话 | 待谁 |
|---|---|---|
| L-47 | S3–S5 文案抽取未搬迁；codemod `frontend/scripts/i18n/codemod.mjs` 已入库未执行（227 处可机械化 / 17 文件） | 指挥官（后续 i18n 批，S3→S4→S5） |
| L-48 | S6 尾项：`AiController` 9 处裸 `Map.of(code, message)` 未接 `ErrorBodyFactory`（接则改构造签名 ⇒ 打红 4 个既有测试） | 指挥官（独立小批） |
| L-49 | S7 未做：`V5__ai_tool_registry.sql` + 缺 `production` 标签 fail-fast + `check-i18n-residue.mjs` 进 CI（现网 1201 条中文 ⇒ 最后开闸） | 指挥官（S7 独立批） |
| L-50 | 双语浏览器验收未做（en 下 chrome 层无中文、翻译质量未验收；45 条 en 摘要为直译；E2E 14 处中文断言仍静态登记） | UI 验收批 / 实机 |
| L-51 | 工具 `displayName` 可见变更知会（见 §6） | 口径知会（用户 / 指挥官） |

速览行同步：批次 **66** 条不变，状态分布 ✅51/📋10 → **✅52 / 📋9**；挂账 **107 → 112** 项；补记「第十五批 2 条」。
`docs/决策看板.md` **未动**（本批无新裁决，合流处置全部落在既有设计稿纪律内）。

## 6. 可见变更知会（`displayName` / `/api/ai/tools`）

- **26 个后端工具**的 `displayName` 由「description 前 20 字」变为 **`title`（中文短名）**（设计稿 E9 要求的行为）。
  前端卡头的 zh 展示名**逐字未变**（同一份 `tool.<name>.title`）。
- `GET /api/ai/tools` **只增 4 个字段**：`titleKey` / `title` / `lang` / `definitionsVersion`；既有按 `name` / `count` 的断言与调用不受影响。
- 风险面：**下游若有按 `displayName` 文案比对的外部脚本会失效**（本仓无此用例，已登记 L-51）。

## 7. 清理与净态

- `git worktree remove ../wt-i18n` ✓；`git branch -d feature/i18n`（was `d76b5ff`）✓。
- **未触碰** `wt-e2e-ctx`（跑中 jar 句柄待删）；**零服务启停**（18330 / 18399 全程 LISTENING）。
- 净态：`git status --porcelain` 仅 `?? data/`；`git rev-parse HEAD origin/main` 一致。
- 台账：仅向 `_shared/services.state` **末尾追加**本棒一行，未改任何历史行。

## 8. 诚实边界

- **未跑 E2E**（纪律禁起服务）：`scripts/verify-e2e.ps1` 保持原样，14 处中文断言耦合是**静态登记**，不是运行观测。
- **未做双语浏览器验收**：`en-US.json` 只保证「键集与 zh 双向对齐 + 占位符同名同数」，**翻译质量未经验收**（L-50）。
- **本批未改任何业务页面**：en 下业务页面仍是中文（S3–S5 未搬迁，预期行为，非缺陷）。
- **合流后门禁是单次全量**：后端 1243 与 surefire 求和逐值一致；前端首跑即绿。`ResumeServiceLeaseTest` 本轮未复现 flake，但既有 flake 性质（L-19 同族）未因此消除。
- 本报告不含本机绝对路径字面量（按 DC-08 口径）。
