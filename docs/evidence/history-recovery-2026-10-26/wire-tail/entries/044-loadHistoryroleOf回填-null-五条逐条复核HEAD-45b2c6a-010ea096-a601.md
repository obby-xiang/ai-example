> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-601` 的 wire 末条 assistant 文本（消息序号 m=43；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-12 11:52 → 2026-10-12 11:56（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：`45b2c6a`（正文自动摘取的首次基线提及），未经本批复验
> - 原始体量：8578 B / 5512 字符 / 34 行；行指纹覆盖率（本批复算）0.0%（无单文件重合）
> - 类别：**施工复核**
> - 与既有件关系：工作树实读
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
已完成复核。以下为报告（全部行号 = HEAD `45b2c6a` 实读；`git status` 实读仅有 `frontend/tests/polling-predicate.spec.ts` 改动 + 两个 task-events spec 删除，`stores/ai.ts`、`MessageJsonCodec.java` 干净，无行号漂移）。

# issue #7 根因断言逐条复核

## 1. loadHistory 逐条独立映射 —— **属实**
`frontend/src/stores/ai.ts:603`（loadHistory）、`:678-710`：`this.messages = history.messages.map((item, index) => {...})`，每条历史项独立建一个 `ChatMessage`（`:697-707`，`id: history-${index}`），**全函数无任何跨条目合并**：正文只取本条目 `item.text`（`:684`），工具段只由本条目 `item.toolCalls` 生成（`:685-695`）。不存在把 `TOOL` 条目回填进前一条 assistant 的代码（全仓 `grep toolResponses` 前端仅两处：`types/ai.ts:141` 类型、`ai.ts:693` 本行）。

## 2. roleOf 兜底 assistant ⇒ 空白块 —— **属实（一处措辞需订正）**
`ai.ts:309-318`：仅 `USER`/`SYSTEM` 特判，其余一律 `return 'assistant'`。`TOOL` 条目无 `toolCalls` ⇒ parts 只有 `{kind:'text', text: item.text ?? ''}`（`:683-684`，后端该字段为空串/none 均折 `''`）。`utils/blocks.ts:111-115` 空正文段直接 `continue` ⇒ `blocksOf(m)=[]`（`AiPanel.vue:591-593`）。`AiPanel.vue:141-156` 的 `v-for="m in ai.messages"` 外层**无条件**渲染（头像 + 气泡 div），无人过滤 ⇒ 渲染出「头像 + 空白灰气泡」。措辞订正：不是「只有头像」，空气泡壳也在。

## 3. 历史回填行恒为 null —— **属实**
`ai.ts:693` 原文：`result: (item.toolResponses ?? []).find((response) => response.id === call.id)?.responseData ?? null`。该行只作用于 `item.toolCalls` 的 ASSISTANT 条目，而真实载荷里 `toolResponses` 只出现在 **TOOL 条目**上（见 4）⇒ 恒为 `null`；`AiPanel.vue:208`（与 `:294`）`v-if="part.result"` 不渲染结果区。附：`TOOL` 条目的 `responseData` 全仓无人消费（被整条丢弃）。**旁证**：`frontend/tests/parts-residue-cleanup.spec.ts:577-610` 的夹具把 `toolCalls` 与 `toolResponses` 写在**同一条 ASSISTANT** 上（真实后端不可能产出该形状），所以该 bug 被"绿灯"掩盖。

## 4. 后端分两条 Redis LIST 条目 —— **属实**
`backend/.../memory/MessageJsonCodec.java:96-123`：统一写 `type`/`content`（`:98-99`），仅 `AssistantMessage && hasToolCalls` 才写 `toolCalls`（`:102-111`），仅 `ToolResponseMessage` 才写 `toolResponses`（`:113-121`）；`:149-158` 按 type 互斥反序列化。`RedisChatMemoryRepository.java:142-148`（`LRANGE` 全量）/`:157-167`（`rightPushAll`）⇒ 一条消息一个字符串项、顺序保留。`AiController.java:509-533` 逐 `Message` 一项：`:514-521` 只对 Assistant 挂 `toolCalls`、只对 Tool 挂 `toolResponses`。实测抓包同形：`docs/spike/logs/sp02/E1-state-before-resume.json` 中 `ASSISTANT(content:"", toolCalls:[call_00,call_01])` 与紧随的 `TOOL(toolResponses:[call_00,call_01])` 为两条。

## 5. 实时帧路不受影响 —— **属实**
`utils/parts.ts:90-103`（`upsertToolPart`，按 `toolCallId` 就地 spread 合并），`tool_result` 分支 `:329-338`（`result: frame.result ?? null`），`frontend_tool_result` 分支 `:409-416`（同为 `frame.result ?? null`）。实时路径结果区正常。

## 6. 第三失真点（前缀位置丢失 / 并入后续文本）—— **不成立**（仓内证据与本仓当前链路相反）
- 代码侧：loadHistory 只在**本条目内**先放正文段后放工具段（`ai.ts:683-695`），没有任何跨条目文本拼接；恢复后工具轮 ASSISTANT 的前缀文本位于**卡片上方**，位置不丢、也不并入下一条。
- 决定性事实：本仓实测留证 `docs/evidence/S42-P1-核心运行时验证.md:388-392`「官方内部工具循环…**不把中间工具响应放进客户端流**（`OpenAiChatModel.internalStream`：仅递归新请求，不 emit 工具轮响应）」；当前期帧序列留证同向：`docs/evidence/M2-T3b-施工报告-DS-V4-Flash.md:407/453/457`（`…tool_start, tool_result, message_start, delta, message_end, done`）、`S44d-前端通道工具验证.md:107-108`（连续多轮工具帧之间无 delta）。⇒ 工具轮的前缀正文**根本没进前端实时流**（也就无从"恢复后移位"）；累计器 `ResilientChatService.java:648-676` 只攒得最终响应文本。
- 抓包穷举：全仓 `ASSISTANT` 带 `toolCalls` 的实例（`docs/spike/logs/sp02/*` 五例 rebuildPreview、`docs/spike/SP-01c-附件-A…txt`、`SP-01d-附件-C…txt`）其文本字段**均为空串**，无非空前缀实例。
- 残余不确定（如实申报）：若某 provider 在工具轮实吐正文，它会进 memory 的 ASSISTANT 条目（`gate/SpToolCallingManager.java:200-203` 写入 `assistant`+`toolMessage`），恢复后出现在**卡片上方**——与 issue 描述仍是相反方向。真正的伴随失真应表述为：一轮被拆成多气泡 + 中间夹一条空白气泡（见 1/2）。

## 7. R-141② 申报注释 —— **核实存在，原文如下**
`ai.ts:670-677`（逐字）：注释头 =「批3③b D7(B)（**R-141②**，定案注记 —— 任何后续批次不得把本注记解读为"丢 unknown 可以接受"）」，正文 =「本历史整替的工具段 `status:'succeeded'`（下）与 `kind2:'UNKNOWN'` 是**展示占位值，不是事实断言**：后端历史载荷（AiHistory / AiHistoryMessage，types/ai.ts:133-150）只有 type / text / toolCalls / toolResponses / timestamp 五类字段，**不携带任何工具执行状态**…这是**已申报的已知失真（R-141②）**…修复属后端面（历史携带状态），已登记立项（R-144）…**任何消费方不得以该 succeeded 值做业务判定**」。

## 8. 「loadHistory 内合并 TOOL 回前条」路线的触点与坑
**须触碰**：`stores/ai.ts:678-710`（两遍扫描：先按 `toolResponses[].id → responseData` 建索引，再在 ASSISTANT 条目建段时回填并**跳过 TOOL 条目**）、`:688-693`（`toolCallId`/`result` 生成口径）、`:309-318 roleOf`（TOOL 不再进渲染）；数据结构 `types/ai.ts:139-146`（`AiHistoryMessage.toolCalls[].id` / `toolResponses[].id`，两处字段名都是 **`id`**，对齐即 `call.id ↔ response.id`）、`types/ai.ts:184-215`（ChatMessage）与 `:243-268`（ToolPart，`result?: string|null`）、`toolCallId` 必填）；工具 `utils/parts.ts:29-31 nextSeg`、`:69-74 sealTrailingStreamPart`、`:90-103 upsertToolPart`、`:178-217 deriveProjections`（返回前必须调用，否则 `content`/`toolRuns` 空）；渲染无需改（`AiPanel.vue:208` 自动生效）。
**坑**：① 一对多：一条 TOOL 可含多条 response、一条 ASSISTANT 可含多次 call（`codec:102-121`；E1 抓包 2 calls ↔ 2 responses），必须按 id 配对、不可假设 1:1 相邻；② 连续多条工具调用/多轮（`TOOL` 只吃自己那份，ASSISTANT 之间不得合并）；③ id 缺失：`call.id` 为空时 `find(r => r.id === call.id)` 会 `undefined===undefined` 误配第一条 ⇒ 需 `call.id` 为真才 id 匹配、否则退回按序位匹配、仍不中则 `null`（且不得因此丢卡片）；④ `responseData` 可为 `null`（前端工具当轮未决），保持 `v-if` 语义；⑤ 不得顺手把硬编码 `status:'succeeded'` 改成"由 result 推断"——那是 R-141②/R-144 的后端面，误改等于新增失真；⑥ `id: history-${index}` 与 block-id 命名空间：跳过条目后 index 有空洞，须保留 ASSISTANT 自身 index（`:688` 兜底 `history-${index}-${name}` 依赖它）；⑦ 与 `restoreSuspendedSnapshot`（`:732+`）及已决残留守卫（`:728/:740` 一带）的交互：合并后段上出现 `result` 但 `requiresAction` 必须仍为 `false`，否则会凭空复活挂起卡；⑧ 既有绿灯 `frontend/tests/parts-residue-cleanup.spec.ts:577-610` 用的是"ASSISTANT 自带 toolResponses"的假形状，合并路线落地时必须改成真实双条目夹具（建议新增 TOOL 条目用例）；⑨ 窗口裁剪 `ResilientChatService.java:600-608` 只保护头部不产生孤立 TOOL，但残缺历史（TTL/裁剪）下可能出现无主 response ⇒ 兜底丢弃，不可报错。

---
**自证**：本棒实际运行模型取自本会话 `agent-601` 的 `wire.jsonl` `llm.request`：`model=deepseek-flash`、`modelAlias=DeepSeek/deepseek-flash`、`provider=openai`、`thinkingEffort=high`、`maxTokens=393216`（本会话 40 条 `llm.request` 全部同值）。
