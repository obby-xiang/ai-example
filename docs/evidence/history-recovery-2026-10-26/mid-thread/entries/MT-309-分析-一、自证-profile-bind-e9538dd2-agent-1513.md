<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-309 · 分析 · 一、自证 profile.bind（e9538dd2 / agent-1513）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`generative_form`、`AiTools.java`、`frontend/src/`。
> **稿态**：历史中间稿 —— 该 wire 第 `m99` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt11` 条有正文的 assistant 消息（共 15 条有正文），末条有正文消息为 `m186`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `e9538dd2` / agent `agent-1513`（`main` = 主会话）；原字符 3,539（链内 1 段、合计 3,539）。
> **链号 / 类别**：`MT-309` / **分析**；判定：助手分析/诊断（历史稿）；**不是用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `7%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `2%`。
> **与末条归档（138 件）的关系**：末条 `m186`（3,247 字符）未入末条归档。
> **脱敏**：路径 2 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

验证完成，报告已落盘。以下为交接内容。

## 一、自证 profile.bind

我自己的 wire（`.../session_e9538dd2-.../agents/agent-1513/wire.jsonl:6`）第 6 条记录：

```
type=profile.bind  agentId=agent-1513  modelAlias=DeepSeek/deepseek-flash
profileName=coder  thinkingEffort=high  systemPromptLen=22699
```

（该文件是唯一含我本轮独有工具调用文本「List probe equipment」的 wire，可据此确认 agentId 归属。）

## 二、环境与方法

- 后端 `127.0.0.1:8081`（真模型 deepseek-flash）、前端 `127.0.0.1:5202`，常开未重启；`git log` HEAD=`0e8bc7c`，仓库有他人在途改动，我**只读未写**。
- Playwright(chromium 1500×980) 驱动真实 UI，全部经**自然语言输入框**发起；旁证用 `/api/ai/health`、`/api/ai/history/{sid}`、`/api/tasks`、`/api/data/*`，并抓 `POST /api/ai/chat` 请求体核对页面上下文。
- 证据目录 `<LOCAL_WORK>/_rg-probe/visop/`：**35 张 PNG + 9 组 evidence.json + report.md（243 行）**；脚本 `vo-lib.mjs` + 9 个场景脚本可直接复跑。

## 三、13 项结论（11 必验 + 2 附）

| # | 项 | 判定 | 关键证据 |
|---|---|---|---|
| 1 | 可视 `get_workspace_state/get_row_count/list_config_defs` | **PASS** | CURRENCY=5 行、定义 15 个，与 REST 逐项一致；回答里的「最近动作」与请求体 `extra.recentActions` 逐字吻合 |
| 2 | 导航 `navigate_to` | **PASS** | `/definitions`→`/tasks`、`→/export` 真实跳转；`list_tasks` 48 条=`totalElements` |
| 3 | 选择/条件 | **BLOCKED** | 400，勾选数/条件区无变化 |
| 4 | 步骤推进 | **BLOCKED** | 400，步骤条仍 `is-process` 在第 2 步 |
| 5 | 表格读 | **BLOCKED** | 在 `task:EXPORT/EXPORT` 上下文下同样 400 |
| 6 | 表格写 | **BLOCKED（双因）** | 写闸默认关 + 页面 400；模型如实说「没有给我 update_rows/replace_values」，`count` 实验前后都是 5 |
| 7 | GF 表单 | **BLOCKED** | 即根因本身 |
| 8 | HITL 确认卡 | **BLOCKED** | 「启动导出」只得 400，无确认卡 |
| 9 | 上传交接 | **BLOCKED（子层 PASS）** | 上传区 3 个 `data-ai-ref` 注册且可用，AI 请求 400 |
| 10 | 作业联动 | **BLOCKED（AI 侧）** | 手点导出：5/5 成功、任务行 100%、`finishedAt 20:54:23` 逐秒对齐 |
| 11 | KB 问答 | **PASS** | `kb_lookup_term→kb_search→kb_read_doc×2`，答案逐段带出处 |
| A | UI 原语 `ui_*` | **FAIL（覆盖）** | 全站仅 `import.upload` 一个区域注册；其余页面快照恒「0 区域/0 元素」；模型明确拒点不盲点 |
| B | 诚实性/披露裁剪 | **PASS** | 无 ref 不点、无写工具不谎报，还纠正了「REGION 不是定义只是层级」 |

## 四、根因（唯一）

`generative_form` 披露的页面，`POST /api/ai/chat` 在**请求组装阶段**被上游拒收：

```
Invalid schema for function 'generative_form': Pointer '/$defs/FormCondition' does not exist
```

GF 优化批把参数改成嵌套 record（`AiTools.java:556-650` `FormSpec→FormField→FormCondition`），victools 把 `$defs` 落在**参数级子 schema**（`properties.form.$defs`），而 `$ref` 从**文档根**解析 ⇒ 指针悬空 ⇒ 400。与工作区里未提交的 `ToolSchemaNormalizer.java:1-30` 所描述的机理完全一致，但**运行中的 HEAD 构建没有它**。

实测 **10 次尝试 10 次 400**：`/tasks`×1、`/export?taskId=579`×6、`/export?taskId=578`×2、`/import?taskId=609`×1。`/definitions`、`/data` 不披露 GF ⇒ 全部正常（4 个 PASS 全来自这两页）。

## 五、额外发现（报告里都有出处）

1. **可操作面覆盖率是独立缺口**：即使修好 schema，导出向导的表格/勾选/步骤条也没注册 `data-ai-ref`，`ui_*` 只有上传区 1 个区域可用 → 建议单独立项，不要和 GF 回归混为一谈。
2. **披露集在请求边界冻结**：从 `/definitions` 要求「选中 CURRENCY」时模型只能先导航，而导航后本轮的 `select_definitions` 仍不在集合内 ⇒ 必须「先站到向导页再问」，恰好就是坏页——修 schema 才能解。
3. 400 后原提问文本会回填输入框（气泡 + 草稿双份），与批G 409 的观感同类，供参考。
4. 写闸没有运行时开关，唯一打开方式是 `app.ai.sheet.write-enabled=true` + 重启（`application.yml:367-370`）；本次按约束不重启，故记 BLOCKED 而非 FAIL。

## 六、需要你知道的两点风险

- **验证期间前端被并发改动**：`20:47:03` `frontend/src/stores/ai.ts` 被外部写入，Vite HMR 报 `does not provide an export named 'useAiStore'`，导致第一轮向导矩阵中 `B-hitl` 那次没拿到错误卡（页面被 HMR 重载）。该组已整组重跑（`w2-wizard-blocked.mjs`，6/6 稳定 400），旧截图已删除，报告采用重跑结果。
- **我产生的副作用数据**：新建导入任务 **#609**；对任务 **#577** 触发过一次真实导出作业（job 609，5/5 成功，任务转 COMPLETED）。如需纯净可删 #609；#577 的作业记录与文件请按需清理。

## 七、交付物

- 报告：`<LOCAL_WORK>/_rg-probe/visop/report.md`（矩阵 + 逐项实证 + 根因 + 修复后 12 条回归清单，含可直接照抄的 prompt）
- 复跑脚本：`vo-lib.mjs`、`v1-visible.mjs`、`n1-nav.mjs`、`w2-wizard-blocked.mjs`、`k1-kb-ui.mjs`、`u1-upload.mjs`、`g1-write-gate.mjs`、`j1-jobpanel.mjs`、`j2-export-run.mjs`、`s1-exportstep.mjs`

未完成项：无（11 项均有明确判定；阻塞项按你给定的口径标 BLOCKED 并写明根因与开关口径）。
