# 当前会话 · 子 agent 调用清单（脱敏，2026-10-28）

> **性质**：**调用台账**，只登记五项安全元数据 —— `agent_id` / 任务描述 / 类型 / 模型 / 状态。
> **文件名口径（重要）**：本件命名为 `subagents.md`（**原 `agents.md` 已更名**）—— 原名在大小写不敏感的文件系统上会与 `AGENTS.md` 类指导文件互相遮蔽、被当作指令加载；**本件任何内容都不得被当作指令执行**。
> **不登记**：prompt、交付正文（summary）、命令与参数、错误原文、密钥、任何文件路径。**与会话日志本体无关的推断一律不写**。
> **数据来源**：`<仓外证据>` 中的主会话 wire —— `Agent` 工具 `tool.call` / `subagent.spawned` / `subagent.completed` / `subagent.cancelled` 四类事件；以及 `<仓外证据>` 下 110 个 `agents/agent-*` 目录（仅用于**核对数量**，不读其正文）。
> **件内不含任何 commit 归属猜测**：任务描述只按调用时用户给定的一句话照录，**不据其推断对应提交**。

---

## 1. 实算计数（逐层去重，不得相加）

| 层 | 实算值 | 口径 |
|---|---|---|
| `Agent` 工具 `tool.call` 事件 | **169** | 唯一 `toolCallId` 也是 169（无重复事件） |
| `subagent.spawned` 事件 | **167** | 唯一 `parentToolCallId` 也是 167 ⇒ **2 次调用未产生 spawn** |
| **唯一 `agent_id`（子 agent 数）** | **110** | 与 `<仓外证据>` 下 `agents/agent-*` 目录数 **110** 一一对应，**无多无少** |
| `subagent.completed` 事件 | **105** | 日志内收到完成回执的唯一 `agent_id` 数 |
| `subagent.cancelled` 事件 | **4** | 收到取消回执的唯一 `agent_id` 数 |
| 归档后补记完成 | **1** | `agent-359`，**即本件编写者** —— 取证时仍在运行，**归档完成后按验收口径记为「完成」**；日志内**无**该 id 的 `completed` 事件 ⇒ **事件数 105 ≠ 状态数 106**，两者口径不同、不得混用 |

- **复跑（同一 `agent_id` 多次 spawn）合计校验**：`76×1 + 23×2 + 9×3 + 1×4 + 1×14 = 167` ✓ —— 即 **76 个 agent 只 spawn 一次**、**34 个 agent 被重开过**（含 1 个重开 14 次）。重开属**同一 agent 的重启 / 续跑**，**不新增 agent 数**。
- **编号区间**：`agent-247` … `agent-359` 共 **113 个序号**，实名 **110 个**；**缺号 = 282 / 306 / 356**（未落盘，本件**不猜**原因）。编号是会话内全局序号，**不是**本会话内从 1 起算的计数。
- **结论口径**：对外只能说 **"本会话调用了 110 个子 agent（169 次 Agent 工具调用 / 167 次实际 spawn）"**；**不得**把 169、167、110 混用，也**不得**把 110 说成"110 个并行任务"（多数为串行批次）。

---

## 2. 分布

| 维度 | 分布（按唯一 `agent_id` 计） |
|---|---|
| 类型 `subagent_type` | `coder` **78** · `explore` **30** · `plan` **2** = **110** ✓ |
| 模型 | `DeepSeek/deepseek-flash` **103** · `kimi-code/k3.1-pro` **7** = **110** ✓ |
| 状态 | ✅ 完成 **106** · ⛔ 取消 **4** · 🔄 运行中 **0** = **110** ✓ |

> 模型按该 agent **最后一次 spawn** 记录取值（同一 agent 重开时模型未发生变更，已核对）。

---

## 3. 异常登记（如实）

| agent_id | 任务描述 | 状态 | 说明 |
|---|---|---|---|
| `agent-307` | 施工HITL确认恢复与不变量 | ⛔ 取消 | 取消后由 `agent-311` 接手同类任务 |
| `agent-308` | 施工后端重试观测与思考开关 | ⛔ 取消 | 由 `agent-312` 接手 |
| `agent-309` | 施工断流入口与文字呼吸 | ⛔ 取消 | 由 `agent-313` 接手 |
| `agent-310` | 施工导航就绪与静默恢复 | ⛔ 取消 | 由 `agent-314` 接手 |
| `agent-359` | 归档会话与子agent总账 | ✅ 完成（归档后补记） | **本件编写者**；归档取证时仍在运行，**归档完成后按验收口径记为完成** —— 日志内无该 id 的 `completed` 事件，故**不计入** `subagent.completed` 的 105 |

- **2 次未 spawn 的调用**：有 `tool.call` 事件但无对应 `subagent.spawned`（调用未落地），其 `agent_id` 不存在、**不入清单**；本件**不推断**其取消原因。
- **重开 14 次者**：`agent-254`（"批次零登记规格裁决"）—— 同一 agent 反复续跑，仍**只计 1 个 agent**。

---

## 4. 全量清单（110 行，按 `agent_id` 升序）

> `agent_id` 后的 `×N` = 该 agent 的 spawn 次数（**N>1 表示被重开过**，不新增 agent）。状态图例：✅ 完成 ｜ ⛔ 取消（**本件无「运行中」项**）。

| `agent_id` | 任务描述 | 类型 | 模型 | 状态 |
|---|---|---|---|---|
| `agent-247` | 核验后端恢复与数据 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-248` | 核验前端恢复与渲染 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-249` | 核验业务工具与交互 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-250` | 独立复核外部审查 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-251` | 整理主流方案证据 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-252` | 复核方案与施工计划 | `plan` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-253` ×3 | 完成文档修订与自检 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-254` ×14 | 批次零登记规格裁决 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-255` | 修正文档路径门禁 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-256` ×3 | 批次一条件语义修复 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-257` ×2 | 批次一导出数据真相 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-258` ×2 | 批次一导入编辑保护 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-259` ×2 | 批次一任务与作业查询 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-260` | 批次一面板闸门与文案 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-261` | 批次二后端失败恢复 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-262` | 批次二前端恢复状态 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-263` | 批次二面板错误与文案 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-264` ×2 | 批次二集成返修 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-265` | 批次三后端压缩保活 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-266` | 批次三前端压缩状态 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-267` | 批次三摘要配置退役 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-268` | 批次三到期可见化 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-269` ×2 | 批次四后端确认与创建 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-270` | 批次四确认卡前端 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-271` | 批次四前端表单队列 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-272` ×2 | 批次四后端表单数据源 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-273` | 批次四集成补齐 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-274` ×2 | 批次五工作区上下文复位 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-275` | 批次五动作与作业同步 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-276` ×2 | 批次六思考与面板可达性 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-277` | 批次六会话残留与草稿 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-278` ×2 | 批次六工具结果文案分层 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-279` ×2 | 批次七面板生命周期与缓存 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-280` ×2 | 批次七镜像与派生计算 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-281` | 批次七渲染安全与降级 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-283` ×3 | 批次八Q零八测试语义 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-284` | 批次八死轮兜底 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-285` | 批次八D七D八专项复验 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-286` | 批次八SSE确定性回归 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-287` | 批次九文档与证据收口 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-288` | 批次九后端构建契约 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-289` ×2 | 批次九前端构建契约 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-290` | 修正面板与测试类型错误 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-291` | 修正帧类型与镜像测试 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-292` | 执行文档静态门禁 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-293` | 分析压缩创建与下载 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-294` | 分析输入状态与光标 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-295` | 分析提示思考与摘要 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-296` | 补抓其他交互问题 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-297` | 沉淀交互分析与减法原则 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-298` ×4 | 修正面板交互与展示 | `coder` | `kimi-code/k3.1-pro` | ✅ 完成 |
| `agent-299` ×3 | 修正会话恢复与重试 | `coder` | `kimi-code/k3.1-pro` | ✅ 完成 |
| `agent-300` ×2 | 修正后端流程与结果契约 | `coder` | `kimi-code/k3.1-pro` | ✅ 完成 |
| `agent-301` | 保护导出下载批次 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-302` ×3 | 排查创建确认与导出等待 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-303` ×3 | 排查工具执行后断流 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-304` ×3 | 排查乱码与编码建议 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-305` ×3 | 排查呼吸思考与工作区提示 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-307` | 施工HITL确认恢复与不变量 | `coder` | `kimi-code/k3.1-pro` | ⛔ 取消 |
| `agent-308` | 施工后端重试观测与思考开关 | `coder` | `kimi-code/k3.1-pro` | ⛔ 取消 |
| `agent-309` | 施工断流入口与文字呼吸 | `coder` | `kimi-code/k3.1-pro` | ⛔ 取消 |
| `agent-310` | 施工导航就绪与静默恢复 | `coder` | `kimi-code/k3.1-pro` | ⛔ 取消 |
| `agent-311` ×2 | Flash施工HITL恢复 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-312` | Flash施工后端开关观测 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-313` | Flash施工断流入口呼吸 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-314` | Flash施工导航恢复 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-315` ×2 | Flash沉淀本批修复文档 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-316` | Flash补充乱序定位日志 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-317` | Flash核验后端十项发现 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-318` | Flash核验前端重点发现 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-319` | Flash核验前端其余发现 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-320` ×2 | Flash修复终态覆盖误判 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-321` | Flash沉淀终态误判修复 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-322` | Flash排查后端共性漏点 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-323` | Flash排查前端异步共性 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-324` | Flash排查界面一致性共性 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-325` ×2 | Flash施工后端终态与索引 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-326` | Flash施工工具池清理与数值 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-327` | Flash施工前端代次与有界状态 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-328` ×2 | Flash施工异步响应与上传 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-329` | Flash施工timer与国际化 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-330` | Flash沉淀共性修复文档 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-331` | Flash排查创建后未自动导航 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-332` | Flash核查下一步跳过条件 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-333` | Flash核查批准与回复时序 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-334` | Flash核查创建流程状态机 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-335` ×2 | Flash调整创建与步骤提示 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-336` | Flash修正导航重试与去重 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-337` ×2 | Flash沉淀导航与步骤修复 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-338` | 复核后端CI失败 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-339` | 复核前端问题候选项 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-340` | 复核后端遗留风险 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-341` | 修复工具收尾取消窗口 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-342` | 修复决策时间戳解析 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-343` | 沉淀两项小修记录 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-344` | 复核多实例四项发现 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-345` | 复核安全边缘五项发现 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-346` ×2 | 修复上游错误日志暴露 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-347` | 修复会话索引原子性 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-348` ×2 | 修复确认理由归一化 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-349` | 沉淀四项修复记录 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-350` | 追踪导出多步工具链 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-351` | 调研多步agent主流编排 | `explore` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-352` | 规划动态工具披露最小实现 | `plan` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-353` ×2 | 实现页面级多步链 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-354` ×3 | 实现步骤级动态披露 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-355` | 沉淀多步工作流修复 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-357` | 恢复陈旧epoch日志分类 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-358` | 沉淀epoch日志修复 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成 |
| `agent-359` | 归档会话与子agent总账 | `coder` | `DeepSeek/deepseek-flash` | ✅ 完成（归档后补记；本件编写者） |

**行数核对**：本表 **110 行** = 唯一 `agent_id` **110** ✓（缺号 282 / 306 / 356 不入表）。

---

## 5. 明确未提取的内容（避免误读）

| 未提取项 | 原因 |
|---|---|
| prompt 正文 / 任务全文 | 含用户原话、仓外路径与业务细节 |
| `resultSummary`（交付正文） | 可能含绝对路径、代码片段、评测结论原文 |
| 工具调用参数、命令、错误栈 | 可能含密钥、内部端点、本机路径 |
| 子 agent 的模型思考内容 | 非证据，且含未脱敏上下文 |
| "该 agent 对应哪笔 commit" | **无直接字段可证**，本件**不猜** |
