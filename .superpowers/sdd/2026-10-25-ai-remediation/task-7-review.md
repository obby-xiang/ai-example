# Task 7 独立复审 — 表单与知识库健壮性

复审对象：`task-7-brief.md` / `task-7-report.md` / `task-7.patch`（851 行，7 个文件）
复审方式：只读。未复跑任何套件（遵循"不重跑宽套件"约束），改用**证据链核验 + 源码/调用点静态审计**。
复审日期：2026-10-25

---

## 0. 结论

| 项 | 判定 |
|---|---|
| **Spec Compliance** | **PASS** — brief 的 5 条要求全部落地，且每条都有可核验的 RED→GREEN 证据；`FormRenderer.vue` 不改的决定成立（有 1 条因此产生的后续项，见 I2） |
| **Task Quality** | **PASS（含 3 条 Important、10 条 Minor）** — 无 Critical；改动最小、注释解释了不变量、测试有正反例；扣分项集中在"长期重试节奏""级联子值无清空入口""两个新可见码缺 i18n 键" |
| **合并建议** | **可以合并**（`git apply` 干净、主树源码未动、补丁与被测代码逐字节一致）。建议合并前顺手做 4 行 i18n 补键（I3）+ 1 行注释订正（M1）；I1 / I2 可作为后续批 |

**Blocker：无。**

---

## 1. 逐条对照 brief

| # | 要求（brief Step 1 的可观测形态） | 判定 | 证据 |
|---|---|---|---|
| 1 | 级联 enum/multi_select 在父值缺失时不得任意通过 | **PASS** | 空集与"无法枚举"分离：`GenerativeFormRules.java:1366-1390`（`sourceId`/无来源 ⇒ `null`，map 无分支 ⇒ 空集）、`:1166` / `:1193`（`options != null` 才严格成员校验）；前端同判据 `form-schema.ts:559-585`（`resolvableOptions`，`sourceId` ⇒ `null`）、`:763` / `:775`。测试 `GenerativeFormRobustnessTest.java:42,50,60,70,76,90`、`gf-schema-robustness.spec.ts:39-84` |
| 2 | 非有限 `step` 必须是字段错误，不是 500 | **PASS** | schema 期 `finiteOrNull`（`GenerativeFormRules.java:1467-1473`）+ `numberBoundIssues`（`:584-600`）；回灌期非有限值 ⇒ `VALUE_TYPE_MISMATCH` 并在进入 `BigDecimal` 前 `break`（`:1098-1104`）；非有限 step/min/max ⇒ `FIELD_STEP_INVALID` / `FIELD_NUMBER_BOUND_INVALID`（`:1105-1127`）。RED 的 `NumberFormatException` 栈正落在**改前**主树 `GenerativeFormRules.java:1114`（`BigDecimal.valueOf(step)`） |
| 3 | 空 KB manifest 不得 ready、不得清空旧索引 | **PASS** | `KbManifest.java:46-52` 先查 `docs.isEmpty()` ⇒ `KbSourceException`；既有 fail-open 路径自动生效（`KbService.java:252` 校验 + `:303-309` catch → `lastError` + 保留旧快照）。测试 `KbLifecycleRobustnessTest.java:40,57` |
| 4 | 重建失败必须仍可重试 | **PASS** | 只由 `refreshNow` 成功路径记版本（`KbService.java:282`、`:294`）；轮询不再预记（`:206-213`）；首刷不再事后无条件记（`:155-160`）。测试 `KbLifecycleRobustnessTest.java:77-101`（RED：仅 1 次尝试；GREEN：≥2 次后放行即 ready） |
| 5 | stop 后 acquire 不得自旋 | **PASS** | `stop()` 先摘引用再退休（`KbService.java:168-179`）；`acquire()` 加停机退出与空引用退出（`:801-813`）。测试 `KbLifecycleRobustnessTest.java:105-127`（RED：3s 未返回） |
| 6 | `FormRenderer.vue` 不改是否有理 | **PASS（有后续项）** | 见 §3.6：渲染器全部校验委托给 `form-schema.ts`（`FormRenderer.vue:246-266`、`:595-597`、`:783`），无重复成员校验；本次缺陷都在规则层。副作用见 I2 |

---

## 2. 证据链核验（未重跑宽套件）

1. **补丁可用性**：在主体树执行 `git apply --check -p1 .superpowers/sdd/.../task-7.patch` ⇒ **exit 0**；补丁涉及的 7 个路径在 `git status` 里干净（主树未提交项是 ConfirmGate/SpToolCallingManager/ResumeService 等其它批），基线正确。
2. **补丁 == 被测代码**（关键真实性检查）：把主体树的 4 个改前文件放进临时 git 仓 → 打本补丁 → 与 `<隔离副本>/_verify-20261025-form-kb` 的 7 个文件逐一 `diff` ⇒ **7/7 完全一致（SAME）**。即 RED/GREEN 日志描述的就是这份补丁，不存在"日志跑的是另一版"。
3. **RED**：`red-final.log` = `Tests run: 15, Failures: 11, Errors: 0`（`GenerativeFormRobustnessTest` 7/11 失败、`KbLifecycleRobustnessTest` 4/4 失败）；失败集合与报告 §3.1 一一对应（3 条级联、2 条 schema 非有限、2 条抛 `NumberFormatException`、2 条空清单、1 条版本沉默、1 条自旋）。报告未夸大连带项：4 个"正例锁定"用例在 RED 期就是绿的，已核对。
4. **RED 跑的是改前源码**：`NumberFormatException` 栈含 `GenerativeFormRules.valueIssues(GenerativeFormRules.java:1114)`，该行在主体树（改前）正是 `.divide(BigDecimal.valueOf(step), …)`；KB 两处断言栈行 `KbLifecycleRobustnessTest.java:124`（自旋）/ `:200`（重试超时）与**交付版测试文件**行号逐字吻合 ⇒ 日志与交付测试同源。
5. **GREEN**：`green-final.log` = `Tests run: 77, Failures: 0, Errors: 0`；分类行 = ProBatch 20 + Robustness 11 + SchemaRules 25 + KbLifecycle 4 + Incremental 5 + Index 12 = **77** ✓。前端 `fe-red.log` = `3 failed | 2 passed (5)`（失败正是三条级联，断言信息 `expected [] to deeply equal …`）；`fe-green-final.log` = `5 files / 47 tests`（新 spec 5 + 相邻 42）✓。
6. **类型检查**：`fe-tsc.log` 为 0 字节。这只能证明"无诊断输出"，**日志本身不含退出码**，严格说"exit 0"是报告的自述而非日志可自证项（见 M9）。
7. **时间线**：red 19:49 → green 19:53 → fe 19:47/19:53 → patch 19:54 → report 19:56，无"报告先于证据"的倒置。

---

## 3. 按复审清单的语义审计

### 3.1 空集 vs 无法枚举（map 级联 vs 外部源失败）

- 后端：`runtimeOptionValues` 只有三种出口 —— 静态 `options`（可能为空集）、`optionsFrom.map`（父值缺失/无分支 ⇒ **空集**）、以及 `sourceId` 或"根本没有可枚举来源" ⇒ **null**。调用方只在 `options != null` 时做成员校验，因此"父值没选"= 拒绝、"规则层看不到"= 跳过，语义分得干净（`GenerativeFormRules.java:1366-1390`、`:1166`、`:1193`）。
- 前端：`resolvableOptions` 同构 —— 有 `options`/非空 `external` ⇒ 数组；`sourceId` 未解析或 `external` 为空 ⇒ `null`；有 `map` 无父值/无分支 ⇒ `[]`（`form-schema.ts:559-585`）。对外仍以 `resolveFieldOptions(...) ?? []` 暴露，渲染契约不变（`:542-547`）。
- **外部源失败不误判**：`external[key] = []`（父值空/请求失败/数据源返回空）走 `null` 分支跳过，不会把"数据源暂时不可用"变成"用户填的全错"；后端 `sourceId` 同样一律跳过。两侧一致，且比"空集即拒绝"更保守 —— 正是这一点让报告 §5.4 的口径成立。
- 遗留不对称（未由本补丁引入）：`external` 已解析时前端严格校验成员，后端对 `sourceId` 永远跳过（M9）。

### 3.2 非有限数值与默认值

- 提交值：`!Double.isFinite(number)` ⇒ `VALUE_TYPE_MISMATCH` 并短路，**不会**进 `BigDecimal`（`GenerativeFormRules.java:1098-1104`）。
- schema min/max：非有限 ⇒ `FIELD_NUMBER_BOUND_INVALID`，且不再落到 `min > max` 分支（`:592-600`）；step：非有限或 ≤0 ⇒ `FIELD_STEP_INVALID`。
- 回灌期对"schema 里 min/max/step 存在但不可用"的情形（只能来自旧版闸门放行的待决表单）给字段错误而不做算术（`:1105-1127`）。
- 前端本来就有有限性闸：`asFiniteNumber`（`form-schema.ts:135`）、`buildInitialValues`（`:413`）、number 值复核（`:727-729`）。`parseFormSchema` 会把非有限 `step` 直接丢弃 ⇒ 前端不会重复报错。
- **未覆盖**：`defaultValue` 为 `1e999` 时 `defaultValueIssues` 仍按 number 接受（`GenerativeFormRules.java:701-706`），前端 `initialValueOf` 会静默丢弃 ⇒ 字段空白而非 schema 错误（M2；与 500 无关，报告 §5.5 已登记）。

### 3.3 空 manifest 与既有源契约

- 既有契约**没有**"合法空知识库 = ready"这一态：
  - `KbManifest` 类注释"不能当作空知识库"、`parse` 注释"坏 JSON / **docs 为空** / id 重复都视为源不可用"（`KbManifest.java:12-16`、`:33`）—— 修复是把注释落成代码，不是新增语义。
  - `docs/知识库维护手册.md:219`：缺 manifest ⇒ "知识库保持加载中"；空目录 ⇒ 播种样例（`:217`）。
  - 空目录态在渲染侧有专门文案（`KbPromptSection.java:80-104`，`else` 分支 :97 起：明确说"不可用"而非"加载中"），说明"无目录可用"是被设计过的**未就绪**态，而非 ready 态。
- `validate()` 的生效面：`LocalDirKbSource:68`、`HttpKbSource:99`、`CachedKbSource:293`、`KbSampleSeeder:143`、`KbService:252` —— 一处收紧覆盖全部源；且都是"失败即保留旧索引"的统一语义。
- `KbManifest.empty()` 全仓**无调用方**（已 grep），不存在"防御性空清单被新校验打死"的隐藏路径。
- 既有测试无一依赖"空 docs 可成功"：`KbCacheTest` 里的 `{"docs":[]}` 只做字符串存取、不 `validate`；`FakeKbSource` 的空实例在 `CachedKbSourceTest` 里只作为"永不被调用的 delegate"（用例断言 `manifestCalls == 0`）。**产品面确认**（"真的存在合法空知识库运营场景吗"）仍建议业务方点头，报告 §5.2 已如实标注。

### 3.4 snapshot / retire / refresh / stop 并发

- 引用计数协议本身是自洽的：`tryAcquire` 先 inc 再查 retired（retired 则 `release` 归还），`retire` CAS + `refs==0` 才 `close`，`release` 在"归零且已退休"时 `close`，`close` 有 `closed` CAS 幂等（`KbService.java:915-953`）。因此"取到手后立刻被 retire"不会读到已关闭的 reader。
- `acquire()` 的不变量成立：`this.snapshot` 只会指向**活快照**或 `null`（`refreshNow` 只在换上新快照后才 retire 旧的；`stop()` 先置 null 再 retire），所以非空 + `tryAcquire false` 只可能是"刚被换掉"，重读一次必得新快照；停机由 `stopped` 兜住（`:801-813`）。
- `stop()` 顺序正确：`stopped=true` → 关调度 → 摘引用 → retire（`:168-179`），既保证读侧不再进入，也让 `search/readDoc/lookup`（都走 `acquire`）立刻退回 `NOT_READY`。
- 仍未闭合的边角（非本补丁引入、也不因本补丁变差）：`refreshNow` 是 `synchronized` 但 `stop()` 不是，二者交错时 `refreshNow` 可在 `stop()` 之后把 `snapshot` 重新指向新快照 ⇒ 该快照永不 retire（reader/目录泄漏），且 `ready()`（`:743`）不看 `stopped`，`POST /kb/reload` 之后停机的服务会重新自报 ready（M8）。

### 3.5 错误码与中英 i18n 回落

- 渲染层回落逻辑正确：`issueText` 先 `te('ai.gf.errors.'+code)`，无键则用后端/本地给的 `message`（`FormRenderer.vue:718-728`）⇒ **不会空白**。
- 但 `FIELD_STEP_INVALID` / `FIELD_NUMBER_BOUND_INVALID` 在 `zh-CN.json:344-368` 与 `en-US.json:344-368` **都没有键**：英文界面在这两类错误上会显示中文句子（I3）。可达性：新表单已在 schema 期被拦（不会下发），只有**旧版闸门放行的待决表单**回灌时才出现 —— 低频但真实。
- 可重试判定不受影响：`NON_RETRYABLE_CODES`（`form-schema.ts:840`）不含这两个码 ⇒ 卡片保持可重试（与 M4 相关）。
- 前后端同码：`VALUE_OUT_OF_OPTIONS`、`VALUE_TYPE_MISMATCH` 都有中英键，级联新拒绝在两种语言下文案正常。

### 3.6 `FormRenderer.vue` 不改是否成立

成立，理由可核验：

- 组件纪律写明"取值与提交前复核只走 `buildInitialValues / checkFormIssues / resolveFieldOptions`"（`FormRenderer.vue:246-249`），实测调用点 `:596`（选项）、`:783`（提交复核），无第二份成员校验逻辑（全仓 `VALUE_OUT_OF_OPTIONS` 只出现在 `form-schema.ts:763/775`）。
- 五条缺陷的判据全部在 `GenerativeFormRules` / `form-schema.ts`，渲染层改与不改都不影响判据 —— 改它反而会把"空集即拒绝"的语义复制两份。
- 唯一与渲染层有关的后果是 I2（多选级联子值无清空入口）：这是"规则收紧 + 渲染器不清理依赖值"的交叉项，属**新暴露的可用性问题**，不是渲染器里存在未修缺陷。

### 3.7 测试真实性

- 用例与缺陷一一对应，且都带"正例锁定"：`cascadeValueInsideResolvedBranchIsAccepted`、`finiteStepStillValidatesAsBefore`、`sourceIdCascadeStaysUnchecked…`；KB 侧 `emptyManifestKeepsTheOldIndexInsteadOfWipingIt` 同时断言"旧索引仍 ready + 检索仍有命中"，比只断言 `ready()==false` 强得多。
- 断言强度无"空断言"：级联用 `containsExactly(key)` + `containsExactly(code)`；自旋用 3s 超时探针并把缺陷写进消息。
- 一处**验证缺口**（M7）：重试用例只证明"失败后会再来一次"，没有证明"成功后会停止再来"（`KbLifecycleRobustnessTest.java:92-100` 之后无"attempts 不再增长"的断言）。若将来 `refreshNow` 成功路径的版本记录（`KbService.java:282/294`）被误删，该用例仍会绿，而线上会每 30s 重建一次。
- 报告口径与实测一致；唯一数字笔误：§4 写"这 6 个被改/新增路径"，实际 7 个（M10）。

---

## 4. 问题清单

### Critical
无。

### Important

**I1 — 重建永久失败后无退避，变成每 30s 一次全量重建 + 一条 WARN**
`backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:206-213`（轮询不再预记版本）+ `:303-309`（每次失败 `log.warn`）；节奏由 `backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java:1472`（`versionPollInterval = 30s`）决定。
后果：一次"改了 manifest 但建索引必失败"的故障（磁盘满、目录权限、Lucene 异常）会让本实例**每 30s** 重走 `fetchManifest → 逐篇 fetchContent → 全量 build`，并打一条 WARN；改前是"一次失败后沉默到 10m 定时刷新"。brief 只要求"可重试"，此实现满足，但缺少退避/上限是可预期的运维噪声与负载放大器。
建议：失败时用"尝试次数 + 退避"（如按 `min(poll*n, refreshInterval)` 递增）或对同版本最多重试 N 次后交回定时刷新；至少把 WARN 降为"首次 + 每 N 次"。

**I2 — 多选级联的"陈旧子值"没有任何 UI 清空入口**
`frontend/src/types/form-schema.ts:759-780`（空集 ⇒ `VALUE_OUT_OF_OPTIONS`）+ `frontend/src/components/AiPanel/FormRenderer.vue:139-152`（multi_select 只渲染**当前**选项，无"清空"控件）+ `:557-575`（`setValue` 只套 `defaultFrom`，不清理依赖字段）。
复现：选 `country=CN` → 勾选 `defs=[CURRENCY]` → 把 `country` 改成无分支的 `US` ⇒ 复选框整组消失、模型里仍是 `['CURRENCY']` ⇒ 提交被拒且用户无法直接取消勾选（只能把父值改回去再取消，或重开卡片丢输入）。enum 级联不受影响：select 恒有空白占位项（`FormRenderer.vue:136`），可直接清空。
建议（后续批）：父值变化时按新的可解析选项集裁剪依赖字段值（渲染器侧一处 watcher），或在多选控件上加"清空"按钮。不阻塞合流：可恢复、仅限多选级联。

**I3 — 两个新可见码缺中英 i18n 键**
`frontend/src/locales/zh-CN.json:344-368`、`frontend/src/locales/en-US.json:344-368` 缺 `FIELD_STEP_INVALID`、`FIELD_NUMBER_BOUND_INVALID`；回落路径（`FormRenderer.vue:718-728`）会把后端中文 message 直接显示给英文用户。
建议：照 `VALUE_STEP_MISMATCH` 体例补 4 行（"「{label}」的步长设置不可用，请重新生成表单" / "\"{label}\" has an invalid step setting; please regenerate the form"）。可达性仅限旧待决表单，但改造成本 4 行。

### Minor

- **M1** `KbService.java:801-813`：注释与报告 §1 行 5 都说"有界循环 / 最多重读一次"，代码实际是 `while (!stopped)` 无重试预算，靠"非空 ⇒ 活快照"的不变量而不死循环。建议显式写成"最多重读 2 次，超限返回 null"，让代码与声明一致，也避免未来有人把它改成"允许 retired 快照留在字段里"时复活自旋。
- **M2** `GenerativeFormRules.java:701-706`：number 的 `defaultValue` 只查 `isNumber()`，`1e999` 通过 schema；前端 `form-schema.ts:413` 静默丢弃 ⇒ 用户看到空字段而非"默认值非法"。建议复用 `finiteOrNull`，与 step/min/max 同口径（报告 §5.5 已登记为后续）。
- **M3** `KbManifest.java:29`：`empty()` 现在是"毒值"（任何后续 `validate()` 必抛），且全仓无调用方。建议删除或改名为 `unavailable()` 并在注释里说明不可再入校验。
- **M4** `GenerativeFormRules.java:1107-1127`：`FIELD_STEP_INVALID` / `FIELD_NUMBER_BOUND_INVALID` 在回灌期被归到**单个字段**，但用户改不动 schema 里的 step/min —— 这类错误不可修复却仍被 `NON_RETRYABLE_CODES`（`form-schema.ts:840`）当可重试，于是"提交→同一条错→再提交"。建议这类"schema 自相矛盾"用表单级码（如 `RESULT_SCHEMA_UNAVAILABLE`）下发，让卡片给出"重新生成"而不是"重试"。仅影响旧待决表单。
- **M5** 空必填 enum 的码不一致：后端 `""` ⇒ `VALUE_OUT_OF_OPTIONS`（`GenerativeFormRules.java:1166`），前端 `""` ⇒ `VALUE_REQUIRED_MISSING`（`form-schema.ts:441`、`:682-690`）。仅文案差异，直连 API 可见。
- **M6** 数值父值的 `asText()` 口径差：后端 `map.get(parentValue.asText())`（`GenerativeFormRules.java:1384`）把 JSON `5.0` 读成 `"5.0"`，前端 `String(parentValue)`（`form-schema.ts:578-582`）得 `"5"`。父字段为 number 且 map 键写成整数时会两侧不等；在"无分支即拒绝"生效后表现为"UI 有该选项、后端判不在选项内"。建议统一为规范化数值键（或文档明确 map 键只支持文本父值）。
- **M7** `KbLifecycleRobustnessTest.java:77-101`：未断言"成功后不再重试"（见 §3.7）。建议补 `assertThat(source.attempts()).isEqualTo(attemptsAtReady)` 之类的一拍后再查。
- **M8** `KbService.java:743` + `:233-312` + `:168-179`：`ready()`/`status()` 不看 `stopped`，`stop()` 与 `refreshNow` 交错时新快照不会被 retire。停机后若再触发一次 `refreshNow`（管理端点），服务会重新自报 ready 且泄漏一份 reader/目录。属既有面，但本批正好在改这里，建议一并加 `if (this.stopped) return false;`。
- **M9** 报告的 tsc 证据是**空日志**（`fe-tsc.log` 0 字节），不含退出码，无法从工件自证 exit 0；建议后续把 `echo "exit=$?"` 一并落盘。
- **M10** 报告 §4 写"这 6 个被改/新增路径"，实为 7 个（§2 已正确写 7 个文件）。纯文档笔误。

### 附：跨批一致性提示（不计分）
`KbService.start()` 在 `stop()` 后不可重入（`stopped` 不回置）、`KbManifest.empty()` 的存在、`HttpKbSource` 空发布语义 —— 报告 §5.5/§5.6 均已如实登记，本批不处理是合理取舍。

---

## 5. 回归面抽检（不跑套件，用静态核对替代）

| 面 | 核对方式 | 结论 |
|---|---|---|
| 既有后端表单测试是否依赖"宽松级联" | 全仓 grep `optionsFrom` ⇒ 仅 `GenerativeFormProBatchRulesTest`（已跑绿） | 无冲突 |
| 既有断言是否依赖空集放行 | grep `VALUE_OUT_OF_OPTIONS` ⇒ 仅 `GenerativeFormProBatchRulesTest:330`（已跑绿） | 无冲突 |
| 空 docs 收紧是否打到别的测试 | grep 测试里的空 manifest：仅 `KbCacheTest` 的字符串存取（不 `validate`）；`FakeKbSource` 空实例在 `CachedKbSourceTest` 中从不被调用（用例断言 `manifestCalls==0`）；`KbLocalSourceTest` / `KbSampleSeederConcurrencyTest` 用 20 篇样例 | 无冲突 |
| 未跑的相关类是否有空语料 | `KbTestSupport.corpus(...)` 无空 map 调用；`KbSampleSeedingTest:122` 解析的是 20 篇样例 | 无冲突 |
| 前端相邻 spec | `gf-renderer/gf-schema-rules` 已在 47 例内跑绿；`gf-i18n` 只断言 `VALUE_REQUIRED_MISSING` 存在，不受新码影响 | 无冲突 |

---

## 6. 合并建议

**合并（Merge）**，按"合并前可选、合并后必跟"分层：

- 合并前（低成本、直接相关，共约 6 行）：I3 补 4 行 i18n 键；M1 把 `acquire()` 的"有界"落成代码（或改注释）。
- 合并后（后续批）：I1 重试退避与日志收敛；I2 级联依赖值裁剪 / 多选清空入口（届时会真正改到 `FormRenderer.vue`，与"本批不动渲染器"的取舍不冲突）；M2 / M3 / M4 / M6 / M7 / M8 按 Minor 排期。

不建议因 I1/I2 退回：两者都不是"要求未实现"，而是"实现了但留有可预期的运行/交互代价"，且都有清晰、局部的后续修法。
