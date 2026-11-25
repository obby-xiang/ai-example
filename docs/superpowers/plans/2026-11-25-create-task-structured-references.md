# create_task 结构化业务引用实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让模型以配置/字段编码或名称表达创建意图，宿主在创建预检前确定性 canonical 化，无法唯一解析时在创建前集中收集，避免已知信息丢失和创建后补卡。

**Architecture:** 保留现有 `defCodes/conditions.fields.fieldCode` JSON 字段名，只在 `create_task` 边界扩展其值语义并原位写回权威编码。新增单一引用解析器复用 `DefinitionCatalog`，下游预检、HITL、草案、向导与执行继续消费 canonical 参数；未决引用复用宿主表单和恢复链，不增加模型请求。

**Tech Stack:** Java 21、Spring AI、Jackson、现有 HostForm/RunStore/ResumeService、Vue 通用生成式表单、Maven 单点测试。

**Spec:** `docs/superpowers/specs/2026-11-25-create-task-structured-references-design.md`

## Global Constraints

- 禁止读取或解析用户原文；禁止中文正则、关键词、停用词、模糊/包含匹配、模型或配置特例。
- 不新增依赖、不增加模型调用、不修改 `ToolRegistry`、不保留双字段协议。
- 不恢复 `TaskSlotParser/TaskIntent`，不改变多配置、明确全量、`scopeKeys` 的既有协议。
- canonical 前不得进入创建 HITL 或产生任务/条件/作业副作用；目录失败必须失败关闭。
- 仅执行下列独立方法级单点，禁止批量或循环执行普通测试；主控验收只看代码，不执行测试。
- 生产代码由子代理实施；每批经独立代码审查后按项目门禁提交推送，禁止 CU。

## Review Focus

- 用户未提配置与模型无法解析配置必须区分：前者允许空数组，后者保留引用并进入未决链，见任务 1/2 单点。
- 目录异常、非法 JSON 与正常未决不能混为可收集状态：失败必须零副作用且可观测，见任务 1 单点。
- 非枚举值不得被任意大写或改写；枚举只有唯一匹配才可归一，见任务 1 单点。
- 表单恢复不得依赖模型重新生成创建参数，重复回执不得重复创建，见任务 2 单点。
- 多配置条件、明确全量与 `scopeKeys` 不得被新解析器改义，见任务 1/3 对照单点。

---

### Task 1: 结构化引用解析与创建入口 canonical 化

**Files:**
- Create: `backend/src/main/java/com/example/configmgr/ai/gate/CreateTaskReferenceResolver.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java`
- Test: `backend/src/test/java/com/example/configmgr/ai/gate/CreateTaskReferenceResolverTest.java`
- Test: `backend/src/test/java/com/example/configmgr/ai/gate/SpToolCallingManagerPendingArgumentsTest.java`

**Interfaces:**
- Consumes: `DefinitionCatalog.list()`、`SlotDefinition.code/name/fields`、`SlotField.code/label/isKey/optionValues`。
- Produces: `CreateTaskReferenceResolver.Resolution resolve(String argumentsJson)`；`Resolution` 含 `Status status`、`String canonicalArguments`、`List<UnresolvedReference> unresolved`、`String failureCode`。`Status` 为 `RESOLVED/UNRESOLVED/FAILED`；`UnresolvedReference` 含 `Kind kind`、`int definitionIndex`、`int fieldIndex`、`String reference`、`List<String> options`，`Kind` 为 `DEFINITION/FIELD/ENUM_VALUE`。定义项的 `fieldIndex=-1`，字段/枚举项带对应定义下标。
- Produces: `RESOLVED` 时 `canonicalArguments` 为现有 `defCodes/fieldCode` 权威编码 JSON；`UNRESOLVED` 时保留原创建参数供任务 2 收集；`FAILED` 不进入预检或 HITL。

- [ ] **Step 1: 写解析器失败单点**

覆盖 `resolvesNamesAndInfersSinglePrimaryKey`、`preservesNonEnumValueAndCanonicalizesEnumOnlyOnUniqueMatch`、`distinguishesMissingAmbiguousBlankAndCatalogFailure`、`preservesMultiConfigEmptyConditionsAndScopeKeys`。断言只处理结构化 JSON，编码/名称唯一精确匹配，单主键推断，非枚举值不改写，多配置与 `scopeKeys` 形状不变。

- [ ] **Step 2: 逐个运行并确认 RED**

在 `backend` 分别执行：

```bash
mvn -Dtest=CreateTaskReferenceResolverTest#resolvesNamesAndInfersSinglePrimaryKey test
mvn -Dtest=CreateTaskReferenceResolverTest#preservesNonEnumValueAndCanonicalizesEnumOnlyOnUniqueMatch test
mvn -Dtest=CreateTaskReferenceResolverTest#distinguishesMissingAmbiguousBlankAndCatalogFailure test
mvn -Dtest=CreateTaskReferenceResolverTest#preservesMultiConfigEmptyConditionsAndScopeKeys test
```

预期：因解析器尚不存在或行为未实现而失败，不得先写生产实现。

- [ ] **Step 3: 实现最小解析器**

只读 `defCodes` 和 `conditions.fields`；编码按大小写安全精确匹配，名称/标签去首尾空白后精确匹配。`fieldCode=null` 仅允许唯一主键推断；枚举仅唯一匹配时归一权威选项。目录异常返回 `FAILED`，零/多命中和空引用返回带定位信息的 `UNRESOLVED`，不得吞异常后沿用原参数。

- [ ] **Step 4: 接入 `bindPendingArguments`**

`type` 缺失/非法时继续先走 `TaskTypeForm`，不得让引用失败遮蔽类型表单。合法 `create_task` 在 `ConditionsForm.bindDraft` 和预检前解析；`RESOLVED` 写回 pending，`UNRESOLVED` 暂以 `CREATE_TASK_REFERENCES_UNRESOLVED` 零副作用阻断，`FAILED` 以 `CREATE_TASK_REFERENCE_RESOLUTION_FAILED` 阻断。任务 2 会把未决阻断替换为创建前表单。

- [ ] **Step 5: 重写旧回归断言并逐个验证 GREEN**

将“导出CNY货币字典保持空参数且通过”的旧断言改为 `referencesAreCanonicalBeforePreflight`，并覆盖 `unresolvedReferencesDoNotReachPreflightOrHitl`、`catalogFailureDoesNotReachPreflightOrHitl`。先确认新断言在旧代码 RED，再在实现后逐个执行：

```bash
mvn -Dtest=CreateTaskReferenceResolverTest#resolvesNamesAndInfersSinglePrimaryKey test
mvn -Dtest=CreateTaskReferenceResolverTest#preservesNonEnumValueAndCanonicalizesEnumOnlyOnUniqueMatch test
mvn -Dtest=CreateTaskReferenceResolverTest#distinguishesMissingAmbiguousBlankAndCatalogFailure test
mvn -Dtest=CreateTaskReferenceResolverTest#preservesMultiConfigEmptyConditionsAndScopeKeys test
mvn -Dtest=SpToolCallingManagerPendingArgumentsTest#referencesAreCanonicalBeforePreflight test
mvn -Dtest=SpToolCallingManagerPendingArgumentsTest#unresolvedReferencesDoNotReachPreflightOrHitl test
mvn -Dtest=SpToolCallingManagerPendingArgumentsTest#catalogFailureDoesNotReachPreflightOrHitl test
```

- [ ] **Step 6: 主控代码复核、IDEA Reformat、实际编译、规范记录并提交推送**

仅暂存本批文件，逐 hunk 审查；不得把单点结果外推为真实模型通过。

### Task 2: 创建前未决详情表单与确定性恢复

**Files:**
- Create: `backend/src/main/java/com/example/configmgr/ai/form/CreateTaskDetailsForm.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/form/FormPurposes.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/form/GenerativeFormRules.java`（仅确有形状约束需要时）
- Modify: `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/PendingToolCall.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java`
- Modify: `frontend/src/components/AiPanel/AiPanel.vue`
- Modify: `frontend/src/locales/zh-CN.json`、`frontend/src/locales/en-US.json`
- Test: `backend/src/test/java/com/example/configmgr/ai/form/CreateTaskDetailsFormTest.java`
- Test: `backend/src/test/java/com/example/configmgr/ai/run/CreateTaskDetailsResumeTest.java`

**Interfaces:**
- Consumes: 任务 1 的 `Resolution/UNRESOLVED/UnresolvedReference` 和原始创建参数。
- Produces: `CreateTaskDetailsForm.parkIfUnresolved(...)`，在创建预检前挂起 `purpose=CREATE_TASK_DETAILS`；表单 pending 持久化原创建 `toolCallId` 与原始参数。
- Produces: 恢复消费点将提交值按 `definitionIndex/fieldIndex/kind` 合并到原参数，再调用任务 1 的 `resolve` 和既有预检/HITL；不得要求模型重新发起 `create_task`。

- [ ] **Step 1: 写表单与恢复失败单点**

表单只包含未决项，枚举值使用权威 options，多缺口同卡且不超过既有 HostForm 上限；超限必须安全失败，不得丢弃未决项。恢复覆盖提交后重新解析/预检、取消零创建、重复恢复幂等、仍有歧义不进 HITL。

- [ ] **Step 2: 逐个运行并确认 RED**

```bash
mvn -Dtest=CreateTaskDetailsFormTest#parksOnlyUnresolvedReferencesWithAuthoritativeOptions test
mvn -Dtest=CreateTaskDetailsFormTest#formPassesWhitelistAndUsesReplyLanguage test
mvn -Dtest=CreateTaskDetailsResumeTest#submissionReresolvesAndRunsPreflightBeforeCreateHitl test
mvn -Dtest=CreateTaskDetailsResumeTest#cancelDoesNotCreateTaskOrJob test
mvn -Dtest=CreateTaskDetailsResumeTest#repeatedResumeDoesNotDuplicateFormOrCreateTask test
mvn -Dtest=CreateTaskDetailsResumeTest#stillAmbiguousSubmissionStopsBeforeCreateHitl test
```

- [ ] **Step 3: 实现创建前表单**

复用 `HostForm` 的 park、帧、TTL、取消和恢复协议，注册 `CREATE_TASK_DETAILS` 及通用中英文标题。字段键由稳定类型和下标组成；表单 pending 显式保存原创建调用身份与原始参数，不存入数据库，不依赖用户原文。

- [ ] **Step 4: 实现恢复合并与重新预检**

在 `ResumeService` 识别该 purpose 的真实回执，合并到同一原创建参数后重新执行任务 1 解析。已解析进入现有创建 HITL；仍未决保持零副作用；取消/重复/迟到回执按现有终态与幂等规则处理。不得伪造一个新的模型 assistant/tool 调用。

- [ ] **Step 5: 逐个验证 GREEN**

重复 Step 2 的六条独立命令，逐条确认通过；主控不代跑。

- [ ] **Step 6: 主控代码复核、前端构建、后端编译、IDEA Reformat、规范记录并提交推送**

重点检查跨实例恢复、pending 序列化、表单超时/取消、原创建调用幂等及前端是否只使用通用渲染。

### Task 3: 提示契约、版本与真实场景验收

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/PromptRulePacks.java`
- Modify: `backend/src/main/resources/ai/tools.yaml`（版本升为 `2026-10-27.42`）
- Modify: 受影响的提示契约测试与项目现行规格文档
- Modify: `docs/superpowers/specs/2026-11-25-create-task-structured-references-design.md`、`docs/superpowers/plans/2026-11-25-create-task-structured-references.md` 的实施结果记录

**Interfaces:**
- Consumes: 任务 1/2 已实现的同名引用与 canonical 行为。
- Produces: 模型可见描述统一为“未提及时传空；提及时传编码或名称；字段未知时保留条件并传 `fieldCode=null`”，不再出现“无法映射就省略/先创建后补充”的冲突口径。

- [ ] **Step 1: 更新提示失败断言**

覆盖 `createSlotRulesStaySynchronizedAcrossYamlAndAnnotation`、`correctionInstructionUsesCurrentSchemaAndPreservesPureQuestionAnswer`、`toolsVersionBumpedForKnownInfoDescriptions`，并保留多配置、明确全量与 `scopeKeys` 的既有语义断言。先逐个确认旧提示下 RED。

- [ ] **Step 2: 同步所有模型可见描述并逐个验证 GREEN**

分别执行相关方法级单点；不得批量运行测试类。启动后核对实际 `definitionsVersion=2026-10-27.42` 和日志，无 WARN/异常才能进入真实复测。

- [ ] **Step 3: 真实模型独立复测**

由 DeepSeek 使用内置浏览器，禁止 CU，在三个独立新会话逐字输入“导出cny货币字典”。每轮核对 canonical 创建实参、仅必要 HITL、无配置/条件补卡、任务 items、作业数和实际 XLSX 仅 CNY；刷新后 history/工具卡一致，无 transcript 降级或错误“已停止”。三轮是相互独立场景，不是批量普通测试。

- [ ] **Step 4: 主控代码复核、规范记录并提交推送**

报告逐轮 run/session、参数、首个失败点和证据路径；未复现的对照项写 `NOT_REPRODUCED`，未执行写 `NOT_RUN`，不得自行修复复测发现的问题。

## 实施结果记录（2026-11-25）

- Task 1/2：实现、审查和推送已完成，终点为 `c15f97df`。同名结构化引用 canonical 化及 `CREATE_TASK_DETAILS` 创建前收集/恢复均已落地，计划中的禁止事项保持有效。
- Task 3 Step 1：已完成。三个指定提示/版本方法及六个补充提示、版本和守卫方法先在旧生产文本上分别取得预期 RED；多配置与 `scopeKeys` 回归作为 GREEN 对照保留，不宣称其独立 RED。
- Task 3 Step 2：已完成。`AiTools`、`tools.yaml`、`ResilientChatService`、`PromptRulePacks` 以及实际仍含旧口径的 `GenerativeFormGuard` 已同步；`definitionsVersion=2026-10-27.42` 由四个独立版本相关方法的运行日志确认。最终 12 个方法级单点分别执行并全部通过；未批量运行测试类或测试套件。
- 工程门禁：五个生产文件已执行 IDEA Reformat Code，生产编译 `BUILD SUCCESS`，规范审查记录随本批提交。完整 RED/GREEN 命令、输出摘要和逐 hunk 自审见任务 3 报告。
- Task 3 Step 3：`NOT_RUN`，保留给控制器的独立 DeepSeek 真实模型/浏览器复测；三轮 run/session、实参、卡片、任务/作业、XLSX、history 与刷新证据均未产生，真实对照项为 `NOT_REPRODUCED`。本实现上下文未启动或重启服务。
- Task 3 Step 4：仅完成本任务本地提交，不推送；真实模型结果不得由上述静态审查或方法级测试外推。
