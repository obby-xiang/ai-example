# 通用 create_task 引用与确定性证据解析最终设计

日期：2026-11-26

最终代码：`38e8baabef524eb55ae11571120a95f522ffa2ba`

真实验收：`<deepseek-evidence-cny-38e8baab>/report.md`

## 最终裁决

最终交付不采用早期设想的 `ActionIntentParser`、`ActionPlan(actions[])`、`submit_action_plan` 或 `CREATE_TASK_DETAILS`。这些名称不代表当前产品结构，也不存在多动作计划执行通道。

当前实现保留并组合以下既有边界：

1. `CreateTaskReferenceResolver` 负责配置、字段和真实主键值 canonical 化。
2. `CreateTaskEvidenceResolver` 负责从结构化参数与最后一条真实用户文本中确定唯一目录和真实 rowKey，并补齐空条件。
3. 当前 agent 的单次 `createEntryCorrection` 仅作为模型补全后备；确定性证据优先，补全失败不再重试。
4. canonical 参数必须再次通过 `CreateTaskReferenceResolver`，随后继续既有 preflight、创建 HITL、任务/作业和 `start_export` HITL。

最终方案不是“这次又全靠模型”。三轮真实验收中的 canonical 创建参数由目录、真实主键和真实 rowKey 共同约束；模型不再拥有绕过本地校验的第二条执行路径。

## 历史失败记录与方案取舍

早期真实失败包括：把“cny货币字典”整体作为配置引用，以及配置/字段解析正确但值保留小写 `cny`。单纯精确引用匹配无法覆盖这两类输入，因此先加入真实主键值归一，再加入确定性证据解析。

中间方案曾考虑用一次专注 function calling 输出通用 `actions[]`，也曾让 corrected 结果直接决定后续流程。这些方案不是最终交付：通用多动作计划被否决，单次 correction 只保留为补全后备； corrected 的空条件仍须经过确定性证据和本地引用解析，不能绕过 preflight/HITL。

施工期间还修正了以下边界：

- 初始未决的内部工具帧不向用户展示，但 pending、Redis、history 和日志保留完整事实。
- corrected 使用当前 `Prompt` 的临时标记；永久 `createEntryCorrectionUsed` 只用于防止第二次补全，不再充当当前批次识别依据。
- `conditions={}` 与 `conditions.fields=[]` 均可由唯一证据补齐；显式 `defCodes=[]`、明确全量和非空 fields 不被覆盖。
- REGION 等非 GLOBAL 定义只有恰好一个非空文本 scopeKey 才可核验；缺 scope、多 scope、零/多目录、零/多 rowKey、分页超限和仓储异常均不猜测。
- 补全失败保持真实失败，不进行第二次 correction，也不伪造成功。

逐方法施工和返修记录保留于 `<single-loop-create-completion>/report.md`；本文件以最终交付和真实三轮结果为准，不把早期失败方案写成当前事实。

## 最终解析顺序

1. 主模型产生 `create_task` pending。
2. `CreateTaskReferenceResolver` 先处理结构化 `defCodes`、字段引用和值。
3. 对非枚举唯一主键的文本 `EQ`，在范围唯一且依赖齐全时通过 `ConfigDataRowRepository.findRow` 核验真实已发布数据。精确原值优先；未命中时才核验去重后的 upper/lower 候选，总计最多三次，且只有唯一权威 rowKey 才归一。非主键、复合主键、数组、null、非文本和范围不唯一均不推断。
4. 原始引用 `UNRESOLVED`，或引用已 `RESOLVED` 但没有条件条目时，`CreateTaskEvidenceResolver` 使用结构化参数和最后一条非 Synthetic `UserMessage` 尝试确定性补齐。
5. 证据结果必须再次经 `CreateTaskReferenceResolver` 达到 `RESOLVED`，才可替换 pending 参数并进入原 preflight/HITL；否则保留原 correction 或失败路径。
6. 只有确定性证据不足时才使用当前 agent 的单次 `createEntryCorrection`。corrected 参数重新经过相同证据、引用解析和 preflight，不新增执行通道。

## CreateTaskEvidenceResolver 边界

### 目录选择

- 合法单个 `defCodes` 优先；无法唯一解析时，才按真实用户文本包含目录 `code/name` 匹配，且必须恰好一个定义。
- `defCodes` 缺失或 null 可尝试唯一证据；非数组、多个元素、空白/null/非文本元素均拒绝。
- 显式 `defCodes=[]` 是独立的全量/未选择配置状态，不参与目录推断，不新增条件。
- 多目录同时命中时不选择第一个，也不依赖后续空仓储结果制造“拒绝”。

### 条件与作用域

- `conditions={}`、缺失/null fields、`fields=[]` 属于空条件候选。
- 只有唯一目录和唯一真实 rowKey 才生成 `fieldCode + EQ + rowKey`；其他非空 fields 不覆盖。
- 无唯一证据时，显式 `fields=[]` 保留明确无条件语义，继续原 preflight/HITL。
- GLOBAL 不需要 scope；非 GLOBAL 只有恰好一个非空文本 `scopeKeys` 才可查询。REGION/R1 可核验，缺 scope、空 scope、多 scope 和非文本 scope 不猜测。

### 真实行匹配与容量

- 只支持单主键定义，使用 `findRowsInScopePaged` 只读一页真实业务数据。
- 默认最多扫描 50 行，构造上限硬限制 200，请求 `maxScanRows+1`；总数超限返回空证据。
- rowKey 忽略大小写，以 ASCII 字母、数字、下划线判断 token 边界；恰好一个真实 rowKey 才采用其权威大小写。
- 该查询只读业务定义和业务行，不保存 AI 会话或运行状态，不新增 Redis 键、线程池、模型并发或执行通道。

## correction、帧和历史语义

- 当前 corrected 批次由 `Prompt` 临时标记识别，标记随本次 Prompt 生命周期结束，不写入永久运行上下文。
- 永久 used 只防止第二次 correction；后续普通工具批不因旧 used 被误判为 corrected。
- 初始未决的 `tool_start/tool_result` 用户帧被抑制，避免展示内部技术失败卡；Redis pending、history、日志和后续 correction 所需事实完整保留。
- corrected 最终失败、preflight 失败和用户可见终态不被初始帧抑制规则隐藏。
- 失败不重试，不用补偿卡、额外模型调用或部分执行掩盖失败。

## 非目标

- 不恢复中文正则、关键词、CNY、模型或供应商特例。
- 不新增 `ActionIntentParser`、`ActionPlan`、`submit_action_plan`、`CREATE_TASK_DETAILS`、多动作编排器或前端补充信息卡。
- 不在纯问答中增加模型调用，不开放多危险动作并行执行。
- 不用真实三轮句型结果外推其他配置、其他动作或所有 OpenAI 兼容供应商。

## 真实三轮验收

证据报告：`<deepseek-evidence-cny-38e8baab>/report.md`。执行 HEAD 为 `38e8baabef524eb55ae11571120a95f522ffa2ba`，真实上游 `deepseek-v4-pro`，逐字输入“导出cny货币字典”，三轮均为全新独立 session。

| 轮次 | 判定 | session | run | task / job |
| --- | --- | --- | --- | --- |
| r1 | PASS | `1795703052357` | `05d5bd2b-8913-4e37-94af-7da8c024111f` | #513 / #417 COMPLETED |
| r2 | PASS | `1795703247918` | `2d56d267-4f43-4dc0-975f-655b6b6ff871` | #514 / #418 COMPLETED |
| r3 | PASS | `1795703390286` | `90a37dc3-547f-44cb-931b-1c26258d18ba` | #515 / #419 COMPLETED |

三轮共同事实：

- 唯一一次 `create_task` 的 canonical 参数均为 `{"type":"EXPORT","defCodes":["CURRENCY"],"conditions":{"fields":[{"fieldCode":"code","operator":"EQ","value":"CNY"}]}}`。
- 无 `CREATE_TASK_DETAILS`、配置选择或条件补卡，无引用解析/precheck 内部错误。
- 创建与启动恰好两个 HITL；创建批准前无任务，批准后只创建任务；启动批准前 0 作业，批准后恰好 1 个 COMPLETED 作业。
- task item 和 `set_conditions` 均为 `CURRENCY/code EQ CNY`；三份 XLSX 均只有 1 行 CNY，无小写 cny 或空文件。
- 三个 run 均 DONE；刷新前后 history 均 16/16，7 张工具卡一致且无缺失，无 transcript 降级、空回复或错误“已停止”。

**最终真实验收结论：3/3 PASS。该结论只覆盖上述句型和三轮现场。**

## NOT_RUN 与独立环境问题

以下项目未验证，统一记为 **NOT_RUN**，不得写成通过：

- 多动作计划或多动作执行。
- 全量测试、测试类或套件。
- 真实 Redis Cluster、多实例随机路由和故障接管。
- CI 和推送后的流水线检查。
- 除三轮句型外的其他真实端到端场景，包括其他配置、歧义、多配置、明确全量和多动作。

独立环境问题：本环境 `/api/ai/runs` 列表接口仍返回 `400 ILLEGAL_STATE`，后端日志另有 `RunStore.readSnapshot` 的 Jackson 反序列化堆栈。执行器使用公开 `GET /api/ai/runs/current?sessionId=...` 获取 runId，`/api/ai/runs/{runId}`、history 和 tasks 正常。该问题单独记录，不混入本功能三轮 PASS，也不据此宣称列表接口已修复。
