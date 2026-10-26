> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-199` 的 wire 末条 assistant 文本（消息序号 m=9；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-09 19:14 → 2026-10-09 19:15（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：`ee1d3c4`（正文自动摘取的首次基线提及），未经本批复验
> - 原始体量：13956 B / 10784 字符 / 193 行；行指纹覆盖率（本批复算）4.1%（最高单文件 3%：`docs/evidence/history-recovery-2026-10-26/reports/wait-fix-a-2026-10-10-等待治理批A施工复核收尾.md`）
> - 类别：**过程核验**
> - 与既有件关系：复核报告；报告口径覆盖率 0.03
> - 脱敏：路径 7 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 6
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
扫描完成，全程只读（Grep/Glob/Read + `git` 只读子命令 + `grep -r` 交叉校验），未修改任何文件。

---

# A. 改动面核对（K4）

## A1. diff --stat / status --porcelain

```
$ git -C <REPO_ROOT> diff --stat
 frontend/src/views/ExportWizardView.vue | 156 ++++++++++++++++++++++++-----
 1 file changed, 134 insertions(+), 22 deletions(-)

$ git -C ... status --porcelain
 M frontend/src/views/ExportWizardView.vue
```

`git status --porcelain --untracked-files=all` 也只输出这一行 —— **无未跟踪文件**。

命中清单（唯一）：`frontend/src/views/ExportWizardView.vue`（M，+134/-22）。**K4 的"仅一个修改文件"成立。**

补充边界证据：
- HEAD = `ee1d3c4`，分支 `main`，与 `origin/main` 齐平（未提交、未 push）。
- diff 的 hunk 头全部落在 `loadDefinitions` / `loadTask` / `loadExistingResults` / `HANDLER_NAMES` / `registerHandlers` / `createAndBindTask` / `watch(` 这几个区域（`@@ -276 +276,9 @@` … `@@ -809,3 +923 @@`），即任务绑定链路 + 两个 export 执行器 + route watch，**没有跨文件、没有碰 backend/scripts/docs**。

## A2. frontend-tools.ts 未改动 + issue#1 修复未破坏

```
$ git -C ... diff -- frontend/src/utils/frontend-tools.ts
（空输出）
$ git -C ... diff --name-only
frontend/src/views/ExportWizardView.vue        # 唯一
```

`frontend/src/utils/frontend-tools.ts` 中 issue#1 修复的四组符号均在场：

| 符号 | 命中行号 |
|---|---|
| `bindsTask?: (args) => number \| null`（ActionDescriptor 字段声明） | `frontend-tools.ts:205` |
| `waitForTaskBound(` | `frontend-tools.ts:273` |
| `resolveNavigateIntent(` | 定义 `:378`；使用 `:318`（`toUiEvent`）、`:326`（`navigatesTo`）、`:327`（`bindsTask`） |
| `defaultExecutor(` | 定义 `:689`；使用 `:538` |

配套证据：`navigate_to` 描述符的 `bindsTask: (args) => resolveNavigateIntent(args)?.taskId ?? null`（`:327`）、等待点 `const expectedTaskId = action.bindsTask?.(args) ?? null`（`:453`）→ `await waitForTaskBound(target, expectedTaskId)`（`:456`），以及 `:284` 的 `workspace.taskId === expectedTaskId` 判据全部原样保留。**issue#1 修复未被本次改动破坏，为旁证成立。**

---

# B. 执行器注册面（K5）

## B1. `registerFrontendToolExecutor` 全量调用点（frontend/src）

grep 模式：`registerFrontendToolExecutor\(|unregisterFrontendToolExecutor\(`

| 文件:行 | 内容 |
|---|---|
| `frontend/src/utils/frontend-tools.ts:59` | 函数**定义**（`export function registerFrontendToolExecutor`） |
| `frontend/src/utils/frontend-tools.ts:64` | 函数**定义**（`unregisterFrontendToolExecutor`） |
| `frontend/src/views/ExportWizardView.vue:850` | **唯一注册点**：`registerFrontendToolExecutor('download_export_file', async (call) => {...})` |
| `frontend/src/views/ExportWizardView.vue:881` | `unregisterFrontendToolExecutor('download_export_file')` |

**全仓库仅注册了 `download_export_file` 一个前端执行器。** 没有第二个注册点，`ImportWizardView.vue` 里**零**调用（见 B3）。其余 FRONTEND 工具靠 `defaultExecutor` 转发到 page handler（`frontend-tools.ts:689+`）。

补充 page handler 注册面（`registerPageHandler` / `PAGE_HANDLER_*` 常量）：

- `frontend-tools.ts:54` — `PAGE_HANDLER_OPEN_EXPORT_EDITOR = 'export.openEditor'`
- `ExportWizardView.vue`：`export.nextStep:776`、`export.selectDefs:786`、`export.setConditions:795`、`export.start:822`、`PAGE_HANDLER_OPEN_EXPORT_EDITOR:827`
- `ImportWizardView.vue`：`import.nextStep:909`、`import.selectDefs:926`、`import.upload:934`、`import.startPrecheck:938`、`import.startImport:943`、`import.startPublish:948`
- `workspace.ts:268/272` — 注册表实现

## B2. `.taskId` 读取点全清单（frontend/src）

grep 模式：`args\.taskId|payload\.taskId|\.taskId`

**执行器/页面处理器从 AI 调用入参读 taskId 的点（K5 的核心面）——只有 3 处：**

| 文件:行 | 形态 | 上下文 |
|---|---|---|
| `ExportWizardView.vue:833` | **`payload.taskId`** | `export.openEditor` 处理器内：`const gate = await ensureTaskBoundForTool(payload.taskId)`（:827 注册的 handler） |
| `ExportWizardView.vue:856` | **`call.args.taskId`** | `download_export_file` 执行器内：`const gate = await ensureTaskBoundForTool(call.args.taskId)` |
| `frontend-tools.ts:696` | **`call.args.taskId`** | `open_export_file_editor` 的 defaultExecutor：`const outcome = await handler({ taskId: call.args.taskId, defCode: call.args.defCode })` —— 即 :833 的 `payload.taskId` 来源 |

**其余 `.taskId` 读取点（路由/存储/动作事件，非执行器入参）：**

| 文件:行 | 形态 | 说明 |
|---|---|---|
| `ExportWizardView.vue:239` | `route.query.taskId` | `readTaskId()` |
| `ExportWizardView.vue:916` | `route.query.taskId` | `watch(...)` 响应路由 query |
| `ImportWizardView.vue:313` | `route.query.taskId` | `readTaskId()` |
| `ImportWizardView.vue:1001` | `route.query.taskId` | `watch(...)` 响应路由 query |
| `frontend-tools.ts:205` | 类型 `bindsTask?: (args) => number \| null` | 描述符字段声明 |
| `frontend-tools.ts:257, 284` | `workspace.taskId` | 等待判据 `workspace.taskId === expectedTaskId` |
| `frontend-tools.ts:322, 324` | `intent.taskId` | `toUiEvent`：`restore_task` 动作生成 |
| `frontend-tools.ts:327` | `resolveNavigateIntent(args)?.taskId` | `bindsTask` |
| `frontend-tools.ts:383` | `args.taskId` | `resolveNavigateIntent` 解析（仅 `export`/`import` 页保留） |
| `frontend-tools.ts:423` | `context.taskId` | `needsTask` 校验 |
| `frontend-tools.ts:623, 648` | `args.taskId` | defaultExecutor：`restore_task`、`run_job` |
| `workspace.ts:103,104,133,134,155,169,232,252` | `state/init/this.taskId` | store 状态读写与快照 |
| `workspace.ts:355, 358` | **`event.taskId`** | 动作 `restore_task` |
| `workspace.ts:361, 371, 381, 390` | `workspace.taskId` | 动作 `goto_step` / `select_defs` / `set_conditions` / `set_import_mode` 的 needsTask 读 |
| `workspace.ts:399` | **`event.taskId`** | 动作 `run_job` |
| `workspace.ts:413, 414` | **`event.taskId`** | 动作 `export_started` |
| `job-watch.ts:57` | `options.taskId` | SSE 订阅 |

未出现 `response.taskId` / `result.taskId` 等其它读取形态。

## B3. ImportWizardView.vue 注册面

`registerFrontendToolExecutor` 调用：**零命中**（`ImportWizardView.vue` 完全不注册前端工具执行器）。

`registerPageHandler` 调用（6 个，全部无 taskId 入参）：

| 行 | 处理器名 | payload 是否含 taskId |
|---|---|---|
| `:909` | `import.nextStep` | 否 —— 读 `payload.step` |
| `:926` | `import.selectDefs` | 否 —— 读 `payload.defCodes` / `payload.mode` |
| `:934` | `import.upload` | 否 —— 回调无参，返回提示文案 |
| `:938` | `import.startPrecheck` | 否 —— 回调无参，用组件内 `taskId.value` |
| `:943` | `import.startImport` | 否 —— 同上 |
| `:948` | `import.startPublish` | 否 —— 同上 |

导入侧任务绑定来源只有两处，均**不经 AI payload**：`readTaskId()` 读 `route.query.taskId`（`:313`，用于 `onMounted`），以及 issue#1 修复的 `watch(() => route.query.taskId, ...)`（`:1001`，`workspace.setTaskId(id, 'IMPORT', null)` 于 `:1009`）。

## B4. 后端 AiTools.java

唯一文件：`backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java`（后端 `@ToolChannel(FRONTEND)` 全量 7 处，均在该文件：`:339/349/365/375/403/439/495`，无第二个工具类）。

### FRONTEND 通道工具全表（7 个）

| 工具名 | 行 | 参数（`@ToolParam`） | 带 taskId？ | Scope / Risk |
|---|---|---|---|---|
| `open_export_file_editor` | `:336-343` | `taskId: Long`（必填）、`defCode: String` | **是**（必填） | `task:EXPORT/EXPORT`，READ |
| `download_export_file` | `:346-353` | `taskId: Long`（必填）、`defCode: String` | **是**（必填） | `task:EXPORT/EXPORT`，READ |
| `navigate_to` | `:362-369` | `page: String`、`taskId: Long`（`required=false`） | 是（可选） | `*`，READ |
| `select_definitions` | `:372-379` | `codes: String[]`、`mode: String?` | 否 | `page:tasks, task:EXPORT, task:IMPORT`，READ |
| `set_condition` | `:400-407` | `defCode: String`、`conditions: FieldQueryCondition` | 否 | `page:tasks, task:EXPORT`，READ |
| `confirm_step` | `:436-442` | `step: String?` | 否 | `page:tasks, task:EXPORT, task:IMPORT`，READ |
| `generative_form` | `:490-498` | `form: FormSpec` | 否 | `page:tasks, task:*`，READ |

（`:334` 定义哨兵桩常量 `FRONTEND_STUB = "FRONTEND_STUB_SHOULD_NOT_RUN"`；`:330` 注释明确方法体不会被执行。）

### 非 FRONTEND 通道但带 taskId 的后端工具（对照用）

`get_workspace_state`（`:148-149`，`taskId` 可选）、`start_export`（`:265-266`）、`start_precheck`（`:285-286`）、`start_import`（`:303-304`）、`start_publish`（`:316-317`）—— 这 5 个是后端直执工具，不经过前端执行器。

**import 侧 editor / download 前端工具：不存在。** 全库 FRONTEND 工具中，只有 export 侧有 `open_export_file_editor` 与 `download_export_file`，两者 scope 均为 `task:EXPORT/EXPORT`；导入向导侧没有任何 editor/download 形态的前端工具。（另外 `run_job` 在后端**不是** `@Tool` —— 该名字只在 `frontend-tools.ts:648` / `workspace.ts:399` 作为前端工作区动作出现。）

---

# C. 脱敏纪律扫描（K6）

扫描范围：`<LOCAL_WORK>/issue2-reports/`（含 `evidence/` 子目录，全部文本文件）。被扫文件清单：
`issue2-修复报告.md`、`evidence/fix.diff`、`evidence/gate-build.log`、`evidence/gate-typecheck.log`、`evidence/probe-run.mjs`、`evidence/probe-src.ts`、`evidence/probe.html`、`evidence/probe-*.json`（14 个）。`evidence/downloads/` 为空目录。

## C1. 盘符绝对路径 —— **零命中（真实盘符）**

两段式扫描：

- 原样正则 `[A-Za-z]:[\\/]`（Grep 工具）在 15 个文本文件中有命中，但逐条核对**全部是 URL 协议**，非盘符：
  - `evidence/probe-run.mjs:20` `http://localhost:5211/__issue2_probe.html`
  - `evidence/probe-run.mjs:53` `http://127.0.0.1:${PORT}/json/version`
  - `evidence/probe-run.mjs:69` `http://127.0.0.1:${PORT}/json`
  - 14 个 `probe-*.json` 各 1–2 条 `blob:http://localhost:5211/<uuid>`（如 `probe-after-cross-download.json:94`、`probe-after-same-task.json:118`）
  - 14 个 `probe-*.json` 各 1 条 Vue Router 警告 `https://github.com/vuejs/router/blob/main/packages/...`（如 `probe-after-task-missing.json:111`）

  （Grep 工具不支持 lookbehind，故补做 `grep -rnoE '[A-Za-z]:[\\/][A-Za-z0-9_.\\/-]*'` 交叉校验，输出仅为 `<DRIVE>//localhost`、`<DRIVE>//github.com`、`<DRIVE>//127.0.0.1` 三种形态。）
- 排除协议后，形如 `E:\`、`C:/`、`file:///E:/` 的**盘符绝对路径：零命中**。`.png` 被原生 `grep` 报 "Binary file matches" 属二进制字节巧合，Grep 工具默认跳过，不计为文本命中。

报告与 diff 的路径写法是全相对路径 + 占位符，例如 `issue2-修复报告.md:3` `仓库：<MAIN_REPO>（分支 main，HEAD=ee1d3c4）`、`:5` `施工范围：<MAIN_REPO>/frontend/`，`evidence/fix.diff:1-4` `a/frontend/src/views/ExportWizardView.vue` / `b/frontend/...`。

## C2. 密钥形态 `\bsk-[A-Za-z0-9]` —— **零命中**

全库（含 `evidence/`）无任何 `sk-` 前缀字符串。

## C3. 禁则命名组合 —— **零命中**

- `(?i)main[-_. ]v2`：零命中。为验证 `(?i)` 内联标志未造成假阴性，做了正向对照：`(?i)MAIN_REPO` 有命中（`issue2-修复报告.md:3,5,73,236`），说明该 grep 路径与标志有效；另用 `grep -rniE 'main[-_. ]v2'` 复扫同样零命中。
- `<REPO>` / `ai-example-code`：**零命中**（`grep -rn 'ai-example-code\|<REPO>'` 亦零）。
- 交叉校验：全库 `grep -rnic 'v2'` 在**所有文本文件计数均为 0**（唯一非零是 `probe-after-cross-download.png` 的 1 次二进制字节巧合）。
- 报告中出现的相关字样只有分支名本身 `main` 与占位符 `<MAIN_REPO>`（`issue2-修复报告.md:3,5,73,236`），**未构成 `main`+分隔符+`v2` 组合**；`fix.diff` 正文为相对路径 diff，无仓库真实目录名，故此项不涉及 diff 正文命中。

## C4. 红队临时目录

`<LOCAL_WORK>/issue2-redteam/` 已存在文件（`dist-before.txt`、`gate-typecheck-redteam.log`、`workspace-diff-redteam.txt`，均 19:14 写入），**按指令跳过，未扫描**。

---

## 结论摘要

- **A**：工作区仅 `frontend/src/views/ExportWizardView.vue` 一个修改文件（+134/-22，无未跟踪文件）；`frontend-tools.ts` 完全未改，issue#1 的 `waitForTaskBound`/`bindsTask`/`resolveNavigateIntent`/`defaultExecutor` 全部在场且调用链完整。
- **B**：前端执行器注册面只有 `download_export_file` 一处；从 AI 入参读 taskId 的执行器路径只有 3 个（`ExportWizardView.vue:833` `payload.taskId`、`:856` `call.args.taskId`、`frontend-tools.ts:696` `call.args.taskId`）；导入向导零执行器注册、零 taskId 入参 handler；后端 FRONTEND 工具共 7 个，仅 `open_export_file_editor` / `download_export_file`（export-only，taskId 必填）与 `navigate_to`（taskId 可选）带 taskId，**不存在 import 侧 editor/download 前端工具**。
- **C**：C1 真实盘符绝对路径零命中（原正则命中全为 `http(s)://` 协议）；C2 `sk-` 零命中；C3 `main[-_. ]v2`、`<REPO>`、`ai-example-code` 三项目零命中（文本文件 `v2` 计数全为 0），报告使用 `<MAIN_REPO>` 占位符；C4 按指令跳过。**K6 脱敏纪律扫描未见违规。**

未做事项：未运行任何构建/测试命令（本任务范围为只读 grep 取证）；`<LOCAL_WORK>/issue2-redteam/` 按要求未纳入扫描。
