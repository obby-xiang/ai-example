<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-014 · 施工复核 · 【给用户 · 待转发】GF-B 任务卡（执行者：Kimi K2.8 Preview）（0c4dbfa1 / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`generative_form`、`ai-example-<OLD_REPO_DIR>`、`frontend/src/`、`AiPanel.vue`。
> **稿态**：历史中间稿 —— 该 wire 第 `m2` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt2` 条有正文的 assistant 消息（共 44 条有正文），末条有正文消息为 `m47`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `0c4dbfa1` / agent `main`（`main` = 主会话）；原字符 4,176（链内 1 段、合计 4,176）。
> **链号 / 类别**：`MT-014` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `0%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `0%`。
> **与末条归档（138 件）的关系**：末条 `m47`（7,479 字符）未入末条归档。
> **脱敏**：路径 1 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 3 处。

---

基线确认【实测】：`HEAD=3de9d02`，工作区干净，与 `<OLD_REPO_DIR>` 同步，与交接状态一致。探索子智能体已把 GF-B 所需的契约与前端现状全部摸清（含 3 处过期注释的精确行号、`generative_form` 前端零引用、回灌类型缺 `cancelled` 字段等缺口）。

按团队分工，GF-B 是主力前端编码 → **Kimi K2.8 Preview**，我无权用子智能体替代。下面是可直接转发的任务卡。

---

## 【给用户 · 待转发】GF-B 任务卡（执行者：Kimi K2.8 Preview）

> **角色**：主力编码。你只执行，不重新裁决；拿不准的报【待裁决】，不要自行变通。
>
> **项目**：ai-example（AI 辅助配置快速实施平台）。仓库 `<REPO>`，分支 `<OLD_REPO_DIR>`，基线 HEAD `3de9d02`（已推送，工作区须保持从干净状态起手）。六个源分支克隆在 `<LOCAL_WORK>\ai-example-code\` 下，只读参照。
>
> **任务**：GF-B 生成式表单前端棒（DC-15）——新增 FormRenderer 并接入 AI 会话面板的前端工具通道，外加三处顺手修正。
>
> ### 必读输入（先读再动手）
> 1. `docs/evidence/GFa-生成式表单后端工具验证.md` **§8 全节**（GF-C 对接契约清单，后端已用测试锁死）+ 文末"裁决结论"节。
> 2. `frontend/src/stores/ai.ts`（903 行，重点：`handleFrame` 的 `frontend_tool_request`/`frontend_tool_result` 分支 771-798 行、`runFrontendTool` 880-901、`submitFrontendToolResult` 563-579）。
> 3. `frontend/src/utils/frontend-tools.ts`（执行器注册表与四条路由、162-166 行的未知工具兜底）。
> 4. `frontend/src/components/wizard/ConditionForm.vue`（视觉基线样板）。
> 5. `docs/s4/S4.4-前端合流规格.md` §1.1（视觉纪律成文条款）。
>
> ### 施工范围
>
> **1. FormRenderer 组件**（`frontend/src/components/AiPanel/` 下新增，TS，禁 JS）
> - 白名单组件映射 Element Plus，六型：`text`→`el-input` / `number`→`el-input-number` / `boolean`→`el-switch` / `date`→`el-date-picker`（`value-format="YYYY-MM-DD"`）/ `enum`→`el-select` 或 `el-radio-group` / `multi_select`→`el-select multiple` 或 `el-checkbox-group`。
> - **禁自由 HTML**：schema 中的标题/描述/选项文本一律插值渲染（`{{ }}`），永不 `v-html`。
> - 未知字段类型/非法 schema 在前端防御性拒收（后端已保证合法，前端只做兜底，不重复实现校验逻辑）。
> - 视觉 1:1 沿用 kimi-k3 蓝本：EP 组件 + 内联 Tailwind 工具类，**禁止新增任何 `<style>` 块**（全仓唯一合法 scoped CSS 是 AiPanel 打字动画）。
>
> **2. 接入前端工具通道**（契约细节以 GFa 文档 §8 为准）
> - `frontend_tool_request`（`name=generative_form`，`args={"form":{…}}`）到达后**不得**走 `frontend-tools.ts` 的通用执行器兜底（那段说明文本会被后端闸门拒 400 拖满超时）。在 store 层特判：将表单 schema 挂到会话状态，在 `AiPanel.vue` 挂起卡区域（168-217 行现状）渲染 FormRenderer，进入"等待用户填写"态。
> - **提交**：`POST /api/ai/frontend-tool-result`，body `{runId, toolCallId, result, source}`，`result` 为字段 key→值的 JSON 字符串（number 是数、date 是 `yyyy-MM-dd`、enum/multi_select 值在选项内、禁未知字段）。提交前按 schema 做客户端复核。
> - **取消**：用户提供显式"取消"入口，回灌 `{runId, toolCallId, cancelled:true}`。渲染器不可用（schema 解析失败等任何异常路径）时**必须**带 `cancelled:true` 回灌（GFa 裁决 #3 契约），让挂起立刻收敛。
> - **被拒重试**：后端 400 `FORM_RESULT_REJECTED` + `status:"PENDING"` 表示挂起仍在等待——提示用户改值后用**同一 toolCallId** 重发，或改发取消。409 `DUPLICATE_TOOL_CALL_ID` 按现有幂等提示处理。
> - **超时**：帧带 `timeoutSeconds/expiresAt`，表单区显示剩余时间；本地判过期后收起表单并尝试 `cancelled:true` 回灌（409 忽略）。
> - 类型补齐：`types/ai.ts` 的 `FrontendToolResultRequest` 增加可选 `cancelled?: boolean`；`types/api.ts` 的 `ErrorCode` 增加 `FORM_RESULT_REJECTED`（及必要的 `FORM_SCHEMA_REJECTED`/`FRONTEND_CANCELLED` 展示态）；`AiPanel.vue` 的 `TOOL_LABELS` 增加 `generative_form` 条目；`stores/ai.ts` 的 `submitFrontendToolResult` 支持 cancel 路径。
>
> **3. 三处顺手修正**
> - ① `frontend/src/utils/frontend-tools.ts:4-8` 顶部计数注释：改为"18 个 `@Tool` / 7 个 `FRONTEND`"口径（同文件 255-257 行同口径过时表述一并改）；顺带核对 `frontend/src/types/tools.ts:7-31` 的工具全集表（现只列 17 个，缺 `generative_form`）并补正。
> - ② `frontend/src/types/condition.ts:23-27` 顶部"后端对未知操作符视为通过"注释已过期（实际已抛异常，S5f 裁决 #5），改为如实描述。
> - ③ `docs/evidence/GFa-生成式表单后端工具验证.md` 中全部 "GF-C" 指代统一改为 "GF-B"（共 14 处：行 9、108、286、320、325、347、348、361 章节标题、385、398、406、408、430、432），仅改指代不动语义。
>
> ### 验收标准（逐项自证）
> 1. `yarn typecheck` 与 `yarn build` 全绿（工作目录 `frontend/`，Node 22，包管理器 yarn）。
> 2. 六型字段渲染正确；提交产出的 `result` JSON 形状与 schema 严格相符。
> 3. 取消/不可用路径必带 `cancelled:true`；`FORM_RESULT_REJECTED` 后同 `toolCallId` 可重发。
> 4. 无新增依赖、无 `<style>` 块、无 `v-html`、无自写 EP 等价物。
> 5. 三处修正完成且全文无残留 "GF-C"（`grep -rn "GF-C" docs/ frontend/` 应为空）。
> 6. 真实 AI 端到端**不属于本棒**（归 GF-C），但需给出一份手动冒烟说明：如何用构造帧/后端联调验证渲染与回灌。
>
> ### 禁止事项
> - 禁改后端任何文件；禁动 `.github/`、`scripts/githooks`；禁引入新 npm 依赖。
> - 禁提交 git（commit/push 由提交官负责）；完工后保持工作区改动待验。
> - 禁扩散 scope：发现契约与文档不符，报【待裁决】，不自行改协议。
> - 端口纪律：如需起 dev server，从 18330 起分配，启动前 `netstat` 确认，只停自己起的进程。
>
> ### 交付物
> 代码改动 + 一份简明完工报告（改动文件清单、验收 6 条逐项自证结果、手动冒烟说明、【待裁决】清单如有）。

---

## 后续编排预告（用户可提前知悉）

- K2.8 交卷后：**红队审查给 DeepSeek V4 Pro**（Kimi 系产出，异厂商对抗性找茬：XSS/插值泄漏、回灌幂等竞态、取消与超时竞态、schema 防御），我会另出任务卡；
- 复核复现给 GLM-5.3（typecheck/build/行为基线独立重跑）；
- 两项通过后我裁决，再派提交官子智能体细分提交 + push；
- 随后进入队列第 2 项 GF-C 端到端联合验证棒。

如无异议，请把上面的任务卡开新对话发给 K2.8。
