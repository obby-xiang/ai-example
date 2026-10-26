> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-15` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：8711 B / 6113 字符 / 56 行；行指纹覆盖率（仓内 + 落盘语料）13.0%
> - 类别：**正式独立报告**
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
# 三前端对比报告（只读调查，证据为 file:line）

## 表1 技术栈与规模

| 项 | A deepseek-v4-pro（现任蓝本） | B glm-5.3 | C kimi-k3 |
|---|---|---|---|
| dependencies | vue ^3.5.13, router ^4.5, pinia ^2.3, element-plus ^2.9.0, spread-sheets/ExcelIO **17.1.5** | 同 A + icons-vue ^2.3.1, axios ^1.7.9, jszip ^3.10.1, **spread-sheets-resources-zh 17.1.5** | 同 B + dayjs ^1.11.13, uuid ^11.0.3 |
| devDependencies | vite ^5.4.11, plugin-vue ^5.2.1, tailwind ^3.4.17, postcss ^8.5.28, autoprefixer ^10.6.1 | vite ^6.0.7, tailwind ^3.4.17, postcss ^8.4.49, autoprefixer ^10.4.20 | 同 B（vite ^6.0.7） |
| 缺失包 | 无 axios / icons-vue / jszip / resources-zh / dayjs / uuid | — | — |
| JS/TS | **纯 JS，0 个 .ts**（.vue 11 / .js 10 / .css 2） | **纯 JS，0 个 .ts**（.vue 8 / .js 8 / .css 1） | **纯 JS，0 个 .ts**（.vue 8 / .js 9 / .css 1） |
| 代码行 | 2933（vue 2144 / js 789） | 2173（vue 1546 / js 627） | 2669（vue 1572 / js 1097） |
| 组件数 | 11 vue：5 视图 + 5 组件 + App | 8 vue：3 视图 + 4 组件 + App | 8 vue：3 视图 + 4 组件 + App |
| 路由/页面 | 5 页：tasks/export/import/**defs（定义管理 381 行）/data（数据浏览 137 行）**，`router/index.js:4-9` | 3 页：home/task/:id/tasks，`router/index.js:4-6`（hash 路由） | 3 页：tasks/export/:taskId/import/:taskId，`router/index.js:4-7` |
| 构建脚本 | 原生 `vite build` | `node scripts/build.mjs`（TEMP 改写绕杀软锁，B/C 同） | 同 B |
| lint/test/tsconfig | 三者全部没有 | 三者全部没有 | 三者全部没有 |

## 表2 关键能力矩阵

| 能力 | A | B | C |
|---|---|---|---|
| a. AI 对话 SSE | 有，自研 fetch 流解析 `api/index.js:61-109` | 有，`api/sse.js:5-59` | 有，`api/sse.js:5-57` |
| a. 思考链渲染 | 有，折叠卡片 `AiPanel.vue:22-28` | **无**（只解析 token/error/done，`stores/ai.js:53-62`） | 有，`<details>` 折叠 `AiPanel.vue:44-47` |
| a. 工具卡片 | 有，含参数/结果/状态四态 `AiPanel.vue:29-47`；事件 `stores/ai.js:155-172` | **无**（仅有"最近操作"activity 列表 `AiPanel.vue:17-27`，SSE activity） | 有，toolRuns 标签 + 参数展示 `AiPanel.vue:51-67` |
| a. HITL 确认卡片 | 有，`AiPanel.vue:48-55` + `stores/ai.js:82-93`（POST /api/ai/confirm 续流） | **无**（后端注释明确"无需 HITL 暂停-恢复" `docs/02:46`） | 有，确认/拒绝按钮 `AiPanel.vue:68-85` + `stores/ai.js:196-219`（POST /api/ai/tool-result 续流） |
| b. 联动契约层 | **事件总线 + 契约层**：`utils/workspaceBus.js:11-52`、`utils/workspaceContract.js:17-118`（ui_event→store action）、版本号 `utils/workspace.js:22-26` | **无**总线/契约；靠全局 EventSource `/api/sse` 广播 + `App.vue:64-102` 分发到 task store；**请求不带工作区上下文**（`stores/ai.js:51`） | **窄接口 store + handler 注册表**：`stores/workspace.js:21-96`（buildContext:65、registerPageHandler:86-96）、前端工具执行器 `utils/frontend-tools.js:33-122` |
| c. 多步骤向导 | 有：导出 3 步 319 行 / 导入 4 步 500 行，步骤状态机随任务状态映射 `ExportWizardView.vue:143-159` | 有：共享壳 `TaskWizard.vue:20-59` + `wizard/ExportWizard.vue`(3 步) / `wizard/ImportWizard.vue`(5 步) | 有：`ExportWizardView.vue`(3 步 424) / `ImportWizardView.vue`(4 步 422)，job 持久化到 stepData（`ExportWizardView.vue:233-247`） |
| c. 进度展示 | **SSE**：`stores/exportTask.js:60-82`、`importTask.js:89-113`，任务中心轮询 4s `TasksView.vue:154` | **SSE**：全局 EventSource 的 progress 事件 `App.vue:86-88`→`stores/task.js:43-47`→`TaskWizard.vue:32-52` 逐项进度 | **轮询**：1s 轮询作业 `utils/job.js:9-29` + `JobProgress.vue`（含行级明细展开 `JobProgress.vue:13-39`） |
| d. SpreadJS | `components/SpreadSheet.vue:15-16,41-105`（ExcelIO 开/存 + Blob 回传）；授权 `main.js:13-15`，**无中文资源、无 Culture 设置**；用 `<i class="el-icon-loading">` `SpreadSheet.vue:9` 但未装图标包 | `components/SpreadGrid.vue:13-57` + `utils/excel.js`(267 行：表头/校验器/JSZip)；授权 `main.js:13-17` + zh 资源/Culture `main.js:10-12` | `components/SpreadSheet.vue:24-134`（ResizeObserver:32-36、collectRows:104-120）+ `utils/excel-io.js`(388 行)；授权/Culture `main.js:16-20`；`tailwind preflight=false` `tailwind.config.js:5-7` |
| e. 任务中心 | `TasksView.vue`：类型筛选+分页+状态标签+进度条 `:18-62`，**4s 自动刷新** `:154`，一键恢复向导与订阅 `:133-149` | `TaskCenter.vue`：类型/状态筛选 `:11-21`，**无分页、无自动刷新**（仅按钮 `:5`），跳转 `:83-85` | `TaskListView.vue`：新建弹窗 `:58-80`、取消/删除 `:132-148`，**无分页、无自动刷新**（仅按钮 `:9`） |

## 表3 UI/交互质量信号

| 信号 | A | B | C |
|---|---|---|---|
| 样式方案 | Tailwind + **36 处 @apply**（10 个 scoped 块，183 行 CSS）+ 自定义 brand 色 `tailwind.config.js:10-15` | 全内联 Tailwind（~126 行含工具类，`@apply` 0），7 个 scoped 块 101 行 | 全内联 Tailwind（`@apply` 1），**仅 1 个 scoped 块 18 行**（三点动画，已注明原因 `AiPanel.vue:186-202`） |
| loading 态 | 30 处 loading 引用、`v-loading` 3、`TaskProgress.vue:5` 进度条 | 11 处、`v-loading` **0** | 22 处、`v-loading` 3、`JobProgress` 空态 `:40` |
| 空/错误态 | `el-empty` 1、catch 31、ElMessage 28 | `el-empty` 2、catch 17、ElMessage 31 | `el-empty` 6、catch 23、ElMessage 25、axios 拦截器统一报错 `api/index.js:9-16` |
| 响应式 | 断点类 **0**，flex-wrap 8 | 断点类 3（`md:grid-cols-2 xl:grid-cols-3` `ExportWizard.vue:12`） | 断点类 **0**，flex-wrap 1 |
| 注释掉的代码块 | ~35 | ~24 | ~26 |
| TODO/FIXME | 0 | 0 | 0 |
| 遗留调试代码 | 无 | **有**：全局错误写 document.title + errorHandler，自带"验证后移除" `main.js:24-37` | 无 |
| 工程化加分项 | — | 构建产物 manualChunks 分包 `vite.config.js:27-34` | sessionStorage 会话镜像 + 后端对账 `stores/ai.js:25-52,248-301`；工具页面/步骤可用性兜底 `frontend-tools.js:12-38` |

## 若以它为 TS 重写蓝本

**A（deepseek-v4-pro）**
- 优点：① 功能面最全（多出配置定义 CRUD、数据浏览两页）；② AI↔工作区解耦最彻底——`workspaceBus`+`workspaceContract` 是唯一耦合点，删任一侧都可用（`docs/07:40-52`）；③ 交互最细（HITL 确认卡、思考链、工具四态卡、步骤点击态与状态机、任务中心 4s 自愈刷新）。
- 缺点：① 未装 `@element-plus/icons-vue`，却用 `<i class="el-icon-loading">`（`SpreadSheet.vue:9`）、图标靠 emoji；② SpreadJS 未接中文资源/Culture，缺 `axios/jszip` 依赖，zip 打包全走后端，前端 Excel 能力弱于 B/C；③ 手写 CSS/@apply 最多（183 行 scoped CSS），Tailwind 化不彻底，TS 重写时样式迁移面最大。

**B（glm-5.3）**
- 优点：① 前端 Excel 能力最厚（`utils/excel.js` 267 行含动态校验器/JSZip 打包），且已接 SpreadJS 中文 Culture；② 步骤壳与步骤体分离（`TaskWizard.vue` 只管步骤条+进度条，wizard/*.vue 管业务），重写时职责切分最清晰；③ 构建配置最工程化（manualChunks 分包、严格端口、统一 axios 拦截器）。
- 缺点：① AI 能力最薄——无思考链、无工具卡片、无 HITL，请求不带工作区上下文（`stores/ai.js:51`），只剩"对话 + activity 灯"；② 联动靠全局 EventSource 广播写 store，无契约层，AI 与工作区之间无可裁剪边界；③ 任务中心无分页、无自动刷新；还留着"验证后移除"的调试钩子（`main.js:24-37`）。

**C（kimi-k3）**
- 优点：① AI 协议最完整：reasoning/tool_run/tool_call/done 全事件 + 前端工具执行器与"页面/步骤可用性"三级兜底（`frontend-tools.js:12-38`），HITL 有确认与拒绝双路径；② 契约层最明确（`stores/workspace.js` 单一窄接口 + `registerPageHandler` 反向注册），并有 sessionStorage 镜像 + 后端对账的刷新恢复双保险；③ 样式最规范（Tailwind 工具类为主、preflight 关闭并注明、仅 18 行 scoped CSS，逐条说明保留原因）。
- 缺点：① 进度全靠 1s 轮询（`utils/job.js:9-29`），无 SSE 推送，实时性与请求量低于 A/B；② 任务列表只有 3 页、无分页/无自动刷新，且无 A 的配置定义与数据浏览页；③ 单文件最大（`ImportWizardView.vue` 422 / `export 424`）且向导状态散落在页面 ref + stepData 双写，重写时状态外提成本高于 A 的 store 方案。
