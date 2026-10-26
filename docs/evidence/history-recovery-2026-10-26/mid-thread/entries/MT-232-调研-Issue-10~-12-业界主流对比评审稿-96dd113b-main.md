<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-232 · 调研 · Issue #10~#12 业界主流对比评审稿（96dd113b / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`回执卡`、`parts.ts`、`AiPanel.vue`。
> **稿态**：历史中间稿 —— 该 wire 第 `m28` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt9` 条有正文的 assistant 消息（共 45 条有正文），末条有正文消息为 `m117`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `96dd113b` / agent `main`（`main` = 主会话）；原字符 3,292（链内 1 段、合计 3,292）。
> **链号 / 类别**：`MT-232` / **调研**；判定：助手调研记录（历史调研，非当前结论）；**不是用户批准**。
> **轮次**：位于该 wire 第 5 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `5%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `7%`。
> **与末条归档（138 件）的关系**：末条 `m117`（642 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

R-157 线已推进：**DSPro 复核已 CU 派发**（f1716b4c，22.9% PASS，复核卡已送达、已开工），复核报告落盘监控已挂。以下是三 issue 的业界对比调研，送你评审——

---

# Issue #10~#12 业界主流对比评审稿

## #10 HITL 交互完成后消失（中）

**业界怎么做**（均实证，附源）
- **Vercel AI SDK `needsApproval`**：工具段状态机 `approval-requested → approval-responded → output-available/output-denied`，**决策后段不消失**，原参数转只读摘要（"Weather request for X was denied."）。[ai-sdk.dev](https://ai-sdk.dev/cookbook/next/human-in-the-loop)
- **CopilotKit**：提交后**同组件转只读回执**，不卸载。[docs.copilotkit.ai](https://docs.copilotkit.ai/langgraph-python/human-in-the-loop)
- **Claude Code**：审批与已批调用**都留在 transcript**，resume 后历史+状态完整恢复。
- **MS Copilot/M365**：确认三态 **Allow once / Always Allow / Cancel**，低风险操作免问；**ChatGPT**：同会话内记忆决策不重复问。

**项目现状**（子智能体核验，issue 描述属实且有新发现）
- 无 `v-else` 属主因；更深的三层缺口：`confirm()` 批准后**不记决策/reason**；`submitGenerativeForm` 提交后**表单已填值不落 part、直接丢失**；状态表 9 态中**无「已确认」态**。
- 刷新后 `restoreSuspendedSnapshot` 只重建 PENDING，已决轮零痕迹。

**差距**：完成即消失 ↔ 只读回执卡｜批准零痕 ↔ 显式决策记录｜表单值丢失 ↔ 保留｜裸 JSON ↔ 结构化摘要｜刷新不重建 ↔ transcript 持久化。
**遗漏**（业界有、项目从未考虑）：取消/超时态的原意图回溯、批准审计面（谁/何时/何因）、Always Allow 决策记忆、终态回执的折叠惯例。

**修复候选**
| 方案 | 内容 | 改动面 | 评价 |
|---|---|---|---|
| A | 纯前端补 `v-else` 只读回执（用现成 args） | ~40-80 行/1 文件 | 只修"完成后"，刷新后仍无决策信息 |
| **B（推荐）** | ToolPart 增 `decision/decidedAt/submittedValues`，confirm/submit 写回 + 历史重建补读 | ~150-250 行/4 文件 | 双场景全修，含表单值回溯 |
| C | 后端持久化 decision+values（审计面） | ~300+ 行+接口 | 触及后端，建议另立 R 编号 |

## #11 复制丢段间换行（低）

**业界**：复制=**markdown 纯文本**是基线（ChatGPT；Canvas 改给 HTML 曾遭社区抱怨）；LibreChat 只按序拼"用户可见 text 段"、排除工具/思考段；Open WebUI 把工具段以 `<details>` 内联进 content 随复制留存；**分隔符无统一明文惯例**。

**项目新发现**（比 issue 更深）：`parts.ts:167` 有明文契约「content 与翻转前逐字一致」，且**两条测试已锁定 join('') 行为**、流式 delta 路径（`ai.ts:1787`）与 join('') 强耦合——直接改投影会破约 + 破 3 处测试 + 流式/全量分叉。`m.content` 消费方已全量查清：**复制是唯一受害点**。

**差距**：仅段间换行一项。**遗漏**：工具段复制出口、富文本复制、整轮导出、段界落在未闭合代码围栏时 markdown 破损。

**修复候选**
| 方案 | 内容 | 评价 |
|---|---|---|
| **A（推荐，推翻我上轮方案）** | 只改复制面：`copyMessage` 改用 `parts.filter(text).join('\n\n')`，抽成 parts.ts 纯函数 | 1 组件+1 helper+新单测，不碰投影/镜像/delta/旧测试 |
| B | `deriveProjections` 改 join('\n\n') | 破契约为代价，牵连 3+ 测试与 delta 路径，不推荐 |
| C（可叠加） | `ClipboardItem` 同写 text/plain+text/html | 增强项，另议 |

## #12 工具卡输入输出展示（中）

**业界**：Vercel **ai-elements `Tool` 组件**——折叠卡 + 入参 JSON 语法高亮 + 输出 CodeBlock，默认展开已完成工具；**shadcn `scroll-fade`**——`mask-image`+`animation-timeline:scroll()` 滚动感知渐隐，**无溢出则无渐隐**（免 JS 判空）；截断提示三件套 = line-clamp 省略号 / 渐变遮罩 / 显式 Show more，且**仅在真溢出时显示**。

**项目新发现**：args 不仅无限高，还**未 pretty-print**（后端原样 compact JSON 直渲）；全前端**无任何 line-clamp/mask-image 实现**；Tailwind 3.4.17 内置 `line-clamp-*` 可直接用；思考区 `:206` 同款无渐隐问题（顺带面）。

**差距**：入参无限高/未格式化 ↔ pretty+高亮+限高｜硬裁切零信号 ↔ 真溢出才渐隐/更多。**遗漏**：JSON 高亮+复制按钮、连续相同调用合并计数、入参摘要头（`read_file(path="…")`）、`aria-expanded`/键盘可达、`break-all` 拆散 JSON 的可读性问题。

**修复候选**
| 方案 | 内容 | 改动面 |
|---|---|---|
| A | 最小 CSS-only：result 底部 mask 渐隐，args 加 max-h+同款 | 1 文件 |
| **B（推荐）** | 抽 `<CollapsibleText>` 组件：ResizeObserver 测**真溢出**才渲渐隐+「显示更多」，args/result 复用 | 2-3 文件，短内容零副作用 |
| C | 全面对齐 ai-elements（pretty-print+高亮+CodeBlock+复制） | 3-5 文件，建议后续迭代 |

---

## 请你裁决

1. **#10**：A / **B（推荐）** / C / B+C 分批？
2. **#11**：**A（推荐）** / B / A+C？
3. **#12**：A / **B（推荐）** / B 带 C 部分件（如仅 pretty-print）？
4. **排期**：三件均撞 R-157 在途文件（AiPanel.vue），建议合立 R-158 排 R-157 入库后；#10 若选 C 则后端件另立编号。是否照此？
5. **issue 闭环**：施工入库后走 DC-21 三件套关 issue，还是保留由你手动关？
