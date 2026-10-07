# GF-A 证据：生成式表单后端工具（DC-15）

> 施工类型：**生产代码新增/增强**（`backend/src/main` 有改动，本棒授权范围）+ 单元测试新增（62 用例）与三处构造点连带更新。
> 依据裁决：`docs/adr/DECISION-REGISTER.md` **DC-15**（限定形态生成式 UI：schema 驱动的表单渲染，六型白名单，禁自由 HTML，
> 挂既有 frontend_tool 通道与回灌幂等语义）；`docs/adr/DECISION-CARDS.md`（前端通道工具 / 挂起外置续跑 / 409 串行化）；
> `docs/s4/S4.2-AI-Runtime-合流规格.md`（`gate/SpToolCallingManager`、`gate/ConfirmGate`、`web/AiController` 的通道契约）。
> 证据等级：**全部为实测**（单测真实执行；原始响应与工具结果文本照录自实跑输出）；未实测到的一律标注【未实测】/【待裁决】。
> 施工日期：2026-10-07　分支：`main`　施工前 HEAD：`4d3b76c`　（本棒**未执行任何 git 写操作**）
> 范围边界：**只做后端**（`backend/`）。前端 `frontend/` 一行未动（FormRenderer 属 GF-B）；`docs/` 下只新增本文件。

## 0. 元信息与脱敏口径

| 项 | 值 |
|---|---|
| 仓库 | `<REPO_ROOT>`（后端模块 `backend/`） |
| 工具链 | JDK 21 / Maven `<MAVEN_HOME>` / Spring Boot 3.5.14 / Spring AI 1.1.x（工具 Schema 与循环由官方提供）/ JUnit 5 + AssertJ + Mockito + MockMvc |
| 外部依赖（本棒） | **无**：全部验证在单测层完成，不连 Redis、不连模型、不起 Web 容器（`@SpringBootTest` 用 H2 内存库，另有两个 standalone MockMvc 用例不启 Spring） |
| 单测命令 | `"<MAVEN_HOME>/mvn.cmd" -B test`（工作目录 `<REPO_ROOT>/backend`） |
| 原始记录 | `<TMP>/gfa-mvn-test-final.log`（全量单测）、`<TMP>/gfa-dump.log`（工具描述 / 校验输出原文）、`<TMP>/gfa-http-dump.log`（HTTP 契约原文） |
| 脱敏 | 本机绝对路径 → `<REPO_ROOT>` / `<MAVEN_HOME>` / `<TMP>`；**本棒未使用任何 AI key**（无模型调用），故无 key 可脱敏；无用户名、无本机盘符样式路径 |

---

## 1. 交付物与代码改动清单

### 1.1 新增（4 文件：2 生产 + 2 测试目录）

| 文件 | 作用 |
|---|---|
| `backend/…/ai/tool/FrontendToolGuard.java` | **通用扩展点**：前端通道工具的"下发前 / 回灌前"复核接口 + `Verdict`（放行/拒绝 + 机器可读码 + 原因清单 + 渲染文本）。按工具名自认领（`supports`），挂起机制对具体工具名零知识 |
| `backend/…/ai/form/GenerativeFormRules.java` | **白名单与校验的事实源**（纯函数、无 Spring）：白名单常量、上限常量、`argumentReasons`/`schemaReasons`/`resultReasons`、严格 ISO 日期判定 |
| `backend/…/ai/form/GenerativeFormGuard.java` | 把模型给的 schema 与前端回灌的值交给上述规则，产出 `Verdict`（只有解析 + 组装，无校验口径） |
| `backend/src/test/…/ai/form/GenerativeFormSchemaRulesTest.java` 等 6 个测试类 | 见 §6.1 |

### 1.2 修改（8 生产 + 4 测试）

| 文件 | 改动 |
|---|---|
| `ai/tools/AiTools.java` | 新增 `generative_form` 工具（`@ToolScope({"page:tasks","task:*"})` + `READ` + `FRONTEND`）与三个入参 record（`FormSpec`/`FormField`/`FormOption`）；类注释的前端工具清单补入该工具 |
| `ai/gate/SpToolCallingManager.java` | 构造注入 `List<FrontendToolGuard>`；`awaitFrontend` 在挂起前过**入参闸门**，被拒则走 `rejectFrontendArguments`（不挂起、不下发、只回填错误结果 + `frontend_tool_result(ok=false)`） |
| `ai/gate/ConfirmGate.java` | 构造注入 `List<FrontendToolGuard>`；`submitFrontendResult` 增加 5 参重载（`cancelled`）与三条分支；新增 `Outcome.REJECTED`、`Submission#verdict`、`dismiss(...)` |
| `ai/run/PendingToolCall.java` | 新增两个状态常量：`FRONTEND_CANCELLED`（用户放弃）、`REJECTED_ARGUMENTS`（入参被安全闸拒绝） |
| `ai/web/AiController.java` | `/frontend-tool-result`：转发 `cancelled`、新增 `Outcome.REJECTED → 400`（`rejectedFrontendResult`，带原因清单与"挂起仍在等待"说明）；类注释的端点表同步 |
| `ai/web/FrontendToolResultRequest.java` | 新增可选字段 `cancelled`（缺省 false，老客户端零改动） |
| `ai/config/AiProperties.java`、`ai/run/ResumeService.java` | **仅注释**：续跑准入的"已有结论条目"枚举补入新状态（行为未变：`ResumeService` 按"非 PENDING 即可续跑"判定） |
| `src/test/…/ToolFrameSequenceConformanceTest.java`、`ConfirmGateWiringTest.java`、`SpToolCallingManagerScopeGuardTest.java` | 三处手工构造 `ConfirmGate`/`SpToolCallingManager` 的点补 `List.of()`（第 9/6 个构造参数）；**断言一条未改** |
| `src/test/…/ToolRegistryDisclosureTest.java` | 把 `generative_form` 补入"全部前端通道工具"清单与防线①↔③全量对账的 `allTools` 清单（清单不补就与事实不符；**断言未削弱**，只是覆盖集合变大） |

---

## 2. schema 白名单设计（类型 / 属性 / 上限）

工具入参形态：`{"form": { … }}`（只接受 `form` 一个键，多出的键直接拒绝）。

### 2.1 表单级属性白名单（`form`）

| 属性 | 必填 | 约束 | 说明 |
|---|---|---|---|
| `scenario` | **是** | `FILTER` / `CLARIFY` | 显式声明用途，对应 DC-15 的两个场景（场景不是靠猜的：它同时被回灌结果文本与排障记录带上） |
| `title` | 否 | 文本 ≤ 100 字符 | 表单标题 |
| `fields` | **是** | 数组，1..20 | 字段列表 |

其余键（`layout`/`style`/`submitLabel`/`description`…）**一律拒绝**（`form 不允许的属性：…`）。

### 2.2 字段级属性白名单（`fields[]`）

| 属性 | 必填 | 约束 |
|---|---|---|
| `key` | 是 | `^[A-Za-z_][A-Za-z0-9_]*$`，≤ 40 字符，**表单内唯一**（回灌结果按此键取值） |
| `label` | 是 | 非空文本，≤ 100 字符 |
| `type` | 是 | **白名单六型**：`text` / `number` / `boolean` / `date` / `enum` / `multi_select` |
| `required` | 否 | 布尔（缺省 false） |
| `defaultValue` | 否 | 类型必须与 `type` 相符；`enum`/`multi_select` 必须在选项内；`date` 必须是合法日历日 |
| `options` | `enum`/`multi_select` **必填**，其余类型**禁止** | 数组 1..50，元素**只能是对象** `{"value":"…","label":"…"}`（`value` 必填 ≤100、表单内选项值唯一；`label` 可选 ≤100）。**不接受裸字符串** |
| `placeholder` | 否 | 仅 `text`/`number`/`date` 可带；≤ 100 字符 |

选项对象的白名单是 `value`/`label` 两个键；多出的键（如 `icon`）拒绝。

### 2.3 类型 × 属性适用矩阵

| type | required | defaultValue | options | placeholder |
|---|---|---|---|---|
| `text` | ✓ | ✓（字符串 ≤200） | ✗ | ✓ |
| `number` | ✓ | ✓（JSON 数字，非字符串） | ✗ | ✓ |
| `boolean` | ✓ | ✓（布尔） | ✗ | ✗ |
| `date` | ✓ | ✓（`yyyy-MM-dd` 合法日历日） | ✗ | ✓ |
| `enum` | ✓ | ✓（须在选项内） | **必填** | ✗ |
| `multi_select` | ✓ | ✓（字符串数组，元素须在选项内、不重复） | **必填** | ✗ |

### 2.4 上限表（值 → 理由）

| 上限 | 值 | 理由 |
|---|---|---|
| 字段数 | ≤ 20 | 一张对话内表单的可用上限；同时把模型上下文里的表单体量封顶 |
| 单字段选项数 | ≤ 50 | 下拉可读性 + 回灌值集合可枚举校验 |
| `key` 长度 | ≤ 40 | 回灌 JSON 的键成本；正则同时限定字符集（防注入奇怪键名） |
| `label` / `title` / `placeholder` / 选项文本 | ≤ 100 | 展示位长度；防"用 label 当正文"的滥用 |
| 单字段文本值 | ≤ 200 | 回灌值的单值上限 |
| `form` 序列化长度 | ≤ 8000 字符 | 工具入参体积上限（一次拒绝而不是让它进上游请求） |
| 回灌值 JSON 长度 | ≤ 8000 字符 | 与上一条对称；同时约束进模型上下文的工具结果 |

### 2.5 安全边界建立在"白名单"，不是"内容黑名单"

- **不在白名单内的类型与属性一律拒绝** ⇒ 模型没有"自由拼 HTML/脚本"的表达能力：`type:"html"`、`attributes`、
  `onClick`、`style` 之类根本进不来（原始输出见 §3.2）。这正是 DC-15 要求的限定形态 ——
  与 DC-14 不采纳项 N2（完整 A2UI/OpenGenUI 渲染层：任意组件树）的区别就在这里：**只有六种预定义控件**。
- **文案内容（title/label/placeholder/选项文本）不做黑名单式启发式过滤**：前端以文本插值渲染（非富文本/
  非 `v-html`），字符串只是数据；滥用面由长度上限封顶。**这条假设有前提**：若 GF-B 引入富文本渲染，
  内容级校验必须回到 `GenerativeFormRules` 补 —— 已写进类的 Javadoc，避免后来者默认"已经安全了"。
- **校验看的是入参原文，不是反序列化后的对象**：`GenerativeFormGuard` 拿的是
  `AssistantMessage.ToolCall#arguments` 原文（`PendingToolCall#arguments`）——若改用反序列化后的 record，
  Jackson 会静默丢弃未知属性，"白名单外的属性一律拒绝"就无从判定。`FormSpec` 等 record 只为生成
  官方 JSON Schema（让模型看到内层形状），两者的属性名由用例对账锁定（§6.1 的契约用例）。

### 2.6 模型看到的形状（官方生成的 JSON Schema 摘要）

`AiTools#generativeForm` 用类型化 record 入参，官方 `JsonSchemaGenerator` 生成的内层结构（原文照录，节选）：

```json
{"type":"object","properties":{"form":{"type":"object","properties":{
  "fields":{"type":"array","items":{"type":"object","properties":{
     "key":{"type":"string"},"label":{"type":"string"},"type":{"type":"string"},
     "required":{"type":"boolean"},"defaultValue":{}, "options":{"type":"array","items":{
        "type":"object","properties":{"value":{"type":"string"},"label":{"type":"string"}},"required":["value"]}},
     "placeholder":{"type":"string"}},
     "required":["key","label","type"]}},
  "scenario":{"type":"string"},"title":{"type":"string"}},
  "required":["fields","scenario"]}},"required":["form"],"additionalProperties":false}
```

`additionalProperties:false` 与闸门的"只接受 `form` 键"同口径：模型按 Schema 生成的入参天然过闸。

---

## 3. 校验与回灌语义

### 3.1 两道闸门的位置（都不在工具方法体里）

| 闸门 | 位置 | 触发 | 不通过时的行为 |
|---|---|---|---|
| **① 下发前（schema 白名单）** | `SpToolCallingManager#awaitFrontend`（挂起机制内） | 通道 `FRONTEND` 且声明了复核器 | **不挂起**（不发 `frontend_tool_request`、不占等待上限、前端看不到非法表单）+ 不认领/不记台账；只把结构化错误结果回填模型，并补一帧 `frontend_tool_result(ok=false,status=REJECTED_ARGUMENTS)` 收敛工具卡 |
| **② 回灌前（值类型复核）** | `ConfirmGate#submitFrontendResult` | `POST /api/ai/frontend-tool-result` | **不消费挂起**（状态仍 `PENDING`、不发帧、不唤醒）+ HTTP 400 带码与原因清单 ⇒ 前端可修正后重试 |

为什么闸门不放在 `AiTools#generativeForm` 方法体里：那是**哨兵桩**（`FRONTEND_STUB`），正常路径永不执行
（方法与既有两个前端工具一致）。放进方法体等于"校验永远不会跑"。

### 3.2 schema 被拒（原始输出照录，`<TMP>/gfa-dump.log`）

输入（模型生成的越权表单：HTML 类型 + 注入属性）：

```json
{"form":{"scenario":"FILTER","fields":[{"key":"content","label":"内容","type":"html","html":"<img src=x onerror=alert(1)>"}]}}
```

回填模型（并作为 `frontend_tool_result.result`）的文本：

```
FORM_SCHEMA_REJECTED：表单 schema 未通过白名单校验，工具未执行（未向前端下发表单）
被拒原因：
1. fields[0] 不允许的属性：html（字段属性白名单：defaultValue/key/label/options/placeholder/required/type）
2. fields[0].type=html 不在白名单类型内（text/number/boolean/date/enum/multi_select）
```

要点：**一次给全所有原因**（不是首错即返）——模型改一处再被拒一次的往返被消掉；编号清单也让"哪一处不合规"可编程处理。

### 3.3 回灌被拒（原始输出照录）

schema（合法，四字段）：

```json
{"form":{"scenario":"FILTER","fields":[
 {"key":"keyword","label":"关键字","type":"text","required":true},
 {"key":"minAmount","label":"最小金额","type":"number"},
 {"key":"effectiveDate","label":"生效日期","type":"date"},
 {"key":"scopeKey","label":"范围","type":"enum","options":[{"value":"XN"},{"value":"HD"}]}]}}
```

前端回灌值：

```json
{"keyword":7,"effectiveDate":"2026-02-30","scopeKey":"ZZ","hacked":"<script>"}
```

拒绝结论：

```
FORM_RESULT_REJECTED：回灌的表单结果未通过类型复核，本次回灌被拒（挂起仍在等待，可修正后重试）
被拒原因：
1. 未知字段：hacked（表单字段：keyword, minAmount, effectiveDate, scopeKey）
2. 字段 keyword 期望 text（字符串），实际是数字
3. 字段 effectiveDate 期望合法日期（yyyy-MM-dd），实际是 "2026-02-30"
4. 字段 scopeKey 的值 "ZZ" 不在选项内（XN/HD）
```

合规回灌（`{"keyword":"CNY","minAmount":10,"effectiveDate":"2026-10-07","scopeKey":"XN"}`）判定为放行
（`accepted=true`），**原样**作为工具结果回填模型（DC-15 的"回灌的就是字段 key→值的 JSON"）。

### 3.4 HTTP 契约（原始响应照录，`<TMP>/gfa-http-dump.log`）

```
POST /api/ai/frontend-tool-result {"runId":"r-1","toolCallId":"c-1","result":"{\"keyword\":7}","source":"http-post","cancelled":false}
→ 400
{"code":"FORM_RESULT_REJECTED","message":"回灌的表单结果未通过类型复核，本次回灌被拒（挂起仍在等待，可修正后重试）","reasons":["字段 keyword 期望 text（字符串），实际是数字"],"runId":"r-1","toolCallId":"c-1","name":"generative_form","status":"PENDING","duplicate":false,"accepted":false,"instanceId":"instance-1","note":"回灌被拒：挂起仍在等待（状态未变），修正后可重试；用户放弃请带 cancelled=true"}

POST 同上（合规值 {"keyword":"CNY"}）→ 200
{"accepted":true,"duplicate":false,"runId":"r-1","toolCallId":"c-1","status":"FRONTEND_RESULT","cancelled":false,"executed":false,"wokeInProcessGate":true,"storedBy":"instance-1"}

POST 同上（{"cancelled":true}）→ 200
{"accepted":true,"duplicate":false,"runId":"r-1","toolCallId":"c-1","status":"FRONTEND_CANCELLED","cancelled":true,"executed":false,"wokeInProcessGate":true,"storedBy":"instance-1"}
```

**HTTP 码的分工（刻意不合并）**：

| 情形 | 码 | 码值 | 挂起状态 |
|---|---|---|---|
| 未决工具 | 409 | `UNKNOWN_TOOL_CALL` | —— |
| 已决条目再提交（含带取消重复提交） | 409 | `DUPLICATE_TOOL_CALL_ID` | 不变 |
| 回灌内容不合工具契约 | **400** | `FORM_RESULT_REJECTED` 等 | **不变（仍 PENDING，可重试）** |
| 缺 runId/toolCallId | 400 | `BAD_REQUEST` | —— |

### 3.5 一次提交 = 一次消费；被拒不算消费

- 复核**通过** ⇒ 条目落 `FRONTEND_RESULT`（既有语义）+ 回灌帧 + 唤醒；重复提交 ⇒ 409 `DUPLICATE_TOOL_CALL_ID`。
- 复核**不通过** ⇒ 条目**保持 `PENDING`**：这是与"已消费"最关键的差别 —— 用户/前端还能改值再提交，
  而不是"提交错了就再也没机会"（也不是把一次错值写进会话历史）。
- **取消优先于复核**：`cancelled=true` 时不看值是否合规（用户放弃填了一半的错表单也必须能退出，
  否则挂起只能等到超时）。次序依据：放弃是兜底出口，复核是质量控制。

---

## 4. 取消终态

### 4.1 既有语义勘察（先看有没有，再决定补什么）

| 既有出口 | 语义 | 能否表达"用户关掉表单" |
|---|---|---|
| `CANCELLED`（`CancelGate#cancel` + `RunStore#cancelPendings`，`POST /api/ai/cancel/{runId}`） | **整轮**被取消（终态 `done{cancelled:true}`） | ✗（代价过大：用户只是不想填这张表，不该废掉整轮） |
| `TIMEOUT`（`ConfirmGate#expire`，reason `FRONTEND_TIMEOUT`） | 前端/人**没响应**，等满 `app.ai.hitl.timeout`（默认 120s）自动取消 | ✗（语义是"没人响应"，不是"用户放弃"；而且要白等 120s） |

结论：**既有语义里没有"用户主动放弃一次前端调用"**，本棒补上（并在 `PendingToolCall` 注释里写明与 `CANCELLED` 的分工）。

### 4.2 补齐的终态

| 项 | 值 |
|---|---|
| 触发 | `POST /api/ai/frontend-tool-result` 带 `cancelled: true`（`result` 被忽略） |
| 条目状态 | `FRONTEND_CANCELLED`（reason `FRONTEND_DISMISSED`，`executed=false`） |
| 结局帧 | `frontend_tool_result(ok=false, status=FRONTEND_CANCELLED)` —— 与"前端超时"**同帧型**，前端不必为新终态再解析一种帧；同时该帧让前端的挂起卡收敛（`pendingCall` 被清） |
| 回填模型的文本 | `FRONTEND_CANCELLED：用户取消了该前端操作（关闭表单/放弃填写），本次调用未获得任何数据；如需继续，请改为向用户询问或换用其它方式。` |
| 通用性 | 该字段是**前端通道级**能力（不限 `generative_form`）：任何前端工具都可用它报告"用户放弃" |
| 幂等 | 已决条目再带 `cancelled` 提交 ⇒ `DUPLICATE_TOOL_CALL_ID`（409），不会把终态改写成取消 |
| 续跑 | `FRONTEND_CANCELLED` 是**已决**状态：续跑按"复用 `resultText`、不执行"处理（不会被当"待外部输入"卡住） |

---

## 5. 披露上下文决策（DC-15 两个场景的接线）

### 5.1 决策表

| 场景 | 现场（披露上下文） | 标签 | 理由 |
|---|---|---|---|
| ① 筛选条件填写 | 任务中心 + 导出向导各步骤 | `page:tasks`、`task:*`（覆盖 `task:EXPORT` 含 `QUERY_COND`） | "条件不必跳转向导"的起点是任务中心的 AI 面板；向导内（含查询条件步）同样可能要让用户在对话里填 |
| ② 询问澄清 | 任意**任务上下文**（含导入向导各步骤） | `task:*` | 信息不足与任务类型无关；`task:*` 已是既有标签（`check_job_status` 在用），不新增语义 |

最终注解：`@ToolScope({"page:tasks", "task:*"})`（`AiTools#generativeForm`）。

### 5.2 为什么**不**用 `*`

- `definitions` / `data` 等"只看不改"的页面没有需要收集的条件或澄清动作 ⇒ 按 DC-14 渐进披露的
  "最小必要工具集"同向处理（与 `navigate_to` 那种"任意页面进入向导"的入口工具不同）。
- **可逆性**：若实测确有"任意页面都要澄清表单"的需求，只改这一处注解（披露与防线③同源，
  不会出现"披露了却被拦"的漂移），改动面一行。
- **本棒实测的披露矩阵**（`GenerativeFormDisclosureTest`，11 个上下文逐点断言）：
  `page:tasks` ✓；`task:EXPORT/{SELECT_DEFS,QUERY_COND,EXPORT}` ✓；`task:IMPORT/{SELECT_DEFS,UPLOAD,PRECHECK,IMPORT,PUBLISH}` ✓；
  `page:definitions` ✗；`page:data` ✗；空上下文 ✗。同一用例逐点核对 `matchContext`（防线③）与披露集（防线①）一致。

### 5.3 与既有披露规则的关系

逐条核对既有规则（`ToolRegistryDisclosureTest` / `AiTools` 的披露口径）：

| 既有规则 | 本棒是否冲突 |
|---|---|
| `definitions/data` 不披露向导动作与 `create_task` | 不冲突（本工具也**不**在这两个页面披露） |
| `page:tasks` 披露向导动作 + `start_export`/`start_precheck`，不披露 `start_import`/`start_publish` | 不冲突（新增一个 `READ`+`FRONTEND` 工具，不改变作业类工具的披露） |
| 任务类型标签 `task:*` 已有使用者（`check_job_status`） | 不冲突（复用标签语义，未新增标签） |
| 前端动作表的 `allowPages`/`allowSteps` 兜底（前端侧防线三） | **见 §8**：前端的动作表尚未含 `generative_form`（GF-B 的工作），故本棒只保证"后端披露 + 后端闸门"两个面 |

---

## 6. 测试映射与运行结果

### 6.1 新增 62 用例的映射（按 `^\s*@Test(\s*$|\()` 精确统计）

| 测试类 | 用例数 | 覆盖点 |
|---|---|---|
| `ai/form/GenerativeFormSchemaRulesTest` | 22 | **每个白名单类型正例**（六型同表 + 最小表单 + 字段数上限 20）；反例：自由 HTML（`type:"html"`/`script`）、任意属性注入（`html`/`attributes`/`onClick`/`style`/`maxLength` 逐条报出）、表单级未知属性、选项对象多键、未知/缺失 `scenario`、字段超限（21）、空字段列表、超长（title/label/placeholder/key）、键非法/重复、`enum`/`multi_select` 缺选项、非选项类型带 `options`、裸字符串选项与重复选项值、`placeholder` 用在非文本类型、`defaultValue` 类型不符（number/date/boolean/enum/multi_select 五种错法）、schema 超长、非对象入参/多余入参键、**一次给全多条原因**；另含严格 ISO 日期判定（含 `2026-02-29` 非法、`2024-02-29` 合法） |
| `ai/form/GenerativeFormSubmissionRulesTest` | 14 | 六型值的正例 + 可选字段缺省/显式 null + 假值与 0 值正例；反例：number 给文本、boolean 给文本、text 给数字/对象、非法日期四种、enum 越界、multi_select 未知/重复/非数组、未知字段键、缺失必填、必填空串、必选空数组、结果非对象（数组/字符串/数字/布尔/null）、结果超长、schema 不可用 |
| `ai/gate/GenerativeFormGateTest` | 8 | **下发闸门**：合法 schema 正常挂起并回填前端值；非法 schema **不挂起**（`awaitFrontendResult` 一次未调用）+ 不认领 + 条目落 `REJECTED_ARGUMENTS` + 发 `frontend_tool_result(ok=false)`。**回灌闸门**：合规 ⇒ `ACCEPTED`/`FRONTEND_RESULT`/唤醒；不合规 ⇒ `REJECTED`、状态仍 `PENDING`、不落库不发帧；取消 ⇒ `FRONTEND_CANCELLED` + 结局帧；取消优先于复核；**幂等重复回灌**（含带取消）⇒ `DUPLICATE`；无复核器的既有前端工具仍收任意文本结果（回归） |
| `ai/form/GenerativeFormContractTest` | 7 | 工具名常量 == `@Tool(name)`；**record 属性名 == 白名单属性集**（三份描述互相对账）；生成的 JSON Schema 顶层 `additionalProperties:false` 且只有 `form`；复核器只认领自己的工具；入参非 JSON、自由文本回灌被拒且文本给出"应带 `cancelled=true`"的指引 |
| `ai/tools/GenerativeFormDisclosureTest` | 7 | 元数据（`FRONTEND` + `READ`）；披露矩阵（任务中心/导出三步骤/导入五步骤 ✓，`definitions`/`data`/空上下文 ✗）；防线①↔③逐点一致（11 上下文）；桩体哨兵；复核器 Bean 装配；**只读端点 `GET /api/ai/tools` 对照**（`page:tasks` 有、`page:data` 无、`task:EXPORT/QUERY_COND` 有） |
| `ai/web/AiControllerFrontendResultTest` | 4 | 400 契约（码/原因/`status=PENDING`/note 指引）；`cancelled` 转发与响应回显；409 两码不回归；缺参 400 |

### 6.2 全量单测结果（原始摘要照录，`<TMP>/gfa-mvn-test-final.log`）

```
$ cd <REPO_ROOT>/backend && "<MAVEN_HOME>/mvn.cmd" -B test
[INFO] Tests run: 246, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- 用例口径：`grep -rnE "^\s*@Test(\s*$|\()" src/test | wc -l` = **246**（基线 184 + 本棒新增 62），40 个测试类（`-- in <类名>` 去重计数）。
- 本棒新增类的逐类结果：`GenerativeFormSchemaRulesTest 22`、`GenerativeFormSubmissionRulesTest 14`、
  `GenerativeFormGateTest 8`（含 `$SuspensionGate 2` + `$SubmissionGate 6`）、`GenerativeFormContractTest 7`、
  `GenerativeFormDisclosureTest 7`、`AiControllerFrontendResultTest 4`。
- 既有用例的连带改动只有"构造参数补位"（3 处）与"清单补全"（1 处），**无一条断言被放宽或删除**
  （可用 `git diff -- backend/src/test` 逐行核对：改动仅为 `List.of()` 实参与 `FRONTEND_TOOLS`/`allTools` 的元素追加）。

### 6.3 未覆盖（诚实标注）

- **真实模型生成表单 schema 的端到端**：需前端 FormRenderer（GF-B）与真实模型，本棒【未实测】。
- **前端侧的动作表白名单校验**（前端防线三）：`frontend/src/utils/frontend-tools.ts` 未登记 `generative_form`，
  本棒【未实测】其表现（预判见 §8.3）。
- **跨进程续跑携带新状态的实跑**：`FRONTEND_CANCELLED`/`REJECTED_ARGUMENTS` 在 `ResumeService` 里走
  "非 PENDING ⇒ 复用 `resultText` 不执行"的既有分支（逻辑与 `TIMEOUT`/`BLOCKED` 同路），
  单测覆盖了状态与文本，**未做真实的进程重启续跑实测**（属 GF-B/E2E 场景）。

---

## 7. E2E 影响结论：**已照跑一轮，22 用例全绿**（含逐条理由）

补跑（2026-10-07，指挥官裁决 #5）：`scripts/verify-e2e.ps1` **一行未改**，按既有脚本照跑一轮 ——
**PASS=22 / FAIL=0**（Q8 项 2/2，整轮约 70 秒），对既有 E2E **零行为扰动**。实测要点：
- `generative_form` 披露后 TC15 的**存在性断言未被翻转**（`page:tasks` 披露工具数 12 → 13）；
- 全程 **0 次** `FORM_SCHEMA_REJECTED` / `FORM_RESULT_REJECTED` / `FRONTEND_CANCELLED`，**0 次挂起超时**；
- 真实模型该轮**未触发**表单，故 §6.3 的"真实模型生成表单 schema 的端到端"仍属 GF-B 场景。

下表逐条核对的**断言影响面**，与补跑实测一致：

| 断言（脚本行号） | 内容 | 是否受本棒影响 |
|---|---|---|
| TC15 `verification:1071-1073` | `page:tasks` **必须包含** 5 个工具名（`list_config_defs`/`create_task`/`navigate_to`/`select_definitions`/`start_export`） | **否**：新增工具只会让集合变大，`-notcontains`/`-contains` 的存在性断言不翻转 |
| TC15 `1074` | `page:tasks` **不得包含** `start_publish` | 否（本棒未改作业类工具的 scope） |
| TC15 `1075-1076` | `task:IMPORT/PUBLISH` 必须含 `start_publish`、不得含 `start_export` | 否（同上） |
| TC15 `1078-1089` | 后端 DEBUG 行的披露清单 **必须等于**只读端点 | 否：两边同源（`ToolRegistry#forContext`），新增工具在两边**同时**出现 |
| TC15 `1090` | PASS 文案里打印工具个数 | 否：仅打印（`$tTasks.Count`），无断言 |
| TC16 `verification:1143` | 至少收到 1 帧 `frontend_tool_request`（工作区动作走前端通道） | 否：向导动作（`navigate_to`/`select_definitions`）本身就是前端通道工具，"打开向导并勾选"的提示词仍会命中它们 |
| TC16 `verification:1145` | 前端工具名至少命中 `{navigate_to, select_definitions, confirm_step, set_condition}` 之一 | 否：本工具名不在该集合内，不会让"至少一个"变假；集合本身未变 |
| Q8 两项 | 日志无 `NoSuchFileException`/`明细写入失败`；无 `No converter for`/`HttpMessageNotWritableException` | 否：本棒新增的日志行（`WARN …安全闸拒绝…`）不含这些串 |
| 其余 TC1–TC14/17–22 | 数据面/作业/会话/历史 | 否：本棒只碰 AI 工具披露与前端工具回灌通道，未改任何服务层/端点语义（`/frontend-tool-result` 只新增可选字段与一个错误分支） |

另外两点：

1. **端到端的表单场景本来就属于 GF-B**（前端 FormRenderer 不存在，无法"用户填表 → 提交"跑通），
   本棒按指令只做后端，端到端表单验证留待 GF-B。
2. **本轮补跑的实测口径**：命令与前置（与 S5b 同口径，2026-10-07 实际执行）：
   `netstat` 确认 18330 空闲 → Memurai 只读 `ping` = `PONG` → 启动后端（独立库、端口 18330、
   key 仅经会话环境变量 `AI_API_KEY=***` 注入、不落文件）→
   `powershell -File scripts/verify-e2e.ps1 -Base http://127.0.0.1:18330 -BackendLog <后端 stdout 日志>`
   → 跑完停进程、删本棒独立库。结果 **PASS=22 / FAIL=0**，耗时无异常（约 70 秒）；
   原先担心的"真实模型在 TC15/TC16 自行调用 `generative_form` 且给出**合法** schema ⇒ 脚本的通用回灌
   （`{"ok":true,"source":"e2e"}` 不是值 JSON）被闸门拒 400 ⇒ 该次挂起等到
   `app.ai.hitl.timeout`（默认 120s）超时"的路径**本轮未出现**（真实模型该轮未触发表单），
   故也不存在挂起超时导致的耗时放大（`Read-Sse` 的停止帧集合里没有 `frontend_tool_result`，
   即便出现该路径也不会让脚本误停）。

---

## 8. GF-B（前端 FormRenderer）对接契约清单

本棒只交付后端；以下是前端必须遵守/可依赖的契约（均已在后端用例里锁定）。

### 8.1 帧（既有帧型，无新增）

| 帧 | 前端拿到什么 |
|---|---|
| `frontend_tool_request` | `name=generative_form`，`args={"form":{…}}`（已过白名单校验，**必为合法 schema**），`toolCallId`、`timeoutSeconds`、`expiresAt`、`callback` |
| `frontend_tool_result` | 结局帧：`ok`、`status`（`FRONTEND_RESULT`/`FRONTEND_CANCELLED`/`REJECTED_ARGUMENTS`/`TIMEOUT`）、`executed=false`、`result` |

### 8.2 回灌（`POST /api/ai/frontend-tool-result`）

- **提交**：`{runId, toolCallId, result: "<字段 key→值的 JSON 对象>", source}` —— `result` 必须是与 schema 相符的值 JSON
  （number 是数、date 是 `yyyy-MM-dd`、enum/multi_select 的值在选项内、未知字段禁止）。
- **用户放弃**：`{runId, toolCallId, cancelled: true}`（`result` 可省）⇒ 条目立刻落 `FRONTEND_CANCELLED`，
  不必等超时。
- **被拒（400）**：`{code:"FORM_RESULT_REJECTED", message, reasons:[…]}` + `status:"PENDING"` ——
  含义是"**挂起仍在等待**"，前端应提示用户改值后重发（同一个 `toolCallId`），或改发 `cancelled:true`。
- 幂等：已决条目再提交（含取消）⇒ 409 `DUPLICATE_TOOL_CALL_ID`。

### 8.3 一处必须注意的**前端能力不可用**路径（【待裁决】见 §9 #3）

现有前端的通用执行器在"工具名未知/能力不可用"时会回灌**一段说明文本**。对 `generative_form` 而言
这段文本**不是**值 JSON ⇒ 会被闸门拒 400、挂起不消费 ⇒ 该轮要等到超时才收尾。因此 GF-B 的正确做法是二选一：

1. 实现表单渲染器（正常路径，回灌值 JSON）；
2. 渲染器不可用（如能力未注册）时，**带 `cancelled:true` 回灌**，让挂起立刻收敛。

这条已用用例锁死语义（`GenerativeFormContractTest#guardRejectsFreeTextResultAndTellsTheFrontendWhatToDo`），
其拒绝文本本身就带着"用户放弃请带 cancelled=true"的指引。

### 8.4 前端需要顺手改掉的过时注释（不属本棒范围）

`frontend/src/utils/frontend-tools.ts` 顶部注释仍写"main 后端 `AiTools` 共 **12** 个 `@Tool`，其中带
`@ToolChannel(FRONTEND)` 的**只有 2 个**"——**本棒之前就已过时**（S4.4d 把 4 个工作区动作标为 FRONTEND 后
实为 6 个），本棒再增 1 个后为：**18 个工具 / 7 个前端通道工具**。`frontend/` 不在本棒范围内，
故只在此登记，请 GF-B 一并更正（它与"前端零改动即可生效"的判断依据直接相关）。

---

## 9. 【待裁决】

| # | 事项 | 本棒的处理 | 备选/影响 |
|---|---|---|---|
| 1 | **澄清场景是否要 `*`（全上下文）** | 取 `{page:tasks, task:*}`（§5.2 的四条理由：`definitions`/`data` 无该场景、渐进披露最小集、可一行放宽） | 若要"任意页面都能发澄清表单"，把注解改为 `{"*"}` 即可（披露与防线③同源，不会漂移）。注意：在 GF-B 之前，放宽会提高"模型在无渲染器页面上发表单⇒等到超时"的概率 |
| 2 | **`cancelled` 字段加在回灌请求体（本棒）vs 新增独立端点** | 加可选字段（老客户端零改动、与既有幂等口径同一入口） | 独立端点（如 `/frontend-tool-cancel`）语义更显式，但要多一套幂等/帧/鉴权口径，成本更高 |
| 3 | **前端"能力不可用"文本回灌被拒 ⇒ 挂起等到 120s 超时** | 保持严格闸门（不削弱校验）；把它写成 GF-B 的对接契约（§8.3）并要求"不可用时带 `cancelled:true`" | 备选：为"前端能力不可用"增设机器可读回灌形态（等于把前端的能力声明引入通道契约，属协议扩展，超出 DC-15 范围，本棒不做） |
| 4 | **选项是否允许裸字符串**（`options:["XN","HD"]`） | 不允许（统一为 `{value,label?}` 对象）：一种形态一条契约，官方 Schema 也能给出内层结构 | 允许双形态更"宽容"，但会让"模型看到的 Schema"与"闸门接受的形状"分叉（本棒的契约用例正是为了防这种分叉） |
| 5 | **新增到披露集的工具对既有 E2E 的行为扰动** | 判定为"不影响判定、只可能影响耗时"（§7 逐条核对），故免跑（已由裁决 #5 覆盖：照跑一轮，PASS=22） | 若指挥官要求实测，按 §7 第 2 点的命令照跑一轮即可 |

---

## 10. 附录：原始记录位置

| 文件 | 内容 |
|---|---|
| `<TMP>/gfa-mvn-test-final.log` | 全量单测输出（246 用例，`BUILD SUCCESS`） |
| `<TMP>/gfa-dump.log` | 工具描述原文、schema 拒绝/回灌拒绝的完整文本（§3.2/§3.3 照录自此处）、白名单常量与上限值打印 |
| `<TMP>/gfa-http-dump.log` | 400/200 三种回灌的原始响应体（§3.4 照录自此处） |

（`<TMP>` = 本棒临时目录，位于仓库之外，不产生 git 噪声。）

---

## 裁决结论（指挥官 K3，2026-10-07）

| # | 事项 | 裁决 |
|---|---|---|
| 1 | 澄清场景放宽到全上下文 | 维持 `{page:tasks, task:*}` —— 与渐进披露最小集同向，GF-B 实测需要再放宽（一行注解） |
| 2 | 取消用可选字段 vs 独立端点 | 维持可选字段 —— 老客户端零改动，复用幂等入口 |
| 3 | "能力不可用"回灌形态 | 维持严格闸门 —— GF-B 契约：前端不可用时必须带 `cancelled:true`；协议扩展不做 |
| 4 | 选项裸字符串 | 维持不允许 —— 模型看到的形状与闸门接受的形状不分叉 |
| 5 | E2E 是否照跑一轮 | 照跑一轮 —— 由提交官验收时执行，实证披露变化不影响 22 用例 |

另：`frontend/src/utils/frontend-tools.ts` 顶部工具计数注释过时（实为 18 个 `@Tool` / 7 个 `FRONTEND`），已于 GF-B 前置修正棒落实（注释口径已更正为 18/7）。
