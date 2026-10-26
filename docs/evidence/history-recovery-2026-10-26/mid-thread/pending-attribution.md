# 归属无法判定 · 候选区（mid-thread 层，不计入项目成果）

> **定位**：本批候选面里 **无项目锚点、任务提示也不指向本项目具体代码/文档/专题** 的中间稿。它们可能是通用业界调研或工具环境排障，**不能当作本项目成果引用**。
> **处置**：只登记定位（会话 / agent / 消息序号）与 220 字摘要，**不落全文**；正文仍在 wire 中，需要时回取。**不计入主仓任何计数**。

| 链号 | 会话 / agent | m | 字符 | 主题 | 摘要（截断） |
|---|---|---|---:|---|---|
| `PA-001` | `164b4082` / `agent-413` | 7 | 2,616 | 结论 | 任务无法执行 —— **KimiCU 插件（kimi-cu-win）在当前宿主的 MCP 层启动失败，`js` 工具根本没有暴露给我**。按你的排障指引，我停在这里，不走 Powershell SendKeys 等绕过方案。 ## 结论 第一步就卡住了：本会话的工具清单里**没有 `select_tools`，也没有任何 `js` / `mcp__kimi-cu__js` / `mcp__kimi-cu-win__js` 工具**。可用 |
| `PA-002` | `164b4082` / `agent-413` | 44 | 3,388 | 更正上一轮的报告 | 任务完成 ✅ —— 这次 KimiCU 可用（我上一轮的结论是错的，已在下文更正），四步全部走完并有截图与磁盘证据。 ## 更正上一轮的报告 上一轮我判断「工具集里没有 `js` 工具」是**误判**——`mcp__plugin-kimi-cu-win_win__js` 一直在我的工具清单里，是我漏看了。实际状态：node 已升到 **v24.21.0**（满足插件要求的 ≥24.15.0），插件已重装（kimi-cu-win v0.3 |
| `PA-003` | `579204cf` / `agent-422` | 32 | 2,943 | 1. 发送是否成功：**否，零副作用** | 任务未能启动。核心结论：**CU 工具存在且观测通道正常，但输入直发通道被系统级阻断，任务卡未能发送，K2.8 未开工。** ## 1. 发送是否成功：**否，零副作用** - 工具 `mcp__plugin-kimi-cu-win_win__js` **确实存在且可用**（观测侧完全正常：`list_windows` / `get_window_state` / 截图 均正常返回）。 - 但**所有真实输入方法都在"激活窗口"这一步被 |
| `PA-004` | `b3eb8361` / `agent-906` | 13 | 5,302 | 1. 消息操作按钮：assistant 常驻 / user hover（ | 自证：`<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_b3eb8361/agents/agent-906/wire.jsonl:5` → 实际 **modelAlias = `DeepSeek/deepseek-flash`**（profileName=`explore`, thinkingEffort=`high`）。以下为业界调研全文（未写任何文件）。 --- ## 1. 消息操 |
| `PA-005` | `b3eb8361` / `agent-906` | 19,22,28 | 23,268 | 6. 长内容处理全家桶（逐类主流模式） | 自证沿用：`.../session_b3eb8361/agents/agent-906/wire.jsonl:5` → modelAlias = `DeepSeek/deepseek-flash`。追加三题调研全文如下（未写文件）。 --- ## 6. 长内容处理全家桶（逐类主流模式） - **a. 工具卡超长输出**：主流 = **默认折叠为一行可扫读摘要 + 点击展开**，而非"展开后长内滚"；VS Code 1.107 起"clo |
| `PA-006` | `cbdb20ea` / `agent-959` | 33 | 9,339 | 调研结论（逐源） | ## 调研结论（逐源） ### 1. VS Code / GitHub Copilot agent mode **批准前**：确认弹窗显示工具名 + 输入参数，可点 chevron 展开细节；Local harness 与扩展工具支持在弹窗内直接改参数，generic 与 terminal 工具的确认是只读的（要改就拒绝后让 agent 重发）— [Use tools with agents](https://code.visualst |
| `PA-007` | `cbdb20ea` / `agent-959` | 52 | 4,417 | 各产品失败态的命名与文案 | ## 各产品失败态的命名与文案 ／ 产品 / 层 ／ 失败态英文名 / 文案 ／ URL ／ ／---／---／---／ ／ **VS Code（Copilot agent）** ／ UI 字面量 **"Tool execution failed"**（`localize('toolExecutionFailed', …)`），只在工具报错且原消息无实质内容时替换显示；同时给该 tool call 加 `chat-tool-call-e |
| `PA-008` | `cbdb20ea` / `agent-959` | 99,111 | 11,706 | 逐源表 | ## 逐源表 ／ 产品 ／ 场景 ／ 限高值 / 行数 ／ 证据 ／ ／---／---／---／---／ ／ **VS Code** ／ 工具卡片里的说明正文（超长折叠为“See more”） ／ **96px**（`max-height: 96px` + 底部渐变 + See more） ／ [chat.css#L907](https://github.com/microsoft/vscode/blob/main/src/vs/wor |
| `PA-009` | `ced79706` / `main` | 130 | 2,007 | 业界主流怎么做的（调研要点，67 处出处已落 `_shared/调研-A | 两路调研齐活。差距分析与方案如下，请你裁决： ## 业界主流怎么做的（调研要点，67 处出处已落 `_shared/调研-AI可观测性业界惯例-DS-Flash.md`） - **标准化方向 = OpenTelemetry GenAI 语义约定**（`gen_ai.*`）：span 树 `invoke_agent → chat / execute_tool`，指标有 `time_to_first_chunk`、token counter |

合计 9 链 / 64,986 字符。
