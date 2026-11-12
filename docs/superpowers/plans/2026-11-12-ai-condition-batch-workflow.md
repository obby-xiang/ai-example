# AI 条件收集与批量设置最终施工方案

**Goal:** 多配置项只调用一次条件工具并保留逐项事实；全量由宿主直接推进，不出现逐配置项工具卡和重复条件摘要。

**Architecture:** 条件表单使用 `conditionMode=CUSTOM/ALL` 与可选条件文本。`ALL` 或 `CUSTOM` 留空均由宿主直接走既有 `confirm_step`，不调用模型或条件工具。真实条件由一次 `set_conditions(items)` 承载，每项独立包含 `defCode + conditions`；前后端先全量校验、再一次写入，任意失败整批不写。`create_task` 非空条件只限单项，多配置项的不同条件统一在后续批量设置。

**Tech Stack:** Java 21 / Spring AI Tool Calling / Redis 既有 PendingToolCall / Vue 3 / TypeScript / JUnit 5 / Vitest，不新增依赖。

**Spec:** `docs/verification/AI对话独立复测清单-2026-11-05.md` 的导出标准工作流、C-09～C-13、C-111～C-113，以及本文件的补充设计决策。

## Global Constraints

- 禁止正则、固定文案、`contains` 或中英文关键词识别业务语义；自由文本只能由模型结构化，结构化选项才允许宿主直接消费。
- 保留宿主 `generative_form`，复用其 toolCallId、卡片、history、刷新恢复和取消协议；全量跳过 `set_conditions`，仍复用既有 `confirm_step` 提交步骤。
- `create_task.conditions={}` 表示未决定；`set_conditions(item.conditions={})` 表示该项明确不筛选，二者不得混淆。
- 不新增数据库字段、Redis 键族、SSE 帧类型或新执行器；草案不写业务库，只有 `confirm_step`/启动前由前端 `persistStepData` 提交。
- 一次批量调用只有一个 toolCallId、一张过程卡和一个汇总回执，但每个配置项的草案、校验、settled 和恢复事实独立可证。
- 任意 item 非法或任一项执行失败，整批不得写入或伪装成功。
- 模型工具协议只披露 `set_conditions(items)`，不披露单项条件工具或别名；前端条件动作只接受 `{items:[{defCode,conditions}]}`。
- 未经 `start_export` 批准不得创建作业；普通导出不增加高危复选框。

## Review Focus

- `CUSTOM` 留空、`ALL`、取消、非法 mode、非空真实条件，以及全量答案后新增配置项：覆盖规则不得失效。
- 不同配置项使用不同字段、算子、范围键和空条件：不得共享字段或误套配置定义。
- 空 items、重复 defCode、缺 conditions、字段不适用、未选中、非法算子、只读/停止状态：必须整批零写。
- 批量重复回执、迟到回执、刷新和跨实例恢复：不得重复卡片、重复草案或错误 settled。
- 多配置项非空 `create_task.conditions` 与全量/混合摘要：不得继续共用条件、丢失不同条件组或改变逐项指纹。

---

### Task 1: `conditionMode` 与全量免模型推进

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/ai/form/ConditionsForm.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/form/GenerativeFormRules.java`
- Test: `backend/src/test/java/com/example/configmgr/ai/form/ConditionsFormTest.java`
- Test: `backend/src/test/java/com/example/configmgr/ai/form/GenerativeFormContractTest.java`
- Test: `frontend/tests/condition-host-form-all-option.spec.ts`

**Interfaces:**
- 必填 `conditionMode` 枚举：`CUSTOM`（默认）/ `ALL`；`conditions` 文本仅 CUSTOM 可见且非必填。
- `ALL` 或 CUSTOM 文本缺失、JSON null、空白均表示当前表单覆盖项明确全量；CUSTOM 非空文本才交模型结构化。
- 非法 mode/值形状按未收集，绝不猜成全量。
- `ConditionFeedback.blank` 同时接受 ALL 与 CUSTOM 空白；`shouldParkConditionAdvance` 只发送既有 `confirm_step`，不调用 `set_conditions`。
- 后端 `ReplyLanguage` 生成选项文案，前端使用既有 enum/visibleWhen/草稿能力，不新增条件专用 Vue 分支。

- [ ] 先写全量、留空、取消、非法 mode、新增配置项和中英文表单失败用例。
- [ ] 独立运行上述后端和前端用例，确认旧实现失败。
- [ ] 实现双字段 Schema、三态解析与直接推进；`visibleValues` 省略不可见 conditions 时不得误判未收集。
- [ ] 复跑相关独立单点。

### Task 2: `set_conditions(items)` 批量协议与逐项草案

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/ai/tool/AiToolNames.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/tool/WizardActionGuard.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/form/ConditionsForm.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/tool/ToolRegistry.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/PromptRulePacks.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java`
- Modify: `backend/src/main/resources/ai/tools.yaml`
- Modify: `backend/src/main/java/com/example/configmgr/ai/gate/ToolPreflightService.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/tool/ToolResultEnvelope.java`
- Tests: 对应批量 Schema、守卫、表单、预检与恢复测试。

**Interfaces:**
```json
{
  "items": [
    {"defCode": "CURRENCY", "conditions": {"fields": [{"fieldCode": "code", "operator": "EQ", "value": "CNY"}]}},
    {"defCode": "DOC_TYPE", "conditions": {"fields": [{"fieldCode": "documentType", "operator": "EQ", "value": "OFFICIAL"}]}},
    {"defCode": "TAX_RATE", "conditions": {}}
  ]
}
```
- 工具名 `set_conditions`；`items` 为 1..N 非空数组，defCode 不重复，每项只含 `defCode/conditions` 且显式携带 conditions。
- `conditions` 复用 `FieldQueryCondition(scopeKeys, fields)`，每个字段按该项配置定义校验。
- 一个批量 PendingToolCall 的参数逐项推导 settled/attempted/issued，不新增草案字段或 Redis 字段。
- 宿主确定性批量草案 ID 包含 run/task 和实际缺口集合；相同完整有序 items 才幂等复用，任一项变化均为新修改。
- `ToolResultEnvelope.target` 只投影 defCodes，不复制完整条件值。
- `create_task` 非空 conditions 仅允许一个 defCode；多项未决定仍允许 `conditions={}`，不同条件走后续 `set_conditions(items)`。

- [ ] 先写单批单卡、不同字段、整批零写、幂等与恢复失败用例。
- [ ] 独立运行并确认旧实现失败。
- [ ] 实现批量签名、逐项校验和批量草案归属；先完成全部 item 校验再允许前端执行。
- [ ] 复跑批量、恢复和失败关闭用例。

### Task 3: 前端批量执行与单卡回执

**Files:**
- Modify: `frontend/src/utils/frontend-tools.ts`
- Modify: `frontend/src/views/ExportWizardView.vue`
- Modify: `frontend/src/types/tools.ts`
- Modify: `frontend/src/stores/workspace.ts`
- Tests: 对应前端批量、校验、工作区守卫与无损恢复测试。

**Interfaces:**
- `set_conditions` 只规范化为 `{items:[{defCode, conditions}]}`。
- 页面 handler 复用 `validateConditionPayload`、selectedDefs、只读和停止守卫。
- 预检阶段逐项校验并预计算草案/摘要，零写入；全部合法后才统一更新 draftModels/touchedConditions/计数缓存/workspace。
- 回执为 `{ok:true, result:"已设置 N 个配置项的查询条件草案"}`，失败为 `{ok:false, reason}`，不得部分成功。
- `persistStepData` 仍逐项提交，不虚构跨请求业务事务。

- [ ] 先写单批动作、第二项失败零写、守卫和恢复失败用例。
- [ ] 独立运行并确认旧实现失败。
- [ ] 实现批量归一化、全量预校验和统一草案应用。
- [ ] 复跑前端单点、typecheck 和 build。

### Task 4: 启动确认摘要与规则同步

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/ai/gate/ToolPreflightService.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/PromptRulePacks.java`
- Modify: `backend/src/main/resources/ai/tools.yaml`
- Modify: `docs/verification/AI对话独立复测清单-2026-11-05.md`
- Test: `backend/src/test/java/com/example/configmgr/ai/gate/ToolPreflightServiceTest.java`
- Test: `backend/src/test/java/com/example/configmgr/ai/run/PromptRulePacksTest.java`
- Test: `backend/src/test/java/com/example/configmgr/ai/tool/ToolResultEnvelopeTest.java`

**Interfaces:**
- 所有项均为 EXPLICIT_UNSET：`N 个配置项均不筛选（全部数据）`。
- 解析后的原始 conditionJson 仅用 `JsonNode.equals` 判定相同才聚合；不同 JSON 保留分组，不按人读摘要或字段名猜相同语义。
- UNDECIDED 和坏 JSON 不参与合法条件聚合。
- 240 字限制采用组感知截断：完整 defCodes 标识与条件文本分别计量；标识可放下时绝不截断，只复用 `GraphemeText` 按字素截条件文本，短组余额向后续长组再分配。
- 若全部完整组标识与分隔符本身超过 240 字，统一使用中性事实 `N 个配置项的筛选条件详见任务` / `See the task for filter conditions of N configuration items` 降级，不删组、不截 ID、不推断条件差异或状态、不假装逐组展示；不得按组数或状态增加分支。
- `reportHash` 继续按逐项、语言中立事实计算，展示聚合不得降低指纹粒度。
- `create_task` 非空 conditions 仅限单项；多项不同条件必须后续 `set_conditions(items)`。
- 提示词、注解、YAML、工具名和卡片文案同步为 `conditionMode=ALL/CUSTOM`、空条件宿主 `confirm_step` 与批量 `set_conditions(items)`；删除“逐项各调用一次”的规则。
- `tools.yaml` 升级版本，只保留当前工具协议。
- 复测清单更新当前预期和卡片描述，既有 PASS/FAIL/NOT_RUN 证据不伪造、不改写为通过。

- [ ] 先写全量、相同/不同 JSON、混合、长摘要、reportHash 粒度和多配置项创建失败用例。
- [ ] 独立运行并确认逐项重复、整串截断或共享条件不符合新契约。
- [ ] 实现按原始 JSON 聚合、组感知截断和单项创建约束。
- [ ] 复跑摘要、创建预检、信封和提示词契约用例；生产 Java 执行 IDEA Reformat 和后端 compile。

### Task 5: 真实模型与恢复验收

**Files:**
- Modify: `docs/verification/AI对话独立复测清单-2026-11-05.md`（追加本轮真实结果，保留既有证据）。

- [ ] 后端相关方法分别独立进程运行，禁止用编译冒充测试。
- [ ] 前端相关 spec 分别独立进程运行，再执行 typecheck 和 build。
- [ ] C-09/C-112、C-10、C-11、15 项全量、多项不同条件各按原矩阵复测；保存 create_task 参数、items、SSE、任务条目、作业数、卡片数和导出文件。
- [ ] 相同 JSON、不同 JSON、全量混合摘要、scopeKeys/IN/EMPTY/NOT_EMPTY 和英文界面分别覆盖。
- [ ] 重复/迟到回执、刷新、取消、创建拒绝、启动拒绝、ALL 后新增配置项分别按独立场景执行。
- [ ] 不把单元测试、桩或静态审查外推为真实模型、真实浏览器、Redis Cluster 或多实例通过。

## 施工顺序与门禁

1. Task 1 条件模式与全量推进。
2. Task 2 后端批量协议。
3. Task 3 前端批量执行。
4. Task 4 摘要、提示词与当前复测预期。
5. 主控逐 hunk 静态复核和独立审查，按 Task 5 复测；失败保留证据。
6. 每批分别提交；生产 Java 必须 IDEA Reformat、生产编译，前端 typecheck/build；非测试提交按仓内 standards 门禁执行。
