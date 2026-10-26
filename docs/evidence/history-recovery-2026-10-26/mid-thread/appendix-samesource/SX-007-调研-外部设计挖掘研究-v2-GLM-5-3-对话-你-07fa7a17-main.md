<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# SX-007 · 调研 · 外部设计挖掘研究 v2：GLM-5.3 对话（你执行）（07fa7a17 / main）

> **项目归属**：**同源 ai-example 分支/评测附录**（不计入主仓成果）。
> **归属依据**：会话属 ai-example 分支合流/早期分支评测（非本主仓）。
> **稿态**：历史中间稿 —— 该 wire 第 `m317` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt139` 条有正文的 assistant 消息（共 151 条有正文），末条有正文消息为 `m337`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `07fa7a17` / agent `main`（`main` = 主会话）；原字符 2,520（链内 1 段、合计 2,520）。
> **链号 / 类别**：`SX-007` / **调研**；判定：助手调研记录（历史调研，非当前结论）；**不是用户批准**。
> **轮次**：位于该 wire 第 66 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `2%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `2%`。
> **与末条归档（138 件）的关系**：末条 `m337`（617 字符）未入末条归档。
> **脱敏**：路径 5 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 3 处。

---

三份源码已就位（commit 钉死、各带导读）。升级版研究提示词——

---

# 外部设计挖掘研究 v2：GLM-5.3 对话（你执行）

**模型：GLM-5.3，全新对话**，整段复制：

```text
你是资深架构研究员。任务：基于外部设计原料摘录与三个开源仓库的源码，对 ai-example
合流项目的 AI Runtime 架构做设计挖掘与差距分析，产出借鉴清单。
你在 Windows（Git Bash）下工作，只读+写报告，禁止任何 git 写操作，禁止修改被分析文件。

【协作纪律（重要）】你是本任务的分析师，不是体力工：凡涉及大文件阅读、批量源码
检索、逐文件核对的粗活，一律派你的子智能体（DeepSeek V4 Flash）去做并回收结论；
你自己只做问题定义、结论研判与报告撰写。子智能体提示词要自包含。

【你的分析对象（我方架构，都在 <LOCAL_WORK>/<REPO>/）】
- docs/adr/DECISION-CARDS.md（12 张 ADR）
- docs/adr/DECISION-REGISTER.md（DC-01~13 约束）
- docs/s4/S4.2-AI-Runtime-合流规格.md（四机制集成时序）
- docs/03-技术方案文档-v2.1.md（总体架构）
- 实现现状：backend/src/main/java/com/example/configmgr/ai/（只读，
  大文件阅读交给子智能体摘要）

【外部原料（文档摘录层）】
- docs/research/raw-copilotkit.md / raw-ag-ui.md / raw-ai-sdk.md

【外部原料（源码层，本地已检出，commit 已钉死；各目录有 README-导读.md 导航）】
- <LOCAL_WORK>/research-src/copilotkit/（commit 73af440，react-core/runtime 等 5 包；
  注意其 AG-UI 协议本体在外部依赖 @ag-ui/client，本仓库是适配层与归约器）
- <LOCAL_WORK>/research-src/ag-ui/（commit e63fee1，typescript-sdk 四包 + spec/1.0
  schema 与 68 个一致性测试场景 + python/go/rust SDK）
- <LOCAL_WORK>/research-src/vercel-ai/（commit e7f55a4，packages/ai（streamText/tool loop）、
  packages/react（useChat）、resume 示例；注意 resumable-stream 是独立 npm 包）
要求：**报告中的关键协议机制结论（事件格式/暂停-恢复语义/状态同步算法）必须用
源码核实过，标注核实文件路径**（如"经 ag-ui spec/1.0/schema.json 核实"）；
文档摘录与源码冲突时以源码为准并注明。

【研究纪律（违反即返工）】
1. 边界：AI Runtime 只在后端（DC-06②，AI SDK 只挖思想）；组件不可直接用
   （三家 React 生态，我方 Vue 3）；M1 不改主线，产出只进 backlog/决策卡；
2. 每条建议必须：出处 / 我方现状（ADR/规格/实现位置）/ 差距分析 /
   借鉴价值（解决什么真实痛点）/ 落地建议（M2 backlog / 当前微调 / 明确不采纳
   及理由）/ 证据等级（【实测-源码核实】/【实测-文档】/【推断】）；
3. 禁止空泛结论，每条落到具体机制；
4. 反向输出：我方优于三家的点（防为新而新）+ 三家不适合我方约束
   （多实例/无用户体系/禁虚拟线程/Vue）的点。

【研究范围（至少覆盖，鼓励补充）】
1. HITL：三家确认门/中断-恢复形态 vs 我方挂起外置+跨进程续跑；
2. 生成式 UI：schema 约束与安全边界 vs 我方向导步骤驱动；
3. Shared State：useCopilotReadable / AG-UI STATE_SNAPSHOT+DELTA(RFC6902) vs
   我方 buildContext 快照+契约窄接口；
4. 前端工具：三家客户端工具协议 vs 我方 frontend_tool 帧+动作表；
5. 渐进式披露：三家有无对应机制 vs 我方三重防线；
6. 记忆：分层记忆 vs 我方 Redis LIST+滑动 TTL；
7. 运行轨迹：trace 回放用于测试/审计的形态；
8. **重点：我方自定义 SSE 帧 vs AG-UI 标准 31 事件**——对齐的收益/代价/路径，
   给明确建议（可参考 ag-ui spec/1.0 的 68 个一致性场景作为协议测试资产）；
9. 可恢复运行：resumable streams / durable execution vs 我方 RunStore 续跑；
10. 大工具结果处理：截断/分页/引用回指；
11. 其他你发现的维度（token 计量、多模态、MCP 兼容性、agent 间协议等）。

【输出】
<LOCAL_WORK>/<REPO>/docs/research/外部设计挖掘与借鉴报告.md：
11 维度逐节（三方机制要点（含源码核实路径）/ 我方现状 / 差距 / 借鉴建议表）+
总结表（M2 / 微调 / 不采纳三栏）+ 我方优势清单 + 不适用清单。
脱敏：禁止本机绝对路径（用 <<OLD_REPO_DIR>>、<RESEARCH_SRC> 占位）。
最后回复 ≤15 行：三栏各多少条、最重要的 3 条建议、报告路径、
你派了多少个子任务给子智能体。
```

**回来后**：报告给我或说"完成"，我逐条裁决（采纳→决策卡/驳回→留痕）。
