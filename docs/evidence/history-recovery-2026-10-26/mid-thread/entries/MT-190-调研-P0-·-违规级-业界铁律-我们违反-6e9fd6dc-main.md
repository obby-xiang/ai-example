<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-190 · 调研 · P0 · 违规级（业界铁律，我们违反）（6e9fd6dc / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`AiPanel.vue`、`generative_form`。
> **稿态**：历史中间稿 —— 该 wire 第 `m42` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt23` 条有正文的 assistant 消息（共 39 条有正文），末条有正文消息为 `m69`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `6e9fd6dc` / agent `main`（`main` = 主会话）；原字符 2,858（链内 1 段、合计 2,858）。
> **链号 / 类别**：`MT-190` / **调研**；判定：助手调研记录（历史调研，非当前结论）；**不是用户批准**。
> **轮次**：位于该 wire 第 22 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `4%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `0%`。
> **与末条归档（138 件）的关系**：末条 `m69`（309 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

调研已全部在手，无需再查。以下是**完整差距清单**（六轮调研汇总：本仓实证 + 14 家业界一手来源对照）：

## P0 · 违规级（业界铁律，我们违反）

| # | 差距 | 本仓实证 | 业界铁律 |
|---|---|---|---|
| 1 | **历史还原丢序**：刷新后正文恒前置、工具调用全部堆尾 | `stores/ai.ts:708-711` 恒拼 `[text, …toolCalls]` | 有序 parts/事件数组 = 唯一真相，历史重放保序（AG-UI 明示"到达序即协议序"、Vercel AI SDK `parts[]`） |
| 2 | **截断标记不消费**：截断文本看起来就像完整内容，无省略号、无展开 | 后端 `ToolResultLimiter.java:56-61`（8000字符/200行）+ `FrameSizeLimiter.java`（单帧64KB）都挂了 `truncated` 标记，前端 grep `truncated` **0 命中** | 截断属运行时层且**必须 in-band 可见**，UI 层做 Show more（Claude Code/Gemini/OpenHands/Codex 四家同） |

## P1 · 重大缺失

| # | 差距 | 实证/对照 |
|---|---|---|
| 3 | **无 markdown/代码高亮**：正文纯文本插值，零渲染依赖 | `AiPanel.vue:167`；业界 GFM+KaTeX+Mermaid 流式安全渲染家家有（LobeChat/assistant-ui/OpenWebUI） |
| 4 | **token/耗时/模型信息白送不显示**：done 帧的 usage/model/attempts、retry 次数、heartbeat、message chars 等 **8 项帧内语义已发不消费** | `stores/ai.ts:1751-1911` 有 case 但不渲染；业界 assistant-ui `durationMs` 等多数有 |
| 5 | **无"上滑暂停跟随"**：自动滚动只会钉底 | `AiPanel.vue:671-678` 裸 `scrollTop=scrollHeight`；assistant-ui 等主流有锁定策略 |
| 6 | **消息内无错误位**：错误全堆顶部 alert 区 | `AiPanel.vue:52-114`；业界在工具卡/消息内就地显示错误态 |
| 7 | **流式与历史无视觉区分**：同一列表渲染，无分隔无标签 | `AiPanel.vue:141-160` |

## P2 · 主流标配缺失

| # | 差距 | 对照 |
|---|---|---|
| 8 | 推理块空转：前端有折叠分支，**后端从不发 reasoning 帧** | 业界 reasoning 独立 role + 默认摘要 + 加密回传续接（AG-UI/Claude） |
| 9 | **无消息级操作栏**：全组件只有一个 hover 复制按钮 | 业界 action bar = 复制/重新生成/赞踩/编辑/朗读/导出（assistant-ui/AI Elements/CopilotKit） |
| 10 | 工具卡内容不可复制（复制只取正文段） | — |
| 11 | 无重新生成 / 消息级重试发送 | 主流均有 |
| 12 | 无建议提示词（suggested prompts/starters） | assistant-ui SuggestionAdapter、Perplexity 后续问题 |
| 13 | 工具组完成态摘要只有"N 段"计数 | Claude Code 式内容摘要"Edited 5 files +27 −23" |
| 14 | 组内单个工具卡不能各自展开 | assistant-ui 双层（组折叠+单卡再折叠） |
| 15 | 无图表/表格渲染（工具结果纯文本灰框） | A2UI 组件目录含卡/图/地图 |
| 16 | 历史无分页，单次全量 GET | 主流分页/虚拟滚动 |
| 17 | 时间戳字段存在但不渲染 | `types/ai.ts:142` |
| 18 | 可访问性缺口：折叠头无 `aria-expanded`、流式区无 `aria-live`、无快捷键 | — |

## P3 · 业界差异化能力（我们完全没有）

| # | 能力 | 业界参照 |
|---|---|---|
| 19 | diff 视图 + checkpoint 逐文件回退 | Cline |
| 20 | 附件上传 / 语音输入 | assistant-ui / Copilot |
| 21 | 编辑已发消息 → 分支会话 | Claude.ai / assistant-ui BranchPicker |
| 22 | Artifact 面板（一等对象+版本迭代） | Claude / OpenWebUI / ChatGPT canvas |
| 23 | 子智能体归属显示 | AG-UI `subagentRunId` |
| 24 | 批量审批 / auto-approve 范围 | Cline / VS Code |
| 25 | 图片/媒体消息段 | 主流均有 |
| 26 | 引用脚注/来源卡 | Perplexity |
| 27 | mermaid / 数学公式 | LobeChat / assistant-ui |
| 28 | composer 形态：无容器/阴影（仅 border-t）、无斜杠命令、无 @ 提及 | 业界胶囊式 composer + 斜杠命令 |

## 不落后 / 亮点（同样给你看全）

- **HITL 审批卡**：倒计时+拒因+双按钮，与业界（AG-UI interrupt/resume、CopilotKit renderAndWaitForResponse）同档
- **generative_form 表单**：六型控件、宿主持组件目录路线——与业界最先进的 Google A2UI / MCP Apps **同构**，方向完全正确，只是只有表单一型
- 工具九态 tag、连接降级三态、组折叠本身（组折叠=主流默认，上轮已证）、头像+用户右/AI 左气泡、15 类 SSE 帧类型层零缺口（缺的是帧内语义消费）

---

**处置参考**（仍待你裁）：P0 两项与 R-144 同域可收编；#3 markdown 与 #4 白送字段是性价比最高的两个独立项；P2/P3 适合攒一个"对话面板 UX 批"排期。全部七件调研已沉淀 `docs/research/`（`06d95e7`），后续派工卡强制注入。
