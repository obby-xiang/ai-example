<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-087 · 施工复核 · 结论（双 verdict）（45653e92 / agent-1762）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`AiTools.java`、`ResilientChatService`、`backend/src/main/java`、`ToolRegistry.java`。
> **稿态**：历史中间稿 —— 该 wire 第 `m53` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt3` 条有正文的 assistant 消息（共 4 条有正文），末条有正文消息为 `m62`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `45653e92` / agent `agent-1762`（`main` = 主会话）；原字符 5,903（链内 1 段、合计 5,903）。
> **链号 / 类别**：`MT-087` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `8%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `2%`。
> **与末条归档（138 件）的关系**：末条 `m62`（3,019 字符）未入末条归档。
> **脱敏**：路径 1 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

## 结论（双 verdict）

- **规格 verdict：通过，可验收。** R3-01、R3-25 的验收口径均达成，且没有夹带新策略（无"未知列即失败"、无 0 行守卫）。
- **质量 verdict：可验收（无 Critical、无代码级 Important 缺陷）。** 2 条 Important 均为验收前置/测试补强，Minor 单列。

本次评审全程只读：未改文件、未提交、未起停服务、未重跑任何测试（仅读日志与已编译产物）。

## 元数据核对（未编造）

- `R3-01`、`R3-25` 与审计报告 `docs/research/排查-A2A5-工具定义与组件复用-2026-10-24.md:180`、`:407` 对应属实；审计给的两处编号、11 工具名单与本次清单逐项一致。
- 实测口径：`AiTools.java` 中 `@Tool(name` = **34**，`@ToolChannel(ToolMeta.Channel.FRONTEND)` = **23**，故 BACKEND = **11**，与报告一致。
- 快照 diff `<LOCAL_WORK>/_p2-review/A-tools-excel.diff` 只含 4 个文件（AiTools + ExcelReader + 2 个新测试），与报告"改动表"逐行对得上；工作区另有 `pom.xml`、`ResilientChatService`、`ErrorCodes`、`GlobalExceptionHandler` 等大量他人未提交改动，归属只能以该 diff 为准，diff 内确实没有这些文件。

## 证据核验（我独立复核的部分）

| 主张 | 复核结果 |
|---|---|
| RED：5 例 3 失败 | 属实。`_r3-01-25-red2.log` 中 `list_config_defs` 原始结果 = `"\"- CURRENCY（货币字典）层级:GLOBAL 字段数:1"`、挂转换器集合 = `[]`、mismatch 用例 `Expecting code to raise a throwable` |
| 快照 29 + 31 绿 | 属实。两份日志末行 `Tests run: 29/31, Failures: 0, Errors: 0`，且含新增的 2 + 3 例 |
| 副本与工作区 4 文件字节一致 | 属实（`cmp` 全 SAME）；副本与工作区其余 10 个差异文件（RedisAvailability / FrameSizeLimiter / ErrorCodes / GlobalExceptionHandler / messages / FakeLeaseRedis 等）与本改动无交集 |
| 主源码可编译 | **比报告更强**：`backend/target/classes/.../AiTools.class`、`ExcelReader.class` 时间戳 **16:53**（晚于源码 16:22 与 diff 快照 16:47），类常量池含 `requireMetaDefCode`、`文件配置定义不匹配`、`resultConverter`，可确认工作区源码已成功编译本次改动 |
| 未跑全量 | 属实。13 份日志里最大分母只有 31；"最终全量待合流"是诚实披露，不是掩饰 |

## 逐项审查

### 1) 注解是否可能误挂 —— 不会，且有三级证据

- 11 处 `resultConverter` 全部落在 11 个 BACKEND 工具上（`backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:98,127,171,187,208,221,261,315,336,353,365`），23 个 FRONTEND 一处未加；这 11 个方法返回类型全是 `String`。
- 生产注册路径确实会读取注解：`ToolRegistry.java:117` 用 `MethodToolCallbackProvider`，其字节码引用 `getToolCallResultConverter`；`@Tool.resultConverter()` 默认常量是 `DefaultToolCallResultConverter`（已从 jar 常量池确认），故"FRONTEND 不挂 = 官方默认"成立。
- 注册期包装不会吞掉转换器：`ToolRegistry.normalizeSchema/applyOverrides` 只换 definition，`OverriddenToolCallback` 两个 `call` 重载都原样委托（`OverriddenToolCallback.java:41-49`），执行路径上的转换器不变。
- 误伤面已排除：`PlainTextToolResultConverter.convert` 对非 `CharSequence` 仍委托官方 JSON，所以即使将来给返回对象的后端工具挂上也不会破坏结构；真正的风险只剩"该挂没挂/多挂"，而这两点分别由运行时 11 例与静态集合断言（`hasSize(34)` + `containsExactlyInAnyOrderElementsOf`）钉住。

### 2) 模型侧测试是否真实 —— 真实，但有一处"镜像"而非端到端

- 第一例走官方 `MethodToolCallbackProvider` 抓原始返回值，再按 `ToolResultEnvelope.of(...).toModelJson(limiter, name)` 还原模型可见 `message` —— 这与生产 `SpToolCallingManager.java:1376` 的 `modelData` 是同一行逻辑，忠实。RED 里两条断言同时红，说明它测的正是转换器，不是巧合。
- 缺口（不构成缺陷）：没有用例穿过 `SpToolCallingManager` 断言这 11 个工具的 message；`AiToolsInvocationTest` 走真链但不校验引号。因为 `modelData` 只有一行镜像，风险低。

### 3) Excel 空值/缺失/兼容路径

| 路径 | 代码行为 | 覆盖 |
|---|---|---|
| 无 `_meta` | `readMetaSheet` 返回 null → 表头回退 | 有用例（`fileWithoutMetaSheetStillFallsBackToHeaderMatching`） |
| `_meta` 存在、`def_code` 为空 | `isBlank` → 跳过比对，表头回退 | **零覆盖**（用例只删整张表，走的是 null 分支；删掉 `isBlank` 这行仍全绿） |
| `_meta` 存在、首列不是 `def_code`（更老的文件形态） | `firstIsDefCode=false` → `defCode=null` → 跳过比对 | 零覆盖 |
| `_meta` 存在但第 1 行缺失 | 返回 null → 回退 | 零覆盖（无风险，逻辑同上） |
| 码不一致 | 抛 `EXCEL_COLUMN_MAPPING_INVALID`，消息含两个码 | 有用例，正/负配对（一例抛、一例同码正常读行），且 mismatch 用例不依赖表头差异，精准隔离了 `def_code` 这一条 |
| 空单元格/NPE | `DataFormatter.formatCellValue(null)` 返回空串（既有 `isRowEmpty`/第 98 行长期如此处理稀疏行），新增的 `row.getCell(0)` 无 NPE 风险 | — |

其他：`withDataRow`/`withoutMetaSheet` 用真实 POI + 生产 `ExcelTemplateBuilder` 造件，不是 mock 造件，属于该层首个直接单测（此前只有作业级回环）。

### 4) 异常映射

链路清楚：`ExcelReader.java:111` 抛 `CodedIllegalArgumentException(EXCEL_COLUMN_MAPPING_INVALID, "文件配置定义不匹配：文件为 A，当前目标为 B…")` → 两条唯一调用方 `PrecheckJobRunner.java:138`、`ImportJobRunner.java:107` 均 `catch (Exception)` → 写 `ValidationIssue(ERROR, "检查过程出错: …")`、item FAILED、`totalErrors>0` ⇒ 作业 FAILED、其余配置项继续。`ValidationIssue` 表结构本身没有 code 列，所以机器码在作业侧只能退化到 message —— 这是既有设计，不算本改动的缺陷；若将来有同步 HTTP 路径，`GlobalExceptionHandler.handleIllegalArg` + `ErrorBodyFactory.codeOf` 会给出 400 + 该码。

### 5) 测试有效性

- RED 是真红（3 例失败，信息与缺陷一一对应，含"`{\"level\":\"GLOBAL\"}` 执行结果首字符为引号"）。
- 绿是真绿（29/31 全绿，且两批不相交、合计恰好覆盖报的 11 个测试类）。
- 唯一不足：兼容分支（`isBlank`/旧 `_meta` 形态）可被删改而全绿，见 Important-1。

## Critical

无。

## Important（附修法）

**I-1 兼容承诺"旧文件未写 def_code 时回退"没有任何用例兜底（建议合并前补，成本 ~15 行）。**
`ExcelReader.java:109` 的 `metaDefCode.isBlank()` 分支零覆盖，而它正是"合流后会不会静默挡住存量文件"的唯一开关。修法：在 `ExcelReaderMetaDefCodeTest` 增加两例——

```java
@Test void blankMetaDefCodeFallsBackInsteadOfFailing() // _meta 第 1 行 cell0 置空串，读 ROLE_DICT 应成功并走表头映射
@Test void metaWithoutDefCodeHeaderIsTreatedAsLegacy()  // 把 labelRow cell0 改成 "field_code"，不应抛错
```

**I-2 全量 + e2e 未跑，属验收前置（非代码缺陷）。**
本改动的影响面是"11 个工具的模型可见文本 + 所有 Excel 读取"，只在副本跑了 60 例。修法：并发构建清空后在工作区跑 `mvn test` 全量 + `scripts/verify-e2e.ps1`；关注点我已预先排除一项——`scripts/verify-e2e.ps1` / `m2t2a-smoke.mjs` 里没有对工具 message 前导引号的黄金断言（全是 `-like '*…*'`），所以 R3-01 不会打红 e2e 文案断言；需重点看的是"错文件上传/0 行"相关链路是否有人工构造的跨配置用例。

## Minor（单列，不阻断验收）

1. `ExcelReaderMetaDefCodeTest.withoutMetaSheet(byte[], String sheetName)` 的 `sheetName` 参数未使用，删掉或用于断言工作表名。
2. `backendBusinessToolsReachTheModelAsPlainTextNotJsonString` 只断言 `doesNotStartWith("\"")`，偏弱；建议改为断言 `raw` 与工具正文完全相等（例如 `list_config_defs` 断言 `contains("\n")` 且不含 `\\n` 字面量），并对 `message` 做同样断言。
3. 11 个工具名在测试里硬编码 + `hasSize(34)` 双钉：与"下一任务改描述/风险"不冲突，但任何新增工具都要同步维护；可考虑从注解派生期望集（`BACKEND ∩ 返回 CharSequence`）以降低维护成本。
4. 错误码借用 `EXCEL_COLUMN_MAPPING_INVALID`（按任务口径正确），但其 i18n 文案是"无法解析文件列映射 / Column mapping could not be resolved"；`ErrorBodyFactory` 在非中文 locale 下按码兜底，细节（A/B 两个码）会丢。当前只走作业侧、无 HTTP 出口，故不构成缺陷。
5. `requireMetaDefCode` 是严格等值比较（仅 trim）：大小写、NBSP、全角差异都会判不等。POI 生成的文件不会有此问题，仅手工改 `_meta` 才踩。
6. 上传期不校验、到 precheck/import 才暴露：`FileController` 已能读到文件字节，可在上传时前置读 `_meta` 并直接 400；属体验优化，另立任务为宜。
7. 残余风险（按范围明确排除，仅登记）：无 `_meta`、或表头无法映射时仍会静默 0 行成功 —— 原缺陷类的另一半未闭环；审计建议里的"未知列 WARNING/失败"应单独立项。
8. 合流顺序：本任务在 11 个 `@Tool` 注解行上新增了第二行，下一任务要改同批注解的 risk/description，必然文本冲突；建议本改动先落，描述/风险任务基于其 rebase，避免手抄丢 `resultConverter`。
9. 无 `docs/` 变更（报告已声明）；R3-01/R3-25 的看板收口记录应在合流时补，避免"代码已修、账未销"。
