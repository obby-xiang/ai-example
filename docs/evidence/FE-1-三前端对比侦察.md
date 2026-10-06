# FE-1 三前端对比侦察（前端合流蓝本复议证据）

> 日期：2026-10-07　目的：DC-13 前端蓝本复议的证据附件（file:line 级）
> 对象：A=deepseek-v4-pro（原蓝本）/ B=glm-5.3 / C=kimi-k3 三个分支的 frontend/

## 表1 技术栈与规模

| 项 | A deepseek-v4-pro | B glm-5.3 | C kimi-k3 |
|---|---|---|---|
| 框架 | vue 3.5 + router + pinia + element-plus + SpreadJS 17.1.5 | 同 A + icons-vue + axios + jszip + spread-sheets-resources-zh | 同 B + dayjs + uuid |
| JS/TS | 纯 JS，0 个 .ts（11 vue/10 js） | 纯 JS，0 个 .ts（8 vue/8 js） | 纯 JS，0 个 .ts（8 vue/9 js） |
| 代码行 | 2933 | 2173 | 2669 |
| 页面 | 5 页（含配置定义 CRUD、数据浏览） | 3 页 | 3 页 |

## 表2 关键能力矩阵（证据 file:line）

| 能力 | A | B | C |
|---|---|---|---|
| AI SSE | 有（api/index.js:61-109） | 有（api/sse.js:5-59） | 有（api/sse.js:5-57） |
| 思考链渲染 | 有（AiPanel.vue:22-28） | **无** | 有（AiPanel.vue:44-47） |
| 工具卡片 | 有，四态（AiPanel.vue:29-47） | **无**（仅 activity 列表） | 有（AiPanel.vue:51-67） |
| HITL 确认卡 | 有（AiPanel.vue:48-55） | **无**（后端注释"无需 HITL"） | 有，确认+拒绝双路径（AiPanel.vue:68-85） |
| 联动契约层 | workspaceBus+workspaceContract（utils/:11-52,17-118） | **无**（全局 EventSource 广播，请求不带工作区上下文 stores/ai.js:51） | 窄接口 store + registerPageHandler（stores/workspace.js:21-96）+ 前端工具执行器（utils/frontend-tools.js:33-122） |
| 进度通道 | SSE（stores/exportTask.js:60-82）+ 任务中心 4s 轮询 | SSE（App.vue:86-88） | **1s 轮询**（utils/job.js:9-29，含行级明细） |
| SpreadJS | 授权无中文 Culture（main.js:13-15）；未装图标包 | 授权 + zh 资源/Culture（main.js:10-17）；utils/excel.js 267 行含校验器/JSZip | 授权 + Culture（main.js:16-20）；utils/excel-io.js 388 行；tailwind preflight 关闭 |
| 任务中心 | 筛选+分页+4s 自动刷新+一键恢复（TasksView.vue:18-154） | 筛选，无分页无自动刷新 | 新建/取消/删除，无分页无自动刷新 |

## 表3 质量信号

| 信号 | A | B | C |
|---|---|---|---|
| 样式 | Tailwind + 36 处 @apply，183 行 scoped CSS（债最重） | 内联 Tailwind，101 行 scoped | 内联 Tailwind，仅 18 行 scoped（最规范） |
| loading/空/错误态 | 30 处 loading、catch 31 | 11 处、v-loading 0；**留调试钩子**（main.js:24-37"验证后移除"） | 22 处、el-empty 6、axios 拦截器统一报错 |
| 工程化 | — | manualChunks 分包（vite.config.js:27-34） | sessionStorage 镜像 + 后端对账刷新恢复（stores/ai.js:25-52,248-301） |

## 蓝本适配评估摘要

- **A**：功能面最全（多 2 页）、解耦最彻底（bus+contract）、交互最细；但样式债最重、SpreadJS 缺中文资源、缺 axios/jszip/icons 依赖。
- **B**：Excel 层最厚（动态校验+JSZip+中文 Culture）、向导壳体分离最清晰、构建最工程化；但 **AI 能力最薄**（无思考链/工具卡/HITL/上下文注入）、无契约层、任务中心无分页无自刷。
- **C**：**AI 协议最完整**（reasoning/tool_run/HITL 双路径 + 前端工具三级兜底）、契约最明确（窄接口+反向注册）、刷新恢复双保险、样式最规范；但进度靠轮询（无 SSE）、任务列表无分页、少 2 个功能页、向导状态散在页面 ref。

## 结论（DC-13 采纳）

主蓝本：**C（kimi-k3）**；部件级吸收：B 的 Excel 工具层/中文 Culture/向导壳体分离/构建配置；
A 的 workspaceBus 事件协议形态/SSE 进度通道（替换 C 的轮询）/配置定义页+数据浏览页/任务中心自动刷新。
