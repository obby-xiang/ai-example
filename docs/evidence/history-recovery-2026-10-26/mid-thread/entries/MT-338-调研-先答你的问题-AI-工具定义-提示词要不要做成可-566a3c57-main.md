<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-338 · 调研 · 先答你的问题：AI 工具定义 / 提示词要不要做成可配置的？（调研结论）（566a3c57 / main）

> **项目归属**：**上下文推定**（正文无路径锚点；依据该 agent 任务提示与项目作业面判定，入库前建议补锚点）。
> **归属依据**：任务提示指向本项目、正文为项目作业面；**无路径/类名锚点**（故标上下文推定）。
> **稿态**：历史中间稿 —— 该 wire 第 `m81` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt30` 条有正文的 assistant 消息（共 91 条有正文），末条有正文消息为 `m271`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `566a3c57` / agent `main`（`main` = 主会话）；原字符 2,224（链内 1 段、合计 2,224）。
> **链号 / 类别**：`MT-338` / **调研**；判定：助手调研记录（历史调研，非当前结论）；**不是用户批准**。
> **轮次**：位于该 wire 第 19 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `0%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `0%`。
> **与末条归档（138 件）的关系**：末条 `m271`（718 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

## 先答你的问题：AI 工具定义 / 提示词要不要做成可配置的？（调研结论）

**一句话：不需要再做深。本仓现状已经踩在业界主流形状上，继续「配置化」反而是逆主流。**

### 业界主流形状

**工具定义 = 代码锚定，没有「搬进 DB」的主流做法。** Spring AI（`@Tool`）、LangChain（`@tool`）、MCP server 全是代码声明 schema+handler。被外置的只有给模型看的文案（description/title/examples）。MCP Registry 这类注册表是**治理面**（发现/owner/审批/allowlist），明确不存工具本体、不在请求路径上（[Kong](https://konghq.com/blog/learning-center/what-is-an-mcp-registry)、[MCP 官方](https://blog.modelcontextprotocol.io/posts/2025-09-08-mcp-registry-preview/)）。工具规模化的主流解法是**运行时收窄/检索**而不是「让运营改定义」——Anthropic 实测 58 个工具 ≈55K tokens，Tool Search 砍到 8.7K（[Anthropic](https://www.anthropic.com/engineering/advanced-tool-use)），本仓的 `ToolRegistry.forContext` 动态披露正是这条路。

**提示词 = 「代码内嵌 + Git」是默认，专用平台是规模化解法。** 最强信号：OpenAI 官方文档明确建议生产提示词放在应用代码里（类型安全、code review、测试、正常部署流程），并关停 `v1/prompts` 平台（2026-11-30）（[OpenAI](https://developers.openai.com/api/docs/guides/prompt-engineering)）。LangSmith/Langfuse/MLflow 这类平台卖的不是版本控制（Git 已有），而是**版本↔trace↔eval 联动、环境晋升、非工程角色编辑**——且这个品类正在震荡（Humanloop 关停、Promptfoo 被收购等）。判据：2-3 个工程师、<5 条 prompt、变更 <每周一次 ⇒ Git 足够，本仓全中。

**变更门禁的共识**：提示词/工具描述变更 = 代码变更（PR + review + eval + 部署），文本 diff 不够、要 eval 判据；禁止「保存即生效」（不可变版本 + label 指针三家一致）；「权限在代码里，不在提示词里」（Replit 删库事件里提示词写了 code freeze 照样被删）。

### 对照本仓的三档建议

- **维持**：`tools.yaml` 只覆盖文案（别把 inputSchema 用起来）；`SYSTEM_PROMPT` 留代码里（22 条是代码闸门的说明书，被测试逐字钉住，外置会削弱「改实现必须改说明书」的强制力）；工具定义 DB 化维持不做。
- **小步增强**（低成本，可排期）：①22 条拆成具名常量块再拼（内容一字不改，diff 局部化）；②给 SYSTEM_PROMPT 加版本号+变更理由；③tools.yaml version 加 CI 校验（改了文案忘升版本即告警）；④补一份工具描述 eval 最小集（10 条真实话术 → 期望工具名）。
- **立项触发条件**（出现才做）：工具 >30 个或定义 >10K tokens（先做检索增强，不是 DB）；prompt 变更 >每周 1 次（拆 classpath 资源仍走 Git）；非工程角色必须直接改描述（前置 = eval 门禁就绪）；接入外部 MCP server（立即做 hash pin，安全项）。

调研全文 168 行带一手来源清单，建议按惯例入库 `docs/research/`（索引第 59 件），我收口时一并提交。**要我把「小步增强」4 条排进待办吗？** 还是先只留调研件。

---

**P7（E2E 存量红收敛）交付**：两遍全量 **PASS=166 / FAIL=0、批① 130/130、exit 0**，后端 **1708 例全绿**。21 条逐条归类（断言过期 11 / 断言逻辑错 2 / 用例构造错 1 / 夹具命题不成立 1 / 客户端缺陷 1 / **产品缺陷 2**），MAJOR-2 时序 flake 已加固（有界条件等待）。顺手修掉两个真产品缺陷：**B-KB-1 循环依赖**（`ToolResultLimiter` 改 `ObjectProvider` 延迟取，KB 三工具恢复注册 37）和 **X-06**（`AiChatRequest.Context` 未知键 500 → 降级不拒绝）。

**P12（桩 reasoning 形态 + @stub 版 S-15 + @gf2 接入 CI + K-03 随迁 + @real C-13 对齐）已派出**，是最后一路施工；回来后我做集成收口（分笔提交 + 联合门禁 + 看板回写 + 新挂账登记）。
